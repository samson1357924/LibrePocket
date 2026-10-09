package dev.librepocket.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
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
 *
 * Phase 3 (implemented): cancelled / failed assistant rows additionally carry
 * `isPartial` (boolean; legacy encoders may write 0/1, both decode) and
 * `failureReason` (string, omitted when null). Both keys are optional on
 * decode: lines exported before Phase 3 have neither and read back as
 * `isPartial=false` / `failureReason=null`.
 */
data class JsonlLine(
    val seq: Long,
    val runId: String,
    val kind: String,
    val text: String,
    val imagesOmitted: Int = 0,
    val createdAt: Long,
    val isPartial: Boolean = false,
    val failureReason: String? = null,
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
            put("isPartial", event.isPartial)
            if (event.failureReason != null) put("failureReason", event.failureReason)
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
        // Phase 3 optional keys: absent (pre-Phase-3 exports) → defaults.
        // `isPartial` accepts booleans and legacy 0/1 ints; anything else fails.
        val isPartial = when (val raw = obj["isPartial"]) {
            null, is JsonNull -> false
            else -> {
                val prim = raw.jsonPrimitive
                prim.booleanOrNull ?: when (prim.intOrNull) {
                    0 -> false
                    1 -> true
                    else -> fail()
                }
            }
        }
        val failureReason = when (val raw = obj["failureReason"]) {
            null, is JsonNull -> null
            else -> {
                val prim = raw.jsonPrimitive
                if (!prim.isString) fail()
                prim.content
            }
        }
        val parsed = JsonlLine(
            seq = seq,
            runId = str("runId"),
            kind = str("kind"),
            text = str("text"),
            imagesOmitted = imagesOmitted,
            createdAt = createdAt,
            isPartial = isPartial,
            failureReason = failureReason,
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
