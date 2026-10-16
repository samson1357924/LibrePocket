package dev.librepocket.agent.ui.chat

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.agent.ui.setup.SetupValidation
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class KeyReadUiFakeProvider(
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
 * PR81 Stage S7：key.read 確認 UI 接線的 Robolectric/Compose 驗收。
 *
 * - dialog 顯示不可變快照（唯讀欄位，不含金鑰內容）。
 * - 確認／拒絕／取消行為（皆不 auto-send；確認後需手動重送）。
 * - mint 在途按鈕 disable（double-tap 防護）。
 * - 過期顯示已失效。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatKeyReadConfirmUiTest {

    private val tmpDirs = ArrayList<java.io.File>()
    private val scopes = ArrayList<CoroutineScope>()
    private val vmStore = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        try {
            vmStore.clear()
        } finally {
            Dispatchers.resetMain()
            scopes.forEach { it.cancel() }
            tmpDirs.forEach { it.deleteRecursively() }
        }
    }

    @get:Rule
    val composeRule = createComposeRule()

    private fun endpoint() = EndpointConfig(
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

    private fun newVm(fake: KeyReadUiFakeProvider): Pair<ChatViewModel, EndpointStore> {
        val dir = Files.createTempDirectory("keyread-confirm-ui").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val store = EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "keyread-ui.preferences_pb") },
            ),
        )
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            vault.putKey("preset:openai", "test-key-123".toCharArray())
            store.save(endpoint())
        }
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
        )
        // Default ruleset: key.read ASK.
        val vm = ChatViewModel(store, factory, InMemoryPolicyStore())
        vmStore.put("chat-keyread-confirm", vm)
        return vm to store
    }

    private fun setChat(vm: ChatViewModel) {
        composeRule.setContent {
            ChatScreen(
                padding = PaddingValues(0.dp),
                viewModel = vm,
            )
        }
    }

    private fun awaitNotice(vm: ChatViewModel, code: String, timeoutMs: Long = 8000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (vm.notice.value != code) {
            if (System.currentTimeMillis() > end) error("timed out waiting for notice=$code got=${vm.notice.value}")
            Thread.sleep(10)
        }
    }

    private fun awaitInput(vm: ChatViewModel, text: String, timeoutMs: Long = 8000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (vm.input.value != text) {
            if (System.currentTimeMillis() > end) error("timed out waiting for input=$text got=${vm.input.value}")
            Thread.sleep(10)
        }
    }

    // 對話框的 binding 快照經 LaunchedEffect 內 suspend peek 才就緒：
    // 不以 waitForIdle 直斷，改以 waitUntil 等供應商節點出現／消失。
    private fun awaitSupplierNode(timeoutMs: Long = 8000) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            try {
                composeRule.onNodeWithText("供應商：preset:openai").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }

    private fun awaitSupplierNodeAbsent(timeoutMs: Long = 8000) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            composeRule.onAllNodesWithText("供應商：preset:openai")
                .fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun dialogShowsImmutableSnapshot() {
        val fake = KeyReadUiFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val (vm, _) = newVm(fake)
        setChat(vm)
        vm.sendDirect("hi")
        awaitNotice(vm, "KEY_READ_CONFIRM_REQUIRED")
        awaitSupplierNode()
        // 不可變快照欄位（唯讀，不含金鑰內容）。
        composeRule.onNodeWithText("讀取金鑰確認").assertIsDisplayed()
        composeRule.onNodeWithText("供應商：preset:openai").assertIsDisplayed()
        composeRule.onNodeWithText("伺服器位址：https://api.openai.com/v1").assertIsDisplayed()
        composeRule.onNodeWithText("來源：https://api.openai.com").assertIsDisplayed()
        composeRule.onNodeWithText("協定：CHAT_COMPLETIONS").assertIsDisplayed()
        composeRule.onNodeWithText("金鑰別名：provider_key/preset:openai").assertIsDisplayed()
        composeRule.onNodeWithText("設定版本：1").assertIsDisplayed()
        composeRule.onNodeWithText("模型：gpt-4o-mini").assertIsDisplayed()
        composeRule.onNodeWithText("確認本次讀取").assertIsDisplayed()
        composeRule.onNodeWithText("拒絕").assertIsDisplayed()
        composeRule.onNodeWithText("取消").assertIsDisplayed()
        // 本次未送出：零請求、restore 已在 input。
        assertEquals(0, fake.streamCalls)
        awaitInput(vm, "hi")
    }

    @Test
    fun confirmShowsConfirmedAndDoesNotAutoSend() {
        val fake = KeyReadUiFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val (vm, _) = newVm(fake)
        setChat(vm)
        vm.sendDirect("hi")
        awaitNotice(vm, "KEY_READ_CONFIRM_REQUIRED")
        awaitSupplierNode()
        composeRule.onNodeWithText("確認本次讀取").performClick()
        composeRule.waitUntil(timeoutMillis = 8000) {
            try {
                composeRule.onNodeWithText("已確認，請再按發送。").assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        // 確認後不 auto-send：零新增請求，restore 仍在 input，未建會話。
        assertEquals(0, fake.streamCalls)
        awaitInput(vm, "hi")
        assertTrue(vm.sessionState.value.messages.isEmpty())
        assertNull(vm.currentSessionId.value)
    }

    @Test
    fun rejectHidesDialogWithoutSendingAndStillRequiresConfirm() {
        val fake = KeyReadUiFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "must not send"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val (vm, _) = newVm(fake)
        setChat(vm)
        vm.sendDirect("hi")
        awaitNotice(vm, "KEY_READ_CONFIRM_REQUIRED")
        awaitSupplierNode()
        assertEquals(1L, vm.keyReadConfirmSequence.value)
        composeRule.onNodeWithText("拒絕").performClick()
        // 拒絕後 dialogs 關閉（快照欄位消失），不發送。
        awaitSupplierNodeAbsent()
        assertEquals(0, fake.streamCalls)
        awaitInput(vm, "hi")
        // 再次手動送出仍需確認、仍零請求。第二次 deny 使 sequence 1→2，
        // 即使中間 null 被 StateFlow conflation 吃掉（notice key 不變），
        // LaunchedEffect(notice, seq) 仍以 seq 變化重置本地 hidden 並重 peek，
        // 同一 composition 下對話框直接重現（無需重掛載）。
        vm.sendDirect("hi again")
        awaitNotice(vm, "KEY_READ_CONFIRM_REQUIRED")
        awaitInput(vm, "hi again")
        assertEquals(0, fake.streamCalls)
        assertEquals(1, vm.pendingRecoveryCount.value)
        assertEquals(2L, vm.keyReadConfirmSequence.value)
        awaitSupplierNode()
    }

    @Test
    fun cancelHidesDialogWithoutSending() {
        val fake = KeyReadUiFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "must not send"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val (vm, _) = newVm(fake)
        setChat(vm)
        vm.sendDirect("hi")
        awaitNotice(vm, "KEY_READ_CONFIRM_REQUIRED")
        awaitSupplierNode()
        composeRule.onNodeWithText("取消").performClick()
        awaitSupplierNodeAbsent()
        assertEquals(0, fake.streamCalls)
        awaitInput(vm, "hi")
    }

    @Test
    fun confirmingDisablesButtonsAgainstDoubleTap() {
        val binding = endpoint().toSessionBinding("gpt-4o-mini")
        composeRule.setContent {
            KeyReadConfirmDialog(
                binding = binding,
                loading = false,
                confirming = true,
                confirmResult = null,
                onConfirm = {},
                onReject = {},
                onCancel = {},
                onClose = {},
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("確認本次讀取").assertIsNotEnabled()
        composeRule.onNodeWithText("拒絕").assertIsNotEnabled()
        composeRule.onNodeWithText("取消").assertIsNotEnabled()
    }

    @Test
    fun expiredBindingShowsExpired() {
        composeRule.setContent {
            KeyReadConfirmDialog(
                binding = null,
                loading = false,
                confirming = false,
                confirmResult = null,
                onConfirm = {},
                onReject = {},
                onCancel = {},
                onClose = {},
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("已失效，請重新送出。").assertIsDisplayed()
        // 快照缺席時確認鈕不可用（不可 mint 空快照）。
        composeRule.onNodeWithText("確認本次讀取").assertIsNotEnabled()
    }

    @Test
    fun failedConfirmShowsExpired() {
        val binding = endpoint().toSessionBinding("gpt-4o-mini")
        composeRule.setContent {
            KeyReadConfirmDialog(
                binding = binding,
                loading = false,
                confirming = false,
                confirmResult = false,
                onConfirm = {},
                onReject = {},
                onCancel = {},
                onClose = {},
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("已失效，請重新送出。").assertIsDisplayed()
        composeRule.onNodeWithText("關閉").assertIsDisplayed()
    }

    @Test
    fun confirmedResultShowsClose() {
        val binding = endpoint().toSessionBinding("gpt-4o-mini")
        var closed = 0
        composeRule.setContent {
            KeyReadConfirmDialog(
                binding = binding,
                loading = false,
                confirming = false,
                confirmResult = true,
                onConfirm = {},
                onReject = {},
                onCancel = {},
                onClose = { closed++ },
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("已確認，請再按發送。").assertIsDisplayed()
        composeRule.onNodeWithText("關閉").assertIsEnabled()
        composeRule.onNodeWithText("關閉").performClick()
        composeRule.runOnIdle { assertEquals(1, closed) }
    }

    /**
     * S3：replacement 在對話框顯示期間保留 Confirm notice 並 bump sequence，
     * 對話框重 peek 新 binding（而非關閉或顯示 SEND_CANCELLED）。
     */
    @Test
    fun replacementRetainsDialogAndRepeeksNewBinding() {
        val fake = KeyReadUiFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val (vm, store) = newVm(fake)
        setChat(vm)
        vm.sendDirect("hi")
        awaitNotice(vm, "KEY_READ_CONFIRM_REQUIRED")
        awaitSupplierNode()
        assertEquals(1L, vm.keyReadConfirmSequence.value)
        runBlocking { store.save(endpointB()) }
        // S3：保留 Confirm（sequence 1→2），對話框重現 B 快照。
        awaitNotice(vm, "KEY_READ_CONFIRM_REQUIRED")
        val end = System.currentTimeMillis() + 8000
        while (vm.keyReadConfirmSequence.value != 2L) {
            if (System.currentTimeMillis() > end) error("timed out waiting for seq=2")
            Thread.sleep(10)
        }
        composeRule.waitUntil(timeoutMillis = 8000) {
            try {
                composeRule.onNodeWithText(
                    "供應商：" + SetupValidation.deriveEndpointId("custom", "http://127.0.0.1:11434/v1"),
                ).assertIsDisplayed()
                true
            } catch (_: AssertionError) {
                false
            }
        }
        composeRule.onNodeWithText("伺服器位址：http://127.0.0.1:11434/v1").assertIsDisplayed()
        composeRule.onNodeWithText("確認本次讀取").assertIsDisplayed()
        // 本次未送出：零請求、輸入保留。
        assertEquals(0, fake.streamCalls)
        awaitInput(vm, "hi")
    }
}
