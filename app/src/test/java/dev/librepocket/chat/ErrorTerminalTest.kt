package dev.librepocket.chat

import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol as ProviderProto
import dev.librepocket.provider.StreamEvent
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

private class ErrorTerminalProvider(
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

private class ErrorTerminalPolicy : PolicyStore {
  override fun evaluate(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
  override suspend fun setRule(rule: PolicyRule) = Unit
  override suspend fun removeRule(pattern: String) = Unit
  override suspend fun listRules(): List<PolicyRule> = emptyList()
  override suspend fun evaluateFresh(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

/**
 * S3-F1 Error symmetry (Current): a fatal [Error] from the terminal write
 * (e.g. store AssertionError via onTurnSucceeded) must take the same
 * fail-closed path as Exception — sanitizeError → UI ERROR, INTERRUPTED
 * mark, flushDurable false — and must not strand queued work (steer text
 * retained for explicit recovery). A fatal [Error] from the provider stream
 * is a non-retryable terminal (no poison-adjacent tool flush, no retry —
 * option (a), true AGG_TOO_LARGE alignment; Target: typed provider Error
 * taxonomy instead of catch-Throwable).
 */
class ErrorTerminalTest {

  private fun errorSink(): TranscriptSink = object : TranscriptSink {
    override suspend fun onTurnStarted(runId: String, text: String) = Unit
    override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) {
      throw AssertionError("store invariant broken")
    }
    override suspend fun onTurnSucceeded(
      runId: String,
      text: String,
      parentRunId: String?,
      attemptIndex: Int?,
      hasReasoning: Boolean,
    ) = onTurnSucceeded(runId, text, hasReasoning)
    override suspend fun onTurnFailed(runId: String, error: String) = Unit
    override suspend fun onTurnFailed(
      runId: String,
      partialText: String,
      error: String,
      parentRunId: String?,
      attemptIndex: Int?,
    ) = onTurnFailed(runId, partialText, error)
    override suspend fun onTurnFailed(
      runId: String,
      partialText: String,
      error: String,
      parentRunId: String?,
      attemptIndex: Int?,
      isFinal: Boolean,
    ) = onTurnFailed(runId, partialText, error, parentRunId, attemptIndex)
    override suspend fun onTurnFailed(
      runId: String,
      partialText: String,
      error: String,
      parentRunId: String?,
      attemptIndex: Int?,
      isFinal: Boolean,
      hasReasoning: Boolean,
    ) = onTurnFailed(runId, partialText, error, parentRunId, attemptIndex, isFinal)
    override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
    override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) = Unit
    override suspend fun onTurnCancelled(
      runId: String,
      partialText: String,
      parentRunId: String?,
      attemptIndex: Int?,
      hasReasoning: Boolean,
    ) = onTurnCancelled(runId, partialText, hasReasoning)
    override suspend fun onSteerQueued(text: String) = Unit
    override suspend fun onToolDone(
      runId: String,
      toolIndex: Int,
      id: String,
      name: String,
      argumentsJson: String,
    ) = Unit
    override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) = Unit
  }

  @Test fun sinkAssertionError_projectsErrorAndStaysFailClosed_steerRecoverable() {
    val provider = ErrorTerminalProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    }
    var n = 0
    val c = TurnController(
      provider = provider,
      policy = ErrorTerminalPolicy(),
      transcript = errorSink(),
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "err-${n++}" },
    )
    try {
      val admission = runBlocking { c.startOrEnqueue("hi") }
      assertTrue("admission must start, was $admission", admission is TurnStart.Started)
      val host = (admission as TurnStart.Started).host
      runBlocking { withTimeout(8_000) { host.join() } }
      // UI ERROR via sanitizeError (no raw leak, message preserved for this payload).
      assertEquals(ChatStatus.ERROR, c.uiState.value.status)
      val err = c.uiState.value.error
      assertTrue("ERROR must be projected, was $err", err != null && err.contains("store invariant broken"))
      // Fail-closed: INTERRUPTED mark + flushDurable false (never true-and-dangling).
      assertTrue("fatal must mark durable failure", c.hasDurableFailures())
      assertTrue("fatal must mark interrupted", c.interruptedTranscriptRunIds().isNotEmpty())
      val durable = runBlocking { withTimeout(8_000) { c.flushDurable() } }
      assertFalse("fatal must keep flushDurable false", durable)
      // Steer text is retained for explicit recovery, never lost behind the seal.
      c.steer("next")
      runBlocking {
        withTimeout(8_000) {
          while (!c.uiState.value.queuedRecoveryRequired) {
            kotlinx.coroutines.delay(10)
          }
        }
      }
      val queued = c.drainQueued()
      assertEquals(1, queued.size)
      assertEquals("next", queued.single().text)
    } finally {
      c.close()
    }
  }

  @Test fun providerAssertionError_isNonRetryableTerminal_noRetryNoPoisonFlush() {
    val provider = ErrorTerminalProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        throw AssertionError("adapter invariant broken")
      }
    }
    var n = 0
    // Healthy sink: the non-retryable terminal failure itself must persist.
    val healthy = object : TranscriptSink {
      var toolDoneCalls = 0
      override suspend fun onTurnStarted(runId: String, text: String) = Unit
      override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) = Unit
      override suspend fun onTurnSucceeded(
        runId: String,
        text: String,
        parentRunId: String?,
        attemptIndex: Int?,
        hasReasoning: Boolean,
      ) = onTurnSucceeded(runId, text, hasReasoning)
      override suspend fun onTurnFailed(runId: String, error: String) = Unit
      override suspend fun onTurnFailed(
        runId: String,
        partialText: String,
        error: String,
        parentRunId: String?,
        attemptIndex: Int?,
      ) = onTurnFailed(runId, partialText, error)
      override suspend fun onTurnFailed(
        runId: String,
        partialText: String,
        error: String,
        parentRunId: String?,
        attemptIndex: Int?,
        isFinal: Boolean,
      ) = Unit
      override suspend fun onTurnFailed(
        runId: String,
        partialText: String,
        error: String,
        parentRunId: String?,
        attemptIndex: Int?,
        isFinal: Boolean,
        hasReasoning: Boolean,
      ) = onTurnFailed(runId, partialText, error, parentRunId, attemptIndex, isFinal)
      override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
      override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) = Unit
      override suspend fun onTurnCancelled(
        runId: String,
        partialText: String,
        parentRunId: String?,
        attemptIndex: Int?,
        hasReasoning: Boolean,
      ) = onTurnCancelled(runId, partialText, hasReasoning)
      override suspend fun onSteerQueued(text: String) = Unit
      override suspend fun onToolDone(
        runId: String,
        toolIndex: Int,
        id: String,
        name: String,
        argumentsJson: String,
      ) {
        toolDoneCalls++
      }
      override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) = Unit
    }
    val c = TurnController(
      provider = provider,
      policy = ErrorTerminalPolicy(),
      transcript = healthy,
      retryConfig = TurnRetryConfig(maxRetries = 3),
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "perr-${n++}" },
    )
    try {
      val admission = runBlocking { c.startOrEnqueue("hi") }
      assertTrue(admission is TurnStart.Started)
      val host = (admission as TurnStart.Started).host
      runBlocking { withTimeout(8_000) { host.join() } }
      // Single attempt: Error never retries.
      assertEquals(1, provider.streamCalls.get())
      // UI ERROR with the sanitized payload.
      assertEquals(ChatStatus.ERROR, c.uiState.value.status)
      assertTrue(
        "provider Error must project, was ${c.uiState.value.error}",
        c.uiState.value.error?.contains("adapter invariant broken") == true,
      )
      // No poison-adjacent flush: the collector must not record pending tools
      // for a fatal Error (same as AGG_TOO_LARGE).
      assertEquals(0, healthy.toolDoneCalls)
    } finally {
      c.close()
    }
  }

  /**
   * Current (S3-F1, option (a)): pending ToolDeltas followed by a fatal Error
   * must still be dropped (never flushed), locking the true AGG_TOO_LARGE
   * alignment. An Exception with the same pending prefix WOULD flush (see
   * TurnControllerAggBudgetTest) — Error never does, even when the message
   * carries no exhaustion marker.
   */
  @Test fun providerErrorWithPendingTools_dropsPending_noFlushNoRetry() {
    val provider = ErrorTerminalProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        emit(
          StreamEvent.ToolDelta(
            toolIndex = 0,
            idChunk = "call-1",
            nameChunk = "search",
            argsChunk = """{"q":"x"}""",
          ),
        )
        throw AssertionError("adapter invariant broken after tools")
      }
    }
    var n = 0
    val healthy = object : TranscriptSink {
      var toolDoneCalls = 0
      override suspend fun onTurnStarted(runId: String, text: String) = Unit
      override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) = Unit
      override suspend fun onTurnSucceeded(
        runId: String,
        text: String,
        parentRunId: String?,
        attemptIndex: Int?,
        hasReasoning: Boolean,
      ) = onTurnSucceeded(runId, text, hasReasoning)
      override suspend fun onTurnFailed(runId: String, error: String) = Unit
      override suspend fun onTurnFailed(
        runId: String,
        partialText: String,
        error: String,
        parentRunId: String?,
        attemptIndex: Int?,
      ) = onTurnFailed(runId, partialText, error)
      override suspend fun onTurnFailed(
        runId: String,
        partialText: String,
        error: String,
        parentRunId: String?,
        attemptIndex: Int?,
        isFinal: Boolean,
      ) = Unit
      override suspend fun onTurnFailed(
        runId: String,
        partialText: String,
        error: String,
        parentRunId: String?,
        attemptIndex: Int?,
        isFinal: Boolean,
        hasReasoning: Boolean,
      ) = onTurnFailed(runId, partialText, error, parentRunId, attemptIndex, isFinal)
      override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
      override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) = Unit
      override suspend fun onTurnCancelled(
        runId: String,
        partialText: String,
        parentRunId: String?,
        attemptIndex: Int?,
        hasReasoning: Boolean,
      ) = onTurnCancelled(runId, partialText, hasReasoning)
      override suspend fun onSteerQueued(text: String) = Unit
      override suspend fun onToolDone(
        runId: String,
        toolIndex: Int,
        id: String,
        name: String,
        argumentsJson: String,
      ) {
        toolDoneCalls++
      }
      override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) = Unit
    }
    val c = TurnController(
      provider = provider,
      policy = ErrorTerminalPolicy(),
      transcript = healthy,
      retryConfig = TurnRetryConfig(maxRetries = 3),
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "perr-pend-${n++}" },
    )
    try {
      val admission = runBlocking { c.startOrEnqueue("hi") }
      assertTrue(admission is TurnStart.Started)
      val host = (admission as TurnStart.Started).host
      runBlocking { withTimeout(8_000) { host.join() } }
      assertEquals(1, provider.streamCalls.get())
      assertEquals(ChatStatus.ERROR, c.uiState.value.status)
      assertTrue(
        "provider Error must project, was ${c.uiState.value.error}",
        c.uiState.value.error?.contains("adapter invariant broken after tools") == true,
      )
      // Pending ToolDelta dropped: Error branch skips flushPendingTools even
      // though the message has no exhaustion marker (unlike Exception).
      assertEquals(0, healthy.toolDoneCalls)
    } finally {
      c.close()
    }
  }
}
