package dev.librepocket.session

import java.io.File

/** Session header (spec §1.5). */
data class SessionMeta(
    val sessionId: String, // UUID v4
    val title: String, // first user message, first 30 chars, redacted
    val createdAt: Long, // epoch millis
    val updatedAt: Long,
    val model: String, // "providerId/modelId"
)

/**
 * Single transcript event (spec §1.5).
 *
 * [text] must already be redacted when it reaches the store; [RoomSessionStore]
 * enforces this by running [dev.librepocket.redact.Redactor] on every write.
 *
 * Phase 3 (implemented): [isPartial] marks cancelled / failed assistant rows
 * (they keep their partial text with the flag set) and [failureReason] carries
 * the sanitized error next to that partial text. Persisted as
 * `isPartial INTEGER NOT NULL DEFAULT 0` / nullable `failureReason TEXT` via
 * the backward-compatible Migration 1→2 (never a destructive migration).
 * Partial-ness is also mirrored in UI memory
 * ([dev.librepocket.chat.UiMessage.isPartial]) and the in-memory INTERRUPTED
 * marks ([dev.librepocket.chat.TurnController.interruptedTranscriptRunIds]).
 *
 * Stage C (implemented): [parentRunId] links every attempt-bound assistant
 * terminal back to its logical turn (`runId=attemptRunId`,
 * `parentRunId=logicalTurnId`; the first attempt reuses the logical id so its
 * parent stays null for byte-compat with pre-C rows). [attemptIndex] is the
 * 0-based attempt number (null for user rows and legacy rows). Retry notices
 * keep `runId=logicalTurnId`. Persisted as nullable `parentRunId TEXT` /
 * nullable `attemptIndex INTEGER` via Migration 2→3 (ALTER ADD COLUMN only).
 * [findDanglingRunIds][dev.librepocket.agent.ui.chat.findDanglingRunIds]
 * groups by logical family (`parentRunId ?: runId`): pre-Stage F rule, any
 * assistant row in the family (completed or partial-failed, including
 * retried partials) completed the family, so a retry→success turn was never
 * mis-marked INTERRUPTED. Since Stage F only final rows close a family —
 * see Stage F below — this paragraph states the pre-F semantics; the
 * implementation follows Stage F.
 *
 * Stage F (implemented): [isFinal] distinguishes a logical-turn final result
 * from a per-attempt intermediate partial. Success, terminal-failure and
 * cancel assistant rows are final (`isFinal=true`); a retryable failure's
 * intermediate fragment (written before its retry notice) is non-final
 * (`isFinal=false`). Retry notices are unchanged (final by default, never a
 * terminal). Persisted as `isFinal INTEGER NOT NULL DEFAULT 1` via Migration
 * 3→4 (ALTER ADD COLUMN only); pre-F rows and JSONL lines without the key
 * read back as `isFinal=true` (old single-terminal semantics). Only
 * `assistant isFinal=1` rows (plus system cancel/interrupted terminals) close
 * a logical family — see [findDanglingRunIds].
 */
data class TranscriptEvent(
    val seq: Long = 0, // DB-assigned; ignored on write
    val sessionId: String,
    val runId: String, // one logical turn owns one user row id; each attempt uses a fresh runId for its assistant/tool/usage rows, retry notices bind to the logical id
    val kind: String, // "user" | "assistant" | "tool" | "steer" | "retry" | "system" | "reasoning"
    val text: String, // redacted before write
    val imagesOmitted: Int = 0, // stripped image-body count (bytes never stored)
    val createdAt: Long,
    val isPartial: Boolean = false, // cancelled / failed assistant fragment
    val failureReason: String? = null, // sanitized error for failed rows (redacted on write)
    val parentRunId: String? = null, // Stage C: logical turn id for attempt assistant terminals; null for user/retry/legacy rows
    val attemptIndex: Int? = null, // Stage C: 0-based attempt number for attempt rows; null for user/legacy rows
    val isFinal: Boolean = true, // Stage F: false only for retryable-failed intermediate partials; missing key/old rows default final
)

/** Prune knobs (spec §8.3). */
data class PrunePolicy(
    val maxEventsPerSession: Int = 2000,
    val maxAgeDays: Int = 90,
    val keepPinnedSessions: Boolean = true,
)

/** Prune outcome: counts only, never content (spec §8.3). */
data class PruneResult(val deletedEvents: Int, val deletedSessions: Int)

/** Transcript store (spec §1.5). */
interface SessionStore {
    /** Create a session; returns the sessionId. */
    suspend fun createSession(title: String, model: String): String

    /** All sessions, most-recently-updated first (titles re-redacted on read). */
    suspend fun listSessions(): List<SessionMeta>

    /** One session header, or null (title re-redacted on read). */
    suspend fun getSession(sessionId: String): SessionMeta?

    /** Append one event; returns the rowId.
     *
     * D15-5: when the session already holds `seq = Long.MAX_VALUE` (legal
     * import), the seq space is exhausted and the write is refused
     * fail-closed (throws, zero rows) — never a wrapped/duplicate seq.
     */
    suspend fun appendEvent(event: TranscriptEvent): Long

    /**
     * R5-4 atomic batch append: persists every event as one all-or-nothing
     * unit and returns the rowIds in order. The default implementation is a
     * sequential [appendEvent] loop (NON-ATOMIC: a mid-batch failure leaves
     * the earlier rows behind); only [RoomSessionStore] (single DB
     * transaction) and the in-memory test fake (snapshot + rollback) upgrade
     * this to a true atomic commit. Current: 2-row marker-first batches
     * ([reasoning] before `assistant`, same partial-first precedent as
     * [dev.librepocket.chat.TranscriptSink.onTurnFailedAndCancelled]) leave
     * only an orphan `reasoning` marker on a mid-batch throw — harmless by
     * construction (no `user` family, never model context, ignored by
     * `findDanglingRunIds`, bounded UI pending set) and the family stays
     * dangling. The 3-row [partial, `reasoning`, cancel] batch of
     * [dev.librepocket.chat.TranscriptSink.onTurnFailedAndCancelled] is the
     * exception: a 2nd-row fault there leaves an orphan non-final partial
     * (`isPartial=1`, `isFinal=0`) — still fail-closed (never model context,
     * never a family terminal, so the family still dangles and reopen still
     * backfills INTERRUPTED). Current (atomic Room/fake) still commits
     * all-or-zero; this paragraph pins only the fallback shape. Concrete
     * caller obligation: treat a throw as a possibly-partial batch (never
     * assume rollback) unless the store documents atomicity; do NOT reorder
     * a batch assistant-first.
     * [snapshotSwitchHistory][dev.librepocket.agent.ui.chat.snapshotSwitchHistory]
     * funnels its marker + prefix through this one call, so Room/fake forks
     * can never strand a half snapshot.
     */
    suspend fun appendEvents(events: List<TranscriptEvent>): List<Long> {
        val ids = ArrayList<Long>(events.size)
        for (event in events) ids.add(appendEvent(event))
        return ids
    }

    /** Page events by [afterSeq] (exclusive), ascending, at most [limit]. */
    suspend fun loadEvents(sessionId: String, afterSeq: Long = 0, limit: Int = 200): List<TranscriptEvent>

    /**
     * Stage E: newest [limit] events, ascending (oldest of the tail first).
     * Default pages forward and keeps the tail (test-scale fakes); the Room
     * store overrides with a single DESC query so resume never materializes
     * the whole session (OOM-safe).
     */
    suspend fun loadTailEvents(sessionId: String, limit: Int): List<TranscriptEvent> {
        require(limit > 0) { "limit must be positive" }
        val all = ArrayList<TranscriptEvent>()
        var afterSeq = 0L
        while (true) {
            val page = loadEvents(sessionId, afterSeq, 200)
            if (page.isEmpty()) break
            all.addAll(page)
            afterSeq = page.last().seq
            if (page.size < 200) break
        }
        return if (all.size <= limit) all else all.subList(all.size - limit, all.size)
    }

    /**
     * Stage E: page backward from [beforeSeq] (exclusive), newest-first, at
     * most [limit]. Backs the tail-fill loop so resume scans only the newest
     * rows (sparse seqs safe: predicate is `seq <`, never an offset).
     * Default pages forward and filters (test-scale fakes); the Room store
     * overrides with a single DESC query.
     *
     * D15-5: [Long.MAX_VALUE] is the unbounded-tail sentinel for the first
     * page (every backward reader starts there). A legally imported
     * `seq = Long.MAX_VALUE` row MUST be visible in that first page, so the
     * sentinel is inclusive-unbounded; every subsequent page (with
     * `beforeSeq` = the previous page minimum, always `< MAX`) stays
     * strictly exclusive.
     */
    suspend fun loadEventsBefore(
        sessionId: String,
        beforeSeq: Long,
        limit: Int,
    ): List<TranscriptEvent> {
        require(limit > 0) { "limit must be positive" }
        val all = ArrayList<TranscriptEvent>()
        var afterSeq = 0L
        while (true) {
            val page = loadEvents(sessionId, afterSeq, 200)
            if (page.isEmpty()) break
            all.addAll(page)
            afterSeq = page.last().seq
            if (page.size < 200) break
        }
        // D15-5: the MAX sentinel is inclusive-unbounded (first page); all
        // other bounds stay strictly exclusive.
        val unbounded = beforeSeq == Long.MAX_VALUE
        return all.filter { unbounded || it.seq < beforeSeq }.takeLast(limit).reversed()
    }

    /**
     * Stage E: total stored events for one session. Drives the observable
     * truncation count (`dropped = total - tail kept`). Default counts via
     * paging; the Room store overrides with the DAO COUNT query.
     */
    suspend fun countEvents(sessionId: String): Int {
        var total = 0
        var afterSeq = 0L
        while (true) {
            val page = loadEvents(sessionId, afterSeq, 500)
            if (page.isEmpty()) break
            total += page.size
            afterSeq = page.last().seq
            if (page.size < 500) break
        }
        return total
    }

    /**
     * Stage E: exact history-row total backing the observable truncation
     * count. [includePartial]=false counts model-context rows
     * (user/assistant, non-partial); true counts replay rows (user/assistant
     * incl. partial). Default filters via paging; the Room store overrides
     * with a filtered DAO COUNT query (one query, O(1) memory).
     */
    suspend fun countHistoryEvents(sessionId: String, includePartial: Boolean): Int {
        var total = 0
        var afterSeq = 0L
        while (true) {
            val page = loadEvents(sessionId, afterSeq, 500)
            if (page.isEmpty()) break
            total += page.count { e ->
                (e.kind == "user" || e.kind == "assistant") && (includePartial || !e.isPartial)
            }
            afterSeq = page.last().seq
            if (page.size < 500) break
        }
        return total
    }

    /**
     * R25-1 far-marker existence probe (bounded, fail-closed): true when the
     * session holds any payload-free `reasoning` row with `seq < [beforeSeq]`
     * (same session only). Backs the exact-cap far-marker guard in
     * [loadModelHistoryScan]: the scan probes strictly below its R24-2
     * extra-page floor (more than one backward page below the kept window),
     * so an adjacent boundary marker already collected by the extra page never
     * sets it (R24-2 precise flag preserved); only a truly far marker does,
     * forcing conservative `reasoningIncomplete=true` in [loadModelHistory]
     * (all kept assistants suspect, like `dropped > 0`). Over-flags by design
     * when the far marker's assistant is no longer kept (Target: pair-aware
     * narrowing). Single COUNT query on Room (O(1) memory); the default pages
     * test-scale fakes with early exit. Throws propagate to the caller, which
     * treats them as suspect (fail-closed).
     */
    suspend fun hasReasoningBefore(sessionId: String, beforeSeq: Long): Boolean {
        var afterSeq = 0L
        while (true) {
            val page = loadEvents(sessionId, afterSeq, 500)
            if (page.isEmpty()) break
            for (e in page) {
                if (e.seq >= beforeSeq) continue
                if (e.kind == "reasoning") return true
            }
            afterSeq = page.last().seq
            if (page.size < 500) break
            // Bounded scan guard: only rows below beforeSeq matter; once the
            // forward cursor passes beforeSeq no later page can match.
            if (afterSeq >= beforeSeq) break
        }
        return false
    }

    /** Export one session as pure JSONL (no header; `jq`-parseable, spec §8.5). */
    suspend fun exportJsonl(sessionId: String, destFile: File)

    /** Import JSONL; validates every line, rewrites to a new sessionId (spec §8.5). */
    suspend fun importJsonl(srcFile: File): String

    /** Apply [PrunePolicy] (spec §8.3). */
    suspend fun prune(policy: PrunePolicy): PruneResult

    /**
     * S4-F4 prune-marker suspect (hotfix b, small) + R26-1/R27 family-aware
     * (Current): true when the LATEST [prune] sweep deleted rows for
     * [sessionId] (or left a `deleted==0` head-orphan residue), so a cut
     * family may have existed. Callers ([loadModelHistory]) treat kept
     * assistants as reasoning-suspect (fail-closed) when true. Per-sweep,
     * NOT sticky: a later clean sweep (`deleted==0` AND user/empty
     * post-fixup head) clears it, [deleteSession] clears it, appends never
     * clear it. R26-1/R27 prune deletes the cut HEAD family only (user
     * boundary; unbounded convergent fixup, unconditionally in the same tx
     * with no `deletes>0` gate), so new prunes leave no user-less assistant
     * behind and restart needs no durable flag for new prunes (orphan shape
     * durably absent; memory pre-publish only closes the delete→publish
     * race). Pre-R26/interior orphans below the head are NOT deleted by the
     * fixup: while suspect is held they hide behind the all-flag, but a
     * clean sweep or a restart (memory-only set is dropped) resumes them
     * compat when no truncation evidence exists (known limitation). Readers
     * additionally drop head orphans when `dropped>0` or suspect (far-only
     * never drops, preserving R25 precision).
     * Test-fake divergence: `SuspectFakeStore.simulatePruneCut` stays R26-only
     * (`drop>0` gate + 10-cap + unconditional clear); R27 proven by Room tests.
     * Default false so hand-written fakes without prune stay clean.
     */
    suspend fun isPruneSuspect(sessionId: String): Boolean = false

    /** Delete a session; events cascade. */
    suspend fun deleteSession(sessionId: String)
}
