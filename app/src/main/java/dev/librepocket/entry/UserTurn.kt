package dev.librepocket.entry

/**
 * 會話歸屬：新建或續接既有會話。
 *
 * 由各入口攜帶的 `sessionId` 推導：空白/缺席即新建，
 * 非空白即續接。續接只記錄目標 ID，不在此驗證存在性
 *（存在性由會話存儲層判定）。
 */
sealed interface SessionTarget {
  data object New : SessionTarget

  data class Resume(val sessionId: String) : SessionTarget
}

/**
 * 歸一化後的使用者輪次。
 *
 * ARCH §3.1：所有入口只做「建/恢復會話 ID + 正規化為 UserTurn +
 * 訂閱事件渲染」。[text] 已去除首尾空白並截斷；[source] 僅供
 * 審計/除錯，不影響語義（同文案不同入口的 [text] 必須相等）。
 */
data class UserTurn(
  val text: String,
  val sessionTarget: SessionTarget,
  val source: EntryKind,
)
