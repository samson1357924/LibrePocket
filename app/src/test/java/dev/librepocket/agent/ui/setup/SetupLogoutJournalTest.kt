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
}
