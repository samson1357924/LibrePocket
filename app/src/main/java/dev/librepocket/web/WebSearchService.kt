package dev.librepocket.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * D2 cache＋single-flight 包裝（#138 Scope：同 query 第二次零 upstream、`cached=true`；
 * 併發同 query 合流為一次 upstream；純 JVM，不接線）。
 *
 * - 命中快取 → `Ok.copy(cached = true)`，零 upstream。
 * - 未命中 → 同 key 併發呼叫共用同一個 upstream（leader 執行、
 *   follower `await()`；follower 取消不影響 leader，只中斷自己的等待）。
 * - 取消語義（#138 Scope：取消不 fallback、不寫 cache）：外部取消原樣透傳，
 *   不寫快取；`Failed`／`Unavailable` 同樣不寫快取（熔斷與缺設定不得焊住）。
 * - 空 query 直接委派（`Unavailable(EMPTY_QUERY)`），不進 cache／in-flight 表。
 */
class WebSearchService(
    private val delegate: WebSearchProvider,
    private val cache: WebSearchCache = WebSearchCache(),
) {
    val providerId: String get() = delegate.id

    private val mutex = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<WebSearchOutcome>>()

    suspend fun search(query: String, count: Int): WebSearchOutcome {
        if (query.isBlank()) return delegate.search(query, count)
        val n = WebSearchBudgets.countOf(count)
        val key = cache.key(delegate.id, query, n)
        cache.get(delegate.id, query, n)?.let { return it.copy(cached = true) }

        val mine: CompletableDeferred<WebSearchOutcome>
        val leader: Boolean
        mutex.withLock {
            val existing = inFlight[key]
            if (existing != null) {
                mine = existing
                leader = false
            } else {
                mine = CompletableDeferred()
                inFlight[key] = mine
                leader = true
            }
        }
        if (!leader) return mine.await()

        try {
            val outcome = delegate.search(query, n)
            if (outcome is WebSearchOutcome.Ok) {
                cache.put(delegate.id, query, n, outcome)
            }
            mine.complete(outcome)
            return outcome
        } catch (e: CancellationException) {
            mine.completeExceptionally(e)
            throw e
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            // 取消狀態下仍須清 inFlight，否則殘留已取消的 deferred 會焊住後續同 key。
            withContext(NonCancellable) {
                mutex.withLock {
                    if (inFlight[key] === mine) inFlight.remove(key)
                }
            }
        }
    }
}
