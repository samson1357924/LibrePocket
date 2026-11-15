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
 *   否則視為 leader 死亡，迴圈重選（成為新 leader 重打 upstream）。重選一律回到當前 vault
 *   重解析——sticky 沿用僅限「尚未被 follower 重選」的當次領導者選舉；巢狀 sticky 在
 *   follower 重選時同樣退回 [WebSearchProvider.credentialState] 重驗（N1 修復），
 *   已刪除憑證在巢狀路徑同樣不會被重送。in-flight 移除
 *   仍在 `NonCancellable` finally 內，殘留已取消的 deferred 不會焊住後續同 key。
 * - leader 已拿到值才觀察到取消（delegate 回 `Ok` 後 `ensureActive()` 失敗）：
 *   不寫快取、leader 仍拋 [CancellationException]，但已在等待的 follower 仍以該
 *   `Ok`（非 cached）完成，不陪葬重打。
 * - 非取消異常（delegate 拋 `Exception`，如吞掉取消的壞 delegate 或程式錯誤）映射為
 *   `Failed(TRANSPORT)` 值回傳（fail-closed，允許非 pinned fallback），不 raw throw；
 *   `Error` 仍 `completeExceptionally` 後原樣拋出。
 * - 空 query 直接委派（`Unavailable(EMPTY_QUERY)`），不進 cache／in-flight 表，取消語義與旁路一致。
 * - Cache identity 綁定同一份不可變 credential snapshot（F1）：以 [WebSearchProvider.credentialState]
 *   單次解析（keyed provider），[CredentialState.Ready] 的 snapshot 同時供 fingerprint 與 HTTP 請求使用
 *   （request 與 cache identity 同源）；[CredentialState.Unavailable]（keyed vault 暫時故障／缺 key，
 *   憑證狀態未知）→ 完全旁路快取（不讀不寫、不進 single-flight、不寫快取，uncacheable）；
 *   [CredentialState.NotApplicable]（非 keyed provider）→ plain cache key，行為完全不變。
 * - Follower 重選會重讀「當前」vault：重試時 [WebSearchProvider.credentialState] 重新解析，
 *   不沿用已輪換／已刪除的舊憑證；刪除的憑證絕不會在重試時被重送（resolve 不到 → 旁路 → delegate `search()`
 *   回 MISSING_KEY）。刪除憑證由 keyed delegate 零網路收斂。巢狀 sticky 在重選時同樣退回當前 vault 重解析。
 * - 巢狀 Service 透傳同一份 snapshot：[searchWithSnapshot] 沿用外層已解析的同一份 snapshot 繼續處理
 *   （sticky；僅限當次領導者選舉；follower 重選退回當前 vault 重解析），所有權不轉移、本層絕不 wipe（最外層持有者於 `finally` 負責）；
 *   每個 nesting 層級各自執行 cache／single-flight，fingerprint 一致故不會互撞。
 * - 快取失敗降級為 miss：[WebSearchCache] 讀取失敗（CancellationException 除外）一律視為 cache miss，不影響回傳值（故障注入測試 pin 住）；
 *   寫入失敗保留上游 `Ok`，絕不改寫為 `Failed`——cache 是純最佳化層，讀寫失敗都不影響回傳值。
 * - in-flight 重選會忽略已完成（isCompleted）的 deferred——leader 完成與從 inFlight 移除之間存在窗口，
 *   follower 可能看到 corpse；重選後若 outcome 未寫入快取（如寫入失敗）且快取未命中，
 *   會對上游重複請求，屬可接受的可用性取捨。
 * - 巢狀多層共用同一 [WebSearchCache]：每層各自 single-flight，無法跨層合流，
 *   共用時可能對上游重複請求兩次——建議只在最外層掛 cache。
 * - 非符合契約之 delegate 若 credentialState 拋出 raw Exception：CE 原樣透傳、
 *   其他一律映射為旁路（[CredentialState.Unavailable]），不把例外直接拋給呼叫端。
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

    /** 透傳 delegate 的憑證狀態解析（巢狀 Service 包裝時 cache identity 不遺失）。 */
    override suspend fun credentialState(): CredentialState = delegate.credentialState()

    @Deprecated("Use id", ReplaceWith("id"))
    val providerId: String get() = delegate.id

    /** 清空底層快取（委派至 `cache.clear()`）。 */
    fun invalidate() = cache.clear()

    /**
     * 快取是純最佳化層；讀取失敗降級為 miss，不得把失敗寫進回傳值。
     * CE 原樣透傳（取消絕不降級為 miss）；其他例外一律視為 miss。
     */
    private fun safeGet(cacheKey: String): WebSearchOutcome.Ok? = try {
        cache.getByKey(cacheKey)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    // 旁路（uncacheable）共用：不查快取、不 single-flight、不寫快取；
    // 對 delegate 的 raw Exception 映射為 Failed(TRANSPORT)（fail-closed），CE 原樣透傳；
    // 壞 delegate 吞掉取消仍回值／把取消表現成一般例外時，central ensureActive() 讓 CE
    // 原樣透傳，絕不把已取消的結果當正常值（或可 fallback 的 TRANSPORT）回傳。
    private suspend fun bypass(query: String, n: Int): WebSearchOutcome = try {
        val outcome = delegate.search(query, n)
        // delegate 正常返回但外部已取消（吞掉 CE 的壞 delegate）：CE 原樣透傳。
        currentCoroutineContext().ensureActive()
        outcome
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // 外部已取消時 CE 透傳（不得映射 TRANSPORT）；否則 mapping 為 Failed(TRANSPORT)。
        currentCoroutineContext().ensureActive()
        WebSearchOutcome.Failed(WebFailureReason.TRANSPORT)
    }

    private val mutex = Mutex()
    private val inFlight: MutableMap<String, CompletableDeferred<WebSearchOutcome>> = mutableMapOf()

    override suspend fun search(query: String, count: Int): WebSearchOutcome {
        if (query.isBlank()) {
            currentCoroutineContext().ensureActive()
            return bypass(query, count)
        }
        return searchInternal(query, WebSearchBudgets.countOf(count), null)
    }

    /**
     * 巢狀 Service 入口：沿用外層已解析的同一份 [snapshot]（sticky）繼續處理。
     * 所有權不在本函數；絕不 wipe 傳入的 snapshot（由最外層持有者於 `finally` 負責）。
     * 首輪沿用外層 snapshot 即本層的 cache identity 與 HTTP 依據（不重讀 vault）；
     * 唯 follower 重選（inner leader 取消）後，本層退回當前 vault 重解析——已刪除的憑證不會被重送，見 [searchInternal] 與 N1 修復。
     * 非符合契約之 keyed 子類務必覆寫 searchWithSnapshot（預設實作回 `Unavailable(MISSING_KEY)`、零 HTTP——功能性靜默失效，非安全漏洞）。
     */
    override suspend fun searchWithSnapshot(
        snapshot: CredentialSnapshot,
        query: String,
        count: Int,
    ): WebSearchOutcome {
        if (query.isBlank()) {
            currentCoroutineContext().ensureActive()
            return bypass(query, count)
        }
        return searchInternal(query, WebSearchBudgets.countOf(count), snapshot)
    }

    /**
     * 搜尋主迴圈。每個迴圈迭代重新解析「當前」憑證狀態（本迭代未降級時沿用外層持有的 sticky——
     * 所有權不在此，絕不 wipe）；follower 重選會把本次降級為 null，下一迭代改重讀當前 vault，
     * 不重用過期憑證。
     */
    private suspend fun searchInternal(
        query: String,
        n: Int,
        sticky: CredentialSnapshot?,
    ): WebSearchOutcome {
        // sticky 只在「尚未被 follower 重選」的單次領導者/跟隨者生命週期內沿用；
        // follower 重選代表上游已死，最低限度的重驗方式是轉回當前 vault 重解析，
        // 絕不以未重驗的過期 snapshot 再次 egress。
        var iterationSticky = sticky
        while (true) {
            val stickyIter = iterationSticky
            val state = if (stickyIter != null) {
                CredentialState.Ready(stickyIter)
            } else {
                try {
                    delegate.credentialState()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    CredentialState.Unavailable
                }
            }
            // CE-swallowing delegate 在已取消 context 下 credentialState() 仍「正常返回」時，
            // 此 ensureActive() 讓取消原樣透傳（覆蓋後續 cache hit 與其下游所有路徑）。
            currentCoroutineContext().ensureActive()
            // snapshot 於 try 內指派（含 cacheKey 建構／cacheNamespace 取用）——
            // 任何路徑（cache hit、join、leader 收斂、例外、retry）皆由單一 finally 負責 wipe。
            var snapshot: CredentialSnapshot? = null
            var retry = false
            try {
                snapshot = when (state) {
                    CredentialState.Unavailable -> return bypass(query, n)
                    CredentialState.NotApplicable -> null // plain cache key，行為不變
                    is CredentialState.Ready -> state.snapshot
                    else -> {
                        // 未來新增子型式時 fail-closed（預設旁路）
                        return bypass(query, n)
                    }
                }
                val cacheKey = try {
                    cache.key(delegate.cacheNamespace, query, n)
                        .let { if (snapshot != null) "$it#rev:${snapshot.fingerprint}" else it }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // delegate 的 cacheNamespace getter 拋出（壞 delegate）：fail-closed；
                    // finally 仍會 wipe snapshot。
                    currentCoroutineContext().ensureActive()
                    return WebSearchOutcome.Failed(WebFailureReason.TRANSPORT)
                }
                safeGet(cacheKey)?.let {
                    currentCoroutineContext().ensureActive()
                    return it.copy(cached = true)
                }

                var lateHit: WebSearchOutcome.Ok? = null
                var mine: CompletableDeferred<WebSearchOutcome>? = null
                var leader = false
                mutex.withLock {
                    lateHit = safeGet(cacheKey) // re-check under lock closes check-then-elect race
                    if (lateHit == null) {
                        // 已完成（含例外完成）的 deferred 視為無 flight，重新選舉（F-1）。
                        val existing = inFlight[cacheKey]?.takeIf { !it.isCompleted }
                        if (existing != null) {
                            mine = existing
                        } else {
                            val fresh = CompletableDeferred<WebSearchOutcome>()
                            mine = fresh
                            inFlight[cacheKey] = fresh
                            leader = true
                        }
                    }
                }
                lateHit?.let {
                    currentCoroutineContext().ensureActive()
                    return it.copy(cached = true)
                }
                val m = mine
                if (m == null) {
                    // 防禦性分支：正常不可達（lateHit == null ⇒ 已選舉／加入 in-flight）；重解析避免以未選舉的狀態繼續。
                    iterationSticky = null // 一律退回當前 vault 重解析（含此防禦性分支）
                    retry = true
                } else if (!leader) {
                    try {
                        val outcome = m.await()
                        currentCoroutineContext().ensureActive()
                        return outcome
                    } catch (_: CancellationException) {
                        // Follower 自己被取消 → ensureActive 拋出，原樣透傳；
                        // 否則是 leader 死亡 → 迴圈重選（成為新 leader），重解析當前憑證狀態。
                        currentCoroutineContext().ensureActive()
                        // follower 重選代表上游已死：退回當前 vault 重解析（巢狀 sticky 亦然，
                        // 不重用過期 snapshot），絕不以未重驗的過期 snapshot 再次 egress（N1 修復）。
                        iterationSticky = null
                        retry = true
                    }
                } else {
                    var okForFollowers: WebSearchOutcome.Ok? = null
                    try {
                        // request 與 cache identity 同源：snapshot 存在 → 同一 key；
                        // 不再讀 vault（F1）。
                        val outcome = if (snapshot != null) {
                            delegate.searchWithSnapshot(snapshot, query, n)
                        } else {
                            delegate.search(query, n)
                        }
                        if (outcome is WebSearchOutcome.Ok) okForFollowers = outcome
                        // 吞掉取消的壞 delegate：已取消仍回值時不得寫 cache。
                        currentCoroutineContext().ensureActive()
                        if (outcome is WebSearchOutcome.Ok) {
                            try {
                                cache.putByKey(cacheKey, outcome)
                            } catch (e: CancellationException) {
                                // 讀側 safeGet 與本寫側皆不吞 CancellationException（合成 CE 與使用者取消同等透傳；Ok 由 leader 的 CE 分支以 okForFollowers 完成給 follower）。
                                throw e
                            } catch (_: Exception) {
                                // 快取寫入失敗是純最佳化層失敗：保持 Ok，不得改寫成 Failed
                            }
                        }
                        m.complete(outcome)
                        return outcome
                    } catch (e: CancellationException) {
                        // Leader 已拿到 Ok 才觀察到取消：follower 仍值得該值（不寫 cache），
                        // leader 自己拋 CE；否則 follower 會重試，不污染。
                        val ok = okForFollowers
                        if (ok != null) {
                            m.complete(ok)
                        } else {
                            m.completeExceptionally(e)
                        }
                        throw e
                    } catch (_: Exception) {
                        try {
                            currentCoroutineContext().ensureActive()
                        } catch (ce: CancellationException) {
                            m.completeExceptionally(ce)
                            throw ce
                        }
                        val failed = WebSearchOutcome.Failed(WebFailureReason.TRANSPORT)
                        m.complete(failed)
                        return failed
                    } catch (e: Throwable) {
                        m.completeExceptionally(e)
                        throw e
                    } finally {
                        // 取消狀態下仍須清 inFlight，否則殘留已取消的 deferred 會焊住後續同 key。
                        withContext(NonCancellable) {
                            mutex.withLock {
                                if (inFlight[cacheKey] === m) inFlight.remove(cacheKey)
                            }
                        }
                    }
                }
            } finally {
                // sticky 由外層持有 → 絕不 wipe；否則本層 delete 掉自己解析的 snapshot。
                // ownership 以「本次迭代」為準：本迭代接收外層 sticky（stickyIter != null）
                // 絕不 wipe；本迭代自 vault（重新）解析時（stickyIter == null）才 wipe 本迭代的 snapshot。
                if (stickyIter == null) snapshot?.wipe()
            }
            if (!retry) error("unreachable") // 所有路徑皆已 return
        }
    }
}
