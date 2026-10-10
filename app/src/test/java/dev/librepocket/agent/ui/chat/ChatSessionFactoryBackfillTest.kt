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
import java.util.Collections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class BackfillFakeProvider(
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
 * Phase 3 resume-recovery regressions (all fake data).
 *
 * - [findDanglingRunIds] marks only user rows with no terminal record;
 *   completed, cancelled-partial, failed, legacy-failed, and already-marked
 *   runs are never re-marked.
 * - `ChatSessionFactory.open` backfills a kill-stranded turn with a `system`
 *   INTERRUPTED marker (replay-compatible: never a user/assistant row),
 *   idempotently across re-opens.
 * - The reopened live session hydrates restored history into its first
 *   request (F8), excluding partial rows from model context.
 */
class ChatSessionFactoryBackfillTest {

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
        provider: BackfillFakeProvider,
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
        failureReason: String? = null,
        parentRunId: String? = null,
        attemptIndex: Int? = null,
    ) = TranscriptEvent(
        sessionId = sid,
        runId = runId,
        kind = kind,
        text = text,
        createdAt = 1L,
        isPartial = isPartial,
        failureReason = failureReason,
        parentRunId = parentRunId,
        attemptIndex = attemptIndex,
    )

    @Test fun danglingRule_marksOnlyUserRowsWithoutTerminal() {
        val sid = "s"
        val events = listOf(
            event(sid, "completed", "user", "q"),
            event(sid, "completed", "assistant", "a"),
            event(sid, "killed", "user", "q?"),
            event(sid, "cancelled", "user", "stop"),
            event(sid, "cancelled", "assistant", "half", isPartial = true),
            event(sid, "failed", "user", "go"),
            event(sid, "failed", "assistant", "frag", isPartial = true, failureReason = "HTTP 500"),
            event(sid, "legacy", "user", "old"),
            event(sid, "legacy", "system", "turn legacy failed: boom"),
            event(sid, "marked", "user", "again?"),
            event(sid, "marked", "system", "turn marked interrupted"),
            event(sid, "usage-only", "user", "hmm"),
            event(sid, "usage-only", "system", "usage input=1 output=2"),
        )
        assertEquals(listOf("killed", "usage-only"), findDanglingRunIds(events))
    }

    @Test fun danglingRule_emptyWhenNothingStranded() {
        assertTrue(findDanglingRunIds(emptyList()).isEmpty())
    }

    @Test fun danglingRule_stageCFamilyRetrySuccessNotDangling() {
        val sid = "s"
        // Retry→success ledger: user(L) + retried partial (runId L, first
        // attempt reuses the logical id) + retry(L) + success (A2 parent L).
        val events = listOf(
            event(sid, "L", "user", "q"),
            event(sid, "L", "assistant", "half", isPartial = true, failureReason = "boom", attemptIndex = 0),
            event(sid, "L", "retry", "attempt 1/1 after 0ms"),
            event(sid, "A2", "assistant", "ok", parentRunId = "L", attemptIndex = 1),
        )
        assertTrue(findDanglingRunIds(events).isEmpty())
    }

    @Test fun danglingRule_stageCFamilyRetryFailureNotDangling() {
        val sid = "s"
        val events = listOf(
            event(sid, "L", "user", "q"),
            event(sid, "L", "assistant", "half", isPartial = true, failureReason = "boom", attemptIndex = 0),
            event(sid, "L", "retry", "attempt 1/1 after 0ms"),
            event(sid, "A2", "assistant", "frag", isPartial = true, failureReason = "boom", parentRunId = "L", attemptIndex = 1),
        )
        assertTrue(findDanglingRunIds(events).isEmpty())
    }

    @Test fun danglingRule_stageCRetryAloneNeverCompletesFamily() {
        val sid = "s"
        // A retry notice without any assistant row must still dangle: retry
        // itself is never terminal (covers a kill between the notice and the
        // next attempt's terminal).
        val events = listOf(
            event(sid, "L", "user", "q"),
            event(sid, "L", "retry", "attempt 1/3 after 2000ms"),
        )
        assertEquals(listOf("L"), findDanglingRunIds(events))
    }

    private fun interruptedMarkers(store: FakeSessionStore) =
        store.events.filter { it.kind == INTERRUPTED_MARKER_KIND && it.text.endsWith("interrupted") }

    @Test fun open_stageCRetrySuccessGainsNoMarkerAcrossTwoReopens() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "m")
        transcripts.appendEvent(event(sid, "L", "user", "q"))
        transcripts.appendEvent(
            event(sid, "L", "assistant", "half", isPartial = true, failureReason = "boom", attemptIndex = 0),
        )
        transcripts.appendEvent(event(sid, "L", "retry", "attempt 1/1 after 0ms"))
        transcripts.appendEvent(event(sid, "A2", "assistant", "ok", parentRunId = "L", attemptIndex = 1))

        val factory = factoryWith(transcripts, BackfillFakeProvider {
            flow { emit(StreamEvent.Done("stop")) }
        })
        val first = factory.open(sampleEndpoint(), sid)
        first.session.close()
        val second = factory.open(sampleEndpoint(), sid)
        try {
            assertTrue(
                "retry-success family must gain zero INTERRUPTED markers across two reopens, " +
                    "found: ${interruptedMarkers(transcripts)}",
                interruptedMarkers(transcripts).isEmpty(),
            )
            // The intermediate half is still in the transcript.
            assertTrue(transcripts.events.any { it.text == "half" && it.isPartial })
            assertTrue(transcripts.events.any { it.text == "ok" && !it.isPartial })
        } finally {
            second.session.close()
        }
    }

    @Test fun open_stageCRetryFailureGainsNoMarker() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "m")
        transcripts.appendEvent(event(sid, "L", "user", "q"))
        transcripts.appendEvent(
            event(sid, "L", "assistant", "half", isPartial = true, failureReason = "boom", attemptIndex = 0),
        )
        transcripts.appendEvent(event(sid, "L", "retry", "attempt 1/1 after 0ms"))
        transcripts.appendEvent(
            event(
                sid, "A2", "assistant", "frag", isPartial = true, failureReason = "boom",
                parentRunId = "L", attemptIndex = 1,
            ),
        )

        val factory = factoryWith(transcripts, BackfillFakeProvider {
            flow { emit(StreamEvent.Done("stop")) }
        })
        val opened = factory.open(sampleEndpoint(), sid)
        try {
            assertTrue(
                "retry-failure family must gain zero INTERRUPTED markers, " +
                    "found: ${interruptedMarkers(transcripts)}",
                interruptedMarkers(transcripts).isEmpty(),
            )
        } finally {
            opened.session.close()
        }
    }

    @Test fun open_backfillsKillStrandedTurnWithReplayCompatibleMarker() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "m")
        transcripts.appendEvent(event(sid, "done", "user", "q1"))
        transcripts.appendEvent(event(sid, "done", "assistant", "a1"))
        // Simulate a kill: user row acked, process died before any terminal row.
        transcripts.appendEvent(event(sid, "stranded", "user", "q2"))

        val provider = BackfillFakeProvider {
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val opened = factoryWith(transcripts, provider).open(sampleEndpoint(), sid)
        try {
            val markers = transcripts.events.filter { it.kind == INTERRUPTED_MARKER_KIND }
            assertEquals(1, markers.size)
            assertEquals("stranded", markers.single().runId)
            assertEquals("turn stranded interrupted", markers.single().text)
            // Completed turns are never marked; markers are never chat rows.
            assertTrue(markers.none { it.kind == "user" || it.kind == "assistant" })
            assertEquals(1, transcripts.events.count { it.runId == "stranded" && it.kind == "user" })
        } finally {
            opened.session.close()
        }
    }

    @Test fun open_backfillIsIdempotentAcrossReopens() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "m")
        transcripts.appendEvent(event(sid, "stranded", "user", "q2"))

        val factory = factoryWith(transcripts, BackfillFakeProvider {
            flow { emit(StreamEvent.Done("stop")) }
        })
        val first = factory.open(sampleEndpoint(), sid)
        first.session.close()
        val second = factory.open(sampleEndpoint(), sid)
        try {
            val markers = transcripts.events.filter {
                it.kind == INTERRUPTED_MARKER_KIND && it.runId == "stranded"
            }
            assertEquals("re-open must not duplicate the marker", 1, markers.size)
        } finally {
            second.session.close()
        }
    }

    @Test fun open_firstResumeRequestSeesRestoredHistoryWithoutPartials() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "m")
        transcripts.appendEvent(event(sid, "r1", "user", "q1"))
        transcripts.appendEvent(event(sid, "r1", "assistant", "a1"))
        // A cancelled partial replays in the UI flagged, but must not read as
        // a completed answer in model context.
        transcripts.appendEvent(event(sid, "r2", "user", "q2"))
        transcripts.appendEvent(event(sid, "r2", "assistant", "half", isPartial = true))

        val provider = BackfillFakeProvider {
            flow {
                emit(StreamEvent.TextDelta(0, 0, "fresh"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val opened = factoryWith(transcripts, provider).open(sampleEndpoint(), sid)
        try {
            opened.session.send("q3")
            val req = provider.seenRequests.single()
            val roles = req.messages.map { it.role }
            assertEquals(listOf("user", "assistant", "user", "user"), roles)
            assertEquals("q1", req.messages[0].text)
            assertEquals("a1", req.messages[1].text)
            // r2 contributes its user row; its partial assistant row is excluded.
            assertEquals("q2", req.messages[2].text)
            assertTrue(req.messages[3].text.startsWith("q3"))
        } finally {
            opened.session.close()
        }
    }
}
