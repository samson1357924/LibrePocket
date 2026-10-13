package dev.librepocket.agent.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.agent.ui.setup.CountingVault
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.agent.ui.setup.GatedDeleteVault
import dev.librepocket.agent.ui.setup.MutablePolicy
import dev.librepocket.agent.ui.setup.PendingDelete
import dev.librepocket.agent.ui.setup.SetupViewModel
import dev.librepocket.agent.ui.setup.ThrowingDeleteVault
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.keystore.KeyVault
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * Stage C（R11 Finding P2/BLOCK）：startup replay generation 競態修復驗收。
 *
 * - C1 single-flight worker + settled 回放：重疊 double-launch 共享同一輪
 *   vault I/O（解 premature unlock：第二輪不再提前落定 fence；解 fence 缺口：
 *   無第二份 I/O）；落定後重 launch 回放快照（零 I/O）；join handle cancel
 *   不殺 worker；logout/save 終端使 settled 失效。
 * - C2 journal-identity tag：失敗落碼攜帶來源 journal；`COMMITTED` 僅清同源
 *   碼；`NO_OP` 永不清。
 *
 * 不改守衛／NavGuard／host／Settings 語義；timeout 列 follow-up
 *（F-UP-REC-TIMEOUT），此檔不測超時。
 */
private class GatedThrowingDeleteVault(
    private val delegate: KeyVault = EncryptedPrefsVault(InMemoryPrefs()),
    val deleteEntered: CompletableDeferred<Unit> = CompletableDeferred(),
    val deleteRelease: CompletableDeferred<Unit> = CompletableDeferred(),
) : KeyVault {
    var deleteAttempts = 0
        private set
    override suspend fun putKey(providerId: String, apiKey: CharArray) = delegate.putKey(providerId, apiKey)
    override suspend fun getKey(providerId: String): CharArray? = delegate.getKey(providerId)
    override suspend fun deleteKey(providerId: String) {
        deleteAttempts++
        deleteEntered.complete(Unit)
        deleteRelease.await()
        throw IOException("DELETE_FAILED")
    }
    override suspend fun hasKey(providerId: String): Boolean = delegate.hasKey(providerId)
}

@OptIn(ExperimentalCoroutinesApi::class)
class StartupReconcileSingleFlightTest {

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
            produceFile = { java.io.File(dir, "singleflight.preferences_pb") },
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

    private fun seedJournal(store: EndpointStore, delegate: KeyVault, journal: PendingDelete) {
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
            assertTrue(store.writePendingDeleteIfAbsent(journal))
        }
    }

    @Test
    fun overlappingDoubleLaunchSharesSingleDelete() {
        // C1-1：雙 launch 重疊 → 同一 worker、deleteKeys==1、雙 onDone 同值；
        // 重疊期間 pending 恆 true（bug 版 R1 結束即 pending=false 不再出現；
        // 兼覆 C1-9 secondLaunchRearmsPending：無第二份 I/O，結構上無缺口）。
        val journal = PendingDelete("preset:openai", 1L, 1700000000000L)
        val store = rawStore("singleflight-overlap")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        seedJournal(store, delegate, journal)
        val vault = GatedDeleteVault(delegate = delegate, gateDelete = true)
        val vm = newVm(store, vault)
        var seen1: Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>? = null
        var seen2: Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>? = null
        val job1 = vm.launchPendingDeleteReconcile { outcome, j -> seen1 = outcome to j }
        runBlocking { withTimeout(10_000) { vault.deleteEntered.await() } }
        assertTrue(vm.startupReconcilePending.value)
        val job2 = vm.launchPendingDeleteReconcile { outcome, j -> seen2 = outcome to j }
        assertTrue("overlapping second launch must not settle the fence early", vm.startupReconcilePending.value)
        vault.gateDelete = false
        vault.deleteRelease.complete(Unit)
        runBlocking { withTimeout(10_000) { job1.join(); job2.join() } }
        assertEquals(SetupViewModel.PendingDeleteOutcome.COMMITTED, seen1?.first)
        assertEquals("both launches share the single round", seen1, seen2)
        assertEquals(journal, seen1?.second)
        assertEquals("single-flight: exactly one vault delete", 1, vault.deleteKeys)
        assertFalse(vm.startupReconcilePending.value)
        runBlocking { assertNull(store.pendingDelete()) }
    }

    @Test
    fun overlappingFailedStampsSingleCode() {
        // C1-2：重疊雙 launch + DELETE_FAILED → 同值回放、碼單次語義、journal 保留。
        val journal = PendingDelete("preset:openai", 1L, 1700000000000L)
        val store = rawStore("singleflight-overlap-fail")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        seedJournal(store, delegate, journal)
        val vault = GatedThrowingDeleteVault(delegate)
        val vm = newVm(store, vault)
        var seen1: Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>? = null
        var seen2: Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>? = null
        val job1 = vm.launchPendingDeleteReconcile { outcome, j ->
            seen1 = outcome to j
            vm.onStartupReconcile(outcome, j, gateReady = false)
        }
        runBlocking { withTimeout(10_000) { vault.deleteEntered.await() } }
        val job2 = vm.launchPendingDeleteReconcile { outcome, j ->
            seen2 = outcome to j
            vm.onStartupReconcile(outcome, j, gateReady = false)
        }
        vault.deleteRelease.complete(Unit)
        runBlocking { withTimeout(10_000) { job1.join(); job2.join() } }
        assertEquals(SetupViewModel.PendingDeleteOutcome.DELETE_FAILED, seen1?.first)
        assertEquals(seen1, seen2)
        assertEquals(1, vault.deleteAttempts)
        assertEquals("LOGOUT_DELETE_FAILED", vm.form.value.errorCode)
        assertEquals(journal.format(), vm.form.value.logoutErrorSourceJournal)
        runBlocking {
            assertEquals("preset:openai", store.pendingDelete()?.alias)
            assertTrue("key survives DELETE_FAILED", delegate.hasKey("preset:openai"))
        }
    }

    @Test
    fun relaunchAfterSettleNoIO() {
        // C1-3：NO_OP 落定後重 launch → pending 恆 false、零 I/O、回放同值、碼 null。
        val store = rawStore("singleflight-relaunch-noop")
        val vault = CountingVault()
        val vm = newVm(store, vault)
        var seen1: Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>? = null
        val job1 = vm.launchPendingDeleteReconcile { outcome, j -> seen1 = outcome to j }
        runBlocking { withTimeout(10_000) { job1.join() } }
        assertEquals(SetupViewModel.PendingDeleteOutcome.NO_OP to null, seen1)
        assertFalse(vm.startupReconcilePending.value)
        val deletes = vault.deleteKeys
        val puts = vault.putKeys
        val has = vault.hasKeys
        var seen2: Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>? = null
        val job2 = vm.launchPendingDeleteReconcile { outcome, j -> seen2 = outcome to j }
        assertFalse("settled replay must not touch the fence", vm.startupReconcilePending.value)
        runBlocking { withTimeout(10_000) { job2.join() } }
        assertEquals(seen1, seen2)
        assertEquals("replay performs zero vault I/O", deletes, vault.deleteKeys)
        assertEquals(puts, vault.putKeys)
        assertEquals(has, vault.hasKeys)
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.startupReconcilePending.value)
    }

    @Test
    fun relaunchAfterFailedSettleReplaysWithoutIO() {
        // C1-4：DELETE_FAILED 落定後重 launch → 零新增 delete、碼不變、同值回放。
        val journal = PendingDelete("preset:openai", 1L, 1700000000000L)
        val store = rawStore("singleflight-relaunch-fail")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        seedJournal(store, delegate, journal)
        val vault = ThrowingDeleteVault(delegate)
        val vm = newVm(store, vault)
        var seen1: Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>? = null
        val job1 = vm.launchPendingDeleteReconcile { outcome, j -> seen1 = outcome to j }
        runBlocking { withTimeout(10_000) { job1.join() } }
        assertEquals(SetupViewModel.PendingDeleteOutcome.DELETE_FAILED, seen1?.first)
        assertEquals(journal, seen1?.second)
        vm.onStartupReconcile(seen1!!.first, seen1.second, gateReady = false)
        assertEquals("LOGOUT_DELETE_FAILED", vm.form.value.errorCode)
        assertEquals(1, vault.deleteAttempts)
        var seen2: Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>? = null
        val job2 = vm.launchPendingDeleteReconcile { outcome, j -> seen2 = outcome to j }
        runBlocking { withTimeout(10_000) { job2.join() } }
        assertEquals(seen1, seen2)
        assertEquals("replay must not re-run the vault delete", 1, vault.deleteAttempts)
        assertEquals("LOGOUT_DELETE_FAILED", vm.form.value.errorCode)
        assertEquals(journal.format(), vm.form.value.logoutErrorSourceJournal)
    }

    @Test
    fun cancelJoinKeepsFenceWhileWorkerActive() {
        // C1-5：cancel join handle 不殺 worker（pending 仍 true）；放行後落定。
        val journal = PendingDelete("preset:openai", 1L, 1700000000000L)
        val store = rawStore("singleflight-cancel-join")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        seedJournal(store, delegate, journal)
        val vault = GatedDeleteVault(delegate = delegate, gateDelete = true)
        val vm = newVm(store, vault)
        var seen1: Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>? = null
        val job1 = vm.launchPendingDeleteReconcile { outcome, j -> seen1 = outcome to j }
        runBlocking { withTimeout(10_000) { vault.deleteEntered.await() } }
        val job2 = vm.launchPendingDeleteReconcile { _, _ -> }
        job2.cancel()
        runBlocking {
            try {
                withTimeout(10_000) { job2.join() }
            } catch (_: CancellationException) {
                // Join handle cancel: expected, worker unaffected.
            }
        }
        assertTrue("cancel must not kill the shared worker", vm.startupReconcilePending.value)
        assertEquals("worker still parked on the single delete", 1, vault.deleteKeys)
        vault.gateDelete = false
        vault.deleteRelease.complete(Unit)
        runBlocking { withTimeout(10_000) { job1.join() } }
        assertEquals(SetupViewModel.PendingDeleteOutcome.COMMITTED to journal, seen1)
        assertFalse(vm.startupReconcilePending.value)
    }

    @Test
    fun deleteFailedThenCommittedClearsStaleCode() {
        // C2-6：J1 DELETE 落碼 → 健康 vault COMMITTED(J1) → 碼清空 + tag 空 +
        // store journal／metadata 空 + NavGuard 終態 fresh SETUP 可達。
        val journal = PendingDelete("preset:openai", 1L, 1700000000000L)
        val store = rawStore("singleflight-clear-same")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        seedJournal(store, delegate, journal)
        var currentVault: KeyVault = ThrowingDeleteVault(delegate)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { currentVault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        val (failed, failedJournal) = runBlocking { vm.reconcilePendingDeleteWithJournal() }
        assertEquals(SetupViewModel.PendingDeleteOutcome.DELETE_FAILED, failed)
        assertEquals(journal, failedJournal)
        vm.onStartupReconcile(failed, failedJournal, gateReady = false)
        assertEquals("LOGOUT_DELETE_FAILED", vm.form.value.errorCode)
        assertEquals(journal.format(), vm.form.value.logoutErrorSourceJournal)
        currentVault = delegate
        val (committed, committedJournal) = runBlocking { vm.reconcilePendingDeleteWithJournal() }
        assertEquals(SetupViewModel.PendingDeleteOutcome.COMMITTED, committed)
        assertEquals(journal, committedJournal)
        vm.onStartupReconcile(committed, committedJournal, gateReady = false)
        assertNull("same-journal COMMITTED must clear the banner", vm.form.value.errorCode)
        assertNull(vm.form.value.logoutErrorSourceJournal)
        runBlocking {
            assertNull(store.pendingDelete())
            assertFalse(store.hasMetadata())
        }
        assertTrue(
            "fresh terminal state must reach SETUP",
            shouldNavigateToSetup(false, false, Routes.SETTINGS, null, false),
        )
    }

    @Test
    fun committedKeepsConcurrentLogoutCode() {
        // C2-7：COMMITTED(J1) 不清來源 J2 的碼（雙序皆成立）。
        val journalJ1 = PendingDelete("preset:openai", 1L, 1700000000000L)
        val journalJ2 = PendingDelete(
            "preset:openai#staged-cccccccccccccccccccccccccccccccc",
            2L,
            1700000000001L,
        )
        // 序 A：J2 碼先落，COMMITTED(J1) 到達 → 碼保留。
        val vmA = newVm(rawStore("singleflight-keep-a"), CountingVault())
        vmA.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.DELETE_FAILED, journalJ2, gateReady = false)
        assertEquals("LOGOUT_DELETE_FAILED", vmA.form.value.errorCode)
        vmA.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.COMMITTED, journalJ1, gateReady = false)
        assertEquals("LOGOUT_DELETE_FAILED", vmA.form.value.errorCode)
        assertEquals(journalJ2.format(), vmA.form.value.logoutErrorSourceJournal)
        // 序 B：COMMITTED(J1) 先到（靜默），J2 失敗後落碼 → 碼照常落下。
        val vmB = newVm(rawStore("singleflight-keep-b"), CountingVault())
        vmB.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.COMMITTED, journalJ1, gateReady = false)
        assertNull(vmB.form.value.errorCode)
        vmB.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.DELETE_FAILED, journalJ2, gateReady = false)
        assertEquals("LOGOUT_DELETE_FAILED", vmB.form.value.errorCode)
        assertEquals(journalJ2.format(), vmB.form.value.logoutErrorSourceJournal)
    }

    @Test
    fun logoutSuccessInvalidatesSettled() {
        // C1-8：logout 成功使 settled 失效——rotation 重 launch 走新鮮 I/O
        //（NO_OP），不重播舊 DELETE_FAILED。
        val journal = PendingDelete("preset:openai", 1L, 1700000000000L)
        val store = rawStore("singleflight-logout-invalidate")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        seedJournal(store, delegate, journal)
        val healthy = CountingVault(delegate)
        var currentVault: KeyVault = ThrowingDeleteVault(delegate)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { currentVault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        var seen1: Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>? = null
        val job1 = vm.launchPendingDeleteReconcile { outcome, j -> seen1 = outcome to j }
        runBlocking { withTimeout(10_000) { job1.join() } }
        assertEquals(SetupViewModel.PendingDeleteOutcome.DELETE_FAILED, seen1?.first)
        vm.onStartupReconcile(seen1!!.first, seen1.second, gateReady = false)
        assertEquals("LOGOUT_DELETE_FAILED", vm.form.value.errorCode)
        // 健康 vault 重試 logout：resume J1 → COMMITTED 成功、整表重置。
        currentVault = healthy
        val done = CompletableDeferred<Boolean>()
        vm.logout { done.complete(it) }
        assertTrue("healthy logout retry must commit", runBlocking { withTimeout(10_000) { done.await() } })
        assertNull(vm.form.value.errorCode)
        assertEquals(1, healthy.deleteKeys)
        // Rotation 重 launch：新鮮 I/O（journal 已除、metadata 已清 → NO_OP），
        // 絕非 settled 重播（否則仍是 DELETE_FAILED）。
        var seen2: Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>? = null
        val job2 = vm.launchPendingDeleteReconcile { outcome, j -> seen2 = outcome to j }
        runBlocking { withTimeout(10_000) { job2.join() } }
        assertEquals(SetupViewModel.PendingDeleteOutcome.NO_OP, seen2?.first)
        assertNull(seen2?.second)
        assertEquals("relaunch must add zero vault deletes", 1, healthy.deleteKeys)
        assertNull(vm.form.value.errorCode)
    }

    @Test
    fun logoutCancelAfterJournalWriteInvalidatesSettled() {
        // 入口失效驗收：第一輪 NO_OP 落定後起 logout（journal 寫入後、vault
        // delete 內取消，journal 留存）→ rotation 重 launch 必須走新鮮 I/O
        //（deleteKeys 增加）且發現 journal（COMMITTED 攜 journal），而非回放
        // 過期 NO_OP（舊碼無入口失效即落此死路）。
        val store = rawStore("singleflight-logout-cancel-invalidate")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
        }
        val vault = GatedDeleteVault(delegate = delegate, gateDelete = true)
        val vm = newVm(store, vault)
        // 第一輪：無 journal → NO_OP 落定。
        var seen1: Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>? = null
        val job1 = vm.launchPendingDeleteReconcile { outcome, j -> seen1 = outcome to j }
        runBlocking { withTimeout(10_000) { job1.join() } }
        assertEquals(SetupViewModel.PendingDeleteOutcome.NO_OP to null, seen1)
        // 起 logout：delete 入口到達即保證 journal 已寫入，其後取消。
        var onDoneCalled = false
        vm.logout { onDoneCalled = true }
        runBlocking { withTimeout(10_000) { vault.deleteEntered.await() } }
        val logoutJob = vm.lastLogoutJobForTest ?: error("no logout job")
        runBlocking {
            logoutJob.cancel()
            logoutJob.join()
        }
        assertFalse("cancel must not call onDone", onDoneCalled)
        val journal = runBlocking { store.pendingDelete() }
        assertEquals("journal must survive the post-write cancel", "preset:openai", journal?.alias)
        // 放行 vault；rotation 重 launch 必須走新鮮 I/O。
        val deletesBefore = vault.deleteKeys
        vault.gateDelete = false
        vault.deleteRelease.complete(Unit)
        var seen2: Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>? = null
        val job2 = vm.launchPendingDeleteReconcile { outcome, j -> seen2 = outcome to j }
        runBlocking { withTimeout(10_000) { job2.join() } }
        assertTrue(
            "relaunch must run fresh vault I/O, not replay settled NO_OP",
            vault.deleteKeys > deletesBefore,
        )
        assertEquals(
            "relaunch must discover the retained journal",
            SetupViewModel.PendingDeleteOutcome.COMMITTED,
            seen2?.first,
        )
        assertEquals(journal, seen2?.second)
    }

    @Test
    fun drainWindowInvalidateRejoinDeliversExactlyOnce() {
        // R4 主回歸：A drain（已封存）後停在 Main fan-out 前；retry 失效並重入。
        // B 不得 join 已封存的 A（否則 B join 完成但 callback stranded），必須跑
        // 新一輪；兩位 callback 各恰好一次，且 join 返回即已送達。
        val store = rawStore("r4-drain-window")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
        }
        val vault = CountingVault(delegate)
        val vm = newVm(store, vault)
        val drainCount = java.util.concurrent.atomic.AtomicInteger(0)
        val enteredA = java.util.concurrent.CountDownLatch(1)
        val enteredB = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        vm.afterDrainBeforeMainForTest = {
            when (drainCount.incrementAndGet()) {
                1 -> enteredA.countDown()
                2 -> enteredB.countDown()
            }
            if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                error("drain seam timeout")
            }
        }
        val seenA = java.util.Collections.synchronizedList(
            ArrayList<Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>>(),
        )
        val seenB = java.util.Collections.synchronizedList(
            ArrayList<Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>>(),
        )
        val jobA = vm.launchPendingDeleteReconcile { outcome, j -> seenA.add(outcome to j) }
        assertTrue(enteredA.await(10, java.util.concurrent.TimeUnit.SECONDS))
        // A 已 drain（settled 已寫、已封存）但 fan-out 未送：此時失效＋重入。
        val jobB = vm.retryStartupReconcile { outcome, j -> seenB.add(outcome to j) }
        assertTrue(enteredB.await(10, java.util.concurrent.TimeUnit.SECONDS))
        release.countDown()
        runBlocking {
            withTimeout(10_000) { jobA.join() }
            withTimeout(10_000) { jobB.join() }
        }
        // join 返回即已送達（不再輪詢）：各恰好一次，皆為 NO_OP＋null。
        assertEquals(1, seenA.size)
        assertEquals(1, seenB.size)
        assertEquals(SetupViewModel.PendingDeleteOutcome.NO_OP, seenA.single().first)
        assertNull(seenA.single().second)
        assertEquals(SetupViewModel.PendingDeleteOutcome.NO_OP, seenB.single().first)
        assertNull(seenB.single().second)
        // NO_OP 兩輪皆零 vault 寫入（strict 讀而已）；B 跑的是新一輪 I/O，
        // 不是回放（回放亦 NO_OP，但 B 若 join 舊 A 則 seenB 為空——上已斷言）。
        assertEquals(0, vault.putKeys)
        assertEquals(0, vault.deleteKeys)
        assertEquals(0, vault.hasKeys)
    }

    @Test
    fun drainedSealedOverlapKeepsPendingUntilNewWorkerSettles() {
        // F2 主回歸：A 已 drain＋封存（停 Main 前），B 啟動後亦停住；
        // 放行 A 先退出時 pending 必須維持 true（舊世代 finally 不得釋放新
        // 世代 fence），B 落定後才置 false。兩 callback 各恰好一次。
        val store = rawStore("f2-overlap-pending")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
        }
        val vault = CountingVault(delegate)
        val vm = newVm(store, vault)
        assertTrue(vm.startupReconcilePending.value)
        val drainCount = java.util.concurrent.atomic.AtomicInteger(0)
        val enteredA = java.util.concurrent.CountDownLatch(1)
        val enteredB = java.util.concurrent.CountDownLatch(1)
        val releaseA = java.util.concurrent.CountDownLatch(1)
        val releaseB = java.util.concurrent.CountDownLatch(1)
        vm.afterDrainBeforeMainForTest = {
            when (drainCount.incrementAndGet()) {
                1 -> {
                    enteredA.countDown()
                    if (!releaseA.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        error("drain seam A timeout")
                    }
                }
                2 -> {
                    enteredB.countDown()
                    if (!releaseB.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        error("drain seam B timeout")
                    }
                }
                else -> error("unexpected third drain")
            }
        }
        val seenA = java.util.Collections.synchronizedList(
            ArrayList<Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>>(),
        )
        val seenB = java.util.Collections.synchronizedList(
            ArrayList<Pair<SetupViewModel.PendingDeleteOutcome, PendingDelete?>>(),
        )
        val jobA = vm.launchPendingDeleteReconcile { outcome, j -> seenA.add(outcome to j) }
        assertTrue(enteredA.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(vm.startupReconcilePending.value)
        val jobB = vm.retryStartupReconcile { outcome, j -> seenB.add(outcome to j) }
        assertTrue(enteredB.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(vm.startupReconcilePending.value)
        // 放行舊世代 A：fan-out 自家 callback，但 fence 不得釋放。
        releaseA.countDown()
        runBlocking { withTimeout(10_000) { jobA.join() } }
        assertEquals(1, seenA.size)
        assertEquals(0, seenB.size)
        assertTrue("old generation finally must not release the new fence", vm.startupReconcilePending.value)
        // 放行新世代 B：落定後 fence 才釋放。
        releaseB.countDown()
        runBlocking {
            withTimeout(10_000) { jobB.join() }
        }
        assertEquals(1, seenA.size)
        assertEquals(1, seenB.size)
        assertEquals(SetupViewModel.PendingDeleteOutcome.NO_OP, seenB.single().first)
        assertFalse(vm.startupReconcilePending.value)
        assertEquals(0, vault.putKeys)
        assertEquals(0, vault.deleteKeys)
    }
}
