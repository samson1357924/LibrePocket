package dev.librepocket.privilege.github

import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import rikka.shizuku.Shizuku

private const val TAG = "ShizukuPermissionRequests"

/** github 源集專用錯誤碼命名空間（值與 main 側 PrivilegeBridgeErrors 相互獨立）。 */
internal object ShizukuRequestErrors {
    /** 碼空間耗盡（候選越過 Int.MAX_VALUE；fail-closed）。 */
    const val REQUEST_CODE_SPACE_EXHAUSTED = "REQUEST_CODE_SPACE_EXHAUSTED"
}

/**
 * android.util.Log 在非 Robolectric 的 JVM 單元測試中未 mock（呼叫即抛
 * RuntimeException）；日誌絕不得影響 fail-closed 控制流，故包一層容錯。
 */
private fun safeLogW(tag: String, msg: String, e: Throwable? = null) {
    try {
        if (e != null) {
            Log.w(tag, msg, e)
        } else {
            Log.w(tag, msg)
        }
    } catch (_: Throwable) {
        // 單元測試環境無 android.util.Log：吞掉
    }
}

/**
 * Shizuku 授權請求通道（#136-B1，BACKLOG D09，僅 github 風味）。
 *
 * 補齊 [ShizukuStatusProbe] 只讀側（`checkSelfPermission`）之外的請求側：
 * [request] 送出 `Shizuku.requestPermission(requestCode)`，授權結果經
 * 上游 `OnRequestPermissionResultListener` 非同步回調，按 requestCode 分發
 * 至 [onResult] 登記的回呼（`grantResult == PERMISSION_GRANTED` 即已授權）。
 *
 * - [request] 回傳值僅代表「請求已送出」，不等於已授權：送出時抛異常
 *   （未安裝／binder 死亡）回 false（fail-closed，未送出），授權與否一律經
 *   回呼到達；呼叫方不得以回傳值作放行依據；
 * - 回呼異常隔離（逐個 runCatching），未知 requestCode 即 no-op；
 * - 真實 `requestPermission`／listener 註冊只在預設參數／[attach] 內觸發
 *   （產品路徑，需 binder 環境）；狀態分發走純 JVM 回呼表，
 *   單測以注入假請求＋[emitResult] 驅動，零真機、零 su、零真連線；
 * - 禁止快取結果作放行：回呼內只做授權持久化（`ShizukuGrantStore.save()`，
 *   由 #136-B3 產品接線）＋審計，不得存狀態複用；放行一律每次 call 經
 *   [ShizukuStatusProbe.status] 新鮮求值（見 [ShizukuBinderEvents] 立約）。
 *
 * 重試語義與生命週期（[attach]/[detach]/守衛）同 [ShizukuBinderEvents]：
 * [attach] 僅在上游註冊成功後才將守衛置 true，失敗即回滾並保持 false，
 * 呼叫方可重試；JVM 單測下 Shizuku 類初始化即失敗，attach 恆走失敗路徑。
 *
 * requestCode 生命週期（本類保證）：本實例生存期內（經 [process] 取得的單例
 * ＝行程生存期；單測新實例＝實例生存期），任何 requestCode 一旦被使用即永久
 * burn——僅增不減、永不復原（含 detach/reattach、revoke、送出失敗與登記回滾；
 * 失敗與回滾照燒，fail-closed）。[allocateRequestCode] 為嚴格遞增 allocator
 * （起點 1、跳過 burned 碼）；發出不 burn，碼於首次送出／登記才照燒
 * （與呼叫方自選碼同一規則）；耗盡（候選越過 Int.MAX_VALUE）拋出
 * `IllegalStateException(ShizukuRequestErrors.REQUEST_CODE_SPACE_EXHAUSTED)`（fail-closed）。
 * 呼叫方自選碼經 [request]／[requestWithCallback]／[onResult] 註冊或送出時照燒。
 * 殘餘邊界：行程重啟後碼空間自 1 重新開始（含呼叫方自選碼）——stale pending
 * dialog 在重啟後答覆，可把舊碼送達新行程的 listener；唯一緩解是 B3 落庫前的
 * final-commit fence（`ShizukuGrantStore.save()` 前即時持久重查），為 B3 啟用的
 * 不可通融硬性前置（見 docs/THREAT_MODEL.md 第 4 項）。
 *
 * attach 成功後才能請求：[request]/[requestWithCallback] 在未成功 attach
 * （含從未 attach、已 [detach]）時一律 fail-closed 回傳 false，不登記任何回呼；
 * [detach] 會反註冊上游 listener 並作廢進行中請求（遞增 epoch＋清空回呼表），
 * 過期 listener 的回呼因 listener 身份檢查不會分發到新 epoch。
 * 一個 requestCode 同時僅一個 outstanding 回呼（見 [requestWithCallback]）。
 *
 * 誠實邊界：Shizuku 授權（Manager 內的使用者允許）是「使用者把特權委託給
 * 本 app」的開關，不等於 OS sandbox——Root／特權語義見兩 runner KDoc，
 * 本類不作執行，不承諾隔離。
 */
class ShizukuPermissionRequests(
    private val addListener: (Shizuku.OnRequestPermissionResultListener) -> Unit = { Shizuku.addRequestPermissionResultListener(it) },
    private val removeListener: (Shizuku.OnRequestPermissionResultListener) -> Unit = { Shizuku.removeRequestPermissionResultListener(it) },
) {
    companion object {
        /** outstanding 回呼表的總筆數上限（超限時 [requestWithCallback]／[onResult]／[request] 拒絕並回傳 false）。 */
        const val MAX_OUTSTANDING = 32

        @Volatile
        private var processInstance: ShizukuPermissionRequests? = null

        fun process(): ShizukuPermissionRequests =
            processInstance ?: synchronized(this) {
                processInstance ?: ShizukuPermissionRequests().also { processInstance = it }
            }

        @VisibleForTesting
        fun resetProcessForTest() {
            synchronized(this) {
                processInstance?.detach()
                processInstance = null
            }
        }

        /**
         * 僅單測隔離用：以指定實例取代行程單例（供注入假 addListener/removeListener 的
         * 實例進 [process] 語義）；僅模組內可見、production 不得調用。取代前先 detach 舊實例，
         * 與 [resetProcessForTest] 相同的隔離語義。
         *
         * 注意：舊實例 detach() 若抛出非 Exception／LinkageError 的 Throwable
         *（detach() 刻意放行），例外會原樣上拋且 processInstance 仍綁定舊實例——
         * fail-loud，不會洩漏 listener（舊實例已先 clearLocked 再 removeListener）。
         */
        @VisibleForTesting
        internal fun setProcessForTest(instance: ShizukuPermissionRequests) {
            synchronized(this) {
                processInstance?.detach()
                processInstance = instance
            }
        }
    }

    private val callbacks = mutableMapOf<Int, MutableList<(Boolean) -> Unit>>()

    /**
     * 已使用（burn）的 requestCode：僅增不減，永不清空、永不復原
     * （含 detach/reattach、revoke、送出失敗與登記回滾；fail-closed）。
     * 讀寫皆在 [lock] 下。
     */
    private val burnedCodes = HashSet<Int>()

    private val lock = Any()

    private var activeEpoch = 0L

    /** 嚴格遞增 allocator 的下一個候選起點（僅增；burned 跳過）。讀寫皆在 [lock] 下。 */
    private var requestCodeCounter = 1L

    /** 是否已向上游註冊結果 listener（冪等守衛，僅 [attach]/[detach]/[clear] 內讀寫）。 */
    private var attached = false

    /**
     * 暫存的上游 listener 引用：attach 建新並以同一物件註冊，
     * detach 以同一引用反註冊；讀寫皆在 [lock] 下，失敗/解除即置 null。
     */
    private var resultListener: Shizuku.OnRequestPermissionResultListener? = null

    /**
     * 按 requestCode 登記授權結果回呼（僅測試用驅動分發表；產品路徑請用
     * [requestWithCallback] 原子登記）。
     *
     * 僅測試用驅動（test-only）：無 production caller，B3 不得經此接線；
     * `otherwise = PRIVATE`（模組內僅單測可見）。
     *
     * 多回呼語義：同一 requestCode 可登記多筆回呼，結果到達時按登記順序逐筆分發；
     * 每筆回呼執行前仍會重查 epoch（作廢後不再執行）。產品路徑的請求＋回呼原子
     * 登記請用 [requestWithCallback]（含 attach 門檻與同碼隔離）。
     * [MAX_OUTSTANDING] 總額與 [requestWithCallback] 共用同一計數（表內全部回呼
     * 筆數達上限即拒絕並回傳 false）；同碼登記會擋住後續 [requestWithCallback]
     * 對同 requestCode 的請求（同碼隔離以回呼表非空為準）。
     *
     * requestCode burn-on-use：首次 live 登記即 burn 該碼（本實例內此後不得再用）；
     * 碼已 burn 且當下無 live（outstanding）回呼時拒絕並回傳 false（防止消費後
     * 重新武裝舊碼）；碼已 burn 但仍有 live 回呼時可追加登記（多回呼語義，
     * 不重複 burn）。
     *
     * @return 登記是否被接受（false＝已達 [MAX_OUTSTANDING] 上限，或碼已 burn
     * 且當下無 live 回呼）。
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun onResult(requestCode: Int, callback: (granted: Boolean) -> Unit): Boolean {
        return synchronized(lock) {
            var outstanding = 0
            for (list in callbacks.values) outstanding += list.size
            if (outstanding >= MAX_OUTSTANDING) {
                safeLogW(TAG, "onResult rejected: outstanding capacity exhausted")
                return@synchronized false
            }
            val live = callbacks[requestCode]?.isNotEmpty() == true
            if (burnedCodes.contains(requestCode) && !live) {
                safeLogW(TAG, "onResult rejected: requestCode burned and no live callbacks")
                return@synchronized false
            }
            callbacks.getOrPut(requestCode) { mutableListOf() } += callback
            burnedCodes.add(requestCode)
            true
        }
    }

    /**
     * 送出授權請求（低階 fire-and-forget；產品路徑請用 [requestWithCallback]）。
     *
     * 裸送本函數不登記任何回呼、不保存任何結果、不具 grant 語義；結果只能由
     * [requestWithCallback]／[onResult] 登記的回呼取得。
     * 未成功 [attach]（含從未 attach、已 [detach]）時 fail-closed：不送出、回傳 false；
     * [detach] 後需重新 [attach] 才能再請求（attach 失敗可重試）。
     * 送出即 burn 該碼（送出失敗照燒、不復原）——本實例內此後不得再用；
     * 同碼已有 outstanding 回呼、或 outstanding 總額已達 [MAX_OUTSTANDING] 上限
     * 亦拒絕並回傳 false。
     *
     * 二段式（本函數＋另行 [onResult] 登記）自始不可行：裸送即 burn 該碼
     * （本實例生存期內永久），故之後對同碼 [onResult] 登記因「burned 且無 live 回呼」
     * 一律回傳 false——這是確定性結果，不是競態或時序問題。`request()` 回傳 true
     * 僅代表「請求已送出，結果在本實例內不可觀測」；需要回呼必須用
     * [requestWithCallback]（原子登記＋送出）。勿試圖「先 [request] 再 [onResult]
     * 補救」——此為文件誤用，修復請改用 [requestWithCallback]。
     * [detach] 僅作廢本地回呼表與 epoch，無法取消伺服器／系統側已送出的請求
     * （server 端不存在取消路徑）。呼叫方無法將結果歸屬到裸送出的請求
     * （同實例內裸送碼已 burn、無法再登記）；不得以裸 `request()` 搭建 grant 流程。
     * 跨行程重啟的碼空間重新開始是殘餘風險，見類別 KDoc 的 requestCode 生命週期段。
     *
     * @return 請求是否已送出（未 attach／碼已 burn／同碼 outstanding／總額超限／
     * 送出時抛異常即 false，fail-closed；true 不代表已授權，結果只經回呼到達）。
     * `CancellationException` 會原樣傳播（不折成 false）。
     */
    fun request(
        requestCode: Int,
        requestPermission: (Int) -> Unit = { code -> Shizuku.requestPermission(code) },
    ): Boolean {
        synchronized(lock) {
            if (!attached) {
                safeLogW(TAG, "request rejected: not attached")
                return false
            }
            if (burnedCodes.contains(requestCode)) {
                safeLogW(TAG, "request rejected: requestCode burned")
                return false
            }
            if (callbacks[requestCode]?.isNotEmpty() == true) {
                safeLogW(TAG, "request rejected: requestCode in use")
                return false
            }
            var outstanding = 0
            for (list in callbacks.values) outstanding += list.size
            if (outstanding >= MAX_OUTSTANDING) {
                safeLogW(TAG, "request rejected: outstanding capacity exhausted")
                return false
            }
            // 送出前 burn（失敗照燒、不復原，fail-closed）
            burnedCodes.add(requestCode)
        }
        return try {
            requestPermission(requestCode)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    private fun rollbackCallback(requestCode: Int, callback: (Boolean) -> Unit) {
        synchronized(lock) {
            val list = callbacks[requestCode]
            list?.remove(callback)
            if (list.isNullOrEmpty()) callbacks.remove(requestCode)
        }
    }

    /**
     * 原子登記回呼並送出授權請求。
     *
     * 未成功 [attach]（含從未 attach、已 [detach]）時 fail-closed：不登記、不送出、
     * 回傳 false；[attach] 失敗可重試，detach 後需重新 attach 才可再請求。
     * 同一 requestCode 已有 outstanding 回呼時拒絕並回傳 false（不登記）——
     * 一個 requestCode 同時僅一個 outstanding 回呼；需並行請求用不同 requestCode。
     * outstanding 回呼總數上限 [MAX_OUTSTANDING]，超限回傳 false。
     * requestCode 送出即 burn（送出失敗與登記回滾照燒、不復原）：本實例內此後
     * 不得再用（見類別 KDoc 的 requestCode 生命週期段）。
     * 若送出異常或失敗，自動回滾並移除已登記的回呼。
     * 回呼登記與實際送出並非對 detach 原子：detach 可能先於送出發生，
     * 屆時登記的回呼會隨表清空被丟棄、結果不再分發（方向 fail-closed）。
     */
    fun requestWithCallback(
        requestCode: Int,
        callback: (Boolean) -> Unit,
        requestPermission: (Int) -> Unit = { code -> Shizuku.requestPermission(code) },
    ): Boolean {
        synchronized(lock) {
            if (!attached) {
                safeLogW(TAG, "requestWithCallback rejected: not attached")
                return false
            }
            if (callbacks[requestCode]?.isNotEmpty() == true) {
                safeLogW(TAG, "requestWithCallback rejected: requestCode in use")
                return false
            }
            var outstanding = 0
            for (list in callbacks.values) outstanding += list.size
            if (outstanding >= MAX_OUTSTANDING) {
                safeLogW(TAG, "requestWithCallback rejected: outstanding capacity exhausted")
                return false
            }
            if (burnedCodes.contains(requestCode)) {
                safeLogW(TAG, "requestWithCallback rejected: requestCode burned")
                return false
            }
            // 送出前 burn（登記回滾仍照燒、不復原，fail-closed）
            burnedCodes.add(requestCode)
            callbacks.getOrPut(requestCode) { mutableListOf() } += callback
        }
        val success = try {
            requestPermission(requestCode)
            true
        } catch (e: CancellationException) {
            rollbackCallback(requestCode, callback)
            throw e
        } catch (_: Throwable) {
            rollbackCallback(requestCode, callback)
            false
        }
        return success
    }

    /**
     * 作廢所有進行中的請求：遞增 activeEpoch 並清空回呼表（synchronized）。
     *
     * 僅測試用；production 請用 [detach]（同時反註冊上游 listener，避免上游殘留）。
     */
    @VisibleForTesting
    fun invalidatePending() {
        synchronized(lock) {
            activeEpoch++
            callbacks.clear()
        }
    }

    /**
     * 是否已成功註冊上游 listener（守衛讀取；供單測斷言重試/重建語義）。
     */
    fun isAttached(): Boolean = synchronized(lock) { attached }

    @VisibleForTesting
    internal fun currentEpoch(): Long = synchronized(lock) { activeEpoch }

    /**
     * 嚴格遞增 requestCode allocator（起點 1，跳過 burned 碼）；發出不保留、不燒碼。
     *
     * allocation 本身【不 burn】：本函數僅保證回傳的碼在本實例內「發出當下」未 burn
     * （嚴格遞增、跳過 burned 碼）。發出不登記、不送出、不佔位——碼於首次實際
     * 送出／登記時才照本實例 burn 規則燒掉（與呼叫方自選碼同一規則；
     * 送出失敗與登記回滾照燒）。僅發出而從未消耗（未送出／未登記）的碼
     * 對本實例完全無影響（inert）：不擋其他呼叫方、不擋後續分配
     * （計數器一律前進，絕不復發同一候選）。
     * 呼叫方（含 B3）應恰為每次請求分配一次，並在恰唯一一次送出／登記中消耗；
     * 發出後若被其他呼叫方先燒（同碼自選送出／登記），消耗時將被 burn 閘拒絕
     * （fail-closed，呼叫方需重新分配）。凍結保證僅在單一實例內有效——
     * 行程重啟後碼空間自 1 重新開始（見類別 KDoc 的 requestCode 生命週期段）。
     * 跳過掃描的最壞情況為線性於「高於計數器的 burned 碼數」
     * （上界為 Int.MAX_VALUE − counter；實務上 burned 碼由單一遞增源維護，
     * 掃描長度可忽略）。
     *
     * @throws IllegalStateException 碼空間耗盡（候選越過 [Int.MAX_VALUE]），
     * message 為 [ShizukuRequestErrors.REQUEST_CODE_SPACE_EXHAUSTED]（fail-closed；計數器釘在
     * Int.MAX_VALUE + 1，後續呼叫必再拋同一例外）。
     */
    @VisibleForTesting
    internal fun allocateRequestCode(): Int = synchronized(lock) {
        var candidate = requestCodeCounter
        while (candidate <= Int.MAX_VALUE.toLong() && burnedCodes.contains(candidate.toInt())) {
            candidate++
        }
        if (candidate > Int.MAX_VALUE.toLong()) {
            // 釘住計數器：後續呼叫必再判 exhausted（fail-closed、可重入）
            requestCodeCounter = Int.MAX_VALUE.toLong() + 1
            throw IllegalStateException(ShizukuRequestErrors.REQUEST_CODE_SPACE_EXHAUSTED)
        }
        val code = candidate.toInt()
        requestCodeCounter = candidate + 1
        code
    }

    /**
     * 測試專用：把 allocator 種子推進到 [next]，用於近耗盡（exhaustion）場景。
     * production 不得調用；僅單測可見；[next] 不得小於目前計數器（僅允許向前重定位）。
     */
    @VisibleForTesting
    internal fun seedRequestCodeCounterForTest(next: Int) {
        require(next >= 1) { "SEED_MUST_BE_POSITIVE" }
        synchronized(lock) {
            require(next.toLong() >= requestCodeCounter) { "SEED_MUST_NOT_REGRESS_COUNTER" }
            requestCodeCounter = next.toLong()
        }
    }

    /**
     * 單次消費回呼分發：按 requestCode 查找並移除回呼（one-shot），
     * 比對 epoch 一致性；每個回呼執行前重查 epoch（作廢後批次中止）；
     * 逐個調用並隔離異常。
     */
    private fun dispatchResult(requestCode: Int, grantResult: Int, epoch: Long) {
        val snapshot: List<(Boolean) -> Unit>
        synchronized(lock) {
            if (epoch != activeEpoch) return
            snapshot = callbacks.remove(requestCode)?.toList() ?: return
        }
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        for (callback in snapshot) {
            // 逐 callback 重查 epoch：快照取出後若被 invalidatePending()/detach()
            // 作廢（含回呼自身在批次中途作廢），後續回呼不得再執行。
            synchronized(lock) { if (epoch != activeEpoch) return }
            try {
                callback(granted)
            } catch (_: Throwable) {
                // 回呼異常隔離（Binder/同步線程中不向外洩漏，避免影響其他回呼）
            }
        }
    }

    /**
     * 僅供測試驅動 dispatch 路徑用；無 production caller；勿在產品碼呼叫。
     * 注意：emitResult 的 epoch 參數重查只能擋「作廢後的過期分發」，無法分辨 stale 與
     * fresh 送達「當前」listener（同一 epoch 內兩者不可分辨）——落庫前的唯一兜底是
     * B3 的 final-commit fence（`ShizukuGrantStore.save()` 前即時重查持久層）。
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun emitResult(
        requestCode: Int,
        grantResult: Int,
        epoch: Long = synchronized(lock) { activeEpoch },
    ) {
        dispatchResult(requestCode, grantResult, epoch)
    }

    /**
     * 產品接線：向上游註冊授權結果 listener，事件轉發進回呼表。
     * 冪等：成功後重複呼叫只註冊一次（[attached] 守衛）；失敗不置守衛，允許重試。
     *
     * 鎖反轉防禦：呼叫 [addListener] 時不持有 [lock]，避免 AB-BA 死鎖；
     * 若在無鎖註冊期間被 [detach]，則主動呼叫 [removeListener] 保持乾淨。
     */
    fun attach() {
        val stagedEpoch: Long
        lateinit var listener: Shizuku.OnRequestPermissionResultListener
        synchronized(lock) {
            if (attached) return
            stagedEpoch = activeEpoch
            listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                // 鎖內讀 attached 與 listener 身份：detach/reattach 後舊 listener 即使
                // 上游反註冊靜默失敗，也不會以新 epoch 分發 stale 結果。
                val epoch = synchronized(lock) {
                    if (!attached) return@OnRequestPermissionResultListener
                    if (resultListener !== listener) return@OnRequestPermissionResultListener
                    activeEpoch
                }
                dispatchResult(requestCode, grantResult, epoch)
            }
        }

        val added = try {
            addListener(listener)
            true
        } catch (e: Exception) {
            safeLogW(TAG, "Shizuku addRequestPermissionResultListener failed", e)
            false
        } catch (e: LinkageError) {
            safeLogW(TAG, "Shizuku addRequestPermissionResultListener failed", e)
            false
        }

        if (!added) return

        val shouldRemove: Boolean
        synchronized(lock) {
            if (activeEpoch != stagedEpoch || attached) {
                // 解鎖期間被 detach / invalidatePending 或已有其它執行緒 attach 成功
                shouldRemove = true
            } else {
                attached = true
                resultListener = listener
                shouldRemove = false
            }
        }

        if (shouldRemove) {
            try {
                removeListener(listener)
            } catch (e: Exception) {
                safeLogW(TAG, "Shizuku removeRequestPermissionResultListener failed", e)
            } catch (e: LinkageError) {
                safeLogW(TAG, "Shizuku removeRequestPermissionResultListener failed", e)
            }
        }
    }

    /**
     * 解除接線：反註冊上游 listener 並清空本地回呼表、丟棄暫存引用、重置守衛。
     * 鎖反轉防禦：在鎖內清空狀態並取出 listener 引用，在鎖外呼叫 [removeListener]。
     * detach 不向外抛出（removeListener 失敗僅記錄日誌）：呼叫端無法得知上游
     * 反註冊是否成功，語義安全由三道閘保證（epoch 作廢／守衛重置／listener 身份檢查）。
     */
    fun detach() {
        val staged: Shizuku.OnRequestPermissionResultListener?
        synchronized(lock) {
            staged = resultListener
            clearLocked()
        }
        if (staged != null) {
            try {
                removeListener(staged)
            } catch (e: Exception) {
                safeLogW(TAG, "Shizuku removeRequestPermissionResultListener failed", e)
            } catch (e: LinkageError) {
                safeLogW(TAG, "Shizuku removeRequestPermissionResultListener failed", e)
            }
        }
    }

    private fun clearLocked() {
        activeEpoch++
        callbacks.clear()
        resultListener = null
        attached = false
    }
}
