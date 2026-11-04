package dev.librepocket.web

/**
 * D1 web.search 正規化 schema（#138 Scope：title／url／snippet／published／siteName）。
 *
 * 純 JVM，不觸碰網路；char-budget 截斷語義沿用 `WebFetch` readCapped
 *（保留前綴＋`truncated` 旗標，永不拋異常）。
 */
data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val published: String? = null,
    val siteName: String? = null,
)

/** 搜尋預算（#138 Scope：search 1..10 筆、model 可見總 20k chars；TTL／single-flight 待 D2）。 */
object WebSearchBudgets {
    const val MIN_COUNT = 1
    const val MAX_COUNT = 10
    const val DEFAULT_COUNT = 5

    /** Model 可見總字元預算（結果集 title＋snippet 加總，超限截斷並標記）。 */
    const val MAX_MODEL_CHARS = 20_000

    fun countOf(raw: Int?): Int = (raw ?: DEFAULT_COUNT).coerceIn(MIN_COUNT, MAX_COUNT)
}

/** Char-budget 截斷結果（`truncated` 為 true 表示曾丟棄內容）。 */
data class CappedResults(
    val results: List<WebSearchResult>,
    val truncated: Boolean,
)

/**
 * 按 [WebSearchBudgets.MAX_MODEL_CHARS] 截斷結果集。
 *
 * 逐筆保留完整 item；邊界筆只截 `snippet`（保留 title／url 可追溯）；
 * 空輸入回傳空＋`truncated=false`，永不拋異常。
 */
fun capResultsForModel(
    results: List<WebSearchResult>,
    maxChars: Int = WebSearchBudgets.MAX_MODEL_CHARS,
): CappedResults {
    val cap = maxChars.coerceAtLeast(1)
    var used = 0
    val kept = ArrayList<WebSearchResult>(results.size)
    for (r in results) {
        val cost = r.title.length + r.snippet.length
        if (used + cost <= cap) {
            kept.add(r)
            used += cost
            continue
        }
        // 邊界筆：title 完整保留（url 可追溯），snippet 截斷至剩餘預算。
        val room = cap - used - r.title.length
        if (room > 0) {
            kept.add(r.copy(snippet = r.snippet.take(room)))
        } else if (kept.isEmpty()) {
            // 首筆 title 即超預算：只保留 title 前綴，snippet 清空。
            kept.add(r.copy(title = r.title.take(cap), snippet = ""))
        }
        return CappedResults(kept.toList(), true)
    }
    return CappedResults(kept.toList(), false)
}
