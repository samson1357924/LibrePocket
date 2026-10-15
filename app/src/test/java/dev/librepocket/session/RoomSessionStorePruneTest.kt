package dev.librepocket.session

import androidx.test.core.app.ApplicationProvider
import dev.librepocket.chat.OrderedTranscriptSink
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R4-6: post-commit auto-prune is best-effort maintenance, never a durable
 * failure. Insert always succeeds here; the prune sweep is fault-injected at
 * the DB layer (a `BEFORE DELETE ON sessions` trigger aborts prune's
 * stale-session sweep while inserts/updates keep working). The 100th core
 * `onTurnStarted` ack must still succeed, its row must be durable, and
 * `interruptedRunIds` / `hasDurableFailures` / `flushDurable` must stay clean.
 *
 * Runs on the JVM under Robolectric with an in-memory Room database (same
 * style as [SessionStoreTest]); synthetic titles/texts only, no endpoint.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomSessionStorePruneTest {

    private var now: Long = 1_700_000_000_000L
    private lateinit var db: LibrePocketDb

    @After
    fun tearDown() {
        if (this::db.isInitialized) {
            db.close()
        }
    }

    @Test fun autoPruneFailureDoesNotFailDurableAck() {
        db = LibrePocketDb.openInMemory(ApplicationProvider.getApplicationContext())
        val store = RoomSessionStore(db) { now }
        val sid = runBlocking { store.createSession("t", "p/m") }
        // A second, stale session gives the auto-prune sweep something to
        // delete. Appends only touch [sid], so this row stays stale no matter
        // how many events land on [sid] (every append refreshes only its own
        // session's updatedAt).
        val stale = runBlocking { store.createSession("stale", "p/m") }
        db.sessionDao().touchSession(stale, now - 91L * 86_400_000L)
        // Fault injection: inserts/updates keep working, but any DELETE on
        // sessions — i.e. prune's stale-session sweep — aborts.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER prune_fault BEFORE DELETE ON sessions " +
                "BEGIN SELECT RAISE(ABORT, 'fault-injected prune failure'); END",
        )
        val exec = Executors.newSingleThreadExecutor()
        try {
            val sink = OrderedTranscriptSink(
                SessionTranscriptSink(store, sid),
                exec.asCoroutineDispatcher(),
                capacity = 8,
            )
            runBlocking {
                withTimeout(60_000) {
                    // 99 appends: no auto-prune sweep yet.
                    repeat(99) { i -> sink.onTurnStarted("prune-run-$i", "hello $i") }
                    // 100th append commits its row, then the auto-prune sweep
                    // fails on the stale session — the core ack must still
                    // succeed instead of surfacing the maintenance failure.
                    sink.onTurnStarted("prune-run-99", "hello 99")
                    assertEquals(100, store.countEvents(sid))
                    assertTrue("barrier must still drain", sink.flush())
                    assertTrue(
                        "prune failure must not poison durability",
                        sink.flushDurable(),
                    )
                    assertTrue(
                        "prune failure must not mark INTERRUPTED",
                        sink.interruptedRunIds().isEmpty(),
                    )
                    assertFalse(sink.hasDurableFailures())
                    assertNotNull(
                        "aborted sweep must leave the stale session behind",
                        db.sessionDao().sessionById(stale),
                    )

                    // Throttle: the failed sweep already reset the counter, so
                    // the next 100 appends run (and fail) exactly one more
                    // sweep instead of retrying hot — and every ack succeeds.
                    repeat(100) { i -> sink.onTurnStarted("prune-run-2nd-$i", "again $i") }
                    assertEquals(200, store.countEvents(sid))
                    assertTrue(sink.flushDurable())
                    assertTrue(sink.interruptedRunIds().isEmpty())

                    sink.shutdown()?.join()
                }
            }
        } finally {
            exec.shutdown()
        }
        // Recovery: with the fault removed the next sweep deletes the stale
        // session, so the earlier failure left no corrupted state behind.
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER prune_fault")
        val result = runBlocking { store.prune(PrunePolicy()) }
        assertEquals(1, result.deletedSessions)
    }
}
