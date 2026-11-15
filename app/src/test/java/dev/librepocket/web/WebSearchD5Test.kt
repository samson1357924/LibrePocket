package dev.librepocket.web

import dev.librepocket.keystore.KeyVault
import dev.librepocket.provider.ServerToolTranscript
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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
 * - cache＋single-flight（經 [WebSearchService] 包裝；含 CompletableDeferred barrier 的
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

    /**
     * FakeVault that models rotation between the first and second read:
     * the first getKey returns key-A and rotates the stored key to key-B
     * (any rotation done via putKey still wins for subsequent reads).
     */
    private class RotationVault : KeyVault {
        private val map = mutableMapOf<String, CharArray>()
        var reads = 0

        override suspend fun putKey(providerId: String, apiKey: CharArray) {
            map[providerId] = apiKey.copyOf()
        }

        override suspend fun getKey(providerId: String): CharArray? {
            reads++
            val a = map[providerId]?.copyOf()
            if (reads == 1) map[providerId] = "key-B".toCharArray()
            return a
        }

        override suspend fun deleteKey(providerId: String) {
            map.remove(providerId)
        }

        override suspend fun hasKey(providerId: String): Boolean = map.containsKey(providerId)
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

    /**
     * FakeGet variant that exposes request headers to the handler
     *（per-key response dispatch：同一 HTTP endpoint 依 token 回不同 fixture）。
     */
    private class FakeGetWithHeaders(
        val handler: suspend (url: String, headers: Map<String, String>) -> WebHttpResponse,
    ) : WebHttpGet {
        val calls = AtomicInteger(0)
        var lastUrl: String? = null
        var lastHeaders: Map<String, String>? = null
        override suspend fun get(url: String, headers: Map<String, String>): WebHttpResponse {
            calls.incrementAndGet()
            lastUrl = url
            lastHeaders = headers
            return handler(url, headers)
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

    /** Per-title Brave response fixture（snapshot 一致性測試專用；單一 result，URL 固定）。 */
    private fun braveJsonFor(title: String): String =
        """{"web":{"results":[{"title":"$title","url":"https://example.com/hit","description":"d"}]}}"""

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

    @Test fun firecrawlQueryIsCappedAt500() = runBlocking {
        val vault = FakeVault()
        vault.putKey(FirecrawlSearchProvider.DEFAULT_KEY_ID, "k-fire".toCharArray())
        val http = FakePost { _, _ -> ok(body = firecrawlJson) }
        val p = FirecrawlSearchProvider(vault, http)
        val out = p.search("x".repeat(600), 5)
        assertTrue(out is WebSearchOutcome.Ok)
        // v2 `query` 契約上限 500 code units：本層靜默截斷（不送 501+）。
        assertTrue(http.lastBody!!.contains("\"query\":\"${"x".repeat(500)}\""))
        assertFalse(http.lastBody!!.contains("x".repeat(501)))
        // count 不受影響。
        assertTrue(http.lastBody!!.contains("\"limit\":5"))
    }

    @Test fun firecrawlQueryCapDoesNotSplitSurrogatePair() = runBlocking {
        val vault = FakeVault()
        vault.putKey(FirecrawlSearchProvider.DEFAULT_KEY_ID, "k-fire".toCharArray())
        val http = FakePost { _, _ -> ok(body = firecrawlJson) }
        val p = FirecrawlSearchProvider(vault, http)
        // 切點（index 499..500）落在 😀（U+1F600，high surrogate at 499）的中間：
        // 不得送出 unpaired high surrogate（→ JSON 替換／query 毀損）；
        // takeUtf16Prefix 規則：捨去末尾孤立的 high surrogate，送前 499 個 'a'。
        val emoji = "\uD83D\uDE00"
        val out = p.search("a".repeat(499) + emoji, 5)
        assertTrue(out is WebSearchOutcome.Ok)
        assertTrue(http.lastBody!!.contains("\"query\":\"${"a".repeat(499)}\""))
        assertFalse("不得送出 unpaired high surrogate", http.lastBody!!.contains('\uD83D'))
        // 對照組：pair 完整落入 500 code units（498 個 'a'＋😀）→ 不捨去。
        http.calls.set(0)
        http.lastBody = null
        val out2 = p.search("a".repeat(498) + emoji, 5)
        assertTrue(out2 is WebSearchOutcome.Ok)
        assertTrue(http.lastBody!!.contains("\"query\":\"${"a".repeat(498)}${emoji}\""))
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

    // ---- Firecrawl parser 級（parseFirecrawlData） ----

    @Test fun parseFirecrawlDataFlatV1ReturnsNull() {
        assertNull(parseFirecrawlData(Json { ignoreUnknownKeys = true }.parseToJsonElement("""{"success":true,"data":[{"url":"https://example.com/x"}]}""") as JsonObject))
    }

    @Test fun parseFirecrawlDataMissingWebReturnsNull() {
        assertNull(parseFirecrawlData(Json { ignoreUnknownKeys = true }.parseToJsonElement("""{"success":true,"data":{"other":[]}}""") as JsonObject))
    }

    @Test fun parseFirecrawlDataDescriptionBeatsMarkdown() {
        val hits = parseFirecrawlData(Json { ignoreUnknownKeys = true }.parseToJsonElement(
            """{"success":true,"data":{"web":[{"title":"D","url":"https://example.com/d","description":"desc wins","markdown":"md loses"}]}}""",
        ) as JsonObject)
        assertEquals(1, hits!!.size)
        assertEquals("desc wins", hits[0].snippet)
    }

    @Test fun parseFirecrawlDataMarkdownBeatsSnippet() {
        val hits = parseFirecrawlData(Json { ignoreUnknownKeys = true }.parseToJsonElement(
            """{"success":true,"data":{"web":[{"title":"M","url":"https://example.com/m","markdown":"md wins","snippet":"snippet loses"}]}}""",
        ) as JsonObject)
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

    @Test fun protocolNeverFallsBackByStatus() = runBlocking {
        // 400/404/405/410/422 → Failed(PROTOCOL)＋mayFallback(null)=false，四家 provider 全覆蓋。
        // 302 → 未分類狀態 → failureForStatus else-branch → TRANSPORT（行為釘住，非 PROTOCOL）。
        fun providers(vault: FakeVault, httpGet: FakeGet, httpPost: FakePost): List<Pair<String, WebSearchProvider>> = listOf(
            BraveSearchProvider.ID to BraveSearchProvider(vault, httpGet),
            TavilySearchProvider.ID to TavilySearchProvider(vault, httpPost),
            ExaSearchProvider.ID to ExaSearchProvider(vault, httpPost),
            FirecrawlSearchProvider.ID to FirecrawlSearchProvider(vault, httpPost),
        )
        for (status in listOf(400, 404, 405, 410, 422)) {
            val vault = FakeVault()
            for (id in listOf(
                BraveSearchProvider.ID,
                TavilySearchProvider.ID,
                ExaSearchProvider.ID,
                FirecrawlSearchProvider.ID,
            )) {
                vault.putKey(defaultKeyIdOf(id), "k".toCharArray())
            }
            val httpGet = FakeGet { ok(status, "{}") }
            val httpPost = FakePost { _, _ -> ok(status, "{}") }
            for ((id, p) in providers(vault, httpGet, httpPost)) {
                val out = p.search("q", 5)
                assertTrue("$id/$status must be Failed", out is WebSearchOutcome.Failed)
                assertEquals("$id/$status", WebFailureReason.PROTOCOL, (out as WebSearchOutcome.Failed).reason)
                assertFalse("$id/$status must not allow fallback", WebSearchSelector.mayFallback(null, out))
            }
        }
        // 402/409/412/418/451（未分類 4xx）→ mapper else-branch → TRANSPORT（fallbackAllowed=true，mayFallback==true）。
        for (status in listOf(402, 409, 412, 418, 451)) {
            val vault = FakeVault()
            for (id in listOf(
                BraveSearchProvider.ID,
                TavilySearchProvider.ID,
                ExaSearchProvider.ID,
                FirecrawlSearchProvider.ID,
            )) {
                vault.putKey(defaultKeyIdOf(id), "k".toCharArray())
            }
            val httpGet = FakeGet { ok(status, "{}") }
            val httpPost = FakePost { _, _ -> ok(status, "{}") }
            for ((id, p) in providers(vault, httpGet, httpPost)) {
                val out = p.search("q", 5)
                assertTrue("$id/$status must be Failed", out is WebSearchOutcome.Failed)
                assertEquals("$id/$status", WebFailureReason.TRANSPORT, (out as WebSearchOutcome.Failed).reason)
                assertTrue("$id/$status must allow fallback (untagged 4xx)", WebSearchSelector.mayFallback(null, out))
            }
        }
        // 302：未分類 → mapper else-branch → TRANSPORT（四家皆然）。
        run {
            val vault = FakeVault()
            for (id in listOf(
                BraveSearchProvider.ID,
                TavilySearchProvider.ID,
                ExaSearchProvider.ID,
                FirecrawlSearchProvider.ID,
            )) {
                vault.putKey(defaultKeyIdOf(id), "k".toCharArray())
            }
            val httpGet = FakeGet { ok(302, "{}") }
            val httpPost = FakePost { _, _ -> ok(302, "{}") }
            for ((id, p) in providers(vault, httpGet, httpPost)) {
                val out = p.search("q", 5)
                assertTrue("$id/302 must be Failed", out is WebSearchOutcome.Failed)
                assertEquals("$id/302", WebFailureReason.TRANSPORT, (out as WebSearchOutcome.Failed).reason)
            }
        }
    }

    @Test fun mayFallbackPinsFallbackSemantics() {
        val supplierError = WebSearchOutcome.Failed(WebFailureReason.SUPPLIER_ERROR)
        val missingKey = WebSearchOutcome.Unavailable(WebUnavailableReason.MISSING_KEY)
        val ok = WebSearchOutcome.Ok(emptyList())
        // 未 pin＋SUPPLIER_ERROR（fallbackAllowed=true）→ 允許換下一個。
        assertTrue(WebSearchSelector.mayFallback(null, supplierError))
        // pinned 時一律 fail-closed，不論 outcome 種類。
        assertFalse(WebSearchSelector.mayFallback("brave", supplierError))
        // Unavailable 一律不可 fallback。
        assertFalse(WebSearchSelector.mayFallback(null, missingKey))
        // Ok（成功）一律不可 fallback。
        assertFalse(WebSearchSelector.mayFallback(null, ok))
        // pinned＋Unavailable：fail-closed 組合。
        assertFalse(WebSearchSelector.mayFallback("brave", missingKey))
        // 未 pin＋TRANSPORT（fallbackAllowed=true 的唯一其他可 fallback 類）。
        assertTrue(WebSearchSelector.mayFallback(null, WebSearchOutcome.Failed(WebFailureReason.TRANSPORT)))
        // pinned＋Ok：fail-closed 組合。
        assertFalse(WebSearchSelector.mayFallback("brave", ok))
        // pinned＋TRANSPORT：fail-closed 組合。
        assertFalse(WebSearchSelector.mayFallback("brave", WebSearchOutcome.Failed(WebFailureReason.TRANSPORT)))
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

    @Test fun malformedBodyIsBadResponseForPostProviders() = runBlocking {
        // GAP-HTTP-2：與 Brave 對稱的三家 POST provider 壞 JSON → BAD_RESPONSE（fail-closed，零 fallback）。
        val vault = FakeVault()
        for (id in listOf(
            TavilySearchProvider.ID,
            ExaSearchProvider.ID,
            FirecrawlSearchProvider.ID,
        )) {
            vault.putKey(defaultKeyIdOf(id), "k".toCharArray())
        }
        val httpPost = FakePost { _, _ -> ok(body = "not json{{") }
        for (p in defaultKeyedProviders(vault, FakeGet { ok(body = braveJson) }, httpPost)) {
            if (p.id == BraveSearchProvider.ID) continue
            val out = p.search("q", 5)
            assertTrue("${p.id} must be Failed", out is WebSearchOutcome.Failed)
            assertEquals(p.id, WebFailureReason.BAD_RESPONSE, (out as WebSearchOutcome.Failed).reason)
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
        // 四家 provider × preset:／custom:／mcp:／jev 外來命名空間 → IAE。
        for ((id, make) in keyedFactories(vault, httpGet, httpPost)) {
            for (bad in listOf("preset:openai", "custom:xx", "mcp:foo", "jev:search", "jev")) {
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
        val outs = withTimeout(5_000) { jobs.map { it.await() } }
        assertTrue(outs.all { it is WebSearchOutcome.Ok })
        assertEquals(1, http.calls.get())
    }

    @Test fun crossInstanceFingerprintDeterminism() = runBlocking {
        // 跨實例 fingerprint 決定性：同一 vault 的兩個 BraveSearchProvider 實例、
        // 各自包一層 WebSearchService、共享同一 WebSearchCache → fingerprint 相同 →
        // service2 命中 service1 寫入的列（cached=true），service2 零 upstream。
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http1 = FakeGet { ok(body = braveJsonFor("first")) }
        val http2 = FakeGet { ok(body = braveJsonFor("second")) }
        val sharedCache = WebSearchCache()
        val svc1 = WebSearchService(BraveSearchProvider(vault, http1), sharedCache)
        val svc2 = WebSearchService(BraveSearchProvider(vault, http2), sharedCache)
        val first = svc1.search("kotlin", 5)
        assertTrue(first is WebSearchOutcome.Ok)
        assertFalse((first as WebSearchOutcome.Ok).cached)
        assertEquals("first", (first as WebSearchOutcome.Ok).results[0].title)
        assertEquals(1, http1.calls.get())
        val second = svc2.search("kotlin", 5)
        assertTrue("service2 必須命中共用 cache（fingerprint 跨實例決定性）：$second", second is WebSearchOutcome.Ok)
        assertTrue("service2 必須 cached=true：$second", (second as WebSearchOutcome.Ok).cached)
        assertEquals("first", (second as WebSearchOutcome.Ok).results[0].title) // 命中的是 service1 寫入的列
        assertEquals("service2 零 upstream", 0, http2.calls.get())
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
        // credentialState 回 Unavailable（vault 無 key）→ Service 完全旁路快取（不讀不寫）；
        // delegate.search 亦讀不到 key → Unavailable(MISSING_KEY)，零網路。
        val second = svc.search("kotlin", 5)
        assertTrue(second is WebSearchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.MISSING_KEY, (second as WebSearchOutcome.Unavailable).reason)
        assertEquals(1, http.calls.get())
    }

    @Test fun transientProbeFailureBypassesCacheAndRecachesLater() = runBlocking {
        // token 捕捉（A-2 強化）：bypass 路徑不寫 cache，故每次皆新鮮 egress；
        // call #1 帶 key-A；輪換到 B＋憑證讀取暫時故障 → bypass 直接以 B 打 upstream；
        // 後續恢復後 miss → 再打一次並寫入 B 的 fp-B 列 → 最後命中該列。
        val sentTokens = CopyOnWriteArrayList<String?>()
        val vault = FlakyProbeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGetWithHeaders { _, headers ->
            sentTokens.add(headers["X-Subscription-Token"])
            ok(body = braveJson)
        }
        val svc = WebSearchService(BraveSearchProvider(vault, http))
        vault.failRemaining = 1 // 僅第一次 credentialState 讀取拋錯；bypass 路徑不寫 cache
        val first = svc.search("kotlin", 5)
        assertTrue(first is WebSearchOutcome.Ok)
        assertFalse((first as WebSearchOutcome.Ok).cached)
        assertEquals(1, http.calls.get())
        assertEquals("call #1 必須以 key-A egress", listOf("key-A"), sentTokens.toList())
        // A → B 輪換；憑證讀取暫時故障 → bypass，B 必須 fresh 打 upstream，不得命中任何 stale 列。
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-B".toCharArray())
        vault.failRemaining = 1
        val second = svc.search("kotlin", 5)
        assertTrue(second is WebSearchOutcome.Ok)
        assertFalse((second as WebSearchOutcome.Ok).cached)
        assertEquals(2, http.calls.get())
        assertEquals("輪換＋flaky read 後 egress 必須帶 key-B", listOf("key-A", "key-B"), sentTokens.toList())
        // 憑證讀取恢復後：cache 尚未有 B 的列（兩次 bypass 皆未寫）→ 再打一次 upstream，帶 B 並寫入 fp-B 列。
        val third = svc.search("kotlin", 5)
        assertTrue(third is WebSearchOutcome.Ok)
        assertFalse((third as WebSearchOutcome.Ok).cached)
        assertEquals(3, http.calls.get())
        assertEquals(listOf("key-A", "key-B", "key-B"), sentTokens.toList())
        // 後續搜尋：憑證狀態正常 → 命中 B 的指紋綁定 cache 列，零新 egress。
        val fourth = svc.search("kotlin", 5)
        assertTrue(fourth is WebSearchOutcome.Ok)
        assertTrue((fourth as WebSearchOutcome.Ok).cached)
        assertEquals(3, http.calls.get())
        assertEquals(listOf("key-A", "key-B", "key-B"), sentTokens.toList())
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
        // credentialState 回 Unavailable（vault 無 key）→ 旁路快取，不得讀到舊 Ok 列；
        // delegate.search 亦讀不到 key → Unavailable(MISSING_KEY)，零新網路。
        val second = svc.search("kotlin", 5)
        assertTrue(second is WebSearchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.MISSING_KEY, (second as WebSearchOutcome.Unavailable).reason)
        assertEquals(1, http.calls.get())
    }

    @Test fun rotateViaProbeFailureServesFreshUpstream() = runBlocking {
        // token 捕捉（A-1 強化）：A → B 輪換＋憑證讀取暫時故障 → bypass，直接以 B 打 upstream，
        // 不得命中 A 的 stale 列；兩次 egress 分別帶 key-A 與 key-B。
        val sentTokens = CopyOnWriteArrayList<String?>()
        val vault = FlakyProbeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGetWithHeaders { _, headers ->
            sentTokens.add(headers["X-Subscription-Token"])
            ok(body = braveJson)
        }
        val svc = WebSearchService(BraveSearchProvider(vault, http))
        val first = svc.search("kotlin", 5)
        assertTrue(first is WebSearchOutcome.Ok)
        assertEquals(1, http.calls.get())
        assertEquals(listOf("key-A"), sentTokens.toList())
        // A → B 輪換；憑證讀取暫時故障 → bypass，直接以 B 打 upstream，不得命中 A 的 stale 列。
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-B".toCharArray())
        vault.failRemaining = 1
        val second = svc.search("kotlin", 5)
        assertTrue(second is WebSearchOutcome.Ok)
        assertFalse((second as WebSearchOutcome.Ok).cached)
        assertEquals(2, http.calls.get())
        assertEquals("兩次 egress 必須分別帶 key-A 與 key-B", listOf("key-A", "key-B"), sentTokens.toList())
    }

    @Test fun staleCachedRowNeverServedWhenKeyDeletedWithFlakyProbe() = runBlocking {
        val vault = FlakyProbeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGet { ok(body = braveJson) }
        val svc = WebSearchService(BraveSearchProvider(vault, http))
        val first = svc.search("kotlin", 5)
        assertTrue(first is WebSearchOutcome.Ok)
        assertEquals(1, http.calls.get())
        // A 的 Ok 列已寫入 fp-A；接著讓下一次 credentialState 故障並刪除 key：
        // stale A 列＋壞 credentialState＋已撤銷憑證 → 不得回傳 stale Ok，零網路。
        vault.failRemaining = 1
        vault.deleteKey(BraveSearchProvider.DEFAULT_KEY_ID)
        val second = svc.search("kotlin", 5)
        assertTrue(second is WebSearchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.MISSING_KEY, (second as WebSearchOutcome.Unavailable).reason)
        assertEquals(1, http.calls.get())
    }

    @Test fun sentinelSearchesNeverWriteCacheRows() = runBlocking {
        // 缺 key／vault 故障哨兵（Unavailable）不得「退化」成 plain cache：Unavailable 期間每次皆 bypass，
        // 不讀不寫；若 sentinel 路徑寫了 plain 列，後續輪換後的搜尋會被誤中。
        val vault = FlakyProbeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGet { ok(body = braveJson) }
        val svc = WebSearchService(BraveSearchProvider(vault, http))
        vault.failRemaining = 1
        val first = svc.search("kotlin", 5)
        assertTrue(first is WebSearchOutcome.Ok)
        assertEquals(1, http.calls.get())
        // A → B 輪換；憑證讀取暫時故障 → bypass，B 必須 fresh 打 upstream，命中不得寫 cache。
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-B".toCharArray())
        vault.failRemaining = 1
        val second = svc.search("kotlin", 5)
        assertTrue(second is WebSearchOutcome.Ok)
        assertFalse((second as WebSearchOutcome.Ok).cached)
        assertEquals(2, http.calls.get())
        // B → C 輪換；再度 bypass：若 Unavailable 路徑曾寫 plain 列，此搜尋會被 cache 命中。
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-C".toCharArray())
        vault.failRemaining = 1
        val third = svc.search("kotlin", 5)
        assertTrue(third is WebSearchOutcome.Ok)
        assertFalse((third as WebSearchOutcome.Ok).cached)
        assertEquals(3, http.calls.get())
    }

    @Test fun nestedKeyedServicesBypassOnProbeFailure() = runBlocking {
        // token 捕捉（A-14 強化）：outer 憑證讀取故障 → outer bypass → inner 自行跑；
        // inner 恢復後解析得 fresh key-A → fp-A → inner MISS → upstream 必須帶 key-A（非 stale／非別 key）。
        val sentTokens = CopyOnWriteArrayList<String?>()
        val vault = FlakyProbeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGetWithHeaders { _, headers ->
            sentTokens.add(headers["X-Subscription-Token"])
            ok(body = braveJson)
        }
        val inner = WebSearchService(BraveSearchProvider(vault, http))
        val outer = WebSearchService(inner)
        // outer credentialState 故障 → outer 完全 bypass → inner 自行跑：
        // inner 的憑證讀取在 flake 消耗後恢復 → fp-A → inner MISS → upstream 一次；
        // outer bypass 不寫 cache，outer 層 cached=false。
        vault.failRemaining = 1
        val first = outer.search("kotlin", 5)
        assertTrue(first is WebSearchOutcome.Ok)
        assertFalse((first as WebSearchOutcome.Ok).cached)
        assertEquals(1, http.calls.get())
        assertEquals("inner upstream 必須帶 fresh key-A", listOf("key-A"), sentTokens.toList())
        assertEquals("key-A", http.lastHeaders!!["X-Subscription-Token"])
        // 同 query 再搜：outer 解析得 fp-A、outer cache miss → 委派 inner；
        // inner 命中 search #1 寫入的 fp-A 列（cached=true），upstream 不再打。
        val second = outer.search("kotlin", 5)
        assertTrue(second is WebSearchOutcome.Ok)
        assertTrue((second as WebSearchOutcome.Ok).cached)
        assertEquals(1, http.calls.get())
        assertEquals(listOf("key-A"), sentTokens.toList()) // 仍只有一次 egress
    }

    @Test fun nestedServicesUseOuterSnapshotAndNeverReReadVault() = runBlocking {
        // 巢狀 Service：outer 解析一次 vault，sticky 透傳 inner → 最底層 provider。
        // inner／provider 層皆不得重讀 vault（每層各自快取以同一份 snapshot fingerprint 為 key）。
        val vault = RotationVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGetWithHeaders { _, headers ->
            val key = headers["X-Subscription-Token"]
            if (key == "key-A") ok(body = braveJsonFor("A-title")) else ok(body = braveJsonFor("B-title"))
        }
        val inner = WebSearchService(BraveSearchProvider(vault, http))
        val outer = WebSearchService(inner)
        // 第一次：outer 解析（getKey#1 → A）並順手輪換成 B；sticky 透傳 inner → HTTP 仍用 A
        val first = outer.search("kotlin", 5)
        assertTrue(first is WebSearchOutcome.Ok)
        assertEquals("A-title", (first as WebSearchOutcome.Ok).results[0].title)
        assertFalse(first.cached)
        assertEquals("key-A", http.lastHeaders!!["X-Subscription-Token"])
        // 第二次：outer 解析 getKey#2 → B → fpB → outer miss（只有 fpA 列）→ inner sticky(fpB) →
        // inner miss → provider.searchWithSnapshot(B) → HTTP 帶 key-B → B-title。
        // 每次搜尋恰好 1 次 vault reads（outer resolve；inner 全走 sticky，零重讀）。
        val second = outer.search("kotlin", 5)
        assertTrue(second is WebSearchOutcome.Ok)
        assertEquals("B-title", (second as WebSearchOutcome.Ok).results[0].title)
        assertFalse(second.cached)
        assertEquals("key-B", http.lastHeaders!!["X-Subscription-Token"])
        assertEquals(2, http.calls.get())
        // inner 層零憑證讀取（否則總 reads ≥ 3）；輪換只發生一次（reads==1 gate 已消耗）。
        assertEquals(2, vault.reads)
    }

    @Test fun throwingCredentialStateIsUncacheableAndFailClosed() = runBlocking {
        val delegate = object : WebSearchProvider {
            override val id: String = "custom-keyed"
            override val capabilities: Set<WebCapability> = emptySet()
            var calls = 0
            override suspend fun probe(): WebProbe = WebProbe.Ready
            override suspend fun search(query: String, count: Int): WebSearchOutcome {
                calls++
                return WebSearchOutcome.Ok(emptyList())
            }
            override suspend fun credentialState(): CredentialState = throw IllegalStateException("boom")
        }
        val svc = WebSearchService(delegate)
        val out = svc.search("q", 5)
        assertTrue("credentialState 拋出不得原樣逃出：$out", out is WebSearchOutcome.Ok)
        assertEquals(1, delegate.calls)
        // 旁路不寫快取：第二次照樣打 upstream
        svc.search("q", 5)
        assertEquals(2, delegate.calls)
    }

    // ---- 快取讀寫故障注入（clockMs fault injection；pin 住「cache 是純最佳化層」契約） ----

    @Test fun cacheReadFaultDegradesToMissAndServesUpstream() = runBlocking {
        // 讀側故障注入：clockMs 在 entry 存在時才被呼叫；故障一律視為 miss（降級），
        // 上游照常打、回傳 Ok（cached=false），不把故障寫進回傳值。
        var clockBroken = false
        val cache = WebSearchCache(clockMs = {
            if (clockBroken) throw IllegalStateException("clock broken") else 1_000L
        })
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGet { ok(body = braveJsonFor("T")) }
        val svc = WebSearchService(BraveSearchProvider(vault, http, timeoutMs = 60_000), cache)
        withTimeout(5_000) {
            val first = svc.search("kotlin", 5)
            assertTrue(first is WebSearchOutcome.Ok)
            assertEquals(1, http.calls.get())
            clockBroken = true
            val second = svc.search("kotlin", 5)
            // 讀失敗 → miss → 再打 upstream；不得回傳 cache 值
            assertFalse("clock 故障不得命中 cache：$second", (second as WebSearchOutcome.Ok).cached)
            assertEquals("讀失敗必須降級為 miss 重打上游", 2, http.calls.get())
        }
    }

    @Test fun cacheWriteFaultPreservesUpstreamOk() = runBlocking {
        // 寫側故障注入：fresh cache 的 clock 永遠拋 ISE；get 在空 map 上不呼叫 clock → miss；
        // delegate 回 Ok → put 靜默失敗（R3：一般 Exception 不拋出）→ 回傳值仍是 Ok。
        val cache = WebSearchCache(clockMs = { throw IllegalStateException("clock broken") })
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGet { ok(body = braveJsonFor("T")) }
        val svc = WebSearchService(BraveSearchProvider(vault, http, timeoutMs = 60_000), cache)
        withTimeout(5_000) {
            val out = svc.search("kotlin", 5)
            assertTrue("寫入故障不得把 Ok 改寫成 Failed：$out", out is WebSearchOutcome.Ok)
            assertEquals(1, http.calls.get())
        }
    }

    @Test fun cacheReadCancellationFaultPropagates() = runBlocking {
        // 讀側 CE：entry 存在時 clockMs 拋 CE → safeGet 原樣透傳（取消絕不降級為 miss）。
        var clockCancel = false
        val cache = WebSearchCache(clockMs = {
            if (clockCancel) throw CancellationException("clock cancelled") else 1_000L
        })
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGet { ok(body = braveJsonFor("T")) }
        val svc = WebSearchService(BraveSearchProvider(vault, http, timeoutMs = 60_000), cache)
        withTimeout(5_000) {
            svc.search("kotlin", 5) // 種入一列（正常時鐘）
            clockCancel = true
            try {
                svc.search("kotlin", 5)
                fail("讀側 CE 故障必須原樣透傳（不得降級為 miss）")
            } catch (_: CancellationException) {
                // expected
            }
            assertEquals("CE 透傳時不得重打上游", 1, http.calls.get())
        }
    }

    @Test fun cacheWriteCancellationFaultPropagates() = runBlocking {
        // 寫側 CE：fresh cache 的 clock 永遠拋 CE；get 在空 map 上不呼叫 clock → miss →
        // delegate 回 Ok → putByKey 拋 CE → leader 的 CE 分支以 okForFollowers 完成 follower，
        // leader 自身重拋 CE → 呼叫端必須收到 CE。
        val cache = WebSearchCache(clockMs = { throw CancellationException("clock cancelled") })
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGet { ok(body = braveJsonFor("T")) }
        val svc = WebSearchService(BraveSearchProvider(vault, http, timeoutMs = 60_000), cache)
        withTimeout(5_000) {
            try {
                svc.search("kotlin", 5)
                fail("寫側 CE 故障必須原樣透傳（leader 的 CE 分支完成 follower 後自身重拋）")
            } catch (_: CancellationException) {
                // expected
            }
            assertEquals(1, http.calls.get())
        }
    }

    // ---- CE 原樣透傳矩陣（CredentialState／probe／vault read／cacheNamespace） ----

    @Test fun credentialStateCancellationPropagatesAtService() = runBlocking {
        // credentialState() 拋 CE：不得映射為 Unavailable／旁路／Failed（CE 原樣透傳）。
        val delegate = object : WebSearchProvider {
            override val id: String = "cs-ce"
            override val capabilities: Set<WebCapability> = emptySet()
            override suspend fun probe(): WebProbe = WebProbe.Ready
            override suspend fun search(query: String, count: Int): WebSearchOutcome =
                WebSearchOutcome.Ok(emptyList())
            override suspend fun credentialState(): CredentialState = throw CancellationException()
        }
        val svc = WebSearchService(delegate)
        try {
            svc.search("q", 5)
            fail("credentialState 的 CE 必須原樣透傳（不得映射為 Unavailable／Failed）")
        } catch (_: CancellationException) {
            // expected
        }
    }

    @Test fun probeHasKeyCancellationPropagates() = runBlocking {
        // probe() 內 hasKey 拋 CE：CE 必須原樣透傳（不得映射為 NotReady）。
        val tracking = TrackingVault()
        val ceVault = object : KeyVault by tracking {
            override suspend fun hasKey(providerId: String): Boolean =
                throw CancellationException()
        }
        val p = BraveSearchProvider(ceVault, FakeGet { ok(body = braveJson) })
        try {
            p.probe()
            fail("probe() 的 hasKey CE 必須原樣透傳（不得映射為 NotReady）")
        } catch (_: CancellationException) {
            // expected
        }
    }

    @Test fun vaultReadCancellationPropagates() = runBlocking {
        // keyed credentialState() 的 vault 讀取拋 CE：CE 必須原樣透傳（不得映射為 Unavailable）。
        val ceVault = object : KeyVault {
            override suspend fun putKey(providerId: String, apiKey: CharArray) {}
            override suspend fun getKey(providerId: String): CharArray? = throw CancellationException()
            override suspend fun deleteKey(providerId: String) {}
            override suspend fun hasKey(providerId: String): Boolean = true
        }
        val p = BraveSearchProvider(ceVault, FakeGet { ok(body = braveJson) })
        try {
            p.credentialState()
            fail("vault 讀取的 CE 必須原樣透傳（不得映射為 Unavailable）")
        } catch (_: CancellationException) {
            // expected
        }
    }

    @Test fun cacheNamespaceGetterCancellationPropagatesAndNonCeMapsToTransport() = runBlocking {
        // cacheNamespace getter 拋 CE：CE 原樣透傳（不得映射 Failed）；
        // 拋一般 Exception：fail-closed 映射為 Failed(TRANSPORT)。
        var throwCe = true
        val delegate = object : WebSearchProvider {
            override val id: String = "cn-thrower"
            override val capabilities: Set<WebCapability> = emptySet()
            override val cacheNamespace: String
                get() = if (throwCe) throw CancellationException() else throw IllegalStateException("x")
            override suspend fun probe(): WebProbe = WebProbe.Ready
            override suspend fun search(query: String, count: Int): WebSearchOutcome =
                WebSearchOutcome.Ok(emptyList())
            override suspend fun credentialState(): CredentialState = CredentialState.NotApplicable
        }
        val svc = WebSearchService(delegate)
        try {
            svc.search("q", 5)
            fail("cacheNamespace 的 CE 必須原樣透傳（不得映射 Failed）")
        } catch (_: CancellationException) {
            // expected
        }
        throwCe = false
        val out = svc.search("q", 5)
        assertTrue("一般 Exception 必須映射 Failed(TRANSPORT)：$out", out is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.TRANSPORT, (out as WebSearchOutcome.Failed).reason)
    }

    // ---- 旁路（Unavailable）取消語義：delegate 吞取消的防禦 ----

    @Test fun cancellationSwallowingDelegateOnBypassPathPropagatesCe() = runBlocking {
        // 壞 delegate：credentialState=Unavailable → bypass；search() 內吞掉 CE 仍回 Ok。
        // central ensureActive() 必須讓外部取消原樣透傳（不得把取消結果當正常 Ok 回傳）。
        val delegate = object : WebSearchProvider {
            override val id: String = "cancellation-swallower"
            override val capabilities: Set<WebCapability> = emptySet()
            override suspend fun probe(): WebProbe = WebProbe.Ready
            override suspend fun search(query: String, count: Int): WebSearchOutcome {
                return try {
                    delay(10_000)
                    WebSearchOutcome.Ok(emptyList())
                } catch (_: CancellationException) {
                    WebSearchOutcome.Ok(emptyList()) // 吞掉取消，仍回值
                }
            }
            override suspend fun credentialState(): CredentialState = CredentialState.Unavailable
        }
        val svc = WebSearchService(delegate)
        // 兩種機制皆無法從 await() 的結果區分 fix（runtime 會把任何正常完成遮蔽成 JCE）；
        // 改以觀測「Service 是否回傳值」為區分通道：
        // fix 生效 → bypass 的 ensureActive() 拋 CE → coroutine 以 CE 結束 → result 從不完成；
        // pre-fix → bypass 直接回 Ok → result.complete(Ok) → isCompleted == true。
        val result = CompletableDeferred<WebSearchOutcome>()
        val job = launch { result.complete(svc.search("kotlin", 5)) }
        delay(50)
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertFalse("吞取消 delegate 在 bypass 路徑不得回傳正常值（central ensureActive() 必須拋 CE）：$result", result.isCompleted)
    }

    @Test fun throwingDelegateOnBypassPathAfterCancelPropagatesCeNotTransport() = runBlocking {
        // 壞 delegate：bypass 路徑把取消表現成一般 Exception；外部已取消時 CE 必須透傳，
        // 不得映射成可 fallback 的 Failed(TRANSPORT)。
        val delegate = object : WebSearchProvider {
            override val id: String = "exception-swallower"
            override val capabilities: Set<WebCapability> = emptySet()
            override suspend fun probe(): WebProbe = WebProbe.Ready
            override suspend fun search(query: String, count: Int): WebSearchOutcome {
                return try {
                    delay(10_000)
                    WebSearchOutcome.Ok(emptyList())
                } catch (_: CancellationException) {
                    throw IllegalStateException("cancelled-as-exception") // 把取消表現成一般例外
                }
            }
            override suspend fun credentialState(): CredentialState = CredentialState.Unavailable
        }
        val svc = WebSearchService(delegate)
        // 同一觀測通道：fix 生效 → catch 分支先 ensureActive() 拋 CE → result 從不完成；
        // pre-fix → 一般 Exception 映射成 Failed(TRANSPORT) 回傳 → result.complete(Failed) → isCompleted == true。
        val result = CompletableDeferred<WebSearchOutcome>()
        val job = launch { result.complete(svc.search("kotlin", 5)) }
        delay(50)
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertFalse("取消後 delegate 拋一般 Exception 不得映射 TRANSPORT（CE 必須透傳）：$result", result.isCompleted)
    }

    @Test fun bypassPathReturnsDelegateValueWhenNotCancelled() = runBlocking {
        // 正常路徑（未取消）：ensureActive() 為 no-op；delegate 的 Ok 原樣回傳。
        val delegate = object : WebSearchProvider {
            override val id: String = "unavailable-plain"
            override val capabilities: Set<WebCapability> = emptySet()
            override suspend fun probe(): WebProbe = WebProbe.Ready
            override suspend fun search(query: String, count: Int): WebSearchOutcome =
                WebSearchOutcome.Ok(emptyList())
            override suspend fun credentialState(): CredentialState = CredentialState.Unavailable
        }
        val svc = WebSearchService(delegate)
        val out = svc.search("kotlin", 5)
        assertEquals(WebSearchOutcome.Ok(emptyList()), out)
    }

    @Test fun throwingDelegateOnBypassPathMapsToFailedTransportWhenNotCancelled() = runBlocking {
        // 未取消時 raw Exception → Failed(TRANSPORT)（既有 fail-closed 映射保留）。
        val delegate = object : WebSearchProvider {
            override val id: String = "unavailable-thrower"
            override val capabilities: Set<WebCapability> = emptySet()
            override suspend fun probe(): WebProbe = WebProbe.Ready
            override suspend fun search(query: String, count: Int): WebSearchOutcome =
                throw IllegalStateException("boom")
            override suspend fun credentialState(): CredentialState = CredentialState.Unavailable
        }
        val svc = WebSearchService(delegate)
        val out = svc.search("kotlin", 5)
        assertTrue(out is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.TRANSPORT, (out as WebSearchOutcome.Failed).reason)
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
        for (state in listOf("started", "searching", "completed", "update", "failed", "cancelled")) {
            assertEquals(
                ServerToolTranscript.webSearchMarker(state),
                WebSearchDispatch.hostedMarker(state),
            )
        }
    }

    // unknown/ill-formed 狀態字一律拒絕（Set<String> allowlist：不在六字集合內＝拋 IAE；CR/LF 亦然）。
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
            assertThrows("hostedMarker(\"$state\") must throw IllegalArgumentException (unknown/ill-formed state)", IllegalArgumentException::class.java) {
                WebSearchDispatch.hostedMarker(state)
            }
        }
    }

    @Test fun hostedMarkerAcceptsKnownStates() {
        // 僅六個已知狀態字（production 收斂後的狀態字＋錯誤路徑字）一律接受。
        for (state in listOf("started", "searching", "completed", "update", "failed", "cancelled")) {
            val marker = WebSearchDispatch.hostedMarker(state)
            assertTrue("marker for $state must contain the state", marker.contains(state))
        }
    }

    @Test fun hostedMarkerRejectsUnknownStates() {
        // `in_progress` 不是 production 詞彙（webSearchStateOf 先收斂為 started），必須拒絕；
        // 其餘皆為未知／ill-formed 狀態字。
        val unknown = listOf(
            "in_progress",
            "arbitrary_state",
            "progress",
            "",
            "Completed",
            "completed ",
            " a",
            "a]b",
            "9lives",
            "a".repeat(33),
            "started\ncompleted",
            "\tstarted",
        )
        for (state in unknown) {
            assertThrows(
                "hostedMarker(\"$state\") must throw IllegalArgumentException (unknown/ill-formed state)",
                IllegalArgumentException::class.java,
            ) {
                WebSearchDispatch.hostedMarker(state)
            }
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

    @Test fun pinnedNotReadyInvokesNoRunners() = runBlocking {
        // pinned provider 未就緒：Unavailable(PINNED_UNAVAILABLE)，hosted／local runner 皆不得被呼叫。
        val registry = registryOf("brave")
        var hostedCalls = 0
        var localCalls = 0
        val out = WebSearchDispatch.search(
            pinnedId = "brave",
            hostedAvailable = true, // hosted 可用也不升：pinned 缺就緒 → fail-closed
            registry = registry,
            query = "kotlin",
            count = 5,
            isReady = { false },
            runHosted = { hostedCalls++; "completed" },
            runLocal = { _, _, _ -> localCalls++; WebSearchOutcome.Ok(emptyList()) },
        )
        assertTrue(out is WebDispatchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.PINNED_UNAVAILABLE, (out as WebDispatchOutcome.Unavailable).reason)
        assertEquals(0, hostedCalls)
        assertEquals(0, localCalls)
    }

    @Test fun serviceWrappedRunLocalCachedTruePassthrough() = runBlocking {
        // dispatch 的 runLocal 傳入經 WebSearchService 包裝的 provider：
        // 同 query 第二次 → cache 命中 → Local 的 outcome 帶 cached=true。
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        val http = FakeGet { ok(body = braveJson) }
        val svc = WebSearchService(BraveSearchProvider(vault, http))
        val registry = WebSearchRegistry(listOf(svc))
        val outs = mutableListOf<WebDispatchOutcome>()
        for (i in 1..2) {
            val out = WebSearchDispatch.search(
                pinnedId = "brave",
                hostedAvailable = false,
                registry = registry,
                query = "kotlin",
                count = 5,
                isReady = { true },
                runHosted = { "completed" },
                runLocal = { p, q, c -> svc.search(q, c) },
            )
            outs.add(out)
        }
        assertTrue(outs[0] is WebDispatchOutcome.Local)
        assertTrue(outs[1] is WebDispatchOutcome.Local)
        val firstOk = (outs[0] as WebDispatchOutcome.Local).outcome
        val secondOk = (outs[1] as WebDispatchOutcome.Local).outcome
        assertTrue(firstOk is WebSearchOutcome.Ok)
        assertFalse((firstOk as WebSearchOutcome.Ok).cached)
        assertTrue(secondOk is WebSearchOutcome.Ok)
        assertTrue("service 包裝的 provider 第二次必須 cached=true：$secondOk", (secondOk as WebSearchOutcome.Ok).cached)
        assertEquals(1, http.calls.get()) // 只有第一次打 upstream
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
        // (b) runHosted 回傳未知狀態字 → hostedMarker 的 IllegalArgumentException 傳播出 search()。
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
        assertTrue(hostedEx.message?.contains("one of") == true)
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

    @Test fun hostedMarkerStateSpaceIsExactlyKnown() {
        // HOSTED_STATES 為 private；測試釘住恰好六個已知狀態字：
        // 六字皆接受（marker 含該字）；in_progress 等未收斂字一律 IAE。
        val hostedStates = WebSearchDispatch::class.java
            .getDeclaredField("HOSTED_STATES")
            .apply { isAccessible = true }
            .get(null) as Set<String>
        assertEquals(setOf("started", "searching", "completed", "update", "failed", "cancelled"), hostedStates)
        for (state in listOf("started", "searching", "completed", "update", "failed", "cancelled")) {
            val marker = WebSearchDispatch.hostedMarker(state)
            assertTrue("marker for $state must contain the state", marker.contains(state))
        }
        // `in_progress` 不是 production 詞彙（webSearchStateOf 收斂為 started），必須拒絕。
        assertThrows(IllegalArgumentException::class.java) {
            WebSearchDispatch.hostedMarker("in_progress")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WebSearchDispatch.hostedMarker("_completed")
        }
    }

    @Test fun hostedMarkerUnknownServerStatePropagates() {
        // worst-case server drift：runHosted 回傳 "in_progress"（我們的 enum 未收斂此字）
        // → hostedMarker 拋 IAE → 例外傳播出 search()（D3 契約：本層不捕捉）。
        // （provider 路徑不會以 server 原字直接呼叫 hostedMarker——webSearchStateOf 先收斂；
        //   本測試釘住 dispatch 層的 guard。）
        val registry = registryOf("brave")
        val ex = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                WebSearchDispatch.search(
                    pinnedId = null,
                    hostedAvailable = true,
                    registry = registry,
                    query = "kotlin",
                    count = 5,
                    isReady = { true },
                    runHosted = { "in_progress" },
                    runLocal = { _, _, _ -> throw AssertionError("runLocal must not be called") },
                )
            }
        }
        assertTrue(ex.message?.contains("one of") == true)
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

    @Test fun keyedOkForFollowersLeaderThrowsCacheEmpty() = runBlocking {
        // keyed 版 okForFollowersLeaderThrowsCacheEmpty：delegate 回 Ok 的同拍取消呼叫端
        // （Ok 已到手、cache 寫入前的窗口）——leader 拋 CE、follower 以 Ok 完成、cache 不寫。
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger(0)
        val cache = WebSearchCache()
        val delegate = object : WebSearchProvider {
            override val id: String = "keyed-ok-then-cancel"
            override val capabilities: Set<WebCapability> = setOf(WebCapability.REQUIRES_KEY)
            override suspend fun probe(): WebProbe = WebProbe.Ready
            override suspend fun search(query: String, count: Int): WebSearchOutcome =
                WebSearchOutcome.Ok(emptyList()) // Service 對 Ready sticky 走 searchWithSnapshot；本路徑僅防禦性保留
            override suspend fun credentialState(): CredentialState =
                CredentialState.Ready(CredentialSnapshot("key-A".toCharArray(), "fp-key-A"))
            override suspend fun searchWithSnapshot(
                snapshot: CredentialSnapshot,
                query: String,
                count: Int,
            ): WebSearchOutcome {
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                // Self-cancel：確定性製造「Ok 已到手、ensureActive 觀察到取消」窗口。
                kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.cancel()
                return WebSearchOutcome.Ok(listOf(WebSearchResult("r", "https://example.com/", "s", null, "example.com")))
            }
        }
        val svc = WebSearchService(delegate, cache)
        val leader = async { svc.search("kotlin", 5) }
        withTimeout(5_000) { entered.await() }
        // UNDISPATCHED：return 時 follower 已掛上 shared deferred。
        val follower = async(start = CoroutineStart.UNDISPATCHED) { svc.search("kotlin", 5) }
        release.complete(Unit)
        try {
            withTimeout(5_000) { leader.await() }
            fail("leader must throw CancellationException")
        } catch (_: CancellationException) {
            // expected：leader 拿到 Ok 後觀察到取消 → 仍拋 CE，follower 以該 Ok 完成
        }
        val out = withTimeout(5_000) { follower.await() }
        assertTrue(out is WebSearchOutcome.Ok)
        assertFalse((out as WebSearchOutcome.Ok).cached)
        assertEquals(0, cache.size) // leader 取消 → 不寫 cache
        // 第二次搜尋：cache 無列 → 再打一次 upstream（calls == 2）。
        // 同 delegate 會再自取消 → CE 原樣透傳；以 try 捕捉只為計數。
        try {
            withTimeout(5_000) { svc.search("kotlin", 5) }
        } catch (_: CancellationException) {
            // expected：同一自取消形狀再現
        }
        assertEquals(2, calls.get())
    }

    @Test fun leaderThrowableCompletesFollowerExceptionallyAndPropagatesRaw() = runBlocking {
        // Leader 在 delegate 內拋 Error（如 AssertionError）：leader 原樣拋出；follower 等待的 shared deferred
        // 以該 Error 完成 → follower.await() 亦原樣拋出（不映射成 Failed、不 swallow）。
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delegate = object : WebSearchProvider {
            override val id: String = "keyed-error"
            override val capabilities: Set<WebCapability> = setOf(WebCapability.REQUIRES_KEY)
            override suspend fun probe(): WebProbe = WebProbe.Ready
            override suspend fun search(query: String, count: Int): WebSearchOutcome =
                WebSearchOutcome.Ok(emptyList()) // 防禦性保留（keyed 路徑走 searchWithSnapshot）
            override suspend fun credentialState(): CredentialState =
                CredentialState.Ready(CredentialSnapshot("key-A".toCharArray(), "fp-key-A"))
            override suspend fun searchWithSnapshot(
                snapshot: CredentialSnapshot,
                query: String,
                count: Int,
            ): WebSearchOutcome {
                entered.complete(Unit)
                release.await()
                throw AssertionError("boom")
            }
        }
        val svc = WebSearchService(delegate, WebSearchCache())
        // 子 coroutine 例外在 runBlocking 下會結構化上拋；以 try/catch 收斂的 coroutine 只為確定性計數。
        val leader = async {
            try {
                svc.search("kotlin", 5)
            } catch (e: AssertionError) {
                e
            }
        }
        withTimeout(5_000) { entered.await() }
        // UNDISPATCHED：follower 於 leader 拋出前掛上 shared deferred；同樣以 try/catch 收斂。
        val follower = async(start = CoroutineStart.UNDISPATCHED) {
            try {
                svc.search("kotlin", 5)
            } catch (e: AssertionError) {
                e
            }
        }
        release.complete(Unit)
        val le = withTimeout(5_000) { leader.await() } as? AssertionError
        assertEquals("boom", le?.message)
        val fe = withTimeout(5_000) { follower.await() } as? AssertionError
        assertEquals("boom", fe?.message)
    }

    // ---- F1：credentialState→fetch 快照一致性 ----

    @Test fun rotationBetweenProbeAndFetchServesConsistentSnapshot() = runBlocking {
        // 第一次 getKey（外層 resolve）回傳 A 並把 vault 輪換為 B：
        // snapshot 必須全程帶 A（HTTP 與 cache fingerprint 同源），輪換後的 B 只能在下一次搜尋出現。
        val vault = RotationVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGetWithHeaders { _, headers ->
            if (headers["X-Subscription-Token"] == "key-A") {
                ok(body = braveJsonFor("A-title"))
            } else {
                ok(body = braveJsonFor("B-title"))
            }
        }
        val svc = WebSearchService(BraveSearchProvider(vault, http))
        val first = svc.search("kotlin", 5)
        assertTrue(first is WebSearchOutcome.Ok)
        assertEquals("A-title", (first as WebSearchOutcome.Ok).results[0].title)
        assertFalse(first.cached)
        assertEquals(1, http.calls.get())
        // vault 已於第一次 getKey 輪換為 B（RotationVault 行為）：
        // 第二次 resolve 得 B → 打 upstream 時帶 B，不得命中 fp-A 的 cache 列。
        val second = svc.search("kotlin", 5)
        assertTrue(second is WebSearchOutcome.Ok)
        assertEquals("B-title", (second as WebSearchOutcome.Ok).results[0].title)
        assertFalse(second.cached)
        assertEquals(2, http.calls.get())
        // 輪回 A：fp-A 的 cache 列必須命中；B 的列絕不得被當成 A 的資料。
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val third = svc.search("kotlin", 5)
        assertTrue(third is WebSearchOutcome.Ok)
        val thirdOk = third as WebSearchOutcome.Ok
        assertEquals("A-title", thirdOk.results[0].title)
        assertTrue(thirdOk.cached)
        assertEquals(2, http.calls.get())
    }

    @Test fun followerReElectionAfterLeaderDeathUsesCurrentCredential() = runBlocking {
        // Follower 已掛上 leader 的 shared deferred（同 fp-A cacheKey）；此時 vault A→B：
        // leader 死亡後 follower 重選必須重讀當前 vault，以 B 打 upstream，不得重用 A 的憑證／HTTP 依據。
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGetWithHeaders { _, headers ->
            entered.complete(Unit)
            release.await()
            if (headers["X-Subscription-Token"] == "key-A") {
                ok(body = braveJsonFor("A-title"))
            } else {
                ok(body = braveJsonFor("B-title"))
            }
        }
        val svc = WebSearchService(BraveSearchProvider(vault, http, timeoutMs = 60_000))
        val leader = async { svc.search("kotlin", 5) }
        withTimeout(5_000) { entered.await() }
        // UNDISPATCHED：return 時 follower 已掛上 shared deferred。
        val follower = async(start = CoroutineStart.UNDISPATCHED) { svc.search("kotlin", 5) }
        delay(50)
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-B".toCharArray())
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
        assertFalse((out as WebSearchOutcome.Ok).cached)
        assertEquals("B-title", out.results[0].title)
        assertEquals("key-B", http.lastHeaders!!["X-Subscription-Token"])
        assertEquals(2, http.calls.get())
    }

    @Test fun deletedCredentialNeverResentAfterLeaderDeath() = runBlocking {
        // Follower 已掛上 leader 的 shared deferred；leader 死亡前 key 被刪除：
        // follower 重選 resolve 不到憑證 → 旁路 → delegate search 回 MISSING_KEY，零網路，
        // 已刪除的憑證絕不會被重送。
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGetWithHeaders { _, _ ->
            entered.complete(Unit)
            release.await()
            ok(body = braveJsonFor("A-title"))
        }
        val svc = WebSearchService(BraveSearchProvider(vault, http, timeoutMs = 60_000))
        val leader = async { svc.search("kotlin", 5) }
        withTimeout(5_000) { entered.await() }
        val follower = async(start = CoroutineStart.UNDISPATCHED) { svc.search("kotlin", 5) }
        delay(50)
        vault.deleteKey(BraveSearchProvider.DEFAULT_KEY_ID)
        leader.cancel()
        release.complete(Unit)
        try {
            withTimeout(5_000) { leader.await() }
            fail("leader must throw CancellationException")
        } catch (_: CancellationException) {
            // expected：leader 取消，shared deferred completeExceptionally
        }
        val out = withTimeout(5_000) { follower.await() }
        assertTrue(out is WebSearchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.MISSING_KEY, (out as WebSearchOutcome.Unavailable).reason)
        assertEquals(1, http.calls.get()) // 只有 leader 的那一次；retry 沒發任何請求
    }

    @Test fun nestedInnerLeaderDeathAfterDeleteNeverResendsDeletedKey() = runBlocking {
        // 巢狀：outer1／outer2 共用 inner Service；outer1 在 HTTP（帶 key-A）中被取消 →
        // inner leader CE；outer2 follower 醒來重選時不得沿用 sticky，必須回當前 vault
        // 重解析 → key 已刪除 → 旁路 → Unavailable(MISSING_KEY)，零第二次上游，
        // 已刪除的憑證絕不會以第二次請求重送。
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val sentTokens = CopyOnWriteArrayList<String?>()
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGetWithHeaders { _, headers ->
            sentTokens.add(headers["X-Subscription-Token"])
            entered.complete(Unit)
            release.await()
            ok(body = braveJsonFor("A-title"))
        }
        val inner = WebSearchService(BraveSearchProvider(vault, http, timeoutMs = 60_000))
        val outer1 = WebSearchService(inner)
        val outer2 = WebSearchService(inner)
        val leader = async { outer1.search("kotlin", 5) }
        withTimeout(5_000) { entered.await() }
        // UNDISPATCHED：return 時 follower 已掛上 inner 的 shared deferred。
        val follower = async(start = CoroutineStart.UNDISPATCHED) { outer2.search("kotlin", 5) }
        delay(50)
        vault.deleteKey(BraveSearchProvider.DEFAULT_KEY_ID)
        leader.cancel()
        release.complete(Unit)
        try {
            withTimeout(5_000) { leader.await() }
            fail("leader must throw CancellationException")
        } catch (_: CancellationException) {
            // expected：leader 取消，inner shared deferred completeExceptionally
        }
        val out = withTimeout(5_000) { follower.await() }
        // fix 生效：follower 重選 resolve 不到憑證 → 旁路 → provider vault 讀取無 key →
        // Unavailable(MISSING_KEY)，零網路；不得以 key-A 重送。
        assertTrue("follower 重選必須看見 key 已刪除（零網路 MISSING_KEY）：$out", out is WebSearchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.MISSING_KEY, (out as WebSearchOutcome.Unavailable).reason)
        assertEquals(1, http.calls.get()) // 只有 leader 那一次；retry 沒發任何請求
        assertEquals(listOf<String?>("key-A"), sentTokens.toList())
    }

    @Test fun nestedRetryAfterLeaderDeathUsesCurrentRotatedKey() = runBlocking {
        // 巢狀：leader 死亡前 key 已輪換 A→B；follower 重選 resolve 得「當前」key-B，
        // 必須以 B 重打上游（fresh 憑證），不得沿用 A。
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val sentTokens = CopyOnWriteArrayList<String?>()
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGetWithHeaders { _, headers ->
            val token = headers["X-Subscription-Token"]
            sentTokens.add(token)
            entered.complete(Unit)
            release.await()
            ok(body = braveJsonFor(if (token == "key-B") "B-title" else "A-title"))
        }
        val inner = WebSearchService(BraveSearchProvider(vault, http, timeoutMs = 60_000))
        val outer1 = WebSearchService(inner)
        val outer2 = WebSearchService(inner)
        val leader = async { outer1.search("kotlin", 5) }
        withTimeout(5_000) { entered.await() }
        // UNDISPATCHED：return 時 follower 已掛上 inner 的 shared deferred。
        val follower = async(start = CoroutineStart.UNDISPATCHED) { outer2.search("kotlin", 5) }
        delay(50)
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-B".toCharArray())
        leader.cancel()
        release.complete(Unit)
        try {
            withTimeout(5_000) { leader.await() }
            fail("leader must throw CancellationException")
        } catch (_: CancellationException) {
            // expected：leader 取消，inner shared deferred completeExceptionally
        }
        val out = withTimeout(5_000) { follower.await() }
        // fix 生效：follower 重選 resolve 得 key-B（fp-B miss）→ 以上游重打一次，帶 key-B。
        assertTrue("follower 重選必須以當前 key-B 重打上游：$out", out is WebSearchOutcome.Ok)
        assertFalse((out as WebSearchOutcome.Ok).cached)
        assertEquals("重選後上游必須收到 key-B 並回 B-title", "B-title", (out as WebSearchOutcome.Ok).results[0].title)
        assertEquals(2, http.calls.get())
        assertEquals(listOf("key-A", "key-B"), sentTokens.toList())
    }

    // ---- R6-1 revision-change pin 測試（#138 巢狀 sticky × 憑證輪換） ----

    @Test fun nestedRevisionChangeRebindsOuterCacheIdentity() = runBlocking {
        // R6-1 THE pin（侵入性 pin；R6-1 機制）：
        // leader 於 HTTP 中被取消、vault 已輪換 A→B；follower 重選時 inner 退回當前 vault
        // 重解析，但 fingerprint 與進入時（fp(A)）不符 → inner 拋出內部標記例外（module-internal
        // CredentialRevisionChangedException）→ outer 層 leader CE 分支捕捉並以當前憑證（B）
        // 重綁 cache identity 重執行自己的迭代。
        // 結果：fp(B) 列取得 B-title（非 cached）；fp(A) 列絕不被寫入 B 的資料——
        // 輪換回 A 後 out2 必須 miss 並以上游 key-A 重打（A-title、cached=false），
        // out3 才命中 fp(A) 快取（A-title、cached=true）。
        // 注意：回應 body 以 token 分派（`if (token == "key-A") "A-title" else "B-title"`）；
        // 規格原始 size-based 條件（`sentTokens.size == 1`）在第三次呼叫（out2）時 size==3，
        // 會回 B-title 而永遠過不了 post-fix 斷言（見 PR 回報）。token-based 分派保留
        // pre-fix 失敗簽章（Ok(cached=true, B-title)）。
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val sentTokens = CopyOnWriteArrayList<String?>()
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGetWithHeaders { _, headers ->
            val token = headers["X-Subscription-Token"]
            sentTokens.add(token)
            entered.complete(Unit)
            release.await()
            ok(body = braveJsonFor(if (token == "key-A") "A-title" else "B-title"))
        }
        val inner = WebSearchService(BraveSearchProvider(vault, http, timeoutMs = 60_000))
        val outer1 = WebSearchService(inner)
        val outer2 = WebSearchService(inner)

        val leader = async { outer1.search("kotlin", 5) }
        withTimeout(5_000) { entered.await() }
        // UNDISPATCHED：return 時 follower 已掛上 inner 的 shared deferred。
        val follower = async(start = CoroutineStart.UNDISPATCHED) { outer2.search("kotlin", 5) }
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-B".toCharArray())
        leader.cancel()
        release.complete(Unit)
        try { withTimeout(5_000) { leader.await() }; fail("leader must throw CE") } catch (_: CancellationException) { /* expected */ }

        val out = withTimeout(5_000) { follower.await() }
        assertTrue(out is WebSearchOutcome.Ok)
        assertEquals("B-title", (out as WebSearchOutcome.Ok).results[0].title)
        assertFalse((out as WebSearchOutcome.Ok).cached)
        // 輪換回 A：fp(A) 列不得含 B 資料（pre-fix 此處回 cached=true/B-title）。
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val out2 = withTimeout(5_000) { outer2.search("kotlin", 5) }
        assertTrue(out2 is WebSearchOutcome.Ok)
        assertEquals("A-title", (out2 as WebSearchOutcome.Ok).results[0].title)
        assertFalse("fp(A) 列不得提供 B 的資料：$out2", (out2 as WebSearchOutcome.Ok).cached)
        val out3 = withTimeout(5_000) { outer2.search("kotlin", 5) }
        assertTrue((out3 as WebSearchOutcome.Ok).cached)
        assertEquals("A-title", (out3 as WebSearchOutcome.Ok).results[0].title)
        assertEquals(listOf("key-A", "key-B", "key-A"), sentTokens.toList())
    }

    @Test fun nestedRevisionSignalNeverEscapesAsException() = runBlocking {
        // nestedRevisionChangeRebindsOuterCacheIdentity 的公開入口 pin：runner 重跑同一流程，
        // 要求搜尋的公開呼叫端（outer2.search）永遠不會觀察到內部標記例外／CE／Failed——
        // 標記只在 outer 層 leader CE 分支內被吸收並重綁，最終結果與一般成功搜尋無異。
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val sentTokens = CopyOnWriteArrayList<String?>()
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGetWithHeaders { _, headers ->
            val token = headers["X-Subscription-Token"]
            sentTokens.add(token)
            entered.complete(Unit)
            release.await()
            ok(body = braveJsonFor(if (token == "key-A") "A-title" else "B-title"))
        }
        val inner = WebSearchService(BraveSearchProvider(vault, http, timeoutMs = 60_000))
        val outer1 = WebSearchService(inner)
        val outer2 = WebSearchService(inner)
        val leader = async { outer1.search("kotlin", 5) }
        withTimeout(5_000) { entered.await() }
        val follower = async(start = CoroutineStart.UNDISPATCHED) { outer2.search("kotlin", 5) }
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-B".toCharArray())
        leader.cancel()
        release.complete(Unit)
        try { withTimeout(5_000) { leader.await() }; fail("leader must throw CE") } catch (_: CancellationException) { /* expected */ }

        // 公開入口必須正常返回（zero CE / zero Failed）；例外會直接從 await() 拋出使測試失敗。
        val out = withTimeout(5_000) { follower.await() }
        assertTrue("outer2.search 公開入口不得拋出或回傳 Failed：$out", out is WebSearchOutcome.Ok)
        assertEquals("B-title", (out as WebSearchOutcome.Ok).results[0].title)

        // 輪換回 A 後的公開呼叫同樣不得觀察到標記（out2 必須 miss 並以上游重打；out3 命中 fp(A)）。
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val out2 = withTimeout(5_000) { outer2.search("kotlin", 5) }
        assertTrue("out2 公開入口回傳必須為 Ok：$out2", out2 is WebSearchOutcome.Ok)
        assertEquals("A-title", (out2 as WebSearchOutcome.Ok).results[0].title)
        assertFalse((out2 as WebSearchOutcome.Ok).cached)
    }

    @Test fun threeLevelNestingBubblesRevisionSignal() = runBlocking {
        // 三層巢狀 outerTopA／outerTopB → middle → inner → provider：
        // leader 於 HTTP 中被取消、vault 輪換 A→B；inner 的 demoting 重解析指紋不符 →
        // 標記向上信號；middle 層吸收並以 fp(B) 重綁重執行；最外層 outerTopB 完成。
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val sentTokens = CopyOnWriteArrayList<String?>()
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGetWithHeaders { _, headers ->
            val token = headers["X-Subscription-Token"]
            sentTokens.add(token)
            entered.complete(Unit)
            release.await()
            ok(body = braveJsonFor(if (token == "key-A") "A-title" else "B-title"))
        }
        val inner = WebSearchService(BraveSearchProvider(vault, http, timeoutMs = 60_000))
        val middle = WebSearchService(inner)
        val outerTopA = WebSearchService(middle)
        val outerTopB = WebSearchService(middle)

        val leader = async { outerTopA.search("kotlin", 5) }
        withTimeout(5_000) { entered.await() }
        val follower = async(start = CoroutineStart.UNDISPATCHED) { outerTopB.search("kotlin", 5) }
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-B".toCharArray())
        leader.cancel()
        release.complete(Unit)
        try { withTimeout(5_000) { leader.await() }; fail("leader must throw CE") } catch (_: CancellationException) { /* expected */ }

        val out = withTimeout(5_000) { follower.await() }
        assertTrue(out is WebSearchOutcome.Ok)
        assertEquals("B-title", (out as WebSearchOutcome.Ok).results[0].title)
        assertFalse((out as WebSearchOutcome.Ok).cached)
        assertEquals(2, http.calls.get())
        assertEquals(listOf("key-A", "key-B"), sentTokens.toList())
    }

    @Test fun sameRevisionDemotionProducesNoSignal() = runBlocking {
        // 同一 revision（delete + re-put 相同 key-A，fingerprint 不變）：follower 重選後
        // demoting 重解析得到同指紋 → gate 通過、無標記信號；結果以當前（相同）key-A 重打上游，
        // 恰好兩次 upstream（N1 retry 本身），不得有 revision restart 造成的重複請求。
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val sentTokens = CopyOnWriteArrayList<String?>()
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGetWithHeaders { _, headers ->
            val token = headers["X-Subscription-Token"]
            sentTokens.add(token)
            entered.complete(Unit)
            release.await()
            ok(body = braveJsonFor(if (token == "key-A") "A-title" else "B-title"))
        }
        val inner = WebSearchService(BraveSearchProvider(vault, http, timeoutMs = 60_000))
        val outer1 = WebSearchService(inner)
        val outer2 = WebSearchService(inner)
        val leader = async { outer1.search("kotlin", 5) }
        withTimeout(5_000) { entered.await() }
        val follower = async(start = CoroutineStart.UNDISPATCHED) { outer2.search("kotlin", 5) }
        // 刪除後重新寫入「相同」key-A：fingerprint 不變 → demotion 不得產生標記。
        vault.deleteKey(BraveSearchProvider.DEFAULT_KEY_ID)
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        leader.cancel()
        release.complete(Unit)
        try { withTimeout(5_000) { leader.await() }; fail("leader must throw CE") } catch (_: CancellationException) { /* expected */ }

        val out = withTimeout(5_000) { follower.await() }
        assertTrue("同 revision demotion 不得產生標記重試：$out", out is WebSearchOutcome.Ok)
        assertEquals("A-title", (out as WebSearchOutcome.Ok).results[0].title)
        assertFalse((out as WebSearchOutcome.Ok).cached)
        // 恰好兩次上游（leader + N1 retry）；revision restart 不得被觸發。
        assertEquals(listOf("key-A", "key-A"), sentTokens.toList())
        assertEquals(2, http.calls.get())
    }

    @Test fun demotionWipeUnwrapAccounting() = runBlocking {
        // R6-1 × R6-2 交界：demote 路徑（gate 拋 CredentialRevisionChangedException）上
        // self-resolved 的 mid-flight-B snapshot 由 inner 的 finally 負責 wipe（此時 stickyIter == null，
        // ownership 為本層 self-owned）；全程每一次 getKey 發出的 buffer 於最外層 finally
        // 收斂後皆須為全零（含 inner gate 路徑那份 B buffer 與 outer2 重綁後那份 B2 buffer）。
        // 注意：TrackingVault 的 getKey 回傳共享參照（putKey 亦不 copy），wipe 會把 vault 內
        // 存儲的同一參照清零，後續 resolve 會得到全零 key 而回 MISSING_KEY——無法承載此流程；
        // 故此測試以 RecordingVault（copy-on-read＋全量記錄）取代，語義同 TrackingVault 但避開共享參照。
        val recording = RecordingVault()
        recording.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val sentTokens = CopyOnWriteArrayList<String?>()
        val http = FakeGetWithHeaders { _, headers ->
            val token = headers["X-Subscription-Token"]
            sentTokens.add(token)
            entered.complete(Unit)
            release.await()
            ok(body = braveJsonFor(if (token == "key-A") "A-title" else "B-title"))
        }
        val inner = WebSearchService(BraveSearchProvider(recording, http, timeoutMs = 60_000))
        val outer1 = WebSearchService(inner)
        val outer2 = WebSearchService(inner)
        val leader = async { outer1.search("kotlin", 5) }
        withTimeout(5_000) { entered.await() }
        val follower = async(start = CoroutineStart.UNDISPATCHED) { outer2.search("kotlin", 5) }
        recording.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-B".toCharArray())
        leader.cancel()
        release.complete(Unit)
        try { withTimeout(5_000) { leader.await() }; fail("leader must throw CE") } catch (_: CancellationException) { /* expected */ }

        val out = withTimeout(5_000) { follower.await() }
        assertTrue(out is WebSearchOutcome.Ok)
        assertEquals("B-title", (out as WebSearchOutcome.Ok).results[0].title)
        // 至少涵蓋：leader self-resolve（A）、follower self-resolve（A）、
        // inner gate demote self-resolve（B）、outer2 revision restart self-resolve（B）＝ 4 份 buffer。
        assertTrue("應記錄到至少 4 份 getKey buffer：${recording.capturedAll.size}", recording.capturedAll.size >= 4)
        assertTrue(
            "demote 路徑與全程所有 recorded buffer 皆必須被 wipe（全零）：${recording.capturedAll.map { b -> b.count { it != '\u0000' } }}",
            recording.capturedAll.all { buf -> buf.all { it == '\u0000' } },
        )
    }

    @Test fun revisionRestartBudgetExhaustionFailsClosed() = runBlocking {
        // R6-1 護欄（ARM-ORDER pin）：delegate 每次呼叫都回報憑證已修訂（內部標記）→
        // CE 分支先行、以當前憑證重綁重執行；重試 3 次（MAX_REVISION_RESTARTS）後 fail-closed
        // 回 Failed(TRANSPORT)。
        // ARM-ORDER 說明：若誤落入非 CE 的一般 Exception 分支，標記會被直接映射為
        // Failed(TRANSPORT) 而不會重試——delegate 呼叫次數即為判別依據（此處必為 4＝1＋3 次 restart）；
        // Exception arm 先行會使 calls == 1，budget 機制從未啟動。
        // 注意：純 vault rotation（每讀輪換 A/B/C/D）無法製造本路徑——top-level entryFingerprint == null
        // 的層級永遠不會拋 gate 標記，restart 後的 re-resolve 會直接綁定新 fp 而成功；
        // 每次 demotion 的 follower-death 為單次事件，restart 鏈不會自我複製。
        // 故以「每次 searchWithSnapshot/search 皆拋內部標記」的 delegate 固定重現預算耗盡。
        // （規格原始描述「requiresKey 序列 AtomicInteger＋固定陣列週期」的 vault 下，
        // follower 初始 resolve 即綁定 fp(B)，與 leader 的 fp(A) 不再合流，不會產生 demotion，
        // 亦不可能回 Failed(TRANSPORT)——見 PR 回報。）
        val marker = RevisionMarkerAlwaysDelegate()
        val service = WebSearchService(marker)

        val out = withTimeout(5_000) { service.search("kotlin", 5) }
        assertTrue("預算耗盡必須 fail-closed 回 Failed：$out", out is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.TRANSPORT, (out as WebSearchOutcome.Failed).reason)
        assertEquals("CE arm 必須重試 3 次（1＋3＝4 次 delegate 呼叫）：${marker.calls.get()}", 4, marker.calls.get())

        // 不得寫入任何 cache 列：切換為穩定行為後，第一次搜尋必須再次打上游（calls 持續增長）。
        marker.markerMode = false
        val out2 = withTimeout(5_000) { service.search("kotlin", 5) }
        assertTrue(out2 is WebSearchOutcome.Ok)
        assertFalse("budget 耗盡不得焊死 cache 列：$out2", (out2 as WebSearchOutcome.Ok).cached)
        assertEquals(5, marker.calls.get())
    }

    @Test fun genuineCancellationWinsOverRevisionRetry() = runBlocking {
        // R6-1 delta 3：並發取消（genuine）優先於 revision restart。
        // T1 shape，但在 follower 醒來前同時取消外層兩個 Service（leader 與 follower）——
        // follower await() 醒來 ensureActive 直接失敗，CE 原樣透傳：
        // 絕不、絕不映射為 Failed、絕不回傳 Ok（leader flight 也不該被吃掉）。
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val sentTokens = CopyOnWriteArrayList<String?>()
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGetWithHeaders { _, headers ->
            val token = headers["X-Subscription-Token"]
            sentTokens.add(token)
            entered.complete(Unit)
            release.await()
            ok(body = braveJsonFor(if (token == "key-A") "A-title" else "B-title"))
        }
        val inner = WebSearchService(BraveSearchProvider(vault, http, timeoutMs = 60_000))
        val outer1 = WebSearchService(inner)
        val outer2 = WebSearchService(inner)
        val leader = async { outer1.search("kotlin", 5) }
        withTimeout(5_000) { entered.await() }
        val follower = async(start = CoroutineStart.UNDISPATCHED) { outer2.search("kotlin", 5) }
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-B".toCharArray())
        // 在 follower 醒來之前，同時取消 leader 與 follower。
        leader.cancel()
        follower.cancel()
        release.complete(Unit)
        try { withTimeout(5_000) { leader.await() }; fail("leader must throw CE") } catch (_: CancellationException) { /* expected */ }
        try {
            withTimeout(5_000) { follower.await() }
            fail("follower must throw plain CE")
        } catch (e: CancellationException) {
            // 並發取消優先：絕不回傳 Failed／Ok；且絕不攜帶內部標記（該標記不可達於並發取消路徑）。
            assertTrue("genuine 並發取消不得映射為內部 revision 標記：$e", e !is CredentialRevisionChangedException)
        }
    }

    @Test fun cancellationDuringCacheableNotApplicableSearchPropagatesCe() = runBlocking {
        // 壞 delegate：credentialState 吞掉 CE 仍「正常返回」NotApplicable（已取消仍回值）。
        // fix 生效 → state 解析後的 ensureActive() 拋 CE → result 從不完成；
        // pre-fix → cache hit 直接回 Ok(cached=true) → result.complete(Ok) → isCompleted == true。
        var swallow = false
        val delegate = object : WebSearchProvider {
            override val id: String = "cancellation-swallower-na"
            override val capabilities: Set<WebCapability> = emptySet()
            override suspend fun probe(): WebProbe = WebProbe.Ready
            override suspend fun search(query: String, count: Int): WebSearchOutcome =
                WebSearchOutcome.Ok(emptyList())
            override suspend fun credentialState(): CredentialState =
                if (!swallow) {
                    CredentialState.NotApplicable
                } else {
                    try {
                        delay(60_000)
                        CredentialState.NotApplicable
                    } catch (_: CancellationException) {
                        CredentialState.NotApplicable // 吞掉取消仍「正常返回」
                    }
                }
        }
        val svc = WebSearchService(delegate)
        // 先以正常呼叫種入 plain cache 列（cached=false）。
        val seeded = svc.search("kotlin", 5)
        assertTrue(seeded is WebSearchOutcome.Ok)
        assertFalse((seeded as WebSearchOutcome.Ok).cached)
        swallow = true
        val result = CompletableDeferred<WebSearchOutcome>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { result.complete(svc.search("kotlin", 5)) }
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertFalse("cache hit 路徑不得回傳已取消呼叫端的值（state 解析後 ensureActive() 必須拋 CE）：$result", result.isCompleted)
    }

    @Test fun cancellationDuringCacheableReadySearchPropagatesCe() = runBlocking {
        // 真 keyed stack：TrackingVault 先以 key-A 種入 cache（#rev:<fp>）；吞掉 CE 的 vault
        // 在已取消 context 下仍回 key-A → state「正常返回」Ready。
        // fix 生效 → state 解析後 ensureActive() 拋 CE → result 從不完成；
        // pre-fix → cache hit 回 Ok(cached=true) → isCompleted == true。
        val vault = CancellationSwallowingVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "key-A".toCharArray())
        val http = FakeGet { ok(body = braveJson) }
        val svc = WebSearchService(BraveSearchProvider(vault, http, timeoutMs = 60_000))
        val seeded = svc.search("kotlin", 5)
        assertTrue(seeded is WebSearchOutcome.Ok)
        assertFalse((seeded as WebSearchOutcome.Ok).cached)
        assertEquals(1, http.calls.get())
        vault.swallow = true
        val result = CompletableDeferred<WebSearchOutcome>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { result.complete(svc.search("kotlin", 5)) }
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertFalse("keyed cache hit 路徑不得回傳已取消呼叫端的值（state 解析後 ensureActive() 必須拋 CE）：$result", result.isCompleted)
        assertEquals(1, http.calls.get()) // 零額外上游
    }

    @Test fun blankQueryCancelledCallerNeverReceivesDelegateValue() = runBlocking {
        // 壞 delegate：search 內取消呼叫端後仍「正常返回」Ok（吞掉 CE 的壞 delegate，不掛起直接回值）；
        // fix → 空 query 旁路的 post-call ensureActive() 拋 CE → result 從不完成；
        // pre-fix → delegate 的 Ok 直接回傳 → result.complete(Ok) → isCompleted == true。
        val delegate = object : WebSearchProvider {
            override val id: String = "blank-cancellation-swallower"
            override val capabilities: Set<WebCapability> = emptySet()
            override suspend fun probe(): WebProbe = WebProbe.Ready
            override suspend fun search(query: String, count: Int): WebSearchOutcome {
                kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.cancel() // 在 delegate 內取消呼叫端
                return WebSearchOutcome.Ok(emptyList())
            }
            override suspend fun credentialState(): CredentialState = CredentialState.NotApplicable
        }
        val svc = WebSearchService(delegate)
        val result = CompletableDeferred<WebSearchOutcome>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { result.complete(svc.search("  ", 5)) }
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertFalse("空 query 已取消的呼叫端不得收到 delegate 的值（bypass ensureActive() 必須拋 CE）：$result", result.isCompleted)
    }

    // ---- Service 層 snapshot 抹除（Phase 1/2 已 pin provider 層；此處 pin Service 的 stickyIter == null 路徑） ----

    @Test fun serviceLevelWipeOnSuccessAndHttp500() = runBlocking {
        // Service 路徑的 wipe：stickyIter == null → searchInternal 的 finally 負責抹除；
        // TrackingVault 的 stored buffer 即 snapshot 的 key（無 copy），故可直接觀測。
        // (a) 成功路徑。
        run {
            val vault = TrackingVault()
            vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "super-secret-key".toCharArray())
            val svc = WebSearchService(BraveSearchProvider(vault, FakeGet { ok(body = braveJson) }))
            val out = svc.search("kotlin", 5)
            assertTrue(out is WebSearchOutcome.Ok)
            assertTrue("service 成功路徑後 captured buffer 必須被抹除", vault.captured!!.all { it == '\u0000' })
        }
        // (b) HTTP 500（Failed 值回傳，非例外）路徑。
        run {
            val vault = TrackingVault()
            vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "super-secret-key".toCharArray())
            val svc = WebSearchService(BraveSearchProvider(vault, FakeGet { ok(500, "x") }))
            val out = svc.search("kotlin", 5)
            assertTrue(out is WebSearchOutcome.Failed)
            assertEquals(WebFailureReason.SUPPLIER_ERROR, (out as WebSearchOutcome.Failed).reason)
            assertTrue("service HTTP 500 後 captured buffer 必須被抹除", vault.captured!!.all { it == '\u0000' })
        }
    }

    @Test fun serviceLevelWipeOnCancellationAfterStateResolution() = runBlocking {
        // R6-2 / pre-fix leak：state 解析得到 Ready(S)（freshly-minted buffer）後、
        // try/finally 開始之前的 ensureActive() 拋 CE，S 從未進入 finally 的保護範圍 → buffer 殘留。
        // fix 後：snapshot 於 ensureActive() 之前指派（try 內第一語句群）→ finally 抹除恰一次。
        val vault = TrackingVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "super-secret-key".toCharArray())
        val http = FakeGet { ok(body = braveJson) }
        val svc = WebSearchService(BraveSearchProvider(vault, http, timeoutMs = 60_000))
        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
            kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.cancel() // 呼叫前已取消
            svc.search("kotlin", 5)
        }
        try {
            withTimeout(5_000) { deferred.await() }
            fail("已取消的呼叫端必須收到 CancellationException")
        } catch (_: CancellationException) {
            // expected：N2 語義不變，CE 原樣透傳（不映射為 Failed／TRANSPORT）
        }
        assertEquals(0, http.calls.get()) // CE 於任何下游動作之前拋出 → 零 HTTP
        assertTrue("state 解析後 CE 透傳時，self-resolved snapshot 必須已被 finally 抹除",
            vault.captured!!.all { it == '\u0000' })
    }

    @Test fun innerFrameInCancelledStateDoesNotWipeOuterSticky() = runBlocking {
        val vault = TrackingVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "super-secret-key".toCharArray())
        val provider = BraveSearchProvider(vault, FakeGet { ok(body = braveJson) })
        // 外層（所有權測試端）單次 resolve——ownership 移交呼叫方（本測試）
        val state = provider.credentialState()
        val snapshot = (state as CredentialState.Ready).snapshot
        val inner = WebSearchService(provider)
        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
            kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            inner.searchWithSnapshot(snapshot, "kotlin", 5)
        }
        try {
            withTimeout(5_000) { deferred.await() }
            fail("已取消的巢狀呼叫端必須收到 CancellationException")
        } catch (_: CancellationException) {
            // expected
        }
        assertTrue("inner（sticky）絕不得 wipe 外層所有權的 snapshot", vault.captured!!.all { it != '\u0000' })
        assertEquals(1, vault.seenGet.size) // sticky 接管：inner 未重讀 vault
        snapshot.wipe()                      // 外層持有者模擬
        assertTrue(vault.captured!!.all { it == '\u0000' })
    }

    @Test fun nestedOuterFrameWipesStickyAfterBothFramesUnwind() = runBlocking {
        val vault = TrackingVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "super-secret-key".toCharArray())
        val selfCancelHttp = FakeGet {
            kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.cancel() // HTTP 回傳前取消呼叫端
            ok(body = braveJson)
        }
        val inner = WebSearchService(BraveSearchProvider(vault, selfCancelHttp, timeoutMs = 60_000))
        val outer = WebSearchService(inner)
        try {
            outer.search("kotlin", 5)
            fail("已取消的巢狀呼叫端必須收到 CancellationException")
        } catch (_: CancellationException) {
            // expected：leader 於取消後回值，ensureActive 拋 CE；outer finally 抹除 sticky
        }
        assertEquals(1, selfCancelHttp.calls.get())     // leader 恰打一次 upstream
        assertTrue("內層（stickyIter != null）不 wipe；外層 finally 必須已抹除", vault.captured!!.all { it == '\u0000' })
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
      withTimeout(5_000) {
        val vault = TrackingVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "super-secret-key".toCharArray())
        val http = FakeGet { delay(10_000); ok(body = braveJson) }
        val out = BraveSearchProvider(vault, http, timeoutMs = 50).search("kotlin", 5)
        assertTrue(out is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.TIMEOUT, (out as WebSearchOutcome.Failed).reason)
        assertTrue("timeout 後 key buffer 必須被抹除", vault.captured!!.all { it == '\u0000' })
      }
    }

    @Test fun keyArrayWipedOnBlankKey() = runBlocking {
        val vault = TrackingVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "   ".toCharArray())
        val out = BraveSearchProvider(vault, FakeGet { ok(body = braveJson) }).search("kotlin", 5)
        assertTrue(out is WebSearchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.MISSING_KEY, (out as WebSearchOutcome.Unavailable).reason)
        assertTrue("blank key 的 buffer 必須被抹除", vault.captured!!.all { it == '\u0000' })
    }

    @Test fun keyArrayWipedOnSuccessAcrossPostKeyProviders() = runBlocking {
        // POST keyed 三家（Tavily／Exa／Firecrawl）的 success 抹除矩陣（鏡像 Brave keyArrayWipedOnSuccess）。
        for (make in listOf<(TrackingVault) -> WebSearchProvider>(
            { v -> TavilySearchProvider(v, FakePost { _, _ -> ok(body = tavilyJson) }) },
            { v -> ExaSearchProvider(v, FakePost { _, _ -> ok(body = exaJson) }) },
            { v -> FirecrawlSearchProvider(v, FakePost { _, _ -> ok(body = firecrawlJson) }) },
        )) {
            val vault = TrackingVault()
            val p = make(vault)
            vault.putKey(defaultKeyIdOf(p.id), "super-secret-key".toCharArray())
            val out = p.search("kotlin", 5)
            assertTrue("${out} must be Ok", out is WebSearchOutcome.Ok)
            assertTrue("success 後 key buffer 必須被抹除：$out", vault.captured!!.all { it == '\u0000' })
        }
    }

    @Test fun keyArrayWipedOnHttp500AcrossPostKeyProviders() = runBlocking {
        // POST keyed 三家的 HTTP 500 抹除矩陣（鏡像 Brave keyArrayWipedOnHttp500）。
        for (make in listOf<(TrackingVault) -> WebSearchProvider>(
            { v -> TavilySearchProvider(v, FakePost { _, _ -> ok(500, "x") }) },
            { v -> ExaSearchProvider(v, FakePost { _, _ -> ok(500, "x") }) },
            { v -> FirecrawlSearchProvider(v, FakePost { _, _ -> ok(500, "x") }) },
        )) {
            val vault = TrackingVault()
            val p = make(vault)
            vault.putKey(defaultKeyIdOf(p.id), "super-secret-key".toCharArray())
            val out = p.search("kotlin", 5)
            assertTrue("${out} must be Failed", out is WebSearchOutcome.Failed)
            assertEquals(WebFailureReason.SUPPLIER_ERROR, (out as WebSearchOutcome.Failed).reason)
            assertTrue("HTTP 500 後 key buffer 必須被抹除：$out", vault.captured!!.all { it == '\u0000' })
        }
    }

    @Test fun keyArrayWipedOnTimeoutAcrossPostKeyProviders() = runBlocking {
        // POST keyed 三家的 timeout 抹除矩陣（鏡像 Brave keyArrayWipedOnTimeout）。
        for (make in listOf<(TrackingVault) -> WebSearchProvider>(
            { v -> TavilySearchProvider(v, FakePost { _, _ -> delay(10_000); ok(body = tavilyJson) }, timeoutMs = 50) },
            { v -> ExaSearchProvider(v, FakePost { _, _ -> delay(10_000); ok(body = exaJson) }, timeoutMs = 50) },
            { v -> FirecrawlSearchProvider(v, FakePost { _, _ -> delay(10_000); ok(body = firecrawlJson) }, timeoutMs = 50) },
        )) {
            val vault = TrackingVault()
            val p = make(vault)
            vault.putKey(defaultKeyIdOf(p.id), "super-secret-key".toCharArray())
            val out = withTimeout(5_000) { p.search("kotlin", 5) }
            assertTrue("${out} must be Failed", out is WebSearchOutcome.Failed)
            assertEquals(WebFailureReason.TIMEOUT, (out as WebSearchOutcome.Failed).reason)
            assertTrue("timeout 後 key buffer 必須被抹除：$out", vault.captured!!.all { it == '\u0000' })
        }
    }

    @Test fun emptyAndDelBlankKeysAreMissingKeyZeroNetwork() = runBlocking {
        // (i) empty CharArray(0)：vault 回傳空陣列 → Unavailable、零 HTTP；stored buffer 無需抹除（空無內容）。
        run {
            val vault = TrackingVault()
            vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, CharArray(0))
            val httpGet = FakeGet { ok(body = braveJson) }
            val out = BraveSearchProvider(vault, httpGet).search("q", 5)
            assertTrue(out is WebSearchOutcome.Unavailable)
            assertEquals(WebUnavailableReason.MISSING_KEY, (out as WebSearchOutcome.Unavailable).reason)
            assertEquals(0, httpGet.calls.get())
            assertTrue("getKey 必須被呼叫", vault.captured != null)
        }
        // (ii) DEL (U+007F)：CONTROL 類不可見字元 → Unavailable、零 HTTP；buffer 必須被抹除。
        run {
            val vault = TrackingVault()
            vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "\u007F".toCharArray())
            val httpGet = FakeGet { ok(body = braveJson) }
            val out = BraveSearchProvider(vault, httpGet).search("q", 5)
            assertTrue(out is WebSearchOutcome.Unavailable)
            assertEquals(WebUnavailableReason.MISSING_KEY, (out as WebSearchOutcome.Unavailable).reason)
            assertEquals(0, httpGet.calls.get())
            assertTrue("DEL blank key 的 buffer 必須被抹除", vault.captured!!.all { it == '\u0000' })
        }
    }

    @Test fun cfBlankKeyIsMissingKeyZeroNetworkAndCapturedBufferWiped() = runBlocking {
        // Unicode FORMAT 類不可見字元（soft hyphen、ZWSP、BOM）構成的 key：不可見 ⇒ 缺憑證，
        // 不得產生 HTTP 呼叫；buffer 必須被抹除。（U+007F DEL 等 CONTROL 類亦涵蓋。）
        val vault = TrackingVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "\u00AD\u200B\uFEFF".toCharArray())
        val httpGet = FakeGet { ok(body = braveJson) }
        val out = BraveSearchProvider(vault, httpGet).search("kotlin", 5)
        assertTrue(out is WebSearchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.MISSING_KEY, (out as WebSearchOutcome.Unavailable).reason)
        assertEquals(0, httpGet.calls.get())
        assertTrue("CF blank key 的 buffer 必須被抹除", vault.captured!!.all { it == '\u0000' })
    }

    // ---- key 述詞：trim + 可列印 ASCII（0x21–0x7E）gate ----

    @Test fun keyWithEmbeddedNewlineIsMissingKeyZeroNetwork() = runBlocking {
        // 內嵌 CR/LF（含 header 注入形狀）：非可列印 ASCII → Unavailable、零網路；captured buffer 必須被抹除。
        val vault = TrackingVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "abc\r\nevil".toCharArray())
        val httpGet = FakeGet { ok(body = braveJson) }
        val out = BraveSearchProvider(vault, httpGet).search("kotlin", 5)
        assertTrue(out is WebSearchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.MISSING_KEY, (out as WebSearchOutcome.Unavailable).reason)
        assertEquals(0, httpGet.calls.get())
        assertTrue("內嵌換行 key 的 captured buffer 必須被抹除", vault.captured!!.all { it == '\u0000' })
    }

    @Test fun keyWithEmbeddedControlOrFormatCharIsMissingKey() = runBlocking {
        // 內嵌 CONTROL（U+0001、DEL U+007F）與 FORMAT（U+200B）字元：非可列印 ASCII → Unavailable、零網路。
        for (variant in listOf("ab\u0001cd", "ab\u007Fcd", "ab\u200Bcd")) {
            val vault = TrackingVault()
            vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, variant.toCharArray())
            val httpGet = FakeGet { ok(body = braveJson) }
            val out = BraveSearchProvider(vault, httpGet).search("kotlin", 5)
            assertTrue("內嵌控制／格式字元變體應為 Unavailable：$out", out is WebSearchOutcome.Unavailable)
            assertEquals(WebUnavailableReason.MISSING_KEY, (out as WebSearchOutcome.Unavailable).reason)
            assertEquals(0, httpGet.calls.get())
            assertTrue("內嵌控制／格式字元 key 的 captured buffer 必須被抹除", vault.captured!!.all { it == '\u0000' })
        }
    }

    @Test fun keyBoundaryPrintableAsciiAcceptedInteriorSpaceRejected() = runBlocking {
        // Printable-ASCII 接受邊界：0x21 '!' 與 0x7E '~' 必須被接受（正常打上游）；
        // 內嵌空白（0x20，trim 只正規化兩端）→ Unavailable、零網路。
        for (variant in listOf("!", "~", "a!b", "a~b", "ab cd")) {
            val vault = TrackingVault()
            vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, variant.toCharArray())
            val httpGet = FakeGet { ok(body = braveJson) }
            val out = BraveSearchProvider(vault, httpGet).search("kotlin", 5)
            if (variant == "ab cd") {
                assertTrue("內嵌空白 key 必須 Unavailable：$out", out is WebSearchOutcome.Unavailable)
                assertEquals(0, httpGet.calls.get())
            } else {
                assertTrue("可列印 ASCII 邊界 key 必須通過：$variant", out is WebSearchOutcome.Ok)
                assertEquals(1, httpGet.calls.get())
            }
        }
    }

    @Test fun whitespacePaddedKeyIsTrimmedForHttpAndCache() = runBlocking {
        // 前後空白 padding：HTTP header 收到 trim 後的 key；cache 身份綁定 trimmed 指紋
        //（第二次搜尋命中快取、只打一次上游）。
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "  key-A  ".toCharArray())
        val sentTokens = CopyOnWriteArrayList<String?>()
        val http = FakeGetWithHeaders { _, headers ->
            sentTokens.add(headers["X-Subscription-Token"])
            ok(body = braveJsonFor("A-title"))
        }
        val svc = WebSearchService(BraveSearchProvider(vault, http))
        val first = svc.search("kotlin", 5)
        assertTrue(first is WebSearchOutcome.Ok)
        assertEquals("key-A", http.lastHeaders!!["X-Subscription-Token"])
        val second = svc.search("kotlin", 5)
        assertTrue(second is WebSearchOutcome.Ok)
        assertTrue("第二次應命中快取", (second as WebSearchOutcome.Ok).cached)
        assertEquals(1, http.calls.get())
        assertEquals(listOf<String?>("key-A"), sentTokens.toList())
    }

    // ---- credentialState／vault 失敗分支 ----

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
      withTimeout(5_000) {
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

    @Test fun maxBodyCharsZeroCoercedToOne() = runBlocking {
        // maxBodyChars=0：下游 coerceAtLeast(1) → 實際上限 1 char；任何 ≥2-char 的上游回應（含本 fixture）
        // 一律 fail-closed 為 BAD_RESPONSE（不拾取、不解析）。
        // 註：1-char 回應會穿過長度閘但仍 parse 失敗 → 亦為 BAD_RESPONSE，故此路徑永不敗退成 Ok。
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        val http = FakeGet { ok(body = braveJsonFor("T")) }
        val out = BraveSearchProvider(vault, http, maxBodyChars = 0).search("q", 5)
        assertTrue("maxBodyChars=0 必須 fail-closed：$out", out is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.BAD_RESPONSE, (out as WebSearchOutcome.Failed).reason)
    }

    @Test fun maxBodyCharsZeroCoercedToOneAcrossPostProviders() = runBlocking {
        // 同上 coerceAtLeast(1) 路徑，Tavily／Exa／Firecrawl（POST 三家）皆 fail-closed。
        val resultsBody = """{"results":[{"title":"T","url":"https://example.com/"}]}"""
        val firecrawlBody = """{"success":true,"data":{"web":[{"title":"T","url":"https://example.com/"}]}}"""
        suspend fun check(id: String, p: WebSearchProvider) {
            val out = p.search("q", 5)
            assertTrue("$id maxBodyChars=0 必須 fail-closed：$out", out is WebSearchOutcome.Failed)
            assertEquals("$id", WebFailureReason.BAD_RESPONSE, (out as WebSearchOutcome.Failed).reason)
        }
        for ((id, body) in listOf(
            TavilySearchProvider.ID to resultsBody,
            ExaSearchProvider.ID to resultsBody,
            FirecrawlSearchProvider.ID to firecrawlBody,
        )) {
            val vault = FakeVault()
            vault.putKey(defaultKeyIdOf(id), "k".toCharArray())
            val httpPost = FakePost { _, _ -> ok(body = body) }
            check(
                id,
                when (id) {
                    TavilySearchProvider.ID -> TavilySearchProvider(vault, httpPost, maxBodyChars = 0)
                    ExaSearchProvider.ID -> ExaSearchProvider(vault, httpPost, maxBodyChars = 0)
                    else -> FirecrawlSearchProvider(vault, httpPost, maxBodyChars = 0)
                },
            )
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

    @Test fun firecrawlSuccessNumberIsBadResponse() = runBlocking {
        // success 為 JSON Number（非 boolean）：takeIf { !it.isString } 會收下，
        // 但 JsonPrimitive(1).booleanOrNull == null → BAD_RESPONSE（fail-closed）。
        val vault = FakeVault()
        vault.putKey(FirecrawlSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        val http = FakePost { _, _ -> ok(body = """{"success":1,"data":{"web":[]}}""") }
        val out = FirecrawlSearchProvider(vault, http).search("q", 5)
        assertTrue(out is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.BAD_RESPONSE, (out as WebSearchOutcome.Failed).reason)
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
                    assertEquals("brave count must clip to 10", "10", httpGet.lastUrl!!.substringAfter("count=").substringBefore("&"))
                TavilySearchProvider.ID ->
                    assertEquals("tavily max_results must clip to 10", "10", Json.parseToJsonElement(httpPost.lastBody!!).jsonObject["max_results"].toString())
                ExaSearchProvider.ID ->
                    assertEquals("exa numResults must clip to 10", "10", Json.parseToJsonElement(httpPost.lastBody!!).jsonObject["numResults"].toString())
                FirecrawlSearchProvider.ID ->
                    assertEquals("firecrawl limit must clip to 10", "10", Json.parseToJsonElement(httpPost.lastBody!!).jsonObject["limit"].toString())
                else -> error("unexpected keyed id: ${p.id}")
            }
            // count=-3 → 裁切為 1（負值同樣落到下限）。
            p.search("q", -3)
            when (p.id) {
                BraveSearchProvider.ID ->
                    assertEquals("brave count must clip -3 to 1", "1", httpGet.lastUrl!!.substringAfter("count=").substringBefore("&"))
                TavilySearchProvider.ID ->
                    assertEquals("tavily max_results must clip -3 to 1", "1", Json.parseToJsonElement(httpPost.lastBody!!).jsonObject["max_results"].toString())
                ExaSearchProvider.ID ->
                    assertEquals("exa numResults must clip -3 to 1", "1", Json.parseToJsonElement(httpPost.lastBody!!).jsonObject["numResults"].toString())
                FirecrawlSearchProvider.ID ->
                    assertEquals("firecrawl limit must clip -3 to 1", "1", Json.parseToJsonElement(httpPost.lastBody!!).jsonObject["limit"].toString())
                else -> error("unexpected keyed id: ${p.id}")
            }
            // count=0 → 裁切為 1。
            p.search("q", 0)
            when (p.id) {
                BraveSearchProvider.ID ->
                    assertEquals("brave count must clip to 1", "1", httpGet.lastUrl!!.substringAfter("count=").substringBefore("&"))
                TavilySearchProvider.ID ->
                    assertEquals("tavily max_results must clip to 1", "1", Json.parseToJsonElement(httpPost.lastBody!!).jsonObject["max_results"].toString())
                ExaSearchProvider.ID ->
                    assertEquals("exa numResults must clip to 1", "1", Json.parseToJsonElement(httpPost.lastBody!!).jsonObject["numResults"].toString())
                FirecrawlSearchProvider.ID ->
                    assertEquals("firecrawl limit must clip to 1", "1", Json.parseToJsonElement(httpPost.lastBody!!).jsonObject["limit"].toString())
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

    @Test fun bravePublishedFallsBackToPublishedDateAndPublished() = runBlocking {
        // page_age 缺席 → published_date 補上；page_age／published_date 皆缺 → published 補上。
        val body = """{"web":{"results":[
              {"title":"T1","url":"https://example.com/a","description":"d","published_date":"2024-07-01"},
              {"title":"T2","url":"https://example.com/b","description":"d","published":"2024-08-01"}
        ]}}"""
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        val http = FakeGet { ok(body = body) }
        val out = BraveSearchProvider(vault, http).search("q", 5)
        val results = (out as WebSearchOutcome.Ok).results
        assertEquals("2024-07-01", results[0].published)
        assertEquals("2024-08-01", results[1].published)
    }

    @Test fun braveQueryIsCappedAt600() = runBlocking {
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        val http = FakeGet { ok(body = braveJson) }
        BraveSearchProvider(vault, http).search("a".repeat(700), 5)
        // q= 項恰 600 個 a（不含第 601 個）；'a' 為 unreserved，不被 URL-encode。
        assertTrue(http.lastUrl!!.contains("q=" + "a".repeat(600) + "&"))
        assertTrue(!http.lastUrl!!.contains("a".repeat(601)))
        val q = http.lastUrl!!.substringAfter("q=").substringBefore("&")
        assertEquals(600, java.net.URLDecoder.decode(q, Charsets.UTF_8).length)
    }

    @Test fun braveRequestDisablesTextDecorations() = runBlocking {
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        val http = FakeGet { ok(body = braveJson) }
        BraveSearchProvider(vault, http).search("kotlin", 5)
        assertTrue(http.lastUrl!!.contains("text_decorations=false"))
    }

    @Test fun crossProviderSnapshotIsFailClosed() = runBlocking {
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "k-brave".toCharArray())
        val braveState = BraveSearchProvider(vault, FakeGet { ok(body = braveJson) }).credentialState()
        assertTrue(braveState is CredentialState.Ready)
        val snapshot = (braveState as CredentialState.Ready).snapshot
        try {
            val fakePost = FakePost { _, _ -> ok(body = tavilyJson) }
            // Brave 的 snapshot 傳給 Tavily：ownerKeyId != keyId → fail-closed，零 HTTP。
            val out = TavilySearchProvider(vault, fakePost).searchWithSnapshot(snapshot, "q", 5)
            assertTrue(out is WebSearchOutcome.Unavailable)
            assertEquals(WebUnavailableReason.MISSING_KEY, (out as WebSearchOutcome.Unavailable).reason)
            assertEquals(0, fakePost.calls.get())
        } finally {
            snapshot.wipe()
        }
    }

    @Test fun keyIdGuardPinsAllFourProviders() = runBlocking {
        // 跨 provider snapshot 誤送防護（GAP-CROSS-1）：snapshot 的 ownerKeyId 與目標 provider
        // 不符 → Unavailable(MISSING_KEY)、零 HTTP、opaque token 不得 egress；
        // foreign provider 不得抹除 caller 持有的 snapshot（所有權不轉移）→ caller 於 finally 抹除。
        // 四家循環：Brave→Tavily、Tavily→Exa、Exa→Firecrawl、Firecrawl→Brave。
        suspend fun mint(id: String, vault: TrackingVault): CredentialSnapshot {
            vault.putKey(defaultKeyIdOf(id), "mint-secret".toCharArray())
            val state = when (id) {
                BraveSearchProvider.ID ->
                    BraveSearchProvider(vault, FakeGet { ok(body = braveJson) }).credentialState()
                TavilySearchProvider.ID ->
                    TavilySearchProvider(vault, FakePost { _, _ -> ok(body = tavilyJson) }).credentialState()
                ExaSearchProvider.ID ->
                    ExaSearchProvider(vault, FakePost { _, _ -> ok(body = exaJson) }).credentialState()
                FirecrawlSearchProvider.ID ->
                    FirecrawlSearchProvider(vault, FakePost { _, _ -> ok(body = firecrawlJson) }).credentialState()
                else -> error("unexpected keyed id: $id")
            }
            assertTrue(state is CredentialState.Ready)
            return (state as CredentialState.Ready).snapshot
        }

        data class ForeignCase(
            val mintId: String,
            val targetId: String,
        )

        for ((mintId, targetId) in listOf(
            ForeignCase(BraveSearchProvider.ID, TavilySearchProvider.ID),
            ForeignCase(TavilySearchProvider.ID, ExaSearchProvider.ID),
            ForeignCase(ExaSearchProvider.ID, FirecrawlSearchProvider.ID),
            ForeignCase(FirecrawlSearchProvider.ID, BraveSearchProvider.ID),
        )) {
            val mintVault = TrackingVault()
            val snapshot = mint(mintId, mintVault)
            val get = FakeGet { ok(body = braveJson) }
            val post = FakePost { _, _ -> ok(body = tavilyJson) }
            // 目標 provider 不放任何 key：若 guard 失效，它會以自身 keyId 解析（缺 key → Unavailable）
            // 或直接打 upstream；兩者都無法意外綠燈，但只有 guard 正確才保證零 HTTP。
            val target: WebSearchProvider = when (targetId) {
                BraveSearchProvider.ID -> BraveSearchProvider(FakeVault(), get)
                TavilySearchProvider.ID -> TavilySearchProvider(FakeVault(), post)
                ExaSearchProvider.ID -> ExaSearchProvider(FakeVault(), post)
                FirecrawlSearchProvider.ID -> FirecrawlSearchProvider(FakeVault(), post)
                else -> error("unexpected keyed id: $targetId")
            }
            try {
                val out = target.searchWithSnapshot(snapshot, "q", 5)
                assertTrue("$mintId snapshot → $targetId 必須 fail-closed：$out", out is WebSearchOutcome.Unavailable)
                assertEquals(WebUnavailableReason.MISSING_KEY, (out as WebSearchOutcome.Unavailable).reason)
                assertEquals("$mintId → $targetId 零 HTTP", 0, get.calls.get())
                assertEquals("$mintId → $targetId 零 HTTP", 0, post.calls.get())
                assertTrue("foreign provider 不得抹除 caller 持有的 snapshot", mintVault.captured!!.any { it != '\u0000' })
            } finally {
                snapshot.wipe()
            }
            assertTrue("caller 的 wipe 必須清零 buffer", mintVault.captured!!.all { it == '\u0000' })
        }
        // 同一 provider 的兩個實例（同一 keyId）：允許 snapshot 透傳（巢狀 Service 共用情境），
        // 且請求以 snapshot 的 key 發出（不重讀 vault）。
        run {
            val mintVault = TrackingVault()
            val snapshot = mint(BraveSearchProvider.ID, mintVault)
            val secondGet = FakeGetWithHeaders { _, headers ->
                ok(body = braveJsonFor(if (headers["X-Subscription-Token"] == "mint-secret") "same-key-title" else "wrong-key-title"))
            }
            try {
                val second = BraveSearchProvider(FakeVault(), secondGet) // 無 key：snapshot 路徑零 vault 讀取
                val out = second.searchWithSnapshot(snapshot, "q", 5)
                assertTrue(out is WebSearchOutcome.Ok)
                assertEquals("same-key-title", (out as WebSearchOutcome.Ok).results[0].title)
                assertEquals(1, secondGet.calls.get())
            } finally {
                snapshot.wipe()
            }
        }
    }

    @Test fun searchWithSnapshotOnEmptyKeyIsMissingKeyUnavailable() = runBlocking {
        // searchWithSnapshot 的空 key 短路：空 CharArray（ownerKeyId 匹配）→ Unavailable(MISSING_KEY)、零 HTTP；
        // CredentialSnapshot internal ctor 由同 module 測試使用（ownerKeyId 必須匹配 provider keyId 才不觸發跨 provider 拒絕）。
        val vault = FakeVault()
        val http = FakeGet { ok(body = braveJson) }
        val snapshot = CredentialSnapshot(CharArray(0), "deadbeef", BraveSearchProvider.DEFAULT_KEY_ID)
        try {
            val out = BraveSearchProvider(vault, http).searchWithSnapshot(snapshot, "q", 5)
            assertTrue(out is WebSearchOutcome.Unavailable)
            assertEquals(WebUnavailableReason.MISSING_KEY, (out as WebSearchOutcome.Unavailable).reason)
            assertEquals(0, http.calls.get())
        } finally {
            snapshot.wipe()
        }
    }

    @Test fun defaultSearchWithSnapshotIsFailClosedUnavailable() = runBlocking {
        // 僅覆寫 interface 的抽象成員（id／capabilities／probe／search）；searchWithSnapshot 沿用預設實作。
        val stub = object : WebSearchProvider {
            override val id: String = "stub"
            override val capabilities: Set<WebCapability> = emptySet()
            override suspend fun probe(): WebProbe = WebProbe.Ready
            override suspend fun search(query: String, count: Int): WebSearchOutcome =
                WebSearchOutcome.Unavailable(WebUnavailableReason.NO_PROVIDER)
        }
        val snapshot = CredentialSnapshot("x".toCharArray(), "deadbeef", "id")
        try {
            val out = stub.searchWithSnapshot(snapshot, "q", 5)
            assertTrue(out is WebSearchOutcome.Unavailable)
            assertEquals(WebUnavailableReason.MISSING_KEY, (out as WebSearchOutcome.Unavailable).reason)
        } finally {
            snapshot.wipe()
        }
    }

    @Test fun searchWithSnapshotOnBlankQueryIsEmptyQueryUnavailable() = runBlocking {
        // searchWithSnapshot 的空 query 短路：Unavailable(EMPTY_QUERY)、零 HTTP、零 vault 讀取；
        // 以 FakeVault 的真實 snapshot 呼叫（finally 負責 wipe）。
        val vault = FakeVault()
        vault.putKey(BraveSearchProvider.DEFAULT_KEY_ID, "k".toCharArray())
        val httpGet = FakeGet { ok(body = braveJson) }
        val provider = BraveSearchProvider(vault, httpGet)
        val readsBefore = vault.seenGet.size
        val state = provider.credentialState()
        assertTrue(state is CredentialState.Ready)
        val snapshot = (state as CredentialState.Ready).snapshot
        try {
            for (blank in listOf("   ", "", "\t\n")) {
                val out = provider.searchWithSnapshot(snapshot, blank, 5)
                assertTrue("searchWithSnapshot(blank) 必須回 Unavailable：$out", out is WebSearchOutcome.Unavailable)
                assertEquals(WebUnavailableReason.EMPTY_QUERY, (out as WebSearchOutcome.Unavailable).reason)
            }
            assertEquals("blank 短路不得打 upstream", 0, httpGet.calls.get())
            assertEquals("blank 短路不得重讀 vault", readsBefore + 1, vault.seenGet.size)
        } finally {
            snapshot.wipe()
        }
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

    @Test fun resultUrlWithOutOfRangePortIsSkipped() {
        val body = """{"web":{"results":[
              {"title":"p0","url":"https://example.com:0/","description":"port zero rejected"},
              {"title":"p65536","url":"https://example.com:65536/","description":"port too big"},
              {"title":"p99999","url":"https://example.com:99999/","description":"port too big"},
              {"title":"p65535","url":"https://example.com:65535/","description":"max valid port"},
              {"title":"ok","url":"https://example.com:8080/","description":"valid port"}
        ]}}"""
        val hits = parseBraveResults(Json { ignoreUnknownKeys = true }, body)
        assertTrue(hits != null)
        assertEquals(2, hits!!.size)
        assertTrue(hits.any { it.url == "https://example.com:65535/" })
        assertTrue(hits.any { it.url == "https://example.com:8080/" })
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
     * 測試專用 vault：swallow=false 時正常回傳 key 拷貝；swallow=true 時 delay(60_000)
     * 後即使已取消仍回 key 拷貝（吞掉 CE 的壞 vault），用於驗證 keyed cache-hit 路徑
     * 的 ensureActive() 防禦：已取消的呼叫端不得拿到 cached 值。
     */
    private class CancellationSwallowingVault : KeyVault {
        private val keys = mutableMapOf<String, CharArray>()
        var swallow = false

        override suspend fun putKey(providerId: String, apiKey: CharArray) {
            keys[providerId] = apiKey.copyOf()
        }

        override suspend fun getKey(providerId: String): CharArray? {
            if (!swallow) return keys[providerId]?.copyOf()
            return try {
                delay(60_000)
                keys[providerId]?.copyOf()
            } catch (_: CancellationException) {
                keys[providerId]?.copyOf() // 吞掉取消仍「正常返回」
            }
        }

        override suspend fun deleteKey(providerId: String) {
            keys.remove(providerId)
        }

        override suspend fun hasKey(providerId: String): Boolean = keys.containsKey(providerId)
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

    /**
     * 測試專用 vault：copy-on-read 的 TrackingVault 變體——每次 getKey 發出的拷貝
     * 皆記錄進 [capturedAll]（最後一次亦同入 [captured]），putKey 亦 copyOf。
     * 用於 R6-1 demote 路徑：TrackingVault 的共享參照語義會讓 wipe 抹掉 vault 內部
     * 存儲的同一份 buffer，後續 resolve 會拿到全零 key（MISSING_KEY），無法承載流程；
     * 此變體保留「可觀測每一份發出 buffer」的能力且不破壞 vault 狀態。
     */
    private class RecordingVault : KeyVault {
        private val keys = mutableMapOf<String, CharArray>()
        val capturedAll = mutableListOf<CharArray>()
        var captured: CharArray? = null
            private set

        override suspend fun putKey(providerId: String, apiKey: CharArray) {
            keys[providerId] = apiKey.copyOf()
        }

        override suspend fun getKey(providerId: String): CharArray? =
            keys[providerId]?.copyOf()?.also { captured = it; capturedAll.add(it) }

        override suspend fun deleteKey(providerId: String) {
            keys.remove(providerId)
        }

        override suspend fun hasKey(providerId: String): Boolean = keys.containsKey(providerId)
    }

    /**
     * 測試專用 delegate（marker 模式下每次 search／searchWithSnapshot 皆拋出
     * module-internal 的 CredentialRevisionChangedException）：
     * 用於驗證 R6-1 重試預算——CE 分支吸收標記並以當前憑證重綁重執行，
     * 重試 MAX_REVISION_RESTARTS 次後 fail-closed 回 Failed(TRANSPORT)；絕不 raw throw、絕不寫 cache。
     * markerMode = false 時改回穩定行為（Ok），用於斷言預算耗盡後不會焊死 cache。
     */
    private class RevisionMarkerAlwaysDelegate : WebSearchProvider {
        val calls = AtomicInteger(0)
        var markerMode = true

        override val id: String = "revision-marker-always"
        override val cacheNamespace: String get() = id
        override val capabilities: Set<WebCapability> = setOf(WebCapability.REQUIRES_KEY)

        override suspend fun probe(): WebProbe = WebProbe.Ready

        override suspend fun credentialState(): CredentialState =
            CredentialState.Ready(CredentialSnapshot("fixed-key".toCharArray(), "fp-fixed"))

        override suspend fun search(query: String, count: Int): WebSearchOutcome {
            calls.incrementAndGet()
            if (markerMode) throw CredentialRevisionChangedException()
            return WebSearchOutcome.Ok(emptyList())
        }

        override suspend fun searchWithSnapshot(snapshot: CredentialSnapshot, query: String, count: Int): WebSearchOutcome {
            calls.incrementAndGet()
            if (markerMode) throw CredentialRevisionChangedException()
            return WebSearchOutcome.Ok(emptyList())
        }
    }
}
