package dev.librepocket.web

/**
 * D1 provider registry（純記憶體快照；SearXNG／DDGS 註冊待 D2，不接 production）。
 *
 * 重複 [WebSearchProvider.id] 在建構時拋 [IllegalArgumentException]（fail-fast，
 * 避免靜默覆蓋）；空白或含 `\u0001`（cache key 分隔符）的 id 同樣 fail-fast
 *（`INVALID_PROVIDER_ID`）。
 *
 * 快照語義：[providers] 每次回傳新建 [List] 拷貝，[ids] 每次回傳新建 [Set] 拷貝；
 * 呼叫方不得假設拿到的是內部視圖，可自由修改回傳集合。`ids()` 拷貝亦保證
 * [WebSearchCache] LRU 淘汰時呼叫方手上的集合不會被連動修改。
 */
class WebSearchRegistry(providers: List<WebSearchProvider>) {
    private val ordered: List<WebSearchProvider> = providers.toList()
    private val byId: Map<String, WebSearchProvider> = run {
        val seen = LinkedHashMap<String, WebSearchProvider>(ordered.size)
        for (p in ordered) {
            require(p.id.isNotBlank() && '\u0001' !in p.id) { "INVALID_PROVIDER_ID:${p.id}" }
            require(!seen.containsKey(p.id)) { "DUPLICATE_PROVIDER_ID:${p.id}" }
            seen[p.id] = p
        }
        seen
    }

    /** 註冊順序（selector fallback 依此順序；D2 填入 SearXNG→DDGS 預設序）。每次回傳快照拷貝。 */
    fun providers(): List<WebSearchProvider> = ordered.toList()

    fun get(id: String): WebSearchProvider? = byId[id]

    fun contains(id: String): Boolean = byId.containsKey(id)

    /** 快照拷貝（LRU-safe：非內部 keySet 視圖）。 */
    fun ids(): Set<String> = byId.keys.toSet()

    val size: Int get() = ordered.size
}
