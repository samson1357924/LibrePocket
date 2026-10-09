package dev.librepocket.chat

import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol as ProviderProto
import dev.librepocket.provider.StreamEvent
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
import org.junit.Assert.fail
import org.junit.Test

private class CancelAttrProvider(
  var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
  override val protocol: ProviderProto = ProviderProto.CHAT_COMPLETIONS
  val streamCalls = AtomicInteger(0)
  override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
    streamCalls.incrementAndGet()
    emitAll(handler(request))
  }
  override suspend fun listModels(): List<String> = emptyList()
}

private class CancelAttrPolicy : PolicyStore {
  override fun evaluate(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
  override suspend fun setRule(rule: PolicyRule) = Unit
  override suspend fun removeRule(pattern: String) = Unit
  override suspend fun listRules(): List<PolicyRule> = emptyList()
  override suspend fun evaluateFresh(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

private class CancelAttrSink : TranscriptSink {
  val started: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
  val succeeded: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
  val cancelled: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
  val failed: MutableList<Triple<String, String, String>> = Collections.synchronizedList(mutableListOf())
  var startedGate: (suspend (String) -> Unit)? = null
  var failSucceededWith: IOException? = null
  override suspend fun onTurnStarted(runId: String, text: String) {
    startedGate?.invoke(text)
    started.add(runId to text)
  }
  override suspend fun onTurnSucceeded(runId: String, text: String) {
    failSucceededWith?.let { throw it }
    succeeded.add(runId to text)
  }
  override suspend fun onTurnFailed(runId: String, error: String) = Unit
  override suspend fun onTurnFailed(runId: String, partialText: String, error: String) {
    failed.add(Triple(runId, partialText, error))
  }
  override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
  override suspend fun onTurnCancelled(runId: String, partialText: String) {
    cancelled.add(runId to partialText)
  }
  override suspend fun onSteerQueued(text: String) = Unit
  override suspend fun onToolDone(runId: String, toolIndex: Int, id: String, name: String, argumentsJson: String) = Unit
  override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) = Unit
}

/**
 * Stage B: cancel attribution must use the live turn's logical/attempt id,
 * never the previous turn's assistant and never an empty runId.
 */
class TurnControllerCancelAttributionTest {

  private fun controller(
    provider: CancelAttrProvider,
    sink: TranscriptSink,
    sleeper: suspend (Long) -> Unit = {},
    retry: TurnRetryConfig = TurnRetryConfig(),
  ): TurnController {
    var n = 0
    return TurnController(
      provider = provider,
      policy = CancelAttrPolicy(),
      transcript = sink,
      retryConfig = retry,
      dispatcher = Dispatchers.Default,
      sleeper = sleeper,
      newId = { "cancel-${n++}" },
    )
  }

  private fun awaitTrue(timeoutMs: Long = 8000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!cond()) {
      if (System.currentTimeMillis() > end) fail("timed out waiting for condition")
      Thread.sleep(10)
    }
  }

  @Test fun cancelDuringStartedAckUsesCurrentTurnIdNotPrevious() {
    val sink = CancelAttrSink()
    val enteredQ2 = CountDownLatch(1)
    val releaseQ2 = CompletableDeferred<Unit>()
    sink.startedGate = { text ->
      if (text == "Q2") {
        enteredQ2.countDown()
        releaseQ2.await()
      }
    }
    val provider = CancelAttrProvider { request ->
      flow {
        val lastUser = request.messages.lastOrNull { it.role == "user" }?.text.orEmpty()
        if (lastUser.contains("Q1")) {
          emit(StreamEvent.TextDelta(0, 0, "A1"))
          emit(StreamEvent.Done("stop"))
        } else {
          emit(StreamEvent.TextDelta(0, 0, "A2"))
          emit(StreamEvent.Done("stop"))
        }
      }
    }
    val c = controller(provider, sink)
    try {
      runBlocking { c.send("Q1") }
      assertEquals(1, sink.started.size)
      val prevId = sink.started.single().first
      assertTrue(prevId.isNotEmpty())

      val admission = runBlocking { c.startOrEnqueue("Q2") }
      assertTrue(admission is TurnStart.Started)
      assertTrue("Q2 did not park on started ack", enteredQ2.await(5, TimeUnit.SECONDS))
      // Placeholder for Q2 must not exist yet: latest assistant is still A1.
      // A buggy latest*-based cancel would reuse A1's id here.
      c.cancel()
      assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
      releaseQ2.complete(Unit)
      runBlocking { withTimeout(5_000) { (admission as TurnStart.Started).host.join() } }
      runBlocking { withTimeout(5_000) { c.flushTranscript() } }

      assertEquals(2, sink.started.size)
      val q2Id = sink.started[1].first
      assertEquals("Q2", sink.started[1].second)
      assertTrue("current turn id must be non-empty", q2Id.isNotEmpty())
      assertTrue("cancel must not reuse previous turn id", q2Id != prevId)
      assertEquals("exactly one cancel record", 1, sink.cancelled.size)
      assertEquals("cancel must carry current turn id", q2Id, sink.cancelled.single().first)
      // Pre-placeholder partial is "" with this turn's id; never A1's text.
      assertEquals("", sink.cancelled.single().second)
      assertFalse("cancel text must not duplicate previous answer", sink.cancelled.single().second == "A1")
      assertTrue("no success for cancelled turn", sink.succeeded.isEmpty() || sink.succeeded.none { it.first == q2Id })
    } finally {
      releaseQ2.complete(Unit)
      c.close()
    }
  }

  @Test fun firstTurnCancelDuringStartedAckHasNonEmptyRunId() {
    val sink = CancelAttrSink()
    val entered = CountDownLatch(1)
    val release = CompletableDeferred<Unit>()
    sink.startedGate = {
      entered.countDown()
      release.await()
    }
    val provider = CancelAttrProvider {
      flow { emit(StreamEvent.Done("stop")) }
    }
    val c = controller(provider, sink)
    try {
      val admission = runBlocking { c.startOrEnqueue("Q1") }
      assertTrue(admission is TurnStart.Started)
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      c.cancel()
      release.complete(Unit)
      runBlocking { withTimeout(5_000) { (admission as TurnStart.Started).host.join() } }
      runBlocking { withTimeout(5_000) { c.flushTranscript() } }
      // User-row semantics: the cancel terminal still uses this turn's id
      // (ordered behind the started entry); empty runId is forbidden.
      assertEquals(1, sink.started.size)
      val logicalId = sink.started.single().first
      assertTrue(logicalId.isNotEmpty())
      assertEquals(1, sink.cancelled.size)
      assertEquals(logicalId, sink.cancelled.single().first)
      assertTrue(sink.cancelled.single().first.isNotEmpty())
      assertEquals("", sink.cancelled.single().second)
    } finally {
      release.complete(Unit)
      c.close()
    }
  }

  @Test fun cancelInRetrySleeperGapUsesFailedAttemptId() {
    val sink = CancelAttrSink()
    val calls = AtomicInteger(0)
    val provider = CancelAttrProvider {
      flow {
        if (calls.incrementAndGet() == 1) {
          emit(StreamEvent.TextDelta(0, 0, "OLD-PARTIAL"))
          emit(StreamEvent.Failed("boom", retryable = true))
        } else {
          emit(StreamEvent.TextDelta(0, 0, "NEW"))
          emit(StreamEvent.Done("stop"))
        }
      }
    }
    val sleeperEntered = CountDownLatch(1)
    val sleeperGate = CompletableDeferred<Unit>()
    val c = controller(
      provider,
      sink,
      sleeper = {
        sleeperEntered.countDown()
        sleeperGate.await()
      },
      retry = TurnRetryConfig(maxRetries = 1, retryDelaysMs = listOf(5_000L)),
    )
    try {
      val admission = runBlocking { c.startOrEnqueue("Q1") }
      assertTrue(admission is TurnStart.Started)
      assertTrue("retry sleeper was not entered", sleeperEntered.await(5, TimeUnit.SECONDS))
      // The failed attempt's partial must be live before cancelling the gap.
      awaitTrue { c.uiState.value.messages.any { it.role == "assistant" && it.text == "OLD-PARTIAL" } }
      val failedAttemptId =
        c.uiState.value.messages.first { it.role == "assistant" && it.text == "OLD-PARTIAL" }.id
      c.cancel()
      // Unblock the sleeper so the host can observe the cancellation.
      sleeperGate.complete(Unit)
      runBlocking { withTimeout(5_000) { (admission as TurnStart.Started).host.join() } }
      runBlocking { withTimeout(5_000) { c.flushTranscript() } }
      assertEquals(1, sink.cancelled.size)
      assertEquals("sleeper-gap cancel must keep the just-failed attempt id", failedAttemptId, sink.cancelled.single().first)
      assertEquals("OLD-PARTIAL", sink.cancelled.single().second)
      assertEquals(1, provider.streamCalls.get())
    } finally {
      sleeperGate.complete(Unit)
      c.close()
    }
  }

  @Test fun storeFailureOnSucceededProjectsErrorInsteadOfStickingStreaming() {
    val sink = CancelAttrSink()
    sink.failSucceededWith = IOException("disk full")
    val provider = CancelAttrProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider, sink)
    try {
      runBlocking { withTimeout(5_000) { c.send("hi") } }
      assertEquals(ChatStatus.ERROR, c.uiState.value.status)
      assertTrue(c.uiState.value.error?.isNotEmpty() == true)
      assertTrue("store failure must be INTERRUPTED-marked", c.interruptedTranscriptRunIds().isNotEmpty())
    } finally {
      c.close()
    }
  }
}
