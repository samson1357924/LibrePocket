package dev.librepocket.privilege.github

import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import rikka.shizuku.Shizuku

private const val TAG = "ShizukuPermissionRequests"

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
        /** outstanding 回呼表的總筆數上限（超限時 [requestWithCallback] 拒絕並回傳 false）。 */
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
    }

    private val callbacks = mutableMapOf<Int, MutableList<(Boolean) -> Unit>>()
    private val lock = Any()

    private var activeEpoch = 0L

    /** 是否已向上游註冊結果 listener（冪等守衛，僅 [attach]/[detach]/[clear] 內讀寫）。 */
    private var attached = false

    /**
     * 暫存的上游 listener 引用：attach 建新並以同一物件註冊，
     * detach 以同一引用反註冊；讀寫皆在 [lock] 下，失敗/解除即置 null。
     */
    private var resultListener: Shizuku.OnRequestPermissionResultListener? = null

    /**
     * 按 requestCode 登記授權結果回呼（#136-B3 接線：回呼內做 grant 持久化＋審計）。
     *
     * 低階多回呼語義（B3 接線用）：同一 requestCode 可登記多筆回呼，結果到達時
     * 逐筆分發；每筆回呼執行前仍會重查 epoch（作廢後不再執行）。產品路徑的
     * 請求＋回呼原子登記請用 [requestWithCallback]（含 attach 門檻與同碼隔離）。
     * 注意：此處登記的回呼計入 [MAX_OUTSTANDING] 總額，且同碼登記會擋住後續
     * [requestWithCallback] 對同 requestCode 的請求（同碼隔離以回呼表非空為準）。
     */
    fun onResult(requestCode: Int, callback: (granted: Boolean) -> Unit) {
        synchronized(lock) {
            callbacks.getOrPut(requestCode) { mutableListOf() } += callback
        }
    }

    /**
     * 送出授權請求（預設走真實 `Shizuku.requestPermission`）。
     *
     * 未成功 [attach] 時 fail-closed：不送出、回傳 false；[detach] 後需重新
     * [attach] 才能再請求（attach 失敗可重試）。
     *
     * @return 請求是否已送出（未 attach 或送出時抛異常即 false，fail-closed；true 不代表
     * 已授權，結果只經 [onResult] 回呼到達）。`CancellationException` 會原樣傳播（不折成 false）。
     */
    fun request(
        requestCode: Int,
        requestPermission: (Int) -> Unit = { code -> Shizuku.requestPermission(code) },
    ): Boolean {
        if (!synchronized(lock) { attached }) return false
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
     */
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

    /** 僅供測試驅動 dispatch 路徑用；無 production caller；勿在產品碼呼叫。 */
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

    /**
     * 清空本地回呼表並重置 [attached] 守衛與暫存 listener 引用
     * （synchronized；測試/生命週期重建用）。
     *
     * 注意：此函數不觸碰上游註冊（純 JVM，JVM 單測安全）；
     * 產品路徑請用 [detach] 以同時反註冊上游，避免上游殘留累積。
     */
    internal fun clear() {
        synchronized(lock) { clearLocked() }
    }

    private fun clearLocked() {
        activeEpoch++
        callbacks.clear()
        resultListener = null
        attached = false
    }
}
