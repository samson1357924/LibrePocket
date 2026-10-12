package dev.librepocket.provider

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ForwardingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.toList

private const val MODEL_LIST_BODY_LIMIT_BYTES = 1024 * 1024
private const val HTTP_ERROR_BODY_LIMIT_BYTES = 16 * 1024
private const val SOURCE_READ_AHEAD_ALLOWANCE_BYTES = 2 * 8 * 1024

/** Regression coverage for authenticated redirects, cancellation, and SSE terminal reads. */
class ProviderTransportTest {
    private val fakeKey = "stage5-fake-provider-key"
    private val prompt = "LOCAL-PRIVATE-PROMPT"

    private fun config(
        protocol: ProviderProtocol,
        baseUrl: String,
        http: ProviderHttpConfig = ProviderHttpConfig(readTimeoutMs = TimeUnit.MINUTES.toMillis(5)),
    ) = ProviderConfig(
        id = "stage5-provider",
        label = "local test",
        baseUrl = baseUrl,
        protocol = protocol,
        apiKeyRef = "fake-key-ref",
        http = http,
    )

    private fun anthropic(
        server: MockWebServer,
        client: OkHttpClient? = null,
        http: ProviderHttpConfig = ProviderHttpConfig(readTimeoutMs = TimeUnit.MINUTES.toMillis(5)),
    ) = AnthropicProvider(
        config(ProviderProtocol.ANTHROPIC, server.url("/").toString().trimEnd('/'), http),
        { fakeKey.toCharArray() },
        client,
    )

    private fun request() = ChatRequest(
        model = "local-model",
        messages = listOf(ChatMessage("user", prompt)),
    )

    private data class LocalTls(
        val server: HandshakeCertificates,
        val client: HandshakeCertificates,
    )

    private fun localTls(): LocalTls {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        return LocalTls(
            server = HandshakeCertificates.Builder()
                .heldCertificate(certificate)
                .build(),
            client = HandshakeCertificates.Builder()
                .addTrustedCertificate(certificate.certificate)
                .build(),
        )
    }

    private suspend fun assertHttpsRedirectFailsClosed(downgradeToHttp: Boolean) {
        val origin = MockWebServer()
        val target = MockWebServer()
        val tls = localTls()
        origin.useHttps(tls.server.sslSocketFactory(), false)
        if (!downgradeToHttp) target.useHttps(tls.server.sslSocketFactory(), false)
        origin.start()
        target.start()
        try {
            // A benign response ensures a client that follows the redirect fails
            // by the security assertion, not by hanging on an empty local queue.
            target.enqueue(MockResponse().setResponseCode(200).setBody("accepted"))
            origin.enqueue(
                MockResponse()
                    .setResponseCode(307)
                    .addHeader("Location", target.url("/collect").toString()),
            )
            val permissive = OkHttpClient.Builder()
                .sslSocketFactory(tls.client.sslSocketFactory(), tls.client.trustManager)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
            val events = withTimeout(5_000) {
                anthropic(origin, permissive).stream(request()).toList()
            }
            val failure = events.filterIsInstance<StreamEvent.Failed>().single()
            assertFalse("all redirects, including TLS cross-origin/downgrade, fail closed", failure.retryable)

            val received = withContext(Dispatchers.IO) { origin.takeRequest(2, TimeUnit.SECONDS) }
            assertNotNull("HTTPS origin receives the authenticated POST", received)
            assertEquals("POST", received!!.method)
            assertTrue(received.path.orEmpty().endsWith("/v1/messages"))
            assertEquals(fakeKey, received.getHeader("x-api-key"))
            assertTrue(received.body.readUtf8().contains(prompt))
            assertEquals("redirect target receives no request", 0, target.requestCount)
        } finally {
            origin.shutdown()
            target.shutdown()
        }
    }

    @Test
    fun httpsCrossOriginRedirectFailsClosedWithInjectedPermissiveClient() = runBlocking {
        assertHttpsRedirectFailsClosed(downgradeToHttp = false)
    }

    @Test
    fun httpsToHttpDowngradeRedirectFailsClosedWithInjectedPermissiveClient() = runBlocking {
        assertHttpsRedirectFailsClosed(downgradeToHttp = true)
    }

    @Test
    fun anthropicPostRedirectsFailClosedWithInjectedRedirectFollowingClient() = runBlocking {
        for (status in listOf(302, 307, 308)) {
            val origin = MockWebServer()
            val target = MockWebServer()
            origin.start()
            target.start()
            try {
                target.enqueue(MockResponse().setResponseCode(200).setBody("accepted"))
                origin.enqueue(
                    MockResponse()
                        .setResponseCode(status)
                        .addHeader("Location", target.url("/collect").toString()),
                )
                // Deliberately pass a permissive client: the provider transport
                // must apply its own no-redirect policy to injected clients.
                val permissive = OkHttpClient.Builder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()
                val events = withTimeout(3_000) { anthropic(origin, permissive).stream(request()).toList() }
                val failed = events.filterIsInstance<StreamEvent.Failed>().single()
                assertFalse("HTTP $status redirect is terminal/fatal", failed.retryable)

                val received = origin.takeRequest(1, TimeUnit.SECONDS)
                assertNotNull("origin request for HTTP $status", received)
                assertEquals("POST", received!!.method)
                assertEquals(fakeKey, received.getHeader("x-api-key"))
                assertTrue(received.body.readUtf8().contains(prompt))
                assertEquals("redirect target must receive no request", 0, target.requestCount)
            } finally {
                origin.shutdown()
                target.shutdown()
            }
        }
    }

    @Test
    fun sameOriginRedirectAlsoFailsClosedByDocumentedPolicy() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(307)
                    .addHeader("Location", server.url("/same-origin-target").toString()),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("accepted"))
            val events = withTimeout(3_000) { anthropic(server).stream(request()).toList() }
            val failure = events.single() as StreamEvent.Failed
            assertFalse(failure.retryable)
            val received = withContext(Dispatchers.IO) { server.takeRequest(1, TimeUnit.SECONDS) }
            assertNotNull("initial same-origin POST is recorded", received)
            assertEquals("POST", received!!.method)
            assertEquals("/v1/messages", received.path)
            assertEquals(fakeKey, received.getHeader("x-api-key"))
            assertTrue(received.body.readUtf8().contains(prompt))
            assertEquals("same-origin redirects are also rejected", 1, server.requestCount)
            val redirected = withContext(Dispatchers.IO) { server.takeRequest(100, TimeUnit.MILLISECONDS) }
            assertNull("no second same-origin request", redirected)
            assertEquals("only the original POST was received", 1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun anthropicModelListRedirectsFailClosedFor302307308() = runBlocking {
        for (status in listOf(302, 307, 308)) {
            val origin = MockWebServer()
            val target = MockWebServer()
            origin.start()
            target.start()
            try {
                target.enqueue(MockResponse().setResponseCode(200).setBody("accepted"))
                origin.enqueue(
                    MockResponse()
                        .setResponseCode(status)
                        .addHeader("Location", target.url("/collect").toString()),
                )
                try {
                    anthropic(origin, OkHttpClient.Builder().followRedirects(true).build()).listModels()
                    throw AssertionError("expected HTTP $status to fail closed")
                } catch (expected: ProviderFailure) {
                    assertTrue(expected.message.orEmpty().contains("HTTP $status"))
                }
                val received = origin.takeRequest(1, TimeUnit.SECONDS)
                assertNotNull("origin request for HTTP $status", received)
                assertEquals("GET", received!!.method)
                assertEquals(fakeKey, received.getHeader("x-api-key"))
                assertEquals("redirect target must receive no request", 0, target.requestCount)
            } finally {
                origin.shutdown()
                target.shutdown()
            }
        }
    }

    private class CallSignals {
        val headers = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val listener = object : EventListener() {
            override fun responseHeadersEnd(call: Call, response: Response) {
                headers.countDown()
            }

            override fun canceled(call: Call) {
                cancelled.countDown()
            }
        }
    }

    /** Counts bytes consumed at the client ResponseBody source boundary, not bytes the server queued. */
    private class ResponseBodyReadSignals {
        val consumedBytes = AtomicLong()
        val readEntered = CountDownLatch(1)
        val calls = CallSignals()

        private val countingInterceptor = object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val response = chain.proceed(chain.request())
                val delegate = response.body ?: return response
                val counted = object : ResponseBody() {
                    private val countedSource: BufferedSource = object : ForwardingSource(delegate.source()) {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            // Entry latch: count down before the blocking delegate read so
                            // cancellation tests can prove the Job was inside source.read().
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
            .eventListener(calls.listener)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    private fun modelListJsonWithExactBytes(size: Int): String {
        val prefix = "{\"data\":[{\"id\":\"budget-model\"}],\"padding\":\""
        val suffix = "\"}"
        val paddingBytes = size - prefix.toByteArray(Charsets.UTF_8).size - suffix.toByteArray(Charsets.UTF_8).size
        require(paddingBytes >= 0)
        return prefix + "x".repeat(paddingBytes) + suffix
    }

    private fun gzipBody(body: String): Buffer = gzipBytes(body.toByteArray(Charsets.UTF_8))

    private fun gzipBytes(raw: ByteArray): Buffer {
        val compressed = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { gzip -> gzip.write(raw) }
            output.toByteArray()
        }
        return Buffer().write(compressed)
    }

    private suspend fun expectTooLarge(provider: LlmProvider, context: String): ProviderFailure {
        val failure = try {
            provider.listModels()
            throw AssertionError("$context should reject an oversized model-list response")
        } catch (expected: ProviderFailure) {
            expected
        }
        assertFalse("$context TOO_LARGE is non-retryable", failure.retryable)
        assertTrue("$context has a stable TOO_LARGE message", failure.message.orEmpty().contains("TOO_LARGE"))
        return failure
    }

    private fun assertReadWithinBudget(signals: ResponseBodyReadSignals, budget: Int, context: String) {
        val consumed = signals.consumedBytes.get()
        assertTrue(
            "$context client-source bytes $consumed <= $budget + sentinel + read-ahead allowance",
            consumed <= budget.toLong() + 1L + SOURCE_READ_AHEAD_ALLOWANCE_BYTES,
        )
    }

    private suspend fun assertCallCancelled(signals: ResponseBodyReadSignals, context: String) {
        assertTrue(
            "$context cancels the actual OkHttp Call",
            withContext(Dispatchers.IO) { signals.calls.cancelled.await(5, TimeUnit.SECONDS) },
        )
    }

    @Test
    fun cancellationDuringSseExecuteBeforeHeadersImmediatelyCancelsCall() = runBlocking {
        val server = MockWebServer()
        val signals = CallSignals()
        server.start()
        var job: Job? = null
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.MINUTES)
                .eventListener(signals.listener)
                .build()
            val active = launch(Dispatchers.IO) { anthropic(server, client).stream(request()).toList() }
            job = active
            val received = withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) }
            assertNotNull("request reaches server while execute waits for headers", received)
            withTimeout(1_500) { active.cancelAndJoin() }
            assertTrue("OkHttp Call.cancel was invoked", withContext(Dispatchers.IO) { signals.cancelled.await(1, TimeUnit.SECONDS) })
        } finally {
            job?.cancel()
            server.shutdown() // Close the peer first so a baseline blocking execute can unwind.
            job?.let { active -> withTimeoutOrNull(1_500) { active.join() } }
        }
    }

    @Test
    fun cancellationDuringModelListExecuteBeforeHeadersImmediatelyCancelsCall() = runBlocking {
        val server = MockWebServer()
        val signals = CallSignals()
        server.start()
        var job: Job? = null
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.MINUTES)
                .eventListener(signals.listener)
                .build()
            val active = launch(Dispatchers.IO) {
                try {
                    anthropic(server, client).listModels()
                } catch (_: CancellationException) {
                    // Expected cancellation is not converted into a retryable failure.
                }
            }
            job = active
            val received = withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) }
            assertNotNull("model request reaches server while execute waits for headers", received)
            withTimeout(1_500) { active.cancelAndJoin() }
            assertTrue("OkHttp Call.cancel was invoked", withContext(Dispatchers.IO) { signals.cancelled.await(1, TimeUnit.SECONDS) })
        } finally {
            job?.cancel()
            server.shutdown() // Close the peer first so a baseline blocking execute can unwind.
            job?.let { active -> withTimeoutOrNull(1_500) { active.join() } }
        }
    }

    @Test
    fun cancellationAfterSseHeadersImmediatelyCancelsBlockingBodyRead() = runBlocking {
        val server = MockWebServer()
        val signals = CallSignals()
        server.start()
        var job: Job? = null
        try {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: body-never-arrives\n\n")
                    .setBodyDelay(10, TimeUnit.SECONDS),
            )
            val client = OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.MINUTES)
                .eventListener(signals.listener)
                .build()
            val provider = anthropic(server, client)
            val active = launch(Dispatchers.IO) { provider.stream(request()).toList() }
            job = active
            assertTrue("response headers arrive before body delay", withContext(Dispatchers.IO) { signals.headers.await(3, TimeUnit.SECONDS) })
            withTimeout(1_500) { active.cancelAndJoin() }
            assertTrue("OkHttp Call.cancel was invoked", withContext(Dispatchers.IO) { signals.cancelled.await(1, TimeUnit.SECONDS) })
            assertEquals(1, server.requestCount)
        } finally {
            job?.cancel()
            server.shutdown() // Close/release the delayed body before bounded joining.
            job?.let { active -> withTimeoutOrNull(1_500) { active.join() } }
        }
    }

    @Test
    fun cancellationAfterModelListHeadersImmediatelyCancelsBlockingBodyRead() = runBlocking {
        val server = MockWebServer()
        val signals = CallSignals()
        server.start()
        var job: Job? = null
        try {
            server.enqueue(
                MockResponse()
                    .setBody("{\"data\":[]}")
                    .setBodyDelay(10, TimeUnit.SECONDS),
            )
            val client = OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.MINUTES)
                .eventListener(signals.listener)
                .build()
            val active = launch(Dispatchers.IO) {
                try {
                    anthropic(server, client).listModels()
                } catch (_: CancellationException) {
                    // Expected: cancellation remains cancellation, not a provider failure.
                }
            }
            job = active
            assertTrue("model response headers arrive before body delay", withContext(Dispatchers.IO) { signals.headers.await(3, TimeUnit.SECONDS) })
            withTimeout(1_500) { active.cancelAndJoin() }
            assertTrue("OkHttp Call.cancel was invoked", withContext(Dispatchers.IO) { signals.cancelled.await(1, TimeUnit.SECONDS) })
            assertEquals(1, server.requestCount)
        } finally {
            job?.cancel()
            server.shutdown() // Close/release the delayed body before bounded joining.
            job?.let { active -> withTimeoutOrNull(1_500) { active.join() } }
        }
    }

    private class OpenAfterPayloadBody(
        payload: String,
        private val release: CountDownLatch,
    ) : ResponseBody() {
        private val payloadBuffer = Buffer().writeUtf8(payload)
        val readCalls = AtomicInteger()
        val closed = CountDownLatch(1)
        private val buffered = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (byteCount == 0L) return 0L
                readCalls.incrementAndGet()
                if (payloadBuffer.size > 0L) return payloadBuffer.read(sink, byteCount)
                // A broken pump asks for another byte after a protocol terminal.
                release.await()
                return -1
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() {
                closed.countDown()
                release.countDown()
            }
        }.buffer()

        override fun contentType(): MediaType? = "text/event-stream".toMediaType()
        override fun contentLength(): Long = -1L
        override fun source(): BufferedSource = buffered
    }

    @Test
    fun eachProtocolTerminalClosesBodyWithoutAnotherRead() = runBlocking {
        fun ssePayloads(vararg payloads: String): String =
            payloads.joinToString(separator = "\n\n", postfix = "\n\n") { "data: $it" }

        val cases = listOf(
            Triple(
                ProviderProtocol.CHAT_COMPLETIONS,
                ssePayloads(
                    """{"choices":[{"delta":{"content":"ok"}}]}""",
                    """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                    "[DONE]",
                    """{"choices":[{"delta":{"content":"AFTER-TERMINAL"}}]}""",
                ),
                "chat/completions",
            ),
            Triple(
                ProviderProtocol.RESPONSES,
                ssePayloads(
                    """{"type":"response.output_text.delta","delta":"ok","output_index":0,"content_index":0}""",
                    """{"type":"response.completed","response":{"status":"completed","output":[]}}""",
                    """{"type":"response.output_text.delta","delta":"AFTER-TERMINAL"}""",
                ),
                "responses",
            ),
            Triple(
                ProviderProtocol.ANTHROPIC,
                ssePayloads(
                    """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""",
                    """{"type":"content_block_stop","index":0}""",
                    """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
                    """{"type":"message_stop"}""",
                    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"AFTER-TERMINAL"}}""",
                ),
                "v1/messages",
            ),
        )

        for ((protocol, payload, route) in cases) {
            val server = MockWebServer()
            val release = CountDownLatch(1)
            val blockingBody = OpenAfterPayloadBody(payload, release)
            server.start()
            try {
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream"))
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    response.newBuilder().body(blockingBody).build()
                }.readTimeout(5, TimeUnit.MINUTES).build()
                val base = server.url("/").toString().trimEnd('/')
                val provider: LlmProvider = when (protocol) {
                    ProviderProtocol.CHAT_COMPLETIONS -> ChatCompletionsProvider(
                        config(protocol, base), { fakeKey.toCharArray() }, client,
                    )
                    ProviderProtocol.RESPONSES -> ResponsesProvider(
                        config(protocol, base), { fakeKey.toCharArray() }, client,
                    )
                    ProviderProtocol.ANTHROPIC -> AnthropicProvider(
                        config(protocol, base), { fakeKey.toCharArray() }, client,
                    )
                }
                var events: List<StreamEvent> = emptyList()
                val collection = launch { events = provider.stream(request()).toList() }
                val completedBeforeAnotherRead = withTimeoutOrNull(2_000) {
                    collection.join()
                    true
                } ?: false
                if (!completedBeforeAnotherRead) release.countDown()
                collection.join()
                assertTrue("$protocol stops before the peer's five-minute read timeout", completedBeforeAnotherRead)
                assertTrue("$protocol produces a healthy terminal fixture", events.last() is StreamEvent.Done)
                assertEquals(
                    "$protocol emits exactly one terminal event and nothing after it",
                    1,
                    events.count { it is StreamEvent.Done || it is StreamEvent.Failed },
                )
                assertTrue("$protocol does not surface post-terminal bytes", events.none {
                    it is StreamEvent.TextDelta && it.delta.contains("AFTER-TERMINAL")
                })
                assertEquals("$protocol reads only the payload once", 1, blockingBody.readCalls.get())
                assertTrue("$protocol closes the still-open response body", blockingBody.closed.await(1, TimeUnit.SECONDS))
                assertEquals("$protocol sends one actual request", 1, server.requestCount)
                val recorded = server.takeRequest(1, TimeUnit.SECONDS)
                assertNotNull(recorded)
                assertTrue(recorded!!.path.orEmpty().endsWith(route))
            } finally {
                release.countDown()
                server.shutdown()
            }
        }
    }

    @Test
    fun missingApiKeyIsFatalAndDoesNotOpenAConnection() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val provider = AnthropicProvider(
                config(ProviderProtocol.ANTHROPIC, server.url("/").toString().trimEnd('/')),
                { null },
            )
            val events = withTimeout(3_000) { provider.stream(request()).toList() }
            val failure = events.single() as StreamEvent.Failed
            assertFalse(failure.retryable)
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun providerEmitsOneFailureForRetryableHttpResponse() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            repeat(4) { server.enqueue(MockResponse().setResponseCode(503).setBody("temporarily unavailable")) }
            val legacyRetries = ProviderHttpConfig(
                maxRetries = 3,
                retryDelaysMs = listOf(0L, 0L, 0L),
                readTimeoutMs = TimeUnit.MINUTES.toMillis(5),
            )
            val events = withTimeout(3_000) { anthropic(server, http = legacyRetries).stream(request()).toList() }
            assertEquals(StreamEvent.Failed("HTTP 503 temporarily unavailable", retryable = true), events.single())
            assertEquals("one provider collection is one request, regardless of legacy config fields", 1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    private fun providerFor(protocol: ProviderProtocol, server: MockWebServer, client: OkHttpClient? = null): LlmProvider {
        val base = server.url("/").toString().trimEnd('/')
        val cfg = config(protocol, base)
        return when (protocol) {
            ProviderProtocol.ANTHROPIC -> AnthropicProvider(cfg, { fakeKey.toCharArray() }, client)
            ProviderProtocol.CHAT_COMPLETIONS -> ChatCompletionsProvider(cfg, { fakeKey.toCharArray() }, client)
            ProviderProtocol.RESPONSES -> ResponsesProvider(cfg, { fakeKey.toCharArray() }, client)
        }
    }

    @Test
    fun modelListAtExactlyOneMiBAcceptsValidJsonForEveryProtocol() = runBlocking {
        val body = modelListJsonWithExactBytes(MODEL_LIST_BODY_LIMIT_BYTES)
        assertEquals(MODEL_LIST_BODY_LIMIT_BYTES, body.toByteArray(Charsets.UTF_8).size)
        for (protocol in ProviderProtocol.values()) {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setBody(body))
                assertEquals(listOf("budget-model"), providerFor(protocol, server).listModels())
                assertEquals("$protocol uses one model-list request", 1, server.requestCount)
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun modelListChunkedAtExactlyOneMiBAcceptsValidJsonForEveryProtocol() = runBlocking {
        // Unknown-length twin of the exact-cap success: the same 1MiB JSON over
        // chunked encoding must pass via the one-byte sentinel EOF probe.
        val body = modelListJsonWithExactBytes(MODEL_LIST_BODY_LIMIT_BYTES)
        assertEquals(MODEL_LIST_BODY_LIMIT_BYTES, body.toByteArray(Charsets.UTF_8).size)
        for (protocol in ProviderProtocol.values()) {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setChunkedBody(body, 1024))
                assertEquals(listOf("budget-model"), providerFor(protocol, server).listModels())
                assertEquals("$protocol chunked exact-cap uses one model-list request", 1, server.requestCount)
            } finally {
                server.shutdown()
            }
        }
    }

    // Added after baseline-red: the pristine test suite cannot reference this new typed API.
    @Test
    fun modelListOverflowCarriesTypedTooLargeCode() = runBlocking {
        val server = MockWebServer()
        val signals = ResponseBodyReadSignals()
        server.start()
        try {
            server.enqueue(
                MockResponse().setBody(
                    modelListJsonWithExactBytes(MODEL_LIST_BODY_LIMIT_BYTES + 1),
                ),
            )
            val failure = expectTooLarge(
                providerFor(ProviderProtocol.CHAT_COMPLETIONS, server, signals.client()),
                "typed model-list overflow",
            )
            assertEquals(ProviderFailureCode.TOO_LARGE, failure.code)
            assertCallCancelled(signals, "typed model-list overflow")
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun modelListKnownAndChunkedUnknownBodiesOverOneMiBFailAndStopReading() = runBlocking {
        val oversized = modelListJsonWithExactBytes(MODEL_LIST_BODY_LIMIT_BYTES + 64 * 1024)
        for (protocol in ProviderProtocol.values()) {
            for (unknownLength in listOf(false, true)) {
                val server = MockWebServer()
                val signals = ResponseBodyReadSignals()
                server.start()
                try {
                    val response = if (unknownLength) {
                        MockResponse().setChunkedBody(oversized, 1024)
                    } else {
                        MockResponse().setBody(oversized)
                    }
                    server.enqueue(response)
                    val kind = if (unknownLength) "chunked" else "known-length"
                    val overflow = expectTooLarge(
                        providerFor(protocol, server, signals.client()),
                        "$protocol $kind model list",
                    )
                    assertEquals("$protocol $kind overflow carries typed code", ProviderFailureCode.TOO_LARGE, overflow.code)
                    assertCallCancelled(signals, "$protocol $kind model-list overflow")
                    assertReadWithinBudget(signals, MODEL_LIST_BODY_LIMIT_BYTES, "$protocol $kind model list")
                    if (unknownLength) {
                        assertTrue(
                        "$protocol unknown-length path reads through the cap before rejecting",
                            signals.consumedBytes.get() >= MODEL_LIST_BODY_LIMIT_BYTES.toLong(),
                        )
                    }
                    assertEquals("$protocol $kind overflow remains one request", 1, server.requestCount)
                } finally {
                    server.shutdown()
                }
            }
        }
    }

    @Test
    fun gzipModelListBudgetCountsDecodedBytes() = runBlocking {
        val server = MockWebServer()
        val signals = ResponseBodyReadSignals()
        server.start()
        try {
            val small = modelListJsonWithExactBytes(512)
            val oversized = modelListJsonWithExactBytes(MODEL_LIST_BODY_LIMIT_BYTES + 64 * 1024)
            val compressedSmall = gzipBody(small)
            val compressedOversized = gzipBody(oversized)
            assertTrue("compressed fixture is below the decoded-byte budget", compressedOversized.size < MODEL_LIST_BODY_LIMIT_BYTES.toLong())
            server.enqueue(
                MockResponse()
                    .addHeader("Content-Encoding", "gzip")
                    .setBody(compressedSmall),
            )
            server.enqueue(
                MockResponse()
                    .addHeader("Content-Encoding", "gzip")
                    .setBody(compressedOversized),
            )
            val provider = providerFor(ProviderProtocol.CHAT_COMPLETIONS, server, signals.client())
            assertEquals(listOf("budget-model"), provider.listModels())
            signals.consumedBytes.set(0L)
            expectTooLarge(provider, "gzip model list counted after decoding")
            assertCallCancelled(signals, "gzip model-list overflow")
            assertReadWithinBudget(signals, MODEL_LIST_BODY_LIMIT_BYTES, "gzip model list")
            assertEquals("gzip cases use one request each", 2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun modelListErrorPrefixIsBoundedAndStillClassifiesLaterFatalMarker() = runBlocking {
        val marker = "insufficient_quota"
        val markerOffset = 4096
        val errorBody = "x".repeat(markerOffset) + marker + "y".repeat(64 * 1024)
        for (protocol in ProviderProtocol.values()) {
            val server = MockWebServer()
            val signals = ResponseBodyReadSignals()
            server.start()
            try {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(503)
                        .addHeader("Retry-After", "0")
                        .setChunkedBody(errorBody, 1024),
                )
                val failure = try {
                    providerFor(protocol, server, signals.client()).listModels()
                    throw AssertionError("$protocol large HTTP error should fail")
                } catch (expected: ProviderFailure) {
                    expected
                }
                assertFalse("$protocol marker within the bounded prefix keeps fatal precedence", failure.retryable)
                assertTrue("$protocol preserves HTTP status in the error", failure.message.orEmpty().contains("HTTP 503"))
                assertTrue(
                    "$protocol reads far enough to observe a marker after the display snippet",
                    signals.consumedBytes.get() >= (markerOffset + marker.length).toLong(),
                )
                assertReadWithinBudget(signals, HTTP_ERROR_BODY_LIMIT_BYTES, "$protocol marked HTTP error")
                assertCallCancelled(signals, "$protocol HTTP error-prefix cap")
                assertEquals("$protocol error body does not cause a follow-up", 1, server.requestCount)
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun fatalMarkerBeyondErrorPrefixFallsBackToHttpStatusClassification() = runBlocking {
        val errorBody = "x".repeat(
            HTTP_ERROR_BODY_LIMIT_BYTES + SOURCE_READ_AHEAD_ALLOWANCE_BYTES + 4096,
        ) + "insufficient_quota" + "y".repeat(32 * 1024)
        for (protocol in ProviderProtocol.values()) {
            val server = MockWebServer()
            val signals = ResponseBodyReadSignals()
            server.start()
            try {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(503)
                        .addHeader("Retry-After", "0")
                        .setChunkedBody(errorBody, 1024),
                )
                val failure = try {
                    providerFor(protocol, server, signals.client()).listModels()
                    throw AssertionError("$protocol large HTTP error should fail")
                } catch (expected: ProviderFailure) {
                    expected
                }
                assertTrue("$protocol marker outside the bounded prefix is not observed; 503 fallback remains retryable", failure.retryable)
                assertTrue("$protocol preserves HTTP status in the error", failure.message.orEmpty().contains("HTTP 503"))
                assertTrue(
                    "$protocol consumes the bounded prefix before status fallback",
                    signals.consumedBytes.get() >= HTTP_ERROR_BODY_LIMIT_BYTES.toLong(),
                )
                assertReadWithinBudget(signals, HTTP_ERROR_BODY_LIMIT_BYTES, "$protocol status-fallback error")
                assertCallCancelled(signals, "$protocol status-fallback error cap")
                assertEquals("$protocol status fallback remains one request", 1, server.requestCount)
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun sseHttpErrorPrefixIsBoundedAnd503RemainsSingleRetryableAttempt() = runBlocking {
        val errorBody = "temporarily unavailable" + "x".repeat(64 * 1024)
        for (protocol in ProviderProtocol.values()) {
            val server = MockWebServer()
            val signals = ResponseBodyReadSignals()
            server.start()
            try {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(503)
                        .addHeader("Retry-After", "0")
                        .setChunkedBody(errorBody, 1024),
                )
                val events = withTimeout(5_000) {
                    providerFor(protocol, server, signals.client()).stream(request()).toList()
                }
                val failure = events.single() as StreamEvent.Failed
                assertTrue("$protocol large error body preserves retryable HTTP 503", failure.retryable)
                assertTrue("$protocol retains the HTTP status", failure.message.contains("HTTP 503"))
                assertTrue(
                    "$protocol reads the error prefix for classifier markers",
                    signals.consumedBytes.get() >= HTTP_ERROR_BODY_LIMIT_BYTES.toLong(),
                )
                assertReadWithinBudget(signals, HTTP_ERROR_BODY_LIMIT_BYTES, "$protocol SSE HTTP error")
                assertCallCancelled(signals, "$protocol SSE HTTP error-prefix cap")
                assertEquals("$protocol 503 remains one provider attempt", 1, server.requestCount)
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun stream503WithRetryAfterZeroMakesOneRequestPerProtocol() = runBlocking {
        for (protocol in ProviderProtocol.values()) {
            val server = MockWebServer()
            server.start()
            try {
                repeat(2) {
                    server.enqueue(
                        MockResponse().setResponseCode(503).addHeader("Retry-After", "0")
                            .setBody("temporarily unavailable"),
                    )
                }
                val events = withTimeout(3_000) { providerFor(protocol, server).stream(request()).toList() }
                assertEquals(
                    "$protocol 503+Retry-After:0 is one retryable failure",
                    StreamEvent.Failed("HTTP 503 temporarily unavailable", retryable = true),
                    events.single(),
                )
                val received = withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) }
                assertNotNull("$protocol records the single POST", received)
                assertEquals("no hidden OkHttp 503 follow-up for $protocol", 1, server.requestCount)
                val second = withContext(Dispatchers.IO) { server.takeRequest(200, TimeUnit.MILLISECONDS) }
                assertNull("no second $protocol request", second)
                assertEquals(1, server.requestCount)
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun listModels503WithRetryAfterZeroMakesOneRequestPerProtocol() = runBlocking {
        for (protocol in ProviderProtocol.values()) {
            val server = MockWebServer()
            server.start()
            try {
                repeat(2) {
                    server.enqueue(
                        MockResponse().setResponseCode(503).addHeader("Retry-After", "0")
                            .setBody("temporarily unavailable"),
                    )
                }
                try {
                    providerFor(protocol, server).listModels()
                    throw AssertionError("expected $protocol 503 to throw")
                } catch (expected: ProviderFailure) {
                    assertTrue("$protocol 503 stays retryable", expected.retryable)
                    assertTrue(expected.message.orEmpty().contains("HTTP 503"))
                }
                val received = withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) }
                assertNotNull("$protocol records the single GET", received)
                assertEquals("GET", received!!.method)
                assertEquals("no hidden OkHttp 503 follow-up for $protocol GET", 1, server.requestCount)
                val second = withContext(Dispatchers.IO) { server.takeRequest(200, TimeUnit.MILLISECONDS) }
                assertNull("no second $protocol GET", second)
                assertEquals(1, server.requestCount)
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun injectedRetryEnabledClientStillMakesOneRequestOn503RetryAfterZero() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            repeat(2) {
                server.enqueue(
                    MockResponse().setResponseCode(503).addHeader("Retry-After", "0")
                        .setBody("temporarily unavailable"),
                )
            }
            val permissive = OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
            val events = withTimeout(3_000) {
                providerFor(ProviderProtocol.CHAT_COMPLETIONS, server, permissive).stream(request()).toList()
            }
            assertEquals(1, events.size)
            assertEquals("no hidden follow-up with injected retry-enabled client", 1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun modelListCallTimeoutWaitingForHeadersIsNetworkFailureNotCancellation() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient.Builder()
                .callTimeout(300, TimeUnit.MILLISECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .build()
            try {
                withTimeout(5_000) { anthropic(server, client).listModels() }
                throw AssertionError("expected callTimeout to fail the model list")
            } catch (e: CancellationException) {
                throw AssertionError("OkHttp callTimeout must not surface as CancellationException", e)
            } catch (e: IOException) {
                assertEquals(
                    "callTimeout stays a retryable network failure",
                    FailureKind.RETRYABLE,
                    ProviderErrorClassifier.classify(null, e, e.message),
                )
            }
            val received = withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) }
            assertNotNull("timed-out model request still reaches the server", received)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun modelListCallTimeoutWhileReadingBodyIsNetworkFailureNotCancellation() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setBody("{\"data\":[]}")
                    .setBodyDelay(5, TimeUnit.SECONDS),
            )
            val client = OkHttpClient.Builder()
                .callTimeout(300, TimeUnit.MILLISECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .build()
            try {
                withTimeout(5_000) { anthropic(server, client).listModels() }
                throw AssertionError("expected callTimeout to fail the model list")
            } catch (e: CancellationException) {
                throw AssertionError("OkHttp callTimeout must not surface as CancellationException", e)
            } catch (e: IOException) {
                assertEquals(
                    "callTimeout stays a retryable network failure",
                    FailureKind.RETRYABLE,
                    ProviderErrorClassifier.classify(null, e, e.message),
                )
            }
            val received = withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) }
            assertNotNull("timed-out model request still reaches the server", received)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sseCallTimeoutWaitingForHeadersIsRetryableFailureNotCancellation() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient.Builder()
                .callTimeout(300, TimeUnit.MILLISECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .build()
            // A CancellationException here (not a Failed event) is the regression:
            // streamingFlow rethrows cancellation instead of emitting a failure.
            val events = withTimeout(5_000) { anthropic(server, client).stream(request()).toList() }
            val failure = events.single() as StreamEvent.Failed
            assertTrue("callTimeout surfaces as a retryable transport failure", failure.retryable)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sseCallTimeoutWhileReadingBodyIsRetryableFailureNotCancellation() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: body-never-arrives\n\n")
                    .setBodyDelay(5, TimeUnit.SECONDS),
            )
            val client = OkHttpClient.Builder()
                .callTimeout(300, TimeUnit.MILLISECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .build()
            val events = withTimeout(5_000) { anthropic(server, client).stream(request()).toList() }
            val failure = events.single() as StreamEvent.Failed
            assertTrue("callTimeout surfaces as a retryable transport failure", failure.retryable)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun modelListChunkedLimitPlusOneCarriesTypedTooLargeCode() = runBlocking {
        for (protocol in ProviderProtocol.values()) {
            val server = MockWebServer()
            val signals = ResponseBodyReadSignals()
            server.start()
            try {
                server.enqueue(
                    MockResponse().setChunkedBody(
                        modelListJsonWithExactBytes(MODEL_LIST_BODY_LIMIT_BYTES + 1),
                        1024,
                    ),
                )
                val failure = expectTooLarge(
                    providerFor(protocol, server, signals.client()),
                    "$protocol chunked LIMIT+1 model list",
                )
                assertEquals("$protocol chunked LIMIT+1 carries typed code", ProviderFailureCode.TOO_LARGE, failure.code)
                assertCallCancelled(signals, "$protocol chunked LIMIT+1 overflow")
                assertReadWithinBudget(signals, MODEL_LIST_BODY_LIMIT_BYTES, "$protocol chunked LIMIT+1 model list")
                assertEquals("$protocol chunked LIMIT+1 remains one request", 1, server.requestCount)
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun errorPrefixCapBoundariesListModelsFailsWithStatusFallback() = runBlocking {
        // No fatal marker: bare 503 falls back to retryable status classification.
        // Sentinel semantics (mirrors readBoundedProviderBody): only a true
        // overflow byte beyond 16KiB cancels. Exact-cap EOF leaves the Call
        // uncancelled; only 16385+ cancels. Consumed is within-budget
        // (cap + 1 sentinel + read-ahead allowance), not exact, because the
        // probe may consume one extra byte on overflow.
        for (protocol in ProviderProtocol.values()) {
            for (size in listOf(16383, 16384, 16385)) {
                for (unknownLength in listOf(false, true)) {
                    val server = MockWebServer()
                    val signals = ResponseBodyReadSignals()
                    server.start()
                    try {
                        val errorBody = "e".repeat(size)
                        val response = MockResponse().setResponseCode(503)
                        if (unknownLength) response.setChunkedBody(errorBody, 1024) else response.setBody(errorBody)
                        // Determinism: disable pooled-connection reuse-drain, which
                        // otherwise races with cancelCall() and makes the exact
                        // client-source byte count non-deterministic (16384 vs
                        // 16385 for the 16385 over-cap case). Cancel, budget,
                        // status and retryable assertions below are unchanged.
                        response.addHeader("Connection", "close")
                        server.enqueue(response)
                        val kind = if (unknownLength) "chunked" else "known-length"
                        val context = "$protocol $kind error $size"
                        val failure = try {
                            providerFor(protocol, server, signals.client()).listModels()
                            throw AssertionError("$context should fail")
                        } catch (expected: ProviderFailure) {
                            expected
                        }
                        assertTrue("$context preserves HTTP status", failure.message.orEmpty().contains("HTTP 503"))
                        assertTrue("$context bare 503 falls back to retryable", failure.retryable)
                        val floorBytes = minOf(size, HTTP_ERROR_BODY_LIMIT_BYTES).toLong()
                        val consumedBytes = signals.consumedBytes.get()
                        assertTrue(
                            "$context consumes at least the bounded prefix ($consumedBytes >= $floorBytes)",
                            consumedBytes >= floorBytes,
                        )
                        assertReadWithinBudget(signals, HTTP_ERROR_BODY_LIMIT_BYTES, context)
                        if (size <= HTTP_ERROR_BODY_LIMIT_BYTES) {
                            // Under-cap and exact-cap sizes are deterministic with
                            // "Connection: close" (no pooled-drain race): production
                            // sentinel probes -1 on exact-cap EOF, so consumed is
                            // exact and the Call stays uncancelled.
                            val expectedConsumed = size.toLong()
                            assertEquals("$context consumed exact client-source bytes", expectedConsumed, signals.consumedBytes.get())
                        } else {
                            // Over-cap: sentinel probe consumes one overflow byte
                            // (discarded, prefix stays capped); observed count may
                            // be cap or cap+1 via OkHttp close-discard vs
                            // cancelCall race, not a production over-read.
                            val consumed = signals.consumedBytes.get()
                            assertTrue(
                                "$context consumed cap client-source bytes up to close-discard race (cap or cap+1), got $consumed",
                                consumed == HTTP_ERROR_BODY_LIMIT_BYTES.toLong() ||
                                    consumed == HTTP_ERROR_BODY_LIMIT_BYTES.toLong() + 1L,
                            )
                        }
                        if (size <= HTTP_ERROR_BODY_LIMIT_BYTES) {
                            assertFalse(
                                "$context at-or-under-cap EOF leaves the Call uncancelled",
                                withContext(Dispatchers.IO) { signals.calls.cancelled.await(1, TimeUnit.SECONDS) },
                            )
                        } else {
                            assertCallCancelled(signals, context)
                        }
                        assertEquals("$context remains one request", 1, server.requestCount)
                    } finally {
                        server.shutdown()
                    }
                }
            }
        }
    }

    @Test
    fun errorPrefixCapBoundariesStreamFailsWithStatusFallback() = runBlocking {
        // Stream twin of the listModels cap-boundary matrix: same bodies, same
        // sentinel cancel expectations (exact-cap EOF uncancelled), surfaced
        // as one Failed event.
        for (protocol in ProviderProtocol.values()) {
            for (size in listOf(16383, 16384, 16385)) {
                for (unknownLength in listOf(false, true)) {
                    val server = MockWebServer()
                    val signals = ResponseBodyReadSignals()
                    server.start()
                    try {
                        val errorBody = "e".repeat(size)
                        val response = MockResponse().setResponseCode(503)
                        if (unknownLength) response.setChunkedBody(errorBody, 1024) else response.setBody(errorBody)
                        // Determinism: same pooled-drain race as the listModels
                        // twin above; "Connection: close" keeps the exact byte
                        // count deterministic without touching cancel/budget.
                        response.addHeader("Connection", "close")
                        server.enqueue(response)
                        val kind = if (unknownLength) "chunked" else "known-length"
                        val context = "$protocol $kind SSE error $size"
                        val events = withTimeout(5_000) {
                            providerFor(protocol, server, signals.client()).stream(request()).toList()
                        }
                        val failure = events.single() as StreamEvent.Failed
                        assertTrue("$context preserves HTTP status", failure.message.contains("HTTP 503"))
                        assertTrue("$context bare 503 stays retryable", failure.retryable)
                        val floorBytes = minOf(size, HTTP_ERROR_BODY_LIMIT_BYTES).toLong()
                        val consumedBytes = signals.consumedBytes.get()
                        assertTrue(
                            "$context consumes at least the bounded prefix ($consumedBytes >= $floorBytes)",
                            consumedBytes >= floorBytes,
                        )
                        assertReadWithinBudget(signals, HTTP_ERROR_BODY_LIMIT_BYTES, context)
                        if (size <= HTTP_ERROR_BODY_LIMIT_BYTES) {
                            // Same determinism note as the listModels twin:
                            // under-cap/exact-cap are exact with "Connection:
                            // close"; exact-cap sentinel probes -1, uncancelled.
                            val expectedConsumed = size.toLong()
                            assertEquals("$context consumed exact client-source bytes", expectedConsumed, signals.consumedBytes.get())
                        } else {
                            // Same over-cap race as the listModels twin:
                            // sentinel probe + OkHttp close-discard vs
                            // cancelCall may add 1 byte; not a production
                            // over-read (see budget assertion above).
                            val consumed = signals.consumedBytes.get()
                            assertTrue(
                                "$context consumed cap client-source bytes up to close-discard race (cap or cap+1), got $consumed",
                                consumed == HTTP_ERROR_BODY_LIMIT_BYTES.toLong() ||
                                    consumed == HTTP_ERROR_BODY_LIMIT_BYTES.toLong() + 1L,
                            )
                        }
                        if (size <= HTTP_ERROR_BODY_LIMIT_BYTES) {
                            assertFalse(
                                "$context at-or-under-cap EOF leaves the Call uncancelled",
                                withContext(Dispatchers.IO) { signals.calls.cancelled.await(1, TimeUnit.SECONDS) },
                            )
                        } else {
                            assertCallCancelled(signals, context)
                        }
                        assertEquals("$context remains one provider attempt", 1, server.requestCount)
                    } finally {
                        server.shutdown()
                    }
                }
            }
        }
    }

    @Test
    fun cancellationInsideErrorPrefixListModelsStaysCancellation() = runBlocking {
        // Slow peer: headers arrive at once, the 503 error body stalls 10s, so
        // the Job is cancelled while blocked inside the error-prefix read.
        val hugeBody = "e".repeat(64 * 1024)
        for (protocol in ProviderProtocol.values()) {
            val server = MockWebServer()
            val signals = ResponseBodyReadSignals()
            server.start()
            var job: Job? = null
            try {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(503)
                        .setChunkedBody(hugeBody, 1024)
                        .setBodyDelay(10, TimeUnit.SECONDS),
                )
                var thrown: Throwable? = null
                val active = launch(Dispatchers.IO) {
                    try {
                        providerFor(protocol, server, signals.client()).listModels()
                    } catch (e: Throwable) {
                        thrown = e
                    }
                }
                job = active
                assertTrue(
                    "$protocol error headers arrive before body delay",
                    withContext(Dispatchers.IO) { signals.calls.headers.await(3, TimeUnit.SECONDS) },
                )
                assertTrue(
                    "$protocol error-prefix read entered before cancel",
                    withContext(Dispatchers.IO) { signals.readEntered.await(3, TimeUnit.SECONDS) },
                )
                withTimeout(1_500) { active.cancelAndJoin() }
                assertCallCancelled(signals, "$protocol error-prefix cancellation")
                withTimeoutOrNull(1_500) { active.join() }
                assertTrue("$protocol error-prefix cancellation stays CancellationException, got $thrown", thrown is CancellationException)
                assertFalse("$protocol cancellation is not converted to ProviderFailure", thrown is ProviderFailure)
                assertEquals("$protocol cancelled error prefix remains one request", 1, server.requestCount)
            } finally {
                job?.cancel()
                server.shutdown()
                job?.let { active -> withTimeoutOrNull(1_500) { active.join() } }
            }
        }
    }

    @Test
    fun cancellationInsideErrorPrefixStreamEmitsNoFailure() = runBlocking {
        // Stream twin: cancelling inside the 503 error-prefix read must surface
        // as CancellationException with no Failed event, not a failure.
        val hugeBody = "e".repeat(64 * 1024)
        for (protocol in ProviderProtocol.values()) {
            val server = MockWebServer()
            val signals = ResponseBodyReadSignals()
            server.start()
            var job: Job? = null
            try {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(503)
                        .setChunkedBody(hugeBody, 1024)
                        .setBodyDelay(10, TimeUnit.SECONDS),
                )
                val events = mutableListOf<StreamEvent>()
                var thrown: Throwable? = null
                val active = launch(Dispatchers.IO) {
                    try {
                        events.addAll(providerFor(protocol, server, signals.client()).stream(request()).toList())
                    } catch (e: Throwable) {
                        thrown = e
                    }
                }
                job = active
                assertTrue(
                    "$protocol SSE error headers arrive before body delay",
                    withContext(Dispatchers.IO) { signals.calls.headers.await(3, TimeUnit.SECONDS) },
                )
                assertTrue(
                    "$protocol SSE error-prefix read entered before cancel",
                    withContext(Dispatchers.IO) { signals.readEntered.await(3, TimeUnit.SECONDS) },
                )
                withTimeout(1_500) { active.cancelAndJoin() }
                assertCallCancelled(signals, "$protocol SSE error-prefix cancellation")
                withTimeoutOrNull(1_500) { active.join() }
                assertTrue("$protocol SSE error-prefix cancellation stays CancellationException, got $thrown", thrown is CancellationException)
                assertTrue("$protocol SSE cancellation emits no Failed event", events.none { it is StreamEvent.Failed })
                assertEquals("$protocol cancelled SSE error prefix remains one request", 1, server.requestCount)
            } finally {
                job?.cancel()
                server.shutdown()
                job?.let { active -> withTimeoutOrNull(1_500) { active.join() } }
            }
        }
    }

    @Test
    fun slowChunkedExactCapModelListStillSucceeds() = runBlocking {
        // Sentinel semantics on a slow stream: an exact-cap body delivered in
        // throttled periods must still EOF-probe -1 (success), not misfire
        // TOO_LARGE. Throttle windows are small on purpose: the probe
        // behavior depends on readTimeout + call.cancel, so this test stays
        // far from the 5min read idle timeout (see the stall test below).
        val body = modelListJsonWithExactBytes(MODEL_LIST_BODY_LIMIT_BYTES)
        for (protocol in ProviderProtocol.values()) {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(
                    MockResponse()
                        .setChunkedBody(body, 8192)
                        .throttleBody(128 * 1024, 50, TimeUnit.MILLISECONDS),
                )
                val models = withTimeout(30_000) {
                    providerFor(protocol, server).listModels()
                }
                assertEquals(listOf("budget-model"), models)
                assertEquals("$protocol slow exact-cap uses one model-list request", 1, server.requestCount)
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun stalledChunkedBodyAfterCapIsCancellable() = runBlocking {
        // Slow chunked stall: the first throttle period delivers the full cap
        // (~1MiB socket bytes, framing makes it marginally fewer body bytes),
        // then the remainder stalls for one long period. The transport must
        // block in source.read (bounded only by readTimeout) and stay
        // interruptible via call.cancel(): cancelling surfaces
        // CancellationException, never a TOO_LARGE misfire. Small stall
        // windows keep this deterministic without approaching the 5min
        // readTimeout; a full-timeout stall proof is a follow-up, not this PR.
        val oversized = modelListJsonWithExactBytes(MODEL_LIST_BODY_LIMIT_BYTES + 64 * 1024)
        for (protocol in ProviderProtocol.values()) {
            val server = MockWebServer()
            val signals = ResponseBodyReadSignals()
            server.start()
            var job: Job? = null
            try {
                server.enqueue(
                    MockResponse()
                        .setChunkedBody(oversized, 64 * 1024)
                        .throttleBody(MODEL_LIST_BODY_LIMIT_BYTES.toLong(), 5, TimeUnit.SECONDS),
                )
                var thrown: Throwable? = null
                val active = launch(Dispatchers.IO) {
                    try {
                        providerFor(protocol, server, signals.client()).listModels()
                    } catch (e: Throwable) {
                        thrown = e
                    }
                }
                job = active
                // Wait until the slow first period has delivered (through the
                // cap region); the overflow byte is still stalled server-side.
                withTimeout(20_000) {
                    while (signals.consumedBytes.get() < MODEL_LIST_BODY_LIMIT_BYTES - 64 * 1024) delay(50)
                }
                // Let in-flight 8KiB reads advance into the stall block.
                delay(300)
                withTimeout(1_500) { active.cancelAndJoin() }
                assertCallCancelled(signals, "$protocol stalled chunked body")
                withTimeoutOrNull(1_500) { active.join() }
                assertTrue(
                    "$protocol stall cancellation stays CancellationException, got $thrown",
                    thrown is CancellationException,
                )
                assertFalse("$protocol stall cancellation is not TOO_LARGE", (thrown as? ProviderFailure)?.code == ProviderFailureCode.TOO_LARGE)
                assertReadWithinBudget(signals, MODEL_LIST_BODY_LIMIT_BYTES, "$protocol stalled chunked body")
                assertEquals("$protocol stalled body remains one request", 1, server.requestCount)
            } finally {
                job?.cancel()
                server.shutdown()
                job?.let { active -> withTimeoutOrNull(1_500) { active.join() } }
            }
        }
    }

    @Test
    fun shortReadTimeoutWhileReadingBodyIsTransportErrorNotTooLarge() = runBlocking {
        // A stalled body under a short readTimeout must surface as a
        // retryable transport IOException, never TOO_LARGE: the cap was never
        // reached, so no size verdict applies.
        for (protocol in ProviderProtocol.values()) {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(
                    MockResponse()
                        .setBody("{\"data\":[]}")
                        .setBodyDelay(3, TimeUnit.SECONDS),
                )
                val client = OkHttpClient.Builder()
                    .readTimeout(500, TimeUnit.MILLISECONDS)
                    .build()
                try {
                    withTimeout(15_000) { providerFor(protocol, server, client).listModels() }
                    throw AssertionError("$protocol stalled body should hit the short readTimeout")
                } catch (e: CancellationException) {
                    throw AssertionError("$protocol readTimeout must not surface as CancellationException", e)
                } catch (e: ProviderFailure) {
                    throw AssertionError("$protocol stalled body must not be TOO_LARGE, got ${e.code}", e)
                } catch (e: IOException) {
                    assertFalse("$protocol timeout message carries no TOO_LARGE verdict", e.message.orEmpty().contains("TOO_LARGE"))
                    assertEquals(
                        "$protocol read timeout stays a retryable transport failure",
                        FailureKind.RETRYABLE,
                        ProviderErrorClassifier.classify(null, e, e.message),
                    )
                }
                val received = withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) }
                assertNotNull("$protocol stalled request still reaches the server", received)
                assertEquals("$protocol stall remains one request", 1, server.requestCount)
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun gzipErrorPrefixDecodingBoundariesDoNotCrash() = runBlocking {
        // Budget counts DECODED bytes: the counting interceptor sits at the
        // application layer, after OkHttp transparently inflates gzip.
        val marker = "insufficient_quota"
        val justInside = "x".repeat(HTTP_ERROR_BODY_LIMIT_BYTES - marker.length - 1) + marker + "y".repeat(32 * 1024)
        val justOutside = "x".repeat(HTTP_ERROR_BODY_LIMIT_BYTES - 5) + marker + "y".repeat(32 * 1024)
        // Marker straddles the 8KiB provider read chunk (8190..8207 crosses
        // 8192) and the 16KiB cap lands mid-あ (8208 + 8176 = 16384).
        val splitMarkerMultibyte = "x".repeat(8190) + marker + "あ".repeat(3000) + "z".repeat(20 * 1024)
        val illegalPrefix = "err ".toByteArray(Charsets.UTF_8) +
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            marker.toByteArray(Charsets.UTF_8) +
            "x".repeat(32 * 1024).toByteArray(Charsets.UTF_8)
        for (protocol in ProviderProtocol.values()) {
            // (i) marker just inside vs just outside the 16KiB decoded prefix.
            for ((label, body, expectRetryable) in listOf(
                Triple("marker-just-inside", justInside, false),
                Triple("marker-just-outside", justOutside, true),
            )) {
                val server = MockWebServer()
                val signals = ResponseBodyReadSignals()
                server.start()
                try {
                    server.enqueue(
                        MockResponse()
                            .setResponseCode(503)
                            .addHeader("Content-Encoding", "gzip")
                            .setBody(gzipBody(body)),
                    )
                    val failure = try {
                        providerFor(protocol, server, signals.client()).listModels()
                        throw AssertionError("$protocol gzip $label should fail")
                    } catch (expected: ProviderFailure) {
                        expected
                    }
                    assertTrue("$protocol gzip $label preserves HTTP status", failure.message.orEmpty().contains("HTTP 503"))
                    assertEquals("$protocol gzip $label retryable follows marker visibility", expectRetryable, failure.retryable)
                    assertReadWithinBudget(signals, HTTP_ERROR_BODY_LIMIT_BYTES, "$protocol gzip $label")
                    assertCallCancelled(signals, "$protocol gzip $label cap")
                    assertEquals("$protocol gzip $label remains one request", 1, server.requestCount)
                } finally {
                    server.shutdown()
                }
            }
            // (ii) multi-byte split + marker split across the 8KiB read chunk.
            run {
                val server = MockWebServer()
                val signals = ResponseBodyReadSignals()
                server.start()
                try {
                    server.enqueue(
                        MockResponse()
                            .setResponseCode(503)
                            .addHeader("Content-Encoding", "gzip")
                            .setBody(gzipBody(splitMarkerMultibyte)),
                    )
                    val failure = try {
                        providerFor(protocol, server, signals.client()).listModels()
                        throw AssertionError("$protocol gzip split-marker-multibyte should fail without crashing")
                    } catch (expected: ProviderFailure) {
                        expected
                    }
                    assertTrue("$protocol gzip split marker preserves HTTP status", failure.message.orEmpty().contains("HTTP 503"))
                    assertFalse("$protocol gzip split marker across 8KiB chunk stays visible/fatal", failure.retryable)
                    assertReadWithinBudget(signals, HTTP_ERROR_BODY_LIMIT_BYTES, "$protocol gzip split-marker-multibyte")
                    assertCallCancelled(signals, "$protocol gzip split-marker-multibyte cap")
                    assertEquals("$protocol gzip split case remains one request", 1, server.requestCount)
                } finally {
                    server.shutdown()
                }
            }
            // (iii) illegal UTF-8 sequence preserved via raw gzip bytes.
            run {
                val server = MockWebServer()
                val signals = ResponseBodyReadSignals()
                server.start()
                try {
                    server.enqueue(
                        MockResponse()
                            .setResponseCode(503)
                            .addHeader("Content-Encoding", "gzip")
                            .setBody(gzipBytes(illegalPrefix)),
                    )
                    val failure = try {
                        providerFor(protocol, server, signals.client()).listModels()
                        throw AssertionError("$protocol gzip illegal-sequence should fail without crashing")
                    } catch (expected: ProviderFailure) {
                        expected
                    }
                    assertTrue("$protocol gzip illegal bytes preserve HTTP status", failure.message.orEmpty().contains("HTTP 503"))
                    assertFalse("$protocol gzip ASCII marker survives illegal bytes (fatal)", failure.retryable)
                    assertReadWithinBudget(signals, HTTP_ERROR_BODY_LIMIT_BYTES, "$protocol gzip illegal-sequence")
                    assertCallCancelled(signals, "$protocol gzip illegal-sequence cap")
                    assertEquals("$protocol gzip illegal case remains one request", 1, server.requestCount)
                } finally {
                    server.shutdown()
                }
            }
            // Gzip stream twin: one decoded-over-cap case must also surface as Failed.
            run {
                val server = MockWebServer()
                val signals = ResponseBodyReadSignals()
                server.start()
                try {
                    server.enqueue(
                        MockResponse()
                            .setResponseCode(503)
                            .addHeader("Content-Encoding", "gzip")
                            .setBody(gzipBody(justOutside)),
                    )
                    val events = withTimeout(5_000) {
                        providerFor(protocol, server, signals.client()).stream(request()).toList()
                    }
                    val failure = events.single() as StreamEvent.Failed
                    assertTrue("$protocol gzip SSE preserves HTTP status", failure.message.contains("HTTP 503"))
                    assertTrue("$protocol gzip SSE outside-marker stays retryable", failure.retryable)
                    assertReadWithinBudget(signals, HTTP_ERROR_BODY_LIMIT_BYTES, "$protocol gzip SSE")
                    assertCallCancelled(signals, "$protocol gzip SSE cap")
                    assertEquals("$protocol gzip SSE remains one attempt", 1, server.requestCount)
                } finally {
                    server.shutdown()
                }
            }
        }
    }
}
