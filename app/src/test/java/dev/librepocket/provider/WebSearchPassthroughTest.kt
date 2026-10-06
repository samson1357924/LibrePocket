package dev.librepocket.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务端 web_search 透传测试（默认关闭）。
 *
 * - 开/关：请求体是否携带 hosted 字段；
 * - 转录标记：服务端搜索走 TextDelta + `[SERVER_TOOL:…]`，与本地 `[tool:…]` 区分；
 * - 脱敏：开启后外发文本先过 Redactor，关闭时零行为变化；
 * - 错误分类：厂商不支持（400 未知字段）为 FATAL，且错误信息脱敏。
 */
class WebSearchPassthroughTest {

    private fun chatConfig() = ProviderConfig(
        id = "00000000-0000-0000-0000-0000000000a1",
        label = "test",
        baseUrl = "https://api.example.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        apiKeyRef = "provider_key/00000000-0000-0000-0000-0000000000a1",
    )

    private fun responsesConfig() = ProviderConfig(
        id = "00000000-0000-0000-0000-0000000000a2",
        label = "test",
        baseUrl = "https://api.example.com/v1",
        protocol = ProviderProtocol.RESPONSES,
        apiKeyRef = "provider_key/00000000-0000-0000-0000-0000000000a2",
    )

    // ---- 开关：Responses ----

    @Test
    fun responsesDisabledOmitsWebSearch() {
        val provider = ResponsesProvider(responsesConfig(), { null })
        val body = provider.buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage("user", "hi")),
                tools = listOf(ToolSchema("get_weather", "desc", """{"type":"object"}""")),
            ),
        )
        assertFalse(body.contains("web_search"))
        assertFalse(body.contains("search_context_size"))
        // 本地 tool 形状不变。
        assertTrue(body.contains("\"type\":\"function\""))
    }

    @Test
    fun responsesEnabledEmitsHostedToolWithSizes() {
        val provider = ResponsesProvider(responsesConfig(), { null })
        for ((size, wire) in listOf(
            WebSearchContextSize.LOW to "low",
            WebSearchContextSize.MEDIUM to "medium",
            WebSearchContextSize.HIGH to "high",
        )) {
            val body = provider.buildBody(
                ChatRequest(
                    model = "m",
                    messages = listOf(ChatMessage("user", "hi")),
                    serverTools = listOf(ServerTool.WebSearch(size)),
                ),
            )
            assertTrue("missing web_search for $size: $body", body.contains("\"type\":\"web_search\""))
            assertTrue(body.contains("\"search_context_size\":\"$wire\""))
        }
    }

    @Test
    fun responsesEnabledPreservesLocalTools() {
        val provider = ResponsesProvider(responsesConfig(), { null })
        val body = provider.buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage("user", "hi")),
                tools = listOf(ToolSchema("get_weather", "desc", """{"type":"object"}""")),
                serverTools = listOf(ServerTool.WebSearch(WebSearchContextSize.HIGH)),
            ),
        )
        assertTrue(body.contains("\"type\":\"function\""))
        assertTrue(body.contains("\"type\":\"web_search\""))
        assertTrue(body.contains("\"search_context_size\":\"high\""))
    }

    // ---- 开关：Chat Completions ----

    @Test
    fun chatDisabledOmitsWebSearchOptions() {
        val provider = ChatCompletionsProvider(chatConfig(), { null })
        val body = provider.buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage("user", "hi")),
                tools = listOf(ToolSchema("get_weather", "desc", """{"type":"object"}""")),
            ),
        )
        assertFalse(body.contains("web_search_options"))
        assertFalse(body.contains("search_context_size"))
    }

    @Test
    fun chatEnabledEmitsAdditiveOptions() {
        val provider = ChatCompletionsProvider(chatConfig(), { null })
        val body = provider.buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage("user", "hi")),
                tools = listOf(ToolSchema("get_weather", "desc", """{"type":"object"}""")),
                serverTools = listOf(ServerTool.WebSearch(WebSearchContextSize.LOW)),
            ),
        )
        assertTrue(body.contains("\"web_search_options\""))
        assertTrue(body.contains("\"search_context_size\":\"low\""))
        // Additive：本地 tools 仍在。
        assertTrue(body.contains("\"type\":\"function\""))
    }

    // ---- 转录标记 ----

    @Test
    fun responsesWebSearchNeverBecomesToolDone() {
        val mapper = ResponsesMapper()
        val out = ArrayList<StreamEvent>()
        out += mapper.mapPayload(
            """{"type":"response.output_item.added","output_index":1,"item_id":"ws_1","item":{"type":"web_search_call","id":"ws_1"}}""",
        )
        out += mapper.mapPayload(
            """{"type":"response.web_search_call.searching","output_index":1,"item_id":"ws_1"}""",
        )
        out += mapper.mapPayload(
            """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"台北天氣晴"}""",
        )
        out += mapper.mapPayload(
            """{"type":"response.output_item.done","output_index":1,"item_id":"ws_1","item":{"type":"web_search_call","id":"ws_1"}}""",
        )

        val texts = out.filterIsInstance<StreamEvent.TextDelta>()
        // 可见回答文本仍走 TextDelta。
        assertTrue(texts.any { it.delta == "台北天氣晴" })
        // 服务端搜索生命周期以 SERVER_TOOL 标记透出，同样走 TextDelta。
        val markers = texts.filter { ServerToolTranscript.isServerToolMarker(it.delta) }
        assertTrue("expected server-tool markers, got $texts", markers.size >= 2)
        // 永不产生本地 ToolDone；与本地 tool call 区分。
        assertTrue(out.filterIsInstance<StreamEvent.ToolDone>().isEmpty())
        assertTrue(out.filterIsInstance<StreamEvent.ToolDelta>().isEmpty())
        for (m in markers) {
            assertFalse(ServerToolTranscript.isLocalToolMarker(m.delta))
        }
    }

    @Test
    fun responsesLocalFunctionCallSemanticsUnchanged() {
        val mapper = ResponsesMapper()
        val out = ArrayList<StreamEvent>()
        out += mapper.mapPayload(
            """{"type":"response.output_item.added","output_index":2,"item_id":"item_wx8f","item":{"type":"function_call","id":"","call_id":"call_wx8fQ2zA","name":"get_weather"}}""",
        )
        out += mapper.mapPayload(
            """{"type":"response.function_call_arguments.delta","output_index":2,"item_id":"item_wx8f","delta":"{}"}""",
        )
        out += mapper.mapPayload(
            """{"type":"response.output_item.done","output_index":2,"item_id":"item_wx8f","item":{"type":"function_call","id":"item_wx8f","call_id":"call_wx8fQ2zA","name":"get_weather","arguments":"{}"}}""",
        )
        val dones = out.filterIsInstance<StreamEvent.ToolDone>()
        assertEquals(1, dones.size)
        assertEquals("call_wx8fQ2zA", dones[0].id)
        assertEquals("get_weather", dones[0].name)
    }

    @Test
    fun chatCitationAnnotationEmitsServerToolMarker() {
        val mapper = ChatCompletionsMapper()
        val events = mapper.mapPayload(
            """{"choices":[{"delta":{"content":"台北","annotations":[{"type":"url_citation","url_citation":{"url":"https://example.com/a","title":"t"}}]}}]}""",
        )
        val texts = events.filterIsInstance<StreamEvent.TextDelta>()
        assertTrue(texts.any { it.delta == "台北" })
        val markers = texts.filter { ServerToolTranscript.isServerToolMarker(it.delta) }
        assertEquals(1, markers.size)
        assertTrue(markers[0].delta.contains("web_search"))
        assertTrue(events.filterIsInstance<StreamEvent.ToolDone>().isEmpty())
    }

    @Test
    fun chatWithoutCitationEmitsNoMarker() {
        val mapper = ChatCompletionsMapper()
        val events = mapper.mapPayload("""{"choices":[{"delta":{"content":"hi"}}]}""")
        assertTrue(events.filterIsInstance<StreamEvent.TextDelta>().any { it.delta == "hi" })
        assertTrue(events.none { it is StreamEvent.TextDelta && ServerToolTranscript.isServerToolMarker(it.delta) })
    }

    // ---- 脱敏 ----

    @Test
    fun serverSearchRedactsSensitiveBeforeSend() {
        val sensitive = "聯絡 test@example.com 再說"
        val responses = ResponsesProvider(responsesConfig(), { null })
        val redactedBody = responses.buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage("user", sensitive)),
                serverTools = listOf(ServerTool.WebSearch()),
            ),
        )
        assertFalse("raw PII leaked: $redactedBody", redactedBody.contains("test@example.com"))
        assertTrue(redactedBody.contains("⟦REDACTED"))

        val chat = ChatCompletionsProvider(chatConfig(), { null })
        val chatBody = chat.buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage("user", sensitive)),
                serverTools = listOf(ServerTool.WebSearch()),
            ),
        )
        assertFalse(chatBody.contains("test@example.com"))
        assertTrue(chatBody.contains("⟦REDACTED"))
    }

    @Test
    fun disabledKeepsZeroBehaviorChange() {
        val sensitive = "聯絡 test@example.com 再說"
        val responses = ResponsesProvider(responsesConfig(), { null })
        val plain = responses.buildBody(
            ChatRequest(model = "m", messages = listOf(ChatMessage("user", sensitive))),
        )
        // 关闭时不脱敏、不改形状（本地链路原文直传，由存储/导出层脱敏）。
        assertTrue(plain.contains("test@example.com"))

        val chat = ChatCompletionsProvider(chatConfig(), { null })
        val chatPlain = chat.buildBody(
            ChatRequest(model = "m", messages = listOf(ChatMessage("user", sensitive))),
        )
        assertTrue(chatPlain.contains("test@example.com"))
    }

    // ---- 错误分类 ----

    @Test
    fun unsupportedVendorErrorIsFatal() {
        assertEquals(
            FailureKind.FATAL,
            ProviderErrorClassifier.classify(400, null, """{"error":{"message":"Unknown field 'web_search_options'"}}"""),
        )
        assertEquals(
            FailureKind.FATAL,
            ProviderErrorClassifier.classify(400, null, """{"error":{"message":"Unknown tool 'web_search'"}}"""),
        )
    }

    @Test
    fun unsupportedErrorMessageIsRedacted() {
        val key = "sk-testSECRET1234567890"
        val msg = redactedError("HTTP 400", "web_search_options rejected $key")
        assertFalse(msg.contains(key))
        assertTrue(msg.contains("⟦REDACTED"))
        assertTrue(msg.startsWith("HTTP 400"))
    }
}
