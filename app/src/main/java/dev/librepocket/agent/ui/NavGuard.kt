package dev.librepocket.agent.ui

/**
 * 啟動落定前有效 loading（Stage B：R10 Finding 2 P2/UX）。
 *
 * replay 慢時 gate 可能已折疊 `NoEndpoint`（journal 保留）而落碼尚未發生；
 * 此時若按 `gateLoading == false` 決策會先跳 Setup、失敗後又不導回 Settings。
 * 落定前視同 loading：呼叫方把回傳值傳給兩守衛的 `gateLoading` 參數（loading
 * 不決策，避免 FOUC），全屏 spinner 條件亦用此值。兩守衛簽名與語義不變。
 */
fun startupEffectiveLoading(
    gateLoading: Boolean,
    startupReconcilePending: Boolean,
): Boolean = gateLoading || startupReconcilePending

/**
 * Stage D2 派生恢復態（R12 Finding 2 P2/BLOCK (b)，單一來源純函數）。
 *
 * 跨 VM journal 不可見：VM-A `NO_OP` 落定（`errorCode == null`）後，VM-B 寫入
 * journal `J_B`——VM-A 的 `observe` 已折疊 `NoEndpoint`、save 已被擋，但
 * `logoutGuardError` 仍為 null，舊守衛誤判 fresh（強制跳 SETUP、drawer/menu
 * disabled、Settings 顯示「尚未設定」而無恢復入口）。
 *
 * 派生：`logoutGuardError ?: if (pendingPresent && !logoutInFlight)
 * "LOGOUT_UNKNOWN" else null`——有碼沿用（STALE 等外語義不動），無碼＋journal
 * present（[EndpointStore.observePendingDelete] 非 null，含 malformed／讀失敗
 * sentinel）＋非執行中 → 中性 `LOGOUT_UNKNOWN`（未確認路徑禁寫「已刪除」；
 * 與 D1 [SetupViewModel.LOGOUT_READ_RETRY] 區分：D1 新碼＝讀失敗未知
 *（journal=null，未觀測存在）；此處＝journal 已觀測存在）。
 * 呼叫方（[StartupGateHost] nav／drawer／menu、`MainScreen` Settings
 * `recoveryCode`）皆以此有效值決策；safe reconcile 複用既有 `logout()`
 * resume 分支（同一 `runPendingDeleteLocked` 核心，不新增 vault 路徑），落碼／
 * 清碼走 `stampLogoutError`＋C2 journal-identity。
 *
 * 純函數、無副作用。
 */
fun effectiveLogoutGuardError(
    logoutGuardError: String?,
    pendingPresent: Boolean,
    logoutInFlight: Boolean,
): String? = logoutGuardError
    ?: if (pendingPresent && !logoutInFlight) "LOGOUT_UNKNOWN" else null

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
