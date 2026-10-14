package dev.librepocket.session

import android.util.Log
import androidx.room.withTransaction
import dev.librepocket.redact.Redactor
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Room-backed [SessionStore] (M4, spec §8).
 *
 * Write-path guarantees (spec §8.4):
 * - every event text passes [Redactor.redact] before insert;
 * - image bytes never reach the DB (only [TranscriptEvent.imagesOmitted]);
 * - `seq` is assigned as (max+1) inside the same transaction as the insert,
 *   with a [Mutex] as the second line of defence for same-session concurrency.
 *
 * The DAO is blocking Java; all DB access is confined to [Dispatchers.IO].
 * Prune auto-runs every [AUTO_PRUNE_EVERY] appends with the default policy;
 * callers needing other policies invoke [prune] explicitly. Auto-prune is
 * best-effort maintenance: it runs after the append transaction commits, and
 * its failure — including [CancellationException] observed in the post-commit
 * tail (counter or sweep) — only emits [Log.w]; the committed rowId is still
 * returned, so a post-commit maintenance failure never fails the durable core
 * ack, never writes an INTERRUPTED mark, and never affects
 * `hasDurableFailures`/`flushDurable`. Only a CE actually thrown by the
 * entry gate or thrown inside the append transaction itself propagates
 * (rollback → row absent → ack must fail); a bare cancel request inside the
 * tx (flag set, nothing thrown) cannot interrupt the blocking body, so it
 * always commit-wins and the deferred delivery is swallowed after the flag
 * is set.
 *
 * R6-4 commit→return boundary, corrected by R8-5: the R6-4 KDoc claimed
 * the [AtomicReference] flag below is published with no suspension point
 * between the transaction return and the set, so any CE at the resume must
 * be post-commit. That premise was false — `db.withTransaction` is itself
 * `suspend`, so the SQLite COMMIT (inside) and the flag set (after) straddle
 * a cancellable resume point (`withTransaction` return → `withContext`
 * resume): a cancel landing after the COMMIT but before the resume threw
 * with the flag still null, misreporting a durable commit as a pre-commit
 * failure (row present, ack failed; R8-5 `RoomCommitSetGapCancelTest` pins
 * the fingerprint pre-fix).
 *
 * R8-5 invariant: the transaction now runs via blocking
 * [androidx.room.RoomDatabase.runInTransaction] on the IO thread, so
 * COMMIT→`committed.set` stays synchronous on one thread with no suspension
 * point in between — a framework-delivered CE can only land after the flag
 * is set (swallow with Log.w + return the durable id) or before the first
 * write. Request vs delivery: a `cancel()` inside the blocking body only sets
 * the flag, so after the first write the region is de facto NonCancellable
 * and deliberately commit-wins (SQLite COMMIT atomicity + durable-ack
 * priority) — rollback stays reserved for the pre-write gate and a CE
 * actually thrown inside the tx. The latter keeps the rollback contract
 * through an explicit non-suspending [ensureActive] gate at the transaction
 * entry (a cancel racing the IO dispatch still propagates with zero rows;
 * the R6-4 pre-commit test pins this), never a suspension. The prune tail is
 * deliberately NOT covered by this catch (and never by NonCancellable):
 * the sweep is O(sessions) and must not hold the shutdown bound.
 * Deliberately not a blanket `NonCancellable` around the commit region
 * either: the blocking body already delivers nothing mid-transaction, so it
 * would only mask the pre-write gate (turning a must-fail pre-commit ack
 * into silent commit-wins) and hide the explicit commit-vs-rollback
 * discriminant.
 *
 * Notes:
 * - seq gaps are legal (prune deletes old rows; import preserves file seqs);
 *   only strictly-increasing order + uniqueness are required.
 * - session titles are redacted on every current write path (create/import),
 *   and re-redacted on every list/get read, so rows written before write-time
 *   redaction existed are still safe to display.
 */
class RoomSessionStore(
    private val db: LibrePocketDb,
    private val clock: () -> Long = System::currentTimeMillis,
) : SessionStore {

    private val dao: SessionDao get() = db.sessionDao()
    private val appendMutex = Mutex()
    // Guarded by [appendMutex]: incremented on every committed append, reset
    // whenever an auto-prune sweep is attempted (success or failure).
    private var appendsSincePrune = 0
    /**
     * S4-F4 prune-marker suspect (hotfix b): sessionIds whose prior [prune]
     * deleted rows (marker-first pair may have been cut: marker pruned,
     * assistant kept, dropped==0). [loadModelHistory] treats them as
     * reasoning-suspect (all kept assistants fail-closed). Memory-only (a
     * restart drops it – noted follow-up); pair-aware prune stays Target (a).
     * Concurrent set, no suspension while held.
     */
    private val pruneSuspectSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * R6-4 test seam: invoked synchronously on the IO thread after the
     * commit flag is published but before the commit region returns (i.e.
     * inside the cancellable [withContext] resume window). Tests set it to
     * throw [CancellationException] to deterministically simulate a
     * commit-after-commit resume cancel; production leaves it `null`.
     * A CE thrown by the hook is post-commit by construction and is
     * swallowed exactly like a resume cancel.
     */
    internal var afterCommitHookForTest: (() -> Unit)? = null

    /**
     * R8-5 test seam: invoked synchronously inside the append transaction,
     * after [SessionDao.touchSession] but before the transaction block
     * returns its row(s). Receives the innermost transaction-running
     * coroutine's [Job] so tests can `cancel()` it without throwing: the
     * framework then delivers the [CancellationException] naturally at the
     * next suspension point. Production leaves it `null`. Cancelling only
     * this inner Job keeps the blast radius inside the commit region — the
     * outer caller (direct `runBlocking` or the sink writer) is never
     * cancelled by the test.
     */
    internal var insideTxAfterInsertHookForTest: ((Job?) -> Unit)? = null

    override suspend fun createSession(title: String, model: String): String =
        withContext(Dispatchers.IO) {
            require(model.isNotBlank()) { "model must not be blank" }
            val now = clock()
            val id = UUID.randomUUID().toString()
            dao.insertSession(
                SessionEntity(
                    id,
                    Redactor.redact(title).text,
                    model,
                    now,
                    now,
                    false,
                ),
            )
            id
        }

    override suspend fun appendEvent(event: TranscriptEvent): Long {
        val prepared = prepareEvent(event)
        // R8-5: commit→set gap guard. [committed] is published with a plain
        // synchronous set on the IO thread straight after the BLOCKING
        // transaction returns — COMMIT→set runs on one thread with no
        // suspension point in between (the R6-4 `withTransaction` resume
        // hole is gone), so any CE delivered at the withContext resume is
        // observably post-commit. A CE before the first write still
        // propagates via the entry [ensureActive] gate below (rollback →
        // row absent → ack must fail).
        val committed = AtomicReference<Long?>(null)
        val rowId = try {
            appendMutex.withLock {
                withContext(Dispatchers.IO) {
                    // Pre-commit gate (non-suspending, no suspension point):
                    // a cancel racing the IO dispatch still fails before any
                    // write, preserving the pre-commit → zero-row contract.
                    ensureActive()
                    val txJob = coroutineContext[Job]
                    val id = db.runInTransaction(
                        Callable<Long> {
                            // Same-tick entry gate for a cancel that lands
                            // after the dispatch above but before the first
                            // write. [Job.ensureActive] never suspends, so
                            // COMMIT→set below stays gap-free.
                            txJob?.ensureActive()
                            require(dao.sessionById(event.sessionId) != null) {
                                "unknown session"
                            }
                            val max = dao.maxSeq(event.sessionId)
                            // D15-5: seq space exhausted (imported
                            // Long.MAX_VALUE): refuse fail-closed. The
                            // (sessionId, seq) index is non-unique and
                            // MAX+1 overflows, so neither the DB nor the old
                            // nextSeq expression would catch the wrap.
                            check(max < Long.MAX_VALUE) { "seq exhausted" }
                            val seq = max + 1
                            val row = dao.insertEvent(
                                TranscriptEventEntity(
                                    0,
                                    event.sessionId,
                                    seq,
                                    event.runId,
                                    event.kind,
                                    prepared.text,
                                    prepared.truncated,
                                    event.imagesOmitted,
                                    event.createdAt,
                                    event.isPartial,
                                    prepared.reason,
                                    event.parentRunId,
                                    event.attemptIndex,
                                    event.isFinal,
                                ),
                            )
                            dao.touchSession(event.sessionId, clock())
                            insideTxAfterInsertHookForTest?.invoke(txJob)
                            row
                        },
                    )
                    committed.set(id)
                    afterCommitHookForTest?.invoke()
                    id
                }
            }
        } catch (e: CancellationException) {
            // Post-commit resume cancel (commit already durable): swallow
            // with Log.w and return the durable rowId so the core ack stays
            // a success. Flag unset means pre-commit/in-tx (mutex wait, IO
            // dispatch, or rollback) — rethrow so the ack correctly fails.
            val known = committed.get()
            if (known != null) {
                Log.w(TAG, "append committed but resume cancelled; returning durable rowId", e)
                known
            } else {
                throw e
            }
        }
        // R4-6: the counter lives under the same mutex as the seq-assigning
        // transaction, so concurrent appends cannot lose increments or double
        // -fire the sweep. Only the decision is made here; the sweep itself
        // stays outside the mutex (and outside the transaction above), exactly
        // as before.
        // R5-6: the whole post-commit tail (counter + sweep) is best-effort
        // maintenance after the row above already committed: a
        // CancellationException observed here (mutex wait or sweep) only emits
        // Log.w and the committed rowId is still returned, so a post-commit
        // cancel never fails the durable core ack and never kills the writer.
        // Structured-concurrency teardown is deferred by at most one
        // successful ack (the Job cancelled flag stays set; the next channel
        // receive still seals normally). Deliberately no
        // withContext(NonCancellable): the sweep is O(sessions) and must not
        // hold the shutdown bound. Only a CE actually thrown by the entry gate
        // or thrown inside the transaction above propagates (rollback → row
        // absent → ack must fail); a bare in-tx cancel request commit-wins
        // (blocking body, deferred delivery swallowed after the flag is set).
        try {
            val shouldPrune = appendMutex.withLock {
                appendsSincePrune++
                if (appendsSincePrune >= AUTO_PRUNE_EVERY) {
                    appendsSincePrune = 0
                    true
                } else {
                    false
                }
            }
            if (shouldPrune) {
                try {
                    prune(PrunePolicy())
                } catch (e: Exception) {
                    // Best-effort maintenance: the row above already committed
                    // and its rowId is still returned below (this also swallows
                    // post-commit cancellation), so the durable core ack stays
                    // a success and no INTERRUPTED mark is written. The
                    // counter was already reset, so the failed sweep is
                    // retried (throttled) after the next AUTO_PRUNE_EVERY
                    // appends instead of retrying hot on every subsequent
                    // append.
                    Log.w(TAG, "auto-prune failed; append already durable, will retry later", e)
                }
            }
        } catch (e: CancellationException) {
            // Post-commit counter wait cancelled after the row committed:
            // same best-effort outcome as a failed sweep — ack stays a
            // success, writer stays alive.
            Log.w(TAG, "auto-prune counter cancelled; append already durable, will retry later", e)
        } catch (e: Exception) {
            Log.w(TAG, "auto-prune failed; append already durable, will retry later", e)
        }
        return rowId
    }

    /**
     * R5-4 atomic batch (single DB transaction): every event is redacted /
     * truncated / seq-assigned exactly as in [appendEvent], but the inserts
     * share one blocking transaction under [appendMutex], with one
     * [SessionDao.touchSession] per touched session inside the same
     * transaction. A failure (unknown session, unknown kind, I/O error,
     * cancellation) rolls back the whole batch — including the touch — so
     * the fork ledger holds all rows or zero. Deliberately inlines the DAO
     * writes instead of delegating to [appendEvent]: re-entering per-row
     * transactions would nest (and Room flattens nested transactions, losing
     * the all-or-nothing boundary) and would touch/prune per row.
     *
     * Auto-prune accounting counts per ROWS toward [AUTO_PRUNE_EVERY]
     * (S4-F10: `appendsSincePrune += events.size`, not one per bulk), and the
     * sweep (if due) runs once after commit, exactly like the single-row path:
     * a committed bulk is never un-done by a prune failure ([Log.w] only,
     * including post-commit cancellation, rowIds still returned). Head-anchored
     * markers (model-switched row 1) are oldest-first pruned first like any old
     * row; per-row accounting keeps the prune horizon row-accurate so a full
     * snapshot's head-marker lifetime matches the single-row path. Only a CE
     * actually thrown by the entry gate or thrown inside the transaction itself
     * propagates (rollback → zero rows → ack must fail); a bare in-tx cancel
     * request commit-wins (blocking body, deferred delivery swallowed after the
     * flag is set).
     * R8-5: like [appendEvent], the bulk runs via blocking
     * [androidx.room.RoomDatabase.runInTransaction] with the same entry
     * [ensureActive] gate, so the COMMIT→flag resume hole is closed here
     * too; a CE delivered on the commit→return resume (after the
     * transaction commits, before the rowIds reach the caller) is swallowed
     * via the same committed-flag discriminant; the returned list is the
     * defensive copy published at commit time.
     */
    override suspend fun appendEvents(events: List<TranscriptEvent>): List<Long> {
        if (events.isEmpty()) return emptyList()
        // Fail fast before any write: a bad kind aborts with zero rows.
        val prepared = events.map { prepareEvent(it) }
        // R8-5: batch analogue of the single-row commit→set gap guard (see
        // [appendEvent]): blocking transaction + entry gate, so COMMIT→set
        // has no suspension point. The published value is a defensive copy:
        // the transaction-local [ids] buffer must never escape mutably.
        val committed = AtomicReference<List<Long>?>(null)
        val rowIds = try {
            appendMutex.withLock {
                withContext(Dispatchers.IO) {
                    ensureActive()
                    val txJob = coroutineContext[Job]
                    val ids = db.runInTransaction(
                        Callable<List<Long>> {
                            txJob?.ensureActive()
                            val ids = ArrayList<Long>(events.size)
                            for (index in events.indices) {
                                val event = events[index]
                                val ready = prepared[index]
                                require(dao.sessionById(event.sessionId) != null) {
                                    "unknown session"
                                }
                                val max = dao.maxSeq(event.sessionId)
                                // D15-5: seq space exhausted (imported
                                // Long.MAX_VALUE): refuse fail-closed. The
                                // (sessionId, seq) index is non-unique and
                                // MAX+1 overflows, so neither the DB nor the old
                                // nextSeq expression would catch the wrap.
                                check(max < Long.MAX_VALUE) { "seq exhausted" }
                                val seq = max + 1
                                ids.add(
                                    dao.insertEvent(
                                        TranscriptEventEntity(
                                            0,
                                            event.sessionId,
                                            seq,
                                            event.runId,
                                            event.kind,
                                            ready.text,
                                            ready.truncated,
                                            event.imagesOmitted,
                                            event.createdAt,
                                            event.isPartial,
                                            ready.reason,
                                            event.parentRunId,
                                            event.attemptIndex,
                                            event.isFinal,
                                        ),
                                    ),
                                )
                            }
                            // One touch per session, inside the transaction: a bulk
                            // failure rolls the touch back together with the rows.
                            val touched = HashSet<String>()
                            for (event in events) {
                                if (touched.add(event.sessionId)) dao.touchSession(event.sessionId, clock())
                            }
                            insideTxAfterInsertHookForTest?.invoke(txJob)
                            ids
                        },
                    )
                    committed.set(ArrayList(ids))
                    afterCommitHookForTest?.invoke()
                    ids
                }
            }
        } catch (e: CancellationException) {
            // Same discriminant as appendEvent: post-commit resume cancel →
            // Log.w + return the durable copy; otherwise rethrow (rollback →
            // zero rows → ack must fail).
            val known = committed.get()
            if (known != null) {
                Log.w(TAG, "bulk append committed but resume cancelled; returning durable rowIds", e)
                known
            } else {
                throw e
            }
        }
        // Same throttled sweep as appendEvent, counted per ROWS (S4-F10).
        // R5-6: same post-commit best-effort rule — a cancel observed here
        // (counter or sweep) only emits Log.w and the committed rowIds are
        // still returned. No withContext(NonCancellable): the sweep is
        // O(sessions) and must not hold the shutdown bound.
        try {
            val shouldPrune = appendMutex.withLock {
                appendsSincePrune += events.size
                if (appendsSincePrune >= AUTO_PRUNE_EVERY) {
                    appendsSincePrune = 0
                    true
                } else {
                    false
                }
            }
            if (shouldPrune) {
                try {
                    prune(PrunePolicy())
                } catch (e: Exception) {
                    // Best-effort maintenance: the bulk above already committed
                    // and its rowIds are still returned below (this also
                    // swallows post-commit cancellation), so the durable core
                    // ack stays a success. The counter was already reset, so
                    // the failed sweep is retried (throttled) after the next
                    // AUTO_PRUNE_EVERY rows instead of retrying hot.
                    Log.w(TAG, "auto-prune failed; bulk already durable, will retry later", e)
                }
            }
        } catch (e: CancellationException) {
            Log.w(TAG, "auto-prune counter cancelled; bulk already durable, will retry later", e)
        } catch (e: Exception) {
            Log.w(TAG, "auto-prune failed; bulk already durable, will retry later", e)
        }
        return rowIds
    }

    /** Shared write-path shaping for [appendEvent]/[appendEvents]: kind gate + redact + truncate + reason. */
    private fun prepareEvent(event: TranscriptEvent): PreparedEvent {
        require(event.kind in VALID_KINDS) { "unknown event kind: ${event.kind}" }
        require(event.kind != "reasoning" || event.text.isEmpty()) { "reasoning marker must have empty text" }
        val redacted = Redactor.redact(event.text).text
        val (text, truncated) = if (redacted.length > MAX_TEXT_CHARS) {
            redacted.take(MAX_TEXT_CHARS) to true
        } else {
            redacted to false
        }
        val reason = event.failureReason?.let { Redactor.redactError(it) }
        return PreparedEvent(text, truncated, reason)
    }

    private data class PreparedEvent(val text: String, val truncated: Boolean, val reason: String?)

    override suspend fun loadEvents(
        sessionId: String,
        afterSeq: Long,
        limit: Int,
    ): List<TranscriptEvent> = withContext(Dispatchers.IO) {
        require(limit > 0) { "limit must be positive" }
        dao.eventsAfter(sessionId, afterSeq, limit).map { it.toEvent() }
    }

    /**
     * Stage E: one DESC query keeps the newest [limit] rows, then restores
     * ascending order. Memory stays O(limit), never O(session).
     */
    override suspend fun loadTailEvents(
        sessionId: String,
        limit: Int,
    ): List<TranscriptEvent> = withContext(Dispatchers.IO) {
        require(limit > 0) { "limit must be positive" }
        dao.eventsTail(sessionId, limit).map { it.toEvent() }.reversed()
    }

    override suspend fun countEvents(sessionId: String): Int =
        withContext(Dispatchers.IO) {
            dao.eventCount(sessionId)
        }

    /**
     * Stage E: exact filtered total via one COUNT query (O(1) memory), so the
     * truncation count is exact without scanning the session.
     */
    override suspend fun countHistoryEvents(
        sessionId: String,
        includePartial: Boolean,
    ): Int = withContext(Dispatchers.IO) {
        if (includePartial) dao.countUiHistory(sessionId) else dao.countModelHistory(sessionId)
    }

    /**
     * Stage E: backward page (newest-first) via one DESC query. Memory stays
     * O(limit), never O(session).
     *
     * D15-5: [Long.MAX_VALUE] is the readers' unbounded-tail sentinel for
     * the first page (every backward reader starts there), so it resolves to
     * the unbounded tail query — a legally imported `seq = Long.MAX_VALUE`
     * row stays visible. All other bounds use the strictly exclusive
     * `seq <` query (sparse seqs safe, advancement guards unchanged).
     */
    override suspend fun loadEventsBefore(
        sessionId: String,
        beforeSeq: Long,
        limit: Int,
    ): List<TranscriptEvent> = withContext(Dispatchers.IO) {
        require(limit > 0) { "limit must be positive" }
        val rows = if (beforeSeq == Long.MAX_VALUE) {
            dao.eventsTail(sessionId, limit)
        } else {
            dao.eventsBefore(sessionId, beforeSeq, limit)
        }
        rows.map { it.toEvent() }
    }

    /**
     * R25-1: single bounded COUNT for the far-marker probe (O(1) memory, same
     * session + `seq <` + `kind='reasoning'` only, never an unbounded scan).
     */
    override suspend fun hasReasoningBefore(sessionId: String, beforeSeq: Long): Boolean =
        withContext(Dispatchers.IO) {
            dao.countReasoningBefore(sessionId, beforeSeq) > 0
        }

    override suspend fun listSessions(): List<SessionMeta> = withContext(Dispatchers.IO) {
        dao.allSessions().map { it.toMeta() }
    }

    override suspend fun getSession(sessionId: String): SessionMeta? =
        withContext(Dispatchers.IO) {
            dao.sessionById(sessionId)?.toMeta()
        }

    override suspend fun exportJsonl(sessionId: String, destFile: File) {
        // Paged streaming export: memory stays O(page), not O(session).
        // Writes to a tmp sibling first; destFile appears atomically only on
        // success, so a failure or oversize abort never leaves a half file.
        // The byte budget is enforced DURING the write (not after
        // materializing everything) to avoid OOM on huge sessions.
        val parent = destFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            error("export failed: cannot create directory ${parent.path}")
        }
        val tmp = File(parent, destFile.name + ".tmp")
        var bytes = 0L
        var afterSeq = 0L
        withContext(Dispatchers.IO) {
            try {
                tmp.bufferedWriter(Charsets.UTF_8).use { out ->
                    while (true) {
                        val page = dao.eventsAfter(sessionId, afterSeq, EXPORT_PAGE_SIZE)
                        if (page.isEmpty()) break
                        for (row in page) {
                            val event = row.toEvent()
                            // B1 defense-in-depth: legacy rows may predate write-time
                            // redaction, so re-redact at export time before touching disk.
                            // Mirrors TranscriptExport.exportRedacted semantics.
                            val redacted = Redactor.redact(event.text).text
                            val reason = event.failureReason?.let { Redactor.redactError(it) }
                            val line = JsonlCodec.encode(event.copy(text = redacted, failureReason = reason))
                            bytes += line.toByteArray(Charsets.UTF_8).size + 1
                            check(bytes <= EXPORT_MAX_BYTES) { "export too large" }
                            out.write(line)
                            out.newLine()
                            afterSeq = row.seq
                        }
                    }
                }
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
            if (!tmp.renameTo(destFile)) {
                tmp.delete()
                error("export failed: cannot move tmp file into place")
            }
        }
    }

    override suspend fun importJsonl(srcFile: File): String {
        // Refuse oversized files BEFORE reading (export caps at the same bound).
        // A missing file has length 0 and falls through to the reader, which
        // throws FileNotFoundException as before.
        require(srcFile.length() <= IMPORT_MAX_BYTES) { "import too large" }
        // Stream line-by-line: memory stays O(file) via the size gate above,
        // never 2x (readLines + parsed list). Parsing completes before any
        // write, so a bad line still rolls back the whole batch (transaction).
        val parsed = ArrayList<JsonlLine>()
        var lastSeq = 0L
        var lineNumber = 0
        withContext(Dispatchers.IO) {
            srcFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (raw in lines) {
                    lineNumber++
                    if (raw.isBlank()) continue
                    val line = JsonlCodec.decode(lineNumber, raw)
                    require(line.seq > lastSeq) { "import failed at line $lineNumber" }
                    lastSeq = line.seq
                    parsed.add(line)
                }
            }
        }
        val now = clock()
        val title = parsed.firstOrNull { it.kind == "user" }?.text?.let {
            Redactor.redact(it).text.take(TITLE_CHARS)
        } ?: IMPORT_FALLBACK_TITLE
        return withContext(Dispatchers.IO) {
            // Deliberately keeps the suspend withTransaction: import has no
            // durable-ack discriminant to protect (R8-5 blocking mode not
            // applicable) and retry is inherently non-idempotent (a new
            // sessionId per attempt), so the COMMIT→resume gap carries no
            // ack-misclassification risk.
            db.withTransaction {
                val sessionId = UUID.randomUUID().toString()
                dao.insertSession(
                    SessionEntity(
                        sessionId,
                        title,
                        IMPORT_MODEL,
                        now,
                        now,
                        false,
                    ),
                )
                for (line in parsed) {
                    // Same truncate parity as appendEvent: over-long texts are cut
                    // and flagged instead of bypassing the MAX_TEXT_CHARS bound.
                    val redactedText = Redactor.redact(line.text).text
                    val (text, truncated) = if (redactedText.length > MAX_TEXT_CHARS) {
                        redactedText.take(MAX_TEXT_CHARS) to true
                    } else {
                        redactedText to false
                    }
                    dao.insertEvent(
                        TranscriptEventEntity(
                            0,
                            sessionId,
                            line.seq,
                            line.runId,
                            line.kind,
                            text,
                            truncated,
                            line.imagesOmitted,
                            line.createdAt,
                            line.isPartial,
                            line.failureReason?.let { Redactor.redactError(it) },
                            line.parentRunId,
                            line.attemptIndex,
                            line.isFinal,
                        ),
                    )
                }
                sessionId
            }
        }
    }

    override suspend fun prune(policy: PrunePolicy): PruneResult =
        pruneWithBeforeConditionalDelete(policy) {}

    /**
     * The callback is an internal test seam for deterministically exercising an update
     * between the candidate snapshot and the conditional DELETE. The actual deletion
     * always goes through Room's single-statement predicate, not this callback or a
     * store-local mutex.
     */
    internal suspend fun pruneWithBeforeConditionalDelete(
        policy: PrunePolicy,
        beforeConditionalDelete: suspend (String) -> Unit,
    ): PruneResult =
        withContext(Dispatchers.IO) {
            var deletedSessions = 0
            var deletedEvents = 0
            val cutoff = clock() - policy.maxAgeDays * MILLIS_PER_DAY
            // This is only a candidate snapshot. Every delete rechecks age and pin
            // state atomically in SQL, so a committed append/touch or pin after this
            // read cannot be lost to a stale in-memory decision.
            val candidates = dao.allSessions()
            val survivors = ArrayList<String>()
            for (session in candidates) {
                beforeConditionalDelete(session.sessionId)
                val deleted = dao.deleteSessionIfStale(
                    session.sessionId,
                    cutoff,
                    policy.keepPinnedSessions,
                )
                if (deleted > 0) {
                    deletedSessions += deleted // CASCADE clears events atomically
                } else {
                    survivors.add(session.sessionId)
                }
            }
            for (sid in survivors) {
                // Imported seq values may be sparse. Keep N actual newest rows by
                // ordering the current rows, rather than deriving a cutoff from MAX(seq).
                // S4-F4(b): per-session delete>0 marks prune-suspect (marker-first
                // pair may have been cut); pair-aware prune stays Target (a).
                val deleted = dao.deleteEventsBeyondLimit(
                    sid,
                    policy.maxEventsPerSession.coerceAtLeast(0),
                )
                if (deleted > 0) pruneSuspectSessions.add(sid)
                deletedEvents += deleted
            }
            PruneResult(deletedEvents = deletedEvents, deletedSessions = deletedSessions)
        }

    /** S4-F4(b): true when a prior [prune] deleted rows for [sessionId]. */
    override suspend fun isPruneSuspect(sessionId: String): Boolean =
        pruneSuspectSessions.contains(sessionId)

    override suspend fun deleteSession(sessionId: String) {
        withContext(Dispatchers.IO) {
            dao.deleteSession(sessionId)
        }
        // S4-R2 suspect lifecycle: delete clears (session gone – no kept
        // assistant to stay suspect). Appends never clear (a later append
        // cannot resurrect a pruned marker-first pair); pair-aware prune +
        // durable suspect stay Target/follow-up (memory-only noted in KDoc).
        pruneSuspectSessions.remove(sessionId)
    }

    private fun TranscriptEventEntity.toEvent(): TranscriptEvent = TranscriptEvent(
        seq = seq,
        sessionId = sessionId,
        runId = runId,
        kind = kind,
        text = text,
        imagesOmitted = imagesOmitted,
        createdAt = createdAt,
        isPartial = isPartial,
        failureReason = failureReason,
        parentRunId = parentRunId,
        attemptIndex = attemptIndex,
        isFinal = isFinalFlag,
    )

    private fun SessionEntity.toMeta(): SessionMeta = SessionMeta(
        sessionId = sessionId,
        // Defense in depth: rows predating write-time redaction are cleaned here.
        title = Redactor.redact(title).text,
        model = model,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        private const val TAG = "RoomSessionStore"
        const val MAX_TEXT_CHARS = 100_000
        const val EXPORT_MAX_BYTES = 20 * 1024 * 1024
        const val IMPORT_MAX_BYTES = EXPORT_MAX_BYTES
        const val EXPORT_PAGE_SIZE = 500
        const val TITLE_CHARS = 30
        const val AUTO_PRUNE_EVERY = 100
        const val MILLIS_PER_DAY = 86_400_000L
        const val IMPORT_MODEL = "imported/unknown"
        const val IMPORT_FALLBACK_TITLE = "imported"
    }
}
