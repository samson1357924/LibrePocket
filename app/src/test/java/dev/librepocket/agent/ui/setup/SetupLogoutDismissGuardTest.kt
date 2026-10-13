package dev.librepocket.agent.ui.setup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.Routes
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.agent.ui.shouldNavigateToSetup
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import java.nio.file.Files
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage A（R10 Finding 1 P2/BLOCK，方案 B 立即止血）鎖定：
 * retained 碼 dismiss 不得釋放 journal、STALE dismiss 安全、重啟可重 stamp、
 * STALE 全序列 dismiss→SETUP→save 成功（不誤傷 fresh/winner 路徑）。
 *
 * 不改守衛簽名、不引入新 StateFlow；僅鎖定既有語義。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupLogoutDismissGuardTest {
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

    private fun rawStore(dirName: String): EndpointStore {
        val dir = Files.createTempDirectory(dirName).toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val raw = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "dismiss-guard.preferences_pb") },
        )
        return EndpointStore(raw)
    }

    private fun preseed(model: String = "m-old"): EndpointConfig = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = model,
        apiKeyRef = "provider_key/preset:openai",
    )

    private fun newVm(store: EndpointStore, vault: dev.librepocket.keystore.KeyVault): SetupViewModel =
        SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )

    private fun awaitSaveSettled(vm: SetupViewModel, timeoutMs: Long = 8000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (vm.form.value.saving) {
            if (System.currentTimeMillis() > end) error("timed out waiting for save")
            Thread.sleep(10)
        }
    }

    @Test
    fun retainedClearKeepsJournalAndRestamps() {
        // seed journal + stamp retained 碼 → clear → 碼 null 且 journal 仍在。
        val store = rawStore("dismiss-guard-retained")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
            assertTrue(store.writePendingDeleteIfAbsent(PendingDelete("preset:openai", 1L, 1700000000000L)))
        }
        val vm = newVm(store, CountingVault(delegate))
        val retainedJournal = PendingDelete("preset:openai", 1L, 1700000000000L)
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.DELETE_FAILED, retainedJournal, gateReady = false)
        assertEquals("LOGOUT_DELETE_FAILED", vm.form.value.errorCode)
        vm.clearLogoutError()
        assertNull(vm.form.value.errorCode)
        runBlocking {
            assertTrue(
                "clearLogoutError must not release the journal",
                store.pendingDelete() != null,
            )
            assertEquals("preset:openai", store.pendingDelete()?.alias)
        }
        // 重啟重 stamp：clear 後再 reconcile → 碼重現。
        // Stage D1：UNKNOWN 分流——此處用 clear-phase UNKNOWN+J（retained）
        // 鎖定舊語義；read-path UNKNOWN+null（中性 READ_RETRY）由新驗收檔覆蓋。
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.DELETE_FAILED, retainedJournal, gateReady = false)
        assertEquals("LOGOUT_DELETE_FAILED", vm.form.value.errorCode)
        vm.clearLogoutError()
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.FAILED, retainedJournal, gateReady = false)
        assertEquals("LOGOUT_CLEAR_FAILED", vm.form.value.errorCode)
        vm.clearLogoutError()
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.UNKNOWN, retainedJournal, gateReady = false)
        assertEquals("LOGOUT_UNKNOWN", vm.form.value.errorCode)
        assertEquals(retainedJournal.format(), vm.form.value.logoutErrorSourceJournal)
        // COMMITTED/NO_OP 不蓋碼（既有語義鎖定）：先清再給兩者，維持 null。
        // Stage G F2 補充：此處為已清 null 態（非 tag-null 未確認碼），故兩者皆維持 null；
        // tag-null READ_FAILED／UNKNOWN 由 NO_OP 清除的語義由新驗收檔覆蓋（見 StageG）。
        vm.clearLogoutError()
        assertNull(vm.form.value.errorCode)
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.COMMITTED, null, gateReady = false)
        assertNull(vm.form.value.errorCode)
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.NO_OP, null, gateReady = false)
        assertNull(vm.form.value.errorCode)
    }

    @Test
    fun staleDismissIsSafeAndNavigatesToSetup() {
        // STALE 對照：journal 已丟（null），dismiss 安全；dismiss 回 fresh 後走 SETUP。
        val store = rawStore("dismiss-guard-stale")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
        }
        val vm = newVm(store, CountingVault(delegate))
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.STALE, null, gateReady = false)
        assertEquals("LOGOUT_STALE", vm.form.value.errorCode)
        runBlocking { assertNull(store.pendingDelete()) }
        vm.clearLogoutError()
        assertNull(vm.form.value.errorCode)
        runBlocking { assertNull(store.pendingDelete()) }
        // Nav：dismiss 後（無碼、非 inFlight）恢復強制跳轉（fresh 語義不變）。
        assertTrue(
            shouldNavigateToSetup(false, false, Routes.SETTINGS, null, false),
        )
    }

    @Test
    fun staleFullSequenceDismissThenSaveSucceeds() {
        // 整合（VM+Nav+Store）：STALE 全序列 dismiss→SETUP→save 成功，
        // 證明不誤傷 fresh/winner 路徑。
        val store = rawStore("dismiss-guard-stale-save")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val winnerAlias = "preset:openai#staged-" + "b".repeat(32)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
            assertTrue(store.writePendingDeleteIfAbsent(PendingDelete("preset:openai", 1L, 1700000000000L)))
            delegate.putKey(winnerAlias, "unit-test-winner-key-BBBBBBBB".toCharArray())
            store.save(preseed(model = "m-new").copy(apiKeyRef = "provider_key/$winnerAlias"))
        }
        val vault = CountingVault(delegate)
        val vm = newVm(store, vault)
        val (outcome, journal) = runBlocking { vm.reconcilePendingDeleteWithJournal() }
        assertEquals(SetupViewModel.PendingDeleteOutcome.STALE, outcome)
        vm.onStartupReconcile(outcome, journal, gateReady = false)
        assertEquals("LOGOUT_STALE", vm.form.value.errorCode)
        // dismiss（僅 STALE 可達）→ journal 已丟，save 不被擋。
        vm.clearLogoutError()
        assertNull(vm.form.value.errorCode)
        runBlocking { assertNull(store.pendingDelete()) }
        assertTrue(shouldNavigateToSetup(false, false, Routes.SETTINGS, null, false))
        // SETUP 側 fresh save 成功（winner 路徑不受傷）。
        vm.onApiKeyChange("unit-test-fresh-key-cccccccc")
        vm.save()
        awaitSaveSettled(vm)
        assertTrue(vm.form.value.saved)
        runBlocking {
            assertTrue(store.observe().first()!!.apiKeyRef.startsWith("provider_key/preset:openai#staged-"))
        }
    }
}
