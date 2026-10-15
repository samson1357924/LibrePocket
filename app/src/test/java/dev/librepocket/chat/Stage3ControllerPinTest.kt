package dev.librepocket.chat

import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.SessionStore
import dev.librepocket.agent.ui.chat.findDanglingRunIds
import dev.librepocket.session.SessionTranscriptSink
import dev.librepocket.session.TranscriptEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class Stage3Policy : PolicyStore {
    override fun evaluate(action: String, resource: String) =
        PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
    override suspend fun setRule(rule: PolicyRule) = Unit
    override suspend fun removeRule(pattern: String) = Unit
    override suspend fun listRules(): List<PolicyRule> = emptyList()
    override suspend fun evaluateFresh(action: String, resource: String) =
        PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

/**
 * Completing provider: half-delta then Done immediately. The terminal submits
 * to the sink at once, so a [Stage3ParkingStore]-wedged terminal is
 * SUBMITTING (pending, uncommitted) when cancel lands — the only shape that
 * installs a cancel receipt (see TurnController.hostedTurn: receipt requires
 * terminalSubmitting). A provider-side gate before Done would leave no
 * submitted terminal, so cancel would take the direct-write path with no
 * receipt at all.
 */
private class HangingProvider : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        emit(StreamEvent.TextDelta(0, 0, "half"))
        emit(StreamEvent.Done("stop"))
    }
    override suspend fun listModels(): List<String> = emptyList()
}

/**
 * Parks TERMINAL assistant appends (non-partial) until released.
 * Partial deltas (isPartial=true) pass through, so the host always reaches
 * terminal submission; the parked terminal keeps the submit truth at
 * SUBMITTING so a racing cancel installs its receipt deterministically.
 * Compensation notice/system rows (retry/system kinds) likewise pass — only
 * the terminal assistant row parks. Single-turn tests use the initial
 * [entered]/[release] pair; the 161-loop calls [nextGeneration] per attempt
 * for a fresh pair.
 */
private class Stage3ParkingStore(val inner: FakeSessionStore) : SessionStore by inner {
    private val gateLock = Any()
    private var enteredLatch = CountDownLatch(1)
    private var releaseGate: CompletableDeferred<Unit> = CompletableDeferred()
    val entered: CountDownLatch get() = synchronized(gateLock) { enteredLatch }
    val release: CompletableDeferred<Unit> get() = synchronized(gateLock) { releaseGate }
    @Volatile var released = false
    fun nextGeneration() {
        synchronized(gateLock) {
            enteredLatch = CountDownLatch(1)
            releaseGate = CompletableDeferred()
        }
        released = false
    }
    fun awaitParked(timeout: Long, unit: TimeUnit): Boolean =
        synchronized(gateLock) { enteredLatch }.await(timeout, unit)
    fun releaseParked() {
        released = true
        synchronized(gateLock) { releaseGate }.complete(Unit)
    }
    private suspend fun parkIfTerminal(events: List<TranscriptEvent>) {
        if (events.any { it.kind == "assistant" && !it.isPartial }) {
            synchronized(gateLock) { enteredLatch }.countDown()
            val g = synchronized(gateLock) { releaseGate }
            if (!released) withContext(NonCancellable) { g.await() }
        }
    }
    override suspend fun appendEvent(event: TranscriptEvent): Long {
        parkIfTerminal(listOf(event))
        return inner.appendEvent(event)
    }
    override suspend fun appendEvents(events: List<TranscriptEvent>): List<Long> {
        if (events.isNotEmpty()) parkIfTerminal(events)
        return inner.appendEvents(events)
    }
}

private class NoopSink : TranscriptSink {
    override suspend fun onTurnStarted(runId: String, text: String) = Unit
    override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) = Unit
    override suspend fun onTurnFailed(runId: String, error: String) = Unit
    override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
    override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) = Unit
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
 * Stage-3 controller/sink pins (P2-2 equal-budget pairs, P2-3 seal handoff,
 * P2-6 receipt bound, P2-7 post-close waiter).
 *
 * Dispatcher note (P1-1): every park is a gate/latch
 * ([CountDownLatch]/[CompletableDeferred]); convergence waits are bounded
 * `withTimeout + delay` polls, never fixed sleeps. Equal budgets are pinned
 * as equal injected values (e.g. 500==500): the positive control lands
 * inside the shared fence via a gate release, the negative holds past it.
 */
class Stage3ControllerPinTest {

    private suspend fun awaitCond(timeoutMs: Long, cond: () -> Boolean) {
        withTimeout(timeoutMs) { while (!cond()) delay(15) }
    }

    private fun controller(
        provider: LlmProvider,
        sink: SessionTranscriptSink,
        n: AtomicInteger,
        settleMs: Long = 300L,
        compensationMs: Long = 500L,
        maxRounds: Int = 60,
    ) = TurnController(
        provider = provider,
        policy = Stage3Policy(),
        transcript = sink,
        retryConfig = TurnRetryConfig(maxRetries = 1, retryDelaysMs = listOf(5_000L)),
        dispatcher = Dispatchers.Default,
        sleeper = {},
        newId = { "s3-${n.incrementAndGet() - 1}" },
        cancelSettleTimeoutMs = settleMs,
        cancelCompensationTimeoutMs = compensationMs,
        cancelReceiptMaxRounds = maxRounds,
    )

    private fun rows(store: FakeSessionStore, sid: String): List<TranscriptEvent> =
        synchronized(store.events) {
            ArrayList(store.events.filter { it.sessionId == sid }.sortedBy { it.seq })
        }

    // ---- P2-3: sink seal x isFinal=false / x hasReasoning=true ----

    /**
     * P2-3a: a sealed sink fails a non-final write fast (never a silent
     * uncompensated row): the failed write marks INTERRUPTED (fail-closed
     * handoff — the family stays dangling for reopen backfill) and
     * flushDurable stays false until that backfill. No terminal is fabricated
     * at seal time; the mark comes from the failed write, never from the bare
     * seal (an idle shutdown with no writes stays clean).
     * Known limitation (Stage-3, non-blocking): pins the handoff (mark +
     * flushDurable false) but never runs the reopen INTERRUPTED backfill
     * second half — the repair path is owned by reopen tests, not repeated
     * here.
     */
    @Test fun sealedSink_nonFinalWriteFailsFast() = runBlocking {
        val sink = OrderedTranscriptSink(NoopSink())
        sink.shutdown(500L)?.join()
        assertTrue(sink.isSealed())
        assertFalse("bare seal with no writes stays clean", sink.hasDurableFailures())
        var failed = false
        try {
            withTimeout(5_000) {
                sink.onTurnFailed("r-nf", "half", "boom", "r-nf", 0, false)
            }
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("sealed non-final write must fail fast, never silently park", failed)
        assertTrue(
            "failed sealed write must mark INTERRUPTED (uncompensated until backfill)",
            sink.hasDurableFailures(),
        )
        assertFalse(
            "marked family must keep sink flushDurable false until backfill",
            withTimeout(5_000) { sink.flushDurable(500L) },
        )
    }

    /**
     * P2-3b: a sealed sink fails a reasoning write fast (same handoff pin:
     * the failed write marks INTERRUPTED, flushDurable stays false until
     * reopen INTERRUPTED backfill owns the repair — same ownership as P2-3a).
     * Same known limitation as P2-3a: no reopen second half is run here.
     */
    @Test fun sealedSink_reasoningWriteFailsFast() = runBlocking {
        val sink = OrderedTranscriptSink(NoopSink())
        sink.shutdown(500L)?.join()
        assertTrue(sink.isSealed())
        var failed = false
        try {
            withTimeout(5_000) {
                sink.onTurnSucceeded("r-rs", "hi", hasReasoning = true)
            }
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("sealed reasoning write must fail fast, never silently park", failed)
        assertTrue(
            "failed sealed write must mark INTERRUPTED (uncompensated until backfill)",
            sink.hasDurableFailures(),
        )
        assertFalse(
            "marked family must keep sink flushDurable false until backfill",
            withTimeout(5_000) { sink.flushDurable(500L) },
        )
    }

    // ---- P2-2 pair (b): immediate-cancel compensationTimeout, equal budgets ----

    /**
     * P2-2b positive: cancelSettle(500)==compensation(500); the parked
     * terminal lands inside the fence via gate release -> the waiter hands
     * off (REVOKED/COMPENSATED, never stuck PENDING) and flushDurable is
     * true with no dangling family (never true-and-dangling either way).
     */
    @Test fun equalBudget_immediateCancel_earlyTerminalHandsOff() = runBlocking {
        val inner = FakeSessionStore()
        val store = Stage3ParkingStore(inner)
        val sid = inner.createSession("t", "m")
        val n = AtomicInteger(0)
        val c = controller(
            HangingProvider(), SessionTranscriptSink(store, sid), n,
            settleMs = 500L, compensationMs = 500L,
        )
        val drive = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val admission = drive.async { c.startOrEnqueue("q") }
            val started = withTimeout(10_000) { admission.await() }
            assertTrue(started is TurnStart.Started)
            awaitCond(10_000) { c.uiState.value.messages.any { it.text == "half" } }
            // Park-before-cancel: the terminal must be SUBMITTING (pending in
            // the wedged store) before cancel races it — otherwise no receipt.
            check(store.entered.await(5, TimeUnit.SECONDS)) { "terminal must park SUBMITTING before cancel" }
            c.cancel()
            awaitCond(10_000) { c.hasUnsettledCancelReceipts() || c.cancelReceiptStates().isNotEmpty() }
            // Land inside the shared 500==500 fence.
            store.releaseParked()
            awaitCond(15_000) {
                val s = c.cancelReceiptStates().values.toList()
                s.isNotEmpty() && s.none { it == "PENDING" || it == "CLAIMED" }
            }
            val states = c.cancelReceiptStates().values.toList()
            assertTrue("early terminal must hand off, was $states", states.none { it == "SEALED" })
            awaitCond(15_000) { !c.hasActiveHost() }
            val durable = withTimeout(10_000) { c.flushDurable() }
            assertTrue("early terminal must drain durable, states=$states", durable)
            assertFalse(
                "never true-and-dangling: durable=$durable dangling=${findDanglingRunIds(rows(inner, sid))}",
                durable && findDanglingRunIds(rows(inner, sid)).isNotEmpty(),
            )
        } finally {
            store.releaseParked()
            c.close()
        }
    }

    /**
     * P2-2b negative: same 500==500 budgets with production-shape rounds
     * (60): the waiter is still PENDING past the 500ms compensation bound
     * (pinned at 600ms — deterministic, not a timing guess: round exhaustion
     * with 50→500ms capped backoff lands at ~29s, so PENDING at 600ms can
     * only mean the terminal truly held past the fence), then converges
     * SEALED with the family dangling; flushDurable stays false until reopen
     * backfill. The ~30s convergence is the honest cost of a true
     * fence-boundary pin (a 3-round cap would seal round-exhausted at
     * ~150ms, before the fence — never used here).
     */
    @Test fun equalBudget_immediateCancel_lateTerminalSeals() = runBlocking {
        val inner = FakeSessionStore()
        val store = Stage3ParkingStore(inner)
        val sid = inner.createSession("t", "m")
        val n = AtomicInteger(0)
        // Production-shape rounds so the waiter genuinely outlives the 500ms
        // fence; SEALED then comes from round exhaustion at ~29s.
        val c = controller(
            HangingProvider(), SessionTranscriptSink(store, sid), n,
            settleMs = 500L, compensationMs = 500L, maxRounds = 60,
        )
        try {
            val admission = CoroutineScope(SupervisorJob() + Dispatchers.Default).async {
                c.startOrEnqueue("q")
            }
            val started = withTimeout(10_000) { admission.await() }
            assertTrue(started is TurnStart.Started)
            awaitCond(10_000) { c.uiState.value.messages.any { it.text == "half" } }
            // Park-before-cancel: the terminal must be SUBMITTING before the racing cancel.
            check(store.entered.await(5, TimeUnit.SECONDS)) { "terminal must park SUBMITTING before cancel" }
            c.cancel()
            awaitCond(10_000) { c.hasUnsettledCancelReceipts() }
            val cancelledAt = System.currentTimeMillis()
            // Fence-boundary pin: waiter alive past the 500ms bound.
            awaitCond(10_000) { System.currentTimeMillis() - cancelledAt >= 600L }
            assertEquals(
                "waiter must survive past the 500ms fence, was ${c.cancelReceiptStates()}",
                listOf("PENDING"),
                c.cancelReceiptStates().values.toList(),
            )
            // Hold the terminal parked to round exhaustion: SEALED lands at
            // ~29s with the family still dangling.
            awaitCond(90_000) { c.cancelReceiptStates().values.any { it == "SEALED" } }
            assertTrue(
                "late terminal must stay visible as uncompensated SEALED",
                c.hasSealedUncompensatedReceipts(),
            )
            assertFalse(
                "SEALED-uncompensated must keep flushDurable false",
                withTimeout(10_000) { c.flushDurable() },
            )
        } finally {
            store.releaseParked()
            c.close()
        }
    }

    // ---- P2-2 pair (a): orphan drain budget == timeout ----

    /**
     * P2-2a positive: a turn queued behind a SUBMITTING-parked host
     * (compensation 500 == close fence 500) drains inside the fence: the
     * release lands the doomed terminal with no INTERRUPTED, and the queued
     * orphan text is preserved for explicit recovery (Queued is never
     * auto-sent — N1 ownership; [drainQueued] reclaims it).
     */
    @Test fun equalBudget_orphanDrain_earlyCommitNoMark() = runBlocking {
        val inner = FakeSessionStore()
        val store = Stage3ParkingStore(inner)
        val sid = inner.createSession("t", "m")
        val n = AtomicInteger(0)
        val c = controller(HangingProvider(), SessionTranscriptSink(store, sid), n)
        val drive = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // Occupy the host with a SUBMITTING-parked terminal, then queue
            // one orphan behind it (Queued proves the orphan is held, never
            // started).
            val first = drive.async { c.startOrEnqueue("first") }
            assertTrue(withTimeout(10_000) { first.await() } is TurnStart.Started)
            awaitCond(10_000) { c.uiState.value.messages.any { it.text == "half" } }
            check(store.entered.await(5, TimeUnit.SECONDS)) { "first terminal must park SUBMITTING" }
            val second = drive.async { c.startOrEnqueue("second") }
            assertTrue(
                "second must queue behind the live host",
                withTimeout(10_000) { second.await() } is TurnStart.Queued,
            )
            // N1 drain-then-close: reclaim the queued orphan BEFORE close
            // (close drops the FIFO by design); the text is caller-owned
            // afterwards, never auto-sent.
            // NOTE: drainQueued clears the FIFO — capture once (a message
            // expression would drain before the condition runs).
            val reclaimed = c.drainQueued()
            assertTrue(
                "queued orphan must stay reclaimable, was $reclaimed",
                reclaimed.any { it.text == "second" },
            )
            // Close with the doomed terminal parked, then release inside the
            // bound: the doomed family commits with no INTERRUPTED.
            c.close(500L)
            store.releaseParked()
            withTimeout(10_000) { c.flushTranscript() }
            second.cancel()
            val firstRows = rows(inner, sid)
            assertTrue(
                "doomed family must commit, kinds=${firstRows.map { it.kind }}",
                firstRows.any { it.kind == "user" && it.text == "first" } &&
                    firstRows.any { it.kind == "assistant" },
            )
            val marks = firstRows.filter { it.kind == "system" && it.text.endsWith("interrupted") }
            assertTrue("early drain must not mark, was $marks", marks.isEmpty())
            val durable = withTimeout(10_000) { c.flushDurable() }
            assertFalse(
                "never true-and-dangling: durable=$durable dangling=${findDanglingRunIds(firstRows)}",
                durable && findDanglingRunIds(firstRows).isNotEmpty(),
            )
        } finally {
            store.releaseParked()
            drive.cancel()
            c.close()
        }
    }

    /**
     * P2-2a negative: same 500==500 budgets with the terminal held past
     * both — the doomed family is marked INTERRUPTED (fail-closed),
     * flushDurable stays false until reopen backfill, and the queued orphan
     * text is still preserved for explicit recovery.
     */
    @Test fun equalBudget_orphanDrain_lateCommitMarks() = runBlocking {
        val inner = FakeSessionStore()
        val store = Stage3ParkingStore(inner)
        val sid = inner.createSession("t", "m")
        val n = AtomicInteger(0)
        // Equal 500==500 budgets; the terminal park holds past both.
        val c = controller(HangingProvider(), SessionTranscriptSink(store, sid), n, compensationMs = 500L)
        val drive = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val first = drive.async { c.startOrEnqueue("first") }
            assertTrue(withTimeout(10_000) { first.await() } is TurnStart.Started)
            awaitCond(10_000) { c.uiState.value.messages.any { it.text == "half" } }
            check(store.entered.await(5, TimeUnit.SECONDS)) { "first terminal must park SUBMITTING" }
            val second = drive.async { c.startOrEnqueue("second") }
            assertTrue(
                "second must queue behind the live host",
                withTimeout(10_000) { second.await() } is TurnStart.Queued,
            )
            // N1 drain-then-close recovery pin (same as the positive).
            // NOTE: drainQueued clears the FIFO — capture once (a message
            // expression would drain before the condition runs).
            val reclaimed = c.drainQueued()
            assertTrue(
                "queued orphan must stay reclaimable, was $reclaimed",
                reclaimed.any { it.text == "second" },
            )
            // Close while the doomed terminal is parked past the 500ms bound:
            // the drain-timeout marks the family (in-memory interrupted ids;
            // the transcript ROW lands only via reopen backfill) and the
            // ledger keeps dangling (user without terminal).
            c.close(500L)
            awaitCond(15_000) { c.interruptedTranscriptRunIds().isNotEmpty() }
            val lateRows = rows(inner, sid)
            assertTrue(
                "held family must keep dangling, kinds=${lateRows.map { it.kind }}",
                findDanglingRunIds(lateRows).isNotEmpty(),
            )
            second.cancel()
            val durable = withTimeout(10_000) { c.flushDurable() }
            assertFalse("marked family must keep flushDurable false", durable)
            assertFalse(
                "never true-and-dangling: durable=$durable dangling=${findDanglingRunIds(lateRows)}",
                durable && findDanglingRunIds(lateRows).isNotEmpty(),
            )
        } finally {
            store.releaseParked()
            drive.cancel()
            c.close()
        }
    }

    // ---- P2-2 pair (c): shutdown drainTimeoutMs, equal budgets ----

    /**
     * P2-2c positive: shutdown fence compensation(500)==close(500); doomed
     * host + waiter settle inside via gate release -> barrier passes with
     * the handoff before it.
     */
    @Test fun equalBudget_shutdown_earlySettleHandsOffBeforeBarrier() = runBlocking {
        val inner = FakeSessionStore()
        val store = Stage3ParkingStore(inner)
        val sid = inner.createSession("t", "m")
        val n = AtomicInteger(0)
        val c = controller(HangingProvider(), SessionTranscriptSink(store, sid), n)
        try {
            val admission = CoroutineScope(SupervisorJob() + Dispatchers.Default).async {
                c.startOrEnqueue("q")
            }
            assertTrue(withTimeout(10_000) { admission.await() } is TurnStart.Started)
            awaitCond(10_000) { c.uiState.value.messages.any { it.text == "half" } }
            // Park-before-cancel: the terminal must be SUBMITTING before the racing cancel.
            check(store.entered.await(5, TimeUnit.SECONDS)) { "terminal must park SUBMITTING before cancel" }
            c.cancel()
            awaitCond(10_000) { c.hasUnsettledCancelReceipts() }
            // Release inside the fence, then close: handoff lands pre-barrier.
            store.releaseParked()
            c.close(500L)
            awaitCond(15_000) {
                val s = c.cancelReceiptStates().values.toList()
                s.isNotEmpty() && s.none { it == "PENDING" || it == "CLAIMED" }
            }
            val durable = withTimeout(10_000) { c.flushDurable() }
            assertFalse(
                "never true-and-dangling: durable=$durable dangling=${findDanglingRunIds(rows(inner, sid))}",
                durable && findDanglingRunIds(rows(inner, sid)).isNotEmpty(),
            )
        } finally {
            store.releaseParked()
            c.close()
        }
    }

    /**
     * P2-2c negative: same 500ms fence (compensation 500 == close 500); the
     * waiter misses the remainder budget -> stays PENDING/CLAIMED at the
     * barrier (later SEALED), never true-and-dangling.
     */
    @Test fun equalBudget_shutdown_lateWaiterMissesRemainder() = runBlocking {
        val inner = FakeSessionStore()
        val store = Stage3ParkingStore(inner)
        val sid = inner.createSession("t", "m")
        val n = AtomicInteger(0)
        val c = controller(
            HangingProvider(), SessionTranscriptSink(store, sid), n,
            maxRounds = 60,
        )
        try {
            val admission = CoroutineScope(SupervisorJob() + Dispatchers.Default).async {
                c.startOrEnqueue("q")
            }
            assertTrue(withTimeout(10_000) { admission.await() } is TurnStart.Started)
            awaitCond(10_000) { c.uiState.value.messages.any { it.text == "half" } }
            // Park-before-cancel: the terminal must be SUBMITTING before the racing cancel.
            check(store.entered.await(5, TimeUnit.SECONDS)) { "terminal must park SUBMITTING before cancel" }
            c.cancel()
            awaitCond(10_000) { c.hasUnsettledCancelReceipts() }
            // Seal the fence with the terminal still parked past it.
            c.close(500L)
            val durable = withTimeout(15_000) { c.flushDurable() }
            assertFalse("missed-remainder waiter must keep flushDurable false", durable)
            assertFalse(
                "never true-and-dangling",
                durable && findDanglingRunIds(rows(inner, sid)).isNotEmpty(),
            )
        } finally {
            store.releaseParked()
            c.close()
        }
    }

    // ---- P2-6: receipt map bound pin (current behavior) ----

    /**
     * P2-6 resource pin (Current): 161 cancels hold 161 small receipt cells
     * (attempt-bounded, never removed within the session) — and the semantic
     * is preserved: no PENDING/CLAIMED left unsettled after convergence, and
     * flushDurable == !hasSealedUncompensatedReceipts. A session-level FIFO
     * cap stays Target (see TurnController.cancelReceipts KDoc).
     * Known limitations (Stage-3, non-blocking): 1-round caps trade
     * fence-boundary realism for loop speed (the true fence hold is pinned
     * by P2-2b, not here); the reopen INTERRUPTED backfill second half is
     * never run (flushDurable false pinned, repair path not exercised).
     */
    @Test fun cancelReceipts_161CancelsAttemptBoundedAndSemanticsKept() = runBlocking {
        // Focused loop: 161 sequential completing turns, each cancelled while
        // its terminal is SUBMITTING in the wedged store (per-generation
        // park/release). Small bounds (settle 150ms, compensation 200ms, 1
        // waiter round) keep the loop bounded (~161 x sub-second, no 30s
        // stalls); every park is a gate/latch with bounded convergence polls.
        val inner2 = FakeSessionStore()
        val store2 = Stage3ParkingStore(inner2)
        val sid2 = inner2.createSession("t", "m")
        val ids = AtomicInteger(0)
        val c2 = TurnController(
            provider = HangingProvider(),
            policy = Stage3Policy(),
            transcript = SessionTranscriptSink(store2, sid2),
            retryConfig = TurnRetryConfig(maxRetries = 0),
            dispatcher = Dispatchers.Default,
            sleeper = {},
            newId = { "s3cap2-${ids.incrementAndGet() - 1}" },
            cancelSettleTimeoutMs = 150L,
            cancelCompensationTimeoutMs = 200L,
            cancelReceiptMaxRounds = 1,
        )
        try {
            repeat(161) { i ->
                store2.nextGeneration()
                val admission = withTimeout(10_000) { c2.startOrEnqueue("q-$i") }
                assertTrue("iteration $i must start, was $admission", admission is TurnStart.Started)
                // Terminal SUBMITTING before the racing cancel (else no receipt).
                check(store2.awaitParked(5, TimeUnit.SECONDS)) { "iteration $i terminal must park SUBMITTING" }
                c2.cancel()
                // The new turn's receipt must install: the map grows by one
                // per attempt (never removed within the session).
                awaitCond(10_000) { c2.cancelReceiptStates().size == i + 1 }
                // Release inside the fence so the waiter hands off; the final
                // per-iteration settle (REVOKED on commit, or 1-round SEALED)
                // never stays PENDING/CLAIMED either way.
                store2.releaseParked()
                awaitCond(15_000) {
                    val st = c2.cancelReceiptStates().values.toList()
                    st.size == i + 1 && st.none { it == "PENDING" || it == "CLAIMED" }
                }
                awaitCond(15_000) { !c2.hasActiveHost() }
            }
            val all = c2.cancelReceiptStates()
            assertEquals("one receipt per cancel attempt, never removed", 161, all.size)
            assertTrue(
                "no receipt may stay unsettled",
                all.values.none { it == "PENDING" || it == "CLAIMED" },
            )
            val durable = withTimeout(10_000) { c2.flushDurable() }
            assertEquals(
                "semantic preserved: durable iff no uncompensated SEALED",
                !c2.hasSealedUncompensatedReceipts(),
                durable,
            )
        } finally {
            store2.releaseParked()
            c2.close()
        }
    }

    // ---- P2-7: waiter installed after channel.close() ----

    /**
     * P2-7: cancel past `channel.close()` (seal-first, then cancel) never
     * leaves a receipt parked PENDING forever: the waiter converges SEALED
     * via the completion fail-safe (or no receipt installs at all), and
     * flushDurable stays false while the family dangles.
     */
    @Test fun cancelAfterChannelClose_neverLeavesUnsettled() = runBlocking {
        val inner = FakeSessionStore()
        val store = Stage3ParkingStore(inner)
        val sid = inner.createSession("t", "m")
        val n = AtomicInteger(0)
        val c = controller(
            HangingProvider(), SessionTranscriptSink(store, sid), n,
            maxRounds = 3,
        )
        try {
            val admission = CoroutineScope(SupervisorJob() + Dispatchers.Default).async {
                c.startOrEnqueue("q")
            }
            assertTrue(withTimeout(10_000) { admission.await() } is TurnStart.Started)
            awaitCond(10_000) { c.uiState.value.messages.any { it.text == "half" } }
            // Park-before-seal: the terminal must be SUBMITTING before the
            // seal so the post-seal cancel meets a pending entry (R14-1 shape).
            check(store.entered.await(5, TimeUnit.SECONDS)) { "terminal must park SUBMITTING before seal" }
            // Seal first: close fences the parked terminal, then cancel past it.
            c.close(500L)
            // Cancel past the seal must return promptly (never hang) and
            // leave nothing unsettled forever.
            withTimeout(10_000) { c.cancel() }
            awaitCond(15_000) {
                c.cancelReceiptStates().values.none { it == "PENDING" || it == "CLAIMED" }
            }
            assertFalse(
                "post-seal cancel must not hang unsettled",
                c.hasUnsettledCancelReceipts(),
            )
            assertFalse(
                "post-seal family must keep flushDurable false",
                withTimeout(10_000) { c.flushDurable() },
            )
        } finally {
            store.releaseParked()
            c.close()
        }
    }
}
