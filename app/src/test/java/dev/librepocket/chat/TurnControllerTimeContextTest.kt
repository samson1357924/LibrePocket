package dev.librepocket.chat

import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatImage
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol as ProviderProto
import dev.librepocket.provider.StreamEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

private class TimeFakeProvider(
  var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
  override val protocol: ProviderProto = ProviderProto.CHAT_COMPLETIONS
  val seenRequests: MutableList<ChatRequest> = Collections.synchronizedList(mutableListOf())
  var streamCalls = 0

  override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
    streamCalls++
    seenRequests.add(request)
    emitAll(handler(request))
  }

  override suspend fun listModels(): List<String> = emptyList()
}

private class TimeRecordingSink : TranscriptSink {
  val started: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

  override suspend fun onTurnStarted(runId: String, text: String) { started.add(runId to text) }
  override suspend fun onTurnSucceeded(runId: String, text: String) = Unit
  override suspend fun onTurnFailed(runId: String, error: String) = Unit
  override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
  override suspend fun onTurnCancelled(runId: String, partialText: String) = Unit
  override suspend fun onSteerQueued(text: String) = Unit
  override suspend fun onToolDone(runId: String, toolIndex: Int, id: String, name: String, argumentsJson: String) = Unit
  override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) = Unit
}

private class TimeAllowPolicy : PolicyStore {
  override fun evaluate(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
  override suspend fun setRule(rule: PolicyRule) = Unit
  override suspend fun removeRule(pattern: String) = Unit
  override suspend fun listRules(): List<PolicyRule> = emptyList()
  override suspend fun evaluateFresh(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

/** Clock whose instant always throws; mirrors RuntimeTimeContextTest's FailingClock. */
private class TurnFailingClock : Clock() {
  private val zone = ZoneId.of("America/New_York")
  override fun getZone(): ZoneId = zone
  override fun withZone(zone: ZoneId): Clock = this
  override fun instant(): Instant = throw IllegalStateException("clock boom")
}

/**
 * Phase 2: ephemeral runtime time-context wiring in [TurnController].
 *
 * The time block lives only in the outgoing [ChatRequest] (copy of the last
 * user message); UI state and transcript keep the raw user text.
 */
class TurnControllerTimeContextTest {

  private val fixedClock: Clock = Clock.fixed(
    Instant.parse("2026-01-15T12:00:00Z"),
    ZoneId.of("America/New_York"),
  )
  private val fixedSessionStart: Instant = Instant.parse("2026-01-14T12:00:00Z")

  private fun controller(
    provider: TimeFakeProvider,
    transcript: TranscriptSink = NoOpTranscriptSink(),
    clock: Clock = fixedClock,
    userTimezone: String? = "America/New_York",
    sessionStart: Instant? = fixedSessionStart,
    delays: MutableList<Long> = mutableListOf(),
    retry: TurnRetryConfig = TurnRetryConfig(),
    imageLoader: (List<ChatImageRef>) -> List<ChatImage> = { emptyList() },
    systemZone: () -> ZoneId = ZoneId::systemDefault,
  ): TurnController {
    var n = 0
    return TurnController(
      provider = provider,
      policy = TimeAllowPolicy(),
      transcript = transcript,
      retryConfig = retry,
      dispatcher = Dispatchers.Default,
      sleeper = { delays.add(it) },
      newId = { "tc-${n++}" },
      imageLoader = imageLoader,
      clock = clock,
      userTimezone = userTimezone,
      sessionStart = sessionStart,
      systemZone = systemZone,
    )
  }

  private fun awaitTrue(timeoutMs: Long = 8000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!cond()) {
      if (System.currentTimeMillis() > end) fail("timed out waiting for condition")
      Thread.sleep(10)
    }
  }

  private fun ok(handler: (ChatRequest) -> Flow<StreamEvent> = {
    flow {
      emit(StreamEvent.TextDelta(0, 0, "done"))
      emit(StreamEvent.Done("stop"))
    }
  }) = TimeFakeProvider(handler)

  private fun lastUserText(req: ChatRequest): String? =
    req.messages.lastOrNull { it.role == "user" }?.text

  @Test fun requestCarriesTimeBlockWhileUiStateStaysClean() {
    val provider = ok()
    val c = controller(provider)
    runBlocking { c.send("hi") }

    val sent = lastUserText(provider.seenRequests.single())
    assertTrue(sent!!.startsWith("hi\n\nRuntime time context:"))
    assertTrue(sent.contains("2026-01-15"))
    assertTrue(sent.contains("America/New_York"))
    assertTrue(sent.contains("UTC-05:00"))
    assertTrue(sent.contains("Session started:"))
    assertTrue(sent.contains("2026-01-14"))

    // Copy-only evidence: UI keeps the raw user text, no time residue.
    val uiUser = c.uiState.value.messages.last { it.role == "user" }.text
    assertEquals("hi", uiUser)
    assertFalse(uiUser.contains("Runtime time context"))
    assertEquals(ChatStatus.IDLE, c.uiState.value.status)
  }

  @Test fun transcriptSeesNoTimeResidue() {
    val provider = ok()
    val sink = TimeRecordingSink()
    val c = controller(provider, transcript = sink)
    runBlocking { c.send("hi") }

    awaitTrue { sink.started.isNotEmpty() }
    assertTrue(sink.started.isNotEmpty())
    for ((_, text) in sink.started) {
      assertEquals("hi", text)
      assertFalse(text.contains("Runtime time context"))
    }
  }

  @Test fun invalidTimezoneFallsBackWithoutCrashing() {
    val provider = ok()
    val c = controller(provider, userTimezone = "Mars/Olympus_Mons")
    runBlocking { c.send("hello") }

    assertEquals(ChatStatus.IDLE, c.uiState.value.status)
    val sent = lastUserText(provider.seenRequests.single())
    assertTrue(sent!!.startsWith("hello\n\nRuntime time context:"))
    // Fallback renders with the system zone instead of crashing or omitting.
    assertFalse(sent.contains("Mars/Olympus_Mons"))
    assertEquals("hello", c.uiState.value.messages.last { it.role == "user" }.text)
  }

  @Test fun imagesAttachAlongsideTimeBlockWithoutCrashing() {
    val provider = ok()
    val loader: (List<ChatImageRef>) -> List<ChatImage> = { refs ->
      refs.map { ChatImage(bytes = byteArrayOf(1, 2, 3), mimeType = it.mimeType) }
    }
    val c = controller(provider, imageLoader = loader)
    runBlocking { c.send("look", images = listOf(ChatImageRef("p", "image/png"))) }

    assertEquals(ChatStatus.IDLE, c.uiState.value.status)
    val req = provider.seenRequests.single()
    val lastUser = req.messages.last { it.role == "user" }
    assertEquals(1, lastUser.images.size)
    assertTrue(lastUser.text.startsWith("look\n\nRuntime time context:"))
    assertEquals("look", c.uiState.value.messages.last { it.role == "user" }.text)
  }

  @Test fun buildRequestWithNoUserMessageInjectsNothingAndDoesNotCrash() {
    val method = TurnController::class.java.getDeclaredMethod("buildRequest", List::class.java)
    method.isAccessible = true

    // No history, image loader empty: nothing to attach to, request stays empty.
    val emptyImages = controller(TimeFakeProvider(), imageLoader = { emptyList() })
    val emptyReq = method.invoke(emptyImages, emptyList<ChatImageRef>()) as ChatRequest
    assertTrue(emptyReq.messages.none { it.text.contains("Runtime time context") })

    // No history but an image arrives: the pre-existing image fallback owns the
    // single user message; the clock never fabricates an extra message.
    val withImage = controller(
      TimeFakeProvider(),
      imageLoader = { refs ->
        refs.map { ChatImage(bytes = byteArrayOf(9), mimeType = it.mimeType) }
      },
    )
    val imageReq = method.invoke(withImage, listOf(ChatImageRef("p", "image/png"))) as ChatRequest
    assertEquals(1, imageReq.messages.count { it.role == "user" })
  }

  @Test fun retrySemanticsUnaffectedByTimeContext() {
    val calls = AtomicInteger(0)
    val delays = mutableListOf<Long>()
    val provider = TimeFakeProvider {
      flow {
        if (calls.incrementAndGet() == 1) {
          emit(StreamEvent.Failed("boom", retryable = true))
        } else {
          emit(StreamEvent.TextDelta(0, 0, "recovered"))
          emit(StreamEvent.Done("stop"))
        }
      }
    }
    val c = controller(
      provider,
      delays = delays,
      retry = TurnRetryConfig(maxRetries = 1, retryDelaysMs = listOf(0L)),
    )
    runBlocking { c.send("hi") }

    assertEquals(2, calls.get())
    assertEquals(listOf(0L), delays)
    assertEquals(ChatStatus.IDLE, c.uiState.value.status)
    assertEquals("recovered", c.uiState.value.messages.last { it.role == "assistant" }.text)
    // Every attempt carries the ephemeral block; UI still keeps raw text.
    assertEquals(2, provider.seenRequests.size)
    for (req in provider.seenRequests) {
      assertTrue(lastUserText(req)!!.contains("Runtime time context:"))
    }
    assertEquals("hi", c.uiState.value.messages.last { it.role == "user" }.text)
  }

  @Test fun failingClockStillSendsWithUnknownMarkerAndIdles() {
    val provider = ok()
    val c = controller(provider, clock = TurnFailingClock())
    runBlocking { c.send("hi") }

    assertEquals(ChatStatus.IDLE, c.uiState.value.status)
    assertNull(c.uiState.value.error)
    val sent = lastUserText(provider.seenRequests.single())
    assertTrue(sent!!.startsWith("hi\n\nRuntime time context:"))
    assertTrue(sent.contains("(time unknown)"))
    // Copy-only evidence: UI keeps the raw user text, no time residue.
    assertEquals("hi", c.uiState.value.messages.last { it.role == "user" }.text)
  }

  @Test fun steerFollowUpsEachCarryFreshTimeBlock() {
    val gate = CompletableDeferred<Unit>()
    val provider = TimeFakeProvider { input ->
      flow {
        // Phase 2 appends an ephemeral time block; branch on the raw text.
        val rawUserText = lastUserText(input)?.substringBefore("\n\n")
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
      awaitTrue { c.uiState.value.messages.any { it.role == "assistant" && it.text == "A" } }
      c.steer("follow-1")
      c.steer("follow-2")
      gate.complete(Unit)
      runBlocking { withTimeout(5000) { first.join() } }
      awaitTrue { c.uiState.value.status == ChatStatus.IDLE && provider.seenRequests.size == 3 }
      // Every follow-up request carries its own fresh time block, same as retries.
      assertEquals(3, provider.seenRequests.size)
      for (req in provider.seenRequests) {
        assertTrue(lastUserText(req)!!.contains("Runtime time context:"))
      }
      assertTrue(
        lastUserText(provider.seenRequests[1])!!.startsWith("follow-1\n\nRuntime time context:"),
      )
      assertTrue(
        lastUserText(provider.seenRequests[2])!!.startsWith("follow-2\n\nRuntime time context:"),
      )
      assertEquals(ChatStatus.IDLE, c.uiState.value.status)
      assertNull(c.uiState.value.error)
    } finally {
      outer.cancel()
    }
  }

  @Test fun consecutiveTurnsPickUpSystemZoneChange() {
    var current = ZoneId.of("Asia/Taipei")
    val provider = ok()
    val c = controller(
      provider,
      // Clock zone is deliberately UTC: blank must follow the supplier, never clock.zone.
      clock = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneId.of("UTC")),
      userTimezone = null,
      systemZone = { current },
    )
    runBlocking { c.send("one") }
    current = ZoneId.of("America/New_York")
    runBlocking { c.send("two") }

    assertEquals(2, provider.seenRequests.size)
    val first = lastUserText(provider.seenRequests[0])!!
    val second = lastUserText(provider.seenRequests[1])!!
    assertTrue(first.startsWith("one\n\nRuntime time context:"))
    assertTrue(first.contains("- User timezone: Asia/Taipei"))
    assertTrue(first.contains("(UTC+08:00)"))
    assertTrue(second.startsWith("two\n\nRuntime time context:"))
    assertTrue(second.contains("- User timezone: America/New_York"))
    // 2026-01-15 is EST (-05:00).
    assertTrue(second.contains("(UTC-05:00)"))
    // UI keeps the raw user text across both turns, no time residue.
    val uiUsers = c.uiState.value.messages.filter { it.role == "user" }.map { it.text }
    assertEquals(listOf("one", "two"), uiUsers)
    assertFalse(uiUsers.any { it.contains("Runtime time context") })
    assertEquals(ChatStatus.IDLE, c.uiState.value.status)
  }

  @Test fun retryPicksUpZoneFlipBetweenAttempts() {
    var current = ZoneId.of("Asia/Taipei")
    val calls = AtomicInteger(0)
    val provider = TimeFakeProvider {
      flow {
        if (calls.incrementAndGet() == 1) {
          // Flip before the retry builds its request: the second attempt must use the new zone.
          current = ZoneId.of("America/New_York")
          emit(StreamEvent.Failed("boom", retryable = true))
        } else {
          emit(StreamEvent.TextDelta(0, 0, "recovered"))
          emit(StreamEvent.Done("stop"))
        }
      }
    }
    val sink = TimeRecordingSink()
    val c = controller(
      provider,
      transcript = sink,
      clock = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneId.of("UTC")),
      userTimezone = null,
      sessionStart = null,
      retry = TurnRetryConfig(maxRetries = 1, retryDelaysMs = listOf(0L)),
      systemZone = { current },
    )
    runBlocking { c.send("hi") }

    assertEquals(2, provider.seenRequests.size)
    val first = lastUserText(provider.seenRequests[0])!!
    val second = lastUserText(provider.seenRequests[1])!!
    assertTrue(first.contains("- User timezone: Asia/Taipei"))
    assertTrue(first.contains("(UTC+08:00)"))
    assertTrue(second.contains("- User timezone: America/New_York"))
    // 2026-01-15 is EST (-05:00).
    assertTrue(second.contains("(UTC-05:00)"))
    // Transcript keeps the raw text across the retry, no time residue. One
    // logical turn owns exactly one user row; the retry only adds an attempt
    // record bound to that id (see TurnControllerLedgerTest).
    awaitTrue { sink.started.isNotEmpty() }
    assertEquals(1, sink.started.size)
    for ((_, text) in sink.started) {
      assertEquals("hi", text)
      assertFalse(text.contains("Runtime time context"))
    }
    assertEquals("hi", c.uiState.value.messages.last { it.role == "user" }.text)
    assertEquals(ChatStatus.IDLE, c.uiState.value.status)
  }

  @Test fun steerFollowUpPicksUpZoneFlip() {
    var current = ZoneId.of("Asia/Taipei")
    val gate = CompletableDeferred<Unit>()
    val provider = TimeFakeProvider { input ->
      flow {
        // Phase 2 appends an ephemeral time block; branch on the raw text.
        val rawUserText = lastUserText(input)?.substringBefore("\n\n")
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
    val sink = TimeRecordingSink()
    val c = controller(
      provider,
      transcript = sink,
      clock = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneId.of("UTC")),
      userTimezone = null,
      sessionStart = null,
      systemZone = { current },
    )
    val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
    try {
      val first = outer.async { c.send("first") }
      awaitTrue { c.uiState.value.messages.any { it.role == "assistant" && it.text == "A" } }
      // Flip after the first request is built but before the follow-up builds its own.
      awaitTrue { provider.seenRequests.size == 1 }
      current = ZoneId.of("America/New_York")
      c.steer("follow-1")
      gate.complete(Unit)
      runBlocking { withTimeout(5000) { first.join() } }
      awaitTrue { c.uiState.value.status == ChatStatus.IDLE && provider.seenRequests.size == 2 }
      assertEquals(2, provider.seenRequests.size)
      val firstReq = lastUserText(provider.seenRequests[0])!!
      val followReq = lastUserText(provider.seenRequests[1])!!
      assertTrue(firstReq.startsWith("first\n\nRuntime time context:"))
      assertTrue(firstReq.contains("- User timezone: Asia/Taipei"))
      assertTrue(followReq.startsWith("follow-1\n\nRuntime time context:"))
      assertTrue(followReq.contains("- User timezone: America/New_York"))
      assertTrue(followReq.contains("(UTC-05:00)"))
      // Transcript keeps raw texts, no time residue.
      awaitTrue { sink.started.size >= 2 }
      for ((_, text) in sink.started) {
        assertFalse(text.contains("Runtime time context"))
      }
      assertEquals(
        listOf("first", "follow-1"),
        c.uiState.value.messages.filter { it.role == "user" }.map { it.text },
      )
      assertEquals(ChatStatus.IDLE, c.uiState.value.status)
    } finally {
      outer.cancel()
    }
  }

  @Test fun chatSessionImplPassesThroughSystemZoneSupplier() {
    var current = ZoneId.of("Asia/Taipei")
    val provider = ok()
    var n = 0
    val session = ChatSessionImpl(
      provider = provider,
      policy = TimeAllowPolicy(),
      transcript = NoOpTranscriptSink(),
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "smoke-${n++}" },
      clock = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneId.of("UTC")),
      userTimezone = null,
      sessionStart = null,
      systemZone = { current },
    )
    runBlocking { session.send("one") }
    current = ZoneId.of("America/New_York")
    runBlocking { session.send("two") }
    assertEquals(2, provider.seenRequests.size)
    assertTrue(lastUserText(provider.seenRequests[0])!!.contains("- User timezone: Asia/Taipei"))
    assertTrue(lastUserText(provider.seenRequests[1])!!.contains("- User timezone: America/New_York"))
    assertEquals(
      listOf("one", "two"),
      session.uiState.value.messages.filter { it.role == "user" }.map { it.text },
    )
  }
}
