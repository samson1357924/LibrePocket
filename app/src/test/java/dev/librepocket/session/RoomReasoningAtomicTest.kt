package dev.librepocket.session

import androidx.test.core.app.ApplicationProvider
import dev.librepocket.agent.ui.chat.SnapshotBudget
import dev.librepocket.agent.ui.chat.loadModelHistory
import dev.librepocket.agent.ui.chat.loadModelHistoryScan
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D15-3 G1 Room-side fault-injection (Round15 P2, scheme A).
 *
 * [SessionReasoningAtomicTest] pins the `assistant` + `reasoning` batch
 * atomicity against the in-memory fake (snapshot + rollback). This file pins
 * the same contract against real Room: an in-transaction failure after the
 * second row was inserted rolls back the whole batch (zero rows), never an
 * orphaned `assistant` that would read back as a complete plain-text result.
 *
 * Fault seam: [RoomSessionStore.insideTxAfterInsertHookForTest] fires
 * synchronously inside the `appendEvents` transaction, after both rows are
 * inserted but before COMMIT (same stub style as
 * `RoomCommitSetGapCancelTest` / `RoomPostCommitResumeCancelTest`, but here
 * the hook THROWS to simulate a genuine in-tx second-row failure — rollback,
 * ack must fail). Both tests drive the production
 * [SessionTranscriptSink] `hasReasoning=true` paths (2-row batch), not a
 * generic single `user` row.
 *
 * G2 (CAP fail-open) and G3 (commit→set gap-cancel) stay documented, not
 * fixed, by this file — see `capBoundary_markerOutsideWindow_resumesFailOpen`
 * and `RoomCommitSetGapCancelTest`.
 *
 * Runs under Robolectric (in-memory Room).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomReasoningAtomicTest {

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
    fun success_hasReasoning_inTxFault_rollsBackZeroRows() = runBlocking {
        val store = openStore()
        val sid = store.createSession("t", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }
        // In-tx throw: both rows already inserted, COMMIT not yet reached —
        // equivalent to a second-row insert fault; the transaction rolls back.
        store.insideTxAfterInsertHookForTest = { throw IOException("injected second-row fault") }
        try {
            try {
                sink.onTurnSucceeded("r1", "answer", hasReasoning = true)
                fail("in-tx batch failure must propagate")
            } catch (_: IOException) {
                // Expected: the batch aborts.
            }
            assertEquals(0, store.countEvents(sid))
            assertTrue("orphaned assistant must never persist", store.loadEvents(sid).isEmpty())
        } finally {
            store.insideTxAfterInsertHookForTest = null
        }
        // Control: the same hasReasoning batch without the fault commits both rows.
        sink.onTurnSucceeded("r1", "answer", hasReasoning = true)
        val rows = store.loadEvents(sid)
        // Phase2 marker-first: [reasoning, assistant].
        assertEquals(listOf("reasoning", "assistant"), rows.map { it.kind })
    }

    @Test
    fun cancel_hasReasoning_inTxFault_rollsBackZeroRows() = runBlocking {
        val store = openStore()
        val sid = store.createSession("t", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }
        store.insideTxAfterInsertHookForTest = { throw IOException("injected second-row fault") }
        try {
            try {
                sink.onTurnCancelled("r1", "partial", hasReasoning = true)
                fail("in-tx batch failure must propagate")
            } catch (_: IOException) {
                // Expected: the batch aborts.
            }
            assertEquals(0, store.countEvents(sid))
            assertTrue("orphaned assistant must never persist", store.loadEvents(sid).isEmpty())
        } finally {
            store.insideTxAfterInsertHookForTest = null
        }
        // Control: the same hasReasoning batch without the fault commits both rows.
        sink.onTurnCancelled("r1", "partial", hasReasoning = true)
        val rows = store.loadEvents(sid)
        assertEquals(listOf("reasoning", "assistant"), rows.map { it.kind })
        assertTrue("cancel assistant keeps its partial text", rows.last().isPartial)
    }

    @Test
    fun failedNonFinal_hasReasoning_inTxFault_rollsBackZeroRows() = runBlocking {
        val store = openStore()
        val sid = store.createSession("t", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }
        store.insideTxAfterInsertHookForTest = { throw IOException("injected second-row fault") }
        try {
            try {
                sink.onTurnFailed("r1", "half", "boom", null, 0, false, true)
                fail("in-tx batch failure must propagate")
            } catch (_: IOException) {
                // Expected: the batch aborts.
            }
            assertEquals(0, store.countEvents(sid))
            assertTrue("orphaned assistant must never persist", store.loadEvents(sid).isEmpty())
        } finally {
            store.insideTxAfterInsertHookForTest = null
        }
        // Control: without the fault the failed batch commits assistant + marker.
        sink.onTurnFailed("r1", "half", "boom", null, 0, false, true)
        val rows = store.loadEvents(sid)
        assertEquals(listOf("reasoning", "assistant"), rows.map { it.kind })
        assertTrue("failed assistant keeps its partial text", rows.last().isPartial)
        assertEquals("boom", rows.last().failureReason)
        assertEquals(false, rows.last().isFinal)
        assertEquals(false, rows.first().isFinal)
        assertTrue("reasoning marker must stay payload-free", rows.first().text.isEmpty())
    }

    @Test
    fun failedAndCancelled_hasReasoning_inTxFault_rollsBackZeroRows() = runBlocking {
        val store = openStore()
        val sid = store.createSession("t", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }
        store.insideTxAfterInsertHookForTest = { throw IOException("injected batch fault") }
        try {
            try {
                sink.onTurnFailedAndCancelled("L", "L", "half", "boom", null, 0, true)
                fail("in-tx batch failure must propagate")
            } catch (_: IOException) {
                // Expected: the batch aborts.
            }
            assertEquals(0, store.countEvents(sid))
            assertTrue("orphaned batch rows must never persist", store.loadEvents(sid).isEmpty())
        } finally {
            store.insideTxAfterInsertHookForTest = null
        }
        // Control: without the fault the compensation batch commits all three rows.
        sink.onTurnFailedAndCancelled("L", "L", "half", "boom", null, 0, true)
        val rows = store.loadEvents(sid)
        assertEquals(listOf("assistant", "reasoning", "system"), rows.map { it.kind })
        assertEquals("turn L cancelled", rows.last().text)
    }

    @Test
    fun failedAndCancelled_noReasoning_inTxFault_rollsBackZeroRows() = runBlocking {
        // R18-3 false path: 2-row batch (assistant + system) rolls back to
        // zero on an in-tx fault, symmetric with the 3-row reasoning variant.
        val store = openStore()
        val sid = store.createSession("t", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }
        store.insideTxAfterInsertHookForTest = { throw IOException("injected batch fault") }
        try {
            try {
                sink.onTurnFailedAndCancelled("L", "L", "half", "boom", null, 0, false)
                fail("in-tx batch failure must propagate")
            } catch (_: IOException) {
                // Expected: the batch aborts.
            }
            assertEquals(0, store.countEvents(sid))
            assertTrue("orphaned batch rows must never persist", store.loadEvents(sid).isEmpty())
        } finally {
            store.insideTxAfterInsertHookForTest = null
        }
        // Control: without the fault the compensation batch commits both rows.
        sink.onTurnFailedAndCancelled("L", "L", "half", "boom", null, 0, false)
        val rows = store.loadEvents(sid)
        assertEquals(listOf("assistant", "system"), rows.map { it.kind })
        assertEquals("turn L cancelled", rows.last().text)
    }

    /**
     * R23-2 exact-cap boundary against real Room: exactly
     * HISTORY_LOAD_CAP (2000) qualifying rows plus the newest pair's
     * payload-free `reasoning` marker. Nothing is truncated (`dropped == 0`,
     * `reasoningIncomplete == false`), yet the newest assistant still resumes
     * `hasReasoning == true` via its in-window marker, while older plain
     * assistants stay false. Mirrors the fake variant in
     * ChatSessionFactoryHistoryTailTest (which covers 2002-over / 3-under).
     */
    @Test
    fun exactCap2000WithNewestMarker_reportsZeroDroppedAndFlagsNewest() = runBlocking {
        val store = openStore()
        val sid = store.createSession("t", "m")
        val cap = SnapshotBudget.HISTORY_LOAD_CAP
        // Exactly CAP qualifying rows: 999 plain pairs + the newest pair.
        for (i in 0 until cap / 2 - 1) {
            store.appendEvent(
                TranscriptEvent(sessionId = sid, runId = "r$i", kind = "user", text = "u$i", createdAt = 1L),
            )
            store.appendEvent(
                TranscriptEvent(sessionId = sid, runId = "r$i", kind = "assistant", text = "a$i", createdAt = 1L),
            )
        }
        val newest = "r-newest"
        store.appendEvent(
            TranscriptEvent(sessionId = sid, runId = newest, kind = "user", text = "q-newest", createdAt = 1L),
        )
        store.appendEvent(
            TranscriptEvent(sessionId = sid, runId = newest, kind = "assistant", text = "a-newest", createdAt = 1L),
        )
        store.appendEvent(
            TranscriptEvent(sessionId = sid, runId = newest, kind = "reasoning", text = "", createdAt = 1L),
        )

        val loaded = loadModelHistory(store, sid)
        assertEquals(0, loaded.droppedCount)
        assertEquals(false, loaded.reasoningIncomplete)
        assertEquals(cap, loaded.messages.size)
        val assistants = loaded.messages.filter { it.role == "assistant" }
        assertEquals(cap / 2, assistants.size)
        assertTrue("newest in-window marker must restore the flag", assistants.last().hasReasoning)
        assertEquals("a-newest", assistants.last().text)
        assertTrue("older plain turns must stay unflagged", assistants.dropLast(1).none { it.hasReasoning })
    }

    /**
     * R24-2 oldest-boundary regression via Room JSONL round-trip: exactly
     * HISTORY_LOAD_CAP (2000) qualifying rows where the oldest retained
     * assistant's marker sits just outside the last backward page — seq=1
     * `reasoning(r-old)` + seq=2 `assistant(r-old)` + seq=3..2001 filler
     * (1999 qualifying rows). The 200-row DESC reader fills 2000 kept at
     * seq=2 and stops before seq=1, so without the boundary scan
     * `dropped == 0` yet `a-old` resumes `hasReasoning == false`.
     *
     * Production writes marker-first (`reasoning` seq N, `assistant` seq N+1,
     * adjacent); this test pins that order through the real import path.
     * The retained newest exact-2000 test above uses marker-after and stays
     * green. Room JSONL import validates per-row only (no pairing/order
     * rule), so this marker-first layout imports legally.
     */
    @Test
    fun exactCap2000WithOldestMarkerJustOutsideViaJsonlImport_flagsOldest() = runBlocking {
        val store = openStore()
        val cap = SnapshotBudget.HISTORY_LOAD_CAP
        val rows = ArrayList<TranscriptEvent>(cap + 1)
        rows.add(
            TranscriptEvent(
                seq = 1L,
                sessionId = "ignored-on-import",
                runId = "r-old",
                kind = "reasoning",
                text = "",
                createdAt = 1L,
            ),
        )
        rows.add(
            TranscriptEvent(
                seq = 2L,
                sessionId = "ignored-on-import",
                runId = "r-old",
                kind = "assistant",
                text = "a-old",
                createdAt = 1L,
            ),
        )
        var seq = 3L
        for (i in 0 until cap / 2 - 1) {
            rows.add(
                TranscriptEvent(
                    seq = seq++,
                    sessionId = "ignored-on-import",
                    runId = "fill-$i",
                    kind = "user",
                    text = "u-fill-$i",
                    createdAt = 1L,
                ),
            )
            rows.add(
                TranscriptEvent(
                    seq = seq++,
                    sessionId = "ignored-on-import",
                    runId = "fill-$i",
                    kind = "assistant",
                    text = "a-fill-$i",
                    createdAt = 1L,
                ),
            )
        }
        rows.add(
            TranscriptEvent(
                seq = seq,
                sessionId = "ignored-on-import",
                runId = "tail",
                kind = "user",
                text = "u-tail",
                createdAt = 1L,
            ),
        )
        val tmpDir = Files.createTempDirectory("room-oldest-boundary-test").toFile()
        try {
            val src = File(tmpDir, "oldest-boundary.jsonl")
            src.writeText(rows.joinToString("\n") { JsonlCodec.encode(it) } + "\n", Charsets.UTF_8)
            val sid = store.importJsonl(src)

            val scan = loadModelHistoryScan(store, sid)
            assertEquals(cap, scan.events.size)
            assertTrue("cap saturated: scan stays suspect", scan.reasoningIncomplete)
            assertTrue("boundary scan must collect the adjacent marker", "r-old" in scan.reasoningRunIds)

            val loaded = loadModelHistory(store, sid)
            assertEquals(0, loaded.droppedCount)
            assertEquals(false, loaded.reasoningIncomplete)
            assertEquals(cap, loaded.messages.size)
            val assistants = loaded.messages.filter { it.role == "assistant" }
            assertEquals(cap / 2, assistants.size)
            assertEquals("a-old", assistants.first().text)
            assertTrue("oldest marker just outside the page must restore the flag", assistants.first().hasReasoning)
            assertTrue("plain filler turns must stay unflagged", assistants.drop(1).none { it.hasReasoning })
            assertTrue(loaded.messages.none { it.role == "reasoning" })
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /**
     * R25-1 far-marker regression via Room JSONL round-trip: exactly
     * HISTORY_LOAD_CAP (2000) qualifying rows where the oldest assistant's
     * marker sits more than one backward page below the window — seq=1
     * `reasoning(r-old)` + seq=2..201 200 non-model notices + seq=202
     * `assistant(r-old)` + seq=203..2201 filler (1999 qualifying rows). The
     * kept scan holds 2000 qualifying (2201..202); the R24-2 extra 200-row
     * page covers only 201..2 and misses seq=1, so pre-fix `dropped == 0`
     * with `a-old` resuming `hasReasoning == false`. Post-fix the bounded
     * COUNT probe (`SessionDao.countReasoningBefore`) forces
     * `reasoningIncomplete == true` (all kept assistants suspect). Over-flags
     * plain filler by design (Target: pair-aware narrowing).
     */
    @Test
    fun exactCap2000WithFarMarkerBeyondOnePageViaJsonlImport_flagsAllConservative() = runBlocking {
        val store = openStore()
        val cap = SnapshotBudget.HISTORY_LOAD_CAP
        val old = "r-old"
        val rows = ArrayList<TranscriptEvent>(cap + 201)
        rows.add(
            TranscriptEvent(
                seq = 1L,
                sessionId = "ignored-on-import",
                runId = old,
                kind = "reasoning",
                text = "",
                createdAt = 1L,
            ),
        )
        var seq = 2L
        for (i in 0 until 200) {
            rows.add(
                TranscriptEvent(
                    seq = seq++,
                    sessionId = "ignored-on-import",
                    runId = "notice-$i",
                    kind = "retry",
                    text = "attempt ${i + 1}/3 after 0ms",
                    createdAt = 1L,
                ),
            )
        }
        rows.add(
            TranscriptEvent(
                seq = seq++,
                sessionId = "ignored-on-import",
                runId = old,
                kind = "assistant",
                text = "a-old",
                createdAt = 1L,
            ),
        )
        for (i in 0 until cap / 2 - 1) {
            rows.add(
                TranscriptEvent(
                    seq = seq++,
                    sessionId = "ignored-on-import",
                    runId = "fill-$i",
                    kind = "user",
                    text = "u-fill-$i",
                    createdAt = 1L,
                ),
            )
            rows.add(
                TranscriptEvent(
                    seq = seq++,
                    sessionId = "ignored-on-import",
                    runId = "fill-$i",
                    kind = "assistant",
                    text = "a-fill-$i",
                    createdAt = 1L,
                ),
            )
        }
        rows.add(
            TranscriptEvent(
                seq = seq,
                sessionId = "ignored-on-import",
                runId = "tail",
                kind = "user",
                text = "u-tail",
                createdAt = 1L,
            ),
        )
        val tmpDir = Files.createTempDirectory("room-far-marker-test").toFile()
        try {
            val src = File(tmpDir, "far-marker.jsonl")
            src.writeText(rows.joinToString("\n") { JsonlCodec.encode(it) } + "\n", Charsets.UTF_8)
            val sid = store.importJsonl(src)

            assertEquals(cap, store.countHistoryEvents(sid, includePartial = false))
            val scan = loadModelHistoryScan(store, sid)
            assertEquals(cap, scan.events.size)
            // capHit conservative (true even at exact CAP, not far evidence).
            // Far evidence is the triple below: hasFarReasoning + loaded
            // Incomplete + dropped==0.
            assertTrue("capHit conservative, true even at exact CAP", scan.reasoningIncomplete)
            assertTrue(
                "far marker must be missed by the one-page boundary scan (201..2 only)",
                old !in scan.reasoningRunIds,
            )
            assertTrue("far marker below the extra-page floor must set the probe", scan.hasFarReasoning)
            assertTrue(
                "bounded COUNT probe must still see the far marker below the window",
                store.hasReasoningBefore(sid, scan.events.first().seq),
            )

            val loaded = loadModelHistory(store, sid)
            assertEquals(0, loaded.droppedCount)
            // Far evidence triple: hasFarReasoning + loaded Incomplete + dropped==0.
            assertTrue("far evidence (hasFarReasoning + loaded Incomplete + dropped==0) must fail closed", loaded.reasoningIncomplete)
            assertEquals(cap, loaded.messages.size)
            val assistants = loaded.messages.filter { it.role == "assistant" }
            assertEquals(cap / 2, assistants.size)
            assertEquals("a-old", assistants.first().text)
            assertTrue("far marker assistant must resume flagged", assistants.first().hasReasoning)
            assertTrue(
                "far-marker guard over-flags all kept assistants by design (Target: pair-aware)",
                assistants.all { it.hasReasoning },
            )
            assertTrue(loaded.messages.none { it.role == "reasoning" })
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
