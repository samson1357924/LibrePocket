package dev.librepocket.web

import dev.librepocket.provider.ServerToolTranscript
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * D1 provider 核心 fixture tests（#138 D1；全 fake，零真網）。
 *
 * timeout／cache／single-flight 待 D2；SearXNG／DDGS 待 D2；不接 production。
 */
class WebSearchCoreTest {

    private class FakeOk(
        override val id: String,
        private val hits: List<WebSearchResult> = listOf(
            WebSearchResult("t1", "https://example.com/1", "s1"),
            WebSearchResult("t2", "https://example.com/2", "s2"),
        ),
    ) : WebSearchProvider {
        override val capabilities: Set<WebCapability> = emptySet()
        var calls = 0
        override suspend fun probe(): WebProbe = WebProbe.Ready
        override suspend fun search(query: String, count: Int): WebSearchOutcome {
            if (query.isBlank()) return WebSearchOutcome.Unavailable(WebUnavailableReason.EMPTY_QUERY)
            calls++
            return WebSearchOutcome.Ok(hits.take(WebSearchBudgets.countOf(count)))
        }
    }

    private class FakeFail(
        override val id: String,
        private val reason: WebFailureReason,
    ) : WebSearchProvider {
        override val capabilities: Set<WebCapability> = emptySet()
        override suspend fun probe(): WebProbe = WebProbe.Ready
        override suspend fun search(query: String, count: Int): WebSearchOutcome =
            WebSearchOutcome.Failed(reason)
    }

    private class FakeNotReady(
        override val id: String,
        private val reason: WebUnavailableReason = WebUnavailableReason.MISSING_KEY,
    ) : WebSearchProvider {
        override val capabilities: Set<WebCapability> = setOf(WebCapability.REQUIRES_KEY)
        override suspend fun probe(): WebProbe = WebProbe.NotReady(reason)
        override suspend fun search(query: String, count: Int): WebSearchOutcome =
            WebSearchOutcome.Unavailable(reason)
    }

    // ---- registry ----

    @Test fun duplicateIdFailsFast() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            WebSearchRegistry(listOf(FakeOk("a"), FakeOk("a")))
        }
        assertTrue(e.message!!.contains("DUPLICATE_PROVIDER_ID"))
    }

    @Test fun registryLookupPreservesOrder() {
        val r = WebSearchRegistry(listOf(FakeOk("searxng"), FakeOk("ddgs")))
        assertEquals(2, r.size)
        assertEquals("searxng", r.providers()[0].id)
        assertTrue(r.contains("ddgs"))
        assertEquals(setOf("searxng", "ddgs"), r.ids())
    }

    // ---- models / budgets ----

    @Test fun countClampsTo1To10() {
        assertEquals(1, WebSearchBudgets.countOf(0))
        assertEquals(10, WebSearchBudgets.countOf(999))
        assertEquals(5, WebSearchBudgets.countOf(null))
    }

    @Test fun emptyQueryIsUnavailableWithoutCalling() = runBlocking {
        val f = FakeOk("x")
        val out = f.search("   ", 5)
        assertTrue(out is WebSearchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.EMPTY_QUERY, (out as WebSearchOutcome.Unavailable).reason)
        assertEquals(0, f.calls) // 空 query 在發請求前短路（D2 真實實作同契約）
    }

    @Test fun charBudgetTruncatesLikeReadCapped() {
        val big = List(5) { WebSearchResult("t$it", "https://example.com/$it", "s".repeat(100)) }
        val full = capResultsForModel(big, maxChars = 1_000_000)
        assertFalse(full.truncated)
        assertEquals(5, full.results.size)
        val capped = capResultsForModel(big, maxChars = 150)
        assertTrue(capped.truncated)
        val kept = capped.results.sumOf { it.title.length + it.snippet.length }
        assertTrue(kept <= 150)
        // url 可追溯：邊界筆 title／url 完整保留（只截 snippet）。
        assertEquals(2, capped.results.size)
        assertEquals("https://example.com/1", capped.results.last().url)
        assertTrue(capped.results.last().snippet.length < 100)
    }

    // ---- selector：hosted 優先（#134 D5） ----

    @Test fun hostedNameMirrorsProviderTranscript() {
        assertEquals(ServerToolTranscript.WEB_SEARCH_NAME, WebSearchSelector.HOSTED_TOOL_NAME)
        assertFalse("web.search" == WebSearchSelector.HOSTED_TOOL_NAME)
    }

    @Test fun pinnedLocalWinsOverHosted() {
        val r = WebSearchRegistry(listOf(FakeOk("searxng")))
        val route = WebSearchSelector.select(pinnedId = "searxng", hostedAvailable = true, registry = r)
        assertEquals(WebRoute.Local("searxng"), route)
    }

    @Test fun pinnedMissingNeverFallsBackOrHosts() {
        val r = WebSearchRegistry(listOf(FakeOk("searxng")))
        val route = WebSearchSelector.select(pinnedId = "nope", hostedAvailable = true, registry = r)
        assertTrue(route is WebRoute.Unavailable)
        assertEquals(WebUnavailableReason.PINNED_UNAVAILABLE, (route as WebRoute.Unavailable).reason)
    }

    @Test fun pinnedNotReadyIsUnavailable() {
        val r = WebSearchRegistry(listOf(FakeNotReady("keyed")))
        val route = WebSearchSelector.select(
            pinnedId = "keyed",
            hostedAvailable = false,
            registry = r,
            isReady = { false },
        )
        assertTrue(route is WebRoute.Unavailable)
        assertEquals(WebUnavailableReason.PINNED_UNAVAILABLE, (route as WebRoute.Unavailable).reason)
    }

    @Test fun unpinnedPrefersHosted() {
        val r = WebSearchRegistry(listOf(FakeOk("searxng")))
        assertEquals(
            WebRoute.Hosted,
            WebSearchSelector.select(pinnedId = null, hostedAvailable = true, registry = r),
        )
    }

    @Test fun unpinnedNoHostedFallsToFirstReady() {
        val r = WebSearchRegistry(listOf(FakeNotReady("keyed"), FakeOk("searxng")))
        val route = WebSearchSelector.select(
            pinnedId = null,
            hostedAvailable = false,
            registry = r,
            isReady = { it.id != "keyed" },
        )
        assertEquals(WebRoute.Local("searxng"), route)
    }

    @Test fun emptyRegistryIsUnavailable() {
        val route = WebSearchSelector.select(
            pinnedId = null,
            hostedAvailable = false,
            registry = WebSearchRegistry(emptyList()),
        )
        assertTrue(route is WebRoute.Unavailable)
        assertEquals(WebUnavailableReason.NO_PROVIDER, (route as WebRoute.Unavailable).reason)
    }

    // ---- fallback 規則：AUTH／PROTOCOL 不 fallback；pinned 不靜默換 ----

    @Test fun authAndProtocolNeverFallback() {
        assertFalse(WebSearchSelector.mayFallback(null, WebSearchOutcome.Failed(WebFailureReason.AUTH)))
        assertFalse(WebSearchSelector.mayFallback(null, WebSearchOutcome.Failed(WebFailureReason.PROTOCOL)))
        assertTrue(WebSearchSelector.mayFallback(null, WebSearchOutcome.Failed(WebFailureReason.TRANSPORT)))
        assertTrue(WebSearchSelector.mayFallback(null, WebSearchOutcome.Failed(WebFailureReason.TIMEOUT)))
    }

    @Test fun unavailableNeverFallback_pinnedNeverFallback() {
        assertFalse(
            WebSearchSelector.mayFallback(
                null,
                WebSearchOutcome.Unavailable(WebUnavailableReason.MISSING_KEY),
            ),
        )
        assertFalse(
            WebSearchSelector.mayFallback(
                "searxng",
                WebSearchOutcome.Failed(WebFailureReason.TRANSPORT),
            ),
        )
    }

    @Test fun fakeProvidersReturnTypedOutcomes() = runBlocking {
        val ok = FakeOk("a").search("kotlin", 1)
        assertTrue(ok is WebSearchOutcome.Ok)
        assertEquals(1, (ok as WebSearchOutcome.Ok).results.size)
        val auth = FakeFail("b", WebFailureReason.AUTH).search("q", 5)
        assertTrue(auth is WebSearchOutcome.Failed)
        assertFalse((auth as WebSearchOutcome.Failed).fallbackAllowed)
    }
}
