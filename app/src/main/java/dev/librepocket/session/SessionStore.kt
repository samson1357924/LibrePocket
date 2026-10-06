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
 */
data class TranscriptEvent(
    val seq: Long = 0, // DB-assigned; ignored on write
    val sessionId: String,
    val runId: String, // one turn shares a runId; a retry starts a new runId
    val kind: String, // "user" | "assistant" | "tool" | "steer" | "retry" | "system"
    val text: String, // redacted before write
    val imagesOmitted: Int = 0, // stripped image-body count (bytes never stored)
    val createdAt: Long,
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
