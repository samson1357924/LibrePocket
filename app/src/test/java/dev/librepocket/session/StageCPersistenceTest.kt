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
 * Stage C persistence regressions (Finding #3 P1 + #4 P1, option A).
 *
 * - Migration 2→3 upgrades a real v2 file DB with ALTER ADD COLUMN only:
 *   rows preserved, new columns default to null, new writes work.
 * - New linkage columns round-trip through [RoomSessionStore] and JSONL.
 * - [JsonlCodec] decodes pre-C lines without the new keys (null defaults)
 *   and rejects malformed new keys with line-number-only errors.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StageCPersistenceTest {

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

    private val v2SessionsDdl =
        "CREATE TABLE `sessions` (`sessionId` TEXT NOT NULL, `title` TEXT, " +
            "`model` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
            "`pinned` INTEGER NOT NULL, PRIMARY KEY(`sessionId`))"
    // Exact v2 DDL: v1 columns + isPartial/failureReason (MIGRATION_1_2), no
    // parentRunId/attemptIndex yet. Must stay byte-faithful or Room's
    // post-migration validation fails for the wrong reason.
    private val v2EventsDdl =
        "CREATE TABLE `transcript_events` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`sessionId` TEXT, `seq` INTEGER NOT NULL, `runId` TEXT, `kind` TEXT, `text` TEXT, " +
            "`truncated` INTEGER NOT NULL, `imagesOmitted` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, `isPartial` INTEGER NOT NULL DEFAULT 0, " +
            "`failureReason` TEXT, FOREIGN KEY(`sessionId`) REFERENCES " +
            "`sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE )"

    /** Builds a real v2 file DB (raw DDL, version 2) with a logical turn. */
    private fun createV2File(name: String, sid: String) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(v2SessionsDdl)
                        db.execSQL(v2EventsDdl)
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
                "(sessionId, seq, runId, kind, text, truncated, imagesOmitted, createdAt, isPartial, failureReason) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(sid, 1L, "run-1", "user", "hello", 0, 0, 1000L, 0, null),
        )
        db.execSQL(
            "INSERT INTO transcript_events " +
                "(sessionId, seq, runId, kind, text, truncated, imagesOmitted, createdAt, isPartial, failureReason) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(sid, 2L, "run-1", "assistant", "hi there", 0, 0, 1001L, 0, null),
        )
        db.close()
        helper.close()
    }

    @Test fun migration2to3_preservesRowsAndDefaults() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = "stagec-mig-${UUID.randomUUID()}.db"
        val sid = "legacy-session-2"
        createV2File(name, sid)

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
            // Pre-migration rows read back with null linkage (single-id legacy).
            for (row in rows) {
                assertNull("migrated row must default parentRunId=null", row.parentRunId)
                assertNull("migrated row must default attemptIndex=null", row.attemptIndex)
                assertFalse(row.isPartial)
                assertNull(row.failureReason)
            }
            // New writes use the new columns through the same DAO.
            db.sessionDao().insertEvent(
                TranscriptEventEntity(
                    0, sid, 3, "attempt-2", "assistant", "ok", false, 0, 1002L,
                    false, null, "run-1", 1,
                ),
            )
            val after = db.sessionDao().allEvents(sid)
            assertEquals(3, after.size)
            assertEquals("attempt-2", after[2].runId)
            assertEquals("run-1", after[2].parentRunId)
            assertEquals(1, after[2].attemptIndex)
        } finally {
            ctx.deleteDatabase(name)
        }
    }

    @Test fun store_roundTripsParentAndAttempt() = runBlocking {
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
                parentRunId = null, attemptIndex = 0,
            ),
        )
        store.appendEvent(
            TranscriptEvent(
                sessionId = sid, runId = "A2", kind = "assistant", text = "ok",
                createdAt = now, parentRunId = "L", attemptIndex = 1,
            ),
        )
        val loaded = store.loadEvents(sid)
        assertEquals(3, loaded.size)
        assertNull(loaded[0].parentRunId)
        assertNull(loaded[0].attemptIndex)
        assertEquals("half", loaded[1].text)
        assertTrue(loaded[1].isPartial)
        assertEquals("boom", loaded[1].failureReason)
        assertNull(loaded[1].parentRunId)
        assertEquals(0, loaded[1].attemptIndex)
        assertEquals("A2", loaded[2].runId)
        assertEquals("L", loaded[2].parentRunId)
        assertEquals(1, loaded[2].attemptIndex)
    }

    @Test fun exportImport_roundTripsParentAttemptAndHalf() = runBlocking {
        tmpDir = Files.createTempDirectory("stagec-jsonl").toFile()
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
                parentRunId = null, attemptIndex = 0,
            ),
        )
        store.appendEvent(
            TranscriptEvent(
                sessionId = sid, runId = "L", kind = "retry", text = "attempt 1/1 after 0ms",
                createdAt = now,
            ),
        )
        store.appendEvent(
            TranscriptEvent(
                sessionId = sid, runId = "A2", kind = "assistant", text = "ok",
                createdAt = now, parentRunId = "L", attemptIndex = 1,
            ),
        )
        val dest = File(tmpDir, "stagec.jsonl")
        store.exportJsonl(sid, dest)
        val imported = store.importJsonl(dest)
        val back = store.loadEvents(imported)
        assertEquals(4, back.size)
        // The intermediate half survives export/import (Finding #4).
        val partial = back.first { it.kind == "assistant" && it.isPartial }
        assertEquals("half", partial.text)
        assertEquals("boom", partial.failureReason)
        val success = back.last { it.kind == "assistant" && !it.isPartial }
        assertEquals("ok", success.text)
        assertEquals("L", success.parentRunId)
        assertEquals(1, success.attemptIndex)
    }

    @Test fun jsonlCodec_oldLinesWithoutParentDecodeToNull() {
        val line = JsonlCodec.decode(
            1,
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"hi\"," +
                "\"imagesOmitted\":0,\"createdAt\":7}",
        )
        assertNull(line.parentRunId)
        assertNull(line.attemptIndex)
        // Phase 3 old lines still decode too.
        val phase3 = JsonlCodec.decode(
            2,
            "{\"seq\":2,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"half\"," +
                "\"imagesOmitted\":0,\"createdAt\":7,\"isPartial\":true,\"failureReason\":\"boom\"}",
        )
        assertNull(phase3.parentRunId)
        assertNull(phase3.attemptIndex)
        assertTrue(phase3.isPartial)
        assertEquals("boom", phase3.failureReason)
    }

    @Test fun jsonlCodec_roundTripsParentAndAttempt() {
        val event = TranscriptEvent(
            seq = 4, sessionId = "s", runId = "A2", kind = "assistant", text = "ok",
            createdAt = 9, parentRunId = "L", attemptIndex = 1,
        )
        val back = JsonlCodec.decode(1, JsonlCodec.encode(event))
        assertEquals("A2", back.runId)
        assertEquals("L", back.parentRunId)
        assertEquals(1, back.attemptIndex)

        val first = TranscriptEvent(
            seq = 5, sessionId = "s", runId = "L", kind = "assistant", text = "half",
            createdAt = 9, isPartial = true, failureReason = "boom",
            parentRunId = null, attemptIndex = 0,
        )
        val firstBack = JsonlCodec.decode(2, JsonlCodec.encode(first))
        assertNull(firstBack.parentRunId)
        assertEquals(0, firstBack.attemptIndex)
        assertTrue(firstBack.isPartial)
    }

    @Test fun jsonlCodec_rejectsBadParentAndAttemptWithLineNumberOnly() {
        val blankParent =
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"t\"," +
                "\"imagesOmitted\":0,\"createdAt\":1,\"parentRunId\":\"\"}"
        try {
            JsonlCodec.decode(3, blankParent)
            fail("blank parentRunId must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("line 3"))
        }
        val badParent =
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"t\"," +
                "\"imagesOmitted\":0,\"createdAt\":1,\"parentRunId\":5}"
        try {
            JsonlCodec.decode(4, badParent)
            fail("non-string parentRunId must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("line 4"))
        }
        val negativeAttempt =
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"t\"," +
                "\"imagesOmitted\":0,\"createdAt\":1,\"attemptIndex\":-1}"
        try {
            JsonlCodec.decode(5, negativeAttempt)
            fail("negative attemptIndex must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("line 5"))
        }
        val badAttempt =
            "{\"seq\":1,\"runId\":\"r\",\"kind\":\"assistant\",\"text\":\"t\"," +
                "\"imagesOmitted\":0,\"createdAt\":1,\"attemptIndex\":\"x\"}"
        try {
            JsonlCodec.decode(6, badAttempt)
            fail("non-int attemptIndex must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("line 6"))
        }
    }

    @Test fun sink_parentAwareOverloadsPersistLinkage() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        sink.onTurnStarted("L", "hello")
        sink.onTurnFailed("L", "half", "boom", null, 0)
        sink.onTurnSucceeded("A2", "ok", "L", 1)
        sink.onTurnCancelled("A3", "cut", "L", 2)

        val rows = store.events
        assertEquals(listOf("user", "assistant", "assistant", "assistant"), rows.map { it.kind })
        assertNull(rows[0].parentRunId)
        assertNull(rows[0].attemptIndex)
        assertEquals("half", rows[1].text)
        assertTrue(rows[1].isPartial)
        assertNull(rows[1].parentRunId)
        assertEquals(0, rows[1].attemptIndex)
        assertEquals("A2", rows[2].runId)
        assertEquals("L", rows[2].parentRunId)
        assertEquals(1, rows[2].attemptIndex)
        assertEquals("A3", rows[3].runId)
        assertEquals("L", rows[3].parentRunId)
        assertEquals(2, rows[3].attemptIndex)
    }
}
