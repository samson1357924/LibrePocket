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
        // 有 path 則原樣保留（尾斜線亦保留：/custom/path/ 與 /custom/path 不等價）。
        assertEquals(
            "https://x.example/custom/path/",
            McpWire.endpoint("https://x.example/custom/path/"),
        )
        assertEquals(
            "https://x.example/custom/path",
            McpWire.endpoint("https://x.example/custom/path"),
        )
    }

    @Test fun endpointDoesNotDuplicateMcpSuffix() {
        // 已有 /mcp 後綴不重複加；尾斜線原樣保留（不去尾斜線）。
        assertEquals("https://x.example/mcp", McpWire.endpoint("https://x.example/mcp"))
        assertEquals("https://x.example/mcp/", McpWire.endpoint("https://x.example/mcp/"))
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

    @Test fun parseCallBadContentValueIsProtocol() {
        // content 鍵在但值非法：不得回退整包 result 充 OK，一律 PROTOCOL。
        // Stage D：非法字面量（garbage/True/undefined）由嚴格 parser 先擋為 bad envelope；
        // 合法 JSON 錯型（字串）仍為 bad content（訊息逐字保留）。
        val cases = mapOf(
            "garbage literal" to ("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":garbage,\"isError\":false}}" to "mcp: bad envelope"),
            "True literal" to ("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":True,\"isError\":false}}" to "mcp: bad envelope"),
            "undefined" to ("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":undefined,\"isError\":false}}" to "mcp: bad envelope"),
            "string content" to ("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":\"oops\",\"isError\":false}}" to "mcp: bad content"),
        )
        for ((name, pair) in cases) {
            val (json, expected) = pair
            try {
                McpWire.parseCall(json)
                fail("content 壞值（$name）必須拋 McpProtocol，不得回 OK")
            } catch (e: McpWire.McpToolFailure) {
                fail("content 壞值（$name）不得映射 TOOL_ERROR，實際=${e.message}")
            } catch (e: McpWire.McpProtocol) {
                assertEquals(expected, e.message)
            } catch (_: IllegalArgumentException) {
                fail("content 壞值（$name）必須拋 McpWire.McpProtocol（非一般 IAE）")
            }
        }
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
        // Stage D：合法 JSON 錯型（string/number/array/bool/合法 exponent）仍為 mcp: bad result；
        // 非法字面量（NaN/123abc/TRUE/undefined）由嚴格 parser 先擋為 mcp: bad envelope。
        val badResultCases = mapOf(
            "string" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":\"plain\"}",
            "number" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":123}",
            "array" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":[1]}",
            "boolTrue" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":true}",
            "boolFalse" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":false}",
            "exponentNumber" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":1e10}",
        )
        for ((name, json) in badResultCases) {
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
        val badEnvelopeCases = mapOf(
            "badLiteralNaN" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":NaN}",
            "badLiteralTrailingChars" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":123abc}",
            "badLiteralTRUE" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":TRUE}",
            "badLiteralUndefined" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":undefined}",
            // 數字嚴格性（NUMBER_RE）：前導零／正號／尾點不得被當合法數字。
            "badLiteralLeadingZero" to "{\"jsonrpc\":\"2.0\",\"id\":02,\"result\":{}}",
            "badLiteralPlusSign" to "{\"jsonrpc\":\"2.0\",\"id\":+2,\"result\":{}}",
            "badLiteralTrailingDot" to "{\"jsonrpc\":\"2.0\",\"id\":2.,\"result\":{}}",
        )
        for ((name, json) in badEnvelopeCases) {
            try {
                McpWire.parseCall(json)
                fail("result 非法字面量（$name）必須拋 McpProtocol，不得回 OK")
            } catch (e: McpWire.McpToolFailure) {
                fail("result 非法字面量（$name）不得映射 TOOL_ERROR，實際=${e.message}")
            } catch (e: McpWire.McpProtocol) {
                assertEquals("mcp: bad envelope", e.message)
            } catch (e: IllegalArgumentException) {
                fail("result 非法字面量（$name）必須拋 McpProtocol（mcp: bad envelope），實際=${e.message}")
            }
        }
    }

    @Test fun parseCallMalformedIsErrorThrows() {
        // Stage D：True 非法字面量由 parser 先擋為 bad envelope；數字 1 仍為 bad isError。
        val envelopeCases = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[],\"isError\":True}}",
        )
        for (json in envelopeCases) {
            try {
                McpWire.parseCall(json)
                fail("畸形 isError 必須拋 McpProtocol（映射 PROTOCOL），不得靜默壓 false：$json")
            } catch (e: McpWire.McpToolFailure) {
                fail("畸形 isError 不得映射 TOOL_ERROR，實際=${e.message}")
            } catch (e: McpWire.McpProtocol) {
                assertEquals("mcp: bad envelope", e.message)
            } catch (_: IllegalArgumentException) {
                fail("畸形 isError 必須拋 McpWire.McpProtocol（非一般 IllegalArgumentException）：$json")
            }
        }
        val isErrorCases = listOf(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[],\"isError\":1}}",
        )
        for (json in isErrorCases) {
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

    @Test fun parseCallStringIsErrorRejected() {
        // 型別嚴格（fail-closed，與 True/1 一致）：字串 "true" 不是布林 true，一律 PROTOCOL。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
            "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":\"true\"}}"
        try {
            McpWire.parseCall(json)
            fail("字串 isError 必須拋 McpProtocol（映射 PROTOCOL），不得靜默壓 false")
        } catch (e: McpWire.McpToolFailure) {
            fail("字串 isError 不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad isError", e.message)
        } catch (_: IllegalArgumentException) {
            fail("字串 isError 必須拋 McpWire.McpProtocol（非一般 IllegalArgumentException）")
        }
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
        assertEquals("https://h/path/?x=1", McpWire.endpoint("https://h/path/?x=1"))
    }

    @Test fun bracketMismatchRejectedAndDeepNestingAccepted() {
        // `[1,2}`：array 用 `}` 關 → 頂層形狀驗證拒絕（McpProtocol，Stage B P2-1 新語義；
        // 舊為一般 IAE "mcp: bad result"，server 層同映射 PROTOCOL，見 McpServer）。
        try {
            McpWire.parseCall("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":[1,2}")
            fail("bracket mismatch [1,2} 必須拒絕")
        } catch (e: McpWire.McpToolFailure) {
            fail("mismatch 不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad envelope", e.message)
        } catch (e: IllegalArgumentException) {
            fail("bracket mismatch 必須拋 McpWire.McpProtocol，實際=${e.message}")
        }
        // `{"a":[1}`：object 內 array 未關 → 同上 McpProtocol。
        try {
            McpWire.parseCall("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"a\":[1}}")
            fail("bracket mismatch {\"a\":[1} 必須拒絕")
        } catch (e: McpWire.McpToolFailure) {
            fail("mismatch 不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad envelope", e.message)
        } catch (e: IllegalArgumentException) {
            fail("bracket mismatch 必須拋 McpWire.McpProtocol，實際=${e.message}")
        }
        // parseList 同樣拒絕 mismatch（頂層形狀驗證 → McpProtocol）。
        try {
            McpWire.parseList("s1", "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[1,2}")
            fail("parseList bracket mismatch 必須拒絕")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad envelope", e.message)
        } catch (e: IllegalArgumentException) {
            fail("parseList mismatch 必須拋 McpWire.McpProtocol，實際=${e.message}")
        }
        try {
            McpWire.parseList("s1", "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"a\":[1}}")
            fail("parseList bracket mismatch 必須拒絕")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad envelope", e.message)
        } catch (e: IllegalArgumentException) {
            fail("parseList mismatch 必須拋 McpWire.McpProtocol，實際=${e.message}")
        }
        // 合法深巢狀仍接受（parseCall 回 OK，不誤判 mismatch）。
        val ok = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
            "{\"content\":[{\"type\":\"text\",\"text\":\"deep\"}]," +
            "\"structuredContent\":{\"a\":[1,{\"b\":2}]}}}"
        val (result, isError) = McpWire.parseCall(ok)
        assertFalse(isError)
        assertTrue(result != null && result.contains("\"b\""))
    }

    @Test fun errorPolymorphicShapesAreProtocol() {
        // Stage D：合法 JSON 錯型（string/number/array）仍為 bad error；
        // 非法字面量 TRUE 由 parser 先擋為 bad envelope。
        val badErrorCases = mapOf(
            "string" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":\"oops\"," +
                "\"result\":{\"content\":[],\"isError\":false}}",
            "number" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":123," +
                "\"result\":{\"content\":[],\"isError\":false}}",
            "array" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":[1]," +
                "\"result\":{\"content\":[],\"isError\":false}}",
        )
        for ((name, json) in badErrorCases) {
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
        val envelopeCases = mapOf(
            "badLiteralTRUE" to "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":TRUE," +
                "\"result\":{\"content\":[],\"isError\":false}}",
        )
        for ((name, json) in envelopeCases) {
            try {
                McpWire.parseCall(json)
                fail("error 非法字面量（$name）必須拋 McpProtocol，不得回 OK 或 TOOL_ERROR")
            } catch (e: McpWire.McpToolFailure) {
                fail("error 非法字面量（$name）不得映射 TOOL_ERROR，實際=${e.message}")
            } catch (e: McpWire.McpProtocol) {
                assertEquals("mcp: bad envelope", e.message)
            } catch (e: IllegalArgumentException) {
                fail("error 非法字面量（$name）必須拋 McpProtocol（mcp: bad envelope），實際=${e.message}")
            }
        }
    }

    @Test fun parseListTrueResultIsBadNotMissing() {
        // Stage D：TRUE 非法字面量由嚴格 parser 先擋為 bad envelope（舊為 bad result.tools）。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":TRUE}"
        try {
            McpWire.parseList("s1", json)
            fail("result:TRUE 非法字面量必須拒絕")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad envelope", e.message)
        } catch (e: IllegalArgumentException) {
            fail("result:TRUE 非法字面量 Stage D 應為 McpProtocol（mcp: bad envelope），實際=${e.message}")
        }
    }

    // ---- h. Stage B P2-1：截斷/尾隨/缺逗號/重複頂層鍵一律 McpProtocol ----

    @Test fun parseCallMissingOuterBraceIsProtocol() {
        // 缺外層 }：舊 topLevelRawField 只取局部值會回 OK，必須擋。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[]}"
        try {
            McpWire.parseCall(json)
            fail("缺外層 } 必須拋 McpProtocol（映射 PROTOCOL），不得回 OK")
        } catch (e: McpWire.McpToolFailure) {
            fail("缺外層 } 不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad envelope", e.message)
        } catch (_: IllegalArgumentException) {
            fail("缺外層 } 必須拋 McpWire.McpProtocol（非一般 IllegalArgumentException）")
        }
    }

    @Test fun parseListMissingOuterBraceIsProtocol() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}"
        try {
            McpWire.parseList("s1", json)
            fail("缺外層 } 必須拋 McpProtocol（映射 PROTOCOL），不得回工具列表")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad envelope", e.message)
        } catch (_: IllegalArgumentException) {
            fail("缺外層 } 必須拋 McpWire.McpProtocol（非一般 IllegalArgumentException）")
        }
    }

    @Test fun parseCallTrailingGarbageIsProtocol() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[]}} garbage"
        try {
            McpWire.parseCall(json)
            fail("尾隨 garbage 必須拋 McpProtocol，不得回 OK")
        } catch (e: McpWire.McpToolFailure) {
            fail("尾隨 garbage 不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad envelope", e.message)
        } catch (_: IllegalArgumentException) {
            fail("尾隨 garbage 必須拋 McpWire.McpProtocol（非一般 IllegalArgumentException）")
        }
    }

    @Test fun parseListTrailingGarbageIsProtocol() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}} trailing"
        try {
            McpWire.parseList("s1", json)
            fail("尾隨 garbage 必須拋 McpProtocol，不得回工具列表")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad envelope", e.message)
        } catch (_: IllegalArgumentException) {
            fail("尾隨 garbage 必須拋 McpWire.McpProtocol（非一般 IllegalArgumentException）")
        }
    }

    @Test fun parseCallMissingCommaIsProtocol() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2 \"result\":{\"content\":[]}}"
        try {
            McpWire.parseCall(json)
            fail("成員間缺逗號必須拋 McpProtocol，不得回 OK")
        } catch (e: McpWire.McpToolFailure) {
            fail("缺逗號不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad envelope", e.message)
        } catch (_: IllegalArgumentException) {
            fail("缺逗號必須拋 McpWire.McpProtocol（非一般 IllegalArgumentException）")
        }
    }

    @Test fun parseCallDuplicateResultKeyIsProtocol() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[]},\"result\":{\"content\":[]}}"
        try {
            McpWire.parseCall(json)
            fail("重複頂層 result 鍵必須拋 McpProtocol（嚴格拒絕，不做 first-wins），不得回 OK")
        } catch (e: McpWire.McpToolFailure) {
            fail("重複鍵不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: duplicate key", e.message)
        } catch (_: IllegalArgumentException) {
            fail("重複鍵必須拋 McpWire.McpProtocol（非一般 IllegalArgumentException）")
        }
    }

    @Test fun parseListDuplicateResultKeyIsProtocol() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]},\"result\":{\"tools\":[]}}"
        try {
            McpWire.parseList("s1", json)
            fail("重複頂層 result 鍵必須拋 McpProtocol，不得回工具列表")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: duplicate key", e.message)
        } catch (_: IllegalArgumentException) {
            fail("重複鍵必須拋 McpWire.McpProtocol（非一般 IllegalArgumentException）")
        }
    }

    @Test fun whitespaceWrappedValidDocumentStillOk() {
        // 合法含前導/尾隨空白與換行仍過（驗證器只擋形狀錯誤，不擋空白）。
        val call = "  \n\t{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[],\"isError\":false}}  \n"
        val (_, isError) = McpWire.parseCall(call)
        assertFalse(isError)
        val list = " \n {\"jsonrpc\":\"2.0\" , \"id\" : 1 , \"result\" : { \"tools\" : [] } } \n "
        assertTrue(McpWire.parseList("s1", list).isEmpty())
        // 四種合法空白（space/tab/LF/CR）在分隔位置皆接受。
        val allWs = "{\"jsonrpc\":\t\"2.0\",\r\n\"id\": 1,\n\"result\":\t{\r\"tools\": [] } }"
        assertTrue(McpWire.parseList("s1", allWs).isEmpty())
    }

    @Test fun illegalWhitespaceNbspInArrayIsProtocol() {
        // NBSP 非 RFC 8259 空白：舊 isWhitespace() 會接受並回空列表，必須擋。
        val json = "{\"result\":{\"content\":[" + "\u00A0" + "]}}"
        try {
            McpWire.parseCall(json)
            fail("NBSP 非法空白必須拋 McpProtocol，不得回 OK")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad envelope", e.message)
        } catch (e: IllegalArgumentException) {
            fail("必須拋 McpWire.McpProtocol，實際=${e.message}")
        }
    }

    @Test fun illegalWhitespaceFormsInEnvelopeAreProtocol() {
        // VT/FF/NBSP 在各分隔位置皆非法（每種走 parseCall 與 parseList）。
        val bads = mapOf(
            "vertical tab" to "\u000B",
            "form feed" to "\u000C",
            "nbsp" to "\u00A0",
        )
        for ((name, ws) in bads) {
            val call = "{\"result\":{" + ws + "\"content\":[]}}"
            try {
                McpWire.parseCall(call)
                fail("非法空白（$name）必須拋 McpProtocol，不得回 OK")
            } catch (e: McpWire.McpProtocol) {
                assertEquals("mcp: bad envelope", e.message)
            } catch (e: IllegalArgumentException) {
                fail("非法空白（$name）必須拋 McpWire.McpProtocol，實際=${e.message}")
            }
            val list = "{\"result\":{\"tools\":[" + ws + "]}}"
            try {
                McpWire.parseList("s1", list)
                fail("非法空白（$name）必須拋 McpProtocol，不得回工具列表")
            } catch (e: McpWire.McpProtocol) {
                assertEquals("mcp: bad envelope", e.message)
            } catch (e: IllegalArgumentException) {
                fail("非法空白（$name）必須拋 McpWire.McpProtocol，實際=${e.message}")
            }
        }
    }

    @Test fun illegalWhitespaceAfterLiteralIsProtocol() {
        // 字面量後的非法空白（literal delim 檢查亦須嚴格）。
        val json = "{\"result\":{\"content\":[],\"isError\":false\u00A0}}"
        try {
            McpWire.parseCall(json)
            fail("字面量後 NBSP 必須拋 McpProtocol，不得回 OK")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad envelope", e.message)
        } catch (e: IllegalArgumentException) {
            fail("必須拋 McpWire.McpProtocol，實際=${e.message}")
        }
    }

    // ---- i. Stage B P2-2：tools 非 object 元素與非 object inputSchema 一律 PROTOCOL ----

    @Test fun parseListNullItemIsProtocol() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[null]}}"
        try {
            McpWire.parseList("s1", json)
            fail("[null] 不得靜默忽略回空列表，必須拋 McpProtocol")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad tools item", e.message)
        } catch (e: IllegalArgumentException) {
            fail("[null] 必須拋 McpWire.McpProtocol，實際=${e.message}")
        }
    }

    @Test fun parseListScalarItemsAreProtocol() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[1,\"x\"]}}"
        try {
            McpWire.parseList("s1", json)
            fail("[1,\"x\"] 不得靜默忽略回空列表，必須拋 McpProtocol")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad tools item", e.message)
        } catch (e: IllegalArgumentException) {
            fail("[1,\"x\"] 必須拋 McpWire.McpProtocol，實際=${e.message}")
        }
    }

    @Test fun parseListMissingNameStillThrows() {
        // [{}] 缺 name：舊行為（mcp: tool without name）保留，仍為 PROTOCOL 級。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[{}]}}"
        try {
            McpWire.parseList("s1", json)
            fail("[{}] 缺 name 必須拒絕")
        } catch (e: IllegalArgumentException) {
            assertEquals("mcp: tool without name", e.message)
        }
    }

    @Test fun parseListStringInputSchemaIsProtocol() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
            "[{\"name\":\"a\",\"inputSchema\":\"str\"}]}}"
        try {
            McpWire.parseList("s1", json)
            fail("inputSchema 為字串必須拋 McpProtocol（mcp: bad inputSchema）")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad inputSchema", e.message)
        } catch (e: IllegalArgumentException) {
            fail("inputSchema 為字串必須拋 McpWire.McpProtocol，實際=${e.message}")
        }
    }

    @Test fun parseListNumericInputSchemaIsProtocol() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
            "[{\"name\":\"a\",\"inputSchema\":123}]}}"
        try {
            McpWire.parseList("s1", json)
            fail("inputSchema 為數字必須拋 McpProtocol（mcp: bad inputSchema）")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad inputSchema", e.message)
        } catch (e: IllegalArgumentException) {
            fail("inputSchema 為數字必須拋 McpWire.McpProtocol，實際=${e.message}")
        }
    }

    @Test fun parseListMultipleValidToolsStillOk() {
        // 合法混合多 tool（含缺席 inputSchema 預設）仍過。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
            "[{\"name\":\"a\",\"description\":\"da\",\"inputSchema\":{\"type\":\"object\"}}," +
            "{\"name\":\"b\"}]}}"
        val tools = McpWire.parseList("s1", json)
        assertEquals(2, tools.size)
        assertEquals("a", tools[0].name)
        assertEquals("b", tools[1].name)
        assertEquals("""{"type":"object"}""", tools[1].inputSchema)
    }

    // ---- k. Stage D P1：巢狀同名鍵不得遮蔽外層（owner 原 repro） ----

    @Test fun schemaFirstNestedNameDoesNotShadowOuter() {
        // inputSchema 在前、內含 examples[0].name=shadow；外層 name=real_tool 必須正確。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
            "[{\"inputSchema\":{\"type\":\"object\",\"examples\":[{\"name\":\"shadow\"}]}," +
            "\"name\":\"real_tool\"}]}}"
        val tools = McpWire.parseList("s1", json)
        assertEquals(1, tools.size)
        assertEquals("real_tool", tools[0].name)
    }

    @Test fun propertiesNestedNameDoesNotShadowOuter() {
        // properties.name 為 object（值非字串）：不得誤判或拋 tool without name。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
            "[{\"inputSchema\":{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}," +
            "\"name\":\"real_tool2\"}]}}"
        val tools = McpWire.parseList("s1", json)
        assertEquals(1, tools.size)
        assertEquals("real_tool2", tools[0].name)
    }

    @Test fun fieldOrderingWithNestedDescriptionKeepsOuter() {
        // name 在前/中/後三種 + inputSchema 內巢狀 description 遮蔽測試。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[" +
            "{\"name\":\"tool_first\",\"inputSchema\":{\"type\":\"object\",\"description\":\"shadow_first\"}," +
            "\"description\":\"outer_first\"}," +
            "{\"inputSchema\":{\"type\":\"object\",\"description\":\"shadow_mid\"}," +
            "\"name\":\"tool_mid\",\"description\":\"outer_mid\"}," +
            "{\"inputSchema\":{\"type\":\"object\",\"description\":\"shadow_last\"}," +
            "\"description\":\"outer_last\",\"name\":\"tool_last\"}]}}"
        val tools = McpWire.parseList("s1", json)
        assertEquals(3, tools.size)
        assertEquals("tool_first", tools[0].name)
        assertEquals("outer_first", tools[0].description)
        assertEquals("tool_mid", tools[1].name)
        assertEquals("outer_mid", tools[1].description)
        assertEquals("tool_last", tools[2].name)
        assertEquals("outer_last", tools[2].description)
    }

    @Test fun errorOuterMessageWinsOverNested() {
        // 外層在前：舊碼亦過（鎖定）；巢狀在前：舊 indexOf 誤取 inner，必須取 outer。
        val outerFirst = "{\"jsonrpc\":\"2.0\",\"id\":2," +
            "\"error\":{\"code\":1,\"message\":\"outer\",\"details\":{\"message\":\"inner\"}}}"
        try {
            McpWire.parseCall(outerFirst)
            fail("expected McpToolFailure")
        } catch (e: McpWire.McpToolFailure) {
            assertEquals("outer", e.message)
        }
        val innerFirst = "{\"jsonrpc\":\"2.0\",\"id\":2," +
            "\"error\":{\"code\":1,\"details\":{\"message\":\"inner\"},\"message\":\"outer\"}}"
        try {
            McpWire.parseCall(innerFirst)
            fail("expected McpToolFailure")
        } catch (e: McpWire.McpToolFailure) {
            assertEquals("outer", e.message)
        }
    }

    // ---- l. Stage D P2 nested：result/tools 巢狀形狀錯誤一律 PROTOCOL ----

    @Test fun nestedMissingCommaInResultIsProtocol() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[] \"isError\":false}}"
        try {
            McpWire.parseCall(json)
            fail("result 內缺逗號必須拋 PROTOCOL，不得回 OK")
        } catch (e: McpWire.McpToolFailure) {
            fail("缺逗號不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (e: McpWire.McpProtocol) {
            // 新 parser：mcp: bad envelope；舊碼回 OK（RED）。
            assertEquals("mcp: bad envelope", e.message)
        } catch (_: IllegalArgumentException) {
            fail("缺逗號必須拋 McpWire.McpProtocol（非一般 IAE）")
        }
    }

    @Test fun nestedMissingCommaInToolsItemIsProtocol() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[{\"name\":\"x\" \"description\":\"d\"}]}}"
        try {
            McpWire.parseList("s1", json)
            fail("tools 元素內缺逗號必須拋 PROTOCOL，不得回工具列表")
        } catch (e: McpWire.McpProtocol) {
            // 預期（新碼 bad envelope）；舊碼回 OK（RED）。
            assertEquals("mcp: bad envelope", e.message)
        } catch (e: IllegalArgumentException) {
            fail("缺逗號必須拋 McpWire.McpProtocol，實際=${e.message}")
        }
    }

    @Test fun nestedTrailingCommaInSchemaIsProtocol() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
            "[{\"name\":\"x\",\"inputSchema\":{\"type\":\"object\",}}]}}"
        try {
            McpWire.parseList("s1", json)
            fail("schema 內 trailing comma 必須拋 PROTOCOL，不得回工具列表")
        } catch (e: McpWire.McpProtocol) {
            // 預期（新碼 bad envelope）；舊碼回 OK（RED）。
            assertEquals("mcp: bad envelope", e.message)
        } catch (e: IllegalArgumentException) {
            fail("trailing comma 必須拋 McpWire.McpProtocol，實際=${e.message}")
        }
    }

    @Test fun nestedDuplicateContentKeyIsProtocol() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[],\"content\":[]}}"
        try {
            McpWire.parseCall(json)
            fail("result 內重複 content 鍵必須拋 duplicate key，不得回 OK")
        } catch (e: McpWire.McpToolFailure) {
            fail("重複鍵不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: duplicate key", e.message)
        } catch (_: IllegalArgumentException) {
            fail("重複鍵必須拋 McpWire.McpProtocol（mcp: duplicate key）")
        }
    }

    @Test fun nestedDuplicateIsErrorKeyIsProtocol() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2," +
            "\"result\":{\"content\":[],\"isError\":false,\"isError\":true}}"
        try {
            McpWire.parseCall(json)
            fail("result 內重複 isError 鍵必須拋 duplicate key，不得回 OK")
        } catch (e: McpWire.McpToolFailure) {
            fail("重複鍵不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: duplicate key", e.message)
        } catch (_: IllegalArgumentException) {
            fail("重複鍵必須拋 McpWire.McpProtocol（mcp: duplicate key）")
        }
    }

    @Test fun nestedIllegalEscapeInUnreadNoteIsProtocol() {
        // note 非讀取鍵：parser 仍須嚴格（舊碼忽略回 OK）。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2," +
            "\"result\":{\"content\":[],\"note\":\"\\x\"}}"
        try {
            McpWire.parseCall(json)
            fail("非法 escape \\x 必須拋 PROTOCOL，不得回 OK")
        } catch (e: McpWire.McpToolFailure) {
            fail("非法 escape 不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad envelope", e.message)
        } catch (_: IllegalArgumentException) {
            fail("非法 escape 必須拋 McpWire.McpProtocol（mcp: bad envelope）")
        }
    }

    @Test fun nestedIllegalEscapeInToolNameIsProtocol() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[{\"name\":\"a\\x\"}]}}"
        try {
            McpWire.parseList("s1", json)
            fail("tool name 非法 escape 必須拋 PROTOCOL，不得回工具列表")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad envelope", e.message)
        } catch (e: IllegalArgumentException) {
            fail("非法 escape 必須拋 McpWire.McpProtocol，實際=${e.message}")
        }
    }

    @Test fun duplicateKeyAfterEscapeDecodeIsProtocol() {
        // 解碼後重複（\u0061 == a）：原文比對會漏，必須解碼後比對。
        val json = "{\"a\":1,\"\\u0061\":2,\"result\":{\"tools\":[]}}"
        try {
            McpWire.parseList("s1", json)
            fail("解碼後重複鍵必須拋 duplicate key")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: duplicate key", e.message)
        } catch (e: IllegalArgumentException) {
            fail("必須拋 McpWire.McpProtocol，實際=${e.message}")
        }
    }

    @Test fun nestedDuplicateKeyAfterEscapeDecodeIsProtocol() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2," +
            "\"result\":{\"content\":[],\"\\u0063ontent\":[]}}"
        try {
            McpWire.parseCall(json)
            fail("巢狀解碼後重複鍵必須拋 duplicate key")
        } catch (e: McpWire.McpToolFailure) {
            fail("重複鍵不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: duplicate key", e.message)
        } catch (_: IllegalArgumentException) {
            fail("必須拋 McpWire.McpProtocol（mcp: duplicate key）")
        }
    }

    @Test fun nestedIllegalLiteralContentNoneIsProtocol() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":None}}"
        try {
            McpWire.parseCall(json)
            fail("content:None 非法字面量必須拋 PROTOCOL，不得回 OK")
        } catch (e: McpWire.McpToolFailure) {
            fail("非法字面量不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (e: McpWire.McpProtocol) {
            // 新 parser：mcp: bad envelope；舊碼為 mcp: bad content（同為 PROTOCOL，訊息收緊）。
        } catch (_: IllegalArgumentException) {
            fail("非法字面量必須拋 McpWire.McpProtocol（非一般 IAE）")
        }
    }

    @Test fun deepLegalSchemaParsesCorrectly() {
        // 3-4 層合法 schema（含 examples/properties 陣列物件）：防過度嚴格退化。
        val schema = "{\"type\":\"object\",\"properties\":{\"addr\":{\"type\":\"object\"," +
            "\"properties\":{\"city\":{\"type\":\"string\",\"examples\":[{\"name\":\"ex\"}]}}}}," +
            "\"examples\":[{\"a\":[{\"b\":1}]}]}"
        val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":" +
            "[{\"name\":\"deep_tool\",\"description\":\"outer_desc\",\"inputSchema\":$schema}]}}"
        val tools = McpWire.parseList("s1", json)
        assertEquals(1, tools.size)
        assertEquals("deep_tool", tools[0].name)
        assertEquals("outer_desc", tools[0].description)
        assertEquals(schema, tools[0].inputSchema)
    }

    @Test fun tooDeepGuardThrowsProtocol() {
        // 70 層巢狀 array 直測 parser：合法形狀但超深 → mcp: too deep（PROTOCOL 級，不 crash）。
        val deepArr = "[".repeat(70) + "]".repeat(70)
        try {
            McpWire.parseStrictDoc(deepArr)
            fail("70 層巢狀 array 必須拋 mcp: too deep，不得回 OK 或 StackOverflow")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: too deep", e.message)
        } catch (e: IllegalArgumentException) {
            fail("超深必須拋 McpWire.McpProtocol（mcp: too deep），實際=${e.message}")
        }
        // 經 parseCall 的 content 深巢狀同理。
        val arrJson = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":$deepArr,\"isError\":false}}"
        try {
            McpWire.parseCall(arrJson)
            fail("70 層巢狀 array 必須拋 mcp: too deep，不得回 OK 或 StackOverflow")
        } catch (e: McpWire.McpToolFailure) {
            fail("超深不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: too deep", e.message)
        } catch (e: IllegalArgumentException) {
            fail("超深必須拋 McpWire.McpProtocol（mcp: too deep），實際=${e.message}")
        }
        // 經 parseCall 的 70 層 object 巢狀同理。
        var nested = "1"
        repeat(70) { nested = "{\"k\":$nested}" }
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[],\"deep\":$nested}}"
        try {
            McpWire.parseCall(json)
            fail("70 層 object 巢狀必須拋 mcp: too deep")
        } catch (e: McpWire.McpToolFailure) {
            fail("超深不得映射 TOOL_ERROR，實際=${e.message}")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: too deep", e.message)
        } catch (e: IllegalArgumentException) {
            fail("超深必須拋 McpWire.McpProtocol，實際=${e.message}")
        }
    }
}
