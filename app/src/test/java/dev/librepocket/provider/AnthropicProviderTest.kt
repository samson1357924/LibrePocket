package dev.librepocket.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anthropic Messages adapter tests (SPEC §3.2 / §3.5 / §10.3).
 *
 * The main path replays the sanitized real-capture-style
 * `sse/anthropic-thinking.txt` fixture through [SseFrameParser] (odd
 * chunking) into [AnthropicMapper]: thinking projection, native
 * `content_block.index` identity, tool aggregation, signature-block
 * non-persistence, and the `message_stop` authoritative terminal.
 */
class AnthropicProviderTest {

    private fun testConfig() = ProviderConfig(
        id = "00000000-0000-0000-0000-000000000003",
        label = "test",
        baseUrl = "https://api.anthropic.com",
        protocol = ProviderProtocol.ANTHROPIC,
        apiKeyRef = "provider_key/00000000-0000-0000-0000-000000000003",
    )

    private fun replayFixture(): List<StreamEvent> {
        val bytes = javaClass.classLoader!!.getResourceAsStream("sse/anthropic-thinking.txt")!!.readBytes()
        val parser = SseFrameParser()
        val mapper = AnthropicMapper()
        val out = ArrayList<StreamEvent>()
        var off = 0
        while (off < bytes.size) {
            val len = minOf(43, bytes.size - off)
            for (f in parser.feed(bytes, off, len)) {
                out += mapper.mapPayload(f.data)
                if (mapper.terminalReached) break
            }
            off += len
        }
        for (f in parser.flush()) {
            out += mapper.mapPayload(f.data)
        }
        out += mapper.finish()
        return out
    }

    @Test
    fun fixtureProjectsThinkingTextAndToolBlocks() {
        val events = replayFixture()

        val usage = events.filterIsInstance<StreamEvent.Usage>()
        assertEquals(StreamEvent.Usage(128, null), usage.first())
        assertEquals(StreamEvent.Usage(128, 64), usage.last())

        val reasoning = events.filterIsInstance<StreamEvent.ReasoningDelta>()
        assertEquals(listOf("用戶要查天氣", "，先確認城市。"), reasoning.map { it.delta })
        // Native content_block.index is kept as blockIndex (§3.5).
        assertEquals(0, reasoning[0].blockIndex)

        val texts = events.filterIsInstance<StreamEvent.TextDelta>()
        assertEquals(listOf("我來幫你查一下"), texts.map { it.delta })
        assertEquals(1, texts[0].blockIndex)

        val dones = events.filterIsInstance<StreamEvent.ToolDone>()
        assertEquals(1, dones.size)
        assertEquals(2, dones[0].toolIndex)
        assertEquals("toolu_wx8f", dones[0].id)
        assertEquals("get_weather", dones[0].name)
        assertEquals("{\"city\":\"Taipei\"}", dones[0].argumentsJson)

        assertEquals(StreamEvent.Done("tool_use"), events.last())
    }

    @Test
    fun signatureBlocksNeverSurfaceAsEvents() {
        val events = replayFixture()
        for (e in events) {
            assertFalse("signature leak: $e", e.toString().contains("SIG-SECRET-ABC"))
        }
        val reasoning = events.filterIsInstance<StreamEvent.ReasoningDelta>()
        assertEquals(2, reasoning.size)
    }

    @Test
    fun missingMessageStopFailsRetryable() {
        val mapper = AnthropicMapper()
        mapper.mapPayload(
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        )
        mapper.mapPayload(
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"half"}}""",
        )
        val terminal = mapper.finish().last()
        assertEquals(StreamEvent.Failed("SSE_TRUNCATED", retryable = true), terminal)
    }

    @Test
    fun unclosedBlockAtMessageStopFailsRetryable() {
        val mapper = AnthropicMapper()
        mapper.mapPayload(
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
        )
        mapper.mapPayload(
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"half"}}""",
        )
        // No content_block_stop, but message_stop arrives with a stop reason.
        mapper.mapPayload("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")
        val events = mapper.mapPayload("""{"type":"message_stop"}""")
        assertEquals(StreamEvent.Failed("SSE_TRUNCATED", retryable = true), events.last())
    }

    @Test
    fun anthropicVersionAndMaxTokensContract() {
        assertEquals("2023-06-01", AnthropicProvider.ANTHROPIC_VERSION)
        val provider = AnthropicProvider(testConfig(), { null })
        val body = provider.buildBody(
            ChatRequest(model = "m", messages = listOf(ChatMessage("user", "hi"))),
        )
        // max_tokens is required on the wire; the adapter defaults it.
        assertTrue(body.contains("\"max_tokens\":${AnthropicProvider.DEFAULT_MAX_TOKENS}"))
        assertTrue(body.contains("\"stream\":true"))
        val explicit = provider.buildBody(
            ChatRequest(model = "m", messages = listOf(ChatMessage("user", "hi")), maxTokens = 64),
        )
        assertTrue(explicit.contains("\"max_tokens\":64"))
    }

    @Test
    fun systemMessagesProjectToTopLevelSystem() {
        val provider = AnthropicProvider(testConfig(), { null })
        val body = provider.buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(
                    ChatMessage("system", "sys-a"),
                    ChatMessage("user", "hi"),
                ),
                systemPromptOverride = "sys-override",
            ),
        )
        assertTrue(body.contains("\"system\":\"sys-override\\nsys-a\""))
    }

    @Test
    fun endpointJoining() {
        val provider = AnthropicProvider(testConfig(), { null })
        assertEquals("https://h/v1/messages", provider.endpoint("https://h"))
        assertEquals("https://h/v1/messages", provider.endpoint("https://h/"))
        assertEquals("https://h/v1/messages", provider.endpoint("https://h/v1/messages"))
    }
}
