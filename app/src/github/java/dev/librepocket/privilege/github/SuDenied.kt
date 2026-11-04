package dev.librepocket.privilege.github

import java.util.Locale

/**
 * su 拒絕判定（B2，Issue #136 B2）。
 *
 * - exit==0 永不視為拒絕（成功）；
 * - 非零且 stderr 命中拒絕關鍵字（大小寫無關）即拒絕（使用者在 Magisk/
 *   KernelSU 彈窗按拒，或 `su` 本體不可用），呼叫方回 typed
 *   `Denied(NOT_WHITELISTED)`；
 * - 非零但無拒絕訊號即一般失敗，呼叫方回 `Failed`（皆不回 Ok）。
 * - 關鍵字刻意收斂為常見英文訊號；未知語系的拒絕仍以 Failed 收斂
 *  （同樣非 Ok，滿足「exit≠0 不回 Ok」底線，未知語系 fail-closed 為 Failed）。
 * - 刻意不收 bare `"su: "` 前綴：任何 su 錯誤行都以 `"su: "` 開頭
 *  （例如 `"su: unknown option"`、`"su: cannot ..."`），bare 前綴會把
 *   一般失敗誤判為拒絕。`su` 側的拒絕本來就帶明確關鍵字
 *  （例如 `"su: permission denied"` 已含 `"permission denied"`），只認
 *   明確拒絕關鍵字即可；見 [isSuDenied] 單測的 bare 前綴負向控制。
 * - 大小寫折疊一律用 [Locale.ROOT]（土耳其語系 `I`/`i` 問題已收斂，
 *   不隨裝置預設 locale 漂移）。
 *
 * BLOCKER 揭露（啟發式限制，判定邏輯刻意不改，保持 fail-closed）：
 * `su -c` 把 su 層與內層命令的多路輸出共用同一個 exit code／stderr，
 * [isSuDenied] 只能對合併後的 stderr 做關鍵字啟發式匹配，無法區分
 * 「su 層拒絕提權」與「內層命令自身 EACCES/EPERM」（例如內層讀不可讀檔）。
 * 因此內層命令的權限失敗可能被誤判為 su Denied；呼叫方應把此處的 Denied
 * 理解為「疑似 su 拒絕」。未來收斂方向：只認 su 本體前綴行（`^su:` 行內
 * 同時命中拒絕關鍵字），內層輸出即使含關鍵字也不算拒絕；在此收斂完成前
 * 不放寬、不收窄現有關鍵字表。
 */
private val SU_DENIED_SIGNALS: List<String> = listOf(
    // 冗餘是刻意（不刪）：短詞是長詞的子字串（如 "denied" ⊂ "permission denied"、
    // "rejected" ⊂ "request rejected"、"eacces" ⊂附 errno 名的訊息），保留是為
    // 了讓截斷／變體訊息仍命中；去重只會省常數比對，不改變語義，故保持 12 個不動。
    "permission denied",
    "permissiondeny",
    "not allowed",
    "denied",
    "unauthorized",
    "operation not permitted",
    "must be rooted",
    "no root",
    "request rejected",
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
    return SU_DENIED_SIGNALS.any { it in text }
}
