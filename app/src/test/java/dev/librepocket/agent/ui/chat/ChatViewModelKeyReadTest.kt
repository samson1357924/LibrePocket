package dev.librepocket.agent.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.chat.ChatStatus
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.keystore.KeyVault
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.TranscriptEvent
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class KeyReadFakeProvider(
    var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    // S1: incremented from the provider IO path; AtomicInteger so concurrent
    // turn assertions never miss an increment.
    val streamCalls = AtomicInteger(0)

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        streamCalls.incrementAndGet()
        emitAll(handler(request))
    }

    override suspend fun listModels(): List<String> = emptyList()
}

/**
 * Phase A1 (#13): key.read ASK must not pass through to the vault.
 * ASK requires a bound one-shot confirmation; without it the read is denied
 * with zero provider calls. DENY always wins; failures deny closed.
 */
class ChatViewModelKeyReadTest {
    private val chatMain = ChatTestMainDispatcher()
    private val chatViewModels = ChatTestViewModelStore()
    private val tmpDirs = ArrayList<File>()
    private val dataScopes = ArrayList<CoroutineScope>()

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
                    tmpDirs.forEach { it.deleteRecursively() }
                } finally {
                    chatMain.resetAndClose()
                }
            }
        }
    }

    private fun newStore(): EndpointStore {
        val dir = Files.createTempDirectory("keyread-atest").toFile()
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

    private fun endpointA() = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "gpt-4o-mini",
        apiKeyRef = "provider_key/preset:openai",
    )

    private fun endpointB() = EndpointConfig(
        providerId = "preset:custom",
        presetId = "custom",
        label = "自訂",
        baseUrl = "http://127.0.0.1:11434/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "local",
        apiKeyRef = "provider_key/preset:custom",
    )

    private fun newVm(
        store: EndpointStore,
        policy: InMemoryPolicyStore = InMemoryPolicyStore(),
        fake: KeyReadFakeProvider = KeyReadFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        },
    ): ChatViewModel {
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            vault.putKey("preset:openai", "test-key-123".toCharArray())
            vault.putKey("preset:custom", "test-key-456".toCharArray())
        }
        val factory = ChatSessionFactory(
            // S1: T0/T1 share one store so a T1 flip is genuinely enforced.
            policy = policy,
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
        )
        return chatViewModels.own(ChatViewModel(store, factory, policy))
    }

    private fun onMain(block: () -> Unit) {
        runBlocking { chatMain.run(block) }
    }

    private fun awaitTrue(timeoutMs: Long = 8000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) error("timed out waiting for condition")
            Thread.sleep(10)
        }
    }

    private fun awaitCompletedTurn(vm: ChatViewModel, expectedTurns: Int) {
        awaitTrue {
            vm.sessionState.value.status == ChatStatus.IDLE &&
                vm.sessionState.value.messages.count { it.role == "user" } >= expectedTurns &&
                vm.sessionState.value.messages.count { it.role == "assistant" && !it.isPartial } >= expectedTurns
        }
    }

    @Test
    fun askWithoutConfirmationDeniesReadWithZeroRequests() {
        val store = newStore()
        runBlocking { store.save(endpointA()) }
        val fake = KeyReadFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "must not send"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val vm = newVm(store, InMemoryPolicyStore(), fake)
        onMain { vm.sendDirect("hi") }
        // Default ruleset is key.read ASK: without a bound confirmation the
        // vault must never be read and no provider call may happen.
        awaitTrue { vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" }
        assertEquals(0, fake.streamCalls.get())
        assertTrue(vm.sessionState.value.messages.isEmpty())
    }

    @Test
    fun confirmedAskAllowsOneSendThenRequiresFreshConfirmation() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val fake = KeyReadFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val vm = newVm(store, InMemoryPolicyStore(), fake)
        assertTrue(vm.confirmKeyReadForTest())
        onMain { vm.sendDirect("one") }
        awaitCompletedTurn(vm, expectedTurns = 1)
        assertEquals(1, fake.streamCalls.get())
        // The confirmation is single-use: the next send needs a fresh one.
        onMain { vm.sendDirect("two") }
        awaitTrue { vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" }
        assertEquals(1, fake.streamCalls.get())
    }

    @Test
    fun confirmationBoundToPreviousEndpointIsRejectedAfterSwitch() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val fake = KeyReadFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val vm = newVm(store, InMemoryPolicyStore(), fake)
        assertTrue(vm.confirmKeyReadForTest())
        onMain { vm.sendDirect("on A") }
        awaitCompletedTurn(vm, expectedTurns = 1)
        assertEquals(1, fake.streamCalls.get())
        // Fresh confirmation bound to A, then the endpoint moves to B.
        assertTrue(vm.confirmKeyReadForTest())
        store.save(endpointB())
        awaitTrue { vm.sessionState.value.messages.isEmpty() }
        onMain { vm.sendDirect("on B") }
        awaitTrue { vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" }
        assertEquals(1, fake.streamCalls.get())
        // Re-confirming on B allows exactly one send there.
        assertTrue(vm.confirmKeyReadForTest())
        onMain { vm.sendDirect("on B again") }
        awaitTrue { fake.streamCalls.get() == 2 }
        awaitTrue { vm.messages.value.count { it.role == "user" } == 2 }
    }

    @Test
    fun denyAlwaysWinsAndBurnsConfirmation() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val fake = KeyReadFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "must not send"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val policy = InMemoryPolicyStore()
        val vm = newVm(store, policy, fake)
        assertTrue(vm.confirmKeyReadForTest())
        policy.setRule(PolicyRule("key.read:*", Verdict.DENY, 20))
        onMain { vm.sendDirect("hi") }
        awaitTrue { vm.notice.value == "POLICY_DENIED" }
        assertEquals(0, fake.streamCalls.get())
        // The minted confirmation was burned by the DENY: back on ASK the
        // next send still requires a fresh confirmation.
        policy.removeRule("key.read:*")
        onMain { vm.sendDirect("hi again") }
        awaitTrue { vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" }
        assertEquals(0, fake.streamCalls.get())
    }

    @Test
    fun policyReadFailureDeniesClosedWithZeroRequests() {
        val store = newStore()
        runBlocking { store.save(endpointA()) }
        val fake = KeyReadFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "must not send"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val throwing = object : PolicyStore {
            override fun evaluate(action: String, resource: String) =
                PolicyDecision(Verdict.DENY, null, System.currentTimeMillis())

            override suspend fun setRule(rule: PolicyRule) = Unit
            override suspend fun removeRule(pattern: String) = Unit
            override suspend fun listRules(): List<PolicyRule> = emptyList()
            override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision =
                throw IllegalStateException("synthetic policy read failure")
        }
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking { vault.putKey("preset:openai", "test-key-123".toCharArray()) }
        // S1: T0/T1 share one store (see newRig).
        val factory = ChatSessionFactory(
            policy = throwing,
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
        )
        val vm = chatViewModels.own(ChatViewModel(store, factory, throwing))
        onMain { vm.sendDirect("hi") }
        awaitTrue { vm.notice.value == "POLICY_DENIED" }
        assertEquals(0, fake.streamCalls.get())
        assertTrue(vm.sessionState.value.messages.isEmpty())
    }

    @Test
    fun explicitAllowProceedsWithoutConfirmation() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val fake = KeyReadFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val policy = InMemoryPolicyStore()
        policy.setRule(PolicyRule("key.read:*", Verdict.ALLOW, 20))
        val vm = newVm(store, policy, fake)
        onMain { vm.sendDirect("hi") }
        awaitCompletedTurn(vm, expectedTurns = 1)
        assertEquals(1, fake.streamCalls.get())
    }

    @Test
    fun policyFailureBurnsConfirmationRequiresFreshConfirm() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val fake = KeyReadFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "must not send"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val delegate = InMemoryPolicyStore()
        val failPolicy = AtomicBoolean(true)
        val policy = object : PolicyStore {
            override fun evaluate(action: String, resource: String): PolicyDecision =
                delegate.evaluate(action, resource)

            override suspend fun setRule(rule: PolicyRule) = delegate.setRule(rule)
            override suspend fun removeRule(pattern: String) = delegate.removeRule(pattern)
            override suspend fun listRules(): List<PolicyRule> = delegate.listRules()
            override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision {
                if (failPolicy.get()) throw IllegalStateException("synthetic policy read failure")
                return delegate.evaluateFresh(action, resource)
            }
        }
        val backend = EncryptedPrefsVault(InMemoryPrefs())
        backend.putKey("preset:openai", "test-key-123".toCharArray())
        backend.putKey("preset:custom", "test-key-456".toCharArray())
        val getKeyCalls = AtomicInteger(0)
        val countingVault = object : KeyVault {
            override suspend fun putKey(providerId: String, apiKey: CharArray) =
                backend.putKey(providerId, apiKey)

            override suspend fun getKey(providerId: String): CharArray? {
                getKeyCalls.incrementAndGet()
                return backend.getKey(providerId)
            }

            override suspend fun deleteKey(providerId: String) = backend.deleteKey(providerId)
            override suspend fun hasKey(providerId: String): Boolean = backend.hasKey(providerId)
        }
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = VaultSource { countingVault },
            buildProvider = { _, _ -> fake },
        )
        val vm = chatViewModels.own(ChatViewModel(store, factory, policy))
        assertTrue(vm.confirmKeyReadForTest())
        onMain { vm.sendDirect("hi") }
        awaitTrue { vm.notice.value == "POLICY_DENIED" }
        assertEquals(0, fake.streamCalls.get())
        assertEquals(0, getKeyCalls.get())
        // Back on ASK without a fresh confirmation: the burned slot stays empty.
        failPolicy.set(false)
        onMain { vm.sendDirect("hi again") }
        awaitTrue { vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" }
        assertEquals(0, fake.streamCalls.get())
        assertEquals(0, getKeyCalls.get())
        assertTrue(vm.sessionState.value.messages.isEmpty())
    }

    @Test
    fun dismissedConfirmationRequiresFreshConfirm() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val fake = KeyReadFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "must not send"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val backend = EncryptedPrefsVault(InMemoryPrefs())
        backend.putKey("preset:openai", "test-key-123".toCharArray())
        backend.putKey("preset:custom", "test-key-456".toCharArray())
        val getKeyCalls = AtomicInteger(0)
        val countingVault = object : KeyVault {
            override suspend fun putKey(providerId: String, apiKey: CharArray) =
                backend.putKey(providerId, apiKey)

            override suspend fun getKey(providerId: String): CharArray? {
                getKeyCalls.incrementAndGet()
                return backend.getKey(providerId)
            }

            override suspend fun deleteKey(providerId: String) = backend.deleteKey(providerId)
            override suspend fun hasKey(providerId: String): Boolean = backend.hasKey(providerId)
        }
        // S1: T0/T1 share one store (see newRig).
        val policy = InMemoryPolicyStore()
        val factory = ChatSessionFactory(
            policy = policy,
            vaultSource = VaultSource { countingVault },
            buildProvider = { _, _ -> fake },
        )
        val vm = chatViewModels.own(ChatViewModel(store, factory, policy))
        assertTrue(vm.confirmKeyReadForTest())
        onMain { vm.dismissKeyReadConfirm() }
        onMain { vm.sendDirect("hi") }
        awaitTrue { vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" }
        assertEquals(0, fake.streamCalls.get())
        assertEquals(0, getKeyCalls.get())
        assertTrue(vm.sessionState.value.messages.isEmpty())
    }

    /**
     * S6 capture-boundary T1: T0 key.read ALLOW passes authorize, then the
     * factory T1 recheck flips to DENY. Must surface POLICY_DENIED with the
     * text restored, zero vault reads and zero provider calls.
     */
    @Test
    fun captureDenyAfterT0AllowMapsToPolicyDeniedWithZeroVaultReads() {
        val store = newStore()
        runBlocking { store.save(endpointA()) }
        val fake = KeyReadFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "must not send"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val delegate = InMemoryPolicyStore()
        runBlocking { delegate.setRule(PolicyRule("key.read:*", Verdict.ALLOW, 20)) }
        val freshCalls = AtomicInteger(0)
        val flipPolicy = object : PolicyStore {
            override fun evaluate(action: String, resource: String) =
                delegate.evaluate(action, resource)

            override suspend fun setRule(rule: PolicyRule) = delegate.setRule(rule)
            override suspend fun removeRule(pattern: String) = delegate.removeRule(pattern)
            override suspend fun listRules(): List<PolicyRule> = delegate.listRules()
            override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision {
                val n = freshCalls.incrementAndGet()
                if (n == 1) return delegate.evaluateFresh(action, resource)
                return PolicyDecision(Verdict.DENY, null, System.currentTimeMillis())
            }
        }
        val backend = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            backend.putKey("preset:openai", "test-key-123".toCharArray())
            backend.putKey("preset:custom", "test-key-456".toCharArray())
        }
        val getKeyCalls = AtomicInteger(0)
        val countingVault = object : KeyVault {
            override suspend fun putKey(providerId: String, apiKey: CharArray) =
                backend.putKey(providerId, apiKey)

            override suspend fun getKey(providerId: String): CharArray? {
                getKeyCalls.incrementAndGet()
                return backend.getKey(providerId)
            }

            override suspend fun deleteKey(providerId: String) = backend.deleteKey(providerId)
            override suspend fun hasKey(providerId: String): Boolean = backend.hasKey(providerId)
        }
        val factory = ChatSessionFactory(
            policy = flipPolicy,
            vaultSource = VaultSource { countingVault },
            buildProvider = { _, _ -> fake },
        )
        val vm = chatViewModels.own(ChatViewModel(store, factory, flipPolicy))
        onMain { vm.sendDirect("hi") }
        awaitTrue { vm.notice.value == "POLICY_DENIED" }
        assertEquals("POLICY_DENIED", vm.notice.value)
        assertEquals("hi", vm.input.value)
        assertEquals(0, getKeyCalls.get())
        assertEquals(0, fake.streamCalls.get())
        assertTrue(vm.sessionState.value.messages.isEmpty())
    }

    /**
     * S6 stale gate: a stale binding aborts before the T1 recheck and the
     * vault read (zero getKey, zero provider build/stream).
     */
    @Test
    fun staleBindingDoesNotReadVault() = runBlocking {
        val backend = EncryptedPrefsVault(InMemoryPrefs())
        backend.putKey("preset:openai", "test-key-123".toCharArray())
        val getKeyCalls = AtomicInteger(0)
        val countingVault = object : KeyVault {
            override suspend fun putKey(providerId: String, apiKey: CharArray) =
                backend.putKey(providerId, apiKey)

            override suspend fun getKey(providerId: String): CharArray? {
                getKeyCalls.incrementAndGet()
                return backend.getKey(providerId)
            }

            override suspend fun deleteKey(providerId: String) = backend.deleteKey(providerId)
            override suspend fun hasKey(providerId: String): Boolean = backend.hasKey(providerId)
        }
        val policy = InMemoryPolicyStore()
        policy.setRule(PolicyRule("key.read:*", Verdict.ALLOW, 20))
        val fake = KeyReadFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "must not send"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val buildCalls = AtomicInteger(0)
        val factory = ChatSessionFactory(
            policy = policy,
            vaultSource = VaultSource { countingVault },
            buildProvider = { _, _ ->
                buildCalls.incrementAndGet()
                fake
            },
        )
        val failure = try {
            factory.create(endpointA(), "hi", allowAsk = false, keyIsCurrent = { false })
            null
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            cancelled
        }
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertEquals("ENDPOINT_BINDING_STALE", failure?.message)
        assertEquals(0, getKeyCalls.get())
        assertEquals(0, buildCalls.get())
        assertEquals(0, fake.streamCalls.get())
    }

    // ---- S1 shared rig: T0/T1 share one store; vault + build counted ----

    private data class CountingRig(
        val vm: ChatViewModel,
        val fake: KeyReadFakeProvider,
        val getKeyCalls: AtomicInteger,
        val buildCalls: AtomicInteger,
    )

    private fun newRig(
        store: EndpointStore,
        policy: PolicyStore,
        fake: KeyReadFakeProvider = KeyReadFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        },
        transcripts: dev.librepocket.session.SessionStore? = null,
    ): CountingRig {
        val backend = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            backend.putKey("preset:openai", "test-key-123".toCharArray())
            backend.putKey("preset:custom", "test-key-456".toCharArray())
        }
        val getKeyCalls = AtomicInteger(0)
        val countingVault = object : KeyVault {
            override suspend fun putKey(providerId: String, apiKey: CharArray) =
                backend.putKey(providerId, apiKey)

            override suspend fun getKey(providerId: String): CharArray? {
                getKeyCalls.incrementAndGet()
                return backend.getKey(providerId)
            }

            override suspend fun deleteKey(providerId: String) = backend.deleteKey(providerId)
            override suspend fun hasKey(providerId: String): Boolean = backend.hasKey(providerId)
        }
        val buildCalls = AtomicInteger(0)
        val factory = ChatSessionFactory(
            policy = policy,
            vaultSource = VaultSource { countingVault },
            buildProvider = { _, _ ->
                buildCalls.incrementAndGet()
                fake
            },
            sessionStores = transcripts?.let { SessionStoreSource { it } },
        )
        return CountingRig(chatViewModels.own(ChatViewModel(store, factory, policy)), fake, getKeyCalls, buildCalls)
    }

    /**
     * S1: scripted key.read verdicts by evaluateFresh call ordinal
     * (T0, then T1, ...); every other action delegates so chat.send /
     * provider.call keep the shared ALLOW defaults.
     *
     * Sequential-use only: the ordinal assumes T0/T1 run one send at a
     * time (no concurrent sends racing the counter). All S1 scripted
     * tests send strictly sequentially.
     */
    private class ScriptedKeyReadPolicy(
        private val delegate: InMemoryPolicyStore,
        private val script: List<Verdict>,
    ) : PolicyStore {
        private val calls = AtomicInteger(0)
        override fun evaluate(action: String, resource: String): PolicyDecision =
            delegate.evaluate(action, resource)

        override suspend fun setRule(rule: PolicyRule) = delegate.setRule(rule)
        override suspend fun removeRule(pattern: String) = delegate.removeRule(pattern)
        override suspend fun listRules(): List<PolicyRule> = delegate.listRules()
        override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision {
            if (action != "key.read") return delegate.evaluateFresh(action, resource)
            val n = calls.incrementAndGet()
            return PolicyDecision(script[minOf(n - 1, script.size - 1)], null, System.currentTimeMillis())
        }
    }

    private fun mustNotSendFake() = KeyReadFakeProvider { _ ->
        flow {
            emit(StreamEvent.TextDelta(0, 0, "must not send"))
            emit(StreamEvent.Done("stop"))
        }
    }

    /**
     * S1 create path: T0 ALLOW passes, then T1 flips to ASK without proof.
     * Must deny closed (POLICY_DENIED) with zero vault reads, zero builds,
     * zero provider calls.
     */
    @Test
    fun allowToAskFlipDeniesCreateWithZeroReads() {
        val store = newStore()
        runBlocking { store.save(endpointA()) }
        val fake = mustNotSendFake()
        val policy = ScriptedKeyReadPolicy(InMemoryPolicyStore(), listOf(Verdict.ALLOW, Verdict.ASK))
        val rig = newRig(store, policy, fake)
        onMain { rig.vm.sendDirect("hi") }
        awaitTrue { rig.vm.notice.value == "POLICY_DENIED" }
        assertEquals("hi", rig.vm.input.value)
        assertEquals(0, rig.getKeyCalls.get())
        assertEquals(0, rig.buildCalls.get())
        assertEquals(0, fake.streamCalls.get())
        assertTrue(rig.vm.sessionState.value.messages.isEmpty())
    }

    /**
     * S1 open path: same ALLOW→ASK flip on resume. Must deny closed with
     * zero vault reads, zero builds, zero provider calls, no session bound.
     */
    @Test
    fun allowToAskFlipDeniesOpenWithZeroReads() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old", "gpt-4o-mini")
        transcripts.appendEvent(
            TranscriptEvent(sessionId = sid, runId = "r1", kind = "user", text = "q1", createdAt = 1L),
        )
        transcripts.appendEvent(
            TranscriptEvent(sessionId = sid, runId = "r1", kind = "assistant", text = "a1", createdAt = 2L),
        )
        val fake = mustNotSendFake()
        val policy = ScriptedKeyReadPolicy(InMemoryPolicyStore(), listOf(Verdict.ALLOW, Verdict.ASK))
        val rig = newRig(store, policy, fake, transcripts)
        onMain { rig.vm.openSession(sid) }
        awaitTrue { rig.vm.notice.value == "POLICY_DENIED" }
        assertNull(rig.vm.currentSessionId.value)
        assertEquals(0, rig.getKeyCalls.get())
        assertEquals(0, rig.buildCalls.get())
        assertEquals(0, fake.streamCalls.get())
        assertTrue(rig.vm.messages.value.isEmpty())
    }

    /**
     * S1: ASK with a bound one-shot consent passes T1 ASK exactly once;
     * the next send needs a fresh confirmation with no new vault reads.
     */
    @Test
    fun consentedAskToAskPassesOnceThenRequiresFreshConfirm() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val fake = KeyReadFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val rig = newRig(store, InMemoryPolicyStore(), fake)
        assertTrue(rig.vm.confirmKeyReadForTest())
        onMain { rig.vm.sendDirect("one") }
        awaitCompletedTurn(rig.vm, expectedTurns = 1)
        // S1 absolute pass cost: exactly one vault read, one build, one stream.
        assertEquals(1, rig.getKeyCalls.get())
        assertEquals(1, rig.buildCalls.get())
        assertEquals(1, fake.streamCalls.get())
        assertEquals("", rig.vm.input.value)
        val getKeyAfterPass = rig.getKeyCalls.get()
        val buildAfterPass = rig.buildCalls.get()
        onMain { rig.vm.sendDirect("two") }
        awaitTrue { rig.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" }
        assertEquals("two", rig.vm.input.value)
        assertEquals(1, fake.streamCalls.get())
        assertEquals(getKeyAfterPass, rig.getKeyCalls.get())
        assertEquals(buildAfterPass, rig.buildCalls.get())
    }

    /**
     * S1: T0 ALLOW then T1 DENY. Must deny closed with zero vault reads,
     * zero builds, zero provider calls, and the T1 deny must burn the
     * slot: back on ASK the next send still needs a fresh confirmation
     * (no DENY residual replay).
     */
    @Test
    fun allowToDenyRefusedWithZeroReads() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val fake = mustNotSendFake()
        val policy = ScriptedKeyReadPolicy(
            InMemoryPolicyStore(),
            listOf(Verdict.ALLOW, Verdict.DENY, Verdict.ASK),
        )
        val rig = newRig(store, policy, fake)
        assertTrue(rig.vm.confirmKeyReadForTest())
        onMain { rig.vm.sendDirect("hi") }
        awaitTrue { rig.vm.notice.value == "POLICY_DENIED" }
        assertEquals("hi", rig.vm.input.value)
        assertEquals(0, rig.getKeyCalls.get())
        assertEquals(0, rig.buildCalls.get())
        assertEquals(0, fake.streamCalls.get())
        assertTrue(rig.vm.sessionState.value.messages.isEmpty())
        // T1 DENY burned the slot: back on ASK a fresh confirm is required.
        onMain { rig.vm.sendDirect("hi again") }
        awaitTrue { rig.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" }
        assertEquals(0, rig.getKeyCalls.get())
        assertEquals(0, rig.buildCalls.get())
        assertEquals(0, fake.streamCalls.get())
    }

    /**
     * S1: T0 consented ASK then T1 DENY. Must deny closed with zero reads,
     * and the slot stays burned: back on ASK the next send still needs a
     * fresh confirmation (no residual pass).
     */
    @Test
    fun consentedAskToDenyRefusedAndBurnsResidual() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val fake = mustNotSendFake()
        val policy = ScriptedKeyReadPolicy(
            InMemoryPolicyStore(),
            listOf(Verdict.ASK, Verdict.DENY, Verdict.ASK),
        )
        val rig = newRig(store, policy, fake)
        assertTrue(rig.vm.confirmKeyReadForTest())
        onMain { rig.vm.sendDirect("hi") }
        awaitTrue { rig.vm.notice.value == "POLICY_DENIED" }
        assertEquals(0, rig.getKeyCalls.get())
        assertEquals(0, rig.buildCalls.get())
        assertEquals(0, fake.streamCalls.get())
        onMain { rig.vm.sendDirect("hi again") }
        awaitTrue { rig.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" }
        assertEquals(0, rig.getKeyCalls.get())
        assertEquals(0, rig.buildCalls.get())
        assertEquals(0, fake.streamCalls.get())
        assertTrue(rig.vm.sessionState.value.messages.isEmpty())
    }

    /**
     * S1 replay: a consent minted before an ALLOW send is burned by that
     * ALLOW authorization, so after flipping back to ASK the next send
     * still requires a fresh confirmation (no replayed pass).
     */
    @Test
    fun allowSendBurnsConsentNoReplayAfterFlipBackToAsk() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val fake = KeyReadFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val policy = InMemoryPolicyStore()
        policy.setRule(PolicyRule("key.read:*", Verdict.ALLOW, 20))
        val rig = newRig(store, policy, fake)
        assertTrue(rig.vm.confirmKeyReadForTest())
        onMain { rig.vm.sendDirect("one") }
        awaitCompletedTurn(rig.vm, expectedTurns = 1)
        // S1 absolute pass cost under ALLOW (consent burned, not used).
        assertEquals(1, rig.getKeyCalls.get())
        assertEquals(1, rig.buildCalls.get())
        assertEquals(1, fake.streamCalls.get())
        policy.removeRule("key.read:*")
        val getKeyBefore = rig.getKeyCalls.get()
        val buildBefore = rig.buildCalls.get()
        onMain { rig.vm.sendDirect("two") }
        awaitTrue { rig.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" }
        assertEquals(1, fake.streamCalls.get())
        assertEquals(getKeyBefore, rig.getKeyCalls.get())
        assertEquals(buildBefore, rig.buildCalls.get())
        assertEquals("two", rig.vm.input.value)
    }

    /**
     * S1 open symmetry of [allowToDenyRefusedWithZeroReads]: T0 ALLOW then
     * T1 DENY on resume. Must deny closed with zero reads and burn the
     * slot, so the next send still needs a fresh confirmation.
     */
    @Test
    fun allowToDenyRefusedOpenWithZeroReads() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old", "gpt-4o-mini")
        transcripts.appendEvent(
            TranscriptEvent(sessionId = sid, runId = "r1", kind = "user", text = "q1", createdAt = 1L),
        )
        transcripts.appendEvent(
            TranscriptEvent(sessionId = sid, runId = "r1", kind = "assistant", text = "a1", createdAt = 2L),
        )
        val fake = mustNotSendFake()
        val policy = ScriptedKeyReadPolicy(
            InMemoryPolicyStore(),
            listOf(Verdict.ALLOW, Verdict.DENY, Verdict.ASK),
        )
        val rig = newRig(store, policy, fake, transcripts)
        assertTrue(rig.vm.confirmKeyReadForTest())
        onMain { rig.vm.openSession(sid) }
        awaitTrue { rig.vm.notice.value == "POLICY_DENIED" }
        assertNull(rig.vm.currentSessionId.value)
        assertEquals(0, rig.getKeyCalls.get())
        assertEquals(0, rig.buildCalls.get())
        assertEquals(0, fake.streamCalls.get())
        assertTrue(rig.vm.messages.value.isEmpty())
        // Burned slot: the next send still needs a fresh confirmation.
        onMain { rig.vm.sendDirect("hi again") }
        awaitTrue { rig.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" }
        assertEquals("hi again", rig.vm.input.value)
        assertEquals(0, rig.getKeyCalls.get())
        assertEquals(0, rig.buildCalls.get())
        assertEquals(0, fake.streamCalls.get())
    }

    /**
     * S1: T1 DENY must burn a consent minted between T0 and T1 (send path).
     * The gate holds the factory T1 recheck while the test mints; the T1
     * deny then burns that slot. Load-bearing for the launchSendOp T1
     * takePending: deleting it leaves a residual consent that passes the
     * next ASK send (this test goes red: no ConfirmRequired, reads > 0).
     */
    @Test
    fun t1DenyBurnsInterT0T1ConsentSend() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val fake = mustNotSendFake()
        val t1Entered = CompletableDeferred<Unit>()
        val releaseT1 = CompletableDeferred<Unit>()
        val rig = newRig(store, GatedDenyKeyReadPolicy(t1Entered, releaseT1), fake)
        onMain { rig.vm.sendDirect("hi") }
        withTimeout(5_000) { t1Entered.await() }
        // Minted after T0 ALLOW, before the gated T1 DENY lands.
        assertTrue(rig.vm.confirmKeyReadForTest())
        releaseT1.complete(Unit)
        awaitTrue { rig.vm.notice.value == "POLICY_DENIED" }
        assertEquals("hi", rig.vm.input.value)
        assertEquals(0, rig.getKeyCalls.get())
        assertEquals(0, rig.buildCalls.get())
        assertEquals(0, fake.streamCalls.get())
        assertTrue(rig.vm.sessionState.value.messages.isEmpty())
        // Residual must be gone: next ASK send still needs a fresh confirm.
        onMain { rig.vm.sendDirect("hi again") }
        awaitTrue { rig.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" }
        assertEquals("hi again", rig.vm.input.value)
        assertEquals(0, rig.getKeyCalls.get())
        assertEquals(0, rig.buildCalls.get())
        assertEquals(0, fake.streamCalls.get())
    }

    /**
     * S1: same inter-T0T1 burn on the steer path. Load-bearing for the
     * steer() T1 takePending.
     */
    @Test
    fun t1DenyBurnsInterT0T1ConsentSteer() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val fake = mustNotSendFake()
        val t1Entered = CompletableDeferred<Unit>()
        val releaseT1 = CompletableDeferred<Unit>()
        val rig = newRig(store, GatedDenyKeyReadPolicy(t1Entered, releaseT1), fake)
        onMain { rig.vm.steer("hi") }
        withTimeout(5_000) { t1Entered.await() }
        assertTrue(rig.vm.confirmKeyReadForTest())
        releaseT1.complete(Unit)
        awaitTrue { rig.vm.notice.value == "POLICY_DENIED" }
        assertEquals("hi", rig.vm.input.value)
        assertEquals(0, rig.getKeyCalls.get())
        assertEquals(0, rig.buildCalls.get())
        assertEquals(0, fake.streamCalls.get())
        onMain { rig.vm.sendDirect("hi again") }
        awaitTrue { rig.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" }
        assertEquals(0, rig.getKeyCalls.get())
        assertEquals(0, rig.buildCalls.get())
        assertEquals(0, fake.streamCalls.get())
    }

    /**
     * S1: same inter-T0T1 burn on the open path. Load-bearing for the
     * openSession T1 takePending.
     */
    @Test
    fun t1DenyBurnsInterT0T1ConsentOpen() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old", "gpt-4o-mini")
        transcripts.appendEvent(
            TranscriptEvent(sessionId = sid, runId = "r1", kind = "user", text = "q1", createdAt = 1L),
        )
        transcripts.appendEvent(
            TranscriptEvent(sessionId = sid, runId = "r1", kind = "assistant", text = "a1", createdAt = 2L),
        )
        val fake = mustNotSendFake()
        val t1Entered = CompletableDeferred<Unit>()
        val releaseT1 = CompletableDeferred<Unit>()
        val rig = newRig(store, GatedDenyKeyReadPolicy(t1Entered, releaseT1), fake, transcripts)
        onMain { rig.vm.openSession(sid) }
        withTimeout(5_000) { t1Entered.await() }
        assertTrue(rig.vm.confirmKeyReadForTest())
        releaseT1.complete(Unit)
        awaitTrue { rig.vm.notice.value == "POLICY_DENIED" }
        assertNull(rig.vm.currentSessionId.value)
        assertEquals(0, rig.getKeyCalls.get())
        assertEquals(0, rig.buildCalls.get())
        assertEquals(0, fake.streamCalls.get())
        onMain { rig.vm.sendDirect("hi again") }
        awaitTrue { rig.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" }
        assertEquals(0, rig.getKeyCalls.get())
        assertEquals(0, rig.buildCalls.get())
        assertEquals(0, fake.streamCalls.get())
    }

    /**
     * S1: ASK consented then ASK on open passes exactly once (absolute
     * 1/1/0 cost), then needs a fresh confirmation. The history load is
     * gated so the consent is minted after openSession's invalidate but
     * before authorize (a pre-minted slot is burned by the invalidate).
     */
    @Test
    fun consentedAskOpenPassesOnceThenRequiresFreshConfirm() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val backing = FakeSessionStore()
        val sid = backing.createSession("old", "gpt-4o-mini")
        backing.appendEvent(
            TranscriptEvent(sessionId = sid, runId = "r1", kind = "user", text = "q1", createdAt = 1L),
        )
        backing.appendEvent(
            TranscriptEvent(sessionId = sid, runId = "r1", kind = "assistant", text = "a1", createdAt = 2L),
        )
        val historyEntered = CompletableDeferred<Unit>()
        val historyRelease = CompletableDeferred<Unit>()
        val gated = GatedLoadTranscriptStore(backing, historyEntered, historyRelease)
        val fake = KeyReadFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val rig = newRig(store, InMemoryPolicyStore(), fake, gated)
        onMain { rig.vm.openSession(sid) }
        withTimeout(5_000) { historyEntered.await() }
        assertTrue(rig.vm.confirmKeyReadForTest())
        historyRelease.complete(Unit)
        awaitTrue { rig.vm.currentSessionId.value == sid }
        awaitTrue { rig.vm.messages.value.size == 2 }
        // S1 absolute open cost: one vault read + one build, zero streams.
        assertEquals(1, rig.getKeyCalls.get())
        assertEquals(1, rig.buildCalls.get())
        assertEquals(0, fake.streamCalls.get())
        // Slot consumed: the next send needs a fresh confirmation.
        onMain { rig.vm.sendDirect("next") }
        awaitTrue { rig.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" }
        assertEquals("next", rig.vm.input.value)
        assertEquals(1, rig.getKeyCalls.get())
        assertEquals(1, rig.buildCalls.get())
        assertEquals(0, fake.streamCalls.get())
    }

    /**
     * S1: a minted consent is burned by newChat invalidation: the next send
     * still needs a fresh confirmation with zero reads.
     */
    @Test
    fun confirmBurnedByNewChatRequiresFreshConfirm() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val fake = mustNotSendFake()
        val rig = newRig(store, InMemoryPolicyStore(), fake)
        assertTrue(rig.vm.confirmKeyReadForTest())
        onMain { rig.vm.newChat() }
        onMain { rig.vm.sendDirect("hi") }
        awaitTrue { rig.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" }
        assertEquals("hi", rig.vm.input.value)
        assertEquals(0, rig.getKeyCalls.get())
        assertEquals(0, rig.buildCalls.get())
        assertEquals(0, fake.streamCalls.get())
        assertTrue(rig.vm.sessionState.value.messages.isEmpty())
    }

    /**
     * S1: a minted consent is burned by openSession invalidation: the open
     * itself needs a fresh confirmation (zero reads) and so does the next
     * send.
     */
    @Test
    fun confirmBurnedByOpenInvalidationRequiresFreshConfirm() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old", "gpt-4o-mini")
        transcripts.appendEvent(
            TranscriptEvent(sessionId = sid, runId = "r1", kind = "user", text = "q1", createdAt = 1L),
        )
        transcripts.appendEvent(
            TranscriptEvent(sessionId = sid, runId = "r1", kind = "assistant", text = "a1", createdAt = 2L),
        )
        val fake = mustNotSendFake()
        val rig = newRig(store, InMemoryPolicyStore(), fake, transcripts)
        assertTrue(rig.vm.confirmKeyReadForTest())
        onMain { rig.vm.openSession(sid) }
        awaitTrue { rig.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" }
        assertNull(rig.vm.currentSessionId.value)
        assertEquals(0, rig.getKeyCalls.get())
        assertEquals(0, rig.buildCalls.get())
        assertEquals(0, fake.streamCalls.get())
        onMain { rig.vm.sendDirect("hi") }
        awaitTrue { rig.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" }
        assertEquals(0, rig.getKeyCalls.get())
        assertEquals(0, rig.buildCalls.get())
        assertEquals(0, fake.streamCalls.get())
    }

    /**
     * S1: T0 ALLOW then gated T1 DENY. T0 passes while T1 blocks; a consent
     * minted in the window must be burned by the T1 deny path.
     * Sequential-use only (one gated send at a time).
     */
    private class GatedDenyKeyReadPolicy(
        private val t1Entered: CompletableDeferred<Unit>,
        private val releaseT1: CompletableDeferred<Unit>,
    ) : PolicyStore {
        private val delegate = InMemoryPolicyStore()
        private val calls = AtomicInteger(0)
        override fun evaluate(action: String, resource: String): PolicyDecision =
            delegate.evaluate(action, resource)

        override suspend fun setRule(rule: PolicyRule) = delegate.setRule(rule)
        override suspend fun removeRule(pattern: String) = delegate.removeRule(pattern)
        override suspend fun listRules(): List<PolicyRule> = delegate.listRules()
        override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision {
            if (action != "key.read") return delegate.evaluateFresh(action, resource)
            return when (calls.incrementAndGet()) {
                1 -> PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
                2 -> {
                    t1Entered.complete(Unit)
                    releaseT1.await()
                    PolicyDecision(Verdict.DENY, null, System.currentTimeMillis())
                }
                else -> PolicyDecision(Verdict.ASK, null, System.currentTimeMillis())
            }
        }
    }

    /**
     * S1: gates transcript history load so openSession's post-invalidate
     * consent mint lands before authorize. Delegates everything else.
     */
    private class GatedLoadTranscriptStore(
        private val delegate: FakeSessionStore,
        private val entered: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : dev.librepocket.session.SessionStore by delegate {
        override suspend fun loadEvents(
            sessionId: String,
            afterSeq: Long,
            limit: Int,
        ): List<TranscriptEvent> {
            entered.complete(Unit)
            release.await()
            return delegate.loadEvents(sessionId, afterSeq, limit)
        }
    }
}
