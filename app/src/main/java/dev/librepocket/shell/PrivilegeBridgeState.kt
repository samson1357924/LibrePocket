package dev.librepocket.shell

import android.util.Log
import androidx.annotation.VisibleForTesting
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "PrivilegeBridgeState"

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
 * S3 提權橋產品態（BACKLOG D09，矩陣 §3）：審計表 + `privilege_bridge`
 * 開關 + 橋接授權的三位一體持有者（零 Android 依賴，JVM 可測）。
 *
 * - 預設三者全關/全空（fail-closed：開關關、授權無、審計空）；
 * - 一鍵收回 [revoke] 原子完成三件事：[PrivilegeAuditLog.revokeAll] 清表、
 *   `privilege_bridge` 開關關閉、橋接授權撤銷，保證收回後不再有跨域執行；
 * - 設定頁（`SettingsScreen.onRevokePrivilege`）直接調 [revoke]，
 *   不得只清表而不關開關。
 *
 * #136-B1 持久化（Store 授權真相源）：
 * - 橋接授權的持久真相源是 [PrivilegeBridgeStore]（DataStore，五鍵：
 *   providerId／configRevision／grantedAt／expiresAt／revoke_journal——
 *   journal 為撤權意圖日誌：撤銷先寫入、擦除後移除；殘留時 [PrivilegeBridgeStore.load]
 *   自癒擦除並回 null）；記憶體旗標
 *   [bridgeGranted] 只是快取，唯一同步點是 [grant]／[refresh]／[revoke]：
 *   [grant] 落庫後才置旗，[refresh] 每次重讀 Store 新鮮判定後同步旗標，
 *   [revoke] 先落記憶體收回（fail-closed），再有界清庫
 *   （marker/journal 見 Store）；
 * - [refresh] 不碰 [bridgeSwitchOn]：開關是 UI 側使用者授權意圖，
 *   與「持久授權是否有效」正交；開關關即投影不可用，與授權有效性無關；
 * - Store 層／跨域 scope 抛錯時仍先落記憶體收回再重抛（fail-closed，
 *   見 [revoke]），呼叫方不得吞錯放行；
 * - 授權到期（`expiresAtMs`，null＝永不過期）由 [PrivilegeGrantPolicy.check]
 *   判定，[refresh] 同步失效（旗標落 false），次 call 即拒。
 *
 * 行程單例（#136-B1）：全行程唯一持有者經 [process] 取得
 * （雙檢鎖，審計表跨 Activity／跨 composition 唯一，不因重組丟失計數）；
 * 單測用新實例（`PrivilegeBridgeState()`）互不污染，僅單例語義測試經
 * [resetProcessForTest] 前後隔離。
 *
 * 已知限制：[grant]／[refresh] opMutex 臨界區無 `withTimeout`
 * （與 [revoke] 不對稱，#136-B3 接線前應評估有界化）。
 */
class PrivilegeBridgeState @VisibleForTesting internal constructor(
    val audit: PrivilegeAuditLog = PrivilegeAuditLog(),
    bridgeSwitchOn: Boolean = false,
    bridgeGranted: Boolean = false,
    private val revokeOpTimeoutMs: Long = REVOKE_OP_TIMEOUT_MS,
) {
    init {
        require(revokeOpTimeoutMs > 0) { "REVOKE_OP_TIMEOUT_MS_MUST_BE_POSITIVE" }
    }

    @Volatile var bridgeSwitchOn: Boolean = bridgeSwitchOn
        private set

    @Volatile var bridgeGranted: Boolean = bridgeGranted
        private set

    fun setBridgeSwitch(enabled: Boolean) {
        bridgeSwitchOn = enabled
    }

    val bridgeEffective: Boolean
        get() = bridgeSwitchOn && bridgeGranted

    val auditCountFlow: StateFlow<Int>
        get() = audit.sizeFlow

    private val opMutex = Mutex()
    private val revokeEpoch = AtomicLong(0L)

    fun auditCount(): Int = audit.size()

    /**
     * 一鍵收回（僅記憶體態）：清表並回傳清除筆數，同時關閉開關與橋接授權。
     *
     * 注意：本函數僅清除記憶體旗標與審計表，不清除磁碟 DataStore。
     * 產品正常流程應呼叫持久版 [revoke(store, withdrawScope)]。
     */
    @Deprecated(
        message = "In-memory only. Use suspend revoke(store, withdrawScope) in production to ensure DataStore is cleared.",
        replaceWith = ReplaceWith("revoke(store, withdrawScope)"),
    )
    fun revoke(): Int {
        revokeEpoch.incrementAndGet()
        bridgeSwitchOn = false
        bridgeGranted = false
        return audit.revokeAll()
    }

    /**
     * 僅單測隔離用：記憶體收回（不碰磁碟）。
     */
    @VisibleForTesting
    fun revokeInMemoryForTest(): Int = revoke()

    /**
     * 僅單測使用：推進 [revokeEpoch]（不寫任何旗標）；production 不得調用。
     * 供 [bridgeGrantedEpochBumpHookForTest] 在 second-read 前確定性插入
     * 「epoch 已前進」的 bump，用以釘住 second-read 單層防線。
     */
    @VisibleForTesting
    internal fun incrementRevokeEpochOnlyForTest() {
        revokeEpoch.incrementAndGet()
    }

    /**
     * 旗標發布（write＋second-read 重查，epoch 原子雙重檢查）：
     * 寫入值為 `valid && revokeEpoch.get() == epochBefore`；寫後再讀一次 epoch——
     * 撤銷落在「賦值」與「回讀」之間時仍落 false（fail-closed，不復活為 true）。
     *
     * 精度說明：本函數兩層（conjunct 重查／second-read）各自獨立即可擋下
     * 「撤銷落在發布窗口」的競態，故既有單執行緒測試只能判別雙重檢查
     * 「整體」被移除；second-read 獨有的「賦值→回讀」納秒窗無法以既有
     * publish hook seam 判別性釘住（該 hook 觸發在賦值「之前」），
     * 故另設 [bridgeGrantedEpochBumpHookForTest]（觸發於賦值與 second-read 之間，
     * 僅推進 epoch、不寫旗標）。
     *
     * 殘留：second-read 仍非對 volatile 讀者全然原子——「賦值（可能為 true）」
     * 與「second-read 壓回 false」之間的納秒窗，並發 volatile 讀取仍可能瞬時
     * 觀測為 true（無法在不持有 opMutex 對抗 revoke 的前提下消除）；
     * #136-B3 以落庫前 final-commit fence 為最終兜底，不得僅憑本旗標放行。
     *
     * [bridgeGrantedPublishHookForTest]（測試專用，production 恆 null）在旗標
     * 寫入前觸發，用以確定性模擬「撤銷發布窗口內落地」。
     * 測試鉤刻意放在「賦值之前」而非「賦值與 second-read 之間」：若放在兩者之間，
     * 撤銷自身會把旗標寫成 false（last-writer-wins），還原 conjunct 或還原
     * second-read 任一處都不會判紅；放在賦值之前，「賦值」成為最後寫入者，
     * 才能確定性判別 epoch 雙重檢查是否真正生效。second-read 仍保留作結構性
     * 防線（涵蓋賦值與回讀之間的競態；該納秒窗以 [bridgeGrantedEpochBumpHookForTest]
     * 單獨釘住）。
     */
    private fun publishBridgeGranted(valid: Boolean, epochBefore: Long) {
        bridgeGrantedPublishHookForTest?.invoke()
        bridgeGranted = valid && revokeEpoch.get() == epochBefore
        bridgeGrantedEpochBumpHookForTest?.invoke()
        // second-read：覆蓋「撤銷落在賦值與回讀之間」的殘餘窗口
        if (revokeEpoch.get() != epochBefore) bridgeGranted = false
    }

    /** 測試專用：旗標寫入前的 hook（fire revokeInMemoryForTest() 等）；production 不得呼叫。 */
    @Volatile
    @VisibleForTesting
    internal var bridgeGrantedPublishHookForTest: (() -> Unit)? = null

    /**
     * 測試專用：旗標賦值後、second-read 前的 hook；bump 僅經
     * [incrementRevokeEpochOnlyForTest] 推進 [revokeEpoch]（不寫旗標），
     * 用以確定性釘住 second-read 單層防線；production 不得呼叫。
     */
    @Volatile
    @VisibleForTesting
    internal var bridgeGrantedEpochBumpHookForTest: (() -> Unit)? = null

    /**
     * 簽發橋授權：校驗＋落庫成功後才置 [bridgeGranted]（落庫抛錯即旗標不動，
     * fail-closed）；開關 [bridgeSwitchOn] 不在此動（UI 側意圖，見本類 KDoc）。
     *
     * 回傳的 [PrivilegeGrant] 僅供記錄；授權判定的唯一依據是 [bridgeGranted]
     *／[PrivilegeBridgeStore.check]——#136-B3 不得以回傳值直接放行。
     *
     * Revoke-wins 併發保證：在排隊等待鎖前預先捕捉 revoke generation epoch，
     * 若在排隊、落庫或校驗期間有撤銷發生，立即使授權失效、清除 Store 並抛出
     * [IllegalStateException]。
     *
     * 落庫後並發撤銷的 best-effort 清庫（revoke-in-flight 補償）失敗時必留一行
     * logcat（不靜默）；此路徑為 revoke-in-flight 補償，fail-closed 語義不變
     * （記憶體旗標已先落 false、`IllegalStateException` 照拋，`CancellationException`
     * 原樣上拋）。
     *
     * 呼叫端不得傳入未來時間戳（否則授權可能在簽發前即「有效」）；
     * `atMs` 逾現在時刻即 fail-fast 抛 [IllegalArgumentException]（`AT_MS_IN_FUTURE`）。
     *
     * 已知限制：本函數 opMutex 臨界區無 `withTimeout`（與 [revoke] 不對稱，
     * #136-B3 接線前應評估有界化）。
     */
    suspend fun grant(
        store: PrivilegeBridgeStore,
        providerId: String,
        configRevision: Long,
        ttlMs: Long? = null,
        atMs: Long = System.currentTimeMillis(),
    ): PrivilegeGrant {
        val epochBefore = revokeEpoch.get()
        require(atMs <= System.currentTimeMillis()) { PrivilegeBridgeErrors.AT_MS_IN_FUTURE }
        return opMutex.withLock {
            if (revokeEpoch.get() != epochBefore) {
                bridgeGranted = false
                throw IllegalStateException(PrivilegeBridgeErrors.GRANT_REVOKED_CONCURRENTLY)
            }
            val grant = PrivilegeGrantPolicy.grant(providerId, configRevision, ttlMs, atMs)
            store.save(grant)
            if (revokeEpoch.get() != epochBefore) {
                bridgeGranted = false
                // best-effort 清庫：失敗可接受（revoke 側具備 marker/journal 復活防護），
                // 但不得吞 CancellationException（會掩蓋取消語義）；
                // 失敗必留一行 logcat（不靜默）。
                try { store.clear() } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    safeLogW(TAG, "revoke-compensation clear failed", t)
                }
                throw IllegalStateException(PrivilegeBridgeErrors.GRANT_REVOKED_CONCURRENTLY)
            }
            // 以新鮮時鐘判定（不重用簽發時 atMs）：落庫耗時可跨過 TTL，
            // postCheck 必須見 expiresAtMs 已過而判失效，否則會置出已過期授權旗標。
            val postCheck = PrivilegeGrantPolicy.check(grant, providerId, configRevision, System.currentTimeMillis())
            if (revokeEpoch.get() != epochBefore) {
                bridgeGranted = false
                // best-effort 清庫（同上，不吞 CancellationException；失敗必留一行 logcat）。
                try { store.clear() } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    safeLogW(TAG, "revoke-compensation clear failed", t)
                }
                throw IllegalStateException(PrivilegeBridgeErrors.GRANT_REVOKED_CONCURRENTLY)
            }
            // 旗標發布（write＋second-read 重查；撤銷落在發布窗口內 → 不得復活為 true）
            publishBridgeGranted(postCheck is PrivilegeGrantCheck.Valid, epochBefore)
            grant
        }
    }

    /**
     * 新鮮重估：每次重讀 [PrivilegeBridgeStore]（單發射，不快取）＋
     * [PrivilegeGrantPolicy.check] 判定後同步 [bridgeGranted]
     *（Valid 即 true，其餘一律 false，fail-closed）。
     *
     * epoch 不符 ⇒ 視同從未授權（[PrivilegeGrantPolicy.REASON_NEVER_GRANTED]，
     * 保守語義：並發撤銷時折疊為既有理由碼，非真從未授權）。
     *
     * 產品 #136-B3 以此供給 runner `bridgeGranted`
     *（blocking 包裝，見 [PrivilegeBridgeStore.check] KDoc 契約）。
     *
     * 已知限制：本函數 opMutex 臨界區無 `withTimeout`（與 [revoke] 不對稱，
     * #136-B3 接線前應評估有界化）。
     */
    suspend fun refresh(
        store: PrivilegeBridgeStore,
        currentProviderId: String?,
        currentRevision: Long?,
        nowMs: Long? = null,
    ): PrivilegeGrantCheck {
        val epochBefore = revokeEpoch.get()
        return opMutex.withLock {
            if (revokeEpoch.get() != epochBefore) {
                bridgeGranted = false
                // 保守語義：epoch 變更分支回 REASON_NEVER_GRANTED 僅表示
                // 「此刻不可信任為已授權」（實為並發撤銷），非真從未授權、
                // 刻意以既有理由碼 fail-closed 折疊（見 refresh KDoc）。
                return@withLock PrivilegeGrantCheck.Invalid(PrivilegeGrantPolicy.REASON_NEVER_GRANTED)
            }
            val check = store.check(currentProviderId, currentRevision, nowMs)
            if (revokeEpoch.get() != epochBefore) {
                bridgeGranted = false
                // 保守語義：epoch 變更分支回 REASON_NEVER_GRANTED 僅表示
                // 「此刻不可信任為已授權」（實為並發撤銷），非真從未授權
                // （同上 epoch 變更分支說明）。
                return@withLock PrivilegeGrantCheck.Invalid(PrivilegeGrantPolicy.REASON_NEVER_GRANTED)
            }
            // 旗標發布（write＋second-read 重查；撤銷落在發布窗口內 → 不得復活為 true）
            publishBridgeGranted(check is PrivilegeGrantCheck.Valid, epochBefore)
            check
        }
    }

    /**
     * 一鍵收回（持久版）：清審計表 → 關開關 → 撤橋接授權（記憶體旗標先落
     * false，fail-closed）→ 有界清持久授權 → 鎖外有界執行可選跨域
     * `withdrawScope`（如 github 側 Shizuku 授權 Store 的 `clear()`，
     * 由 #136-B3 產品接線傳入，本檔不跨源集引用）。
     *
     * 有界語義（fail-closed）：opMutex 等待、`store.clear()`、[withdrawScope]
     * 三段各自以 `revokeOpTimeoutMs`（預設 [REVOKE_OP_TIMEOUT_MS]＝5s，僅單測
     * 注入更短）為上限；最壞吊掛 ≈ 三者之和。任一階段超時皆【非靜默成功】：
     * 記憶體旗標已先落 false，但一律抛 [IllegalStateException]
     * （`PRIVILEGE_REVOKE_TIMEOUT`／`PRIVILEGE_REVOKE_STORE_TIMEOUT`／
     * `PRIVILEGE_REVOKE_SCOPE_TIMEOUT`），呼叫端可視為失敗重試。
     * 有界保證僅對服從協作式取消的 I/O 成立；阻塞型卡死（無 suspension point
     * 的原生 IO/JNI）不在上界內。
     * opMutex 等待逾時（鎖未取得）時：記憶體旗標已收回；另以鎖外同有界
     * best-effort `store.clear()`（journal/marker 為持久兜底）嘗試落持久撤銷；
     * 仍失敗即呼叫端重試語義。
     * 不對稱對照：臨界區內 `store.clear()` 抛出的 [CancellationException] 直接
     * 作 primaryFailure 原樣上拋（CE-first：呼叫端可識別上層取消）；鎖外
     * best-effort 路徑的主失敗已是 `PRIVILEGE_REVOKE_TIMEOUT`（ISE），該路徑
     * clear 抛的 CE 一律包 `IllegalStateException`（`PRIVILEGE_REVOKE_BEST_EFFORT_CLEAR_FAILED`）
     * 作 suppressed 附加，避免呼叫端把主失敗誤判為上層取消（補償只附隨，不改寫主失敗語義）。
     * 正常 scope 拋錯時沿用既有 primaryFailure／addSuppressed 合併邏輯；
     * 複合逾時時（已有 primaryFailure 又遇 withdrawScope 逾時）
     * `PRIVILEGE_REVOKE_SCOPE_TIMEOUT` 以 suppressed [IllegalStateException]
     * 附加（非 [CancellationException]）。
     *
     * 持久層／跨域 scope 抛錯時仍先落記憶體收回再重抛（fail-closed：
     * 收回永不因持久失敗而殘留可用旗標）；[CancellationException] 原樣傳播。
     * [withdrawScope] 在 [opMutex] 鎖外執行（不再持有鎖），但仍僅應執行外部
     * Store 清除（例如 ShizukuGrantStore.clear()），嚴禁回調本物件的
     * [grant]、[refresh] 或 [revoke]（語義錯亂，且 [revoke] 重入將重複清表）。
     */
    suspend fun revoke(
        store: PrivilegeBridgeStore,
        withdrawScope: (suspend () -> Unit)? = null,
    ): Int {
        revokeEpoch.incrementAndGet()
        bridgeSwitchOn = false
        bridgeGranted = false
        val cleared = audit.revokeAll()

        withContext(NonCancellable) {
            // 有界等待 opMutex：只包 acquisition，臨界區內各段另以 timeout 保護，
            // 鎖取得超時 → fail-closed 抛錯（記憶體已關旗標，呼叫端可重試）。
            val acquired = withTimeoutOrNull(revokeOpTimeoutMs) {
                opMutex.lock()
                true
            }
            var primaryFailure: Throwable? = null
            if (acquired == null) {
                // kotlinx.coroutines 1.11.0：Mutex.lock() 文件保證——若等待期間
                // 取得鎖後才遇 mid-wait 取消（如逾時），會先釋放鎖再傳播取消，
                // 故逾時取得點不會殘留「已持鎖但以為失敗」的情形，無需補 unlock；
                // 絕不 force-unlock 他協程持有的 mutex（unlock() 不做擁有者檢查）。
                // opMutex 被長臨界區佔用（grant/refresh 含 DataStore I/O）或
                // DataStore 永久掛起 → 不再無界等待。
                primaryFailure = IllegalStateException(PrivilegeBridgeErrors.REVOKE_TIMEOUT)
                // 鎖外同有界 best-effort durable 清除（與臨界區內的 clear 冪等）：
                // journal/marker 任一落地即可使舊授權不復活；兩者皆失敗＝已知限制 (ii)。
                try {
                    val bestEffort = withTimeoutOrNull(revokeOpTimeoutMs) { store.clear() }
                    if (bestEffort == null) {
                        primaryFailure.addSuppressed(
                            IllegalStateException(PrivilegeBridgeErrors.REVOKE_BEST_EFFORT_CLEAR_TIMEOUT),
                        )
                    }
                } catch (t: Throwable) {
                    // 不得以 CancellationException 作 suppressed（不與 CE-first 契約同形）：
                    // 一律包 IllegalStateException。
                    primaryFailure.addSuppressed(
                        IllegalStateException(PrivilegeBridgeErrors.REVOKE_BEST_EFFORT_CLEAR_FAILED, t),
                    )
                }
            } else {
                try {
                    bridgeSwitchOn = false
                    bridgeGranted = false
                    // store.clear() 有界：超時 → PRIVILEGE_REVOKE_STORE_TIMEOUT。
                    try {
                        val clearedStore = withTimeoutOrNull(revokeOpTimeoutMs) { store.clear() }
                        if (clearedStore == null) {
                            primaryFailure = IllegalStateException(PrivilegeBridgeErrors.REVOKE_STORE_TIMEOUT)
                        }
                    } catch (t: Throwable) {
                        // CancellationException 照現況捕捉為 primaryFailure，最終原樣 rethrow。
                        primaryFailure = t
                    }
                } finally {
                    bridgeSwitchOn = false
                    bridgeGranted = false
                    opMutex.unlock()
                }
            }
            // withdrawScope 移出 opMutex 臨界區，鎖外有界執行；
            // store.clear 失敗時仍照舊執行（盡力撤銷，fail-closed）。
            if (withdrawScope != null) {
                try {
                    val withdrawn = withTimeoutOrNull(revokeOpTimeoutMs) { withdrawScope() }
                    if (withdrawn == null) {
                        val existing = primaryFailure
                        if (existing == null) {
                            primaryFailure = IllegalStateException(PrivilegeBridgeErrors.REVOKE_SCOPE_TIMEOUT)
                        } else {
                            // TimeoutCancellationException 為 public，但其型別與 CE-first 契約
                            // 同形（MainScreen 會誤判為上層取消而原樣上拋）；改以型別一致的
                            // IllegalStateException 附加（fail-closed 語義不變）。
                            existing.addSuppressed(IllegalStateException(PrivilegeBridgeErrors.REVOKE_SCOPE_TIMEOUT))
                        }
                    }
                } catch (t: Throwable) {
                    // 正常 scope 拋錯：保留現有 primaryFailure/addSuppressed 合併邏輯。
                    val existingScope = primaryFailure
                    if (existingScope != null) {
                        existingScope.addSuppressed(t)
                    } else {
                        primaryFailure = t
                    }
                }
            }
            if (primaryFailure != null) throw primaryFailure
        }
        return cleared
    }

    companion object {
        /** revoke 持久化各段（opMutex 等待／store.clear／withdrawScope）的預設逾時。 */
        private const val REVOKE_OP_TIMEOUT_MS = 5_000L

        @Volatile
        private var processInstance: PrivilegeBridgeState? = null

        /** 全行程唯一橋態（見本類 KDoc 行程單例段）。 */
        fun process(): PrivilegeBridgeState =
            processInstance ?: synchronized(this) {
                processInstance ?: PrivilegeBridgeState().also { processInstance = it }
            }

        /** 僅單測隔離用：丟棄行程實例（產品不得調用）。 */
        internal fun resetProcessForTest() {
            synchronized(this) {
                processInstance = null
            }
        }
    }
}
