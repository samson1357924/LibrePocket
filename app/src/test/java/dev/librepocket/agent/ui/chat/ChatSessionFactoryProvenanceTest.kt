package dev.librepocket.agent.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.chat.ChatStatus
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.TranscriptEvent
import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class ProvenanceFakeProvider(
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
 * Stage G (R2-3 [P1/privacy]) resume-provenance regressions (all fake data).
 *
 * - `create` persists provenance as `"${providerId}@${origin}/${modelId}"` so
 *   `open` can attribute stored history to a specific provider and web origin.
 * - `open` with matching provider and origin hydrates the restored prefix into
 *   the first request (same provider + same origin + different model still
 *   hydrates: recipient/credential unchanged).
 * - `open` with different providerId, different host/origin, or malformed origin
 *   withholds the prefix: the first request carries only the new user message,
 *   and [CreatedSession.historyWithheld] is true so the ViewModel shows a visible
 *   notice.
 * - Legacy rows without provenance (bare model id, no `/`, including blank)
 *   fail closed (withhold for privacy).
 */
class ChatSessionFactoryProvenanceTest {

    private fun openaiEndpoint() = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "gpt-4o-mini",
        apiKeyRef = "provider_key/preset:openai",
    )

    private fun customEndpoint(baseUrl: String) = EndpointConfig(
        providerId = "preset:custom",
        presetId = "custom",
        label = "Custom",
        baseUrl = baseUrl,
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "local-model",
        apiKeyRef = "provider_key/preset:custom",
    )

    private fun factoryWith(
        transcripts: FakeSessionStore,
        provider: ProvenanceFakeProvider,
    ) = ChatSessionFactory(
        policy = InMemoryPolicyStore(),
        vaultSource = VaultSource {
            val vault = EncryptedPrefsVault(InMemoryPrefs())
            vault.putKey("preset:openai", "test-key-12345678".toCharArray())
            vault.putKey("preset:custom", "test-key-12345678".toCharArray())
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
    ) = TranscriptEvent(
        sessionId = sid,
        runId = runId,
        kind = kind,
        text = text,
        createdAt = 1L,
    )

    @Test fun storedProviderOf_parsesFirstSegmentOnly() {
        assertEquals("preset:openai", storedProviderOf("preset:openai/gpt-4o-mini"))
        assertEquals("preset:openai", storedProviderOf("preset:openai@https://api.openai.com/gpt-4o-mini"))
        // Model ids may themselves contain "/"; the provider is still the
        // first segment (providerIds never contain "/").
        assertEquals("preset:openrouter", storedProviderOf("preset:openrouter/openrouter/auto"))
        assertEquals("preset:openrouter", storedProviderOf("preset:openrouter@https://openrouter.ai/openrouter/auto"))
        assertEquals(null, storedProviderOf("gpt-4o-mini"))
        assertEquals(null, storedProviderOf(""))
        assertEquals("", storedProviderOf("/gpt-4o-mini"))
        assertEquals("", storedProviderOf("@https://api.openai.com/gpt-4o-mini"))
    }

    @Test fun storedOriginOf_extractsOrigin() {
        assertEquals("https://api.openai.com", storedOriginOf("preset:openai@https://api.openai.com/gpt-4o-mini"))
        assertEquals("http://127.0.0.1:11434", storedOriginOf("preset:custom@http://127.0.0.1:11434/local-model"))
        assertEquals(null, storedOriginOf("preset:openai/gpt-4o-mini"))
        assertEquals(null, storedOriginOf("gpt-4o-mini"))
        assertEquals(null, storedOriginOf(""))
        assertEquals(null, storedOriginOf("/gpt-4o-mini"))
        assertEquals("", storedOriginOf("preset:openai@/gpt-4o-mini"))
    }

    @Test fun isSameProviderOrigin_gateMatrix() {
        val openaiUrl = "https://api.openai.com/v1"

        // New format with @origin
        assertTrue(isSameProviderOrigin("preset:openai@https://api.openai.com/gpt-4o-mini", "preset:openai", openaiUrl))
        assertTrue(isSameProviderOrigin("preset:openai@https://api.openai.com/other-model", "preset:openai", openaiUrl))
        assertFalse(isSameProviderOrigin("preset:openai@https://api.openai.com/gpt-4o-mini", "preset:deepseek", openaiUrl))
        assertFalse(isSameProviderOrigin("preset:openai@https://api.openai.com/gpt-4o-mini", "preset:openai", "https://proxy.example.com/v1"))

        // Custom endpoints: same host true, different host false
        assertTrue(isSameProviderOrigin("preset:custom@http://127.0.0.1:11434/local-model", "preset:custom", "http://127.0.0.1:11434/v1"))
        assertFalse(isSameProviderOrigin("preset:custom@http://127.0.0.1:11434/local-model", "preset:custom", "http://127.0.0.1:8080/v1"))
        assertFalse(isSameProviderOrigin("preset:custom@http://host-a:8080/local-model", "preset:custom", "http://host-b:8080/v1"))

        // Old format without @origin: built-in preset with canonical origin succeeds; custom fails closed
        assertTrue(isSameProviderOrigin("preset:openai/gpt-4o-mini", "preset:openai", openaiUrl))
        assertTrue(isSameProviderOrigin("preset:openai/other-model", "preset:openai", openaiUrl))
        assertFalse(isSameProviderOrigin("preset:openai/gpt-4o-mini", "preset:openai", "https://proxy.example.com/v1"))
        assertFalse(isSameProviderOrigin("preset:custom/local-model", "preset:custom", "http://127.0.0.1:11434/v1"))
        assertFalse(isSameProviderOrigin("preset:deepseek/deepseek-chat", "preset:openai", openaiUrl))

        // Legacy bare model id (no "/"): fail-closed (all false)
        assertFalse(isSameProviderOrigin("gpt-4o-mini", "preset:openai", openaiUrl))
        assertFalse(isSameProviderOrigin("m", "preset:openai", openaiUrl))
        assertFalse(isSameProviderOrigin("", "preset:openai", openaiUrl))

        // Malformed / foreign / imported: fail-closed (all false)
        assertFalse(isSameProviderOrigin("/gpt-4o-mini", "preset:openai", openaiUrl))
        assertFalse(isSameProviderOrigin("imported/unknown", "preset:openai", openaiUrl))
    }

    @Test fun create_writesQualifiedProvenanceModel() = runBlocking {
        val transcripts = FakeSessionStore()
        val factory = factoryWith(transcripts, ProvenanceFakeProvider())
        val created = factory.create(openaiEndpoint(), "hello world chat title here")
        try {
            val sid = checkNotNull(created.sessionId)
            assertEquals("preset:openai@https://api.openai.com/gpt-4o-mini", transcripts.metas[sid]!!.model)
            assertTrue(transcripts.metas[sid]!!.model.contains("@https://api.openai.com/"))
            assertFalse(created.historyWithheld)
        } finally {
            created.session.close()
            factory.discardUnattached(created, deleteTranscriptRow = true)
        }
    }

    @Test fun open_sameProviderHydratesFirstRequest() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "preset:openai/gpt-4o-mini")
        transcripts.appendEvent(event(sid, "r1", "user", "q1"))
        transcripts.appendEvent(event(sid, "r1", "assistant", "a1"))

        val provider = ProvenanceFakeProvider {
            flow {
                emit(StreamEvent.TextDelta(0, 0, "fresh"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val opened = factoryWith(transcripts, provider).open(openaiEndpoint(), sid)
        try {
            assertFalse(opened.historyWithheld)
            opened.session.send("q3")
            val req = provider.seenRequests.single()
            assertEquals(listOf("user", "assistant", "user"), req.messages.map { it.role })
            assertEquals("q1", req.messages[0].text)
            assertEquals("a1", req.messages[1].text)
            assertTrue(req.messages[2].text.startsWith("q3"))
        } finally {
            opened.session.close()
        }
    }

    @Test fun open_differentProviderWithholdsHistoryWithEmptyContext() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "preset:deepseek/deepseek-chat")
        transcripts.appendEvent(event(sid, "r1", "user", "q1"))
        transcripts.appendEvent(event(sid, "r1", "assistant", "a1"))

        val provider = ProvenanceFakeProvider {
            flow {
                emit(StreamEvent.TextDelta(0, 0, "fresh"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val opened = factoryWith(transcripts, provider).open(openaiEndpoint(), sid)
        try {
            assertTrue(opened.historyWithheld)
            opened.session.send("q3")
            val req = provider.seenRequests.single()
            // Fail closed: only the new user message reaches the new provider.
            assertEquals(listOf("user"), req.messages.map { it.role })
            assertTrue(req.messages.single().text.startsWith("q3"))
            assertTrue(req.messages.none { it.text.contains("q1") || it.text.contains("a1") })
        } finally {
            opened.session.close()
        }
    }

    @Test fun open_sameProviderDifferentModelStillHydrates() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "preset:openai/gpt-4o")
        transcripts.appendEvent(event(sid, "r1", "user", "q1"))
        transcripts.appendEvent(event(sid, "r1", "assistant", "a1"))

        val provider = ProvenanceFakeProvider {
            flow {
                emit(StreamEvent.TextDelta(0, 0, "fresh"))
                emit(StreamEvent.Done("stop"))
            }
        }
        // Same provider, different model: recipient/credential unchanged.
        val opened = factoryWith(transcripts, provider).open(openaiEndpoint(), sid)
        try {
            assertFalse(opened.historyWithheld)
            opened.session.send("q3")
            val req = provider.seenRequests.single()
            assertEquals(listOf("user", "assistant", "user"), req.messages.map { it.role })
            assertEquals("q1", req.messages[0].text)
        } finally {
            opened.session.close()
        }
    }

    @Test fun open_legacyBareModelWithholdsForPrivacy() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "m")
        transcripts.appendEvent(event(sid, "r1", "user", "q1"))
        transcripts.appendEvent(event(sid, "r1", "assistant", "a1"))

        val provider = ProvenanceFakeProvider {
            flow {
                emit(StreamEvent.TextDelta(0, 0, "fresh"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val opened = factoryWith(transcripts, provider).open(openaiEndpoint(), sid)
        try {
            assertTrue(opened.historyWithheld)
            opened.session.send("q3")
            val req = provider.seenRequests.single()
            assertEquals(listOf("user"), req.messages.map { it.role })
            assertTrue(req.messages.single().text.startsWith("q3"))
            assertTrue(req.messages.none { it.text.contains("q1") || it.text.contains("a1") })
        } finally {
            opened.session.close()
        }
    }

    @Test fun open_sameProviderDifferentBaseUrlWithholdsModelContext() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "preset:custom@http://127.0.0.1:11434/local-model")
        transcripts.appendEvent(event(sid, "r1", "user", "q1"))
        transcripts.appendEvent(event(sid, "r1", "assistant", "a1"))

        val provider = ProvenanceFakeProvider {
            flow {
                emit(StreamEvent.TextDelta(0, 0, "fresh"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val opened = factoryWith(transcripts, provider).open(customEndpoint("http://127.0.0.1:11435/v1"), sid)
        try {
            assertTrue(opened.historyWithheld)
            opened.session.send("q2")
            val req = provider.seenRequests.single()
            assertEquals(listOf("user"), req.messages.map { it.role })
            assertTrue(req.messages.single().text.startsWith("q2"))
            assertTrue(req.messages.none { it.text.contains("q1") || it.text.contains("a1") })
        } finally {
            opened.session.close()
        }
    }

    @Test fun open_sameProviderSameBaseUrlHydratesFirstRequest() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "preset:custom@http://127.0.0.1:11434/local-model")
        transcripts.appendEvent(event(sid, "r1", "user", "q1"))
        transcripts.appendEvent(event(sid, "r1", "assistant", "a1"))

        val provider = ProvenanceFakeProvider {
            flow {
                emit(StreamEvent.TextDelta(0, 0, "fresh"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val opened = factoryWith(transcripts, provider).open(customEndpoint("http://127.0.0.1:11434/v1"), sid)
        try {
            assertFalse(opened.historyWithheld)
            opened.session.send("q2")
            val req = provider.seenRequests.single()
            assertEquals(listOf("user", "assistant", "user"), req.messages.map { it.role })
            assertEquals("q1", req.messages[0].text)
            assertEquals("a1", req.messages[1].text)
            assertTrue(req.messages[2].text.startsWith("q2"))
        } finally {
            opened.session.close()
        }
    }

    @Test fun open_importedUnknownWithholdsModelContext() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "imported/unknown")
        transcripts.appendEvent(event(sid, "r1", "user", "q1"))
        transcripts.appendEvent(event(sid, "r1", "assistant", "a1"))

        val provider = ProvenanceFakeProvider {
            flow {
                emit(StreamEvent.TextDelta(0, 0, "fresh"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val opened = factoryWith(transcripts, provider).open(openaiEndpoint(), sid)
        try {
            assertTrue(opened.historyWithheld)
            opened.session.send("q2")
            val req = provider.seenRequests.single()
            assertEquals(listOf("user"), req.messages.map { it.role })
            assertTrue(req.messages.single().text.startsWith("q2"))
            assertTrue(req.messages.none { it.text.contains("q1") || it.text.contains("a1") })
        } finally {
            opened.session.close()
        }
    }

    @Test fun open_malformedProviderSegmentWithholds() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "/gpt-4o-mini")
        transcripts.appendEvent(event(sid, "r1", "user", "q1"))
        transcripts.appendEvent(event(sid, "r1", "assistant", "a1"))

        val provider = ProvenanceFakeProvider {
            flow {
                emit(StreamEvent.TextDelta(0, 0, "fresh"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val opened = factoryWith(transcripts, provider).open(openaiEndpoint(), sid)
        try {
            assertTrue(opened.historyWithheld)
            opened.session.send("q3")
            val req = provider.seenRequests.single()
            assertEquals(listOf("user"), req.messages.map { it.role })
        } finally {
            opened.session.close()
        }
    }

    @Test fun crossProviderNotice_hasUserVisibleText() {
        val text = chatNoticeText("RESUME_CROSS_PROVIDER_HISTORY_WITHHELD")
        assertTrue(text != "RESUME_CROSS_PROVIDER_HISTORY_WITHHELD")
        assertEquals(
            "先前對話屬於其他 provider 或來源不明，為保護隱私不會自動傳給目前 provider；舊紀錄仍顯示於畫面，新對話從空白上下文開始。",
            text,
        )
    }

    // --- ViewModel wiring: the withheld flag surfaces as a visible notice. ---

    private val tmpDirs = ArrayList<java.io.File>()
    private val scopes = ArrayList<CoroutineScope>()
    private val chatViewModels = ChatTestViewModelStore()
    private val chatMain = ChatTestMainDispatcher()

    @Before
    fun setUpMain() {
        chatMain.install()
    }

    @After
    fun tearDownMain() {
        try {
            chatMain.clearViewModels(chatViewModels)
        } finally {
            try {
                cancelAndJoinChatTestScopes(scopes)
            } finally {
                try {
                    tmpDirs.forEach { it.deleteRecursively() }
                } finally {
                    chatMain.resetAndClose()
                }
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        runBlocking { chatMain.run(block) }
    }

    private fun newStore(): EndpointStore {
        val dir = Files.createTempDirectory("chat-prov-test").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        return EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "chat.preferences_pb") },
            ),
        )
    }

    private fun awaitTrue(timeoutMs: Long = 8000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) error("timed out waiting for condition")
            Thread.sleep(10)
        }
    }

    @Test
    fun openSessionAcrossProviders_replaysLocallyNoticesAndWithholdsModelContext() {
        val store = newStore()
        runBlocking { store.save(openaiEndpoint()) }
        val transcripts = FakeSessionStore()
        val sid = runBlocking {
            val id = transcripts.createSession("old chat", "preset:deepseek/deepseek-chat")
            transcripts.appendEvent(event(id, "r1", "user", "q1"))
            transcripts.appendEvent(event(id, "r1", "assistant", "a1"))
            id
        }
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking { vault.putKey("preset:openai", "sk-test-key-123".toCharArray()) }
        val fake = ProvenanceFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = transcripts
            },
        )
        val vm = chatViewModels.own(ChatViewModel(store, factory, InMemoryPolicyStore()))
        onMain { vm.openSession(sid) }
        // Local replay still shows the old rows; the visible notice explains
        // the withheld model context.
        awaitTrue { vm.messages.value.size == 2 }
        assertEquals(listOf("q1", "a1"), vm.messages.value.map { it.text })
        awaitTrue { vm.notice.value == "RESUME_CROSS_PROVIDER_HISTORY_WITHHELD" }
        onMain { vm.onInputChange("new") }
        onMain { vm.send() }
        awaitTrue { vm.sessionState.value.status == ChatStatus.IDLE && vm.messages.value.size == 4 }
        val req = fake.seenRequests.single()
        assertEquals(listOf("user"), req.messages.map { it.role })
        assertTrue(req.messages.single().text.startsWith("new"))
    }
}
