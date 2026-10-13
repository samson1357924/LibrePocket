package dev.librepocket.session

import dev.librepocket.agent.ui.chat.SnapshotBudget
import dev.librepocket.agent.ui.chat.findDanglingRunIds
import dev.librepocket.agent.ui.chat.loadModelHistory
import dev.librepocket.agent.ui.chat.snapshotSwitchHistory
import dev.librepocket.chat.OrderedTranscriptSink
import dev.librepocket.provider.ChatMessage
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.ProviderFailure
import dev.librepocket.provider.ToolSchema
import dev.librepocket.provider.requireNoReasoningHistory
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * D15-3 reasoning-marker atomicity regressions (Round15 P2, scheme A).
 *
 * A `hasReasoning=true` success/cancel persists `assistant` + `reasoning` as
 * one [SessionStore.appendEvents] batch: Room (single transaction) and the
 * in-memory fake (snapshot + rollback) commit all rows or zero, so a
 * second-row failure must leave zero rows and propagate (never an orphaned
 * `assistant` that would read back as a complete plain-text result and
 * fail-open a tool-dependent follow-up).
 */
class SessionReasoningAtomicTest {

    /** Throws on exactly the 2nd [FakeSessionStore.appendEvent] call (mid-batch fault). */
    private class FailSecondAppendStore(
        private val failure: () -> Throwable = { IOException("injected second-row fault") },
    ) : FakeSessionStore() {
        var appendCalls = 0
        override suspend fun appendEvent(event: TranscriptEvent): Long {
            appendCalls++
            if (appendCalls == 2) throw failure()
            return super.appendEvent(event)
        }
    }

    /** Counts [appendEvents] batches: pins the one-batch shape (1-2 rows, one call). */
    private class BatchCountingStore : FakeSessionStore() {
        var batchCalls = 0
        val batchSizes = ArrayList<Int>()
        override suspend fun appendEvents(events: List<TranscriptEvent>): List<Long> {
            batchCalls++
            batchSizes.add(events.size)
            return super.appendEvents(events)
        }
    }

    private suspend fun rows(store: FakeSessionStore, sid: String): List<TranscriptEvent> =
        store.loadEvents(sid)

    // -- second-row IOException: 4 hasReasoning branches ---------------------

    @Test
    fun success3Arg_secondRowIOException_leavesZeroRowsAndPropagates() = runBlocking {
        val store = FailSecondAppendStore()
        val sid = store.createSession("t", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }
        try {
            sink.onTurnSucceeded("r1", "answer", hasReasoning = true)
            fail("second-row store failure must propagate")
        } catch (_: IOException) {
            // Expected: the batch aborts.
        }
        assertTrue("orphaned assistant must never persist", rows(store, sid).isEmpty())
        assertEquals(0, store.events.size)
    }

    @Test
    fun success5Arg_secondRowIOException_leavesZeroRowsAndPropagates() = runBlocking {
        val store = FailSecondAppendStore()
        val sid = store.createSession("t", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }
        try {
            sink.onTurnSucceeded("a2", "answer", "L", 1, hasReasoning = true)
            fail("second-row store failure must propagate")
        } catch (_: IOException) {
        }
        assertTrue(rows(store, sid).isEmpty())
        // Linkage must not leak through the rollback either.
        assertEquals(0, store.events.size)
    }

    @Test
    fun cancel3Arg_secondRowIOException_leavesZeroRowsAndPropagates() = runBlocking {
        val store = FailSecondAppendStore()
        val sid = store.createSession("t", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }
        try {
            sink.onTurnCancelled("r1", "partial", hasReasoning = true)
            fail("second-row store failure must propagate")
        } catch (_: IOException) {
        }
        assertTrue(rows(store, sid).isEmpty())
        assertEquals(0, store.events.size)
    }

    @Test
    fun cancel5Arg_secondRowIOException_leavesZeroRowsAndPropagates() = runBlocking {
        val store = FailSecondAppendStore()
        val sid = store.createSession("t", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }
        try {
            sink.onTurnCancelled("a3", "partial", "L", 2, hasReasoning = true)
            fail("second-row store failure must propagate")
        } catch (_: IOException) {
        }
        assertTrue(rows(store, sid).isEmpty())
        assertEquals(0, store.events.size)
    }

    // -- second-row CancellationException: same 4 branches -------------------

    @Test
    fun success3Arg_secondRowCancel_leavesZeroRowsAndPropagates() = runBlocking {
        val store = FailSecondAppendStore({ CancellationException("writer teardown") })
        val sid = store.createSession("t", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }
        try {
            sink.onTurnSucceeded("r1", "answer", hasReasoning = true)
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            assertEquals("writer teardown", e.message)
        }
        assertTrue(rows(store, sid).isEmpty())
        assertEquals(0, store.events.size)
    }

    @Test
    fun success5Arg_secondRowCancel_leavesZeroRowsAndPropagates() = runBlocking {
        val store = FailSecondAppendStore({ CancellationException("writer teardown") })
        val sid = store.createSession("t", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }
        try {
            sink.onTurnSucceeded("a2", "answer", "L", 1, hasReasoning = true)
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
        }
        assertTrue(rows(store, sid).isEmpty())
        assertEquals(0, store.events.size)
    }

    @Test
    fun cancel3Arg_secondRowCancel_leavesZeroRowsAndPropagates() = runBlocking {
        val store = FailSecondAppendStore({ CancellationException("writer teardown") })
        val sid = store.createSession("t", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }
        try {
            sink.onTurnCancelled("r1", "partial", hasReasoning = true)
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
        }
        assertTrue(rows(store, sid).isEmpty())
        assertEquals(0, store.events.size)
    }

    @Test
    fun cancel5Arg_secondRowCancel_leavesZeroRowsAndPropagates() = runBlocking {
        val store = FailSecondAppendStore({ CancellationException("writer teardown") })
        val sid = store.createSession("t", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }
        try {
            sink.onTurnCancelled("a3", "partial", "L", 2, hasReasoning = true)
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
        }
        assertTrue(rows(store, sid).isEmpty())
        assertEquals(0, store.events.size)
    }

    // -- batch shape: one appendEvents call carrying 1-2 rows ----------------

    @Test
    fun reasoningWrites_goThroughSingleBatch() = runBlocking {
        val store = BatchCountingStore()
        val sid = store.createSession("t", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        sink.onTurnSucceeded("r1", "plain", hasReasoning = false)
        assertEquals(listOf(1), store.batchSizes)

        sink.onTurnSucceeded("r2", "thought", hasReasoning = true)
        assertEquals(listOf(1, 2), store.batchSizes)

        sink.onTurnSucceeded("a3", "thought", "L", 0, hasReasoning = true)
        assertEquals(listOf(1, 2, 2), store.batchSizes)

        sink.onTurnCancelled("r4", "part", hasReasoning = false)
        assertEquals(listOf(1, 2, 2, 1), store.batchSizes)

        sink.onTurnCancelled("a5", "part", "L", 1, hasReasoning = true)
        assertEquals(listOf(1, 2, 2, 1, 2), store.batchSizes)

        assertEquals(5, store.batchCalls)
        assertEquals(
            listOf("assistant", "assistant", "reasoning", "assistant", "reasoning", "assistant", "assistant", "reasoning"),
            store.events.map { it.kind },
        )
        // Marker linkage: the payload-free `reasoning` row inherits its
        // assistant's runId / parentRunId / attemptIndex / isFinal verbatim
        // (empty text, flag-only).
        val markers = store.events.filter { it.kind == "reasoning" }
        assertEquals(listOf("r2", "a3", "a5"), markers.map { it.runId })
        assertTrue("reasoning marker must stay payload-free", markers.all { it.text.isEmpty() })
        assertTrue("reasoning marker must stay final", markers.all { it.isFinal })
        assertEquals(listOf(null, "L", "L"), markers.map { it.parentRunId })
        assertEquals(listOf(null, 0, 1), markers.map { it.attemptIndex })
        val assistantsByRun = store.events.filter { it.kind == "assistant" }.associateBy { it.runId }
        for (marker in markers) {
            val assistant = assistantsByRun.getValue(marker.runId)
            assertEquals(assistant.parentRunId, marker.parentRunId)
            assertEquals(assistant.attemptIndex, marker.attemptIndex)
            assertEquals(assistant.isFinal, marker.isFinal)
        }
    }

    // -- writer durability: batch failure poisons flushDurable --------------

    @Test
    fun orderedWriter_batchFailure_failsFlushDurable() {
        val exec = Executors.newSingleThreadExecutor()
        val dispatcher = exec.asCoroutineDispatcher()
        try {
            runBlocking {
                withTimeout(10_000) {
                    val store = FailSecondAppendStore()
                    val sid = store.createSession("t", "m")
                    val ordered = OrderedTranscriptSink(SessionTranscriptSink(store, sid) { 42L }, dispatcher)
                    try {
                        ordered.onTurnSucceeded("r1", "answer", hasReasoning = true)
                        fail("writer must fail the ack on a batch abort")
                    } catch (_: IOException) {
                    }
                    assertTrue("zero rows after rollback", rows(store, sid).isEmpty())
                    assertEquals(0, store.events.size)
                    assertTrue(ordered.hasDurableFailures())
                    assertFalse("flushDurable must report the batch abort", ordered.flushDurable())
                    ordered.shutdown()?.join()
                }
            }
        } finally {
            dispatcher.close()
            exec.shutdown()
        }
    }

    // -- kill/reopen: pre-fix orphan stays a complete result (compat) --------

    @Test
    fun legacyOrphanAssistant_withoutMarker_readsAsCompleteResult() = runBlocking {
        // Pre-fix / pre-marker ledger: user + final assistant, no reasoning row.
        val store = FakeSessionStore()
        val sid = store.createSession("t", "m")
        store.appendEvent(
            TranscriptEvent(sessionId = sid, runId = "r1", kind = "user", text = "q", createdAt = 1L),
        )
        store.appendEvent(
            TranscriptEvent(
                sessionId = sid, runId = "r1", kind = "assistant", text = "a",
                createdAt = 2L, isFinal = true,
            ),
        )
        // Kill/reopen compat: a final assistant closes the family — never a
        // false INTERRUPTED — and resumes provider-visible fail-open
        // (hasReasoning=false, plain-text continuation).
        assertTrue(findDanglingRunIds(store.loadEvents(sid)).isEmpty())
        val history = loadModelHistory(store, sid)
        assertEquals(2, history.messages.size)
        val assistant = history.messages.first { it.role == "assistant" }
        assertEquals("a", assistant.text)
        assertFalse(assistant.hasReasoning)
    }

    // -- model-switch fork: marker rides the snapshot; withheld carries none -

    @Test
    fun sameOriginFork_carriesReasoningMarker() = runBlocking {
        val store = FakeSessionStore()
        val oldId = store.createSession("t", "p/m1")
        val sink = SessionTranscriptSink(store, oldId) { 1L }
        sink.onTurnStarted("r0", "q0")
        sink.onTurnSucceeded("r0", "a0", hasReasoning = true)

        val newId = store.createSession("t", "p/m2")
        snapshotSwitchHistory(store, oldId, newId, "m1", "m2", historyWithheld = false)

        val forked = store.loadEvents(newId)
        assertTrue(forked.any { it.kind == "reasoning" && it.runId == "r0" && it.text.isEmpty() })
        val history = loadModelHistory(store, newId)
        val assistant = history.messages.first { it.role == "assistant" }
        assertTrue("forked reasoning origin must keep its flag", assistant.hasReasoning)
    }

    @Test
    fun withheldFork_carriesNoHistoryRows() = runBlocking {
        val store = FakeSessionStore()
        val oldId = store.createSession("t", "p/m1")
        val sink = SessionTranscriptSink(store, oldId) { 1L }
        sink.onTurnStarted("r0", "q0")
        sink.onTurnSucceeded("r0", "a0", hasReasoning = true)

        val newId = store.createSession("t", "p/m2")
        val copied = snapshotSwitchHistory(store, oldId, newId, "m1", "m2", historyWithheld = true)

        assertEquals(0, copied)
        val forked = store.loadEvents(newId)
        assertTrue(forked.none { it.kind == "user" || it.kind == "assistant" || it.kind == "reasoning" })
        assertTrue(forked.any { it.kind == "system" && it.text.startsWith("model switched ") })
    }

    // -- CAP boundary: marker outside the window resumes fail-open -----------

    @Test
    fun capBoundary_markerOutsideWindow_resumesFailOpen() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("t", "m")
        // Reordered marker: the orphan marker for run "cap-old" lands at seq 1,
        // far below the assistant it flags. After the filler below pushes it
        // outside the HISTORY_LOAD_CAP scan window, the kept assistant resumes
        // provider-visible fail-open (hasReasoning=false).
        store.appendEvent(
            TranscriptEvent(sessionId = sid, runId = "cap-old", kind = "reasoning", text = "", createdAt = 1L),
        )
        val cap = SnapshotBudget.HISTORY_LOAD_CAP
        var seq = 1
        // Fill exactly CAP qualifying rows (user/assistant, non-partial).
        val turns = cap / 2
        for (i in 0 until turns) {
            seq++
            store.appendEvent(
                TranscriptEvent(sessionId = sid, runId = "fill-$i", kind = "user", text = "q$i", createdAt = seq.toLong()),
            )
            seq++
            store.appendEvent(
                TranscriptEvent(sessionId = sid, runId = "fill-$i", kind = "assistant", text = "a$i", createdAt = seq.toLong()),
            )
        }
        // Newest assistant reuses the orphan runId: kept (newest), but its
        // marker at seq 1 is outside the backward scan window.
        store.appendEvent(
            TranscriptEvent(sessionId = sid, runId = "cap-user", kind = "user", text = "q-old", createdAt = 9_000L),
        )
        store.appendEvent(
            TranscriptEvent(sessionId = sid, runId = "cap-old", kind = "assistant", text = "old answer", createdAt = 9_001L),
        )
        // Control: a recent reasoning turn inside the window keeps its flag.
        store.appendEvent(
            TranscriptEvent(sessionId = sid, runId = "cap-new", kind = "user", text = "q-new", createdAt = 9_002L),
        )
        // Direct batch (bypasses the sink) keeps both rows adjacent.
        store.appendEvents(
            listOf(
                TranscriptEvent(sessionId = sid, runId = "cap-new", kind = "assistant", text = "new answer", createdAt = 9_003L),
                TranscriptEvent(sessionId = sid, runId = "cap-new", kind = "reasoning", text = "", createdAt = 9_003L),
            ),
        )

        val history = loadModelHistory(store, sid)
        assertTrue("cap truncation must be observable", history.droppedCount > 0)
        val old = history.messages.firstOrNull { it.text == "old answer" }
        assertTrue("old assistant must still be inside the window", old != null)
        assertFalse("marker outside the window resumes fail-open", requireNotNull(old).hasReasoning)
        val fresh = history.messages.firstOrNull { it.text == "new answer" }
        assertTrue(requireNotNull(fresh).hasReasoning)
    }

    // -- provider gate: lost flag fail-opens on tool-dependent turns ---------

    @Test
    fun providerGate_missingFlagFailOpensOnToolDependent() {
        val tools = listOf(ToolSchema("t", "d", "{}"))
        val flagged = ChatRequest(
            model = "m",
            messages = listOf(
                ChatMessage("user", "hi"),
                ChatMessage("assistant", "thinking trace", hasReasoning = true),
            ),
            tools = tools,
        )
        try {
            requireNoReasoningHistory(flagged)
            fail("tool-dependent reasoning history must fail closed")
        } catch (e: ProviderFailure) {
            assertFalse(e.retryable)
            assertEquals("THINKING_ROUND_TRIP_NOT_SUPPORTED", e.message)
        }
        // The orphan-ledger shape (flag lost): the same turn replays as plain
        // text and the tool-dependent gate mis-passes — provider-visible
        // fail-open, pinned here so the ledger fix above stays load-bearing.
        val unflagged = ChatRequest(
            model = "m",
            messages = listOf(
                ChatMessage("user", "hi"),
                ChatMessage("assistant", "thinking trace", hasReasoning = false),
            ),
            tools = tools,
        )
        requireNoReasoningHistory(unflagged)
    }
}
