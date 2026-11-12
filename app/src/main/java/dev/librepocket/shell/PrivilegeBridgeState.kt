package dev.librepocket.shell

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
 * #136-B3 接線形狀（最小 grant 抽象，依賴 #166 先合併）：
 * - [PrivilegeRevokeStore] 為持久授權 Store 的最小形狀（僅 `clear()`）：
 *   B1 的 `PrivilegeBridgeStore`（DataStore 四鍵）在 #166 合併後實現此介面
 *   （rebase 時加 `: PrivilegeRevokeStore`），本分支不重做 B1 的 DataStore／
 *   policy／expiry 實作；
 * - 持久版 [revoke] 先清持久授權（＋可選跨域 `withdrawScope`，產品傳
 *   github 側 `ShizukuGrantStore.clear`）再清記憶體；持久層／跨域抛錯時仍先落
 *   記憶體收回再重抛（fail-closed，收回永不因持久失敗而殘留可用旗標）；
 * - `MainScreen.onRevokePrivilege` 經此持久版收回（`scope` 傳跨域 clear），
 *   B1 未合併前以記憶體 Store 佔位、PR 描述註明依賴 #166（rebase 後以 B1
 *   持久版為準）。
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
     * 一鍵收回（持久版，#136-B3 接線形狀，依賴 #166）：
     * 先清持久授權 [store]（＋可選跨域 [withdrawScope]，如 github 側 Shizuku
     * 授權 Store 的 `clear()`，由產品接線傳入，本檔不跨源集引用）→ 清審計表 →
     * 關開關 → 撤橋接授權。
     *
     * 持久層／跨域 scope 抛錯時仍先落記憶體收回再重抛（fail-closed）；
     * [kotlinx.coroutines.CancellationException] 原樣傳播（先收回再抛）。
     */
    suspend fun revoke(
        store: PrivilegeRevokeStore,
        withdrawScope: (suspend () -> Unit)? = null,
    ): Int {
        try {
            store.clear()
            withdrawScope?.invoke()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            revoke()
            throw cancelled
        } catch (failure: Exception) {
            revoke()
            throw failure
        }
        return revoke()
    }
}

/**
 * 持久授權 Store 的最小形狀（#136-B3 接線形狀，依賴 #166）：
 * 僅 `clear()`（撤銷）。B1 的 `PrivilegeBridgeStore`（DataStore）在 #166
 * 合併後實現此介面（rebase 時加 `: PrivilegeRevokeStore`）；本分支的 JVM
 * 接線測試以記憶體假實現佔位，不重做 B1 的 DataStore／policy／expiry。
 */
interface PrivilegeRevokeStore {
    suspend fun clear()
}

/** 記憶體假持久 Store（僅 #136-B3 JVM 接線形狀佔位；產品以 B1 DataStore 為準）。 */
class InMemoryPrivilegeRevokeStore : PrivilegeRevokeStore {
    @Volatile var clearCalls: Int = 0
        private set
    override suspend fun clear() {
        clearCalls++
    }
}
