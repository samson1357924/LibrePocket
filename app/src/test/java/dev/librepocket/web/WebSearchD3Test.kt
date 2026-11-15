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
        consentConfirmed: Boolean = true,
    ) = WebSearchAdapter.Config(
        masterEnabled = true,
        pinnedId = pinnedId,
        ddgsOptIn = ddgsOptIn,
        hostedAvailable = hosted,
        consentConfirmed = consentConfirmed,
    )

    private fun stubProvider(
        id: String,
        probeResult: WebProbe = WebProbe.Ready,
        onProbe: (() -> Unit)? = null,
        onSearch: suspend (query: String, count: Int) -> WebSearchOutcome,
    ): WebSearchProvider = object : WebSearchProvider {
        override val id: String = id
        override val capabilities: Set<WebCapability> = emptySet()
        override suspend fun probe(): WebProbe {
            onProbe?.invoke()
            return probeResult
        }
        override suspend fun search(query: String, count: Int): WebSearchOutcome =
            onSearch(query, count)
    }

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
            consentConfirmed = true,
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

        val local = WebSearchLocal.searchSuspend(
            "kotlin",
            5,
            registry,
            masterEnabled = false,
            consentConfirmed = true,
        )
        assertTrue(local is WebSearchLocal.SearchOutcome.Unavailable)
        val u = local as WebSearchLocal.SearchOutcome.Unavailable
        assertEquals(DenyReason.USER_DISABLED, u.reason)
        assertEquals("PROVIDER_DISABLED", u.detail)
    }

    @Test fun consentNotConfirmedIsProviderDisabledZeroProbesZeroCalls() = runBlocking {
        val probes = AtomicInteger(0)
        val calls = AtomicInteger(0)
        val provider = stubProvider(
            id = "searxng",
            onProbe = { probes.incrementAndGet() },
            onSearch = { _, _ ->
                calls.incrementAndGet()
                WebSearchOutcome.Ok(emptyList())
            },
        )
        val registry = WebSearchRegistry(listOf(provider))
        val out = WebSearchAdapter.search(
            "kotlin",
            5,
            registry,
            cfg(consentConfirmed = false),
        )
        assertTrue(out is WebSearchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.PROVIDER_DISABLED, (out as WebSearchOutcome.Unavailable).reason)
        assertEquals(0, probes.get())
        assertEquals(0, calls.get())

        val local = WebSearchLocal.searchSuspend(
            query = "kotlin",
            count = 5,
            registry = registry,
            masterEnabled = true,
            consentConfirmed = false,
        )
        assertTrue(local is WebSearchLocal.SearchOutcome.Unavailable)
        val u = local as WebSearchLocal.SearchOutcome.Unavailable
        assertEquals(DenyReason.USER_DISABLED, u.reason)
        assertEquals("PROVIDER_DISABLED", u.detail)
        assertEquals(0, probes.get())
        assertEquals(0, calls.get())
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

    @Test fun unconfiguredSearxngWithDdgsOptIn_fallsThroughToDdgsAndSucceeds() = runBlocking {
        val searxngHttp = FakeHttp { WebHttpResponse(200, searxngJson) }
        val ddgsHttp = FakeHttp { WebHttpResponse(200, ddgHtml) }
        val registry = WebSearchRegistry(
            listOf(SearxngSearchProvider("", searxngHttp), DdgsSearchProvider(ddgsHttp)),
        )
        val out = WebSearchAdapter.search("kotlin", 5, registry, cfg(ddgsOptIn = true))
        assertTrue(out is WebSearchOutcome.Ok)
        assertEquals("https://kotlinlang.org/", (out as WebSearchOutcome.Ok).results.single().url)
        assertEquals(0, searxngHttp.calls.get())
        assertEquals(1, ddgsHttp.calls.get())
    }

    @Test fun readyProviderReturningUnavailableAtSearchTime_neverFallsBackToDdgs() = runBlocking {
        val ddgsHttp = FakeHttp { WebHttpResponse(200, ddgHtml) }
        val searxng = stubProvider("searxng", probeResult = WebProbe.Ready) { _, _ ->
            WebSearchOutcome.Unavailable(WebUnavailableReason.MISSING_URL)
        }
        val registry = WebSearchRegistry(listOf(searxng, DdgsSearchProvider(ddgsHttp)))
        val out = WebSearchAdapter.search("kotlin", 5, registry, cfg(ddgsOptIn = true))
        assertTrue(out is WebSearchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.MISSING_URL, (out as WebSearchOutcome.Unavailable).reason)
        assertEquals(0, ddgsHttp.calls.get())
    }

    @Test fun pinnedSearxngFailureNeverFallsBackToDdgs() = runBlocking {
        val searxngHttp = FakeHttp { WebHttpResponse(500, "boom") }
        val ddgsHttp = FakeHttp { WebHttpResponse(200, ddgHtml) }
        val registry = WebSearchRegistry(
            listOf(searxngService(searxngHttp), ddgsService(ddgsHttp)),
        )
        val pinned = WebSearchAdapter.search(
            "kotlin",
            5,
            registry,
            cfg(ddgsOptIn = true, pinnedId = "searxng"),
        )
        assertTrue(pinned is WebSearchOutcome.Failed)
        assertEquals(1, searxngHttp.calls.get())
        assertEquals(0, ddgsHttp.calls.get())
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
        assertFalse(WebSearchSettings.CONSENT_TEXT.contains("WebSearchCache"))
        assertTrue(WebSearchSettings.CONSENT_TEXT.contains("未經全域脫敏"))
        assertTrue(WebSearchSettings.CONSENT_TEXT.contains("備份"))
        assertTrue(
            WebSearchSettings.CONSENT_TEXT.contains(
                "當 SearXNG 未設定、格式無效或暫時失敗時將自動改送 DuckDuckGo 非官方端點，見下方警語",
            ),
        )
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
            WebSearchSettings.ProviderStatus.INVALID_URL,
            WebSearchSettings.statusFor(WebProbe.NotReady(WebUnavailableReason.INVALID_URL)),
        )
        assertEquals(
            WebSearchSettings.ProviderStatus.MISSING_KEY,
            WebSearchSettings.statusFor(WebProbe.NotReady(WebUnavailableReason.MISSING_KEY)),
        )
        assertEquals(
            WebSearchSettings.ProviderStatus.NOT_READY,
            WebSearchSettings.statusFor(WebProbe.NotReady(WebUnavailableReason.NO_PROVIDER)),
        )
        assertEquals(
            "設定格式有效（未連網）",
            WebSearchSettings.statusText(WebSearchSettings.ProviderStatus.AVAILABLE),
        )
        assertEquals("缺 URL", WebSearchSettings.statusText(WebSearchSettings.ProviderStatus.MISSING_URL))
        assertEquals("URL 格式無效", WebSearchSettings.statusText(WebSearchSettings.ProviderStatus.INVALID_URL))
        assertEquals("缺 key", WebSearchSettings.statusText(WebSearchSettings.ProviderStatus.MISSING_KEY))
        assertEquals("未就緒", WebSearchSettings.statusText(WebSearchSettings.ProviderStatus.NOT_READY))
    }

    // ---- Phase 1 additions: pinning / ordering / service / failure / cancellation / validation ----

    @Test fun pinnedUnreadyWithDdgsOptInAndHostedAvailableIsPinnedUnavailableZeroCalls() = runBlocking {
        val searxngHttp = FakeHttp { WebHttpResponse(200, searxngJson) }
        val ddgsHttp = FakeHttp { WebHttpResponse(200, ddgHtml) }
        val registry = WebSearchRegistry(
            listOf(SearxngSearchProvider("", searxngHttp), DdgsSearchProvider(ddgsHttp)),
        )
        val out = WebSearchAdapter.search(
            "kotlin",
            5,
            registry,
            cfg(ddgsOptIn = true, pinnedId = "searxng", hosted = true),
        )
        assertTrue(out is WebSearchOutcome.Unavailable)
        assertEquals(WebUnavailableReason.PINNED_UNAVAILABLE, (out as WebSearchOutcome.Unavailable).reason)
        assertEquals(0, searxngHttp.calls.get())
        assertEquals(0, ddgsHttp.calls.get())

        val stubNotReady = stubProvider("searxng", probeResult = WebProbe.NotReady(WebUnavailableReason.INVALID_URL)) { _, _ ->
            error("must not execute")
        }
        val out2 = WebSearchAdapter.search(
            "kotlin",
            5,
            WebSearchRegistry(listOf(stubNotReady, DdgsSearchProvider(ddgsHttp))),
            cfg(ddgsOptIn = true, pinnedId = "searxng", hosted = true),
        )
        assertEquals(WebUnavailableReason.PINNED_UNAVAILABLE, (out2 as WebSearchOutcome.Unavailable).reason)
        assertEquals(0, ddgsHttp.calls.get())
    }

    @Test fun pinnedReadyWinsOverHostedAvailable() = runBlocking {
        val searxngHttp = FakeHttp { WebHttpResponse(200, searxngJson) }
        val registry = WebSearchRegistry(listOf(searxngService(searxngHttp)))
        val out = WebSearchAdapter.search(
            "kotlin",
            5,
            registry,
            cfg(pinnedId = "searxng", hosted = true),
        )
        assertTrue(out is WebSearchOutcome.Ok)
        assertEquals(1, searxngHttp.calls.get())
    }

    @Test fun pinnedDdgsFailureNeverFallsBackToReadySearxng() = runBlocking {
        val searxngHttp = FakeHttp { WebHttpResponse(200, searxngJson) }
        val ddgsHttp = FakeHttp { WebHttpResponse(500, "boom") }
        val registry = WebSearchRegistry(
            listOf(searxngService(searxngHttp), ddgsService(ddgsHttp)),
        )
        val out = WebSearchAdapter.search(
            "kotlin",
            5,
            registry,
            cfg(ddgsOptIn = true, pinnedId = "ddgs"),
        )
        assertTrue(out is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.SUPPLIER_ERROR, (out as WebSearchOutcome.Failed).reason)
        assertEquals(1, ddgsHttp.calls.get())
        assertEquals(0, searxngHttp.calls.get())
    }

    @Test fun pinnedIdWhitespaceTrimmedAndBlankPinTreatedAsUnpinned() = runBlocking {
        val searxngHttp = FakeHttp { WebHttpResponse(200, searxngJson) }
        val registry = WebSearchRegistry(listOf(searxngService(searxngHttp)))

        // " searxng " -> pinned local wins over hosted.
        val trimmedPin = WebSearchAdapter.search(
            "kotlin",
            5,
            registry,
            cfg(pinnedId = " searxng ", hosted = true),
        )
        assertTrue(trimmedPin is WebSearchOutcome.Ok)
        assertEquals(1, searxngHttp.calls.get())

        // "   " -> unpinned -> hosted wins -> HOSTED_NOT_SUPPORTED with 0 calls.
        val blankPinHosted = WebSearchAdapter.search(
            "kotlin",
            5,
            registry,
            cfg(pinnedId = "   ", hosted = true),
        )
        assertEquals(
            WebUnavailableReason.HOSTED_NOT_SUPPORTED,
            (blankPinHosted as WebSearchOutcome.Unavailable).reason,
        )
        assertEquals(1, searxngHttp.calls.get())
    }

    @Test fun reverseRegistryOrderStillPrefersSearxngBeforeDdgs() = runBlocking {
        val searxngHttp = FakeHttp { WebHttpResponse(200, searxngJson) }
        val ddgsHttp = FakeHttp { WebHttpResponse(200, ddgHtml) }
        val registry = WebSearchRegistry(
            listOf(ddgsService(ddgsHttp), searxngService(searxngHttp)),
        )
        val out = WebSearchAdapter.search("kotlin", 5, registry, cfg(ddgsOptIn = true))
        assertTrue(out is WebSearchOutcome.Ok)
        assertEquals(1, searxngHttp.calls.get())
        assertEquals(0, ddgsHttp.calls.get())
        assertEquals(2, (out as WebSearchOutcome.Ok).results.size)
    }

    @Test fun serviceProbeDelegationFallbackCacheHitAndSingleFlightViaAdapter() = runBlocking {
        val searxngHttp = FakeHttp { WebHttpResponse(500, "boom") }
        val ddgsHttp = FakeHttp { WebHttpResponse(200, ddgHtml) }
        val searxngSvc = WebSearchService(SearxngSearchProvider("https://search.example", searxngHttp))
        val ddgsSvc = WebSearchService(DdgsSearchProvider(ddgsHttp))
        val nested = WebSearchService(WebSearchService(SearxngSearchProvider("", searxngHttp)))

        assertEquals(WebProbe.Ready, searxngSvc.probe())
        assertEquals(WebProbe.Ready, ddgsSvc.probe())
        assertEquals(WebProbe.NotReady(WebUnavailableReason.MISSING_URL), nested.probe())

        // SearXNG 500 -> fallback DDGS Ok; 2nd call -> DDGS cache hit (0 extra DDGS calls).
        val registry = WebSearchRegistry(listOf(searxngSvc, ddgsSvc))
        val first = WebSearchAdapter.search("kotlin", 5, registry, cfg(ddgsOptIn = true)) as WebSearchOutcome.Ok
        assertFalse(first.cached)
        assertEquals(1, searxngHttp.calls.get())
        assertEquals(1, ddgsHttp.calls.get())

        val second = WebSearchAdapter.search("kotlin", 5, registry, cfg(ddgsOptIn = true)) as WebSearchOutcome.Ok
        assertTrue(second.cached)
        assertEquals(2, searxngHttp.calls.get())
        assertEquals(1, ddgsHttp.calls.get())

        // Single-flight through WebSearchAdapter when SearXNG service is leader.
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sfHttp = FakeHttp {
            entered.complete(Unit)
            release.await()
            WebHttpResponse(200, searxngJson)
        }
        val sfReg = WebSearchRegistry(listOf(searxngService(sfHttp)))
        val a = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            WebSearchAdapter.search("kotlin", 5, sfReg, cfg())
        }
        val b = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            WebSearchAdapter.search("kotlin", 5, sfReg, cfg())
        }
        withTimeout(5_000) { entered.await() }
        release.complete(Unit)
        val oa = a.await() as WebSearchOutcome.Ok
        val ob = b.await() as WebSearchOutcome.Ok
        assertEquals(1, sfHttp.calls.get())
        assertEquals(2, oa.results.size)
        assertEquals(2, ob.results.size)
    }

    @Test fun protocolNeverFallsBack_fallbackAllowedErrorsFallback_unavailableDoesNotClobberFailed() = runBlocking {
        // PROTOCOL (400 / 404) never falls back.
        for (code in listOf(400, 404)) {
            val searxngHttp = FakeHttp { WebHttpResponse(code, "bad") }
            val ddgsHttp = FakeHttp { WebHttpResponse(200, ddgHtml) }
            val reg = WebSearchRegistry(listOf(searxngService(searxngHttp), ddgsService(ddgsHttp)))
            val out = WebSearchAdapter.search("kotlin", 5, reg, cfg(ddgsOptIn = true))
            assertEquals("code=$code", WebFailureReason.PROTOCOL, (out as WebSearchOutcome.Failed).reason)
            assertEquals(1, searxngHttp.calls.get())
            assertEquals(0, ddgsHttp.calls.get())
        }

        // TIMEOUT, BAD_RESPONSE, TRANSPORT fall back to opted-in DDGS.
        for (reason in listOf(
            WebFailureReason.TIMEOUT,
            WebFailureReason.BAD_RESPONSE,
            WebFailureReason.TRANSPORT,
        )) {
            val ddgsHttp = FakeHttp { WebHttpResponse(200, ddgHtml) }
            val failingPrimary = stubProvider("searxng") { _, _ -> WebSearchOutcome.Failed(reason) }
            val reg = WebSearchRegistry(listOf(failingPrimary, ddgsService(ddgsHttp)))
            val out = WebSearchAdapter.search("kotlin", 5, reg, cfg(ddgsOptIn = true))
            assertTrue("reason=$reason", out is WebSearchOutcome.Ok)
            assertEquals(1, ddgsHttp.calls.get())
        }

        // Fallback returning Unavailable must NOT clobber primary's Failed(TRANSPORT).
        val primaryTransport = stubProvider("searxng") { _, _ ->
            WebSearchOutcome.Failed(WebFailureReason.TRANSPORT)
        }
        val fallbackUnavailable = stubProvider("ddgs") { _, _ ->
            WebSearchOutcome.Unavailable(WebUnavailableReason.NO_PROVIDER)
        }
        val reg = WebSearchRegistry(listOf(primaryTransport, fallbackUnavailable))
        val out = WebSearchAdapter.search("kotlin", 5, reg, cfg(ddgsOptIn = true))
        assertTrue(out is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.TRANSPORT, (out as WebSearchOutcome.Failed).reason)
    }

    @Test fun swallowedCancelInSearchRethrowsCeAndDoesNotFallback() = runBlocking {
        // Case 1: provider swallows cancel and returns Failed(TRANSPORT).
        val entered1 = CompletableDeferred<Unit>()
        val release1 = CompletableDeferred<Unit>()
        val ddgsCalls1 = AtomicInteger(0)
        val swallowerReturn = stubProvider("searxng") { _, _ ->
            entered1.complete(Unit)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release1.await() }
            WebSearchOutcome.Failed(WebFailureReason.TRANSPORT)
        }
        val ddgs1 = stubProvider("ddgs") { _, _ ->
            ddgsCalls1.incrementAndGet()
            WebSearchOutcome.Ok(emptyList())
        }
        val job1 = async {
            WebSearchAdapter.search("kotlin", 5, WebSearchRegistry(listOf(swallowerReturn, ddgs1)), cfg(ddgsOptIn = true))
        }
        withTimeout(5_000) { entered1.await() }
        job1.cancel()
        release1.complete(Unit)
        try {
            job1.await()
            org.junit.Assert.fail("expected CancellationException")
        } catch (_: CancellationException) {
            // expected
        }
        assertEquals(0, ddgsCalls1.get())

        // Case 2: provider swallows cancel and throws RuntimeException.
        val entered2 = CompletableDeferred<Unit>()
        val release2 = CompletableDeferred<Unit>()
        val ddgsCalls2 = AtomicInteger(0)
        val swallowerThrow = stubProvider("searxng") { _, _ ->
            entered2.complete(Unit)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release2.await() }
            throw RuntimeException("swallowed cancel")
        }
        val ddgs2 = stubProvider("ddgs") { _, _ ->
            ddgsCalls2.incrementAndGet()
            WebSearchOutcome.Ok(emptyList())
        }
        val job2 = async {
            WebSearchAdapter.search("kotlin", 5, WebSearchRegistry(listOf(swallowerThrow, ddgs2)), cfg(ddgsOptIn = true))
        }
        withTimeout(5_000) { entered2.await() }
        job2.cancel()
        release2.complete(Unit)
        try {
            job2.await()
            org.junit.Assert.fail("expected CancellationException")
        } catch (_: CancellationException) {
            // expected
        }
        assertEquals(0, ddgsCalls2.get())
    }

    @Test fun runtimeExceptionInBareProviderMapsToTransportAndFallsBack_errorRethrowsWithoutFallback() = runBlocking {
        val ddgsHttp = FakeHttp { WebHttpResponse(200, ddgHtml) }
        val throwingProvider = stubProvider("searxng") { _, _ ->
            throw IllegalStateException("unexpected bug")
        }
        val reg1 = WebSearchRegistry(listOf(throwingProvider, ddgsService(ddgsHttp)))
        val out1 = WebSearchAdapter.search("kotlin", 5, reg1, cfg(ddgsOptIn = true))
        assertTrue(out1 is WebSearchOutcome.Ok)
        assertEquals(1, ddgsHttp.calls.get())

        val ddgsCalls = AtomicInteger(0)
        val errorProvider = stubProvider("searxng") { _, _ ->
            throw AssertionError("fatal assertion")
        }
        val ddgs = stubProvider("ddgs") { _, _ ->
            ddgsCalls.incrementAndGet()
            WebSearchOutcome.Ok(emptyList())
        }
        val reg2 = WebSearchRegistry(listOf(errorProvider, ddgs))
        try {
            WebSearchAdapter.search("kotlin", 5, reg2, cfg(ddgsOptIn = true))
            org.junit.Assert.fail("expected AssertionError")
        } catch (e: AssertionError) {
            assertEquals("fatal assertion", e.message)
        }
        assertEquals(0, ddgsCalls.get())
    }

    @Test fun isReadyRuntimeExceptionFailsClosed_isReadyCancellationPropagates() = runBlocking {
        val searxngCalls = AtomicInteger(0)
        val searxng = stubProvider("searxng") { _, _ ->
            searxngCalls.incrementAndGet()
            WebSearchOutcome.Ok(emptyList())
        }
        val ddgsHttp = FakeHttp { WebHttpResponse(200, ddgHtml) }
        val reg = WebSearchRegistry(listOf(searxng, ddgsService(ddgsHttp)))
        val out = WebSearchAdapter.search(
            "kotlin",
            5,
            reg,
            cfg(ddgsOptIn = true),
            isReady = { p ->
                if (p.id == "searxng") throw IllegalStateException("probe crashed")
                true
            },
        )
        assertTrue(out is WebSearchOutcome.Ok)
        assertEquals(0, searxngCalls.get())
        assertEquals(1, ddgsHttp.calls.get())

        try {
            WebSearchAdapter.search(
                "kotlin",
                5,
                reg,
                cfg(ddgsOptIn = true),
                isReady = { throw CancellationException("probe cancelled") },
            )
            org.junit.Assert.fail("expected CancellationException")
        } catch (_: CancellationException) {
            // expected
        }
    }

    @Test fun countClampedAtAdapterBoundaryOnBareProvider() = runBlocking {
        val seenCounts = mutableListOf<Int>()
        val bare = stubProvider("searxng") { _, c ->
            seenCounts.add(c)
            WebSearchOutcome.Ok(emptyList())
        }
        val reg = WebSearchRegistry(listOf(bare))
        WebSearchAdapter.search("kotlin", 0, reg, cfg())
        WebSearchAdapter.search("kotlin", 999, reg, cfg())
        assertEquals(listOf(1, 10), seenCounts)
    }

    @Test fun searxngProbeAndSettingsProbeAgreement() = runBlocking {
        val noop = FakeHttp { WebHttpResponse(200, "{}") }
        suspend fun assertSameProbe(url: String, expected: WebProbe) {
            val providerProbe = SearxngSearchProvider(url, noop).probe()
            val settingsProbe = WebSearchSettings.probeSearxngUrl(url)
            assertEquals("url=$url provider", expected, providerProbe)
            assertEquals("url=$url settings", expected, settingsProbe)
        }

        // Blank -> MISSING_URL
        for (blank in listOf("", "   ", "\t\n", "\u00A0")) {
            assertSameProbe(blank, WebProbe.NotReady(WebUnavailableReason.MISSING_URL))
        }

        // Invalid non-blank -> INVALID_URL
        val invalidUrls = listOf(
            "ftp://search.example",
            "not-a-url",
            "https://user:pass@search.example",
            "https://@search.example",
            "https://search.example?q=1",
            "https://search.example#f",
            "\u200B",
            "https://search\u200B.example",
            "https://search.example\u00A0",
            "https://search\u00A0.example",
            "https://search.example/\u3000",
            "https://search.example/\u0000",
            "https://search.example/\u007F",
            "https://search.example/\uD800",
            "https://search.example/\uDC00",
            "https://search.example/\uDC00\uD800",
            "https://search.example/" + "a".repeat(2048),
            // IPv6 zone IDs
            "http://[fe80::1%eth0]:8080",
            "http://[fe80::1%25eth0]:8080",
            // Hostless / single-slash / empty port
            "https:/search.example",
            "https:///search.example",
            "https://",
            "http://:8080",
            "https://search.example:",
            "https://search.example:/path",
            "http://[::1]:",
            // Invalid ports
            "https://search.example:0",
            "https://search.example:65536",
            "https://search.example:-1",
            "https://search.example:abc",
            "http://[::1]:65536",
            // Backslash / authority smuggling
            "https://evil.com\\@search.example",
            "https://search.example\\path",
            // Malformed IPv6
            "http://::1",
            "http://[::1",
            "http://[::1]abc",
            // Dangerous / non-canonical addresses
            "http://0.0.0.0",
            "https://169.254.169.254",
            "http://100.100.100.200",
            "http://[fd00:ec2::254]",
            "http://[::ffff:127.0.0.1]",
            "http://[::ffff:7f00:1]",
            "http://[::127.0.0.1]",
            "http://[::169.254.169.254]",
            "http://[fe80::1]",
            "http://[::]",
            "http://0177.0.0.1",
            "http://0x7f000001",
            "http://2130706433",
            "http://127.0.0.1.",
            // Dot-segment paths
            "https://search.example/a/../b",
            "https://search.example/a/./b",
            "https://search.example/.",
            "https://search.example/..",
            "https://search.example/a/%2e%2e/b",
        )
        for (bad in invalidUrls) {
            assertSameProbe(bad, WebProbe.NotReady(WebUnavailableReason.INVALID_URL))
            org.junit.Assert.assertThrows("keyIdFor url=$bad", IllegalArgumentException::class.java) {
                WebSearchSettings.keyIdFor("searxng", bad)
            }
        }

        // Valid -> Ready (including loopback & LAN RFC1918 & encoded path & IPv6)
        for (good in listOf(
            "http://127.0.0.1:8080",
            "http://192.168.1.50:8080",
            "http://192.168.1.10:8080",
            "http://10.0.0.1:8080",
            "http://172.16.0.1:8080",
            "http://localhost:8080",
            "https://a",
            "https://search.example",
            "  https://search.example/sub/  ",
            "https://search.example/%41",
            "https://search.example/tenant%2Fprivate",
            "http://[::1]:8080",
            "http://[2001:DB8::1]:8080/Sub/",
        )) {
            assertSameProbe(good, WebProbe.Ready)
        }

        // DdgsSearchProvider probe parity with shared validation
        assertEquals(WebProbe.Ready, DdgsSearchProvider(noop).probe())
        assertEquals(WebProbe.Ready, DdgsSearchProvider(noop, endpoint = "https://custom.ddg.example/html/").probe())
        assertEquals(WebProbe.Ready, DdgsSearchProvider(noop, endpoint = "http://127.0.0.1:8080/ddg").probe())
        assertEquals(
            WebProbe.NotReady(WebUnavailableReason.MISSING_URL),
            DdgsSearchProvider(noop, endpoint = "  ").probe(),
        )
        for (badDdgs in listOf(
            "https://@duckduckgo.com",
            "https://duckduckgo.com:65536",
            "https://duckduckgo.com:0",
            "https://duckduckgo.com:",
            "ftp://duckduckgo.com/html/",
            "https://html.duckduckgo.com/html/?q=test",
        )) {
            assertEquals(
                "ddgs bad=$badDdgs",
                WebProbe.NotReady(WebUnavailableReason.INVALID_URL),
                DdgsSearchProvider(noop, endpoint = badDdgs).probe(),
            )
        }
    }

    @Test fun lazyProbingOnlyProbesSecondProviderWhenFallbackTriggered() = runBlocking {
        val searxngProbes = AtomicInteger(0)
        val ddgsProbes = AtomicInteger(0)
        var failSearxng = false
        val searxng = stubProvider(
            id = "searxng",
            onProbe = { searxngProbes.incrementAndGet() },
            onSearch = { _, _ ->
                if (failSearxng) WebSearchOutcome.Failed(WebFailureReason.TRANSPORT)
                else WebSearchOutcome.Ok(listOf(WebSearchResult("S", "https://s.example", "s")))
            },
        )
        val ddgs = stubProvider(
            id = "ddgs",
            onProbe = { ddgsProbes.incrementAndGet() },
            onSearch = { _, _ ->
                WebSearchOutcome.Ok(listOf(WebSearchResult("D", "https://d.example", "d")))
            },
        )
        val reg = WebSearchRegistry(listOf(searxng, ddgs))

        // Primary succeeds -> 2nd provider is NOT probed.
        val ok = WebSearchAdapter.search("kotlin", 5, reg, cfg(ddgsOptIn = true))
        assertTrue(ok is WebSearchOutcome.Ok)
        assertEquals(1, searxngProbes.get())
        assertEquals(0, ddgsProbes.get())

        // Primary fails TRANSPORT -> 2nd provider IS lazily probed and runs.
        failSearxng = true
        val fallback = WebSearchAdapter.search("kotlin", 5, reg, cfg(ddgsOptIn = true))
        assertTrue(fallback is WebSearchOutcome.Ok)
        assertEquals(2, searxngProbes.get())
        assertEquals(1, ddgsProbes.get())

        // Suspending isReady works directly.
        val suspendedOut = WebSearchAdapter.search(
            "kotlin",
            5,
            reg,
            cfg(ddgsOptIn = true),
            isReady = { p ->
                delay(1)
                p.id == "ddgs"
            },
        ) as WebSearchOutcome.Ok
        assertEquals("https://d.example", suspendedOut.results.single().url)
    }

    @Test fun keyIdForHardeningAndEdgeCases() {
        assertEquals(
            "web.searxng@https://search.example:8443/SubPath",
            WebSearchSettings.keyIdFor("searxng", "HTTPS://SEARCH.EXAMPLE:8443/SubPath/"),
        )
        assertEquals(
            "web.searxng@https://search.example:443",
            WebSearchSettings.keyIdFor("searxng", "https://search.example:443/"),
        )
        assertEquals(
            "web.searxng@https://search.example",
            WebSearchSettings.keyIdFor("searxng", "https://search.example///"),
        )
        assertEquals(
            "web.searxng@http://[2001:db8::1]:8080/Sub",
            WebSearchSettings.keyIdFor("searxng", "http://[2001:DB8::1]:8080/Sub/"),
        )

        // Scheme, explicit port, path case, and percent-encoded path distinctions
        assertTrue(
            WebSearchSettings.keyIdFor("searxng", "http://search.example") !=
                WebSearchSettings.keyIdFor("searxng", "https://search.example"),
        )
        assertTrue(
            WebSearchSettings.keyIdFor("searxng", "https://search.example:443") !=
                WebSearchSettings.keyIdFor("searxng", "https://search.example"),
        )
        assertTrue(
            WebSearchSettings.keyIdFor("searxng", "https://search.example/Sub") !=
                WebSearchSettings.keyIdFor("searxng", "https://search.example/sub"),
        )
        assertTrue(
            WebSearchSettings.keyIdFor("searxng", "https://search.example/tenant%2Fprivate") !=
                WebSearchSettings.keyIdFor("searxng", "https://search.example/tenant/private"),
        )
        assertTrue(
            WebSearchSettings.keyIdFor("searxng", "https://search.example/%41") !=
                WebSearchSettings.keyIdFor("searxng", "https://search.example/A"),
        )

        // Valid surrogate pair passes while lone/reversed surrogates fail
        assertEquals("web.searxng\uD83D\uDE00", WebSearchSettings.namespaceFor("searxng\uD83D\uDE00"))

        for (badPid in listOf(
            "",
            "   ",
            " searxng",
            "searxng ",
            "a\u00A0b",
            "a@b",
            "a/b",
            "a b",
            "a\u0000b",
            "a\u200Bb",
            "a\uD800b",
            "a\uDC00b",
            "a\uDC00\uD800b",
        )) {
            org.junit.Assert.assertThrows("namespaceFor pid=$badPid", IllegalArgumentException::class.java) {
                WebSearchSettings.namespaceFor(badPid)
            }
            org.junit.Assert.assertThrows("keyIdFor pid=$badPid", IllegalArgumentException::class.java) {
                WebSearchSettings.keyIdFor(badPid, "https://search.example")
            }
        }
        for (badOrigin in listOf(
            "",
            "   ",
            "ftp://search.example",
            "not-a-url",
            "https://user:pass@search.example",
            "https://@search.example",
            "https://search.example?q=1",
            "https://search.example#f",
            "https://search.example/\u200B",
            "https://search.example\u00A0",
            "https://search.example/\uD800",
            "http://[fe80::1%eth0]:8080",
            "https://search.example/a/../b",
        )) {
            org.junit.Assert.assertThrows("origin=$badOrigin", IllegalArgumentException::class.java) {
                WebSearchSettings.keyIdFor("searxng", badOrigin)
            }
        }
    }

    @Test fun searchSuspendExhaustiveOutcomesPassthroughAndCancellation() = runBlocking {
        // truncated + cached passthrough across all 4 (truncated, cached) combinations
        for ((trunc, cch) in listOf(false to false, true to false, false to true, true to true)) {
            val okStub = stubProvider("searxng") { _, _ ->
                WebSearchOutcome.Ok(
                    results = listOf(WebSearchResult("T", "https://example.com", "S")),
                    truncated = trunc,
                    cached = cch,
                )
            }
            val okOut = WebSearchLocal.searchSuspend(
                query = "kotlin",
                count = 5,
                registry = WebSearchRegistry(listOf(okStub)),
                masterEnabled = true,
                consentConfirmed = true,
            ) as WebSearchLocal.SearchOutcome.Ok
            assertEquals("truncated=$trunc", trunc, okOut.truncated)
            assertEquals("cached=$cch", cch, okOut.cached)
            assertEquals(1, okOut.results.size)
        }

        // Explicit ddgsOptIn = true + custom isReady through WebSearchLocal.searchSuspend
        val searxngCalls = AtomicInteger(0)
        val ddgsCalls = AtomicInteger(0)
        val primary = stubProvider("searxng") { _, _ ->
            searxngCalls.incrementAndGet()
            WebSearchOutcome.Ok(listOf(WebSearchResult("S", "https://s.example", "s")))
        }
        val ddgs = stubProvider("ddgs") { _, _ ->
            ddgsCalls.incrementAndGet()
            WebSearchOutcome.Ok(listOf(WebSearchResult("D", "https://d.example", "d")))
        }
        val localOptIn = WebSearchLocal.searchSuspend(
            query = "kotlin",
            count = 5,
            registry = WebSearchRegistry(listOf(primary, ddgs)),
            masterEnabled = true,
            ddgsOptIn = true,
            consentConfirmed = true,
            isReady = { it.id == "ddgs" },
        ) as WebSearchLocal.SearchOutcome.Ok
        assertEquals(0, searxngCalls.get())
        assertEquals(1, ddgsCalls.get())
        assertEquals("https://d.example", localOptIn.results.single().url)

        val okStub = stubProvider("searxng") { _, _ ->
            WebSearchOutcome.Ok(listOf(WebSearchResult("T", "https://example.com", "S")))
        }

        // All 8 WebUnavailableReason mappings (including non-blank message)
        val u1 = WebSearchLocal.searchSuspend(
            query = "kotlin",
            registry = WebSearchRegistry(listOf(okStub)),
            masterEnabled = false,
            consentConfirmed = true,
        ) as WebSearchLocal.SearchOutcome.Unavailable
        assertEquals(DenyReason.USER_DISABLED, u1.reason)
        assertEquals("PROVIDER_DISABLED", u1.detail)
        assertTrue(u1.message.isNotBlank())

        val u2 = WebSearchLocal.searchSuspend(
            query = "   ",
            registry = WebSearchRegistry(listOf(okStub)),
            masterEnabled = true,
            consentConfirmed = true,
        ) as WebSearchLocal.SearchOutcome.Unavailable
        assertEquals(DenyReason.NO_PRIVILEGE, u2.reason)
        assertEquals("EMPTY_QUERY", u2.detail)
        assertTrue(u2.message.isNotBlank())

        val u3 = WebSearchLocal.searchSuspend(
            query = "kotlin",
            registry = WebSearchRegistry(listOf(okStub)),
            masterEnabled = true,
            hostedAvailable = true,
            consentConfirmed = true,
        ) as WebSearchLocal.SearchOutcome.Unavailable
        assertEquals(DenyReason.NO_PRIVILEGE, u3.reason)
        assertEquals("HOSTED_NOT_SUPPORTED", u3.detail)
        assertTrue(u3.message.isNotBlank())

        val u4 = WebSearchLocal.searchSuspend(
            query = "kotlin",
            registry = WebSearchRegistry(listOf(okStub)),
            masterEnabled = true,
            pinnedId = "missing",
            consentConfirmed = true,
        ) as WebSearchLocal.SearchOutcome.Unavailable
        assertEquals(DenyReason.NO_PRIVILEGE, u4.reason)
        assertEquals("PINNED_UNAVAILABLE", u4.detail)
        assertTrue(u4.message.isNotBlank())

        val u5 = WebSearchLocal.searchSuspend(
            query = "kotlin",
            registry = WebSearchRegistry(emptyList()),
            masterEnabled = true,
            consentConfirmed = true,
        ) as WebSearchLocal.SearchOutcome.Unavailable
        assertEquals(DenyReason.NO_PRIVILEGE, u5.reason)
        assertEquals("NO_PROVIDER", u5.detail)
        assertTrue(u5.message.isNotBlank())

        for (directReason in listOf(
            WebUnavailableReason.MISSING_URL,
            WebUnavailableReason.INVALID_URL,
            WebUnavailableReason.MISSING_KEY,
        )) {
            val uStub = stubProvider("searxng") { _, _ -> WebSearchOutcome.Unavailable(directReason) }
            val uOut = WebSearchLocal.searchSuspend(
                query = "kotlin",
                registry = WebSearchRegistry(listOf(uStub)),
                masterEnabled = true,
                consentConfirmed = true,
            ) as WebSearchLocal.SearchOutcome.Unavailable
            assertEquals(DenyReason.NO_PRIVILEGE, uOut.reason)
            assertEquals(directReason.detail, uOut.detail)
            assertTrue(uOut.message.isNotBlank())
            assertTrue(uOut.message.contains(directReason.detail))
        }

        // All 6 WebFailureReason mappings
        for (failReason in WebFailureReason.values()) {
            val fStub = stubProvider("searxng") { _, _ -> WebSearchOutcome.Failed(failReason) }
            val fOut = WebSearchLocal.searchSuspend(
                query = "kotlin",
                registry = WebSearchRegistry(listOf(fStub)),
                masterEnabled = true,
                consentConfirmed = true,
            ) as WebSearchLocal.SearchOutcome.Failed
            assertEquals(failReason.detail, fOut.detail)
            assertTrue(fOut.message.contains(failReason.detail))
        }

        // CE propagation through WebSearchLocal.searchSuspend
        val cancelStub = stubProvider("searxng") { _, _ ->
            throw CancellationException("user cancel")
        }
        try {
            WebSearchLocal.searchSuspend(
                query = "kotlin",
                registry = WebSearchRegistry(listOf(cancelStub)),
                masterEnabled = true,
                consentConfirmed = true,
            )
            org.junit.Assert.fail("expected CancellationException")
        } catch (_: CancellationException) {
            // expected
        }
    }

    @Test fun threeProviderFallbackHaltsWhenFallbackReturnsUnavailable() = runBlocking {
        val ddgsProbes = AtomicInteger(0)
        val ddgsCalls = AtomicInteger(0)
        val p0 = stubProvider("searxng") { _, _ -> WebSearchOutcome.Failed(WebFailureReason.TRANSPORT) }
        val p1 = stubProvider("custom") { _, _ -> WebSearchOutcome.Unavailable(WebUnavailableReason.MISSING_KEY) }
        val p2 = stubProvider(
            id = "ddgs",
            onProbe = { ddgsProbes.incrementAndGet() },
            onSearch = { _, _ ->
                ddgsCalls.incrementAndGet()
                WebSearchOutcome.Ok(emptyList())
            },
        )
        val reg = WebSearchRegistry(listOf(p0, p1, p2))
        val out = WebSearchAdapter.search("kotlin", 5, reg, cfg(ddgsOptIn = true))
        assertTrue(out is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.TRANSPORT, (out as WebSearchOutcome.Failed).reason)
        assertEquals(0, ddgsProbes.get())
        assertEquals(0, ddgsCalls.get())
    }

    @Test fun fallbackReadinessSkipAndAllUnreadyFallback() = runBlocking {
        val skippedCalls = AtomicInteger(0)
        val p0 = stubProvider("searxng") { _, _ -> WebSearchOutcome.Failed(WebFailureReason.TRANSPORT) }
        val p1NotReady = stubProvider("custom", probeResult = WebProbe.NotReady(WebUnavailableReason.MISSING_KEY)) { _, _ ->
            skippedCalls.incrementAndGet()
            WebSearchOutcome.Ok(emptyList())
        }
        val p2Ready = stubProvider("ddgs", probeResult = WebProbe.Ready) { _, _ ->
            WebSearchOutcome.Ok(listOf(WebSearchResult("D", "https://d.example", "d")))
        }

        // Candidate 2 NotReady is skipped (0 search calls), succeeds via candidate 3.
        val ok = WebSearchAdapter.search(
            "kotlin",
            5,
            WebSearchRegistry(listOf(p0, p1NotReady, p2Ready)),
            cfg(ddgsOptIn = true),
        ) as WebSearchOutcome.Ok
        assertEquals(0, skippedCalls.get())
        assertEquals("https://d.example", ok.results.single().url)

        // Variant with no candidate 3 -> returns primary's Failed(TRANSPORT).
        val failed = WebSearchAdapter.search(
            "kotlin",
            5,
            WebSearchRegistry(listOf(p0, p1NotReady)),
            cfg(ddgsOptIn = true),
        )
        assertTrue(failed is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.TRANSPORT, (failed as WebSearchOutcome.Failed).reason)
        assertEquals(0, skippedCalls.get())
    }

    @Test fun swallowedCancelInIsReadyRethrowsCeWithoutCallingSearch() = runBlocking {
        // Case 1: isReady waits out cancel and returns true -> CE rethrown, 0 search calls.
        val entered1 = CompletableDeferred<Unit>()
        val release1 = CompletableDeferred<Unit>()
        val searchCalls1 = AtomicInteger(0)
        val reg1 = WebSearchRegistry(
            listOf(
                stubProvider("searxng") { _, _ ->
                    searchCalls1.incrementAndGet()
                    WebSearchOutcome.Ok(emptyList())
                },
                stubProvider("ddgs") { _, _ ->
                    searchCalls1.incrementAndGet()
                    WebSearchOutcome.Ok(emptyList())
                },
            ),
        )
        val job1 = async {
            WebSearchAdapter.search(
                "kotlin",
                5,
                reg1,
                cfg(ddgsOptIn = true),
                isReady = {
                    entered1.complete(Unit)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release1.await() }
                    true
                },
            )
        }
        withTimeout(5_000) { entered1.await() }
        job1.cancel()
        release1.complete(Unit)
        try {
            job1.await()
            org.junit.Assert.fail("expected CancellationException")
        } catch (_: CancellationException) {
            // expected
        }
        assertEquals(0, searchCalls1.get())

        // Case 2: isReady waits out cancel and throws RuntimeException -> CE rethrown, 0 search calls.
        val entered2 = CompletableDeferred<Unit>()
        val release2 = CompletableDeferred<Unit>()
        val searchCalls2 = AtomicInteger(0)
        val reg2 = WebSearchRegistry(
            listOf(
                stubProvider("searxng") { _, _ ->
                    searchCalls2.incrementAndGet()
                    WebSearchOutcome.Ok(emptyList())
                },
                stubProvider("ddgs") { _, _ ->
                    searchCalls2.incrementAndGet()
                    WebSearchOutcome.Ok(emptyList())
                },
            ),
        )
        val job2 = async {
            WebSearchAdapter.search(
                "kotlin",
                5,
                reg2,
                cfg(ddgsOptIn = true),
                isReady = {
                    entered2.complete(Unit)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release2.await() }
                    throw RuntimeException("swallowed cancel in isReady")
                },
            )
        }
        withTimeout(5_000) { entered2.await() }
        job2.cancel()
        release2.complete(Unit)
        try {
            job2.await()
            org.junit.Assert.fail("expected CancellationException")
        } catch (_: CancellationException) {
            // expected
        }
        assertEquals(0, searchCalls2.get())
    }

    @Test fun singleThrowingProviderMapsToTransportDirectly() = runBlocking {
        val throwing = stubProvider("searxng") { _, _ ->
            throw IllegalStateException("single provider boom")
        }
        val out = WebSearchAdapter.search("kotlin", 5, WebSearchRegistry(listOf(throwing)), cfg())
        assertTrue(out is WebSearchOutcome.Failed)
        assertEquals(WebFailureReason.TRANSPORT, (out as WebSearchOutcome.Failed).reason)
    }

    @Test fun adapterDirectEdgeCasesEmptyRegistryPinnedMissingHostedAllNotReady() = runBlocking {
        // emptyList with hosted = false -> NO_PROVIDER
        val emptyUnpinned = WebSearchAdapter.search(
            "kotlin",
            5,
            WebSearchRegistry(emptyList()),
            cfg(hosted = false),
        )
        assertEquals(WebUnavailableReason.NO_PROVIDER, (emptyUnpinned as WebSearchOutcome.Unavailable).reason)

        // emptyList with hosted = true -> HOSTED_NOT_SUPPORTED
        val emptyHosted = WebSearchAdapter.search(
            "kotlin",
            5,
            WebSearchRegistry(emptyList()),
            cfg(hosted = true),
        )
        assertEquals(WebUnavailableReason.HOSTED_NOT_SUPPORTED, (emptyHosted as WebSearchOutcome.Unavailable).reason)

        // pinnedId = "missing" with hosted = true and ready providers -> PINNED_UNAVAILABLE (0 probes / 0 calls)
        val probes = AtomicInteger(0)
        val calls = AtomicInteger(0)
        val ready = stubProvider(
            id = "searxng",
            onProbe = { probes.incrementAndGet() },
            onSearch = { _, _ ->
                calls.incrementAndGet()
                WebSearchOutcome.Ok(emptyList())
            },
        )
        val pinnedMissing = WebSearchAdapter.search(
            "kotlin",
            5,
            WebSearchRegistry(listOf(ready)),
            cfg(pinnedId = "missing", hosted = true),
        )
        assertEquals(WebUnavailableReason.PINNED_UNAVAILABLE, (pinnedMissing as WebSearchOutcome.Unavailable).reason)
        assertEquals(0, probes.get())
        assertEquals(0, calls.get())

        // All-NotReady registry -> NO_PROVIDER (0 search calls)
        val notReadyCalls = AtomicInteger(0)
        val notReadyA = stubProvider("searxng", probeResult = WebProbe.NotReady(WebUnavailableReason.MISSING_URL)) { _, _ ->
            notReadyCalls.incrementAndGet()
            WebSearchOutcome.Ok(emptyList())
        }
        val notReadyB = stubProvider("ddgs", probeResult = WebProbe.NotReady(WebUnavailableReason.INVALID_URL)) { _, _ ->
            notReadyCalls.incrementAndGet()
            WebSearchOutcome.Ok(emptyList())
        }
        val allNotReady = WebSearchAdapter.search(
            "kotlin",
            5,
            WebSearchRegistry(listOf(notReadyA, notReadyB)),
            cfg(ddgsOptIn = true),
        )
        assertEquals(WebUnavailableReason.NO_PROVIDER, (allNotReady as WebSearchOutcome.Unavailable).reason)
        assertEquals(0, notReadyCalls.get())
    }
}
