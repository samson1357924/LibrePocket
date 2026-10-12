package dev.librepocket.agent.ui

/**
 * 啟動導航守衛純條件（Re-review #8 Finding 2 P2/BLOCK 階段2）。
 *
 * 背景：登出部分清除（`LOGOUT_CLEAR_FAILED`：金鑰已刪、設定仍在）會把 gate
 * 折疊為 `NoEndpoint`，而舊硬守衛「無端點即強制跳 Setup」會把仍在 Settings
 * 的使用者踢走，導致 [SettingsScreen][dev.librepocket.agent.ui.settings.SettingsScreen]
 * NoEndpoint 恢復卡（重試/關閉）不可達。`LOGOUT_STALE`（已有新金鑰生效）同理：
 * 待 dismiss 前亦不應強制跳轉，否則使用者無機會看到「不需重試」文案。
 *
 * 規則（全部滿足才跳轉）：
 * - 非 loading（loading 不決策，避免 FOUC）；
 * - 無可用端點（`hasEndpoint == false`）；
 * - 當前路由已知且不在 SETUP（已在 SETUP 不重導；`null` 初始幀不導）；
 * - 無未恢復登出態：`logoutErrorCode` 非 `LOGOUT_*` 且 `logoutInFlight == false`。
 *   非 `LOGOUT_*` 碼（如 save 驗證碼）不攔截——呼叫方以
 *   `form.errorCode?.takeIf { startsWith("LOGOUT_") }` 過濾後傳入，防禦性再判一次。
 *
 * 純函數、無副作用，單測見 `NavGuardTest` 真值表。
 */
fun shouldNavigateToSetup(
    hasEndpoint: Boolean,
    gateLoading: Boolean,
    currentRoute: String?,
    logoutErrorCode: String?,
    logoutInFlight: Boolean,
): Boolean {
    if (gateLoading) return false
    if (hasEndpoint) return false
    if (currentRoute == null) return false
    if (currentRoute == Routes.SETUP) return false
    if (logoutInFlight) return false
    if (logoutErrorCode?.startsWith("LOGOUT_") == true) return false
    return true
}
