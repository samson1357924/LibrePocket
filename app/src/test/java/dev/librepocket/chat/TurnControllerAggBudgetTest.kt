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
  override suspend fun onTurnSucceeded(runId: String, text: String) = Unit
  override suspend fun onTurnFailed(runId: String, error: String) { failed.add(error) }
  override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
  override suspend fun onTurnCancelled(runId: String, partialText: String) = Unit
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
      // Downgraded batching: every delta still performs exactly one update
      // (streaming visibility + cancel snapshots depend on it).
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
}
