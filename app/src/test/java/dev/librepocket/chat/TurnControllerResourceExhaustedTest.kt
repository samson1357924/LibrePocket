package dev.librepocket.chat

import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatMessage
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderFailure
import dev.librepocket.provider.ProviderFailureCode
import dev.librepocket.provider.ProviderProtocol as ProviderProto
import dev.librepocket.provider.SseFrameParser
import dev.librepocket.provider.StreamEvent
import dev.librepocket.provider.TOOL_ARGS_TOO_LARGE
import dev.librepocket.provider.streamingFlow
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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

  override suspend fun onTurnStarted(runId: String, text: String) = Unit
  override suspend fun onTurnSucceeded(runId: String, text: String) = Unit
  override suspend fun onTurnFailed(runId: String, error: String) { failed.add(error) }
  override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
  override suspend fun onTurnCancelled(runId: String, partialText: String) = Unit
  override suspend fun onSteerQueued(text: String) = Unit
  override suspend fun onToolDone(runId: String, toolIndex: Int, id: String, name: String, argumentsJson: String) {
    tools.add(Tool(runId, toolIndex, id, name, argumentsJson))
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
    var guard = 0
    while (sink.failed.isEmpty()) {
      if (++guard > 800) fail("timed out waiting for transcript failure")
      Thread.sleep(10)
    }
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
    Thread.sleep(300)
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
      var guard = 0
      while (sink.tools.isEmpty()) {
        if (++guard > 800) fail("timed out waiting for preserved tool record")
        Thread.sleep(10)
      }
      Thread.sleep(300)
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
