package dev.librepocket.web

import java.text.Normalizer

/**
 * D4 外部不可信內容 envelope（#138 D4；純 JVM，不接線）。
 *
 * - 模型可見的外部抓取內容一律包一層 [BEGIN]／[END]，呼叫方不得直接把原始
 *   HTML／文字送模型。
 * - 防偽造：[wrap] 前先以 [stripForgedMarkers] 剝除內容內已有的 marker
 *   字串；剝除為大小寫不敏感（sentinel marker 為 ASCII，任何大小寫變體不得
 *   存活進模型可見體），且 [cleanField] 之每輪先以 NFKC 摺疊（fullwidth／
 *   small-form 相似字摺為 ASCII）、再剝 marker（大小寫不敏感）再清理，令相似字亦無法躲過。
 *   再包一層，保證輸出恰含一組 marker。
 * - 清理：[sanitize] 移除所有 `Character.FORMAT`（Cf）碼點，並移除除
 *   `\n`（U+000A）與 `\t`（U+0009）外之所有 `Character.CONTROL`（Cc）碼點
 *   （ESC／BEL／DEL／C1 如 U+0085 NEL 等一律剔除），涵蓋 bidi
 *   控制（U+061C／U+200E／U+200F／U+202A–U+202E／U+2066–U+2069）與零寬／
 *   Cf（U+200B–U+200D／U+2060／U+FEFF／U+00AD／U+180E／U+2061–U+2064／
 *   U+206A–U+206F／U+FFF9–U+FFFB／tags U+E0000–U+E007F 等）；此外亦精確
 *   剔除下列非 Cf 但有混淆／隱匿風險之碼點（見 [EXTRA_STRIP]）：
 *   U+034F（CGJ）、U+115F／U+1160（Hangul fillers）、U+3164（Hangul filler）、
 *   U+FFA0（halfwidth Hangul filler）、U+2028／U+2029（line／paragraph separator）。
 * - 按補充平面碼點迭代（[Character.charCount]），不切斷 surrogate pair。
 * - [cleanField] 為 `nfkc → strip → sanitize` 迭代至精確定點（無輪數上限）：
 *   nfkc 先摺 fullwidth／small-form marker 相似字為 ASCII，剝除 marker
 *   （大小寫不敏感）再清理（去 Cf／Cc／EXTRA_STRIP），反覆至不動點。
 *   終止性：NFKC 僅首輪可擴張（展開為單字元有限映射；首輪後無待展開
 *   字元，其後之 nfkc 至多合併／重排，長度不增、碼點數不增）；自第二輪
 *   起每一輪或達不動點、或碼點數嚴格下降，刪除輪次數以首輪正規化後
 *   長度為上界，故必於有限輪內收斂（最壞 O(n²) 輪數上界）。
 * - 所有迴圈受呼叫方傳入的 `deadlineNs` **與** `checking` 探針雙約束
 *   （預設 `Long.MAX_VALUE`／`{}`，語義與先前版本完全一致）：deadline 到期即拋
 *   [DeadlineExceededException]（WebExtract.kt 定義；與 CancellationException 區分）；
 *   `checking`（協程活性探針，通常為 `ensureActive()`）於相同檢查點呼叫，
 *   呼叫方之協程已取消時拋 [kotlinx.coroutines.CancellationException]。
 *   工作由呼叫方的解析預算（全程 deadline）＋協程活性雙閘化；迭代仍為契約上正確的定點。
 *   注意：`stripForgedMarkers` 內之 Regex 替換與 `Normalizer.normalize` 不織入檢查
 *   （regex／ICU 無逐步插入點，線性且受 input cap 約束，刻意不 weave）。
 * - [wrap] 之輸出形狀為 `BEGIN + "\n" + cleaned + "\n" + END`，不因輸入而變；
 *   語義僅輸出本體內容變化。
 */
object WebContentEnvelope {
    const val BEGIN = "<<<EXTERNAL_UNTRUSTED_CONTENT>>>"
    const val END = "<<<END_EXTERNAL_UNTRUSTED_CONTENT>>>"

    /** 大小寫不敏感之 marker 匹配式（sentinel marker 為 ASCII）。 */
    private val BEGIN_CI = Regex("(?i)" + Regex.escape(BEGIN))
    private val END_CI = Regex("(?i)" + Regex.escape(END))

    /**
     * 剝除內容內偽造的 marker（[BEGIN]／[END]；大小寫不敏感全刪）。
     *
     * 剝除為大小寫不敏感：sentinel marker 皆為 ASCII，任何大小寫變體不得
     * 存活進模型可見體；本函式通常於 [cleanField] 之 NFKC 摺疊之後執行，
     * 兼容性（compatibility）相似字（fullwidth／small-form 等）已被摺為 ASCII。
     *
     * 進入時先檢查 deadline／[checking]（雙閘，與其他工作點同一約束）；
     * 內部之 `BEGIN_CI`／`END_CI` Regex 替換不可 weave（regex 無逐步插入點），
     * 其工作量線性且受輸入長度（input cap）約束，接受此剩餘。
     */
    fun stripForgedMarkers(text: String, deadlineNs: Long = Long.MAX_VALUE, checking: () -> Unit = {}): String {
        checking()
        if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
        if (text.isEmpty()) return text
        return END_CI.replace(BEGIN_CI.replace(text, ""), "")
    }

    /**
     * 定點清理：`nfkc → stripForgedMarkers → sanitize` 迭代至精確定點（無輪數上限）。
     *
     * Cf／兼容性混淆第二通道：攻擊者可在 marker 內插入 Cf（如 `EXTERNAL\u200B_UNTRUSTED`）
     * 或以 fullwidth／small-form 等兼容相似字構造 marker；單輪不足以收束——
     * NFKC 先摺相似字為 ASCII，剝除 marker（大小寫不敏感），再清理（去 Cf／Cc／EXTRA_STRIP），
     * 反覆至不動點即返回。終止性：NFKC 僅首輪可擴張（展開為單字元有限映射；
     * 首輪後無待展開字元，其後 nfkc 至多合併／重排，長度不增、碼點數不增）；
     * 自第二輪起每輪或達不動點、或碼點數嚴格下降，刪除輪次數以首輪
     * 正規化後長度為上界，故必於有限輪內收斂（最壞 O(n²) 輪數上界）。
     * 注意：`strip` 非冪等（先剝 BEGIN、後剝 END）：END 之移除可於接縫「造」出
     * 新的 BEGIN（實測例：`<<<EXTERNAL_UNTRUSTED_CONTENT<<<END_EXTERNAL_UNTRUSTED_CONTENT>>>>>>`
     * 單輪後留下 `<<<EXTERNAL_UNTRUSTED_CONTENT>>>`；實測未造出 END），故單輪不足以
     * 收束，由外層迭代至定點吸收；
     * NFKC 摺疊僅施用於此處；parseHtml／entity unescape 不做 NFKC。
     *
     * 工作受 `deadlineNs` **與** `checking` 雙約束（預設 `Long.MAX_VALUE`／`{}`，
     * 語義與先前版本一致）：外層每輪先呼叫 [checking] 再檢查 [deadlineNs]、
     * 以及 [sanitize] 內每 512 個 codepoint 雙閘；逾時拋
     * [DeadlineExceededException]；呼叫方協程已取消時由 [checking] 拋
     * [kotlinx.coroutines.CancellationException]——兩者皆原樣上拋
     * （CancellationException 不映射、不吞沒）。逾時／取消即中止，不回傳部分清理結果。
     * 注意：`Normalizer.normalize`（ICU）本身不可 weave，其工作量受輸入長度約束，接受此剩餘。
     */
    fun cleanField(text: String, deadlineNs: Long = Long.MAX_VALUE, checking: () -> Unit = {}): String {
        var s = text
        while (true) {
            checking()
            if (System.nanoTime() >= deadlineNs) throw DeadlineExceededException()
            val next = sanitize(
                stripForgedMarkers(Normalizer.normalize(s, Normalizer.Form.NFKC), deadlineNs, checking),
                deadlineNs,
                checking,
            )
            if (next == s) return next
            s = next
        }
    }

    /**
     * 移除 Cf（FORMAT）碼點、除 `\n`（U+000A）與 `\t`（U+0009）外之所有
     * Cc（CONTROL）碼點，以及 [EXTRA_STRIP] 中之精確碼點；永不拋異常。
     *
     * 以碼點迭代，補充平面 tags 等亦按整點判定；孤立 surrogate（
     * `getType` 回 SURROGATE）保留，交由上層 replacement 解碼處理。
     * 注意：`\n`／`\t`（Cc）保留；其餘 Cc（ESC／BEL／DEL／C1 含 U+0085 NEL
     * 等）一律移除，不進模型可見體；U+2028／U+2029 非 Cc 且明列於
     * [EXTRA_STRIP]，亦會被移除（換行語義以 `\n` 表示）。
     *
     * 每 512 個 codepoint 同點雙閘：先呼叫 [checking]（呼叫方協程已取消 →
     * [kotlinx.coroutines.CancellationException] 原樣上拋），再檢查 [deadlineNs]
     * （逾時 → [DeadlineExceededException]）。
     */
    fun sanitize(text: String, deadlineNs: Long = Long.MAX_VALUE, checking: () -> Unit = {}): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var i = 0
        var units = 0
        while (i < text.length) {
            // 每 512 個 codepoint 雙閘一次（避免逐 char 的時鐘讀取開銷）。
            if (++units and 511 == 0) {
                checking()
                if (System.nanoTime() >= deadlineNs) {
                    throw DeadlineExceededException()
                }
            }
            val cp = text.codePointAt(i)
            val type = Character.getType(cp)
            val isControl = type == Character.CONTROL.toInt()
            if (type != Character.FORMAT.toInt() &&
                !(isControl && cp != 0x0A && cp != 0x09) &&
                cp !in EXTRA_STRIP
            ) {
                sb.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /**
     * 非 Cf、但有混淆／隱匿風險而明確剝除之碼點：
     * U+034F（CGJ，combining grapheme joiner）、
     * U+115F／U+1160／U+3164／U+FFA0（Hangul fillers）、
     * U+2028／U+2029（line／paragraph separators）。
     */
    private val EXTRA_STRIP = setOf(0x034F, 0x115F, 0x1160, 0x3164, 0xFFA0, 0x2028, 0x2029)

    /**
     * 先以 [cleanField] 定點處理（NFKC＋sanitize＋剝偽造 marker）、
     * 最後包一層。工作受 `deadlineNs` **與** `checking` 雙約束
     * （預設 `Long.MAX_VALUE`／`{}`，語義與先前版本一致）：
     * 逾時拋 [DeadlineExceededException]；呼叫方協程已取消時由 `checking` 拋
     * [kotlinx.coroutines.CancellationException]——兩者皆不回傳部分結果。
     *
     * 輸出形狀：`BEGIN + "\n" + cleaned + "\n" + END`，恰含一組 marker；
     * 輸入內容無論為何（含偽造 marker／bidi）皆 fail-closed 為單層乾淨 envelope。
     */
    fun wrap(raw: String, deadlineNs: Long = Long.MAX_VALUE, checking: () -> Unit = {}): String {
        val cleaned = cleanField(raw, deadlineNs, checking)
        return "$BEGIN\n$cleaned\n$END"
    }

}
