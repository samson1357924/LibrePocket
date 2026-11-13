package dev.librepocket.agent.assistant

import android.content.AttributionSource
import android.content.Intent
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.SpeechRecognizer
import android.util.Log

private const val TAG = "LibreRecognition"

// C2 定案：session 內不做串流 STT（自家 process 辨識需要 RECORD_AUDIO，
// 與零新增權限契約衝突）。本 stub 保持誠實無能力：不接系統 STT、不偽造結果。
// 語音輸入走 AssistantVoice 委派 RecognizerIntent（錄音由系統／第三方辨識
// Activity 承擔）。

/**
 * C1 RecognitionService stub（Issue #137 C1a）。
 *
 * C1 不做語音辨識（C2 才在 session 內接系統 STT）：[onStartListening]
 * 誠實回報 [SpeechRecognizer.ERROR_CLIENT]，不偽造辨識結果；
 * [onCheckRecognitionSupport] 回報零支援（空語言清單），不偽造能力；
 * [onCancel]／[onStopListening] 無狀態可清，直接 no-op。
 *
 * STT 遷移計畫（C2）：在 session 內接系統 STT（串流辨識），取代本 stub；
 * 本 stub 的 onError／onResults 僅為佔位（fail-closed，不偽造結果）。
 * ERROR_CLIENT 選擇理由：誠實客戶端錯誤（C1 無辨識能力）；[onCheckRecognitionSupport]
 * 已報零支援故本方法極少被調；若實測發現系統重試 aggressive，再評估 ERROR_SERVER，
 * 目前保持語義正確。
 */
class LibreRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent, listener: Callback) {
        try {
            listener.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (e: Exception) {
            // Binder 回調失敗即放棄，不重試、不偽造結果。
            Log.w(TAG, "onStartListening callback failed", e)
        }
    }

    override fun onCheckRecognitionSupport(intent: Intent, callback: SupportCallback) {
        // 雙參版（API 33＋）：C1 無辨識能力，回報空語言清單＝零支援。
        // 三參 AttributionSource 版見下（同樣回空支援，避免委託方向不確定）。
        reportNoSupport(callback)
    }

    override fun onCheckRecognitionSupport(
        intent: Intent,
        attributionSource: AttributionSource,
        callback: SupportCallback,
    ) {
        // 三參版（API 33＋）顯式覆寫、同樣回空支援：基類雙參／三參互調方向
        // 依版本實作而定，只覆寫一邊可能被另一路徑繞過，兩邊同覆寫最穩。
        reportNoSupport(callback)
    }

    private fun reportNoSupport(callback: SupportCallback) {
        try {
            callback.onSupportResult(RecognitionSupport.Builder().build())
        } catch (e: Exception) {
            Log.w(TAG, "onCheckRecognitionSupport callback failed", e)
        }
    }

    override fun onCancel(listener: Callback) = Unit

    override fun onStopListening(listener: Callback) = Unit
}
