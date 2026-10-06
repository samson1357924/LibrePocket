package dev.librepocket.tool

/**
 * S1-B 降級話術共享模板（CAPABILITY_MATRIX §5）。
 *
 * 模板：`做不到 X（原因碼）→ 可替代 Y → 需要你做 Z`。
 * 原因碼一律取自 [DenyReason] 原文；[detail] 為機器可讀的細分碼
 * （例如 `CLEARTEXT_NON_LOCAL`），附於原因碼之後，不替代原因碼。
 */
object S1bFallback {

    fun message(
        what: String,
        reason: DenyReason,
        detail: String,
        alternative: String,
        needFromUser: String,
    ): String =
        "做不到$what（$reason/$detail）→ 可替代$alternative → 需要你做$needFromUser"
}
