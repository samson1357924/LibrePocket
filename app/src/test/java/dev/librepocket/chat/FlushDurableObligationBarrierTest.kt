package dev.librepocket.chat

import dev.librepocket.agent.ui.chat.ChatSessionFactory
import dev.librepocket.agent.ui.chat.SessionStoreSource
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.agent.ui.chat.allowKeyReadPolicy
import dev.librepocket.agent.ui.chat.findDanglingRunIds
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
import dev.librepocket.session.SessionTranscriptSink
import dev.librepocket.session.TranscriptEvent
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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

private class P1GateProvider(
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

private class P1AllowPolicy : PolicyStore {
  override fun evaluate(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
  override suspend fun setRule(rule: PolicyRule) = Unit
  override suspend fun removeRule(pattern: String) = Unit
  override suspend fun listRules(): List<PolicyRule> = emptyList()
  override suspend fun evaluateFresh(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

/**
 * Admitted-then-failed store probe for the R22-3 reverify companion test:
 * the targeted user row is admitted to the session channel and then fails
 * in the writer, so the failure poisons durability ([TurnController
 * .hasDurableFailures]) instead of rolling back as never-admitted. Every
 * other row persists normally.
 */
private class P1FailingStore(val failText: String) : FakeSessionStore() {
  override suspend fun appendEvent(event: TranscriptEvent): Long {
    if (event.kind == "user" && event.text == failText) throw IOException("p1 reverify failure probe")
    return super.appendEvent(event)
  }
}

/**
 * R22 Phase 1 `flushDurable` never-true-and-dangling regressions.
 *
 * Covers every terminal-obligation window the pre-fix five-conjunct
 * [TurnController.flushDurable] missed: promotion await, promotion UNKNOWN,
 * direct UNKNOWN ghost across close, a streaming host with only its user row
 * durable, a doomed host whose settle budget exhausts, and the R22-3
 * read-to-read TOCTOU window.
 *
 * Determinism: every park uses an explicit gate ([CompletableDeferred] /
 * [CountDownLatch] entry fences plus the existing
 * `beforeAdmissionFinalizeForTest` / `beforePromotionAwaitForTest` /
 * `afterCommitBeforeAckForTest` / new `beforeFlushDurableReverifyForTest`
 * seams). Convergence waits are bounded `withTimeout + delay` polls — no
 * wall-clock `Thread.sleep` guessing. Every test asserts
 * [assertNotTrueAndDangling] (durable && dangling never co-exist) and ends
 * with [openTwiceConsistent] (reopen repair idempotence).
 *
 * Current/Target honesty: these tests pin the Current double-read +
 * sticky-flag linearization. A single-lock snapshot with a monotonic failure
 * epoch is Target (see [TurnController.flushDurable]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FlushDurableObligationBarrierTest {

  companion object {
    private const val MODEL = "preset:openai@https://api.openai.com/gpt-4o-mini"
  }

  private fun lastUserContains(req: ChatRequest, needle: String): Boolean =
    req.messages.lastOrNull { it.role == "user" }?.text?.contains(needle) == true

  private fun controller(
    provider: P1GateProvider,
    sink: SessionTranscriptSink,
    n: AtomicInteger,
    prefix: String,
    acceptTimeoutMs: Long? = null,
  ): TurnController = TurnController(
    provider = provider,
    policy = P1AllowPolicy(),
    transcript = sink,
    retryConfig = TurnRetryConfig(),
    dispatcher = Dispatchers.Default,
    sleeper = {},
    newId = { "$prefix-${n.incrementAndGet() - 1}" },
    transcriptDispatcher = Dispatchers.Default,
    acceptTimeoutMs = acceptTimeoutMs ?: TurnController.ACCEPT_DURABLE_TIMEOUT_MS,
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

  private suspend fun awaitCond(timeoutMs: Long, what: String, cond: () -> Boolean) {
    withTimeout(timeoutMs) {
      while (!cond()) delay(15)
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
      assertTrue(
        "reopen must leave no dangling family, was ${findDanglingRunIds(snapshot(store, sid))}",
        findDanglingRunIds(snapshot(store, sid)).isEmpty(),
      )
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

  @Test fun promotionAwaitBlocksFlushDurable() {
    val store = FakeSessionStore()
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val firstGate = CompletableDeferred<Unit>()
    val firstEntered = CountDownLatch(1)
    val promoParked = CountDownLatch(1)
    val releasePromo = CountDownLatch(1)
    val provider = P1GateProvider(handler = { req ->
      flow {
        if (lastUserContains(req, "p1-promofirst")) {
          firstEntered.countDown()
          firstGate.await()
        }
        emit(StreamEvent.Done("stop"))
      }
    })
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n, "p1-promoawait")
    c.beforePromotionAwaitForTest = {
      promoParked.countDown()
      check(releasePromo.await(30, TimeUnit.SECONDS))
    }
    try {
      runBlocking {
        val started = withTimeout(8_000) { c.startOrEnqueue("p1-promofirst", opId = 1101L) }
        assertTrue(started is TurnStart.Started)
        assertTrue("first provider call must park", firstEntered.await(5, TimeUnit.SECONDS))
        assertEquals(TurnStart.Queued, withTimeout(8_000) { c.startOrEnqueue("p1-promofollow", opId = 1102L) })
        // Pre-promotion running-host window (streaming-host shape): the first
        // user row is durable with no terminal yet — non-empty dangling naming
        // the first runId, blocked by the host guard (promotion not reserved).
        assertTrue("running first host must trip the host guard", c.hasActiveHost())
        assertFalse("promotion not yet reserved while first runs", c.hasUnsettledPromotion())
        val runningDurable = withTimeout(8_000) { c.flushDurable() }
        assertFalse("running first host must not flushDurable true", runningDurable)
        val runningDangling = findDanglingRunIds(snapshot(store, sid))
        assertEquals("exactly the first family must dangle, was $runningDangling", 1, runningDangling.size)
        // The ledger user row carries the first logical id (durable before
        // Started returned): capture it once and reuse it below — no
        // mint-ordinal hardcoding (appendUser mints the UI row first, so the
        // first logical id is NOT prefix-0; see TurnController.appendUser).
        val firstRunId = snapshot(store, sid).single { it.kind == "user" }.runId
        assertEquals("dangling id must be the first user row", firstRunId, runningDangling.single())
        assertNotTrueAndDangling(store, sid, runningDurable, "promotion pre-gate running host")
        firstGate.complete(Unit)
        assertTrue("promotion must park on its durable await", promoParked.await(5, TimeUnit.SECONDS))
        // Lock-1 await window: reservation held, no UNKNOWN yet.
        assertTrue("promotion await must trip the promotion guard", c.hasUnsettledPromotion())
        assertTrue("promotion await must trip the admission guard", c.hasUnsettledAdmission())
        assertFalse("no UNKNOWN yet in the await window", c.hasPendingAdmission())
        val durable = withTimeout(8_000) { c.flushDurable() }
        assertFalse("promotion await family without terminal must not flushDurable true", durable)
        // Promotion-await window ledger shape: the first terminal ack lands
        // happens-before the Lock-1 park (same host coroutine), so the first
        // family is already closed and the follow row is still unsent — the
        // ledger is clean and ONLY the promotion guard blocks. Pin the shape
        // explicitly so a non-empty dangling can never hide behind this false.
        val promoSnap = snapshot(store, sid)
        assertTrue(
          "first user row must be durable, kinds=${promoSnap.map { it.kind }}",
          promoSnap.any { it.kind == "user" && it.text == "p1-promofirst" },
        )
        assertTrue(
          "first terminal must be durable before the promotion park",
          promoSnap.any { it.kind == "assistant" && it.runId == firstRunId },
        )
        assertTrue(
          "follow row must still be unsent while promotion parks, kinds=${promoSnap.map { it.kind }}",
          promoSnap.none { it.kind == "user" && it.text == "p1-promofollow" },
        )
        assertTrue(
          "promotion-await ledger must be closed (guard is the sole blocker), dangling=${findDanglingRunIds(promoSnap)}",
          findDanglingRunIds(promoSnap).isEmpty(),
        )
        assertNotTrueAndDangling(store, sid, durable, "promotion await")
        // Release: Lock-2 commits normally — single follow row, single launch.
        releasePromo.countDown()
        withTimeout(8_000) { (started as TurnStart.Started).host.join() }
        awaitCond(8_000, "follow host must settle") {
          provider.streamCalls.get() == 2 && !c.hasUnsettledPromotion() && !c.hasActiveHost()
        }
        withTimeout(8_000) { c.flushTranscript() }
        assertFalse(c.hasUnsettledPromotion())
        assertFalse(c.hasUnsettledAdmission())
        val settled = withTimeout(8_000) { c.flushDurable() }
        assertTrue("settled promotion family must flushDurable true", settled)
        assertNotTrueAndDangling(store, sid, settled, "promotion await settled")
        assertEquals(1, snapshot(store, sid).count { it.kind == "user" && it.text == "p1-promofollow" })
      }
      assertEquals("follow must reach the provider exactly once", 2, provider.streamCalls.get())
      openTwiceConsistent(store, sid)
    } finally {
      releasePromo.countDown()
      firstGate.complete(Unit)
      runCatching { c.close() }
    }
  }

  @Test fun promotionUnknownBlocksFlushDurable() {
    val store = FakeSessionStore()
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val firstGate = CompletableDeferred<Unit>()
    val firstEntered = CountDownLatch(1)
    val promoParked = CountDownLatch(1)
    val ghostCommitEntered = CountDownLatch(1)
    val releaseGhost = CountDownLatch(1)
    val followGate = CompletableDeferred<Unit>()
    val provider = P1GateProvider(handler = { req ->
      flow {
        if (lastUserContains(req, "p1-unkfirst")) {
          firstEntered.countDown()
          firstGate.await()
        }
        if (lastUserContains(req, "p1-unkfollow")) {
          // Parks the claimed follow host pre-terminal so the ledger shape
          // stays stable across the release-then-claim gap below (dangling
          // holds until the test releases this gate after the ghost ack).
          followGate.await()
        }
        emit(StreamEvent.Done("stop"))
      }
    })
    val n = AtomicInteger(0)
    // Small accept budget (same 300ms-class shape as the existing R6-1
    // promotion-UNKNOWN grid): the follow send is admitted, then the writer
    // parks post-commit, so the accept timeout fires after-send (fuzzy) and
    // retains promotion UNKNOWN ownership instead of NOT_ADMITTED. The
    // post-commit park (close-orphan手法) doubles as the window stabilizer:
    // the ack never lands, so the background settle can never prove the ghost
    // durable and claim it away mid-window — UNKNOWN persists until released.
    val c = controller(provider, SessionTranscriptSink(store, sid), n, "p1-promounk", acceptTimeoutMs = 300L)
    c.beforePromotionAwaitForTest = {
      // The next commit is necessarily the follow ghost user row: the
      // predecessor fully acked before Lock-1 in the same host coroutine, and
      // nothing else writes until the follow host launches (post-window).
      c.afterCommitBeforeAckForTest = {
        ghostCommitEntered.countDown()
        check(releaseGhost.await(30, TimeUnit.SECONDS))
      }
      promoParked.countDown()
    }
    try {
      runBlocking {
        val started = withTimeout(8_000) { c.startOrEnqueue("p1-unkfirst", opId = 1201L) }
        assertTrue(started is TurnStart.Started)
        assertTrue("first provider call must park", firstEntered.await(5, TimeUnit.SECONDS))
        assertEquals(TurnStart.Queued, withTimeout(8_000) { c.startOrEnqueue("p1-unkfollow", opId = 1202L) })
        // Pre-promotion running-host window (streaming-host shape): the first
        // user row is durable with no terminal yet — non-empty dangling naming
        // the first runId, blocked by the host guard (promotion not reserved).
        assertTrue("running first host must trip the host guard", c.hasActiveHost())
        assertFalse("promotion not yet reserved while first runs", c.hasUnsettledPromotion())
        val runningDurable = withTimeout(8_000) { c.flushDurable() }
        assertFalse("running first host must not flushDurable true", runningDurable)
        val runningDangling = findDanglingRunIds(snapshot(store, sid))
        assertEquals("exactly the first family must dangle, was $runningDangling", 1, runningDangling.size)
        // Same no-hardcoding capture as the promotion-await pin above: the
        // ledger user row carries the first logical id.
        val firstRunId = snapshot(store, sid).single { it.kind == "user" }.runId
        assertEquals("dangling id must be the first user row", firstRunId, runningDangling.single())
        assertNotTrueAndDangling(store, sid, runningDurable, "promotion pre-gate running host")
        firstGate.complete(Unit)
        assertTrue("promotion must reach its durable await", promoParked.await(5, TimeUnit.SECONDS))
        // The ghost row is committed but unacked (writer parked): the ledger
        // dangles now and the ownership below is real UNKNOWN, not a transient
        // the background settle can claim away (its barrier cannot complete).
        assertTrue("ghost must park post-commit", ghostCommitEntered.await(5, TimeUnit.SECONDS))
        awaitCond(8_000, "promotion UNKNOWN ownership") { c.hasPendingAdmission() }
        assertTrue("promotion UNKNOWN must trip the promotion guard", c.hasUnsettledPromotion())
        assertTrue("promotion UNKNOWN must trip the admission guard", c.hasUnsettledAdmission())
        // Pin the non-empty dangling naming the ghost (predecessor already
        // closed). Ghost id derived from the ledger, not hardcoded (Lock-1
        // mints it after the UI row + first logical ids).
        awaitCond(8_000, "follow ghost user row committed") {
          snapshot(store, sid).any { it.kind == "user" && it.text == "p1-unkfollow" }
        }
        val ghostRunId = snapshot(store, sid).single { it.kind == "user" && it.text == "p1-unkfollow" }.runId
        assertTrue(
          "predecessor terminal must be durable underneath the ghost",
          snapshot(store, sid).any { it.kind == "assistant" && it.runId == firstRunId },
        )
        // Short budget (close-orphan shape): the drain cannot finish behind
        // the parked writer; the guard asserts above already name the blocker.
        val durable = withTimeout(8_000) { c.flushDurable(400L) }
        assertFalse("promotion UNKNOWN family must not flushDurable true", durable)
        val unkDangling = findDanglingRunIds(snapshot(store, sid))
        assertEquals("exactly the follow ghost family must dangle, was $unkDangling", 1, unkDangling.size)
        assertEquals("dangling id must be the follow ghost row", ghostRunId, unkDangling.single())
        assertNotTrueAndDangling(store, sid, durable, "promotion UNKNOWN")
        // Release the parked ghost ack (unset the hook first so the follow
        // terminal below never parks), then settle claims under the same id
        // (Lock-2 commit: no resend, single follow row, single launch).
        runCatching { c.afterCommitBeforeAckForTest = null }
        releaseGhost.countDown()
        followGate.complete(Unit)
        withTimeout(8_000) {
          while (c.hasPendingAdmission()) {
            c.settlePendingAdmission(1_000L)
            delay(25)
          }
        }
        withTimeout(8_000) { (started as TurnStart.Started).host.join() }
        awaitCond(8_000, "follow host must settle") {
          provider.streamCalls.get() == 2 && !c.hasUnsettledPromotion() && !c.hasActiveHost()
        }
        withTimeout(8_000) { c.flushTranscript() }
        val settled = withTimeout(8_000) { c.flushDurable() }
        assertTrue("claimed promotion family must flushDurable true", settled)
        assertNotTrueAndDangling(store, sid, settled, "promotion UNKNOWN settled")
        assertEquals(1, snapshot(store, sid).count { it.kind == "user" && it.text == "p1-unkfollow" })
      }
      assertEquals("follow must reach the provider exactly once", 2, provider.streamCalls.get())
      openTwiceConsistent(store, sid)
    } finally {
      releaseGhost.countDown()
      followGate.complete(Unit)
      firstGate.complete(Unit)
      runCatching { c.afterCommitBeforeAckForTest = null }
      runCatching { c.close() }
    }
  }

  @Test fun closeUnknownOrphanBlocksFlushDurable() {
    val store = FakeSessionStore()
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = P1GateProvider()
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n, "p1-closeunk", acceptTimeoutMs = 300L)
    // Park the writer post-commit (true post-commit window per the D15-2
    // seam): the user row is committed to the store but its ack never lands,
    // so the accept timeout fires fuzzy (send proof true) and retains direct
    // UNKNOWN ownership over a ledger row that already exists.
    val commits = AtomicInteger(0)
    val commitEntered = CountDownLatch(1)
    val releaseCommit = CountDownLatch(1)
    c.afterCommitBeforeAckForTest = {
      if (commits.incrementAndGet() == 1) {
        commitEntered.countDown()
        releaseCommit.await(30, TimeUnit.SECONDS)
      }
    }
    try {
      runBlocking {
        supervisorScope {
          val deferred = async(Dispatchers.Default) { c.startOrEnqueue("p1-unkghost", opId = 1301L) }
          assertTrue("writer must park post-commit", commitEntered.await(5, TimeUnit.SECONDS))
          awaitCond(8_000, "direct UNKNOWN ownership") { c.hasPendingAdmission() }
          // The ghost row is already committed: the ledger dangles now.
          awaitCond(8_000, "ghost user row committed") {
            snapshot(store, sid).any { it.kind == "user" && it.text == "p1-unkghost" }
          }
          val ghostDangling = findDanglingRunIds(snapshot(store, sid))
          assertTrue("ghost ledger must dangle, was ${snapshot(store, sid).map { it.kind }}", ghostDangling.isNotEmpty())
          try {
            withTimeout(5_000) { deferred.await() }
            fail("timeout-after-send must throw TranscriptPendingException, not return Started")
          } catch (_: TranscriptPendingException) {
            // Expected UNKNOWN path.
          }
          // Close drops the UNKNOWN ownership (drain-before-close contract):
          // the orphan user row keeps no cancel-terminal.
          c.close(drainTimeoutMs = 200L)
          assertTrue("close-dropped ghost must trip the sticky close guard", c.hasClosedUnsettledAdmission())
          val durable = withTimeout(8_000) { c.flushDurable(400L) }
          assertFalse("close-dropped ghost must not flushDurable true", durable)
          assertNotTrueAndDangling(store, sid, durable, "close UNKNOWN orphan")
          assertTrue(
            "orphan ledger must still dangle, was ${snapshot(store, sid).map { it.kind }}",
            findDanglingRunIds(snapshot(store, sid)).isNotEmpty(),
          )
          // Sealed afterwards, still false: the permanent sticky tier never
          // lifts on this session — only reopen backfill repairs the family.
          awaitCond(8_000, "transcript seal") { c.isTranscriptSealed() }
          val sealed = withTimeout(8_000) { c.flushDurable(400L) }
          assertFalse("sealed orphan must still not flushDurable true", sealed)
          assertNotTrueAndDangling(store, sid, sealed, "close UNKNOWN orphan sealed")
          releaseCommit.countDown()
          withTimeout(8_000) { runCatching { c.flushTranscript() } }
        }
      }
      assertEquals("ghost must never launch a provider call", 0, provider.streamCalls.get())
      // Reopen repairs with exactly one INTERRUPTED row, then stays stable.
      val f = factory(store)
      val ep = endpoint()
      val opened = runBlocking { f.open(ep, sid) }
      try {
        val afterOpen = snapshot(store, sid)
        assertTrue(
          "reopen must repair the ghost orphan, dangling=${findDanglingRunIds(afterOpen)}",
          findDanglingRunIds(afterOpen).isEmpty(),
        )
        assertTrue(
          "reopen repair must leave an INTERRUPTED row, kinds=${afterOpen.map { it.kind }}",
          afterOpen.any { it.kind == "system" && it.text.contains("interrupted") },
        )
      } finally {
        opened.session.close()
      }
      openTwiceConsistent(store, sid)
    } finally {
      releaseCommit.countDown()
      runCatching { c.close() }
    }
  }

  @Test fun streamingHostBlocksFlushDurable() {
    val store = FakeSessionStore()
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val hostEntered = CountDownLatch(1)
    val hostGate = CompletableDeferred<Unit>()
    val provider = P1GateProvider(handler = {
      flow {
        hostEntered.countDown()
        hostGate.await()
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    })
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n, "p1-stream")
    try {
      runBlocking {
        val started = withTimeout(8_000) { c.startOrEnqueue("p1-streaming", opId = 1401L) }
        assertTrue(started is TurnStart.Started)
        assertTrue("host must park on the provider gate", hostEntered.await(5, TimeUnit.SECONDS))
        // Host entry cleared the admission pending, but the live host still
        // owns a user row with no terminal.
        awaitCond(8_000, "host entry clears the admission guard") { !c.hasUnsettledAdmission() }
        assertFalse("running host must not trip the admission guard", c.hasUnsettledAdmission())
        assertTrue("live host must trip the host guard", c.hasActiveHost())
        val durable = withTimeout(8_000) { c.flushDurable() }
        assertFalse("streaming host with only its user durable must not flushDurable true", durable)
        val dangling = findDanglingRunIds(snapshot(store, sid))
        assertEquals("exactly the streaming family must dangle, was $dangling", 1, dangling.size)
        assertEquals(
          "dangling id must be the streaming user row",
          snapshot(store, sid).single { it.kind == "user" }.runId,
          dangling.single(),
        )
        assertNotTrueAndDangling(store, sid, durable, "streaming host")
        hostGate.complete(Unit)
        withTimeout(8_000) { (started as TurnStart.Started).host.join() }
        awaitCond(8_000, "host must clear") { !c.hasActiveHost() }
        withTimeout(8_000) { c.flushTranscript() }
        val settled = withTimeout(8_000) { c.flushDurable() }
        assertTrue("completed turn must flushDurable true", settled)
        assertNotTrueAndDangling(store, sid, settled, "streaming host settled")
      }
      assertEquals(1, provider.streamCalls.get())
      openTwiceConsistent(store, sid)
    } finally {
      hostGate.complete(Unit)
      runCatching { c.close() }
    }
  }

  @Test fun closeLiveHostCleanSettleLiftsSticky() {
    val store = FakeSessionStore()
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val hostEntered = CountDownLatch(1)
    val hostGate = CompletableDeferred<Unit>()
    val provider = P1GateProvider(handler = {
      flow {
        hostEntered.countDown()
        hostGate.await()
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    })
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n, "p1-lift")
    try {
      runBlocking {
        val started = withTimeout(8_000) { c.startOrEnqueue("p1-lifting", opId = 1701L) }
        assertTrue(started is TurnStart.Started)
        assertTrue("host must park on the provider gate", hostEntered.await(5, TimeUnit.SECONDS))
        awaitCond(8_000, "user row durable") {
          snapshot(store, sid).any { it.kind == "user" && it.text == "p1-lifting" }
        }
        // Close while the host is live with a generous drain budget: the
        // liftable sticky tier trips (controller-observed family parked on the
        // provider gate), never the permanent ghost tier (no UNKNOWN or
        // promotion reservation dropped).
        c.close(drainTimeoutMs = 5_000L)
        assertTrue("live-host close must trip the sticky close guard", c.hasClosedUnsettledAdmission())
        // Release: the cancelled host still lands its cancel terminal through
        // the independent session writer then drain + seal complete.
        hostGate.complete(Unit)
        withTimeout(8_000) { (started as TurnStart.Started).host.join() }
        awaitCond(8_000, "host must clear") { !c.hasActiveHost() }
        awaitCond(8_000, "cancel family must close") {
          findDanglingRunIds(snapshot(store, sid)).isEmpty()
        }
        // Clean proof lifts the sticky tier: shutdown done with no
        // pending/UNKNOWN/promotion/active/orphan evidence, no durable
        // failures, and no unsettled or SEALED-uncompensated receipts.
        awaitCond(8_000, "sticky tier must lift on the clean proof") { !c.hasClosedUnsettledAdmission() }
        withTimeout(8_000) { c.flushTranscript() }
        assertFalse(c.hasClosedUnsettledAdmission())
        val durable = withTimeout(8_000) { c.flushDurable() }
        assertTrue("clean-settled close must flushDurable true", durable)
        assertNotTrueAndDangling(store, sid, durable, "close live-host clean settle")
        assertEquals(1, snapshot(store, sid).count { it.kind == "user" && it.text == "p1-lifting" })
      }
      assertEquals("live host must reach the provider exactly once", 1, provider.streamCalls.get())
      openTwiceConsistent(store, sid)
    } finally {
      hostGate.complete(Unit)
      runCatching { c.close() }
    }
  }

  @Test fun doomedHostTimeoutBlocksFlushDurable() {
    val store = FakeSessionStore()
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val providerEntered = CountDownLatch(1)
    val providerGate = CompletableDeferred<Unit>()
    val provider = P1GateProvider(handler = {
      flow {
        providerEntered.countDown()
        providerGate.await()
        emit(StreamEvent.Done("stop"))
      }
    })
    // Stall the writer for terminal writes only: admission stays fast (user
    // durable, host launches), but the doomed host's cancel terminal can
    // never land within the close budget — the settle timeout must bite.
    val inner = SessionTranscriptSink(store, sid)
    val termEntered = CountDownLatch(1)
    val termGate = CompletableDeferred<Unit>()
    val stallingSink = object : TranscriptSink by inner {
      // NOTE: override the exact 5-arg overload the cancel handler calls
      // (see TurnController hostedTurn). A `by`-delegated object generates a
      // straight forwarder for every non-overridden signature, so overriding
      // only the 3-arg base would be bypassed via the interface default —
      // the stall must sit on this overload itself.
      override suspend fun onTurnCancelled(
        runId: String,
        partialText: String,
        parentRunId: String?,
        attemptIndex: Int?,
        hasReasoning: Boolean,
      ) {
        termEntered.countDown()
        termGate.await()
        inner.onTurnCancelled(runId, partialText, parentRunId, attemptIndex, hasReasoning)
      }
      override suspend fun onTurnCancelled(
        runId: String,
        partialText: String,
        hasReasoning: Boolean,
      ) {
        termEntered.countDown()
        termGate.await()
        inner.onTurnCancelled(runId, partialText, hasReasoning)
      }
      override suspend fun onLogicalTurnCancelled(
        logicalTurnId: String,
        attemptRunId: String,
        partialText: String,
        attemptIndex: Int?,
      ) {
        termEntered.countDown()
        termGate.await()
        inner.onLogicalTurnCancelled(logicalTurnId, attemptRunId, partialText, attemptIndex)
      }
    }
    val n = AtomicInteger(0)
    val c = TurnController(
      provider = provider,
      policy = P1AllowPolicy(),
      transcript = stallingSink,
      retryConfig = TurnRetryConfig(),
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "p1-doomed-${n.incrementAndGet() - 1}" },
      transcriptDispatcher = Dispatchers.Default,
    )
    try {
      runBlocking {
        val started = withTimeout(8_000) { c.startOrEnqueue("p1-doomed", opId = 1501L) }
        assertTrue(started is TurnStart.Started)
        assertTrue("host must park on the provider gate", providerEntered.await(5, TimeUnit.SECONDS))
        awaitCond(8_000, "user row durable") {
          snapshot(store, sid).any { it.kind == "user" && it.text == "p1-doomed" }
        }
        // Close with the provider gate held and a tiny drain budget: the
        // doomed settle exhausts while the cancel terminal is still gated.
        c.close(drainTimeoutMs = 200L)
        assertTrue("cancel terminal must reach the stalled writer", termEntered.await(5, TimeUnit.SECONDS))
        assertTrue("doomed close must trip the sticky close guard", c.hasClosedUnsettledAdmission())
        // Doomed-window ledger shape (streaming-host style): only the doomed
        // user row is durable, the cancel terminal is still gated — non-empty
        // dangling naming exactly that row.
        val doomedDangling = findDanglingRunIds(snapshot(store, sid))
        assertEquals("exactly the doomed family must dangle, was $doomedDangling", 1, doomedDangling.size)
        assertEquals(
          "dangling id must be the doomed user row",
          snapshot(store, sid).single { it.kind == "user" }.runId,
          doomedDangling.single(),
        )
        val durable = withTimeout(8_000) { c.flushDurable(400L) }
        assertFalse("doomed settle timeout without terminal must not flushDurable true", durable)
        assertNotTrueAndDangling(store, sid, durable, "doomed host timeout")
        awaitCond(8_000, "transcript seal") { c.isTranscriptSealed() }
        // Release both gates: the late terminal commits (no duplicate user
        // row). R23-1: the drain-timeout mark clears on the proven commit —
        // the row IS durable now — so durability clears too (every row is
        // durable, the family is closed); only a genuine store failure would
        // stay poisoned. Reopen stays safe (nothing dangles).
        providerGate.complete(Unit)
        termGate.complete(Unit)
        withTimeout(8_000) { (started as TurnStart.Started).host.join() }
        withTimeout(8_000) { runCatching { c.flushTranscript() } }
        awaitCond(8_000, "host must clear") { !c.hasActiveHost() }
        assertEquals(
          "seal must never duplicate the user row, kinds=${snapshot(store, sid).map { it.kind }}",
          1,
          snapshot(store, sid).count { it.kind == "user" },
        )
        val sealed = withTimeout(8_000) { c.flushDurable(400L) }
        // R23-1: the late terminal committed, so every row is durable and the
        // doomed family is closed — flushDurable honestly reports true (still
        // never true-and-dangling, see below). The pre-release check above
        // pins the timeout path stays fail-closed while the terminal is gated.
        assertTrue("committed doomed family must flushDurable true", sealed)
        assertNotTrueAndDangling(store, sid, sealed, "doomed host sealed")
      }
      assertEquals("doomed host must reach the provider exactly once", 1, provider.streamCalls.get())
      openTwiceConsistent(store, sid)
    } finally {
      providerGate.complete(Unit)
      termGate.complete(Unit)
      runCatching { c.close() }
    }
  }

  @Test fun flushDurableReverifyGateBlocksLateAdmission() {
    val store = FakeSessionStore()
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = P1GateProvider(handler = {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    })
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n, "p1-toctou")
    // R22-3 gate: parks flushDurable between its first durable-failure read
    // (after the admission proof) and the failure re-read.
    val reverifyEntered = CountDownLatch(1)
    val reverifyGate = CompletableDeferred<Unit>()
    c.beforeFlushDurableReverifyForTest = {
      reverifyEntered.countDown()
      reverifyGate.await()
    }
    // A finalize-parked admission: user row already durable, no terminal yet.
    val finalizeEntered = CountDownLatch(1)
    val finalizeGate = CompletableDeferred<Unit>()
    c.beforeAdmissionFinalizeForTest = {
      finalizeEntered.countDown()
      finalizeGate.await()
    }
    try {
      runBlocking {
        supervisorScope {
          val durableDeferred = async(Dispatchers.Default) { withTimeout(8_000) { c.flushDurable() } }
          assertTrue("flushDurable must park on the reverify gate", reverifyEntered.await(5, TimeUnit.SECONDS))
          // Open a terminal obligation INSIDE the TOCTOU window: a single
          // pass over the guards (pre-fix order) already ran clean, so only
          // the post-gate re-verification can still fail closed.
          val late = async(Dispatchers.Default) { c.startOrEnqueue("p1-late", opId = 1601L) }
          assertTrue("late admission must park on the finalize barrier", finalizeEntered.await(5, TimeUnit.SECONDS))
          assertTrue("late admission must hold the slot", c.hasUnsettledAdmission())
          // TOCTOU-window ledger shape (streaming-host style): the late user
          // row is already durable behind the finalize barrier with no
          // terminal yet — non-empty dangling naming exactly that row.
          val lateSnap = snapshot(store, sid)
          val lateDangling = findDanglingRunIds(lateSnap)
          assertEquals("exactly the late family must dangle, was $lateDangling", 1, lateDangling.size)
          assertEquals(
            "dangling id must be the late user row",
            lateSnap.single { it.kind == "user" }.runId,
            lateDangling.single(),
          )
          reverifyGate.complete(Unit)
          val durable = withTimeout(8_000) { durableDeferred.await() }
          assertFalse("obligation opened in the reverify window must not flushDurable true", durable)
          assertNotTrueAndDangling(store, sid, durable, "TOCTOU reverify")
          // Release: the late turn launches exactly once and settles clean.
          finalizeGate.complete(Unit)
          val started = withTimeout(8_000) { late.await() }
          assertTrue("late admission must start, was $started", started is TurnStart.Started)
          withTimeout(8_000) { (started as TurnStart.Started).host.join() }
          awaitCond(8_000, "late host must settle") { !c.hasUnsettledAdmission() && !c.hasActiveHost() }
          withTimeout(8_000) { c.flushTranscript() }
          val settled = withTimeout(8_000) { c.flushDurable() }
          assertTrue("settled late turn must flushDurable true", settled)
          assertNotTrueAndDangling(store, sid, settled, "TOCTOU settled")
          assertEquals(1, snapshot(store, sid).count { it.kind == "user" && it.text == "p1-late" })
        }
      }
      assertEquals(1, provider.streamCalls.get())
      openTwiceConsistent(store, sid)
    } finally {
      reverifyGate.complete(Unit)
      finalizeGate.complete(Unit)
      runCatching { c.close() }
    }
  }

  @Test fun flushDurableReverifyGateCatchesLateDurableFailure() {
    // R22-3 companion to the late-admission TOCTOU pin above: the KDoc "or
    // land a durable failure" branch — a durable store failure admitted
    // INSIDE the reverify window must still fail closed via the second
    // failure read, even though the first pass ran clean. The late user-row
    // send is admitted, then the writer fails it (poison, never a silent
    // rollback), so the proof is the poison flag itself plus the missing row.
    val store = P1FailingStore(failText = "p1-latefail")
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = P1GateProvider(handler = {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    })
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n, "p1-toctoufail")
    // Same reverify park as the late-admission pin: parks flushDurable
    // between its first durable-failure read and the failure re-read.
    val reverifyEntered = CountDownLatch(1)
    val reverifyGate = CompletableDeferred<Unit>()
    c.beforeFlushDurableReverifyForTest = {
      reverifyEntered.countDown()
      reverifyGate.await()
    }
    try {
      runBlocking {
        supervisorScope {
          val durableDeferred = async(Dispatchers.Default) { withTimeout(8_000) { c.flushDurable() } }
          assertTrue("flushDurable must park on the reverify gate", reverifyEntered.await(5, TimeUnit.SECONDS))
          val late = async(Dispatchers.Default) { c.startOrEnqueue("p1-latefail", opId = 1801L) }
          // The writer fails the admitted row before the waiter throws, so
          // the poison flag is already the deterministic fence (no sleep).
          awaitCond(8_000, "late failure must poison durability") { c.hasDurableFailures() }
          try {
            withTimeout(8_000) { late.await() }
            fail("failed admission must throw TranscriptDurabilityException, not return Started")
          } catch (_: TranscriptDurabilityException) {
            // Expected admitted-write-failure path.
          }
          reverifyGate.complete(Unit)
          val durable = withTimeout(8_000) { durableDeferred.await() }
          assertFalse("failure landed in the reverify window must not flushDurable true", durable)
          assertTrue("poison must still read back after the window", c.hasDurableFailures())
          assertNotTrueAndDangling(store, sid, durable, "TOCTOU durable failure")
          assertTrue(
            "failed user row must never land, kinds=${snapshot(store, sid).map { it.kind }}",
            snapshot(store, sid).none { it.kind == "user" && it.text == "p1-latefail" },
          )
        }
      }
      assertEquals("failed admission must never reach the provider", 0, provider.streamCalls.get())
      openTwiceConsistent(store, sid)
    } finally {
      reverifyGate.complete(Unit)
      runCatching { c.close() }
    }
  }

  @Test fun flushDurableReturnGateCatchesLateDurableFailure() {
    // F-A return-window pin (fail-last order): a durable store failure
    // admitted AFTER the second admission snapshot but BEFORE the final
    // failure read must still fail closed via that final read, even though
    // both snapshots ran clean. Parks flushDurable on the Stage-2 return
    // gate [beforeFlushDurableReturnForTest], admits a must-fail user row
    // (P1FailingStore手法), fences on the poison flag (no sleep), then
    // releases and proves flushDurable==false && hasDurableFailures==true.
    val store = P1FailingStore(failText = "p1-returnfail")
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = P1GateProvider(handler = {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    })
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n, "p1-returnfail")
    val returnEntered = CountDownLatch(1)
    val returnGate = CompletableDeferred<Unit>()
    c.beforeFlushDurableReturnForTest = {
      returnEntered.countDown()
      returnGate.await()
    }
    try {
      runBlocking {
        supervisorScope {
          val durableDeferred = async(Dispatchers.Default) { withTimeout(8_000) { c.flushDurable() } }
          assertTrue("flushDurable must park on the return gate", returnEntered.await(5, TimeUnit.SECONDS))
          val late = async(Dispatchers.Default) { c.startOrEnqueue("p1-returnfail", opId = 1901L) }
          awaitCond(8_000, "late failure must poison durability") { c.hasDurableFailures() }
          try {
            withTimeout(8_000) { late.await() }
            fail("failed admission must throw TranscriptDurabilityException, not return Started")
          } catch (_: TranscriptDurabilityException) {
            // Expected admitted-write-failure path.
          }
          returnGate.complete(Unit)
          val durable = withTimeout(8_000) { durableDeferred.await() }
          assertFalse("failure landed in the return window must not flushDurable true", durable)
          assertTrue("poison must still read back after the window", c.hasDurableFailures())
          assertNotTrueAndDangling(store, sid, durable, "return-window durable failure")
          assertTrue(
            "failed user row must never land, kinds=${snapshot(store, sid).map { it.kind }}",
            snapshot(store, sid).none { it.kind == "user" && it.text == "p1-returnfail" },
          )
        }
      }
      assertEquals("failed admission must never reach the provider", 0, provider.streamCalls.get())
      openTwiceConsistent(store, sid)
    } finally {
      returnGate.complete(Unit)
      runCatching { c.close() }
    }
  }
}
