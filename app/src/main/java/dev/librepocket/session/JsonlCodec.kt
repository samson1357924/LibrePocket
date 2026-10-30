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
val VALID_KINDS: Set<String> = setOf("user", "assistant", "tool", "steer", "retry", "system", "reasoning")

/**
 * One JSONL line (spec §8.5): flat object, no header line, so `jq` reads it directly.
 * Example: `{"seq":1,"runId":"…","kind":"user","text":"…","imagesOmitted":0,"createdAt":…}`.
 *
 * Phase 3 (implemented): cancelled / failed assistant rows additionally carry
 * `isPartial` (boolean; legacy encoders may write 0/1, both decode) and
 * `failureReason` (string, omitted when null). Both keys are optional on
 * decode: lines exported before Phase 3 have neither and read back as
 * `isPartial=false` / `failureReason=null`.
 *
 * Stage C (implemented): attempt-bound assistant terminals additionally carry
 * `parentRunId` (logical turn id, omitted when null) and `attemptIndex`
 * (0-based attempt number, omitted when null). Both keys are optional on
 * decode: pre-C lines have neither and read back as null (single-id legacy
 * semantics: family = `runId`).
 *
 * Stage F (implemented): final vs intermediate partial is carried as
 * `isFinal` (boolean; legacy encoders may write 0/1, both decode). The key is
 * optional on decode: pre-F lines have no key and read back as `isFinal=true`
 * (old single-terminal semantics). Encoders always write the key.
 *
 * Phase 5 (implemented): an `assistant` row with `isFinal=false` must also
 * carry `isPartial=true` — production writes intermediate partials only in
 * that shape (see `SessionTranscriptSink`), while the model-context
 * projection filters `!isPartial` and the backfill treats `isFinal=false`
 * as non-terminal. A crafted `assistant` line with `isFinal=false` and
 * `isPartial=false` would dangle forever yet still enter model context, so
 * decode rejects it fail-closed. `reasoning` flag-only markers legitimately
 * carry `isFinal=false` with `isPartial=false` and stay accepted.
 * `isTruncated` records Room's prior text truncation; absent in older files
 * defaults to false and cannot establish that their original text was complete.
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
    val parentRunId: String? = null,
    val attemptIndex: Int? = null,
    val isFinal: Boolean = true,
    val isTruncated: Boolean = false,
)

/**
 * JSONL encode/decode for transcript events.
 *
 * Built on kotlinx-serialization-json's tree API (no compiler plugin needed):
 * decoding still goes through `Json.parseToJsonElement`, so malformed lines
 * fail centrally with a line-number-only error.
 *
 * S2 fail-closed gates (production write parity, see `SessionTranscriptSink`).
 *
 * Current (S2 implemented): the three gates below are enforced on every
 * import decode; Target keeps this fail-closed posture — any cap change must
 * update the codec constant and the S2 boundary tests together.
 * - F2 shape: `parentRunId`/`attemptIndex` only on `assistant`/`reasoning`;
 *   `failureReason` only on `assistant` (production never writes a system
 *   `failureReason` column — legacy failures are `system` text rows); `isFinal=false`
 *   only on `assistant`/`reasoning`. Pre-F lines without the key still default
 *   to `true` and are unaffected.
 * - F7 caps: `runId`/`parentRunId` ≤ 256 chars, `failureReason` ≤ 4096 chars
 *   (production `failureReason` is `Redactor.redactError`-capped at 500, so
 *   4096 is a generous safety margin sitting well above any legit row;
 *   `text` is bounded after redaction by the importer — the decode caps exist to fail
 *   a single 19MB id/reason column before it reaches the DB).
 *   `createdAt` has no decode upper bound by design (clock skew must not kill
 *   an import); only negativity is rejected.
 * - F11 types: every field read is gated so `null`/object/array/wrong-primitive
 *   all surface as `IAE("import failed at line N")` with no content echo.
 */
object JsonlCodec {
    private val json = Json

    /** S2 F7: id columns stay bounded (production ids are UUID-scale, <60). */
    const val MAX_RUN_ID_CHARS = 256

    /** S2 F7: reason cap (4096) sits far above `Redactor.ERROR_MAX_CHARS` (500) — generous, never clips legit rows. */
    const val MAX_FAILURE_REASON_CHARS = 4096

    fun encode(event: TranscriptEvent, isTruncated: Boolean = event.isTruncated): String =
        buildJsonObject {
            put("seq", event.seq)
            put("runId", event.runId)
            put("kind", event.kind)
            put("text", event.text)
            put("imagesOmitted", event.imagesOmitted)
            put("createdAt", event.createdAt)
            put("isPartial", event.isPartial)
            put("isFinal", event.isFinal)
            if (isTruncated) put("isTruncated", true)
            if (event.failureReason != null) put("failureReason", event.failureReason)
            if (event.parentRunId != null) put("parentRunId", event.parentRunId)
            if (event.attemptIndex != null) put("attemptIndex", event.attemptIndex)
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
        // F11: every field read is gated so a JsonNull/object/array (whose
        // `jsonPrimitive` throws) or any other structural mismatch surfaces as
        // the line-number-only IAE, never the raw kotlinx message. A `fail()`
        // inside the block already carries the right message and is rethrown.
        fun <T> gated(block: () -> T): T {
            try {
                return block()
            } catch (e: IllegalArgumentException) {
                if (e.message?.startsWith("import failed at line") == true) throw e
                fail(e)
            } catch (e: Exception) {
                fail(e)
            }
        }
        fun str(key: String): String = gated {
            val prim = obj[key]?.jsonPrimitive ?: fail()
            if (!prim.isString) fail()
            prim.content
        }
        val seq: Long = gated {
            val prim = obj["seq"]?.jsonPrimitive ?: fail()
            if (prim.isString) fail()
            prim.longOrNull ?: fail()
        }
        val imagesOmitted: Int = gated {
            val prim = obj["imagesOmitted"]?.jsonPrimitive ?: fail()
            if (prim.isString) fail()
            prim.intOrNull ?: fail()
        }
        val createdAt: Long = gated {
            val prim = obj["createdAt"]?.jsonPrimitive ?: fail()
            if (prim.isString) fail()
            prim.longOrNull ?: fail()
        }
        // Phase 3 optional keys: absent (pre-Phase-3 exports) → defaults.
        // `isPartial` accepts booleans and legacy 0/1 ints; anything else fails.
        // S2 F11: JSON strings are wrong primitives even when numeric-shaped
        // (`longOrNull`/`intOrNull`/`booleanOrNull` parse string content, so an
        // explicit `isString` guard keeps `seq:"1"` / `isPartial:"x"` fail-closed).
        val isPartial: Boolean = gated {
            when (val raw = obj["isPartial"]) {
                null, is JsonNull -> false
                else -> {
                    val prim = raw.jsonPrimitive
                    if (prim.isString) fail()
                    prim.booleanOrNull ?: when (prim.intOrNull) {
                        0 -> false
                        1 -> true
                        else -> fail()
                    }
                }
            }
        }
        val failureReason: String? = gated {
            when (val raw = obj["failureReason"]) {
                null, is JsonNull -> null
                else -> {
                    val prim = raw.jsonPrimitive
                    if (!prim.isString) fail()
                    prim.content
                }
            }
        }
        // Stage C optional keys: absent (pre-C exports) → null (single-id legacy).
        val parentRunId: String? = gated {
            when (val raw = obj["parentRunId"]) {
                null, is JsonNull -> null
                else -> {
                    val prim = raw.jsonPrimitive
                    if (!prim.isString) fail()
                    prim.content
                }
            }
        }
        val attemptIndex: Int? = gated {
            when (val raw = obj["attemptIndex"]) {
                null, is JsonNull -> null
                else -> {
                    val prim = raw.jsonPrimitive
                    if (prim.isString) fail()
                    prim.intOrNull ?: fail()
                }
            }
        }
        // Stage F optional key: absent (pre-F exports) → true (single-terminal legacy).
        // Accepts booleans and legacy 0/1 ints; anything else fails.
        val isFinal: Boolean = gated {
            when (val raw = obj["isFinal"]) {
                null, is JsonNull -> true
                else -> {
                    val prim = raw.jsonPrimitive
                    if (prim.isString) fail()
                    prim.booleanOrNull ?: when (prim.intOrNull) {
                        0 -> false
                        1 -> true
                        else -> fail()
                    }
                }
            }
        }
        val isTruncated: Boolean = gated {
            when (val raw = obj["isTruncated"]) {
                null -> false
                else -> {
                    val prim = raw.jsonPrimitive
                    if (prim.isString) fail()
                    prim.booleanOrNull ?: fail()
                }
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
            parentRunId = parentRunId,
            attemptIndex = attemptIndex,
            isFinal = isFinal,
            isTruncated = isTruncated,
        )
        if (parsed.kind !in VALID_KINDS) fail()
        // Phase 5: an assistant intermediate partial must stay partial. A
        // crafted non-partial non-final assistant row would never close its
        // family (backfill treats isFinal=false as non-terminal) yet still
        // enter model context (projection filters !isPartial only) — reject
        // fail-closed. Reasoning flag-only markers are exempt by contract.
        if (parsed.kind == "assistant" && !parsed.isFinal && !parsed.isPartial) fail()
        // The reasoning marker is payload-free by contract (same runId as its
        // assistant event, empty text); a non-empty body is rejected like an
        // unknown kind so imports cannot smuggle reasoning content into the store.
        if (parsed.kind == "reasoning" && parsed.text.isNotEmpty()) fail()
        // Reject nonsensical fields early (spec §8.5): seqs start at 1 and the
        // importer additionally requires strict increase; negative counters /
        // timestamps would pollute ordering and prune math.
        if (parsed.seq < 1) fail()
        if (parsed.runId.isBlank()) fail()
        if (parsed.imagesOmitted < 0) fail()
        if (parsed.createdAt < 0) fail()
        if (parsed.parentRunId != null && parsed.parentRunId.isBlank()) fail()
        if (parsed.attemptIndex != null && parsed.attemptIndex < 0) fail()
        // S2 F2 shape gate (production parity, see SessionTranscriptSink):
        // attempt linkage lives only on assistant/reasoning terminals (user,
        // retry, system, tool, steer rows never carry it); failureReason lives
        // only on assistant rows (production never writes a system
        // failureReason column — legacy failures are system text rows); a
        // non-final row lives only on assistant/reasoning intermediates.
        // Missing-key pre-F rows already defaulted to final above and are
        // unaffected by the isFinal leg.
        if ((parsed.parentRunId != null || parsed.attemptIndex != null) &&
            parsed.kind != "assistant" && parsed.kind != "reasoning"
        ) fail()
        if (parsed.failureReason != null && parsed.kind != "assistant") fail()
        if (!parsed.isFinal && parsed.kind != "assistant" && parsed.kind != "reasoning") fail()
        // S2 F7 field caps: fail a single over-long id/reason column before it
        // reaches the DB (Room truncates only `text`; ids/reasons are stored
        // verbatim). Production ids are UUID-scale and reasons are
        // redactError-capped at 500, so these bounds never clip legit rows.
        if (parsed.runId.length > MAX_RUN_ID_CHARS) fail()
        if (parsed.parentRunId != null && parsed.parentRunId.length > MAX_RUN_ID_CHARS) fail()
        if (parsed.failureReason != null && parsed.failureReason.length > MAX_FAILURE_REASON_CHARS) fail()
        return parsed
    }
}
