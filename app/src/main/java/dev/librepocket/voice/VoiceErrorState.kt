package dev.librepocket.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 語音雙錯誤通道（#7）：STT 與 TTS 各管各的。
 *
 * - STT 失敗 → [sttError]；STT 成功只清 [sttError]，不碰 TTS；
 * - TTS 失敗（[VoiceSpeaker.onError]）→ [ttsError]；
 * - [ttsError] 只在三處清除：實際送引擎前（[onSpeakStarted]，直讀分支與
 *   確認接受分支）、TTS 成功（[VoiceSpeaker.onDone] → [onTtsSuccess]）、
 *   `voice_output` 關閉（[onOutputDisabled]）；進入確認流程（只記待朗讀
 *   文字）與取消確認皆不碰 [ttsError]；
 * - 純狀態 holder（Compose `mutableStateOf` 驅動重組），轉換規則可純 JVM 單測，
 *   不依賴 Compose 測試框架。
 */
class VoiceErrorState {
    var sttError by mutableStateOf<String?>(null)
        private set
    var ttsError by mutableStateOf<String?>(null)
        private set

    /** STT 成功：只清 STT 錯誤（TTS 錯誤留給 TTS 自己的清除點）。 */
    fun onSttSuccess() {
        sttError = null
    }

    fun onSttFailure(message: String) {
        sttError = message
    }

    fun onTtsFailure(message: String) {
        ttsError = message
    }

    /** 實際送引擎前：先清舊 TTS 錯誤，再送引擎。 */
    fun onSpeakStarted() {
        ttsError = null
    }

    /** TTS 成功（utterance onDone）：清 TTS 錯誤。 */
    fun onTtsSuccess() {
        ttsError = null
    }

    /** `voice_output` 關閉：TTS 通道不再存在，清 TTS 錯誤。 */
    fun onOutputDisabled() {
        ttsError = null
    }
}
