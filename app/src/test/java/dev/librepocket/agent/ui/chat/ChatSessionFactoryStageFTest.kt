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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class StageFFakeProvider(
    var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        emitAll(handler(request))
    }
    override suspend fun listModels(): List<String> = emptyList()
}

/**
 * Stage F R2-1: only a final assistant row (or a system cancel/interrupted
 * terminal) closes a logical family. A lone non-final retried partial —
 * whether the kill lands during the backoff sleeper (partial + retry, no
 * successor) or between the partial write and its retry notice (partial, no
 * retry) — still dangles and gains exactly one INTERRUPTED marker across two
 * reopens. A retry→success family owns a final row and gains none.
 */
class ChatSessionFactoryStageFTest {

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
        provider: StageFFakeProvider,
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
        isFinal: Boolean = true,
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
        isFinal = isFinal,
    )

    private fun interruptedMarkers(store: FakeSessionStore) =
        store.events.filter { it.kind == INTERRUPTED_MARKER_KIND && it.text.endsWith("interrupted") }

    @Test fun danglingRule_nonFinalPartialPlusRetryStillDangles() {
        val sid = "s"
        val events = listOf(
            event(sid, "L", "user", "q"),
            event(sid, "L", "assistant", "half", isPartial = true, failureReason = "boom", attemptIndex = 0, isFinal = false),
            event(sid, "L", "retry", "attempt 1/3 after 2000ms"),
        )
        assertEquals(listOf("L"), findDanglingRunIds(events))
    }

    @Test fun danglingRule_nonFinalPartialWithoutRetryStillDangles() {
        val sid = "s"
        // Kill between the partial write and its retry notice: no retry row yet.
        val events = listOf(
            event(sid, "L", "user", "q"),
            event(sid, "L", "assistant", "half", isPartial = true, failureReason = "boom", attemptIndex = 0, isFinal = false),
        )
        assertEquals(listOf("L"), findDanglingRunIds(events))
    }

    @Test fun danglingRule_finalSuccessClosesFamily() {
        val sid = "s"
        val events = listOf(
            event(sid, "L", "user", "q"),
            event(sid, "L", "assistant", "half", isPartial = true, failureReason = "boom", attemptIndex = 0, isFinal = false),
            event(sid, "L", "retry", "attempt 1/1 after 0ms"),
            event(sid, "A2", "assistant", "ok", parentRunId = "L", attemptIndex = 1, isFinal = true),
        )
        assertTrue(findDanglingRunIds(events).isEmpty())
    }

    @Test fun danglingRule_systemCancelMarkClosesFamily() {
        val sid = "s"
        val events = listOf(
            event(sid, "L", "user", "q"),
            event(sid, "L", "assistant", "half", isPartial = true, failureReason = "boom", attemptIndex = 0, isFinal = false),
            event(sid, "L", "retry", "attempt 1/1 after 0ms"),
            event(sid, "L", "system", "turn L cancelled"),
        )
        assertTrue(findDanglingRunIds(events).isEmpty())
    }

    @Test fun open_killDuringSleeperGainsExactlyOneMarkerAcrossTwoReopens() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "m")
        // Kill during the backoff sleeper: partial (non-final) + retry, no successor.
        transcripts.appendEvent(event(sid, "L", "user", "q"))
        transcripts.appendEvent(
            event(sid, "L", "assistant", "half", isPartial = true, failureReason = "boom", attemptIndex = 0, isFinal = false),
        )
        transcripts.appendEvent(event(sid, "L", "retry", "attempt 1/3 after 2000ms"))

        val factory = factoryWith(transcripts, StageFFakeProvider {
            flow { emit(StreamEvent.Done("stop")) }
        })
        val first = factory.open(sampleEndpoint(), sid)
        first.session.close()
        val second = factory.open(sampleEndpoint(), sid)
        try {
            val markers = interruptedMarkers(transcripts).filter { it.runId == "L" }
            assertEquals("killed sleeper family must gain exactly one marker across two reopens", 1, markers.size)
            assertEquals("turn L interrupted", markers.single().text)
        } finally {
            second.session.close()
        }
    }

    @Test fun open_killBetweenPartialAndNoticeGainsExactlyOneMarker() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "m")
        // Kill between the partial write and its retry notice: no retry row.
        transcripts.appendEvent(event(sid, "L", "user", "q"))
        transcripts.appendEvent(
            event(sid, "L", "assistant", "half", isPartial = true, failureReason = "boom", attemptIndex = 0, isFinal = false),
        )

        val factory = factoryWith(transcripts, StageFFakeProvider {
            flow { emit(StreamEvent.Done("stop")) }
        })
        val first = factory.open(sampleEndpoint(), sid)
        first.session.close()
        val second = factory.open(sampleEndpoint(), sid)
        try {
            val markers = interruptedMarkers(transcripts).filter { it.runId == "L" }
            assertEquals(1, markers.size)
        } finally {
            second.session.close()
        }
    }

    @Test fun open_retrySuccessControlGainsNoMarker() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "m")
        transcripts.appendEvent(event(sid, "L", "user", "q"))
        transcripts.appendEvent(
            event(sid, "L", "assistant", "half", isPartial = true, failureReason = "boom", attemptIndex = 0, isFinal = false),
        )
        transcripts.appendEvent(event(sid, "L", "retry", "attempt 1/1 after 0ms"))
        transcripts.appendEvent(event(sid, "A2", "assistant", "ok", parentRunId = "L", attemptIndex = 1, isFinal = true))

        val factory = factoryWith(transcripts, StageFFakeProvider {
            flow { emit(StreamEvent.Done("stop")) }
        })
        val first = factory.open(sampleEndpoint(), sid)
        first.session.close()
        val second = factory.open(sampleEndpoint(), sid)
        try {
            assertTrue(
                "retry-success family must gain zero markers, found: ${interruptedMarkers(transcripts)}",
                interruptedMarkers(transcripts).isEmpty(),
            )
        } finally {
            second.session.close()
        }
    }

    @Test fun open_cancelMarkedFamilyGainsNoInterruptedMarker() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "m")
        transcripts.appendEvent(event(sid, "L", "user", "q"))
        transcripts.appendEvent(
            event(sid, "L", "assistant", "half", isPartial = true, failureReason = "boom", attemptIndex = 0, isFinal = false),
        )
        transcripts.appendEvent(event(sid, "L", "retry", "attempt 1/1 after 0ms"))
        transcripts.appendEvent(event(sid, "L", "system", "turn L cancelled"))

        val factory = factoryWith(transcripts, StageFFakeProvider {
            flow { emit(StreamEvent.Done("stop")) }
        })
        val opened = factory.open(sampleEndpoint(), sid)
        try {
            assertTrue(
                "cancel-marked family must gain zero INTERRUPTED markers, found: ${interruptedMarkers(transcripts)}",
                interruptedMarkers(transcripts).isEmpty(),
            )
        } finally {
            opened.session.close()
        }
    }
}
