package dev.librepocket.mcp

import dev.librepocket.keystore.KeyVault
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage4 多伺服器聚合（[McpRegistry]）契約：
 * - `ids()`／`get()` 逐服索引（命中回同實例、缺席回 null）；
 * - `listAll()` 聚合各服獨立結果（一服失敗不牽連他服——逐服獨立熔斷）；
 * - 逐服 timeout 為上限（非總量），呼叫方另包總超時。
 *
 * 全程 MockWebServer（回環），不連外網。
 */
class McpRegistryTest {

    private class FakeVault : KeyVault {
        override suspend fun putKey(providerId: String, apiKey: CharArray) = Unit
        override suspend fun getKey(providerId: String): CharArray? = "sekret-token-1".toCharArray()
        override suspend fun deleteKey(providerId: String) = Unit
        override suspend fun hasKey(providerId: String): Boolean = true
    }

    private fun goodListBody(): String =
        """{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"search","description":"d","inputSchema":{"type":"object"}}]}}"""

    private fun server(web: MockWebServer, id: String): McpServer = McpServer(
        config = McpServerConfig(id = id, baseUrl = web.url("/").toString()),
        keyVault = FakeVault(),
        transport = McpTransport(),
        breaker = McpCircuitBreaker(),
    )

    @Test fun idsAndGetIndexRegisteredServers() {
        val webA = MockWebServer()
        val webB = MockWebServer()
        webA.start()
        webB.start()
        try {
            val a = server(webA, "a")
            val b = server(webB, "b")
            val reg = McpRegistry(mapOf("a" to a, "b" to b))
            assertEquals(setOf("a", "b"), reg.ids())
            // 命中回同實例（identity），缺席回 null。
            assertSame(a, reg.get("a"))
            assertSame(b, reg.get("b"))
            assertNull("未註冊 id 必須回 null", reg.get("zzz"))
        } finally {
            webA.shutdown()
            webB.shutdown()
        }
    }

    @Test fun emptyRegistryHasNoIds() {
        val reg = McpRegistry(emptyMap())
        assertTrue(reg.ids().isEmpty())
        assertNull(reg.get("a"))
    }

    @Test fun listAllAggregatesIndependentResults() = runBlocking {
        val webA = MockWebServer()
        val webB = MockWebServer()
        webA.start()
        webB.start()
        try {
            // A 服正常回 tools/list；B 服回畸形（tools 非陣列）→ 各自獨立結果。
            webA.enqueue(MockResponse().setResponseCode(200).setBody(goodListBody()))
            webB.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"jsonrpc":"2.0","id":1,"result":{"tools":"oops"}}"""),
            )
            val reg = McpRegistry(mapOf("a" to server(webA, "a"), "b" to server(webB, "b")))
            val got = withTimeoutOrNull(8_000) { reg.listAll() }
            assertNotNull("listAll 必須在 8s 內回傳", got)
            assertEquals(setOf("a", "b"), got!!.keys)
            assertEquals("A 服獨立成功（OK）", McpStatus.OK, got.getValue("a").status)
            assertEquals(1, got.getValue("a").tools.size)
            assertEquals("B 服畸形獨立失敗（PROTOCOL），不牽連 A", McpStatus.PROTOCOL, got.getValue("b").status)
            assertTrue(got.getValue("b").tools.isEmpty())
        } finally {
            webA.shutdown()
            webB.shutdown()
        }
    }

    @Test fun listAllOneServerCircuitOpenDoesNotAffectOthers() = runBlocking {
        val webA = MockWebServer()
        val webDown = MockWebServer()
        webA.start()
        webDown.start()
        try {
            // 先把 down 服連續打掛（3 次 400 → PROTOCOL + 開路），再驗 listAll 仍聚合兩服。
            repeat(3) { webDown.enqueue(MockResponse().setResponseCode(400).setBody("bad")) }
            webA.enqueue(MockResponse().setResponseCode(200).setBody(goodListBody()))
            val down = server(webDown, "down")
            // 逐服獨立：down 服開路後，其後續呼叫短路為 CIRCUIT_OPEN。
            repeat(3) { down.listTools() }
            assertTrue("down 服熔斷應已開路", down.breaker().isOpen())
            // listAll 仍聚合：a 服 OK，down 服 CIRCUIT_OPEN（開路短路）。
            val reg = McpRegistry(mapOf("a" to server(webA, "a"), "down" to down))
            val got = withTimeoutOrNull(8_000) { reg.listAll() }
            assertNotNull(got)
            assertEquals(McpStatus.OK, got!!.getValue("a").status)
            assertEquals("開路服短路為 CIRCUIT_OPEN", McpStatus.CIRCUIT_OPEN, got.getValue("down").status)
            // 反向獨立：a 服成功不得重置他服熔斷——listAll 後 down 服仍應開路。
            //（若 listAll 誤加「他服成功即清空 down 服 failures／開窗」的重置邏輯，
            //  此斷言紅——breaker 僅有 onSuccess/onFailure/onNeutral 契約面，D-04。）
            assertTrue("他服成功不得重置 down 服熔斷", down.breaker().isOpen())
        } finally {
            webA.shutdown()
            webDown.shutdown()
        }
    }

    @Test fun listAllIsDisabledWhenServerDisabled() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            val disabled = McpServer(
                config = McpServerConfig(id = "off", baseUrl = web.url("/").toString(), enabled = false),
                keyVault = FakeVault(),
                transport = McpTransport(),
            )
            val reg = McpRegistry(mapOf("off" to disabled))
            val got = withTimeoutOrNull(5_000) { reg.listAll() }
            assertNotNull(got)
            assertEquals("停用服回 DISABLED（不發送）", McpStatus.DISABLED, got!!.getValue("off").status)
            assertEquals("不發送任何請求", 0, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test fun listAllAcceptsTimeoutParamOnHappyPath() = runBlocking {
        // 快速回應路徑：確認 listAll(timeoutMs) 接受參數並走通呼叫。真正的
        // 「timeoutMs 逐服轉發」由 listAllForwardsPerServerTimeout 以 15s deadline
        // 對 18s 延遲伺服器驗證（timeout 不再只是被接受）。
        val web = MockWebServer()
        web.start()
        try {
            web.enqueue(MockResponse().setResponseCode(200).setBody(goodListBody()))
            val reg = McpRegistry(mapOf("a" to server(web, "a")))
            // timeoutMs 鉗 15–30s：傳 MIN_MS 仍成功。
            val got = withTimeoutOrNull(8_000) { reg.listAll(McpTimeouts.MIN_MS) }
            assertNotNull(got)
            assertEquals(McpStatus.OK, got!!.getValue("a").status)
            assertEquals(1, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test fun listAllForwardsPerServerTimeout() = runBlocking {
        // TQ-03：listAll(timeoutMs) 必須把呼叫方上限**逐服轉發**給 listTools。
        // 慢服 body 延遲 18s：若轉發 MIN_MS（15s）→ 15s 到期 TIMEOUT；若 listAll
        // 丟掉參數改用 DEFAULT_MS（20s），18s 時 body 已到 → OK。故 15s/18s/20s
        // 三個數字使本測對「參數被忽略」有鑑別力（不是只驗參數被接受）。
        // 快服立即回 OK，證明逾時是逐服而非整體（慢服 15s 不會拖垮快服）。
        val slow = MockWebServer()
        val fast = MockWebServer()
        slow.start()
        fast.start()
        try {
            slow.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody(goodListBody())
                    .setBodyDelay(18, TimeUnit.SECONDS),
            )
            fast.enqueue(MockResponse().setResponseCode(200).setBody(goodListBody()))
            val slowServer = server(slow, "slow")
            val fastServer = server(fast, "fast")
            val reg = McpRegistry(
                linkedMapOf("slow" to slowServer, "fast" to fastServer),
            )
            val got = withTimeoutOrNull(25_000) { reg.listAll(McpTimeouts.MIN_MS) }
            assertNotNull("listAll 必須在 watchdog 25s 內回傳（慢服 15s deadline）", got)
            assertEquals(
                "慢服必須因轉發的 15s deadline 而 TIMEOUT（若用預設 20s，18s body 會到 → OK）",
                McpStatus.TIMEOUT,
                got!!.getValue("slow").status,
            )
            assertEquals("快服必須獨立 OK（逐服預算，非整體縮短）", McpStatus.OK, got.getValue("fast").status)
            assertEquals("慢服恰一發（timeout 不重送）", 1, slow.requestCount)
            assertEquals("快服恰一發", 1, fast.requestCount)
            assertEquals("慢服 TIMEOUT 必須計熔斷一次", 1, slowServer.breaker().failureCount())
            assertEquals("快服成功不得計熔斷", 0, fastServer.breaker().failureCount())
        } finally {
            slow.shutdown()
            fast.shutdown()
        }
    }

    @Test fun registryDefensivelyCopiesConstructorMapAndIdsView() {
        // Stage3 C-10：防禦性複製——建構後增刪改**原 map** 不影響 registry
        //（成員資格在建構時固定）；ids() 回傳**快照**，呼叫端增刪回傳集合
        // 不影響內部。mutation（拿掉 toMap()/keys.toSet()）此測即紅。
        val web = MockWebServer()
        web.start()
        try {
            val a = server(web, "a")
            val b = server(web, "b")
            // 兩元素起步：Kotlin toSet() 對單元素集合回不可變 singleton——
            // 快照可變性驗證須 ≥2 元素（LinkedHashSet）才有意義。
            val mutable = linkedMapOf("a" to a, "b" to b)
            val reg = McpRegistry(mutable)
            // 原 map 事後變更不得滲透 registry。
            mutable["evil"] = server(web, "evil")
            mutable.remove("a")
            assertEquals("原 map 增改不得影響 registry", setOf("a", "b"), reg.ids())
            assertSame("命中仍回原實例", a, reg.get("a"))
            assertNull("未經原 map 事後新增的 id 必須回 null", reg.get("evil"))
            // ids() 快照：呼叫端（型別上可變的 Set 實作）增刪不得影響內部。
            val ids = reg.ids() as MutableSet<String>
            ids.add("injected")
            ids.remove("a")
            assertEquals("ids() 回傳集合被改動不得影響內部", setOf("a", "b"), reg.ids())
            assertSame(a, reg.get("a"))
        } finally {
            web.shutdown()
        }
    }
}
