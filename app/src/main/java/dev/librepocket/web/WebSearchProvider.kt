package dev.librepocket.web

/** 本地 provider 能力（key／URL 缺失 → typed `Unavailable`，見 D3 設定 UX；keyed 實作待 D5）。 */
enum class WebCapability {
    REQUIRES_KEY,
    REQUIRES_URL,
}

/** `probe()` 結果（D1 只定契約；SearXNG／DDGS 實作與超時待 D2）。 */
sealed interface WebProbe {
    data object Ready : WebProbe
    data class NotReady(val reason: WebUnavailableReason) : WebProbe
}

/**
 * D1 本地搜尋 provider 介面（#138 `WebProvider` 子集；`extract()` 待 D4）。
 *
 * 全部 `suspend`；D2 實作須用 cancellable 呼叫並在取消時關閉底層
 * I/O（取消不 fallback、不寫 cache，見 #138 Scope）。
 */
interface WebSearchProvider {
    /** Registry 內唯一（重複 id 在 [WebSearchRegistry] 建構時 fail-fast）。 */
    val id: String

    val capabilities: Set<WebCapability>

    /** 可用性探測（缺 key／缺 URL 回 [WebProbe.NotReady]，不拋異常）。 */
    suspend fun probe(): WebProbe

    /** 搜尋（空 query 回 `Unavailable(EMPTY_QUERY)`，不發任何請求）。 */
    suspend fun search(query: String, count: Int): WebSearchOutcome
}
