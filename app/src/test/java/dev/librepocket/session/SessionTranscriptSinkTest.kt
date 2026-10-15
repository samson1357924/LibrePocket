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
     * R25-1 far-marker probe (in-memory, same session + `seq <` only).
     */
    override suspend fun hasReasoningBefore(sessionId: String, beforeSeq: Long): Boolean =
        events.any { it.sessionId == sessionId && it.seq < beforeSeq && it.kind == "reasoning" }

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

/**
 * S4-R2 prune-suspect test fake, R26-only frozen shape (NOT R27 Room behavior;
 * Fake itself stays no-prune per S1 F9 Target: [FakeSessionStore.prune]
 * always throws, never silent success – use Room for real prune paths).
 * This subclass simulates a keep-newest row cut PLUS the R26-1 head-family
 * fixup: [simulatePruneCut] drops the oldest rows beyond [keepNewestRows],
 * then while the oldest kept user/assistant row is an assistant deletes that
 * whole logical family (`parentRunId ?: runId`, bounded loop), and marks the
 * session suspect iff the total dropped > 0 (per-sweep, NOT sticky — a later
 * clean cut clears). [deleteSession] clears suspect; appends never clear (a
 * later append cannot resurrect a pruned marker). Memory-only (restart drops
 * it); the DB/delete-vs-read-filter `dropped` gap is eliminated by
 * construction (both sides delete the family in storage, so both report
 * `dropped==0` + suspect with the head family gone whole) for the shared
 * intersection shape only. Known R26-vs-R27 divergences (frozen, do NOT sync
 * to Room; R27 semantics proven by Room tests, not by this fake — see
 * P1_SPEC R27 paragraph): (1) gate — fake runs fixup only when `drop > 0`,
 * R27 Room runs fixup unconditionally in the same tx (no `deletes > 0`
 * gate); (2) bound — fake uses a `guard < 10` bounded loop, R27 Room uses
 * unbounded `while(true)` + break-on-no-progress convergence; (3) clear —
 * fake clears suspect unconditionally on `drop == 0`, R27 Room clears only
 * on shape-aware clean (post-fixup user/empty head) and keeps suspect on
 * assistant-head residue (`deleted == 0` + assistant head).
 */
open class SuspectFakeStore : FakeSessionStore() {
    private val suspect = HashSet<String>()

    /**
     * Simulate a keep-newest prune cut with the R26-1 family fixup (R26-only
     * frozen shape; see class KDoc for the three R27 divergences). Drop
     * oldest rows beyond keep, then delete whole cut head families. Returns
     * the TOTAL dropped (raw cut + family fixup), equal to Room's
     * `PruneResult.deletedEvents` only for the shared intersection shape —
     * R27 semantics proven by Room tests. Crafted duplicate-`seq` ties
     * (production `seq` is unique) resolve by list order here vs `rowId`
     * order in Room.
     */
    fun simulatePruneCut(sessionId: String, keepNewestRows: Int): Int {
        val rows = events.filter { it.sessionId == sessionId }.sortedBy { it.seq }
        var drop = (rows.size - keepNewestRows).coerceAtLeast(0)
        if (drop > 0) {
            val dropSeqs = rows.take(drop).map { it.seq }.toSet()
            events.removeAll { it.sessionId == sessionId && it.seq in dropSeqs }
            // R26-only frozen head-family fixup: head non-user ⇒ delete the
            // whole parentRunId?:runId family, bounded loop, size-convergent
            // (breaks on no progress). Differs from R27 Room (unconditional,
            // unbounded convergent fixup in the same tx).
            var guard = 0
            while (guard++ < 10) {
                val head = events.filter { it.sessionId == sessionId && (it.kind == "user" || it.kind == "assistant") }
                    .minByOrNull { it.seq } ?: break
                if (head.kind == "user") break
                val family = (head.parentRunId ?: head.runId)
                if (family.isBlank()) break
                val before = events.size
                // Family predicate (same sides as SessionDao.deleteFamily):
                // match on either side of the family id, not just familyOf.
                // R26-only frozen shape; R27 Room behavior proven by Room tests.
                events.removeAll {
                    it.sessionId == sessionId && (it.runId == family || it.parentRunId == family)
                }
                val removed = before - events.size
                if (removed <= 0) break
                drop += removed
            }
            suspect.add(sessionId)
        } else {
            // Clean cut: per-sweep, not sticky. R26-only frozen: unconditional
            // clear on drop==0; R27 Room clears only on shape-aware clean
            // (user/empty head) and keeps suspect on assistant-head residue.
            suspect.remove(sessionId)
        }
        return drop
    }

    override suspend fun isPruneSuspect(sessionId: String): Boolean = suspect.contains(sessionId)

    override suspend fun deleteSession(sessionId: String) {
        super.deleteSession(sessionId)
        suspect.remove(sessionId)
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
