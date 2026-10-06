package dev.librepocket.voice.github

import com.microsoft.cognitiveservices.speech.ResultReason
import com.microsoft.cognitiveservices.speech.SpeechConfig
import com.microsoft.cognitiveservices.speech.SpeechSynthesisResult
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer
import dev.librepocket.keystore.KeyVault
import dev.librepocket.redact.Redactor
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.S1bFallback
import dev.librepocket.voice.AzureSpeechGate
import dev.librepocket.voice.VoiceTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * S2 Azure 雲端語音次選引擎（`src/github` 限定，github 版專用；TTS 專用）。
 *
 * - 地位：系統 STT/TTS 優先，Azure 只做 TTS 次選（雙開關 `voice_output` 且
 *   `azure_tts`，後者預設關；見 [AzureSpeechGate]）。STT 一律走系統
 *   RecognizerIntent 委託（零 RECORD_AUDIO），本引擎不提供雲端辨識：
 *   麥克風直連雲端需要錄音權限與音檔鏈路，與 S2 零新權限紅線衝突，
 *   故 `transcribeOnce` 已刪除。
 * - 金鑰：[KeyVault] providerId `"azure_speech"`（[KEY_REF]）；讀出的
 *   `CharArray` 在 `finally` 內覆寫抹除。
 * - 隱私：送雲文字先走 [Redactor.redact]（[VoiceTools.redactForCloud]）；
 *   音檔不落地：合成走預設喇叭輸出，記憶體位元組不寫檔
 *   （本檔無任何 `java.io.File` 引用）。
 * - TODO(S2-voice)：本引擎尚無產品呼叫者。落地需補：設定頁 Azure key
 *   （[KeyVault] `azure_speech`）與 region（非密鑰字串）入口、
 *   朗讀鏈路的 Azure fallback 接線（雙開關皆開且有 key 時才走本引擎，
 *   否則維持系統 TTS）。
 * - 本檔引用 `com.microsoft.cognitiveservices.speech`（FOSS 黑名單針，
 *   見 `HardeningPolicy.FOSS_STRING_BLACKLIST`）：絕不可移入主源集。
 */
object AzureSpeechEngine {

    /** KeyVault providerId（`VoiceTools.AZURE_KEY_REF` 的鏡像，避免主/github 模組硬依賴）。 */
    const val KEY_REF = "azure_speech"

    const val DEFAULT_VOICE = "zh-TW-HsiaoChenNeural"

    const val DETAIL_FLAVOR = "GITHUB_ONLY"
    const val DETAIL_SWITCH_OFF = "SWITCH_OFF"
    const val DETAIL_NO_KEY = "NO_KEY"
    const val DETAIL_EMPTY_TEXT = "EMPTY_TEXT"
    const val DETAIL_SDK_ERROR = "SDK_ERROR"

    sealed interface Check {
        data object Allowed : Check
        data class Denied(val reason: DenyReason, val detail: String, val message: String) : Check
    }

    sealed interface SynthOutcome {
        data object Ok : SynthOutcome
        data class Denied(val reason: DenyReason, val detail: String, val message: String) : SynthOutcome
        data class Failed(val detail: String, val message: String) : SynthOutcome
    }

    private fun denied(reason: DenyReason, detail: String, what: String, need: String): Check.Denied =
        Check.Denied(
            reason = reason,
            detail = detail,
            message = S1bFallback.message(
                what = what,
                reason = reason,
                detail = detail,
                alternative = "use the system voice output, or read the text on screen manually",
                needFromUser = need,
            ),
        )

    /** 執行前門禁（投影層之外、呼叫雲端之前的第二道門）。 */
    suspend fun check(
        flavor: Flavor,
        voiceOutputOn: Boolean,
        azureTtsOn: Boolean,
        vault: KeyVault,
    ): Check {
        if (flavor != Flavor.GITHUB) {
            return denied(DenyReason.FLAVOR_BLOCKED, DETAIL_FLAVOR, "Azure 語音（$DETAIL_FLAVOR）", "使用直接下載版並開啟 Azure 語音")
        }
        if (!voiceOutputOn || !azureTtsOn) {
            return denied(DenyReason.USER_DISABLED, DETAIL_SWITCH_OFF, "Azure 語音（$DETAIL_SWITCH_OFF）", "到設定同時開啟「語音輸出」與「Azure 語音」後重試")
        }
        val hasKey = try {
            vault.hasKey(KEY_REF)
        } catch (_: Exception) {
            false
        }
        if (!hasKey) {
            return denied(DenyReason.NO_PRIVILEGE, DETAIL_NO_KEY, "Azure 語音（$DETAIL_NO_KEY）", "先在設定寫入 Azure Speech 金鑰後重試")
        }
        return Check.Allowed
    }

    /**
     * 雲端合成並經預設喇叭播放（音檔不寫檔）。
     *
     * @param region Azure region（非密鑰，由呼叫方以設定傳入）。
     */
    suspend fun synthesizeToSpeaker(
        text: String,
        region: String,
        vault: KeyVault,
        flavor: Flavor = Flavor.GITHUB,
        voiceOutputOn: Boolean = true,
        azureTtsOn: Boolean = true,
        voiceName: String = DEFAULT_VOICE,
    ): SynthOutcome = withContext(Dispatchers.IO) {
        when (val c = check(flavor, voiceOutputOn, azureTtsOn, vault)) {
            is Check.Denied -> return@withContext SynthOutcome.Denied(c.reason, c.detail, c.message)
            is Check.Allowed -> Unit
        }
        // 送雲前再 redact：雲端只收已遮罩文字。空正文視為呼叫方未提供可朗讀內容，
        // 屬使用者側可修正（USER_DISABLED：需要使用者提供文字），非權限缺失。
        val redacted = VoiceTools.redactForCloud(text)
        if (redacted.isBlank()) {
            return@withContext SynthOutcome.Denied(
                DenyReason.USER_DISABLED,
                DETAIL_EMPTY_TEXT,
                S1bFallback.message(
                    what = "Azure 語音（$DETAIL_EMPTY_TEXT）",
                    reason = DenyReason.USER_DISABLED,
                    detail = DETAIL_EMPTY_TEXT,
                    alternative = "read the text on screen manually",
                    needFromUser = "提供要朗讀的文字",
                ),
            )
        }
        val key = try {
            vault.getKey(KEY_REF)
        } catch (e: Exception) {
            return@withContext SynthOutcome.Failed(
                DETAIL_NO_KEY,
                "讀取 Azure 金鑰失敗：${Redactor.redactError(e.message ?: e.javaClass.simpleName)}",
            )
        } ?: return@withContext SynthOutcome.Denied(
            DenyReason.NO_PRIVILEGE,
            DETAIL_NO_KEY,
            S1bFallback.message(
                what = "Azure 語音（$DETAIL_NO_KEY）",
                reason = DenyReason.NO_PRIVILEGE,
                detail = DETAIL_NO_KEY,
                alternative = "use the system voice output",
                needFromUser = "先在設定寫入 Azure Speech 金鑰後重試",
            ),
        )
        try {
            // NOTE(m1)：concatToString() 把金鑰拷貝進不可變 String，JVM 層無法抹除，
            // 只能等 GC；此處把 keyStr 生命週期壓到最小（僅組 SpeechConfig 用），
            // 用完即離開作用域。CharArray 本體仍在 finally 覆寫抹除。
            val keyStr = key.concatToString()
            val config = SpeechConfig.fromSubscription(keyStr, region)
            try {
                config.speechSynthesisVoiceName = voiceName
                SpeechSynthesizer(config).use { synth ->
                    val result: SpeechSynthesisResult = try {
                        synth.SpeakTextAsync(redacted).get()
                    } catch (e: Exception) {
                        return@withContext SynthOutcome.Failed(
                            DETAIL_SDK_ERROR,
                            "Azure 合成失敗：${Redactor.redactError(e.message ?: e.javaClass.simpleName)}",
                        )
                    } finally {
                        try {
                            config.close()
                        } catch (_: Exception) {
                        }
                    }
                    try {
                        if (result.reason == ResultReason.SynthesizingAudioCompleted) {
                            SynthOutcome.Ok
                        } else {
                            SynthOutcome.Failed(
                                DETAIL_SDK_ERROR,
                                "Azure 合成未完成（${result.reason}）",
                            )
                        }
                    } finally {
                        try {
                            result.close()
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (e: Exception) {
                try {
                    config.close()
                } catch (_: Exception) {
                }
                SynthOutcome.Failed(
                    DETAIL_SDK_ERROR,
                    "Azure 合成失敗：${Redactor.redactError(e.message ?: e.javaClass.simpleName)}",
                )
            }
        } finally {
            key.fill('\u0000')
        }
    }

    /** Kotlin `use` for SDK AutoCloseable without stdlib dependency surprises. */
    private inline fun <T : AutoCloseable, R> T.use(block: (T) -> R): R {
        try {
            return block(this)
        } finally {
            try {
                close()
            } catch (_: Exception) {
            }
        }
    }
}
