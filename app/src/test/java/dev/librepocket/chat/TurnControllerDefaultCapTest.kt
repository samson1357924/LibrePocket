package dev.librepocket.chat

import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatMessage
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderFailure
import dev.librepocket.provider.ProviderProtocol as ProviderProto
import dev.librepocket.provider.StreamEvent
import dev.librepocket.provider.ToolCall
import dev.librepocket.provider.validateToolPairing
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class DefCapFakeProvider(
  var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
  override val protocol: ProviderProto = ProviderProto.CHAT_COMPLETIONS
  val seenRequests: MutableList<ChatRequest> = Collections.synchronizedList(mutableListOf())

  override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
    seenRequests.add(request)
    emitAll(handler(request))
  }

  override suspend fun listModels(): List<String> = emptyList()
}

private class DefCapAllowPolicy : PolicyStore {
  override fun evaluate(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
  override suspend fun setRule(rule: PolicyRule) = Unit
  override suspend fun removeRule(pattern: String) = Unit
  override suspend fun listRules(): List<PolicyRule> = emptyList()
  override suspend fun evaluateFresh(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

/**
 * B1 (#16) default-cap red tests (run against the pre-B1 Unbounded default).
 *
 * - [unboundedRequestSizeGrowsWithHistory] is the measurement proof: explicit
 *   Unbounded ships the whole history, so request chars grow linearly with
 *   history length (no bound). Passes before and after B1.
 * - [defaultCapBoundsLongDialogue] and [toolPairNotSplitByCountCap] are the
 *   red pins: they expect a bounded default and pair-atomic truncation, so
 *   they FAIL on the pre-B1 code (default Unbounded drops nothing; naive
 *   takeLast splits the tool pair) and turn green after B1.
 * - [defaultCharLegBounds150RowDialogue] is the char-leg counterpart
 *   (the 150 x 500-char fixture cited by the HistoryWindowCap KDoc):
 *   precise load seed (30 / 15,000), post-send drop (1 / 500), zero
 *   send-time trim, and char reconciliation.
 * - [boundedTakesMaxLegThenSnapsForwardOverPair],
 *   [multiCallBatchSnapsForwardWhole], [orphanNewestToolRowRetainedForDownstreamReject],
 *   [globallyUnpairedHistoryFallsBackToNaiveCut], [singlePendingBackfillSnapsPairAtomically],
 *   [degenerateSnapPastEndYieldsEmptyList],
 *   [multiTurnSequentialToolRoundsSnapCleanlyToIntermediateBoundary], and
 *   [outOfOrderBatchToolResultsResolveToCleanTurnBoundary] pin the snap semantics:
 *   dual-leg max, whole-batch snap, single-pending backfill matching ProviderJson,
 *   out-of-order result resolution, sequential round boundaries, degenerate snap-past-end,
 *   no orphan special-case in the window (retain + downstream `validateToolPairing` fail-closed),
 *   and naive-cut fallback for globally unpaired histories.
 * - [sendTimeTrimPreservesToolPairAtomicityUnderUnbounded] and
 *   [sendTimeTrimRetainsIntactPairWhenHeadPlainMessageDrops] pin the second trim layer
 *   (send-time MAX_REQUEST_CHARS) pair-atomicity under Unbounded historyCap.
 * - [orphanPrefixOutsideWindowIsDroppedWhileValidTailSucceeds] pins that an orphan
 *   prefix outside the window is dropped without error while valid tail succeeds,
 *   and fails closed with TOOL_OUTPUT_ORPHAN_ID if kept in-window.
 * - [invalidDiscardedPrefixDoesNotBreakLaterPairSnapping],
 *   [invalidDiscardedPrefixUnderBoundedCapAndSendTimeTrim], and
 *   [malformedBatchAndToolRowWithToolCallsFailClosed] pin Phase A local batch
 *   boundary snapping: invalid or malformed prefixes wholly before the cut do not
 *   disable pair-atomic snapping for later valid batches (under count, bounded char,
 *   and send-time `MAX_REQUEST_CHARS` trims), while `role == "tool"` rows carrying
 *   `toolCalls` or malformed retained batches fail closed downstream.
 * - [exactMessageCapBoundaries] and [exactCharCapBoundaries] pin exact message and
 *   char count boundaries for history windowing.
 */
class TurnControllerDefaultCapTest {

  private fun doneOnce(text: String = "ok"): (ChatRequest) -> Flow<StreamEvent> = {
    flow {
      emit(StreamEvent.TextDelta(0, 0, text))
      emit(StreamEvent.Done("stop"))
    }
  }

  private fun controller(
    provider: DefCapFakeProvider,
    history: List<ChatMessage> = emptyList(),
    cap: HistoryWindowCap? = null,
  ): TurnController {
    var n = 0
    return if (cap == null) {
      TurnController(
        provider = provider,
        policy = DefCapAllowPolicy(),
        dispatcher = Dispatchers.Default,
        sleeper = {},
        newId = { "defcap-${n++}" },
        initialHistory = history,
      )
    } else {
      TurnController(
        provider = provider,
        policy = DefCapAllowPolicy(),
        dispatcher = Dispatchers.Default,
        sleeper = {},
        newId = { "defcap-${n++}" },
        initialHistory = history,
        historyCap = cap,
      )
    }
  }

  @Test(timeout = 30_000) fun unboundedRequestSizeGrowsWithHistory() {
    // Measurement basis (synthetic, JVM-only): 20 x 500-char rows vs
    // 100 x 500-char rows, both well under MAX_REQUEST_CHARS so the
    // send-time trim never trips. Unbounded ships everything, so the
    // second request is ~5x the first — linear, unbounded growth.
    fun rows(n: Int): List<ChatMessage> = (0 until n).map { i ->
      val role = if (i % 2 == 0) "user" else "assistant"
      ChatMessage(role = role, text = "h$i:" + "v".repeat(495))
    }
    val p20 = DefCapFakeProvider(doneOnce())
    val c20 = controller(p20, rows(20), HistoryWindowCap.Unbounded)
    try {
      runBlocking { c20.send("q") }
    } finally {
      c20.close()
    }
    val p100 = DefCapFakeProvider(doneOnce())
    val c100 = controller(p100, rows(100), HistoryWindowCap.Unbounded)
    try {
      runBlocking { c100.send("q") }
    } finally {
      c100.close()
    }
    val r20 = p20.seenRequests.single()
    val r100 = p100.seenRequests.single()
    val s20 = r20.messages.sumOf { it.text.length }.toLong()
    val s100 = r100.messages.sumOf { it.text.length }.toLong()
    assertEquals(0, c20.droppedHistoryCount)
    assertEquals(0, c100.droppedHistoryCount)
    assertEquals(0, c20.lastRequestTrimmedCount)
    assertEquals(0, c100.lastRequestTrimmedCount)
    // Linear growth: 100 rows >> 20 rows (allow time-block + live delta slack).
    assertTrue("unbounded must grow with history, got $s20 vs $s100", s100 > s20 * 3)
  }

  @Test(timeout = 30_000) fun defaultCapBoundsLongDialogue() {
    // B1 count-leg pin, end-to-end: 250 tiny rows vs the 200-msg default leg
    // (the char leg never binds: ~2.5k chars total << 60k). Load windows the
    // 250-row prefix to the newest 200 (seed drop 50); the live row then
    // evicts exactly one more, so the post-send drop is precisely 1 with the
    // char counterpart equal to that single evicted row.
    val history = (0 until 250).map { i ->
      val role = if (i % 2 == 0) "user" else "assistant"
      ChatMessage(role = role, text = "m$i:" + "v".repeat(6))
    }
    // Oldest load-surviving row: the post-send eviction drops exactly it.
    val evictedLen = history[50].text.length.toLong()
    val provider = DefCapFakeProvider(doneOnce())
    val c = controller(provider, history)
    try {
      // Load-time seed: 250-row prefix -> newest 200 (drop 50, all plain).
      assertEquals(50, c.droppedHistoryCount)
      runBlocking { c.send("tail-q") }
      val req = provider.seenRequests.single()
      assertEquals(0, c.lastRequestTrimmedCount)
      assertEquals(0L, c.lastRequestTrimmedChars)
      // Combined (200 prefix + live = 201) windows to 200: evict one.
      assertEquals(1, c.droppedHistoryCount)
      assertEquals(evictedLen, c.droppedHistoryChars)
      assertEquals(req.messages.sumOf { it.text.length }.toLong(), c.lastRequestChars)
      // Newest (the live tail) always survives.
      assertTrue(req.messages.last().text.startsWith("tail-q"))
    } finally {
      c.close()
    }
  }

  @Test(timeout = 30_000) fun toolPairNotSplitByCountCap() {
    // B1 red pin: naive MaxMessages(2) takeLast on
    // [assistant(call a), tool(a), user(tail)] keeps [tool(a), user] — an
    // orphan tool result split from its call. Pair-atomic truncation snaps
    // FORWARD past the split pair: boundaries of the legal history are
    // [0, 2, 3], so the naive start 1 snaps to 2 and exactly [user] survives
    // (the whole orphan pair drops, within cap) — never the 2-row split and
    // never the over-cap whole-pair retain.
    val call = ChatMessage(
      role = "assistant",
      text = "calling",
      toolCalls = listOf(ToolCall(id = "call-a", name = "lsp.symbols", argumentsJson = """{"query":"A"}""")),
    )
    val result = ChatMessage(role = "tool", text = "output-a", toolCallId = "call-a")
    val tail = ChatMessage(role = "user", text = "tail-q")
    val kept = applyHistoryCap(
      listOf(call, result, tail),
      HistoryWindowCap.MaxMessages(2),
    )
    assertEquals(
      "window must drop the split pair whole, got ${kept.map { it.role }}",
      listOf("user"),
      kept.map { it.role },
    )
    assertEquals(listOf("tail-q"), kept.map { it.text })
  }

  @Test(timeout = 30_000) fun emptyHistoryKeepsLegacyShapeUnderDefault() {
    val provider = DefCapFakeProvider(doneOnce())
    val c = controller(provider)
    try {
      runBlocking { c.send("hello") }
      val req = provider.seenRequests.single()
      assertEquals(1, req.messages.size)
      assertEquals("user", req.messages.single().role)
      assertTrue(req.messages.single().text.startsWith("hello"))
      assertEquals(0, c.droppedHistoryCount)
      assertEquals(0L, c.droppedHistoryChars)
    } finally {
      c.close()
    }
  }

  @Test(timeout = 30_000) fun singleHugeMessageSurvivesDefaultCapWhole() {
    // A single live row bigger than the default char budget but within the
    // 100k admission bound is still kept whole (truncation drops messages,
    // never splits one) — at least the newest always survives.
    val big = "w".repeat(70_000)
    val provider = DefCapFakeProvider(doneOnce())
    val c = controller(provider)
    try {
      runBlocking { c.send(big) }
      val req = provider.seenRequests.single()
      assertEquals(1, req.messages.size)
      assertTrue(req.messages.single().text.startsWith(big.substring(0, 100)))
      assertEquals(0, c.droppedHistoryCount)
      assertEquals(0L, c.droppedHistoryChars)
      assertEquals(req.messages.sumOf { it.text.length }.toLong(), c.lastRequestChars)
    } finally {
      c.close()
    }
  }

  @Test(timeout = 30_000) fun defaultIsBoundedDualTrack() {
    // The B1 default is the documented dual-track bound, not Unbounded.
    val def = HistoryWindowCap.DEFAULT
    assertTrue("default must be Bounded, got $def", def is HistoryWindowCap.Bounded)
    val bounded = def as HistoryWindowCap.Bounded
    assertEquals(HistoryWindowCap.DEFAULT_MAX_MESSAGES, bounded.maxMessages)
    assertEquals(HistoryWindowCap.DEFAULT_MAX_CHARS, bounded.maxChars)
    assertEquals(200, bounded.maxMessages)
    assertEquals(60_000, bounded.maxChars)
  }

  @Test(timeout = 30_000) fun boundedRejectsNonPositiveBudgets() {
    fun expectReject(label: String, block: () -> Unit) {
      try {
        block()
        org.junit.Assert.fail("expected $label rejection")
      } catch (_: IllegalArgumentException) {
        // expected: fail-closed at construction, never a silent zero cap.
      }
    }
    expectReject("Bounded(0, 10)") { HistoryWindowCap.Bounded(0, 10) }
    expectReject("Bounded(10, 0)") { HistoryWindowCap.Bounded(10, 0) }
    expectReject("Bounded(0, 0)") { HistoryWindowCap.Bounded(0, 0) }
    expectReject("Bounded(-1, 10)") { HistoryWindowCap.Bounded(-1, 10) }
    expectReject("Bounded(10, -1)") { HistoryWindowCap.Bounded(10, -1) }
    expectReject("MaxMessages(0)") { HistoryWindowCap.MaxMessages(0) }
    expectReject("MaxMessages(-5)") { HistoryWindowCap.MaxMessages(-5) }
    expectReject("MaxChars(0)") { HistoryWindowCap.MaxChars(0) }
    expectReject("MaxChars(-100)") { HistoryWindowCap.MaxChars(-100) }
  }

  @Test(timeout = 30_000) fun boundedEnforcesBothLegs() {
    fun msg(text: String) = ChatMessage(role = "user", text = text)
    // Count leg: 10 tiny rows, char budget huge -> count keeps newest 5.
    val manyTiny = (0 until 10).map { msg("m$it") }
    assertEquals(
      listOf("m5", "m6", "m7", "m8", "m9"),
      applyHistoryCap(manyTiny, HistoryWindowCap.Bounded(5, 1_000_000)).map { it.text },
    )
    // Char leg: 5 x 500-char rows (2500 chars), count budget huge (100) ->
    // char budget 1000 keeps newest 2 whole rows (500 + 500), drops 3.
    val bigRows = (0 until 5).map { msg("r$it:" + "x".repeat(495)) }
    val keptChars = applyHistoryCap(bigRows, HistoryWindowCap.Bounded(100, 1_000))
    assertEquals(2, keptChars.size)
    assertEquals(bigRows.takeLast(2).map { it.text }, keptChars.map { it.text })
  }

  @Test(timeout = 30_000) fun toolPairNotSplitByCharsCap() {
    // Char-leg split: [assistant(call, 37 chars), tool(result, 4), user(tail, 4)]
    // with MaxChars(20) naively keeps [tool, user] (4 + 4 <= 20, plus the
    // call would exceed). Pair-atomicity must drop the orphan tool
    // forward -> [user] only. (Marker-form histories, where the tool marker
    // rides inline in the assistant text, are pinned separately by
    // TurnControllerHistoryTest.maxCharsCapKeepsWholeMessagesSoToolPairsStayIntact.)
    val result = ChatMessage(role = "tool", text = "out4", toolCallId = "call-a")
    // Structured call form (same pair, new envelope): the snap works on the
    // structured ids, not the marker text.
    val structuredCall = ChatMessage(
      role = "assistant",
      text = "calling-" + "x".repeat(30),
      toolCalls = listOf(ToolCall(id = "call-a", name = "lsp.symbols", argumentsJson = """{"query":"A"}""")),
    )
    val tail = ChatMessage(role = "user", text = "tail")
    val kept = applyHistoryCap(
      listOf(structuredCall, result, tail),
      HistoryWindowCap.MaxChars(20),
    )
    assertTrue(
      "char window must not split the tool pair, got ${kept.map { it.role }}",
      kept.none { it.role == "tool" } || kept.any { it.toolCalls.any { tc -> tc.id == "call-a" } },
    )
    assertEquals(listOf("tail"), kept.map { it.text })
  }

  @Test(timeout = 30_000) fun boundedOverLimitTruncationDropsOldestAndCountsChars() {
    // Bounded end-to-end (mirrors the MaxMessages total-window contract):
    // 4-row prefix windows to 2 at load, then the live row evicts one more.
    val history = listOf(
      ChatMessage(role = "user", text = "drop-q1"),
      ChatMessage(role = "assistant", text = "drop-a1"),
      ChatMessage(role = "user", text = "keep-q2"),
      ChatMessage(role = "assistant", text = "keep-a2"),
    )
    val provider = DefCapFakeProvider(doneOnce())
    val c = controller(provider, history, HistoryWindowCap.Bounded(2, 1_000_000))
    try {
      // Load-time seed: 4-row prefix -> 2 newest (drop 2, both plain).
      assertEquals(2, c.droppedHistoryCount)
      assertEquals(
        ("drop-q1".length + "drop-a1".length).toLong(),
        c.droppedHistoryChars,
      )
      runBlocking { c.send("q3") }
      val req = provider.seenRequests.single()
      // Combined (2 prefix + live q3 = 3) windows to 2: evict oldest prefix row.
      assertEquals(1, c.droppedHistoryCount)
      assertEquals("keep-q2".length.toLong(), c.droppedHistoryChars)
      assertEquals(listOf("assistant", "user"), req.messages.map { it.role })
      assertEquals("keep-a2", req.messages[0].text)
      assertTrue(req.messages[1].text.startsWith("q3"))
      assertEquals(req.messages.sumOf { it.text.length }.toLong(), c.lastRequestChars)
    } finally {
      c.close()
    }
  }

  @Test(timeout = 30_000) fun defaultCharLegBounds150RowDialogue() {
    // B1 char-leg pin, end-to-end (the fixture the HistoryWindowCap KDoc
    // measurement note cites): 150 x exactly-500-char rows = 75,000 chars,
    // over the 60k default char leg but under the 100k send-time cap, with
    // the 200-msg count leg never binding (150/121 rows). Load keeps the
    // newest 120 rows (120 x 500 = 60,000; one more would exceed) with seed
    // drop 30 / 15,000 chars; the live row then evicts exactly one more, the
    // send-time trim stays at 0, and the chars reconcile.
    val history = (0 until 150).map { i ->
      val role = if (i % 2 == 0) "user" else "assistant"
      ChatMessage(role = role, text = "h" + i.toString().padStart(4, '0') + ":" + "v".repeat(494))
    }
    assertTrue("fixture must be fixed-width 500-char rows", history.all { it.text.length == 500 })
    val provider = DefCapFakeProvider(doneOnce())
    val c = controller(provider, history)
    try {
      // Load-time seed: 150-row prefix -> newest 120 (drop 30 x 500 chars).
      assertEquals(30, c.droppedHistoryCount)
      assertEquals(15_000L, c.droppedHistoryChars)
      runBlocking { c.send("tail-q") }
      val req = provider.seenRequests.single()
      // Send-time trim never trips (~60k + time block << 100k).
      assertEquals(0, c.lastRequestTrimmedCount)
      assertEquals(0L, c.lastRequestTrimmedChars)
      // Combined (120 prefix + live = 121 rows / 60,006 chars) windows to
      // 120 rows: evict exactly the oldest prefix row (500 chars).
      assertEquals(1, c.droppedHistoryCount)
      assertEquals(500L, c.droppedHistoryChars)
      assertEquals(req.messages.sumOf { it.text.length }.toLong(), c.lastRequestChars)
      // Newest (the live tail) always survives.
      assertTrue(req.messages.last().text.startsWith("tail-q"))
      // Char reconciliation (HistoryWindowCap identity): combined chars +
      // ephemeral time-block delta == dropped + trimmed + final. Base
      // windowed sum before the time block is combined - dropped.
      val combinedChars = 120 * 500L + "tail-q".length
      val timeDelta = c.lastRequestChars - (combinedChars - 500L)
      assertTrue("only the ephemeral time block may differ, got $timeDelta", timeDelta in 1..10_000)
      assertEquals(combinedChars + timeDelta, 500L + 0L + c.lastRequestChars)
    } finally {
      c.close()
    }
  }

  @Test(timeout = 30_000) fun boundedTakesMaxLegThenSnapsForwardOverPair() {
    // Dual-leg max + snap forward: 7 x 10-char rows
    // [p0, p1, p2, p3, assistant(call), tool(result), user(tail)] under
    // Bounded(4, 25). Count leg naive start = 7 - 4 = 3; char leg keeps the
    // newest 2 (10 + 10 <= 25, a third row would exceed) so naive start = 5;
    // max(3, 5) = 5 lands between call and result (the legal history's
    // boundaries are [0, 1, 2, 3, 4, 6, 7]) and snaps FORWARD to 6, so
    // exactly [tail] survives. The count leg alone (naive 3) would keep
    // [p3, call, result, tail] whole — the char leg forces the deeper cut
    // and pair atomicity drops the split pair whole instead of shipping half.
    val plains = (0..3).map { i ->
      val role = if (i % 2 == 0) "user" else "assistant"
      ChatMessage(role = role, text = "p$i" + "v".repeat(8))
    }
    val call = ChatMessage(
      role = "assistant",
      text = "call-" + "x".repeat(5),
      toolCalls = listOf(ToolCall(id = "call-a", name = "lsp.symbols", argumentsJson = """{"query":"A"}""")),
    )
    val result = ChatMessage(role = "tool", text = "out-" + "y".repeat(6), toolCallId = "call-a")
    val tailText = "tail-q" + "z".repeat(4)
    val tail = ChatMessage(role = "user", text = tailText)
    val history = plains + call + result + tail
    assertTrue("fixture must be fixed-width 10-char rows", history.all { it.text.length == 10 })
    assertEquals(
      listOf("p3" + "v".repeat(8), "call-" + "x".repeat(5), "out-" + "y".repeat(6), tailText),
      applyHistoryCap(history, HistoryWindowCap.MaxMessages(4)).map { it.text },
    )
    assertEquals(
      listOf(tailText),
      applyHistoryCap(history, HistoryWindowCap.MaxChars(25)).map { it.text },
    )
    assertEquals(
      "dual max(3, 5) = 5 must snap forward to 6, keeping [tail] only",
      listOf(tailText),
      applyHistoryCap(history, HistoryWindowCap.Bounded(4, 25)).map { it.text },
    )
  }

  @Test(timeout = 30_000) fun orphanNewestToolRowRetainedForDownstreamReject() {
    // No orphan special-case in the window: a lone orphan `tool` row is kept
    // as-is under every cap (naive start 0 / the single-newest rule keeps
    // it), and the downstream provider validator fails closed on the
    // unpaired shape instead of the window silently repairing or dropping it.
    val orphan = ChatMessage(role = "tool", text = "out-x", toolCallId = "no-such-call")
    val single = listOf(orphan)
    assertEquals(single, applyHistoryCap(single, HistoryWindowCap.DEFAULT))
    assertEquals(single, applyHistoryCap(single, HistoryWindowCap.MaxMessages(1)))
    assertEquals(single, applyHistoryCap(single, HistoryWindowCap.MaxChars(1)))
    assertEquals(single, applyHistoryCap(single, HistoryWindowCap.Bounded(1, 1)))
    for (backfill in listOf(true, false)) {
      try {
        validateToolPairing(single, backfill)
        org.junit.Assert.fail("expected orphan tool rejection (backfill=$backfill)")
      } catch (e: ProviderFailure) {
        assertTrue(
          "downstream must fail closed on the orphan id, got ${e.message} (backfill=$backfill)",
          (e.message ?: "").contains("TOOL_OUTPUT_ORPHAN_ID"),
        )
      }
    }
  }

  @Test(timeout = 30_000) fun globallyUnpairedHistoryFallsBackToNaiveCut() {
    // Negative pin: a history that is already globally unpaired is NOT
    // repaired by the window — the naive cut ships as-is and the downstream
    // validator fails closed on the shipped shape.
    // Case 1: dangling trailing batch (call with no result). Naive
    // MaxMessages(1) keeps [call]; the snap must not move or repair it.
    val danglingCall = ChatMessage(
      role = "assistant",
      text = "calling",
      toolCalls = listOf(ToolCall(id = "call-d", name = "lsp.symbols", argumentsJson = """{"query":"D"}""")),
    )
    val keptDangling = applyHistoryCap(
      listOf(ChatMessage(role = "user", text = "u0"), danglingCall),
      HistoryWindowCap.MaxMessages(1),
    )
    assertEquals(listOf("calling"), keptDangling.map { it.text })
    try {
      validateToolPairing(keptDangling, true)
      org.junit.Assert.fail("expected dangling-batch rejection")
    } catch (e: ProviderFailure) {
      assertTrue(
        "dangling batch must fail closed, got ${e.message}",
        (e.message ?: "").contains("TOOL_CALLS_UNRESOLVED"),
      )
    }
    // Case 2: blank toolCallId with multiple pending calls. Since pending.size == 2,
    // backfill is ambiguous and fails, so the history is globally unpaired and the
    // naive MaxMessages(2) cut [tool(blank), tail] is preserved as-is.
    val blankCall = ChatMessage(
      role = "assistant",
      text = "calling",
      toolCalls = listOf(
        ToolCall(id = "c1", name = "lsp.symbols", argumentsJson = """{"query":"E1"}"""),
        ToolCall(id = "c2", name = "lsp.symbols", argumentsJson = """{"query":"E2"}"""),
      ),
    )
    val blankTool = ChatMessage(role = "tool", text = "out", toolCallId = "   ")
    val blankTail = ChatMessage(role = "user", text = "tail")
    val keptBlank = applyHistoryCap(
      listOf(blankCall, blankTool, blankTail),
      HistoryWindowCap.MaxMessages(2),
    )
    assertEquals(listOf("tool", "user"), keptBlank.map { it.role })
    try {
      validateToolPairing(keptBlank, true)
      org.junit.Assert.fail("expected blank-id rejection")
    } catch (_: ProviderFailure) {
      // expected: the naive cut is still unpaired, downstream fails closed.
    }
  }

  @Test(timeout = 30_000) fun singlePendingBackfillSnapsPairAtomically() {
    // Single-pending backfill matching ProviderJson.validateToolPairing(..., allowChatBackfill = true):
    // A tool row with null id matches the single pending tool call, so the pair is
    // recognized as valid. Naive cut inside the pair (MaxMessages(2)) snaps forward
    // past the pair to [tail], while MaxMessages(3) keeps all 3 whole and downstream
    // validateToolPairing(history, true) succeeds.
    val call = ChatMessage(
      role = "assistant",
      text = "calling",
      toolCalls = listOf(ToolCall(id = "c1", name = "lsp.symbols", argumentsJson = """{"query":"A"}""")),
    )
    val result = ChatMessage(role = "tool", text = "out", toolCallId = null)
    val tail = ChatMessage(role = "user", text = "tail")
    val history = listOf(call, result, tail)

    val kept2 = applyHistoryCap(history, HistoryWindowCap.MaxMessages(2))
    assertEquals(listOf("tail"), kept2.map { it.text })

    val kept3 = applyHistoryCap(history, HistoryWindowCap.MaxMessages(3))
    assertEquals(listOf("calling", "out", "tail"), kept3.map { it.text })
    assertEquals(listOf(null, "c1", null), validateToolPairing(history, true))
  }

  @Test(timeout = 30_000) fun degenerateSnapPastEndYieldsEmptyList() {
    // Degenerate snap-past-end: history ends with a pair [call, tool].
    // MaxMessages(1) naively cuts to [tool], which would be an orphan.
    // Boundary snap snaps forward past the end (cut = 2), yielding emptyList().
    val call = ChatMessage(
      role = "assistant",
      text = "calling",
      toolCalls = listOf(ToolCall(id = "c1", name = "lsp.symbols", argumentsJson = """{"query":"A"}""")),
    )
    val res = ChatMessage(role = "tool", text = "out", toolCallId = "c1")
    val history = listOf(call, res)

    val kept = applyHistoryCap(history, HistoryWindowCap.MaxMessages(1))
    assertEquals(emptyList<ChatMessage>(), kept)
  }

  @Test(timeout = 30_000) fun multiTurnSequentialToolRoundsSnapCleanlyToIntermediateBoundary() {
    val u0 = ChatMessage(role = "user", text = "u0")
    val call1 = ChatMessage(
      role = "assistant",
      text = "call1",
      toolCalls = listOf(ToolCall(id = "c1", name = "fn", argumentsJson = "{}")),
    )
    val res1 = ChatMessage(role = "tool", text = "res1", toolCallId = "c1")
    val u1 = ChatMessage(role = "user", text = "u1")
    val call2 = ChatMessage(
      role = "assistant",
      text = "call2",
      toolCalls = listOf(ToolCall(id = "c2", name = "fn", argumentsJson = "{}")),
    )
    val res2 = ChatMessage(role = "tool", text = "res2", toolCallId = "c2")
    val tail = ChatMessage(role = "user", text = "tail")
    val history = listOf(u0, call1, res1, u1, call2, res2, tail)

    val kept = applyHistoryCap(history, HistoryWindowCap.MaxMessages(5))
    assertEquals(listOf("u1", "call2", "res2", "tail"), kept.map { it.text })
    assertEquals(listOf(null, null, "c2", null), validateToolPairing(kept, true))
  }

  @Test(timeout = 30_000) fun outOfOrderBatchToolResultsResolveToCleanTurnBoundary() {
    val batch = ChatMessage(
      role = "assistant",
      text = "calling-both",
      toolCalls = listOf(
        ToolCall(id = "c1", name = "lsp.symbols", argumentsJson = """{"query":"A"}"""),
        ToolCall(id = "c2", name = "lsp.symbols", argumentsJson = """{"query":"B"}"""),
      ),
    )
    val res2 = ChatMessage(role = "tool", text = "out-2", toolCallId = "c2")
    val res1 = ChatMessage(role = "tool", text = "out-1", toolCallId = "c1")
    val tail = ChatMessage(role = "user", text = "tail")
    val history = listOf(batch, res2, res1, tail)

    // Naive cut between res2 and res1 (MaxMessages(2) -> naiveStart = 2, landing on res1)
    // snaps forward past the whole batch to [tail].
    val kept = applyHistoryCap(history, HistoryWindowCap.MaxMessages(2))
    assertEquals(listOf("tail"), kept.map { it.text })

    // When all 4 are within cap, out-of-order results drain pending cleanly and validate downstream.
    val keptAll = applyHistoryCap(history, HistoryWindowCap.MaxMessages(4))
    assertEquals(listOf("calling-both", "out-2", "out-1", "tail"), keptAll.map { it.text })
    assertEquals(listOf(null, "c2", "c1", null), validateToolPairing(keptAll, true))
  }

  @Test(timeout = 30_000) fun multiCallBatchSnapsForwardWhole() {
    // One assistant row carrying 2 toolCalls plus both matching tool rows: a
    // naive cut anywhere inside the batch must snap forward over the WHOLE
    // batch ([tail] only), never ship a half batch. Legal-history boundaries
    // are [0, 3, 4], so naive starts 1/2/3 all snap to 3.
    val batch = ChatMessage(
      role = "assistant",
      text = "calling-both",
      toolCalls = listOf(
        ToolCall(id = "call-a", name = "lsp.symbols", argumentsJson = """{"query":"A"}"""),
        ToolCall(id = "call-b", name = "lsp.symbols", argumentsJson = """{"query":"B"}"""),
      ),
    )
    val outA = ChatMessage(role = "tool", text = "out-a", toolCallId = "call-a")
    val outB = ChatMessage(role = "tool", text = "out-b", toolCallId = "call-b")
    val tail = ChatMessage(role = "user", text = "tail")
    val history = listOf(batch, outA, outB, tail)
    for (max in listOf(3, 2, 1)) {
      assertEquals(
        "naive cut inside the batch (max=$max) must snap forward whole",
        listOf("tail"),
        applyHistoryCap(history, HistoryWindowCap.MaxMessages(max)).map { it.text },
      )
    }
    // No cut needed: the whole batch ships intact.
    assertEquals(4, applyHistoryCap(history, HistoryWindowCap.MaxMessages(4)).size)
    // And the intact batch is valid downstream (ids resolve in order).
    assertEquals(
      listOf(null, "call-a", "call-b", null),
      validateToolPairing(history, true),
    )
  }

  @Test(timeout = 30_000) fun sendTimeTrimPreservesToolPairAtomicityUnderUnbounded() {
    val call = ChatMessage(
      role = "assistant",
      text = "call:" + "x".repeat(30_000),
      toolCalls = listOf(ToolCall("c1", "tool", "{}")),
    )
    val result = ChatMessage(role = "tool", text = "out:" + "y".repeat(25_000), toolCallId = "c1")
    val tail = "tail:" + "z".repeat(50_000)
    val provider = DefCapFakeProvider(doneOnce())
    val c = controller(provider, listOf(call, result), HistoryWindowCap.Unbounded)
    try {
      runBlocking { c.send(tail) }
      assertEquals(2, c.lastRequestTrimmedCount)
      val req = provider.seenRequests.single()
      assertTrue("shipped messages must contain no orphan tool messages", req.messages.none { it.role == "tool" })
      assertEquals(1, req.messages.size)
      assertEquals("user", req.messages.single().role)
      assertTrue(req.messages.single().text.startsWith("tail:"))
      validateToolPairing(req.messages, true)
    } finally {
      c.close()
    }
  }

  @Test(timeout = 30_000) fun sendTimeTrimRetainsIntactPairWhenHeadPlainMessageDrops() {
    val u0 = ChatMessage(role = "user", text = "u0:" + "a".repeat(30_000))
    val call = ChatMessage(
      role = "assistant",
      text = "call:" + "x".repeat(20_000),
      toolCalls = listOf(ToolCall("c1", "tool", "{}")),
    )
    val result = ChatMessage(role = "tool", text = "out:" + "y".repeat(20_000), toolCallId = "c1")
    val tail = "tail:" + "z".repeat(40_000)
    val provider = DefCapFakeProvider(doneOnce())
    val c = controller(provider, listOf(u0, call, result), HistoryWindowCap.Unbounded)
    try {
      runBlocking { c.send(tail) }
      assertEquals(1, c.lastRequestTrimmedCount)
      val req = provider.seenRequests.single()
      assertEquals(3, req.messages.size)
      assertEquals(listOf("assistant", "tool", "user"), req.messages.map { it.role })
      assertEquals(call.text, req.messages[0].text)
      assertEquals(result.text, req.messages[1].text)
      assertTrue(req.messages[2].text.startsWith("tail:"))
      validateToolPairing(req.messages, true)
    } finally {
      c.close()
    }
  }

  @Test(timeout = 30_000) fun orphanPrefixOutsideWindowIsDroppedWhileValidTailSucceeds() {
    val orphanTool = ChatMessage(role = "tool", text = "out-orphan", toolCallId = "orphan-call")
    val userTail = ChatMessage(role = "user", text = "valid tail")
    val history = listOf(orphanTool, userTail)

    // When cap = MaxMessages(1), orphan prefix outside the window is dropped.
    val kept1 = applyHistoryCap(history, HistoryWindowCap.MaxMessages(1))
    assertEquals(listOf(userTail), kept1)
    // Downstream validateToolPairing([userTail], true) succeeds without error.
    validateToolPairing(kept1, true)

    // When cap = MaxMessages(2), applyHistoryCap keeps [orphanTool, userTail].
    val kept2 = applyHistoryCap(history, HistoryWindowCap.MaxMessages(2))
    assertEquals(listOf(orphanTool, userTail), kept2)
    // Downstream validateToolPairing fails closed with TOOL_OUTPUT_ORPHAN_ID.
    try {
      validateToolPairing(kept2, true)
      org.junit.Assert.fail("expected orphan tool rejection when kept in window")
    } catch (e: ProviderFailure) {
      assertTrue(
        "downstream must fail closed with TOOL_OUTPUT_ORPHAN_ID, got ${e.message}",
        (e.message ?: "").contains("TOOL_OUTPUT_ORPHAN_ID"),
      )
    }
  }

  @Test(timeout = 30_000) fun exactMessageCapBoundaries() {
    fun msg(text: String) = ChatMessage(role = "user", text = text)
    val cap5 = HistoryWindowCap.MaxMessages(5)

    // 5 plain messages with MaxMessages(5) keeps all 5.
    val msgs5 = (1..5).map { msg("m$it") }
    assertEquals(msgs5, applyHistoryCap(msgs5, cap5))

    // 6 plain messages with MaxMessages(5) drops exactly oldest 1, keeps newest 5.
    val msgs6 = (1..6).map { msg("m$it") }
    assertEquals(msgs6.drop(1), applyHistoryCap(msgs6, cap5))

    // 4 plain messages with MaxMessages(5) keeps all 4.
    val msgs4 = (1..4).map { msg("m$it") }
    assertEquals(msgs4, applyHistoryCap(msgs4, cap5))
  }

  @Test(timeout = 30_000) fun exactCharCapBoundaries() {
    fun msg(text: String) = ChatMessage(role = "user", text = text)
    val cap40 = HistoryWindowCap.MaxChars(40)

    val m1 = msg("a".repeat(20))
    val m2 = msg("b".repeat(20))
    val m3 = msg("c".repeat(20))

    // 2 messages of 20 chars each (total 40 chars) with MaxChars(40) keeps both.
    val two = listOf(m1, m2)
    assertEquals(two, applyHistoryCap(two, cap40))

    // Add 3rd message of 20 chars (total 60 chars) with MaxChars(40) keeps newest 2 (40 chars) and drops oldest 1.
    val three = listOf(m1, m2, m3)
    assertEquals(listOf(m2, m3), applyHistoryCap(three, cap40))

    // Message of 41 chars with MaxChars(40) keeps the message whole (single newest rule).
    val m41 = msg("x".repeat(41))
    assertEquals(listOf(m41), applyHistoryCap(listOf(m41), cap40))
    assertEquals(listOf(m41), applyHistoryCap(listOf(m1, m41), cap40))
  }

  @Test(timeout = 30_000) fun invalidDiscardedPrefixDoesNotBreakLaterPairSnapping() {
    val orphan = ChatMessage(role = "tool", text = "orphan", toolCallId = "unknown")
    val call = ChatMessage(
      role = "assistant",
      text = "call",
      toolCalls = listOf(ToolCall(id = "a", name = "fn", argumentsJson = "{}")),
    )
    val answer = ChatMessage(role = "tool", text = "answer", toolCallId = "a")
    val tail = ChatMessage(role = "user", text = "tail")
    val history = listOf(orphan, call, answer, tail)

    val kept4 = applyHistoryCap(history, HistoryWindowCap.MaxMessages(4))
    assertEquals(history, kept4)
    try {
      validateToolPairing(kept4, true)
      org.junit.Assert.fail("expected orphan tool rejection for MaxMessages(4)")
    } catch (e: ProviderFailure) {
      assertTrue(
        "downstream must fail closed with TOOL_OUTPUT_ORPHAN_ID, got ${e.message}",
        (e.message ?: "").contains("TOOL_OUTPUT_ORPHAN_ID"),
      )
    }

    val kept3 = applyHistoryCap(history, HistoryWindowCap.MaxMessages(3))
    assertEquals(listOf(call, answer, tail), kept3)
    assertEquals(listOf(null, "a", null), validateToolPairing(kept3, true))

    val kept2 = applyHistoryCap(history, HistoryWindowCap.MaxMessages(2))
    assertEquals(listOf(tail), kept2)
    assertEquals(listOf(null), validateToolPairing(kept2, true))

    val kept1 = applyHistoryCap(history, HistoryWindowCap.MaxMessages(1))
    assertEquals(listOf(tail), kept1)
    assertEquals(listOf(null), validateToolPairing(kept1, true))
  }

  @Test(timeout = 30_000) fun invalidDiscardedPrefixUnderBoundedCapAndSendTimeTrim() {
    // Part 1: Bounded char-leg and count-leg cuts after an orphan prefix.
    val orphan = ChatMessage(role = "tool", text = "orphan-" + "x".repeat(13), toolCallId = "unknown")
    val call = ChatMessage(
      role = "assistant",
      text = "call-" + "y".repeat(25),
      toolCalls = listOf(ToolCall(id = "a", name = "fn", argumentsJson = "{}")),
    )
    val answer = ChatMessage(role = "tool", text = "ans-" + "z".repeat(6), toolCallId = "a")
    val tail = ChatMessage(role = "user", text = "tail-" + "w".repeat(5))
    val boundedHistory = listOf(orphan, call, answer, tail)
    assertEquals(listOf(20, 30, 10, 10), boundedHistory.map { it.text.length })

    val keptCharBounded = applyHistoryCap(
      boundedHistory,
      HistoryWindowCap.Bounded(maxMessages = 10, maxChars = 25),
    )
    assertEquals(listOf(tail), keptCharBounded)
    assertEquals(listOf(null), validateToolPairing(keptCharBounded, true))

    val keptCountBounded = applyHistoryCap(
      boundedHistory,
      HistoryWindowCap.Bounded(maxMessages = 2, maxChars = 100),
    )
    assertEquals(listOf(tail), keptCountBounded)
    assertEquals(listOf(null), validateToolPairing(keptCountBounded, true))

    // Part 2: Send-time MAX_REQUEST_CHARS trim under HistoryWindowCap.Unbounded after orphan prefix.
    val bigOrphan = ChatMessage(
      role = "tool",
      text = "orphan:" + "o".repeat(29_993),
      toolCallId = "unknown",
    )
    val bigCallA = ChatMessage(
      role = "assistant",
      text = "call:" + "c".repeat(29_995),
      toolCalls = listOf(ToolCall(id = "a", name = "fn", argumentsJson = "{}")),
    )
    val bigToolA = ChatMessage(
      role = "tool",
      text = "tool:" + "t".repeat(24_995),
      toolCallId = "a",
    )
    assertEquals(30_000, bigOrphan.text.length)
    assertEquals(30_000, bigCallA.text.length)
    assertEquals(25_000, bigToolA.text.length)

    val liveTail = "tail:" + "z".repeat(50_000)
    val provider = DefCapFakeProvider(doneOnce())
    val c = controller(provider, listOf(bigOrphan, bigCallA, bigToolA), HistoryWindowCap.Unbounded)
    try {
      runBlocking { c.send(liveTail) }
      val req = provider.seenRequests.single()
      assertEquals(3, c.lastRequestTrimmedCount)
      assertEquals(0, c.droppedHistoryCount)
      assertEquals(1, req.messages.size)
      assertEquals("user", req.messages.single().role)
      assertTrue(req.messages.single().text.startsWith("tail:"))
      assertEquals(listOf(null), validateToolPairing(req.messages, true))
    } finally {
      c.close()
    }
  }

  @Test(timeout = 30_000) fun malformedBatchAndToolRowWithToolCallsFailClosed() {
    // Part 1: role == "tool" row carrying non-empty toolCalls causes completeToolBatchEnd to
    // return -1; MaxMessages(2) keeps [tool, tail] and downstream fails closed.
    val callA1 = ChatMessage(
      role = "assistant",
      text = "call-a",
      toolCalls = listOf(ToolCall(id = "a", name = "fn", argumentsJson = "{}")),
    )
    val toolWithCalls = ChatMessage(
      role = "tool",
      text = "out",
      toolCallId = "a",
      toolCalls = listOf(ToolCall(id = "b", name = "fn", argumentsJson = "{}")),
    )
    val tail1 = ChatMessage(role = "user", text = "tail")
    val kept1 = applyHistoryCap(
      listOf(callA1, toolWithCalls, tail1),
      HistoryWindowCap.MaxMessages(2),
    )
    assertEquals(listOf(toolWithCalls, tail1), kept1)
    try {
      validateToolPairing(kept1, true)
      org.junit.Assert.fail("expected TOOL_CALLS_ROLE_MUST_BE_ASSISTANT rejection")
    } catch (e: ProviderFailure) {
      assertTrue(
        "downstream must fail closed with TOOL_CALLS_ROLE_MUST_BE_ASSISTANT, got ${e.message}",
        (e.message ?: "").contains("TOOL_CALLS_ROLE_MUST_BE_ASSISTANT"),
      )
    }

    // Part 2: malformed batch before a valid pair is skipped so later pair snapping still works.
    val danglingCall = ChatMessage(
      role = "assistant",
      text = "dangling",
      toolCalls = listOf(ToolCall(id = "d", name = "fn", argumentsJson = "{}")),
    )
    val orphan = ChatMessage(role = "tool", text = "orphan", toolCallId = "x")
    val callA2 = ChatMessage(
      role = "assistant",
      text = "call-a",
      toolCalls = listOf(ToolCall(id = "a", name = "fn", argumentsJson = "{}")),
    )
    val answerA = ChatMessage(role = "tool", text = "answer-a", toolCallId = "a")
    val tail2 = ChatMessage(role = "user", text = "tail")
    val kept2 = applyHistoryCap(
      listOf(danglingCall, orphan, callA2, answerA, tail2),
      HistoryWindowCap.MaxMessages(2),
    )
    assertEquals(listOf(tail2), kept2)
    assertEquals(listOf(null), validateToolPairing(kept2, true))
  }
}
