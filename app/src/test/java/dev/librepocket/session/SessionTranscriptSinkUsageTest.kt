package dev.librepocket.session

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #84 sink-level regressions for [SessionTranscriptSink.onUsage]: pre-terminal
 * calls merge last-wins by runId and flush as exactly one `system` row with
 * the attempt terminal (flood-safe, capped); a post-terminal trailing call
 * keeps the legacy single-row shape.
 */
class SessionTranscriptSinkUsageTest {

    private fun usageRows(store: FakeSessionStore): List<TranscriptEvent> =
        store.events.filter { it.kind == "system" && it.text.startsWith("usage ") }

    @Test
    fun dualPreTerminalUsageMergesToSingleRowWithFinalValues() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        // Anthropic shape: input-only first, full values on the delta.
        sink.onUsage("r1", 10, null)
        sink.onUsage("r1", 10, 20)
        sink.onTurnSucceeded("r1", "hi there", hasReasoning = false)

        val rows = usageRows(store)
        assertEquals(1, rows.size)
        assertEquals("usage input=10 output=20", rows.single().text)
        assertEquals("r1", rows.single().runId)
    }

    @Test
    fun splitNullsMergeLastWins() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        sink.onUsage("r1", 10, null)
        sink.onUsage("r1", null, 20)
        sink.onTurnSucceeded("r1", "hi there", hasReasoning = false)

        val rows = usageRows(store)
        assertEquals(1, rows.size)
        assertEquals("usage input=10 output=20", rows.single().text)
    }

    @Test
    fun flood100PreTerminalStillSingleRow() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        repeat(100) { i -> sink.onUsage("r1", i, i * 2) }
        sink.onTurnSucceeded("r1", "hi there", hasReasoning = false)

        val rows = usageRows(store)
        assertEquals(1, rows.size)
        assertEquals("usage input=99 output=198", rows.single().text)
    }

    @Test
    fun usageFloodCapFreezesPast128() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        repeat(130) { i -> sink.onUsage("r1", i, i * 2) }
        sink.onTurnSucceeded("r1", "hi there", hasReasoning = false)

        // Past MAX_USAGE_EVENTS_PER_RUN the merge freezes (never a failure,
        // never unbounded CPU): the row keeps the cap's values, still one row.
        val rows = usageRows(store)
        assertEquals(1, rows.size)
        assertEquals("usage input=127 output=254", rows.single().text)
    }

    @Test
    fun distinctRunsDoNotMerge() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        sink.onUsage("a", 1, 1)
        sink.onUsage("b", 2, 2)
        sink.onTurnSucceeded("a", "first", hasReasoning = false)
        sink.onTurnSucceeded("b", "second", hasReasoning = false)

        val rows = usageRows(store)
        assertEquals(2, rows.size)
        assertEquals("usage input=1 output=1", rows.first { it.runId == "a" }.text)
        assertEquals("usage input=2 output=2", rows.first { it.runId == "b" }.text)
    }

    @Test
    fun trailingPostTerminalUsageAppendsOnceThenDrops() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        // Legacy call order (terminal already written): the first trailing
        // call keeps the historical single-row shape, the rest rate-limit.
        sink.onTurnSucceeded("r1", "hi there", hasReasoning = false)
        sink.onUsage("r1", 10, 20)
        sink.onUsage("r1", 11, 21)
        sink.onUsage("r1", 12, 22)

        val rows = usageRows(store)
        assertEquals(1, rows.size)
        assertEquals("usage input=10 output=20", rows.single().text)
    }

    @Test
    fun failedTerminalFlushesPendingUsage() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        sink.onUsage("r1", 7, null)
        sink.onUsage("r1", null, 8)
        sink.onTurnFailed("r1", "half", "boom", null, 0, true, false)

        val rows = usageRows(store)
        assertEquals(1, rows.size)
        assertEquals("usage input=7 output=8", rows.single().text)
    }

    @Test
    fun cancelledTerminalFlushesPendingUsage() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        sink.onUsage("r1", 3, 4)
        sink.onTurnCancelled("r1", "partial", null, 0, false)

        val rows = usageRows(store)
        assertEquals(1, rows.size)
        assertEquals("usage input=3 output=4", rows.single().text)
    }

    @Test
    fun usageCapExactBoundary128And129Freeze() = runBlocking {
        // 128 events merge fully (last wins = event 127); the 129th is
        // ignored: both freeze at (127, 254), pinning `seen <= 128`.
        for (count in listOf(128, 129)) {
            val store = FakeSessionStore()
            val sid = store.createSession("hi", "m")
            val sink = SessionTranscriptSink(store, sid) { 42L }

            repeat(count) { i -> sink.onUsage("r1", i, i * 2) }
            sink.onTurnSucceeded("r1", "hi there", hasReasoning = false)

            val rows = usageRows(store)
            assertEquals("$count events still one row", 1, rows.size)
            assertEquals("usage input=127 output=254", rows.single().text)
        }
    }

    @Test
    fun distinctRunsCappedAt256DropsBestEffort() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        // 300 distinct pre-terminal runIds: only the first 256 buffer
        // (insertion order); the rest drop best-effort without throwing.
        repeat(300) { i -> sink.onUsage("r$i", i, i) }
        // A buffered run must still merge while full (the cap only gates new
        // runIds, never updates to an already-buffered one).
        sink.onUsage("r0", 100, 100)
        sink.onTurnSucceeded("r0", "kept", hasReasoning = false)
        sink.onTurnSucceeded("r299", "dropped", hasReasoning = false)

        val rows = usageRows(store)
        assertEquals("usage input=100 output=100", rows.first { it.runId == "r0" }.text)
        assertTrue("r299 usage must have been dropped", rows.none { it.runId == "r299" })
    }

    @Test
    fun trailingAfterFailedAppendsOnceThenDrops() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        sink.onTurnFailed("r1", "half", "boom", null, 0, true, false)
        sink.onUsage("r1", 10, 20)
        sink.onUsage("r1", 11, 21)

        val rows = usageRows(store)
        assertEquals(1, rows.size)
        assertEquals("usage input=10 output=20", rows.single().text)
    }
}
