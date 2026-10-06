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
 *   `PLAINTEXT_DENIED`) on resolution. A single confirm, a forged/expired/
 *   cross-session/replayed token, or a second-confirm denial all refuse export.
 *
 * Pure Kotlin, zero Android dependencies.
 */
object TranscriptExport {

    /** Pending one-shot plaintext tokens: token -> (sessionId, issuedAtMs). */
    private val pendingTokens = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    /** Token validity window (5 min); expired tokens are purged on use. */
    const val TOKEN_TTL_MS = 5 * 60 * 1000L

    /** First step of the plaintext flow: records intent, returns a one-shot token. */
    fun requestPlaintext(
        sessionId: String,
        firstConfirm: Boolean,
        audit: AuditLog,
        atMs: Long = System.currentTimeMillis(),
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        // Best-effort purge of abandoned tokens (request without confirm leaks one entry).
        pendingTokens.entries.removeIf { nowMs - it.value.second > TOKEN_TTL_MS }
        audit.record(
            type = if (firstConfirm) AuditType.PLAINTEXT_REQUESTED else AuditType.PLAINTEXT_DENIED,
            sessionId = sessionId,
            detail = if (firstConfirm) "first-confirm=yes" else "first-confirm=no",
            atMs = atMs,
        )
        require(firstConfirm) { "plaintext export needs an explicit first confirmation" }
        val token = UUID.randomUUID().toString()
        pendingTokens[token] = sessionId to nowMs
        return token
    }

    /**
     * Second step: emits plaintext JSONL only when [secondConfirm] is true AND
     * [token] is a live token issued by [requestPlaintext] for the same
     * [sessionId]. Tokens are single-use (atomically consumed), session-bound,
     * and expire [TOKEN_TTL_MS] after issue. The outcome (confirmed/denied)
     * is always audited.
     *
     * Threat model: the token guards the double-confirm UX flow against
     * accidental/single-click export. The caller already holds [events] in
     * memory, so this is not a boundary against malicious code — only
     * against flow bypass. [nowMs] defaults to the system clock; tests may
     * pass explicit values. [atMs] is audit timestamp only and never affects
     * expiry.
     */
    fun confirmPlaintext(
        sessionId: String,
        events: List<BackupEvent>,
        token: String,
        secondConfirm: Boolean,
        audit: AuditLog,
        atMs: Long = System.currentTimeMillis(),
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        // Atomic consume: exactly one concurrent confirmer can win the token.
        val issued = pendingTokens.remove(token)
        val tokenOk = issued != null &&
            issued.first == sessionId &&
            nowMs - issued.second in 0..TOKEN_TTL_MS
        audit.record(
            type = if (ok(secondConfirm, tokenOk)) AuditType.PLAINTEXT_CONFIRMED else AuditType.PLAINTEXT_DENIED,
            sessionId = sessionId,
            detail = "second-confirm=${if (secondConfirm) "yes" else "no"} events=${events.size}",
            atMs = atMs,
        )
        require(tokenOk) { "plaintext export needs a valid request token (call requestPlaintext first)" }
        require(secondConfirm) { "plaintext export needs an explicit second confirmation" }
        return buildJsonl(events, redact = false)
    }

    private fun ok(secondConfirm: Boolean, tokenOk: Boolean) = secondConfirm && tokenOk

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
