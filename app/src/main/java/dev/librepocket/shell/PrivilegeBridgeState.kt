package dev.librepocket.shell

import androidx.annotation.VisibleForTesting
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 * - 橋接授權的持久真相源是 [PrivilegeBridgeStore]（DataStore，四鍵：
 *   providerId／configRevision／grantedAt／expiresAt）；記憶體旗標
 *   [bridgeGranted] 只是快取，唯一同步點是 [grant]／[refresh]／[revoke]：
 *   [grant] 落庫後才置旗，[refresh] 每次重讀 Store 新鮮判定後同步旗標，
 *   [revoke] 先清庫（＋可選跨域 `withdrawScope`）再清記憶體；
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
 */
class PrivilegeBridgeState @VisibleForTesting internal constructor(
    val audit: PrivilegeAuditLog = PrivilegeAuditLog(),
    bridgeSwitchOn: Boolean = false,
    bridgeGranted: Boolean = false,
) {
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
     * 簽發橋授權：校驗＋落庫成功後才置 [bridgeGranted]（落庫抛錯即旗標不動，
     * fail-closed）；開關 [bridgeSwitchOn] 不在此動（UI 側意圖，見本類 KDoc）。
     *
     * Revoke-wins 併發保證：在排隊等待鎖前預先捕捉 revoke generation epoch，
     * 若在排隊、落庫或校驗期間有撤銷發生，立即使授權失效、清除 Store 並抛出
     * [IllegalStateException]。
     */
    suspend fun grant(
        store: PrivilegeBridgeStore,
        providerId: String,
        configRevision: Long,
        ttlMs: Long? = null,
        atMs: Long = System.currentTimeMillis(),
    ): PrivilegeGrant {
        val epochBefore = revokeEpoch.get()
        return opMutex.withLock {
            if (revokeEpoch.get() != epochBefore) {
                bridgeGranted = false
                throw IllegalStateException("PRIVILEGE_GRANT_REVOKED_CONCURRENTLY")
            }
            val grant = PrivilegeGrantPolicy.grant(providerId, configRevision, ttlMs, atMs)
            store.save(grant)
            if (revokeEpoch.get() != epochBefore) {
                bridgeGranted = false
                try { store.clear() } catch (_: Throwable) {}
                throw IllegalStateException("PRIVILEGE_GRANT_REVOKED_CONCURRENTLY")
            }
            val postCheck = PrivilegeGrantPolicy.check(grant, providerId, configRevision, atMs)
            if (revokeEpoch.get() != epochBefore) {
                bridgeGranted = false
                try { store.clear() } catch (_: Throwable) {}
                throw IllegalStateException("PRIVILEGE_GRANT_REVOKED_CONCURRENTLY")
            }
            bridgeGranted = postCheck is PrivilegeGrantCheck.Valid
            grant
        }
    }

    /**
     * 新鮮重估：每次重讀 [PrivilegeBridgeStore]（單發射，不快取）＋
     * [PrivilegeGrantPolicy.check] 判定後同步 [bridgeGranted]
     *（Valid 即 true，其餘一律 false，fail-closed）。
     *
     * 產品 #136-B3 以此供給 runner `bridgeGranted`
     *（blocking 包裝，見 [PrivilegeBridgeStore.check] KDoc 契約）。
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
                return@withLock PrivilegeGrantCheck.Invalid(PrivilegeGrantPolicy.REASON_NEVER_GRANTED)
            }
            val check = store.check(currentProviderId, currentRevision, nowMs)
            if (revokeEpoch.get() != epochBefore) {
                bridgeGranted = false
                return@withLock PrivilegeGrantCheck.Invalid(PrivilegeGrantPolicy.REASON_NEVER_GRANTED)
            }
            bridgeGranted = check is PrivilegeGrantCheck.Valid
            check
        }
    }

    /**
     * 一鍵收回（持久版）：清持久授權（＋可選跨域 `withdrawScope`，
     * 如 github 側 Shizuku 授權 Store 的 `clear()`，由 #136-B3 產品接線傳入，
     * 本檔不跨源集引用）→ 清審計表 → 關開關 → 撤橋接授權。
     *
     * 持久層／跨域 scope 抛錯時仍先落記憶體收回再重抛（fail-closed：
     * 收回永不因持久失敗而殘留可用旗標）；[CancellationException] 原樣傳播。
     *
     * 注意重入約束：本函數在持有內部 [opMutex] 鎖的臨界區內執行 [withdrawScope]，
     * 該鎖為非重入鎖（non-reentrant）。傳入的 [withdrawScope] 僅應執行外部 Store 清除
     *（例如 ShizukuGrantStore.clear()），嚴禁回調本物件的 [grant]、[refresh] 或 [revoke]，
     * 否則將導致死鎖。
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
            opMutex.withLock {
                bridgeSwitchOn = false
                bridgeGranted = false
                var primaryFailure: Throwable? = null
                try {
                    store.clear()
                } catch (t: Throwable) {
                    primaryFailure = t
                }
                if (withdrawScope != null) {
                    try {
                        withdrawScope()
                    } catch (t: Throwable) {
                        if (primaryFailure != null) {
                            primaryFailure.addSuppressed(t)
                        } else {
                            primaryFailure = t
                        }
                    }
                }
                bridgeSwitchOn = false
                bridgeGranted = false
                if (primaryFailure != null) throw primaryFailure
            }
        }
        return cleared
    }

    companion object {
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
