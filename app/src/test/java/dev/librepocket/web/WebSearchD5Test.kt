package dev.librepocket.web

import dev.librepocket.keystore.KeyVault
import dev.librepocket.provider.ServerToolTranscript
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * D5 provider-side fixture tests（#138 D5；全 fake：fake KeyVault＋fake HTTP，零真網）。
 *
 * - keyed 命中（四家解析＋auth 形狀）、缺 key `Unavailable(MISSING_KEY)` 零網路、
 *   AUTH／PROTOCOL 不 fallback（不靜默換，不升 hosted）。
 * - keyId 綁定 providerId＋endpoint（建構期 fail-fast：任何非綁定 keyId 拋 IAE，
 *   含 foreign 命名空間／跨 provider／自訂 endpoint）。
 * - cache＋single-flight（經 [WebSearchService] 包裝；含 latch barrier 的
 *   single-flight 合流／leader·follower 取消語義測試）。
 * - keyed 路徑 CharArray 抹除（[TrackingVault]：getKey 直接參照回傳，
 *   成功／HTTP 500／timeout／blank key 皆驗 buffer 已 fill('\u0000')）。
 * - hosted 優先＋transcript 標記、本地/hosted 互斥、ServerTools 鏡像一致性。
 * - 本檔不碰 ToolRegistry／executionReady（D3 UI wiring 留待 #165 後）。
 */
class WebSearchD5Test {

    private class FakeVault : KeyVault {
        val keys = mutableMapOf<String, CharArray>()
        val seenGet = mutableListOf<String>()
        val seenHas = mutableListOf<String>()
        var failReads = false
        var failHasKey = false

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
            if (failHasKey) throw IllegalStateException("hasKey down")
            return keys.containsKey(providerId)
        }
    }

    /**
     * FakeVault that fails the first `failRemaining` getKey calls with
     * IllegalStateException (transient vault outage), copying out like
     * [FakeVault] once the failure budget is exhausted.
     */
    private class FlakyProbeVault : KeyVault {
        val keys = mutableMapOf<String, CharArray>()
        var failRemaining: Int = 0

        override suspend fun putKey(providerId: String, apiKey: CharArray) {
            keys[providerId] = apiKey.copyOf()
        }

        override suspend fun getKey(providerId: String): CharArray? {
            if (failRemaining > 0) {
                failRemaining--
                throw IllegalStateException("vault down")
            }
            return keys[providerId]?.copyOf()
        }

        override suspend fun deleteKey(providerId: String) {
            keys.remove(providerId)
        }

        override suspend fun hasKey(providerId: String): Boolean = keys.containsKey(providerId)
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
          {"title":"Kotlin Docs","url":"https://kotlinlang.org/","description":"Modern language","page_age":"2024-01-02"},
          {"title":"No URL","description":"skip me"},
          {"url":"https://example.com/plain"},
          {"title":"ftp pick","url":"ftp://example.com/x","description":"skip scheme"},
          {"title":"userinfo pick","url":"https://user:pass@example.com/u","description":"skip userinfo"},
          {"title":"enc userinfo pick","url":"https://user%40pass@example.com/u","description":"skip encoded userinfo"},
          {"title":"no host pick","url":"https://","description":"skip no host"},
          {"title":"Upper Pick","url":"HTTPS://EXAMPLE.COM/UPPER","description":"Upper case ok"},
          {"title":"v6 Pick","url":"https://[::1]/v6","description":"IPv6 literal ok"},
          {"title":"backslash pick","url":"https://evil.example\\@trusted.example/","description":"skip backslash"},
          {"title":"underscore pick","url":"https://foo_bar.example.com/x","description":"skip no host"}
        ]}}
    """.trimIndent()

    private val tavilyJson = """
        {"results":[
          {"title":"T1","url":"https://example.com/t1","content":" Tavily snippet ","published_date":"2024-05-01"},
          {"title":"Bad","url":"notaurl","content":"skip me"}
        ],"answer":"ignored"}
    """.trimIndent()

    // tavily 多筆 fixture（既有 tavilyJson 僅 1 筆 valid hit 且不動既有斷言）。
    // 注意：count=2 欲驗 truncated 需 valid hits > count，故需 3 筆 valid hits
    // （T2、T3 皆接在既有條目之後）；T1／Bad 順序與 tavilyJson 一致。
    private val tavilyJsonMulti = """
        {"results":[
          {"title":"T1","url":"https://example.com/t1","content":" Tavily snippet ","published_date":"2024-05-01"},
          {"title":"Bad","url":"notaurl","content":"skip me"},
          {"title":"T2","url":"https://example.com/t2","content":"Second hit"},
          {"title":"T3","url":"https://example.com/t3","content":"Third hit"}
        ],"answer":"ignored"}
    """.trimIndent()

    private val exaJson = """
        {"results":[
          {"title":"E1","url":"https://example.com/e1","highlights":["Highlighted snippet text"],"publishedDate":"2024-06-01"},
          {"title":"E2","url":"https://example.com/e2","text":"Exa text fallback","publishedDate":"2024-06-02"},
          {"title":"E3","url":"https://example.com/e3","highlights":["a","","b"],"text":"fallback ignored"},
          {"title":"E4","url":"https://example.com/e4","highlights":[],"text":"Empty highlights fallback"},
          {"title":"E5","url":"https://example.com/e5","highlights":[true],"text":"Non-string highlights fallback"}
        ]}
    """.trimIndent()

    private val firecrawlJson = """
        {"success":true,"data":{"web":[
          {"title":"F1","url":"https://example.com/f1","description":"Firecrawl summary"},
          {"title":"F2","url":"https://example.com/f2","markdown":"# only markdown"}
        ]}}
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
        assertEquals(4, results.size)
        assertEquals("Kotlin Docs", results[0].title)
        assertEquals("https://kotlinlang.org/", results[0].url)
        assertEquals("Modern language", results[0].snippet)
        assertEquals("2024-01-02", results[0].published)
        assertEquals("kotlinlang.org", results[0].siteName)
        // 無 description 標題回退 URL；cached=false（裸 delegate，未經 Service）。
        assertEquals("https://example.com/plain", results[1].title)
        // scheme/host 大小寫不拘 → 接受；IPv6 literal → 接受。
        assertEquals("HTTPS://EXAMPLE.COM/UPPER", results[2].url)
        assertEquals("https://[::1]/v6", results[3].url)
        // userinfo（含編碼 userinfo）／無 host／backslash host／`_` host 條目皆被跳過。
        assertTrue(results.none { it.url.contains("user:pass") })
        assertTrue(results.none { it.url.contains("user%40pass") })
        assertTrue(results.none { it.url == "https://" })
        assertTrue(results.none { '\\' in it.url })
        assertTrue(results.none { it.url.contains("foo_bar") })
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
        assertEquals("Bearer k-tavily", http.lastHeaders!!["Authorization"])
        assertTrue(http.lastBody!!.contains("\"query\":\"kotlin\""))
        assertTrue(http.lastBody!!.contains("\"max_results\":5"))
        assertTrue(http.lastBody!!.contains("\"include_answer\":false"))
        assertFalse(http.lastBody!!.contains("api_key"))
    }

    @Test fun exaSearchHit() = runBlocking {
        val vault = FakeVault()
        vault.putKey(ExaSearchProvider.DEFAULT_KEY_ID, "k-exa".toCharArray())
        val http = FakePost { _, _ -> ok(body = exaJson) }
        val p = ExaSearchProvider(vault, http)
        val out = p.search("kotlin", 5)
        assertTrue(out is WebSearchOutcome.Ok)
        val results = (out as WebSearchOutcome.Ok).results
        assertEquals(5, results.size)
        assertEquals("E1", results[0].title)
        assertEquals("Highlighted snippet text", results[0].snippet)
        assertEquals("2024-06-01", results[0].published)
        // highlights 缺 → text fallback；空字串項被濾掉；highlights:[] 與非字串項皆視同無 highlights → text fallback。
        assertEquals("Exa text fallback", results[1].snippet)
        assertEquals("a b", results[2].snippet)
        assertEquals("Empty highlights fallback", results[3].snippet)
        assertEquals("Non-string highlights fallback", results[4].snippet)
        assertEquals("k-exa", http.lastHeaders!!["x-api-key"])
        assertTrue(http.lastBody!!.contains("\"numResults\":5"))
        assertTrue(http.lastBody!!.contains("\"contents\""))
        assertTrue(http.lastBody!!.contains("highlights"))
        assertTrue(http.lastBody!!.contains("\"contents\":{\"highlights\":true}"))
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
        // F2 行使 markdown 回退（description 缺席 → markdown；description→markdown→snippet 優先序）。
        assertEquals("# only markdown", results[1].snippet)
        assertEquals(FirecrawlSearchProvider.ENDPOINT, http.lastUrl)
        assertEquals("Bearer k-fire", http.lastHeaders!!["Authorization"])
        assertTrue(http.lastBody!!.contains("\"limit\":5"))
    }

    @Test fun firecrawlSuccessAsStringIsBadResponse() = runBlocking {
        val vault = FakeVault()
        vault.putKey(FirecrawlSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        val http = FakePost { _, _ -> ok(body = """{"success":"yes","data":{"web":[{"title":"X","url":"https://example.com/"}]}}""") }
        val out = FirecrawlSearchProvider(vault, http).search("q", 5)
        assertTrue(out is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.BAD_RESPONSE, (out as WebSearchOutcome.Failed).reason)
    }

    @Test fun firecrawlSuccessStringTrueIsBadResponse() = runBlocking {
        val vault = FakeVault()
        vault.putKey(FirecrawlSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        val http = FakePost { _, _ -> ok(body = """{"success":"true","data":{"web":[{"title":"X","url":"https://example.com/"}]}}""") }
        val out = FirecrawlSearchProvider(vault, http).search("q", 5)
        assertTrue(out is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.BAD_RESPONSE, (out as WebSearchOutcome.Failed).reason)
    }

    @Test fun firecrawlSuccessStringFalseIsBadResponse() = runBlocking {
        val vault = FakeVault()
        vault.putKey(FirecrawlSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        val http = FakePost { _, _ -> ok(body = """{"success":"false"}""") }
        val out = FirecrawlSearchProvider(vault, http).search("q", 5)
        assertTrue(out is WebSearchOutcome.Failed)
        // 字串 "false" 不得落入 booleanOrNull==false → SUPPLIER_ERROR 路徑。
        assertEquals(WebFailureReason.BAD_RESPONSE, (out as WebSearchOutcome.Failed).reason)
    }

    @Test fun firecrawlSuccessNullIsBadResponse() = runBlocking {
        val vault = FakeVault()
        vault.putKey(FirecrawlSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        val http = FakePost { _, _ -> ok(body = """{"success":null}""") }
        val out = FirecrawlSearchProvider(vault, http).search("q", 5)
        assertTrue(out is WebSearchOutcome.Failed)
        // JsonNull 亦為 JsonPrimitive 且 booleanOrNull==null → fail-closed BAD_RESPONSE。
        assertEquals(WebFailureReason.BAD_RESPONSE, (out as WebSearchOutcome.Failed).reason)
    }

    @Test fun firecrawlExplicitFailureIsSupplierError() = runBlocking {
        val vault = FakeVault()
        vault.putKey(FirecrawlSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        val http = FakePost { _, _ -> ok(body = """{"success":false,"error":"quota"}""") }
        val out = FirecrawlSearchProvider(vault, http).search("q", 5)
        assertTrue(out is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.SUPPLIER_ERROR, (out as WebSearchOutcome.Failed).reason)
    }

    @Test fun firecrawlFlatV1ShapeIsBadResponse() = runBlocking {
        val vault = FakeVault()
        vault.putKey(FirecrawlSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        val http = FakePost { _, _ -> ok(body = """{"success":true,"data":[{"url":"https://example.com/x"}]}""") }
        val out = FirecrawlSearchProvider(vault, http).search("q", 5)
        assertTrue(out is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.BAD_RESPONSE, (out as WebSearchOutcome.Failed).reason)
    }

    @Test fun firecrawlDataMissingWebIsBadResponse() = runBlocking {
        val vault = FakeVault()
        vault.putKey(FirecrawlSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        val http = FakePost { _, _ -> ok(body = """{"success":true,"data":{"other":[]}}""") }
        val out = FirecrawlSearchProvider(vault, http).search("q", 5)
        assertTrue(out is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.BAD_RESPONSE, (out as WebSearchOutcome.Failed).reason)
    }

    // ---- Firecrawl parser 級（parseFirecrawlDataForTest internal 測試 hook） ----

    @Test fun parseFirecrawlDataFlatV1ReturnsNull() {
        val p = FirecrawlSearchProvider(FakeVault(), FakePost { _, _ -> ok(body = "{}") })
        assertNull(p.parseFirecrawlDataForTest("""{"success":true,"data":[{"url":"https://example.com/x"}]}"""))
    }

    @Test fun parseFirecrawlDataMissingWebReturnsNull() {
        val p = FirecrawlSearchProvider(FakeVault(), FakePost { _, _ -> ok(body = "{}") })
        assertNull(p.parseFirecrawlDataForTest("""{"success":true,"data":{"other":[]}}"""))
    }

    @Test fun parseFirecrawlDataDescriptionBeatsMarkdown() {
        val p = FirecrawlSearchProvider(FakeVault(), FakePost { _, _ -> ok(body = "{}") })
        val hits = p.parseFirecrawlDataForTest(
            """{"success":true,"data":{"web":[{"title":"D","url":"https://example.com/d","description":"desc wins","markdown":"md loses"}]}}""",
        )
        assertEquals(1, hits!!.size)
        assertEquals("desc wins", hits[0].snippet)
    }

    @Test fun parseFirecrawlDataMarkdownBeatsSnippet() {
        val p = FirecrawlSearchProvider(FakeVault(), FakePost { _, _ -> ok(body = "{}") })
        val hits = p.parseFirecrawlDataForTest(
            """{"success":true,"data":{"web":[{"title":"M","url":"https://example.com/m","markdown":"md wins","snippet":"snippet loses"}]}}""",
        )
        assertEquals(1, hits!!.size)
        assertEquals("md wins", hits[0].snippet)
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

    // ---- keyId 綁定 providerId＋endpoint ----

    private fun defaultKeyIdOf(id: String): String = when (id) {
        BraveSearchProvider.ID -> BraveSearchProvider.DEFAULT_KEY_ID
        TavilySearchProvider.ID -> TavilySearchProvider.DEFAULT_KEY_ID
        ExaSearchProvider.ID -> ExaSearchProvider.DEFAULT_KEY_ID
        FirecrawlSearchProvider.ID -> FirecrawlSearchProvider.DEFAULT_KEY_ID
        else -> throw IllegalArgumentException("unknown keyed id: $id")
    }

    /** 四家 provider 的 (id, keyId→provider) 工廠對；keyId 由呼叫方帶入。 */
    private fun keyedFactories(
        vault: FakeVault,
        httpGet: FakeGet,
        httpPost: FakePost,
    ): List<Pair<String, (String) -> WebSearchProvider>> = listOf(
        BraveSearchProvider.ID to { keyId -> BraveSearchProvider(vault, httpGet, keyId = keyId) },
        TavilySearchProvider.ID to { keyId -> TavilySearchProvider(vault, httpPost, keyId = keyId) },
        ExaSearchProvider.ID to { keyId -> ExaSearchProvider(vault, httpPost, keyId = keyId) },
        FirecrawlSearchProvider.ID to { keyId -> FirecrawlSearchProvider(vault, httpPost, keyId = keyId) },
    )

    private fun assertConstructionFailsFast(
        id: String,
        make: () -> WebSearchProvider,
        vault: FakeVault,
        httpGet: FakeGet,
        httpPost: FakePost,
    ) {
        val ex = assertThrows(IllegalArgumentException::class.java) { make() }
        assertTrue(
            "$id message must mention KEYID_PROVIDER_MISMATCH: ${ex.message}",
            ex.message?.contains("KEYID_PROVIDER_MISMATCH") == true,
        )
        assertTrue("$id vault.seenGet must stay empty", vault.seenGet.isEmpty())
        assertTrue("$id vault.seenHas must stay empty", vault.seenHas.isEmpty())
        assertEquals(0, httpGet.calls.get())
        assertEquals(0, httpPost.calls.get())
    }

    /**
     * 四家 keyed provider（各自固定 body；key 皆 put "k"）——parse 邊界測試專用。
     *
     * POST 端以 url 派發 body（Tavily／Exa／Firecrawl endpoint 互斥，皆 not startsWith 對方）；
     * GET（Brave）直接回 braveBody。
     */
    private suspend fun keyedWithBodies(
        braveBody: String,
        tavilyBody: String,
        exaBody: String,
        firecrawlBody: String,
    ): List<WebSearchProvider> {
        val vault = FakeVault()
        for (id in listOf(
            BraveSearchProvider.ID,
            TavilySearchProvider.ID,
            ExaSearchProvider.ID,
            FirecrawlSearchProvider.ID,
        )) {
            vault.putKey(defaultKeyIdOf(id), "k".toCharArray())
        }
        val get = FakeGet { ok(body = braveBody) }
        val post = FakePost { url, _ ->
            val body = when {
                url.startsWith(TavilySearchProvider.ENDPOINT) -> tavilyBody
                url.startsWith(ExaSearchProvider.ENDPOINT) -> exaBody
                url.startsWith(FirecrawlSearchProvider.ENDPOINT) -> firecrawlBody
                else -> error("unexpected endpoint: $url")
            }
            ok(body = body)
        }
        return defaultKeyedProviders(vault, get, post)
    }

    @Test fun defaultKeyIdsBindProviderAndEndpoint() {
        assertEquals("web.brave@https://api.search.brave.com/res/v1/web/search", BraveSearchProvider.DEFAULT_KEY_ID)
        assertEquals("web.tavily@https://api.tavily.com/search", TavilySearchProvider.DEFAULT_KEY_ID)
        assertEquals("web.exa@https://api.exa.ai/search", ExaSearchProvider.DEFAULT_KEY_ID)
        assertEquals("web.firecrawl@https://api.firecrawl.dev/v2/search", FirecrawlSearchProvider.DEFAULT_KEY_ID)
    }

    @Test fun mismatchedKeyIdFailsFastAtConstruction() {
        val vault = FakeVault()
        // putKey 為 suspend；直接寫入 fixture map（建構測試不在 suspend 中）。
        vault.keys[BraveSearchProvider.DEFAULT_KEY_ID] = "old".toCharArray()
        val httpGet = FakeGet { ok(body = braveJson) }
        val httpPost = FakePost { _, _ -> ok(body = tavilyJson) }
        // 四家 provider × 自訂 endpoint（web.<id>@https://evil.example/）→ IAE。
        for ((id, make) in keyedFactories(vault, httpGet, httpPost)) {
            assertConstructionFailsFast(id, { make("web.$id@https://evil.example/") }, vault, httpGet, httpPost)
        }
    }

    @Test fun foreignPresetKeyIdIsRejected() {
        val vault = FakeVault()
        val httpGet = FakeGet { ok(body = braveJson) }
        val httpPost = FakePost { _, _ -> ok(body = tavilyJson) }
        // 四家 provider × preset:／custom:／mcp: 外來命名空間 → IAE。
        for ((id, make) in keyedFactories(vault, httpGet, httpPost)) {
            for (bad in listOf("preset:openai", "custom:xx", "mcp:foo")) {
                assertConstructionFailsFast(id, { make(bad) }, vault, httpGet, httpPost)
            }
        }
    }

    @Test fun crossProviderKeyIdIsRejected() {
        val vault = FakeVault()
        val httpGet = FakeGet { ok(body = braveJson) }
        val httpPost = FakePost { _, _ -> ok(body = tavilyJson) }
        val allDefaultKeyIds = listOf(
            BraveSearchProvider.DEFAULT_KEY_ID,
            TavilySearchProvider.DEFAULT_KEY_ID,
            ExaSearchProvider.DEFAULT_KEY_ID,
            FirecrawlSearchProvider.DEFAULT_KEY_ID,
        )
        // 4 家 × 跨 provider keyId 完整交叉矩陣（除自身綁定者外皆 IAE）。
        for ((id, make) in keyedFactories(vault, httpGet, httpPost)) {
            val own = defaultKeyIdOf(id)
            for (foreign in allDefaultKeyIds) {
                if (foreign == own) continue
                assertConstructionFailsFast(id, { make(foreign) }, vault, httpGet, httpPost)
            }
        }
    }

    // ---- cache＋single-flight（經 WebSearchService） ----

    @Test fun serviceCacheSecondPhaseIsCached() = runBlocking {
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

        // 併發同 query 的 calls==1 只證明第二階段走 cache 路徑，不證明 single-flight 合流
        //（無 barrier 時無法斷言 follower 掛在共享 deferred 上）；
        // 合流證明見 keyedSingleFlightCoalescesConcurrentQueries。
        svc.invalidate()
        http.calls.set(0)
        val jobs = List(8) { async { svc.search("kotlin", 5) } }
        val outs = jobs.map { it.await() }
        assertTrue(outs.all { it is WebSearchOutcome.Ok })
        assertEquals(1, http.calls.get())
    }

    @Test fun keyRotationInvalidatesCachedRows() = runBlocking {
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGet { ok(body = braveJson) }
        val svc = WebSearchService(BraveSearchProvider(vault, http))
        val first = svc.search("kotlin", 5)
        assertTrue(first is WebSearchOutcome.Ok)
        assertFalse((first as WebSearchOutcome.Ok).cached)
        assertTrue(vault.seenGet.contains(BraveSearchProvider.DEFAULT_KEY_ID))
        // 同 keyId 輪換 key-A → key-B：舊憑證 cache 列不得命中。
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-B".toCharArray())
        val second = svc.search("kotlin", 5)
        assertTrue(second is WebSearchOutcome.Ok)
        assertFalse((second as WebSearchOutcome.Ok).cached)
        assertEquals(2, http.calls.get())
        // key-B 的列已寫入：第三次命中 key-B cache。
        val third = svc.search("kotlin", 5)
        assertTrue(third is WebSearchOutcome.Ok)
        assertTrue((third as WebSearchOutcome.Ok).cached)
        assertEquals(2, http.calls.get())
    }

    @Test fun keyDeletionInvalidatesCachedRows() = runBlocking {
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGet { ok(body = braveJson) }
        val svc = WebSearchService(BraveSearchProvider(vault, http))
        val first = svc.search("kotlin", 5)
        assertTrue(first is WebSearchOutcome.Ok)
        assertFalse((first as WebSearchOutcome.Ok).cached)
        vault.deleteKey(BraveSearchProvider.DEFAULT_KEY_ID)
        // cacheRevision 回 REV_UNAVAILABLE（vault 無 key）→ Service 完全旁路快取（不讀不寫）；
        // delegate.search 亦讀不到 key → Unavailable(MISSING_KEY)，零網路。
        val second = svc.search("kotlin", 5)
        assertTrue(second is WebSearchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.MISSING_KEY, (second as WebSearchOutcome.Unavailable).reason)
        assertEquals(1, http.calls.get())
    }

    @Test fun transientProbeFailureBypassesCacheAndRecachesLater() = runBlocking {
        val vault = FlakyProbeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGet { ok(body = braveJson) }
        val svc = WebSearchService(BraveSearchProvider(vault, http))
        vault.failRemaining = 1 // 僅第一次 rev probe 拋錯；bypass 路徑不寫 cache
        val first = svc.search("kotlin", 5)
        assertTrue(first is WebSearchOutcome.Ok)
        assertFalse((first as WebSearchOutcome.Ok).cached)
        assertEquals(1, http.calls.get())
        // probe 恢復後：cache 尚未有 A 的列（bypass 未寫）→ 再打一次 upstream 並寫入 cache。
        val second = svc.search("kotlin", 5)
        assertTrue(second is WebSearchOutcome.Ok)
        assertFalse((second as WebSearchOutcome.Ok).cached)
        assertEquals(2, http.calls.get())
        // 後續搜尋：probe 正常 → 命中 A 的 rev 綁定 cache 列。
        val third = svc.search("kotlin", 5)
        assertTrue(third is WebSearchOutcome.Ok)
        assertTrue((third as WebSearchOutcome.Ok).cached)
        assertEquals(2, http.calls.get())
    }

    @Test fun transientProbeFailureWithDeletionNeverServesStale() = runBlocking {
        val vault = FlakyProbeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGet { ok(body = braveJson) }
        val svc = WebSearchService(BraveSearchProvider(vault, http))
        vault.failRemaining = 1
        val first = svc.search("kotlin", 5)
        assertTrue(first is WebSearchOutcome.Ok)
        assertEquals(1, http.calls.get())
        vault.deleteKey(BraveSearchProvider.DEFAULT_KEY_ID)
        // probe 回 REV_UNAVAILABLE（vault 無 key）→ 旁路快取，不得讀到舊 Ok 列；
        // delegate.search 亦讀不到 key → Unavailable(MISSING_KEY)，零新網路。
        val second = svc.search("kotlin", 5)
        assertTrue(second is WebSearchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.MISSING_KEY, (second as WebSearchOutcome.Unavailable).reason)
        assertEquals(1, http.calls.get())
    }

    @Test fun rotateViaProbeFailureServesFreshUpstream() = runBlocking {
        val vault = FlakyProbeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGet { ok(body = braveJson) }
        val svc = WebSearchService(BraveSearchProvider(vault, http))
        val first = svc.search("kotlin", 5)
        assertTrue(first is WebSearchOutcome.Ok)
        assertEquals(1, http.calls.get())
        // A → B 輪換；probe 暫時故障 → bypass，直接以 B 打 upstream，不得命中 A 的 stale 列。
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-B".toCharArray())
        vault.failRemaining = 1
        val second = svc.search("kotlin", 5)
        assertTrue(second is WebSearchOutcome.Ok)
        assertFalse((second as WebSearchOutcome.Ok).cached)
        assertEquals(2, http.calls.get())
    }

    @Test fun staleCachedRowNeverServedWhenKeyDeletedWithFlakyProbe() = runBlocking {
        val vault = FlakyProbeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGet { ok(body = braveJson) }
        val svc = WebSearchService(BraveSearchProvider(vault, http))
        val first = svc.search("kotlin", 5)
        assertTrue(first is WebSearchOutcome.Ok)
        assertEquals(1, http.calls.get())
        // A 的 Ok 列已寫入 fp-A；接著讓下一次 rev probe 故障並刪除 key：
        // stale A 列＋壞 probe＋已撤銷憑證 → 不得回傳 stale Ok，零網路。
        vault.failRemaining = 1
        vault.deleteKey(BraveSearchProvider.DEFAULT_KEY_ID)
        val second = svc.search("kotlin", 5)
        assertTrue(second is WebSearchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.MISSING_KEY, (second as WebSearchOutcome.Unavailable).reason)
        assertEquals(1, http.calls.get())
    }

    @Test fun sentinelSearchesNeverWriteCacheRows() = runBlocking {
        // G1 哨兵不得「退化」成 plain cache：REV_UNAVAILABLE 期間每次皆 bypass，
        // 不讀不寫；若 sentinel 路徑寫了 plain 列，後續輪換後的搜尋會被誤中。
        val vault = FlakyProbeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGet { ok(body = braveJson) }
        val svc = WebSearchService(BraveSearchProvider(vault, http))
        vault.failRemaining = 1
        val first = svc.search("kotlin", 5)
        assertTrue(first is WebSearchOutcome.Ok)
        assertEquals(1, http.calls.get())
        // A → B 輪換；probe 暫時故障 → bypass，B 必須 fresh 打 upstream，命中不得寫 cache。
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-B".toCharArray())
        vault.failRemaining = 1
        val second = svc.search("kotlin", 5)
        assertTrue(second is WebSearchOutcome.Ok)
        assertFalse((second as WebSearchOutcome.Ok).cached)
        assertEquals(2, http.calls.get())
        // B → C 輪換；再度 bypass：若 sentinel 路徑曾寫 plain 列，此搜尋會被 cache 命中。
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-C".toCharArray())
        vault.failRemaining = 1
        val third = svc.search("kotlin", 5)
        assertTrue(third is WebSearchOutcome.Ok)
        assertFalse((third as WebSearchOutcome.Ok).cached)
        assertEquals(3, http.calls.get())
    }

    @Test fun nestedKeyedServicesBypassOnProbeFailure() = runBlocking {
        val vault = FlakyProbeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGet { ok(body = braveJson) }
        val inner = WebSearchService(BraveSearchProvider(vault, http))
        val outer = WebSearchService(inner)
        // outer rev probe 故障 → outer 完全 bypass → inner 自行跑：
        // inner 的 probe 在 flake 消耗後恢復 → fp-A → inner MISS → upstream 一次；
        // outer bypass 不寫 cache，outer 層 cached=false。
        vault.failRemaining = 1
        val first = outer.search("kotlin", 5)
        assertTrue(first is WebSearchOutcome.Ok)
        assertFalse((first as WebSearchOutcome.Ok).cached)
        assertEquals(1, http.calls.get())
        // 同 query 再搜：outer rev=fp-A、outer cache miss → 委派 inner；
        // inner 命中 search #1 寫入的 fp-A 列（cached=true），upstream 不再打。
        val second = outer.search("kotlin", 5)
        assertTrue(second is WebSearchOutcome.Ok)
        assertTrue((second as WebSearchOutcome.Ok).cached)
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

    @Test fun hostedMarkerRejectsInvalidStates() {
        val invalid = listOf(
            "",
            "Completed",
            "COMPLETED",
            "completed ",
            " completed",
            "9lives",
            "a]b",
            "a\nb",
            "a\rb",
            "\tcompleted",
            "web_search!",
            "a".repeat(33),
            "\u0007x",
        )
        for (state in invalid) {
            assertThrows("hostedMarker(\"$state\") must throw IllegalArgumentException", IllegalArgumentException::class.java) {
                WebSearchDispatch.hostedMarker(state)
            }
        }
    }

    @Test fun hostedMarkerAcceptsProtocolStates() {
        for (state in listOf("in_progress", "completed", "failed", "cancelled", "started")) {
            val marker = WebSearchDispatch.hostedMarker(state)
            assertTrue("marker for $state must contain the state", marker.contains(state))
        }
    }

    @Test fun hostedMarkerFormatIsPinned() {
        assertEquals(
            "\n[SERVER_TOOL:web_search completed]\n",
            WebSearchDispatch.hostedMarker("completed"),
        )
    }

    @Test fun dispatchPassesQueryAndCountToRunner() = runBlocking {
        val registry = registryOf("brave")
        var seenQuery: String? = null
        var seenCount: Int? = null
        val out = WebSearchDispatch.search(
            pinnedId = null,
            hostedAvailable = false,
            registry = registry,
            query = "kotlin",
            count = 5,
            isReady = { true },
            runHosted = { "completed" },
            runLocal = { _, q, c ->
                seenQuery = q
                seenCount = c
                WebSearchOutcome.Ok(emptyList())
            },
        )
        assertTrue(out is WebDispatchOutcome.Local)
        assertEquals("kotlin", seenQuery)
        assertEquals(5, seenCount)
    }

    @Test fun dispatchNoProviderIsUnavailable() = runBlocking {
        // WebSearchRegistry 允許空清單（見 WebSearchCoreTest 的 emptyList 用法）。
        val registry = WebSearchRegistry(emptyList())
        var hostedCalls = 0
        var localCalls = 0
        val out = WebSearchDispatch.search(
            pinnedId = null,
            hostedAvailable = false,
            registry = registry,
            query = "kotlin",
            count = 5,
            isReady = { false },
            runHosted = { hostedCalls++; "completed" },
            runLocal = { _, _, _ -> localCalls++; WebSearchOutcome.Ok(emptyList()) },
        )
        assertTrue(out is WebDispatchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.NO_PROVIDER, (out as WebDispatchOutcome.Unavailable).reason)
        assertEquals(0, hostedCalls)
        assertEquals(0, localCalls)
    }

    @Test fun dispatchRunnerExceptionPropagates() {
        val registry = registryOf("brave")
        // (a) runLocal 拋 IllegalStateException → 原樣傳播（本層不捕捉）。
        val localEx = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                WebSearchDispatch.search(
                    pinnedId = null,
                    hostedAvailable = false,
                    registry = registry,
                    query = "kotlin",
                    count = 5,
                    isReady = { true },
                    runHosted = { "completed" },
                    runLocal = { _, _, _ -> throw IllegalStateException("boom") },
                )
            }
        }
        assertEquals("boom", localEx.message)
        // (b) runHosted 回傳非法狀態字 → hostedMarker 的 IllegalArgumentException 傳播出 search()。
        val hostedEx = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                WebSearchDispatch.search(
                    pinnedId = null,
                    hostedAvailable = true,
                    registry = registry,
                    query = "kotlin",
                    count = 5,
                    isReady = { true },
                    runHosted = { "bad]state" },
                    runLocal = { _, _, _ -> throw AssertionError("runLocal must not be called") },
                )
            }
        }
        assertTrue(hostedEx.message?.contains("a-z") == true)
    }

    @Test fun responseToStringDoesNotLeakKey() {
        val r = WebHttpResponse(
            code = 200,
            body = "supersecret-body-value",
            headers = mapOf("Authorization" to "Bearer supersecret-token"),
        )
        val s = r.toString()
        assertFalse(s.contains("supersecret-body-value"))
        assertFalse(s.contains("supersecret-token"))
        assertTrue(s.contains("bodyChars"))
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
            isReady = { true },
            runHosted = { hostedCalls++; "completed" },
            runLocal = { _, _, _ -> localCalls++; WebSearchOutcome.Ok(emptyList()) },
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
            runLocal = { p, q, c -> WebSearchService(p).search(q, c) },
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
                runLocal = { _, _, _ -> localCalls++; WebSearchOutcome.Ok(emptyList()) },
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
            runLocal = { p, _, _ -> seen.add(p.id); WebSearchOutcome.Ok(emptyList()) },
        )
        assertTrue(out is WebDispatchOutcome.Local)
        assertEquals("brave", (out as WebDispatchOutcome.Local).providerId)
        assertEquals(listOf("brave"), seen)
        assertEquals(0, hostedCalls)
    }

    // ---- 短路／探測次數／select parity／例外傳播 ----

    @Test fun hostedMarkerAccepts32CharBoundary() {
        // "a".repeat(32) 為 HOSTED_STATE 上限（首字母 [a-z]＋至多 31 個 [a-z_]），必須接受。
        val state = "a".repeat(32)
        val marker = WebSearchDispatch.hostedMarker(state)
        assertTrue("marker must contain the 32-char state", marker.contains(state))
        // 首字元不得為底線（regex 首類別為 [a-z]）：一律 IllegalArgumentException。
        assertThrows(IllegalArgumentException::class.java) {
            WebSearchDispatch.hostedMarker("_completed")
        }
    }

    @Test fun emptyQueryProbesZeroProviders() = runBlocking {
        val registry = registryOf("brave")
        val probes = mutableListOf<String>()
        var hostedCalls = 0
        val out = WebSearchDispatch.search(
            pinnedId = null,
            hostedAvailable = true,
            registry = registry,
            query = "   ",
            count = 5,
            isReady = { probes.add(it.id); true },
            runHosted = { hostedCalls++; "completed" },
            runLocal = { _, _, _ -> error("runLocal must not run") },
        )
        assertTrue(out is WebDispatchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.EMPTY_QUERY, (out as WebDispatchOutcome.Unavailable).reason)
        assertTrue("blank query must short-circuit before any probe", probes.isEmpty())
        assertEquals(0, hostedCalls)
    }

    @Test fun hostedPathProbesNoProviders() = runBlocking {
        val registry = registryOf("brave", "tavily")
        val probes = mutableListOf<String>()
        var hostedCalls = 0
        val out = WebSearchDispatch.search(
            pinnedId = null,
            hostedAvailable = true,
            registry = registry,
            query = "kotlin",
            count = 5,
            isReady = { probes.add(it.id); true },
            runHosted = { hostedCalls++; "completed" },
            runLocal = { _, _, _ -> error("runLocal must not run") },
        )
        assertTrue(out is WebDispatchOutcome.Hosted)
        assertTrue("hosted path must probe zero providers", probes.isEmpty())
        assertEquals(1, hostedCalls)
    }

    @Test fun firstReadyShortCircuitsProbing() = runBlocking {
        val registry = registryOf("brave", "tavily")
        // (a) brave 未就緒、tavily 就緒 → 依註冊序探測 brave→tavily，見到首個就緒即短路。
        run {
            val probes = mutableListOf<String>()
            var localId: String? = null
            val out = WebSearchDispatch.search(
                pinnedId = null,
                hostedAvailable = false,
                registry = registry,
                query = "kotlin",
                count = 5,
                isReady = { probes.add(it.id); it.id == "tavily" },
                runHosted = { error("hosted must not run") },
                runLocal = { p, _, _ -> localId = p.id; WebSearchOutcome.Ok(emptyList()) },
            )
            assertTrue(out is WebDispatchOutcome.Local)
            assertEquals("tavily", (out as WebDispatchOutcome.Local).providerId)
            assertEquals("tavily", localId)
            assertEquals(listOf("brave", "tavily"), probes)
        }
        // (b) brave 就緒 → 只探測 brave，tavily 不被探測（短路，無多餘 probe）。
        run {
            val probes = mutableListOf<String>()
            var localId: String? = null
            val out = WebSearchDispatch.search(
                pinnedId = null,
                hostedAvailable = false,
                registry = registry,
                query = "kotlin",
                count = 5,
                isReady = { probes.add(it.id); it.id == "brave" },
                runHosted = { error("hosted must not run") },
                runLocal = { p, _, _ -> localId = p.id; WebSearchOutcome.Ok(emptyList()) },
            )
            assertTrue(out is WebDispatchOutcome.Local)
            assertEquals("brave", (out as WebDispatchOutcome.Local).providerId)
            assertEquals("brave", localId)
            assertEquals(listOf("brave"), probes)
        }
    }

    @Test fun pinnedPathProbesOnlyPinned() = runBlocking {
        val registry = registryOf("brave", "tavily")
        val probes = mutableListOf<String>()
        var hostedCalls = 0
        var localId: String? = null
        val out = WebSearchDispatch.search(
            pinnedId = "tavily",
            hostedAvailable = false,
            registry = registry,
            query = "kotlin",
            count = 5,
            isReady = { probes.add(it.id); true },
            runHosted = { hostedCalls++; "completed" },
            runLocal = { p, _, _ -> localId = p.id; WebSearchOutcome.Ok(emptyList()) },
        )
        assertTrue(out is WebDispatchOutcome.Local)
        assertEquals("tavily", (out as WebDispatchOutcome.Local).providerId)
        assertEquals("tavily", localId)
        assertEquals(listOf("tavily"), probes)
        assertEquals(0, hostedCalls)
    }

    @Test fun dispatchSelectParityWithSelector() = runBlocking {
        val registry = registryOf("brave", "tavily")
        val readySets = listOf(
            emptySet(),
            setOf("brave"),
            setOf("tavily"),
            setOf("brave", "tavily"),
        )
        for (pinnedId in listOf<String?>(null, "tavily")) {
            for (hostedAvailable in listOf(false, true)) {
                for (readySet in readySets) {
                    val case = "pinned=$pinnedId hostedAvailable=$hostedAvailable readySet=$readySet"
                    val route = WebSearchSelector.select(pinnedId, hostedAvailable, registry) {
                        it.id in readySet
                    }
                    val out = WebSearchDispatch.search(
                        pinnedId = pinnedId,
                        hostedAvailable = hostedAvailable,
                        registry = registry,
                        query = "kotlin",
                        count = 5,
                        isReady = { it.id in readySet },
                        runHosted = { "completed" },
                        runLocal = { _, _, _ -> WebSearchOutcome.Ok(emptyList()) },
                    )
                    when (route) {
                        is WebRoute.Hosted ->
                            assertTrue("$case: select=Hosted but outcome=$out", out is WebDispatchOutcome.Hosted)
                        is WebRoute.Local -> {
                            assertTrue(
                                "$case: select=Local(${route.providerId}) but outcome=$out",
                                out is WebDispatchOutcome.Local,
                            )
                            assertEquals(
                                "$case: providerId mismatch",
                                route.providerId,
                                (out as WebDispatchOutcome.Local).providerId,
                            )
                        }
                        is WebRoute.Unavailable -> {
                            assertTrue(
                                "$case: select=Unavailable(${route.reason}) but outcome=$out",
                                out is WebDispatchOutcome.Unavailable,
                            )
                            assertEquals(
                                "$case: reason mismatch",
                                route.reason,
                                (out as WebDispatchOutcome.Unavailable).reason,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test fun isReadyExceptionPropagatesBeforeRunners() {
        val registry = registryOf("brave")
        var hostedCalls = 0
        var localCalls = 0
        val ex = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                WebSearchDispatch.search(
                    pinnedId = null,
                    hostedAvailable = false,
                    registry = registry,
                    query = "kotlin",
                    count = 5,
                    isReady = { throw IllegalStateException("probe boom") },
                    runHosted = { hostedCalls++; "completed" },
                    runLocal = { _, _, _ -> localCalls++; WebSearchOutcome.Ok(emptyList()) },
                )
            }
        }
        assertEquals("probe boom", ex.message)
        assertEquals(0, hostedCalls)
        assertEquals(0, localCalls)
    }

    // ---- keyed single-flight／leader·follower 取消（WebSearchService 包裝） ----

    @Test fun keyedSingleFlightCoalescesConcurrentQueries() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        val http = FakeGet {
            entered.complete(Unit)
            release.await()
            ok(body = braveJson)
        }
        val svc = WebSearchService(BraveSearchProvider(vault, http, timeoutMs = 60_000))
        val jobs = List(8) { async(start = CoroutineStart.UNDISPATCHED) { svc.search("kotlin", 5) } }
        withTimeout(5_000) { entered.await() }
        // leader 已進 handler、7 個 follower 已同步掛上 shared deferred：仍只有一次 upstream。
        assertEquals(1, http.calls.get())
        release.complete(Unit)
        val outs = withTimeout(5_000) { jobs.map { it.await() } }
        assertTrue(outs.all { it is WebSearchOutcome.Ok })
        assertEquals(1, http.calls.get())
    }

    @Test fun keyedFollowerCancelDoesNotAffectLeader() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        val http = FakeGet {
            entered.complete(Unit)
            release.await()
            ok(body = braveJson)
        }
        val svc = WebSearchService(BraveSearchProvider(vault, http, timeoutMs = 60_000))
        val leader = async { svc.search("kotlin", 5) }
        withTimeout(5_000) { entered.await() }
        // UNDISPATCHED：return 時 follower 已掛上 shared deferred。
        val follower = async(start = CoroutineStart.UNDISPATCHED) { svc.search("kotlin", 5) }
        delay(50)
        follower.cancelAndJoin()
        release.complete(Unit)
        val out = withTimeout(5_000) { leader.await() }
        assertTrue(out is WebSearchOutcome.Ok)
        assertFalse((out as WebSearchOutcome.Ok).cached)
        assertEquals(1, http.calls.get())
    }

    @Test fun keyedLeaderCancelFollowerRetriesAndSucceeds() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        val http = FakeGet {
            entered.complete(Unit)
            release.await()
            ok(body = braveJson)
        }
        val svc = WebSearchService(BraveSearchProvider(vault, http, timeoutMs = 60_000))
        val leader = async { svc.search("kotlin", 5) }
        withTimeout(5_000) { entered.await() }
        // UNDISPATCHED：return 時 follower 已掛上 shared deferred。
        val follower = async(start = CoroutineStart.UNDISPATCHED) { svc.search("kotlin", 5) }
        leader.cancel()
        release.complete(Unit)
        try {
            withTimeout(5_000) { leader.await() }
            fail("leader must throw CancellationException")
        } catch (_: CancellationException) {
            // expected：leader 取消，shared deferred completeExceptionally
        }
        val out = withTimeout(5_000) { follower.await() }
        assertTrue(out is WebSearchOutcome.Ok)
        assertEquals(2, http.calls.get())
    }

    // ---- keyed CharArray 抹除（TrackingVault：getKey 直接參照回傳） ----

    @Test fun keyArrayWipedOnSuccess() = runBlocking {
        val vault = TrackingVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "super-secret-key".toCharArray())
        val out = BraveSearchProvider(vault, FakeGet { ok(body = braveJson) }).search("kotlin", 5)
        assertTrue(out is WebSearchOutcome.Ok)
        assertTrue("getKey 必須被呼叫", vault.captured != null)
        assertTrue("success 後 key buffer 必須被抹除", vault.captured!!.all { it == '\u0000' })
    }

    @Test fun keyArrayWipedOnHttp500() = runBlocking {
        val vault = TrackingVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "super-secret-key".toCharArray())
        val out = BraveSearchProvider(vault, FakeGet { ok(500, "x") }).search("kotlin", 5)
        assertTrue(out is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.SUPPLIER_ERROR, (out as WebSearchOutcome.Failed).reason)
        assertTrue("HTTP 500 後 key buffer 必須被抹除", vault.captured!!.all { it == '\u0000' })
    }

    @Test fun keyArrayWipedOnTimeout() = runBlocking {
        val vault = TrackingVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "super-secret-key".toCharArray())
        val http = FakeGet { delay(10_000); ok(body = braveJson) }
        val out = BraveSearchProvider(vault, http, timeoutMs = 50).search("kotlin", 5)
        assertTrue(out is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.TIMEOUT, (out as WebSearchOutcome.Failed).reason)
        assertTrue("timeout 後 key buffer 必須被抹除", vault.captured!!.all { it == '\u0000' })
    }

    @Test fun keyArrayWipedOnBlankKey() = runBlocking {
        val vault = TrackingVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "   ".toCharArray())
        val out = BraveSearchProvider(vault, FakeGet { ok(body = braveJson) }).search("kotlin", 5)
        assertTrue(out is WebSearchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.MISSING_KEY, (out as WebSearchOutcome.Unavailable).reason)
        assertTrue("blank key 的 buffer 必須被抹除", vault.captured!!.all { it == '\u0000' })
    }

    // ---- probe／vault 失敗分支 ----

    @Test fun hasKeyThrowsMakesProbeNotReady() = runBlocking {
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        vault.failHasKey = true
        val p = BraveSearchProvider(vault, FakeGet { ok(body = braveJson) })
        val probe = p.probe()
        assertTrue(probe is WebProbe.NotReady)
        assertEquals(WebUnavailableReason.MISSING_KEY, (probe as WebProbe.NotReady).reason)
        assertTrue("probe 不得讀取 key material", vault.seenGet.isEmpty())
    }

    @Test fun blankVaultKeySearchIsMissingKeyZeroNetwork() = runBlocking {
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "   ".toCharArray())
        val httpGet = FakeGet { ok(body = braveJson) }
        val out = BraveSearchProvider(vault, httpGet).search("q", 5)
        assertTrue(out is WebSearchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.MISSING_KEY, (out as WebSearchOutcome.Unavailable).reason)
        assertEquals(0, httpGet.calls.get())
    }

    @Test fun probeNeverReadsKeyMaterial() = runBlocking {
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        val p = BraveSearchProvider(vault, FakeGet { ok(body = braveJson) })
        assertTrue(p.probe() is WebProbe.Ready)
        assertTrue("probe 只能用 hasKey，不得呼叫 getKey", vault.seenGet.isEmpty())
        assertEquals(listOf(BraveSearchProvider.DEFAULT_KEY_ID), vault.seenHas)
    }

    // ---- keyed typed 錯誤矩陣（四家 × 同一矩陣） ----

    @Test fun keyedStatus429IsSupplierError() = runBlocking {
        val vault = FakeVault()
        val httpGet = FakeGet { ok(429, "{}") }
        val httpPost = FakePost { _, _ -> ok(429, "{}") }
        for (p in defaultKeyedProviders(vault, httpGet, httpPost)) {
            vault.putKey(defaultKeyIdOf(p.id), "k".toCharArray())
            val out = p.search("q", 5)
            assertTrue("${p.id}/429 must be Failed", out is WebSearchOutcome.Failed)
            val failed = out as WebSearchOutcome.Failed
            assertEquals(p.id, WebFailureReason.SUPPLIER_ERROR, failed.reason)
            assertTrue("${p.id}/429 must allow fallback", failed.fallbackAllowed)
        }
    }

    @Test fun keyedStatus500And503AreSupplierError() = runBlocking {
        for (code in listOf(500, 503)) {
            val vault = FakeVault()
            val httpGet = FakeGet { ok(code, "{}") }
            val httpPost = FakePost { _, _ -> ok(code, "{}") }
            for (p in defaultKeyedProviders(vault, httpGet, httpPost)) {
                vault.putKey(defaultKeyIdOf(p.id), "k".toCharArray())
                val out = p.search("q", 5)
                assertTrue("${p.id}/$code must be Failed", out is WebSearchOutcome.Failed)
                assertEquals("${p.id}/$code", WebFailureReason.SUPPLIER_ERROR, (out as WebSearchOutcome.Failed).reason)
                assertTrue("${p.id}/$code must allow fallback", out.fallbackAllowed)
            }
        }
    }

    @Test fun keyedStatus408And504AreTimeout() = runBlocking {
        for (code in listOf(408, 504)) {
            val vault = FakeVault()
            val httpGet = FakeGet { ok(code, "{}") }
            val httpPost = FakePost { _, _ -> ok(code, "{}") }
            for (p in defaultKeyedProviders(vault, httpGet, httpPost)) {
                vault.putKey(defaultKeyIdOf(p.id), "k".toCharArray())
                val out = p.search("q", 5)
                assertTrue("${p.id}/$code must be Failed", out is WebSearchOutcome.Failed)
                assertEquals("${p.id}/$code", WebFailureReason.TIMEOUT, (out as WebSearchOutcome.Failed).reason)
                assertTrue("${p.id}/$code must allow fallback", out.fallbackAllowed)
            }
        }
    }

    @Test fun keyedTransportOnIoFailure() = runBlocking {
        val vault = FakeVault()
        val httpGet = FakeGet { throw IOException("boom") }
        val httpPost = FakePost { _, _ -> throw IOException("boom") }
        for (p in defaultKeyedProviders(vault, httpGet, httpPost)) {
            vault.putKey(defaultKeyIdOf(p.id), "k".toCharArray())
            val out = p.search("q", 5)
            assertTrue("${p.id} must be Failed", out is WebSearchOutcome.Failed)
            assertEquals(p.id, WebFailureReason.TRANSPORT, (out as WebSearchOutcome.Failed).reason)
            assertTrue("${p.id} must allow fallback", out.fallbackAllowed)
        }
    }

    @Test fun keyedTimeoutIsInjectable() = runBlocking {
        val vault = FakeVault()
        val httpGet = FakeGet { delay(10_000); ok(body = braveJson) }
        val httpPost = FakePost { _, _ -> delay(10_000); ok(body = tavilyJson) }
        // defaultKeyedProviders 的 timeoutMs 同時套用 GET 與 postAsGet 轉接路徑。
        for (p in defaultKeyedProviders(vault, httpGet, httpPost, timeoutMs = 50)) {
            vault.putKey(defaultKeyIdOf(p.id), "k".toCharArray())
            val out = p.search("q", 5)
            assertTrue("${p.id} must be Failed", out is WebSearchOutcome.Failed)
            assertEquals(p.id, WebFailureReason.TIMEOUT, (out as WebSearchOutcome.Failed).reason)
            assertTrue("${p.id} must allow fallback", out.fallbackAllowed)
        }
    }

    @Test fun keyedOversizeBodyIsBadResponse() = runBlocking {
        val vault = FakeVault()
        val httpGet = FakeGet { ok(body = braveJson) }
        val httpPost = FakePost { _, _ -> ok(body = tavilyJson) }
        // maxBodyChars=10：所有 hit fixture 長度皆遠超 10 chars → BAD_RESPONSE。
        for (p in defaultKeyedProviders(vault, httpGet, httpPost, maxBodyChars = 10)) {
            vault.putKey(defaultKeyIdOf(p.id), "k".toCharArray())
            val out = p.search("q", 5)
            assertTrue("${p.id} must be Failed", out is WebSearchOutcome.Failed)
            assertEquals(p.id, WebFailureReason.BAD_RESPONSE, (out as WebSearchOutcome.Failed).reason)
            assertTrue("${p.id} must allow fallback", out.fallbackAllowed)
        }
    }

    @Test fun firecrawlSupplierErrorIsFallbackAllowed() = runBlocking {
        val vault = FakeVault()
        vault.putKey(FirecrawlSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        val http = FakePost { _, _ -> ok(body = """{"success":false,"error":"quota"}""") }
        val out = FirecrawlSearchProvider(vault, http).search("q", 5)
        assertTrue(out is WebSearchOutcome.Failed)
        val failed = out as WebSearchOutcome.Failed
        assertEquals(WebFailureReason.SUPPLIER_ERROR, failed.reason)
        assertTrue("SUPPLIER_ERROR must allow fallback", failed.fallbackAllowed)
    }

    @Test fun firecrawlMissingSuccessAndDataIsBadResponse() = runBlocking {
        val vault = FakeVault()
        vault.putKey(FirecrawlSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        val http = FakePost { _, _ -> ok(body = "{}") }
        val out = FirecrawlSearchProvider(vault, http).search("q", 5)
        assertTrue(out is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.BAD_RESPONSE, (out as WebSearchOutcome.Failed).reason)
    }

    @Test fun firecrawlMissingSuccessWithValidDataIsBadResponse() = runBlocking {
        val vault = FakeVault()
        vault.putKey(FirecrawlSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        val http = FakePost { _, _ -> ok(body = """{"data":{"web":[{"title":"X","url":"https://example.com/"}]}}""") }
        val out = FirecrawlSearchProvider(vault, http).search("q", 5)
        assertTrue(out is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.BAD_RESPONSE, (out as WebSearchOutcome.Failed).reason)
    }

    // ---- count 預算裁切／URL encoding ----

    @Test fun keyedCountBudgetIsClippedToOneToTen() = runBlocking {
        val vault = FakeVault()
        val httpGet = FakeGet { ok(body = braveJson) }
        val httpPost = FakePost { _, _ -> ok(body = tavilyJson) }
        for (p in defaultKeyedProviders(vault, httpGet, httpPost)) {
            vault.putKey(defaultKeyIdOf(p.id), "k".toCharArray())
            // count=999 → 裁切為 10。
            p.search("q", 999)
            when (p.id) {
                BraveSearchProvider.ID ->
                    assertTrue("brave count must clip to 10", httpGet.lastUrl!!.contains("count=10"))
                TavilySearchProvider.ID ->
                    assertTrue("tavily max_results must clip to 10", httpPost.lastBody!!.contains("\"max_results\":10"))
                ExaSearchProvider.ID ->
                    assertTrue("exa numResults must clip to 10", httpPost.lastBody!!.contains("\"numResults\":10"))
                FirecrawlSearchProvider.ID ->
                    assertTrue("firecrawl limit must clip to 10", httpPost.lastBody!!.contains("\"limit\":10"))
                else -> error("unexpected keyed id: ${p.id}")
            }
            // count=0 → 裁切為 1。
            p.search("q", 0)
            when (p.id) {
                BraveSearchProvider.ID ->
                    assertTrue("brave count must clip to 1", httpGet.lastUrl!!.contains("count=1"))
                TavilySearchProvider.ID ->
                    assertTrue("tavily max_results must clip to 1", httpPost.lastBody!!.contains("\"max_results\":1"))
                ExaSearchProvider.ID ->
                    assertTrue("exa numResults must clip to 1", httpPost.lastBody!!.contains("\"numResults\":1"))
                FirecrawlSearchProvider.ID ->
                    assertTrue("firecrawl limit must clip to 1", httpPost.lastBody!!.contains("\"limit\":1"))
                else -> error("unexpected keyed id: ${p.id}")
            }
        }
    }

    @Test fun braveQueryIsUrlEncoded() = runBlocking {
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        val http = FakeGet { ok(body = braveJson) }
        BraveSearchProvider(vault, http).search("a&b c", 5)
        assertTrue("query 必須 URL-encoded（& → %26、空格 → +）", http.lastUrl!!.contains("q=a%26b+c"))
    }

    // ---- count 裁切＋truncated 標記 ----

    @Test fun keyedClipsToCountAndMarksTruncated() = runBlocking {
        val vault = FakeVault()
        // brave：4 筆 valid hits，count=2 → 恰 2 筆，truncated=true。
        run {
            vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
            val out = BraveSearchProvider(vault, FakeGet { ok(body = braveJson) }).search("kotlin", 2)
            assertTrue(out is WebSearchOutcome.Ok)
            val results = (out as WebSearchOutcome.Ok).results
            assertEquals(2, results.size)
            assertEquals("Kotlin Docs", results[0].title)
            assertEquals("https://example.com/plain", results[1].url)
            assertTrue("clips 超出 count 必須標記 truncated", out.truncated)
        }
        // exa：E1..E5 皆 valid，count=2 → 恰 2 筆，truncated=true。
        run {
            vault.putKey(ExaSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
            val out = ExaSearchProvider(vault, FakePost { _, _ -> ok(body = exaJson) }).search("kotlin", 2)
            assertTrue(out is WebSearchOutcome.Ok)
            val results = (out as WebSearchOutcome.Ok).results
            assertEquals(2, results.size)
            assertEquals(listOf("E1", "E2"), results.map { it.title })
            assertTrue(out.truncated)
        }
        // tavily：獨立多筆 fixture（tavilyJson 僅 1 valid hit 且不動既有斷言；
        // 見 tavilyJsonMulti KDoc），count=2 → 恰 2 筆，truncated=true。
        run {
            vault.putKey(TavilySearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
            val out = TavilySearchProvider(vault, FakePost { _, _ -> ok(body = tavilyJsonMulti) }).search("kotlin", 2)
            assertTrue(out is WebSearchOutcome.Ok)
            val results = (out as WebSearchOutcome.Ok).results
            assertEquals(2, results.size)
            assertEquals(listOf("T1", "T2"), results.map { it.title })
            assertTrue(out.truncated)
        }
        // firecrawl：F1／F2 兩筆，count=1 → 恰 1 筆，truncated=true。
        run {
            vault.putKey(FirecrawlSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
            val out = FirecrawlSearchProvider(vault, FakePost { _, _ -> ok(body = firecrawlJson) }).search("kotlin", 1)
            assertTrue(out is WebSearchOutcome.Ok)
            val results = (out as WebSearchOutcome.Ok).results
            assertEquals(1, results.size)
            assertEquals("F1", results[0].title)
            assertTrue(out.truncated)
        }
    }

    // ---- parse 邊界（四家） ----

    @Test fun keyedEmptyResultsArrayIsOkEmpty() = runBlocking {
        val providers = keyedWithBodies(
            braveBody = """{"web":{"results":[]}}""",
            tavilyBody = """{"results":[]}""",
            exaBody = """{"results":[]}""",
            firecrawlBody = """{"success":true,"data":{"web":[]}}""",
        )
        for (p in providers) {
            val out = p.search("q", 5)
            assertTrue("${p.id} must be Ok", out is WebSearchOutcome.Ok)
            val okOut = out as WebSearchOutcome.Ok
            assertTrue("${p.id} results must be empty", okOut.results.isEmpty())
            assertFalse("${p.id} truncated must be false", okOut.truncated)
        }
    }

    @Test fun keyedNonObjectRootIsBadResponse() = runBlocking {
        // root 為 JsonArray（"[]"）：四家皆視為 BAD_RESPONSE（firecrawl 同樣 root cast 失敗 → null）。
        val providers = keyedWithBodies(
            braveBody = "[]",
            tavilyBody = "[]",
            exaBody = "[]",
            firecrawlBody = "[]",
        )
        for (p in providers) {
            val out = p.search("q", 5)
            assertTrue("${p.id} must be Failed", out is WebSearchOutcome.Failed)
            assertEquals(p.id, WebFailureReason.BAD_RESPONSE, (out as WebSearchOutcome.Failed).reason)
        }
    }

    @Test fun keyedMissingResultsArrayIsBadResponse() = runBlocking {
        val providers = keyedWithBodies(
            braveBody = """{"web":{}}""",
            tavilyBody = """{"foo":1}""",
            exaBody = """{"foo":1}""",
            firecrawlBody = """{"success":true}""",
        )
        for (p in providers) {
            val out = p.search("q", 5)
            assertTrue("${p.id} must be Failed", out is WebSearchOutcome.Failed)
            assertEquals(p.id, WebFailureReason.BAD_RESPONSE, (out as WebSearchOutcome.Failed).reason)
        }
    }

    @Test fun keyedItemNotObjectIsSkipped() = runBlocking {
        val valid = """{"title":"Ok","url":"https://example.com/ok"}"""
        val providers = keyedWithBodies(
            braveBody = """{"web":{"results":["not-an-object",$valid]}}""",
            tavilyBody = """{"results":["not-an-object",$valid]}""",
            exaBody = """{"results":["not-an-object",$valid]}""",
            firecrawlBody = """{"success":true,"data":{"web":["not-an-object",$valid]}}""",
        )
        for (p in providers) {
            val out = p.search("q", 5)
            assertTrue("${p.id} must be Ok", out is WebSearchOutcome.Ok)
            val results = (out as WebSearchOutcome.Ok).results
            assertEquals("${p.id} 恰保留 1 筆 valid hit", 1, results.size)
            assertEquals("https://example.com/ok", results[0].url)
        }
    }

    @Test fun keyedNonStringOrMissingUrlSkipsItem() = runBlocking {
        val items =
            """{"title":"Num","url":123},{"title":"Blank","url":""},{"title":"Null","url":null},""" +
                """{"title":"Ok","url":"https://example.com/ok"}"""
        val providers = keyedWithBodies(
            braveBody = """{"web":{"results":[$items]}}""",
            tavilyBody = """{"results":[$items]}""",
            exaBody = """{"results":[$items]}""",
            firecrawlBody = """{"success":true,"data":{"web":[$items]}}""",
        )
        for (p in providers) {
            val out = p.search("q", 5)
            assertTrue("${p.id} must be Ok", out is WebSearchOutcome.Ok)
            val results = (out as WebSearchOutcome.Ok).results
            assertEquals("${p.id} url 非字串／空／null 皆跳過", 1, results.size)
            assertEquals("https://example.com/ok", results[0].url)
        }
    }

    @Test fun keyedBlankTitleFallsBackToUrl() = runBlocking {
        val blankTitle = """{"title":"   ","url":"https://example.com/ok","description":"d"}"""
        val providers = keyedWithBodies(
            braveBody = """{"web":{"results":[$blankTitle]}}""",
            tavilyBody = """{"results":[$blankTitle]}""",
            exaBody = """{"results":[$blankTitle]}""",
            firecrawlBody = """{"success":true,"data":{"web":[$blankTitle]}}""",
        )
        for (p in providers) {
            val out = p.search("q", 5)
            assertTrue("${p.id} must be Ok", out is WebSearchOutcome.Ok)
            val results = (out as WebSearchOutcome.Ok).results
            assertEquals("${p.id} blank title 必須回退 url", "https://example.com/ok", results.single().title)
        }
    }

    @Test fun keyedSnippetFallbackOrder() = runBlocking {
        // brave：item 僅 text → text；item 僅 snippet（description 缺席）→ snippet。
        // tavily：item 僅 description → description。
        // exa：item 僅 snippet → snippet；item 僅 description（無 highlights／text）→ description。
        // firecrawl：item 僅 snippet（description／markdown 缺席）→ snippet。
        val providers = keyedWithBodies(
            braveBody = """
                {"web":{"results":[
                  {"title":"A","url":"https://example.com/a","text":"brave text hit"},
                  {"title":"B","url":"https://example.com/b","snippet":"brave snippet hit"}
                ]}}
            """.trimIndent(),
            tavilyBody = """{"results":[{"title":"T","url":"https://example.com/t","description":"tavily desc hit"}]}""",
            exaBody = """
                {"results":[
                  {"title":"E1","url":"https://example.com/e1","snippet":"exa snippet hit"},
                  {"title":"E2","url":"https://example.com/e2","description":"exa desc hit"}
                ]}
            """.trimIndent(),
            firecrawlBody = """{"success":true,"data":{"web":[{"title":"F","url":"https://example.com/f","snippet":"firecrawl snippet hit"}]}}""",
        )
        for (p in providers) {
            val out = p.search("q", 5)
            assertTrue("${p.id} must be Ok", out is WebSearchOutcome.Ok)
            val results = (out as WebSearchOutcome.Ok).results
            when (p.id) {
                BraveSearchProvider.ID -> {
                    assertEquals("brave text fallback", "brave text hit", results[0].snippet)
                    assertEquals("brave snippet fallback", "brave snippet hit", results[1].snippet)
                }
                TavilySearchProvider.ID ->
                    assertEquals("tavily description fallback", "tavily desc hit", results.single().snippet)
                ExaSearchProvider.ID -> {
                    assertEquals("exa snippet fallback", "exa snippet hit", results[0].snippet)
                    assertEquals("exa description fallback", "exa desc hit", results[1].snippet)
                }
                FirecrawlSearchProvider.ID ->
                    assertEquals("firecrawl snippet fallback", "firecrawl snippet hit", results.single().snippet)
                else -> error("unexpected keyed id: ${p.id}")
            }
        }
    }

    @Test fun keyedPublishedBlankIsNull() = runBlocking {
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        // published_date 全空白 → trim 後為空 → published==null。
        val body =
            """{"web":{"results":[{"title":"T","url":"https://example.com/p","description":"d","published_date":"   "}]}}"""
        val out = BraveSearchProvider(vault, FakeGet { ok(body = body) }).search("q", 5)
        assertTrue(out is WebSearchOutcome.Ok)
        assertNull((out as WebSearchOutcome.Ok).results.single().published)
    }

    @Test fun keyedPublishedBlankIsNullForAllProviders() = runBlocking {
        // 四家 published 欄位全空白 → trim 後為空 → published==null（同 keyedPublishedBlankIsNull 語義）。
        val providers = keyedWithBodies(
            braveBody = """{"web":{"results":[{"title":"T","url":"https://example.com/b","description":"d","published_date":"   "}]}}""",
            tavilyBody = """{"results":[{"title":"T","url":"https://example.com/t","content":"c","published_date":"   "}]}""",
            exaBody = """{"results":[{"title":"E","url":"https://example.com/e","text":"t","publishedDate":"   "}]}""",
            firecrawlBody = """{"success":true,"data":{"web":[{"title":"F","url":"https://example.com/f","description":"d","publishedDate":"   "}]}}""",
        )
        for (p in providers) {
            val out = p.search("q", 5)
            assertTrue("${p.id} must be Ok", out is WebSearchOutcome.Ok)
            val results = (out as WebSearchOutcome.Ok).results
            assertEquals("${p.id} 恰保留 1 筆 valid hit", 1, results.size)
            assertNull("${p.id} blank published 必須為 null", results.single().published)
        }
    }

    @Test fun keyedUnicodePreserved() = runBlocking {
        val item = """{"title":"標題 🔍","url":"https://example.com/u","description":"片段 🚀"}"""
        val providers = keyedWithBodies(
            braveBody = """{"web":{"results":[$item]}}""",
            tavilyBody = """{"results":[$item]}""",
            exaBody = """{"results":[$item]}""",
            firecrawlBody = """{"success":true,"data":{"web":[$item]}}""",
        )
        for (p in providers) {
            val out = p.search("q", 5)
            assertTrue("${p.id} must be Ok", out is WebSearchOutcome.Ok)
            val results = (out as WebSearchOutcome.Ok).results
            assertEquals("${p.id} title", "標題 🔍", results.single().title)
            assertEquals("${p.id} snippet", "片段 🚀", results.single().snippet)
        }
    }

    // ---- cacheNamespace／Service 整合 ----

    @Test fun keyedCacheNamespacesAreDistinctAndBoundToEndpoint() {
        val vault = FakeVault()
        val providers = defaultKeyedProviders(
            vault,
            FakeGet { ok(body = "{}") },
            FakePost { _, _ -> ok(body = "{}") },
        )
        val endpoints = mapOf(
            BraveSearchProvider.ID to BraveSearchProvider.ENDPOINT,
            TavilySearchProvider.ID to TavilySearchProvider.ENDPOINT,
            ExaSearchProvider.ID to ExaSearchProvider.ENDPOINT,
            FirecrawlSearchProvider.ID to FirecrawlSearchProvider.ENDPOINT,
        )
        val namespaces = providers.map { it.cacheNamespace }
        assertEquals("四家 cache namespace 必須互異", 4, namespaces.distinct().size)
        for (p in providers) {
            assertEquals(
                "${p.id} namespace 必須綁定 endpoint",
                "${p.id}@${endpoints.getValue(p.id)}",
                p.cacheNamespace,
            )
        }
    }

    @Test fun keyedFailureIsNotCached() = runBlocking {
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        val http = FakeGet { ok(500, "x") }
        val svc = WebSearchService(BraveSearchProvider(vault, http))
        val first = svc.search("k", 5)
        val second = svc.search("k", 5)
        assertEquals("Failed 不得寫 cache：兩次皆打 upstream", 2, http.calls.get())
        for (out in listOf(first, second)) {
            assertTrue(out is WebSearchOutcome.Failed)
            assertEquals(WebFailureReason.SUPPLIER_ERROR, (out as WebSearchOutcome.Failed).reason)
        }
    }

    // ---- EMPTY_QUERY ----

    @Test fun keyedEmptyQueryShortCircuitsPerProvider() = runBlocking {
        val vault = FakeVault()
        val httpGet = FakeGet { ok(body = braveJson) }
        val httpPost = FakePost { _, _ -> ok(body = tavilyJson) }
        for (p in defaultKeyedProviders(vault, httpGet, httpPost)) {
            val out = p.search("   ", 5)
            assertTrue("${p.id} must be Unavailable", out is WebSearchOutcome.Unavailable)
            assertEquals(p.id, WebUnavailableReason.EMPTY_QUERY, (out as WebSearchOutcome.Unavailable).reason)
        }
        assertEquals(0, httpGet.calls.get())
        assertEquals(0, httpPost.calls.get())
        assertTrue(vault.seenGet.isEmpty())
    }

    /**
     * 測試專用 vault：getKey 直接回傳儲存的 [CharArray] 參照（不 copyOf），
     * 讓測試可斷言 provider 於 finally 以 fill('\u0000') 抹除 buffer；
     * putKey 直接存參照（不 wipe）。
     */
    private class TrackingVault : KeyVault {
        val keys = mutableMapOf<String, CharArray>()
        val seenGet = mutableListOf<String>()
        val seenHas = mutableListOf<String>()
        var captured: CharArray? = null
            private set

        override suspend fun putKey(providerId: String, apiKey: CharArray) {
            keys[providerId] = apiKey
        }

        override suspend fun getKey(providerId: String): CharArray? {
            seenGet.add(providerId)
            return keys[providerId]?.also { captured = it }
        }

        override suspend fun deleteKey(providerId: String) {
            keys.remove(providerId)
        }

        override suspend fun hasKey(providerId: String): Boolean {
            seenHas.add(providerId)
            return keys.containsKey(providerId)
        }
    }
}
