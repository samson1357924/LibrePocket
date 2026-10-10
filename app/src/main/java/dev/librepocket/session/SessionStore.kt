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

    /** Export one session as pure JSONL (no header; `jq`-parseable, spec §8.5). */
    suspend fun exportJsonl(sessionId: String, destFile: File)

    /** Import JSONL; validates every line, rewrites to a new sessionId (spec §8.5). */
    suspend fun importJsonl(srcFile: File): String

    /** Apply [PrunePolicy] (spec §8.3). */
    suspend fun prune(policy: PrunePolicy): PruneResult

    /** Delete a session; events cascade. */
    suspend fun deleteSession(sessionId: String)
}
