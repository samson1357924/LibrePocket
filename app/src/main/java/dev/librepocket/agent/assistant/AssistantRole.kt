package dev.librepocket.agent.assistant

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * C1 系統預設助理：RoleManager 流程純邏輯（Issue #137 C1b）。
 *
 * - 本檔只含純 Kotlin／平台無關決策（快照 → 決策／原因碼），不直接碰
 *   [android.app.role.RoleManager]，生產接線見 [AssistantRoleUi]，
 *   純邏輯 JVM 單測為主，intent 契約另見 Robolectric。
 * - C1 不新增任何權限：`isRoleAvailable`／`isRoleHeld`／
 *   `createRequestRoleIntent` 皆為同步呼叫；失敗一律 fallback
 *   [Settings.ACTION_VOICE_INPUT_SETTINGS]，fail-closed，不靜默退回。
 * - 原因碼（[assistantReasonText]）：被拒／撤銷／OEM 不支援／請求失敗
 *   皆有對應文案，UI 只顯示文案不做流程分支，色彩可按失敗/中性區分（見 SettingsScreen）。
 */
data class AssistantRoleSnapshot(
    val roleAvailable: Boolean,
    val roleHeld: Boolean,
)

/** Settings 卡中立狀態（仿 AutomationSettingsState：null 即整卡隱藏）。 */
data class AssistantSettingsState(
    val roleHeld: Boolean,
    val roleAvailable: Boolean,
    val reasonCode: String? = null,
    /** fallback 設定頁打不開（resolve null／非系統／拋異常）：卡內紅字提示，不跳轉。 */
    val fallbackFailed: Boolean = false,
)

sealed interface AssistantRoleDecision {
    /** 已持有：長按首頁／手勢可叫出，無需再要求。 */
    data object AlreadyHeld : AssistantRoleDecision

    /** 可要求：Settings 一鍵 `createRequestRoleIntent(ROLE_ASSISTANT)`。 */
    data object RequestRole : AssistantRoleDecision

    /** OEM 不支援：不發要求，直接 fallback 語音輸入設定。 */
    data object UnsupportedFallback : AssistantRoleDecision
}

fun decideAssistantRole(snapshot: AssistantRoleSnapshot): AssistantRoleDecision =
    when {
        snapshot.roleHeld -> AssistantRoleDecision.AlreadyHeld
        snapshot.roleAvailable -> AssistantRoleDecision.RequestRole
        else -> AssistantRoleDecision.UnsupportedFallback
    }

object AssistantReasonCode {
    const val GRANTED = "ASSISTANT_GRANTED"
    const val DENIED = "ASSISTANT_DENIED"
    const val REVOKED = "ASSISTANT_REVOKED"
    const val UNSUPPORTED = "ASSISTANT_UNSUPPORTED"
    const val REQUEST_FAILED = "ASSISTANT_REQUEST_FAILED"
}

/**
 * 雙向調和（純函數）：快照持有態優先於一切原因碼。
 *
 * 四參版（Stage 2 人類 review #2＋#3）：
 * - 若 `snapshot.roleHeld == true`，直接回 null（清除原因碼）：
 *   頭部已表達持有態，原因區不再重複顯示（避免 DENIED＋held 矛盾：
 *   頭部顯示已持有、原因顯示被拒；亦避免 GRANTED＋held 重複文案：
 *   held 頭部與 GRANTED 原因完全同字串）。
 * - 否則若 `justGranted == true`，回 [AssistantReasonCode.GRANTED]：
 *   `RESULT_OK` 後系統落盤有延遲，重讀快照仍非持有是預期競態，
 *   此寬限旗保證回調後首幀不立即誤判 REVOKED；下次 ON_RESUME／快照重讀
 *   後由 [AssistantRoleUi] 清回 false，寬限即結束。
 * - 否則若 `stored == GRANTED`，覆寫為 [AssistantReasonCode.REVOKED]：
 *   曾經授予（sticky GRANTED 碼）但重讀快照已非持有，視為角色被撤銷。
 * - 否則若 `stored == null && everHeld == true`，回 [AssistantReasonCode.REVOKED]：
 *   外部授予→撤銷鏈路：使用者曾在系統設定頁手動授予（未經本應用 request
 *   回調，故 `stored` 仍為 null），之後又在系統側被撤銷；僅憑 `stored`
 *   無法捕捉，須以 `everHeld`（曾經觀察到 `roleHeld == true` 即 sticky）
 *   補上撤銷判定。僅 stored null 時才用 everHeld 判撤銷；顯式新鮮碼
 *  （DENIED／UNSUPPORTED／REQUEST_FAILED／REVOKED）一律 passthrough，
 *   不被 everHeld 覆寫（新鮮拒絕優先於陳舊持有痕跡）。
 * - 其餘原樣 passthrough（含 null 與 DENIED／UNSUPPORTED／REQUEST_FAILED／REVOKED）。
 *
 * `everHeld` 由 SharedPreferences（`"assistant_role"`／`"ever_held"`）持久化，
 * process death 後仍能判定外部撤銷；`justGranted` 僅記憶體態（remember，
 * 下次重讀即清），不落盤。
 *
 * 已知 GRANTED 競態：`RESULT_OK` 後系統未即時落盤時，重讀快照仍非持有會
 * 短暫顯示 REVOKED；該 REVOKED sticky 到下次要求／結果回調（ON_RESUME 重讀
 * 由 AssistantRoleUi Phase 2 已補：LifecycleEventObserver(ON_RESUME)→refreshTick++；
 * 本 Stage 另加 `justGranted` 寬限，首幀保持 GRANTED）。
 */
fun effectiveAssistantReason(
    stored: String?,
    snapshot: AssistantRoleSnapshot,
    everHeld: Boolean,
    justGranted: Boolean,
): String? =
    if (snapshot.roleHeld) {
        null
    } else if (justGranted && stored == AssistantReasonCode.GRANTED) {
        AssistantReasonCode.GRANTED
    } else if (stored == AssistantReasonCode.GRANTED) {
        AssistantReasonCode.REVOKED
    } else if (stored == null && everHeld) {
        AssistantReasonCode.REVOKED
    } else {
        stored
    }

/** 雙參相容 overload：轉調四參版（`everHeld=false, justGranted=false`，舊測語義不變）。 */
fun effectiveAssistantReason(
    stored: String?,
    snapshot: AssistantRoleSnapshot,
): String? =
    effectiveAssistantReason(stored, snapshot, everHeld = false, justGranted = false)

/** 原因碼 → 顯示文案（UI 只顯示文案不做流程分支，色彩可按失敗/中性區分（見 SettingsScreen）；未知碼原樣回報，不偽造語義）。
 *
 * 硬編碼說明（PocketGuard 建議）：本 PR 不做全量 strings.xml 遷移（SettingsScreen
 * 全檔既有硬編碼＋strings.xml 僅 app_name，專案級重構不宜在本 PR）。
 * TODO(i18n-followup): 集中遷移＋純函數改 mapper 注入（原因碼→文案改由呼叫方注入 mapper，本檔保持純邏輯可測）。
 */
fun assistantReasonText(code: String): String =
    when (code) {
        AssistantReasonCode.GRANTED ->
            "已設為預設助理：長按首頁鍵或系統手勢可叫出 LibrePocket。"
        AssistantReasonCode.DENIED ->
            "要求被拒：維持現狀，可改用語音輸入設定或稍後重試。"
        AssistantReasonCode.REVOKED ->
            "預設助理角色已被撤銷：可重新要求，或改用語音輸入設定。"
        AssistantReasonCode.UNSUPPORTED ->
            "此裝置不支援系統助理角色：已改用語音輸入設定。"
        AssistantReasonCode.REQUEST_FAILED ->
            "無法開啟系統授權頁：已改用語音輸入設定。"
        else -> "未知狀態（$code）。"
    }

/**
 * C1 session 喚起入口 Intent（Issue #137：保留現有 ASSIST／SEND，不新增）。
 *
 * `LibreVoiceInteractionSession.onShow` 在此基礎上掛 MainActivity component
 * 後經 `startVoiceActivity` 發出；onShow 本身即使用者手勢（長按首頁／手勢），
 * EntryGate 以手勢放行，無背景啟動濫用。Robolectric 測 action 斷言。
 *
 * 警告：此無參 overload 為隱式 Intent，不可直接 `startActivity`（會劫持到
 * 任意可處理 `ACTION_ASSIST` 的應用）。僅供 session 接線與測試斷言；
 * 生產喚起請用有參 overload [assistantVoiceIntent(android.content.Context)]
 *（限包 implicit，非真 explicit，見該 overload KDoc）。
 */
internal fun assistantVoiceIntent(): Intent = Intent(Intent.ACTION_ASSIST)

/**
 * 限包 implicit 喚起入口（非真 explicit）：以呼叫方包名限定收件者，降低隱式
 * `ACTION_ASSIST` 被第三方助理應用劫持的風險；但 `setPackage` 仍屬 implicit
 * 解析，並非 `setClass`／`setComponent` 的真 explicit。Session 已在
 * 此基礎上再 `setClass` 轉真 explicit（見 LibreVoiceInteractionSession）。
 *
 * 有參版契約由 Phase 4 Robolectric 覆蓋（純 JVM 無 context，本 phase 不強加測試）。
 */
fun assistantVoiceIntent(context: Context): Intent =
    Intent(Intent.ACTION_ASSIST).setPackage(context.packageName)

/**
 * C1b fallback：要求失敗／被拒／OEM 不支援時改開系統語音輸入設定。
 *
 * 不加 [Intent.FLAG_ACTIVITY_NEW_TASK]：本 Intent 自身不帶 task flag，
 * 調用方用 applicationContext＋無條件 NEW_TASK（見 AssistantRoleUi.openFallback）。
 * Flag 斷言留給 Phase 4 Robolectric。
 */
fun assistantFallbackIntent(): Intent =
    Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)

/**
 * Fallback 安全啟動候選（純資料，無 Android 依賴，由呼叫方映射）。
 *
 * @param isSystem 是否為系統應用（呼叫方以
 *   `activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0` 判定）。
 * @param isResolver 是否為系統選單本身（包名 `android` 且類名含
 *   `ResolverActivity`／`ChooserActivity`）；此旗標由呼叫方在映射期計算，
 *   本函數僅做過濾，不做字串判斷。
 */
data class FallbackCandidate(
    val packageName: String,
    val className: String,
    val isSystem: Boolean,
    val isResolver: Boolean,
)

/**
 * 從 fallback 候選中選出唯一可安全 explicit 啟動的系統目標。
 *
 * Resolver 繞過原理：[android.content.pm.PackageManager.resolveActivity]
 * 在「多候選且無預設」時回傳的是系統自身的 ResolverActivity／ChooserActivity
 * （包名 `android` 的系統組件），而非最終會被啟動的應用。因此僅對
 * `resolveActivity` 結果做 `FLAG_SYSTEM` 檢查會誤判為安全——檢查通過後仍以
 * implicit intent `startActivity`，系統會彈出選單讓使用者選到第三方應用，
 * 第三方即拿到 `ACTION_VOICE_INPUT_SETTINGS` 的啟動，原有的「只放行系統」
 * 保證不成立（官方文件：`resolveActivity` 回傳的是將處理 intent 的 activity，
 * 無預設且多候選時即為 resolver 本身；實際目標要經由選單才能確定）。
 *
 * 規則（fail-closed）：
 * 1. 先排除 [FallbackCandidate.isResolver] 為 true 者（系統選單本身）；
 * 2. 再排除 [FallbackCandidate.isSystem] 為 false 者（第三方劫持）；
 * 3. 剩餘恰 1 個 → 回傳它（呼叫方以 `setComponent` explicit 啟動，不再經選單）；
 *    剩餘為 0 或 ≥2（多系統候選無法安全決定唯一目標）→ 回傳 null，
 *    呼叫方顯示失敗，不放行。
 */
fun selectFallbackTarget(candidates: List<FallbackCandidate>): FallbackCandidate? {
    val systemOnly = candidates.filter { !it.isResolver && it.isSystem }
    return if (systemOnly.size == 1) systemOnly[0] else null
}
