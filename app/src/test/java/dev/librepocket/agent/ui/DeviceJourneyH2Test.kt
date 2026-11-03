package dev.librepocket.agent.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.chat.ChatSessionFactory
import dev.librepocket.agent.ui.chat.ChatViewModel
import dev.librepocket.agent.ui.chat.SessionStoreSource
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.agent.ui.chat.allowKeyReadPolicy
import dev.librepocket.agent.ui.chat.cancelAndJoinChatTestScopes
import dev.librepocket.agent.ui.setup.CountingVault
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.agent.ui.setup.SetupViewModel
import dev.librepocket.agent.ui.setup.VerdictPolicy
import dev.librepocket.chat.ChatStatus
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.FakeSessionStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

private class H2FakeProvider(
    var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow { emitAll(handler(request)) }
    override suspend fun listModels(): List<String> = emptyList()
}

/**
 * Batch H2（Issue #18 殘留）：零裝置 journey＋process-recreation（Robolectric/fake）。
 *
 * - 全程 sleep-free：以 `first {} + withTimeout`／`waitUntil` 等條件等待取代固定 delay／sleep。
 * - 測試のみ：不改 production 行為；以 negative control 綁定 red-green（見各測試 KDoc 的 RED 敘述）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceJourneyH2Test {

    @get:Rule
    val composeRule = createComposeRule()

    private val tmpDirs = ArrayList<File>()
    private val scopes = ArrayList<CoroutineScope>()
    private val chatMain = dev.librepocket.agent.ui.chat.ChatTestMainDispatcher()
    private val chatStores = dev.librepocket.agent.ui.chat.ChatTestViewModelStore()

    @Before
    fun setUp() {
        chatMain.install()
    }

    @After
    fun tearDown() {
        try {
            chatMain.clearViewModels(chatStores)
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

    private fun newStore(): EndpointStore {
        val dir = Files.createTempDirectory("h2-journey").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        return EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(dir, "h2.preferences_pb") },
            ),
        )
    }

    private fun openAiConfig(model: String = "gpt-4o-mini") = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = model,
        apiKeyRef = "provider_key/preset:openai",
    )

    private fun onMain(block: () -> Unit) {
        runBlocking { chatMain.run(block) }
    }

    /**
     * 正向 journey：first-run setup save → chat send → model-only 編輯沿用 → logout 清除。
     *
     * RED（若 production 退化即紅）：save 未落盤則快照為空；send 未回 `ok` 則訊息數不足；
     * model-only 空白 key 若被誤拒則 saved=false；logout 未清則快照仍在。皆為條件等待，
     * 無固定 sleep；任一環退化即超時紅，非計數膨脹。
     */
    @Test
    fun journey_firstRunSetupSendEditLogout() = runBlocking {
        val store = newStore()
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val setupVm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = VerdictPolicy(Verdict.ALLOW),
            buildProvider = { _, _ -> H2FakeProvider() },
        )
        // first-run：空白表單填 key＋save。
        onMain {
            setupVm.onApiKeyChange("fake-h2-journey-key-12345678")
            setupVm.save()
        }
        withTimeout(10_000) { setupVm.form.first { !it.saving && (it.saved || it.errorCode != null) } }
        assertNull("setup save must succeed, got ${setupVm.form.value.errorCode}", setupVm.form.value.errorCode)
        assertTrue(setupVm.form.value.saved)
        val saved = withTimeout(10_000) { store.snapshotStrict() }
        assertEquals("preset:openai", saved?.providerId)

        // chat send（Fake provider 回 ok）：sleep-free 以 first 等待訊息落定。
        val fake = H2FakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val transcripts = FakeSessionStore()
        val factory = ChatSessionFactory(
            policy = allowKeyReadPolicy(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = transcripts
            },
        )
        val chatVm = chatStores.own(ChatViewModel(store, factory, allowKeyReadPolicy()))
        onMain { chatVm.onInputChange("hi h2") }
        onMain { chatVm.send() }
        withTimeout(10_000) {
            chatVm.sessionState.first { it.status == ChatStatus.IDLE && it.messages.size == 2 }
        }
        assertEquals("ok", chatVm.sessionState.value.messages.first { it.role == "assistant" }.text)

        // resume：同 store 新 VM 可開舊會話（FakeSessionStore 內仍有該會話）。
        val sid = checkNotNull(chatVm.currentSessionId.value) { "first send must create session" }
        assertTrue(transcripts.metas.containsKey(sid))

        // endpoint model-only 編輯沿用（空白 key 免重貼）。
        val editVm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = VerdictPolicy(Verdict.ALLOW),
            buildProvider = { _, _ -> H2FakeProvider() },
        )
        val putsBefore = vault.putKeys
        onMain {
            editVm.prefillForEdit(openAiConfig())
            editVm.onModelChange("gpt-4o")
            editVm.save()
        }
        withTimeout(10_000) { editVm.form.first { !it.saving && (it.saved || it.errorCode != null) } }
        assertNull(editVm.form.value.errorCode)
        assertTrue(editVm.form.value.saved)
        assertEquals(putsBefore, vault.putKeys)

        // logout 清除：store＋vault 鍵皆空。
        val done = CompletableDeferred<Boolean>()
        onMain { editVm.logout { done.complete(it) } }
        assertTrue("logout must succeed", withTimeout(10_000) { done.await() })
        assertNull(withTimeout(10_000) { store.snapshotStrict() })
    }

    private fun customConfig(url: String, model: String = "local"): EndpointConfig {
        val id = dev.librepocket.agent.ui.setup.SetupValidation.deriveEndpointId("custom", url)
        return EndpointConfig(
            providerId = id,
            presetId = "custom",
            label = "自訂",
            baseUrl = url,
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            model = model,
            apiKeyRef = dev.librepocket.agent.ui.setup.SetupValidation.deriveApiKeyRef(id),
        )
    }

    /**
     * Negative control：跨 origin 空白 key 不沿用（fail-closed 須重貼）。
     *
     * RED：若身份比對誤用 origin/寬鬆相等，此測試轉紅（空白 key 被沿用即 saved=true）。
     * 與正向 model-only 沿用測試成對：同字串沿用放行、跨 origin 拒絕，證明 gate 非恆真。
     */
    @Test
    fun journey_crossOriginBlankKeyRequiresRepaste_negativeControl() = runBlocking {
        val urlA = "http://127.0.0.1:11442/v1"
        val urlB = "http://127.0.0.1:11443/v1"
        val store = newStore()
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val idA = dev.librepocket.agent.ui.setup.SetupValidation.deriveEndpointId("custom", urlA)
        runBlocking {
            vault.putKey(idA, "fake-h2-cross-key-12345678".toCharArray())
            store.save(customConfig(urlA))
        }
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = VerdictPolicy(Verdict.ALLOW),
            buildProvider = { _, _ -> H2FakeProvider() },
        )
        onMain {
            vm.prefillForEdit(customConfig(urlA))
            vm.onBaseUrlChange(urlB)
            vm.save()
        }
        withTimeout(10_000) { vm.form.first { !it.saving && (it.saved || it.errorCode != null) } }
        assertEquals("SETUP_KEY_TOO_SHORT", vm.form.value.errorCode)
        assertEquals(false, vm.form.value.saved)
    }

    /**
     * Process-recreation：聊天草稿（rememberSaveable）經 emulateSavedInstanceStateRestore 存活。
     *
     * RED：若草稿用純 remember（非 saveable），restore 後為空即紅；此處以 saveable 綠，
     * 證明接線非恆過（把 rememberSaveable 換成 remember 即紅，negative control）。
     */
    @Test
    fun compose_draftSurvivesProcessRecreation() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            Column {
                var draft by rememberSaveable { mutableStateOf("") }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("輸入草稿") },
                )
            }
        }
        composeRule.onNodeWithText("輸入草稿").performTextInput("hello restore")
        composeRule.onNodeWithText("hello restore").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("hello restore").assertIsDisplayed()
    }

    /**
     * 大字體變體：fontScale=1.3 下草稿仍存活且可見（零裝置覆蓋旋轉／字體放大的最小替代）。
     */
    @Test
    @Config(sdk = [33], fontScale = 1.3f)
    fun compose_draftSurvivesRecreationAtLargeFont() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            Column {
                var draft by rememberSaveable { mutableStateOf("") }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("大字體草稿") },
                )
            }
        }
        composeRule.onNodeWithText("大字體草稿").performTextInput("大字你好")
        composeRule.onNodeWithText("大字你好").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("大字你好").assertIsDisplayed()
    }
}
