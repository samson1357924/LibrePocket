package dev.librepocket.chat

import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spec §10.3 M3: fake [LlmProvider] emits fixed [StreamEvent] sequences.
 * Covers send→STREAMING→IDLE, double-send without crashing, steer queuing
 * without cancelling the current turn (fake sees 0 cancels), automatic steer
 * consumption, and cancel keeping the partial text.
 */
private class FakeSessionLlm(
  var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
  override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
  val seen: MutableList<ChatRequest> = Collections.synchronizedList(mutableListOf())
  var streamCalls = 0
  val cancelCalls = AtomicInteger(0)

  override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
    streamCalls++
    seen.add(request)
    try {
      emitAll(handler(request))
    } catch (e: CancellationException) {
      cancelCalls.incrementAndGet()
      throw e
    }
  }

  override suspend fun listModels(): List<String> = emptyList()
}

private class ChatAllowPolicy : PolicyStore {
  override fun evaluate(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
  override suspend fun setRule(rule: PolicyRule) = Unit
  override suspend fun removeRule(pattern: String) = Unit
  override suspend fun listRules(): List<PolicyRule> = emptyList()
  override suspend fun evaluateFresh(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

class ChatSessionTest {

  private fun session(
    provider: FakeSessionLlm,
    newId: () -> String = run {
      var n = 0
      { "s-${n++}" }
    },
  ): ChatSessionImpl = ChatSessionImpl(
    provider = provider,
    policy = ChatAllowPolicy(),
    transcript = NoOpTranscriptSink(),
    dispatcher = Dispatchers.Default,
    sleeper = {},
    newId = newId,
  )

  private fun awaitTrue(timeoutMs: Long = 8000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!cond()) {
      if (System.currentTimeMillis() > end) fail("timed out waiting for condition")
      Thread.sleep(10)
    }
  }

  private fun assistantsOf(s: ChatSession) =
    s.uiState.value.messages.filter { it.role == "assistant" }

  private fun usersOf(s: ChatSession) =
    s.uiState.value.messages.filter { it.role == "user" }.map { it.text }

  @Test fun sendStreamsThenIdles() {
    val provider = FakeSessionLlm {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "Hello"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val s = session(provider)
    runBlocking { s.send("hi") }
    assertEquals(listOf("hi"), usersOf(s))
    assertEquals("Hello", assistantsOf(s).single().text)
    assertFalse(assistantsOf(s).single().isPartial)
    assertEquals(ChatStatus.IDLE, s.uiState.value.status)
    assertEquals(1, provider.streamCalls)
  }

  @Test fun doubleSendThrowsWithoutCrashing() {
    val gate = CompletableDeferred<Unit>()
    val provider = FakeSessionLlm {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "partial"))
        gate.await()
        emit(StreamEvent.Done("stop"))
      }
    }
    val s = session(provider)
    val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
    try {
      val first = outer.async { s.send("first") }
      awaitTrue { assistantsOf(s).any { it.text == "partial" } }
      try {
        runBlocking { s.send("second") }
        fail("expected IllegalStateException")
      } catch (_: IllegalStateException) {
        // Projected via uiState.error; session survives.
      }
      gate.complete(Unit)
      runBlocking { withTimeout(5000) { first.join() } }
      assertEquals(ChatStatus.IDLE, s.uiState.value.status)
      assertEquals(listOf("first"), usersOf(s))
    } finally {
      outer.cancel()
    }
  }

  @Test fun steerQueuesWithoutCancellingAndAutoConsumes() {
    val gate = CompletableDeferred<Unit>()
    val provider = FakeSessionLlm { req ->
      flow {
        val current = req.messages.lastOrNull { it.role == "user" }?.text
        if (current == "first") {
          emit(StreamEvent.TextDelta(0, 0, "A"))
          gate.await()
          emit(StreamEvent.Done("stop"))
        } else {
          emit(StreamEvent.TextDelta(0, 0, "B-$current"))
          emit(StreamEvent.Done("stop"))
        }
      }
    }
    val s = session(provider)
    val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
    try {
      val first = outer.async { s.send("first") }
      awaitTrue { assistantsOf(s).any { it.text == "A" } }
      s.steer("follow-1")
      // Steer never cancels the current turn.
      assertEquals(0, provider.cancelCalls.get())
      assertEquals(1, s.uiState.value.pendingSteerCount)
      gate.complete(Unit)
      runBlocking { withTimeout(5000) { first.join() } }
      // Turn ends: the queued steer is auto-sent as the next user message.
      awaitTrue { s.uiState.value.status == ChatStatus.IDLE && usersOf(s).size == 2 }
      assertEquals(listOf("first", "follow-1"), usersOf(s))
      assertEquals(0, s.uiState.value.pendingSteerCount)
      assertEquals(0, provider.cancelCalls.get())
      assertEquals(listOf("A", "B-follow-1"), assistantsOf(s).map { it.text })
    } finally {
      outer.cancel()
    }
  }

  @Test fun cancelKeepsPartialAndCountsOnce() {
    val provider = FakeSessionLlm {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        awaitCancellation()
      }
    }
    val s = session(provider)
    val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
    try {
      val job = outer.async { s.send("hi") }
      awaitTrue { assistantsOf(s).any { it.text == "half" } }
      s.cancel()
      assertEquals(ChatStatus.CANCELLED, s.uiState.value.status)
      awaitTrue { provider.cancelCalls.get() == 1 }
      runBlocking { withTimeout(5000) { job.join() } }
      assertEquals("half", assistantsOf(s).single().text)
      assertTrue(assistantsOf(s).single().isPartial)
      assertEquals(ChatStatus.CANCELLED, s.uiState.value.status)
    } finally {
      outer.cancel()
    }
  }
}
