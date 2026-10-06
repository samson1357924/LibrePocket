package dev.librepocket.voice

import dev.librepocket.redact.Redactor

/**
 * S2 語音工具常數（主源集，純 JVM，零 Android 依賴）。
 *
 * - `voice.transcribe`（READ）：系統語音辨識正文入口，開關 `voice_input` 預設開。
 * - `voice.speak`（WRITE）：系統 TTS 朗讀入口，開關 `voice_output` 預設開。
 * - `voice.speak.azure`（WRITE，僅 GITHUB）：Azure 雲端語音次選，需同時開啟
 *   `voice_output` 且 `azure_tts`（後者預設關）。
 *
 * 隱私不變量（S2 鎖定）：
 * - 語音正文寫入 [RoomSessionStore][dev.librepocket.session.RoomSessionStore]
 *   前一律走 [Redactor.redact]（該類寫路徑已全覆蓋，此處再收斂一次）；
 * - 音檔不落地：麥克風音訊只駐留記憶體，絕不寫檔；
 * - 送雲（Azure）前再 redact 一次，設定頁明示此行為。
 */
object VoiceTools {

    const val TRANSCRIBE_NAME = "voice.transcribe"
    const val SPEAK_NAME = "voice.speak"
    const val AZURE_SPEAK_NAME = "voice.speak.azure"

    const val SWITCH_INPUT = "voice_input"
    const val SWITCH_INPUT_DEFAULT = true

    const val SWITCH_OUTPUT = "voice_output"
    const val SWITCH_OUTPUT_DEFAULT = true

    const val SWITCH_AZURE = "azure_tts"
    const val SWITCH_AZURE_DEFAULT = false

    /** KeyVault providerId：Azure Speech 金鑰（僅 github 版使用）。 */
    const val AZURE_KEY_REF = "azure_speech"

    const val FALLBACK_STT_HINT = "type the message manually in the input box"
    const val FALLBACK_TTS_HINT = "read the message text on screen manually"

    /** 寫入轉錄庫前的正文收斂（冪等；底層 Room 寫路徑亦會再 redact）。 */
    fun redactForStore(text: String): String = Redactor.redact(text).text

    /** 送雲（Azure）前的正文收斂：雲端只收已遮罩文字。 */
    fun redactForCloud(text: String): String = Redactor.redact(text).text
}
