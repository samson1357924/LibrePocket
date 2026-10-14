package dev.librepocket.session

import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fake-vs-Room parity regressions (test-only, zero production change).
 *
 * Scope: [FakeSessionStore] only. The `SequentialStore` in
 * `SessionSequentialFallbackTest` mirrors the same S1 write shaping
 * (kind gate, reasoning empty-only, redact + 100k truncate, redactError
 * reason; title redacted + blank-model gate on create), the same CASCADE
 * [deleteSession], and the same no-prune/no-export/no-import
 * `UnsupportedOperationException` targets, but it is NOT covered here
 * (its list/get reads skip the Fake read-time title re-redact, and it
 * inherits the non-atomic sequential batch loop by design — see its own
 * KDoc non-goals). Hand-written [SessionStore] fakes
 * elsewhere are NOT covered here: the `StageCStore` in
 * `TurnControllerStageCTest` (no-op export/prune), the store in
 * `GhostUndeliveredDurableTest` (no-op export/prune), the
 * `DelaySessionStore` in `TurnControllerLedgerTest` (no-op export/prune),
 * the gated store in `ChatSessionLifecycleTest` (no-op export/prune), and
 * the `ExportingFakeStore` in `ChatSessionFactorySwitchSnapshotTest`
 * (overrides export with a real JSONL write).
 *
 * Target contract pinned here:
 * - Fake is no-prune/no-export/no-import (unsupported targets):
 *   [FakeSessionStore.prune], [FakeSessionStore.exportJsonl] and
 *   [FakeSessionStore.importJsonl] must throw
 *   [UnsupportedOperationException] (never silent success), so a fork
 *   snapshot byte/row cap can never green-light on a full-copy Fake.
 *   Callers touching those paths must use Room (Robolectric).
 * - Fake write shaping must mirror [RoomSessionStore] prepareEvent shaping
 *   only (kind gate, reasoning empty-only, redact + 100k truncate,
 *   redactError reason, title redact, model blank gate, unknown-session
 *   gate): same stored text/reason bytes or same-throw.
 * - Fake delete must CASCADE like Room (DAO CASCADE): no orphan rows.
 * - Fake batch must roll back like the Room transaction: a mid-batch throw
 *   leaves zero rows behind.
 *
 * Explicit non-goals (known gaps, NOT pinned):
 * - `truncated` flag: stored in the Room entity but never exposed via
 *   [TranscriptEvent] reads/exports, so invisible via API.
 * - append `touch` of `updatedAt`: Room bumps the header clock on every
 *   write; Fake keeps the create-time value.
 * - prune/export/import (unsupported targets by design; see above).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionFakeParityRegressionTest {

    private var now: Long = 1_700_000_000_000L
    private lateinit var db: LibrePocketDb
    private lateinit var room: RoomSessionStore
    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        db = LibrePocketDb.openInMemory(ApplicationProvider.getApplicationContext())
        room = RoomSessionStore(db) { now }
        tmpDir = Files.createTempDirectory("s1-fake-parity").toFile()
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) db.close()
        if (this::tmpDir.isInitialized) tmpDir.deleteRecursively()
    }

    private fun event(
        sid: String,
        kind: String = "user",
        text: String = "hello",
        run: String = "run-1",
        failureReason: String? = null,
    ) = TranscriptEvent(
        sessionId = sid,
        runId = run,
        kind = kind,
        text = text,
        createdAt = now,
        failureReason = failureReason,
    )

    // ---- F1: delete must cascade (Fake + Room dual) ----

    @Test fun fakeRoomParity_deleteCascadesToEvents(): Unit = runBlocking {
        // Fake half: header + 3 rows, delete, all reads must be 0.
        val fake = FakeSessionStore()
        val fsid = fake.createSession("t", "p/m")
        repeat(3) { i -> fake.appendEvent(event(fsid, text = "m$i", run = "run-$i")) }
        assertEquals(3, fake.loadEvents(fsid).size)
        fake.deleteSession(fsid)
        assertNull("deleted header must be gone", fake.getSession(fsid))
        assertEquals("loadEvents after delete must be 0 (CASCADE)", 0, fake.loadEvents(fsid).size)
        assertEquals("countEvents after delete must be 0 (CASCADE)", 0, fake.countEvents(fsid))
        assertEquals("tail after delete must be 0 (CASCADE)", 0, fake.loadTailEvents(fsid, 10).size)
        // Re-creating the SAME id must not resurrect old rows.
        // white-box: writes the header map directly to rebuild the same id
        // without going through createSession (which mints a fresh UUID and
        // cannot reuse an id); invariant pinned is read-path only — every
        // read (load/count/tail) must still see zero rows.
        fake.metas[fsid] = SessionMeta(fsid, "recreated", now, now, "p/m")
        assertEquals("recreated same id must have zero old rows", 0, fake.loadEvents(fsid).size)
        assertEquals(0, fake.countEvents(fsid))

        // Room half: same shape, proves the dual对照 target.
        val rsid = room.createSession("t", "p/m")
        repeat(3) { i -> room.appendEvent(event(rsid, text = "m$i", run = "run-$i")) }
        assertEquals(3, room.loadEvents(rsid).size)
        room.deleteSession(rsid)
        assertNull(room.getSession(rsid))
        assertEquals(0, room.loadEvents(rsid).size)
        assertEquals(0, room.countEvents(rsid))
        assertEquals(0, room.loadTailEvents(rsid, 10).size)
        // Same-id rebuild via DAO must also see zero old rows (CASCADE proof).
        // white-box: inserts the header row via the DAO directly because the
        // public createSession API always mints a fresh UUID and cannot reuse
        // an id; invariant pinned is read-path only — Room reads must still
        // see zero rows for the rebuilt id.
        db.sessionDao().insertSession(SessionEntity(rsid, "recreated", "p/m", now, now, false))
        assertEquals(0, room.loadEvents(rsid).size)
        assertEquals(0, room.countEvents(rsid))
    }

    // ---- F8: prepare parity matrix (same bytes or same throw) ----

    @Test fun fakeRoomParity_prepareEvent(): Unit = runBlocking {
        // 1. Unknown kind: both must reject with the same gate message.
        assertBothThrowMessage("unknown event kind") { sid, store ->
            store.appendEvent(event(sid, kind = "telepathy", text = "x"))
        }
        // 2. Non-empty reasoning marker: both must reject.
        run {
            val fake = FakeSessionStore()
            val fsid = fake.createSession("t", "p/m")
            val rsid = room.createSession("t", "p/m")
            var fakeEx: Throwable? = null
            var roomEx: Throwable? = null
            try {
                fake.appendEvent(event(fsid, kind = "reasoning", text = "smuggled thought"))
            } catch (e: IllegalArgumentException) {
                fakeEx = e
            }
            try {
                room.appendEvent(event(rsid, kind = "reasoning", text = "smuggled thought"))
            } catch (e: IllegalArgumentException) {
                roomEx = e
            }
            assertTrue("Fake must reject non-empty reasoning marker", fakeEx != null)
            assertTrue("Room must reject non-empty reasoning marker", roomEx != null)
            assertTrue(
                "both reasoning rejections must name reasoning: fake=${fakeEx?.message} room=${roomEx?.message}",
                (fakeEx?.message ?: "").contains("reasoning") &&
                    (roomEx?.message ?: "").contains("reasoning"),
            )
        }
        // 3. Empty reasoning marker: both accept, same empty bytes.
        run {
            val fake = FakeSessionStore()
            val fsid = fake.createSession("t", "p/m")
            fake.appendEvent(event(fsid, kind = "reasoning", text = "", run = "r1"))
            val rsid = room.createSession("t", "p/m")
            room.appendEvent(event(rsid, kind = "reasoning", text = "", run = "r1"))
            assertEquals("", fake.loadEvents(fsid).single().text)
            assertEquals("", room.loadEvents(rsid).single().text)
        }
        // 4. Overlong text: both truncate to MAX_TEXT_CHARS with identical bytes.
        run {
            val big = "y".repeat(RoomSessionStore.MAX_TEXT_CHARS + 1)
            val fake = FakeSessionStore()
            val fsid = fake.createSession("t", "p/m")
            fake.appendEvent(event(fsid, text = big))
            val rsid = room.createSession("t", "p/m")
            room.appendEvent(event(rsid, text = big))
            val ftext = fake.loadEvents(fsid).single().text
            val rtext = room.loadEvents(rsid).single().text
            assertEquals(RoomSessionStore.MAX_TEXT_CHARS, ftext.length)
            assertEquals(RoomSessionStore.MAX_TEXT_CHARS, rtext.length)
            assertEquals("overlong truncation shaping must match", rtext, ftext)
        }
        // 5. Secret text: both redact to identical bytes, no raw secret.
        run {
            val secret = "sk-abcDEF1234567890"
            val mail = "user@example.com"
            val raw = "contact $mail my key $secret ok"
            val fake = FakeSessionStore()
            val fsid = fake.createSession("t", "p/m")
            fake.appendEvent(event(fsid, text = raw))
            val rsid = room.createSession("t", "p/m")
            room.appendEvent(event(rsid, text = raw))
            val ftext = fake.loadEvents(fsid).single().text
            val rtext = room.loadEvents(rsid).single().text
            assertEquals("secret redaction shaping must match", rtext, ftext)
            assertTrue(!ftext.contains(secret) && !ftext.contains(mail))
            assertTrue(ftext.contains("⟦REDACTED"))
        }
        // 6. failureReason: both apply redactError identically.
        run {
            val rawReason = "boom https://example.com/?token=abc123 Bearer abcdef123456"
            val fake = FakeSessionStore()
            val fsid = fake.createSession("t", "p/m")
            fake.appendEvent(event(fsid, kind = "assistant", text = "half", failureReason = rawReason))
            val rsid = room.createSession("t", "p/m")
            room.appendEvent(event(rsid, kind = "assistant", text = "half", failureReason = rawReason))
            val freason = fake.loadEvents(fsid).single().failureReason
            val rreason = room.loadEvents(rsid).single().failureReason
            assertEquals("failureReason redactError shaping must match", rreason, freason)
            assertTrue(freason != null && !freason.contains("abc123"))
        }
        // 7. Title redaction parity on create.
        run {
            val secretTitle = "call user@example.com now"
            val fake = FakeSessionStore()
            val fsid = fake.createSession(secretTitle, "p/m")
            val rsid = room.createSession(secretTitle, "p/m")
            val ftitle = fake.getSession(fsid)!!.title
            val rtitle = room.getSession(rsid)!!.title
            assertEquals("title redaction shaping must match", rtitle, ftitle)
            assertTrue(!ftitle.contains("user@example.com"))
        }
        // 8. Blank model: both must reject with the same gate.
        for (blank in listOf("", "   ")) {
            var fakeEx: Throwable? = null
            var roomEx: Throwable? = null
            try {
                FakeSessionStore().createSession("t", blank)
            } catch (e: IllegalArgumentException) {
                fakeEx = e
            }
            try {
                room.createSession("t", blank)
            } catch (e: IllegalArgumentException) {
                roomEx = e
            }
            assertTrue("Fake must reject blank model '$blank'", fakeEx != null)
            assertTrue("Room must reject blank model '$blank'", roomEx != null)
            assertTrue(
                "both model gates must match: fake=${fakeEx?.message} room=${roomEx?.message}",
                (fakeEx?.message ?: "").contains("model must not be blank") &&
                    (roomEx?.message ?: "").contains("model must not be blank"),
            )
        }
    }

    private suspend fun assertBothThrowMessage(
        expectFragment: String,
        write: suspend (String, SessionStore) -> Unit,
    ) {
        val fake = FakeSessionStore()
        val fsid = fake.createSession("t", "p/m")
        val rsid = room.createSession("t", "p/m")
        var fakeEx: Throwable? = null
        var roomEx: Throwable? = null
        try {
            write(fsid, fake)
        } catch (e: IllegalArgumentException) {
            fakeEx = e
        }
        try {
            write(rsid, room)
        } catch (e: IllegalArgumentException) {
            roomEx = e
        }
        assertTrue("Fake must throw IAE for $expectFragment", fakeEx != null)
        assertTrue("Room must throw IAE for $expectFragment", roomEx != null)
        assertTrue(
            "both must name '$expectFragment': fake=${fakeEx?.message} room=${roomEx?.message}",
            (fakeEx?.message ?: "").contains(expectFragment) &&
                (roomEx?.message ?: "").contains(expectFragment),
        )
    }

    // ---- F9: prune/export/import must be explicit-unsupported in Fake ----

    @Test fun fakeRoomParity_pruneExportUnsupported(): Unit = runBlocking {
        val fake = FakeSessionStore()
        val sid = fake.createSession("t", "p/m")
        fake.appendEvent(event(sid, text = "hello"))

        // Prune must throw (never silent (0,0)): fork snapshot caps must not
        // green-light on a full-copy Fake.
        try {
            fake.prune(PrunePolicy())
            fail("Fake.prune must throw UnsupportedOperationException (no-prune target)")
        } catch (e: UnsupportedOperationException) {
            assertTrue(e.message!!.contains("unsupported in Fake"))
        }
        // Export must throw (never silent Unit + missing file).
        val dest = File(tmpDir, "fake-export.jsonl")
        try {
            fake.exportJsonl(sid, dest)
            fail("Fake.exportJsonl must throw UnsupportedOperationException (no-export target)")
        } catch (e: UnsupportedOperationException) {
            assertTrue(e.message!!.contains("unsupported in Fake"))
        }
        // Trivially true by construction (Fake throws before touching dest —
        // there is no tmp/rename path), kept as a tripwire against a future
        // partial-write export override.
        assertTrue("failed Fake export must leave no file behind", !dest.exists())
        // Import must throw UnsupportedOperationException (explicit, not generic error).
        val src = File(tmpDir, "fake-import.jsonl")
        src.writeText(
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"hi\",\"imagesOmitted\":0,\"createdAt\":1}\n",
            Charsets.UTF_8,
        )
        try {
            fake.importJsonl(src)
            fail("Fake.importJsonl must throw UnsupportedOperationException (no-import target)")
        } catch (e: UnsupportedOperationException) {
            assertTrue(e.message!!.contains("unsupported in Fake"))
        }
    }

    // ---- F1: unknown session must fail closed on both ----

    @Test fun fakeRoomParity_unknownSessionGate(): Unit = runBlocking {
        // appendEvent on a never-created id: both reject with the Room message.
        var fakeEx: Throwable? = null
        var roomEx: Throwable? = null
        val ghost = UUID.randomUUID().toString()
        try {
            FakeSessionStore().appendEvent(event(ghost))
        } catch (e: IllegalArgumentException) {
            fakeEx = e
        }
        try {
            room.appendEvent(event(ghost))
        } catch (e: IllegalArgumentException) {
            roomEx = e
        }
        assertTrue("Fake must reject unknown session", fakeEx != null)
        assertTrue("Room must reject unknown session", roomEx != null)
        assertTrue(
            "both must name 'unknown session': fake=${fakeEx?.message} room=${roomEx?.message}",
            (fakeEx?.message ?: "").contains("unknown session") &&
                (roomEx?.message ?: "").contains("unknown session"),
        )

        // Delete-then-append: the id is gone, so the same gate must fire again.
        val fake = FakeSessionStore()
        val fsid = fake.createSession("t", "p/m")
        fake.appendEvent(event(fsid))
        fake.deleteSession(fsid)
        val rsid = room.createSession("t", "p/m")
        room.appendEvent(event(rsid))
        room.deleteSession(rsid)
        var fakeGone: Throwable? = null
        var roomGone: Throwable? = null
        try {
            fake.appendEvent(event(fsid))
        } catch (e: IllegalArgumentException) {
            fakeGone = e
        }
        try {
            room.appendEvent(event(rsid))
        } catch (e: IllegalArgumentException) {
            roomGone = e
        }
        assertTrue("Fake must reject append after delete", fakeGone != null)
        assertTrue("Room must reject append after delete", roomGone != null)
        assertTrue(
            "both delete-then-append rejections must name 'unknown session'",
            (fakeGone?.message ?: "").contains("unknown session") &&
                (roomGone?.message ?: "").contains("unknown session"),
        )
    }

    // ---- R5-4: a batch with one bad row must commit zero rows on both ----

    @Test fun fakeRoomParity_batchBadKindRollsBackZeroRows(): Unit = runBlocking {
        val stores = listOf<Pair<String, SessionStore>>("fake" to FakeSessionStore(), "room" to room)
        for ((label, store) in stores) {
            val sid = store.createSession("t", "p/m")
            val batch = listOf(
                event(sid, text = "valid first", run = "run-ok"),
                event(sid, kind = "telepathy", text = "smuggled-kind", run = "run-bad"),
            )
            try {
                store.appendEvents(batch)
                fail("$label batch with a bad kind must throw")
            } catch (e: IllegalArgumentException) {
                assertTrue(
                    "$label batch rejection must name the kind gate",
                    (e.message ?: "").contains("unknown event kind"),
                )
            }
            assertEquals("$label rolled-back batch must leave zero rows", 0, store.loadEvents(sid, 0, 200).size)
            assertEquals("$label rolled-back batch must count zero", 0, store.countEvents(sid))
            // Same zero-read caliber as the F1 delete CASCADE pin above:
            // tail, backward page, and history COUNT must all read zero.
            assertEquals("$label rolled-back batch tail must be zero", 0, store.loadTailEvents(sid, 10).size)
            assertEquals("$label rolled-back batch before-page must be zero", 0, store.loadEventsBefore(sid, Long.MAX_VALUE, 10).size)
            assertEquals("$label rolled-back batch history count must be zero", 0, store.countHistoryEvents(sid, includePartial = false))
        }
    }

    // ---- failureReason=null must survive the write on both ----

    @Test fun fakeRoomParity_failureReasonNullPreserved(): Unit = runBlocking {
        val fake = FakeSessionStore()
        val fsid = fake.createSession("t", "p/m")
        fake.appendEvent(event(fsid, kind = "assistant", text = "half", failureReason = null))
        val rsid = room.createSession("t", "p/m")
        room.appendEvent(event(rsid, kind = "assistant", text = "half", failureReason = null))
        assertNull("Fake must preserve null failureReason", fake.loadEvents(fsid).single().failureReason)
        assertNull("Room must preserve null failureReason", room.loadEvents(rsid).single().failureReason)
    }

    // ---- read gates: limit == 0 throws, beforeSeq == 0 reads zero rows ----

    @Test fun fakeRoomParity_loadLimits(): Unit = runBlocking {
        val fake = FakeSessionStore()
        val fsid = fake.createSession("t", "p/m")
        fake.appendEvent(event(fsid))
        val rsid = room.createSession("t", "p/m")
        room.appendEvent(event(rsid))
        // limit == 0: both must gate exactly like Room.
        var fakeEx: Throwable? = null
        var roomEx: Throwable? = null
        try {
            fake.loadEvents(fsid, 0, 0)
        } catch (e: IllegalArgumentException) {
            fakeEx = e
        }
        try {
            room.loadEvents(rsid, 0, 0)
        } catch (e: IllegalArgumentException) {
            roomEx = e
        }
        assertTrue("Fake must gate limit == 0", fakeEx != null)
        assertTrue("Room must gate limit == 0", roomEx != null)
        assertTrue(
            "both limit gates must match: fake=${fakeEx?.message} room=${roomEx?.message}",
            (fakeEx?.message ?: "").contains("limit must be positive") &&
                (roomEx?.message ?: "").contains("limit must be positive"),
        )
        // beforeSeq == 0: nothing is older, so both read back zero rows.
        assertEquals(0, fake.loadEventsBefore(fsid, 0, 10).size)
        assertEquals(0, room.loadEventsBefore(rsid, 0, 10).size)
    }
}
