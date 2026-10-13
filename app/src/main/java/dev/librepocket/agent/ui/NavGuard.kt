package dev.librepocket.agent.ui

import dev.librepocket.agent.ui.setup.PendingDelete
import dev.librepocket.agent.ui.setup.PendingTeardownState

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
 * Stage E 四態派生恢復態（R13 Finding P2/BLOCK；D2 `pendingPresent: Boolean` 升格）。
 *
 * D2 的單布林把「已確認存在」與「未知（transient 讀失敗／毀損）」混為同一
 * present：無 journal 的 transient 失敗亦派生 `LOGOUT_UNKNOWN` 重試清除——
 * phantom pending 配破壞性外觀的恢復入口。
 *
 * 派生（有碼沿用，無碼看四態，非執行中才決策）：
 * - 有 `logoutGuardError` → 沿用（STALE 等外語義不動）；
 * - 無碼＋[PendingTeardownState.KnownPending]＋非執行中 → `LOGOUT_UNKNOWN`
 *  （已確認存在，retained 重試清除；未確認路徑禁寫「已刪除」）；
 * - 無碼＋[PendingTeardownState.UnknownReadFailure]／
 *   [PendingTeardownState.MalformedPending]＋非執行中 → `LOGOUT_READ_RETRY`
 *  （未確認：重試讀取 only，絕不給重試清除；複用 D1 碼／文案／按鈕／清除語義，
 *   不新增碼——此字串必須等於
 *   [dev.librepocket.agent.ui.setup.SetupViewModel.LOGOUT_READ_RETRY]）；
 * - 無碼＋[PendingTeardownState.Absent] → `null`（fresh 不變）；
 * - 執行中 → `null`（在途抑制派生，執行中不決策）。
 *
 * 呼叫方（[StartupGateHost] nav／drawer／menu、`MainScreen` Settings
 * `recoveryCode`）皆以此有效值決策；safe reconcile 複用既有 `logout()`
 * resume 分支（同一 `runPendingDeleteLocked` 核心，不新增 vault 路徑），落碼／
 * 清碼走 `stampLogoutError`＋C2 journal-identity。
 *
 * 純函數、無副作用。
 */
fun effectiveLogoutGuardError(
    logoutGuardError: String?,
    pendingTeardown: PendingTeardownState,
    logoutInFlight: Boolean,
): String? = effectiveLogoutGuardError(logoutGuardError, null, pendingTeardown, logoutInFlight)

/**
 * Stage F 四參派生抑制（R14 Finding P2/BLOCK：stale 恢復鍵回退全新 destructive logout）。
 *
 * 三參舊入口保留委託（`tag = null` → 沿用今日派生，行為不變）：既有呼叫方未遷移前
 * 語義不動；[MainScreen] 與 [StartupGateHost] 改傳
 * `form.logoutErrorSourceJournal`（見 [dev.librepocket.agent.ui.setup.SetupViewModel]
 * C2 journal-identity 標籤）。
 *
 * 規則（依序）：
 * - 執行中（`logoutInFlight == true`）→ `null`（在途抑制，不決策；兩入口共用同旗）；
 * - 無碼（`code == null`）→ 沿用今日派生（看四態：KnownPending→`LOGOUT_UNKNOWN`，
 *   Unknown／Malformed→`LOGOUT_READ_RETRY`，Absent→`null`；`tag` 忽略——無碼即無源）；
 * - 有碼＋`tag == null` → 沿用（`code`；含 D1 中性 `LOGOUT_READ_RETRY` 與
 *   `LOGOUT_READ_FAILED`，皆無源可比對）；
 * - 有碼＋`tag != null`：
 *   - `LOGOUT_STALE` → 沿用（dismiss-only 安全碼：無破壞性重試，Absent／J_C 下仍需
 *     關閉入口；抑制僅針對重試清除碼）；
 *   - `KnownPending(J)` 且 `J.format() == tag` → 沿用（同源 retained 有效）；
 *   - `Absent` → `null`（抑制 stale 回落：journal 已無，舊重試清除鍵不再派生；
 *     真正清卡靠 `retryPendingDelete` 終端同源清，純函數只隱藏）；
 *   - `KnownPending(J_C)` 且 `J_C.format() != tag` → `null`（抑制回落 J_C 派生：
 *     絕不以後來世代的 `J_C` 冒充舊卡的重試對象；亦不觸 `J_C` 的清除——後者屬
 *     `J_C` 持有者的恢復域；舊卡退場靠 `retryPendingDelete(StaleGone)` 終端報
 *     `STALE(expected tag)`）；
 *   - `UnknownReadFailure`／`MalformedPending` → 沿用（fail-closed 不對稱：
 *     未確認態可能掩蓋本源 journal，Absent 可證無而隱藏，未知不可證無而保留；
 *     重試走 `retryPendingDelete`→`ReadUnknown`→重試讀取，絕不進清除）。
 *
 * 真值表（`code/tag/state → effective`，`inFlight=false`；`true` 恆 `null`）：
 * | code | tag | state | effective |
 * |---|---|---|---|
 * | null | — | KnownPending(J) | LOGOUT_UNKNOWN |
 * | null | — | Unknown／Malformed | LOGOUT_READ_RETRY |
 * | null | — | Absent | null |
 * | STALE | J_A | *（Absent／J_C／Unknown 皆含） | STALE |
 * | DELETE_FAILED | null | * | DELETE_FAILED |
 * | DELETE_FAILED | J_A | KnownPending(J_A) | DELETE_FAILED |
 * | DELETE_FAILED | J_A | Absent | null |
 * | DELETE_FAILED | J_A | KnownPending(J_C≠J_A) | null |
 * | DELETE_FAILED | J_A | Unknown／Malformed | DELETE_FAILED |
 * （CLEAR_FAILED／UNKNOWN 同 DELETE_FAILED 行；READ_RETRY 同 tag-null 行。）
 *
 * 純函數、無副作用；真正清卡靠 `retryPendingDelete` 終端（見
 * [dev.librepocket.agent.ui.setup.SetupViewModel.retryPendingDelete]）。
 */
fun effectiveLogoutGuardError(
    logoutGuardError: String?,
    logoutErrorSourceJournal: String?,
    pendingTeardown: PendingTeardownState,
    logoutInFlight: Boolean,
): String? {
    if (logoutInFlight) return null
    if (logoutGuardError == null) {
        return when (pendingTeardown) {
            is PendingTeardownState.KnownPending -> "LOGOUT_UNKNOWN"
            PendingTeardownState.UnknownReadFailure,
            PendingTeardownState.MalformedPending -> "LOGOUT_READ_RETRY"
            PendingTeardownState.Absent -> null
        }
    }
    if (logoutErrorSourceJournal == null) return logoutGuardError
    // STALE 為 dismiss-only 安全碼：任何態皆沿用（待關閉可達）。
    if (logoutGuardError == "LOGOUT_STALE") return logoutGuardError
    return when (pendingTeardown) {
        is PendingTeardownState.KnownPending ->
            if (pendingTeardown.pending.format() == logoutErrorSourceJournal) {
                logoutGuardError
            } else {
                null
            }
        PendingTeardownState.Absent -> null
        PendingTeardownState.UnknownReadFailure,
        PendingTeardownState.MalformedPending -> logoutGuardError
    }
}

/**
 * Stage G 攜身份派生恢復態（R15 F1：F1 派生攜帶有效身份）。
 *
 * 回傳 `(effectiveCode, retryJournal)`：`effectiveCode` 為 UI/導航決策用有效碼
 * （沿用舊 [effectiveLogoutGuardError] 四參語義，惟異源 `J_C` 由隱藏改為攜帶），
 * `retryJournal` 為重試目標身份（`MainScreen.onRetryLogout` 直接使用，不再讀
 * `form.logoutErrorSourceJournal`；提交仍經 `retryPendingDelete` 快照比對，禁盲
 * replay）。
 *
 * 規則（依序，`inFlight=true` 恆 `(null, null)`）：
 * - `code == null` → 沿用今日（`tag` 忽略）：`KnownPending(J)` →
 *   `("LOGOUT_UNKNOWN", J)`；`UnknownReadFailure`／`MalformedPending` →
 *   `("LOGOUT_READ_RETRY", null)`；`Absent` → `(null, null)`；
 * - `code != null`＋`tag == null` → 沿用 `(code, null)`（含 D1 中性
 *   `LOGOUT_READ_RETRY`，無源可比對）；
 * - `code != null`＋`tag != null`：
 *   - `LOGOUT_STALE` → 恆顯 `("LOGOUT_STALE", null)`（dismiss-only，無破壞性重試）；
 *   - `LOGOUT_READ_RETRY` → 沿用 `(code, null)`（中性碼同 `tag-null` 行，不轉
 *     `UNKNOWN`；舊真值表保留）；
 *   - `KnownPending(J)` 且 `J.format() == tag` → 同源沿用 `(code, J)`；
 *   - `KnownPending(J_C)` 且 `J_C.format() != tag` → `("LOGOUT_UNKNOWN", J_C)`
 *    （碼固定中性 `UNKNOWN`，不沿用舊 `DELETE_FAILED`；`J_C` 屬其持有者恢復域，
 *     舊卡不冒充、亦不觸 `J_C` 以外）；
 *   - `Absent` → `(null, null)`（抑制 stale 回落；真清卡靠 `retryPendingDelete`
 *     終端同源清）；
 *   - `UnknownReadFailure`／`MalformedPending` → 保留舊碼 `(code, null)`
 *    （fail-closed 不對稱：未知可能掩蓋本源，禁隱藏；重試走
 *     `retryPendingDelete`→`ReadUnknown`→重試讀取，絕不進清除）。
 *
 * 純函數、無副作用。
 */
data class LogoutRecovery(
    val effectiveCode: String?,
    val retryJournal: PendingDelete?,
)

fun effectiveLogoutRecovery(
    logoutGuardError: String?,
    logoutErrorSourceJournal: String?,
    pendingTeardown: PendingTeardownState,
    logoutInFlight: Boolean,
): LogoutRecovery {
    if (logoutInFlight) return LogoutRecovery(null, null)
    if (logoutGuardError == null) {
        return when (pendingTeardown) {
            is PendingTeardownState.KnownPending ->
                LogoutRecovery("LOGOUT_UNKNOWN", pendingTeardown.pending)
            PendingTeardownState.UnknownReadFailure,
            PendingTeardownState.MalformedPending ->
                LogoutRecovery("LOGOUT_READ_RETRY", null)
            PendingTeardownState.Absent -> LogoutRecovery(null, null)
        }
    }
    if (logoutErrorSourceJournal == null) return LogoutRecovery(logoutGuardError, null)
    if (logoutGuardError == "LOGOUT_STALE") return LogoutRecovery(logoutGuardError, null)
    if (logoutGuardError == "LOGOUT_READ_RETRY") return LogoutRecovery(logoutGuardError, null)
    return when (pendingTeardown) {
        is PendingTeardownState.KnownPending ->
            if (pendingTeardown.pending.format() == logoutErrorSourceJournal) {
                LogoutRecovery(logoutGuardError, pendingTeardown.pending)
            } else {
                LogoutRecovery("LOGOUT_UNKNOWN", pendingTeardown.pending)
            }
        PendingTeardownState.Absent -> LogoutRecovery(null, null)
        PendingTeardownState.UnknownReadFailure,
        PendingTeardownState.MalformedPending -> LogoutRecovery(logoutGuardError, null)
    }
}

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
    // Stage F1: unconfirmed read failure suppresses the Setup hard gate like
    // loading — a transient failure is never a proven Absent.
    isReadFailure: Boolean = false,
): Boolean {
    if (isReadFailure) return false
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
    // Stage F1: the legacy LOGOUT-code steering stays silent on an unconfirmed
    // read — the dedicated read-failure steering below owns this state.
    isReadFailure: Boolean = false,
): Boolean {
    if (isReadFailure) return false
    if (gateLoading) return false
    if (hasEndpoint) return false
    if (logoutInFlight) return false
    if (logoutErrorCode?.startsWith("LOGOUT_") != true) return false
    if (currentRoute != Routes.CHAT) return false
    return true
}

/**
 * 讀取失敗導向純條件（Stage F1：#17 NEW Finding 1 P1）。
 *
 * `ReadFailure` 是需用戶重試的已落定終態（非過渡 loading）：fail-closed 不進
 * SETUP（未證 Absent），而是把 CHAT 上的使用者帶往 SETTINGS 的重試讀取畫面
 *（[SettingsScreen] `ReadFailure` 分支，僅重試讀取）。在途（pending）時不決策
 *（全屏 spinner 照舊，避免 FOUC）；已在 SETTINGS／SETUP 不重導；`null` 初始幀
 * 不決策（NavHost 組成後 currentRoute 落 CHAT 即觸發）。
 *
 * 純函數、無副作用。
 */
fun shouldNavigateToSettingsOnReadFailure(
    isReadFailure: Boolean,
    startupReconcilePending: Boolean,
    currentRoute: String?,
): Boolean {
    if (!isReadFailure) return false
    if (startupReconcilePending) return false
    if (currentRoute != Routes.CHAT) return false
    return true
}
