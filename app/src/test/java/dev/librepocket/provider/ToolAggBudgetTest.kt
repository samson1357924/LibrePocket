package dev.librepocket.provider

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Aggregation-budget regressions (#16): every ToolDelta→ToolDone aggregator
 * shares one per-call memory budget ([MAX_TOOL_CALL_AGG_BYTES]); exceeding it
 * is a typed non-retryable failure ([TOOL_ARGS_TOO_LARGE]), distinct from
 * malformed payloads (ignored) and clean-EOF truncation (`SSE_TRUNCATED`,
 * retryable). Nothing is silently truncated.
 *
 * Boundary math: the char→byte estimate is ×3, so with a 1 MiB budget
 * 349525 chars (×3 = 1048575) still fit while one more char overflows.
 */
class ToolAggBudgetTest {

    private fun toolFailureOf(block: () -> Unit): ProviderFailure {
        try {
            block()
        } catch (e: ProviderFailure) {
            return e
        }
        fail("expected typed TOOL_ARGS_TOO_LARGE ProviderFailure")
        error("unreachable")
    }

    @Test
    fun requireToolAggBudgetExactBoundary() {
        requireToolAggBudget(349_525, 0)
        requireToolAggBudget(0, 0)
        val over = toolFailureOf { requireToolAggBudget(349_525, 1) }
        assertEquals(TOOL_ARGS_TOO_LARGE, over.message)
        assertFalse(over.retryable)
        assertEquals(ProviderFailureCode.AGG_TOO_LARGE, over.code)
    }

    @Test
    fun chatCompletionsSingleFragmentOverBudgetThrowsTyped() {
        val mapper = ChatCompletionsMapper()
        val frag = "y".repeat(400_000)
        val payload = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"f","arguments":"$frag"}}]}}]}"""
        val failure = toolFailureOf { mapper.mapPayload(payload) }
        assertEquals(TOOL_ARGS_TOO_LARGE, failure.message)
        assertFalse("aggregation overflow must not retry the poisoned stream", failure.retryable)
        assertEquals(ProviderFailureCode.AGG_TOO_LARGE, failure.code)
    }

    @Test
    fun chatCompletionsManySmallFragmentsTripAggregate() {
        val mapper = ChatCompletionsMapper()
        val frag = "y".repeat(4_000)
        var threw = false
        repeat(120) {
            val payload = """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"$frag"}}]}}]}"""
            try {
                mapper.mapPayload(payload)
            } catch (e: ProviderFailure) {
                assertEquals(TOOL_ARGS_TOO_LARGE, e.message)
                assertFalse(e.retryable)
                threw = true
                return@repeat
            }
        }
        assertTrue("120 x 4k-char fragments must exceed the aggregate budget", threw)
    }

    @Test
    fun chatCompletionsUnderBudgetAccumulatesUnchanged() {
        val mapper = ChatCompletionsMapper()
        val frag = "y".repeat(1_000)
        repeat(2) {
            val payload = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"f","arguments":"$frag"}}]}}]}"""
            mapper.mapPayload(payload)
        }
        val terminal = mapper.finish()
        val done = terminal.filterIsInstance<StreamEvent.ToolDone>().single()
        assertEquals(2_000, done.argumentsJson.length)
    }

    @Test
    fun responsesDeltaStreamOverBudgetThrowsTyped() {
        val mapper = ResponsesMapper()
        val frag = "x".repeat(4_000)
        var threw = false
        repeat(120) {
            try {
                mapper.mapPayload("""{"type":"response.function_call_arguments.delta","item_id":"it1","delta":"$frag"}""")
            } catch (e: ProviderFailure) {
                assertEquals(TOOL_ARGS_TOO_LARGE, e.message)
                assertFalse(e.retryable)
                assertEquals(ProviderFailureCode.AGG_TOO_LARGE, e.code)
                threw = true
                return@repeat
            }
        }
        assertTrue("120 x 4k-char fragments must exceed the aggregate budget", threw)
    }

    @Test
    fun responsesTerminalArgumentsOverBudgetThrowsTyped() {
        val mapper = ResponsesMapper()
        val big = "x".repeat(400_000)
        val failure = toolFailureOf {
            mapper.mapPayload(
                """{"type":"response.output_item.done","item_id":"it1","item":{"type":"function_call","call_id":"c1","name":"f","arguments":"$big"}}""",
            )
        }
        assertEquals(TOOL_ARGS_TOO_LARGE, failure.message)
        assertFalse(failure.retryable)
    }

    @Test
    fun anthropicInputJsonOverBudgetThrowsTyped() {
        val mapper = AnthropicMapper()
        mapper.mapPayload(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t1","name":"n"}}""",
        )
        val frag = "z".repeat(4_000)
        var threw = false
        repeat(120) {
            try {
                mapper.mapPayload(
                    """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"$frag"}}""",
                )
            } catch (e: ProviderFailure) {
                assertEquals(TOOL_ARGS_TOO_LARGE, e.message)
                assertFalse(e.retryable)
                assertEquals(ProviderFailureCode.AGG_TOO_LARGE, e.code)
                threw = true
                return@repeat
            }
        }
        assertTrue("120 x 4k-char fragments must exceed the aggregate budget", threw)
    }

    @Test
    fun overflowMarkersClassifyAsFatal() {
        assertEquals(
            FailureKind.FATAL,
            ProviderErrorClassifier.classify(null, null, TOOL_ARGS_TOO_LARGE),
        )
        assertEquals(
            FailureKind.FATAL,
            ProviderErrorClassifier.classify(null, null, SseFrameParser.FRAME_TOO_LARGE_MESSAGE),
        )
        assertEquals(
            FailureKind.RETRYABLE,
            ProviderErrorClassifier.classify(null, null, "SSE_TRUNCATED"),
        )
    }

    @Test
    fun stackedSmallLinesAbortStreamWithTypedFrameFailure() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            // 300 individually-legal (~4 KiB) data lines in ONE event: no line
            // trips the per-line cap, but the joined payload (~1.2 M chars,
            // ~3.6 M estimated bytes) must trip the per-event total.
            val line = "data: " + "q".repeat(4_096) + "\n"
            val body = buildString {
                repeat(300) { append(line) }
                append("\n")
            }
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(body),
            )
            val config = ProviderConfig(
                id = "agg-budget",
                label = "test",
                baseUrl = server.url("/").toString().trimEnd('/'),
                protocol = ProviderProtocol.CHAT_COMPLETIONS,
                apiKeyRef = "fake",
                http = ProviderHttpConfig(readTimeoutMs = TimeUnit.MINUTES.toMillis(5)),
            )
            val provider = ChatCompletionsProvider(config, { "fake-key".toCharArray() }, OkHttpClient())
            val events = provider.stream(
                ChatRequest(model = "m", messages = listOf(ChatMessage("user", "hi"))),
            ).toList()
            val failure = events.last() as StreamEvent.Failed
            assertTrue(
                "stacked event must fail typed, got: ${failure.message}",
                failure.message.contains(SseFrameParser.FRAME_TOO_LARGE_MESSAGE),
            )
            assertFalse("frame overflow must not retry the poisoned stream", failure.retryable)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
