package dev.librepocket.agent.ui.chat

import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.TranscriptEvent
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class TailFakeProvider(
    var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    val seenRequests: MutableList<ChatRequest> = Collections.synchronizedList(mutableListOf())

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        seenRequests.add(request)
        emitAll(handler(request))
    }

    override suspend fun listModels(): List<String> = emptyList()
}

/**
 * Stage E history-tail regressions (all fake data).
 *
 * - Model history keeps the NEWEST rows (tail), oldest-first restored, with
 *   an exact dropped count ([CreatedSession.historyDroppedCount]).
 * - The outgoing cap windows the combined (prefix + live) total, so the
 *   first send after a full tail evicts one more oldest row to hold 2000.
 * - A giant single row is truncated with a visible marker (fail-closed, never
 *   a silent unbounded payload).
 */
class ChatSessionFactoryHistoryTailTest {

    private fun sampleEndpoint() = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "gpt-4o-mini",
        apiKeyRef = "provider_key/preset:openai",
    )

    private fun factoryWith(
        transcripts: FakeSessionStore,
        provider: TailFakeProvider,
    ) = ChatSessionFactory(
        policy = InMemoryPolicyStore(),
        vaultSource = VaultSource {
            val vault = EncryptedPrefsVault(InMemoryPrefs())
            vault.putKey("preset:openai", "test-key-12345678".toCharArray())
            vault
        },
        buildProvider = { _, _ -> provider },
        sessionStores = object : SessionStoreSource {
            override suspend fun store() = transcripts
        },
    )

    private fun event(
        sid: String,
        runId: String,
        kind: String,
        text: String,
        isPartial: Boolean = false,
    ) = TranscriptEvent(
        sessionId = sid,
        runId = runId,
        kind = kind,
        text = text,
        createdAt = 1L,
        isPartial = isPartial,
    )

    /** 1001 complete turns (user+assistant pairs): 2002 qualifying rows, no dangling. */
    private suspend fun seedPairs(transcripts: FakeSessionStore, sid: String, turns: Int) {
        for (i in 0 until turns) {
            transcripts.appendEvent(event(sid, "r$i", "user", "u$i"))
            transcripts.appendEvent(event(sid, "r$i", "assistant", "a$i"))
        }
    }

    @Test fun open_keepsNewest2000AndReportsExactDropped() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
        seedPairs(transcripts, sid, 1001)

        val provider = TailFakeProvider {
            flow {
                emit(StreamEvent.TextDelta(0, 0, "fresh"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val opened = factoryWith(transcripts, provider).open(sampleEndpoint(), sid)
        try {
            // 2002 qualifying rows, cap 2000: oldest pair (u0,a0) omitted at load.
            assertEquals(2, opened.historyDroppedCount)
            opened.session.send("q-new")
            val req = provider.seenRequests.single()
            // Total-window semantics: the outgoing cap bounds the combined
            // (2000-row prefix + 1 live) copy at 2000, so the new turn evicts
            // one more oldest prefix row (u1). Request holds [a1..a1000, q-new].
            assertEquals(2000, req.messages.size)
            assertEquals("a1", req.messages.first().text)
            assertEquals("a1000", req.messages[2000 - 2].text)
            assertTrue(req.messages.last().text.startsWith("q-new"))
            assertTrue(req.messages.none { it.text == "u0" || it.text == "a0" || it.text == "u1" })
        } finally {
            opened.session.close()
        }
    }

    @Test fun open_noTruncationReportsZeroDropped() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
        seedPairs(transcripts, sid, 3)

        val provider = TailFakeProvider {
            flow {
                emit(StreamEvent.TextDelta(0, 0, "fresh"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val opened = factoryWith(transcripts, provider).open(sampleEndpoint(), sid)
        try {
            assertEquals(0, opened.historyDroppedCount)
            opened.session.send("q-new")
            val req = provider.seenRequests.single()
            assertEquals(
                listOf("u0", "a0", "u1", "a1", "u2", "a2"),
                req.messages.dropLast(1).map { it.text },
            )
        } finally {
            opened.session.close()
        }
    }

    @Test fun open_giantSingleRowTruncatedWithVisibleMarker() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
        val big = "x".repeat(MODEL_HISTORY_MAX_CHARS_PER_MESSAGE + 5_000)
        transcripts.appendEvent(event(sid, "r-big", "user", big))
        transcripts.appendEvent(event(sid, "r-big", "assistant", "ok"))

        val provider = TailFakeProvider {
            flow {
                emit(StreamEvent.TextDelta(0, 0, "fresh"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val opened = factoryWith(transcripts, provider).open(sampleEndpoint(), sid)
        try {
            assertEquals(0, opened.historyDroppedCount)
            opened.session.send("q-new")
            val req = provider.seenRequests.single()
            val hydrated = req.messages.first().text
            // Fail-closed: bounded + marked, never the silent 25k-char payload.
            assertEquals(
                MODEL_HISTORY_MAX_CHARS_PER_MESSAGE + HISTORY_TRUNCATION_MARKER.length,
                hydrated.length,
            )
            assertTrue(hydrated.endsWith(HISTORY_TRUNCATION_MARKER))
            assertTrue(hydrated.startsWith("x".repeat(100)))
        } finally {
            opened.session.close()
        }
    }

    @Test fun cappedHistoryText_boundary() {
        val factory = factoryWith(FakeSessionStore(), TailFakeProvider())
        val exact = "y".repeat(MODEL_HISTORY_MAX_CHARS_PER_MESSAGE)
        assertEquals(exact, factory.cappedHistoryText(exact))
        assertEquals("short", factory.cappedHistoryText("short"))
        val over = "z".repeat(MODEL_HISTORY_MAX_CHARS_PER_MESSAGE + 1)
        val capped = factory.cappedHistoryText(over)
        assertTrue(capped.endsWith(HISTORY_TRUNCATION_MARKER))
        assertEquals(MODEL_HISTORY_MAX_CHARS_PER_MESSAGE + HISTORY_TRUNCATION_MARKER.length, capped.length)
    }

    /**
     * R23-2 exact-cap boundary: exactly HISTORY_LOAD_CAP (2000) qualifying
     * rows plus the newest pair's payload-free `reasoning` marker. Nothing is
     * truncated (`dropped == 0`, `reasoningIncomplete == false`), yet the
     * newest assistant still resumes `hasReasoning == true` via its in-window
     * marker (exact-`runId`), while older plain assistants stay false.
     * Existing HistoryTail tests cover only 2002-over and 3-under.
     */
    @Test fun open_exactCap2000WithNewestMarker_reportsZeroDroppedAndFlagsNewest() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
        val cap = SnapshotBudget.HISTORY_LOAD_CAP
        // Exactly CAP qualifying rows: 999 plain pairs + the newest pair.
        for (i in 0 until cap / 2 - 1) {
            transcripts.appendEvent(event(sid, "r$i", "user", "u$i"))
            transcripts.appendEvent(event(sid, "r$i", "assistant", "a$i"))
        }
        val newest = "r-newest"
        transcripts.appendEvent(event(sid, newest, "user", "q-newest"))
        transcripts.appendEvent(event(sid, newest, "assistant", "a-newest"))
        transcripts.appendEvent(
            TranscriptEvent(
                sessionId = sid,
                runId = newest,
                kind = "reasoning",
                text = "",
                createdAt = 1L,
            ),
        )

        val loaded = loadModelHistory(transcripts, sid)
        assertEquals(0, loaded.droppedCount)
        assertEquals(false, loaded.reasoningIncomplete)
        assertEquals(cap, loaded.messages.size)
        val assistants = loaded.messages.filter { it.role == "assistant" }
        assertEquals(cap / 2, assistants.size)
        assertTrue("newest in-window marker must restore the flag", assistants.last().hasReasoning)
        assertEquals("a-newest", assistants.last().text)
        assertTrue("older plain turns must stay unflagged", assistants.dropLast(1).none { it.hasReasoning })
        // The marker itself never enters model context.
        assertTrue(loaded.messages.none { it.text.isEmpty() && it.role == "reasoning" })
    }

    /**
     * R24-2 oldest-boundary regression (fake): exactly HISTORY_LOAD_CAP
     * (2000) qualifying rows where the oldest retained assistant's marker
     * sits just outside the last backward page — seq=1 `reasoning(r-old)` +
     * seq=2 `assistant(r-old)` + seq=3..2001 filler (1999 qualifying rows).
     * The 200-row DESC reader fills 2000 kept at seq=2 and stops before
     * seq=1, so without the boundary scan `dropped == 0` yet `a-old`
     * resumes `hasReasoning == false` (pre-fix gap).
     *
     * Production writes marker-first (`reasoning` seq N, `assistant` seq N+1,
     * adjacent — see SessionTranscriptSinkTest); this test pins that order.
     * The retained newest exact-2000 test above uses marker-after (newest
     * pair) and stays green. Room JSONL import validates per-row only (no
     * pairing/order rule), so this marker-first layout is a legal import
     * shape.
     */
    @Test fun open_exactCap2000WithOldestMarkerJustOutside_flagsOldest() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
        val cap = SnapshotBudget.HISTORY_LOAD_CAP
        val old = "r-old"
        // Marker-first production order: seq=1 marker, seq=2 assistant.
        transcripts.appendEvent(
            TranscriptEvent(
                sessionId = sid,
                runId = old,
                kind = "reasoning",
                text = "",
                createdAt = 1L,
            ),
        )
        transcripts.appendEvent(event(sid, old, "assistant", "a-old"))
        // Filler: 999 plain pairs (1998 rows) + one trailing user = 1999
        // rows (seq=3..2001). Total qualifying = 1 + 1999 = CAP exactly.
        for (i in 0 until cap / 2 - 1) {
            transcripts.appendEvent(event(sid, "fill-$i", "user", "u-fill-$i"))
            transcripts.appendEvent(event(sid, "fill-$i", "assistant", "a-fill-$i"))
        }
        transcripts.appendEvent(event(sid, "tail", "user", "u-tail"))

        val scan = loadModelHistoryScan(transcripts, sid)
        assertEquals(cap, scan.events.size)
        assertTrue("cap saturated: scan stays suspect", scan.reasoningIncomplete)
        assertTrue("boundary scan must collect the adjacent marker", old in scan.reasoningRunIds)

        val loaded = loadModelHistory(transcripts, sid)
        assertEquals(0, loaded.droppedCount)
        assertEquals(false, loaded.reasoningIncomplete)
        assertEquals(cap, loaded.messages.size)
        val assistants = loaded.messages.filter { it.role == "assistant" }
        assertEquals(cap / 2, assistants.size)
        assertEquals("a-old", assistants.first().text)
        assertTrue("oldest marker just outside the page must restore the flag", assistants.first().hasReasoning)
        assertTrue("plain filler turns must stay unflagged", assistants.drop(1).none { it.hasReasoning })
        assertTrue(loaded.messages.none { it.role == "reasoning" })
    }

    /**
     * R25-1 far-marker regression (fake): exactly HISTORY_LOAD_CAP (2000)
     * qualifying rows where the oldest assistant's marker sits more than one
     * backward page below the window — seq=1 `reasoning(r-old)` + seq=2..201
     * 200 non-model notices + seq=202 `assistant(r-old)` + seq=203..2201
     * filler (1999 qualifying rows). The kept scan holds 2000 qualifying
     * (2201..202); the R24-2 extra 200-row page covers only 201..2 and misses
     * seq=1, so pre-fix `dropped == 0` with `a-old` resuming
     * `hasReasoning == false` (fail-open). Post-fix the bounded
     * `hasReasoningBefore(202)` probe forces `reasoningIncomplete == true`
     * (all kept assistants suspect, like `dropped > 0`). Over-flags plain
     * filler by design (Target: pair-aware narrowing).
     */
    @Test fun open_exactCap2000WithFarMarkerBeyondOnePage_flagsAllConservative() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
        val cap = SnapshotBudget.HISTORY_LOAD_CAP
        val old = "r-old"
        // seq=1: far reasoning marker for the oldest kept assistant.
        transcripts.appendEvent(
            TranscriptEvent(
                sessionId = sid,
                runId = old,
                kind = "reasoning",
                text = "",
                createdAt = 1L,
            ),
        )
        // seq=2..201: 200 non-model notices (never qualifying, never dropped-counted).
        for (i in 0 until 200) {
            transcripts.appendEvent(
                TranscriptEvent(
                    sessionId = sid,
                    runId = "notice-$i",
                    kind = "retry",
                    text = "attempt ${i + 1}/3 after 0ms",
                    createdAt = 1L,
                ),
            )
        }
        // seq=202: the marker's assistant (oldest kept qualifying row).
        transcripts.appendEvent(event(sid, old, "assistant", "a-old"))
        // seq=203..2201: 999 plain pairs (1998 rows) + one trailing user = 1999
        // rows. Total qualifying = 1 + 1999 = CAP exactly.
        for (i in 0 until cap / 2 - 1) {
            transcripts.appendEvent(event(sid, "fill-$i", "user", "u-fill-$i"))
            transcripts.appendEvent(event(sid, "fill-$i", "assistant", "a-fill-$i"))
        }
        transcripts.appendEvent(event(sid, "tail", "user", "u-tail"))

        assertEquals(cap, transcripts.countHistoryEvents(sid, includePartial = false))
        val scan = loadModelHistoryScan(transcripts, sid)
        assertEquals(cap, scan.events.size)
        // capHit conservative (true even at exact CAP, not far evidence).
        // Far evidence is the triple below: hasFarReasoning + loaded
        // Incomplete + dropped==0.
        assertTrue("capHit conservative, true even at exact CAP", scan.reasoningIncomplete)
        assertTrue(
            "far marker must be missed by the one-page boundary scan (201..2 only)",
            old !in scan.reasoningRunIds,
        )
        assertTrue("far marker below the extra-page floor must set the probe", scan.hasFarReasoning)
        assertTrue(
            "bounded probe must still see the far marker below the window",
            transcripts.hasReasoningBefore(sid, scan.events.first().seq),
        )

        val loaded = loadModelHistory(transcripts, sid)
        assertEquals(0, loaded.droppedCount)
        // Far evidence triple: hasFarReasoning + loaded Incomplete + dropped==0.
        assertTrue("far evidence (hasFarReasoning + loaded Incomplete + dropped==0) must fail closed", loaded.reasoningIncomplete)
        assertEquals(cap, loaded.messages.size)
        val assistants = loaded.messages.filter { it.role == "assistant" }
        assertEquals(cap / 2, assistants.size)
        assertEquals("a-old", assistants.first().text)
        assertTrue("far marker assistant must resume flagged", assistants.first().hasReasoning)
        assertTrue(
            "far-marker guard over-flags all kept assistants by design (Target: pair-aware)",
            assistants.all { it.hasReasoning },
        )
        assertTrue(loaded.messages.none { it.role == "reasoning" })
    }

    /**
     * D15-5: a legally imported `seq = Long.MAX_VALUE` row must survive the
     * model-context backward scan (first page starts at the MAX sentinel).
     */
    @Test fun maxSeq_modelHistoryScanSeesMaxRow() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.events.add(event(sid, "r-max", "user", "u-old").copy(seq = Long.MAX_VALUE - 1))
        transcripts.events.add(event(sid, "r-max", "assistant", "a-max").copy(seq = Long.MAX_VALUE))

        val loaded = loadModelHistory(transcripts, sid)
        assertEquals(listOf("u-old", "a-max"), loaded.messages.map { it.text })
        assertEquals(0, loaded.droppedCount)
    }

    /** D15-5: the fork-snapshot scan sees the MAX row too (same sentinel start). */
    @Test fun maxSeq_switchSnapshotScanSeesMaxRow() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.events.add(event(sid, "r-max", "user", "u-old").copy(seq = Long.MAX_VALUE - 1))
        transcripts.events.add(event(sid, "r-max", "assistant", "a-max").copy(seq = Long.MAX_VALUE))

        val snap = loadSwitchSnapshotEvents(transcripts, sid)
        assertEquals(listOf(Long.MAX_VALUE - 1, Long.MAX_VALUE), snap.events.map { it.seq })
        assertEquals(0, snap.droppedByBytes)
    }

    /**
     * Finding1 probe CE symmetry (Factory isPruneSuspect): a
     * CancellationException from the probe must propagate (lifecycle cancel,
     * never swallowed to a history result), while an IOException still
     * fails closed to reasoningIncomplete=true.
     */
    @Test fun pruneSuspectCancellation_propagatesNeverSwallowed() = runBlocking {
        val transcripts = object : FakeSessionStore() {
            override suspend fun isPruneSuspect(sessionId: String): Boolean =
                throw CancellationException("probe cancelled")
        }
        val sid = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.appendEvent(event(sid, "r0", "user", "u0"))
        transcripts.appendEvent(event(sid, "r0", "assistant", "a0"))
        try {
            loadModelHistory(transcripts, sid)
            fail("probe CE must propagate, not return history")
        } catch (_: CancellationException) {
            // Expected: never swallowed to suspect-true history.
        }
    }

    @Test fun pruneSuspectIOException_failsClosedToIncomplete() = runBlocking {
        val transcripts = object : FakeSessionStore() {
            override suspend fun isPruneSuspect(sessionId: String): Boolean =
                throw IOException("store wedged")
        }
        val sid = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.appendEvent(event(sid, "r0", "user", "u0"))
        transcripts.appendEvent(event(sid, "r0", "assistant", "a0"))
        val loaded = loadModelHistory(transcripts, sid)
        assertTrue("wedged suspect read must fail closed", loaded.reasoningIncomplete)
    }

    /**
     * Finding1 probe CE symmetry (Factory hasReasoningBefore): CE
     * propagates from the far-marker probe, while an IOException fails
     * closed to reasoningIncomplete=true. Cap-exact ledger (2000 rows,
     * dropped==0) so only the far probe decides.
     */
    @Test fun farProbeCancellation_propagatesNeverSwallowed() = runBlocking {
        val transcripts = object : FakeSessionStore() {
            override suspend fun hasReasoningBefore(sessionId: String, beforeSeq: Long): Boolean =
                throw CancellationException("far probe cancelled")
        }
        val sid = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
        seedPairs(transcripts, sid, 1000)
        try {
            loadModelHistory(transcripts, sid)
            fail("far-probe CE must propagate, not return history")
        } catch (_: CancellationException) {
            // Expected.
        }
    }

    @Test fun farProbeIOException_failsClosedToIncomplete() = runBlocking {
        val transcripts = object : FakeSessionStore() {
            override suspend fun hasReasoningBefore(sessionId: String, beforeSeq: Long): Boolean =
                throw IOException("far probe wedged")
        }
        val sid = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
        seedPairs(transcripts, sid, 1000)
        val loaded = loadModelHistory(transcripts, sid)
        assertEquals(0, loaded.droppedCount)
        assertTrue("wedged far probe must fail closed", loaded.reasoningIncomplete)
    }

    /**
     * D15-5: sparse ledger (dense prefix + MAX pair) spanning more than one
     * 200-row reader page: the MAX row is kept and every row is visited once.
     */
    @Test fun maxSeq_modelScanMultiPageSparseSeesMax() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
        for (i in 0 until 250) {
            transcripts.appendEvent(event(sid, "r$i", "user", "u$i"))
        }
        transcripts.events.add(event(sid, "r-max", "user", "u-old").copy(seq = Long.MAX_VALUE - 1))
        transcripts.events.add(event(sid, "r-max", "assistant", "a-max").copy(seq = Long.MAX_VALUE))

        val scan = loadModelHistoryScan(transcripts, sid)
        assertEquals(252, scan.events.size)
        assertEquals(252, scan.events.map { it.seq }.toSet().size)
        assertEquals(Long.MAX_VALUE, scan.events.last().seq)
        assertEquals("a-max", scan.events.last().text)
        assertEquals("u0", scan.events.first().text)
    }
}
