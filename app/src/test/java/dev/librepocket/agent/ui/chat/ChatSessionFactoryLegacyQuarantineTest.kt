package dev.librepocket.agent.ui.chat

import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.SessionTranscriptSink
import dev.librepocket.session.TranscriptEvent
import java.util.Collections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class QuarantineFakeProvider(
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
 * Phase 4 (P1-1/P1-2) fail-closed regressions.
 *
 * - Legacy (no `@origin`) ids predate R4-1 fork isolation and may already mix
 *   two recipients' rows under one id (rows carry no per-row origin). Reopen
 *   must quarantine via fork (new id, `historyWithheld=true`), never hydrate
 *   the whole batch by header alone.
 * - `isLegacyProvenance` pins the header shape gate.
 * - Legacy two-arg `onTurnFailed` pre-sanitizes with `redactError` (P2 parity).
 */
class ChatSessionFactoryLegacyQuarantineTest {

    private fun endpointA() = EndpointConfig(
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
        provider: QuarantineFakeProvider,
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

    private fun event(sid: String, runId: String, kind: String, text: String) = TranscriptEvent(
        sessionId = sid,
        runId = runId,
        kind = kind,
        text = text,
        createdAt = 1L,
    )

    @Test fun isLegacyProvenance_gate() {
        assertTrue(isLegacyProvenance("preset:openai/gpt-4o-mini"))
        assertTrue(isLegacyProvenance("gpt-4o-mini"))
        assertTrue(isLegacyProvenance("m"))
        assertTrue(isLegacyProvenance(""))
        assertTrue(isLegacyProvenance("imported/unknown"))
        assertFalse(isLegacyProvenance("preset:openai@https://api.openai.com/gpt-4o-mini"))
        assertFalse(isLegacyProvenance("preset:custom@http://127.0.0.1:11434/local-model"))
    }

    @Test fun isSameProviderOrigin_legacyAlwaysFalse() {
        val url = "https://api.openai.com/v1"
        // Phase 4: even the canonical built-in old format never hydrates.
        assertFalse(isSameProviderOrigin("preset:openai/gpt-4o-mini", "preset:openai", url))
        assertFalse(isSameProviderOrigin("preset:openai/other-model", "preset:openai", url))
    }

    /**
     * Mixed-ledger quarantine: same id holds A rows plus simulated B rows
     * (exfiltrated text), header is legacy A (no `@origin`, pre-R4-1 shape).
     * Reopen with A must NOT forward B rows to A: it forks (new id,
     * withheld) and the first request carries only the new turn.
     */
    @Test fun open_legacyMixedLedger_forksAndNeverForwardsBToA() = runBlocking {
        val transcripts = FakeSessionStore()
        val oldId = transcripts.createSession("old chat", "preset:openai/gpt-4o-mini")
        transcripts.appendEvent(event(oldId, "rA", "user", "QA-secret-A"))
        transcripts.appendEvent(event(oldId, "rA", "assistant", "AA-secret-A"))
        // Simulated B-recipient rows already mixed under the same id.
        transcripts.appendEvent(event(oldId, "rB", "user", "QB-secret-B"))
        transcripts.appendEvent(event(oldId, "rB", "assistant", "AB-secret-B"))

        val provider = QuarantineFakeProvider {
            flow {
                emit(StreamEvent.TextDelta(0, 0, "fresh"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val opened = factoryWith(transcripts, provider).open(endpointA(), oldId)
        try {
            val newId = checkNotNull(opened.sessionId)
            assertNotEquals("legacy mixed id must fork, not reuse", oldId, newId)
            assertTrue(opened.historyWithheld)
            opened.session.send("QNEW")
            val req = provider.seenRequests.single()
            assertEquals(listOf("user"), req.messages.map { it.role })
            assertTrue(req.messages.single().text.startsWith("QNEW"))
            assertTrue(req.messages.none { it.text.contains("QB-secret-B") || it.text.contains("AB-secret-B") })
            assertTrue(req.messages.none { it.text.contains("QA-secret-A") || it.text.contains("AA-secret-A") })
            // Old archive untouched (read-only quarantine).
            val oldRows = transcripts.events.filter { it.sessionId == oldId }
            assertEquals(4, oldRows.size)
            // New ledger holds only the withheld marker + new turn (never a copy).
            val newRows = transcripts.events.filter { it.sessionId == newId }
            assertTrue(newRows.any { it.kind == "system" && modelSwitchMarkerWithheld(it.text) })
            assertTrue(newRows.none { it.text.contains("secret-B") || it.text.contains("secret-A") })
        } finally {
            opened.session.close()
        }
    }

    /** Control: R4-1+ `@origin` ledger with the same rows still hydrates. */
    @Test fun open_newFormatSameOrigin_stillHydrates() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.appendEvent(event(sid, "r1", "user", "q1"))
        transcripts.appendEvent(event(sid, "r1", "assistant", "a1"))

        val provider = QuarantineFakeProvider {
            flow {
                emit(StreamEvent.TextDelta(0, 0, "fresh"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val opened = factoryWith(transcripts, provider).open(endpointA(), sid)
        try {
            assertEquals(sid, opened.sessionId)
            assertFalse(opened.historyWithheld)
            opened.session.send("q-new")
            val req = provider.seenRequests.single()
            assertEquals(listOf("user", "assistant", "user"), req.messages.map { it.role })
            assertEquals("q1", req.messages[0].text)
        } finally {
            opened.session.close()
        }
    }

    /** P2: legacy failure reason uses stricter `redactError` (URL token stripped). */
    @Test fun legacyOnTurnFailed_usesRedactErrorParity() = runBlocking {
        val store = FakeSessionStore()
        val sid = store.createSession("t", "m")
        val sink = SessionTranscriptSink(store, sid) { 42L }
        val raw = "down https://example.com/x?api_key=example-value tail"
        sink.onTurnFailed("r1", raw)
        val row = store.events.single()
        assertEquals("system", row.kind)
        assertTrue(row.text.startsWith("turn r1 failed: "))
        assertFalse("raw token value must not persist", row.text.contains("example-value"))
        assertFalse("raw query must not persist", row.text.contains("api_key=example-value"))
        assertTrue("URL token param must be redacted", row.text.contains("api_key=⟦REDACTED⟧"))
    }
}
