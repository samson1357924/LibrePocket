package dev.librepocket.agent.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.chat.ChatStatus
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class KeyReadFakeProvider(
    var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    @Volatile var streamCalls = 0

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        streamCalls++
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
        val dir = Files.createTempDirectory("keyread-ask-test").toFile()
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
            vault.putKey("preset:openai", "sk-test-key-123".toCharArray())
            vault.putKey("preset:custom", "sk-test-key-456".toCharArray())
        }
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
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
        assertEquals(0, fake.streamCalls)
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
        assertTrue(vm.confirmKeyRead())
        onMain { vm.sendDirect("one") }
        awaitCompletedTurn(vm, expectedTurns = 1)
        assertEquals(1, fake.streamCalls)
        // The confirmation is single-use: the next send needs a fresh one.
        onMain { vm.sendDirect("two") }
        awaitTrue { vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" }
        assertEquals(1, fake.streamCalls)
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
        assertTrue(vm.confirmKeyRead())
        onMain { vm.sendDirect("on A") }
        awaitCompletedTurn(vm, expectedTurns = 1)
        assertEquals(1, fake.streamCalls)
        // Fresh confirmation bound to A, then the endpoint moves to B.
        assertTrue(vm.confirmKeyRead())
        store.save(endpointB())
        awaitTrue { vm.sessionState.value.messages.isEmpty() }
        onMain { vm.sendDirect("on B") }
        awaitTrue { vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" }
        assertEquals(1, fake.streamCalls)
        // Re-confirming on B allows exactly one send there.
        assertTrue(vm.confirmKeyRead())
        onMain { vm.sendDirect("on B again") }
        awaitTrue { fake.streamCalls == 2 }
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
        assertTrue(vm.confirmKeyRead())
        policy.setRule(PolicyRule("key.read:*", Verdict.DENY, 20))
        onMain { vm.sendDirect("hi") }
        awaitTrue { vm.notice.value == "POLICY_DENIED" }
        assertEquals(0, fake.streamCalls)
        // The minted confirmation was burned by the DENY: back on ASK the
        // next send still requires a fresh confirmation.
        policy.removeRule("key.read:*")
        onMain { vm.sendDirect("hi again") }
        awaitTrue { vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" }
        assertEquals(0, fake.streamCalls)
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
        runBlocking { vault.putKey("preset:openai", "sk-test-key-123".toCharArray()) }
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
        )
        val vm = chatViewModels.own(ChatViewModel(store, factory, throwing))
        onMain { vm.sendDirect("hi") }
        awaitTrue { vm.notice.value == "POLICY_DENIED" }
        assertEquals(0, fake.streamCalls)
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
        assertEquals(1, fake.streamCalls)
    }
}
