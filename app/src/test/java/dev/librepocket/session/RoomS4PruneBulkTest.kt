package dev.librepocket.session

import androidx.test.core.app.ApplicationProvider
import dev.librepocket.agent.ui.chat.loadModelHistory
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S4 P1 persistence pins (Room, Robolectric).
 * - F4(b) + R26-1: keep-newest prune deletes a cut HEAD family whole
 *   (marker-first pair can no longer strand its assistant); the deleting
 *   sweep marks suspect (pre-published to close the DELETE→publish race,
 *   per-sweep NOT sticky — clean sweep clears) so the next loadModelHistory
 *   resumes conservative all-flag even with dropped==0.
 * - F10: auto-prune bulk counts per ROWS (appendsSincePrune += events.size),
 *   not one per bulk; head marker documented in store KDoc.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomS4PruneBulkTest {

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

    /**
     * R27-2 auto-prune-proof seeding (Current): head-orphan ledgers MUST be
     * seeded via import (NOT appends) — appends auto-prune every 100 rows and
     * the unconditional fixup would converge the head orphan mid-seed, so an
     * append-seeded count never reaches 2001. Same pattern as
     * `RoomR26FamilyAwarePruneTest.importRows` (deletePublishRace,
     * fifteenHeadOrphans). Import bypasses the append counter, so the DB holds
     * the exact pre-prune shape for the explicit prune under test.
     */
    private suspend fun importRows(rows: List<TranscriptEvent>): Pair<RoomSessionStore, String> {
        val store = openStore()
        val tmpDir = Files.createTempDirectory("s4-prune-test").toFile()
        try {
            val src = File(tmpDir, "seed.jsonl")
            src.writeText(rows.joinToString("\n") { JsonlCodec.encode(it) } + "\n", Charsets.UTF_8)
            val sid = store.importJsonl(src)
            return store to sid
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun pruneCutsMarkerFirstPair_keptAssistantStaysSuspect() = runBlocking {
        // Marker-first production pair at head: reasoning seq1 + assistant seq2,
        // then 1999 qualifying filler rows (999 pairs + 1 user) => 2000 qualifying,
        // 2001 raw rows total. Prune keep-2000 raw deletes exactly the marker.
        // Seeded via import (NOT appends): see importRows KDoc above.
        var seq = 1L
        val rows = ArrayList<TranscriptEvent>(2001)
        rows.add(
            TranscriptEvent(seq = seq++, sessionId = "x", runId = "r-old", kind = "reasoning", text = "", createdAt = 1L),
        )
        rows.add(
            TranscriptEvent(seq = seq++, sessionId = "x", runId = "r-old", kind = "assistant", text = "a-old", createdAt = 1L),
        )
        for (i in 0 until 999) {
            rows.add(
                TranscriptEvent(seq = seq++, sessionId = "x", runId = "fill-$i", kind = "user", text = "u-fill-$i", createdAt = 1L),
            )
            rows.add(
                TranscriptEvent(seq = seq++, sessionId = "x", runId = "fill-$i", kind = "assistant", text = "a-fill-$i", createdAt = 1L),
            )
        }
        rows.add(
            TranscriptEvent(seq = seq, sessionId = "x", runId = "tail", kind = "user", text = "u-tail", createdAt = 1L),
        )
        val (store, sid) = importRows(rows)
        assertEquals(2001, store.countEvents(sid))
        val result = store.prune(PrunePolicy())
        // R26-1 family-aware: raw cut deletes the marker (1) plus the whole cut
        // head family (orphan a-old, 1) so kept starts at a user boundary.
        assertEquals(2, result.deletedEvents)
        assertEquals(1999, store.countEvents(sid))
        assertTrue("prune deleted rows => suspect generation must hold", store.isPruneSuspect(sid))
        val loaded = loadModelHistory(store, sid)
        // Post-fixup qualifying total == kept == 1999 => dropped==0 yet suspect.
        assertEquals(0, loaded.droppedCount)
        assertTrue(
            "pruned head family must resume suspect (incomplete via suspect)",
            loaded.reasoningIncomplete,
        )
        val assistants = loaded.messages.filter { it.role == "assistant" }
        assertEquals(999, assistants.size)
        assertTrue("cut head family must be gone whole (no orphan assistant)", assistants.none { it.text == "a-old" })
        assertEquals("u-fill-0", loaded.messages.first().text)
        assertEquals("user", loaded.messages.first().role)
    }

    @Test
    fun bulkAutoPrune_countsPerRows_notPerBulk() = runBlocking {        val store = openStore()
        val sid = store.createSession("t", "p/m")
        val stale = store.createSession("stale", "p/m")
        db.sessionDao().touchSession(stale, now - 91L * 86_400_000L)
        // 10 bulks x 10 rows = 100 rows => AUTO_PRUNE_EVERY rows triggers one sweep.
        // Pre-fix (one per bulk, counter==10) the stale session survives; post-fix
        // (per rows, counter==100) the sweep deletes it.
        repeat(10) { b ->
            val batch = ArrayList<TranscriptEvent>(10)
            for (i in 0 until 10) {
                batch.add(
                    TranscriptEvent(
                        sessionId = sid,
                        runId = "bulk-$b-$i",
                        kind = "user",
                        text = "hello $b $i",
                        createdAt = 1L,
                    ),
                )
            }
            store.appendEvents(batch)
        }
        assertEquals(100, store.countEvents(sid))
        // Auto-prune runs synchronously after the counter hits (suspending prune
        // inside appendEvents), so the stale row must already be gone – no
        // explicit prune call here (that would mask the per-row vs per-bulk
        // divergence: pre-fix counter==10, no sweep, stale survives).
        assertEquals(
            "per-row bulk accounting must have swept the stale session",
            null,
            db.sessionDao().sessionById(stale),
        )
    }

    /**
     * S4-R2 SuspectFakeStore dual (R26-1 family-aware BOTH sides: raw cut drops
     * the marker, the in-storage head-family fixup drops the orphan a-old, so
     * BOTH report deleted==2 / count==1999 / loaded dropped==0 + suspect
     * all-flag — the old DB-delete vs read-filter `dropped` gap is eliminated
     * by construction). Fake itself stays no-prune per S1 F9 (prune throws);
     * this subclass simulates the keep-newest row cut + fixup. Same shape as
     * pruneCutsMarkerFirstPair_keptAssistantStaysSuspect above; asserts the
     * SAME numbers (parity is exact, not analogous).
     */
    @Test
    fun suspectFake_familyFixupParity_matchesRoom() = runBlocking {
        val store = SuspectFakeStore()
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
        store.appendEvent(
            TranscriptEvent(sessionId = sid, runId = "tail", kind = "user", text = "u-tail", createdAt = 1L),
        )
        assertEquals(2001, store.countEvents(sid))
        val dropped = store.simulatePruneCut(sid, keepNewestRows = 2000)
        // Room-convergent: raw 1 (marker) + fixup 1 (orphan a-old) = 2.
        assertEquals(2, dropped)
        assertEquals(1999, store.countEvents(sid))
        assertTrue("simulated cut must mark suspect", store.isPruneSuspect(sid))
        val loaded = loadModelHistory(store, sid)
        // Same as Room: post-fixup qualifying total == kept == 1999.
        assertEquals(0, loaded.droppedCount)
        assertTrue(
            "fake cut head family must resume suspect like Room",
            loaded.reasoningIncomplete,
        )
        val assistants = loaded.messages.filter { it.role == "assistant" }
        assertEquals(999, assistants.size)
        assertTrue("cut head family must be gone whole (no orphan assistant)", assistants.none { it.text == "a-old" })
        assertEquals("u-fill-0", loaded.messages.first().text)
        assertEquals("user", loaded.messages.first().role)
    }

    /**
     * S4-R2 suspect lifecycle: delete clears, append never clears, clean
     * sweep clears (per-sweep, NOT sticky — see
     * `RoomR26FamilyAwarePruneTest.throwThenCleanSweep_clearsSuspect`).
     * Memory-only (restart drops it; new prunes leave no orphan behind so
     * the flag is never load-bearing across restart). Covers Room + Fake.
     */
    @Test
    fun suspectLifecycle_deleteClears_appendKeeps() = runBlocking {
        // Room (seeded via import, NOT appends: appends auto-prune every 100
        // rows and the R27-2 unconditional fixup would converge the head
        // reasoning+assistant orphan mid-seed, so an append-seeded explicit
        // prune would see deleted==0; see importRows KDoc above).
        var seq = 1L
        val seedRows = ArrayList<TranscriptEvent>(2001)
        seedRows.add(
            TranscriptEvent(seq = seq++, sessionId = "x", runId = "r", kind = "reasoning", text = "", createdAt = 1L),
        )
        seedRows.add(
            TranscriptEvent(seq = seq++, sessionId = "x", runId = "r", kind = "assistant", text = "a", createdAt = 1L),
        )
        for (i in 0 until 1999) {
            seedRows.add(
                TranscriptEvent(seq = seq++, sessionId = "x", runId = "f-$i", kind = "user", text = "u$i", createdAt = 1L),
            )
        }
        val (room, rid) = importRows(seedRows)
        val res = room.prune(PrunePolicy(maxEventsPerSession = 2000))
        assertTrue(res.deletedEvents > 0)
        assertTrue(room.isPruneSuspect(rid))
        room.appendEvent(
            TranscriptEvent(sessionId = rid, runId = "late", kind = "user", text = "late", createdAt = 1L),
        )
        assertTrue("append must not clear suspect (marker cannot resurrect)", room.isPruneSuspect(rid))
        room.deleteSession(rid)
        assertTrue("delete must clear suspect (session gone)", !room.isPruneSuspect(rid))
        // Fake.
        val fake = SuspectFakeStore()
        val fid = fake.createSession("t", "m")
        fake.appendEvent(
            TranscriptEvent(sessionId = fid, runId = "r", kind = "user", text = "q", createdAt = 1L),
        )
        fake.appendEvent(
            TranscriptEvent(sessionId = fid, runId = "r", kind = "assistant", text = "a", createdAt = 1L),
        )
        fake.simulatePruneCut(fid, keepNewestRows = 1)
        assertTrue(fake.isPruneSuspect(fid))
        fake.appendEvent(
            TranscriptEvent(sessionId = fid, runId = "late", kind = "user", text = "late", createdAt = 1L),
        )
        assertTrue("fake append must not clear suspect", fake.isPruneSuspect(fid))
        fake.deleteSession(fid)
        assertTrue("fake delete must clear suspect", !fake.isPruneSuspect(fid))
    }

    /**
     * S4-R2 fail-closed: isPruneSuspect read failure => suspect true (never a
     * silent hasReasoning=false on a tool-dependent follow-up). Small ledger,
     * dropped==0, no markers – only the suspect flag flips the oldest.
     */
    @Test
    fun pruneSuspectReadFailure_failClosed_allFlag() = runBlocking {
        val store = object : FakeSessionStore() {
            override suspend fun isPruneSuspect(sessionId: String): Boolean = error("store wedged")
        }
        val sid = store.createSession("t", "m")
        store.appendEvent(
            TranscriptEvent(sessionId = sid, runId = "r1", kind = "user", text = "q", createdAt = 1L),
        )
        store.appendEvent(
            TranscriptEvent(sessionId = sid, runId = "r1", kind = "assistant", text = "a", createdAt = 1L),
        )
        val loaded = loadModelHistory(store, sid)
        assertEquals(0, loaded.droppedCount)
        assertTrue("wedged suspect read must fail closed", loaded.reasoningIncomplete)
        assertTrue(loaded.messages.filter { it.role == "assistant" }.all { it.hasReasoning })
    }
}
