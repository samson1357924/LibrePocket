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
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeChatProvider(
    var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    var streamCalls = 0

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        streamCalls++
        emitAll(handler(request))
    }

    override suspend fun listModels(): List<String> = emptyList()
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatWiringTest {

    private val tmpDirs = ArrayList<java.io.File>()
    private val scopes = ArrayList<CoroutineScope>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        scopes.forEach { it.cancel() }
        tmpDirs.forEach { it.deleteRecursively() }
    }

    private fun newStore(): EndpointStore {
        val dir = Files.createTempDirectory("chat-wire-test").toFile()
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

    private fun sampleEndpoint() = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "gpt-4o-mini",
        apiKeyRef = "provider_key/preset:openai",
    )

    private fun newVm(
        store: EndpointStore = newStore(),
        fake: FakeChatProvider = FakeChatProvider(),
    ): ChatViewModel {
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
        )
        return ChatViewModel(store, factory)
    }

    private fun awaitTrue(timeoutMs: Long = 8000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) error("timed out waiting for condition")
            Thread.sleep(10)
        }
    }

    @Test
    fun sendStreamsReplyToIdle() {
        val store = newStore()
        val fake = FakeChatProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "hello"))
                emit(StreamEvent.Done("stop"))
            }
        }
        // Note: the fake provider ignores keys, so no vault seeding is needed.
        val vm = newVm(store, fake)
        runBlocking { store.save(sampleEndpoint()) }
        vm.onInputChange("hi")
        vm.send()
        awaitTrue { vm.sessionState.value.status == ChatStatus.IDLE && vm.sessionState.value.messages.size == 2 }
        val assistant = vm.sessionState.value.messages.first { it.role == "assistant" }
        assertEquals("hello", assistant.text)
        assertTrue(vm.sessionState.value.error == null)
    }

    @Test
    fun busySendIsIgnoredAndCancelWorks() {
        val store = newStore()
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            store.save(sampleEndpoint())
            vault.putKey("preset:openai", "sk-test-key-123".toCharArray())
        }
        val fake = FakeChatProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "partial"))
                delay(5000)
                emit(StreamEvent.Done("stop"))
            }
        }
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
        )
        val vm = ChatViewModel(store, factory)
        vm.onInputChange("first")
        vm.send()
        awaitTrue { vm.sessionState.value.status == ChatStatus.STREAMING }
        vm.onInputChange("second")
        vm.send() // busy: ignored, no crash
        Thread.sleep(50)
        assertEquals(ChatStatus.STREAMING, vm.sessionState.value.status)
        vm.cancel()
        awaitTrue { vm.sessionState.value.status == ChatStatus.CANCELLED }
        val partial = vm.sessionState.value.messages.first { it.role == "assistant" }
        assertTrue(partial.isPartial)
    }

    @Test
    fun fatalErrorSurfacesAndRetryResends() {
        val store = newStore()
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            store.save(sampleEndpoint())
            vault.putKey("preset:openai", "sk-test-key-123".toCharArray())
        }
        var failFirst = true
        val fake = FakeChatProvider { _ ->
            flow {
                if (failFirst) {
                    emit(StreamEvent.Failed("HTTP 401", false))
                } else {
                    emit(StreamEvent.TextDelta(0, 0, "recovered"))
                    emit(StreamEvent.Done("stop"))
                }
            }
        }
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
        )
        val vm = ChatViewModel(store, factory)
        vm.onInputChange("hi")
        vm.send()
        awaitTrue { vm.sessionState.value.status == ChatStatus.ERROR }
        assertTrue(vm.sessionState.value.error != null)
        assertEquals(1, fake.streamCalls)
        assertTrue(vm.canRetry)
        failFirst = false
        vm.retry()
        awaitTrue { vm.sessionState.value.status == ChatStatus.IDLE }
        assertEquals(2, fake.streamCalls)
        assertNull(vm.sessionState.value.error)
    }

    @Test
    fun factoryRejectsMalformedRef() {
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = VaultSource { EncryptedPrefsVault(InMemoryPrefs()) },
            buildProvider = { _, _ -> FakeChatProvider() },
        )
        runBlocking {
            try {
                factory.create(sampleEndpoint().copy(apiKeyRef = "sk-pasted-key-material"), "hi")
                error("expected PROVIDER_KEY_REF_MALFORMED")
            } catch (e: IllegalArgumentException) {
                assertEquals("PROVIDER_KEY_REF_MALFORMED", e.message)
            }
        }
    }

    @Test
    fun openSessionReplaysHistoryThenLiveAppends() {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = dev.librepocket.session.FakeSessionStore()
        val sid = runBlocking { transcripts.createSession("old chat", "m") }
        runBlocking {
            transcripts.appendEvent(
                dev.librepocket.session.TranscriptEvent(
                    sessionId = sid, runId = "r1", kind = "user", text = "q1", createdAt = 1L,
                ),
            )
            transcripts.appendEvent(
                dev.librepocket.session.TranscriptEvent(
                    sessionId = sid, runId = "r1", kind = "assistant", text = "a1", createdAt = 2L,
                ),
            )
            // Non-rendered kinds stay in the store but hide from replay.
            transcripts.appendEvent(
                dev.librepocket.session.TranscriptEvent(
                    sessionId = sid, runId = "r1", kind = "system", text = "usage", createdAt = 3L,
                ),
            )
        }
        val fake = FakeChatProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = VaultSource { EncryptedPrefsVault(InMemoryPrefs()) },
            buildProvider = { _, _ -> fake },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = transcripts
            },
        )
        val vm = ChatViewModel(store, factory)
        vm.openSession(sid)
        awaitTrue { vm.messages.value.size == 2 }
        assertEquals(listOf("q1", "a1"), vm.messages.value.map { it.text })
        vm.onInputChange("new")
        vm.send()
        awaitTrue { vm.messages.value.size == 4 }
        assertEquals("ok", vm.messages.value.last().text)
        // Live turn persisted into the same transcript session.
        assertTrue(transcripts.events.count { it.kind == "user" } == 2)
    }

    @Test
    fun steerWhenIdleSends() {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val fake = FakeChatProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "steered"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val vm = newVm(store, fake)
        vm.steer("go")
        awaitTrue { vm.sessionState.value.messages.size == 2 }
        assertEquals("go", vm.sessionState.value.messages.first { it.role == "user" }.text)
    }

    @Test
    fun steerWhileBusyQueuesFollowUp() {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val fake = FakeChatProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "part"))
                delay(300)
                emit(StreamEvent.Done("stop"))
            }
        }
        val vm = newVm(store, fake)
        vm.onInputChange("first")
        vm.send()
        awaitTrue { vm.sessionState.value.status == ChatStatus.STREAMING }
        vm.steer("follow")
        awaitTrue { vm.sessionState.value.pendingSteerCount == 1 || vm.messages.value.size == 4 }
        awaitTrue(timeoutMs = 12000) { vm.messages.value.size == 4 }
        assertEquals("follow", vm.messages.value[2].text)
    }

    @Test
    fun endpointSwitchRecreatesSession() {
        val store = newStore()
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            store.save(sampleEndpoint())
            vault.putKey("preset:openai", "sk-test-key-123".toCharArray())
            vault.putKey("preset:custom", "sk-test-key-456".toCharArray())
        }
        val fake = FakeChatProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        }
        var creates = 0
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ ->
                creates++
                fake
            },
        )
        val vm = ChatViewModel(store, factory)
        vm.onInputChange("one")
        vm.send()
        awaitTrue { vm.sessionState.value.messages.isNotEmpty() }
        assertEquals(1, creates)
        runBlocking {
            store.save(
                sampleEndpoint().copy(
                    providerId = "preset:custom",
                    presetId = "custom",
                    label = "自訂",
                    baseUrl = "http://127.0.0.1:11434/v1",
                    model = "local",
                ),
            )
        }
        vm.onInputChange("two")
        vm.send()
        awaitTrue { creates == 2 }
    }
}
