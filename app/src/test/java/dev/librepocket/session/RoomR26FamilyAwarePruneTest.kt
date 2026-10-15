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
import java.util.concurrent.Callable
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
 * Suspect is per-sweep, NOT sticky: a deleting sweep (`deleted>0`) holds
 * the flag; a clean sweep (`deleted==0` AND user/empty post-fixup head)
 * clears it; a `deleted==0` residue sweep (still-assistant head:
 * blank-family or famDeleted<=0 no-progress) keeps it fail-closed; delete
 * clears it, appends never clear it. A DAO throw removes the pre-published
 * flag unless a prior sweep had set it, then rethrows. Concurrent sweeps
 * serialize on the prune event-cap mutex (`pruneEventCapMutex`,
 * last-finisher-wins by design, deterministic for same-outcome overlaps).
 *
 * Scope: `crossStoreInstance_sameDb_noFailOpen` covers a second store over
 * the SAME open handle (memory flag dropped, shape gone); true process
 * restart (close → reopen the DB FILE → fresh store → true `Factory.open`)
 * is covered by `fileBacked_restart_noFailOpen`. Pre-R26 crafted orphans are
 * pinned compat-then-swept in `preR26Orphan_freshStoreCompat_thenSweepClears`.
 *
 * Known limitations (Target, not pinned here): restart+residue stays
 * memory-only (a residue orphan after restart replays compat until the next
 * `deleted>0`/residue sweep re-flags it) and terminal/retryable multi-path
 * coverage beyond the pinned attempt-family whole-group + tool-residue
 * shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomR26FamilyAwarePruneTest {

    private var now: Long = 1_700_000_000_000L
    private lateinit var db: LibrePocketDb
    private val extraDbs = mutableListOf<LibrePocketDb>()

    @After
    fun tearDown() {
        if (this::db.isInitialized) {
            db.close()
        }
        for (extra in extraDbs) {
            try {
                extra.close()
            } catch (_: Exception) {
            }
        }
        extraDbs.clear()
    }

    private fun openStore(): RoomSessionStore {
        if (this::db.isInitialized) {
            extraDbs.add(db)
        }
        db = LibrePocketDb.openInMemory(ApplicationProvider.getApplicationContext())
        return RoomSessionStore(db) { now }
    }

    private fun seedDirectDao(targetDb: LibrePocketDb, sid: String, rows: List<TranscriptEvent>) {
        targetDb.runInTransaction(
            Callable {
                var seq = 1L
                for (e in rows) {
                    targetDb.sessionDao().insertEvent(
                        TranscriptEventEntity(
                            0, sid, seq++, e.runId, e.kind, e.text, false, 0,
                            e.createdAt, e.isPartial, e.failureReason,
                            e.parentRunId, e.attemptIndex, e.isFinal,
                        ),
                    )
                }
                Unit
            },
        )
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
                // R27-1: the per-session raw-cut + family-fixup runs in one
                // blocking Room transaction — delegate it to the real handle
                // so the ThrowingDao fault injection still lands INSIDE tx
                // semantics (throw → rollback + flag cleanup + rethrow).
                override fun <V> runInTransaction(callable: Callable<V>): V =
                    db.runInTransaction(callable)
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
     * P1-4 true F4 interleave: a load concurrent with the pre-publish → tx
     * window observes live totals with the pre-published flag held. Latch
     * blocks the sweep AFTER suspect pre-publish but BEFORE the prune tx
     * starts (R27-1: deliberately pre-tx — an in-tx hook starves concurrent
     * readers, pinning red via hook timeout instead): pre-publish code
     * reports suspect (green); legacy post-publish code would report
     * suspect==false with dropped==0 here (false-clean RED — logic argument
     * only, no revert run against old code was executed; the old test only
     * covered scan-then-count max, never this window). This is pre-publish
     * coverage over live pre-delete totals, NOT a post-delete-window
     * interleave: raw+fixup commit atomically in one tx, so no
     * delete→publish gap exists — the covered window is flag-set →
     * tx-commit.
     */
    @Test
    fun deletePublishRace_interleavedLoad_observesSuspect() = runBlocking {
        // Marker-first 2001-row ledger: the raw cut will delete exactly the
        // marker, so the interleaved load (pre-tx) sees total 2000 / scan
        // 2000 headed by orphan a-old; pre-published suspect trims a-old
        // (head-orphan drop counted honestly) → filtered 1999 → dropped==1
        // + suspect all-flag. Old post-publish code would see
        // suspect==false, no trim, dropped==0 false-clean (new 1+suspect vs
        // old 0+clean).
        // R27-2 note: seeded via import (NOT appends) — appends auto-prune
        // every 100 rows and the unconditional fixup would converge the head
        // orphan mid-seed, leaving no interleave to observe.
        val rows = ArrayList<TranscriptEvent>(2001)
        var seq = 1L
        rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "r-old", kind = "reasoning", text = "", createdAt = 1L))
        rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "r-old", kind = "assistant", text = "a-old", createdAt = 1L))
        for (i in 0 until 999) {
            rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "fill-$i", kind = "user", text = "u-fill-$i", createdAt = 1L))
            rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "fill-$i", kind = "assistant", text = "a-fill-$i", createdAt = 1L))
        }
        rows.add(TranscriptEvent(seq = seq, sessionId = "x", runId = "tail", kind = "user", text = "u-tail", createdAt = 1L))
        val (store, sid) = importRows(rows)
        assertEquals(2001, store.countEvents(sid))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        store.afterEventDeleteHookForTest = {
            entered.countDown()
            if (!release.await(15, TimeUnit.SECONDS)) throw AssertionError("prune hook timed out")
        }
        try {
            val pruning = async(Dispatchers.IO) { store.prune(PrunePolicy()) }
            assertTrue("prune must reach the pre-tx hook", entered.await(15, TimeUnit.SECONDS))
            try {
                val loaded = loadModelHistory(store, sid)
                assertEquals("interleaved load must honestly count the suspect-trimmed head orphan", 1, loaded.droppedCount)
                assertTrue(
                    "load between pre-publish and the prune tx must still see suspect (pre-publish closes the race)",
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
     * R27-2 convergent pin (replaces the P1-8 no-fixup pin): the head-only
     * fixup is UNCONDITIONAL in the same tx (no `deleted>0` gate), so even a
     * small ledger with a large keep converges — the user-less head family
     * goes in the same sweep that finds it. Old P1-8 code gated the fixup on
     * `deleted>0` and retained the orphan here (deleted 0, clean); current
     * code deletes it (deleted 1, suspect).
     */
    @Test
    fun headAssistant_smallLedger_largeKeep_converges() = runBlocking {
        val store = openStore()
        val sid = store.createSession("t", "m")
        store.appendEvent(TranscriptEvent(sessionId = sid, runId = "h", kind = "assistant", text = "a-h-orphan", createdAt = 1L))
        store.appendEvent(TranscriptEvent(sessionId = sid, runId = "p", kind = "user", text = "u-p", createdAt = 1L))
        store.appendEvent(TranscriptEvent(sessionId = sid, runId = "p", kind = "assistant", text = "a-p", createdAt = 1L))
        val result = store.prune(PrunePolicy(maxEventsPerSession = 2000))
        // Raw 0 (under keep) + fixup 1 (head orphan family h) = 1.
        assertEquals(1, result.deletedEvents)
        assertTrue("deleting sweep holds suspect", store.isPruneSuspect(sid))
        assertEquals(2, store.countEvents(sid))
        val remaining = store.loadEvents(sid, afterSeq = 0, limit = 10)
        assertTrue(remaining.none { it.text == "a-h-orphan" })
        // Suspect forces the conservative all-flag even with dropped==0.
        val loaded = loadModelHistory(store, sid)
        assertEquals(listOf("u-p", "a-p"), loaded.messages.map { it.text })
        assertTrue("convergent delete leaves suspect (fail-closed)", loaded.reasoningIncomplete)
        // Pure helper converges the same shape (small ledger, no cap).
        val events = listOf(
            TranscriptEvent(sessionId = sid, runId = "h", kind = "assistant", text = "a-h-orphan", createdAt = 1L),
            TranscriptEvent(sessionId = sid, runId = "p", kind = "user", text = "u-p", createdAt = 1L),
            TranscriptEvent(sessionId = sid, runId = "p", kind = "assistant", text = "a-p", createdAt = 1L),
        )
        val trimmed = dropHeadOrphanAssistantFamilies(events)
        assertEquals(listOf("u-p", "a-p"), trimmed.map { it.text })
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
     * R27-2 convergent pin (replaces the P1-6 guard-10 pin): the head-only
     * fixup is an unbounded while(true)+break-on-no-progress loop in the
     * same tx — ONE sweep converges arbitrary N (crafted 15-orphan scale is
     * fine; production splits at most one family). The old 10-cap (raw 1 +
     * 10 families = 11, 11th remainder pinned) is gone; current code clears
     * all 15 in one sweep (raw 1 + fixup 14 = 15). The pure helper converges
     * identically (no cap).
     */
    @Test
    fun fifteenHeadOrphans_convergentSingleSweep() = runBlocking {
        // Seeded via import (NOT appends): appends auto-prune every 100 rows
        // and the R27-2 unconditional fixup would converge the crafted head
        // orphans mid-seed, so an append-seeded count never reaches 2001.
        val rows = ArrayList<TranscriptEvent>(2001)
        var seq = 1L
        for (i in 0 until 15) {
            rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "o$i", kind = "assistant", text = "orph-$i", createdAt = 1L))
        }
        for (i in 0 until 992) {
            rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "fill-$i", kind = "user", text = "u-fill-$i", createdAt = 1L))
            rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "fill-$i", kind = "assistant", text = "a-fill-$i", createdAt = 1L))
        }
        rows.add(TranscriptEvent(seq = seq++, sessionId = "x", runId = "pad", kind = "user", text = "u-pad", createdAt = 1L))
        rows.add(TranscriptEvent(seq = seq, sessionId = "x", runId = "tail", kind = "user", text = "u-tail", createdAt = 1L))
        val (store, sid) = importRows(rows)
        // 15 + 1984 + 1 + 1 = 2001 rows; keep 2000 => raw drops orph-0.
        assertEquals(2001, store.countEvents(sid))
        val first = store.prune(PrunePolicy())
        // Raw 1 (orph-0) + fixup 14 (orph-1..14) = 15, single sweep.
        assertEquals(15, first.deletedEvents)
        assertEquals(1986, store.countEvents(sid))
        assertTrue("deleting sweep holds suspect", store.isPruneSuspect(sid))
        val head = store.loadEvents(sid, afterSeq = 0, limit = 3000)
            .first { it.kind == "user" || it.kind == "assistant" }
        assertEquals("convergent sweep leaves u-fill-0 at the head", "u-fill-0", head.text)
        // Pure helper converges the same crafted shape: 11 heads + user.
        val crafted = (0..10).map { i ->
            TranscriptEvent(sessionId = sid, runId = "c$i", kind = "assistant", text = "c-orph-$i", createdAt = 1L)
        } + TranscriptEvent(sessionId = sid, runId = "t", kind = "user", text = "u-t", createdAt = 1L)
        val trimmed = dropHeadOrphanAssistantFamilies(crafted)
        assertEquals(
            "pure helper converges (no 10-cap remainder)",
            "u-t",
            trimmed.first { it.kind == "user" || it.kind == "assistant" }.text,
        )
        assertTrue(trimmed.none { it.text.startsWith("c-orph-") })
        val scan = loadModelHistoryScan(store, sid)
        assertNoHeadOrphan(scan.events, "converged scan")
        val snap = loadSwitchSnapshotEvents(store, sid)
        assertNoHeadOrphan(snap.events, "converged snapshot")
    }

    /**
     * R27-1 fixup fault rollback: raw+fixup run in ONE tx, so a fixup DAO
     * throw aborts everything (no durable partial shape) and the store
     * rethrows with per-sweep flag cleanup (!alreadySuspect => false, prior
     * => true). Covers both fixup seams ([oldestHistory] and
     * [deleteFamily], plus a [deleteFamily] `Error` variant pinning
     * `catch (Throwable)`); the raw-cut seam stays pinned by
     * `throwThenCleanSweep_clearsSuspect`.
     */
    @Test
    fun fixupFault_rollsBack_shapeAndFlag() = runBlocking {
        val real = openStore()
        val sid = real.createSession("t", "m")
        real.appendEvent(TranscriptEvent(sessionId = sid, runId = "h", kind = "assistant", text = "a-h-orphan", createdAt = 1L))
        real.appendEvent(TranscriptEvent(sessionId = sid, runId = "p", kind = "user", text = "u-p", createdAt = 1L))
        real.appendEvent(TranscriptEvent(sessionId = sid, runId = "p", kind = "assistant", text = "a-p", createdAt = 1L))
        assertEquals(3, real.countEvents(sid))
        val throwing = ThrowingDao(db.sessionDao())
        val store = RoomSessionStore(
            object : LibrePocketDb() {
                override fun sessionDao(): SessionDao = throwing
                override fun createInvalidationTracker(): InvalidationTracker =
                    throw UnsupportedOperationException("fake db: no tracker")
                override fun clearAllTables() = Unit
                override fun <V> runInTransaction(callable: Callable<V>): V =
                    db.runInTransaction(callable)
            },
        ) { now }
        fun headText(): String = runBlocking {
            store.loadEvents(sid, afterSeq = 0, limit = 10)
                .first { it.kind == "user" || it.kind == "assistant" }.text
        }
        // Fixup deleteFamily fault with no prior: rethrows, NOTHING durable.
        throwing.failFamily = true
        try {
            store.prune(PrunePolicy(maxEventsPerSession = 2000))
            fail("prune must rethrow the fixup family failure")
        } catch (_: RuntimeException) {
            // Expected ("boom-family").
        } finally {
            throwing.failFamily = false
        }
        assertEquals("fixup fault must roll back raw+fixup (pre-prune shape)", 3, store.countEvents(sid))
        assertEquals("a-h-orphan", headText())
        assertFalse("throw with no prior evidence must not stick the flag", store.isPruneSuspect(sid))
        // Fixup deleteFamily Error (not just Exception) with no prior: same
        // rollback + flag cleanup, pins the store's `catch (Throwable)`.
        throwing.failFamilyError = true
        try {
            store.prune(PrunePolicy(maxEventsPerSession = 2000))
            fail("prune must rethrow the fixup family Error")
        } catch (e: AssertionError) {
            assertEquals("boom-family-error", e.message)
        } finally {
            throwing.failFamilyError = false
        }
        assertEquals("Error fault must also roll back (pre-prune shape)", 3, store.countEvents(sid))
        assertEquals("a-h-orphan", headText())
        assertFalse("Error with no prior evidence must not stick the flag", store.isPruneSuspect(sid))
        // Fixup oldestHistory fault with no prior: same rollback, no flag.
        throwing.failOldest = true
        try {
            store.prune(PrunePolicy(maxEventsPerSession = 2000))
            fail("prune must rethrow the fixup oldest failure")
        } catch (_: RuntimeException) {
            // Expected ("boom-oldest").
        } finally {
            throwing.failOldest = false
        }
        assertEquals(3, store.countEvents(sid))
        assertEquals("a-h-orphan", headText())
        assertFalse(store.isPruneSuspect(sid))
        // Healthy sweep converges (raw 0 + fixup 1) and proves suspect.
        val healthy = store.prune(PrunePolicy(maxEventsPerSession = 2000))
        assertEquals(1, healthy.deletedEvents)
        assertEquals(2, store.countEvents(sid))
        assertTrue(store.isPruneSuspect(sid))
        // Fault WITH prior suspect: still rethrows, keeps the proven flag,
        // rolls back (count unchanged). oldestHistory fires even on the now
        // clean ledger (head probe), so it deterministically faults here;
        // deleteFamily would not fire on a clean head (break-before-delete).
        throwing.failOldest = true
        try {
            store.prune(PrunePolicy(maxEventsPerSession = 2000))
            fail("prune must rethrow the fixup failure")
        } catch (_: RuntimeException) {
            // Expected.
        } finally {
            throwing.failOldest = false
        }
        assertEquals("fault with prior must not lose rows", 2, store.countEvents(sid))
        assertTrue("throw must keep a previously proven flag (fail-closed)", store.isPruneSuspect(sid))
        // Later clean sweep clears the prior flag again (per-sweep).
        val clean = store.prune(PrunePolicy(maxEventsPerSession = 2000))
        assertEquals(0, clean.deletedEvents)
        assertFalse("clean sweep must clear a prior flag", store.isPruneSuspect(sid))
    }

    /**
     * R27-1 pre-tx seam: [afterEventDeleteHookForTest] fires BEFORE the tx
     * starts, so a throw there is pre-write by construction (nothing
     * durable to roll back) with the same flag cleanup + rethrow. Throws an
     * [Error] (not just Exception) to pin the store's `catch (Throwable)`
     * — never a silent success. No latch/blocking: immediate throw, no CI
     * wait.
     */
    @Test
    fun preTxHookThrow_noDurable_flagSemantics() = runBlocking {
        val store = openStore()
        val sid = store.createSession("t", "m")
        store.appendEvent(TranscriptEvent(sessionId = sid, runId = "h", kind = "assistant", text = "a-h-orphan", createdAt = 1L))
        store.appendEvent(TranscriptEvent(sessionId = sid, runId = "p", kind = "user", text = "u-p", createdAt = 1L))
        store.appendEvent(TranscriptEvent(sessionId = sid, runId = "p", kind = "assistant", text = "a-p", createdAt = 1L))
        try {
            // No prior: Error rethrown, nothing durable, no flag.
            store.afterEventDeleteHookForTest = { throw AssertionError("boom-hook") }
            try {
                store.prune(PrunePolicy(maxEventsPerSession = 2000))
                fail("prune must rethrow the pre-tx hook Error")
            } catch (e: AssertionError) {
                assertEquals("boom-hook", e.message)
            }
            assertEquals(3, store.countEvents(sid))
            assertFalse("pre-tx throw with no prior must not stick the flag", store.isPruneSuspect(sid))
            // Healthy sweep proves suspect (convergent fixup deletes 1).
            store.afterEventDeleteHookForTest = null
            val healthy = store.prune(PrunePolicy(maxEventsPerSession = 2000))
            assertEquals(1, healthy.deletedEvents)
            assertTrue(store.isPruneSuspect(sid))
            // Prior + pre-tx Error: still rethrown, still nothing durable,
            // proven flag kept.
            store.afterEventDeleteHookForTest = { throw AssertionError("boom-hook") }
            try {
                store.prune(PrunePolicy(maxEventsPerSession = 2000))
                fail("prune must rethrow the pre-tx hook Error")
            } catch (e: AssertionError) {
                assertEquals("boom-hook", e.message)
            }
            assertEquals(2, store.countEvents(sid))
            assertTrue("pre-tx throw must keep a previously proven flag", store.isPruneSuspect(sid))
        } finally {
            store.afterEventDeleteHookForTest = null
        }
        val clean = store.prune(PrunePolicy(maxEventsPerSession = 2000))
        assertEquals(0, clean.deletedEvents)
        assertFalse(store.isPruneSuspect(sid))
    }

    /**
     * R27-2 second sweep (FILE-backed): 15 crafted head orphans + filler to
     * 2001 → first sweep converges fully (raw 1 + fixup 14 = 15) → second
     * sweep with the SAME keep is a true clean (`deleted==0` AND user head)
     * and clears suspect → close→reopen the DB FILE → fresh store reads the
     * correct clean (no orphan, dropped 0, suspect false) → true
     * `Factory.open` resumes clean with zero new rows → snapshot/fork keeps
     * the user-first prefix.
     */
    @Test
    fun secondSweep_cleanClearsSuspect_fileBackedRestartClean() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = "r27-second-sweep-${UUID.randomUUID()}.db"
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
            // Same-origin session + direct DAO inserts (NOT appends/import):
            // appends auto-prune every 100 rows and would converge the
            // crafted head orphans mid-seed; import would stamp IMPORT_MODEL
            // and fork on open instead of resuming same-origin.
            sid = store1.createSession("t", model)
            val rows = ArrayList<TranscriptEvent>(2001)
            for (i in 0 until 15) {
                rows.add(TranscriptEvent(sessionId = sid, runId = "o$i", kind = "assistant", text = "orph-$i", createdAt = 1L))
            }
            for (i in 0 until 992) {
                rows.add(TranscriptEvent(sessionId = sid, runId = "fill-$i", kind = "user", text = "u-fill-$i", createdAt = 1L))
                rows.add(TranscriptEvent(sessionId = sid, runId = "fill-$i", kind = "assistant", text = "a-fill-$i", createdAt = 1L))
            }
            // Complete tail pair (no lone tail user) so the reopen backfill
            // below adds zero rows.
            rows.add(TranscriptEvent(sessionId = sid, runId = "pad", kind = "user", text = "u-pad", createdAt = 1L))
            rows.add(TranscriptEvent(sessionId = sid, runId = "pad", kind = "assistant", text = "a-pad", createdAt = 1L))
            seedDirectDao(db1, sid, rows)
            assertEquals(2001, store1.countEvents(sid))
            val first = store1.prune(PrunePolicy())
            assertEquals(15, first.deletedEvents)
            assertEquals(1986, store1.countEvents(sid))
            assertTrue(store1.isPruneSuspect(sid))
            // Same keep, nothing left to cut: raw 0 + no-progress fixup
            // (head already user) => deleted 0 AND head clean => clears.
            val second = store1.prune(PrunePolicy())
            assertEquals(0, second.deletedEvents)
            assertFalse("clean second sweep (deleted==0, user head) must clear suspect", store1.isPruneSuspect(sid))
            assertEquals(1986, store1.countEvents(sid))
        } finally {
            db1.close()
        }
        val db2 = openFileDb()
        try {
            val store2 = RoomSessionStore(db2) { now }
            assertFalse("memory suspect must not survive restart", store2.isPruneSuspect(sid))
            val loaded = loadModelHistory(store2, sid)
            assertEquals(0, loaded.droppedCount)
            assertFalse("converged shape => clean resume is correct, not false-clean", loaded.reasoningIncomplete)
            assertEquals("u-fill-0", loaded.messages.first().text)
            assertTrue(loaded.messages.none { it.text.startsWith("orph-") })
            val scan = loadModelHistoryScan(store2, sid)
            assertNoHeadOrphan(scan.events, "restart scan")
            val snap = loadSwitchSnapshotEvents(store2, sid)
            assertNoHeadOrphan(snap.events, "restart snapshot")
            assertTrue(snap.events.none { it.text.startsWith("orph-") })
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
            assertTrue(findDanglingRunIds(store2.loadTailEvents(sid, 2000)).isEmpty())
            // Snapshot/fork keeps the user-first prefix (no orphan crosses).
            val newId = store2.createSession("t", model)
            val copied = snapshotSwitchHistory(store2, sid, newId, "m0", "m1", historyWithheld = false)
            assertTrue("converged fork must copy the prefix", copied > 0)
            val forked = store2.loadEvents(newId, afterSeq = 0, limit = 3000)
            assertTrue(forked.none { it.text.startsWith("orph-") })
            val forkQualifying = forked.filter { it.kind == "user" || it.kind == "assistant" }
            assertEquals("user", forkQualifying.first().kind)
            assertEquals("u-fill-0", forkQualifying.first().text)
            assertTrue(findDanglingRunIds(forked).isEmpty())
        } finally {
            db2.close()
            dbFile.delete()
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
        }
    }

    /**
     * R27-1 file-backed fixup fault + reopen: 2002-row same-origin ledger
     * (head user(h)+reasoning(h)+assistant(h), kept fill-0 triple, 998 plain
     * pairs — R27 `seedRows` shape) with a `deleteFamily` fault injected
     * INSIDE the single tx (fake db delegates `runInTransaction` to the real
     * file handle, `ThrowingDao.failFamily` wraps its DAO). The prune
     * rethrows, rolls everything back (count unchanged, head still h, no
     * flag), then close→reopen the DB FILE → fresh store + true
     * `loadModelHistory` + true `Factory.open` still see the pre-prune shape
     * (head h, honest dropped, no orphan-clean misreport). Deterministic, no
     * sleep/latch.
     */
    @Test
    fun fileBacked_fixupFault_rollback_reopenClean() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = "r26-fixup-fault-${UUID.randomUUID()}.db"
        val dbFile = ctx.getDatabasePath(name)
        fun openFileDb(): LibrePocketDb = Room.databaseBuilder(ctx, LibrePocketDb::class.java, name)
            .addMigrations(LibrePocketDb.MIGRATION_1_2, LibrePocketDb.MIGRATION_2_3, LibrePocketDb.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        val model = "preset:openai@https://api.openai.com/gpt-4o-mini"
        val db1 = openFileDb()
        val sid: String
        try {
            val real1 = RoomSessionStore(db1) { now }
            sid = real1.createSession("t", model)
            val rows = ArrayList<TranscriptEvent>(2002)
            rows.add(TranscriptEvent(sessionId = sid, runId = "h", kind = "user", text = "u-h", createdAt = 1L))
            rows.add(TranscriptEvent(sessionId = sid, runId = "h", kind = "reasoning", text = "", createdAt = 1L))
            rows.add(TranscriptEvent(sessionId = sid, runId = "h", kind = "assistant", text = "a-h", createdAt = 1L))
            rows.add(TranscriptEvent(sessionId = sid, runId = "fill-0", kind = "user", text = "u-fill-0", createdAt = 1L))
            rows.add(TranscriptEvent(sessionId = sid, runId = "fill-0", kind = "reasoning", text = "", createdAt = 1L))
            rows.add(TranscriptEvent(sessionId = sid, runId = "fill-0", kind = "assistant", text = "a-fill-0", createdAt = 1L))
            for (i in 1..998) {
                rows.add(TranscriptEvent(sessionId = sid, runId = "fill-$i", kind = "user", text = "u-fill-$i", createdAt = 1L))
                rows.add(TranscriptEvent(sessionId = sid, runId = "fill-$i", kind = "assistant", text = "a-fill-$i", createdAt = 1L))
            }
            seedDirectDao(db1, sid, rows)
            assertEquals(2002, real1.countEvents(sid))
            val throwing = ThrowingDao(db1.sessionDao())
            val store = RoomSessionStore(
                object : LibrePocketDb() {
                    override fun sessionDao(): SessionDao = throwing
                    override fun createInvalidationTracker(): InvalidationTracker =
                        throw UnsupportedOperationException("fake db: no tracker")
                    override fun clearAllTables() = Unit
                    override fun <V> runInTransaction(callable: Callable<V>): V =
                        db1.runInTransaction(callable)
                },
            ) { now }
            throwing.failFamily = true
            try {
                store.prune(PrunePolicy())
                fail("prune must rethrow the file-backed fixup failure")
            } catch (_: RuntimeException) {
                // Expected ("boom-family").
            } finally {
                throwing.failFamily = false
            }
            assertEquals("file-backed fixup fault must roll back (pre-prune shape)", 2002, store.countEvents(sid))
            assertFalse("throw with no prior evidence must not stick the flag", store.isPruneSuspect(sid))
            val head = store.loadEvents(sid, afterSeq = 0, limit = 10)
                .first { it.kind == "user" || it.kind == "assistant" }
            assertEquals("rollback keeps pre-prune head", "u-h", head.text)
        } finally {
            db1.close()
        }
        val db2 = openFileDb()
        try {
            val store2 = RoomSessionStore(db2) { now }
            assertFalse("memory suspect must not survive restart", store2.isPruneSuspect(sid))
            assertEquals(2002, store2.countEvents(sid))
            val loaded = loadModelHistory(store2, sid)
            assertEquals("reopen must still see pre-prune head h", "u-h", loaded.messages.first().text)
            assertTrue("rollback deleted nothing (a-h still present)", loaded.messages.any { it.text == "a-h" })
            val scan = loadModelHistoryScan(store2, sid)
            assertNoHeadOrphan(scan.events, "reopen scan")
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
                assertEquals("open dropped must match the honest load dropped", loaded.droppedCount, opened.historyDroppedCount)
                assertFalse(opened.historyWithheld)
            } finally {
                opened.session.close()
            }
            assertEquals("reopen must add zero rows (no dangling family)", before, store2.countEvents(sid))
            assertTrue(findDanglingRunIds(store2.loadTailEvents(sid, 2000)).isEmpty())
        } finally {
            db2.close()
            dbFile.delete()
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
        }
    }

    /**
     * R27-2 shape-aware residue: `deleted==0` clears suspect ONLY when the
     * post-fixup head is user/empty. A no-progress head orphan (blank family
     * break, or `famDeleted<=0` break) keeps suspect fail-closed across
     * sweeps — a later `deleted==0` sweep must NOT clear it.
     */
    @Test
    fun residueHeadOrphan_deletedZeroKeepsSuspect() = runBlocking {
        // Real blank-family residue (no mock): head assistant with a blank
        // logical family hits the isBlank break — fixup makes no progress.
        val store = openStore()
        val sid = store.createSession("t", "m")
        store.appendEvent(TranscriptEvent(sessionId = sid, runId = "", kind = "assistant", text = "a-blank-orphan", createdAt = 1L))
        store.appendEvent(TranscriptEvent(sessionId = sid, runId = "p", kind = "user", text = "u-p", createdAt = 1L))
        store.appendEvent(TranscriptEvent(sessionId = sid, runId = "p", kind = "assistant", text = "a-p", createdAt = 1L))
        val first = store.prune(PrunePolicy(maxEventsPerSession = 2000))
        assertEquals("blank-family residue makes no progress", 0, first.deletedEvents)
        assertTrue("deleted==0 with assistant head must keep suspect (residue)", store.isPruneSuspect(sid))
        assertEquals(3, store.countEvents(sid))
        val second = store.prune(PrunePolicy(maxEventsPerSession = 2000))
        assertEquals(0, second.deletedEvents)
        assertTrue("second deleted==0 with residue must NOT clear suspect", store.isPruneSuspect(sid))
        assertEquals(3, store.countEvents(sid))
        val loaded = loadModelHistory(store, sid)
        assertTrue("residue stays suspect (fail-closed, never false-clean)", loaded.reasoningIncomplete)
        // Mock famDeleted<=0 residue: normal family, but the family delete
        // reports no progress — same shape-aware outcome, no clear.
        val real2 = openStore()
        val sid2 = real2.createSession("t", "m")
        real2.appendEvent(TranscriptEvent(sessionId = sid2, runId = "h", kind = "assistant", text = "a-h-orphan", createdAt = 1L))
        real2.appendEvent(TranscriptEvent(sessionId = sid2, runId = "p", kind = "user", text = "u-p", createdAt = 1L))
        real2.appendEvent(TranscriptEvent(sessionId = sid2, runId = "p", kind = "assistant", text = "a-p", createdAt = 1L))
        val zeroDao = NoProgressFamilyDao(db.sessionDao())
        val stuck = RoomSessionStore(
            object : LibrePocketDb() {
                override fun sessionDao(): SessionDao = zeroDao
                override fun createInvalidationTracker(): InvalidationTracker =
                    throw UnsupportedOperationException("fake db: no tracker")
                override fun clearAllTables() = Unit
                override fun <V> runInTransaction(callable: Callable<V>): V =
                    db.runInTransaction(callable)
            },
        ) { now }
        val z1 = stuck.prune(PrunePolicy(maxEventsPerSession = 2000))
        assertEquals("famDeleted<=0 residue makes no progress", 0, z1.deletedEvents)
        assertTrue(stuck.isPruneSuspect(sid2))
        assertEquals(3, stuck.countEvents(sid2))
        val z2 = stuck.prune(PrunePolicy(maxEventsPerSession = 2000))
        assertEquals(0, z2.deletedEvents)
        assertTrue("second deleted==0 with famDeleted<=0 residue must NOT clear", stuck.isPruneSuspect(sid2))
        assertEquals(3, stuck.countEvents(sid2))
    }
}

/**
 * P1-1 throw-path DAO double: delegates everything to the real DAO except
 * fault-injectable [deleteEventsBeyondLimit] (raw cut) plus R27-1 fixup
 * faults ([oldestHistory] / [deleteFamily], thrown INSIDE the single tx so
 * the whole raw+fixup unit rolls back). Lets the test throw AFTER the
 * store pre-published suspect (the exact sticky-flag window).
 */
private class ThrowingDao(private val real: SessionDao) : SessionDao by real {
    var failDelete: Boolean = false
    var failOldest: Boolean = false
    var failFamily: Boolean = false
    var failFamilyError: Boolean = false

    override fun deleteEventsBeyondLimit(sid: String, keep: Int): Int {
        if (failDelete) throw RuntimeException("boom-delete")
        return real.deleteEventsBeyondLimit(sid, keep)
    }

    override fun oldestHistory(sid: String, limit: Int): List<TranscriptEventEntity> {
        if (failOldest) throw RuntimeException("boom-oldest")
        return real.oldestHistory(sid, limit)
    }

    override fun deleteFamily(sid: String, fid: String): Int {
        if (failFamilyError) throw AssertionError("boom-family-error")
        if (failFamily) throw RuntimeException("boom-family")
        return real.deleteFamily(sid, fid)
    }
}

/** No-op provider for the true `Factory.open` restart reopen (empty stream). */
private class R26NoopProvider : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    override fun stream(request: ChatRequest): Flow<StreamEvent> = emptyFlow()
    override suspend fun listModels(): List<String> = emptyList()
}

/**
 * R27-2 residue double: reports `famDeleted<=0` no-progress for every family
 * delete (head orphan never converges). Pins the shape-aware clean rule:
 * `deleted==0` with a still-assistant head must keep suspect.
 */
private class NoProgressFamilyDao(private val real: SessionDao) : SessionDao by real {
    override fun deleteFamily(sid: String, fid: String): Int = 0
}
