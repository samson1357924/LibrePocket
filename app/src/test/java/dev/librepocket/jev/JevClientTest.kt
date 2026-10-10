package dev.librepocket.jev

import dev.librepocket.keystore.KeyVault
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val JEV_BODY_LIMIT_BYTES = 32_768
private const val JEV_READ_AHEAD_ALLOWANCE_BYTES = 2 * 8 * 1024

/** Regression coverage for Jev bounded POST reads and Job-judged cancellation. */
class JevClientTest {
    private class FakeVault(keys: Map<String, String> = mapOf("jev" to "k-jeopardy-test")) : KeyVault {
        private val map = keys.mapValues { it.value.toCharArray() }.toMutableMap()
        override suspend fun putKey(providerId: String, apiKey: CharArray) {
            map[providerId] = apiKey.copyOf()
        }
        override suspend fun getKey(providerId: String): CharArray? = map[providerId]?.copyOf()
        override suspend fun deleteKey(providerId: String) {
            map.remove(providerId)
        }
        override suspend fun hasKey(providerId: String): Boolean = map.containsKey(providerId)
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
        val calls = CallSignals()

        private val countingInterceptor = object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val response = chain.proceed(chain.request())
                val delegate = response.body ?: return response
                val counted = object : ResponseBody() {
                    private val countedSource: BufferedSource = object : ForwardingSource(delegate.source()) {
                        override fun read(sink: Buffer, byteCount: Long): Long {
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

    private fun choiceJsonWithExactBytes(size: Int): String {
        val prefix = "{\"bestId\":\"ALARM\",\"confidences\":{\"ALARM\":0.9},\"abstain\":false,\"padding\":\""
        val suffix = "\"}"
        val paddingBytes = size - prefix.toByteArray(Charsets.UTF_8).size - suffix.toByteArray(Charsets.UTF_8).size
        require(paddingBytes >= 0)
        return prefix + "x".repeat(paddingBytes) + suffix
    }

    private fun scoreJsonWithExactBytes(size: Int): String {
        val prefix = "{\"score\":0.2,\"reasonCodes\":[\"LOW\"],\"padding\":\""
        val suffix = "\"}"
        val paddingBytes = size - prefix.toByteArray(Charsets.UTF_8).size - suffix.toByteArray(Charsets.UTF_8).size
        require(paddingBytes >= 0)
        return prefix + "x".repeat(paddingBytes) + suffix
    }

    private fun unknownLengthBody(text: String) = object : ResponseBody() {
        private val buf = Buffer().writeUtf8(text)
        override fun contentType(): MediaType? = "application/json; charset=utf-8".toMediaType()
        override fun contentLength(): Long = -1L
        override fun source(): BufferedSource = buf
    }

    private fun assertReadWithinBudget(signals: ResponseBodyReadSignals, budget: Int, context: String) {
        val consumed = signals.consumedBytes.get()
        assertTrue(
            "$context client-source bytes $consumed <= $budget + sentinel + read-ahead allowance",
            consumed <= budget.toLong() + 1L + JEV_READ_AHEAD_ALLOWANCE_BYTES,
        )
    }

    private suspend fun assertCallCancelled(signals: ResponseBodyReadSignals, context: String) {
        assertTrue(
            "$context cancels the actual OkHttp Call",
            withContext(Dispatchers.IO) { signals.calls.cancelled.await(1, TimeUnit.SECONDS) },
        )
    }

    @Test
    fun choiceExactCapKnownAndChunkedSucceed() = runBlocking {
        val body = choiceJsonWithExactBytes(JEV_BODY_LIMIT_BYTES)
        assertEquals(JEV_BODY_LIMIT_BYTES, body.toByteArray(Charsets.UTF_8).size)
        for (unknownLength in listOf(false, true)) {
            val server = MockWebServer()
            server.start()
            try {
                val response = if (unknownLength) {
                    MockResponse().setChunkedBody(body, 1024)
                } else {
                    MockResponse().setBody(body)
                }
                server.enqueue(response)
                val base = server.url("/").toString().trimEnd('/')
                val res = withTimeout(5_000) {
                    JevClient(base, FakeVault(), http = OkHttpClient())
                        .choose("明天七點設鬧鐘", JevIntents.defaultCandidates(), timeoutMs = 5_000)
                }
                val kind = if (unknownLength) "chunked" else "known-length"
                assertEquals("$kind exact-cap stays OK", JevStatus.OK, res.status)
                assertEquals("$kind exact-cap keeps bestId", "ALARM", res.bestId)
                assertEquals("$kind exact-cap uses one request", 1, server.requestCount)
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun choiceOverflowKnownAndChunkedFailsClosedAndStopsAtCap() = runBlocking {
        val oversized = choiceJsonWithExactBytes(JEV_BODY_LIMIT_BYTES + 64 * 1024)
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
                val base = server.url("/").toString().trimEnd('/')
                val res = withTimeout(5_000) {
                    JevClient(base, FakeVault(), http = signals.client())
                        .choose("明天七點設鬧鐘", JevIntents.defaultCandidates(), timeoutMs = 5_000)
                }
                assertEquals("$kind overflow maps to ERROR", JevStatus.ERROR, res.status)
                assertTrue("$kind overflow abstains", res.abstain)
                assertCallCancelled(signals, "$kind overflow")
                assertReadWithinBudget(signals, JEV_BODY_LIMIT_BYTES, "$kind overflow")
                if (unknownLength) {
                    assertTrue(
                        "$kind unknown-length path reads through the cap before rejecting",
                        signals.consumedBytes.get() >= JEV_BODY_LIMIT_BYTES.toLong(),
                    )
                }
                assertEquals("$kind overflow remains one request", 1, server.requestCount)
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun boundedReadOverflowCarriesTypedTooLargeCode() {
        // Known-length pre-check: contentLength alone rejects without consuming the wire.
        var cancelled = 0
        val oversized = choiceJsonWithExactBytes(JEV_BODY_LIMIT_BYTES + 1)
        val known = oversized.toResponseBody("application/json; charset=utf-8".toMediaType())
        try {
            readBoundedJevBody(known) { cancelled++ }
            throw AssertionError("known-length overflow should throw typed JevFailure")
        } catch (expected: JevFailure) {
            assertEquals(JevFailureCode.TOO_LARGE, expected.code)
            assertFalse("TOO_LARGE is non-retryable", expected.retryable)
        }
        assertEquals("known-length overflow cancels the call", 1, cancelled)

        // Unknown-length sentinel: exact cap passes, cap+1 rejects via the one-byte probe.
        val exact = choiceJsonWithExactBytes(JEV_BODY_LIMIT_BYTES)
        val exactText = readBoundedJevBody(unknownLengthBody(exact)) { throw AssertionError("exact cap must not cancel") }
        assertEquals(JEV_BODY_LIMIT_BYTES, exactText.toByteArray(Charsets.UTF_8).size)

        var chunkedCancelled = 0
        try {
            readBoundedJevBody(unknownLengthBody(oversized)) { chunkedCancelled++ }
            throw AssertionError("chunked LIMIT+1 should throw typed JevFailure")
        } catch (expected: JevFailure) {
            assertEquals(JevFailureCode.TOO_LARGE, expected.code)
            assertFalse("chunked TOO_LARGE is non-retryable", expected.retryable)
        }
        assertEquals("chunked overflow cancels the call", 1, chunkedCancelled)
    }

    @Test
    fun scoreOverflowMapsToTooLargeError() = runBlocking {
        val server = MockWebServer()
        val signals = ResponseBodyReadSignals()
        server.start()
        try {
            server.enqueue(MockResponse().setChunkedBody(scoreJsonWithExactBytes(JEV_BODY_LIMIT_BYTES + 1024), 1024))
            val base = server.url("/").toString().trimEnd('/')
            val res = withTimeout(5_000) {
                JevClient(base, FakeVault(), http = signals.client())
                    .score("alarm.create", JevContext(SideEffect.WRITE, "alarm", "goal"), timeoutMs = 5_000)
            }
            assertEquals(JevStatus.ERROR, res.status)
            assertTrue("overflow keeps a stable TOO_LARGE marker", res.reasonCodes.contains("TOO_LARGE"))
            assertCallCancelled(signals, "score overflow")
            assertReadWithinBudget(signals, JEV_BODY_LIMIT_BYTES, "score overflow")
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun cancellationDuringExecuteBeforeHeadersCancelsCall() = runBlocking {
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
            val base = server.url("/").toString().trimEnd('/')
            var failure: Throwable? = null
            var result: ChoiceResult? = null
            val active = launch(Dispatchers.IO) {
                try {
                    result = JevClient(base, FakeVault(), http = client)
                        .choose("明天七點設鬧鐘", JevIntents.defaultCandidates(), timeoutMs = 30_000)
                } catch (e: CancellationException) {
                    failure = e
                    throw e
                }
            }
            job = active
            val received = withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) }
            assertNotNull("request reaches server while execute waits for headers", received)
            withTimeout(1_500) { active.cancelAndJoin() }
            assertTrue("OkHttp Call.cancel was invoked", withContext(Dispatchers.IO) { signals.cancelled.await(1, TimeUnit.SECONDS) })
            assertTrue("external cancel stays cancellation, not ERROR", failure is CancellationException)
            assertNull("cancelled choose emits no result", result)
        } finally {
            job?.cancel()
            server.shutdown()
            job?.let { active -> withTimeoutOrNull(1_500) { active.join() } }
        }
    }

    @Test
    fun cancellationAfterHeadersCancelsBlockingBodyRead() = runBlocking {
        val server = MockWebServer()
        val signals = CallSignals()
        server.start()
        var job: Job? = null
        try {
            server.enqueue(
                MockResponse()
                    .setBody("{\"bestId\":\"ALARM\",\"confidences\":{\"ALARM\":0.9},\"abstain\":false}")
                    .setBodyDelay(10, TimeUnit.SECONDS),
            )
            val client = OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.MINUTES)
                .eventListener(signals.listener)
                .build()
            val base = server.url("/").toString().trimEnd('/')
            var failure: Throwable? = null
            var result: ChoiceResult? = null
            val active = launch(Dispatchers.IO) {
                try {
                    result = JevClient(base, FakeVault(), http = client)
                        .choose("明天七點設鬧鐘", JevIntents.defaultCandidates(), timeoutMs = 30_000)
                } catch (e: CancellationException) {
                    failure = e
                    throw e
                }
            }
            job = active
            assertTrue(
                "response headers arrive before body delay",
                withContext(Dispatchers.IO) { signals.headers.await(3, TimeUnit.SECONDS) },
            )
            withTimeout(1_500) { active.cancelAndJoin() }
            assertTrue("OkHttp Call.cancel was invoked", withContext(Dispatchers.IO) { signals.cancelled.await(1, TimeUnit.SECONDS) })
            assertTrue("external cancel stays cancellation, not ERROR", failure is CancellationException)
            assertFalse("OkHttp self-timeout must not masquerade as our cancel", failure is TimeoutCancellationException)
            assertNull("cancelled choose emits no result", result)
            assertEquals(1, server.requestCount)
        } finally {
            job?.cancel()
            server.shutdown()
            job?.let { active -> withTimeoutOrNull(1_500) { active.join() } }
        }
    }
}
