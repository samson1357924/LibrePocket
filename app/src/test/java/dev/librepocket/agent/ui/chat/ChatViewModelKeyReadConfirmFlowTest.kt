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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class ConfirmFlowFakeProvider(
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
 * PR81 Stage S7：DefaultPolicyRuleset（key.read ASK）下的確認流程。
 *
 * 覆蓋：首次送出零請求→ConfirmRequired、confirm 後手動重送一次放行、連續兩次
 * （第二次需重 confirm）、開歷史會話無 confirm 不建 transport、拒絕／改端點後
 * 不可送出、peek 只讀不 mint。
 */
class ChatViewModelKeyReadConfirmFlowTest {
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
        val dir = Files.createTempDirectory("keyread-flow").toFile()
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
        val fake: ConfirmFlowFakeProvider,
        val getKeyCalls: AtomicInteger,
        val buildCalls: AtomicInteger,
    )

    private fun newHarness(
        store: EndpointStore,
        transcripts: FakeSessionStore? = null,
    ): Harness {
        val fake = ConfirmFlowFakeProvider { _ ->
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
        // Default ruleset: key.read ASK (no explicit rule).
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

    @Test
    fun firstSendWithoutConfirmDeniesWithZeroRequestsAndRestoresInput() {
        val store = newStore()
        runBlocking { store.save(endpointA()) }
        val h = newHarness(store)
        assertEquals(0L, h.vm.keyReadConfirmSequence.value)
        onMain { h.vm.sendDirect("hi") }
        awaitTrue { h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" && h.vm.input.value == "hi" }
        // 每次 deny sequence +1。
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)
        assertEquals(0, h.fake.streamCalls)
        assertEquals(0, h.getKeyCalls.get())
        assertEquals(0, h.buildCalls.get())
        assertTrue(h.vm.sessionState.value.messages.isEmpty())
        // restore 已在 input：手動重送前內容仍在。
        assertEquals("hi", h.vm.input.value)
    }

    @Test
    fun confirmThenManualResendAllowsExactlyOnce() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val h = newHarness(store)
        assertEquals(0L, h.vm.keyReadConfirmSequence.value)
        assertTrue(h.vm.confirmKeyRead())
        // confirm 不 bump sequence。
        assertEquals(0L, h.vm.keyReadConfirmSequence.value)
        // 確認僅 mint，不 auto-send：送出前零請求。
        assertEquals(0, h.fake.streamCalls)
        onMain { h.vm.sendDirect("one") }
        awaitCompletedTurn(h.vm, expectedTurns = 1)
        assertEquals(1, h.fake.streamCalls)
        assertEquals(0L, h.vm.keyReadConfirmSequence.value)
        // 確認後不 auto-send 已由上式保證（恰一次 stream）；連續第二次需重 confirm。
        // 拒絕半段零新增請求：快照本輪前計數，deny 後必須不變。
        val getKeyBeforeDeny = h.getKeyCalls.get()
        val buildBeforeDeny = h.buildCalls.get()
        onMain { h.vm.sendDirect("two") }
        awaitTrue { h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" && h.vm.input.value == "two" }
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)
        assertEquals(1, h.fake.streamCalls)
        assertEquals(getKeyBeforeDeny, h.getKeyCalls.get())
        assertEquals(buildBeforeDeny, h.buildCalls.get())
        assertEquals("two", h.vm.input.value)
        // 重 confirm 後手動重送放行（confirm 不 bump）。
        assertTrue(h.vm.confirmKeyRead())
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)
        assertEquals(1, h.fake.streamCalls)
        onMain { h.vm.sendDirect("two") }
        awaitCompletedTurn(h.vm, expectedTurns = 2)
        assertEquals(2, h.fake.streamCalls)
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)
    }

    @Test
    fun openHistoryWithoutConfirmBuildsNoTransport() = runBlocking {
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
        assertEquals(0L, h.vm.keyReadConfirmSequence.value)
        onMain { h.vm.openSession(sid) }
        awaitTrue { h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" }
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)
        assertEquals(0, h.fake.streamCalls)
        assertEquals(0, h.getKeyCalls.get())
        assertEquals(0, h.buildCalls.get())
        assertNull(h.vm.currentSessionId.value)
        assertTrue(h.vm.messages.value.isEmpty())
    }

    @Test
    fun dismissThenSendStillRequiresConfirmWithZeroRequests() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val h = newHarness(store)
        assertEquals(0L, h.vm.keyReadConfirmSequence.value)
        assertTrue(h.vm.confirmKeyRead())
        assertEquals(0L, h.vm.keyReadConfirmSequence.value)
        assertEquals(0, h.fake.streamCalls)
        onMain { h.vm.dismissKeyReadConfirm() }
        // dismiss 不 bump sequence。
        assertEquals(0L, h.vm.keyReadConfirmSequence.value)
        onMain { h.vm.sendDirect("hi") }
        awaitTrue { h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" && h.vm.input.value == "hi" }
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)
        assertEquals(0, h.fake.streamCalls)
        assertEquals(0, h.getKeyCalls.get())
        assertEquals(0, h.buildCalls.get())
        assertEquals("hi", h.vm.input.value)
    }

    @Test
    fun switchEndpointAfterConfirmStillRequiresFreshConfirm() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val h = newHarness(store)
        assertEquals(0L, h.vm.keyReadConfirmSequence.value)
        assertTrue(h.vm.confirmKeyRead())
        assertEquals(0L, h.vm.keyReadConfirmSequence.value)
        assertEquals(0, h.fake.streamCalls)
        store.save(endpointB())
        // 交錯固定：save B 的 observe/invalidate 與送出競態。首輪送出可能先撞
        // Stale（SEND_CANCELLED_ENDPOINT_CHANGED），重試送出直到穩態
        // ConfirmRequired + restore（input 回填 "on B"）。
        val steadyEnd = System.currentTimeMillis() + 8000
        while (h.vm.notice.value != "KEY_READ_CONFIRM_REQUIRED" || h.vm.input.value != "on B") {
            if (System.currentTimeMillis() > steadyEnd) {
                error(
                    "timed out waiting for steady ConfirmRequired on B " +
                        "got notice=${h.vm.notice.value} input=${h.vm.input.value}",
                )
            }
            onMain { h.vm.sendDirect("on B") }
            awaitTrue {
                h.vm.input.value == "on B" &&
                    (h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" ||
                        h.vm.notice.value == "SEND_CANCELLED_ENDPOINT_CHANGED")
            }
        }
        assertEquals(0, h.fake.streamCalls)
        assertEquals(0, h.getKeyCalls.get())
        assertEquals(0, h.buildCalls.get())
        // B 上的 deny 使 sequence 0→1（Stale 路徑不 bump）。
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)
        // 在 B 上重 confirm 後恰放行一次（confirm 不 bump）。
        assertTrue(h.vm.confirmKeyRead())
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)
        assertEquals(0, h.fake.streamCalls)
        onMain { h.vm.sendDirect("on B again") }
        awaitCompletedTurn(h.vm, expectedTurns = 1)
        assertEquals(1, h.fake.streamCalls)
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)
        assertEquals(1, h.vm.sessionState.value.messages.count { it.role == "user" })
        assertEquals(
            1,
            h.vm.sessionState.value.messages.count { it.role == "assistant" && !it.isPartial },
        )
    }

    @Test
    fun peekReturnsSnapshotWithoutMintingConsent() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val h = newHarness(store)
        assertEquals(0L, h.vm.keyReadConfirmSequence.value)
        val snapshot = h.vm.peekKeyReadBinding()
        // peek 不 bump sequence。
        assertEquals(0L, h.vm.keyReadConfirmSequence.value)
        assertNotNull(snapshot)
        assertEquals("preset:openai", snapshot!!.providerId)
        assertEquals("https://api.openai.com/v1", snapshot.effectiveBaseUrl)
        assertEquals("https://api.openai.com", snapshot.origin)
        assertEquals(ProviderProtocol.CHAT_COMPLETIONS, snapshot.protocol)
        assertEquals("provider_key/preset:openai", snapshot.apiKeyRef)
        assertEquals("gpt-4o-mini", snapshot.model)
        // 快照設定版本與 store 一致（單次 save 後為 1L）。
        assertEquals(1L, store.currentRevision())
        assertEquals(store.currentRevision(), snapshot.configRevision)
        // peek 不 mint：直接送出仍需確認、零請求。
        onMain { h.vm.sendDirect("hi") }
        awaitTrue { h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" && h.vm.input.value == "hi" }
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)
        assertEquals(0, h.fake.streamCalls)
        assertEquals(0, h.getKeyCalls.get())
        assertEquals(0, h.buildCalls.get())
    }

    @Test
    fun peekReturnsNullWhenNoEndpoint() = runBlocking {
        val store = newStore()
        val h = newHarness(store)
        assertEquals(0L, h.vm.keyReadConfirmSequence.value)
        assertNull(h.vm.peekKeyReadBinding())
        assertEquals(0L, h.vm.keyReadConfirmSequence.value)
        assertEquals(false, h.vm.confirmKeyRead())
        assertEquals(0L, h.vm.keyReadConfirmSequence.value)
    }

    private suspend fun seedHistory(transcripts: FakeSessionStore): String {
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
        return sid
    }

    /**
     * S2：歷史 session 在預設 ASK 下確認後可顯式 resume（不經第二次
     * invalidate，不燒剛 mint 的 consent）。open→ConfirmRequired(seq1,
     * 零 transport)→confirm→resume→attach（恰一次 getKey/build、history
     * 已載、sequence 不再+1）；後續送出仍需重確認。
     */
    @Test
    fun openConfirmThenExplicitResumeOpensOnceWithoutSequenceBump() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val transcripts = FakeSessionStore()
        val sid = seedHistory(transcripts)
        val h = newHarness(store, transcripts)
        onMain { h.vm.openSession(sid) }
        awaitTrue {
            h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" &&
                h.vm.pendingOpenSessionId.value == sid
        }
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)
        assertEquals(0, h.fake.streamCalls)
        assertEquals(0, h.getKeyCalls.get())
        assertEquals(0, h.buildCalls.get())
        assertNull(h.vm.currentSessionId.value)
        assertTrue(h.vm.messages.value.isEmpty())
        // 確認僅 mint，不 auto-open：零新增請求、sequence 不動。
        assertTrue(h.vm.confirmKeyRead())
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)
        assertEquals(0, h.fake.streamCalls)
        assertEquals(0, h.getKeyCalls.get())
        assertEquals(0, h.buildCalls.get())
        assertNull(h.vm.currentSessionId.value)
        // 顯式 resume：同一 generation，不經第二次 invalidate。
        onMain { h.vm.resumePendingOpen(sid) }
        awaitTrue { h.vm.currentSessionId.value == sid }
        awaitTrue { h.vm.messages.value.size == 2 }
        assertEquals("q1", h.vm.messages.value[0].text)
        assertEquals("a1", h.vm.messages.value[1].text)
        assertEquals(1, h.getKeyCalls.get())
        assertEquals(1, h.buildCalls.get())
        assertEquals(0, h.fake.streamCalls)
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)
        assertNull(h.vm.pendingOpenSessionId.value)
        assertNull(h.vm.notice.value)
        // Slot 已消耗一次：下次送出需重確認、零新增請求。
        onMain { h.vm.sendDirect("next") }
        awaitTrue { h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" && h.vm.input.value == "next" }
        assertEquals(2L, h.vm.keyReadConfirmSequence.value)
        assertEquals(1, h.getKeyCalls.get())
        assertEquals(1, h.buildCalls.get())
        assertEquals(0, h.fake.streamCalls)
    }

    /**
     * S2：cancel（dismiss 燒 consent）後顯式 resume 仍 ConfirmRequired
     *（sequence+1、零 transport、不開啟）。
     */
    @Test
    fun dismissThenResumeStillRequiresConfirmWithZeroRequests() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val transcripts = FakeSessionStore()
        val sid = seedHistory(transcripts)
        val h = newHarness(store, transcripts)
        onMain { h.vm.openSession(sid) }
        awaitTrue {
            h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" &&
                h.vm.pendingOpenSessionId.value == sid
        }
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)
        assertTrue(h.vm.confirmKeyRead())
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)
        onMain { h.vm.dismissKeyReadConfirm() }
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)
        // cancel 燒掉後 resume：無有效 consent，仍 ConfirmRequired。
        onMain { h.vm.resumePendingOpen(sid) }
        awaitTrue {
            h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" &&
                h.vm.keyReadConfirmSequence.value == 2L
        }
        assertNull(h.vm.currentSessionId.value)
        assertEquals(0, h.fake.streamCalls)
        assertEquals(0, h.getKeyCalls.get())
        assertEquals(0, h.buildCalls.get())
        assertTrue(h.vm.messages.value.isEmpty())
    }

    /**
     * S2：endpoint 切換後顯式 resume 仍 ConfirmRequired（binding 全欄位 fence，
     * 零 transport、不開啟）。
     */
    @Test
    fun switchEndpointThenResumeStillRequiresFreshConfirm() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val transcripts = FakeSessionStore()
        val sid = seedHistory(transcripts)
        val h = newHarness(store, transcripts)
        onMain { h.vm.openSession(sid) }
        awaitTrue {
            h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" &&
                h.vm.pendingOpenSessionId.value == sid
        }
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)
        assertTrue(h.vm.confirmKeyRead())
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)
        store.save(endpointB())
        // 等待取代失效落地（無未接受 op 時 notice 清空），再顯式 resume。
        awaitTrue { h.vm.notice.value == null }
        onMain { h.vm.resumePendingOpen(sid) }
        awaitTrue {
            h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" &&
                h.vm.keyReadConfirmSequence.value == 2L
        }
        assertNull(h.vm.currentSessionId.value)
        assertEquals(0, h.fake.streamCalls)
        assertEquals(0, h.getKeyCalls.get())
        assertEquals(0, h.buildCalls.get())
        assertTrue(h.vm.messages.value.isEmpty())
    }

    /**
     * S2：ABA（A→B→A 回到相同 binding 但新 generation）後顯式 resume 仍
     * ConfirmRequired（generation fence，零 transport、不開啟）。
     */
    @Test
    fun abaThenResumeStillRequiresFreshConfirm() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val transcripts = FakeSessionStore()
        val sid = seedHistory(transcripts)
        val h = newHarness(store, transcripts)
        onMain { h.vm.openSession(sid) }
        awaitTrue {
            h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" &&
                h.vm.pendingOpenSessionId.value == sid
        }
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)
        assertTrue(h.vm.confirmKeyRead())
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)
        store.save(endpointB())
        store.save(endpointA())
        onMain { h.vm.resumePendingOpen(sid) }
        awaitTrue {
            h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" &&
                h.vm.keyReadConfirmSequence.value == 2L
        }
        assertNull(h.vm.currentSessionId.value)
        assertEquals(0, h.fake.streamCalls)
        assertEquals(0, h.getKeyCalls.get())
        assertEquals(0, h.buildCalls.get())
        assertTrue(h.vm.messages.value.isEmpty())
    }

    /**
     * S2：resume 必須比對 sessionId：錯誤 sid 永不開啟（零 transport），
     * 原 pending 保留。
     */
    @Test
    fun resumeWithWrongSessionIdNeverOpens() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val transcripts = FakeSessionStore()
        val sid = seedHistory(transcripts)
        val other = transcripts.createSession("other", "gpt-4o-mini")
        val h = newHarness(store, transcripts)
        onMain { h.vm.openSession(sid) }
        awaitTrue {
            h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" &&
                h.vm.pendingOpenSessionId.value == sid
        }
        assertTrue(h.vm.confirmKeyRead())
        onMain { h.vm.resumePendingOpen(other) }
        awaitTrue { h.vm.keyReadConfirmSequence.value == 2L }
        assertNull(h.vm.currentSessionId.value)
        assertEquals(sid, h.vm.pendingOpenSessionId.value)
        assertEquals(0, h.fake.streamCalls)
        assertEquals(0, h.getKeyCalls.get())
        assertEquals(0, h.buildCalls.get())
        assertTrue(h.vm.messages.value.isEmpty())
    }
}
