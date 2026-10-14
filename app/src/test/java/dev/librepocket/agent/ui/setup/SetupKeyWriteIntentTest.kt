package dev.librepocket.agent.ui.setup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.keystore.KeyVault
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PR81 Stage S4：KeepExisting/model-save UX＋ASK dialog intent。
 *
 * - 成功標記以 entry snapshot 的 model 回寫已持久化值（persisted==displayed）；
 *   save 卡在 hasKey 前、程式化改 model 的縱深測試（UI 層已 disabled）。
 * - [KeyWriteIntent]＋[KeyWriteConfirmSummary] 與 confirmKeyWrite 同步設置／
 *   清除（5 設、8 清：5 post-show fence＋confirm／dismiss／invalidate），一律經
 *   show/hide helper。
 * - double-tap 單寫、dismiss 重放 POLICY_DENIED 零寫、mint-race（pre-show）隱藏。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupKeyWriteIntentTest {

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
        val dir = Files.createTempDirectory("setup-intent-test").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        return EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "intent.preferences_pb") },
            ),
        )
    }

    private fun newVm(
        store: EndpointStore,
        vault: KeyVault,
        policy: PolicyStore,
    ): SetupViewModel = SetupViewModel(
        store = store,
        vaultSource = VaultSource { vault },
        policy = policy,
        buildProvider = { _, _ -> error("no provider needed") as LlmProvider },
    )

    private fun awaitSettled(vm: SetupViewModel, timeoutMs: Long = 8000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (vm.form.value.saving) {
            if (System.currentTimeMillis() > end) error("timed out waiting for save")
            Thread.sleep(10)
        }
    }

    private fun awaitDialog(vm: SetupViewModel, timeoutMs: Long = 8000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!vm.form.value.confirmKeyWrite) {
            if (System.currentTimeMillis() > end) error("timed out waiting for dialog")
            Thread.sleep(10)
        }
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

    @Test
    fun stagedModelRaceWritesBackPersistedValue() {
        // S4 VM barrier（staged）：save 卡在 orphan-guard hasKey 前，
        // 程式化改 model（UI 層已 disabled，此為縱深）→ 成功後
        // displayed 必須等於 persisted（entry snapshot），消除分叉。
        val store = newStore()
        val vault = HasKeyGatingVault()
        val vm = newVm(store, vault, VerdictPolicy(Verdict.ALLOW))
        vm.onApiKeyChange("unit-test-key-123")
        vm.onModelChange("m-entry")
        vm.save()
        assertTrue("save never reached hasKey", vault.entered.await(10, TimeUnit.SECONDS))
        vm.onModelChange("m-race")
        vault.release.countDown()
        awaitSettled(vm)
        assertNull(vm.form.value.errorCode)
        assertTrue(vm.form.value.saved)
        val persisted = runBlocking { store.observe().first() }?.model
        assertEquals("m-entry", persisted)
        assertEquals("displayed must equal persisted", persisted, vm.form.value.model)
        assertEquals(0, vault.deleteKeys)
        assertEquals(1, vault.hasKeys)
    }

    @Test
    fun keepExistingModelRaceWritesBackPersistedValue() {
        // S4 VM barrier（keepExisting）：save 卡在 reuse hasKey 前改 model →
        // 成功後 displayed 等於 persisted，live ref 保留、零 key-material I/O。
        val store = newStore()
        val vault = HasKeyGatingVault()
        runBlocking {
            vault.putKey("preset:openai", "test-key-123".toCharArray())
            store.save(openAiConfig())
        }
        val putsBefore = vault.putKeys
        val vm = newVm(store, vault, VerdictPolicy(Verdict.ALLOW))
        vm.prefillForEdit(openAiConfig())
        vm.onModelChange("m-entry")
        vm.save()
        assertTrue("save never reached hasKey", vault.entered.await(10, TimeUnit.SECONDS))
        vm.onModelChange("m-race")
        vault.release.countDown()
        awaitSettled(vm)
        assertNull(vm.form.value.errorCode)
        assertTrue(vm.form.value.saved)
        runBlocking {
            val live = store.snapshotStrict()
            assertEquals("provider_key/preset:openai", live?.apiKeyRef)
            assertEquals("m-entry", live?.model)
        }
        assertEquals("displayed must equal persisted", "m-entry", vm.form.value.model)
        assertEquals(putsBefore, vault.putKeys)
        assertEquals(0, vault.deleteKeys)
        assertEquals(0, vault.getKeys)
        assertEquals(1, vault.hasKeys)
    }

    @Test
    fun stagedAskSetsStagedIntentAndSummary() {
        // STAGED mint：intent＋summary 與 dialog 同 emission 設置；
        // confirm tap（第二 entry 合法提交）成功後同步清除。
        val policy = VerdictPolicy(Verdict.ASK)
        val vault = CountingVault()
        val vm = newVm(newStore(), vault, policy)
        vm.onApiKeyChange("unit-test-key-123")
        vm.onModelChange("m-new")
        vm.save()
        awaitDialog(vm)
        assertTrue(vm.form.value.confirmKeyWrite)
        assertEquals(KeyWriteIntent.STAGED, vm.form.value.pendingKeyWriteIntent)
        val summary = vm.form.value.confirmSummary
        assertEquals("OpenAI", summary?.providerLabel)
        assertEquals("https://api.openai.com/v1", summary?.baseUrl)
        assertNull(summary?.oldModel)
        assertEquals("m-new", summary?.newModel)
        assertFalse(vm.form.value.saved)
        // 第二 entry（confirm 攜 consent）合法提交不受影響。
        vm.confirmKeyWriteSave()
        awaitSettled(vm)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertNull(vm.form.value.pendingKeyWriteIntent)
        assertNull(vm.form.value.confirmSummary)
        assertTrue(vm.form.value.saved)
        assertEquals("m-new", vm.form.value.model)
        assertEquals(1, vault.putKeys)
    }

    @Test
    fun keepExistingAskSetsIntentAndDismissReplaysDeniedWithZeroWrites() {
        // KEEP_EXISTING mint：intent＋summary 攜 old→new；
        // dismiss 同步清除；dismiss 後重走 save（翻 DENY）即 POLICY_DENIED
        // 且零寫、無對話框。
        val vault = CountingVault()
        val store = newStore()
        runBlocking {
            vault.putKey("preset:openai", "test-key-123".toCharArray())
            store.save(openAiConfig())
        }
        val putsBefore = vault.putKeys
        val policy = MutablePolicy(Verdict.ASK)
        val vm = newVm(store, vault, policy)
        vm.prefillForEdit(openAiConfig())
        vm.onModelChange("m-new")
        vm.save()
        awaitDialog(vm)
        assertEquals(KeyWriteIntent.KEEP_EXISTING, vm.form.value.pendingKeyWriteIntent)
        val summary = vm.form.value.confirmSummary
        assertEquals("OpenAI", summary?.providerLabel)
        assertEquals("https://api.openai.com/v1", summary?.baseUrl)
        assertEquals("m-old", summary?.oldModel)
        assertEquals("m-new", summary?.newModel)
        vm.dismissKeyWriteConfirm()
        assertFalse(vm.form.value.confirmKeyWrite)
        assertNull(vm.form.value.pendingKeyWriteIntent)
        assertNull(vm.form.value.confirmSummary)
        assertFalse(vm.form.value.saved)
        // Dismiss 已丟棄 consent：直接重放 confirm 為早退（零寫、不落碼）。
        vm.confirmKeyWriteSave()
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertEquals(putsBefore, vault.putKeys)
        // Dismiss 後重走 save：重估 fresh，DENY 即 POLICY_DENIED 且零寫。
        policy.verdict = Verdict.DENY
        vm.save()
        awaitSettled(vm)
        assertEquals("POLICY_DENIED_KEY_WRITE", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertNull(vm.form.value.pendingKeyWriteIntent)
        assertNull(vm.form.value.confirmSummary)
        assertEquals(putsBefore, vault.putKeys)
        assertEquals(0, vault.deleteKeys)
        assertEquals(0, vault.getKeys)
        // 對話框路徑零 vault I/O：初次 ASK 在快照／hasKey 前即 mint＋彈窗返回；
        // dismiss 後 DENY 重走亦在首個 fresh 即拒絕，故 hasKeys 保持 0。
        // hasKey 只發生在 confirm 提交的第二次 entry（本測試不走 confirm）。
        assertEquals(0, vault.hasKeys)
        runBlocking {
            val live = store.snapshotStrict()
            assertEquals("m-old", live?.model)
        }
    }

    @Test
    fun keepExistingConfirmSuccessIsMetadataOnly() {
        // S6：KEEP_EXISTING confirm 成功為 metadata-only：live ref 保留、
        // hasKey==1（存在性前提，僅確認存在）、get/put/delete==0。
        val vault = CountingVault()
        val store = newStore()
        runBlocking {
            vault.putKey("preset:openai", "test-key-123".toCharArray())
            store.save(openAiConfig())
        }
        val putsBefore = vault.putKeys
        val vm = newVm(store, vault, VerdictPolicy(Verdict.ASK))
        vm.prefillForEdit(openAiConfig())
        vm.onModelChange("m-new-keep")
        vm.save()
        awaitDialog(vm)
        assertEquals(KeyWriteIntent.KEEP_EXISTING, vm.form.value.pendingKeyWriteIntent)
        vm.confirmKeyWriteSave()
        awaitSettled(vm)
        assertNull(vm.form.value.errorCode)
        assertTrue(vm.form.value.saved)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertNull(vm.form.value.pendingKeyWriteIntent)
        assertNull(vm.form.value.confirmSummary)
        assertEquals(putsBefore, vault.putKeys)
        assertEquals(0, vault.deleteKeys)
        assertEquals(0, vault.getKeys)
        assertEquals(1, vault.hasKeys)
        runBlocking {
            val live = store.snapshotStrict()
            assertEquals("provider_key/preset:openai", live?.apiKeyRef)
            assertEquals("m-new-keep", live?.model)
        }
        assertEquals("m-new-keep", vm.form.value.model)
    }

    @Test
    fun confirmReplayWritesOnce() {
        // 順序重放（非併發）：consume-once，第二次為 early-return（dialog 已關），
        // 單寫且 intent/summary 已清；前後 freshCalls 皆 5（第二次零 policy I/O）。
        val policy = VerdictPolicy(Verdict.ASK)
        val vault = CountingVault()
        val vm = newVm(newStore(), vault, policy)
        vm.onApiKeyChange("unit-test-key-123")
        vm.save()
        awaitDialog(vm)
        vm.confirmKeyWriteSave()
        awaitSettled(vm)
        assertTrue(vm.form.value.saved)
        assertNull(vm.form.value.errorCode)
        assertEquals(5, policy.freshCalls)
        vm.confirmKeyWriteSave()
        assertTrue(vm.form.value.saved)
        assertNull(vm.form.value.errorCode)
        assertEquals(5, policy.freshCalls)
        assertNull(vm.form.value.pendingKeyWriteIntent)
        assertNull(vm.form.value.confirmSummary)
        assertEquals(1, vault.putKeys)
    }

    @Test
    fun mintRaceAbortsBeforeDialogWithZeroWrites() {
        // Pre-show abort（mint→dialog 窗口）：afterMintBeforeDialogForTest 鉤住
        // mint 成功到 showKeyWriteConfirm 之間的窗口，edit 經 pre-show fence
        // 中止（不同於 post-show fence：show 後到 post-show fence 之間為 CPU-only，
        // 無 suspend 點，本測試不覆蓋該窗口）。intent/summary 同步清，不留殘留供重放。
        val policy = VerdictPolicy(Verdict.ASK)
        val vault = CountingVault()
        val store = newStore()
        val vm = newVm(store, vault, policy)
        vm.onApiKeyChange("unit-test-key-123")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        vm.afterMintBeforeDialogForTest = {
            entered.countDown()
            if (!release.await(10, TimeUnit.SECONDS)) error("mint-dialog probe timeout")
        }
        vm.save()
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        vm.onApiKeyChange("unit-test-key-456")
        release.countDown()
        awaitSettled(vm)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertNull(vm.form.value.pendingKeyWriteIntent)
        assertNull(vm.form.value.confirmSummary)
        assertEquals("POLICY_DENIED_KEY_WRITE", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertEquals(0, vault.putKeys)
        // 殘留清除後重放 confirm 同樣拒絕。
        vm.confirmKeyWriteSave()
        assertEquals(0, vault.putKeys)
    }

    @Test
    fun dialogModelRaceCommitsLiveSnapshot() {
        // S4 方案 (b) 鎖定：對話框顯示後程式化改 model（UI 層已 disabled，
        // 此為縱深，VM 容忍 stale：不 bump、不設 gate、不 invalidate 同意書），
        // confirm 為全新 entry（重讀 live form），故提交 raced 新值而非 dialog
        // 顯示時舊值；成功後回寫 persisted（confirm-time entry）覆蓋顯示，
        // persisted==displayed（無分叉）。Dialog summary 為顯示時快照（舊值），
        // 僅顯示用，不參與 gate。
        val policy = VerdictPolicy(Verdict.ASK)
        val vault = CountingVault()
        val store = newStore()
        val vm = newVm(store, vault, policy)
        vm.onApiKeyChange("unit-test-key-123")
        vm.onModelChange("m-entry")
        vm.save()
        awaitDialog(vm)
        assertEquals(KeyWriteIntent.STAGED, vm.form.value.pendingKeyWriteIntent)
        assertEquals("m-entry", vm.form.value.confirmSummary?.newModel)
        // Dialog 顯示後程式化改 model（繞過 UI disabled）。
        vm.onModelChange("m-race")
        // VM 容忍：dialog 仍在（model 不 bump、不 invalidate），summary 仍舊值。
        assertTrue(vm.form.value.confirmKeyWrite)
        assertEquals("m-entry", vm.form.value.confirmSummary?.newModel)
        assertEquals("m-race", vm.form.value.model)
        vm.confirmKeyWriteSave()
        awaitSettled(vm)
        assertNull(vm.form.value.errorCode)
        assertTrue(vm.form.value.saved)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertNull(vm.form.value.pendingKeyWriteIntent)
        assertNull(vm.form.value.confirmSummary)
        // Confirm-time entry 權威：persisted 為 raced 新值，顯示回寫覆蓋一致。
        val persisted = runBlocking { store.observe().first() }?.model
        assertEquals("m-race", persisted)
        assertEquals("displayed must equal persisted", persisted, vm.form.value.model)
        assertEquals(1, vault.putKeys)
    }
}
