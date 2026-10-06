package dev.librepocket.entry

/**
 * 後台啟動閘門（PLAY 合規：無背景啟動濫用）。
 *
 * - 前台入口一律放行（使用者當下正在操作）。
 * - 後台入口（通知/Tile/Share/Assist 被系統喚起時）必須攜帶
 *   使用者手勢（點通知、點 Tile、按助手鍵等）；無手勢即
 *   [RequiresUserGesture]，呼叫方不得自動拉起前台或發起輪次，
 *   只能等使用者點擊後再以同一 [EntryInput] 重入。
 */
sealed interface EntryGateResult {
  data object Allowed : EntryGateResult

  data object RequiresUserGesture : EntryGateResult
}

object EntryGate {
  fun check(isForeground: Boolean, hasUserGesture: Boolean): EntryGateResult =
    if (isForeground || hasUserGesture) EntryGateResult.Allowed
    else EntryGateResult.RequiresUserGesture

  fun check(input: EntryInput): EntryGateResult =
    check(isForeground = input.isForeground, hasUserGesture = input.hasUserGesture)
}
