package dev.librepocket.agent.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.provider.ProviderProtocol
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class ChatEndpointSessionRegressionTest {
    private val chatMain = ChatTestMainDispatcher()
    private val chatViewModels = ChatTestViewModelStore()
    private val tmpDirs = ArrayList<File>()
    private val dataScopes = ArrayList<CoroutineScope>()
    private val servers = ArrayList<MockWebServer>()

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
                cancelAndJoinChatTestScopes(dataScopes)
            } finally {
                try {
                    servers.forEach { it.shutdown() }
                } finally {
                    try {
                        tmpDirs.forEach { it.deleteRecursively() }
                    } finally {
                        chatMain.resetAndClose()
                    }
                }
            }
        }
    }

    private fun newStore(): EndpointStore {
        val dir = Files.createTempDirectory("endpoint-binding-test").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataScopes.add(scope)
        return EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(dir, "endpoint.preferences_pb") },
            ),
        )
    }

    private fun own(viewModel: ChatViewModel): ChatViewModel = chatViewModels.own(viewModel)

    private fun newServer(): MockWebServer = MockWebServer().also {
        it.start()
        servers.add(it)
    }

    private fun endpoint(server: MockWebServer) = EndpointConfig(
        providerId = "preset:custom",
        presetId = "custom",
        label = "local test endpoint",
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "test-model",
        apiKeyRef = "provider_key/shared-test-alias",
    )

    private fun response() = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n",
        )

    private suspend fun awaitNoAttachedSession(viewModel: ChatViewModel) {
        withTimeout(5_000) {
            viewModel.sessionState.first { it.status.name == "IDLE" && it.messages.isEmpty() }
        }
    }

    /** TurnController keeps its message list for the lifetime of one ChatSession. */
    private suspend fun awaitCompletedTurn(viewModel: ChatViewModel, expectedTurnsInSession: Int) {
        val completed = withTimeoutOrNull(10_000) {
            viewModel.sessionState.first { state ->
                state.status.name == "IDLE" &&
                    state.messages.count { it.role == "user" } >= expectedTurnsInSession &&
                    state.messages.count { it.role == "assistant" && !it.isPartial } >= expectedTurnsInSession
            }
        }
        if (completed == null) {
            throw AssertionError(
                "Turn did not complete within 10000 ms " +
                    "(expectedTurnsInSession=$expectedTurnsInSession); " +
                    "sessionState=${viewModel.sessionState.value}, notice=${viewModel.notice.value}",
            )
        }
    }

    @Test
    fun samePresetAndModelSwitchesOriginAndFrozenKeyNeverFollowsMutableAlias() = runBlocking {
        val store = newStore()
        val endpointA = newServer()
        val endpointB = newServer()
        endpointA.enqueue(response())
        endpointA.enqueue(response())
        endpointB.enqueue(response())

        val vault = EncryptedPrefsVault(InMemoryPrefs())
        vault.putKey("shared-test-alias", "test-key-A-123".toCharArray())
        store.save(endpoint(endpointA))

        val policy = InMemoryPolicyStore()
        val sessions = ChatSessionFactory(
            policy = policy,
            vaultSource = VaultSource { vault },
        )
        val viewModel = own(ChatViewModel(store, sessions, policy))
        try {
            chatMain.run { viewModel.sendDirect("first on A") }
            val first = endpointA.takeRequest(10, TimeUnit.SECONDS)
            assertNotNull(first)
            assertEquals("/v1/chat/completions", first!!.path)
            assertEquals("Bearer test-key-A-123", first.getHeader("Authorization"))
            awaitCompletedTurn(viewModel, expectedTurnsInSession = 1)

            // Setup writes the mutable alias before endpoint metadata. The
            // already-live A session must retain only its captured A key.
            vault.putKey("shared-test-alias", "test-key-B-456".toCharArray())
            chatMain.run { viewModel.sendDirect("still bound to A") }
            val second = endpointA.takeRequest(10, TimeUnit.SECONDS)
            assertNotNull(second)
            assertEquals("Bearer test-key-A-123", second!!.getHeader("Authorization"))
            awaitCompletedTurn(viewModel, expectedTurnsInSession = 2)
            assertEquals(0, endpointB.requestCount)

            // Metadata save advances configRevision. The retained ViewModel
            // observes the shared DataStore and rebuilds to B before send.
            store.save(endpoint(endpointB))
            awaitNoAttachedSession(viewModel)
            chatMain.run { viewModel.sendDirect("now on B") }
            val third = endpointB.takeRequest(10, TimeUnit.SECONDS)
            assertNotNull(third)
            assertEquals("/v1/chat/completions", third!!.path)
            assertEquals("Bearer test-key-B-456", third.getHeader("Authorization"))
            awaitCompletedTurn(viewModel, expectedTurnsInSession = 1)

            assertEquals(2, endpointA.requestCount)
            assertEquals(1, endpointB.requestCount)

            endpointA.enqueue(response())
            vault.putKey("shared-test-alias", "test-key-A-123".toCharArray())
            store.save(endpoint(endpointA))
            awaitNoAttachedSession(viewModel)
            chatMain.run { viewModel.sendDirect("back on A") }
            val fourth = endpointA.takeRequest(10, TimeUnit.SECONDS)
            assertNotNull(fourth)
            assertEquals("Bearer test-key-A-123", fourth!!.getHeader("Authorization"))
            awaitCompletedTurn(viewModel, expectedTurnsInSession = 1)
            assertEquals(3, endpointA.requestCount)
            assertEquals(1, endpointB.requestCount)
        } finally {
            chatMain.run { viewModel.newChat() }
        }
    }
}
