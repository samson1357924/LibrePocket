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
}
