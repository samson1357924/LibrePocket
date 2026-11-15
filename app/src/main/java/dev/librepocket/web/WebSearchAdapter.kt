package dev.librepocket.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * D3 本地搜尋 adapter（#138 D3；純 JVM，不接線）。
 *
 * - SearXNG 預設 fallback、DDGS opt-in：未 pin＋無 hosted 時，候選依「非 DDGS 優先、
 *   DDGS 在後」順序，按需（lazy）以 `isReady` 探測；首個就緒者執行，只有
 *   `Failed(fallbackAllowed)` 才依序探測下一個（經 [WebSearchSelector.mayFallback]）；
 *   `Unavailable` 一律不換、pinned 一律不換、AUTH／PROTOCOL 一律不換。
 * - DDGS 未 opt-in 時直接排除（含 pinned `ddgs` → `PINNED_UNAVAILABLE`，
 *   fail-closed，不靜默換 SearXNG）。
 * - master 關閉或尚未確認同意（[Config.consentConfirmed]）→ `Unavailable(PROVIDER_DISABLED)`，
 *   零探測、零 upstream。
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
        val consentConfirmed: Boolean = false,
    )

    /**
     * 候選過濾與排序（非 DDGS／SearXNG 優先，DDGS 僅在 opt-in 後排於尾端）。
     *
     * DDGS 識別以 id 字面量（[DdgsSearchProvider.ID]）判定，不依賴 capabilities
     *（兩者皆 keyless，capabilities 無法區分；id 為 registry 唯一鍵，穩定）。
     */
    internal fun candidatesOf(
        providers: List<WebSearchProvider>,
        ddgsOptIn: Boolean,
    ): List<WebSearchProvider> {
        val primary = providers.filter { it.id != DdgsSearchProvider.ID }
        if (!ddgsOptIn) return primary
        return primary + providers.filter { it.id == DdgsSearchProvider.ID }
    }

    suspend fun search(
        query: String,
        count: Int,
        registry: WebSearchRegistry,
        config: Config = Config(),
        isReady: (suspend (WebSearchProvider) -> Boolean)? = null,
    ): WebSearchOutcome {
        if (!config.masterEnabled || !config.consentConfirmed) {
            return WebSearchOutcome.Unavailable(WebUnavailableReason.PROVIDER_DISABLED)
        }
        if (query.isBlank()) return WebSearchOutcome.Unavailable(WebUnavailableReason.EMPTY_QUERY)

        val n = WebSearchBudgets.countOf(count)
        // Pinned DDGS 但未 opt-in → fail-closed（不靜默換 SearXNG）。
        val pin = config.pinnedId?.trim()?.takeIf { it.isNotEmpty() }
        if (pin == DdgsSearchProvider.ID && !config.ddgsOptIn) {
            return WebSearchOutcome.Unavailable(WebUnavailableReason.PINNED_UNAVAILABLE)
        }

        val ordered = candidatesOf(registry.providers(), config.ddgsOptIn)
        val filtered = WebSearchRegistry(ordered)
        val readyCheck: suspend (WebSearchProvider) -> Boolean =
            isReady ?: { it.probe() is WebProbe.Ready }

        suspend fun safeReady(p: WebSearchProvider): Boolean {
            return try {
                val ready = readyCheck(p)
                currentCoroutineContext().ensureActive()
                ready
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                false
            } catch (e: Throwable) {
                throw e
            }
        }

        var startIndex = -1
        val route: WebRoute = when {
            pin != null -> {
                val p = filtered.get(pin)
                if (p == null) {
                    WebSearchSelector.select(pin, config.hostedAvailable, filtered) { false }
                } else {
                    val pReady = safeReady(p)
                    startIndex = ordered.indexOfFirst { it.id == pin }
                    WebSearchSelector.select(pin, config.hostedAvailable, filtered) { it.id == pin && pReady }
                }
            }
            config.hostedAvailable -> {
                WebSearchSelector.select(null, true, filtered) { false }
            }
            else -> {
                var firstReady: WebSearchProvider? = null
                for (i in ordered.indices) {
                    val candidate = ordered[i]
                    if (safeReady(candidate)) {
                        firstReady = candidate
                        startIndex = i
                        break
                    }
                }
                val firstId = firstReady?.id
                WebSearchSelector.select(null, false, filtered) { it.id == firstId }
            }
        }

        when (route) {
            is WebRoute.Hosted ->
                return WebSearchOutcome.Unavailable(WebUnavailableReason.HOSTED_NOT_SUPPORTED)
            is WebRoute.Unavailable ->
                return WebSearchOutcome.Unavailable(route.reason)
            is WebRoute.Local -> Unit
        }

        val indicesToTry: Iterable<Int> = if (pin != null) {
            listOf(startIndex)
        } else {
            startIndex until ordered.size
        }

        var last: WebSearchOutcome? = null
        for (i in indicesToTry) {
            val p = ordered[i]
            if (i != startIndex && !safeReady(p)) continue
            val out = try {
                val r = p.search(query, n)
                currentCoroutineContext().ensureActive()
                r
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                WebSearchOutcome.Failed(WebFailureReason.TRANSPORT)
            } catch (e: Throwable) {
                throw e
            }
            when (out) {
                is WebSearchOutcome.Ok -> return out
                is WebSearchOutcome.Unavailable -> return last ?: out
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
