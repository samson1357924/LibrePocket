package dev.librepocket.privilege.github

/**
 * su 拒絕判定（B2，Issue #136 B2）。
 *
 * - exit==0 永不視為拒絕（成功）；
 * - 非零且 stderr 命中拒絕關鍵字（大小寫無關）即拒絕（使用者在 Magisk/
 *   KernelSU 彈窗按拒，或 `su` 本體不可用），呼叫方回 typed
 *   `Denied(NOT_WHITELISTED)`；
 * - 非零但無拒絕訊號即一般失敗，呼叫方回 `Failed`（皆不回 Ok）。
 * - 關鍵字刻意收斂為常見英文訊號；未知語系的拒絕仍以 Failed 收斂
 *  （同樣非 Ok，滿足「exit≠0 不回 Ok」底線）。
 * - 刻意不收 bare `"su: "` 前綴：任何 su 錯誤行都以 `"su: "` 開頭
 *  （例如 `"su: unknown option"`、`"su: cannot ..."`），bare 前綴會把
 *   一般失敗誤判為拒絕。`su` 側的拒絕本來就帶明確關鍵字
 *  （例如 `"su: permission denied"` 已含 `"permission denied"`），只認
 *   明確拒絕關鍵字即可；見 [isSuDenied] 單測的 bare 前綴負向控制。
 */
fun isSuDenied(stderr: ByteArray, exitCode: Int): Boolean {
    if (exitCode == 0) return false
    val text = try {
        String(stderr, Charsets.UTF_8).lowercase()
    } catch (_: Exception) {
        return false
    }
    if (text.isBlank()) return false
    val signals = listOf(
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
    return signals.any { it in text }
}
