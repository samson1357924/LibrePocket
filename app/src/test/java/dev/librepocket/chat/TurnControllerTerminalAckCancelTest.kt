package dev.librepocket.chat

import dev.librepocket.agent.ui.chat.findDanglingRunIds
import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol as ProviderProto
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.JsonlCodec
import dev.librepocket.session.SessionTranscriptSink
import dev.librepocket.session.TranscriptEvent
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
import org.junit.Assert.assertTrue
import org.junit.Test

private class AckCancelProvider(
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

private class AckCancelPolicy : PolicyStore {
  override fun evaluate(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
  override suspend fun setRule(rule: PolicyRule) = Unit
  override suspend fun removeRule(pattern: String) = Unit
  override suspend fun listRules(): List<PolicyRule> = emptyList()
  override suspend fun evaluateFresh(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

/**
 * D15-2 terminal-ack cancel grid: `terminalizedAttempts.add` runs only after
 * the terminal `writeCore` ack returns, so a host cancelled in the commit→ack
 * window (writer committed, ack still in flight) used to miss the set and
 * write a duplicate assistant row for the same attempt. The sink now owns the
 * synchronous submit truth (pending snapshot + commit counts) and the
 * controller records attempt-dimension terminal intent before admission, so
 * the cancel handler tells SUBMITTING vs COMMITTED vs FAILED without
 * suspending: suppressed finals write zero new rows, suppressed intermediate
 * partials (non-final, family dangling) write one system-kind final mark.
 *
 * 9 cells (terminal kind × cancel timing) with four-side assertions each:
 * (a) ledger kinds, (b) same-attempt single assistant (no duplicate) +
 * text/flags, (c) family closed (no dangling, final flags), (d) provider ran
 * once and the UI keeps one assistant block (legal retry preserved: cancel
 * wins, nothing deleted, store append-only).
 *
 * Timings: commit前 = cancel while the terminal store write is blocked
 * (SUBMITTING); commit后ack前 = writer committed but the ack is held via
 * [TurnController.afterCommitBeforeAckForTest] (COMMITTED); ack后 = terminal
 * fully acked — paused post-ack hook for success/final, backoff sleeper for
 * the retryable intermediate (with its retry notice, like the Stage F gap).
 */
class TurnControllerTerminalAckCancelTest {

  /** Store that can hold the terminal assistant write in its commit window. */
  private class GatedStore : FakeSessionStore() {
    @Volatile var blockAssistant = false
    val assistantEntered = CountDownLatch(1)
    val assistantGate = CompletableDeferred<Unit>()
    override suspend fun appendEvent(event: TranscriptEvent): Long {
      if (event.kind == "assistant" && blockAssistant) {
        assistantEntered.countDown()
        assistantGate.await()
      }
      return super.appendEvent(event)
    }
  }

  private fun controller(
    provider: AckCancelProvider,
    sink: SessionTranscriptSink,
    n: AtomicInteger,
    sleeper: suspend (Long) -> Unit = {},
    retry: TurnRetryConfig = TurnRetryConfig(),
  ): TurnController = TurnController(
    provider = provider,
    policy = AckCancelPolicy(),
    transcript = sink,
    retryConfig = retry,
    dispatcher = Dispatchers.Default,
    sleeper = sleeper,
    newId = { "ack-${n.incrementAndGet() - 1}" },
  )

  private fun startedHost(c: TurnController): kotlinx.coroutines.Job {
    val admission = runBlocking { c.startOrEnqueue("hi") }
    assertTrue("admission must start, was $admission", admission is TurnStart.Started)
    return (admission as TurnStart.Started).host
  }

  private fun joinHost(host: kotlinx.coroutines.Job) {
    runBlocking { withTimeout(8_000) { host.join() } }
  }

  private fun flush(c: TurnController) {
    runBlocking { withTimeout(8_000) { c.flushTranscript() } }
  }

  // ---- success × commit前: SUBMITTING final → zero new rows. ----

  @Test fun successCancelBeforeCommitWritesZeroNew() {
    val store = GatedStore().apply { blockAssistant = true }
    val sid = runBlocking { store.createSession("hi", "m") }
    val provider = AckCancelProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n)
    try {
      val host = startedHost(c)
      assertTrue("terminal write never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      Thread.sleep(300)
      store.assistantGate.complete(Unit)
      joinHost(host)
      flush(c)
      // (a) ledger: one user + one success, no cancel row.
      assertEquals(listOf("user", "assistant"), store.events.map { it.kind })
      // (b) same attempt owns exactly one assistant row, final, complete text.
      val attemptId = store.events[0].runId
      val assistants = store.events.filter { it.kind == "assistant" && it.runId == attemptId }
      assertEquals("duplicate assistant for $attemptId: ${store.events}", 1, assistants.size)
      assertEquals("ok", assistants.single().text)
      assertEquals(true, assistants.single().isFinal)
      assertEquals(false, assistants.single().isPartial)
      // (c) family closed by the success final.
      assertTrue("dangling: ${store.events}", findDanglingRunIds(store.events).isEmpty())
      // (d) single provider attempt, one UI assistant block.
      assertEquals(1, provider.streamCalls.get())
      assertEquals(1, c.uiState.value.messages.count { it.role == "assistant" })
    } finally {
      store.assistantGate.complete(Unit)
      c.close()
    }
  }

  // ---- success × commit后ack前: COMMITTED final → zero new rows. ----

  @Test fun successCancelBetweenCommitAndAckWritesZeroNew() {
    val store = FakeSessionStore()
    val sid = runBlocking { store.createSession("hi", "m") }
    val provider = AckCancelProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n)
    val commits = AtomicInteger(0)
    val committedLatch = CountDownLatch(1)
    val releaseWriter = CountDownLatch(1)
    c.afterCommitBeforeAckForTest = {
      if (commits.incrementAndGet() == 2) {
        committedLatch.countDown()
        releaseWriter.await(5, TimeUnit.SECONDS)
      }
    }
    try {
      val host = startedHost(c)
      assertTrue("terminal never committed", committedLatch.await(5, TimeUnit.SECONDS))
      c.cancel()
      Thread.sleep(300)
      releaseWriter.countDown()
      joinHost(host)
      flush(c)
      // (a) ledger: one user + one success, no cancel row.
      assertEquals(listOf("user", "assistant"), store.events.map { it.kind })
      // (b) same attempt owns exactly one final assistant row.
      val attemptId = store.events[0].runId
      val assistants = store.events.filter { it.kind == "assistant" && it.runId == attemptId }
      assertEquals("duplicate assistant for $attemptId: ${store.events}", 1, assistants.size)
      assertEquals("ok", assistants.single().text)
      assertEquals(true, assistants.single().isFinal)
      // (c) family closed by the success final.
      assertTrue("dangling: ${store.events}", findDanglingRunIds(store.events).isEmpty())
      // (d) single provider attempt, one UI assistant block.
      assertEquals(1, provider.streamCalls.get())
      assertEquals(1, c.uiState.value.messages.count { it.role == "assistant" })
      // Export/import consistency at the codec level (StageF pattern).
      for (e in store.events) {
        val back = JsonlCodec.decode(1, JsonlCodec.encode(e.copy(seq = e.seq.coerceAtLeast(1))))
        assertEquals("isFinal must round-trip for ${e.kind}", e.isFinal, back.isFinal)
      }
    } finally {
      releaseWriter.countDown()
      c.close()
    }
  }

  // ---- success × ack后: acked final → zero new rows. ----

  @Test fun successCancelAfterAckWritesZeroNew() {
    val store = FakeSessionStore()
    val sid = runBlocking { store.createSession("hi", "m") }
    val provider = AckCancelProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n)
    val ackedLatch = CountDownLatch(1)
    val holdHost = CompletableDeferred<Unit>()
    c.afterTerminalAckForTest = {
      ackedLatch.countDown()
      holdHost.await()
    }
    try {
      val host = startedHost(c)
      assertTrue("terminal never acked", ackedLatch.await(5, TimeUnit.SECONDS))
      c.cancel()
      joinHost(host)
      flush(c)
      // (a) ledger: one user + one success, no cancel row.
      assertEquals(listOf("user", "assistant"), store.events.map { it.kind })
      // (b) same attempt owns exactly one final assistant row.
      val attemptId = store.events[0].runId
      val assistants = store.events.filter { it.kind == "assistant" && it.runId == attemptId }
      assertEquals("duplicate assistant for $attemptId: ${store.events}", 1, assistants.size)
      assertEquals("ok", assistants.single().text)
      assertEquals(true, assistants.single().isFinal)
      // (c) family closed by the success final.
      assertTrue("dangling: ${store.events}", findDanglingRunIds(store.events).isEmpty())
      // (d) single provider attempt, one UI assistant block.
      assertEquals(1, provider.streamCalls.get())
      assertEquals(1, c.uiState.value.messages.count { it.role == "assistant" })
    } finally {
      holdHost.complete(Unit)
      c.close()
    }
  }

  // ---- terminal-failure × commit前: SUBMITTING final → zero new rows. ----

  @Test fun terminalFailureCancelBeforeCommitWritesZeroNew() {
    val store = GatedStore().apply { blockAssistant = true }
    val sid = runBlocking { store.createSession("hi", "m") }
    val provider = AckCancelProvider {
      flow { emit(StreamEvent.Failed("boom", retryable = false)) }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n)
    try {
      val host = startedHost(c)
      assertTrue("terminal write never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      Thread.sleep(300)
      store.assistantGate.complete(Unit)
      joinHost(host)
      flush(c)
      // (a) ledger: one user + one terminal failure, no cancel row.
      assertEquals(listOf("user", "assistant"), store.events.map { it.kind })
      // (b) same attempt owns exactly one final partial row with the reason.
      val attemptId = store.events[0].runId
      val assistants = store.events.filter { it.kind == "assistant" && it.runId == attemptId }
      assertEquals("duplicate assistant for $attemptId: ${store.events}", 1, assistants.size)
      assertEquals(true, assistants.single().isPartial)
      assertEquals(true, assistants.single().isFinal)
      assertEquals("boom", assistants.single().failureReason)
      // (c) family closed by the terminal-failure final.
      assertTrue("dangling: ${store.events}", findDanglingRunIds(store.events).isEmpty())
      // (d) single provider attempt, one UI assistant block (failed fragment kept).
      assertEquals(1, provider.streamCalls.get())
      assertEquals(1, c.uiState.value.messages.count { it.role == "assistant" })
    } finally {
      store.assistantGate.complete(Unit)
      c.close()
    }
  }

  // ---- terminal-failure × commit后ack前: COMMITTED final → zero new rows. ----

  @Test fun terminalFailureCancelBetweenCommitAndAckWritesZeroNew() {
    val store = FakeSessionStore()
    val sid = runBlocking { store.createSession("hi", "m") }
    val provider = AckCancelProvider {
      flow { emit(StreamEvent.Failed("boom", retryable = false)) }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n)
    val commits = AtomicInteger(0)
    val committedLatch = CountDownLatch(1)
    val releaseWriter = CountDownLatch(1)
    c.afterCommitBeforeAckForTest = {
      if (commits.incrementAndGet() == 2) {
        committedLatch.countDown()
        releaseWriter.await(5, TimeUnit.SECONDS)
      }
    }
    try {
      val host = startedHost(c)
      assertTrue("terminal never committed", committedLatch.await(5, TimeUnit.SECONDS))
      c.cancel()
      Thread.sleep(300)
      releaseWriter.countDown()
      joinHost(host)
      flush(c)
      // (a) ledger: one user + one terminal failure, no cancel row.
      assertEquals(listOf("user", "assistant"), store.events.map { it.kind })
      // (b) same attempt owns exactly one final partial row.
      val attemptId = store.events[0].runId
      val assistants = store.events.filter { it.kind == "assistant" && it.runId == attemptId }
      assertEquals("duplicate assistant for $attemptId: ${store.events}", 1, assistants.size)
      assertEquals(true, assistants.single().isPartial)
      assertEquals(true, assistants.single().isFinal)
      assertEquals("boom", assistants.single().failureReason)
      // (c) family closed by the terminal-failure final.
      assertTrue("dangling: ${store.events}", findDanglingRunIds(store.events).isEmpty())
      // (d) single provider attempt, one UI assistant block.
      assertEquals(1, provider.streamCalls.get())
      assertEquals(1, c.uiState.value.messages.count { it.role == "assistant" })
      // Export/import consistency at the codec level (StageF pattern).
      for (e in store.events) {
        val back = JsonlCodec.decode(1, JsonlCodec.encode(e.copy(seq = e.seq.coerceAtLeast(1))))
        assertEquals("isFinal must round-trip for ${e.kind}", e.isFinal, back.isFinal)
      }
    } finally {
      releaseWriter.countDown()
      c.close()
    }
  }

  // ---- terminal-failure × ack后: acked final → zero new rows. ----

  @Test fun terminalFailureCancelAfterAckWritesZeroNew() {
    val store = FakeSessionStore()
    val sid = runBlocking { store.createSession("hi", "m") }
    val provider = AckCancelProvider {
      flow { emit(StreamEvent.Failed("boom", retryable = false)) }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n)
    val ackedLatch = CountDownLatch(1)
    val holdHost = CompletableDeferred<Unit>()
    c.afterTerminalAckForTest = {
      ackedLatch.countDown()
      holdHost.await()
    }
    try {
      val host = startedHost(c)
      assertTrue("terminal never acked", ackedLatch.await(5, TimeUnit.SECONDS))
      c.cancel()
      joinHost(host)
      flush(c)
      // (a) ledger: one user + one terminal failure, no cancel row.
      assertEquals(listOf("user", "assistant"), store.events.map { it.kind })
      // (b) same attempt owns exactly one final partial row.
      val attemptId = store.events[0].runId
      val assistants = store.events.filter { it.kind == "assistant" && it.runId == attemptId }
      assertEquals("duplicate assistant for $attemptId: ${store.events}", 1, assistants.size)
      assertEquals(true, assistants.single().isPartial)
      assertEquals(true, assistants.single().isFinal)
      assertEquals("boom", assistants.single().failureReason)
      // (c) family closed by the terminal-failure final.
      assertTrue("dangling: ${store.events}", findDanglingRunIds(store.events).isEmpty())
      // (d) single provider attempt, one UI assistant block.
      assertEquals(1, provider.streamCalls.get())
      assertEquals(1, c.uiState.value.messages.count { it.role == "assistant" })
    } finally {
      holdHost.complete(Unit)
      c.close()
    }
  }

  // ---- retryable-intermediate × commit前: SUBMITTING non-final → system mark. ----

  @Test fun intermediateCancelBeforeCommitWritesSystemMark() {
    val store = GatedStore().apply { blockAssistant = true }
    val sid = runBlocking { store.createSession("hi", "m") }
    val provider = AckCancelProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        emit(StreamEvent.Failed("boom", retryable = true))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(
      provider, SessionTranscriptSink(store, sid), n,
      retry = TurnRetryConfig(maxRetries = 1, retryDelaysMs = listOf(5_000L)),
    )
    try {
      val host = startedHost(c)
      assertTrue("terminal write never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      // Let the handler admit its system mark behind the held writer, then
      // release so the partial commits first and the mark lands after it.
      Thread.sleep(300)
      store.assistantGate.complete(Unit)
      joinHost(host)
      flush(c)
      // (a) ledger: partial then system mark (cancelled before the retry
      // notice, so no retry row); never a second assistant.
      assertEquals(listOf("user", "assistant", "system"), store.events.map { it.kind })
      // (b) same attempt owns exactly one assistant row: non-final partial.
      val attemptId = store.events[0].runId
      val assistants = store.events.filter { it.kind == "assistant" && it.runId == attemptId }
      assertEquals("duplicate assistant for $attemptId: ${store.events}", 1, assistants.size)
      assertEquals("half", assistants.single().text)
      assertEquals(true, assistants.single().isPartial)
      assertEquals("intermediate partial must stay non-final", false, assistants.single().isFinal)
      assertEquals("boom", assistants.single().failureReason)
      // (c) system mark closes the dangling family with the exact pattern.
      val mark = store.events[2]
      assertEquals(attemptId, mark.runId)
      assertEquals("turn $attemptId cancelled", mark.text)
      assertEquals(true, mark.isFinal)
      assertTrue("dangling: ${store.events}", findDanglingRunIds(store.events).isEmpty())
      // (d) retry never starts (cancel won), UI keeps the one partial block.
      assertEquals(1, provider.streamCalls.get())
      val uiAssistants = c.uiState.value.messages.filter { it.role == "assistant" }
      assertEquals(1, uiAssistants.size)
      assertEquals("half", uiAssistants.single().text)
    } finally {
      store.assistantGate.complete(Unit)
      c.close()
    }
  }

  // ---- retryable-intermediate × commit后ack前: COMMITTED non-final → system mark. ----

  @Test fun intermediateCancelBetweenCommitAndAckWritesSystemMark() {
    val store = FakeSessionStore()
    val sid = runBlocking { store.createSession("hi", "m") }
    val provider = AckCancelProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        emit(StreamEvent.Failed("boom", retryable = true))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(
      provider, SessionTranscriptSink(store, sid), n,
      retry = TurnRetryConfig(maxRetries = 1, retryDelaysMs = listOf(5_000L)),
    )
    val commits = AtomicInteger(0)
    val committedLatch = CountDownLatch(1)
    val releaseWriter = CountDownLatch(1)
    c.afterCommitBeforeAckForTest = {
      if (commits.incrementAndGet() == 2) {
        committedLatch.countDown()
        releaseWriter.await(5, TimeUnit.SECONDS)
      }
    }
    try {
      val host = startedHost(c)
      assertTrue("terminal never committed", committedLatch.await(5, TimeUnit.SECONDS))
      c.cancel()
      Thread.sleep(300)
      releaseWriter.countDown()
      joinHost(host)
      flush(c)
      // (a) ledger: partial then system mark (cancelled before the retry notice).
      assertEquals(listOf("user", "assistant", "system"), store.events.map { it.kind })
      // (b) same attempt owns exactly one non-final partial row.
      val attemptId = store.events[0].runId
      val assistants = store.events.filter { it.kind == "assistant" && it.runId == attemptId }
      assertEquals("duplicate assistant for $attemptId: ${store.events}", 1, assistants.size)
      assertEquals("half", assistants.single().text)
      assertEquals(true, assistants.single().isPartial)
      assertEquals("boom", assistants.single().failureReason)
      assertEquals("intermediate partial must stay non-final", false, assistants.single().isFinal)
      // (c) system mark closes the dangling family.
      val mark = store.events[2]
      assertEquals("turn $attemptId cancelled", mark.text)
      assertEquals(true, mark.isFinal)
      assertTrue("dangling: ${store.events}", findDanglingRunIds(store.events).isEmpty())
      // (d) retry never starts, UI keeps the one partial block.
      assertEquals(1, provider.streamCalls.get())
      assertEquals("half", c.uiState.value.messages.single { it.role == "assistant" }.text)
      // Export/import consistency at the codec level (StageF pattern).
      for (e in store.events) {
        val back = JsonlCodec.decode(1, JsonlCodec.encode(e.copy(seq = e.seq.coerceAtLeast(1))))
        assertEquals("isFinal must round-trip for ${e.kind}", e.isFinal, back.isFinal)
      }
    } finally {
      releaseWriter.countDown()
      c.close()
    }
  }

  // ---- retryable-intermediate × ack后 (backoff gap): acked non-final → system mark. ----

  @Test fun intermediateCancelAfterAckWritesSystemMark() {
    val store = FakeSessionStore()
    val sid = runBlocking { store.createSession("hi", "m") }
    val provider = AckCancelProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        emit(StreamEvent.Failed("boom", retryable = true))
      }
    }
    val n = AtomicInteger(0)
    val sleeperEntered = CountDownLatch(1)
    val sleeperGate = CompletableDeferred<Unit>()
    val c = controller(
      provider, SessionTranscriptSink(store, sid), n,
      sleeper = {
        sleeperEntered.countDown()
        sleeperGate.await()
      },
      retry = TurnRetryConfig(maxRetries = 1, retryDelaysMs = listOf(5_000L)),
    )
    try {
      val host = startedHost(c)
      assertTrue("retry sleeper never entered", sleeperEntered.await(5, TimeUnit.SECONDS))
      runBlocking {
        withTimeout(5_000) {
          while (store.events.none { it.kind == "assistant" && it.text == "half" }) delay(10)
        }
      }
      c.cancel()
      sleeperGate.complete(Unit)
      joinHost(host)
      flush(c)
      // (a) ledger: partial + retry notice + system mark; never two assistants.
      assertEquals(listOf("user", "assistant", "retry", "system"), store.events.map { it.kind })
      // (b) same attempt owns exactly one non-final partial row.
      val user = store.events[0]
      val partial = store.events[1]
      assertEquals(1, store.events.count { it.kind == "assistant" })
      assertEquals("half", partial.text)
      assertEquals(true, partial.isPartial)
      assertEquals("intermediate partial must stay non-final", false, partial.isFinal)
      // (c) system mark closes the family with the exact pattern.
      val mark = store.events[3]
      assertEquals(user.runId, mark.runId)
      assertEquals("turn ${user.runId} cancelled", mark.text)
      assertEquals(true, mark.isFinal)
      assertTrue("dangling: ${store.events}", findDanglingRunIds(store.events).isEmpty())
      // (d) second provider attempt never starts; UI keeps the partial.
      assertEquals(1, provider.streamCalls.get())
      assertEquals("half", c.uiState.value.messages.single { it.role == "assistant" }.text)
    } finally {
      sleeperGate.complete(Unit)
      c.close()
    }
  }

  // ---- retry-id × commit后ack前: COMMITTED (cancelId != logicalTurnId, >= 1) → zero new rows. ----

  @Test fun retryIdCancelBetweenCommitAndAckWritesZeroNew() {
    val store = FakeSessionStore()
    val sid = runBlocking { store.createSession("hi", "m") }
    val calls = AtomicInteger(0)
    val provider = AckCancelProvider {
      flow {
        if (calls.incrementAndGet() == 1) {
          emit(StreamEvent.TextDelta(0, 0, "half"))
          emit(StreamEvent.Failed("boom", retryable = true))
        } else {
          emit(StreamEvent.TextDelta(0, 0, "ok"))
          emit(StreamEvent.Done("stop"))
        }
      }
    }
    val n = AtomicInteger(0)
    val c = controller(
      provider, SessionTranscriptSink(store, sid), n,
      retry = TurnRetryConfig(maxRetries = 1, retryDelaysMs = listOf(0L)),
    )
    val committedLatch = CountDownLatch(1)
    val releaseWriter = CountDownLatch(1)
    val commits = AtomicInteger(0)
    c.afterCommitBeforeAckForTest = {
      // Ledger commits: user (logical) = 1, intermediate partial (logical) =
      // 2, retry success (fresh retry id) = 3 (retry notice has no commit).
      // Hold only the 3rd commit→ack window to hit the retry-id >= 1 branch
      // (cancelId != logicalTurnId). The UI row consumes the first newId, so
      // ids are not hardcoded here.
      if (commits.incrementAndGet() == 3) {
        committedLatch.countDown()
        releaseWriter.await(5, TimeUnit.SECONDS)
      }
    }
    try {
      val host = startedHost(c)
      assertTrue("retry terminal never committed", committedLatch.await(5, TimeUnit.SECONDS))
      c.cancel()
      Thread.sleep(300)
      releaseWriter.countDown()
      joinHost(host)
      flush(c)
      // (a) ledger: user + intermediate partial + retry notice + retry success, no cancel row.
      assertEquals(listOf("user", "assistant", "retry", "assistant"), store.events.map { it.kind })
      // (b) retry attempt owns exactly one final assistant row (cancelId != logicalTurnId,
      // so the >= 1 branch suppresses the duplicate final).
      val user = store.events[0]
      val partial = store.events[1]
      val retryNotice = store.events[2]
      val success = store.events[3]
      assertEquals(user.runId, partial.runId)
      assertEquals(user.runId, retryNotice.runId)
      assertEquals(true, partial.isPartial)
      assertEquals(false, partial.isFinal)
      assertEquals("boom", partial.failureReason)
      assertTrue("retry must mint a fresh id, was ${success.runId} vs logical ${user.runId}", success.runId != user.runId)
      assertEquals("ok", success.text)
      assertEquals(true, success.isFinal)
      assertEquals(false, success.isPartial)
      assertEquals(1, store.events.count { it.kind == "assistant" && it.runId == success.runId })
      // (c) family closed (no dangling).
      assertTrue("dangling: ${store.events}", findDanglingRunIds(store.events).isEmpty())
      // (d) both provider attempts ran, UI keeps one block per attempt.
      assertEquals(2, provider.streamCalls.get())
      assertEquals(2, c.uiState.value.messages.count { it.role == "assistant" })
      // Export/import consistency at the codec level (StageF pattern).
      for (e in store.events) {
        val back = JsonlCodec.decode(1, JsonlCodec.encode(e.copy(seq = e.seq.coerceAtLeast(1))))
        assertEquals("isFinal must round-trip for ${e.kind}", e.isFinal, back.isFinal)
      }
    } finally {
      releaseWriter.countDown()
      c.close()
    }
  }
}
