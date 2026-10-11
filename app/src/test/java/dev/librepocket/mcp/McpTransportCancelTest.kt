package dev.librepocket.mcp

import dev.librepocket.keystore.KeyVault
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

    @Test fun byteOverCapThrowsInsteadOfTruncatedOk() = runBlocking {
        val web = MockWebServer()
        web.start()
        val signals = CallSignals()
        try {
            // 300KiB chunked body（> 256KiB 位元組上限）：必須拋 McpResponseTooLarge，
            // 先 cancel 再拋，不回截斷半 JSON；絕不映射 OK。
            web.enqueue(MockResponse().setResponseCode(200).setChunkedBody("y".repeat(300 * 1024), 8192))
            val outcome = withTimeoutOrNull(5_000) {
                runCatching {
                    transport(signals).postJson(web.url("/").toString(), "sekret", "{}", McpTimeouts.DEFAULT_MS)
                }
            }
            assertNotNull("超大 body 必須在 5s 內拋出（不等 EOF 排空）", outcome)
            val failure = outcome!!.exceptionOrNull()
            assertTrue("位元組超量必須拋 McpResponseTooLarge（非截斷 OK），實際=$outcome", failure is McpResponseTooLarge)
            assertCallCancelled(signals, "位元組超量 terminal")
            assertEquals(1, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test fun charOverCapThrowsInsteadOfTruncatedOk() = runBlocking {
        val web = MockWebServer()
        web.start()
        val signals = CallSignals()
        try {
            // 200KiB ASCII（204800 位元組 < 256KiB，但 204800 字元 > 64k 字元上限）：
            // 解碼後超量同樣拋 McpResponseTooLarge，不回截斷 JSON。
            web.enqueue(MockResponse().setResponseCode(200).setChunkedBody("y".repeat(200 * 1024), 8192))
            val outcome = withTimeoutOrNull(10_000) {
                runCatching {
                    transport(signals).postJson(web.url("/").toString(), "sekret", "{}", McpTimeouts.DEFAULT_MS)
                }
            }
            assertNotNull("字元超量必須拋出", outcome)
            val failure = outcome!!.exceptionOrNull()
            assertTrue("字元超量必須拋 McpResponseTooLarge（非截斷 OK），實際=$outcome", failure is McpResponseTooLarge)
            assertEquals(1, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test fun cjkLegalJsonPreservedWithoutTruncation() = runBlocking {
        val web = MockWebServer()
        web.start()
        val signals = CallSignals()
        try {
            // Review fixture：text = "繁".repeat(22000)，整體 JSON 約 22089 字元 / 66089 位元組。
            // 舊 64k 位元組 cap 會在多位元組中間切開並誤報 OK；新雙上限（256KiB + 64k 字元）必須完整保留。
            val cjk = "繁".repeat(22_000)
            val body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"$cjk\"}],\"isError\":false}}"
            assertTrue("fixture 必須小於新雙上限（否則測錯分支）", body.toByteArray(Charsets.UTF_8).size < McpTransport.RESPONSE_MAX_BYTES)
            assertTrue("fixture 必須小於字元上限", body.length < McpTransport.RESPONSE_MAX_CHARS)
            web.enqueue(MockResponse().setResponseCode(200).setBody(body))
            val got = withTimeoutOrNull(5_000) {
                transport(signals).postJson(web.url("/").toString(), "sekret", "{}", McpTimeouts.DEFAULT_MS)
            }
            assertNotNull("合法 CJK JSON 必須完整回傳", got)
            assertEquals(200, got!!.first)
            assertEquals("CJK JSON 必須逐字完整（無截斷、無 replacement character）", body, got.second)
            assertFalse("完整回傳不得含 replacement character", got.second!!.contains("\uFFFD"))
            assertEquals(1, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test fun emojiLegalJsonPreservedWithoutSplittingSurrogates() = runBlocking {
        val web = MockWebServer()
        web.start()
        val signals = CallSignals()
        try {
            // emoji 4 bytes / 2 UTF-16 單位："😀".repeat(10000) = 20000 字元 / 40000 位元組。
            // 舊 64k 位元組可容納但新測試確保 surrogate pair 不被切開；更大數量由位元組上限測試覆蓋。
            val emoji = "😀".repeat(10_000)
            val body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"$emoji\"}],\"isError\":false}}"
            web.enqueue(MockResponse().setResponseCode(200).setBody(body))
            val got = withTimeoutOrNull(5_000) {
                transport(signals).postJson(web.url("/").toString(), "sekret", "{}", McpTimeouts.DEFAULT_MS)
            }
            assertNotNull("合法 emoji JSON 必須完整回傳", got)
            assertEquals(body, got!!.second)
            assertFalse("不得含 replacement character", got.second!!.contains("\uFFFD"))
            // Surrogate pair 完整：尾端不得是孤立 high/low surrogate。
            val text = got.second!!
            val last = text[text.length - 20]
            assertFalse("不得以孤立 surrogate 結尾", Character.isHighSurrogate(last) && text.endsWith(last.toString()))
            assertEquals(1, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test fun multibyteSplitNeverProducesReplacementCharacter() = runBlocking {
        val web = MockWebServer()
        web.start()
        val signals = CallSignals()
        try {
            // 在 256KiB 邊界上切開 "繁"（3 bytes）：前綴 262143 個 'a' + "繁" = 262146 位元組。
            // 第 262144 位元組正好是 "繁" 的首 byte；舊碼回截斷字串尾帶 U+FFFD，新碼必須拋。
            val payload = "a".repeat((McpTransport.RESPONSE_MAX_BYTES - 1).toInt()) + "繁"
            assertTrue("fixture 必須超位元組上限", payload.toByteArray(Charsets.UTF_8).size > McpTransport.RESPONSE_MAX_BYTES)
            web.enqueue(MockResponse().setResponseCode(200).setChunkedBody(payload, 8192))
            val outcome = withTimeoutOrNull(5_000) {
                runCatching {
                    transport(signals).postJson(web.url("/").toString(), "sekret", "{}", McpTimeouts.DEFAULT_MS)
                }
            }
            assertNotNull("邊界切開必須拋出（不等 EOF）", outcome)
            val failure = outcome!!.exceptionOrNull()
            assertTrue("邊界切開必須拋 McpResponseTooLarge（不得回含 U+FFFD 的截斷字串），實際=$outcome", failure is McpResponseTooLarge)
            assertCallCancelled(signals, "邊界截斷 terminal")
            assertEquals(1, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test fun exactCapStallReturnsPromptlyWithoutWaitingDeadline() = runBlocking {
        // 送滿位元組上限後 stall（不送 chunk 終止塊、不關 socket）：達 cap 立即拋，
        // 不得為等第 257KiB 個位元組卡到總 deadline（DEFAULT 20s；舊 +1 探針行為）。
        val cap = McpTransport.RESPONSE_MAX_BYTES.toInt()
        val serverSocket = java.net.ServerSocket(0)
        val port = serverSocket.localPort
        val serverThread = Thread {
            try {
                val socket = serverSocket.accept()
                socket.use { s ->
                    s.soTimeout = 10_000
                    val input = s.getInputStream()
                    // 讀請求頭直到空行（小 body 已進 socket buffer，不細讀 body）。
                    val header = java.io.ByteArrayOutputStream()
                    var last4 = ByteArray(0)
                    while (true) {
                        val b = input.read()
                        if (b == -1) break
                        header.write(b)
                        last4 = (last4 + b.toByte()).takeLast(4).toByteArray()
                        if (last4.size == 4 &&
                            last4[0] == '\r'.code.toByte() && last4[1] == '\n'.code.toByte() &&
                            last4[2] == '\r'.code.toByte() && last4[3] == '\n'.code.toByte()
                        ) break
                        if (header.size() > 64 * 1024) break
                    }
                    val out = s.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Type: application/json\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    out.flush()
                    val chunk = "a".repeat(8192)
                    var sent = 0
                    while (sent < cap) {
                        val n = minOf(8192, cap - sent)
                        out.write((n.toString(16) + "\r\n").toByteArray(Charsets.US_ASCII))
                        out.write(chunk.substring(0, n).toByteArray(Charsets.US_ASCII))
                        out.write("\r\n".toByteArray(Charsets.US_ASCII))
                        out.flush()
                        sent += n
                    }
                    // stall：不送 terminal 0-chunk，不關，舊碼在此卡 probe 讀直到 deadline。
                    Thread.sleep(10_000)
                }
            } catch (_: Exception) {
            }
        }
        serverThread.isDaemon = true
        serverThread.start()
        try {
            val start = System.nanoTime()
            val outcome = withTimeoutOrNull(8_000) {
                runCatching {
                    McpTransport().postJson("http://127.0.0.1:$port/", "sekret", "{}", McpTimeouts.DEFAULT_MS)
                }
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000L
            assertNotNull("達 cap stall 必須在 8s 內拋出（不等總 deadline 20s；舊碼此時仍卡在 probe 讀）", outcome)
            val failure = outcome!!.exceptionOrNull()
            assertTrue("stall 必須拋 McpResponseTooLarge，實際=$outcome", failure is McpResponseTooLarge)
            assertTrue("必須不等總 deadline（實際 ${elapsedMs}ms）", elapsedMs < 8_000L)
        } finally {
            try {
                serverSocket.close()
            } catch (_: Exception) {
            }
        }
    }

    @Test fun serverTruncatedCallMapsToProtocolNotOk() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // tools/call 回超大 body：server 必須回 PROTOCOL + 計熔斷一次，絕不回 OK(null) 假成功。
            web.enqueue(MockResponse().setResponseCode(200).setChunkedBody("z".repeat(300 * 1024), 8192))
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            val got = withTimeoutOrNull(10_000) { server.callTool("t") }
            assertNotNull("超量 callTool 必須回 PROTOCOL（非 null/例外/OK）", got)
            assertEquals(McpStatus.PROTOCOL, got!!.status)
            assertNull("PROTOCOL 不得帶 resultJson", got.resultJson)
            assertEquals("截斷必須計入熔斷失敗一次", 1, breaker.failureCount())
            assertFalse(breaker.isOpen())
            assertEquals(1, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test fun serverCjkCallPreservesDataAndOk() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // 同 cjk fixture：server 必須回 OK 且 resultJson 含完整 22000 個 "繁"，breaker 歸零。
            val cjk = "繁".repeat(22_000)
            val body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"$cjk\"}],\"isError\":false}}"
            web.enqueue(MockResponse().setResponseCode(200).setBody(body))
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            val got = withTimeoutOrNull(5_000) { server.callTool("t") }
            assertNotNull("合法 CJK callTool 必須回 OK", got)
            assertEquals(McpStatus.OK, got!!.status)
            val resultJson = requireNotNull(got.resultJson) { "OK 必須帶 resultJson" }
            assertTrue("resultJson 必須含完整 CJK（無截斷）", resultJson.contains(cjk))
            assertFalse("不得含 replacement character", resultJson.contains("\uFFFD"))
            assertEquals("成功不得計熔斷", 0, breaker.failureCount())
            assertEquals(1, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test fun serverIncompleteJsonMapsToProtocolNotOk() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // 上限內但語法不完整的半 JSON（HTTP 200 完整送達、無傳輸錯誤）：
            // parseCall 雙缺必須拋，server 回 PROTOCOL + 計熔斷，絕不回 OK(null) 假成功。
            web.enqueue(MockResponse().setResponseCode(200).setBody("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":"))
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            val got = withTimeoutOrNull(5_000) { server.callTool("t") }
            assertNotNull("半 JSON 必須回 PROTOCOL（非 null/例外/OK）", got)
            assertEquals(McpStatus.PROTOCOL, got!!.status)
            assertNull("PROTOCOL 不得帶 resultJson", got.resultJson)
            assertEquals("半 JSON 必須計入熔斷失敗一次", 1, breaker.failureCount())
            assertFalse(breaker.isOpen())
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

    @Test fun parentDeadlinePropagatesInsteadOfNull() = runBlocking {
        val web = MockWebServer()
        web.start()
        val signals = CallSignals()
        try {
            // 父層 withTimeout(800ms) 先於本層 15s 到期：必須拋 TimeoutCancellationException，
            // 不得吞為 null（否則呼叫方誤判 TIMEOUT 並誤增熔斷）。
            web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val start = System.nanoTime()
            val outcome = runCatching {
                withTimeout(800) {
                    McpTransport(signaledClient(signals)).postJson(
                        web.url("/").toString(),
                        "sekret",
                        "{}",
                        McpTimeouts.DEFAULT_MS,
                    )
                }
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000L
            val failure = outcome.exceptionOrNull()
            assertTrue(
                "父層 deadline 必須透傳 TimeoutCancellationException（非 null），實際=$outcome",
                failure is TimeoutCancellationException,
            )
            assertTrue("父層 deadline 應約 800ms 觸發（實際 ${elapsedMs}ms），不得等本層 15s", elapsedMs < 5_000L)
            assertCallCancelled(signals, "父層 deadline")
            assertEquals(1, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test fun serverParentDeadlineDoesNotTripBreaker() = runBlocking {
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
            // listTools 父層 deadline：透傳、不計熔斷、不映射 TIMEOUT。
            val listOutcome = runCatching {
                withTimeout(800) { server.listTools(McpTimeouts.DEFAULT_MS) }
            }
            val listFailure = listOutcome.exceptionOrNull()
            assertTrue(
                "listTools 父層 deadline 必須透傳 TimeoutCancellationException，實際=$listOutcome",
                listFailure is TimeoutCancellationException,
            )
            // callTool 父層 deadline：同上。
            val callOutcome = runCatching {
                withTimeout(800) { server.callTool("t", "{}", McpTimeouts.DEFAULT_MS) }
            }
            val callFailure = callOutcome.exceptionOrNull()
            assertTrue(
                "callTool 父層 deadline 必須透傳 TimeoutCancellationException，實際=$callOutcome",
                callFailure is TimeoutCancellationException,
            )
            assertEquals("父層 deadline 不得計入熔斷失敗", 0, breaker.failureCount())
            assertFalse(breaker.isOpen())
            assertEquals(2, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test fun serverOwnDeadlineMapsToTimeoutAndTripsBreaker() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // 本層 deadline（MIN 15s）到期：回 TIMEOUT 且計入熔斷一次。
            // 對照組：父層 deadline 測試必須拋例外且不計熔斷（見上兩測試）。
            web.enqueue(MockResponse().setResponseCode(200).setBody("{\"jsonrpc\":\"2.0\"}").setBodyDelay(20, TimeUnit.SECONDS))
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            val got = withTimeoutOrNull(18_000) { server.listTools(McpTimeouts.MIN_MS) }
            assertNotNull("本層 deadline 到期必須回 TIMEOUT（非 null/例外）", got)
            assertEquals(McpStatus.TIMEOUT, got!!.status)
            assertEquals("本層超時應計入熔斷失敗一次", 1, breaker.failureCount())
            assertFalse(breaker.isOpen())
            assertEquals(1, web.requestCount)
        } finally {
            web.shutdown()
        }
    }
}
