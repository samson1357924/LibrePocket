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

    // ---- C-07：到期語義（查詢純唯讀、同點一致、無半開、延窗） ----

    @Test fun expiryQueriesAreReadOnlyAndConsistentRegardlessOfOrder() {
        // 舊碼 `isOpen()` 到期順手重置（唯讀查詢有副作用）：先呼叫 isOpen 的
        // 執行緒會讓後呼叫的 failureCount 從舊值變 0，結果依呼叫順序而異。
        // 現為：到期後 isOpen()==false／failureCount()==0（已清窗讀取視圖），
        // 且兩者皆不改狀態——把假時鐘撥回未到期可證明狀態仍在。
        var now = 0L
        val first = McpCircuitBreaker(maxFailures = 1, openMs = 100L, clockMs = { now })
        first.onFailure()
        assertTrue(first.isOpen())
        assertEquals(1, first.failureCount())
        now = 100L
        assertEquals("到期後 failureCount 讀為 0", 0, first.failureCount())
        assertFalse("到期後 isOpen 讀為 false", first.isOpen())
        now = 50L
        assertTrue("查詢不得重置：撥回未到期後視窗狀態仍在", first.isOpen())
        assertEquals("查詢不得清計數", 1, first.failureCount())

        // 相反查詢順序得到相同讀取視圖（無順序依賴）。
        var now2 = 0L
        val second = McpCircuitBreaker(maxFailures = 1, openMs = 100L, clockMs = { now2 })
        second.onFailure()
        now2 = 100L
        assertFalse(second.isOpen())
        assertEquals(0, second.failureCount())
    }

    @Test fun failureAfterExpiryStartsFreshRecoveryCountWithoutHalfOpen() {
        // C-07(c)：無半開態（記錄在案的最小取捨）。到期後第一個 canCall 全閉
        // 放行＋清零；仍壞的伺服器需再累積滿閾值才重開，期間全量發送。
        var now = 0L
        val breaker = McpCircuitBreaker(maxFailures = 3, openMs = 100L, clockMs = { now })
        repeat(3) { breaker.onFailure() }
        assertTrue(breaker.isOpen())
        now = 100L
        assertFalse("到期純查詢即讀為已清窗", breaker.isOpen())
        assertEquals(0, breaker.failureCount())
        // 到期後第一敗：恢復期計數從 1 起（不沿用舊計數、不立刻重開）。
        breaker.onFailure()
        assertEquals(1, breaker.failureCount())
        assertFalse("單敗不得立即重開（無半開單發探測）", breaker.isOpen())
        assertTrue("視窗已過即全閉放行", breaker.canCall())
        // 需再累積滿閾值才重開。
        breaker.onFailure()
        breaker.onFailure()
        assertTrue("再滿閾值才重開", breaker.isOpen())
    }

    @Test fun inFlightFailureBeforeExpiryPushesWindowOut() {
        // C-07(d)：開窗中（未到期前抵達）的 in-flight 失敗把窗口重切為 now+openMs
        // ——已達閾值時每敗重切，開窗可被持續失敗一路延長。
        var now = 0L
        val breaker = McpCircuitBreaker(maxFailures = 1, openMs = 1_000L, clockMs = { now })
        breaker.onFailure() // 開路至 1_000
        now = 500L
        breaker.onFailure() // 未到期 → 重切至 1_500
        now = 1_200L
        assertTrue("原窗已過但延長窗未過，仍開路", breaker.isOpen())
        assertFalse(breaker.canCall())
        now = 1_500L
        assertFalse("延長窗到點即閉", breaker.isOpen())
        assertTrue(breaker.canCall())
    }

    @Test fun backwardInjectedClockDoesNotShrinkOrExtendWindow() {
        // C-08：預設時鐘已改單調源；注入的假時鐘若回撥，到期判定以 `now >= openUntil`
        // 為準——回撥只會讓窗口「看起來更遠」，不會提早關窗（生產預設無回撥）。
        var now = 10_000L
        val breaker = McpCircuitBreaker(maxFailures = 1, openMs = 100L, clockMs = { now })
        breaker.onFailure() // openUntil = 10_100
        now = 5_000L // NTP 式回撥
        assertTrue("回撥不得提早關窗", breaker.isOpen())
        assertFalse(breaker.canCall())
    }

    @Test fun defaultMonotonicClockIsUptimeBasedNotEpoch() {
        // C-08：預設時鐘改 nanoTime 基準（開機 uptime），值遠小於 epoch 毫秒。
        // mutation（改回 System::currentTimeMillis 的 epoch 值）此測即紅。
        // nanoTime 規範允許任意起點（含負值），故不 pin 非負；只驗不倒退＋量級。
        val t1 = monotonicClockMs()
        val t2 = monotonicClockMs()
        assertTrue("單調時鐘不得倒退（t2 >= t1）", t2 >= t1)
        // 量級判準（避免 wall clock 跨 1ms 的機率性誤綠）：uptime 量級（至多數十天）
        // vs epoch 量級（約 1.7e12 ms），差值遠大於 1e6；改回 wall clock 時差值 ≈ 0
        // → 穩定轉紅。不用 `t < currentTimeMillis()` 的點比較。
        assertTrue(
            "單調時鐘不得是 epoch 量級（currentTimeMillis）：差值須遠大於 1e6 ms",
            System.currentTimeMillis() - t2 > 1_000_000L,
        )
    }

    @Test fun breakerDefaultClockFieldIsMonotonicUptimeBasedNotWallClock() {
        // C-08 接線 pin：預設參數必須實際指向 monotonicClockMs（不只是函式本身單調
        // ——mutation 把預設改回 System::currentTimeMillis 時，前測仍綠，本測會紅）。
        val field = McpCircuitBreaker::class.java.getDeclaredField("clockMs")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val clock = field.get(McpCircuitBreaker()) as () -> Long
        assertTrue(
            "McpCircuitBreaker 預設時鐘不得是 epoch 量級（System::currentTimeMillis）：" +
                "uptime 與 epoch 差值須遠大於 1e6 ms",
            System.currentTimeMillis() - clock() > 1_000_000L,
        )
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
