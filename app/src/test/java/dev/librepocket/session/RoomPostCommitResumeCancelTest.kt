package dev.librepocket.session

import androidx.test.core.app.ApplicationProvider
import dev.librepocket.chat.OrderedTranscriptSink
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R6-4: the commit→return boundary. The `withContext(IO)` hop that carries
 * the committed rowId back to the caller is cancellable with prompt
 * cancellation — a cancel landing after the DB commit but before the resume
 * used to discard the rowId and surface a [CancellationException] that the
 * writer treated as a teardown (failure + seal + INTERRUPTED), even though
 * the row was already durable.
 *
 * The fix ([RoomSessionStore.afterCommitHookForTest] + committed-flag
 * discriminant) is exercised here against a real in-memory Room database:
 * the hook throws [CancellationException] from exactly the post-commit
 * resume window, deterministically simulating the cancel without races.
 *
 * R5-6 vs R6-4 boundary: R5-6 pinned the post-commit *maintenance tail*
 * (counter + prune sweep); every test here pins the *commit→return* hop
 * before the tail runs.
 *
 * Runs under Robolectric (in-memory Room). Verified green locally on
 * JDK21 (4/4) as well as for CI JDK 25 — if the environment regresses to
 * the old JDK21 native-SQLite UnsatisfiedLinkError, fall back to CI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomPostCommitResumeCancelTest {

    private var now: Long = 1_700_000_000_000L
    private lateinit var db: LibrePocketDb

    @After
    fun tearDown() {
        if (this::db.isInitialized) {
            db.close()
        }
    }

    private fun openStore(): RoomSessionStore {
        db = LibrePocketDb.openInMemory(ApplicationProvider.getApplicationContext())
        return RoomSessionStore(db) { now }
    }

    private fun event(sid: String, run: String, text: String = "hello") = TranscriptEvent(
        sessionId = sid,
        runId = run,
        kind = "user",
        text = text,
        createdAt = now,
    )

    @Test fun singleResumeCancelStillReturnsDurableRowId() {
        val store = openStore()
        val sid = runBlocking { store.createSession("t", "p/m") }
        val hookCalls = AtomicInteger(0)
        store.afterCommitHookForTest = {
            hookCalls.incrementAndGet()
            throw CancellationException("simulated post-commit resume cancel")
        }
        try {
            val rowId = runBlocking { store.appendEvent(event(sid, "resume-run")) }
            assertTrue("durable rowId must be returned, got $rowId", rowId > 0)
            assertEquals(1, runBlocking { store.countEvents(sid) })
            assertEquals(
                "committed row must list under its runId",
                listOf("resume-run"),
                runBlocking { store.loadEvents(sid).map { it.runId } },
            )
        } finally {
            store.afterCommitHookForTest = null
        }
        assertEquals(1, hookCalls.get())
    }

    @Test fun batchResumeCancelStillReturnsDurableRowIds() {
        val store = openStore()
        val sid = runBlocking { store.createSession("t", "p/m") }
        store.afterCommitHookForTest = {
            throw CancellationException("simulated post-commit resume cancel")
        }
        try {
            val ids = runBlocking {
                store.appendEvents(
                    listOf(
                        event(sid, "batch-run-0"),
                        event(sid, "batch-run-1"),
                        event(sid, "batch-run-2"),
                    ),
                )
            }
            assertEquals(3, ids.size)
            assertTrue("durable rowIds must be returned, got $ids", ids.all { it > 0 })
            assertEquals(
                "committed bulk must list all rows",
                listOf("batch-run-0", "batch-run-1", "batch-run-2"),
                runBlocking { store.loadEvents(sid).map { it.runId } },
            )
        } finally {
            store.afterCommitHookForTest = null
        }
    }

    @Test fun sinkLevelAckSurvivesResumeCancel() {
        val store = openStore()
        val sid = runBlocking { store.createSession("t", "p/m") }
        // One-shot: only the first commit observes the resume cancel; the
        // rest of the stream must proceed normally with the writer alive.
        val hookCalls = AtomicInteger(0)
        store.afterCommitHookForTest = {
            if (hookCalls.incrementAndGet() == 1) {
                throw CancellationException("simulated post-commit resume cancel")
            }
        }
        val exec = Executors.newSingleThreadExecutor()
        try {
            val sink = OrderedTranscriptSink(
                SessionTranscriptSink(store, sid),
                exec.asCoroutineDispatcher(),
                capacity = 8,
            )
            runBlocking {
                withTimeout(60_000) {
                    // Must ack as success despite the resume cancel — the row
                    // committed before the cancel landed.
                    sink.onTurnStarted("sink-resume-run", "hello")
                    assertEquals(1, store.countEvents(sid))
                    assertTrue("barrier must still drain", sink.flush())
                    assertTrue(
                        "resume cancel must not poison durability",
                        sink.flushDurable(),
                    )
                    assertTrue(
                        "resume cancel must not mark INTERRUPTED",
                        sink.interruptedRunIds().isEmpty(),
                    )
                    assertFalse(sink.hasDurableFailures())
                    assertFalse(
                        "resume cancel must not kill the writer",
                        sink.isSealed(),
                    )
                    sink.shutdown()?.join()
                }
            }
        } finally {
            exec.shutdown()
            store.afterCommitHookForTest = null
        }
        assertEquals(1, hookCalls.get())
    }

    @Test fun preCommitCancelStillPropagates() {
        val store = openStore()
        val sid = runBlocking { store.createSession("t", "p/m") }
        store.afterCommitHookForTest = null
        // Deterministic flag-unset branch: the calling job is cancelled
        // before the call, so the mutex acquisition inside the commit
        // region throws with nothing committed — the discriminant must
        // rethrow, never synthesize a rowId. (A blanket CE swallow would
        // either NPE on the null flag or return a bogus id and trip the
        // fail() below.) True mid-transaction cancel is additionally pinned
        // at sink level by OrderedTranscriptSinkPostCommitCancelTest.
        try {
            runBlocking {
                coroutineContext[Job]!!.cancel(CancellationException("in-tx"))
                store.appendEvent(event(sid, "cancel-run"))
                fail("pre-commit cancel must not ack as success")
            }
            fail("pre-commit cancel must propagate out of the call")
        } catch (e: CancellationException) {
            // Expected: flag unset → rethrow (only the type + absence of a
            // row are asserted; the exact CE identity is dispatcher detail).
        }
        assertEquals(
            "rolled-back row must be absent",
            0,
            runBlocking { store.countEvents(sid) },
        )
    }
}
