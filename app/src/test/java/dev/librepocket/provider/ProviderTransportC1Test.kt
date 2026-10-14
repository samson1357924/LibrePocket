package dev.librepocket.provider

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Read chunk the SSE pump pulls per `source.read`; bounds below derive from it. */
private const val SSE_PUMP_READ_CHUNK_BYTES = 8 * 1024

/** Chunks of slack for the read in flight when a budget latches. */
private const val SSE_READ_IN_FLIGHT_CHUNKS = 4

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
 *   the blocks-map cap, GREEN after). The mapper-level branches are covered
 *   in [ToolAggBudgetTest]; this file locks the end-to-end wire contract
 *   (one typed terminal, no `Done`, one request).
 * - Read-bounded early abort: the line budget must be enforced while the
 *   bytes flow in, not discovered at the end of the body.
 * - Terminal precedence: once the protocol terminal (`[DONE]`, `message_stop`)
 *   is reached, a later over-long line must be swallowed instead of
 *   appending a second terminal event.
 * - Cancellation precedence: cancelling while a partial over-long line is
 *   buffered must stay a cancellation, never a retryable transport failure.
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

    private fun providerFor(
        protocol: ProviderProtocol,
        server: MockWebServer,
        client: OkHttpClient? = null,
    ): LlmProvider {
        val base = server.url("/").toString().trimEnd('/')
        val cfg = config(protocol, base)
        val key = { fakeKey.toCharArray() }
        return when (protocol) {
            ProviderProtocol.CHAT_COMPLETIONS -> ChatCompletionsProvider(cfg, key, client)
            ProviderProtocol.RESPONSES -> ResponsesProvider(cfg, key, client)
            ProviderProtocol.ANTHROPIC -> AnthropicProvider(cfg, key, client)
        }
    }

    /** Same adapters with an always-missing key (see [KeyProvider] semantics). */
    private fun keylessProviderFor(
        protocol: ProviderProtocol,
        server: MockWebServer,
    ): LlmProvider {
        val cfg = config(protocol, server.url("/").toString().trimEnd('/'))
        val key = { null }
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

    /**
     * Counts the bytes the pump actually pulls from the client-side
     * ResponseBody source — the mirror of
     * [ProviderTransportTest]'s `ResponseBodyReadSignals`: the number consumed
     * by the reader, not the number the peer queued.
     */
    private class SseBodyReadSignals {
        val consumedBytes = AtomicLong()
        val readEntered = CountDownLatch(1)

        private val countingInterceptor = object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val response = chain.proceed(chain.request())
                val delegate = response.body ?: return response
                val counted = object : ResponseBody() {
                    private val countedSource: BufferedSource = object : ForwardingSource(delegate.source()) {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            // Entry latch first, so cancellation tests can prove the
                            // Job was inside source.read() and not upstream of it.
                            readEntered.countDown()
                            val read = super.read(sink, byteCount)
                            if (read > 0L) consumedBytes.addAndGet(read)
                            return read
                        }
                    }.buffer()

                    override fun contentType(): MediaType? = delegate.contentType()
                    override fun contentLength(): Long = delegate.contentLength()
                    override fun source(): BufferedSource = countedSource
                }
                return response.newBuilder().body(counted).build()
            }
        }

        fun client(): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(countingInterceptor)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    /**
     * Wait until the pump has pulled at least [thresholdBytes] from the body,
     * so a cancellation assertion cannot race ahead of the read loop.
     */
    private suspend fun awaitConsumedBytes(signals: SseBodyReadSignals, thresholdBytes: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (signals.consumedBytes.get() >= thresholdBytes) return true
            delay(20)
        }
        return false
    }

    /**
     * One >1MiB `data:` line, end to end for every protocol. Besides the typed
     * terminal this locks the EARLY abort: the body is a single newline-free
     * giant line, so the latch is taken by the unbuffered `textBuf` tail check
     * at the end of `SseFrameParser.drainLines` — the complete-line check
     * inside `handleLine` cannot fire because the line only completes at EOF.
     * A pump that read the whole ~1.05MiB body first (and only then noticed)
     * would still emit the same terminal, so the bytes actually consumed are
     * bounded to the first chunks plus the chunks it takes to fill the tail
     * buffer to the latch threshold.
     */
    @Test
    fun sseSingleOverlongLineAbortsStreamTyped() = runBlocking {
        // The tail latch needs MAX_LINE_BYTES/3 (+1) chars buffered before it
        // can trip; a read-all implementation consumes the whole body instead.
        val tailLatchChars = (SseFrameParser.MAX_LINE_BYTES / 3 + 1).toLong()
        val earlyReadBudget = tailLatchChars + SSE_READ_IN_FLIGHT_CHUNKS * SSE_PUMP_READ_CHUNK_BYTES
        for (protocol in ProviderProtocol.values()) {
            val server = MockWebServer()
            val signals = SseBodyReadSignals()
            server.start()
            try {
                val body = "data: " + "x".repeat(SseFrameParser.MAX_LINE_BYTES + 1024) + "\n\n"
                server.enqueue(
                    MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(body),
                )
                val events = withTimeout(10_000) {
                    providerFor(protocol, server, signals.client()).stream(request()).toList()
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
                val consumed = signals.consumedBytes.get()
                assertTrue(
                    "$protocol aborts while the line is buffering, consumed=$consumed bytes <= $earlyReadBudget",
                    consumed <= earlyReadBudget,
                )
                assertTrue(
                    "$protocol must not drain the whole ${body.toByteArray(Charsets.UTF_8).size}-byte body, consumed=$consumed bytes",
                    consumed < body.toByteArray(Charsets.UTF_8).size,
                )
            } finally {
                server.shutdown()
            }
        }
    }

    /**
     * End-to-end Anthropic twin of the mapper-level
     * [ToolAggBudgetTest.anthropicThinkingBlockFloodTripsDistinctCapTyped]:
     * 65 distinct `content_block_start` indexes must surface as one typed,
     * non-retryable `TOOL_ARGS_TOO_LARGE` / `AGG_TOO_LARGE` terminal, with no
     * `Done` and a single wire request.
     */
    @Test
    fun anthropicStreamSixtyFiveDistinctBlocksFailsTypedWithoutDone() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val body = buildString {
                for (i in 0..MAX_TOOL_CALLS_PER_ATTEMPT) {
                    append("""data: {"type":"content_block_start","index":$i,"content_block":{"type":"text","text":""}}""")
                    append("\n\n")
                }
                append("data: {\"type\":\"message_stop\"}\n\n")
            }
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(body),
            )
            val events = withTimeout(10_000) {
                providerFor(ProviderProtocol.ANTHROPIC, server).stream(request()).toList()
            }
            val failures = events.filterIsInstance<StreamEvent.Failed>()
            assertEquals("exactly one terminal failure", 1, failures.size)
            val failure = failures.single()
            assertTrue(
                "Anthropic block flood fails typed, got: ${failure.message}",
                failure.message.contains(TOOL_ARGS_TOO_LARGE),
            )
            assertFalse("block flood must not retry the poisoned stream", failure.retryable)
            assertEquals(
                ProviderFailureCode.AGG_TOO_LARGE.name,
                failure.code,
            )
            assertTrue("a poisoned attempt never reports success", events.none { it is StreamEvent.Done })
            assertEquals("poisoned attempt remains one request", 1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    /**
     * End-to-end positive control for the same budget: a legal fan-out of 64
     * text blocks, each properly closed plus `message_delta`/`message_stop`,
     * still projects one `StreamEvent.Done` on a single request — the cap
     * bounds the map, it does not kill legitimate streams.
     */
    @Test
    fun anthropicStreamSixtyFourBlocksCloseIntoDone() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val body = buildString {
                for (i in 0 until MAX_TOOL_CALLS_PER_ATTEMPT) {
                    append("""data: {"type":"content_block_start","index":$i,"content_block":{"type":"text","text":""}}""")
                    append("\n\n")
                }
                for (i in 0 until MAX_TOOL_CALLS_PER_ATTEMPT) {
                    append("""data: {"type":"content_block_stop","index":$i}""")
                    append("\n\n")
                }
                append("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n")
                append("data: {\"type\":\"message_stop\"}\n\n")
            }
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(body),
            )
            val events = withTimeout(10_000) {
                providerFor(ProviderProtocol.ANTHROPIC, server).stream(request()).toList()
            }
            assertEquals(
                "a legal 64-block fan-out still closes cleanly",
                listOf(StreamEvent.Done("end_turn")),
                events,
            )
            assertEquals("clean close remains one request", 1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    /**
     * Terminal precedence over the line budget: the protocol terminal
     * (`[DONE]`) arrives first, so the pump stops reading the body and the
     * over-long tail that follows on the wire never becomes a second
     * terminal. Only one `StreamEvent.Done`, no `StreamEvent.Failed`, one
     * request.
     *
     * Scope note: in this shape the latch CANNOT fire — reading already
     * stopped after `[DONE]` — so this locks the provider-side STOP contract
     * (a mapper that stops returning `SsePumpDecision.STOP` for its protocol
     * terminal keeps draining and appends a typed line-overflow terminal
     * after the `Done`). The pump-internal stop-reading control flow itself is
     * locked by [ProviderTransportTest.eachProtocolTerminalClosesBodyWithoutAnotherRead],
     * and the `!protocolTerminalReached` suppression guard is
     * defense-in-depth for shapes the parser cannot produce; the EOF-flush
     * latch path that reaches the post-loop guard is locked by
     * [unterminatedMultibyteTailLineLatchesAtEofFailsTyped].
     */
    @Test
    fun chatCompletionsDoneBeforeOverlongLineKeepsSingleTerminal() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val body = buildString {
                append("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n")
                append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
                append("data: [DONE]\n\n")
                // Trailing over-long line, NEVER READ: the pump stopped after
                // [DONE], so the latch cannot fire and this only locks the
                // provider-side STOP contract (see the KDoc above).
                append("data: ")
                append("x".repeat(SseFrameParser.MAX_LINE_BYTES + 1024))
            }
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(body),
            )
            val events = withTimeout(10_000) {
                providerFor(ProviderProtocol.CHAT_COMPLETIONS, server).stream(request()).toList()
            }
            assertEquals(1, events.count { it is StreamEvent.Done })
            assertTrue("the overlong tail must not append a failure", events.none { it is StreamEvent.Failed })
            assertEquals(StreamEvent.Done("stop"), events.last())
            assertEquals("one terminal is one request", 1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    /**
     * EOF-flush latch path: one newline-free line whose final multi-byte char
     * is split across the last read. Everything decoded before EOF is exactly
     * `MAX_LINE_BYTES / 3` chars — one char UNDER the tail latch — because the
     * incomplete trailing sequence stays buffered by `completeUtf8PrefixLen`.
     * Only `SseFrameParser.flush()` at EOF decodes the held-back byte, crossing
     * the threshold, so `lineTooLong` first appears after the read loop: that is
     * the only shape that reaches `pumpSse`'s POST-LOOP latched check, and the
     * failure must be the typed non-retryable `SSE_LINE_TOO_LONG`, never the
     * retryable `SSE_TRUNCATED` that a missing post-loop check would leave.
     */
    @Test
    fun unterminatedMultibyteTailLineLatchesAtEofFailsTyped() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val prefix = "data: "
            // (MAX_LINE_BYTES / 3 + 1) chars is the first length that trips the
            // x3 estimate; drop one char for the split final multi-byte char.
            val tailChars = SseFrameParser.MAX_LINE_BYTES / 3 + 1 - prefix.length
            val body = Buffer()
                .writeUtf8(prefix)
                .writeUtf8("中".repeat(tailChars - 1))
                .writeByte(0xE4) // lead byte of the final char; completes only at EOF
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(body),
            )
            val events = withTimeout(10_000) {
                providerFor(ProviderProtocol.CHAT_COMPLETIONS, server).stream(request()).toList()
            }
            val failure = events.last() as StreamEvent.Failed
            assertTrue(
                "EOF-latched line overflow fails typed, got: ${failure.message}",
                failure.message.contains("SSE_LINE_TOO_LONG"),
            )
            assertFalse("EOF-latched line overflow must not retry", failure.retryable)
            assertEquals(
                ProviderFailureCode.AGG_TOO_LARGE.name,
                failure.code,
            )
            assertEquals("one latched stream remains one request", 1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    /**
     * Cancellation × budget-latch race: the peer delivers a partial (still
     * under-budget) giant line and then stalls, so the pump sits in
     * `source.read()` with a nearly-latched `textBuf` that the very next bytes
     * would trip. Cancelling there must interrupt the blocking read as a
     * `CancellationException` — never as the retryable `SSE_TRANSPORT`
     * failure `pumpSse`'s IOException catch could raise, and never as the
     * line-too-long terminal the latch would raise.
     */
    @Test
    fun cancellationWhilePartialOverlongLineBuffersEmitsNoTerminal() = runBlocking {
        // "data: " (6) + 340_000 chars => ~1.02MiB estimated: below
        // MAX_LINE_BYTES, so the tail latch has not tripped and the very next
        // bytes would trip it.
        val partialChars = 340_000
        // The peer must stay blocked: an unknown-length (chunked) body whose
        // last period is stalled server-side. A fixed-length body would EOF
        // cleanly at contentLength and emit SSE_TRUNCATED, hiding the race.
        val totalChars = partialChars + 64 * 1024
        val bodyChars = "data: " + "x".repeat(totalChars)
        val server = MockWebServer()
        val signals = SseBodyReadSignals()
        server.start()
        var job: Job? = null
        try {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setChunkedBody(bodyChars, 8 * 1024)
                    .throttleBody(partialChars.toLong(), 60, TimeUnit.SECONDS),
            )
            val events = mutableListOf<StreamEvent>()
            var thrown: Throwable? = null
            val active = launch(Dispatchers.IO) {
                try {
                    events.addAll(
                        providerFor(ProviderProtocol.ANTHROPIC, server, signals.client())
                            .stream(request()).toList(),
                    )
                } catch (e: Throwable) {
                    thrown = e
                }
            }
            job = active
            assertTrue(
                "pump enters the stalled body read",
                withContext(Dispatchers.IO) { signals.readEntered.await(5, TimeUnit.SECONDS) },
            )
            val buffered = awaitConsumedBytes(signals, (partialChars * 9L) / 10L)
            val consumed = signals.consumedBytes.get()
            assertTrue(
                "the partial over-long line reaches the pump before cancellation, consumed=$consumed",
                buffered,
            )
            assertTrue(
                "the remaining bytes stay stalled server-side, consumed=$consumed of ${bodyChars.length}",
                consumed < bodyChars.length,
            )
            withTimeout(10_000) { active.cancelAndJoin() }
            assertTrue("cancelling emits no terminal event, got: $events", events.isEmpty())
            assertTrue(
                "cancelling is not converted into a retryable transport failure, threw: $thrown",
                thrown == null || thrown is CancellationException,
            )
            assertEquals("cancelled stream remains one request", 1, server.requestCount)
        } finally {
            job?.cancel()
            server.shutdown() // Release the throttled peer before the bounded rejoin.
            job?.let { active -> withTimeoutOrNull(5_000) { active.join() } }
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

    /**
     * Missing-key negative control for `listModels`, the twin of
     * [ProviderTransportTest.missingApiKeyIsFatalAndDoesNotOpenAConnection]:
     * the key is resolved before any socket is opened, so the failure is
     * fatal, typed and leaves zero requests on the wire.
     */
    @Test
    fun listModelsMissingApiKeyIsFatalAndDoesNotOpenAConnection() = runBlocking {
        for (protocol in ProviderProtocol.values()) {
            val server = MockWebServer()
            server.start()
            try {
                try {
                    keylessProviderFor(protocol, server).listModels()
                    fail("$protocol missing-key listModels should throw")
                } catch (expected: ProviderFailure) {
                    assertFalse("$protocol missing key is not retryable", expected.retryable)
                    assertTrue(
                        "$protocol names the missing key, got: ${expected.message}",
                        expected.message.orEmpty().contains("missing_api_key"),
                    )
                }
                assertEquals("$protocol missing key opens no connection", 0, server.requestCount)
            } finally {
                server.shutdown()
            }
        }
    }

    /**
     * Original RED case of the blocks-map cap: 65 distinct TEXT block indexes
     * (a legal small SSE line each) must trip the shared distinct-index budget
     * with the same typed non-retryable failure as tool overflows.
     * [ToolAggBudgetTest] adds the discriminating variants (thinking flood,
     * mixed kinds, restart-is-not-admission, demotion).
     */
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
