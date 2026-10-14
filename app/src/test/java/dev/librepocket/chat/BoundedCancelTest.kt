package dev.librepocket.chat

import dev.librepocket.agent.ui.chat.ChatSessionFactory
import dev.librepocket.agent.ui.chat.SessionStoreSource
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.agent.ui.chat.findDanglingRunIds
import dev.librepocket.agent.ui.chat.interruptedMarkerText
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol as ProviderProto
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.SessionTranscriptSink
import dev.librepocket.session.TranscriptEvent
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

private class BoundedCancelProvider(
  var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
  override val protocol: ProviderProto = ProviderProto.CHAT_COMPLETIONS
  override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
    emitAll(handler(request))
  }
  override suspend fun listModels(): List<String> = emptyList()
}

private class BoundedCancelPolicy : PolicyStore {
  override fun evaluate(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
  override suspend fun setRule(rule: PolicyRule) = Unit
  override suspend fun removeRule(pattern: String) = Unit
  override suspend fun listRules(): List<PolicyRule> = emptyList()
  override suspend fun evaluateFresh(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

private class BoundedCancelReopenProvider : LlmProvider {
  override val protocol: ProviderProto = ProviderProto.CHAT_COMPLETIONS
  override fun stream(request: ChatRequest): Flow<StreamEvent> = emptyFlow()
  override suspend fun listModels(): List<String> = emptyList()
}

/**
 * S3-F2 bounded immediate cancel: a wedged writer must not park the cancelled
 * host. The immediate cancel terminal (NonCancellable channel.send +
 * ack.await) is bounded by cancelCompensationTimeoutMs — timeout writes no
 * terminal row and converges via the PENDING receipt (deferred notice-first)
 * or markInterrupted (all other paths), so flushDurable stays false until
 * reopen INTERRUPTED backfill (never true-and-dangling, never a duplicate).
 *
 * Current (S3-F2): production 5s ([TurnController.CANCEL_RECEIPT_COMPENSATION_TIMEOUT_MS]);
 * tests inject 1s to prove the mechanism without a real 5s stall (the 6s
 * fence below is the spec's production-convergence bar). Target: single
 * shared ledger budget instead of three shares of this timeout.
 */
class BoundedCancelTest {

  companion object {
    private const val MODEL = "preset:openai@https://api.openai.com/gpt-4o-mini"
  }

  /** Store whose assistant rows (cancel terminal) wedge on a never-completing gate. */
  private class WedgedAssistantStore(
    val entered: CountDownLatch = CountDownLatch(1),
    val gate: CompletableDeferred<Unit> = CompletableDeferred(),
  ) : FakeSessionStore() {
    override suspend fun appendEvent(event: TranscriptEvent): Long {
      if (event.kind == "assistant") {
        entered.countDown()
        gate.await()
      }
      return super.appendEvent(event)
    }
    override suspend fun appendEvents(events: List<TranscriptEvent>): List<Long> {
      if (events.any { it.kind == "assistant" }) {
        entered.countDown()
        gate.await()
      }
      return super.appendEvents(events)
    }
  }

  /**
   * Current (S3-F2): store whose system cancel-mark rows wedge. Covers the
   * suppressed (backoff-gap) immediate path — already-durable intermediate
   * partial, cancel writes onLogicalTurnCancelled (system) — while the
   * assistant wedge above covers the streaming immediate path
   * (onTurnCancelled). Both converge via timeout markInterrupted with the
   * precise logical id.
   */
  private class WedgedSystemStore(
    val entered: CountDownLatch = CountDownLatch(1),
    val gate: CompletableDeferred<Unit> = CompletableDeferred(),
  ) : FakeSessionStore() {
    override suspend fun appendEvent(event: TranscriptEvent): Long {
      if (event.kind == "system" && event.text.contains("cancelled")) {
        entered.countDown()
        gate.await()
      }
      return super.appendEvent(event)
    }
    override suspend fun appendEvents(events: List<TranscriptEvent>): List<Long> {
      if (events.any { it.kind == "system" && it.text.contains("cancelled") }) {
        entered.countDown()
        gate.await()
      }
      return super.appendEvents(events)
    }
  }

  /**
   * Late-terminal store for the receipt-SEALED control (Current S3-F2).
   * Latch-controlled (no real 800ms sleep): the assistant entry parks on
   * [gate] until the test releases it after observing SEALED, so the waiter
   * deadline (maxRounds=3 ≈ 350ms polling) always wins deterministically
   * without a wall-clock stall. Production would stall 5s+; here the latch
   * holds indefinitely until SEALED.
   */
  private open class LateTerminalStore(
    val gate: CompletableDeferred<Unit> = CompletableDeferred(),
  ) : FakeSessionStore() {
    val assistantEntered = CountDownLatch(1)
    override suspend fun appendEvent(event: TranscriptEvent): Long {
      if (event.kind == "assistant") {
        assistantEntered.countDown()
        gate.await()
      }
      return super.appendEvent(event)
    }
    override suspend fun appendEvents(events: List<TranscriptEvent>): List<Long> {
      if (events.any { it.kind == "assistant" }) {
        assistantEntered.countDown()
        gate.await()
      }
      return super.appendEvents(events)
    }
  }

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
    policy = InMemoryPolicyStore(),
    vaultSource = VaultSource {
      val vault = EncryptedPrefsVault(InMemoryPrefs())
      vault.putKey("preset:openai", "test-key-12345678".toCharArray())
      vault
    },
    buildProvider = { _, _ -> BoundedCancelReopenProvider() },
    sessionStores = object : SessionStoreSource {
      override suspend fun store() = store
    },
  )

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

  @Test fun wedgedImmediateCancel_hostConvergesBounded_flushFalse_reopenSingleInterrupted() {
    val store = WedgedAssistantStore()
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val providerGate = CompletableDeferred<Unit>()
    val provider = BoundedCancelProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        providerGate.await()
      }
    }
    val n = AtomicInteger(0)
    // Injected 1s bound (production 5s): proves the mechanism without a real
    // 5s stall; the 6s fence below is the spec's production-convergence bar.
    val c = TurnController(
      provider = provider,
      policy = BoundedCancelPolicy(),
      transcript = SessionTranscriptSink(store, sid),
      retryConfig = TurnRetryConfig(),
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "bcan-${n.incrementAndGet() - 1}" },
      cancelCompensationTimeoutMs = 1_000L,
    )
    try {
      val admission = runBlocking { c.startOrEnqueue("hi") }
      assertTrue("admission must start, was $admission", admission is TurnStart.Started)
      val host = (admission as TurnStart.Started).host
      // Wait until the provider is streaming, then cancel into the wedged
      // immediate onTurnCancelled write.
      runBlocking {
        withTimeout(5_000) {
          while (c.uiState.value.messages.none { it.role == "assistant" }) {
            kotlinx.coroutines.delay(10)
          }
        }
      }
      c.cancel()
      // Bounded convergence: the wedged immediate write times out (1s bound)
      // instead of parking the host; 6s is the spec bar for production 5s.
      // Prove the wedge was actually hit (cancel entered the stuck delegate)
      // before latching convergence, so the timeout — not a fast path — owns it.
      assertTrue(
        "cancel must enter the wedged delegate",
        store.entered.await(5, TimeUnit.SECONDS),
      )
      val start = System.currentTimeMillis()
      runBlocking { withTimeout(6_000) { host.join() } }
      val elapsed = System.currentTimeMillis() - start
      assertTrue("wedged cancel must converge <6s, took ${elapsed}ms", elapsed < 6_000L)
      // Fail-closed: no terminal row, INTERRUPTED mark, flushDurable false
      // (never true-and-dangling). No PENDING receipt exists for a streaming
      // immediate (no terminal intent), so convergence is via markInterrupted.
      assertTrue("wedged immediate must mark durable failure", c.hasDurableFailures())
      // Precise id: streaming immediate marks the attempt (= logical for the
      // first attempt). The user row is the only ledger row, so its runId is
      // the expected INTERRUPTED id.
      val userRunId = store.events.single { it.kind == "user" }.runId
      assertEquals(listOf(userRunId), c.interruptedTranscriptRunIds())
      val durable = runBlocking { withTimeout(8_000) { c.flushDurable() } }
      assertFalse("wedged immediate must keep flushDurable false", durable)
      // Close must stay bounded behind the wedged writer, then reopen
      // backfills exactly one INTERRUPTED (second open adds zero, no dup).
      c.close()
      runBlocking { runCatching { withTimeout(8_000) { c.flushTranscript() } } }
      awaitLedger(store) { evts -> evts.any { it.kind == "user" } }
      assertEquals(listOf("user"), store.events.map { it.kind })
    } finally {
      // Do NOT complete the wedged gate here: releasing it would let the
      // abandoned cancel terminal commit (user + assistant, family closed)
      // before reopen, defeating the user-only dangle this test pins. The
      // close above already kills the stuck writer via writerScope.cancel();
      // the incomplete gate is then garbage (no waiter remains).
      providerGate.complete(Unit)
      c.close()
      runBlocking { runCatching { withTimeout(8_000) { c.flushTranscript() } } }
    }
    assertReopenAddsSingleInterrupted(store, sid)
  }

  /**
   * Current (S3-F2) suppressed wedge: cancel lands in the backoff gap on an
   * already-durable intermediate partial, so the immediate writes the system
   * mark (onLogicalTurnCancelled). A wedged system delegate must still
   * converge bounded via timeout markInterrupted on the precise logical id
   * (never true-and-dangling, never a duplicate assistant).
   *
   * Production 5s bound; test injects 1s like the streaming wedge above.
   */
  @Test fun wedgedSuppressedCancel_hostConvergesBounded_flushFalse_reopenSingleInterrupted() {
    val store = WedgedSystemStore()
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = BoundedCancelProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        emit(StreamEvent.Failed("boom", retryable = true))
      }
    }
    val n = AtomicInteger(0)
    // Latch sleeper: holds the host in the backoff gap until cancel lands
    // (cancellable — cancel throws CE out of the await into the suppressed
    // immediate path). No real 5s sleep.
    val backoffGate = CompletableDeferred<Unit>()
    val c = TurnController(
      provider = provider,
      policy = BoundedCancelPolicy(),
      transcript = SessionTranscriptSink(store, sid),
      retryConfig = TurnRetryConfig(maxRetries = 1, retryDelaysMs = listOf(5_000L)),
      dispatcher = Dispatchers.Default,
      sleeper = { _: Long -> backoffGate.await() },
      newId = { "bsup-${n.incrementAndGet() - 1}" },
      cancelCompensationTimeoutMs = 1_000L,
    )
    try {
      val admission = runBlocking { c.startOrEnqueue("hi") }
      assertTrue("admission must start, was $admission", admission is TurnStart.Started)
      val host = (admission as TurnStart.Started).host
      // Wait until the intermediate partial + retry notice are durable, then
      // cancel into the wedged system-mark write.
      awaitLedger(store) { evts ->
        evts.count { it.kind == "assistant" } == 1 && evts.any { it.kind == "retry" }
      }
      c.cancel()
      assertTrue(
        "suppressed cancel must enter the wedged system delegate",
        store.entered.await(5, TimeUnit.SECONDS),
      )
      val start = System.currentTimeMillis()
      runBlocking { withTimeout(6_000) { host.join() } }
      val elapsed = System.currentTimeMillis() - start
      assertTrue("wedged suppressed cancel must converge <6s, took ${elapsed}ms", elapsed < 6_000L)
      assertTrue("wedged suppressed must mark durable failure", c.hasDurableFailures())
      // Precise id: suppressed immediate marks the logical family id.
      val userRunId = store.events.single { it.kind == "user" }.runId
      assertEquals(listOf(userRunId), c.interruptedTranscriptRunIds())
      val durable = runBlocking { withTimeout(8_000) { c.flushDurable() } }
      assertFalse("wedged suppressed must keep flushDurable false", durable)
      c.close()
      runBlocking { runCatching { withTimeout(8_000) { c.flushTranscript() } } }
      awaitLedger(store) { evts -> evts.any { it.kind == "user" } }
      // No system cancel row (wedged, timed out): user + partial + notice only.
      assertEquals(listOf("user", "assistant", "retry"), store.events.map { it.kind })
    } finally {
      // Keep the system gate wedged (like the assistant wedge): releasing it
      // would commit the cancel mark and close the family before reopen.
      // Close kills the stuck writer; the incomplete gate is garbage.
      backoffGate.complete(Unit)
      c.close()
      runBlocking { runCatching { withTimeout(8_000) { c.flushTranscript() } } }
    }
    assertReopenAddsSingleInterrupted(store, sid)
  }

  @Test fun receiptSealedControl_hasSealedUncompensated_flushFalse_reopenSingleInterrupted() {
    // Control for the PENDING-receipt convergence branch (Current S3-F2):
    // terminal parks on a latch past the receipt deadline (maxRounds=3 ≈
    // 350ms polling), so the waiter degrades to SEALED before the test
    // releases the latch and the late SUCCESS commits. No real 800ms sleep —
    // hook/latch only (entered latch proves the wedge, SEALED poll proves the
    // deadline won, gate release proves the late commit). SEALED without
    // proven family-final stays visible (hasSealedUncompensated) and blocks
    // flushDurable; reopen adds exactly one INTERRUPTED.
    val store = LateTerminalStore()
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = BoundedCancelProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        emit(StreamEvent.Failed("boom", retryable = true))
      }
    }
    val n = AtomicInteger(0)
    val c = TurnController(
      provider = provider,
      policy = BoundedCancelPolicy(),
      transcript = SessionTranscriptSink(store, sid),
      retryConfig = TurnRetryConfig(maxRetries = 1, retryDelaysMs = listOf(5_000L)),
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "bseal-${n.incrementAndGet() - 1}" },
      // Short settle (300ms) like CancelReceiptCloseBarrierTest: the latched
      // terminal is still SUBMITTING after the bounded wait, so the cancel
      // installs its PENDING receipt instead of settling as COMMITTED (the
      // production 5s default would let an unlatched commit land first and
      // take the zero-row path with no receipt). The 3-round waiter (~350ms
      // polling) then degrades to SEALED before the latch releases.
      cancelSettleTimeoutMs = 300L,
      cancelCompensationTimeoutMs = 5_000L,
      cancelReceiptMaxRounds = 3,
    )
    try {
      val admission = runBlocking { c.startOrEnqueue("hi") }
      assertTrue(admission is TurnStart.Started)
      val host = (admission as TurnStart.Started).host
      assertTrue("terminal never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      runBlocking { withTimeout(8_000) { host.join() } }
      // Waiter deadline first: SEALED while the latch still holds the SUCCESS.
      val deadline = System.currentTimeMillis() + 8_000L
      while (System.currentTimeMillis() < deadline) {
        if (c.cancelReceiptStates().values.singleOrNull() == "SEALED") break
        Thread.sleep(25)
      }
      assertEquals("SEALED", c.cancelReceiptStates().values.singleOrNull())
      // Release the latch only after SEALED won: proves the late SUCCESS
      // lands after the deadline (deterministic order, no wall-clock race).
      store.gate.complete(Unit)
      awaitLedger(store) { evts -> evts.any { it.kind == "assistant" } }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      assertEquals(listOf("user", "assistant", "retry"), store.events.map { it.kind })
      assertTrue(
        "SEALED without proven final must stay visible",
        c.hasSealedUncompensatedReceipts(),
      )
      val durable = runBlocking { withTimeout(8_000) { c.flushDurable() } }
      assertFalse("SEALED without proven final must block flushDurable", durable)
    } finally {
      // Ensure the latch never parks close/teardown on failure paths.
      store.gate.complete(Unit)
      c.close()
      runBlocking { runCatching { withTimeout(8_000) { c.flushTranscript() } } }
    }
    assertReopenAddsSingleInterrupted(store, sid)
  }
}
