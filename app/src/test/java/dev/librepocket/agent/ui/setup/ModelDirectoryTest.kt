package dev.librepocket.agent.ui.setup

import dev.librepocket.models.ModelsDevSnapshot
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Local synthetic transport tests; none contact models.dev. */
class ModelDirectoryTest {
    @Test
    fun fetchBudgetRejectsLongMaxByteCaps(): Unit {
        assertThrows(IllegalArgumentException::class.java) {
            ModelDirectory.FetchBudget(maxWireBytes = Long.MAX_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelDirectory.FetchBudget(maxDecodedBytes = Long.MAX_VALUE)
        }
    }

    @Test
    fun fetchBudgetAcceptsDefaultAndSmallPositiveCaps(): Unit {
        val defaults = ModelDirectory.FetchBudget()
        assertEquals(ModelDirectory.DEFAULT_MAX_WIRE_BYTES, defaults.maxWireBytes)
        assertEquals(ModelDirectory.DEFAULT_MAX_DECODED_BYTES, defaults.maxDecodedBytes)

        val small = ModelDirectory.FetchBudget(maxWireBytes = 1, maxDecodedBytes = 1)
        assertEquals(1L, small.maxWireBytes)
        assertEquals(1L, small.maxDecodedBytes)
    }

    @Test
    fun fetchBodyRejectsKnownLengthLargerThanDefaultBodyCap(): Unit {
        runBlocking {
            withServer { server ->
                server.enqueue(MockResponse().setBody("x".repeat(DEFAULT_BODY_CAP_BYTES + 1)))
                val observedCall = AtomicReference<Call?>()
                val canceledAtBodyCompletion = AtomicReference<Boolean?>(null)
                val client = trackingClient(observedCall, canceledAtBodyCompletion)

                val failure = fetchFailure(server, client = client)
                assertEquals(ModelsDevSnapshot.SnapshotFallbackReason.WIRE_BODY_TOO_LARGE, failure.reason)
                assertTrue("oversize rejection must cancel before response close can drain", observedCall.get()!!.isCanceled())
                assertTrue("body close must observe cancellation before any HTTP/1 discard", canceledAtBodyCompletion.get() == true)
            }
        }
    }

    @Test
    fun fetchBodyRejectsUnknownLengthChunkedWireOverflow(): Unit {
        runBlocking {
            withServer { server ->
                server.enqueue(MockResponse().setChunkedBody("x".repeat(17), 3))
                val observedCall = AtomicReference<Call?>()
                val canceledAtBodyCompletion = AtomicReference<Boolean?>(null)
                val client = trackingClient(observedCall, canceledAtBodyCompletion)

                val failure = fetchFailure(
                    server,
                    smallBudget(maxWireBytes = 16, maxDecodedBytes = 64),
                    client,
                )
                assertEquals(ModelsDevSnapshot.SnapshotFallbackReason.WIRE_BODY_TOO_LARGE, failure.reason)
                assertTrue("stream overflow must cancel before response close can drain", observedCall.get()!!.isCanceled())
                assertTrue("body close must observe cancellation before any HTTP/1 discard", canceledAtBodyCompletion.get() == true)
            }
        }
    }

    @Test
    fun fetchBodyEnforcesDecodedUtf8BytesRatherThanCharacterCount(): Unit {
        runBlocking {
            withServer { server ->
                val body = "é".repeat(7)
                assertTrue(body.length < 12)
                assertTrue(body.toByteArray(Charsets.UTF_8).size > 12)
                server.enqueue(MockResponse().setBody(body))

                val failure = fetchFailure(server, smallBudget(maxWireBytes = 64, maxDecodedBytes = 12))
                assertEquals(ModelsDevSnapshot.SnapshotFallbackReason.DECODED_BODY_TOO_LARGE, failure.reason)
            }
        }
    }

    @Test
    fun fetchBodyRejectsGzipExpansionAboveDecodedLimitWhenWireFits(): Unit {
        runBlocking {
            withServer { server ->
                val compressed = gzip("a".repeat(256))
                assertTrue(compressed.size < 64)
                server.enqueue(
                    MockResponse()
                        .setHeader("Content-Encoding", "gzip")
                        .setBody(Buffer().write(compressed)),
                )

                val failure = fetchFailure(server, smallBudget(maxWireBytes = 64, maxDecodedBytes = 64))
                assertEquals(ModelsDevSnapshot.SnapshotFallbackReason.DECODED_BODY_TOO_LARGE, failure.reason)
            }
        }
    }

    @Test
    fun fetchBodyClassifiesPrematureDisconnectAsTruncated(): Unit {
        runBlocking {
            withServer { server ->
                server.enqueue(
                    MockResponse()
                        .setBody("response-body-that-will-be-cut-off")
                        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
                )

                val failure = fetchFailure(server, smallBudget(maxWireBytes = 128, maxDecodedBytes = 128))
                assertEquals(ModelsDevSnapshot.SnapshotFallbackReason.TRUNCATED_BODY, failure.reason)
            }
        }
    }

    @Test
    fun httpStatusWinsWithoutReadingLargeBodyRetryingOrLeakingBodyText(): Unit {
        runBlocking {
            withServer { server ->
                val sentinel = "private-response-sentinel"
                server.enqueue(
                    MockResponse()
                        .setResponseCode(503)
                        .addHeader("Retry-After", "0")
                        .setBody(sentinel.repeat(64)),
                )
                val observedCall = AtomicReference<Call?>()
                val canceledAtBodyCompletion = AtomicReference<Boolean?>(null)
                val client = trackingClient(observedCall, canceledAtBodyCompletion)

                val failure = fetchFailure(
                    server,
                    smallBudget(maxWireBytes = 8, maxDecodedBytes = 8),
                    client,
                )
                assertEquals(ModelsDevSnapshot.SnapshotFallbackReason.HTTP_STATUS, failure.reason)
                assertEquals(503, failure.httpStatusCode)
                assertFalse(failure.message.orEmpty().contains(sentinel))
                assertEquals(1, server.requestCount)
                assertTrue("error response must cancel before response close can drain", observedCall.get()!!.isCanceled())
                assertTrue("terminal body event must observe cancellation before close/discard", canceledAtBodyCompletion.get() == true)
            }
        }
    }

    @Test
    fun redirectIsAStatusFallbackAndIsNotFollowed(): Unit {
        runBlocking {
            withServer { server ->
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/second"))
                server.enqueue(MockResponse().setBody("should not be requested"))

                val failure = fetchFailure(server)
                assertEquals(ModelsDevSnapshot.SnapshotFallbackReason.HTTP_STATUS, failure.reason)
                assertEquals(302, failure.httpStatusCode)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun unsupportedEncodingHasStableReason(): Unit {
        runBlocking {
            withServer { server ->
                server.enqueue(MockResponse().setHeader("Content-Encoding", "br").setBody("opaque"))
                val observedCall = AtomicReference<Call?>()
                val canceledAtBodyCompletion = AtomicReference<Boolean?>(null)
                val client = trackingClient(observedCall, canceledAtBodyCompletion)

                val failure = fetchFailure(server, client = client)
                assertEquals(
                    ModelsDevSnapshot.SnapshotFallbackReason.UNSUPPORTED_CONTENT_ENCODING,
                    failure.reason,
                )
                assertTrue(observedCall.get()!!.isCanceled())
                assertTrue("unsupported-body close must observe cancellation", canceledAtBodyCompletion.get() == true)
            }
        }
    }

    @Test
    fun emptySuccessfulResponseHasDistinctReason(): Unit {
        runBlocking {
            withServer { server ->
                server.enqueue(MockResponse().setBody(""))

                val failure = fetchFailure(server)
                assertEquals(ModelsDevSnapshot.SnapshotFallbackReason.EMPTY_BODY, failure.reason)
            }
        }
    }

    @Test
    fun totalCallDeadlineIsDistinctFromCallerCancellation(): Unit {
        runBlocking {
            withServer { server ->
                server.enqueue(
                    MockResponse()
                        .setHeadersDelay(2, TimeUnit.SECONDS)
                        .setBody("{}"),
                )

                val failure = withTimeout(3_000) {
                    fetchFailure(
                        server,
                        smallBudget(
                            maxWireBytes = 64,
                            maxDecodedBytes = 64,
                            connectTimeoutMs = 1_000,
                            readIdleTimeoutMs = 1_000,
                            callTimeoutMs = 200,
                        ),
                    )
                }
                assertEquals(ModelsDevSnapshot.SnapshotFallbackReason.TIMEOUT, failure.reason)
            }
        }
    }

    @Test
    fun fetchBodySendsPlainKeylessGetEvenIfSuppliedClientWouldAddAuth(): Unit {
        runBlocking {
            withServer { server ->
                server.enqueue(MockResponse().setBody("{}"))
                val observedCall = AtomicReference<Call?>()
                val client = trackingClient(observedCall)
                val authAddingClient = client.newBuilder()
                    .addInterceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder()
                                .header("Authorization", "Bearer synthetic-test-only")
                                .build(),
                        )
                    }
                    .addNetworkInterceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder()
                                .header("Authorization", "Bearer network-synthetic-test-only")
                                .header("Cookie", "session=synthetic-test-only")
                                .build(),
                        )
                    }
                    .build()

                assertEquals("{}", ModelDirectory.fetchBody(authAddingClient, server.url("/models.json").toString()))
                val request = server.takeRequest(1, TimeUnit.SECONDS)
                assertNotNull(request)
                assertEquals("GET", request!!.method)
                assertEquals("/models.json", request.path)
                assertNull(request.getHeader("Authorization"))
                assertNull(request.getHeader("Cookie"))
                assertFalse(observedCall.get()!!.isCanceled())
            }
        }
    }

    @Test
    fun fetchBodyStripsAppLayerXApiKey(): Unit {
        runBlocking {
            withServer { server ->
                server.enqueue(MockResponse().setBody("{}"))
                val leakingClient = OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder()
                                .header("x-api-key", "synthetic-test-only")
                                .build(),
                        )
                    }
                    .build()

                assertEquals("{}", ModelDirectory.fetchBody(leakingClient, server.url("/models.json").toString()))
                val request = server.takeRequest(1, TimeUnit.SECONDS)
                assertNotNull(request)
                assertNull("x-api-key must be stripped", request!!.getHeader("x-api-key"))
            }
        }
    }

    // Verifies isolation (the isolated client inherits no caller network
    // interceptors), not the strip path itself; the strip in the wire-budget
    // interceptor is defense-in-depth for hypothetical future attachment.
    @Test
    fun fetchBodyStripsNetworkLayerXApiKey(): Unit {
        runBlocking {
            withServer { server ->
                server.enqueue(MockResponse().setBody("{}"))
                val leakingClient = OkHttpClient.Builder()
                    .addNetworkInterceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder()
                                .header("x-api-key", "synthetic-network-test-only")
                                .build(),
                        )
                    }
                    .build()

                assertEquals("{}", ModelDirectory.fetchBody(leakingClient, server.url("/models.json").toString()))
                val request = server.takeRequest(1, TimeUnit.SECONDS)
                assertNotNull(request)
                assertNull("x-api-key must be stripped", request!!.getHeader("x-api-key"))
            }
        }
    }

    @Test
    fun fetchBodyStripsApiKeyVariantsAtAppLayer(): Unit {
        runBlocking {
            withServer { server ->
                for (headerName in API_KEY_HEADER_VARIANTS) {
                    server.enqueue(MockResponse().setBody("{}"))
                    val leakingClient = OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            chain.proceed(
                                chain.request().newBuilder()
                                    .header(headerName, "synthetic-test-only")
                                    .build(),
                            )
                        }
                        .build()

                    assertEquals(
                        "{}",
                        ModelDirectory.fetchBody(leakingClient, server.url("/models.json").toString()),
                    )
                    val request = server.takeRequest(1, TimeUnit.SECONDS)
                    assertNotNull("request leaking $headerName should reach the local server", request)
                    assertNull(request!!.getHeader(headerName))
                }
            }
        }
    }

    // Verifies isolation (the isolated client inherits no caller network
    // interceptors), not the strip path itself; the strip in the wire-budget
    // interceptor is defense-in-depth for hypothetical future attachment.
    @Test
    fun fetchBodyStripsApiKeyVariantsAtNetworkLayer(): Unit {
        runBlocking {
            withServer { server ->
                for (headerName in API_KEY_HEADER_VARIANTS) {
                    server.enqueue(MockResponse().setBody("{}"))
                    val leakingClient = OkHttpClient.Builder()
                        .addNetworkInterceptor { chain ->
                            chain.proceed(
                                chain.request().newBuilder()
                                    .header(headerName, "synthetic-network-test-only")
                                    .build(),
                            )
                        }
                        .build()

                    assertEquals(
                        "{}",
                        ModelDirectory.fetchBody(leakingClient, server.url("/models.json").toString()),
                    )
                    val request = server.takeRequest(1, TimeUnit.SECONDS)
                    assertNotNull("request leaking $headerName should reach the local server", request)
                    assertNull(request!!.getHeader(headerName))
                }
            }
        }
    }

    @Test
    fun fetchBodyIgnoresInjectedCookieJar(): Unit {
        runBlocking {
            withServer { server ->
                server.enqueue(
                    MockResponse()
                        .setBody("{}")
                        .addHeader("Set-Cookie", "server=synthetic-server-cookie; Path=/"),
                )
                val savedCookies = mutableListOf<Cookie>()
                val injectingJar = object : CookieJar {
                    override fun loadForRequest(url: HttpUrl): List<Cookie> = listOf(
                        Cookie.Builder()
                            .name("session")
                            .value("synthetic-cookie-injected")
                            .hostOnlyDomain(url.host)
                            .build(),
                    )

                    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                        savedCookies += cookies
                    }
                }
                val clientWithJar = OkHttpClient.Builder()
                    .cookieJar(injectingJar)
                    .build()

                assertEquals("{}", ModelDirectory.fetchBody(clientWithJar, server.url("/models.json").toString()))
                val request = server.takeRequest(1, TimeUnit.SECONDS)
                assertNotNull(request)
                assertNull(request!!.getHeader("Cookie"))
                assertTrue("isolated client must not persist server cookies", savedCookies.isEmpty())
            }
        }
    }

    @Test
    fun fetchBodyIgnoresInheritedCacheUnderSmallWireBudget(): Unit {
        runBlocking {
            val cacheDir = Files.createTempDirectory("model-directory-cache-test").toFile()
            var cachingClientRef: OkHttpClient? = null
            try {
                withServer { server ->
                    val cacheableBody = "x".repeat(64)
                    server.enqueue(
                        MockResponse()
                            .setBody(cacheableBody)
                            .addHeader("Cache-Control", "max-age=60"),
                    )
                    val cachingClient = OkHttpClient.Builder()
                        .cache(Cache(cacheDir, 1024 * 1024L))
                        .build()
                        .also { cachingClientRef = it }
                    // Populate the injected client's cache outside fetchBody.
                    val seedRequest = Request.Builder().url(server.url("/models.json")).get().build()
                    cachingClient.newCall(seedRequest).execute().use {
                        assertTrue(it.isSuccessful)
                        it.body.string()
                    }
                    assertEquals(1, server.requestCount)

                    // Positive control: the seed must actually be cached, otherwise
                    // the wire-cap assertion below would pass vacuously.
                    cachingClient.newCall(seedRequest).execute().use {
                        assertTrue(it.isSuccessful)
                        it.body.string()
                        assertNotNull("seeded response must be served from cache", it.cacheResponse)
                    }
                    assertEquals("seeded response must not hit the network again", 1, server.requestCount)

                    server.enqueue(
                        MockResponse()
                            .setBody(cacheableBody)
                            .addHeader("Cache-Control", "max-age=60"),
                    )
                    val failure = fetchFailure(
                        server,
                        smallBudget(maxWireBytes = 16, maxDecodedBytes = 128),
                        cachingClient,
                    )
                    assertEquals(ModelsDevSnapshot.SnapshotFallbackReason.WIRE_BODY_TOO_LARGE, failure.reason)
                    assertEquals(
                        "a cached response must not short-circuit the wire cap",
                        2,
                        server.requestCount,
                    )
                }
            } finally {
                try {
                    cachingClientRef?.cache?.close()
                } finally {
                    cacheDir.deleteRecursively()
                }
            }
        }
    }

    @Test
    fun fetchBodyStripsCompositeAuthFromBothLayers(): Unit {
        runBlocking {
            withServer { server ->
                server.enqueue(MockResponse().setBody("{}"))
                val compositeClient = OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        var leaked = chain.request().newBuilder()
                        for (headerName in STRIPPED_HEADERS) {
                            leaked = leaked.header(headerName, "synthetic-app-test-only")
                        }
                        chain.proceed(leaked.build())
                    }
                    .addNetworkInterceptor { chain ->
                        var leaked = chain.request().newBuilder()
                        for (headerName in STRIPPED_HEADERS) {
                            leaked = leaked.header(headerName, "synthetic-network-test-only")
                        }
                        chain.proceed(leaked.build())
                    }
                    .build()

                assertEquals("{}", ModelDirectory.fetchBody(compositeClient, server.url("/models.json").toString()))
                val request = server.takeRequest(1, TimeUnit.SECONDS)
                assertNotNull(request)
                for (headerName in STRIPPED_HEADERS) {
                    assertNull("header $headerName must be stripped", request!!.getHeader(headerName))
                }
            }
        }
    }

    @Test
    fun cancellingFetchClosesBlockingCallPromptly(): Unit {
        runBlocking {
            val server = MockWebServer()
            server.start()
            val client = OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            supervisorScope {
                val fetch = async(Dispatchers.IO) {
                    ModelDirectory.fetchBody(client, server.url("/held-response").toString())
                }
                try {
                    assertNotNull("request should reach the local server", server.takeRequest(2, TimeUnit.SECONDS))

                    fetch.cancel()
                    val completedPromptly = withTimeoutOrNull(CANCEL_COMPLETION_BUDGET_MS) {
                        fetch.join()
                        true
                    } ?: false

                    assertTrue("cancel must close blocking OkHttp I/O promptly", completedPromptly)
                    assertTrue(fetch.isCancelled)
                } finally {
                    server.shutdown()
                    fetch.cancelAndJoin()
                    client.connectionPool.evictAll()
                }
            }
        }
    }

    private fun fetchFailure(
        server: MockWebServer,
        budget: ModelDirectory.FetchBudget = ModelDirectory.FetchBudget(),
        client: OkHttpClient = OkHttpClient(),
    ): ModelsDevSnapshot.FetchException = assertThrows(ModelsDevSnapshot.FetchException::class.java) {
        runBlocking {
            ModelDirectory.fetchBody(
                client = client,
                url = server.url("/models.json").toString(),
                budget = budget,
            )
        }
    }

    private fun trackingClient(
        call: AtomicReference<Call?>,
        canceledAtBodyCompletion: AtomicReference<Boolean?>? = null,
    ): OkHttpClient = OkHttpClient.Builder()
        .eventListenerFactory { observed ->
            call.set(observed)
            if (canceledAtBodyCompletion == null) {
                EventListener.NONE
            } else {
                object : EventListener() {
                    override fun responseBodyEnd(call: Call, byteCount: Long) {
                        canceledAtBodyCompletion.set(call.isCanceled())
                    }

                    override fun responseFailed(call: Call, ioe: java.io.IOException) {
                        canceledAtBodyCompletion.set(call.isCanceled())
                    }
                }
            }
        }
        .build()

    private fun smallBudget(
        maxWireBytes: Long,
        maxDecodedBytes: Long,
        connectTimeoutMs: Long = 1_000,
        readIdleTimeoutMs: Long = 1_000,
        callTimeoutMs: Long = 2_000,
    ) = ModelDirectory.FetchBudget(
        maxWireBytes = maxWireBytes,
        maxDecodedBytes = maxDecodedBytes,
        connectTimeoutMs = connectTimeoutMs,
        readIdleTimeoutMs = readIdleTimeoutMs,
        callTimeoutMs = callTimeoutMs,
    )

    private fun gzip(body: String): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return output.toByteArray()
    }

    private suspend fun withServer(block: suspend (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            block(server)
        } finally {
            server.shutdown()
        }
    }

    private companion object {
        const val DEFAULT_BODY_CAP_BYTES = 4 * 1024 * 1024
        const val CANCEL_COMPLETION_BUDGET_MS = 750L
        val STRIPPED_HEADERS = listOf(
            "Authorization",
            "Proxy-Authorization",
            "Cookie",
            "x-api-key",
            "api-key",
            "x-goog-api-key",
        )
        val API_KEY_HEADER_VARIANTS = listOf("api-key", "x-goog-api-key", "X-Api-Key")
    }
}
