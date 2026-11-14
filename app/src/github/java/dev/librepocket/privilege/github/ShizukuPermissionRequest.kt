package dev.librepocket.privilege.github

import android.content.pm.PackageManager
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import rikka.shizuku.Shizuku

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
 * 誠實邊界：Shizuku 授權（Manager 內的使用者允許）是「使用者把特權委託給
 * 本 app」的開關，不等於 OS sandbox——Root／特權語義見兩 runner KDoc，
 * 本類不作執行，不承諾隔離。
 */
class ShizukuPermissionRequests(
    private val addListener: (Shizuku.OnRequestPermissionResultListener) -> Unit = { Shizuku.addRequestPermissionResultListener(it) },
    private val removeListener: (Shizuku.OnRequestPermissionResultListener) -> Unit = { Shizuku.removeRequestPermissionResultListener(it) },
) {
    companion object {
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

    /** 按 requestCode 登記授權結果回呼（#136-B3 接線：回呼內做 grant 持久化＋審計）。 */
    fun onResult(requestCode: Int, callback: (granted: Boolean) -> Unit) {
        synchronized(lock) {
            callbacks.getOrPut(requestCode) { mutableListOf() } += callback
        }
    }

    /**
     * 送出授權請求（預設走真實 `Shizuku.requestPermission`）。
     *
     * @return 請求是否已送出（送出時抛異常即 false，fail-closed；true 不代表
     * 已授權，結果只經 [onResult] 回呼到達）。
     */
    fun request(
        requestCode: Int,
        requestPermission: (Int) -> Unit = { code -> Shizuku.requestPermission(code) },
    ): Boolean = runCatching { requestPermission(requestCode) }.isSuccess

    private fun rollbackCallback(requestCode: Int, callback: (Boolean) -> Unit) {
        synchronized(lock) {
            val list = callbacks[requestCode]
            list?.remove(callback)
            if (list.isNullOrEmpty()) callbacks.remove(requestCode)
        }
    }

    /**
     * 原子登記回呼並送出授權請求。
     * 若送出異常或失敗，自動回滾並移除已登記的回呼。
     */
    fun requestWithCallback(
        requestCode: Int,
        callback: (Boolean) -> Unit,
        requestPermission: (Int) -> Unit = { code -> Shizuku.requestPermission(code) },
    ): Boolean {
        synchronized(lock) {
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
     * 比對 epoch 一致性；逐個調用並隔離異常。
     */
    private fun dispatchResult(requestCode: Int, grantResult: Int, epoch: Long) {
        val snapshot: List<(Boolean) -> Unit>
        synchronized(lock) {
            if (epoch != activeEpoch) return
            snapshot = callbacks.remove(requestCode)?.toList() ?: return
        }
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        for (callback in snapshot) {
            try {
                callback(granted)
            } catch (_: Throwable) {
                // 回呼異常隔離（Binder/同步線程中不向外洩漏，避免影響其他回呼）
            }
        }
    }

    /** 僅真實 listener 轉發用（internal：行程外不可偽造結果）：按碼分發結果快照（異常隔離，逐個收斂）。 */
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
        val listener: Shizuku.OnRequestPermissionResultListener
        synchronized(lock) {
            if (attached) return
            stagedEpoch = activeEpoch
            listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                val epoch = synchronized(lock) {
                    if (!attached) return@OnRequestPermissionResultListener
                    activeEpoch
                }
                dispatchResult(requestCode, grantResult, epoch)
            }
        }

        val added = try {
            addListener(listener)
            true
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
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
            } catch (_: Exception) {
            } catch (_: LinkageError) {
            }
        }
    }

    /**
     * 解除接線：反註冊上游 listener 並清空本地回呼表、丟棄暫存引用、重置守衛。
     * 鎖反轉防禦：在鎖內清空狀態並取出 listener 引用，在鎖外呼叫 [removeListener]。
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
            } catch (_: Exception) {
            } catch (_: LinkageError) {
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
