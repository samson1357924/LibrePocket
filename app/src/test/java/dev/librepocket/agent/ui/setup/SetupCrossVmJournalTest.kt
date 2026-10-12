package dev.librepocket.agent.ui.setup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage D2（R12 Finding 2 P2/BLOCK）驗收：跨 VM journal 不可見。
 *
 * 雙 VM 同 Store rig（兩 `SetupViewModel` 共享同一 `EndpointStore`／DataStore，
 * 不同 vault；複用既有 `CountingVault`／`ThrowingDeleteVault` 手法）：
 * - Bug 重現（修前：VM-A `NO_OP` 落定後 VM-B 留 `J_B`，VM-A `errorCode` null、
 *   `Setup==true`／`Settings==false`、save 被擋、relaunch 回放 `NO_OP`；修後：
 *   `pendingTeardown` 發射 `KnownPending(J_B)`、派生 `LOGOUT_UNKNOWN` 下 `Setup==false`／
 *   `Settings==true`、relaunch 新鮮 I/O 至 `COMMITTED`）；
 * - (a) 可觀測（`KnownPending(J_B)`；malformed 為 `MalformedPending` 非真 journal，
 *   讀失敗為 `UnknownReadFailure` 未知即 present）；
 * - (b) 派生恢復態（純函數＋守衛真值表，Host／Settings 接線另見專檔）；
 * - Safe reconcile 至 `COMMITTED`（健康 vault、同源清碼、终態 fresh SETUP 可達、
 *   save 重試 `saved==true`＋READY）；
 * - (c) rotation（外部 `J_B`→新鮮 I/O；無 journal 常態 vault 零 I/O）。
 *
 * 不改 D1（新碼／UI／清除）、映射、取消語義；不做 timeout。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupCrossVmJournalTest {

    private val tmpDirs = ArrayList<java.io.File>()
    private val scopes = ArrayList<CoroutineScope>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        // D2: cancel DataStore scopes BEFORE resetMain — pendingTeardown/gate
        // collect store flows via viewModelScope(Main); cancelling after reset
        // would dispatch via a reset Main and fail teardown (not the test).
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
            produceFile = { java.io.File(dir, "d2-crossvm.preferences_pb") },
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
    fun bugRepro_crossVmJournalVisibleViaPendingTeardownAndDerivedRecovery() {
        // 驗收 1（修後為恢復）：VM-A NO_OP 落定；VM-B logout（throwing vault）留 J_B
        // 後丟棄；斷言 VM-A 側 domain state＋派生＋fence＋新鮮 relaunch。
        val store = rawStore("d2-bug-repro")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
        }
        val vaultA = CountingVault(delegate)
        val vmA = newVm(store, vaultA)
        // VM-A 首輪：無 journal → NO_OP 落定（修前行為起點）。
        var seenA1: Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>? = null
        val jobA1 = vmA.launchPendingDeleteReconcile { outcome, j -> seenA1 = outcome to j }
        runBlocking { withTimeout(10_000) { jobA1.join() } }
        assertEquals(SetupViewModel.PendingDeleteOutcome.NO_OP to null, seenA1)
        assertFalse(vmA.startupReconcilePending.value)
        assertNull(vmA.form.value.errorCode)
        // VM-B：同 Store、throwing vault，logout 留 J_B（delete 失敗、journal 保留）後丟棄。
        val vmB = newVm(store, ThrowingDeleteVault(delegate))
        val doneB = CompletableDeferred<Boolean>()
        vmB.logout { doneB.complete(it) }
        assertFalse("VM-B throwing logout must fail", runBlocking { withTimeout(10_000) { doneB.await() } })
        assertEquals("LOGOUT_DELETE_FAILED", vmB.form.value.errorCode)
        val journalB = runBlocking { store.pendingDelete() }
        assertNotNull("VM-B must leave J_B", journalB)
        assertEquals("preset:openai", journalB!!.alias)
        assertEquals(1L, journalB.revision)
        // VM-A 側 domain state：pendingDelete==J_B、observe null（折疊 NoEndpoint）、
        // gate NoEndpoint、errorCode null（修前：無碼、無恢復入口）。
        runBlocking {
            assertEquals(journalB, store.pendingDelete())
            assertNull("observe folds while J_B retained", store.observe().first())
        }
        val gateA = runBlocking {
            withTimeout(10_000) { vmA.gate.first { it !is EndpointGate.Loading } }
        }
        assertTrue("gate folds to NoEndpoint while J_B retained", gateA is EndpointGate.NoEndpoint)
        assertNull("VM-A errorCode stays null (cross-VM invisible pre-D2)", vmA.form.value.errorCode)
        // 修前守衛（raw null）：誤判 fresh（Setup==true/Settings==false）。
        assertEquals(true, shouldNavigateToSetup(false, false, Routes.CHAT, null, false))
        assertEquals(false, shouldNavigateToSettings(false, false, Routes.CHAT, null, false))
        // save 被擋（fence 存活，journal 未被掩蓋）。
        vmA.onApiKeyChange("unit-test-fresh-key-cccccccc")
        vmA.save()
        awaitSaveSettled(vmA)
        assertFalse(vmA.form.value.saved)
        assertEquals("SAVE_BLOCKED_BY_TEARDOWN", vmA.form.value.errorCode)
        // VM-A pendingTeardown 發射 KnownPending(J_B)（(a) 可觀測，修後恢復基礎）。
        val tornDown = runBlocking {
            withTimeout(10_000) { vmA.pendingTeardown.first { it is PendingTeardownState.KnownPending } }
        }
        assertEquals(journalB, (tornDown as PendingTeardownState.KnownPending).pending)
        // 派生恢復態（(b)）：effective LOGOUT_UNKNOWN 下 Setup==false/Settings==true。
        val effective = effectiveLogoutGuardError(
            null,
            PendingTeardownState.KnownPending(journalB),
            logoutInFlight = false,
        )
        assertEquals("LOGOUT_UNKNOWN", effective)
        assertEquals(false, shouldNavigateToSetup(false, false, Routes.CHAT, effective, false))
        assertEquals(true, shouldNavigateToSettings(false, false, Routes.CHAT, effective, false))
        // 為何 LOGOUT_UNKNOWN（未確認路徑禁寫已刪除；與 D1 READ_RETRY 區分）：
        // 文案禁寫「已刪除」，且非 READ_RETRY（D1 新碼＝讀失敗未知，此處＝已觀測存在）。
        assertFalse(setupErrorText(effective!!).contains("已刪除"))
        assertFalse(effective == SetupViewModel.LOGOUT_READ_RETRY)
        // relaunch 新鮮 I/O（修後；修前回放 NO_OP）：deleteKeys 增、回 COMMITTED+J_B。
        val deletesBefore = vaultA.deleteKeys
        var seenA2: Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>? = null
        val jobA2 = vmA.launchPendingDeleteReconcile { outcome, j -> seenA2 = outcome to j }
        runBlocking { withTimeout(10_000) { jobA2.join() } }
        assertTrue(
            "relaunch must run fresh vault I/O, not replay settled NO_OP",
            vaultA.deleteKeys > deletesBefore,
        )
        assertEquals(SetupViewModel.PendingDeleteOutcome.COMMITTED, seenA2?.first)
        assertEquals(journalB, seenA2?.second)
    }

    @Test
    fun pendingTeardown_malformedIsUnconfirmed() {
        // Stage E 改寫（sentinel→四態）：malformed 為 MalformedPending（非真
        // journal——絕不是 KnownPending，身份不可用於 vault／clear 路徑），派生
        // 走非破壞性重試讀取（D1 READ_RETRY 複用），絕不給重試清除。
        val store = rawStore("d2-malformed-present")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
            store.dataStore.edit { prefs ->
                prefs[ENDPOINT_PENDING_DELETE_V1] = "v9|broken"
            }
        }
        // Store 流：malformed 發射 MalformedPending（未確認即 present，fail-closed）。
        val observed = runBlocking {
            withTimeout(10_000) { store.observePendingDelete().first() }
        }
        assertEquals(PendingTeardownState.MalformedPending, observed)
        assertTrue(
            "malformed must never pose as a real journal",
            observed !is PendingTeardownState.KnownPending,
        )
        // VM 流：同樣未確認（WhileSubscribed 初值 Absent，收集後發射）。
        val vm = newVm(store, CountingVault(delegate))
        val tornDown = runBlocking {
            withTimeout(10_000) { vm.pendingTeardown.first { it is PendingTeardownState.MalformedPending } }
        }
        assertEquals(PendingTeardownState.MalformedPending, tornDown)
        // 派生非破壞性：中性 READ_RETRY（D1 碼複用），非 retained UNKNOWN。
        val effective = effectiveLogoutGuardError(null, tornDown, logoutInFlight = false)
        assertEquals(SetupViewModel.LOGOUT_READ_RETRY, effective)
        assertFalse("malformed must never derive retry-clear", effective == "LOGOUT_UNKNOWN")
        // lenient observe 仍折疊（NoEndpoint），fence 仍擋 save（不同站點，不在此重斷）。
        runBlocking {
            assertNull(store.observe().first())
        }
    }

    @Test
    fun safeReconcileToCommittedViaLogout_clearsJournalAndUnblocksSave() {
        // 驗收 4：VM-A 健康 vault 經既有 onLogout resume J_B 至 COMMITTED（同一
        // runPendingDeleteLocked 核心，不新增 vault 路徑；落碼／清碼走既有語義）。
        val store = rawStore("d2-safe-reconcile")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val journal = PendingDelete("preset:openai", 1L, 1700000000000L)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
            assertTrue(store.writePendingDeleteIfAbsent(journal))
        }
        val vaultA = CountingVault(delegate)
        val vmA = newVm(store, vaultA)
        // 先經啟動路徑落 retained 碼（帶 tag），證明同源清碼。
        vmA.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.DELETE_FAILED, journal, gateReady = false)
        assertEquals("LOGOUT_DELETE_FAILED", vmA.form.value.errorCode)
        assertEquals(journal.format(), vmA.form.value.logoutErrorSourceJournal)
        // Safe reconcile 入口：複用 onLogout（見 present journal 走 resume 分支）。
        assertNull(vmA.lastLogoutJobForTest)
        val done = CompletableDeferred<Boolean>()
        vmA.logout { done.complete(it) }
        assertTrue("healthy logout resume must commit", runBlocking { withTimeout(10_000) { done.await() } })
        // COMMITTED 攜 J_B 語義：journal 空、rev+1、同源清碼（整表重置，碼＋tag 空）。
        runBlocking {
            assertNull(store.pendingDelete())
            assertEquals(2L, store.currentRevision())
            assertFalse(store.hasMetadata())
            assertFalse(delegate.hasKey("preset:openai"))
        }
        assertNull(vmA.form.value.errorCode)
        assertNull(vmA.form.value.logoutErrorSourceJournal)
        assertEquals(listOf("preset:openai"), vaultA.deletedIds)
        // 終態 fresh SETUP 可達（無碼、NoEndpoint、非 inFlight → 強制跳轉）。
        assertTrue(shouldNavigateToSetup(false, false, Routes.SETTINGS, null, false))
        // save 重試 saved==true＋READY（fence 已釋放，新 staged 提交）。
        vmA.onApiKeyChange("unit-test-fresh-key-cccccccc")
        vmA.save()
        awaitSaveSettled(vmA)
        assertTrue("save must succeed after COMMITTED", vmA.form.value.saved)
        assertNull(vmA.form.value.errorCode)
        runBlocking {
            val observed = store.observe().first()
            assertTrue(observed != null)
            assertTrue(observed!!.apiKeyRef.startsWith("provider_key/preset:openai#staged-"))
            assertEquals(EndpointReadiness.READY, store.readiness(delegate))
        }
    }

    @Test
    fun rotation_externalJournalRunsFreshIo_quiescentStaysZeroVaultIo() {
        // 驗收 5 (c)：NO_OP 後外部寫 J_B → relaunch 新鮮 I/O（deleteKeys 增）回
        // COMMITTED＋J_B；無 journal 常態 vault 零 I/O（既有 relaunchAfterSettleNoIO
        // 語義不變，只多一讀）。
        val store = rawStore("d2-rotation-fresh")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
        }
        val vault = CountingVault(delegate)
        val vm = newVm(store, vault)
        var seen1: Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>? = null
        val job1 = vm.launchPendingDeleteReconcile { outcome, j -> seen1 = outcome to j }
        runBlocking { withTimeout(10_000) { job1.join() } }
        assertEquals(SetupViewModel.PendingDeleteOutcome.NO_OP to null, seen1)
        assertFalse(vm.startupReconcilePending.value)
        // 常態：無 journal 重 launch 回放 NO_OP、vault 零 I/O（probe 只多一讀）。
        val deletes = vault.deleteKeys
        val puts = vault.putKeys
        val has = vault.hasKeys
        var seenQ: Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>? = null
        val jobQ = vm.launchPendingDeleteReconcile { outcome, j -> seenQ = outcome to j }
        assertFalse("settled replay must not touch the fence", vm.startupReconcilePending.value)
        runBlocking { withTimeout(10_000) { jobQ.join() } }
        assertEquals(seen1, seenQ)
        assertEquals(deletes, vault.deleteKeys)
        assertEquals(puts, vault.putKeys)
        assertEquals(has, vault.hasKeys)
        // 外部寫 J_B（跨 VM）：同 emission revision 不變（journal 不進 rev），raw 出現。
        val journalB = PendingDelete("preset:openai", 1L, 1700000000001L)
        runBlocking {
            assertTrue(store.writePendingDeleteIfAbsent(journalB))
        }
        // rotation 重 launch：generation 不同 → 失效後新鮮 worker（deleteKeys 增）。
        val deletesBefore = vault.deleteKeys
        var seen2: Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>? = null
        val job2 = vm.launchPendingDeleteReconcile { outcome, j -> seen2 = outcome to j }
        runBlocking { withTimeout(10_000) { job2.join() } }
        assertTrue("external J_B must trigger fresh vault I/O", vault.deleteKeys > deletesBefore)
        assertEquals(SetupViewModel.PendingDeleteOutcome.COMMITTED, seen2?.first)
        assertEquals(journalB, seen2?.second)
        runBlocking {
            assertNull(store.pendingDelete())
            assertEquals(2L, store.currentRevision())
        }
    }

    @Test
    fun effectiveLogoutGuardError_truthTable() {
        // Stage E 四態真值表（純函數，含 derived 碼行；NavGuard 互斥語義不變）：
        // - 有碼沿用（STALE 等不動，不論四態）；
        // - 無碼＋KnownPending＋非 inFlight → LOGOUT_UNKNOWN（重試清除）；
        // - 無碼＋UnknownReadFailure／MalformedPending＋非 inFlight →
        //   LOGOUT_READ_RETRY（D1 碼複用，重試讀取 only）；
        // - inFlight 抑制派生（執行中不決策）；
        // - Absent 常態 null（fresh 不變）。
        val journal = PendingDelete("preset:openai", 1L, 1700000000001L)
        val known = PendingTeardownState.KnownPending(journal)
        assertEquals(
            "LOGOUT_STALE",
            effectiveLogoutGuardError("LOGOUT_STALE", known, logoutInFlight = false),
        )
        assertEquals(
            "LOGOUT_DELETE_FAILED",
            effectiveLogoutGuardError(
                "LOGOUT_DELETE_FAILED",
                PendingTeardownState.Absent,
                logoutInFlight = false,
            ),
        )
        assertEquals(
            "LOGOUT_STALE",
            effectiveLogoutGuardError(
                "LOGOUT_STALE",
                PendingTeardownState.UnknownReadFailure,
                logoutInFlight = false,
            ),
        )
        assertEquals(
            "LOGOUT_UNKNOWN",
            effectiveLogoutGuardError(null, known, logoutInFlight = false),
        )
        assertEquals(
            SetupViewModel.LOGOUT_READ_RETRY,
            effectiveLogoutGuardError(null, PendingTeardownState.UnknownReadFailure, logoutInFlight = false),
        )
        assertEquals(
            SetupViewModel.LOGOUT_READ_RETRY,
            effectiveLogoutGuardError(null, PendingTeardownState.MalformedPending, logoutInFlight = false),
        )
        assertNull(
            effectiveLogoutGuardError(null, known, logoutInFlight = true),
        )
        assertNull(
            effectiveLogoutGuardError(null, PendingTeardownState.UnknownReadFailure, logoutInFlight = true),
        )
        assertNull(
            effectiveLogoutGuardError(null, PendingTeardownState.MalformedPending, logoutInFlight = true),
        )
        assertNull(
            effectiveLogoutGuardError(null, PendingTeardownState.Absent, logoutInFlight = false),
        )
        // derived KnownPending 碼行：Setup 抑制＋CHAT→SETTINGS（與既有 LOGOUT_* 一致，互斥）.
        val derived = effectiveLogoutGuardError(null, known, logoutInFlight = false)
        assertEquals(false, shouldNavigateToSetup(false, false, Routes.CHAT, derived, false))
        assertEquals(false, shouldNavigateToSetup(false, false, Routes.SETTINGS, derived, false))
        assertEquals(true, shouldNavigateToSettings(false, false, Routes.CHAT, derived, false))
        assertEquals(false, shouldNavigateToSettings(false, false, Routes.SETTINGS, derived, false))
        assertEquals(false, shouldNavigateToSettings(false, false, Routes.CHAT, derived, true))
        // derived 未確認態同走 SETTINGS（重試讀取卡在 Settings），互斥不走 SETUP.
        val derivedRetry = effectiveLogoutGuardError(
            null,
            PendingTeardownState.UnknownReadFailure,
            logoutInFlight = false,
        )
        assertEquals(false, shouldNavigateToSetup(false, false, Routes.CHAT, derivedRetry, false))
        assertEquals(true, shouldNavigateToSettings(false, false, Routes.CHAT, derivedRetry, false))
        // fresh 對照：無碼走 SETUP，不走 SETTINGS.
        assertEquals(true, shouldNavigateToSetup(false, false, Routes.CHAT, null, false))
        assertEquals(false, shouldNavigateToSettings(false, false, Routes.CHAT, null, false))
    }
}
