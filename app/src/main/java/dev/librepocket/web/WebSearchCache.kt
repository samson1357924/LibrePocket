package dev.librepocket.web

import java.util.Collections

/**
 * D2 搜尋結果記憶體快取（#138 Scope：TTL 15 min；純 JVM，不接線）。
 *
 * - key＝providerId＋正規化 query＋count；只存 `Ok`（`Failed`／`Unavailable`
 *   不存，避免把熔斷或缺設定焊住，也避免壓住 selector 的 fallback）。
 * - 時鐘可注入（`clockMs`），TTL 到期採 lazy 淘汰（`get` 命中過期即刪）＋
 *   `put` 時順手掃一輪過期；另有 [evictExpired] 可供呼叫方／測試主動清掃。
 * - 有界 LRU：最多保留 [MAX_ENTRIES] 筆，超過時淘汰最久未使用（access-order
 *   `LinkedHashMap.removeEldestEntry`）。`Collections.synchronizedMap` 保證單步
 *   操作執行緒安全；`get` 的檢查—刪除與 `put` 的掃描—插入等複合操作額外
 *   `synchronized(map)`。
 * - 防禦性拷貝：`put` 存 `outcome.copy(results = outcome.results.toList())`，
 *   `get` 回傳同樣拷貝，避免呼叫方持有／竄改 list 污染快取。
 * - key 格式為內部不透明（`key()` 保持 public 僅因 Service／測試需要）：
 *   段長度前綴（`"${len}:${segment}\u0001..."`）避免 `\u0001` 拼接碰撞；
 *   僅 `trim()` query，不做大小寫正規化——搜尋引擎可能大小寫敏感，
 *   大小寫合流留待 PocketGuard 階段再議。
 * - `clockMs` 生產環境應為單調時鐘（如 `SystemClock.elapsedRealtime()`），
 *   預設 `System.currentTimeMillis()` 僅為 JVM 測試方便；wall-clock 回撥
 *   可能讓已過期條目短暫復活，屬於已知取捨。
 * - 刻意快取空結果 `Ok`：空 `Ok` 仍寫入，避免同 query 反覆打 upstream；
 *   是否區分空／非空留待 D3 再議。
 * - disk spill 列 phase 2（non-goal）。
 */
class WebSearchCache(
    val ttlMs: Long = DEFAULT_TTL_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    init {
        require(ttlMs > 0) { "ttlMs must be > 0" }
    }

    companion object {
        const val DEFAULT_TTL_MS = 15 * 60 * 1_000L

        /** 快取上限筆數（LRU 有界，避免無界增長）。 */
        const val MAX_ENTRIES = 200
    }

    private data class Entry(val outcome: WebSearchOutcome.Ok, val expiresAt: Long)

    private val map: MutableMap<String, Entry> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
                override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Entry>): Boolean =
                    size > MAX_ENTRIES
            },
        )

    fun key(providerId: String, query: String, count: Int): String {
        val q = query.trim()
        return "${providerId.length}:$providerId\u0001${q.length}:$q\u0001$count"
    }

    /** 命中回原始 `Ok` 的防禦性拷貝（`cached=false`）；由 [WebSearchService] 翻成 `cached=true`。 */
    fun get(providerId: String, query: String, count: Int): WebSearchOutcome.Ok? =
        getByKey(key(providerId, query, count))

    internal fun getByKey(k: String): WebSearchOutcome.Ok? {
        val entry: Entry?
        // Collections.synchronizedMap: compound get-check-remove + iteration require external synchronized(map).
        synchronized(map) {
            entry = map[k]
            if (entry != null && clockMs() >= entry.expiresAt) {
                map.remove(k)
                return null
            }
            // 觸碰 LRU 順序：synchronizedMap 的 get 已更新 access-order。
        }
        return entry?.outcome?.copy(results = entry.outcome.results.toList())
    }

    fun put(providerId: String, query: String, count: Int, outcome: WebSearchOutcome.Ok) {
        putByKey(key(providerId, query, count), outcome)
    }

    internal fun putByKey(k: String, outcome: WebSearchOutcome.Ok) {
        val now = clockMs()
        // 溢位防護：now 已接近 Long.MAX_VALUE 時先箝制再加 ttl。
        val expiresAt = now.coerceAtMost(Long.MAX_VALUE - ttlMs) + ttlMs
        val snapshot = outcome.copy(results = outcome.results.toList())
        synchronized(map) {
            // 先掃過期再插入，避免過期條目長期佔住 LRU 名額。
            sweepLocked(now)
            map[k] = Entry(snapshot, expiresAt)
        }
    }

    /** 主動清除所有過期條目（`put` 已內建順手清掃；此供呼叫方／測試用）。 */
    fun evictExpired() {
        synchronized(map) {
            sweepLocked(clockMs())
        }
    }

    fun clear() {
        // synchronizedMap 的 clear 本身已同步。
        map.clear()
    }

    /**
     * 目前 map 筆數（含尚未被掃掉的過期條目；cheap，不觸發掃描）。
     * 如需精確有效筆數請先呼叫 [evictExpired]。
     */
    val size: Int get() = map.size

    /** 呼叫方須持有 `synchronized(map)`。以 [nowMs] 快照掃描，避免逐筆重取時鐘。 */
    private fun sweepLocked(nowMs: Long) {
        val it = map.entries.iterator()
        while (it.hasNext()) {
            if (nowMs >= it.next().value.expiresAt) it.remove()
        }
    }
}
