package dev.librepocket.tool

import dev.librepocket.web.WebSearchAdapter
import dev.librepocket.web.WebSearchOutcome
import dev.librepocket.web.WebSearchProvider
import dev.librepocket.web.WebSearchRegistry
import dev.librepocket.web.WebUnavailableReason

/**
 * S1-B `web.search` 本地版 (READ)：`{query, count}`。
 *
 * 與 `provider/ServerTools.kt` 服務端透傳的區分（刻意不同名）：
 * - 服務端：hosted 工具名為 `web_search`（底線，見
 *   `ServerToolTranscript.WEB_SEARCH_NAME`），由供應商遠端執行，
 *   本地不產生 tool call 事件；
 * - 本地版：工具名為 `web.search`（點分隔），由本地可配的 [Supplier]
 *   執行，產生本地 tool call。
 *
 * 開關 `websearch` 預設 false（未配置供應商或開關關閉時為 UNAVAILABLE）。
 * 純 JVM（無 Android API），可在單元測試直接執行。
 *
 * D3：新增 [searchSuspend] 經 [WebSearchAdapter] 接新 provider 層
 *（SearXNG 預設 fallback、DDGS 需 opt-in；走呼叫方傳入的
 * `WebSearchService` cache＋single-flight）。舊同步 [Supplier] API 保留
 *（相容測試／假供應商），production 新路徑一律走 suspend 版。
 * `executionReady` 維持 false（SCAFFOLD，不可執行，見 ToolRegistry）。
 */
object WebSearchLocal {

    const val TOOL_NAME = "web.search"
    const val SWITCH = "websearch"
    const val SWITCH_DEFAULT = false

    /**
     * 服務端透傳名（`ServerToolTranscript.WEB_SEARCH_NAME` 的鏡像，
     * 避免硬依賴 provider 模組；單測斷言兩者永不相等）。
     */
    const val SERVER_SIDE_NAME = "web_search"

    const val DEFAULT_COUNT = 5
    const val MAX_COUNT = 10

    const val FALLBACK_HINT = "search manually in a browser"

    data class Result(val title: String, val url: String, val snippet: String)

    /** 可配供應商（預設無；由上層裝配搜尋後端後注入）。 */
    fun interface Supplier {
        fun search(query: String, count: Int): List<Result>
    }

    sealed interface SearchOutcome {
        data class Ok(
            val results: List<Result>,
            val truncated: Boolean = false,
            val cached: Boolean = false,
        ) : SearchOutcome
        data class Unavailable(val reason: DenyReason, val detail: String, val message: String) : SearchOutcome
        data class Failed(val detail: String, val message: String) : SearchOutcome
    }

    fun countOf(raw: Int?): Int = (raw ?: DEFAULT_COUNT).coerceIn(1, MAX_COUNT)

    fun search(query: String, count: Int = DEFAULT_COUNT, supplier: Supplier?): SearchOutcome {
        if (query.isBlank()) {
            return SearchOutcome.Unavailable(
                reason = DenyReason.NO_PRIVILEGE,
                detail = "EMPTY_QUERY",
                message = S1bFallback.message(
                    what = "搜尋（EMPTY_QUERY）",
                    reason = DenyReason.NO_PRIVILEGE,
                    detail = "EMPTY_QUERY",
                    alternative = FALLBACK_HINT,
                    needFromUser = "提供要搜尋的關鍵字",
                ),
            )
        }
        val active = supplier
            ?: return SearchOutcome.Unavailable(
                reason = DenyReason.NO_PRIVILEGE,
                detail = "NO_SUPPLIER",
                message = S1bFallback.message(
                    what = "搜尋（NO_SUPPLIER）",
                    reason = DenyReason.NO_PRIVILEGE,
                    detail = "NO_SUPPLIER",
                    alternative = FALLBACK_HINT,
                    needFromUser = "手動在瀏覽器搜尋，或先配置搜尋供應商再開啟 websearch 開關",
                ),
            )
        return try {
            SearchOutcome.Ok(active.search(query, countOf(count)))
        } catch (e: Exception) {
            SearchOutcome.Failed(
                detail = "SUPPLIER_ERROR",
                message = S1bFallback.message(
                    what = "搜尋（SUPPLIER_ERROR）",
                    reason = DenyReason.NO_PRIVILEGE,
                    detail = "SUPPLIER_ERROR",
                    alternative = FALLBACK_HINT,
                    needFromUser = "手動在瀏覽器搜尋（供應商錯誤：${e.message ?: e.javaClass.simpleName}）",
                ),
            )
        }
    }

    /**
     * D3 suspend 版：經 [WebSearchAdapter] 走新 provider 層。
     *
     * - `masterEnabled=false`（預設）或 `consentConfirmed=false`（預設 fail-closed）
     *   → `Unavailable(PROVIDER_DISABLED→USER_DISABLED)`，零探測、零 upstream；
     * - 空 query → `Unavailable(EMPTY_QUERY)`；
     * - SearXNG 預設首選、DDGS 需 [ddgsOptIn]（未 opt-in 直接排除，
     *   pinned `ddgs` 亦 fail-closed 為 `PINNED_UNAVAILABLE`，不靜默換）；
     * - `Failed(fallbackAllowed)`＋未 pin 才換下一個，`Unavailable`／pinned／
     *   AUTH／PROTOCOL 一律不換；取消（CE）原樣透傳，不 fallback、不寫 cache
     *  （cache 由傳入的 `WebSearchService` 保證）。
     * - `hostedAvailable=true` 且未 pin → `Unavailable(HOSTED_NOT_SUPPORTED)`
     *  （本地不與 hosted 並用，由上層走 hosted）。
     *
     * 純映射：`Ok` 取 title／url／snippet（`published`／`siteName` 為 model 可見
     * 預算內資訊，本地 `Result` 僅保留三欄）並透傳 [SearchOutcome.Ok.truncated] 與
     * [SearchOutcome.Ok.cached]；`Unavailable` 按原因映射
     * `DenyReason`（`PROVIDER_DISABLED→USER_DISABLED`，其餘 `NO_PRIVILEGE`）；
     * `Failed` 保留 `reason.detail`。
     */
    suspend fun searchSuspend(
        query: String,
        count: Int = DEFAULT_COUNT,
        registry: WebSearchRegistry,
        masterEnabled: Boolean = SWITCH_DEFAULT,
        pinnedId: String? = null,
        ddgsOptIn: Boolean = false,
        hostedAvailable: Boolean = false,
        consentConfirmed: Boolean = false,
        isReady: (suspend (WebSearchProvider) -> Boolean)? = null,
    ): SearchOutcome {
        val n = countOf(count)
        val outcome = WebSearchAdapter.search(
            query = query,
            count = n,
            registry = registry,
            config = WebSearchAdapter.Config(
                masterEnabled = masterEnabled,
                pinnedId = pinnedId,
                ddgsOptIn = ddgsOptIn,
                hostedAvailable = hostedAvailable,
                consentConfirmed = consentConfirmed,
            ),
            isReady = isReady,
        )
        return when (outcome) {
            is WebSearchOutcome.Ok -> SearchOutcome.Ok(
                results = outcome.results.map { Result(it.title, it.url, it.snippet) },
                truncated = outcome.truncated,
                cached = outcome.cached,
            )
            is WebSearchOutcome.Unavailable -> {
                val reason = if (outcome.reason == WebUnavailableReason.PROVIDER_DISABLED) {
                    DenyReason.USER_DISABLED
                } else {
                    DenyReason.NO_PRIVILEGE
                }
                val needFromUser = when (outcome.reason) {
                    WebUnavailableReason.PROVIDER_DISABLED ->
                        "先在設定→網路搜尋開啟開關並完成同意（目前版本尚未接線至對話工具執行）"
                    WebUnavailableReason.EMPTY_QUERY ->
                        "提供要搜尋的關鍵字"
                    WebUnavailableReason.HOSTED_NOT_SUPPORTED ->
                        "目前端點支援服務端搜尋，應改走 hosted 路徑"
                    WebUnavailableReason.PINNED_UNAVAILABLE ->
                        "檢查所指定的搜尋供應商設定是否就緒（或取消固定改用預設）"
                    WebUnavailableReason.MISSING_URL ->
                        "先在設定填入有效的 SearXNG baseUrl"
                    WebUnavailableReason.INVALID_URL ->
                        "修正設定中的 SearXNG URL 格式（需 http/https 且不可含帳密、query 或 fragment）"
                    WebUnavailableReason.MISSING_KEY ->
                        "先在設定配置該搜尋供應商所需的 API 金鑰"
                    WebUnavailableReason.NO_PROVIDER ->
                        "手動在瀏覽器搜尋，或先配置並啟用可用的搜尋供應商"
                }
                SearchOutcome.Unavailable(
                    reason = reason,
                    detail = outcome.detail,
                    message = S1bFallback.message(
                        what = "搜尋（${outcome.detail}）",
                        reason = reason,
                        detail = outcome.detail,
                        alternative = FALLBACK_HINT,
                        needFromUser = needFromUser,
                    ),
                )
            }
            is WebSearchOutcome.Failed -> SearchOutcome.Failed(
                detail = outcome.detail,
                message = S1bFallback.message(
                    what = "搜尋（${outcome.detail}）",
                    reason = DenyReason.NO_PRIVILEGE,
                    detail = outcome.detail,
                    alternative = FALLBACK_HINT,
                    needFromUser = "手動在瀏覽器搜尋（供應商錯誤：${outcome.detail}）",
                ),
            )
        }
    }
}
