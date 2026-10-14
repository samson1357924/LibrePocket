package dev.librepocket.session

import androidx.test.core.app.ApplicationProvider
import dev.librepocket.agent.ui.chat.SnapshotBudget
import dev.librepocket.agent.ui.chat.loadModelHistory
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
 * - F4(b): keep-newest prune may cut a marker-first pair (reasoning seq1 +
 *   assistant seq2 + filler); dropped==0 yet the kept assistant must resume
 *   suspect (hasReasoning true or reasoningIncomplete true). Hotfix (b):
 *   prune generation deletedEvents>0 => isPruneSuspect => next loadModelHistory
 *   conservative all-flag. Pair-aware prune stays Target (a).
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

    @Test
    fun pruneCutsMarkerFirstPair_keptAssistantStaysSuspect() = runBlocking {
        val store = openStore()
        val sid = store.createSession("t", "m")
        // Marker-first production pair at head: reasoning seq1 + assistant seq2,
        // then 1999 qualifying filler rows (999 pairs + 1 user) => 2000 qualifying,
        // 2001 raw rows total. Prune keep-2000 raw deletes exactly the marker.
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
        val result = store.prune(PrunePolicy())
        assertEquals(1, result.deletedEvents)
        assertEquals(2000, store.countEvents(sid))
        assertTrue("prune deleted rows => suspect generation must hold", store.isPruneSuspect(sid))
        val loaded = loadModelHistory(store, sid)
        // dropped==0 post-prune (qualifying total == kept == CAP) yet suspect.
        assertEquals(0, loaded.droppedCount)
        assertTrue(
            "pruned marker-first pair must resume suspect (incomplete or flagged oldest)",
            loaded.reasoningIncomplete ||
                loaded.messages.filter { it.role == "assistant" }.firstOrNull()?.hasReasoning == true,
        )
        val assistants = loaded.messages.filter { it.role == "assistant" }
        assertEquals(SnapshotBudget.HISTORY_LOAD_CAP / 2, assistants.size)
        assertEquals("a-old", assistants.first().text)
        assertTrue("oldest kept assistant lost its marker to prune – must stay suspect", assistants.first().hasReasoning)
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
     * S4-R2 SuspectFakeStore dual (dropped==0 + suspect all-flag, Room same
     * semantics). Fake itself stays no-prune per S1 F9 (prune throws); this
     * subclass simulates the keep-newest row cut. Same shape as
     * pruneCutsMarkerFirstPair above: marker pruned, assistant kept,
     * dropped==0 yet every kept assistant stays suspect.
     */
    @Test
    fun suspectFake_droppedZeroSuspect_allFlag_matchesRoom() = runBlocking {
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
        assertEquals(1, dropped)
        assertEquals(2000, store.countEvents(sid))
        assertTrue("simulated cut must mark suspect", store.isPruneSuspect(sid))
        val loaded = loadModelHistory(store, sid)
        assertEquals(0, loaded.droppedCount)
        assertTrue(
            "fake cut marker-first pair must resume suspect like Room",
            loaded.reasoningIncomplete ||
                loaded.messages.filter { it.role == "assistant" }.firstOrNull()?.hasReasoning == true,
        )
        val assistants = loaded.messages.filter { it.role == "assistant" }
        assertEquals(SnapshotBudget.HISTORY_LOAD_CAP / 2, assistants.size)
        assertEquals("a-old", assistants.first().text)
        assertTrue("oldest kept assistant lost its marker – must stay suspect", assistants.first().hasReasoning)
    }

    /**
     * S4-R2 suspect lifecycle: delete clears, append never clears.
     * Limits: memory-only (restart drops it) + pair-aware prune stays Target –
     * see [SessionStore.isPruneSuspect] KDoc follow-up. Covers Room + Fake.
     */
    @Test
    fun suspectLifecycle_deleteClears_appendKeeps() = runBlocking {
        // Room.
        val room = openStore()
        val rid = room.createSession("t", "m")
        room.appendEvent(
            TranscriptEvent(sessionId = rid, runId = "r", kind = "reasoning", text = "", createdAt = 1L),
        )
        room.appendEvent(
            TranscriptEvent(sessionId = rid, runId = "r", kind = "assistant", text = "a", createdAt = 1L),
        )
        for (i in 0 until 1999) {
            room.appendEvent(
                TranscriptEvent(sessionId = rid, runId = "f-$i", kind = "user", text = "u$i", createdAt = 1L),
            )
        }
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
