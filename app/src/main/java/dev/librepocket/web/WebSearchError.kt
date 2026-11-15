package dev.librepocket.web

/**
 * D1 typed 錯誤（#138 Scope：pinned 不可用 → `Unavailable` 不靜默換；
 * 非 pinned 才 fallback，且 AUTH／PROTOCOL 不 fallback）。
 *
 * 純 JVM 分類器：[WebFailureReason.fallbackAllowed] 為 false 者呼叫方
 * 不得換下一個 provider；[WebUnavailableReason] 一律不 fallback。
 */
enum class WebUnavailableReason {
    EMPTY_QUERY,
    NO_PROVIDER,
    PROVIDER_DISABLED,
    MISSING_KEY,
    MISSING_URL,
    INVALID_URL,
    PINNED_UNAVAILABLE,
    HOSTED_NOT_SUPPORTED;

    /** 除錯／transcript 用的穩定字串，恆等於 [name]（去重：不再為每個 entry 存一份拷貝）。 */
    val detail: String get() = name
}

enum class WebFailureReason(val fallbackAllowed: Boolean) {
    AUTH(false),
    PROTOCOL(false),
    TRANSPORT(true),
    TIMEOUT(true),
    BAD_RESPONSE(true),
    SUPPLIER_ERROR(true);

    /** 除錯／transcript 用的穩定字串，恆等於 [name]（去重：不再為每個 entry 存一份拷貝）。 */
    val detail: String get() = name
}

sealed interface WebSearchOutcome {
    data class Ok(
        val results: List<WebSearchResult>,
        val truncated: Boolean = false,
        /** D2：true 表來自 [WebSearchService] 快取命中（零 upstream）。 */
        val cached: Boolean = false,
    ) : WebSearchOutcome

    data class Unavailable(val reason: WebUnavailableReason) : WebSearchOutcome {
        val detail: String get() = reason.detail
    }

    data class Failed(val reason: WebFailureReason) : WebSearchOutcome {
        val detail: String get() = reason.detail
        val fallbackAllowed: Boolean get() = reason.fallbackAllowed
    }
}
