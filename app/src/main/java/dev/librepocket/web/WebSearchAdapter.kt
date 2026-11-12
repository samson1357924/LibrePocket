package dev.librepocket.web

import kotlinx.coroutines.CancellationException

/**
 * D3 本地搜尋 adapter（#138 D3；純 JVM，不接線）。
 *
 * - SearXNG 預設 fallback、DDGS opt-in：未 pin＋無 hosted 時，候選為
 *   registry 註冊順序（預期 SearXNG→DDGS）中「就緒＋（DDGS 需 opt-in）」者；
 *   首個 `Failed(fallbackAllowed)` 才換下一個（經
 *   [WebSearchSelector.mayFallback]）；`Unavailable` 一律不換、
 *   pinned 一律不換、AUTH／PROTOCOL 一律不換。
 * - DDGS 未 opt-in 時直接排除（含 pinned `ddgs` → `PINNED_UNAVAILABLE`，
 *   fail-closed，不靜默換 SearXNG）。
 * - master 關閉 → `Unavailable(PROVIDER_DISABLED)`，零 upstream。
 * - 取消語義：[CancellationException] 原樣透傳，不 fallback、不寫 cache
 *  （寫 cache 由 [WebSearchService] 保證，adapter 側保證不捕捉 CE、不換下一個）。
 * - 本層不做 cache：cache＋single-flight 由呼叫方傳入的
 *   [WebSearchService] 包裝提供（production 傳 Service、測試傳 fake Service）。
 * - `hostedAvailable=true` 且未 pin → [WebRoute.Hosted]，adapter 回
 *   `Unavailable(HOSTED_NOT_SUPPORTED)`（本地不與 hosted 並用，由上層走 hosted）。
 *
 * Throw 契約：除 [CancellationException] 外不拋異常（provider contract
 * no-throw＋Service 中央映射已保證；此處再以 try/catch 兜底未知 `Exception`
 * → `Failed(TRANSPORT)`，`Error` 仍原樣拋）。
 */
object WebSearchAdapter {

    data class Config(
        val masterEnabled: Boolean = false,
        val pinnedId: String? = null,
        val ddgsOptIn: Boolean = false,
        val hostedAvailable: Boolean = false,
    )

    /**
     * 候選過濾（SearXNG 預設、DDGS 需 opt-in）。
     *
     * DDGS 識別以 id 字面量（[DdgsSearchProvider.ID]）判定，不依賴 capabilities
     *（兩者皆 keyless，capabilities 無法區分；id 為 registry 唯一鍵，穩定）。
     */
    internal fun candidatesOf(
        providers: List<WebSearchProvider>,
        ddgsOptIn: Boolean,
    ): List<WebSearchProvider> {
        if (ddgsOptIn) return providers.toList()
        return providers.filter { it.id != DdgsSearchProvider.ID }
    }

    suspend fun search(
        query: String,
        count: Int,
        registry: WebSearchRegistry,
        config: Config = Config(),
        isReady: (WebSearchProvider) -> Boolean = { true },
    ): WebSearchOutcome {
        if (!config.masterEnabled) {
            return WebSearchOutcome.Unavailable(WebUnavailableReason.PROVIDER_DISABLED)
        }
        if (query.isBlank()) return WebSearchOutcome.Unavailable(WebUnavailableReason.EMPTY_QUERY)

        // Pinned DDGS 但未 opt-in → fail-closed（不靜默換 SearXNG）。
        val pin = config.pinnedId?.trim()?.takeIf { it.isNotEmpty() }
        if (pin == DdgsSearchProvider.ID && !config.ddgsOptIn) {
            return WebSearchOutcome.Unavailable(WebUnavailableReason.PINNED_UNAVAILABLE)
        }

        val filtered = WebSearchRegistry(candidatesOf(registry.providers(), config.ddgsOptIn))
        val route = WebSearchSelector.select(pin, config.hostedAvailable, filtered, isReady)
        when (route) {
            is WebRoute.Hosted ->
                return WebSearchOutcome.Unavailable(WebUnavailableReason.HOSTED_NOT_SUPPORTED)
            is WebRoute.Unavailable ->
                return WebSearchOutcome.Unavailable(route.reason)
            is WebRoute.Local -> {
                // 首選 provider（pinned 或首個就緒）。
            }
        }
        val firstId = (route as WebRoute.Local).providerId
        val ordered = filtered.providers()
        val startIndex = ordered.indexOfFirst { it.id == firstId }.coerceAtLeast(0)
        // Fallback 順序：首選＋其後就緒者（註冊順序；pinned 時 select 已保證
        // 單一路徑，mayFallback 恆 false，故迴圈只跑首選一次）。
        val queue = listOf(ordered[startIndex]) +
            ordered.drop(startIndex + 1).filter(isReady)

        var last: WebSearchOutcome? = null
        for (p in queue) {
            val out = try {
                p.search(query, count)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                WebSearchOutcome.Failed(WebFailureReason.TRANSPORT)
            } catch (e: Throwable) {
                throw e
            }
            when (out) {
                is WebSearchOutcome.Ok -> return out
                is WebSearchOutcome.Unavailable -> return out
                is WebSearchOutcome.Failed -> {
                    last = out
                    if (!WebSearchSelector.mayFallback(pin, out)) return out
                    // fallbackAllowed＋未 pin → 繼續下一個候選。
                }
            }
        }
        return last ?: WebSearchOutcome.Unavailable(WebUnavailableReason.NO_PROVIDER)
    }
}
