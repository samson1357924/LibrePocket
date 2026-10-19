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
    fun redactedThinkingBlockMarksReasoningPresence() {
        val mapper = AnthropicMapper()
        val start = mapper.mapPayload(
            """{"type":"content_block_start","index":0,"content_block":{"type":"redacted_thinking"}}""",
        )
        assertEquals(listOf(StreamEvent.ReasoningPresent), start)
    }

    @Test
    fun signatureDeltaMarksReasoningPresenceWithoutPayload() {
        val mapper = AnthropicMapper()
        mapper.mapPayload(
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
        )
        val sig = mapper.mapPayload(
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"SIG-SECRET-ABC"}}""",
        )
        assertEquals(listOf(StreamEvent.ReasoningPresent), sig)
        for (e in sig) {
            assertFalse("signature leak: $e", e.toString().contains("SIG-SECRET-ABC"))
        }
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
    fun emptyTextDeltaFrameYieldsNoEvent() {
        // Empty-delta suppression (parity with ChatCompletions): an empty
        // text_delta frame yields NO TextDelta — an empty increment must
        // never pose as final-answer evidence downstream (continuation
        // completeness, maintainer P1). A following non-empty frame on the
        // same block still lands (guard, not arm-level mute).
        val mapper = AnthropicMapper()
        mapper.mapPayload(
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        )
        assertEquals(
            emptyList<StreamEvent>(),
            mapper.mapPayload(
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":""}}""",
            ),
        )
        val real = mapper.mapPayload(
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"real"}}""",
        )
        assertEquals(listOf(StreamEvent.TextDelta(0, 0, "real")), real)
    }

    @Test
    fun emptyThinkingDeltaFrameYieldsNoEvent() {
        // Same suppression on the thinking arm: an empty thinking frame is
        // dropped (zero payload must not flip the downstream reasoning-source
        // flag either). The block-start presence marker is unchanged.
        val mapper = AnthropicMapper()
        assertEquals(
            listOf(StreamEvent.ReasoningPresent),
            mapper.mapPayload(
                """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            ),
        )
        assertEquals(
            emptyList<StreamEvent>(),
            mapper.mapPayload(
                """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":""}}""",
            ),
        )
        val real = mapper.mapPayload(
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"reasoning payload"}}""",
        )
        assertEquals(listOf(StreamEvent.ReasoningDelta(0, 0, "reasoning payload")), real)
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

    // ---- Phase-0 review (C): blank tool id / name at content_block_stop ----

    @Test
    fun toolUseStartWithoutIdSynthesizesRepairIdAtStop() {
        // A tool_use block started without an id must not emit an empty
        // tool-call id at content_block_stop: internal pairing validation
        // (TOOL_CALL_MISSING_ID, non-retryable) and providers fail closed on
        // one. Repair parity with ResponsesProvider's output_item.done:
        // `call_` + 8 chars — and no empty id ever rides the done event.
        val mapper = AnthropicMapper()
        mapper.mapPayload(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","name":"get_weather"}}""",
        )
        val stopEvents = mapper.mapPayload("""{"type":"content_block_stop","index":0}""")
        val done = stopEvents.filterIsInstance<StreamEvent.ToolDone>().single()
        assertTrue("expected call_-prefixed synthetic id, was: ${done.id}", done.id.startsWith("call_"))
        assertEquals("call_XXXXXXXX".length, done.id.length)
        assertEquals("get_weather", done.name)
    }

    @Test
    fun mixedTextAndTwoToolBlocksProjectInOrderWithDistinctIds() {
        // Multi-call/mixed stream: a text block plus two tool_use blocks (one
        // explicit id, one blank repaired at stop). The mapper must keep
        // native indexes, preserve the explicit id, synthesize a distinct
        // non-empty id for the blank call, and still end on message_stop.
        val mapper = AnthropicMapper()
        assertEquals(
            emptyList<StreamEvent>(),
            mapper.mapPayload(
                """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            ),
        )
        assertEquals(
            listOf(StreamEvent.TextDelta(0, 0, "lookup: ")),
            mapper.mapPayload(
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lookup: "}}""",
            ),
        )
        mapper.mapPayload(
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_a","name":"get_weather"}}""",
        )
        mapper.mapPayload(
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"city\":\"Taipei\"}"}}""",
        )
        mapper.mapPayload("""{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","name":"get_time"}}""")
        mapper.mapPayload(
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"tz\":\"UTC\"}"}}""",
        )
        mapper.mapPayload("""{"type":"content_block_stop","index":0}""")
        val first = mapper.mapPayload("""{"type":"content_block_stop","index":1}""")
            .filterIsInstance<StreamEvent.ToolDone>().single()
        val second = mapper.mapPayload("""{"type":"content_block_stop","index":2}""")
            .filterIsInstance<StreamEvent.ToolDone>().single()
        mapper.mapPayload("""{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""")
        assertEquals(listOf(StreamEvent.Done("tool_use")), mapper.mapPayload("""{"type":"message_stop"}"""))

        assertEquals(1, first.toolIndex)
        assertEquals("toolu_a", first.id)
        assertEquals("get_weather", first.name)
        assertEquals("""{"city":"Taipei"}""", first.argumentsJson)
        assertEquals(2, second.toolIndex)
        assertTrue("blank id must be repaired, was: ${second.id}", second.id.startsWith("call_"))
        assertTrue(
            "repaired id must keep the canonical call_ + 8-hex shape, was: ${second.id}",
            Regex("call_[0-9a-f]{8}").matches(second.id),
        )
        assertEquals("get_time", second.name)
        assertEquals("""{"tz":"UTC"}""", second.argumentsJson)
    }

    @Test
    fun toolUseStartWithoutNameRidesBlankNameNoFabrication() {
        // Blank tool NAME: no synthesis by design — a nameless tool_use
        // rides its (empty) name verbatim; the turn layer's records-only
        // gate on the exact slice tool name can never execute a nameless
        // call (fail-closed downstream, never a wrong-name execution).
        val mapper = AnthropicMapper()
        mapper.mapPayload(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_wx8f"}}""",
        )
        val done = mapper.mapPayload("""{"type":"content_block_stop","index":0}""")
            .filterIsInstance<StreamEvent.ToolDone>().single()
        assertEquals("toolu_wx8f", done.id)
        assertEquals("", done.name)
    }

    // ---- P1-3: block-protocol latch + idempotent duplicate stop ----

    @Test
    fun duplicateContentBlockStopIsIdempotentSingleToolDone() {
        val mapper = AnthropicMapper()
        mapper.mapPayload(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","name":"get_weather"}}""",
        )
        val first = mapper.mapPayload("""{"type":"content_block_stop","index":0}""")
            .filterIsInstance<StreamEvent.ToolDone>().single()
        assertTrue("expected call_-prefixed synthetic id", first.id.startsWith("call_"))
        // Duplicate stop: same block already closed -> idempotent empty,
        // no second ToolDone, no second synthetic id.
        assertEquals(emptyList<StreamEvent>(), mapper.mapPayload("""{"type":"content_block_stop","index":0}"""))
        mapper.mapPayload("""{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""")
        assertEquals(listOf(StreamEvent.Done("tool_use")), mapper.mapPayload("""{"type":"message_stop"}"""))
    }

    @Test
    fun unknownIndexDeltaLatchesAndMessageStopFailsWithoutDone() {
        val mapper = AnthropicMapper()
        mapper.mapPayload(
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        )
        assertEquals(
            emptyList<StreamEvent>(),
            mapper.mapPayload(
                """{"type":"content_block_delta","index":5,"delta":{"type":"text_delta","text":"ghost"}}""",
            ),
        )
        mapper.mapPayload("""{"type":"content_block_stop","index":0}""")
        mapper.mapPayload("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")
        val stop = mapper.mapPayload("""{"type":"message_stop"}""")
        assertEquals(
            listOf(StreamEvent.Failed("SSE_TRUNCATED", retryable = true)),
            stop,
        )
        assertTrue(stop.none { it is StreamEvent.Done })
    }

    @Test
    fun unknownIndexStopLatchesAndMessageStopFails() {
        val mapper = AnthropicMapper()
        assertEquals(
            emptyList<StreamEvent>(),
            mapper.mapPayload("""{"type":"content_block_stop","index":7}"""),
        )
        mapper.mapPayload("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")
        assertEquals(
            listOf(StreamEvent.Failed("SSE_TRUNCATED", retryable = true)),
            mapper.mapPayload("""{"type":"message_stop"}"""),
        )
    }

    @Test
    fun deltaForClosedBlockLatchesAndMessageStopFails() {
        val mapper = AnthropicMapper()
        mapper.mapPayload(
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        )
        mapper.mapPayload("""{"type":"content_block_stop","index":0}""")
        assertEquals(
            emptyList<StreamEvent>(),
            mapper.mapPayload(
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"late"}}""",
            ),
        )
        mapper.mapPayload("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")
        assertEquals(
            listOf(StreamEvent.Failed("SSE_TRUNCATED", retryable = true)),
            mapper.mapPayload("""{"type":"message_stop"}"""),
        )
    }

    @Test
    fun toolUseStopReasonWithoutAnyToolBlockFailsRetryable() {
        val mapper = AnthropicMapper()
        mapper.mapPayload(
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        )
        mapper.mapPayload("""{"type":"content_block_stop","index":0}""")
        mapper.mapPayload("""{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""")
        val stop = mapper.mapPayload("""{"type":"message_stop"}""")
        assertEquals(
            listOf(StreamEvent.Failed("SSE_TRUNCATED", retryable = true)),
            stop,
        )
        assertTrue(stop.none { it is StreamEvent.Done })
    }

    // ---- P1-4: structured Anthropic error types ----

    @Test
    fun structuredAnthropicErrorTypesClassify() {
        val fatalAuth = AnthropicMapper().mapPayload(
            """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""",
        ).filterIsInstance<StreamEvent.Failed>().single()
        assertFalse(fatalAuth.retryable)

        val fatalRequest = AnthropicMapper().mapPayload(
            """{"type":"error","error":{"type":"invalid_request_error","message":"malformed request"}}""",
        ).filterIsInstance<StreamEvent.Failed>().single()
        assertFalse(fatalRequest.retryable)

        val retryable = AnthropicMapper().mapPayload(
            """{"type":"message_error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
        ).filterIsInstance<StreamEvent.Failed>().single()
        assertTrue(retryable.retryable)

        val mapper = AnthropicMapper()
        val events = mapper.mapPayload(
            """{"type":"error","error":{"type":"authentication_error","message":"bad"}}""",
        )
        assertTrue(mapper.terminalReached)
        assertTrue(events.none { it is StreamEvent.Done })
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
