package dev.librepocket.agent.ui.chat

import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.TranscriptEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Issue #86 Phase B: nightly-only full-stress counterpart of the PR-slimmed
 * snapshot byte-cap tests (Current: PR runs the 11-turn / 22-row slim versions;
 * this file pins the original 100-turn / 200-row / 20M-char stress).
 *
 * Gate: each test `assumeTrue(NIGHTLY_STRESS == "1")` so PR CI skips in
 * milliseconds, while the nightly `nightly-unit-stress` job sets
 * `NIGHTLY_STRESS=1` with an independent 35-minute timeout and uploads JUnit
 * XML artifacts (retained 14 days; red is triaged by filing an issue manually,
 * never blocks merge).
 *
 * Uses the true [FakeSessionStore.appendEvent] shaping (redact + truncate +
 * redactError) — F8 parity path — so the nightly run also guards the
 * production `RoomSessionStore.prepareEvent` cost. PR-slimmed tests use the
 * pre-redacted fast path; this file must NOT use it.
 *
 * Mapping (slim <-> full, same budget behavior; nightly pins the exact
 * original 200-row arithmetic, PR pins the same 20-fit / 21st-overflow rule at
 * 22 rows):
 * - `overBudgetPrefix_full200Rows_abandonsWholeWithMarker` <->
 *   `ChatSnapshotByteCapTest.overBudgetPrefix_loadBoundedAndSnapshotAbandonsWholeWithMarker`
 *   (20 fit / 180 dropped vs 20 fit / 2 dropped; both 0 copied + byte marker;
 *   clean-reopen pin lives in the PR slim test only).
 * - `byteCapOverBudget_fiveDim_full200Rows` <->
 *   `SnapshotFiveDimPhase4Test.byteCapOverBudget_fiveDim` (0 copied + marker;
 *   JSONL+reopen supplement lives in the FiveDim PR test only).
 */
class ChatSnapshotByteCapFullStressTest {

    private fun event(
        sid: String,
        runId: String,
        kind: String,
        text: String,
    ) = TranscriptEvent(
        sessionId = sid,
        runId = runId,
        kind = kind,
        text = text,
        createdAt = 1L,
    )

    private fun rows(store: FakeSessionStore, sid: String) =
        store.events.filter { it.sessionId == sid }.sortedBy { it.seq }

    private fun requireNightly() {
        assumeTrue(
            "nightly-only stress (set NIGHTLY_STRESS=1)",
            System.getenv("NIGHTLY_STRESS") == "1",
        )
    }

    @Test fun overBudgetPrefix_full200Rows_abandonsWholeWithMarker() = runBlocking {
        requireNightly()
        val store = FakeSessionStore()
        val oldId = store.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        // Original full stress: 100 turns x 100k = 200 rows / 20M chars.
        val huge = "x".repeat(100_000)
        repeat(100) { i ->
            store.appendEvent(event(oldId, "r$i", "user", huge))
            store.appendEvent(event(oldId, "r$i", "assistant", huge))
        }

        val loaded = loadSwitchSnapshotEvents(store, oldId)
        assertTrue(loaded.events.size < 200)
        assertTrue(loaded.events.sumOf { it.text.length } <= SnapshotBudget.SNAPSHOT_MAX_TOTAL_CHARS)
        assertEquals(200 - loaded.events.size, loaded.droppedByBytes)
        assertTrue(loaded.droppedByBytes > 0)
        assertEquals(20, loaded.events.size)
        assertEquals(180, loaded.droppedByBytes)

        val newId = store.createSession("t", "preset:openai@https://api.openai.com/gpt-4o")
        val copied = snapshotSwitchHistory(store, oldId, newId, "gpt-4o-mini", "gpt-4o", false)
        assertEquals(0, copied)
        val forked = rows(store, newId)
        assertEquals(2, forked.size)
        assertTrue(forked[0].text.startsWith("model switched gpt-4o-mini -> gpt-4o"))
        assertTrue(isSnapshotIncompleteMarker(forked[1].text))
        assertTrue(forked[1].text.contains("byte budget exceeded"))
        assertTrue(forked.none { it.kind == "user" || it.kind == "assistant" })
        assertTrue(hasIncompleteSnapshotMarker(store, newId))
        assertTrue(findDanglingRunIds(forked).isEmpty())
    }

    @Test fun byteCapOverBudget_fiveDim_full200Rows() = runBlocking {
        requireNightly()
        val transcripts = FakeSessionStore()
        val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        val huge = "x".repeat(100_000)
        repeat(100) { i ->
            transcripts.appendEvent(event(oldId, "r$i", "user", huge))
            transcripts.appendEvent(event(oldId, "r$i", "assistant", huge))
        }
        val newId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o")
        val copied = snapshotSwitchHistory(transcripts, oldId, newId, "gpt-4o-mini", "gpt-4o", false)
        assertEquals(0, copied)
        val forked = rows(transcripts, newId)
        assertEquals(2, forked.size)
        assertTrue(forked[0].text.startsWith("model switched "))
        assertTrue(isSnapshotIncompleteMarker(forked[1].text))
        assertTrue(forked[1].text.contains("byte budget exceeded"))
        assertTrue(findDanglingRunIds(forked).isEmpty())
    }
}
