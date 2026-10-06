package dev.librepocket.backup

import dev.librepocket.redact.Redactor
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Audit event kinds (D07 minimal).
 *
 * The log stores COUNTS and reason codes only — never message bodies, keys,
 * or other sensitive原文. [detail] is a short machine string
 * (`events=3`, `first-confirm=yes`); free text is never accepted.
 */
enum class AuditType {
    BACKUP_CREATED,
    RESTORE_DONE,
    REDACTED_EXPORT,
    PLAINTEXT_REQUESTED,
    PLAINTEXT_CONFIRMED,
    PLAINTEXT_DENIED,
}

/** One audit row: type + session scope + redacted detail + timestamp. */
data class AuditRecord(
    val type: AuditType,
    val sessionId: String,
    val detail: String,
    val atMs: Long,
)

/**
 * In-memory audit log for backup/export flows.
 *
 * - [record] is the only write path; callers MUST pass machine strings only
 *   (ids, counters, yes/no flags). The signature accepts a free-text [detail]
 *   for forward-compat, so this is a convention, not a type-level guarantee.
 * - [exportJson] runs [Redactor.redact] over the serialized rows as a
 *   best-effort second pass; heuristic redaction cannot guarantee zero leakage
 *   for unknown secret formats.
 */
class AuditLog {
    private val records = mutableListOf<AuditRecord>()
    private val lock = Any()

    fun record(
        type: AuditType,
        sessionId: String,
        detail: String,
        atMs: Long = System.currentTimeMillis(),
    ): AuditRecord {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        val row = AuditRecord(type, sessionId, detail, atMs)
        synchronized(lock) { records += row }
        return row
    }

    fun snapshot(): List<AuditRecord> = synchronized(lock) { records.toList() }

    fun size(): Int = synchronized(lock) { records.size }

    fun countByType(): Map<AuditType, Int> = synchronized(lock) {
        records.groupingBy { it.type }.eachCount()
    }

    /** Serializes rows as JSONL with a final redaction pass (never plaintext). */
    fun exportJson(): String = synchronized(lock) {
        records.map { r ->
            val line = buildJsonObject {
                put("type", r.type.name)
                put("sessionId", r.sessionId)
                put("detail", r.detail)
                put("atMs", r.atMs)
            }.toString()
            Redactor.redact(line).text
        }.joinToString("\n", postfix = if (records.isEmpty()) "" else "\n")
    }
}
