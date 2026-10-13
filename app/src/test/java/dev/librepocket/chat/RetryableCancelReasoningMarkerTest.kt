package dev.librepocket.chat

import dev.librepocket.agent.ui.chat.ChatSessionFactory
import dev.librepocket.agent.ui.chat.SessionStoreSource
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.agent.ui.chat.cancelledMarkerText
import dev.librepocket.agent.ui.chat.findDanglingRunIds
import dev.librepocket.agent.ui.chat.loadModelHistoryScan
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

private class R183Provider(
  var handler: (ChatRequest) -> Flow<StreamEvent> = { kotlinx.coroutines.flow.emptyFlow() },
) : LlmProvider {
  override val protocol: ProviderProto = ProviderProto.CHAT_COMPLETIONS
  override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
    emitAll(handler(request))
  }
  override suspend fun listModels(): List<String> = emptyList()
}

private class R183Policy : PolicyStore {
  override fun evaluate(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
  override suspend fun setRule(rule: PolicyRule) = Unit
  override suspend fun removeRule(pattern: String) = Unit
  override suspend fun listRules(): List<PolicyRule> = emptyList()
  override suspend fun evaluateFresh(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

private class R183ReopenProvider : LlmProvider {
  override val protocol: ProviderProto = ProviderProto.CHAT_COMPLETIONS
  override fun stream(request: ChatRequest): Flow<StreamEvent> = kotlinx.coroutines.flow.emptyFlow()
  override suspend fun listModels(): List<String> = emptyList()
}

/**
 * R18-3 regressions: a reasoning-sourced retryable partial that is still
 * SUBMITTING when cancel lands must keep its `hasReasoning` flag through the
 * notice-first compensation (batch `assistant` + payload-free `reasoning` +
 * `system`) and reopen.
 *
 * Shape under test: `ReasoningDelta` + text, then retryable FAIL whose
 * intermediate write stalls (late writer FAIL), cancel installs the receipt,
 * the waiter compensates with the atomic batch. Four surfaces must agree:
 * live UI `hasReasoning`, ledger marker, JSONL marker, reopen (family closed
 * by the batch system mark, marker intact, second open adds zero). Control:
 * the same flow without reasoning stays a single assistant row (no marker).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RetryableCancelReasoningMarkerTest {

  companion object {
    private const val SETTLE_MS = 300L
    private const val STALL_MS = 800L
    private const val MODEL = "preset:openai@https://api.openai.com/gpt-4o-mini"
  }

  private open class R183LateStore : FakeSessionStore() {
    val assistantCalls = AtomicInteger(0)
    val assistantEntered = CountDownLatch(1)
    override suspend fun appendEvent(event: TranscriptEvent): Long {
      if (event.kind == "assistant") {
        if (assistantCalls.incrementAndGet() == 1) {
          assistantEntered.countDown()
          kotlinx.coroutines.delay(STALL_MS)
          throw IOException("late writer failure")
        }
      }
      return super.appendEvent(event)
    }
  }

  private fun controller(
    provider: R183Provider,
    sink: SessionTranscriptSink,
    n: AtomicInteger,
  ): TurnController = TurnController(
    provider = provider,
    policy = R183Policy(),
    transcript = sink,
    retryConfig = TurnRetryConfig(maxRetries = 1, retryDelaysMs = listOf(5_000L)),
    dispatcher = Dispatchers.Default,
    sleeper = {},
    newId = { "r183-${n.incrementAndGet() - 1}" },
    cancelSettleTimeoutMs = SETTLE_MS,
    cancelCompensationTimeoutMs = 5_000L,
    cancelReceiptMaxRounds = 60,
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
    buildProvider = { _, _ -> R183ReopenProvider() },
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

  @Test fun reasoningPartial_cancelCompensation_keepsMarkerThroughReopen() {
    val store = R183LateStore()
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = R183Provider {
      flow {
        emit(StreamEvent.ReasoningDelta(0, 0, "think"))
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
          evts.any { it.kind == "reasoning" } &&
          evts.any { it.kind == "retry" && it.text.startsWith("cancel requested") }
      }
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
      // 1. Live UI keeps the reasoning flag on the partial fragment.
      val live = c.uiState.value.messages.single { it.role == "assistant" }
      assertEquals("thinkhalf", live.text)
      assertTrue("live partial must stay reasoning-sourced", live.hasReasoning)
      assertTrue(live.isPartial)
      // 2. Ledger: user + cancel notice + batch (partial + marker + system mark).
      assertEquals(
        listOf("user", "retry", "assistant", "reasoning", "system"),
        store.events.map { it.kind },
      )
      val logical = store.events[0].runId
      val partial = store.events[2]
      assertEquals(logical, partial.runId)
      assertEquals("thinkhalf", partial.text)
      assertTrue(partial.isPartial)
      assertEquals(false, partial.isFinal)
      assertEquals("partial lost (store failure)", partial.failureReason)
      val marker = store.events[3]
      assertEquals("reasoning", marker.kind)
      assertEquals(partial.runId, marker.runId)
      assertEquals("", marker.text)
      assertEquals(false, marker.isFinal)
      assertEquals(partial.parentRunId, marker.parentRunId)
      assertEquals(partial.attemptIndex, marker.attemptIndex)
      val mark = store.events[4]
      assertEquals(logical, mark.runId)
      assertEquals(cancelledMarkerText(logical), mark.text)
      assertTrue(mark.isFinal)
      assertTrue(findDanglingRunIds(store.events).isEmpty())
      // 3. JSONL: the marker rides the wire (kind/runId/empty-text preserved).
      for (e in store.events) {
        val back = JsonlCodec.decode(1, JsonlCodec.encode(e.copy(seq = e.seq.coerceAtLeast(1))))
        assertEquals(e.kind, back.kind)
        assertEquals(e.runId, back.runId)
        assertEquals(e.text, back.text)
        assertEquals(e.isFinal, back.isFinal)
      }
      val markerBack = JsonlCodec.decode(
        1,
        JsonlCodec.encode(marker.copy(seq = marker.seq.coerceAtLeast(1))),
      )
      assertEquals("reasoning", markerBack.kind)
      assertEquals("", markerBack.text)
      // 4. The shared history scan collects the marker for its assistant row.
      val scan = runBlocking { loadModelHistoryScan(store, sid) }
      assertTrue("scan must collect the failed-partial marker", partial.runId in scan.reasoningRunIds)
      assertEquals("COMPENSATED", awaitReceiptState(c))
    } finally {
      c.close()
      runBlocking { withTimeout(8_000) { c.flushTranscript() } }
    }
    // 5. Real reopen/backfill: family already closed by the batch system mark,
    // so zero INTERRUPTED, marker intact, and second open adds zero.
    val f = factory(store)
    val ep = endpoint()
    val first = runBlocking { f.open(ep, sid) }
    try {
      val afterFirst = store.events.filter { it.sessionId == sid }.sortedBy { it.seq }
      assertEquals(1, afterFirst.count { it.kind == "assistant" })
      assertEquals(1, afterFirst.count { it.kind == "reasoning" })
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

  @Test fun plainPartial_cancelCompensation_staysSingleRow() {
    val store = R183LateStore()
    val sid = runBlocking { store.createSession("hi", MODEL) }
    val provider = R183Provider {
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
      val live = c.uiState.value.messages.single { it.role == "assistant" }
      assertEquals("half", live.text)
      assertFalse("plain partial must not gain a reasoning flag", live.hasReasoning)
      // Control: no reasoning marker row — the batch stays partial + system.
      assertEquals(
        listOf("user", "retry", "assistant", "system"),
        store.events.map { it.kind },
      )
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
      assertTrue(afterFirst.none { it.kind == "reasoning" })
      assertTrue(findDanglingRunIds(afterFirst).isEmpty())
    } finally {
      first.session.close()
    }
  }
}
