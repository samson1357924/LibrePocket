package dev.librepocket.agent.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.chat.ChatStatus
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
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
    // S1: incremented from the provider IO path; AtomicInteger so concurrent
    // turn assertions never miss an increment.
    val streamCalls = java.util.concurrent.atomic.AtomicInteger(0)
    val seenRequests: MutableList<ChatRequest> = java.util.Collections.synchronizedList(mutableListOf())

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        streamCalls.incrementAndGet()
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
        runBlocking { vault.putKey("preset:openai", "unit-test-key-123".toCharArray()) }
        // S1: T0/T1 share one store so the factory T1 recheck enforces the
        // same verdict the ViewModel authorized (see KeyReadTest newRig).
        val policy = allowKeyReadPolicy()
        val factory = ChatSessionFactory(
            policy = policy,
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
        )
        return chatViewModels.own(ChatViewModel(store, factory, policy))
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
        // Determinism: save before VM creation, as every sibling test does.
        // Creating the VM first races the initial null→endpoint observation
        // against send: the send can self-invalidate as Stale and drop,
        // so awaitTrue never reaches IDLE+2 and times out.
        runBlocking { store.save(sampleEndpoint()) }
        val vm = newVm(store, fake)
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
            vault.putKey("preset:openai", "unit-test-key-123".toCharArray())
        }
        val fake = FakeChatProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "partial"))
                delay(5000)
                emit(StreamEvent.Done("stop"))
            }
        }
        // S1: shared T0/T1 store (see newVm).
        val policy = allowKeyReadPolicy()
        val factory = ChatSessionFactory(
            policy = policy,
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
        )
        val vm = chatViewModels.own(ChatViewModel(store, factory, policy))
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
            vault.putKey("preset:openai", "unit-test-key-123".toCharArray())
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
        // S1: shared T0/T1 store (see newVm).
        val policy = allowKeyReadPolicy()
        val factory = ChatSessionFactory(
            policy = policy,
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
        )
        val vm = chatViewModels.own(ChatViewModel(store, factory, policy))
        onMain { vm.onInputChange("hi") }
        onMain { vm.send() }
        awaitTrue { vm.sessionState.value.status == ChatStatus.ERROR }
        assertTrue(vm.sessionState.value.error != null)
        assertEquals(1, fake.streamCalls.get())
        assertTrue(vm.canRetry)
        failFirst = false
        onMain { vm.retry() }
        awaitTrue { vm.sessionState.value.status == ChatStatus.IDLE }
        assertEquals(2, fake.streamCalls.get())
        assertNull(vm.sessionState.value.error)
    }

    @Test
    fun factoryCancellationAfterDurableCreateDeletesOnlyTheNewTranscriptRow() = runBlocking {
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        vault.putKey("preset:openai", "unit-test-key-123".toCharArray())
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
        // S1: direct factory.create passes explicit fail-closed
        // allowAsk=false (Kotlin forbids defaults on overrides, so the
        // interface default does not apply to the concrete type) with an
        // explicit ALLOW verdict; never forge proof with allowAsk=true.
        val factory = ChatSessionFactory(
            policy = allowKeyReadPolicy(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> FakeChatProvider() },
            sessionStores = SessionStoreSource { transcripts },
        )
        val creation = async { factory.create(sampleEndpoint(), "cancel after row", allowAsk = false, keyIsCurrent = { true }) }

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
        vault.putKey("preset:openai", "unit-test-key-123".toCharArray())
        val transcripts = dev.librepocket.session.FakeSessionStore()
        // S1: direct create needs explicit ALLOW (fail-closed default).
        val factory = ChatSessionFactory(
            policy = allowKeyReadPolicy(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> FakeChatProvider() },
            sessionStores = SessionStoreSource { transcripts },
        )

        val created = factory.create(sampleEndpoint(), "not attached", allowAsk = false, keyIsCurrent = { true })
        val sessionId = checkNotNull(created.sessionId)
        assertNotNull(transcripts.getSession(sessionId))

        factory.discardUnattached(created, deleteTranscriptRow = true)

        assertNull(transcripts.getSession(sessionId))
    }

    @Test
    fun discardingAnOpenedSessionNeverDeletesItsExistingTranscriptRow() = runBlocking {
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        vault.putKey("preset:openai", "unit-test-key-123".toCharArray())
        val transcripts = dev.librepocket.session.FakeSessionStore()
        val sessionId = transcripts.createSession("existing", "gpt-4o-mini")
        // S1: direct open needs explicit ALLOW (fail-closed default).
        val factory = ChatSessionFactory(
            policy = allowKeyReadPolicy(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> FakeChatProvider() },
            sessionStores = SessionStoreSource { transcripts },
        )

        val opened = factory.open(sampleEndpoint(), sessionId, allowAsk = false, keyIsCurrent = { true })
        factory.discardUnattached(opened, deleteTranscriptRow = false)

        assertNotNull(transcripts.getSession(sessionId))
    }

    @Test
    fun factoryRejectsMalformedRef() {
        // S1: malformed ref throws before the policy gate; ALLOW keeps the
        // verdict path explicit under fail-closed defaults.
        val factory = ChatSessionFactory(
            policy = allowKeyReadPolicy(),
            vaultSource = VaultSource { EncryptedPrefsVault(InMemoryPrefs()) },
            buildProvider = { _, _ -> FakeChatProvider() },
        )
        runBlocking {
            try {
                factory.create(sampleEndpoint().copy(apiKeyRef = "sk-pasted-key-material"), "hi", allowAsk = false, keyIsCurrent = { true })
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
        runBlocking { vault.putKey("preset:openai", "unit-test-key-123".toCharArray()) }
        val fake = FakeChatProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        }
        // S1: T0/T1 share one store (see newVm).
        val policy = allowKeyReadPolicy()
        val factory = ChatSessionFactory(
            policy = policy,
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = transcripts
            },
        )
        val vm = chatViewModels.own(ChatViewModel(store, factory, policy))
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

    @Test
    fun openSessionRestoresReasoningMarker() {
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
            // Payload-free resume marker for the reasoning-sourced turn.
            transcripts.appendEvent(
                dev.librepocket.session.TranscriptEvent(
                    sessionId = sid, runId = "r1", kind = "reasoning", text = "", createdAt = 3L,
                ),
            )
            transcripts.appendEvent(
                dev.librepocket.session.TranscriptEvent(
                    sessionId = sid, runId = "r2", kind = "user", text = "q2", createdAt = 4L,
                ),
            )
            transcripts.appendEvent(
                dev.librepocket.session.TranscriptEvent(
                    sessionId = sid, runId = "r2", kind = "assistant", text = "a2", createdAt = 5L,
                ),
            )
        }
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking { vault.putKey("preset:openai", "unit-test-key-123".toCharArray()) }
        val fake = FakeChatProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        }
        // S1: T0/T1 share one store (see newVm).
        val policy = allowKeyReadPolicy()
        val factory = ChatSessionFactory(
            policy = policy,
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = transcripts
            },
        )
        val vm = chatViewModels.own(ChatViewModel(store, factory, policy))
        onMain { vm.openSession(sid) }
        awaitTrue { vm.messages.value.size == 4 }
        val replayed = vm.messages.value
        // The marker restores the flag but never renders as a message.
        assertEquals(listOf("q1", "a1", "q2", "a2"), replayed.map { it.text })
        assertFalse(replayed[0].hasReasoning)
        assertTrue(replayed[1].hasReasoning)
        assertFalse(replayed[2].hasReasoning)
        assertFalse(replayed[3].hasReasoning)
    }

    @Test
    fun openSessionRestoresMarkerArrivingBeforeAssistant() {
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
            // Marker persisted before its assistant event: loadHistory buffers
            // it in pendingMarkers until the assistant arrives.
            transcripts.appendEvent(
                dev.librepocket.session.TranscriptEvent(
                    sessionId = sid, runId = "r1", kind = "reasoning", text = "", createdAt = 2L,
                ),
            )
            transcripts.appendEvent(
                dev.librepocket.session.TranscriptEvent(
                    sessionId = sid, runId = "r1", kind = "assistant", text = "a1", createdAt = 3L,
                ),
            )
        }
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking { vault.putKey("preset:openai", "unit-test-key-123".toCharArray()) }
        val fake = FakeChatProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        }
        // S1: T0/T1 share one store (see newVm).
        val policy = allowKeyReadPolicy()
        val factory = ChatSessionFactory(
            policy = policy,
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = transcripts
            },
        )
        val vm = chatViewModels.own(ChatViewModel(store, factory, policy))
        onMain { vm.openSession(sid) }
        awaitTrue { vm.messages.value.size == 2 }
        val replayed = vm.messages.value
        // The early marker restores the flag but never renders as a message.
        assertEquals(listOf("q1", "a1"), replayed.map { it.text })
        assertFalse(replayed[0].hasReasoning)
        assertTrue(replayed[1].hasReasoning)
    }

    @Test
    fun openSessionRestoresReasoningMarkerAcrossPages() {
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
            // Push the marker onto a later page than its assistant event.
            repeat(200) { i ->
                transcripts.appendEvent(
                    dev.librepocket.session.TranscriptEvent(
                        sessionId = sid, runId = "fill-$i", kind = "system", text = "usage", createdAt = 3L,
                    ),
                )
            }
            transcripts.appendEvent(
                dev.librepocket.session.TranscriptEvent(
                    sessionId = sid, runId = "r1", kind = "reasoning", text = "", createdAt = 4L,
                ),
            )
        }
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking { vault.putKey("preset:openai", "unit-test-key-123".toCharArray()) }
        val fake = FakeChatProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        }
        // S1: T0/T1 share one store (see newVm).
        val policy = allowKeyReadPolicy()
        val factory = ChatSessionFactory(
            policy = policy,
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = transcripts
            },
        )
        val vm = chatViewModels.own(ChatViewModel(store, factory, policy))
        onMain { vm.openSession(sid) }
        awaitTrue { vm.messages.value.size == 2 }
        assertEquals(listOf("q1", "a1"), vm.messages.value.map { it.text })
        assertTrue(vm.messages.value[1].hasReasoning)
    }

    @Test
    fun openSessionLegacyTranscriptWithoutMarkerRestoresFalse() {
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
            // Legacy row: written before the reasoning marker existed, so no
            // marker follows even if the original turn was reasoning-sourced.
            transcripts.appendEvent(
                dev.librepocket.session.TranscriptEvent(
                    sessionId = sid, runId = "r1", kind = "assistant", text = "a1", createdAt = 2L,
                ),
            )
        }
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking { vault.putKey("preset:openai", "unit-test-key-123".toCharArray()) }
        val fake = FakeChatProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        }
        // S1: T0/T1 share one store (see newVm).
        val policy = allowKeyReadPolicy()
        val factory = ChatSessionFactory(
            policy = policy,
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = transcripts
            },
        )
        val vm = chatViewModels.own(ChatViewModel(store, factory, policy))
        onMain { vm.openSession(sid) }
        awaitTrue { vm.messages.value.size == 2 }
        assertEquals(listOf("q1", "a1"), vm.messages.value.map { it.text })
        assertFalse(vm.messages.value[0].hasReasoning)
        assertFalse(vm.messages.value[1].hasReasoning)
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
        // S1: T0/T1 share the same deny store; the factory is never
        // reached (T0 denies), but the shared instance keeps the wiring
        // explicit (see newVm).
        val factory = ChatSessionFactory(
            policy = deny,
            vaultSource = VaultSource { EncryptedPrefsVault(InMemoryPrefs()) },
            buildProvider = { _, _ -> fake },
        )
        val vm = chatViewModels.own(ChatViewModel(store, factory, deny))
        onMain { vm.onInputChange("hi") }
        onMain { vm.send() }
        awaitTrue { vm.notice.value == "POLICY_DENIED" }
        assertEquals(0, fake.streamCalls.get())
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
        // S1: shared deny store (see policyDenyBlocksSendWithZeroRequests).
        val factory = ChatSessionFactory(
            policy = deny,
            vaultSource = VaultSource { EncryptedPrefsVault(InMemoryPrefs()) },
            buildProvider = { _, _ -> fake },
        )
        val vm = chatViewModels.own(ChatViewModel(store, factory, deny))
        onMain { vm.steer("queued-evil") }
        awaitTrue { vm.notice.value == "POLICY_DENIED" }
        assertEquals(0, fake.streamCalls.get())
    }

    @Test
    fun modelSwitchRecreatesLiveSession() {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking { vault.putKey("preset:openai", "unit-test-key-123".toCharArray()) }
        val fake = FakeChatProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        }
        var creates = 0
        val transcripts = dev.librepocket.session.FakeSessionStore()
        // S1: shared T0/T1 store (see newVm).
        val modelPolicy = allowKeyReadPolicy()
        val factory = ChatSessionFactory(
            policy = modelPolicy,
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ ->
                creates++
                fake
            },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = transcripts
            },
        )
        val vm = chatViewModels.own(ChatViewModel(store, factory, modelPolicy))
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
            vault.putKey("preset:openai", "unit-test-key-123".toCharArray())
            vault.putKey("preset:custom", "unit-test-key-456".toCharArray())
        }
        val fake = FakeChatProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        }
        var creates = 0
        // S1: shared T0/T1 store (see newVm).
        val switchPolicy = allowKeyReadPolicy()
        val factory = ChatSessionFactory(
            policy = switchPolicy,
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ ->
                creates++
                fake
            },
        )
        val vm = chatViewModels.own(ChatViewModel(store, factory, switchPolicy))
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
}
