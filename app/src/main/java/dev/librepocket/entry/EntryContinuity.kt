package dev.librepocket.entry

/** 輪次存活狀態（入口層可見子集；完整狀態機見 ARCH §7.1）。 */
enum class EntryTurnState {
  IDLE,
  STREAMING,
  INTERRUPTED,
}

/**
 * 生命週期連續性（P6 驗收：旋轉/切後台不斷線；回收後手動恢復）。
 *
 * - [onRotate]/[onBackgrounded]：一律 [ContinuityEffect.KeepAlive]，
 *   呼叫方保持事件訂閱即可，禁止取消 in-flight turn。
 * - [onProcessRestart]：行程被系統回收後重建；若死亡時有未完成
 *   輪次則回傳 [EntryTurnState.INTERRUPTED]，必須經使用者明確
 *   恢復（[needsManualResume] 為 true），絕不自動重放副作用。
 */
sealed interface ContinuityEffect {
  data object KeepAlive : ContinuityEffect
}

object EntryContinuity {
  fun onRotate(): ContinuityEffect = ContinuityEffect.KeepAlive

  fun onBackgrounded(): ContinuityEffect = ContinuityEffect.KeepAlive

  fun onProcessRestart(hadRunningTurn: Boolean): EntryTurnState =
    if (hadRunningTurn) EntryTurnState.INTERRUPTED else EntryTurnState.IDLE

  fun needsManualResume(state: EntryTurnState): Boolean =
    state == EntryTurnState.INTERRUPTED
}
