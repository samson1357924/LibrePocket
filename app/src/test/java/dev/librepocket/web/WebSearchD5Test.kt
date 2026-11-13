package dev.librepocket.web

import dev.librepocket.keystore.KeyVault
import dev.librepocket.provider.ServerToolTranscript
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D5 provider-side fixture tests（#138 D5；全 fake：fake KeyVault＋fake HTTP，零真網）。
 *
 * - keyed 命中（四家解析＋auth 形狀）、缺 key `Unavailable(MISSING_KEY)` 零網路、
 *   AUTH／PROTOCOL 不 fallback（不靜默換，不升 hosted）。
 * - keyId 綁定 providerId＋origin（endpoint 切換不可沿用舊 key）。
 * - cache＋single-flight（經 [WebSearchService] 包裝）。
 * - hosted 優先＋transcript 標記、本地/hosted 互斥、ServerTools 鏡像一致性。
 * - 本檔不碰 ToolRegistry／executionReady（D3 UI wiring 留待 #165 後）。
 */
class WebSearchD5Test {

    private class FakeVault : KeyVault {
        val keys = mutableMapOf<String, CharArray>()
        val seenGet = mutableListOf<String>()
        val seenHas = mutableListOf<String>()
        var failReads = false

        override suspend fun putKey(providerId: String, apiKey: CharArray) {
            keys[providerId] = apiKey.copyOf()
        }

        override suspend fun getKey(providerId: String): CharArray? {
            seenGet.add(providerId)
            if (failReads) throw IllegalStateException("vault down")
            return keys[providerId]?.copyOf()
        }

        override suspend fun deleteKey(providerId: String) {
            keys.remove(providerId)
        }

        override suspend fun hasKey(providerId: String): Boolean {
            seenHas.add(providerId)
            return keys.containsKey(providerId)
        }
    }

    private class FakeGet(
        val handler: suspend (url: String) -> WebHttpResponse,
    ) : WebHttpGet {
        val calls = AtomicInteger(0)
        var lastUrl: String? = null
        var lastHeaders: Map<String, String>? = null
        override suspend fun get(url: String, headers: Map<String, String>): WebHttpResponse {
            calls.incrementAndGet()
            lastUrl = url
            lastHeaders = headers
            return handler(url)
        }
    }

    private class FakePost(
        val handler: suspend (url: String, body: String) -> WebHttpResponse,
    ) : WebHttpPost {
        val calls = AtomicInteger(0)
        var lastUrl: String? = null
        var lastBody: String? = null
        var lastHeaders: Map<String, String>? = null
        override suspend fun post(url: String, body: String, headers: Map<String, String>): WebHttpResponse {
            calls.incrementAndGet()
            lastUrl = url
            lastBody = body
            lastHeaders = headers
            return handler(url, body)
        }
    }

    private val braveJson = """
        {"web":{"results":[
          {"title":"Kotlin Docs","url":"https://kotlinlang.org/","description":"Modern language","published_date":"2024-01-02"},
          {"title":"No URL","description":"skip me"},
          {"url":"https://example.com/plain"},
          {"title":"ftp pick","url":"ftp://example.com/x","description":"skip scheme"}
        ]}}
    """.trimIndent()

    private val tavilyJson = """
        {"results":[
          {"title":"T1","url":"https://example.com/t1","content":" Tavily snippet ","published_date":"2024-05-01"},
          {"title":"Bad","url":"notaurl","content":"skip me"}
        ],"answer":"ignored"}
    """.trimIndent()

    private val exaJson = """
        {"results":[
          {"title":"E1","url":"https://example.com/e1","text":"Exa long text","publishedDate":"2024-06-01"}
        ]}
    """.trimIndent()

    private val firecrawlJson = """
        {"success":true,"data":[
          {"title":"F1","url":"https://example.com/f1","description":"Firecrawl summary","markdown":"# long markdown"},
          {"title":"F2","url":"https://example.com/f2","markdown":"# only markdown"}
        ]}
    """.trimIndent()

    private fun ok(code: Int = 200, body: String): WebHttpResponse = WebHttpResponse(code, body)

    // ---- keyed 命中 ----

    @Test fun braveSearchHit() = runBlocking {
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "k-brave".toCharArray())
        val http = FakeGet { ok(body = braveJson) }
        val p = BraveSearchProvider(vault, http)
        assertEquals("brave", p.id)
        assertEquals(setOf(WebCapability.REQUIRES_KEY), p.capabilities)
        assertTrue(p.probe() is WebProbe.Ready)

        val out = p.search("kotlin", 5)
        assertTrue(out is WebSearchOutcome.Ok)
        val results = (out as WebSearchOutcome.Ok).results
        assertEquals(2, results.size)
        assertEquals("Kotlin Docs", results[0].title)
        assertEquals("https://kotlinlang.org/", results[0].url)
        assertEquals("Modern language", results[0].snippet)
        assertEquals("2024-01-02", results[0].published)
        assertEquals("kotlinlang.org", results[0].siteName)
        // 無 description 標題回退 URL；cached=false（裸 delegate，未經 Service）。
        assertEquals("https://example.com/plain", results[1].title)
        assertFalse(out.cached)
        // URL 形狀＋auth header。
        assertTrue(http.lastUrl!!.startsWith(BraveSearchProvider.ENDPOINT + "?q="))
        assertTrue(http.lastUrl!!.contains("q=kotlin"))
        assertEquals("k-brave", http.lastHeaders!!["X-Subscription-Token"])
        assertEquals(listOf(BraveSearchProvider.DEFAULT_KEY_ID), vault.seenGet)
    }

    @Test fun tavilySearchHit() = runBlocking {
        val vault = FakeVault()
        vault.putKey(TavilySearchProvider.DEFAULT_KEY_ID, "k-tavily".toCharArray())
        val http = FakePost { _, _ -> ok(body = tavilyJson) }
        val p = TavilySearchProvider(vault, http)
        val out = p.search("kotlin", 5)
        assertTrue(out is WebSearchOutcome.Ok)
        val results = (out as WebSearchOutcome.Ok).results
        assertEquals(1, results.size)
        assertEquals("T1", results[0].title)
        assertEquals("Tavily snippet", results[0].snippet)
        assertEquals("2024-05-01", results[0].published)
        assertEquals(TavilySearchProvider.ENDPOINT, http.lastUrl)
        assertEquals("application/json", http.lastHeaders!!["Content-Type"])
        assertTrue(http.lastBody!!.contains("\"query\":\"kotlin\""))
        assertTrue(http.lastBody!!.contains("\"max_results\":5"))
        assertTrue(http.lastBody!!.contains("\"api_key\":\"k-tavily\""))
    }

    @Test fun exaSearchHit() = runBlocking {
        val vault = FakeVault()
        vault.putKey(ExaSearchProvider.DEFAULT_KEY_ID, "k-exa".toCharArray())
        val http = FakePost { _, _ -> ok(body = exaJson) }
        val p = ExaSearchProvider(vault, http)
        val out = p.search("kotlin", 5)
        assertTrue(out is WebSearchOutcome.Ok)
        val results = (out as WebSearchOutcome.Ok).results
        assertEquals(1, results.size)
        assertEquals("E1", results[0].title)
        assertEquals("Exa long text", results[0].snippet)
        assertEquals("2024-06-01", results[0].published)
        assertEquals("k-exa", http.lastHeaders!!["x-api-key"])
        assertTrue(http.lastBody!!.contains("\"numResults\":5"))
    }

    @Test fun firecrawlSearchHit() = runBlocking {
        val vault = FakeVault()
        vault.putKey(FirecrawlSearchProvider.DEFAULT_KEY_ID, "k-fire".toCharArray())
        val http = FakePost { _, _ -> ok(body = firecrawlJson) }
        val p = FirecrawlSearchProvider(vault, http)
        val out = p.search("kotlin", 5)
        assertTrue(out is WebSearchOutcome.Ok)
        val results = (out as WebSearchOutcome.Ok).results
        assertEquals(2, results.size)
        assertEquals("Firecrawl summary", results[0].snippet)
        // description 缺 → markdown 回退。
        assertEquals("# only markdown", results[1].snippet)
        assertEquals("Bearer k-fire", http.lastHeaders!!["Authorization"])
        assertTrue(http.lastBody!!.contains("\"limit\":5"))
    }

    @Test fun firecrawlExplicitFailureIsSupplierError() = runBlocking {
        val vault = FakeVault()
        vault.putKey(FirecrawlSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        val http = FakePost { _, _ -> ok(body = """{"success":false,"error":"quota"}""") }
        val out = FirecrawlSearchProvider(vault, http).search("q", 5)
        assertTrue(out is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.SUPPLIER_ERROR, (out as WebSearchOutcome.Failed).reason)
    }

    // ---- 缺 key → Unavailable，零網路 ----

    @Test fun missingKeyIsUnavailableZeroNetwork() = runBlocking {
        val vault = FakeVault() // 空 vault
        val httpGet = FakeGet { ok(body = braveJson) }
        val httpPost = FakePost { _, _ -> ok(body = tavilyJson) }
        val providers = defaultKeyedProviders(vault, httpGet, httpPost)
        for (p in providers) {
            val out = p.search("kotlin", 5)
            assertTrue(p.id + " must be Unavailable", out is WebSearchOutcome.Unavailable)
            assertEquals(p.id, WebUnavailableReason.MISSING_KEY, (out as WebSearchOutcome.Unavailable).reason)
            assertTrue(p.id + " probe must be NotReady", p.probe() is WebProbe.NotReady)
        }
        assertEquals(0, httpGet.calls.get())
        assertEquals(0, httpPost.calls.get())
    }

    @Test fun vaultReadFailureIsFailClosed() = runBlocking {
        val vault = FakeVault()
        vault.failReads = true
        val p = BraveSearchProvider(vault, FakeGet { ok(body = braveJson) })
        val out = p.search("kotlin", 5)
        assertTrue(out is WebSearchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.MISSING_KEY, (out as WebSearchOutcome.Unavailable).reason)
        assertTrue(p.probe() is WebProbe.NotReady)
    }

    // ---- AUTH／PROTOCOL 不 fallback ----

    @Test fun authNeverFallsBack() = runBlocking {
        for (code in listOf(401, 403)) {
            val vault = FakeVault()
            val httpGet = FakeGet { ok(code, "{}") }
            val httpPost = FakePost { _, _ -> ok(code, "{}") }
            for (p in defaultKeyedProviders(vault, httpGet, httpPost)) {
                vault.putKey(defaultKeyIdOf(p.id), "k".toCharArray())
                val out = p.search("q", 5)
                assertTrue("${p.id}/$code must be Failed", out is WebSearchOutcome.Failed)
                val failed = out as WebSearchOutcome.Failed
                assertEquals(p.id, WebFailureReason.AUTH, failed.reason)
                assertFalse(p.id, failed.fallbackAllowed)
                assertFalse(p.id, WebSearchSelector.mayFallback(null, failed))
                assertFalse(p.id, WebSearchSelector.mayFallback(p.id, failed))
            }
        }
    }

    @Test fun protocolNeverFallsBack() = runBlocking {
        val vault = FakeVault()
        val httpGet = FakeGet { ok(400, "{}") }
        val httpPost = FakePost { _, _ -> ok(400, "{}") }
        for (p in defaultKeyedProviders(vault, httpGet, httpPost)) {
            vault.putKey(defaultKeyIdOf(p.id), "k".toCharArray())
            val out = p.search("q", 5)
            assertTrue(p.id, out is WebSearchOutcome.Failed)
            val failed = out as WebSearchOutcome.Failed
            assertEquals(p.id, WebFailureReason.PROTOCOL, failed.reason)
            assertFalse(p.id, WebSearchSelector.mayFallback(null, failed))
        }
    }

    @Test fun malformedBodyIsBadResponse() = runBlocking {
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        val out = BraveSearchProvider(vault, FakeGet { ok(body = "not json{{") }).search("q", 5)
        assertTrue(out is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.BAD_RESPONSE, (out as WebSearchOutcome.Failed).reason)
    }

    // ---- keyId 綁定 providerId＋origin ----

    private fun defaultKeyIdOf(id: String): String = when (id) {
        BraveSearchProvider.ID -> BraveSearchProvider.DEFAULT_KEY_ID
        TavilySearchProvider.ID -> TavilySearchProvider.DEFAULT_KEY_ID
        ExaSearchProvider.ID -> ExaSearchProvider.DEFAULT_KEY_ID
        FirecrawlSearchProvider.ID -> FirecrawlSearchProvider.DEFAULT_KEY_ID
        else -> throw IllegalArgumentException("unknown keyed id: $id")
    }

    @Test fun defaultKeyIdsBindProviderAndOrigin() {
        assertEquals("web.brave@https://api.search.brave.com/res/v1/web/search", BraveSearchProvider.DEFAULT_KEY_ID)
        assertEquals("web.tavily@https://api.tavily.com/search", TavilySearchProvider.DEFAULT_KEY_ID)
        assertEquals("web.exa@https://api.exa.ai/search", ExaSearchProvider.DEFAULT_KEY_ID)
        assertEquals("web.firecrawl@https://api.firecrawl.dev/v1/search", FirecrawlSearchProvider.DEFAULT_KEY_ID)
    }

    @Test fun endpointSwitchNeverReusesOldKey() = runBlocking {
        val vault = FakeVault()
        // vault 只有舊 endpoint 的 key；新 keyId 查無 → MISSING_KEY，零網路。
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "old".toCharArray())
        val http = FakeGet { ok(body = braveJson) }
        val moved = BraveSearchProvider(
            vault,
            http,
            keyId = "web.brave@https://search.example.invalid",
        )
        val out = moved.search("kotlin", 5)
        assertTrue(out is WebSearchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.MISSING_KEY, (out as WebSearchOutcome.Unavailable).reason)
        assertEquals(0, http.calls.get())
        assertEquals(listOf("web.brave@https://search.example.invalid"), vault.seenGet)
    }

    // ---- cache＋single-flight（經 WebSearchService） ----

    @Test fun serviceCacheAndSingleFlight() = runBlocking {
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        val http = FakeGet { ok(body = braveJson) }
        val svc = WebSearchService(BraveSearchProvider(vault, http))
        val first = svc.search("kotlin", 5)
        assertTrue(first is WebSearchOutcome.Ok)
        assertFalse((first as WebSearchOutcome.Ok).cached)
        val second = svc.search("kotlin", 5)
        assertTrue(second is WebSearchOutcome.Ok)
        assertTrue((second as WebSearchOutcome.Ok).cached)
        assertEquals(1, http.calls.get())

        // 併發同 query 合流為一次 upstream。
        svc.invalidate()
        http.calls.set(0)
        val jobs = List(8) { async { svc.search("kotlin", 5) } }
        val outs = jobs.map { it.await() }
        assertTrue(outs.all { it is WebSearchOutcome.Ok })
        assertEquals(1, http.calls.get())
    }

    // ---- 預設註冊 ----

    @Test fun defaultFactoryRegistryOk() {
        val vault = FakeVault()
        val providers = defaultKeyedProviders(vault, FakeGet { ok(body = "{}") }, FakePost { _, _ -> ok(body = "{}") })
        assertEquals(
            listOf("brave", "tavily", "exa", "firecrawl"),
            providers.map { it.id },
        )
        assertTrue(providers.all { WebCapability.REQUIRES_KEY in it.capabilities })
        // Registry fail-fast 無重複 id。
        val registry = WebSearchRegistry(providers)
        assertEquals(4, registry.size)
    }

    // ---- hosted 優先＋標記、互斥、鏡像一致性 ----

    private fun registryOf(vararg ids: String): WebSearchRegistry {
        val vault = FakeVault()
        val get = FakeGet { ok(body = braveJson) }
        val post = FakePost { _, _ -> ok(body = tavilyJson) }
        val all = defaultKeyedProviders(vault, get, post)
        return WebSearchRegistry(all.filter { it.id in ids })
    }

    @Test fun hostedMarkerMirrorsServerTools() {
        assertEquals(WebSearchSelector.HOSTED_TOOL_NAME, ServerToolTranscript.WEB_SEARCH_NAME)
        for (state in listOf("completed", "failed")) {
            assertEquals(
                ServerToolTranscript.webSearchMarker(state),
                WebSearchDispatch.hostedMarker(state),
            )
        }
    }

    @Test fun hostedPreferredMarkedExclusive() = runBlocking {
        val registry = registryOf("brave")
        var hostedCalls = 0
        var localCalls = 0
        val out = WebSearchDispatch.search(
            pinnedId = null,
            hostedAvailable = true,
            registry = registry,
            query = "kotlin",
            count = 5,
            runHosted = { hostedCalls++; "completed" },
            runLocal = { localCalls++; WebSearchOutcome.Ok(emptyList()) },
        )
        assertTrue(out is WebDispatchOutcome.Hosted)
        assertEquals(
            ServerToolTranscript.webSearchMarker("completed"),
            (out as WebDispatchOutcome.Hosted).transcriptMarker,
        )
        assertEquals(1, hostedCalls)
        assertEquals(0, localCalls) // 不可同時送兩者
    }

    @Test fun pinnedLocalNeverHosts() = runBlocking {
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        val http = FakeGet { ok(body = braveJson) }
        val registry = WebSearchRegistry(listOf(BraveSearchProvider(vault, http)))
        var hostedCalls = 0
        val out = WebSearchDispatch.search(
            pinnedId = "brave",
            hostedAvailable = true,
            registry = registry,
            query = "kotlin",
            count = 5,
            isReady = { true },
            runHosted = { hostedCalls++; "completed" },
            runLocal = { p -> WebSearchService(p).search("kotlin", 5) },
        )
        assertTrue(out is WebDispatchOutcome.Local)
        val local = out as WebDispatchOutcome.Local
        assertEquals("brave", local.providerId)
        assertTrue(local.outcome is WebSearchOutcome.Ok)
        assertEquals(0, hostedCalls)
    }

    @Test fun unavailableInvokesNeither() = runBlocking {
        val registry = registryOf("brave")
        var hostedCalls = 0
        var localCalls = 0
        suspend fun probe(pinned: String?, query: String): WebDispatchOutcome {
            hostedCalls = 0
            localCalls = 0
            return WebSearchDispatch.search(
                pinnedId = pinned,
                hostedAvailable = true,
                registry = registry,
                query = query,
                count = 5,
                isReady = { false },
                runHosted = { hostedCalls++; "completed" },
                runLocal = { localCalls++; WebSearchOutcome.Ok(emptyList()) },
            )
        }
        // pinned 缺失：即使 hosted 可用也不升 hosted、不跑本地。
        val missing = probe("nope", "kotlin")
        assertTrue(missing is WebDispatchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.PINNED_UNAVAILABLE, (missing as WebDispatchOutcome.Unavailable).reason)
        assertEquals(0, hostedCalls)
        assertEquals(0, localCalls)
        // 空 query：兩 runner 皆不呼叫。
        val empty = probe(null, "   ")
        assertTrue(empty is WebDispatchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.EMPTY_QUERY, (empty as WebDispatchOutcome.Unavailable).reason)
        assertEquals(0, hostedCalls)
        assertEquals(0, localCalls)
    }

    @Test fun unpinnedNoHostedRunsLocalOnly() = runBlocking {
        val registry = registryOf("brave", "tavily")
        var hostedCalls = 0
        val seen = mutableListOf<String>()
        val out = WebSearchDispatch.search(
            pinnedId = null,
            hostedAvailable = false,
            registry = registry,
            query = "kotlin",
            count = 5,
            isReady = { it.id == "brave" },
            runHosted = { hostedCalls++; "completed" },
            runLocal = { p -> seen.add(p.id); WebSearchOutcome.Ok(emptyList()) },
        )
        assertTrue(out is WebDispatchOutcome.Local)
        assertEquals("brave", (out as WebDispatchOutcome.Local).providerId)
        assertEquals(listOf("brave"), seen)
        assertEquals(0, hostedCalls)
    }
}
