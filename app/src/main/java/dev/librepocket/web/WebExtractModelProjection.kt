package dev.librepocket.web

/**
 * D3 模型可見投影的 **internal seam**（#138 hard gate #8／#9；尚未接線、無 production caller）。
 *
 * [WebExtractOutcome.Ok.envelope] 的 fence **只**涵蓋 capped snippet；`content.title`／
 * `url`／`siteName`／`published` 是 marker-free、Cf／Cc 已清、硬上限截斷的 untrusted
 * structured values，**不在 fence 之內**。未來的 D3 wiring 不得把這些欄位當作 in-fence
 * 內容呈現：必須使用本函式 re-fence **整個序列化 payload**，或完全丟棄上述欄位。
 *
 * 本函式刻意不輸出 `Ok.envelope`（其內容等同已清理的 `content.snippet`，再嵌一層
 * marker 會被外層 [WebContentEnvelope.wrap] 的防偽造清理剝掉）；外層 envelope 是唯一
 * 的模型可見籬笆。任何攻擊者字串（含 title／published／url 的 host 與 query/path、
 * siteName）只可能出現在 fence 之內——field-agnostic 斷言見 `WebPinnedHttpAdapterTest`。
 */
internal object WebExtractModelProjection {

    /** 序列化並 re-fence 整個 tool-result payload；輸出恰含一組 BEGIN／END。 */
    fun serialize(ok: WebExtractOutcome.Ok): String {
        val c = ok.content
        val payload = buildString {
            append('{')
            append("\"title\":\"").append(escape(c.title)).append('"')
            append(",\"url\":\"").append(escape(c.url)).append('"')
            append(",\"snippet\":\"").append(escape(c.snippet)).append('"')
            append(",\"published\":").append(quotedOrNull(c.published))
            append(",\"siteName\":").append(quotedOrNull(c.siteName))
            append(",\"truncated\":").append(ok.truncated)
            append('}')
        }
        return WebContentEnvelope.wrap(payload)
    }

    private fun quotedOrNull(value: String?): String =
        if (value == null) "null" else "\"${escape(value)}\""

    /** JSON 字串轉義：控制字元轉為反斜線 u 加四位十六進位，避免 serialized payload 攜帶 Cc。 */
    private fun escape(value: String): String = buildString(value.length + 8) {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch < ' ') {
                        append("\\u")
                        append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
        }
    }
}
