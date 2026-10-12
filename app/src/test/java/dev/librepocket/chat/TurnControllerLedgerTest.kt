package dev.librepocket.chat

import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol as ProviderProto
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.PrunePolicy
import dev.librepocket.session.PruneResult
import dev.librepocket.session.SessionMeta
import dev.librepocket.session.SessionStore
import dev.librepocket.session.SessionTranscriptSink
import dev.librepocket.session.TranscriptEvent
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
import org.junit.Assert.fail
import org.junit.Test

private class LedgerFakeProvider(
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

private class LedgerAllowPolicy : PolicyStore {
  override fun evaluate(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
  override suspend fun setRule(rule: PolicyRule) = Unit
  override suspend fun removeRule(pattern: String) = Unit
  override suspend fun listRules(): List<PolicyRule> = emptyList()
  override suspend fun evaluateFresh(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

/** Fake store with per-kind write latency to force inversions without ordering. */
private class DelaySessionStore(
  var delayFor: (String) -> Long = { 0L },
  val sessionId: String = "ledger-test-session",
) : SessionStore {
  val events: MutableList<TranscriptEvent> = Collections.synchronizedList(mutableListOf())

  override suspend fun createSession(title: String, model: String): String = sessionId
  override suspend fun listSessions(): List<SessionMeta> = emptyList()
  override suspend fun getSession(sessionId: String): SessionMeta? =
    SessionMeta(sessionId, "t", 0L, 0L, "m")
  override suspend fun appendEvent(event: TranscriptEvent): Long {
    val wait = delayFor(event.kind)
    if (wait > 0) delay(wait)
    events.add(event.copy(seq = (events.size + 1).toLong()))
    return events.size.toLong()
  }
  override suspend fun loadEvents(sessionId: String, afterSeq: Long, limit: Int): List<TranscriptEvent> =
    events.filter { it.sessionId == sessionId && it.seq > afterSeq }.take(limit)
  override suspend fun exportJsonl(sessionId: String, destFile: File) = Unit
  override suspend fun importJsonl(srcFile: File): String = error("unused")
  override suspend fun prune(policy: PrunePolicy): PruneResult = PruneResult(0, 0)
  override suspend fun deleteSession(sessionId: String) = Unit
}

/**
 * Phase 1 conversation-ledger regressions: full production wiring
 * TurnController -> SessionTranscriptSink -> SessionStore with a fake store.
 *
 * - slow stores cannot reorder the ledger (single session writer);
 * - send/close back-to-back keeps the acked events (durable ack + drain);
 * - one logical turn retries with exactly one user row bound to its attempts.
 */
class TurnControllerLedgerTest {

  private fun controller(
    provider: LedgerFakeProvider,
    store: DelaySessionStore,
    retry: TurnRetryConfig = TurnRetryConfig(),
  ): TurnController {
    var n = 0
    return TurnController(
      provider = provider,
      policy = LedgerAllowPolicy(),
      transcript = SessionTranscriptSink(store, store.sessionId),
      retryConfig = retry,
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "ledger-${n++}" },
    )
  }

  private fun kindsOf(store: DelaySessionStore): List<String> = store.events.map { it.kind }

  @Test fun slowStoreCannotReorderLedger() {
    // Invert natural completion speed: user writes slowest, terminal fastest.
    // Without the session writer the assistant row would land first.
    val store = DelaySessionStore(delayFor = { kind ->
      when (kind) {
        "user" -> 80L
        "tool" -> 40L
        "system" -> 20L
        else -> 0L
      }
    })
    val provider = LedgerFakeProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.ToolDone(0, "call_1", "lookup", """{"q":"hi"}"""))
        emit(StreamEvent.Usage(10, 20))
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider, store)
    // send() returns only after the terminal core write is acked, so the
    // whole ledger prefix is durable here with no extra waiting.
    runBlocking { c.send("hi") }
    try {
      assertEquals(listOf("user", "tool", "system", "assistant"), kindsOf(store))
      assertEquals("hi", store.events[0].text)
      assertTrue(store.events[1].text.contains("lookup"))
      assertTrue(store.events[2].text.contains("usage"))
      // The ToolDone marker lives in the same assistant block by design.
      assertTrue(store.events[3].text.contains("ok"))
      assertTrue(store.events[3].text.contains("lookup"))
      assertEquals(listOf(1L, 2L, 3L, 4L), store.events.map { it.seq })
    } finally {
      c.close()
    }
  }

  @Test fun completedTurnSurvivesImmediateClose() {
    val store = DelaySessionStore()
    val provider = LedgerFakeProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider, store)
    runBlocking { c.send("hi") }
    // Back-to-back newChat/close: the acked user + assistant rows must survive.
    c.close()
    runBlocking { withTimeout(5_000) { c.flushTranscript() } }
    assertEquals(listOf("user", "assistant"), kindsOf(store))
    assertEquals("hi", store.events[0].text)
    assertEquals("ok", store.events[1].text)
    assertTrue(c.interruptedTranscriptRunIds().isEmpty())
  }

  @Test fun cancelledTurnKeepsUserAndPartialAfterClose() {
    val store = DelaySessionStore()
    val gate = CompletableDeferred<Unit>()
    val provider = LedgerFakeProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        gate.await()
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider, store)
    try {
      val admission = runBlocking { c.startOrEnqueue("hi", opId = 7) }
      assertTrue(admission is TurnStart.Started)
      // Wait until the partial is live: the host owns an assistant block, so
      // close() cancels a started turn (not a pre-start host whose body never
      // runs) and the terminal record carries the partial text.
      runBlocking {
        withTimeout(5_000) {
          while (c.uiState.value.messages.none { it.role == "assistant" && it.text == "half" }) {
            delay(10)
          }
        }
      }
      // Immediate close (no gate release): the doomed host's terminal record
      // is settled ahead of the flush barrier by close()'s bounded drain.
      c.close()
      runBlocking { withTimeout(5_000) { c.flushTranscript() } }
      assertEquals(listOf("user", "assistant"), kindsOf(store))
      assertEquals("hi", store.events[0].text)
      assertEquals("half", store.events[1].text)
      assertTrue(c.interruptedTranscriptRunIds().isEmpty())
    } finally {
      gate.complete(Unit)
      c.close()
    }
  }

  @Test fun cancelledTurnKeepsPartialFlagInMemoryAndPartialTextInLedger() {
    val store = DelaySessionStore()
    val gate = CompletableDeferred<Unit>()
    val provider = LedgerFakeProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        gate.await()
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider, store)
    try {
      val admission = runBlocking { c.startOrEnqueue("hi", opId = 7) }
      assertTrue(admission is TurnStart.Started)
      runBlocking {
        withTimeout(5_000) {
          while (c.uiState.value.messages.none { it.role == "assistant" && it.text == "half" }) {
            delay(10)
          }
        }
      }
      c.cancel()
      c.close()
      runBlocking { withTimeout(5_000) { c.flushTranscript() } }
      // Ledger keeps the partial text unstructured (the structured partial
      // flag column is a Phase 3 Target; see TranscriptEvent KDoc).
      assertEquals(listOf("half"), store.events.filter { it.kind == "assistant" }.map { it.text })
      // Memory partial flag survives: the cancelled block is never finalized.
      val assistants = c.uiState.value.messages.filter { it.role == "assistant" }
      assertEquals(1, assistants.size)
      assertTrue("cancelled partial must stay isPartial in memory", assistants.single().isPartial)
      assertTrue(c.interruptedTranscriptRunIds().isEmpty())
    } finally {
      gate.complete(Unit)
      c.close()
    }
  }

  @Test fun retryEmitsExactlyOneUserRowBoundToItsAttempts() {
    val store = DelaySessionStore()
    val calls = AtomicInteger(0)
    val provider = LedgerFakeProvider {
      flow {
        if (calls.incrementAndGet() == 1) {
          emit(StreamEvent.Failed("boom", retryable = true))
        } else {
          emit(StreamEvent.TextDelta(0, 0, "recovered"))
          emit(StreamEvent.Done("stop"))
        }
      }
    }
    val c = controller(provider, store, retry = TurnRetryConfig(maxRetries = 1, retryDelaysMs = listOf(0L)))
    runBlocking { c.send("hi") }
    try {
      val users = store.events.filter { it.kind == "user" }
      assertEquals("one logical turn keeps one user row", 1, users.size)
      assertEquals("hi", users.single().text)
      val retries = store.events.filter { it.kind == "retry" }
      assertEquals(1, retries.size)
      assertEquals(
        "the attempt record is bound to the logical turn, not the failed attempt",
        users.single().runId,
        retries.single().runId,
      )
      // Stage C: every retryable failure persists its partial terminal BEFORE
      // the retry notice (here the first attempt emitted no deltas, so the
      // partial keeps "" with the sanitized reason), then the success row.
      assertEquals(listOf("user", "assistant", "retry", "assistant"), kindsOf(store))
      val retriedPartial = store.events[1]
      assertEquals("assistant", retriedPartial.kind)
      assertTrue("retried partial must be flagged", retriedPartial.isPartial)
      assertEquals("", retriedPartial.text)
      assertEquals("boom", retriedPartial.failureReason)
      assertEquals(
        "first attempt reuses the logical id, so its parent stays null (pre-C compat)",
        users.single().runId,
        retriedPartial.runId,
      )
      assertEquals(null, retriedPartial.parentRunId)
      assertEquals(0, retriedPartial.attemptIndex)
      val success = store.events.last()
      assertEquals("recovered", success.text)
      assertEquals(users.single().runId, success.parentRunId)
      assertEquals(1, success.attemptIndex)
      // The success family completes the logical turn (no dangling).
      assertTrue(
        "retry-success family must not dangle",
        dev.librepocket.agent.ui.chat.findDanglingRunIds(store.events).isEmpty(),
      )
      // UI keeps one assistant block per attempt with distinct ids.
      val assistants = c.uiState.value.messages.filter { it.role == "assistant" }
      assertEquals(2, assistants.size)
      assertEquals(2, assistants.map { it.id }.toSet().size)
    } finally {
      c.close()
    }
  }

  @Test fun stalledStoreKeepsCloseFastAndMarksInterruptedExplicitly() {
    val gate = CompletableDeferred<Unit>()
    // R5-1: NOT_ADMITTED stall simulated BEFORE the channel send (admission
    // backpressure), so no ghost can late-commit: timeout-before-send stays
    // fail-closed with an INTERRUPTED mark. Timeout-after-send (delegate
    // stall) is now UNKNOWN/PENDING_COMMIT — covered by the R5-1 late-commit
    // tests below.
    val fastSink = object : dev.librepocket.chat.TranscriptSink {
      override suspend fun onTurnStarted(runId: String, text: String) = Unit
      override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) = Unit
      override suspend fun onTurnFailed(runId: String, error: String) = Unit
      override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
      override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) = Unit
      override suspend fun onSteerQueued(text: String) = Unit
      override suspend fun onToolDone(
        runId: String,
        toolIndex: Int,
        id: String,
        name: String,
        argumentsJson: String,
      ) = Unit
      override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) = Unit
    }
    var n = 0
    val c = TurnController(
      provider = LedgerFakeProvider {
        flow { emit(StreamEvent.Done("stop")) }
      },
      policy = LedgerAllowPolicy(),
      transcript = fastSink,
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "stalled-${n++}" },
      // Stage 6: stall must fail-closed fast (not park 5s in tests).
      acceptTimeoutMs = 400L,
    )
    c.beforeAdmissionSendForTest = { gate.await() }
    try {
      // Stage 6 (R4-3): a stalled user-row ack no longer returns Started.
      // It throws fail-closed (caller retains the text) and marks INTERRUPTED.
      try {
        runBlocking { c.startOrEnqueue("hi", opId = 9) }
        fail("stalled admission must throw TranscriptDurabilityException, not return Started")
      } catch (e: dev.librepocket.chat.TranscriptDurabilityException) {
        // Expected fail-closed path.
      }
      // Fail-closed rollback: no ghost UI row remains for the unaccepted text.
      assertTrue(
        "failed admission must roll back the UI append",
        c.uiState.value.messages.none { it.role == "user" && it.text == "hi" },
      )
      // The admission-time wait already marked the logical id (UI row
      // stalled-0, logical stalled-1); close() must still stay fast while the
      // store stalls.
      val started = System.currentTimeMillis()
      c.close(drainTimeoutMs = 200L)
      val elapsed = System.currentTimeMillis() - started
      assertTrue("close() must stay fast when the store stalls, took ${elapsed}ms", elapsed < 5_000L)
      val end = System.currentTimeMillis() + 8_000L
      while (c.interruptedTranscriptRunIds().isEmpty()) {
        if (System.currentTimeMillis() > end) fail("stalled user row was never marked INTERRUPTED")
        Thread.sleep(10)
      }
      // The parked user row (second newId: UI user row takes stalled-0, the
      // logical turn takes stalled-1) is explicitly marked, never dropped.
      assertTrue(c.interruptedTranscriptRunIds().contains("stalled-1"))
    } finally {
      gate.complete(Unit)
      c.close()
    }
  }

  @Test fun flushTranscriptReportsDrainTimeoutAndDrainsAfterUnblock() {
    val gate = CompletableDeferred<Unit>()
    val blockingSink = object : dev.librepocket.chat.TranscriptSink {
      // Stage 6: admission (user row) stays fast so Started is still returned;
      // terminal + orphan-cancel acks stall, parking the drain behind the gate.
      override suspend fun onTurnStarted(runId: String, text: String) = Unit
      override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) {
        gate.await()
      }
      override suspend fun onTurnFailed(runId: String, error: String) = Unit
      override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
      override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) {
        gate.await()
      }
      override suspend fun onLogicalTurnCancelled(
        logicalTurnId: String,
        attemptRunId: String,
        partialText: String,
        attemptIndex: Int?,
      ) {
        gate.await()
      }
      override suspend fun onSteerQueued(text: String) = Unit
      override suspend fun onToolDone(
        runId: String,
        toolIndex: Int,
        id: String,
        name: String,
        argumentsJson: String,
      ) = Unit
      override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) = Unit
    }
    var n = 0
    val c = TurnController(
      provider = LedgerFakeProvider {
        flow { emit(StreamEvent.Done("stop")) }
      },
      policy = LedgerAllowPolicy(),
      transcript = blockingSink,
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "flush-${n++}" },
    )
    try {
      val admission = runBlocking { c.startOrEnqueue("hi", opId = 11) }
      assertTrue(admission is TurnStart.Started)
      c.close(drainTimeoutMs = 5_000L)
      // The background drain is stalled behind the gate: a bounded flush
      // reports false instead of parking the caller.
      runBlocking {
        withTimeout(10_000) {
          assertFalse("flushTranscript must time out while the drain stalls", c.flushTranscript(200))
        }
      }
      gate.complete(Unit)
      // Once the store unblocks the same call drains fully.
      runBlocking {
        withTimeout(10_000) {
          assertTrue("flushTranscript must drain after the store unblocks", c.flushTranscript())
        }
      }
    } finally {
      gate.complete(Unit)
      c.close()
    }
  }

  @Test
  fun zeroDispatchImmediateCloseDoesNotLoseAcceptedUserTurn() {
    val store = DelaySessionStore()
    val provider = LedgerFakeProvider()

    class PausedDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
      private val tasks = Collections.synchronizedList(ArrayList<Runnable>())
      @Volatile var paused = true

      override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
        if (paused) {
          tasks.add(block)
        } else {
          Dispatchers.Default.dispatch(context, block)
        }
      }

      fun resumeAll() {
        paused = false
        val queued = synchronized(tasks) {
          val copy = ArrayList(tasks)
          tasks.clear()
          copy
        }
        for (task in queued) {
          Dispatchers.Default.dispatch(kotlin.coroutines.EmptyCoroutineContext, task)
        }
      }
    }

    val pausedDispatcher = PausedDispatcher()
    var n = 0
    val c = TurnController(
      provider = provider,
      policy = LedgerAllowPolicy(),
      transcript = SessionTranscriptSink(store, store.sessionId),
      retryConfig = TurnRetryConfig(),
      dispatcher = pausedDispatcher,
      sleeper = {},
      newId = { "zero-dispatch-${n++}" },
      // Stage 6: writer stays unblocked so Started==durable still holds while
      // the host never dispatches; the acceptance ack does not deadlock.
      transcriptDispatcher = Dispatchers.Default,
    )

    // Call startOrEnqueue while host dispatcher is paused -> Started (user row
    // already durable via the writer dispatcher), host coroutine never executed
    val admission = runBlocking { c.startOrEnqueue("zero dispatch user turn", opId = 42L) }
    assertTrue(admission is TurnStart.Started)
    assertEquals(0, provider.streamCalls.get())

    // Zero-dispatch immediate close
    c.close()

    // Resume dispatcher and flush transcript
    pausedDispatcher.resumeAll()
    val drained = runBlocking { withTimeout(5_000) { c.flushTranscript() } }
    assertTrue(drained)

    // Assert: transcript has recorded the user row and has a cancellation or interrupted terminal record
    val userEvents = store.events.filter { it.kind == "user" }
    assertEquals(1, userEvents.size)
    assertEquals("zero dispatch user turn", userEvents[0].text)

    // Terminal record exists (cancellation system mark or marked interrupted)
    val terminalEvents = store.events.filter { it.kind == "system" && it.text.contains("cancelled") }
    val isInterrupted = c.interruptedTranscriptRunIds().isNotEmpty()
    assertTrue(terminalEvents.isNotEmpty() || isInterrupted)
  }

  @Test
  fun zeroDispatchImmediateCloseDoesNotLoseAcceptedStartTurn() {
    val store = DelaySessionStore()
    val provider = LedgerFakeProvider()

    class PausedDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
      private val tasks = Collections.synchronizedList(ArrayList<Runnable>())
      @Volatile var paused = true

      override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
        if (paused) {
          tasks.add(block)
        } else {
          Dispatchers.Default.dispatch(context, block)
        }
      }

      fun resumeAll() {
        paused = false
        val queued = synchronized(tasks) {
          val copy = ArrayList(tasks)
          tasks.clear()
          copy
        }
        for (task in queued) {
          Dispatchers.Default.dispatch(kotlin.coroutines.EmptyCoroutineContext, task)
        }
      }
    }

    val pausedDispatcher = PausedDispatcher()
    var n = 0
    val c = TurnController(
      provider = provider,
      policy = LedgerAllowPolicy(),
      transcript = SessionTranscriptSink(store, store.sessionId),
      retryConfig = TurnRetryConfig(),
      dispatcher = pausedDispatcher,
      sleeper = {},
      newId = { "zero-dispatch-st-${n++}" },
      // Stage 6: same split as above (writer unblocked, host paused).
      transcriptDispatcher = Dispatchers.Default,
    )

    val job = runBlocking { c.startTurn("zero dispatch startTurn") }
    assertEquals(0, provider.streamCalls.get())

    c.close()

    pausedDispatcher.resumeAll()
    val drained = runBlocking { withTimeout(5_000) { c.flushTranscript() } }
    assertTrue(drained)

    val userEvents = store.events.filter { it.kind == "user" }
    assertEquals(1, userEvents.size)
    assertEquals("zero dispatch startTurn", userEvents[0].text)

    val terminalEvents = store.events.filter { it.kind == "system" && it.text.contains("cancelled") }
    val isInterrupted = c.interruptedTranscriptRunIds().isNotEmpty()
    assertTrue(terminalEvents.isNotEmpty() || isInterrupted)
  }

  /**
   * Stage 6 (R4-3) SIGKILL regression: Started == durable user row.
   *
   * The host dispatcher never releases (zero dispatch, like a SIGKILL right
   * after acceptance); the writer dispatcher stays unblocked so the
   * admission-time ack can complete. No close/flush/resumeAll happens before
   * the assertion — the controller is discarded outright. Before the fix this
   * saw 0 rows (user row only existed in memory + pendingAdmission); after
   * the fix it sees exactly 1 durable user row.
   */
  @Test
  fun acceptedUserRowDurableBeforeHostDispatch() {
    val store = DelaySessionStore()
    val provider = LedgerFakeProvider()

    class NeverDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
      override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
        // Never run: the host never dispatches (SIGKILL before first suspend).
      }
    }

    var n = 0
    val c = TurnController(
      provider = provider,
      policy = LedgerAllowPolicy(),
      transcript = SessionTranscriptSink(store, store.sessionId),
      retryConfig = TurnRetryConfig(),
      dispatcher = NeverDispatcher(),
      sleeper = {},
      newId = { "sigkill-${n++}" },
      transcriptDispatcher = Dispatchers.Default,
    )

    val text = "sigkill durable user turn"
    val admission = runBlocking { c.startOrEnqueue(text, opId = 100L) }
    assertTrue(admission is TurnStart.Started)
    assertEquals("host must never have dispatched", 0, provider.streamCalls.get())

    // SIGKILL: no close, no resumeAll, no flush — drop the controller and read
    // the same underlying store directly. The row must already be durable.
    val userEvents = store.events.filter { it.kind == "user" }
    assertEquals("Started must imply a durable user row without close/flush", 1, userEvents.size)
    assertEquals(text, userEvents[0].text)

    // Cleanup only (after the durability assertion above).
    c.close()
  }

  /**
   * Stage 6 reverse: stalled store + bounded timeout fails closed without
   * losing the text. Started must throw [TranscriptDurabilityException] (not
   * return Started), the UI append is rolled back, the logical id is
   * INTERRUPTED-marked, and the same text remains retryable once the store
   * unblocks (caller retains ownership, mirroring the InputTooLong path).
   */
  @Test
  fun stalledAdmissionFailsClosedWithoutLosingText() {
    val gate = CompletableDeferred<Unit>()
    // R5-1: same NOT_ADMITTED treatment as above — stall BEFORE send so the
    // timeout path keeps fail-closed rollback + INTERRUPTED mark. Delegate
    // stalls (timeout-after-send) are now UNKNOWN; see R5-1 tests below.
    val fastSink = object : dev.librepocket.chat.TranscriptSink {
      override suspend fun onTurnStarted(runId: String, text: String) = Unit
      override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) = Unit
      override suspend fun onTurnFailed(runId: String, error: String) = Unit
      override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
      override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) = Unit
      override suspend fun onSteerQueued(text: String) = Unit
      override suspend fun onToolDone(
        runId: String,
        toolIndex: Int,
        id: String,
        name: String,
        argumentsJson: String,
      ) = Unit
      override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) = Unit
    }
    var n = 0
    val c = TurnController(
      provider = LedgerFakeProvider {
        flow { emit(StreamEvent.Done("stop")) }
      },
      policy = LedgerAllowPolicy(),
      transcript = fastSink,
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "stall-reverse-${n++}" },
      acceptTimeoutMs = 300L,
    )
    c.beforeAdmissionSendForTest = { gate.await() }
    val text = "stall reverse retryable text"
    try {
      try {
        runBlocking { c.startOrEnqueue(text, opId = 77L) }
        fail("stalled admission must throw, not return Started")
      } catch (e: TranscriptDurabilityException) {
        // Expected fail-closed: caller retains `text` for retry.
      }
      // Fail-closed rollback: no ghost UI row.
      assertTrue(
        c.uiState.value.messages.none { it.role == "user" && it.text == text },
      )
      // Explicit INTERRUPTED mark for the logical id (UI stalled-0, logical
      // stalled-1 equivalent with this prefix).
      assertTrue(c.interruptedTranscriptRunIds().contains("stall-reverse-1"))
      // The text is not lost: unblock and retry the same text — it admits.
      gate.complete(Unit)
      val retry = runBlocking {
        withTimeout(5_000) { c.startOrEnqueue(text, opId = 77L) }
      }
      assertTrue("unblocked retry must admit", retry is TurnStart.Started)
    } finally {
      gate.complete(Unit)
      c.close()
    }
  }

  /**
   * R5-1 late-commit: writer already started the user-row delegate when the
   * accept timeout fires (send proof true → UNKNOWN, not NOT_ADMITTED).
   * The controller retains ownership: same-opId explicit retry is blocked
   * (no second user row), the ghost late-commits, bounded settle claims it
   * under the SAME logical id (no resend), and reopen sees exactly one user
   * row with no false INTERRUPTED mark.
   */
  @Test
  fun timeoutAfterSendLateCommitClaimsSingleUserRow() {
    val store = DelaySessionStore()
    val inner = SessionTranscriptSink(store, store.sessionId)
    val gate = CompletableDeferred<Unit>()
    val blockingSink = object : TranscriptSink by inner {
      override suspend fun onTurnStarted(runId: String, text: String) {
        gate.await()
        inner.onTurnStarted(runId, text)
      }
    }
    var n = 0
    val c = TurnController(
      provider = LedgerFakeProvider {
        flow {
          emit(StreamEvent.TextDelta(0, 0, "ok"))
          emit(StreamEvent.Done("stop"))
        }
      },
      policy = LedgerAllowPolicy(),
      transcript = blockingSink,
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "ghost-${n++}" },
      acceptTimeoutMs = 300L,
    )
    val text = "late commit text"
    try {
      // Writer has started (parked in the delegate gate) → timeout-after-send.
      try {
        runBlocking { c.startOrEnqueue(text, opId = 77L) }
        fail("timeout-after-send must throw TranscriptPendingException, not return Started")
      } catch (e: TranscriptPendingException) {
        // Expected UNKNOWN/PENDING_COMMIT path.
      }
      // Ownership retained: UI row kept (not rolled back), still pending, and
      // — critically — no false INTERRUPTED mark while fate is unknown.
      assertTrue(c.hasPendingAdmission())
      assertEquals(1, c.uiState.value.messages.count { it.role == "user" && it.text == text })
      assertTrue(
        "UNKNOWN must not mark INTERRUPTED before settle",
        c.interruptedTranscriptRunIds().isEmpty(),
      )
      assertTrue(
        "ghost entry must be admitted (sent) and awaiting the writer",
        c.pendingTranscriptRunIds().contains("ghost-1"),
      )
      assertTrue("ghost must not have persisted yet", store.events.none { it.kind == "user" })
      // Explicit retry with the SAME opId before settle: blocked, no second row.
      try {
        runBlocking { c.startOrEnqueue(text, opId = 77L) }
        fail("same-opId retry during UNKNOWN must throw TranscriptPendingException")
      } catch (e: TranscriptPendingException) {
        // Expected: no resend, no duplicate.
      }
      assertEquals(
        "blocked retry must not append a second UI row",
        1,
        c.uiState.value.messages.count { it.role == "user" && it.text == text },
      )
      assertEquals(0, c.uiState.value.pendingSteerCount)
      assertTrue("blocked retry must not persist a second row", store.events.none { it.kind == "user" })
      // Late commit: unblock the writer, then drive bounded settle to claim.
      gate.complete(Unit)
      runBlocking {
        withTimeout(8_000) {
          while (c.hasPendingAdmission()) {
            c.settlePendingAdmission(500L)
            delay(25)
          }
        }
      }
      // Claimed under the same logical id: no INTERRUPTED, single user row.
      assertTrue(
        "claimed ghost must not be INTERRUPTED",
        c.interruptedTranscriptRunIds().isEmpty(),
      )
      val users = store.events.filter { it.kind == "user" }
      assertEquals("claimed ghost keeps exactly one user row", 1, users.size)
      assertEquals(text, users.single().text)
      assertEquals("ghost-1", users.single().runId)
      // The claimed host runs the turn to completion (no resend of the row).
      runBlocking {
        withTimeout(8_000) {
          while (c.uiState.value.status != ChatStatus.IDLE) delay(10)
        }
        withTimeout(5_000) { c.flushTranscript() }
      }
      assertEquals(
        "no duplicate user row after the claimed turn completes",
        1,
        store.events.count { it.kind == "user" },
      )
      assertEquals(
        1,
        c.uiState.value.messages.count { it.role == "user" && it.text == text },
      )
      // Reopen view: the logical family is complete (user + final assistant),
      // so backfill must find nothing dangling and model history sees one user.
      assertTrue(
        "claimed family must not dangle",
        dev.librepocket.agent.ui.chat.findDanglingRunIds(store.events).isEmpty(),
      )
      assertTrue(c.interruptedTranscriptRunIds().isEmpty())
    } finally {
      gate.complete(Unit)
      c.close()
    }
  }

  /**
   * R5-1 settle-failure: the ghost write is confirmed failed (delegate
   * IOException → INTERRUPTED) or the writer is sealed. Bounded settle then
   * converts UNKNOWN to fail-closed: UI rolled back, ownership released, and
   * the same opId becomes retryable via [TranscriptDurabilityException]
   * (later succeeding with exactly one row once the store recovers).
   */
  @Test
  fun timeoutAfterSendSettleFailureBecomesRetryable() {
    val store = DelaySessionStore()
    val inner = SessionTranscriptSink(store, store.sessionId)
    val gate = CompletableDeferred<Unit>()
    var failNext = true
    val failingSink = object : TranscriptSink by inner {
      override suspend fun onTurnStarted(runId: String, text: String) {
        gate.await()
        if (failNext) throw java.io.IOException("store down")
        inner.onTurnStarted(runId, text)
      }
    }
    var n = 0
    val c = TurnController(
      provider = LedgerFakeProvider {
        flow {
          emit(StreamEvent.TextDelta(0, 0, "recovered"))
          emit(StreamEvent.Done("stop"))
        }
      },
      policy = LedgerAllowPolicy(),
      transcript = failingSink,
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "settle-fail-${n++}" },
      acceptTimeoutMs = 300L,
    )
    val text = "settle failure text"
    try {
      try {
        runBlocking { c.startOrEnqueue(text, opId = 77L) }
        fail("timeout-after-send must throw TranscriptPendingException")
      } catch (e: TranscriptPendingException) {
        // Expected UNKNOWN path.
      }
      assertTrue(c.hasPendingAdmission())
      assertTrue(c.interruptedTranscriptRunIds().isEmpty())
      // Confirm the failure: unblock so the delegate throws, then settle.
      gate.complete(Unit)
      runBlocking {
        withTimeout(8_000) {
          while (c.hasPendingAdmission()) {
            c.settlePendingAdmission(500L)
            delay(25)
          }
        }
      }
      // Converted to fail-closed: row rolled back, INTERRUPTED explicit,
      // ownership released for retry.
      assertTrue(c.interruptedTranscriptRunIds().contains("settle-fail-1"))
      assertTrue(
        "failed settle must roll back the UI row",
        c.uiState.value.messages.none { it.role == "user" && it.text == text },
      )
      assertEquals("transcript store unavailable", c.uiState.value.error)
      // Same opId is retryable now: store still down → durability failure
      // (NOT pending), never a silent second-row duplicate.
      try {
        runBlocking { withTimeout(5_000) { c.startOrEnqueue(text, opId = 77L) } }
        fail("retry while store is down must throw TranscriptDurabilityException")
      } catch (e: TranscriptDurabilityException) {
        // Expected retryable fail-closed path.
      }
      assertTrue("failed retry must persist nothing", store.events.none { it.kind == "user" })
      // Store recovers: the same opId admits exactly one row.
      failNext = false
      val retry = runBlocking {
        withTimeout(5_000) { c.startOrEnqueue(text, opId = 77L) }
      }
      assertTrue("recovered retry must admit", retry is TurnStart.Started)
      runBlocking {
        (retry as TurnStart.Started).host.join()
        withTimeout(5_000) { c.flushTranscript() }
      }
      assertEquals(1, store.events.count { it.kind == "user" })
      assertEquals(text, store.events.single { it.kind == "user" }.text)
      // The pre-failure ghost id stays marked; the store-down retry id is
      // marked too (its delegate write failed), but the recovered turn's id
      // is clean.
      assertTrue(c.interruptedTranscriptRunIds().contains("settle-fail-1"))
      val recoveredRunId = store.events.single { it.kind == "user" }.runId
      assertTrue(
        "recovered turn must not be INTERRUPTED",
        !c.interruptedTranscriptRunIds().contains(recoveredRunId),
      )
    } finally {
      gate.complete(Unit)
      c.close()
    }
  }

  /**
   * R6-2 external cancel after the channel send (send proof true) must follow
   * the UNKNOWN path — not the NOT_ADMITTED rollback. Ownership is retained
   * (UI row kept, same-opId resend blocked), no false INTERRUPTED mark is
   * written, and the bounded settle claims the late-committing ghost under the
   * SAME logical id (single user row, no dangling family).
   */
  @OptIn(DelicateCoroutinesApi::class)
  @Test
  fun callerCancelAfterSendKeepsOwnershipAndClaimsOnLateCommit() {
    val store = DelaySessionStore()
    val inner = SessionTranscriptSink(store, store.sessionId)
    val gate = CompletableDeferred<Unit>()
    val delegateEntered = AtomicBoolean(false)
    val blockingSink = object : TranscriptSink by inner {
      override suspend fun onTurnStarted(runId: String, text: String) {
        delegateEntered.set(true)
        gate.await()
        inner.onTurnStarted(runId, text)
      }
    }
    val provider = LedgerFakeProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    }
    var n = 0
    val c = TurnController(
      provider = provider,
      policy = LedgerAllowPolicy(),
      transcript = blockingSink,
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "cancel-after-${n++}" },
      // Large budget excludes the timeout path: only the external cancel fires.
      acceptTimeoutMs = 10_000L,
    )
    val text = "caller cancel after send"
    val opId = 101L
    try {
      val deferred = GlobalScope.async(Dispatchers.Default) {
        c.startOrEnqueue(text, opId = opId)
      }
      // Delegate entry proves the channel send already succeeded (writer only
      // runs after send), so sent == true and the waiter is parked in ack.await.
      runBlocking {
        withTimeout(5_000) {
          while (!delegateEntered.get()) delay(10)
        }
      }
      runBlocking {
        withTimeout(5_000) {
          while (!c.pendingTranscriptRunIds().contains("cancel-after-1")) delay(10)
        }
      }
      assertFalse("admission must still be parked in ack.await", deferred.isCompleted)
      // External caller-scope cancel (ack.await cancelled, entry already sent).
      deferred.cancel()
      try {
        runBlocking {
          withTimeout(5_000) { deferred.await() }
        }
        fail("caller cancel after send must throw TranscriptPendingException, not return Started")
      } catch (e: TranscriptPendingException) {
        // Expected UNKNOWN path.
      } catch (e: CancellationException) {
        fail("caller cancel after send must not surface CancellationException, got $e")
      }
      // UNKNOWN ownership: UI row kept, still pending, never INTERRUPTED.
      assertTrue(c.hasPendingAdmission())
      assertTrue(
        "sent==true cancel must not mark INTERRUPTED before settle",
        c.interruptedTranscriptRunIds().isEmpty(),
      )
      assertEquals(1, c.uiState.value.messages.count { it.role == "user" && it.text == text })
      assertTrue(c.pendingTranscriptRunIds().contains("cancel-after-1"))
      assertTrue("ghost must not have persisted yet", store.events.none { it.kind == "user" })
      // Same-opId explicit retry is blocked (no second row) until settle.
      try {
        runBlocking { withTimeout(5_000) { c.startOrEnqueue(text, opId = opId) } }
        fail("same-opId retry during UNKNOWN must throw TranscriptPendingException")
      } catch (e: TranscriptPendingException) {
        // Expected: no resend, no duplicate.
      }
      assertEquals(
        "blocked retry must not append a second UI row",
        1,
        c.uiState.value.messages.count { it.role == "user" && it.text == text },
      )
      assertEquals(0, c.uiState.value.pendingSteerCount)
      // Late commit: unblock the writer, then drive bounded settle to claim.
      gate.complete(Unit)
      runBlocking {
        withTimeout(8_000) {
          while (c.hasPendingAdmission()) {
            c.settlePendingAdmission(500L)
            delay(25)
          }
        }
      }
      assertTrue(
        "claimed ghost must not be INTERRUPTED",
        c.interruptedTranscriptRunIds().isEmpty(),
      )
      val users = store.events.filter { it.kind == "user" }
      assertEquals("claimed ghost keeps exactly one user row", 1, users.size)
      assertEquals(text, users.single().text)
      assertEquals("cancel-after-1", users.single().runId)
      // The claimed host runs the turn to completion (single provider call).
      runBlocking {
        withTimeout(8_000) {
          while (c.uiState.value.status != ChatStatus.IDLE) delay(10)
        }
        withTimeout(5_000) { c.flushTranscript() }
      }
      assertEquals(
        "no duplicate user row after the claimed turn completes",
        1,
        store.events.count { it.kind == "user" },
      )
      assertEquals(
        1,
        c.uiState.value.messages.count { it.role == "user" && it.text == text },
      )
      assertTrue(
        "claimed family must not dangle",
        dev.librepocket.agent.ui.chat.findDanglingRunIds(store.events).isEmpty(),
      )
      assertTrue(c.interruptedTranscriptRunIds().isEmpty())
      assertEquals("claimed host runs exactly once, no resend", 1, provider.streamCalls.get())
    } finally {
      gate.complete(Unit)
      c.close()
    }
  }

  /**
   * R6-2对照: external cancel before the channel send (send proof false) keeps
   * NOT_ADMITTED semantics — CE + rollback + INTERRUPTED, immediately
   * retryable with a fresh id once the admission backpressure clears.
   */
  @OptIn(DelicateCoroutinesApi::class)
  @Test
  fun callerCancelBeforeSendStillFailsClosed() {
    val store = DelaySessionStore()
    val inner = SessionTranscriptSink(store, store.sessionId)
    val provider = LedgerFakeProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "recovered"))
        emit(StreamEvent.Done("stop"))
      }
    }
    var n = 0
    val c = TurnController(
      provider = provider,
      policy = LedgerAllowPolicy(),
      transcript = inner,
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "cancel-before-${n++}" },
      acceptTimeoutMs = 10_000L,
    )
    val gate = CompletableDeferred<Unit>()
    val beforeSendEntered = AtomicBoolean(false)
    // Park BEFORE the channel send so sent stays false at cancel time.
    c.beforeAdmissionSendForTest = {
      beforeSendEntered.set(true)
      gate.await()
    }
    val text = "caller cancel before send"
    val opId = 102L
    try {
      val deferred = GlobalScope.async(Dispatchers.Default) {
        c.startOrEnqueue(text, opId = opId)
      }
      runBlocking {
        withTimeout(5_000) {
          while (!beforeSendEntered.get()) delay(10)
        }
      }
      // Let the waiter settle into the gate (send provably not attempted).
      Thread.sleep(200)
      assertFalse("admission must still be parked before send", deferred.isCompleted)
      deferred.cancel()
      try {
        runBlocking {
          withTimeout(5_000) { deferred.await() }
        }
        fail("caller cancel before send must throw CancellationException")
      } catch (e: TranscriptPendingException) {
        fail("before-send cancel must not become UNKNOWN/Pending, got $e")
      } catch (e: CancellationException) {
        // Expected NOT_ADMITTED path.
      }
      // Fail-closed: no ownership, UI rolled back, explicit INTERRUPTED mark.
      assertFalse(c.hasPendingAdmission())
      assertTrue(
        "failed admission must roll back the UI append",
        c.uiState.value.messages.none { it.role == "user" && it.text == text },
      )
      assertTrue(c.interruptedTranscriptRunIds().contains("cancel-before-1"))
      assertTrue(c.pendingTranscriptRunIds().isEmpty())
      assertTrue("nothing was ever sent, so nothing persisted", store.events.none { it.kind == "user" })
      // Retryable once the backpressure clears: fresh id admits exactly one row.
      gate.complete(Unit)
      val retry = runBlocking {
        withTimeout(5_000) { c.startOrEnqueue(text, opId = opId) }
      }
      assertTrue("unblocked retry must admit", retry is TurnStart.Started)
      runBlocking {
        (retry as TurnStart.Started).host.join()
        withTimeout(5_000) { c.flushTranscript() }
      }
      assertEquals(1, store.events.count { it.kind == "user" })
      assertEquals(text, store.events.single { it.kind == "user" }.text)
      val recoveredRunId = store.events.single { it.kind == "user" }.runId
      assertTrue(
        "cancelled before-send id stays marked",
        c.interruptedTranscriptRunIds().contains("cancel-before-1"),
      )
      assertTrue(
        "recovered turn must not be INTERRUPTED",
        !c.interruptedTranscriptRunIds().contains(recoveredRunId),
      )
    } finally {
      gate.complete(Unit)
      c.close()
    }
  }

  /**
   * R8-6 fuzzy send-cancel end to end: the channel is full (capacity 1 behind
   * a parked writer) so the admission send really suspends and resumes; the
   * post-send hook then parks it after the send returned but before the `sent`
   * proof flag is set (admitted yet `sent == false`, deterministically — no
   * send-guessing sleep). A caller cancel landing there must map to UNKNOWN,
   * never NOT_ADMITTED: ownership retained ([hasPendingAdmission], UI row
   * kept, same-opId resend blocked), no false INTERRUPTED mark, and the
   * bounded settle claims the late commit under the SAME logical id (single
   * user row; reopen trio: single row + complete family + no INTERRUPTED).
   * Only the external cancel fires (large [acceptTimeoutMs], no timeout).
   */
  @OptIn(DelicateCoroutinesApi::class)
  @Test
  fun fullChannelResumedSendCancelLateCommitRetryReopenSingleRow() {
    val store = DelaySessionStore()
    val inner = SessionTranscriptSink(store, store.sessionId)
    val writerGate = CompletableDeferred<Unit>()
    val blockerEntered = CompletableDeferred<Unit>()
    val ghostGate = CompletableDeferred<Unit>()
    val ghostEntered = AtomicBoolean(false)
    val blockingSink = object : TranscriptSink by inner {
      override suspend fun onSteerQueued(text: String) {
        if (text == "blocker") {
          blockerEntered.complete(Unit)
          writerGate.await()
        }
        inner.onSteerQueued(text)
      }
      override suspend fun onTurnStarted(runId: String, text: String) {
        ghostEntered.set(true)
        ghostGate.await()
        inner.onTurnStarted(runId, text)
      }
    }
    val provider = LedgerFakeProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    }
    var n = 0
    val c = TurnController(
      provider = provider,
      policy = LedgerAllowPolicy(),
      transcript = blockingSink,
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "r8fuzzy-${n++}" },
      // Large budget excludes the timeout path: only the external cancel fires.
      acceptTimeoutMs = 10_000L,
      transcriptCapacity = 1,
    )
    val afterSendEntered = AtomicBoolean(false)
    val hookRelease = CompletableDeferred<Unit>()
    c.afterAdmissionSendForTest = { _ ->
      afterSendEntered.set(true)
      hookRelease.await()
    }
    val text = "fuzzy admission text"
    val opId = 801L
    try {
      // Fill the channel behind the parked writer: blocker (taken, parked in
      // the delegate gate) + filler (buffered, capacity 1).
      c.offerSteerForTest("blocker")
      runBlocking {
        withTimeout(5_000) { blockerEntered.await() }
      }
      c.offerSteerForTest("filler")
      val deferred = GlobalScope.async(Dispatchers.Default) {
        c.startOrEnqueue(text, opId = opId)
      }
      // Release the writer so the really-suspended send resumes, then park
      // post-send-pre-flag. Either interleaving (suspend-then-resume or instant
      // send) lands in the hook with sent == false and the entry admitted.
      writerGate.complete(Unit)
      runBlocking {
        withTimeout(5_000) { while (!afterSendEntered.get()) delay(10) }
      }
      assertFalse("admission must be parked post-send-pre-flag", deferred.isCompleted)
      // Hold the late commit in the delegate while the fuzzy cancel lands.
      runBlocking {
        withTimeout(5_000) { while (!ghostEntered.get()) delay(10) }
      }
      deferred.cancel()
      try {
        runBlocking {
          withTimeout(5_000) { deferred.await() }
        }
        fail("fuzzy send-cancel must throw TranscriptPendingException, not return Started")
      } catch (e: TranscriptPendingException) {
        // Expected UNKNOWN path.
      } catch (e: CancellationException) {
        fail("fuzzy send-cancel must not surface CancellationException, got $e")
      }
      // UNKNOWN ownership: still pending, UI row kept, never INTERRUPTED.
      assertTrue(c.hasPendingAdmission())
      assertTrue(
        "fuzzy cancel must not mark INTERRUPTED before settle",
        c.interruptedTranscriptRunIds().isEmpty(),
      )
      assertEquals(1, c.uiState.value.messages.count { it.role == "user" && it.text == text })
      val ghost = c.pendingTranscriptRunIds().single()
      // Same-opId explicit retry is blocked (no second row) until settle.
      try {
        runBlocking { withTimeout(5_000) { c.startOrEnqueue(text, opId = opId) } }
        fail("same-opId retry during UNKNOWN must throw TranscriptPendingException")
      } catch (e: TranscriptPendingException) {
        // Expected: no resend, no duplicate.
      }
      assertEquals(
        "blocked retry must not append a second UI row",
        1,
        c.uiState.value.messages.count { it.role == "user" && it.text == text },
      )
      assertEquals(0, c.uiState.value.pendingSteerCount)
      // Late commit: release the ghost delegate + hook, settle claims the
      // ghost under the SAME logical id.
      ghostGate.complete(Unit)
      hookRelease.complete(Unit)
      runBlocking {
        withTimeout(8_000) {
          while (c.hasPendingAdmission()) {
            c.settlePendingAdmission(500L)
            delay(25)
          }
        }
      }
      assertTrue(
        "claimed ghost must not be INTERRUPTED",
        c.interruptedTranscriptRunIds().isEmpty(),
      )
      val users = store.events.filter { it.kind == "user" }
      assertEquals("claimed ghost keeps exactly one user row", 1, users.size)
      assertEquals(text, users.single().text)
      assertEquals(ghost, users.single().runId)
      // The claimed host runs the turn to completion (single provider call).
      runBlocking {
        withTimeout(8_000) {
          while (c.uiState.value.status != ChatStatus.IDLE) delay(10)
        }
        withTimeout(5_000) { c.flushTranscript() }
      }
      assertEquals(
        "no duplicate user row after the claimed turn completes",
        1,
        store.events.count { it.kind == "user" },
      )
      assertEquals(
        1,
        c.uiState.value.messages.count { it.role == "user" && it.text == text },
      )
      // Reopen trio: single user row, complete logical family, no INTERRUPTED.
      assertTrue(
        "claimed family must not dangle",
        dev.librepocket.agent.ui.chat.findDanglingRunIds(store.events).isEmpty(),
      )
      assertTrue(c.interruptedTranscriptRunIds().isEmpty())
      assertEquals("claimed host runs exactly once, no resend", 1, provider.streamCalls.get())
    } finally {
      writerGate.complete(Unit)
      ghostGate.complete(Unit)
      hookRelease.complete(Unit)
      c.afterAdmissionSendForTest = null
      c.close()
    }
  }

  /**
   * R8-1 promotion CE fuzzy: the follow-up send was attempted (`attempted == true`,
   * `sent == false` via the deterministic post-send proxy, guaranteed-admitted —
   * stronger than production's suspended-send CE race) when the predecessor host
   * is cancelled before the timeout. Must retain promotion UNKNOWN ownership
   * (same id, drain + same-opId blocked, `queuedRecoveryRequired == false`),
   * never `failPromotion`, so the late commit claims under the SAME id with a
   * single ledger row (no ghost double-column). Large [acceptTimeoutMs] excludes
   * the timeout path: only the host CE fires.
   */
  @Test
  fun promotionCeFuzzyKeepsUnknownAndClaimsSingleRow() {
    val store = DelaySessionStore()
    val inner = SessionTranscriptSink(store, store.sessionId)
    val firstGate = CompletableDeferred<Unit>()
    val followStoreGate = CompletableDeferred<Unit>()
    val ghostEntered = AtomicBoolean(false)
    val followText = "follow-CE-fuzzy"
    val blockingSink = object : TranscriptSink by inner {
      override suspend fun onTurnStarted(runId: String, text: String) {
        if (text == followText) {
          ghostEntered.set(true)
          followStoreGate.await()
        }
        inner.onTurnStarted(runId, text)
      }
    }
    val provider = LedgerFakeProvider { request ->
      flow {
        val lastUser = request.messages.lastOrNull { it.role == "user" }?.text ?: ""
        if (lastUser.contains("first")) firstGate.await()
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    }
    var n = 0
    val c = TurnController(
      provider = provider,
      policy = LedgerAllowPolicy(),
      transcript = blockingSink,
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "r8promo-${n++}" },
      acceptTimeoutMs = 10_000L,
    )
    val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val afterSendEntered = AtomicBoolean(false)
    val hookRelease = CompletableDeferred<Unit>()
    try {
      val first = outer.async { c.send("first") }
      runBlocking {
        withTimeout(5_000) {
          while (provider.streamCalls.get() != 1) delay(10)
        }
      }
      assertEquals(TurnStart.Queued, runBlocking { c.startOrEnqueue(followText, opId = 901L) })
      // Park ONLY the promotion ghost post-send-pre-flag (deterministic proxy
      // for the suspended-send CE flag shape: attempted true, sent false,
      // admitted). Installed in beforePromotionAwait (after the predecessor
      // terminals already acked) so the predecessor's own core writes never
      // hit this global hook (writeCore funnels through writeCoreTracked and
      // would otherwise park the terminal and stick the ghost behind its gate).
      c.beforePromotionAwaitForTest = {
        c.afterAdmissionSendForTest = { _ ->
          afterSendEntered.set(true)
          hookRelease.await()
        }
      }
      firstGate.complete(Unit)
      runBlocking {
        withTimeout(5_000) { while (!afterSendEntered.get()) delay(10) }
        withTimeout(5_000) { while (!ghostEntered.get()) delay(10) }
        withTimeout(5_000) { while (c.pendingTranscriptRunIds().isEmpty()) delay(10) }
      }
      assertFalse("promotion must be parked in the post-send hook", first.isCompleted)
      // Host CE while fuzzy (attempted, sent false, admitted).
      c.cancel()
      runBlocking { withTimeout(5_000) { first.join() } }
      runBlocking {
        withTimeout(5_000) { while (!c.hasPendingAdmission()) delay(10) }
      }
      // UNKNOWN ownership: pending, drain + same-opId blocked, no recovery.
      assertTrue(c.hasPendingAdmission())
      assertEquals(ChatStatus.ERROR, c.uiState.value.status)
      assertEquals("transcript store pending", c.uiState.value.error)
      assertFalse(
        "promotion UNKNOWN must not project recovery",
        c.uiState.value.queuedRecoveryRequired,
      )
      assertEquals(1, c.uiState.value.pendingSteerCount)
      assertEquals(listOf("first"), c.uiState.value.messages.filter { it.role == "user" }.map { it.text })
      assertTrue(
        "fuzzy CE must not mark INTERRUPTED before settle",
        c.interruptedTranscriptRunIds().isEmpty(),
      )
      val ghost = c.pendingTranscriptRunIds().single()
      try {
        c.drainQueued()
        fail("drainQueued during promotion UNKNOWN must throw TranscriptPendingException")
      } catch (_: TranscriptPendingException) {
        // Expected: ownership retained, no steal.
      }
      try {
        runBlocking { withTimeout(5_000) { c.startOrEnqueue(followText, opId = 901L) } }
        fail("same-opId retry during promotion UNKNOWN must throw TranscriptPendingException")
      } catch (_: TranscriptPendingException) {
        // Expected: ghost already owns opId 901.
      }
      assertEquals(listOf("first"), c.uiState.value.messages.filter { it.role == "user" }.map { it.text })
      assertEquals(1, provider.streamCalls.get())
      assertTrue("ghost must not have persisted yet", store.events.none { it.kind == "user" && it.text == followText })
      // Late commit: release the ghost delegate + hook, settle claims the ghost
      // under the SAME logical id (Lock-2 commit, no resend).
      c.afterAdmissionSendForTest = null
      followStoreGate.complete(Unit)
      hookRelease.complete(Unit)
      runBlocking {
        withTimeout(8_000) {
          while (c.hasPendingAdmission()) {
            c.settlePendingAdmission(500L)
            delay(25)
          }
        }
      }
      assertTrue(
        "claimed ghost must not be INTERRUPTED",
        c.interruptedTranscriptRunIds().isEmpty(),
      )
      val followRows = store.events.filter { it.kind == "user" && it.text == followText }
      assertEquals("claimed ghost keeps exactly one follow row (no ghost double-column)", 1, followRows.size)
      assertEquals(ghost, followRows.single().runId)
      assertEquals(2, store.events.count { it.kind == "user" })
      // The claimed host runs the follow-up to completion (single provider call).
      runBlocking {
        withTimeout(8_000) {
          while (c.uiState.value.status != ChatStatus.IDLE) delay(10)
        }
        withTimeout(5_000) { c.flushTranscript() }
      }
      assertEquals(2, store.events.count { it.kind == "user" })
      assertEquals(
        1,
        c.uiState.value.messages.count { it.role == "user" && it.text == followText },
      )
      assertEquals("follow must reach the provider exactly once", 2, provider.streamCalls.get())
      assertTrue(c.drainQueued().isEmpty())
      assertEquals(0, c.uiState.value.pendingSteerCount)
      assertFalse(c.uiState.value.queuedRecoveryRequired)
      // Reopen trio: single follow row, complete family, no INTERRUPTED.
      assertTrue(
        "claimed family must not dangle",
        dev.librepocket.agent.ui.chat.findDanglingRunIds(store.events).isEmpty(),
      )
      assertTrue(c.interruptedTranscriptRunIds().isEmpty())
    } finally {
      firstGate.complete(Unit)
      followStoreGate.complete(Unit)
      hookRelease.complete(Unit)
      c.afterAdmissionSendForTest = null
      c.beforePromotionAwaitForTest = null
      outer.cancel()
      c.close()
    }
  }

  /**
   * R8-1 contrast (anti-over-UNKNOWN): a host CE before the channel send
   * (`!sent && !attempted`, parked in the pre-send gate) is proven
   * never-admitted and must stay fail-closed (`failPromotion`, FIFO retained
   * with recovery, never UNKNOWN). Same wiring as the fuzzy main test, only
   * the park point differs.
   */
  @Test
  fun promotionCeBeforeSendStaysFailClosed() {
    val store = DelaySessionStore()
    val inner = SessionTranscriptSink(store, store.sessionId)
    val firstGate = CompletableDeferred<Unit>()
    val followText = "follow-CE-proven"
    val blockingSink = object : TranscriptSink by inner {
      override suspend fun onTurnStarted(runId: String, text: String) {
        inner.onTurnStarted(runId, text)
      }
    }
    val provider = LedgerFakeProvider { request ->
      flow {
        val lastUser = request.messages.lastOrNull { it.role == "user" }?.text ?: ""
        if (lastUser.contains("first")) firstGate.await()
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    }
    var n = 0
    val c = TurnController(
      provider = provider,
      policy = LedgerAllowPolicy(),
      transcript = blockingSink,
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "r8promop-${n++}" },
      acceptTimeoutMs = 10_000L,
    )
    val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val beforeSendEntered = AtomicBoolean(false)
    val beforeGate = CompletableDeferred<Unit>()
    try {
      val first = outer.async { c.send("first") }
      runBlocking {
        withTimeout(5_000) {
          while (provider.streamCalls.get() != 1) delay(10)
        }
      }
      assertEquals(TurnStart.Queued, runBlocking { c.startOrEnqueue(followText, opId = 902L) })
      // Park BEFORE the promotion send (proven never-attempted): the CE below
      // must not become UNKNOWN. Installed in beforePromotionAwait so the
      // predecessor terminals (same global beforeSend hook) never park here.
      c.beforePromotionAwaitForTest = {
        c.beforeAdmissionSendForTest = {
          beforeSendEntered.set(true)
          beforeGate.await()
        }
      }
      firstGate.complete(Unit)
      runBlocking {
        withTimeout(5_000) { while (!beforeSendEntered.get()) delay(10) }
      }
      // Let the waiter settle into the gate (send provably not attempted).
      Thread.sleep(200)
      assertFalse("promotion must be parked before send", first.isCompleted)
      c.cancel()
      beforeGate.complete(Unit)
      runBlocking { withTimeout(5_000) { first.join() } }
      // Fail-closed: never UNKNOWN, FIFO retained with recovery for explicit
      // reclaim (drainable, not Pending-blocked).
      assertFalse("proven before-send CE must not become UNKNOWN", c.hasPendingAdmission())
      assertTrue(c.pendingTranscriptRunIds().isEmpty())
      assertEquals(1, c.uiState.value.pendingSteerCount)
      assertTrue(
        "proven promotion CE retains the FIFO for explicit recovery",
        c.uiState.value.queuedRecoveryRequired,
      )
      assertEquals(listOf("first"), c.uiState.value.messages.filter { it.role == "user" }.map { it.text })
      assertEquals("cancelled follow-up is never launched", 1, provider.streamCalls.get())
      assertTrue(
        "proven ghost was never admitted, so nothing follows to persist",
        store.events.none { it.kind == "user" && it.text == followText },
      )
      assertEquals(listOf(QueuedIntent(902L, followText)), c.drainQueued())
      assertTrue(c.drainQueued().isEmpty())
    } finally {
      firstGate.complete(Unit)
      beforeGate.complete(Unit)
      c.beforeAdmissionSendForTest = null
      c.beforePromotionAwaitForTest = null
      outer.cancel()
      c.close()
    }
  }

  /**
   * R9-1 undelivered barrier proof: a CE racing a truly suspended send
   * (attempted, physically never admitted — buffer full behind a parked
   * writer, no receiver) is fuzzy and maps to UNKNOWN, but the ghost was never
   * delivered so a successful barrier flush proves non-delivery (admitted
   * ghosts always clear pending before the barrier drains, FIFO). Settle
   * resolves in the OPEN session via tryResolveUndelivered — no seal needed:
   * ownership released, true INTERRUPTED mark, still no ledger row and no
   * launch, and the same opId is immediately retryable in the same session
   * with a fresh id (exactly one row, single provider call, retry id clean).
   * Contrast: fuzzy DELIVERED still claims (see
   * fullChannelResumedSendCancelLateCommitRetryReopenSingleRow — flush + pending
   * gone → claim, no false INTERRUPTED).
   */
  @OptIn(DelicateCoroutinesApi::class)
  @Test
  fun fuzzyUndeliveredBarrierRecoversInOpenSession() {
    val store = DelaySessionStore()
    val inner = SessionTranscriptSink(store, store.sessionId)
    val writerGate = CompletableDeferred<Unit>()
    val blockerEntered = CompletableDeferred<Unit>()
    val blockingSink = object : TranscriptSink by inner {
      override suspend fun onSteerQueued(text: String) {
        if (text == "blocker") {
          blockerEntered.complete(Unit)
          writerGate.await()
        }
        inner.onSteerQueued(text)
      }
    }
    val provider = LedgerFakeProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    }
    var n = 0
    val c = TurnController(
      provider = provider,
      policy = LedgerAllowPolicy(),
      transcript = blockingSink,
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "r9guard-${n++}" },
      acceptTimeoutMs = 10_000L,
      transcriptCapacity = 1,
    )
    val beforeSendEntered = AtomicBoolean(false)
    c.beforeAdmissionSendForTest = {
      beforeSendEntered.set(true)
    }
    val text = "fuzzy guard text"
    val opId = 903L
    try {
      // Fill the channel behind the parked writer: blocker (taken, parked) +
      // filler (buffered, capacity 1). The next admission send suspends.
      c.offerSteerForTest("blocker")
      runBlocking {
        withTimeout(5_000) { blockerEntered.await() }
      }
      c.offerSteerForTest("filler")
      val deferred = GlobalScope.async(Dispatchers.Default) {
        c.startOrEnqueue(text, opId = opId)
      }
      runBlocking {
        withTimeout(5_000) { while (!beforeSendEntered.get()) delay(10) }
        withTimeout(5_000) { while (c.pendingTranscriptRunIds().isEmpty()) delay(10) }
      }
      // Let the waiter suspend inside channel.send (attempt recorded, buffer
      // full, writer parked); the delay only lands the suspension, never
      // guesses `sent`.
      Thread.sleep(200)
      assertFalse("admission must still be suspended in send", deferred.isCompleted)
      deferred.cancel()
      try {
        runBlocking {
          withTimeout(5_000) { deferred.await() }
        }
        fail("fuzzy suspended-send cancel must throw TranscriptPendingException")
      } catch (e: TranscriptPendingException) {
        // Expected UNKNOWN path (fuzzy, not NOT_ADMITTED).
      } catch (e: CancellationException) {
        fail("fuzzy cancel must not surface CancellationException, got $e")
      }
      c.beforeAdmissionSendForTest = null
      // UNKNOWN ownership with the pending mark retained, never INTERRUPTED.
      assertTrue(c.hasPendingAdmission())
      val ghost = c.pendingTranscriptRunIds().single()
      assertTrue(c.interruptedTranscriptRunIds().isEmpty())
      assertEquals(1, c.uiState.value.messages.count { it.role == "user" && it.text == text })
      assertTrue("ghost must not have persisted yet", store.events.none { it.kind == "user" })
      // Release the writer so the barrier can drain blocker + filler. The
      // ghost never entered, so flush succeeds with the id still pending —
      // R9-1 non-delivery proof, resolved without sealing.
      writerGate.complete(Unit)
      val settled = runBlocking {
        withTimeout(5_000) { c.settlePendingAdmission(500L) }
      }
      assertTrue("fuzzy-undelivered barrier proof must resolve in the open session", settled)
      assertFalse("resolved UNKNOWN must release ownership", c.hasPendingAdmission())
      assertTrue(
        "resolved ghost must release the pending mark",
        c.pendingTranscriptRunIds().isEmpty(),
      )
      assertTrue(
        "resolved ghost carries a true INTERRUPTED mark (never claimed)",
        c.interruptedTranscriptRunIds().contains(ghost),
      )
      assertTrue("resolved fuzzy entry was never claimed", store.events.none { it.kind == "user" })
      assertEquals("resolved fuzzy entry never launches", 0, provider.streamCalls.get())
      assertTrue(
        "convert must roll back the optimistic UI row",
        c.uiState.value.messages.none { it.role == "user" && it.text == text },
      )
      assertEquals("transcript store unavailable", c.uiState.value.error)
      // Same-session retry with the SAME opId admits immediately (no close, no
      // seal): fresh logical id, exactly one row, single provider call.
      val retry = runBlocking {
        withTimeout(5_000) { c.startOrEnqueue(text, opId = opId) }
      }
      assertTrue("same-session retry after barrier proof must admit", retry is TurnStart.Started)
      runBlocking {
        (retry as TurnStart.Started).host.join()
        withTimeout(5_000) { c.flushTranscript() }
      }
      val users = store.events.filter { it.kind == "user" }
      assertEquals("retry keeps exactly one user row", 1, users.size)
      assertEquals(text, users.single().text)
      assertEquals("retry runs the provider exactly once", 1, provider.streamCalls.get())
      val retryId = users.single().runId
      assertTrue(
        "retry id must differ from the undelivered ghost",
        retryId != ghost,
      )
      assertTrue(
        "undelivered ghost stays marked",
        c.interruptedTranscriptRunIds().contains(ghost),
      )
      assertTrue(
        "retry id must not carry a false INTERRUPTED mark",
        !c.interruptedTranscriptRunIds().contains(retryId),
      )
      assertEquals(
        1,
        c.uiState.value.messages.count { it.role == "user" && it.text == text },
      )
    } finally {
      writerGate.complete(Unit)
      c.beforeAdmissionSendForTest = null
      c.close()
    }
  }

  /**
   * Round 10 M1-direct: writer blocked + long drain — a direct waiter sealed
   * before its durable await resolves must never rebuild UNKNOWN. Close wins
   * the race: the waiter observes the seal on its bounded wait, rolls back
   * the optimistic UI row and throws TranscriptDurabilityException (retryable
   * fail-closed, not Pending-blocked), with hasPendingAdmission() false.
   */
  @OptIn(DelicateCoroutinesApi::class)
  @Test
  fun closeDuringDirectAwaitNeverRebuildsUnknownAfterSeal() {
    val store = DelaySessionStore()
    val inner = SessionTranscriptSink(store, store.sessionId)
    val writerGate = CompletableDeferred<Unit>()
    val delegateEntered = AtomicBoolean(false)
    val blockingSink = object : TranscriptSink by inner {
      override suspend fun onTurnStarted(runId: String, text: String) {
        delegateEntered.set(true)
        writerGate.await()
        inner.onTurnStarted(runId, text)
      }
    }
    val provider = LedgerFakeProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    }
    var n = 0
    val c = TurnController(
      provider = provider,
      policy = LedgerAllowPolicy(),
      transcript = blockingSink,
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "m1direct-${n++}" },
      acceptTimeoutMs = 2_000L,
    )
    val text = "m1 direct sealed text"
    val opId = 911L
    try {
      val deferred = GlobalScope.async(Dispatchers.Default) {
        c.startOrEnqueue(text, opId = opId)
      }
      // The entry is admitted (writer owns it, sent == true) and the waiter is
      // parked in ack.await — still uncompleted, fate still open.
      runBlocking {
        withTimeout(5_000) { while (!delegateEntered.get()) delay(10) }
        withTimeout(5_000) { while (c.pendingTranscriptRunIds().isEmpty()) delay(10) }
      }
      assertFalse("admission must still be parked in ack.await", deferred.isCompleted)
      // Long drain keeps the shutdown behind the blocked writer, so the waiter
      // observes the seal on its bounded wait (not via a drain-timeout
      // interrupt that could mask the M1 path).
      c.close(drainTimeoutMs = 8_000L)
      val failure = runBlocking {
        withTimeout(10_000) {
          try {
            deferred.await()
            null
          } catch (e: Exception) {
            e
          }
        }
      }
      assertTrue(
        "sealed waiter must throw TranscriptDurabilityException, got $failure",
        failure is TranscriptDurabilityException,
      )
      assertFalse("sealed waiter must not rebuild UNKNOWN", c.hasPendingAdmission())
      assertTrue(
        "sealed waiter must roll back the optimistic UI row",
        c.uiState.value.messages.none { it.role == "user" && it.text == text },
      )
      assertEquals("transcript store unavailable", c.uiState.value.error)
      assertTrue(
        "ghost must not have persisted while the writer is blocked",
        store.events.none { it.kind == "user" },
      )
      assertEquals("sealed admission must never launch", 0, provider.streamCalls.get())
      // Release the writer: the orphan ghost drains (at most one row), still
      // no host launch, still no UNKNOWN.
      writerGate.complete(Unit)
      val drained = runBlocking { withTimeout(10_000) { c.flushTranscript() } }
      assertTrue("drain must complete after the writer unblocks", drained)
      assertFalse(c.hasPendingAdmission())
      assertTrue(
        "orphan ghost survives as at most one row (no duplicate, no launch)",
        store.events.count { it.kind == "user" } <= 1,
      )
      assertEquals("sealed admission must never launch", 0, provider.streamCalls.get())
      assertTrue(
        "rolled-back row stays rolled back after the drain",
        c.uiState.value.messages.none { it.role == "user" && it.text == text },
      )
    } finally {
      writerGate.complete(Unit)
      c.close()
    }
  }

  /**
   * Round 10 M1-promotion: the follow ghost send completed (writer owns the
   * entry, await parked post-send-pre-flag) before close seals. The host
   * fuzzy-CE must go through failPromotion — never rebuild UNKNOWN
   * (hasPendingAdmission() false) — with no follow launch and no second row.
   * Assertions run after the host join while the writer is still blocked, so
   * no background settle can mask the fail-closed path.
   */
  @OptIn(DelicateCoroutinesApi::class)
  @Test
  fun closeDuringPromotionGhostSendFailsClosedWithoutUnknown() {
    val store = DelaySessionStore()
    val inner = SessionTranscriptSink(store, store.sessionId)
    val firstGate = CompletableDeferred<Unit>()
    val followStoreGate = CompletableDeferred<Unit>()
    val ghostEntered = AtomicBoolean(false)
    val followText = "follow-M1-sealed"
    val blockingSink = object : TranscriptSink by inner {
      override suspend fun onTurnStarted(runId: String, text: String) {
        if (text == followText) {
          ghostEntered.set(true)
          followStoreGate.await()
        }
        inner.onTurnStarted(runId, text)
      }
    }
    val provider = LedgerFakeProvider { request ->
      flow {
        val lastUser = request.messages.lastOrNull { it.role == "user" }?.text ?: ""
        if (lastUser.contains("first")) firstGate.await()
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    }
    var n = 0
    val c = TurnController(
      provider = provider,
      policy = LedgerAllowPolicy(),
      transcript = blockingSink,
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "m1promo-${n++}" },
      acceptTimeoutMs = 10_000L,
    )
    val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val afterSendEntered = AtomicBoolean(false)
    val hookRelease = CompletableDeferred<Unit>()
    try {
      val first = outer.async { c.send("first") }
      runBlocking {
        withTimeout(5_000) { while (provider.streamCalls.get() != 1) delay(10) }
      }
      assertEquals(TurnStart.Queued, runBlocking { c.startOrEnqueue(followText, opId = 912L) })
      // Park ONLY the promotion ghost post-send-pre-flag (deterministic proxy
      // for the suspended-send CE flag shape: attempted true, sent false,
      // admitted) — installed in beforePromotionAwait so the predecessor's own
      // core writes never hit this global hook.
      c.beforePromotionAwaitForTest = {
        c.afterAdmissionSendForTest = { _ ->
          afterSendEntered.set(true)
          hookRelease.await()
        }
      }
      firstGate.complete(Unit)
      // Ghost send completed: the channel send returned (writer owns the entry,
      // parked in the delegate) and the await is parked post-send-pre-flag.
      runBlocking {
        withTimeout(5_000) { while (!afterSendEntered.get()) delay(10) }
        withTimeout(5_000) { while (!ghostEntered.get()) delay(10) }
        withTimeout(5_000) { while (c.pendingTranscriptRunIds().isEmpty()) delay(10) }
      }
      assertFalse("promotion must be parked in the post-send hook", first.isCompleted)
      // Seal under a long drain while the ghost is in flight: the host
      // fuzzy-CE must fail closed via failPromotion, never rebuild UNKNOWN.
      c.close(drainTimeoutMs = 8_000L)
      runBlocking { withTimeout(5_000) { first.join() } }
      // Join-then-assert while the writer is still blocked: the ghost is still
      // writer-owned and unclaimed, so no settle/claim can mask the path.
      assertFalse("sealed promotion must not rebuild UNKNOWN", c.hasPendingAdmission())
      assertTrue(
        "ghost must still be writer-owned behind the blocked writer",
        c.pendingTranscriptRunIds().isNotEmpty(),
      )
      assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
      assertTrue(c.drainQueued().isEmpty())
      assertEquals(listOf("first"), c.uiState.value.messages.filter { it.role == "user" }.map { it.text })
      assertEquals("sealed promotion must never launch the follow-up", 1, provider.streamCalls.get())
      assertTrue(
        "ghost must not have persisted while the writer is blocked",
        store.events.none { it.kind == "user" && it.text == followText },
      )
      // Release the writer: the orphan ghost drains (at most one row), still
      // no follow launch, still no UNKNOWN.
      followStoreGate.complete(Unit)
      hookRelease.complete(Unit)
      val drained = runBlocking { withTimeout(10_000) { c.flushTranscript() } }
      assertTrue("drain must complete after the writer unblocks", drained)
      assertFalse(c.hasPendingAdmission())
      assertTrue(
        "acked ghost survives as at most one orphan row (no duplicate, no follow-up launch)",
        store.events.count { it.kind == "user" && it.text == followText } <= 1,
      )
      assertEquals("sealed promotion must never launch the follow-up", 1, provider.streamCalls.get())
      assertEquals(listOf("first"), c.uiState.value.messages.filter { it.role == "user" }.map { it.text })
      assertTrue(c.drainQueued().isEmpty())
    } finally {
      firstGate.complete(Unit)
      followStoreGate.complete(Unit)
      hookRelease.complete(Unit)
      c.afterAdmissionSendForTest = null
      c.beforePromotionAwaitForTest = null
      outer.cancel()
      c.close()
    }
  }
}
