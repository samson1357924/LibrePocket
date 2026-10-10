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
        val sid = transcripts.createSession("old chat", "preset:openai/gpt-4o-mini")
        seedPairs(transcripts, sid, 1001)

        val provider = TailFakeProvider {
            flow {
                emit(StreamEvent.TextDelta(0, 0, "fresh"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val opened = factoryWith(transcripts, provider).open(sampleEndpoint(), sid)
        try {
            // 2002 qualifying rows, cap 2000: oldest pair (u0,a0) omitted.
            assertEquals(2, opened.historyDroppedCount)
            opened.session.send("q-new")
            val req = provider.seenRequests.single()
            // 2000 restored + the new turn.
            assertEquals(2001, req.messages.size)
            assertEquals("u1", req.messages.first().text)
            assertEquals("a1000", req.messages[2000 - 1].text)
            assertTrue(req.messages.last().text.startsWith("q-new"))
            assertTrue(req.messages.none { it.text == "u0" || it.text == "a0" })
        } finally {
            opened.session.close()
        }
    }

    @Test fun open_noTruncationReportsZeroDropped() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "preset:openai/gpt-4o-mini")
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
        val sid = transcripts.createSession("old chat", "preset:openai/gpt-4o-mini")
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
}
