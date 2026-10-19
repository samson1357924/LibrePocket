package dev.librepocket.mcp

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * D03 parseCall 誤判回歸：只有頂層 `error` 鍵才算 error 包；
 * result.content 內出現 `"error"`（文字值或巢狀鍵）仍回 OK，不丟資料。
 */
class McpWireTest {

    @Test fun contentTextExactlyErrorIsOk() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
            "{\"content\":[{\"type\":\"text\",\"text\":\"error\"}],\"isError\":false}}"
        val (result, isError) = McpWire.parseCall(json, null)
        assertFalse(isError)
        assertTrue(result != null && result.contains("error"))
    }

    @Test fun nestedErrorKeyInsideResultIsOk() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
            "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]," +
            "\"structuredContent\":{\"error\":\"nested detail\"},\"isError\":false}}"
        val (result, isError) = McpWire.parseCall(json, null)
        assertFalse(isError)
        assertTrue(result != null && result.contains("ok"))
    }

    @Test fun contentItemWithErrorKeyIsOk() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
            "{\"content\":[{\"type\":\"text\",\"text\":\"hi\",\"error\":\"detail\"}],\"isError\":false}}"
        val (_, isError) = McpWire.parseCall(json, null)
        assertFalse(isError)
    }

    @Test fun topLevelErrorStillThrows() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2," +
            "\"error\":{\"code\":-32602,\"message\":\"bad input\"}}"
        try {
            McpWire.parseCall(json, null)
            fail("expected McpToolFailure")
        } catch (e: McpWire.McpToolFailure) {
            assertEquals("bad input", e.message)
        }
    }

    @Test fun resultAndErrorBothPresentRejected() {
        // result XOR error（Stage2，外部 review #2）：矛盾包不得擇一採納。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2," +
            // 錯誤碼用範圍外中性碼（1）：互斥檢查先於 code 分類，此測試只pin XOR，
            // 不得依賴 server 範圍碼的分類結果（那由 serverErrorRangeCodesAreProtocol 釘）。
            "\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}," +
            "\"error\":{\"code\":1,\"message\":\"boom\"}}"
        try {
            McpWire.parseCall(json, null)
            fail("result+error 同時存在必須拒（McpProtocol）")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: result and error both present", e.message)
        }
    }

    @Test fun nullTopLevelErrorIsRejected() {
        // error 鍵存在即必須是 object（null 亦非法）：error:null + result 與
        // error:null 單獨皆不得回 OK（bad error 先於互斥檢查）。
        val both = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
            "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false},\"error\":null}"
        try {
            McpWire.parseCall(both, null)
            fail("error:null + result 必須拒")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad error", e.message)
        }
        val onlyNull = "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":null}"
        try {
            McpWire.parseCall(onlyNull, null)
            fail("error:null 單獨必須拒")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad error", e.message)
        }
    }

    @Test fun missingContentAndResultThrowsProtocol() {
        // 截斷半 JSON（content/result 雙缺）：必須拋 IllegalArgumentException（server 映射 PROTOCOL），
        // 不回 (null, false) 假成功。
        val truncated = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":"
        try {
            McpWire.parseCall(truncated, null)
            fail("expected IllegalArgumentException for missing result.content")
        } catch (e: McpWire.McpToolFailure) {
            fail("truncated JSON must not be McpToolFailure (TOOL_ERROR), got: ${e.message}")
        } catch (_: IllegalArgumentException) {
            // 預期：server 映射 PROTOCOL + 計熔斷。
        }
    }

    @Test fun largeContentPreservedWithoutSilentTake() {
        // 傳輸層 64k 字元內的完整結果不得靜默 take(32768)：40k CJK 必須逐字保留。
        val big = "繁".repeat(40_000)
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
            "{\"content\":[{\"type\":\"text\",\"text\":\"$big\"}],\"isError\":false}}"
        val (result, isError) = McpWire.parseCall(json, null)
        assertFalse(isError)
        assertTrue("40k CJK 結果必須完整保留（無 32k 靜默截斷）", result != null && result.contains(big))
    }

    @Test fun escEscapesAllC0ControlsForRfc8259Json() {
        // esc 必須逃逸所有 <0x20 控制字元（parity with FastRouter.esc），
        // 否則 hostile frame 的 名稱/參數 會以裸控制字元進入 JSON 輸出。
        assertEquals("\\u0000", McpWire.esc("\u0000"))
        assertEquals("\\u001f", McpWire.esc("\u001f"))
        assertEquals("\\n\\t\\r", McpWire.esc("\n\t\r"))
        // RFC 8259 short escapes for backspace/form feed (parity with
        // FastRouter.esc) instead of the generic \uXXXX C0 branch.
        assertEquals("\\b", McpWire.esc("\b"))
        assertEquals("\\f", McpWire.esc("\u000C"))
        assertEquals("\\b\\f", McpWire.esc("\b\u000C"))
        assertEquals("\\\"x\\\\", McpWire.esc("\"x\\"))
        assertEquals("A \\u0001B \\u0002", McpWire.esc("A \u0001B \u0002"))
        // DEL(0x7F) 以上邊界：RFC 8259 只要求 <0x20，DEL 保持原料不被逃逸。
        assertEquals("\u007f", McpWire.esc("\u007f"))
        // 0x20 以上的字元不被逃逸。
        assertEquals("穩定", McpWire.esc("穩定"))
    }

    @Test fun escHandlesLoneSurrogateDeterministically() {
        // 孤立 surrogate（無伴的 high/low）：OkHttp/okio 對孤立 surrogate 的 UTF-8
        // 編碼會靜默置換成 `?` (0x3F)（不是 U+FFFD），esc 必須以確定性 \\uXXXX 逃逸（與 C0 分支同
        // 的小寫 4-hex 約定）；完整有效 pair 原樣通過（兩個原始單元可正常編碼）。
        val lone = McpWire.esc("\uD800")
        assertEquals("\\ud800", lone) // 字面 backslash-u-d-8-0-0
        assertEquals(lone, McpWire.esc("\uD800")) // 確定性：同輸入同輸出
        assertEquals("\\udc00", McpWire.esc("\uDC00")) // 孤立 low 逃逸
        assertEquals("\\\"\\ud800", McpWire.esc("\"") + McpWire.esc("\uD800"))
        // 完整有效 pair：原樣通過，parse + esc 往返自同（不重組 code point）。
        val pair = "\uD83D\uDE00"
        assertEquals(pair, McpWire.esc(pair))
        val doc = "{\"a\":\"${McpWire.esc(pair)}\"}"
        val obj = McpWire.parseStrictDoc(doc) as McpWire.JObj
        val parsed = obj.find("a") as McpWire.JStr
        assertEquals(pair, parsed.v)
        assertEquals(pair, McpWire.esc(parsed.v))
    }

    @Test fun parseRejectsSignedHexUnicodeEscape() {
        // `\uXXXX` 需要恰好 4 個嚴格 hex 字元：`hex.toIntOrNull(16)` 也接受
        // 帶號字首（`\\u-123` 會靜默解成 U+FEDD、`\\u+0bb` 解成 U+00BB），
        // RFC 8259 只認 hex——一律與其他惡形分支同拋 bad envelope。
        for (bad in listOf("{\"a\":\"\\u-123\"}", "{\"a\":\"\\u+0bb\"}")) {
            try {
                McpWire.parseStrictDoc(bad)
                fail("expected McpProtocol for $bad")
            } catch (e: McpWire.McpProtocol) {
                assertEquals("mcp: bad envelope", e.message)
            }
        }
    }

    @Test fun callBodyRejectsArgumentJsonThatIsNotAnObject() {
        // KDoc 約定 `argumentsJson` 已是合法 JSON object 文本；非物件/惡形
        // 一律建構期 fail-closed（與惡形包同型的 McpProtocol），不再把壞參數
        // 原樣插進信封。McpServer.callTool 在 vault／HTTP 之前建構 body 並把
        // 此例外映射為 PROTOCOL（零發送、零熔斷）；見 McpFuseTest 的端到端 pin。
        for (bad in listOf("\",\"extra\":true", "[1,2]", "not json")) {
            try {
                McpWire.callBody("t", bad)
                fail("expected McpProtocol for $bad")
            } catch (e: McpWire.McpProtocol) {
                assertEquals("mcp: bad envelope", e.message)
            }
        }
    }

    @Test fun callBodyKeepsValidObjectArgsUnchanged() {
        assertEquals(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\"," +
                "\"params\":{\"name\":\"t\",\"arguments\":{\"q\":\"x\"}}}",
            McpWire.callBody("t", "{\"q\":\"x\"}"),
        )
        // 空白/空串照舊正規化為 `{}`。
        for (blank in listOf("", " ", " \t\n", "\u000B", "\u00A0")) {
            assertEquals(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\"," +
                    "\"params\":{\"name\":\"t\",\"arguments\":{}}}",
                McpWire.callBody("t", blank),
            )
        }
    }

    // ---- surrogate-safe caps on the short parsed fields (name/description/
    // error message): a plain `take` at the cap can cut BETWEEN the two halves
    // of a well-formed UTF-16 surrogate pair and emit a LONE surrogate into a
    // field that later rides JSON/UTF-8 (OkHttp silently swaps a lone
    // surrogate for `?` (0x3F) at encode (not U+FFFD), so the wire value
    // would differ from the parsed one). The cap must step back over the
    // split pair instead. ----

    /** Assert the string carries no LONE surrogate (each high surrogate is
     *  immediately followed by its low partner; no stray low surrogate). */
    private fun assertNoLoneSurrogate(s: String) {
        var i = 0
        while (i < s.length) {
            val u = s[i].code
            if (u in 0xD800..0xDBFF) {
                assertTrue(
                    "lone high surrogate at $i of: $s",
                    i + 1 < s.length && s[i + 1].code in 0xDC00..0xDFFF,
                )
                i += 2
            } else {
                assertFalse("lone low surrogate at $i of: $s", u in 0xDC00..0xDFFF)
                i++
            }
        }
    }

    @Test fun parseListNameShapeIsFailClosedAndSurrogateSafe() {
        // #93 contract（較早期「128 截斷」政策更嚴）：超限名**整頁拒**（不得靜默
        // 截斷——截斷後的名是另一支工具，而 enabledTools／McpScope 以名為鍵）。
        // surrogate 安全性仍成立：長度剛好落在上限內的名保留其完整 surrogate pair。
        val emoji = "\uD83D\uDE00"
        val rawName = "n".repeat(126) + emoji   // 128 units exactly
        assertEquals(128, rawName.length)
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[" +
            "{\"name\":\"$rawName\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}}]}}"
        val tool = McpWire.parseList("s1", json, 1L).single()
        assertNoLoneSurrogate(tool.name)
        assertEquals(128, tool.name.length)
        // 超過上限一個 unit：整頁拒（不截斷、不採納）。
        val tooLong = "n".repeat(127) + emoji   // 129 units
        val jsonLong = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[" +
            "{\"name\":\"$tooLong\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}}]}}"
        try {
            McpWire.parseList("s1", jsonLong, 1L)
            fail("超限名必須整頁拒（不得靜默截斷成另一支工具）")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: tool name exceeds 128 chars", e.message)
        }
    }

    @Test fun parseListDescriptionCapDoesNotSplitSurrogatePair() {
        // 499 ASCII + one emoji (501 units): the cap cut at 500 lands on the
        // pair's high half → the capped description is 499 units.
        val emoji = "\uD83D\uDE00"
        val rawDesc = "d".repeat(499) + emoji
        assertEquals(501, rawDesc.length)
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[" +
            "{\"name\":\"search\",\"description\":\"$rawDesc\",\"inputSchema\":{\"type\":\"object\"}}]}}"
        val tool = McpWire.parseList("s1", json, 1L).single()
        assertNoLoneSurrogate(tool.description)
        assertEquals(499, tool.description.length)
        assertTrue(tool.description.length <= 500)
    }

    @Test fun parseCallErrorMessageCapDoesNotSplitSurrogatePair() {
        // 199 ASCII + one emoji (201 units): the cap cut at 200 lands on the
        // pair's high half → the error message is 199 units, no lone
        // surrogate (the message is the one server-controlled string that
        // reaches the tool-error path).
        val emoji = "\uD83D\uDE00"
        val rawMsg = "e".repeat(199) + emoji
        assertEquals(201, rawMsg.length)
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2," +
            "\"error\":{\"code\":-32602,\"message\":\"$rawMsg\"}}"
        try {
            McpWire.parseCall(json, 2L)
            fail("expected McpToolFailure")
        } catch (e: McpWire.McpToolFailure) {
            val msg = e.message!!
            assertNoLoneSurrogate(msg)
            assertEquals(199, msg.length)
            assertTrue(msg.length <= 200)
        }
    }

    @Test fun isValidBaseUrlAcceptsOnlyHttpEndpointShapes() {
        // The pre-flight shape check (McpServer): a blank or malformed
        // address is a configuration problem, never a transport failure.
        //
        // DIFFERENTIAL DIRECTION: `isValidBaseUrl` must NEVER pass an
        // address whose `endpoint()` form OkHttp rejects (`endpoint(...)`
        // `.toHttpUrlOrNull() == null`) — a passed address reaches the
        // transport layer, where a rejection would surface as TRANSPORT
        // plus a breaker failure (exactly what the pre-flight exists to
        // prevent).
        val accepted = listOf(
            "https://mcp.example.com",
            "https://mcp.example.com/",
            "https://mcp.example.com/mcp",
            "http://localhost:8080/mcp",
            "https://mcp.example.com/mcp?x=1",
            "https://mcp.example.com/mcp#frag",
            "  https://mcp.example.com/mcp  ",
            // Scheme case is irrelevant (the scheme is compared lowercased,
            // and OkHttp canonicalizes it too).
            "HTTPS://mcp.example.com/mcp",
            // IPv6 literal host (bracketed) with an explicit port.
            "http://[::1]:8080/mcp",
            // Userinfo is legal in an authority (a proxy/token style URL).
            "https://user:pass@mcp.example.com/mcp",
            // Underscore in the host label (used by some internal DNS names).
            "https://mcp_example.com/mcp",
            // IDN host: OkHttp canonicalizes it to punycode.
            "https://例え.テスト/mcp",
        )
        for (ok in accepted) {
            assertTrue(ok, McpWire.isValidBaseUrl(ok))
            // Differential direction (see above): an accepted address must
            // survive the production endpoint form.
            assertTrue(
                "endpoint form of $ok",
                McpWire.endpoint(ok).toHttpUrlOrNull() != null,
            )
        }
        for (bad in listOf(
            "",
            "   ",
            "not-a-url",
            "ftp://host",
            "http://",
            "https://",
            "//host/mcp",
            "mcp.example.com",
            "https://ho st/mcp",
            // Single-slash authority: OkHttp's parser reads `https:/host` as
            // host `host`, so the shape check must reject it explicitly.
            "https:/host",
            "https:/host/mcp",
            // Empty authority with a path: no host at all.
            "https:///mcp",
            // Port out of range (OkHttp rejects the whole URL).
            "https://mcp.example.com:99999/mcp",
            // Whitespace inside the USERINFO: OkHttp would percent-encode it
            // and parse the URL anyway; the pre-flight is deliberately
            // stricter (fail-closed) — a rewritten credential must never
            // reach the wire.
            "https://user:pa ss@host/mcp",
        )) {
            assertFalse(bad, McpWire.isValidBaseUrl(bad))
        }
        // The regression that motivated the raw-address check: `endpoint()`
        // rewrites the host-less `http://` into `http:/mcp`, which OkHttp
        // happily reads as host `mcp` — the shape check must NOT be applied
        // to that rewritten form.
        assertFalse("http://", McpWire.isValidBaseUrl("http://"))
        assertEquals("http:/mcp", McpWire.endpoint("http://"))
    }
}
