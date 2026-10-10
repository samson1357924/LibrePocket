package dev.librepocket.mcp

import dev.librepocket.keystore.KeyVault
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 傳輸取消迴歸測試（對齊 provider 取消語義）：
 * 無 headers / 有 headers 無 body / heartbeat 滴流 / terminal 不關 socket 四種情境皆可取消釋放；
 * 取消後連線可重用（不洩漏）；terminal 回應不等 EOF；超大 body 在上限處 terminal 截斷。
 *
 * 全程 MockWebServer，不連外網；覆蓋既有傳輸層常數（connect 5s / write 10s / read 30s +
 * [McpTimeouts] 15–30s），不虛構數字。
 */
class McpTransportCancelTest {

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

    /** 沿用 [McpTransport.defaultHttp] 再掛 EventListener（不改既有超時/重試語義）。 */
    private fun signaledClient(signals: CallSignals): OkHttpClient =
        McpTransport.defaultHttp().newBuilder()
            .eventListener(signals.listener)
            .build()

    private fun transport(signals: CallSignals) = McpTransport(signaledClient(signals))

    private class FakeVault : KeyVault {
        override suspend fun putKey(providerId: String, apiKey: CharArray) = Unit
        override suspend fun getKey(providerId: String): CharArray? = "sekret-token-1".toCharArray()
        override suspend fun deleteKey(providerId: String) = Unit
        override suspend fun hasKey(providerId: String): Boolean = true
    }

    /** 取消中的子協程結果收集器：吞掉取消只為斷言（測試 harness 用途，production 不吞）。 */
    private suspend fun CoroutineScope.runCancellablePost(
        web: MockWebServer,
        signals: CallSignals,
        timeoutMs: Long,
        awaitInFlight: suspend () -> Unit,
    ): AtomicReference<Result<Pair<Int, String?>?>> {
        val outcome = AtomicReference<Result<Pair<Int, String?>?>>()
        val active = launch(Dispatchers.IO) {
            outcome.set(runCatching { transport(signals).postJson(web.url("/").toString(), "sekret", "{}", timeoutMs) })
        }
        try {
            awaitInFlight()
            val joined = withTimeoutOrNull(2_000) {
                active.cancelAndJoin()
                true
            }
            assertTrue("取消必須立即中斷 blocking IO 並 join", joined == true)
        } finally {
            active.cancel()
            withTimeoutOrNull(1_500) { active.join() }
        }
        return outcome
    }

    private suspend fun awaitRequest(web: MockWebServer) {
        assertNotNull(
            "請求應已到達 server（取消發生在傳輸中）",
            withContext(Dispatchers.IO) { web.takeRequest(3, TimeUnit.SECONDS) },
        )
    }

    private fun assertCancelledOutcome(outcome: AtomicReference<Result<Pair<Int, String?>?>>, context: String) {
        val result = outcome.get()
        assertNotNull("$context 子協程應已產出結果", result)
        val failure = result!!.exceptionOrNull()
        assertTrue("$context 外部取消必須透傳 CancellationException（非 null/TIMEOUT/TRANSPORT），實際=$result", failure is CancellationException)
    }

    private suspend fun assertCallCancelled(signals: CallSignals, context: String) {
        assertTrue(
            "$context 必須呼叫實際 OkHttp Call.cancel()",
            withContext(Dispatchers.IO) { signals.cancelled.await(2, TimeUnit.SECONDS) },
        )
    }

    @Test fun cancelWhileWaitingHeadersCancelsCallAndPropagates() = runBlocking {
        val web = MockWebServer()
        web.start()
        val signals = CallSignals()
        try {
            web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val outcome = runCancellablePost(web, signals, McpTimeouts.DEFAULT_MS) { awaitRequest(web) }
            assertCancelledOutcome(outcome, "等 headers 取消")
            assertCallCancelled(signals, "等 headers 取消")
            assertEquals(1, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test fun cancelWhileWaitingBodyCancelsCallAndPropagates() = runBlocking {
        val web = MockWebServer()
        web.start()
        val signals = CallSignals()
        try {
            web.enqueue(MockResponse().setBody("{\"jsonrpc\":\"2.0\"}").setBodyDelay(10, TimeUnit.SECONDS))
            val outcome = runCancellablePost(web, signals, McpTimeouts.DEFAULT_MS) {
                assertTrue(
                    "headers 應先到達（取消發生在 body 讀取中）",
                    withContext(Dispatchers.IO) { signals.headers.await(3, TimeUnit.SECONDS) },
                )
            }
            assertCancelledOutcome(outcome, "等 body 取消")
            assertCallCancelled(signals, "等 body 取消")
            assertEquals(1, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test fun cancelDuringHeartbeatDripCancelsCallAndPropagates() = runBlocking {
        val web = MockWebServer()
        web.start()
        val signals = CallSignals()
        try {
            // 心跳式滴流：headers 立到，body 以 1KiB/s 滴 256KiB（約 256s，測試只取前段）。
            web.enqueue(
                MockResponse()
                    .setChunkedBody("x".repeat(256 * 1024), 1024)
                    .throttleBody(1024, 1, TimeUnit.SECONDS),
            )
            val outcome = runCancellablePost(web, signals, McpTimeouts.DEFAULT_MS) {
                assertTrue(
                    "headers 應先到達（取消發生在滴流讀取中）",
                    withContext(Dispatchers.IO) { signals.headers.await(3, TimeUnit.SECONDS) },
                )
                // 再等一小段，確保已進入 body 滴流讀取（deadline 30s 內仍在滴）。
                withContext(Dispatchers.IO) { Thread.sleep(1_500) }
            }
            assertCancelledOutcome(outcome, "心跳滴流中取消")
            assertCallCancelled(signals, "心跳滴流中取消")
            assertEquals(1, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test fun terminalResponseCompletesWithoutSocketClose() = runBlocking {
        val web = MockWebServer()
        web.start()
        val signals = CallSignals()
        try {
            // terminal 回應但 socket 保持開啟（KEEP_OPEN）：必須不等 EOF 即回傳。
            val body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}}"
            repeat(2) {
                web.enqueue(MockResponse().setResponseCode(200).setBody(body).setSocketPolicy(SocketPolicy.KEEP_OPEN))
            }
            val first = withTimeoutOrNull(5_000) {
                transport(signals).postJson(web.url("/").toString(), "sekret", "{}", McpTimeouts.DEFAULT_MS)
            }
            assertNotNull("terminal 回應必須在 socket 未關下即時回傳（不等 EOF）", first)
            assertEquals(200, first!!.first)
            assertEquals("terminal body 必須完整（非常態截斷）", body, first.second)
            // 連線可重用：第二步同樣即時成功（無洩漏）。
            val second = withTimeoutOrNull(5_000) {
                transport(signals).postJson(web.url("/").toString(), "sekret", "{}", McpTimeouts.DEFAULT_MS)
            }
            assertNotNull("取消/terminal 後連線必須可重用", second)
            assertEquals(200, second!!.first)
            assertEquals(2, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test fun cancelledCallReleasesConnectionForReuse() = runBlocking {
        val web = MockWebServer()
        web.start()
        val signals = CallSignals()
        try {
            web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[],\"isError\":false}}"
            web.enqueue(MockResponse().setResponseCode(200).setBody(body))
            val outcome = runCancellablePost(web, signals, McpTimeouts.DEFAULT_MS) { awaitRequest(web) }
            assertCancelledOutcome(outcome, "取消後重用")
            assertCallCancelled(signals, "取消後重用")
            // 同一 client 下一步成功：連線已釋放回池，無洩漏。
            val retry = withTimeoutOrNull(5_000) {
                transport(signals).postJson(web.url("/").toString(), "sekret", "{}", McpTimeouts.DEFAULT_MS)
            }
            assertNotNull("取消後同一傳輸必須可發下一步", retry)
            assertEquals(200, retry!!.first)
            assertEquals(2, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test fun oversizedBodyStopsAtCapWithoutWaitingEof() = runBlocking {
        val web = MockWebServer()
        web.start()
        val signals = CallSignals()
        try {
            // 200KiB chunked body：讀到 64k 上限即 terminal，先 cancel 再關，不等 EOF。
            web.enqueue(MockResponse().setResponseCode(200).setChunkedBody("y".repeat(200 * 1024), 1024))
            val got = withTimeoutOrNull(5_000) {
                transport(signals).postJson(web.url("/").toString(), "sekret", "{}", McpTimeouts.DEFAULT_MS)
            }
            assertNotNull("超大 body 必須在上限處 terminal 回傳", got)
            assertEquals(200, got!!.first)
            assertEquals(
                "超大 body 截斷至既有 64k 語義",
                McpTransport.RESPONSE_MAX_BYTES.toInt(),
                got.second?.length,
            )
            assertCallCancelled(signals, "超大 body terminal")
            assertEquals(1, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test fun totalDeadlineReturnsNullPromptly() = runBlocking {
        val web = MockWebServer()
        web.start()
        val signals = CallSignals()
        try {
            // body 20s 後到（< readTimeout 30s）：舊碼約 20s 才回（內容），
            // 真總 deadline 應在 clamp 下限 15s 到期即回 null，且走 call.cancel()。
            web.enqueue(MockResponse().setResponseCode(200).setBody("{\"jsonrpc\":\"2.0\"}").setBodyDelay(20, TimeUnit.SECONDS))
            val start = System.nanoTime()
            val got = withTimeoutOrNull(18_000) {
                McpTransport(signaledClient(signals)).postJson(
                    web.url("/").toString(),
                    "sekret",
                    "{}",
                    McpTimeouts.MIN_MS,
                )
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000L
            assertTrue("總 deadline 必須在 18s 內觸發（真 deadline，非等 IO 自然返回），實際 ${elapsedMs}ms", elapsedMs < 18_000L)
            assertNull("總 deadline 到期必須回 null（呼叫方映射 TIMEOUT）", got)
            assertTrue("deadline 觸發應約 15s（實際 ${elapsedMs}ms），過快為假陽性", elapsedMs >= 10_000L)
            assertCallCancelled(signals, "總 deadline")
            assertEquals(1, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test fun serverLevelCancellationPropagatesWithoutTrippingBreaker() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            repeat(2) { web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            // listTools 取消：透傳、不計熔斷。
            val listOutcome = AtomicReference<Result<McpListResult>>()
            val listJob = launch(Dispatchers.IO) { listOutcome.set(runCatching { server.listTools() }) }
            awaitRequest(web)
            assertTrue(withTimeoutOrNull(2_000) { listJob.cancelAndJoin(); true } == true)
            val listResult = listOutcome.get()
            assertNotNull("listTools 子協程應已產出結果", listResult)
            val listFailure = listResult!!.exceptionOrNull()
            assertTrue("listTools 取消必須透傳 CancellationException，實際=$listFailure", listFailure is CancellationException)
            // callTool 取消：透傳、不計熔斷。
            val callOutcome = AtomicReference<Result<McpCallResult>>()
            val callJob = launch(Dispatchers.IO) { callOutcome.set(runCatching { server.callTool("t") }) }
            awaitRequest(web)
            assertTrue(withTimeoutOrNull(2_000) { callJob.cancelAndJoin(); true } == true)
            val callResult = callOutcome.get()
            assertNotNull("callTool 子協程應已產出結果", callResult)
            val callFailure = callResult!!.exceptionOrNull()
            assertTrue("callTool 取消必須透傳 CancellationException，實際=$callFailure", callFailure is CancellationException)
            assertEquals("取消不得計入熔斷失敗", 0, breaker.failureCount())
            assertFalse(breaker.isOpen())
            assertEquals(2, web.requestCount)
        } finally {
            web.shutdown()
        }
    }
}
