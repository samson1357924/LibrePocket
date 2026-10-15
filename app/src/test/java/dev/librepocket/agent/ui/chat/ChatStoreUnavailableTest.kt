package dev.librepocket.agent.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.TranscriptEvent
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class StallFakeProvider(
    var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    var streamCalls = 0
    val seenRequests: MutableList<ChatRequest> = java.util.Collections.synchronizedList(mutableListOf())

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        streamCalls++
        seenRequests.add(request)
        emitAll(handler(request))
    }

    override suspend fun listModels(): List<String> = emptyList()
}

/** Store-down fake: session creation succeeds, every durable append fails (fail-closed admission). */
private class StallSessionStore : FakeSessionStore() {
    override suspend fun appendEvent(event: TranscriptEvent): Long {
        throw IOException("store down")
    }
}

/**
 * Stage 6 (R4-3) VM admission: a stalled/failed transcript store surfaces
 * CHAT_STORE_UNAVAILABLE, zero provider calls, draft restored.
 * Pure JVM with FakeSessionStore (no production endpoint).
 */
class ChatStoreUnavailableTest {

    private val tmpDirs = ArrayList<java.io.File>()
    private val scopes = ArrayList<CoroutineScope>()
    private val chatViewModels = ChatTestViewModelStore()
    private val chatMain = ChatTestMainDispatcher()

    @Before
    fun setUp() {
        chatMain.install()
    }

    @After
    fun tearDown() {
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
        val dir = Files.createTempDirectory("chat-store-unavailable-test").toFile()
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

    private fun awaitTrue(timeoutMs: Long = 8000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) error("timed out waiting for condition")
            Thread.sleep(10)
        }
    }

    private fun stallVm(endpointStore: EndpointStore, fake: StallFakeProvider): ChatViewModel {
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking { vault.putKey("preset:openai", "sk-test-key-123".toCharArray()) }
        val transcripts = StallSessionStore()
        val factory = ChatSessionFactory(
            policy = allowKeyReadPolicy(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
            sessionStores = SessionStoreSource { transcripts },
        )
        return chatViewModels.own(ChatViewModel(endpointStore, factory, allowKeyReadPolicy()))
    }

    @Test
    fun stalledSendKeepsDraftAndNoticesWithoutProviderCall() {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val fake = StallFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "must not send"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val vm = stallVm(store, fake)
        val text = "hello store stall"
        onMain { vm.onInputChange(text) }
        onMain { vm.send() }
        awaitTrue { vm.notice.value == "CHAT_STORE_UNAVAILABLE" }
        assertEquals(0, fake.streamCalls)
        assertTrue(fake.seenRequests.isEmpty())
        assertEquals(text, vm.input.value)
        assertTrue(vm.sessionState.value.messages.isEmpty())
        assertEquals("無法寫入對話紀錄，本次未送出，請稍後重試。", chatNoticeText("CHAT_STORE_UNAVAILABLE"))
    }

    @Test
    fun stalledSendDirectKeepsNoticeWithoutProviderCall() {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val fake = StallFakeProvider()
        val vm = stallVm(store, fake)
        val text = "hello direct stall"
        onMain { vm.sendDirect(text) }
        awaitTrue { vm.notice.value == "CHAT_STORE_UNAVAILABLE" }
        assertEquals(0, fake.streamCalls)
        assertTrue(fake.seenRequests.isEmpty())
        assertEquals(text, vm.input.value)
        assertTrue(vm.sessionState.value.messages.isEmpty())
    }
}
