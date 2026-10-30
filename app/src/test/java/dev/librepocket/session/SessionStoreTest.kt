package dev.librepocket.session

import androidx.test.core.app.ApplicationProvider
import dev.librepocket.agent.ui.chat.findDanglingRunIds
import dev.librepocket.agent.ui.chat.snapshotSwitchHistory
import dev.librepocket.redact.Redactor
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

    @Test fun export_missingOrDeletedSessionPreservesDestinationButEmptySessionWorks(): Unit = runBlocking {
        setUp()
        val empty = store.createSession("empty", "p/m")
        val dest = File(tmpDir, "session.jsonl")
        store.exportJsonl(empty, dest)
        assertEquals("", dest.readText())
        store.deleteSession(empty)
        for (sid in listOf("missing-session", empty)) {
            dest.writeText("existing")
            try {
                store.exportJsonl(sid, dest)
                fail("missing session must fail")
            } catch (e: IllegalArgumentException) {
                assertEquals("unknown session", e.message)
            }
            assertEquals("existing", dest.readText())
            assertEquals(listOf("session.jsonl"), tmpDir.list()!!.sorted())
        }
    }

    @Test fun export_invalidEventShapesPreserveDestination(): Unit = runBlocking {
        setUp()
        val invalid = listOf(
            event("unused", kind = "assistant").copy(isFinal = false, isPartial = false),
            event("unused", kind = "user").copy(imagesOmitted = -1),
            event("unused", kind = "user").copy(parentRunId = "parent"),
            event("unused", kind = "user").copy(createdAt = -1),
        )
        val dest = File(tmpDir, "invalid.jsonl")
        for (bad in invalid) {
            val sid = store.createSession("t", "p/m")
            store.appendEvent(event(sid, text = "valid first"))
            store.appendEvent(bad.copy(sessionId = sid))
            dest.writeText("existing")
            try {
                store.exportJsonl(sid, dest)
                fail("invalid event must fail export")
            } catch (e: IllegalArgumentException) {
                assertEquals("import failed at line 2", e.message)
            }
            assertEquals("existing", dest.readText())
            assertEquals(listOf("invalid.jsonl"), tmpDir.list()!!.sorted())
        }
    }

    @Test fun export_invalidSequencesIncludingPageBoundaryPreserveDestination(): Unit = runBlocking {
        setUp()
        val dest = File(tmpDir, "sequence.jsonl")
        for (duplicateAtBoundary in listOf(false, true)) {
            val sid = store.createSession("t", "p/m")
            if (duplicateAtBoundary) insertLegacyExportRows(sid, RoomSessionStore.EXPORT_PAGE_SIZE, "valid")
            val seq = if (duplicateAtBoundary) RoomSessionStore.EXPORT_PAGE_SIZE.toLong() else 0L
            db.sessionDao().insertEvent(TranscriptEventEntity(
                0, sid, seq, "r", "user", "invalid sequence", false, 0, now, false, null, null, null,
            ))
            dest.writeText("existing")
            try {
                store.exportJsonl(sid, dest)
                fail("invalid sequence must fail export")
            } catch (e: IllegalArgumentException) {
                assertEquals("export failed: invalid event sequence", e.message)
            }
            assertEquals("existing", dest.readText())
            assertEquals(listOf("sequence.jsonl"), tmpDir.list()!!.sorted())
        }
    }

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
            assertEquals(a.kind, b.kind)
            assertEquals(a.text, b.text)
            assertEquals(a.imagesOmitted, b.imagesOmitted)
            assertEquals(a.createdAt, b.createdAt)
        }
        assertEquals(back[0].runId, back[1].runId)
        assertNotEquals(back[0].runId, back[2].runId)
        val meta = db.sessionDao().sessionById(imported)!!
        assertEquals("first message here", meta.title)
    }

    @Test fun import_roundTripPreservesReasoningMarker(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("hello world session", "p/m")
        store.appendEvent(event(sid, kind = "user", text = "first message here", run = "r1"))
        store.appendEvent(event(sid, kind = "assistant", text = "reply", run = "r1"))
        // Payload-free resume marker: same runId as its assistant event, empty text.
        store.appendEvent(event(sid, kind = "reasoning", text = "", run = "r1"))

        val dest = File(tmpDir, "s.jsonl")
        store.exportJsonl(sid, dest)
        val imported = store.importJsonl(dest)

        val back = store.loadEvents(imported)
        assertEquals(listOf("user", "assistant", "reasoning"), back.map { it.kind })
        val marker = back.single { it.kind == "reasoning" }
        assertEquals(back.first { it.kind == "assistant" }.runId, marker.runId)
        assertEquals("", marker.text)
    }

    @Test fun import_nonEmptyReasoningMarkerRejected(): Unit = runBlocking {
        setUp()
        val bad = File(tmpDir, "bad-reasoning.jsonl")
        bad.writeText(
            "{\"seq\":1,\"runId\":\"r1\",\"kind\":\"reasoning\",\"text\":\"smuggled thought\",\"imagesOmitted\":0,\"createdAt\":1}\n",
            Charsets.UTF_8,
        )
        try {
            store.importJsonl(bad)
            fail("non-empty reasoning marker must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("line 1"))
        }
    }

    @Test fun append_nonEmptyReasoningMarkerRejected(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        try {
            store.appendEvent(event(sid, kind = "reasoning", text = "smuggled thought", run = "r1"))
            fail("non-empty reasoning marker must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("reasoning"))
        }
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
        assertTrue(store.loadEvents(sid).single().isTruncated)
        assertTrue(store.loadTailEvents(sid, 1).single().isTruncated)
        assertTrue(store.loadEventsBefore(sid, Long.MAX_VALUE, 1).single().isTruncated)
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
                false,
                null,
                null,
                null,
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

    @Test fun export_idEncodingSurvivesRedactionAtNumericBoundaries() {
        val uuids = listOf(
            "00000000-0000-4000-8000-0900123456ff",
            "41111111-1111-4111-8111-111111111111",
            "ffffffff-ffff-4fff-bfff-ffffffffffff",
        )
        val ids = uuids.map { redactionSafeExportId(UUID.fromString(it)) }
        assertEquals(ids.size, ids.toSet().size)
        for (id in ids) {
            assertTrue(id.matches(Regex("[a-p]{32}")))
            for (tail in listOf("cancelled", "interrupted", "failed: safe")) {
                val marker = "turn $id $tail"
                assertEquals(marker, Redactor.redact(marker).text)
            }
        }
        assertNotEquals("turn ${uuids[0]} cancelled", Redactor.redact("turn ${uuids[0]} cancelled").text)
    }

    @Test fun export_alreadyRedactedMarkerFailsWithoutReplacingDestination(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        val run = "user@example.com"
        store.appendEvent(event(sid, kind = "user", text = "q", run = run))
        store.appendEvent(event(sid, kind = "system", text = "turn $run cancelled", run = run))
        val stored = store.loadEvents(sid)
        assertEquals("turn ⟦REDACTED:EMAIL⟧ cancelled", stored[1].text)
        assertExportLimitPreservesDestination(sid, "export cannot identify redacted terminal marker")
    }

    @Test fun export_bearerRedactionThatConsumedTerminalFailsClosed(): Unit = runBlocking {
        setUp()
        for (tail in listOf("cancelled", "interrupted", "failed: synthetic")) {
            val sid = store.createSession("t", "p/m")
            val run = "bearer syntheticSecret123"
            store.appendEvent(event(sid, kind = "user", text = "q", run = run))
            store.appendEvent(event(sid, kind = "system", text = "turn $run $tail", run = run))
            assertTrue(!store.loadEvents(sid)[1].text.contains("syntheticSecret123"))
            assertExportLimitPreservesDestination(sid, "export cannot identify redacted terminal marker")
        }
    }

    @Test fun export_remapsSensitiveIdsAcrossPagesAndImport(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        val logical = "private-user@example.com"
        val attempt = "sk-abcDEF1234567890"
        val other = "sk-abcDEF1234567891"
        // Import maps arbitrary IDs before persistence; export maps them again.
        val source = File(tmpDir, "ids.jsonl")
        val events = (1..RoomSessionStore.EXPORT_PAGE_SIZE + 1).map { seq ->
            TranscriptEvent(
                seq = seq.toLong(), sessionId = sid,
                runId = if (seq == 1) logical else if (seq == 2) other else attempt,
                kind = if (seq == 1) "user" else "assistant", text = "safe",
                createdAt = now, parentRunId = if (seq == 1) null else logical,
                attemptIndex = if (seq == 1) null else 1,
            )
        }
        source.writeText(events.joinToString("\n") { JsonlCodec.encode(it) } + "\n")
        val imported = store.importJsonl(source)
        val dest = File(tmpDir, "ids-export.jsonl")
        store.exportJsonl(imported, dest)
        val raw = dest.readText()
        for (secret in listOf(logical, attempt, other)) assertTrue(!raw.contains(secret))
        val lines = dest.readLines().mapIndexed { i, line -> JsonlCodec.decode(i + 1, line) }
        assertEquals(lines[0].runId, lines[1].parentRunId)
        assertEquals(lines[0].runId, lines.last().parentRunId)
        assertEquals(lines[2].runId, lines.last().runId)
        assertNotEquals(lines[1].runId, lines[2].runId)
        assertNotEquals(lines[0].runId, lines[2].runId)
        val restored = store.loadEvents(store.importJsonl(dest), limit = events.size)
        assertEquals(restored[0].runId, restored[1].parentRunId)
        assertEquals(restored[0].runId, restored.last().parentRunId)
        assertEquals(restored[2].runId, restored.last().runId)
        assertNotEquals(restored[1].runId, restored[2].runId)
        assertEquals(events.map { it.attemptIndex }, restored.map { it.attemptIndex })
        // Export must not rewrite existing Room data.
        val stored = store.loadEvents(imported, limit = events.size)
        assertTrue(stored.none { it.runId in listOf(logical, attempt, other) || it.parentRunId == logical })
        assertEquals(stored[0].runId, stored.last().parentRunId)
        val second = File(tmpDir, "ids-export-again.jsonl")
        store.exportJsonl(imported, second)
        assertNotEquals(lines[0].runId, JsonlCodec.decode(1, second.readLines().first()).runId)
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

    @Test fun import_mapsAllIdsAndRewritesTerminalsBeforePersistence(): Unit = runBlocking {
        setUp()
        val logical = "person@example.com"
        val attempt = "sk-abcDEF1234567890"
        for (tail in listOf("cancelled", "interrupted", "failed: synthetic")) {
            val events = listOf(
                event("unused", kind = "user", text = "question", run = logical).copy(seq = 1),
                event("unused", kind = "assistant", text = "answer", run = attempt)
                    .copy(seq = 2, parentRunId = logical, attemptIndex = 1),
                event("unused", kind = "system", text = "turn $logical $tail", run = logical).copy(seq = 3),
            )
            val imported = store.importJsonl(events.joinToString("\n") { JsonlCodec.encode(it) }.byteInputStream())
            val rows = db.sessionDao().allEvents(imported)
            assertEquals(rows[0].runId, rows[1].parentRunId)
            assertEquals(rows[0].runId, rows[2].runId)
            assertNotEquals(rows[0].runId, rows[1].runId)
            assertEquals("turn ${rows[0].runId} $tail", rows[2].text)
            assertTrue(rows.none { it.runId == logical || it.runId == attempt || it.parentRunId == logical })
            assertTrue(findDanglingRunIds(store.loadEvents(imported)).isEmpty())
        }
    }

    @Test fun import_alreadyRedactedTerminalFailsBeforeWriting(): Unit = runBlocking {
        setUp()
        for (run in listOf("person@example.com", "bearer syntheticSecret123")) {
            for (tail in listOf("cancelled", "interrupted", "failed: synthetic")) {
                val marker = Redactor.redact("turn $run $tail").text
                val first = event("unused", kind = "user", text = "q", run = run).copy(seq = 1)
                val second = event("unused", kind = "system", text = marker, run = run).copy(seq = 2)
                val source = listOf(first, second).joinToString("\n") { JsonlCodec.encode(it) }
                try {
                    store.importJsonl(source.byteInputStream())
                    fail("unidentifiable terminal must fail")
                } catch (e: IllegalArgumentException) {
                    assertEquals("import failed at line 2", e.message)
                }
                assertTrue(store.listSessions().isEmpty())
            }
        }
    }

    @Test fun import_rejectsMalformedUtf8WithoutWriting(): Unit = runBlocking {
        setUp()
        val valid = JsonlCodec.encode(event("unused", text = "valid", run = "r").copy(seq = 1))
        val prefix = "\n{\"seq\":2,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"".toByteArray()
        val suffix = "\",\"imagesOmitted\":0,\"createdAt\":1}".toByteArray()
        for (invalid in listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xe2.toByte(), 0x82.toByte()))) {
            val bytes = valid.toByteArray() + prefix + invalid + suffix
            try {
                store.importJsonl(bytes.inputStream())
                fail("malformed UTF-8 must fail")
            } catch (e: IllegalArgumentException) {
                assertEquals("import failed: invalid UTF-8", e.message)
            }
            assertTrue(store.listSessions().isEmpty())
        }
        val id = store.importJsonl(JsonlCodec.encode(event("unused", text = "合法�字", run = "r").copy(seq = 1)).byteInputStream())
        assertEquals("合法�字", store.loadEvents(id).single().text)
    }

    @Test fun import_rejectsOversizedRedactedTextWithoutCreatingSession(): Unit = runBlocking {
        setUp()
        for (big in listOf("z".repeat(100_001), "x".repeat(99_990) + " a@b.co")) {
            val valid = JsonlCodec.encode(event("unused").copy(seq = 1))
            val invalid = JsonlCodec.encode(event("unused", text = big).copy(seq = 2))
            try {
                store.importJsonl("$valid\n$invalid\n".byteInputStream())
                fail("oversized redacted text must fail")
            } catch (e: JsonlImportLimitException) {
                assertEquals("import failed at line 2", e.message)
            }
            assertTrue(store.listSessions().isEmpty())
        }
    }

    @Test fun export_preservesTruncationFlagAndRejectsInvalidFlag(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        store.appendEvent(event(sid, text = "x".repeat(100_001)))
        val dest = File(tmpDir, "truncated.jsonl")
        store.exportJsonl(sid, dest)
        val line = dest.readText().trimEnd()
        assertTrue(JsonlCodec.decode(1, line).isTruncated)
        val imported = store.importJsonl(dest)
        assertTrue(db.sessionDao().allEvents(imported).single().isTruncated)
        assertTrue(store.loadEvents(imported).single().isTruncated)
        assertEquals(100_000, store.loadEvents(imported).single().text.length)
        val before = store.listSessions().size
        for (invalid in listOf("null", "1", "\"true\"", "{}")) {
            try {
                store.importJsonl(line.replace("\"isTruncated\":true", "\"isTruncated\":$invalid").byteInputStream())
                fail("invalid flag must fail")
            } catch (e: IllegalArgumentException) {
                assertEquals("import failed at line 1", e.message)
            }
            assertEquals(before, store.listSessions().size)
        }
        assertTrue(!JsonlCodec.decode(1, JsonlCodec.encode(event(sid).copy(seq = 1))).isTruncated)
        assertTrue(JsonlCodec.decode(1, JsonlCodec.encode(event(sid).copy(seq = 1, isTruncated = true))).isTruncated)
    }

    @Test fun snapshot_preservesExistingAndNewTruncationFlags(): Unit = runBlocking {
        setUp()
        val source = store.createSession("source", "p/m")
        val target = store.createSession("target", "p/m")
        store.appendEvent(event(source, text = "already shortened").copy(isTruncated = true))
        store.appendEvent(event(source, kind = "assistant", text = "answer"))
        store.appendEvents(listOf(
            event(source, text = "x".repeat(RoomSessionStore.MAX_TEXT_CHARS + 1), run = "other"),
            event(source, kind = "assistant", text = "complete", run = "other"),
        ))
        val original = store.loadEvents(source)
        assertEquals(listOf(true, false, true, false), original.map { it.isTruncated })

        assertEquals(4, snapshotSwitchHistory(store, source, target, "old", "new", false))
        val copied = store.loadEvents(target).filter { it.kind == "user" || it.kind == "assistant" }
        assertEquals(original.map { it.text }, copied.map { it.text })
        assertEquals(listOf(true, false, true, false), copied.map { it.isTruncated })
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

    @Test fun import_lineAndRowLimitsFailBeforeWriting(): Unit = runBlocking {
        setUp()
        val tooLong = " ".repeat(RoomSessionStore.IMPORT_MAX_LINE_CHARS + 1)
        try {
            store.importJsonl(tooLong.byteInputStream())
            fail("oversized blank line must fail")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("line too large"))
        }
        val rows = buildString {
            for (i in 1..RoomSessionStore.IMPORT_MAX_ROWS + 1) {
                append("{\"seq\":$i,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"x\",\"imagesOmitted\":0,\"createdAt\":1}\n")
            }
        }
        try {
            store.importJsonl(rows.byteInputStream())
            fail("too many rows must fail")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("too large"))
        }
        assertTrue(store.listSessions().isEmpty())
    }

    @Test fun import_lineLimitIsInclusiveForLegacyBackup(): Unit = runBlocking {
        setUp()
        val prefix = "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"x\",\"padding\":\""
        val suffix = "\",\"imagesOmitted\":0,\"createdAt\":1}"
        val line = prefix + "x".repeat(RoomSessionStore.IMPORT_MAX_LINE_CHARS - prefix.length - suffix.length) + suffix
        val id = store.importJsonl(line.byteInputStream())
        assertEquals(1, store.countEvents(id))
        try {
            store.importJsonl((line + " ").byteInputStream())
            fail("line one code unit over the limit must fail")
        } catch (e: JsonlImportLimitException) {
            assertTrue(e.message!!.contains("line too large"))
        }
        assertEquals(1, store.listSessions().size)
    }

    @Test fun import_contentLimitAndLineEndings(): Unit = runBlocking {
        setUp()
        val prefix = "{\"seq\":%d,\"runId\":\"r\",\"kind\":\"user\",\"text\":\""
        val suffix = "\",\"imagesOmitted\":0,\"createdAt\":1}"
        val valid = prefix.format(1) + "\\u0061".repeat(20_000) + suffix + "\r\n" +
            prefix.format(2) + "b" + suffix + "\r"
        val id = store.importJsonl(valid.byteInputStream())
        assertEquals(2, store.countEvents(id))
        val large = buildString {
            for (i in 1..10) append(prefix.format(i)).append("x\",\"padding\":\"")
                .append("x".repeat(900_000)).append(suffix).append('\n')
        }
        try {
            store.importJsonl(large.byteInputStream())
            fail("aggregate content limit must fail")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("too large"))
        }
        assertEquals(1, store.listSessions().size)
    }

    @Test fun import_cancelDuringTransactionRollsBack(): Unit = runBlocking {
        setUp()
        store.importAfterInsertHookForTest = { it?.cancel() }
        val source = """{"seq":1,"runId":"r","kind":"user","text":"x","imagesOmitted":0,"createdAt":1}"""
        val result = async { store.importJsonl(source.byteInputStream()) }
        try {
            result.await()
            fail("expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // The explicit post-insert gate must abort the transaction.
        } finally {
            store.importAfterInsertHookForTest = null
        }
        assertTrue(store.listSessions().isEmpty())
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

    private fun insertLegacyExportRows(sid: String, count: Int, text: String, runId: String = "r") {
        db.runInTransaction {
            repeat(count) { index ->
                db.sessionDao().insertEvent(
                    TranscriptEventEntity(
                        0, sid, index.toLong() + 1, runId, "user", text,
                        false, 0, now, false, null, null, null,
                    ),
                )
            }
        }
    }

    private suspend fun assertExportLimitPreservesDestination(sid: String, expectedMessage: String) {
        val dest = File(tmpDir, "limited.jsonl")
        dest.writeText("existing destination", Charsets.UTF_8)
        try {
            store.exportJsonl(sid, dest)
            fail("expected export limit failure")
        } catch (e: JsonlExportLimitException) {
            assertEquals(expectedMessage, e.message)
        }
        assertEquals("existing destination", dest.readText(Charsets.UTF_8))
        assertEquals("temporary export must be removed", listOf(dest.name), tmpDir.list()!!.sorted())
    }

    @Test fun export_lineLimitFailureRemovesTmpAndPreservesDestination(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        // A legacy row can exceed current append-time truncation.
        insertLegacyExportRows(sid, 1, "x".repeat(RoomSessionStore.IMPORT_MAX_LINE_CHARS))
        assertExportLimitPreservesDestination(sid, "export line too large")
    }

    @Test fun export_legacyTextOverImportCapPreservesDestination(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        insertLegacyExportRows(sid, 1, "x".repeat(100_001))
        assertExportLimitPreservesDestination(sid, "export text exceeds import limit (100000 characters)")
    }

    @Test fun export_redactionExpansionOverImportCapPreservesDestination(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        insertLegacyExportRows(sid, 1, "x".repeat(99_990) + " a@b.co")
        assertExportLimitPreservesDestination(sid, "export text exceeds import limit (100000 characters)")
    }

    @Test fun export_textAtImportCapRoundTripsWithoutTruncation(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        val text = "x".repeat(100_000)
        insertLegacyExportRows(sid, 1, text)
        val dest = File(tmpDir, "at-cap.jsonl")
        store.exportJsonl(sid, dest)
        val imported = store.importJsonl(dest)
        assertEquals(text, store.loadEvents(imported).single().text)
        assertTrue(!db.sessionDao().allEvents(imported).single().isTruncated)
    }

    @Test fun export_oversizedLegacyIdPreservesDestination(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        insertLegacyExportRows(sid, 1, "safe", "x".repeat(RoomSessionStore.IMPORT_MAX_LINE_CHARS + 1))
        assertExportLimitPreservesDestination(sid, "export IDs too large")
    }

    @Test fun export_totalDistinctLegacyIdsAreBounded(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        db.runInTransaction {
            repeat(9) { index ->
                db.sessionDao().insertEvent(TranscriptEventEntity(
                    0, sid, index.toLong() + 1, "$index" + "x".repeat(1_000_000), "user", "safe",
                    false, 0, now, false, null, null, null,
                ))
            }
        }
        assertExportLimitPreservesDestination(sid, "export IDs too large")
    }

    @Test fun export_rowLimitFailureRemovesTmpAndPreservesDestination(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        insertLegacyExportRows(sid, RoomSessionStore.IMPORT_MAX_ROWS + 1, "x")
        assertExportLimitPreservesDestination(sid, "export too large")
    }

    @Test fun export_contentLimitFailureRemovesTmpAndPreservesDestination(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        // Each encoded line is below the line limit; the total exceeds the character limit.
        insertLegacyExportRows(sid, 85, "x".repeat(100_000))
        assertExportLimitPreservesDestination(sid, "export too large")
    }

    @Test fun export_utf8ByteLimitFailureRemovesTmpAndPreservesDestination(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        // The aggregate remains below the character limit but exceeds 20 MiB in UTF-8.
        insertLegacyExportRows(sid, 70, "界".repeat(100_000))
        assertExportLimitPreservesDestination(sid, "export too large")
    }

    @Test fun export_multibyteAcrossPagesCanBeImported(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        val text = "繁體中文🙂"
        val count = RoomSessionStore.EXPORT_PAGE_SIZE + 1
        insertLegacyExportRows(sid, count, text)
        val dest = File(tmpDir, "unicode.jsonl")

        store.exportJsonl(sid, dest)

        assertEquals(listOf(dest.name), tmpDir.list()!!.sorted())
        assertTrue(dest.length() > dest.readText(Charsets.UTF_8).length)
        val imported = store.importJsonl(dest)
        val original = store.loadEvents(sid, limit = count)
        val restored = store.loadEvents(imported, limit = count)
        assertNotEquals(sid, imported)
        assertEquals(count, restored.size)
        assertEquals(original.map { it.copy(sessionId = imported, runId = restored.first().runId) }, restored)
        assertTrue(restored.all { it.text == text })
    }

    @Test fun export_roomSnapshotSurvivesConcurrentMutations(): Unit = runBlocking {
        setUp()
        for (operation in listOf("prune", "delete", "append")) {
            val sid = store.createSession("t", "p/m")
            val count = RoomSessionStore.EXPORT_PAGE_SIZE + 5
            insertLegacyExportRows(sid, count, "snapshot")
            val pageRead = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val started = CountDownLatch(1)
            val finished = CountDownLatch(1)
            store.exportAfterPageHookForTest = {
                if (pageRead.count > 0) {
                    pageRead.countDown()
                    check(resume.await(10, TimeUnit.SECONDS))
                }
            }
            val dest = File(tmpDir, "$operation.jsonl")
            val export = async(Dispatchers.IO) { store.exportJsonl(sid, dest) }
            try {
                assertTrue(pageRead.await(10, TimeUnit.SECONDS))
                val mutation = async(Dispatchers.IO) {
                    started.countDown()
                    try {
                        when (operation) {
                            "prune" -> store.prune(PrunePolicy(maxEventsPerSession = 1))
                            "delete" -> store.deleteSession(sid)
                            else -> store.appendEvent(event(sid, text = "later"))
                        }
                    } finally {
                        finished.countDown()
                    }
                }
                assertTrue(started.await(10, TimeUnit.SECONDS))
                // Give the mutation an opportunity to finish while export is paused.
                // A timeout is not evidence that it reached a database lock: the
                // coroutine may still be queued. The content checks below apply in
                // either ordering and do not claim deterministic lock coverage.
                finished.await(500, TimeUnit.MILLISECONDS)
                resume.countDown()
                export.await()
                mutation.await()
                val lines = dest.readLines().mapIndexed { index, line -> JsonlCodec.decode(index + 1, line) }
                assertEquals((1L..count.toLong()).toList(), lines.map { it.seq })
                assertTrue(lines.all { it.text == "snapshot" })
                when (operation) {
                    "prune" -> assertEquals(1, store.countEvents(sid))
                    "delete" -> assertNull(store.getSession(sid))
                    else -> assertEquals(count + 1, store.countEvents(sid))
                }
            } finally {
                resume.countDown()
                store.exportAfterPageHookForTest = null
            }
        }
    }

    @Test fun export_cancelBetweenPagesPreservesDestinationAndRemovesTemp(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        insertLegacyExportRows(sid, RoomSessionStore.EXPORT_PAGE_SIZE + 1, "snapshot")
        val pageRead = CountDownLatch(1)
        val resume = CountDownLatch(1)
        store.exportAfterPageHookForTest = {
            pageRead.countDown()
            check(resume.await(10, TimeUnit.SECONDS))
        }
        val dest = File(tmpDir, "cancel.jsonl").apply { writeText("existing") }
        val export = async(Dispatchers.IO) { store.exportJsonl(sid, dest) }
        try {
            assertTrue(pageRead.await(10, TimeUnit.SECONDS))
            assertEquals(1, tmpDir.listFiles()!!.count {
                it.name.startsWith("jsonl-export-") && it.name.endsWith(".tmp")
            })
            export.cancel()
            resume.countDown()
            try {
                export.await()
                fail("expected cancellation")
            } catch (_: kotlinx.coroutines.CancellationException) { }
            assertEquals("existing", dest.readText())
            assertEquals(listOf("cancel.jsonl"), tmpDir.list()!!.sorted())
        } finally {
            resume.countDown()
            store.exportAfterPageHookForTest = null
        }
    }

    @Test fun export_failureReasonUsesFullAndErrorRedaction(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        db.sessionDao().insertEvent(TranscriptEventEntity(
            0, sid, 1, "r", "assistant", "fragment", false, 0, now, true,
            "alice@example.com https://example.com/?token=secret", null, null,
        ))
        val dest = File(tmpDir, "reason.jsonl")
        store.exportJsonl(sid, dest)
        val reason = JsonlCodec.decode(1, dest.readText().trim()).failureReason!!
        assertTrue(!reason.contains("alice@example.com"))
        assertTrue(!reason.contains("secret"))
        assertTrue(reason.contains("⟦REDACTED:EMAIL⟧"))
        val appended = store.createSession("t", "p/m")
        store.appendEvent(event(appended, kind = "assistant").copy(
            isPartial = true, failureReason = "alice@example.com",
        ))
        assertTrue(!store.loadEvents(appended).single().failureReason!!.contains("alice@example.com"))
        val imported = store.importJsonl(
            """{"seq":1,"runId":"r","kind":"assistant","text":"x","imagesOmitted":0,"createdAt":1,"isPartial":true,"failureReason":"alice@example.com"}""".byteInputStream(),
        )
        assertTrue(!store.loadEvents(imported).single().failureReason!!.contains("alice@example.com"))
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

    // ---- Stage E: DESC tail paging + filtered counts ----

    @Test fun tail_descPagingAndFilteredCounts(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        store.appendEvent(event(sid, kind = "user", text = "u0", run = "r0"))
        store.appendEvent(event(sid, kind = "assistant", text = "a0", run = "r0"))
        store.appendEvent(event(sid, kind = "system", text = "sys", run = "r0"))
        store.appendEvent(event(sid, kind = "user", text = "u1", run = "r1"))
        store.appendEvent(
            TranscriptEvent(
                sessionId = sid,
                runId = "r1",
                kind = "assistant",
                text = "half",
                createdAt = now,
                isPartial = true,
            ),
        )
        store.appendEvent(event(sid, kind = "assistant", text = "a1", run = "r1"))

        // Raw total vs replay total (user/assistant incl. partial) vs model
        // total (user/assistant, non-partial): the exact dropped-count basis.
        assertEquals(6, store.countEvents(sid))
        assertEquals(5, store.countHistoryEvents(sid, includePartial = true))
        assertEquals(4, store.countHistoryEvents(sid, includePartial = false))

        // Newest-first window, restored to oldest-first.
        val tail = store.loadTailEvents(sid, 3)
        assertEquals(listOf(4L, 5L, 6L), tail.map { it.seq })
        assertEquals(listOf("u1", "half", "a1"), tail.map { it.text })

        // Backward page below a seq bound, newest-first (sparse-seq safe:
        // predicate is `seq <`, never an offset).
        val before = store.loadEventsBefore(sid, 4, 2)
        assertEquals(listOf(3L, 2L), before.map { it.seq })

        // Over-large limit keeps everything, still ascending.
        val all = store.loadTailEvents(sid, 100)
        assertEquals((1L..6L).toList(), all.map { it.seq })

        try {
            store.loadTailEvents(sid, 0)
            fail("non-positive tail limit must throw")
        } catch (_: IllegalArgumentException) {
            // Fail closed on bad limits.
        }
    }
}
