package dev.librepocket.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * D2 cache＋single-flight 包裝（#138 Scope：同 query 第二次零 upstream、`cached=true`；
 * 併發同 query 合流為一次 upstream；純 JVM，不接線）。
 *
 * Composable／registry-ready：本類自身實作 [WebSearchProvider]，可直接註冊進
 * [WebSearchRegistry]（[id]／[capabilities]／[probe] 全委派給底層 delegate），
 * 也可巢狀包裝（Service 包 Service；快取按 cacheNamespace＋query＋count 分 key，
 * 不會互撞，見「快取隔離」）。
 *
 * - 命中快取 → `Ok.copy(cached = true)`，零 upstream。
 * - 未命中 → 同 key 併發呼叫共用同一個 upstream（leader 執行、
 *   follower `await()`；follower 取消不影響 leader，只中斷自己的等待）。
 * - 取消語義（#138 Scope：取消不 fallback、不寫 cache）：外部取消原樣透傳，
 *   不寫快取；`Failed`／`Unavailable` 同樣不寫快取（熔斷與缺設定不得焊住）。
 * - follower 重試（leader 取消不污染 follower）：follower `await()` 收到
 *   [CancellationException] 時先 `ensureActive()`——若 follower 自己被取消則原樣拋出；
 *   否則視為 leader 死亡，迴圈重選（成為新 leader 重打 upstream）。in-flight 移除
 *   仍在 `NonCancellable` finally 內，殘留已取消的 deferred 不會焊住後續同 key。
 * - leader 已拿到值才觀察到取消（delegate 回 `Ok` 後 `ensureActive()` 失敗）：
 *   不寫快取、leader 仍拋 [CancellationException]，但已在等待的 follower 仍以該
 *   `Ok`（非 cached）完成，不陪葬重打。
 * - 非取消異常（delegate 拋 `Exception`，如吞掉取消的壞 delegate 或程式錯誤）映射為
 *   `Failed(TRANSPORT)` 值回傳（fail-closed，允許非 pinned fallback），不 raw throw；
 *   `Error` 仍 `completeExceptionally` 後原樣拋出。
 * - 空 query 直接委派（`Unavailable(EMPTY_QUERY)`），不進 cache／in-flight 表。
 * - 快取隔離：預設每個 Service 持有自有 [WebSearchCache]，不同 provider 的
 *   Service 快取互相隔離；如需跨 provider／跨 Service 共用快取，請顯式傳入
 *   同一 [WebSearchCache] 實例。共用快取以 [WebSearchProvider.cacheNamespace]
 *   切 key（非 [WebSearchProvider.id]）：same-scheme same-origin shares hits
 *  （僅本版本內同源共用；key 已改為 namespaced 格式，不跨版本沿用舊條目）；
 *   異源（如兩個 Searxng 不同 baseUrl）不再互撞——同 query 各自打一次 upstream。
 * - `inFlight` 未設硬上限：併發同 key 合流、不同 key 各一 deferred，
 *   實際併發受上游 fetch timeouts 收斂；刻意不設上限以免引入等待正確性風險。
 */
class WebSearchService(
    private val delegate: WebSearchProvider,
    private val cache: WebSearchCache = WebSearchCache(),
) : WebSearchProvider {
    override val id: String get() = delegate.id

    override val cacheNamespace: String get() = delegate.cacheNamespace

    override val capabilities: Set<WebCapability> get() = delegate.capabilities

    override suspend fun probe(): WebProbe = delegate.probe()

    @Deprecated("Use id", ReplaceWith("id"))
    val providerId: String get() = delegate.id

    /** 清空底層快取（委派至 `cache.clear()`）。 */
    fun invalidate() = cache.clear()

    private val mutex = Mutex()
    private val inFlight: MutableMap<String, CompletableDeferred<WebSearchOutcome>> = mutableMapOf()

    override suspend fun search(query: String, count: Int): WebSearchOutcome {
        if (query.isBlank()) return delegate.search(query, count)
        val n = WebSearchBudgets.countOf(count)
        val key = cache.key(delegate.cacheNamespace, query, n)
        while (true) {
            cache.getByKey(key)?.let { return it.copy(cached = true) }

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
            if (!leader) {
                try {
                    return mine.await()
                } catch (_: CancellationException) {
                    // Follower 自己被取消 → ensureActive 拋出，原樣透傳；
                    // 否則是 leader 死亡 → 迴圈重選（成為新 leader）。
                    currentCoroutineContext().ensureActive()
                    continue
                }
            }

            var okForFollowers: WebSearchOutcome.Ok? = null
            try {
                val outcome = delegate.search(query, n)
                if (outcome is WebSearchOutcome.Ok) okForFollowers = outcome
                // 吞掉取消的壞 delegate：已取消仍回值時不得寫 cache。
                currentCoroutineContext().ensureActive()
                if (outcome is WebSearchOutcome.Ok) {
                    cache.putByKey(key, outcome)
                }
                mine.complete(outcome)
                return outcome
            } catch (e: CancellationException) {
                // Leader 已拿到 Ok 才觀察到取消：follower 仍值得該值（不寫 cache），
                // leader 自己拋 CE；否則 follower 會重試，不污染。
                val ok = okForFollowers
                if (ok != null) {
                    mine.complete(ok)
                } else {
                    mine.completeExceptionally(e)
                }
                throw e
            } catch (_: Exception) {
                try { currentCoroutineContext().ensureActive() }
                catch (ce: CancellationException) { mine.completeExceptionally(ce); throw ce }
                val failed = WebSearchOutcome.Failed(WebFailureReason.TRANSPORT)
                mine.complete(failed)
                return failed
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
}
