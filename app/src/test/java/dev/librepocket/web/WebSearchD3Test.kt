package dev.librepocket.web

import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.ToolRegistry
import dev.librepocket.tool.WebSearchLocal
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D3 adapter tests（#138 D3；全 fake：fake HTTP＋fake 時鐘，零真網）。
 *
 * - SearXNG fixture→hits（經 adapter→[WebSearchLocal.Result] 映射）；
 * - DDG `uddg=` 解開（經 adapter，需 ddgs opt-in）；
 * - cache 命中（第二次零 upstream，經 `WebSearchService`）；
 * - 取消不 fallback、不寫 cache（adapter 層行為）；
 * - master 關→`PROVIDER_DISABLED` 零 upstream；pinned DDGS 未 opt-in→`PINNED_UNAVAILABLE`；
 * - `Failed(TRANSPORT)` 非 pinned＋opt-in→fallback DDGS；AUTH 不 fallback；
 * - KeyVault 命名空間 `web.<providerId>`＋keyId 綁 providerId＋origin，不與聊天 key 共用；
 * - DDGS 非官方警語存在；`executionReady` 維持 false。
 */
class WebSearchD3Test {

    private val searxngJson = """
        {"query":"kotlin","results":[
          {"title":"Kotlin Docs","url":"https://kotlinlang.org/","content":"Modern language","publishedDate":"2024-01-02"},
          {"url":"https://example.com/nocontent"}
        ]}
    """.trimIndent()

    private val ddgHtml = """
        <html><body>
        <div class="result"><h2 class="result__title">
        <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2F&rut=abc">Kotlin <b>Docs</b></a>
        </h2>
        <a class="result__snippet" href="//duckduckgo.com/l/?uddg=x">Modern <b>language</b></a>
        </div>
        </body></html>
    """.trimIndent()

    private class FakeHttp(
        val handler: suspend (url: String) -> WebHttpResponse,
    ) : WebHttpGet {
        val calls = AtomicInteger(0)
        override suspend fun get(url: String, headers: Map<String, String>): WebHttpResponse {
            calls.incrementAndGet()
            return handler(url)
        }
    }

    private fun searxngService(
        http: FakeHttp,
        baseUrl: String = "https://search.example",
    ): WebSearchService = WebSearchService(SearxngSearchProvider(baseUrl, http))

    private fun ddgsService(http: FakeHttp): WebSearchService =
        WebSearchService(DdgsSearchProvider(http))

    private fun cfg(
        ddgsOptIn: Boolean = false,
        pinnedId: String? = null,
        hosted: Boolean = false,
    ) = WebSearchAdapter.Config(
        masterEnabled = true,
        pinnedId = pinnedId,
        ddgsOptIn = ddgsOptIn,
        hostedAvailable = hosted,
    )

    // ---- SearXNG fixture → hits（經 adapter 映射） ----

    @Test fun searxngFixtureViaAdapterMapsHits() = runBlocking {
        val http = FakeHttp { WebHttpResponse(200, searxngJson) }
        val registry = WebSearchRegistry(listOf(searxngService(http)))
        val out = WebSearchAdapter.search("kotlin", 5, registry, cfg())
        assertTrue(out is WebSearchOutcome.Ok)
        val ok = out as WebSearchOutcome.Ok
        assertEquals(2, ok.results.size)
        assertEquals("Kotlin Docs", ok.results[0].title)
        assertEquals("https://kotlinlang.org/", ok.results[0].url)
        assertFalse(ok.cached)

        // 經 WebSearchLocal suspend 橋接 → Result 三欄映射。
        val local = WebSearchLocal.searchSuspend(
            query = "kotlin",
            count = 5,
            registry = registry,
            masterEnabled = true,
        )
        assertTrue(local is WebSearchLocal.SearchOutcome.Ok)
        val results = (local as WebSearchLocal.SearchOutcome.Ok).results
        assertEquals(2, results.size)
        assertEquals("Kotlin Docs", results[0].title)
        assertEquals("https://kotlinlang.org/", results[0].url)
        assertEquals("Modern language", results[0].snippet)
    }

    // ---- DDG uddg= 解開（需 opt-in） ----

    @Test fun ddgsUddgUnwrappedViaAdapterWhenOptedIn() = runBlocking {
        val http = FakeHttp { WebHttpResponse(200, ddgHtml) }
        val registry = WebSearchRegistry(listOf(ddgsService(http)))
        // 未 opt-in → DDGS 被排除 → NO_PROVIDER。
        val excluded = WebSearchAdapter.search("kotlin", 5, registry, cfg(ddgsOptIn = false))
        assertTrue(excluded is WebSearchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.NO_PROVIDER, (excluded as WebSearchOutcome.Unavailable).reason)
        assertEquals(0, http.calls.get())

        // opt-in → 命中並解開 uddg。
        val out = WebSearchAdapter.search("kotlin", 5, registry, cfg(ddgsOptIn = true))
        assertTrue(out is WebSearchOutcome.Ok)
        val ok = out as WebSearchOutcome.Ok
        assertEquals(1, ok.results.size)
        assertEquals("https://kotlinlang.org/", ok.results[0].url)
        assertEquals("Kotlin Docs", ok.results[0].title)
    }

    // ---- cache 命中（第二次零 upstream） ----

    @Test fun cacheSecondCallZeroUpstreamViaAdapter() = runBlocking {
        val http = FakeHttp { WebHttpResponse(200, searxngJson) }
        val registry = WebSearchRegistry(listOf(searxngService(http)))
        val first = WebSearchAdapter.search("kotlin", 5, registry, cfg())
        assertTrue(first is WebSearchOutcome.Ok)
        assertFalse((first as WebSearchOutcome.Ok).cached)
        val second = WebSearchAdapter.search("kotlin", 5, registry, cfg())
        assertTrue(second is WebSearchOutcome.Ok)
        assertTrue((second as WebSearchOutcome.Ok).cached)
        assertEquals(1, http.calls.get())
    }

    // ---- 取消不 fallback、不寫 cache（adapter 層） ----

    @Test fun cancelDoesNotFallbackOrWriteCache() = runBlocking {
        val release = CompletableDeferred<Unit>()
        var observedCancel = false
        val searxngHttp = FakeHttp {
            try {
                release.await()
                WebHttpResponse(200, searxngJson)
            } catch (e: CancellationException) {
                observedCancel = true
                throw e
            }
        }
        // SearXNG 先卡住、DDGS opt-in 待命：取消必須透傳，不換 DDGS、不寫 cache。
        val ddgsHttp = FakeHttp { WebHttpResponse(200, ddgHtml) }
        val registry = WebSearchRegistry(
            listOf(searxngService(searxngHttp), ddgsService(ddgsHttp)),
        )
        val job = async {
            WebSearchAdapter.search("kotlin", 5, registry, cfg(ddgsOptIn = true))
        }
        withTimeout(5_000) { while (searxngHttp.calls.get() < 1) delay(1) }
        job.cancelAndJoin()
        assertTrue(observedCancel)
        assertTrue(job.isCancelled)
        assertEquals(0, ddgsHttp.calls.get())

        release.complete(Unit)
        val out = WebSearchAdapter.search("kotlin", 5, registry, cfg(ddgsOptIn = true))
        assertTrue(out is WebSearchOutcome.Ok)
        assertFalse((out as WebSearchOutcome.Ok).cached)
        assertEquals(2, searxngHttp.calls.get())
    }

    // ---- master 關／pinned／fallback 規則 ----

    @Test fun masterOffIsProviderDisabledZeroUpstream() = runBlocking {
        val http = FakeHttp { WebHttpResponse(200, searxngJson) }
        val registry = WebSearchRegistry(listOf(searxngService(http)))
        val out = WebSearchAdapter.search(
            "kotlin",
            5,
            registry,
            WebSearchAdapter.Config(masterEnabled = false),
        )
        assertTrue(out is WebSearchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.PROVIDER_DISABLED, (out as WebSearchOutcome.Unavailable).reason)
        assertEquals(0, http.calls.get())

        val local = WebSearchLocal.searchSuspend("kotlin", 5, registry, masterEnabled = false)
        assertTrue(local is WebSearchLocal.SearchOutcome.Unavailable)
        val u = local as WebSearchLocal.SearchOutcome.Unavailable
        assertEquals(DenyReason.USER_DISABLED, u.reason)
        assertEquals("PROVIDER_DISABLED", u.detail)
    }

    @Test fun pinnedDdgsWithoutOptInIsPinnedUnavailable() = runBlocking {
        val http = FakeHttp { WebHttpResponse(200, ddgHtml) }
        val registry = WebSearchRegistry(listOf(ddgsService(http)))
        val out = WebSearchAdapter.search(
            "kotlin",
            5,
            registry,
            cfg(ddgsOptIn = false, pinnedId = "ddgs"),
        )
        assertTrue(out is WebSearchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.PINNED_UNAVAILABLE, (out as WebSearchOutcome.Unavailable).reason)
        assertEquals(0, http.calls.get())
    }

    @Test fun transportFallbackToDdgsWhenOptedIn() = runBlocking {
        val searxngHttp = FakeHttp { WebHttpResponse(500, "boom") }
        val ddgsHttp = FakeHttp { WebHttpResponse(200, ddgHtml) }
        val registry = WebSearchRegistry(
            listOf(searxngService(searxngHttp), ddgsService(ddgsHttp)),
        )
        // 未 opt-in：DDGS 排除 → SearXNG SUPPLIER_ERROR 直接回（無處可換）。
        val noOpt = WebSearchAdapter.search("kotlin", 5, registry, cfg(ddgsOptIn = false))
        assertTrue(noOpt is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.SUPPLIER_ERROR, (noOpt as WebSearchOutcome.Failed).reason)
        assertEquals(1, searxngHttp.calls.get() + ddgsHttp.calls.get() - 0)
        assertEquals(0, ddgsHttp.calls.get())

        // opt-in：SearXNG SUPPLIER_ERROR(fallbackAllowed)→換 DDGS 成功。
        val searxngHttp2 = FakeHttp { WebHttpResponse(500, "boom") }
        val ddgsHttp2 = FakeHttp { WebHttpResponse(200, ddgHtml) }
        val registry2 = WebSearchRegistry(
            listOf(searxngService(searxngHttp2), ddgsService(ddgsHttp2)),
        )
        val out = WebSearchAdapter.search("kotlin", 5, registry2, cfg(ddgsOptIn = true))
        assertTrue(out is WebSearchOutcome.Ok)
        assertEquals("https://kotlinlang.org/", (out as WebSearchOutcome.Ok).results.single().url)
        assertEquals(1, searxngHttp2.calls.get())
        assertEquals(1, ddgsHttp2.calls.get())
    }

    @Test fun authNeverFallbackEvenWhenOptedIn() = runBlocking {
        val searxngHttp = FakeHttp { WebHttpResponse(401, "unauthorized") }
        val ddgsHttp = FakeHttp { WebHttpResponse(200, ddgHtml) }
        val registry = WebSearchRegistry(
            listOf(searxngService(searxngHttp), ddgsService(ddgsHttp)),
        )
        val out = WebSearchAdapter.search("kotlin", 5, registry, cfg(ddgsOptIn = true))
        assertTrue(out is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.AUTH, (out as WebSearchOutcome.Failed).reason)
        assertFalse(out.fallbackAllowed)
        assertEquals(1, searxngHttp.calls.get())
        assertEquals(0, ddgsHttp.calls.get())
    }

    @Test fun unavailableNeverFallback_pinnedNeverFallback() = runBlocking {
        // SearXNG 缺 URL → Unavailable，不換 DDGS。
        val ddgsHttp = FakeHttp { WebHttpResponse(200, ddgHtml) }
        val registry = WebSearchRegistry(
            listOf(SearxngSearchProvider("  ", ddgsHttp), DdgsSearchProvider(ddgsHttp)),
        )
        val out = WebSearchAdapter.search("kotlin", 5, registry, cfg(ddgsOptIn = true))
        assertTrue(out is WebSearchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.MISSING_URL, (out as WebSearchOutcome.Unavailable).reason)
        assertEquals(0, ddgsHttp.calls.get())

        // pinned SearXNG 失敗 → 不換（mayFallback 恆 false）。
        val searxngHttp = FakeHttp { WebHttpResponse(500, "boom") }
        val ddgsHttp2 = FakeHttp { WebHttpResponse(200, ddgHtml) }
        val registry2 = WebSearchRegistry(
            listOf(searxngService(searxngHttp), ddgsService(ddgsHttp2)),
        )
        val pinned = WebSearchAdapter.search(
            "kotlin",
            5,
            registry2,
            cfg(ddgsOptIn = true, pinnedId = "searxng"),
        )
        assertTrue(pinned is WebSearchOutcome.Failed)
        assertEquals(1, searxngHttp.calls.get())
        assertEquals(0, ddgsHttp2.calls.get())
    }

    @Test fun emptyQueryShortCircuitsZeroUpstream() = runBlocking {
        val http = FakeHttp { WebHttpResponse(200, searxngJson) }
        val registry = WebSearchRegistry(listOf(searxngService(http)))
        val out = WebSearchAdapter.search("   ", 5, registry, cfg())
        assertTrue(out is WebSearchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.EMPTY_QUERY, (out as WebSearchOutcome.Unavailable).reason)
        assertEquals(0, http.calls.get())
    }

    @Test fun hostedAvailableUnpinnedIsHostedNotSupported() = runBlocking {
        val http = FakeHttp { WebHttpResponse(200, searxngJson) }
        val registry = WebSearchRegistry(listOf(searxngService(http)))
        val out = WebSearchAdapter.search("kotlin", 5, registry, cfg(hosted = true))
        assertTrue(out is WebSearchOutcome.Unavailable)
        assertEquals(
            WebUnavailableReason.HOSTED_NOT_SUPPORTED,
            (out as WebSearchOutcome.Unavailable).reason,
        )
        assertEquals(0, http.calls.get())
    }

    // ---- KeyVault 命名空間＋DDGS 警語＋executionReady ----

    @Test fun keyVaultNamespaceIsWebPrefixedAndBoundToOrigin() {
        assertEquals("web.searxng", WebSearchSettings.namespaceFor("searxng"))
        assertEquals("web.ddgs", WebSearchSettings.namespaceFor("ddgs"))
        val a = WebSearchSettings.keyIdFor("searxng", "https://search.example")
        val b = WebSearchSettings.keyIdFor("searxng", "https://other.example")
        // 同 provider＋同 origin 同 key、異 origin 異 key（切換不可沿用）。
        assertEquals(a, WebSearchSettings.keyIdFor("searxng", "https://search.example/"))
        assertTrue(a.contains("searxng") && a.contains("search.example"))
        assertTrue(a != b)
        // 不與聊天 key 共用（聊天為 `preset:*`／`custom:*` 形狀，見 SetupValidation）。
        assertFalse(a.startsWith("preset:"))
        assertFalse(a.startsWith("custom:"))
        assertTrue(a.startsWith("web."))
    }

    @Test fun ddgsUnofficialWarningMustExist() {
        assertTrue(WebSearchSettings.DDGS_UNOFFICIAL_WARNING.contains("非官方"))
        assertTrue(WebSearchSettings.CONSENT_TEXT.isNotBlank())
    }

    @Test fun webSearchExecutionReadyStaysFalse() {
        val ctx = ProjectionContext(
            flavor = Flavor.PLAY,
            userSwitches = mapOf(WebSearchLocal.SWITCH to true),
        )
        // 投影可 NATIVE（開關語義），但 executionReady 恆 false（不可執行）。
        assertEquals(
            dev.librepocket.tool.CapabilityLevel.NATIVE,
            ToolRegistry.projectAll(ctx).getValue(WebSearchLocal.TOOL_NAME).level,
        )
        assertFalse(ToolRegistry.find(WebSearchLocal.TOOL_NAME)!!.executionReady)
        assertEquals(1, ToolRegistry.ALL.count { it.executionReady })
    }

    @Test fun providerStatusMapping() {
        assertEquals(
            WebSearchSettings.ProviderStatus.AVAILABLE,
            WebSearchSettings.statusFor(WebProbe.Ready),
        )
        assertEquals(
            WebSearchSettings.ProviderStatus.MISSING_URL,
            WebSearchSettings.statusFor(WebProbe.NotReady(WebUnavailableReason.MISSING_URL)),
        )
        assertEquals(
            WebSearchSettings.ProviderStatus.MISSING_KEY,
            WebSearchSettings.statusFor(WebProbe.NotReady(WebUnavailableReason.MISSING_KEY)),
        )
        assertEquals("可用", WebSearchSettings.statusText(WebSearchSettings.ProviderStatus.AVAILABLE))
        assertEquals("缺 URL", WebSearchSettings.statusText(WebSearchSettings.ProviderStatus.MISSING_URL))
        assertEquals("缺 key", WebSearchSettings.statusText(WebSearchSettings.ProviderStatus.MISSING_KEY))
    }
}
