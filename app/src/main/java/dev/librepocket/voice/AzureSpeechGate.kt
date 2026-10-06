package dev.librepocket.voice

import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor

/**
 * Azure 雲端語音門禁（主源集，純 JVM；SDK 引用只活在 `src/github` 的
 * `AzureSpeechEngine`，此門禁供投影層與單測共用）。
 *
 * 雙開關語義（S2 鎖定）：
 * - `voice_output`（預設開）且 `azure_tts`（預設關）皆開，
 *   且風味為 GITHUB，方可用；
 * - 原因碼：非 GITHUB → `FLAVOR_BLOCKED`；任一開關關閉 → `USER_DISABLED`；
 *   無金鑰 → `NO_PRIVILEGE`。
 */
object AzureSpeechGate {

    /** 雙開關 + 風味皆滿足才可用（金鑰有無由引擎執行期再判）。 */
    fun canUse(
        flavor: Flavor,
        voiceOutputOn: Boolean = VoiceTools.SWITCH_OUTPUT_DEFAULT,
        azureTtsOn: Boolean = VoiceTools.SWITCH_AZURE_DEFAULT,
    ): Boolean =
        flavor == Flavor.GITHUB && voiceOutputOn && azureTtsOn

    /** 投影/執行共用的拒絕原因（null = 放行）。 */
    fun denyReason(
        flavor: Flavor,
        voiceOutputOn: Boolean = VoiceTools.SWITCH_OUTPUT_DEFAULT,
        azureTtsOn: Boolean = VoiceTools.SWITCH_AZURE_DEFAULT,
        hasKey: Boolean = false,
    ): DenyReason? {
        if (flavor != Flavor.GITHUB) return DenyReason.FLAVOR_BLOCKED
        if (!voiceOutputOn || !azureTtsOn) return DenyReason.USER_DISABLED
        if (!hasKey) return DenyReason.NO_PRIVILEGE
        return null
    }
}
