package dev.librepocket.web

/** 本地 provider 能力（key／URL 缺失 → typed `Unavailable`，見 D3 設定 UX；keyed 實作待 D5）。 */
enum class WebCapability {
    REQUIRES_KEY,
    REQUIRES_URL,
}

/** 單次 vault 讀取得出的憑證狀態。 */
sealed interface CredentialState {
    /** 非 keyed provider（無綁定憑證）→ 沿用舊 cache 行為。 */
    data object NotApplicable : CredentialState
    /**
     * vault 讀取失敗／缺 key 等致憑證狀態未知。持久故障（缺 key／vault 完全故障／blank key）→ Service 完全旁路快取（不讀不寫、不進 single-flight）、fail-closed，delegate `search()` 重讀亦失敗、零網路。
     * 暫時故障（vault 抖動）→ 同樣旁路（uncacheable），delegate `search()` 會重讀 vault 一次——若當時已恢復則照常打 upstream（query 原文 egress）；仍 fail-closed、仍不寫快取、永不使用過期憑證。
     */
    data object Unavailable : CredentialState
    /**
     * 讀取成功；snapshot 所有權移交呼叫方，呼叫方負責 wipe（見 [CredentialSnapshot.wipe]）。
     * 所有權契約（亦見 `searchWithSnapshot()` KDoc）：呼叫方 MUST 以 try/finally 於 finally 內 `wipe()`，
     * 即使 search()／searchWithSnapshot() 拋出；snapshot 為單次使用。被呼叫端不得保留／複製／清零傳入的 snapshot；
     * 負責 wipe 的是持有最外層 search() 呼叫的一方（一般為最外層 WebSearchService）。
     * 含取消（CE 原樣透傳）路徑：持有者必須先確保 snapshot 已指派進入 try/finally 再做任何可能拋出的動作（WebSearchService 以「指派→ensureActive→分派」順序落實）。
     */
    class Ready(val snapshot: CredentialSnapshot) : CredentialState
}

/**
 * 不可變憑證快照：同一份（trim 後）CharArray 既產生 cache 指紋（[fingerprint]）、亦供 HTTP 請求使用，
 * 消除 read#1(指紋)／read#2(key) 之間的憑證漂移。in-memory only；絕不落盤。
 * 指紋 = SHA-256(trim 後 CharArray 經 LE byte-pack)，取前 8 bytes 之十六進位（16 hex chars）。key material 不進指紋、不印出。
 * [ownerKeyId] 記錄產生本 snapshot 的 keyId（keyed provider 建構時綁定）；接收端
 * MUST 以此核對自身 keyId，防 D3 接線傳錯 snapshot 造成跨 provider 送 key。
 */
class CredentialSnapshot internal constructor(
    internal val key: CharArray,
    internal val fingerprint: String,
    internal val ownerKeyId: String? = null,
) {
    /** 與原 `search()` 相同的正規化：concat → trim。僅供 provider 內部單次請求使用。 */
    internal fun keyAsTrimmedString(): String = key.concatToString().trim()

    /** 清零持有的 key material（呼叫方責任：`finally` 保證）。 */
    fun wipe() {
        key.fill('\u0000')
    }
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
     * 解析一次 vault 憑證快照（keyed provider）。預設 [CredentialState.NotApplicable]（keyless）。
     * 以 throw 方式表達 vault 暫時故障時，[WebSearchService] 會映射為 [CredentialState.Unavailable]（旁路、fail-closed），不拋給呼叫端。
     * [CancellationException] 原樣透傳。
     */
    suspend fun credentialState(): CredentialState = CredentialState.NotApplicable

    /**
     * 以 [snapshot] 執行搜尋（不再讀 vault；HTTP 使用 snapshot 的 key）。
     * 巢狀 [WebSearchService]：Service 收到此呼叫時，沿用外層已解析的同一份 snapshot 繼續處理（見 Service KDoc）。
     * 預設實作回 `Unavailable(MISSING_KEY)`、零 HTTP。keyed provider MUST 覆寫，否則功能性靜默失效（非安全漏洞，不影響憑證邊界）；unwired provider 仍 fail-closed。
     * 不得清零傳入的 snapshot（由最外層持有者負責）。
     * 所有權契約（MUST）：持有最外層 search() 呼叫的一方 MUST 以 try/finally 於 finally 內 `wipe()`
     * 傳入的 snapshot，即使本方法拋出；snapshot 為單次使用；被呼叫端不得保留／複製／清零傳入的 snapshot。
     */
    suspend fun searchWithSnapshot(snapshot: CredentialSnapshot, query: String, count: Int): WebSearchOutcome =
        WebSearchOutcome.Unavailable(WebUnavailableReason.MISSING_KEY)

    /** 可用性探測（缺 key／缺 URL 回 [WebProbe.NotReady]，不拋異常）。 */
    suspend fun probe(): WebProbe

    /** 搜尋（空 query 回 `Unavailable(EMPTY_QUERY)`，不發任何請求）。 */
    suspend fun search(query: String, count: Int): WebSearchOutcome
}
