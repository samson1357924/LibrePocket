package dev.librepocket.chat

import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ChatCompletionsProvider
import dev.librepocket.provider.ProviderConfig
import dev.librepocket.provider.ProviderHttpConfig
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.ProviderProtocol as ProviderProto
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

private class FakeLlmProvider(
  var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
  override val protocol: ProviderProto = ProviderProto.CHAT_COMPLETIONS
  val seenRequests: MutableList<ChatRequest> = Collections.synchronizedList(mutableListOf())
  var streamCalls = 0
  val cancelCalls = AtomicInteger(0)

  override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
    streamCalls++
    seenRequests.add(request)
    try {
      emitAll(handler(request))
    } catch (e: CancellationException) {
      cancelCalls.incrementAndGet()
      throw e
    }
  }

  override suspend fun listModels(): List<String> = emptyList()
}

private class RecordingSink : TranscriptSink {
  val started: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
  val succeeded: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
  val failed: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
  data class Retried(val runId: String, val attempt: Int, val maxAttempts: Int, val delayMs: Long)
  val retried: MutableList<Retried> = Collections.synchronizedList(mutableListOf())
  val cancelled: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
  val steerQueued: MutableList<String> = Collections.synchronizedList(mutableListOf())
  data class Tool(val runId: String, val toolIndex: Int, val id: String, val name: String, val args: String)
  val tools: MutableList<Tool> = Collections.synchronizedList(mutableListOf())
  data class Usage(val runId: String, val inputTokens: Int?, val outputTokens: Int?)
  val usages: MutableList<Usage> = Collections.synchronizedList(mutableListOf())

  override suspend fun onTurnStarted(runId: String, text: String) { started.add(runId to text) }
  override suspend fun onTurnSucceeded(runId: String, text: String) { succeeded.add(runId to text) }
  override suspend fun onTurnFailed(runId: String, error: String) { failed.add(runId to error) }
  override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) {
    retried.add(Retried(runId, attempt, maxAttempts, delayMs))
  }
  override suspend fun onTurnCancelled(runId: String, partialText: String) { cancelled.add(runId to partialText) }
  override suspend fun onSteerQueued(text: String) { steerQueued.add(text) }
  override suspend fun onToolDone(runId: String, toolIndex: Int, id: String, name: String, argumentsJson: String) {
    tools.add(Tool(runId, toolIndex, id, name, argumentsJson))
  }
  override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) {
    usages.add(Usage(runId, inputTokens, outputTokens))
  }
}

private class AllowPolicy : PolicyStore {
  override fun evaluate(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
  override suspend fun setRule(rule: PolicyRule) = Unit
  override suspend fun removeRule(pattern: String) = Unit
  override suspend fun listRules(): List<PolicyRule> = emptyList()
  override suspend fun evaluateFresh(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

private class DenyPolicy : PolicyStore {
  override fun evaluate(action: String, resource: String) =
    PolicyDecision(Verdict.DENY, null, System.currentTimeMillis())
  override suspend fun setRule(rule: PolicyRule) = Unit
  override suspend fun removeRule(pattern: String) = Unit
  override suspend fun listRules(): List<PolicyRule> = emptyList()
  override suspend fun evaluateFresh(action: String, resource: String) =
    PolicyDecision(Verdict.DENY, null, System.currentTimeMillis())
}

class TurnControllerTest {

  private fun controller(
    provider: FakeLlmProvider,
    policy: PolicyStore = AllowPolicy(),
    delays: MutableList<Long> = mutableListOf(),
    retry: TurnRetryConfig = TurnRetryConfig(),
    transcript: TranscriptSink = NoOpTranscriptSink(),
  ): TurnController {
    var n = 0
    return TurnController(
      provider = provider,
      policy = policy,
      transcript = transcript,
      retryConfig = retry,
      dispatcher = Dispatchers.Default,
      sleeper = { delays.add(it) },
      newId = { "id-${n++}" },
    )
  }

  private fun awaitTrue(timeoutMs: Long = 8000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!cond()) {
      if (System.currentTimeMillis() > end) fail("timed out waiting for condition")
      Thread.sleep(10)
    }
  }

  private fun assistantsOf(c: TurnController) =
    c.uiState.value.messages.filter { it.role == "assistant" }

  private fun usersOf(c: TurnController) =
    c.uiState.value.messages.filter { it.role == "user" }.map { it.text }

  private fun lastUserTextOf(req: ChatRequest): String? =
    req.messages.lastOrNull { it.role == "user" }?.text

  @Test fun retryDefaultsMatchSpec() {
    val r = TurnRetryConfig()
    assertEquals(3, r.maxRetries)
    assertEquals(listOf(2_000L, 4_000L, 8_000L), r.retryDelaysMs)
  }

  @Test fun turnRetryConfigRejectsBudgetsAboveFourTotalAttempts() {
    try {
      TurnRetryConfig(maxRetries = 4)
      fail("expected hard four-attempt cap")
    } catch (_: IllegalArgumentException) {
      // expected
    }
  }

  @Test fun sendStreamsThenIdles() {
    val provider = FakeLlmProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "Hello"))
        emit(StreamEvent.TextDelta(0, 0, " world"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider)
    runBlocking { c.send("hi") }
    assertEquals(listOf("hi"), usersOf(c))
    val assistants = assistantsOf(c)
    assertEquals(1, assistants.size)
    assertEquals("Hello world", assistants[0].text)
    assertFalse(assistants[0].isPartial)
    assertEquals(ChatStatus.IDLE, c.uiState.value.status)
    assertEquals(null, c.uiState.value.error)
    assertEquals(1, provider.streamCalls)
    // Request carries the user history through the unified ChatRequest.
    // Phase 2 appends an ephemeral time block to a copy of the last user
    // message: raw text stays first, time block follows.
    val sent = lastUserTextOf(provider.seenRequests.single())
    assertTrue(sent!!.startsWith("hi\n\n"))
    assertTrue(sent.contains("Runtime time context:"))
  }

  @Test fun doubleSendThrowsAndFirstTurnSurvives() {
    val gate = CompletableDeferred<Unit>()
    val provider = FakeLlmProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "partial"))
        gate.await()
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider)
    val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
    try {
      val first = outer.async { c.send("first") }
      awaitTrue { assistantsOf(c).any { it.text == "partial" } }
      try {
        runBlocking { c.send("second") }
        fail("expected IllegalStateException")
      } catch (_: IllegalStateException) {
        // expected; also projected via uiState.error, no crash
      }
      assertNotNull(c.uiState.value.error)
      assertEquals(1, provider.streamCalls)
      gate.complete(Unit)
      runBlocking { withTimeout(5000) { first.join() } }
      assertEquals(ChatStatus.IDLE, c.uiState.value.status)
      assertEquals(listOf("first"), usersOf(c))
      val assistant = assistantsOf(c).single()
      assertEquals("partial", assistant.text)
      assertFalse(assistant.isPartial)
    } finally {
      outer.cancel()
    }
  }

  @Test fun steerQueuesWithoutCancellingAndAutoSendsNextInOrder() {
    val gate = CompletableDeferred<Unit>()
    val provider = FakeLlmProvider { input ->
      flow {
        // Phase 2 appends an ephemeral time block; branch on the raw text.
        val rawUserText = lastUserTextOf(input)?.substringBefore("\n\n")
        if (rawUserText == "first") {
          emit(StreamEvent.TextDelta(0, 0, "A"))
          gate.await()
          emit(StreamEvent.Done("stop"))
        } else {
          emit(StreamEvent.TextDelta(0, 0, "B-$rawUserText"))
          emit(StreamEvent.Done("stop"))
        }
      }
    }
    val c = controller(provider)
    val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
    try {
      val first = outer.async { c.send("first") }
      awaitTrue { assistantsOf(c).any { it.text == "A" } }
      c.steer("follow-1")
      c.steer("follow-2")
      // Steer never cancels the current turn (cooperative Job cancel count stays 0).
      assertEquals(0, provider.cancelCalls.get())
      assertEquals(2, c.uiState.value.pendingSteerCount)
      gate.complete(Unit)
      runBlocking { withTimeout(5000) { first.join() } }
      awaitTrue { c.uiState.value.status == ChatStatus.IDLE && usersOf(c).size == 3 }
      assertEquals(listOf("first", "follow-1", "follow-2"), usersOf(c))
      assertEquals(0, c.uiState.value.pendingSteerCount)
      assertEquals(0, provider.cancelCalls.get())
      val bodies = assistantsOf(c).map { it.text }
      assertEquals(listOf("A", "B-follow-1", "B-follow-2"), bodies)
      assertTrue(assistantsOf(c).none { it.isPartial })
    } finally {
      outer.cancel()
    }
  }

  @Test fun steerWhileIdleStartsTurn() {
    val provider = FakeLlmProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "yo"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider)
    c.steer("hello")
    awaitTrue { assistantsOf(c).any { !it.isPartial } }
    assertEquals(listOf("hello"), usersOf(c))
    assertEquals(ChatStatus.IDLE, c.uiState.value.status)
  }

  @Test fun cancelStopsStreamFastAndKeepsPartial() {
    val provider = FakeLlmProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "chunk-1"))
        emit(StreamEvent.TextDelta(0, 0, "chunk-2"))
        awaitCancellation()
      }
    }
    val c = controller(provider)
    val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
    try {
      val job = outer.async { c.send("hi") }
      awaitTrue { assistantsOf(c).any { it.text == "chunk-1chunk-2" } }
      c.cancel()
      // Immediate, synchronous flip (well under the 200ms budget).
      assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
      awaitTrue { provider.cancelCalls.get() == 1 }
      val frozen = assistantsOf(c).single().text
      Thread.sleep(300)
      assertEquals(frozen, assistantsOf(c).single().text)
      assertTrue(assistantsOf(c).single().isPartial)
      runBlocking { withTimeout(5000) { job.join() } }
      assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
    } finally {
      outer.cancel()
    }
  }

  @Test fun retryableFailuresRetryWithNewRunIdsAndBackoff() {
    val calls = AtomicInteger(0)
    val delays = mutableListOf<Long>()
    val provider = FakeLlmProvider {
      flow {
        when (calls.incrementAndGet()) {
          1 -> emit(StreamEvent.Failed("boom-1", retryable = true))
          2 -> emit(StreamEvent.Failed("boom-2", retryable = true))
          else -> {
            emit(StreamEvent.TextDelta(0, 0, "ok"))
            emit(StreamEvent.Done("stop"))
          }
        }
      }
    }
    val c = controller(provider, delays = delays)
    runBlocking { c.send("hi") }
    assertEquals(3, calls.get())
    assertEquals(listOf(2_000L, 4_000L), delays)
    // Fresh runId per attempt: one assistant block each, ids distinct.
    val assistants = assistantsOf(c)
    assertEquals(3, assistants.size)
    assertEquals(3, assistants.map { it.id }.toSet().size)
    // Failed fragments are kept (partial) and never backfilled.
    assertTrue(assistants[0].isPartial)
    assertTrue(assistants[1].isPartial)
    assertEquals("ok", assistants[2].text)
    assertFalse(assistants[2].isPartial)
    assertEquals(ChatStatus.IDLE, c.uiState.value.status)
  }

  @Test fun partialOutputFromOneAttemptNeverCombinesWithTheNextAttempt() {
    val calls = AtomicInteger(0)
    val ids = AtomicInteger(0)
    val provider = FakeLlmProvider {
      flow {
        if (calls.incrementAndGet() == 1) {
          emit(StreamEvent.TextDelta(0, 0, "OLD-PARTIAL"))
          emit(StreamEvent.Failed("connection lost", retryable = true))
        } else {
          emit(StreamEvent.TextDelta(0, 0, "NEW-ANSWER"))
          emit(StreamEvent.Done("stop"))
        }
      }
    }
    val c = TurnController(
      provider = provider,
      policy = AllowPolicy(),
      retryConfig = TurnRetryConfig(maxRetries = 1, retryDelaysMs = listOf(0L)),
      sleeper = {},
      newId = { "attempt-${ids.incrementAndGet()}" },
    )

    runBlocking { c.send("hi") }

    val assistants = assistantsOf(c)
    assertEquals(2, calls.get())
    assertEquals(listOf("OLD-PARTIAL", "NEW-ANSWER"), assistants.map { it.text })
    assertEquals(2, assistants.map { it.id }.toSet().size)
    assertTrue(assistants[0].isPartial)
    assertFalse(assistants[1].isPartial)
  }

  @Test fun retryExhaustionMakesExactlyFourActualHttpRequests() = runBlocking {
    val server = MockWebServer()
    server.start()
    try {
      repeat(16) {
        // Old provider-level and turn-level retries can consume up to 16 responses;
        // keep the regression bounded while asserting the fixed four-request budget below.
        server.enqueue(MockResponse().setResponseCode(503).setBody("temporarily unavailable"))
      }
      val provider = ChatCompletionsProvider(
        ProviderConfig(
          id = "turn-retry-test",
          label = "local test",
          baseUrl = server.url("/").toString().trimEnd('/'),
          protocol = ProviderProtocol.CHAT_COMPLETIONS,
          apiKeyRef = "fake-key-ref",
          // Retained compatibility fields must not create a provider retry loop.
          http = ProviderHttpConfig(maxRetries = 3, retryDelaysMs = listOf(0L)),
        ),
        { "stage5-fake-key".toCharArray() },
      )
      val controller = TurnController(
        provider = provider,
        policy = AllowPolicy(),
        retryConfig = TurnRetryConfig(maxRetries = 3, retryDelaysMs = listOf(0L, 0L, 0L)),
        sleeper = {},
      )

      controller.send("budget-test")

      assertEquals(4, server.requestCount)
      repeat(4) {
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull("request ${it + 1}", request)
        assertEquals("POST", request!!.method)
        assertEquals("Bearer stage5-fake-key", request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains("budget-test"))
      }
      assertEquals(ChatStatus.ERROR, controller.uiState.value.status)
    } finally {
      server.shutdown()
    }
  }

  @Test fun retryExhaustionWithRetryAfterZeroMakesExactlyFourActualHttpRequests() = runBlocking {
    val server = MockWebServer()
    server.start()
    try {
      repeat(8) {
        // Without the transport fix each controller attempt resends once more
        // (4 attempts -> 8 exchanges); with the fix the total stays 4.
        server.enqueue(
          MockResponse().setResponseCode(503).addHeader("Retry-After", "0")
            .setBody("temporarily unavailable"),
        )
      }
      val provider = ChatCompletionsProvider(
        ProviderConfig(
          id = "turn-retry-after-zero-test",
          label = "local test",
          baseUrl = server.url("/").toString().trimEnd('/'),
          protocol = ProviderProtocol.CHAT_COMPLETIONS,
          apiKeyRef = "fake-key-ref",
          http = ProviderHttpConfig(maxRetries = 3, retryDelaysMs = listOf(0L)),
        ),
        { "stage5-fake-key".toCharArray() },
      )
      val controller = TurnController(
        provider = provider,
        policy = AllowPolicy(),
        retryConfig = TurnRetryConfig(maxRetries = 3, retryDelaysMs = listOf(0L, 0L, 0L)),
        sleeper = {},
      )

      controller.send("budget-retry-after-zero")

      assertEquals("four controller attempts stay four wire requests", 4, server.requestCount)
      repeat(4) {
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull("request ${it + 1}", request)
        assertEquals("POST", request!!.method)
        assertEquals("Bearer stage5-fake-key", request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains("budget-retry-after-zero"))
      }
      assertEquals(ChatStatus.ERROR, controller.uiState.value.status)
    } finally {
      server.shutdown()
    }
  }

  @Test fun actualProviderPartialIoFailureRetriesAsSeparateAssistantAttempt() = runBlocking {
    val server = MockWebServer()
    server.start()
    try {
      val oldFrame = "data: {\"choices\":[{\"delta\":{\"content\":\"OLD\"}}]}\n\n"
      val truncatedBody = oldFrame + ":" + "padding-comment-".repeat(8_192)
      server.enqueue(
        MockResponse()
          .setHeader("Content-Type", "text/event-stream")
          .setBody(truncatedBody)
          .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
      )
      server.enqueue(
        MockResponse()
          .setHeader("Content-Type", "text/event-stream")
          .setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"NEW\"}}]}\n\n" +
              "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
              "data: [DONE]\n\n",
          ),
      )
      val provider = ChatCompletionsProvider(
        ProviderConfig(
          id = "partial-io-retry-test",
          label = "local test",
          baseUrl = server.url("/").toString().trimEnd('/'),
          protocol = ProviderProtocol.CHAT_COMPLETIONS,
          apiKeyRef = "fake-key-ref",
          // Legacy provider retries are deliberately immediate so the old nested
          // retry owner fails by request count rather than sleeping or hanging.
          http = ProviderHttpConfig(maxRetries = 3, retryDelaysMs = listOf(0L, 0L, 0L)),
        ),
        { "stage5-fake-key".toCharArray() },
      )
      val controller = TurnController(
        provider = provider,
        policy = AllowPolicy(),
        retryConfig = TurnRetryConfig(maxRetries = 1, retryDelaysMs = listOf(0L)),
        sleeper = {},
      )

      withTimeout(10_000) { controller.send("partial-io-budget-test") }

      assertEquals("one partial attempt plus one controller retry", 2, server.requestCount)
      repeat(2) {
        val recorded = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull("actual provider request ${it + 1}", recorded)
        assertEquals("Bearer stage5-fake-key", recorded!!.getHeader("Authorization"))
        assertTrue(recorded.body.readUtf8().contains("partial-io-budget-test"))
      }
      val assistants = assistantsOf(controller)
      assertEquals(listOf("OLD", "NEW"), assistants.map { it.text })
      assertEquals("each attempt has its own assistant block/run id", 2, assistants.map { it.id }.toSet().size)
      assertTrue("truncated first attempt remains partial", assistants[0].isPartial)
      assertFalse("second attempt completes", assistants[1].isPartial)
      assertFalse("partial text is never combined with the retry", assistants.any { it.text.contains("OLDNEW") })
      assertEquals(ChatStatus.IDLE, controller.uiState.value.status)
    } finally {
      server.shutdown()
    }
  }

  @Test fun actualUnauthorizedProviderResponseDoesNotRetryTheTurn() = runBlocking {
    val server = MockWebServer()
    server.start()
    try {
      server.enqueue(MockResponse().setResponseCode(401).setBody("invalid_api_key"))
      val provider = ChatCompletionsProvider(
        ProviderConfig(
          id = "turn-auth-test",
          label = "local test",
          baseUrl = server.url("/").toString().trimEnd('/'),
          protocol = ProviderProtocol.CHAT_COMPLETIONS,
          apiKeyRef = "fake-key-ref",
        ),
        { "stage5-fake-key".toCharArray() },
      )
      val controller = TurnController(
        provider = provider,
        policy = AllowPolicy(),
        retryConfig = TurnRetryConfig(maxRetries = 3, retryDelaysMs = listOf(0L, 0L, 0L)),
        sleeper = {},
      )

      controller.send("auth-test")

      assertEquals(1, server.requestCount)
      assertEquals(ChatStatus.ERROR, controller.uiState.value.status)
    } finally {
      server.shutdown()
    }
  }

  @Test fun fatalFailureDoesNotRetry() {
    val calls = AtomicInteger(0)
    val provider = FakeLlmProvider {
      flow {
        calls.incrementAndGet()
        emit(StreamEvent.Failed("http 401 unauthorized", retryable = false))
      }
    }
    val c = controller(provider)
    runBlocking { c.send("hi") }
    assertEquals(1, calls.get())
    assertEquals(ChatStatus.ERROR, c.uiState.value.status)
    assertNotNull(c.uiState.value.error)
    assertTrue(c.uiState.value.error!!.length <= 500)
  }

  @Test fun policyDenyBlocksSend() {
    val provider = FakeLlmProvider {
      flow { emit(StreamEvent.Done("stop")) }
    }
    val c = controller(provider, policy = DenyPolicy())
    try {
      runBlocking { c.send("hi") }
      fail("expected SecurityException")
    } catch (_: SecurityException) {
      // expected
    }
    assertEquals(0, provider.streamCalls)
    assertEquals(ChatStatus.ERROR, c.uiState.value.status)
  }

  @Test fun closeClearsQueueAndRejectsSend() {
    val gate = CompletableDeferred<Unit>()
    val provider = FakeLlmProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "x"))
        gate.await()
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider)
    val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
    try {
      val job = outer.async { c.send("hi") }
      awaitTrue { assistantsOf(c).isNotEmpty() }
      c.steer("queued")
      awaitTrue { c.uiState.value.pendingSteerCount == 1 }
      c.close()
      assertEquals(0, c.uiState.value.pendingSteerCount)
      awaitTrue { provider.cancelCalls.get() >= 1 }
      gate.complete(Unit)
      runBlocking { withTimeout(5000) { job.join() } }
      try {
        runBlocking { c.send("later") }
        fail("expected IllegalStateException after close")
      } catch (e: IllegalStateException) {
        assertTrue(e.message!!.contains("closed"))
      }
    } finally {
      outer.cancel()
    }
  }

  @Test fun blankSendAndSteerRejected() {
    val c = controller(FakeLlmProvider())
    try {
      runBlocking { c.send("  ") }
      fail("expected IllegalArgumentException")
    } catch (_: IllegalArgumentException) {
    }
    try {
      c.steer("")
      fail("expected IllegalArgumentException")
    } catch (_: IllegalArgumentException) {
    }
  }

  @Test fun unusedSleeperDefaultExists() {
    // Real-delay constructor path compiles (not executed here to avoid sleeping).
    var n = 0
    TurnController(FakeLlmProvider(), AllowPolicy(), NoOpTranscriptSink(), newId = { "k-${n++}" })
  }

  // ---- B1: error desensitization ----

  @Test fun errorIsRedactedViaRedactor() {
    val raw = "HTTP 401 unauthorized for https://api.example.com/v1?api_key=SECRET123 " +
      "with Bearer abcdef123456 from 192.168.0.7 denied"
    val provider = FakeLlmProvider {
      flow { emit(StreamEvent.Failed(raw, retryable = false)) }
    }
    val sink = RecordingSink()
    val c = controller(provider, transcript = sink)
    runBlocking { c.send("hi") }
    val err = c.uiState.value.error
    assertNotNull(err)
    assertTrue(err!!.length <= 500)
    assertFalse(err.contains("SECRET123"))
    assertFalse(err.contains("abcdef123456"))
    assertFalse(err.contains("192.168.0.7"))
    assertTrue(err.contains("REDACTED"))
    // Transcript persistence receives the same sanitized string.
    awaitTrue { sink.failed.isNotEmpty() }
    assertEquals(err, sink.failed.single().second)
  }

  @Test fun longErrorIsTruncatedTo500() {
    val provider = FakeLlmProvider {
      flow { emit(StreamEvent.Failed("x".repeat(600), retryable = false)) }
    }
    val c = controller(provider)
    runBlocking { c.send("hi") }
    assertNotNull(c.uiState.value.error)
    assertTrue(c.uiState.value.error!!.length <= 500)
  }

  @Test fun sanitizeErrorMatchesRedactor() {
    assertEquals(
      dev.librepocket.redact.Redactor.redactError("Bearer abcdef123456 boom"),
      sanitizeError("Bearer abcdef123456 boom"),
    )
  }

  // ---- B3: unified StreamEvent — nothing lost ----

  @Test fun reasoningDeltasAreRetainedInAssistantBlock() {
    val provider = FakeLlmProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "answer-"))
        emit(StreamEvent.ReasoningDelta(0, 1, "thought"))
        emit(StreamEvent.TextDelta(0, 2, "-done"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider)
    runBlocking { c.send("hi") }
    val body = assistantsOf(c).single().text
    assertTrue(body.contains("answer-"))
    assertTrue(body.contains("thought"))
    assertTrue(body.contains("-done"))
    assertFalse(assistantsOf(c).single().isPartial)
  }

  @Test fun toolDeltasAndDoneAreRecorded() {
    val provider = FakeLlmProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "working"))
        emit(StreamEvent.ToolDelta(0, "call_", "get_wea", "{\"city\":\"Par"))
        emit(StreamEvent.ToolDelta(0, "abc123", "ther", "is\"}"))
        emit(StreamEvent.ToolDone(0, "call_abc123", "get_weather", "{\"city\":\"Paris\"}"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val sink = RecordingSink()
    val c = controller(provider, transcript = sink)
    runBlocking { c.send("hi") }
    awaitTrue { sink.tools.isNotEmpty() }
    val tool = sink.tools.single()
    assertEquals(0, tool.toolIndex)
    assertEquals("call_abc123", tool.id)
    assertEquals("get_weather", tool.name)
    assertEquals("{\"city\":\"Paris\"}", tool.args)
    // UI block keeps a marker so the tool call is visible, not dropped.
    val body = assistantsOf(c).single().text
    assertTrue(body.contains("working"))
    assertTrue(body.contains("get_weather"))
    assertTrue(body.contains("{\"city\":\"Paris\"}"))
  }

  @Test fun toolDeltasWithoutToolDoneAreFlushedNotDropped() {
    val provider = FakeLlmProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "working"))
        emit(StreamEvent.ToolDelta(0, "call_x", "lookup", "{\"q\":\"hi\"}"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val sink = RecordingSink()
    val c = controller(provider, transcript = sink)
    runBlocking { c.send("hi") }
    awaitTrue { sink.tools.isNotEmpty() }
    val tool = sink.tools.single()
    assertEquals("lookup", tool.name)
    assertTrue(tool.args.contains("hi"))
    assertTrue(assistantsOf(c).single().text.contains("lookup"))
  }

  @Test fun usageIsRecordedAndExposed() {
    val provider = FakeLlmProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Usage(10, 20))
        emit(StreamEvent.Done("stop"))
      }
    }
    val sink = RecordingSink()
    val c = controller(provider, transcript = sink)
    runBlocking { c.send("hi") }
    awaitTrue { sink.usages.isNotEmpty() }
    assertEquals(10, sink.usages.single().inputTokens)
    assertEquals(20, sink.usages.single().outputTokens)
    assertEquals(StreamEvent.Usage(10, 20), c.lastUsage)
    assertNull(controller(FakeLlmProvider()).lastUsage)
  }

  @Test fun providerRetryingNoticesAreForwardedInOrder() {
    val provider = FakeLlmProvider {
      flow {
        emit(StreamEvent.Retrying(1, 3, 2_000L))
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val sink = RecordingSink()
    val c = controller(provider, transcript = sink)
    runBlocking { c.send("hi") }
    awaitTrue { sink.retried.isNotEmpty() }
    val first = sink.retried.first()
    assertEquals(1, first.attempt)
    assertEquals(3, first.maxAttempts)
    assertEquals(2_000L, first.delayMs)
    // Turn still succeeds; provider retry notices never abort the turn.
    assertEquals(ChatStatus.IDLE, c.uiState.value.status)
    assertEquals("ok", assistantsOf(c).single().text)
  }

  @Test fun ownRetriesStillEmitRetryingWithBackoffOrder() {
    val calls = AtomicInteger(0)
    val delays = mutableListOf<Long>()
    val provider = FakeLlmProvider {
      flow {
        if (calls.incrementAndGet() <= 2) {
          emit(StreamEvent.Failed("boom", retryable = true))
        } else {
          emit(StreamEvent.TextDelta(0, 0, "ok"))
          emit(StreamEvent.Done("stop"))
        }
      }
    }
    val sink = RecordingSink()
    val c = controller(provider, delays = delays, transcript = sink)
    runBlocking { c.send("hi") }
    assertEquals(listOf(2_000L, 4_000L), delays)
    // fireTranscript 是 scope.launch 異步投遞，抵達順序不保證：等齊 2 筆後按 attempt 排序再斷言。
    awaitTrue { sink.retried.size == 2 }
    val byAttempt = sink.retried.sortedBy { it.attempt }
    assertEquals(listOf(1, 2), byAttempt.map { it.attempt })
    assertEquals(listOf(2_000L, 4_000L), byAttempt.map { it.delayMs })
  }

  @Test fun truncatedStreamIsTreatedAsRetryable() {
    val calls = AtomicInteger(0)
    val provider = FakeLlmProvider {
      flow {
        calls.incrementAndGet()
        emit(StreamEvent.TextDelta(0, 0, "frag"))
        // No Done/Failed: truncated.
      }
    }
    val c = controller(provider, retry = TurnRetryConfig(maxRetries = 1))
    runBlocking { c.send("hi") }
    // 1 initial + 1 retry, then ERROR with sanitized message.
    assertEquals(2, calls.get())
    assertEquals(ChatStatus.ERROR, c.uiState.value.status)
    assertNotNull(c.uiState.value.error)
  }

  // Q1: idle startOrEnqueue accepts exactly once (Started + join completes).
  @Test fun startOrEnqueueIdleStartsOnce() {
    val provider = FakeLlmProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider)
    val verdict = runBlocking { c.startOrEnqueue("hi") }
    assertTrue(verdict is TurnStart.Started)
    runBlocking { withTimeout(5000) { (verdict as TurnStart.Started).host.join() } }
    assertEquals(listOf("hi"), usersOf(c))
    assertEquals(1, provider.streamCalls)
    assertEquals(ChatStatus.IDLE, c.uiState.value.status)
  }

  // Q1: busy startOrEnqueue queues exactly once (no throw, no extra provider
  // call); the queued follow-up runs after the first turn completes.
  @Test fun startOrEnqueueBusyQueuesOnceAndFollowUpRuns() {
    val gate = CompletableDeferred<Unit>()
    val provider = FakeLlmProvider { input ->
      flow {
        if (lastUserTextOf(input) == "first") {
          emit(StreamEvent.TextDelta(0, 0, "A"))
          gate.await()
          emit(StreamEvent.Done("stop"))
        } else {
          emit(StreamEvent.TextDelta(0, 0, "B-" + lastUserTextOf(input)))
          emit(StreamEvent.Done("stop"))
        }
      }
    }
    val sink = RecordingSink()
    val c = controller(provider, transcript = sink)
    val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
    try {
      val first = outer.async { c.send("first") }
      awaitTrue { assistantsOf(c).any { it.text == "A" } }
      val verdict = runBlocking { c.startOrEnqueue("second") }
      assertTrue(verdict is TurnStart.Queued)
      assertEquals(1, c.uiState.value.pendingSteerCount)
      assertEquals(1, provider.streamCalls)
      // fireTranscript delivers onSteerQueued asynchronously: await it.
      awaitTrue { sink.steerQueued.toList() == listOf("second") }
      gate.complete(Unit)
      runBlocking { withTimeout(5000) { first.join() } }
      awaitTrue { c.uiState.value.status == ChatStatus.IDLE && provider.streamCalls == 2 }
      assertEquals(0, c.uiState.value.pendingSteerCount)
      // The queued text is what ran as the follow-up turn.
      awaitTrue { usersOf(c).contains("second") }
    } finally {
      outer.cancel()
    }
  }

  @Test fun queueAdmissionAtCompletionIsPromotedInsteadOfStranded() {
    val completionCheck = CountDownLatch(1)
    val resumeCompletion = CountDownLatch(1)
    val provider = FakeLlmProvider {
      flow { emit(StreamEvent.Done("stop")) }
    }
    val c = controller(provider)
    c.beforeCompletionQueueCheckForTest = {
      c.beforeCompletionQueueCheckForTest = null
      completionCheck.countDown()
      check(resumeCompletion.await(30, TimeUnit.SECONDS))
    }

    try {
      val started = runBlocking { c.startOrEnqueue("first") } as TurnStart.Started
      assertTrue("completion did not reach queue check", completionCheck.await(5, TimeUnit.SECONDS))
      assertEquals(TurnStart.Queued, runBlocking { c.startOrEnqueue("follow-up") })
      resumeCompletion.countDown()
      runBlocking { withTimeout(5000) { started.host.join() } }

      awaitTrue { provider.streamCalls == 2 && c.uiState.value.status == ChatStatus.IDLE }
      assertEquals(listOf("first", "follow-up"), usersOf(c))
      assertEquals(0, c.uiState.value.pendingSteerCount)
    } finally {
      resumeCompletion.countDown()
      c.close()
    }
  }

  // N1 handoff: the follow-up gate runs BEFORE dequeue, so a deny at
  // promotion leaves the queued text in the FIFO (still reclaimable via
  // drainQueued) instead of dropping it, and the denied text is never
  // appended or started.
  @Test fun denyAtPromotionKeepsQueueReclaimable() {
    val gate = CompletableDeferred<Unit>()
    val provider = FakeLlmProvider { input ->
      flow {
        if (lastUserTextOf(input) == "first") {
          emit(StreamEvent.TextDelta(0, 0, "A"))
          gate.await()
          emit(StreamEvent.Done("stop"))
        } else {
          emit(StreamEvent.Done("stop"))
        }
      }
    }
    val evals = AtomicInteger(0)
    val policy = object : PolicyStore {
      override fun evaluate(action: String, resource: String) =
        PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
      override suspend fun setRule(rule: PolicyRule) = Unit
      override suspend fun removeRule(pattern: String) = Unit
      override suspend fun listRules(): List<PolicyRule> = emptyList()
      override suspend fun evaluateFresh(action: String, resource: String) =
        if (evals.incrementAndGet() == 2) {
          PolicyDecision(Verdict.DENY, null, System.currentTimeMillis())
        } else {
          PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
        }
    }
    val c = controller(provider, policy = policy)
    val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
    try {
      val first = outer.async { c.send("first") }
      awaitTrue { assistantsOf(c).any { it.text == "A" } }
      val verdict = runBlocking { c.startOrEnqueue("second") }
      assertTrue(verdict is TurnStart.Queued)
      gate.complete(Unit)
      runBlocking { withTimeout(5000) { first.join() } }
      awaitTrue { c.uiState.value.status == ChatStatus.ERROR }
      assertEquals(listOf("first"), usersOf(c))
      assertEquals(1, provider.streamCalls)
      val drained = c.drainQueued()
      assertEquals(listOf("second"), drained.map { it.text })
      assertEquals(0, c.uiState.value.pendingSteerCount)
    } finally {
      outer.cancel()
    }
  }

  @Test fun teardownAfterFollowUpGateDrainsIntentBeforeAtomicHandoff() {
    val firstTurnGate = CompletableDeferred<Unit>()
    val gatePassed = CountDownLatch(1)
    val releaseHandoff = CountDownLatch(1)
    val provider = FakeLlmProvider { input ->
      flow {
        if (lastUserTextOf(input) == "first") {
          firstTurnGate.await()
        } else {
          awaitCancellation()
        }
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = TurnController(
      provider = provider,
      policy = AllowPolicy(),
      dispatcher = Dispatchers.Default,
    )
    c.afterFollowUpGateForTest = {
      // Pause after ALLOW but before the atomic queue→transcript handoff.
      // Teardown must still be able to reclaim the original queued operation.
      gatePassed.countDown()
      check(releaseHandoff.await(30, TimeUnit.SECONDS))
    }
    val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
    var closer: Thread? = null
    try {
      val first = outer.async { c.send("first") }
      awaitTrue { provider.streamCalls == 1 }
      assertEquals(TurnStart.Queued, runBlocking { c.startOrEnqueue("follow-up", opId = 73) })
      firstTurnGate.complete(Unit)
      assertTrue("follow-up gate did not pass", gatePassed.await(5, TimeUnit.SECONDS))

      // The head is still in the queue until the handoff lock is acquired.
      assertEquals(listOf(QueuedIntent(73L, "follow-up")), c.drainQueued())
      closer = Thread { c.close() }.also { it.start() }
      closer.join(5000)
      assertFalse("close() did not finish", closer.isAlive)
      releaseHandoff.countDown()
      runBlocking { withTimeout(5000) { first.join() } }

      // Teardown won: the intent has exactly one owner (the drain result), not
      // a transcript append or a follow-up provider call.
      assertEquals(listOf("first"), usersOf(c))
      assertEquals(0, c.uiState.value.pendingSteerCount)
      assertTrue(c.drainQueued().isEmpty())
    } finally {
      releaseHandoff.countDown()
      closer?.join(5000)
      outer.cancel()
      c.close()
    }
  }

}
