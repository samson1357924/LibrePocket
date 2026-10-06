package dev.librepocket.entry

/**
 * 跨入口派發：沿用 P1 M3 steering 語義（見 `dev.librepocket.chat`）。
 *
 * - 閒置 → [SendNow]：直接作為新一輪 user 訊息發送。
 * - 忙碌（已有 in-flight turn）→ [QueueAsSteer]：永不搶占/取消
 *   當前輪次，排隊 FIFO，當前輪完整結束後一次消費一條。
 * - 語義與入口種類無關：Tile 來文與通知回覆來文排隊規則一致。
 */
sealed interface EntryRoute {
  data object SendNow : EntryRoute

  data object QueueAsSteer : EntryRoute
}

object EntryDispatch {
  fun route(isBusy: Boolean): EntryRoute =
    if (isBusy) EntryRoute.QueueAsSteer else EntryRoute.SendNow
}

/**
 * 入口 steering 佇列（`TurnController` 排隊語義的入口側鏡像）。
 *
 * 只記錄已歸一的 [UserTurn]，保證跨入口順序 = 到達順序。
 * 本類非執行緒安全之外的同步由呼叫方（Runtime 單一派發線程）
 * 保證；單元測試以單線程斷言順序即可。
 */
class EntrySteerQueue {
  private val queue: ArrayDeque<UserTurn> = ArrayDeque()

  /** 按忙碌狀態決定直發或排隊；排隊時回傳 [EntryRoute.QueueAsSteer]。 */
  fun submit(turn: UserTurn, isBusy: Boolean): EntryRoute {
    if (!isBusy) return EntryRoute.SendNow
    queue.addLast(turn)
    return EntryRoute.QueueAsSteer
  }

  /** 取出隊首（FIFO）；為空回傳 null。 */
  fun pollNext(): UserTurn? = queue.removeFirstOrNull()

  fun pendingCount(): Int = queue.size

  fun clear() {
    queue.clear()
  }
}
