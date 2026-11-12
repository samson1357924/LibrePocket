package dev.librepocket.shell

import kotlinx.coroutines.CancellationException

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
class PrivilegeBridgeState(
    val audit: PrivilegeAuditLog = PrivilegeAuditLog(),
    @Volatile var bridgeSwitchOn: Boolean = false,
    @Volatile var bridgeGranted: Boolean = false,
) {
    fun auditCount(): Int = audit.size()

    /**
     * 一鍵收回：清表並回傳清除筆數，同時關閉開關與橋接授權。
     */
    fun revoke(): Int {
        val cleared = audit.revokeAll()
        bridgeSwitchOn = false
        bridgeGranted = false
        return cleared
    }

    /**
     * 簽發橋授權：校驗＋落庫成功後才置 [bridgeGranted]（落庫抛錯即旗標不動，
     * fail-closed）；開關 [bridgeSwitchOn] 不在此動（UI 側意圖，見本類 KDoc）。
     */
    suspend fun grant(
        store: PrivilegeBridgeStore,
        providerId: String,
        configRevision: Long,
        ttlMs: Long? = null,
        atMs: Long = System.currentTimeMillis(),
    ): PrivilegeGrant {
        val grant = PrivilegeGrantPolicy.grant(providerId, configRevision, ttlMs, atMs)
        store.save(grant)
        bridgeGranted = true
        return grant
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
        nowMs: Long = System.currentTimeMillis(),
    ): PrivilegeGrantCheck {
        val check = store.check(currentProviderId, currentRevision, nowMs)
        bridgeGranted = check is PrivilegeGrantCheck.Valid
        return check
    }

    /**
     * 一鍵收回（持久版）：清持久授權（＋可選跨域 `withdrawScope`，
     * 如 github 側 Shizuku 授權 Store 的 `clear()`，由 #136-B3 產品接線傳入，
     * 本檔不跨源集引用）→ 清審計表 → 關開關 → 撤橋接授權。
     *
     * 持久層／跨域 scope 抛錯時仍先落記憶體收回再重抛（fail-closed：
     * 收回永不因持久失敗而殘留可用旗標）；[CancellationException] 原樣傳播。
     */
    suspend fun revoke(
        store: PrivilegeBridgeStore,
        withdrawScope: (suspend () -> Unit)? = null,
    ): Int {
        try {
            store.clear()
            withdrawScope?.invoke()
        } catch (cancelled: CancellationException) {
            revoke()
            throw cancelled
        } catch (failure: Exception) {
            revoke()
            throw failure
        }
        return revoke()
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
