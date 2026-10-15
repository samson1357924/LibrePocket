package dev.librepocket.chat

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
import dev.librepocket.session.PrunePolicy
import dev.librepocket.session.PruneResult
import dev.librepocket.session.SessionMeta
import dev.librepocket.session.SessionStore
import dev.librepocket.session.SessionTranscriptSink
import dev.librepocket.session.TranscriptEvent
import java.io.File
import java.util.Collections
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

private class StageFFakeProvider(
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

private class StageFAllowPolicy : PolicyStore {
  override fun evaluate(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
  override suspend fun setRule(rule: PolicyRule) = Unit
  override suspend fun removeRule(pattern: String) = Unit
  override suspend fun listRules(): List<PolicyRule> = emptyList()
  override suspend fun evaluateFresh(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

/**
 * Stage F R2-2: cancel in the retry backoff gap writes one system-kind final
 * mark instead of a second assistant row for the same attempt.
 *
 * Ledger shape under test (first attempt fails retryable, cancel lands in
 * the sleeper before the second id is minted):
 * `user(L) + assistant(L, partial, isFinal=0) + retry(L) + system(L, "turn L cancelled", isFinal=1)`.
 */
class TurnControllerStageFDedupTest {

  private fun controller(
    provider: StageFFakeProvider,
    sink: SessionTranscriptSink,
    sleeperEntered: CountDownLatch,
    sleeperGate: CompletableDeferred<Unit>,
  ): TurnController {
    var n = 0
    return TurnController(
      provider = provider,
      policy = StageFAllowPolicy(),
      transcript = sink,
      retryConfig = TurnRetryConfig(maxRetries = 1, retryDelaysMs = listOf(5_000L)),
      dispatcher = Dispatchers.Default,
      sleeper = {
        sleeperEntered.countDown()
        sleeperGate.await()
      },
      newId = { "stagef-${n++}" },
    )
  }

  @Test fun cancelInBackoffWritesOneAssistantPlusSystemMark() {
    val store = FakeSessionStore()
    val sid = runBlocking { store.createSession("hi", "m") }
    val sink = SessionTranscriptSink(store, sid)
    val calls = AtomicInteger(0)
    val provider = StageFFakeProvider {
      flow {
        if (calls.incrementAndGet() == 1) {
          emit(StreamEvent.TextDelta(0, 0, "half"))
          emit(StreamEvent.Failed("boom", retryable = true))
        } else {
          emit(StreamEvent.TextDelta(0, 0, "NEW"))
          emit(StreamEvent.Done("stop"))
        }
      }
    }
    val entered = CountDownLatch(1)
    val gate = CompletableDeferred<Unit>()
    val c = controller(provider, sink, entered, gate)
    try {
      val admission = runBlocking { c.startOrEnqueue("hi") }
      assertTrue(admission is TurnStart.Started)
      assertTrue("retry sleeper was not entered", entered.await(5, TimeUnit.SECONDS))
      // The intermediate partial must be durable before the cancel lands.
      runBlocking {
        withTimeout(5_000) {
          while (store.events.none { it.kind == "assistant" && it.text == "half" }) delay(10)
        }
      }
      c.cancel()
      gate.complete(Unit)
      runBlocking { withTimeout(5_000) { (admission as TurnStart.Started).host.join() } }
      runBlocking { withTimeout(5_000) { c.flushTranscript() } }

      // DB: one user + one (non-final) partial assistant + one retry + one system cancel mark.
      assertEquals(
        listOf("user", "assistant", "retry", "system"),
        store.events.map { it.kind },
      )
      val user = store.events[0]
      val partial = store.events[1]
      val retry = store.events[2]
      val mark = store.events[3]
      // Same attempt owns exactly one assistant row (no second same-attempt assistant).
      assertEquals(1, store.events.count { it.kind == "assistant" })
      assertEquals("half", partial.text)
      assertTrue(partial.isPartial)
      assertEquals("boom", partial.failureReason)
      assertEquals("retried partial must be non-final", false, partial.isFinal)
      assertEquals(user.runId, partial.runId)
      assertEquals(user.runId, retry.runId)
      // System cancel mark binds the logical family with the exact text pattern.
      assertEquals(user.runId, mark.runId)
      assertEquals("turn ${user.runId} cancelled", mark.text)
      assertEquals("cancel mark must be final", true, mark.isFinal)
      // The second provider attempt never starts: cancel won the backoff gap.
      assertEquals(1, provider.streamCalls.get())
      // The logical family is completed by the cancel mark: no dangling.
      assertTrue(
        "cancel-marked family must not dangle, events=${store.events}",
        dev.librepocket.agent.ui.chat.findDanglingRunIds(store.events).isEmpty(),
      )
      // Replay (user/assistant incl. partial; system hidden) shows exactly one half.
      val replay = store.events.filter { it.kind == "user" || it.kind == "assistant" }
      assertEquals(listOf("hi", "half"), replay.map { it.text })
      // UI keeps one assistant block per attempt (the partial); the system
      // mark never becomes a chat row.
      val assistants = c.uiState.value.messages.filter { it.role == "assistant" }
      assertEquals(1, assistants.size)
      assertEquals("half", assistants.single().text)
      // Export/import consistency at the codec level: isFinal survives the round trip.
      for (e in store.events) {
        val back = JsonlCodec.decode(1, JsonlCodec.encode(e.copy(seq = e.seq.coerceAtLeast(1))))
        assertEquals("isFinal must round-trip for ${e.kind}", e.isFinal, back.isFinal)
      }
    } finally {
      gate.complete(Unit)
      c.close()
    }
  }

  @Test fun streamingCancelWithoutPriorTerminalKeepsFinalAssistant() {
    val store = FakeSessionStore()
    val sid = runBlocking { store.createSession("hi", "m") }
    val sink = SessionTranscriptSink(store, sid)
    val gate = CompletableDeferred<Unit>()
    val provider = StageFFakeProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        gate.await()
        emit(StreamEvent.Done("stop"))
      }
    }
    var n = 0
    val c = TurnController(
      provider = provider,
      policy = StageFAllowPolicy(),
      transcript = sink,
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "stagef-s-${n++}" },
    )
    try {
      val admission = runBlocking { c.startOrEnqueue("hi") }
      assertTrue(admission is TurnStart.Started)
      runBlocking {
        withTimeout(5_000) {
          while (c.uiState.value.messages.none { it.role == "assistant" && it.text == "half" }) delay(10)
        }
      }
      c.cancel()
      c.close()
      runBlocking { withTimeout(5_000) { c.flushTranscript() } }
      assertEquals(listOf("user", "assistant"), store.events.map { it.kind })
      val cancelled = store.events[1]
      assertEquals("half", cancelled.text)
      assertTrue(cancelled.isPartial)
      assertEquals("streaming cancel must stay final", true, cancelled.isFinal)
      assertTrue(dev.librepocket.agent.ui.chat.findDanglingRunIds(store.events).isEmpty())
    } finally {
      gate.complete(Unit)
      c.close()
    }
  }

  @Test fun retrySuccessControlHasFinalRowsAndNoDangling() {
    val store = FakeSessionStore()
    val sid = runBlocking { store.createSession("hi", "m") }
    val sink = SessionTranscriptSink(store, sid)
    val calls = AtomicInteger(0)
    val provider = StageFFakeProvider {
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
    var n = 0
    val c = TurnController(
      provider = provider,
      policy = StageFAllowPolicy(),
      transcript = sink,
      retryConfig = TurnRetryConfig(maxRetries = 1, retryDelaysMs = listOf(0L)),
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "stagef-c-${n++}" },
    )
    runBlocking { c.send("hi") }
    try {
      assertEquals(listOf("user", "assistant", "retry", "assistant"), store.events.map { it.kind })
      assertEquals(false, store.events[1].isFinal)
      assertEquals(true, store.events[3].isFinal)
      assertTrue(dev.librepocket.agent.ui.chat.findDanglingRunIds(store.events).isEmpty())
    } finally {
      c.close()
    }
  }
}
