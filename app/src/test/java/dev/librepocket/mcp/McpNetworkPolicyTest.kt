package dev.librepocket.mcp

import dev.librepocket.keystore.KeyVault
import dev.librepocket.policy.EndpointNetworkOptIn
import dev.librepocket.policy.HostResolver
import dev.librepocket.policy.NetworkDenyCode
import dev.librepocket.policy.NetworkEgressPolicy
import dev.librepocket.policy.NetworkEvaluation
import dev.librepocket.policy.NetworkOptInStore
import dev.librepocket.policy.NetworkPolicy
import dev.librepocket.policy.PinnedNetworkDns
import java.io.IOException
import java.lang.reflect.Modifier
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A3 (Issue #135 A3)：MCP per-endpoint NetworkPolicy 接線。
 *
 * 契約：
 * - `McpServerConfig.networkOptIn` per-server、預設 deny-all（HTTPS_ONLY），無全域一鍵開放；
 * - 建連前先調 `NetworkPolicy.evaluateUrl`（讀 key／建連之前）：拒絕即 typed fatal
 *   [McpNetworkPolicyBlocked]（deny code 可辨、不重試），server 層映射 PROTOCOL、
 *   零 vault／零 HTTP／零熔斷；
 * - redirect 0-follow（3xx 終端 → PROTOCOL＋稽核 `denyCode=REDIRECT_BLOCKED`，
 *   即 REDIRECT_BLOCKED 語義，不逐跳跟隨）；
 * - metadata／link-local／multicast／unspecified／CGNAT 永遠阻擋（opt-in 也打不開）；
 * - opt-in 綁定 endpoint identity＋configRevision：任一變動使舊確認自動失效
 *   （僅綁 opt-in；key 綁定仍 open，日後不 overclaim）。
 *
 * 全假 key／MockWebServer／fake Dns，禁真網路（字面 IP 用必敗 resolver 證零 DNS）。
 */
class McpNetworkPolicyTest {

    private class CountingVault : KeyVault {
        val reads = AtomicInteger(0)
        override suspend fun putKey(providerId: String, apiKey: CharArray) = Unit
        override suspend fun getKey(providerId: String): CharArray? {
            reads.incrementAndGet()
            return "sekret-token-1".toCharArray()
        }
        override suspend fun deleteKey(providerId: String) = Unit
        override suspend fun hasKey(providerId: String): Boolean = true
    }

    private fun addr(literal: String): InetAddress = InetAddress.getByName(literal)

    private fun fakeDns(vararg entries: Pair<String, List<String>>): HostResolver {
        val table = entries.toMap()
        return HostResolver { host ->
            table[host]?.map(::addr) ?: throw UnknownHostException(host)
        }
    }

    private val noDns: HostResolver = HostResolver {
        throw UnknownHostException("must not resolve literals")
    }

    private fun pilotStore() = NetworkOptInStore(allowTierUpgrade = true)

    private fun goodListBody() =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}"

    private fun goodCallBody() =
        "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}}"

    private fun interceptorClient(
        onCall: (okhttp3.Request) -> Response,
    ): OkHttpClient = McpTransport.defaultHttp().newBuilder()
        .addInterceptor { chain -> onCall(chain.request()) }
        .build()

    private fun mock200JsonResponse(request: okhttp3.Request, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()

    // ---- 正向：https 公網 PASS ----

    @Test fun httpsPublicPassesAtGate() {
        val dns = fakeDns("example.com" to listOf("203.0.113.7"))
        val eval = requireMcpNetworkPolicyAllowed(
            "https://example.com/mcp",
            EndpointNetworkOptIn(),
            dns,
        )
        assertTrue("https 公網預設即放行", eval.allowed)
    }

    @Test fun privateNameNeedsOptInAtGate() {
        val dns = fakeDns("example.com" to listOf("10.1.2.3"))
        // 預設 deny-all：https 解析到私有即拒。
        try {
            requireMcpNetworkPolicyAllowed("https://example.com/mcp", EndpointNetworkOptIn(), dns)
            fail("private https without opt-in must throw")
        } catch (e: McpNetworkPolicyBlocked) {
            assertEquals(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, e.denyCode)
        }
        // ALLOW_PRIVATE_DNS 後放行（僅驗閘，不建連）。
        val allowed = requireMcpNetworkPolicyAllowed(
            "https://example.com/mcp",
            EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_PRIVATE_DNS),
            dns,
        )
        assertTrue(allowed.allowed)
    }

    // ---- 正向：loopback opt-in 後 http PASS（預設先拒） ----

    @Test fun loopbackHttpDeniedByDefaultThenPassesAfterOptIn() = runBlocking {
        val web = MockWebServer()
        try {
            web.start()
            val base = web.url("/").toString()
            val store = pilotStore()
            val vault = CountingVault()
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = base),
                keyVault = vault,
                transport = McpTransport(),
                breaker = breaker,
                networkOptInStore = store,
            )
            // 未確認：預設 deny-all → PROTOCOL，零 vault／零 HTTP／零熔斷。
            val denied = withTimeoutOrNull(5_000) { server.listTools() }
            assertNotNull("5s 內未回傳", denied)
            assertEquals(McpStatus.PROTOCOL, denied!!.status)
            assertEquals("denied sends nothing", 0, web.requestCount)
            assertEquals("credential must not be fetched on deny", 0, vault.reads.get())
            assertEquals(0, breaker.failureCount())
            // 直調同拒：typed fatal 帶 CLEARTEXT_NOT_PERMITTED。
            try {
                McpTransport().postJson(base, "sekret", "{}", McpTimeouts.DEFAULT_MS)
                fail("loopback http without opt-in must throw")
            } catch (e: McpNetworkPolicyBlocked) {
                assertEquals(NetworkDenyCode.CLEARTEXT_NOT_PERMITTED, e.denyCode)
            }
            // 明示確認升級後放行。
            store.confirm(
                mcpEndpointId("s1"),
                base,
                NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE,
                0L,
            )
            web.enqueue(MockResponse().setResponseCode(200).setBody(goodListBody()))
            val ok = withTimeoutOrNull(5_000) { server.listTools() }
            assertNotNull("5s 內未回傳", ok)
            assertEquals("opt-in 後 loopback http 必須 OK", McpStatus.OK, ok!!.status)
            assertEquals(1, web.requestCount)
            assertEquals(1, vault.reads.get())
        } finally {
            web.shutdown()
        }
    }

    // ---- 負向：10.evil.com 經 http 拒 ----

    @Test fun tenEvilComHttpDenied() = runBlocking {
        // `10.evil.com` 是公網名（字串前綴比對會誤放行，位元組分類不會）。
        val dns = fakeDns("10.evil.com" to listOf("203.0.113.7"))
        // 政策層同拒（typed fatal 帶 CLEARTEXT_NOT_PERMITTED：公網 http 永不開放）。
        try {
            requireMcpNetworkPolicyAllowed("http://10.evil.com/mcp", EndpointNetworkOptIn(), dns)
            fail("public http must be denied at gate")
        } catch (e: McpNetworkPolicyBlocked) {
            assertEquals(NetworkDenyCode.CLEARTEXT_NOT_PERMITTED, e.denyCode)
        }
        // server 層：形狀閘先擋（非回環明文）→ PROTOCOL，零 vault／零熔斷、不建連。
        val vault = CountingVault()
        val breaker = McpCircuitBreaker()
        val server = McpServer(
            config = McpServerConfig(
                id = "s1",
                baseUrl = "http://10.evil.com/mcp",
                networkResolver = dns,
            ),
            keyVault = vault,
            transport = McpTransport(),
            breaker = breaker,
        )
        val got = withTimeoutOrNull(5_000) { server.listTools() }
        assertNotNull("5s 內未回傳", got)
        assertEquals(McpStatus.PROTOCOL, got!!.status)
        assertEquals(0, vault.reads.get())
        assertEquals(0, breaker.failureCount())
    }

    // ---- 負向：metadata 拒（opt-in 也拒） ----

    @Test fun metadataBlockedEvenWithOptIn() = runBlocking {
        // 政策層：http metadata＋cleartext opt-in 仍拒（零 DNS，字面 IP）。
        try {
            requireMcpNetworkPolicyAllowed(
                "http://169.254.169.254/mcp",
                EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE),
                noDns,
            )
            fail("metadata http must be denied even with opt-in")
        } catch (e: McpNetworkPolicyBlocked) {
            assertEquals(NetworkDenyCode.METADATA_ADDRESS_BLOCKED, e.denyCode)
        }
        // server 層：https metadata＋ALLOW_PRIVATE_DNS 確認仍拒 → PROTOCOL，
        // 零 vault／零熔斷（opt-in 打不開 always-blocked）。
        val base = "https://169.254.169.254/mcp"
        val store = pilotStore()
        store.confirm(mcpEndpointId("s1"), base, NetworkEgressPolicy.ALLOW_PRIVATE_DNS, 0L)
        val vault = CountingVault()
        val breaker = McpCircuitBreaker()
        val server = McpServer(
            config = McpServerConfig(id = "s1", baseUrl = base, networkResolver = noDns),
            keyVault = vault,
            transport = McpTransport(),
            breaker = breaker,
            networkOptInStore = store,
        )
        val got = withTimeoutOrNull(5_000) { server.listTools() }
        assertNotNull("5s 內未回傳", got)
        assertEquals(McpStatus.PROTOCOL, got!!.status)
        assertEquals(0, vault.reads.get())
        assertEquals(0, breaker.failureCount())
        // 直調同拒：typed fatal 帶 METADATA_ADDRESS_BLOCKED。
        try {
            McpTransport().postJson(
                base,
                "sekret",
                "{}",
                McpTimeouts.DEFAULT_MS,
                EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_PRIVATE_DNS),
                noDns,
            )
            fail("metadata https must throw even with opt-in")
        } catch (e: McpNetworkPolicyBlocked) {
            assertEquals(NetworkDenyCode.METADATA_ADDRESS_BLOCKED, e.denyCode)
        }
    }

    // ---- 負向：永遠阻擋類 opt-in 也打不開 ----

    @Test fun alwaysBlockedClassesDeniedDespiteOptIn() {
        val cleartext = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)
        val privateDns = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_PRIVATE_DNS)
        // link-local／multicast／unspecified／CGNAT：opt-in 也打不開
        //（mergeClass 最嚴勝出，deny code 收斂為 PRIVATE_ADDRESS_BLOCKED）。
        for (url in listOf(
            "http://169.254.1.1/mcp",
            "http://224.0.0.1/mcp",
            "http://0.0.0.0/mcp",
            "http://100.64.0.1/mcp",
        )) {
            try {
                requireMcpNetworkPolicyAllowed(url, cleartext, noDns)
                fail("$url must be denied despite opt-in")
            } catch (e: McpNetworkPolicyBlocked) {
                assertEquals("$url deny code", NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, e.denyCode)
            }
        }
        // https CGNAT＋private opt-in 同拒。
        try {
            requireMcpNetworkPolicyAllowed("https://100.64.0.1/mcp", privateDns, noDns)
            fail("cgnat https must be denied despite opt-in")
        } catch (e: McpNetworkPolicyBlocked) {
            assertEquals(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, e.denyCode)
        }
    }

    @Test fun tunnelEmbeddedAddressesAreNotAcceptedAsMcpHttpLoopback() {
        val cleartext = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)
        // 轉換/隧道位址不得因內嵌 127.0.0.1/10.x 而取得 MCP http loopback 或 private 授權。
        for (url in listOf(
            "http://[64:ff9b::127.0.0.1]/mcp",
            "http://[64:ff9b::10.0.0.1]/mcp",
            "http://[2002:7f00:1::1]/mcp",
            "http://[2002:0a00:1::1]/mcp",
            "http://[::ffff:0:127.0.0.1]/mcp",
            "http://[::127.0.0.1]/mcp",
            "http://[2001:0000:c633:6401:0000:ffff:80ff:fffe]/mcp",
        )) {
            try {
                requireMcpNetworkPolicyAllowed(url, cleartext, noDns)
                fail("$url must not be accepted as MCP http loopback/private")
            } catch (e: McpNetworkPolicyBlocked) {
                assertEquals("$url deny code", NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, e.denyCode)
            }
        }
        // DNS 解析到 tunnel-embedded loopback 同樣 fail-closed（無二次授權）。
        val dns = HostResolver { listOf(InetAddress.getByName("2002:7f00:1::1")) }
        try {
            requireMcpNetworkPolicyAllowed("https://mcp-embedded.example/mcp", cleartext, dns)
            fail("dns-resolved tunnel embedded address must be denied")
        } catch (e: McpNetworkPolicyBlocked) {
            assertEquals(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, e.denyCode)
        }
    }

    // ---- 負向：redirect 永不跟隨 ----

    @Test fun redirectToHttpTargetIsNeverFollowed() = runBlocking {
        val origin = MockWebServer()
        val target = MockWebServer()
        try {
            origin.start()
            target.start()
            val store = pilotStore()
            val originBase = origin.url("/").toString()
            store.confirm(
                mcpEndpointId("s1"),
                originBase,
                NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE,
                0L,
            )
            origin.enqueue(
                MockResponse().setResponseCode(307)
                    .addHeader("Location", target.url("/collect").toString()),
            )
            target.enqueue(MockResponse().setResponseCode(200).setBody(goodListBody()))
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = originBase),
                keyVault = CountingVault(),
                transport = McpTransport(),
                breaker = breaker,
                networkOptInStore = store,
            )
            val got = withTimeoutOrNull(5_000) { server.callTool("t", "{}") }
            assertNotNull("5s 內未回傳", got)
            assertEquals("307 是終端回應（不跟隨）", McpStatus.PROTOCOL, got!!.status)
            assertEquals(NetworkDenyCode.REDIRECT_BLOCKED.name, got.auditMap()["denyCode"])
            assertEquals("跳轉目標不得收到任何請求", 0, target.requestCount)
            assertEquals("origin 恰一發（不跟隨、不重送）", 1, origin.requestCount)
            assertEquals(1, breaker.failureCount())
        } finally {
            runCatching { origin.shutdown() }
            runCatching { target.shutdown() }
        }
    }

    // ---- opt-in 綁定變更後舊 confirm 失效 ----

    @Test fun optInBindingInvalidatedOnChange() = runBlocking {
        val web1 = MockWebServer()
        val web2 = MockWebServer()
        try {
            web1.start()
            web2.start()
            val base1 = web1.url("/").toString()
            val base2 = web2.url("/").toString()
            val endpointId = mcpEndpointId("s1")
            val store = pilotStore()
            store.confirm(endpointId, base1, NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE, 0L)
            // store 層：port 變動（base1→base2）即新身份，舊確認失效。
            assertEquals(
                NetworkEgressPolicy.HTTPS_ONLY,
                store.confirmedPolicyFor(endpointId, base2, 0L),
            )
            // store 層：revision 變動即失效。
            assertEquals(
                NetworkEgressPolicy.HTTPS_ONLY,
                store.confirmedPolicyFor(endpointId, base1, 1L),
            )
            // server 層：換 URL（port 不同）→ 政策閘擋下 → PROTOCOL，零 vault。
            val vaultA = CountingVault()
            val serverA = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = base2),
                keyVault = vaultA,
                transport = McpTransport(),
                breaker = McpCircuitBreaker(),
                networkOptInStore = store,
            )
            val deniedA = withTimeoutOrNull(5_000) { serverA.listTools() }
            assertNotNull(deniedA)
            assertEquals(McpStatus.PROTOCOL, deniedA!!.status)
            assertEquals(0, vaultA.reads.get())
            assertEquals(0, web2.requestCount)
            // server 層：revision 變動 → 同拒。
            val vaultB = CountingVault()
            val serverB = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = base1, configRevision = 1L),
                keyVault = vaultB,
                transport = McpTransport(),
                breaker = McpCircuitBreaker(),
                networkOptInStore = store,
            )
            val deniedB = withTimeoutOrNull(5_000) { serverB.listTools() }
            assertNotNull(deniedB)
            assertEquals(McpStatus.PROTOCOL, deniedB!!.status)
            assertEquals(0, vaultB.reads.get())
            assertEquals(0, web1.requestCount)
            // token 綁定全部欄位：任一變動即不同 token。
            val t1 = NetworkOptInStore.confirmationTokenFor(
                endpointId, base1, NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE, 0L,
            )
            val t2 = NetworkOptInStore.confirmationTokenFor(
                endpointId, base2, NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE, 0L,
            )
            assertTrue("baseUrl 變動 token 必須不同", t1 != t2)
        } finally {
            runCatching { web1.shutdown() }
            runCatching { web2.shutdown() }
        }
    }

    // ---- play 姿態：mcp: 確認亦不得升級（對照 chat playStaysHttpsOnly） ----

    @Test fun mcpPlayPostureRejectsTierUpgrade() {
        // 預設建構即 play 姿態（allowTierUpgrade=false）：mcp: 命名空間的
        // 非 HTTPS_ONLY 確認一律拒收（fail-closed，不記錄），與 chat
        // `NetworkPolicyS2Test.playStaysHttpsOnly／defaultStoreRejectsTierUpgrade`
        // 同姿態。
        val playStore = NetworkOptInStore()
        val endpointId = mcpEndpointId("s1")
        val base = "https://mcp.example.com/mcp"
        for (tier in listOf(
            NetworkEgressPolicy.ALLOW_PRIVATE_DNS,
            NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE,
        )) {
            try {
                playStore.confirm(endpointId, base, tier, 0L)
                fail("play (mcp:) store must reject $tier")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message.orEmpty().contains("NETWORK_OPTIN_TIER_NOT_ALLOWED"))
            }
        }
        // 拒收不留痕：查詢仍是預設 deny-all。
        assertEquals(
            NetworkEgressPolicy.HTTPS_ONLY,
            playStore.confirmedPolicyFor(endpointId, base, 0L),
        )
        // HTTPS_ONLY 確認（降級／預設記錄）在 play store 仍可。
        playStore.confirm(endpointId, base, NetworkEgressPolicy.HTTPS_ONLY, 0L)
        assertEquals(
            NetworkEgressPolicy.HTTPS_ONLY,
            playStore.confirmedPolicyFor(endpointId, base, 0L),
        )
    }

    // ---- 同一 McpServer 物件：降級／invalidate 下一次呼叫即生效 ----

    @Test fun sameServerSeesDowngradeAndInvalidateImmediately() = runBlocking {
        val web = MockWebServer()
        try {
            web.start()
            val base = web.url("/").toString()
            val endpointId = mcpEndpointId("s1")
            val store = pilotStore()
            store.confirm(endpointId, base, NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE, 0L)
            val vault = CountingVault()
            val breaker = McpCircuitBreaker()
            // 同一物件重複使用：後續降級／invalidate 必須下一次呼叫即生效，不需換物件
            //（effectiveOptIn 每呼叫重解 store.optInFor，活躍 session 生即生效）。
            val server = McpServer(
                config = McpServerConfig(id = "s1", baseUrl = base),
                keyVault = vault,
                transport = McpTransport(),
                breaker = breaker,
                networkOptInStore = store,
            )
            web.enqueue(MockResponse().setResponseCode(200).setBody(goodListBody()))
            val ok1 = withTimeoutOrNull(5_000) { server.listTools() }
            assertNotNull(ok1)
            assertEquals(McpStatus.OK, ok1!!.status)
            assertEquals(1, web.requestCount)
            assertEquals(1, vault.reads.get())
            assertEquals(0, breaker.failureCount())
            // 降級回 HTTPS_ONLY：下一次呼叫即被政策閘擋下（PROTOCOL、零 vault／零 HTTP／零熔斷）。
            store.confirm(endpointId, base, NetworkEgressPolicy.HTTPS_ONLY, 0L)
            val denied = withTimeoutOrNull(5_000) { server.listTools() }
            assertNotNull(denied)
            assertEquals(McpStatus.PROTOCOL, denied!!.status)
            assertEquals("downgrade sends nothing", 1, web.requestCount)
            assertEquals("credential must not be fetched on deny", 1, vault.reads.get())
            assertEquals(0, breaker.failureCount())
            // 重新升級確認：同一物件恢復放行。
            store.confirm(endpointId, base, NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE, 0L)
            web.enqueue(MockResponse().setResponseCode(200).setBody(goodListBody()))
            val ok2 = withTimeoutOrNull(5_000) { server.listTools() }
            assertNotNull(ok2)
            assertEquals(McpStatus.OK, ok2!!.status)
            assertEquals(2, web.requestCount)
            assertEquals(2, vault.reads.get())
            assertEquals(0, breaker.failureCount())
            // invalidate：下一次呼叫同樣即時失效（同一物件，不需重建）。
            store.invalidate(endpointId)
            val denied2 = withTimeoutOrNull(5_000) { server.listTools() }
            assertNotNull(denied2)
            assertEquals(McpStatus.PROTOCOL, denied2!!.status)
            assertEquals("invalidate sends nothing", 2, web.requestCount)
            assertEquals(2, vault.reads.get())
            assertEquals(0, breaker.failureCount())
        } finally {
            web.shutdown()
        }
    }

    // ---- 負向補強（#135）：IPv6 永阻段／userinfo／rebind＋稽核 denyCode ----

    @Test fun ipv6AlwaysBlockedClassesDenied() {
        val cleartext = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)
        val privateDns = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_PRIVATE_DNS)
        // link-local（fe80::1）／multicast（ff02::1）／unspecified（::）：
        // 字面 IPv6，opt-in 也打不開，零 DNS。
        for (url in listOf("http://[fe80::1]/mcp", "http://[ff02::1]/mcp", "http://[::]/mcp")) {
            try {
                requireMcpNetworkPolicyAllowed(url, cleartext, noDns)
                fail("$url must be denied despite opt-in")
            } catch (e: McpNetworkPolicyBlocked) {
                assertEquals(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, e.denyCode)
            }
        }
        // https＋較弱 opt-in 同樣永遠阻擋。
        try {
            requireMcpNetworkPolicyAllowed("https://[fe80::1]/mcp", privateDns, noDns)
            fail("link-local https must be denied despite opt-in")
        } catch (e: McpNetworkPolicyBlocked) {
            assertEquals(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, e.denyCode)
        }
    }

    @Test fun userinfoBlockedAtGate() {
        // userinfo 永遠阻擋（先於 DNS）：必敗 resolver 證零解析。
        val mustNotResolve: HostResolver = HostResolver {
            throw UnknownHostException("userinfo must not trigger DNS")
        }
        for (url in listOf(
            "http://user@localhost/mcp",
            "https://user:pass@example.com/mcp",
        )) {
            try {
                requireMcpNetworkPolicyAllowed(url, EndpointNetworkOptIn(), mustNotResolve)
                fail("$url must be denied as userinfo")
            } catch (e: McpNetworkPolicyBlocked) {
                assertEquals(NetworkDenyCode.USERINFO_BLOCKED, e.denyCode)
            }
        }
    }

    @Test fun dnsRebindPinnedDnsUnit() {
        // 純 [PinnedNetworkDns] 單元（未經 transport 接線；接線見
        // `reboundDelegateAfterPreverifyStillConnectsPinnedAddress`）：
        // fake resolver 首回公網、次回私有：pin 釘住首回（連線不受污染），
        // hasRebind 偵測到變化（呼叫方須重決策），未釘住 host 嚴格拒收
        //（DNS_PIN_MISS，不洩漏、不建連）。
        val first = listOf(addr("203.0.113.7"))
        val rebound = listOf(addr("10.0.0.1"))
        var current = first
        val mutableDelegate = HostResolver { _: String -> current }
        val pinned = PinnedNetworkDns.forHost("example.com", first, mutableDelegate)
        current = rebound
        assertEquals(first, pinned.lookup("example.com"))
        assertEquals(true, pinned.hasRebind("example.com"))
        try {
            pinned.lookup("unpinned.example")
            fail("unpinned host must throw")
        } catch (e: UnknownHostException) {
            assertTrue(e.message.orEmpty().contains("DNS_PIN_MISS"))
        }
    }

    @Test fun reboundDelegateAfterPreverifyStillConnectsPinnedAddress() = runBlocking {
        // 接線測試（非 PinnedNetworkDns 單元）：MockWebServer 綁定 127.0.0.2（OS 預設 localhost
        // 會解成 127.0.0.1 連不上），並注入會拋 AssertionError 的 client.dns + EventListener，
        // 證明 McpServer→McpTransport 經 PinnedNetworkDns 實際覆寫 socket 連線位址至 127.0.0.2、
        // 且 delegate 在預驗後翻轉至 10.0.0.1 時仍單次解析並零熔斷成功。
        val web = MockWebServer()
        try {
            web.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2)), 0)
            web.enqueue(MockResponse().setResponseCode(200).setBody(goodListBody()))
            val base = "http://localhost:${web.port}/mcp"
            var resolves = 0
            var first: List<InetAddress>? = null
            val skewDelegate = HostResolver { _ ->
                resolves++
                if (resolves == 1) {
                    listOf(addr("127.0.0.2")).also { first = it }
                } else {
                    listOf(addr("10.0.0.1"))
                }
            }
            val seenDnsEnd = AtomicReference<List<InetAddress>>()
            val seenConnectAddr = AtomicReference<InetAddress>()
            val pinnedCheckClient = McpTransport.defaultHttp().newBuilder()
                .dns { throw AssertionError("client dns must be overridden by PinnedNetworkDns") }
                .eventListener(object : EventListener() {
                    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
                        seenDnsEnd.set(inetAddressList)
                    }

                    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                        seenConnectAddr.set(inetSocketAddress.address)
                    }
                })
                .build()
            val vault = CountingVault()
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(
                    id = "s1",
                    baseUrl = base,
                    networkOptIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE),
                    networkResolver = skewDelegate,
                ),
                keyVault = vault,
                transport = McpTransport(pinnedCheckClient),
                breaker = breaker,
            )
            val got = withTimeoutOrNull(5_000) { server.listTools() }
            assertNotNull("5s 內未回傳", got)
            assertEquals("skew 下仍須連首回釘住地址 127.0.0.2 成功", McpStatus.OK, got!!.status)
            assertEquals("恰一發（建連走 pin，未跟隨／重送）", 1, web.requestCount)
            assertEquals("單次解析：transport 不得二次查 DNS", 1, resolves)
            assertEquals(listOf(addr("127.0.0.2")), seenDnsEnd.get())
            assertEquals(addr("127.0.0.2"), seenConnectAddr.get())
            assertEquals("阻擋未觸發即無 denyCode", null, got.auditMap()["denyCode"])
            assertEquals("成功不得計熔斷", 0, breaker.failureCount())
            // 同狀態平行 pin 證 rebind 確實發生（delegate 已翻轉）：建連成功與
            // 此處 hasRebind==true 並存，即「釘住首回、偵測到變化」。
            val parallel = PinnedNetworkDns.forHost("localhost", first!!, skewDelegate)
            assertEquals(true, parallel.hasRebind("localhost"))
        } finally {
            runCatching { web.shutdown() }
        }
    }

    @Test fun policyBlockedRecordsDenyCodeInAudit() = runBlocking {
        // 政策阻擋收斂為 PROTOCOL 時，denyCode 記入稽核（靜態碼名；
        // 位址／URL 永不進稽核面），且零 vault／零 HTTP／零熔斷。
        val vault = CountingVault()
        val breaker = McpCircuitBreaker()
        // https 公網名解析到私有：預設 deny-all → PRIVATE_ADDRESS_BLOCKED。
        val dns = fakeDns("example.com" to listOf("10.1.2.3"))
        val server = McpServer(
            config = McpServerConfig(
                id = "s1",
                baseUrl = "https://example.com/mcp",
                networkResolver = dns,
            ),
            keyVault = vault,
            transport = McpTransport(),
            breaker = breaker,
        )
        val got = withTimeoutOrNull(5_000) { server.listTools() }
        assertNotNull("5s 內未回傳", got)
        assertEquals(McpStatus.PROTOCOL, got!!.status)
        assertEquals(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED.name, got.auditMap()["denyCode"])
        assertFalse("稽核不得含位址", got.auditMap().toString().contains("10.1.2.3"))
        assertFalse("稽核不得含 URL", got.auditMap().toString().contains("example.com/mcp"))
        assertEquals(0, vault.reads.get())
        assertEquals(0, breaker.failureCount())
        // callTool 同政策。
        val callGot = withTimeoutOrNull(5_000) { server.callTool("t", "{}") }
        assertNotNull(callGot)
        assertEquals(McpStatus.PROTOCOL, callGot!!.status)
        assertEquals(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED.name, callGot.auditMap()["denyCode"])
        // 非政策 PROTOCOL（形狀閘：userinfo 由 requireSecureEndpoint 先擋）
        // 不帶 denyCode——維持分層語義。
        val shapeServer = McpServer(
            config = McpServerConfig(id = "s1", baseUrl = "http://user@localhost:9/mcp"),
            keyVault = vault,
            transport = McpTransport(),
            breaker = McpCircuitBreaker(),
        )
        val shapeGot = withTimeoutOrNull(5_000) { shapeServer.listTools() }
        assertNotNull(shapeGot)
        assertEquals(McpStatus.PROTOCOL, shapeGot!!.status)
        assertEquals(null, shapeGot.auditMap()["denyCode"])
    }

    // ---- Phase 2 (B-01 / B-02 / W-01 / W-02 / W-03 / UnknownServiceException) 強化測試 ----

    @Test fun preflightTokenCanonicalUrlMatchesMixedCaseAndDefaultPortWithZeroSecondDns() = runBlocking {
        // 1) HTTPS 混合大小寫＋顯式預設埠（https://EXAMPLE.COM:443/mcp）：與預驗 canonicalUrl 一致，
        //    postJsonWithPreverified 零二次 DNS 沿用密封 McpPreflightToken。
        val dnsCalls = AtomicInteger(0)
        val countingResolver = HostResolver { host ->
            dnsCalls.incrementAndGet()
            assertEquals("example.com", host)
            listOf(addr("203.0.113.10"))
        }
        val interceptedUrls = mutableListOf<String>()
        val httpsTransport = McpTransport(
            interceptorClient { req ->
                interceptedUrls.add(req.url.toString())
                mock200JsonResponse(req, goodListBody())
            },
        )
        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        val httpsOutcome = httpsTransport.preflightMcpNetworkPolicy(
            url = "https://example.com/mcp",
            optIn = EndpointNetworkOptIn(),
            resolver = countingResolver,
            deadlineNs = deadlineNs,
        )
        assertTrue(httpsOutcome is McpPreflightOutcome.Allowed)
        val httpsToken = (httpsOutcome as McpPreflightOutcome.Allowed).token
        assertEquals(1, dnsCalls.get())
        assertEquals(listOf(addr("203.0.113.10")), httpsToken.eval.resolvedAddresses)

        val httpsResp = httpsTransport.postJsonWithPreverified(
            baseUrl = "https://EXAMPLE.COM:443/mcp",
            bearer = "sekret",
            bodyJson = "{}",
            deadlineNs = deadlineNs,
            optIn = EndpointNetworkOptIn(),
            resolver = countingResolver,
            preverified = httpsToken,
        )
        assertNotNull(httpsResp)
        assertEquals(200, httpsResp!!.first)
        assertEquals("canonicalUrl 相符不得觸發第二次 DNS", 1, dnsCalls.get())
        assertEquals(listOf("https://example.com/mcp"), interceptedUrls)

        // 2) 真實 MockWebServer + EventListener.dnsEnd 驗證 PinnedNetworkDns 實際釘住位址且零二次 DNS。
        val web = MockWebServer()
        try {
            web.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
            web.enqueue(MockResponse().setResponseCode(200).setBody(goodListBody()))
            val seenPinnedAddrs = AtomicReference<List<InetAddress>>()
            val client = McpTransport.defaultHttp().newBuilder()
                .eventListener(object : EventListener() {
                    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
                        seenPinnedAddrs.set(inetAddressList)
                    }
                })
                .build()
            val localTransport = McpTransport(client)
            val localDnsCalls = AtomicInteger(0)
            val localResolver = HostResolver { host ->
                localDnsCalls.incrementAndGet()
                assertEquals("localhost", host)
                listOf(addr("127.0.0.1"))
            }
            val loopbackOptIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)
            val localDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            val localOutcome = localTransport.preflightMcpNetworkPolicy(
                url = "http://localhost:${web.port}/mcp",
                optIn = loopbackOptIn,
                resolver = localResolver,
                deadlineNs = localDeadline,
            )
            val localToken = (localOutcome as McpPreflightOutcome.Allowed).token
            val localResp = localTransport.postJsonWithPreverified(
                baseUrl = "http://LOCALHOST:${web.port}/mcp",
                bearer = "sekret",
                bodyJson = "{}",
                deadlineNs = localDeadline,
                optIn = loopbackOptIn,
                resolver = localResolver,
                preverified = localToken,
            )
            assertNotNull(localResp)
            assertEquals(200, localResp!!.first)
            assertEquals(1, web.requestCount)
            assertEquals("混合大小寫 localhost 亦不得二次 DNS", 1, localDnsCalls.get())
            assertEquals(listOf(addr("127.0.0.1")), seenPinnedAddrs.get())
        } finally {
            runCatching { web.shutdown() }
        }

        // 3) 防禦性複製：自訂 resolver 回傳可變 List 後立刻竄改，不得污染已鑄造 token 或 gate 結果。
        val mutableAddrs = mutableListOf(addr("203.0.113.10"))
        val mutatingResolver = HostResolver { mutableAddrs }
        val gateEval = requireMcpNetworkPolicyAllowed("https://example.com/mcp", EndpointNetworkOptIn(), mutatingResolver)
        val tokenOutcome = httpsTransport.preflightMcpNetworkPolicy(
            url = "https://example.com/mcp",
            optIn = EndpointNetworkOptIn(),
            resolver = mutatingResolver,
            deadlineNs = deadlineNs,
        ) as McpPreflightOutcome.Allowed
        mutableAddrs.clear()
        mutableAddrs.add(addr("10.0.0.1"))
        assertEquals(listOf(addr("203.0.113.10")), gateEval.resolvedAddresses)
        assertEquals(listOf(addr("203.0.113.10")), tokenOutcome.token.eval.resolvedAddresses)
    }

    @Test fun postJsonWithPreverifiedLiteralOrResolverMismatchFallsBackToRealResolver() = runBlocking {
        // 1) 字面 IP 不符（127.0.0.1 token vs ::1 目標；McpWire.isLoopbackHost 僅白名單 localhost/127.0.0.1/::1，
        //    故跨字面回環用 127.0.0.1 vs ::1，另加 https 跨字面 203.0.113.10 vs 203.0.113.20.）：
        //    不得沿用舊 token，必須連往真實目標（0 DNS）。
        val webA = MockWebServer()
        val webB = MockWebServer()
        try {
            webA.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
            webB.start(InetAddress.getByName("::1"), 0)
            webB.enqueue(MockResponse().setResponseCode(200).setBody(goodListBody()))
            val loopbackOptIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)
            val transport = McpTransport()
            val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            val tokenForA = (
                transport.preflightMcpNetworkPolicy(
                    url = "http://127.0.0.1:${webA.port}/mcp",
                    optIn = loopbackOptIn,
                    resolver = noDns,
                    deadlineNs = deadlineNs,
                ) as McpPreflightOutcome.Allowed
            ).token

            val resp = transport.postJsonWithPreverified(
                baseUrl = "http://[::1]:${webB.port}/mcp",
                bearer = "sekret",
                bodyJson = "{}",
                deadlineNs = deadlineNs,
                optIn = loopbackOptIn,
                resolver = noDns,
                preverified = tokenForA,
            )
            assertNotNull(resp)
            assertEquals(200, resp!!.first)
            assertEquals("舊 token 端點 127.0.0.1 不得收到請求", 0, webA.requestCount)
            assertEquals("必須連往實際目標字面端點 ::1", 1, webB.requestCount)
        } finally {
            runCatching { webA.shutdown() }
            runCatching { webB.shutdown() }
        }

        // 1b) HTTPS 跨字面 IP mismatch（203.0.113.10 token vs 203.0.113.20）：不得沿用舊 token，connectStart 必須指向 203.0.113.20。
        val seenLiteralConnectAddr = AtomicReference<InetAddress>()
        val stopSocketFactory = object : javax.net.SocketFactory() {
            private fun stubSocket(): java.net.Socket = object : java.net.Socket() {
                override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) {
                    throw IOException("stop-before-network")
                }
            }
            override fun createSocket(): java.net.Socket = stubSocket()
            override fun createSocket(host: String?, port: Int): java.net.Socket = stubSocket()
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): java.net.Socket = stubSocket()
            override fun createSocket(host: InetAddress?, port: Int): java.net.Socket = stubSocket()
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): java.net.Socket = stubSocket()
        }
        val literalHttpsTransport = McpTransport(
            McpTransport.defaultHttp().newBuilder()
                .socketFactory(stopSocketFactory)
                .eventListener(object : EventListener() {
                    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                        seenLiteralConnectAddr.set(inetSocketAddress.address)
                    }
                })
                .build(),
        )
        val httpsLitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        val httpsLitToken = (
            literalHttpsTransport.preflightMcpNetworkPolicy(
                url = "https://203.0.113.10/mcp",
                optIn = EndpointNetworkOptIn(),
                resolver = noDns,
                deadlineNs = httpsLitDeadline,
            ) as McpPreflightOutcome.Allowed
        ).token
        try {
            literalHttpsTransport.postJsonWithPreverified(
                baseUrl = "https://203.0.113.20/mcp",
                bearer = "sekret",
                bodyJson = "{}",
                deadlineNs = httpsLitDeadline,
                optIn = EndpointNetworkOptIn(),
                resolver = noDns,
                preverified = httpsLitToken,
            )
            fail("stub socket must abort before network")
        } catch (e: IOException) {
            assertEquals("stop-before-network", e.message)
        }
        assertEquals(addr("203.0.113.20"), seenLiteralConnectAddr.get())

        // 1c) 尾點字面 IP（https://203.0.113.20./mcp）經 trimEnd('.') 正規化後仍走單次快速重用（0 DNS）。
        val trailingDotTransport = McpTransport(
            interceptorClient { req -> mock200JsonResponse(req, goodListBody()) },
        )
        val trailingToken = (
            trailingDotTransport.preflightMcpNetworkPolicy(
                url = "https://203.0.113.20./mcp",
                optIn = EndpointNetworkOptIn(),
                resolver = noDns,
                deadlineNs = httpsLitDeadline,
            ) as McpPreflightOutcome.Allowed
        ).token
        assertEquals(
            200,
            trailingDotTransport.postJsonWithPreverified(
                baseUrl = "https://203.0.113.20./mcp",
                bearer = "sekret",
                bodyJson = "{}",
                deadlineNs = httpsLitDeadline,
                optIn = EndpointNetworkOptIn(),
                resolver = noDns,
                preverified = trailingToken,
            )?.first,
        )

        // 1c-2) 尾點主機名（https://example.com./mcp）：preflight 以 counting resolver 解析一次；
        // 第一次 post 走快速重用（token 單次消耗，**不得**二次 DNS）；第二次沿用同一 token
        // 必須回退重驗（再 DNS 一次）——證明快速路徑確實消耗密封權杖，而非可重複沿用。
        val trailingHostDnsCalls = AtomicInteger(0)
        val trailingHostDns = HostResolver {
            trailingHostDnsCalls.incrementAndGet()
            listOf(addr("203.0.113.20"))
        }
        val trailingHostTransport = McpTransport(
            interceptorClient { req -> mock200JsonResponse(req, goodListBody()) },
        )
        val trailingHostToken = (
            trailingHostTransport.preflightMcpNetworkPolicy(
                url = "https://example.com./mcp",
                optIn = EndpointNetworkOptIn(),
                resolver = trailingHostDns,
                deadlineNs = httpsLitDeadline,
            ) as McpPreflightOutcome.Allowed
        ).token
        assertEquals("preflight 應解析一次", 1, trailingHostDnsCalls.get())
        assertEquals(
            "快速重用應回 200",
            200,
            trailingHostTransport.postJsonWithPreverified(
                baseUrl = "https://example.com./mcp",
                bearer = "sekret",
                bodyJson = "{}",
                deadlineNs = httpsLitDeadline,
                optIn = EndpointNetworkOptIn(),
                resolver = trailingHostDns,
                preverified = trailingHostToken,
            )?.first,
        )
        assertEquals("快速重用不得二次 DNS", 1, trailingHostDnsCalls.get())
        assertEquals(
            "已消耗 token 第二次仍應回 200（回退重驗）",
            200,
            trailingHostTransport.postJsonWithPreverified(
                baseUrl = "https://example.com./mcp",
                bearer = "sekret",
                bodyJson = "{}",
                deadlineNs = httpsLitDeadline,
                optIn = EndpointNetworkOptIn(),
                resolver = trailingHostDns,
                preverified = trailingHostToken,
            )?.first,
        )
        assertEquals("已消耗 token 第二次必須回退重驗（再 DNS 一次）", 2, trailingHostDnsCalls.get())

        // 2) 相同 URL 但不同 HostResolver 實例（resolver !== preverified.resolver）：必須使用新 resolver 重驗。
        val dnsACalls = AtomicInteger(0)
        val dnsA = HostResolver {
            dnsACalls.incrementAndGet()
            listOf(addr("203.0.113.10"))
        }
        val dnsBCalls = AtomicInteger(0)
        val dnsB = HostResolver {
            dnsBCalls.incrementAndGet()
            listOf(addr("203.0.113.20"))
        }
        val transport = McpTransport(
            interceptorClient { req -> mock200JsonResponse(req, goodListBody()) },
        )
        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        val tokenA = (
            transport.preflightMcpNetworkPolicy(
                url = "https://example.com/mcp",
                optIn = EndpointNetworkOptIn(),
                resolver = dnsA,
                deadlineNs = deadlineNs,
            ) as McpPreflightOutcome.Allowed
        ).token
        val resp2 = transport.postJsonWithPreverified(
            baseUrl = "https://example.com/mcp",
            bearer = "sekret",
            bodyJson = "{}",
            deadlineNs = deadlineNs,
            optIn = EndpointNetworkOptIn(),
            resolver = dnsB,
            preverified = tokenA,
        )
        assertNotNull(resp2)
        assertEquals(200, resp2!!.first)
        assertEquals(1, dnsACalls.get())
        assertEquals("resolver 實例不同必須重新解析新 resolver", 1, dnsBCalls.get())

        // 3) 單次使用（McpPreflightToken.consume）：同一 token＋同一 resolver＋同一 URL 連打兩次，
        //    首發零二次 DNS（dnsCalls=1），次發已消耗必須回落 preflightMcpNetworkPolicy（dnsCalls=2）。
        val singleCalls = AtomicInteger(0)
        val singleResolver = HostResolver {
            singleCalls.incrementAndGet()
            listOf(addr("203.0.113.30"))
        }
        val singleToken = (
            transport.preflightMcpNetworkPolicy(
                url = "https://single.example.com/mcp",
                optIn = EndpointNetworkOptIn(),
                resolver = singleResolver,
                deadlineNs = deadlineNs,
            ) as McpPreflightOutcome.Allowed
        ).token
        assertEquals(1, singleCalls.get())
        val firstUse = transport.postJsonWithPreverified(
            baseUrl = "https://single.example.com/mcp",
            bearer = "sekret",
            bodyJson = "{}",
            deadlineNs = deadlineNs,
            optIn = EndpointNetworkOptIn(),
            resolver = singleResolver,
            preverified = singleToken,
        )
        assertEquals(200, firstUse?.first)
        assertEquals("首次消耗密封權杖不得二次查 DNS", 1, singleCalls.get())
        val secondUse = transport.postJsonWithPreverified(
            baseUrl = "https://single.example.com/mcp",
            bearer = "sekret",
            bodyJson = "{}",
            deadlineNs = deadlineNs,
            optIn = EndpointNetworkOptIn(),
            resolver = singleResolver,
            preverified = singleToken,
        )
        assertEquals(200, secondUse?.first)
        assertEquals("已消耗權杖跨呼叫重用必須回落重查 DNS", 2, singleCalls.get())
    }

    @Test fun pinnedNetworkDnsEnforcesNonSystemLoopbackPinAcrossDirectPostJsonAndMismatchFallback() = runBlocking {
        // High #2 非空洞 socket pinning 驗證：MockWebServer 綁定 127.0.0.2（OS 預設 localhost 僅解 127.0.0.1），
        // 注入必敗 client.dns＋EventListener（dnsEnd/connectStart），證實直接 postJson 與
        // postJsonWithPreverified mismatch fallback 皆實際將 socket 連線釘往 127.0.0.2。
        val web = MockWebServer()
        try {
            web.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2)), 0)
            web.enqueue(MockResponse().setResponseCode(200).setBody(goodListBody()))
            web.enqueue(MockResponse().setResponseCode(200).setBody(goodListBody()))

            val seenDnsEnds = mutableListOf<List<InetAddress>>()
            val seenConnectAddrs = mutableListOf<InetAddress>()
            val pinnedCheckClient = McpTransport.defaultHttp().newBuilder()
                .dns { throw AssertionError("client dns must be overridden by PinnedNetworkDns") }
                .eventListener(object : EventListener() {
                    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
                        seenDnsEnds.add(inetAddressList)
                    }

                    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                        seenConnectAddrs.add(inetSocketAddress.address)
                    }
                })
                .build()
            val transport = McpTransport(pinnedCheckClient)
            val cleartext = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)
            val resolver127_0_0_2 = HostResolver { host ->
                assertEquals("localhost", host)
                listOf(addr("127.0.0.2"))
            }

            // 1) 直調 McpTransport.postJson
            val directResp = transport.postJson(
                baseUrl = "http://localhost:${web.port}/mcp",
                bearer = "sekret",
                bodyJson = "{}",
                timeoutMs = McpTimeouts.DEFAULT_MS,
                optIn = cleartext,
                resolver = resolver127_0_0_2,
            )
            assertNotNull(directResp)
            assertEquals(200, directResp!!.first)

            // 2) postJsonWithPreverified URL mismatch fallback（預驗 127.0.0.1 /other，實送 127.0.0.2 /mcp）
            val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            val staleToken = (
                transport.preflightMcpNetworkPolicy(
                    url = "http://localhost:${web.port}/other",
                    optIn = cleartext,
                    resolver = fakeDns("localhost" to listOf("127.0.0.1")),
                    deadlineNs = deadlineNs,
                ) as McpPreflightOutcome.Allowed
            ).token
            val fallbackResp = transport.postJsonWithPreverified(
                baseUrl = "http://localhost:${web.port}/mcp",
                bearer = "sekret",
                bodyJson = "{}",
                deadlineNs = deadlineNs,
                optIn = cleartext,
                resolver = resolver127_0_0_2,
                preverified = staleToken,
            )
            assertNotNull(fallbackResp)
            assertEquals(200, fallbackResp!!.first)

            assertEquals(2, web.requestCount)
            assertEquals(listOf(listOf(addr("127.0.0.2")), listOf(addr("127.0.0.2"))), seenDnsEnds)
            assertEquals(listOf(addr("127.0.0.2"), addr("127.0.0.2")), seenConnectAddrs)
        } finally {
            runCatching { web.shutdown() }
        }
    }

    @Test fun postJsonWithPreverifiedDowngradedOptInBlocksWithZeroExtraDns() = runBlocking {
        // 以合法 ALLOW_PRIVATE_DNS 取得密封 McpPreflightToken 後，於降級的 HTTPS_ONLY 下呼叫
        // postJsonWithPreverified：必須立即阻擋（PRIVATE_ADDRESS_BLOCKED）、零二次 DNS、零 HTTP。
        val dnsCalls = AtomicInteger(0)
        val resolver = HostResolver {
            dnsCalls.incrementAndGet()
            listOf(addr("10.0.0.1"))
        }
        val httpCalls = AtomicInteger(0)
        val transport = McpTransport(
            interceptorClient { req ->
                httpCalls.incrementAndGet()
                mock200JsonResponse(req, goodListBody())
            },
        )
        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        val token = (
            transport.preflightMcpNetworkPolicy(
                url = "https://internal.example.com/mcp",
                optIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_PRIVATE_DNS),
                resolver = resolver,
                deadlineNs = deadlineNs,
            ) as McpPreflightOutcome.Allowed
        ).token
        assertEquals(1, dnsCalls.get())

        try {
            transport.postJsonWithPreverified(
                baseUrl = "https://internal.example.com/mcp",
                bearer = "sekret",
                bodyJson = "{}",
                deadlineNs = deadlineNs,
                optIn = EndpointNetworkOptIn(NetworkEgressPolicy.HTTPS_ONLY),
                resolver = resolver,
                preverified = token,
            )
            fail("降級至 HTTPS_ONLY 後必須阻擋私有 IP")
        } catch (e: McpNetworkPolicyBlocked) {
            assertEquals(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, e.denyCode)
        }
        assertEquals("opt-in 降級阻擋不得重查 DNS", 1, dnsCalls.get())
        assertEquals("opt-in 降級阻擋不得發送 HTTP", 0, httpCalls.get())

        // fallback Blocked（mismatch 到解析為私有 IP 的目標）亦立即拋 McpNetworkPolicyBlocked、0 HTTP。
        val fallbackResolver = HostResolver { host ->
            if (host == "public.example.com") listOf(addr("203.0.113.10")) else listOf(addr("10.0.0.9"))
        }
        val pubToken = (
            transport.preflightMcpNetworkPolicy(
                url = "https://public.example.com/mcp",
                optIn = EndpointNetworkOptIn(),
                resolver = fallbackResolver,
                deadlineNs = deadlineNs,
            ) as McpPreflightOutcome.Allowed
        ).token
        try {
            transport.postJsonWithPreverified(
                baseUrl = "https://private.example.com/mcp",
                bearer = "sekret",
                bodyJson = "{}",
                deadlineNs = deadlineNs,
                optIn = EndpointNetworkOptIn(),
                resolver = fallbackResolver,
                preverified = pubToken,
            )
            fail("fallback mismatch 解析到私有 IP 必須阻擋")
        } catch (e: McpNetworkPolicyBlocked) {
            assertEquals(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, e.denyCode)
        }
        assertEquals("fallback Blocked 不得發送 HTTP", 0, httpCalls.get())

        // 縱深防線：postJsonWithPreverified 直調亦拒非回環明文／userinfo／非法 Bearer（0 DNS、0 HTTP）。
        val noCallResolver = HostResolver { throw AssertionError("bad endpoint/bearer must fail before DNS") }
        for (badUrl in listOf("http://example.com/mcp", "http://user@localhost/mcp")) {
            try {
                transport.postJsonWithPreverified(
                    baseUrl = badUrl,
                    bearer = "sekret",
                    bodyJson = "{}",
                    deadlineNs = deadlineNs,
                    optIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE),
                    resolver = noCallResolver,
                    preverified = pubToken,
                )
                fail("postJsonWithPreverified 必須拒 $badUrl")
            } catch (_: McpWire.McpProtocol) {
            }
        }
        for (badBearer in listOf("", "tok\nen", "tok\u00e9")) {
            try {
                transport.postJsonWithPreverified(
                    baseUrl = "https://public.example.com/mcp",
                    bearer = badBearer,
                    bodyJson = "{}",
                    deadlineNs = deadlineNs,
                    optIn = EndpointNetworkOptIn(),
                    resolver = noCallResolver,
                    preverified = pubToken,
                )
                fail("postJsonWithPreverified 必須拒非法 bearer")
            } catch (e: McpWire.McpProtocol) {
                assertEquals("mcp: bad bearer", e.message)
            }
        }
        assertEquals(0, httpCalls.get())
    }

    @Test fun b02NoPublicOrInternalTransportMethodAcceptsCallerNetworkEvaluationAndDirectPostJsonUsesResolver() = runBlocking {
        // B-02 API/反射釘：McpTransport／McpTransportKt／McpServer 不得存在任何接收 NetworkEvaluation 的公開或內部方法／建構子；
        // McpPreflightToken 不得有公開建構子或接受 NetworkEvaluation 的工廠。
        for (ctor in McpTransport::class.java.constructors + McpTransport::class.java.declaredConstructors) {
            assertFalse(
                "McpTransport 建構子不得接收 NetworkEvaluation",
                NetworkEvaluation::class.java in ctor.parameterTypes,
            )
        }
        val transportMethods = (
            McpTransport::class.java.methods.toList() +
                McpTransport::class.java.declaredMethods.toList() +
                McpTransport.Companion::class.java.declaredMethods.toList() +
                Class.forName("dev.librepocket.mcp.McpTransportKt").declaredMethods.toList()
            ).distinct()
        for (m in transportMethods) {
            assertFalse(
                "McpTransport/McpTransportKt.${m.name} 不得接收 NetworkEvaluation",
                NetworkEvaluation::class.java in m.parameterTypes,
            )
        }
        for (ctor in McpServer::class.java.constructors + McpServer::class.java.declaredConstructors) {
            assertFalse(
                "McpServer 建構子不得接收 NetworkEvaluation",
                NetworkEvaluation::class.java in ctor.parameterTypes,
            )
        }
        val serverMethods = (
            McpServer::class.java.methods.toList() +
                McpServer::class.java.declaredMethods.toList() +
                McpServer.Companion::class.java.declaredMethods.toList()
            ).distinct()
        for (m in serverMethods) {
            assertFalse(
                "McpServer.${m.name} 不得接收 NetworkEvaluation",
                NetworkEvaluation::class.java in m.parameterTypes,
            )
        }
        val nonSyntheticPublicCtors = McpPreflightToken::class.java.constructors.filterNot { it.isSynthetic }
        assertEquals("McpPreflightToken 不得有非合成公開建構子", 0, nonSyntheticPublicCtors.size)
        val nonSyntheticDeclaredCtors = McpPreflightToken::class.java.declaredConstructors.filterNot { it.isSynthetic }
        assertEquals(1, nonSyntheticDeclaredCtors.size)
        assertTrue("McpPreflightToken 建構子必須為 private", Modifier.isPrivate(nonSyntheticDeclaredCtors.single().modifiers))
        val tokenMethods = (
            McpPreflightToken::class.java.declaredMethods.toList() +
                McpPreflightToken.Companion::class.java.declaredMethods.toList()
            )
        for (m in tokenMethods) {
            assertFalse(
                "McpPreflightToken.${m.name} 不得接收外部 NetworkEvaluation",
                NetworkEvaluation::class.java in m.parameterTypes,
            )
        }

        // 預設接線檢查：McpTransport() 與 McpServer(...) 必須指向 NetworkPolicy.defaultPreflightExecutor 且 clampTimeoutMs 鉗 15s..30s。
        val defaultTransport = McpTransport()
        val transportExecField = McpTransport::class.java.getDeclaredField("preflightExecutor").apply { isAccessible = true }
        assertSame(NetworkPolicy.defaultPreflightExecutor, transportExecField.get(defaultTransport))
        val transportClampField = McpTransport::class.java.getDeclaredField("clampTimeoutMs").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val transportClamp = transportClampField.get(defaultTransport) as (Long) -> Long
        assertEquals(15_000L, transportClamp(100L))
        assertEquals(30_000L, transportClamp(50_000L))

        val defaultServer = McpServer(
            config = McpServerConfig(id = "s-default", baseUrl = "https://example.com/mcp"),
            keyVault = CountingVault(),
        )
        val serverExecField = McpServer::class.java.getDeclaredField("preflightExecutor").apply { isAccessible = true }
        assertSame(NetworkPolicy.defaultPreflightExecutor, serverExecField.get(defaultServer))
        val serverClampField = McpServer::class.java.getDeclaredField("clampTimeoutMs").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val serverClamp = serverClampField.get(defaultServer) as (Long) -> Long
        assertEquals(15_000L, serverClamp(100L))
        assertEquals(30_000L, serverClamp(50_000L))

        // 直調 postJson 一律走傳入的 HostResolver，無法靠舊 eval retarget。
        val dnsCalls = AtomicInteger(0)
        val resolver = HostResolver { host ->
            dnsCalls.incrementAndGet()
            assertEquals("example.com", host)
            listOf(addr("203.0.113.7"))
        }
        val httpCalls = AtomicInteger(0)
        val transport = McpTransport(
            interceptorClient { req ->
                httpCalls.incrementAndGet()
                mock200JsonResponse(req, goodListBody())
            },
        )
        val resp = transport.postJson(
            baseUrl = "https://example.com/mcp",
            bearer = "sekret",
            bodyJson = "{}",
            timeoutMs = McpTimeouts.DEFAULT_MS,
            optIn = EndpointNetworkOptIn(),
            resolver = resolver,
        )
        assertNotNull(resp)
        assertEquals(200, resp!!.first)
        assertEquals(1, dnsCalls.get())
        assertEquals(1, httpCalls.get())
    }

    @Test fun b02ConcurrentConsumeYieldsExactlyOneTrue() = runBlocking {
        // B-02 單次消耗：密封權杖的 consume() 以 AtomicBoolean.compareAndSet 實作，
        // 並發呼叫必恰有一個回 true，其餘 false（不可重複沿用）。
        val transport = McpTransport()
        val token = (
            transport.preflightMcpNetworkPolicy(
                url = "https://203.0.113.10/mcp",
                optIn = EndpointNetworkOptIn(),
                resolver = noDns,
                deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5),
            ) as McpPreflightOutcome.Allowed
        ).token
        val results = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()
        val latch = CountDownLatch(1)
        val threads = (1..8).map {
            Thread {
                latch.await()
                results.add(token.consume())
            }.apply { isDaemon = true }
        }
        threads.forEach { it.start() }
        latch.countDown()
        threads.forEach { it.join(2_000) }
        assertEquals(1, results.count { it })
        assertEquals(7, results.count { !it })
    }

    @Test fun preflightTimeoutReturnsTimeoutStatusWithoutBreaker() = runBlocking {
        // W-02 / WARN-04：DNS preflight 超時立即回 TIMEOUT，零 vault、零熔斷，且確實中斷卡住的解析執行緒。
        // 使用 newCachedThreadPool（SynchronousQueue 直接交付、無單執行緒隊列競爭窗口）＋每任務
        // 獨立 entered/interrupted latch，確保兩次 preflight 皆實際進入 resolver 並遭中斷。
        val vault = CountingVault()
        val breaker = McpCircuitBreaker()
        val enteredList = CountDownLatch(1)
        val interruptedList = CountDownLatch(1)
        val listResolver = HostResolver {
            enteredList.countDown()
            try {
                Thread.sleep(5_000)
            } catch (e: InterruptedException) {
                interruptedList.countDown()
                throw e
            }
            listOf(addr("203.0.113.1"))
        }
        val enteredCall = CountDownLatch(1)
        val interruptedCall = CountDownLatch(1)
        val callResolver = HostResolver {
            enteredCall.countDown()
            try {
                Thread.sleep(5_000)
            } catch (e: InterruptedException) {
                interruptedCall.countDown()
                throw e
            }
            listOf(addr("203.0.113.1"))
        }
        val testExecutor = Executors.newCachedThreadPool { r ->
            Thread(r, "test-preflight-timeout").apply { isDaemon = true }
        }
        try {
            // 預熱執行器避免池冷啟影響 250ms 超時視窗
            testExecutor.submit {}.get(2, TimeUnit.SECONDS)
            val listServer = McpServer.forTesting(
                config = McpServerConfig(
                    id = "s-slow-list",
                    baseUrl = "https://slow-list.example.com/mcp",
                    networkResolver = listResolver,
                ),
                keyVault = vault,
                transport = McpTransport(),
                breaker = breaker,
                preflightExecutor = testExecutor,
                clampTimeoutMs = { it.coerceIn(1L, McpTimeouts.MAX_MS) },
            )
            val startList = System.currentTimeMillis()
            val gotList = listServer.listTools(timeoutMs = 250L)
            val elapsedList = System.currentTimeMillis() - startList
            assertEquals(McpStatus.TIMEOUT, gotList.status)
            assertTrue("listTools preflight timeout 應在 2s 內返回，實際 ${elapsedList}ms", elapsedList < 2_000L)
            assertEquals(null, gotList.auditMap()["denyCode"])
            assertFalse("稽核不得含 host", gotList.auditMap().toString().contains("slow-list.example.com"))
            assertEquals("listTools preflight 不得讀 vault", 0, vault.reads.get())
            assertEquals("listTools preflight timeout 不得計入熔斷失敗", 0, breaker.failureCount())
            assertTrue("listTools preflight 須實際進入 resolver", enteredList.await(2, TimeUnit.SECONDS))
            assertTrue("listTools 超時須實際中斷解析執行緒", interruptedList.await(2, TimeUnit.SECONDS))

            val callServer = McpServer.forTesting(
                config = McpServerConfig(
                    id = "s-slow-call",
                    baseUrl = "https://slow-call.example.com/mcp",
                    networkResolver = callResolver,
                ),
                keyVault = vault,
                transport = McpTransport(),
                breaker = breaker,
                preflightExecutor = testExecutor,
                clampTimeoutMs = { it.coerceIn(1L, McpTimeouts.MAX_MS) },
            )
            val startCall = System.currentTimeMillis()
            val gotCall = callServer.callTool("toolA", "{}", timeoutMs = 250L)
            val elapsedCall = System.currentTimeMillis() - startCall
            assertEquals(McpStatus.TIMEOUT, gotCall.status)
            assertTrue("callTool preflight timeout 應在 2s 內返回，實際 ${elapsedCall}ms", elapsedCall < 2_000L)
            assertEquals(null, gotCall.auditMap()["denyCode"])
            assertFalse("稽核不得含 host", gotCall.auditMap().toString().contains("slow-call.example.com"))
            assertEquals("callTool preflight 不得讀 vault", 0, vault.reads.get())
            assertEquals("callTool preflight timeout 不得計入熔斷失敗", 0, breaker.failureCount())
            assertTrue("callTool preflight 須實際進入 resolver", enteredCall.await(2, TimeUnit.SECONDS))
            assertTrue("callTool 超時須實際中斷解析執行緒", interruptedCall.await(2, TimeUnit.SECONDS))
        } finally {
            testExecutor.shutdownNow()
            assertTrue(testExecutor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun preflightCancelledByCallingCoroutineCancelsPromptly() = runBlocking {
        // 協程取消時（listTools / callTool / 直調 postJson），runPreflightSuspend 應立即中斷 worker thread，
        // 不得等待 10s DNS，且零 vault、零 HTTP、零熔斷，CancellationException 原樣透傳。
        val testExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-preflight-cancel").apply { isDaemon = true }
        }
        val web = MockWebServer()
        try {
            testExecutor.submit {}.get(2, TimeUnit.SECONDS)
            web.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)

            // 1) McpServer.listTools
            val enteredList = CountDownLatch(1)
            val interruptedList = CountDownLatch(1)
            val vaultList = CountingVault()
            val breakerList = McpCircuitBreaker()
            val listResolver = HostResolver {
                enteredList.countDown()
                try {
                    Thread.sleep(10_000)
                } catch (e: InterruptedException) {
                    interruptedList.countDown()
                    throw e
                }
                listOf(addr("203.0.113.1"))
            }
            val serverList = McpServer.forTesting(
                config = McpServerConfig(
                    id = "s-slow-cancel-list",
                    baseUrl = "https://slow.example.com/mcp",
                    networkResolver = listResolver,
                ),
                keyVault = vaultList,
                breaker = breakerList,
                preflightExecutor = testExecutor,
            )
            val listOutcome = AtomicReference<Result<McpListResult>>()
            val listJob = launch(Dispatchers.Default) {
                listOutcome.set(runCatching { serverList.listTools(timeoutMs = 10_000L) })
            }
            assertTrue("必須先確定進入 listTools resolver 再取消", enteredList.await(5, TimeUnit.SECONDS))
            val t0 = System.currentTimeMillis()
            listJob.cancelAndJoin()
            assertTrue("listTools 協程取消應迅速返回", System.currentTimeMillis() - t0 < 2_000L)
            assertTrue("listTools 取消必須中斷 worker", interruptedList.await(2, TimeUnit.SECONDS))
            assertTrue(listOutcome.get()?.exceptionOrNull() is CancellationException)
            assertEquals(0, vaultList.reads.get())
            assertEquals(0, breakerList.failureCount())

            // 2) McpServer.callTool
            val enteredCall = CountDownLatch(1)
            val interruptedCall = CountDownLatch(1)
            val vaultCall = CountingVault()
            val breakerCall = McpCircuitBreaker()
            val callResolver = HostResolver {
                enteredCall.countDown()
                try {
                    Thread.sleep(10_000)
                } catch (e: InterruptedException) {
                    interruptedCall.countDown()
                    throw e
                }
                listOf(addr("203.0.113.1"))
            }
            val serverCall = McpServer.forTesting(
                config = McpServerConfig(
                    id = "s-slow-cancel-call",
                    baseUrl = "https://slow.example.com/mcp",
                    networkResolver = callResolver,
                ),
                keyVault = vaultCall,
                breaker = breakerCall,
                preflightExecutor = testExecutor,
            )
            val callOutcome = AtomicReference<Result<McpCallResult>>()
            val callJob = launch(Dispatchers.Default) {
                callOutcome.set(runCatching { serverCall.callTool("t", "{}", timeoutMs = 10_000L) })
            }
            assertTrue("必須先確定進入 callTool resolver 再取消", enteredCall.await(5, TimeUnit.SECONDS))
            val t1 = System.currentTimeMillis()
            callJob.cancelAndJoin()
            assertTrue("callTool 協程取消應迅速返回", System.currentTimeMillis() - t1 < 2_000L)
            assertTrue("callTool 取消必須中斷 worker", interruptedCall.await(2, TimeUnit.SECONDS))
            assertTrue(callOutcome.get()?.exceptionOrNull() is CancellationException)
            assertEquals(0, vaultCall.reads.get())
            assertEquals(0, breakerCall.failureCount())

            // 3) 直調 McpTransport.postJson
            val enteredDirect = CountDownLatch(1)
            val interruptedDirect = CountDownLatch(1)
            val directResolver = HostResolver {
                enteredDirect.countDown()
                try {
                    Thread.sleep(10_000)
                } catch (e: InterruptedException) {
                    interruptedDirect.countDown()
                    throw e
                }
                listOf(addr("127.0.0.1"))
            }
            val transport = McpTransport(
                client = McpTransport.defaultHttp(),
                preflightExecutor = testExecutor,
                clampTimeoutMs = McpTimeouts::clamp,
                nanoClock = System::nanoTime,
            )
            val directOutcome = AtomicReference<Result<Pair<Int, String?>?>>()
            val directJob = launch(Dispatchers.Default) {
                directOutcome.set(
                    runCatching {
                        transport.postJson(
                            baseUrl = "http://localhost:${web.port}/mcp",
                            bearer = "sekret",
                            bodyJson = "{}",
                            timeoutMs = McpTimeouts.DEFAULT_MS,
                            optIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE),
                            resolver = directResolver,
                        )
                    },
                )
            }
            assertTrue("必須先確定進入 postJson resolver 再取消", enteredDirect.await(5, TimeUnit.SECONDS))
            val t2 = System.currentTimeMillis()
            directJob.cancelAndJoin()
            assertTrue("直調 postJson 協程取消應迅速返回", System.currentTimeMillis() - t2 < 2_000L)
            assertTrue("直調 postJson 取消必須中斷 worker", interruptedDirect.await(2, TimeUnit.SECONDS))
            assertTrue(directOutcome.get()?.exceptionOrNull() is CancellationException)
            assertEquals(0, web.requestCount)
        } finally {
            runCatching { web.shutdown() }
            testExecutor.shutdownNow()
            assertTrue(testExecutor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun postJsonWithPreverifiedUrlMismatchFallsBackToRealResolverUnderDeadline() = runBlocking {
        // High #1 非空洞驗證：以**同一實例** countingResolver（dns === token.resolver）測試路徑與埠號不符，
        // 證實即使 resolver 實例相同，URL path／port 任一不符仍拒絕沿用 token 並回落重查 DNS（dnsCalls 1→2）。
        val dnsCalls = AtomicInteger(0)
        val countingResolver = HostResolver {
            dnsCalls.incrementAndGet()
            listOf(addr("203.0.113.10"))
        }
        val seenUrl = AtomicReference<String>()
        val transport = McpTransport(
            interceptorClient { req ->
                seenUrl.set(req.url.toString())
                mock200JsonResponse(req, goodListBody())
            },
        )
        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)

        // 1) Path 不符（/mcp -> /other-endpoint），同一 resolver 實例
        val pathToken = (
            transport.preflightMcpNetworkPolicy(
                url = "https://example.com/mcp",
                optIn = EndpointNetworkOptIn(),
                resolver = countingResolver,
                deadlineNs = deadlineNs,
            ) as McpPreflightOutcome.Allowed
        ).token
        assertEquals(1, dnsCalls.get())
        val gotPath = transport.postJsonWithPreverified(
            baseUrl = "https://example.com/other-endpoint",
            bearer = "sekret",
            bodyJson = "{}",
            deadlineNs = deadlineNs,
            optIn = EndpointNetworkOptIn(),
            resolver = countingResolver,
            preverified = pathToken,
        )
        assertNotNull(gotPath)
        assertEquals(200, gotPath!!.first)
        assertEquals("同一 resolver 下 URL path 不符必須回落重查 DNS", 2, dnsCalls.get())
        assertEquals("https://example.com/other-endpoint", seenUrl.get())

        // 2) Port 不符（預設 443 -> 8443），同一 resolver 實例＋未消耗新 token
        val portToken = (
            transport.preflightMcpNetworkPolicy(
                url = "https://example.com/mcp",
                optIn = EndpointNetworkOptIn(),
                resolver = countingResolver,
                deadlineNs = deadlineNs,
            ) as McpPreflightOutcome.Allowed
        ).token
        assertEquals(3, dnsCalls.get())
        val gotPort = transport.postJsonWithPreverified(
            baseUrl = "https://example.com:8443/mcp",
            bearer = "sekret",
            bodyJson = "{}",
            deadlineNs = deadlineNs,
            optIn = EndpointNetworkOptIn(),
            resolver = countingResolver,
            preverified = portToken,
        )
        assertNotNull(gotPort)
        assertEquals(200, gotPort!!.first)
        assertEquals("同一 resolver 下 URL port 不符必須回落重查 DNS", 4, dnsCalls.get())
        assertEquals("https://example.com:8443/mcp", seenUrl.get())
    }

    @Test fun b01NonLoopbackAndMixedDnsOverHttpRejectedAcrossAllLayers() = runBlocking {
        val cleartext = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)
        val cases = listOf(
            "private-v4" to listOf("10.1.2.3"),
            "mixed-loopback-and-private" to listOf("127.0.0.1", "10.1.2.3"),
            "private-ula-v6" to listOf("fc00::1"),
        )
        val quickTransport = McpTransport(
            client = McpTransport.defaultHttp(),
            preflightExecutor = NetworkPolicy.defaultPreflightExecutor,
            clampTimeoutMs = { it.coerceIn(1L, 500L) },
            nanoClock = System::nanoTime,
        )
        for ((label, ips) in cases) {
            val web = MockWebServer()
            try {
                web.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
                // 預放 dummy 回應：若有任何層回歸誤發 HTTP，立刻回 200 並觸發 fail，不等 20s 超時
                web.enqueue(MockResponse().setResponseCode(200).setBody(goodListBody()))
                val base = "http://localhost:${web.port}/mcp"
                val dns = fakeDns("localhost" to ips)

                // 1) requireMcpNetworkPolicyAllowed
                try {
                    requireMcpNetworkPolicyAllowed(base, cleartext, dns)
                    fail("$label must be denied at requireMcpNetworkPolicyAllowed")
                } catch (e: McpNetworkPolicyBlocked) {
                    assertEquals("$label gate denyCode", NetworkDenyCode.CLEARTEXT_NOT_PERMITTED, e.denyCode)
                }

                // 2) direct McpTransport.postJson
                try {
                    quickTransport.postJson(base, "sekret", "{}", 500L, cleartext, dns)
                    fail("$label must be denied at direct postJson")
                } catch (e: McpNetworkPolicyBlocked) {
                    assertEquals("$label postJson denyCode", NetworkDenyCode.CLEARTEXT_NOT_PERMITTED, e.denyCode)
                }
                assertEquals("$label direct postJson 0 HTTP", 0, web.requestCount)

                // 3) McpServer.listTools & 4) McpServer.callTool
                val vault = CountingVault()
                val breaker = McpCircuitBreaker()
                val server = McpServer.forTesting(
                    config = McpServerConfig(
                        id = "s-b01",
                        baseUrl = base,
                        networkOptIn = cleartext,
                        networkResolver = dns,
                    ),
                    keyVault = vault,
                    transport = quickTransport,
                    breaker = breaker,
                    clampTimeoutMs = { it.coerceIn(1L, 500L) },
                )
                val listRes = server.listTools(timeoutMs = 500L)
                assertEquals("$label listTools status", McpStatus.PROTOCOL, listRes.status)
                assertEquals("$label listTools denyCode", NetworkDenyCode.CLEARTEXT_NOT_PERMITTED.name, listRes.auditMap()["denyCode"])
                assertEquals("$label listTools 0 vault", 0, vault.reads.get())
                assertEquals("$label listTools 0 HTTP", 0, web.requestCount)
                assertEquals("$label listTools 0 breaker", 0, breaker.failureCount())

                val callRes = server.callTool("t", "{}", timeoutMs = 500L)
                assertEquals("$label callTool status", McpStatus.PROTOCOL, callRes.status)
                assertEquals("$label callTool denyCode", NetworkDenyCode.CLEARTEXT_NOT_PERMITTED.name, callRes.auditMap()["denyCode"])
                assertEquals("$label callTool 0 vault", 0, vault.reads.get())
                assertEquals("$label callTool 0 HTTP", 0, web.requestCount)
                assertEquals("$label callTool 0 breaker", 0, breaker.failureCount())
            } finally {
                runCatching { web.shutdown() }
            }
        }
    }

    @Test fun b01PositiveControlsLoopbackHttpAndPrivateHttpsPass() = runBlocking {
        val cleartext = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)
        // IPv6 loopback ::1 over http passes gate
        val v6Eval = requireMcpNetworkPolicyAllowed("http://localhost/mcp", cleartext, fakeDns("localhost" to listOf("::1")))
        assertTrue(v6Eval.allowed)
        // https + ALLOW_PRIVATE_DNS resolving to 10.1.2.3 passes gate
        val privateHttpsEval = requireMcpNetworkPolicyAllowed(
            "https://example.com/mcp",
            EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_PRIVATE_DNS),
            fakeDns("example.com" to listOf("10.1.2.3")),
        )
        assertTrue(privateHttpsEval.allowed)

        // End-to-end http://localhost -> 127.0.0.1 succeeds
        val web = MockWebServer()
        try {
            web.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
            web.enqueue(MockResponse().setResponseCode(200).setBody(goodListBody()))
            web.enqueue(MockResponse().setResponseCode(200).setBody(goodCallBody()))
            val base = "http://localhost:${web.port}/mcp"
            val dns = fakeDns("localhost" to listOf("127.0.0.1"))
            assertTrue(requireMcpNetworkPolicyAllowed(base, cleartext, dns).allowed)
            val server = McpServer(
                config = McpServerConfig(
                    id = "s-pos",
                    baseUrl = base,
                    networkOptIn = cleartext,
                    networkResolver = dns,
                ),
                keyVault = CountingVault(),
            )
            assertEquals(McpStatus.OK, server.listTools().status)
            assertEquals(McpStatus.OK, server.callTool("t", "{}").status)
            assertEquals(2, web.requestCount)
        } finally {
            runCatching { web.shutdown() }
        }
    }

    @Test fun b02LiveOptInDowngradeDuringVaultReadBlocksWithoutExtraDnsOrHttp() = runBlocking {
        // 1) ALLOW_CLEARTEXT_PRIVATE -> HTTPS_ONLY 與 ALLOW_CLEARTEXT_PRIVATE -> ALLOW_PRIVATE_DNS
        //    於 http://localhost:<port>/mcp keyVault.getKey() 期間降級：皆立即阻擋、零二次 DNS、零 HTTP、零熔斷。
        val web = MockWebServer()
        try {
            web.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
            val base = "http://localhost:${web.port}/mcp"
            val store = pilotStore()
            val endpointId = mcpEndpointId("s-downgrade-http")
            val dnsCalls = AtomicInteger(0)
            val dns = HostResolver {
                dnsCalls.incrementAndGet()
                listOf(addr("127.0.0.1"))
            }
            val downgradeTarget = AtomicReference(NetworkEgressPolicy.HTTPS_ONLY)
            val downgradeVault = object : KeyVault {
                override suspend fun putKey(providerId: String, apiKey: CharArray) = Unit
                override suspend fun getKey(providerId: String): CharArray {
                    store.confirm(endpointId, base, downgradeTarget.get(), 0L)
                    return "sekret-token-1".toCharArray()
                }
                override suspend fun deleteKey(providerId: String) = Unit
                override suspend fun hasKey(providerId: String): Boolean = true
            }
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(id = "s-downgrade-http", baseUrl = base, networkResolver = dns),
                keyVault = downgradeVault,
                transport = McpTransport(),
                breaker = breaker,
                networkOptInStore = store,
            )

            // 1a) ALLOW_CLEARTEXT_PRIVATE -> HTTPS_ONLY
            downgradeTarget.set(NetworkEgressPolicy.HTTPS_ONLY)
            store.confirm(endpointId, base, NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE, 0L)
            val listGot = server.listTools()
            assertEquals(McpStatus.PROTOCOL, listGot.status)
            assertEquals(NetworkDenyCode.CLEARTEXT_NOT_PERMITTED.name, listGot.auditMap()["denyCode"])
            assertEquals(0, web.requestCount)
            assertEquals(0, breaker.failureCount())
            assertEquals("降級重驗不得觸發第二次 DNS", 1, dnsCalls.get())

            store.confirm(endpointId, base, NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE, 0L)
            val callGot = server.callTool("t", "{}")
            assertEquals(McpStatus.PROTOCOL, callGot.status)
            assertEquals(NetworkDenyCode.CLEARTEXT_NOT_PERMITTED.name, callGot.auditMap()["denyCode"])
            assertEquals(0, web.requestCount)
            assertEquals(0, breaker.failureCount())
            assertEquals("callTool 降級重驗不得觸發第二次 DNS", 2, dnsCalls.get())

            // 1b) ALLOW_CLEARTEXT_PRIVATE -> ALLOW_PRIVATE_DNS on http://localhost
            downgradeTarget.set(NetworkEgressPolicy.ALLOW_PRIVATE_DNS)
            store.confirm(endpointId, base, NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE, 0L)
            val listPrivateDowngrade = server.listTools()
            assertEquals(McpStatus.PROTOCOL, listPrivateDowngrade.status)
            assertEquals(NetworkDenyCode.CLEARTEXT_NOT_PERMITTED.name, listPrivateDowngrade.auditMap()["denyCode"])
            assertEquals(0, web.requestCount)
            assertEquals(0, breaker.failureCount())
            assertEquals("降級至 ALLOW_PRIVATE_DNS 不得觸發第二次 DNS", 3, dnsCalls.get())

            store.confirm(endpointId, base, NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE, 0L)
            val callPrivateDowngrade = server.callTool("t", "{}")
            assertEquals(McpStatus.PROTOCOL, callPrivateDowngrade.status)
            assertEquals(NetworkDenyCode.CLEARTEXT_NOT_PERMITTED.name, callPrivateDowngrade.auditMap()["denyCode"])
            assertEquals(0, web.requestCount)
            assertEquals(0, breaker.failureCount())
            assertEquals("callTool 降級至 ALLOW_PRIVATE_DNS 不得觸發第二次 DNS", 4, dnsCalls.get())
        } finally {
            runCatching { web.shutdown() }
        }

        // 2) ALLOW_PRIVATE_DNS -> HTTPS_ONLY on https://internal.example.com/mcp during keyVault.getKey()
        val httpsBase = "https://internal.example.com/mcp"
        val store2 = pilotStore()
        val endpointId2 = mcpEndpointId("s-downgrade-https")
        val dnsCalls2 = AtomicInteger(0)
        val dns2 = HostResolver {
            dnsCalls2.incrementAndGet()
            listOf(addr("10.1.2.3"))
        }
        val httpCalls2 = AtomicInteger(0)
        val transport2 = McpTransport(
            interceptorClient { req ->
                httpCalls2.incrementAndGet()
                mock200JsonResponse(req, goodListBody())
            },
        )
        val downgradeVault2 = object : KeyVault {
            override suspend fun putKey(providerId: String, apiKey: CharArray) = Unit
            override suspend fun getKey(providerId: String): CharArray {
                store2.confirm(endpointId2, httpsBase, NetworkEgressPolicy.HTTPS_ONLY, 0L)
                return "sekret-token-1".toCharArray()
            }
            override suspend fun deleteKey(providerId: String) = Unit
            override suspend fun hasKey(providerId: String): Boolean = true
        }
        val breaker2 = McpCircuitBreaker()
        val server2 = McpServer(
            config = McpServerConfig(id = "s-downgrade-https", baseUrl = httpsBase, networkResolver = dns2),
            keyVault = downgradeVault2,
            transport = transport2,
            breaker = breaker2,
            networkOptInStore = store2,
        )

        store2.confirm(endpointId2, httpsBase, NetworkEgressPolicy.ALLOW_PRIVATE_DNS, 0L)
        val httpsListGot = server2.listTools()
        assertEquals(McpStatus.PROTOCOL, httpsListGot.status)
        assertEquals(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED.name, httpsListGot.auditMap()["denyCode"])
        assertEquals(0, httpCalls2.get())
        assertEquals(0, breaker2.failureCount())
        assertEquals(1, dnsCalls2.get())

        store2.confirm(endpointId2, httpsBase, NetworkEgressPolicy.ALLOW_PRIVATE_DNS, 0L)
        val httpsCallGot = server2.callTool("t", "{}")
        assertEquals(McpStatus.PROTOCOL, httpsCallGot.status)
        assertEquals(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED.name, httpsCallGot.auditMap()["denyCode"])
        assertEquals(0, httpCalls2.get())
        assertEquals(0, breaker2.failureCount())
        assertEquals(2, dnsCalls2.get())
    }

    @Test fun w01DirectTransportPostJsonAndFallbackWithHangingResolverTimeOutPromptly() = runBlocking {
        val entered1 = CountDownLatch(1)
        val interrupted1 = CountDownLatch(1)
        val hanging1 = HostResolver {
            entered1.countDown()
            try {
                Thread.sleep(10_000)
            } catch (e: InterruptedException) {
                interrupted1.countDown()
                throw e
            }
            listOf(addr("203.0.113.1"))
        }
        val entered2 = CountDownLatch(1)
        val interrupted2 = CountDownLatch(1)
        val hanging2 = HostResolver {
            entered2.countDown()
            try {
                Thread.sleep(10_000)
            } catch (e: InterruptedException) {
                interrupted2.countDown()
                throw e
            }
            listOf(addr("203.0.113.2"))
        }
        val testExecutor = Executors.newCachedThreadPool { r ->
            Thread(r, "test-w01-transport").apply { isDaemon = true }
        }
        try {
            // 預熱執行器避免建執行緒冷啟影響 250ms 超時視窗
            testExecutor.submit {}.get(2, TimeUnit.SECONDS)
            val transport = McpTransport(
                client = McpTransport.defaultHttp(),
                preflightExecutor = testExecutor,
                clampTimeoutMs = { it.coerceIn(1L, McpTimeouts.MAX_MS) },
                nanoClock = System::nanoTime,
            )
            // 1) 直調 postJson 遇懸掛 DNS 在 250ms 立即回 null 並中斷 worker
            val start1 = System.currentTimeMillis()
            val out1 = transport.postJson(
                baseUrl = "https://hanging1.example.com/mcp",
                bearer = "sekret",
                bodyJson = "{}",
                timeoutMs = 250L,
                resolver = hanging1,
            )
            val elapsed1 = System.currentTimeMillis() - start1
            assertNull("直調 postJson DNS 超時必須回 null", out1)
            assertTrue("應迅速返回 (<2s)，實際 ${elapsed1}ms", elapsed1 < 2_000L)
            assertTrue(entered1.await(2, TimeUnit.SECONDS))
            assertTrue("hanging1 worker 必須被中斷", interrupted1.await(2, TimeUnit.SECONDS))

            // 2) postJsonWithPreverified 遇到 URL mismatch 回落懸掛 DNS 時，亦受 deadlineNs 約束回 null 並中斷 worker
            val token = (
                transport.preflightMcpNetworkPolicy(
                    url = "https://good.example.com/mcp",
                    optIn = EndpointNetworkOptIn(),
                    resolver = fakeDns("good.example.com" to listOf("203.0.113.10")),
                    deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5),
                ) as McpPreflightOutcome.Allowed
            ).token
            val start2 = System.currentTimeMillis()
            val out2 = transport.postJsonWithPreverified(
                baseUrl = "https://hanging2.example.com/mcp",
                bearer = "sekret",
                bodyJson = "{}",
                deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250L),
                optIn = EndpointNetworkOptIn(),
                resolver = hanging2,
                preverified = token,
            )
            val elapsed2 = System.currentTimeMillis() - start2
            assertNull("fallback mismatch DNS 超時必須回 null", out2)
            assertTrue("fallback 應迅速返回 (<2s)，實際 ${elapsed2}ms", elapsed2 < 2_000L)
            assertTrue(entered2.await(2, TimeUnit.SECONDS))
            assertTrue("hanging2 worker 必須被中斷", interrupted2.await(2, TimeUnit.SECONDS))
        } finally {
            testExecutor.shutdownNow()
            assertTrue(testExecutor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun w02SingleAggregateDeadlineSpansDnsAndHttpBudget() = runBlocking {
        // W-02 決定性時序：注入假 nanoClock，Step 5 DNS preflight 推進 1000ms、Step 6 vault
        // 推進 850ms（皆零 Thread.sleep），對 2000ms 總預算僅剩 150ms；MockWebServer
        // setBodyDelay(600ms) 長於剩餘預算但**短於完整預算** → listTools 與 callTool 皆確定性於
        // Step 7 HTTP TIMEOUT（elapsed < 500ms、requestCount == 1、breaker == 1）。
        // 若 Step 7 誤用新鮮預算（2000ms），600ms body 會完成而回 200 OK，本測試即失敗（非空洞）。
        val testExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-w02-agg").apply { isDaemon = true }
        }
        try {
            testExecutor.submit {}.get(2, TimeUnit.SECONDS)
            val cleartext = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)
            val fakeNowNs = AtomicLong(1_000_000_000L)
            val nanoClock: () -> Long = { fakeNowNs.get() }
            val slowDns = HostResolver {
                fakeNowNs.addAndGet(TimeUnit.MILLISECONDS.toNanos(1_000))
                listOf(addr("127.0.0.1"))
            }
            val slowVault = object : KeyVault {
                val reads = AtomicInteger(0)
                override suspend fun putKey(providerId: String, apiKey: CharArray) = Unit
                override suspend fun getKey(providerId: String): CharArray? {
                    reads.incrementAndGet()
                    fakeNowNs.addAndGet(TimeUnit.MILLISECONDS.toNanos(850))
                    return "sekret-token-1".toCharArray()
                }
                override suspend fun deleteKey(providerId: String) = Unit
                override suspend fun hasKey(providerId: String): Boolean = true
            }
            val transport = McpTransport(
                client = McpTransport.defaultHttp(),
                preflightExecutor = testExecutor,
                clampTimeoutMs = { it.coerceIn(1L, McpTimeouts.MAX_MS) },
                nanoClock = nanoClock,
            )

            // listTools
            val webList = MockWebServer()
            try {
                webList.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
                webList.enqueue(
                    MockResponse().setResponseCode(200)
                        .setBodyDelay(600, TimeUnit.MILLISECONDS)
                        .setBody(goodListBody()),
                )
                val breakerList = McpCircuitBreaker()
                val serverList = McpServer.forTesting(
                    config = McpServerConfig(
                        id = "s-agg-list",
                        baseUrl = "http://localhost:${webList.port}/mcp",
                        networkOptIn = cleartext,
                        networkResolver = slowDns,
                    ),
                    keyVault = slowVault,
                    transport = transport,
                    breaker = breakerList,
                    preflightExecutor = testExecutor,
                    clampTimeoutMs = { it.coerceIn(1L, McpTimeouts.MAX_MS) },
                    nanoClock = nanoClock,
                )
                val t0 = System.currentTimeMillis()
                val res = serverList.listTools(timeoutMs = 2_000L)
                val elapsed = System.currentTimeMillis() - t0
                assertEquals(McpStatus.TIMEOUT, res.status)
                assertTrue("剩 150ms 預算應 < 1000ms 返回，實際 ${elapsed}ms", elapsed < 1_000L)
                assertEquals(1, webList.requestCount)
                assertEquals("Step 7 HTTP 超時應計 1 次熔斷", 1, breakerList.failureCount())
            } finally {
                runCatching { webList.shutdown() }
            }

            // callTool
            val webCall = MockWebServer()
            try {
                webCall.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
                webCall.enqueue(
                    MockResponse().setResponseCode(200)
                        .setBodyDelay(600, TimeUnit.MILLISECONDS)
                        .setBody(goodCallBody()),
                )
                val breakerCall = McpCircuitBreaker()
                val serverCall = McpServer.forTesting(
                    config = McpServerConfig(
                        id = "s-agg-call",
                        baseUrl = "http://localhost:${webCall.port}/mcp",
                        networkOptIn = cleartext,
                        networkResolver = slowDns,
                    ),
                    keyVault = slowVault,
                    transport = transport,
                    breaker = breakerCall,
                    preflightExecutor = testExecutor,
                    clampTimeoutMs = { it.coerceIn(1L, McpTimeouts.MAX_MS) },
                    nanoClock = nanoClock,
                )
                val t0 = System.currentTimeMillis()
                val res = serverCall.callTool("t", "{}", timeoutMs = 2_000L)
                val elapsed = System.currentTimeMillis() - t0
                assertEquals(McpStatus.TIMEOUT, res.status)
                assertTrue("callTool 剩 150ms 預算應 < 500ms 返回，實際 ${elapsed}ms", elapsed < 500L)
                assertEquals(1, webCall.requestCount)
                assertEquals("Step 7 HTTP 超時應計 1 次熔斷", 1, breakerCall.failureCount())
            } finally {
                runCatching { webCall.shutdown() }
            }
        } finally {
            testExecutor.shutdownNow()
            assertTrue(testExecutor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun w02SlowKeyVaultExceedingBudgetReturnsTimeoutWithZeroHttp() = runBlocking {
        val testExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-w02-vault").apply { isDaemon = true }
        }
        val web = MockWebServer()
        try {
            testExecutor.submit {}.get(2, TimeUnit.SECONDS)
            web.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
            val cleartext = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)

            // (a) keyVault.getKey() 永久掛起（CompletableDeferred.await()）遭 withTimeoutOrNull(remainingMs) 中斷：
            //     回 TIMEOUT，0 HTTP，0 breaker failure。
            val vaultCancelled = AtomicInteger(0)
            val stalledVault = object : KeyVault {
                override suspend fun putKey(providerId: String, apiKey: CharArray) = Unit
                override suspend fun getKey(providerId: String): CharArray? {
                    try {
                        CompletableDeferred<CharArray?>().await()
                    } catch (e: CancellationException) {
                        vaultCancelled.incrementAndGet()
                        throw e
                    }
                    return null
                }
                override suspend fun deleteKey(providerId: String) = Unit
                override suspend fun hasKey(providerId: String): Boolean = true
            }
            val stalledBreaker = McpCircuitBreaker()
            val stalledServer = McpServer.forTesting(
                config = McpServerConfig(
                    id = "s-stalled-vault",
                    baseUrl = "http://localhost:${web.port}/mcp",
                    networkOptIn = cleartext,
                    networkResolver = fakeDns("localhost" to listOf("127.0.0.1")),
                ),
                keyVault = stalledVault,
                breaker = stalledBreaker,
                preflightExecutor = testExecutor,
                clampTimeoutMs = { it.coerceIn(1L, McpTimeouts.MAX_MS) },
            )
            val listStalled = stalledServer.listTools(timeoutMs = 150L)
            assertEquals(McpStatus.TIMEOUT, listStalled.status)
            assertEquals("vault 掛起超時不得發出 HTTP", 0, web.requestCount)
            assertEquals("vault 掛起超時不得計熔斷失敗", 0, stalledBreaker.failureCount())

            val callStalled = stalledServer.callTool("t", "{}", timeoutMs = 150L)
            assertEquals(McpStatus.TIMEOUT, callStalled.status)
            assertEquals("callTool vault 掛起超時不得發出 HTTP", 0, web.requestCount)
            assertEquals("callTool vault 掛起超時不得計熔斷失敗", 0, stalledBreaker.failureCount())
            assertEquals("兩次 vault 掛起皆須被 withTimeoutOrNull 取消", 2, vaultCancelled.get())

            // (b) keyVault.getKey() 返回時聚合 deadlineNs 剛過（假時鐘於 getKey 內推進 200ms > 150ms）：
            //     Step 7 前檢查 deadlineNs - nanoClock() <= 0L 命中 TIMEOUT，0 HTTP，0 breaker failure。
            val fakeNowNsB = AtomicLong(1_000_000_000L)
            val clockB: () -> Long = { fakeNowNsB.get() }
            val slowClockVault = object : KeyVault {
                override suspend fun putKey(providerId: String, apiKey: CharArray) = Unit
                override suspend fun getKey(providerId: String): CharArray {
                    fakeNowNsB.addAndGet(TimeUnit.MILLISECONDS.toNanos(200))
                    return "sekret-token-1".toCharArray()
                }
                override suspend fun deleteKey(providerId: String) = Unit
                override suspend fun hasKey(providerId: String): Boolean = true
            }
            val breakerB = McpCircuitBreaker()
            val serverB = McpServer.forTesting(
                config = McpServerConfig(
                    id = "s-post-vault-expired",
                    baseUrl = "http://localhost:${web.port}/mcp",
                    networkOptIn = cleartext,
                    networkResolver = fakeDns("localhost" to listOf("127.0.0.1")),
                ),
                keyVault = slowClockVault,
                transport = McpTransport(McpTransport.defaultHttp(), testExecutor, { it.coerceIn(1L, McpTimeouts.MAX_MS) }, clockB),
                breaker = breakerB,
                preflightExecutor = testExecutor,
                clampTimeoutMs = { it.coerceIn(1L, McpTimeouts.MAX_MS) },
                nanoClock = clockB,
            )
            assertEquals(McpStatus.TIMEOUT, serverB.listTools(timeoutMs = 150L).status)
            assertEquals(0, web.requestCount)
            assertEquals("vault 返回後剛到期不得計熔斷失敗", 0, breakerB.failureCount())
            assertEquals(McpStatus.TIMEOUT, serverB.callTool("t", "{}", timeoutMs = 150L).status)
            assertEquals(0, web.requestCount)
            assertEquals("callTool vault 返回後剛到期不得計熔斷失敗", 0, breakerB.failureCount())

            // (b2) 進 vault 前即到期（DNS 消耗完整 150ms 預算）：0 vault 讀取、0 HTTP、0 熔斷。
            val preVaultNowNs = AtomicLong(1_000_000_000L)
            val preVaultClock: () -> Long = { preVaultNowNs.get() }
            val neverReadVault = CountingVault()
            val preVaultBreaker = McpCircuitBreaker()
            val preVaultServer = McpServer.forTesting(
                config = McpServerConfig(
                    id = "s-pre-vault-expired",
                    baseUrl = "http://localhost:${web.port}/mcp",
                    networkOptIn = cleartext,
                    networkResolver = HostResolver {
                        preVaultNowNs.addAndGet(TimeUnit.MILLISECONDS.toNanos(150))
                        listOf(addr("127.0.0.1"))
                    },
                ),
                keyVault = neverReadVault,
                transport = McpTransport(McpTransport.defaultHttp(), testExecutor, { it.coerceIn(1L, McpTimeouts.MAX_MS) }, preVaultClock),
                breaker = preVaultBreaker,
                preflightExecutor = testExecutor,
                clampTimeoutMs = { it.coerceIn(1L, McpTimeouts.MAX_MS) },
                nanoClock = preVaultClock,
            )
            assertEquals(McpStatus.TIMEOUT, preVaultServer.listTools(timeoutMs = 150L).status)
            assertEquals(McpStatus.TIMEOUT, preVaultServer.callTool("t", "{}", timeoutMs = 150L).status)
            assertEquals("進 vault 前已到期不得讀 vault", 0, neverReadVault.reads.get())
            assertEquals(0, web.requestCount)
            assertEquals(0, preVaultBreaker.failureCount())

            // (c) 組合預算：DNS (150ms 假時鐘) + Vault (200ms 假時鐘) + 慢 HTTP (剩 150ms vs 2s bodyDelay)
            //     -> TIMEOUT 且計 Step 7 熔斷失敗 1 次。
            val fakeNowNsC = AtomicLong(1_000_000_000L)
            val clockC: () -> Long = { fakeNowNsC.get() }
            val combinedDns = HostResolver {
                fakeNowNsC.addAndGet(TimeUnit.MILLISECONDS.toNanos(150))
                listOf(addr("127.0.0.1"))
            }
            val combinedVault = object : KeyVault {
                override suspend fun putKey(providerId: String, apiKey: CharArray) = Unit
                override suspend fun getKey(providerId: String): CharArray {
                    fakeNowNsC.addAndGet(TimeUnit.MILLISECONDS.toNanos(200))
                    return "sekret-token-1".toCharArray()
                }
                override suspend fun deleteKey(providerId: String) = Unit
                override suspend fun hasKey(providerId: String): Boolean = true
            }
            val transportC = McpTransport(
                client = McpTransport.defaultHttp(),
                preflightExecutor = testExecutor,
                clampTimeoutMs = { it.coerceIn(1L, McpTimeouts.MAX_MS) },
                nanoClock = clockC,
            )
            web.enqueue(MockResponse().setResponseCode(200).setBodyDelay(2, TimeUnit.SECONDS).setBody(goodListBody()))
            val breakerCList = McpCircuitBreaker()
            val serverCList = McpServer.forTesting(
                config = McpServerConfig(
                    id = "s-combined-list",
                    baseUrl = "http://localhost:${web.port}/mcp",
                    networkOptIn = cleartext,
                    networkResolver = combinedDns,
                ),
                keyVault = combinedVault,
                transport = transportC,
                breaker = breakerCList,
                preflightExecutor = testExecutor,
                clampTimeoutMs = { it.coerceIn(1L, McpTimeouts.MAX_MS) },
                nanoClock = clockC,
            )
            val combinedListRes = serverCList.listTools(timeoutMs = 500L)
            assertEquals(McpStatus.TIMEOUT, combinedListRes.status)
            assertEquals(1, web.requestCount)
            assertEquals("組合預算進入 Step 7 HTTP 超時應計 1 次熔斷", 1, breakerCList.failureCount())

            web.enqueue(MockResponse().setResponseCode(200).setBodyDelay(2, TimeUnit.SECONDS).setBody(goodCallBody()))
            val breakerCCall = McpCircuitBreaker()
            val serverCCall = McpServer.forTesting(
                config = McpServerConfig(
                    id = "s-combined-call",
                    baseUrl = "http://localhost:${web.port}/mcp",
                    networkOptIn = cleartext,
                    networkResolver = combinedDns,
                ),
                keyVault = combinedVault,
                transport = transportC,
                breaker = breakerCCall,
                preflightExecutor = testExecutor,
                clampTimeoutMs = { it.coerceIn(1L, McpTimeouts.MAX_MS) },
                nanoClock = clockC,
            )
            val combinedCallRes = serverCCall.callTool("t", "{}", timeoutMs = 500L)
            assertEquals(McpStatus.TIMEOUT, combinedCallRes.status)
            assertEquals(2, web.requestCount)
            assertEquals("callTool 組合預算進入 Step 7 HTTP 超時應計 1 次熔斷", 1, breakerCCall.failureCount())
        } finally {
            runCatching { web.shutdown() }
            testExecutor.shutdownNow()
            assertTrue(testExecutor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun nanoClockWraparoundAcrossLongMaxValuePreservesDeadlineArithmetic() = runBlocking {
        // Low #7：System.nanoTime 跨 Long.MAX_VALUE 溢位繞回測試。
        // deadlineNs - nanoClock() <= 0L 的有號差值於溢位前後仍須正確（不得誤判立即逾時或永不逾時）。
        val testExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-nanoclock-wrap").apply { isDaemon = true }
        }
        val web = MockWebServer()
        try {
            testExecutor.submit {}.get(2, TimeUnit.SECONDS)
            web.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
            val base = "http://localhost:${web.port}/mcp"
            val cleartext = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)

            // 1) McpServer.forTesting：起點 Long.MAX_VALUE - 100ms，budget = 500ms（deadlineNs 繞回為負）
            // 1a) DNS +50ms（仍在 Long.MAX_VALUE 前正值），Vault +200ms（跨過 Long.MAX_VALUE 成負值）-> 剩 250ms 正常 OK
            val now1 = AtomicLong(Long.MAX_VALUE - TimeUnit.MILLISECONDS.toNanos(100))
            val clock1: () -> Long = { now1.get() }
            val dns1 = HostResolver {
                now1.addAndGet(TimeUnit.MILLISECONDS.toNanos(50))
                listOf(addr("127.0.0.1"))
            }
            val vault1 = object : KeyVault {
                override suspend fun putKey(providerId: String, apiKey: CharArray) = Unit
                override suspend fun getKey(providerId: String): CharArray {
                    now1.addAndGet(TimeUnit.MILLISECONDS.toNanos(200))
                    return "sekret-token-1".toCharArray()
                }
                override suspend fun deleteKey(providerId: String) = Unit
                override suspend fun hasKey(providerId: String): Boolean = true
            }
            web.enqueue(MockResponse().setResponseCode(200).setBody(goodListBody()))
            val transport1 = McpTransport(McpTransport.defaultHttp(), testExecutor, { it.coerceIn(1L, McpTimeouts.MAX_MS) }, clock1)
            val breaker1 = McpCircuitBreaker()
            val server1 = McpServer.forTesting(
                config = McpServerConfig("s-wrap-ok", base, networkOptIn = cleartext, networkResolver = dns1),
                keyVault = vault1,
                transport = transport1,
                breaker = breaker1,
                preflightExecutor = testExecutor,
                clampTimeoutMs = { it.coerceIn(1L, McpTimeouts.MAX_MS) },
                nanoClock = clock1,
            )
            assertEquals(McpStatus.OK, server1.listTools(timeoutMs = 500L).status)
            assertEquals(1, web.requestCount)
            assertEquals(0, breaker1.failureCount())

            // 1b) 繞回後 Step 7 HTTP 超時（DNS +350ms 繞回，剩 150ms vs 2s bodyDelay）-> TIMEOUT + 1 熔斷
            val now2 = AtomicLong(Long.MAX_VALUE - TimeUnit.MILLISECONDS.toNanos(100))
            val clock2: () -> Long = { now2.get() }
            val dns2 = HostResolver {
                now2.addAndGet(TimeUnit.MILLISECONDS.toNanos(350))
                listOf(addr("127.0.0.1"))
            }
            web.enqueue(MockResponse().setResponseCode(200).setBodyDelay(2, TimeUnit.SECONDS).setBody(goodListBody()))
            val transport2 = McpTransport(McpTransport.defaultHttp(), testExecutor, { it.coerceIn(1L, McpTimeouts.MAX_MS) }, clock2)
            val breaker2 = McpCircuitBreaker()
            val server2 = McpServer.forTesting(
                config = McpServerConfig("s-wrap-to", base, networkOptIn = cleartext, networkResolver = dns2),
                keyVault = CountingVault(),
                transport = transport2,
                breaker = breaker2,
                preflightExecutor = testExecutor,
                clampTimeoutMs = { it.coerceIn(1L, McpTimeouts.MAX_MS) },
                nanoClock = clock2,
            )
            assertEquals(McpStatus.TIMEOUT, server2.listTools(timeoutMs = 500L).status)
            assertEquals(2, web.requestCount)
            assertEquals(1, breaker2.failureCount())

            // 2) 直調 McpTransport.postJson：繞回未過期成功、繞回已過期回 null 且 0 HTTP
            val now3 = AtomicLong(Long.MAX_VALUE - TimeUnit.MILLISECONDS.toNanos(100))
            val clock3: () -> Long = { now3.get() }
            val transport3 = McpTransport(McpTransport.defaultHttp(), testExecutor, { it.coerceIn(1L, McpTimeouts.MAX_MS) }, clock3)
            web.enqueue(MockResponse().setResponseCode(200).setBody(goodListBody()))
            val okWrap = transport3.postJson(
                baseUrl = base,
                bearer = "sekret",
                bodyJson = "{}",
                timeoutMs = 500L,
                optIn = cleartext,
                resolver = HostResolver {
                    now3.addAndGet(TimeUnit.MILLISECONDS.toNanos(50))
                    listOf(addr("127.0.0.1"))
                },
            )
            assertEquals(200, okWrap?.first)
            assertEquals(3, web.requestCount)

            now3.set(Long.MAX_VALUE - TimeUnit.MILLISECONDS.toNanos(100))
            val expiredWrap = transport3.postJson(
                baseUrl = base,
                bearer = "sekret",
                bodyJson = "{}",
                timeoutMs = 500L,
                optIn = cleartext,
                resolver = HostResolver {
                    now3.addAndGet(TimeUnit.MILLISECONDS.toNanos(600))
                    listOf(addr("127.0.0.1"))
                },
            )
            assertNull("繞回後已過期必須回 null", expiredWrap)
            assertEquals("繞回後已過期不得發出 HTTP", 3, web.requestCount)
        } finally {
            runCatching { web.shutdown() }
            testExecutor.shutdownNow()
            assertTrue(testExecutor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun w03SaturatedPreflightExecutorRejectsDnsFastWhileBypassingLiteralIp() = runBlocking {
        // 驗證預設共享執行器確為 SynchronousQueue + max 16 有界池
        val shared = NetworkPolicy.defaultPreflightExecutor as ThreadPoolExecutor
        assertEquals(16, shared.maximumPoolSize)
        assertTrue(shared.queue is SynchronousQueue<*>)

        // 2 條不可中斷執行緒飽和 2-slot SynchronousQueue + AbortPolicy 執行器
        val maxPool = 2
        val saturatedPool = ThreadPoolExecutor(
            0,
            maxPool,
            30L,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            ThreadFactory { r -> Thread(r, "test-w03-sat").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
        val release = AtomicBoolean(false)
        val started = CountDownLatch(maxPool)
        val finished = CountDownLatch(maxPool)
        val web = MockWebServer()
        val webV6 = MockWebServer()
        try {
            web.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
            webV6.start(InetAddress.getByName("::1"), 0)
            repeat(maxPool) {
                saturatedPool.submit {
                    started.countDown()
                    while (!release.get()) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                            // 模擬不可中斷的原生 getaddrinfo() 阻塞
                        }
                    }
                    finished.countDown()
                }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))

            // 1) DNS 名稱請求於 McpServer.listTools / callTool 快速失敗 TRANSPORT（0 vault、0 HTTP、0 熔斷）
            val vault = CountingVault()
            val breaker = McpCircuitBreaker()
            val dnsServer = McpServer(
                config = McpServerConfig(
                    id = "s-sat-dns",
                    baseUrl = "https://sat.example.com/mcp",
                    networkResolver = noDns,
                ),
                keyVault = vault,
                transport = McpTransport(),
                breaker = breaker,
                preflightExecutor = saturatedPool,
            )
            val tList = System.currentTimeMillis()
            val listGot = dnsServer.listTools()
            val listElapsed = System.currentTimeMillis() - tList
            assertEquals(McpStatus.TRANSPORT, listGot.status)
            assertTrue("過載拒收應迅速返回 (<1s)，實際 ${listElapsed}ms", listElapsed < 1_000L)
            assertEquals(0, vault.reads.get())
            assertEquals(0, web.requestCount)
            assertEquals("preflight OverloadRejected 不得計入熔斷", 0, breaker.failureCount())

            val tCall = System.currentTimeMillis()
            val callGot = dnsServer.callTool("t", "{}")
            val callElapsed = System.currentTimeMillis() - tCall
            assertEquals(McpStatus.TRANSPORT, callGot.status)
            assertTrue("callTool 過載拒收應迅速返回 (<1s)，實際 ${callElapsed}ms", callElapsed < 1_000L)
            assertEquals(0, vault.reads.get())
            assertEquals(0, web.requestCount)
            assertEquals(0, breaker.failureCount())

            // 1b) 飽和池下 callTool 非法 toolName / argumentsJson 必須在 Step 3 即回 PROTOCOL（0 vault、0 熔斷，非 TRANSPORT）
            for ((badName, badArgs) in listOf(
                "" to "{}",
                "mcp.s-sat-dns.t" to "{}",
                "t" to "not-json",
                "t" to "[1,2]",
            )) {
                val invalidCall = dnsServer.callTool(badName, badArgs)
                assertEquals(McpStatus.PROTOCOL, invalidCall.status)
                assertEquals(0, vault.reads.get())
                assertEquals(0, breaker.failureCount())
            }

            // 2) 直調 McpTransport.postJson 與 postJsonWithPreverified 回落於飽和執行器立即拋 McpPreflightOverloadedException
            val satTransport = McpTransport(
                client = McpTransport.defaultHttp(),
                preflightExecutor = saturatedPool,
                clampTimeoutMs = McpTimeouts::clamp,
                nanoClock = System::nanoTime,
            )
            val tDirect = System.currentTimeMillis()
            try {
                satTransport.postJson(
                    baseUrl = "https://sat.example.com/mcp",
                    bearer = "sekret",
                    bodyJson = "{}",
                    timeoutMs = McpTimeouts.DEFAULT_MS,
                    resolver = noDns,
                )
                fail("飽和 DNS 執行器直調 postJson 必須拋 McpPreflightOverloadedException")
            } catch (e: McpPreflightOverloadedException) {
                assertTrue(e.message.orEmpty().contains("overload"))
            }
            assertTrue(System.currentTimeMillis() - tDirect < 1_000L)

            val freshToken = (
                McpTransport().preflightMcpNetworkPolicy(
                    url = "https://good.example.com/mcp",
                    optIn = EndpointNetworkOptIn(),
                    resolver = fakeDns("good.example.com" to listOf("203.0.113.10")),
                    deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5),
                ) as McpPreflightOutcome.Allowed
            ).token
            val tFallback = System.currentTimeMillis()
            try {
                satTransport.postJsonWithPreverified(
                    baseUrl = "https://sat-mismatch.example.com/mcp",
                    bearer = "sekret",
                    bodyJson = "{}",
                    deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5),
                    optIn = EndpointNetworkOptIn(),
                    resolver = noDns,
                    preverified = freshToken,
                )
                fail("飽和 DNS 執行器 fallback 必須拋 McpPreflightOverloadedException")
            } catch (e: McpPreflightOverloadedException) {
                assertTrue(e.message.orEmpty().contains("overload"))
            }
            assertTrue(System.currentTimeMillis() - tFallback < 1_000L)

            // 已消耗權杖於同一 URL 重用亦必須回落並受飽和執行器拒收為 McpPreflightOverloadedException
            val consumedTransport = McpTransport(
                client = interceptorClient { req -> mock200JsonResponse(req, goodListBody()) },
                preflightExecutor = saturatedPool,
                clampTimeoutMs = McpTimeouts::clamp,
                nanoClock = System::nanoTime,
            )
            val singleDns = fakeDns("good.example.com" to listOf("203.0.113.10"))
            val consumedToken = (
                McpTransport().preflightMcpNetworkPolicy(
                    url = "https://good.example.com/mcp",
                    optIn = EndpointNetworkOptIn(),
                    resolver = singleDns,
                    deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5),
                ) as McpPreflightOutcome.Allowed
            ).token
            assertEquals(
                200,
                consumedTransport.postJsonWithPreverified(
                    baseUrl = "https://good.example.com/mcp",
                    bearer = "sekret",
                    bodyJson = "{}",
                    deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5),
                    optIn = EndpointNetworkOptIn(),
                    resolver = singleDns,
                    preverified = consumedToken,
                )?.first,
            )
            try {
                consumedTransport.postJsonWithPreverified(
                    baseUrl = "https://good.example.com/mcp",
                    bearer = "sekret",
                    bodyJson = "{}",
                    deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5),
                    optIn = EndpointNetworkOptIn(),
                    resolver = singleDns,
                    preverified = consumedToken,
                )
                fail("已消耗權杖重用回落遇飽和執行器必須拋 McpPreflightOverloadedException")
            } catch (e: McpPreflightOverloadedException) {
                assertTrue(e.message.orEmpty().contains("overload"))
            }

            // Step 7 拋出 McpPreflightOverloadedException 時，McpServer 必須映射 TRANSPORT 且 0 熔斷失敗
            val overloadedBreaker = McpCircuitBreaker()
            val overloadedServer = McpServer(
                config = McpServerConfig(
                    id = "s-step7-overload",
                    baseUrl = "https://example.com/mcp",
                    networkResolver = fakeDns("example.com" to listOf("203.0.113.10")),
                ),
                keyVault = CountingVault(),
                transport = McpTransport(interceptorClient { throw McpPreflightOverloadedException() }),
                breaker = overloadedBreaker,
            )
            assertEquals(McpStatus.TRANSPORT, overloadedServer.listTools().status)
            assertEquals("Step 7 McpPreflightOverloadedException 不得計熔斷", 0, overloadedBreaker.failureCount())
            assertEquals(McpStatus.TRANSPORT, overloadedServer.callTool("t", "{}").status)
            assertEquals("callTool Step 7 McpPreflightOverloadedException 不得計熔斷", 0, overloadedBreaker.failureCount())

            // 3) 飽和池繞過（requiresDnsLookup == false）：
            //    3a) http://127.0.0.1:<port>/mcp 成功（server 與直調 postJson）
            web.enqueue(MockResponse().setResponseCode(200).setBody(goodListBody()))
            web.enqueue(MockResponse().setResponseCode(200).setBody(goodCallBody()))
            web.enqueue(MockResponse().setResponseCode(200).setBody(goodListBody()))
            val literalServer = McpServer(
                config = McpServerConfig(
                    id = "s-sat-literal",
                    baseUrl = "http://127.0.0.1:${web.port}/mcp",
                    networkOptIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE),
                    networkResolver = noDns,
                ),
                keyVault = vault,
                transport = satTransport,
                breaker = breaker,
                preflightExecutor = saturatedPool,
            )
            assertEquals(McpStatus.OK, literalServer.listTools().status)
            assertEquals(McpStatus.OK, literalServer.callTool("t", "{}").status)
            val directLiteral = satTransport.postJson(
                baseUrl = "http://127.0.0.1:${web.port}/mcp",
                bearer = "sekret",
                bodyJson = "{}",
                timeoutMs = McpTimeouts.DEFAULT_MS,
                optIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE),
                resolver = noDns,
            )
            assertEquals(200, directLiteral?.first)
            assertEquals(3, web.requestCount)

            //    3b) http://[::1]:<port>/mcp 於飽和池下成功（requiresDnsLookup == false）
            webV6.enqueue(MockResponse().setResponseCode(200).setBody(goodListBody()))
            val v6Server = McpServer(
                config = McpServerConfig(
                    id = "s-sat-v6-loopback",
                    baseUrl = "http://[::1]:${webV6.port}/mcp",
                    networkOptIn = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE),
                    networkResolver = noDns,
                ),
                keyVault = vault,
                transport = satTransport,
                breaker = breaker,
                preflightExecutor = saturatedPool,
            )
            assertEquals(McpStatus.OK, v6Server.listTools().status)
            assertEquals(1, webV6.requestCount)

            //    3c) 無需 DNS 的阻擋案例於飽和池下皆回 PROTOCOL（非 OverloadRejected/TRANSPORT），0 vault、0 熔斷
            val bypassBlockedCases = listOf(
                Triple("http-localhost-https-only", "http://localhost:${web.port}/mcp", NetworkDenyCode.CLEARTEXT_NOT_PERMITTED),
                Triple("https-link-local-v6", "https://[fe80::1]/mcp", NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED),
                Triple("https-onion", "https://foo.onion/mcp", NetworkDenyCode.INVALID_HOST),
            )
            val readsBeforeBypass = vault.reads.get()
            for ((label, url, expectedCode) in bypassBlockedCases) {
                val bypassBreaker = McpCircuitBreaker()
                val bypassServer = McpServer(
                    config = McpServerConfig(
                        id = "s-$label",
                        baseUrl = url,
                        networkOptIn = EndpointNetworkOptIn(),
                        networkResolver = noDns,
                    ),
                    keyVault = vault,
                    transport = satTransport,
                    breaker = bypassBreaker,
                    preflightExecutor = saturatedPool,
                )
                val res = bypassServer.listTools()
                assertEquals("$label 必須繞過飽和池並回 PROTOCOL", McpStatus.PROTOCOL, res.status)
                assertEquals("$label denyCode", expectedCode.name, res.auditMap()["denyCode"])
                assertEquals("$label 0 breaker", 0, bypassBreaker.failureCount())
            }
            assertEquals("所有飽和池繞過阻擋不得碰 vault", readsBeforeBypass, vault.reads.get())

            // 被拒字面 IP 直調 postJson 亦走 requiresDnsLookup == false 路徑
            try {
                satTransport.postJson(
                    baseUrl = "https://10.0.0.1/mcp",
                    bearer = "sekret",
                    bodyJson = "{}",
                    timeoutMs = McpTimeouts.DEFAULT_MS,
                    resolver = noDns,
                )
                fail("literal private IP must fail policy gate without executor")
            } catch (e: McpNetworkPolicyBlocked) {
                assertEquals(NetworkDenyCode.PRIVATE_ADDRESS_BLOCKED, e.denyCode)
            }
        } finally {
            runCatching { web.shutdown() }
            runCatching { webV6.shutdown() }
            release.set(true)
            saturatedPool.shutdownNow()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertTrue(saturatedPool.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun unknownServiceExceptionCleartextMapsToProtocolWithoutBreakerWhileOtherMapsToTransportWithBreaker() = runBlocking {
        // 無需啟動 MockWebServer（全走 interceptorClient 拋出例外）；覆蓋直拋、cause 包裝、suppressed、
        // 小寫／not enabled、循環例外鏈、https:// 偽裝、null message 與一般 protocol error。
        val httpBase = "http://localhost:8080/mcp"
        val httpsBase = "https://example.com/mcp"
        val cleartext = EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE)
        val httpDns = fakeDns("localhost" to listOf("127.0.0.1"))
        val httpsDns = fakeDns("example.com" to listOf("203.0.113.10"))

        val cleartextCases = listOf<Pair<String, () -> Throwable>>(
            "direct-cleartext" to {
                UnknownServiceException("CLEARTEXT communication to localhost not permitted by network security policy")
            },
            "wrapped-cause" to {
                IOException(
                    "outer io",
                    UnknownServiceException("CLEARTEXT communication to localhost not permitted by network security policy"),
                )
            },
            "suppressed" to {
                IOException("outer io").apply {
                    addSuppressed(UnknownServiceException("CLEARTEXT communication not permitted"))
                }
            },
            "lowercase-cleartext" to {
                UnknownServiceException("cleartext communication not permitted")
            },
            "cleartext-not-enabled" to {
                UnknownServiceException("Cleartext HTTP traffic to localhost not enabled")
            },
            "cyclic-chain" to {
                val root = IOException("cycle-a")
                val mid = IOException("cycle-b", root)
                root.initCause(mid)
                mid.addSuppressed(UnknownServiceException("CLEARTEXT communication not permitted"))
                root
            },
        )
        for ((label, makeEx) in cleartextCases) {
            val breaker = McpCircuitBreaker()
            val server = McpServer(
                config = McpServerConfig(
                    id = "s-nsc-$label",
                    baseUrl = httpBase,
                    networkOptIn = cleartext,
                    networkResolver = httpDns,
                ),
                keyVault = CountingVault(),
                transport = McpTransport(interceptorClient { throw makeEx() }),
                breaker = breaker,
            )
            val listRes = server.listTools()
            assertEquals("$label listTools status", McpStatus.PROTOCOL, listRes.status)
            assertEquals("$label listTools denyCode", NetworkDenyCode.CLEARTEXT_NOT_PERMITTED.name, listRes.auditMap()["denyCode"])
            assertEquals("$label listTools 0 breaker", 0, breaker.failureCount())

            val callRes = server.callTool("t", "{}")
            assertEquals("$label callTool status", McpStatus.PROTOCOL, callRes.status)
            assertEquals("$label callTool denyCode", NetworkDenyCode.CLEARTEXT_NOT_PERMITTED.name, callRes.auditMap()["denyCode"])
            assertEquals("$label callTool 0 breaker", 0, breaker.failureCount())
        }

        // 以下情況必歸 TRANSPORT ＋ 計熔斷 1 次（含 https:// 帶 cleartext 字樣、null message、非明文錯誤）
        val transportCases = listOf<Triple<String, String, () -> Throwable>>(
            Triple("https-cleartext-msg", httpsBase) {
                UnknownServiceException("CLEARTEXT communication to example.com not permitted by network security policy")
            },
            Triple("null-message", httpBase) {
                UnknownServiceException(null as String?)
            },
            Triple("protocol-error", httpBase) {
                UnknownServiceException("protocol error")
            },
            Triple("non-policy-cleartext-word", httpBase) {
                UnknownServiceException("cleartext mismatch")
            },
            // 純循環 cause/suppressed 鏈（無明文措辭）：走訪必須因 IdentityHashMap 守卫
            // 終止於重複節點，不得無窮迴圈；無明文措辭故仍歸 TRANSPORT + 計熔斷。
            Triple("cyclic-no-cleartext", httpBase) {
                val root = IOException("cycle-a")
                val mid = IOException("cycle-b", root)
                root.initCause(mid)
                mid.addSuppressed(IOException("cycle-c"))
                root
            },
        )
        for ((label, base, makeEx) in transportCases) {
            val isHttps = base.startsWith("https://")
            val cfgOptIn = if (isHttps) EndpointNetworkOptIn() else cleartext
            val cfgDns = if (isHttps) httpsDns else httpDns
            val transport = McpTransport(interceptorClient { throw makeEx() })

            val breakerList = McpCircuitBreaker()
            val serverList = McpServer(
                config = McpServerConfig("s-tr-list-$label", base, networkOptIn = cfgOptIn, networkResolver = cfgDns),
                keyVault = CountingVault(),
                transport = transport,
                breaker = breakerList,
            )
            val listRes = serverList.listTools()
            assertEquals("$label listTools status", McpStatus.TRANSPORT, listRes.status)
            assertNull("$label listTools denyCode", listRes.auditMap()["denyCode"])
            assertEquals("$label listTools 1 breaker", 1, breakerList.failureCount())

            val breakerCall = McpCircuitBreaker()
            val serverCall = McpServer(
                config = McpServerConfig("s-tr-call-$label", base, networkOptIn = cfgOptIn, networkResolver = cfgDns),
                keyVault = CountingVault(),
                transport = transport,
                breaker = breakerCall,
            )
            val callRes = serverCall.callTool("t", "{}")
            assertEquals("$label callTool status", McpStatus.TRANSPORT, callRes.status)
            assertNull("$label callTool denyCode", callRes.auditMap()["denyCode"])
            assertEquals("$label callTool 1 breaker", 1, breakerCall.failureCount())
        }
    }
}
