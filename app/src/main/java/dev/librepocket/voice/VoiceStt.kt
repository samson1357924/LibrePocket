package dev.librepocket.voice

import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.S1bFallback

/**
 * S2 系統 STT 參數與結果解析（主源集，純 JVM，零 Android 依賴）。
 *
 * 字串常數刻意與 `android.speech.RecognizerIntent` 的字面值一致，
 * 以便純 JVM 單測斷言；真正的 `Intent` 由 ChatScreen 以
 * `RecognizerIntent` 常數建構（同一字面值，系統行為一致）。
 *
 * - action = `ACTION_RECOGNIZE_SPEECH`
 * - `EXTRA_LANGUAGE_MODEL` = `LANGUAGE_MODEL_FREE_FORM`
 * - `EXTRA_LANGUAGE` = `zh-TW`
 * - `EXTRA_MAX_RESULTS` = 1
 *
 * 無辨識服務時降級手動輸入：
 * - 開關關閉 → `USER_DISABLED`
 * - 無系統辨識服務 → `NO_PRIVILEGE`
 */
object VoiceStt {

    const val ACTION = "android.speech.action.RECOGNIZE_SPEECH"
    const val EXTRA_LANGUAGE_MODEL = "android.speech.extra.LANGUAGE_MODEL"
    const val LANGUAGE_MODEL_FREE_FORM = "free_form"
    const val EXTRA_LANGUAGE = "android.speech.extra.LANGUAGE"
    const val LANGUAGE = "zh-TW"
    const val EXTRA_MAX_RESULTS = "android.speech.extra.MAX_RESULTS"
    const val MAX_RESULTS = 1
    const val EXTRA_RESULTS = "android.speech.extra.RESULTS"

    const val DETAIL_NO_SERVICE = "NO_RECOGNITION_SERVICE"
    const val DETAIL_SWITCH_OFF = "SWITCH_OFF"
    const val DETAIL_EMPTY_RESULT = "EMPTY_RESULT"

    /** 降級判定：null 表示可走系統辨識，非 null 為對應原因碼。 */
    fun degradeReason(hasRecognitionService: Boolean, inputEnabled: Boolean): DenyReason? {
        if (!inputEnabled) return DenyReason.USER_DISABLED
        if (!hasRecognitionService) return DenyReason.NO_PRIVILEGE
        return null
    }

    /** 取辨識候選首條非空正文；無結果回 null（呼叫方留手動輸入）。 */
    fun pickResult(results: List<String>?): String? =
        results?.firstOrNull { it.isNotBlank() }

    /** 降級話術（CAPABILITY_MATRIX §5：原因碼原文引用）。 */
    fun fallbackMessage(reason: DenyReason, detail: String): String =
        S1bFallback.message(
            what = "語音輸入（$detail）",
            reason = reason,
            detail = detail,
            alternative = VoiceTools.FALLBACK_STT_HINT,
            needFromUser = "在輸入框手動輸入",
        )
}
