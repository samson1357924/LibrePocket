package dev.librepocket.chat

import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatMessage
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.FailureKind
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderErrorClassifier
import dev.librepocket.provider.ProviderFailure
import dev.librepocket.provider.ProviderFailureCode
import dev.librepocket.provider.ProviderProtocol as ProviderProto
import dev.librepocket.provider.SseFrameParser
import dev.librepocket.provider.StreamEvent
import dev.librepocket.provider.TEXT_TOO_LARGE
import dev.librepocket.provider.TOOL_ARGS_TOO_LARGE
import dev.librepocket.provider.ATTEMPT_DEADLINE_EXCEEDED
import dev.librepocket.provider.SSE_TOTAL_TOO_LARGE
import dev.librepocket.provider.inferAggFailureCodeName
import dev.librepocket.provider.streamingFlow
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class ResExhaustFakeProvider(
  var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
  override val protocol: ProviderProto = ProviderProto.CHAT_COMPLETIONS
  val seenRequests: MutableList<ChatRequest> = Collections.synchronizedList(mutableListOf())
  val streamCalls = AtomicInteger(0)

  override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
    streamCalls.incrementAndGet()
    seenRequests.add(request)
    emitAll(handler(request))
  }

  override suspend fun listModels(): List<String> = emptyList()
}

private class ResExhaustAllowPolicy : PolicyStore {
  override fun evaluate(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
  override suspend fun setRule(rule: PolicyRule) = Unit
  override suspend fun removeRule(pattern: String) = Unit
  override suspend fun listRules(): List<PolicyRule> = emptyList()
  override suspend fun evaluateFresh(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

private class ResExhaustRecordingSink : TranscriptSink {
  val failed: MutableList<String> = Collections.synchronizedList(mutableListOf())
  data class Tool(val runId: String, val toolIndex: Int, val id: String, val name: String, val args: String)
  val tools: MutableList<Tool> = Collections.synchronizedList(mutableListOf())

  // P3/TEST: deterministic delivery latches (no wall-clock polling).
  val failedLatch = CompletableDeferred<Unit>()
  val toolLatch = CompletableDeferred<Unit>()

  override suspend fun onTurnStarted(runId: String, text: String) = Unit
  override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) = Unit
  override suspend fun onTurnSucceeded(
    runId: String,
    text: String,
    parentRunId: String?,
    attemptIndex: Int?,
    hasReasoning: Boolean,
  ) = onTurnSucceeded(runId, text, hasReasoning)
  override suspend fun onTurnFailed(runId: String, error: String) {
    failed.add(error)
    failedLatch.complete(Unit)
  }
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
  override suspend fun onToolDone(runId: String, toolIndex: Int, id: String, name: String, argumentsJson: String) {
    tools.add(Tool(runId, toolIndex, id, name, argumentsJson))
    toolLatch.complete(Unit)
  }
  override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) = Unit
}

/**
 * Stage D Finding #3: a provider resource-exhausted [StreamEvent.Failed]
 * (typed `AGG_TOO_LARGE` code or overflow markers) must not flush
 * poison-adjacent pending ToolDeltas. Already-completed [StreamEvent.ToolDone]
 * records stay (emitted inline); the truncated fallback (`failed == null`)
 * still flushes.
 *
 * `code` travels via [StreamEvent.Failed.code]: `streamingFlow` fills
 * `ProviderFailure.code?.name` or infers it from the message markers, and
 * `pumpSse` sets it for SSE budget overflows. [isResourceExhausted] checks
 * the code first, then the same markers case-insensitively for
 * legacy/message-only `Failed`s.
 */
class TurnControllerResourceExhaustedTest {

  private fun controller(
    provider: ResExhaustFakeProvider,
    transcript: TranscriptSink = NoOpTranscriptSink(),
  ): TurnController {
    var n = 0
    return TurnController(
      provider = provider,
      policy = ResExhaustAllowPolicy(),
      transcript = transcript,
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "res-${n++}" },
    )
  }

  private fun awaitFailed(sink: ResExhaustRecordingSink) {
    // P3: deterministic latch instead of a 10ms poll loop.
    runBlocking { withTimeout(5_000) { sink.failedLatch.await() } }
  }

  private fun assertSuppressed(
    c: TurnController,
    provider: ResExhaustFakeProvider,
    sink: ResExhaustRecordingSink,
    marker: String,
  ) {
    assertEquals(ChatStatus.ERROR, c.uiState.value.status)
    val error = c.uiState.value.error.orEmpty()
    assertTrue("typed overflow expected, got: $error", error.contains(marker))
    assertEquals("poisoned stream must not be retried", 1, provider.streamCalls.get())
    awaitFailed(sink)
    assertEquals(1, sink.failed.size)
    assertTrue(
      "transcript must carry the typed reason, got: ${sink.failed.single()}",
      sink.failed.single().contains(marker),
    )
    // P3: deterministic drain barrier instead of a 300ms absence sleep. The
    // turn host has joined, so no turn-scoped write can be admitted after this
    // flush; anything a poison-flush regression would have written is already
    // delivered to the recording sink below. The boolean result is asserted so
    // a drain timeout cannot vacuous-pass the absence check.
    runBlocking { assertTrue("transcript drain timed out", c.flushTranscript(8_000)) }
    assertTrue("no poison-adjacent tool records may be persisted, got: ${sink.tools}", sink.tools.isEmpty())
    val body = c.uiState.value.messages.filter { it.role == "assistant" }.single().text
    assertFalse("resource-exhausted pending tools must leave no marker, got: ${body.take(120)}", body.contains("[tool:"))
  }

  @Test fun toolArgsTooLargeViaCodedStreamingFlowSuppressesPendingFlush() {
    val provider = ResExhaustFakeProvider()
    provider.handler = {
      streamingFlow {
        emit(StreamEvent.ToolDelta(0, "id-0", "f", "{\"a\":1}"))
        emit(StreamEvent.ToolDelta(0, null, null, "x"))
        throw ProviderFailure(false, TOOL_ARGS_TOO_LARGE, code = ProviderFailureCode.AGG_TOO_LARGE)
      }
    }
    // Probe the transport layer directly: the typed throw must surface as a
    // coded Failed (code propagation, not just message text).
    val probe = runBlocking {
      provider.handler(ChatRequest(model = "m", messages = listOf(ChatMessage("user", "hi")))).toList()
    }
    val probeFailed = probe.filterIsInstance<StreamEvent.Failed>().single()
    assertTrue(probeFailed.message.contains(TOOL_ARGS_TOO_LARGE))
    assertEquals(ProviderFailureCode.AGG_TOO_LARGE.name, probeFailed.code)

    val sink = ResExhaustRecordingSink()
    val c = controller(provider, sink)
    try {
      runBlocking { c.send("hi") }
      assertSuppressed(c, provider, sink, TOOL_ARGS_TOO_LARGE)
    } finally {
      c.close()
    }
  }

  @Test fun textTooLargeViaCodedStreamingFlowSuppressesPendingFlush() {
    val provider = ResExhaustFakeProvider()
    provider.handler = {
      streamingFlow {
        emit(StreamEvent.ToolDelta(0, "id-0", "f", "{\"a\":1}"))
        emit(StreamEvent.ToolDelta(0, null, null, "x"))
        throw ProviderFailure(false, TEXT_TOO_LARGE, code = ProviderFailureCode.AGG_TOO_LARGE)
      }
    }
    // Probe the transport layer directly: the typed throw must surface as a
    // coded non-retryable Failed (code propagation, not just message text).
    val probe = runBlocking {
      provider.handler(ChatRequest(model = "m", messages = listOf(ChatMessage("user", "hi")))).toList()
    }
    val probeFailed = probe.filterIsInstance<StreamEvent.Failed>().single()
    assertTrue(probeFailed.message.contains(TEXT_TOO_LARGE))
    assertEquals(ProviderFailureCode.AGG_TOO_LARGE.name, probeFailed.code)
    assertFalse("TEXT_TOO_LARGE must be non-retryable", probeFailed.retryable)

    val sink = ResExhaustRecordingSink()
    val c = controller(provider, sink)
    try {
      runBlocking { c.send("hi") }
      assertSuppressed(c, provider, sink, TEXT_TOO_LARGE)
      // The collector-thrown shape (code present after the Phase 1 fix) and
      // the legacy message-only shape must both read as exhausted.
      assertTrue(isResourceExhausted(StreamEvent.Failed(TEXT_TOO_LARGE, retryable = false)))
      assertTrue(
        isResourceExhausted(
          StreamEvent.Failed(
            TEXT_TOO_LARGE,
            retryable = false,
            code = ProviderFailureCode.AGG_TOO_LARGE.name,
          ),
        ),
      )
      assertFalse(isResourceExhausted(StreamEvent.Failed("boom", retryable = true)))
    } finally {
      c.close()
    }
  }

  @Test fun textTooLargeCollectorThrowSuppressesPendingFlush() {
    // Collector-thrown TEXT_TOO_LARGE (requireTurnTextBudget) takes the
    // `catch ProviderFailure` path: pending ToolDeltas must not flush.
    val provider = ResExhaustFakeProvider {
      flow {
        emit(StreamEvent.ToolDelta(0, "id-0", "f", "{\"a\":1}"))
        emit(StreamEvent.ToolDelta(0, null, null, "x"))
        repeat(101) { emit(StreamEvent.TextDelta(0, 0, "y".repeat(1_000))) }
        emit(StreamEvent.Done("stop"))
      }
    }
    val sink = ResExhaustRecordingSink()
    val c = controller(provider, sink)
    try {
      runBlocking { c.send("hi") }
      assertSuppressed(c, provider, sink, TEXT_TOO_LARGE)
    } finally {
      c.close()
    }
  }

  @Test fun textTooLargeMarkersAreFatalAndInferAggTooLarge() {
    // Classifier: a text_too_large body snippet is fatal (never retried),
    // consistent with inferAggFailureCodeName and isResourceExhausted.
    assertEquals(
      FailureKind.FATAL,
      ProviderErrorClassifier.classify(null, null, "response text_too_large: budget exceeded"),
    )
    assertEquals(
      ProviderFailureCode.AGG_TOO_LARGE.name,
      inferAggFailureCodeName("TEXT_TOO_LARGE: per-attempt budget exceeded"),
    )
  }

  @Test fun sseFrameTooLargeMessageOnlyStillSuppressesPendingFlush() {
    // Legacy/message-only Failed (code == null): the controller falls back to
    // the marker check, consistent with the classifier's fatal markers.
    val provider = ResExhaustFakeProvider {
      flow {
        emit(StreamEvent.ToolDelta(1, "id-1", "g", "{}"))
        emit(StreamEvent.ToolDelta(1, null, null, "y"))
        emit(StreamEvent.Failed(SseFrameParser.FRAME_TOO_LARGE_MESSAGE, retryable = false))
      }
    }
    val sink = ResExhaustRecordingSink()
    val c = controller(provider, sink)
    try {
      runBlocking { c.send("hi") }
      assertSuppressed(c, provider, sink, SseFrameParser.FRAME_TOO_LARGE_MESSAGE)
      // The message-only shape carries no code, yet is still exhausted.
      assertTrue(isResourceExhausted(StreamEvent.Failed(SseFrameParser.FRAME_TOO_LARGE_MESSAGE, false)))
      assertFalse(isResourceExhausted(StreamEvent.Failed("boom", retryable = true)))
      assertFalse(isResourceExhausted(null))
    } finally {
      c.close()
    }
  }

  @Test fun sseLineTooLongViaInferredCodeSuppressesPendingFlush() {
    // Uncoded throw: streamingFlow infers AGG_TOO_LARGE from the message.
    val provider = ResExhaustFakeProvider()
    provider.handler = {
      streamingFlow {
        emit(StreamEvent.ToolDelta(0, "id-0", "n", "{}"))
        throw ProviderFailure(false, "SSE_LINE_TOO_LONG")
      }
    }
    val probe = runBlocking {
      provider.handler(ChatRequest(model = "m", messages = listOf(ChatMessage("user", "hi")))).toList()
    }
    val probeFailed = probe.filterIsInstance<StreamEvent.Failed>().single()
    assertTrue(probeFailed.message.contains("SSE_LINE_TOO_LONG"))
    assertEquals(
      "uncoded SSE overflow must infer the typed code",
      ProviderFailureCode.AGG_TOO_LARGE.name,
      probeFailed.code,
    )

    val sink = ResExhaustRecordingSink()
    val c = controller(provider, sink)
    try {
      runBlocking { c.send("hi") }
      assertSuppressed(c, provider, sink, "SSE_LINE_TOO_LONG")
    } finally {
      c.close()
    }
  }

  @Test fun completedToolDonePreservedWhilePendingSuppressed() {
    val provider = ResExhaustFakeProvider()
    provider.handler = {
      streamingFlow {
        emit(StreamEvent.ToolDone(0, "c0", "f0", "{}"))
        emit(StreamEvent.ToolDelta(1, "c1", "f1", "{\"a\":1}"))
        throw ProviderFailure(false, TOOL_ARGS_TOO_LARGE, code = ProviderFailureCode.AGG_TOO_LARGE)
      }
    }
    val sink = ResExhaustRecordingSink()
    val c = controller(provider, sink)
    try {
      runBlocking { c.send("hi") }
      assertEquals(ChatStatus.ERROR, c.uiState.value.status)
      val error = c.uiState.value.error.orEmpty()
      assertTrue("typed overflow expected, got: $error", error.contains(TOOL_ARGS_TOO_LARGE))
      assertEquals("poisoned stream must not be retried", 1, provider.streamCalls.get())
      awaitFailed(sink)
      assertEquals(1, sink.failed.size)
      // P3: deterministic tool-delivery latch + drain barrier instead of polls
      // and a 300ms absence sleep (boolean asserted: no vacuous drain timeout).
      runBlocking { withTimeout(5_000) { sink.toolLatch.await() } }
      runBlocking { assertTrue("transcript drain timed out", c.flushTranscript(8_000)) }
      assertEquals("only the completed ToolDone survives, got: ${sink.tools}", 1, sink.tools.size)
      val kept = sink.tools.single()
      assertEquals(0, kept.toolIndex)
      assertEquals("c0", kept.id)
      assertEquals("f0", kept.name)
      assertTrue("pending idx1 must leave no record", sink.tools.none { it.toolIndex == 1 })
      val body = c.uiState.value.messages.filter { it.role == "assistant" }.single().text
      assertTrue("completed ToolDone marker stays, got: ${body.take(120)}", body.contains("[tool:f0"))
      assertFalse("pending idx1 must leave no marker, got: ${body.take(200)}", body.contains("[tool:f1"))
    } finally {
      c.close()
    }
  }

  @Test fun attemptDeadlineExceededViaCodedStreamingFlowSuppressesPendingFlushAndDoesNotRetry() {
    val provider = ResExhaustFakeProvider()
    provider.handler = {
      streamingFlow {
        emit(StreamEvent.ToolDelta(0, "id-0", "f", "{\"a\":1}"))
        emit(StreamEvent.ToolDelta(0, null, null, "x"))
        throw ProviderFailure(false, ATTEMPT_DEADLINE_EXCEEDED, code = ProviderFailureCode.DEADLINE_EXCEEDED)
      }
    }
    val probe = runBlocking {
      provider.handler(ChatRequest(model = "m", messages = listOf(ChatMessage("user", "hi")))).toList()
    }
    val probeFailed = probe.filterIsInstance<StreamEvent.Failed>().single()
    assertTrue(probeFailed.message.contains(ATTEMPT_DEADLINE_EXCEEDED))
    assertEquals(ProviderFailureCode.DEADLINE_EXCEEDED.name, probeFailed.code)
    assertFalse("ATTEMPT_DEADLINE_EXCEEDED must be non-retryable", probeFailed.retryable)

    val sink = ResExhaustRecordingSink()
    val c = controller(provider, sink)
    try {
      runBlocking { c.send("hi") }
      assertSuppressed(c, provider, sink, ATTEMPT_DEADLINE_EXCEEDED)
      assertTrue(isResourceExhausted(StreamEvent.Failed(ATTEMPT_DEADLINE_EXCEEDED, retryable = false)))
      assertTrue(
        isResourceExhausted(
          StreamEvent.Failed(
            ATTEMPT_DEADLINE_EXCEEDED,
            retryable = false,
            code = ProviderFailureCode.DEADLINE_EXCEEDED.name,
          ),
        ),
      )
    } finally {
      c.close()
    }
  }

  @Test fun sseTotalTooLargeViaCodedStreamingFlowSuppressesPendingFlushAndDoesNotRetry() {
    val provider = ResExhaustFakeProvider()
    provider.handler = {
      streamingFlow {
        emit(StreamEvent.ToolDelta(0, "id-0", "f", "{\"a\":1}"))
        emit(StreamEvent.ToolDelta(0, null, null, "x"))
        throw ProviderFailure(false, SSE_TOTAL_TOO_LARGE, code = ProviderFailureCode.AGG_TOO_LARGE)
      }
    }
    val probe = runBlocking {
      provider.handler(ChatRequest(model = "m", messages = listOf(ChatMessage("user", "hi")))).toList()
    }
    val probeFailed = probe.filterIsInstance<StreamEvent.Failed>().single()
    assertTrue(probeFailed.message.contains(SSE_TOTAL_TOO_LARGE))
    assertEquals(ProviderFailureCode.AGG_TOO_LARGE.name, probeFailed.code)
    assertFalse("SSE_TOTAL_TOO_LARGE must be non-retryable", probeFailed.retryable)

    val sink = ResExhaustRecordingSink()
    val c = controller(provider, sink)
    try {
      runBlocking { c.send("hi") }
      assertSuppressed(c, provider, sink, SSE_TOTAL_TOO_LARGE)
      assertTrue(isResourceExhausted(StreamEvent.Failed(SSE_TOTAL_TOO_LARGE, retryable = false)))
      assertTrue(
        isResourceExhausted(
          StreamEvent.Failed(
            SSE_TOTAL_TOO_LARGE,
            retryable = false,
            code = ProviderFailureCode.AGG_TOO_LARGE.name,
          ),
        ),
      )
    } finally {
      c.close()
    }
  }

  @Test fun attemptDeadlineExceededAndSseTotalTooLargeMarkersAreFatalAndInferCodes() {
    assertEquals(
      FailureKind.FATAL,
      ProviderErrorClassifier.classify(null, null, "error: attempt_deadline_exceeded"),
    )
    assertEquals(
      FailureKind.FATAL,
      ProviderErrorClassifier.classify(null, null, "error: sse_total_too_large: wire bytes exceeded"),
    )
    assertEquals(
      FailureKind.FATAL,
      ProviderErrorClassifier.classify(null, java.io.IOException("attempt_deadline_exceeded"), null),
    )
    assertEquals(
      FailureKind.FATAL,
      ProviderErrorClassifier.classify(null, java.io.IOException("sse_total_too_large"), null),
    )
    assertEquals(
      FailureKind.FATAL,
      ProviderErrorClassifier.classify(null, java.io.IOException("wrap", java.io.IOException("attempt_deadline_exceeded")), null),
    )
    assertEquals(
      FailureKind.FATAL,
      ProviderErrorClassifier.classify(null, java.io.IOException("wrap", java.io.IOException("sse_total_too_large")), null),
    )
    assertEquals(
      ProviderFailureCode.DEADLINE_EXCEEDED.name,
      inferAggFailureCodeName("ATTEMPT_DEADLINE_EXCEEDED"),
    )
    assertEquals(
      ProviderFailureCode.AGG_TOO_LARGE.name,
      inferAggFailureCodeName("SSE_TOTAL_TOO_LARGE"),
    )
    assertEquals(
      null,
      inferAggFailureCodeName(null as Throwable?),
    )
    assertEquals(
      null,
      inferAggFailureCodeName(java.io.IOException("generic error")),
    )
    assertEquals(
      ProviderFailureCode.DEADLINE_EXCEEDED.name,
      inferAggFailureCodeName(
        java.io.IOException("wrap", java.io.IOException("ATTEMPT_DEADLINE_EXCEEDED")),
      ),
    )
    assertEquals(
      ProviderFailureCode.AGG_TOO_LARGE.name,
      inferAggFailureCodeName(
        java.io.IOException("wrap", java.io.IOException("SSE_TOTAL_TOO_LARGE")),
      ),
    )
    assertEquals(
      ProviderFailureCode.DEADLINE_EXCEEDED.name,
      inferAggFailureCodeName(
        RuntimeException(
          "wrap",
          ProviderFailure(false, "deadline", code = ProviderFailureCode.DEADLINE_EXCEEDED),
        ),
      ),
    )
    // Cause chain cycle detection in inferAggFailureCodeName:
    val cyclicClean1 = Exception("clean 1")
    val cyclicClean2 = Exception("clean 2", cyclicClean1)
    cyclicClean1.initCause(cyclicClean2)
    assertEquals(null, inferAggFailureCodeName(cyclicClean1))

    val cyclicFatal1 = Exception("fatal 1")
    val cyclicFatal2 = java.io.IOException("ATTEMPT_DEADLINE_EXCEEDED", cyclicFatal1)
    cyclicFatal1.initCause(cyclicFatal2)
    assertEquals(ProviderFailureCode.DEADLINE_EXCEEDED.name, inferAggFailureCodeName(cyclicFatal1))
  }

  @Test fun wrappedIOExceptionDeadlineExceededSuppressesPendingFlushAndDoesNotRetry() {
    val provider = ResExhaustFakeProvider()
    provider.handler = {
      flow {
        emit(StreamEvent.ToolDelta(0, "id-0", "f", "{\"a\":1}"))
        emit(StreamEvent.ToolDelta(0, null, null, "x"))
        throw java.io.IOException("wrapped transport error", java.io.IOException(ATTEMPT_DEADLINE_EXCEEDED))
      }
    }
    val sink = ResExhaustRecordingSink()
    val c = controller(provider, sink)
    try {
      runBlocking { c.send("hi") }
      assertEquals(ChatStatus.ERROR, c.uiState.value.status)
      assertEquals("poisoned stream must not be retried", 1, provider.streamCalls.get())
      awaitFailed(sink)
      assertEquals(1, sink.failed.size)
      assertTrue(sink.failed.single().contains("wrapped transport error"))
      runBlocking { assertTrue("transcript drain timed out", c.flushTranscript(8_000)) }
      assertTrue("no poison-adjacent tool records may be persisted, got: ${sink.tools}", sink.tools.isEmpty())
      val body = c.uiState.value.messages.filter { it.role == "assistant" }.single().text
      assertFalse("resource-exhausted pending tools must leave no marker, got: ${body.take(120)}", body.contains("[tool:"))
    } finally {
      c.close()
    }
  }

  @Test fun wrappedIOExceptionSseTotalTooLargeInStreamingFlowInfersCodeAndSuppressesPendingFlush() {
    val provider = ResExhaustFakeProvider()
    provider.handler = {
      streamingFlow {
        emit(StreamEvent.ToolDelta(0, "id-0", "f", "{\"a\":1}"))
        emit(StreamEvent.ToolDelta(0, null, null, "x"))
        throw java.io.IOException("transport wrap", java.io.IOException(SSE_TOTAL_TOO_LARGE))
      }
    }
    val probe = runBlocking {
      provider.handler(ChatRequest(model = "m", messages = listOf(ChatMessage("user", "hi")))).toList()
    }
    val probeFailed = probe.filterIsInstance<StreamEvent.Failed>().single()
    assertEquals(ProviderFailureCode.AGG_TOO_LARGE.name, probeFailed.code)
    assertFalse("wrapped SSE_TOTAL_TOO_LARGE must be non-retryable", probeFailed.retryable)

    val sink = ResExhaustRecordingSink()
    val c = controller(provider, sink)
    try {
      runBlocking { c.send("hi") }
      assertEquals(ChatStatus.ERROR, c.uiState.value.status)
      assertEquals("poisoned stream must not be retried", 1, provider.streamCalls.get())
      awaitFailed(sink)
      assertEquals(1, sink.failed.size)
      runBlocking { assertTrue("transcript drain timed out", c.flushTranscript(8_000)) }
      assertTrue("no poison-adjacent tool records may be persisted, got: ${sink.tools}", sink.tools.isEmpty())
      val body = c.uiState.value.messages.filter { it.role == "assistant" }.single().text
      assertFalse("resource-exhausted pending tools must leave no marker, got: ${body.take(120)}", body.contains("[tool:"))
    } finally {
      c.close()
    }
  }

  @Test fun completedToolDonePreservedOnAttemptDeadlineExceeded() {
    val provider = ResExhaustFakeProvider()
    provider.handler = {
      streamingFlow {
        emit(StreamEvent.ToolDone(0, "c0", "f0", "{}"))
        emit(StreamEvent.ToolDelta(1, "c1", "f1", "{\"a\":1}"))
        throw ProviderFailure(false, ATTEMPT_DEADLINE_EXCEEDED, code = ProviderFailureCode.DEADLINE_EXCEEDED)
      }
    }
    val sink = ResExhaustRecordingSink()
    val c = controller(provider, sink)
    try {
      runBlocking { c.send("hi") }
      assertEquals(ChatStatus.ERROR, c.uiState.value.status)
      val error = c.uiState.value.error.orEmpty()
      assertTrue("typed overflow expected, got: $error", error.contains(ATTEMPT_DEADLINE_EXCEEDED))
      assertEquals("poisoned stream must not be retried", 1, provider.streamCalls.get())
      awaitFailed(sink)
      assertEquals(1, sink.failed.size)
      runBlocking { withTimeout(5_000) { sink.toolLatch.await() } }
      runBlocking { assertTrue("transcript drain timed out", c.flushTranscript(8_000)) }
      assertEquals("only the completed ToolDone survives, got: ${sink.tools}", 1, sink.tools.size)
      val kept = sink.tools.single()
      assertEquals(0, kept.toolIndex)
      assertEquals("c0", kept.id)
      assertEquals("f0", kept.name)
      assertTrue("pending idx1 must leave no record", sink.tools.none { it.toolIndex == 1 })
      val body = c.uiState.value.messages.filter { it.role == "assistant" }.single().text
      assertTrue("completed ToolDone marker stays, got: ${body.take(120)}", body.contains("[tool:f0"))
      assertFalse("pending idx1 must leave no marker, got: ${body.take(200)}", body.contains("[tool:f1"))
    } finally {
      c.close()
    }
  }
}
