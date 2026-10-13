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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
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

private class AttributionProvider(
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

private class AttributionPolicy : PolicyStore {
  override fun evaluate(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
  override suspend fun setRule(rule: PolicyRule) = Unit
  override suspend fun removeRule(pattern: String) = Unit
  override suspend fun listRules(): List<PolicyRule> = emptyList()
  override suspend fun evaluateFresh(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

/**
 * R18-2 direction-A regression: per-entry receipt attribution.
 *
 * The aggregated per-runId mix cannot tell terminal SUCCESS + system FAIL
 * apart from terminal FAIL + system SUCCESS (both observe `commitCount == 2,
 * interrupted == true, pending == false` for a first-attempt id): the old
 * waiter compensated both with a duplicate assistant row. The per-entry
 * receipt (terminal ack observed directly + system-mark outcome captured
 * synchronously at the `onLogicalTurnCancelled` call site) compensates by
 * who failed:
 *
 * - terminal SUCCESS + system FAIL → system-mark idempotent exact-text
 *   rewrite, never a second assistant row ([systemFailCompensatesSystemMarkOnly]).
 * - terminal FAIL + system SUCCESS → assistant partial dump (control grid,
 *   [assistantFailCompensatesAssistantControl]).
 * - terminal FAIL + system FAIL (both-failed, non-final) → assistant dump
 *   first + system exact-text rewrite second, sequentially in one
 *   NonCancellable; a failed second write degrades to SEALED
 *   ([bothFailedSecondWriteFailsDegradesSealed]).
 * - terminal FAIL + system SKIPPED (final intent, zero optimistic rows) →
 *   normal cancel final ([finalIntentTerminalFailCompensatesFinal]).
 *
 * Both use TextDelta half + retryable Failed with a long retry backoff and a
 * cancel while the terminal is still SUBMITTING (800ms stall vs 300ms settle
 * budget, same scale as LateFailureCompensationTest): the receipt is
 * installed and the waiter must attribute. Five dimensions per case: (DB)
 * ledger kinds/text/flags + no dangling, (JSONL) codec round-trip, (reopen)
 * reload equals, (UI) assistant blocks, (flushDurable) false on store failure
 * with no duplicate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LateFailureSystemAttributionTest {

  companion object {
    private const val SETTLE_MS = 300L
    private const val STALL_MS = 800L
  }

  /**
   * R18-2 SystemFailStore: assistant terminal stalls then commits (delayed
   * SUCCESS), the optimistic system mark fails once with IOException (the
   * compensation rewrite succeeds). Proves the waiter rewrites the system
   * mark instead of duplicating the assistant.
   */
  private class SystemFailStore(
    val stallMs: Long = STALL_MS,
  ) : FakeSessionStore() {
    val assistantCalls = AtomicInteger(0)
    val systemCalls = AtomicInteger(0)
    val assistantEntered = CountDownLatch(1)
    override suspend fun appendEvent(event: TranscriptEvent): Long {
      if (event.kind == "assistant") {
        assistantCalls.incrementAndGet()
        assistantEntered.countDown()
        kotlinx.coroutines.delay(stallMs)
      } else if (event.kind == "system") {
        // Fail only the first system mark (the optimistic one); the
        // compensation exact-text rewrite must succeed.
        if (systemCalls.incrementAndGet() == 1) throw IOException("injected system mark failure")
      }
      return super.appendEvent(event)
    }
  }

  /** Control store: first assistant fails, system marks always succeed. */
  private class AssistantFailStore(
    val stallMs: Long = STALL_MS,
  ) : FakeSessionStore() {
    val assistantCalls = AtomicInteger(0)
    val assistantEntered = CountDownLatch(1)
    override suspend fun appendEvent(event: TranscriptEvent): Long {
      if (event.kind == "assistant") {
        val n = assistantCalls.incrementAndGet()
        if (n == 1) {
          assistantEntered.countDown()
          kotlinx.coroutines.delay(stallMs)
          throw IOException("injected terminal failure")
        }
      }
      return super.appendEvent(event)
    }
  }

  /**
   * R18-2 both-failed store: the non-final terminal stalls then fails (first
   * assistant throws) and EVERY system mark fails (optimistic + compensation
   * rewrite). The compensation assistant dump (second assistant call) must
   * succeed so the waiter reaches the sequential second write and degrades on
   * its failure. [order] records the store-visible write sequence to prove
   * assistant-first-then-system ordering.
   */
  private class BothFailStore(
    val stallMs: Long = STALL_MS,
  ) : FakeSessionStore() {
    val assistantCalls = AtomicInteger(0)
    val systemCalls = AtomicInteger(0)
    val assistantEntered = CountDownLatch(1)
    val order = Collections.synchronizedList(ArrayList<String>())
    override suspend fun appendEvent(event: TranscriptEvent): Long {
      if (event.kind == "assistant") {
        val n = assistantCalls.incrementAndGet()
        if (n == 1) {
          assistantEntered.countDown()
          kotlinx.coroutines.delay(stallMs)
          order.add("terminal-fail")
          throw IOException("injected terminal failure")
        }
        order.add("assistant-comp")
      } else if (event.kind == "system") {
        val n = systemCalls.incrementAndGet()
        order.add("system-$n")
        throw IOException("injected system failure #$n")
      }
      return super.appendEvent(event)
    }
  }

  private fun controller(
    provider: AttributionProvider,
    sink: SessionTranscriptSink,
    n: AtomicInteger,
  ): TurnController = TurnController(
    provider = provider,
    policy = AttributionPolicy(),
    transcript = sink,
    retryConfig = TurnRetryConfig(maxRetries = 1, retryDelaysMs = listOf(5_000L)),
    dispatcher = Dispatchers.Default,
    sleeper = {},
    newId = { "attr-${n.incrementAndGet() - 1}" },
    cancelSettleTimeoutMs = SETTLE_MS,
    cancelCompensationTimeoutMs = 5_000L,
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

  private fun awaitCalls(calls: AtomicInteger, want: Int, timeoutMs: Long = 8_000L) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (calls.get() >= want) return
      Thread.sleep(25)
    }
    throw AssertionError("expected >= $want store calls in ${timeoutMs}ms, was ${calls.get()}")
  }

  /**
   * R18-2 white-box seam: the waiter receipt state is private (ledger-visible
   * only via rows), so the SEALED-vs-COMPENSATED assertions below read it via
   * reflection. Spins until the single installed receipt leaves
   * PENDING/CLAIMED for a terminal state (REVOKED/COMPENSATED/SEALED).
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
        val state = (sf.get(receipt) as AtomicReference<*>).get().toString()
        if (state != "PENDING" && state != "CLAIMED") return state
      }
      Thread.sleep(25)
    }
    throw AssertionError("receipt never reached a terminal state")
  }

  @Test fun systemFailCompensatesSystemMarkOnly() {
    val store = SystemFailStore()
    val sid = runBlocking { store.createSession("hi", "m") }
    val provider = AttributionProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        emit(StreamEvent.Failed("boom", retryable = true))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n)
    try {
      val host = startedHost(c)
      assertTrue("terminal never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      joinHost(host)
      // R18-1 Stage 2 notice-first: terminal commits (delayed SUCCESS of the
      // intermediate partial), notice lands, deferred system mark FAILs once
      // (injected) → SEALED/backfill (no retry, family stays dangling for
      // reopen INTERRUPTED). Ledger: user + original partial + notice, no
      // system. Never a duplicate assistant.
      awaitLedger(store) { evts ->
        evts.count { it.kind == "assistant" } == 1 &&
          evts.any { it.kind == "retry" && it.text.startsWith("cancel requested") } &&
          evts.none { it.kind == "system" }
      }
      // Deferred system try fails (injected) → SEALED. Wait for the waiter to
      // attempt it before asserting counts.
      awaitCalls(store.systemCalls, 1)
      assertEquals("SEALED", awaitReceiptTerminalState(c))
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      assertEquals(listOf("user", "assistant", "retry"), store.events.map { it.kind })
      val attemptId = store.events[0].runId
      val assistants = store.events.filter { it.kind == "assistant" && it.runId == attemptId }
      assertEquals("duplicate assistant for $attemptId: ${store.events}", 1, assistants.size)
      val partial = assistants.single()
      assertEquals("half", partial.text)
      assertEquals(true, partial.isPartial)
      assertEquals("system-FAIL must keep the original non-final partial", false, partial.isFinal)
      assertEquals("boom", partial.failureReason)
      // Single deferred try (no optimistic + rewrite): first system call throws,
      // waiter degrades to SEALED without a second try.
      assertEquals(1, store.systemCalls.get())
      assertEquals(1, store.assistantCalls.get())
      val notice = store.events.single { it.kind == "retry" }
      assertEquals(attemptId, notice.runId)
      assertEquals("SEALED", awaitReceiptTerminalState(c))
      assertEquals(
        "deferred system failure must leave the family dangling for backfill: ${store.events}",
        listOf(attemptId),
        findDanglingRunIds(store.events),
      )
      // (JSONL) + (reopen) + (UI) + (flushDurable=false: durable failure occurred, no duplicate).
      for (e in store.events) {
        val back = JsonlCodec.decode(1, JsonlCodec.encode(e.copy(seq = e.seq.coerceAtLeast(1))))
        assertEquals("isFinal must round-trip for ${e.kind}", e.isFinal, back.isFinal)
        assertEquals("isPartial must round-trip for ${e.kind}", e.isPartial, back.isPartial)
      }
      val reloaded = runBlocking { store.loadEvents(sid, 0, 100) }
      assertEquals(
        store.events.map { it.runId to it.text to it.kind },
        reloaded.map { it.runId to it.text to it.kind },
      )
      assertEquals("half", c.uiState.value.messages.single { it.role == "assistant" }.text)
      assertTrue(
        "system failure must leave an INTERRUPTED id for backfill",
        c.interruptedTranscriptRunIds().contains(attemptId),
      )
      assertFalse("system store failure must keep flushDurable false", runBlocking { withTimeout(8_000) { c.flushDurable() } })
      assertEquals(1, provider.streamCalls.get())
    } finally {
      c.close()
    }
  }

  @Test fun assistantFailCompensatesAssistantControl() {
    val store = AssistantFailStore()
    val sid = runBlocking { store.createSession("hi", "m") }
    val provider = AttributionProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        emit(StreamEvent.Failed("boom", retryable = true))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n)
    try {
      val host = startedHost(c)
      assertTrue("terminal never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      joinHost(host)
      // R18-1 notice-first: terminal FAILs (no row), notice lands, waiter
      // writes the atomic batch partial+system: exactly one assistant (batch)
      // + one system (batch) + one notice.
      awaitLedger(store) { evts ->
        evts.count { it.kind == "assistant" } == 1 && evts.any { it.kind == "system" } &&
          evts.any { it.kind == "retry" && it.text.startsWith("cancel requested") }
      }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      val kinds = store.events.map { it.kind }
      assertEquals(
        "R18-1 batch must be user+retry+assistant+system, was $kinds",
        listOf("user", "retry", "assistant", "system"),
        kinds,
      )
      val attemptId = store.events[0].runId
      // Assistant unique: the compensation dump (original terminal left no
      // row), never a second assistant.
      val assistants = store.events.filter { it.kind == "assistant" }
      assertEquals("duplicate assistant: ${store.events}", 1, assistants.size)
      val dump = assistants.single()
      assertEquals(attemptId, dump.runId)
      assertEquals("half", dump.text)
      assertEquals(true, dump.isPartial)
      assertEquals("non-final compensation must stay non-final", false, dump.isFinal)
      // Failure attribution is terminal: the dump carries the compensation
      // reason, while the system mark is the optimistic one (single call).
      assertEquals("partial lost (store failure)", dump.failureReason)
      val mark = store.events.single { it.kind == "system" }
      assertEquals(attemptId, mark.runId)
      assertEquals("turn $attemptId cancelled", mark.text)
      assertEquals(true, mark.isFinal)
      assertTrue("dangling: ${store.events}", findDanglingRunIds(store.events).isEmpty())
      for (e in store.events) {
        val back = JsonlCodec.decode(1, JsonlCodec.encode(e.copy(seq = e.seq.coerceAtLeast(1))))
        assertEquals("isFinal must round-trip for ${e.kind}", e.isFinal, back.isFinal)
        assertEquals("isPartial must round-trip for ${e.kind}", e.isPartial, back.isPartial)
      }
      val reloaded = runBlocking { store.loadEvents(sid, 0, 100) }
      assertEquals(
        store.events.map { it.runId to it.text to it.kind },
        reloaded.map { it.runId to it.text to it.kind },
      )
      assertEquals("half", c.uiState.value.messages.single { it.role == "assistant" }.text)
      assertTrue(
        "terminal failure must leave an INTERRUPTED id for backfill",
        c.interruptedTranscriptRunIds().contains(attemptId),
      )
      assertFalse("terminal store failure must keep flushDurable false", runBlocking { withTimeout(8_000) { c.flushDurable() } })
      assertEquals(1, provider.streamCalls.get())
    } finally {
      c.close()
    }
  }

  @Test fun bothFailedSecondWriteFailsDegradesSealed() {
    val store = BothFailStore()
    val sid = runBlocking { store.createSession("hi", "m") }
    val provider = AttributionProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        emit(StreamEvent.Failed("boom", retryable = true))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n)
    try {
      val host = startedHost(c)
      assertTrue("terminal never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      joinHost(host)
      // R18-1 atomic batch: terminal FAILs (no row), notice lands, batch
      // assistant+system is attempted as one unit and rolls back (fake
      // snapshot) on the injected system failure → zero batch rows. Ledger:
      // user + notice only, family stays dangling for backfill.
      awaitLedger(store) { evts -> evts.any { it.kind == "retry" && it.text.startsWith("cancel requested") } }
      awaitCalls(store.systemCalls, 1)
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      assertEquals(listOf("user", "retry"), store.events.map { it.kind })
      val attemptId = store.events[0].runId
      assertEquals(2, store.assistantCalls.get())
      assertEquals("notice + single batch try (no optimistic)", 1, store.systemCalls.get())
      // Atomic attempt order: terminal fail, then batch assistant try, then
      // batch system fail (rollback removes the batch assistant row).
      val order = ArrayList(store.order)
      assertEquals(
        listOf("terminal-fail", "assistant-comp", "system-1"),
        order,
      )
      // Failed second write degrades to SEALED (not COMPENSATED, never
      // CLAIMED-stuck): no system row, the family stays open for backfill.
      assertEquals("SEALED", awaitReceiptTerminalState(c))
      assertTrue(
        "open family must stay dangling for backfill: ${store.events}",
        findDanglingRunIds(store.events).contains(attemptId),
      )
      // (JSONL) + (reopen) + (UI) + (flushDurable=false: durable failures occurred).
      for (e in store.events) {
        val back = JsonlCodec.decode(1, JsonlCodec.encode(e.copy(seq = e.seq.coerceAtLeast(1))))
        assertEquals("isFinal must round-trip for ${e.kind}", e.isFinal, back.isFinal)
        assertEquals("isPartial must round-trip for ${e.kind}", e.isPartial, back.isPartial)
      }
      val reloaded = runBlocking { store.loadEvents(sid, 0, 100) }
      assertEquals(
        store.events.map { it.runId to it.text to it.kind },
        reloaded.map { it.runId to it.text to it.kind },
      )
      assertEquals("half", c.uiState.value.messages.single { it.role == "assistant" }.text)
      assertTrue(
        "both-failed must leave an INTERRUPTED id for backfill",
        c.interruptedTranscriptRunIds().contains(attemptId),
      )
      assertFalse("both-failed store failures must keep flushDurable false", runBlocking { withTimeout(8_000) { c.flushDurable() } })
      assertEquals(1, provider.streamCalls.get())
    } finally {
      c.close()
    }
  }

  @Test fun finalIntentTerminalFailCompensatesFinal() {
    val store = AssistantFailStore()
    val sid = runBlocking { store.createSession("hi", "m") }
    val provider = AttributionProvider {
      flow { emit(StreamEvent.Failed("boom", retryable = false)) }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n)
    try {
      val host = startedHost(c)
      assertTrue("terminal never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      joinHost(host)
      // Final-intent terminal FAILs (no row, zero optimistic rows, system
      // SKIPPED) → waiter writes the normal cancel final.
      awaitLedger(store) { evts -> evts.count { it.kind == "assistant" } == 1 && evts.size == 2 }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      // (DB) user + compensation cancel final, never a system mark.
      assertEquals(listOf("user", "assistant"), store.events.map { it.kind })
      val attemptId = store.events[0].runId
      val comp = store.events[1]
      assertEquals(attemptId, comp.runId)
      assertEquals(true, comp.isPartial)
      assertEquals("final-intent FAIL must compensate a final cancel", true, comp.isFinal)
      assertEquals(null, comp.failureReason)
      assertTrue(
        "final intent must attempt no system mark: ${store.events}",
        store.events.none { it.kind == "system" },
      )
      assertEquals("COMPENSATED", awaitReceiptTerminalState(c))
      assertTrue("dangling: ${store.events}", findDanglingRunIds(store.events).isEmpty())
      // (JSONL) + (reopen) + (UI) + (flushDurable=false: durable failure occurred).
      for (e in store.events) {
        val back = JsonlCodec.decode(1, JsonlCodec.encode(e.copy(seq = e.seq.coerceAtLeast(1))))
        assertEquals("isFinal must round-trip for ${e.kind}", e.isFinal, back.isFinal)
        assertEquals("isPartial must round-trip for ${e.kind}", e.isPartial, back.isPartial)
      }
      val reloaded = runBlocking { store.loadEvents(sid, 0, 100) }
      assertEquals(
        store.events.map { it.runId to it.text to it.kind },
        reloaded.map { it.runId to it.text to it.kind },
      )
      assertEquals(1, c.uiState.value.messages.count { it.role == "assistant" })
      assertTrue(
        "terminal failure must leave an INTERRUPTED id for backfill",
        c.interruptedTranscriptRunIds().contains(attemptId),
      )
      assertFalse("terminal store failure must keep flushDurable false", runBlocking { withTimeout(8_000) { c.flushDurable() } })
      assertEquals(1, provider.streamCalls.get())
    } finally {
      c.close()
    }
  }
}
