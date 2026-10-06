package dev.librepocket.tool

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
        data class Ok(val results: List<Result>) : SearchOutcome
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
}
