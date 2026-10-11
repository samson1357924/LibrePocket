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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
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
 *
 * Stage 6 (R4-3): admission (user row) is durable before Started, so the
 * host-park tests below cover post-accept cancellation, while the
 * admission-window tests cover a cancel parked on the durable await itself
 * (Finding 1: accepted-then-cancelled, no ghost launch).
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

  @Test fun cancelDuringHostParkUsesCurrentTurnIdNotPrevious() {
    val sink = CancelAttrSink()
    // Stage 6: admission (user row) is durable before Started, so the host —
    // not the admission — is the cancellable park point. Park the provider
    // (after the placeholder, before any TextDelta) instead of the transcript
    // ack; attribution must still use the live turn's id, never the previous.
    val enteredQ2 = CountDownLatch(1)
    val releaseQ2 = CompletableDeferred<Unit>()
    val provider = CancelAttrProvider { request ->
      flow {
        val lastUser = request.messages.lastOrNull { it.role == "user" }?.text.orEmpty()
        if (lastUser.contains("Q1")) {
          emit(StreamEvent.TextDelta(0, 0, "A1"))
          emit(StreamEvent.Done("stop"))
        } else {
          enteredQ2.countDown()
          releaseQ2.await()
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
      assertTrue("Q2 host did not park on provider", enteredQ2.await(5, TimeUnit.SECONDS))
      // Host already appended the placeholder (empty) and parks before any
      // TextDelta; a buggy latest*-based cancel would still reuse A1's id.
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
      // Pre-delta partial is "" with this turn's id; never A1's text.
      assertEquals("", sink.cancelled.single().second)
      assertFalse("cancel text must not duplicate previous answer", sink.cancelled.single().second == "A1")
      assertTrue("no success for cancelled turn", sink.succeeded.isEmpty() || sink.succeeded.none { it.first == q2Id })
    } finally {
      releaseQ2.complete(Unit)
      c.close()
    }
  }

  @Test fun firstTurnCancelDuringHostParkHasNonEmptyRunId() {
    val sink = CancelAttrSink()
    // Stage 6: same provider-park as above (admission is already durable).
    val entered = CountDownLatch(1)
    val release = CompletableDeferred<Unit>()
    val provider = CancelAttrProvider {
      flow {
        entered.countDown()
        release.await()
        emit(StreamEvent.Done("stop"))
      }
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

  /**
   * Finding 1 regression: a cancel parked on the admission-time durable await
   * must not ghost-launch. Semantics locked here (accepted-then-cancelled):
   * the user row stays durable, a cancel terminal is drained (no second user
   * row), the provider is never called, the UI keeps the user row with
   * CANCELLED, and the caller gets Started with an already-cancelled host.
   */
  @Test fun cancelDuringAdmissionDurableWindowDoesNotGhostLaunch() {
    val sink = CancelAttrSink()
    val entered = CountDownLatch(1)
    val release = CompletableDeferred<Unit>()
    sink.startedGate = { text ->
      if (text == "Q-window") {
        entered.countDown()
        release.await()
      }
    }
    val provider = CancelAttrProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "SHOULD-NEVER-STREAM"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider, sink)
    try {
      runBlocking {
        val deferred = async(Dispatchers.Default) { c.startOrEnqueue("Q-window") }
        assertTrue("admission did not park on durable await", entered.await(5, TimeUnit.SECONDS))
        // Window: pendingAdmission set, no host yet. Cancel must be effective.
        c.cancel()
        assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
        release.complete(Unit)
        val admission = withTimeout(5_000) { deferred.await() }
        assertTrue("window-cancel must still report Started (accepted-then-cancelled)", admission is TurnStart.Started)
        val host = (admission as TurnStart.Started).host
        assertTrue("window-cancel host must already be cancelled", host.isCancelled || host.isCompleted)
        try {
          withTimeout(5_000) { host.join() }
        } catch (_: CancellationException) {
          // Expected: the cancelled host joins with cancellation.
        }
        withTimeout(5_000) { c.flushTranscript() }
      }
      assertEquals("ghost launch: provider must never be called", 0, provider.streamCalls.get())
      assertEquals("exactly one durable user row", 1, sink.started.size)
      assertEquals("Q-window", sink.started.single().second)
      assertEquals("exactly one cancel terminal for the window turn", 1, sink.cancelled.size)
      assertEquals("cancel must carry the window turn id", sink.started.single().first, sink.cancelled.single().first)
      assertTrue(
        "UI must keep the cancelled user row",
        c.uiState.value.messages.any { it.role == "user" && it.text == "Q-window" },
      )
      assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
    } finally {
      release.complete(Unit)
      c.close()
    }
  }

  /**
   * Finding 1 via [TurnController.startTurn]: same accepted-then-cancelled
   * semantics, but the caller gets an already-cancelled Job instead of
   * ghost-launching.
   */
  @Test fun cancelDuringAdmissionDurableWindowStartTurnDoesNotGhostLaunch() {
    val sink = CancelAttrSink()
    val entered = CountDownLatch(1)
    val release = CompletableDeferred<Unit>()
    sink.startedGate = { text ->
      if (text == "Q-window-st") {
        entered.countDown()
        release.await()
      }
    }
    val provider = CancelAttrProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "SHOULD-NEVER-STREAM"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider, sink)
    try {
      runBlocking {
        val deferred = async(Dispatchers.Default) { c.startTurn("Q-window-st") }
        assertTrue("admission did not park on durable await", entered.await(5, TimeUnit.SECONDS))
        c.cancel()
        assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
        release.complete(Unit)
        val host = withTimeout(5_000) { deferred.await() }
        assertTrue("window-cancel host must already be cancelled", host.isCancelled || host.isCompleted)
        try {
          withTimeout(5_000) { host.join() }
        } catch (_: CancellationException) {
        }
        withTimeout(5_000) { c.flushTranscript() }
      }
      assertEquals("ghost launch: provider must never be called", 0, provider.streamCalls.get())
      assertEquals(1, sink.started.size)
      assertEquals(1, sink.cancelled.size)
      assertEquals(sink.started.single().first, sink.cancelled.single().first)
      assertTrue(
        c.uiState.value.messages.any { it.role == "user" && it.text == "Q-window-st" },
      )
      assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
    } finally {
      release.complete(Unit)
      c.close()
    }
  }

  /**
   * Finding 2 regression: a finalize that observes close after its durable ack
   * already succeeded is a durability failure, not a bare
   * IllegalStateException. The ledger keeps the user row + cancel terminal
   * (no ghost host), the UI row is rolled back with ERROR, and the caller
   * gets [TranscriptDurabilityException] so the text stays retryable (never a
   * stranded pendingOps).
   */
  @Test fun finalizeClosedAfterDurableAckRollsBackForRetry() {
    val sink = CancelAttrSink()
    val entered = CountDownLatch(1)
    val release = CompletableDeferred<Unit>()
    sink.startedGate = { text ->
      if (text == "Q-closed") {
        entered.countDown()
        release.await()
      }
    }
    val provider = CancelAttrProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "SHOULD-NEVER-STREAM"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider, sink)
    try {
      runBlocking {
        supervisorScope {
          val deferred = async(Dispatchers.Default) { c.startOrEnqueue("Q-closed", opId = 99L) }
          assertTrue("admission did not park on durable await", entered.await(5, TimeUnit.SECONDS))
          // Close wins while the waiter is parked; release immediately so the
          // ack still succeeds before the background seal, driving the
          // finalize-closed path (not the seal-race rollback). supervisorScope
          // isolates the expected child failure so it cannot cancel the test
          // body (structured concurrency would otherwise fail runBlocking on
          // exit even though await rethrows into our catch).
          c.close(drainTimeoutMs = 5_000L)
          release.complete(Unit)
          try {
            withTimeout(5_000) { deferred.await() }
            fail("finalize-closed must throw TranscriptDurabilityException, not return Started")
          } catch (e: TranscriptDurabilityException) {
            // Expected fail-closed path (caller retains the text).
          }
          withTimeout(5_000) { c.flushTranscript() }
        }
      }
      assertEquals("ghost launch: provider must never be called", 0, provider.streamCalls.get())
      // Ledger: user row durable (exactly one user row, ack already succeeded
      // before close won). The cancel terminal is best-effort under the
      // background seal race: either a cancel record lands, or the runId is
      // explicitly INTERRUPTED-marked (same OR as the zero-dispatch tests).
      assertEquals(1, sink.started.size)
      assertEquals("Q-closed", sink.started.single().second)
      val closedLogicalId = sink.started.single().first
      val hasClosedCancel =
        sink.cancelled.size == 1 && sink.cancelled.single().first == closedLogicalId
      val closedInterrupted = c.interruptedTranscriptRunIds().contains(closedLogicalId)
      assertTrue(
        "finalize-closed must leave a cancel terminal or an explicit interrupt mark",
        hasClosedCancel || closedInterrupted,
      )
      // UI: optimistic row rolled back, ERROR for explicit retry.
      assertTrue(
        "finalize-closed must roll back the UI user row",
        c.uiState.value.messages.none { it.role == "user" && it.text == "Q-closed" },
      )
      assertEquals(ChatStatus.ERROR, c.uiState.value.status)
    } finally {
      release.complete(Unit)
      c.close()
    }
  }

  /**
   * Finding 5 asymmetry lock: promotion follow-ups are host-durable (dequeue
   * then write, no accept timeout). A user-row store failure after dequeue
   * cannot be reclaimed via drainQueued and has no caller to retain it: the
   * UI user row is kept with ERROR and the runId is INTERRUPTED-marked.
   */
  @Test fun promotionUserRowFailureIsHostDurableNotReclaimable() {
    val sink = CancelAttrSink()
    val enteredFirst = CountDownLatch(1)
    val releaseFirst = CompletableDeferred<Unit>()
    val provider = CancelAttrProvider { request ->
      flow {
        val lastUser = request.messages.lastOrNull { it.role == "user" }?.text.orEmpty()
        if (lastUser.contains("first")) {
          enteredFirst.countDown()
          releaseFirst.await()
          emit(StreamEvent.TextDelta(0, 0, "first-ok"))
          emit(StreamEvent.Done("stop"))
        } else {
          emit(StreamEvent.TextDelta(0, 0, "SHOULD-NEVER-STREAM-FOLLOW"))
          emit(StreamEvent.Done("stop"))
        }
      }
    }
    // Only the follow-up user row fails durably; the first turn persists.
    val failingSink = object : TranscriptSink by sink {
      override suspend fun onTurnStarted(runId: String, text: String) {
        if (text == "follow") throw IOException("promotion store boom")
        sink.onTurnStarted(runId, text)
      }
    }
    val c = controller(provider, failingSink)
    try {
      runBlocking {
        val firstDeferred = async(Dispatchers.Default) { c.startOrEnqueue("first") }
        // Wait for the admission to become Started, then for the host to park
        // on the provider so the follow-up queues behind a live turn.
        var started: TurnStart? = null
        val end = System.currentTimeMillis() + 5_000L
        while (started == null) {
          if (System.currentTimeMillis() > end) fail("first never started")
          if (firstDeferred.isCompleted) {
            started = firstDeferred.await()
          } else {
            delay(10)
          }
        }
        assertTrue(started is TurnStart.Started)
        assertTrue("first host did not park on provider", enteredFirst.await(5, TimeUnit.SECONDS))
        val queued = c.startOrEnqueue("follow")
        assertTrue("follow must queue behind the live first turn", queued is TurnStart.Queued)
        releaseFirst.complete(Unit)
        val host1 = (started as TurnStart.Started).host
        withTimeout(5_000) { host1.join() }
        // The promotion attempt dequeues follow, fails its user-row write,
        // and projects ERROR (host-durable, no caller retain).
        awaitTrue { c.uiState.value.status == ChatStatus.ERROR }
        withTimeout(5_000) { c.flushTranscript() }
      }
      // Asymmetry: the dequeued follow-up is NOT reclaimable.
      assertTrue("promotion failure must leave FIFO drained, not reclaimable", c.drainQueued().isEmpty())
      assertEquals(ChatStatus.ERROR, c.uiState.value.status)
      assertTrue(
        "promotion UI row is kept (not rolled back like direct admissions)",
        c.uiState.value.messages.any { it.role == "user" && it.text == "follow" },
      )
      assertTrue(
        "promotion user-row failure must be INTERRUPTED-marked",
        c.interruptedTranscriptRunIds().isNotEmpty(),
      )
      assertEquals("follow must never reach the provider (failed before dispatch)", 1, provider.streamCalls.get())
    } finally {
      releaseFirst.complete(Unit)
      c.close()
    }
  }
}
