package dev.librepocket.session

import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M4 unit tests (spec §10.3): seq monotonicity, CASCADE delete, prune
 * (age + cap + pinned exemption), JSONL export/import round-trip with
 * per-line JSON validity (`jq`-parseable), batch rollback, redaction on
 * write, truncation, and concurrent-append seq integrity (10×50).
 *
 * Runs on the JVM under Robolectric with an in-memory Room database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionStoreTest {

    private var now: Long = 1_700_000_000_000L
    private lateinit var db: LibrePocketDb
    private lateinit var store: RoomSessionStore
    private lateinit var tmpDir: File

    private fun setUp() {
        db = LibrePocketDb.openInMemory(ApplicationProvider.getApplicationContext())
        store = RoomSessionStore(db) { now }
        tmpDir = Files.createTempDirectory("session-test").toFile()
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

    // ---- seq ----

    @Test fun seq_monotonicAndPaging(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        repeat(5) { i -> store.appendEvent(event(sid, text = "m$i", run = "run-$i")) }

        val all = store.loadEvents(sid)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), all.map { it.seq })

        val page = store.loadEvents(sid, afterSeq = 2, limit = 2)
        assertEquals(listOf(3L, 4L), page.map { it.seq })
        assertEquals(listOf("m2", "m3"), page.map { it.text })
    }

    @Test fun append_unknownSessionFails(): Unit = runBlocking {
        setUp()
        try {
            store.appendEvent(event("no-such-session"))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("unknown session"))
        }
    }

    @Test fun append_unknownKindRejected(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        try {
            store.appendEvent(event(sid, kind = "telepathy"))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // message must not echo attacker-controlled kind verbatim-adjacent? it names the kind;
            // the contract is just rejection here.
            assertTrue(e.message!!.contains("unknown event kind"))
        }
    }

    // ---- cascade ----

    @Test fun delete_cascadesToEvents(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        repeat(3) { store.appendEvent(event(sid)) }
        assertEquals(3, db.sessionDao().eventCount(sid))

        store.deleteSession(sid)
        assertEquals(0, store.loadEvents(sid).size)
        assertEquals(0, db.sessionDao().eventCount(sid))
    }

    // ---- prune ----

    @Test fun prune_deletesStaleKeepsFreshAndPinned(): Unit = runBlocking {
        setUp()
        val old = store.createSession("old", "p/m")
        store.appendEvent(event(old))
        val pinned = store.createSession("pinned", "p/m")
        store.appendEvent(event(pinned))
        db.sessionDao().setPinned(pinned, true)

        now += 100L * 86_400_000L // +100 days
        val fresh = store.createSession("fresh", "p/m")
        store.appendEvent(event(fresh))

        val result = store.prune(PrunePolicy(maxAgeDays = 90))
        assertEquals(1, result.deletedSessions)
        assertEquals(0, result.deletedEvents)

        val remaining = db.sessionDao().allSessions().map { it.sessionId }.toSet()
        assertEquals(setOf(pinned, fresh), remaining)
        // The stale, unpinned session deletion must execute its FK cascade.
        assertEquals(0, db.sessionDao().eventCount(old))
        assertEquals(1, store.loadEvents(pinned).size)
    }

    @Test fun prune_preservesAppendCommittedAfterCandidateSnapshot(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("old", "p/m")
        store.appendEvent(event(sid, text = "before"))
        db.sessionDao().touchSession(sid, now - 31L * 86_400_000L)

        // A second store has its own mutex, so this specifically verifies that the
        // conditional SQL DELETE (not the pruning store's mutex) protects the row.
        val appendingStore = RoomSessionStore(db) { now }
        var interleaved = false
        val result = store.pruneWithBeforeConditionalDelete(
            PrunePolicy(maxAgeDays = 30),
        ) { candidate ->
            if (candidate == sid && !interleaved) {
                interleaved = true
                appendingStore.appendEvent(event(sid, text = "committed after snapshot"))
            }
        }

        assertTrue(interleaved)
        assertEquals(0, result.deletedSessions)
        assertEquals(2, db.sessionDao().eventCount(sid))
        assertEquals(
            listOf("before", "committed after snapshot"),
            store.loadEvents(sid).map { it.text },
        )
    }

    @Test fun prune_preservesPinCommittedAfterCandidateSnapshotWhenConfigured(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("old", "p/m")
        store.appendEvent(event(sid))
        db.sessionDao().touchSession(sid, now - 31L * 86_400_000L)

        var interleaved = false
        val result = store.pruneWithBeforeConditionalDelete(
            PrunePolicy(maxAgeDays = 30, keepPinnedSessions = true),
        ) { candidate ->
            if (candidate == sid && !interleaved) {
                interleaved = true
                db.sessionDao().setPinned(sid, true)
            }
        }

        assertTrue(interleaved)
        assertEquals(0, result.deletedSessions)
        assertTrue(db.sessionDao().sessionById(sid).isPinned)
        assertEquals(1, db.sessionDao().eventCount(sid))
    }

    @Test fun prune_keepPinnedFalseDeletesExpiredPinnedSessionAndCascades(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("old pinned", "p/m")
        store.appendEvent(event(sid))
        db.sessionDao().touchSession(sid, now - 31L * 86_400_000L)
        db.sessionDao().setPinned(sid, true)

        val result = store.prune(
            PrunePolicy(maxAgeDays = 30, keepPinnedSessions = false),
        )

        assertEquals(1, result.deletedSessions)
        assertNull(db.sessionDao().sessionById(sid))
        assertEquals(0, db.sessionDao().eventCount(sid))
    }

    @Test fun prune_capKeepsNewest(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        repeat(8) { i -> store.appendEvent(event(sid, text = "m$i")) }

        val result = store.prune(PrunePolicy(maxAgeDays = 365, maxEventsPerSession = 5))
        assertEquals(0, result.deletedSessions)
        assertEquals(3, result.deletedEvents)

        val kept = store.loadEvents(sid)
        assertEquals(listOf(4L, 5L, 6L, 7L, 8L), kept.map { it.seq })
        assertEquals(listOf("m3", "m4", "m5", "m6", "m7"), kept.map { it.text })
    }

    @Test fun prune_capRetainsNewestActualEventsForSparseImportedSeqs(): Unit = runBlocking {
        setUp()
        val source = File(tmpDir, "sparse.jsonl")
        val sparseSeqs = listOf(1L, 10L, 1_000L, 1_000_000L)
        source.writeText(
            sparseSeqs.joinToString("\n") { seq ->
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
        val sid = store.importJsonl(source)

        val result = store.prune(PrunePolicy(maxAgeDays = 365, maxEventsPerSession = 2))

        assertEquals(0, result.deletedSessions)
        assertEquals(2, result.deletedEvents)
        assertEquals(listOf(1_000L, 1_000_000L), store.loadEvents(sid).map { it.seq })
    }

    // ---- JSONL ----

    @Test fun export_jsonlLinesParseAsJson(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        store.appendEvent(event(sid, kind = "user", text = "mail me at user@example.com"))
        store.appendEvent(event(sid, kind = "assistant", text = "sure thing"))

        val dest = File(tmpDir, "out.jsonl")
        store.exportJsonl(sid, dest)

        val lines = dest.readLines(Charsets.UTF_8)
        assertEquals(2, lines.size)
        for (line in lines) {
            assertTrue("expected object line: $line", line.startsWith("{"))
            Json.parseToJsonElement(line) // throws if not valid JSON: jq-parseable proof
        }
        assertTrue(lines[0].contains("⟦REDACTED:EMAIL⟧"))
        assertTrue(!lines[0].contains("user@example.com"))
        assertTrue(lines[1].contains("\"seq\":2"))
    }

    @Test fun import_roundTrip(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("hello world session", "p/m")
        store.appendEvent(event(sid, kind = "user", text = "first message here", run = "r1"))
        store.appendEvent(event(sid, kind = "assistant", text = "reply", run = "r1"))
        store.appendEvent(event(sid, kind = "user", text = "second", run = "r2"))

        val dest = File(tmpDir, "s.jsonl")
        store.exportJsonl(sid, dest)
        val imported = store.importJsonl(dest)

        assertNotEquals(sid, imported)
        val original = store.loadEvents(sid)
        val back = store.loadEvents(imported)
        assertEquals(original.size, back.size)
        for ((a, b) in original.zip(back)) {
            assertEquals(a.seq, b.seq)
            assertEquals(a.runId, b.runId)
            assertEquals(a.kind, b.kind)
            assertEquals(a.text, b.text)
            assertEquals(a.imagesOmitted, b.imagesOmitted)
            assertEquals(a.createdAt, b.createdAt)
        }
        val meta = db.sessionDao().sessionById(imported)!!
        assertEquals("first message here", meta.title)
    }

    @Test fun import_badLineRollsBackWholeBatch(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        store.appendEvent(event(sid, text = "before"))
        val before = db.sessionDao().allSessions().size

        val bad = File(tmpDir, "bad.jsonl")
        bad.writeText(
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"ok\",\"imagesOmitted\":0,\"createdAt\":1}\n" +
                "this is not json\n",
            Charsets.UTF_8,
        )
        try {
            store.importJsonl(bad)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("line 2"))
            assertTrue(!e.message!!.contains("this is not json"))
        }
        assertEquals(before, db.sessionDao().allSessions().size)
    }

    // ---- redaction on write / truncation ----

    @Test fun append_redactsBeforePersist(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        val secret = "sk-abcDEF1234567890"
        store.appendEvent(event(sid, text = "my key $secret ok"))

        val loaded = store.loadEvents(sid).single().text
        assertTrue(loaded.contains("⟦REDACTED:API_KEY⟧"))
        assertTrue(!loaded.contains(secret))
    }

    @Test fun append_truncatesHugeText(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        store.appendEvent(event(sid, text = "y".repeat(100_001)))

        val loaded = store.loadEvents(sid).single().text
        assertEquals(100_000, loaded.length)
        val flag = db.sessionDao().allEvents(sid).single().isTruncated
        assertTrue(flag)
    }

    @Test fun import_redactsSensitiveContent(): Unit = runBlocking {
        setUp()
        val secretEmail = "user@example.com"
        val secretKey = "sk-abcDEF1234567890"
        val crafted = File(tmpDir, "crafted-sensitive.jsonl")
        crafted.writeText(
            "{\"seq\":1,\"runId\":\"r1\",\"kind\":\"user\",\"text\":\"contact $secretEmail my key $secretKey\",\"imagesOmitted\":0,\"createdAt\":1}\n" +
                "{\"seq\":2,\"runId\":\"r1\",\"kind\":\"assistant\",\"text\":\"call $secretEmail back\",\"imagesOmitted\":0,\"createdAt\":2}\n",
            Charsets.UTF_8,
        )
        val imported = store.importJsonl(crafted)

        val events = store.loadEvents(imported)
        assertEquals(2, events.size)
        for (e in events) {
            assertTrue(!e.text.contains(secretEmail))
            assertTrue(!e.text.contains(secretKey))
        }
        assertTrue(events[0].text.contains("⟦REDACTED:EMAIL⟧"))
        assertTrue(events[0].text.contains("⟦REDACTED:API_KEY⟧"))
        assertTrue(events[1].text.contains("⟦REDACTED:EMAIL⟧"))

        val meta = db.sessionDao().sessionById(imported)!!
        assertTrue(!meta.title.contains(secretEmail))
        assertTrue(!meta.title.contains(secretKey))
        assertTrue(meta.title.contains("⟦REDACTED:EMAIL⟧"))
    }

    // ---- concurrency ----

    @Test fun export_redactsLegacyPlaintextRows(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        // Simulate a legacy DB row written before write-time redaction existed:
        // bypass RoomSessionStore.appendEvent and insert plaintext via the DAO.
        val secretEmail = "user@example.com"
        val secretKey = "sk-abcDEF1234567890"
        val secretPhone = "0912-345-678"
        db.sessionDao().insertEvent(
            TranscriptEventEntity(
                0,
                sid,
                1,
                "run-1",
                "user",
                "contact $secretEmail my key $secretKey call $secretPhone",
                false,
                0,
                now,
            ),
        )

        val dest = File(tmpDir, "legacy.jsonl")
        store.exportJsonl(sid, dest)

        val lines = dest.readLines(Charsets.UTF_8)
        assertEquals(1, lines.size)
        Json.parseToJsonElement(lines[0]) // still jq-parseable
        val out = lines[0]
        assertTrue(!out.contains(secretEmail))
        assertTrue(!out.contains(secretKey))
        assertTrue(!out.contains(secretPhone))
        assertTrue(out.contains("⟦REDACTED"))
    }

    @Test fun import_rejectsBadFieldValues(): Unit = runBlocking {
        setUp()
        val cases = listOf(
            // seq must be >= 1
            "{\"seq\":0,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1}",
            // runId must be non-blank
            "{\"seq\":1,\"runId\":\"\",\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1}",
            "{\"seq\":1,\"runId\":\"  \",\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":1}",
            // counters / timestamps must be non-negative
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":-1,\"createdAt\":1}",
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"t\",\"imagesOmitted\":0,\"createdAt\":-5}",
        )
        for ((i, line) in cases.withIndex()) {
            val f = File(tmpDir, "badfield-$i.jsonl")
            f.writeText(line + "\n", Charsets.UTF_8)
            try {
                store.importJsonl(f)
                fail("case $i must throw")
            } catch (e: IllegalArgumentException) {
                // expected; message carries line number only, never content
            }
        }
    }

    @Test fun import_truncatesHugeTextWithFlag(): Unit = runBlocking {
        setUp()
        val big = "z".repeat(100_001)
        val f = File(tmpDir, "big.jsonl")
        f.writeText(
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"$big\",\"imagesOmitted\":0,\"createdAt\":1}\n",
            Charsets.UTF_8,
        )
        val imported = store.importJsonl(f)
        val loaded = store.loadEvents(imported).single().text
        assertEquals(RoomSessionStore.MAX_TEXT_CHARS, loaded.length)
        assertTrue(db.sessionDao().allEvents(imported).single().isTruncated)
    }

    @Test fun import_tooLargeRefusedBeforeReading(): Unit = runBlocking {
        setUp()
        val before = db.sessionDao().allSessions().size
        val f = File(tmpDir, "huge.jsonl")
        // Just over the gate: mostly zeros, one valid line at the end.
        f.writeText("0".repeat(RoomSessionStore.IMPORT_MAX_BYTES + 1), Charsets.UTF_8)
        try {
            store.importJsonl(f)
            fail("oversize import must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("too large"))
        }
        assertEquals(before, db.sessionDao().allSessions().size)
    }

    @Test fun export_failureLeavesNoPartialFile(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        store.appendEvent(event(sid, text = "hello"))
        // Parent is an existing FILE, so no directory can be created there.
        val blocker = File(tmpDir, "blocker")
        blocker.writeText("x", Charsets.UTF_8)
        val dest = File(blocker, "out.jsonl")
        try {
            store.exportJsonl(sid, dest)
            fail("expected export failure")
        } catch (e: Exception) {
            // expected (IO or export-failed)
        }
        assertTrue(!dest.exists())
    }

    @Test fun append_concurrentKeepsSeqDense(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        List(10) { w ->
            launch(Dispatchers.IO) {
                repeat(50) { i ->
                    store.appendEvent(event(sid, text = "w$w-$i", run = "run-$w"))
                }
            }
        }.joinAll()

        val seqs = store.loadEvents(sid, limit = 1000).map { it.seq }.sorted()
        assertEquals((1L..500L).toList(), seqs)
    }
}
