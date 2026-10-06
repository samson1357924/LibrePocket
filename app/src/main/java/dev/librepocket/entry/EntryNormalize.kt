package dev.librepocket.entry

/**
 * 各入口原始輸入（Android 薄層在呼叫 normalize 前裝配）。
 *
 * - [rawText]：Launcher 啟動附言 / Shortcut 查詢 / Tile 預設口令
 *   / Share 正文 / 通知 RemoteInput 回覆 / Assist 語音轉文字。
 * - [shareTitle]：僅 SHARE 使用（`EXTRA_SUBJECT`）；其他入口忽略。
 * - [sessionId]：缺席或空白即新建會話，否則續接該會話。
 * - [isForeground]/[hasUserGesture]：只供 [EntryGate] 判定，
 *   不參與正規化文字。
 */
data class EntryInput(
  val kind: EntryKind,
  val rawText: String? = null,
  val shareTitle: String? = null,
  val sessionId: String? = null,
  val isForeground: Boolean = true,
  val hasUserGesture: Boolean = true,
)

/**
 * 入口正規化：6 入口 → 同一 [UserTurn]。
 *
 * 規則（原創最小語義）：
 * 1. SHARE 若標題非空白則為 `"標題\n正文"`，否則只取正文；
 *    其餘入口只取 [EntryInput.rawText]。
 * 2. 取出後 trim；空白即拒絕（`IllegalArgumentException`，
 *    訊息攜帶入口名供除錯，不含正文以外的敏感資訊）。
 * 3. 超過 [MAX_TEXT_LENGTH] 截斷（保留前 N 字，不拋錯）。
 * 4. `sessionId` trim 後非空白即 [SessionTarget.Resume]，否則 [SessionTarget.New]。
 * 5. 同一語義文案經不同入口歸一後 [UserTurn.text] 相等；
 *    差異只保留在 [UserTurn.source]（審計用）。
 */
object EntryNormalize {
  const val MAX_TEXT_LENGTH = 4000

  fun normalize(input: EntryInput): UserTurn {
    val combined = when (input.kind) {
      EntryKind.SHARE -> {
        val title = input.shareTitle?.trim().orEmpty()
        val body = input.rawText?.trim().orEmpty()
        if (title.isNotEmpty() && body.isNotEmpty()) "$title\n$body" else body
      }
      else -> input.rawText?.trim().orEmpty()
    }
    val text = combined.trim()
    require(text.isNotEmpty()) { "empty turn from ${input.kind}" }
    val bounded = if (text.length > MAX_TEXT_LENGTH) text.take(MAX_TEXT_LENGTH) else text
    val target = input.sessionId?.trim()?.takeIf { it.isNotEmpty() }
      ?.let { SessionTarget.Resume(it) } ?: SessionTarget.New
    return UserTurn(text = bounded, sessionTarget = target, source = input.kind)
  }
}
