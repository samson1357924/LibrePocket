package dev.librepocket.web

/**
 * D1 provider registry（純記憶體快照；SearXNG／DDGS 註冊待 D2，不接 production）。
 *
 * 重複 [WebSearchProvider.id] 在建構時拋 [IllegalArgumentException]（fail-fast，
 * 避免靜默覆蓋）。
 */
class WebSearchRegistry(providers: List<WebSearchProvider>) {
    private val ordered: List<WebSearchProvider> = providers.toList()
    private val byId: Map<String, WebSearchProvider> = run {
        val seen = LinkedHashMap<String, WebSearchProvider>(ordered.size)
        for (p in ordered) {
            require(!seen.containsKey(p.id)) { "DUPLICATE_PROVIDER_ID:${p.id}" }
            seen[p.id] = p
        }
        seen
    }

    /** 註冊順序（selector fallback 依此順序；D2 填入 SearXNG→DDGS 預設序）。 */
    fun providers(): List<WebSearchProvider> = ordered

    fun get(id: String): WebSearchProvider? = byId[id]

    fun contains(id: String): Boolean = byId.containsKey(id)

    fun ids(): Set<String> = byId.keys

    val size: Int get() = ordered.size
}
