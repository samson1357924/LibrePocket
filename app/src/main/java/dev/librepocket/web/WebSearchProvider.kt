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
 *
 * Throw 契約（Phase 4）：
 * - 實作必須不拋異常（除了 [kotlinx.coroutines.CancellationException]，其必須原樣透傳）；
 * - 所有可預期錯誤一律以值回傳：缺設定／空 query → [WebSearchOutcome.Unavailable]，
 *   傳輸／超時／協定／上游錯誤 → [WebSearchOutcome.Failed]；
 * - [WebSearchService] 在中央統一把意外 `Exception` 映射為 `Failed(TRANSPORT)`
 *  （fail-closed；見 Phase 1），`Error` 仍原樣拋出。呼叫方只處理值，不接 raw throw。
 */
interface WebSearchProvider {
    /** Registry 內唯一（重複 id 在 [WebSearchRegistry] 建構時 fail-fast）。 */
    val id: String

    /**
     * 快取／in-flight 命名空間（[WebSearchService] 以此切 cache key 與合流 key）。
     *
     * 預設為 [id]；具 per-origin 設定（如 baseUrl／endpoint 不同即不同上游）的
     * provider 必須覆寫，把正規化後的 origin 納入（同源沿用舊命中、異源不再互撞）。
     * Registry 身份仍以 [id] 為準（同一 registry 內多實例仍 fail-fast，不以此欄區分）。
     */
    val cacheNamespace: String get() = id

    val capabilities: Set<WebCapability>

    /**
     * Credential revision for cache coherence. Implementations holding a
     * bound credential (keyed providers) MUST return a non-null, stable-but-
     * distinct value per distinct credential, or [REV_UNAVAILABLE] when the
     * credential state cannot be determined (transient vault failure / missing
     * key); [null] means "no credential" (non-keyed provider) and preserves
     * legacy cache behavior.
     *
     * The value is in-memory only (a fingerprint), never persisted, and must not
     * be derivable back into key material.
     * [REV_UNAVAILABLE] is a bypass sentinel: [WebSearchService] must not read,
     * join, or write any cache row when it is returned.
     */
    suspend fun cacheRevision(): String? = null

    /** 可用性探測（缺 key／缺 URL 回 [WebProbe.NotReady]，不拋異常）。 */
    suspend fun probe(): WebProbe

    /** 搜尋（空 query 回 `Unavailable(EMPTY_QUERY)`，不發任何請求）。 */
    suspend fun search(query: String, count: Int): WebSearchOutcome

    companion object {
        /** keyed provider 的 vault 讀取故障／憑證狀態未知哨兵：Service 必須旁路（bypass）整個快取，不讀不寫。 */
        const val REV_UNAVAILABLE: String = "rev-unavailable"
    }
}
