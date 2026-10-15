package dev.librepocket.chat

import dev.librepocket.agent.ui.chat.ChatSessionFactory
import dev.librepocket.agent.ui.chat.SessionStoreSource
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.agent.ui.chat.allowKeyReadPolicy
import dev.librepocket.agent.ui.chat.cancelledMarkerText
import dev.librepocket.agent.ui.chat.findDanglingRunIds
import dev.librepocket.agent.ui.chat.interruptedMarkerText
import dev.librepocket.agent.ui.chat.modelSwitchMarkerWithheld
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
import kotlinx.coroutines.CompletableDeferred
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

private class R191Provider(
  var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
  override val protocol: ProviderProto = ProviderProto.CHAT_COMPLETIONS
  override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
    emitAll(handler(request))
  }
  override suspend fun listModels(): List<String> = emptyList()
}

private class R191Policy : PolicyStore {
  override fun evaluate(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
  override suspend fun setRule(rule: PolicyRule) = Unit
  override suspend fun removeRule(pattern: String) = Unit
  override suspend fun listRules(): List<PolicyRule> = emptyList()
  override suspend fun evaluateFresh(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

private class R191ReopenProvider : LlmProvider {
  override val protocol: ProviderProto = ProviderProto.CHAT_COMPLETIONS
  override fun stream(request: ChatRequest): Flow<StreamEvent> = emptyFlow()
  override suspend fun listModels(): List<String> = emptyList()
}

/**
 * R19-1 regressions: the cancel-receipt waiter must hand its terminal
 * outcome off before the close flush barrier, and [TurnController.flushDurable]
 * must conjoin "no PENDING/CLAIMED receipt" so an immediate close never
 * reports true-and-dangling.
 *
 * Each test uses a controlled store gate (stalled terminal write): cancel
 * installs the receipt while SUBMITTING, then [TurnController.close] runs
 * immediately (no host join — the New Chat / endpoint-switch interleave).
 * Order under test: host settle → receipt outcome handoff
 * (CLAIM/REVOKED/COMPENSATED/SEALED terminal) → flush barrier → seal, with
 * the compensation admission landing before `channel.close()`.
 *
 * Paths: FAILED atomic batch, SUCCESS deferred system mark, final-intent
 * zero-row revoke, fresh-retry id, reasoning marker. A sixth test pins the
 * live [flushDurable] conjunction directly (terminal settled, waiter not yet
 * awake) plus source-session ownership (a fresh session never claims the old
 * receipt). Every ledger-shape test finishes with real
 * [ChatSessionFactory.open] twice (second open adds zero).
 *
 * Existing guards (deadline-expired SEALED, pre-compensation-death seed,
 * old-gap user+system) stay in [RetryableCancelNoticeFirstTest] untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CancelReceiptCloseBarrierTest {

  companion object {
    private const val SETTLE_MS = 300L
    private const val STALL_MS = 600L
    private const val MODEL = "preset:openai@https://api.openai.com/gpt-4o-mini"
  }

  private open class R191LateStore(
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
    provider: R191Provider,
    sink: SessionTranscriptSink,
    n: AtomicInteger,
    prefix: String,
    maxRounds: Int = 60,
    retry: TurnRetryConfig = TurnRetryConfig(maxRetries = 1, retryDelaysMs = listOf(5_000L)),
    settleMs: Long = SETTLE_MS,
  ): TurnController = TurnController(
    provider = provider,
    policy = R191Policy(),
    transcript = sink,
    retryConfig = retry,
    dispatcher = Dispatchers.Default,
    sleeper = {},
    newId = { "$prefix-${n.incrementAndGet() - 1}" },
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
    buildProvider = { _, _ -> R191ReopenProvider() },
    sessionStores = object : SessionStoreSource {
      override suspend fun store() = store
    },
  )

  private fun startedHost(c: TurnController): kotlinx.coroutines.Job {
    val admission = runBlocking { c.startOrEnqueue("hi") }
    assertTrue("admission must start, was $admission", admission is TurnStart.Started)
    return (admission as TurnStart.Started).host
  }

  private fun awaitReceiptTerminal(c: TurnController, timeoutMs: Long = 8_000L): String {
    // Phase 5: read-only seam snapshot (see TurnController.cancelReceiptStates),
    // no reflection into the private receipt map. Structural-test note: the
    // map is in-memory, attempt-bounded, never removed — a kill falls back to
    // reopen INTERRUPTED backfill, so this latches attempt liveness, never
    // cross-restart durability (only flushDurable + reopen prove the ledger).
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val receipt = c.cancelReceiptStates().values.singleOrNull()
      if (receipt != null && receipt != "PENDING" && receipt != "CLAIMED") return receipt
      Thread.sleep(25)
    }
    throw AssertionError("receipt never reached a terminal state")
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

  private fun awaitUnsettled(c: TurnController, timeoutMs: Long = 5_000L) {
    // The receipt installs in the host's cancel catch (async, after the
    // bounded cancelSettleTimeoutMs wait): poll instead of asserting
    // synchronously right after cancel().
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!c.hasUnsettledCancelReceipts() && System.currentTimeMillis() < deadline) {
      Thread.sleep(25)
    }
    assertTrue("receipt must be unsettled after cancel", c.hasUnsettledCancelReceipts())
  }

  private fun armWaiterGate(c: TurnController): CountDownLatch {
    val entered = CountDownLatch(1)
    c.onCancelReceiptWaiterEnteredForTest = { entered.countDown() }
    return entered
  }

  private fun awaitWaiterEntered(entered: CountDownLatch, what: String, timeoutMs: Long = 5_000L) {
    // Waiter launch+registration gate via the Phase 5 test seam (see
    // TurnController.onCancelReceiptWaiterEnteredForTest): replaces the old
    // fixed 20ms sleep heuristic. Once this fires, a later close snapshot
    // necessarily contains the waiter job, so the handoff-before-barrier
    // order no longer races waiter startup. Flake risk: low — a cancel still
    // racing close concurrently is fenced by close's re-snapshot join, and
    // any residual stays fail-closed (hasUnsettledCancelReceipts blocks
    // flushDurable; reopen backfill repairs the family).
    assertTrue("$what: waiter never launched+registered", entered.await(timeoutMs, TimeUnit.SECONDS))
  }

  private fun assertNotTrueAndDangling(
    store: FakeSessionStore,
    durable: Boolean,
    what: String,
  ) {
    val dangling = findDanglingRunIds(store.events)
    assertFalse(
      "$what: flushDurable must never be true-and-dangling (durable=$durable, dangling=$dangling, kinds=${store.events.map { it.kind }})",
      durable && dangling.isNotEmpty(),
    )
  }

  private fun openTwiceConsistent(store: FakeSessionStore, sid: String) {
    val f = factory(store)
    val ep = endpoint()
    val first = runBlocking { f.open(ep, sid) }
    try {
      val afterFirst = store.events.filter { it.sessionId == sid }.sortedBy { it.seq }
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

  @Test fun failedBatch_immediateClose_noTrueAndDangling_reopenTwiceConsistent() {
    val store = R191LateStore(failPredicate = { it == 1 })
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = R191Provider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        emit(StreamEvent.Failed("boom", retryable = true))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n, "r191-fail")
    try {
      startedHost(c)
      assertTrue("terminal never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      // Immediate close: no host join (New Chat / switch interleave).
      awaitUnsettled(c)
      c.close()
      val durable = runBlocking { withTimeout(8_000) { c.flushDurable() } }
      assertNotTrueAndDangling(store, durable, "failed-batch immediate close")
      // The deferred atomic batch must still land (admitted before the seal):
      // partial preserved, family closed by the batch system mark.
      awaitLedger(store) { evts ->
        evts.any { it.kind == "system" } && evts.count { it.kind == "assistant" } == 1 &&
          evts.any { it.kind == "retry" && it.text.startsWith("cancel requested") }
      }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      assertEquals(listOf("user", "retry", "assistant", "system"), store.events.map { it.kind })
      assertEquals("half", store.events.single { it.kind == "assistant" }.text)
      assertTrue(findDanglingRunIds(store.events).isEmpty())
      assertFalse("failed batch must not be unsettled after handoff", c.hasUnsettledCancelReceipts())
      assertEquals("COMPENSATED", awaitReceiptTerminal(c))
      // F-B family closure with poison retained: the compensation closed the
      // family (no dangle, receipt settled) but the late store failure
      // poisoned durability — flushDurable stays false via the fail-last
      // failure read. Tier-symmetric with the doomed-timeout late-commit
      // true case (FlushDurableObligationBarrierTest): there the timeout mark
      // lifts on the proven commit (durable true); here a genuine store
      // failure never lifts (durable false).
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      assertFalse(
        "failed-batch immediate close must not flushDurable true (poison retained)",
        runBlocking { withTimeout(8_000) { c.flushDurable() } },
      )
      assertTrue(
        "failed-batch store failure must poison durability",
        c.hasDurableFailures(),
      )
    } finally {
      runBlocking { runCatching { withTimeout(8_000) { c.flushTranscript() } } }
    }
    // Late store failure poisons durability but the family is closed; reopen
    // adds at most the durable-failure accounting, never a second partial.
    val f = factory(store)
    val ep = endpoint()
    val first = runBlocking { f.open(ep, sid) }
    try {
      val afterFirst = store.events.filter { it.sessionId == sid }.sortedBy { it.seq }
      assertEquals(1, afterFirst.count { it.kind == "assistant" })
      assertTrue(afterFirst.any { it.kind == "system" })
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

  @Test fun successSystem_immediateClose_noTrueAndDangling_reopenTwiceConsistent() {
    val store = R191LateStore(failPredicate = { it == 1 }, failWith = null)
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = R191Provider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        emit(StreamEvent.Failed("boom", retryable = true))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n, "r191-ok")
    try {
      startedHost(c)
      assertTrue("terminal never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      awaitUnsettled(c)
      c.close()
      val durable = runBlocking { withTimeout(8_000) { c.flushDurable() } }
      assertNotTrueAndDangling(store, durable, "success-system immediate close")
      // Terminal committed late-success: deferred system mark lands in order.
      awaitLedger(store) { evts ->
        evts.any { it.kind == "system" } && evts.count { it.kind == "assistant" } == 1
      }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      assertEquals(listOf("user", "assistant", "retry", "system"), store.events.map { it.kind })
      assertTrue(findDanglingRunIds(store.events).isEmpty())
      assertEquals("half", store.events.single { it.kind == "assistant" }.text)
      assertFalse(c.hasUnsettledCancelReceipts())
    } finally {
      runBlocking { runCatching { withTimeout(8_000) { c.flushTranscript() } } }
    }
    openTwiceConsistent(store, sid)
  }

  @Test fun finalIntent_immediateClose_zeroNewRows_reopenClean() {
    val store = R191LateStore(failPredicate = { it == 1 }, failWith = null)
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = R191Provider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(
      provider, SessionTranscriptSink(store, sid), n, "r191-final",
      retry = TurnRetryConfig(),
    )
    try {
      startedHost(c)
      assertTrue("terminal never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      awaitUnsettled(c)
      c.close()
      val durable = runBlocking { withTimeout(8_000) { c.flushDurable() } }
      assertNotTrueAndDangling(store, durable, "final-intent immediate close")
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      // Final intent keeps zero new rows: exactly user + terminal assistant.
      awaitLedger(store) { evts -> evts.count { it.kind == "assistant" } == 1 && evts.size == 2 }
      assertEquals(listOf("user", "assistant"), store.events.map { it.kind })
      assertTrue(findDanglingRunIds(store.events).isEmpty())
      assertFalse(c.hasUnsettledCancelReceipts())
    } finally {
      runBlocking { runCatching { withTimeout(8_000) { c.flushTranscript() } } }
    }
    openTwiceConsistent(store, sid)
  }

  @Test fun freshRetry_immediateClose_batchPreserved_reopenTwiceConsistent() {
    val store = R191LateStore(failPredicate = { it == 2 })
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val attemptNo = AtomicInteger(0)
    val provider = R191Provider {
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
      provider, SessionTranscriptSink(store, sid), n, "r191-fresh",
      retry = TurnRetryConfig(maxRetries = 2, retryDelaysMs = listOf(0L, 0L)),
    )
    try {
      startedHost(c)
      assertTrue("fresh terminal never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      awaitUnsettled(c)
      c.close()
      val durable = runBlocking { withTimeout(8_000) { c.flushDurable() } }
      assertNotTrueAndDangling(store, durable, "fresh-retry immediate close")
      awaitLedger(store) { evts ->
        evts.any { it.kind == "system" } && evts.count { it.kind == "assistant" } == 2
      }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      assertEquals(
        listOf("user", "assistant", "retry", "retry", "assistant", "system"),
        store.events.map { it.kind },
      )
      val logical = store.events[0].runId
      val batchPartial = store.events[4]
      assertTrue("fresh retry must mint a fresh id", batchPartial.runId != logical)
      assertEquals("half2", batchPartial.text)
      assertTrue(findDanglingRunIds(store.events).isEmpty())
      assertFalse(c.hasUnsettledCancelReceipts())
      assertEquals("COMPENSATED", awaitReceiptTerminal(c))
    } finally {
      runBlocking { runCatching { withTimeout(8_000) { c.flushTranscript() } } }
    }
    openTwiceConsistent(store, sid)
  }

  @Test fun reasoning_immediateClose_markerPreserved_reopenTwiceConsistent() {
    val store = R191LateStore(failPredicate = { it == 1 })
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = R191Provider {
      flow {
        emit(StreamEvent.ReasoningDelta(0, 0, "think"))
        emit(StreamEvent.TextDelta(0, 0, "half"))
        emit(StreamEvent.Failed("boom", retryable = true))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n, "r191-rsn")
    try {
      startedHost(c)
      assertTrue("terminal never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      awaitUnsettled(c)
      c.close()
      val durable = runBlocking { withTimeout(8_000) { c.flushDurable() } }
      assertNotTrueAndDangling(store, durable, "reasoning immediate close")
      awaitLedger(store) { evts ->
        evts.any { it.kind == "system" } && evts.any { it.kind == "reasoning" } &&
          evts.count { it.kind == "assistant" } == 1
      }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      assertEquals(
        listOf("user", "retry", "assistant", "reasoning", "system"),
        store.events.map { it.kind },
      )
      assertEquals("thinkhalf", store.events.single { it.kind == "assistant" }.text)
      assertTrue(findDanglingRunIds(store.events).isEmpty())
      for (e in store.events) {
        val back = JsonlCodec.decode(1, JsonlCodec.encode(e.copy(seq = e.seq.coerceAtLeast(1))))
        assertEquals(e.kind, back.kind)
        assertEquals(e.isFinal, back.isFinal)
      }
      assertFalse(c.hasUnsettledCancelReceipts())
    } finally {
      runBlocking { runCatching { withTimeout(8_000) { c.flushTranscript() } } }
    }
    openTwiceConsistent(store, sid)
  }

  @Test fun unsettledReceipt_flushDurableConjunction_blocksTrueAndDangling_sourceOwnershipKept() {
    // Terminal settle → barrier → waiter-not-awake interleave on a LIVE
    // session (no close): the terminal outcome is still stalled, so the
    // waiter has not yet claimed, while user + notice are already durable.
    // flushDurable must report false (never true-and-dangling) until the
    // handoff lands; a fresh session never claims the old receipt.
    val store = R191LateStore(failPredicate = { it == 1 }, failWith = null, stallMs = 2_000L)
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = R191Provider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        emit(StreamEvent.Failed("boom", retryable = true))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n, "r191-conj")
    try {
      val host = startedHost(c)
      assertTrue("terminal never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      awaitUnsettled(c)
      // Source-session ownership: a fresh controller on its own store has no
      // unsettled receipt even while the source still owes its handoff.
      val freshStore = FakeSessionStore()
      val freshSid = runBlocking { freshStore.createSession("hi", MODEL) }
      val fresh = controller(
        R191Provider(), SessionTranscriptSink(freshStore, freshSid),
        AtomicInteger(0), "r191-new",
      )
      try {
        assertFalse(
          "new session must not claim the old receipt",
          fresh.hasUnsettledCancelReceipts(),
        )
      } finally {
        fresh.close()
      }
      // Live flush while the waiter still sleeps: drain succeeds for
      // user + notice, but the conjunction must hold false.
      val drained = runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      assertTrue("user + notice must drain while terminal stalls, was $drained", drained)
      val durableWhilePending = runBlocking { withTimeout(8_000) { c.flushDurable() } }
      assertNotTrueAndDangling(store, durableWhilePending, "live conjunction while PENDING")
      assertFalse(
        "flushDurable must be false while the receipt is still PENDING",
        durableWhilePending,
      )
      // Let the terminal settle; the waiter hands off and the deferred system
      // lands in order.
      runBlocking { withTimeout(8_000) { host.join() } }
      awaitLedger(store) { evts -> evts.any { it.kind == "system" } }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      assertEquals(listOf("user", "assistant", "retry", "system"), store.events.map { it.kind })
      assertTrue(findDanglingRunIds(store.events).isEmpty())
      val durableAfter = runBlocking { withTimeout(8_000) { c.flushDurable() } }
      assertTrue("flushDurable must recover after the handoff, was $durableAfter", durableAfter)
      assertNotTrueAndDangling(store, durableAfter, "live conjunction after handoff")
      assertFalse(c.hasUnsettledCancelReceipts())
    } finally {
      c.close()
      runBlocking { runCatching { withTimeout(8_000) { c.flushTranscript() } } }
    }
    openTwiceConsistent(store, sid)
  }

  @Test fun sessionImpl_exposesUnsettledReceiptSeam() {
    // ChatSessionImpl must forward the read-only seam (VM downgrade evidence).
    val store = FakeSessionStore()
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = object : LlmProvider {
      override val protocol: ProviderProto = ProviderProto.CHAT_COMPLETIONS
      override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        emit(StreamEvent.Failed("boom", retryable = true))
      }
      override suspend fun listModels(): List<String> = emptyList()
    }
    val n = AtomicInteger(0)
    val session: ChatSession = ChatSessionImpl(
      provider = provider,
      policy = R191Policy(),
      transcript = SessionTranscriptSink(store, sid),
      retryConfig = TurnRetryConfig(maxRetries = 1, retryDelaysMs = listOf(5_000L)),
      dispatcher = Dispatchers.Default,
      newId = { "r191-seam-${n.incrementAndGet() - 1}" },
    )
    try {
      assertFalse("fresh session must start settled", session.hasUnsettledCancelReceipts())
    } finally {
      session.close()
    }
    // Default fake seam stays false so existing VM fakes are unaffected.
    val fake: ChatSession = object : ChatSession {
      override val uiState: kotlinx.coroutines.flow.StateFlow<ChatUiState>
        get() = throw UnsupportedOperationException()
      override suspend fun send(text: String, images: List<ChatImageRef>) = Unit
      override suspend fun startTurn(text: String, images: List<ChatImageRef>) =
        throw UnsupportedOperationException()
      override suspend fun startOrEnqueue(
        text: String,
        images: List<ChatImageRef>,
        opId: Long?,
      ): TurnStart = throw UnsupportedOperationException()
      override fun drainQueued(): List<QueuedIntent> = emptyList()
      override fun cancel() = Unit
      override suspend fun flush(timeoutMs: Long): Boolean = true
      override fun steer(text: String) = Unit
      override fun close() = Unit
    }
    assertFalse("default seam must stay false for legacy fakes", fake.hasUnsettledCancelReceipts())
    assertFalse("default sealed seam must stay false for legacy fakes", fake.hasSealedUncompensatedReceipts())
  }

  @Test fun deadlineExpiredLateSuccessSeal_noTrueAndDangling_reopenInterrupted() {
    // Phase 1 SEALED truth contract: deadline-expired SEALED with a late
    // SUCCESS intermediate (non-final notice-first) leaves
    // user + partial + notice dangling (no system mark). SEALED without
    // proven family-final must block flushDurable (never true-and-dangling)
    // and reopen must add exactly one INTERRUPTED (second open adds zero).
    // Short receipt horizon (3 rounds ≈ 50+100+200ms) vs 800ms stall: the
    // waiter times out to SEALED before the late SUCCESS commits.
    val store = R191LateStore(failPredicate = { it == 1 }, failWith = null, stallMs = 800L)
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = R191Provider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        emit(StreamEvent.Failed("boom", retryable = true))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(
      provider, SessionTranscriptSink(store, sid), n, "r191-seal",
      maxRounds = 3,
      retry = TurnRetryConfig(maxRetries = 1, retryDelaysMs = listOf(5_000L)),
    )
    try {
      val host = startedHost(c)
      assertTrue("terminal never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      awaitUnsettled(c)
      runBlocking { withTimeout(8_000) { host.join() } }
      // Deadline first: SEALED before the late SUCCESS lands.
      assertEquals("SEALED", awaitReceiptTerminal(c))
      awaitLedger(store) { evts ->
        evts.any { it.kind == "retry" && it.text.startsWith("cancel requested") }
      }
      // Double latch, no wall-clock sleep (Phase 5): the stalled terminal
      // commits on its own stall clock — latch the late assistant row in the
      // ledger first, then re-latch the SEALED terminal (still SEALED: a
      // post-seal SUCCESS never revokes, the family stays dangling for
      // backfill). Timing note: maxRounds=3 bounds the waiter to ~350ms of
      // polling vs the 800ms stall, so SEALED-before-SUCCESS holds by a
      // ~450ms margin on any scheduler that runs the waiter at all.
      awaitLedger(store) { evts -> evts.any { it.kind == "assistant" } }
      assertEquals("SEALED", awaitReceiptTerminal(c))
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      val kinds = store.events.map { it.kind }
      assertEquals(listOf("user", "assistant", "retry"), kinds)
      val attemptId = store.events[0].runId
      assertEquals(listOf(attemptId), findDanglingRunIds(store.events))
      assertTrue(
        "SEALED without proven final must stay visible",
        c.hasSealedUncompensatedReceipts(),
      )
      val durable = runBlocking { withTimeout(8_000) { c.flushDurable() } }
      assertNotTrueAndDangling(store, durable, "deadline-expired late-success SEALED")
      assertFalse("SEALED without proven final must block flushDurable", durable)
    } finally {
      c.close()
      runBlocking { runCatching { withTimeout(8_000) { c.flushTranscript() } } }
    }
    // Real reopen/backfill: dangling family gains exactly one INTERRUPTED,
    // second open adds zero.
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

  // ---- Phase 2: close-budget linearization (fence T2/T3) ----

  /**
   * Gate-controlled terminal store (LateFailureCompensationTest
   * sealedWriterDegradesWithoutParking手法): the first assistant write parks
   * until [gate] completes, so the test fixes whether the terminal SUCCESS
   * lands before the waiter's next poll (T2 forward) or after the barrier
   * seal (T2 reverse). Non-assistant rows (user, notice, compensation marks)
   * never park.
   */
  private class R192GateStore(
    val entered: CountDownLatch = CountDownLatch(1),
    val gate: CompletableDeferred<Unit> = CompletableDeferred(),
    val failWith: IOException? = null,
  ) : FakeSessionStore() {
    val assistantCalls = AtomicInteger(0)
    override suspend fun appendEvent(event: TranscriptEvent): Long {
      if (event.kind == "assistant") {
        assistantCalls.incrementAndGet()
        entered.countDown()
        gate.await()
        failWith?.let { throw it }
      }
      return super.appendEvent(event)
    }
  }

  private fun endpointCross(): EndpointConfig = EndpointConfig(
    providerId = "preset:anthropic",
    presetId = "anthropic",
    label = "Anthropic",
    baseUrl = "https://api.anthropic.com/v1",
    protocol = ProviderProto.CHAT_COMPLETIONS,
    model = "claude-3-5-sonnet",
    apiKeyRef = "provider_key/preset:anthropic",
  )

  private fun forkFactory(store: FakeSessionStore): ChatSessionFactory = ChatSessionFactory(
    policy = allowKeyReadPolicy(),
    vaultSource = VaultSource {
      val vault = EncryptedPrefsVault(InMemoryPrefs())
      vault.putKey("preset:openai", "test-key-12345678".toCharArray())
      vault.putKey("preset:anthropic", "test-key-12345678".toCharArray())
      vault
    },
    buildProvider = { _, _ -> R191ReopenProvider() },
    sessionStores = object : SessionStoreSource {
      override suspend fun store() = store
    },
  )

  private fun awaitSealed(c: TurnController, timeoutMs: Long = 5_000L) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!c.isTranscriptSealed() && System.currentTimeMillis() < deadline) {
      Thread.sleep(25)
    }
    assertTrue("barrier must seal while the terminal is still gated", c.isTranscriptSealed())
  }

  private fun assertReopenAddsSingleInterrupted(store: FakeSessionStore, sid: String) {
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

  private fun assertCrossOriginForkClean(store: FakeSessionStore, sid: String) {
    // ChatSessionFactory cross-origin fork semantics: a cross-origin open
    // never reuses the old id; the old ledger stays a read-only archive
    // (no backfill, no copy-out) and the fork holds only the withheld
    // marker (never a copy of the old rows).
    val oldCount = store.events.count { it.sessionId == sid }
    val opened = runBlocking { forkFactory(store).open(endpointCross(), sid) }
    try {
      assertTrue("cross-origin open must withhold history", opened.historyWithheld)
      val newId = checkNotNull(opened.sessionId)
      assertTrue("cross-origin open must fork a new id", newId != sid)
      assertEquals(
        "old ledger must stay a read-only archive",
        oldCount,
        store.events.count { it.sessionId == sid },
      )
      val forked = store.events.filter { it.sessionId == newId }.sortedBy { it.seq }
      assertTrue(
        "fork must persist the withheld marker, was $forked",
        forked.any {
          it.kind == "system" && it.text.startsWith("model switched ") &&
            modelSwitchMarkerWithheld(it.text)
        },
      )
      assertTrue("new ledger must not dangle, was $forked", findDanglingRunIds(forked).isEmpty())
    } finally {
      opened.session.close()
    }
  }

  @Test fun closeBarrierGatedSuccess_beforeWaiterNextPoll_reopenAndForkConsistent() {
    // Phase 2 T2 forward: the terminal SUCCESS lands after close() but
    // before the waiter's next poll wake, so the owed system-mark admission
    // must linearize before the flush barrier/seal (never SEALED).
    // maxRounds=60 never touches the receipt deadline here — the race under
    // test is purely the close fence (drain 450ms vs the 50ms poll sleep).
    val store = R192GateStore()
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = R191Provider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        emit(StreamEvent.Failed("boom", retryable = true))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n, "r192-gate")
    val entered = armWaiterGate(c)
    try {
      val host = startedHost(c)
      assertTrue("terminal never entered", store.entered.await(5, TimeUnit.SECONDS))
      c.cancel()
      // The waiter installed its receipt and launched+registered (seam gate
      // below replaces the old fixed 20ms sleep heuristic); the terminal is
      // still stalled on the gate.
      awaitUnsettled(c)
      awaitWaiterEntered(entered, "r192-gate")
      // Shutdown settle: the doomed join parks on the stalled host; the
      // waiter snapshot + flush barrier are queued behind it.
      c.close(drainTimeoutMs = 450L)
      // Land the terminal SUCCESS before the waiter next wakes: the handoff
      // (deferred system mark) is admitted pre-barrier. Fix the read order
      // deterministically — host join, then the ledger fence, then the
      // receipt-terminal fence — and only then read flushDurable, so the
      // true below proves a settled handoff instead of racing it in flight.
      store.gate.complete(Unit)
      runBlocking { withTimeout(8_000) { host.join() } }
      awaitLedger(store) { evts ->
        evts.any { it.kind == "system" } && evts.count { it.kind == "assistant" } == 1
      }
      val terminal = awaitReceiptTerminal(c)
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      val durable = runBlocking { withTimeout(8_000) { c.flushDurable() } }
      assertNotTrueAndDangling(store, durable, "barrier-gated success")
      val kinds = store.events.map { it.kind }
      assertTrue("gated success must land the deferred system mark, was $kinds", kinds.contains("system"))
      assertEquals("half", store.events.single { it.kind == "assistant" }.text)
      assertTrue(findDanglingRunIds(store.events).isEmpty())
      assertFalse(c.hasUnsettledCancelReceipts())
      assertFalse(
        "gated success must not leave a sealed-uncompensated receipt",
        c.hasSealedUncompensatedReceipts(),
      )
      assertTrue(
        "gated success must hand off, never SEAL, was $terminal",
        terminal == "REVOKED" || terminal == "COMPENSATED",
      )
      assertTrue("clean handoff must drain durable, was $durable", durable)
    } finally {
      runBlocking { runCatching { withTimeout(8_000) { c.flushTranscript() } } }
    }
    openTwiceConsistent(store, sid)
    assertCrossOriginForkClean(store, sid)
  }

  @Test fun closeBarrierGatedLate_afterSealSealed_reopenInterrupted() {
    // Phase 2 T2 reverse control: the SAME gate released only after the
    // barrier seal must degrade to SEALED (proves the forward test's gate
    // actually fixes the order rather than passing unconditionally).
    val store = R192GateStore()
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = R191Provider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        emit(StreamEvent.Failed("boom", retryable = true))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n, "r192-late")
    val entered = armWaiterGate(c)
    try {
      startedHost(c)
      assertTrue("terminal never entered", store.entered.await(5, TimeUnit.SECONDS))
      c.cancel()
      awaitUnsettled(c)
      awaitWaiterEntered(entered, "r192-late")
      c.close(drainTimeoutMs = 450L)
      // Do NOT release the gate: let the 450ms settle budget exhaust, the
      // barrier flush, and the seal land first.
      awaitSealed(c)
      assertFalse("seal must land while the terminal is still gated", store.gate.isCompleted)
      // Late SUCCESS after the seal: too late for the barrier.
      store.gate.complete(Unit)
      // Late SUCCESS really committed (not a bare timeout): the gated
      // assistant row lands post-seal, so the dangle claim has an object.
      awaitLedger(store) { evts -> evts.any { it.kind == "assistant" } }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      assertEquals(listOf("user", "assistant", "retry"), store.events.map { it.kind })
      assertEquals("half", store.events.single { it.kind == "assistant" }.text)
      assertEquals(1, store.assistantCalls.get())
      assertEquals("SEALED", awaitReceiptTerminal(c))
      val attemptId = store.events[0].runId
      val dangling = findDanglingRunIds(store.events)
      assertEquals(listOf(attemptId), dangling)
      assertTrue(
        "SEALED without proven final must stay visible",
        c.hasSealedUncompensatedReceipts(),
      )
      val durable = runBlocking { withTimeout(8_000) { c.flushDurable() } }
      assertNotTrueAndDangling(store, durable, "late-gated SEALED")
      assertFalse("SEALED without proven final must block flushDurable", durable)
    } finally {
      c.close()
      runBlocking { runCatching { withTimeout(8_000) { c.flushTranscript() } } }
    }
    assertReopenAddsSingleInterrupted(store, sid)
  }

  @Test fun equalBudget_failedTerminal_closeDrainEqualsSettle_noTrueAndDangling() {
    // Phase 2 T3 (FAILED): production budgets are equal
    // (CANCEL_SETTLE_TIMEOUT_MS 5000 == OrderedTranscriptSink
    // .DEFAULT_DRAIN_TIMEOUT_MS 5000); scaled 10x down here (500 == 500).
    // The terminal parks on the gate (R192GateStore手法): close(drain ==
    // settle) seals first — awaitSealed proves the seal landed while the
    // terminal is still gated — then gate.complete releases it, destined
    // late → SEALED. Timeouts below are fences only, no wall-clock stall
    // arithmetic. Must still never report true-and-dangling:
    // SEALED + dangling + flushDurable false + reopen INTERRUPTED.
    val store = R192GateStore(failWith = IOException("late writer failure"))
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = R191Provider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        emit(StreamEvent.Failed("boom", retryable = true))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(
      provider, SessionTranscriptSink(store, sid), n, "r192-eqfail",
      settleMs = 500L,
    )
    val entered = armWaiterGate(c)
    try {
      startedHost(c)
      assertTrue("terminal never entered", store.entered.await(5, TimeUnit.SECONDS))
      c.cancel()
      awaitUnsettled(c)
      awaitWaiterEntered(entered, "r192-eqfail")
      c.close(drainTimeoutMs = 500L)
      // Seal lands while the terminal is still parked on the gate.
      awaitSealed(c)
      assertEquals(1, store.assistantCalls.get())
      assertFalse("seal must land while the terminal is still gated", store.gate.isCompleted)
      // Release the parked terminal: too late for the barrier by construction.
      store.gate.complete(Unit)
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      awaitLedger(store) { evts ->
        evts.any { it.kind == "retry" && it.text.startsWith("cancel requested") }
      }
      assertEquals(listOf("user", "retry"), store.events.map { it.kind })
      assertEquals(1, store.assistantCalls.get())
      assertEquals("SEALED", awaitReceiptTerminal(c))
      val attemptId = store.events[0].runId
      val dangling = findDanglingRunIds(store.events)
      assertEquals(listOf(attemptId), dangling)
      val durable = runBlocking { withTimeout(8_000) { c.flushDurable() } }
      assertNotTrueAndDangling(store, durable, "equal-budget failed SEALED")
      assertFalse("exhausted settle must block flushDurable", durable)
    } finally {
      c.close()
      runBlocking { runCatching { withTimeout(8_000) { c.flushTranscript() } } }
    }
    assertReopenAddsSingleInterrupted(store, sid)
  }

  @Test fun equalBudget_successTerminal_closeDrainEqualsSettle_noTrueAndDangling() {
    // Phase 2 T3 (SUCCESS): same equal-budget shape as the FAILED case
    // above (production 5000 == 5000, here 500 == 500): the terminal parks
    // on the gate, close(drain == settle) seals first (awaitSealed proves it
    // landed while the terminal is still gated), then gate.complete releases
    // it — destined late → SEALED. Differs from FAILED only in the
    // failWith/provider shape (here the parked terminal would commit SUCCESS
    // if it ever landed). Timeouts are fences only, no wall-clock stall
    // arithmetic. SEALED + dangling + flushDurable false + reopen
    // INTERRUPTED, never true-and-dangling.
    val store = R192GateStore(failWith = null)
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = R191Provider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        emit(StreamEvent.Failed("boom", retryable = true))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(
      provider, SessionTranscriptSink(store, sid), n, "r192-eqok",
      settleMs = 500L,
    )
    val entered = armWaiterGate(c)
    try {
      startedHost(c)
      assertTrue("terminal never entered", store.entered.await(5, TimeUnit.SECONDS))
      c.cancel()
      awaitUnsettled(c)
      awaitWaiterEntered(entered, "r192-eqok")
      c.close(drainTimeoutMs = 500L)
      awaitSealed(c)
      assertEquals(1, store.assistantCalls.get())
      assertFalse("seal must land while the terminal is still gated", store.gate.isCompleted)
      store.gate.complete(Unit)
      // Late SUCCESS really committed post-seal (same proof as the T2 reverse):
      // the gated assistant row lands, so the dangle claim has an object.
      awaitLedger(store) { evts -> evts.any { it.kind == "assistant" } }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      assertEquals(listOf("user", "assistant", "retry"), store.events.map { it.kind })
      assertEquals("half", store.events.single { it.kind == "assistant" }.text)
      assertEquals(1, store.assistantCalls.get())
      assertEquals("SEALED", awaitReceiptTerminal(c))
      val attemptId = store.events[0].runId
      val dangling = findDanglingRunIds(store.events)
      assertEquals(listOf(attemptId), dangling)
      val durable = runBlocking { withTimeout(8_000) { c.flushDurable() } }
      assertNotTrueAndDangling(store, durable, "equal-budget success SEALED")
      assertFalse("exhausted settle must block flushDurable", durable)
    } finally {
      c.close()
      runBlocking { runCatching { withTimeout(8_000) { c.flushTranscript() } } }
    }
    assertReopenAddsSingleInterrupted(store, sid)
  }

  @Test fun equalBudget_earlyTerminal_handsOff_noSeal_durableTrue() {
    // Phase 5 positive control for the T3 pair above (same 500 == 500
    // shape): the gate releases BEFORE close, so the terminal SUCCESS and
    // its deferred system mark linearize before the barrier — the
    // remaining-budget fence must hand off (never SEAL) and flushDurable
    // must report true. Proves the T3 SEALED outcomes come from the
    // late-terminal order, not from the equal budgets themselves.
    val store = R192GateStore(failWith = null)
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = R191Provider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        emit(StreamEvent.Failed("boom", retryable = true))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(
      provider, SessionTranscriptSink(store, sid), n, "r192-early",
      settleMs = 500L,
    )
    val entered = armWaiterGate(c)
    try {
      val host = startedHost(c)
      assertTrue("terminal never entered", store.entered.await(5, TimeUnit.SECONDS))
      c.cancel()
      awaitUnsettled(c)
      awaitWaiterEntered(entered, "early-terminal hands-off")
      // Hands off before the seal: release the parked terminal while the
      // waiter still polls, then latch the deferred system mark + handoff.
      store.gate.complete(Unit)
      runBlocking { withTimeout(8_000) { host.join() } }
      awaitLedger(store) { evts ->
        evts.any { it.kind == "system" } && evts.count { it.kind == "assistant" } == 1
      }
      val terminal = awaitReceiptTerminal(c)
      // Equal-budget close after the handoff: nothing owed, barrier drains.
      c.close(drainTimeoutMs = 500L)
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      val durable = runBlocking { withTimeout(8_000) { c.flushDurable() } }
      assertNotTrueAndDangling(store, durable, "equal-budget early-terminal hands-off")
      assertEquals(listOf("user", "assistant", "retry", "system"), store.events.map { it.kind })
      assertEquals("half", store.events.single { it.kind == "assistant" }.text)
      assertTrue(findDanglingRunIds(store.events).isEmpty())
      assertFalse(c.hasUnsettledCancelReceipts())
      assertFalse(
        "early terminal must not leave a sealed-uncompensated receipt",
        c.hasSealedUncompensatedReceipts(),
      )
      assertTrue(
        "early terminal must hand off, never SEAL, was $terminal",
        terminal == "REVOKED" || terminal == "COMPENSATED",
      )
      assertTrue("handed-off ledger must drain durable, was $durable", durable)
    } finally {
      runBlocking { runCatching { withTimeout(8_000) { c.flushTranscript() } } }
    }
    openTwiceConsistent(store, sid)
    assertCrossOriginForkClean(store, sid)
  }

  @Test fun sealedSettledFinalIntent_closeSealBeforeHandoff_flushTrue_reopenTwiceConsistent() {
    // F-C SEALED-settled allowed branch: final intent (Done) whose terminal
    // commits AFTER the close seal still counts as settled when the
    // per-entry outcome proves final-commit (suppressedFinal + outcome true,
    // system SKIPPED) — SEALED alone is NOT unsettled there. R192GateStore
    // parks the terminal pre-commit so cancel installs PENDING while
    // SUBMITTING; afterCommitBeforeAckForTest latches the terminal commit
    // (true post-commit window: commit landed, ack still in flight) so the
    // test proves the commit object before asserting; close(small drain)
    // exhausts the settle budget and seals first; releasing the gate lets
    // the owed terminal commit post-seal via the independent writer. The
    // waiter stays SEALED (seal raced) but hasSealedUncompensated is false,
    // flushDurable is true, and reopen adds zero twice.
    val store = R192GateStore(failWith = null)
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = R191Provider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n, "r192-sealed-settled")
    val entered = armWaiterGate(c)
    // Terminal commit latch (commit#2: user is #1, terminal is #2 for the
    // first attempt). No park — just proves the post-commit window so the
    // SEALED-settled claim has a commit object, never a bare timeout.
    val commits = AtomicInteger(0)
    val terminalCommitted = CountDownLatch(1)
    c.afterCommitBeforeAckForTest = {
      if (commits.incrementAndGet() == 2) terminalCommitted.countDown()
    }
    try {
      startedHost(c)
      assertTrue("terminal never entered", store.entered.await(5, TimeUnit.SECONDS))
      c.cancel()
      awaitUnsettled(c)
      awaitWaiterEntered(entered, "sealed-settled")
      c.close(drainTimeoutMs = 450L)
      awaitSealed(c)
      assertFalse("seal must land while the terminal is still gated", store.gate.isCompleted)
      store.gate.complete(Unit)
      assertTrue("terminal must commit post-seal", terminalCommitted.await(5, TimeUnit.SECONDS))
      awaitLedger(store) { evts -> evts.any { it.kind == "assistant" } }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      // Final intent keeps zero new rows: exactly user + terminal assistant.
      assertEquals(listOf("user", "assistant"), store.events.map { it.kind })
      assertEquals("ok", store.events.single { it.kind == "assistant" }.text)
      assertTrue(findDanglingRunIds(store.events).isEmpty())
      assertEquals("SEALED", awaitReceiptTerminal(c))
      assertFalse(
        "SEALED with proven final-commit must not read uncompensated",
        c.hasSealedUncompensatedReceipts(),
      )
      assertFalse(c.hasUnsettledCancelReceipts())
      val durable = runBlocking { withTimeout(8_000) { c.flushDurable() } }
      assertNotTrueAndDangling(store, durable, "sealed-settled final")
      assertTrue("settled SEALED family must drain durable, was $durable", durable)
    } finally {
      store.gate.complete(Unit)
      runCatching { c.afterCommitBeforeAckForTest = null }
      runBlocking { runCatching { withTimeout(8_000) { c.flushTranscript() } } }
    }
    openTwiceConsistent(store, sid)
    assertCrossOriginForkClean(store, sid)
  }
}
