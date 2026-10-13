package dev.librepocket.agent.ui.setup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dev.librepocket.agent.ui.Routes
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.agent.ui.effectiveLogoutGuardError
import dev.librepocket.agent.ui.effectiveLogoutRecovery
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

private class StageGFailReads(
    private val delegate: DataStore<Preferences>,
    private var failReads: Int = 1,
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

private class StageGFailUpdates(
    private val delegate: DataStore<Preferences>,
    private val error: IOException = IOException("UPDATE_TRANSPORT_FAILED"),
) : DataStore<Preferences> {
    override val data: Flow<Preferences> get() = delegate.data
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        throw error
    }
}

/**
 * Stage G（R15 P2x2）驗收：F1 派生攜身份＋F2 NO_OP 清 tag-null。
 *
 * F1：新派生 `effectiveLogoutRecovery` 回 `(effectiveCode, retryJournal)`——同源沿用、
 * 異源 `J_C` 攜 `("LOGOUT_UNKNOWN", J_C)`（碼固定中性）、Absent 抑制、未確認保留、
 * STALE 恆顯、在途抑制；`MainScreen.onRetryLogout` 讀顯示快照的 `retryJournal`
 * （漂移仍重試顯示身份，終端快照比對即 `StaleGone`，禁盲 replay）。
 *
 * F2／R5：`onStartupReconcile` NO_OP 收斂無身份（tag==null）的非 STALE `LOGOUT_*`
 * （READ_FAILED／UNKNOWN／READ_RETRY／CLEAR_FAILED-null／DELETE_FAILED-null 皆清，回 fresh
 * 重確認態，零 vault／metadata 寫入）；STALE 無論 tag 皆不清；帶 tag 碼永不清；
 * COMMITTED 分支不同步擴大（保 C2 同源不變式）；清除純 state，真 pending 由
 * observation 獨立重現，清除後回 fresh 確認態。
 *
 * 不碰映射大體／取消／probe／timeout；PocketGuard WARN 不動。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupStageGRecoveryTest {
    private val tmpDirs = ArrayList<java.io.File>()
    private val scopes = ArrayList<CoroutineScope>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
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
            produceFile = { java.io.File(dir, "stageg.preferences_pb") },
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

    private fun awaitRetry(
        vm: SetupViewModel,
        expected: PendingDelete,
        timeoutMs: Long = 10_000,
    ): SetupViewModel.RetryResult {
        val done = CompletableDeferred<SetupViewModel.RetryResult>()
        vm.retryPendingDelete(expected) { done.complete(it) }
        return runBlocking { withTimeout(timeoutMs) { done.await() } }
    }

    private fun awaitSaveSettled(vm: SetupViewModel, timeoutMs: Long = 8000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (vm.form.value.saving) {
            if (System.currentTimeMillis() > end) error("timed out waiting for save")
            Thread.sleep(10)
        }
    }

    // ---------- F1 真值表 ----------

    @Test
    fun f1_truthTable_carriesIdentity() {
        val jA = PendingDelete("preset:openai", 1L, 1700000000000L)
        val jC = PendingDelete("preset:openai#staged-" + "c".repeat(32), 3L, 1800000000000L)
        val tagA = jA.format()
        val knownA = PendingTeardownState.KnownPending(jA)
        val knownC = PendingTeardownState.KnownPending(jC)
        // 無碼沿用今日（tag 忽略）。
        run {
            val r = effectiveLogoutRecovery(null, null, knownA, false)
            assertEquals("LOGOUT_UNKNOWN", r.effectiveCode)
            assertEquals(jA, r.retryJournal)
        }
        run {
            val r = effectiveLogoutRecovery(null, tagA, knownA, false)
            assertEquals("LOGOUT_UNKNOWN", r.effectiveCode)
            assertEquals(jA, r.retryJournal)
        }
        run {
            val r = effectiveLogoutRecovery(null, null, PendingTeardownState.UnknownReadFailure, false)
            assertEquals(SetupViewModel.LOGOUT_READ_RETRY, r.effectiveCode)
            assertNull(r.retryJournal)
        }
        run {
            val r = effectiveLogoutRecovery(null, null, PendingTeardownState.MalformedPending, false)
            assertEquals(SetupViewModel.LOGOUT_READ_RETRY, r.effectiveCode)
            assertNull(r.retryJournal)
        }
        run {
            val r = effectiveLogoutRecovery(null, tagA, PendingTeardownState.Absent, false)
            assertNull(r.effectiveCode)
            assertNull(r.retryJournal)
        }
        // 在途恆 (null, null)（無碼／有碼／STALE 皆抑制）。
        run {
            val r = effectiveLogoutRecovery(null, tagA, knownA, true)
            assertNull(r.effectiveCode)
            assertNull(r.retryJournal)
        }
        run {
            val r = effectiveLogoutRecovery("LOGOUT_DELETE_FAILED", tagA, knownA, true)
            assertNull(r.effectiveCode)
            assertNull(r.retryJournal)
        }
        run {
            val r = effectiveLogoutRecovery("LOGOUT_STALE", tagA, knownA, true)
            assertNull(r.effectiveCode)
            assertNull(r.retryJournal)
        }
        // 有碼＋tag null 沿用 (code, null)。
        run {
            val r = effectiveLogoutRecovery("LOGOUT_DELETE_FAILED", null, knownC, false)
            assertEquals("LOGOUT_DELETE_FAILED", r.effectiveCode)
            assertNull(r.retryJournal)
        }
        run {
            val r = effectiveLogoutRecovery(SetupViewModel.LOGOUT_READ_RETRY, null, PendingTeardownState.Absent, false)
            assertEquals(SetupViewModel.LOGOUT_READ_RETRY, r.effectiveCode)
            assertNull(r.retryJournal)
        }
        // STALE 恆顯 (STALE, null)（Absent／J_C／Unknown 皆含）。
        for (state in listOf(PendingTeardownState.Absent, knownA, knownC, PendingTeardownState.UnknownReadFailure, PendingTeardownState.MalformedPending)) {
            val r = effectiveLogoutRecovery("LOGOUT_STALE", tagA, state, false)
            assertEquals("STALE=$state", "LOGOUT_STALE", r.effectiveCode)
            assertNull("STALE=$state", r.retryJournal)
        }
        // READ_RETRY＋tag 仍沿用（同 tag-null 行，不轉 UNKNOWN）。
        run {
            val r = effectiveLogoutRecovery(SetupViewModel.LOGOUT_READ_RETRY, tagA, knownC, false)
            assertEquals(SetupViewModel.LOGOUT_READ_RETRY, r.effectiveCode)
            assertNull(r.retryJournal)
        }
        // 同源沿用 (code, J)。
        run {
            val r = effectiveLogoutRecovery("LOGOUT_DELETE_FAILED", tagA, knownA, false)
            assertEquals("LOGOUT_DELETE_FAILED", r.effectiveCode)
            assertEquals(jA, r.retryJournal)
        }
        run {
            val r = effectiveLogoutRecovery("LOGOUT_CLEAR_FAILED", tagA, knownA, false)
            assertEquals("LOGOUT_CLEAR_FAILED", r.effectiveCode)
            assertEquals(jA, r.retryJournal)
        }
        run {
            val r = effectiveLogoutRecovery("LOGOUT_UNKNOWN", tagA, knownA, false)
            assertEquals("LOGOUT_UNKNOWN", r.effectiveCode)
            assertEquals(jA, r.retryJournal)
        }
        // 異源 J_C≠tag → (LOGOUT_UNKNOWN, J_C)（碼固定中性，不沿用舊 DELETE_FAILED）。
        run {
            val r = effectiveLogoutRecovery("LOGOUT_DELETE_FAILED", tagA, knownC, false)
            assertEquals("LOGOUT_UNKNOWN", r.effectiveCode)
            assertEquals(jC, r.retryJournal)
            // 舊 overload 真值表不動（仍 null），新舊差異鎖定。
            assertNull(effectiveLogoutGuardError("LOGOUT_DELETE_FAILED", tagA, knownC, false))
            // 新有效碼走 SETTINGS（無 fresh 回退）；舊 null 走 SETUP（fresh 回退，修前行為）。
            assertEquals(false, shouldNavigateToSetup(false, false, Routes.CHAT, r.effectiveCode, false))
            assertEquals(true, shouldNavigateToSettings(false, false, Routes.CHAT, r.effectiveCode, false))
        }
        // Absent＋tag → (null, null) 抑制。
        run {
            val r = effectiveLogoutRecovery("LOGOUT_DELETE_FAILED", tagA, PendingTeardownState.Absent, false)
            assertNull(r.effectiveCode)
            assertNull(r.retryJournal)
        }
        // Unknown／Malformed＋tag 保留舊碼 (code, null)。
        run {
            val r = effectiveLogoutRecovery("LOGOUT_DELETE_FAILED", tagA, PendingTeardownState.UnknownReadFailure, false)
            assertEquals("LOGOUT_DELETE_FAILED", r.effectiveCode)
            assertNull(r.retryJournal)
        }
        run {
            val r = effectiveLogoutRecovery("LOGOUT_DELETE_FAILED", tagA, PendingTeardownState.MalformedPending, false)
            assertEquals("LOGOUT_DELETE_FAILED", r.effectiveCode)
            assertNull(r.retryJournal)
        }
    }

    // ---------- F1 2-VM：J_A→B→J_C→A ----------

    @Test
    fun f1_twoVm_jAtoBtoJCtoA_retriesJCWithoutFreshFallback() {
        val store = rawStore("g-2vm")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
            assertEquals(1L, store.currentRevision())
        }
        // A 建 J_A（throwing vault，DELETE_FAILED＋tagA）。
        val vmABuild = newVm(store, ThrowingDeleteVault(delegate))
        val doneA = CompletableDeferred<Boolean>()
        vmABuild.logout { doneA.complete(it) }
        assertFalse(runBlocking { withTimeout(10_000) { doneA.await() } })
        assertEquals("LOGOUT_DELETE_FAILED", vmABuild.form.value.errorCode)
        val jA = runBlocking { store.pendingDelete() } ?: error("A must leave J_A")
        val tagA = jA.format()
        assertEquals(tagA, vmABuild.form.value.logoutErrorSourceJournal)
        // B 健康重試 J_A 至 COMMITTED（rev 1→2，舊 key 刪）。
        val vaultB = CountingVault(delegate)
        val vmB = newVm(store, vaultB)
        val replayB = awaitRetry(vmB, jA)
        assertTrue(replayB is SetupViewModel.RetryResult.Replayed)
        assertEquals(SetupViewModel.PendingDeleteOutcome.COMMITTED, (replayB as SetupViewModel.RetryResult.Replayed).outcome)
        runBlocking {
            assertNull(store.pendingDelete())
            assertEquals(2L, store.currentRevision())
            assertFalse(delegate.hasKey("preset:openai"))
        }
        // B save 新 winner（rev 2→3，READY）。
        vmB.onApiKeyChange("unit-test-winner-key-BBBBBBBB")
        vmB.save()
        awaitSaveSettled(vmB)
        assertTrue(vmB.form.value.saved)
        val winnerRef = runBlocking { store.observe().first()!!.apiKeyRef }
        assertTrue(winnerRef.startsWith("provider_key/preset:openai#staged-"))
        val winnerAlias = SetupValidation.aliasFromRef(winnerRef)
        runBlocking {
            assertEquals(3L, store.currentRevision())
            assertTrue(delegate.hasKey(winnerAlias))
        }
        // B 新 teardown 留 J_C（throwing vault，alias＝winner）。
        val vmB2 = newVm(store, ThrowingDeleteVault(delegate))
        val doneB2 = CompletableDeferred<Boolean>()
        vmB2.logout { doneB2.complete(it) }
        assertFalse(runBlocking { withTimeout(10_000) { doneB2.await() } })
        assertEquals("LOGOUT_DELETE_FAILED", vmB2.form.value.errorCode)
        val jC = runBlocking { store.pendingDelete() } ?: error("B must leave J_C")
        assertEquals(winnerAlias, jC.alias)
        assertEquals(3L, jC.revision)
        // A（舊卡 tagA）見 NoEndpoint＋J_C 恢復：派生 (UNKNOWN, J_C)，無 fresh 回退。
        val vmA = newVm(store, CountingVault(delegate))
        vmA.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.DELETE_FAILED, jA, gateReady = false)
        assertEquals("LOGOUT_DELETE_FAILED", vmA.form.value.errorCode)
        assertEquals(tagA, vmA.form.value.logoutErrorSourceJournal)
        val knownC = runBlocking {
            withTimeout(10_000) { vmA.pendingTeardown.first { it is PendingTeardownState.KnownPending } }
        } as PendingTeardownState.KnownPending
        assertEquals(jC, knownC.pending)
        runBlocking { assertNull(store.observe().first()) }
        val recovered = effectiveLogoutRecovery(
            "LOGOUT_DELETE_FAILED",
            tagA,
            knownC,
            logoutInFlight = false,
        )
        assertEquals("LOGOUT_UNKNOWN", recovered.effectiveCode)
        assertEquals(jC, recovered.retryJournal)
        // 舊 String? 仍 fresh 回退（null→SETUP），新有效碼走 SETTINGS（修後行為）。
        assertNull(effectiveLogoutGuardError("LOGOUT_DELETE_FAILED", tagA, knownC, false))
        assertEquals(false, shouldNavigateToSetup(false, false, Routes.CHAT, recovered.effectiveCode, false))
        assertEquals(true, shouldNavigateToSettings(false, false, Routes.CHAT, recovered.effectiveCode, false))
        // A 以顯示身份 J_C 重試至 COMMITTED（B 僅 J_C 恢復後刪，無 fresh fallback）。
        val vaultARetry = CountingVault(delegate)
        val vmARetry = newVm(store, vaultARetry)
        vmARetry.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.DELETE_FAILED, jA, gateReady = false)
        val result = awaitRetry(vmARetry, jC)
        assertTrue("retry J_C must replay, was $result", result is SetupViewModel.RetryResult.Replayed)
        assertEquals(SetupViewModel.PendingDeleteOutcome.COMMITTED, (result as SetupViewModel.RetryResult.Replayed).outcome)
        assertEquals(jC, result.journal)
        runBlocking {
            assertNull(store.pendingDelete())
            assertEquals(4L, store.currentRevision())
            assertFalse(store.hasMetadata())
            assertFalse(delegate.hasKey(winnerAlias))
        }
        assertNull(vmARetry.form.value.errorCode)
        assertNull(vmARetry.form.value.logoutErrorSourceJournal)
        // B 僅刪 J_C（winner），未刪舊 alias（已於 J_A 輪刪除），無全新 logout（禁 fresh 回退：
        // 重放攜同一 J_C 身份——含 timestamp——新鮮 logout 會另起新 timestamp，不可能相等）。
        assertEquals(listOf(winnerAlias), vaultARetry.deletedIds)
    }

    // ---------- F1 競態 StaleGone：顯示 J_C 後漂移重試 J_C ----------

    @Test
    fun f1_race_staleGone_displayJCThenDriftRetryJC() {
        val store = rawStore("g-race")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val winnerAlias = "preset:openai#staged-" + "c".repeat(32)
        val jC = PendingDelete(winnerAlias, 3L, 1800000000000L)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            delegate.putKey(winnerAlias, "unit-test-winner-key-CCCCCCCC".toCharArray())
            store.save(preseed())
            // 世代推進至 rev 3（與 StaleRetry 取代序列同式，J_A 已去、winner 存、J_C 留）。
            assertTrue(store.clearIfRevision(1L))
            store.save(preseed(model = "m-new2").copy(apiKeyRef = "provider_key/$winnerAlias"))
            assertEquals(3L, store.currentRevision())
            assertTrue(store.writePendingDeleteIfAbsent(jC))
        }
        // 顯示：舊卡 tagA＋J_C 派生 (UNKNOWN, J_C)（此處以 tagA＝J_A 模擬舊卡）。
        val jA = PendingDelete("preset:openai", 1L, 1700000000000L)
        val displayed = effectiveLogoutRecovery(
            "LOGOUT_DELETE_FAILED",
            jA.format(),
            PendingTeardownState.KnownPending(jC),
            logoutInFlight = false,
        )
        assertEquals("LOGOUT_UNKNOWN", displayed.effectiveCode)
        assertEquals(jC, displayed.retryJournal)
        // 漂移：B 健康重試 J_C 至 COMMITTED（J_C 去、winner 刪、rev 3→4、無 meta）。
        val vmB = newVm(store, CountingVault(delegate))
        val drifted = awaitRetry(vmB, jC)
        assertTrue(drifted is SetupViewModel.RetryResult.Replayed)
        assertEquals(SetupViewModel.PendingDeleteOutcome.COMMITTED, (drifted as SetupViewModel.RetryResult.Replayed).outcome)
        runBlocking {
            assertNull(store.pendingDelete())
            assertEquals(4L, store.currentRevision())
            assertFalse(delegate.hasKey(winnerAlias))
        }
        // 重試顯示的 J_C（已漂移）：StaleGone、零新增 delete、J_C 不復活；
        // 舊卡靠 Absent 派生抑制隱藏（config-null 跨源不清卡，見 clearStaleCardSameOrigin）。
        val vaultA = CountingVault(delegate)
        val vmA = newVm(store, vaultA)
        vmA.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.DELETE_FAILED, jA, gateReady = false)
        val deletesBefore = vaultA.deleteKeys
        // 注意：此處用顯示快照的 J_C（非重算新態），提交仍快照比對，禁盲 replay。
        val result = awaitRetry(vmA, displayed.retryJournal ?: error("displayed must carry J_C"))
        assertEquals(SetupViewModel.RetryResult.StaleGone, result)
        assertEquals(deletesBefore, vaultA.deleteKeys)
        runBlocking {
            assertNull("drifted J_C must not resurrect", store.pendingDelete())
            assertEquals(4L, store.currentRevision())
        }
        assertFalse(vmA.form.value.logoutInFlight)
    }

    // ---------- F2：snapshot fail-once→READ_FAILED→NO_OP 清 ----------

    @Test
    fun f2_noOpClearsTagNullReadFailed_backToFresh() {
        val raw = rawStore("g-f2-readfailed")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            raw.save(preseed())
        }
        val failingOnce = StageGFailReads(raw.dataStore, failReads = 1)
        val store = EndpointStore(failingOnce)
        val vault = CountingVault(delegate)
        val vm = newVm(store, vault)
        // snapshot fail-once logout → READ_FAILED-null（零副作用）。
        val done = CompletableDeferred<Boolean>()
        vm.logout { done.complete(it) }
        assertEquals(false, runBlocking { withTimeout(10_000) { done.await() } })
        assertEquals("LOGOUT_READ_FAILED", vm.form.value.errorCode)
        assertNull(vm.form.value.logoutErrorSourceJournal)
        assertEquals(0, vault.deleteKeys)
        runBlocking {
            assertTrue(delegate.hasKey("preset:openai"))
            assertEquals(1L, store.currentRevision())
        }
        // 重試 reconcile（第二次讀成功、真無 journal）→ NO_OP → 橫幅消失、回 fresh。
        val (outcome, journal) = runBlocking { vm.reconcilePendingDeleteWithJournal() }
        assertEquals(SetupViewModel.PendingDeleteOutcome.NO_OP, outcome)
        assertNull(journal)
        vm.onStartupReconcile(outcome, journal, gateReady = false)
        assertNull("F2: NO_OP must clear tag-null READ_FAILED", vm.form.value.errorCode)
        assertNull(vm.form.value.logoutErrorSourceJournal)
        assertFalse(vm.form.value.logoutInFlight)
        // vault 不動、key/meta/rev 完整、後續 fresh 確認態（確定清除＋取消可達，save 不被擋）。
        runBlocking {
            assertTrue(delegate.hasKey("preset:openai"))
            assertNull(store.pendingDelete())
            assertEquals(1L, store.currentRevision())
            val observed = store.observe().first()
            assertTrue(observed != null)
            assertEquals("provider_key/preset:openai", observed!!.apiKeyRef)
        }
        assertEquals(0, vault.deleteKeys)
        assertEquals(0, failingOnce.updateCalls)
        // fresh 確認態：save 成功（不被 teardown 擋）。
        vm.onApiKeyChange("unit-test-fresh-key-cccccccc")
        vm.save()
        awaitSaveSettled(vm)
        assertTrue("fresh save must succeed after F2 clear", vm.form.value.saved)
    }

    // ---------- F2：ack-unknown→absent 對稱 ----------

    @Test
    fun f2_noOpClearsTagNullUnknown_symmetricToReadFailed() {
        val raw = rawStore("g-f2-unknown")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            raw.save(preseed())
        }
        // journal 寫入傳輸全敗（讀直通、寫全拋）：logout 在 journal 寫入即 ack-loss，
        // provably absent → 未確認 UNKNOWN-null（禁 CLEAR_FAILED），零 delete。
        val failingUpdates = StageGFailUpdates(raw.dataStore)
        val store = EndpointStore(failingUpdates)
        val vault = CountingVault(delegate)
        val vm = newVm(store, vault)
        val done = CompletableDeferred<Boolean>()
        vm.logout { done.complete(it) }
        assertEquals(false, runBlocking { withTimeout(10_000) { done.await() } })
        assertEquals("LOGOUT_UNKNOWN", vm.form.value.errorCode)
        assertNull(vm.form.value.logoutErrorSourceJournal)
        assertEquals(0, vault.deleteKeys)
        // 同 store 無法重試 NO_OP（寫全敗會擋 clear？NO_OP 不寫，故可用同 store 重試？
        // NO_OP 僅讀，讀直通、真無 journal → NO_OP；但 failingUpdates 的讀直通，故可直接 NO_OP 清）。
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.NO_OP, null, gateReady = false)
        assertNull("F2: NO_OP must clear tag-null UNKNOWN", vm.form.value.errorCode)
        assertNull(vm.form.value.logoutErrorSourceJournal)
        runBlocking {
            assertTrue(delegate.hasKey("preset:openai"))
        }
        assertEquals(0, vault.deleteKeys)
        // COMMITTED-null 不清 UNKNOWN-null（對照；保 C2 同源不變式）。
        val raw2 = rawStore("g-f2-unknown-committed")
        val delegate2 = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate2.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            raw2.save(preseed())
        }
        val store2 = EndpointStore(StageGFailUpdates(raw2.dataStore))
        val vm2 = newVm(store2, CountingVault(delegate2))
        val done2 = CompletableDeferred<Boolean>()
        vm2.logout { done2.complete(it) }
        assertEquals(false, runBlocking { withTimeout(10_000) { done2.await() } })
        assertEquals("LOGOUT_UNKNOWN", vm2.form.value.errorCode)
        vm2.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.COMMITTED, null, gateReady = false)
        assertEquals("COMMITTED-null must never clear UNKNOWN-null", "LOGOUT_UNKNOWN", vm2.form.value.errorCode)
    }

    // ---------- F2：併發窗口（清除後新 J_new 派生重現） ----------

    @Test
    fun f2_concurrentWindow_clearedThenNewJournalRederives() {
        val store = rawStore("g-f2-window")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
        }
        val vm = newVm(store, CountingVault(delegate))
        // 先以 READ_FAILED-null 模擬未確認殘留，再 NO_OP 清至 fresh。
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.UNKNOWN, null, gateReady = false)
        // read-path UNKNOWN-null 落 READ_RETRY（非 READ_FAILED），此處以真實 logout 取得 READ_FAILED：
        // 為簡化，直接以 logout fail-once 取得（同 f2_noOpClearsTagNullReadFailed 前半）。
        // 此測試聚焦窗口：先清 READ_RETRY（D1），再寫 J_new，派生重現。
        assertEquals(SetupViewModel.LOGOUT_READ_RETRY, vm.form.value.errorCode)
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.NO_OP, null, gateReady = false)
        assertNull(vm.form.value.errorCode)
        // 清除後新 J_new（併發 teardown）寫入：observation 派生獨立重現，不依賴已清碼。
        val jNew = PendingDelete("preset:openai", 1L, 1900000000000L)
        runBlocking { assertTrue(store.writePendingDeleteIfAbsent(jNew)) }
        val observed = runBlocking {
            withTimeout(10_000) { vm.pendingTeardown.first { it is PendingTeardownState.KnownPending } }
        } as PendingTeardownState.KnownPending
        assertEquals(jNew, observed.pending)
        val rederived = effectiveLogoutRecovery(null, null, observed, logoutInFlight = false)
        assertEquals("LOGOUT_UNKNOWN", rederived.effectiveCode)
        assertEquals(jNew, rederived.retryJournal)
        // 新碼走 SETTINGS（恢復可達），非 fresh SETUP。
        assertEquals(false, shouldNavigateToSetup(false, false, Routes.CHAT, rederived.effectiveCode, false))
        assertEquals(true, shouldNavigateToSettings(false, false, Routes.CHAT, rederived.effectiveCode, false))
    }

    // ---------- F2 負向：STALE／帶 tag 碼 NO_OP 不清；2226／CLEAR-null 殘留 ----------

    @Test
    fun f2_negative_staleAndTaggedNeverCleared_nullTagConverges() {
        val vm = newVm(rawStore("g-f2-negative"), CountingVault())
        val jA = PendingDelete("preset:openai", 1L, 1700000000000L)
        val jC = PendingDelete("preset:openai#staged-" + "c".repeat(32), 3L, 1800000000000L)
        // STALE（無論 tag）永不清。
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.STALE, null, gateReady = false)
        assertEquals("LOGOUT_STALE", vm.form.value.errorCode)
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.NO_OP, null, gateReady = false)
        assertEquals("STALE-null must survive NO_OP", "LOGOUT_STALE", vm.form.value.errorCode)
        vm.clearLogoutError()
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.STALE, jC, gateReady = false)
        assertEquals("LOGOUT_STALE", vm.form.value.errorCode)
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.NO_OP, null, gateReady = false)
        assertEquals("STALE+tag must survive NO_OP", "LOGOUT_STALE", vm.form.value.errorCode)
        vm.clearLogoutError()
        // 帶 tag 碼永不清（DELETE／CLEAR／UNKNOWN+J）。
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.DELETE_FAILED, jA, gateReady = false)
        assertEquals("LOGOUT_DELETE_FAILED", vm.form.value.errorCode)
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.NO_OP, null, gateReady = false)
        assertEquals("LOGOUT_DELETE_FAILED", vm.form.value.errorCode)
        vm.clearLogoutError()
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.FAILED, jA, gateReady = false)
        assertEquals("LOGOUT_CLEAR_FAILED", vm.form.value.errorCode)
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.NO_OP, null, gateReady = false)
        assertEquals("LOGOUT_CLEAR_FAILED", vm.form.value.errorCode)
        vm.clearLogoutError()
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.UNKNOWN, jA, gateReady = false)
        assertEquals("LOGOUT_UNKNOWN", vm.form.value.errorCode)
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.NO_OP, null, gateReady = false)
        assertEquals("LOGOUT_UNKNOWN+tag must survive NO_OP", "LOGOUT_UNKNOWN", vm.form.value.errorCode)
        vm.clearLogoutError()
        // Stage R5：無身份 null-tag 碼經 NO_OP（證無 journal）收斂回 fresh，
        // 不再殘留。2226 dirty-alias DELETE_FAILED-null（PendingDelete 構造失敗，
        // 無 journal 可比對）：經 DataStore 直寫繞過 save 驗證（save 拒 `|`，
        // 模擬歷史髒數據），真實 logout 落 `DELETE_FAILED-null`，再給 NO_OP 即
        // 收斂（零 vault／metadata 寫入，不自動 fresh-delete 任何 key）。
        val raw2226 = rawStore("g-f2-2226")
        val delegate2226 = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate2226.putKey("bad|alias", "unit-test-old-key-123456".toCharArray())
            // 先健康 save，再直寫惡化 apiKeyRef（繞 save 驗證，模擬歷史髒數據）。
            raw2226.save(preseed())
            raw2226.dataStore.edit { prefs ->
                prefs[ENDPOINT_API_KEY_REF_V1] = "provider_key/bad|alias"
            }
        }
        val vault2226 = CountingVault(delegate2226)
        val vm2226 = newVm(raw2226, vault2226)
        val done2226 = CompletableDeferred<Boolean>()
        vm2226.logout { done2226.complete(it) }
        assertEquals(false, runBlocking { withTimeout(10_000) { done2226.await() } })
        assertEquals("LOGOUT_DELETE_FAILED", vm2226.form.value.errorCode)
        assertNull(vm2226.form.value.logoutErrorSourceJournal)
        val puts2226 = vault2226.putKeys
        val deletes2226 = vault2226.deleteKeys
        val rev2226 = runBlocking { raw2226.currentRevision() }
        vm2226.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.NO_OP, null, gateReady = false)
        assertNull(
            "2226 DELETE-null must converge to fresh on proven-Absent NO_OP",
            vm2226.form.value.errorCode,
        )
        assertNull(vm2226.form.value.logoutErrorSourceJournal)
        assertEquals(puts2226, vault2226.putKeys)
        assertEquals(deletes2226, vault2226.deleteKeys)
        assertEquals(rev2226, runBlocking { raw2226.currentRevision() })
        runBlocking { assertTrue(delegate2226.hasKey("bad|alias")) }
        // alias-null CLEAR_FAILED-null（無 alias 跳過 journal，直進 clear 失敗）：
        // 先健康 save，再直寫惡化 apiKeyRef（繞 save 驗證），疊加寫全敗 store 使 clear 失敗，
        // 真實 logout 落 `CLEAR_FAILED-null`，再給 NO_OP 即收斂（同上零寫入）。
        val rawAliasNull = rawStore("g-f2-aliasnull")
        val delegateAliasNull = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegateAliasNull.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            rawAliasNull.save(preseed())
            rawAliasNull.dataStore.edit { prefs ->
                prefs[ENDPOINT_API_KEY_REF_V1] = "bad-ref-without-prefix"
            }
        }
        val storeAliasNull = EndpointStore(StageGFailUpdates(rawAliasNull.dataStore))
        val vaultAliasNull = CountingVault(delegateAliasNull)
        val vmAliasNull = newVm(storeAliasNull, vaultAliasNull)
        val doneAliasNull = CompletableDeferred<Boolean>()
        vmAliasNull.logout { doneAliasNull.complete(it) }
        assertEquals(false, runBlocking { withTimeout(10_000) { doneAliasNull.await() } })
        assertEquals("LOGOUT_CLEAR_FAILED", vmAliasNull.form.value.errorCode)
        assertNull(vmAliasNull.form.value.logoutErrorSourceJournal)
        val putsAliasNull = vaultAliasNull.putKeys
        val deletesAliasNull = vaultAliasNull.deleteKeys
        val revAliasNull = runBlocking { rawAliasNull.currentRevision() }
        vmAliasNull.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.NO_OP, null, gateReady = false)
        assertNull(
            "alias-null CLEAR-null must converge to fresh on proven-Absent NO_OP",
            vmAliasNull.form.value.errorCode,
        )
        assertNull(vmAliasNull.form.value.logoutErrorSourceJournal)
        assertEquals(putsAliasNull, vaultAliasNull.putKeys)
        assertEquals(deletesAliasNull, vaultAliasNull.deleteKeys)
        assertEquals(revAliasNull, runBlocking { rawAliasNull.currentRevision() })
        runBlocking { assertTrue(delegateAliasNull.hasKey("preset:openai")) }
    }
}
