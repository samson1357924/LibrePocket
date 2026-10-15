package dev.librepocket.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.librepocket.agent.ui.chat.ChatSessionFactory
import dev.librepocket.agent.ui.chat.SessionStoreSource
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.agent.ui.chat.allowKeyReadPolicy
import dev.librepocket.agent.ui.chat.familyOf
import dev.librepocket.agent.ui.chat.findDanglingRunIds
import dev.librepocket.agent.ui.chat.loadModelHistory
import dev.librepocket.agent.ui.chat.loadSwitchSnapshotEvents
import dev.librepocket.agent.ui.chat.snapshotSwitchHistory
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R27-1/R27-2 true `Factory.open` + snapshot regressions (Room file-backed, Robolectric).
 *
 * Store-side convergent prune is pinned elsewhere; THESE tests pin the
 * consumer side after a real restart: seed (direct DAO inserts on a
 * same-origin session — never `append*`, whose AUTO_PRUNE_EVERY=100 sweep
 * would converge the crafted head orphan mid-seed) → explicit prune →
 * close → reopen the DB FILE → fresh store → true `Factory.open`.
 *
 * Seed shape (2002 raw rows): head user(h)+reasoning(h)+assistant(h), one
 * kept reasoning turn (fill-0 triple), 998 plain filler pairs. Default keep
 * 2000 converges it in ONE sweep (raw 2: u-h + marker; fixup 1: a-h = 3),
 * leaving 1999 rows headed by u-fill-0. The kept fill-0 marker separates the
 * suspect all-flag (every kept assistant `hasReasoning=true`) from the clean
 * precise flag (only `a-fill-0` true).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomR27FactoryOpenSnapshotTest {

    private var now: Long = 1_700_000_000_000L
    private var memDb: LibrePocketDb? = null

    @After
    fun tearDown() {
        memDb?.close()
        memDb = null
    }

    private fun openFileDb(name: String): LibrePocketDb {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return Room.databaseBuilder(ctx, LibrePocketDb::class.java, name)
            .addMigrations(LibrePocketDb.MIGRATION_1_2, LibrePocketDb.MIGRATION_2_3, LibrePocketDb.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
    }

    private fun dbFileOf(name: String): File {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return ctx.getDatabasePath(name)
    }

    /** 2002-row seed: head triple + kept reasoning turn + 998 plain pairs. */
    private fun seedRows(): List<TranscriptEvent> {
        val rows = ArrayList<TranscriptEvent>(2002)
        rows.add(TranscriptEvent(sessionId = "seed", runId = "h", kind = "user", text = "u-h", createdAt = 1L))
        rows.add(TranscriptEvent(sessionId = "seed", runId = "h", kind = "reasoning", text = "", createdAt = 1L))
        rows.add(TranscriptEvent(sessionId = "seed", runId = "h", kind = "assistant", text = "a-h", createdAt = 1L))
        rows.add(TranscriptEvent(sessionId = "seed", runId = "fill-0", kind = "user", text = "u-fill-0", createdAt = 1L))
        rows.add(TranscriptEvent(sessionId = "seed", runId = "fill-0", kind = "reasoning", text = "", createdAt = 1L))
        rows.add(TranscriptEvent(sessionId = "seed", runId = "fill-0", kind = "assistant", text = "a-fill-0", createdAt = 1L))
        for (i in 1..998) {
            rows.add(TranscriptEvent(sessionId = "seed", runId = "fill-$i", kind = "user", text = "u-fill-$i", createdAt = 1L))
            rows.add(TranscriptEvent(sessionId = "seed", runId = "fill-$i", kind = "assistant", text = "a-fill-$i", createdAt = 1L))
        }
        return rows
    }

    /**
     * Direct DAO inserts with explicit seqs: bypasses the `append*`
     * AUTO_PRUNE_EVERY sweep so the crafted head orphan survives until the
     * explicit prune under test. Same-origin header comes from the caller's
     * own `createSession` (import would stamp IMPORT_MODEL and fork on open).
     */
    private fun seedDirectDao(db: LibrePocketDb, sid: String, rows: List<TranscriptEvent>) {
        db.runInTransaction(
            Callable {
                var seq = 1L
                for (e in rows) {
                    db.sessionDao().insertEvent(
                        TranscriptEventEntity(
                            0, sid, seq++, e.runId, e.kind, e.text, false, 0,
                            e.createdAt, e.isPartial, e.failureReason,
                            e.parentRunId, e.attemptIndex, e.isFinal,
                        ),
                    )
                }
                Unit
            },
        )
    }

    private fun sameOriginEndpoint() = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "gpt-4o-mini",
        apiKeyRef = "provider_key/preset:openai",
    )

    private fun factoryFor(store: RoomSessionStore) = ChatSessionFactory(
        policy = allowKeyReadPolicy(),
        vaultSource = VaultSource {
            val vault = EncryptedPrefsVault(InMemoryPrefs())
            vault.putKey("preset:openai", "test-key-12345678".toCharArray())
            vault
        },
        buildProvider = { _, _ -> R27NoopProvider() },
        sessionStores = object : SessionStoreSource {
            override suspend fun store() = store
        },
    )

    private fun assertNoHeadOrphan(events: List<TranscriptEvent>, what: String) {
        val head = events.firstOrNull { it.kind == "user" || it.kind == "assistant" } ?: return
        assertTrue("$what kept must start at a user boundary, got ${head.kind}", head.kind == "user")
        for (a in events.filter { it.kind == "assistant" }) {
            val fam = familyOf(a)
            assertTrue(
                "$what orphan assistant without user family (runId=${a.runId})",
                events.any { it.kind == "user" && familyOf(it) == fam },
            )
        }
    }

    /**
     * R27-1 crash-atomicity open: single convergent prune (deleted 3, suspect
     * held) → restart drops the memory-only flag → fresh store + true
     * `Factory.open` resumes clean (shape durably gone, not false-clean):
     * head is user, dropped count honest, flags precise (only `a-fill-0`).
     * Pre-restart, the same ledger under suspect over-flags every assistant
     * (fail-closed all-flag).
     */
    @Test
    fun crashAtomicity_open_convergedHeadHonestDroppedAndFlags() = runBlocking {
        val name = "r27-open-${UUID.randomUUID()}.db"
        val dbFile = dbFileOf(name)
        val sid: String
        val db1 = openFileDb(name)
        try {
            val store1 = RoomSessionStore(db1) { now }
            sid = store1.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
            seedDirectDao(db1, sid, seedRows())
            assertEquals(2002, store1.countEvents(sid))
            val result = store1.prune(PrunePolicy())
            // Raw 2 (u-h + marker) + fixup 1 (orphan a-h) = 3, one sweep.
            assertEquals(3, result.deletedEvents)
            assertEquals(1999, store1.countEvents(sid))
            assertTrue(store1.isPruneSuspect(sid))
            // Suspect held: dropped 0 (post-prune total) but fail-closed
            // all-flag — every kept assistant reads hasReasoning=true.
            val suspect = loadModelHistory(store1, sid)
            assertEquals("u-fill-0", suspect.messages.first().text)
            assertEquals("user", suspect.messages.first().role)
            assertEquals(0, suspect.droppedCount)
            assertTrue("deleting sweep must hold suspect", suspect.reasoningIncomplete)
            assertTrue(
                "suspect must over-flag every kept assistant (incl. plain filler)",
                suspect.messages.filter { it.role == "assistant" }.all { it.hasReasoning },
            )
            assertTrue(suspect.messages.none { it.text == "a-h" })
        } finally {
            db1.close()
        }
        val db2 = openFileDb(name)
        try {
            val store2 = RoomSessionStore(db2) { now }
            assertFalse("memory suspect must not survive restart", store2.isPruneSuspect(sid))
            val loaded = loadModelHistory(store2, sid)
            assertEquals(0, loaded.droppedCount)
            assertFalse("converged shape => clean resume, not false-clean", loaded.reasoningIncomplete)
            assertEquals(1998, loaded.messages.size)
            assertEquals("user", loaded.messages.first().role)
            assertEquals("u-fill-0", loaded.messages.first().text)
            assertTrue(loaded.messages.none { it.text == "a-h" })
            assertEquals(
                "clean resume must flag exactly the kept reasoning turn",
                listOf("a-fill-0"),
                loaded.messages.filter { it.hasReasoning }.map { it.text },
            )
            // True Factory.open reopen (same-origin: backfill scan + hydrate).
            val before = store2.countEvents(sid)
            val factory = factoryFor(store2)
            val opened = factory.open(sameOriginEndpoint(), sid)
            try {
                assertEquals("Factory.open must surface the honest dropped count", loaded.droppedCount, opened.historyDroppedCount)
                assertEquals(0, opened.historyDroppedCount)
                assertFalse(opened.historyWithheld)
            } finally {
                opened.session.close()
            }
            assertEquals("reopen must add zero rows (no dangling family)", before, store2.countEvents(sid))
            assertTrue(findDanglingRunIds(store2.loadTailEvents(sid, 2000)).isEmpty())
        } finally {
            db2.close()
            dbFile.delete()
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
        }
    }

    /**
     * R27-2 second-sweep open: first prune converges (deleted 3, suspect),
     * second sweep with the same keep is truly clean (deleted 0, user head →
     * clears suspect) → restart → fresh store + true `Factory.open` resumes
     * clean with the honest dropped count and precise flags.
     */
    @Test
    fun secondSweep_open_cleanResume() = runBlocking {
        val name = "r27-second-open-${UUID.randomUUID()}.db"
        val dbFile = dbFileOf(name)
        val sid: String
        val db1 = openFileDb(name)
        try {
            val store1 = RoomSessionStore(db1) { now }
            sid = store1.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
            seedDirectDao(db1, sid, seedRows())
            assertEquals(2002, store1.countEvents(sid))
            val first = store1.prune(PrunePolicy())
            assertEquals(3, first.deletedEvents)
            assertTrue(store1.isPruneSuspect(sid))
            // Same keep, nothing left to cut: raw 0 + no-progress fixup
            // (head already user) => deleted 0 AND head clean => clears.
            val second = store1.prune(PrunePolicy())
            assertEquals(0, second.deletedEvents)
            assertFalse("clean second sweep must clear suspect", store1.isPruneSuspect(sid))
            assertEquals(1999, store1.countEvents(sid))
        } finally {
            db1.close()
        }
        val db2 = openFileDb(name)
        try {
            val store2 = RoomSessionStore(db2) { now }
            assertFalse(store2.isPruneSuspect(sid))
            val loaded = loadModelHistory(store2, sid)
            assertEquals(0, loaded.droppedCount)
            assertFalse(loaded.reasoningIncomplete)
            assertEquals("u-fill-0", loaded.messages.first().text)
            assertTrue(loaded.messages.none { it.text == "a-h" })
            assertEquals(
                listOf("a-fill-0"),
                loaded.messages.filter { it.hasReasoning }.map { it.text },
            )
            val before = store2.countEvents(sid)
            val opened = factoryFor(store2).open(sameOriginEndpoint(), sid)
            try {
                assertEquals(loaded.droppedCount, opened.historyDroppedCount)
                assertEquals(0, opened.historyDroppedCount)
                assertFalse(opened.historyWithheld)
            } finally {
                opened.session.close()
            }
            assertEquals(before, store2.countEvents(sid))
            assertTrue(findDanglingRunIds(store2.loadTailEvents(sid, 2000)).isEmpty())
        } finally {
            db2.close()
            dbFile.delete()
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
        }
    }

    /**
     * R27-2 snapshot: after the convergent prune, `loadSwitchSnapshotEvents`
     * starts at a user boundary (no head orphan copied) and the durable
     * `snapshotSwitchHistory` fork carries the same user-first prefix.
     */
    @Test
    fun snapshotHeadStartsAtUser_forkStartsAtUser() = runBlocking {
        val db = LibrePocketDb.openInMemory(ApplicationProvider.getApplicationContext())
        memDb = db
        val store = RoomSessionStore(db) { now }
        val sid = store.createSession("t", "m")
        seedDirectDao(db, sid, seedRows())
        assertEquals(2002, store.countEvents(sid))
        val result = store.prune(PrunePolicy())
        assertEquals(3, result.deletedEvents)
        val snap = loadSwitchSnapshotEvents(store, sid)
        assertTrue("snapshot must not copy orphan", snap.events.none { it.text == "a-h" })
        assertNoHeadOrphan(snap.events, "snapshot")
        val qualifying = snap.events.filter { it.kind == "user" || it.kind == "assistant" }
        assertEquals("user", qualifying.first().kind)
        assertEquals("u-fill-0", qualifying.first().text)
        val newId = store.createSession("t", "m")
        val copied = snapshotSwitchHistory(store, sid, newId, "m0", "m1", historyWithheld = false)
        assertEquals("no dangling => full prefix copies", snap.events.size, copied)
        val forked = store.loadEvents(newId, afterSeq = 0, limit = 3000)
        assertTrue(forked.none { it.text == "a-h" })
        val forkQualifying = forked.filter { it.kind == "user" || it.kind == "assistant" }
        assertEquals("user", forkQualifying.first().kind)
        assertEquals("u-fill-0", forkQualifying.first().text)
        assertTrue(findDanglingRunIds(forked).isEmpty())
    }
}

/** No-op provider for the true `Factory.open` restart reopen (empty stream). */
private class R27NoopProvider : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    override fun stream(request: ChatRequest): Flow<StreamEvent> = emptyFlow()
    override suspend fun listModels(): List<String> = emptyList()
}
