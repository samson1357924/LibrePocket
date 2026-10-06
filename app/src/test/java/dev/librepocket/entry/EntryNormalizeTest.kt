package dev.librepocket.entry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * D06 最小版驗收（BACKLOG D06 / ROADMAP P6）：
 * 各入口 → 同一 UserTurn；走查表（新建/續接 × 前台/後台；
 * 旋轉/切後台不斷線；回收後 INTERRUPTED 手動恢復）；
 * steering 排隊沿用 P1 M3 語義且跨入口一致。
 */
class EntryNormalizeTest {

  private val allKinds = EntryKind.entries.toList()

  private fun inputFor(kind: EntryKind, text: String = "幫我查一下明天的行程"): EntryInput =
    when (kind) {
      EntryKind.SHARE -> EntryInput(kind = kind, rawText = text)
      else -> EntryInput(kind = kind, rawText = text)
    }

  // ---- 各入口歸一 ----

  @Test fun allEntriesNormalizeToSameUserTurnText() {
    val turns = allKinds.map { EntryNormalize.normalize(inputFor(it)) }
    val first = turns.first().text
    assertEquals("幫我查一下明天的行程", first)
    for (turn in turns) {
      assertEquals(first, turn.text)
    }
    // 來源僅供審計：語義相同但來源標記不同。
    assertEquals(allKinds.toSet(), turns.map { it.source }.toSet())
  }

  @Test fun shareTitleIsPrependedWithNewline() {
    val turn = EntryNormalize.normalize(
      EntryInput(kind = EntryKind.SHARE, rawText = "正文", shareTitle = "  標題 "),
    )
    assertEquals("標題\n正文", turn.text)
  }

  @Test fun shareWithoutTitleEqualsPlainText() {
    val share = EntryNormalize.normalize(
      EntryInput(kind = EntryKind.SHARE, rawText = "同文案"),
    )
    val launcher = EntryNormalize.normalize(
      EntryInput(kind = EntryKind.LAUNCHER, rawText = "同文案"),
    )
    assertEquals(launcher.text, share.text)
  }

  @Test fun blankTextRejectedForEveryEntry() {
    for (kind in allKinds) {
      for (raw in listOf(null, "", "   ", "\n\t ")) {
        try {
          EntryNormalize.normalize(EntryInput(kind = kind, rawText = raw))
          fail("expected rejection for $kind raw=$raw")
        } catch (_: IllegalArgumentException) {
          // expected
        }
      }
    }
  }

  @Test fun shareBlankBodyRejectedEvenWithTitle() {
    try {
      EntryNormalize.normalize(
        EntryInput(kind = EntryKind.SHARE, rawText = "  ", shareTitle = "只有標題"),
      )
      fail("expected rejection: SHARE 正文為空時只憑標題不應成輪")
    } catch (_: IllegalArgumentException) {
      // expected：分享必須有正文，避免標題雜訊單獨成輪。
    }
  }

  @Test fun longTextIsTruncatedNotRejected() {
    val long = "a".repeat(EntryNormalize.MAX_TEXT_LENGTH + 500)
    val turn = EntryNormalize.normalize(EntryInput(kind = EntryKind.ASSIST, rawText = long))
    assertEquals(EntryNormalize.MAX_TEXT_LENGTH, turn.text.length)
    assertEquals(long.take(EntryNormalize.MAX_TEXT_LENGTH), turn.text)
  }

  @Test fun whitespaceIsTrimmed() {
    val turn = EntryNormalize.normalize(
      EntryInput(kind = EntryKind.TILE, rawText = "  續接上文  \n"),
    )
    assertEquals("續接上文", turn.text)
  }

  // ---- 會話歸屬：新建 / 續接 ----

  @Test fun missingSessionIdMeansNew() {
    for (kind in allKinds) {
      val turn = EntryNormalize.normalize(inputFor(kind).copy(sessionId = null))
      assertEquals(SessionTarget.New, turn.sessionTarget)
    }
  }

  @Test fun blankSessionIdMeansNew() {
    val turn = EntryNormalize.normalize(
      EntryInput(kind = EntryKind.LAUNCHER, rawText = "hi", sessionId = "  "),
    )
    assertEquals(SessionTarget.New, turn.sessionTarget)
  }

  @Test fun nonBlankSessionIdMeansResume() {
    val turn = EntryNormalize.normalize(
      EntryInput(kind = EntryKind.NOTIFICATION_REPLY, rawText = "好", sessionId = " s-123 "),
    )
    assertEquals(SessionTarget.Resume("s-123"), turn.sessionTarget)
  }

  // ---- 走查表：新建/續接 × 前台/後台 ----

  @Test fun walkthroughMatrix() {
    data class Case(
      val sessionId: String?,
      val isForeground: Boolean,
      val hasUserGesture: Boolean,
      val expectGate: EntryGateResult,
    )
    val cases = listOf(
      // 前台：無手勢也放行（使用者正在操作）。
      Case(null, true, false, EntryGateResult.Allowed),
      Case("s-1", true, false, EntryGateResult.Allowed),
      // 前台 + 手勢：放行。
      Case(null, true, true, EntryGateResult.Allowed),
      Case("s-1", true, true, EntryGateResult.Allowed),
      // 後台 + 手勢（點通知/點 Tile/助手鍵）：放行。
      Case(null, false, true, EntryGateResult.Allowed),
      Case("s-1", false, true, EntryGateResult.Allowed),
      // 後台無手勢：阻擋，不得自動拉起/發輪。
      Case(null, false, false, EntryGateResult.RequiresUserGesture),
      Case("s-1", false, false, EntryGateResult.RequiresUserGesture),
    )
    for (kind in allKinds) {
      for (c in cases) {
        val input = EntryInput(
          kind = kind,
          rawText = "走查",
          sessionId = c.sessionId,
          isForeground = c.isForeground,
          hasUserGesture = c.hasUserGesture,
        )
        // 正規化與閘門正交：正規化永遠成功，閘門決定能否發起。
        val turn = EntryNormalize.normalize(input)
        if (c.sessionId == null) assertEquals(SessionTarget.New, turn.sessionTarget)
        else assertEquals(SessionTarget.Resume(c.sessionId), turn.sessionTarget)
        assertEquals(
          "kind=$kind fg=${c.isForeground} gesture=${c.hasUserGesture}",
          c.expectGate,
          EntryGate.check(input),
        )
      }
    }
  }

  @Test fun backgroundWithoutGestureMustNotAutoLaunch() {
    // 回歸 PLAY 紅線：後台無手勢時呼叫方必須等待使用者點擊，
    // 以同一輸入重入，而非直接發送。
    val input = EntryInput(
      kind = EntryKind.NOTIFICATION_REPLY,
      rawText = "回覆內容",
      sessionId = "s-9",
      isForeground = false,
      hasUserGesture = false,
    )
    assertEquals(EntryGateResult.RequiresUserGesture, EntryGate.check(input))
    // 正規化本身不阻擋（內容合法），阻擋發生在發起層。
    assertEquals("回覆內容", EntryNormalize.normalize(input).text)
  }

  // ---- 生命週期：旋轉 / 切後台不斷線；回收後手動恢復 ----

  @Test fun rotateAndBackgroundKeepAlive() {
    assertEquals(ContinuityEffect.KeepAlive, EntryContinuity.onRotate())
    assertEquals(ContinuityEffect.KeepAlive, EntryContinuity.onBackgrounded())
  }

  @Test fun processRestartMarksRunningAsInterrupted() {
    assertEquals(EntryTurnState.INTERRUPTED, EntryContinuity.onProcessRestart(true))
    assertTrue(EntryContinuity.needsManualResume(EntryTurnState.INTERRUPTED))
  }

  @Test fun processRestartWithoutRunningStaysIdle() {
    assertEquals(EntryTurnState.IDLE, EntryContinuity.onProcessRestart(false))
    assertTrue(!EntryContinuity.needsManualResume(EntryTurnState.IDLE))
    assertTrue(!EntryContinuity.needsManualResume(EntryTurnState.STREAMING))
  }

  @Test fun interruptedNeverAutoReplays() {
    // 不自動重放：重啟後狀態既不是 STREAMING（未自動續跑），
    // 也必須經手動恢復才能回到運行。
    val state = EntryContinuity.onProcessRestart(hadRunningTurn = true)
    assertTrue(state != EntryTurnState.STREAMING)
    assertTrue(EntryContinuity.needsManualResume(state))
  }

  // ---- steering：P1 M3 語義跨入口一致 ----

  @Test fun idleRoutesToSendNow() {
    assertEquals(EntryRoute.SendNow, EntryDispatch.route(isBusy = false))
  }

  @Test fun busyRoutesToQueueAsSteer() {
    assertEquals(EntryRoute.QueueAsSteer, EntryDispatch.route(isBusy = true))
  }

  @Test fun steerQueueIsFifoAcrossEntries() {
    val queue = EntrySteerQueue()
    val first = EntryNormalize.normalize(
      EntryInput(kind = EntryKind.TILE, rawText = "改成大杯", sessionId = "s-1"),
    )
    val second = EntryNormalize.normalize(
      EntryInput(kind = EntryKind.NOTIFICATION_REPLY, rawText = "改成去冰", sessionId = "s-1"),
    )
    val third = EntryNormalize.normalize(
      EntryInput(kind = EntryKind.ASSIST, rawText = "順便查天氣", sessionId = "s-1"),
    )
    // 閒置直發：不進佇列。
    val idleTurn = EntryNormalize.normalize(EntryInput(kind = EntryKind.LAUNCHER, rawText = "首輪"))
    assertEquals(EntryRoute.SendNow, queue.submit(idleTurn, isBusy = false))
    assertEquals(0, queue.pendingCount())
    // 忙碌：三個不同入口一律排隊，順序 = 到達順序。
    assertEquals(EntryRoute.QueueAsSteer, queue.submit(first, isBusy = true))
    assertEquals(EntryRoute.QueueAsSteer, queue.submit(second, isBusy = true))
    assertEquals(EntryRoute.QueueAsSteer, queue.submit(third, isBusy = true))
    assertEquals(3, queue.pendingCount())
    assertEquals(first, queue.pollNext())
    assertEquals(second, queue.pollNext())
    assertEquals(1, queue.pendingCount())
    assertEquals(third, queue.pollNext())
    assertEquals(0, queue.pendingCount())
    assertEquals(null, queue.pollNext())
  }

  @Test fun steerQueueNeverCancelsCurrentTurn() {
    // 語義斷言：submit 只做路由判定，不提供任何取消當前輪的 API；
    // 呼叫方持有的是歸一副本，原 turn 物件不受影響。
    val queue = EntrySteerQueue()
    val turn = EntryNormalize.normalize(EntryInput(kind = EntryKind.SHORTCUT, rawText = "跟進"))
    queue.submit(turn, isBusy = true)
    assertEquals(1, queue.pendingCount())
    assertEquals("跟進", turn.text)
    queue.clear()
    assertEquals(0, queue.pendingCount())
  }
}
