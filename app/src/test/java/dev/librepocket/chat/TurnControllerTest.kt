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
import kotlinx.coroutines.flow.first
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Sentinel prefix of the ephemeral runtime time block appended by TurnController.buildRequest. */
private const val TIME_BLOCK_SENTINEL = "\n\nRuntime time context:"

/**
 * Strips the trailing sentinel-led time block from a provider-request user
 * text, returning the admitted base text. The sentinel (not a bare blank
 * line) is used so multi-paragraph user input keeps its own blank lines.
 * The block is always appended as a suffix, so cut at its last occurrence
 * to preserve a literal sentinel inside user text.
 */
internal fun stripTimeBlock(text: String): String {
  val idx = text.lastIndexOf(TIME_BLOCK_SENTINEL)
  return if (idx >= 0) text.substring(0, idx) else text
}

private class FakeLlmProvider(
  var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
  override val protocol: ProviderProto = ProviderProto.CHAT_COMPLETIONS
  val seenRequests: MutableList<ChatRequest> = Collections.synchronizedList(mutableListOf())
  val streamCalls = AtomicInteger(0)
  val cancelCalls = AtomicInteger(0)
  val cancellationObserved = CountDownLatch(1)
  private val requestEntrySignals = ConcurrentHashMap<String, CountDownLatch>()

  fun signalRequestEntry(userText: String): CountDownLatch = CountDownLatch(1).also { signal ->
    check(requestEntrySignals.putIfAbsent(userText, signal) == null) {
      "request entry already registered for $userText"
    }
  }

  override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
    streamCalls.incrementAndGet()
    seenRequests.add(request)
    request.messages.lastOrNull { it.role == "user" }?.text?.let { userText ->
      requestEntrySignals[stripTimeBlock(userText)]?.countDown()
    }
    try {
      emitAll(handler(request))
    } catch (e: CancellationException) {
      cancelCalls.incrementAndGet()
      cancellationObserved.countDown()
      throw e
    }
  }

  override suspend fun listModels(): List<String> = emptyList()
}

private enum class GateFailure { DENY, ASK, UNAVAILABLE }

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

  // Phase 2 appends an ephemeral time block (sentinel-led trailing suffix)
  // to the last user message in the provider request. Branch on the raw
  // text, not the time suffix.
  private fun baseUserTextOf(req: ChatRequest): String? =
    lastUserTextOf(req)?.let(::stripTimeBlock)

  @Test fun stripTimeBlockKeepsMultiParagraphInput() {
    assertEquals(
      "para1\n\npara2",
      stripTimeBlock("para1\n\npara2\n\nRuntime time context:\n- Current time: x"),
    )
    assertEquals("para1\n\npara2", stripTimeBlock("para1\n\npara2"))
    assertEquals("", stripTimeBlock("\n\nRuntime time context: (time unknown)"))
  }

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
    assertEquals(1, provider.streamCalls.get())
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
      assertEquals(1, provider.streamCalls.get())
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

  @Test fun directStartWhileBusyWithNormalQueueKeepsBusyContractAndPromotesQueueOnce() = runBlocking {
    val firstTurnGate = CompletableDeferred<Unit>()
    val provider = FakeLlmProvider { request ->
      flow {
        if (baseUserTextOf(request) == "A") firstTurnGate.await()
        emit(StreamEvent.Done("stop"))
      }
    }
    val requestEntered = provider.signalRequestEntry("A")
    val c = controller(provider)
    try {
      val active = c.startTurn("A")
      assertTrue("A did not enter the provider", requestEntered.await(5, TimeUnit.SECONDS))
      assertEquals(TurnStart.Queued, c.startOrEnqueue("B", opId = 91))
      assertFalse(c.uiState.value.queuedRecoveryRequired)

      val failure = runCatching { c.startTurn("C") }.exceptionOrNull()
      assertEquals(IllegalStateException::class.java, failure?.javaClass)
      assertEquals("already in flight", failure?.message)
      assertEquals("busy: another turn is in flight", c.uiState.value.error)
      assertEquals(listOf("A"), usersOf(c))
      assertEquals(1, provider.streamCalls.get())

      firstTurnGate.complete(Unit)
      withTimeout(5_000) { active.join() }
      awaitTrue {
        c.uiState.value.status == ChatStatus.IDLE && usersOf(c) == listOf("A", "B") &&
          provider.streamCalls.get() == 2
      }
      assertEquals(listOf("A", "B"), usersOf(c))
      assertEquals(listOf("A", "B"), provider.seenRequests.mapNotNull(::baseUserTextOf))
      assertEquals(0, c.uiState.value.pendingSteerCount)
      assertFalse(c.uiState.value.queuedRecoveryRequired)
      assertTrue(c.drainQueued().isEmpty())
    } finally {
      firstTurnGate.complete(Unit)
      c.close()
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

  @Test fun idleSteerRacingNewTurnQueuesAtomicallyWithoutLosingText() {
    val firstTurnGate = CompletableDeferred<Unit>()
    val followUpGatePassed = CountDownLatch(1)
    val releaseFollowUpGate = CountDownLatch(1)
    val steerCheckedIdle = CountDownLatch(1)
    val releaseSteer = CountDownLatch(1)
    val provider = FakeLlmProvider { request ->
      flow {
        if (baseUserTextOf(request) == "first") firstTurnGate.await()
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider)
    c.afterFollowUpGateForTest = {
      c.afterFollowUpGateForTest = null
      followUpGatePassed.countDown()
      check(releaseFollowUpGate.await(30, TimeUnit.SECONDS))
    }
    c.afterIdleSteerCheckForTest = {
      c.afterIdleSteerCheckForTest = null
      steerCheckedIdle.countDown()
      check(releaseSteer.await(30, TimeUnit.SECONDS))
    }
    val steerThreadFailure = AtomicReference<Throwable?>()
    val steerThread = Thread {
      try {
        c.steer("legacy")
      } catch (failure: Throwable) {
        steerThreadFailure.set(failure)
      }
    }
    val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
    try {
      steerThread.start()
      assertTrue("idle steer did not reach its admission seam", steerCheckedIdle.await(5, TimeUnit.SECONDS))

      val first = outer.async { c.send("first") }
      awaitTrue { provider.streamCalls.get() == 1 }
      assertEquals(TurnStart.Queued, runBlocking { c.startOrEnqueue("queued", opId = 73) })
      firstTurnGate.complete(Unit)

      // The active turn's ALLOWed promotion is paused before its handoff.
      // The racing idle steer must atomically queue behind the older intent.
      assertTrue("follow-up gate did not pass", followUpGatePassed.await(5, TimeUnit.SECONDS))
      releaseSteer.countDown()
      steerThread.join(5_000)
      assertFalse("steer caller did not return", steerThread.isAlive)
      assertNull(steerThreadFailure.get())
      awaitTrue { c.uiState.value.pendingSteerCount == 2 }
      assertEquals(2, c.uiState.value.pendingSteerCount)
      assertFalse(c.uiState.value.queuedRecoveryRequired)

      // The fresh gate already passed; releasing the promotion should run both
      // accepted FIFO intents in their original order.
      releaseFollowUpGate.countDown()
      runBlocking { withTimeout(5_000) { first.join() } }
      awaitTrue { c.uiState.value.status == ChatStatus.IDLE && usersOf(c).size == 3 }
      assertEquals(listOf("first", "queued", "legacy"), usersOf(c))
      assertEquals(3, provider.streamCalls.get())
      assertEquals(0, c.uiState.value.pendingSteerCount)
      assertTrue(c.drainQueued().isEmpty())
    } finally {
      releaseSteer.countDown()
      releaseFollowUpGate.countDown()
      steerThread.join(5_000)
      firstTurnGate.complete(Unit)
      outer.cancel()
      c.close()
    }
  }

  @Test fun idleSteerRetainsTextOnFreshGateDenyAskOrFailure() {
    fun decision(verdict: Verdict) = PolicyDecision(verdict, null, System.currentTimeMillis())

    for (failure in GateFailure.entries) {
      val policy = object : PolicyStore {
        override fun evaluate(action: String, resource: String) = decision(Verdict.ALLOW)
        override suspend fun setRule(rule: PolicyRule) = Unit
        override suspend fun removeRule(pattern: String) = Unit
        override suspend fun listRules(): List<PolicyRule> = emptyList()
        override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision = when (failure) {
          GateFailure.DENY -> decision(Verdict.DENY)
          GateFailure.ASK -> decision(Verdict.ASK)
          GateFailure.UNAVAILABLE -> throw IllegalStateException("synthetic policy failure")
        }
      }
      val provider = FakeLlmProvider()
      val c = controller(provider, policy = policy)
      val expectedError = when (failure) {
        GateFailure.DENY -> "denied by policy"
        GateFailure.ASK -> "approval required"
        GateFailure.UNAVAILABLE -> "policy evaluation failed"
      }
      try {
        c.steer("unaccepted")
        awaitTrue { c.uiState.value.queuedRecoveryRequired }

        assertEquals(ChatStatus.ERROR, c.uiState.value.status)
        assertEquals(expectedError, c.uiState.value.error)
        assertEquals(1, c.uiState.value.pendingSteerCount)
        assertTrue(c.uiState.value.queuedRecoveryRequired)
        assertEquals(0, provider.streamCalls.get())
        assertTrue(usersOf(c).isEmpty())
        assertEquals(listOf(QueuedIntent(null, "unaccepted")), c.drainQueued())
      } finally {
        c.close()
      }
    }
  }

  @Test fun legacySteerRestoresNeedsRecoveryFifoBeforeCurrentText() {
    val steerGateEntered = CountDownLatch(1)
    val releaseSteerGate = CountDownLatch(1)
    val firstTurnGate = CompletableDeferred<Unit>()
    val evaluations = AtomicInteger()
    val policy = object : PolicyStore {
      override fun evaluate(action: String, resource: String) =
        PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
      override suspend fun setRule(rule: PolicyRule) = Unit
      override suspend fun removeRule(pattern: String) = Unit
      override suspend fun listRules(): List<PolicyRule> = emptyList()
      override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision =
        when (evaluations.incrementAndGet()) {
          1 -> {
            steerGateEntered.countDown()
            check(releaseSteerGate.await(30, TimeUnit.SECONDS))
            PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
          }
          2 -> PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
          3 -> PolicyDecision(Verdict.DENY, null, System.currentTimeMillis())
          else -> PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
        }
    }
    val provider = FakeLlmProvider { request ->
      flow {
        if (baseUserTextOf(request) == "first") firstTurnGate.await()
        emit(StreamEvent.Done("stop"))
      }
    }
    val requestEntered = provider.signalRequestEntry("first")
    val c = controller(provider, policy = policy)
    try {
      c.steer("legacy")
      assertTrue("steer did not enter its fresh gate", steerGateEntered.await(5, TimeUnit.SECONDS))

      val first = runBlocking { c.startTurn("first") }
      assertTrue("provider request did not enter", requestEntered.await(5, TimeUnit.SECONDS))
      assertEquals(TurnStart.Queued, runBlocking { c.startOrEnqueue("older-1", opId = 71) })
      assertEquals(TurnStart.Queued, runBlocking { c.startOrEnqueue("older-2", opId = 72) })
      firstTurnGate.complete(Unit)
      runBlocking { withTimeout(5_000) { first.join() } }

      assertEquals(ChatStatus.ERROR, c.uiState.value.status)
      assertEquals("denied by policy", c.uiState.value.error)
      assertEquals(2, c.uiState.value.pendingSteerCount)
      assertTrue(c.uiState.value.queuedRecoveryRequired)
      assertEquals(listOf("first"), usersOf(c))
      assertEquals(1, provider.streamCalls.get())

      // The idle admission now receives the older FIFO as NeedsRecovery. It
      // must restore that order before appending its own unaccepted text.
      releaseSteerGate.countDown()
      awaitTrue { c.uiState.value.pendingSteerCount == 3 && c.uiState.value.queuedRecoveryRequired }
      assertEquals("denied by policy", c.uiState.value.error)
      assertEquals(listOf("first"), usersOf(c))
      assertEquals(1, provider.streamCalls.get())
      assertEquals(
        listOf(
          QueuedIntent(71L, "older-1"),
          QueuedIntent(72L, "older-2"),
          QueuedIntent(null, "legacy"),
        ),
        c.drainQueued(),
      )
    } finally {
      releaseSteerGate.countDown()
      firstTurnGate.complete(Unit)
      c.close()
    }
  }

  @Test fun lateLegacySteerDenyPreservesAnActiveTurnUntilRecoverySettlement() = runBlocking {
    val legacyGateEntered = CompletableDeferred<Unit>()
    val releaseLegacyGate = CompletableDeferred<Unit>()
    val activeTurnGate = CompletableDeferred<Unit>()
    val evaluations = AtomicInteger()
    val policy = object : PolicyStore {
      override fun evaluate(action: String, resource: String) =
        PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
      override suspend fun setRule(rule: PolicyRule) = Unit
      override suspend fun removeRule(pattern: String) = Unit
      override suspend fun listRules(): List<PolicyRule> = emptyList()
      override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision =
        if (evaluations.incrementAndGet() == 1) {
          legacyGateEntered.complete(Unit)
          releaseLegacyGate.await()
          PolicyDecision(Verdict.DENY, null, System.currentTimeMillis())
        } else {
          PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
        }
    }
    val provider = FakeLlmProvider { request ->
      flow {
        if (baseUserTextOf(request) == "active only") activeTurnGate.await()
        emit(StreamEvent.Done("stop"))
      }
    }
    val requestEntered = provider.signalRequestEntry("active only")
    val c = controller(provider, policy = policy)
    try {
      c.steer("late denied legacy")
      withTimeout(5_000) { legacyGateEntered.await() }

      val active = c.startTurn("active only")
      assertTrue("active turn did not enter provider", requestEntered.await(5, TimeUnit.SECONDS))
      assertEquals(ChatStatus.STREAMING, c.uiState.value.status)

      releaseLegacyGate.complete(Unit)
      withTimeout(5_000) {
        c.uiState.first { it.queuedRecoveryRequired && it.pendingSteerCount == 1 }
      }
      assertEquals("late deny must not overwrite the active projection", ChatStatus.STREAMING, c.uiState.value.status)
      assertNull(c.uiState.value.error)
      assertEquals(listOf("active only"), usersOf(c))
      assertEquals(1, provider.streamCalls.get())

      activeTurnGate.complete(Unit)
      withTimeout(5_000) {
        c.uiState.first { it.status == ChatStatus.ERROR && it.queuedRecoveryRequired }
      }
      assertEquals(listOf("active only"), usersOf(c))
      assertEquals(1, provider.streamCalls.get())
      assertEquals(listOf(QueuedIntent(null, "late denied legacy")), c.drainQueued())
    } finally {
      releaseLegacyGate.complete(Unit)
      activeTurnGate.complete(Unit)
      c.close()
    }
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
    assertEquals(0, provider.streamCalls.get())
    assertEquals(ChatStatus.ERROR, c.uiState.value.status)
    assertFalse(c.uiState.value.queuedRecoveryRequired)
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
    assertEquals(1, provider.streamCalls.get())
    assertEquals(ChatStatus.IDLE, c.uiState.value.status)
  }

  // Q1: busy startOrEnqueue queues exactly once (no throw, no extra provider
  // call); the queued follow-up runs after the first turn completes.
  @Test fun startOrEnqueueBusyQueuesOnceAndFollowUpRuns() {
    val gate = CompletableDeferred<Unit>()
    val provider = FakeLlmProvider { input ->
      flow {
        if (baseUserTextOf(input) == "first") {
          emit(StreamEvent.TextDelta(0, 0, "A"))
          gate.await()
          emit(StreamEvent.Done("stop"))
        } else {
          emit(StreamEvent.TextDelta(0, 0, "B-" + baseUserTextOf(input)))
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
      assertEquals(1, provider.streamCalls.get())
      // fireTranscript delivers onSteerQueued asynchronously: await it.
      awaitTrue { sink.steerQueued.toList() == listOf("second") }
      gate.complete(Unit)
      runBlocking { withTimeout(5000) { first.join() } }
      awaitTrue { c.uiState.value.status == ChatStatus.IDLE && provider.streamCalls.get() == 2 }
      assertEquals(0, c.uiState.value.pendingSteerCount)
      // The queued text is what ran as the follow-up turn.
      awaitTrue { usersOf(c).contains("second") }
    } finally {
      outer.cancel()
    }
  }

  @Test fun busyStartOrEnqueueRejectsImagesWithoutQueueOrProviderSideEffects() = runBlocking {
    val releaseFirst = CompletableDeferred<Unit>()
    val provider = FakeLlmProvider { request ->
      flow {
        if (baseUserTextOf(request) == "first") releaseFirst.await()
        emit(StreamEvent.Done("stop"))
      }
    }
    val loadedImageCount = AtomicInteger()
    val c = TurnController(
      provider = provider,
      policy = AllowPolicy(),
      dispatcher = Dispatchers.Default,
      imageLoader = { images -> loadedImageCount.addAndGet(images.size); emptyList() },
    )
    try {
      val first = c.startOrEnqueue("first") as TurnStart.Started
      awaitTrue { provider.streamCalls.get() == 1 }

      val failure = runCatching {
        c.startOrEnqueue("image follow-up", images = listOf(ChatImageRef("/private/image.jpg", "image/jpeg")), opId = 101)
      }.exceptionOrNull()

      assertTrue("busy image admission must throw IllegalStateException", failure is IllegalStateException)
      assertEquals(0, c.uiState.value.pendingSteerCount)
      assertTrue(c.drainQueued().isEmpty())
      assertEquals(1, provider.streamCalls.get())
      assertEquals(0, loadedImageCount.get())
      assertEquals(listOf("first"), usersOf(c))

      releaseFirst.complete(Unit)
      withTimeout(5_000) { first.host.join() }
    } finally {
      releaseFirst.complete(Unit)
      c.close()
    }
  }

  @Test fun postGateBusyRaceRejectsImagesWithoutQueueOrProviderSideEffects() = runBlocking {
    val gateEntered = CountDownLatch(1)
    val releaseGate = CountDownLatch(1)
    // Pin the active turn inside the provider: without this barrier the
    // immediate-Done flow can finish and clear inFlight before the racing
    // image op resumes post-gate (hosted-only flake), making it see idle.
    val activeTurnGate = CompletableDeferred<Unit>()
    val provider = FakeLlmProvider { request ->
      flow {
        if (baseUserTextOf(request) == "active") activeTurnGate.await()
        emit(StreamEvent.Done("stop"))
      }
    }
    val activeRequestEntered = provider.signalRequestEntry("active")
    val loadedImageCount = AtomicInteger()
    val c = TurnController(
      provider = provider,
      policy = AllowPolicy(),
      dispatcher = Dispatchers.Default,
      imageLoader = { images -> loadedImageCount.addAndGet(images.size); emptyList() },
    )
    val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
    c.afterStartOrEnqueueGateForTest = {
      c.afterStartOrEnqueueGateForTest = null
      gateEntered.countDown()
      check(releaseGate.await(30, TimeUnit.SECONDS))
    }
    try {
      val imageAdmission = outer.async {
        runCatching {
          c.startOrEnqueue(
            "racing image follow-up",
            images = listOf(ChatImageRef("/private/image.jpg", "image/jpeg")),
            opId = 102,
          )
        }.exceptionOrNull()
      }
      assertTrue("image admission did not reach its post-gate seam", gateEntered.await(5, TimeUnit.SECONDS))

      val active = c.startOrEnqueue("active") as TurnStart.Started
      // Prove the active turn is pinned inside the provider before releasing
      // the racing op; otherwise it may complete first and the race is lost.
      assertTrue("active provider request did not enter", activeRequestEntered.await(5, TimeUnit.SECONDS))
      releaseGate.countDown()
      val failure = withTimeout(5_000) { imageAdmission.await() }

      assertTrue("post-gate image race must throw IllegalStateException", failure is IllegalStateException)
      activeTurnGate.complete(Unit)
      withTimeout(5_000) { active.host.join() }
      assertEquals(0, c.uiState.value.pendingSteerCount)
      assertTrue(c.drainQueued().isEmpty())
      assertEquals(1, provider.streamCalls.get())
      assertEquals(0, loadedImageCount.get())
      assertEquals(listOf("active"), usersOf(c))
    } finally {
      releaseGate.countDown()
      activeTurnGate.complete(Unit)
      outer.cancel()
      c.close()
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

      awaitTrue { provider.streamCalls.get() == 2 && c.uiState.value.status == ChatStatus.IDLE }
      assertEquals(listOf("first", "follow-up"), usersOf(c))
      assertEquals(0, c.uiState.value.pendingSteerCount)
    } finally {
      resumeCompletion.countDown()
      c.close()
    }
  }

  @Test fun cancelAtCompletionQueueCheckLeavesQueuedIntentDrainable() {
    val completionCheck = CountDownLatch(1)
    val resumeCompletion = CountDownLatch(1)
    val provider = FakeLlmProvider {
      flow { emit(StreamEvent.Done("stop")) }
    }
    val firstRequestEntered = provider.signalRequestEntry("A")
    val c = controller(provider)
    c.beforeCompletionQueueCheckForTest = {
      c.beforeCompletionQueueCheckForTest = null
      completionCheck.countDown()
      check(resumeCompletion.await(30, TimeUnit.SECONDS))
    }

    try {
      val started = runBlocking { c.startOrEnqueue("A") } as TurnStart.Started
      assertTrue("first provider request did not enter", firstRequestEntered.await(5, TimeUnit.SECONDS))
      assertTrue("completion did not reach queue check", completionCheck.await(5, TimeUnit.SECONDS))
      assertEquals(TurnStart.Queued, runBlocking { c.startOrEnqueue("B", opId = 75) })

      c.cancel()
      assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
      resumeCompletion.countDown()
      runBlocking { withTimeout(5_000) { started.host.join() } }

      assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
      assertEquals(listOf("A"), usersOf(c))
      assertEquals(1, provider.streamCalls.get())
      assertEquals(1, c.uiState.value.pendingSteerCount)
      assertEquals(listOf(QueuedIntent(75L, "B")), c.drainQueued())
      assertTrue(c.drainQueued().isEmpty())
      assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
    } finally {
      resumeCompletion.countDown()
      c.close()
    }
  }

  @Test fun enqueueAfterCancelBeforeHostCompletionSignalsRecovery() {
    val cleanupEntered = CountDownLatch(1)
    val releaseCleanup = CountDownLatch(1)
    val provider = FakeLlmProvider {
      flow {
        try {
          awaitCancellation()
        } finally {
          // Keep the cancelled host incomplete so a later admission sees the
          // cancelled job as active and queues behind it.
          cleanupEntered.countDown()
          check(releaseCleanup.await(30, TimeUnit.SECONDS))
        }
      }
    }
    val requestEntered = provider.signalRequestEntry("A")
    val c = controller(provider)
    try {
      val started = runBlocking { c.startOrEnqueue("A") } as TurnStart.Started
      assertTrue("provider request did not enter", requestEntered.await(5, TimeUnit.SECONDS))

      c.cancel()
      assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
      assertFalse(c.uiState.value.queuedRecoveryRequired)
      assertTrue("provider cancellation cleanup did not block", cleanupEntered.await(5, TimeUnit.SECONDS))

      assertEquals(TurnStart.Queued, runBlocking { c.startOrEnqueue("B", opId = 76) })
      assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
      assertEquals(1, c.uiState.value.pendingSteerCount)
      assertTrue("late queued work must signal recovery", c.uiState.value.queuedRecoveryRequired)

      releaseCleanup.countDown()
      runBlocking { withTimeout(5_000) { started.host.join() } }
      assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
      assertEquals(1, c.uiState.value.pendingSteerCount)
      assertTrue(c.uiState.value.queuedRecoveryRequired)
      assertEquals(listOf(QueuedIntent(76L, "B")), c.drainQueued())
      assertTrue(c.drainQueued().isEmpty())
      assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
    } finally {
      releaseCleanup.countDown()
      c.close()
    }
  }

  @Test fun staleFreshGateFailurePreservesPromotionRecoveryCauseAndQueue() = runBlocking {
    fun decisionFor(failure: GateFailure): PolicyDecision = when (failure) {
      GateFailure.DENY -> PolicyDecision(Verdict.DENY, null, System.currentTimeMillis())
      GateFailure.ASK -> PolicyDecision(Verdict.ASK, null, System.currentTimeMillis())
      GateFailure.UNAVAILABLE -> throw IllegalStateException("synthetic failure")
    }

    fun errorFor(failure: GateFailure): String = when (failure) {
      GateFailure.DENY -> "denied by policy"
      GateFailure.ASK -> "approval required"
      GateFailure.UNAVAILABLE -> "policy evaluation failed"
    }

    suspend fun verify(freshFailure: GateFailure, promotionFailure: GateFailure) {
      val staleGateEntered = CompletableDeferred<Unit>()
      val releaseStaleGate = CompletableDeferred<Unit>()
      val activeTurnGate = CompletableDeferred<Unit>()
      val checks = AtomicInteger()
      val policy = object : PolicyStore {
        override fun evaluate(action: String, resource: String) =
          PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
        override suspend fun setRule(rule: PolicyRule) = Unit
        override suspend fun removeRule(pattern: String) = Unit
        override suspend fun listRules(): List<PolicyRule> = emptyList()
        override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision =
          when (checks.incrementAndGet()) {
            1 -> {
              staleGateEntered.complete(Unit)
              releaseStaleGate.await()
              decisionFor(freshFailure)
            }
            2 -> PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
            3 -> decisionFor(promotionFailure)
            else -> PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
          }
      }
      val provider = FakeLlmProvider { request ->
        flow {
          if (baseUserTextOf(request) == "A") activeTurnGate.await()
          emit(StreamEvent.Done("stop"))
        }
      }
      val requestEntered = provider.signalRequestEntry("A")
      val c = controller(provider, policy = policy)
      val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
      try {
        val staleAdmission = outer.async { c.startOrEnqueue("stale", opId = 77) }
        withTimeout(5_000) { staleGateEntered.await() }

        val active = c.startTurn("A")
        assertTrue("active provider request did not enter", requestEntered.await(5, TimeUnit.SECONDS))
        assertEquals(TurnStart.Queued, c.startOrEnqueue("queued", opId = 78))
        activeTurnGate.complete(Unit)
        withTimeout(5_000) { active.join() }
        assertEquals(ChatStatus.ERROR, c.uiState.value.status)
        assertEquals(errorFor(promotionFailure), c.uiState.value.error)
        assertEquals(1, c.uiState.value.pendingSteerCount)
        assertTrue(c.uiState.value.queuedRecoveryRequired)

        // This late fresh failure is a separate failed operation. It must be
        // thrown with its own cause without erasing the promotion recovery.
        releaseStaleGate.complete(Unit)
        val freshError = runCatching { withTimeout(5_000) { staleAdmission.await() } }.exceptionOrNull()
        when (freshFailure) {
          GateFailure.DENY -> assertTrue(freshError is SecurityException)
          GateFailure.ASK -> assertTrue(freshError is ApprovalRequiredException)
          GateFailure.UNAVAILABLE -> assertTrue(freshError is PolicyEvaluationException)
        }
        assertEquals(ChatStatus.ERROR, c.uiState.value.status)
        assertEquals(errorFor(promotionFailure), c.uiState.value.error)
        assertEquals(1, c.uiState.value.pendingSteerCount)
        assertTrue(c.uiState.value.queuedRecoveryRequired)
        assertEquals(listOf("A"), usersOf(c))
        assertEquals(1, provider.streamCalls.get())
        assertEquals(listOf(QueuedIntent(78L, "queued")), c.drainQueued())
      } finally {
        releaseStaleGate.complete(Unit)
        activeTurnGate.complete(Unit)
        outer.cancel()
        c.close()
      }
    }

    verify(GateFailure.DENY, GateFailure.ASK)
    verify(GateFailure.ASK, GateFailure.UNAVAILABLE)
    verify(GateFailure.UNAVAILABLE, GateFailure.DENY)
  }

  @Test fun staleDenyOrAskGateCannotOverwriteStreamingOrCancelledTurn() = runBlocking {
    suspend fun verifyRace(verdict: Verdict, cancelActive: Boolean, expected: ChatStatus) {
      val candidateEntered = CompletableDeferred<Unit>()
      val releaseCandidate = CompletableDeferred<Unit>()
      val checks = AtomicInteger()
      val policy = object : PolicyStore {
        override fun evaluate(action: String, resource: String) =
          PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
        override suspend fun setRule(rule: PolicyRule) = Unit
        override suspend fun removeRule(pattern: String) = Unit
        override suspend fun listRules(): List<PolicyRule> = emptyList()
        override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision =
          if (checks.incrementAndGet() == 1) {
            candidateEntered.complete(Unit)
            releaseCandidate.await()
            PolicyDecision(verdict, null, System.currentTimeMillis())
          } else {
            PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
          }
      }
      val provider = FakeLlmProvider { flow { awaitCancellation() } }
      val c = controller(provider, policy = policy)
      val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
      try {
        val candidate = outer.async { c.startOrEnqueue("candidate", opId = 1) }
        withTimeout(5_000) { candidateEntered.await() }

        val active = c.startTurn("active")
        awaitTrue { provider.streamCalls.get() == 1 }
        assertEquals(ChatStatus.STREAMING, c.uiState.value.status)
        if (cancelActive) {
          c.cancel()
          assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
        }

        releaseCandidate.complete(Unit)
        val failure = runCatching { withTimeout(5_000) { candidate.await() } }.exceptionOrNull()
        if (verdict == Verdict.DENY) assertTrue(failure is SecurityException)
        else assertTrue(failure is ApprovalRequiredException)
        assertEquals("stale gate result must preserve the concurrent state", expected, c.uiState.value.status)
        assertEquals("rejected admission has no provider call", 1, provider.streamCalls.get())
        active.cancel()
      } finally {
        releaseCandidate.complete(Unit)
        outer.cancel()
        c.close()
      }
    }

    verifyRace(Verdict.DENY, cancelActive = false, expected = ChatStatus.STREAMING)
    verifyRace(Verdict.ASK, cancelActive = true, expected = ChatStatus.CANCELLED)
  }

  // N1 handoff: the follow-up gate runs BEFORE dequeue, so a deny at
  // promotion leaves the queued text in the FIFO (still reclaimable via
  // drainQueued) instead of dropping it, and the denied text is never
  // appended or started.
  @Test fun denyAtPromotionKeepsQueueReclaimable() {
    val gate = CompletableDeferred<Unit>()
    val provider = FakeLlmProvider { input ->
      flow {
        if (baseUserTextOf(input) == "first") {
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
      val verdict = runBlocking { c.startOrEnqueue("second", opId = 73) }
      assertTrue(verdict is TurnStart.Queued)
      gate.complete(Unit)
      runBlocking { withTimeout(5000) { first.join() } }
      awaitTrue { c.uiState.value.status == ChatStatus.ERROR }
      assertTrue(c.uiState.value.queuedRecoveryRequired)
      assertEquals(listOf("first"), usersOf(c))
      assertEquals(1, provider.streamCalls.get())

      // Idle admission atomically receives the denied FIFO instead of
      // overtaking it or pretending the new text was accepted.
      val recovery = runBlocking { c.startOrEnqueue("third", opId = 93) }
      assertEquals(
        TurnStart.NeedsRecovery(listOf(QueuedIntent(73L, "second"))),
        recovery,
      )
      assertEquals(0, c.uiState.value.pendingSteerCount)
      assertFalse(c.uiState.value.queuedRecoveryRequired)
      assertEquals(1, provider.streamCalls.get())
      assertEquals(listOf("first"), usersOf(c))

      val retried = runBlocking { c.startOrEnqueue("third", opId = 93) } as TurnStart.Started
      runBlocking { withTimeout(5000) { retried.host.join() } }
      assertFalse(c.uiState.value.queuedRecoveryRequired)
      assertEquals(listOf("first", "third"), usersOf(c))
      assertEquals(2, provider.streamCalls.get())
    } finally {
      outer.cancel()
    }
  }

  @Test fun cancelAfterPromotionDenyKeepsCancelledStateAndQueuedIntent() {
    val firstTurnGate = CompletableDeferred<Unit>()
    val denyReached = CountDownLatch(1)
    val releaseDeny = CountDownLatch(1)
    val provider = FakeLlmProvider { input ->
      flow {
        if (baseUserTextOf(input) == "A") firstTurnGate.await()
        emit(StreamEvent.Done("stop"))
      }
    }
    val firstRequestEntered = provider.signalRequestEntry("A")
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
    c.afterFollowUpDenyForTest = {
      // The DENY result is known, but settlement has not acquired the lock.
      denyReached.countDown()
      check(releaseDeny.await(30, TimeUnit.SECONDS))
    }
    try {
      val started = runBlocking { c.startOrEnqueue("A") } as TurnStart.Started
      assertTrue("first provider request did not enter", firstRequestEntered.await(5, TimeUnit.SECONDS))
      assertEquals(TurnStart.Queued, runBlocking { c.startOrEnqueue("B", opId = 74) })
      firstTurnGate.complete(Unit)
      assertTrue("promotion DENY did not reach barrier", denyReached.await(5, TimeUnit.SECONDS))

      c.cancel()
      assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
      releaseDeny.countDown()
      runBlocking { withTimeout(5_000) { started.host.join() } }

      assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
      assertEquals(listOf("A"), usersOf(c))
      assertEquals(1, provider.streamCalls.get())
      assertEquals(1, c.uiState.value.pendingSteerCount)
      assertEquals(listOf(QueuedIntent(74L, "B")), c.drainQueued())
      assertTrue(c.drainQueued().isEmpty())
      assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
    } finally {
      releaseDeny.countDown()
      c.close()
    }
  }

  @Test fun promotionGateFailureKeepsQueueForExplicitRecovery() {
    val firstTurnGate = CompletableDeferred<Unit>()
    val evaluations = AtomicInteger(0)
    val policy = object : PolicyStore {
      override fun evaluate(action: String, resource: String) =
        PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
      override suspend fun setRule(rule: PolicyRule) = Unit
      override suspend fun removeRule(pattern: String) = Unit
      override suspend fun listRules(): List<PolicyRule> = emptyList()
      override suspend fun evaluateFresh(action: String, resource: String) =
        when (evaluations.incrementAndGet()) {
          1 -> PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
          else -> throw IllegalStateException("synthetic policy storage failure")
        }
    }
    val provider = FakeLlmProvider { request ->
      flow {
        if (baseUserTextOf(request) == "first") firstTurnGate.await()
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider, policy = policy)
    val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
    try {
      val first = outer.async { c.send("first") }
      awaitTrue { provider.streamCalls.get() == 1 }
      assertEquals(TurnStart.Queued, runBlocking { c.startOrEnqueue("queued", opId = 73) })

      firstTurnGate.complete(Unit)
      runBlocking { withTimeout(5000) { first.join() } }

      assertEquals(ChatStatus.ERROR, c.uiState.value.status)
      assertEquals("policy evaluation failed", c.uiState.value.error)
      assertEquals(1, c.uiState.value.pendingSteerCount)
      assertTrue(c.uiState.value.queuedRecoveryRequired)
      assertEquals(2, evaluations.get())
      assertEquals(1, provider.streamCalls.get())
      assertEquals(listOf("first"), usersOf(c))

      // The legacy direct API cannot bypass or consume the retained FIFO.
      val beforeDirectStart = c.uiState.value
      val directFailure = runCatching { runBlocking { c.startTurn("direct candidate") } }.exceptionOrNull()
      assertTrue(directFailure is RecoveryRequiredException)
      assertEquals(beforeDirectStart, c.uiState.value)
      assertEquals(listOf("first"), usersOf(c))
      assertEquals(1, provider.streamCalls.get())

      // Legacy steering keeps its synchronous recovery contract but also
      // appends the rejected text behind the existing recovery FIFO.
      val steerFailure = runCatching { c.steer("legacy") }.exceptionOrNull()
      assertTrue(steerFailure is RecoveryRequiredException)
      assertEquals(2, c.uiState.value.pendingSteerCount)
      assertTrue(c.uiState.value.queuedRecoveryRequired)

      // The explicit admission API still transfers the FIFO to the caller;
      // it neither auto-runs the queued item nor accepts the new candidate.
      assertEquals(
        TurnStart.NeedsRecovery(listOf(QueuedIntent(73L, "queued"), QueuedIntent(null, "legacy"))),
        runBlocking { c.startOrEnqueue("next", opId = 93) },
      )
      assertEquals(0, c.uiState.value.pendingSteerCount)
      assertFalse(c.uiState.value.queuedRecoveryRequired)
      assertEquals(1, provider.streamCalls.get())
      assertEquals(listOf("first"), usersOf(c))
    } finally {
      outer.cancel()
      c.close()
    }
  }

  @Test fun recoveryDiscoveredAtSecondAdmissionLockDoesNotAcceptCandidate() {
    val candidateGatePassed = CountDownLatch(1)
    val releaseCandidate = CountDownLatch(1)
    val firstTurnGate = CompletableDeferred<Unit>()
    val evaluations = AtomicInteger(0)
    val policy = object : PolicyStore {
      override fun evaluate(action: String, resource: String) =
        PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
      override suspend fun setRule(rule: PolicyRule) = Unit
      override suspend fun removeRule(pattern: String) = Unit
      override suspend fun listRules(): List<PolicyRule> = emptyList()
      override suspend fun evaluateFresh(action: String, resource: String) =
        when (evaluations.incrementAndGet()) {
          3 -> PolicyDecision(Verdict.DENY, null, System.currentTimeMillis())
          else -> PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
        }
    }
    val provider = FakeLlmProvider { request ->
      flow {
        if (baseUserTextOf(request) == "first") firstTurnGate.await()
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider, policy = policy)
    c.afterStartOrEnqueueGateForTest = {
      c.afterStartOrEnqueueGateForTest = null
      candidateGatePassed.countDown()
      check(releaseCandidate.await(30, TimeUnit.SECONDS))
    }
    val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
    try {
      val candidate = outer.async { c.startOrEnqueue("candidate", opId = 93) }
      assertTrue("candidate did not pass its fresh gate", candidateGatePassed.await(5, TimeUnit.SECONDS))

      val first = outer.async { c.send("first") }
      awaitTrue { provider.streamCalls.get() == 1 }
      assertEquals(TurnStart.Queued, runBlocking { c.startOrEnqueue("second", opId = 73) })
      firstTurnGate.complete(Unit)
      runBlocking { withTimeout(5000) { first.join() } }
      assertTrue(c.uiState.value.queuedRecoveryRequired)
      assertEquals(listOf("first"), usersOf(c))
      assertEquals(1, provider.streamCalls.get())

      releaseCandidate.countDown()
      assertEquals(
        TurnStart.NeedsRecovery(listOf(QueuedIntent(73L, "second"))),
        runBlocking { withTimeout(5000) { candidate.await() } },
      )
      // The second-lock recovery branch neither appends candidate nor calls
      // the provider. The caller may retry only after taking ownership of the
      // returned FIFO, and that retry evaluates policy afresh.
      assertEquals(listOf("first"), usersOf(c))
      assertEquals(1, provider.streamCalls.get())
      assertEquals(3, evaluations.get())

      val retry = runBlocking { c.startOrEnqueue("candidate", opId = 93) } as TurnStart.Started
      runBlocking { withTimeout(5000) { retry.host.join() } }
      assertEquals(4, evaluations.get())
      assertEquals(listOf("first", "candidate"), usersOf(c))
      assertEquals(2, provider.streamCalls.get())
    } finally {
      releaseCandidate.countDown()
      outer.cancel()
      c.close()
    }
  }

  @Test fun directStartRejectsRecoveryDiscoveredAtItsSecondAdmissionLock() {
    val candidateGatePassed = CountDownLatch(1)
    val releaseCandidate = CountDownLatch(1)
    val firstTurnGate = CompletableDeferred<Unit>()
    val evaluations = AtomicInteger(0)
    val policy = object : PolicyStore {
      override fun evaluate(action: String, resource: String) =
        PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
      override suspend fun setRule(rule: PolicyRule) = Unit
      override suspend fun removeRule(pattern: String) = Unit
      override suspend fun listRules(): List<PolicyRule> = emptyList()
      override suspend fun evaluateFresh(action: String, resource: String) =
        if (evaluations.incrementAndGet() == 3) {
          PolicyDecision(Verdict.DENY, null, System.currentTimeMillis())
        } else {
          PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
        }
    }
    val provider = FakeLlmProvider { request ->
      flow {
        if (baseUserTextOf(request) == "first") firstTurnGate.await()
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider, policy = policy)
    c.afterStartTurnGateForTest = {
      c.afterStartTurnGateForTest = null
      candidateGatePassed.countDown()
      check(releaseCandidate.await(30, TimeUnit.SECONDS))
    }
    val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
    try {
      val candidate = outer.async { c.startTurn("candidate") }
      assertTrue("candidate did not pass its fresh gate", candidateGatePassed.await(5, TimeUnit.SECONDS))

      val first = outer.async { c.send("first") }
      awaitTrue { provider.streamCalls.get() == 1 }
      assertEquals(TurnStart.Queued, runBlocking { c.startOrEnqueue("queued", opId = 79) })
      firstTurnGate.complete(Unit)
      runBlocking { withTimeout(5_000) { first.join() } }
      assertEquals(ChatStatus.ERROR, c.uiState.value.status)
      assertEquals(1, c.uiState.value.pendingSteerCount)
      assertTrue(c.uiState.value.queuedRecoveryRequired)

      releaseCandidate.countDown()
      val failure = runCatching { runBlocking { withTimeout(5_000) { candidate.await() } } }.exceptionOrNull()
      assertTrue("second lock rejects without consuming recovery", failure is RecoveryRequiredException)
      assertEquals(listOf("first"), usersOf(c))
      assertEquals(1, provider.streamCalls.get())
      assertEquals(1, c.uiState.value.pendingSteerCount)
      assertTrue(c.uiState.value.queuedRecoveryRequired)
      assertEquals(listOf(QueuedIntent(79L, "queued")), c.drainQueued())
    } finally {
      releaseCandidate.countDown()
      outer.cancel()
      c.close()
    }
  }

  @Test fun completedTurnInvalidatesEarlierDenyAndAskGateFailures() = runBlocking {
    suspend fun verifyFailure(verdict: Verdict) {
      val candidateGateEntered = CompletableDeferred<Unit>()
      val releaseCandidateGate = CompletableDeferred<Unit>()
      val evaluations = AtomicInteger()
      val policy = object : PolicyStore {
        override fun evaluate(action: String, resource: String) =
          PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
        override suspend fun setRule(rule: PolicyRule) = Unit
        override suspend fun removeRule(pattern: String) = Unit
        override suspend fun listRules(): List<PolicyRule> = emptyList()
        override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision =
          when (evaluations.incrementAndGet()) {
            1 -> {
              candidateGateEntered.complete(Unit)
              releaseCandidateGate.await()
              PolicyDecision(verdict, null, System.currentTimeMillis())
            }
            else -> PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
          }
      }
      val provider = FakeLlmProvider { flow { emit(StreamEvent.Done("stop")) } }
      val c = controller(provider, policy = policy)
      val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
      try {
        val candidate = outer.async { c.startOrEnqueue("candidate", opId = 80) }
        withTimeout(5_000) { candidateGateEntered.await() }

        val completed = c.startTurn("successful turn")
        withTimeout(5_000) { completed.join() }
        assertEquals(ChatStatus.IDLE, c.uiState.value.status)
        assertNull(c.uiState.value.error)
        assertEquals(listOf("successful turn"), usersOf(c))

        releaseCandidateGate.complete(Unit)
        val failure = runCatching { withTimeout(5_000) { candidate.await() } }.exceptionOrNull()
        if (verdict == Verdict.DENY) assertTrue(failure is SecurityException)
        else assertTrue(failure is ApprovalRequiredException)
        assertEquals("stale gate must preserve the completed turn's IDLE result", ChatStatus.IDLE, c.uiState.value.status)
        assertNull(c.uiState.value.error)
        assertEquals(listOf("successful turn"), usersOf(c))
        assertEquals(1, provider.streamCalls.get())
      } finally {
        releaseCandidateGate.complete(Unit)
        outer.cancel()
        c.close()
      }
    }

    verifyFailure(Verdict.DENY)
    verifyFailure(Verdict.ASK)
  }

  @Test fun admissionSnapshotInvalidatesGateFailuresBeforeGateStarts() = runBlocking {
    suspend fun verifyFailure(failure: GateFailure, directStart: Boolean) {
      val candidateAtGateSeam = CountDownLatch(1)
      val releaseCandidate = CountDownLatch(1)
      val evaluations = AtomicInteger()
      val policy = object : PolicyStore {
        override fun evaluate(action: String, resource: String) =
          PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
        override suspend fun setRule(rule: PolicyRule) = Unit
        override suspend fun removeRule(pattern: String) = Unit
        override suspend fun listRules(): List<PolicyRule> = emptyList()
        override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision =
          when (evaluations.incrementAndGet()) {
            1 -> PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
            else -> when (failure) {
              GateFailure.DENY -> PolicyDecision(Verdict.DENY, null, System.currentTimeMillis())
              GateFailure.ASK -> PolicyDecision(Verdict.ASK, null, System.currentTimeMillis())
              GateFailure.UNAVAILABLE -> throw IllegalStateException("policy unavailable")
            }
          }
      }
      val provider = FakeLlmProvider { flow { emit(StreamEvent.Done("stop")) } }
      val c = controller(provider, policy = policy)
      c.beforeAdmissionGateForTest = {
        c.beforeAdmissionGateForTest = null
        candidateAtGateSeam.countDown()
        check(releaseCandidate.await(30, TimeUnit.SECONDS))
      }
      val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
      try {
        val candidate = outer.async {
          if (directStart) c.startTurn("candidate") else c.startOrEnqueue("candidate", opId = 82)
        }
        assertTrue("candidate did not pause after its initial idle snapshot", candidateAtGateSeam.await(5, TimeUnit.SECONDS))

        val successfulTurn = c.startTurn("successful turn")
        withTimeout(5_000) { successfulTurn.join() }
        assertEquals(ChatStatus.IDLE, c.uiState.value.status)
        assertNull(c.uiState.value.error)
        assertEquals(listOf("successful turn"), usersOf(c))

        releaseCandidate.countDown()
        val gateFailure = runCatching { withTimeout(5_000) { candidate.await() } }.exceptionOrNull()
        when (failure) {
          GateFailure.DENY -> assertTrue(gateFailure is SecurityException)
          GateFailure.ASK -> assertTrue(gateFailure is ApprovalRequiredException)
          GateFailure.UNAVAILABLE -> assertTrue(gateFailure is PolicyEvaluationException)
        }
        assertEquals("stale gate must preserve the completed turn's IDLE result", ChatStatus.IDLE, c.uiState.value.status)
        assertNull(c.uiState.value.error)
        assertEquals(listOf("successful turn"), usersOf(c))
        assertEquals(1, provider.streamCalls.get())
      } finally {
        releaseCandidate.countDown()
        outer.cancel()
        c.close()
      }
    }

    for (directStart in listOf(false, true)) {
      verifyFailure(GateFailure.DENY, directStart)
      verifyFailure(GateFailure.ASK, directStart)
      verifyFailure(GateFailure.UNAVAILABLE, directStart)
    }
  }

  @Test fun teardownAfterFollowUpGateDrainsIntentBeforeAtomicHandoff() {
    val firstTurnGate = CompletableDeferred<Unit>()
    val gatePassed = CountDownLatch(1)
    val releaseHandoff = CountDownLatch(1)
    val provider = FakeLlmProvider { input ->
      flow {
        if (baseUserTextOf(input) == "first") {
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
      awaitTrue { provider.streamCalls.get() == 1 }
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

  @Test fun cancellationAfterFollowUpGateKeepsIntentDrainableWithoutPromotion() {
    val firstTurnGate = CompletableDeferred<Unit>()
    val gatePassed = CountDownLatch(1)
    val releaseHandoff = CountDownLatch(1)
    val provider = FakeLlmProvider { input ->
      flow {
        if (baseUserTextOf(input) == "first") firstTurnGate.await()
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = TurnController(
      provider = provider,
      policy = AllowPolicy(),
      dispatcher = Dispatchers.Default,
    )
    c.afterFollowUpGateForTest = {
      gatePassed.countDown()
      check(releaseHandoff.await(30, TimeUnit.SECONDS))
    }
    val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
    try {
      val first = outer.async { c.send("first") }
      awaitTrue { provider.streamCalls.get() == 1 }
      assertEquals(TurnStart.Queued, runBlocking { c.startOrEnqueue("follow-up", opId = 73) })
      firstTurnGate.complete(Unit)
      assertTrue("follow-up gate did not pass", gatePassed.await(5, TimeUnit.SECONDS))

      // Cancellation wins while the ALLOWed intent is still in the FIFO and
      // the hosted job is paused immediately before the promotion lock.
      c.cancel()
      assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
      releaseHandoff.countDown()
      runBlocking { withTimeout(5_000) { first.join() } }

      assertEquals(listOf("first"), usersOf(c))
      assertEquals("cancelled follow-up is retained for recovery", 1, c.uiState.value.pendingSteerCount)
      assertEquals(listOf(QueuedIntent(73L, "follow-up")), c.drainQueued())
      assertEquals(listOf("first"), usersOf(c))
      assertEquals("cancellation must not launch the follow-up provider request", 1, provider.streamCalls.get())
      assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
    } finally {
      releaseHandoff.countDown()
      outer.cancel()
      c.close()
    }
  }

  @Test fun cancelAfterSuccessfulPromotionCancelsFollowUpExactlyOnce() {
    val firstTurnGate = CompletableDeferred<Unit>()
    val gatePassed = CountDownLatch(1)
    val releaseHandoff = CountDownLatch(1)
    val provider = FakeLlmProvider { input ->
      flow {
        if (baseUserTextOf(input) == "A") {
          firstTurnGate.await()
        } else {
          awaitCancellation()
        }
        emit(StreamEvent.Done("stop"))
      }
    }
    val firstRequestEntered = provider.signalRequestEntry("A")
    val followUpRequestEntered = provider.signalRequestEntry("B")
    val c = TurnController(
      provider = provider,
      policy = AllowPolicy(),
      dispatcher = Dispatchers.Default,
    )
    c.afterFollowUpGateForTest = {
      gatePassed.countDown()
      check(releaseHandoff.await(30, TimeUnit.SECONDS))
    }
    try {
      val started = runBlocking { c.startOrEnqueue("A") } as TurnStart.Started
      assertTrue("first provider request did not enter", firstRequestEntered.await(5, TimeUnit.SECONDS))
      assertEquals(TurnStart.Queued, runBlocking { c.startOrEnqueue("B", opId = 73) })
      firstTurnGate.complete(Unit)
      assertTrue("follow-up gate did not pass", gatePassed.await(5, TimeUnit.SECONDS))

      // Promotion wins the handoff race; the request-entry signal proves B
      // owns the FIFO item before cancellation targets the new follow-up host.
      releaseHandoff.countDown()
      assertTrue("follow-up provider request did not enter", followUpRequestEntered.await(5, TimeUnit.SECONDS))
      runBlocking { withTimeout(5_000) { started.host.join() } }

      assertEquals(listOf("A", "B"), usersOf(c))
      assertEquals(1, usersOf(c).count { it == "B" })
      assertEquals(2, provider.streamCalls.get())
      assertTrue(c.drainQueued().isEmpty())
      assertEquals(0, c.uiState.value.pendingSteerCount)

      c.cancel()
      assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
      assertTrue(
        "follow-up provider did not observe cancellation",
        provider.cancellationObserved.await(5, TimeUnit.SECONDS),
      )
      assertEquals(1, provider.cancelCalls.get())
      assertEquals(2, provider.streamCalls.get())
      assertEquals(listOf("A", "B"), usersOf(c))
      assertEquals(1, usersOf(c).count { it == "B" })
      assertTrue(c.drainQueued().isEmpty())
      assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
    } finally {
      releaseHandoff.countDown()
      c.close()
    }
  }
}
