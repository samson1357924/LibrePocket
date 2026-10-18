package dev.librepocket.agent.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.agent.ui.setup.SetupValidation
import dev.librepocket.chat.ChatStatus
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.keystore.KeyVault
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
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

private class S3FakeProvider(
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
 * S3 回歸：UI 批准快照與 mint 綁定一致、非同步覆寫防護、replacement 保留 notice。
 *
 * - 過期快照 confirm 失敗 closed 且不 mint（slot 保持空）。
 * - 在途舊 confirm 完成不覆寫新世代的 pending。
 * - replacement 在 ConfirmRequired 顯示期間保留 notice 並 bump sequence。
 */
class ChatViewModelKeyReadS3Test {
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
        val dir = Files.createTempDirectory("keyread-s3").toFile()
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
        // Stage1: stable per-endpoint entity id derived from the custom baseUrl.
        providerId = SetupValidation.deriveEndpointId("custom", "http://127.0.0.1:11434/v1"),
        presetId = "custom",
        label = "自訂",
        baseUrl = "http://127.0.0.1:11434/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "local",
        apiKeyRef = SetupValidation.deriveApiKeyRef(
            SetupValidation.deriveEndpointId("custom", "http://127.0.0.1:11434/v1"),
        ),
    )

    private class Harness(
        val vm: ChatViewModel,
        val fake: S3FakeProvider,
        val getKeyCalls: AtomicInteger,
        val buildCalls: AtomicInteger,
    )

    private fun newHarness(store: EndpointStore): Harness {
        val fake = S3FakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val backend = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            backend.putKey("preset:openai", "test-key-123".toCharArray())
            backend.putKey(SetupValidation.deriveEndpointId("custom", "http://127.0.0.1:11434/v1"), "test-key-456".toCharArray())
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
     * S3-1：peek A → save B → confirm(expected=A) == false 且不 mint（slot 空：
     * 下次送出仍 ConfirmRequired、零 vault 讀取）；重 peek B → confirm(expected=B)
     * == true，後續送出放行。
     */
    @Test
    fun staleSnapshotConfirmFailsClosedWithoutMinting() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val h = newHarness(store)
        onMain { h.vm.sendDirect("hi") }
        awaitTrue {
            h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" &&
                h.vm.input.value == "hi" &&
                h.vm.keyReadConfirmSequence.value == 1L
        }
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)
        val snapshotA = h.vm.peekKeyReadBinding()
        assertNotNull(snapshotA)
        assertEquals("preset:openai", snapshotA!!.providerId)
        val tokenA = h.vm.peekKeyReadConfirmToken()
        assertNotNull(tokenA)

        // 端點切到 B：S3 保留 Confirm notice 並 bump（重 peek B）。
        store.save(endpointB())
        awaitTrue {
            h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" &&
                h.vm.keyReadConfirmSequence.value == 2L
        }
        // 過期快照＋舊 token 確認：失敗 closed，不 mint（sequence 不動）。
        assertFalse(h.vm.confirmKeyRead(snapshotA, tokenA))
        assertEquals(2L, h.vm.keyReadConfirmSequence.value)
        assertEquals(0, h.getKeyCalls.get())
        assertEquals(0, h.buildCalls.get())

        // Slot 保持空：下次送出仍需確認、零 vault 讀取。
        onMain { h.vm.sendDirect("hi") }
        awaitTrue {
            h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" &&
                h.vm.keyReadConfirmSequence.value == 3L
        }
        assertEquals(0, h.getKeyCalls.get())
        assertEquals(0, h.buildCalls.get())
        assertEquals(0, h.fake.streamCalls)

        // 重 peek B＋新 token 並確認：放行，後續送出恰一次成功。
        val snapshotB = h.vm.peekKeyReadBinding()
        assertNotNull(snapshotB)
        assertEquals(SetupValidation.deriveEndpointId("custom", "http://127.0.0.1:11434/v1"), snapshotB!!.providerId)
        val tokenB = h.vm.peekKeyReadConfirmToken()
        assertNotNull(tokenB)
        assertTrue(h.vm.confirmKeyRead(snapshotB, tokenB))
        assertEquals(3L, h.vm.keyReadConfirmSequence.value)
        onMain { h.vm.sendDirect("hi") }
        awaitCompletedTurn(h.vm, expectedTurns = 1)
        assertEquals(1, h.fake.streamCalls)
    }

    /**
     * S3-2：seq1 的舊 confirm 在途時 seq2 的新 deny 落地，舊完成（過期快照）
     * 不覆寫新 pending：notice 仍為最新 ConfirmRequired、sequence 不回退、
     * 零 vault 讀取、輸入保留最新文本。
     */
    @Test
    fun staleInflightConfirmDoesNotOverwriteNewerPending() = runBlocking {
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
        assertNotNull(snapshotA)
        val tokenA = h.vm.peekKeyReadConfirmToken()
        assertNotNull(tokenA)

        // 切到 B 並等待保留落地（seq2），此後 snapshotA＋tokenA 已過期。
        store.save(endpointB())
        awaitTrue {
            h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" &&
                h.vm.keyReadConfirmSequence.value == 2L
        }
        // 舊快照＋舊 token 的 confirm 在途時，新的 deny 交錯落地。
        val staleConfirm = async { h.vm.confirmKeyRead(snapshotA, tokenA) }
        onMain { h.vm.sendDirect("two") }
        awaitTrue {
            h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" &&
                h.vm.input.value == "two" &&
                h.vm.keyReadConfirmSequence.value == 3L
        }
        // 舊完成失敗 closed，不覆寫新 pending。
        assertFalse(staleConfirm.await())
        assertEquals("KEY_READ_CONFIRM_REQUIRED", h.vm.notice.value)
        assertEquals(3L, h.vm.keyReadConfirmSequence.value)
        assertEquals("two", h.vm.input.value)
        assertEquals(0, h.getKeyCalls.get())
        assertEquals(0, h.buildCalls.get())
        assertEquals(0, h.fake.streamCalls)
        // 舊文本仍在 outbox 可恢復。
        assertEquals(1, h.vm.pendingRecoveryCount.value)
    }

    /**
     * S3-3：replacement 在 ConfirmRequired 顯示期間保留 notice 並 bump
     * sequence（而非清成 null/SEND_CANCELLED），強制重 peek 新 binding。
     */
    @Test
    fun replacementRetainsConfirmNoticeAndBumpsSequence() = runBlocking {
        val store = newStore()
        store.save(endpointA())
        val h = newHarness(store)
        onMain { h.vm.sendDirect("hi") }
        awaitTrue { h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" && h.vm.input.value == "hi" }
        assertEquals(1L, h.vm.keyReadConfirmSequence.value)

        store.save(endpointB())
        // 保留：notice 不被清成 null，也不是 SEND_CANCELLED；sequence +1。
        awaitTrue {
            h.vm.notice.value == "KEY_READ_CONFIRM_REQUIRED" &&
                h.vm.keyReadConfirmSequence.value == 2L
        }
        // 輸入保留、零傳輸。
        assertEquals("hi", h.vm.input.value)
        assertEquals(0, h.getKeyCalls.get())
        assertEquals(0, h.buildCalls.get())
        assertEquals(0, h.fake.streamCalls)
        // 重 peek 已是新 binding。
        val snapshot = h.vm.peekKeyReadBinding()
        assertNotNull(snapshot)
        assertEquals(SetupValidation.deriveEndpointId("custom", "http://127.0.0.1:11434/v1"), snapshot!!.providerId)
        assertEquals("http://127.0.0.1:11434/v1", snapshot.effectiveBaseUrl)
        // 顯示期間的 null 快照／null token 確認永遠失敗 closed。
        assertFalse(h.vm.confirmKeyRead(null, null))
        assertFalse(h.vm.confirmKeyRead(snapshot, null))
        assertEquals(2L, h.vm.keyReadConfirmSequence.value)
        assertEquals(0, h.getKeyCalls.get())
    }
}
