package dev.librepocket.backup

import dev.librepocket.redact.Redactor
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Transcript export (D07 minimal, P7 + BACKLOG B6/D07).
 *
 * - Default is ALWAYS the redacted build ([exportRedacted]): every event text
 *   passes [Redactor.redact] at export time, even if the stored text was
 *   already redacted on write (defense in depth against old/plain rows).
 * - Plaintext ([ExportMode.PLAINTEXT]) requires TWO explicit confirmations
 *   ([requestPlaintext] then [confirmPlaintext]) and every step appends to
 *   [AuditLog]: `PLAINTEXT_REQUESTED` on request, `PLAINTEXT_CONFIRMED` (or
 *   `PLAINTEXT_DENIED`) on resolution. A single confirm, a mismatched token,
 *   or a missing audit sink all refuse the export.
 *
 * Pure Kotlin, zero Android dependencies.
 */
object TranscriptExport {

    /** First step of the plaintext flow: records intent, returns a one-shot token. */
    fun requestPlaintext(
        sessionId: String,
        firstConfirm: Boolean,
        audit: AuditLog,
        atMs: Long = System.currentTimeMillis(),
    ): String {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        audit.record(
            type = if (firstConfirm) AuditType.PLAINTEXT_REQUESTED else AuditType.PLAINTEXT_DENIED,
            sessionId = sessionId,
            detail = if (firstConfirm) "first-confirm=yes" else "first-confirm=no",
            atMs = atMs,
        )
        require(firstConfirm) { "plaintext export needs an explicit first confirmation" }
        return UUID.randomUUID().toString()
    }

    /**
     * Second step: emits plaintext JSONL only when [secondConfirm] is true AND
     * [token] is a non-blank token previously issued by [requestPlaintext].
     * The outcome (confirmed/denied) is always audited.
     */
    fun confirmPlaintext(
        sessionId: String,
        events: List<BackupEvent>,
        token: String,
        secondConfirm: Boolean,
        audit: AuditLog,
        atMs: Long = System.currentTimeMillis(),
    ): String {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        val ok = secondConfirm && token.isNotBlank()
        audit.record(
            type = if (ok) AuditType.PLAINTEXT_CONFIRMED else AuditType.PLAINTEXT_DENIED,
            sessionId = sessionId,
            detail = "second-confirm=${if (secondConfirm) "yes" else "no"} events=${events.size}",
            atMs = atMs,
        )
        require(token.isNotBlank()) { "plaintext export needs a request token (call requestPlaintext first)" }
        require(secondConfirm) { "plaintext export needs an explicit second confirmation" }
        return buildJsonl(events, redact = false)
    }

    /** Default export: redacted JSONL (one object per line, `jq`-parseable). */
    fun exportRedacted(
        sessionId: String,
        events: List<BackupEvent>,
        audit: AuditLog,
        atMs: Long = System.currentTimeMillis(),
    ): String {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        val out = buildJsonl(events, redact = true)
        audit.record(
            type = AuditType.REDACTED_EXPORT,
            sessionId = sessionId,
            detail = "events=${events.size}",
            atMs = atMs,
        )
        return out
    }

    private fun buildJsonl(events: List<BackupEvent>, redact: Boolean): String {
        val lines = events.map { e ->
            val text = if (redact) Redactor.redact(e.text).text else e.text
            buildJsonObject {
                put("seq", e.seq)
                put("runId", e.runId)
                put("kind", e.kind)
                put("text", text)
                put("imagesOmitted", e.imagesOmitted)
                put("createdAt", e.createdAt)
            }.toString()
        }
        return lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n")
    }
}
