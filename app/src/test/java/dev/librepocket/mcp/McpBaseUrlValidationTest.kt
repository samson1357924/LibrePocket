package dev.librepocket.mcp

import dev.librepocket.keystore.KeyVault
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * baseUrl 形狀先驗（McpServer）：使用者設定的地址若無法形成有效 http(s)
 * endpoint，屬配置問題而非傳輸失敗——必須在任何 vault 讀取、任何 HTTP 與任何
 * 熔斷計數之前 fail-closed 為 UNAUTHORIZED。
 *
 * 舊行為：malformed URL 一路走到傳輸層才拋 IllegalArgumentException，被外層
 * catch(Exception) 映射成 TRANSPORT 並計一次熔斷失敗——幾次壞設定就會打開整台
 * 機器的 MCP 通道（熔斷器是全域共享的）。
 */
class McpBaseUrlValidationTest {

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

    /** A vault that FAILS the test if it is ever read: a malformed address
     *  must be rejected before the key is fetched. */
    private class VaultMustNotBeRead : KeyVault {
        override suspend fun putKey(providerId: String, apiKey: CharArray) = error("no put")
        override suspend fun getKey(providerId: String): CharArray? =
            error("malformed baseUrl must not touch the vault")
        override suspend fun deleteKey(providerId: String) = error("no delete")
        override suspend fun hasKey(providerId: String): Boolean = error("no hasKey")
    }

    private class Clock(var now: Long = 0L) {
        fun get(): Long = now
    }

    private fun goodListBody(): String =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[" +
            "{\"name\":\"search\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}}]}}"

    private fun okCallBody(): String =
        "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}}"

    @Test fun malformedBaseUrlIsUnauthorizedWithoutVaultHttpOrBreaker() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            // No response enqueued: any HTTP attempt would surface as a failure.
            val clock = Clock(0L)
            val breaker = McpCircuitBreaker(maxFailures = 3, openMs = 60_000L, clockMs = clock::get)
            // `http://`/`https://` (no host) and `https:/host` (single slash)
            // are the shapes OkHttp's lenient parser would salvage — the
            // pre-flight must reject them anyway.
            for (bad in listOf(
                "not-a-url",
                "ftp://host",
                "http://",
                "https://",
                "https:/host",
                "mcp.example.com",
            )) {
                val s = McpServer(
                    config = McpServerConfig(id = "s1", baseUrl = bad, keyId = "mcp:s1"),
                    keyVault = VaultMustNotBeRead(),
                    transport = McpTransport(),
                    breaker = breaker,
                    clockMs = clock::get,
                )
                val listed = s.listTools()
                assertEquals("listTools baseUrl=$bad", McpStatus.UNAUTHORIZED, listed.status)
                assertTrue("listTools baseUrl=$bad", listed.tools.isEmpty())
                val called = s.callTool("search", "{}")
                assertEquals("callTool baseUrl=$bad", McpStatus.UNAUTHORIZED, called.status)
                assertNull("callTool baseUrl=$bad", called.resultJson)
            }
            // Zero HTTP, zero breaker failures: a configuration problem must
            // not poison the shared circuit breaker.
            assertEquals(0, web.requestCount)
            assertEquals(0, breaker.failureCount())
            assertFalse(breaker.isOpen())
        } finally {
            web.shutdown()
        }
    }

    @Test fun malformedBaseUrlIsUnauthorizedEvenWithAStoredKey() = runBlocking {
        // The address shape is the discriminator, not the key: with a real
        // key in the vault a malformed address must still be UNAUTHORIZED
        // (never OK, never TRANSPORT) and must not reach the wire.
        //
        // NON-VACUOUS SHAPE: the address below is a SALVAGE shape — OkHttp's
        // lenient parser resolves it to the MockWebServer host/port, so
        // WITHOUT the pre-flight the calls would hit the mock (the two
        // enqueued 200 bodies below) and return OK. With the pre-flight the
        // request never leaves the process (requestCount == 0) and both
        // calls are UNAUTHORIZED. (The `http://` no-host shape stays covered
        // by the loop in malformedBaseUrlIsUnauthorizedWithoutVaultHttpOrBreaker.)
        val web = MockWebServer()
        web.start()
        try {
            web.enqueue(MockResponse().setResponseCode(200).setBody(goodListBody()))
            web.enqueue(MockResponse().setResponseCode(200).setBody(okCallBody()))
            val salvage = "http:/127.0.0.1:${web.port}/mcp"
            // Proof that the shape really is salvageable by OkHttp: without
            // this, the zero-HTTP assertion below would be vacuous (the
            // address might be hopeless for OkHttp too).
            val salvaged = salvage.toHttpUrlOrNull()
            assertNotNull("OkHttp must salvage the single-slash shape", salvaged)
            assertEquals("127.0.0.1", salvaged!!.host)
            assertEquals(web.port, salvaged.port)
            val clock = Clock(0L)
            val breaker = McpCircuitBreaker(maxFailures = 3, openMs = 60_000L, clockMs = clock::get)
            val s = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = salvage, keyId = "mcp:s1"),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
                clockMs = clock::get,
            )
            assertEquals(McpStatus.UNAUTHORIZED, s.listTools().status)
            assertEquals(McpStatus.UNAUTHORIZED, s.callTool("search", "{}").status)
            // Zero HTTP (the mock never saw a request — the enqueued 200
            // bodies stayed unconsumed) and zero breaker failures (a
            // configuration problem must not poison the shared breaker).
            assertEquals(0, web.requestCount)
            assertEquals(0, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun disabledWinsOverMalformedBaseUrl() = runBlocking {
        // Ordering pin: the DISABLED switch is the cheapest short-circuit
        // and must win over the baseUrl shape check (a disabled server is
        // not "unauthorized" — it is off). Zero vault read, zero HTTP, zero
        // breaker count.
        val web = MockWebServer()
        web.start()
        try {
            val clock = Clock(0L)
            val breaker = McpCircuitBreaker(maxFailures = 3, openMs = 60_000L, clockMs = clock::get)
            val s = McpServer(
                config = McpServerConfig(
                    id = "s1",
                    baseUrl = "not-a-url",
                    keyId = "mcp:s1",
                    enabled = false,
                ),
                keyVault = VaultMustNotBeRead(),
                transport = McpTransport(),
                breaker = breaker,
                clockMs = clock::get,
            )
            assertEquals(McpStatus.DISABLED, s.listTools().status)
            assertEquals(McpStatus.DISABLED, s.callTool("search", "{}").status)
            assertEquals(0, web.requestCount)
            assertEquals(0, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun openCircuitWinsOverMalformedBaseUrl() = runBlocking {
        // Ordering pin: an OPEN circuit short-circuits before the baseUrl
        // shape check (the existing breaker semantics must not change). The
        // failure count stays at 1 — the malformed address adds no failure
        // of its own, and the open circuit never reaches the wire.
        val web = MockWebServer()
        web.start()
        try {
            val clock = Clock(0L)
            val breaker = McpCircuitBreaker(maxFailures = 1, openMs = 60_000L, clockMs = clock::get)
            breaker.onFailure() // one failure opens the circuit (maxFailures = 1)
            assertTrue(breaker.isOpen())
            val s = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = "not-a-url", keyId = "mcp:s1"),
                keyVault = VaultMustNotBeRead(),
                transport = McpTransport(),
                breaker = breaker,
                clockMs = clock::get,
            )
            assertEquals(McpStatus.CIRCUIT_OPEN, s.listTools().status)
            assertEquals(McpStatus.CIRCUIT_OPEN, s.callTool("search", "{}").status)
            assertEquals(0, web.requestCount)
            assertEquals(1, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun blankBaseUrlStaysUnauthorized() = runBlocking {
        val web = MockWebServer()
        web.start()
        try {
            val clock = Clock(0L)
            val breaker = McpCircuitBreaker(maxFailures = 3, openMs = 60_000L, clockMs = clock::get)
            for (blank in listOf("", "   ")) {
                val s = McpServer(
                    config = McpServerConfig(id = "s1", baseUrl = blank, keyId = "mcp:s1"),
                    keyVault = VaultMustNotBeRead(),
                    transport = McpTransport(),
                    breaker = breaker,
                    clockMs = clock::get,
                )
                assertEquals(McpStatus.UNAUTHORIZED, s.listTools().status)
                assertEquals(McpStatus.UNAUTHORIZED, s.callTool("search", "{}").status)
            }
            assertEquals(0, web.requestCount)
            assertEquals(0, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun malformedArgsStillWinProtocolOverMalformedBaseUrl() = runBlocking {
        // Ordering pin: a caller-side argument bug is reported as PROTOCOL,
        // never re-labelled as UNAUTHORIZED by the address check (the address
        // check runs after the body validation). Zero vault, zero HTTP, zero
        // breaker either way.
        val web = MockWebServer()
        web.start()
        try {
            val clock = Clock(0L)
            val breaker = McpCircuitBreaker(maxFailures = 3, openMs = 60_000L, clockMs = clock::get)
            val s = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = "not-a-url", keyId = "mcp:s1"),
                keyVault = VaultMustNotBeRead(),
                transport = McpTransport(),
                breaker = breaker,
                clockMs = clock::get,
            )
            assertEquals(McpStatus.PROTOCOL, s.callTool("search", "[1,2]").status)
            assertEquals(McpStatus.PROTOCOL, s.callTool("search", "not json").status)
            // Valid args + malformed address → UNAUTHORIZED (config problem).
            assertEquals(McpStatus.UNAUTHORIZED, s.callTool("search", "{}").status)
            assertEquals(0, web.requestCount)
            assertEquals(0, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun validBaseUrlStillListsAndCalls() = runBlocking {
        // The address check must not reject legitimate configurations: a
        // well-formed baseUrl keeps the normal OK path (list + call).
        val web = MockWebServer()
        web.start()
        try {
            web.enqueue(MockResponse().setResponseCode(200).setBody(goodListBody()))
            web.enqueue(MockResponse().setResponseCode(200).setBody(okCallBody()))
            val clock = Clock(0L)
            val breaker = McpCircuitBreaker(maxFailures = 3, openMs = 60_000L, clockMs = clock::get)
            val s = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString(), keyId = "mcp:s1"),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
                clockMs = clock::get,
            )
            val listed = s.listTools()
            assertEquals(McpStatus.OK, listed.status)
            assertEquals(listOf("search"), listed.tools.map { it.name })
            val called = s.callTool("search", "{}")
            assertEquals(McpStatus.OK, called.status)
            assertEquals(2, web.requestCount)
            assertEquals(0, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    @Test fun validBaseUrlWithServerErrorIsTransportNotUnauthorized() = runBlocking {
        // Control group for the baseUrl pre-flight: a REAL transport failure
        // on a well-formed address must stay TRANSPORT (and count ONE
        // breaker failure). If the pre-flight mislabelled a live server
        // error as UNAUTHORIZED, the breaker would never see the failure
        // (a configuration problem must not poison the shared breaker) and
        // the operator would read a server outage as an auth problem.
        val web = MockWebServer()
        web.start()
        try {
            // HTTP 500 with a non-JSON body: a server-side failure, not a
            // caller/config problem.
            web.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            val clock = Clock(0L)
            val breaker = McpCircuitBreaker(maxFailures = 3, openMs = 60_000L, clockMs = clock::get)
            val s = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = web.url("/").toString(), keyId = "mcp:s1"),
                keyVault = FakeVault(),
                transport = McpTransport(),
                breaker = breaker,
                clockMs = clock::get,
            )
            val listed = s.listTools()
            assertEquals(McpStatus.TRANSPORT, listed.status)
            assertTrue(listed.tools.isEmpty())
            // The failure reached the wire (the pre-flight let it through)
            // and counted exactly ONE breaker failure.
            assertEquals(1, web.requestCount)
            assertEquals(1, breaker.failureCount())
            assertFalse(breaker.isOpen())
            // Same for the call path: a well-formed address + a server error
            // is TRANSPORT, not UNAUTHORIZED.
            web.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            val called = s.callTool("search", "{}")
            assertEquals(McpStatus.TRANSPORT, called.status)
            assertNull(called.resultJson)
            assertEquals(2, web.requestCount)
            assertEquals(2, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }
}
