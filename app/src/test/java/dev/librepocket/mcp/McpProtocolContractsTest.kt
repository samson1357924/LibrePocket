package dev.librepocket.mcp

import dev.librepocket.keystore.KeyVault
import java.util.concurrent.TimeUnit
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
 * #11 MCP protocol contracts：單一最小子集起步（per-revision suite）。
 *
 * 修訂決策（Stage2，書面記錄）：
 * - 候選 `2025-11-25` 與 `2026-07-28` 皆為 **Proposed**（未採納）：本 repo 內無任一
 *   修訂的規格文本，工作區無外網可核對，任何「差異表」都將是虛構，故此處不寫差異表。
 * - 選擇：單一起步 —— 僅 `tools/list`＋`tools/call` 經 Streamable HTTP POST、
 *   Bearer 標頭、單頁（不跟隨分頁）、HTTP 一問一答配對（不做 response id 斷言）。
 *   不送 `initialize`、不接 SSE stream、不協商 `protocolVersion`。
 *   排除依據不是「規格說不用」，而是 fail-closed：子集外的形狀一律走 PROTOCOL
 *  （或根本不送該請求），見本檔 lifecycle/metadata/SSE/cursor/id 契約。
 * - 將來若要支援任一修訂，須先把該修訂文本納入 `docs/specs/` 並逐項補契約，
 *   不得把本子集的綠燈當成修訂相容證據。
 *
 * 全程 MockWebServer／純 wire 解析，不連外網；MCP 生產零引用維持（不接線）。
 */
class McpProtocolContractsTest {

    private class FakeVault : KeyVault {
        override suspend fun putKey(providerId: String, apiKey: CharArray) = Unit
        override suspend fun getKey(providerId: String): CharArray? = "sekret-token-1".toCharArray()
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
            assertEquals("mcp: missing result", e.message)
        }
    }

    @Test fun emptyObjectIsNotOkForCall() {
        try {
            McpWire.parseCall("{}")
            fail("空包必須拒絕（映射 PROTOCOL），不得回 OK")
        } catch (e: McpWire.McpToolFailure) {
            fail("空包不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (e: IllegalArgumentException) {
            assertEquals("mcp: missing result", e.message)
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
            assertEquals("mcp: missing result", e.message)
        }
        try {
            McpWire.parseCall(json)
            fail("notification 形狀不得回 OK")
        } catch (e: McpWire.McpToolFailure) {
            fail("不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (e: IllegalArgumentException) {
            assertEquals("mcp: missing result", e.message)
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
        val tools = McpWire.parseList("s1", json)
        assertEquals("本頁工具仍須解析", 1, tools.size)
        assertEquals("a", tools[0].name)
        // 無跟隨語義由 E2E 鎖定：見 standardSubsetFakeEndToEnd（listTools 只發一次 HTTP）。
    }

    // ---- f. id 對應：HTTP 一問一答配對，wire 層不斷言 id（鎖定現狀） ----

    @Test fun responseIdIsNotAssertedByWireLayer() {
        // 呼叫方以 HTTP 一問一答配對（見 McpWire KDoc 非目標註記）：string／null／缺席 id
        // 皆照常解析。此測鎖定該語義，防後人誤加 id 斷言把單子集玩成多路複用。
        val stringId = "{\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"result\":{\"tools\":[]}}"
        assertTrue(McpWire.parseList("s1", stringId).isEmpty())
        val nullId = "{\"jsonrpc\":\"2.0\",\"id\":null,\"result\":" +
            "{\"content\":[],\"isError\":false}}"
        val (_, isError) = McpWire.parseCall(nullId)
        assertFalse(isError)
        val missingId = "{\"jsonrpc\":\"2.0\",\"result\":{\"tools\":[]}}"
        assertTrue(McpWire.parseList("s1", missingId).isEmpty())
        // id 型別錯亂但解析仍過：mismatched id 不擋（配對責任在傳輸層，不在 parser）。
        val mismatched = "{\"jsonrpc\":\"2.0\",\"id\":999,\"result\":{\"tools\":[]}}"
        assertTrue(McpWire.parseList("s1", mismatched).isEmpty())
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

    @Test fun missingJsonrpcStillParsesLocksNonGoal() {
        // jsonrpc 非目標（不斷言版本）：缺席仍照常解析，防後人誤加版本斷言。
        val list = "{\"id\":1,\"result\":{\"tools\":[]}}"
        assertTrue(McpWire.parseList("s1", list).isEmpty())
        val call = "{\"id\":2,\"result\":{\"content\":[],\"isError\":false}}"
        val (_, isError) = McpWire.parseCall(call)
        assertFalse(isError)
    }

    @Test fun emptyResultObjectIsOkLocksCurrentSemantics() {
        // 空 result {} 視為 OK（已知限制，見 McpWire.parseCall KDoc）。
        val (resultJson, isError) = McpWire.parseCall("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}")
        assertFalse(isError)
        assertEquals("{}", resultJson)
    }

    @Test fun structuredContentDiscardedWhenContentPresent() {
        // content 存在時 structuredContent 被丟棄（已知限制，見 KDoc）。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
            "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]," +
            "\"structuredContent\":{\"key\":\"rich\"},\"isError\":false}}"
        val (resultJson, isError) = McpWire.parseCall(json)
        assertFalse(isError)
        assertNotNull(resultJson)
        assertTrue(resultJson!!.contains("ok"))
        assertFalse(resultJson.contains("rich"))
    }

    @Test fun isErrorNullIsFalse() {
        val (resultJson, isError) = McpWire.parseCall(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[],\"isError\":null}}",
        )
        assertFalse(isError)
        assertNotNull(resultJson)
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
            assertTrue(first!!.body.readUtf8().contains("\"method\":\"tools/list\""))
            assertTrue(second!!.body.readUtf8().contains("\"method\":\"tools/call\""))
            assertFalse("lifecycle 交握不得出現", first.body.readUtf8().contains("initialize"))
            assertFalse("lifecycle 交握不得出現", second.body.readUtf8().contains("initialize"))
            assertEquals("成功不得計熔斷", 0, breaker.failureCount())
            // 稽核不含原文。
            assertFalse(call.auditMap().toString().contains("hi"))
        } finally {
            web.shutdown()
        }
    }
}
