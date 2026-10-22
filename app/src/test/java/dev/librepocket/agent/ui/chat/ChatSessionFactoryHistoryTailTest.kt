package dev.librepocket.agent.ui.chat

import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
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
import org.junit.Assert.assertFalse
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
        policy = allowKeyReadPolicy(),
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

    @Test fun open_giantAssistantRowCompactsToolMarkersInModelContext() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
        val args = "a".repeat(10_000)
        val huge = "answer\n[tool:lsp.symbols $args]" + "z".repeat(15_000)
        transcripts.appendEvent(event(sid, "r-tool", "user", "q"))
        transcripts.appendEvent(event(sid, "r-tool", "assistant", huge))

        val provider = TailFakeProvider {
            flow {
                emit(StreamEvent.TextDelta(0, 0, "fresh"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val opened = factoryWith(transcripts, provider).open(sampleEndpoint(), sid)
        try {
            opened.session.send("q-new")
            val req = provider.seenRequests.single()
            val hydrated = req.messages[1].text
            // Model context: marker bounded, args echo dropped, answer kept.
            assertTrue(hydrated.contains("[tool:lsp.symbols …]"))
            assertFalse(hydrated.contains("a".repeat(32)))
            assertTrue(hydrated.contains("z".repeat(1_000)))
            // Persistence is untouched: compaction is a read-path projection.
            val raw = transcripts.loadEvents(sid, 0, 10).first { it.kind == "assistant" }.text
            assertEquals(huge, raw)
        } finally {
            opened.session.close()
        }
    }

    @Test fun open_giantUserRowNeverCompactsToolMarkerText() = runBlocking {
        // The hydration call site compacts assistant rows only. A user row's
        // `[tool:...]` text is user content; it must reach the model verbatim
        // even when the same row is capped.
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
        val marker = "[tool:note keep these user args]"
        val huge = "user start\n$marker " + "z".repeat(30_000)
        transcripts.appendEvent(event(sid, "r-user-tool", "user", huge))
        transcripts.appendEvent(event(sid, "r-user-tool", "assistant", "ok"))

        val provider = TailFakeProvider {
            flow {
                emit(StreamEvent.TextDelta(0, 0, "fresh"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val opened = factoryWith(transcripts, provider).open(sampleEndpoint(), sid)
        try {
            opened.session.send("q-new")
            val hydratedUser = provider.seenRequests.single().messages.first().text
            assertTrue(hydratedUser.contains(marker))
            assertFalse(hydratedUser.contains("[tool:note …]"))
            assertTrue(hydratedUser.endsWith(HISTORY_TRUNCATION_MARKER))
            val raw = transcripts.loadEvents(sid, 0, 10).first { it.kind == "user" }.text
            assertEquals(huge, raw)
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

    @Test fun cappedHistoryText_compactsToolMarkerArgsBeforeCap() {
        // A huge inline args echo is audit text; it must not consume the row
        // budget or push the visible answer past the 20k cut. Compaction keeps
        // the marker (name only) so the model still sees the tool-call trace.
        val args = "a".repeat(10_000)
        val text = "head\n[tool:lsp.symbols $args]" + "z".repeat(15_000)
        assertTrue(text.length > MODEL_HISTORY_MAX_CHARS_PER_MESSAGE)
        val capped = cappedHistoryText(text)
        assertTrue("marker survives in bounded form", capped.contains("[tool:lsp.symbols …]"))
        assertFalse("args echo must be dropped", capped.contains("a".repeat(32)))
        assertTrue("answer after the marker must not be pushed out", capped.contains("z".repeat(1_000)))
        // Compaction alone brought this row under the cap: no cap cut happened,
        // so a "history truncated" suffix would falsely claim content loss.
        assertFalse(capped.endsWith(HISTORY_TRUNCATION_MARKER))
    }

    @Test fun cappedHistoryText_preservesClosedMidLineMarkerProse() {
        // Only line-start producer markers are compacted. A closed marker shape
        // inside ordinary prose is user text and must survive over-cap capping
        // beside the rest of the prefix.
        val text = "start [tool:note keep these args] " + "y".repeat(30_000)
        val capped = cappedHistoryText(text)
        assertTrue(capped.contains("start [tool:note keep these args]"))
        assertFalse(capped.contains("[tool:note …]"))
    }

    @Test fun cappedHistoryText_roleAwareCompactionKeepsUserArgs() {
        val marker = "\n[tool:lsp.symbols {\"query\":\"" + "a".repeat(1_000) + "\"}]"
        val text = marker + "z".repeat(30_000)
        val user = cappedHistoryText(text, compactMarkers = false)
        assertTrue("user rows must not be rewritten", user.contains("a".repeat(32)))
        val assistant = cappedHistoryText(text, compactMarkers = true)
        assertTrue(assistant.contains("[tool:lsp.symbols …]"))
    }

    @Test fun compactToolMarkers_respectsClosingBracketInsideQuotedArgs() {
        // The args echo is JSON; a `]` inside a quoted value must not close the
        // marker early and strand the remainder as ordinary text.
        val marker = "[tool:lsp.symbols {\"query\":\"a]b\"}]"
        val tail = "z".repeat(100)
        assertEquals(
            "[tool:lsp.symbols …]$tail",
            compactToolMarkers(marker + tail),
        )
    }

    @Test fun compactToolMarkers_doesNotSpanLinesInsideQuotedArgs() {
        // A literal newline inside a quoted value is not producer output (the
        // producer folds CR/LF); treating it as unterminated prevents a crafted
        // multi-line span from compacting intervening assistant prose.
        val text = "[tool:lsp.symbols {\"query\":\"oops\nstill prose\n\"]}] tail"
        assertEquals(text, compactToolMarkers(text))
    }

    @Test fun cappedHistoryText_neverSplitsSurrogateAtCap() {
        val text = "a".repeat(MODEL_HISTORY_MAX_CHARS_PER_MESSAGE - 1) + "😀" +
            "b".repeat(100)
        val body = cappedHistoryText(text).removeSuffix(HISTORY_TRUNCATION_MARKER)
        assertEquals(MODEL_HISTORY_MAX_CHARS_PER_MESSAGE - 1, body.length)
        assertFalse("cap must not leave a lone high surrogate", body.last().isHighSurrogate())
    }

    @Test fun cappedHistoryText_neverEndsInsideToolMarker() {
        // The 20k cut can land in the middle of a compacted marker; a partial
        // "[tool:lsp.symbol…" is not replayable and must be dropped instead.
        // Producer markers start at line start (TurnController appends "\n").
        val prefixLen = MODEL_HISTORY_MAX_CHARS_PER_MESSAGE - 10
        val text = "y".repeat(prefixLen - 1) + "\n" +
            "[tool:lsp.symbols " + "b".repeat(100) + "]"
        val body = cappedHistoryText(text).removeSuffix(HISTORY_TRUNCATION_MARKER)
        assertFalse(body.contains("[tool:"))
        assertEquals(prefixLen, body.length)
    }

    @Test fun cappedHistoryText_dropsEveryTrailingPartialMarkerOpener() {
        // Two producer-shaped openers straddle the cut with no closing bracket:
        // dropping only the last one would leave the first fragment visible.
        val text = "y".repeat(MODEL_HISTORY_MAX_CHARS_PER_MESSAGE - 16) +
            "\n[tool:a\n[tool:b" + "z".repeat(10)
        val body = cappedHistoryText(text).removeSuffix(HISTORY_TRUNCATION_MARKER)
        assertFalse(body.contains("[tool:"))
        assertEquals(MODEL_HISTORY_MAX_CHARS_PER_MESSAGE - 15, body.length)
    }

    @Test fun cappedHistoryText_neverEndsInsideMarkerOpenerPrefix() {
        // The cut may fall 1-6 chars into the opener itself, not just after it.
        for (opener in listOf("[", "[t", "[to", "[too", "[tool", "[tool:")) {
            val prefixLen = MODEL_HISTORY_MAX_CHARS_PER_MESSAGE - opener.length
            val text = "y".repeat(prefixLen - 1) + "\n" + opener +
                "name args]\n" + "z".repeat(100)
            val body = cappedHistoryText(text).removeSuffix(HISTORY_TRUNCATION_MARKER)
            assertEquals("opener='$opener'", prefixLen, body.length)
            assertFalse("opener='$opener'", body.endsWith("["))
        }
    }

    @Test fun cappedHistoryText_trimsOverlappingOpenerPrefixes() {
        // Trimming one prefix can expose another ("[[tool:" / "[tool:[").
        for (tail in listOf("[[tool:", "[tool:[")) {
            val text = "y".repeat(MODEL_HISTORY_MAX_CHARS_PER_MESSAGE - tail.length) +
                tail + "z".repeat(10)
            val body = cappedHistoryText(text).removeSuffix(HISTORY_TRUNCATION_MARKER)
            assertFalse("tail='$tail'", body.endsWith("["))
            assertEquals(
                "tail='$tail'",
                MODEL_HISTORY_MAX_CHARS_PER_MESSAGE - 7,
                body.length,
            )
        }
    }

    @Test fun cappedHistoryText_underCapRowsReplayByteIdentical() {
        // Compaction is strictly an over-cap projection: short rows containing
        // real markers must reach the provider unchanged.
        for (text in listOf(
            "[tool:lsp.symbols {\"q\":\"x\"}]",
            "a\n[tool:t {\"k\":1}]\nb",
        )) {
            assertEquals(text, cappedHistoryText(text))
        }
    }

    @Test fun cappedHistoryText_ignoresMidLineMarkerFragment() {
        // A literal mid-line "[tool:" in an ordinary over-cap paste is not a
        // producer marker; truncating at it would drop most of the row.
        val text = "prefix [tool:realname " + "y".repeat(MODEL_HISTORY_MAX_CHARS_PER_MESSAGE)
        val body = cappedHistoryText(text).removeSuffix(HISTORY_TRUNCATION_MARKER)
        assertEquals(MODEL_HISTORY_MAX_CHARS_PER_MESSAGE, body.length)
        assertTrue(body.endsWith("y"))
    }

    @Test fun compactToolMarkers_boundsNamesAndLeavesFragmentsVerbatim() {
        // Normal rows bypass compaction entirely (call path above), but the
        // helper itself only touches real marker shapes.
        assertEquals("[tool:oops] and [tool:] stay", compactToolMarkers("[tool:oops] and [tool:] stay"))
        assertEquals("[TOOL:y z]", compactToolMarkers("[TOOL:y z]"))
        assertEquals(
            "[tool:${"n".repeat(64)} …]",
            compactToolMarkers("[tool:${"n".repeat(200)} args]"),
        )
        assertEquals("[tool:lsp.symbols …]", compactToolMarkers("[tool:lsp.symbols {\"q\":\"x\"}]"))
        // Short empty/no-args markers stay byte-identical: adding " …" would
        // grow the marker; over-long names are capped even in those shapes.
        assertEquals("[tool:a ]", compactToolMarkers("[tool:a ]"))
        assertEquals("[tool:oops]", compactToolMarkers("[tool:oops]"))
        assertEquals("[tool:${"n".repeat(64)} …]", compactToolMarkers("[tool:${"n".repeat(200)} ]"))
        assertEquals("[tool:${"n".repeat(64)}]", compactToolMarkers("[tool:${"n".repeat(200)}]"))
        // 65-unit no-args name would grow if compacted to "[name …]"; the
        // no-args shape shrinks instead.
        assertEquals("[tool:${"n".repeat(64)}]", compactToolMarkers("[tool:${"n".repeat(65)}]"))
        // The 64-unit cap must not split a surrogate pair.
        assertEquals(
            "[tool:${"n".repeat(63)} …]",
            compactToolMarkers("[tool:${"n".repeat(63)}😀${"x".repeat(200)} args]"),
        )
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


    @Test fun historyTail_toolResultMarkerHydratesBoundedAndPersistsRaw() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
        val json = "x".repeat(20_000)
        val marker = "\n[tool-result:lsp.symbols:${json.length}:$json]"
        val huge = "answer prefix" + marker + "\nFINAL ANSWER"
        transcripts.appendEvent(event(sid, "r-tool", "user", "q"))
        transcripts.appendEvent(event(sid, "r-tool", "assistant", huge))

        val provider = TailFakeProvider {
            flow {
                emit(StreamEvent.TextDelta(0, 0, "fresh"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val opened = factoryWith(transcripts, provider).open(sampleEndpoint(), sid)
        try {
            opened.session.send("q-new")
            val hydrated = provider.seenRequests.single().messages[1].text
            assertTrue(hydrated.contains("[tool-result:lsp.symbols:${json.length}:…]"))
            assertTrue(hydrated.contains("FINAL ANSWER"))
            assertFalse(hydrated.contains("x".repeat(32)))
            val raw = transcripts.loadEvents(sid, 0, 10).first { it.kind == "assistant" }.text
            assertEquals(huge, raw)
        } finally {
            opened.session.close()
        }
    }

    @Test fun compactToolMarkers_toolResultNestedBracketsAndQuotedClose() {
        val json = """[{"a]b":["c]d",{"e":"f]g"}]}"""
        val tail = "\nTAIL"
        assertEquals(
            "\n[tool-result:lsp.symbols:${json.length}:…]$tail",
            compactToolMarkers("\n[tool-result:lsp.symbols:${json.length}:$json]$tail"),
        )
    }

    @Test fun compactToolMarkers_toolResultMalformedLenNotRewritten() {
        for (marker in listOf(
            "\n[tool-result:lsp.symbols:999:{\"x\":1}]\nANSWER",
            "\n[tool-result:lsp.symbols:abc:{\"x\":1}]\nANSWER",
            "\n[tool-result:lsp.symbols:-1:{\"x\":1}]\nANSWER",
            "\n[tool-result:lsp.symbols:5:{\"x\":1}]\nANSWER",
        )) {
            assertEquals(marker, compactToolMarkers(marker))
        }
    }

    @Test fun compactToolMarkers_toolResultUnterminatedPreservesTail() {
        val text = "head\n[tool-result:lsp.symbols:5:{\"x\""
        val out = compactToolMarkers(text + "\nANSWER")
        assertTrue(out.contains("ANSWER"))
    }

    @Test fun cappedHistoryText_malformedToolResultCapDoesNotSplitOpener() {
        val text = "ANSWER\n[tool-result:lsp.symbols:abc:{}] " + "z".repeat(MODEL_HISTORY_MAX_CHARS_PER_MESSAGE)
        val capped = cappedHistoryText(text)
        val body = capped.removeSuffix(HISTORY_TRUNCATION_MARKER)
        assertFalse(body.contains("[tool-result:lsp.symbols:abc:…]"))
        assertFalse(body.endsWith("[tool-result:") || body.endsWith("[tool:"))
        assertTrue(body.contains("ANSWER"))
    }

    @Test fun cappedHistoryText_userRowUnterminatedToolMarkerPureCap() {
        val opener = "\n[tool:note "
        val text = "u".repeat(MODEL_HISTORY_MAX_CHARS_PER_MESSAGE - 5) + opener + "z".repeat(100)
        val capped = cappedHistoryText(text, compactMarkers = false)
        val body = capped.removeSuffix(HISTORY_TRUNCATION_MARKER)
        assertEquals(MODEL_HISTORY_MAX_CHARS_PER_MESSAGE, body.length)
        assertEquals(text.take(MODEL_HISTORY_MAX_CHARS_PER_MESSAGE), body)
    }

    @Test fun displayAssistantText_boundsAndPreserves() {
        val text = "intro\n[tool:lsp.symbols {\"q\":\"x\"}]\n[tool-result:lsp.symbols:7:{\"a\":1}]\nFINAL"
        val out = displayAssistantText(text)
        assertTrue(out.contains("[tool:lsp.symbols …]"))
        assertTrue(out.contains("[tool-result:lsp.symbols:7:…]"))
        assertTrue(out.contains("FINAL"))
        assertEquals(text, displayAssistantText("user", text))
        val inline = "see [tool:note keep] here"
        assertEquals(inline, displayAssistantText(inline))
        val partial = "head\n[tool:note " + "x".repeat(10)
        assertEquals(partial, displayAssistantText(partial))
        val after = "ANSWER\n[tool:t {}]"
        assertTrue(displayAssistantText(after).contains("ANSWER"))
    }

    @Test fun compactToolMarkers_resultMarkerNameBoundaries() {
        val json = "{}"
        val longName = "n".repeat(200)
        val longMarker = "\n[tool-result:$longName:${json.length}:$json]"
        val longOut = compactToolMarkers(longMarker)
        assertTrue(longOut.contains("[tool-result:${"n".repeat(64)}:${json.length}:…]"))

        // Empty name is not a valid result marker -> left verbatim.
        val emptyMarker = "\n[tool-result::${json.length}:$json]"
        assertEquals(emptyMarker, compactToolMarkers(emptyMarker))

        // Surrogate-bearing name must not crash and must stay bounded.
        val surrName = "n".repeat(63) + "\uD83D"
        val surrMarker = "\n[tool-result:$surrName:${json.length}:$json]"
        val surrOut = compactToolMarkers(surrMarker)
        assertTrue(surrOut.length <= surrMarker.length)
    }

    @Test fun compactToolMarkers_hugeLenNotRewritten() {
        for (marker in listOf(
            "\n[tool-result:lsp.symbols:2147483647:{}]\nANSWER",
            "\n[tool-result:lsp.symbols:2147483648:{}]\nANSWER",
            "\n[tool-result:lsp.symbols:999999999999999999999:{}]\nANSWER",
        )) {
            assertEquals(marker, compactToolMarkers(marker))
            assertTrue(compactToolMarkers(marker).contains("ANSWER"))
            assertFalse(compactToolMarkers(marker).contains("\u2026]"))
        }
    }

    @Test fun compactToolMarkers_leadingZeroMillionNotGiantReceipt() {
        val marker = "\n[tool-result:lsp.symbols:${"0".repeat(5000)}1:{}]\nANSWER"
        val out = compactToolMarkers(marker)
        assertEquals(marker, out)
        assertTrue(out.contains("ANSWER"))
        assertTrue(out.length <= marker.length + 1)
        assertFalse(out.contains("\u2026]"))
    }

    @Test fun compactToolMarkers_invalidResultMarkerThenValidMarkerCompacted() {
        val text = "head\n[tool-result:lsp.symbols:abc:{}]\n[tool:lisp {\"q\":\"x\"}]\n[tool-result:lsp.symbols:7:{\"a\":1}]\nANSWER"
        val out = compactToolMarkers(text)
        assertTrue(out.contains("ANSWER"))
        assertTrue(out.contains("[tool:lisp \u2026]"))
        assertTrue(out.contains("[tool-result:lsp.symbols:7:\u2026]"))
        assertFalse(out.contains("[tool-result:lsp.symbols:abc:\u2026]"))
    }

    @Test fun compactToolMarkers_unterminatedResultMarkerThenValidMarker() {
        val text = "head\n[tool-result:lsp.symbols:5:{\"x\"\n[tool:lisp {\"q\":\"x\"}]\nANSWER"
        val out = compactToolMarkers(text)
        assertTrue(out.contains("ANSWER"))
        assertTrue(out.contains("[tool:lisp \u2026]"))
    }

    /**
     * F2: an unterminated (producer-shaped) opener must not abort the whole
     * compaction scan. The markers behind it still have to be compacted —
     * otherwise their args echo keeps the row over the cap and pushes the
     * answer past the cut. The malformed opener text itself is preserved
     * (it may be crafted prose), never dropped.
     */
    @Test fun compactToolMarkers_unterminatedProducerOpenerStillCompactsLaterMarkers() {
        val text = "head\n[tool:foo {\"q\":\"\n[tool:lisp {\"q\":\"x\"}]\n" +
            "[tool-result:lsp.symbols:7:{\"a\":1}]\nANSWER"
        val out = compactToolMarkers(text)
        assertTrue("later producer marker must be compacted", out.contains("[tool:lisp \u2026]"))
        assertTrue("later result marker must be compacted", out.contains("[tool-result:lsp.symbols:7:\u2026]"))
        assertTrue("answer must survive", out.contains("ANSWER"))
        assertTrue("malformed opener text must be preserved", out.contains("[tool:foo"))
        assertTrue("prose must be preserved", out.contains("{\"q\":\""))
    }

    /**
     * F1: a malformed opener in front of a valid marker must not take the
     * valid marker (or the answer behind it) down with it. The raw
     * "last `]`" heuristic treated the first malformed opener as the start of
     * an unterminated region and dropped everything behind it.
     */
    @Test fun cappedHistoryText_malformedProducerOpenerKeepsLaterMarkerAndAnswer() {
        val text = "head\n[tool:foo {\"q\":\"" + "z".repeat(100) + "\n[tool:lsp.symbols " +
            "a".repeat(30_000) + "]\nANSWER"
        val capped = cappedHistoryText(text)
        assertTrue("later valid marker must survive", capped.contains("[tool:lsp.symbols \u2026]"))
        assertTrue("answer must survive", capped.contains("ANSWER"))
        assertFalse(
            "no cap cut happened: no truncation suffix",
            capped.endsWith(HISTORY_TRUNCATION_MARKER),
        )
    }

    /**
     * F3: a `]` inside quoted args does not close the marker. The cut must
     * not land inside such a marker and leave a partial `[tool:foo` fragment
     * behind (the old raw-last-`]` heuristic believed the quoted `]` closed
     * the marker).
     */
    @Test fun cappedHistoryText_quotedBracketInsideArgsDoesNotKeepPartialMarker() {
        val text = "head\n[tool:foo {\"a\":\"]" + "b".repeat(30_000)
        val capped = cappedHistoryText(text)
        val body = capped.removeSuffix(HISTORY_TRUNCATION_MARKER)
        assertFalse("partial marker fragment must be dropped", body.contains("[tool:foo"))
        assertFalse("no marker opener may survive", body.contains("[tool:"))
        // Only the prose head survives; the separator newline in front of the
        // marker is kept (same shape as `cappedHistoryText_neverEndsInsideToolMarker`).
        assertEquals("head\n", body)
    }

    /** F3, result-marker shape: a quoted `]` inside the result payload. */
    @Test fun cappedHistoryText_quotedBracketInsideResultArgsDoesNotKeepPartialMarker() {
        val text = "head\n[tool-result:lsp.symbols:7:{\"a\":\"]" + "b".repeat(30_000)
        val capped = cappedHistoryText(text)
        val body = capped.removeSuffix(HISTORY_TRUNCATION_MARKER)
        assertFalse("partial result marker fragment must be dropped", body.contains("[tool-result:"))
        assertFalse("no marker opener may survive", body.contains("[tool:"))
        // Only the prose head survives (the separator newline is kept).
        assertEquals("head\n", body)
    }

    /**
     * The compaction rewrites a result payload to `…`, so the declared length
     * no longer matches the shortened row. The marker-aware cut must still
     * see the compacted marker as COMPLETE — otherwise it drops the marker
     * (and the answer behind it) from an otherwise intact row.
     */
    @Test fun cappedHistoryText_keepsCompactedResultMarkerWithDeclaredLength() {
        val json = "x".repeat(20_000)
        val text = "answer prefix\n[tool-result:lsp.symbols:${json.length}:$json]\nFINAL ANSWER"
        val capped = cappedHistoryText(text)
        assertTrue(
            "compacted result marker must survive",
            capped.contains("[tool-result:lsp.symbols:${json.length}:\u2026]"),
        )
        assertTrue("answer behind the marker must survive", capped.contains("FINAL ANSWER"))
        assertFalse("the payload echo must be dropped", capped.contains("x".repeat(32)))
    }

    /**
     * F3/P3-5: compaction alone brought the row under the cap and the
     * marker-aware cut only trimmed the trailing partial opener. No cap cut
     * happened, so no "history truncated" suffix may be added — the compacted
     * marker is the visible record of the dropped args.
     */
    @Test fun cappedHistoryText_compactionOnlyTrimAddsNoTruncationSuffix() {
        val text = "head\n[tool:big " + "a".repeat(30_000) + "]\n[tool:note " + "z".repeat(50)
        val capped = cappedHistoryText(text)
        assertTrue("compacted marker must survive", capped.contains("[tool:big \u2026]"))
        assertFalse("trailing partial opener must be gone", capped.contains("[tool:note"))
        assertFalse(
            "no cap cut happened: no truncation suffix",
            capped.endsWith(HISTORY_TRUNCATION_MARKER),
        )
    }

    @Test fun compactToolMarkers_lenExceedsRemainingByOneNotRewritten() {
        var found: String? = null
        for (L in 1..200) {
            val m = "\n[tool-result:lsp.symbols:$L:abc]\nANSWER"
            val jsonStart = m.indexOf("abc")
            val remaining = m.length - jsonStart
            if (L == remaining + 1) { found = m; break }
        }
        requireNotNull(found) { "no boundary marker found" }
        assertEquals(found, compactToolMarkers(found))
        assertTrue(compactToolMarkers(found).contains("ANSWER"))
        assertFalse(compactToolMarkers(found).contains("\u2026]"))
    }

}
