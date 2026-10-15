package dev.librepocket.chat

import dev.librepocket.agent.ui.chat.ChatSessionFactory
import dev.librepocket.agent.ui.chat.SessionStoreSource
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.agent.ui.chat.allowKeyReadPolicy
import dev.librepocket.agent.ui.chat.cancelledMarkerText
import dev.librepocket.agent.ui.chat.findDanglingRunIds
import dev.librepocket.agent.ui.chat.interruptedMarkerText
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
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

private class R181Provider(
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

private class R181Policy : PolicyStore {
  override fun evaluate(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
  override suspend fun setRule(rule: PolicyRule) = Unit
  override suspend fun removeRule(pattern: String) = Unit
  override suspend fun listRules(): List<PolicyRule> = emptyList()
  override suspend fun evaluateFresh(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

private class R181ReopenProvider : LlmProvider {
  override val protocol: ProviderProto = ProviderProto.CHAT_COMPLETIONS
  override fun stream(request: ChatRequest): Flow<StreamEvent> = emptyFlow()
  override suspend fun listModels(): List<String> = emptyList()
}

/**
 * R18-1 Stage 2 regressions: retryable cancel is notice-first (durable
 * non-terminal), SUCCESS appends system in order, FAILED writes atomic
 * partial+system batch. Kill / receipt-timeout before the deferred write
 * leaves the family dangling (reopen INTERRUPTED), never user+system closed
 * without the partial. Verified with real [ChatSessionFactory.open]/backfill
 * (not just loadEvents): UI/dangling/JSONL + second-open-zero. Tests inject a
 * short [TurnController.cancelReceiptMaxRounds] seam instead of waiting ~29s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RetryableCancelNoticeFirstTest {

  companion object {
    private const val SETTLE_MS = 300L
    private const val STALL_MS = 800L
    private const val MODEL = "preset:openai@https://api.openai.com/gpt-4o-mini"
  }

  private open class R181LateStore(
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
          kotlinx.coroutines.delay(stallMs)
          failWith?.let { throw it }
        }
      }
      return super.appendEvent(event)
    }
  }

  private fun controller(
    provider: R181Provider,
    sink: SessionTranscriptSink,
    n: AtomicInteger,
    settleMs: Long = SETTLE_MS,
    maxRounds: Int = 60,
    retry: TurnRetryConfig = TurnRetryConfig(maxRetries = 1, retryDelaysMs = listOf(5_000L)),
  ): TurnController = TurnController(
    provider = provider,
    policy = R181Policy(),
    transcript = sink,
    retryConfig = retry,
    dispatcher = Dispatchers.Default,
    sleeper = {},
    newId = { "r181-${n.incrementAndGet() - 1}" },
    cancelSettleTimeoutMs = settleMs,
    cancelCompensationTimeoutMs = 5_000L,
    cancelReceiptMaxRounds = maxRounds,
  )

  private fun endpoint(): EndpointConfig = EndpointConfig(
    providerId = "preset:openai",
    presetId = "openai",
    label = "OpenAI",
    baseUrl = "https://api.openai.com/v1",
    protocol = ProviderProto.CHAT_COMPLETIONS,
    model = "gpt-4o-mini",
    apiKeyRef = "provider_key/preset:openai",
  )

  private fun factory(store: FakeSessionStore): ChatSessionFactory = ChatSessionFactory(
    policy = allowKeyReadPolicy(),
    vaultSource = VaultSource {
      val vault = EncryptedPrefsVault(InMemoryPrefs())
      vault.putKey("preset:openai", "test-key-12345678".toCharArray())
      vault
    },
    buildProvider = { _, _ -> R181ReopenProvider() },
    sessionStores = object : SessionStoreSource {
      override suspend fun store() = store
    },
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

  private fun awaitReceiptState(c: TurnController, timeoutMs: Long = 8_000L): String {
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
      }
      Thread.sleep(25)
    }
    throw AssertionError("receipt never reached a terminal state")
  }

  /**
   * P0-1 Phase4 fence: two flush drains with zero-new-rows断言. Replaces
   * `Thread.sleep(400/900)` guess-waits in benign/deadline tests.
   *
   * Given: host joined + ledger shape awaited.
   * When: two consecutive flushTranscript drains run after the receipt
   *   reaches a terminal state.
   * Then: ledger size is identical (no late duplicate can still land).
   */
  private fun flushTwiceAssertStable(c: TurnController, store: FakeSessionStore) {
    runBlocking { withTimeout(8_000) { c.flushTranscript() } }
    val before = synchronized(store.events) { ArrayList(store.events).size }
    // Strict fence (never swallowed): every caller already awaits its ledger
    // shape + receipt terminal above, so a timeout here is a real wedge.
    awaitReceiptState(c, timeoutMs = 8_000L)
    runBlocking { withTimeout(8_000) { c.flushTranscript() } }
    val after = synchronized(store.events) { ArrayList(store.events).size }
    assertEquals("two flush drains must add zero rows (fence, not sleep)", before, after)
  }

  @Test fun retryableLateFailBatchSuccessReopenTerminal() {
    val store = R181LateStore(failPredicate = { it == 1 })
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = R181Provider {
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
      awaitLedger(store) { evts ->
        evts.any { it.kind == "system" } && evts.count { it.kind == "assistant" } == 1 &&
          evts.any { it.kind == "retry" && it.text.startsWith("cancel requested") }
      }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      assertEquals(listOf("user", "retry", "assistant", "system"), store.events.map { it.kind })
      val attemptId = store.events[0].runId
      assertEquals("half", store.events.single { it.kind == "assistant" }.text)
      assertEquals(false, store.events.single { it.kind == "assistant" }.isFinal)
      assertTrue(findDanglingRunIds(store.events).isEmpty())
      for (e in store.events) {
        val back = JsonlCodec.decode(1, JsonlCodec.encode(e.copy(seq = e.seq.coerceAtLeast(1))))
        assertEquals(e.isFinal, back.isFinal)
      }
      assertEquals("half", c.uiState.value.messages.single { it.role == "assistant" }.text)
      assertEquals("COMPENSATED", awaitReceiptState(c))
    } finally {
      // Keep rows for reopen: close without dropping them (flush already drained).
      c.close()
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
    }
    // Real reopen/backfill: family already closed by the batch system mark,
    // so zero INTERRUPTED and second open adds zero.
    val f = factory(store)
    val ep = endpoint()
    val first = runBlocking { f.open(ep, sid) }
    try {
      val afterFirst = store.events.filter { it.sessionId == sid }.sortedBy { it.seq }
      assertEquals(1, afterFirst.count { it.kind == "assistant" })
      assertTrue(afterFirst.any { it.kind == "system" && it.text == cancelledMarkerText(afterFirst[0].runId) })
      assertTrue(afterFirst.none { it.text.endsWith("interrupted") })
      assertTrue(findDanglingRunIds(afterFirst).isEmpty())
    } finally {
      first.session.close()
    }
    val sizeAfterFirst = store.events.count { it.sessionId == sid }
    val second = runBlocking { f.open(ep, sid) }
    try {
      val afterSecond = store.events.filter { it.sessionId == sid }.sortedBy { it.seq }
      assertEquals(sizeAfterFirst, afterSecond.size)
      assertTrue(findDanglingRunIds(afterSecond).isEmpty())
    } finally {
      second.session.close()
    }
  }

  @Test fun retryableLateSuccessSystemReopenTerminal() {
    val store = R181LateStore(failPredicate = { it == 1 }, failWith = null)
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = R181Provider {
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
      // P0-1 fence: deferred system mark awaited via ledger shape + receipt
      // terminal + two flush drains zero-new (replaces sleep 400).
      awaitLedger(store) { evts ->
        evts.any { it.kind == "system" } && evts.count { it.kind == "assistant" } == 1
      }
      flushTwiceAssertStable(c, store)
      assertEquals(listOf("user", "assistant", "retry", "system"), store.events.map { it.kind })
      assertTrue(findDanglingRunIds(store.events).isEmpty())
      assertEquals("half", c.uiState.value.messages.single { it.role == "assistant" }.text)
    } finally {
      c.close()
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
    }
    val f = factory(store)
    val ep = endpoint()
    val first = runBlocking { f.open(ep, sid) }
    try {
      val afterFirst = store.events.filter { it.sessionId == sid }.sortedBy { it.seq }
      assertTrue(afterFirst.none { it.text.endsWith("interrupted") })
      assertTrue(findDanglingRunIds(afterFirst).isEmpty())
    } finally {
      first.session.close()
    }
    val sizeAfterFirst = store.events.count { it.sessionId == sid }
    val second = runBlocking { f.open(ep, sid) }
    try {
      assertEquals(sizeAfterFirst, store.events.count { it.sessionId == sid })
    } finally {
      second.session.close()
    }
  }

  @Test fun successLateFailReopenTerminal() {
    val store = R181LateStore(failPredicate = { it == 1 })
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = R181Provider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(
      provider, SessionTranscriptSink(store, sid), n,
      retry = TurnRetryConfig(),
    )
    try {
      val host = startedHost(c)
      assertTrue("terminal never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      joinHost(host)
      awaitLedger(store) { evts -> evts.count { it.kind == "assistant" } == 1 && evts.size == 2 }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      assertEquals(listOf("user", "assistant"), store.events.map { it.kind })
      assertTrue(findDanglingRunIds(store.events).isEmpty())
    } finally {
      c.close()
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
    }
    val f = factory(store)
    val ep = endpoint()
    val first = runBlocking { f.open(ep, sid) }
    try {
      val afterFirst = store.events.filter { it.sessionId == sid }.sortedBy { it.seq }
      assertTrue(afterFirst.none { it.text.endsWith("interrupted") })
      assertTrue(findDanglingRunIds(afterFirst).isEmpty())
    } finally {
      first.session.close()
    }
  }

  @Test fun deadlineExpiredLateFailReopenInterrupted() {
    // Short receipt horizon (3 rounds ≈ 50+100+200ms) vs 800ms stall: the waiter
    // times out to SEALED before the late FAIL, so no batch is written and the
    // family stays dangling for reopen INTERRUPTED.
    val store = R181LateStore()
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = R181Provider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        emit(StreamEvent.Failed("boom", retryable = true))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n, maxRounds = 3)
    try {
      val host = startedHost(c)
      assertTrue("terminal never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      joinHost(host)
      assertEquals("SEALED", awaitReceiptState(c, timeoutMs = 8_000L))
      // Late FAIL lands after the deadline: no batch (receipt already SEALED),
      // ledger owns user + notice only (terminal left no row).
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      // Flush may still be draining the stalled terminal FAIL; poll until the
      // writer settles, then assert the dangling shape (no system, no partial).
      awaitLedger(store, timeoutMs = 8_000L) { evts ->
        evts.any { it.kind == "retry" && it.text.startsWith("cancel requested") }
      }
      // Give the late FAIL a moment to settle (it must NOT write a batch now).
      // P0-1 fence: SEALED already awaited above; drain the stalled terminal
      // FAIL via ledger fence (notice present + assistantCalls settled) then
      // two flush drains zero-new — replaces sleep 900.
      awaitLedger(store, timeoutMs = 8_000L) { evts ->
        evts.any { it.kind == "retry" && it.text.startsWith("cancel requested") } &&
          store.assistantCalls.get() >= 1
      }
      flushTwiceAssertStable(c, store)
      val kinds = store.events.map { it.kind }
      assertTrue("deadline-expired must not compensate, was $kinds", kinds.none { it == "system" })
      assertTrue("deadline-expired must not compensate, was $kinds", kinds.none { it == "assistant" })
      val attemptId = store.events[0].runId
      assertEquals(listOf(attemptId), findDanglingRunIds(store.events))
      for (e in store.events) {
        val back = JsonlCodec.decode(1, JsonlCodec.encode(e.copy(seq = e.seq.coerceAtLeast(1))))
        assertEquals(e.isFinal, back.isFinal)
      }
    } finally {
      c.close()
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
    }
    // Real reopen/backfill: dangling user(+notice) gains exactly one INTERRUPTED.
    val f = factory(store)
    val ep = endpoint()
    val sizeBefore = store.events.count { it.sessionId == sid }
    val first = runBlocking { f.open(ep, sid) }
    try {
      val afterFirst = store.events.filter { it.sessionId == sid }.sortedBy { it.seq }
      assertEquals(sizeBefore + 1, afterFirst.size)
      val mark = afterFirst.last()
      assertEquals("system", mark.kind)
      assertEquals(interruptedMarkerText(afterFirst[0].runId), mark.text)
      assertTrue(findDanglingRunIds(afterFirst).isEmpty())
    } finally {
      first.session.close()
    }
    val sizeAfterFirst = store.events.count { it.sessionId == sid }
    val second = runBlocking { f.open(ep, sid) }
    try {
      assertEquals(sizeAfterFirst, store.events.count { it.sessionId == sid })
    } finally {
      second.session.close()
    }
  }

  @Test fun preCompensationDeathSeedReopenInterrupted() = runBlocking {
    // Simulates a kill after the notice persisted but before the deferred
    // batch (user + notice only, no partial, no system): reopen must add
    // INTERRUPTED, never silently close.
    val store = FakeSessionStore()
    val sid = store.createSession("hi", MODEL)
    val sink = SessionTranscriptSink(store, sid) { 1L }
    sink.onTurnStarted("L", "q")
    sink.onTurnCancelRequested("L", "L", 0)
    assertEquals(listOf("L"), findDanglingRunIds(store.events))
    val f = factory(store)
    val ep = endpoint()
    val first = f.open(ep, sid)
    try {
      val afterFirst = store.events.filter { it.sessionId == sid }.sortedBy { it.seq }
      assertEquals(3, afterFirst.size)
      assertEquals(interruptedMarkerText("L"), afterFirst.last().text)
      assertTrue(findDanglingRunIds(afterFirst).isEmpty())
    } finally {
      first.session.close()
    }
    val sizeAfterFirst = store.events.count { it.sessionId == sid }
    val second = f.open(ep, sid)
    try {
      assertEquals(sizeAfterFirst, store.events.count { it.sessionId == sid })
    } finally {
      second.session.close()
    }
  }

  @Test fun oldGapUserSystemWithoutPartialNotRetroactive() = runBlocking {
    // Known historical gap (pre-R18-1 premature close): user + system cancelled
    // without the partial. Family is already closed, so reopen must NOT add
    // INTERRUPTED and must NOT backfill a partial (no retroactive fix).
    val store = FakeSessionStore()
    val sid = store.createSession("hi", MODEL)
    val sink = SessionTranscriptSink(store, sid) { 1L }
    sink.onTurnStarted("L", "q")
    sink.onLogicalTurnCancelled("L", "L", "", 0)
    assertTrue(findDanglingRunIds(store.events).isEmpty())
    val f = factory(store)
    val ep = endpoint()
    val sizeBefore = store.events.count { it.sessionId == sid }
    val first = f.open(ep, sid)
    try {
      val afterFirst = store.events.filter { it.sessionId == sid }.sortedBy { it.seq }
      assertEquals(sizeBefore, afterFirst.size)
      assertTrue(afterFirst.none { it.text.endsWith("interrupted") })
      assertTrue(findDanglingRunIds(afterFirst).isEmpty())
    } finally {
      first.session.close()
    }
  }

  private fun commitCountOf(c: TurnController, runId: String): Int {
    val f = TurnController::class.java.getDeclaredField("orderedTranscript")
    f.isAccessible = true
    val sink = f.get(c) as OrderedTranscriptSink
    return sink.coreCommitCount(runId)
  }

  @Test fun freshRetryLateFailBatchSuccessReopenTerminal() {
    // R18-1 fresh-retry-id path (cancelId != logicalTurnId): first attempt fails
    // retryable and commits (attempt 0 intermediate), the retry mints a fresh id
    // (attempt 1), and cancel lands while the fresh intermediate is still
    // SUBMITTING (second assistant write stalled). The waiter must take the
    // >= 1 branch (fresh batch single entry = 1 commit) and write the atomic
    // batch partial(parentRunId=logical)+system(runId=logical); the cancel notice
    // stays bound to the logical family (runId=logical). Verified with real
    // Factory.open/backfill: UI/dangling/JSONL + second-open-zero.
    val store = R181LateStore(failPredicate = { it == 2 })
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val attemptNo = AtomicInteger(0)
    val provider = R181Provider {
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
      retry = TurnRetryConfig(maxRetries = 2, retryDelaysMs = listOf(0L, 0L)),
    )
    try {
      val host = startedHost(c)
      assertTrue("fresh terminal never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      joinHost(host)
      awaitLedger(store) { evts ->
        evts.any { it.kind == "system" } && evts.count { it.kind == "assistant" } == 2 &&
          evts.any { it.kind == "retry" && it.text.startsWith("cancel requested") }
      }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      assertEquals(
        listOf("user", "assistant", "retry", "retry", "assistant", "system"),
        store.events.map { it.kind },
      )
      val logical = store.events[0].runId
      val firstPartial = store.events[1]
      assertEquals("assistant", firstPartial.kind)
      assertEquals(logical, firstPartial.runId)
      assertEquals(null, firstPartial.parentRunId)
      assertEquals(0, firstPartial.attemptIndex)
      assertEquals("half1", firstPartial.text)
      assertEquals(false, firstPartial.isFinal)
      assertEquals(true, firstPartial.isPartial)
      val firstRetry = store.events[2]
      assertEquals("retry", firstRetry.kind)
      assertEquals(logical, firstRetry.runId)
      val notice = store.events[3]
      assertEquals("retry", notice.kind)
      assertEquals(logical, notice.runId)
      assertTrue("cancel notice must start with prefix, was ${notice.text}", notice.text.startsWith("cancel requested"))
      val batchPartial = store.events[4]
      assertEquals("assistant", batchPartial.kind)
      val fresh = batchPartial.runId
      assertTrue("fresh retry must mint a fresh id, was $fresh vs logical $logical", fresh != logical)
      assertTrue("cancel notice must name the fresh attempt, was ${notice.text}", notice.text.contains(fresh))
      assertEquals(logical, batchPartial.parentRunId)
      assertEquals(1, batchPartial.attemptIndex)
      assertEquals("half2", batchPartial.text)
      assertEquals(true, batchPartial.isPartial)
      assertEquals(false, batchPartial.isFinal)
      assertEquals("partial lost (store failure)", batchPartial.failureReason)
      val mark = store.events[5]
      assertEquals("system", mark.kind)
      assertEquals(logical, mark.runId)
      assertEquals(cancelledMarkerText(logical), mark.text)
      assertEquals(true, mark.isFinal)
      // >= 1 path: fresh batch is a single core entry keyed by the fresh id
      // (batch(1) = 1 holds); the logical family owns user + first partial (= 2).
      assertEquals(2, commitCountOf(c, logical))
      assertEquals(1, commitCountOf(c, fresh))
      assertTrue(findDanglingRunIds(store.events).isEmpty())
      for (e in store.events) {
        val back = JsonlCodec.decode(1, JsonlCodec.encode(e.copy(seq = e.seq.coerceAtLeast(1))))
        assertEquals(e.isFinal, back.isFinal)
        assertEquals(e.parentRunId, back.parentRunId)
        assertEquals(e.attemptIndex, back.attemptIndex)
      }
      val assistants = c.uiState.value.messages.filter { it.role == "assistant" }
      assertEquals(2, assistants.size)
      assertEquals("half1", assistants[0].text)
      assertEquals("half2", assistants[1].text)
      assertEquals("COMPENSATED", awaitReceiptState(c))
    } finally {
      c.close()
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
    }
    val f = factory(store)
    val ep = endpoint()
    val first = runBlocking { f.open(ep, sid) }
    try {
      val afterFirst = store.events.filter { it.sessionId == sid }.sortedBy { it.seq }
      assertEquals(2, afterFirst.count { it.kind == "assistant" })
      val reopenBatch = afterFirst.filter { it.kind == "assistant" }[1]
      assertTrue("fresh batch must survive reopen, was ${reopenBatch.runId}", reopenBatch.runId != afterFirst[0].runId)
      assertEquals(afterFirst[0].runId, reopenBatch.parentRunId)
      assertEquals(afterFirst[0].runId, afterFirst.single { it.kind == "retry" && it.text.startsWith("cancel requested") }.runId)
      assertTrue(afterFirst.any { it.kind == "system" && it.text == cancelledMarkerText(afterFirst[0].runId) })
      assertTrue(afterFirst.none { it.text.endsWith("interrupted") })
      assertTrue(findDanglingRunIds(afterFirst).isEmpty())
    } finally {
      first.session.close()
    }
    val sizeAfterFirst = store.events.count { it.sessionId == sid }
    val second = runBlocking { f.open(ep, sid) }
    try {
      val afterSecond = store.events.filter { it.sessionId == sid }.sortedBy { it.seq }
      assertEquals(sizeAfterFirst, afterSecond.size)
      assertTrue(findDanglingRunIds(afterSecond).isEmpty())
    } finally {
      second.session.close()
    }
  }

  @Test fun freshRetryDeadlineExpiredLateFailReopenInterrupted() {
    // Fresh-id deadline-expired twin: same two-attempt shape, but the receipt
    // horizon is 3 rounds vs the 800ms stall, so the waiter times out to SEALED
    // before the fresh late FAIL. No batch is written; the ledger owns user +
    // first partial + first retry + cancel notice (both notices runId=logical),
    // the family stays dangling for reopen INTERRUPTED.
    // Stall only the fresh intermediate (call 2); the first partial (call 1) commits.
    val stalling = object : FakeSessionStore() {
      val assistantCalls = AtomicInteger(0)
      val assistantEntered = CountDownLatch(1)
      override suspend fun appendEvent(event: TranscriptEvent): Long {
        if (event.kind == "assistant" && assistantCalls.incrementAndGet() == 2) {
          assistantEntered.countDown()
          kotlinx.coroutines.delay(STALL_MS)
          throw IOException("late writer failure")
        }
        return super.appendEvent(event)
      }
    }
    val sid = runBlocking { stalling.createSession("hi", MODEL) }
    val attemptNo = AtomicInteger(0)
    val provider = R181Provider {
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
      provider, SessionTranscriptSink(stalling, sid), n, maxRounds = 3,
      retry = TurnRetryConfig(maxRetries = 2, retryDelaysMs = listOf(0L, 0L)),
    )
    try {
      val host = startedHost(c)
      assertTrue("fresh terminal never entered", stalling.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      joinHost(host)
      assertEquals("SEALED", awaitReceiptState(c, timeoutMs = 8_000L))
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      awaitLedger(stalling, timeoutMs = 8_000L) { evts ->
        evts.any { it.kind == "retry" && it.text.startsWith("cancel requested") }
      }
      // P0-1 fence: SEALED already awaited; drain stalled fresh FAIL then two
      // flush drains zero-new — replaces sleep 900.
      flushTwiceAssertStable(c, stalling)
      assertEquals(
        listOf("user", "assistant", "retry", "retry"),
        stalling.events.map { it.kind },
      )
      val logical = stalling.events[0].runId
      val firstPartial = stalling.events[1]
      assertEquals(logical, firstPartial.runId)
      assertEquals(null, firstPartial.parentRunId)
      assertEquals("half1", firstPartial.text)
      assertEquals(false, firstPartial.isFinal)
      assertEquals(logical, stalling.events[2].runId)
      val notice = stalling.events[3]
      assertEquals("retry", notice.kind)
      assertEquals(logical, notice.runId)
      assertTrue("cancel notice must mention a fresh attempt id, was ${notice.text}", notice.text.contains("attempt "))
      val noticeAttempt = notice.text.substringAfter("(attempt ").substringBefore(")")
      assertTrue("cancelId must be fresh, was $noticeAttempt vs logical $logical", noticeAttempt != logical && noticeAttempt.isNotEmpty())
      assertEquals(listOf(logical), findDanglingRunIds(stalling.events))
      for (e in stalling.events) {
        val back = JsonlCodec.decode(1, JsonlCodec.encode(e.copy(seq = e.seq.coerceAtLeast(1))))
        assertEquals(e.isFinal, back.isFinal)
        assertEquals(e.parentRunId, back.parentRunId)
        assertEquals(e.attemptIndex, back.attemptIndex)
      }
    } finally {
      c.close()
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
    }
    val f = factory(stalling)
    val ep = endpoint()
    val sizeBefore = stalling.events.count { it.sessionId == sid }
    val logicalBefore = stalling.events[0].runId
    val first = runBlocking { f.open(ep, sid) }
    try {
      val afterFirst = stalling.events.filter { it.sessionId == sid }.sortedBy { it.seq }
      assertEquals(sizeBefore + 1, afterFirst.size)
      val mark = afterFirst.last()
      assertEquals("system", mark.kind)
      assertEquals(interruptedMarkerText(logicalBefore), mark.text)
      assertTrue(findDanglingRunIds(afterFirst).isEmpty())
    } finally {
      first.session.close()
    }
    val sizeAfterFirst = stalling.events.count { it.sessionId == sid }
    val second = runBlocking { f.open(ep, sid) }
    try {
      assertEquals(sizeAfterFirst, stalling.events.count { it.sessionId == sid })
    } finally {
      second.session.close()
    }
  }
}
