package dev.librepocket.mcp

import dev.librepocket.keystore.KeyVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Stage2 envelope 完整性契約（外部獨立 review #2／#4）：
 * `jsonrpc=="2.0"`、id expectedId 校驗、result XOR error、
 * error code taxonomy（協議碼與保留 server 範圍 −32099..−32000 → [McpWire.McpProtocol]
 * → PROTOCOL + 計熔斷；`-32602`/範圍外未知碼 → [McpWire.McpToolFailure] → TOOL_ERROR 中性）、
 * request-shaped 拒、出站預檢（toolName 長度、arguments 上限）。
 *
 * 全程 MockWebServer／純 wire 解析，不連外網。
 */
class McpEnvelopeTest {

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

    // ---- error code taxonomy（wire 層型別分類） ----

    @Test fun protocolErrorCodesAreMcpProtocol() {
        // -32700 parse error／-32600 invalid request／-32601 method not found／
        // -32603 internal error：協議/伺服器端錯 → McpProtocol（server → PROTOCOL + 熔斷）。
        for (code in listOf(-32700, -32600, -32601, -32603)) {
            val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":$code,\"message\":\"e$code\"}}"
            try {
                McpWire.parseCall(json)
                fail("協議碼 $code 必須拋 McpProtocol")
            } catch (e: McpWire.McpProtocol) {
                assertEquals("mcp: jsonrpc error $code", e.message)
            }
        }
    }

    @Test fun invalidParamsAndUnknownCodesAreToolFailure() {
        // -32602（invalid params）與範圍外未知碼：工具級中性，message 保留伺服器文字。
        //（−32000 已移出此清單：保留 server 範圍見 serverErrorRangeCodesAreProtocol——
        // 外部 review「前輪 P2-1」修正，舊 pin 把伺服器失敗鎖為中性是錯的。）
        val cases = mapOf(
            -32602 to "bad input",
            -32100 to "just below range",
            -31999 to "just above range",
            1 to "outer",
        )
        for ((code, msg) in cases) {
            val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":$code,\"message\":\"$msg\"}}"
            try {
                McpWire.parseCall(json)
                fail("code $code 必須拋 McpToolFailure")
            } catch (e: McpWire.McpToolFailure) {
                assertEquals(msg, e.message)
            }
        }
    }

    @Test fun serverErrorRangeCodesAreProtocol() {
        // JSON-RPC 2.0 §5.1 保留 server 範圍（−32099..−32000）：伺服器端失敗，
        // 非工具業務錯——與協議碼同待遇（外部 review「前輪 P2-1」；含邊界與範圍中點）。
        for (code in listOf(-32099L, -32050L, -32000L)) {
            val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":$code,\"message\":\"srv\"}}"
            try {
                McpWire.parseCall(json)
                fail("server 範圍碼 $code 必須拋 McpProtocol")
            } catch (e: McpWire.McpProtocol) {
                assertEquals("mcp: jsonrpc error $code", e.message)
            }
        }
    }

    @Test fun errorMessageIsRequiredString() {
        // JSON-RPC 2.0 §5.1：error.message 必備 String。缺／非字串屬形狀不完整
        //（外部 review P3-1）——不得默默當中性業務錯（否則畸形 error 永不計熔斷）。
        val bads = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32602}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":1,\"message\":123}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":1,\"message\":null}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":1,\"message\":{}}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32700}}",
            // server 範圍碼（−32099..−32000）缺 message 同樣 bad error：
            // 形狀驗證先於 code 分類，不得因最終會計熔斷就跳過 message 檢查。
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32000}}",
        )
        for (bad in bads) {
            try {
                McpWire.parseCall(bad)
                fail("缺／非字串 message 必須拒：$bad")
            } catch (e: McpWire.McpProtocol) {
                assertEquals("mcp: bad error", e.message)
            }
        }
        // 空字串 message 仍是合法 String（規格只要求型別）：維持中性工具錯。
        try {
            McpWire.parseCall(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":1,\"message\":\"\"}}",
            )
            fail("空字串 message 必須走 McpToolFailure")
        } catch (e: McpWire.McpToolFailure) {
            assertEquals("", e.message)
        }
    }

    @Test fun errorWithoutIntegerCodeIsProtocol() {
        // code 缺失／字串／非整數／溢位：不得靜默當業務錯，一律 McpProtocol。
        val bads = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"message\":\"m\"}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":\"-32601\",\"message\":\"m\"}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":1.5,\"message\":\"m\"}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":null,\"message\":\"m\"}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":1e3,\"message\":\"m\"}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2," +
                "\"error\":{\"code\":99999999999999999999,\"message\":\"m\"}}",
        )
        for (bad in bads) {
            try {
                McpWire.parseCall(bad)
                fail("非法 code 必須拒：$bad")
            } catch (e: McpWire.McpProtocol) {
                assertEquals("mcp: bad error", e.message)
            }
        }
    }

    @Test fun nonObjectErrorIsProtocol() {
        // error 鍵存在但非 object（字串／數字）：bad error。
        for (bad in listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":\"oops\"}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":42}",
        )) {
            try {
                McpWire.parseCall(bad)
                fail("非 object error 必須拒：$bad")
            } catch (e: McpWire.McpProtocol) {
                assertEquals("mcp: bad error", e.message)
            }
        }
    }

    // ---- request-shaped（method+result hybrid） ----

    @Test fun requestShapedHybridRejected() {
        // server 端請求包（含 method）混入 result：不是本子集的回應。
        val hybrid = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"," +
            "\"result\":{\"tools\":[]}}"
        try {
            McpWire.parseList("s1", hybrid, expectedId = 1L)
            fail("method+result hybrid 必須拒")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: request-shaped response", e.message)
        }
        val callHybrid = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\"," +
            "\"result\":{\"content\":[],\"isError\":false}}"
        try {
            McpWire.parseCall(callHybrid, expectedId = 2L)
            fail("method+result hybrid 必須拒")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: request-shaped response", e.message)
        }
        // 純 server 端請求包（method+id、無 result）同樣拒（雙 parser）。
        val requestOnly = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"
        try {
            McpWire.parseList("s1", requestOnly, expectedId = 1L)
            fail("純請求包不得回工具列表")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: request-shaped response", e.message)
        }
        try {
            McpWire.parseCall("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\"}")
            fail("純請求包不得回 OK")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: request-shaped response", e.message)
        }
    }

    // ---- list 端 error 包：不分 code 一律拒（與 call taxonomy 不對稱） ----

    @Test fun listErrorPacketRejectedEvenForToolNeutralCodes() {
        // -32602 對 call 中性（invalidParamsStaysNeutralThroughServer），
        // 對 list 一律拒——不對稱分類 pin（KDoc 宣稱，刪分支即紅）。
        for (code in listOf(-32601, -32602, -32000)) {
            val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":$code,\"message\":\"m\"}}"
            try {
                McpWire.parseList("s1", json, expectedId = 1L)
                fail("list 不得收 error 包：code=$code")
            } catch (e: McpWire.McpProtocol) {
                assertEquals("mcp: unexpected error response", e.message)
            }
        }
        // list 層 result+error 矛盾包不得採納 result（刪分支後此例會回工具列表，故有鑑別力）。
        val both = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}," +
            "\"error\":{\"code\":-32602,\"message\":\"m\"}}"
        try {
            McpWire.parseList("s1", both, expectedId = 1L)
            fail("list 不得收 result+error")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: unexpected error response", e.message)
        }
        // error:null 亦拒（error 鍵存在即拒，含 null——與 call 端 bad error 對稱）。
        try {
            McpWire.parseList(
                "s1",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]},\"error\":null}",
                expectedId = 1L,
            )
            fail("list 不得收 error:null")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: unexpected error response", e.message)
        }
    }

    // ---- 出站預檢（callBody fail-closed） ----

    @Test fun callBodyRejectsOversizedToolName() {
        val long = "t".repeat(McpWire.TOOL_NAME_MAX_CHARS + 1)
        try {
            McpWire.callBody(long, "{}")
            fail("超長 toolName 必須拒（不發送）")
        } catch (e: IllegalArgumentException) {
            assertEquals(
                "mcp: tool name exceeds ${McpWire.TOOL_NAME_MAX_CHARS} chars",
                e.message,
            )
        }
        // 邊界值仍過。
        McpWire.parseStrictDoc(
            McpWire.callBody("t".repeat(McpWire.TOOL_NAME_MAX_CHARS), "{}"),
        )
    }

    @Test fun oversizedInboundToolNameIsRejectedNotTruncated() {
        // 對稱政策 pin（外部 review P3-3）：入站（list 回應）超限名與出站（callBody）
        // 同待遇——整頁拒（[McpWire.McpProtocol]），不靜默截斷。截斷後名會被
        // callBody 當成另一支工具真的送達伺服器，且 enabledTools／McpScope 以名為鍵，
        // 前綴相同的長名碰撞即授權對象錯位，故 fail-closed。
        val name200 = "n".repeat(McpWire.TOOL_NAME_MAX_CHARS + 72)
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
            "[{\"name\":\"$name200\",\"inputSchema\":{\"type\":\"object\"}}]}}"
        try {
            McpWire.parseList("s1", json, expectedId = 1L)
            fail("入站超限名必須拒（不得截斷採納）")
        } catch (e: McpWire.McpProtocol) {
            assertEquals(
                "mcp: tool name exceeds ${McpWire.TOOL_NAME_MAX_CHARS} chars",
                e.message,
            )
        }
        // 邊界值（恰 128）仍採納。
        val exact = "n".repeat(McpWire.TOOL_NAME_MAX_CHARS)
        val okJson = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
            "[{\"name\":\"$exact\",\"inputSchema\":{\"type\":\"object\"}}]}}"
        assertEquals(1, McpWire.parseList("s1", okJson, expectedId = 1L).size)
        // 恰 129（+1）拒——不只測 200：界限本身必須被 pin（reviewer 追加）。
        val one = "n".repeat(McpWire.TOOL_NAME_MAX_CHARS + 1)
        val oneJson = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
            "[{\"name\":\"$one\",\"inputSchema\":{\"type\":\"object\"}}]}}"
        try {
            McpWire.parseList("s1", oneJson, expectedId = 1L)
            fail("129 units 名必須拒")
        } catch (e: McpWire.McpProtocol) {
            assertEquals(
                "mcp: tool name exceeds ${McpWire.TOOL_NAME_MAX_CHARS} chars",
                e.message,
            )
        }
        // 129 units 含 emoji（127 n + 😀 = 129 units）：units 計數（非 code point），
        // 含成對 surrogate 也一樣拒——改用 code point 計數此處即紅。
        val emojiName = "n".repeat(McpWire.TOOL_NAME_MAX_CHARS - 1) + "😀"
        assertEquals(McpWire.TOOL_NAME_MAX_CHARS + 1, emojiName.length)
        val emojiJson = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
            "[{\"name\":\"$emojiName\",\"inputSchema\":{\"type\":\"object\"}}]}}"
        try {
            McpWire.parseList("s1", emojiJson, expectedId = 1L)
            fail("129 units（含 emoji）名必須拒")
        } catch (e: McpWire.McpProtocol) {
            assertEquals(
                "mcp: tool name exceeds ${McpWire.TOOL_NAME_MAX_CHARS} chars",
                e.message,
            )
        }
    }

    @Test fun inboundToolNameControlCharsRejectedFailClosed() {
        // 伺服器控制的入站名流進 auditMap（tools 名清單）與以名為鍵的開關——CR/LF 可偽造
        // 稽核列、C0/ESC 可干擾終端（外部 review P3-3）。fail-closed 整頁拒，不採納。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
            "[{\"name\":\"bad\\nname\",\"inputSchema\":{\"type\":\"object\"}}]}}"
        try {
            McpWire.parseList("s1", json, expectedId = 1L)
            fail("含 CR/LF 控制字元的入站名必須拒")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: tool name has control chars", e.message)
        }
        // 跨 C0 範圍＋DEL 取樣（JSON \uXXXX 轉義——raw control char 會先破壞 JSON parse）：
        // 每個都須拒。`>= 0x7F` 誤刪非 ASCII 由下方 read_é文 正向對照釘住；
        // `<= 0x20` 誤拒空白由 inboundToolNameWithSpaceIsAccepted 釘住。
        for (esc in listOf("\\n", "\\r", "\\t", "\\u001b", "\\u007f")) {
            val j = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
                "[{\"name\":\"a${esc}b\",\"inputSchema\":{\"type\":\"object\"}}]}}"
            try {
                McpWire.parseList("s1", j, expectedId = 1L)
                fail("控制字元 [$esc] 必須拒")
            } catch (e: McpWire.McpProtocol) {
                assertEquals("mcp: tool name has control chars", e.message)
            }
        }
        // 邊界：非 ASCII 非控制字母（é、中文）仍採納——預檢只拒 C0/DEL，不是
        // printable-ASCII 一刀切（否則會誤拒合法國際化工具名）。
        val ok = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
            "[{\"name\":\"read_é文\",\"inputSchema\":{\"type\":\"object\"}}]}}"
        assertEquals(1, McpWire.parseList("s1", ok, expectedId = 1L).size)
    }

    @Test fun inboundToolNameWithSpaceIsAccepted() {
        // 政策 pin：工具名「內含」空格照收（`my tool`）。沒有此測，控制字元檢查
        // `it.code < 0x20` 被改成 `<= 0x20` 會誤拒半形空格且無測試變紅。
        // 注意：**純空白名**已改為入站整頁拒（見 inboundBlankToolNameRejectedWholePage，
        // re-review F-S2-01/F-S2-02 政策收緊）。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
            "[{\"name\":\"my tool\",\"inputSchema\":{\"type\":\"object\"}}]}}"
        val tools = McpWire.parseList("s1", json, expectedId = 1L)
        assertEquals(1, tools.size)
        assertEquals("my tool", tools[0].name)
    }

    @Test fun inboundBlankToolNameRejectedWholePage() {
        // re-review F-S2-01/F-S2-02：空白／空名**整頁拒**（靜態訊息、不回顯伺服器字串）。
        // 與保留前綴同政策——空白名在所有 McpScope 授權入口都被 requireBareToolName
        // 拒（isNotBlank），照收會「列出但不可用」且讓未來接線 `map { toToolDef(...) }`
        // 拋未映射 IAE 打斷整輪。isBlank() 涵蓋 Unicode 空白（全形空格／NBSP）。
        // mutation（拿掉 parseListPage 的空白檢查）此測即紅。
        val page = { name: String ->
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
                "[{\"name\":\"$name\",\"inputSchema\":{\"type\":\"object\"}}]}}"
        }
        for (name in listOf("", " ", "   ", "\u3000", "\u00a0")) {
            try {
                McpWire.parseList("s1", page(name), expectedId = 1L)
                fail("空白名必須整頁拒：[$name]")
            } catch (e: McpWire.McpProtocol) {
                assertEquals("mcp: tool name is blank", e.message)
            }
        }
        // 混頁（一支合法＋一支純空白）→ 同樣整頁拒（不靜默略過單支）。
        try {
            McpWire.parseList(
                "s1",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
                    "[{\"name\":\"ok\",\"inputSchema\":{\"type\":\"object\"}}," +
                    "{\"name\":\" \",\"inputSchema\":{\"type\":\"object\"}}]}}",
                expectedId = 1L,
            )
            fail("混頁含空白名必須整頁拒")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: tool name is blank", e.message)
        }
    }

    @Test fun inboundReservedPrefixToolNameRejectedWholePage() {
        // S2-R01/S2-D02：保留前綴 `mcp.`（＝McpScope.SWITCH_PREFIX，單一真相經
        // McpWire.RESERVED_TOOL_PREFIX 引用）開頭的遠端名**整頁拒**——保留前綴兩端
        // 同政策（入站整頁拒、出站 requireBareToolName 拒用）。照收會「列出但不可用」
        //（所有 McpScope 授權入口都拒此前綴），且未來接線 `map { toToolDef(...) }`
        // 會拋未映射 IAE 打斷整輪。mutation（拿掉 parseListPage 的前綴檢查）此測即紅。
        val page = { tools: String ->
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":$tools}}"
        }
        // ① 單支 `mcp.x` → McpProtocol、靜態訊息。
        try {
            McpWire.parseList(
                "s1",
                page("""[{"name":"mcp.x","inputSchema":{"type":"object"}}]"""),
                expectedId = 1L,
            )
            fail("保留前綴名必須整頁拒")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: tool name uses reserved prefix", e.message)
        }
        // ② 混頁（一支合法＋一支保留前綴）→ 同樣整頁拒（不只擋單支頁、不靜默略過）。
        try {
            McpWire.parseList(
                "s1",
                page(
                    """[{"name":"good","inputSchema":{"type":"object"}},""" +
                        """{"name":"mcp.s1.search","inputSchema":{"type":"object"}}]""",
                ),
                expectedId = 1L,
            )
            fail("混頁含保留前綴名必須整頁拒（不得略過單支）")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: tool name uses reserved prefix", e.message)
        }
        // ③ 大小寫變體 `MCP.x` 照收（前綴檢查大小寫敏感、不做正規化——正向 pin）。
        val ok = McpWire.parseList(
            "s1",
            page("""[{"name":"MCP.x","inputSchema":{"type":"object"}}]"""),
            expectedId = 1L,
        )
        assertEquals(1, ok.size)
        assertEquals("MCP.x", ok[0].name)
        // ④ 前綴字樣夾在中間（`xmcp.y`）非保留前綴，照收。
        assertEquals(
            1,
            McpWire.parseList(
                "s1",
                page("""[{"name":"xmcp.y","inputSchema":{"type":"object"}}]"""),
                expectedId = 1L,
            ).size,
        )
    }

    @Test fun limitConstantsArePinnedToLiterals() {
        // 常數 literal pin（reviewer 追加）：KDoc 與外部文件引用的數字必須與
        // 實作一致——改常數卻沒同步文件時，此測試先紅。
        assertEquals(500, McpWire.DESCRIPTION_MAX_CHARS)
        assertEquals(128, McpWire.TOOL_NAME_MAX_CHARS)
    }

    @Test fun callBodyRejectsLoneSurrogateToolName() {
        // 出站孤立 surrogate（reviewer P3-6）：esc 不修 surrogate，UTF-8 編碼會變
        // U+FFFD——送達名與列表名不一致（工具識別錯位），發送前拒。
        for (bad in listOf("a\uD800", "a\uDC00", "\uD800\uD800", "x\uD800y")) {
            try {
                McpWire.callBody(bad, "{}")
                fail("孤立 surrogate 出站名必須拒：${bad.map { it.code }}")
            } catch (e: IllegalArgumentException) {
                assertEquals("mcp: tool name has lone surrogate", e.message)
            }
        }
        // 成對 surrogate（emoji）名仍放行且原樣出現在請求體。
        val okName = "t😀"
        assertTrue(McpWire.callBody(okName, "{}").contains("\"name\":\"t😀\""))
    }

    @Test fun loneSurrogateInCallResponseIsRejected() {
        // parseCall 路徑的孤立 surrogate（reviewer 追加）：回應 body 的字串值
        // 同樣在 parser 層拒（與 list 路徑對稱），不因走 call 分支而放行。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":" +
            "[{\"type\":\"text\",\"text\":\"x\\ud800\"}],\"isError\":false}}"
        try {
            McpWire.parseCall(json, expectedId = 2L)
            fail("call 回應含孤立 surrogate 必須拒")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad envelope", e.message)
        }
    }

    @Test fun surrogateBoundaryTruncationAppliesToDescription() {
        // P3 pin：description(500) 呼叫點確實走 truncateUnits（非 take），截斷點
        // 落在 surrogate pair 中間時退 1——改回 take 此處即紅
        //（errorMessage 呼叫點另有 errorMessageTruncationIsSurrogateSafe 釘住）。
        // name 不再截斷（超限即拒，見 oversizedInboundToolNameIsRejectedNotTruncated），
        // 限內含成對 emoji 者原樣保留（孤立 surrogate 由 loneSurrogateEscapeIsRejected 拒收）。
        val emoji = "😀" // 2 UTF-16 units
        // name：126 個 n + emoji = 128 units（恰上限，成對 surrogate 不受影響）。
        val name = "n".repeat(McpWire.TOOL_NAME_MAX_CHARS - 2) + emoji
        assertEquals(McpWire.TOOL_NAME_MAX_CHARS, name.length)
        // description：499 個 d + emoji = 501 units，500 截斷落在 high surrogate → 退 1。
        val desc = "d".repeat(499) + emoji
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
            "[{\"name\":\"$name\",\"description\":\"$desc\"," +
            "\"inputSchema\":{\"type\":\"object\"}}]}}"
        val tools = McpWire.parseList("s1", json, expectedId = 1L)
        assertEquals(1, tools.size)
        assertEquals("name 限內成對 emoji 必須原樣保留", name, tools[0].name)
        assertEquals("description 截斷不得留孤立 surrogate", 499, tools[0].description.length)
        assertFalse("description 尾不得是 high surrogate", tools[0].description.last().isHighSurrogate())
    }

    @Test fun descriptionTruncatedAtNamedLimit() {
        // DESCRIPTION_MAX_CHARS 命名常數 pin：500 恰過、501 → 截到 500（截斷非拒絕）。
        fun list(desc: String) = McpWire.parseList(
            "s1",
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
                "[{\"name\":\"t\",\"description\":\"$desc\"," +
                "\"inputSchema\":{\"type\":\"object\"}}]}}",
            expectedId = 1L,
        )[0].description
        assertEquals(McpWire.DESCRIPTION_MAX_CHARS, list("d".repeat(500)).length)
        assertEquals(McpWire.DESCRIPTION_MAX_CHARS, list("d".repeat(501)).length)
    }

    @Test fun loneSurrogateEscapeIsRejected() {
        // 孤立 surrogate escape（語法合法、語意無效）：出站 esc／UTF-8 編碼會把它
        // 變形為 U+FFFD，送達伺服器的名稱與列表不一致（工具識別錯位）——
        // parseString fail-closed 拒收（比 RFC 嚴格，已文件化）。
        // 缺形狀組合（reviewer 追加）：孤立 low 接在一般字元後、high 後接 high
        //（非 low）——兩種都必須在 parser 層拒，不得當字面量放行。
        for (bad in listOf(
            "\\ud800", "\\udc00", "x\\ud800", "\\ud800x",
            "x\\udc00", "\\ud800\\ud800", "\\udc00\\ud800",
        )) {
            val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
                "[{\"name\":\"$bad\",\"inputSchema\":{\"type\":\"object\"}}]}}"
            try {
                McpWire.parseList("s1", json, expectedId = 1L)
                fail("孤立 surrogate 必須拒：$bad")
            } catch (e: McpWire.McpProtocol) {
                assertEquals("mcp: bad envelope", e.message)
            }
        }
        // 成對 escape（😀）合法通過且解碼為同一 emoji。
        val paired = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
            "[{\"name\":\"\\ud83d\\ude00\",\"inputSchema\":{\"type\":\"object\"}}]}}"
        assertEquals("😀", McpWire.parseList("s1", paired, expectedId = 1L)[0].name)
    }

    @Test fun callBodyRejectsOversizedArguments() {
        // 超上限 arguments 拒絕（不發送）；訊息全文釘死含 arguments 上限，
        // 不得誤稱 response body。
        val big = "x".repeat(McpWire.ARGUMENTS_MAX_CHARS)
        val args = "{\"pad\":\"$big\"}"
        try {
            McpWire.callBody("t", args)
            fail("超量 arguments 必須拒（不發送）")
        } catch (e: IllegalArgumentException) {
            assertEquals(
                "mcp: arguments exceed ${McpWire.ARGUMENTS_MAX_CHARS} chars",
                e.message,
            )
        }
        // 邊界值仍過：args 恰為 ARGUMENTS_MAX_CHARS 字元（`>` 是嚴格大於）。
        // {"pad":""} 佔 10 字元。
        val exactArgs = "{\"pad\":\"" + "y".repeat(McpWire.ARGUMENTS_MAX_CHARS - 10) + "\"}"
        assertEquals(McpWire.ARGUMENTS_MAX_CHARS, exactArgs.length)
        val body = McpWire.callBody("t", exactArgs)
        assertTrue("恰上限 arguments 必須原樣入 body", body.contains(exactArgs))
    }

    @Test fun badIdTypeWithoutExpectedIdIsRejected() {
        // 無 expectedId 也只放行數值／字串 id：布林等型別 → mcp: bad id。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":true,\"result\":{\"tools\":[]}}"
        try {
            McpWire.parseList("s1", json)
            fail("布林 id 必須拒")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad id", e.message)
        }
    }

    @Test fun serverRejectsOversizedCallBeforeHttp() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            val breaker = McpCircuitBreaker()
            val vault = FakeVault()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = vault,
                transport = McpTransport(),
                breaker = breaker,
            )
            val longName = deadline {
                server.callTool("t".repeat(McpWire.TOOL_NAME_MAX_CHARS + 1), "{}")
            }
            assertEquals(McpStatus.PROTOCOL, longName.status)
            val bigArgs = deadline {
                server.callTool("t", "{\"pad\":\"" + "x".repeat(McpWire.ARGUMENTS_MAX_CHARS) + "\"}")
            }
            assertEquals(McpStatus.PROTOCOL, bigArgs.status)
            // 出站預檢 fail-closed：不發送、不碰 vault、不計熔斷。
            assertEquals("超量參數不得發送 HTTP", 0, web.requestCount)
            assertEquals("超量參數不得碰 vault", 0, vault.reads)
            assertEquals("超量參數不得計熔斷", 0, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    // ---- server 層分類（熔斷語義） ----

    @Test fun protocolErrorCodeCountsBreakerAndMapsProtocol() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // 連續協議碼 error 包（舊 bug：全當中性 → breaker 永遠不能得知不互通）。
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32601,\"message\":\"no\"}}",
                ),
            )
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32603,\"message\":\"int\"}}",
                ),
            )
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32601,\"message\":\"no\"}}",
                ),
            )
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            assertEquals(
                McpStatus.PROTOCOL,
                deadline { server.callTool("t", "{}") }.status,
            )
            assertEquals(1, breaker.failureCount())
            assertEquals(
                McpStatus.PROTOCOL,
                deadline { server.callTool("t", "{}") }.status,
            )
            assertEquals(2, breaker.failureCount())
            assertEquals(
                McpStatus.PROTOCOL,
                deadline { server.callTool("t", "{}") }.status,
            )
            assertEquals("協議碼必須累計", 3, breaker.failureCount())
            assertTrue("協議碼必須累計並開路", breaker.isOpen())
            assertEquals(
                McpStatus.CIRCUIT_OPEN,
                deadline { server.callTool("t", "{}") }.status,
            )
        } finally {
            web.shutdown()
        }
    }

    @Test fun serverErrorRangeThroughServerOpensCircuit() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // 連續 server 範圍碼（−32099/−32001/−32000，伺服器端失敗）：必須計熔斷
            // 並開路——修前被當中性業務錯（onNeutral），熔斷永不開路（外部 review「前輪 P2-1」）。
            for (code in listOf(-32099, -32001, -32000)) {
                web.enqueue(
                    MockResponse().setResponseCode(200).setBody(
                        "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":$code,\"message\":\"srv\"}}",
                    ),
                )
            }
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            for (expected in 1..3) {
                assertEquals(
                    McpStatus.PROTOCOL,
                    deadline { server.callTool("t", "{}") }.status,
                )
                assertEquals("server 範圍碼必須累計", expected, breaker.failureCount())
            }
            assertTrue("三次 server 範圍碼必須開路", breaker.isOpen())
            assertEquals(
                McpStatus.CIRCUIT_OPEN,
                deadline { server.callTool("t", "{}") }.status,
            )
            assertEquals("開路後不得再發 HTTP", 3, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test fun invalidParamsStaysNeutralThroughServer() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // -32602 仍工具級中性：連發三次不得累計、不得開路
            //（與 server 範圍碼的 serverErrorRangeThroughServerOpensCircuit 對照）。
            repeat(3) {
                web.enqueue(
                    MockResponse().setResponseCode(200).setBody(
                        "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32602,\"message\":\"bad\"}}",
                    ),
                )
            }
            web.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            repeat(3) {
                assertEquals(
                    McpStatus.TOOL_ERROR,
                    deadline { server.callTool("t", "{}") }.status,
                )
                assertEquals("-32602 必須中性（不得累計）", 0, breaker.failureCount())
            }
            assertFalse("中性不得開路", breaker.isOpen())
            // 其後真實失敗仍正常計數（中性未污染記帳）。
            assertEquals(
                McpStatus.TRANSPORT,
                deadline { server.callTool("t", "{}") }.status,
            )
            assertEquals(1, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun idMismatchThroughServerIsProtocolAndCountsBreaker() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // server 回錯 id（回了別的請求的內容）：不得採納、不得重置熔斷。
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":999,\"result\":{\"tools\":" +
                        "[{\"name\":\"evil\",\"inputSchema\":{\"type\":\"object\"}}]}}",
                ),
            )
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[],\"isError\":false}}",
                ),
            )
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString()),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
            )
            val list = deadline { server.listTools() }
            assertEquals(McpStatus.PROTOCOL, list.status)
            assertTrue("錯 id 回應不得採納", list.tools.isEmpty())
            assertEquals(1, breaker.failureCount())
            // call 同語義（id 1 ≠ CALL_ID 2）。
            val call = deadline { server.callTool("t", "{}") }
            assertEquals(McpStatus.PROTOCOL, call.status)
            assertEquals(2, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun errorPacketForListIsProtocolNotOk() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // tools/list 收到 error 包（如 -32601）：PROTOCOL + 熔斷，不當空列表。
            web.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601,\"message\":\"no\"}}",
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
            assertTrue(got.tools.isEmpty())
            assertEquals(1, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }
}
