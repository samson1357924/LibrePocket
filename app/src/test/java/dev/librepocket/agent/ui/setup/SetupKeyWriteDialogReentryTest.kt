package dev.librepocket.agent.ui.setup

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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S5：Setup 對話框期間重入＋save 未知 preset fail-closed＋logout 在途 probe 閘。
 *
 * - ASK 下 save 出對話框後、無編輯再 save → silent return：對話框＋intent／
 *   summary 不動、無第二次 fresh/mint/put（freshCalls 保持 1；後續 confirm 仍
 *   一次寫入，總 fresh 1+4=5）。
 * - 同一對話框期間 test/refresh → silent return：policy 翻 ALLOW 後亦零 provider
 *   構造、零 listModels、零目錄抓取，errorCode 不被覆寫。
 * - save 遇未知 preset → 落碼 SETUP_UNKNOWN_PRESET（與 probe 同形），零
 *   vault/policy I/O，saving 保持 false，不 crash（此前裸調直接拋出）。
 * - logout teardown 在途的新 probe → silent return，零 provider 構造。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupKeyWriteDialogReentryTest {

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

    private fun newStore(): EndpointStore {
        val dir = Files.createTempDirectory("setup-s5-reentry-test").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        return EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "s5-reentry.preferences_pb") },
            ),
        )
    }

    private fun newVm(
        store: EndpointStore,
        vault: KeyVault,
        policy: PolicyStore,
        buildCalls: AtomicInteger = AtomicInteger(0),
        dirCalls: AtomicInteger = AtomicInteger(0),
    ): SetupViewModel = SetupViewModel(
        store = store,
        vaultSource = VaultSource { vault },
        policy = policy,
        buildProvider = { _, _ ->
            buildCalls.incrementAndGet()
            error("S5: provider must not be constructed while blocked") as LlmProvider
        },
        fetchDirectory = { _: String ->
            dirCalls.incrementAndGet()
            """{"openai":{"models":{}}}"""
        },
    )

    private fun openAiConfig(model: String = "m-old") = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = model,
        apiKeyRef = "provider_key/preset:openai",
    )

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
    fun askResaveWithoutEditKeepsDialogWithZeroExtraIo() {
        val policy = VerdictPolicy(Verdict.ASK)
        val vault = CountingVault()
        val vm = newVm(newStore(), vault, policy)
        vm.onApiKeyChange("unit-test-key-123")
        vm.save()
        awaitDialog(vm)
        assertTrue(vm.form.value.confirmKeyWrite)
        assertEquals(KeyWriteIntent.STAGED, vm.form.value.pendingKeyWriteIntent)
        assertEquals(1, policy.freshCalls)
        assertEquals(0, vault.putKeys)
        assertEquals(0, vault.hasKeys)
        // 對話框期間無編輯再 save：silent return（不重估、不重 mint、不寫入）。
        vm.save()
        // Bug-manifest 窗口：未設閘時第二次 save 會在數百 ms 內重估＋重 mint；
        // 兩次讀 freshCalls 皆須為 1。
        Thread.sleep(200)
        assertEquals(1, policy.freshCalls)
        Thread.sleep(200)
        assertEquals(1, policy.freshCalls)
        assertTrue(vm.form.value.confirmKeyWrite)
        assertEquals(KeyWriteIntent.STAGED, vm.form.value.pendingKeyWriteIntent)
        assertEquals(vm.form.value.model, vm.form.value.confirmSummary?.newModel)
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertEquals(0, vault.putKeys)
        assertEquals(0, vault.hasKeys)
        // confirm 仍一次寫入：首輪 V1（1）＋confirm V1/VPut/V2/VCommit（4）= 5。
        vm.confirmKeyWriteSave()
        awaitSettled(vm)
        assertTrue(vm.form.value.saved)
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertEquals(5, policy.freshCalls)
        assertEquals(1, vault.putKeys)
    }

    @Test
    fun dialogBlocksTestAndRefreshWithZeroIo() {
        val policy = MutablePolicy(Verdict.ASK)
        val vault = CountingVault()
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vm = newVm(newStore(), vault, policy, buildCalls, dirCalls)
        vm.onApiKeyChange("unit-test-key-123")
        vm.save()
        awaitDialog(vm)
        assertTrue(vm.form.value.confirmKeyWrite)
        // 對話框期間翻 ALLOW：未設閘時 probe 會真的構造 provider＋listModels；
        // 設閘後必須零 I/O（errorCode 亦不被覆寫）。
        policy.verdict = Verdict.ALLOW
        vm.testConnection()
        vm.refreshModels()
        Thread.sleep(200)
        assertEquals(0, buildCalls.get())
        Thread.sleep(200)
        assertEquals(0, buildCalls.get())
        assertEquals(0, dirCalls.get())
        assertTrue(vm.form.value.confirmKeyWrite)
        assertFalse(vm.form.value.testing)
        assertFalse(vm.form.value.modelsLoading)
        assertNull(vm.form.value.testModels)
        assertNull(vm.form.value.errorCode)
        // probe 零 fresh：僅首輪 save V1。
        assertEquals(1, policy.freshCalls)
    }

    @Test
    fun unknownPresetSaveFallsClosedWithoutCrash() {
        val policy = VerdictPolicy(Verdict.ALLOW)
        val vault = CountingVault()
        val vm = newVm(newStore(), vault, policy)
        vm.onApiKeyChange("unit-test-key-123")
        // UI 到達不了未知 preset（selectPreset 擋未知、prefill 轉 custom）：
        // 以反射植入，覆蓋程式化／未來路徑的縱深（此前裸調直接拋出 crash）。
        val field = SetupViewModel::class.java.getDeclaredField("_form")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val form = field.get(vm) as MutableStateFlow<SetupUiState>
        form.value = form.value.copy(presetId = "__unknown_preset__")
        vm.save()
        awaitSettled(vm)
        assertEquals("SETUP_UNKNOWN_PRESET", vm.form.value.errorCode)
        assertFalse(vm.form.value.saving)
        assertFalse(vm.form.value.saved)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertEquals(0, vault.putKeys)
        assertEquals(0, vault.hasKeys)
        assertEquals(0, policy.freshCalls)
    }

    @Test
    fun probeBlockedDuringLogoutInFlight() {
        val store = newStore()
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val vault = DeleteGatingLogoutVault(delegate)
        runBlocking {
            delegate.putKey("preset:openai", "test-key-123".toCharArray())
            store.save(openAiConfig())
        }
        val policy = VerdictPolicy(Verdict.ALLOW)
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vm = newVm(store, vault, policy, buildCalls, dirCalls)
        val done = CompletableDeferred<Boolean>()
        vm.logout { done.complete(it) }
        runBlocking { withTimeout(10_000) { vault.deleteEntered.await() } }
        assertTrue(vm.form.value.logoutInFlight)
        // 有效 typed key 下的新 probe：未設閘時會構造 provider（ALLOW），
        // 設閘後必須 silent return、零構造。
        vm.onApiKeyChange("sk-test-key-123")
        vm.testConnection()
        vm.refreshModels()
        Thread.sleep(300)
        assertFalse(vm.form.value.testing)
        assertFalse(vm.form.value.modelsLoading)
        assertNull(vm.form.value.errorCode)
        assertEquals(0, buildCalls.get())
        assertEquals(0, dirCalls.get())
        vault.deleteRelease.complete(Unit)
        val ok = runBlocking { withTimeout(10_000) { done.await() } }
        assertTrue(ok)
        Thread.sleep(300)
        assertEquals(0, buildCalls.get())
        assertEquals(0, dirCalls.get())
    }
}
