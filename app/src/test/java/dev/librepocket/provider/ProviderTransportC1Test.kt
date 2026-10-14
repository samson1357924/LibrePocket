package dev.librepocket.provider

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase C1 transport-budget regressions (provider layer only; no
 * TurnController/ChatViewModel/Session/Setup).
 *
 * - SSE single-line hard limit: one >1MiB `data:` line aborts the stream as
 *   typed non-retryable `SSE_LINE_TOO_LONG` / `AGG_TOO_LARGE` (no unbounded
 *   accumulation, no `.take()`-style read-all-then-truncate).
 * - Aggregate cumulative hard limit end-to-end: a hostile stream of small
 *   tool fragments trips `TOOL_ARGS_TOO_LARGE` / `AGG_TOO_LARGE` without
 *   emitting a poisoned `ToolDone`.
 * - 401 / missing-key never retries at the transport layer (single wire
 *   request, fatal). 503 single-attempt behavior is already covered by
 *   [ProviderTransportTest] (`stream503WithRetryAfterZero*`,
 *   `listModels503WithRetryAfterZero*`, `sseHttpErrorPrefixIsBounded*`);
 *   controller-level retries live in `TurnControllerTest` and are
 *   deliberately untouched here.
 * - Anthropic non-tool block flood: `content_block_start` with distinct
 *   non-tool indexes must trip the same distinct-index budget (RED before
 *   the blocks-map cap, GREEN after).
 */
class ProviderTransportC1Test {

    private val fakeKey = "c1-fake-key"

    private fun config(protocol: ProviderProtocol, baseUrl: String) = ProviderConfig(
        id = "c1-provider",
        label = "c1 test",
        baseUrl = baseUrl,
        protocol = protocol,
        apiKeyRef = "fake-key-ref",
        http = ProviderHttpConfig(readTimeoutMs = TimeUnit.MINUTES.toMillis(5)),
    )

    private fun providerFor(protocol: ProviderProtocol, server: MockWebServer): LlmProvider {
        val base = server.url("/").toString().trimEnd('/')
        val cfg = config(protocol, base)
        val key = { fakeKey.toCharArray() }
        return when (protocol) {
            ProviderProtocol.CHAT_COMPLETIONS -> ChatCompletionsProvider(cfg, key)
            ProviderProtocol.RESPONSES -> ResponsesProvider(cfg, key)
            ProviderProtocol.ANTHROPIC -> AnthropicProvider(cfg, key)
        }
    }

    private fun request() = ChatRequest(
        model = "c1-model",
        messages = listOf(ChatMessage("user", "hi")),
    )

    @Test
    fun sseSingleOverlongLineAbortsStreamTyped() = runBlocking {
        for (protocol in ProviderProtocol.values()) {
            val server = MockWebServer()
            server.start()
            try {
                val body = "data: " + "x".repeat(SseFrameParser.MAX_LINE_BYTES + 1024) + "\n\n"
                server.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(body),
                )
                val events = withTimeout(10_000) {
                    providerFor(protocol, server).stream(request()).toList()
                }
                val failure = events.last() as StreamEvent.Failed
                assertTrue(
                    "$protocol overlong line must fail typed, got: ${failure.message}",
                    failure.message.contains("SSE_LINE_TOO_LONG"),
                )
                assertFalse("$protocol line overflow must not retry the poisoned stream", failure.retryable)
                assertEquals(
                    "$protocol line overflow carries typed code",
                    ProviderFailureCode.AGG_TOO_LARGE.name,
                    failure.code,
                )
                assertEquals("$protocol overlong line remains one request", 1, server.requestCount)
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun toolAggregateOverflowViaStreamFailsTypedWithoutPoisonedDone() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val frag = "y".repeat(4_000)
            val body = buildString {
                repeat(120) {
                    append("data: ")
                    append("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"$frag"}}]}}]}""")
                    append("\n\n")
                }
            }
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(body),
            )
            val cfg = config(
                ProviderProtocol.CHAT_COMPLETIONS,
                server.url("/").toString().trimEnd('/'),
            )
            val provider = ChatCompletionsProvider(cfg, { fakeKey.toCharArray() })
            val events = withTimeout(10_000) {
                provider.stream(ChatRequest(model = "m", messages = listOf(ChatMessage("user", "hi")))).toList()
            }
            val failure = events.last() as StreamEvent.Failed
            assertTrue(
                "tool aggregate overflow must fail typed, got: ${failure.message}",
                failure.message.contains(TOOL_ARGS_TOO_LARGE),
            )
            assertFalse("aggregate overflow must not retry the poisoned stream", failure.retryable)
            assertEquals(
                ProviderFailureCode.AGG_TOO_LARGE.name,
                failure.code,
            )
            assertTrue(
                "poisoned fragment must never become a ToolDone",
                events.none { it is StreamEvent.ToolDone },
            )
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun http401StreamIsSingleFatalAttempt() = runBlocking {
        for (protocol in ProviderProtocol.values()) {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(401)
                        .setBody("""{"error":{"message":"invalid_api_key: check your key"}}"""),
                )
                // Second response proves no transport-level retry: it must stay queued.
                server.enqueue(MockResponse().setResponseCode(200).setBody("accepted"))
                val events = withTimeout(5_000) {
                    providerFor(protocol, server).stream(request()).toList()
                }
                val failure = events.single() as StreamEvent.Failed
                assertFalse("$protocol HTTP 401 must not retry", failure.retryable)
                assertTrue("$protocol preserves HTTP status, got: ${failure.message}", failure.message.contains("HTTP 401"))
                assertEquals("$protocol 401 remains one provider attempt", 1, server.requestCount)
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun http401ListModelsIsSingleFatalThrow() = runBlocking {
        for (protocol in ProviderProtocol.values()) {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(401)
                        .setBody("""{"error":{"message":"unauthorized"}}"""),
                )
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
                try {
                    providerFor(protocol, server).listModels()
                    fail("$protocol HTTP 401 listModels should throw")
                } catch (expected: ProviderFailure) {
                    assertFalse("$protocol HTTP 401 must not retry", expected.retryable)
                    assertTrue(
                        "$protocol preserves HTTP status, got: ${expected.message}",
                        expected.message.orEmpty().contains("HTTP 401"),
                    )
                }
                assertEquals("$protocol 401 listModels remains one request", 1, server.requestCount)
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun anthropicManyDistinctTextBlocksTripBlockCapTyped() {
        val mapper = AnthropicMapper()
        for (i in 0 until MAX_TOOL_CALLS_PER_ATTEMPT) {
            mapper.mapPayload(
                """{"type":"content_block_start","index":$i,"content_block":{"type":"text","text":""}}""",
            )
        }
        try {
            mapper.mapPayload(
                """{"type":"content_block_start","index":$MAX_TOOL_CALLS_PER_ATTEMPT,"content_block":{"type":"text","text":""}}""",
            )
            fail("expected 65th distinct Anthropic block to trip the distinct-index budget")
        } catch (e: ProviderFailure) {
            assertEquals(TOOL_ARGS_TOO_LARGE, e.message)
            assertFalse(e.retryable)
            assertEquals(ProviderFailureCode.AGG_TOO_LARGE, e.code)
        }
    }
}
