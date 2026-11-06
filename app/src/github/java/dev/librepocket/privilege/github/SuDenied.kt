package dev.librepocket.privilege.github

import java.util.Locale

/**
 * su 拒絕判定（B2，Issue #136 B2；P2 精確化，僅認 `^su:` 前綴行）。
 *
 * - exit==0 永不視為拒絕（成功）；
 * - 非零且 stderr 存在 `^su:` 前綴行（行首可有空白，大小寫無關）同時命中
 *   拒絕子串（`denied`／`rejected`／`eacces`／`eperm`，大小寫無關）即拒絕
 *   （使用者在 Magisk/KernelSU 彈窗按拒，或 `su` 本體不可用），呼叫方回
 *   `Failed`（B4 起不再斷言 Denied，見 [RootSuRunner]）；
 * - 非零但無此類行即一般失敗，呼叫方回 `Failed`（皆不回 Ok）。
 *   內層命令自身 EACCES/EPERM（例如 `cat` 不可讀檔，無 `su:` 前綴）一律 Failed，
 *   不誤判為 Denied（見負向單測）。
 * - 關鍵字刻意收斂為常見英文訊號；未知語系的拒絕仍以 Failed 收斂
 *  （同樣非 Ok，滿足「exit≠0 不回 Ok」底線，未知語系 fail-closed 為 Failed）。
 * - 刻意不收 bare `"su: "` 前綴：任何 su 錯誤行都以 `"su: "` 開頭
 *  （例如 `"su: unknown option"`、`"su: cannot ..."`），bare 前綴會把
 *   一般失敗誤判為拒絕。`su` 側的拒絕本來就帶明確關鍵字
 *  （例如 `"su: permission denied"` 已含 `"denied"`），只認
 *   前綴行＋明確拒絕子串即可；見 [isSuDenied] 單測的 bare 前綴負向控制。
 * - 大小寫折疊一律用 [Locale.ROOT]（土耳其語系 `I`/`i` 問題已收斂，
 *   不隨裝置預設 locale 漂移）。
 *
 * P2 精確化（啟發式收斂完成）：`su -c` 把 su 層與內層命令的多路輸出共用
 * 同一個 exit code／stderr，舊實現對合併後全文做關鍵字匹配，內層 EACCES
 * 即誤判為 Denied。現只認 `^su:` 前綴行內命中，內層輸出即使含關鍵字
 * 也不算拒絕。
 *
 * B4 降級（owner P2-7）：`^su:` 前綴僅為啟發式，不保證來源 —— 內層程式亦可
 * 印出同形 `su: ... denied` 行並以非零 exit 退出（見 B4 負向單測），與真正的
 * su 層拒絕在合併 stderr／共享 exit code 下不可區分。無獨立可信來源時不得
 * 斷言 Denied；呼叫方一律收斂為 Failed（不確定失敗），不得以 KDoc 宣稱
 * 前綴保證來源。
 */
private val SU_DENIED_SIGNALS: List<String> = listOf(
    // P2 精確化：僅保留 4 個拒絕子串家族（denied/rejected/eacces/eperm），
    // 須同時滿足 `^su:` 前綴行（見 [isSuDenied]）。舊 12 關鍵字中
    // "not allowed"/"unauthorized"/"must be rooted"/"no root"/
    // "operation not permitted" 等通用短語易在內層輸出誤命中，故不再單獨收斂；
    // su 側拒絕本就帶 denied/rejected/eacces/eperm（例如 "su: permission denied"
    // 含 "denied"），覆盖真实拒绝路径。
    "denied",
    "rejected",
    "eacces",
    "eperm",
)

/**
 * P2-A 可見性：僅提權通道內部＋同模組單測使用，收斂為 internal；
 * 跨模組呼叫方應經 Runner typed 結果（Denied/Failed），不得直接做字串啟發式判定.
 */
internal fun isSuDenied(stderr: ByteArray, exitCode: Int): Boolean {
    if (exitCode == 0) return false
    // String(bytes, UTF_8) 永不抛（用 Replacement 字元收斂非法序列），不包 try/catch。
    val text = String(stderr, Charsets.UTF_8).lowercase(Locale.ROOT)
    if (text.isBlank()) return false
    // P2 精確化：只認 `^su:` 前綴行內命中（行首可有空白，大小寫已折疊）；
    // 內層輸出即使含 denied/rejected/eacces/eperm 也不算拒絕。
    return text.lineSequence().any { line ->
        val trimmed = line.trimStart()
        trimmed.startsWith("su:") && SU_DENIED_SIGNALS.any { it in trimmed }
    }
}
