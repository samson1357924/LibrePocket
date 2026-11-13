package dev.librepocket.web

/**
 * D4 外部不可信內容 envelope（#138 D4；純 JVM，不接線）。
 *
 * - 模型可見的外部抓取內容一律包一層 [BEGIN]／[END]，呼叫方不得直接把原始
 *   HTML／文字送模型。
 * - 防偽造：[wrap] 前先以 [stripForgedMarkers] 剝除內容內已有的 marker
 *   字串（大小寫敏感、精確匹配），再包一層，保證輸出恰含一組 marker。
 * - 清理：[sanitize] 移除所有 `Character.FORMAT`（Cf）碼點，涵蓋 bidi
 *   控制（U+061C／U+200E／U+200F／U+202A–U+202E／U+2066–U+2069）與零寬／
 *   Cf（U+200B–U+200D／U+2060／U+FEFF／U+00AD／U+180E／U+2061–U+2064／
 *   U+206A–U+206F／U+FFF9–U+FFFB／tags U+E0000–U+E007F 等）；
 *   Cc／換行（`\n`／`\t`）保留，不影響版式。
 * - 按補充平面碼點迭代（[Character.charCount]），不切斷 surrogate pair。
 */
object WebContentEnvelope {
    const val BEGIN = "<<<EXTERNAL_UNTRUSTED_CONTENT>>>"
    const val END = "<<<END_EXTERNAL_UNTRUSTED_CONTENT>>>"

    /** 剝除內容內偽造的 marker（[BEGIN]／[END] 精確子串全刪）。 */
    fun stripForgedMarkers(text: String): String {
        if (text.isEmpty()) return text
        var s = text
        if (BEGIN in s) s = s.replace(BEGIN, "")
        if (END in s) s = s.replace(END, "")
        return s
    }

    /**
     * 移除 Cf（FORMAT）碼點；永不拋異常。
     *
     * 以碼點迭代，補充平面 tags 等亦按整點判定；孤立 surrogate（골
     * `getType` 回 SURROGATE）保留，交由上層 replacement 解碼處理。
     */
    fun sanitize(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val type = Character.getType(cp)
            if (type != Character.FORMAT.toInt()) {
                sb.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /**
     * 先剝偽造 marker、再做 Cf 清理、最後包一層。
     *
     * 輸出形狀：`BEGIN + "\n" + cleaned + "\n" + END`，恰含一組 marker；
     * 輸入 qualquer（含偽造 marker／bidi）皆 fail-closed 為單層乾淨 envelope。
     */
    fun wrap(raw: String): String {
        val cleaned = sanitize(stripForgedMarkers(raw))
        return "$BEGIN\n$cleaned\n$END"
    }

    /** 計數輸出內 marker 出現次數（測試／稽核用）。 */
    internal fun countBegin(enveloped: String): Int =
        enveloped.split(BEGIN, ignoreCase = false).size - 1

    internal fun countEnd(enveloped: String): Int =
        enveloped.split(END, ignoreCase = false).size - 1
}
