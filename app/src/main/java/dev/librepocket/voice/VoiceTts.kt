package dev.librepocket.voice

import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.S1bFallback
import java.util.Locale

/**
 * S2 系統 TTS 純邏輯（主源集，純 JVM，零 Android 依賴）。
 *
 * - 分段上限 [MAX_CHUNK_CHARS] = 4000 字（`TextToSpeech.speak` 長文截斷防線）；
 * - 語言回退鏈：`zh-TW` → `zh-CN` → 列出 voices 找 `zh` 前綴。
 * - 失敗話術：[ttsFallbackMessage]（與 [VoiceStt.fallbackMessage] 對稱，
 *   原因碼原文引用）；引擎探測：[hasEngine]。
 */
object VoiceTts {

    /** 單次 `speak` 最大字元數（超過即分段，首段 QUEUE_FLUSH、後續 QUEUE_ADD）。 */
    const val MAX_CHUNK_CHARS = 4000

    /** TTS 失敗細分碼（隨 [ttsFallbackMessage] 原文引用，見 `VoiceStt.DETAIL_*` 對稱）。 */
    const val DETAIL_NO_ENGINE = "NO_ENGINE"
    const val DETAIL_INIT_FAILED = "INIT_FAILED"
    const val DETAIL_LANG_UNAVAILABLE = "LANG_UNAVAILABLE"
    const val DETAIL_SPEAK_FAILED = "SPEAK_FAILED"

    /**
     * 系統引擎揭露（S2 鎖定）：朗讀走裝置安裝的系統語音引擎，
     * 離線能力與資料處理（是否送雲）取決於該引擎；本 app 不新增權限、
     * 不自建語音通道。[SPEAK_BUTTON_DESCRIPTION] 與設定頁語音卡引用同一語義，
     * 單測釘選一致（見 `VoiceDisclosureTest`）。
     */
    const val ENGINE_DISCLOSURE = "系統語音引擎處理；資料傳輸取決於已安裝的引擎"

    /** 朗讀鈕無障礙文案：動作 + 引擎揭露（ChatScreen 引用此常數，不手寫字串）。 */
    const val SPEAK_BUTTON_DESCRIPTION = "朗讀（" + ENGINE_DISCLOSURE + "）"

    val PREFERRED_TW: Locale = Locale.forLanguageTag("zh-TW")
    val PREFERRED_CN: Locale = Locale.forLanguageTag("zh-CN")

    /**
     * 定長分段（保留全部文字，不丟字；空輸入回空表）。
     *
     * @param max 每段上限（>0；單測可傳小值驗證邊界）。
     */
    fun split(text: String, max: Int = MAX_CHUNK_CHARS): List<String> {
        require(max > 0) { "max must be positive" }
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>((text.length / max) + 1)
        var i = 0
        while (i < text.length) {
            out.add(text.substring(i, minOf(i + max, text.length)))
            i += max
        }
        return out
    }

    /**
     * 引擎可用性探測（純函數；`null`/空表皆視為無可用引擎，fail-closed）。
     *
     * [VoiceSpeaker] 以 `TextToSpeech.engines` 餵入此函數：探測到無引擎時
     * 視為初始化失敗（阻擋後續朗讀並經失敗通道揭露），未知（`null`，如引擎
     * 查詢本身拋錯）則維持原初始化流程，不改變既有 lifecycle 語義。
     */
    fun hasEngine(engines: Collection<*>?): Boolean = !engines.isNullOrEmpty()

    /** 降級話術（CAPABILITY_MATRIX §5：原因碼原文引用；與 `VoiceStt` 對稱）。 */
    fun ttsFallbackMessage(reason: DenyReason, detail: String): String =
        S1bFallback.message(
            what = "語音輸出（$detail）",
            reason = reason,
            detail = detail,
            alternative = VoiceTools.FALLBACK_TTS_HINT,
            needFromUser = "直接看螢幕上的文字",
        )

    /**
     * 語言回退鏈（純函數，TTS 查詢結果以 lambda/集合注入以便單測）。
     *
     * @param isSupported `setLanguage(locale)` 是否可用（LANG_AVAILABLE 側）。
     * @param voiceLangs `voices` 各自的語言標籤（如 `zh-TW` / `cmn-cn`），大小寫寬容。
     * @return 選定語言（`zh-TW`/`zh-CN`/首個 `zh` 系 voice 語言），皆無回 null。
     */
    fun resolveLocale(
        isSupported: (Locale) -> Boolean,
        voiceLangs: Collection<String>,
    ): Locale? {
        if (isSupported(PREFERRED_TW)) return PREFERRED_TW
        if (isSupported(PREFERRED_CN)) return PREFERRED_CN
        val hit = voiceLangs.firstOrNull {
            it.lowercase().replace('_', '-').startsWith("zh")
        } ?: return null
        return try {
            Locale.forLanguageTag(hit.replace('_', '-'))
        } catch (_: Exception) {
            PREFERRED_CN
        }
    }
}
