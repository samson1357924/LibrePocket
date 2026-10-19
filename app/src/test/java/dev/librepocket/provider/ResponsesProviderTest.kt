package dev.librepocket.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Responses adapter tests (SPEC §3.2 / §3.5 / §10.3).
 *
 * The main path replays the sanitized real-capture-style
 * `sse/responses-recover.txt` fixture through [SseFrameParser] (odd chunking)
 * into [ResponsesMapper]: text recovery from an empty-`output`
 * `response.completed`, reasoning deltas, tool aggregation, and the opaque
 * (encrypted-reasoning) non-persistence rule.
 */
class ResponsesProviderTest {

    private fun testConfig() = ProviderConfig(
        id = "00000000-0000-0000-0000-000000000002",
        label = "test",
        baseUrl = "https://api.example.com/v1",
        protocol = ProviderProtocol.RESPONSES,
        apiKeyRef = "provider_key/00000000-0000-0000-0000-000000000002",
    )

    private fun replayFixture(): List<StreamEvent> {
        val bytes = javaClass.classLoader!!.getResourceAsStream("sse/responses-recover.txt")!!.readBytes()
        val parser = SseFrameParser()
        val mapper = ResponsesMapper()
        val out = ArrayList<StreamEvent>()
        var off = 0
        while (off < bytes.size) {
            val len = minOf(41, bytes.size - off)
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
    fun fixtureRecoversEmptyCompletedOutputFromStreamedDeltas() {
        val events = replayFixture()

        val texts = events.filterIsInstance<StreamEvent.TextDelta>()
        assertEquals(listOf("今天台北", "天氣晴。"), texts.map { it.delta })
        // (output_index, content_index) -> blockIndex mapping (§3.5).
        assertEquals(0, texts[0].blockIndex)

        val reasoning = events.filterIsInstance<StreamEvent.ReasoningDelta>()
        assertEquals(listOf("先查天氣再回覆"), reasoning.map { it.delta })
        assertEquals(1000, reasoning[0].blockIndex)

        val dones = events.filterIsInstance<StreamEvent.ToolDone>()
        assertEquals(1, dones.size)
        assertEquals("call_wx8fQ2zA", dones[0].id)
        assertEquals("get_weather", dones[0].name)
        assertEquals("{\"city\":\"Taipei\"}", dones[0].argumentsJson)

        val usage = events.filterIsInstance<StreamEvent.Usage>()
        assertEquals(listOf(StreamEvent.Usage(128, 64)), usage)

        // Empty `output` array: authoritative terminal still lands, with the
        // already-streamed deltas kept (P1 recover simplification).
        assertEquals(StreamEvent.Done("stop"), events.last())
    }

    @Test
    fun responseFailedIsFatal() {
        val mapper = ResponsesMapper()
        val events = mapper.mapPayload(
            """{"type":"response.failed","response":{"error":{"message":"invalid_api_key"}}}""",
        )
        val failed = events.filterIsInstance<StreamEvent.Failed>()
        assertEquals(1, failed.size)
        assertFalse(failed[0].retryable)
        assertTrue(mapper.terminalReached)
    }

    @Test
    fun responseIncompleteIsFatal() {
        val mapper = ResponsesMapper()
        val events = mapper.mapPayload(
            """{"type":"response.incomplete","response":{"incomplete_details":"max_output_tokens"}}""",
        )
        val failed = events.filterIsInstance<StreamEvent.Failed>()
        assertEquals(1, failed.size)
        assertFalse(failed[0].retryable)
    }

    @Test
    fun truncatedStreamFailsRetryable() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"half"}""",
        )
        val terminal = mapper.finish().last()
        assertEquals(StreamEvent.Failed("SSE_TRUNCATED", retryable = true), terminal)
    }

    @Test
    fun emptyOutputTextDeltaFrameYieldsNoEvent() {
        // Empty-delta suppression (parity with ChatCompletions): an empty
        // frame yields NO TextDelta — an empty increment must never pose as
        // final-answer evidence downstream (continuation completeness,
        // maintainer P1). A following non-empty frame still lands (the guard
        // only mutes the empty frame, not the arm).
        val mapper = ResponsesMapper()
        assertEquals(
            emptyList<StreamEvent>(),
            mapper.mapPayload(
                """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":""}""",
            ),
        )
        val real = mapper.mapPayload(
            """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"real"}""",
        )
        assertEquals(listOf(StreamEvent.TextDelta(0, 0, "real")), real)
    }

    @Test
    fun emptyReasoningDeltaFrameYieldsNoEvent() {
        // Same suppression on the reasoning arm: an empty reasoning frame is
        // dropped (zero payload must not flip the downstream reasoning-source
        // flag either).
        val mapper = ResponsesMapper()
        assertEquals(
            emptyList<StreamEvent>(),
            mapper.mapPayload(
                """{"type":"response.reasoning_summary_text.delta","output_index":1,"content_index":0,"delta":""}""",
            ),
        )
        val real = mapper.mapPayload(
            """{"type":"response.reasoning_summary_text.delta","output_index":1,"content_index":0,"delta":"summarized"}""",
        )
        assertEquals(1, real.size)
        assertEquals(StreamEvent.ReasoningDelta(0, 1000, "summarized"), real.single())
    }

    @Test
    fun opaqueFieldsNeverSurfaceAsEvents() {
        val events = replayFixture()
        for (e in events) {
            assertFalse("opaque leak: $e", e.toString().contains("OPAQUE-SECRET-XYZ"))
        }
    }

    @Test
    fun reasoningOutputItemMarksPresenceWithoutDelta() {
        val mapper = ResponsesMapper()
        val added = mapper.mapPayload(
            """{"type":"response.output_item.added","output_index":1,"item_id":"item_rs1","item":{"type":"reasoning","id":"rs_1"}}""",
        )
        assertEquals(listOf(StreamEvent.ReasoningPresent), added)
        val done = mapper.mapPayload(
            """{"type":"response.output_item.done","output_index":1,"item_id":"item_rs1","item":{"type":"reasoning","id":"rs_1"}}""",
        )
        assertEquals(listOf(StreamEvent.ReasoningPresent), done)
    }

    @Test
    fun reasoningPresenceMarkerCarriesNoPayload() {
        val mapper = ResponsesMapper()
        val events = mapper.mapPayload(
            """{"type":"response.output_item.added","output_index":1,"item_id":"item_rs1","item":{"type":"reasoning","id":"rs_1","summary":[],"content":[]}}""",
        )
        assertEquals(1, events.size)
        assertTrue(events.single() is StreamEvent.ReasoningPresent)
    }

    @Test
    fun functionCallItemsDoNotMarkReasoning() {
        val mapper = ResponsesMapper()
        val added = mapper.mapPayload(
            """{"type":"response.output_item.added","output_index":2,"item_id":"item_wx8f","item":{"type":"function_call","id":"","call_id":"call_x","name":"get_weather"}}""",
        )
        assertTrue(added.none { it is StreamEvent.ReasoningPresent })
    }

    @Test
    fun requestBodyIsUnstoredStreamWithoutPreviousResponse() {
        val provider = ResponsesProvider(testConfig(), { null })
        val body = provider.buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage("user", "hi")),
            ),
        )
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"store\":false"))
        assertFalse(body.contains("previous_response_id"))
        assertTrue(body.contains("\"type\":\"message\""))
    }

    @Test
    fun endpointJoining() {
        val provider = ResponsesProvider(testConfig(), { null })
        assertEquals("https://h/v1/responses", provider.endpoint("https://h/v1"))
        assertEquals("https://h/v1/responses", provider.endpoint("https://h/v1/"))
        assertEquals("https://h/v1/responses", provider.endpoint("https://h/v1/responses"))
    }
}
