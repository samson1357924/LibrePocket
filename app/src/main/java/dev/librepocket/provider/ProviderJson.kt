package dev.librepocket.provider

import kotlinx.serialization.json.Json
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
 */
internal fun chatToolCallJson(tc: ToolCall): String = buildJsonObject {
    put("id", tc.id)
    put("type", "function")
    put("function", buildJsonObject {
        put("name", tc.name)
        put("arguments", tc.argumentsJson)
    })
}.toString()
