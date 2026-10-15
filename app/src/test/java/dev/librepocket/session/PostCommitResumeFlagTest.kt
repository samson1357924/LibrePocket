package dev.librepocket.session

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
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
 * R6-4 JVM-runnable pin of the commit→return discriminant semantics.
 *
 * Honesty note: like the R5-6 `PostCommitCancelStore`, the
 * [FlagGuardStore] below MIRRORS the production `RoomSessionStore` shape
 * (commit → synchronous [AtomicReference] publish → resume-window hook →
 * `catch (CE)` discriminant) against [FakeSessionStore]; it never executes
 * a line of the Room code, so it cannot catch a Room-side wiring mistake
 * (e.g. flag published in the wrong place). True Room coverage is
 * [RoomPostCommitResumeCancelTest] (Robolectric, in-memory Room). What this file
 * does pin, on any JDK:
 * - a CE landing after the flag is published is post-commit and must be
 *   swallowed with the durable id returned (single + batch);
 * - a CE with the flag unset (pre-commit / in-tx) must propagate and leave
 *   zero rows;
 * - at sink level, a swallowed resume cancel still acks durably without
 *   sealing the writer, while a pre-commit cancel still fails the ack.
 */
class PostCommitResumeFlagTest {

    /**
     * Same shape as the fixed `RoomSessionStore` commit region: publish a
     * non-suspend flag synchronously after the commit, run the
     * resume-window hook, and swallow a [CancellationException] iff the
     * flag is set.
     */
    private class FlagGuardStore : FakeSessionStore() {
        var hook: (() -> Unit)? = null
        /** When true, rows with this runId throw pre-commit (in-tx simulation). */
        var failBeforeCommitRunId: String? = null

        override suspend fun appendEvent(event: TranscriptEvent): Long {
            val committed = AtomicReference<Long?>(null)
            return try {
                // The fake has no cancellable suspension point, so ambient
                // cancellation cannot pre-empt it; pre-commit cancel is
                // simulated by throwing before the commit line instead.
                if (event.runId == failBeforeCommitRunId) throw CancellationException("in-tx")
                val id = super.appendEvent(event)
                committed.set(id)
                hook?.invoke()
                id
            } catch (e: CancellationException) {
                committed.get() ?: throw e
            }
        }

        override suspend fun appendEvents(events: List<TranscriptEvent>): List<Long> {
            if (events.isEmpty()) return emptyList()
            val committed = AtomicReference<List<Long>?>(null)
            return try {
                val snapshot = ArrayList(this.events)
                try {
                    val ids = ArrayList<Long>(events.size)
                    for (event in events) ids.add(super.appendEvent(event))
                    committed.set(ArrayList(ids))
                    hook?.invoke()
                    ids
                } catch (t: Throwable) {
                    if (committed.get() == null) {
                        this.events.clear()
                        this.events.addAll(snapshot)
                    }
                    throw t
                }
            } catch (e: CancellationException) {
                committed.get() ?: throw e
            }
        }
    }

    @Test fun singleResumeCancelReturnsDurableRowId() {
        val store = FlagGuardStore()
        val sid = runBlocking { store.createSession("t", "p/m") }
        store.hook = { throw CancellationException("simulated resume cancel") }
        val rowId = runBlocking {
            store.appendEvent(
                TranscriptEvent(sessionId = sid, runId = "r1", kind = "user", text = "hi", createdAt = 1),
            )
        }
        assertTrue("durable rowId must be returned, got $rowId", rowId > 0)
        assertEquals(listOf("r1"), store.events.map { it.runId })
    }

    @Test fun batchResumeCancelReturnsDurableRowIds() {
        val store = FlagGuardStore()
        val sid = runBlocking { store.createSession("t", "p/m") }
        store.hook = { throw CancellationException("simulated resume cancel") }
        val ids = runBlocking {
            store.appendEvents(
                (0..2).map {
                    TranscriptEvent(sessionId = sid, runId = "b$it", kind = "user", text = "hi", createdAt = 1)
                },
            )
        }
        assertEquals(3, ids.size)
        assertTrue(ids.all { it > 0 })
        assertEquals(listOf("b0", "b1", "b2"), store.events.map { it.runId })
    }

    @Test fun preCommitCancelPropagatesAndLeavesZeroRows() {
        val store = FlagGuardStore()
        val sid = runBlocking { store.createSession("t", "p/m") }
        store.hook = null
        store.failBeforeCommitRunId = "cancel-run"
        try {
            runBlocking {
                store.appendEvent(
                    TranscriptEvent(sessionId = sid, runId = "cancel-run", kind = "user", text = "hi", createdAt = 1),
                )
                fail("pre-commit cancel must not ack as success")
            }
            fail("pre-commit cancel must propagate out of the call")
        } catch (_: CancellationException) {
            // Expected: flag unset → rethrow.
        }
        assertTrue(
            "rolled-back row must be absent",
            store.events.none { it.runId == "cancel-run" },
        )
    }

    @Test fun sinkAckSurvivesResumeCancelButNotPreCommitCancel() {
        val exec = Executors.newSingleThreadExecutor()
        try {
            val dispatcher = exec.asCoroutineDispatcher()
            // Resume-cancel store: hook CE is post-commit → swallowed → ack.
            val durable = FlagGuardStore()
            val sid = runBlocking { durable.createSession("t", "p/m") }
            durable.hook = { throw CancellationException("simulated resume cancel") }
            val sink = dev.librepocket.chat.OrderedTranscriptSink(
                SessionTranscriptSink(durable, sid),
                dispatcher,
                capacity = 8,
            )
            runBlocking {
                withTimeout(30_000) {
                    sink.onTurnStarted("sink-run", "hello")
                    assertTrue(sink.flush())
                    assertTrue(sink.flushDurable())
                    assertTrue(sink.interruptedRunIds().isEmpty())
                    assertFalse(sink.hasDurableFailures())
                    assertFalse(sink.isSealed())
                    sink.shutdown()?.join()
                }
            }
            assertEquals(listOf("sink-run"), durable.events.map { it.runId })
        } finally {
            exec.shutdown()
        }
    }
}
