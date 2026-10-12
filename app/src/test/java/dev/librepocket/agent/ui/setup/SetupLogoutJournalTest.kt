package dev.librepocket.agent.ui.setup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 1 logout teardown journal (Re-review #8 Finding 1 P1).
 *
 * The pending-delete journal makes the delete→clear window restartable and
 * resumable: logout journals BEFORE the vault delete, the fenced clear removes
 * the journal atomically on a win, and a crash/cancel in between replays via
 * [SetupViewModel.reconcilePendingDelete] (startup + logout-failure shared
 * path) instead of stranding torn-down metadata undiscovered.
 *
 * Reuses the existing fault doubles ([CountingVault], [MutablePolicy],
 * [GatedUpdateDataStore], [LogoutClearFailingDataStore]); no KeyVault
 * interface, schema-migration, or cross-file DB change.
 *
 * Residual (not this phase): save-side staged orphans still need a vault list
 * API for GC; cross-process DataStore fencing stays single-process-only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupLogoutJournalTest {
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

    private fun rawDelegate(dirName: String): androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
        val dir = Files.createTempDirectory(dirName).toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "logout-journal.preferences_pb") },
        )
    }

    private fun journalPreseed(model: String = "m-old"): EndpointConfig = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = model,
        apiKeyRef = "provider_key/preset:openai",
    )

    @Test
    fun startupWithNoPendingIsNoOp() {
        // 啟動無 pending → NO_OP: no vault I/O, no metadata write, no revision.
        val store = EndpointStore(rawDelegate("logout-journal-noop"))
        val vault = CountingVault()
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        val outcome = runBlocking { vm.reconcilePendingDelete() }
        assertEquals(SetupViewModel.PendingDeleteOutcome.NO_OP, outcome)
        assertEquals(0, vault.putKeys)
        assertEquals(0, vault.deleteKeys)
        assertEquals(0, vault.getKeys)
        assertEquals(0, vault.hasKeys)
        runBlocking {
            assertNull(store.pendingDelete())
            assertFalse(store.hasMetadata())
            assertNull(store.currentRevision())
        }
    }

    @Test
    fun pendingFoldsReadinessAndGate() {
        // Pending journal folds lenient readers first-unavailable: observe
        // null, readiness NO_KEY (no vault touch), gate NoEndpoint — while the
        // strict reads still expose the raw values for fencing/reconcile.
        val raw = rawDelegate("logout-journal-fold")
        val store = EndpointStore(raw)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(journalPreseed())
            assertTrue(store.writePendingDeleteIfAbsent(PendingDelete("preset:openai", 1L, 1700000000000L)))
        }
        val vault = CountingVault(delegate)
        runBlocking {
            assertNull(store.observe().first())
            assertFalse(store.hasMetadata())
            assertEquals(EndpointReadiness.NO_KEY, store.readiness(vault))
            assertEquals(0, vault.hasKeys)
            // Gate maps a null observe to NoEndpoint by construction (no
            // separate collection here: activating the shared gate flow would
            // outlive the DataStore scope torn down below).
            assertNull(store.observe().first())
            // Strict readers stay raw for fencing/reconcile.
            assertEquals("provider_key/preset:openai", store.snapshotStrict()!!.apiKeyRef)
            assertEquals(1L, store.currentRevision())
            assertEquals("preset:openai", store.pendingDelete()?.alias)
        }
    }

    @Test
    fun malformedJournalFailsClosed() {
        // Malformed journal: lenient readers still fold (unavailable), strict
        // journal reads throw, reconcile keeps the journal with zero vault or
        // metadata side effects.
        val raw = rawDelegate("logout-journal-malformed")
        val store = EndpointStore(raw)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(journalPreseed())
            store.dataStore.edit { prefs ->
                prefs[ENDPOINT_PENDING_DELETE_V1] = "v9|broken"
            }
        }
        val vault = CountingVault(delegate)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        runBlocking {
            assertNull(store.observe().first())
            assertEquals(EndpointReadiness.NO_KEY, store.readiness(vault))
            assertEquals(0, vault.hasKeys)
            // Raw metadata is untouched underneath the fold.
            assertEquals("provider_key/preset:openai", store.snapshotStrict()!!.apiKeyRef)
            assertEquals(1L, store.currentRevision())
            try {
                store.pendingDelete()
                error("expected ENDPOINT_PENDING_DELETE_MALFORMED")
            } catch (e: IllegalStateException) {
                assertEquals("ENDPOINT_PENDING_DELETE_MALFORMED", e.message)
            }
            val outcome = vm.reconcilePendingDelete()
            assertEquals(SetupViewModel.PendingDeleteOutcome.UNKNOWN, outcome)
            assertEquals(0, vault.deleteKeys)
            assertEquals("v9|broken", store.dataStore.data.first()[ENDPOINT_PENDING_DELETE_V1])
            assertEquals("provider_key/preset:openai", store.snapshotStrict()!!.apiKeyRef)
            assertEquals(1L, store.currentRevision())
            assertTrue(delegate.hasKey("preset:openai"))
        }
    }

    @Test
    fun cancelAfterDeleteReplaysToCommittedOnRestart() {
        // 刪後取消 → restart 可完成: journal + delete win, clear parks, cancel
        // rethrows with the journal kept; a fresh ViewModel on the same store
        // (restart) replays to COMMITTED.
        val raw = rawDelegate("logout-journal-cancel-replay")
        val preseed = EndpointStore(raw)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val vault = CountingVault(delegate)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            preseed.save(journalPreseed())
            assertEquals(1L, preseed.currentRevision())
        }
        val gated = GatedUpdateDataStore(raw, gateBeforeUpdate = true, updatesToPass = 1)
        val store = EndpointStore(gated)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        var onDoneCalled = false
        vm.logout { onDoneCalled = true }
        runBlocking { withTimeout(10_000) { gated.entered.await() } }
        val job = vm.lastLogoutJobForTest ?: error("no logout job")
        runBlocking {
            job.cancel()
            job.join()
        }
        assertTrue(job.isCancelled)
        assertNull(vm.form.value.errorCode)
        assertFalse(onDoneCalled)
        runBlocking {
            assertEquals("preset:openai", store.pendingDelete()?.alias)
            assertEquals(1L, store.pendingDelete()?.revision)
            assertNull(store.observe().first())
            assertFalse("delete won before the gate", delegate.hasKey("preset:openai"))
            assertEquals(1L, store.currentRevision())
        }
        // Restart: the gate is gone (process relaunch flushes the parked
        // writer); a fresh ViewModel replays the journal to completion.
        gated.gateBeforeUpdate = false
        gated.release.complete(Unit)
        val vmRestart = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        val outcome = runBlocking { vmRestart.reconcilePendingDelete() }
        assertEquals(SetupViewModel.PendingDeleteOutcome.COMMITTED, outcome)
        runBlocking {
            assertNull(store.pendingDelete())
            assertFalse(store.hasMetadata())
            assertEquals(2L, store.currentRevision())
            assertFalse(delegate.hasKey("preset:openai"))
        }
        // Delete ran once (the replay re-delete is the same idempotent alias,
        // counted once more here — exactly the journal alias, never a live slot).
        assertTrue(vault.deletedIds.isNotEmpty())
        assertTrue(vault.deletedIds.all { it == "preset:openai" })
    }

    @Test
    fun permanentClearFailureKeepsJournalThenReplaySucceeds() {
        // 永久 clear IOException → journal 保留 + 重放成功: logout reports
        // CLEAR_FAILED with the journal kept and readers folded; a healthy
        // store on the same delegate then replays to COMMITTED.
        val raw = rawDelegate("logout-journal-clear-fail-replay")
        val realStore = EndpointStore(raw)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val vault = CountingVault(delegate)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            realStore.save(journalPreseed())
            assertEquals(1L, realStore.currentRevision())
        }
        val failing = LogoutClearFailingDataStore(raw, passThroughWrites = 1)
        val store = EndpointStore(failing)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        val done = CompletableDeferred<Boolean>()
        vm.logout { done.complete(it) }
        val ok = runBlocking { withTimeout(10_000) { done.await() } }
        assertFalse(ok)
        assertEquals("LOGOUT_CLEAR_FAILED", vm.form.value.errorCode)
        runBlocking {
            assertEquals("preset:openai", store.pendingDelete()?.alias)
            assertEquals(1L, store.pendingDelete()?.revision)
            assertNull(store.observe().first())
            assertEquals(EndpointReadiness.NO_KEY, store.readiness(delegate))
            assertFalse(delegate.hasKey("preset:openai"))
        }
        // Transport recovers (same delegate, healthy wrapper): replay commits.
        val healthy = EndpointStore(raw)
        val vmReplay = SetupViewModel(
            store = healthy,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        val outcome = runBlocking { vmReplay.reconcilePendingDelete() }
        assertEquals(SetupViewModel.PendingDeleteOutcome.COMMITTED, outcome)
        runBlocking {
            assertNull(healthy.pendingDelete())
            assertFalse(healthy.hasMetadata())
            assertEquals(2L, healthy.currentRevision())
            assertFalse(delegate.hasKey("preset:openai"))
        }
    }

    @Test
    fun reconcileStaleDiscardsJournalWithoutTouchingWinner() {
        // STALE: a save commits a new generation over a pending journal; the
        // replay deletes only the stale alias, loses the CAS, discards the
        // superseded journal, and leaves the winner byte-for-byte intact.
        val raw = rawDelegate("logout-journal-stale")
        val store = EndpointStore(raw)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(journalPreseed())
            assertEquals(1L, store.currentRevision())
            assertTrue(store.writePendingDeleteIfAbsent(PendingDelete("preset:openai", 1L, 1700000000000L)))
        }
        val winnerAlias = "preset:openai#staged-" + "b".repeat(32)
        runBlocking {
            delegate.putKey(winnerAlias, "unit-test-winner-key-BBBBBBBB".toCharArray())
            store.save(journalPreseed(model = "m-new").copy(apiKeyRef = "provider_key/$winnerAlias"))
            assertEquals(2L, store.currentRevision())
        }
        val vault = CountingVault(delegate)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        val outcome = runBlocking { vm.reconcilePendingDelete() }
        assertEquals(SetupViewModel.PendingDeleteOutcome.STALE, outcome)
        runBlocking {
            assertNull("superseded journal is discarded", store.pendingDelete())
            assertEquals(2L, store.currentRevision())
            assertEquals("provider_key/$winnerAlias", store.observe().first()!!.apiKeyRef)
            assertTrue("winner key survives", delegate.hasKey(winnerAlias))
            assertFalse("stale predecessor re-delete is harmless", delegate.hasKey("preset:openai"))
            assertEquals(EndpointReadiness.READY, store.readiness(delegate))
        }
        assertEquals(listOf("preset:openai"), vault.deletedIds)
    }

    @Test
    fun staleDiscardAfterJournalRecreatedKeepsReplacement() {
        // ABA: R1 replays J_A and parks inside clearIfRevision (after the
        // idempotent delete won); a newer generation — replay J_A to
        // COMMITTED, save winner metadata B, journal J_B — lands between
        // R1's CAS-loss and R1's discard. The conditional discard must keep
        // J_B: the outcome is still STALE, but the replacement journal,
        // winner metadata, and revision survive byte-for-byte.
        val raw = rawDelegate("logout-journal-aba-keep")
        val preseed = EndpointStore(raw)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val winnerAlias = "preset:openai#staged-" + "b".repeat(32)
        val journalA = PendingDelete("preset:openai", 1L, 1700000000000L)
        val journalB = PendingDelete(winnerAlias, 3L, 1800000000000L)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            delegate.putKey(winnerAlias, "unit-test-winner-key-BBBBBBBB".toCharArray())
            preseed.save(journalPreseed())
            assertEquals(1L, preseed.currentRevision())
            assertTrue(preseed.writePendingDeleteIfAbsent(journalA))
        }
        // updatesToPass = 0: R1's clearIfRevision (update #1) parks at the
        // gate BEFORE touching the delegate, so the winner below writes via
        // the raw delegate with the gate counter untouched (no counting
        // flake); the parked update holds no DataStore lock, so no deadlock.
        val gated = GatedUpdateDataStore(raw, gateBeforeUpdate = true, updatesToPass = 0)
        val store = EndpointStore(gated)
        val vault = CountingVault(delegate)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        val r1Scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(r1Scope)
        val r1 = r1Scope.async { vm.reconcilePendingDelete() }
        runBlocking { withTimeout(10_000) { gated.entered.await() } }
        val healthy = EndpointStore(raw)
        runBlocking {
            assertTrue(healthy.clearIfRevision(1L))
            assertEquals(2L, healthy.currentRevision())
            healthy.save(journalPreseed(model = "m-new").copy(apiKeyRef = "provider_key/$winnerAlias"))
            assertEquals(3L, healthy.currentRevision())
            assertTrue(healthy.writePendingDeleteIfAbsent(journalB))
        }
        gated.release.complete(Unit)
        val outcome = runBlocking { withTimeout(10_000) { r1.await() } }
        assertEquals(SetupViewModel.PendingDeleteOutcome.STALE, outcome)
        runBlocking {
            assertEquals("replacement journal survives the stale discard", journalB, healthy.pendingDelete())
            assertEquals(3L, healthy.currentRevision())
            assertEquals("provider_key/$winnerAlias", healthy.snapshotStrict()!!.apiKeyRef)
            assertEquals("m-new", healthy.snapshotStrict()!!.model)
            // Lenient readers stay folded while the replacement is in flight.
            assertNull(healthy.observe().first())
            assertTrue("winner key survives", delegate.hasKey(winnerAlias))
            assertFalse("stale predecessor re-delete is harmless", delegate.hasKey("preset:openai"))
        }
        // R1 deleted only the stale predecessor alias, never the winner slot.
        assertEquals(listOf("preset:openai"), vault.deletedIds)
    }

    private fun awaitSaveSettled(vm: SetupViewModel, timeoutMs: Long = 8000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (vm.form.value.saving) {
            if (System.currentTimeMillis() > end) error("timed out waiting for save")
            Thread.sleep(10)
        }
    }

    @Test
    fun saveBlockedByPresentJournalFailsClosed() {
        // Stage 2 P2: preseed A + J_A → save 擋下 fail-closed：saved=false +
        // blocked 碼、staged 已清（早期圍欄：未寫 vault）、metadata 仍 A、
        // revision 不動、journal 保留、readiness NO_KEY。
        val raw = rawDelegate("stage2-save-blocked")
        val store = EndpointStore(raw)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val journalA = PendingDelete("preset:openai", 1L, 1700000000000L)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(journalPreseed())
            assertEquals(1L, store.currentRevision())
            assertTrue(store.writePendingDeleteIfAbsent(journalA))
        }
        val vault = CountingVault(delegate)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        awaitSaveSettled(vm)
        assertFalse(vm.form.value.saved)
        assertEquals("SAVE_BLOCKED_BY_TEARDOWN", vm.form.value.errorCode)
        assertEquals("有待清理的登出狀態，請重試。", setupErrorText("SAVE_BLOCKED_BY_TEARDOWN"))
        runBlocking {
            assertEquals("early fence: no staged vault write", 0, vault.putKeys)
            assertEquals("provider_key/preset:openai", store.snapshotStrict()!!.apiKeyRef)
            assertEquals(1L, store.currentRevision())
            assertEquals(journalA, store.pendingDelete())
            assertNull(store.observe().first())
            assertEquals(EndpointReadiness.NO_KEY, store.readiness(delegate))
            assertTrue(delegate.hasKey("preset:openai"))
        }
    }

    @Test
    fun staleLogoutJournalWriteFencedAfterWinnerCommits() {
        // Stage 2 P2 雙 VM：L 快照 rev1 後 park 在 journal write；S 提交 B 到
        // rev2；放行 L → fenced write 回 false、不做 vault delete、observe 全程
        // B、L 報 LOGOUT_STALE、readiness(B)==READY。
        val raw = rawDelegate("stage2-stale-logout-fenced")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            EndpointStore(raw).save(journalPreseed())
        }
        val gated = GatedUpdateDataStore(raw, gateBeforeUpdate = true, updatesToPass = 0)
        val storeL = EndpointStore(gated)
        val vaultL = CountingVault(delegate)
        val vmL = SetupViewModel(
            store = storeL,
            vaultSource = VaultSource { vaultL },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        val done = CompletableDeferred<Boolean>()
        vmL.logout { done.complete(it) }
        runBlocking { withTimeout(10_000) { gated.entered.await() } }
        val storeS = EndpointStore(raw)
        val vaultS = CountingVault(delegate)
        val vmS = SetupViewModel(
            store = storeS,
            vaultSource = VaultSource { vaultS },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        vmS.onApiKeyChange("unit-test-winner-key-BBBBBBBB")
        vmS.save()
        awaitSaveSettled(vmS)
        assertTrue(vmS.form.value.saved)
        runBlocking { assertEquals(2L, storeS.currentRevision()) }
        gated.release.complete(Unit)
        val ok = runBlocking { withTimeout(10_000) { done.await() } }
        assertFalse(ok)
        assertEquals("LOGOUT_STALE", vmL.form.value.errorCode)
        assertEquals("stale logout performs no vault delete", 0, vaultL.deleteKeys)
        assertTrue(vaultL.deletedIds.isEmpty())
        runBlocking {
            val winnerRef = storeS.observe().first()!!.apiKeyRef
            assertTrue(winnerRef.startsWith("provider_key/preset:openai#staged-"))
            assertNull("stale journal never lands", storeS.pendingDelete())
            assertEquals(2L, storeS.currentRevision())
            assertEquals(EndpointReadiness.READY, storeS.readiness(delegate))
        }
    }

    @Test
    fun saveJournalRaceBetweenSnapshotAndCasFailsClosed() {
        // Stage 2 P2 barrier：save park 在 CAS 前，期間寫入 J_A，放行後原子圍欄
        // 擋下：saved=false、blocked 碼、staged 已清、journal 保留、metadata 仍
        // A、revision 不動。
        val raw = rawDelegate("stage2-save-journal-race")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val healthy = EndpointStore(raw)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            healthy.save(journalPreseed())
            assertEquals(1L, healthy.currentRevision())
        }
        val gated = GatedUpdateDataStore(raw, gateBeforeUpdate = true, updatesToPass = 0)
        val store = EndpointStore(gated)
        val vault = CountingVault(delegate)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        runBlocking { withTimeout(10_000) { gated.entered.await() } }
        val journalA = PendingDelete("preset:openai", 1L, 1700000000000L)
        runBlocking { assertTrue(healthy.writePendingDeleteIfAbsent(journalA)) }
        gated.release.complete(Unit)
        awaitSaveSettled(vm)
        assertFalse(vm.form.value.saved)
        assertEquals("SAVE_BLOCKED_BY_TEARDOWN", vm.form.value.errorCode)
        val staged = vault.putIds.single()
        runBlocking {
            assertEquals(listOf(staged), vault.deletedIds)
            assertFalse(delegate.hasKey(staged))
            assertEquals(journalA, healthy.pendingDelete())
            assertEquals("provider_key/preset:openai", healthy.snapshotStrict()!!.apiKeyRef)
            assertEquals(1L, healthy.currentRevision())
            assertNull(healthy.observe().first())
        }
    }

    @Test
    fun malformedJournalBlocksSaveFailClosed() {
        // Stage 2 P2：手寫 v9|broken → save 擋下 fail-closed：saved=false +
        // blocked 碼、無 vault 寫入、metadata/revision 不動、journal 原樣保留。
        val raw = rawDelegate("stage2-malformed-blocks-save")
        val store = EndpointStore(raw)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(journalPreseed())
            store.dataStore.edit { prefs ->
                prefs[ENDPOINT_PENDING_DELETE_V1] = "v9|broken"
            }
        }
        val vault = CountingVault(delegate)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        awaitSaveSettled(vm)
        assertFalse(vm.form.value.saved)
        assertEquals("SAVE_BLOCKED_BY_TEARDOWN", vm.form.value.errorCode)
        runBlocking {
            assertEquals(0, vault.putKeys)
            assertEquals("v9|broken", store.dataStore.data.first()[ENDPOINT_PENDING_DELETE_V1])
            assertEquals("provider_key/preset:openai", store.snapshotStrict()!!.apiKeyRef)
            assertEquals(1L, store.currentRevision())
            assertNull(store.observe().first())
            assertEquals(EndpointReadiness.NO_KEY, store.readiness(delegate))
        }
    }

    @Test
    fun blockedSaveReconcileThenRetryReachesReady() {
        // Stage 2 P2 拒絕後行為：blocked save → reconcile 解決 journal → 重試
        // save → saved=true + READY。
        val raw = rawDelegate("stage2-reconcile-retry")
        val store = EndpointStore(raw)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(journalPreseed())
            assertTrue(store.writePendingDeleteIfAbsent(PendingDelete("preset:openai", 1L, 1700000000000L)))
        }
        val vault = CountingVault(delegate)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        awaitSaveSettled(vm)
        assertFalse(vm.form.value.saved)
        assertEquals("SAVE_BLOCKED_BY_TEARDOWN", vm.form.value.errorCode)
        val outcome = runBlocking { vm.reconcilePendingDelete() }
        assertEquals(SetupViewModel.PendingDeleteOutcome.COMMITTED, outcome)
        vm.save()
        awaitSaveSettled(vm)
        assertTrue(vm.form.value.saved)
        runBlocking {
            val ref = store.observe().first()!!.apiKeyRef
            assertTrue(ref.startsWith("provider_key/preset:openai#staged-"))
            assertEquals(EndpointReadiness.READY, store.readiness(delegate))
        }
    }

    @Test
    fun cancelParkedAtCasWithJournalLandedNeverReportsSuccess() {
        // Stage 2 P2 cancel 感知：save park 在 CAS，期間 journal 落下，cancel
        // 不得 swallow 成 saved=true（journal 在途即拒絕成功、只清自己的
        // staged、journal/metadata/revision 原樣保留）。
        val raw = rawDelegate("stage2-cancel-journal")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val healthy = EndpointStore(raw)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            healthy.save(journalPreseed())
        }
        val gated = GatedUpdateDataStore(raw, gateBeforeUpdate = true, updatesToPass = 0)
        val store = EndpointStore(gated)
        val vault = CountingVault(delegate)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        runBlocking { withTimeout(10_000) { gated.entered.await() } }
        val staged = vault.putIds.single()
        val journalA = PendingDelete("preset:openai", 1L, 1700000000000L)
        runBlocking { assertTrue(healthy.writePendingDeleteIfAbsent(journalA)) }
        val job = vm.lastSaveJobForTest ?: error("no save job")
        runBlocking {
            job.cancel()
            job.join()
        }
        assertTrue(job.isCancelled)
        assertFalse(vm.form.value.saving)
        assertFalse(vm.form.value.saved)
        assertNull(vm.form.value.errorCode)
        runBlocking {
            assertEquals(listOf(staged), vault.deletedIds)
            assertFalse(delegate.hasKey(staged))
            assertEquals(journalA, healthy.pendingDelete())
            assertEquals("provider_key/preset:openai", healthy.snapshotStrict()!!.apiKeyRef)
            assertEquals(1L, healthy.currentRevision())
        }
    }
}
