package dev.librepocket.agent.ui.setup

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.keystore.KeyVault
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PR81 Stage S4：model 欄 fence＋ASK dialog intent 的 Compose 驗收。
 *
 * - saving／confirm 顯示中 model 欄 disabled。
 * - STAGED 沿用 key-write 文案；KEEP_EXISTING 為保留文案（無「存入」）＋身份列。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SetupKeyWriteConfirmUiTest {

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

    @get:Rule
    val composeRule = createComposeRule()

    private val noProvider: (dev.librepocket.provider.ProviderConfig, dev.librepocket.provider.KeyProvider) -> LlmProvider =
        { _, _ -> error("no provider needed") as LlmProvider }

    private fun newVm(policy: PolicyStore, vault: KeyVault): Pair<SetupViewModel, EndpointStore> {
        val dir = Files.createTempDirectory("setup-confirm-ui").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val store = EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "confirm-ui.preferences_pb") },
            ),
        )
        return SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = policy,
            buildProvider = noProvider,
        ) to store
    }

    private fun openAiConfig(model: String = "m-old") = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = model,
        apiKeyRef = "provider_key/preset:openai",
    )

    private fun setScreen(vm: SetupViewModel) {
        composeRule.setContent {
            SetupScreen(
                padding = PaddingValues(0.dp),
                isFirstRun = true,
                onSaved = {},
                viewModel = vm,
            )
        }
    }

    private fun awaitDialog(vm: SetupViewModel, timeoutMs: Long = 8000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!vm.form.value.confirmKeyWrite) {
            if (System.currentTimeMillis() > end) error("timed out waiting for dialog")
            Thread.sleep(10)
        }
    }

    private fun awaitSettled(vm: SetupViewModel, timeoutMs: Long = 8000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (vm.form.value.saving) {
            if (System.currentTimeMillis() > end) error("timed out waiting for save")
            Thread.sleep(10)
        }
    }

    @Test
    fun savingDisablesModelField() {
        // putKey 內阻塞：saving==true 期間 model 欄 disabled。
        val vault = BlockingVault()
        val (vm, _) = newVm(VerdictPolicy(Verdict.ALLOW), vault)
        setScreen(vm)
        vm.onApiKeyChange("unit-test-key-123")
        vm.onModelChange("s4-model-saving")
        vm.save()
        assertTrue(vault.entered.await(10, TimeUnit.SECONDS))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("s4-model-saving").assertIsNotEnabled()
        vault.release.countDown()
        awaitSettled(vm)
        composeRule.waitForIdle()
    }

    @Test
    fun stagedConfirmDisablesModelFieldAndKeepsCopy() {
        // STAGED 對話框：model 欄 disabled，沿用 key-write 文案（含「存入」），
        // title/確認鈕為寫入字樣；S6 起另顯示身份列（provider label、baseUrl、
        // newModel，與 KEEP_EXISTING 對齊）。
        val (vm, _) = newVm(VerdictPolicy(Verdict.ASK), CountingVault())
        setScreen(vm)
        vm.onApiKeyChange("unit-test-key-123")
        vm.onModelChange("s4-model-staged")
        vm.save()
        awaitDialog(vm)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("s4-model-staged").assertIsNotEnabled()
        composeRule.onNodeWithText("寫入金鑰確認").assertIsDisplayed()
        composeRule.onNodeWithText("確定寫入").assertIsDisplayed()
        composeRule.onNodeWithText("存入本機加密儲存", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("供應商：OpenAI").assertIsDisplayed()
        composeRule.onNodeWithText("伺服器位址：https://api.openai.com/v1").assertIsDisplayed()
        composeRule.onNodeWithText("模型：s4-model-staged").assertIsDisplayed()
        composeRule.onAllNodesWithText("保留既有金鑰", substring = true).assertCountEquals(0)
    }

    @Test
    fun keepExistingConfirmShowsRetainCopyAndIdentity() {
        // KEEP_EXISTING 對話框：保留文案（substring「保留既有金鑰」，無「存入」）＋
        // 身份列（provider label、baseUrl、model old→new），model 欄 disabled；
        // title/確認鈕為更新字樣。
        val vault = CountingVault()
        val (vm, store) = newVm(VerdictPolicy(Verdict.ASK), vault)
        runBlocking {
            vault.putKey("preset:openai", "test-key-123".toCharArray())
            store.save(openAiConfig())
        }
        setScreen(vm)
        vm.prefillForEdit(openAiConfig())
        vm.onModelChange("m-new-ui")
        vm.save()
        awaitDialog(vm)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("m-new-ui").assertIsNotEnabled()
        composeRule.onNodeWithText("確認更新設定").assertIsDisplayed()
        composeRule.onNodeWithText("確定更新").assertIsDisplayed()
        composeRule.onNodeWithText("保留既有金鑰", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("不會讀取金鑰內容", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("零 put/get", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("存入", substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("供應商：OpenAI").assertIsDisplayed()
        composeRule.onNodeWithText("伺服器位址：https://api.openai.com/v1").assertIsDisplayed()
        composeRule.onNodeWithText("模型：m-old → m-new-ui").assertIsDisplayed()
    }
}
