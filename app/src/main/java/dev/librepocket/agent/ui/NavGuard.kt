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

/**
 * 啟動恢復導向純條件（Stage 3 P2，可達性決策 (a)）。
 *
 * 決策：啟動 replay 失敗（`LOGOUT_*` + `NoEndpoint`）的使用者若停在 CHAT，
 * 恢復卡（在 Settings）不可達——舊硬守衛只攔截 SETUP 導向，人會卡在無端點的
 * CHAT 空態。此函數把該態自動導向 SETTINGS 而非 SETUP（[shouldNavigateToSetup]
 * 語義不變：兩者互斥——有碼走 SETTINGS，無碼走 SETUP），drawer/選單鍵亦在
 * 恢復態啟用（見 `MainScreen`），手動前往 SETTINGS 始終可達。
 *
 * 規則（全部滿足才導向 SETTINGS）：
 * - 非 loading；無可用端點；不在登出執行中；
 * - 有未恢復登出態（`LOGOUT_*` 任一碼，含 STALE 待 dismiss）；
 * - 當前路由為 CHAT（已在 SETTINGS/SETUP 不重導；`null` 初始幀不決策）。
 *
 * 純函數、無副作用。
 */
fun shouldNavigateToSettings(
    hasEndpoint: Boolean,
    gateLoading: Boolean,
    currentRoute: String?,
    logoutErrorCode: String?,
    logoutInFlight: Boolean,
): Boolean {
    if (gateLoading) return false
    if (hasEndpoint) return false
    if (logoutInFlight) return false
    if (logoutErrorCode?.startsWith("LOGOUT_") != true) return false
    if (currentRoute != Routes.CHAT) return false
    return true
}
