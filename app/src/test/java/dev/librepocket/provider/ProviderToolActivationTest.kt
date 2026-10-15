package dev.librepocket.provider

import dev.librepocket.tool.Flavor
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage1 #5 remaining activation contracts (scaffold-only, no dispatcher).
 *
 * Production still sends no tools (`TurnController.buildRequest` stays
 * pure-chat, `ToolRegistry.visibleTools` is empty for all 41 tools); these
 * tests only validate explicit scaffold histories via `buildBody`.
 *
 * Covers:
 * - mock function_call -> fake executor -> function_call_output -> final
 *   answer call_id pairing (Anthropic with non-empty tools merges results);
 * - empty / single / multi / +hosted web_search strict JSON parse;
 * - nested / escaped / Unicode round-trip;
 * - multi-tool same round + failure result ID / role preservation;
 * - Chat / Anthropic no-regression (pure-chat + single-tool).
 */
class ProviderToolActivationTest {

    private fun chatConfig() = ProviderConfig(
        id = "00000000-0000-0000-0000-00000000cc01",
        label = "test",
        baseUrl = "https://api.example.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        apiKeyRef = "provider_key/00000000-0000-0000-0000-00000000cc01",
    )

    private fun responsesConfig() = ProviderConfig(
        id = "00000000-0000-0000-0000-00000000cc02",
        label = "test",
        baseUrl = "https://api.example.com/v1",
        protocol = ProviderProtocol.RESPONSES,
        apiKeyRef = "provider_key/00000000-0000-0000-0000-00000000cc02",
    )

    private fun anthropicConfig() = ProviderConfig(
        id = "00000000-0000-0000-0000-00000000cc03",
        label = "test",
        baseUrl = "https://api.anthropic.com",
        protocol = ProviderProtocol.ANTHROPIC,
        apiKeyRef = "provider_key/00000000-0000-0000-0000-00000000cc03",
    )

    private fun strictObj(body: String) = Json.parseToJsonElement(body).jsonObject

    private val advName = "we\"ird\\tool_激活🎉"
    private val advDesc = "desc \"quoted\" back\\slash newline\n tab\t 中文🎉 café"
    private val advText = "query \"hi\" \\ bye\nnewline 中文🎉 café"
    private val advNestedArgs =
        """{"q":"v\"v\\中🎉","nested":{"list":[1,"x",{"k":"a\nb\tc"}],"empty":{},"num":42},"uni":"café 中文🎉"}"""
    private val advOutput = "result \"quoted\" back\\slash\nnewline\t中文🎉 café"
    private val failureOutput = "TOOL_FAILED: timeout after 10s \"quoted\" 中文🎉"
    private val finalAnswer = "done 🎉 最終回答 \"quoted\""

    private fun localTools() = listOf(
        ToolSchema("plain_tool", "plain desc", """{"type":"object"}"""),
        ToolSchema(advName, advDesc, """{"type":"object","properties":{"q":{"type":"string"}}}"""),
        ToolSchema("third_tool", "第三個工具 🎉", """{"type":"object","properties":{}}"""),
    )

    private fun singleTool() = listOf(
        ToolSchema("get_weather", "desc", """{"type":"object"}"""),
    )

    /** Fake executor: deterministic stand-in for the (unwired) dispatcher. */
    private fun fakeExecute(tc: ToolCall): String = when (tc.name) {
        "fail_tool" -> failureOutput
        else -> """{"temp":25,"note":"晴 \"quoted\" 中文 for ${tc.id}"}"""
    }

    // ---- mock round-trip: call -> fake exec -> output -> final answer ----

    @Test
    fun responsesMockRoundTripKeepsCallIdToFinalAnswer() {
        val tc = ToolCall(id = "call_mock_resp1", name = "get_weather", argumentsJson = advNestedArgs)
        val output = fakeExecute(tc)
        val history = listOf(
            ChatMessage("user", advText),
            ChatMessage("assistant", "", toolCalls = listOf(tc)),
            ChatMessage("tool", output, toolCallId = tc.id),
            ChatMessage("assistant", finalAnswer),
        )
        val body = ResponsesProvider(responsesConfig(), { null }).buildBody(
            ChatRequest(model = "m", messages = history, tools = singleTool()),
        )
        val input = strictObj(body)["input"]!!.jsonArray.map { it.jsonObject }
        val types = input.map { it["type"]!!.jsonPrimitive.content }
        assertEquals(
            listOf("message", "function_call", "function_call_output", "message"),
            types,
        )
        val call = input.first { it["type"]!!.jsonPrimitive.content == "function_call" }
        val out = input.first { it["type"]!!.jsonPrimitive.content == "function_call_output" }
        assertEquals(tc.id, call["call_id"]!!.jsonPrimitive.content)
        assertEquals(tc.id, out["call_id"]!!.jsonPrimitive.content)
        assertEquals(output, out["output"]!!.jsonPrimitive.content)
        val final = input.last { it["type"]!!.jsonPrimitive.content == "message" }
        assertEquals("assistant", final["role"]!!.jsonPrimitive.content)
        val finalText = final["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals(finalAnswer, finalText)
    }

    @Test
    fun chatMockRoundTripKeepsCallIdToFinalAnswer() {
        val tc = ToolCall(id = "call_mock_cc1", name = "get_weather", argumentsJson = advNestedArgs)
        val output = fakeExecute(tc)
        val history = listOf(
            ChatMessage("user", advText),
            ChatMessage("assistant", "", toolCalls = listOf(tc)),
            ChatMessage("tool", output, toolCallId = tc.id),
            ChatMessage("assistant", finalAnswer),
        )
        val body = ChatCompletionsProvider(chatConfig(), { null }).buildBody(
            ChatRequest(model = "m", messages = history, tools = singleTool()),
        )
        val messages = strictObj(body)["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("user", "assistant", "tool", "assistant"),
            messages.map { it["role"]!!.jsonPrimitive.content },
        )
        val callId = messages[1]["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(tc.id, callId)
        assertEquals(tc.id, messages[2]["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals(output, messages[2]["content"]!!.jsonPrimitive.content)
        assertEquals(finalAnswer, messages[3]["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun anthropicMockRoundTripWithNonEmptyToolsMergesAndKeepsFinal() {
        val tc = ToolCall(id = "toolu_mock_anth1", name = "get_weather", argumentsJson = advNestedArgs)
        val output = fakeExecute(tc)
        val history = listOf(
            ChatMessage("user", advText),
            ChatMessage("assistant", "", toolCalls = listOf(tc)),
            ChatMessage("tool", output, toolCallId = tc.id),
            ChatMessage("assistant", finalAnswer),
        )
        val tools = localTools()
        val body = AnthropicProvider(anthropicConfig(), { null }).buildBody(
            ChatRequest(model = "m", messages = history, tools = tools),
        )
        val root = strictObj(body)
        // Non-empty tools still emitted alongside the merged tool_result turn.
        assertEquals(tools.size, root["tools"]!!.jsonArray.size)
        val messages = root["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(4, messages.size)
        assertEquals(
            listOf("user", "assistant", "user", "assistant"),
            messages.map { it["role"]!!.jsonPrimitive.content },
        )
        val use = messages[1]["content"]!!.jsonArray.map { it.jsonObject }
            .first { it["type"]!!.jsonPrimitive.content == "tool_use" }
        assertEquals(tc.id, use["id"]!!.jsonPrimitive.content)
        val result = messages[2]["content"]!!.jsonArray.map { it.jsonObject }
            .first { it["type"]!!.jsonPrimitive.content == "tool_result" }
        assertEquals(tc.id, result["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals(output, result["content"]!!.jsonPrimitive.content)
        val finalBlocks = messages[3]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(finalAnswer, finalBlocks[0]["text"]!!.jsonPrimitive.content)
    }

    // ---- empty / single / multi / +hosted strict parse ----

    @Test
    fun emptyToolsStrictParseAllAdapters() {
        val messages = listOf(ChatMessage("user", "hi"))
        val resp = strictObj(
            ResponsesProvider(responsesConfig(), { null }).buildBody(
                ChatRequest(model = "m", messages = messages),
            ),
        )
        assertFalse(resp.containsKey("tools"))
        val chat = strictObj(
            ChatCompletionsProvider(chatConfig(), { null }).buildBody(
                ChatRequest(model = "m", messages = messages),
            ),
        )
        assertFalse(chat.containsKey("tools"))
        assertFalse(chat.containsKey("web_search_options"))
        val anth = strictObj(
            AnthropicProvider(anthropicConfig(), { null }).buildBody(
                ChatRequest(model = "m", messages = messages),
            ),
        )
        assertFalse(anth.containsKey("tools"))
    }

    @Test
    fun singleToolStrictParseAllAdapters() {
        val messages = listOf(ChatMessage("user", "hi"))
        val respTools = strictObj(
            ResponsesProvider(responsesConfig(), { null }).buildBody(
                ChatRequest(model = "m", messages = messages, tools = singleTool()),
            ),
        )["tools"]!!.jsonArray
        assertEquals(1, respTools.size)
        val chatTools = strictObj(
            ChatCompletionsProvider(chatConfig(), { null }).buildBody(
                ChatRequest(model = "m", messages = messages, tools = singleTool()),
            ),
        )["tools"]!!.jsonArray
        assertEquals(1, chatTools.size)
        val anthTools = strictObj(
            AnthropicProvider(anthropicConfig(), { null }).buildBody(
                ChatRequest(model = "m", messages = messages, tools = singleTool()),
            ),
        )["tools"]!!.jsonArray
        assertEquals(1, anthTools.size)
    }

    @Test
    fun multiToolStrictParseAllAdapters() {
        val tools = localTools()
        val messages = listOf(ChatMessage("user", advText))
        val respTools = strictObj(
            ResponsesProvider(responsesConfig(), { null }).buildBody(
                ChatRequest(model = "m", messages = messages, tools = tools),
            ),
        )["tools"]!!.jsonArray
        assertEquals(tools.size, respTools.size)
        val chatTools = strictObj(
            ChatCompletionsProvider(chatConfig(), { null }).buildBody(
                ChatRequest(model = "m", messages = messages, tools = tools),
            ),
        )["tools"]!!.jsonArray
        assertEquals(tools.size, chatTools.size)
        val anthTools = strictObj(
            AnthropicProvider(anthropicConfig(), { null }).buildBody(
                ChatRequest(model = "m", messages = messages, tools = tools),
            ),
        )["tools"]!!.jsonArray
        assertEquals(tools.size, anthTools.size)
    }

    @Test
    fun hostedWebSearchStrictParse() {
        val tools = localTools()
        val messages = listOf(ChatMessage("user", advText))
        // Responses: local tools + hosted web_search in one tools[] array.
        val respTools = strictObj(
            ResponsesProvider(responsesConfig(), { null }).buildBody(
                ChatRequest(
                    model = "m",
                    messages = messages,
                    tools = tools,
                    serverTools = listOf(ServerTool.WebSearch(WebSearchContextSize.HIGH)),
                ),
            ),
        )["tools"]!!.jsonArray
        assertEquals(tools.size + 1, respTools.size)
        assertEquals(
            "web_search",
            respTools.last().jsonObject["type"]!!.jsonPrimitive.content,
        )
        // Chat: local tools[] plus separate web_search_options.
        val chatRoot = strictObj(
            ChatCompletionsProvider(chatConfig(), { null }).buildBody(
                ChatRequest(
                    model = "m",
                    messages = messages,
                    tools = tools,
                    serverTools = listOf(ServerTool.WebSearch(WebSearchContextSize.LOW)),
                ),
            ),
        )
        assertEquals(tools.size, chatRoot["tools"]!!.jsonArray.size)
        assertEquals(
            "low",
            chatRoot["web_search_options"]!!.jsonObject["search_context_size"]!!.jsonPrimitive.content,
        )
        // Anthropic has no hosted web_search mapping: serverTools are ignored
        // but the body must still strict-parse with local tools intact.
        val anthRoot = strictObj(
            AnthropicProvider(anthropicConfig(), { null }).buildBody(
                ChatRequest(
                    model = "m",
                    messages = messages,
                    tools = tools,
                    serverTools = listOf(ServerTool.WebSearch(WebSearchContextSize.MEDIUM)),
                ),
            ),
        )
        assertEquals(tools.size, anthRoot["tools"]!!.jsonArray.size)
        assertFalse(anthRoot.containsKey("web_search_options"))
    }

    // ---- nested / escaped / Unicode round-trip ----

    @Test
    fun nestedEscapedUnicodeArgsRoundTripAllAdapters() {
        val tc = ToolCall(id = "call_nested1", name = advName, argumentsJson = advNestedArgs)
        val expected = Json.parseToJsonElement(advNestedArgs)
        val history = listOf(
            ChatMessage("user", advText),
            ChatMessage("assistant", "thinking 中文", toolCalls = listOf(tc)),
            ChatMessage("tool", advOutput, toolCallId = tc.id),
        )
        val chatCall = strictObj(
            ChatCompletionsProvider(chatConfig(), { null }).buildBody(
                ChatRequest(model = "m", messages = history, tools = localTools()),
            ),
        )["messages"]!!.jsonArray.map { it.jsonObject }
            .first { it["role"]!!.jsonPrimitive.content == "assistant" }["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals(expected, Json.parseToJsonElement(chatCall["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content))
        val respCall = strictObj(
            ResponsesProvider(responsesConfig(), { null }).buildBody(
                ChatRequest(model = "m", messages = history, tools = localTools()),
            ),
        )["input"]!!.jsonArray.map { it.jsonObject }
            .first { it["type"]!!.jsonPrimitive.content == "function_call" }
        assertEquals(expected, Json.parseToJsonElement(respCall["arguments"]!!.jsonPrimitive.content))
        assertEquals(advOutput, strictObj(
            ResponsesProvider(responsesConfig(), { null }).buildBody(
                ChatRequest(model = "m", messages = history, tools = localTools()),
            ),
        )["input"]!!.jsonArray.map { it.jsonObject }
            .first { it["type"]!!.jsonPrimitive.content == "function_call_output" }["output"]!!.jsonPrimitive.content)
        val anthUse = strictObj(
            AnthropicProvider(anthropicConfig(), { null }).buildBody(
                ChatRequest(model = "m", messages = history, tools = localTools()),
            ),
        )["messages"]!!.jsonArray.map { it.jsonObject }
            .first { it["role"]!!.jsonPrimitive.content == "assistant" }["content"]!!.jsonArray
            .map { it.jsonObject }.first { it["type"]!!.jsonPrimitive.content == "tool_use" }
        assertEquals(expected, anthUse["input"]!!)
    }

    @Test
    fun adversarialNamesDescriptionsRoundTrip() {
        val tools = localTools()
        val messages = listOf(ChatMessage("user", advText))
        val respTools = strictObj(
            ResponsesProvider(responsesConfig(), { null }).buildBody(
                ChatRequest(model = "m", messages = messages, tools = tools),
            ),
        )["tools"]!!.jsonArray.map { it.jsonObject }
        for (i in tools.indices) {
            assertEquals(tools[i].name, respTools[i]["name"]!!.jsonPrimitive.content)
            assertEquals(tools[i].description, respTools[i]["description"]!!.jsonPrimitive.content)
        }
        val chatTools = strictObj(
            ChatCompletionsProvider(chatConfig(), { null }).buildBody(
                ChatRequest(model = "m", messages = messages, tools = tools),
            ),
        )["tools"]!!.jsonArray.map { it.jsonObject }
        for (i in tools.indices) {
            val fn = chatTools[i]["function"]!!.jsonObject
            assertEquals(tools[i].name, fn["name"]!!.jsonPrimitive.content)
            assertEquals(tools[i].description, fn["description"]!!.jsonPrimitive.content)
        }
        val anthTools = strictObj(
            AnthropicProvider(anthropicConfig(), { null }).buildBody(
                ChatRequest(model = "m", messages = messages, tools = tools),
            ),
        )["tools"]!!.jsonArray.map { it.jsonObject }
        for (i in tools.indices) {
            assertEquals(tools[i].name, anthTools[i]["name"]!!.jsonPrimitive.content)
            assertEquals(tools[i].description, anthTools[i]["description"]!!.jsonPrimitive.content)
        }
    }

    // ---- multi-tool same round + failure preserves IDs / roles ----

    @Test
    fun responsesMultiToolWithFailurePreservesIdsAndRoles() {
        val tc1 = ToolCall(id = "call_multi_ok", name = "get_weather", argumentsJson = """{"city":"Taipei 台北"}""")
        val tc2 = ToolCall(id = "call_multi_fail", name = "fail_tool", argumentsJson = """{"city":"Taichung \"quoted\""}""")
        val out1 = fakeExecute(tc1)
        val out2 = fakeExecute(tc2)
        assertEquals(failureOutput, out2)
        val history = listOf(
            ChatMessage("user", advText),
            ChatMessage("assistant", "", toolCalls = listOf(tc1, tc2)),
            ChatMessage("tool", out1, toolCallId = tc1.id),
            ChatMessage("tool", out2, toolCallId = tc2.id),
            ChatMessage("assistant", finalAnswer),
        )
        val body = ResponsesProvider(responsesConfig(), { null }).buildBody(
            ChatRequest(model = "m", messages = history, tools = singleTool()),
        )
        val input = strictObj(body)["input"]!!.jsonArray.map { it.jsonObject }
        val calls = input.filter { it["type"]!!.jsonPrimitive.content == "function_call" }
        val outputs = input.filter { it["type"]!!.jsonPrimitive.content == "function_call_output" }
        assertEquals(listOf(tc1.id, tc2.id), calls.map { it["call_id"]!!.jsonPrimitive.content })
        assertEquals(listOf(tc1.id, tc2.id), outputs.map { it["call_id"]!!.jsonPrimitive.content })
        assertEquals(out1, outputs[0]["output"]!!.jsonPrimitive.content)
        // Failure result keeps the same role/type and id, only content differs.
        assertEquals("function_call_output", outputs[1]["type"]!!.jsonPrimitive.content)
        assertEquals(failureOutput, outputs[1]["output"]!!.jsonPrimitive.content)
        val finals = input.filter { it["type"]!!.jsonPrimitive.content == "message" && it["role"]!!.jsonPrimitive.content == "assistant" }
        assertTrue(finals.any {
            it["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content == finalAnswer
        })
    }

    @Test
    fun chatMultiToolWithFailurePreservesIdsAndRoles() {
        val tc1 = ToolCall(id = "call_cc_ok", name = "get_weather", argumentsJson = """{"city":"Taipei"}""")
        val tc2 = ToolCall(id = "call_cc_fail", name = "fail_tool", argumentsJson = """{"city":"Taichung"}""")
        val out1 = fakeExecute(tc1)
        val out2 = fakeExecute(tc2)
        val history = listOf(
            ChatMessage("user", advText),
            ChatMessage("assistant", "", toolCalls = listOf(tc1, tc2)),
            ChatMessage("tool", out1, toolCallId = tc1.id),
            ChatMessage("tool", out2, toolCallId = tc2.id),
            ChatMessage("assistant", finalAnswer),
        )
        val body = ChatCompletionsProvider(chatConfig(), { null }).buildBody(
            ChatRequest(model = "m", messages = history, tools = singleTool()),
        )
        val messages = strictObj(body)["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("user", "assistant", "tool", "tool", "assistant"),
            messages.map { it["role"]!!.jsonPrimitive.content },
        )
        val ids = messages[1]["tool_calls"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf(tc1.id, tc2.id), ids)
        assertEquals(tc1.id, messages[2]["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals(tc2.id, messages[3]["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals(out1, messages[2]["content"]!!.jsonPrimitive.content)
        assertEquals(failureOutput, messages[3]["content"]!!.jsonPrimitive.content)
        assertEquals(finalAnswer, messages[4]["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun anthropicMultiToolWithFailureMergesAndPreservesIds() {
        val tc1 = ToolCall(id = "toolu_multi_ok", name = "get_weather", argumentsJson = """{"city":"Taipei 台北"}""")
        val tc2 = ToolCall(id = "toolu_multi_fail", name = "fail_tool", argumentsJson = """{"city":"Taichung"}""")
        val out1 = fakeExecute(tc1)
        val out2 = fakeExecute(tc2)
        val history = listOf(
            ChatMessage("user", advText),
            ChatMessage("assistant", "", toolCalls = listOf(tc1, tc2)),
            ChatMessage("tool", out1, toolCallId = tc1.id),
            ChatMessage("tool", out2, toolCallId = tc2.id),
            ChatMessage("assistant", finalAnswer),
        )
        val tools = singleTool()
        val body = AnthropicProvider(anthropicConfig(), { null }).buildBody(
            ChatRequest(model = "m", messages = history, tools = tools),
        )
        val messages = strictObj(body)["messages"]!!.jsonArray.map { it.jsonObject }
        // Two consecutive tool outputs merge into one user message; final stays assistant.
        assertEquals(
            listOf("user", "assistant", "user", "assistant"),
            messages.map { it["role"]!!.jsonPrimitive.content },
        )
        val uses = messages[1]["content"]!!.jsonArray.map { it.jsonObject }
            .filter { it["type"]!!.jsonPrimitive.content == "tool_use" }
        assertEquals(listOf(tc1.id, tc2.id), uses.map { it["id"]!!.jsonPrimitive.content })
        val results = messages[2]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, results.size)
        assertTrue(results.all { it["type"]!!.jsonPrimitive.content == "tool_result" })
        assertEquals(listOf(tc1.id, tc2.id), results.map { it["tool_use_id"]!!.jsonPrimitive.content })
        assertEquals(out1, results[0]["content"]!!.jsonPrimitive.content)
        assertEquals(failureOutput, results[1]["content"]!!.jsonPrimitive.content)
        assertEquals(
            finalAnswer,
            messages[3]["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
    }

    // ---- Chat / Anthropic no-regression ----

    @Test
    fun chatPureChatNoRegression() {
        val history = listOf(
            ChatMessage("user", "hi 中文"),
            ChatMessage("assistant", "hello 🎉"),
            ChatMessage("user", "thanks"),
        )
        val body = ChatCompletionsProvider(chatConfig(), { null }).buildBody(
            ChatRequest(model = "m", messages = history),
        )
        val messages = strictObj(body)["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, messages.size)
        assertEquals(
            listOf("user", "assistant", "user"),
            messages.map { it["role"]!!.jsonPrimitive.content },
        )
        assertTrue(messages.none { it.containsKey("tool_calls") })
        assertTrue(messages.none { it["role"]!!.jsonPrimitive.content == "tool" })
    }

    @Test
    fun anthropicPureChatNoRegression() {
        val history = listOf(
            ChatMessage("user", "hi 中文"),
            ChatMessage("assistant", "hello 🎉"),
            ChatMessage("user", "thanks"),
        )
        val tools = emptyList<ToolSchema>()
        val body = AnthropicProvider(anthropicConfig(), { null }).buildBody(
            ChatRequest(model = "m", messages = history, tools = tools),
        )
        val root = strictObj(body)
        assertFalse(root.containsKey("tools"))
        val messages = root["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, messages.size)
        for (m in messages) {
            val blocks = m["content"]!!.jsonArray.map { it.jsonObject }
            assertTrue(blocks.none { it["type"]!!.jsonPrimitive.content == "tool_use" })
            assertTrue(blocks.none { it["type"]!!.jsonPrimitive.content == "tool_result" })
        }
    }

    @Test
    fun scaffoldStaysPureChatAndRegistryHidden() {
        // Production invariant: TurnController.buildRequest sends
        // ChatRequest(model, messages) with default tools == [] /
        // serverTools == [] / thinking == null, and visibleTools is empty
        // for all 41 tools until the wiring PR flips executionReady.
        val empty = ChatRequest(model = "m", messages = listOf(ChatMessage("user", "hi")))
        assertTrue(empty.tools.isEmpty())
        assertTrue(empty.serverTools.isEmpty())
        assertEquals(null, empty.thinking)
        val permissive = ProjectionContext(
            flavor = Flavor.GITHUB,
            automationEnabled = true,
            grantedPermissions = setOf(
                "android.permission.READ_CALENDAR",
                "android.permission.WRITE_CALENDAR",
                "android.permission.READ_CONTACTS",
                "listener:notification",
            ),
            userSwitches = mapOf(
                "shell" to true,
                "privilege_bridge" to true,
                "calendar" to true,
                "contacts_full" to true,
                "notification" to true,
                "screenshot" to true,
                "webfetch" to true,
                "websearch" to true,
                "database" to true,
                "lsp" to true,
                "clipboard" to true,
                "files" to true,
                "voice_input" to true,
                "voice_output" to true,
                "azure_tts" to true,
                "linux" to true,
                "compile" to true,
                "decompile" to true,
            ),
        )
        assertTrue(ToolRegistry.visibleTools(permissive).isEmpty())
        assertTrue(ToolRegistry.ALL.all { !it.executionReady })
    }
}
