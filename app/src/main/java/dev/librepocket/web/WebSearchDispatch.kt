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
 * 就緒探測（[isReady]）為 **required** suspend 參數（`probe()` 為 suspend）；
 * production 必須傳入真實就緒判斷，例如 `{ p -> p.probe() is WebProbe.Ready }`。
 * `probe()` 僅為廉價的 hasKey 查詢；execution-time 的 key 狀態仍可能漂移（TOCTOU）——
 * consent／opt-in 閘門歸 D3，本層不管。
 *
 * [runLocal] 收到 `(provider, query, count)`，query／count 由本層原樣下傳，
 * 無隱藏 closure 漂移；[runHosted] 不帶參數（hosted 內容由 endpoint 決定）。
 *
 * [hostedMarker] 為 allowlist（Set<String>，非 enum class）：僅含六個已知狀態字，其他字串
 * 直接拋 [IllegalArgumentException]，異常原樣向上傳播給呼叫方
 * （D3 必須自行捕捉／處理；本層不捕捉）。
 *
 * Throw 契約：本層不捕捉異常（runner 的 [kotlinx.coroutines.CancellationException]
 * 原樣透傳；provider 的值契約由 [WebSearchService] 中央保證）。
 * `isReady` 拋出的例外原樣傳播（fail-loud）；這與 D3 `WebSearchAdapter.safeReady` 將例外吞為 false（靜默跳過候選）刻意不同——routing 層 fail-loud 是本層契約，兩者不得互相複製貼上。
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
     * allowlist for hosted 轉錄狀態字：僅六個已知狀態字——
     * production 程式碼於別處實際發出的狀態字（`started`／`searching`／`completed`／`update`，
     * 見 `ResponsesProvider.webSearchStateOf` 對廠商事件後綴的收斂），
     * 加上 `failed`／`cancelled`（錯誤路徑）。
     */
    private val HOSTED_STATES: Set<String> = setOf("started", "searching", "completed", "update", "failed", "cancelled")

    /**
     * Hosted 轉錄標記（與 `provider/ServerTools.kt`
     * `ServerToolTranscript.webSearchMarker(state)` 同形，避免 web 模組硬依賴
     * provider 模組；兩者一致性由 `hostedMarkerMirrorsServerTools` 測試釘住。
     * `state` 為呼叫方傳入的狀態字（如 `completed`），不攜帶使用者原文。
     *
     * 構造保證：[state] 必須屬於 [HOSTED_STATES]——僅此六個已知狀態字的
     * 字串 allowlist（`started`／`searching`／`completed`／`update` 為 production
     * 程式碼於別處實際發出的狀態字，加上 `failed`／`cancelled` 供錯誤路徑）；
     * 任何其他字串（例如 server 送來的 garbage）皆拋 [IllegalArgumentException]——
     * 異常原樣傳播給呼叫方（D3 須自行捕捉／處理，本層不捕捉）。
     * 本層不嘗試「清理」未知狀態字，allowlist 就是允許列表本身。
     *
     * 注意 `ServerToolTranscript.webSearchMarker` 本身不做驗證——web 側刻意更嚴格；
     * 兩者的 mirror 一致性由 `hostedMarkerMirrorsServerTools` 測試釘住
     * （mirror 校驗的狀態字是 allowlist 的子集）。
     */
    fun hostedMarker(state: String): String {
        require(state in HOSTED_STATES) { "hostedMarker state must be one of $HOSTED_STATES" }
        return "\n[SERVER_TOOL:${WebSearchSelector.HOSTED_TOOL_NAME} $state]\n"
    }

    /**
     * 單一路徑執行。
     *
     * @param isReady 就緒探測（suspend；`probe()` 為 suspend）。production 傳入
     *   `{ p -> p.probe() is WebProbe.Ready }`；無預設值——測試也必須明示，
     *   避免靜默的 `{ true }` 被複製進 production。
     * @param runHosted hosted 執行體（僅路由為 [WebRoute.Hosted] 時呼叫一次，
     *   回傳轉錄狀態字）；本地 `cached` 語義不適用 hosted。
     * @param runLocal 本地執行體（僅路由為 [WebRoute.Local] 時呼叫一次；
     *   呼叫方應傳入經 [WebSearchService] 包裝的 provider 以得 cache＋single-flight）。
     * @param query 會原樣傳入 runLocal（blank 已於本層短路為 `Unavailable(EMPTY_QUERY)`）。
     * @param count 為呼叫方上限（provider 內部再做預算收斂）。
     *
     * 實作備註：[WebSearchSelector.select] 為 non-suspend 純函數，此處先以與 select
     * 相同的短路順序預探測（pinned → 僅該 provider；未 pin 且無 hosted → 依註冊序至
     * 首個就緒為止），再將 suspend isReady 橋接為同步查詢，保持 select 行為逐位一致。
     */
    suspend fun search(
        pinnedId: String?,
        hostedAvailable: Boolean,
        registry: WebSearchRegistry,
        query: String,
        count: Int,
        isReady: suspend (WebSearchProvider) -> Boolean,
        runHosted: suspend () -> String,
        runLocal: suspend (WebSearchProvider, query: String, count: Int) -> WebSearchOutcome,
    ): WebDispatchOutcome {
        if (query.isBlank()) return WebDispatchOutcome.Unavailable(WebUnavailableReason.EMPTY_QUERY)
        // select 為 non-suspend；先以相同短路順序預探測，再橋接為同步 readyCache 查詢。
        val readyCache = HashMap<String, Boolean>()
        val pin = pinnedId?.trim()?.takeIf { it.isNotEmpty() }
        when {
            pin != null -> registry.get(pin)?.let { readyCache[it.id] = isReady(it) }
            hostedAvailable -> Unit // Hosted 路徑 select 不探測任何 provider
            else -> for (p in registry.providers()) {
                val ready = isReady(p)
                readyCache[p.id] = ready
                if (ready) break // 與 firstOrNull 短路一致
            }
        }
        return when (
            val route = WebSearchSelector.select(pinnedId, hostedAvailable, registry) { p ->
                // unreachable by construction: select 只會查詢本次已預探測的 provider；保留 fail-closed。
                readyCache[p.id] ?: false
            }
        ) {
            is WebRoute.Hosted ->
                WebDispatchOutcome.Hosted(hostedMarker(runHosted()))
            is WebRoute.Local -> {
                val p = registry.get(route.providerId)
                    ?: return WebDispatchOutcome.Unavailable(WebUnavailableReason.NO_PROVIDER)
                WebDispatchOutcome.Local(p.id, runLocal(p, query, count))
            }
            is WebRoute.Unavailable ->
                WebDispatchOutcome.Unavailable(route.reason)
        }
    }
}
