package dev.librepocket.session

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import dev.librepocket.agent.ui.chat.ChatSessionFactory
import dev.librepocket.agent.ui.chat.SessionStoreSource
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.agent.ui.chat.allowKeyReadPolicy
import dev.librepocket.agent.ui.chat.cancelledMarkerText
import dev.librepocket.agent.ui.chat.findDanglingRunIds
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage F persistence regressions (R2-1).
 *
 * - Migration 3→4 upgrades a real v3 file DB with ALTER ADD COLUMN only:
 *   rows preserved, `isFinal` defaults to 1 (old single-terminal semantics),
 *   new writes (final + non-final) work.
 * - `isFinal` round-trips through [RoomSessionStore] and JSONL export/import,
 *   including the system cancel mark.
 * - [JsonlCodec] decodes pre-F lines without the key as final (`true`), accepts
 *   legacy 0/1 ints, and rejects malformed values with line-number-only errors.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StageFPersistenceTest {

    private var now: Long = 1_700_000_000_000L
    private lateinit var tmpDir: File
    private val openDbs = ArrayList<LibrePocketDb>()

    @After
    fun tearDown() {
        for (db in openDbs) {
            try {
                db.close()
            } catch (_: Exception) {
            }
        }
        openDbs.clear()
        if (this::tmpDir.isInitialized) {
            tmpDir.deleteRecursively()
        }
    }

    private val v3SessionsDdl =
        "CREATE TABLE `sessions` (`sessionId` TEXT NOT NULL, `title` TEXT, " +
            "`model` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
            "`pinned` INTEGER NOT NULL, PRIMARY KEY(`sessionId`))"
    // Exact v3 DDL: v2 columns + parentRunId/attemptIndex (MIGRATION_2_3), no
    // isFinal yet. Must stay byte-faithful or Room's post-migration
    // validation fails for the wrong reason.
    private val v3EventsDdl =
        "CREATE TABLE `transcript_events` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`sessionId` TEXT, `seq` INTEGER NOT NULL, `runId` TEXT, `kind` TEXT, `text` TEXT, " +
            "`truncated` INTEGER NOT NULL, `imagesOmitted` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, `isPartial` INTEGER NOT NULL DEFAULT 0, " +
            "`failureReason` TEXT, `parentRunId` TEXT, `attemptIndex` INTEGER, " +
            "FOREIGN KEY(`sessionId`) REFERENCES " +
            "`sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE )"

    /** Builds a real v3 file DB (raw DDL, version 3) with a logical turn. */
    private fun createV3File(name: String, sid: String) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(v3SessionsDdl)
                        db.execSQL(v3EventsDdl)
                        db.execSQL(
                            "CREATE INDEX `index_transcript_events_sessionId_seq` " +
                                "ON `transcript_events` (`sessionId`, `seq`)",
                        )
                        db.execSQL(
                            "CREATE INDEX `index_transcript_events_runId` " +
                                "ON `transcript_events` (`runId`)",
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build(),
        )
        val db = helper.writableDatabase
        db.execSQL(
            "INSERT INTO sessions (sessionId, title, model, createdAt, updatedAt, pinned) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(sid, "legacy chat", "p/m", 1000L, 1000L, 0),
        )
        db.execSQL(
            "INSERT INTO transcript_events " +
                "(sessionId, seq, runId, kind, text, truncated, imagesOmitted, createdAt, isPartial, failureReason, parentRunId, attemptIndex) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(sid, 1L, "run-1", "user", "hello", 0, 0, 1000L, 0, null, null, null),
        )
        db.execSQL(
            "INSERT INTO transcript_events " +
                "(sessionId, seq, runId, kind, text, truncated, imagesOmitted, createdAt, isPartial, failureReason, parentRunId, attemptIndex) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(sid, 2L, "run-1", "assistant", "hi there", 0, 0, 1001L, 0, null, null, 0),
        )
        db.close()
        helper.close()
    }

    @Test fun migration3to4_preservesRowsAndDefaultsFinal() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = "stagef-mig-${UUID.randomUUID()}.db"
        val sid = "legacy-session-3"
        createV3File(name, sid)

        val db = Room.databaseBuilder(ctx, LibrePocketDb::class.java, name)
            .addMigrations(LibrePocketDb.MIGRATION_1_2, LibrePocketDb.MIGRATION_2_3, LibrePocketDb.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        openDbs.add(db)
        try {
            val rows = db.sessionDao().allEvents(sid)
            assertEquals(2, rows.size)
            assertEquals("hello", rows[0].text)
            assertEquals("hi there", rows[1].text)
            // Pre-migration rows read back as final (old single-terminal semantics).
            for (row in rows) {
                assertTrue("migrated row must default isFinal=true", row.isFinalFlag)
            }
            assertNull(rows[0].parentRunId)
            assertEquals(0, rows[1].attemptIndex)
            // New writes use the new column through the same DAO.
            db.sessionDao().insertEvent(
                TranscriptEventEntity(
                    0, sid, 3, "run-1", "assistant", "half", false, 0, 1002L,
                    true, "boom", null, 0, false,
                ),
            )
            db.sessionDao().insertEvent(
                TranscriptEventEntity(
                    0, sid, 4, "run-1", "system", "turn run-1 cancelled", false, 0, 1003L,
                    false, null, null, null, true,
                ),
            )
            val after = db.sessionDao().allEvents(sid)
            assertEquals(4, after.size)
            assertFalse(after[2].isFinalFlag)
            assertTrue(after[3].isFinalFlag)
        } finally {
            ctx.deleteDatabase(name)
        }
    }

    @Test fun store_roundTripsFinalFlag() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = LibrePocketDb.openInMemory(ctx)
        openDbs.add(db)
        val store = RoomSessionStore(db) { now }

        val sid = store.createSession("t", "p/m")
        store.appendEvent(
            TranscriptEvent(
                sessionId = sid, runId = "L", kind = "user", text = "q", createdAt = now,
            ),
        )
        store.appendEvent(
            TranscriptEvent(
                sessionId = sid, runId = "L", kind = "assistant", text = "half",
                createdAt = now, isPartial = true, failureReason = "boom",
                parentRunId = null, attemptIndex = 0, isFinal = false,
            ),
        )
        store.appendEvent(
            TranscriptEvent(
                sessionId = sid, runId = "L", kind = "system", text = "turn L cancelled",
                createdAt = now, isFinal = true,
            ),
        )
        val loaded = store.loadEvents(sid)
        assertEquals(3, loaded.size)
        assertTrue(loaded[0].isFinal)
        assertFalse(loaded[1].isFinal)
        assertTrue(loaded[2].isFinal)
        assertEquals("half", loaded[1].text)
        assertEquals("turn L cancelled", loaded[2].text)
    }

    @Test fun exportImport_roundTripsFinalAndCancelMark() = runBlocking {
        tmpDir = Files.createTempDirectory("stagef-jsonl").toFile()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = LibrePocketDb.openInMemory(ctx)
        openDbs.add(db)
        val store = RoomSessionStore(db) { now }

        val sid = store.createSession("t", "p/m")
        store.appendEvent(
            TranscriptEvent(
                sessionId = sid, runId = "L", kind = "user", text = "q", createdAt = now,
            ),
        )
        store.appendEvent(
            TranscriptEvent(
                sessionId = sid, runId = "L", kind = "assistant", text = "half",
                createdAt = now, isPartial = true, failureReason = "boom",
                parentRunId = null, attemptIndex = 0, isFinal = false,
            ),
        )
        store.appendEvent(
            TranscriptEvent(
                sessionId = sid, runId = "L", kind = "retry", text = "attempt 1/1 after 0ms",
                createdAt = now, isFinal = true,
            ),
        )
        store.appendEvent(
            TranscriptEvent(
                sessionId = sid, runId = "L", kind = "system", text = "turn L cancelled",
                createdAt = now, isFinal = true,
            ),
        )
        val dest = File(tmpDir, "stagef.jsonl")
        store.exportJsonl(sid, dest)
        // The final flag is on the wire for every row.
        val lines = dest.readLines(Charsets.UTF_8)
        assertEquals(4, lines.size)
        assertTrue(lines[1].contains("\"isFinal\":false"))
        assertTrue(lines[3].contains("\"isFinal\":true"))
        val imported = store.importJsonl(dest)
        val back = store.loadEvents(imported)
        assertEquals(4, back.size)
        assertFalse(back[1].isFinal)
        assertTrue(back[3].isFinal)
        assertEquals("half", back[1].text)
        assertEquals(back[0].runId, back[3].runId)
        assertEquals("turn ${back[3].runId} cancelled", back[3].text)
    }

    @Test fun jsonlCodec_missingKeyDefaultsFinal() {
        val line = JsonlCodec.decode(
            1,
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"hi\"," +
                "\"imagesOmitted\":0,\"createdAt\":7}",
        )
        assertTrue(line.isFinal)
        // isPartial-missing old lines still decode too.
        val partial = JsonlCodec.decode(
            2,
            "{\"seq\":2,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"half\"," +
                "\"imagesOmitted\":0,\"createdAt\":7,\"isPartial\":true,\"failureReason\":\"boom\"}",
        )
        assertTrue(partial.isPartial)
        assertTrue("pre-F line without isFinal must default final", partial.isFinal)
    }

    @Test fun jsonlCodec_roundTripsFinalFlag() {
        val nonFinal = TranscriptEvent(
            seq = 4, sessionId = "s", runId = "L", kind = "assistant", text = "half",
            createdAt = 9, isPartial = true, failureReason = "boom",
            parentRunId = null, attemptIndex = 0, isFinal = false,
        )
        val back = JsonlCodec.decode(1, JsonlCodec.encode(nonFinal))
        assertFalse(back.isFinal)
        assertTrue(back.isPartial)

        val final = TranscriptEvent(
            seq = 5, sessionId = "s", runId = "L", kind = "system", text = "turn L cancelled",
            createdAt = 9, isFinal = true,
        )
        val finalBack = JsonlCodec.decode(2, JsonlCodec.encode(final))
        assertTrue(finalBack.isFinal)
    }

    @Test fun jsonlCodec_acceptsIntFinalAndRejectsBad() {
        // Production shape: a non-final assistant intermediate is always
        // partial (see SessionTranscriptSink), so the int-0 case carries
        // isPartial=true; a bare non-partial non-final assistant line is
        // rejected (see jsonlCodec_rejectsNonPartialNonFinalAssistant below).
        val zero = JsonlCodec.decode(
            1,
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"t\"," +
                "\"imagesOmitted\":0,\"createdAt\":1,\"isPartial\":true,\"isFinal\":0}",
        )
        assertFalse(zero.isFinal)
        val one = JsonlCodec.decode(
            2,
            "{\"seq\":2,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"t\"," +
                "\"imagesOmitted\":0,\"createdAt\":1,\"isFinal\":1}",
        )
        assertTrue(one.isFinal)
        try {
            JsonlCodec.decode(
                3,
                "{\"seq\":3,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"t\"," +
                    "\"imagesOmitted\":0,\"createdAt\":1,\"isFinal\":\"yes\"}",
            )
            fail("non-boolean isFinal must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("line 3"))
            assertFalse(e.message!!.contains("yes"))
        }
    }

    @Test fun jsonlCodec_rejectsNonPartialNonFinalAssistant() {
        // Phase 5: a crafted assistant row with isFinal=false and
        // isPartial=false would dangle forever (backfill treats non-final as
        // non-terminal) yet still enter model context (projection filters
        // !isPartial only) — production never writes this shape, so decode
        // rejects it fail-closed with a line-number-only error.
        try {
            JsonlCodec.decode(
                7,
                "{\"seq\":7,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"smuggled\"," +
                    "\"imagesOmitted\":0,\"createdAt\":1,\"isPartial\":false,\"isFinal\":false}",
            )
            fail("non-partial non-final assistant must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("line 7"))
            assertFalse(e.message!!.contains("smuggled"))
        }
        // Missing-key pre-F lines still default (old single-terminal rows are
        // final, so they never hit the reject): covered by
        // jsonlCodec_missingKeyDefaultsFinal.
        // Reasoning flag-only markers legitimately carry isFinal=false with
        // isPartial=false (see SessionTranscriptSink) and stay accepted.
        val marker = JsonlCodec.decode(
            8,
            "{\"seq\":8,\"runId\":\"r\",\"kind\":\"reasoning\",\"text\":\"\"," +
                "\"imagesOmitted\":0,\"createdAt\":1,\"isFinal\":false}",
        )
        assertFalse(marker.isFinal)
        assertFalse(marker.isPartial)
    }

    @Test fun sink_failureFinalFlagAndCancelMarkPersist() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        sink.onTurnStarted("L", "hello")
        sink.onTurnFailed("L", "half", "boom", null, 0, false)
        sink.onTurnFailed("A2", "frag", "down", "L", 1, true)
        sink.onTurnSucceeded("A3", "ok", "L", 2, hasReasoning = false)
        sink.onTurnCancelled("A4", "cut", "L", 3, hasReasoning = false)
        sink.onLogicalTurnCancelled("L2", "A5", "half", 0)

        val rows = store.events
        assertEquals(
            listOf("user", "assistant", "assistant", "assistant", "assistant", "system"),
            rows.map { it.kind },
        )
        assertTrue(rows[0].isFinal)
        assertFalse("intermediate partial must be non-final", rows[1].isFinal)
        assertTrue(rows[2].isFinal)
        assertTrue(rows[3].isFinal)
        assertTrue(rows[4].isFinal)
        assertEquals("L2", rows[5].runId)
        assertEquals("turn L2 cancelled", rows[5].text)
        assertTrue(rows[5].isFinal)
    }

    /**
     * PG-S4 backoff-gap cancel reopen (pure JVM, [FakeSessionStore]): seed
     * user + non-final failed partial + retry notice + one
     * [SessionTranscriptSink.onLogicalTurnCancelled] system mark, then reopen
     * via [ChatSessionFactory.open] — the family must read terminal (exactly
     * one assistant row and one `turn L cancelled` mark, no INTERRUPTED, no
     * dangling), and a second open must add zero rows.
     */
    @Test fun backoffGapCancelMark_reopenSeesTerminalAndSecondOpenAddsZero() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        val sink = SessionTranscriptSink(store, sid) { 1L }
        sink.onTurnStarted("L", "q")
        sink.onTurnFailed("A1", "half", "boom", "L", 0, false)
        sink.onTurnRetried("L", 1, 3, 0L)
        sink.onLogicalTurnCancelled("L", "A1", "half", 0)

        val factory = ChatSessionFactory(
            policy = allowKeyReadPolicy(),
            vaultSource = VaultSource {
                val vault = EncryptedPrefsVault(InMemoryPrefs())
                vault.putKey("preset:openai", "test-key-12345678".toCharArray())
                vault
            },
            buildProvider = { _, _ -> StageFCancelReopenProvider() },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = store
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
        val first = factory.open(endpoint, sid)
        try {
            val afterFirst = store.events.filter { it.sessionId == sid }.sortedBy { it.seq }
            assertEquals(1, afterFirst.count { it.kind == "assistant" })
            assertEquals(1, afterFirst.count { it.kind == "system" && it.text == cancelledMarkerText("L") })
            assertTrue(afterFirst.none { it.kind == "system" && it.text.endsWith("interrupted") })
            assertTrue(findDanglingRunIds(afterFirst).isEmpty())
        } finally {
            first.session.close()
        }
        val sizeAfterFirst = store.events.count { it.sessionId == sid }
        val second = factory.open(endpoint, sid)
        try {
            val afterSecond = store.events.filter { it.sessionId == sid }.sortedBy { it.seq }
            assertEquals(sizeAfterFirst, afterSecond.size)
            assertTrue(findDanglingRunIds(afterSecond).isEmpty())
        } finally {
            second.session.close()
        }
    }
}

private class StageFCancelReopenProvider : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    override fun stream(request: ChatRequest): Flow<StreamEvent> = emptyFlow()
    override suspend fun listModels(): List<String> = emptyList()
}
