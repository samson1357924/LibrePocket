package dev.librepocket.agent.ui.setup

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.keystore.KeyVault
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage F2（#18）：SETUP 路由的 ReadFailure 恢復入口。
 *
 * gate-first 硬導航後靜止在 SETUP 的使用者（舊守衛不導、Back 被攔、menu
 * disabled）須在頁內有可點的重試讀取；重試走同一安全 reconcile（只重讀＋
 * 回放，不碰表單草稿、不調 logout），恢復後草稿仍在。
 */
private class SetupFailReadsDataStore(
    private val delegate: DataStore<Preferences>,
    failReads: Int,
    private val error: IOException = IOException("STRICT_READ_FAILED"),
) : DataStore<Preferences> {
    private val remaining = AtomicInteger(failReads)
    override val data: Flow<Preferences>
        get() = flow {
            if (remaining.getAndDecrement() > 0) throw error
            emitAll(delegate.data)
        }
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        delegate.updateData(transform)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SetupScreenReadFailureTest {

    private val tmpDirs = ArrayList<java.io.File>()
    private val scopes = ArrayList<CoroutineScope>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        scopes.forEach { it.cancel() }
        tmpDirs.forEach { it.deleteRecursively() }
    }

    private val noProvider: (dev.librepocket.provider.ProviderConfig, dev.librepocket.provider.KeyProvider) -> LlmProvider =
        { _, _ -> error("no provider needed") as LlmProvider }

    private fun preseed(model: String = "m-old"): EndpointConfig = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = model,
        apiKeyRef = "provider_key/preset:openai",
    )

    private fun newVm(failReads: Int): SetupViewModel {
        val dir = Files.createTempDirectory("setup-read-failure").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val raw = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "setup-read-failure.preferences_pb") },
        )
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            EndpointStore(raw).save(preseed())
        }
        // SetupScreen 只收集 gate（唯一讀者）：首讀失敗即 ReadFailure。
        val store = EndpointStore(SetupFailReadsDataStore(raw, failReads))
        val vault: KeyVault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking { vault.putKey("preset:openai", "unit-test-old-key-123456".toCharArray()) }
        return SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
    }

    @get:Rule
    val composeRule = createComposeRule()

    private fun onSetupArea(text: String, substring: Boolean = false) =
        composeRule.onNodeWithText(text, substring = substring).performScrollTo()

    @Test
    fun readFailureShowsRetryAndRecoversWithDraftKept() {
        // SETUP（firstRun，無碼）→ gate ReadFailure → 重試可見可點 →
        // 同一 VM 恢復 Ready(A)，草稿保留（isFirstRun 不影響按鍵可達）。
        val vm = newVm(failReads = 2)
        composeRule.setContent {
            SetupScreen(
                padding = PaddingValues(0.dp),
                isFirstRun = true,
                onSaved = {},
                viewModel = vm,
            )
        }
        // 前兩次讀各敗一次（gate 首讀＋worker 首次 strict 讀，順序無關）：
        // gate 黏住 ReadFailure，worker 落 UNKNOWN；重試後全成功。
        val first = runBlocking {
            withTimeout(10_000) { vm.gate.first { it !is EndpointGate.Loading } }
        }
        assertTrue("transient failure must surface ReadFailure, got $first", first is EndpointGate.ReadFailure)
        // 生產 MainScreen 組成即跑初始 reconcile：此處同式落定 pending（NO_OP），
        // gate 保持 ReadFailure（需重試 tick 才重建）。
        runBlocking {
            withTimeout(10_000) {
                vm.launchPendingDeleteReconcile { o, j -> vm.onStartupReconcile(o, j) }.join()
            }
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("setupRetryRead").assertCountEquals(1)
        onSetupArea("設定讀取失敗", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("setupRetryRead").performScrollTo()
        composeRule.onNodeWithTag("setupRetryRead").assertIsDisplayed()
        composeRule.onNodeWithTag("setupRetryRead").assertIsEnabled()
        // 先寫草稿（key＋model），再點重試：恢復後草稿須保留。
        vm.onApiKeyChange("draft-key-12345678")
        vm.onModelChange("draft-model")
        composeRule.onNodeWithTag("setupRetryRead").performScrollTo()
        composeRule.onNodeWithTag("setupRetryRead").performClick()
        runBlocking {
            withTimeout(10_000) { vm.gate.first { it is EndpointGate.Ready } }
        }
        val ready = vm.gate.value as EndpointGate.Ready
        assertEquals("m-old", ready.config.model)
        assertEquals("draft-key-12345678", vm.form.value.apiKey)
        assertEquals("draft-model", vm.form.value.model)
        assertEquals("retry must not disturb save state", false, vm.form.value.saving)
        // 恢復後重試卡消失（非 ReadFailure 不渲染）。
        composeRule.onAllNodesWithTag("setupRetryRead").assertCountEquals(0)
    }

    @Test
    fun editModeReadFailureRetryIsReachable() {
        // 編輯態（isFirstRun=false）同樣可達；重試不觸發 save。
        val vm = newVm(failReads = 2)
        var saved = 0
        composeRule.setContent {
            SetupScreen(
                padding = PaddingValues(0.dp),
                isFirstRun = false,
                onSaved = { saved++ },
                viewModel = vm,
            )
        }
        runBlocking {
            withTimeout(10_000) { vm.gate.first { it is EndpointGate.ReadFailure } }
        }
        runBlocking {
            withTimeout(10_000) {
                vm.launchPendingDeleteReconcile { o, j -> vm.onStartupReconcile(o, j) }.join()
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("setupRetryRead").performScrollTo()
        composeRule.onNodeWithTag("setupRetryRead").assertIsDisplayed()
        composeRule.onNodeWithTag("setupRetryRead").assertIsEnabled()
        composeRule.onNodeWithTag("setupRetryRead").performScrollTo()
        composeRule.onNodeWithTag("setupRetryRead").performClick()
        runBlocking {
            withTimeout(10_000) { vm.gate.first { it is EndpointGate.Ready } }
        }
        assertEquals(0, saved)
    }
}
