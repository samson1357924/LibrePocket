package dev.librepocket.web

import java.util.concurrent.ConcurrentHashMap

/**
 * D2 搜尋結果記憶體快取（#138 Scope：TTL 15 min；純 JVM，不接線）。
 *
 * - key＝providerId＋正規化 query＋count；只存 `Ok`（`Failed`／`Unavailable`
 *   不存，避免把熔斷或缺設定焊住，也避免壓住 selector 的 fallback）。
 * - 時鐘可注入（`clockMs`），TTL 到期採 lazy 淘汰；disk spill 列 phase 2（non-goal）。
 */
class WebSearchCache(
    val ttlMs: Long = DEFAULT_TTL_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val DEFAULT_TTL_MS = 15 * 60 * 1_000L
    }

    private data class Entry(val outcome: WebSearchOutcome.Ok, val expiresAt: Long)

    private val map = ConcurrentHashMap<String, Entry>()

    fun key(providerId: String, query: String, count: Int): String =
        providerId + "\u0001" + query.trim() + "\u0001" + count

    /** 命中回原始 `Ok`（`cached=false`）；由 [WebSearchService] 翻成 `cached=true`。 */
    fun get(providerId: String, query: String, count: Int): WebSearchOutcome.Ok? {
        val k = key(providerId, query, count)
        val entry = map[k] ?: return null
        if (clockMs() >= entry.expiresAt) {
            map.remove(k, entry)
            return null
        }
        return entry.outcome
    }

    fun put(providerId: String, query: String, count: Int, outcome: WebSearchOutcome.Ok) {
        map[key(providerId, query, count)] = Entry(outcome, clockMs() + ttlMs.coerceAtLeast(1))
    }

    fun clear() {
        map.clear()
    }

    val size: Int get() = map.size
}
