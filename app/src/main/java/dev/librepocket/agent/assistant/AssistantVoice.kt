package dev.librepocket.agent.assistant

import android.content.Intent
import dev.librepocket.entry.EntryNormalize

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
 *
 * 信任邊界與不變量：
 * - [isVoiceInputRequested] 只接受 `Intent.ACTION_ASSIST` 且非系統歷史重放
 *   （`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` 必須為 0）的 intent，阻斷外部非 ASSIST
 *   或從最近任務重放時重複喚起 STT；
 * - 該 flag 只是 UI 提示（開系統辨識框），不是 entry 正文：
 *   不進 `EntryNormalize`，偽造的 ASSIST＋flag 最多打開辨識框，
 *   結果經 [resolveVoicePrefill] 截斷至 [EntryNormalize.MAX_TEXT_LENGTH]（4000 字）後
 *   只 prefill、不自動送出（見 [AUTO_SEND_AFTER_VOICE]），無能力增益。
 */
internal object AssistantVoice {
    /** ASSIST intent 附帶 voice 請求的 extra key（字面值穩定，Robolectric 鎖定）。 */
    const val EXTRA_VOICE_INPUT = "dev.librepocket.extra.ASSIST_VOICE_INPUT"

    /**
     * Session-instance nonce bound to the initial trusted handoff; the voice-task MainActivity
     * echoes it back so retrigger delivery/consume can be bound to the trusted voice task.
     * Not a secret; grants nothing by itself.
     */
    const val EXTRA_SESSION_INSTANCE_ID = "dev.librepocket.extra.ASSIST_SESSION_INSTANCE_ID"

    /**
     * 語音結果只做 prefill，不自動送出（恆 false，由使用者確認後送出）。
     * 未來任何自動送出都必須另立決策並改此常數＋測試，此處先鎖死。
     */
    const val AUTO_SEND_AFTER_VOICE = false

    /** 在喚起 intent 上掛 voice 請求（回傳同一 intent，不改 action／package／component）。 */
    fun withVoiceInput(intent: Intent): Intent =
        intent.putExtra(EXTRA_VOICE_INPUT, true)

    /**
     * 解析喚起 intent 是否帶合法 voice 請求。
     *
     * 信任邊界：必須為 [Intent.ACTION_ASSIST]、不得帶
     * [Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY]（防最近任務重放誤觸發），
     * 且 [EXTRA_VOICE_INPUT] 顯式為 `true`；null 或不符合者一律 `false`。
     */
    fun isVoiceInputRequested(intent: Intent?): Boolean {
        if (intent == null) return false
        if (intent.action != Intent.ACTION_ASSIST) return false
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return false
        return intent.getBooleanExtra(EXTRA_VOICE_INPUT, false)
    }

    /**
     * C2 回填語義（純函數，JVM 可測）：辨識候選取首條非空正文並截斷至
     * [EntryNormalize.MAX_TEXT_LENGTH]（4000 字）；
     * 無結果回 null（呼叫方留手動輸入，不報錯不自動送出）。
     *
     * trim/empty 檢查為防禦性邊界：涵蓋 `VoiceStt.pickResult` 契約與未修剪／純空白候選，
     * 長度上限則維持 prefill-only 邊界（不自動送出，亦不接受超長回填）。
     * `VoiceStt.pickResult` 保留作為測試鎖定的參考契約，production-dead（production 使用本檔的 bounded variant）。
     * 首個非空白字元定位後，body ≤ cap 時 trimEnd（≡ 原 trim 的後綴側），body > cap 時取前 cap 字元（≡ 原 trim 後 take(cap) 的前綴）——逐位元組等價於舊 pipeline（trim→take(4000)），且兩分支皆 bounded copy；空候選→null。
     * 實作經單次反向掃描定位 end（跳過尾綴空白）後計算 take = min(end−start, cap)，為舊 pipeline 的逐位元組等價且 bounded copy；尾端空白超界情境由 AssistantVoiceTest 釘住。
     */
    fun resolveVoicePrefill(results: List<*>?): String? {
        results ?: return null
        for (element in results) {
            val candidate = element as? String ?: continue
            // 首個非空白字元 start（O(n) 掃描，零配置）與尾綴空白跑終點 end（反向有界掃描，零配置）：
            //  take = min(end - start, MAX_TEXT_LENGTH)；substring bounded copy。
            // 逐位元組等價於舊 pipeline（trim → take(4000) → 非空即採納），且無無界複製。
            val start = candidate.indexOfFirst { !it.isWhitespace() }
            val trimmed = if (start == -1) {
                null
            } else {
                var end = candidate.length
                while (end > start && candidate[end - 1].isWhitespace()) end--
                candidate.substring(start, start + minOf(end - start, EntryNormalize.MAX_TEXT_LENGTH))
                    .takeIf { it.isNotEmpty() }
            }
            if (trimmed != null) return trimmed
        }
        return null
    }
}
