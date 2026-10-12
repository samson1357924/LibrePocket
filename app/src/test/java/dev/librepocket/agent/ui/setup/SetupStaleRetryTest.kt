package dev.librepocket.agent.ui.setup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.agent.ui.effectiveLogoutGuardError
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
import kotlinx.coroutines.delay
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
 * Stage F（R14 Finding P2/BLOCK）驗收：stale 恢復鍵回退全新 destructive logout。
 *
 * 新入口 `retryPendingDelete(expected)` 全程 `saveMutex` 內單快照判定；`logout()`
 * 收斂 fresh-only（全新確認專用），兩顆重試清除改綁此入口（`MainScreen`）。
 * - 主回歸：A 建 `J_A＋DELETE_FAILED` → B resume `COMMITTED` → B save 新 B →
 *   A 收斂 `Absent＋Ready(B)` → `A.retryPendingDelete(J_A)` → `StaleGone`、
 *   B key／meta／rev 不變、無 `J_B`、舊卡退場；
 * - 同源重放成功：`COMMITTED`、journal 空、`rev＋1`、同源清碼；
 * - `J_C` 取代後不碰 C：`StaleGone`、`J_C`／meta／rev 不變、無 `deleteKey(C)`；
 * - `ReadUnknown`：strict 失敗／毀損 → 零 I/O、僅重試讀取；
 * - `inFlight` 互斥（兩入口共用）。
 *
 * 不改 D1 中性碼／清除、映射大體、取消語義、generation probe；不做 timeout。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupStaleRetryTest {
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
            produceFile = { java.io.File(dir, "f-stale-retry.preferences_pb") },
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

    private fun awaitRetry(
        vm: SetupViewModel,
        expected: PendingDelete,
        timeoutMs: Long = 10_000,
    ): SetupViewModel.RetryResult {
        val done = CompletableDeferred<SetupViewModel.RetryResult>()
        vm.retryPendingDelete(expected) { done.complete(it) }
        return runBlocking { withTimeout(timeoutMs) { done.await() } }
    }

    @Test
    fun staleRetryAfterWinnerSave_doesNotDeleteNewKey() {
        // 主回歸：A 建 J_A＋DELETE_FAILED → B resume COMMITTED → B save 新 B →
        // A 收斂 Absent＋Ready(B) → A.retryPendingDelete(J_A) → StaleGone。
        val store = rawStore("f-stale-main")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
            assertEquals(1L, store.currentRevision())
        }
        // A：throwing vault logout 建 J_A（delete 敗、journal 留、DELETE_FAILED）。
        val vaultABuild = ThrowingDeleteVault(delegate)
        val vmABuild = newVm(store, vaultABuild)
        val doneABuild = CompletableDeferred<Boolean>()
        vmABuild.logout { doneABuild.complete(it) }
        assertFalse(runBlocking { withTimeout(10_000) { doneABuild.await() } })
        assertEquals("LOGOUT_DELETE_FAILED", vmABuild.form.value.errorCode)
        val journalA = runBlocking { store.pendingDelete() }
            ?: error("A must leave J_A")
        assertEquals("preset:openai", journalA.alias)
        assertEquals(1L, journalA.revision)
        val tagA = journalA.format()
        assertEquals(tagA, vmABuild.form.value.logoutErrorSourceJournal)
        // B：同 Store 健康 vault，經新入口 resume J_A 至 COMMITTED。
        val vaultB = CountingVault(delegate)
        val vmB = newVm(store, vaultB)
        val replayB = awaitRetry(vmB, journalA)
        assertTrue(replayB is SetupViewModel.RetryResult.Replayed)
        assertEquals(
            SetupViewModel.PendingDeleteOutcome.COMMITTED,
            (replayB as SetupViewModel.RetryResult.Replayed).outcome,
        )
        runBlocking {
            assertNull(store.pendingDelete())
            assertEquals(2L, store.currentRevision())
            assertFalse(delegate.hasKey("preset:openai"))
        }
        assertNull(vmB.form.value.errorCode)
        // B save 新 B（staged 提交，READY）。
        vmB.onApiKeyChange("unit-test-winner-key-BBBBBBBB")
        vmB.save()
        awaitSaveSettled(vmB)
        assertTrue(vmB.form.value.saved)
        val winnerRef = runBlocking { store.observe().first()!!.apiKeyRef }
        assertTrue(winnerRef.startsWith("provider_key/preset:openai#staged-"))
        val winnerAlias = SetupValidation.aliasFromRef(winnerRef)
        runBlocking {
            assertEquals(3L, store.currentRevision())
            assertEquals(EndpointReadiness.READY, store.readiness(delegate))
            assertTrue(delegate.hasKey(winnerAlias))
        }
        // A 側收斂：Absent＋Ready(B)（舊卡仍 DELETE_FAILED，待重試）。
        val vmA = newVm(store, CountingVault(delegate))
        vmA.onStartupReconcile(
            SetupViewModel.PendingDeleteOutcome.DELETE_FAILED,
            journalA,
            gateReady = true,
        )
        assertEquals("LOGOUT_DELETE_FAILED", vmA.form.value.errorCode)
        assertEquals(tagA, vmA.form.value.logoutErrorSourceJournal)
        runBlocking {
            withTimeout(10_000) { vmA.pendingTeardown.first { it is PendingTeardownState.Absent } }
            val gate = withTimeout(10_000) { vmA.gate.first { it is EndpointGate.Ready } }
            assertTrue(gate is EndpointGate.Ready)
        }
        // 派生抑制：舊 DELETE_FAILED(J_A)＋Absent → null（舊重試鍵隱藏，防回退）。
        assertNull(
            effectiveLogoutGuardError(
                "LOGOUT_DELETE_FAILED",
                tagA,
                PendingTeardownState.Absent,
                logoutInFlight = false,
            ),
        )
        // A stale 重試：StaleGone、B 不變、無 J_B、舊卡退場（STALE 待 dismiss）。
        val vaultARetry = CountingVault(delegate)
        val vmARetry = newVm(store, vaultARetry)
        // 先 stamp 舊卡（模擬 A 側殘留），再重試。
        vmARetry.onStartupReconcile(
            SetupViewModel.PendingDeleteOutcome.DELETE_FAILED,
            journalA,
            gateReady = true,
        )
        val deletesBefore = vaultARetry.deleteKeys
        val result = awaitRetry(vmARetry, journalA)
        assertEquals(SetupViewModel.RetryResult.StaleGone, result)
        assertEquals(deletesBefore, vaultARetry.deleteKeys)
        assertTrue(vaultARetry.deletedIds.none { it == winnerAlias })
        runBlocking {
            assertNull("no J_B may be created by a stale retry", store.pendingDelete())
            assertEquals(3L, store.currentRevision())
            assertEquals(winnerRef, store.observe().first()!!.apiKeyRef)
            assertTrue("winner key survives", delegate.hasKey(winnerAlias))
            assertEquals(EndpointReadiness.READY, store.readiness(delegate))
        }
        // 舊卡退場：DELETE_FAILED 被 STALE(expected tag) 取代（待 dismiss）。
        assertEquals("LOGOUT_STALE", vmARetry.form.value.errorCode)
        assertEquals(tagA, vmARetry.form.value.logoutErrorSourceJournal)
        assertFalse(vmARetry.form.value.logoutInFlight)
    }

    @Test
    fun sameJournalReplay_commitsAndClearsSameOrigin() {
        // 同 J_A 重放成功：COMMITTED、journal 空、rev＋1、同源清碼。
        val store = rawStore("f-same-replay")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val journalA = PendingDelete("preset:openai", 1L, 1700000000000L)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
            assertTrue(store.writePendingDeleteIfAbsent(journalA))
        }
        val vault = CountingVault(delegate)
        val vm = newVm(store, vault)
        // 先落 retained 碼（帶 tag），證明同源清碼。
        vm.onStartupReconcile(
            SetupViewModel.PendingDeleteOutcome.DELETE_FAILED,
            journalA,
            gateReady = false,
        )
        assertEquals("LOGOUT_DELETE_FAILED", vm.form.value.errorCode)
        assertEquals(journalA.format(), vm.form.value.logoutErrorSourceJournal)
        val result = awaitRetry(vm, journalA)
        assertTrue(result is SetupViewModel.RetryResult.Replayed)
        assertEquals(
            SetupViewModel.PendingDeleteOutcome.COMMITTED,
            (result as SetupViewModel.RetryResult.Replayed).outcome,
        )
        assertEquals(journalA, result.journal)
        runBlocking {
            assertNull(store.pendingDelete())
            assertEquals(2L, store.currentRevision())
            assertFalse(store.hasMetadata())
            assertFalse(delegate.hasKey("preset:openai"))
        }
        assertNull(vm.form.value.errorCode)
        assertNull(vm.form.value.logoutErrorSourceJournal)
        assertEquals(listOf("preset:openai"), vault.deletedIds)
    }

    @Test
    fun replacedByJC_doesNotTouchC() {
        // J_C 取代後不碰 C：StaleGone、J_C／meta／rev 不變、無 deleteKey(C)。
        val store = rawStore("f-jc-keep")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val journalA = PendingDelete("preset:openai", 1L, 1700000000000L)
        val winnerAlias = "preset:openai#staged-" + "c".repeat(32)
        val journalC = PendingDelete(winnerAlias, 3L, 1800000000000L)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            delegate.putKey(winnerAlias, "unit-test-winner-key-CCCCCCCC".toCharArray())
            store.save(preseed())
            assertTrue(store.writePendingDeleteIfAbsent(journalA))
            // J_C 取代序列（真實世代推進）：B 將 J_A 終結（clear 帶走 journal，
            // rev 1→2）→ B save 贏家（rev 2→3）→ B 新 teardown 寫 J_C（rev 3）。
            // 直接以 clear＋save＋journal 建構，rev 必進且 J_A 已去。
            assertTrue(store.clearIfRevision(1L))
            assertEquals(2L, store.currentRevision())
            store.save(preseed(model = "m-new2").copy(apiKeyRef = "provider_key/$winnerAlias"))
            assertEquals(3L, store.currentRevision())
            assertTrue(store.writePendingDeleteIfAbsent(journalC))
        }
        val vault = CountingVault(delegate)
        val vm = newVm(store, vault)
        vm.onStartupReconcile(
            SetupViewModel.PendingDeleteOutcome.DELETE_FAILED,
            journalA,
            gateReady = false,
        )
        assertEquals("LOGOUT_DELETE_FAILED", vm.form.value.errorCode)
        val deletesBefore = vault.deleteKeys
        val result = awaitRetry(vm, journalA)
        assertEquals(SetupViewModel.RetryResult.StaleGone, result)
        // 絕不碰 C：零新增 delete、J_C／meta／rev 原樣。
        assertEquals(deletesBefore, vault.deleteKeys)
        assertTrue(vault.deletedIds.none { it == winnerAlias })
        runBlocking {
            assertEquals(journalC, store.pendingDelete())
            assertEquals(3L, store.currentRevision())
            assertEquals("provider_key/$winnerAlias", store.snapshotStrict()!!.apiKeyRef)
            assertTrue(delegate.hasKey(winnerAlias))
        }
        // 舊卡退場：報 STALE(expected tag，防與 J_C COMMITTED 混淆)。
        assertEquals("LOGOUT_STALE", vm.form.value.errorCode)
        assertEquals(journalA.format(), vm.form.value.logoutErrorSourceJournal)
        // 派生：舊 STALE 恆顯（dismiss 可達，舊／新一致 `(STALE, null)`）；
        // Stage G F1 異源 J_C 由隱藏改攜帶：舊 `null`（pure 只隱藏，真清卡靠終端已報
        // STALE），新 `("LOGOUT_UNKNOWN", J_C)`（碼固定中性，不沿用舊 DELETE_FAILED）。
        assertEquals(
            "LOGOUT_STALE",
            effectiveLogoutGuardError(
                "LOGOUT_STALE",
                journalA.format(),
                PendingTeardownState.KnownPending(journalC),
                logoutInFlight = false,
            ),
        )
        run {
            val staleCarried = dev.librepocket.agent.ui.effectiveLogoutRecovery(
                "LOGOUT_STALE",
                journalA.format(),
                PendingTeardownState.KnownPending(journalC),
                logoutInFlight = false,
            )
            assertEquals("LOGOUT_STALE", staleCarried.effectiveCode)
            assertNull(staleCarried.retryJournal)
        }
        assertNull(
            effectiveLogoutGuardError(
                "LOGOUT_DELETE_FAILED",
                journalA.format(),
                PendingTeardownState.KnownPending(journalC),
                logoutInFlight = false,
            ),
        )
        run {
            val carried = dev.librepocket.agent.ui.effectiveLogoutRecovery(
                "LOGOUT_DELETE_FAILED",
                journalA.format(),
                PendingTeardownState.KnownPending(journalC),
                logoutInFlight = false,
            )
            assertEquals("LOGOUT_UNKNOWN", carried.effectiveCode)
            assertEquals(journalC, carried.retryJournal)
        }
    }

    @Test
    fun readUnknown_zeroIo_goesRetryRead() {
        // strict 失敗 → ReadUnknown：零 I/O、僅重試讀取。
        val raw = rawStore("f-read-unknown-strict")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            raw.save(preseed())
        }
        val failingReads = StrictFailOnceDataStore(raw.dataStore, failReads = 1)
        val store = EndpointStore(failingReads)
        val vault = CountingVault(delegate)
        val vm = newVm(store, vault)
        val expected = PendingDelete("preset:openai", 1L, 1700000000000L)
        val result = awaitRetry(vm, expected)
        assertEquals(SetupViewModel.RetryResult.ReadUnknown, result)
        assertEquals(0, vault.deleteKeys)
        assertEquals(0, vault.putKeys)
        assertEquals(0, vault.hasKeys)
        assertEquals(0, failingReads.updateCalls)
        assertFalse(vm.form.value.logoutInFlight)
        assertNull(vm.form.value.errorCode)
        runBlocking {
            assertTrue(delegate.hasKey("preset:openai"))
            assertEquals(1L, raw.currentRevision())
        }
        // 毀損 journal → ReadUnknown：零 I/O、journal 原樣保留。
        val rawMalformed = rawStore("f-read-unknown-malformed")
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            rawMalformed.save(preseed())
            rawMalformed.dataStore.edit { prefs ->
                prefs[ENDPOINT_PENDING_DELETE_V1] = "v9|broken"
            }
        }
        val storeMalformed = EndpointStore(rawMalformed.dataStore)
        val vaultMalformed = CountingVault(delegate)
        val vmMalformed = newVm(storeMalformed, vaultMalformed)
        val resultMalformed = awaitRetry(vmMalformed, expected)
        assertEquals(SetupViewModel.RetryResult.ReadUnknown, resultMalformed)
        assertEquals(0, vaultMalformed.deleteKeys)
        assertEquals(0, vaultMalformed.putKeys)
        assertEquals(0, vaultMalformed.hasKeys)
        runBlocking {
            assertEquals(
                "v9|broken",
                storeMalformed.dataStore.data.first()[ENDPOINT_PENDING_DELETE_V1],
            )
            assertTrue(delegate.hasKey("preset:openai"))
            assertEquals(1L, storeMalformed.currentRevision())
        }
        assertFalse(vmMalformed.form.value.logoutInFlight)
    }

    @Test
    fun inFlightMutualExclusion_retryBlockedWhileLogoutRuns() {
        // inFlight 互斥：logout 在途時重試直接忽略（不覆 job、不調 onDone、零新增 I/O）。
        val store = rawStore("f-inflight-mutex")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
        }
        val gating = DeleteGatingLogoutVault(delegate)
        val vm = newVm(store, gating)
        var logoutDone = false
        var logoutValue = false
        vm.logout {
            logoutDone = true
            logoutValue = it
        }
        runBlocking { withTimeout(10_000) { gating.deleteEntered.await() } }
        val firstJob = vm.lastLogoutJobForTest ?: error("no logout job")
        assertTrue(vm.form.value.logoutInFlight)
        // 重試在途忽略。
        var retryDoneCalled = false
        vm.retryPendingDelete(PendingDelete("preset:openai", 1L, 1700000000000L)) {
            retryDoneCalled = true
        }
        assertTrue("retry must be ignored while logout runs", vm.form.value.logoutInFlight)
        assertTrue(firstJob === vm.lastLogoutJobForTest)
        // 反向亦互斥：重試在途時 logout 忽略（以第二 VM 的快速重試覆蓋首 job？此處
        // 只斷言同 VM 第二入口不覆蓋：放行 logout 後重試仍未跑）。
        gating.deleteRelease.complete(Unit)
        runBlocking {
            withTimeout(10_000) {
                while (!logoutDone) delay(5)
            }
        }
        assertTrue(logoutValue)
        assertFalse(retryDoneCalled)
        assertFalse(vm.form.value.logoutInFlight)
        assertEquals(1, gating.deleteKeys)
    }

    @Test
    fun pendingNullRevMatches_neverFallsBackToFresh() {
        // pending==null && rev==expected（生產不可達）→ StaleGone 清卡、禁回退 fresh。
        val store = rawStore("f-nofallback-fresh")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
            assertEquals(1L, store.currentRevision())
        }
        val expected = PendingDelete("preset:openai", 1L, 1700000000000L)
        val vault = CountingVault(delegate)
        val vm = newVm(store, vault)
        // 模擬舊卡殘留（DELETE_FAILED＋J_A tag），但 store 真無 journal 且 rev 恰為 1。
        vm.onStartupReconcile(
            SetupViewModel.PendingDeleteOutcome.DELETE_FAILED,
            expected,
            gateReady = true,
        )
        assertEquals("LOGOUT_DELETE_FAILED", vm.form.value.errorCode)
        val result = awaitRetry(vm, expected)
        assertEquals(SetupViewModel.RetryResult.StaleGone, result)
        // 禁回退 fresh：零 vault 刪、meta／rev 不變、無新 journal。
        assertEquals(0, vault.deleteKeys)
        runBlocking {
            assertNull(store.pendingDelete())
            assertEquals(1L, store.currentRevision())
            assertEquals("provider_key/preset:openai", store.snapshotStrict()!!.apiKeyRef)
            assertTrue(delegate.hasKey("preset:openai"))
        }
        // config 非 null → 報 STALE 待 dismiss（舊 DELETE 退場）。
        assertEquals("LOGOUT_STALE", vm.form.value.errorCode)
        assertEquals(expected.format(), vm.form.value.logoutErrorSourceJournal)
    }

    @Test
    fun pendingNullConfigNull_sameOriginSilentlyClearsWithZeroIo() {
        // pending==null && config==null：同源靜默清卡、零 I/O、禁回退 fresh。
        // 序列：J_A COMMITTED（rev 1→2、無 meta、無 journal）後，殘留舊卡
        // DELETE_FAILED(J_A) 的第二 VM 以同 J_A 重試 → StaleGone＋清卡。
        val store = rawStore("f-absent-null-silent")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val journalA = PendingDelete("preset:openai", 1L, 1700000000000L)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
            assertTrue(store.writePendingDeleteIfAbsent(journalA))
        }
        // 首輪：健康重放至 COMMITTED（整表重置）。
        val vmCommit = newVm(store, CountingVault(delegate))
        vmCommit.onStartupReconcile(
            SetupViewModel.PendingDeleteOutcome.DELETE_FAILED,
            journalA,
            gateReady = false,
        )
        val committed = awaitRetry(vmCommit, journalA)
        assertTrue(committed is SetupViewModel.RetryResult.Replayed)
        assertEquals(
            SetupViewModel.PendingDeleteOutcome.COMMITTED,
            (committed as SetupViewModel.RetryResult.Replayed).outcome,
        )
        runBlocking {
            assertNull(store.pendingDelete())
            assertEquals(2L, store.currentRevision())
            assertFalse(store.hasMetadata())
            assertFalse(delegate.hasKey("preset:openai"))
        }
        // 次輪：新 VM＋新計數 vault（零基線），殘留同源舊卡後重試。
        val vault = CountingVault(delegate)
        val vm = newVm(store, vault)
        vm.onStartupReconcile(
            SetupViewModel.PendingDeleteOutcome.DELETE_FAILED,
            journalA,
            gateReady = false,
        )
        assertEquals("LOGOUT_DELETE_FAILED", vm.form.value.errorCode)
        assertEquals(journalA.format(), vm.form.value.logoutErrorSourceJournal)
        val result = awaitRetry(vm, journalA)
        assertEquals(SetupViewModel.RetryResult.StaleGone, result)
        // 零 I/O：零 vault 刪／寫／讀、rev 不動、無新 journal、meta 仍空。
        assertEquals(0, vault.deleteKeys)
        assertEquals(0, vault.putKeys)
        assertEquals(0, vault.hasKeys)
        runBlocking {
            assertNull(store.pendingDelete())
            assertEquals(2L, store.currentRevision())
            assertFalse(store.hasMetadata())
            assertFalse(delegate.hasKey("preset:openai"))
        }
        // 同源靜默清卡：碼＋tag 皆空（非 STALE 待 dismiss）。
        assertNull(vm.form.value.errorCode)
        assertNull(vm.form.value.logoutErrorSourceJournal)
        assertFalse(vm.form.value.logoutInFlight)
    }

    @Test
    fun internalStale_sameSnapshotLosesCas_reportsStaleGone() {
        // 內部 STALE：快照同源、回放中 CAS 落敗（跨 VM 可達：A 快照 J_A 後
        // B 先 COMMITTED，A 回放 clearIfRevision(1) 見 rev 2 → STALE）。
        // A 以 gating vault 停在 delete 內，B 同 J_A 先 COMMIT，A 放行後得
        // StaleGone＋STALE 碼；B 的提交（rev 2、無 meta、無 journal）不被覆寫。
        val store = rawStore("f-internal-stale")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val journalA = PendingDelete("preset:openai", 1L, 1700000000000L)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
            assertTrue(store.writePendingDeleteIfAbsent(journalA))
        }
        val gatingA = DeleteGatingLogoutVault(delegate)
        val vmA = newVm(store, gatingA)
        vmA.onStartupReconcile(
            SetupViewModel.PendingDeleteOutcome.DELETE_FAILED,
            journalA,
            gateReady = false,
        )
        assertEquals("LOGOUT_DELETE_FAILED", vmA.form.value.errorCode)
        // A 先行：快照 J_A 後停在 deleteKey 內。
        val doneA = CompletableDeferred<SetupViewModel.RetryResult>()
        vmA.retryPendingDelete(journalA) { doneA.complete(it) }
        runBlocking { withTimeout(10_000) { gatingA.deleteEntered.await() } }
        // B 同 J_A 先 COMMIT（健康 vault，同核心、不同 VM 鎖）。
        val vaultB = CountingVault(delegate)
        val vmB = newVm(store, vaultB)
        val doneB = CompletableDeferred<SetupViewModel.RetryResult>()
        vmB.retryPendingDelete(journalA) { doneB.complete(it) }
        val resultB = runBlocking { withTimeout(10_000) { doneB.await() } }
        assertTrue(resultB is SetupViewModel.RetryResult.Replayed)
        assertEquals(
            SetupViewModel.PendingDeleteOutcome.COMMITTED,
            (resultB as SetupViewModel.RetryResult.Replayed).outcome,
        )
        runBlocking {
            assertNull(store.pendingDelete())
            assertEquals(2L, store.currentRevision())
            assertFalse(store.hasMetadata())
        }
        // A 放行：delete 冪等（鍵已無）後 clearIfRevision(1) 落敗 → STALE。
        gatingA.deleteRelease.complete(Unit)
        val resultA = runBlocking { withTimeout(10_000) { doneA.await() } }
        assertEquals(SetupViewModel.RetryResult.StaleGone, resultA)
        assertEquals(1, gatingA.deleteKeys)
        // A 不覆寫 B：rev 仍 2、無 journal、meta 仍空。
        runBlocking {
            assertNull(store.pendingDelete())
            assertEquals(2L, store.currentRevision())
            assertFalse(store.hasMetadata())
            assertFalse(delegate.hasKey("preset:openai"))
        }
        // 舊卡退場：DELETE_FAILED 被 STALE(expected tag) 取代，待 dismiss。
        assertEquals("LOGOUT_STALE", vmA.form.value.errorCode)
        assertEquals(journalA.format(), vmA.form.value.logoutErrorSourceJournal)
        assertFalse(vmA.form.value.logoutInFlight)
    }
}

/** 讀失敗一次後直通（strict 失敗誘發 ReadUnknown 用；寫直通計數）。 */
private class StrictFailOnceDataStore(
    private val delegate: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
    private var failReads: Int = 1,
    private val error: IOException = IOException("STRICT_READ_FAILED"),
) : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
    var updateCalls = 0
        private set
    private var readCalls = 0
    override val data: Flow<androidx.datastore.preferences.core.Preferences>
        get() = flow {
            if (readCalls < failReads) {
                readCalls++
                throw error
            }
            emitAll(delegate.data)
        }

    override suspend fun updateData(transform: suspend (t: androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences {
        updateCalls++
        return delegate.updateData(transform)
    }
}
