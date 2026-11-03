package dev.librepocket.agent.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * C1 RecognitionService stub（Issue #137 C1a）。
 *
 * C1 不做語音辨識（C2 才在 session 內接系統 STT）：[onStartListening]
 * 誠實回報 [SpeechRecognizer.ERROR_CLIENT]，不偽造辨識結果；
 * [onCancel]／[onStopListening] 無狀態可清，直接 no-op。
 */
class LibreRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent, listener: Callback) {
        try {
            listener.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (_: Exception) {
            // Binder 回調失敗即放棄，不重試、不偽造結果。
        }
    }

    override fun onCancel(listener: Callback) = Unit

    override fun onStopListening(listener: Callback) = Unit
}
