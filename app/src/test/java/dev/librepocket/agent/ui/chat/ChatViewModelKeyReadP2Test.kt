package dev.librepocket.agent.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.chat.ChatStatus
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.keystore.KeyVault
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.FakeSessionStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class P2FakeProvider(
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
 * P2 回歸（BLOCKER-2）：`confirmKeyRead(expected, token)` 僅當三者全等才 mint。
 *
 * - 舊 notice→newChat→stale confirm→新 send 仍 ConfirmRequired 且零 vault 讀。
 * - openSession 新輪燒 token＋consent，舊 token 失效。
 * - 同端點 ABA（A→B→A 新 generation）舊 token 失效。
 * - 在途舊 confirm 晚於新 deny 不覆寫。
 * - mint→newChat 燒除方向保留（新 API）。
 */
class ChatViewModelKeyReadP2Test {
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
        val dir = Files.createTempDirectory("keyread-p2").toFile()
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

    private class Harness(
        val vm: ChatViewModel,
        val fake: P2FakeProvider,
        val getKeyCalls: AtomicInteger,
        val buildCalls: AtomicInteger,
    )

    private fun newHarness(
        store: EndpointStore,
        transcripts: FakeSessionStore? = null,
    ): Harness {
        val fake = P2FakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
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
        val buildCalls = AtomicInteger(0)
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = VaultSource { countingVault },
            buildProvider = { _, _ ->
                buildCalls.incrementAndGet()
                fake
            },
            sessionStores = transcripts?.let { SessionStoreSource { it } },
        )
        val vm = chatViewModels.own(ChatViewModel(store, factory, InMemoryPolicyStore()))
        return Harness(vm, fake, getKeyCalls, buildCalls)
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

    /**
     * P2-1：舊 notice→newChat→stale confirm 必 false（無 pending），新 send 仍
     * ConfirmRequired 且零 vault 讀。覆蓋 BLOCKER-2 主線：只比 binding＋當下
     * generation 即 mint 的舊行為在此應已失效。
     */
    @Test
    fun staleConfirmAfterNewChatFailsAndNextSendStillRequiresConfirm() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val h = newHarness(store)
        onMain { h.vm.sendDirect("hi") }
        awaitTrue { h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" && h.vm.input.value == "hi" }
        awaitTrue { h.vm.keyReadConfirmSequence.value == 1L }
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)
        val snapshotA = h.vm.peekKeyReadBinding()
        val tokenA = h.vm.peekKeyReadConfirmToken()
        assertNotNull(snapshotA)
        assertNotNull(tokenA)

        // newChat 新輪燒 token＋consent（sequence 永不重置，仍為 1）。
        onMain { h.vm.newChat() }
        awaitTrue { h.vm.notice.value == null }
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)
        assertNull(h.vm.peekKeyReadConfirmToken())

        // 舊 notice 的 stale tap：無 pending 必 false，零 vault 讀、不建 token。
        assertFalse(h.vm.confirmKeyRead(snapshotA, tokenA))
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)
        assertEquals(0, h.getKeyCalls.get())
        assertEquals(0, h.buildCalls.get())
        assertEquals(0, h.fake.streamCalls)

        // 新 send 仍需新確認（seq 1→2），零 vault 讀。
        onMain { h.vm.sendDirect("new") }
        awaitTrue {
            h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" &&
                h.vm.keyReadConfirmSequence.value == 2L
        }
        assertEquals(2L, h.vm.keyReadConfirmSequence.value)
        assertEquals(0, h.getKeyCalls.get())
        assertEquals(0, h.buildCalls.get())
        assertEquals(0, h.fake.streamCalls)

        // 新 token＋新 snapshot 可 mint，後續送出恰放行一次。
        val snapshotNew = h.vm.peekKeyReadBinding()
        val tokenNew = h.vm.peekKeyReadConfirmToken()
        assertNotNull(snapshotNew)
        assertNotNull(tokenNew)
        assertTrue(h.vm.confirmKeyRead(snapshotNew, tokenNew))
        onMain { h.vm.sendDirect("new") }
        awaitCompletedTurn(h.vm, expectedTurns = 1)
        assertEquals(1, h.fake.streamCalls)
        assertEquals(1, h.getKeyCalls.get())
    }

    /**
     * P2-2：openSession 新輪燒舊 token。首輪 open→ConfirmRequired(seq1,T1) 後，
     * 次輪 open 同 sid 燒 T1 並重發 seq2/T2；舊 (snapshot,T1) 必 false，新
     * (snapshot,T2) 才可 mint；全程零 vault 讀直到顯式 resume。
     */
    @Test
    fun openSessionNewRoundBurnsOldToken() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old", "gpt-4o-mini")
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
        val h = newHarness(store, transcripts)
        onMain { h.vm.openSession(sid) }
        awaitTrue {
            h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" &&
                h.vm.pendingOpenSessionId.value == sid
        }
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)
        val snapshotA = h.vm.peekKeyReadBinding()
        val token1 = h.vm.peekKeyReadConfirmToken()
        assertNotNull(snapshotA)
        assertNotNull(token1)

        // 次輪 open 同 sid：燒 T1，重發 seq2/T2。
        onMain { h.vm.openSession(sid) }
        awaitTrue {
            h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" &&
                h.vm.keyReadConfirmSequence.value == 2L &&
                h.vm.pendingOpenSessionId.value == sid
        }
        val token2 = h.vm.peekKeyReadConfirmToken()
        assertNotNull(token2)
        assertTrue(token1 != token2)

        // 舊 token 必 false，零 vault 讀。
        assertFalse(h.vm.confirmKeyRead(snapshotA, token1))
        assertEquals(2L, h.vm.keyReadConfirmSequence.value)
        assertEquals(0, h.getKeyCalls.get())
        assertEquals(0, h.buildCalls.get())
        assertNull(h.vm.currentSessionId.value)

        // 新 token 可 mint；顯式 resume 恰開啟一次。
        val snapshot2 = h.vm.peekKeyReadBinding()
        assertTrue(h.vm.confirmKeyRead(snapshot2, token2))
        onMain { h.vm.resumePendingOpen(sid) }
        awaitTrue { h.vm.currentSessionId.value == sid }
        assertEquals(1, h.getKeyCalls.get())
        assertEquals(1, h.buildCalls.get())
    }

    /**
     * P2-3：同端點 ABA（A→B→A）新 generation 舊 token 失效。A→B 保留 seq2，
     * B→A 保留 seq3；首輪 A 的 (snapshotA,token1) 在最終 A 世代必 false
     *（binding 世代＋sequence＋configRevision 三重失配至少其一），零 vault 讀；
     * 重 peek 最終 A 的新 token 才可 mint。
     */
    @Test
    fun abaSameEndpointOldTokenInvalid() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val h = newHarness(store)
        onMain { h.vm.sendDirect("hi") }
        awaitTrue {
            h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" &&
                h.vm.keyReadConfirmSequence.value == 1L
        }
        val snapshotA1 = h.vm.peekKeyReadBinding()
        val token1 = h.vm.peekKeyReadConfirmToken()
        assertNotNull(snapshotA1)
        assertNotNull(token1)

        store.save(endpointB())
        awaitTrue {
            h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" &&
                h.vm.keyReadConfirmSequence.value == 2L
        }
        store.save(endpointA())
        awaitTrue {
            h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" &&
                h.vm.keyReadConfirmSequence.value == 3L
        }
        // 舊 token（首輪 A）在新世代 A 必 false。
        assertFalse(h.vm.confirmKeyRead(snapshotA1, token1))
        assertEquals(3L, h.vm.keyReadConfirmSequence.value)
        assertEquals(0, h.getKeyCalls.get())
        assertEquals(0, h.buildCalls.get())
        assertEquals(0, h.fake.streamCalls)

        // 最終世代重 peek 才可 mint。
        val snapshotA3 = h.vm.peekKeyReadBinding()
        val token3 = h.vm.peekKeyReadConfirmToken()
        assertNotNull(snapshotA3)
        assertNotNull(token3)
        assertTrue(h.vm.confirmKeyRead(snapshotA3, token3))
        onMain { h.vm.sendDirect("hi") }
        awaitCompletedTurn(h.vm, expectedTurns = 1)
        assertEquals(1, h.fake.streamCalls)
    }

    /**
     * P2-4：在途舊 confirm 晚於新 deny 不覆寫。seq1 舊 (snapshotA,tokenA) 在途時，
     * B 保留 seq2＋新 send 落地 seq3；舊完成必 false，notice 仍為最新、sequence
     * 不回退、零 vault 讀、輸入保留最新文本。
     */
    @Test
    fun staleInflightConfirmAfterNewDenyFails() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val h = newHarness(store)
        onMain { h.vm.sendDirect("one") }
        awaitTrue {
            h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" &&
                h.vm.input.value == "one" &&
                h.vm.keyReadConfirmSequence.value == 1L
        }
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)
        val snapshotA = h.vm.peekKeyReadBinding()
        val tokenA = h.vm.peekKeyReadConfirmToken()
        assertNotNull(snapshotA)
        assertNotNull(tokenA)

        store.save(endpointB())
        awaitTrue {
            h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" &&
                h.vm.keyReadConfirmSequence.value == 2L
        }
        val staleConfirm = async { h.vm.confirmKeyRead(snapshotA, tokenA) }
        onMain { h.vm.sendDirect("two") }
        awaitTrue {
            h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" &&
                h.vm.input.value == "two" &&
                h.vm.keyReadConfirmSequence.value == 3L
        }
        assertFalse(staleConfirm.await())
        assertEquals("KEY_READ_CONFIRM_REQUIRED", h.vm.notice.value)
        assertEquals(3L, h.vm.keyReadConfirmSequence.value)
        assertEquals("two", h.vm.input.value)
        assertEquals(0, h.getKeyCalls.get())
        assertEquals(0, h.buildCalls.get())
        assertEquals(0, h.fake.streamCalls)
        assertEquals(1, h.vm.pendingRecoveryCount.value)
    }

    /**
     * P2-5：mint→newChat 燒除方向保留（新 API）。deny seq1→confirm(T1) true→
     * newChat 燒→下次送出仍 ConfirmRequired(seq2) 且零 vault 讀。
     */
    @Test
    fun mintThenNewChatBurnsRequiringFreshConfirm() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val h = newHarness(store)
        onMain { h.vm.sendDirect("hi") }
        awaitTrue {
            h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" &&
                h.vm.keyReadConfirmSequence.value == 1L
        }
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)
        val snapshot = h.vm.peekKeyReadBinding()
        val token = h.vm.peekKeyReadConfirmToken()
        assertNotNull(snapshot)
        assertNotNull(token)
        assertTrue(h.vm.confirmKeyRead(snapshot, token))
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)

        onMain { h.vm.newChat() }
        awaitTrue { h.vm.notice.value == null }
        assertNull(h.vm.peekKeyReadConfirmToken())
        onMain { h.vm.sendDirect("hi") }
        awaitTrue {
            h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" &&
                h.vm.keyReadConfirmSequence.value == 2L
        }
        assertEquals(2L, h.vm.keyReadConfirmSequence.value)
        assertEquals(0, h.getKeyCalls.get())
        assertEquals(0, h.buildCalls.get())
        assertEquals(0, h.fake.streamCalls)
    }

    /**
     * P2-6：dismiss 丟棄 token 後 stale tap 必 false。deny seq1→confirm(T1)
     * true→dismiss 丟棄→stale (snapshot,T1) false 且零 vault 讀；下次送出仍
     * ConfirmRequired(seq2)。
     */
    @Test
    fun dismissedTokenStaleTapFails() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val h = newHarness(store)
        onMain { h.vm.sendDirect("hi") }
        awaitTrue {
            h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" &&
                h.vm.keyReadConfirmSequence.value == 1L
        }
        val snapshot = h.vm.peekKeyReadBinding()
        val token = h.vm.peekKeyReadConfirmToken()
        assertNotNull(snapshot)
        assertNotNull(token)
        assertTrue(h.vm.confirmKeyRead(snapshot, token))
        onMain { h.vm.dismissKeyReadConfirm() }
        assertNull(h.vm.peekKeyReadConfirmToken())
        assertFalse(h.vm.confirmKeyRead(snapshot, token))
        assertEquals(0, h.getKeyCalls.get())
        assertEquals(0, h.buildCalls.get())
        onMain { h.vm.sendDirect("hi") }
        awaitTrue {
            h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" &&
                h.vm.keyReadConfirmSequence.value == 2L
        }
        assertEquals(0, h.getKeyCalls.get())
        assertEquals(0, h.buildCalls.get())
    }
}
