package dev.librepocket.mcp

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
        val (result, isError) = McpWire.parseCall(json)
        assertFalse(isError)
        assertTrue(result != null && result.contains("error"))
    }

    @Test fun nestedErrorKeyInsideResultIsOk() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
            "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]," +
            "\"structuredContent\":{\"error\":\"nested detail\"},\"isError\":false}}"
        val (result, isError) = McpWire.parseCall(json)
        assertFalse(isError)
        assertTrue(result != null && result.contains("ok"))
    }

    @Test fun contentItemWithErrorKeyIsOk() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
            "{\"content\":[{\"type\":\"text\",\"text\":\"hi\",\"error\":\"detail\"}],\"isError\":false}}"
        val (_, isError) = McpWire.parseCall(json)
        assertFalse(isError)
    }

    @Test fun topLevelErrorStillThrows() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2," +
            "\"error\":{\"code\":-32602,\"message\":\"bad input\"}}"
        try {
            McpWire.parseCall(json)
            fail("expected McpToolFailure")
        } catch (e: McpWire.McpToolFailure) {
            assertEquals("bad input", e.message)
        }
    }

    @Test fun topLevelErrorWinsOverNestedResultShape() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2," +
            "\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}," +
            "\"error\":{\"code\":-32000,\"message\":\"boom\"}}"
        try {
            McpWire.parseCall(json)
            fail("expected McpToolFailure")
        } catch (e: McpWire.McpToolFailure) {
            assertEquals("boom", e.message)
        }
    }

    @Test fun nullTopLevelErrorIsOk() {
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":" +
            "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false},\"error\":null}"
        val (_, isError) = McpWire.parseCall(json)
        assertFalse(isError)
    }
}
