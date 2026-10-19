package dev.librepocket.mcp

import dev.librepocket.keystore.KeyVault
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage4 C-11／D-04：熔斷器併發正確性與公開契約面。
 *
 * - 併發失敗計數無丟失（[McpCircuitBreaker] 全入口 `@Synchronized`：8×250
 *   併發 `onFailure` 恰記 2000，未達閾值不得開路）；
 * - 開路期間併發 hammer：全部短路 CIRCUIT_OPEN、**不發 HTTP、不碰 vault**
 *   （vault 讀數與請求數在 hammer 前後完全不變）；
 * - D-04：公開契約面恰為 `canCall`/`isOpen`/`onSuccess`/`onNeutral`/
 *   `onFailure`/`failureCount`——曾存在的 `onStatus`/`reset` 為零呼叫死碼已刪，
 *   重加即紅（防死碼復活）。
 *
 * 全程 MockWebServer（loopback），不連外網。
 */
class McpCircuitBreakerConcurrencyTest {

    /** 可計數 vault：hammer 期間「一次都不可被讀」是核心斷言。 */
    private class CountingVault : KeyVault {
        val reads = AtomicInteger()
        override suspend fun putKey(providerId: String, apiKey: CharArray) = Unit
        override suspend fun getKey(providerId: String): CharArray? {
            reads.incrementAndGet()
            return "sekret-token-1".toCharArray()
        }
        override suspend fun deleteKey(providerId: String) = Unit
        override suspend fun hasKey(providerId: String): Boolean = true
    }

    private val threads = 8
    private val perThread = 250

    @Test fun concurrentFailuresAreCountedWithoutLostUpdates() {
        // C-11：非同步安全的計數會在 2000 併發遞增下丟更新（<2000）；
        // 閾值設 10_000 使 hammer 期間永不開路，純驗計數守恆。
        val breaker = McpCircuitBreaker(maxFailures = 10_000, openMs = 60_000L, clockMs = { 0L })
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        try {
            repeat(threads) {
                pool.execute {
                    start.await()
                    repeat(perThread) { breaker.onFailure() }
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue("併發 hammer 必須在 10s 內完成", done.await(10, TimeUnit.SECONDS))
            assertEquals(
                "8×250 併發 onFailure 不得丟計數（@Synchronized）",
                threads * perThread,
                breaker.failureCount(),
            )
            assertFalse("未達 maxFailures=10_000 不得開路", breaker.isOpen())
        } finally {
            pool.shutdown()
        }
    }

    @Test fun openCircuitShortCircuitsConcurrentHammerWithoutHttpOrVaultReads() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // maxFailures=1：一發 500 即開路。
            web.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            val vault = CountingVault()
            val breaker = McpCircuitBreaker(maxFailures = 1, openMs = 600_000L, clockMs = { 0L })
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = vault,
                transport = McpTransport(),
                breaker = breaker,
                clockMs = { 0L },
            )
            assertEquals(McpStatus.TRANSPORT, server.callTool("t", "{}").status)
            assertTrue(breaker.isOpen())
            val readsBefore = vault.reads.get()
            val requestsBefore = web.requestCount
            assertEquals(1, readsBefore)
            assertEquals(1, requestsBefore)

            // C-11 hammer：8 執行緒 ×（250 callTool + 250 listTools）全在開路期間。
            val statuses = java.util.concurrent.ConcurrentLinkedQueue<McpStatus>()
            val pool = Executors.newFixedThreadPool(threads)
            val start = CountDownLatch(1)
            val done = CountDownLatch(threads)
            try {
                repeat(threads) {
                    pool.execute {
                        start.await()
                        runBlocking {
                            repeat(perThread) {
                                statuses.add(server.callTool("t", "{}").status)
                                statuses.add(server.listTools().status)
                            }
                        }
                        done.countDown()
                    }
                }
                start.countDown()
                assertTrue("開路 hammer 必須在 15s 內完成", done.await(15, TimeUnit.SECONDS))
            } finally {
                pool.shutdown()
            }

            assertEquals(
                "全部呼叫都必須短路為 CIRCUIT_OPEN",
                threads * perThread * 2,
                statuses.size,
            )
            assertTrue(
                "不得混入其他狀態（開路期間不可能發送或成功）",
                statuses.all { it == McpStatus.CIRCUIT_OPEN },
            )
            assertEquals("開路期間不得再讀 vault", readsBefore, vault.reads.get())
            assertEquals("開路期間不得再發 HTTP", requestsBefore, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test fun breakerPublicSurfaceIsExactlyTheContractedApi() {
        // D-04：onStatus／reset 為零生產零測試呼叫的死碼已刪（本測為負向釘）——
        // 任何人重加任何公開方法（含復活 onStatus/reset）此測即紅，
        // 迫使重新走「是否有呼叫方」的審查而非靜默累積死碼。
        val surface = McpCircuitBreaker::class.java.methods
            .filter { it.declaringClass == McpCircuitBreaker::class.java }
            // isSynthetic：未來若實作介面，bridge 方法（synthetic）不得誤入
            // 契約面比對（真正的公開方法一律非 synthetic，釘面意圖不變）。
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet()
        assertEquals(
            "契約面 = 三事件（onSuccess/onNeutral/onFailure）＋三查詢（canCall/isOpen/failureCount）",
            setOf("canCall", "isOpen", "onSuccess", "onNeutral", "onFailure", "failureCount"),
            surface,
        )
    }
}
