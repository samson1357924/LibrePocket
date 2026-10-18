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

    @Test fun resultAndErrorBothPresentRejected() {
        // result XOR error（Stage2，外部 review #2）：矛盾包不得擇一採納。
        val json = "{\"jsonrpc\":\"2.0\",\"id\":2," +
            // 錯誤碼用範圍外中性碼（1）：互斥檢查先於 code 分類，此測試只pin XOR，
            // 不得依賴 server 範圍碼的分類結果（那由 serverErrorRangeCodesAreProtocol 釘）。
            "\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}," +
            "\"error\":{\"code\":1,\"message\":\"boom\"}}"
        try {
            McpWire.parseCall(json)
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
            McpWire.parseCall(both)
            fail("error:null + result 必須拒")
        } catch (e: McpWire.McpProtocol) {
            assertEquals("mcp: bad error", e.message)
        }
        val onlyNull = "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":null}"
        try {
            McpWire.parseCall(onlyNull)
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
            McpWire.parseCall(truncated)
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
        val (result, isError) = McpWire.parseCall(json)
        assertFalse(isError)
        assertTrue("40k CJK 結果必須完整保留（無 32k 靜默截斷）", result != null && result.contains(big))
    }
}
