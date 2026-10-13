package dev.librepocket.chat

import dev.librepocket.agent.ui.chat.ChatSessionFactory
import dev.librepocket.agent.ui.chat.SessionStoreSource
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.agent.ui.chat.findDanglingRunIds
import dev.librepocket.agent.ui.chat.hasIncompleteSnapshotMarker
import dev.librepocket.agent.ui.chat.snapshotSwitchHistory
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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class BarrierProvider(
  var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
  override val protocol: ProviderProto = ProviderProto.CHAT_COMPLETIONS
  val streamCalls = AtomicInteger(0)
  override fun stream(request: ChatRequest): Flow<StreamEvent> {
    streamCalls.incrementAndGet()
    return handler(request)
  }
  override suspend fun listModels(): List<String> = emptyList()
}

private class BarrierPolicy : PolicyStore {
  override fun evaluate(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
  override suspend fun setRule(rule: PolicyRule) = Unit
  override suspend fun removeRule(pattern: String) = Unit
  override suspend fun listRules(): List<PolicyRule> = emptyList()
  override suspend fun evaluateFresh(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

/**
 * R21-1 Phase 1 admission-finalize barrier regressions.
 *
 * Barrier: `beforeAdmissionFinalizeForTest` parks the waiter AFTER the
 * durable user-row ack but BEFORE the finalize lock — the family owns a user
 * row with no terminal yet. While parked:
 * - `hasUnsettledAdmission()` is true (direct pending),
 * - `flushDurable()` must be false (never true-and-dangling),
 * - a same-origin fork must not CLEAN-copy the dangling user.
 *
 * Semantics choice (explicit): for the `ChatSessionFactory.open` same-sid
 * path, CLEAN is defined as "no dangling copy because the reopen backfill
 * repaired it". The tests assert pre-fork ledger dangles (non-empty, proving
 * the guard) and post-open ledger is empty *with* an INTERRUPTED repair row
 * (proving it was repaired, not cleanly copied). For the snapshot fork path
 * (cancel test), CLEAN is "no dangling copy because exclude dropped it";
 * the fork must carry the incomplete marker (never a clean orphan copy).
 *
 * Dispatcher note: StandardTestDispatcher needs runTest and is incompatible
 * with Robolectric/runBlocking here, so the caller/host split uses the
 * LedgerTest:540-style self-made PausedDispatcher for the host dispatcher
 * plus `transcriptDispatcher=Dispatchers.Default` for the writer (admission
 * ack completes while the host never dispatches). The caller parks on a
 * CompletableDeferred gate (deterministic, no timing).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AdmissionFinalizeBarrierTest {

  companion object {
    private const val MODEL = "preset:openai@https://api.openai.com/gpt-4o-mini"
  }

  /** LedgerTest:540-style pausable dispatcher (host side only). */
  private class PausedDispatcher : CoroutineDispatcher() {
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

  private fun controller(
    provider: BarrierProvider,
    sink: SessionTranscriptSink,
    n: AtomicInteger,
    prefix: String,
    paused: PausedDispatcher,
  ): TurnController = TurnController(
    provider = provider,
    policy = BarrierPolicy(),
    transcript = sink,
    retryConfig = TurnRetryConfig(),
    dispatcher = paused,
    sleeper = {},
    newId = { "$prefix-${n.incrementAndGet() - 1}" },
    transcriptDispatcher = Dispatchers.Default,
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
    policy = InMemoryPolicyStore(),
    vaultSource = VaultSource {
      val vault = EncryptedPrefsVault(InMemoryPrefs())
      vault.putKey("preset:openai", "test-key-12345678".toCharArray())
      vault
    },
    buildProvider = { _, _ ->
      object : LlmProvider {
        override val protocol: ProviderProto = ProviderProto.CHAT_COMPLETIONS
        override fun stream(request: ChatRequest): Flow<StreamEvent> = emptyFlow()
        override suspend fun listModels(): List<String> = emptyList()
      }
    },
    sessionStores = object : SessionStoreSource {
      override suspend fun store() = store
    },
  )

  private fun snapshot(store: FakeSessionStore, sid: String): List<TranscriptEvent> =
    synchronized(store.events) {
      ArrayList(store.events.filter { it.sessionId == sid }.sortedBy { it.seq })
    }

  private fun awaitLedgerUser(
    store: FakeSessionStore,
    sid: String,
    text: String,
    timeoutMs: Long = 8_000L,
  ) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val has = synchronized(store.events) {
        store.events.any { it.sessionId == sid && it.kind == "user" && it.text == text }
      }
      if (has) return
      Thread.sleep(25)
    }
    throw AssertionError("user row '$text' never committed: ${snapshot(store, sid)}")
  }

  private fun awaitTrue(timeoutMs: Long = 8_000L, cond: () -> Boolean, what: String) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!cond()) {
      if (System.currentTimeMillis() > end) fail("timed out waiting for: $what")
      Thread.sleep(10)
    }
  }

  private fun assertNotTrueAndDangling(store: FakeSessionStore, sid: String, durable: Boolean, what: String) {
    val dangling = findDanglingRunIds(snapshot(store, sid))
    assertFalse(
      "$what: flushDurable must never be true-and-dangling (durable=$durable, dangling=$dangling)",
      durable && dangling.isNotEmpty(),
    )
  }

  private fun openTwiceConsistent(store: FakeSessionStore, sid: String) {
    val f = factory(store)
    val ep = endpoint()
    val first = runBlocking { f.open(ep, sid) }
    try {
      assertTrue(findDanglingRunIds(snapshot(store, sid)).isEmpty())
    } finally {
      first.session.close()
    }
    val sizeAfterFirst = synchronized(store.events) { store.events.count { it.sessionId == sid } }
    val second = runBlocking { f.open(ep, sid) }
    try {
      val afterSecond = snapshot(store, sid)
      assertEquals(sizeAfterFirst, afterSecond.size)
      assertTrue(findDanglingRunIds(afterSecond).isEmpty())
    } finally {
      second.session.close()
    }
  }

  @Test fun startOrEnqueue_finalizeBarrier_closeWins_flushFalse_forkRepairs() {
    val store = FakeSessionStore()
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = BarrierProvider()
    val n = AtomicInteger(0)
    val paused = PausedDispatcher()
    val c = controller(provider, SessionTranscriptSink(store, sid), n, "r21-soe", paused)
    val gate = CompletableDeferred<Unit>()
    val enteredGate = CountDownLatch(1)
    c.beforeAdmissionFinalizeForTest = {
      enteredGate.countDown()
      gate.await()
    }
    try {
      runBlocking {
        supervisorScope {
          val deferred = async(Dispatchers.Default) { c.startOrEnqueue("barrier-soe", opId = 101L) }
          awaitLedgerUser(store, sid, "barrier-soe")
          assertTrue("waiter must park on finalize barrier", enteredGate.await(5, TimeUnit.SECONDS))
          assertTrue("pending admission must trip the guard", c.hasUnsettledAdmission())
          // Close wins while parked: no host yet, waiter will take the
          // finalize-closed path (rollback + TranscriptDurabilityException).
          c.close(drainTimeoutMs = 5_000L)
          // R21-1 Phase 1 tie: the closed barrier still owns the admission
          // window, so the guard below stays true and flushDurable==false is
          // via the new hasUnsettledAdmission conjunct (mirrors the cancel
          // test's post-cancel guard assert).
          assertTrue("closed barrier must still trip the guard", c.hasUnsettledAdmission())
          val durable = withTimeout(8_000) { c.flushDurable() }
          assertFalse("barrier family without terminal must not flushDurable true", durable)
          assertNotTrueAndDangling(store, sid, durable, "startOrEnqueue barrier")
          // Pre-fork ledger must dangle (proves the guard, not just a timeout).
          assertTrue(
            "pre-fork ledger must dangle, was ${snapshot(store, sid).map { it.kind }}",
            findDanglingRunIds(snapshot(store, sid)).isNotEmpty(),
          )
          // Same-origin fork via open(same sid): must not CLEAN-copy the
          // dangling user. Post-open dangling is empty *because* the reopen
          // backfill repaired it with an INTERRUPTED row (not a clean copy).
          val f = factory(store)
          val opened = f.open(endpoint(), sid)
          try {
            val afterOpen = snapshot(store, sid)
            assertTrue(
              "reopen must repair the barrier orphan, dangling=${findDanglingRunIds(afterOpen)}",
              findDanglingRunIds(afterOpen).isEmpty(),
            )
            assertTrue(
              "reopen repair must leave an INTERRUPTED row, kinds=${afterOpen.map { it.kind }}",
              afterOpen.any { it.kind == "system" && it.text.contains("interrupted") },
            )
          } finally {
            opened.session.close()
          }
          gate.complete(Unit)
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
      openTwiceConsistent(store, sid)
    } finally {
      gate.complete(Unit)
      paused.resumeAll()
      runCatching { c.close() }
    }
  }

  @Test fun startTurn_finalizeBarrier_closeWins_flushFalse_forkRepairs() {
    val store = FakeSessionStore()
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = BarrierProvider()
    val n = AtomicInteger(0)
    val paused = PausedDispatcher()
    val c = controller(provider, SessionTranscriptSink(store, sid), n, "r21-st", paused)
    val gate = CompletableDeferred<Unit>()
    val enteredGate = CountDownLatch(1)
    c.beforeAdmissionFinalizeForTest = {
      enteredGate.countDown()
      gate.await()
    }
    try {
      runBlocking {
        supervisorScope {
          val deferred = async(Dispatchers.Default) { c.startTurn("barrier-st") }
          awaitLedgerUser(store, sid, "barrier-st")
          assertTrue("waiter must park on finalize barrier", enteredGate.await(5, TimeUnit.SECONDS))
          assertTrue("pending admission must trip the guard", c.hasUnsettledAdmission())
          c.close(drainTimeoutMs = 5_000L)
          // R21-1 Phase 1 tie: the closed barrier still owns the admission
          // window, so the guard below stays true and flushDurable==false is
          // via the new hasUnsettledAdmission conjunct (mirrors the cancel
          // test's post-cancel guard assert).
          assertTrue("closed barrier must still trip the guard", c.hasUnsettledAdmission())
          val durable = withTimeout(8_000) { c.flushDurable() }
          assertFalse("barrier family without terminal must not flushDurable true", durable)
          assertNotTrueAndDangling(store, sid, durable, "startTurn barrier")
          assertTrue(
            "pre-fork ledger must dangle, was ${snapshot(store, sid).map { it.kind }}",
            findDanglingRunIds(snapshot(store, sid)).isNotEmpty(),
          )
          val f = factory(store)
          val opened = f.open(endpoint(), sid)
          try {
            val afterOpen = snapshot(store, sid)
            assertTrue(
              "reopen must repair the barrier orphan, dangling=${findDanglingRunIds(afterOpen)}",
              findDanglingRunIds(afterOpen).isEmpty(),
            )
            assertTrue(
              "reopen repair must leave an INTERRUPTED row, kinds=${afterOpen.map { it.kind }}",
              afterOpen.any { it.kind == "system" && it.text.contains("interrupted") },
            )
          } finally {
            opened.session.close()
          }
          gate.complete(Unit)
          try {
            withTimeout(5_000) { deferred.await() }
            fail("finalize-closed must throw TranscriptDurabilityException, not return a host")
          } catch (e: TranscriptDurabilityException) {
            // Expected fail-closed path.
          }
          withTimeout(5_000) { c.flushTranscript() }
        }
      }
      assertEquals("ghost launch: provider must never be called", 0, provider.streamCalls.get())
      openTwiceConsistent(store, sid)
    } finally {
      gate.complete(Unit)
      paused.resumeAll()
      runCatching { c.close() }
    }
  }

  @Test fun startOrEnqueue_cancelDuringBarrier_cancelPathStillExcludes() {
    val store = FakeSessionStore()
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = BarrierProvider()
    val n = AtomicInteger(0)
    val paused = PausedDispatcher()
    val c = controller(provider, SessionTranscriptSink(store, sid), n, "r21-cancel", paused)
    val gate = CompletableDeferred<Unit>()
    val enteredGate = CountDownLatch(1)
    c.beforeAdmissionFinalizeForTest = {
      enteredGate.countDown()
      gate.await()
    }
    try {
      runBlocking {
        supervisorScope {
          val deferred = async(Dispatchers.Default) { c.startOrEnqueue("barrier-cancel", opId = 202L) }
          awaitLedgerUser(store, sid, "barrier-cancel")
          assertTrue("waiter must park on finalize barrier", enteredGate.await(5, TimeUnit.SECONDS))
          assertTrue(c.hasUnsettledAdmission())
          // Cancel lands in the barrier (Finding 1): projects CANCELLED, the
          // waiter abandons the launch and drains a cancel terminal instead
          // of ghost-launching. The CANCELLED path must still exclude.
          c.cancel()
          assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
          assertTrue("cancelled barrier must still trip the guard", c.hasUnsettledAdmission())
          val durable = withTimeout(8_000) { c.flushDurable() }
          assertFalse("cancelled barrier without terminal must not flushDurable true", durable)
          assertNotTrueAndDangling(store, sid, durable, "cancel barrier")
          assertTrue(
            "pre-fork ledger must dangle, was ${snapshot(store, sid).map { it.kind }}",
            findDanglingRunIds(snapshot(store, sid)).isNotEmpty(),
          )
          // Fork while cancelled-but-unterminated: snapshot with exclude must
          // not CLEAN-copy the orphan — it drops the family and carries the
          // incomplete marker.
          val forkId = store.createSession("fork", MODEL)
          val copied = snapshotSwitchHistory(
            store, sid, forkId,
            "gpt-4o-mini", "gpt-4o-mini", false,
            excludeDanglingFamilies = true, markIncomplete = true,
          )
          assertEquals("dangling family must be excluded from the fork", 0, copied)
          val forked = snapshot(store, forkId)
          assertTrue(findDanglingRunIds(forked).isEmpty())
          assertTrue(
            "excluded fork must carry the incomplete marker, kinds=${forked.map { it.kind }}",
            runBlocking { hasIncompleteSnapshotMarker(store, forkId) },
          )
          gate.complete(Unit)
          val started = withTimeout(5_000) { deferred.await() }
          assertTrue("cancel barrier must return Started, was $started", started is TurnStart.Started)
          val host = (started as TurnStart.Started).host
          assertTrue("cancel barrier host must already be cancelled", host.isCancelled)
          // Orphan drain (user already durable, skipped there) writes the
          // cancel terminal; poll for it, then the guard must converge.
          awaitTrue(8_000, { !c.hasUnsettledAdmission() }, "orphan drain must clear the guard")
          awaitTrue(
            8_000,
            { findDanglingRunIds(snapshot(store, sid)).isEmpty() },
            "cancel terminal must close the family",
          )
          val settled = withTimeout(8_000) { c.flushDurable() }
          assertTrue("settled cancel family must flushDurable true, dangling=${findDanglingRunIds(snapshot(store, sid))}", settled)
          withTimeout(5_000) { host.join() }
        }
      }
      assertEquals("cancelled barrier must never stream", 0, provider.streamCalls.get())
      // Original ledger now owns user + cancel terminal; reopen twice stays consistent.
      c.close()
      openTwiceConsistent(store, sid)
    } finally {
      gate.complete(Unit)
      paused.resumeAll()
      runCatching { c.close() }
    }
  }

  @Test fun steadyState_hostRunning_doesNotTripGuard_flushTrue() {
    val store = FakeSessionStore()
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val providerEntered = CountDownLatch(1)
    val providerGate = CompletableDeferred<Unit>()
    val provider = BarrierProvider(handler = {
      kotlinx.coroutines.flow.flow {
        providerEntered.countDown()
        providerGate.await()
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    })
    val n = AtomicInteger(0)
    // Unpaused host: normal admission must clear pending at host entry, so
    // the guard stays false while the host runs (no false positive). The
    // provider gate parks the live host; the poll below proves the prologue
    // cleared pending (guard false) while the turn is still STREAMING.
    val paused = PausedDispatcher().apply { paused = false }
    val c = controller(provider, SessionTranscriptSink(store, sid), n, "r21-steady", paused)
    try {
      runBlocking {
        supervisorScope {
          val admission = withTimeout(8_000) { c.startOrEnqueue("steady-q", opId = 303L) }
          assertTrue(admission is TurnStart.Started)
          assertTrue(
            "host must park on the provider gate",
            providerEntered.await(5, TimeUnit.SECONDS),
          )
          // Host entry (hostedTurn prologue) cleared pendingAdmission: guard
          // false even while the host is still running (STREAMING). Poll
          // because the launch-to-prologue hop is async after Started.
          awaitTrue(8_000, { !c.hasUnsettledAdmission() }, "host entry must clear the guard")
          assertFalse("running host must not trip the admission guard", c.hasUnsettledAdmission())
          providerGate.complete(Unit)
          withTimeout(8_000) { (admission as TurnStart.Started).host.join() }
          withTimeout(8_000) { c.flushTranscript() }
          assertFalse(c.hasUnsettledAdmission())
          val durable = withTimeout(8_000) { c.flushDurable() }
          // Successful turn owns user + terminal: the admission guard
          // converged (false) and the ledger drains clean.
          assertTrue("settled turn must flushDurable true", durable)
          assertNotTrueAndDangling(store, sid, durable, "steady state")
        }
      }
    } finally {
      providerGate.complete(Unit)
      paused.resumeAll()
      runCatching { c.close() }
    }
  }

  @Test fun chatSessionImpl_hasUnsettledAdmission_forwardsController() {
    val store = FakeSessionStore()
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = BarrierProvider(handler = {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    })
    val n = AtomicInteger(0)
    // Paused host dispatcher parks the admission before host entry (the
    // writer shares the dispatcher, so the durable await parks too). The
    // pending slot is still owned throughout: the impl must forward the
    // live controller state (the interface default is false, so any true
    // observation proves the delegate wiring).
    val paused = PausedDispatcher()
    val s = ChatSessionImpl(
      provider = provider,
      policy = BarrierPolicy(),
      transcript = SessionTranscriptSink(store, sid),
      dispatcher = paused,
      newId = { "r21-impl-${n.incrementAndGet() - 1}" },
    )
    try {
      runBlocking {
        supervisorScope {
          val deferred = async(Dispatchers.Default) { s.startOrEnqueue("impl-guard", opId = 404L) }
          awaitTrue(8_000, { s.hasUnsettledAdmission() }, "impl must forward unsettled admission")
          assertTrue(s.hasUnsettledAdmission())
          paused.resumeAll()
          val started = withTimeout(8_000) { deferred.await() }
          assertTrue("impl admission must start, was $started", started is TurnStart.Started)
          withTimeout(8_000) { (started as TurnStart.Started).host.join() }
          withTimeout(8_000) { s.flush() }
          awaitTrue(8_000, { !s.hasUnsettledAdmission() }, "impl guard must converge after settle")
          assertFalse(s.hasUnsettledAdmission())
          val durable = withTimeout(8_000) { s.flushDurable() }
          assertTrue("settled impl turn must flushDurable true", durable)
          assertNotTrueAndDangling(store, sid, durable, "impl delegate")
        }
      }
      assertEquals("impl turn must stream once", 1, provider.streamCalls.get())
    } finally {
      paused.resumeAll()
      runCatching { s.close() }
    }
  }
}
