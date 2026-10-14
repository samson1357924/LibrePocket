package dev.librepocket.session

import dev.librepocket.redact.Redactor
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** In-memory SessionStore fake (JVM, no Room).
 *
 * Scope: this class only ([FakeSessionStore]). The `SequentialStore` in
 * `SessionSequentialFallbackTest` mirrors the same S1 write shaping
 * (kind gate, reasoning empty-only, redact + 100k truncate, redactError
 * reason; title redacted + blank-model gate on create), the same CASCADE
 * delete, and the same no-prune/no-export/no-import
 * `UnsupportedOperationException` targets, but it is NOT covered by the
 * parity tests (its list/get reads skip the read-time title re-redact below,
 * and it inherits the non-atomic sequential batch loop by design — see its
 * own KDoc non-goal). Hand-written [SessionStore]
 * fakes elsewhere are NOT covered by the parity tests: the `StageCStore` in
 * `TurnControllerStageCTest` (no-op export/prune), the store in
 * `GhostUndeliveredDurableTest` (no-op export/prune), the
 * `DelaySessionStore` in `TurnControllerLedgerTest` (no-op export/prune),
 * the gated store in `ChatSessionLifecycleTest` (no-op export/prune), and
 * the `ExportingFakeStore` in `ChatSessionFactorySwitchSnapshotTest`
 * (overrides export with a real JSONL write).
 *
 * S1 P0 write-shaping parity with [RoomSessionStore.prepareEvent] shaping
 * only: create redacts the title and gates a blank model; append gates the
 * kind (same message as Room), gates a non-empty `reasoning` marker, gates
 * an unknown session (same `"unknown session"` message as Room, checked
 * after shaping just like Room prepares before its session check), redacts
 * text + truncates to [RoomSessionStore.MAX_TEXT_CHARS], redacts
 * `failureReason` via `redactError`, gates `limit > 0` on reads, and
 * re-redacts titles on get/list reads — stored text/reason bytes match Room
 * or throw alike.
 *
 * Explicit non-goals (known gaps, NOT pinned):
 * - `truncated` flag: stored in the Room entity but never exposed via
 *   [TranscriptEvent] reads/exports, so invisible via API.
 * - append `touch` of `updatedAt`: Room bumps the header clock on every
 *   write; Fake keeps the create-time value.
 * - prune/export/import are unsupported targets (see below), by design.
 *
 * Target: no-prune/no-export/no-import — [prune], [exportJsonl] and
 * [importJsonl] always throw `UnsupportedOperationException("unsupported in
 * Fake, use Room")` (never silent success), so a fork-snapshot byte/row cap
 * can never green-light on a full-copy Fake. Callers touching those paths
 * must use Room (Robolectric). [deleteSession] cascades to events like the
 * Room FK CASCADE.
 */
open class FakeSessionStore : SessionStore {
    val metas = LinkedHashMap<String, SessionMeta>()
    val events = ArrayList<TranscriptEvent>()
    var now = 1_700_000_000_000L

    override suspend fun createSession(title: String, model: String): String {
        require(model.isNotBlank()) { "model must not be blank" }
        val id = UUID.randomUUID().toString()
        metas[id] = SessionMeta(id, Redactor.redact(title).text, now, now, model)
        return id
    }

    override suspend fun listSessions(): List<SessionMeta> =
        metas.values.sortedByDescending { it.updatedAt }
            .map { it.copy(title = Redactor.redact(it.title).text) }

    override suspend fun getSession(sessionId: String): SessionMeta? {
        val meta = metas[sessionId] ?: return null
        return meta.copy(title = Redactor.redact(meta.title).text)
    }

    override suspend fun appendEvent(event: TranscriptEvent): Long {
        // S1 F8 Room parity (RoomSessionStore.prepareEvent shaping only):
        // kind gate + reasoning empty-only + redact + 100k truncate +
        // redactError reason. Shaping runs before the session check, exactly
        // like Room (prepareEvent precedes the in-tx sessionById gate), so a
        // bad kind on an unknown session still reports the kind gate.
        require(event.kind in VALID_KINDS) { "unknown event kind: ${event.kind}" }
        require(event.kind != "reasoning" || event.text.isEmpty()) { "reasoning marker must have empty text" }
        val redacted = Redactor.redact(event.text).text
        val text = if (redacted.length > RoomSessionStore.MAX_TEXT_CHARS) {
            redacted.take(RoomSessionStore.MAX_TEXT_CHARS)
        } else {
            redacted
        }
        val reason = event.failureReason?.let { Redactor.redactError(it) }
        // S1 F1 Room parity (RoomSessionStore "unknown session" gate).
        require(metas.containsKey(event.sessionId)) { "unknown session" }
        // D15-5 Room parity: seq is the per-session max + 1 (identical to
        // the old size + 1 for dense single-session stores), refused
        // fail-closed once the space is exhausted so no wrapped/duplicate
        // row is ever written. Must filter by sessionId like Room's
        // WHERE sessionId = :sid, otherwise a MAX row in another session
        // would wrongly exhaust this session.
        val max = events.filter { it.sessionId == event.sessionId }.maxOfOrNull { it.seq } ?: 0L
        check(max < Long.MAX_VALUE) { "seq exhausted" }
        events.add(event.copy(seq = max + 1, text = text, failureReason = reason))
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

    override suspend fun loadEvents(sessionId: String, afterSeq: Long, limit: Int): List<TranscriptEvent> {
        // Room parity: RoomSessionStore.loadEvents gates limit > 0.
        require(limit > 0) { "limit must be positive" }
        return events.filter { it.sessionId == sessionId && it.seq > afterSeq }.take(limit)
    }

    /**
     * S1 F9 Target: Fake is no-prune/no-export/no-import. These always throw
     * (never silent success) so fork-snapshot caps cannot green-light on a
     * full-copy Fake; use Room (Robolectric) for those paths.
     */
    override suspend fun exportJsonl(sessionId: String, destFile: File) {
        throw UnsupportedOperationException("unsupported in Fake, use Room")
    }
    override suspend fun importJsonl(srcFile: File): String =
        throw UnsupportedOperationException("unsupported in Fake, use Room")
    override suspend fun prune(policy: PrunePolicy): PruneResult =
        throw UnsupportedOperationException("unsupported in Fake, use Room")
    override suspend fun deleteSession(sessionId: String) {
        // S1 F1 Room CASCADE parity: header + events atomically gone.
        metas.remove(sessionId)
        events.removeAll { it.sessionId == sessionId }
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
    fun reasoningTurnAppendsEmptyMarkerBeforeAssistant() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("hi", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }

        sink.onTurnStarted("r1", "hello")
        sink.onTurnSucceeded("r1", "thoughtful answer", hasReasoning = true)

        // Phase2 marker-first: [reasoning, assistant] so a non-atomic
        // fallback leaves an orphan marker (fail-closed), never an orphan
        // assistant (fail-open).
        assertEquals(listOf("user", "reasoning", "assistant"), store.events.map { it.kind })
        val marker = store.events[1]
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

        // Phase2 marker-first: [reasoning, assistant].
        assertEquals(listOf("reasoning", "assistant"), store.events.map { it.kind })
        assertEquals("r9", store.events.first().runId)
        assertEquals("", store.events.first().text)
    }
}
