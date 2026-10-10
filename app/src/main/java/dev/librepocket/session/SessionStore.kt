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
 * groups by logical family (`parentRunId ?: runId`): any assistant row in the
 * family (completed or partial-failed, including retried partials) completes
 * the family, so a retry→success turn is never mis-marked INTERRUPTED.
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
    val runId: String, // logical id for user/retry rows; fresh attempt id for assistant/tool/usage rows
    val kind: String, // "user" | "assistant" | "tool" | "steer" | "retry" | "system"
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

    /** Append one event; returns the rowId. */
    suspend fun appendEvent(event: TranscriptEvent): Long

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
        return all.filter { it.seq < beforeSeq }.takeLast(limit).reversed()
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

    /** Export one session as pure JSONL (no header; `jq`-parseable, spec §8.5). */
    suspend fun exportJsonl(sessionId: String, destFile: File)

    /** Import JSONL; validates every line, rewrites to a new sessionId (spec §8.5). */
    suspend fun importJsonl(srcFile: File): String

    /** Apply [PrunePolicy] (spec §8.3). */
    suspend fun prune(policy: PrunePolicy): PruneResult

    /** Delete a session; events cascade. */
    suspend fun deleteSession(sessionId: String)
}
