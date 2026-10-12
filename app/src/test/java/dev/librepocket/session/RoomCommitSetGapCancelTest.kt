package dev.librepocket.session

import androidx.test.core.app.ApplicationProvider
import dev.librepocket.chat.OrderedTranscriptSink
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R8-5: the COMMIT→`committed.set` gap.
 *
 * The R6-4 KDoc claimed no suspension point sits between the transaction
 * return and the committed-flag publish, so any CE at the resume must be
 * post-commit. That premise is false: [db.withTransaction][androidx.room.withTransaction]
 * is itself `suspend` — COMMIT happens inside, but the `withTransaction`
 * return is a cancellable resume point. A cancel landing after the SQLite
 * COMMIT but before the resume throws BEFORE `committed.set`, so the
 * discriminant sees a null flag and misclassifies a durable commit as a
 * pre-commit failure (rollback → ack must fail): row present, ack failed.
 *
 * Each test below cancels ONLY the innermost transaction-running Job (handed
 * to [RoomSessionStore.insideTxAfterInsertHookForTest] by production, never
 * throwing — the framework delivers the CE naturally at the next suspension)
 * and then asserts the durable outcome:
 * - pre-fix (suspend `withTransaction`): resume lands before the flag set →
 *   CE misclassified → ack fails though the row is durable → RED;
 * - post-fix (blocking `runInTransaction`): COMMIT→set stays synchronous on
 *   the IO thread with no suspension in between → the same CE lands after
 *   the flag is set → swallowed → durable id returned → GREEN.
 *
 * A hook that THREW (instead of cancelling) would stay red after the fix —
 * it fires inside the transaction, i.e. a genuine in-tx failure with
 * rollback — so manual throw is deliberately not used as acceptance.
 *
 * Runs under Robolectric (in-memory Room).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomCommitSetGapCancelTest {

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

    /** Gap-cancel hook: cancel the tx Job, never throw — framework delivers. */
    private fun gapHook(): (kotlinx.coroutines.Job?) -> Unit =
        { txJob -> txJob?.cancel(CancellationException("R8-5 gap cancel")) }

    @Test fun gapResumeCancelWithFlagUnsetButRowDurable_single() {
        val store = openStore()
        val sid = runBlocking { store.createSession("t", "p/m") }
        store.insideTxAfterInsertHookForTest = gapHook()
        try {
            val rowId = try {
                runBlocking { store.appendEvent(event(sid, "gap-run")) }
            } catch (e: CancellationException) {
                // Gap fingerprint: the row committed (SQLite COMMIT ran before
                // the resume CE) yet the ack failed (flag was still null).
                assertEquals(
                    "gap: row must be durable even though the ack failed",
                    1,
                    runBlocking { store.countEvents(sid) },
                )
                throw e
            }
            assertTrue("durable rowId must be returned, got $rowId", rowId > 0)
            assertEquals(1, runBlocking { store.countEvents(sid) })
            assertEquals(
                listOf("gap-run"),
                runBlocking { store.loadEvents(sid).map { it.runId } },
            )
        } finally {
            store.insideTxAfterInsertHookForTest = null
        }
    }

    @Test fun gapResumeCancelWithFlagUnsetButRowDurable_batch() {
        val store = openStore()
        val sid = runBlocking { store.createSession("t", "p/m") }
        store.insideTxAfterInsertHookForTest = gapHook()
        try {
            val ids = try {
                runBlocking {
                    store.appendEvents(
                        listOf(
                            event(sid, "gap-batch-0"),
                            event(sid, "gap-batch-1"),
                            event(sid, "gap-batch-2"),
                        ),
                    )
                }
            } catch (e: CancellationException) {
                assertEquals(
                    "gap: bulk rows must be durable even though the ack failed",
                    3,
                    runBlocking { store.countEvents(sid) },
                )
                throw e
            }
            assertEquals(3, ids.size)
            assertTrue("durable rowIds must be returned, got $ids", ids.all { it > 0 })
            assertEquals(
                listOf("gap-batch-0", "gap-batch-1", "gap-batch-2"),
                runBlocking { store.loadEvents(sid).map { it.runId } },
            )
        } finally {
            store.insideTxAfterInsertHookForTest = null
        }
    }

    @Test fun gapResumeCancelWithFlagUnsetButRowDurable_sink() {
        val store = openStore()
        val sid = runBlocking { store.createSession("t", "p/m") }
        // One-shot: only the first commit observes the gap cancel; the hook
        // cancels the writer's inner tx child, never the writer itself, so a
        // fixed store contains the CE inside appendEvent and the writer lives.
        var fired = false
        store.insideTxAfterInsertHookForTest = { txJob ->
            if (!fired) {
                fired = true
                txJob?.cancel(CancellationException("R8-5 gap cancel"))
            }
        }
        val exec = Executors.newSingleThreadExecutor()
        try {
            val sink = OrderedTranscriptSink(
                SessionTranscriptSink(store, sid),
                exec.asCoroutineDispatcher(),
                capacity = 8,
            )
            try {
                runBlocking {
                    withTimeout(60_000) {
                        sink.onTurnStarted("gap-sink-run", "hello")
                    }
                }
            } catch (e: CancellationException) {
                assertEquals(
                    "gap: row must be durable even though the ack failed",
                    1,
                    runBlocking { store.countEvents(sid) },
                )
                throw e
            }
            runBlocking {
                withTimeout(60_000) {
                    assertEquals(1, store.countEvents(sid))
                    assertTrue("barrier must still drain", sink.flush())
                    assertTrue(
                        "gap cancel must not poison durability",
                        sink.flushDurable(),
                    )
                    assertTrue(
                        "gap cancel must not mark INTERRUPTED",
                        sink.interruptedRunIds().isEmpty(),
                    )
                    assertFalse(sink.hasDurableFailures())
                    assertFalse(
                        "gap cancel must not kill the writer",
                        sink.isSealed(),
                    )
                    sink.shutdown()?.join()
                }
            }
        } finally {
            exec.shutdown()
            store.insideTxAfterInsertHookForTest = null
        }
        assertTrue("gap hook must have fired once", fired)
    }
}
