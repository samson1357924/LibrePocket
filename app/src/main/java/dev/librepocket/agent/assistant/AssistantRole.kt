package dev.librepocket.agent.assistant

import android.content.Intent
import android.provider.Settings

/**
 * C1 系統預設助理：RoleManager 流程純邏輯（Issue #137 C1b）。
 *
 * - 本檔只含純 Kotlin／平台無關決策（快照 → 決策／原因碼），不直接碰
 *   [android.app.role.RoleManager]，生產接線見 [AssistantRoleUi]，
 *   測試全部用 fake 快照（JVM 單測，無 Robolectric）。
 * - C1 不新增任何權限：`isRoleAvailable`／`isRoleHeld`／
 *   `createRequestRoleIntent` 皆為同步呼叫；失敗一律 fallback
 *   [Settings.ACTION_VOICE_INPUT_SETTINGS]，fail-closed，不靜默退回。
 * - 原因碼（[assistantReasonText]）：被拒／撤銷／OEM 不支援／請求失敗
 *   皆有對應文案，UI 只顯示、不分支。
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
 * 撤銷偵測（純函數）：曾經授予（stored == GRANTED）但重讀快照已非持有，
 * 視為角色被撤銷，覆寫為 [AssistantReasonCode.REVOKED]。
 */
fun effectiveAssistantReason(
    stored: String?,
    snapshot: AssistantRoleSnapshot,
): String? =
    if (stored == AssistantReasonCode.GRANTED && !snapshot.roleHeld) {
        AssistantReasonCode.REVOKED
    } else {
        stored
    }

/** 原因碼 → 顯示文案（UI 只顯示、不分支；未知碼原樣回報，不偽造語義）。 */
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
 */
fun assistantVoiceIntent(): Intent = Intent(Intent.ACTION_ASSIST)

/** C1b fallback：要求失敗／被拒／OEM 不支援時改開系統語音輸入設定。 */
fun assistantFallbackIntent(): Intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
