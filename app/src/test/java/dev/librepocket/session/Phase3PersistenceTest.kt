package dev.librepocket.session

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.util.UUID
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
 * Phase 3 persistence regressions (all fake data, no production endpoints).
 *
 * - Migration 1→2 upgrades a real v1 file DB: rows preserved, new columns
 *   default to `isPartial=false` / `failureReason=null`, new writes work.
 * - New flags round-trip through [RoomSessionStore] and JSONL export/import.
 * - [JsonlCodec] decodes pre-Phase-3 lines without the new keys (defaults)
 *   and accepts legacy 0/1 ints for `isPartial`.
 * - [SessionTranscriptSink] three-arg failure keeps partial + reason as an
 *   `assistant` row; cancel keeps the partial flagged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Phase3PersistenceTest {

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

    // Exact v1 DDL copied from the v1 generated LibrePocketDb_Impl
    // (createAllTables): the migration test must build a byte-faithful v1
    // file, or Room's post-migration validation fails for the wrong reason.
    private val v1SessionsDdl =
        "CREATE TABLE `sessions` (`sessionId` TEXT NOT NULL, `title` TEXT, " +
            "`model` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
            "`pinned` INTEGER NOT NULL, PRIMARY KEY(`sessionId`))"
    private val v1EventsDdl =
        "CREATE TABLE `transcript_events` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`sessionId` TEXT, `seq` INTEGER NOT NULL, `runId` TEXT, `kind` TEXT, `text` TEXT, " +
            "`truncated` INTEGER NOT NULL, `imagesOmitted` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, FOREIGN KEY(`sessionId`) REFERENCES " +
            "`sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE )"

    /** Builds a real v1 file DB (raw DDL, version 1) with two legacy rows. */
    private fun createV1File(name: String, sid: String) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(v1SessionsDdl)
                        db.execSQL(v1EventsDdl)
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
                "(sessionId, seq, runId, kind, text, truncated, imagesOmitted, createdAt) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(sid, 1L, "run-1", "user", "hello", 0, 0, 1000L),
        )
        db.execSQL(
            "INSERT INTO transcript_events " +
                "(sessionId, seq, runId, kind, text, truncated, imagesOmitted, createdAt) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(sid, 2L, "run-1", "assistant", "hi there", 0, 0, 1001L),
        )
        db.close()
        helper.close()
    }

    @Test fun migration1to2_preservesRowsAndDefaults() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = "phase3-mig-${UUID.randomUUID()}.db"
        val sid = "legacy-session-1"
        createV1File(name, sid)

        // Stage C bumped the database to version 3: a v1 file now migrates
        // 1→2→3. Both migrations are backward-compatible ADD COLUMNs, so the
        // Phase 3 assertions below still hold, plus the Stage C null defaults.
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
            assertEquals(1L, rows[0].seq)
            assertEquals(2L, rows[1].seq)
            // Pre-migration rows read back with safe defaults.
            for (row in rows) {
                assertFalse("migrated row must default isPartial=false", row.isPartial)
                assertNull("migrated row must default failureReason=null", row.failureReason)
                assertNull("migrated row must default parentRunId=null", row.parentRunId)
                assertNull("migrated row must default attemptIndex=null", row.attemptIndex)
            }
            // New writes use the new columns through the same DAO.
            db.sessionDao().insertEvent(
                TranscriptEventEntity(
                    0, sid, 3, "run-2", "assistant", "half", false, 0, 1002L, true, "HTTP 500",
                    null, null,
                ),
            )
            val after = db.sessionDao().allEvents(sid)
            assertEquals(3, after.size)
            assertTrue(after[2].isPartial)
            assertEquals("HTTP 500", after[2].failureReason)
        } finally {
            ctx.deleteDatabase(name)
        }
    }

    @Test fun store_roundTripsPartialAndReason() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = LibrePocketDb.openInMemory(ctx)
        openDbs.add(db)
        val store = RoomSessionStore(db) { now }

        val sid = store.createSession("t", "p/m")
        store.appendEvent(
            TranscriptEvent(
                sessionId = sid, runId = "r1", kind = "user", text = "q", createdAt = now,
            ),
        )
        store.appendEvent(
            TranscriptEvent(
                sessionId = sid, runId = "r1", kind = "assistant", text = "half",
                createdAt = now, isPartial = true, failureReason = "HTTP 500",
            ),
        )
        val loaded = store.loadEvents(sid)
        assertEquals(2, loaded.size)
        assertFalse(loaded[0].isPartial)
        assertNull(loaded[0].failureReason)
        assertTrue(loaded[1].isPartial)
        assertEquals("HTTP 500", loaded[1].failureReason)
        assertEquals("half", loaded[1].text)
    }

    @Test fun exportImport_roundTripsPartialAndReason() = runBlocking {
        tmpDir = Files.createTempDirectory("phase3-jsonl").toFile()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = LibrePocketDb.openInMemory(ctx)
        openDbs.add(db)
        val store = RoomSessionStore(db) { now }

        val sid = store.createSession("t", "p/m")
        store.appendEvent(
            TranscriptEvent(
                sessionId = sid, runId = "r1", kind = "user", text = "q", createdAt = now,
            ),
        )
        store.appendEvent(
            TranscriptEvent(
                sessionId = sid, runId = "r1", kind = "assistant", text = "half",
                createdAt = now, isPartial = true, failureReason = "boom",
            ),
        )
        val dest = File(tmpDir, "partial.jsonl")
        store.exportJsonl(sid, dest)
        val imported = store.importJsonl(dest)
        val back = store.loadEvents(imported)
        assertEquals(2, back.size)
        assertTrue(back[1].isPartial)
        assertEquals("boom", back[1].failureReason)
        assertEquals("half", back[1].text)
    }

    @Test fun jsonlCodec_oldLinesWithoutKeysDecodeToDefaults() {
        val line = JsonlCodec.decode(
            1,
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"hi\"," +
                "\"imagesOmitted\":0,\"createdAt\":7}",
        )
        assertFalse(line.isPartial)
        assertNull(line.failureReason)
    }

    @Test fun jsonlCodec_roundTripsNewKeys() {
        val event = TranscriptEvent(
            seq = 4, sessionId = "s", runId = "r", kind = "assistant", text = "half",
            createdAt = 9, isPartial = true, failureReason = "retry later",
        )
        val back = JsonlCodec.decode(1, JsonlCodec.encode(event))
        assertTrue(back.isPartial)
        assertEquals("retry later", back.failureReason)
        assertEquals("half", back.text)

        val clean = TranscriptEvent(
            seq = 5, sessionId = "s", runId = "r", kind = "assistant", text = "done",
            createdAt = 9,
        )
        val cleanBack = JsonlCodec.decode(2, JsonlCodec.encode(clean))
        assertFalse(cleanBack.isPartial)
        assertNull(cleanBack.failureReason)
    }

    @Test fun jsonlCodec_acceptsLegacyIntPartial() {
        val zero = JsonlCodec.decode(
            1,
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"t\"," +
                "\"imagesOmitted\":0,\"createdAt\":1,\"isPartial\":0}",
        )
        assertFalse(zero.isPartial)
        val one = JsonlCodec.decode(
            2,
            "{\"seq\":2,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"t\"," +
                "\"imagesOmitted\":0,\"createdAt\":1,\"isPartial\":1}",
        )
        assertTrue(one.isPartial)
    }

    @Test fun jsonlCodec_rejectsBadNewKeysWithLineNumberOnly() {
        val badPartial =
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"t\"," +
                "\"imagesOmitted\":0,\"createdAt\":1,\"isPartial\":\"yes\"}"
        try {
            JsonlCodec.decode(3, badPartial)
            fail("non-boolean isPartial must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("line 3"))
            assertFalse(e.message!!.contains("yes"))
        }
        val badReason =
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"t\"," +
                "\"imagesOmitted\":0,\"createdAt\":1,\"failureReason\":5}"
        try {
            JsonlCodec.decode(4, badReason)
            fail("non-string failureReason must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("line 4"))
        }
    }

    @Test fun sink_failedThreeArgKeepsPartialAndReason() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        sink.onTurnStarted("r1", "hello")
        sink.onTurnFailed("r1", "half-written", "HTTP 500")
        sink.onTurnCancelled("r2", "cut off")

        val rows = store.events
        assertEquals(listOf("user", "assistant", "assistant"), rows.map { it.kind })
        val failed = rows[1]
        assertEquals("half-written", failed.text)
        assertTrue(failed.isPartial)
        assertEquals("HTTP 500", failed.failureReason)
        val cancelled = rows[2]
        assertEquals("cut off", cancelled.text)
        assertTrue(cancelled.isPartial)
        assertNull(cancelled.failureReason)
    }
}
