package dev.librepocket.session

import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

/**
 * G3: store-level session list/get (Room, Robolectric + in-memory DB).
 * Titles are re-redacted on read, so legacy rows stay safe to display.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionListTest {

    private var now: Long = 1_700_000_000_000L
    private lateinit var db: LibrePocketDb
    private lateinit var store: RoomSessionStore

    @Before
    fun setUp() {
        db = LibrePocketDb.openInMemory(ApplicationProvider.getApplicationContext())
        store = RoomSessionStore(db) { now++ }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun listSessionsOrdersByUpdatedAt() = runBlocking {
        val first = store.createSession("first chat", "preset:openai/gpt-4o-mini")
        Thread.sleep(2)
        val second = store.createSession("second chat", "preset:openai/gpt-4o-mini")
        val listed = store.listSessions()
        assertEquals(2, listed.size)
        assertEquals(second, listed[0].sessionId)
        assertEquals(first, listed[1].sessionId)
        assertEquals("preset:openai/gpt-4o-mini", listed[0].model)
    }

    @Test
    fun newMessageBumpsSessionToTop() = runBlocking {
        val first = store.createSession("first", "m")
        val second = store.createSession("second", "m")
        store.appendEvent(
            TranscriptEvent(sessionId = first, runId = "r1", kind = "user", text = "hello", createdAt = 1L),
        )
        val listed = store.listSessions()
        assertEquals(first, listed[0].sessionId)
        assertEquals(second, listed[1].sessionId)
    }

    @Test
    fun getSessionRoundTripAndUnknown() = runBlocking {
        val id = store.createSession("hello world", "m")
        val meta = store.getSession(id)
        assertNotNull(meta)
        assertEquals("hello world", meta!!.title)
        assertNull(store.getSession("no-such-session"))
    }

    @Test
    fun legacyTitleIsReRedactedOnRead() = runBlocking {
        // Plant a pre-redaction row straight through the DAO.
        val rawTitle = "sk-ant-oat1-abcdefghijklmnopqrstuvwxyz0123456789"
        val id = "legacy-session"
        db.sessionDao().insertSession(SessionEntity(id, rawTitle, "m", 1L, 2L, false))
        val listed = store.listSessions()
        assertEquals(1, listed.size)
        assertTrue("title must not leak the raw key", !listed[0].title.contains("sk-ant-oat1"))
        // Re-redaction must actually change the displayed title here.
        assertTrue(listed[0].title != rawTitle)
        assertEquals(listed[0].title, store.getSession(id)!!.title)
    }
}
