package dev.librepocket.session

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Non-atomic store fallback probe (owner P1 repro shape): routes every row
 * through the real [FakeSessionStore.appendEvent] shaping but uses the
 * [SessionStore] default sequential [SessionStore.appendEvents] loop (no
 * snapshot / rollback) with [IOException] injected at the given 1-based
 * [appendEvent] call numbers — a committed prefix behind a thrown terminal.
 */
private class SequentialFaultStore(
    private val failAtCalls: Set<Int>,
) : FakeSessionStore() {
    var calls = 0
        private set

    override suspend fun appendEvent(event: TranscriptEvent): Long {
        if (++calls in failAtCalls) throw IOException("injected fault at appendEvent call $calls")
        return super.appendEvent(event)
    }

    override suspend fun appendEvents(events: List<TranscriptEvent>): List<Long> {
        // Reproduce the SessionStore default sequential fallback by hand: each
        // row goes through the (possibly fault-injecting) appendEvent above,
        // so a mid-batch throw leaves the committed prefix behind (non-atomic).
        val ids = ArrayList<Long>(events.size)
        for (event in events) ids.add(appendEvent(event))
        return ids
    }
}

/**
 * #84 sink-level regressions for [SessionTranscriptSink.onUsage]: pre-terminal
 * calls merge last-wins by runId and flush as exactly one `system` row after
 * the attempt's terminal ack (flood-safe, capped); a post-terminal trailing call
 * keeps the legacy single-row shape unless the terminal already flushed.
 */
class SessionTranscriptSinkUsageTest {

    private fun usageRows(store: FakeSessionStore): List<TranscriptEvent> =
        store.events.filter { it.kind == "system" && it.text.startsWith("usage ") }

    @Test
    fun dualPreTerminalUsageMergesToSingleRowWithFinalValues() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        // Anthropic shape: input-only first, full values on the delta.
        sink.onUsage("r1", 10, null)
        sink.onUsage("r1", 10, 20)
        sink.onTurnSucceeded("r1", "hi there", hasReasoning = false)
        sink.flushBufferedUsageBestEffort("r1")

        val rows = usageRows(store)
        assertEquals(1, rows.size)
        assertEquals("usage input=10 output=20", rows.single().text)
        assertEquals("r1", rows.single().runId)
        // S2-F2 shape gate: the usage row is a bare system row — it never
        // carries attempt linkage (parentRunId/attemptIndex), matching
        // JsonlCodec's import gate for system rows.
        assertEquals(null, rows.single().parentRunId)
        assertEquals(null, rows.single().attemptIndex)
    }

    @Test
    fun splitNullsMergeLastWins() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        sink.onUsage("r1", 10, null)
        sink.onUsage("r1", null, 20)
        sink.onTurnSucceeded("r1", "hi there", hasReasoning = false)
        sink.flushBufferedUsageBestEffort("r1")

        val rows = usageRows(store)
        assertEquals(1, rows.size)
        assertEquals("usage input=10 output=20", rows.single().text)
    }

    @Test
    fun flood100PreTerminalStillSingleRow() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        repeat(100) { i -> sink.onUsage("r1", i, i * 2) }
        sink.onTurnSucceeded("r1", "hi there", hasReasoning = false)
        sink.flushBufferedUsageBestEffort("r1")

        val rows = usageRows(store)
        assertEquals(1, rows.size)
        assertEquals("usage input=99 output=198", rows.single().text)
    }

    @Test
    fun usageFloodCapFreezesPast128() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        repeat(130) { i -> sink.onUsage("r1", i, i * 2) }
        sink.onTurnSucceeded("r1", "hi there", hasReasoning = false)
        sink.flushBufferedUsageBestEffort("r1")

        // Past MAX_USAGE_EVENTS_PER_RUN the merge freezes (never a failure,
        // never unbounded CPU): the row keeps the cap's values, still one row.
        val rows = usageRows(store)
        assertEquals(1, rows.size)
        assertEquals("usage input=127 output=254", rows.single().text)
    }

    @Test
    fun distinctRunsDoNotMerge() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        sink.onUsage("a", 1, 1)
        sink.onUsage("b", 2, 2)
        sink.onTurnSucceeded("a", "first", hasReasoning = false)
        sink.flushBufferedUsageBestEffort("a")
        sink.onTurnSucceeded("b", "second", hasReasoning = false)
        sink.flushBufferedUsageBestEffort("b")

        val rows = usageRows(store)
        assertEquals(2, rows.size)
        assertEquals("usage input=1 output=1", rows.first { it.runId == "a" }.text)
        assertEquals("usage input=2 output=2", rows.first { it.runId == "b" }.text)
    }

    @Test
    fun trailingPostTerminalUsageAppendsOnceThenDrops() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        // Legacy call order (terminal already written): the first trailing
        // call keeps the historical single-row shape, the rest rate-limit.
        sink.onTurnSucceeded("r1", "hi there", hasReasoning = false)
        sink.flushBufferedUsageBestEffort("r1")
        sink.onUsage("r1", 10, 20)
        sink.onUsage("r1", 11, 21)
        sink.onUsage("r1", 12, 22)

        val rows = usageRows(store)
        assertEquals(1, rows.size)
        assertEquals("usage input=10 output=20", rows.single().text)
    }

    @Test
    fun failedTerminalFlushesPendingUsage() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        sink.onUsage("r1", 7, null)
        sink.onUsage("r1", null, 8)
        sink.onTurnFailed("r1", "half", "boom", null, 0, true, false)
        sink.flushBufferedUsageBestEffort("r1")

        val rows = usageRows(store)
        assertEquals(1, rows.size)
        assertEquals("usage input=7 output=8", rows.single().text)
    }

    @Test
    fun cancelledTerminalFlushesPendingUsage() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        sink.onUsage("r1", 3, 4)
        sink.onTurnCancelled("r1", "partial", null, 0, false)
        sink.flushBufferedUsageBestEffort("r1")

        val rows = usageRows(store)
        assertEquals(1, rows.size)
        assertEquals("usage input=3 output=4", rows.single().text)
    }

    @Test
    fun usageCapExactBoundary128And129Freeze() = runBlocking {
        // 128 events merge fully (last wins = event 127); the 129th is
        // ignored: both freeze at (127, 254), pinning `seen <= 128`.
        for (count in listOf(128, 129)) {
            val store = FakeSessionStore()
            val sid = store.createSession("hi", "m")
            val sink = SessionTranscriptSink(store, sid) { 42L }

            repeat(count) { i -> sink.onUsage("r1", i, i * 2) }
            sink.onTurnSucceeded("r1", "hi there", hasReasoning = false)
            sink.flushBufferedUsageBestEffort("r1")

            val rows = usageRows(store)
            assertEquals("$count events still one row", 1, rows.size)
            assertEquals("usage input=127 output=254", rows.single().text)
        }
    }

    @Test
    fun distinctRunsCappedAt256DropsBestEffort() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        // 300 distinct pre-terminal runIds: only the first 256 buffer
        // (insertion order); the rest drop best-effort without throwing.
        repeat(300) { i -> sink.onUsage("r$i", i, i) }
        // A buffered run must still merge while full (the cap only gates new
        // runIds, never updates to an already-buffered one).
        sink.onUsage("r0", 100, 100)
        sink.onTurnSucceeded("r0", "kept", hasReasoning = false)
        sink.flushBufferedUsageBestEffort("r0")
        sink.onTurnSucceeded("r299", "dropped", hasReasoning = false)
        sink.flushBufferedUsageBestEffort("r299")

        val rows = usageRows(store)
        assertEquals("usage input=100 output=100", rows.first { it.runId == "r0" }.text)
        assertTrue("r299 usage must have been dropped", rows.none { it.runId == "r299" })
    }

    @Test
    fun trailingAfterFailedAppendsOnceThenDrops() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        sink.onTurnFailed("r1", "half", "boom", null, 0, true, false)
        sink.flushBufferedUsageBestEffort("r1")
        sink.onUsage("r1", 10, 20)
        sink.onUsage("r1", 11, 21)

        val rows = usageRows(store)
        assertEquals(1, rows.size)
        assertEquals("usage input=10 output=20", rows.single().text)
    }

    @Test
    fun preAndPostTerminalUsageWritesExactlyOneRow() = runBlocking {
        // Owner P2: a terminal-flushed usage consumes the trailing budget —
        // pre- plus post-terminal usage still own exactly one row.
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        sink.onUsage("r1", 1, 2)
        sink.onTurnSucceeded("r1", "ok", hasReasoning = false)
        sink.flushBufferedUsageBestEffort("r1")
        sink.onUsage("r1", 3, 4)

        val rows = usageRows(store)
        assertEquals("pre+post terminal usage must own exactly one row", 1, rows.size)
        assertEquals("usage input=1 output=2", rows.single().text)
    }

    @Test
    fun successUsageFaultAfterCoreAckKeepsTerminal() = runBlocking {
        // Owner P1: usage rides outside the terminal unit — a usage-write
        // fault on a non-atomic fallback must not break the terminal.
        val store = SequentialFaultStore(setOf(2))
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        sink.onUsage("r1", 10, 20)
        // Core single assistant row is call #1, usage notice is call #2
        // (fault swallowed) — the terminal itself must not throw.
        sink.onTurnSucceeded("r1", "hi there", hasReasoning = false)
        sink.flushBufferedUsageBestEffort("r1")

        assertTrue("usage fault must be swallowed", usageRows(store).isEmpty())
        val assistants = store.events.filter { it.runId == "r1" && it.kind == "assistant" }
        assertEquals(1, assistants.size)
        assertTrue(assistants.single().isFinal)
        assertEquals("hi there", assistants.single().text)
    }

    @Test
    fun successReasoningCoreFaultMidBatchStaysDangling() = runBlocking {
        // Owner P1: the core batch keeps its pre-#84 fail-closed shape — a
        // mid-batch fault leaves at most the orphan marker, never a final
        // assistant (the marker itself is isFinal but never a family terminal).
        val store = SequentialFaultStore(setOf(2))
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        sink.onUsage("r1", 10, 20)
        try {
            // Batch is [reasoning #1, assistant #2]: the fault strands the
            // marker while the terminal throws — no committed final.
            sink.onTurnSucceeded("r1", "hi there", hasReasoning = true)
            sink.flushBufferedUsageBestEffort("r1")
            fail("core batch fault must propagate")
        } catch (_: IOException) {
        }
        val r1 = store.events.filter { it.runId == "r1" }
        assertEquals("only the orphan marker may persist", listOf("reasoning"), r1.map { it.kind })
    }

    @Test
    fun failedTerminalUsageFaultKeepsCore() = runBlocking {
        // Owner P1, failed path: core single partial is call #1, usage
        // notice is call #2 (fault swallowed) — the failure row persists.
        val store = SequentialFaultStore(setOf(2))
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        sink.onUsage("r1", 7, 8)
        sink.onTurnFailed("r1", "half", "boom", null, 0, true, false)
        sink.flushBufferedUsageBestEffort("r1")

        val partials = store.events.filter { it.runId == "r1" && it.kind == "assistant" }
        assertEquals(1, partials.size)
        assertTrue(partials.single().isPartial)
        assertTrue("usage fault must be swallowed", usageRows(store).isEmpty())
    }

    @Test
    fun cancelledTerminalUsageFaultKeepsCore() = runBlocking {
        // Owner P1, cancel path: same shape as the failed path.
        val store = SequentialFaultStore(setOf(2))
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        sink.onUsage("r1", 3, 4)
        sink.onTurnCancelled("r1", "partial", null, 0, false)
        sink.flushBufferedUsageBestEffort("r1")

        val partials = store.events.filter { it.runId == "r1" && it.kind == "assistant" }
        assertEquals(1, partials.size)
        assertTrue(partials.single().isPartial)
        assertTrue(partials.single().isFinal)
        assertTrue("usage fault must be swallowed", usageRows(store).isEmpty())
    }

    @Test
    fun failedAndCancelledCoreFaultMidBatchPropagatesWithoutFinal() = runBlocking {
        // Owner P1, racing-cancel path: fault on the 2nd row (reasoning
        // marker) leaves the non-final partial only — still dangling.
        val store = SequentialFaultStore(setOf(2))
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        try {
            sink.onTurnFailedAndCancelled("L", "A9", "half", "boom", null, 0, true)
            fail("core batch fault must propagate")
        } catch (_: IOException) {
        }
        val attempt = store.events.filter { it.runId == "A9" }
        assertEquals("only the non-final partial may persist", listOf("assistant"), attempt.map { it.kind })
        assertTrue(attempt.single().isPartial)
        assertTrue(attempt.single().isFinal.not())
        assertTrue("no terminal mark may commit behind a thrown terminal", store.events.none { it.isFinal })
    }

    @Test
    fun sameRunIdTerminalRetryPreservesUsageAfterCoreFault() = runBlocking {
        // Owner P1: pre-ack memory is not cleared — a same-runId terminal
        // retry after a core fault still owns its authoritative usage row.
        val store = SequentialFaultStore(setOf(1))
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        sink.onUsage("r1", 7, 8)
        try {
            // Core single assistant row is call #1: nothing commits.
            sink.onTurnSucceeded("r1", "hi there", hasReasoning = false)
        sink.flushBufferedUsageBestEffort("r1")
            fail("core fault must propagate")
        } catch (_: IOException) {
        }
        assertTrue(store.events.none { it.runId == "r1" })
        // Retry: assistant is call #2, usage notice is call #3 — both land.
        sink.onTurnSucceeded("r1", "hi there", hasReasoning = false)
        sink.flushBufferedUsageBestEffort("r1")

        val rows = usageRows(store)
        assertEquals(1, rows.size)
        assertEquals("usage input=7 output=8", rows.single().text)
        assertEquals(1, store.events.count { it.runId == "r1" && it.kind == "assistant" })
    }
}
