package dev.librepocket.agent.ui.setup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.keystore.KeyVault
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
 * Stage2 (#13) key.write 確認驗收：consent 綁定操作快照。
 *
 * 本次新增測試檔實為兩檔：本檔（Stage2 #13 同意書驗收）＋
 * [SetupEndpointIdMigrationTest]（Stage1 #2 residual 端點 id 遷移，entity／
 * policy 分流）；後者歸 Stage1，不在本檔重複計算。
 *
 * 鎖定（只增測試，ID 推導規則／binding 全等不動）：
 * - ALLOW／ASK／DENY 三路＋對話框前零寫入（vault put／store save 計數皆零）；
 * - ASK→DENY→Confirm 零 putKey／零 metadata 寫；
 * - 改 preset／URL 舊 consent 即時失效（同步藏框，confirm 靜默無操作）；
 * - 單次消費不可重用、dismiss 放棄後重放靜默無操作；
 * - consent 不持久化（磁碟無明文／無 consent、重建 VM 無殘留、結構零 key material、
 *   建構子無 SavedState）；
 * - KEEP_EXISTING 複用路徑 DENY 恆擋（零 key-material I/O）；
 * - 最終提交 gate（VCommit）DENY 零 metadata 寫、僅清自身 staged；
 * - stale-CAS 失敗重試成功且 endpoint id 穩定（與 #2 合驗）。
 *
 * 等待一律 barrier（CompletableDeferred）＋ TestDispatcher／delay 輪詢，
 * 不用 Thread.sleep；key 一律假 key。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupKeyWriteConsentAcceptanceTest {

    private val tmpDirs = ArrayList<java.io.File>()
    private val scopes = ArrayList<CoroutineScope>()
    private val vms = ArrayList<SetupViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        vms.forEach { runCatching { it.viewModelScope.cancel() } }
        vms.clear()
        Dispatchers.resetMain()
        scopes.forEach { it.cancel() }
        tmpDirs.forEach { it.deleteRecursively() }
    }

    /** DataStore 寫計數裝飾：saveIfRevision／save／clear 的 updateData 呼叫計數。 */
    private class CountingDataStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> {
        var updateCalls = 0
            private set
        override val data: Flow<Preferences> = delegate.data
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            updateCalls++
            return delegate.updateData(transform)
        }
    }

    /** suspending barrier vault：putKey 內停車 chờ放行（TOCTOU  окне，不用 sleep）。 */
    private class PutBarrierVault(
        private val delegate: KeyVault = EncryptedPrefsVault(InMemoryPrefs()),
        val entered: CompletableDeferred<Unit> = CompletableDeferred(),
        val release: CompletableDeferred<Unit> = CompletableDeferred(),
    ) : KeyVault {
        var putKeys = 0
            private set
        var getKeys = 0
            private set
        var deleteKeys = 0
            private set
        var hasKeys = 0
            private set
        val putIds = ArrayList<String>()
        val deletedIds = ArrayList<String>()

        override suspend fun putKey(providerId: String, apiKey: CharArray) {
            putKeys++
            putIds.add(providerId)
            entered.complete(Unit)
            release.await()
            delegate.putKey(providerId, apiKey)
        }

        override suspend fun getKey(providerId: String): CharArray? {
            getKeys++
            return delegate.getKey(providerId)
        }

        override suspend fun deleteKey(providerId: String) {
            deleteKeys++
            deletedIds.add(providerId)
            delegate.deleteKey(providerId)
        }

        override suspend fun hasKey(providerId: String): Boolean {
            hasKeys++
            return delegate.hasKey(providerId)
        }
    }

    private data class Rig(
        val vm: SetupViewModel,
        val vault: CountingVault,
        val store: EndpointStore,
        val backing: CountingDataStore,
        val dir: java.io.File,
    )

    private fun newRig(
        policy: PolicyStore,
        delegate: KeyVault = EncryptedPrefsVault(InMemoryPrefs()),
    ): Rig {
        val dir = Files.createTempDirectory("setup-consent-accept").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val backing = CountingDataStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "consent-accept.preferences_pb") },
            ),
        )
        val store = EndpointStore(backing)
        val vault = CountingVault(delegate)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = policy,
            buildProvider = { _, _ -> error("no provider needed") as LlmProvider },
        )
        vms.add(vm)
        return Rig(vm, vault, store, backing, dir)
    }

    private suspend fun awaitSaveIdle(vm: SetupViewModel) {
        withTimeout(10_000) { while (vm.form.value.saving) delay(5) }
    }

    private suspend fun awaitDialog(vm: SetupViewModel) {
        withTimeout(10_000) { vm.form.first { it.confirmKeyWrite } }
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
    fun allowSavesDirectlyWithFourGatesAndOneMetadataWrite() = runBlocking {
        val policy = VerdictPolicy(Verdict.ALLOW)
        val rig = newRig(policy)
        rig.vm.onApiKeyChange("fake-accept-allow-key-AAAAAAAA")
        val writesBefore = rig.backing.updateCalls
        rig.vm.save()
        awaitSaveIdle(rig.vm)
        assertTrue(rig.vm.form.value.saved)
        assertFalse(rig.vm.form.value.confirmKeyWrite)
        // V1＋VPut（put 前）＋V2＋VCommit（CAS 前）：每次副作用前皆 fresh 評估。
        assertEquals(4, policy.freshCalls)
        assertEquals("key.write", policy.lastAction)
        assertEquals("preset:openai", policy.lastResource)
        assertEquals(1, rig.vault.putKeys)
        // Stage2 WARN 補強：staged 成功僅 orphan-guard 存在性 hasKey（miss），
        // 零內容讀取、零退役刪除。
        assertEquals(0, rig.vault.deleteKeys)
        assertEquals(0, rig.vault.getKeys)
        assertEquals(1, rig.vault.hasKeys)
        assertEquals(writesBefore + 1, rig.backing.updateCalls)
        assertTrue(rig.store.hasMetadata())
    }

    @Test
    fun denyBlocksDirectSaveWithZeroVaultAndStoreWrites() = runBlocking {
        val policy = VerdictPolicy(Verdict.DENY)
        val rig = newRig(policy)
        rig.vm.onApiKeyChange("fake-accept-deny-key-BBBBBBBB")
        val writesBefore = rig.backing.updateCalls
        rig.vm.save()
        awaitSaveIdle(rig.vm)
        assertEquals("POLICY_DENIED_KEY_WRITE", rig.vm.form.value.errorCode)
        assertFalse(rig.vm.form.value.saved)
        assertFalse(rig.vm.form.value.confirmKeyWrite)
        assertEquals(1, policy.freshCalls)
        assertEquals(0, rig.vault.putKeys)
        assertEquals(0, rig.vault.deleteKeys)
        assertEquals(0, rig.vault.getKeys)
        assertEquals(0, rig.vault.hasKeys)
        assertEquals(writesBefore, rig.backing.updateCalls)
        assertFalse(rig.store.hasMetadata())
    }

    @Test
    fun askMintsDialogWithZeroPreConfirmWritesThenCommitsOnConfirm() = runBlocking {
        val policy = VerdictPolicy(Verdict.ASK)
        val rig = newRig(policy)
        rig.vm.onApiKeyChange("fake-accept-ask-key-CCCCCCCC")
        val writesBefore = rig.backing.updateCalls
        rig.vm.save()
        awaitDialog(rig.vm)
        awaitSaveIdle(rig.vm)
        // 對話框前零寫入：mint 只讀 revision，不碰 vault／metadata。
        assertTrue(rig.vm.form.value.confirmKeyWrite)
        assertFalse(rig.vm.form.value.saved)
        assertEquals(1, policy.freshCalls)
        assertEquals(0, rig.vault.putKeys)
        assertEquals(0, rig.vault.deleteKeys)
        assertEquals(0, rig.vault.getKeys)
        // Stage2 WARN 補強：mint 前零 vault 接觸，存在性探針亦零次。
        assertEquals(0, rig.vault.hasKeys)
        assertEquals(writesBefore, rig.backing.updateCalls)
        assertFalse(rig.store.hasMetadata())
        // Confirm 攜一次性 consent：V1／VPut（put 前）／V2／VCommit（CAS 前）全跑。
        rig.vm.confirmKeyWriteSave()
        awaitSaveIdle(rig.vm)
        assertTrue(rig.vm.form.value.saved)
        assertFalse(rig.vm.form.value.confirmKeyWrite)
        assertEquals(5, policy.freshCalls)
        assertEquals(1, rig.vault.putKeys)
        // Stage2 WARN 補強：提交成功僅 orphan-guard hasKey（miss），零內容讀、零刪。
        assertEquals(0, rig.vault.deleteKeys)
        assertEquals(0, rig.vault.getKeys)
        assertEquals(1, rig.vault.hasKeys)
        assertEquals(writesBefore + 1, rig.backing.updateCalls)
        assertTrue(rig.store.hasMetadata())
    }

    @Test
    fun askToDenyConfirmPerformsZeroWrites() = runBlocking {
        val policy = MutablePolicy(Verdict.ASK)
        val rig = newRig(policy)
        rig.vm.onApiKeyChange("fake-accept-askdeny-key-DDDDDDDD")
        val writesBefore = rig.backing.updateCalls
        rig.vm.save()
        awaitDialog(rig.vm)
        awaitSaveIdle(rig.vm)
        assertEquals(1, policy.freshCalls)
        // 對話框後撤回為 DENY：confirm 的 V1 即擋，零 putKey／零 save。
        policy.verdict = Verdict.DENY
        rig.vm.confirmKeyWriteSave()
        awaitSaveIdle(rig.vm)
        assertFalse(rig.vm.form.value.confirmKeyWrite)
        assertEquals("POLICY_DENIED_KEY_WRITE", rig.vm.form.value.errorCode)
        assertFalse(rig.vm.form.value.saved)
        assertEquals(2, policy.freshCalls)
        assertEquals("key.write", policy.lastAction)
        assertEquals("preset:openai", policy.lastResource)
        assertEquals(0, rig.vault.putKeys)
        assertEquals(0, rig.vault.deleteKeys)
        assertEquals(0, rig.vault.getKeys)
        // Stage2 WARN 補強：V1 即擋，快照／hasKey 前返回，存在性探針亦零次。
        assertEquals(0, rig.vault.hasKeys)
        assertEquals(writesBefore, rig.backing.updateCalls)
        assertFalse(rig.store.hasMetadata())
    }

    @Test
    fun presetChangeInvalidatesUnconsumedConsentImmediately() = runBlocking {
        val policy = MutablePolicy(Verdict.ASK)
        val rig = newRig(policy)
        rig.vm.onApiKeyChange("fake-accept-preset-key-EEEEEEEE")
        rig.vm.save()
        awaitDialog(rig.vm)
        awaitSaveIdle(rig.vm)
        assertEquals(1, policy.freshCalls)
        // 改 preset 即時撤回：同步藏框（不經 IO 等待），confirm 靜默無操作。
        rig.vm.selectPreset("anthropic")
        assertFalse(rig.vm.form.value.confirmKeyWrite)
        rig.vm.confirmKeyWriteSave()
        assertNull(rig.vm.form.value.errorCode)
        assertFalse(rig.vm.form.value.saved)
        assertEquals(1, policy.freshCalls)
        assertEquals(0, rig.vault.putKeys)
        // Stage2 WARN 補強：改身份失效為同步藏框＋confirm 靜默早退（零評估、
        // 零 vault／metadata 接觸），hasMetadata 恆 false。
        assertEquals(0, rig.vault.deleteKeys)
        assertEquals(0, rig.vault.getKeys)
        assertEquals(0, rig.vault.hasKeys)
        assertEquals(0, rig.backing.updateCalls)
        assertFalse(rig.store.hasMetadata())
    }

    @Test
    fun customUrlChangeInvalidatesConsentAndKeepsEntityPolicySplit() = runBlocking {
        val urlA = "https://custom-consent-a.example.com/v1"
        val urlB = "https://custom-consent-b.example.com/v1"
        val policy = MutablePolicy(Verdict.ASK)
        val rig = newRig(policy)
        rig.vm.selectPreset("custom")
        rig.vm.onBaseUrlChange(urlA)
        rig.vm.onApiKeyChange("fake-accept-custom-key-FFFFFFFF")
        rig.vm.save()
        awaitDialog(rig.vm)
        awaitSaveIdle(rig.vm)
        // Stage1 分流：授權 scope 仍是 preset-shared，不因 per-endpoint id 而變。
        assertEquals("preset:custom", policy.lastResource)
        // 改 URL 舊 consent 即時失效：同步藏框，confirm 靜默無操作、零寫。
        rig.vm.onBaseUrlChange(urlB)
        assertFalse(rig.vm.form.value.confirmKeyWrite)
        rig.vm.confirmKeyWriteSave()
        assertFalse(rig.vm.form.value.saved)
        assertEquals(1, policy.freshCalls)
        assertEquals(0, rig.vault.putKeys)
        // Stage2 WARN 補強：同上，改 URL 失效零 vault／metadata 接觸。
        assertEquals(0, rig.vault.deleteKeys)
        assertEquals(0, rig.vault.getKeys)
        assertEquals(0, rig.vault.hasKeys)
        assertEquals(0, rig.backing.updateCalls)
        assertFalse(rig.store.hasMetadata())
        // 重貼＋顯式重綁後再存：新 consent 綁新 entity，提交的 entity id 為
        // per-URL 推導值（≠ preset:custom），policy scope 仍 preset:custom。
        rig.vm.onApiKeyChange("fake-accept-custom-key-GGGGGGGG")
        rig.vm.save()
        awaitSaveIdle(rig.vm)
        assertTrue(rig.vm.form.value.confirmRebind)
        rig.vm.confirmRebind()
        rig.vm.save()
        awaitDialog(rig.vm)
        awaitSaveIdle(rig.vm)
        rig.vm.confirmKeyWriteSave()
        awaitSaveIdle(rig.vm)
        assertTrue(rig.vm.form.value.saved)
        val expectedId = SetupValidation.deriveEndpointId("custom", urlB)
        val meta = withTimeout(10_000) { rig.store.snapshotStrict() }
        assertEquals(expectedId, meta?.providerId)
        assertEquals(urlB, meta?.baseUrl)
        assertEquals("preset:custom", policy.lastResource)
        assertEquals(1, rig.vault.putKeys)
    }

    @Test
    fun confirmConsumesOnceSecondTapIsNoOp() = runBlocking {
        val policy = VerdictPolicy(Verdict.ASK)
        val rig = newRig(policy)
        rig.vm.onApiKeyChange("fake-accept-once-key-HHHHHHHH")
        rig.vm.save()
        awaitDialog(rig.vm)
        awaitSaveIdle(rig.vm)
        rig.vm.confirmKeyWriteSave()
        awaitSaveIdle(rig.vm)
        assertTrue(rig.vm.form.value.saved)
        assertEquals(5, policy.freshCalls)
        assertEquals(1, rig.vault.putKeys)
        // Stage2 WARN 補強：單次消費成功僅 orphan-guard hasKey，零內容讀、零刪。
        assertEquals(0, rig.vault.deleteKeys)
        assertEquals(0, rig.vault.getKeys)
        assertEquals(1, rig.vault.hasKeys)
        val writesAfterCommit = rig.backing.updateCalls
        // 單次消費：第二次為 early-return，零 policy I/O、零寫入。
        rig.vm.confirmKeyWriteSave()
        assertTrue(rig.vm.form.value.saved)
        assertEquals(5, policy.freshCalls)
        assertEquals(1, rig.vault.putKeys)
        assertEquals(writesAfterCommit, rig.backing.updateCalls)
    }

    @Test
    fun dismissAbandonsConsentConfirmReplayIsSilentNoOp() = runBlocking {
        val policy = VerdictPolicy(Verdict.ASK)
        val rig = newRig(policy)
        rig.vm.onApiKeyChange("fake-accept-dismiss-key-IIIIIIII")
        rig.vm.save()
        awaitDialog(rig.vm)
        awaitSaveIdle(rig.vm)
        rig.vm.dismissKeyWriteConfirm()
        assertFalse(rig.vm.form.value.confirmKeyWrite)
        assertFalse(rig.vm.form.value.saved)
        val callsAfterDismiss = policy.freshCalls
        val writesAfterDismiss = rig.backing.updateCalls
        // 放棄後重放 confirm：對話框已關→靜默早退（不落碼、零評估、零寫）。
        rig.vm.confirmKeyWriteSave()
        assertNull(rig.vm.form.value.errorCode)
        assertFalse(rig.vm.form.value.saved)
        assertEquals(callsAfterDismiss, policy.freshCalls)
        assertEquals(0, rig.vault.putKeys)
        // Stage2 WARN 補強：放棄＋重放全程零 vault 接觸（含存在性探針）。
        assertEquals(0, rig.vault.deleteKeys)
        assertEquals(0, rig.vault.getKeys)
        assertEquals(0, rig.vault.hasKeys)
        assertEquals(writesAfterDismiss, rig.backing.updateCalls)
        assertFalse(rig.store.hasMetadata())
    }

    @Test
    fun consentNeverPersistedToDiskOrRecreatedVm() = runBlocking {
        val fakeKey = "fake-accept-disk-key-ZZ9Q7X42"
        val policy = MutablePolicy(Verdict.ASK)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val rig = newRig(policy, delegate)
        rig.vm.onApiKeyChange(fakeKey)
        rig.vm.save()
        awaitDialog(rig.vm)
        awaitSaveIdle(rig.vm)
        assertTrue(rig.vm.form.value.confirmKeyWrite)
        // 重建 VM（同 store／同 vault）：無殘留對話框，confirm 靜默無操作。
        val policy2 = MutablePolicy(Verdict.ASK)
        val vm2 = SetupViewModel(
            store = rig.store,
            vaultSource = VaultSource { delegate },
            policy = policy2,
            buildProvider = { _, _ -> error("no provider needed") as LlmProvider },
        )
        vms.add(vm2)
        assertFalse(vm2.form.value.confirmKeyWrite)
        vm2.confirmKeyWriteSave()
        assertNull(vm2.form.value.errorCode)
        assertFalse(vm2.form.value.saved)
        assertEquals(0, policy2.freshCalls)
        // 磁碟：DataStore 檔不含明文 key、不含 consent 痕跡。
        val blob = rig.dir.listFiles()
            ?.filter { it.isFile }
            ?.fold(StringBuilder()) { acc, f -> acc.append(f.readBytes().toString(Charsets.ISO_8859_1)) }
            .toString().orEmpty()
        assertFalse("disk must not contain plaintext key", blob.contains(fakeKey))
        assertFalse("disk must not contain consent marker", blob.contains("KeyWriteConsent"))
        // 結構：consent 僅身份快照欄位，零 key material（無 CharArray／apiKey 欄）。
        val consentClass = Class.forName("dev.librepocket.agent.ui.setup.KeyWriteConsent")
        val fields = consentClass.declaredFields
        val names = fields.map { it.name }.toSet()
        assertEquals(
            setOf("action", "resource", "providerId", "baseUrl", "configRevision", "formRevision"),
            names,
        )
        assertTrue(fields.none { it.type == CharArray::class.java })
        assertTrue(names.none { it.contains("apiKey", ignoreCase = true) })
        // 建構子無 SavedState：consent 不可能經 SavedState 持久化。
        val hasSavedStateParam = SetupViewModel::class.java.declaredConstructors.any { ctor ->
            ctor.parameterTypes.any { it.simpleName.contains("SavedState") }
        }
        assertFalse("SetupViewModel must not take SavedStateHandle", hasSavedStateParam)
    }

    @Test
    fun keepExistingAskToDenyConfirmWritesNothing() = runBlocking {
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val policy = MutablePolicy(Verdict.ASK)
        val rig = newRig(policy, delegate)
        delegate.putKey("preset:openai", "fake-accept-live-key-JJJJJJJJ".toCharArray())
        rig.store.save(openAiConfig())
        val putsBefore = rig.vault.putKeys
        val writesBefore = rig.backing.updateCalls
        rig.vm.prefillForEdit(openAiConfig())
        rig.vm.onModelChange("m-new-reuse")
        rig.vm.save()
        awaitDialog(rig.vm)
        awaitSaveIdle(rig.vm)
        assertEquals(KeyWriteIntent.KEEP_EXISTING, rig.vm.form.value.pendingKeyWriteIntent)
        // 複用路徑 confirm 前撤回為 DENY：V1 即擋，零 key-material I/O、零寫。
        policy.verdict = Verdict.DENY
        rig.vm.confirmKeyWriteSave()
        awaitSaveIdle(rig.vm)
        assertEquals("POLICY_DENIED_KEY_WRITE", rig.vm.form.value.errorCode)
        assertFalse(rig.vm.form.value.saved)
        assertEquals(putsBefore, rig.vault.putKeys)
        assertEquals(0, rig.vault.deleteKeys)
        assertEquals(0, rig.vault.getKeys)
        assertEquals(0, rig.vault.hasKeys)
        assertEquals(writesBefore, rig.backing.updateCalls)
        assertEquals("m-old", withTimeout(10_000) { rig.store.snapshotStrict() }?.model)
    }

    @Test
    fun finalCommitDenyPerformsZeroMetadataWrites() = runBlocking {
        // V1／VPut／V2 ALLOW、VCommit DENY：put 已發生但 CAS 永不嘗試，
        // 僅清自身 staged（putIds＝deletedIds），metadata 寫計數零增長。
        val policy = FlipAfterPolicy(allowCalls = 3)
        val rig = newRig(policy)
        rig.vm.onApiKeyChange("fake-accept-commit-key-KKKKKKKK")
        val writesBefore = rig.backing.updateCalls
        rig.vm.save()
        awaitSaveIdle(rig.vm)
        assertEquals("POLICY_DENIED_KEY_WRITE", rig.vm.form.value.errorCode)
        assertFalse(rig.vm.form.value.saved)
        assertEquals(4, policy.freshCalls)
        assertEquals(1, rig.vault.putKeys)
        assertEquals(1, rig.vault.deleteKeys)
        assertEquals(rig.vault.putIds.single(), rig.vault.deletedIds.single())
        // Stage2 WARN 補強：VCommit DENY 前 orphan-guard hasKey 已跑（miss），
        // 全程零內容讀取。
        assertEquals(0, rig.vault.getKeys)
        assertEquals(1, rig.vault.hasKeys)
        assertEquals(writesBefore, rig.backing.updateCalls)
        assertFalse(rig.store.hasMetadata())
    }

    @Test
    fun keepExistingVCommitDenyAfterHasKeyWritesNothing() = runBlocking {
        // Stage2 WARN 補強（KEEP_EXISTING hasKey 後 VCommit-DENY 子例）：
        // 與 keepExistingAskToDenyConfirmWritesNothing（V1 即擋、hasKeys==0）
        // 互補——此例 V1 ALLOW 放行，reuse 前提 hasKey(live alias)==true 已執行，
        // 隨後 VCommit 翻 DENY：零 metadata 寫、零 key-material I/O（put／delete／
        // get 皆零），舊 metadata（m-old）原封不動。hasMetadata 恆 true（預置仍在）。
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val policy = FlipAfterPolicy(allowCalls = 1)
        val rig = newRig(policy, delegate)
        delegate.putKey("preset:openai", "fake-accept-live-key-TTTTTTTT".toCharArray())
        rig.store.save(openAiConfig())
        val putsBefore = rig.vault.putKeys
        val writesBefore = rig.backing.updateCalls
        rig.vm.prefillForEdit(openAiConfig())
        rig.vm.onModelChange("m-new-vcommit-deny")
        rig.vm.save()
        awaitSaveIdle(rig.vm)
        // V1 ALLOW＋VCommit DENY：共 2 次 fresh 評估。
        assertEquals(2, policy.freshCalls)
        assertEquals("POLICY_DENIED_KEY_WRITE", rig.vm.form.value.errorCode)
        assertFalse(rig.vm.form.value.saved)
        assertFalse(rig.vm.form.value.confirmKeyWrite)
        assertEquals(putsBefore, rig.vault.putKeys)
        assertEquals(0, rig.vault.deleteKeys)
        assertEquals(0, rig.vault.getKeys)
        // reuse 前提的存在性檢查已跑（true）——與 V1-DENY 子例的 0 次互補。
        assertEquals(1, rig.vault.hasKeys)
        assertEquals(writesBefore, rig.backing.updateCalls)
        assertTrue(rig.store.hasMetadata())
        assertEquals("m-old", withTimeout(10_000) { rig.store.snapshotStrict() }?.model)
    }

    @Test
    fun staleCasLoserRetrySucceedsWithStableEndpointId() = runBlocking {
        val url = "https://custom-retry.example.com/v1"
        val dir = Files.createTempDirectory("setup-consent-retry").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val store = EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "consent-retry.preferences_pb") },
            ),
        )
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val policyLoser = MutablePolicy(Verdict.ALLOW)
        val policyWinner = MutablePolicy(Verdict.ALLOW)
        val vaultLoser = PutBarrierVault(delegate)
        val vaultWinner = CountingVault(delegate)
        val noProvider: (dev.librepocket.provider.ProviderConfig, dev.librepocket.provider.KeyProvider) -> LlmProvider =
            { _, _ -> error("no provider needed") as LlmProvider }
        val vmLoser = SetupViewModel(store, VaultSource { vaultLoser }, policyLoser, buildProvider = noProvider)
        val vmWinner = SetupViewModel(store, VaultSource { vaultWinner }, policyWinner, buildProvider = noProvider)
        vms.add(vmLoser)
        vms.add(vmWinner)
        val expectedId = SetupValidation.deriveEndpointId("custom", url)
        // Loser 先快照（fresh null）後停在 putKey；winner 完整提交（rev null→1）。
        vmLoser.selectPreset("custom")
        vmLoser.onBaseUrlChange(url)
        vmLoser.onApiKeyChange("fake-accept-loser-key-LLLLLLLL")
        vmLoser.save()
        withTimeout(10_000) { vaultLoser.entered.await() }
        vmWinner.selectPreset("custom")
        vmWinner.onBaseUrlChange(url)
        vmWinner.onApiKeyChange("fake-accept-winner-key-MMMMMMMM")
        vmWinner.save()
        awaitSaveIdle(vmWinner)
        assertTrue(vmWinner.form.value.saved)
        assertEquals(expectedId, withTimeout(10_000) { store.snapshotStrict() }?.providerId)
        assertEquals("preset:custom", policyWinner.lastResource)
        val winnerAlias = SetupValidation.aliasFromRef(withTimeout(10_000) { store.snapshotStrict() }!!.apiKeyRef)
        // Loser 恢復：stale null 輸 CAS，僅清自身 staged，winner 原子不受損。
        vaultLoser.release.complete(Unit)
        awaitSaveIdle(vmLoser)
        assertEquals("POLICY_DENIED_KEY_WRITE", vmLoser.form.value.errorCode)
        assertFalse(vmLoser.form.value.saved)
        assertEquals(1, vaultLoser.putKeys)
        assertEquals(listOf(vaultLoser.putIds.single()), vaultLoser.deletedIds)
        assertTrue(delegate.hasKey(winnerAlias))
        // 失敗重試：同 key 同 URL 再存即成功（rev 1→2），entity id 穩定。
        vmLoser.save()
        awaitSaveIdle(vmLoser)
        assertTrue(vmLoser.form.value.saved)
        assertNull(vmLoser.form.value.errorCode)
        val meta = withTimeout(10_000) { store.snapshotStrict() }
        assertEquals(expectedId, meta?.providerId)
        assertEquals(url, meta?.baseUrl)
        assertEquals("preset:custom", policyLoser.lastResource)
        assertEquals(2L, withTimeout(10_000) { store.currentRevision() })
        val retryAlias = SetupValidation.aliasFromRef(meta!!.apiKeyRef)
        assertTrue(retryAlias != winnerAlias)
        assertTrue(delegate.hasKey(retryAlias))
        // 重試提交退役 winner 舊 live（同 staged 路徑旋轉語義），新 key 正確。
        assertFalse(delegate.hasKey(winnerAlias))
        assertEquals(2, vaultLoser.putKeys)
        val retryKey = withTimeout(10_000) { delegate.getKey(retryAlias) }
        val expectedChars = "fake-accept-loser-key-LLLLLLLL".toCharArray()
        try {
            assertTrue(retryKey != null && retryKey.contentEquals(expectedChars))
        } finally {
            retryKey?.fill('\u0000')
            expectedChars.fill('\u0000')
        }
    }
}
