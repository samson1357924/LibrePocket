package dev.librepocket.agent.assistant

import android.content.Intent
import dev.librepocket.voice.VoiceStt

/**
 * C2 session 喚起→STT 文字回填契約（Issue #137 C2，三 flavor 共用）。
 *
 * 設計決策（權限路徑選擇）：
 * - 直接用 `android.speech.SpeechRecognizer` 在自家 process 做串流辨識，
 *   需要 `RECORD_AUDIO`（本 App 錄音）——與 C1「零新增權限」契約衝突，
 *   故 C2 不走此路（`LibreRecognitionService` stub 保持誠實無能力，
 *   不偽造結果；session 內 Compose＋串流 STT 永不做，除非另立 C3 權限決策）。
 * - C2 走 `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` 委派：錄音由系統／
 *   第三方辨識 Activity 處理，本 App 不加 `RECORD_AUDIO`（既有
 *   `VoiceStt`＋`ChatScreen.launchSystemStt` 同一路徑，結果經
 *   `ChatViewModel.prefill()` 只進輸入框、不自動送出）。
 *
 * 喚起鏈：`LibreVoiceInteractionSession.onShow` 在 ASSIST intent 掛
 * [EXTRA_VOICE_INPUT] → `MainActivity` 轉一次態 →
 * `MainScreen` → `ChatScreen` 自動走既有 `launchSystemStt()`。
 * 該 flag 只是 UI 提示（開系統辨識框），不是 entry 正文：
 * 不進 `EntryNormalize`，偽造的 ASSIST＋flag 最多打開辨識框，
 * 結果仍只 prefill、不自動送出（見 [AUTO_SEND_AFTER_VOICE]），無能力增益。
 */
object AssistantVoice {
    /** ASSIST intent 附帶 voice 請求的 extra key（字面值穩定，Robolectric 鎖定）。 */
    const val EXTRA_VOICE_INPUT = "dev.librepocket.extra.ASSIST_VOICE_INPUT"

    /**
     * 語音結果只做 prefill，不自動送出（恆 false，由使用者確認後送出）。
     * 未來任何自動送出都必須另立決策並改此常數＋測試，此處先鎖死。
     */
    const val AUTO_SEND_AFTER_VOICE = false

    /** 在喚起 intent 上掛 voice 請求（回傳同一 intent，不改 action／package／component）。 */
    fun withVoiceInput(intent: Intent): Intent =
        intent.putExtra(EXTRA_VOICE_INPUT, true)

    /** 解析喚起 intent 是否帶 voice 請求（null／無 extra／非 true 一律 false）。 */
    fun isVoiceInputRequested(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_VOICE_INPUT, false) == true

    /**
     * C2 回填語義（純函數，JVM 可測）：辨識候選取首條非空正文；
     * 無結果回 null（呼叫方留手動輸入，不報錯不自動送出）。
     */
    fun resolveVoicePrefill(results: List<String>?): String? =
        VoiceStt.pickResult(results)
}
