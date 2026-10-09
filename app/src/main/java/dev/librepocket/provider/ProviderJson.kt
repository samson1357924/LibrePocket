package dev.librepocket.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared request tool-wire JSON fragments (Phase 1).
 *
 * Local-tool fragments for all three adapters are built as a JSON AST
 * ([buildJsonObject]); raw string concatenation survives only for leaf
 * string values, which the JSON encoder quotes ([put] funnels strings into
 * `JsonPrimitive`). Structural braces/brackets therefore close exactly once
 * by construction, and every adapter shares one encoder path.
 *
 * [ToolSchema.jsonSchema] is embedded via [Json.parseToJsonElement]
 * (strict): a malformed schema fails fast with [IllegalArgumentException]
 * (mapped to a fatal provider failure upstream) instead of emitting an
 * invalid request body. Key order in each fragment preserves the previous
 * wire shape.
 */

/** Parse an already-balanced schema fragment strictly. */
internal fun toolSchemaElement(schemaJson: String) = Json.parseToJsonElement(schemaJson)

/** Responses `{"type":"function","name":…,"description":…,"parameters":…}`. */
internal fun responsesFunctionToolJson(t: ToolSchema): String = buildJsonObject {
    put("type", "function")
    put("name", t.name)
    put("description", t.description)
    put("parameters", toolSchemaElement(t.jsonSchema))
}.toString()

/** Chat Completions `{"type":"function","function":{"name":…,"description":…,"parameters":…}}`. */
internal fun chatFunctionToolJson(t: ToolSchema): String = buildJsonObject {
    put("type", "function")
    put("function", buildJsonObject {
        put("name", t.name)
        put("description", t.description)
        put("parameters", toolSchemaElement(t.jsonSchema))
    })
}.toString()

/** Anthropic `{"name":…,"description":…,"input_schema":…}`. */
internal fun anthropicToolJson(t: ToolSchema): String = buildJsonObject {
    put("name", t.name)
    put("description", t.description)
    put("input_schema", toolSchemaElement(t.jsonSchema))
}.toString()

/**
 * Chat Completions assistant `tool_calls[]` element.
 * `argumentsJson` stays a JSON-encoded STRING (quoted), matching the
 * Chat Completions wire shape (`function.arguments` is a string).
 * Blank arguments are normalised to `"{}"` so an empty aggregation never
 * emits an empty string the server would reject; the normalisation is the
 * request-side counterpart of the mapper's missing-args repair.
 */
internal fun chatToolCallJson(tc: ToolCall): String = buildJsonObject {
    put("id", tc.id)
    put("type", "function")
    put("function", buildJsonObject {
        put("name", tc.name)
        put("arguments", tc.argumentsJson.ifBlank { "{}" })
    })
}.toString()

/**
 * Phase 2: role / call-id pairing + round-trip semantics.
 *
 * Production still sends no tools ([dev.librepocket.chat.TurnController]
 * stays pure-chat); these helpers only validate explicit test/scaffold
 * histories. Any illegal pairing throws [ProviderFailure] (fail-closed,
 * retryable=false) so no request is sent.
 */

/**
 * Phase 2 (P1 follow-up): single-chronological-pass exactly-once tool pairing.
 *
 * Production still sends no tools ([dev.librepocket.chat.TurnController]
 * stays pure-chat); this only validates explicit test/scaffold histories.
 * Any illegal pairing throws [ProviderFailure] (fail-closed,
 * retryable=false) so no request is sent.
 *
 * State machine over one chronological scan:
 * - `seen`: every declared call id (rejects TOOL_CALL_DUPLICATE_ID).
 * - `pending`: ids of the current assistant tool turn not yet answered.
 * - `consumed`: ids already answered (rejects TOOL_OUTPUT_DUPLICATE_ID).
 * - `toolCalls` non-empty: role must be assistant; each entry is checked for
 *   blank id/name, duplicates and argument shape; a new batch arriving while
 *   `pending` is non-empty means the previous turn never drained ->
 *   TOOL_CALLS_UNRESOLVED.
 * - `role == tool`: images are rejected; a stored id is used as-is while a
 *   blank id is backfilled only when [allowChatBackfill] (Chat-only) and
 *   exactly one call is pending (else MISSING_ID / AMBIGUOUS_ID); answered
 *   ids reject replays, ids outside `pending` (forward refs included) reject
 *   as orphans.
 * - Any other role (user / call-free assistant / system) with non-empty
 *   `pending` closes a turn with output missing -> TOOL_CALLS_UNRESOLVED.
 *   System messages are skipped at emit time but still count as a turn
 *   boundary here (a leading system sees empty `pending`, so no effect).
 * - Non-empty `pending` at end of scan -> TOOL_CALLS_UNRESOLVED.
 *
 * @param allowChatBackfill Chat-only single-pending backfill for blank ids;
 *   Responses/Anthropic pass false and always fail blank ids with MISSING_ID.
 * @return list aligned with [messages]: tool positions carry the resolved id,
 *   every other position is null.
 */
internal fun validateToolPairing(messages: List<ChatMessage>, allowChatBackfill: Boolean): List<String?> {
    val resolved = MutableList<String?>(messages.size) { null }
    val seen = LinkedHashSet<String>()
    val pending = LinkedHashSet<String>()
    val consumed = LinkedHashSet<String>()
    for ((index, m) in messages.withIndex()) {
        if (m.toolCalls.isNotEmpty()) {
            if (m.role != "assistant") throw ProviderFailure(false, "TOOL_CALLS_ROLE_MUST_BE_ASSISTANT")
            val batch = LinkedHashSet<String>()
            for (tc in m.toolCalls) {
                if (tc.id.isBlank()) throw ProviderFailure(false, "TOOL_CALL_MISSING_ID")
                if (tc.name.isBlank()) throw ProviderFailure(false, "TOOL_CALL_MISSING_NAME")
                if (tc.id in seen || !batch.add(tc.id)) throw ProviderFailure(false, "TOOL_CALL_DUPLICATE_ID")
                validateToolArgumentsObject(tc.argumentsJson)
            }
            if (pending.isNotEmpty()) throw ProviderFailure(false, "TOOL_CALLS_UNRESOLVED")
            seen.addAll(batch)
            pending.addAll(batch)
            continue
        }
        if (m.role == "tool") {
            if (m.images.isNotEmpty()) throw ProviderFailure(false, "TOOL_OUTPUT_IMAGES_NOT_SUPPORTED")
            val stored = m.toolCallId
            val id: String = if (!stored.isNullOrBlank()) {
                stored
            } else if (allowChatBackfill && pending.size == 1) {
                pending.single()
            } else if (!allowChatBackfill) {
                throw ProviderFailure(false, "TOOL_OUTPUT_MISSING_ID")
            } else if (pending.isEmpty()) {
                throw ProviderFailure(false, "TOOL_OUTPUT_MISSING_ID")
            } else {
                throw ProviderFailure(false, "TOOL_OUTPUT_AMBIGUOUS_ID")
            }
            if (id in consumed) throw ProviderFailure(false, "TOOL_OUTPUT_DUPLICATE_ID")
            if (id !in pending) throw ProviderFailure(false, "TOOL_OUTPUT_ORPHAN_ID")
            pending.remove(id)
            consumed.add(id)
            resolved[index] = id
            continue
        }
        if (pending.isNotEmpty()) throw ProviderFailure(false, "TOOL_CALLS_UNRESOLVED")
    }
    if (pending.isNotEmpty()) throw ProviderFailure(false, "TOOL_CALLS_UNRESOLVED")
    return resolved
}

/** Non-blank arguments must be a JSON object; blank means `{}` (filled by callers). */
internal fun validateToolArgumentsObject(argumentsJson: String) {
    if (argumentsJson.isBlank()) return
    try {
        val el = Json.parseToJsonElement(argumentsJson)
        if (el !is JsonObject) throw ProviderFailure(false, "TOOL_ARGUMENTS_MUST_BE_OBJECT")
    } catch (e: ProviderFailure) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw ProviderFailure(false, "TOOL_ARGUMENTS_INVALID")
    }
}

/** Anthropic `input` / object-form arguments: blank -> `{}`, else strict object. */
internal fun toolInputElement(argumentsJson: String) = try {
    if (argumentsJson.isBlank()) {
        buildJsonObject { }
    } else {
        when (val el = Json.parseToJsonElement(argumentsJson)) {
            is JsonObject -> el
            else -> throw ProviderFailure(false, "TOOL_ARGUMENTS_MUST_BE_OBJECT")
        }
    }
} catch (e: ProviderFailure) {
    throw e
} catch (e: IllegalArgumentException) {
    throw ProviderFailure(false, "TOOL_ARGUMENTS_INVALID")
}

/** Responses `{"type":"function_call","call_id":…,"name":…,"arguments":…(string)}`. */
internal fun responsesFunctionCallJson(tc: ToolCall): String = buildJsonObject {
    put("type", "function_call")
    put("call_id", tc.id)
    put("name", tc.name)
    put("arguments", tc.argumentsJson.ifBlank { "{}" })
}.toString()

/** Responses `{"type":"function_call_output","call_id":…,"output":…}`. */
internal fun responsesFunctionCallOutputJson(callId: String, output: String): String = buildJsonObject {
    put("type", "function_call_output")
    put("call_id", callId)
    put("output", output)
}.toString()

/** Anthropic assistant `{"type":"tool_use","id":…,"name":…,"input":…(object)}`. */
internal fun anthropicToolUseJson(tc: ToolCall): String = buildJsonObject {
    put("type", "tool_use")
    put("id", tc.id)
    put("name", tc.name)
    put("input", toolInputElement(tc.argumentsJson))
}.toString()

/** Anthropic user `{"type":"tool_result","tool_use_id":…,"content":…}`. */
internal fun anthropicToolResultJson(toolUseId: String, text: String): String = buildJsonObject {
    put("type", "tool_result")
    put("tool_use_id", toolUseId)
    put("content", text)
}.toString()
