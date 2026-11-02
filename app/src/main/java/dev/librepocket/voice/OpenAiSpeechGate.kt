package dev.librepocket.voice

import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor

/**
 * OpenAI 雲端語音門禁（主源集，純 JVM，零 Android 依賴）。
 *
 * 規則與安全契約：
 * - 支援全部風味（PLAY, FOSS, GITHUB）：全 flavor 放行，不回傳 [DenyReason.FLAVOR_BLOCKED]；
 * - 雙開關語義：`voice_output`（預設開）且 `openai_tts`（預設關）皆開，方可用；
 *   任一開關關閉回傳 [DenyReason.USER_DISABLED]；
 * - 金鑰檢查：無金鑰回傳 [DenyReason.NO_PRIVILEGE]；
 * - 門禁放行回傳 null。
 */
object OpenAiSpeechGate {

    /** 雙開關皆開才可用（全風味支援）。 */
    fun canUse(
        voiceOutputOn: Boolean = VoiceTools.SWITCH_OUTPUT_DEFAULT,
        openAiTtsOn: Boolean = VoiceTools.SWITCH_OPENAI_DEFAULT,
    ): Boolean = voiceOutputOn && openAiTtsOn

    /** 全風味過載，語義等同 [canUse]（全風味放行）。 */
    fun canUse(
        flavor: Flavor,
        voiceOutputOn: Boolean = VoiceTools.SWITCH_OUTPUT_DEFAULT,
        openAiTtsOn: Boolean = VoiceTools.SWITCH_OPENAI_DEFAULT,
    ): Boolean = canUse(voiceOutputOn, openAiTtsOn)

    /** 拒絕原因（null = 放行）。 */
    fun denyReason(
        voiceOutputOn: Boolean = VoiceTools.SWITCH_OUTPUT_DEFAULT,
        openAiTtsOn: Boolean = VoiceTools.SWITCH_OPENAI_DEFAULT,
        hasKey: Boolean = false,
    ): DenyReason? {
        if (!voiceOutputOn || !openAiTtsOn) return DenyReason.USER_DISABLED
        if (!hasKey) return DenyReason.NO_PRIVILEGE
        return null
    }

    /** 全風味過載，語義等同 [denyReason]（全風味放行，不阻擋任何 flavor）。 */
    fun denyReason(
        flavor: Flavor,
        voiceOutputOn: Boolean = VoiceTools.SWITCH_OUTPUT_DEFAULT,
        openAiTtsOn: Boolean = VoiceTools.SWITCH_OPENAI_DEFAULT,
        hasKey: Boolean = false,
    ): DenyReason? = denyReason(voiceOutputOn, openAiTtsOn, hasKey)
}
