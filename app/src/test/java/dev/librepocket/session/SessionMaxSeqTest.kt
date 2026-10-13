package dev.librepocket.session

import androidx.test.core.app.ApplicationProvider
import java.io.File
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
 * D15-5 regressions: `seq = Long.MAX_VALUE` is a legal import value and must
 * stay readable, and the append path must refuse fail-closed once the seq
 * space is exhausted (MAX+1 overflows; the (sessionId, seq) index is
 * non-unique, so the DB would not catch the wrap).
 *
 * Room half runs on the JVM under Robolectric with an in-memory database;
 * the fake half pins [FakeSessionStore] parity (default
 * `loadEventsBefore` + guarded `appendEvent`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionMaxSeqRoomTest {

    private var now: Long = 1_700_000_000_000L
    private lateinit var db: LibrePocketDb
    private lateinit var store: RoomSessionStore
    private lateinit var tmpDir: File

    private fun setUp() {
        db = LibrePocketDb.openInMemory(ApplicationProvider.getApplicationContext())
        store = RoomSessionStore(db) { now }
        tmpDir = Files.createTempDirectory("session-maxseq-test").toFile()
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) {
            db.close()
        }
        if (this::tmpDir.isInitialized) {
            tmpDir.deleteRecursively()
        }
    }

    private fun event(
        sid: String,
        kind: String = "user",
        text: String = "hello",
        run: String = "run-1",
    ) = TranscriptEvent(
        sessionId = sid,
        runId = run,
        kind = kind,
        text = text,
        createdAt = now,
    )

    private fun importSeqs(seqs: List<Long>): String {
        val src = File(tmpDir, "max-${seqs.hashCode()}.jsonl")
        src.writeText(
            seqs.joinToString("\n") { seq ->
                JsonlCodec.encode(
                    TranscriptEvent(
                        seq = seq,
                        sessionId = "ignored-on-import",
                        runId = "imported-$seq",
                        kind = "user",
                        text = "event-$seq",
                        createdAt = now,
                    ),
                )
            } + "\n",
            Charsets.UTF_8,
        )
        return runBlocking { store.importJsonl(src) }
    }

    @Test fun importMax_firstBackwardPageSeesMax(): Unit = runBlocking {
        setUp()
        val sid = importSeqs(listOf(1L, 2L, Long.MAX_VALUE - 1, Long.MAX_VALUE))

        // First backward page (the readers' MAX sentinel start) is
        // unbounded: the MAX row is visible, newest-first.
        assertEquals(
            listOf(Long.MAX_VALUE, Long.MAX_VALUE - 1, 2L, 1L),
            store.loadEventsBefore(sid, Long.MAX_VALUE, 10).map { it.seq },
        )
        // Tail / forward / counts agree on the same 4 rows.
        assertEquals(
            listOf(1L, 2L, Long.MAX_VALUE - 1, Long.MAX_VALUE),
            store.loadTailEvents(sid, 10).map { it.seq },
        )
        assertEquals(
            listOf(1L, 2L, Long.MAX_VALUE - 1, Long.MAX_VALUE),
            store.loadEvents(sid).map { it.seq },
        )
        assertEquals(4, store.countEvents(sid))
        assertEquals(4, store.countHistoryEvents(sid, includePartial = true))
        assertEquals(4, store.countHistoryEvents(sid, includePartial = false))
        // Non-sentinel bounds stay strictly exclusive.
        assertEquals(
            listOf(2L, 1L),
            store.loadEventsBefore(sid, Long.MAX_VALUE - 1, 10).map { it.seq },
        )
    }

    @Test fun importMax_exportReimportPreservesSeqs(): Unit = runBlocking {
        setUp()
        val sid = importSeqs(listOf(1L, 2L, Long.MAX_VALUE - 1, Long.MAX_VALUE))

        val dest = File(tmpDir, "max.jsonl")
        store.exportJsonl(sid, dest)
        val sid2 = store.importJsonl(dest)

        assertEquals(
            store.loadEvents(sid).map { it.seq },
            store.loadEvents(sid2).map { it.seq },
        )
        assertEquals(
            store.loadEvents(sid).map { it.text },
            store.loadEvents(sid2).map { it.text },
        )
        // Replay (backward from the sentinel) matches on both ledgers.
        assertEquals(
            store.loadEventsBefore(sid, Long.MAX_VALUE, 10).map { it.seq },
            store.loadEventsBefore(sid2, Long.MAX_VALUE, 10).map { it.seq },
        )
    }

    @Test fun sparseMultiPage_backwardWalkCoversAllExactlyOnce(): Unit = runBlocking {
        setUp()
        val dense = (1L..250L).toList()
        val sid = importSeqs(dense + listOf(Long.MAX_VALUE - 1, Long.MAX_VALUE))

        val seen = ArrayList<Long>()
        var before = Long.MAX_VALUE
        while (true) {
            val page = store.loadEventsBefore(sid, before, 100)
            if (page.isEmpty()) break
            seen.addAll(page.map { it.seq })
            val pageMin = page.minOf { it.seq }
            assertTrue("backward pages must advance below the previous minimum", pageMin < before)
            before = pageMin
            if (page.size < 100) break
        }

        assertEquals(252, seen.size)
        assertEquals(252, seen.toSet().size)
        assertEquals(Long.MAX_VALUE, seen.first())
        assertEquals(dense + listOf(Long.MAX_VALUE - 1, Long.MAX_VALUE), seen.sorted())
    }

    @Test fun appendAfterMax_failClosed(): Unit = runBlocking {
        setUp()
        val sid = importSeqs(listOf(Long.MAX_VALUE - 1, Long.MAX_VALUE))

        try {
            store.appendEvent(event(sid, text = "one past max"))
            fail("append past Long.MAX_VALUE must throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("seq exhausted"))
        }
        assertEquals(2, store.countEvents(sid))
        assertEquals(
            listOf(Long.MAX_VALUE - 1, Long.MAX_VALUE),
            store.loadEvents(sid).map { it.seq },
        )

        // Batch refuses atomically too: zero snapshot rows, not a half copy.
        try {
            store.appendEvents(listOf(event(sid, text = "b1"), event(sid, text = "b2")))
            fail("batch append past Long.MAX_VALUE must throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("seq exhausted"))
        }
        assertEquals(2, store.countEvents(sid))
        assertEquals(
            listOf(Long.MAX_VALUE - 1, Long.MAX_VALUE),
            store.loadTailEvents(sid, 10).map { it.seq },
        )
    }

    @Test fun midBatchOverflow_rollsBackZeroRows(): Unit = runBlocking {
        setUp()
        val sid = importSeqs(listOf(Long.MAX_VALUE - 1))
        val beforeTexts = store.loadEvents(sid).map { it.text }

        // First row would take MAX, second exhausts: the whole batch must
        // roll back to zero new rows.
        try {
            store.appendEvents(listOf(event(sid, text = "b1"), event(sid, text = "b2")))
            fail("mid-batch overflow must throw with zero new rows")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("seq exhausted"))
        }
        assertEquals(1, store.countEvents(sid))
        assertEquals(
            listOf(Long.MAX_VALUE - 1),
            store.loadEvents(sid).map { it.seq },
        )
        assertEquals(beforeTexts, store.loadEvents(sid).map { it.text })
    }
}

class SessionMaxSeqFakeTest {

    private fun seedMaxPair(store: FakeSessionStore, sid: String) {
        store.events.add(
            TranscriptEvent(
                seq = Long.MAX_VALUE - 1,
                sessionId = sid,
                runId = "r-max",
                kind = "user",
                text = "u-old",
                createdAt = 1L,
            ),
        )
        store.events.add(
            TranscriptEvent(
                seq = Long.MAX_VALUE,
                sessionId = sid,
                runId = "r-max",
                kind = "assistant",
                text = "a-max",
                createdAt = 1L,
            ),
        )
    }

    @Test fun firstBackwardPageSeesMax(): Unit = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("t", "p/m")
        seedMaxPair(store, sid)

        assertEquals(
            listOf(Long.MAX_VALUE, Long.MAX_VALUE - 1),
            store.loadEventsBefore(sid, Long.MAX_VALUE, 10).map { it.seq },
        )
        assertEquals(
            listOf(Long.MAX_VALUE - 1, Long.MAX_VALUE),
            store.loadTailEvents(sid, 10).map { it.seq },
        )
        assertEquals(2, store.countEvents(sid))
        // Non-sentinel bounds stay strictly exclusive: the bound row itself
        // is not returned.
        assertEquals(
            emptyList<Long>(),
            store.loadEventsBefore(sid, Long.MAX_VALUE - 1, 10).map { it.seq },
        )
    }

    @Test fun appendAfterMax_failClosed(): Unit = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("t", "p/m")
        seedMaxPair(store, sid)

        try {
            store.appendEvent(
                TranscriptEvent(sessionId = sid, runId = "r", kind = "user", text = "x", createdAt = 1L),
            )
            fail("fake append past Long.MAX_VALUE must throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("seq exhausted"))
        }
        assertEquals(2, store.events.size)

        try {
            store.appendEvents(
                listOf(
                    TranscriptEvent(sessionId = sid, runId = "r", kind = "user", text = "y", createdAt = 1L),
                ),
            )
            fail("fake batch append past Long.MAX_VALUE must throw with zero rows")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("seq exhausted"))
        }
        assertEquals(2, store.events.size)
    }

    @Test fun midBatchOverflow_rollsBackZeroRows(): Unit = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("t", "p/m")
        store.events.add(
            TranscriptEvent(
                seq = Long.MAX_VALUE - 1,
                sessionId = sid,
                runId = "r-max",
                kind = "user",
                text = "u-old",
                createdAt = 1L,
            ),
        )
        val beforeTexts = store.events.map { it.text }

        // First row would take MAX, second exhausts: snapshot rollback must
        // leave zero new rows.
        try {
            store.appendEvents(
                listOf(
                    TranscriptEvent(sessionId = sid, runId = "r", kind = "user", text = "b1", createdAt = 1L),
                    TranscriptEvent(sessionId = sid, runId = "r", kind = "user", text = "b2", createdAt = 1L),
                ),
            )
            fail("fake mid-batch overflow must throw with zero new rows")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("seq exhausted"))
        }
        assertEquals(1, store.events.size)
        assertEquals(listOf(Long.MAX_VALUE - 1), store.events.map { it.seq })
        assertEquals(beforeTexts, store.events.map { it.text })
    }

    @Test fun appendNormal_stillDense(): Unit = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("t", "p/m")
        repeat(3) {
            store.appendEvent(
                TranscriptEvent(sessionId = sid, runId = "r", kind = "user", text = "m", createdAt = 1L),
            )
        }
        assertEquals(listOf(1L, 2L, 3L), store.events.map { it.seq })
    }
}
