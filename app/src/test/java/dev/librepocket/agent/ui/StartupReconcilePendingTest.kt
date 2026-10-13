package dev.librepocket.agent.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.agent.ui.setup.GatedDeleteVault
import dev.librepocket.agent.ui.setup.MutablePolicy
import dev.librepocket.agent.ui.setup.PendingDelete
import dev.librepocket.agent.ui.setup.SetupViewModel
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
 * Stage B（R10 Finding 2 P2/UX）：啟動 replay 落定閘。
 *
 * 背景：replay 慢時 gate 可能已折疊 `NoEndpoint` 而落碼尚未發生；舊接線以
 * `gateLoading == false` 決策會先跳 Setup、失敗後又不導回 Settings。本檔鎖定：
 * - [SetupViewModel.startupReconcilePending] 初值 `true`，`onDone`（全 outcome，
 *   含無 journal 的 `NO_OP`）與取消落定後為 `false`；
 * - 落定前視同 loading（[startupEffectiveLoading]）：兩守衛不決策；
 * - 落定後完整資訊決策：失敗碼直達 SETTINGS（無 SETUP 中間態）、fresh 走 SETUP；
 * - STALE dismiss 只產生一次 SETTINGS→SETUP，此後無往返；
 * - 恢復態 drawer 手勢 + 選單鍵 enabled 表示式鎖定（`hasEndpoint || code != null`）。
 *
 * 守衛本身簽名與語義不變（真值表見 `NavGuardTest`／`StartupRecoveryNavTest`，
 * 本檔只測新閘與其組合）；`reconcileOutcomeToLogout` 映射不動。
 */

/**
 * 先懸住、放行後拋錯的 delete vault：建模「慢 replay 且 delete 失敗」
 *（`DELETE_FAILED`：journal 保留、key 仍在、未嘗試 clear）。
 */
private class HangingThenThrowingDeleteVault(
    private val delegate: KeyVault = EncryptedPrefsVault(InMemoryPrefs()),
    val deleteEntered: CompletableDeferred<Unit> = CompletableDeferred(),
    val deleteRelease: CompletableDeferred<Unit> = CompletableDeferred(),
) : KeyVault {
    override suspend fun putKey(providerId: String, apiKey: CharArray) = delegate.putKey(providerId, apiKey)
    override suspend fun getKey(providerId: String): CharArray? = delegate.getKey(providerId)
    override suspend fun deleteKey(providerId: String) {
        deleteEntered.complete(Unit)
        deleteRelease.await()
        throw IOException("DELETE_FAILED")
    }
    override suspend fun hasKey(providerId: String): Boolean = delegate.hasKey(providerId)
}

@OptIn(ExperimentalCoroutinesApi::class)
class StartupReconcilePendingTest {

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
            produceFile = { java.io.File(dir, "reconcile-pending.preferences_pb") },
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

    /** drawer/選單門禁與恢復卡可達改由生產接線測鎖定（見 `StartupGateWiringTest`，直測
     *  `StartupGateHost`）；此檔不重抄 `hasEndpoint || code != null` 表示式。 */
    @Test
    fun pendingStartsTrue() {
        val vm = newVm(rawStore("pending-starts-true"), EncryptedPrefsVault(InMemoryPrefs()))
        assertTrue(vm.startupReconcilePending.value)
    }

    @Test
    fun startupEffectiveLoadingTruthTable() {
        assertFalse(startupEffectiveLoading(gateLoading = false, startupReconcilePending = false))
        assertTrue(startupEffectiveLoading(gateLoading = true, startupReconcilePending = false))
        assertTrue(startupEffectiveLoading(gateLoading = false, startupReconcilePending = true))
        assertTrue(startupEffectiveLoading(gateLoading = true, startupReconcilePending = true))
    }

    @Test
    fun noJournalLaunchSettlesPendingSilentlyAndFreshGoesToSetup() {
        // fastReplay 無 journal 對照 + noJournalFreshStillGoesToSetup：NO_OP 落定、
        // 不落碼、fresh（無碼 + NoEndpoint）走 SETUP 分支。
        val vm = newVm(rawStore("pending-noop"), EncryptedPrefsVault(InMemoryPrefs()))
        assertTrue(vm.startupReconcilePending.value)
        var seen: SetupViewModel.PendingDeleteOutcome? = null
        val job = vm.launchPendingDeleteReconcile { outcome, journal ->
            seen = outcome
            vm.onStartupReconcile(outcome, journal)
        }
        runBlocking { withTimeout(10_000) { job.join() } }
        assertEquals(SetupViewModel.PendingDeleteOutcome.NO_OP, seen)
        assertFalse(vm.startupReconcilePending.value)
        assertNull(vm.form.value.errorCode)
        // 落定後完整資訊決策：fresh 從 CHAT/SETTINGS 走 SETUP，絕不走 SETTINGS。
        assertTrue(shouldNavigateToSetup(false, false, Routes.CHAT, null, false))
        assertTrue(shouldNavigateToSetup(false, false, Routes.SETTINGS, null, false))
        assertFalse(shouldNavigateToSettings(false, false, Routes.CHAT, null, false))
    }

    @Test
    fun slowReplayNeverParksInSetupThenFailsDirectToSettings() {
        // slowReplayNeverParksInSetup：journal preseed + delete 懸住 → gate 已
        // NoEndpoint + route CHAT，但落定閘視同 loading → 未 navigate(SETUP)；
        // 放行 DELETE_FAILED → 終態 SETTINGS + 恢復卡（重試清除）條件成立。
        val store = rawStore("pending-slow-replay")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
            assertTrue(store.writePendingDeleteIfAbsent(PendingDelete("preset:openai", 1L, 1700000000000L)))
        }
        val vault = HangingThenThrowingDeleteVault(delegate = delegate)
        val vm = newVm(store, vault)
        var seen: SetupViewModel.PendingDeleteOutcome? = null
        // MainScreen onDone 等價：落碼 + 落定（pending 由 launch 的 finally 復位）。
        val job = vm.launchPendingDeleteReconcile { outcome, journal ->
            seen = outcome
            vm.onStartupReconcile(outcome, journal)
        }
        runBlocking { withTimeout(10_000) { vault.deleteEntered.await() } }
        // 懸住期間：pending 仍 true；gate 早已折疊 NoEndpoint（journal 保留），
        // 但有效 loading 阻塞兩守衛 → 無 SETUP 導航、仍 spinner（不暴露 CHAT 空態）。
        assertTrue(vm.startupReconcilePending.value)
        val navLoading = startupEffectiveLoading(gateLoading = false, startupReconcilePending = true)
        assertTrue(navLoading)
        assertFalse(
            "must not park in SETUP while the replay is unsettled",
            shouldNavigateToSetup(false, navLoading, Routes.CHAT, null, false),
        )
        assertFalse(
            "must not steer to SETTINGS before the outcome lands",
            shouldNavigateToSettings(false, navLoading, Routes.CHAT, null, false),
        )
        // 放行 → DELETE_FAILED 落碼 + 落定。
        vault.deleteRelease.complete(Unit)
        runBlocking { withTimeout(10_000) { job.join() } }
        assertEquals(SetupViewModel.PendingDeleteOutcome.DELETE_FAILED, seen)
        assertFalse(vm.startupReconcilePending.value)
        val code = vm.form.value.errorCode
        assertEquals("LOGOUT_DELETE_FAILED", code)
        // 終態：CHAT 直達 SETTINGS（fastReplayGoesDirectToSettings：落碼先於
        // gate 決策 → 無 SETUP 中間事件），恢復卡重試條件成立（drawer/選單門禁
        // 改由接線測鎖定）。
        val settledLoading = startupEffectiveLoading(false, vm.startupReconcilePending.value)
        assertFalse(settledLoading)
        assertFalse(shouldNavigateToSetup(false, settledLoading, Routes.CHAT, code, false))
        assertTrue(shouldNavigateToSettings(false, settledLoading, Routes.CHAT, code, false))
        runBlocking {
            assertEquals("preset:openai", store.pendingDelete()?.alias)
            assertTrue(delegate.hasKey("preset:openai"))
        }
    }

    @Test
    fun failedAndUnknownOutcomesSteerToSettings() {
        // slowReplay 參數化：FAILED/UNKNOWN 皆到 SETTINGS（STALE 另計）。
        // Stage D1：UNKNOWN 需攜 journal（clear-phase）才落 LOGOUT_UNKNOWN；
        // read-path UNKNOWN+null 改落 READ_RETRY（另由新驗收檔覆蓋，此處鎖定 retained 側）。
        val vm = newVm(rawStore("pending-failed-unknown"), EncryptedPrefsVault(InMemoryPrefs()))
        val retainedJournal = PendingDelete("preset:openai", 1L, 1700000000000L)
        for ((outcome, journal, code) in listOf(
            Triple(SetupViewModel.PendingDeleteOutcome.FAILED, null, "LOGOUT_CLEAR_FAILED"),
            Triple(SetupViewModel.PendingDeleteOutcome.UNKNOWN, retainedJournal, "LOGOUT_UNKNOWN"),
            Triple(SetupViewModel.PendingDeleteOutcome.DELETE_FAILED, null, "LOGOUT_DELETE_FAILED"),
        )) {
            vm.onStartupReconcile(outcome, journal, gateReady = false)
            assertEquals(code, vm.form.value.errorCode)
            assertFalse(shouldNavigateToSetup(false, false, Routes.CHAT, code, false))
            assertTrue("outcome=$outcome", shouldNavigateToSettings(false, false, Routes.CHAT, code, false))
            vm.clearLogoutError()
        }
    }

    @Test
    fun staleNeedsDismissCardOrReadySilence() {
        // STALE → gate 未 Ready 時落碼待 dismiss（CHAT 導向 SETTINGS 關閉卡）；
        // gate 已 Ready（贏家活著）時靜默（有端點 → 兩守衛皆不決策）。
        val vm = newVm(rawStore("pending-stale"), EncryptedPrefsVault(InMemoryPrefs()))
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.STALE, null, gateReady = false)
        assertEquals("LOGOUT_STALE", vm.form.value.errorCode)
        assertFalse(shouldNavigateToSetup(false, false, Routes.CHAT, "LOGOUT_STALE", false))
        assertTrue(shouldNavigateToSettings(false, false, Routes.CHAT, "LOGOUT_STALE", false))
        vm.clearLogoutError()
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.STALE, null, gateReady = true)
        assertNull(vm.form.value.errorCode)
        assertFalse(shouldNavigateToSetup(true, false, Routes.CHAT, null, false))
        assertFalse(shouldNavigateToSettings(true, false, Routes.CHAT, null, false))
    }

    @Test
    fun dismissStaleReturnsToSetupOnceWithoutPingPong() {
        // dismissReturnsToSetupWithoutPingPong：SETTINGS 恢復卡關閉（STALE）→
        // 一次 SETTINGS→SETUP，此後無往返。
        val store = rawStore("pending-dismiss-pingpong")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
        }
        val vm = newVm(store, EncryptedPrefsVault(InMemoryPrefs()))
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.STALE, null, gateReady = false)
        assertEquals("LOGOUT_STALE", vm.form.value.errorCode)
        // CHAT 收到一次 SETTINGS 導向（關閉卡可達）。
        assertTrue(shouldNavigateToSettings(false, false, Routes.CHAT, "LOGOUT_STALE", false))
        // 已在 SETTINGS：兩守衛皆不重導（停留待 dismiss）。
        var code = vm.form.value.errorCode
        assertFalse(shouldNavigateToSettings(false, false, Routes.SETTINGS, code, false))
        assertFalse(shouldNavigateToSetup(false, false, Routes.SETTINGS, code, false))
        // 關閉（僅 STALE 可達）：journal 本就已丟，dismiss 安全。
        vm.clearLogoutError()
        code = vm.form.value.errorCode
        assertNull(code)
        runBlocking { assertNull(store.pendingDelete()) }
        // 一次 SETTINGS→SETUP。
        assertTrue(shouldNavigateToSetup(false, false, Routes.SETTINGS, code, false))
        // 抵達 SETUP 後穩定：已在 SETUP 不重導，無往返。
        assertFalse(shouldNavigateToSetup(false, false, Routes.SETUP, code, false))
        assertFalse(shouldNavigateToSettings(false, false, Routes.SETUP, code, false))
    }

    @Test
    fun cancelJoinHandleKeepsWorkerAliveUntilSettle() {
        // Stage C C1 join-handle 語義：回傳值是 `worker.join()` 等待柄，
        // cancel 只停止等待、不殺 worker——fence 保持到 worker 落定；放行後
        // onDone 照常發生、pending 落定。
        val store = rawStore("pending-cancel")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
            assertTrue(store.writePendingDeleteIfAbsent(PendingDelete("preset:openai", 1L, 1700000000000L)))
        }
        val vault = GatedDeleteVault(delegate = delegate, gateDelete = true)
        val vm = newVm(store, vault)
        var onDoneCalls = 0
        var seen: SetupViewModel.PendingDeleteOutcome? = null
        val job = vm.launchPendingDeleteReconcile { outcome, journal ->
            onDoneCalls++
            seen = outcome
            vm.onStartupReconcile(outcome, journal)
        }
        runBlocking { withTimeout(10_000) { vault.deleteEntered.await() } }
        assertTrue(vm.startupReconcilePending.value)
        job.cancel()
        runBlocking {
            try {
                withTimeout(10_000) { job.join() }
            } catch (_: CancellationException) {
                // Join handle cancel: expected, worker unaffected.
            }
        }
        // Worker 存活：fence 仍 armed、vault 仍懸住。
        assertTrue("cancel must not kill the shared worker", vm.startupReconcilePending.value)
        assertEquals(0, onDoneCalls)
        vault.gateDelete = false
        vault.deleteRelease.complete(Unit)
        // 放行後 worker 落定（此 handle 已取消不再等待；另起 join 確認落定）。
        val settleEnd = System.currentTimeMillis() + 10_000
        while (vm.startupReconcilePending.value) {
            if (System.currentTimeMillis() > settleEnd) error("timed out waiting for worker settle")
            Thread.sleep(10)
        }
        assertFalse(vm.startupReconcilePending.value)
        runBlocking { assertNull(store.pendingDelete()) }
    }
}
