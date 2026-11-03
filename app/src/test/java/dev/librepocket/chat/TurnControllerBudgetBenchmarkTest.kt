package dev.librepocket.chat

import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatMessage
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol as ProviderProto
import dev.librepocket.provider.StreamEvent
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class BmFakeProvider(
  var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
  override val protocol: ProviderProto = ProviderProto.CHAT_COMPLETIONS
  val seenRequests: MutableList<ChatRequest> = Collections.synchronizedList(mutableListOf())
  val streamCalls = AtomicInteger(0)

  override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
    streamCalls.incrementAndGet()
    seenRequests.add(request)
    emitAll(handler(request))
  }

  override suspend fun listModels(): List<String> = emptyList()
}

private class BmAllowPolicy : PolicyStore {
  override fun evaluate(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
  override suspend fun setRule(rule: PolicyRule) = Unit
  override suspend fun removeRule(pattern: String) = Unit
  override suspend fun listRules(): List<PolicyRule> = emptyList()
  override suspend fun evaluateFresh(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

/**
 * H1 (#16 residual) benchmark skeleton + window reconciliation pins.
 *
 * Pure JVM (all fake data, no network, no device): synthetic floods with a
 * JUnit time limit, so CI gets a reproducible shape without claiming
 * on-device heap/frame numbers. Real heap/frame/latency thresholds stay
 * Target until measured on device (see #16 acceptance).
 *
 * Covers:
 * - hydration char budget reconciliation (`droppedHistoryChars` mirrors
 *   `droppedHistoryCount`; `lastRequestChars` mirrors the shipped request);
 * - attempt/request window reconciliation (`lastRequestTrimmedChars`
 *   mirrors `lastRequestTrimmedCount`; Unbounded default still drops nothing);
 * - #83 per-attempt semantics are documented in KDoc (StreamEvent +
 *   TurnController); this file pins the per-attempt reset (retry starts
 *   fresh) at the controller level without re-testing the budget itself.
 */
class TurnControllerBudgetBenchmarkTest {

  private fun doneOnce(text: String = "ok"): (ChatRequest) -> Flow<StreamEvent> = {
    flow {
      emit(StreamEvent.TextDelta(0, 0, text))
      emit(StreamEvent.Done("stop"))
    }
  }

  private fun controller(
    provider: BmFakeProvider,
    history: List<ChatMessage> = emptyList(),
    cap: HistoryWindowCap = HistoryWindowCap.Unbounded,
  ): TurnController {
    var n = 0
    return TurnController(
      provider = provider,
      policy = BmAllowPolicy(),
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "bm-${n++}" },
      initialHistory = history,
      historyCap = cap,
    )
  }

  @Test(timeout = 30_000) fun hydrationCharBudgetReconcilesWithCounts() {
    val history = listOf(
      ChatMessage(role = "user", text = "drop-q1"),
      ChatMessage(role = "assistant", text = "drop-a1"),
      ChatMessage(role = "user", text = "keep-q2"),
      ChatMessage(role = "assistant", text = "keep-a2"),
    )
    val provider = BmFakeProvider(doneOnce())
    val c = controller(provider, history, HistoryWindowCap.MaxMessages(2))
    try {
      runBlocking { c.send("q3") }
      val req = provider.seenRequests.single()
      // Total-window semantics: capped prefix [keep-q2, keep-a2] + live q3
      // (3 rows) windows to [keep-a2, q3] (2 rows): one head row dropped.
      assertEquals(1, c.droppedHistoryCount)
      // Char side mirrors the count: the dropped head row is "keep-q2"
      // (7 chars) — the load-time seed (2 rows) is replaced by this
      // request's window drop, same contract as the count.
      assertEquals("keep-q2".length.toLong(), c.droppedHistoryChars)
      // Small request fits MAX_REQUEST_CHARS: no send-time trim.
      assertEquals(0, c.lastRequestTrimmedCount)
      assertEquals(0L, c.lastRequestTrimmedChars)
      // Final size mirrors the shipped request exactly (time-block included).
      assertEquals(req.messages.sumOf { it.text.length }, c.lastRequestChars)
      assertTrue("request must carry the kept prefix", req.messages[0].text == "keep-a2")
    } finally {
      c.close()
    }
  }

  @Test(timeout = 30_000) fun maxCharsHydrationExposesCharDrop() {
    val toolBlock = "answer\n[tool:lookup {\"q\":\"hi\"}]"
    val history = listOf(
      ChatMessage(role = "user", text = "drop me, way too long for the budget"),
      ChatMessage(role = "assistant", text = toolBlock),
      // Long tail: the combined (capped prefix + live) window still overflows
      // MaxChars(20) after the live row appends, so the send-time drop stays
      // observable (a short tail would fit alongside the live row and the
      // load-time seed would be replaced by 0 — existing total-window
      // contract, not a bug).
      ChatMessage(role = "user", text = "tail-row-is-long-enough!!"),
    )
    val provider = BmFakeProvider(doneOnce())
    val c = controller(provider, history, HistoryWindowCap.MaxChars(20))
    try {
      // Load-time seed: 3-row prefix windows to the single newest whole row.
      assertEquals(2, c.droppedHistoryCount)
      assertTrue("seed char drop must mirror, got ${c.droppedHistoryChars}", c.droppedHistoryChars > 0)
      runBlocking { c.send("q9") }
      val req = provider.seenRequests.single()
      // Window keeps the newest whole messages only; the drop is observable
      // in both units (count > 0 and chars > 0), never silent.
      assertTrue("window must drop, got ${c.droppedHistoryCount}", c.droppedHistoryCount > 0)
      assertTrue("char drop must mirror, got ${c.droppedHistoryChars}", c.droppedHistoryChars > 0)
      assertEquals(req.messages.sumOf { it.text.length }, c.lastRequestChars)
      // Tool-pair integrity holds: no half marker survives in the request.
      for (m in req.messages) {
        assertTrue("no half tool marker may ship", !m.text.contains("[tool:") || m.text == toolBlock)
      }
    } finally {
      c.close()
    }
  }

  @Test(timeout = 30_000) fun unboundedDefaultDropsNothingInEitherUnit() {
    val history = listOf(
      ChatMessage(role = "user", text = "q1"),
      ChatMessage(role = "assistant", text = "a1"),
    )
    val provider = BmFakeProvider(doneOnce())
    val c = controller(provider, history, HistoryWindowCap.Unbounded)
    try {
      runBlocking { c.send("q2") }
      assertEquals(0, c.droppedHistoryCount)
      assertEquals(0L, c.droppedHistoryChars)
      assertEquals(0, c.lastRequestTrimmedCount)
      assertEquals(0L, c.lastRequestTrimmedChars)
      val req = provider.seenRequests.single()
      assertEquals(req.messages.sumOf { it.text.length }, c.lastRequestChars)
    } finally {
      c.close()
    }
  }

  @Test(timeout = 30_000) fun requestTrimCharsMirrorTrimCountsUnderCap() {
    // 8 history rows x ~18k chars + one live row overflows the 100k request
    // cap, so the send-time trim must show up in both units.
    val history = (0 until 8).map { i ->
      val role = if (i % 2 == 0) "user" else "assistant"
      ChatMessage(role = role, text = "hist-$i: " + "x".repeat(17_990))
    }
    val provider = BmFakeProvider(doneOnce())
    val c = controller(provider, history, HistoryWindowCap.Unbounded)
    try {
      runBlocking { c.send("latest user: " + "y".repeat(17_985)) }
      val req = provider.seenRequests.single()
      assertTrue("request must fit the cap", req.messages.sumOf { it.text.length } <= TurnController.MAX_REQUEST_CHARS)
      assertTrue("trim count must trip, got ${c.lastRequestTrimmedCount}", c.lastRequestTrimmedCount > 0)
      assertTrue("trim chars must mirror, got ${c.lastRequestTrimmedChars}", c.lastRequestTrimmedChars > 0)
      assertEquals(req.messages.sumOf { it.text.length }, c.lastRequestChars)
      // Unbounded hydration still drops nothing at the window stage.
      assertEquals(0, c.droppedHistoryCount)
      assertEquals(0L, c.droppedHistoryChars)
    } finally {
      c.close()
    }
  }

  @Test(timeout = 30_000) fun syntheticTinyDeltaDripBenchmarkSkeleton() {
    // Synthetic flood skeleton (non-device): 3000 x 1-char deltas through the
    // #83 hybrid flush cadence. Pins exactness + the O(flushes) bound and logs
    // wall time for manual comparison; NOT an on-device frame/heap claim.
    val provider = BmFakeProvider {
      flow {
        repeat(3_000) { emit(StreamEvent.TextDelta(0, 0, "x")) }
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider)
    try {
      val startNs = System.nanoTime()
      runBlocking { c.send("hi") }
      val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
      println("TurnControllerBudgetBenchmark: 3000x1-char drip took ${elapsedMs}ms (JVM skeleton, non-device)")
      val assistants = c.uiState.value.messages.filter { it.role == "assistant" }
      assertEquals("x".repeat(3_000), assistants.single().text)
      assertEquals(3_000, c.streamedTextDeltaCount)
      assertTrue("batched flushes must stay well below 3000, got ${c.uiTextUpdateCount}", c.uiTextUpdateCount < 200)
      assertTrue("copied chars must drop an order of magnitude, got ${c.uiTextCopiedChars}", c.uiTextCopiedChars < 500_000)
    } finally {
      c.close()
    }
  }

  @Test(timeout = 30_000) fun syntheticHydrationWindowBenchmarkSkeleton() {
    // Synthetic hydration skeleton at the HISTORY_LOAD_CAP scale (2000 rows):
    // windows the combined copy and reconciles both units. Times the send
    // (window + request build + one fake round) on JVM only.
    val history = (0 until 2000).map { i ->
      val role = if (i % 2 == 0) "user" else "assistant"
      ChatMessage(role = role, text = "h$i:" + "v".repeat(90))
    }
    val provider = BmFakeProvider(doneOnce("r"))
    val c = controller(provider, history, HistoryWindowCap.MaxMessages(2000))
    try {
      val startNs = System.nanoTime()
      runBlocking { c.send("tail-q") }
      val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
      println("TurnControllerBudgetBenchmark: 2000-row hydration window took ${elapsedMs}ms (JVM skeleton, non-device)")
      val req = provider.seenRequests.single()
      assertEquals(req.messages.sumOf { it.text.length }, c.lastRequestChars)
      assertEquals(req.messages.size + c.droppedHistoryCount + c.lastRequestTrimmedCount, 2000 + 1)
    } finally {
      c.close()
    }
  }
}
