package dev.librepocket.agent.assistant

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import dev.librepocket.agent.MainActivity

private const val TAG = "LibreVoiceSession"

// TODO(C2-assistant, Issue #137): session 內 Compose／串流 STT 不做——直接
// SpeechRecognizer 需本 App 錄音權限，與 C1 零新增權限契約衝突；
// C2 以 RecognizerIntent 委派完成（見 AssistantVoice）。
// MainActivity 已強制執行 isVoiceInteractionRoot（isTrustedVoiceSession()）
// 區分系統語音互動工作階段與外部偽造 ACTION_ASSIST；
// 實機 E2E 驗證（手勢／鎖屏／返回／多次喚起＋Samsung／Xiaomi 實機）仍於 #137 追蹤。

/**
 * C1/C2 語音助理 session（Issue #137）。
 *
 * `onShow` 後以 [assistantVoiceIntent(android.content.Context)] 為基礎，
 * 此處再 `setClass` 轉真 explicit，掛 MainActivity component（真 explicit）
 * 並經 `startVoiceActivity` 發出
 * `ACTION_ASSIST`（沿用既有 ASSIST 入口），再 `hide()`。C1/C2 皆不在 session 內做
 * UI／串流 STT（C2 改走 RecognizerIntent 委派）；喚起失敗亦 `hide()`，fail-closed，不殘留空白 session。
 *
 * `hide()` 與 `startVoiceActivity` 成功與否無關，兩路皆於 `finally` 執行
 * （成功亦 hide——UI 由 MainActivity 承載；失敗亦 hide——fail-closed，
 * 不殘留空白 session）。
 *
 * C2 UX 設計決策（專用 voice task）：`startVoiceActivity(assist)` 依系統語音助理
 * 契約建立獨立的 voice task（與主 App 現有對話草稿相互獨立，不覆寫主任務草稿）；
 * MainActivity 收到 `AssistantVoice.EXTRA_VOICE_INPUT` 後自動走既有
 * RecognizerIntent 委派，結果只 prefill 不自動送出（[AssistantVoice.AUTO_SEND_AFTER_VOICE]）。
 * session 本體不做 Compose／串流 STT（直接 SpeechRecognizer 需本 App 錄音權限，
 * 與 C1 零新增權限契約衝突）。
 *
 * 信任邊界：`ACTION_ASSIST` 是既有隱式入口，第三方應用亦可偽造發起——
 * `MainActivity` 透過 `isVoiceInteractionRoot`（`isTrustedVoiceSession()`）強制
 * 區分由 `startVoiceActivity` 啟動的系統可信工作階段與外部偽造 intent：
 * 不可信喚起僅開啟 App（EntryGate：ASSIST 無文字即只開 App、不自動發送，
 * 亦不自動觸發 STT），不授予額外能力。
 */
open class LibreVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    /**
     * Activity-only 流程（人類 review #5 session UI）：關預設 voice UI window，
     * 防空白閃爍；實際 UI 由 MainActivity 承載，session 本體不留可見 window。
     */
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        try {
            // C2：掛 voice 請求 flag（見 AssistantVoice：只是 UI 提示，
            // MainActivity 收到後自動走既有 RecognizerIntent 委派；零新權限）。
            val assist = AssistantVoice.withVoiceInput(assistantVoiceIntent(context)).setClass(
                context,
                MainActivity::class.java,
            )
            startVoiceActivity(assist)
        } catch (e: Exception) {
            Log.w(TAG, "startVoiceActivity failed; hiding session", e)
        } finally {
            // hide() 與成功與否無關（見上 KDoc）：成功與失敗兩路皆執行。
            hide()
        }
    }
}
