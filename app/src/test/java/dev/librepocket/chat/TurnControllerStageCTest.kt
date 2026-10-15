package dev.librepocket.chat

import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol as ProviderProto
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.PrunePolicy
import dev.librepocket.session.PruneResult
import dev.librepocket.session.SessionMeta
import dev.librepocket.session.SessionStore
import dev.librepocket.session.SessionTranscriptSink
import dev.librepocket.session.TranscriptEvent
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class StageCFakeProvider(
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

private class StageCAllowPolicy : PolicyStore {
  override fun evaluate(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
  override suspend fun setRule(rule: PolicyRule) = Unit
  override suspend fun removeRule(pattern: String) = Unit
  override suspend fun listRules(): List<PolicyRule> = emptyList()
  override suspend fun evaluateFresh(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

private class StageCStore(
  val sessionId: String = "stagec-test-session",
) : SessionStore {
  val events: MutableList<TranscriptEvent> = Collections.synchronizedList(mutableListOf())
  override suspend fun createSession(title: String, model: String): String = sessionId
  override suspend fun listSessions(): List<SessionMeta> = emptyList()
  override suspend fun getSession(sessionId: String): SessionMeta? =
    SessionMeta(sessionId, "t", 0L, 0L, "m")
  override suspend fun appendEvent(event: TranscriptEvent): Long {
    events.add(event.copy(seq = (events.size + 1).toLong()))
    return events.size.toLong()
  }
  override suspend fun loadEvents(sessionId: String, afterSeq: Long, limit: Int): List<TranscriptEvent> =
    events.filter { it.sessionId == sessionId && it.seq > afterSeq }.take(limit)
  override suspend fun exportJsonl(sessionId: String, destFile: File) = Unit
  override suspend fun importJsonl(srcFile: File): String = error("unused")
  override suspend fun prune(policy: PrunePolicy): PruneResult = PruneResult(0, 0)
  override suspend fun deleteSession(sessionId: String) = Unit
}

/**
 * Stage C ledger regressions (Finding #3 P1 + #4 P1).
 *
 * - A retryable failure persists its intermediate partial BEFORE the retry
 *   notice, so the fragment survives reopen/export even when the next attempt
 *   succeeds.
 * - Every attempt terminal links back to the logical family via
 *   parentRunId/attemptIndex; the logical family owns the full chain.
 */
class TurnControllerStageCTest {

  private fun controller(
    provider: StageCFakeProvider,
    store: StageCStore,
    retry: TurnRetryConfig = TurnRetryConfig(),
  ): TurnController {
    var n = 0
    return TurnController(
      provider = provider,
      policy = StageCAllowPolicy(),
      transcript = SessionTranscriptSink(store, store.sessionId),
      retryConfig = retry,
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "stagec-${n++}" },
    )
  }

  @Test fun partialRetrySuccessPersistsHalfAndLinksFamily() {
    val store = StageCStore()
    val calls = AtomicInteger(0)
    val provider = StageCFakeProvider {
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
    val c = controller(provider, store, retry = TurnRetryConfig(maxRetries = 1, retryDelaysMs = listOf(0L)))
    runBlocking { c.send("hi") }
    try {
      assertEquals(listOf("user", "assistant", "retry", "assistant"), store.events.map { it.kind })
      val user = store.events[0]
      val partial = store.events[1]
      val retry = store.events[2]
      val success = store.events[3]
      // Intermediate fragment is durable (Finding #4).
      assertEquals("half", partial.text)
      assertTrue(partial.isPartial)
      assertEquals("boom", partial.failureReason)
      // First attempt reuses the logical id (pre-C compat).
      assertEquals(user.runId, partial.runId)
      assertNull(partial.parentRunId)
      assertEquals(0, partial.attemptIndex)
      // Retry notice stays bound to the logical id.
      assertEquals(user.runId, retry.runId)
      // Success links back to the logical family (Finding #3).
      assertEquals("ok", success.text)
      assertTrue(!success.isPartial)
      assertEquals(user.runId, success.parentRunId)
      assertEquals(1, success.attemptIndex)
      assertTrue(success.runId != user.runId)
      // The logical family is completed: no dangling, no INTERRUPTED on reopen.
      assertTrue(dev.librepocket.agent.ui.chat.findDanglingRunIds(store.events).isEmpty())
      // UI keeps one block per attempt.
      val assistants = c.uiState.value.messages.filter { it.role == "assistant" }
      assertEquals(2, assistants.size)
    } finally {
      c.close()
    }
  }

  @Test fun retryTerminalFailureLinksFamilyAndLeavesNoDangling() {
    val store = StageCStore()
    val provider = StageCFakeProvider {
      flow { emit(StreamEvent.Failed("down", retryable = true)) }
    }
    val c = controller(provider, store, retry = TurnRetryConfig(maxRetries = 1, retryDelaysMs = listOf(0L)))
    runBlocking { c.send("hi") }
    try {
      assertEquals(listOf("user", "assistant", "retry", "assistant"), store.events.map { it.kind })
      val user = store.events[0]
      val retried = store.events[1]
      val terminal = store.events[3]
      assertTrue(retried.isPartial)
      assertEquals("down", retried.failureReason)
      assertEquals(user.runId, retried.runId)
      assertNull(retried.parentRunId)
      assertEquals(0, retried.attemptIndex)
      assertTrue(terminal.isPartial)
      assertEquals("down", terminal.failureReason)
      assertEquals(user.runId, terminal.parentRunId)
      assertEquals(1, terminal.attemptIndex)
      assertTrue(dev.librepocket.agent.ui.chat.findDanglingRunIds(store.events).isEmpty())
    } finally {
      c.close()
    }
  }

  @Test fun cancelTerminalLinksFamily() {
    val store = StageCStore()
    val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
    val provider = StageCFakeProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        gate.await()
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider, store)
    try {
      val admission = runBlocking { c.startOrEnqueue("hi", opId = 3) }
      assertTrue(admission is TurnStart.Started)
      runBlocking {
        kotlinx.coroutines.withTimeout(5_000) {
          while (c.uiState.value.messages.none { it.role == "assistant" && it.text == "half" }) {
            delay(10)
          }
        }
      }
      c.cancel()
      c.close()
      runBlocking { kotlinx.coroutines.withTimeout(5_000) { c.flushTranscript() } }
      assertEquals(listOf("user", "assistant"), store.events.map { it.kind })
      val user = store.events[0]
      val cancelled = store.events[1]
      assertEquals("half", cancelled.text)
      assertTrue(cancelled.isPartial)
      // Single-attempt cancel reuses the logical id.
      assertEquals(user.runId, cancelled.runId)
      assertNull(cancelled.parentRunId)
      assertEquals(0, cancelled.attemptIndex)
      assertTrue(dev.librepocket.agent.ui.chat.findDanglingRunIds(store.events).isEmpty())
    } finally {
      gate.complete(Unit)
      c.close()
    }
  }
}
