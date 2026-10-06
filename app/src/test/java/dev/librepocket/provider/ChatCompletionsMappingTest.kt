package dev.librepocket.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Chat Completions projection tests (SPEC §3.2 / §3.5 / §4 / §7.2).
 * The main path replays the real-capture-style `sse/chat-basic.txt`
 * fixture through [SseFrameParser] (odd chunking) into [ChatCompletionsMapper].
 */
class ChatCompletionsMappingTest {

    private fun testConfig() = ProviderConfig(
        id = "00000000-0000-0000-0000-000000000001",
        label = "test",
        baseUrl = "https://api.example.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        apiKeyRef = "provider_key/00000000-0000-0000-0000-000000000001",
    )

    private fun replayFixture(): List<StreamEvent> {
        val bytes = javaClass.classLoader!!.getResourceAsStream("sse/chat-basic.txt")!!.readBytes()
        val parser = SseFrameParser()
        val mapper = ChatCompletionsMapper()
        val out = ArrayList<StreamEvent>()
        var off = 0
        while (off < bytes.size) {
            val len = minOf(37, bytes.size - off)
            for (f in parser.feed(bytes, off, len)) {
                if (f.isDone) mapper.markDone() else out += mapper.mapPayload(f.data)
            }
            off += len
        }
        for (f in parser.flush()) {
            if (f.isDone) mapper.markDone() else out += mapper.mapPayload(f.data)
        }
        out += mapper.finish()
        return out
    }

    @Test
    fun fixtureProjectsToUnifiedEventStream() {
        val events = replayFixture()

        val texts = events.filterIsInstance<StreamEvent.TextDelta>()
        assertEquals(listOf("我來幫你查一下"), texts.map { it.delta })
        assertEquals(0, texts[0].blockIndex)

        val reasoning = events.filterIsInstance<StreamEvent.ReasoningDelta>()
        assertEquals(listOf("用戶要查天氣，先確認城", "市。"), reasoning.map { it.delta })
        // Same visible kind continues the block; the tool switch opens a new one.
        assertEquals(1, reasoning[0].blockIndex)
        assertEquals(1, reasoning[1].blockIndex)

        val toolDeltas = events.filterIsInstance<StreamEvent.ToolDelta>()
        assertEquals(3, toolDeltas.size)

        val usage = events.filterIsInstance<StreamEvent.Usage>()
        assertEquals(listOf(StreamEvent.Usage(128, 64)), usage)

        val dones = events.filterIsInstance<StreamEvent.ToolDone>()
        assertEquals(2, dones.size)
        // Empty id chunk never overwrote the valid id.
        assertEquals("call_wx8fQ2zA", dones[0].id)
        assertEquals("get_weather", dones[0].name)
        assertEquals("{\"city\":\"Taipei\"}", dones[0].argumentsJson)
        // Missing id was repaired with a response-scoped unique value.
        assertTrue(dones[1].id.startsWith("call_"))
        assertNotEquals(dones[0].id, dones[1].id)
        assertEquals("get_time", dones[1].name)
        assertEquals("{}", dones[1].argumentsJson)

        val terminal = events.last()
        assertEquals(StreamEvent.Done("tool_calls"), terminal)
    }

    @Test
    fun missingDoneMarkerFailsRetryable() {
        val mapper = ChatCompletionsMapper()
        val out = ArrayList<StreamEvent>()
        out += mapper.mapPayload(
            """{"choices":[{"delta":{"content":"hi"},"finish_reason":"stop"}]}""",
        )
        out += mapper.finish() // no [DONE] seen
        assertEquals(StreamEvent.Failed("SSE_TRUNCATED", retryable = true), out.last())
    }

    @Test
    fun missingFinishReasonFailsRetryable() {
        val mapper = ChatCompletionsMapper()
        mapper.mapPayload("""{"choices":[{"delta":{"content":"half"}}]}""")
        mapper.markDone()
        val terminal = mapper.finish().last()
        assertEquals(StreamEvent.Failed("SSE_TRUNCATED", retryable = true), terminal)
    }

    @Test
    fun reasoningRepresentationsDeduplicatedPerChunk() {
        val mapper = ChatCompletionsMapper()
        val events = mapper.mapPayload(
            """{"choices":[{"delta":{"reasoning_content":"first","reasoning":"second",""" +
                """"reasoning_details":[{"summary":"third"}],"content":""}}]}""",
        )
        val reasoning = events.filterIsInstance<StreamEvent.ReasoningDelta>()
        assertEquals(1, reasoning.size)
        assertEquals("first", reasoning[0].delta)
    }

    @Test
    fun escapedUnicodeDecodes() {
        val mapper = ChatCompletionsMapper()
        val events = mapper.mapPayload(
            """{"choices":[{"delta":{"content":"\u4e2d\u6587"}}]}""",
        )
        assertEquals("中文", (events.single() as StreamEvent.TextDelta).delta)
    }

    @Test
    fun nonJsonPayloadIgnoredBySniffing() {
        val mapper = ChatCompletionsMapper()
        assertTrue(mapper.mapPayload("not json at all").isEmpty())
        assertTrue(mapper.mapPayload("""{"type":"anthropic-thing"}""").isEmpty())
    }

    @Test
    fun requestBodyShape() {
        val provider = ChatCompletionsProvider(testConfig(), { null })
        val body = provider.buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(
                    ChatMessage("system", "sys-a"),
                    ChatMessage("user", "hi", images = listOf(ChatImage(byteArrayOf(1, 2, 3), "image/png"))),
                ),
                tools = listOf(ToolSchema("get_weather", "desc", """{"type":"object"}""")),
                maxTokens = 64,
                systemPromptOverride = "sys-override",
            ),
        )
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"model\":\"m\""))
        assertTrue(body.contains("\"max_tokens\":64"))
        // System messages collapse into one leading system message, order kept
        // (newline is JSON-escaped in the wire body).
        assertTrue(body.contains("sys-override\\nsys-a"))
        assertTrue(body.contains("\"type\":\"function\""))
        assertTrue(body.contains("\"parameters\":{\"type\":\"object\"}"))
        assertTrue(body.contains("\"type\":\"image_url\""))
        assertTrue(body.contains("data:image/png;base64,"))
    }

    @Test
    fun endpointJoining() {
        val provider = ChatCompletionsProvider(testConfig(), { null })
        assertEquals("https://h/v1/chat/completions", provider.endpoint("https://h/v1"))
        assertEquals("https://h/v1/chat/completions", provider.endpoint("https://h/v1/"))
        assertEquals("https://h/v1/chat/completions", provider.endpoint("https://h/v1/chat/completions"))
    }

    @Test
    fun baseUrlValidation() {
        validateBaseUrl("https://api.example.com/v1")
        validateBaseUrl("http://localhost:8080/v1")
        validateBaseUrl("http://127.0.0.1:8080/")
        validateBaseUrl("http://[::1]:8080/")
        validateBaseUrl("http://192.168.1.20:8080/")
        validateBaseUrl("http://10.0.2.2:8080/")
        for (bad in listOf("http://api.example.com/v1", "ftp://x/", "not a url")) {
            try {
                validateBaseUrl(bad)
                fail("expected rejection: $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun imagesBeyondFourAreCut() {
        val provider = ChatCompletionsProvider(testConfig(), { null })
        val imgs = List(6) { ChatImage(byteArrayOf(it.toByte()), "image/jpeg") }
        val parts = provider.contentParts(ChatMessage("user", "t", images = imgs))
        assertEquals(4, Regex("\"type\":\"image_url\"").findAll(parts).count())
    }

    @Test
    fun oversizeImageRejectedNeverDegraded() {
        val provider = ChatCompletionsProvider(testConfig(), { null })
        val big = ByteArray((ImageFallbackPolicy.MAX_SINGLE_IMAGE_BYTES + 1).toInt())
        try {
            provider.contentParts(ChatMessage("user", "t", images = listOf(ChatImage(big, "image/png"))))
            fail("expected IMAGE_TOO_LARGE")
        } catch (e: ProviderFailure) {
            assertEquals("IMAGE_TOO_LARGE", e.message)
        }
    }

    @Test
    fun extractModelIdsTolerant() {
        assertEquals(
            listOf("a", "b"),
            extractModelIds("""{"data":[{"id":"a"},{"id":"b"}],"object":"list"}"""),
        )
        assertTrue(extractModelIds("garbage").isEmpty())
    }

}
