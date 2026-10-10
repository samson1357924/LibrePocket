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
    val blockingSink = object : dev.librepocket.chat.TranscriptSink {
      override suspend fun onTurnStarted(runId: String, text: String) {
        gate.await()
      }
      override suspend fun onTurnSucceeded(runId: String, text: String) = Unit
      override suspend fun onTurnFailed(runId: String, error: String) = Unit
      override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
      override suspend fun onTurnCancelled(runId: String, partialText: String) = Unit
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
      newId = { "stalled-${n++}" },
    )
    try {
      val admission = runBlocking { c.startOrEnqueue("hi", opId = 9) }
      assertTrue(admission is TurnStart.Started)
      // The host is parked on the durable user-row ack behind the gate.
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
      override suspend fun onTurnStarted(runId: String, text: String) {
        gate.await()
      }
      override suspend fun onTurnSucceeded(runId: String, text: String) = Unit
      override suspend fun onTurnFailed(runId: String, error: String) = Unit
      override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
      override suspend fun onTurnCancelled(runId: String, partialText: String) = Unit
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
    )

    // Call startOrEnqueue while dispatcher is paused -> Started, host coroutine never executed
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
}
