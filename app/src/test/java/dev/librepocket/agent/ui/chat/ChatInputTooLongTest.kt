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
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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

private class TooLongFakeProvider(
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

/**
 * R4-4 VM admission: oversize single-turn input (incl. time block) is
 * rejected with CHAT_INPUT_TOO_LONG, zero provider calls, draft restored.
 * Pure JVM with FakeChatProvider (no production endpoint).
 */
class ChatInputTooLongTest {

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
        val dir = Files.createTempDirectory("chat-toolong-test").toFile()
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

    @Test
    fun oversizeSendKeepsDraftAndNoticesWithoutProviderCall() {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val fake = TooLongFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "must not send"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking { vault.putKey("preset:openai", "sk-test-key-123".toCharArray()) }
        val factory = ChatSessionFactory(
            policy = allowKeyReadPolicy(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
        )
        val vm = chatViewModels.own(ChatViewModel(store, factory, allowKeyReadPolicy()))

        // 100k + overhead unambiguously exceeds MAX_REQUEST_CHARS=100_000.
        val huge = "x".repeat(100_000 + 5_000)
        onMain { vm.onInputChange(huge) }
        onMain { vm.send() }

        awaitTrue { vm.notice.value == "CHAT_INPUT_TOO_LONG" }
        assertEquals(0, fake.streamCalls)
        assertTrue(fake.seenRequests.isEmpty())
        // Draft preserved in the input box (send() cleared it first, restore puts it back).
        assertEquals(huge, vm.input.value)
        // Nothing appended to the live session.
        assertTrue(vm.sessionState.value.messages.isEmpty())
        // User-visible copy resolves (not a raw fall-through).
        assertEquals("輸入過長，本次未送出，請縮短後重試。", chatNoticeText("CHAT_INPUT_TOO_LONG"))
    }

    @Test
    fun oversizeSendDirectKeepsNoticeWithoutProviderCall() {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val fake = TooLongFakeProvider()
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking { vault.putKey("preset:openai", "sk-test-key-123".toCharArray()) }
        val factory = ChatSessionFactory(
            policy = allowKeyReadPolicy(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
        )
        val vm = chatViewModels.own(ChatViewModel(store, factory, allowKeyReadPolicy()))

        val huge = "y".repeat(100_000 + 5_000)
        onMain { vm.sendDirect(huge) }

        awaitTrue { vm.notice.value == "CHAT_INPUT_TOO_LONG" }
        assertEquals(0, fake.streamCalls)
        assertTrue(fake.seenRequests.isEmpty())
    }
}
