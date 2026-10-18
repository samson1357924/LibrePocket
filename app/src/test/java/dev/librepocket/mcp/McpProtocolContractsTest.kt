package dev.librepocket.mcp

import dev.librepocket.keystore.KeyVault
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * #11 MCP protocol contracts：單一傳輸子集起步（per-revision suite）。
 *
 * 修訂選型（Stage2 本 PR 選型，非 Accepted ADR，書面記錄）：
 * - 候選 `2025-11-25` 與 `2026-07-28`（#11 提及的候選修訂號，repo 內無文本）
 *   皆為候選未採納：本 repo 內無任一修訂的規格文本，工作區無外網可核對，
 *   任何「差異表」都將是虛構，故此處不寫差異表。
 * - 選擇：單一傳輸子集起步 —— 僅 `tools/list`＋`tools/call` 經 Streamable HTTP POST、
 *   Bearer 標頭、單頁（不跟隨分頁）、固定 id（list=1／call=2，收回應時以 expectedId
 *   數值校驗，Stage2 起錯 id 一律 fail-closed）。
 *   不送 `initialize`、不接 SSE stream、不協商 `protocolVersion`。
 *   排除依據不是「規格說不用」，而是 fail-closed：子集外的形狀一律走 PROTOCOL
 *  （或根本不送該請求），見本檔 lifecycle/metadata/SSE/cursor/id 契約。
 * - 將來若要支援任一修訂，須先把該修訂文本納入 `docs/specs/` 並逐項補契約，
 *   不得把本子集的綠燈當成修訂相容證據。
 *
 * 全程 MockWebServer／純 wire 解析，不連外網；MCP 生產零 import 維持（不接線）。
 */
class McpProtocolContractsTest {

    /** 5 秒統一 deadline：逾時以明確錯誤失敗（取代 `!!` 的無脈絡 NPE）。 */
    private suspend fun <T> deadline(block: suspend CoroutineScope.() -> T): T =
        withTimeoutOrNull(5_000, block)
            ?: error("5 秒內未回傳——server 未返回（佇列阻塞或 timeout 到期）")

    private class FakeVault : KeyVault {
        override suspend fun putKey(providerId: String, apiKey: CharArray) = Unit
        override suspend fun getKey(providerId: String): CharArray? = "sekret-token-1".toCharArray()
        override suspend fun deleteKey(providerId: String) = Unit
        override suspend fun hasKey(providerId: String): Boolean = true
    }

    private class CountingVault : KeyVault {
        var calls = 0
        override suspend fun putKey(providerId: String, apiKey: CharArray) = Unit
        override suspend fun getKey(providerId: String): CharArray? {
            calls++
            return "sekret-token-1".toCharArray()
        }
        override suspend fun deleteKey(providerId: String) = Unit
        override suspend fun hasKey(providerId: String): Boolean = true
    }

    // ---- a. 空包永不回 OK（{} 無 result 即拒） ----

    @Test fun emptyObjectIsNotOkForList() {
        try {
            McpWire.parseList("s1", "{}")
            fail("空包必須拒絕（映射 PROTOCOL），不得回空工具列表充 OK")
        } catch (e: McpWire.McpToolFailure) {
            fail("空包不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (e: IllegalArgumentException) {
            // Stage2：空包連 jsonrpc 都缺，envelope 驗證先擋。
            assertEquals("mcp: bad jsonrpc", e.message)
        }
    }

    @Test fun emptyObjectIsNotOkForCall() {
        try {
            McpWire.parseCall("{}")
            fail("空包必須拒絕（映射 PROTOCOL），不得回 OK")
        } catch (e: McpWire.McpToolFailure) {
            fail("空包不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (e: IllegalArgumentException) {
            // Stage2：同 List 變體，空包在 envelope 驗證即拒。
            assertEquals("mcp: bad jsonrpc", e.message)
        }
    }

    // ---- b. lifecycle：client 永不送 initialize；initialize 形狀不被當工具列表 ----

    @Test fun requestBodiesNeverNegotiateLifecycle() {
        assertFalse("listBody 不得含 initialize", McpWire.listBody().contains("initialize"))
        assertFalse("callBody 不得含 initialize", McpWire.callBody("t", "{}").contains("initialize"))
        assertFalse("listBody 不得含 protocolVersion", McpWire.listBody().contains("protocolVersion"))
        assertFalse("callBody 不得含 protocolVersion", McpWire.callBody("t", "{}").contains("protocolVersion"))
        assertTrue(McpWire.listBody().contains("tools/list"))
        assertTrue(McpWire.callBody("t", "{}").contains("tools/call"))
    }

    @Test fun initializeShapedResultIsNotAToolList() {
        // initialize 回應形狀（protocolVersion/capabilities/serverInfo，無 tools）餵給
        // parseList 必須拒（缺 tools 鍵），不得回空列表充 OK。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":" +
            "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{}," +
            "\"serverInfo\":{\"name\":\"s\",\"version\":\"1\"}}}"
        try {
            McpWire.parseList("s1", json)
            fail("initialize 形狀不得被當成工具列表（映射 PROTOCOL）")
        } catch (e: McpWire.McpToolFailure) {
            fail("不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (e: IllegalArgumentException) {
            assertEquals("mcp: bad result.tools", e.message)
        }
    }

    @Test fun notificationShapedDocIsNotOk() {
        // notifications/initialized 形狀（無 result，有 method）雙 parser 皆拒。
        val json = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"
        try {
            McpWire.parseList("s1", json)
            fail("notification 形狀不得回工具列表")
        } catch (e: McpWire.McpToolFailure) {
            fail("不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (e: IllegalArgumentException) {
            // 無 id：envelope 驗證先擋（id 先於 result 檢查）。
            assertEquals("mcp: missing id", e.message)
        }
        try {
            McpWire.parseCall(json)
            fail("notification 形狀不得回 OK")
        } catch (e: McpWire.McpToolFailure) {
            fail("不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (e: IllegalArgumentException) {
            assertEquals("mcp: missing id", e.message)
        }
    }

    // ---- c. metadata：未知鍵前向相容（忽略），但必需鍵仍強制 ----

    @Test fun unknownMetaKeysToleratedWhenRequiredKeysPresent() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"_meta\":{\"trace\":\"x\"}," +
            "\"result\":{\"tools\":[{\"name\":\"a\",\"inputSchema\":{\"type\":\"object\"}}]," +
            "\"_meta\":{\"page\":\"1\"}}}"
        val tools = McpWire.parseList("s1", json)
        assertEquals(1, tools.size)
        assertEquals("a", tools[0].name)
        val call = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
            "{\"content\":[],\"isError\":false,\"_meta\":{}},\"protocolVersion\":\"2025-11-25\"}"
        val (_, isError) = McpWire.parseCall(call)
        assertFalse(isError)
    }

    // ---- d. SSE：event-stream 文本不是 JSON-RPC 包，一律拒 ----

    @Test fun sseFramingIsRejectedNotOk() {
        val sse = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2," +
            "\"result\":{\"content\":[],\"isError\":false}}\n\n"
        try {
            McpWire.parseCall(sse)
            fail("SSE frame 不得被當成 tools/call 回應回 OK")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad envelope", e.message)
        } catch (e: IllegalArgumentException) {
            fail("SSE frame 必須拋 McpProtocol（mcp: bad envelope），實際=${e.message}")
        }
        try {
            McpWire.parseList("s1", sse)
            fail("SSE frame 不得被當成 tools/list 回應")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad envelope", e.message)
        } catch (e: IllegalArgumentException) {
            fail("SSE frame 必須拋 McpProtocol，實際=${e.message}")
        }
    }

    // ---- e. cursor：單頁子集，不自動跟隨分頁 ----

    @Test fun nextCursorDoesNotAutoFollowSinglePage() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
            "[{\"name\":\"a\",\"inputSchema\":{\"type\":\"object\"}}],\"nextCursor\":\"cursor-1\"}}"
        val page = McpWire.parseListPage("s1", json)
        assertEquals("本頁工具仍須解析", 1, page.tools.size)
        assertEquals("a", page.tools[0].name)
        assertEquals("非空 nextCursor 必須標記還有下一頁（PARTIAL，不回 OK 假完整）", true, page.hasMorePages)
        // parseList 薄委託維持相容（不含分頁旗標）。
        assertEquals(1, McpWire.parseList("s1", json).size)
        // 無跟隨語義由 E2E 鎖定：見 standardSubsetFakeEndToEnd（listTools 只發一次 HTTP）
        // 與 McpServerSemanticsTest.nextCursorMapsToPartialKeepingTools。
    }

    // ---- f. id 對應：expectedId 數值校驗（Stage2 翻轉舊「不斷言」pin） ----

    @Test fun responseIdIsAssertedAgainstExpectedId() {
        // 無 expectedId（測試直呼叫）只驗 id 存在與型別；server 呼叫傳 expectedId
        // 必須數值相符——單發子集不支援 id 複用，但錯 id 回應不得採納（外部 review #2）。
        val stringId = "{\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"result\":{\"tools\":[]}}"
        assertTrue("無 expectedId：字串 id 仍接受", McpWire.parseList("s1", stringId).isEmpty())
        // 缺席／null id 一律拒（翻轉舊「照常解析」pin）。
        for (bad in listOf(
            "{\"jsonrpc\":\"2.0\",\"result\":{\"tools\":[]}}",
            "{\"jsonrpc\":\"2.0\",\"id\":null,\"result\":{\"tools\":[]}}",
        )) {
            try {
                McpWire.parseList("s1", bad)
                fail("缺席／null id 必須拒：$bad")
            } catch (e: McpWire.McpProtocol) {
                assertEquals("mcp: missing id", e.message)
            }
        }
        // mismatched id：無 expectedId 不比對值（HTTP 一問一答仍在 server 層把關），
        // 傳 expectedId 必須擋。
        val mismatched = "{\"jsonrpc\":\"2.0\",\"id\":999,\"result\":{\"tools\":[]}}"
        assertTrue(McpWire.parseList("s1", mismatched).isEmpty())
        try {
            McpWire.parseList("s1", mismatched, expectedId = 1L)
            fail("mismatched id 必須拒")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: id mismatch", e.message)
        }
        // 字串 id 配 expectedId 同樣 mismatch（要求數值相符）。
        try {
            McpWire.parseList("s1", stringId, expectedId = 1L)
            fail("字串 id 配 expectedId 必須拒")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: id mismatch", e.message)
        }
        // 分數／指數字面量 id：合法 JNum 但非整數字串，toLongOrNull 得 null → mismatch
        // （伺服器把 2 序列化成 2.0 的 fail-closed 取捨在此釘定）。
        for (rawId in listOf("1.0", "1e3")) {
            val floatId = "{\"jsonrpc\":\"2.0\",\"id\":$rawId,\"result\":{\"tools\":[]}}"
            try {
                McpWire.parseList("s1", floatId, expectedId = 1L)
                fail("id $rawId 不得當 1")
            } catch (e: McpWire.McpProtocol) {
                assertEquals("mcp: id mismatch", e.message)
            }
        }
        // call 同語義。
        val callMismatch = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[],\"isError\":false}}"
        try {
            McpWire.parseCall(callMismatch, expectedId = 2L)
            fail("call id mismatch 必須拒")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: id mismatch", e.message)
        }
        // 相符 id 通過。
        val okList = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}"
        assertTrue(McpWire.parseList("s1", okList, expectedId = 1L).isEmpty())
        val okCall = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[],\"isError\":false}}"
        val (_, isError) = McpWire.parseCall(okCall, expectedId = 2L)
        assertFalse(isError)
    }

    // ---- g. 巢狀同名 key（per-revision suite 變體） ----

    @Test fun nestedToolsKeyInsideSchemaDoesNotShadow() {
        // inputSchema.properties.tools 為巢狀陣列：不得被當成外層 result.tools。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
            "[{\"name\":\"real\",\"inputSchema\":{\"type\":\"object\"," +
            "\"properties\":{\"tools\":{\"type\":\"array\"}}}}]}}"
        val tools = McpWire.parseList("s1", json)
        assertEquals(1, tools.size)
        assertEquals("real", tools[0].name)
    }

    @Test fun nestedResultKeyInsideStructuredContentIsOk() {
        // structuredContent 內的巢狀 "result" 鍵不得遮蔽頂層 result。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
            "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]," +
            "\"structuredContent\":{\"result\":\"nested\"},\"isError\":false}}"
        val (result, isError) = McpWire.parseCall(json)
        assertFalse(isError)
        assertTrue(result != null && result.contains("ok"))
    }

    // ---- h. 超長 schema 拒（per-revision suite 獨立 fixture） ----

    @Test fun oversizedSchemaRejectedAtWireLayer() {
        val big = "z".repeat(McpWire.INPUT_SCHEMA_MAX_CHARS)
        val schema = "{\"type\":\"object\",\"extra\":\"$big\"}"
        assertTrue(schema.length > McpWire.INPUT_SCHEMA_MAX_CHARS)
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
            "[{\"name\":\"t\",\"inputSchema\":$schema}]}}"
        try {
            McpWire.parseList("s1", json)
            fail("超長 inputSchema 必須拒絕，不得靜默截斷")
        } catch (e: McpWire.McpProtocol) {
            assertEquals(
                "mcp: inputSchema exceeds ${McpWire.INPUT_SCHEMA_MAX_CHARS} chars",
                e.message,
            )
        } catch (_: IllegalArgumentException) {
            fail("必須拋 McpWire.McpProtocol（非一般 IAE）")
        }
    }

    // ---- h2. Stage1 加固：轉義／請求體 fail-closed（鎖定修復） ----

    @Test fun unicodeEscapeWithSignIsRejected() {
        // \u+123 / \u-12a 非法（toIntOrNull(16) 會吞 +/-，此處必須拒）。
        for (bad in listOf("\\u+123", "\\u-12a", "\\uZZZZ")) {
            val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
                "[{\"name\":\"t\",\"inputSchema\":{\"type\":\"object\",\"v\":\"a${bad}b\"}}]}}"
            try {
                McpWire.parseList("s1", json)
                fail("非法 \\u 跳脫必須拒絕：$bad")
            } catch (_: McpWire.McpProtocol) {
                // 預期：mcp: bad envelope。
            } catch (e: IllegalArgumentException) {
                fail("必須拋 McpProtocol，實際=${e.message}")
            }
        }
    }

    @Test fun escCoversBackspaceFormfeedAndC0() {
        val name = "a\"b\\c\bd\ne\rg\th" + '\u000C' + '\u0001' + "-end"
        val body = McpWire.callBody(name, "{}")
        // 產出必須是合法 JSON（嚴格 parser 可走訪），且不得含裸控制字元。
        McpWire.parseStrictDoc(body)
        assertFalse("不得含裸 BS", body.contains("\b"))
        assertFalse("不得含裸 FF", body.contains("\u000C"))
        assertTrue(body.contains("\\b"))
        assertTrue(body.contains("\\f"))
        assertTrue(body.contains("\\u0001"))
    }

    @Test fun callBodyRejectsNonObjectArguments() {
        for (bad in listOf("null", "[]", "{unquoted}", "not json")) {
            try {
                McpWire.callBody("t", bad)
                fail("非 object arguments 必須拒絕：$bad")
            } catch (_: IllegalArgumentException) {
                // 預期 fail-closed（發送前擋下）。
            }
        }
        try {
            McpWire.callBody("   ", "{}")
            fail("空白 toolName 必須拒絕")
        } catch (_: IllegalArgumentException) {
            // 預期。
        }
        // 空字串預設 {} 且合法。
        McpWire.parseStrictDoc(McpWire.callBody("t", ""))
    }

    @Test fun clientArgErrorMapsToProtocolWithoutBreakerOrHttp() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            val bad = withTimeoutOrNull(5_000) { server.callTool("t", "not json") }
            assertNotNull(bad)
            assertEquals(McpStatus.PROTOCOL, bad!!.status)
            assertEquals("客戶端參數錯誤不得發送 HTTP", 0, web.requestCount)
            assertEquals("客戶端參數錯誤不得計熔斷", 0, breaker.failureCount())
            val blank = withTimeoutOrNull(5_000) { server.callTool("   ", "{}") }
            assertNotNull(blank)
            assertEquals(McpStatus.PROTOCOL, blank!!.status)
            assertEquals(0, web.requestCount)
            assertEquals(0, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun missingOrWrongJsonrpcIsRejected() {
        // jsonrpc 必須存在且嚴格 "2.0"（Stage2 翻轉舊 non-goal pin，外部 review #2）。
        for (bad in listOf(
            "{\"id\":1,\"result\":{\"tools\":[]}}",
            "{\"jsonrpc\":\"1.0\",\"id\":1,\"result\":{\"tools\":[]}}",
            "{\"jsonrpc\":2.0,\"id\":1,\"result\":{\"tools\":[]}}",
            "{\"jsonrpc\":null,\"id\":1,\"result\":{\"tools\":[]}}",
            "{\"jsonrpc\":true,\"id\":1,\"result\":{\"tools\":[]}}",
        )) {
            try {
                McpWire.parseList("s1", bad)
                fail("缺／錯版 jsonrpc 必須拒：$bad")
            } catch (e: McpWire.McpProtocol) {
                assertEquals("mcp: bad jsonrpc", e.message)
            }
        }
        val call = "{\"id\":2,\"result\":{\"content\":[],\"isError\":false}}"
        try {
            McpWire.parseCall(call)
            fail("缺 jsonrpc 必須拒")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad jsonrpc", e.message)
        }
        // 正確版本仍過。
        val ok = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}"
        assertTrue(McpWire.parseList("s1", ok, expectedId = 1L).isEmpty())
    }

    @Test fun emptyResultObjectIsRejected() {
        // 空 result {} 不再當成功（Stage3，外部 review #5）：形狀不完整 → PROTOCOL。
        try {
            McpWire.parseCall("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}")
            fail("空 result 必須拒（McpProtocol），不得回 OK")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: empty result", e.message)
        }
        // content 陣列存在（即使空）仍算有結果。
        val emptyContent = McpWire.parseCall(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[],\"isError\":false}}",
        )
        assertFalse(emptyContent.second)
        assertEquals("[]", emptyContent.first)
    }

    @Test fun structuredContentPreservedWithContent() {
        // content＋structuredContent 皆有時整包保留（Stage3 翻轉舊「丟棄」pin）。
        val result = "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]," +
            "\"structuredContent\":{\"key\":\"rich\"},\"isError\":false}"
        val (resultJson, isError) = McpWire.parseCall(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":$result}",
        )
        assertFalse(isError)
        assertEquals("整包 result.raw 全等保留，不得擇一丟棄", result, resultJson)
    }

    @Test fun isErrorNullIsFalse() {
        val (resultJson, isError) = McpWire.parseCall(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[],\"isError\":null}}",
        )
        assertFalse(isError)
        assertNotNull(resultJson)
    }

    // ---- h3. Stage2 加固：安全端點／熔斷中性／非 JSON 早擋 ----

    @Test fun secureEndpointUnits() {
        // https 放行（含 bare-origin 自動補 /mcp）。
        assertTrue(McpWire.requireSecureEndpoint("https://mcp.example.com").endsWith("/mcp"))
        assertTrue(
            McpWire.requireSecureEndpoint("https://mcp.example.com/api/mcp?x=1").contains("/api/mcp"),
        )
        // 回環 http 放行（測試／本機）：全等驗證（bare-origin 同樣自動補 /mcp）。
        assertEquals(
            "http://localhost:8080/mcp",
            McpWire.requireSecureEndpoint("http://localhost:8080/mcp"),
        )
        assertEquals(
            "http://127.0.0.1:8080/mcp",
            McpWire.requireSecureEndpoint("http://127.0.0.1:8080/"),
        )
        // 非回環 http 與畸形一律拒。
        for (bad in listOf("http://mcp.example.com/mcp", "http://192.168.1.10/mcp", "::::", "ftp://h/mcp")) {
            try {
                McpWire.requireSecureEndpoint(bad)
                fail("必須拒絕：$bad")
            } catch (_: IllegalArgumentException) {
                // 預期 fail-closed。
            }
        }
        try {
            McpWire.requireSecureEndpoint("   ")
            fail("空白必須拒絕")
        } catch (_: IllegalArgumentException) {
            // 預期。
        }
    }

    @Test fun insecureEndpointBlockedWithoutVaultOrBreaker() = runBlocking {
        val counting = CountingVault()
        val breaker = McpCircuitBreaker()
        val server = McpServer(
            config = McpServerConfig(id = "s1", baseUrl = "http://mcp.example.com/mcp"),
            keyVault = counting,
            transport = McpTransport(),
            breaker = breaker,
        )
        // 目標為外網明文：若預驗失效而走到傳輸層，將嘗試真實連線（慢／TRANSPORT），
        // 此處限 5s 內回 PROTOCOL 即證明發送前擋下。
        val list = withTimeoutOrNull(5_000) { server.listTools() }
        assertNotNull(list)
        assertEquals(McpStatus.PROTOCOL, list!!.status)
        val call = withTimeoutOrNull(5_000) { server.callTool("t", "{}") }
        assertNotNull(call)
        assertEquals(McpStatus.PROTOCOL, call!!.status)
        assertEquals("配置錯誤不得碰 vault", 0, counting.calls)
        assertEquals("配置錯誤不得計熔斷", 0, breaker.failureCount())
    }

    @Test fun toolErrorIsNeutralDoesNotResetBreaker() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // 2×TRANSPORT（500）＋1×TOOL_ERROR（isError）＋1×TRANSPORT 必開路；
            // 若 TOOL_ERROR 重置計數則永不開路（舊 bug）。
            web.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            web.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
                        "{\"content\":[{\"type\":\"text\",\"text\":\"x\"}],\"isError\":true}}",
                ),
            )
            web.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            assertEquals(McpStatus.TRANSPORT, deadline { server.callTool("t", "{}") }.status)
            assertEquals(McpStatus.TRANSPORT, deadline { server.callTool("t", "{}") }.status)
            assertEquals(2, breaker.failureCount())
            assertEquals(McpStatus.TOOL_ERROR, deadline { server.callTool("t", "{}") }.status)
            assertEquals("TOOL_ERROR 必須中性（不重置）", 2, breaker.failureCount())
            assertEquals(McpStatus.TRANSPORT, deadline { server.callTool("t", "{}") }.status)
            assertTrue(breaker.isOpen())
            assertEquals(McpStatus.CIRCUIT_OPEN, deadline { server.callTool("t", "{}") }.status)
        } finally {
            web.shutdown()
        }
    }

    @Test fun errorPacketIsNeutralDoesNotResetBreaker() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // error 包（McpToolFailure）同為中性：2×TRANSPORT＋1×error 包＋1×TRANSPORT 必開路。
            web.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            web.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32602,\"message\":\"bad\"}}",
                ),
            )
            web.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            assertEquals(McpStatus.TRANSPORT, deadline { server.callTool("t", "{}") }.status)
            assertEquals(McpStatus.TRANSPORT, deadline { server.callTool("t", "{}") }.status)
            assertEquals(2, breaker.failureCount())
            assertEquals(McpStatus.TOOL_ERROR, deadline { server.callTool("t", "{}") }.status)
            assertEquals("error 包必須中性（不重置）", 2, breaker.failureCount())
            assertEquals(McpStatus.TRANSPORT, deadline { server.callTool("t", "{}") }.status)
            assertTrue(breaker.isOpen())
        } finally {
            web.shutdown()
        }
    }

    @Test fun nonJsonContentTypeRejectedEarly() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            web.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .setBody("event: message\ndata: {\"jsonrpc\":\"2.0\"}\n\n"),
            )
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            val got = withTimeoutOrNull(5_000) { server.listTools() }
            assertNotNull(got)
            assertEquals(McpStatus.PROTOCOL, got!!.status)
            assertEquals(1, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    // ---- i. 自訂 endpoint E2E（含 query 保留＋標頭） ----
    @Test fun customEndpointWithQueryPreservedEndToEnd() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}",
                ),
            )
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/api/v2/mcp?key=set").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = McpCircuitBreaker(),
            )
            val got = withTimeoutOrNull(5_000) { server.listTools() }
            assertNotNull(got)
            assertEquals(McpStatus.OK, got!!.status)
            val req = withTimeoutOrNull(5_000L) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    web.takeRequest(5, TimeUnit.SECONDS)
                }
            }
            assertNotNull("expected request within 5s", req)
            assertEquals("自訂 path＋query 必須原樣保留", "/api/v2/mcp?key=set", req!!.path)
            assertEquals("POST", req.method)
            assertEquals("Bearer sekret-token-1", req.getHeader("Authorization"))
            assertTrue(req.body.readUtf8().contains("tools/list"))
        } finally {
            web.shutdown()
        }
    }

    // ---- j. standard-server fake E2E（最小子集：list→call，方法斷言＋熔斷歸零） ----

    @Test fun standardSubsetFakeEndToEnd() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
                        "[{\"name\":\"search\",\"description\":\"d\"," +
                        "\"inputSchema\":{\"type\":\"object\"}}]}}",
                ),
            )
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
                        "{\"content\":[{\"type\":\"text\",\"text\":\"done\"}],\"isError\":false}}",
                ),
            )
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            val list = withTimeoutOrNull(5_000) { server.listTools() }
            assertNotNull(list)
            assertEquals(McpStatus.OK, list!!.status)
            assertEquals(listOf("search"), list.tools.map { it.name })
            val call = withTimeoutOrNull(5_000) { server.callTool("search", "{\"q\":\"hi\"}") }
            assertNotNull(call)
            assertEquals(McpStatus.OK, call!!.status)
            assertFalse(call.isError)
            assertTrue(call.resultJson != null && call.resultJson.contains("done"))
            // fake 只說最小子集：恰兩次 HTTP（單頁 list 不跟隨分頁），方法依序為 tools/list、tools/call。
            assertEquals(2, web.requestCount)
            val first = web.takeRequest(5, TimeUnit.SECONDS)
            val second = web.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(first)
            assertNotNull(second)
            // body 是一次性 Buffer：先緩存再全部斷言（二次 readUtf8 讀到空 buffer，
            // 否則「不得出現 initialize」會是永真的假負例）。
            val firstBody = first!!.body.readUtf8()
            val secondBody = second!!.body.readUtf8()
            assertTrue(firstBody.contains("\"method\":\"tools/list\""))
            assertTrue(secondBody.contains("\"method\":\"tools/call\""))
            // 出站 envelope 字面量：jsonrpc 版本與固定 id（list=1、call=2）。
            assertTrue(firstBody.contains("\"jsonrpc\":\"2.0\""))
            assertTrue(firstBody.contains("\"id\":1,\"method\":\"tools/list\""))
            assertTrue(secondBody.contains("\"jsonrpc\":\"2.0\""))
            assertTrue(secondBody.contains("\"id\":2,\"method\":\"tools/call\""))
            assertFalse("lifecycle 交握不得出現", firstBody.contains("initialize"))
            assertFalse("lifecycle 交握不得出現", secondBody.contains("initialize"))
            assertEquals("成功不得計熔斷", 0, breaker.failureCount())
            // 稽核不含原文。
            assertFalse(call.auditMap().toString().contains("hi"))
        } finally {
            web.shutdown()
        }
    }
}
