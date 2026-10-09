package dev.librepocket.provider

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
 * Phase 1: request-body strict JSON round-trip for every tool shape.
 *
 * All bodies are parsed with `Json.parseToJsonElement` (strict: any extra
 * `}` / unbalanced bracket throws). Empty-tools cases document the current
 * production zero-behavior path (TurnController sends no tools); non-empty
 * cases expose the latent extra-brace bug in the Responses/Anthropic adapters
 * (Chat was already balanced; its rewrite only unifies the AST path).
 */
class ProviderToolWireStrictTest {

    private fun chatConfig() = ProviderConfig(
        id = "00000000-0000-0000-0000-00000000aa01",
        label = "test",
        baseUrl = "https://api.example.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        apiKeyRef = "provider_key/00000000-0000-0000-0000-00000000aa01",
    )

    private fun responsesConfig() = ProviderConfig(
        id = "00000000-0000-0000-0000-00000000aa02",
        label = "test",
        baseUrl = "https://api.example.com/v1",
        protocol = ProviderProtocol.RESPONSES,
        apiKeyRef = "provider_key/00000000-0000-0000-0000-00000000aa02",
    )

    private fun anthropicConfig() = ProviderConfig(
        id = "00000000-0000-0000-0000-00000000aa03",
        label = "test",
        baseUrl = "https://api.anthropic.com",
        protocol = ProviderProtocol.ANTHROPIC,
        apiKeyRef = "provider_key/00000000-0000-0000-0000-00000000aa03",
    )

    private fun strictObj(body: String) = Json.parseToJsonElement(body).jsonObject

    // ---- shared adversarial leaves: nested quotes, backslash, newline/tab, unicode ----

    private val advName = "we\"ird\\tool"
    private val advDesc = "desc \"quoted\" back\\slash newline\n tab\t 中文🎉 café"
    private val advText = "say \"hi\" \\ bye\nnewline 中文🎉"
    private val advSchema =
        """{"type":"object","properties":{"q":{"type":"string","description":"a \"quoted\" \\ desc 中文"}},"required":["q"]}"""

    private fun advTools() = listOf(
        ToolSchema("plain_tool", "plain desc", """{"type":"object"}"""),
        ToolSchema(advName, advDesc, advSchema),
        ToolSchema("third_tool", "第三個工具 🎉", """{"type":"object","properties":{}}"""),
    )

    // ---- empty-tools: current production path, must stay valid ----

    @Test
    fun responsesEmptyToolsStrictPasses() {
        val body = ResponsesProvider(responsesConfig(), { null }).buildBody(
            ChatRequest(model = "m", messages = listOf(ChatMessage("user", "hi"))),
        )
        val root = strictObj(body)
        assertFalse(root.containsKey("tools"))
    }

    @Test
    fun chatEmptyToolsStrictPasses() {
        val body = ChatCompletionsProvider(chatConfig(), { null }).buildBody(
            ChatRequest(model = "m", messages = listOf(ChatMessage("user", "hi"))),
        )
        val root = strictObj(body)
        assertFalse(root.containsKey("tools"))
        assertFalse(root.containsKey("web_search_options"))
    }

    @Test
    fun anthropicEmptyToolsStrictPasses() {
        val body = AnthropicProvider(anthropicConfig(), { null }).buildBody(
            ChatRequest(model = "m", messages = listOf(ChatMessage("user", "hi"))),
        )
        val root = strictObj(body)
        assertFalse(root.containsKey("tools"))
    }

    // ---- single / multi tools ----

    @Test
    fun responsesSingleToolStrictPasses() {
        val body = ResponsesProvider(responsesConfig(), { null }).buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage("user", "hi")),
                tools = listOf(ToolSchema("get_weather", "desc", """{"type":"object"}""")),
            ),
        )
        val tools = strictObj(body)["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals("get_weather", tools[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun responsesMultiToolStrictPasses() {
        val tools = advTools()
        val body = ResponsesProvider(responsesConfig(), { null }).buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage("user", advText)),
                tools = tools,
            ),
        )
        val arr = strictObj(body)["tools"]!!.jsonArray
        assertEquals(tools.size, arr.size)
        for (i in tools.indices) {
            assertEquals(tools[i].name, arr[i].jsonObject["name"]!!.jsonPrimitive.content)
            assertEquals(tools[i].description, arr[i].jsonObject["description"]!!.jsonPrimitive.content)
        }
        assertEquals(advName, arr[1].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun chatSingleToolStrictPasses() {
        val body = ChatCompletionsProvider(chatConfig(), { null }).buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage("user", "hi")),
                tools = listOf(ToolSchema("get_weather", "desc", """{"type":"object"}""")),
            ),
        )
        val tools = strictObj(body)["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals("get_weather", tools[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun chatMultiToolStrictPasses() {
        val tools = advTools()
        val body = ChatCompletionsProvider(chatConfig(), { null }).buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage("user", advText)),
                tools = tools,
            ),
        )
        val arr = strictObj(body)["tools"]!!.jsonArray
        assertEquals(tools.size, arr.size)
        for (i in tools.indices) {
            val fn = arr[i].jsonObject["function"]!!.jsonObject
            assertEquals(tools[i].name, fn["name"]!!.jsonPrimitive.content)
            assertEquals(tools[i].description, fn["description"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun anthropicSingleToolStrictPasses() {
        val body = AnthropicProvider(anthropicConfig(), { null }).buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage("user", "hi")),
                tools = listOf(ToolSchema("get_weather", "desc", """{"type":"object"}""")),
            ),
        )
        val tools = strictObj(body)["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals("get_weather", tools[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun anthropicMultiToolStrictPasses() {
        val tools = advTools()
        val body = AnthropicProvider(anthropicConfig(), { null }).buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage("user", advText)),
                tools = tools,
            ),
        )
        val arr = strictObj(body)["tools"]!!.jsonArray
        assertEquals(tools.size, arr.size)
        for (i in tools.indices) {
            assertEquals(tools[i].name, arr[i].jsonObject["name"]!!.jsonPrimitive.content)
            assertEquals(tools[i].description, arr[i].jsonObject["description"]!!.jsonPrimitive.content)
        }
    }

    // ---- hosted coexistence ----

    @Test
    fun responsesHostedOnlyStrictPasses() {
        val body = ResponsesProvider(responsesConfig(), { null }).buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage("user", "hi")),
                serverTools = listOf(ServerTool.WebSearch(WebSearchContextSize.MEDIUM)),
            ),
        )
        val tools = strictObj(body)["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals("web_search", tools[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun responsesHostedCoexistsWithLocalTools() {
        val tools = advTools()
        val body = ResponsesProvider(responsesConfig(), { null }).buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage("user", advText)),
                tools = tools,
                serverTools = listOf(ServerTool.WebSearch(WebSearchContextSize.HIGH)),
            ),
        )
        val arr = strictObj(body)["tools"]!!.jsonArray
        assertEquals(tools.size + 1, arr.size)
        val types = arr.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(List(tools.size) { "function" } + "web_search", types)
        val hosted = arr.last().jsonObject
        assertEquals("high", hosted["search_context_size"]!!.jsonPrimitive.content)
    }

    @Test
    fun chatHostedOnlyStrictPasses() {
        val body = ChatCompletionsProvider(chatConfig(), { null }).buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage("user", "hi")),
                serverTools = listOf(ServerTool.WebSearch(WebSearchContextSize.LOW)),
            ),
        )
        val root = strictObj(body)
        assertFalse(root.containsKey("tools"))
        assertEquals("low", root["web_search_options"]!!.jsonObject["search_context_size"]!!.jsonPrimitive.content)
    }

    @Test
    fun chatHostedCoexistsWithLocalTools() {
        val tools = advTools()
        val body = ChatCompletionsProvider(chatConfig(), { null }).buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage("user", advText)),
                tools = tools,
                serverTools = listOf(ServerTool.WebSearch(WebSearchContextSize.LOW)),
            ),
        )
        val root = strictObj(body)
        assertEquals(tools.size, root["tools"]!!.jsonArray.size)
        assertEquals("low", root["web_search_options"]!!.jsonObject["search_context_size"]!!.jsonPrimitive.content)
    }

    // ---- message-embedded tool shapes ----

    @Test
    fun chatMessageToolCallsStrictPass() {
        val tc = ToolCall(
            id = "call_\"x\\1",
            name = advName,
            argumentsJson = """{"q":"v\"v\\中"}""",
        )
        val body = ChatCompletionsProvider(chatConfig(), { null }).buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(
                    ChatMessage("user", advText),
                    ChatMessage("assistant", "", toolCalls = listOf(tc)),
                    ChatMessage("tool", advText, toolCallId = tc.id),
                ),
                tools = advTools(),
            ),
        )
        val root = strictObj(body)
        val messages = root["messages"]!!.jsonArray
        val assistant = messages.first { it.jsonObject["role"]!!.jsonPrimitive.content == "assistant" }.jsonObject
        val calls = assistant["tool_calls"]!!.jsonArray
        assertEquals(1, calls.size)
        assertEquals(tc.id, calls[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(tc.argumentsJson, calls[0].jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
        val toolMsg = messages.first { it.jsonObject["role"]!!.jsonPrimitive.content == "tool" }.jsonObject
        assertEquals(tc.id, toolMsg["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun anthropicToolMessagesStrictPass() {
        val tc = ToolCall(
            id = "toolu_\"x\\1",
            name = advName,
            argumentsJson = """{"q":"v\"v\\中"}""",
        )
        val body = AnthropicProvider(anthropicConfig(), { null }).buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(
                    ChatMessage("user", advText),
                    ChatMessage("assistant", "", toolCalls = listOf(tc)),
                    ChatMessage("tool", advText, toolCallId = tc.id),
                ),
                tools = advTools(),
            ),
        )
        strictObj(body)
    }

    @Test
    fun responsesAdversarialInputMessageStrictPasses() {
        val body = ResponsesProvider(responsesConfig(), { null }).buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage("user", advText)),
                tools = advTools(),
            ),
        )
        val root = strictObj(body)
        val input = root["input"]!!.jsonArray
        assertTrue(input.isNotEmpty())
        val text = input[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals(advText, text)
    }

    // ---- registry sweep: every declared schema must be strict-parseable ----

    @Test
    fun registrySchemasStrictPass() {
        for (tool in ToolRegistry.ALL) {
            Json.parseToJsonElement(tool.jsonSchema)
        }
        assertTrue(ToolRegistry.ALL.isNotEmpty())
    }

    @Test
    fun allRegistryToolsStrictPassPerAdapter() {
        val tools = ToolRegistry.ALL.map { def ->
            ToolSchema(def.name, def.description, def.jsonSchema)
        }
        val messages = listOf(ChatMessage("user", advText))
        val responsesBody = ResponsesProvider(responsesConfig(), { null }).buildBody(
            ChatRequest(
                model = "m",
                messages = messages,
                tools = tools,
                serverTools = listOf(ServerTool.WebSearch(WebSearchContextSize.HIGH)),
            ),
        )
        assertEquals(
            tools.size + 1,
            strictObj(responsesBody)["tools"]!!.jsonArray.size,
        )
        val chatBody = ChatCompletionsProvider(chatConfig(), { null }).buildBody(
            ChatRequest(
                model = "m",
                messages = messages,
                tools = tools,
                serverTools = listOf(ServerTool.WebSearch(WebSearchContextSize.LOW)),
            ),
        )
        assertEquals(tools.size, strictObj(chatBody)["tools"]!!.jsonArray.size)
        val anthropicBody = AnthropicProvider(anthropicConfig(), { null }).buildBody(
            ChatRequest(model = "m", messages = messages, tools = tools),
        )
        assertEquals(tools.size, strictObj(anthropicBody)["tools"]!!.jsonArray.size)
    }
}
