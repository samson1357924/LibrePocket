package dev.librepocket.mcp

import dev.librepocket.keystore.KeyVault
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Stage2 envelope 完整性契約（外部獨立 review #2／#4）：
 * `jsonrpc=="2.0"`、id expectedId 校驗、result XOR error、
 * error code taxonomy（協議碼 → [McpWire.McpProtocol] → PROTOCOL + 計熔斷；
 * `-32602`/未知碼 → [McpWire.McpToolFailure] → TOOL_ERROR 中性）、
 * request-shaped 拒、出站預檢（toolName 長度、arguments 上限）。
 *
 * 全程 MockWebServer／純 wire 解析，不連外網。
 */
class McpEnvelopeTest {

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
        // -32602（invalid params）與未知碼：工具級中性，message 保留伺服器文字。
        val cases = mapOf(
            -32602 to "bad input",
            -32000 to "boom",
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
            val longName = withTimeoutOrNull(5_000) {
                server.callTool("t".repeat(McpWire.TOOL_NAME_MAX_CHARS + 1), "{}")
            }!!
            assertEquals(McpStatus.PROTOCOL, longName.status)
            val bigArgs = withTimeoutOrNull(5_000) {
                server.callTool("t", "{\"pad\":\"" + "x".repeat(McpWire.ARGUMENTS_MAX_CHARS) + "\"}")
            }!!
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
                withTimeoutOrNull(5_000) { server.callTool("t", "{}") }!!.status,
            )
            assertEquals(1, breaker.failureCount())
            assertEquals(
                McpStatus.PROTOCOL,
                withTimeoutOrNull(5_000) { server.callTool("t", "{}") }!!.status,
            )
            assertEquals(2, breaker.failureCount())
            assertEquals(
                McpStatus.PROTOCOL,
                withTimeoutOrNull(5_000) { server.callTool("t", "{}") }!!.status,
            )
            assertEquals("協議碼必須累計", 3, breaker.failureCount())
            assertTrue("協議碼必須累計並開路", breaker.isOpen())
            assertEquals(
                McpStatus.CIRCUIT_OPEN,
                withTimeoutOrNull(5_000) { server.callTool("t", "{}") }!!.status,
            )
        } finally {
            web.shutdown()
        }
    }

    @Test fun invalidParamsStaysNeutralThroughServer() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // -32602 仍工具級中性（既有 errorPacketIsNeutralDoesNotResetBreaker 語義）。
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
            assertEquals(
                McpStatus.TOOL_ERROR,
                withTimeoutOrNull(5_000) { server.callTool("t", "{}") }!!.status,
            )
            assertEquals("-32602 必須中性", 0, breaker.failureCount())
            assertEquals(
                McpStatus.TRANSPORT,
                withTimeoutOrNull(5_000) { server.callTool("t", "{}") }!!.status,
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
            val list = withTimeoutOrNull(5_000) { server.listTools() }!!
            assertEquals(McpStatus.PROTOCOL, list.status)
            assertTrue("錯 id 回應不得採納", list.tools.isEmpty())
            assertEquals(1, breaker.failureCount())
            // call 同語義（id 1 ≠ CALL_ID 2）。
            val call = withTimeoutOrNull(5_000) { server.callTool("t", "{}") }!!
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
            val got = withTimeoutOrNull(5_000) { server.listTools() }!!
            assertEquals(McpStatus.PROTOCOL, got.status)
            assertTrue(got.tools.isEmpty())
            assertEquals(1, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }
}
