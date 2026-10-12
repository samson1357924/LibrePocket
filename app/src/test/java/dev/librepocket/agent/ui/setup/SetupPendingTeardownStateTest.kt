package dev.librepocket.agent.ui.setup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dev.librepocket.agent.ui.Routes
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.agent.ui.effectiveLogoutGuardError
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
 * Stage E（R13 Finding P2/BLOCK）驗收：phantom pending 四態修復。
 *
 * D2 的 `UNKNOWN_PRESENT` sentinel 把 transient 讀失敗（無 journal）與已確認
 * journal 混為同一 present：黏住的失敗經派生給出 `LOGOUT_UNKNOWN` 重試清除——
 * 無 journal 卻配破壞性外觀的恢復入口（且內層 `catch` 完成後永不重讀，phantom
 * 常駐）。本檔以 [FlakyDataStore]（前 N 次 `data` 拋 [IOException] 後恢復，
 * `updateData` 直通——既有 fake 皆為 private 常敗或包讀計數，不可用）覆蓋 flow
 * 路徑（`FailingDataStore` 未覆 `observePendingDelete` 的缺口）：
 * - (a) 無 journal＋健康 key/meta：flow 收集見 [PendingTeardownState.UnknownReadFailure]，
 *   strict 後續成功 → 僅重試讀取（D1 碼複用），按下後零 vault I/O、key/meta 不變；
 * - (b) transient→retry 後 flow 回 [PendingTeardownState.Absent]，派生 null →
 *   導航 fresh、Settings 無殘留卡；
 * - (c) 對照：KnownPending 健康 resume 至 COMMITTED；Malformed fail-closed
 *   （零 vault I/O、journal 保留、logout 報 READ_FAILED 零副作用，reconcile 走
 *   中性 READ_RETRY）。
 *
 * 不改 D1 碼／文案／清除、不改映射／取消、不碰 gate flow、不做 timeout。
 */
private class FlakyDataStore(
    private val delegate: DataStore<Preferences>,
    private val failReads: Int,
    private val error: IOException = IOException("FLAKY_READ_FAILED"),
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
class SetupPendingTeardownStateTest {
    private val tmpDirs = ArrayList<java.io.File>()
    private val scopes = ArrayList<CoroutineScope>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        // 先 cancel DataStore scopes 再 resetMain（VM flow 經 viewModelScope(Main)
        // 收集 store；順序反了會經已重置的 Main 分發而炸 teardown，非測試本體）。
        scopes.forEach { it.cancel() }
        Dispatchers.resetMain()
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
            produceFile = { java.io.File(dir, "e-phantom.preferences_pb") },
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

    @Test
    fun transientReadFailure_derivesRetryReadOnly_zeroIoOnRetry() {
        // Review (a)：無 journal＋健康 key/meta；flow 收集注入 1 次 IOException →
        // UnknownReadFailure；strict 後續成功（真無 journal）；派生僅重試讀取；
        // 按下重試讀取後 deleteKeys==0、key/meta/rev 不變、flow 回 Absent。
        val raw = rawStore("e-phantom-readonly")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            raw.save(preseed())
        }
        val flaky = FlakyDataStore(raw.dataStore, failReads = 1)
        val store = EndpointStore(flaky)
        val vault = CountingVault(delegate)
        val vm = newVm(store, vault)
        // Flow 收集消耗唯一一次失敗（確定性：尚無 worker，gate 未訂閱——stateIn
        // 上游唯此一讀）→ UnknownReadFailure（絕不誤報 Absent）。
        val first = runBlocking {
            withTimeout(10_000) { vm.pendingTeardown.first { it is PendingTeardownState.UnknownReadFailure } }
        }
        assertEquals(PendingTeardownState.UnknownReadFailure, first)
        // strict 後續成功：已恢復、真無 journal。
        runBlocking {
            assertNull(store.pendingDelete())
        }
        // 派生僅重試讀取（D1 碼複用；絕非 retained UNKNOWN／重試清除）。
        val effective = effectiveLogoutGuardError(null, first, logoutInFlight = false)
        assertEquals(SetupViewModel.LOGOUT_READ_RETRY, effective)
        val text = setupErrorText(effective!!)
        assertFalse("wording must not claim deleted: $text", text.contains("已刪除"))
        assertFalse("wording must not claim residual: $text", text.contains("殘留"))
        assertTrue("wording keeps no-change promise: $text", text.contains("未做任何變更"))
        assertTrue("wording points at read-retry: $text", text.contains("重試讀取"))
        // 按下重試讀取：bump 重建內層收集（重讀）＋新鮮 worker → NO_OP（真無 journal）。
        var seen: Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>? = null
        val job = vm.retryStartupReconcile { outcome, journal ->
            seen = outcome to journal
            vm.onStartupReconcile(outcome, journal, gateReady = false)
        }
        runBlocking { withTimeout(10_000) { job.join() } }
        val pollEnd = System.currentTimeMillis() + 10_000
        while (seen == null) {
            if (System.currentTimeMillis() > pollEnd) error("timed out waiting for retry fan-out")
            Thread.sleep(10)
        }
        val retried = checkNotNull(seen)
        assertEquals(SetupViewModel.PendingDeleteOutcome.NO_OP, retried.first)
        assertNull(retried.second)
        // 零 vault I/O、零 clear 寫（NO_OP 不碰 vault、不寫元數據）。
        assertEquals(0, vault.deleteKeys)
        assertEquals(0, vault.putKeys)
        assertEquals(0, vault.hasKeys)
        assertEquals(0, flaky.updateCalls)
        // key/meta/rev 不變、無碼。
        runBlocking {
            assertTrue(delegate.hasKey("preset:openai"))
            assertNull("no journal after retry", store.pendingDelete())
            assertEquals(1L, store.currentRevision())
            val observed = store.observe().first()
            assertTrue("metadata survives retry", observed != null)
            assertEquals("provider_key/preset:openai", observed!!.apiKeyRef)
        }
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.logoutInFlight)
        // Flow 回 Absent（tick 重建即重讀，無手動回寫）。
        runBlocking {
            withTimeout(10_000) { vm.pendingTeardown.first { it is PendingTeardownState.Absent } }
        }
    }

    @Test
    fun transientWorkerFailure_retryClearsToAbsent_noResidualCards() {
        // Review (b)：首輪 worker 讀失敗 → READ_RETRY 落碼；重試成功 → NO_OP 清碼；
        // flow 回 Absent；派生 null → 導航 fresh（SETUP 可達、無 SETTINGS 殘留）、
        // gate Ready（Settings 顯示端點卡、無恢復卡）。
        val raw = rawStore("e-transient-retry-absent")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            raw.save(preseed())
        }
        val flaky = FlakyDataStore(raw.dataStore, failReads = 1)
        val store = EndpointStore(flaky)
        val vault = CountingVault(delegate)
        val vm = newVm(store, vault)
        // 首輪：worker strict 讀消耗唯一一次失敗（flow 未訂閱，唯一一讀）→
        // UNKNOWN+null → 中性 READ_RETRY。
        var seen1: Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>? = null
        val job1 = vm.launchPendingDeleteReconcile { outcome, journal ->
            seen1 = outcome to journal
            vm.onStartupReconcile(outcome, journal, gateReady = false)
        }
        runBlocking { withTimeout(10_000) { job1.join() } }
        val stampEnd = System.currentTimeMillis() + 10_000
        while (seen1 == null || vm.form.value.errorCode == null) {
            if (System.currentTimeMillis() > stampEnd) error("timed out waiting for neutral stamp")
            Thread.sleep(10)
        }
        val stamped = checkNotNull(seen1)
        assertEquals(SetupViewModel.PendingDeleteOutcome.UNKNOWN, stamped.first)
        assertNull(stamped.second)
        assertEquals(SetupViewModel.LOGOUT_READ_RETRY, vm.form.value.errorCode)
        assertEquals("zero vault deletes on read-path UNKNOWN", 0, vault.deleteKeys)
        assertEquals("no clear may be attempted after a strict-read failure", 0, flaky.updateCalls)
        // Flow 此時訂閱：transient 已過 → Absent。
        runBlocking {
            withTimeout(10_000) { vm.pendingTeardown.first { it is PendingTeardownState.Absent } }
        }
        // 按下重試讀取 → NO_OP → 中性碼清除（他碼不動語義另見 D1 既有測試）。
        var seen2: Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>? = null
        val job2 = vm.retryStartupReconcile { outcome, journal ->
            seen2 = outcome to journal
            vm.onStartupReconcile(outcome, journal, gateReady = false)
        }
        runBlocking { withTimeout(10_000) { job2.join() } }
        val clearEnd = System.currentTimeMillis() + 10_000
        while (seen2 == null || vm.form.value.errorCode != null) {
            if (System.currentTimeMillis() > clearEnd) error("timed out waiting for retry clear")
            Thread.sleep(10)
        }
        val cleared = checkNotNull(seen2)
        assertEquals(SetupViewModel.PendingDeleteOutcome.NO_OP, cleared.first)
        assertNull(cleared.second)
        assertNull(vm.form.value.errorCode)
        assertNull(vm.form.value.logoutErrorSourceJournal)
        // Flow 回 Absent、派生 null → 導航 fresh，無殘留卡。
        runBlocking {
            withTimeout(10_000) { vm.pendingTeardown.first { it is PendingTeardownState.Absent } }
        }
        assertNull(
            effectiveLogoutGuardError(null, PendingTeardownState.Absent, logoutInFlight = false),
        )
        assertEquals(true, shouldNavigateToSetup(false, false, Routes.CHAT, null, false))
        assertEquals(false, shouldNavigateToSettings(false, false, Routes.CHAT, null, false))
        // gate Ready：Settings 顯示端點卡（恢復卡無）。
        val gate = runBlocking {
            withTimeout(10_000) { vm.gate.first { it !is EndpointGate.Loading } }
        }
        assertTrue("gate must be Ready after transient recovery, was $gate", gate is EndpointGate.Ready)
        assertEquals(0, vault.deleteKeys)
        runBlocking {
            assertTrue(delegate.hasKey("preset:openai"))
            assertEquals(1L, store.currentRevision())
        }
    }

    @Test
    fun knownPending_resumesToCommitted() {
        // Review (c) 前半：KnownPending 可觀測 → 健康 vault logout resume 同一核心
        // 至 COMMITTED（journal 清、key 刪、meta 清、rev+1）。
        val raw = rawStore("e-known-resume")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val journal = PendingDelete("preset:openai", 1L, 1700000000000L)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            raw.save(preseed())
            assertTrue(raw.writePendingDeleteIfAbsent(journal))
        }
        val store = EndpointStore(raw.dataStore)
        // Store 流發射 KnownPending（已確認存在，非未知態）。
        val observed = runBlocking {
            withTimeout(10_000) { store.observePendingDelete().first() }
        }
        assertEquals(PendingTeardownState.KnownPending(journal), observed)
        // 派生 retained 重試清除（既有 UNKNOWN 語義不動）。
        assertEquals(
            "LOGOUT_UNKNOWN",
            effectiveLogoutGuardError(null, observed, logoutInFlight = false),
        )
        val vault = CountingVault(delegate)
        val vm = newVm(store, vault)
        val done = CompletableDeferred<Boolean>()
        vm.logout { done.complete(it) }
        assertTrue("healthy logout resume must commit", runBlocking { withTimeout(10_000) { done.await() } })
        runBlocking {
            assertNull(store.pendingDelete())
            assertEquals(2L, store.currentRevision())
            assertFalse(delegate.hasKey("preset:openai"))
        }
        assertEquals(listOf("preset:openai"), vault.deletedIds)
        assertNull(vm.form.value.errorCode)
    }

    @Test
    fun malformedPending_failsClosedWithReadFailedAndKeepsJournal() {
        // Review (c) 後半（對照）：Malformed fail-closed——logout 報 READ_FAILED、
        // 零 vault I/O、journal 保留、meta/key/rev 不變；reconcile 走中性
        // READ_RETRY（重試讀取），絕不給重試清除。
        val raw = rawStore("e-malformed-failclosed")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            raw.save(preseed())
            raw.dataStore.edit { prefs ->
                prefs[ENDPOINT_PENDING_DELETE_V1] = "v9|broken"
            }
        }
        val store = EndpointStore(raw.dataStore)
        val observed = runBlocking {
            withTimeout(10_000) { store.observePendingDelete().first() }
        }
        assertEquals(PendingTeardownState.MalformedPending, observed)
        assertTrue(
            "malformed must never pose as a real journal",
            observed !is PendingTeardownState.KnownPending,
        )
        val vault = CountingVault(delegate)
        val vm = newVm(store, vault)
        // logout：strict 讀毀損 → READ_FAILED，副作用零。
        val done = CompletableDeferred<Boolean>()
        vm.logout { done.complete(it) }
        assertFalse("malformed logout must fail", runBlocking { withTimeout(10_000) { done.await() } })
        assertEquals("LOGOUT_READ_FAILED", vm.form.value.errorCode)
        assertNull(vm.form.value.logoutErrorSourceJournal)
        assertEquals(0, vault.deleteKeys)
        assertEquals(0, vault.putKeys)
        assertEquals(0, vault.hasKeys)
        runBlocking {
            assertEquals(
                "malformed journal must survive fail-closed logout",
                "v9|broken",
                store.dataStore.data.first()[ENDPOINT_PENDING_DELETE_V1],
            )
            assertTrue(delegate.hasKey("preset:openai"))
            assertEquals(1L, store.currentRevision())
            val config = store.observe().first()
            assertTrue("lenient readers still fold while malformed is retained", config == null)
        }
        // reconcile 對照：UNKNOWN+null（零 vault I/O）→ 中性 READ_RETRY。
        val (outcome, outJournal) = runBlocking { vm.reconcilePendingDeleteWithJournal() }
        assertEquals(SetupViewModel.PendingDeleteOutcome.UNKNOWN, outcome)
        assertNull("malformed carries no journal identity", outJournal)
        assertEquals("zero vault deletes on malformed", 0, vault.deleteKeys)
        assertEquals(0, vault.putKeys)
        assertEquals(0, vault.hasKeys)
        vm.onStartupReconcile(outcome, outJournal, gateReady = false)
        // logout 的 READ_FAILED 被中性碼覆寫（既有覆寫語義：失敗 stamp 恆勝）——
        // 此處斷言覆寫結果為 READ_RETRY（重試讀取），非 retained UNKNOWN。
        assertEquals(SetupViewModel.LOGOUT_READ_RETRY, vm.form.value.errorCode)
        assertNull(vm.form.value.logoutErrorSourceJournal)
        // 派生對照：Malformed → 重試讀取；KnownPending → 重試清除。
        assertEquals(
            SetupViewModel.LOGOUT_READ_RETRY,
            effectiveLogoutGuardError(null, PendingTeardownState.MalformedPending, logoutInFlight = false),
        )
        val journal = PendingDelete("preset:openai", 1L, 1700000000000L)
        assertEquals(
            "LOGOUT_UNKNOWN",
            effectiveLogoutGuardError(
                null,
                PendingTeardownState.KnownPending(journal),
                logoutInFlight = false,
            ),
        )
    }
}
