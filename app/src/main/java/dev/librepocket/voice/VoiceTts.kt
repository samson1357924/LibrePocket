package dev.librepocket.voice

import java.util.Locale

/**
 * S2 系統 TTS 純邏輯（主源集，純 JVM，零 Android 依賴）。
 *
 * - 分段上限 [MAX_CHUNK_CHARS] = 4000 字（`TextToSpeech.speak` 長文截斷防線）；
 * - 語言回退鏈：`zh-TW` → `zh-CN` → 列出 voices 找 `zh` 前綴。
 */
object VoiceTts {

    /** 單次 `speak` 最大字元數（超過即分段，首段 QUEUE_FLUSH、後續 QUEUE_ADD）。 */
    const val MAX_CHUNK_CHARS = 4000

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
