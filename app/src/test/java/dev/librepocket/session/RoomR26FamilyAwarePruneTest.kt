package dev.librepocket.session

import android.content.Context
import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.librepocket.agent.ui.chat.ChatSessionFactory
import dev.librepocket.agent.ui.chat.SessionStoreSource
import dev.librepocket.agent.ui.chat.SnapshotBudget
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.agent.ui.chat.allowKeyReadPolicy
import dev.librepocket.agent.ui.chat.dropHeadOrphanAssistantFamilies
import dev.librepocket.agent.ui.chat.familyOf
import dev.librepocket.agent.ui.chat.findDanglingRunIds
import dev.librepocket.agent.ui.chat.loadModelHistory
import dev.librepocket.agent.ui.chat.loadModelHistoryScan
import dev.librepocket.agent.ui.chat.loadSwitchSnapshotEvents
import dev.librepocket.agent.ui.chat.snapshotSwitchHistory
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
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
 * R26-1 family-aware prune + read-side head trim (Room, Robolectric).
 *
 * Prune keep-newest must never strand a user-less assistant: a cut that splits
 * a user/assistant family (marker-first implies user-less, as the user row is
 * older than its marker) deletes the whole head family, so kept starts at a
 * user boundary. Readers drop head orphans when truncation/prune evidence
 * exists (model) or unconditionally (snapshot), and scan-then-count uses
 * before/after max with pre-published suspect (F4).
 *
 * Suspect is per-sweep, NOT sticky: only a sweep with `deleted>0` holds the
 * flag; a clean sweep clears it, delete clears it, appends never clear it. A
 * DAO throw removes the pre-published flag unless a prior sweep had set it,
 * then rethrows. Concurrent sweeps serialize on the event-cap mutex
 * (last-finisher-wins, deterministic for same-outcome overlaps).
 *
 * Scope: `crossStoreInstance_sameDb_noFailOpen` covers a second store over
 * the SAME open handle (memory flag dropped, shape gone); true process
 * restart (close → reopen the DB FILE → fresh store → true `Factory.open`)
 * is covered by `fileBacked_restart_noFailOpen`. Pre-R26 crafted orphans are
 * pinned compat-then-swept in `preR26Orphan_freshStoreCompat_thenSweepClears`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomR26FamilyAwarePruneTest {

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

    private suspend fun importRows(rows: List<TranscriptEvent>): Pair<RoomSessionStore, String> {
        val store = openStore()
        val tmpDir = Files.createTempDirectory("r26-test").toFile()
        try {
            val src = File(tmpDir, "seed.jsonl")
            src.writeText(rows.joinToString("\n") { JsonlCodec.encode(it) } + "\n", Charsets.UTF_8)
            val sid = store.importJsonl(src)
            return store to sid
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    private fun assertNoHeadOrphan(events: List<TranscriptEvent>, what: String) {
        val head = events.firstOrNull { it.kind == "user" || it.kind == "assistant" } ?: return
        assertTrue("$what kept must start at a user boundary, got ${head.kind}", head.kind == "user")
        for (a in events.filter { it.kind == "assistant" }) {
            val fam = familyOf(a)
            assertTrue(
                "$what orphan assistant without user family (runId=${a.runId})",
                events.any { it.kind == "user" && familyOf(it) == fam },
            )
        }
    }

    @Test
    fun pruneUserSplit_keptStartsWithUser_noOrphanInModelAndSnapshot() = runBlocking {
        // Head user(h)+assistant(h) + 999 pairs + tail user = 2001 raw/qualifying.
        val rows = ArrayList<TranscriptEvent>(2001)
        var seq = 1L
        rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "h", kind = "user", text = "u-h", createdAt = 1L))
        rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "h", kind = "assistant", text = "a-h", createdAt = 1L))
        for (i in 0 until 999) {
            rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "fill-$i", kind = "user", text = "u-fill-$i", createdAt = 1L))
            rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "fill-$i", kind = "assistant", text = "a-fill-$i", createdAt = 1L))
        }
        rows.add(TranscriptEvent(seq = seq, sessionId = "x", runId = "tail", kind = "user", text = "u-tail", createdAt = 1L))
        val (store, sid) = importRows(rows)
        assertEquals(2001, store.countEvents(sid))
        val result = store.prune(PrunePolicy())
        // Raw 1 (user h) + fixup 1 (orphan a-h) = 2.
        assertEquals(2, result.deletedEvents)
        assertEquals(1999, store.countEvents(sid))
        val scan = loadModelHistoryScan(store, sid)
        assertNoHeadOrphan(scan.events, "model scan")
        val loaded = loadModelHistory(store, sid)
        assertEquals("user", loaded.messages.first().role)
        assertEquals("u-fill-0", loaded.messages.first().text)
        assertTrue("cut head family must be gone whole", loaded.messages.none { it.text == "a-h" })
        val snap = loadSwitchSnapshotEvents(store, sid)
        assertNoHeadOrphan(snap.events, "snapshot")
        assertTrue("snapshot must not copy orphan", snap.events.none { it.text == "a-h" })
    }

    @Test
    fun pruneMarkerSplit_wholeFamilyGone() = runBlocking {
        // Head user(h)+reasoning(h)+assistant(h) + 999 pairs + tail = 2002 raw, 2001 qualifying.
        val rows = ArrayList<TranscriptEvent>(2002)
        var seq = 1L
        rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "h", kind = "user", text = "u-h", createdAt = 1L))
        rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "h", kind = "reasoning", text = "", createdAt = 1L))
        rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "h", kind = "assistant", text = "a-h", createdAt = 1L))
        for (i in 0 until 999) {
            rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "fill-$i", kind = "user", text = "u-fill-$i", createdAt = 1L))
            rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "fill-$i", kind = "assistant", text = "a-fill-$i", createdAt = 1L))
        }
        rows.add(TranscriptEvent(seq = seq, sessionId = "x", runId = "tail", kind = "user", text = "u-tail", createdAt = 1L))
        val (store, sid) = importRows(rows)
        assertEquals(2002, store.countEvents(sid))
        val result = store.prune(PrunePolicy())
        // Raw 2 (user h + marker) + fixup 1 (orphan a-h) = 3.
        assertEquals(3, result.deletedEvents)
        assertEquals(1999, store.countEvents(sid))
        val loaded = loadModelHistory(store, sid)
        assertEquals("u-fill-0", loaded.messages.first().text)
        assertTrue(loaded.messages.none { it.text == "a-h" })
        val snap = loadSwitchSnapshotEvents(store, sid)
        assertTrue(snap.events.none { it.text == "a-h" })
        assertNoHeadOrphan(snap.events, "snapshot")
    }

    @Test
    fun crossStoreInstance_sameDb_noFailOpen() = runBlocking {
        val rows = ArrayList<TranscriptEvent>(2002)
        var seq = 1L
        rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "h", kind = "user", text = "u-h", createdAt = 1L))
        rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "h", kind = "reasoning", text = "", createdAt = 1L))
        rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "h", kind = "assistant", text = "a-h", createdAt = 1L))
        for (i in 0 until 999) {
            rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "fill-$i", kind = "user", text = "u-fill-$i", createdAt = 1L))
            rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "fill-$i", kind = "assistant", text = "a-fill-$i", createdAt = 1L))
        }
        rows.add(TranscriptEvent(seq = seq, sessionId = "x", runId = "tail", kind = "user", text = "u-tail", createdAt = 1L))
        val (first, sid) = importRows(rows)
        first.prune(PrunePolicy())
        assertTrue("first store must hold suspect", first.isPruneSuspect(sid))
        // Same-handle second instance (NOT a process restart).
        val second = RoomSessionStore(db) { now }
        // Memory-only flag does not survive (true close→file-reopen restart
        // is pinned separately in fileBacked_restart_noFailOpen); the orphan
        // shape itself is gone, so no fail-open either way.
        assertTrue("second instance starts clean (memory-only)", !second.isPruneSuspect(sid))
        val loaded = loadModelHistory(second, sid)
        assertEquals("u-fill-0", loaded.messages.first().text)
        assertTrue(loaded.messages.none { it.text == "a-h" })
        // No missing markers for the kept plain filler: clean-false is correct,
        // not a false-clean (no orphan to flag).
        assertTrue("kept plain filler must resume clean", !loaded.reasoningIncomplete)
        val scan = loadModelHistoryScan(second, sid)
        assertNoHeadOrphan(scan.events, "second store scan")
        val snap = loadSwitchSnapshotEvents(second, sid)
        assertTrue(snap.events.none { it.text == "a-h" })
    }

    @Test
    fun forkSnapshot_doesNotCopyOrphan() = runBlocking {
        // No prune: 2000 raw rows exceed the 1999 snapshot copy cap, so the
        // snapshot cut splits the head pair (drops user h, keeps a-h orphan).
        val rows = ArrayList<TranscriptEvent>(2000)
        var seq = 1L
        rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "h", kind = "user", text = "u-h", createdAt = 1L))
        rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "h", kind = "assistant", text = "a-h", createdAt = 1L))
        for (i in 0 until 999) {
            rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "fill-$i", kind = "user", text = "u-fill-$i", createdAt = 1L))
            rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "fill-$i", kind = "assistant", text = "a-fill-$i", createdAt = 1L))
        }
        val (store, sid) = importRows(rows)
        assertEquals(2000, store.countEvents(sid))
        val snap = loadSwitchSnapshotEvents(store, sid)
        assertTrue("snapshot must not keep head orphan", snap.events.none { it.text == "a-h" })
        assertNoHeadOrphan(snap.events, "snapshot")
        val newId = store.createSession("t", "m")
        val copied = snapshotSwitchHistory(store, sid, newId, "m0", "m1", historyWithheld = false)
        assertTrue("orphan family must not cross the fork", copied < SnapshotBudget.SWITCH_SNAPSHOT_COPY_CAP)
        val forked = store.loadEvents(newId, afterSeq = 0, limit = 3000)
        val qualifying = forked.filter { it.kind == "user" || it.kind == "assistant" }
        assertTrue("forked prefix must start at a user", qualifying.first().kind == "user")
        assertTrue(forked.none { it.text == "a-h" })
    }

    @Test
    fun scanThenCount_concurrentPrune_notFalseClean() = runBlocking {
        // Exactly CAP clean pairs (1000 pairs, head user). Without interference
        // the load is clean (dropped 0, no suspect, no far marker).
        val store = openStore()
        val sid = store.createSession("t", "m")
        for (i in 0 until SnapshotBudget.HISTORY_LOAD_CAP / 2) {
            store.appendEvent(
                TranscriptEvent(sessionId = sid, runId = "r$i", kind = "user", text = "u$i", createdAt = 1L),
            )
            store.appendEvent(
                TranscriptEvent(sessionId = sid, runId = "r$i", kind = "assistant", text = "a$i", createdAt = 1L),
            )
        }
        val clean = loadModelHistory(store, sid)
        assertEquals(0, clean.droppedCount)
        assertTrue("exact-cap clean ledger must stay clean", !clean.reasoningIncomplete)
        // Wrapper injects a real prune (pre-published suspect + family fixup) on
        // the first backward page, i.e. a prune-during-scan interleave.
        var injected = false
        val wrapping = object : SessionStore by store {
            override suspend fun loadEventsBefore(sessionId: String, beforeSeq: Long, limit: Int): List<TranscriptEvent> {
                if (!injected && sessionId == sid) {
                    injected = true
                    store.prune(PrunePolicy(maxEventsPerSession = SnapshotBudget.HISTORY_LOAD_CAP - 1))
                }
                return store.loadEventsBefore(sessionId, beforeSeq, limit)
            }
        }
        val loaded = loadModelHistory(wrapping, sid)
        assertTrue("prune injected during scan", injected)
        assertTrue(
            "concurrent prune during scan must never report false-clean",
            loaded.reasoningIncomplete,
        )
    }

    @Test
    fun noPrune_smallLedger_headUntouched_noExtraDelete() = runBlocking {
        val store = openStore()
        val sid = store.createSession("t", "m")
        store.appendEvent(TranscriptEvent(sessionId = sid, runId = "h", kind = "user", text = "u-h", createdAt = 1L))
        store.appendEvent(TranscriptEvent(sessionId = sid, runId = "h", kind = "assistant", text = "a-h", createdAt = 1L))
        val result = store.prune(PrunePolicy(maxEventsPerSession = 2000))
        assertEquals(0, result.deletedEvents)
        assertTrue("no delete => no suspect", !store.isPruneSuspect(sid))
        val loaded = loadModelHistory(store, sid)
        assertEquals(listOf("u-h", "a-h"), loaded.messages.map { it.text })
        assertTrue(!loaded.reasoningIncomplete)
        // Pure helper: already-user head is a no-op.
        val events = listOf(
            TranscriptEvent(sessionId = sid, runId = "h", kind = "user", text = "u-h", createdAt = 1L),
            TranscriptEvent(sessionId = sid, runId = "h", kind = "assistant", text = "a-h", createdAt = 1L),
        )
        assertEquals(events, dropHeadOrphanAssistantFamilies(events))
    }

    /**
     * P1-1 sticky-suspect (throw + per-sweep): a DAO throw after pre-publish
     * removes the flag unless a prior sweep had set it, then rethrows (never
     * silent success); a later clean sweep clears any prior flag (per-sweep,
     * NOT sticky). Old code kept the pre-published flag on throw forever
     * (sticky fail-closed turned permanent) — this test is red there.
     */
    @Test
    fun throwThenCleanSweep_clearsSuspect() = runBlocking {
        val real = openStore()
        val sid = real.createSession("t", "m")
        real.appendEvent(TranscriptEvent(sessionId = sid, runId = "r1", kind = "user", text = "u1", createdAt = 1L))
        real.appendEvent(TranscriptEvent(sessionId = sid, runId = "r1", kind = "assistant", text = "a1", createdAt = 1L))
        real.appendEvent(TranscriptEvent(sessionId = sid, runId = "r2", kind = "user", text = "u2", createdAt = 1L))
        val throwing = ThrowingDao(db.sessionDao())
        val store = RoomSessionStore(
            object : LibrePocketDb() {
                override fun sessionDao(): SessionDao = throwing
                // Room 2.8 RoomDatabase abstract members unused by this
                // DAO-delegating fake (never opened, only sessionDao() read).
                override fun createInvalidationTracker(): InvalidationTracker =
                    throw UnsupportedOperationException("fake db: no tracker")
                override fun clearAllTables() = Unit
            },
        ) { now }
        // Throw with no prior suspect: rethrows, flag NOT left behind.
        throwing.failDelete = true
        try {
            store.prune(PrunePolicy(maxEventsPerSession = 2))
            fail("prune must rethrow the DAO failure")
        } catch (_: RuntimeException) {
            // Expected ("boom-delete").
        }
        assertFalse("throw with no prior evidence must not stick the flag", store.isPruneSuspect(sid))
        // Healthy sweep deletes => flag set (throw left no residue).
        throwing.failDelete = false
        val deleted = store.prune(PrunePolicy(maxEventsPerSession = 2))
        // Raw 1 (u1) + fixup 1 (orphaned a1) = 2.
        assertEquals(2, deleted.deletedEvents)
        assertTrue(store.isPruneSuspect(sid))
        // Throw WITH prior suspect: keeps the proven flag, still rethrows.
        throwing.failDelete = true
        try {
            store.prune(PrunePolicy(maxEventsPerSession = 2))
            fail("prune must rethrow the DAO failure")
        } catch (_: RuntimeException) {
            // Expected.
        }
        assertTrue("throw must keep a previously proven flag (fail-closed)", store.isPruneSuspect(sid))
        // Clean sweep clears the prior flag (per-sweep, NOT sticky).
        throwing.failDelete = false
        val clean = store.prune(PrunePolicy(maxEventsPerSession = 2000))
        assertEquals(0, clean.deletedEvents)
        assertFalse("clean sweep must clear a prior flag", store.isPruneSuspect(sid))
    }

    /**
     * P1-1 prune×prune concurrency: overlapping sweeps serialize on the
     * event-cap mutex, so the flag always mirrors the last COMPLETED sweep.
     * Both overlaps here are same-outcome (deterministic): all-clean, and
     * prunable-where-every-finisher-but-the-first-is-clean (last finisher is
     * necessarily clean => flag false). Mixed-outcome sequential order still
     * decides the flag (last-writer-wins, documented in the store KDoc).
     */
    @Test
    fun concurrentPrunes_sameOutcome_deterministic() = runBlocking {
        val store = openStore()
        val sid = store.createSession("t", "m")
        store.appendEvent(TranscriptEvent(sessionId = sid, runId = "r1", kind = "user", text = "u1", createdAt = 1L))
        store.appendEvent(TranscriptEvent(sessionId = sid, runId = "r1", kind = "assistant", text = "a1", createdAt = 1L))
        val cleans = (1..8).map {
            async(Dispatchers.IO) { store.prune(PrunePolicy(maxEventsPerSession = 2000)) }
        }.awaitAll()
        assertTrue("all-clean overlap must delete nothing", cleans.all { it.deletedEvents == 0 })
        assertFalse(store.isPruneSuspect(sid))
        // Prunable overlap: head user+assistant + 999 pairs + tail = 2001 rows.
        val sid2 = store.createSession("t", "m")
        store.appendEvent(TranscriptEvent(sessionId = sid2, runId = "h", kind = "user", text = "u-h", createdAt = 1L))
        store.appendEvent(TranscriptEvent(sessionId = sid2, runId = "h", kind = "assistant", text = "a-h", createdAt = 1L))
        for (i in 0 until 999) {
            store.appendEvent(
                TranscriptEvent(sessionId = sid2, runId = "fill-$i", kind = "user", text = "u-fill-$i", createdAt = 1L),
            )
            store.appendEvent(
                TranscriptEvent(sessionId = sid2, runId = "fill-$i", kind = "assistant", text = "a-fill-$i", createdAt = 1L),
            )
        }
        store.appendEvent(TranscriptEvent(sessionId = sid2, runId = "tail", kind = "user", text = "u-tail", createdAt = 1L))
        (1..8).map {
            async(Dispatchers.IO) { store.prune(PrunePolicy()) }
        }.awaitAll()
        // Exactly one sweep did the work (raw 1 + fixup 1); the rest were
        // clean, so the last finisher deterministically cleared the flag —
        // and the orphan shape is durably gone either way.
        assertEquals(1999, store.countEvents(sid2))
        assertFalse("last finisher clean => flag cleared (shape already gone)", store.isPruneSuspect(sid2))
        val scan = loadModelHistoryScan(store, sid2)
        assertNoHeadOrphan(scan.events, "concurrent prune scan")
        assertEquals(2, store.countEvents(sid))
    }

    /**
     * P1-4 true F4 interleave: a load concurrent with the DELETE-commit →
     * reconcile window observes post-delete totals. Latch blocks the sweep
     * AFTER the conditional DELETE commits but BEFORE flag reconcile/fixup:
     * pre-publish code reports suspect (green); legacy post-publish code
     * would report suspect==false with dropped==0 here (false-clean RED —
     * logic argument only, no revert run against old code was executed;
     * the old test only covered scan-then-count max, never this window).
     */
    @Test
    fun deletePublishRace_interleavedLoad_observesSuspect() = runBlocking {
        // Marker-first 2001-row ledger: raw cut deletes exactly the marker, so
        // the interleaved load sees total 2000 / scan 2000 headed by orphan
        // a-old; pre-published suspect trims a-old (head-orphan drop counted
        // honestly) → filtered 1999 → dropped==1 + suspect all-flag. Old
        // post-publish code would see suspect==false, no trim, dropped==0
        // false-clean (new 1+suspect vs old 0+clean).
        val store = openStore()
        val sid = store.createSession("t", "m")
        store.appendEvent(
            TranscriptEvent(sessionId = sid, runId = "r-old", kind = "reasoning", text = "", createdAt = 1L),
        )
        store.appendEvent(
            TranscriptEvent(sessionId = sid, runId = "r-old", kind = "assistant", text = "a-old", createdAt = 1L),
        )
        for (i in 0 until 999) {
            store.appendEvent(
                TranscriptEvent(sessionId = sid, runId = "fill-$i", kind = "user", text = "u-fill-$i", createdAt = 1L),
            )
            store.appendEvent(
                TranscriptEvent(sessionId = sid, runId = "fill-$i", kind = "assistant", text = "a-fill-$i", createdAt = 1L),
            )
        }
        store.appendEvent(TranscriptEvent(sessionId = sid, runId = "tail", kind = "user", text = "u-tail", createdAt = 1L))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        store.afterEventDeleteHookForTest = {
            entered.countDown()
            if (!release.await(15, TimeUnit.SECONDS)) throw AssertionError("prune hook timed out")
        }
        try {
            val pruning = async(Dispatchers.IO) { store.prune(PrunePolicy()) }
            assertTrue("prune must reach the post-delete hook", entered.await(15, TimeUnit.SECONDS))
            try {
                val loaded = loadModelHistory(store, sid)
                assertEquals("interleaved load must honestly count the suspect-trimmed head orphan", 1, loaded.droppedCount)
                assertTrue(
                    "load between DELETE commit and flag reconcile/fixup must still see suspect (pre-publish closes the race)",
                    loaded.reasoningIncomplete,
                )
            } finally {
                release.countDown()
            }
            val result = pruning.await()
            assertEquals(2, result.deletedEvents)
            assertTrue(store.isPruneSuspect(sid))
        } finally {
            store.afterEventDeleteHookForTest = null
        }
    }

    /**
     * P1-3 true restart evidence (FILE-backed, not same-handle): seed →
     * prune → close() → reopen the DB file → fresh store → model history +
     * snapshot + a TRUE `Factory.open` reopen (backfillInterrupted path).
     * Memory suspect is dropped by the restart, yet no user-less orphan
     * reaches any consumer (shape durably gone — not a false-clean: there is
     * nothing left to flag).
     */
    @Test
    fun fileBacked_restart_noFailOpen() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = "r26-restart-${UUID.randomUUID()}.db"
        val dbFile = ctx.getDatabasePath(name)
        fun openFileDb(): LibrePocketDb = Room.databaseBuilder(ctx, LibrePocketDb::class.java, name)
            .addMigrations(LibrePocketDb.MIGRATION_1_2, LibrePocketDb.MIGRATION_2_3, LibrePocketDb.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        val model = "preset:openai@https://api.openai.com/gpt-4o-mini"
        val db1 = openFileDb()
        val sid: String
        try {
            val store1 = RoomSessionStore(db1) { now }
            sid = store1.createSession("t", model)
            store1.appendEvent(TranscriptEvent(sessionId = sid, runId = "h", kind = "user", text = "u-h", createdAt = 1L))
            store1.appendEvent(TranscriptEvent(sessionId = sid, runId = "h", kind = "reasoning", text = "", createdAt = 1L))
            store1.appendEvent(TranscriptEvent(sessionId = sid, runId = "h", kind = "assistant", text = "a-h", createdAt = 1L))
            for (i in 0 until 999) {
                store1.appendEvent(
                    TranscriptEvent(sessionId = sid, runId = "fill-$i", kind = "user", text = "u-fill-$i", createdAt = 1L),
                )
                store1.appendEvent(
                    TranscriptEvent(sessionId = sid, runId = "fill-$i", kind = "assistant", text = "a-fill-$i", createdAt = 1L),
                )
            }
            // NOTE: no lone tail user — every user family ends with a final
            // assistant, so the Factory.open backfill below must add zero rows.
            val result = store1.prune(PrunePolicy())
            assertEquals(3, result.deletedEvents)
            assertTrue(store1.isPruneSuspect(sid))
        } finally {
            db1.close()
        }
        val db2 = openFileDb()
        try {
            val store2 = RoomSessionStore(db2) { now }
            assertFalse("memory suspect must not survive restart", store2.isPruneSuspect(sid))
            val loaded = loadModelHistory(store2, sid)
            assertEquals(0, loaded.droppedCount)
            assertFalse("shape gone => clean resume is correct, not false-clean", loaded.reasoningIncomplete)
            assertEquals("u-fill-0", loaded.messages.first().text)
            assertTrue(loaded.messages.none { it.text == "a-h" })
            val snap = loadSwitchSnapshotEvents(store2, sid)
            assertTrue(snap.events.none { it.text == "a-h" })
            assertNoHeadOrphan(snap.events, "restart snapshot")
            // True Factory.open reopen (same-origin: backfill scan + hydrate).
            val before = store2.countEvents(sid)
            val factory = ChatSessionFactory(
                policy = allowKeyReadPolicy(),
                vaultSource = VaultSource {
                    val vault = EncryptedPrefsVault(InMemoryPrefs())
                    vault.putKey("preset:openai", "test-key-12345678".toCharArray())
                    vault
                },
                buildProvider = { _, _ -> R26NoopProvider() },
                sessionStores = object : SessionStoreSource {
                    override suspend fun store() = store2
                },
            )
            val endpoint = EndpointConfig(
                providerId = "preset:openai",
                presetId = "openai",
                label = "OpenAI",
                baseUrl = "https://api.openai.com/v1",
                protocol = ProviderProtocol.CHAT_COMPLETIONS,
                model = "gpt-4o-mini",
                apiKeyRef = "provider_key/preset:openai",
            )
            val opened = factory.open(endpoint, sid)
            try {
                assertEquals(0, opened.historyDroppedCount)
                assertFalse(opened.historyWithheld)
            } finally {
                opened.session.close()
            }
            assertEquals("reopen must add zero rows (no dangling family)", before, store2.countEvents(sid))
        } finally {
            db2.close()
            dbFile.delete()
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
        }
    }

    /**
     * P1-3 pre-R26 orphan restart pin: a head-assistant orphan imported with
     * NO prune reads back compat (orphan present, clean — KNOWN LIMITATION,
     * no truncation evidence and no suspect for readers to act on); the next
     * `deletes>0` sweep then removes the head family whole.
     */
    @Test
    fun preR26Orphan_freshStoreCompat_thenSweepClears() = runBlocking {
        val rows = ArrayList<TranscriptEvent>(12)
        var seq = 1L
        rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "pre26", kind = "assistant", text = "a-pre26", createdAt = 1L))
        for (i in 0 until 5) {
            rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "f$i", kind = "user", text = "u-f$i", createdAt = 1L))
            rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "f$i", kind = "assistant", text = "a-f$i", createdAt = 1L))
        }
        rows.add(TranscriptEvent(seq = seq, sessionId = "x", runId = "tail", kind = "user", text = "u-tail", createdAt = 1L))
        val (_, sid) = importRows(rows)
        // Fresh store (restart-equivalent memory state): no evidence, so the
        // pre-R26 orphan replays compat — pinned known limitation.
        val fresh = RoomSessionStore(db) { now }
        assertFalse(fresh.isPruneSuspect(sid))
        val compat = loadModelHistory(fresh, sid)
        assertEquals(0, compat.droppedCount)
        assertFalse("pre-R26 orphan with no evidence replays compat (known limitation)", compat.reasoningIncomplete)
        assertEquals("a-pre26", compat.messages.first().text)
        // Next deletes>0 sweep clears the head family whole (imported orphan
        // a-pre26 + raw-cut victim u-f0 go; a-f0 orphan goes with the fixup).
        val result = fresh.prune(PrunePolicy(maxEventsPerSession = 10))
        assertEquals(3, result.deletedEvents)
        assertEquals(9, fresh.countEvents(sid))
        assertTrue(fresh.isPruneSuspect(sid))
        val loaded = loadModelHistory(fresh, sid)
        assertEquals("u-f1", loaded.messages.first().text)
        assertTrue(loaded.messages.none { it.text == "a-pre26" || it.text == "a-f0" })
        val tail = fresh.loadTailEvents(sid, 50)
        assertTrue(tail.none { it.text == "a-pre26" })
        // A later clean sweep clears the flag again (per-sweep, NOT sticky).
        val clean = fresh.prune(PrunePolicy(maxEventsPerSession = 2000))
        assertEquals(0, clean.deletedEvents)
        assertFalse(fresh.isPruneSuspect(sid))
    }

    /**
     * P1-8 negative control (conditional gate): a head-assistant orphan in a
     * SMALL ledger with a large keep (deleted==0) runs NO fixup — the orphan
     * is retained and the load stays clean. Pins that the fixup is gated on
     * `deletes>0` (production sweeps over cap always delete; crafted small
     * ledgers keep orphans by design — known limitation, not a sweep bug).
     */
    @Test
    fun headAssistant_smallLedger_largeKeep_noFixup_orphanRetained() = runBlocking {
        val store = openStore()
        val sid = store.createSession("t", "m")
        store.appendEvent(TranscriptEvent(sessionId = sid, runId = "h", kind = "assistant", text = "a-h-orphan", createdAt = 1L))
        store.appendEvent(TranscriptEvent(sessionId = sid, runId = "p", kind = "user", text = "u-p", createdAt = 1L))
        store.appendEvent(TranscriptEvent(sessionId = sid, runId = "p", kind = "assistant", text = "a-p", createdAt = 1L))
        val result = store.prune(PrunePolicy(maxEventsPerSession = 2000))
        assertEquals(0, result.deletedEvents)
        assertFalse("deleted==0 => no suspect, no fixup", store.isPruneSuspect(sid))
        assertEquals(3, store.countEvents(sid))
        val loaded = loadModelHistory(store, sid)
        assertEquals(listOf("a-h-orphan", "u-p", "a-p"), loaded.messages.map { it.text })
        assertFalse(loaded.reasoningIncomplete)
    }

    /**
     * P1-8 keep=0 full-delete path pin: everything goes, flag holds (proven
     * deletes), loads read empty but suspect.
     */
    @Test
    fun keepZero_fullDelete_pins() = runBlocking {
        val store = openStore()
        val sid = store.createSession("t", "m")
        store.appendEvent(TranscriptEvent(sessionId = sid, runId = "r", kind = "user", text = "q", createdAt = 1L))
        store.appendEvent(TranscriptEvent(sessionId = sid, runId = "r", kind = "assistant", text = "a", createdAt = 1L))
        val result = store.prune(PrunePolicy(maxEventsPerSession = 0))
        assertEquals(2, result.deletedEvents)
        assertEquals(0, store.countEvents(sid))
        assertTrue(store.isPruneSuspect(sid))
        val loaded = loadModelHistory(store, sid)
        assertTrue(loaded.messages.isEmpty())
        assertEquals(0, loaded.droppedCount)
        assertTrue("empty but proven-deleted => suspect", loaded.reasoningIncomplete)
    }

    /**
     * P1-8 attempt big-family whole-group delete: a cut through a
     * user + N attempts + retry/notices family deletes the whole group
     * (user/assistant/retry/marker rows share the logical id); the bare
     * attempt-keyed tool row survives as disclosed P2-3 DB residue (never
     * model context, never a family terminal — no false INTERRUPTED).
     */
    @Test
    fun attemptFamily_wholeGroupDeleted_plusToolResidue() = runBlocking {
        val store = openStore()
        val sid = store.createSession("t", "m")
        store.appendEvent(TranscriptEvent(sessionId = sid, runId = "L", kind = "user", text = "u-L", createdAt = 1L))
        store.appendEvent(
            TranscriptEvent(sessionId = sid, runId = "A1", parentRunId = "L", attemptIndex = 0, kind = "reasoning", text = "", createdAt = 1L),
        )
        store.appendEvent(
            TranscriptEvent(
                sessionId = sid, runId = "A1", parentRunId = "L", attemptIndex = 0,
                kind = "assistant", text = "a-partial", createdAt = 1L, isPartial = true, isFinal = false,
            ),
        )
        store.appendEvent(TranscriptEvent(sessionId = sid, runId = "L", kind = "retry", text = "attempt 1/3", createdAt = 1L))
        store.appendEvent(
            TranscriptEvent(
                sessionId = sid, runId = "A2", parentRunId = "L", attemptIndex = 1,
                kind = "assistant", text = "a-succ", createdAt = 1L,
            ),
        )
        store.appendEvent(TranscriptEvent(sessionId = sid, runId = "A2", kind = "tool", text = "[tool:t1 {}]", createdAt = 1L))
        for (i in 0 until 997) {
            store.appendEvent(
                TranscriptEvent(sessionId = sid, runId = "fill-$i", kind = "user", text = "u-fill-$i", createdAt = 1L),
            )
            store.appendEvent(
                TranscriptEvent(sessionId = sid, runId = "fill-$i", kind = "assistant", text = "a-fill-$i", createdAt = 1L),
            )
        }
        // NOTE: the ledger ends with a complete pair (no lone tail user), so
        // any dangling family below would implicate the tool residue itself.
        store.appendEvent(TranscriptEvent(sessionId = sid, runId = "pad", kind = "user", text = "u-pad", createdAt = 1L))
        store.appendEvent(TranscriptEvent(sessionId = sid, runId = "pad", kind = "assistant", text = "a-pad", createdAt = 1L))
        // 6 + 1994 + 2 = 2002 rows; keep 2000 => raw drops u(L) + marker.
        assertEquals(2002, store.countEvents(sid))
        val result = store.prune(PrunePolicy())
        // Raw 2 + fixup 3 (a-partial, retry(L), a-succ); bare tool(A2) stays.
        assertEquals(5, result.deletedEvents)
        assertEquals(1997, store.countEvents(sid))
        assertTrue(store.isPruneSuspect(sid))
        val remaining = store.loadEvents(sid, afterSeq = 0, limit = 3000)
        assertTrue(remaining.none { it.text == "u-L" || it.text == "a-partial" || it.text == "a-succ" })
        assertTrue("retry notice of the cut family goes with it", remaining.none { it.kind == "retry" })
        val tools = remaining.filter { it.kind == "tool" }
        assertEquals("bare attempt-keyed tool row is disclosed P2-3 residue", 1, tools.size)
        val loaded = loadModelHistory(store, sid)
        assertTrue(loaded.messages.none { it.text == "a-partial" || it.text == "a-succ" })
        assertTrue("tool residue never reaches model context", loaded.messages.none { it.text.startsWith("[tool:") })
        assertEquals("user", loaded.messages.first().role)
        assertTrue(
            "tool residue is not a family terminal (no false INTERRUPTED)",
            findDanglingRunIds(remaining).isEmpty(),
        )
    }

    /**
     * P1-6 guard-10 pin: 15 crafted head orphan families need more than 10
     * fixup iterations — ONE sweep removes the raw cut + 10 families and pins
     * the 11th head orphan as REMAINDER (documented cap, crafted-only;
     * production cuts split at most one family). A second `deletes>0` sweep
     * converges (note: a clean sweep would NOT run the fixup — the gate is
     * `deleted>0`, so convergence needs a deleting sweep). The pure helper
     * pins the same 10-cap (11th crafted head orphan survives one pass —
     * snapshot-fork narrow window, disclosed in P1_SPEC).
     */
    @Test
    fun elevenHeadOrphans_guardCapPinsBehavior() = runBlocking {
        val store = openStore()
        val sid = store.createSession("t", "m")
        for (i in 0 until 15) {
            store.appendEvent(
                TranscriptEvent(sessionId = sid, runId = "o$i", kind = "assistant", text = "orph-$i", createdAt = 1L),
            )
        }
        for (i in 0 until 992) {
            store.appendEvent(
                TranscriptEvent(sessionId = sid, runId = "fill-$i", kind = "user", text = "u-fill-$i", createdAt = 1L),
            )
            store.appendEvent(
                TranscriptEvent(sessionId = sid, runId = "fill-$i", kind = "assistant", text = "a-fill-$i", createdAt = 1L),
            )
        }
        store.appendEvent(TranscriptEvent(sessionId = sid, runId = "pad", kind = "user", text = "u-pad", createdAt = 1L))
        store.appendEvent(TranscriptEvent(sessionId = sid, runId = "tail", kind = "user", text = "u-tail", createdAt = 1L))
        // 15 + 1984 + 1 + 1 = 2001 rows; keep 2000 => raw drops orph-0.
        assertEquals(2001, store.countEvents(sid))
        val first = store.prune(PrunePolicy())
        // Raw 1 + 10 guard-capped families = 11; orph-11..14 remain (pinned).
        assertEquals(11, first.deletedEvents)
        assertEquals(1990, store.countEvents(sid))
        val head = store.loadEvents(sid, afterSeq = 0, limit = 3000)
            .first { it.kind == "user" || it.kind == "assistant" }
        assertEquals("guard cap pins the 11th crafted head orphan", "orph-11", head.text)
        // Pure helper pins the same cap: 11 crafted heads => 11th survives.
        val crafted = (0..10).map { i ->
            TranscriptEvent(sessionId = sid, runId = "c$i", kind = "assistant", text = "c-orph-$i", createdAt = 1L)
        } + TranscriptEvent(sessionId = sid, runId = "t", kind = "user", text = "u-t", createdAt = 1L)
        val trimmed = dropHeadOrphanAssistantFamilies(crafted)
        assertEquals(
            "pure helper keeps the same 10-cap (11th crafted head survives one pass)",
            "c-orph-10",
            trimmed.first { it.kind == "user" || it.kind == "assistant" }.text,
        )
        // Second DELETING sweep converges (keep = count - 1 forces deletes>0).
        val second = store.prune(PrunePolicy(maxEventsPerSession = 1989))
        // Raw 1 (orph-11) + fixup 3 (orph-12..14) = 4.
        assertEquals(4, second.deletedEvents)
        val scan = loadModelHistoryScan(store, sid)
        assertNoHeadOrphan(scan.events, "converged scan")
        val snap = loadSwitchSnapshotEvents(store, sid)
        assertNoHeadOrphan(snap.events, "converged snapshot")
    }
}

/**
 * P1-1 throw-path DAO double: delegates everything to the real DAO except a
 * fault-injectable [deleteEventsBeyondLimit]. Lets the test throw AFTER the
 * store pre-published suspect (the exact sticky-flag window).
 */
private class ThrowingDao(private val real: SessionDao) : SessionDao by real {
    var failDelete: Boolean = false

    override fun deleteEventsBeyondLimit(sid: String, keep: Int): Int {
        if (failDelete) throw RuntimeException("boom-delete")
        return real.deleteEventsBeyondLimit(sid, keep)
    }
}

/** No-op provider for the true `Factory.open` restart reopen (empty stream). */
private class R26NoopProvider : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    override fun stream(request: ChatRequest): Flow<StreamEvent> = emptyFlow()
    override suspend fun listModels(): List<String> = emptyList()
}
