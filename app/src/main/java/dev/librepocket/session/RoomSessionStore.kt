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
import kotlinx.coroutines.currentCoroutineContext
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
     * S4-F4 prune-marker suspect (hotfix b) + R26-1 family-aware (Current):
     * sessionIds whose LATEST [prune] sweep deleted rows. [loadModelHistory]
     * treats them as reasoning-suspect (all kept assistants fail-closed) when
     * truncation evidence exists. Per-sweep (NOT sticky): a sweep with
     * `deletedEvents==0` AND a clean (user/empty) post-fixup head clears
     * the flag (R27-2 shape-aware; `deleted==0` residue keeps fail-closed),
     * [deleteSession] clears it, and
     * appends never clear it (a later append cannot resurrect a pruned
     * pair). Clearing on a clean sweep is safe for new prunes because the
     * R26-1 fixup durably deletes the cut head family (user boundary,
     * marker-first implies user-less as the user row is older), so no
     * user-less assistant survives for the flag to protect — restart needs
     * no durable flag for new prunes (memory pre-publish is only a race
     * closer, see [prune], never load-bearing across restart).
     *
     * R27-1 atomic raw-cut + family-fixup (Current, P1 BLOCK): the raw
     * keep-newest cut (`deleteEventsBeyondLimit`) and the family fixup
     * (`oldestHistory` + `deleteFamily` loop) run in ONE blocking Room
     * transaction per session (`db.runInTransaction`, same R8-5 shape as
     * append) inside [pruneEventCapMutex]. A kill landing between them rolls
     * the whole tx back → restart sees the pre-prune shape (no durable head
     * orphan) with this memory-only set dropped — correct, as nothing was
     * durably cut, so no suspect flag is needed for the rolled-back shape.
     * Fixup/hook failures are fail-closed: a DAO throw aborts the tx and
     * propagates (never swallowed as success); only size-convergent stops
     * (empty, user head, blank family, `famDeleted<=0`) end the loop
     * normally (R27-2: unbounded, no guard cap — termination by strict
     * decrease, see [prune]).
     *
     * Still memory-only (NOT durable — no over-claim): restart drops this
     * set. Phase 1 guarantees only the no-orphan SHAPE (rollback on
     * crash-mid-fixup), not suspect durability across restart.
     *
     * Head-only convergent scope (R27-2, Current): the fixup runs
     * unconditionally in the same tx (no `deleted>0` gate) and clears ALL
     * head orphans in a single sweep (unbounded `while(true)` +
     * break-on-no-progress — termination by strict decrease, see [prune]).
     * Small-ledger `deleted==0` sweeps also converge: only a post-fixup
     * user/empty head clears suspect; a still-assistant head (blank family
     * or `famDeleted<=0` residue) keeps suspect fail-closed. Pre-R26/interior
     * orphans below the head are still NOT deleted by the fixup. While
     * suspect is held they stay hidden behind the conservative all-flag;
     * after a clean sweep clears suspect (or after restart, which drops this
     * memory-only set) an interior orphan with no truncation evidence
     * resumes compat (known limitation, disclosed in P1_SPEC/THREAT_MODEL —
     * not a fail-open of new prunes).
     *
     * Crafted-ledger over-drop (disclosed): the family delete keys on
     * `parentRunId ?: runId` and assumes production write order + unique
     * runIds; a crafted ledger reusing one runId across turns or interleaved
     * out of order loses every remaining row sharing the head family id
     * (conservative direction). `oldestHistory` has no `isPartial` filter,
     * so a crafted head partial steers the fixup (readers ignore partials
     * anyway). The family delete matches any kind (`runId == fid OR
     * parentRunId == fid`), so the cut family's retry notices and cancel
     * marks go with it; tool/usage rows keyed by a bare attempt id
     * (`parentRunId == null`, `runId == attemptId` ≠ logical family id)
     * survive as DB residue (P2-3, disclosed in P1_SPEC — never model
     * context, never a family terminal).
     * Test-fake divergence: `SuspectFakeStore.simulatePruneCut` stays R26-only
     * (`drop>0` gate + 10-cap + unconditional clear); R27 proven by Room tests.
     * Concurrent set, no suspension while held.
     */
    private val pruneSuspectSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * R26-1 prune event-cap mutex: serializes ONLY the per-session
     * keep-newest + family-fixup segment across concurrent [prune] sweeps
     * (separate from [appendMutex], which guards append accounting). The
     * flag is pre-published before the DELETE, so without serialization two
     * overlapping sweeps could interleave publish/delete/clear and leave the
     * flag reflecting the earlier sweep instead of the last completed one
     * (last-finisher-wins is the documented semantic: the flag always
     * mirrors the latest completed sweep's `deleted>0`). Session-expiry
     * deletes stay outside this mutex (single-statement conditional SQL).
     */
    private val pruneEventCapMutex = Mutex()

    /**
     * R26-1 F4 test seam (R27-1: invoked after suspect pre-publish but
     * BEFORE the per-session prune transaction starts — deliberately NOT
     * inside the tx: a blocked hook holding the write tx open starves
     * concurrent readers, so the interleaved load never completes and the
     * hook times out). Tests block here to interleave a concurrent load
     * that observes the pre-published flag with live (pre-delete) totals:
     * pre-publish code reports suspect (green); legacy post-publish code
     * would report a false clean here (red — logic argument, no revert run
     * against old code was executed). A throw here — including
     * RuntimeException, no longer swallowed — happens before any write and
     * propagates fail-closed with flag cleanup. Contract: the hook runs
     * under the non-reentrant [pruneEventCapMutex] but outside any write
     * tx — it must stay lock-free (no prune reentry, no write-tx
     * acquisition); the F4 test parks on latches and issues reads only.
     * In-tx crash injection stays covered by ThrowingDao (DAO throw inside
     * the real tx → rollback), not by this seam. Production leaves it
     * `null`.
     */
    internal var afterEventDeleteHookForTest: ((String) -> Unit)? = null

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

    internal var importAfterInsertHookForTest: ((Job?) -> Unit)? = null

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
        return PreparedEvent(text, event.isTruncated || truncated, reason)
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
        // Paged streaming export; the ID map is bounded by rows and source ID characters.
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
        var chars = 0L
        var rows = 0
        var afterSeq = 0L
        val ids = HashMap<String, String>()
        val assignedIds = HashSet<String>()
        var idChars = 0L
        fun exportId(id: String): String = ids.getOrPut(id) {
            if (id.length > IMPORT_MAX_LINE_CHARS || idChars + id.length > IMPORT_MAX_CONTENT_CHARS) {
                throw JsonlExportLimitException("export IDs too large")
            }
            idChars += id.length
            var candidate: String
            do {
                candidate = redactionSafeExportId(UUID.randomUUID())
            } while (!assignedIds.add(candidate))
            candidate
        }
        withContext(Dispatchers.IO) {
            try {
                tmp.bufferedWriter(Charsets.UTF_8).use { out ->
                    while (true) {
                        val page = dao.eventsAfter(sessionId, afterSeq, EXPORT_PAGE_SIZE)
                        if (page.isEmpty()) break
                        for (row in page) {
                            if (++rows > IMPORT_MAX_ROWS) throw JsonlExportLimitException("export too large")
                            // Inspect the stored text before any read-time redaction:
                            // marker recognition requires the original boundary.
                            val event = row.toEvent().copy(text = row.text)
                            // B1 defense-in-depth: legacy rows may predate write-time
                            // redaction, so re-redact at export time before touching disk.
                            // Mirrors TranscriptExport.exportRedacted semantics.
                            val mappedRunId = exportId(event.runId)
                            // Match the original marker before redaction, and only on system rows.
                            // A marker already redacted on write may no longer contain its
                            // runId; it cannot be safely identified or reconstructed here.
                            // Legacy failure suffixes remain free text and still need redaction.
                            val markerPrefix = "turn ${event.runId} "
                            val markerTail = if (event.kind == "system" && event.text.startsWith(markerPrefix)) {
                                event.text.substring(markerPrefix.length).takeIf {
                                    it == "cancelled" || it == "interrupted" || it.startsWith("failed")
                                }
                            } else null
                            val redactedPrefix = Redactor.redact(markerPrefix).text
                            if (markerTail == null && event.kind == "system" && redactedPrefix != markerPrefix &&
                                event.text.startsWith(redactedPrefix) &&
                                event.text.substring(redactedPrefix.length).let {
                                    it == "cancelled" || it == "interrupted" || it.startsWith("failed")
                                }
                            ) throw JsonlExportLimitException("export cannot identify redacted terminal marker")
                            if (markerTail == null && event.kind == "system" &&
                                listOf("cancelled", "interrupted", "failed").any { tail ->
                                    val original = markerPrefix + tail
                                    val masked = Redactor.redact(original).text
                                    masked != original && (event.text == masked ||
                                        (tail == "failed" && event.text.startsWith(masked)))
                                }
                            ) throw JsonlExportLimitException("export cannot identify redacted terminal marker")
                            val redacted = if (markerTail != null) {
                                "turn $mappedRunId ${Redactor.redact(markerTail).text}"
                            } else Redactor.redact(event.text).text
                            val reason = event.failureReason?.let { Redactor.redactError(it) }
                            // IDs can contain arbitrary secrets, including in legacy rows.
                            // Use one bijection for both fields, independent for each export.
                            val line = JsonlCodec.encode(event.copy(
                                runId = mappedRunId,
                                parentRunId = event.parentRunId?.let { exportId(it) },
                                text = redacted,
                                failureReason = reason,
                            ), isTruncated = row.isTruncated)
                            if (line.length > IMPORT_MAX_LINE_CHARS) throw JsonlExportLimitException("export line too large")
                            // Import redacts again before applying its durable text cap.
                            // Reject rather than produce an export that import would truncate.
                            if (Redactor.redact(redacted).text.length > MAX_TEXT_CHARS) {
                                throw JsonlExportLimitException("export text exceeds import limit (100000 characters)")
                            }
                            chars += line.length
                            if (chars > IMPORT_MAX_CONTENT_CHARS) throw JsonlExportLimitException("export too large")
                            bytes += line.toByteArray(Charsets.UTF_8).size + 1
                            if (bytes > EXPORT_MAX_BYTES) throw JsonlExportLimitException("export too large")
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
        if (srcFile.length() > IMPORT_MAX_BYTES) throw JsonlImportLimitException("import too large")
        val input = object : java.io.FilterInputStream(srcFile.inputStream()) {
            private var closed = false
            override fun close() { if (!closed) { closed = true; super.close() } }
        }
        return input.use { importJsonlImpl(it, closeAfterRead = true) }
    }

    override suspend fun importJsonl(input: java.io.InputStream): String =
        importJsonlImpl(input, closeAfterRead = false)

    private suspend fun importJsonlImpl(input: java.io.InputStream, closeAfterRead: Boolean): String {
        // Parse before writing, bounded by the source-byte limit. Parsed objects
        // can still occupy substantially more memory than the source bytes.
        val parsed = ArrayList<JsonlLine>()
        var lastSeq = 0L
        var lineNumber = 0
        var contentChars = 0L
        withContext(Dispatchers.IO) {
            val bounded = object : java.io.FilterInputStream(input) {
                var count = 0L
                override fun read(): Int {
                    val value = super.read()
                    if (value != -1 && ++count > IMPORT_MAX_BYTES) throw JsonlImportLimitException("import too large")
                    return value
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) {
                        count += n
                        if (count > IMPORT_MAX_BYTES) throw JsonlImportLimitException("import too large")
                    }
                    return n
                }
                override fun close() { if (closeAfterRead) input.close() }
            }
            // readLine() is unbounded; reject before constructing an oversized line.
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            try {
                java.io.PushbackReader(java.io.InputStreamReader(bounded, decoder).buffered(), 1).use { reader ->
                    while (true) {
                        ensureActive()
                        val raw = readLimitedJsonlLine(reader) ?: break
                        lineNumber++
                        if (raw.isBlank()) continue
                        contentChars += raw.length
                        if (contentChars > IMPORT_MAX_CONTENT_CHARS || parsed.size >= IMPORT_MAX_ROWS)
                            throw JsonlImportLimitException("import too large")
                        val line = JsonlCodec.decode(lineNumber, raw)
                        require(line.seq > lastSeq) { "import failed at line $lineNumber" }
                        if (line.kind == "system" && terminalTail(line.runId, line.text) == null &&
                            isAmbiguousRedactedTerminal(line.runId, line.text)
                        ) throw IllegalArgumentException("import failed at line $lineNumber")
                        if (Redactor.redact(line.text).text.length > MAX_TEXT_CHARS) {
                            throw JsonlImportLimitException("import failed at line $lineNumber")
                        }
                        lastSeq = line.seq
                        parsed.add(line)
                    }
                }
            } catch (_: java.nio.charset.CharacterCodingException) {
                throw IllegalArgumentException("import failed: invalid UTF-8")
            }
        }
        // Never persist source IDs: arbitrary input may contain sensitive data.
        // One injective map preserves all run/parent relationships across rows.
        val assignedIds = HashSet<String>()
        for (line in parsed) {
            assignedIds.add(line.runId)
            line.parentRunId?.let { assignedIds.add(it) }
        }
        val remappedIds = HashMap<String, String>()
        fun mapId(source: String): String = remappedIds.getOrPut(source) {
            var id: String
            do { id = redactionSafeExportId(UUID.randomUUID()) } while (!assignedIds.add(id))
            id
        }
        for (line in parsed) {
            mapId(line.runId)
            line.parentRunId?.let { mapId(it) }
        }
        val now = clock()
        val title = parsed.firstOrNull { it.kind == "user" }?.text?.let {
            Redactor.redact(it).text.take(TITLE_CHARS)
        } ?: IMPORT_FALLBACK_TITLE
        return withContext(Dispatchers.IO) {
            // Cancellation after commit but before returning the ID leaves the
            // outcome unknown to the caller; retry can create a duplicate.
            db.withTransaction {
                val job = currentCoroutineContext()[Job]
                job?.ensureActive()
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
                    job?.ensureActive()
                    val mappedRunId = remappedIds.getValue(line.runId)
                    val tail = if (line.kind == "system") terminalTail(line.runId, line.text) else null
                    val text = if (tail != null) "turn $mappedRunId $tail" else line.text
                    val redactedText = Redactor.redact(text).text
                    if (redactedText.length > MAX_TEXT_CHARS) {
                        throw JsonlImportLimitException("import text exceeds limit")
                    }
                    dao.insertEvent(
                        TranscriptEventEntity(
                            0,
                            sessionId,
                            line.seq,
                            mappedRunId,
                            line.kind,
                            redactedText,
                            line.isTruncated,
                            line.imagesOmitted,
                            line.createdAt,
                            line.isPartial,
                            line.failureReason?.let { Redactor.redactError(it) },
                            line.parentRunId?.let { remappedIds.getValue(it) },
                            line.attemptIndex,
                            line.isFinal,
                        ),
                    )
                    importAfterInsertHookForTest?.invoke(job)
                }
                job?.ensureActive()
                sessionId
            }
        }
    }

    private fun terminalTail(runId: String, text: String): String? {
        val prefix = "turn $runId "
        if (!text.startsWith(prefix)) return null
        return text.substring(prefix.length).takeIf {
            it == "cancelled" || it == "interrupted" || it.startsWith("failed")
        }
    }

    private fun isAmbiguousRedactedTerminal(runId: String, text: String): Boolean {
        val prefix = "turn $runId "
        val maskedPrefix = Redactor.redact(prefix).text
        if (maskedPrefix != prefix && text.startsWith(maskedPrefix) &&
            text.substring(maskedPrefix.length).let {
                it == "cancelled" || it == "interrupted" || it.startsWith("failed")
            }
        ) return true
        return listOf("cancelled", "interrupted", "failed").any { tail ->
            val original = prefix + tail
            val masked = Redactor.redact(original).text
            masked != original && (text == masked || (tail == "failed" && text.startsWith(masked)))
        }
    }

    private fun readLimitedJsonlLine(reader: java.io.PushbackReader): String? {
        val line = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c == -1) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString()
            if (c == '\r'.code) {
                val next = reader.read()
                if (next != -1 && next != '\n'.code) reader.unread(next)
                return line.toString()
            }
            if (line.length >= IMPORT_MAX_LINE_CHARS) throw JsonlImportLimitException("import line too large")
            line.append(c.toChar())
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
                    // Session gone – no kept assistant to stay suspect.
                    pruneSuspectSessions.remove(session.sessionId)
                } else {
                    survivors.add(session.sessionId)
                }
            }
            // Serialized by [pruneEventCapMutex]: the publish→delete→clear
            // sequence below is one critical section, so the flag always
            // mirrors the latest COMPLETED sweep (last-finisher-wins, by
            // design — concurrent sweeps with mixed outcomes resolve to the
            // last finisher's deleted>0, never a stale earlier value).
            pruneEventCapMutex.withLock {
                for (sid in survivors) {
                    // Imported seq values may be sparse. Keep N actual newest rows by
                    // ordering the current rows, rather than deriving a cutoff from MAX(seq).
                    // R26-1 F4: pre-publish suspect BEFORE the conditional DELETE to
                    // close the DB-delete→publish race (a concurrent loadModelHistory
                    // between commit and post-publish would otherwise see post-delete
                    // totals with suspect==false → under-flag). A sweep that deletes
                    // nothing clears the flag again below (per-sweep, NOT sticky);
                    // a throw removes the pre-published flag unless a prior sweep
                    // had set it (fail-closed for proven evidence only), then
                    // rethrows — never a silent success.
                    // R26-1 family-aware (Current): when the raw keep-newest cut splits
                    // a user/assistant family (marker-first implies user-less, as the
                    // user row is older than its marker), delete the whole head family
                    // so kept starts at a user boundary and no user-less assistant
                    // reaches loadModelHistory/snapshot (no restart fail-open).
                    val alreadySuspect = pruneSuspectSessions.contains(sid)
                    pruneSuspectSessions.add(sid)
                    // R27-1 (P1 BLOCK): the raw keep-newest cut + the whole
                    // family fixup run in ONE blocking Room transaction (same
                    // R8-5 `db.runInTransaction(Callable)` shape as append): a
                    // kill landing between them rolls everything back, so
                    // restart sees the pre-prune shape (no durable head
                    // orphan) with this memory-only flag dropped — correct, as
                    // nothing was durably cut.
                    try {
                        // Test-only interleave point, BEFORE the tx starts
                        // (F4 deterministic test; deliberately NOT inside the
                        // tx: a blocked hook holding the write tx open starves
                        // concurrent readers — the interleaved load never
                        // completes and the hook times out instead, pinned red
                        // by `deletePublishRace_interleavedLoad_observesSuspect`
                        // against the in-tx placement). A throw here happens
                        // before any write, so there is nothing to roll back;
                        // the catch below still drops the pre-published flag
                        // (unless prior evidence) and rethrows — including
                        // RuntimeException, whose old fall-through to flag
                        // reconcile + fixup success is gone — never a silent
                        // success.
                        afterEventDeleteHookForTest?.invoke(sid)
                        val (txDeleted, headClean) = db.runInTransaction(
                            Callable<Pair<Int, Boolean>> {
                                var total = dao.deleteEventsBeyondLimit(
                                    sid,
                                    policy.maxEventsPerSession.coerceAtLeast(0),
                                )
                                // R27-2 convergent head-only fixup (Current):
                                // UNCONDITIONAL in the same tx (no total>0 gate)
                                // — while the oldest kept user/assistant row is
                                // an assistant, its whole logical family goes.
                                // Unbounded while(true) + break-on-no-progress:
                                // empty / user head / blank family /
                                // famDeleted<=0 end the loop normally; a DAO
                                // throw is NEVER swallowed here: it aborts the
                                // tx (rollback) and propagates fail-closed via
                                // the catch below. Termination by strict
                                // decrease: every non-breaking iteration deletes
                                // >=1 row (famDeleted>0), so the DB row count
                                // strictly drops — the loop must end.
                                // Pathological empty families are stopped by the
                                // isBlank / famDeleted<=0 breaks, making the old
                                // 10-cap redundant; one sweep converges
                                // arbitrary N (crafted 15-orphan scale is fine,
                                // production splits at most one family).
                                while (true) {
                                    val oldest = dao.oldestHistory(sid, 1)
                                    if (oldest.isEmpty()) break
                                    val head = oldest[0]
                                    if (head.kind == "user") break
                                    val family = head.parentRunId ?: head.runId
                                    if (family.isBlank()) break
                                    val famDeleted = dao.deleteFamily(sid, family)
                                    if (famDeleted <= 0) break
                                    total += famDeleted
                                }
                                // Shape-aware clean decision (same tx, atomic —
                                // never a raw-cut-only visible shape): the final
                                // head determines clean. Empty/user = converged
                                // (no orphan left); still-assistant = residue
                                // (blank or famDeleted<=0 no-progress) → not clean.
                                val tail = dao.oldestHistory(sid, 1)
                                val clean = tail.isEmpty() || tail[0].kind == "user"
                                total to clean
                            },
                        )
                        if (txDeleted == 0 && headClean) {
                            // Clean sweep: no deletions AND post-fixup head is
                            // user/empty — no truncation evidence and no orphan
                            // left for the flag to protect, so the pre-published
                            // flag (and any prior one — per-sweep, not sticky)
                            // is cleared. Appends still never clear (only sweeps
                            // publish).
                            pruneSuspectSessions.remove(sid)
                        } else {
                            // Deleting sweep (txDeleted>0): keep pre-published
                            // suspect (truncation evidence) and count. Residue
                            // sweep (txDeleted==0 but head still assistant):
                            // keep suspect fail-closed (no clear, no count) —
                            // a blank/famDeleted<=0 no-progress orphan stays
                            // protected.
                            if (txDeleted > 0) deletedEvents += txDeleted
                        }
                    } catch (t: Throwable) {
                        // Test hook, raw cut, and/or fixup failed: a hook
                        // throw happened before any write (nothing durable),
                        // a raw/fixup throw already rolled the tx back — in
                        // both cases no partial shape is durable. Drop the
                        // pre-published flag unless a prior sweep had proven
                        // evidence (fail-closed for proven evidence only),
                        // then rethrow — never a silent success.
                        if (!alreadySuspect) pruneSuspectSessions.remove(sid)
                        throw t
                    }
                }
            }
            PruneResult(deletedEvents = deletedEvents, deletedSessions = deletedSessions)
        }

    /**
     * S4-F4(b) + R26-1 + R27-2 shape-aware: true when the LATEST [prune] sweep
     * deleted rows for [sessionId] (or left a `deleted==0` head-orphan residue).
     * Per-sweep, NOT sticky: a later clean sweep (`deleted==0` AND user/empty
     * post-fixup head) clears it (new prunes leave no orphan behind, so nothing
     * needs protecting); [deleteSession] clears it; appends never clear it.
     */
    override suspend fun isPruneSuspect(sessionId: String): Boolean =
        pruneSuspectSessions.contains(sessionId)

    override suspend fun deleteSession(sessionId: String) {
        withContext(Dispatchers.IO) {
            dao.deleteSession(sessionId)
        }
        // S4-R2 suspect lifecycle: delete clears (session gone – no kept
        // assistant to stay suspect). Appends never clear (a later append
        // cannot resurrect a pruned pair); only sweeps publish — a clean
        // sweep (deleted==0 AND user/empty head, R27-2 shape-aware) clears,
        // a deleting sweep sets, a deleted==0 residue sweep keeps fail-closed.
        // R26-1 family-aware leaves no orphan behind, so restart needs no durable
        // flag for new prunes (memory-only set is race-closer, not
        // load-bearing).
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
        isTruncated = isTruncated,
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
        // Characters are UTF-16 code units, measured on the encoded JSONL line.
        // 1 Mi accommodates escaped 100k-character stored text plus metadata.
        const val IMPORT_MAX_LINE_CHARS = 1024 * 1024
        const val IMPORT_MAX_CONTENT_CHARS = 8 * 1024 * 1024
        const val IMPORT_MAX_ROWS = 10_000
        const val EXPORT_PAGE_SIZE = 500
        const val TITLE_CHARS = 30
        const val AUTO_PRUNE_EVERY = 100
        const val MILLIS_PER_DAY = 86_400_000L
        const val IMPORT_MODEL = "imported/unknown"
        const val IMPORT_FALLBACK_TITLE = "imported"
    }
}

/** Encode all UUID hex nibbles as a-p: no digits or punctuation for redaction to rewrite. */
internal fun redactionSafeExportId(id: UUID): String = buildString(32) {
    for (char in id.toString()) {
        if (char != '-') append('a' + char.digitToInt(16))
    }
}
