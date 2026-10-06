package dev.librepocket.session

import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory SessionStore fake (JVM, no Room). */
open class FakeSessionStore : SessionStore {
    val metas = LinkedHashMap<String, SessionMeta>()
    val events = ArrayList<TranscriptEvent>()
    var now = 1_700_000_000_000L

    override suspend fun createSession(title: String, model: String): String {
        val id = UUID.randomUUID().toString()
        metas[id] = SessionMeta(id, title, now, now, model)
        return id
    }

    override suspend fun listSessions(): List<SessionMeta> =
        metas.values.sortedByDescending { it.updatedAt }

    override suspend fun getSession(sessionId: String): SessionMeta? = metas[sessionId]

    override suspend fun appendEvent(event: TranscriptEvent): Long {
        require(event.kind in setOf("user", "assistant", "tool", "steer", "retry", "system"))
        events.add(event.copy(seq = (events.size + 1).toLong()))
        return events.size.toLong()
    }

    override suspend fun loadEvents(sessionId: String, afterSeq: Long, limit: Int): List<TranscriptEvent> =
        events.filter { it.sessionId == sessionId && it.seq > afterSeq }.take(limit)

    override suspend fun exportJsonl(sessionId: String, destFile: File) = Unit
    override suspend fun importJsonl(srcFile: File): String = error("unused")
    override suspend fun prune(policy: PrunePolicy): PruneResult = PruneResult(0, 0)
    override suspend fun deleteSession(sessionId: String) {
        metas.remove(sessionId)
    }
}

class SessionTranscriptSinkTest {

    @Test
    fun turnLifecycleAppendsKinds() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        sink.onTurnStarted("r1", "hello")
        sink.onTurnSucceeded("r1", "hi there")
        sink.onTurnRetried("r2", 1, 3, 2000)
        sink.onTurnFailed("r2", "HTTP 500")
        sink.onTurnCancelled("r3", "partial")
        sink.onSteerQueued("be brief")
        sink.onToolDone("r1", 0, "t1", "search", """{"q":"x"}""")
        sink.onUsage("r1", 10, 20)

        val kinds = store.events.map { it.kind }
        assertEquals(
            listOf("user", "assistant", "retry", "system", "assistant", "steer", "tool", "system"),
            kinds,
        )
        assertTrue(store.events.all { it.sessionId == sid && it.createdAt == 42L })
        assertEquals("hello", store.events[0].text)
        assertEquals("hi there", store.events[1].text)
    }

    @Test
    fun sinkNeverThrowsOnStoreFailure() = runBlocking {
        val store = object : FakeSessionStore() {
            override suspend fun appendEvent(event: TranscriptEvent): Long = error("db down")
        }
        val sink = SessionTranscriptSink(store, "sid")
        // Must not propagate: persistence never breaks the chat loop.
        sink.onTurnStarted("r1", "hello")
        sink.onTurnSucceeded("r1", "hi")
        sink.onTurnFailed("r1", "boom")
        sink.onTurnRetried("r1", 1, 3, 2000)
        sink.onTurnCancelled("r1", "partial")
        sink.onSteerQueued("be brief")
        sink.onToolDone("r1", 0, "t1", "search", """{"q":"x"}""")
        sink.onUsage("r1", 10, 20)
    }
}
