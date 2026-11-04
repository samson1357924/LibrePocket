package dev.librepocket.chat

import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatMessage
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol as ProviderProto
import dev.librepocket.provider.StreamEvent
import dev.librepocket.router.SafeToolSlice
import dev.librepocket.tool.LspTools
import dev.librepocket.tool.ProjectionContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class BmFakeProvider(
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

private class BmAllowPolicy : PolicyStore {
  override fun evaluate(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
  override suspend fun setRule(rule: PolicyRule) = Unit
  override suspend fun removeRule(pattern: String) = Unit
  override suspend fun listRules(): List<PolicyRule> = emptyList()
  override suspend fun evaluateFresh(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

/**
 * H1 (#16 residual) benchmark skeleton + window reconciliation pins.
 *
 * Pure JVM (all fake data, no network, no device): synthetic floods with a
 * JUnit time limit, so CI gets a reproducible shape without claiming
 * on-device heap/frame numbers. Real heap/frame/latency thresholds stay
 * Target until measured on device (see #16 acceptance).
 *
 * Covers:
 * - hydration char budget reconciliation (`droppedHistoryChars` mirrors
 *   `droppedHistoryCount`; `lastRequestChars` mirrors the shipped request);
 * - attempt/request window reconciliation (`lastRequestTrimmedChars`
 *   mirrors `lastRequestTrimmedCount`; Unbounded default still drops nothing);
 * - #83 per-attempt semantics (retry starts fresh) are pinned by
 *   TurnControllerUsageTextBudgetTest.textBudgetResetsAcrossRetryableRetry;
 *   this file does not re-test the budget itself.
 */
class TurnControllerBudgetBenchmarkTest {

  private fun doneOnce(text: String = "ok"): (ChatRequest) -> Flow<StreamEvent> = {
    flow {
      emit(StreamEvent.TextDelta(0, 0, text))
      emit(StreamEvent.Done("stop"))
    }
  }

  private fun controller(
    provider: BmFakeProvider,
    history: List<ChatMessage> = emptyList(),
    cap: HistoryWindowCap = HistoryWindowCap.Unbounded,
    clock: Clock = Clock.systemDefaultZone(),
    systemZone: () -> ZoneId = ZoneId::systemDefault,
    toolSlice: SafeToolSlice? = null,
  ): TurnController {
    var n = 0
    return TurnController(
      provider = provider,
      policy = BmAllowPolicy(),
      // Determinism note: dispatcher stays Dispatchers.Default, but every
      // assertion below pins exact counts/copies — never wall time — so
      // scheduling is not a flakiness source.
      dispatcher = Dispatchers.Default,
      sleeper = {},
      newId = { "bm-${n++}" },
      clock = clock,
      systemZone = systemZone,
      initialHistory = history,
      historyCap = cap,
      toolSlice = toolSlice,
    )
  }

  /** Minimal slice backend for the continuation test (mirrors the ToolSliceTest CountingBackend). */
  private class BmSliceBackend : LspTools.Backend {
    val invocations = AtomicInteger(0)
    override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> {
      invocations.incrementAndGet()
      return listOf(LspTools.Symbol("$query:example", "function", "app/src/main/Example.kt", 1))
    }
    override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
  }

  /** Slice assembly mirrors TurnControllerToolSliceTest.loop(): lsp switch on, ALLOW-all policy. */
  private fun sliceLoop(backend: LspTools.Backend, clock: Clock): SafeToolSlice =
    SafeToolSlice(
      ctxSupplier = { ProjectionContext(userSwitches = mapOf("lsp" to true)) },
      policy = BmAllowPolicy(),
      backend = backend,
      clock = clock,
    )

  @Test(timeout = 30_000) fun hydrationCharBudgetReconcilesWithCounts() {
    val history = listOf(
      ChatMessage(role = "user", text = "drop-q1"),
      ChatMessage(role = "assistant", text = "drop-a1"),
      ChatMessage(role = "user", text = "keep-q2"),
      ChatMessage(role = "assistant", text = "keep-a2"),
    )
    val provider = BmFakeProvider(doneOnce())
    val c = controller(provider, history, HistoryWindowCap.MaxMessages(2))
    try {
      runBlocking { c.send("q3") }
      val req = provider.seenRequests.single()
      // Total-window semantics: capped prefix [keep-q2, keep-a2] + live q3
      // (3 rows) windows to [keep-a2, q3] (2 rows): one head row dropped.
      assertEquals(1, c.droppedHistoryCount)
      // Char side mirrors the count: the dropped head row is "keep-q2"
      // (7 chars) — the load-time seed (2 rows) is replaced by this
      // request's window drop, same contract as the count.
      assertEquals("keep-q2".length.toLong(), c.droppedHistoryChars)
      // Small request fits MAX_REQUEST_CHARS: no send-time trim.
      assertEquals(0, c.lastRequestTrimmedCount)
      assertEquals(0L, c.lastRequestTrimmedChars)
      // Final size mirrors the shipped request exactly (time-block included).
      assertEquals(req.messages.sumOf { it.text.length }.toLong(), c.lastRequestChars)
      assertEquals("keep-a2", req.messages[0].text)
    } finally {
      c.close()
    }
  }

  @Test(timeout = 30_000) fun hydrationCharIdentityHoldsIncludingTimeDelta() {
    // BLOCK-1: full char reconciliation including the ephemeral time-block
    // delta. Fixed clock + fixed zone make the block deterministic, so the
    // expected delta is computed by calling the REAL buildRuntimeTimeContext
    // with the same path parameters prod uses (userTimezone=null,
    // sessionStart=null) — never hardcoded.
    val fixedClock = Clock.fixed(Instant.parse("2026-10-09T01:41:22Z"), ZoneId.of("UTC"))
    val fixedZone = { ZoneId.of("UTC") }
    val history = listOf(
      ChatMessage(role = "user", text = "drop-q1"),
      ChatMessage(role = "assistant", text = "drop-a1"),
      ChatMessage(role = "user", text = "keep-q2"),
      ChatMessage(role = "assistant", text = "keep-a2"),
    )
    val windowCap = HistoryWindowCap.MaxMessages(2)
    val provider = BmFakeProvider(doneOnce())
    val c = controller(provider, history, windowCap, clock = fixedClock, systemZone = fixedZone)
    try {
      runBlocking { c.send("q3") }
      val req = provider.seenRequests.single()
      // Combined window (capped prefix + live-at-build, time-block excluded
      // by construction): applyHistoryCap is the real prod window function.
      // live-at-build is exactly the one non-partial user row send() appends
      // before the build (the assistant placeholder is partial, excluded;
      // the "ok" response arrives after the build).
      val cappedPrefix = applyHistoryCap(history, windowCap)
      val liveAtBuild = listOf(ChatMessage(role = "user", text = "q3"))
      val combinedChars = (cappedPrefix + liveAtBuild).sumOf { it.text.length.toLong() }
      // Time-block delta: "\n\n" + the real block prod suffixes to the last
      // user copy (same clock/userTimezone/sessionStart/zoneSupplier path).
      // Named args also disambiguate the Clock overload (a bare null
      // sessionStart would match both the Instant? and ZonedDateTime? forms).
      val timeDelta =
        ("\n\n" + buildRuntimeTimeContext(
          clock = fixedClock,
          userTimezone = null,
          sessionStart = null,
          zoneSupplier = fixedZone,
        )).length.toLong()
      assertEquals(
        combinedChars + timeDelta,
        c.droppedHistoryChars + c.lastRequestTrimmedChars + c.lastRequestChars,
      )
      // And the shipped request is exactly the windowed base + the block.
      assertEquals(req.messages.sumOf { it.text.length }.toLong(), c.lastRequestChars)
      assertEquals(1, c.droppedHistoryCount)
      assertEquals("keep-q2".length.toLong(), c.droppedHistoryChars)
      assertEquals(0, c.lastRequestTrimmedCount)
      assertEquals(0L, c.lastRequestTrimmedChars)
    } finally {
      c.close()
    }
  }

  @Test(timeout = 30_000) fun continuationCharAccountingAddsOnTopOfBase() {
    // BLOCK-2: the continuation's own prefix trim publishes ON TOP of the
    // base request's trim (same total-head-trim contract as the count).
    // Near-cap calibration borrowed from
    // TurnControllerToolSliceTest.continuationAccountingIncludesToolCallsPayloadAndTrims:
    // a TEXT-only accounting of the whole continuation would fit the cap, but
    // counting the structured assistant toolCalls payload (4k args echo)
    // breaches it, so the builder head-trims exactly the near-cap head row.
    val fixedClock = Clock.fixed(Instant.parse("2026-10-09T01:41:22Z"), ZoneId.of("UTC"))
    val fixedZone = { ZoneId.of("UTC") }
    val nearCapHistory = "a".repeat(98_500)
    val echoArgs = """{"query":"${"x".repeat(4_000)}"}"""
    val provider = BmFakeProvider()
    val backend = BmSliceBackend()
    val c = controller(
      provider,
      history = listOf(ChatMessage(role = "user", text = nearCapHistory)),
      clock = fixedClock,
      systemZone = fixedZone,
      toolSlice = sliceLoop(backend, fixedClock),
    )
    // Cells observed at each request's stream time: the attempt sees the base
    // request's trim, the continuation sees the total (base + its own). The
    // fixed clock keeps both builds' time blocks byte-identical, so the
    // attempt/continuation message comparison below is exact (no
    // second-boundary skew between the two builds).
    var baseTrimmedCount = -1
    var baseTrimmedChars = -1L
    var contSeenTotalCount = -1
    var contSeenTotalChars = -1L
    var responses = 0
    provider.handler = { req ->
      responses++
      if (req.tools.isNotEmpty()) {
        baseTrimmedCount = c.lastRequestTrimmedCount
        baseTrimmedChars = c.lastRequestTrimmedChars
      } else {
        contSeenTotalCount = c.lastRequestTrimmedCount
        contSeenTotalChars = c.lastRequestTrimmedChars
      }
      flow {
        if (responses == 1) {
          emit(
            StreamEvent.ToolDone(
              toolIndex = 0,
              id = "call-a",
              name = "lsp.symbols",
              argumentsJson = echoArgs,
            ),
          )
          emit(StreamEvent.Done("tool_calls"))
        } else {
          emit(StreamEvent.TextDelta(0, 0, "trimmed answer"))
          emit(StreamEvent.Done("stop"))
        }
      }
    }
    try {
      runBlocking { c.send("查 A") }
      assertEquals(ChatStatus.IDLE, c.uiState.value.status)
      assertEquals(1, backend.invocations.get())
      assertEquals(2, provider.seenRequests.size)
      val attempt = provider.seenRequests[0]
      val cont = provider.seenRequests[1]
      // Wire shape: attempt offers the slice tool; continuation ships the
      // frozen pair with no tools.
      assertEquals(listOf("lsp.symbols"), attempt.tools.map { it.name })
      assertTrue("continuation must carry no tools", cont.tools.isEmpty())
      assertEquals(
        listOf("call-a"),
        cont.messages.filter { it.role == "assistant" }.flatMap { it.toolCalls }.map { it.id },
      )
      assertTrue("continuation must carry the tool result", cont.messages.any { it.role == "tool" })
      // The near-cap base fits on its own: no base trim; the structured
      // round is what breaches the cap.
      assertEquals(0, baseTrimmedCount)
      assertEquals(0L, baseTrimmedChars)
      // Trim tripped in both units, count and chars in the same direction.
      assertTrue("trim count must trip, got ${c.lastRequestTrimmedCount}", c.lastRequestTrimmedCount > 0)
      assertTrue("trim chars must mirror, got ${c.lastRequestTrimmedChars}", c.lastRequestTrimmedChars > 0)
      assertTrue("count flow must agree, got ${c.requestTrimmedCount.value}", c.requestTrimmedCount.value > 0)
      // Add-on-top: the structured round starts at the assistant message
      // carrying toolCalls; everything before it is the surviving base
      // suffix. The dropped head rows' text is the continuation's own trim.
      val structuredAt = cont.messages.indexOfFirst { it.toolCalls.isNotEmpty() }
      assertTrue("continuation must attach the structured round", structuredAt >= 0)
      val finalBase = cont.messages.subList(0, structuredAt)
      assertEquals(attempt.messages.takeLast(finalBase.size), finalBase)
      val droppedRows = attempt.messages.take(attempt.messages.size - finalBase.size)
      assertTrue("continuation must head-trim the near-cap row", droppedRows.isNotEmpty())
      val contTrimmedChars = droppedRows.sumOf { it.text.length.toLong() }
      assertEquals(baseTrimmedChars + contTrimmedChars, c.lastRequestTrimmedChars)
      assertEquals(baseTrimmedCount + droppedRows.size, c.lastRequestTrimmedCount)
      // Handler-observed totals agree with the post-turn cells (the
      // continuation stream runs after the add-on-top publish).
      assertEquals(c.lastRequestTrimmedCount, contSeenTotalCount)
      assertEquals(c.lastRequestTrimmedChars, contSeenTotalChars)
      // Final size mirrors the shipped continuation exactly.
      assertEquals(cont.messages.sumOf { it.text.length }.toLong(), c.lastRequestChars)
    } finally {
      c.close()
    }
  }

  @Test(timeout = 30_000) fun continuationTrimAddsOnTopOfNonzeroBaseTrim() {
    // BLOCK-2b: base MAX_REQUEST_CHARS trim AND the continuation's own prefix
    // trim are BOTH non-zero; the continuation adds on top (catches a
    // replace-instead-of-add regression that continuationCharAccountingAddsOnTopOfBase
    // cannot see because its baseTrim==0).
    // Fixture sizes are derived from TurnController.MAX_REQUEST_CHARS (never
    // magic): perRow=cap/100 with 150 history rows + live ≈1.5x the cap, so
    // the plain buildRequest send-time trim must trip; the structured round
    // (4k args echo + tool result, same BmSliceBackend/sliceLoop pattern as
    // the base-zero continuation test) pushes the wire estimate over the cap
    // again so the continuation head-trims further.
    val fixedClock = Clock.fixed(Instant.parse("2026-10-09T01:41:22Z"), ZoneId.of("UTC"))
    val fixedZone = { ZoneId.of("UTC") }
    val cap = TurnController.MAX_REQUEST_CHARS
    val perRow = cap / 100
    val history = (0 until 150).map { i ->
      val role = if (i % 2 == 0) "user" else "assistant"
      ChatMessage(role = role, text = "hist-$i: " + "x".repeat(perRow))
    }
    val echoArgs = """{"query":"${"x".repeat(4_000)}"}"""
    val provider = BmFakeProvider()
    val backend = BmSliceBackend()
    val c = controller(
      provider,
      history = history,
      clock = fixedClock,
      systemZone = fixedZone,
      toolSlice = sliceLoop(backend, fixedClock),
    )
    // Same handler-observation pattern as continuationCharAccountingAddsOnTopOfBase:
    // the attempt sees the base trim, the continuation sees the total. Fixed
    // clock keeps both builds' time blocks byte-identical.
    var baseTrimmedCount = -1
    var baseTrimmedChars = -1L
    var contSeenTotalCount = -1
    var contSeenTotalChars = -1L
    var responses = 0
    provider.handler = { req ->
      responses++
      if (req.tools.isNotEmpty()) {
        baseTrimmedCount = c.lastRequestTrimmedCount
        baseTrimmedChars = c.lastRequestTrimmedChars
      } else {
        contSeenTotalCount = c.lastRequestTrimmedCount
        contSeenTotalChars = c.lastRequestTrimmedChars
      }
      flow {
        if (responses == 1) {
          emit(
            StreamEvent.ToolDone(
              toolIndex = 0,
              id = "call-a",
              name = "lsp.symbols",
              argumentsJson = echoArgs,
            ),
          )
          emit(StreamEvent.Done("tool_calls"))
        } else {
          emit(StreamEvent.TextDelta(0, 0, "trimmed answer"))
          emit(StreamEvent.Done("stop"))
        }
      }
    }
    try {
      runBlocking { c.send("查 A") }
      assertEquals(ChatStatus.IDLE, c.uiState.value.status)
      assertEquals(1, backend.invocations.get())
      assertEquals(2, provider.seenRequests.size)
      val attempt = provider.seenRequests[0]
      val cont = provider.seenRequests[1]
      // Wire shape: attempt offers the slice tool; continuation ships the
      // frozen pair with no tools.
      assertEquals(listOf("lsp.symbols"), attempt.tools.map { it.name })
      assertTrue("continuation must carry no tools", cont.tools.isEmpty())
      assertEquals(
        listOf("call-a"),
        cont.messages.filter { it.role == "assistant" }.flatMap { it.toolCalls }.map { it.id },
      )
      assertTrue("continuation must carry the tool result", cont.messages.any { it.role == "tool" })
      // 1. Both phases trim in both units (base observed at attempt stream
      // time; continuation's own drop derived from the message diff below).
      assertTrue("base trim count must be non-zero, got $baseTrimmedCount", baseTrimmedCount > 0)
      assertTrue("base trim chars must be non-zero, got $baseTrimmedChars", baseTrimmedChars > 0)
      // The structured round starts at the assistant message carrying
      // toolCalls; everything before it is the surviving base suffix. The
      // dropped head rows' text is the continuation's own trim (droppedRows
      // pattern, never a re-derived prod formula).
      val structuredAt = cont.messages.indexOfFirst { it.toolCalls.isNotEmpty() }
      assertTrue("continuation must attach the structured round", structuredAt >= 0)
      val finalBase = cont.messages.subList(0, structuredAt)
      assertEquals(attempt.messages.takeLast(finalBase.size), finalBase)
      val droppedRows = attempt.messages.take(attempt.messages.size - finalBase.size)
      assertTrue(
        "continuation must head-trim on top of a non-zero base, attempt=${attempt.messages.size} finalBase=${finalBase.size}",
        droppedRows.isNotEmpty(),
      )
      val contTrimmedChars = droppedRows.sumOf { it.text.length.toLong() }
      assertTrue("continuation trim chars must be non-zero, got $contTrimmedChars", contTrimmedChars > 0)
      // 2. Add-vs-replace: the terminal cells are the SUM of the two phases.
      assertEquals(baseTrimmedCount + droppedRows.size, c.lastRequestTrimmedCount)
      assertEquals(baseTrimmedChars + contTrimmedChars, c.lastRequestTrimmedChars)
      // Handler-observed totals agree with the post-turn cells (the
      // continuation stream runs after the add-on-top publish).
      assertEquals(c.lastRequestTrimmedCount, contSeenTotalCount)
      assertEquals(c.lastRequestTrimmedChars, contSeenTotalChars)
      // 3. Final size mirrors the shipped continuation exactly, same direction.
      assertEquals(cont.messages.sumOf { it.text.length }.toLong(), c.lastRequestChars)
      assertTrue("count flow must agree, got ${c.requestTrimmedCount.value}", c.requestTrimmedCount.value > 0)
    } finally {
      c.close()
    }
  }

  @Test(timeout = 30_000) fun maxCharsHydrationExposesCharDrop() {
    val toolBlock = "answer\n[tool:lookup {\"q\":\"hi\"}]"
    val history = listOf(
      ChatMessage(role = "user", text = "drop me, way too long for the budget"),
      ChatMessage(role = "assistant", text = toolBlock),
      // Long tail: the combined (capped prefix + live) window still overflows
      // MaxChars(20) after the live row appends, so the send-time drop stays
      // observable (a short tail would fit alongside the live row and the
      // load-time seed would be replaced by 0 — existing total-window
      // contract, not a bug).
      ChatMessage(role = "user", text = "tail-row-is-long-enough!!"),
    )
    val provider = BmFakeProvider(doneOnce())
    val c = controller(provider, history, HistoryWindowCap.MaxChars(20))
    try {
      // Load-time seed, exact: 3-row prefix windows to the single newest
      // whole row, so exactly the two older rows drop (count 2, chars = the
      // two dropped rows' text sum).
      assertEquals(2, c.droppedHistoryCount)
      assertEquals(
        (history[0].text.length + history[1].text.length).toLong(),
        c.droppedHistoryChars,
      )
      runBlocking { c.send("q9") }
      val req = provider.seenRequests.single()
      // Send-time window drop replaces the seed with this request's exact
      // combined-window drop (existing total-window contract): [tail, q9]
      // windows to [q9], so exactly the tail row drops (count 1, its chars).
      assertEquals(1, c.droppedHistoryCount)
      assertEquals(history[2].text.length.toLong(), c.droppedHistoryChars)
      // Small request fits MAX_REQUEST_CHARS: no send-time trim.
      assertEquals(0, c.lastRequestTrimmedCount)
      assertEquals(0L, c.lastRequestTrimmedChars)
      assertEquals(req.messages.sumOf { it.text.length }.toLong(), c.lastRequestChars)
      // No half-marker loop: the window already dropped the toolBlock row, so
      // the shipped request carries no marker at all. Tool-pair integrity for
      // whole-message truncation is structural (markers ride inline in their
      // assistant block — truncation drops messages, never splits one; see
      // HistoryWindowCap KDoc), and MaxMessages-window coverage above pins
      // the windowing itself. A vacuous "no half marker" scan over a
      // marker-free request would prove nothing, so it is omitted deliberately.
    } finally {
      c.close()
    }
  }

  @Test(timeout = 30_000) fun unboundedDefaultDropsNothingInEitherUnit() {
    val history = listOf(
      ChatMessage(role = "user", text = "q1"),
      ChatMessage(role = "assistant", text = "a1"),
    )
    val provider = BmFakeProvider(doneOnce())
    val c = controller(provider, history, HistoryWindowCap.Unbounded)
    try {
      runBlocking { c.send("q2") }
      assertEquals(0, c.droppedHistoryCount)
      assertEquals(0L, c.droppedHistoryChars)
      assertEquals(0, c.lastRequestTrimmedCount)
      assertEquals(0L, c.lastRequestTrimmedChars)
      val req = provider.seenRequests.single()
      assertEquals(req.messages.sumOf { it.text.length }.toLong(), c.lastRequestChars)
    } finally {
      c.close()
    }
  }

  @Test(timeout = 30_000) fun requestTrimCharsMirrorTrimCountsUnderCap() {
    // Derive the flood size from the cap instead of magic numbers: 8 history
    // rows plus one live row must jointly overflow MAX_REQUEST_CHARS so the
    // send-time trim trips in both units. cap/5 per row -> 9 rows ≈ 1.8x the
    // cap; every single row still fits the admission boundary on its own.
    val cap = TurnController.MAX_REQUEST_CHARS
    val perRow = cap / 5
    val history = (0 until 8).map { i ->
      val role = if (i % 2 == 0) "user" else "assistant"
      ChatMessage(role = role, text = "hist-$i: " + "x".repeat(perRow))
    }
    val provider = BmFakeProvider(doneOnce())
    val c = controller(provider, history, HistoryWindowCap.Unbounded)
    try {
      runBlocking { c.send("latest user: " + "y".repeat(perRow)) }
      val req = provider.seenRequests.single()
      assertTrue("request must fit the cap", req.messages.sumOf { it.text.length } <= TurnController.MAX_REQUEST_CHARS)
      assertTrue("trim count must trip, got ${c.lastRequestTrimmedCount}", c.lastRequestTrimmedCount > 0)
      assertTrue("trim chars must mirror, got ${c.lastRequestTrimmedChars}", c.lastRequestTrimmedChars > 0)
      assertEquals(req.messages.sumOf { it.text.length }.toLong(), c.lastRequestChars)
      // Unbounded hydration still drops nothing at the window stage.
      assertEquals(0, c.droppedHistoryCount)
      assertEquals(0L, c.droppedHistoryChars)
    } finally {
      c.close()
    }
  }

  @Test(timeout = 30_000) fun syntheticTinyDeltaDripBenchmarkSkeleton() {
    // Synthetic flood skeleton (non-device): 3000 x 1-char deltas through the
    // #83 hybrid flush cadence. Pins exactness + the O(flushes) bound and logs
    // wall time for manual comparison; NOT an on-device frame/heap claim.
    //
    // Bound derivation (not magic): cadence UI_TEXT_FLUSH_CHARS=4096 /
    // UI_TEXT_FLUSH_DELTAS=32. The first 32 deltas commit immediately
    // (small-block fast paint), then one flush per 32 unflushed deltas plus
    // the terminal flush -> ~125 commits under the current constants, far
    // below the <200 bound; copied chars sum the growing snapshots ≈ 143k,
    // far below the <500_000 bound. Bounds keep slack for cadence-adjacent
    // edits. Flushes are pure delta/char counting with no clock dependency,
    // so these pins are not flaky.
    val provider = BmFakeProvider {
      flow {
        repeat(3_000) { emit(StreamEvent.TextDelta(0, 0, "x")) }
        emit(StreamEvent.Done("stop"))
      }
    }
    val c = controller(provider)
    try {
      val startNs = System.nanoTime()
      runBlocking { c.send("hi") }
      val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
      println("TurnControllerBudgetBenchmark: 3000x1-char drip took ${elapsedMs}ms (JVM skeleton, non-device)")
      val assistants = c.uiState.value.messages.filter { it.role == "assistant" }
      assertEquals("x".repeat(3_000), assistants.single().text)
      assertEquals(3_000, c.streamedTextDeltaCount)
      assertTrue("batched flushes must stay well below 3000, got ${c.uiTextUpdateCount}", c.uiTextUpdateCount < 200)
      assertTrue("copied chars must drop an order of magnitude, got ${c.uiTextCopiedChars}", c.uiTextCopiedChars < 500_000)
    } finally {
      c.close()
    }
  }

  @Test(timeout = 30_000) fun syntheticHydrationWindowBenchmarkSkeleton() {
    // Synthetic hydration skeleton at the HISTORY_LOAD_CAP scale (2000 rows):
    // windows the combined copy and reconciles both units. Times the send
    // (window + request build + one fake round) on JVM only.
    val history = (0 until 2000).map { i ->
      val role = if (i % 2 == 0) "user" else "assistant"
      ChatMessage(role = role, text = "h$i:" + "v".repeat(90))
    }
    val provider = BmFakeProvider(doneOnce("r"))
    val c = controller(provider, history, HistoryWindowCap.MaxMessages(2000))
    try {
      val startNs = System.nanoTime()
      runBlocking { c.send("tail-q") }
      val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
      println("TurnControllerBudgetBenchmark: 2000-row hydration window took ${elapsedMs}ms (JVM skeleton, non-device)")
      val req = provider.seenRequests.single()
      assertEquals(req.messages.sumOf { it.text.length }.toLong(), c.lastRequestChars)
      // 2000 x ~93-char rows ≈ 186k of combined window: the MaxMessages(2000)
      // window drops exactly the oldest row, then the still-over-cap head
      // exceeds MAX_REQUEST_CHARS, so the send-time trim must trip in both
      // units and the shipped payload must fit the cap.
      assertEquals(1, c.droppedHistoryCount)
      assertEquals(history.first().text.length.toLong(), c.droppedHistoryChars)
      val payload = req.messages.sumOf { it.text.length }
      assertTrue("request must fit the cap, got $payload", payload <= TurnController.MAX_REQUEST_CHARS)
      assertTrue("trim count must trip, got ${c.lastRequestTrimmedCount}", c.lastRequestTrimmedCount > 0)
      assertTrue("trim chars must mirror, got ${c.lastRequestTrimmedChars}", c.lastRequestTrimmedChars > 0)
      assertEquals(req.messages.size + c.droppedHistoryCount + c.lastRequestTrimmedCount, history.size + 1)
    } finally {
      c.close()
    }
  }
}
