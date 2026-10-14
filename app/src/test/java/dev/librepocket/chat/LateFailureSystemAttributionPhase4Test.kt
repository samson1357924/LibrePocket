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
import java.util.concurrent.atomic.AtomicReference
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

private class P4AttrProvider(
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

private class P4AttrPolicy : PolicyStore {
  override fun evaluate(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
  override suspend fun setRule(rule: PolicyRule) = Unit
  override suspend fun removeRule(pattern: String) = Unit
  override suspend fun listRules(): List<PolicyRule> = emptyList()
  override suspend fun evaluateFresh(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

/**
 * PR54 Phase4 P1-1: attribution-matrix缺格補齊.
 *
 * 現況 (LateFailureSystemAttributionTest): 僅4格 —
 * terminal SUCCESS+system FAIL (deferred單寫)、terminal FAIL+system SUCCESS
 * (control)、both FAILED、final-intent FAIL+system SKIPPED。全為 reasoning=false。
 *
 * 本檔補缺格 (每格一正一反, Given/When/Then, 可注入clock/gate無固定sleep):
 * - A: final-intent SUCCESS + system FAIL (雜散失敗不得誤補第二assistant)。
 * - B: intermediate SUCCESS + system SUCCESS但cancel在system ack後 (post-ack零新增)。
 * - C: fresh-retry-id x system FAIL (parentRunId/attemptIndex連結)。
 * - D: A形 x reasoning=true (RetryableCancelReasoningMarker只測batch不測attribution;
 *   B/C無reasoning twins)。
 *
 * 五維每案: DB kinds/text/flags+無dangling (或預期dangling)、JSONL round-trip、
 * reopen reload、flushDurable；UI僅A正/A反(shape-pin)/D正/D反 (B/C無UI斷言)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LateFailureSystemAttributionPhase4Test {

  companion object {
    private const val SETTLE_MS = 300L
    private const val STALL_MS = 800L
  }

  /** Final SUCCESS stalls then commits; system fails if ever attempted (stray). */
  private class FinalSuccessStraySystemFailStore(val stallMs: Long = STALL_MS) : FakeSessionStore() {
    val assistantEntered = CountDownLatch(1)
    val systemCalls = AtomicInteger(0)
    override suspend fun appendEvent(event: TranscriptEvent): Long {
      if (event.kind == "assistant") {
        assistantEntered.countDown()
        delay(stallMs)
      } else if (event.kind == "system") {
        systemCalls.incrementAndGet()
        throw IOException("stray system failure")
      }
      return super.appendEvent(event)
    }
  }

  /** Fresh-retry: first partial commits, second stalls-then-fails, system always fails. */
  private class FreshSystemFailStore(val stallMs: Long = STALL_MS) : FakeSessionStore() {
    val assistantCalls = AtomicInteger(0)
    val systemCalls = AtomicInteger(0)
    val secondEntered = CountDownLatch(1)
    override suspend fun appendEvent(event: TranscriptEvent): Long {
      if (event.kind == "assistant") {
        if (assistantCalls.incrementAndGet() == 2) {
          secondEntered.countDown()
          delay(stallMs)
          throw IOException("fresh terminal failure")
        }
      } else if (event.kind == "system") {
        systemCalls.incrementAndGet()
        throw IOException("injected system failure")
      }
      return super.appendEvent(event)
    }
    override suspend fun appendEvents(events: List<TranscriptEvent>): List<Long> {
      // Atomic batch containing a system row must also fail (Room parity).
      if (events.any { it.kind == "system" }) {
        systemCalls.addAndGet(events.count { it.kind == "system" })
        throw IOException("injected batch system failure")
      }
      return super.appendEvents(events)
    }
  }

  private fun controller(
    provider: P4AttrProvider,
    sink: SessionTranscriptSink,
    n: AtomicInteger,
    retry: TurnRetryConfig = TurnRetryConfig(maxRetries = 1, retryDelaysMs = listOf(5_000L)),
  ): TurnController = TurnController(
    provider = provider,
    policy = P4AttrPolicy(),
    transcript = sink,
    retryConfig = retry,
    dispatcher = Dispatchers.Default,
    sleeper = {},
    newId = { "p4attr-${n.incrementAndGet() - 1}" },
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
      val snapshot: List<TranscriptEvent> = synchronized(store.events) { ArrayList(store.events) }
      if (cond(snapshot)) return
      Thread.sleep(25)
    }
    throw AssertionError("ledger condition not met in ${timeoutMs}ms: ${store.events}")
  }

  private fun awaitReceiptTerminal(c: TurnController, timeoutMs: Long = 8_000L): String {
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
      } else {
        return "REVOKED"
      }
      Thread.sleep(25)
    }
    throw AssertionError("receipt never reached a terminal state")
  }

  private fun assertFiveDim(
    store: FakeSessionStore,
    sid: String,
    c: TurnController,
    expectDurable: Boolean,
  ) {
    // JSONL round-trip incl. linkage columns.
    for (e in store.events) {
      val back = JsonlCodec.decode(1, JsonlCodec.encode(e.copy(seq = e.seq.coerceAtLeast(1))))
      assertEquals("isFinal must round-trip for ${e.kind}", e.isFinal, back.isFinal)
      assertEquals("isPartial must round-trip for ${e.kind}", e.isPartial, back.isPartial)
      assertEquals("parentRunId must round-trip for ${e.kind}", e.parentRunId, back.parentRunId)
      assertEquals("attemptIndex must round-trip for ${e.kind}", e.attemptIndex, back.attemptIndex)
    }
    // Reopen reload equals.
    val reloaded = runBlocking { store.loadEvents(sid, 0, 200) }
    assertEquals(
      store.events.map { it.runId to it.text to it.kind },
      reloaded.map { it.runId to it.text to it.kind },
    )
    // flushDurable.
    val durable = runBlocking { withTimeout(8_000) { c.flushDurable() } }
    assertEquals("flushDurable must be $expectDurable", expectDurable, durable)
  }

  // ---- A正: final SUCCESS + stray system FAIL → 零第二assistant. ----

  @Test fun finalSuccessStraySystemFail_noSecondAssistant() {
    // Given: final-intent SUCCESS (stall/commit) + stray system FAIL store.
    val store = FinalSuccessStraySystemFailStore()
    val sid = runBlocking { store.createSession("hi", "m") }
    val provider = P4AttrProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n, TurnRetryConfig())
    try {
      // When: cancel lands while SUBMITTING (stall > settle).
      val host = startedHost(c)
      assertTrue("terminal never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      joinHost(host)
      awaitLedger(store) { evts -> evts.count { it.kind == "assistant" } == 1 && evts.size == 2 }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      // P0-1 fence: benign commit-wins revokes (REVOKED) before durable check.
      assertEquals("REVOKED", awaitReceiptTerminal(c))
      // Then: single assistant final, never a second assistant; stray system
      // never attempted (SKIPPED stays SKIPPED, systemCalls == 0).
      assertEquals(listOf("user", "assistant"), store.events.map { it.kind })
      assertEquals(0, store.systemCalls.get())
      assertEquals(1, store.events.count { it.kind == "assistant" })
      assertEquals("ok", store.events.single { it.kind == "assistant" }.text)
      assertEquals(true, store.events.single { it.kind == "assistant" }.isFinal)
      assertTrue(findDanglingRunIds(store.events).isEmpty())
      assertEquals(1, c.uiState.value.messages.count { it.role == "assistant" })
      assertFiveDim(store, sid, c, expectDurable = true)
      assertEquals(1, provider.streamCalls.get())
    } finally {
      c.close()
    }
  }

  // ---- A反 (shape-pin): final SUCCESS + system-SUCCESS能力 → 同形零新增. ----

  @Test fun finalSuccessSystemSuccessShapePin_noSecondAssistant() {
    // Given: final SUCCESS with a system-SUCCESS-capable store (shape pin:
    // final-SUCCESS never invokes system, so zero system writes is by design
    // in both twins; this twin uses a SUCCESS store to pin identical shape).
    val store = object : FakeSessionStore() {
      val assistantEntered = CountDownLatch(1)
      val systemCalls = AtomicInteger(0)
      override suspend fun appendEvent(event: TranscriptEvent): Long {
        if (event.kind == "assistant") {
          assistantEntered.countDown()
          delay(STALL_MS)
        } else if (event.kind == "system") {
          systemCalls.incrementAndGet()
        }
        return super.appendEvent(event)
      }
    }
    val sid = runBlocking { store.createSession("hi", "m") }
    val provider = P4AttrProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n, TurnRetryConfig())
    try {
      // When: same cancel-while-SUBMITTING shape (no system write expected).
      val host = startedHost(c)
      assertTrue("terminal never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      joinHost(host)
      awaitLedger(store) { evts -> evts.count { it.kind == "assistant" } == 1 && evts.size == 2 }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      assertEquals("REVOKED", awaitReceiptTerminal(c))
      // Then: identical shape to the stray-FAIL twin (shape pin: system
      // never attempted in final-SUCCESS, so systemCalls == 0 by design).
      assertEquals(listOf("user", "assistant"), store.events.map { it.kind })
      assertEquals(1, store.events.count { it.kind == "assistant" })
      assertEquals(0, store.systemCalls.get())
      assertTrue(findDanglingRunIds(store.events).isEmpty())
      assertEquals(1, c.uiState.value.messages.count { it.role == "assistant" })
      assertFiveDim(store, sid, c, expectDurable = true)
      // Probe: the SUCCESS capability is real (system write lands, no throw).
      val probeSid = runBlocking { store.createSession("probe", "m") }
      runBlocking {
        store.appendEvent(
          TranscriptEvent(
            sessionId = probeSid, runId = "probe", kind = "system",
            text = "probe-ok", createdAt = 1L,
          ),
        )
      }
      assertEquals(1, store.systemCalls.get())
    } finally {
      c.close()
    }
  }

  // ---- B正: post-ack cancel → 零新增. ----

  @Test fun postAckCancel_zeroNewRows() {
    // Given: a committed final SUCCESS turn (no stall, no receipt).
    val store = FakeSessionStore()
    val sid = runBlocking { store.createSession("hi", "m") }
    val provider = P4AttrProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n, TurnRetryConfig())
    try {
      val host = startedHost(c)
      joinHost(host)
      awaitLedger(store) { evts -> evts.count { it.kind == "assistant" } == 1 && evts.size == 2 }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      val sizeBefore = synchronized(store.events) { ArrayList(store.events).size }
      // When: cancel lands AFTER the system/terminal ack (post-ack).
      c.cancel()
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      // Then: zero new rows (post-ack cancel is a status-only no-op).
      val sizeAfter = synchronized(store.events) { ArrayList(store.events).size }
      assertEquals("post-ack cancel must add zero rows", sizeBefore, sizeAfter)
      assertEquals(listOf("user", "assistant"), store.events.map { it.kind })
      assertTrue(findDanglingRunIds(store.events).isEmpty())
      assertFiveDim(store, sid, c, expectDurable = true)
    } finally {
      c.close()
    }
  }

  // ---- B反: pre-ack cancel (stall) → 補償 (對照 post-ack 零新增). ----

  @Test fun preAckCancelControl_compensates() {
    // Given: same final shape but terminal stalls then FAILs once (receipt
    // installed); the compensation rewrite must succeed (fail only call 1).
    val failing = object : FakeSessionStore() {
      val entered = CountDownLatch(1)
      val calls = AtomicInteger(0)
      override suspend fun appendEvent(event: TranscriptEvent): Long {
        if (event.kind == "assistant" && calls.incrementAndGet() == 1) {
          entered.countDown()
          delay(STALL_MS)
          throw IOException("late terminal failure")
        }
        return super.appendEvent(event)
      }
    }
    val sid = runBlocking { failing.createSession("hi", "m") }
    val provider = P4AttrProvider {
      flow { emit(StreamEvent.Failed("boom", retryable = false)) }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(failing, sid), n, TurnRetryConfig())
    try {
      // When: cancel lands while SUBMITTING (pre-ack).
      val host = startedHost(c)
      assertTrue("terminal never entered", failing.entered.await(5, TimeUnit.SECONDS))
      c.cancel()
      joinHost(host)
      awaitLedger(failing) { evts -> evts.count { it.kind == "assistant" } == 1 && evts.size == 2 }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      // Then: pre-ack FAIL compensates a final (differs from post-ack zero).
      assertEquals(listOf("user", "assistant"), failing.events.map { it.kind })
      assertEquals(true, failing.events[1].isFinal)
      assertTrue(findDanglingRunIds(failing.events).isEmpty())
      assertFiveDim(failing, sid, c, expectDurable = false)
    } finally {
      c.close()
    }
  }

  // ---- C正: fresh-retry-id x system FAIL → SEALED + 連結保留. ----

  @Test fun freshRetrySystemFail_sealedPreservesLinkage() {
    // Given: first partial commits, fresh second stalls-then-fails, system FAILs.
    val store = FreshSystemFailStore()
    val sid = runBlocking { store.createSession("hi", "m") }
    val attemptNo = AtomicInteger(0)
    val provider = P4AttrProvider {
      flow {
        if (attemptNo.incrementAndGet() == 1) {
          emit(StreamEvent.TextDelta(0, 0, "half1"))
          emit(StreamEvent.Failed("boom1", retryable = true))
        } else {
          emit(StreamEvent.TextDelta(0, 0, "half2"))
          emit(StreamEvent.Failed("boom2", retryable = true))
        }
      }
    }
    val n = AtomicInteger(0)
    val c = controller(
      provider, SessionTranscriptSink(store, sid), n,
      TurnRetryConfig(maxRetries = 2, retryDelaysMs = listOf(0L, 0L)),
    )
    try {
      // When: cancel lands while the fresh terminal is SUBMITTING.
      val host = startedHost(c)
      assertTrue("fresh terminal never entered", store.secondEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      joinHost(host)
      assertEquals("SEALED", awaitReceiptTerminal(c))
      awaitLedger(store) { evts ->
        evts.any { it.kind == "retry" && it.text.startsWith("cancel requested") }
      }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      // Then: batch rolled back (no batch assistant/system), ledger owns
      // user + first partial + notices; family dangles for backfill; the
      // committed first partial keeps parentRunId=null/attemptIndex=0.
      val kinds = store.events.map { it.kind }
      assertEquals(listOf("user", "assistant", "retry", "retry"), kinds)
      val logical = store.events[0].runId
      val firstPartial = store.events[1]
      assertEquals(logical, firstPartial.runId)
      assertEquals(null, firstPartial.parentRunId)
      assertEquals(0, firstPartial.attemptIndex)
      assertEquals(false, firstPartial.isFinal)
      val notice = store.events[3]
      assertEquals(logical, notice.runId)
      assertTrue(notice.text.contains("attempt "))
      assertEquals(listOf(logical), findDanglingRunIds(store.events))
      assertFiveDim(store, sid, c, expectDurable = false)
    } finally {
      c.close()
    }
  }

  // ---- C反: fresh-retry-id x system SUCCESS → COMPENSATED (對照). ----

  @Test fun freshRetrySystemSuccessControl_compensated() {
    // Given: same fresh shape but system writes succeed (batch lands).
    val store = object : FakeSessionStore() {
      val assistantCalls = AtomicInteger(0)
      val secondEntered = CountDownLatch(1)
      override suspend fun appendEvent(event: TranscriptEvent): Long {
        if (event.kind == "assistant" && assistantCalls.incrementAndGet() == 2) {
          secondEntered.countDown()
          delay(STALL_MS)
          throw IOException("fresh terminal failure")
        }
        return super.appendEvent(event)
      }
    }
    val sid = runBlocking { store.createSession("hi", "m") }
    val attemptNo = AtomicInteger(0)
    val provider = P4AttrProvider {
      flow {
        if (attemptNo.incrementAndGet() == 1) {
          emit(StreamEvent.TextDelta(0, 0, "half1"))
          emit(StreamEvent.Failed("boom1", retryable = true))
        } else {
          emit(StreamEvent.TextDelta(0, 0, "half2"))
          emit(StreamEvent.Failed("boom2", retryable = true))
        }
      }
    }
    val n = AtomicInteger(0)
    val c = controller(
      provider, SessionTranscriptSink(store, sid), n,
      TurnRetryConfig(maxRetries = 2, retryDelaysMs = listOf(0L, 0L)),
    )
    try {
      // When: cancel lands while the fresh terminal is SUBMITTING.
      val host = startedHost(c)
      assertTrue("fresh terminal never entered", store.secondEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      joinHost(host)
      awaitLedger(store) { evts ->
        evts.any { it.kind == "system" } && evts.count { it.kind == "assistant" } == 2
      }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      // Then: batch lands with linkage (fresh runId != logical,
      // parentRunId=logical, attemptIndex=1); family closed.
      assertEquals(
        listOf("user", "assistant", "retry", "retry", "assistant", "system"),
        store.events.map { it.kind },
      )
      val logical = store.events[0].runId
      val batchPartial = store.events[4]
      assertTrue(batchPartial.runId != logical)
      assertEquals(logical, batchPartial.parentRunId)
      assertEquals(1, batchPartial.attemptIndex)
      assertEquals("COMPENSATED", awaitReceiptTerminal(c))
      assertTrue(findDanglingRunIds(store.events).isEmpty())
      assertFiveDim(store, sid, c, expectDurable = false)
    } finally {
      c.close()
    }
  }

  // ---- D正: final SUCCESS stray + reasoning=true → marker同行不誤補. ----

  @Test fun finalSuccessStraySystemFailReasoning_noSecondAssistantKeepsMarker() {
    // Given: reasoning-sourced final SUCCESS (stall/commit) + stray system FAIL.
    val store = FinalSuccessStraySystemFailStore()
    val sid = runBlocking { store.createSession("hi", "m") }
    val provider = P4AttrProvider {
      flow {
        emit(StreamEvent.ReasoningDelta(0, 0, "think"))
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n, TurnRetryConfig())
    try {
      // When: cancel lands while SUBMITTING.
      val host = startedHost(c)
      assertTrue("terminal never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      joinHost(host)
      awaitLedger(store) { evts -> evts.any { it.kind == "assistant" } }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      assertEquals("REVOKED", awaitReceiptTerminal(c))
      // Then: exactly one assistant (never a stray second), reasoning marker
      // rides with the same runId, system never attempted.
      assertEquals(1, store.events.count { it.kind == "assistant" })
      assertEquals(0, store.systemCalls.get())
      val asst = store.events.single { it.kind == "assistant" }
      assertEquals("thinkok", asst.text)
      val markers = store.events.filter { it.kind == "reasoning" }
      assertEquals(1, markers.size)
      assertEquals(asst.runId, markers.single().runId)
      assertEquals("", markers.single().text)
      assertTrue(findDanglingRunIds(store.events).isEmpty())
      val live = c.uiState.value.messages.single { it.role == "assistant" }
      assertTrue("live partial must stay reasoning-sourced", live.hasReasoning)
      assertFiveDim(store, sid, c, expectDurable = true)
    } finally {
      c.close()
    }
  }

  // ---- D反: plain (reasoning=false) twin → 無marker (對照). ----

  @Test fun finalSuccessStraySystemFailPlainControl_noMarker() {
    // Given: same final SUCCESS stray shape but plain (no reasoning).
    val store = FinalSuccessStraySystemFailStore()
    val sid = runBlocking { store.createSession("hi", "m") }
    val provider = P4AttrProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n, TurnRetryConfig())
    try {
      // When: cancel lands while SUBMITTING.
      val host = startedHost(c)
      assertTrue("terminal never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      joinHost(host)
      awaitLedger(store) { evts -> evts.any { it.kind == "assistant" } }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      assertEquals("REVOKED", awaitReceiptTerminal(c))
      // Then: control stays marker-free (reasoning flag is the only delta).
      assertEquals(1, store.events.count { it.kind == "assistant" })
      assertTrue(store.events.none { it.kind == "reasoning" })
      assertFalse(c.uiState.value.messages.single { it.role == "assistant" }.hasReasoning)
      assertFiveDim(store, sid, c, expectDurable = true)
    } finally {
      c.close()
    }
  }
}
