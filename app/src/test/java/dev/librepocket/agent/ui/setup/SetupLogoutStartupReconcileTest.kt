package dev.librepocket.agent.ui.setup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
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
 * DataStore whose first [failReads] `data` collections throw (models a strict
 * re-read transport failure for `snapshotDeleteStateStrict`), then delegates
 * to the real store so retention can be verified on the same instance.
 * Counts `updateData` to prove no clear is attempted on the UNKNOWN path.
 */
private class StrictReadFailingDataStore(
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

/**
 * Stage 3 P2：replay 錯誤分類與啟動可見性。
 *
 * - resume/journal replay 的 delete 失敗一律報 `DELETE_FAILED`（非 `FAILED`），
 *   resume 映射為 `LOGOUT_DELETE_FAILED`（非 `LOGOUT_CLEAR_FAILED`，不斷言
 *   「已刪除」）；journal 保留、key 仍在、observe 仍折疊。
 * - 健康 vault 重播提交（COMMITTED）。
 * - 啟動 replay 經 [SetupViewModel.onStartupReconcile] 落碼：DELETE→DELETE 碼、
 *   UNKNOWN→中性碼（非 CLEAR、文案無「已刪除」、零 vault I/O）、NO_OP 永不落
 *   CLEAR、STALE 顯式決策（gate Ready 靜默，否則落 STALE）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupLogoutStartupReconcileTest {
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
            produceFile = { java.io.File(dir, "startup-reconcile.preferences_pb") },
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

    @Test
    fun resumeDeleteFailsMapsToDeleteNotClear() {
        // journal + ThrowingDeleteVault 的 logout resume：outcome DELETE_FAILED，
        // errorCode LOGOUT_DELETE_FAILED（非 CLEAR）、journal 保留、key 仍在、
        // observe 仍折疊 null。
        val store = rawStore("resume-delete-fails")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
            assertEquals(1L, store.currentRevision())
            assertTrue(store.writePendingDeleteIfAbsent(PendingDelete("preset:openai", 1L, 1700000000000L)))
        }
        val vm = newVm(store, ThrowingDeleteVault(delegate))
        val done = CompletableDeferred<Boolean>()
        vm.logout { done.complete(it) }
        val ok = runBlocking { withTimeout(10_000) { done.await() } }
        assertFalse("resume delete failure must not report success", ok)
        assertEquals("LOGOUT_DELETE_FAILED", vm.form.value.errorCode)
        runBlocking {
            assertEquals("preset:openai", store.pendingDelete()?.alias)
            assertEquals(1L, store.pendingDelete()?.revision)
            assertTrue("key survives the failed delete", delegate.hasKey("preset:openai"))
            assertNull(store.observe().first())
            assertEquals(EndpointReadiness.NO_KEY, store.readiness(delegate))
        }
    }

    @Test
    fun resumeDeleteFailThenRetryWithHealthyVaultCommits() {
        // 上一個測試的殘留：換健康 vault 的 logout resume → COMMITTED。
        val store = rawStore("resume-delete-retry")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
            assertTrue(store.writePendingDeleteIfAbsent(PendingDelete("preset:openai", 1L, 1700000000000L)))
        }
        val failing = newVm(store, ThrowingDeleteVault(delegate))
        val failed = CompletableDeferred<Boolean>()
        failing.logout { failed.complete(it) }
        assertFalse(runBlocking { withTimeout(10_000) { failed.await() } })
        assertEquals("LOGOUT_DELETE_FAILED", failing.form.value.errorCode)

        val healthy = CountingVault(delegate)
        val retry = newVm(store, healthy)
        val done = CompletableDeferred<Boolean>()
        retry.logout { done.complete(it) }
        assertTrue("healthy retry must commit", runBlocking { withTimeout(10_000) { done.await() } })
        assertNull(retry.form.value.errorCode)
        runBlocking {
            assertNull(store.pendingDelete())
            assertFalse(store.hasMetadata())
            assertEquals(2L, store.currentRevision())
            assertFalse(delegate.hasKey("preset:openai"))
        }
        assertEquals(listOf("preset:openai"), healthy.deletedIds)
    }

    @Test
    fun startupReplayFailSurfacesDeleteCode() {
        // 啟動 replay（reconcile + onStartupReconcile）：DELETE_FAILED 落
        // LOGOUT_DELETE_FAILED、journal 保留、gate 折疊（observe null）；健康
        // vault 重播 → COMMITTED。
        val store = rawStore("startup-replay-fail")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
            assertTrue(store.writePendingDeleteIfAbsent(PendingDelete("preset:openai", 1L, 1700000000000L)))
        }
        val vm = newVm(store, ThrowingDeleteVault(delegate))
        val outcome = runBlocking { vm.reconcilePendingDelete() }
        assertEquals(SetupViewModel.PendingDeleteOutcome.DELETE_FAILED, outcome)
        vm.onStartupReconcile(outcome)
        assertEquals("LOGOUT_DELETE_FAILED", vm.form.value.errorCode)
        runBlocking {
            assertEquals("preset:openai", store.pendingDelete()?.alias)
            assertTrue(delegate.hasKey("preset:openai"))
            assertNull("gate folds to NoEndpoint while the journal is retained", store.observe().first())
        }
        // 健康 vault 重播提交。
        val replay = newVm(store, CountingVault(delegate))
        assertEquals(
            SetupViewModel.PendingDeleteOutcome.COMMITTED,
            runBlocking { replay.reconcilePendingDelete() },
        )
    }

    @Test
    fun startupReplayUnknownSurfacesNeutralCode() {
        // malformed journal：UNKNOWN、零 vault I/O；啟動落中性碼（非 CLEAR，
        // 文案不含「已刪除」）。journal 原樣保留、key 仍在。
        val store = rawStore("startup-replay-unknown")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
            store.dataStore.edit { prefs ->
                prefs[ENDPOINT_PENDING_DELETE_V1] = "v9|broken"
            }
        }
        val vault = CountingVault(delegate)
        val vm = newVm(store, vault)
        val outcome = runBlocking { vm.reconcilePendingDelete() }
        assertEquals(SetupViewModel.PendingDeleteOutcome.UNKNOWN, outcome)
        assertEquals("zero vault I/O on UNKNOWN", 0, vault.deleteKeys)
        assertEquals(0, vault.putKeys)
        runBlocking {
            assertEquals(
                "malformed journal must be kept for the next retry",
                "v9|broken",
                store.dataStore.data.first()[ENDPOINT_PENDING_DELETE_V1],
            )
            assertTrue("key survives UNKNOWN (no vault I/O)", delegate.hasKey("preset:openai"))
        }
        vm.onStartupReconcile(outcome)
        assertEquals("LOGOUT_UNKNOWN", vm.form.value.errorCode)
        val text = setupErrorText(vm.form.value.errorCode!!)
        assertFalse("neutral wording must not claim deleted: $text", text.contains("已刪除"))
    }

    @Test
    fun startupReplayStrictReadFailureSurfacesUnknownWithRetention() {
        // strict 重讀拋（snapshotDeleteStateStrict throw）：UNKNOWN、零 vault
        // I/O、journal 保留、key 仍在、啟動落 LOGOUT_UNKNOWN（非 CLEAR）。
        val raw = rawStore("startup-replay-read-fail")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            raw.save(preseed())
            assertTrue(raw.writePendingDeleteIfAbsent(PendingDelete("preset:openai", 1L, 1700000000000L)))
        }
        val failingReads = StrictReadFailingDataStore(raw.dataStore, failReads = 1)
        val store = EndpointStore(failingReads)
        val vault = CountingVault(delegate)
        val vm = newVm(store, vault)
        val outcome = runBlocking { vm.reconcilePendingDelete() }
        assertEquals(SetupViewModel.PendingDeleteOutcome.UNKNOWN, outcome)
        assertEquals("zero vault I/O on UNKNOWN", 0, vault.deleteKeys)
        assertEquals(0, vault.putKeys)
        assertEquals("no clear may be attempted after a strict-read failure", 0, failingReads.updateCalls)
        runBlocking {
            assertEquals("preset:openai", store.pendingDelete()?.alias)
            assertEquals(1L, store.pendingDelete()?.revision)
            assertTrue("key survives UNKNOWN (no vault I/O)", delegate.hasKey("preset:openai"))
        }
        vm.onStartupReconcile(outcome)
        assertEquals("LOGOUT_UNKNOWN", vm.form.value.errorCode)
        val text = setupErrorText(vm.form.value.errorCode!!)
        assertFalse("neutral wording must not claim deleted: $text", text.contains("已刪除"))
    }

    @Test
    fun noOpNeverMapsToClear() {
        // 無 journal：NO_OP；啟動處理永不落碼（更不落 CLEAR）。
        val store = rawStore("startup-noop")
        val vm = newVm(store, CountingVault())
        assertEquals(
            SetupViewModel.PendingDeleteOutcome.NO_OP,
            runBlocking { vm.reconcilePendingDelete() },
        )
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.NO_OP)
        assertNull(vm.form.value.errorCode)
        // COMMITTED 同為靜默（不寫、不清既有碼）。
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.COMMITTED)
        assertNull(vm.form.value.errorCode)
    }

    @Test
    fun committedAndNoOpNeverClearExistingCode() {
        // COMMITTED/NO_OP 永不清既有碼：預置 DELETE 碼後，兩者皆保留（提交
        // 無需橫幅；與提交競態的既有碼由其持有者保留）。
        val store = rawStore("startup-noop-retain")
        val vm = newVm(store, CountingVault())
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.DELETE_FAILED)
        assertEquals("LOGOUT_DELETE_FAILED", vm.form.value.errorCode)
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.COMMITTED)
        assertEquals(
            "COMMITTED must never clear a pre-existing LOGOUT_*",
            "LOGOUT_DELETE_FAILED",
            vm.form.value.errorCode,
        )
        vm.onStartupReconcile(SetupViewModel.PendingDeleteOutcome.NO_OP)
        assertEquals(
            "NO_OP must never clear a pre-existing LOGOUT_*",
            "LOGOUT_DELETE_FAILED",
            vm.form.value.errorCode,
        )
    }

    @Test
    fun startupStaleStampsWhenGateNotReady() {
        // STALE + gate 未 Ready（啟動時尚未解析）：落 LOGOUT_STALE 待 dismiss。
        val store = rawStore("startup-stale-stamp")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val winnerAlias = "preset:openai#staged-" + "b".repeat(32)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
            assertTrue(store.writePendingDeleteIfAbsent(PendingDelete("preset:openai", 1L, 1700000000000L)))
            delegate.putKey(winnerAlias, "unit-test-winner-key-BBBBBBBB".toCharArray())
            store.save(preseed(model = "m-new").copy(apiKeyRef = "provider_key/$winnerAlias"))
            assertEquals(2L, store.currentRevision())
        }
        val vm = newVm(store, CountingVault(delegate))
        val outcome = runBlocking { vm.reconcilePendingDelete() }
        assertEquals(SetupViewModel.PendingDeleteOutcome.STALE, outcome)
        // gate 未 Ready（啟動時尚未解析）→ 顯式落碼（經 test seam 直接給決策
        // 輸入；生產入口讀 live gate，見 onStartupReconcile 無參版本）。
        vm.onStartupReconcile(outcome, gateReady = false)
        assertEquals("LOGOUT_STALE", vm.form.value.errorCode)
    }

    @Test
    fun startupStaleSilentWhenGateReady() {
        // STALE + gate 已 Ready（贏家活著）：靜默，不打擾。
        val store = rawStore("startup-stale-silent")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val winnerAlias = "preset:openai#staged-" + "b".repeat(32)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseed())
            assertTrue(store.writePendingDeleteIfAbsent(PendingDelete("preset:openai", 1L, 1700000000000L)))
            delegate.putKey(winnerAlias, "unit-test-winner-key-BBBBBBBB".toCharArray())
            store.save(preseed(model = "m-new").copy(apiKeyRef = "provider_key/$winnerAlias"))
            assertEquals(2L, store.currentRevision())
        }
        val vm = newVm(store, CountingVault(delegate))
        val outcome = runBlocking { vm.reconcilePendingDelete() }
        assertEquals(SetupViewModel.PendingDeleteOutcome.STALE, outcome)
        // gate 已 Ready（贏家活著）→ 靜默（經 test seam 直接給決策輸入，
        // 不收集 WhileSubscribed gate 流以免與 teardown 取消競態）。
        vm.onStartupReconcile(outcome, gateReady = true)
        assertNull("Ready gate stays silent on STALE", vm.form.value.errorCode)
    }
}
