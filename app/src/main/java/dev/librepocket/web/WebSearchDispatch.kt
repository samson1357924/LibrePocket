package dev.librepocket.web

/**
 * D5 hosted 優先接線（#138 D5；純 JVM，不接線）。
 *
 * 沿 D1 [WebSearchSelector] 語義的 provider 側執行層（單一路徑保證）：
 * - pinned 本地 → 只跑該 provider（[runHosted] 永不呼叫，不送 hosted）；
 * - 未 pin 且 endpoint 支援 hosted → 只跑 hosted（[runLocal] 永不呼叫，不送本地），
 *   回傳的 [WebDispatchOutcome.Hosted.transcriptMarker] 供轉錄層寫入，
 *   與服務端透傳標記同形（見 [hostedMarker]）；
 * - 未 pin 且無 hosted → 首個就緒本地 provider；
 * - [WebRoute.Unavailable]（pinned 缺失／未就緒、無候選）→ 兩 runner 皆不呼叫。
 *
 * 互斥以構造保證：單次 [search] 至多呼叫其中一個 runner（見 `…Exclusive` 測試）。
 * 多 provider fallback 迴圈（`Failed(fallbackAllowed)` 換下一個）歸 D3 adapter
 *（#165，含 consent／opt-in 閘），本層只做單一路徑執行，不做迴圈。
 *
 * Throw 契約：本層不捕捉異常（runner 的 [kotlinx.coroutines.CancellationException]
 * 原樣透傳；provider 的值契約由 [WebSearchService] 中央保證）。
 * D3 UI wiring 留待：本檔不註冊 ToolDef、不翻 `executionReady`。
 */
sealed interface WebDispatchOutcome {
    /** 只跑 hosted（本地零請求）；[transcriptMarker] 寫入轉錄以替代本地 tool call。 */
    data class Hosted(val transcriptMarker: String) : WebDispatchOutcome

    /** 只跑本地（hosted 零請求）；[outcome] 為所選 provider 的原值回傳。 */
    data class Local(val providerId: String, val outcome: WebSearchOutcome) : WebDispatchOutcome

    /** 未執行任何路徑（兩 runner 皆未呼叫）。 */
    data class Unavailable(val reason: WebUnavailableReason) : WebDispatchOutcome {
        val detail: String get() = reason.detail
    }
}

object WebSearchDispatch {
    /**
     * Hosted 轉錄標記（與 `provider/ServerTools.kt`
     * `ServerToolTranscript.webSearchMarker(state)` 同形，避免 web 模組硬依賴
     * provider 模組；兩者一致性由 `hostedMarkerMirrorsServerTools` 測試釘住。
     * `state` 為呼叫方傳入的狀態字（如 `completed`），不攜帶使用者原文。
     */
    fun hostedMarker(state: String): String =
        "\n[SERVER_TOOL:${WebSearchSelector.HOSTED_TOOL_NAME} $state]\n"

    /**
     * 單一路徑執行。
     *
     * @param isReady 就緒探測（production 傳入 `probe()` 驅動；預設 `{ true }`
     *   僅為簽名相容的測試便利，沿 [WebSearchSelector.select] 姿態）。
     * @param runHosted hosted 執行體（僅路由為 [WebRoute.Hosted] 時呼叫一次，
     *   回傳轉錄狀態字）；本地 `cached` 語義不適用 hosted。
     * @param runLocal 本地執行體（僅路由為 [WebRoute.Local] 時呼叫一次；
     *   呼叫方應傳入經 [WebSearchService] 包裝的 provider 以得 cache＋single-flight）。
     */
    suspend fun search(
        pinnedId: String?,
        hostedAvailable: Boolean,
        registry: WebSearchRegistry,
        query: String,
        count: Int,
        isReady: (WebSearchProvider) -> Boolean = { true },
        runHosted: suspend () -> String,
        runLocal: suspend (WebSearchProvider) -> WebSearchOutcome,
    ): WebDispatchOutcome {
        if (query.isBlank()) return WebDispatchOutcome.Unavailable(WebUnavailableReason.EMPTY_QUERY)
        return when (val route = WebSearchSelector.select(pinnedId, hostedAvailable, registry, isReady)) {
            is WebRoute.Hosted ->
                WebDispatchOutcome.Hosted(hostedMarker(runHosted()))
            is WebRoute.Local -> {
                val p = registry.get(route.providerId)
                    ?: return WebDispatchOutcome.Unavailable(WebUnavailableReason.NO_PROVIDER)
                WebDispatchOutcome.Local(p.id, runLocal(p))
            }
            is WebRoute.Unavailable ->
                WebDispatchOutcome.Unavailable(route.reason)
        }
    }
}
