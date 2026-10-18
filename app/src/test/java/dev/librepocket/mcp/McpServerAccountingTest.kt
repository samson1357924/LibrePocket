package dev.librepocket.mcp

import dev.librepocket.keystore.KeyVault
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage3 Server 記帳（accounting）契約：
 * - socket 讀超時（非本層 deadline）映射 TIMEOUT（非 TRANSPORT）＋計熔斷；
 * - Bearer 可列印 ASCII 預檢（控制字元／非 ASCII／空 → UNAUTHORIZED、不發送、不計熔斷）；
 * - vault getKey 拋例外不崩潰（→ UNAUTHORIZED、不發送、不計熔斷）；
 * - key 用後即擦（CharArray 填零，呼叫方拿到的副本仍在但原陣列已清）。
 *
 * 全程 MockWebServer（回環），不連外網。
 */
class McpServerAccountingTest {

    private class FakeVault : KeyVault {
        override suspend fun putKey(providerId: String, apiKey: CharArray) = Unit
        override suspend fun getKey(providerId: String): CharArray? = "sekret-token-1".toCharArray()
        override suspend fun deleteKey(providerId: String) = Unit
        override suspend fun hasKey(providerId: String): Boolean = true
    }

    /** 回傳**同一個** CharArray 實例（測試保留參考以驗證用後即擦）。 */
    private class RefVault(private val key: CharArray) : KeyVault {
        override suspend fun putKey(providerId: String, apiKey: CharArray) = Unit
        override suspend fun getKey(providerId: String): CharArray? = key
        override suspend fun deleteKey(providerId: String) = Unit
        override suspend fun hasKey(providerId: String): Boolean = true
    }

    /** getKey 拋例外（模擬 keystore 錯誤／裝置鎖定）。 */
    private class ThrowingVault : KeyVault {
        override suspend fun putKey(providerId: String, apiKey: CharArray) = Unit
        override suspend fun getKey(providerId: String): CharArray? = error("keystore broken")
        override suspend fun deleteKey(providerId: String) = Unit
        override suspend fun hasKey(providerId: String): Boolean = true
    }

    private fun server(
        web: MockWebServer,
        transport: McpTransport,
        breaker: McpCircuitBreaker = McpCircuitBreaker(),
        vault: KeyVault = FakeVault(),
    ): McpServer = McpServer(
        config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
        keyVault = vault,
        transport = transport,
        breaker = breaker,
    )

    // ---- socket 讀超時 → TIMEOUT（非 TRANSPORT） ----

    @Test fun socketReadTimeoutMapsToTimeoutNotTransport() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // 延遲回應頭 2s，注入 readTimeout=300ms 的 client：OkHttp socket timeout
            // 遠早於本層 deadline（MIN 15s），確保 SocketTimeoutException 先觸發到達 server 層。
            web.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeadersDelay(2, TimeUnit.SECONDS)
                    .setBody("{}"),
            )
            val fastRead = OkHttpClient.Builder()
                .readTimeout(300, TimeUnit.MILLISECONDS)
                .build()
            val breaker = McpCircuitBreaker()
            val s = server(web, McpTransport(fastRead), breaker)
            val got = withTimeoutOrNull(8_000) { s.listTools(McpTimeouts.MIN_MS) }
            assertNotNull("8s 內必須回傳（socket timeout 300ms 應先觸發）", got)
            assertEquals(
                "socket 讀超時必須映射 TIMEOUT（非 TRANSPORT）",
                McpStatus.TIMEOUT,
                got!!.status,
            )
            assertEquals("socket 超時應計入熔斷失敗一次", 1, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun socketReadTimeoutOnCallToolMapsToTimeout() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            web.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeadersDelay(2, TimeUnit.SECONDS)
                    .setBody("{}"),
            )
            val fastRead = OkHttpClient.Builder()
                .readTimeout(300, TimeUnit.MILLISECONDS)
                .build()
            val breaker = McpCircuitBreaker()
            val s = server(web, McpTransport(fastRead), breaker)
            val got = withTimeoutOrNull(8_000) { s.callTool("t", "{}", McpTimeouts.MIN_MS) }
            assertNotNull(got)
            assertEquals("callTool socket 超時映射 TIMEOUT", McpStatus.TIMEOUT, got!!.status)
            assertEquals(1, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    // ---- Bearer 可列印 ASCII 預檢（非此 → UNAUTHORIZED、不發送、不計熔斷） ----

    @Test fun nonPrintableBearerIsRejectedBeforeSending() = runBlocking {
        // CR/LF（header injection 向量）、非 ASCII、空字串、超長（>8192）四種 key 一律 UNAUTHORIZED。
        val cases = listOf(
            Triple("bad\r\nX-Evil: 1", "CR/LF", 0),
            Triple("t\u00f6k\u00e9n-non-ascii", "non-ascii", 0),
            Triple("", "empty", 0),
            Triple("a".repeat(8_193), "over-length", 0),
        )
        for ((badKey, label, _) in cases) {
            val web = MockWebServer()
            web.start()
            try {
                val breaker = McpCircuitBreaker()
                val s = server(web, McpTransport(), breaker, RefVault(badKey.toCharArray()))
                val got = withTimeoutOrNull(5_000) { s.listTools() }
                assertNotNull("key=$label 必須回傳", got)
                assertEquals(
                    "key=$label 必須 UNAUTHORIZED（不發送）",
                    McpStatus.UNAUTHORIZED,
                    got!!.status,
                )
                assertEquals("不得計熔斷（配置問題非傳輸失敗）", 0, breaker.failureCount())
                assertEquals("不得發送任何請求", 0, web.requestCount)
            } finally {
                web.shutdown()
            }
        }
    }

    @Test fun isPrintableAsciiBoundary() {
        // 直釘邊界：可列印 ASCII 0x20–0x7E 接受；範圍外／空／超長拒絕。
        assertTrue(isPrintableAscii("sekret-token-1"))
        assertTrue(isPrintableAscii("abc~DEL-safe")) // 0x7E 含
        assertTrue(isPrintableAscii(" ")) // 單一 space（0x20）合法
        assertFalse(isPrintableAscii("")) // 空
        assertFalse(isPrintableAscii("tab\there")) // TAB 0x09 控制字元
        assertFalse(isPrintableAscii("cr\rln")) // CR 0x0D
        assertTrue(isPrintableAscii("a b")) // 內嵌 space（0x20）合法——僅 CR/LF/控制字元危險
        assertFalse(isPrintableAscii("del\u007f")) // DEL 0x7F（>0x7E）
        assertFalse(isPrintableAscii("t\u00f6k\u00e9n")) // 非 ASCII
        assertFalse(isPrintableAscii("a".repeat(8_192 + 1))) // 超長
        assertTrue(isPrintableAscii("a".repeat(8_192))) // 恰等上限仍接受
    }

    @Test fun nonPrintableBearerOnCallToolIsRejectedBeforeSending() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            val breaker = McpCircuitBreaker()
            val s = server(web, McpTransport(), breaker, RefVault("bad\r\nkey".toCharArray()))
            val got = withTimeoutOrNull(5_000) { s.callTool("t", "{}") }
            assertNotNull(got)
            assertEquals(McpStatus.UNAUTHORIZED, got!!.status)
            assertEquals(0, breaker.failureCount())
            assertEquals(0, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test fun printableBearerStillSends() = runBlocking {
        // 對照組：可列印 ASCII key 照常發送（預檢不得誤殺合法 token）。
        val web = MockWebServer()
        web.start()
        try {
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}",
                ),
            )
            val s = server(web, McpTransport())
            val got = withTimeoutOrNull(5_000) { s.listTools() }
            assertNotNull(got)
            assertEquals(McpStatus.OK, got!!.status)
            assertEquals(1, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    // ---- vault getKey 拋例外不崩潰（→ UNAUTHORIZED、不發送、不計熔斷） ----

    @Test fun vaultExceptionMapsToUnauthorizedWithoutCrashing() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            val breaker = McpCircuitBreaker()
            val s = server(web, McpTransport(), breaker, ThrowingVault())
            val got = withTimeoutOrNull(5_000) { s.listTools() }
            assertNotNull("vault 例外必須回傳 UNAUTHORIZED（不得外拋崩潰）", got)
            assertEquals(McpStatus.UNAUTHORIZED, got!!.status)
            assertEquals("不得計熔斷", 0, breaker.failureCount())
            assertEquals("不得發送", 0, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test fun vaultExceptionOnCallToolMapsToUnauthorizedWithoutCrashing() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            val breaker = McpCircuitBreaker()
            val s = server(web, McpTransport(), breaker, ThrowingVault())
            val got = withTimeoutOrNull(5_000) { s.callTool("t", "{}") }
            assertNotNull("callTool vault 例外必須回傳（不得外拋）", got)
            assertEquals(McpStatus.UNAUTHORIZED, got!!.status)
            assertEquals(0, breaker.failureCount())
            assertEquals(0, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    // ---- key 用後即擦（CharArray 填零） ----

    @Test fun keyIsErasedAfterUseButRequestStillCarriesToken() = runBlocking {
        val web = MockWebServer()
        web.start()
        val real = "sekret-token-1".toCharArray()
        val vault = RefVault(real)
        try {
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}",
                ),
            )
            val s = server(web, McpTransport(), McpCircuitBreaker(), vault)
            val got = withTimeoutOrNull(5_000) { s.listTools() }
            assertNotNull(got)
            assertEquals(McpStatus.OK, got!!.status)
            // 請求仍帶原 token（String(key) 先複製，擦除在複製之後）。
            val req = web.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull("expected request within 5s", req)
            assertEquals("Bearer sekret-token-1", req!!.getHeader("Authorization"))
            // 用後即擦：vault 交出的原 CharArray 必須全部填零。
            assertTrue(
                "key \u7528\u5f8c\u5373\u64e6\uff1a\u539f CharArray \u5fc5\u9808\u5168\u70ba \\u0000",
                real.all { it == '\u0000' },
            )
        } finally {
            web.shutdown()
        }
    }
}
