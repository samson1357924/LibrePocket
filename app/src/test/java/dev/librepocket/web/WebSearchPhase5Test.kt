package dev.librepocket.web

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 5 missing coverage (all deterministic, fake-only, no new deps).
 *
 * Latch strategy: entry [CompletableDeferred] instead of delay-polling;
 * follower attach guaranteed via [CoroutineStart.UNDISPATCHED] (runs
 * synchronously to first suspension, i.e. already on the shared deferred).
 */
class WebSearchPhase5Test {

    private fun okResult(title: String = "t", url: String = "https://example.com/") =
        WebSearchResult(title, url, "s")

    private class CountingProvider(
        override val id: String = "x",
        val handler: suspend () -> WebSearchOutcome,
    ) : WebSearchProvider {
        override val capabilities: Set<WebCapability> = emptySet()
        val calls = AtomicInteger(0)
        override suspend fun probe(): WebProbe = WebProbe.Ready
        override suspend fun search(query: String, count: Int): WebSearchOutcome {
            calls.incrementAndGet()
            return handler()
        }
    }

    private class FakeHttp(val handler: suspend (String) -> WebHttpResponse) : WebHttpGet {
        val calls = AtomicInteger(0)
        override suspend fun get(url: String, headers: Map<String, String>): WebHttpResponse {
            calls.incrementAndGet()
            return handler(url)
        }
    }

    // ---- Service: leader-cancel / okForFollowers / exception ----

    @Test fun leaderCancelFollowerRetriesAndSucceeds() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val calls = AtomicInteger(0)
        val delegate = object : WebSearchProvider {
            override val id: String = "x"
            override val capabilities: Set<WebCapability> = emptySet()
            override suspend fun probe(): WebProbe = WebProbe.Ready
            override suspend fun search(query: String, count: Int): WebSearchOutcome {
                val n = calls.incrementAndGet()
                if (n == 1) {
                    entered.complete(Unit)
                    release.await()
                }
                return WebSearchOutcome.Ok(listOf(okResult()))
            }
        }
        val svc = WebSearchService(delegate)
        val leader = async { svc.search("kotlin", 5) }
        withTimeout(5_000) { entered.await() }
        // UNDISPATCHED: returns only after follower is suspended on shared deferred.
        val follower = async(start = CoroutineStart.UNDISPATCHED) { svc.search("kotlin", 5) }
        leader.cancel()
        release.complete(Unit)
        try {
            withTimeout(5_000) { leader.await() }
            fail("leader must throw CE")
        } catch (_: CancellationException) {
            // expected
        }
        val out = withTimeout(5_000) { follower.await() }
        assertTrue(out is WebSearchOutcome.Ok)
        assertEquals(2, calls.get())
    }

    @Test fun okForFollowersLeaderThrowsCacheEmpty() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = WebSearchCache()
        val delegate = object : WebSearchProvider {
            override val id: String = "x"
            override val capabilities: Set<WebCapability> = emptySet()
            override suspend fun probe(): WebProbe = WebProbe.Ready
            override suspend fun search(query: String, count: Int): WebSearchOutcome {
                entered.complete(Unit)
                release.await()
                // Self-cancel: deterministic "Ok received then cancel observed" window.
                currentCoroutineContext()[Job]?.cancel(CancellationException("self-cancel"))
                return WebSearchOutcome.Ok(listOf(okResult()))
            }
        }
        val svc = WebSearchService(delegate, cache)
        val leader = async { svc.search("kotlin", 5) }
        withTimeout(5_000) { entered.await() }
        val follower = async(start = CoroutineStart.UNDISPATCHED) { svc.search("kotlin", 5) }
        release.complete(Unit)
        try {
            withTimeout(5_000) { leader.await() }
            fail("leader must throw CE")
        } catch (_: CancellationException) {
            // expected: leader had value but still throws
        }
        val fout = withTimeout(5_000) { follower.await() }
        assertTrue(fout is WebSearchOutcome.Ok)
        assertFalse((fout as WebSearchOutcome.Ok).cached)
        assertEquals(0, cache.size) // no write on cancelled leader
    }

    @Test fun delegateRuntimeExceptionMapsToTransportAndClearsInFlight() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val calls = AtomicInteger(0)
        val delegate = object : WebSearchProvider {
            override val id: String = "x"
            override val capabilities: Set<WebCapability> = emptySet()
            override suspend fun probe(): WebProbe = WebProbe.Ready
            override suspend fun search(query: String, count: Int): WebSearchOutcome {
                if (calls.incrementAndGet() == 1) {
                    entered.complete(Unit)
                    release.await()
                    throw RuntimeException("boom")
                }
                throw RuntimeException("boom-again")
            }
        }
        val svc = WebSearchService(delegate)
        val leader = async { svc.search("kotlin", 5) }
        withTimeout(5_000) { entered.await() }
        val followers = List(3) {
            async(start = CoroutineStart.UNDISPATCHED) { svc.search("kotlin", 5) }
        }
        release.complete(Unit)
        val all = listOf(leader) + followers
        val outs = withTimeout(5_000) { all.map { it.await() } }
        assertTrue(outs.all { it is WebSearchOutcome.Failed })
        assertTrue(outs.all { (it as WebSearchOutcome.Failed).reason == WebFailureReason.TRANSPORT })
        // inFlight cleared: next call retries upstream.
        val again = svc.search("kotlin", 5)
        assertTrue(again is WebSearchOutcome.Failed)
        assertEquals(2, calls.get())
    }

    @Test fun concurrentDistinctKeysTwoUpstreams() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val bothEntered = CompletableDeferred<Unit>()
        val calls = AtomicInteger(0)
        val delegate = object : WebSearchProvider {
            override val id: String = "x"
            override val capabilities: Set<WebCapability> = emptySet()
            override suspend fun probe(): WebProbe = WebProbe.Ready
            override suspend fun search(query: String, count: Int): WebSearchOutcome {
                if (calls.incrementAndGet() == 2) bothEntered.complete(Unit)
                release.await()
                return WebSearchOutcome.Ok(listOf(okResult(query, "https://example.com/$query")))
            }
        }
        val svc = WebSearchService(delegate)
        val a = async { svc.search("alpha", 5) }
        val b = async { svc.search("beta", 5) }
        withTimeout(5_000) { bothEntered.await() }
        release.complete(Unit)
        val oa = withTimeout(5_000) { a.await() } as WebSearchOutcome.Ok
        val ob = withTimeout(5_000) { b.await() } as WebSearchOutcome.Ok
        assertEquals(2, calls.get())
        assertTrue(oa.results.single().url.contains("alpha"))
        assertTrue(ob.results.single().url.contains("beta"))
    }

    @Test fun blankQueriesBypassCache() = runBlocking {
        val cache = WebSearchCache()
        var calls = 0
        val delegate = object : WebSearchProvider {
            override val id: String = "x"
            override val capabilities: Set<WebCapability> = emptySet()
            override suspend fun probe(): WebProbe = WebProbe.Ready
            override suspend fun search(query: String, count: Int): WebSearchOutcome {
                calls++
                return WebSearchOutcome.Unavailable(WebUnavailableReason.EMPTY_QUERY)
            }
        }
        val svc = WebSearchService(delegate, cache)
        assertTrue(svc.search("   ", 5) is WebSearchOutcome.Unavailable)
        assertTrue(svc.search("", 5) is WebSearchOutcome.Unavailable)
        assertEquals(2, calls)
        assertEquals(0, cache.size)
    }

    @Test fun trimAliasCoalescesToOneUpstream() = runBlocking {
        var calls = 0
        val delegate = object : WebSearchProvider {
            override val id: String = "x"
            override val capabilities: Set<WebCapability> = emptySet()
            override suspend fun probe(): WebProbe = WebProbe.Ready
            override suspend fun search(query: String, count: Int): WebSearchOutcome {
                calls++
                return WebSearchOutcome.Ok(listOf(okResult()))
            }
        }
        val svc = WebSearchService(delegate)
        svc.search("kotlin", 5)
        val second = svc.search("  kotlin  ", 5) as WebSearchOutcome.Ok
        assertTrue(second.cached)
        assertEquals(1, calls)
    }

    @Test fun failedCoalescingSingleUpstreamAndRetry() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val calls = AtomicInteger(0)
        val delegate = object : WebSearchProvider {
            override val id: String = "x"
            override val capabilities: Set<WebCapability> = emptySet()
            override suspend fun probe(): WebProbe = WebProbe.Ready
            override suspend fun search(query: String, count: Int): WebSearchOutcome {
                if (calls.incrementAndGet() == 1) {
                    entered.complete(Unit)
                    release.await()
                }
                return WebSearchOutcome.Failed(WebFailureReason.SUPPLIER_ERROR)
            }
        }
        val svc = WebSearchService(delegate)
        val leader = async { svc.search("kotlin", 5) }
        withTimeout(5_000) { entered.await() }
        val followers = List(4) {
            async(start = CoroutineStart.UNDISPATCHED) { svc.search("kotlin", 5) }
        }
        release.complete(Unit)
        val outs = withTimeout(5_000) { (listOf(leader) + followers).map { it.await() } }
        assertTrue(outs.all { it is WebSearchOutcome.Failed })
        assertEquals(1, calls.get())
        // Failed not cached: retry re-fetches.
        assertTrue(svc.search("kotlin", 5) is WebSearchOutcome.Failed)
        assertEquals(2, calls.get())
    }

    // ---- Cache (direct) ----

    @Test fun cacheProviderIsolation() {
        val cache = WebSearchCache()
        val ok = WebSearchOutcome.Ok(listOf(okResult()))
        cache.put("a", "q", 5, ok)
        cache.put("b", "q", 5, ok)
        assertEquals(2, cache.size)
        assertNotNull(cache.get("a", "q", 5))
        assertNotNull(cache.get("b", "q", 5))
    }

    @Test fun cacheClearTriggersRefetch() = runBlocking {
        val http = FakeHttp { WebHttpResponse(200, """{"results":[{"title":"T","url":"https://example.com/"}]}""") }
        val svc = WebSearchService(SearxngSearchProvider("https://search.example", http))
        svc.search("kotlin", 5)
        assertEquals(1, http.calls.get())
        svc.invalidate()
        val second = svc.search("kotlin", 5) as WebSearchOutcome.Ok
        assertFalse(second.cached)
        assertEquals(2, http.calls.get())
    }

    @Test fun cacheEvictExpiredRemovesExpired() {
        var now = 1_000L
        val cache = WebSearchCache(clockMs = { now })
        cache.put("a", "q", 5, WebSearchOutcome.Ok(listOf(okResult())))
        assertEquals(1, cache.size)
        now += WebSearchCache.DEFAULT_TTL_MS // reach expiry
        cache.evictExpired()
        assertEquals(0, cache.size)
        assertNull(cache.get("a", "q", 5))
    }

    @Test fun cacheTtlNonPositiveThrows() {
        assertThrows(IllegalArgumentException::class.java) { WebSearchCache(ttlMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { WebSearchCache(ttlMs = -1) }
    }

    @Test fun cacheBackwardsClockStillHits() {
        var now = 10_000L
        val cache = WebSearchCache(clockMs = { now })
        cache.put("a", "q", 5, WebSearchOutcome.Ok(listOf(okResult())))
        now = 5_000L // wall-clock moved backwards
        assertNotNull(cache.get("a", "q", 5))
    }

    @Test fun cacheDefensiveCopy() {
        val cache = WebSearchCache()
        val backing = mutableListOf(okResult("t1"))
        cache.put("a", "q", 5, WebSearchOutcome.Ok(backing))
        backing.add(okResult("poison"))
        assertEquals(1, cache.get("a", "q", 5)!!.results.size)
        // get returns a fresh copy each time: equal content, distinct instances.
        val g1 = cache.get("a", "q", 5)!!
        val g2 = cache.get("a", "q", 5)!!
        assertEquals(g1.results, g2.results)
        assertTrue(g1.results !== g2.results)
    }

    @Test fun cacheLruBoundEvictsEldest() {
        val cache = WebSearchCache()
        val ok = WebSearchOutcome.Ok(listOf(okResult()))
        for (i in 0 until WebSearchCache.MAX_ENTRIES + 1) {
            cache.put("a", "q$i", 5, ok)
        }
        assertEquals(WebSearchCache.MAX_ENTRIES, cache.size)
        assertNull(cache.get("a", "q0", 5))
        assertNotNull(cache.get("a", "q${WebSearchCache.MAX_ENTRIES}", 5))
    }

    // ---- Selector / Registry ----

    @Test fun blankPinnedTreatedAsUnpinnedConsistently() {
        val fake = object : WebSearchProvider {
            override val id: String = "searxng"
            override val capabilities: Set<WebCapability> = emptySet()
            override suspend fun probe(): WebProbe = WebProbe.Ready
            override suspend fun search(query: String, count: Int): WebSearchOutcome =
                WebSearchOutcome.Ok(emptyList())
        }
        val r = WebSearchRegistry(listOf(fake))
        // select: blank pin == unpinned -> hosted wins when available.
        assertEquals(
            WebRoute.Hosted,
            WebSearchSelector.select(pinnedId = "   ", hostedAvailable = true, registry = r),
        )
        // mayFallback: blank pin == unpinned -> TRANSPORT may fallback...
        assertTrue(
            WebSearchSelector.mayFallback("   ", WebSearchOutcome.Failed(WebFailureReason.TRANSPORT)),
        )
        // ...but Ok never fallbacks (the "false" consistency half).
        assertFalse(
            WebSearchSelector.mayFallback("   ", WebSearchOutcome.Ok(emptyList())),
        )
        assertFalse(
            WebSearchSelector.mayFallback("", WebSearchOutcome.Unavailable(WebUnavailableReason.MISSING_KEY)),
        )
    }

    @Test fun selectTrimsPinnedId() {
        val fake = object : WebSearchProvider {
            override val id: String = "searxng"
            override val capabilities: Set<WebCapability> = emptySet()
            override suspend fun probe(): WebProbe = WebProbe.Ready
            override suspend fun search(query: String, count: Int): WebSearchOutcome =
                WebSearchOutcome.Ok(emptyList())
        }
        val r = WebSearchRegistry(listOf(fake))
        assertEquals(
            WebRoute.Local("searxng"),
            WebSearchSelector.select(pinnedId = " searxng ", hostedAvailable = true, registry = r),
        )
    }

    @Test fun registryBlankAndSeparatorIdsThrow() {
        fun bad(id: String) = object : WebSearchProvider {
            override val id: String = id
            override val capabilities: Set<WebCapability> = emptySet()
            override suspend fun probe(): WebProbe = WebProbe.Ready
            override suspend fun search(query: String, count: Int): WebSearchOutcome =
                WebSearchOutcome.Ok(emptyList())
        }
        val blank = assertThrows(IllegalArgumentException::class.java) {
            WebSearchRegistry(listOf(bad("   ")))
        }
        assertTrue(blank.message!!.contains("INVALID_PROVIDER_ID"))
        val sep = assertThrows(IllegalArgumentException::class.java) {
            WebSearchRegistry(listOf(bad("a\u0001b")))
        }
        assertTrue(sep.message!!.contains("INVALID_PROVIDER_ID"))
    }

    @Test fun registryIdsCopyMutationSafe() {
        val fake = object : WebSearchProvider {
            override val id: String = "searxng"
            override val capabilities: Set<WebCapability> = emptySet()
            override suspend fun probe(): WebProbe = WebProbe.Ready
            override suspend fun search(query: String, count: Int): WebSearchOutcome =
                WebSearchOutcome.Ok(emptyList())
        }
        val r = WebSearchRegistry(listOf(fake))
        val copy = r.ids().toMutableSet()
        copy.add("evil")
        assertFalse(r.contains("evil"))
        assertEquals(1, r.size)
        assertEquals(setOf("searxng"), r.ids())
    }

    // ---- Models ----

    @Test fun firstItemTitleOverflowTruncatesTitle() {
        val r = WebSearchResult("A".repeat(100), "https://e.com/", "s")
        val out = capResultsForModel(listOf(r), maxChars = 10)
        assertTrue(out.truncated)
        assertEquals(1, out.results.size)
        assertEquals("", out.results.single().snippet)
        assertEquals(10, out.results.single().title.length)
    }

    @Test fun roomZeroKeepsBoundaryWithEmptySnippet() {
        val r1 = WebSearchResult("A", "UU", "SS") // cost 1+2+2=5
        val r2 = WebSearchResult("TTT", "UU", "long-snippet-body")
        val out = capResultsForModel(listOf(r1, r2), maxChars = 10)
        // used=5 after r1; room for r2 = 10-5-3-2 = 0 -> kept with "" snippet.
        assertTrue(out.truncated)
        assertEquals(2, out.results.size)
        assertEquals("", out.results.last().snippet)
        assertEquals("TTT", out.results.last().title)
    }

    @Test fun emptyInputNotTruncated() {
        val out = capResultsForModel(emptyList(), maxChars = 100)
        assertFalse(out.truncated)
        assertTrue(out.results.isEmpty())
    }

    @Test fun maxCharsNonPositiveCoercedToOne() {
        val r = WebSearchResult("AB", "U", "S")
        for (cap in listOf(0, -5)) {
            val out = capResultsForModel(listOf(r), maxChars = cap)
            assertTrue("cap=$cap", out.truncated)
            assertEquals("cap=$cap", 1, out.results.size)
        }
    }

    @Test fun emojiSurrogatePairNotSplit() {
        val emoji = "😀" // 2 UTF-16 units
        // title 1 + url 1 = 2 used; cap 5 -> room 3 -> take(3) would split 2nd emoji.
        val r = WebSearchResult("T", "U", emoji + emoji)
        val out = capResultsForModel(listOf(r), maxChars = 5)
        assertTrue(out.truncated)
        val snippet = out.results.single().snippet
        assertFalse(snippet.last().isHighSurrogate())
        assertEquals(emoji, snippet) // split high surrogate dropped
    }

    @Test fun longUrlCountsTowardBudget() {
        val r1 = WebSearchResult("A", "UU", "SS") // cost 5
        val r2 = WebSearchResult("T", "U".repeat(100), "S")
        val out = capResultsForModel(listOf(r1, r2), maxChars = 20)
        // If url were ignored r2 would fit; url counted -> r2 dropped.
        assertTrue(out.truncated)
        assertEquals(1, out.results.size)
    }

    @Test fun searxngCountClipSetsTruncated() = runBlocking {
        val json = """{"results":[
            |{"title":"A","url":"https://example.com/a","content":"x"},
            |{"title":"B","url":"https://example.com/b","content":"y"},
            |{"title":"C","url":"https://example.com/c","content":"z"}
            |]}""".trimMargin()
        val http = FakeHttp { WebHttpResponse(200, json) }
        val out = SearxngSearchProvider("https://search.example", http).search("q", 1) as WebSearchOutcome.Ok
        assertEquals(1, out.results.size)
        assertTrue(out.truncated) // hits(3) > n(1) -> clipped
    }

    // ---- Providers / HTTP ----

    @Test fun failureForStatus302And504() {
        assertEquals(WebFailureReason.TRANSPORT, failureForStatus(302))
        assertEquals(WebFailureReason.TIMEOUT, failureForStatus(504))
        assertNull(failureForStatus(200))
    }

    @Test fun searxngUppercaseHttpsAcceptedPerImpl() {
        val p = SearxngSearchProvider("https://search.example", FakeHttp { WebHttpResponse(200, "{}") })
        val hits = p.parseSearxng("""{"results":[{"title":"U","url":"HTTPS://example.com/x","content":"s"}]}""")
        // Impl matches scheme ignoreCase -> uppercase HTTPS kept, not skipped.
        assertNotNull(hits)
        assertEquals(1, hits!!.size)
        assertEquals("HTTPS://example.com/x", hits.single().url)
    }

    @Test fun ddgsChallengeFormIsSupplierError() = runBlocking {
        val http = FakeHttp {
            WebHttpResponse(200, """<html><body><form class="challenge-form">x</form></body></html>""")
        }
        val out = DdgsSearchProvider(http).search("q", 5)
        assertTrue(out is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.SUPPLIER_ERROR, (out as WebSearchOutcome.Failed).reason)
    }

    @Test fun hostOfUppercaseWwwPortCases() {
        assertEquals("example.com", hostOf("HTTPS://WWW.Example.COM:8080/path"))
        assertEquals("example.com", hostOf("https://www.example.com/"))
        assertEquals("example.com", hostOf("http://example.com:80/"))
        assertNull(hostOf("not a url"))
    }

    // ---- follower-cancel sanity (mirror, latch-based, no delay) ----

    @Test fun followerCancelDoesNotAffectLeaderLatchBased() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val calls = AtomicInteger(0)
        val http = FakeHttp {
            calls.incrementAndGet()
            entered.complete(Unit)
            release.await()
            WebHttpResponse(200, """{"results":[{"title":"T","url":"https://example.com/"}]}""")
        }
        val svc = WebSearchService(
            SearxngSearchProvider("https://search.example", http, timeoutMs = 60_000),
        )
        val leader = async { svc.search("kotlin", 5) }
        withTimeout(5_000) { entered.await() }
        val follower = async(start = CoroutineStart.UNDISPATCHED) { svc.search("kotlin", 5) }
        follower.cancelAndJoin()
        assertTrue(follower.isCancelled)
        release.complete(Unit)
        val out = withTimeout(5_000) { leader.await() } as WebSearchOutcome.Ok
        assertFalse(out.cached)
        assertEquals(1, calls.get())
    }

    @Test fun throwPlusCancelFollowerCompletesLeaderThrows() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger(0)
        val delegate = object : WebSearchProvider {
            override val id: String = "x"
            override val capabilities: Set<WebCapability> = emptySet()
            override suspend fun probe(): WebProbe = WebProbe.Ready
            override suspend fun search(query: String, count: Int): WebSearchOutcome {
                calls.incrementAndGet()
                entered.complete(Unit)
                // NonCancellable block: leader.cancel() stays pending until release,
                // then delegate throws (not CE) so the service Exception arm runs
                // with a cancelled context — the BLOCK regression window.
                withContext(NonCancellable) { release.await() }
                throw RuntimeException("boom")
            }
        }
        val svc = WebSearchService(delegate)
        val leader = async { svc.search("kotlin", 5) }
        withTimeout(5_000) { entered.await() }
        // UNDISPATCHED: follower suspended on the shared deferred before cancel.
        val follower = async(start = CoroutineStart.UNDISPATCHED) { svc.search("kotlin", 5) }
        leader.cancel()
        release.complete(Unit)
        try {
            withTimeout(5_000) { leader.await() }
            fail("leader must throw CE")
        } catch (_: CancellationException) {
            // expected: leader cancelled, Exception arm rethrows CE
        }
        val out = withTimeout(5_000) { follower.await() }
        assertTrue(out is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.TRANSPORT, (out as WebSearchOutcome.Failed).reason)
        assertEquals(2, calls.get())
    }
}
