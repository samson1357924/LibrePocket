package dev.librepocket.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * D1 有界切片回歸（MCP #11）：
 * - parseCall 的 isError/content 限頂層 result 層級（巢狀同名鍵不得誤判 TOOL_ERROR；缺 result 即拋）；
 * - parseList 只認頂層 result 包裝（無包裝的 {"tools":[...]} 必須拒絕；有包裝仍過）；
 * - endpoint()：bare-origin 自動補 `/mcp`（舊配置相容），有 path 原樣保留；
 * - inputSchema 超限拒絕（不靜默截斷）。
 */
class McpWireD1Test {

    // ---- a. 巢狀 isError 不得誤判整包為 TOOL_ERROR ----

    @Test fun nestedIsErrorTrueInsideContentIsNotToolError() {
        // content 陣列元素內的巢狀 "isError":true 不得覆寫外層 result.isError=false。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
            "{\"content\":[{\"type\":\"text\",\"text\":\"ok\",\"isError\":true}],\"isError\":false}}"
        val (_, isError) = McpWire.parseCall(json)
        assertFalse("巢狀 content 內的 isError:true 不得誤判整包為 TOOL_ERROR", isError)
    }

    @Test fun missingOuterIsErrorWithNestedTrueDefaultsFalse() {
        // result 層無 isError 時，巢狀 true 不得被全域搜到當成頂層值。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
            "{\"content\":[{\"type\":\"text\",\"text\":\"ok\",\"isError\":true}]}}"
        val (result, isError) = McpWire.parseCall(json)
        assertFalse("缺外層 isError 時不得沿用巢狀值", isError)
        assertFalse("結果不得為空", result.isNullOrBlank())
    }

    @Test fun missingResultThrowsEvenWhenTopLevelContentPresent() {
        // 無頂層 result 即拋（fail-closed）：頂層游離 content 不得被當成結果回 OK。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2," +
            "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}"
        try {
            McpWire.parseCall(json)
            fail("缺 result 必須拋 IllegalArgumentException（映射 PROTOCOL），不得回 OK")
        } catch (e: McpWire.McpToolFailure) {
            fail("缺 result 不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (_: IllegalArgumentException) {
            // 預期：server 映射 PROTOCOL。
        }
    }

    // ---- b. endpoint()：bare-origin 補 /mcp，有 path 原樣保留 ----

    @Test fun endpointAppendsMcpForBareOriginAndPreservesCustomPath() {
        // bare-origin（無 path）自動補 /mcp（舊配置相容）。
        assertEquals("https://x.example/mcp", McpWire.endpoint("https://x.example"))
        assertEquals("https://x.example/mcp", McpWire.endpoint("https://x.example/"))
        // 有 path 則原樣保留（僅去尾斜線）。
        assertEquals(
            "https://x.example/custom/path",
            McpWire.endpoint("https://x.example/custom/path/"),
        )
        assertEquals(
            "https://x.example/custom/path",
            McpWire.endpoint("https://x.example/custom/path"),
        )
    }

    @Test fun endpointDoesNotDuplicateMcpSuffix() {
        // 已有 /mcp 後綴不重複加。
        assertEquals("https://x.example/mcp", McpWire.endpoint("https://x.example/mcp"))
        assertEquals("https://x.example/mcp", McpWire.endpoint("https://x.example/mcp/"))
    }

    // ---- c. inputSchema 超限拒絕（拋 McpProtocol，不靜默截斷） ----

    @Test fun oversizedInputSchemaRejectedInsteadOfTruncated() {
        val big = "x".repeat(9_000)
        val schema = "{\"type\":\"object\",\"extra\":\"$big\"}"
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
            "[{\"name\":\"search\",\"description\":\"d\",\"inputSchema\":$schema}]}}"
        try {
            McpWire.parseList("s1", json)
            fail("inputSchema 超 8192 字元必須拒絕（拋 McpProtocol 映射 PROTOCOL），不得靜默 take 截斷")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: inputSchema exceeds 8192 chars", e.message)
        } catch (e: McpWire.McpToolFailure) {
            fail("schema 超限不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (_: IllegalArgumentException) {
            fail("schema 超限必須拋 McpWire.McpProtocol（非一般 IllegalArgumentException）")
        }
    }

    @Test fun schemaAtLimitStillAccepted() {
        // 邊界：恰好 8192 字元仍接受（只擋超限，不擋合法）。
        val prefix = "{\"type\":\"object\",\"extra\":\""
        val suffix = "\"}"
        val pad = 8_192 - prefix.length - suffix.length
        val schema = prefix + "y".repeat(pad) + suffix
        assertEquals(8_192, schema.length)
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
            "[{\"name\":\"search\",\"description\":\"d\",\"inputSchema\":$schema}]}}"
        val tools = McpWire.parseList("s1", json)
        assertEquals(1, tools.size)
        assertEquals(schema, tools[0].inputSchema)
    }

    // ---- d. parseList 只認頂層 result 包裝 ----

    @Test fun parseListAcceptsResultWrappedTools() {
        // 正向：有 result 包裝仍過。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
            "[{\"name\":\"search\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}}]}}"
        val tools = McpWire.parseList("s1", json)
        assertEquals(1, tools.size)
        assertEquals("search", tools[0].name)
    }

    @Test fun parseListRejectsBareToolsWithoutResult() {
        // 無 result 包裝的 {"tools":[...]} 必須拒絕（fail-closed，與 parseCall 一致）。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"tools\":" +
            "[{\"name\":\"search\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}}]}"
        try {
            McpWire.parseList("s1", json)
            fail("無頂層 result 包裝必須拋 IllegalArgumentException（映射 PROTOCOL），不得回工具列表")
        } catch (e: McpWire.McpProtocol) {
            fail("無 result 包裝不得映射 schema 超限，實際=${e.message}")
        } catch (e: IllegalArgumentException) {
            assertEquals("mcp: missing result", e.message)
        }
    }

    @Test fun parseListBareToolsMessageIsExactlyMissingResult() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"tools\":" +
            "[{\"name\":\"search\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}}]}"
        try {
            McpWire.parseList("s1", json)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("mcp: missing result", e.message)
        }
    }

    @Test fun parseListStringResultMessageIsExactlyBadResultTools() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"oops\"}"
        try {
            McpWire.parseList("s1", json)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("mcp: bad result.tools", e.message)
        }
    }

    // ---- e. Stage 1：parseCall fail-closed（result 非 object 不得回 OK） ----

    @Test fun parseCallTruePositiveContentAndIsError() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
            "{\"content\":[{\"type\":\"text\",\"text\":\"nope\"}],\"isError\":true}}"
        val (result, isError) = McpWire.parseCall(json)
        assertTrue(isError)
        assertTrue("content 必須保留", result != null && result.contains("nope"))
    }

    @Test fun parseCallNullResultThrows() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":null}"
        try {
            McpWire.parseCall(json)
            fail("result:null 必須拋 IllegalArgumentException（映射 PROTOCOL），不得回 OK")
        } catch (e: McpWire.McpToolFailure) {
            fail("result:null 不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (_: IllegalArgumentException) {
            // 預期：server 映射 PROTOCOL。
        }
    }

    @Test fun parseCallNonObjectResultsThrowProtocol() {
        val cases = mapOf(
            "string" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":\"plain\"}",
            "number" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":123}",
            "array" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":[1]}",
            "boolTrue" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":true}",
            "boolFalse" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":false}",
            "badLiteralNaN" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":NaN}",
            "badLiteralTrailingChars" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":123abc}",
            "exponentNumber" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":1e10}",
            "badLiteralTRUE" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":TRUE}",
            "badLiteralUndefined" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":undefined}",
        )
        for ((name, json) in cases) {
            try {
                McpWire.parseCall(json)
                fail("result 非 object（$name）必須拋 IllegalArgumentException（映射 PROTOCOL），不得回 OK")
            } catch (e: McpWire.McpToolFailure) {
                fail("result 非 object（$name）不得映射 TOOL_ERROR，實際=${e.message}")
            } catch (e: McpWire.McpProtocol) {
                fail("result 非 object（$name）須為 mcp: bad result（一般 IAE），不得為 McpProtocol，實際=${e.message}")
            } catch (e: IllegalArgumentException) {
                assertEquals("mcp: bad result", e.message)
            }
        }
    }

    @Test fun parseCallMalformedIsErrorThrows() {
        val cases = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[],\"isError\":True}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[],\"isError\":1}}",
        )
        for (json in cases) {
            try {
                McpWire.parseCall(json)
                fail("畸形 isError 必須拋 McpProtocol（映射 PROTOCOL），不得靜默壓 false：$json")
            } catch (e: McpWire.McpToolFailure) {
                fail("畸形 isError 不得映射 TOOL_ERROR，實際=${e.message}")
            } catch (e: McpWire.McpProtocol) {
                assertEquals("mcp: bad isError", e.message)
            } catch (_: IllegalArgumentException) {
                fail("畸形 isError 必須拋 McpWire.McpProtocol（非一般 IllegalArgumentException）：$json")
            }
        }
    }

    @Test fun parseCallStringIsErrorStaysFalse() {
        // 型別嚴格：字串 "true" 不是布林 true，保持 false（鎖定現行語義）。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
            "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":\"true\"}}"
        val (_, isError) = McpWire.parseCall(json)
        assertFalse("字串 isError 不得當布林 true", isError)
    }

    @Test fun parseCallFalseErrorShapeIsProtocolNotToolError() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":false," +
            "\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}}"
        try {
            McpWire.parseCall(json)
            fail("error:false 非 object 形狀必須拋 PROTOCOL，不得回 OK 或 TOOL_ERROR")
        } catch (e: McpWire.McpToolFailure) {
            fail("error:false 不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad error", e.message)
        } catch (_: IllegalArgumentException) {
            fail("error:false 必須拋 McpWire.McpProtocol（非一般 IllegalArgumentException）")
        }
    }

    // ---- f. Stage 1：wire 層總大小 guard（與傳輸層同值；server 映射見 McpFuseTest） ----

    @Test fun parseCallOversizeThrowsProtocol() {
        val pad = "x".repeat(McpTransport.RESPONSE_MAX_CHARS + 1 - 50)
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[],\"note\":\"$pad\"}}"
        assertTrue(json.length > McpTransport.RESPONSE_MAX_CHARS)
        try {
            McpWire.parseCall(json)
            fail("超 RESPONSE_MAX_CHARS 必須拋 McpProtocol（映射 PROTOCOL），不得解析")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: response body exceeds ${McpTransport.RESPONSE_MAX_CHARS} chars", e.message)
        } catch (_: IllegalArgumentException) {
            fail("超長輸入必須拋 McpWire.McpProtocol（非一般 IllegalArgumentException）")
        }
    }

    @Test fun parseListOversizeThrowsProtocol() {
        val pad = "x".repeat(McpTransport.RESPONSE_MAX_CHARS + 1 - 50)
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[],\"note\":\"$pad\"}}"
        assertTrue(json.length > McpTransport.RESPONSE_MAX_CHARS)
        try {
            McpWire.parseList("s1", json)
            fail("超 RESPONSE_MAX_CHARS 必須拋 McpProtocol（映射 PROTOCOL），不得解析")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: response body exceeds ${McpTransport.RESPONSE_MAX_CHARS} chars", e.message)
        } catch (_: IllegalArgumentException) {
            fail("超長輸入必須拋 McpWire.McpProtocol（非一般 IllegalArgumentException）")
        }
    }

    // ---- g. Stage 1 Majors：endpoint query/fragment、bracket mismatch、error 多形狀 ----

    @Test fun endpointInsertsMcpBeforeQueryAndFragment() {
        assertEquals("https://h/mcp?x=1", McpWire.endpoint("https://h?x=1"))
        assertEquals("https://h/mcp#frag", McpWire.endpoint("https://h#frag"))
        assertEquals("https://h/mcp?x=1", McpWire.endpoint("https://h/?x=1"))
        assertEquals("https://h/path?x=1", McpWire.endpoint("https://h/path?x=1"))
        assertEquals("https://h/path?x=1", McpWire.endpoint("https://h/path/?x=1"))
    }

    @Test fun bracketMismatchRejectedAndDeepNestingAccepted() {
        // `[1,2}`：array 用 `}` 關 → parseCall 拒絕（bad result）。
        try {
            McpWire.parseCall("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":[1,2}")
            fail("bracket mismatch [1,2} 必須拒絕")
        } catch (e: McpWire.McpToolFailure) {
            fail("mismatch 不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (e: IllegalArgumentException) {
            assertEquals("mcp: bad result", e.message)
        }
        // `{"a":[1}`：object 內 array 未關 → parseCall 拒絕。
        try {
            McpWire.parseCall("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"a\":[1}}")
            fail("bracket mismatch {\"a\":[1} 必須拒絕")
        } catch (e: McpWire.McpToolFailure) {
            fail("mismatch 不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (e: IllegalArgumentException) {
            assertEquals("mcp: bad result", e.message)
        }
        // parseList 同樣拒絕 mismatch（result 非法 → bad result.tools）。
        try {
            McpWire.parseList("s1", "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[1,2}")
            fail("parseList bracket mismatch 必須拒絕")
        } catch (e: IllegalArgumentException) {
            assertEquals("mcp: bad result.tools", e.message)
        }
        try {
            McpWire.parseList("s1", "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"a\":[1}}")
            fail("parseList bracket mismatch 必須拒絕")
        } catch (e: IllegalArgumentException) {
            assertEquals("mcp: bad result.tools", e.message)
        }
        // 合法深巢狀仍接受（parseCall 回 OK，不誤判 mismatch）。
        val ok = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"a\":[1,{\"b\":2}]}}"
        val (result, isError) = McpWire.parseCall(ok)
        assertFalse(isError)
        assertTrue(result != null && result.contains("\"b\""))
    }

    @Test fun errorPolymorphicShapesAreProtocol() {
        val cases = mapOf(
            "string" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":\"oops\"," +
                "\"result\":{\"content\":[],\"isError\":false}}",
            "number" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":123," +
                "\"result\":{\"content\":[],\"isError\":false}}",
            "array" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":[1]," +
                "\"result\":{\"content\":[],\"isError\":false}}",
            "badLiteralTRUE" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":TRUE," +
                "\"result\":{\"content\":[],\"isError\":false}}",
        )
        for ((name, json) in cases) {
            try {
                McpWire.parseCall(json)
                fail("error 非 object（$name）必須拋 McpProtocol，不得回 OK 或 TOOL_ERROR")
            } catch (e: McpWire.McpToolFailure) {
                fail("error 非 object（$name）不得映射 TOOL_ERROR，實際=${e.message}")
            } catch (e: McpWire.McpProtocol) {
                assertEquals("mcp: bad error", e.message)
            } catch (e: IllegalArgumentException) {
                fail("error 非 object（$name）必須拋 McpProtocol，實際=${e.message}")
            }
        }
    }

    @Test fun parseListTrueResultIsBadNotMissing() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":TRUE}"
        try {
            McpWire.parseList("s1", json)
            fail("result:TRUE 非法字面量必須拒絕")
        } catch (e: IllegalArgumentException) {
            assertEquals("mcp: bad result.tools", e.message)
        }
    }
}
