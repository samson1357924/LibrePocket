package dev.librepocket.mcp

import dev.librepocket.keystore.KeyVault
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D03 熔斷測試（BACKLOG D03）：連續失敗開路，開路期間短路不發 HTTP；
 * 視窗過後恢復；工具業務錯不計熔斷；Token 只走標頭；稽核不含原參數。
 *
 * 全程 MockWebServer，不連外網。
 */
class McpFuseTest {

    private class FakeVault(keys: Map<String, String> = mapOf("mcp:s1" to "sekret-token-1")) : KeyVault {
        private val map = keys.mapValues { it.value.toCharArray() }.toMutableMap()
        override suspend fun putKey(providerId: String, apiKey: CharArray) {
            map[providerId] = apiKey.copyOf()
        }
        override suspend fun getKey(providerId: String): CharArray? = map[providerId]?.copyOf()
        override suspend fun deleteKey(providerId: String) {
            map.remove(providerId)
        }
        override suspend fun hasKey(providerId: String): Boolean = map.containsKey(providerId)
    }

    private class Clock(var now: Long = 0L) {
        fun get(): Long = now
    }

    private fun server(
        web: MockWebServer,
        clock: Clock,
        breaker: McpCircuitBreaker,
        id: String = "s1",
        keyId: String = "mcp:s1",
    ): McpServer = McpServer(
        config = McpServerConfig(id = id, baseUrl = web.url("/").toString(), keyId = keyId),
        keyVault = FakeVault(),
        transport = McpTransport(),
        breaker = breaker,
        clockMs = clock::get,
    )

    private fun okCall(): MockResponse {
        val body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}}"
        return MockResponse().setResponseCode(200).setBody(body)
    }

    private fun toolErrorCall(): MockResponse {
        val body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"nope\"}],\"isError\":true}}"
        return MockResponse().setResponseCode(200).setBody(body)
    }

    private fun badListBody(): String {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":\"oops\"}}"
    }

    private fun goodListBody(): String {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[{\"name\":\"search\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}}]}}"
    }

    @Test fun consecutiveTransportFailuresOpenCircuit() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            repeat(3) { web.enqueue(MockResponse().setResponseCode(500).setBody("boom")) }
            val clock = Clock(1_000L)
            val breaker = McpCircuitBreaker(maxFailures = 3, openMs = 60_000L, clockMs = clock::get)
            val s = server(web, clock, breaker)
            val arg = "{\"q\":\"x\"}"
            repeat(3) {
                val r = s.callTool("search", arg)
                assertEquals(McpStatus.TRANSPORT, r.status)
            }
            assertTrue(breaker.isOpen())
            assertEquals(3, web.requestCount)
            // 開路期間短路：不發 HTTP。
            val shorted = s.callTool("search", arg)
            assertEquals(McpStatus.CIRCUIT_OPEN, shorted.status)
            assertEquals(3, web.requestCount)
        } finally {
            web.shutdown()
        }
    }

    @Test fun windowExpiryRecovers() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            repeat(3) { web.enqueue(MockResponse().setResponseCode(500).setBody("boom")) }
            web.enqueue(okCall())
            val clock = Clock(0L)
            val breaker = McpCircuitBreaker(maxFailures = 3, openMs = 60_000L, clockMs = clock::get)
            val s = server(web, clock, breaker)
            val emptyArg = "{}"
            repeat(3) { s.callTool("search", emptyArg) }
            assertTrue(breaker.isOpen())
            clock.now = 61_000L
            val hiArg = "{\"q\":\"hi\"}"
            val r = s.callTool("search", hiArg)
            assertEquals(McpStatus.OK, r.status)
            assertFalse(breaker.isOpen())
            assertEquals(0, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun toolBusinessErrorDoesNotTripBreaker() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            repeat(3) { web.enqueue(toolErrorCall()) }
            val clock = Clock(0L)
            val breaker = McpCircuitBreaker(maxFailures = 3, openMs = 60_000L, clockMs = clock::get)
            val s = server(web, clock, breaker)
            repeat(3) {
                val r = s.callTool("search", "{}")
                assertEquals(McpStatus.TOOL_ERROR, r.status)
            }
            assertFalse(breaker.isOpen())
            assertEquals(0, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun unauthorizedCountsAsFailure() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            repeat(3) { web.enqueue(MockResponse().setResponseCode(401).setBody("no")) }
            val clock = Clock(0L)
            val breaker = McpCircuitBreaker(maxFailures = 3, openMs = 60_000L, clockMs = clock::get)
            val s = server(web, clock, breaker)
            repeat(3) {
                assertEquals(McpStatus.UNAUTHORIZED, s.callTool("t", "{}").status)
            }
            assertTrue(breaker.isOpen())
        } finally {
            web.shutdown()
        }
    }

    @Test fun tokenOnlyInHeaderAndAuditHasNoArgs() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            web.enqueue(okCall())
            val clock = Clock(0L)
            val s = server(web, clock, McpCircuitBreaker(clockMs = clock::get))
            val secretArg = "s3cr3t-query-zzz"
            val arg = "{\"q\":\"" + secretArg + "\"}"
            val r = s.callTool("search", arg)
            assertEquals(McpStatus.OK, r.status)
            val req = web.takeRequest()
            // Token 只走標頭。
            assertEquals("Bearer sekret-token-1", req.getHeader("Authorization"))
            val body = req.body.readUtf8()
            assertFalse("token leaked into body", body.contains("sekret-token-1"))
            // 原參數不進稽核。
            val audit = r.auditMap().toString()
            assertFalse("args leaked into audit", audit.contains(secretArg))
            assertFalse("token leaked into audit", audit.contains("sekret-token-1"))
            assertTrue(audit.contains("search"))
        } finally {
            web.shutdown()
        }
    }

    @Test fun listParsesToolsAndEndpointIsSlashMcp() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // 第一包是惡形（tools 不是陣列）→ PROTOCOL。
            web.enqueue(MockResponse().setResponseCode(200).setBody(badListBody()))
            web.enqueue(MockResponse().setResponseCode(200).setBody(goodListBody()))
            val clock = Clock(0L)
            val s = server(web, clock, McpCircuitBreaker(clockMs = clock::get))
            val bad = s.listTools()
            assertEquals(McpStatus.PROTOCOL, bad.status)
            val good = s.listTools()
            assertEquals(McpStatus.OK, good.status)
            assertEquals(listOf("search"), good.tools.map { it.name })
            // 端點形狀：POST /mcp（Streamable HTTP，不走 SSE）。
            val first = web.takeRequest()
            val second = web.takeRequest()
            for (req in listOf(first, second)) {
                assertEquals("/mcp", req.path?.substringBefore("?"))
                assertEquals("POST", req.method)
            }
            assertEquals("https://x.example/mcp", McpWire.endpoint("https://x.example/"))
            assertEquals("https://x.example/mcp", McpWire.endpoint("https://x.example/mcp"))
        } finally {
            web.shutdown()
        }
    }
}
