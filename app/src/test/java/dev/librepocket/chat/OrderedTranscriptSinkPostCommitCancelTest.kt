package dev.librepocket.chat

import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.SessionTranscriptSink
import dev.librepocket.session.TranscriptEvent
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
 * R5-6: post-commit auto-prune maintenance must never fail the durable core
 * ack — not even when it observes [CancellationException].
 *
 * Bug chain this locks out: the writer `Job` runs `entry.block`, which calls
 * `RoomSessionStore.appendEvent` (already committed), whose prune/`withLock`
 * tail rethrew the observed cancel; the writer then treated it as a teardown
 * (`failure` + rethrow + seal), so one already-durable row mis-reported as a
 * failed ack (INTERRUPTED + `flushDurable=false`) and killed the whole writer
 * even though prune deleted zero rows.
 *
 * Fixed rule (mirrored by [PostCommitCancelStore] below, exactly like the
 * production tail): anything thrown *after* the row commits — counter wait
 * or prune sweep, `Exception` or `CancellationException` — is best-effort
 * maintenance (log + still return the rowId). Only a cancellation *inside*
 * the transaction (rollback → row absent) still fails the ack.
 *
 * Pure JVM with [FakeSessionStore] (no Room/Robolectric): the fake replicates
 * the production tail shape (commit first, maintenance second) so the sink
 * contract is pinned even where Robolectric cannot run.
 *
 * Coverage honesty (R5-6 review): these are SINK-contract tests, NOT Room
 * coverage — [PostCommitCancelStore] never executes a line of the fixed
 * `RoomSessionStore` tail, so a future Room-side over-fix (e.g. swallowing an
 * in-transaction cancel) would still pass here. TODO(R5-6-followup): once
 * Robolectric runs (blocked locally by the JDK21 native-SQLite
 * UnsatisfiedLinkError; CI JDK 25 must run it), add a true Room test — 100
 * appends with the prune sweep throwing [CancellationException] must still
 * return the rowId, and an in-transaction cancel must still propagate.
 */
class OrderedTranscriptSinkPostCommitCancelTest {

    /**
     * Mimics the fixed [dev.librepocket.session.RoomSessionStore] tail:
     * commit the row first, then run throttled maintenance every 100 appends;
     * the maintenance observes [CancellationException] (like a prune sweep
     * racing writer teardown) but swallows it and still returns the rowId.
     */
    private class PostCommitCancelStore : FakeSessionStore() {
        var swallowedCancels = 0

        override suspend fun appendEvent(event: TranscriptEvent): Long {
            val rowId = super.appendEvent(event)
            if (events.size % 100 == 0) {
                try {
                    throw CancellationException("post-commit prune cancelled")
                } catch (e: CancellationException) {
                    // R5-6 fixed semantics: best-effort maintenance only.
                    swallowedCancels++
                }
            }
            return rowId
        }
    }

    @Test fun postCommitCancelDoesNotFailAckOrKillWriter() {
        val exec = Executors.newSingleThreadExecutor()
        try {
            val dispatcher = exec.asCoroutineDispatcher()
            val store = PostCommitCancelStore()
            val sid = runBlocking { store.createSession("t", "p/m") }
            val sink = OrderedTranscriptSink(SessionTranscriptSink(store, sid), dispatcher, capacity = 8)
            runBlocking {
                withTimeout(30_000) {
                    repeat(99) { i -> sink.onTurnStarted("post-commit-run-$i", "hello $i") }
                    // 100th append commits its row, then maintenance observes
                    // cancel — the core ack must still succeed.
                    sink.onTurnStarted("post-commit-run-99", "hello 99")
                    assertEquals(1, store.swallowedCancels)
                    assertEquals(100, store.events.size)
                    assertTrue(
                        "cancelled maintenance must leave the committed row behind",
                        store.events.any { it.runId == "post-commit-run-99" && it.kind == "user" },
                    )
                    assertTrue("barrier must still drain", sink.flush())
                    assertTrue(
                        "post-commit cancel must not poison durability",
                        sink.flushDurable(),
                    )
                    assertTrue(
                        "post-commit cancel must not mark INTERRUPTED",
                        sink.interruptedRunIds().isEmpty(),
                    )
                    assertFalse(sink.hasDurableFailures())
                    assertTrue(sink.pendingRunIds().isEmpty())
                    assertFalse(
                        "post-commit cancel must not kill the writer",
                        sink.isSealed(),
                    )

                    // Writer is alive: the next core still acks durably, and
                    // the next throttled sweep swallows the same way.
                    repeat(100) { i -> sink.onTurnStarted("post-commit-2nd-$i", "again $i") }
                    assertEquals(2, store.swallowedCancels)
                    assertEquals(200, store.events.size)
                    assertTrue(sink.flushDurable())
                    assertTrue(sink.interruptedRunIds().isEmpty())
                    assertFalse(sink.isSealed())

                    sink.shutdown()?.join()
                }
            }
        } finally {
            exec.shutdown()
        }
    }

    @Test fun inTransactionCancelStillFailsAck() {
        val exec = Executors.newSingleThreadExecutor()
        try {
            val dispatcher = exec.asCoroutineDispatcher()
            val store = object : FakeSessionStore() {
                override suspend fun appendEvent(event: TranscriptEvent): Long {
                    // In-transaction cancel: rollback, zero rows — must fail.
                    if (event.runId == "cancel-run") throw CancellationException("in-tx rollback")
                    return super.appendEvent(event)
                }
            }
            val sid = runBlocking { store.createSession("t", "p/m") }
            val sink = OrderedTranscriptSink(SessionTranscriptSink(store, sid), dispatcher, capacity = 8)
            runBlocking {
                withTimeout(30_000) {
                    try {
                        sink.onTurnStarted("cancel-run", "hello")
                        fail("in-transaction cancel must not ack as success")
                    } catch (e: CancellationException) {
                        // Explicit bounded failure (teardown seal path).
                        assertTrue("unexpected cancel message: $e", e.message != null)
                    }
                    assertTrue(
                        "rolled-back row must be absent",
                        store.events.none { it.runId == "cancel-run" },
                    )
                    assertTrue(
                        "failed core surfaces as INTERRUPTED, never a false durable ack",
                        sink.interruptedRunIds().contains("cancel-run"),
                    )
                    assertTrue(sink.pendingRunIds().isEmpty())
                    assertTrue(
                        "in-transaction cancel must poison durability",
                        sink.hasDurableFailures(),
                    )
                    assertFalse(
                        "in-transaction cancel must poison flushDurable",
                        sink.flushDurable(),
                    )
                    // The writer tore down with the entry: later cores fail
                    // fast instead of parking forever (fail-fast CE or seal).
                    assertTrue("writer must be sealed after in-tx cancel", sink.isSealed())
                    withTimeout(4_000) {
                        try {
                            sink.onTurnStarted("late-run", "late")
                            fail("post-death core must fail fast, never succeed")
                        } catch (_: CancellationException) {
                        } catch (_: Exception) {
                        }
                    }
                    assertTrue(sink.interruptedRunIds().contains("late-run"))
                    sink.shutdown()?.join()
                }
            }
        } finally {
            exec.shutdown()
        }
    }
}
