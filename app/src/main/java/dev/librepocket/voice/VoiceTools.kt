package dev.librepocket.voice

import dev.librepocket.redact.Redactor

/**
 * S2 語音工具常數（主源集，純 JVM，零 Android 依賴）。
 *
 * - `voice.transcribe`（READ）：系統語音辨識正文入口，開關 `voice_input` 預設開。
 * - `voice.speak`（WRITE）：系統 TTS 朗讀入口，開關 `voice_output` 預設開。
 * - `voice.speak.azure`（WRITE，僅 GITHUB）：Azure 雲端語音次選，需同時開啟
 *   `voice_output` 且 `azure_tts`（後者預設關）。
 *   SCAFFOLD（PR#1 review 收斂）：雲端分派接線（設定頁 key/region 入口 +
 *   朗讀鏈路 fallback）尚未落地，[AzureSpeechEngine] 尚無產品呼叫者；
 *   在此之前投影 NATIVE 僅表示開關語義，實際呼叫走系統語音降級
 *   （見 ToolRegistry fallbackHint）。接線 PR 翻轉此註記並附端到端測試。
 *
 * 隱私不變量（S2 鎖定）：
 * - 語音正文寫入 [RoomSessionStore][dev.librepocket.session.RoomSessionStore]
 *   前一律走 [Redactor.redact]（該類寫路徑已全覆蓋，此處再收斂一次）；
 * - 音檔不落地：麥克風音訊只駐留記憶體，絕不寫檔；
 * - 送雲（Azure）前再 redact 一次，設定頁明示此行為。
 * - 系統 TTS 手勢路徑採明確揭露（逐則點按 + 引擎揭露文案，
 *   見 [VoiceTts.SPEAK_BUTTON_DESCRIPTION][dev.librepocket.voice.VoiceTts]），
 *   不做送引擎前 redact（避免朗讀遮罩 token；可見原文即使用者點按的當則訊息）；
 *   雲端（Azure）自動上傳路徑維持送雲前 redact（[redactForCloud]）。
 */
object VoiceTools {

    const val TRANSCRIBE_NAME = "voice.transcribe"
    const val SPEAK_NAME = "voice.speak"
    const val AZURE_SPEAK_NAME = "voice.speak.azure"
    const val OPENAI_SPEAK_NAME = "voice.speak.openai"

    const val SWITCH_INPUT = "voice_input"
    const val SWITCH_INPUT_DEFAULT = true

    const val SWITCH_OUTPUT = "voice_output"
    const val SWITCH_OUTPUT_DEFAULT = true

    const val SWITCH_AZURE = "azure_tts"
    const val SWITCH_AZURE_DEFAULT = false

    const val SWITCH_OPENAI = "openai_tts"
    const val SWITCH_OPENAI_DEFAULT = false

    /** KeyVault providerId：Azure Speech 金鑰（僅 github 版使用）。 */
    const val AZURE_KEY_REF = "azure_speech"

    /** KeyVault providerId：OpenAI TTS 金鑰。 */
    const val OPENAI_KEY_REF = "openai_tts"

    const val FALLBACK_STT_HINT = "type the message manually in the input box"
    const val FALLBACK_TTS_HINT = "read the message text on screen manually"

    /** 寫入轉錄庫前的正文收斂（冪等；底層 Room 寫路徑亦會再 redact）。 */
    fun redactForStore(text: String): String = Redactor.redact(text).text

    /** 送雲（Azure）前的正文收斂：雲端只收已遮罩文字。 */
    fun redactForCloud(text: String): String = Redactor.redact(text).text

    /**
     * UI 門禁共用判定（#3，Stage3）：與 `ToolRegistry` 投影同 key、同預設，
     * 缺鍵即預設值。`ChatScreen` 與 `ProjectionContext` 皆經此讀開關，
     * 開關關閉即隱藏對應入口。
     */
    fun inputEnabled(switches: Map<String, Boolean>): Boolean =
        switches.getOrDefault(SWITCH_INPUT, SWITCH_INPUT_DEFAULT)

    /** 見 [inputEnabled]（`voice_output` 預設開）。 */
    fun outputEnabled(switches: Map<String, Boolean>): Boolean =
        switches.getOrDefault(SWITCH_OUTPUT, SWITCH_OUTPUT_DEFAULT)

    /**
     * 快照門禁（R4-2 三態）：載入完成且未失敗才看開關值；loading／讀錯一律關閉
     * （fail-closed，不經產品預設開）。`MainScreen` 餵 `ChatScreen` 前與設定頁
     * 開關顯示皆經此判定。
     */
    fun inputEnabled(snapshot: VoiceSwitchSnapshot): Boolean =
        snapshot.loaded && !snapshot.loadFailed && inputEnabled(snapshot.switches)

    /** 見 [inputEnabled]（快照三態門禁，`voice_output` 側）。 */
    fun outputEnabled(snapshot: VoiceSwitchSnapshot): Boolean =
        snapshot.loaded && !snapshot.loadFailed && outputEnabled(snapshot.switches)

    /** 見 [outputEnabled]（`openai_tts` 預設關）。 */
    fun openAiTtsEnabled(switches: Map<String, Boolean>): Boolean =
        switches.getOrDefault(SWITCH_OPENAI, SWITCH_OPENAI_DEFAULT)

    /** 見 [outputEnabled]（快照三態門禁，`openai_tts` 側）。 */
    fun openAiTtsEnabled(snapshot: VoiceSwitchSnapshot): Boolean =
        snapshot.loaded && !snapshot.loadFailed && openAiTtsEnabled(snapshot.switches)

    /**
     * 投影用開關（R4-2）：同一快照在 loadFailed 時兩鍵視為關（fail-closed），
     * 避免 UI 隱藏但 tool 仍可用的分叉；loading 態照常回 map（缺鍵即產品預設）。
     * Tool 側無 UI 等待語義（投影為純函數、無首幀概念，且語音工具現階段皆
     * `executionReady=false` 不進模型名單），故 loading 沿用既有預設而不等待。
     * 未來投影接線時以此結果為 `ProjectionContext(userSwitches=...)`。
     */
    fun projectionSwitches(snapshot: VoiceSwitchSnapshot): Map<String, Boolean> =
        if (snapshot.loadFailed) {
            snapshot.switches + mapOf(
                SWITCH_INPUT to false,
                SWITCH_OUTPUT to false,
                SWITCH_OPENAI to false,
            )
        } else {
            snapshot.switches
        }
}
