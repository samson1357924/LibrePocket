package dev.librepocket.web

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D2 fixture tests（#138 D2；全 fake：fake HTTP＋fake 時鐘，零真網）。
 *
 * - SearXNG JSON／timeout 可注入／typed error；DDGS HTML `uddg=` 解開。
 * - cache（TTL 15 min、可注入時鐘）＋single-flight（同 query 併發合流）。
 */
class WebSearchD2Test {

    // ---- fixtures ----

    private val searxngJson = """
        {"query":"kotlin","results":[
          {"title":"Kotlin Docs","url":"https://kotlinlang.org/","content":"Modern programming language","publishedDate":"2024-01-02"},
          {"title":"No URL Entry","content":"skip me"},
          {"url":"https://example.com/nocontent"},
          {"title":"ftp pick","url":"ftp://example.com/x","content":"skip scheme"},
          {"title":"DDG","url":"https://duckduckgo.com/","content":"privacy search"}
        ]}
    """.trimIndent()

    private val ddgHtml = """
        <html><body>
        <div class="result"><h2 class="result__title">
        <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2F&amp;rut=abc">Kotlin <b>Docs</b></a>
        </h2>
        <a class="result__snippet" href="//duckduckgo.com/l/?uddg=x">Modern <b>language</b> &amp; tools</a>
        </div>
        <div class="result"><h2 class="result__title">
        <a class="result__a" href="https://duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fs%3Fa%3D1%26b%3D2">Second &quot;Hit&quot;</a>
        </h2></div>
        <div class="nav"><a href="/about">About</a></div>
        </body></html>
    """.trimIndent()

    private class FakeHttp(
        val handler: suspend (url: String) -> WebHttpResponse,
    ) : WebHttpGet {
        val urls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val calls = AtomicInteger(0)
        override suspend fun get(url: String, headers: Map<String, String>): WebHttpResponse {
            urls.add(url)
            calls.incrementAndGet()
            return handler(url)
        }
    }

    private fun okJson(json: String = searxngJson, code: Int = 200) = WebHttpResponse(code, json)

    // ---- SearXNG：fixture → hits ----

    @Test fun searxngFixtureMapsHits() = runBlocking {
        val http = FakeHttp { okJson() }
        val p = SearxngSearchProvider("https://search.example/", http)
        val out = p.search("kotlin", 5)
        assertTrue(out is WebSearchOutcome.Ok)
        val ok = out as WebSearchOutcome.Ok
        // 缺 url／ftp scheme 條目跳過；nocontent 標題回退 URL。
        assertEquals(3, ok.results.size)
        assertEquals("Kotlin Docs", ok.results[0].title)
        assertEquals("https://kotlinlang.org/", ok.results[0].url)
        assertEquals("Modern programming language", ok.results[0].snippet)
        assertEquals("2024-01-02", ok.results[0].published)
        assertEquals("kotlinlang.org", ok.results[0].siteName)
        assertEquals("https://example.com/nocontent", ok.results[1].title)
        assertEquals("https://example.com/nocontent", ok.results[1].url)
        assertFalse(ok.cached)
        // URL 形狀：format=json＋編碼 q；SearXNG 分頁由 server 決定，count 走 client 端 take。
        assertTrue(http.urls.single().contains("/search?format=json"))
        assertTrue(http.urls.single().contains("q=kotlin"))
        assertEquals("searxng", p.id)
        assertTrue(p.probe() is WebProbe.Ready)
    }

    @Test fun searxngCountClampedClientSide() = runBlocking {
        val http = FakeHttp { okJson() }
        val p = SearxngSearchProvider("https://search.example", http)
        val out = p.search("kotlin", 999) as WebSearchOutcome.Ok
        assertEquals(3, out.results.size) // min(10, 實際 3 筆)
        val empty = p.search("kotlin", 0) as WebSearchOutcome.Ok
        assertEquals(1, empty.results.size) // countOf(0) → 1
    }

    @Test fun searxngEmptyQueryShortCircuits() = runBlocking {
        val http = FakeHttp { okJson() }
        val out = SearxngSearchProvider("https://search.example", http).search("   ", 5)
        assertTrue(out is WebSearchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.EMPTY_QUERY, (out as WebSearchOutcome.Unavailable).reason)
        assertEquals(0, http.calls.get())
    }

    @Test fun searxngMissingUrlIsUnavailable() = runBlocking {
        val http = FakeHttp { okJson() }
        val p = SearxngSearchProvider("  ", http)
        assertTrue(p.probe() is WebProbe.NotReady)
        val out = p.search("kotlin", 5)
        assertTrue(out is WebSearchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.MISSING_URL, (out as WebSearchOutcome.Unavailable).reason)
        assertEquals(0, http.calls.get())
    }

    @Test fun searxngMalformedBaseIsProtocol() = runBlocking {
        val http = FakeHttp { okJson() }
        val out = SearxngSearchProvider("ht!tp://::", http).search("kotlin", 5)
        assertTrue(out is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.PROTOCOL, (out as WebSearchOutcome.Failed).reason)
        assertFalse(out.fallbackAllowed)
        assertEquals(0, http.calls.get())
    }

    // ---- SearXNG：typed errors ----

    private fun statusCase(code: Int, reason: WebFailureReason, fallback: Boolean) = runBlocking {
        val http = FakeHttp { WebHttpResponse(code, "{}") }
        val out = SearxngSearchProvider("https://search.example", http).search("q", 5)
        assertTrue("code=$code", out is WebSearchOutcome.Failed)
        assertEquals("code=$code", reason, (out as WebSearchOutcome.Failed).reason)
        assertEquals("code=$code", fallback, out.fallbackAllowed)
    }

    @Test fun searxngStatusMapping() {
        statusCase(401, WebFailureReason.AUTH, false)
        statusCase(403, WebFailureReason.AUTH, false)
        statusCase(408, WebFailureReason.TIMEOUT, true)
        statusCase(429, WebFailureReason.SUPPLIER_ERROR, true)
        statusCase(500, WebFailureReason.SUPPLIER_ERROR, true)
        statusCase(400, WebFailureReason.PROTOCOL, false)
        statusCase(404, WebFailureReason.PROTOCOL, false)
    }

    @Test fun searxngBadJsonAndOversizeAreBadResponse() = runBlocking {
        val garbage = FakeHttp { WebHttpResponse(200, "{not json") }
        val bad = SearxngSearchProvider("https://search.example", garbage).search("q", 5)
        assertEquals(WebFailureReason.BAD_RESPONSE, (bad as WebSearchOutcome.Failed).reason)
        assertTrue(bad.fallbackAllowed)

        val noResults = FakeHttp { WebHttpResponse(200, """{"foo":1}""") }
        val missing = SearxngSearchProvider("https://search.example", noResults).search("q", 5)
        assertEquals(WebFailureReason.BAD_RESPONSE, (missing as WebSearchOutcome.Failed).reason)

        val big = FakeHttp { WebHttpResponse(200, searxngJson) }
        val over = SearxngSearchProvider("https://search.example", big, maxBodyChars = 10).search("q", 5)
        assertEquals(WebFailureReason.BAD_RESPONSE, (over as WebSearchOutcome.Failed).reason)
    }

    @Test fun searxngTransportOnIoFailure() = runBlocking {
        val http = FakeHttp { throw java.io.IOException("down") }
        val out = SearxngSearchProvider("https://search.example", http).search("q", 5)
        val failed = out as WebSearchOutcome.Failed
        assertEquals(WebFailureReason.TRANSPORT, failed.reason)
        assertTrue(failed.fallbackAllowed)
    }

    @Test fun searxngTimeoutIsInjectableAndCancellable() = runBlocking {
        var observedCancel = false
        val http = FakeHttp {
            try {
                delay(30_000)
                okJson()
            } catch (e: CancellationException) {
                observedCancel = true
                throw e
            }
        }
        val out = SearxngSearchProvider("https://search.example", http, timeoutMs = 50).search("q", 5)
        assertTrue(out is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.TIMEOUT, (out as WebSearchOutcome.Failed).reason)
        assertTrue(out.fallbackAllowed)
        assertTrue(observedCancel) // withTimeout 真的中斷了底層 suspend
    }

    // ---- DDGS：uddg= 解開 ----

    @Test fun ddgsFixtureUnwrapsUddg() = runBlocking {
        val http = FakeHttp { WebHttpResponse(200, ddgHtml) }
        val p = DdgsSearchProvider(http)
        val out = p.search("kotlin", 5)
        assertTrue(out is WebSearchOutcome.Ok)
        val ok = out as WebSearchOutcome.Ok
        assertEquals(2, ok.results.size)
        assertEquals("https://kotlinlang.org/", ok.results[0].url)
        assertEquals("Kotlin Docs", ok.results[0].title) // 去 tag
        assertEquals("Modern language & tools", ok.results[0].snippet) // unescape
        assertEquals("kotlinlang.org", ok.results[0].siteName)
        // uddg 內含編碼 & 與 query 一併還原；導覽列連結（無 result__a）忽略。
        assertEquals("https://example.com/s?a=1&b=2", ok.results[1].url)
        assertEquals("Second \"Hit\"", ok.results[1].title)
        assertTrue(http.urls.single().startsWith(DdgsSearchProvider.DDGS_DEFAULT_ENDPOINT))
        assertTrue(http.urls.single().contains("q=kotlin"))
        assertEquals("ddgs", p.id)
        assertTrue(p.probe() is WebProbe.Ready)
    }

    @Test fun ddgsEmptyQueryShortCircuits() = runBlocking {
        val http = FakeHttp { WebHttpResponse(200, ddgHtml) }
        val out = DdgsSearchProvider(http).search("  ", 5)
        assertEquals(WebUnavailableReason.EMPTY_QUERY, (out as WebSearchOutcome.Unavailable).reason)
        assertEquals(0, http.calls.get())
    }

    @Test fun ddgsNoResultsIsOkEmpty() = runBlocking {
        val http = FakeHttp { WebHttpResponse(200, "<html><body><p>No results</p></body></html>") }
        val out = DdgsSearchProvider(http).search("zxqv nonsense", 5)
        assertTrue(out is WebSearchOutcome.Ok)
        assertTrue((out as WebSearchOutcome.Ok).results.isEmpty())
    }

    @Test fun ddgsBotWallIsSupplierError() = runBlocking {
        val http = FakeHttp {
            WebHttpResponse(200, "<html><body><div class=\"anomaly-modal\">challenge</div></body></html>")
        }
        val out = DdgsSearchProvider(http).search("q", 5)
        assertEquals(WebFailureReason.SUPPLIER_ERROR, (out as WebSearchOutcome.Failed).reason)
    }

    @Test fun ddgsBlankBodyIsBadResponse() = runBlocking {
        val http = FakeHttp { WebHttpResponse(200, "   ") }
        val out = DdgsSearchProvider(http).search("q", 5)
        assertEquals(WebFailureReason.BAD_RESPONSE, (out as WebSearchOutcome.Failed).reason)
    }

    @Test fun ddgsAuthMapsTyped() = runBlocking {
        val http = FakeHttp { WebHttpResponse(403, "forbidden") }
        val out = DdgsSearchProvider(http).search("q", 5)
        val failed = out as WebSearchOutcome.Failed
        assertEquals(WebFailureReason.AUTH, failed.reason)
        assertFalse(failed.fallbackAllowed)
    }

    @Test fun ddgsBlankBody403IsAuth() = runBlocking {
        val http = FakeHttp { WebHttpResponse(403, "   ") }
        val out = DdgsSearchProvider(http).search("q", 5)
        val failed = out as WebSearchOutcome.Failed
        assertEquals(WebFailureReason.AUTH, failed.reason)
        assertFalse(failed.fallbackAllowed)
    }

    // ---- cache：第二次零 upstream、cached=true ----

    @Test fun cacheSecondCallHitsZeroUpstream() = runBlocking {
        val http = FakeHttp { okJson() }
        val svc = WebSearchService(SearxngSearchProvider("https://search.example", http))
        val first = svc.search("kotlin", 5) as WebSearchOutcome.Ok
        assertFalse(first.cached)
        val second = svc.search("kotlin", 5) as WebSearchOutcome.Ok
        assertTrue(second.cached)
        assertEquals(first.results.map { it.url }, second.results.map { it.url })
        assertEquals(1, http.calls.get())
    }

    @Test fun cacheKeyIncludesCount() = runBlocking {
        val http = FakeHttp { okJson() }
        val svc = WebSearchService(SearxngSearchProvider("https://search.example", http))
        svc.search("kotlin", 3)
        svc.search("kotlin", 1)
        assertEquals(2, http.calls.get())
        (svc.search("kotlin", 3) as WebSearchOutcome.Ok).let { assertTrue(it.cached) }
        assertEquals(2, http.calls.get())
    }

    @Test fun cacheTtlExpiryViaFakeClock() = runBlocking {
        var now = 1_000L
        val cache = WebSearchCache(clockMs = { now })
        val http = FakeHttp { okJson() }
        val svc = WebSearchService(SearxngSearchProvider("https://search.example", http), cache)
        svc.search("kotlin", 5)
        now += WebSearchCache.DEFAULT_TTL_MS - 1
        assertTrue((svc.search("kotlin", 5) as WebSearchOutcome.Ok).cached)
        assertEquals(1, http.calls.get())
        now += 1 // 到期（lazy 淘汰）
        val refetched = svc.search("kotlin", 5) as WebSearchOutcome.Ok
        assertFalse(refetched.cached)
        assertEquals(2, http.calls.get())
    }

    @Test fun failuresAreNotCached() = runBlocking {
        var attempt = 0
        val http = FakeHttp {
            attempt++
            if (attempt == 1) WebHttpResponse(500, "boom") else okJson()
        }
        val svc = WebSearchService(SearxngSearchProvider("https://search.example", http))
        val failed = svc.search("kotlin", 5)
        assertTrue(failed is WebSearchOutcome.Failed)
        val retried = svc.search("kotlin", 5) as WebSearchOutcome.Ok
        assertFalse(retried.cached)
        assertEquals(2, http.calls.get())
    }

    // ---- single-flight：併發合流 ----

    @Test fun singleFlightCoalescesConcurrentQueries() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val http = FakeHttp {
            release.await()
            okJson()
        }
        val svc = WebSearchService(SearxngSearchProvider("https://search.example", http))
        val jobs = List(20) {
            async(start = CoroutineStart.UNDISPATCHED) { svc.search("kotlin", 5) }
        }
        // leader 已進 fetch、19 個 follower 已掛上同一個 deferred：仍只有一次 upstream。
        withTimeout(5_000) { while (http.calls.get() < 1) delay(1) }
        assertEquals(1, http.calls.get())
        release.complete(Unit)
        val outs = jobs.map { it.await() }
        assertEquals(1, http.calls.get())
        assertTrue(outs.all { it is WebSearchOutcome.Ok && it.results.size == 3 })
    }

    @Test fun cancellationDoesNotWriteCache() = runBlocking {
        val release = CompletableDeferred<Unit>()
        var observedCancel = false
        val http = FakeHttp {
            try {
                release.await()
                okJson()
            } catch (e: CancellationException) {
                observedCancel = true
                throw e
            }
        }
        val svc = WebSearchService(
            SearxngSearchProvider("https://search.example", http, timeoutMs = 60_000),
        )
        val job = async { svc.search("kotlin", 5) }
        withTimeout(5_000) { while (http.calls.get() < 1) delay(1) }
        job.cancelAndJoin()
        assertTrue(observedCancel)
        assertTrue(job.isCancelled)
        // 取消沒寫 cache：下一次照樣打 upstream。
        release.complete(Unit)
        val out = svc.search("kotlin", 5) as WebSearchOutcome.Ok
        assertFalse(out.cached)
        assertEquals(2, http.calls.get())
        assertEquals(3, out.results.size)
    }

    // ---- D2 review：髒資料／引號／取消／狀態映射 ----

    @Test fun searxngDirtyItemsSkippedWithoutThrow() = runBlocking {
        val dirty = """
            {"results":[
              {"title":"Good","url":"https://example.com/good","content":"ok"},
              {"title":{"nested":1},"url":"https://example.com/obj-title","content":"t"},
              {"title":"ArrUrl","url":["https://example.com/x"],"content":"skip"},
              {"title":"ObjUrl","url":{"u":"https://example.com/y"},"content":"skip"},
              {"title":"NullUrl","url":null,"content":"skip"},
              "not-an-object",
              {"title":"Num","url":12345,"content":"skip-scheme"}
            ]}
        """.trimIndent()
        val http = FakeHttp { WebHttpResponse(200, dirty) }
        val out = SearxngSearchProvider("https://search.example", http).search("q", 10)
        assertTrue(out is WebSearchOutcome.Ok)
        val ok = out as WebSearchOutcome.Ok
        // object title 回退 URL 不拋；array／object／null url 與數字 scheme 全跳過。
        assertEquals(2, ok.results.size)
        assertEquals("Good", ok.results[0].title)
        assertEquals("https://example.com/obj-title", ok.results[1].url)
        assertEquals("https://example.com/obj-title", ok.results[1].title)
    }

    @Test fun ddgsSingleQuoteHrefUnwraps() = runBlocking {
        val html = """
            <html><body>
            <div class="result"><a class='result__a' href='//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fsingle&rut=x'>Single Title</a></div>
            <div class="result"><a class="result__a" href='https://example.com/direct'>Direct</a></div>
            </body></html>
        """.trimIndent()
        val out = DdgsSearchProvider(FakeHttp { WebHttpResponse(200, html) }).search("q", 5)
        assertTrue(out is WebSearchOutcome.Ok)
        val ok = out as WebSearchOutcome.Ok
        assertEquals(2, ok.results.size)
        assertEquals("https://example.com/single", ok.results[0].url)
        assertEquals("Single Title", ok.results[0].title)
        assertEquals("https://example.com/direct", ok.results[1].url)
    }

    @Test fun ddgsAmpDecodedLastAvoidsDoubleDecode() = runBlocking {
        val html = """<html><body><div class="result">""" +
            """<a class="result__a" href="https://example.com/">A &amp;lt; B &amp; C &quot;Q&quot;</a>""" +
            """</div></body></html>"""
        val out = DdgsSearchProvider(FakeHttp { WebHttpResponse(200, html) }).search("q", 5)
        assertTrue(out is WebSearchOutcome.Ok)
        // `&amp;lt;` 只解一次 → `&lt;`（舊順序會誤解成 `<`）；普通 `&amp;`／`&quot;` 照解。
        assertEquals("A &lt; B & C \"Q\"", (out as WebSearchOutcome.Ok).results.single().title)
    }

    @Test fun followerCancelDoesNotAffectLeader() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val http = FakeHttp {
            release.await()
            okJson()
        }
        val svc = WebSearchService(
            SearxngSearchProvider("https://search.example", http, timeoutMs = 60_000),
        )
        val leader = async { svc.search("kotlin", 5) }
        withTimeout(5_000) { while (http.calls.get() < 1) delay(1) }
        val follower = async { svc.search("kotlin", 5) }
        delay(50) // 確保 follower 已掛上同一個 in-flight 再取消
        follower.cancelAndJoin()
        assertTrue(follower.isCancelled)
        release.complete(Unit)
        val out = withTimeout(5_000) { leader.await() } as WebSearchOutcome.Ok
        assertFalse(out.cached)
        assertEquals(1, http.calls.get())
        // leader 成果已寫 cache，後續同 query 零 upstream。
        val second = svc.search("kotlin", 5) as WebSearchOutcome.Ok
        assertTrue(second.cached)
        assertEquals(1, http.calls.get())
    }

    @Test fun protocolStatusesMapAndAreNotCached() = runBlocking {
        for (code in listOf(405, 410, 422)) {
            val searxngHttp = FakeHttp { WebHttpResponse(code, "x") }
            val searxng = SearxngSearchProvider("https://search.example", searxngHttp).search("q", 5)
            assertTrue("searxng code=$code", searxng is WebSearchOutcome.Failed)
            assertEquals("searxng code=$code", WebFailureReason.PROTOCOL, (searxng as WebSearchOutcome.Failed).reason)
            assertFalse(searxng.fallbackAllowed)

            val ddgsHttp = FakeHttp { WebHttpResponse(code, "x") }
            val ddgs = DdgsSearchProvider(ddgsHttp).search("q", 5)
            assertTrue("ddgs code=$code", ddgs is WebSearchOutcome.Failed)
            assertEquals("ddgs code=$code", WebFailureReason.PROTOCOL, (ddgs as WebSearchOutcome.Failed).reason)
            assertFalse(ddgs.fallbackAllowed)
        }
        // PROTOCOL 失敗不寫 cache：重打照樣 upstream。
        var calls = 0
        val http = FakeHttp {
            calls++
            WebHttpResponse(405, "gone")
        }
        val svc = WebSearchService(SearxngSearchProvider("https://search.example", http))
        repeat(2) { assertTrue(svc.search("kotlin", 5) is WebSearchOutcome.Failed) }
        assertEquals(2, calls)
    }

    @Test fun unavailableIsNotCached() = runBlocking {
        var calls = 0
        val unavailable = object : WebSearchProvider {
            override val id: String = "u"
            override val capabilities: Set<WebCapability> = emptySet()
            override suspend fun probe(): WebProbe = WebProbe.Ready
            override suspend fun search(query: String, count: Int): WebSearchOutcome {
                calls++
                return WebSearchOutcome.Unavailable(WebUnavailableReason.MISSING_URL)
            }
        }
        val svc = WebSearchService(unavailable)
        repeat(2) { assertTrue(svc.search("kotlin", 5) is WebSearchOutcome.Unavailable) }
        assertEquals(2, calls)
    }

    @Test fun lateCacheHitAfterMissDoesNotStartSecondUpstream() = runBlocking {
        // Interleaving under test (check-then-elect race):
        // 1. jobA misses the (shared) cache, then parks at svcA.mutex BEFORE electing
        //    in-flight leader/follower (mutex held externally via reflection).
        // 2. svcB (own mutex, same shared WebSearchCache + same cacheNamespace) runs to
        //    completion on the test thread and populates the cache.
        // 3. svcA mutex released -> jobA resumes. Without an under-lock re-check it would
        //    elect itself leader and fire a 2nd upstream; with the fix it sees the late
        //    hit under lock and returns Ok(cached=true) with zero delegateA calls.
        // Deterministic (no timing): the held mutex forces the park; the delay below only
        // lets the UNDISPATCHED body run to its first suspension point.
        val cache = WebSearchCache()
        val callsA = AtomicInteger(0)
        val callsB = AtomicInteger(0)
        fun countingDelegate(calls: AtomicInteger, id: String) = object : WebSearchProvider {
            override val id: String = id
            override val cacheNamespace: String = "shared-ns"
            override val capabilities: Set<WebCapability> = emptySet()
            override suspend fun probe(): WebProbe = WebProbe.Ready
            override suspend fun search(query: String, count: Int): WebSearchOutcome {
                calls.incrementAndGet()
                return WebSearchOutcome.Ok(
                    results = listOf(WebSearchResult("T", "https://example.com/", "s")),
                )
            }
        }
        val svcA = WebSearchService(countingDelegate(callsA, "a"), cache)
        val svcB = WebSearchService(countingDelegate(callsB, "b"), cache)

        // Reflection clearly marked: hold svcA's private mutex so jobA parks pre-election.
        val mutexField = WebSearchService::class.java.getDeclaredField("mutex")
        mutexField.setAccessible(true)
        val mutexA = mutexField.get(svcA) as Mutex
        mutexA.lock()
        var jobA: kotlinx.coroutines.Deferred<WebSearchOutcome>? = null
        try {
            jobA = async(start = CoroutineStart.UNDISPATCHED) { svcA.search("q", 5) }
            delay(50) // let UNDISPATCHED body run to mutex.withLock suspension
            assertFalse(jobA.isCompleted) // parked, not complete
            assertEquals(0, callsA.get())

            val outB = svcB.search("q", 5) as WebSearchOutcome.Ok
            assertFalse(outB.cached)
            assertEquals(1, callsB.get())

            mutexA.unlock()
            val outA = withTimeout(5_000) { jobA.await() } as WebSearchOutcome.Ok
            assertTrue(outA.cached)
            assertEquals(0, callsA.get()) // no second upstream
            assertEquals(1, callsA.get() + callsB.get())
        } finally {
            if (mutexA.isLocked) {
                try { mutexA.unlock() } catch (_: IllegalStateException) { /* already released */ }
            }
            jobA?.cancel()
        }
    }
}
