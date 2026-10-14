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
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class HistoryFakeProvider(
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

private class HistoryAllowPolicy : PolicyStore {
    override fun evaluate(action: String, resource: String) =
        PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
    override suspend fun setRule(rule: PolicyRule) = Unit
    override suspend fun removeRule(pattern: String) = Unit
    override suspend fun listRules(): List<PolicyRule> = emptyList()
    override suspend fun evaluateFresh(action: String, resource: String) =
        PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

private class PartialCapturingSink : TranscriptSink {
    data class Failed(val runId: String, val partialText: String, val error: String)
    val failedThreeArg = Collections.synchronizedList(mutableListOf<Failed>())

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
    override suspend fun onTurnFailed(runId: String, partialText: String, error: String) {
        failedThreeArg.add(Failed(runId, partialText, error))
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
    override suspend fun onToolDone(
        runId: String,
        toolIndex: Int,
        id: String,
        name: String,
        argumentsJson: String,
    ) = Unit
    override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) = Unit
}

/**
 * Phase 3 hydration regressions (pure JVM, all fake data).
 *
 * - F8: the first request after resume carries the restored prefix.
 * - [HistoryWindowCap] windows the combined (capped prefix + live) request
 *   copy and truncates oldest-first at whole-message boundaries
 *   (tool markers ride inside their assistant block, so pairs stay intact)
 *   and every truncation is observable via [TurnController.droppedHistoryCount].
 * - The terminal failure path forwards the live partial fragment plus the
 *   sanitized reason through the three-arg sink form.
 */
class TurnControllerHistoryTest {

    private fun doneOnce(text: String = "ok"): (ChatRequest) -> Flow<StreamEvent> = {
        flow {
            emit(StreamEvent.TextDelta(0, 0, text))
            emit(StreamEvent.Done("stop"))
        }
    }

    private fun historyOf(vararg pairs: Pair<String, String>): List<ChatMessage> =
        pairs.map { (role, text) -> ChatMessage(role = role, text = text) }

    @Test fun resumeFirstRequestCarriesRestoredHistory() {
        val provider = HistoryFakeProvider(doneOnce())
        var n = 0
        val c = TurnController(
            provider = provider,
            policy = HistoryAllowPolicy(),
            dispatcher = Dispatchers.Default,
            sleeper = {},
            newId = { "hist-${n++}" },
            initialHistory = historyOf("user" to "q1", "assistant" to "a1"),
        )
        try {
            runBlocking { c.send("q2") }
            val req = provider.seenRequests.single()
            assertEquals(listOf("user", "assistant", "user"), req.messages.map { it.role })
            assertEquals("q1", req.messages[0].text)
            assertEquals("a1", req.messages[1].text)
            // The live message keeps its text; only the ephemeral time block is suffixed.
            assertTrue(req.messages[2].text.startsWith("q2"))
            assertEquals(0, c.droppedHistoryCount)
        } finally {
            c.close()
        }
    }

    @Test fun emptyHistoryKeepsLegacyRequestShape() {
        val provider = HistoryFakeProvider(doneOnce())
        var n = 0
        val c = TurnController(
            provider = provider,
            policy = HistoryAllowPolicy(),
            dispatcher = Dispatchers.Default,
            sleeper = {},
            newId = { "legacy-${n++}" },
        )
        try {
            runBlocking { c.send("hello") }
            val req = provider.seenRequests.single()
            assertEquals(1, req.messages.size)
            assertEquals("user", req.messages.single().role)
            assertTrue(req.messages.single().text.startsWith("hello"))
            assertEquals(0, c.droppedHistoryCount)
        } finally {
            c.close()
        }
    }

    @Test fun maxMessagesCapTruncatesOldestFirstAndReports() {
        val provider = HistoryFakeProvider(doneOnce())
        var n = 0
        val c = TurnController(
            provider = provider,
            policy = HistoryAllowPolicy(),
            dispatcher = Dispatchers.Default,
            sleeper = {},
            newId = { "cap-${n++}" },
            initialHistory = historyOf(
                "user" to "drop-q1",
                "assistant" to "drop-a1",
                "user" to "keep-q2",
                "assistant" to "keep-a2",
            ),
            historyCap = HistoryWindowCap.MaxMessages(2),
        )
        try {
            // Total-window semantics: the cap bounds the combined
            // (capped prefix + live) request copy, newest kept. Load caps the
            // 4-row prefix to [keep-q2, keep-a2] (seed drop 2); the first send
            // appends q3 (combined 3) so the window evicts one more oldest
            // prefix row (keep-q2) to hold the total at 2. The load-time seed
            // is replaced by this request's window drop (1); the load-time
            // total stays observable separately via the factory's
            // historyDroppedCount.
            runBlocking { c.send("q3") }
            val req = provider.seenRequests.single()
            assertEquals(
                listOf("assistant", "user"),
                req.messages.map { it.role },
            )
            assertEquals("keep-a2", req.messages[0].text)
            assertTrue(req.messages[1].text.startsWith("q3"))
            assertEquals(1, c.droppedHistoryCount)
        } finally {
            c.close()
        }
    }

    @Test fun maxCharsCapKeepsWholeMessagesSoToolPairsStayIntact() {
        val toolBlock = "answer\n[tool:lookup {\"q\":\"hi\"}]"
        val kept = applyHistoryCap(
            historyOf(
                "user" to "drop me, way too long for the budget",
                "assistant" to toolBlock,
                "user" to "tail",
            ),
            HistoryWindowCap.MaxChars(20),
        )
        // "tail"(4) + toolBlock(30) exceeds 20, so only "tail" survives — but
        // the tool-carrying assistant block is never split, only dropped whole.
        assertEquals(listOf("tail"), kept.map { it.text })
        for (m in kept) {
            assertTrue("no half tool marker may survive", !m.text.contains("[tool:") || m.text == toolBlock)
        }

        val fitting = applyHistoryCap(
            historyOf("user" to "qq", "assistant" to toolBlock),
            HistoryWindowCap.MaxChars(10_000),
        )
        assertEquals(listOf("qq", toolBlock), fitting.map { it.text })
    }

    @Test fun unboundedDefaultHydratesEverything() {
        val history = historyOf("user" to "q1", "assistant" to "a1", "user" to "q2")
        assertEquals(history, applyHistoryCap(history, HistoryWindowCap.Unbounded))
        var n = 0
        val c = TurnController(
            provider = HistoryFakeProvider(doneOnce()),
            policy = HistoryAllowPolicy(),
            dispatcher = Dispatchers.Default,
            sleeper = {},
            newId = { "unb-${n++}" },
            initialHistory = history,
        )
        try {
            assertEquals(0, c.droppedHistoryCount)
        } finally {
            c.close()
        }
    }

    @Test fun terminalFailureForwardsPartialFragmentAndReason() {
        val provider = HistoryFakeProvider {
            flow {
                emit(StreamEvent.TextDelta(0, 0, "half"))
                emit(StreamEvent.Failed("HTTP 500 boom", retryable = false))
            }
        }
        val sink = PartialCapturingSink()
        var n = 0
        val c = TurnController(
            provider = provider,
            policy = HistoryAllowPolicy(),
            transcript = sink,
            dispatcher = Dispatchers.Default,
            sleeper = {},
            newId = { "fail-${n++}" },
        )
        try {
            runBlocking { c.send("hi") }
            val failure = sink.failedThreeArg.single()
            assertEquals("half", failure.partialText)
            assertTrue("reason must be sanitized, got: ${failure.error}", failure.error.contains("500"))
            // Memory keeps the failed fragment flagged, matching the ledger row.
            val assistants = c.uiState.value.messages.filter { it.role == "assistant" }
            assertEquals(1, assistants.size)
            assertTrue(assistants.single().isPartial)
            assertEquals("half", assistants.single().text)
        } finally {
            c.close()
        }
    }

    @Test fun aggregateRequestContextCapTruncatesOldestFirst() {
        val provider = HistoryFakeProvider(doneOnce())
        var n = 0
        // Create 8 history messages of 18,000 chars each = 144,000 chars.
        val historyList = (0 until 8).map { i ->
            val role = if (i % 2 == 0) "user" else "assistant"
            ChatMessage(role = role, text = "hist-$i: " + "x".repeat(17990))
        }
        val c = TurnController(
            provider = provider,
            policy = HistoryAllowPolicy(),
            dispatcher = Dispatchers.Default,
            sleeper = {},
            newId = { "agg-${n++}" },
            initialHistory = historyList,
        )
        try {
            val latestUserText = "latest user: " + "y".repeat(17985)
            runBlocking { c.send(latestUserText) }
            val req = provider.seenRequests.single()
            val totalChars = req.messages.sumOf { it.text.length }
            assertTrue(
                "total request chars must be <= MAX_REQUEST_CHARS, was $totalChars",
                totalChars <= TurnController.MAX_REQUEST_CHARS,
            )
            val lastMsg = req.messages.last()
            assertEquals("user", lastMsg.role)
            assertTrue(
                "latest user message must be completely preserved",
                lastMsg.text.startsWith(latestUserText),
            )
            // Initial chars = 8 * 18,000 + ~18,000 (user) + time context (~100) = ~162,100
            // Dropping msg 0 (18,000) -> ~144,100
            // Dropping msg 1 (18,000) -> ~126,100
            // Dropping msg 2 (18,000) -> ~108,100
            // Dropping msg 3 (18,000) -> ~90,100 <= 100,000
            // Exactly 4 messages trimmed.
            assertEquals(4, c.lastRequestTrimmedCount)
            assertEquals(8 + 1 - 4, req.messages.size)
            assertEquals("hist-4", req.messages[0].text.substring(0, 6))
        } finally {
            c.close()
        }
    }

    @Test fun aggregateRequestContextCapRejectsSingleOversizeInputAtAdmission() {
        val provider = HistoryFakeProvider(doneOnce())
        var n = 0
        // History has 2 messages of 20,000 chars each = 40,000 chars.
        val historyList = listOf(
            ChatMessage(role = "user", text = "hist-0: " + "a".repeat(19990)),
            ChatMessage(role = "assistant", text = "hist-1: " + "b".repeat(19990)),
        )
        val sink = PartialCapturingSink()
        val c = TurnController(
            provider = provider,
            policy = HistoryAllowPolicy(),
            transcript = sink,
            dispatcher = Dispatchers.Default,
            sleeper = {},
            newId = { "single-${n++}" },
            initialHistory = historyList,
        )
        try {
            // R4-4 strict invariant: a single-turn input that alone (plus the
            // ephemeral "\n\n" + time block) exceeds MAX_REQUEST_CHARS is
            // rejected at admission — never silently truncated, never sent.
            val hugeUserText = "huge-user: " + "z".repeat(105_000)
            val failure = runCatching { runBlocking { c.send(hugeUserText) } }.exceptionOrNull()
            assertTrue(
                "oversize single input must be rejected with InputTooLongException, was $failure",
                failure is InputTooLongException,
            )
            assertTrue("rejected input must stay an IllegalArgumentException", failure is IllegalArgumentException)
            // No provider call, no trimming bookkeeping, no UI append.
            assertTrue("no request may be emitted for rejected input", provider.seenRequests.isEmpty())
            assertEquals(0, c.lastRequestTrimmedCount)
            assertTrue(
                "rejected input must not append a user row",
                c.uiState.value.messages.none { it.role == "user" && it.text == hugeUserText },
            )
            assertTrue("no transcript failure row may be recorded", sink.failedThreeArg.isEmpty())

            // Same fail-fast via startOrEnqueue (idle direct admission).
            val directFailure =
                runCatching { runBlocking { c.startOrEnqueue("q".repeat(100_000 + 1_000)) } }.exceptionOrNull()
            assertTrue(directFailure is InputTooLongException)
            assertTrue(provider.seenRequests.isEmpty())

            // Same fail-fast via synchronous steer (busy or idle entry check).
            val steerFailure = runCatching { c.steer("s".repeat(100_000 + 1_000)) }.exceptionOrNull()
            assertTrue(steerFailure is InputTooLongException)
            assertTrue(provider.seenRequests.isEmpty())
        } finally {
            c.close()
        }
    }

    @Test fun singleInputBoundaryFitsExactlyAtMaxMinusOverhead() {
        // Deterministic overhead: fixed clock + fixed zone, no session line.
        val fixedClock = java.time.Clock.fixed(
            java.time.Instant.parse("2026-01-15T12:00:00Z"),
            java.time.ZoneId.of("UTC"),
        )
        val zoneSupplier: () -> java.time.ZoneId = { java.time.ZoneId.of("UTC") }
        val overhead = 2 + buildRuntimeTimeContext(fixedClock, "UTC", null, zoneSupplier).length
        val maxText = TurnController.MAX_REQUEST_CHARS - overhead
        assertTrue("test budget must be positive, overhead=$overhead", maxText > 0)

        fun controllerFor(provider: HistoryFakeProvider): TurnController {
            var n = 0
            return TurnController(
                provider = provider,
                policy = HistoryAllowPolicy(),
                dispatcher = Dispatchers.Default,
                sleeper = {},
                newId = { "bound-${n++}" },
                clock = fixedClock,
                userTimezone = "UTC",
                sessionStart = null,
                systemZone = zoneSupplier,
            )
        }

        // Exactly at budget: accepted, strict sum <= MAX holds.
        val okProvider = HistoryFakeProvider(doneOnce())
        val ok = controllerFor(okProvider)
        try {
            val fitting = "w".repeat(maxText)
            runBlocking { ok.send(fitting) }
            val req = okProvider.seenRequests.single()
            val total = req.messages.sumOf { it.text.length }
            assertTrue("strict invariant: sum=$total <= MAX", total <= TurnController.MAX_REQUEST_CHARS)
            assertEquals(maxText + overhead, total)
        } finally {
            ok.close()
        }

        // One char over: rejected at admission, nothing emitted.
        val overProvider = HistoryFakeProvider(doneOnce())
        val over = controllerFor(overProvider)
        try {
            val oneOver = "w".repeat(maxText + 1)
            val failure = runCatching { runBlocking { over.send(oneOver) } }.exceptionOrNull()
            assertTrue(failure is InputTooLongException)
            assertTrue(overProvider.seenRequests.isEmpty())
            assertEquals(0, over.lastRequestTrimmedCount)
        } finally {
            over.close()
        }
    }

    @Test fun queuedOversizeInputFailsFastWithoutEnqueue() {
        val gate = CompletableDeferred<Unit>()
        val provider = HistoryFakeProvider {
            flow {
                emit(StreamEvent.TextDelta(0, 0, "part"))
                gate.await()
                emit(StreamEvent.Done("stop"))
            }
        }
        var n = 0
        val c = TurnController(
            provider = provider,
            policy = HistoryAllowPolicy(),
            dispatcher = Dispatchers.Default,
            sleeper = {},
            newId = { "queue-${n++}" },
        )
        val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val first = outer.async { c.send("first") }
            // Wait until the first turn is actually in flight.
            val end = System.currentTimeMillis() + 8_000
            while (provider.seenRequests.isEmpty()) {
                if (System.currentTimeMillis() > end) error("timed out waiting for in-flight turn")
                Thread.sleep(10)
            }
            val oversize = "q".repeat(100_000 + 1_000)
            val failure =
                runCatching { runBlocking { c.startOrEnqueue(oversize, opId = 77) } }.exceptionOrNull()
            assertTrue("busy oversize enqueue must fail fast, was $failure", failure is InputTooLongException)
            assertEquals(0, c.uiState.value.pendingSteerCount)
            assertTrue(c.drainQueued().isEmpty())
            gate.complete(Unit)
            runBlocking { first.await() }
        } finally {
            try { gate.complete(Unit) } catch (_: Exception) { }
            outer.cancel()
            c.close()
        }
    }

    @Test fun promotionRevalidatesLengthAfterZoneGrowth() {
        // Promotion race (R4-4): narrow zone passes admission into the FIFO,
        // wide zone at promotion must reject via failPromotion (FIFO retained,
        // no appendUser, no transcript start, no provider call).
        val fixedClock = java.time.Clock.fixed(
            java.time.Instant.parse("2026-01-15T12:00:00Z"),
            java.time.ZoneId.of("UTC"),
        )
        var current: java.time.ZoneId = java.time.ZoneId.of("UTC")
        val narrowZone = java.time.ZoneId.of("UTC")
        val wideZone = java.time.ZoneId.of("America/Argentina/Buenos_Aires")
        val narrowBlock = buildRuntimeTimeContext(fixedClock, null, null) { narrowZone }
        val wideBlock = buildRuntimeTimeContext(fixedClock, null, null) { wideZone }
        val overheadNarrow = 2 + narrowBlock.length
        val overheadWide = 2 + wideBlock.length
        assertTrue(
            "wide zone must render a longer block (narrow=$overheadNarrow wide=$overheadWide)",
            overheadWide > overheadNarrow,
        )
        // Exactly fits the narrow block, overshoots the wide block.
        val followText = "p".repeat(TurnController.MAX_REQUEST_CHARS - overheadNarrow)
        assertTrue(followText.length + overheadNarrow <= TurnController.MAX_REQUEST_CHARS)
        assertTrue(followText.length + overheadWide > TurnController.MAX_REQUEST_CHARS)

        val gate = CompletableDeferred<Unit>()
        val provider = HistoryFakeProvider {
            flow {
                emit(StreamEvent.TextDelta(0, 0, "part"))
                gate.await()
                emit(StreamEvent.Done("stop"))
            }
        }
        val startedTexts: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val sink = object : TranscriptSink {
            override suspend fun onTurnStarted(runId: String, text: String) { startedTexts.add(text) }
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
            override suspend fun onToolDone(runId: String, toolIndex: Int, id: String, name: String, argumentsJson: String) = Unit
            override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) = Unit
        }
        var n = 0
        val c = TurnController(
            provider = provider,
            policy = HistoryAllowPolicy(),
            transcript = sink,
            dispatcher = Dispatchers.Default,
            sleeper = {},
            newId = { "promo-${n++}" },
            clock = fixedClock,
            userTimezone = null,
            sessionStart = null,
            systemZone = { current },
        )
        val outer = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val first = outer.async { c.send("first") }
            val end = System.currentTimeMillis() + 8_000
            while (provider.seenRequests.isEmpty()) {
                if (System.currentTimeMillis() > end) error("timed out waiting for in-flight turn")
                Thread.sleep(10)
            }
            // Admitted while the zone is still narrow: passes admission, queued FIFO.
            assertEquals(TurnStart.Queued, runBlocking { c.startOrEnqueue(followText, opId = 99) })
            assertEquals(1, c.uiState.value.pendingSteerCount)
            // Grow the zone before the promotion handoff.
            current = wideZone
            gate.complete(Unit)
            runBlocking { first.await() }
            val end2 = System.currentTimeMillis() + 8_000
            while (c.uiState.value.status != ChatStatus.ERROR) {
                if (System.currentTimeMillis() > end2) error("timed out waiting for promotion rejection")
                Thread.sleep(10)
            }
            assertEquals("input too long", c.uiState.value.error)
            assertEquals(1, c.uiState.value.pendingSteerCount)
            assertTrue(c.uiState.value.queuedRecoveryRequired)
            // No follow-up provider call, no follow-up user row, no follow-up transcript start.
            assertEquals(1, provider.streamCalls.get())
            assertEquals(1, provider.seenRequests.size)
            assertEquals(listOf("first"), c.uiState.value.messages.filter { it.role == "user" }.map { it.text })
            assertEquals(listOf("first"), startedTexts.toList())
            // failPromotion retains the FIFO for explicit recovery.
            assertEquals(listOf(QueuedIntent(99L, followText)), c.drainQueued())
        } finally {
            try { gate.complete(Unit) } catch (_: Exception) { }
            outer.cancel()
            c.close()
        }
    }

    @Test fun idleDirectStartRejectedWhenZoneGrowsDuringGate() {
        // Gate-growth race (R4-4 second round): narrow zone passes admission,
        // wide zone at the idle direct-start second lock must reject with
        // InputTooLongException (no append, no provider call, FIFO retained empty).
        val fixedClock = java.time.Clock.fixed(
            java.time.Instant.parse("2026-01-15T12:00:00Z"),
            java.time.ZoneId.of("UTC"),
        )
        var current: java.time.ZoneId = java.time.ZoneId.of("UTC")
        val narrowZone = java.time.ZoneId.of("UTC")
        val wideZone = java.time.ZoneId.of("America/Argentina/Buenos_Aires")
        val narrowBlock = buildRuntimeTimeContext(fixedClock, null, null) { narrowZone }
        val wideBlock = buildRuntimeTimeContext(fixedClock, null, null) { wideZone }
        val overheadNarrow = 2 + narrowBlock.length
        val overheadWide = 2 + wideBlock.length
        assertTrue(
            "wide zone must render a longer block (narrow=$overheadNarrow wide=$overheadWide)",
            overheadWide > overheadNarrow,
        )
        val directText = "g".repeat(TurnController.MAX_REQUEST_CHARS - overheadNarrow)
        assertTrue(directText.length + overheadNarrow <= TurnController.MAX_REQUEST_CHARS)
        assertTrue(directText.length + overheadWide > TurnController.MAX_REQUEST_CHARS)

        val provider = HistoryFakeProvider(doneOnce())
        val startedTexts: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val sink = object : TranscriptSink {
            override suspend fun onTurnStarted(runId: String, text: String) { startedTexts.add(text) }
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
            override suspend fun onToolDone(runId: String, toolIndex: Int, id: String, name: String, argumentsJson: String) = Unit
            override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) = Unit
        }
        var n = 0
        val c = TurnController(
            provider = provider,
            policy = HistoryAllowPolicy(),
            transcript = sink,
            dispatcher = Dispatchers.Default,
            sleeper = {},
            newId = { "gate-${n++}" },
            clock = fixedClock,
            userTimezone = null,
            sessionStart = null,
            systemZone = { current },
        )
        try {
            // Flip narrow -> wide inside the gate window (after the fresh gate,
            // before the second lock), so admission passes but the idle
            // direct-start re-check must fail.
            c.afterStartOrEnqueueGateForTest = { current = wideZone }
            val failure =
                runCatching { runBlocking { c.startOrEnqueue(directText, opId = 101) } }.exceptionOrNull()
            assertTrue("idle direct start must fail fast, was $failure", failure is InputTooLongException)
            // No append, no provider call, no transcript start.
            assertTrue(provider.seenRequests.isEmpty())
            assertEquals(0, provider.streamCalls.get())
            assertTrue(c.uiState.value.messages.isEmpty())
            assertTrue(startedTexts.isEmpty())
            assertEquals(ChatStatus.IDLE, c.uiState.value.status)
            assertEquals(0, c.uiState.value.pendingSteerCount)
            assertTrue(c.drainQueued().isEmpty())
        } finally {
            c.afterStartOrEnqueueGateForTest = null
            c.close()
        }
    }

    @Test fun startTurnSecondLockRejectedWhenZoneGrowsDuringGate() {
        // Same gate-growth race via the legacy startTurn second lock: admission
        // passes narrow, second lock must reject wide with no bump/append.
        val fixedClock = java.time.Clock.fixed(
            java.time.Instant.parse("2026-01-15T12:00:00Z"),
            java.time.ZoneId.of("UTC"),
        )
        var current: java.time.ZoneId = java.time.ZoneId.of("UTC")
        val narrowZone = java.time.ZoneId.of("UTC")
        val wideZone = java.time.ZoneId.of("America/Argentina/Buenos_Aires")
        val narrowBlock = buildRuntimeTimeContext(fixedClock, null, null) { narrowZone }
        val wideBlock = buildRuntimeTimeContext(fixedClock, null, null) { wideZone }
        val overheadNarrow = 2 + narrowBlock.length
        val overheadWide = 2 + wideBlock.length
        assertTrue(
            "wide zone must render a longer block (narrow=$overheadNarrow wide=$overheadWide)",
            overheadWide > overheadNarrow,
        )
        val directText = "h".repeat(TurnController.MAX_REQUEST_CHARS - overheadNarrow)
        assertTrue(directText.length + overheadNarrow <= TurnController.MAX_REQUEST_CHARS)
        assertTrue(directText.length + overheadWide > TurnController.MAX_REQUEST_CHARS)

        val provider = HistoryFakeProvider(doneOnce())
        val startedTexts: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val sink = object : TranscriptSink {
            override suspend fun onTurnStarted(runId: String, text: String) { startedTexts.add(text) }
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
            override suspend fun onToolDone(runId: String, toolIndex: Int, id: String, name: String, argumentsJson: String) = Unit
            override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) = Unit
        }
        var n = 0
        val c = TurnController(
            provider = provider,
            policy = HistoryAllowPolicy(),
            transcript = sink,
            dispatcher = Dispatchers.Default,
            sleeper = {},
            newId = { "gate-st-${n++}" },
            clock = fixedClock,
            userTimezone = null,
            sessionStart = null,
            systemZone = { current },
        )
        try {
            c.afterStartTurnGateForTest = { current = wideZone }
            val failure =
                runCatching { runBlocking { c.startTurn(directText) } }.exceptionOrNull()
            assertTrue("startTurn second lock must fail fast, was $failure", failure is InputTooLongException)
            assertTrue(provider.seenRequests.isEmpty())
            assertEquals(0, provider.streamCalls.get())
            assertTrue(c.uiState.value.messages.isEmpty())
            assertTrue(startedTexts.isEmpty())
            assertEquals(ChatStatus.IDLE, c.uiState.value.status)
            assertTrue(c.drainQueued().isEmpty())
        } finally {
            c.afterStartTurnGateForTest = null
            c.close()
        }
    }
}
