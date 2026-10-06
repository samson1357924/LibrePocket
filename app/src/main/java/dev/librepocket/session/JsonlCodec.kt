package dev.librepocket.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Closed event-kind set (spec §8.5 import validation). */
val VALID_KINDS: Set<String> = setOf("user", "assistant", "tool", "steer", "retry", "system")

/**
 * One JSONL line (spec §8.5): flat object, no header line, so `jq` reads it directly.
 * Example: `{"seq":1,"runId":"…","kind":"user","text":"…","imagesOmitted":0,"createdAt":…}`.
 */
data class JsonlLine(
    val seq: Long,
    val runId: String,
    val kind: String,
    val text: String,
    val imagesOmitted: Int = 0,
    val createdAt: Long,
)

/**
 * JSONL encode/decode for transcript events.
 *
 * Built on kotlinx-serialization-json's tree API (no compiler plugin needed):
 * decoding still goes through `Json.parseToJsonElement`, so malformed lines
 * fail centrally with a line-number-only error.
 */
object JsonlCodec {
    private val json = Json

    fun encode(event: TranscriptEvent): String =
        buildJsonObject {
            put("seq", event.seq)
            put("runId", event.runId)
            put("kind", event.kind)
            put("text", event.text)
            put("imagesOmitted", event.imagesOmitted)
            put("createdAt", event.createdAt)
        }.toString()

    /** Parses one line; throws [IllegalArgumentException] naming only the line number. */
    fun decode(lineNumber: Int, line: String): JsonlLine {
        fun fail(cause: Throwable? = null): Nothing =
            throw IllegalArgumentException("import failed at line $lineNumber", cause)
        val obj = try {
            json.parseToJsonElement(line).jsonObject
        } catch (e: Exception) {
            fail(e)
        }
        fun str(key: String): String {
            val prim = obj[key]?.jsonPrimitive ?: fail()
            if (!prim.isString) fail()
            return prim.content
        }
        val seq = obj["seq"]?.jsonPrimitive?.longOrNull ?: fail()
        val imagesOmitted = obj["imagesOmitted"]?.jsonPrimitive?.intOrNull ?: fail()
        val createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: fail()
        val parsed = JsonlLine(
            seq = seq,
            runId = str("runId"),
            kind = str("kind"),
            text = str("text"),
            imagesOmitted = imagesOmitted,
            createdAt = createdAt,
        )
        if (parsed.kind !in VALID_KINDS) fail()
        // Reject nonsensical fields early (spec §8.5): seqs start at 1 and the
        // importer additionally requires strict increase; negative counters /
        // timestamps would pollute ordering and prune math.
        if (parsed.seq < 1) fail()
        if (parsed.runId.isBlank()) fail()
        if (parsed.imagesOmitted < 0) fail()
        if (parsed.createdAt < 0) fail()
        return parsed
    }
}
