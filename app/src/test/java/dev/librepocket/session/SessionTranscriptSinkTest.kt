package dev.librepocket.session

import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
        require(event.kind in VALID_KINDS)
        events.add(event.copy(seq = (events.size + 1).toLong()))
        return events.size.toLong()
    }

    /**
     * R5-4 atomic batch: mirrors [RoomSessionStore.appendEvents] single-
     * transaction semantics without Room — the pre-batch list is snapshotted
     * and any throw (including [CancellationException]) restores it before
     * rethrowing, so a mid-copy failure leaves zero snapshot rows behind.
     * Routes each row through the virtual [appendEvent] so fault-injecting
     * subclasses still observe every row write.
     */
    override suspend fun appendEvents(events: List<TranscriptEvent>): List<Long> {
        if (events.isEmpty()) return emptyList()
        val snapshot = ArrayList(this.events)
        try {
            val ids = ArrayList<Long>(events.size)
            for (event in events) ids.add(appendEvent(event))
            return ids
        } catch (t: Throwable) {
            this.events.clear()
            this.events.addAll(snapshot)
            throw t
        }
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
        sink.onTurnSucceeded("r1", "hi there", hasReasoning = false)
        sink.onTurnRetried("r2", 1, 3, 2000)
        sink.onTurnFailed("r2", "HTTP 500")
        sink.onTurnCancelled("r3", "partial", hasReasoning = false)
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
    fun coreThrowsButNoticesStayBestEffortOnStoreFailure() = runBlocking {
        val store = object : FakeSessionStore() {
            override suspend fun appendEvent(event: TranscriptEvent): Long = error("db down")
        }
        val sink = SessionTranscriptSink(store, "sid")
        // Durable core events must propagate so the session writer fails the
        // ack instead of reporting a false durable write (reasoning markers
        // are part of the core write, so both flag values are covered).
        for (core in listOf<suspend () -> Unit>(
            { sink.onTurnStarted("r1", "hello") },
            { sink.onTurnSucceeded("r1", "hi", hasReasoning = false) },
            { sink.onTurnSucceeded("r1", "hi", hasReasoning = true) },
            { sink.onTurnFailed("r1", "boom") },
            { sink.onTurnFailed("r1", "half", "boom") },
            { sink.onTurnCancelled("r1", "partial", hasReasoning = false) },
            { sink.onTurnCancelled("r1", "partial", hasReasoning = true) },
        )) {
            try {
                core()
                fail("durable core write must not swallow a store failure")
            } catch (_: IllegalStateException) {
                // Explicit failure: no false durable report.
            }
        }
        // Best-effort notices never break the chat loop.
        sink.onTurnRetried("r1", 1, 3, 2000)
        sink.onSteerQueued("be brief")
        sink.onToolDone("r1", 0, "t1", "search", """{"q":"x"}""")
        sink.onUsage("r1", 10, 20)
    }

    @Test
    fun reasoningTurnAppendsEmptyMarkerAfterAssistant() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        sink.onTurnStarted("r1", "hello")
        sink.onTurnSucceeded("r1", "thoughtful answer", hasReasoning = true)

        assertEquals(listOf("user", "assistant", "reasoning"), store.events.map { it.kind })
        val marker = store.events.last()
        assertEquals("r1", marker.runId)
        assertEquals("", marker.text)
    }

    @Test
    fun plainTurnWritesNoMarker() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        sink.onTurnSucceeded("r1", "plain answer", hasReasoning = false)
        sink.onTurnCancelled("r2", "partial", hasReasoning = false)

        assertEquals(listOf("assistant", "assistant"), store.events.map { it.kind })
    }

    @Test
    fun cancelledReasoningTurnAppendsMarker() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        sink.onTurnCancelled("r9", "partial thought", hasReasoning = true)

        assertEquals(listOf("assistant", "reasoning"), store.events.map { it.kind })
        assertEquals("r9", store.events.last().runId)
        assertEquals("", store.events.last().text)
    }
}
