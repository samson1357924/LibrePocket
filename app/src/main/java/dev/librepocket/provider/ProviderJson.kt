package dev.librepocket.provider

import kotlinx.serialization.SerializationException
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
 * [ToolSchema.jsonSchema] is embedded via [toolSchemaElement]
 * (strict, fail-closed): a malformed schema throws
 * `ProviderFailure(false, "TOOL_SCHEMA_INVALID")`, a well-formed but
 * non-object root (null / array / string / number) throws
 * `ProviderFailure(false, "TOOL_SCHEMA_MUST_BE_OBJECT")`, instead of
 * emitting an invalid request body. Blank is INVALID (not `{}`), keeping
 * schema semantics distinct from arguments blank-fills. Key order in each
 * fragment preserves the previous wire shape.
 */

/** Parse an already-balanced schema fragment strictly; must be a JSON object (fail-closed). */
internal fun toolSchemaElement(schemaJson: String) = try {
    when (val el = Json.parseToJsonElement(schemaJson)) {
        is JsonObject -> el
        else -> throw ProviderFailure(false, "TOOL_SCHEMA_MUST_BE_OBJECT")
    }
} catch (e: ProviderFailure) {
    throw e
} catch (e: SerializationException) {
    // SerializationException extends IllegalArgumentException in kotlinx-serialization 1.10.0;
    // catch first so a future upstream hierarchy change still maps correctly.
    throw ProviderFailure(false, "TOOL_SCHEMA_INVALID")
} catch (e: IllegalArgumentException) {
    throw ProviderFailure(false, "TOOL_SCHEMA_INVALID")
}

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
 *
 * Non-thinking only: reasoning-bearing histories fail closed via
 * [requireNoReasoningHistory] (see below) before any pairing check.
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

/**
 * Stage 4 fail-closed gate (scoped: tool-dependent turns and thinking-mode
 * requests only): thinking/reasoning histories need opaque
 * passthrough, not yet supported — refusing stateless resend.
 *
 * A stateless rebuild cannot faithfully carry opaque reasoning payloads:
 * Responses `store=false` rebuilds only cover text / function_call /
 * function_call_output items (reasoning items have no text equivalent),
 * and Anthropic rebuilds only emit text + tool_use blocks (thinking /
 * signature blocks are dropped). Resending without them would silently
 * change the reasoning context, so every adapter calls this first in
 * `buildBody` — before any other validation.
 *
 * Scoped (Stage 1 ordinary-chat regression fix): plain-text follow-ups
 * must not fail. Production [dev.librepocket.chat.TurnController.buildRequest]
 * always sends `tools == []`, `serverTools == []`, `thinking == null`, and
 * `UiMessage` has no `toolCalls` field (`ToolDone` only merges a text
 * marker), so a pure-chat history never carries structural tool content.
 * The `hasReasoning` refusal therefore applies only when the turn is
 * tool-dependent:
 *
 * - `request.thinking != null` (explicit thinking mode) always throws
 *   `ProviderFailure(retryable=false, "THINKING_ROUND_TRIP_NOT_SUPPORTED")`
 *   (explicit-mode precheck, unconditional).
 * - otherwise, only when `request.tools` / `request.serverTools` is
 *   non-empty, or any message carries `toolCalls` / `role == "tool"`,
 *   AND some message carries `hasReasoning == true`.
 *
 * Non-thinking, no-tools histories (defaults) pass through with zero
 * behavior change.
 *
 * Trade-off: the no-tools pass-through accepts silent narrowing of opaque
 * reasoning context (visible merged text is sent, opaque payloads are
 * discarded) in exchange for keeping ordinary chat connected instead of
 * failing it closed.
 */
internal fun requireNoReasoningHistory(request: ChatRequest) {
    if (request.thinking != null) throw ProviderFailure(false, "THINKING_ROUND_TRIP_NOT_SUPPORTED")
    val toolDependent = request.tools.isNotEmpty() || request.serverTools.isNotEmpty() ||
        request.messages.any { it.toolCalls.isNotEmpty() || it.role == "tool" }
    if (toolDependent && request.messages.any { it.hasReasoning }) {
        throw ProviderFailure(false, "THINKING_ROUND_TRIP_NOT_SUPPORTED")
    }
}

/**
 * Finding B [P2] fail-closed gate: assistant messages must not carry images.
 *
 * Chat Completions emits `content[]` parts for any role with images, so an
 * assistant+images history would send
 * `{"role":"assistant","content":[{"type":"text"},{"type":"image_url"...}]}`
 * which OpenAI does not support; Responses (`input_image`) and Anthropic
 * (`image` source blocks) have the same gap for assistant-carried images.
 * Production never hits this: [dev.librepocket.chat.TurnController.buildRequest]
 * attaches images to the last user message only. The gate therefore changes
 * zero production behavior and only refuses explicit test/scaffold histories.
 *
 * Gate ordering (every adapter's `buildBody`):
 * 1. [requireNoReasoningHistory] unconditional `thinking != null` precheck first,
 * 2. [requireNoReasoningHistory] tool-dependent `hasReasoning` refusal second,
 * 3. this assistant-image gate third,
 * 4. [validateToolPairing] fourth.
 *
 * Only `role == "assistant"` with non-empty `images` is rejected
 * (`ProviderFailure(retryable=false, "ASSISTANT_IMAGES_NOT_SUPPORTED")`);
 * user images still flow to the multimodal paths; system images keep the
 * existing text-only convergence (silently dropped, unchanged here), and
 * tool-output images keep their own `TOOL_OUTPUT_IMAGES_NOT_SUPPORTED`
 * rejection inside [validateToolPairing].
 */
internal fun requireNoAssistantImages(messages: List<ChatMessage>) {
    if (messages.any { it.role == "assistant" && it.images.isNotEmpty() }) {
        throw ProviderFailure(false, "ASSISTANT_IMAGES_NOT_SUPPORTED")
    }
}

/** Non-blank arguments must be a JSON object; blank means `{}` (filled by callers). */
internal fun validateToolArgumentsObject(argumentsJson: String) {
    if (argumentsJson.isBlank()) return
    try {
        val el = Json.parseToJsonElement(argumentsJson)
        if (el !is JsonObject) throw ProviderFailure(false, "TOOL_ARGUMENTS_MUST_BE_OBJECT")
    } catch (e: ProviderFailure) {
        throw e
    } catch (e: SerializationException) {
        // SerializationException extends IllegalArgumentException in kotlinx-serialization 1.10.0;
        // catch first so a future upstream hierarchy change still maps correctly.
        throw ProviderFailure(false, "TOOL_ARGUMENTS_INVALID")
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
} catch (e: SerializationException) {
    // SerializationException extends IllegalArgumentException in kotlinx-serialization 1.10.0;
    // catch first so a future upstream hierarchy change still maps correctly.
    throw ProviderFailure(false, "TOOL_ARGUMENTS_INVALID")
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
