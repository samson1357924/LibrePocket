package dev.librepocket.agent.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.agent.ui.setup.SetupValidation
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    private fun baseUrlOf(server: MockWebServer): String = server.url("/v1").toString().trimEnd('/')

    /** Production-shaped staged alias: `<providerId>#staged-<suffix>`. */
    private fun namespacedAlias(server: MockWebServer, suffix: String): String {
        val providerId = SetupValidation.deriveEndpointId("custom", baseUrlOf(server))
        return SetupValidation.stagedAlias(providerId, suffix)
    }

    private fun endpoint(server: MockWebServer, alias: String): EndpointConfig {
        // Stage1: stable per-endpoint entity id derived from the custom URL —
        // distinct servers never share the providerId namespace; each Key is
        // scoped to its own vault alias (production staged shape, so the
        // triple passes the store/factory namespace checks).
        val providerId = SetupValidation.deriveEndpointId("custom", baseUrlOf(server))
        return EndpointConfig(
            providerId = providerId,
            presetId = "custom",
            label = "local test endpoint",
            baseUrl = baseUrlOf(server),
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            model = "test-model",
            apiKeyRef = "provider_key/" + SetupValidation.stagedAlias(providerId, alias),
        )
    }

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
        val aliasA = "stage1-test-alias-a"
        val aliasB = "stage1-test-alias-b"
        val nsA = namespacedAlias(endpointA, aliasA)
        val nsB = namespacedAlias(endpointB, aliasB)
        vault.putKey(nsA, "test-key-A-123".toCharArray())
        vault.putKey(nsB, "test-key-B-456".toCharArray())
        store.save(endpoint(endpointA, aliasA))

        val policy = allowKeyReadPolicy()
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
            vault.putKey(nsA, "test-key-B-456".toCharArray())
            chatMain.run { viewModel.sendDirect("still bound to A") }
            val second = endpointA.takeRequest(10, TimeUnit.SECONDS)
            assertNotNull(second)
            assertEquals("Bearer test-key-A-123", second!!.getHeader("Authorization"))
            awaitCompletedTurn(viewModel, expectedTurnsInSession = 2)
            assertEquals(0, endpointB.requestCount)

            // Metadata save advances configRevision. The retained ViewModel
            // observes the shared DataStore and rebuilds to B before send.
            store.save(endpoint(endpointB, aliasB))
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
            vault.putKey(nsA, "test-key-A-123".toCharArray())
            store.save(endpoint(endpointA, aliasA))
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

    /**
     * Stage1 (R2): same preset + same model, different custom URLs — every
     * switch rebuilds and each destination receives only its own key.
     * Extends A->B->A with a same-URL rotation (entity id stable, vault alias
     * rotates) and a failed save (stale CAS loses → binding unchanged, the
     * loser URL sees nothing new). Asserts the real request target + the
     * Authorization header on both MockWebServers; zero real keys/endpoints.
     */
    @Test
    fun customPerUrlKeyIsolationAcrossSwitchRotationAndFailedSave() = runBlocking {
        val store = newStore()
        val serverA = newServer()
        val serverB = newServer()

        val vault = EncryptedPrefsVault(InMemoryPrefs())
        val aliasA = "stage1-iso-alias-a"
        val aliasB = "stage1-iso-alias-b"
        val aliasA2 = "stage1-iso-alias-a-rotated"
        val nsA = namespacedAlias(serverA, aliasA)
        val nsB = namespacedAlias(serverB, aliasB)
        val nsA2 = namespacedAlias(serverA, aliasA2)
        val keyA1 = "stage1-fake-key-A-11111111"
        val keyB = "stage1-fake-key-B-22222222"
        val keyA2 = "stage1-fake-key-A-rotated-33333333"
        vault.putKey(nsA, keyA1.toCharArray())
        vault.putKey(nsB, keyB.toCharArray())
        vault.putKey(nsA2, keyA2.toCharArray())

        val cfgA1 = endpoint(serverA, aliasA)
        val cfgB = endpoint(serverB, aliasB)
        val cfgA2 = endpoint(serverA, aliasA2)
        // Per-endpoint namespaces: distinct URLs never share the entity id;
        // rotation on the same URL keeps it (stable) while the vault alias
        // — and hence the binding — rotates.
        assertNotEquals(cfgA1.providerId, cfgB.providerId)
        assertEquals(cfgA1.providerId, cfgA2.providerId)
        assertNotEquals(cfgA1.apiKeyRef, cfgA2.apiKeyRef)
        store.save(cfgA1)

        val policy = allowKeyReadPolicy()
        val sessions = ChatSessionFactory(
            policy = policy,
            vaultSource = VaultSource { vault },
        )
        val viewModel = own(ChatViewModel(store, sessions, policy))
        try {
            // 1) First turn on A carries KA1 to A only.
            serverA.enqueue(response())
            chatMain.run { viewModel.sendDirect("first on A") }
            val first = serverA.takeRequest(10, TimeUnit.SECONDS)
            assertNotNull(first)
            assertEquals("/v1/chat/completions", first!!.path)
            assertEquals("Bearer $keyA1", first.getHeader("Authorization"))
            awaitCompletedTurn(viewModel, expectedTurnsInSession = 1)
            assertEquals(1, serverA.requestCount)
            assertEquals(0, serverB.requestCount)

            // 2) Switch to B (same preset, same model, different URL): the
            // session must rebuild and B must receive only KB.
            serverB.enqueue(response())
            store.save(cfgB)
            awaitNoAttachedSession(viewModel)
            chatMain.run { viewModel.sendDirect("now on B") }
            val second = serverB.takeRequest(10, TimeUnit.SECONDS)
            assertNotNull(second)
            assertEquals("/v1/chat/completions", second!!.path)
            assertEquals("Bearer $keyB", second!!.getHeader("Authorization"))
            awaitCompletedTurn(viewModel, expectedTurnsInSession = 1)
            assertEquals(1, serverA.requestCount)
            assertEquals(1, serverB.requestCount)

            // 3) Rotation back on A: same entity id, new alias/key — A must
            // receive only the rotated key.
            serverA.enqueue(response())
            val revBeforeRotation = store.currentRevision()
            store.save(cfgA2)
            awaitNoAttachedSession(viewModel)
            chatMain.run { viewModel.sendDirect("back on A rotated") }
            val third = serverA.takeRequest(10, TimeUnit.SECONDS)
            assertNotNull(third)
            assertEquals("Bearer $keyA2", third!!.getHeader("Authorization"))
            awaitCompletedTurn(viewModel, expectedTurnsInSession = 1)
            assertEquals(2, serverA.requestCount)
            assertEquals(1, serverB.requestCount)

            // 4) Failed save to B (a stale writer's revision loses the CAS and
            // writes nothing): the live binding stays on A — the next turn
            // still reaches A with the rotated key and B sees nothing new.
            val committed = store.saveIfRevision(cfgB, expectedRevision = revBeforeRotation)
            assertFalse(committed)
            serverA.enqueue(response())
            chatMain.run { viewModel.sendDirect("still on A after failed save") }
            val fourth = serverA.takeRequest(10, TimeUnit.SECONDS)
            assertNotNull(fourth)
            assertEquals("Bearer $keyA2", fourth!!.getHeader("Authorization"))
            awaitCompletedTurn(viewModel, expectedTurnsInSession = 1)
            assertEquals(3, serverA.requestCount)
            assertEquals(1, serverB.requestCount)
        } finally {
            chatMain.run { viewModel.newChat() }
        }
    }

    /**
     * Stage1 compat: a pre-Stage1 custom row (shared `preset:custom` entity
     * id over the fixed vault slot, whose content may belong to any custom
     * URL ever saved) is never sent — the send fails closed with NO_ENDPOINT
     * (input preserved for re-entry in Setup), and neither server observes a
     * request.
     */
    @Test
    fun legacyCustomRowNeverSendsAndSurfacesNoEndpoint() = runBlocking {
        val store = newStore()
        val serverA = newServer()
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        vault.putKey("preset:custom", "stage1-fake-legacy-key".toCharArray())
        store.save(
            EndpointConfig(
                providerId = "preset:custom",
                presetId = "custom",
                label = "legacy endpoint",
                baseUrl = baseUrlOf(serverA),
                protocol = ProviderProtocol.CHAT_COMPLETIONS,
                model = "test-model",
                apiKeyRef = "provider_key/preset:custom",
            ),
        )
        val policy = allowKeyReadPolicy()
        val sessions = ChatSessionFactory(
            policy = policy,
            vaultSource = VaultSource { vault },
        )
        val viewModel = own(ChatViewModel(store, sessions, policy))
        try {
            chatMain.run { viewModel.sendDirect("must not send") }
            withTimeout(5_000) { viewModel.notice.first { it == "NO_ENDPOINT" } }
            assertEquals("must not send", viewModel.input.value)
            assertEquals(0, serverA.requestCount)
            assertNull(serverA.takeRequest(1, TimeUnit.SECONDS))
        } finally {
            chatMain.run { viewModel.newChat() }
        }
    }
}
