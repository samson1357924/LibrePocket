package dev.librepocket.mcp

import dev.librepocket.keystore.KeyVault
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Stage4 A-03：HTTP 狀態 × 重試預算矩陣的端到端釘（純測試補強，不改生產碼）。
 *
 * 誠實分類（與現行實作一致，非理想化矩陣）：
 * - 300/301/303/304（＋既有 302/307/308 覆蓋）：redirect 是終端回應、不跟隨、恰一發
 *   → PROTOCOL＋計熔斷（`callTool` 與 `listTools` 兩條分支分別覆蓋）；
 * - 408/429：暫態 → TRANSPORT＋計熔斷，恰一發（one-shot body 擋 OkHttp 408 重送）；
 * - 503：TRANSPORT＋計熔斷；`Retry-After` 無論 delta-seconds／HTTP-date／垃圾值
 *   一律被 [RetryAfterStrip] 於 503 剝除 → 預算外重送不可達、恰一發；
 * - 429 帶 `Retry-After: 0` 不被自動重送（429 不走 503 剝離分支，OkHttp 亦不重送）；
 * - 406/415（N7 有意決策釘，McpServer listTools／THREAT_MODEL MCP 行）：本子集
 *   只送 `Accept: application/json`、不協商 event-stream，合規 server 回 406
 *   屬互通差異 → 歸暫態 TRANSPORT（非 PROTOCOL）＋計熔斷＋恰一發，3 連敗即開路。
 *
 * 每格只 enqueue 一發：任何隱性重送會拿不到第二份回應 → 佇列卡住 → 5s watchdog
 * 取消（協作式取消本身不搶占 blocking execute()；是 invokeOnCompletion
 * (onCancelling) 鈎子呼叫 Call.cancel() 中斷阻塞 IO，實測 ~5s 回 null）→ 斷言紅。
 * 全程 MockWebServer（loopback，允許 http），不連外網。
 */
class McpHttpBudgetMatrixTest {

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

    /** 5 秒統一 watchdog：server 未回（疑似隱性重送卡住佇列）即明確失敗
     *  （取消經 postJson 的 invokeOnCompletion 鈎子 Call.cancel() 中斷阻塞 IO）。 */
    private suspend fun <T> deadline(block: suspend () -> T): T? =
        withTimeoutOrNull(5_000) { block() }

    // ---- 3xx 終端：301/303 與 302/307/308 同待遇 ----

    @Test fun redirect301OnCallToolIsTerminalProtocolWithSingleRequest() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // 只 enqueue 一發：若政策回歸（自動跟隨或重送），第二請求無回應 →
            // watchdog 取消（Call.cancel() 中斷）→ deadline 回 null →
            // assertNotNull 紅。
            web.enqueue(redirect(301, web.url("/moved").toString()))
            val breaker = McpCircuitBreaker()
            val s = server(web, breaker)
            val got = deadline { s.callTool("t", "{}") }
            assertNotNull("301 必須在 5s 內原樣回到呼叫方（不跟隨、不重送）", got)
            assertEquals("301 必須映射 PROTOCOL（非 TRANSPORT/OK）", McpStatus.PROTOCOL, got!!.status)
            assertEquals("301 恰一發（body 不重放）", 1, web.requestCount)
            assertEquals("3xx 計熔斷一次（現行不變分類）", 1, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun redirect303OnListToolsIsTerminalProtocolWithSingleRequest() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            web.enqueue(redirect(303, web.url("/see-other").toString()))
            val breaker = McpCircuitBreaker()
            val s = server(web, breaker)
            val got = deadline { s.listTools() }
            assertNotNull("303 必須在 5s 內原樣回到呼叫方", got)
            assertEquals("303 必須映射 PROTOCOL", McpStatus.PROTOCOL, got!!.status)
            assertEquals("303 恰一發（303→GET 也不得被改寫送出）", 1, web.requestCount)
            assertEquals("3xx 計熔斷一次", 1, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun redirect300And304OnCallToolAreTerminalProtocolWithSingleRequest() = runBlocking {
        // 300（Multiple Choices）與 304（Not Modified）也是 3xx 終端回應：不得被
        // 特別當成 OK／無 body 成功，也不得跟隨或重送。逐一用獨立 MockWebServer
        // 與 breaker，避免前一 code 的熔斷狀態污染下一 code。
        for (code in listOf(300, 304)) {
            val web = MockWebServer()
            web.start()
            try {
                web.enqueue(redirect(code, web.url("/moved").toString()))
                val breaker = McpCircuitBreaker()
                val s = server(web, breaker)
                val got = deadline { s.callTool("t", "{}") }
                assertNotNull("$code 必須在 5s 內原樣回到呼叫方（不跟隨、不重送）", got)
                assertEquals("$code 必須映射 PROTOCOL（非 OK/TRANSPORT）", McpStatus.PROTOCOL, got!!.status)
                assertEquals("$code 恰一發（body 不重放、不跟隨 Location）", 1, web.requestCount)
                assertEquals("$code 計熔斷一次", 1, breaker.failureCount())
            } finally {
                web.shutdown()
            }
        }
    }

    @Test fun redirect300And304OnListToolsAreTerminalProtocolWithSingleRequest() = runBlocking {
        // 3xx 分支在 McpServer.listTools 與 callTool 各有一份；只測 callTool 會讓
        // listTools 對 300/304 的特例（例如把 304 當空成功）逃過。此列逐一用
        // 獨立 MockWebServer/breaker 覆蓋 listTools 的 300 與 304。
        for (code in listOf(300, 304)) {
            val web = MockWebServer()
            web.start()
            try {
                web.enqueue(redirect(code, web.url("/moved").toString()))
                val breaker = McpCircuitBreaker()
                val got = deadline { server(web, breaker).listTools() }
                assertNotNull("$code listTools 必須在 5s 內原樣回到呼叫方（不跟隨、不重送）", got)
                assertEquals("$code listTools 必須映射 PROTOCOL（非 OK/TRANSPORT）", McpStatus.PROTOCOL, got!!.status)
                assertEquals("$code listTools 恰一發（不跟隨 Location）", 1, web.requestCount)
                assertEquals("$code listTools 計熔斷一次", 1, breaker.failureCount())
            } finally {
                web.shutdown()
            }
        }
    }

    // ---- 408/429 暫態：不隱性重送 ----

    @Test fun http408OnCallToolDoesNotResend() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // 408 是 OkHttp 的 replay 候選分支（由 one-shot body 擋下）：
            // 只 enqueue 一發，任何重送都拿不到回應 → watchdog 取消（Call.cancel()
            // 中斷阻塞 IO）→ 紅。
            web.enqueue(MockResponse().setResponseCode(408).setBody("request timeout"))
            val breaker = McpCircuitBreaker()
            val s = server(web, breaker)
            val got = deadline { s.callTool("t", "{}") }
            assertNotNull("408 必須在 5s 內回傳（重送會卡住佇列）", got)
            assertEquals("408 暫態 → TRANSPORT", McpStatus.TRANSPORT, got!!.status)
            assertEquals("408 不得隱性重送", 1, web.requestCount)
            assertEquals("408 計熔斷一次", 1, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    // ---- 406/415 互通差異：有意決策歸暫態（McpServer C 行／THREAT_MODEL MCP 行）----

    @Test fun http406OnCallToolIsTransportCountingBreakerWithSingleRequest() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // 有意決策（N7 釘）：本子集只送 `Accept: application/json`、不協商
            // event-stream，合規 server 回 406 屬互通差異 → TRANSPORT（非
            // PROTOCOL、非 UNAUTHORIZED）＋計熔斷＋恰一發。
            web.enqueue(MockResponse().setResponseCode(406).setBody("not acceptable"))
            val breaker = McpCircuitBreaker()
            val s = server(web, breaker)
            val got = deadline { s.callTool("t", "{}") }
            assertNotNull("406 必須在 5s 內回傳", got)
            assertEquals("406 歸暫態 → TRANSPORT", McpStatus.TRANSPORT, got!!.status)
            assertEquals("406 恰一發", 1, web.requestCount)
            assertEquals("406 計熔斷一次", 1, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun http415OnListToolsIsTransportCountingBreakerWithSingleRequest() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // 同 406 決策、list 端（McpServer「其餘 4xx/5xx → TRANSPORT」分支，
            // 註解明列 406/415）：415 同待遇——計熔斷、單發。
            web.enqueue(MockResponse().setResponseCode(415).setBody("unsupported media type"))
            val breaker = McpCircuitBreaker()
            val s = server(web, breaker)
            val got = deadline { s.listTools() }
            assertNotNull("415 必須在 5s 內回傳", got)
            assertEquals("415 歸暫態 → TRANSPORT", McpStatus.TRANSPORT, got!!.status)
            assertEquals("415 恰一發", 1, web.requestCount)
            assertEquals("415 計熔斷一次", 1, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    // ---- 503 × Retry-After 各種值：一律剝除、不重送 ----

    @Test fun retryAfterHttpDateInPastDoesNotResendOn503() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // HTTP-date 形式（1999 過去時刻：若剝除失效，OkHttp 的 Retry-After
            // 解析與 followUp 是唯一可能的重送來源）→ 恰一發。
            web.enqueue(
                MockResponse().setResponseCode(503)
                    .addHeader("Retry-After", "Wed, 21 Oct 1999 07:28:00 GMT")
                    .setBody("unavailable"),
            )
            val breaker = McpCircuitBreaker()
            val s = server(web, breaker)
            val got = deadline { s.callTool("t", "{}") }
            assertNotNull("503+過去 HTTP-date 必須 5s 內回傳", got)
            assertEquals(McpStatus.TRANSPORT, got!!.status)
            assertEquals("過去 HTTP-date 不得觸發重送", 1, web.requestCount)
            assertEquals(1, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun retryAfterHttpDateInFutureDoesNotResendOn503() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // 未來 HTTP-date（2120）：delta-seconds 與 HTTP-date 兩種解讀都不得
            // 讓本層等待或重送——單發、即時回 TRANSPORT。
            web.enqueue(
                MockResponse().setResponseCode(503)
                    .addHeader("Retry-After", "Fri, 31 Dec 2120 23:59:59 GMT")
                    .setBody("unavailable"),
            )
            val breaker = McpCircuitBreaker()
            val s = server(web, breaker)
            val got = deadline { s.callTool("t", "{}") }
            assertNotNull("503+未來 HTTP-date 必須 5s 內回傳（不等待、不重送）", got)
            assertEquals(McpStatus.TRANSPORT, got!!.status)
            assertEquals("未來 HTTP-date 不得觸發重送", 1, web.requestCount)
            assertEquals(1, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun retryAfterGarbageValueDoesNotResendOn503() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // 垃圾值（非數字非日期）：解析失敗也不得放大成重送或例外。
            web.enqueue(
                MockResponse().setResponseCode(503)
                    .addHeader("Retry-After", "banana, soon-ish")
                    .setBody("unavailable"),
            )
            val breaker = McpCircuitBreaker()
            val s = server(web, breaker)
            val got = deadline { s.callTool("t", "{}") }
            assertNotNull("503+垃圾 Retry-After 必須 5s 內回傳", got)
            assertEquals(McpStatus.TRANSPORT, got!!.status)
            assertEquals("垃圾 Retry-After 不得觸發重送", 1, web.requestCount)
            assertEquals(1, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun retryAfterZeroOn429DoesNotResend() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // 429 不走 503 剝離分支（KDoc：429/408 不會被自動重送故無剝離）——
            // 即使帶 `Retry-After: 0` 亦恰一發（若 OkHttp 政策變動開始重送，此測紅）。
            web.enqueue(
                MockResponse().setResponseCode(429)
                    .addHeader("Retry-After", "0")
                    .setBody("slow down"),
            )
            val breaker = McpCircuitBreaker()
            val s = server(web, breaker)
            val got = deadline { s.callTool("t", "{}") }
            assertNotNull("429 必須 5s 內回傳", got)
            assertEquals("429 暫態 → TRANSPORT", McpStatus.TRANSPORT, got!!.status)
            assertEquals("429+Retry-After:0 不得觸發重送", 1, web.requestCount)
            assertEquals("429 計熔斷一次", 1, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }
}
