package dev.librepocket.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 2: role / call-id pairing + round-trip semantics (no production tools).
 *
 * - Responses: `message` / `function_call` / `function_call_output` split by
 *   `call_id` (tool is never rewritten to user); stateless `store=false`
 *   histories self-contain the full pairing.
 * - Anthropic: tool turns ride `tool_result` paired with `tool_use` ids.
 * - Chat: stored `tool_call_id` is verified; a missing id is filled only for
 *   the single-call case, otherwise fail-closed (no request is sent).
 * - Cross-adapter: one tool definition + call carries equal semantics on all
 *   three wires (mock call -> fake execution -> output -> final answer pairs).
 */
class ProviderToolRoundTripTest {

    private fun chatConfig() = ProviderConfig(
        id = "00000000-0000-0000-0000-00000000bb01",
        label = "test",
        baseUrl = "https://api.example.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        apiKeyRef = "provider_key/00000000-0000-0000-0000-00000000bb01",
    )

    private fun responsesConfig() = ProviderConfig(
        id = "00000000-0000-0000-0000-00000000bb02",
        label = "test",
        baseUrl = "https://api.example.com/v1",
        protocol = ProviderProtocol.RESPONSES,
        apiKeyRef = "provider_key/00000000-0000-0000-0000-00000000bb02",
    )

    private fun anthropicConfig() = ProviderConfig(
        id = "00000000-0000-0000-0000-00000000bb03",
        label = "test",
        baseUrl = "https://api.anthropic.com",
        protocol = ProviderProtocol.ANTHROPIC,
        apiKeyRef = "provider_key/00000000-0000-0000-0000-00000000bb03",
    )

    private fun strictObj(body: String) = Json.parseToJsonElement(body).jsonObject

    private val advName = "we\"ird\\tool"
    private val advText = "say \"hi\" \\ bye\nnewline 中文🎉"
    private val advArgs = """{"q":"v\"v\\中🎉","nested":{"list":[1,"x"]}}"""
    private val advOutput = "result \"quoted\" back\\slash\nnewline\t中文🎉 café"
    private val advCallId = "call_\"x\\1"

    private fun advTools() = listOf(
        ToolSchema("plain_tool", "plain desc", """{"type":"object"}"""),
    )

    // ---- Responses: call/output pairing ----

    @Test
    fun responsesCallOutputPairStrictPasses() {
        val tc = ToolCall(id = advCallId, name = advName, argumentsJson = advArgs)
        val body = ResponsesProvider(responsesConfig(), { null }).buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(
                    ChatMessage("user", advText),
                    ChatMessage("assistant", "thinking \"quoted\" 中文", toolCalls = listOf(tc)),
                    ChatMessage("tool", advOutput, toolCallId = tc.id),
                    ChatMessage("assistant", "final 🎉 done"),
                ),
                tools = advTools(),
            ),
        )
        val root = strictObj(body)
        assertTrue(body.contains("\"store\":false"))
        assertFalse(body.contains("previous_response_id"))
        val input = root["input"]!!.jsonArray.map { it.jsonObject }
        val types = input.map { it["type"]!!.jsonPrimitive.content }
        // user message, assistant message, function_call, function_call_output, final message.
        assertEquals(
            listOf("message", "message", "function_call", "function_call_output", "message"),
            types,
        )
        val call = input.first { it["type"]!!.jsonPrimitive.content == "function_call" }
        assertEquals(tc.id, call["call_id"]!!.jsonPrimitive.content)
        assertEquals(tc.name, call["name"]!!.jsonPrimitive.content)
        assertEquals(tc.argumentsJson, call["arguments"]!!.jsonPrimitive.content)
        val out = input.first { it["type"]!!.jsonPrimitive.content == "function_call_output" }
        assertEquals(tc.id, out["call_id"]!!.jsonPrimitive.content)
        assertEquals(advOutput, out["output"]!!.jsonPrimitive.content)
        // Tool output is never rewritten to a user message.
        val userMessages = input.filter {
            it["type"]!!.jsonPrimitive.content == "message" &&
                it["role"]!!.jsonPrimitive.content == "user"
        }
        assertEquals(1, userMessages.size)
        val userText = userMessages[0]["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals(advText, userText)
    }

    @Test
    fun responsesEmptyAssistantTextEmitsOnlyCall() {
        val tc = ToolCall(id = "call_abc", name = "get_weather", argumentsJson = """{"city":"Taipei"}""")
        val body = ResponsesProvider(responsesConfig(), { null }).buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(
                    ChatMessage("user", "hi"),
                    ChatMessage("assistant", "", toolCalls = listOf(tc)),
                    ChatMessage("tool", "sunny", toolCallId = tc.id),
                ),
                tools = advTools(),
            ),
        )
        val input = strictObj(body)["input"]!!.jsonArray.map { it.jsonObject }
        val types = input.map { it["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("message", "function_call", "function_call_output"), types)
    }

    @Test
    fun responsesStatelessHistorySelfContainsPairing() {
        val tc = ToolCall(id = "call_wx8fQ2zA", name = "get_weather", argumentsJson = """{"city":"Taipei"}""")
        val body = ResponsesProvider(responsesConfig(), { null }).buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(
                    ChatMessage("user", "weather?"),
                    ChatMessage("assistant", "", toolCalls = listOf(tc)),
                    ChatMessage("tool", """{"temp":25}""", toolCallId = tc.id),
                    ChatMessage("user", "and tomorrow?"),
                ),
                tools = advTools(),
            ),
        )
        assertTrue(body.contains("\"store\":false"))
        assertFalse(body.contains("previous_response_id"))
        val input = strictObj(body)["input"]!!.jsonArray.map { it.jsonObject }
        assertTrue(input.any { it["type"]!!.jsonPrimitive.content == "function_call" })
        assertTrue(input.any { it["type"]!!.jsonPrimitive.content == "function_call_output" })
        val callIds = input.filter { it["type"]!!.jsonPrimitive.content == "function_call" }
            .map { it["call_id"]!!.jsonPrimitive.content }.toSet()
        val outIds = input.filter { it["type"]!!.jsonPrimitive.content == "function_call_output" }
            .map { it["call_id"]!!.jsonPrimitive.content }.toSet()
        assertEquals(callIds, outIds)
    }

    @Test
    fun responsesOrphanToolOutputFailsClosed() {
        try {
            ResponsesProvider(responsesConfig(), { null }).buildBody(
                ChatRequest(
                    model = "m",
                    messages = listOf(
                        ChatMessage("user", "hi"),
                        ChatMessage("assistant", "", toolCalls = listOf(ToolCall("call_a", "t", "{}"))),
                        ChatMessage("tool", "oops", toolCallId = "call_b"),
                    ),
                    tools = advTools(),
                ),
            )
            fail("expected orphan rejection")
        } catch (e: ProviderFailure) {
            assertFalse(e.retryable)
        }
    }

    @Test
    fun responsesBlankToolOutputIdFailsClosed() {
        try {
            ResponsesProvider(responsesConfig(), { null }).buildBody(
                ChatRequest(
                    model = "m",
                    messages = listOf(
                        ChatMessage("user", "hi"),
                        ChatMessage("assistant", "", toolCalls = listOf(ToolCall("call_a", "t", "{}"))),
                        ChatMessage("tool", "oops", toolCallId = null),
                    ),
                    tools = advTools(),
                ),
            )
            fail("expected missing-id rejection")
        } catch (e: ProviderFailure) {
            assertFalse(e.retryable)
        }
    }

    @Test
    fun responsesDuplicateCallIdsFailClosed() {
        try {
            ResponsesProvider(responsesConfig(), { null }).buildBody(
                ChatRequest(
                    model = "m",
                    messages = listOf(
                        ChatMessage("assistant", "", toolCalls = listOf(ToolCall("call_a", "t1", "{}"), ToolCall("call_a", "t2", "{}"))),
                    ),
                    tools = advTools(),
                ),
            )
            fail("expected duplicate rejection")
        } catch (e: ProviderFailure) {
            assertFalse(e.retryable)
        }
    }

    @Test
    fun responsesMalformedArgumentsFailClosed() {
        try {
            ResponsesProvider(responsesConfig(), { null }).buildBody(
                ChatRequest(
                    model = "m",
                    messages = listOf(
                        ChatMessage("assistant", "", toolCalls = listOf(ToolCall("call_a", "t", "{not json"))),
                    ),
                    tools = advTools(),
                ),
            )
            fail("expected malformed-args rejection")
        } catch (e: ProviderFailure) {
            assertFalse(e.retryable)
        }
    }

    // ---- Anthropic: tool_result pairing ----

    @Test
    fun anthropicCallResultPairStrictPasses() {
        val tc = ToolCall(id = "toolu_\"x\\1", name = advName, argumentsJson = advArgs)
        val body = AnthropicProvider(anthropicConfig(), { null }).buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(
                    ChatMessage("user", advText),
                    ChatMessage("assistant", "thinking 中文", toolCalls = listOf(tc)),
                    ChatMessage("tool", advOutput, toolCallId = tc.id),
                ),
                tools = advTools(),
            ),
        )
        val root = strictObj(body)
        val messages = root["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, messages.size)
        val assistant = messages[1]
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        val blocks = assistant["content"]!!.jsonArray.map { it.jsonObject }
        val toolUse = blocks.first { it["type"]!!.jsonPrimitive.content == "tool_use" }
        assertEquals(tc.id, toolUse["id"]!!.jsonPrimitive.content)
        assertEquals(tc.name, toolUse["name"]!!.jsonPrimitive.content)
        assertEquals(
            Json.parseToJsonElement(tc.argumentsJson),
            toolUse["input"]!!,
        )
        val resultMsg = messages[2]
        assertEquals("user", resultMsg["role"]!!.jsonPrimitive.content)
        val resultBlocks = resultMsg["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, resultBlocks.size)
        assertEquals("tool_result", resultBlocks[0]["type"]!!.jsonPrimitive.content)
        assertEquals(tc.id, resultBlocks[0]["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals(advOutput, resultBlocks[0]["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun anthropicOrphanToolResultFailsClosed() {
        try {
            AnthropicProvider(anthropicConfig(), { null }).buildBody(
                ChatRequest(
                    model = "m",
                    messages = listOf(
                        ChatMessage("user", "hi"),
                        ChatMessage("assistant", "", toolCalls = listOf(ToolCall("toolu_a", "t", "{}"))),
                        ChatMessage("tool", "oops", toolCallId = "toolu_b"),
                    ),
                    tools = advTools(),
                ),
            )
            fail("expected orphan rejection")
        } catch (e: ProviderFailure) {
            assertFalse(e.retryable)
        }
    }

    @Test
    fun anthropicBlankToolResultIdFailsClosed() {
        try {
            AnthropicProvider(anthropicConfig(), { null }).buildBody(
                ChatRequest(
                    model = "m",
                    messages = listOf(
                        ChatMessage("user", "hi"),
                        ChatMessage("assistant", "", toolCalls = listOf(ToolCall("toolu_a", "t", "{}"))),
                        ChatMessage("tool", "oops", toolCallId = ""),
                    ),
                    tools = advTools(),
                ),
            )
            fail("expected missing-id rejection")
        } catch (e: ProviderFailure) {
            assertFalse(e.retryable)
        }
    }

    // ---- Chat: verify-or-fill ----

    @Test
    fun chatCallOutputPairStrictPasses() {
        val tc = ToolCall(id = advCallId, name = advName, argumentsJson = advArgs)
        val body = ChatCompletionsProvider(chatConfig(), { null }).buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(
                    ChatMessage("user", advText),
                    ChatMessage("assistant", "", toolCalls = listOf(tc)),
                    ChatMessage("tool", advOutput, toolCallId = tc.id),
                ),
                tools = advTools(),
            ),
        )
        val messages = strictObj(body)["messages"]!!.jsonArray.map { it.jsonObject }
        val assistant = messages.first { it["role"]!!.jsonPrimitive.content == "assistant" }
        val calls = assistant["tool_calls"]!!.jsonArray
        assertEquals(1, calls.size)
        assertEquals(tc.id, calls[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(tc.argumentsJson, calls[0].jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
        assertEquals(advName, calls[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val toolMsg = messages.first { it["role"]!!.jsonPrimitive.content == "tool" }
        assertEquals(tc.id, toolMsg["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals(advOutput, toolMsg["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun chatBlankToolIdSingleCallFills() {
        val tc = ToolCall(id = "call_single", name = "t", argumentsJson = "{}")
        val body = ChatCompletionsProvider(chatConfig(), { null }).buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(
                    ChatMessage("user", "hi"),
                    ChatMessage("assistant", "", toolCalls = listOf(tc)),
                    ChatMessage("tool", "out", toolCallId = null),
                ),
                tools = advTools(),
            ),
        )
        val toolMsg = strictObj(body)["messages"]!!.jsonArray.map { it.jsonObject }
            .first { it["role"]!!.jsonPrimitive.content == "tool" }
        assertEquals("call_single", toolMsg["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun chatBlankToolIdMultiCallFailsClosed() {
        try {
            ChatCompletionsProvider(chatConfig(), { null }).buildBody(
                ChatRequest(
                    model = "m",
                    messages = listOf(
                        ChatMessage("user", "hi"),
                        ChatMessage(
                            "assistant",
                            "",
                            toolCalls = listOf(ToolCall("call_a", "t", "{}"), ToolCall("call_b", "t", "{}")),
                        ),
                        ChatMessage("tool", "out", toolCallId = null),
                    ),
                    tools = advTools(),
                ),
            )
            fail("expected ambiguous rejection")
        } catch (e: ProviderFailure) {
            assertFalse(e.retryable)
        }
    }

    @Test
    fun chatOrphanToolOutputFailsClosed() {
        try {
            ChatCompletionsProvider(chatConfig(), { null }).buildBody(
                ChatRequest(
                    model = "m",
                    messages = listOf(
                        ChatMessage("user", "hi"),
                        ChatMessage("assistant", "", toolCalls = listOf(ToolCall("call_a", "t", "{}"))),
                        ChatMessage("tool", "oops", toolCallId = "call_b"),
                    ),
                    tools = advTools(),
                ),
            )
            fail("expected orphan rejection")
        } catch (e: ProviderFailure) {
            assertFalse(e.retryable)
        }
    }

    // ---- Cross-adapter semantic equality + mock round-trip ----

    @Test
    fun crossAdapterToolSemanticsEqual() {
        val tc = ToolCall(id = "call_equal1", name = "get_weather", argumentsJson = advArgs)
        val output = advOutput
        val history = listOf(
            ChatMessage("user", advText),
            ChatMessage("assistant", "assistant text 中文", toolCalls = listOf(tc)),
            ChatMessage("tool", output, toolCallId = tc.id),
        )
        // Chat
        val chatBody = ChatCompletionsProvider(chatConfig(), { null }).buildBody(
            ChatRequest(model = "m", messages = history, tools = advTools()),
        )
        val chatMsgs = strictObj(chatBody)["messages"]!!.jsonArray.map { it.jsonObject }
        val chatCall = chatMsgs.first { it["role"]!!.jsonPrimitive.content == "assistant" }["tool_calls"]!!.jsonArray[0].jsonObject
        val chatCallId = chatCall["id"]!!.jsonPrimitive.content
        val chatArgs = Json.parseToJsonElement(chatCall["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
        // Responses
        val respBody = ResponsesProvider(responsesConfig(), { null }).buildBody(
            ChatRequest(model = "m", messages = history, tools = advTools()),
        )
        val respInput = strictObj(respBody)["input"]!!.jsonArray.map { it.jsonObject }
        val respCall = respInput.first { it["type"]!!.jsonPrimitive.content == "function_call" }
        val respOut = respInput.first { it["type"]!!.jsonPrimitive.content == "function_call_output" }
        // Anthropic
        val anthBody = AnthropicProvider(anthropicConfig(), { null }).buildBody(
            ChatRequest(model = "m", messages = history, tools = advTools()),
        )
        val anthMsgs = strictObj(anthBody)["messages"]!!.jsonArray.map { it.jsonObject }
        val anthUse = anthMsgs.first { it["role"]!!.jsonPrimitive.content == "assistant" }["content"]!!.jsonArray
            .map { it.jsonObject }.first { it["type"]!!.jsonPrimitive.content == "tool_use" }
        val anthResult = anthMsgs.first {
            it["role"]!!.jsonPrimitive.content == "user" &&
                it["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content == "tool_result"
        }["content"]!!.jsonArray[0].jsonObject
        // IDs pair across all three wires.
        assertEquals(tc.id, chatCallId)
        assertEquals(tc.id, respCall["call_id"]!!.jsonPrimitive.content)
        assertEquals(tc.id, respOut["call_id"]!!.jsonPrimitive.content)
        assertEquals(tc.id, anthUse["id"]!!.jsonPrimitive.content)
        assertEquals(tc.id, anthResult["tool_use_id"]!!.jsonPrimitive.content)
        // Names equal.
        assertEquals(tc.name, chatCall["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(tc.name, respCall["name"]!!.jsonPrimitive.content)
        assertEquals(tc.name, anthUse["name"]!!.jsonPrimitive.content)
        // Arguments semantically equal (parsed JSON).
        val expectedArgs = Json.parseToJsonElement(tc.argumentsJson)
        assertEquals(expectedArgs, chatArgs)
        assertEquals(expectedArgs, Json.parseToJsonElement(respCall["arguments"]!!.jsonPrimitive.content))
        assertEquals(expectedArgs, anthUse["input"]!!)
        // Outputs equal.
        val chatOut = chatMsgs.first { it["role"]!!.jsonPrimitive.content == "tool" }["content"]!!.jsonPrimitive.content
        assertEquals(output, chatOut)
        assertEquals(output, respOut["output"]!!.jsonPrimitive.content)
        assertEquals(output, anthResult["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun mockRoundTripPairsAcrossAdapters() {
        // Turn 1: user asks; model would return a call (simulated here); fake
        // execution produces an output; Turn 2 resends the full pairing plus a
        // follow-up. Each adapter must strict-parse and keep ids paired.
        val tc = ToolCall(id = "call_mock1", name = "get_weather", argumentsJson = """{"city":"Taipei 台北"}""")
        val fakeOutput = """{"temp":25,"note":"晴 \"quoted\" 中文"}"""
        val round2 = listOf(
            ChatMessage("user", advText),
            ChatMessage("assistant", "", toolCalls = listOf(tc)),
            ChatMessage("tool", fakeOutput, toolCallId = tc.id),
            ChatMessage("user", "thanks 🎉"),
        )
        val chatBody = ChatCompletionsProvider(chatConfig(), { null }).buildBody(
            ChatRequest(model = "m", messages = round2, tools = advTools()),
        )
        val chatMsgs = strictObj(chatBody)["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            tc.id,
            chatMsgs.first { it["role"]!!.jsonPrimitive.content == "tool" }["tool_call_id"]!!.jsonPrimitive.content,
        )
        val respBody = ResponsesProvider(responsesConfig(), { null }).buildBody(
            ChatRequest(model = "m", messages = round2, tools = advTools()),
        )
        val respInput = strictObj(respBody)["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            tc.id,
            respInput.first { it["type"]!!.jsonPrimitive.content == "function_call_output" }["call_id"]!!.jsonPrimitive.content,
        )
        val anthBody = AnthropicProvider(anthropicConfig(), { null }).buildBody(
            ChatRequest(model = "m", messages = round2, tools = advTools()),
        )
        val anthMsgs = strictObj(anthBody)["messages"]!!.jsonArray.map { it.jsonObject }
        val resultBlock = anthMsgs.map { it["content"]!!.jsonArray.map { b -> b.jsonObject } }.flatten()
            .first { it["type"]!!.jsonPrimitive.content == "tool_result" }
        assertEquals(tc.id, resultBlock["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals(fakeOutput, resultBlock["content"]!!.jsonPrimitive.content)
    }
}
