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

/** Collect assistant-carried tool-call ids, validating each entry. */
internal fun collectAssistantToolIds(messages: List<ChatMessage>): LinkedHashSet<String> {
    val known = LinkedHashSet<String>()
    for (m in messages) {
        if (m.toolCalls.isNotEmpty() && m.role != "assistant") {
            throw ProviderFailure(false, "TOOL_CALLS_ROLE_MUST_BE_ASSISTANT")
        }
        for (tc in m.toolCalls) {
            if (tc.id.isBlank()) throw ProviderFailure(false, "TOOL_CALL_MISSING_ID")
            if (tc.name.isBlank()) throw ProviderFailure(false, "TOOL_CALL_MISSING_NAME")
            if (!known.add(tc.id)) throw ProviderFailure(false, "TOOL_CALL_DUPLICATE_ID")
            validateToolArgumentsObject(tc.argumentsJson)
        }
        if (m.role == "tool" && m.images.isNotEmpty()) {
            throw ProviderFailure(false, "TOOL_OUTPUT_IMAGES_NOT_SUPPORTED")
        }
    }
    return known
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

/** Strict tool-output pairing: id must be present and match a known assistant call. */
internal fun requireToolOutputId(toolCallId: String?, known: Set<String>): String {
    if (toolCallId.isNullOrBlank()) throw ProviderFailure(false, "TOOL_OUTPUT_MISSING_ID")
    if (toolCallId !in known) throw ProviderFailure(false, "TOOL_OUTPUT_ORPHAN_ID")
    return toolCallId
}

/**
 * Chat-only pairing: a stored id is verified; a missing id is filled only
 * when exactly one assistant call exists (single-call補齊). Empty history or
 * ambiguous multi-call histories fail closed.
 */
internal fun resolveChatToolOutputId(toolCallId: String?, known: Set<String>): String {
    if (!toolCallId.isNullOrBlank()) {
        if (toolCallId !in known) throw ProviderFailure(false, "TOOL_OUTPUT_ORPHAN_ID")
        return toolCallId
    }
    if (known.size == 1) return known.single()
    if (known.isEmpty()) throw ProviderFailure(false, "TOOL_OUTPUT_MISSING_ID")
    throw ProviderFailure(false, "TOOL_OUTPUT_AMBIGUOUS_ID")
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
