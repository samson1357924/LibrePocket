package dev.librepocket.mcp

import dev.librepocket.keystore.KeyVault
import dev.librepocket.policy.EndpointNetworkOptIn
import dev.librepocket.policy.HostResolver
import dev.librepocket.policy.NetworkDenyCode
import dev.librepocket.policy.NetworkEgressPolicy
import dev.librepocket.policy.NetworkOptInStore
import dev.librepocket.policy.PinnedNetworkDns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        web.start()
        try {
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

    // ---- 負向：redirect 永不跟隨 ----

    @Test fun redirectToHttpTargetIsNeverFollowed() = runBlocking {
        val origin = MockWebServer()
        val target = MockWebServer()
        origin.start()
        target.start()
        try {
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
            assertEquals("跳轉目標不得收到任何請求", 0, target.requestCount)
            assertEquals("origin 恰一發（不跟隨、不重送）", 1, origin.requestCount)
            assertEquals(1, breaker.failureCount())
        } finally {
            origin.shutdown()
            target.shutdown()
        }
    }

    // ---- opt-in 綁定變更後舊 confirm 失效 ----

    @Test fun optInBindingInvalidatedOnChange() = runBlocking {
        val web1 = MockWebServer()
        val web2 = MockWebServer()
        web1.start()
        web2.start()
        try {
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
            web1.shutdown()
            web2.shutdown()
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
        web.start()
        try {
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
        // 接線測試（非 PinnedNetworkDns 單元）：delegate 在 server 預驗後翻轉
        // （首回迴環放行 → 次回私有），server→transport 經 preverifiedEval 單次
        // 解析接線——transport 不再查 DNS，建連仍走首回釘住地址，且熔斷不計數。
        val web = MockWebServer()
        web.start()
        try {
            web.enqueue(MockResponse().setResponseCode(200).setBody(goodListBody()))
            val base = web.url("/mcp").toString()
            var resolves = 0
            var first: List<InetAddress>? = null
            val skewDelegate = HostResolver { _ ->
                resolves++
                if (resolves == 1) {
                    listOf(addr("127.0.0.1")).also { first = it }
                } else {
                    listOf(addr("10.0.0.1"))
                }
            }
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
                transport = McpTransport(),
                breaker = breaker,
            )
            val got = withTimeoutOrNull(5_000) { server.listTools() }
            assertNotNull("5s 內未回傳", got)
            assertEquals("skew 下仍須連首回釘住地址成功", McpStatus.OK, got!!.status)
            assertEquals("恰一發（建連走 pin，未跟隨／重送）", 1, web.requestCount)
            assertEquals("單次解析：transport 不得二次查 DNS", 1, resolves)
            assertEquals("阻擋未觸發即無 denyCode", null, got.auditMap()["denyCode"])
            assertEquals("成功不得計熔斷", 0, breaker.failureCount())
            // 同狀態平行 pin 證 rebind 確實發生（delegate 已翻轉）：建連成功與
            // 此處 hasRebind==true 並存，即「釘住首回、偵測到變化」。
            val parallel = PinnedNetworkDns.forHost("localhost", first!!, skewDelegate)
            assertEquals(true, parallel.hasRebind("localhost"))
        } finally {
            web.shutdown()
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
}
