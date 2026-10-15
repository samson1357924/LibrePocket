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
import java.io.IOException
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class LateProvider(
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

private class LatePolicy : PolicyStore {
  override fun evaluate(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
  override suspend fun setRule(rule: PolicyRule) = Unit
  override suspend fun removeRule(pattern: String) = Unit
  override suspend fun listRules(): List<PolicyRule> = emptyList()
  override suspend fun evaluateFresh(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

/**
 * R17-1 regression matrix: late-writer settlement after the sync cancel
 * settle-wait (production 5s budget vs 5.1s stall; tests scale to 300ms vs
 * 800ms via the injectable [TurnController.cancelSettleTimeoutMs] seam so CI
 * stays fast — no real 5.1s stall).
 *
 * - stall + late-FAIL (writer throws IOException after the stall) ×
 *   success / terminal-failure / retryable-partial / retry-success fresh id:
 *   the PENDING receipt compensates (final → normal cancel final, non-final
 *   → partial dump behind the optimistic system mark).
 * - stall + late-success (writer commits after the stall): benign, zero new
 *   rows, receipt revokes.
 * - seal degradation (close while PENDING): SEALED/backfill, no compensation
 *   write, writer never parks forever (WARN-1 bounded compensation write).
 *
 * Five dimensions per case: (DB) ledger kinds/text/flags + no dangling (or
 * expected dangling for seal), (JSONL) codec round-trip, (reopen) reload
 * equals, (UI) assistant blocks, (flushDurable) false on store failure / true
 * on benign.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LateFailureCompensationTest {

  companion object {
    /** Test-scale settle budget (production 5_000ms). */
    private const val SETTLE_MS = 300L
    /** Test-scale stall (production 5_100ms): must exceed [SETTLE_MS]. */
    private const val STALL_MS = 800L
  }

  /** Store whose terminal assistant write stalls then fails or succeeds. */
  private open class LateStore(
    val stallMs: Long = STALL_MS,
    val failPredicate: (call: Int) -> Boolean = { true },
    val failWith: IOException? = IOException("late writer failure"),
  ) : FakeSessionStore() {
    val assistantCalls = AtomicInteger(0)
    val assistantEntered = CountDownLatch(1)
    override suspend fun appendEvent(event: TranscriptEvent): Long {
      if (event.kind == "assistant") {
        val n = assistantCalls.incrementAndGet()
        if (failPredicate(n)) {
          assistantEntered.countDown()
          delay(stallMs)
          failWith?.let { throw it }
        }
      }
      return super.appendEvent(event)
    }
  }

  private fun controller(
    provider: LateProvider,
    sink: SessionTranscriptSink,
    n: AtomicInteger,
    settleMs: Long = SETTLE_MS,
    compensationMs: Long = 5_000L,
    retry: TurnRetryConfig = TurnRetryConfig(),
    sleeper: suspend (Long) -> Unit = {},
  ): TurnController = TurnController(
    provider = provider,
    policy = LatePolicy(),
    transcript = sink,
    retryConfig = retry,
    dispatcher = Dispatchers.Default,
    sleeper = sleeper,
    newId = { "late-${n.incrementAndGet() - 1}" },
    cancelSettleTimeoutMs = settleMs,
    cancelCompensationTimeoutMs = compensationMs,
  )

  private fun startedHost(c: TurnController): kotlinx.coroutines.Job {
    val admission = runBlocking { c.startOrEnqueue("hi") }
    assertTrue("admission must start, was $admission", admission is TurnStart.Started)
    return (admission as TurnStart.Started).host
  }

  private fun joinHost(host: kotlinx.coroutines.Job, timeoutMs: Long = 8_000L) {
    runBlocking { withTimeout(timeoutMs) { host.join() } }
  }

  private fun awaitLedger(
    store: FakeSessionStore,
    timeoutMs: Long = 8_000L,
    cond: (List<TranscriptEvent>) -> Boolean,
  ) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val snapshot: List<TranscriptEvent> = synchronized(store.events) {
        ArrayList(store.events)
      }
      if (cond(snapshot)) return
      Thread.sleep(25)
    }
    throw AssertionError("ledger condition not met in ${timeoutMs}ms: ${store.events}")
  }

  private fun assertJsonlRoundTrip(events: List<TranscriptEvent>) {
    for (e in events) {
      val back = JsonlCodec.decode(1, JsonlCodec.encode(e.copy(seq = e.seq.coerceAtLeast(1))))
      assertEquals("isFinal must round-trip for ${e.kind}", e.isFinal, back.isFinal)
      assertEquals("isPartial must round-trip for ${e.kind}", e.isPartial, back.isPartial)
    }
  }

  private fun assertReopen(store: FakeSessionStore, sid: String, expected: List<TranscriptEvent>) {
    val reloaded = runBlocking { store.loadEvents(sid, 0, 100) }
    assertEquals(
      "reopen must reload the same ledger",
      expected.map { it.runId to it.text to it.kind },
      reloaded.map { it.runId to it.text to it.kind },
    )
  }

  private fun flushDurable(c: TurnController): Boolean =
    runBlocking { withTimeout(8_000) { c.flushDurable() } }

  /**
   * P0-1 Phase4 fence: receipt terminal state via the private cancelReceipts
   * seam (PENDING/CLAIMED spin until REVOKED/COMPENSATED/SEALED). Replaces
   * wall-clock sleeps that guessed the waiter had settled.
   *
   * Given: a cancel receipt was installed (stall > settle budget).
   * When: the waiter settles the terminal outcome.
   * Then: the single receipt leaves PENDING/CLAIMED for a terminal state.
   */
  private fun awaitReceiptTerminalState(c: TurnController, timeoutMs: Long = 8_000L): String {
    val f = TurnController::class.java.getDeclaredField("cancelReceipts")
    f.isAccessible = true
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      @Suppress("UNCHECKED_CAST")
      val map = f.get(c) as java.util.concurrent.ConcurrentHashMap<*, *>
      val receipt = map.values.singleOrNull()
      if (receipt != null) {
        val sf = receipt.javaClass.getDeclaredField("state")
        sf.isAccessible = true
        val state = (sf.get(receipt) as java.util.concurrent.atomic.AtomicReference<*>).get().toString()
        if (state != "PENDING" && state != "CLAIMED") return state
      } else {
        // Benign revoke path may never install a receipt (acked before
        // stall): treat empty as REVOKED-equivalent settled.
        return "REVOKED"
      }
      Thread.sleep(25)
    }
    throw AssertionError("receipt never reached a terminal state")
  }

  /**
   * P0-1 Phase4 fence: two flush drains with zero-new-rows断言. Replaces
   * `Thread.sleep(400)` guess-waits in benign late-success tests.
   *
   * Given: host joined + first flush drained.
   * When: two consecutive flushTranscript drains run.
   * Then: ledger size is identical (no late duplicate can still land).
   */
  private fun flushTwiceAssertStable(c: TurnController, store: FakeSessionStore) {
    runBlocking { withTimeout(8_000) { c.flushTranscript() } }
    val before = synchronized(store.events) { ArrayList(store.events).size }
    // Fence on the receipt terminal state instead of a fixed sleep: the
    // waiter has settled (REVOKED for benign commit-wins, COMPENSATED for
    // late-FAIL, SEALED for wedge) before we measure stability. Strict:
    // a fence timeout fails the test (never swallowed) — every caller
    // already awaits its ledger shape, so a timeout is a real wedge.
    awaitReceiptTerminalState(c, timeoutMs = 8_000L)
    runBlocking { withTimeout(8_000) { c.flushTranscript() } }
    val after = synchronized(store.events) { ArrayList(store.events).size }
    assertEquals("two flush drains must add zero rows (fence, not sleep)", before, after)
  }

  // ---- ★1: success late-FAIL → compensation cancel final. ----

  @Test fun successLateFailCompensatesCancelFinal() {
    val store = LateStore(failPredicate = { it == 1 })
    val sid = runBlocking { store.createSession("hi", "m") }
    val provider = LateProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n)
    try {
      val host = startedHost(c)
      assertTrue("terminal never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      // Host keeps the optimistic zero (final SUBMITTING stall): it completes
      // after the 300ms settle budget without waiting for the 800ms writer.
      joinHost(host)
      // Late FAIL (no commit) → waiter claims and writes the cancel final.
      awaitLedger(store) { evts -> evts.count { it.kind == "assistant" } == 1 && evts.size == 2 }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      // (DB) user + compensation cancel final (original success text lost to
      // the store failure, cancel-time snapshot "ok" preserved).
      assertEquals(listOf("user", "assistant"), store.events.map { it.kind })
      val attemptId = store.events[0].runId
      val comp = store.events[1]
      assertEquals(attemptId, comp.runId)
      assertEquals("ok", comp.text)
      assertEquals(true, comp.isPartial)
      assertEquals("late-FAIL final must compensate a final cancel", true, comp.isFinal)
      assertEquals(null, comp.failureReason)
      assertTrue("dangling: ${store.events}", findDanglingRunIds(store.events).isEmpty())
      // (JSONL) + (reopen) + (UI) + (flushDurable=false: durable failure occurred).
      assertJsonlRoundTrip(store.events)
      assertReopen(store, sid, store.events)
      assertEquals(1, c.uiState.value.messages.count { it.role == "assistant" })
      assertEquals("ok", c.uiState.value.messages.single { it.role == "assistant" }.text)
      assertFalse("late store failure must keep flushDurable false", flushDurable(c))
      assertEquals(1, provider.streamCalls.get())
    } finally {
      c.close()
    }
  }

  // ---- ★3: terminal-failure late-FAIL → compensation cancel final. ----

  @Test fun terminalFailureLateFailCompensatesCancelFinal() {
    val store = LateStore(failPredicate = { it == 1 })
    val sid = runBlocking { store.createSession("hi", "m") }
    val provider = LateProvider {
      flow { emit(StreamEvent.Failed("boom", retryable = false)) }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n)
    try {
      val host = startedHost(c)
      assertTrue("terminal never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      joinHost(host)
      awaitLedger(store) { evts -> evts.count { it.kind == "assistant" } == 1 && evts.size == 2 }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      assertEquals(listOf("user", "assistant"), store.events.map { it.kind })
      val attemptId = store.events[0].runId
      val comp = store.events[1]
      assertEquals(attemptId, comp.runId)
      assertEquals(true, comp.isPartial)
      assertEquals("late-FAIL terminal-failure must compensate a final", true, comp.isFinal)
      assertEquals(null, comp.failureReason)
      assertTrue("dangling: ${store.events}", findDanglingRunIds(store.events).isEmpty())
      assertJsonlRoundTrip(store.events)
      assertReopen(store, sid, store.events)
      assertEquals(1, c.uiState.value.messages.count { it.role == "assistant" })
      assertFalse("late store failure must keep flushDurable false", flushDurable(c))
      assertEquals(1, provider.streamCalls.get())
    } finally {
      c.close()
    }
  }

  // ---- ★4: retryable-intermediate late-FAIL → system mark + partial dump. ----

  @Test fun intermediateLateFailCompensatesPartialDump() {
    val store = LateStore(failPredicate = { it == 1 })
    val sid = runBlocking { store.createSession("hi", "m") }
    val provider = LateProvider {
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
      assertTrue("terminal never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      // R18-1 Stage 2 notice-first: the host writes only a durable
      // non-terminal notice (retry-like) while the terminal is still
      // SUBMITTING; the waiter then appends the atomic batch
      // partial(non-final)+system(final) after the late FAIL.
      joinHost(host)
      awaitLedger(store) { evts ->
        evts.any { it.kind == "system" } && evts.count { it.kind == "assistant" } == 1 &&
          evts.any { it.kind == "retry" && it.text.startsWith("cancel requested") }
      }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      // (DB) user + cancel-intent notice (non-terminal, keeps dangling) +
      // atomic batch partial dump (non-final, cancel-time "half") + system mark
      // (final, closes the family). Order: notice is admitted behind the
      // failed terminal, batch lands last (partial then system in one unit).
      val kinds = store.events.map { it.kind }
      assertEquals(
        "R18-1 notice-first batch must be user+retry+assistant+system, was $kinds",
        listOf("user", "retry", "assistant", "system"),
        kinds,
      )
      val attemptId = store.events[0].runId
      val notice = store.events.single { it.kind == "retry" && it.text.startsWith("cancel requested") }
      assertEquals(attemptId, notice.runId)
      val partial = store.events.single { it.kind == "assistant" }
      assertEquals(attemptId, partial.runId)
      assertEquals("half", partial.text)
      assertEquals(true, partial.isPartial)
      assertEquals("non-final batch partial must stay non-final", false, partial.isFinal)
      assertEquals("partial lost (store failure)", partial.failureReason)
      val mark = store.events.single { it.kind == "system" }
      assertEquals(attemptId, mark.runId)
      assertEquals("turn $attemptId cancelled", mark.text)
      assertEquals(true, mark.isFinal)
      assertTrue("dangling: ${store.events}", findDanglingRunIds(store.events).isEmpty())
      assertJsonlRoundTrip(store.events)
      assertReopen(store, sid, store.events)
      assertEquals("half", c.uiState.value.messages.single { it.role == "assistant" }.text)
      assertFalse("late store failure must keep flushDurable false", flushDurable(c))
      assertEquals(1, provider.streamCalls.get())
    } finally {
      c.close()
    }
  }

  // ---- ★2: retry-success (fresh id) late-FAIL → compensation for the fresh id. ----

  @Test fun retrySuccessLateFailCompensatesFreshId() {
    val secondEntered = CountDownLatch(1)
    val store = object : FakeSessionStore() {
      val assistantCalls = AtomicInteger(0)
      override suspend fun appendEvent(event: TranscriptEvent): Long {
        if (event.kind == "assistant" && assistantCalls.incrementAndGet() == 2) {
          secondEntered.countDown()
          delay(STALL_MS)
          throw IOException("late retry failure")
        }
        return super.appendEvent(event)
      }
    }
    val sid = runBlocking { store.createSession("hi", "m") }
    val providerCalls = AtomicInteger(0)
    val provider = LateProvider {
      flow {
        if (providerCalls.incrementAndGet() == 1) {
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
    try {
      val host = startedHost(c)
      assertTrue("retry terminal never entered", secondEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      joinHost(host)
      awaitLedger(store) { evts -> evts.size == 4 }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      // (DB) user + intermediate partial + retry notice + compensation final
      // for the fresh retry id (original retry success lost).
      assertEquals(listOf("user", "assistant", "retry", "assistant"), store.events.map { it.kind })
      val user = store.events[0]
      val partial = store.events[1]
      val comp = store.events[3]
      assertEquals(user.runId, partial.runId)
      assertEquals(true, partial.isPartial)
      assertEquals(false, partial.isFinal)
      assertTrue("retry must mint a fresh id", comp.runId != user.runId)
      assertEquals("ok", comp.text)
      assertEquals(true, comp.isPartial)
      assertEquals(true, comp.isFinal)
      assertEquals(1, store.events.count { it.kind == "assistant" && it.runId == comp.runId })
      assertTrue("dangling: ${store.events}", findDanglingRunIds(store.events).isEmpty())
      assertJsonlRoundTrip(store.events)
      assertReopen(store, sid, store.events)
      assertEquals(2, c.uiState.value.messages.count { it.role == "assistant" })
      assertFalse("late store failure must keep flushDurable false", flushDurable(c))
      assertEquals(2, provider.streamCalls.get())
    } finally {
      c.close()
    }
  }

  // ---- 5: success late-success (benign) → zero new rows. ----

  @Test fun successLateSuccessWritesZeroNew() {
    val store = LateStore(failPredicate = { it == 1 }, failWith = null)
    val sid = runBlocking { store.createSession("hi", "m") }
    val provider = LateProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n)
    try {
      val host = startedHost(c)
      assertTrue("terminal never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      // Stall (800ms) exceeds the 300ms settle budget → receipt installed, but
      // the writer then commits: revoke, optimistic zero stands, no duplicate.
      joinHost(host)
      // P0-1 fence: receipt REVOKED + two flush drains zero-new (replaces sleep 400).
      flushTwiceAssertStable(c, store)
      assertEquals(listOf("user", "assistant"), store.events.map { it.kind })
      val attemptId = store.events[0].runId
      val assistants = store.events.filter { it.kind == "assistant" && it.runId == attemptId }
      assertEquals(1, assistants.size)
      assertEquals("ok", assistants.single().text)
      assertEquals(true, assistants.single().isFinal)
      assertTrue("dangling: ${store.events}", findDanglingRunIds(store.events).isEmpty())
      assertJsonlRoundTrip(store.events)
      assertReopen(store, sid, store.events)
      assertEquals(1, c.uiState.value.messages.count { it.role == "assistant" })
      assertTrue("benign late-success must drain clean", flushDurable(c))
      assertEquals(1, provider.streamCalls.get())
    } finally {
      c.close()
    }
  }

  // ---- 6: intermediate late-success (benign) → system mark only, no dump. ----

  @Test fun intermediateLateSuccessWritesSystemMarkOnly() {
    val store = LateStore(failPredicate = { it == 1 }, failWith = null)
    val sid = runBlocking { store.createSession("hi", "m") }
    val provider = LateProvider {
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
      assertTrue("terminal never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      joinHost(host)
      // P0-1 fence: deferred system mark lands via waiter; await ledger shape
      // + receipt terminal + two flush drains zero-new (replaces sleep 400).
      awaitLedger(store) { evts ->
        evts.any { it.kind == "system" } && evts.count { it.kind == "assistant" } == 1
      }
      flushTwiceAssertStable(c, store)
      // R18-1 benign: intermediate commits, notice-first keeps dangling until
      // the waiter appends the deferred system mark — never the partial dump
      // (exactly one assistant). Ledger: user + original partial + notice +
      // system (notice is retry-like, hidden from replay, visible in export).
      assertEquals(
        listOf("user", "assistant", "retry", "system"),
        store.events.map { it.kind },
      )
      assertEquals(1, store.events.count { it.kind == "assistant" })
      val partial = store.events.single { it.kind == "assistant" }
      assertEquals("half", partial.text)
      assertEquals(false, partial.isFinal)
      assertEquals("boom", partial.failureReason)
      val notice = store.events.single { it.kind == "retry" && it.text.startsWith("cancel requested") }
      assertEquals(store.events[0].runId, notice.runId)
      val mark = store.events.single { it.kind == "system" }
      assertEquals(store.events[0].runId, mark.runId)
      assertEquals("turn ${store.events[0].runId} cancelled", mark.text)
      assertTrue("dangling: ${store.events}", findDanglingRunIds(store.events).isEmpty())
      assertJsonlRoundTrip(store.events)
      assertReopen(store, sid, store.events)
      assertEquals("half", c.uiState.value.messages.single { it.role == "assistant" }.text)
      assertTrue("benign late-success must drain clean", flushDurable(c))
      assertEquals(1, provider.streamCalls.get())
    } finally {
      c.close()
    }
  }

  // ---- ★9: seal degradation → SEALED/backfill, bounded compensation write. ----

  @Test fun sealedWriterDegradesWithoutParking() {
    val gate = CompletableDeferred<Unit>()
    val entered = CountDownLatch(1)
    val store = object : FakeSessionStore() {
      override suspend fun appendEvent(event: TranscriptEvent): Long {
        if (event.kind == "assistant") {
          entered.countDown()
          gate.await()
        }
        return super.appendEvent(event)
      }
    }
    val sid = runBlocking { store.createSession("hi", "m") }
    val provider = LateProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val n = AtomicInteger(0)
    // Short compensation budget proves WARN-1: even with a wedged writer the
    // compensation write degrades instead of parking forever.
    val c = controller(provider, SessionTranscriptSink(store, sid), n, compensationMs = 400L)
    try {
      val host = startedHost(c)
      assertTrue("terminal never entered", entered.await(5, TimeUnit.SECONDS))
      c.cancel()
      // Optimistic zero (final stall) lets the host complete after settle;
      // sealing while the writer is still wedged must degrade the receipt.
      joinHost(host)
      c.close(drainTimeoutMs = 400L)
      // Do NOT complete the gate: the writer stays wedged until shutdown
      // times out, marks INTERRUPTED and cancels the scope. The waiter must
      // observe the seal and degrade without writing (WARN-1 bounded write).
      // P0-1 fence: await SEALED receipt + flush drain (replaces sleep 900).
      assertEquals("SEALED", awaitReceiptTerminalState(c))
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      // Ledger owns only the user row; the terminal never committed and no
      // compensation was written (backfill owns the INTERRUPTED row).
      assertTrue(
        "sealed waiter must not compensate, was ${store.events}",
        store.events.none { it.kind == "assistant" },
      )
      assertEquals(listOf("user"), store.events.map { it.kind })
      // User-only with no final is dangling by definition (expected here, not
      // a leak: the seal degraded the waiter to SEALED with no compensation
      // write, so reopen backfill owns the INTERRUPTED row).
      val userRunId = store.events[0].runId
      assertEquals(
        "sealed user-only ledger must dangle the user family for backfill",
        listOf(userRunId),
        findDanglingRunIds(store.events),
      )
      assertTrue(
        "sealed stall must leave an INTERRUPTED id for backfill",
        c.interruptedTranscriptRunIds().contains(userRunId),
      )
      assertJsonlRoundTrip(store.events)
      assertReopen(store, sid, store.events)
      // (UI) seal degradation: cancel won synchronously (CANCELLED) and the UI
      // keeps the cancel-time snapshot — user + streamed "ok" (finalized on
      // Done before the wedged terminal write) — while the ledger durably owns
      // only the user row. That UI-vs-ledger divergence is what reopen
      // backfill resolves with the INTERRUPTED mark above.
      assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
      assertEquals(1, c.uiState.value.messages.count { it.role == "user" })
      assertEquals("hi", c.uiState.value.messages.single { it.role == "user" }.text)
      assertEquals(1, c.uiState.value.messages.count { it.role == "assistant" })
      assertEquals("ok", c.uiState.value.messages.single { it.role == "assistant" }.text)
      assertFalse(
        "sealed assistant snapshot was finalized on Done before the wedged write",
        c.uiState.value.messages.single { it.role == "assistant" }.isPartial,
      )
      // flushDurable is false: the admission never became durable.
      assertFalse("sealed stall must not drain clean", flushDurable(c))
    } finally {
      gate.complete(Unit)
      c.close()
    }
  }
}
