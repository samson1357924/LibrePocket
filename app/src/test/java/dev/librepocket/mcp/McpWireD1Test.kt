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
 * - endpoint() 保留使用者自訂完整 path（不強制改寫 /mcp）；
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

    // ---- b. endpoint() 保留自訂 path ----

    @Test fun endpointPreservesCustomPathWithoutForcingMcp() {
        // 無後綴保留原樣，不強制附加 /mcp。
        assertEquals("https://x.example", McpWire.endpoint("https://x.example/"))
        assertEquals(
            "https://x.example/custom/path",
            McpWire.endpoint("https://x.example/custom/path/"),
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
            assertTrue("拒絕須帶原因碼", e.message != null && e.message!!.contains("inputSchema"))
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
            assertTrue(
                "須為缺 result 的 fail-closed 拒絕，實際=${e.message}",
                e.message != null && e.message!!.contains("missing result"),
            )
        }
    }
}
