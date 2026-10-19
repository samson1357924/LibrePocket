package dev.librepocket.mcp

import dev.librepocket.keystore.KeyVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Stage3 server 語義契約（外部獨立 review #3／#5＋先前 N-findings）：
 * - 非空 `nextCursor` → [McpStatus.PARTIAL]（本頁工具保留，不回 OK 假完整）；
 * - tools/call 結果完整性：空 result 拒、structuredContent 保留不丟；
 * - [McpCallResult.errorMessage] 透傳伺服器 error message（不進稽核）；
 * - HTTP 400/404/405 → PROTOCOL + 計熔斷（非暫態配置錯）；429/408 等仍 TRANSPORT。
 *
 * 全程 MockWebServer／純 wire 解析，不連外網。
 */
class McpServerSemanticsTest {

    /** 5 秒統一 deadline：逾時以明確錯誤失敗（取代 `!!` 的無脈絡 NPE）。 */
    private suspend fun <T> deadline(block: suspend CoroutineScope.() -> T): T =
        withTimeoutOrNull(5_000, block)
            ?: error("5 秒內未回傳——server 未返回（佇列阻塞或 timeout 到期）")

    private class FakeVault : KeyVault {
        var reads = 0
        override suspend fun putKey(providerId: String, apiKey: CharArray) = Unit
        override suspend fun getKey(providerId: String): CharArray? {
            reads++
            return "sekret-token-1".toCharArray()
        }
        override suspend fun deleteKey(providerId: String) = Unit
        override suspend fun hasKey(providerId: String): Boolean = true
    }

    private class EmptyVault : KeyVault {
        override suspend fun putKey(providerId: String, apiKey: CharArray) = Unit
        override suspend fun getKey(providerId: String): CharArray? = null
        override suspend fun deleteKey(providerId: String) = Unit
        override suspend fun hasKey(providerId: String): Boolean = false
    }

    // ---- nextCursor：單頁子集但不靜默丟頁 ----

    @Test fun nextCursorVariantsAtWireLevel() {
        val base = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]"
        // 缺席／null／空字串 → 無下一頁。
        for (tail in listOf("}", ",\"nextCursor\":null}", ",\"nextCursor\":\"\"}")) {
            val page = McpWire.parseListPage("s1", "$base$tail}", expectedId = 1L)
            assertFalse("應無下一頁：$tail", page.hasMorePages)
        }
        // 非字串型別 → bad nextCursor（不靜默當無分頁）。
        for (bad in listOf("123", "{}", "true")) {
            try {
                McpWire.parseListPage("s1", "$base,\"nextCursor\":$bad}}", expectedId = 1L)
                fail("非字串 nextCursor 必須拒：$bad")
            } catch (e: McpWire.McpProtocol) {
                assertEquals("mcp: bad nextCursor", e.message)
            }
        }
        // 空白字串非空 → 有下一頁（isEmpty 語義，不 trim）。
        assertTrue(
            "空白 cursor \" \" 屬非空字串",
            McpWire.parseListPage("s1", "$base,\"nextCursor\":\" \"}}", expectedId = 1L).hasMorePages,
        )
    }

    @Test fun badNextCursorWinsOverBadToolItems() {
        // 驗證優先序：tools 陣列型別 → nextCursor → 項目內容。
        // 兩者皆異常時 nextCursor 先擋（文件化順序，非巧合）。
        try {
            McpWire.parseListPage(
                "s1",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" +
                    "{\"tools\":[{\"bad\":1}],\"nextCursor\":123}}",
                expectedId = 1L,
            )
            fail("cursor 異常必須先於 tools 項目異常擋下")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad nextCursor", e.message)
        }
    }

    @Test fun nextCursorMapsToPartialKeepingTools() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // 先種一次失敗：PARTIAL 若計 neutral（而非 success）則不會重置，
            // 以此鑑別 breaker.onSuccess 真的被呼叫。
            web.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
                        "[{\"name\":\"a\",\"inputSchema\":{\"type\":\"object\"}}]," +
                        "\"nextCursor\":\"page-2\"}}",
                ),
            )
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            assertEquals(McpStatus.TRANSPORT, deadline { server.listTools() }.status)
            assertEquals(1, breaker.failureCount())
            val got = deadline { server.listTools() }
            assertEquals("不完整列表不得回 OK", McpStatus.PARTIAL, got.status)
            assertEquals("本頁工具必須保留", listOf("a"), got.tools.map { it.name })
            assertEquals("PARTIAL 必須計成功並重置（onSuccess 非 onNeutral）", 0, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun badNextCursorThroughServerIsProtocol() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]," +
                        "\"nextCursor\":123}}",
                ),
            )
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            val got = deadline { server.listTools() }
            assertEquals(McpStatus.PROTOCOL, got.status)
            assertEquals(1, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun noCursorStillMapsToOk() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
                        "[{\"name\":\"a\",\"inputSchema\":{\"type\":\"object\"}}]}}",
                ),
            )
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = McpCircuitBreaker(),
            )
            val got = deadline { server.listTools() }
            assertEquals(McpStatus.OK, got.status)
            assertEquals(1, got.tools.size)
        } finally {
            web.shutdown()
        }
    }

    // ---- tools/call 結果完整性 ----

    @Test fun structuredContentOnlyIsKept() {
        // 僅 structuredContent（無 content）：保留結構化結果，不當空成功。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
            "{\"structuredContent\":{\"key\":\"rich\"},\"isError\":false}}"
        val (resultJson, isError) = McpWire.parseCall(json, expectedId = 2L)
        assertFalse(isError)
        assertEquals("structuredContent 原文保留", "{\"key\":\"rich\"}", resultJson)
    }

    @Test fun badStructuredContentShapeIsProtocol() {
        for (bad in listOf("[1]", "\"str\"", "42")) {
            val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
                "{\"content\":[],\"structuredContent\":$bad,\"isError\":false}}"
            try {
                McpWire.parseCall(json, expectedId = 2L)
                fail("非 object structuredContent 必須拒：$bad")
            } catch (e: McpWire.McpProtocol) {
                assertEquals("mcp: bad structuredContent", e.message)
            }
        }
    }

    @Test fun badContentItemsAreProtocol() {
        // 元素層 fail-closed：content 元素必須是含字串 type 的 object，
        // 不得原文透傳回 OK（外部 final review P3）。
        for (badItem in listOf("1", "\"x\"", "{}", "{\"type\":42}", "null")) {
            val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
                "{\"content\":[$badItem],\"isError\":false}}"
            try {
                McpWire.parseCall(json, expectedId = 2L)
                fail("壞 content 元素必須拒：$badItem")
            } catch (e: McpWire.McpProtocol) {
                assertEquals("mcp: bad content item", e.message)
            }
        }
        // 正例：合法元素（含 type 字串）照常通過。
        val ok = McpWire.parseCall(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
                "{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"isError\":false}}",
            expectedId = 2L,
        )
        assertFalse(ok.second)
    }

    @Test fun contentRequiredFieldsAreProtocol() {
        // ContentBlock 必填欄位（外部 review「前輪 P2-2」）：有 type 但缺該型別必填資料
        // 不得當成功——逐型別負向 fixtures ＋ 未知型別 fail-closed。
        val bads = mapOf(
            "{\"type\":\"text\"}" to "mcp: content missing text.text",
            "{\"type\":\"text\",\"text\":123}" to "mcp: content bad text.text",
            "{\"type\":\"text\",\"text\":null}" to "mcp: content missing text.text",
            "{\"type\":\"image\",\"data\":\"AA==\"}" to "mcp: content missing image.mimeType",
            "{\"type\":\"image\",\"mimeType\":\"image/png\"}" to "mcp: content missing image.data",
            "{\"type\":\"image\",\"data\":123,\"mimeType\":\"image/png\"}" to
                "mcp: content bad image.data",
            "{\"type\":\"audio\",\"mimeType\":\"audio/wav\"}" to "mcp: content missing audio.data",
            "{\"type\":\"resource\"}" to "mcp: content missing resource.resource",
            "{\"type\":\"resource\",\"resource\":\"str\"}" to "mcp: content bad resource.resource",
            "{\"type\":\"resource_link\",\"uri\":\"u\"}" to
                "mcp: content missing resource_link.name",
            // 未知型別訊息刻意靜態（不回顯伺服器字串 type 欄位）。
            "{\"type\":\"\"}" to "mcp: unknown content type",
            "{\"type\":\"future-block\"}" to "mcp: unknown content type",
            // 四缺 fixture（reviewer 追加）：逐型別必填表每一欄位各有一個缺點，
            // image 錯 mimeType 型別／audio 缺 mimeType／resource_link 缺 uri／
            // resource_link 錯 name 型別。
            "{\"type\":\"image\",\"data\":\"AA==\",\"mimeType\":123}" to
                "mcp: content bad image.mimeType",
            "{\"type\":\"audio\",\"data\":\"AA==\"}" to "mcp: content missing audio.mimeType",
            "{\"type\":\"resource_link\",\"name\":\"n\"}" to
                "mcp: content missing resource_link.uri",
            "{\"type\":\"resource_link\",\"uri\":\"u\",\"name\":123}" to
                "mcp: content bad resource_link.name",
            // mustBeString=false→true 鑑別力（reviewer P3）：flag 誤改 false 時
            // missing 案例訊息不變、測試仍綠——須有 bad-shape（非字串）案例才會紅。
            "{\"type\":\"audio\",\"data\":\"AA==\",\"mimeType\":123}" to
                "mcp: content bad audio.mimeType",
            "{\"type\":\"resource_link\",\"uri\":123,\"name\":\"n\"}" to
                "mcp: content bad resource_link.uri",
            "{\"type\":\"text\",\"text\":{}}" to "mcp: content bad text.text",
            // resource 內層二段驗證（外部 review「2026-10-19 re-review P2-1」fail-open）：外層 object 之後
            // 內層缺 uri／缺 text|blob／型別錯 → 靜態訊息 bad resource contents。
            // 完整矩陣（含正反鑑別）見 resourceContentRequiresUriAndTextOrBlob。
            "{\"type\":\"resource\",\"resource\":{}}" to "mcp: bad resource contents",
            "{\"type\":\"resource\",\"resource\":{\"uri\":\"u\"}}" to
                "mcp: bad resource contents",
            "{\"type\":\"resource\",\"resource\":null}" to "mcp: content missing resource.resource",
            "{\"type\":\"resource\",\"resource\":{\"text\":\"x\"}}" to
                "mcp: bad resource contents",
            "{\"type\":\"resource\",\"resource\":{\"uri\":\"u\",\"text\":1}}" to
                "mcp: bad resource contents",
            "{\"type\":\"resource\",\"resource\":{\"uri\":123,\"blob\":[]}}" to
                "mcp: bad resource contents",
        )
        for ((item, msg) in bads) {
            val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
                "{\"content\":[$item],\"isError\":false}}"
            try {
                McpWire.parseCall(json, expectedId = 2L)
                fail("壞 ContentBlock 必須拒：$item")
            } catch (e: McpWire.McpProtocol) {
                assertEquals(msg, e.message)
            }
        }
        // 混合陣列：任一元素壞即整包拒（loop 覆蓋每個元素，非只第一個）。
        val mixed = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":" +
            "[{\"type\":\"text\",\"text\":\"ok\"},{\"type\":\"text\"}],\"isError\":false}}"
        try {
            McpWire.parseCall(mixed, expectedId = 2L)
            fail("混合陣列中的壞元素必須拒")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: content missing text.text", e.message)
        }
        // 正例：已支援型別必填齊備＋未知鍵仍寬容（既有未知鍵政策不變）。
        // 五型別全列（reviewer P3）：缺正例時「合法 block 也拒」的反轉 mutation 不會紅。
        // resource 正例須帶 text|blob（舊 fixture `{"uri":"u"}` 在 P2-1 後已是負向，
        // 見 resourceContentRequiresUriAndTextOrBlob）。
        val ok = McpWire.parseCall(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":" +
                "[{\"type\":\"text\",\"text\":\"hi\",\"extra\":1}," +
                "{\"type\":\"image\",\"data\":\"AA==\",\"mimeType\":\"image/png\"}," +
                "{\"type\":\"audio\",\"data\":\"AA==\",\"mimeType\":\"audio/wav\"}," +
                "{\"type\":\"resource\",\"resource\":{\"uri\":\"u\",\"text\":\"embedded\"}}," +
                "{\"type\":\"resource_link\",\"uri\":\"u\",\"name\":\"n\"}]," +
                "\"isError\":false}}",
            expectedId = 2L,
        )
        assertFalse(ok.second)
    }

    // ---- resource 內層二段驗證（外部 review「2026-10-19 re-review P2-1」fail-open）----

    @Test fun resourceContentRequiresUriAndTextOrBlob() {
        // 內層政策：uri 必須**非空**字串；text/blob 至少一個存在且為字串；
        // 兩者並存時兩個都必須是字串（一正一錯即拒）；null 不算合法內容載體。
        // 訊息全程靜態（bad resource contents）——不逐項區分、不插值伺服器文字。
        val bads = mapOf(
            // 缺 type：元素層就拒（與 inner 驗證前後一致）。
            "{\"resource\":{\"uri\":\"u\"}}" to "mcp: bad content item",
            // 空物件：無 uri 無 text/blob。
            "{\"type\":\"resource\",\"resource\":{}}" to "mcp: bad resource contents",
            // 缺 text|blob（＝舊 review P2 repro：修前回 OK 且 onSuccess 重置熔斷）。
            "{\"type\":\"resource\",\"resource\":{\"uri\":\"u\"}}" to
                "mcp: bad resource contents",
            // 缺 uri。
            "{\"type\":\"resource\",\"resource\":{\"text\":\"x\"}}" to
                "mcp: bad resource contents",
            "{\"type\":\"resource\",\"resource\":{\"blob\":\"AA==\"}}" to
                "mcp: bad resource contents",
            // 空字串 uri 非「非空」。
            "{\"type\":\"resource\",\"resource\":{\"uri\":\"\",\"text\":\"x\"}}" to
                "mcp: bad resource contents",
            // 型別錯：uri 非字串／text 非字串／blob 非字串。
            "{\"type\":\"resource\",\"resource\":{\"uri\":123,\"blob\":[]}}" to
                "mcp: bad resource contents",
            "{\"type\":\"resource\",\"resource\":{\"uri\":\"u\",\"text\":1}}" to
                "mcp: bad resource contents",
            "{\"type\":\"resource\",\"resource\":{\"uri\":\"u\",\"blob\":{\"b\":1}}}" to
                "mcp: bad resource contents",
            // 兩者並存但一正一錯：不得只驗存在的那個。
            "{\"type\":\"resource\",\"resource\":{\"uri\":\"u\",\"text\":\"x\",\"blob\":1}}" to
                "mcp: bad resource contents",
            // null 不視為合法內容載體（fail-closed：null 內容＝無內容）。
            "{\"type\":\"resource\",\"resource\":{\"uri\":\"u\",\"text\":null}}" to
                "mcp: bad resource contents",
            // mixed null（review 指出的 mutation 盲點）：text:null＋合法 blob 不得被
            // 另一個載體掩蓋——null 不視為缺席載體，與 null-only 同拒。
            "{\"type\":\"resource\",\"resource\":{\"uri\":\"u\",\"text\":null,\"blob\":\"AA==\"}}" to
                "mcp: bad resource contents",
            "{\"type\":\"resource\",\"resource\":{\"uri\":\"u\",\"blob\":null}}" to
                "mcp: bad resource contents",
            // 深層夾帶：未知鍵巢狀的 text 不得充當內容載體（只認 resource 直接成員）。
            "{\"type\":\"resource\",\"resource\":{\"uri\":\"u\",\"x\":{\"text\":\"y\"}}}" to
                "mcp: bad resource contents",
        )
        for ((item, msg) in bads) {
            val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
                "{\"content\":[$item],\"isError\":false}}"
            try {
                McpWire.parseCall(json, expectedId = 2L)
                fail("畸形 resource 內層必須拒：$item")
            } catch (e: McpWire.McpProtocol) {
                assertEquals("靜態訊息不得隨內容變動：$item", msg, e.message)
            }
        }
        // 正向：text 單獨／blob 單獨（含 mimeType 未知鍵）／兩者並存皆 OK，
        // 且 resultJson 逐字元保留原文（raw-span 透傳，不重排不丟鍵）。
        val positives = listOf(
            "{\"type\":\"resource\",\"resource\":{\"uri\":\"u\",\"text\":\"hi\"}}",
            "{\"type\":\"resource\",\"resource\":{\"uri\":\"u\",\"blob\":\"AA==\",\"mimeType\":\"image/png\"}}",
            "{\"type\":\"resource\",\"resource\":{\"uri\":\"u\",\"text\":\"hi\",\"blob\":\"AA==\"}}",
            // 已知政策 pin：uri 只拒空字串、不 trim（空白字串照收，與 nextCursor 政策一致）——
            // 若日後改為 isBlank 拒收，此案例應翻轉為負向（改斷言 bad resource contents）。
            "{\"type\":\"resource\",\"resource\":{\"uri\":\" \",\"text\":\"x\"}}",
        )
        for (item in positives) {
            val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
                "{\"content\":[$item],\"isError\":false}}"
            val (resultJson, isError) = McpWire.parseCall(json, expectedId = 2L)
            assertFalse("合法 resource 不得 isError：$item", isError)
            assertEquals("resultJson 必須逐字元保留原文：$item", "[$item]", resultJson)
        }
    }

    @Test fun validationResultOrderCombinedFixture() {
        // 驗證順序 pin（reviewer 追加）：content 元素錯與 structuredContent 型別錯
        // 同時存在 → content 元素驗證先跑（文件化的順序），訊息是 content missing。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
            "{\"content\":[{\"type\":\"text\"}],\"structuredContent\":[1],\"isError\":false}}"
        try {
            McpWire.parseCall(json, expectedId = 2L)
            fail("驗證順序 fixture 必須拒")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: content missing text.text", e.message)
        }
        // 反向 pin：content 元素全好、structuredContent 型別錯 → 這時才是 bad structuredContent。
        val scOnly = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
            "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]," +
            "\"structuredContent\":[1],\"isError\":false}}"
        try {
            McpWire.parseCall(scOnly, expectedId = 2L)
            fail("structuredContent 陣列必須拒")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad structuredContent", e.message)
        }
    }

    @Test fun badContentWithIsErrorTrueIsProtocolNotToolError() {
        // 壞 content + isError:true（reviewer 追加）：形狀錯不得被 isError:true
        // 蓋成 TOOL_ERROR（那會當中性業務錯、不計熔斷）。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
            "{\"content\":[{\"type\":\"text\"}],\"isError\":true}}"
        try {
            McpWire.parseCall(json, expectedId = 2L)
            fail("壞 content + isError:true 必須 McpProtocol")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: content missing text.text", e.message)
        }
    }

    @Test fun emptyContentArrayIsExplicitlyOk() {
        // 空 content 陣列顯式 pin（reviewer 追加）：[] 是「有 content 鍵但零項目」
        // ——合法形狀（非空 result、非壞元素），維持 OK/isError=false。
        val (resultJson, isError) = McpWire.parseCall(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[],\"isError\":false}}",
            expectedId = 2L,
        )
        assertFalse(isError)
        assertEquals("[]", resultJson)
    }

    @Test fun contentMissingRequiredFieldMapsProtocolAndCountsBreaker() = runBlocking {
        // E2E（外部 review「前輪 P2-2」核心回歸）：200 + 只有 type 的 text block →
        // PROTOCOL + 計熔斷；不得回 OK、不得 onSuccess 重置熔斷。
        val web = MockWebServer()
        web.start()
        try {
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
                        "{\"content\":[{\"type\":\"text\"}],\"isError\":false}}",
                ),
            )
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            val got = deadline { server.callTool("t", "{}") }
            assertEquals(McpStatus.PROTOCOL, got.status)
            assertNull("PROTOCOL 路徑不得帶 resultJson", got.resultJson)
            assertEquals("畸形 ContentBlock 必須計熔斷（不得 onSuccess）", 1, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun malformedResourceContentOpensCircuit() = runBlocking {
        // E2E（外部 review「2026-10-19 re-review P2-1」回歸）：畸形 resource 內層（缺 text|blob）連打
        // 熔斷門檻次 → 逐次 PROTOCOL + 計熔斷，最後開路 CIRCUIT_OPEN。
        // 修前此 fixture 回 OK 且 onSuccess 重置熔斷——連打永不開路。
        val web = MockWebServer()
        web.start()
        try {
            val badBody = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
                "{\"content\":[{\"type\":\"resource\",\"resource\":{\"uri\":\"u\"}}]," +
                "\"isError\":false}}"
            repeat(3) { web.enqueue(MockResponse().setResponseCode(200).setBody(badBody)) }
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            for (expected in 1..3) {
                assertEquals(McpStatus.PROTOCOL, deadline { server.callTool("t", "{}") }.status)
                assertEquals("畸形 resource 必須逐次累計熔斷", expected, breaker.failureCount())
            }
            assertTrue("連打門檻次畸形 resource 必須開路", breaker.isOpen())
            assertEquals(
                McpStatus.CIRCUIT_OPEN,
                deadline { server.callTool("t", "{}") }.status,
            )
            assertEquals("開路後不得再發 HTTP", 3, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test fun missingInputSchemaListOpensCircuit() = runBlocking {
        // E2E（外部 review「2026-10-19 re-review P2-2」回歸）：tools/list 缺 inputSchema（頁內混合：
        // 一支合法、一支缺 schema）→ 整頁 PROTOCOL + 計熔斷；連打門檻次開路。
        // 修前缺 schema 合成 {"type":"object"} 回 OK——不互通的伺服器永不被發現。
        val web = MockWebServer()
        web.start()
        try {
            val badBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
                "[{\"name\":\"good\",\"inputSchema\":{\"type\":\"object\"}}," +
                "{\"name\":\"bad\"}]}}"
            repeat(3) { web.enqueue(MockResponse().setResponseCode(200).setBody(badBody)) }
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            for (expected in 1..3) {
                assertEquals(McpStatus.PROTOCOL, deadline { server.listTools() }.status)
                assertEquals("缺 inputSchema 必須逐次累計熔斷", expected, breaker.failureCount())
            }
            assertTrue("連打門檻次缺 inputSchema 必須開路", breaker.isOpen())
            assertEquals(
                McpStatus.CIRCUIT_OPEN,
                deadline { server.listTools() }.status,
            )
            assertEquals("開路後不得再發 HTTP", 3, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test fun errorMessageTruncationIsSurrogateSafe() {
        // 截斷點落在 surrogate pair 中間時，孤立 high surrogate 必須被去掉
        //（否則渲染/序列化變 U+FFFD、出站名變 '?'）。
        val emoji = "😀" // 2 個 UTF-16 code units
        // 199 個 x + emoji：take(200) 會落在 high surrogate 上 → 退 1 → 199 字元。
        val msg = "x".repeat(199) + emoji
        val truncated = McpWire.truncateUnits(msg, 200)
        assertEquals("截斷後不得含孤立 surrogate", 199, truncated.length)
        assertEquals("x", truncated.last().toString())
        // 198 個 x + emoji：take(200) 恰好含完整 pair → 保留。
        val exact = "x".repeat(198) + emoji
        assertEquals(200, McpWire.truncateUnits(exact, 200).length)
        // 未超限不動。
        assertEquals("short", McpWire.truncateUnits("short", 200))
    }

    @Test fun structuredContentNullIsTreatedAsAbsent() {
        // null 視為缺席（可選鍵寬容，與 nextCursor: null／isError: null 對稱）。
        // + content → 正常成功；單獨 null（無 content）→ 兩鍵皆無 → empty result 拒。
        val withContent = McpWire.parseCall(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
                "{\"content\":[],\"structuredContent\":null,\"isError\":false}}",
            expectedId = 2L,
        )
        assertFalse(withContent.second)
        assertEquals("[]", withContent.first)
        try {
            McpWire.parseCall(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
                    "{\"structuredContent\":null,\"isError\":false}}",
                expectedId = 2L,
            )
            fail("structuredContent:null 單獨存在等同空 result，必須拒")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: empty result", e.message)
        }
    }

    @Test fun emptyStructuredContentObjectIsKeptAsRaw() {
        // structuredContent:{} 空物件仍算有結構化結果（≠ 空 result），raw 全等 "{}"。
        val (resultJson, isError) = McpWire.parseCall(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
                "{\"structuredContent\":{},\"isError\":false}}",
            expectedId = 2L,
        )
        assertFalse(isError)
        assertEquals("{}", resultJson)
    }

    @Test fun contentNullIsTypeErrorUnlikeStructuredContentNull() {
        // content 非可選鍵：null 仍屬型別錯 → bad content（與 structuredContent 寬容 null 分歧，
        // 已文件化取捨）。驗證順序 pin：content 型別錯先於 structuredContent 型別錯。
        for (json in listOf(
            "{\"content\":null,\"structuredContent\":{\"k\":1}}",
            "{\"content\":42,\"structuredContent\":[1]}",
        )) {
            try {
                McpWire.parseCall("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":$json}", expectedId = 2L)
                fail("content 型別錯必須先擋：$json")
            } catch (e: McpWire.McpProtocol) {
                assertEquals("mcp: bad content", e.message)
            }
        }
    }

    @Test fun topLevelNextCursorIsIgnoredAsUnknownKey() {
        // 頂層同名鍵視為未知鍵忽略（僅認 result 內），不得誤判分頁。
        val page = McpWire.parseListPage(
            "s1",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"nextCursor\":\"x\"," +
                "\"result\":{\"tools\":[]}}",
            expectedId = 1L,
        )
        assertFalse("頂層 nextCursor 不得觸發 hasMorePages", page.hasMorePages)
    }

    @Test fun emptyResultThroughServerIsProtocol() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}",
                ),
            )
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            val got = deadline { server.callTool("t", "{}") }
            assertEquals("空 result 不得回 OK", McpStatus.PROTOCOL, got.status)
            assertEquals(1, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun isErrorWithoutContentIsProtocolAndCountsBreaker() = runBlocking {
        // isError=true 但 result 兩鍵皆無＝形狀不完整：不當業務錯（中性）、
        // 不當成功——一律 PROTOCOL + 計熔斷（fail-closed，KDoc 已文件化此分支）。
        // wire 直呼精確訊息 pin（E2E 之外再釘 wire 層訊息）。
        try {
            McpWire.parseCall(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"isError\":true}}",
                expectedId = 2L,
            )
            fail("無內容 isError 必須拒（mcp: empty result）")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: empty result", e.message)
        }
        val web = MockWebServer()
        web.start()
        try {
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"isError\":true}}",
                ),
            )
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            val got = deadline { server.callTool("t", "{}") }
            assertEquals("無內容 isError 不得當中性業務錯", McpStatus.PROTOCOL, got.status)
            assertEquals(1, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    // ---- errorMessage 透傳（隱私：不進稽核） ----

    @Test fun toolErrorMessagePropagatedButNotAudited() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32602,\"message\":\"bad\"}}",
                ),
            )
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = McpCircuitBreaker(),
            )
            val got = deadline { server.callTool("t", "{}") }
            assertEquals(McpStatus.TOOL_ERROR, got.status)
            assertEquals("伺服器 message 必須透傳", "bad", got.errorMessage)
            assertFalse("稽核不得含 error message", got.auditMap().toString().contains("bad"))
            assertFalse(
                "稽核鍵集合不得出現 errorMessage",
                got.auditMap().containsKey("errorMessage"),
            )
        } finally {
            web.shutdown()
        }
    }

    @Test fun errorPacketWithoutMessageIsProtocolAndCountsBreaker() = runBlocking {
        // 缺 message 的畸形 error 包（JSON-RPC 2.0 §5.1 必備欄位）：PROTOCOL + 計熔斷，
        // 不得當中性工具錯、不得回退預設字串（外部 review P3-1）；
        // PROTOCOL 路徑無 errorMessage（與 protocolErrorPathLeavesErrorMessageNull 同語義）。
        val web = MockWebServer()
        web.start()
        try {
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32602}}",
                ),
            )
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            val got = deadline { server.callTool("t", "{}") }
            assertEquals(McpStatus.PROTOCOL, got.status)
            assertNull("PROTOCOL 路徑不得帶 errorMessage", got.errorMessage)
            assertEquals("畸形 error 必須計熔斷（不得中性）", 1, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun longErrorMessageIsTruncatedTo200() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            val longMsg = "x".repeat(500)
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":" +
                        "{\"code\":-32602,\"message\":\"$longMsg\"}}",
                ),
            )
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = McpCircuitBreaker(),
            )
            val got = deadline { server.callTool("t", "{}") }
            assertEquals(McpStatus.TOOL_ERROR, got.status)
            assertEquals("message 必須截到 200 字元", 200, got.errorMessage?.length)
        } finally {
            web.shutdown()
        }
    }

    @Test fun controlCharErrorMessageIsEscapedEndToEnd() = runBlocking {
        // Stage3 A-02 E2E：伺服器 message 含 ESC 序列＋CR/LF → errorMessage 不含裸
        // C0/DEL（wire 層已換 \uXXXX 轉義；server 層鉗制為 no-op）——上層錯誤呈現
        // 不得外顯伺服器控制字元。修飾前 errorMessage 含裸 \u001b/\r\n，本測即紅。
        val web = MockWebServer()
        web.start()
        try {
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32602," +
                        "\"message\":\"\\u001b[31mFAKE\\r\\nline\"}}",
                ),
            )
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = McpCircuitBreaker(),
            )
            val got = deadline { server.callTool("t", "{}") }
            assertEquals(McpStatus.TOOL_ERROR, got.status)
            val msg = got.errorMessage!!
            assertTrue("errorMessage 不得含裸 C0/DEL", msg.none { it.code < 0x20 || it.code == 0x7F })
            assertEquals("\\u001b[31mFAKE\\u000d\\u000aline", msg)
            assertTrue(msg.length <= McpWire.ERROR_MESSAGE_MAX_UNITS)
            assertFalse("稽核不得含 errorMessage", got.auditMap().toString().contains("FAKE"))
        } finally {
            web.shutdown()
        }
    }

    @Test fun emptyErrorMessageMapsToStaticTextEndToEnd() = runBlocking {
        // Stage3 B-03 E2E：空 message → errorMessage 為靜態 `jsonrpc error -32602`
        //（不是空字串——伺服器不得以空 message 讓錯誤不可辨識），仍 TOOL_ERROR 中性。
        val web = MockWebServer()
        web.start()
        try {
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32602,\"message\":\"\"}}",
                ),
            )
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            val got = deadline { server.callTool("t", "{}") }
            assertEquals(McpStatus.TOOL_ERROR, got.status)
            assertEquals("jsonrpc error -32602", got.errorMessage)
            assertEquals("空 message 仍屬中性工具錯（不計熔斷）", 0, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun protocolErrorPathLeavesErrorMessageNull() = runBlocking {
        // PROTOCOL 路徑（協議碼 -32601）：errorMessage 為 null（僅 TOOL_ERROR 才有）。
        val web = MockWebServer()
        web.start()
        try {
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32601,\"message\":\"m\"}}",
                ),
            )
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            val got = deadline { server.callTool("t", "{}") }
            assertEquals(McpStatus.PROTOCOL, got.status)
            assertNull("PROTOCOL 路徑不得帶 errorMessage", got.errorMessage)
            assertEquals(1, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun okResultHasNoErrorMessage() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
                        "{\"content\":[],\"isError\":false}}",
                ),
            )
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = McpCircuitBreaker(),
            )
            val got = deadline { server.callTool("t", "{}") }
            assertEquals(McpStatus.OK, got.status)
            assertNull(got.errorMessage)
        } finally {
            web.shutdown()
        }
    }

    @Test fun isErrorResultMapsToToolErrorWithNullMessage() = runBlocking {
        // isError=true 結果路徑的 TOOL_ERROR：errorMessage 為 null（無伺服器 message，
        // 詳情在 resultJson）——與 error 包路徑（透傳伺服器 message、上限 200）的分歧已文件化。
        val web = MockWebServer()
        web.start()
        try {
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
                        "{\"content\":[{\"type\":\"text\",\"text\":\"failed\"}],\"isError\":true}}",
                ),
            )
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = McpCircuitBreaker(),
            )
            val got = deadline { server.callTool("t", "{}") }
            assertEquals(McpStatus.TOOL_ERROR, got.status)
            assertTrue(got.isError)
            assertNull("isError 結果路徑無伺服器 message", got.errorMessage)
        } finally {
            web.shutdown()
        }
    }

    // ---- HTTP 4xx taxonomy ----

    @Test fun configuration4xxMapsToProtocolAndCountsBreaker() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // 400/404/405：端點/請求形狀配置錯（非暫態）→ PROTOCOL + 計熔斷。
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            for ((code, expectCount) in listOf(400 to 1, 404 to 2, 405 to 3)) {
                web.enqueue(MockResponse().setResponseCode(code).setBody("nope"))
                val call = deadline { server.callTool("t", "{}") }
                assertEquals("call HTTP $code 必須 PROTOCOL", McpStatus.PROTOCOL, call.status)
                assertEquals(expectCount, breaker.failureCount())
            }
            assertTrue(breaker.isOpen())
        } finally {
            web.shutdown()
        }
    }

    @Test fun configuration4xxOnListIsProtocol() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // list 端同待遇：400/404/405 → PROTOCOL + 計熔斷（與 call 端一致）。
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            for ((code, expectCount) in listOf(400 to 1, 404 to 2, 405 to 3)) {
                web.enqueue(MockResponse().setResponseCode(code).setBody("nope"))
                val got = deadline { server.listTools() }
                assertEquals("list HTTP $code 必須 PROTOCOL", McpStatus.PROTOCOL, got.status)
                assertTrue(got.tools.isEmpty())
                assertEquals("list HTTP $code 必須計熔斷", expectCount, breaker.failureCount())
            }
        } finally {
            web.shutdown()
        }
    }

    @Test fun transient4xxStillMapsToTransport() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // 429（限流）/408（逾時）屬暫態 → TRANSPORT（不得誤判配置錯）；
            // 熔斷待遇相同：皆計失敗（分類只差 status 標籤）。
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            var expectCount = 0
            for (code in listOf(429, 408)) {
                web.enqueue(MockResponse().setResponseCode(code).setBody("slow down"))
                val call = deadline { server.callTool("t", "{}") }
                assertEquals("HTTP $code 必須 TRANSPORT", McpStatus.TRANSPORT, call.status)
                assertEquals(++expectCount, breaker.failureCount())
            }
            // 408 是 OkHttp replay 分支之一（由 one-shot body 擋下）：
            // 恰兩發請求＝兩碼各一發，無隱性重送。
            assertEquals("429/408 不得觸發重送", 2, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    // ---- Stage4 覆蓋清掃：DISABLED／vault-miss／空 body ----

    @Test fun disabledServerOrToolShortCircuitsWithoutHttp() = runBlocking {
        // 主開關關閉與逐工具關閉皆須短路：不發 HTTP、不碰 vault。
        val web = MockWebServer()
        web.start()
        try {
            val offVault = FakeVault()
            val off = McpServer(
                config = McpServerConfig(
                    id = "s1",
                    baseUrl = web.url("/").toString(),
                    enabled = false,
                ),
                keyVault = offVault,
                transport = McpTransport(),
                breaker = McpCircuitBreaker(),
            )
            assertEquals(McpStatus.DISABLED, off.listTools().status)
            assertEquals(McpStatus.DISABLED, off.callTool("t", "{}").status)
            val perToolVault = FakeVault()
            val perTool = McpServer(
                config = McpServerConfig(
                    id = "s1",
                    baseUrl = web.url("/").toString(),
                    enabledTools = mapOf("t" to false),
                ),
                keyVault = perToolVault,
                transport = McpTransport(),
                breaker = McpCircuitBreaker(),
            )
            assertEquals(McpStatus.DISABLED, perTool.callTool("t", "{}").status)
            assertEquals("停用不得發 HTTP", 0, web.requestCount)
            assertEquals("停用不得碰 vault（主開關）", 0, offVault.reads)
            assertEquals("停用不得碰 vault（逐工具）", 0, perToolVault.reads)
        } finally {
            web.shutdown()
        }
    }

    @Test fun qualifiedToolNameMapsToProtocolWithoutHttpVaultOrBreaker() = runBlocking {
        // C-02 E2E：已限定名（`mcp.<id>.<tool>`，ToolDef.name 形狀）不得被當 bare 名
        // 發送——舊路徑 enabledTools 以 "mcp.s1.search" 查鍵、缺鍵預設啟用，
        // 停用清單（"search"→false）被靜默繞過且真的發送。形狀閘（C-02）fail-closed：
        // PROTOCOL、不發送（requestCount==0）、不碰 vault、不計熔斷（走 callTool 既有
        // IAE catch，不新增分支待遇）。
        val web = MockWebServer()
        web.start()
        try {
            val breaker = McpCircuitBreaker()
            val vault = FakeVault()
            val server = McpServer(
                config = McpServerConfig(
                    id = "s1",
                    baseUrl = web.url("/").toString(),
                    enabledTools = mapOf("search" to false),
                ),
                keyVault = vault,
                transport = McpTransport(),
                breaker = breaker,
            )
            val got = deadline { server.callTool("mcp.s1.search", "{}") }
            assertEquals(McpStatus.PROTOCOL, got.status)
            assertEquals("已限定名不得發送 HTTP", 0, web.requestCount)
            assertEquals("已限定名不得碰 vault", 0, vault.reads)
            assertEquals("已限定名不得計熔斷", 0, breaker.failureCount())
            // 同待遇取樣：C0 控制字元名與超長名同樣 PROTOCOL、不發送。
            for (bad in listOf("bad\nname", "t".repeat(McpWire.TOOL_NAME_MAX_CHARS + 1))) {
                assertEquals(McpStatus.PROTOCOL, deadline { server.callTool(bad, "{}") }.status)
                assertEquals("控制字元/超長名不得發送 HTTP", 0, web.requestCount)
                assertEquals("控制字元/超長名不得碰 vault", 0, vault.reads)
                assertEquals("控制字元/超長名不得計熔斷", 0, breaker.failureCount())
            }
            // 對照：bare 停用名維持既有 DISABLED 語意（形狀閘不改動停用判定）。
            assertEquals(McpStatus.DISABLED, deadline { server.callTool("search", "{}") }.status)
            assertEquals(0, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test fun reservedPrefixToolListMapsToProtocolAndCountsBreaker() = runBlocking {
        // S2-R01/S2-D02 E2E：遠端 tools/list 回含保留前綴 `mcp.x` 的頁 → 整頁
        // PROTOCOL＋計熔斷（入站 parseListPage 拋 McpProtocol 的既有 catch 分支，
        // 與超限名／控制字元名同待遇）；不回 OK、不帶出任何工具。
        val web = MockWebServer()
        web.start()
        try {
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
                        "[{\"name\":\"mcp.x\",\"inputSchema\":{\"type\":\"object\"}}]}}",
                ),
            )
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            val got = deadline { server.listTools() }
            assertEquals(McpStatus.PROTOCOL, got.status)
            assertTrue("保留前綴名頁不得帶出任何工具", got.tools.isEmpty())
            assertEquals("保留前綴名必須計熔斷", 1, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun shapeGateRunsAfterDisabledAndCircuitOpenShortCircuits() = runBlocking {
        // S2-D01 先後序 pin：形狀閘（requireBareToolName）在 DISABLED／CIRCUIT_OPEN
        // **之後**、callBody **之前**——便宜短路先於形狀閘（見 McpServer.callTool KDoc）。
        val web = MockWebServer()
        web.start()
        try {
            // ① 停用 server ＋已限定名 `mcp.s1.search` → DISABLED（不是 PROTOCOL）：
            // 便宜判定先跑，形狀閘不執行，不碰 vault、不發 HTTP。
            val vault = FakeVault()
            val off = McpServer(
                config = McpServerConfig(
                    id = "s1",
                    baseUrl = web.url("/").toString(),
                    enabled = false,
                ),
                keyVault = vault,
                transport = McpTransport(),
                breaker = McpCircuitBreaker(),
            )
            assertEquals(McpStatus.DISABLED, deadline { off.callTool("mcp.s1.search", "{}") }.status)
            assertEquals("停用短路不得碰 vault", 0, vault.reads)
            assertEquals("停用短路不得發 HTTP", 0, web.requestCount)
            // ② 熔斷已開路＋非法名 `mcp.s1.search` → CIRCUIT_OPEN（不是 PROTOCOL）：
            // 開路判定先於形狀閘，同樣不碰 vault、不發 HTTP、不重計熔斷。
            val breaker = McpCircuitBreaker()
            val openVault = FakeVault()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = openVault,
                transport = McpTransport(),
                breaker = breaker,
            )
            repeat(3) { breaker.onFailure() }
            assertTrue("前提：熔斷已開路", breaker.isOpen())
            assertEquals(
                McpStatus.CIRCUIT_OPEN,
                deadline { server.callTool("mcp.s1.search", "{}") }.status,
            )
            assertEquals("開路短路不得碰 vault", 0, openVault.reads)
            assertEquals("開路短路不得發 HTTP", 0, web.requestCount)
            assertEquals("CIRCUIT_OPEN 短路不得重計熔斷", 3, breaker.failureCount())
            // 對照（先後序反面）：同一非法名在「啟用＋熔斷閉合」時才走形狀閘 → PROTOCOL。
            val liveVault = FakeVault()
            val live = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = liveVault,
                transport = McpTransport(),
                breaker = McpCircuitBreaker(),
            )
            assertEquals(McpStatus.PROTOCOL, deadline { live.callTool("mcp.s1.search", "{}") }.status)
            assertEquals(0, liveVault.reads)
            assertEquals("形狀閘違反不得發 HTTP", 0, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test fun loneSurrogateAsymmetryScopeAllowsButCallToolRejects() = runBlocking {
        // S2-D03 不對稱交叉 pin：requireBareToolName 不查孤立 surrogate（scope 放行，
        // 只用來查鍵／建開關鍵、不出網路），出站 McpWire.callBody 一律拒 →
        // callTool 回 PROTOCOL、不發送、不碰 vault、不計熔斷。
        val web = MockWebServer()
        web.start()
        try {
            val cfg = McpServerConfig(id = "s1", baseUrl = web.url("/").toString())
            val lone = "a\uD800"
            assertTrue(
                "scope 側放行（已知不對稱，見 requireBareToolName KDoc）",
                McpScope.canExecute(cfg, lone, confirmed = true),
            )
            val vault = FakeVault()
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = cfg,
                keyVault = vault,
                transport = McpTransport(),
                breaker = breaker,
            )
            assertEquals(McpStatus.PROTOCOL, deadline { server.callTool(lone, "{}") }.status)
            assertEquals("孤立 surrogate 不得發送", 0, web.requestCount)
            assertEquals("孤立 surrogate 不得碰 vault", 0, vault.reads)
            assertEquals("孤立 surrogate 不得計熔斷", 0, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun missingVaultKeyMapsToUnauthorizedWithoutHttp() = runBlocking {
        // vault 無 key：UNAUTHORIZED、不發 HTTP、不計熔斷（配置問題非傳輸失敗）。
        val web = MockWebServer()
        web.start()
        try {
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = EmptyVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            assertEquals(McpStatus.UNAUTHORIZED, server.listTools().status)
            assertEquals(McpStatus.UNAUTHORIZED, server.callTool("t", "{}").status)
            assertEquals("無 key 不得發 HTTP", 0, web.requestCount)
            assertEquals("無 key 不計熔斷", 0, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun blankBaseUrlIsUnauthorizedWithoutHttp() = runBlocking {
        // 空 baseUrl：配置錯誤 → UNAUTHORIZED（與 vault-miss 同待遇），
        // 不發 HTTP、不碰 vault、不計熔斷（此分支曾零覆蓋，final review P2）。
        val web = MockWebServer()
        web.start()
        try {
            val vault = FakeVault()
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = "   "),
                keyVault = vault,
                transport = McpTransport(),
                breaker = breaker,
            )
            assertEquals(McpStatus.UNAUTHORIZED, server.listTools().status)
            assertEquals(McpStatus.UNAUTHORIZED, server.callTool("t", "{}").status)
            assertEquals("空地址不得發 HTTP", 0, web.requestCount)
            assertEquals("空地址不得碰 vault", 0, vault.reads)
            assertEquals("空地址不計熔斷", 0, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun oversizedSuccessListBodyIsProtocolNotOk() = runBlocking {
        // list 端 200＋超量 body → PROTOCOL + 計熔斷（McpResponseTooLarge catch
        // 的 list 分支，曾無 E2E——刪掉會靜默降級 TRANSPORT）。
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
            web.enqueue(
                MockResponse().setResponseCode(200)
                    .setChunkedBody("[".repeat(300 * 1024), 8192),
            )
            val got = deadline { server.listTools() }
            assertEquals("超量 200 list 不得回 OK", McpStatus.PROTOCOL, got.status)
            assertTrue(got.tools.isEmpty())
            assertEquals(1, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun blankBodyWith2xxIsTransportNotProtocol() = runBlocking {
        // 2xx＋空白 body（Content-Type 缺席情境）：不進 parser（與解析失敗→PROTOCOL 分界），
        // 走 TRANSPORT。list 與 call 是兩條獨立的 isNullOrBlank 分支，各自驗證。
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
            web.enqueue(MockResponse().setResponseCode(200).setBody(""))
            val call = deadline { server.callTool("t", "{}") }
            assertEquals(McpStatus.TRANSPORT, call.status)
            assertEquals(1, breaker.failureCount())
            web.enqueue(MockResponse().setResponseCode(200).setBody(""))
            val list = deadline { server.listTools() }
            assertEquals(McpStatus.TRANSPORT, list.status)
            assertEquals(2, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }
}
