package dev.librepocket.chat

import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatMessage
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.MAX_TOOL_CALLS_PER_ATTEMPT
import dev.librepocket.provider.ProviderProtocol as ProviderProto
import dev.librepocket.provider.StreamEvent
import dev.librepocket.provider.TOOL_ARGS_TOO_LARGE
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class AggFakeProvider(
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

private class AggAllowPolicy : PolicyStore {
  override fun evaluate(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
  override suspend fun setRule(rule: PolicyRule) = Unit
  override suspend fun removeRule(pattern: String) = Unit
  override suspend fun listRules(): List<PolicyRule> = emptyList()
  override suspend fun evaluateFresh(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

private class AggRecordingSink : TranscriptSink {
  val failed: MutableList<String> = Collections.synchronizedList(mutableListOf())
  data class Tool(val runId: String, val toolIndex: Int, val id: String, val name: String, val args: String)
  val tools: MutableList<Tool> = Collections.synchronizedList(mutableListOf())

  override suspend fun onTurnStarted(runId: String, text: String) = Unit
  override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) = Unit
  override suspend fun onTurnSucceeded(
    runId: String,
    text: String,
    parentRunId: String?,
    attemptIndex: Int?,
    hasReasoning: Boolean,
  ) = onTurnSucceeded(runId, text, hasReasoning)
  override suspend fun onTurnFailed(runId: String, error: String) { failed.add(error) }
  override suspend fun onTurnFailed(
    runId: String,
    partialText: String,
    error: String,
    parentRunId: String?,
    attemptIndex: Int?,
  ) = onTurnFailed(runId, partialText, error)
  override suspend fun onTurnFailed(
    runId: String,
    partialText: String,
    error: String,
    parentRunId: String?,
    attemptIndex: Int?,
    isFinal: Boolean,
  ) = onTurnFailed(runId, partialText, error, parentRunId, attemptIndex)
  override suspend fun onTurnFailed(
    runId: String,
    partialText: String,
    error: String,
    parentRunId: String?,
    attemptIndex: Int?,
    isFinal: Boolean,
    hasReasoning: Boolean,
  ) = onTurnFailed(runId, partialText, error, parentRunId, attemptIndex, isFinal)
  override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
  override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) = Unit
  override suspend fun onTurnCancelled(
    runId: String,
    partialText: String,
    parentRunId: String?,
    attemptIndex: Int?,
    hasReasoning: Boolean,
  ) = onTurnCancelled(runId, partialText, hasReasoning)
  override suspend fun onSteerQueued(text: String) = Unit
  override suspend fun onToolDone(runId: String, toolIndex: Int, id: String, name: String, argumentsJson: String) {
    tools.add(Tool(runId, toolIndex, id, name, argumentsJson))
  }
  override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) = Unit
}

/**
 * #16 remainder regressions for [TurnController]:
 * - per-call tool aggregation overflow is a typed non-retryable terminal
 *   (`TOOL_ARGS_TOO_LARGE`): no retry of the poisoned stream, no
 *   poison-adjacent tool records, failed fragment stays partial;
 * - Finding #1: the same typed terminal guards the per-attempt whole-round
 *   tool total (many small ToolDeltas across many indexes) and the
 *   distinct-index cap (at most `MAX_TOOL_CALLS_PER_ATTEMPT` tool indexes per
 *   attempt), with the same no-retry / no-poison-record behavior;
 * - UI-update cost counters observe every delta (batching downgraded to
 *   counters + KDoc TODO until on-device measurements justify a threshold);
 * - [HistoryWindowCap] windows only the outgoing request copy (Unbounded by
 *   default), oldest-first at whole-message boundaries, with an observable
 *   drop count.
 */
class TurnControllerAggBudgetTest {

  private fun controller(
    provider: AggFakeProvider,
    transcript: TranscriptSink = NoOpTranscriptSink(),
    historyCap: HistoryWindowCap = HistoryWindowCap.Unbounded,
  ): TurnController {
    var n = 0
    return TurnController(
      provider = provider,
      policy = AggAllowPolicy(),
      transcript = transcript,
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "agg-${n++}" },
      historyCap = historyCap,
    )
  }

  @Test fun toolDeltaFloodFailsTypedWithoutRetryOrPoisonRecords() {
    val provider = AggFakeProvider {
      flow {
        repeat(400) {
          emit(StreamEvent.ToolDelta(0, "id", "nm", "x".repeat(4_096)))
        }
        emit(StreamEvent.Done("stop"))
      }
    }
    val sink = AggRecordingSink()
    val c = controller(provider, sink)
    try {
      runBlocking { c.send("hi") }
      assertEquals(ChatStatus.ERROR, c.uiState.value.status)
      val error = c.uiState.value.error.orEmpty()
      assertTrue("typed overflow expected, got: $error", error.contains("TOOL_ARGS_TOO_LARGE"))
      assertEquals("poisoned stream must not be retried", 1, provider.streamCalls.get())
      var guard = 0
      while (sink.failed.isEmpty()) {
        if (++guard > 800) fail("timed out waiting for transcript failure")
        Thread.sleep(10)
      }
      assertEquals(1, sink.failed.size)
      assertTrue(
        "transcript must carry the typed reason, got: ${sink.failed.single()}",
        sink.failed.single().contains("TOOL_ARGS_TOO_LARGE"),
      )
      // No onToolDone is ever launched for the poisoned index (fail-closed
      // skip runs synchronously before the terminal projection); settle
      // briefly so a stray async record would have landed, then assert.
      Thread.sleep(300)
      assertTrue("no poison-adjacent tool records may be persisted", sink.tools.isEmpty())
      val assistants = c.uiState.value.messages.filter { it.role == "assistant" }
      assertEquals(1, assistants.size)
      assertTrue("failed attempt keeps its fragment partial", assistants.single().isPartial)
    } finally {
      c.close()
    }
  }

  @Test fun manyDistinctToolIndexesFailTypedWithoutRetry() {
    val provider = AggFakeProvider {
      flow {
        for (i in 0 until 1000) emit(StreamEvent.ToolDelta(i, "id-$i", "n-$i", "x"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val sink = AggRecordingSink()
    val c = controller(provider, sink)
    try {
      runBlocking { c.send("hi") }
      assertEquals(ChatStatus.ERROR, c.uiState.value.status)
      val error = c.uiState.value.error.orEmpty()
      assertTrue("typed overflow expected, got: $error", error.contains(TOOL_ARGS_TOO_LARGE))
      assertEquals("poisoned stream must not be retried", 1, provider.streamCalls.get())
      var guard = 0
      while (sink.failed.isEmpty()) {
        if (++guard > 800) fail("timed out waiting for transcript failure")
        Thread.sleep(10)
      }
      assertEquals(1, sink.failed.size)
      assertTrue(
        "transcript must carry the typed reason, got: ${sink.failed.single()}",
        sink.failed.single().contains(TOOL_ARGS_TOO_LARGE),
      )
      Thread.sleep(300)
      assertTrue("no poison-adjacent tool records may be persisted", sink.tools.isEmpty())
    } finally {
      c.close()
    }
  }

  @Test fun totalAcrossToolIndexesFailsTypedWithoutRetry() {
    // 4 indexes x 120 x ~1k chars = ~483k total (trips); per-index ~121k
    // stays under the per-call budget, so only the attempt total can trip.
    val provider = AggFakeProvider {
      flow {
        repeat(120) {
          for (t in 0 until 4) emit(StreamEvent.ToolDelta(t, "id-$t", "n-$t", "y".repeat(1_000)))
        }
        emit(StreamEvent.Done("stop"))
      }
    }
    val sink = AggRecordingSink()
    val c = controller(provider, sink)
    try {
      runBlocking { c.send("hi") }
      assertEquals(ChatStatus.ERROR, c.uiState.value.status)
      val error = c.uiState.value.error.orEmpty()
      assertTrue("typed overflow expected, got: $error", error.contains(TOOL_ARGS_TOO_LARGE))
      assertEquals("poisoned stream must not be retried", 1, provider.streamCalls.get())
      var guard = 0
      while (sink.failed.isEmpty()) {
        if (++guard > 800) fail("timed out waiting for transcript failure")
        Thread.sleep(10)
      }
      assertEquals(1, sink.failed.size)
      assertTrue(
        "transcript must carry the typed reason, got: ${sink.failed.single()}",
        sink.failed.single().contains(TOOL_ARGS_TOO_LARGE),
      )
      Thread.sleep(300)
      assertTrue("no poison-adjacent tool records may be persisted", sink.tools.isEmpty())
    } finally {
      c.close()
    }
  }

  @Test fun distinctToolCountCapBoundary() {
    val provider = AggFakeProvider()
    provider.handler = {
      flow {
        // First turn stays within the cap; the second turn adds exactly one
        // more distinct index. Both turns use 1-char fragments so only the
        // distinct-index cap can trip, never a char budget.
        val n = if (provider.streamCalls.get() == 1) MAX_TOOL_CALLS_PER_ATTEMPT else MAX_TOOL_CALLS_PER_ATTEMPT + 1
        for (i in 0 until n) emit(StreamEvent.ToolDelta(i, null, null, "x"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider)
    try {
      runBlocking { c.send("first") }
      assertEquals(1, provider.streamCalls.get())
      assertEquals(ChatStatus.IDLE, c.uiState.value.status)
      assertEquals(null, c.uiState.value.error)
      runBlocking { c.send("second") }
      assertEquals(ChatStatus.ERROR, c.uiState.value.status)
      val error = c.uiState.value.error.orEmpty()
      assertTrue("typed overflow expected, got: $error", error.contains(TOOL_ARGS_TOO_LARGE))
      assertEquals("cap overflow must not retry", 2, provider.streamCalls.get())
    } finally {
      c.close()
    }
  }

  @Test fun fewToolsUnderBudgetsAccumulateNormally() {
    val frag = "y".repeat(1_000)
    val provider = AggFakeProvider {
      flow {
        for (t in 0 until 3) repeat(2) { emit(StreamEvent.ToolDelta(t, "id-$t", "n-$t", frag)) }
        for (t in 0 until 3) emit(StreamEvent.ToolDone(t, "id-$t", "n-$t", frag + frag))
        emit(StreamEvent.Done("stop"))
      }
    }
    val sink = AggRecordingSink()
    val c = controller(provider, sink)
    try {
      runBlocking { c.send("hi") }
      var guard = 0
      while (sink.tools.size < 3) {
        if (++guard > 800) fail("timed out waiting for tool records")
        Thread.sleep(10)
      }
      val args = sink.tools.sortedBy { it.toolIndex }.map { it.args }
      assertEquals(listOf(frag + frag, frag + frag, frag + frag), args)
      assertEquals(null, c.uiState.value.error)
    } finally {
      c.close()
    }
  }

  @Test fun textUpdateCostCountersObserveDeltas() {
    val provider = AggFakeProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ab"))
        emit(StreamEvent.TextDelta(0, 0, "cde"))
        emit(StreamEvent.ReasoningDelta(0, 0, "f"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider)
    try {
      runBlocking { c.send("hi") }
      val assistants = c.uiState.value.messages.filter { it.role == "assistant" }
      assertEquals("abcdef", assistants.single().text)
      assertEquals(3, c.streamedTextDeltaCount)
      // #83 hybrid cadence: 3 small deltas stay under the flush cadence, so
      // each still commits immediately (streaming visibility preserved).
      assertEquals(3, c.uiTextUpdateCount)
      // Copies: (0+2) + (2+3) + (5+1).
      assertEquals(13L, c.uiTextCopiedChars)
    } finally {
      c.close()
    }
  }

  @Test fun toolMarkerCountsAsUpdateNotDelta() {
    val provider = AggFakeProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "a"))
        emit(StreamEvent.ToolDone(0, "c1", "n", "{}"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider)
    try {
      runBlocking { c.send("hi") }
      val assistants = c.uiState.value.messages.filter { it.role == "assistant" }
      assertEquals("a\n[tool:n {}]", assistants.single().text)
      assertEquals(1, c.streamedTextDeltaCount)
      // #83 hybrid cadence: small text delta + tool marker commit immediately.
      assertEquals(2, c.uiTextUpdateCount)
    } finally {
      c.close()
    }
  }

  @Test fun unboundedDefaultKeepsFullHistory() {
    val n = AtomicInteger(0)
    val provider = AggFakeProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "r${n.incrementAndGet()}"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider)
    try {
      runBlocking {
        c.send("q1")
        c.send("q2")
        c.send("q3")
      }
      val req = provider.seenRequests.last()
      assertEquals(
        listOf("user", "assistant", "user", "assistant", "user"),
        req.messages.map { it.role },
      )
      assertEquals(0, c.droppedHistoryCount)
    } finally {
      c.close()
    }
  }

  @Test fun maxMessagesCapWindowsRequestAndCountsDrops() {
    val n = AtomicInteger(0)
    val provider = AggFakeProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "r${n.incrementAndGet()}"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider, historyCap = HistoryWindowCap.MaxMessages(2))
    try {
      runBlocking {
        c.send("q1")
        c.send("q2")
        c.send("q3")
      }
      // Settled [u1,a1,u2,a2,u3] windows to [a2,u3] in the request copy.
      val req = provider.seenRequests.last()
      assertEquals(listOf("assistant", "user"), req.messages.map { it.role })
      assertEquals("r2", req.messages[0].text)
      assertTrue(req.messages[1].text.startsWith("q3"))
      assertEquals(3, c.droppedHistoryCount)
      // UI state keeps everything; only the request copy is windowed.
      assertEquals(3, c.uiState.value.messages.count { it.role == "user" })
      assertEquals(3, c.uiState.value.messages.count { it.role == "assistant" })
    } finally {
      c.close()
    }
  }

  @Test fun maxCharsCapDropsWholeMessagesSoToolPairsStayIntact() {
    val toolBlock = "answer\n[tool:lookup {\"q\":\"hi\"}]"
    fun msg(role: String, text: String) = ChatMessage(role = role, text = text)
    val kept = applyHistoryCap(
      listOf(
        msg("user", "drop me, way too long for the budget"),
        msg("assistant", toolBlock),
        msg("user", "tail"),
      ),
      HistoryWindowCap.MaxChars(20),
    )
    // "tail"(4) + toolBlock(30) exceeds 20, so only "tail" survives — but the
    // tool-carrying assistant block is never split, only dropped whole.
    assertEquals(listOf("tail"), kept.map { it.text })

    val fitting = applyHistoryCap(
      listOf(msg("user", "qq"), msg("assistant", toolBlock)),
      HistoryWindowCap.MaxChars(10_000),
    )
    assertEquals(listOf("qq", toolBlock), fitting.map { it.text })
    assertEquals(
      listOf(msg("user", "q1")),
      applyHistoryCap(listOf(msg("user", "q1")), HistoryWindowCap.Unbounded),
    )
  }

  @Test fun maxCharsExactCapKeepsSingleMessageWhole() {
    // Boundary pin for applyHistoryCap's strict `>` break: a single message
    // exactly equal to the cap is kept whole (never split), so nothing drops.
    val provider = AggFakeProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "r1"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider, historyCap = HistoryWindowCap.MaxChars(2))
    try {
      runBlocking { c.send("q1") }
      // Settled ["q1"(2)] exactly equals the cap: kept whole, dropped 0.
      val first = provider.seenRequests.last()
      assertEquals(1, first.messages.size)
      assertTrue(first.messages[0].text.startsWith("q1"))
      assertEquals(0, c.droppedHistoryCount)
      runBlocking { c.send("q2") }
      // Settled ["q1"(2), "r1"(2), "q2"(2)]: newest-first keeps "q2"(2),
      // then 2 + 2 > 2 breaks — only the newest survives, dropped 2.
      val second = provider.seenRequests.last()
      assertEquals(1, second.messages.size)
      assertTrue(second.messages[0].text.startsWith("q2"))
      assertEquals(2, c.droppedHistoryCount)
    } finally {
      c.close()
    }
  }

  @Test fun windowRejectsNonPositiveBudgets() {
    try {
      HistoryWindowCap.MaxMessages(0)
      fail("expected MaxMessages(0) rejection")
    } catch (_: IllegalArgumentException) {
      // expected
    }
    try {
      HistoryWindowCap.MaxChars(0)
      fail("expected MaxChars(0) rejection")
    } catch (_: IllegalArgumentException) {
      // expected
    }
  }

  // ---- Stage C Finding #4/#5: ToolDone guard + first-chunk-wins ----

  @Test fun directOversizedToolDoneFailsTypedWithoutMarkerOrTranscript() {
    val bigArgs = "x".repeat(400_000)
    val provider = AggFakeProvider {
      flow {
        emit(StreamEvent.ToolDone(0, "c1", "f", bigArgs))
        emit(StreamEvent.Done("stop"))
      }
    }
    val sink = AggRecordingSink()
    val c = controller(provider, sink)
    try {
      runBlocking { c.send("hi") }
      assertEquals(ChatStatus.ERROR, c.uiState.value.status)
      val error = c.uiState.value.error.orEmpty()
      assertTrue("typed overflow expected, got: $error", error.contains(TOOL_ARGS_TOO_LARGE))
      assertEquals("poisoned stream must not be retried", 1, provider.streamCalls.get())
      var guard = 0
      while (sink.failed.isEmpty()) {
        if (++guard > 800) fail("timed out waiting for transcript failure")
        Thread.sleep(10)
      }
      assertTrue(
        "transcript must carry the typed reason, got: ${sink.failed.single()}",
        sink.failed.single().contains(TOOL_ARGS_TOO_LARGE),
      )
      Thread.sleep(300)
      assertTrue("oversized ToolDone must leave no transcript record", sink.tools.isEmpty())
      val body = c.uiState.value.messages.filter { it.role == "assistant" }.single().text
      assertFalse("oversized ToolDone must leave no marker, got: ${body.take(80)}", body.contains("[tool:"))
    } finally {
      c.close()
    }
  }

  @Test fun toolDeltaMetadataResendStaysSingleCopy() {
    // N=10000 resends of the same id/name must not throw (first-wins:
    // repeats contribute 0) and the flushed record keeps a single copy.
    val provider = AggFakeProvider {
      flow {
        repeat(10_000) { emit(StreamEvent.ToolDelta(0, "c1", "f", "")) }
        emit(StreamEvent.ToolDelta(0, null, null, "{}"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val sink = AggRecordingSink()
    val c = controller(provider, sink)
    try {
      runBlocking { c.send("hi") }
      assertEquals(ChatStatus.IDLE, c.uiState.value.status)
      var guard = 0
      while (sink.tools.isEmpty()) {
        if (++guard > 800) fail("timed out waiting for tool record")
        Thread.sleep(10)
      }
      val tool = sink.tools.single()
      assertEquals("c1", tool.id)
      assertEquals("f", tool.name)
      assertEquals("{}", tool.args)
    } finally {
      c.close()
    }
  }

  // ---- Stage E: terminal-only ToolDone bills count + round total ----

  @Test fun terminalOnlyToolDoneFloodTripsDistinctCountCap() {
    // 65 terminal-only ToolDones with tiny payloads: per-call (~10 chars)
    // and round total (~650 chars) both stay far under 1 MiB, so ONLY the
    // 64-distinct cap can trip. (NOTE: the Stage E brief's literal "x20k"
    // for this case would trip the round total first at ~17 tools instead
    // of the count cap, so small args are used here to isolate the count
    // scope; the 20k-args total isolation is covered by the next test.)
    val provider = AggFakeProvider {
      flow {
        for (i in 0 until MAX_TOOL_CALLS_PER_ATTEMPT + 1) {
          emit(StreamEvent.ToolDone(i, "id-$i", "n-$i", "{}"))
        }
        emit(StreamEvent.Done("stop"))
      }
    }
    val sink = AggRecordingSink()
    val c = controller(provider, sink)
    try {
      runBlocking { c.send("hi") }
      assertEquals(ChatStatus.ERROR, c.uiState.value.status)
      val error = c.uiState.value.error.orEmpty()
      assertTrue("typed overflow expected, got: $error", error.contains(TOOL_ARGS_TOO_LARGE))
      assertEquals("poisoned stream must not be retried", 1, provider.streamCalls.get())
      var guard = 0
      while (sink.failed.isEmpty()) {
        if (++guard > 800) fail("timed out waiting for transcript failure")
        Thread.sleep(10)
      }
      assertEquals(1, sink.failed.size)
      assertTrue(
        "transcript must carry the typed reason, got: ${sink.failed.single()}",
        sink.failed.single().contains(TOOL_ARGS_TOO_LARGE),
      )
      Thread.sleep(500)
      assertEquals(
        "first 64 terminal-only ToolDones stay, got indexes: ${sink.tools.map { it.toolIndex }}",
        MAX_TOOL_CALLS_PER_ATTEMPT,
        sink.tools.size,
      )
      assertEquals(
        (0 until MAX_TOOL_CALLS_PER_ATTEMPT).toSet(),
        sink.tools.map { it.toolIndex }.toSet(),
      )
      assertTrue("tripping idx64 must leave no record", sink.tools.none { it.toolIndex == 64 })
      val body = c.uiState.value.messages.filter { it.role == "assistant" }.single().text
      assertTrue("first ToolDone marker stays, got: ${body.take(80)}", body.contains("[tool:n-0 "))
      assertFalse("tripping idx64 must leave no marker, got tail: ${body.takeLast(120)}", body.contains("[tool:n-64 "))
    } finally {
      c.close()
    }
  }

  @Test fun terminalOnlyToolDonesTripRoundTotal() {
    // 20 terminal-only ToolDones x 20k args: per-call (20k*3=60k) passes and
    // count (20<64) passes, but the round total (400k chars*3=1.2M>1MiB)
    // trips. Growth bills at ~20007-20009 chars each, so the 18th ToolDone
    // (index 17) is the first to overflow: 17 retained (0..16).
    val bigArgs = "x".repeat(20_000)
    val provider = AggFakeProvider {
      flow {
        for (i in 0 until 20) emit(StreamEvent.ToolDone(i, "id-$i", "n-$i", bigArgs))
        emit(StreamEvent.Done("stop"))
      }
    }
    val sink = AggRecordingSink()
    val c = controller(provider, sink)
    try {
      runBlocking { c.send("hi") }
      assertEquals(ChatStatus.ERROR, c.uiState.value.status)
      val error = c.uiState.value.error.orEmpty()
      assertTrue("typed overflow expected, got: $error", error.contains(TOOL_ARGS_TOO_LARGE))
      assertEquals("poisoned stream must not be retried", 1, provider.streamCalls.get())
      var guard = 0
      while (sink.failed.isEmpty()) {
        if (++guard > 800) fail("timed out waiting for transcript failure")
        Thread.sleep(10)
      }
      assertEquals(1, sink.failed.size)
      assertTrue(
        "transcript must carry the typed reason, got: ${sink.failed.single()}",
        sink.failed.single().contains(TOOL_ARGS_TOO_LARGE),
      )
      Thread.sleep(500)
      assertEquals(
        "only pre-overflow ToolDones survive, got ${sink.tools.size}: ${sink.tools.map { it.toolIndex }}",
        17,
        sink.tools.size,
      )
      assertEquals((0 until 17).toSet(), sink.tools.map { it.toolIndex }.toSet())
      assertTrue("tripping idx17 must leave no record", sink.tools.none { it.toolIndex == 17 })
      val body = c.uiState.value.messages.filter { it.role == "assistant" }.single().text
      assertTrue("first ToolDone marker stays, got: ${body.take(80)}", body.contains("[tool:n-0 "))
      assertFalse("tripping idx17 must leave no marker", body.contains("[tool:n-17 "))
    } finally {
      c.close()
    }
  }

  @Test fun fewTerminalOnlyToolDonesUnderBudgetsSucceed() {
    val smallArgs = "y".repeat(1_000)
    val provider = AggFakeProvider {
      flow {
        for (i in 0 until 3) emit(StreamEvent.ToolDone(i, "id-$i", "n-$i", smallArgs))
        emit(StreamEvent.Done("stop"))
      }
    }
    val sink = AggRecordingSink()
    val c = controller(provider, sink)
    try {
      runBlocking { c.send("hi") }
      assertEquals(ChatStatus.IDLE, c.uiState.value.status)
      assertEquals(null, c.uiState.value.error)
      var guard = 0
      while (sink.tools.size < 3) {
        if (++guard > 800) fail("timed out waiting for tool records, got ${sink.tools.size}")
        Thread.sleep(10)
      }
      Thread.sleep(300)
      assertEquals(3, sink.tools.size)
      assertEquals((0 until 3).toSet(), sink.tools.map { it.toolIndex }.toSet())
      assertEquals(listOf(smallArgs, smallArgs, smallArgs), sink.tools.sortedBy { it.toolIndex }.map { it.args })
      val body = c.uiState.value.messages.filter { it.role == "assistant" }.single().text
      for (i in 0 until 3) assertTrue("marker idx$i stays, got: ${body.take(120)}", body.contains("[tool:n-$i "))
    } finally {
      c.close()
    }
  }
}
