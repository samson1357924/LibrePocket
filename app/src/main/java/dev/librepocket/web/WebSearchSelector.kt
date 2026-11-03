package dev.librepocket.web

/**
 * D1 路由決議（#134 D5：hosted 優先；pinned 本地與 hosted 互斥）。
 *
 * - pinned 本地 → 只走該 provider；缺失／未就緒回
 *   `Unavailable(PINNED_UNAVAILABLE)`，不靜默換、不升 hosted；
 * - 未 pin 且 endpoint 支援 hosted → [WebRoute.Hosted]（transcript 以
 *   [HOSTED_TOOL_NAME] 標記，不與本地並用）；
 * - 未 pin 且無 hosted → 註冊順序首個就緒 provider；
 * - 回傳單一路徑，永不同時送兩者。
 */
sealed interface WebRoute {
    data object Hosted : WebRoute
    data class Local(val providerId: String) : WebRoute
    data class Unavailable(val reason: WebUnavailableReason) : WebRoute {
        val detail: String get() = reason.detail
    }
}

object WebSearchSelector {
    /**
     * Hosted 轉錄名（`provider/ServerTools.kt` `ServerToolTranscript` 的鏡像，
     * 避免 web 模組硬依賴 provider；單測斷言兩者永不脫鉤）。
     */
    const val HOSTED_TOOL_NAME = "web_search"

    /**
     * 純函數選路。
     *
     * @param pinnedId 使用者 pin 的本地 provider（null／blank＝未 pin）。
     * @param hostedAvailable 當前 chat endpoint 是否支援 server-side web search。
     * @param isReady provider 就緒探測（D1 預設 true；D2/D3 接入 probe／開關）。
     */
    fun select(
        pinnedId: String?,
        hostedAvailable: Boolean,
        registry: WebSearchRegistry,
        isReady: (WebSearchProvider) -> Boolean = { true },
    ): WebRoute {
        val pinned = pinnedId?.takeIf { it.isNotBlank() }
        if (pinned != null) {
            val p = registry.get(pinned)
            return if (p != null && isReady(p)) WebRoute.Local(pinned)
            else WebRoute.Unavailable(WebUnavailableReason.PINNED_UNAVAILABLE)
        }
        if (hostedAvailable) return WebRoute.Hosted
        val first = registry.providers().firstOrNull(isReady)
        return if (first != null) WebRoute.Local(first.id)
        else WebRoute.Unavailable(WebUnavailableReason.NO_PROVIDER)
    }

    /**
     * 失敗後是否允許換下一個 provider（僅非 pinned＋`fallbackAllowed`）。
     * [WebSearchOutcome.Unavailable] 一律 false。
     */
    fun mayFallback(pinnedId: String?, outcome: WebSearchOutcome): Boolean {
        if (!pinnedId.isNullOrBlank()) return false
        return outcome is WebSearchOutcome.Failed && outcome.fallbackAllowed
    }
}
