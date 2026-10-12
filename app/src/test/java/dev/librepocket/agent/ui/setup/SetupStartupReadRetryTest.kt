package dev.librepocket.agent.ui.setup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dev.librepocket.agent.ui.Routes
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.agent.ui.shouldNavigateToSettings
import dev.librepocket.agent.ui.shouldNavigateToSetup
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.keystore.KeyVault
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage D1（R12 Finding 1 P2/BLOCK）驗收：啟動讀失敗誤導重試清除。
 *
 * - read-path `UNKNOWN`（journal=null）走新中性碼 [SetupViewModel.LOGOUT_READ_RETRY]；
 *   clear-phase `UNKNOWN`（journal!=null）維持 `LOGOUT_UNKNOWN` retained 不變。
 * - 重試為非破壞性 reconcile-only（[SetupViewModel.retryStartupReconcile]），絕不調 logout。
 * - 重試結果：有 journal 升級 retained；`NO_OP`／`COMMITTED` 顯式清除中性碼（他碼不動）。
 * - single-flight：settled 重播無 I/O；invalidate 後重試跑新鮮 worker。
 * - dismiss 安全：中性碼 clear 後真無 journal 可存、掩蓋 journal 照擋。
 *
 * 不改 (UNKNOWN,J)/DELETE/FAILED/STALE 映射；不碰跨 VM／派生態／generation；不做 timeout。
 */
private class D1StrictReadFailingDataStore(
    private val delegate: DataStore<Preferences>,
    private val failReads: Int = 1,
    private val error: IOException = IOException("STRICT_READ_FAILED"),
) : DataStore<Preferences> {
    var updateCalls = 0
        private set
    private var readCalls = 0
    override val data: Flow<Preferences>
        get() = flow {
            if (readCalls < failReads) {
                readCalls++
                throw error
            }
            emitAll(delegate.data)
        }
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        updateCalls++
        return delegate.updateData(transform)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SetupStartupReadRetryTest {
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
            produceFile = { java.io.File(dir, "d1-read-retry.preferences_pb") },
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

    private fun newVm(store: EndpointStore, vault: KeyVault): SetupViewModel =
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
    fun healthyNoJournalSingleReadFail_mapsToNeutralRetry() {
        // 驗收 1：健康 preseed + 無 journal + failReads=1 → UNKNOWN+null、零 vault I/O、
        // updateCalls==0、key 在、新碼（非 UNKNOWN/READ_FAILED）、文案無已刪除/殘留。
        val raw = rawStore("d1-healthy-read-fail")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            raw.save(preseed())
        }
        val failingReads = D1StrictReadFailingDataStore(raw.dataStore, failReads = 1)
        val store = EndpointStore(failingReads)
        val vault = CountingVault(delegate)
        val vm = newVm(store, vault)
        val (outcome, journal) = runBlocking { vm.reconcilePendingDeleteWithJournal() }
        assertEquals(SetupViewModel.PendingDeleteOutcome.UNKNOWN, outcome)
        assertNull("read-path UNKNOWN carries null journal", journal)
        assertEquals("zero vault deletes on read-path UNKNOWN", 0, vault.deleteKeys)
        assertEquals(0, vault.putKeys)
        assertEquals(0, vault.hasKeys)
        assertEquals("no clear may be attempted after a strict-read failure", 0, failingReads.updateCalls)
        runBlocking {
            assertTrue("key survives (no vault I/O)", delegate.hasKey("preset:openai"))
        }
        vm.onStartupReconcile(outcome, journal, gateReady = false)
        val code = vm.form.value.errorCode
        assertEquals(SetupViewModel.LOGOUT_READ_RETRY, code)
        assertTrue("new code keeps LOGOUT_ prefix for guards", code!!.startsWith("LOGOUT_"))
        assertEquals("LOGOUT_READ_FAILED must not be reused", false, code == "LOGOUT_READ_FAILED")
        assertEquals("must not fall back to UNKNOWN", false, code == "LOGOUT_UNKNOWN")
        assertNull(vm.form.value.logoutErrorSourceJournal)
        assertFalse(vm.form.value.logoutInFlight)
        val text = setupErrorText(code)
        assertFalse("wording must not claim deleted: $text", text.contains("已刪除"))
        assertFalse("wording must not claim residual: $text", text.contains("殘留"))
        assertTrue("wording keeps no-change promise: $text", text.contains("未做任何變更"))
        assertTrue("wording points at read-retry: $text", text.contains("重試讀取"))
    }

    @Test
    fun neutralRetryNoJournal_clearsSilentlyKeepsKeyMeta() {
        // 驗收 2：重試得 NO_OP → 碼 null、key/meta/rev 完整、後續 save 不被擋。
        val raw = rawStore("d1-neutral-retry-clears")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            raw.save(preseed())
        }
        val failingReads = D1StrictReadFailingDataStore(raw.dataStore, failReads = 1)
        val store = EndpointStore(failingReads)
        val vault = CountingVault(delegate)
        val vm = newVm(store, vault)
        // 首輪：read-path UNKNOWN+null → 中性碼。
        val (first, firstJournal) = runBlocking { vm.reconcilePendingDeleteWithJournal() }
        assertEquals(SetupViewModel.PendingDeleteOutcome.UNKNOWN, first)
        assertNull(firstJournal)
        vm.onStartupReconcile(first, firstJournal, gateReady = false)
        assertEquals(SetupViewModel.LOGOUT_READ_RETRY, vm.form.value.errorCode)
        // 重試：failReads 已耗盡，同 store 第二次 strict 讀成功、無 journal → NO_OP。
        // 經 retryStartupReconcile（invalidate + 新鮮 worker）以鎖定非破壞性路徑。
        var seen: Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>? = null
        val job = vm.retryStartupReconcile { outcome, journal ->
            seen = outcome to journal
            vm.onStartupReconcile(outcome, journal, gateReady = false)
        }
        runBlocking { withTimeout(10_000) { job.join() } }
        // join handle 落定不保證 Main fan-out 已執行（worker finally 先落定 pending，
        // Main 回調隨後）：輪詢至碼清除，證明 onDone 已到達。
        val clearEnd = System.currentTimeMillis() + 10_000
        while (vm.form.value.errorCode != null) {
            if (System.currentTimeMillis() > clearEnd) error("timed out waiting for neutral clear")
            Thread.sleep(10)
        }
        assertEquals(SetupViewModel.PendingDeleteOutcome.NO_OP, seen?.first)
        assertNull(seen?.second)
        assertNull("NO_OP must explicitly clear the neutral code", vm.form.value.errorCode)
        assertNull(vm.form.value.logoutErrorSourceJournal)
        assertFalse(vm.form.value.logoutInFlight)
        // key/meta/rev 完整。
        runBlocking {
            assertTrue(delegate.hasKey("preset:openai"))
            assertNull("no journal after NO_OP retry", store.pendingDelete())
            assertEquals(1L, store.currentRevision())
            val observed = store.observe().first()
            assertTrue("metadata survives neutral retry", observed != null)
            assertEquals("provider_key/preset:openai", observed!!.apiKeyRef)
        }
        // 後續 save 不被擋（真無 journal 則 fresh 可存）。
        vm.onApiKeyChange("unit-test-fresh-key-cccccccc")
        vm.save()
        awaitSaveSettled(vm)
        assertTrue("fresh save must succeed after neutral clear", vm.form.value.saved)
        assertNull(vm.form.value.errorCode)
    }

    @Test
    fun journalPresentReadFail_retryUpgradesToRetained() {
        // 驗收 3a：有 journal + failReads=1 → 首輪新碼；健康 vault 重試前仍用 throwing
        // vault 升級 DELETE retained（帶 tag）。
        val raw = rawStore("d1-journal-upgrade-delete")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val journal = PendingDelete("preset:openai", 1L, 1700000000000L)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            raw.save(preseed())
            assertTrue(raw.writePendingDeleteIfAbsent(journal))
        }
        val failingReads = D1StrictReadFailingDataStore(raw.dataStore, failReads = 1)
        val store = EndpointStore(failingReads)
        val throwing = ThrowingDeleteVault(delegate)
        val vm = newVm(store, throwing)
        val (first, firstJournal) = runBlocking { vm.reconcilePendingDeleteWithJournal() }
        assertEquals(SetupViewModel.PendingDeleteOutcome.UNKNOWN, first)
        assertNull(firstJournal)
        vm.onStartupReconcile(first, firstJournal, gateReady = false)
        assertEquals(SetupViewModel.LOGOUT_READ_RETRY, vm.form.value.errorCode)
        // 重試：第二次 strict 讀成功，發現 journal，delete 拋 → DELETE_FAILED+J。
        val (second, secondJournal) = runBlocking { vm.reconcilePendingDeleteWithJournal() }
        assertEquals(SetupViewModel.PendingDeleteOutcome.DELETE_FAILED, second)
        assertEquals(journal, secondJournal)
        vm.onStartupReconcile(second, secondJournal, gateReady = false)
        assertEquals("LOGOUT_DELETE_FAILED", vm.form.value.errorCode)
        assertEquals(journal.format(), vm.form.value.logoutErrorSourceJournal)
        val text = setupErrorText(vm.form.value.errorCode!!)
        assertFalse("delete wording must not claim deleted: $text", text.contains("已刪除"))
    }

    @Test
    fun journalPresentReadFail_retryUpgradesToClearRetained() {
        // 驗收 3b：FAILED（delete 證勝＋clear 已知未提交）升級 CLEAR retained（帶 tag）。
        val raw = rawStore("d1-journal-upgrade-clear")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val journal = PendingDelete("preset:openai", 1L, 1700000000000L)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            raw.save(preseed())
            assertTrue(raw.writePendingDeleteIfAbsent(journal))
        }
        // clear-transport 全敗：reconcile 的 delete 勝、clear 拋＋probe 同 rev 見元數據＋
        // 同 rev 重試仍敗 → FAILED。
        val failingClear = SetupLogoutClearTestHelperClearFailing(raw.dataStore)
        val store = EndpointStore(failingClear)
        val vm = newVm(store, CountingVault(delegate))
        // 先 stamp 中性碼（模擬首輪讀失敗），再以 FAILED 升級。
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.UNKNOWN, null, gateReady = false)
        assertEquals(SetupViewModel.LOGOUT_READ_RETRY, vm.form.value.errorCode)
        val (outcome, outJournal) = runBlocking { vm.reconcilePendingDeleteWithJournal() }
        assertEquals(SetupViewModel.PendingDeleteOutcome.FAILED, outcome)
        assertEquals(journal, outJournal)
        vm.onStartupReconcile(outcome, outJournal, gateReady = false)
        assertEquals("LOGOUT_CLEAR_FAILED", vm.form.value.errorCode)
        assertEquals(journal.format(), vm.form.value.logoutErrorSourceJournal)
    }

    @Test
    fun clearPhaseUnknownWithJournal_staysRetained() {
        // 驗收 3c：clear-phase UNKNOWN+J 直落 LOGOUT_UNKNOWN，不走新碼。
        val store = rawStore("d1-clearphase-unknown")
        val vm = newVm(store, CountingVault())
        val journal = PendingDelete("preset:openai", 1L, 1700000000000L)
        // 先中性碼，再以 UNKNOWN+J 升級 retained。
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.UNKNOWN, null, gateReady = false)
        assertEquals(SetupViewModel.LOGOUT_READ_RETRY, vm.form.value.errorCode)
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.UNKNOWN, journal, gateReady = false)
        assertEquals("LOGOUT_UNKNOWN", vm.form.value.errorCode)
        assertEquals(journal.format(), vm.form.value.logoutErrorSourceJournal)
        // 直接落（無前碼）亦為 UNKNOWN retained。
        val vm2 = newVm(rawStore("d1-clearphase-direct"), CountingVault())
        vm2.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.UNKNOWN, journal, gateReady = false)
        assertEquals("LOGOUT_UNKNOWN", vm2.form.value.errorCode)
        assertEquals(journal.format(), vm2.form.value.logoutErrorSourceJournal)
    }

    @Test
    fun neutralOnlyClear_neverTouchesOtherLogoutCodes() {
        // 驗收 4 補充（VM 側）：NO_OP／COMMITTED-null 僅清中性碼，他碼不動。
        val vm = newVm(rawStore("d1-noop-keeps-other"), CountingVault())
        val journal = PendingDelete("preset:openai", 1L, 1700000000000L)
        // onStartup 產物的三 retained 碼：NO_OP／COMMITTED-null 皆不清除。
        val retained = listOf(
            SetupViewModel.PendingDeleteOutcome.DELETE_FAILED to "LOGOUT_DELETE_FAILED",
            SetupViewModel.PendingDeleteOutcome.FAILED to "LOGOUT_CLEAR_FAILED",
        )
        for ((outcome, code) in retained) {
            vm.onStartupReconcile(outcome, journal, gateReady = false)
            assertEquals(code, vm.form.value.errorCode)
            vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.NO_OP, null, gateReady = false)
            assertEquals("NO_OP must never clear $code", code, vm.form.value.errorCode)
            vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.COMMITTED, null, gateReady = false)
            assertEquals("COMMITTED-null must never clear $code", code, vm.form.value.errorCode)
            // 不同 journal 的 COMMITTED 亦不清除。
            val other = PendingDelete("preset:openai#staged-cccccccccccccccccccccccccccccccc", 2L, 1700000000001L)
            vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.COMMITTED, other, gateReady = false)
            assertEquals(code, vm.form.value.errorCode)
            vm.clearLogoutError()
        }
        // clear-phase UNKNOWN+J 同為 retained：NO_OP／COMMITTED-null 不清除。
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.UNKNOWN, journal, gateReady = false)
        assertEquals("LOGOUT_UNKNOWN", vm.form.value.errorCode)
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.NO_OP, null, gateReady = false)
        assertEquals("LOGOUT_UNKNOWN", vm.form.value.errorCode)
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.COMMITTED, null, gateReady = false)
        assertEquals("LOGOUT_UNKNOWN", vm.form.value.errorCode)
        vm.clearLogoutError()
        // logout 路徑的 READ_FAILED（retained、重試清除）同樣不被 NO_OP 清除：
        // 以 read-failing store 跑一次 logout 取得真實 READ_FAILED，再給 NO_OP。
        val rawReadFail = rawStore("d1-noop-keeps-readfailed")
        val delegateReadFail = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegateReadFail.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            rawReadFail.save(preseed())
        }
        val readFailStore = EndpointStore(D1StrictReadFailingDataStore(rawReadFail.dataStore, failReads = 10))
        val readFailVm = newVm(readFailStore, CountingVault(delegateReadFail))
        val readDone = CompletableDeferred<Boolean>()
        readFailVm.logout { readDone.complete(it) }
        assertEquals(false, runBlocking { withTimeout(10_000) { readDone.await() } })
        assertEquals("LOGOUT_READ_FAILED", readFailVm.form.value.errorCode)
        readFailVm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.NO_OP, null, gateReady = false)
        assertEquals("LOGOUT_READ_FAILED", readFailVm.form.value.errorCode)
        readFailVm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.COMMITTED, null, gateReady = false)
        assertEquals("LOGOUT_READ_FAILED", readFailVm.form.value.errorCode)
        // 中性碼兩去向皆清。
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.UNKNOWN, null, gateReady = false)
        assertEquals(SetupViewModel.LOGOUT_READ_RETRY, vm.form.value.errorCode)
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.NO_OP, null, gateReady = false)
        assertNull(vm.form.value.errorCode)
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.UNKNOWN, null, gateReady = false)
        assertEquals(SetupViewModel.LOGOUT_READ_RETRY, vm.form.value.errorCode)
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.COMMITTED, null, gateReady = false)
        assertNull("COMMITTED-null must clear the neutral code", vm.form.value.errorCode)
        // COMMITTED+J 亦清中性碼（重試證明 teardown 已完成）。
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.UNKNOWN, null, gateReady = false)
        assertEquals(SetupViewModel.LOGOUT_READ_RETRY, vm.form.value.errorCode)
        val j2 = PendingDelete("preset:openai", 1L, 1700000000000L)
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.COMMITTED, j2, gateReady = false)
        assertNull(vm.form.value.errorCode)
    }

    @Test
    fun navigation_newCodeSteersToSettingsNotSetup() {
        // 驗收 4（導航側）：新碼與舊四碼一致走 SETTINGS，互斥不走 SETUP。
        val codes = listOf(
            SetupViewModel.LOGOUT_READ_RETRY,
            "LOGOUT_READ_FAILED",
            "LOGOUT_DELETE_FAILED",
            "LOGOUT_CLEAR_FAILED",
            "LOGOUT_UNKNOWN",
            "LOGOUT_STALE",
        )
        for (code in codes) {
            assertEquals("code=$code settings", true, shouldNavigateToSettings(false, false, Routes.CHAT, code, false))
            assertEquals("code=$code setup", false, shouldNavigateToSetup(false, false, Routes.CHAT, code, false))
        }
        // 無碼走 SETUP（fresh 語義不變）。
        assertEquals(false, shouldNavigateToSettings(false, false, Routes.CHAT, null, false))
        assertEquals(true, shouldNavigateToSetup(false, false, Routes.CHAT, null, false))
    }

    @Test
    fun singleFlight_settledNeutralReplaysWithoutIO_retryRunsFresh() {
        // 驗收 5：settled UNKNOWN+null 後 rotation 重播無 I/O；invalidate 後重試跑新鮮 worker。
        val raw = rawStore("d1-singleflight-neutral")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            raw.save(preseed())
        }
        val failingReads = D1StrictReadFailingDataStore(raw.dataStore, failReads = 1)
        val store = EndpointStore(failingReads)
        val vault = CountingVault(delegate)
        val vm = newVm(store, vault)
        var seen1: Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>? = null
        val job1 = vm.launchPendingDeleteReconcile { outcome, j ->
            seen1 = outcome to j
            vm.onStartupReconcile(outcome, j, gateReady = false)
        }
        runBlocking { withTimeout(10_000) { job1.join() } }
        // 等 Main fan-out 落碼（join 僅保證 worker 落定，Main 回調隨後）。
        val stampEnd = System.currentTimeMillis() + 10_000
        while (vm.form.value.errorCode == null) {
            if (System.currentTimeMillis() > stampEnd) error("timed out waiting for neutral stamp")
            Thread.sleep(10)
        }
        assertEquals(SetupViewModel.PendingDeleteOutcome.UNKNOWN, seen1?.first)
        assertNull(seen1?.second)
        assertEquals(SetupViewModel.LOGOUT_READ_RETRY, vm.form.value.errorCode)
        assertFalse(vm.startupReconcilePending.value)
        val deletes = vault.deleteKeys
        val puts = vault.putKeys
        val has = vault.hasKeys
        val updates = failingReads.updateCalls
        // Rotation 重 launch：回放快照，零新增 I/O，pending 恆 false。
        var seen2: Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>? = null
        val job2 = vm.launchPendingDeleteReconcile { outcome, j -> seen2 = outcome to j }
        assertFalse("settled replay must not touch the fence", vm.startupReconcilePending.value)
        runBlocking { withTimeout(10_000) { job2.join() } }
        assertEquals(seen1, seen2)
        assertEquals(deletes, vault.deleteKeys)
        assertEquals(puts, vault.putKeys)
        assertEquals(has, vault.hasKeys)
        assertEquals(updates, failingReads.updateCalls)
        // retryStartupReconcile：invalidate 後跑新鮮 worker（第二次讀成功、無 journal → NO_OP），
        // 證明非回放（outcome 由 UNKNOWN 變 NO_OP）且中性碼被清除。
        var seen3: Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>? = null
        val job3 = vm.retryStartupReconcile { outcome, j ->
            seen3 = outcome to j
            vm.onStartupReconcile(outcome, j, gateReady = false)
        }
        runBlocking { withTimeout(10_000) { job3.join() } }
        val clearEnd = System.currentTimeMillis() + 10_000
        while (vm.form.value.errorCode != null) {
            if (System.currentTimeMillis() > clearEnd) error("timed out waiting for retry clear")
            Thread.sleep(10)
        }
        assertEquals(SetupViewModel.PendingDeleteOutcome.NO_OP, seen3?.first)
        assertNull(seen3?.second)
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.startupReconcilePending.value)
    }

    @Test
    fun retryNeverTouchesLogoutInFlight() {
        // 重試絕不調 logout：logoutInFlight 全程 false。
        val raw = rawStore("d1-retry-no-logout")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            raw.save(preseed())
        }
        val vm = newVm(raw, CountingVault(delegate))
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.UNKNOWN, null, gateReady = false)
        assertEquals(SetupViewModel.LOGOUT_READ_RETRY, vm.form.value.errorCode)
        assertFalse(vm.form.value.logoutInFlight)
        val job = vm.retryStartupReconcile { outcome, j ->
            vm.onStartupReconcile(outcome, j, gateReady = false)
        }
        runBlocking { withTimeout(10_000) { job.join() } }
        val end = System.currentTimeMillis() + 10_000
        while (vm.form.value.errorCode != null) {
            if (System.currentTimeMillis() > end) error("timed out waiting for clear")
            Thread.sleep(10)
        }
        assertFalse("retry must never touch logoutInFlight", vm.form.value.logoutInFlight)
        assertNull(vm.lastLogoutJobForTest)
    }

    @Test
    fun dismissNeutral_trueNoJournalSaveSucceeds() {
        // 驗收 6a：新碼 clear 後真無 journal → 存成功。
        val store = rawStore("d1-dismiss-true-nojournal")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
        }
        val vm = newVm(store, CountingVault(delegate))
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.UNKNOWN, null, gateReady = false)
        assertEquals(SetupViewModel.LOGOUT_READ_RETRY, vm.form.value.errorCode)
        vm.clearLogoutError()
        assertNull(vm.form.value.errorCode)
        runBlocking { assertNull(store.pendingDelete()) }
        vm.onApiKeyChange("unit-test-fresh-key-cccccccc")
        vm.save()
        awaitSaveSettled(vm)
        assertTrue("dismissed neutral with truly no journal must save", vm.form.value.saved)
        runBlocking {
            assertTrue(store.observe().first()!!.apiKeyRef.startsWith("provider_key/preset:openai#staged-"))
        }
    }

    @Test
    fun dismissNeutral_maskedJournalSaveBlockedAndJournalKept() {
        // 驗收 6b：掩蓋 journal（首輪讀失敗致 neutral，journal 實存）→ clear 後存被擋且 journal 留，
        // 下次 replay 重發現並升級 retained。
        val raw = rawStore("d1-dismiss-masked")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val journal = PendingDelete("preset:openai", 1L, 1700000000000L)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            raw.save(preseed())
            assertTrue(raw.writePendingDeleteIfAbsent(journal))
        }
        val failingReads = D1StrictReadFailingDataStore(raw.dataStore, failReads = 1)
        val store = EndpointStore(failingReads)
        val vm = newVm(store, CountingVault(delegate))
        // 首輪讀失敗 → 中性碼（journal 被掩蓋）。
        val (first, firstJournal) = runBlocking { vm.reconcilePendingDeleteWithJournal() }
        assertEquals(SetupViewModel.PendingDeleteOutcome.UNKNOWN, first)
        assertNull(firstJournal)
        vm.onStartupReconcile(first, firstJournal, gateReady = false)
        assertEquals(SetupViewModel.LOGOUT_READ_RETRY, vm.form.value.errorCode)
        // dismiss（ViewModel 允許清中性碼；UI 無此鍵，見 Settings 測試）。
        vm.clearLogoutError()
        assertNull(vm.form.value.errorCode)
        // save fence 照擋（第二次讀成功，見 journal）。
        vm.onApiKeyChange("unit-test-fresh-key-cccccccc")
        vm.save()
        awaitSaveSettled(vm)
        assertFalse(vm.form.value.saved)
        assertEquals("SAVE_BLOCKED_BY_TEARDOWN", vm.form.value.errorCode)
        runBlocking {
            assertEquals("masked journal must survive dismiss", "preset:openai", store.pendingDelete()?.alias)
            assertTrue(delegate.hasKey("preset:openai"))
            assertNull("gate still folds while the journal is retained", store.observe().first())
        }
        // 下次 replay 重發現並升級 retained（此處以健康 vault 直接 reconcile）。
        vm.onApiKeyChange("unit-test-old-key-123456") // 清掉 saved 態殘留？save 失敗不改 key，僅為語義完整。
        val healthy = newVm(store, CountingVault(delegate))
        val (second, secondJournal) = runBlocking { healthy.reconcilePendingDeleteWithJournal() }
        assertEquals(SetupViewModel.PendingDeleteOutcome.COMMITTED, second)
        assertEquals(journal, secondJournal)
    }
}

/** clear 全敗 store（reconcile FAILED 誘發用）：讀直通、寫全拋。 */
private class SetupLogoutClearTestHelperClearFailing(
    private val delegate: DataStore<Preferences>,
) : DataStore<Preferences> {
    override val data: Flow<Preferences> get() = delegate.data
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        throw IOException("CLEAR_TRANSPORT_FAILED")
    }
}
