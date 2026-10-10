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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeChatProvider(
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

class ChatWiringTest {

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
        runBlocking { vault.putKey("preset:openai", "sk-test-key-123".toCharArray()) }
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
        )
        return chatViewModels.own(ChatViewModel(store, factory, InMemoryPolicyStore()))
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
        val vm = newVm(store, fake)
        runBlocking { store.save(sampleEndpoint()) }
        onMain { vm.onInputChange("hi") }
        onMain { vm.send() }
        awaitTrue { vm.sessionState.value.status == ChatStatus.IDLE && vm.sessionState.value.messages.size == 2 }
        val assistant = vm.sessionState.value.messages.first { it.role == "assistant" }
        assertEquals("hello", assistant.text)
        assertTrue(vm.sessionState.value.error == null)
    }

    @Test
    fun busySendSteersAndCancelWorks() {
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
        val vm = chatViewModels.own(ChatViewModel(store, factory, InMemoryPolicyStore()))
        try {
            onMain { vm.onInputChange("first") }
            onMain { vm.send() }
            awaitTrue { vm.sessionState.value.status == ChatStatus.STREAMING }
            onMain { vm.onInputChange("second") }
            onMain { vm.send() } // busy: steered, never preempts; input consumed into the queue
            awaitTrue { vm.sessionState.value.pendingSteerCount == 1 }
            assertEquals(ChatStatus.STREAMING, vm.sessionState.value.status)
            onMain { vm.cancel() }
            awaitTrue { vm.sessionState.value.status == ChatStatus.CANCELLED }
            val partial = vm.sessionState.value.messages.first { it.role == "assistant" }
            assertTrue(partial.isPartial)
        } finally {
            onMain { vm.newChat() } // close session: drains the queued steer, no background leak
        }
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
        val vm = chatViewModels.own(ChatViewModel(store, factory, InMemoryPolicyStore()))
        onMain { vm.onInputChange("hi") }
        onMain { vm.send() }
        awaitTrue { vm.sessionState.value.status == ChatStatus.ERROR }
        assertTrue(vm.sessionState.value.error != null)
        assertEquals(1, fake.streamCalls)
        assertTrue(vm.canRetry)
        failFirst = false
        onMain { vm.retry() }
        awaitTrue { vm.sessionState.value.status == ChatStatus.IDLE }
        assertEquals(2, fake.streamCalls)
        assertNull(vm.sessionState.value.error)
    }

    @Test
    fun factoryCancellationAfterDurableCreateDeletesOnlyTheNewTranscriptRow() = runBlocking {
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        vault.putKey("preset:openai", "sk-test-key-123".toCharArray())
        val rowCreated = CompletableDeferred<Unit>()
        val allowCreateReturn = CompletableDeferred<Unit>()
        val transcripts = object : dev.librepocket.session.FakeSessionStore() {
            override suspend fun createSession(title: String, model: String): String {
                val id = super.createSession(title, model)
                rowCreated.complete(Unit)
                allowCreateReturn.await()
                return id
            }
        }
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> FakeChatProvider() },
            sessionStores = SessionStoreSource { transcripts },
        )
        val creation = async { factory.create(sampleEndpoint(), "cancel after row") }

        try {
            withTimeout(5_000) { rowCreated.await() }
            creation.cancel()
            assertFalse(creation.isCompleted)
        } finally {
            allowCreateReturn.complete(Unit)
        }
        withTimeout(7_000) { creation.cancelAndJoin() }
        assertTrue(transcripts.metas.isEmpty())
    }

    @Test
    fun discardingAnUnattachedNewSessionDeletesItsTranscriptRow() = runBlocking {
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        vault.putKey("preset:openai", "sk-test-key-123".toCharArray())
        val transcripts = dev.librepocket.session.FakeSessionStore()
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> FakeChatProvider() },
            sessionStores = SessionStoreSource { transcripts },
        )

        val created = factory.create(sampleEndpoint(), "not attached")
        val sessionId = checkNotNull(created.sessionId)
        assertNotNull(transcripts.getSession(sessionId))

        factory.discardUnattached(created, deleteTranscriptRow = true)

        assertNull(transcripts.getSession(sessionId))
    }

    @Test
    fun discardingAnOpenedSessionNeverDeletesItsExistingTranscriptRow() = runBlocking {
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        vault.putKey("preset:openai", "sk-test-key-123".toCharArray())
        val transcripts = dev.librepocket.session.FakeSessionStore()
        val sessionId = transcripts.createSession("existing", "gpt-4o-mini")
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> FakeChatProvider() },
            sessionStores = SessionStoreSource { transcripts },
        )

        val opened = factory.open(sampleEndpoint(), sessionId)
        factory.discardUnattached(opened, deleteTranscriptRow = false)

        assertNotNull(transcripts.getSession(sessionId))
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
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking { vault.putKey("preset:openai", "sk-test-key-123".toCharArray()) }
        val fake = FakeChatProvider { _ ->
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
        awaitTrue { vm.messages.value.size == 2 }
        assertEquals(listOf("q1", "a1"), vm.messages.value.map { it.text })
        onMain { vm.onInputChange("new") }
        onMain { vm.send() }
        awaitTrue { vm.messages.value.size == 4 }
        assertEquals("ok", vm.messages.value.last().text)
        // Live turn persisted into the same transcript session.
        assertTrue(transcripts.events.count { it.kind == "user" } == 2)
    }

    // Phase 2 baseline (fake store, synthetic rows only): open replays
    // pre-existing rows as-is and invents no INTERRUPTED markers.
    // Cross-restart RUNNING -> INTERRUPTED backfill needs the Phase-3
    // persisted partial/status bits and lands in Phase 3 (see
    // ChatSessionFactory.open KDoc); this pins the baseline it extends.
    @Test
    fun openReplaysDanglingPartialAsIsWithoutInventedMarks() {
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
            // Dangling partial from a killed process: no terminal row follows.
            transcripts.appendEvent(
                dev.librepocket.session.TranscriptEvent(
                    sessionId = sid, runId = "r1", kind = "assistant", text = "half answer", createdAt = 2L,
                ),
            )
        }
        val before = transcripts.events.size
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking { vault.putKey("preset:openai", "sk-test-key-123".toCharArray()) }
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> FakeChatProvider() },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = transcripts
            },
        )
        val vm = chatViewModels.own(ChatViewModel(store, factory, InMemoryPolicyStore()))
        onMain { vm.openSession(sid) }
        awaitTrue { vm.messages.value.size == 2 }
        assertEquals(listOf("q1", "half answer"), vm.messages.value.map { it.text })
        assertEquals("Phase 2 invents no backfill rows", before, transcripts.events.size)
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
        onMain { vm.steer("go") }
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
        onMain { vm.onInputChange("first") }
        onMain { vm.send() }
        awaitTrue { vm.sessionState.value.status == ChatStatus.STREAMING }
        onMain { vm.steer("follow") }
        awaitTrue { vm.sessionState.value.pendingSteerCount == 1 || vm.messages.value.size == 4 }
        awaitTrue(timeoutMs = 12000) { vm.messages.value.size == 4 }
        assertEquals("follow", vm.messages.value[2].text)
    }

    @Test
    fun policyDenyBlocksSendWithZeroRequests() {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val fake = FakeChatProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "must not send"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val deny = object : dev.librepocket.policy.PolicyStore {
            override fun evaluate(action: String, resource: String) =
                dev.librepocket.policy.PolicyDecision(
                    dev.librepocket.policy.Verdict.DENY, null, System.currentTimeMillis(),
                )

            override suspend fun setRule(rule: dev.librepocket.policy.PolicyRule) = Unit
            override suspend fun removeRule(pattern: String) = Unit
            override suspend fun listRules() = emptyList<dev.librepocket.policy.PolicyRule>()
            override suspend fun evaluateFresh(action: String, resource: String) =
                dev.librepocket.policy.PolicyDecision(
                    dev.librepocket.policy.Verdict.DENY, null, System.currentTimeMillis(),
                )
        }
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = VaultSource { EncryptedPrefsVault(InMemoryPrefs()) },
            buildProvider = { _, _ -> fake },
        )
        val vm = chatViewModels.own(ChatViewModel(store, factory, deny))
        onMain { vm.onInputChange("hi") }
        onMain { vm.send() }
        awaitTrue { vm.notice.value == "POLICY_DENIED" }
        assertEquals(0, fake.streamCalls)
        assertTrue(vm.sessionState.value.messages.isEmpty())
    }

    @Test
    fun policyDenyBlocksSteerWithZeroRequests() {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val fake = FakeChatProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "must not send"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val deny = object : dev.librepocket.policy.PolicyStore {
            override fun evaluate(action: String, resource: String) =
                dev.librepocket.policy.PolicyDecision(
                    dev.librepocket.policy.Verdict.DENY, null, System.currentTimeMillis(),
                )

            override suspend fun setRule(rule: dev.librepocket.policy.PolicyRule) = Unit
            override suspend fun removeRule(pattern: String) = Unit
            override suspend fun listRules() = emptyList<dev.librepocket.policy.PolicyRule>()
            override suspend fun evaluateFresh(action: String, resource: String) =
                dev.librepocket.policy.PolicyDecision(
                    dev.librepocket.policy.Verdict.DENY, null, System.currentTimeMillis(),
                )
        }
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = VaultSource { EncryptedPrefsVault(InMemoryPrefs()) },
            buildProvider = { _, _ -> fake },
        )
        val vm = chatViewModels.own(ChatViewModel(store, factory, deny))
        onMain { vm.steer("queued-evil") }
        awaitTrue { vm.notice.value == "POLICY_DENIED" }
        assertEquals(0, fake.streamCalls)
    }

    @Test
    fun modelSwitchRecreatesLiveSession() {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking { vault.putKey("preset:openai", "sk-test-key-123".toCharArray()) }
        val fake = FakeChatProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        }
        var creates = 0
        val transcripts = dev.librepocket.session.FakeSessionStore()
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ ->
                creates++
                fake
            },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = transcripts
            },
        )
        val vm = chatViewModels.own(ChatViewModel(store, factory, InMemoryPolicyStore()))
        onMain { vm.onInputChange("one") }
        onMain { vm.send() }
        awaitTrue { vm.sessionState.value.messages.isNotEmpty() }
        assertEquals(1, creates)
        // The transcript sink persists asynchronously; wait for it before switching.
        awaitTrue { transcripts.events.count { it.kind == "user" || it.kind == "assistant" } == 2 }
        // Same provider, different model -> live session must rebuild with the
        // new model, and the previous transcript stays visible.
        runBlocking { store.save(sampleEndpoint().copy(model = "gpt-4o")) }
        onMain { vm.onInputChange("two") }
        onMain { vm.send() }
        awaitTrue { creates == 2 }
        awaitTrue { vm.messages.value.size == 4 }
        assertEquals(listOf("gpt-4o-mini", "gpt-4o"), fake.seenRequests.map { it.model })
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
        val vm = chatViewModels.own(ChatViewModel(store, factory, InMemoryPolicyStore()))
        onMain { vm.onInputChange("one") }
        onMain { vm.send() }
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
                    apiKeyRef = "provider_key/preset:custom",
                ),
            )
        }
        onMain { vm.onInputChange("two") }
        onMain { vm.send() }
        awaitTrue { creates == 2 }
    }

    @Test
    fun sameProviderModelSwitchCarriesHistoryIntoNextRequest() {
        val store = newStore()
        val transcripts = dev.librepocket.session.FakeSessionStore()
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            store.save(sampleEndpoint().copy(model = "gpt-4o-mini"))
            vault.putKey("preset:openai", "sk-test-key-123".toCharArray())
        }
        val fake = FakeChatProvider { req ->
            flow {
                val userMsg = req.messages.last { it.role == "user" }.text
                if (userMsg.startsWith("u1")) {
                    emit(StreamEvent.TextDelta(0, 0, "a1"))
                } else {
                    emit(StreamEvent.TextDelta(0, 0, "a2"))
                }
                emit(StreamEvent.Done("stop"))
            }
        }
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
            sessionStores = SessionStoreSource { transcripts },
        )
        val vm = chatViewModels.own(ChatViewModel(store, factory, InMemoryPolicyStore()))

        // Round 1: send u1
        onMain { vm.onInputChange("u1") }
        onMain { vm.send() }
        awaitTrue { vm.sessionState.value.status == ChatStatus.IDLE && vm.messages.value.size == 2 }
        assertEquals("a1", vm.messages.value.last().text)
        assertEquals(1, fake.seenRequests.size)

        // Wait for transcript persistence
        awaitTrue { transcripts.events.count { it.kind == "user" || it.kind == "assistant" } == 2 }

        // Switch model within same provider
        runBlocking {
            store.save(sampleEndpoint().copy(model = "gpt-4o"))
        }

        // Round 2: send u2
        onMain { vm.onInputChange("u2") }
        onMain { vm.send() }
        awaitTrue { vm.sessionState.value.status == ChatStatus.IDLE && vm.messages.value.size == 4 }
        assertEquals("a2", vm.messages.value.last().text)
        assertEquals(2, fake.seenRequests.size)

        // Provider spy asserts seenRequests[1].messages contains round 1 history (u1, a1, u2)
        val secondReq = fake.seenRequests[1]
        assertEquals(3, secondReq.messages.size)
        assertEquals("user", secondReq.messages[0].role)
        assertTrue(secondReq.messages[0].text.startsWith("u1"))
        assertEquals("assistant", secondReq.messages[1].role)
        assertEquals("a1", secondReq.messages[1].text)
        assertEquals("user", secondReq.messages[2].role)
        assertTrue(secondReq.messages[2].text.startsWith("u2"))
        assertNull(vm.notice.value)
    }

    @Test
    fun crossProviderModelSwitchWithholdsHistoryAndSetsNotice() {
        val store = newStore()
        val transcripts = dev.librepocket.session.FakeSessionStore()
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            store.save(sampleEndpoint().copy(model = "gpt-4o-mini"))
            vault.putKey("preset:openai", "sk-test-key-123".toCharArray())
            vault.putKey("preset:anthropic", "sk-ant-test-key".toCharArray())
        }
        val fake = FakeChatProvider { req ->
            flow {
                val userMsg = req.messages.last { it.role == "user" }.text
                if (userMsg.startsWith("u1")) {
                    emit(StreamEvent.TextDelta(0, 0, "a1"))
                } else {
                    emit(StreamEvent.TextDelta(0, 0, "a2"))
                }
                emit(StreamEvent.Done("stop"))
            }
        }
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
            sessionStores = SessionStoreSource { transcripts },
        )
        val vm = chatViewModels.own(ChatViewModel(store, factory, InMemoryPolicyStore()))

        // Round 1: send u1
        onMain { vm.onInputChange("u1") }
        onMain { vm.send() }
        awaitTrue { vm.sessionState.value.status == ChatStatus.IDLE && vm.messages.value.size == 2 }
        assertEquals("a1", vm.messages.value.last().text)
        assertEquals(1, fake.seenRequests.size)

        // Wait for transcript persistence
        awaitTrue { transcripts.events.count { it.kind == "user" || it.kind == "assistant" } == 2 }

        // Switch to different provider (Anthropic)
        val anthropicEndpoint = EndpointConfig(
            providerId = "preset:anthropic",
            presetId = "anthropic",
            label = "Anthropic",
            baseUrl = "https://api.anthropic.com/v1",
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            model = "claude-3-5-sonnet",
            apiKeyRef = "provider_key/preset:anthropic",
        )
        runBlocking {
            store.save(anthropicEndpoint)
        }

        // Round 2: send u2
        onMain { vm.onInputChange("u2") }
        onMain { vm.send() }
        awaitTrue { vm.sessionState.value.status == ChatStatus.IDLE && vm.messages.value.size == 4 }
        assertEquals("a2", vm.messages.value.last().text)
        assertEquals(2, fake.seenRequests.size)

        // Provider spy asserts seenRequests[1].messages contains ONLY new message (u2)
        val secondReq = fake.seenRequests[1]
        assertEquals(1, secondReq.messages.size)
        assertEquals("user", secondReq.messages[0].role)
        assertTrue(secondReq.messages[0].text.startsWith("u2"))
        assertEquals("RESUME_CROSS_PROVIDER_HISTORY_WITHHELD", vm.notice.value)
    }
}
