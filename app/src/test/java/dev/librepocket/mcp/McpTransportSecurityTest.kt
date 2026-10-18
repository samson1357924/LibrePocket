package dev.librepocket.mcp

import dev.librepocket.keystore.KeyVault
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Stage1 傳輸層單次預算契約（外部 review P1：初始 URL 預驗可被自動 redirect 繞過）。
 *
 * 契約：
 * - 3xx 為終端回應（不跟隨）：第二端點收到 0 個請求，`tools/call` body 不重放；
 *   [McpServer] 映射 PROTOCOL + 計熔斷；
 * - 注入的 permissive client（`followRedirects(true)`）一律經
 *   [McpTransport.transportClient] 強制套用同一政策；
 * - OkHttp 內部 `503 + Retry-After: 0` 預算外重送被剝除（非 idempotent call 只送一次）；
 * - 直調 [McpTransport.postJson] 到非回環明文在任何 socket 之前 fail-closed。
 *
 * 全程 MockWebServer（loopback，允許 http），不連外網。
 */
class McpTransportSecurityTest {

    private class FakeVault : KeyVault {
        override suspend fun putKey(providerId: String, apiKey: CharArray) = Unit
        override suspend fun getKey(providerId: String): CharArray? = "sekret-token-1".toCharArray()
        override suspend fun deleteKey(providerId: String) = Unit
        override suspend fun hasKey(providerId: String): Boolean = true
    }

    private fun server(web: MockWebServer, breaker: McpCircuitBreaker) = McpServer(
        config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
        keyVault = FakeVault(),
        transport = McpTransport(),
        breaker = breaker,
    )

    private fun redirect(code: Int, location: String) = MockResponse()
        .setResponseCode(code)
        .addHeader("Location", location)

    private fun okCall() = MockResponse().setResponseCode(200).setBody(
        "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
            "{\"content\":[{\"type\":\"text\",\"text\":\"x\"}],\"isError\":false}}",
    )

    // ---- 1. one-shot body tripwire：二次寫入硬失敗 ----

    @Test fun oneShotBodyTripwireFailsLoudOnSecondWrite() {
        // tripwire 是對未來回歸的硬閘（政策破壞 one-shot 保護時大聲失敗，
        // 不靜默重複副作用）；此處直接雙寫驗其存在——刪 check 即紅。
        val body = OneShotJsonBody("{\"a\":1}")
        val sink = okio.Buffer()
        body.writeTo(sink)
        assertEquals("{\"a\":1}", sink.readUtf8())
        try {
            body.writeTo(okio.Buffer())
            fail("one-shot body 二次寫入必須硬失敗（tripwire）")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("one-shot body replayed"))
        }
    }

    // ---- 2. cross-origin redirect 不跟隨：body 不外送（review P1）----

    @Test fun redirect307DoesNotReplayArgumentsToUnvalidatedOrigin() = runBlocking {
        val origin = MockWebServer()
        val target = MockWebServer()
        origin.start()
        target.start()
        try {
            origin.enqueue(redirect(307, target.url("/collect").toString()))
            target.enqueue(okCall())
            val breaker = McpCircuitBreaker()
            val server = server(origin, breaker)
            val got = withTimeoutOrNull(5_000) { server.callTool("send_email", "{\"q\":\"private\"}") }
            assertNotNull("5s 內未回傳（watchdog 到期或佇列阻塞）", got)
            assertEquals("307 是終端回應（不跟隨）", McpStatus.PROTOCOL, got!!.status)
            assertEquals("第二端點不得收到任何請求", 0, target.requestCount)
            assertEquals("origin 只收到初始那一發（無同源二次請求）", 1, origin.requestCount)
            assertEquals("重定向不得被當成功", 1, breaker.failureCount())
        } finally {
            origin.shutdown()
            target.shutdown()
        }
    }

    @Test fun redirect302DoesNotIssueGetToUnvalidatedOrigin() = runBlocking {
        val origin = MockWebServer()
        val target = MockWebServer()
        origin.start()
        target.start()
        try {
            origin.enqueue(redirect(302, target.url("/steal").toString()))
            // 預防性佇列：若政策回歸而被跟隨，target 會回 200 讓斷言明確失敗（而非 watchdog 超時）。
            target.enqueue(okCall())
            val breaker = McpCircuitBreaker()
            val server = server(origin, breaker)
            val list = withTimeoutOrNull(5_000) { server.listTools() }
            assertNotNull("5s 內未回傳（watchdog 到期或佇列阻塞）", list)
            assertEquals("302 必須是終端回應", McpStatus.PROTOCOL, list!!.status)
            assertEquals("302 不得被改寫成 GET 送出去", 0, target.requestCount)
            assertEquals(1, origin.requestCount)
            assertEquals(1, breaker.failureCount())
        } finally {
            origin.shutdown()
            target.shutdown()
        }
    }

    @Test fun sameOriginRedirectIsAlsoRefusedByPolicy() = runBlocking {
        // 契約是「一律不跟隨」（不只跨 origin）：hook 檔、path 搬移都必須顯式失敗，
        // 不在本層悄悄多打一發。
        val web = MockWebServer()
        web.start()
        try {
            web.enqueue(redirect(307, web.url("/other").toString()))
            val breaker = McpCircuitBreaker()
            val server = server(web, breaker)
            val got = withTimeoutOrNull(5_000) { server.callTool("t", "{}") }
            assertNotNull("5s 內未回傳（watchdog 到期或佇列阻塞）", got)
            assertEquals("307 是終端回應（不跟隨）", McpStatus.PROTOCOL, got!!.status)
            assertEquals("同 origin redirect 也不得跟隨", 1, web.requestCount)
            assertEquals(1, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    // ---- 3. 注入的 permissive client 一律正規化 ----

    @Test fun injectedClientWithRedirectsEnabledIsNeutralized() = runBlocking {
        val origin = MockWebServer()
        val target = MockWebServer()
        origin.start()
        target.start()
        try {
            origin.enqueue(redirect(307, target.url("/collect").toString()))
            target.enqueue(okCall())
            val permissive = OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = origin.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(permissive),
                breaker = breaker,
            )
            val got = withTimeoutOrNull(5_000) { server.callTool("t", "{}") }
            assertNotNull("5s 內未回傳（watchdog 到期或佇列阻塞）", got)
            assertEquals("注入 client 不得沿用其 redirect 政策", McpStatus.PROTOCOL, got!!.status)
            assertEquals("注入 client 不得沿用其 redirect 政策", 0, target.requestCount)
            assertEquals(1, origin.requestCount)
            assertEquals(1, breaker.failureCount())
        } finally {
            origin.shutdown()
            target.shutdown()
        }
    }

    // ---- 4. 503 + Retry-After:0 不在 OkHttp 內部重送（review F-02）----

    @Test fun retryAfterZeroDoesNotReplayToolsCall() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // 分路閘：RetryAfterStrip 專責 503+Retry-After:0；one-shot body 專責 408/421。
            // 此測驗可觀測結果：恰一發、狀態非 OK（任一閘回歸即紅）。
            web.enqueue(
                MockResponse().setResponseCode(503)
                    .addHeader("Retry-After", "0")
                    .setBody("unavailable"),
            )
            web.enqueue(okCall())
            val breaker = McpCircuitBreaker()
            val server = server(web, breaker)
            val got = withTimeoutOrNull(5_000) { server.callTool("t", "{}") }
            assertNotNull("5s 內未回傳（watchdog 到期或佇列阻塞）", got)
            assertEquals("503 必須原樣回到呼叫方（不被內部重送成 200）", McpStatus.TRANSPORT, got!!.status)
            assertEquals("非 idempotent tools/call 不得被預算外重送", 1, web.requestCount)
            assertEquals(1, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    // ---- 5. 直調 transport 的明文 fail-closed（KDoc「直調亦擋」）----

    @Test fun directPostJsonToInsecureEndpointThrowsBeforeAnySocket() = runBlocking {
        // TEST-NET-1（RFC 5737，預期不可路由）：若預驗失效，退化行為會是嘗試連線後失敗，
        // 而非靜默送出；本測只驗「任何 socket 之前」即拋。
        try {
            McpTransport().postJson(
                "http://192.0.2.1/mcp",
                "sekret-token-1",
                "{}",
                McpTimeouts.DEFAULT_MS,
            )
            fail("非回環明文端點必須在發送前拋 McpProtocol")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: insecure endpoint", e.message)
        }
    }

    // ---- 6. 3xx 契約端到端：list 與 call 皆 PROTOCOL，成功路徑不受影響 ----

    @Test fun redirectRefusedThenNextCallSucceedsNormally() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // 跨 scheme redirect 字串：修復後 OkHttp 在讀 Location 前即短路，
            // 不會解析 other.example（僅在政策回歸時才可能嘗試 DNS，該域名為保留字）。
            web.enqueue(redirect(308, "https://other.example/collect"))
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}",
                ),
            )
            val breaker = McpCircuitBreaker()
            val server = server(web, breaker)
            val list = withTimeoutOrNull(5_000) { server.listTools() }
            assertNotNull("5s 內未回傳（watchdog 到期或佇列阻塞）", list)
            assertEquals("3xx 必須映射 PROTOCOL（非 TRANSPORT/OK）", McpStatus.PROTOCOL, list!!.status)
            assertEquals(1, breaker.failureCount())
            val ok = withTimeoutOrNull(5_000) { server.listTools() }
            assertNotNull("5s 內未回傳", ok)
            assertEquals(McpStatus.OK, ok!!.status)
            assertEquals("3xx 只計一次失敗（非重複重送）", 2, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    // ---- 7. 單次預算的正向控制：正常 200 路徑仍完全可用 ----

    @Test fun happyPathStillSingleRoundTripWithBearer() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
                        "{\"content\":[{\"type\":\"text\",\"text\":\"done\"}],\"isError\":false}}",
                ),
            )
            val breaker = McpCircuitBreaker()
            val server = server(web, breaker)
            val got = withTimeoutOrNull(5_000) { server.callTool("t", "{\"q\":\"x\"}") }
            assertNotNull("5s 內未回傳", got)
            assertEquals(McpStatus.OK, got!!.status)
            assertEquals(1, web.requestCount)
            assertEquals(0, breaker.failureCount())
            val req = web.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull("expected request within 5s", req)
            assertEquals("Bearer sekret-token-1", req!!.getHeader("Authorization"))
            // 請求標頭契約（McpTransport 發送設定）。
            assertTrue(
                "Content-Type 必須為 application/json：${req.getHeader("Content-Type")}",
                req.getHeader("Content-Type")?.startsWith("application/json") == true,
            )
            assertEquals("application/json", req.getHeader("Accept"))
            assertEquals("POST", req.method)
        } finally {
            web.shutdown()
        }
    }

    // ---- 8. 單次預算元件的直接釘子（防回歸成隱性重送）----

    @Test fun transportClientIsIdempotentAndForcesAllPolicyKnobs() {
        // defaultHttp() 已套用 → 建構子再套用一次不得重複掛攔截器。
        val once = McpTransport.defaultHttp()
        assertEquals(
            "RetryAfterStrip 只能有一份（idempotent）",
            1,
            once.networkInterceptors.count { it is RetryAfterStrip },
        )
        val twice = McpTransport.transportClient(once)
        assertEquals(1, twice.networkInterceptors.count { it is RetryAfterStrip })

        // 注入的 permissive client：redirect/重試政策一律被覆寫。
        val permissive = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
        val enforced = McpTransport.transportClient(permissive)
        assertFalse("不得跟隨 redirect", enforced.followRedirects)
        assertFalse("不得跟隨 SSL redirect", enforced.followSslRedirects)
        assertFalse("不得重試連線失敗", enforced.retryOnConnectionFailure)
        assertEquals("Bearer 重試/cookie/cache 三個重送來源必須清空", Authenticator.NONE, enforced.authenticator)
        assertEquals(Authenticator.NONE, enforced.proxyAuthenticator)
        assertEquals(CookieJar.NO_COOKIES, enforced.cookieJar)
        assertNull("cache 必須清空（cached-503 不得繞過剝除）", enforced.cache)
    }

    @Test fun postBodyIsOneShotSoOkHttpNeverReplaysIt() {
        // one-shot 會讓 OkHttp 在 503/408/421/authenticator 任一 replay 分支直接放棄重送。
        val body = OneShotJsonBody("{\"jsonrpc\":\"2.0\"}")
        assertTrue("POST body 必須 one-shot", body.isOneShot())
        assertTrue("contentLength 必須反映實際位元組", body.contentLength() > 0)
        assertTrue(body.contentType().toString().contains("json"))
        // 對照：OkHttp 預設 RequestBody 非 one-shot（即回歸態，503/421 可重送）。
        val plain = "{}".toRequestBody("application/json".toMediaType())
        assertFalse("預設 body 可重送＝本層不可接受的回歸態", plain.isOneShot())
    }
}
