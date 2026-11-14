package dev.librepocket.web

/**
 * D3 設定／KeyVault 契約（#138 D3；純 JVM，不接線）。
 *
 * - master 開關 key 沿用 [dev.librepocket.tool.WebSearchLocal.SWITCH]
 *  （`websearch`，預設關）；DDGS opt-in 為獨立開關（預設關）。
 * - DDGS 為非官方 DuckDuckGo HTML 端點：任何顯示 DDGS 的 UI 必須同時顯示
 *   [DDGS_UNOFFICIAL_WARNING]（見設定卡測試釘住）。
 * - KeyVault 命名空間 `web.<providerId>`（如 `web.searxng`、`web.ddgs`），
 *   與聊天 key（`preset:*`／`custom:*`，見 `SetupValidation`）永不共用；
 *   keyId 綁定 providerId＋origin（[keyIdFor]），endpoint 切換不可沿用。
 * - D5 的 keyed providers 尚未落地：本檔只定命名空間與狀態映射，
 *   不做任何 key 讀寫；SearXNG／DDGS 皆 keyless（SearXNG 需 URL，DDGS 免設定）。
 */
object WebSearchSettings {

    /** DDGS opt-in 開關（預設關；需明確 opt-in 才納入候選）。 */
    const val DDGS_SWITCH = "websearch_ddgs"
    const val DDGS_SWITCH_DEFAULT = false

    /** 首次開啟 consent sheet 確認旗（DataStore 持久化，只問一次）。 */
    const val CONSENT_ACK_KEY = "websearch_consent_ack"

    /** SearXNG baseUrl 持久化 key（空字串＝未設定 → `MISSING_URL`）。 */
    const val SEARXNG_URL_KEY = "websearch_searxng_url"

    /**
     * DDGS 非官方警語（D3 必須顯示，任何顯示 DDGS 的 UI 不得省略）。
     */
    const val DDGS_UNOFFICIAL_WARNING =
        "DDGS 為非官方 DuckDuckGo HTML 端點（解析 HTML，非官方 API），" +
            "可能隨時失效或觸發驗證；僅在明確 opt-in 後使用，不保證可用性。"

    const val CONSENT_TITLE = "啟用網路搜尋？"
    const val CONSENT_TEXT =
        "啟用後，搜尋查詢內容（未經全域脫敏）將經網路送往你設定的搜尋後端" +
            "（預設自架 SearXNG；若另開啟 DDGS opt-in，當 SearXNG 未設定、格式無效或暫時失敗時" +
            "將自動改送 DuckDuckGo 非官方端點，見下方警語），並依該後端條款處理。" +
            "本機快取保留 15 分鐘，取消不寫快取。" +
            "偏好設定（含 SearXNG 網址與同意狀態，不含 API 金鑰）會依系統與應用備份設定納入 Android 備份範圍。" +
            "目前版本（D3）尚未提供實際聊天工具連線搜尋（僅儲存設定與同意狀態）。"

    /** SearXNG URL 形狀驗證單一真相源（純本地格式檢查，不打網路）。 */
    fun probeSearxngUrl(baseUrl: String): WebProbe = SearxngSearchProvider.probeUrl(baseUrl)

    private fun requireValidProviderId(providerId: String): String {
        val pid = providerId.trim()
        require(
            pid.isNotEmpty() &&
                pid == providerId &&
                '@' !in pid &&
                '/' !in pid &&
                !pid.any { it.isWhitespace() } &&
                !containsControlFormatOrSurrogateChar(pid),
        ) { "INVALID_WEB_PROVIDER_ID" }
        return pid
    }

    /** KeyVault 命名空間：`web.<providerId>`（與聊天 key 永不共用）。 */
    fun namespaceFor(providerId: String): String = "web.${requireValidProviderId(providerId)}"

    /**
     * keyId 綁定 providerId＋origin（endpoint 切換不可沿用）。
     *
     * 嚴格驗證 providerId 與 http/https origin，scheme＋host 小寫、保留明確 port 與
     * `rawPath` 大小寫、去尾 `/`；非法輸入 fail-fast 拋 [IllegalArgumentException]。
     */
    fun keyIdFor(providerId: String, origin: String): String {
        val pid = requireValidProviderId(providerId)
        val normalized = normalizeHttpOrigin(origin)
            ?: throw IllegalArgumentException("INVALID_WEB_ORIGIN")
        return "${namespaceFor(pid)}@$normalized"
    }

    /** 設定頁 provider 狀態（由 `probe()` 映射，見 [statusFor]）。 */
    enum class ProviderStatus {
        AVAILABLE,
        MISSING_URL,
        INVALID_URL,
        MISSING_KEY,
        NOT_READY,
    }

    fun statusFor(probe: WebProbe): ProviderStatus = when (probe) {
        is WebProbe.Ready -> ProviderStatus.AVAILABLE
        is WebProbe.NotReady -> when (probe.reason) {
            WebUnavailableReason.MISSING_URL -> ProviderStatus.MISSING_URL
            WebUnavailableReason.INVALID_URL -> ProviderStatus.INVALID_URL
            WebUnavailableReason.MISSING_KEY -> ProviderStatus.MISSING_KEY
            else -> ProviderStatus.NOT_READY
        }
    }

    fun statusText(status: ProviderStatus): String = when (status) {
        ProviderStatus.AVAILABLE -> "設定格式有效（未連網）"
        ProviderStatus.MISSING_URL -> "缺 URL"
        ProviderStatus.INVALID_URL -> "URL 格式無效"
        ProviderStatus.MISSING_KEY -> "缺 key"
        ProviderStatus.NOT_READY -> "未就緒"
    }
}
