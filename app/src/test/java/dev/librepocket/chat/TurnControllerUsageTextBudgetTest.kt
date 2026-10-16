package dev.librepocket.chat

import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol as ProviderProto
import dev.librepocket.provider.StreamEvent
import dev.librepocket.provider.TEXT_TOO_LARGE
import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.SessionTranscriptSink
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class UbtFakeProvider(
  override val protocol: ProviderProto = ProviderProto.CHAT_COMPLETIONS,
  var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
  val seenRequests: MutableList<ChatRequest> = Collections.synchronizedList(mutableListOf())
  val streamCalls = AtomicInteger(0)

  override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
    streamCalls.incrementAndGet()
    seenRequests.add(request)
    emitAll(handler(request))
  }

  override suspend fun listModels(): List<String> = emptyList()
}

private class UbtAllowPolicy : PolicyStore {
  override fun evaluate(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
  override suspend fun setRule(rule: PolicyRule) = Unit
  override suspend fun removeRule(pattern: String) = Unit
  override suspend fun listRules(): List<PolicyRule> = emptyList()
  override suspend fun evaluateFresh(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

private class UbtRecordingSink : TranscriptSink {
  data class Usage(val runId: String, val inputTokens: Int?, val outputTokens: Int?)
  val usages: MutableList<Usage> = Collections.synchronizedList(mutableListOf())
  data class Failed(
    val runId: String,
    val partialText: String,
    val error: String,
    val parentRunId: String?,
    val attemptIndex: Int?,
    val isFinal: Boolean,
    val hasReasoning: Boolean,
  )
  val faileds: MutableList<Failed> = Collections.synchronizedList(mutableListOf())
  data class Cancelled(val runId: String, val partialText: String)
  val cancelled: MutableList<Cancelled> = Collections.synchronizedList(mutableListOf())

  override suspend fun onTurnStarted(runId: String, text: String) = Unit
  override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) = Unit
  override suspend fun onTurnSucceeded(
    runId: String,
    text: String,
    parentRunId: String?,
    attemptIndex: Int?,
    hasReasoning: Boolean,
  ) = Unit
  override suspend fun onTurnFailed(runId: String, error: String) = Unit
  override suspend fun onTurnFailed(runId: String, partialText: String, error: String) = Unit
  override suspend fun onTurnFailed(
    runId: String,
    partialText: String,
    error: String,
    parentRunId: String?,
    attemptIndex: Int?,
  ) {
    faileds.add(Failed(runId, partialText, error, parentRunId, attemptIndex, true, false))
  }
  override suspend fun onTurnFailed(
    runId: String,
    partialText: String,
    error: String,
    parentRunId: String?,
    attemptIndex: Int?,
    isFinal: Boolean,
  ) {
    faileds.add(Failed(runId, partialText, error, parentRunId, attemptIndex, isFinal, false))
  }
  override suspend fun onTurnFailed(
    runId: String,
    partialText: String,
    error: String,
    parentRunId: String?,
    attemptIndex: Int?,
    isFinal: Boolean,
    hasReasoning: Boolean,
  ) {
    faileds.add(Failed(runId, partialText, error, parentRunId, attemptIndex, isFinal, hasReasoning))
  }
  override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
  override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) {
    cancelled.add(Cancelled(runId, partialText))
  }
  override suspend fun onTurnCancelled(
    runId: String,
    partialText: String,
    parentRunId: String?,
    attemptIndex: Int?,
    hasReasoning: Boolean,
  ) {
    cancelled.add(Cancelled(runId, partialText))
  }
  override suspend fun onSteerQueued(text: String) = Unit
  override suspend fun onToolDone(runId: String, toolIndex: Int, id: String, name: String, argumentsJson: String) = Unit
  override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) {
    usages.add(Usage(runId, inputTokens, outputTokens))
  }
}

/**
 * #84 (authoritative per-attempt usage) + #83 (visible-text budget +
 * incremental UI update) regressions for [TurnController], with fake data and
 * an injectable (no-op) sleeper — no network, no wall-clock waits.
 */
class TurnControllerUsageTextBudgetTest {

  private var n = 0

  private fun controller(
    provider: UbtFakeProvider,
    transcript: TranscriptSink = NoOpTranscriptSink(),
    retry: TurnRetryConfig = TurnRetryConfig(),
  ): TurnController {
    n = 0
    return TurnController(
      provider = provider,
      policy = UbtAllowPolicy(),
      transcript = transcript,
      retryConfig = retry,
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "ubt-${n++}" },
    )
  }

  @Test fun anthropicDualUsageMergesToSingleAuthoritativeNotice() {
    val provider = UbtFakeProvider {
      flow {
        emit(StreamEvent.Usage(10, null))
        emit(StreamEvent.Usage(10, 20))
        emit(StreamEvent.TextDelta(0, 0, "hi"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val sink = UbtRecordingSink()
    val c = controller(provider, sink)
    try {
      runBlocking { c.send("hello") }
      assertEquals(ChatStatus.IDLE, c.uiState.value.status)
      assertEquals("one attempt owns one usage notice", 1, sink.usages.size)
      assertEquals(10, sink.usages.single().inputTokens)
      assertEquals(20, sink.usages.single().outputTokens)
      assertEquals(StreamEvent.Usage(10, 20), c.lastUsage)
    } finally {
      c.close()
    }
  }

  @Test fun usageFlood100StillSingleAuthoritativeNotice() {
    val provider = UbtFakeProvider {
      flow {
        repeat(100) { i -> emit(StreamEvent.Usage(i, i * 2)) }
        emit(StreamEvent.TextDelta(0, 0, "hi"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val sink = UbtRecordingSink()
    val c = controller(provider, sink)
    try {
      runBlocking { c.send("hello") }
      assertEquals(ChatStatus.IDLE, c.uiState.value.status)
      assertEquals("100-event flood still owns one usage notice", 1, sink.usages.size)
      assertEquals(99, sink.usages.single().inputTokens)
      assertEquals(198, sink.usages.single().outputTokens)
      assertEquals(StreamEvent.Usage(99, 198), c.lastUsage)
    } finally {
      c.close()
    }
  }

  @Test fun usageIsolatedPerAttemptAcrossRetry() {
    val calls = AtomicInteger(0)
    val provider = UbtFakeProvider {
      flow {
        if (calls.getAndIncrement() == 0) {
          emit(StreamEvent.Usage(1, 1))
          emit(StreamEvent.TextDelta(0, 0, "part"))
          emit(StreamEvent.Failed("boom", retryable = true))
        } else {
          emit(StreamEvent.Usage(2, 2))
          emit(StreamEvent.TextDelta(0, 0, "ok"))
          emit(StreamEvent.Done("stop"))
        }
      }
    }
    val sink = UbtRecordingSink()
    val c = controller(provider, sink)
    try {
      runBlocking { c.send("hello") }
      assertEquals(ChatStatus.IDLE, c.uiState.value.status)
      assertEquals("each attempt owns its own usage notice", 2, sink.usages.size)
      assertEquals(2, sink.usages.map { it.runId }.toSet().size)
      assertEquals(setOf(Pair(1, 1), Pair(2, 2)), sink.usages.map { it.inputTokens to it.outputTokens }.toSet())
      assertEquals(StreamEvent.Usage(2, 2), c.lastUsage)
    } finally {
      c.close()
    }
  }

  @Test fun dualUsagePersistsSingleRowWithFinalValuesInStore() {
    val store = FakeSessionStore()
    val sid = runBlocking { store.createSession("hi", "m") }
    val provider = UbtFakeProvider {
      flow {
        // Anthropic shape: input-only first, full values on the delta.
        emit(StreamEvent.Usage(10, null))
        emit(StreamEvent.Usage(10, 20))
        emit(StreamEvent.TextDelta(0, 0, "hi"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider, SessionTranscriptSink(store, sid))
    try {
      runBlocking { c.send("hello") }
      val usageRows = store.events.filter { it.kind == "system" && it.text.startsWith("usage ") }
      assertEquals("Anthropic normal turn persists exactly one usage row", 1, usageRows.size)
      assertEquals("usage input=10 output=20", usageRows.single().text)
    } finally {
      c.close()
    }
  }

  @Test fun usageFlood100PersistsSingleRowInStore() {
    val store = FakeSessionStore()
    val sid = runBlocking { store.createSession("hi", "m") }
    val provider = UbtFakeProvider {
      flow {
        repeat(100) { i -> emit(StreamEvent.Usage(i, i * 2)) }
        emit(StreamEvent.TextDelta(0, 0, "hi"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider, SessionTranscriptSink(store, sid))
    try {
      runBlocking { c.send("hello") }
      val usageRows = store.events.filter { it.kind == "system" && it.text.startsWith("usage ") }
      assertEquals("100-event flood still persists one usage row", 1, usageRows.size)
      assertEquals("usage input=99 output=198", usageRows.single().text)
    } finally {
      c.close()
    }
  }

  @Test fun textBudgetOverflowIsTypedNonRetryable() {
    val provider = UbtFakeProvider {
      flow {
        repeat(120) { emit(StreamEvent.TextDelta(0, 0, "y".repeat(1_000))) }
        emit(StreamEvent.Done("stop"))
      }
    }
    val sink = UbtRecordingSink()
    val c = controller(provider, sink)
    try {
      runBlocking { c.send("hi") }
      assertEquals(ChatStatus.ERROR, c.uiState.value.status)
      val error = c.uiState.value.error.orEmpty()
      assertTrue("typed overflow expected, got: $error", error.contains(TEXT_TOO_LARGE))
      assertEquals("poisoned stream must not be retried", 1, provider.streamCalls.get())
      assertEquals(1, sink.faileds.size)
      assertTrue(
        "transcript must carry the typed reason, got: ${sink.faileds.single().error}",
        sink.faileds.single().error.contains(TEXT_TOO_LARGE),
      )
      assertTrue("overflow terminal is final", sink.faileds.single().isFinal)
      // No silent truncation: the accepted fragment stays partial.
      val assistants = c.uiState.value.messages.filter { it.role == "assistant" }
      assertEquals(1, assistants.size)
      assertTrue("failed attempt keeps its fragment partial", assistants.single().isPartial)
      assertEquals("accepted prefix kept, tripping delta rejected", 100_000, assistants.single().text.length)
    } finally {
      c.close()
    }
  }

  @Test fun reasoningBudgetOverflowIsTypedNonRetryable() {
    val provider = UbtFakeProvider {
      flow {
        repeat(120) { emit(StreamEvent.ReasoningDelta(0, 0, "r".repeat(1_000))) }
        emit(StreamEvent.Done("stop"))
      }
    }
    val sink = UbtRecordingSink()
    val c = controller(provider, sink)
    try {
      runBlocking { c.send("hi") }
      assertEquals(ChatStatus.ERROR, c.uiState.value.status)
      assertTrue(
        "typed overflow expected, got: ${c.uiState.value.error}",
        c.uiState.value.error.orEmpty().contains(TEXT_TOO_LARGE),
      )
      assertEquals("poisoned stream must not be retried", 1, provider.streamCalls.get())
      assertEquals(1, sink.faileds.size)
      assertTrue("reasoning flag survives the overflow terminal", sink.faileds.single().hasReasoning)
      val assistants = c.uiState.value.messages.filter { it.role == "assistant" }
      assertEquals(1, assistants.size)
      assertTrue(assistants.single().isPartial)
      assertTrue(assistants.single().hasReasoning)
      assertEquals(100_000, assistants.single().text.length)
    } finally {
      c.close()
    }
  }

  @Test fun threeProtocolsShareTextBudgetSemantics() {
    // Negative control: the budget lives in the controller collector, so all
    // three wire protocols trip the same typed non-retryable terminal.
    for (protocol in listOf(
      ProviderProto.CHAT_COMPLETIONS,
      ProviderProto.RESPONSES,
      ProviderProto.ANTHROPIC,
    )) {
      val provider = UbtFakeProvider(
        handler = {
          flow {
            repeat(120) { emit(StreamEvent.TextDelta(0, 0, "z".repeat(1_000))) }
            emit(StreamEvent.Done("stop"))
          }
        },
        protocol = protocol,
      )
      val c = controller(provider)
      try {
        runBlocking { c.send("hi") }
        assertEquals("$protocol must fail closed", ChatStatus.ERROR, c.uiState.value.status)
        assertTrue(
          "$protocol must report typed overflow, got: ${c.uiState.value.error}",
          c.uiState.value.error.orEmpty().contains(TEXT_TOO_LARGE),
        )
        assertEquals("$protocol must not retry the poisoned stream", 1, provider.streamCalls.get())
      } finally {
        c.close()
      }
    }
  }

  @Test fun threeThousandDeltasCopyDropsOrdersOfMagnitude() {
    val provider = UbtFakeProvider {
      flow {
        repeat(3_000) { emit(StreamEvent.TextDelta(0, 0, "x")) }
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider)
    try {
      runBlocking { c.send("hi") }
      val assistants = c.uiState.value.messages.filter { it.role == "assistant" }
      assertEquals("x".repeat(3_000), assistants.single().text)
      assertEquals(3_000, c.streamedTextDeltaCount)
      // Baseline without batching: sum(1..3000) = 4_501_500 copied chars over
      // 3000 updates. Batched flushes must cut at least an order of magnitude
      // while keeping the final text exact.
      assertTrue(
        "copied=${c.uiTextCopiedChars} must drop an order of magnitude below 4501500",
        c.uiTextCopiedChars < 500_000,
      )
      assertTrue(
        "updates=${c.uiTextUpdateCount} must batch well below 3000",
        c.uiTextUpdateCount < 200,
      )
      assertTrue("streaming still flushes before the terminal", c.uiTextUpdateCount > 1)
    } finally {
      c.close()
    }
  }

  @Test fun usageFloodPast128FreezesAtCapInController() {
    // Stage1 follow-up: controller-level >128 freeze (sink already pins 130
    // at TurnControllerUsageTextBudgetTest-adjacent sink suite). Past
    // MAX_USAGE_EVENTS_PER_ATTEMPT the merge freezes, never a failure, still
    // exactly one authoritative notice.
    val provider = UbtFakeProvider {
      flow {
        repeat(200) { i -> emit(StreamEvent.Usage(i, i * 2)) }
        emit(StreamEvent.TextDelta(0, 0, "hi"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val sink = UbtRecordingSink()
    val c = controller(provider, sink)
    try {
      runBlocking { c.send("hello") }
      assertEquals(ChatStatus.IDLE, c.uiState.value.status)
      assertEquals("200-event flood still owns one usage notice", 1, sink.usages.size)
      assertEquals(127, sink.usages.single().inputTokens)
      assertEquals(254, sink.usages.single().outputTokens)
      assertEquals(StreamEvent.Usage(127, 254), c.lastUsage)
    } finally {
      c.close()
    }
  }

  @Test fun textBudgetResetsAcrossRetryableRetry() {
    // Stage1 follow-up: visible-text budget is per-attempt isolated. Two
    // 60k attempts total 120k across the turn but each <100k, so the retry
    // must succeed (a global budget would trip TEXT_TOO_LARGE on the second).
    val calls = AtomicInteger(0)
    val provider = UbtFakeProvider {
      flow {
        if (calls.getAndIncrement() == 0) {
          repeat(60) { emit(StreamEvent.TextDelta(0, 0, "a".repeat(1_000))) }
          emit(StreamEvent.Failed("boom", retryable = true))
        } else {
          repeat(60) { emit(StreamEvent.TextDelta(0, 0, "b".repeat(1_000))) }
          emit(StreamEvent.Done("stop"))
        }
      }
    }
    val c = controller(provider)
    try {
      runBlocking { c.send("hi") }
      assertEquals(ChatStatus.IDLE, c.uiState.value.status)
      assertEquals("retryable poison must retry once", 2, provider.streamCalls.get())
      val assistants = c.uiState.value.messages.filter { it.role == "assistant" }
      assertEquals("one block per attempt", 2, assistants.size)
      assertEquals(60_000, assistants[0].text.length)
      assertEquals(60_000, assistants[1].text.length)
      assertTrue("first attempt stays partial", assistants[0].isPartial)
      assertFalse("retried attempt succeeds whole", assistants[1].isPartial)
      assertEquals("b".repeat(60_000), assistants[1].text)
    } finally {
      c.close()
    }
  }

  @Test fun largeBufferedCancelKeepsExactSnapshot() {
    // Stage1 follow-up: cancel mid-stream with a large buffered (partially
    // unflushed) block must flush before snapshotting, so UI + transcript
    // stay exact. 100x100 chars = 10k exceeds the 4k/32 cadence, so an
    // unflushed tail is guaranteed; batched commits stay well below 100.
    val chunk = "x".repeat(100)
    val deltas = 100
    val provider = UbtFakeProvider {
      flow {
        repeat(deltas) { emit(StreamEvent.TextDelta(0, 0, chunk)) }
        awaitCancellation()
      }
    }
    val sink = UbtRecordingSink()
    val c = controller(provider, sink)
    val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
    try {
      val job = outer.async { c.send("hi") }
      runBlocking { withTimeout(5_000) { while (c.streamedTextDeltaCount < deltas) delay(1) } }
      assertTrue(
        "100 deltas must batch well below 100 commits, got ${c.uiTextUpdateCount}",
        c.uiTextUpdateCount < deltas,
      )
      c.cancel()
      runBlocking { withTimeout(5_000) { job.join() } }
      assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
      val assistants = c.uiState.value.messages.filter { it.role == "assistant" }
      assertEquals(1, assistants.size)
      assertEquals("cancel snapshot must flush the buffered tail exact", 10_000, assistants.single().text.length)
      assertEquals("x".repeat(10_000), assistants.single().text)
      assertTrue("cancel keeps partial", assistants.single().isPartial)
      assertEquals("transcript cancel carries the exact partial", 1, sink.cancelled.size)
      assertEquals(10_000, sink.cancelled.single().partialText.length)
    } finally {
      outer.cancel()
      c.close()
    }
  }
}
