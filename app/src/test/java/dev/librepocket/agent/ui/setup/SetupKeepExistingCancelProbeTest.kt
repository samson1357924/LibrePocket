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
 * PR81 Stage S5: KeepExisting ambiguous-cancel dedicated probe (Finding #6).
 *
 * Covers [SetupViewModel] `probeKeepExistingCommit` + the `!reuseCommitted`
 * ambiguous branch of `executeKeepExistingCommit`:
 * - CAS-throws-Cancel-after-commit -> saved=true + invalidate;
 * - before-commit Cancel -> saved=false + store unchanged + rethrow;
 * - concurrent winner + cancel never saved=true (revision advanced but config
 *   mismatched -> rethrow + invalidate);
 * - journal-over-commit -> rethrow without saved;
 * - read-failure / malformed -> null -> rethrow;
 * - addExact overflow boundary never throws outward.
 *
 * S5 scope only: staged path, dialog (S4) and binding (S3) untouched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupKeepExistingCancelProbeTest {
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

    private fun openAiConfig(model: String = "m-old") = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = model,
        apiKeyRef = "provider_key/preset:openai",
    )

    private data class GatedHarness(
        val raw: DataStore<Preferences>,
        val gated: GatedUpdateDataStore,
        val store: EndpointStore,
        val rawStore: EndpointStore,
        val vault: CountingVault,
        val vm: SetupViewModel,
    )

    private fun newGatedHarness(): GatedHarness {
        val dir = Files.createTempDirectory("setup-s5-probe").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val raw: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "s5.preferences_pb") },
        )
        val gated = GatedUpdateDataStore(raw)
        val store = EndpointStore(gated)
        val rawStore = EndpointStore(raw)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val vault = CountingVault(delegate)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = VerdictPolicy(Verdict.ALLOW),
            buildProvider = { _, _ -> error("no provider needed") as LlmProvider },
        )
        return GatedHarness(raw, gated, store, rawStore, vault, vm)
    }

    private suspend fun awaitGate(d: kotlinx.coroutines.CompletableDeferred<Unit>) {
        withTimeout(10_000) { d.await() }
    }

    private fun startupSettledSnapshot(vm: SetupViewModel): Any? {
        val lockF = SetupViewModel::class.java.getDeclaredField("startupLock")
        lockF.isAccessible = true
        val lock = checkNotNull(lockF.get(vm))
        val settledF = SetupViewModel::class.java.getDeclaredField("startupSettled")
        settledF.isAccessible = true
        synchronized(lock) { return settledF.get(vm) }
    }

    private fun settleStartup(vm: SetupViewModel) {
        runBlocking {
            withTimeout(10_000) {
                vm.launchPendingDeleteReconcile { _, _ -> }.join()
            }
        }
        assertTrue("startup must settle before S5 assert", startupSettledSnapshot(vm) != null)
    }

    private fun preseed(h: GatedHarness, model: String = "m-old") {
        runBlocking {
            h.vault.putKey("preset:openai", "stored-key-12345".toCharArray())
            h.store.save(openAiConfig(model))
        }
    }

    @Test
    fun casThrowsCancelAfterCommitReportsSuccessAndInvalidates() = runBlocking {
        val h = newGatedHarness()
        preseed(h)
        settleStartup(h.vm)
        h.gated.commitThenGate = true
        h.vm.prefillForEdit(openAiConfig("m-old"))
        h.vm.onModelChange("m-new")
        h.vm.save()
        awaitGate(h.gated.entered)
        val job = h.vm.lastSaveJobForTest ?: error("no save job")
        job.cancel()
        job.join()
        // Ambiguous Cancel after commit: probe true -> swallow to success.
        assertFalse(h.vm.form.value.saving)
        assertNull(h.vm.form.value.errorCode)
        assertTrue(h.vm.form.value.saved)
        assertEquals("m-new", h.vm.form.value.model)
        assertEquals(2L, h.store.currentRevision())
        val live = h.store.snapshotStrict()
        assertEquals("provider_key/preset:openai", live?.apiKeyRef)
        assertEquals("m-new", live?.model)
        // KeepExisting metadata-only: preseed put only, zero save-time writes.
        // Preseed used the same counting vault (1 put); the save adds none.
        assertEquals(1, h.vault.putKeys)
        assertEquals(0, h.vault.getKeys)
        assertEquals(0, h.vault.deleteKeys)
        assertEquals(1, h.vault.hasKeys)
        // Invalidate took effect: settled cache dropped.
        assertNull(startupSettledSnapshot(h.vm))
    }

    @Test
    fun casThrowsCancelBeforeCommitRethrowsWithStoreUnchanged() = runBlocking {
        val h = newGatedHarness()
        preseed(h)
        val beforeMeta = h.store.observe().first()
        val beforeRev = h.store.currentRevision()
        h.gated.gateBeforeUpdate = true
        h.vm.prefillForEdit(openAiConfig("m-old"))
        h.vm.onModelChange("m-new")
        h.vm.save()
        awaitGate(h.gated.entered)
        val job = h.vm.lastSaveJobForTest ?: error("no save job")
        job.cancel()
        job.join()
        assertFalse(h.vm.form.value.saving)
        assertNull(h.vm.form.value.errorCode)
        assertFalse(h.vm.form.value.saved)
        assertTrue(job.isCancelled)
        assertEquals(beforeMeta, h.store.observe().first())
        assertEquals(beforeRev, h.store.currentRevision())
        assertEquals(1L, h.store.currentRevision())
        assertEquals("m-old", h.store.snapshotStrict()?.model)
        // Preseed put only; the cancelled save adds no vault writes.
        assertEquals(1, h.vault.putKeys)
        assertEquals(0, h.vault.deleteKeys)
    }

    @Test
    fun concurrentWinnerPlusCancelNeverReportsSuccess() = runBlocking {
        val h = newGatedHarness()
        preseed(h)
        settleStartup(h.vm)
        h.gated.gateBeforeUpdate = true
        h.vm.prefillForEdit(openAiConfig("m-old"))
        h.vm.onModelChange("m-loser")
        h.vm.save()
        awaitGate(h.gated.entered)
        // Winner commits directly via the raw store (same slot, different model).
        val winnerWrote = h.rawStore.saveIfRevision(openAiConfig("m-winner"), 1L)
        assertTrue(winnerWrote)
        assertEquals(2L, h.rawStore.currentRevision())
        val job = h.vm.lastSaveJobForTest ?: error("no save job")
        job.cancel()
        job.join()
        // Revision advanced but config mismatched: must rethrow, never saved.
        assertFalse(h.vm.form.value.saving)
        assertNull(h.vm.form.value.errorCode)
        assertFalse(h.vm.form.value.saved)
        assertTrue(job.isCancelled)
        assertEquals(2L, h.store.currentRevision())
        assertEquals("m-winner", h.store.snapshotStrict()?.model)
        assertEquals("provider_key/preset:openai", h.store.snapshotStrict()?.apiKeyRef)
        // Generation moved: ambiguous path invalidates the settled cache.
        assertNull(startupSettledSnapshot(h.vm))
    }

    @Test
    fun journalOverCommitRethrowsWithoutSaved() = runBlocking {
        val h = newGatedHarness()
        preseed(h)
        h.gated.commitThenGate = true
        h.vm.prefillForEdit(openAiConfig("m-old"))
        h.vm.onModelChange("m-new")
        h.vm.save()
        awaitGate(h.gated.entered)
        // Commit already won (rev 2); teardown journals over it before the probe.
        val journalWrote = h.rawStore.writePendingDeleteIfAbsent(
            PendingDelete(alias = "preset:openai", revision = 2L, timestampMs = 1700000000000L),
        )
        assertTrue(journalWrote)
        val job = h.vm.lastSaveJobForTest ?: error("no save job")
        job.cancel()
        job.join()
        assertFalse(h.vm.form.value.saving)
        assertNull(h.vm.form.value.errorCode)
        assertFalse(h.vm.form.value.saved)
        assertTrue(job.isCancelled)
        // Journal kept, commit still at rev 2 but never reported as success.
        assertEquals("preset:openai", h.rawStore.pendingDelete()?.alias)
        assertEquals(2L, h.store.currentRevision())
    }

    @Test
    fun probeReadFailureMapsToNullThenRethrow() = runBlocking {
        val dir = Files.createTempDirectory("setup-s5-readfail").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val raw: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "s5-readfail.preferences_pb") },
        )
        val failing = S5ProbeFailingDataStore(raw)
        val gated = GatedUpdateDataStore(failing)
        val store = EndpointStore(gated)
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = VerdictPolicy(Verdict.ALLOW),
            buildProvider = { _, _ -> error("no provider needed") as LlmProvider },
        )
        runBlocking {
            vault.putKey("preset:openai", "stored-key-12345".toCharArray())
            store.save(openAiConfig("m-old"))
        }
        gated.commitThenGate = true
        vm.prefillForEdit(openAiConfig("m-old"))
        vm.onModelChange("m-new")
        vm.save()
        withTimeout(10_000) { gated.entered.await() }
        // Fail the probe read only (CAS already committed underneath).
        failing.failReads = true
        val job = vm.lastSaveJobForTest ?: error("no save job")
        job.cancel()
        job.join()
        failing.failReads = false
        assertFalse(vm.form.value.saving)
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertTrue(job.isCancelled)
        assertEquals(2L, store.currentRevision())
        assertEquals("m-new", store.snapshotStrict()?.model)
    }

    @Test
    fun malformedJournalMapsToNullThenRethrow() = runBlocking {
        val h = newGatedHarness()
        preseed(h)
        h.gated.commitThenGate = true
        h.vm.prefillForEdit(openAiConfig("m-old"))
        h.vm.onModelChange("m-new")
        h.vm.save()
        awaitGate(h.gated.entered)
        // Malformed journal over the commit: strict read throws -> probe null.
        h.raw.edit { prefs -> prefs[ENDPOINT_PENDING_DELETE_V1] = "malformed!!!" }
        val job = h.vm.lastSaveJobForTest ?: error("no save job")
        job.cancel()
        job.join()
        assertFalse(h.vm.form.value.saving)
        assertNull(h.vm.form.value.errorCode)
        assertFalse(h.vm.form.value.saved)
        assertTrue(job.isCancelled)
        assertEquals(2L, h.store.currentRevision())
        // Malformed journal kept (strict read still throws fail-closed).
        var malformedSeen = false
        try {
            h.rawStore.snapshotDeleteStateStrict()
        } catch (_: IllegalStateException) {
            malformedSeen = true
        }
        assertTrue(malformedSeen)
    }

    @Test
    fun addExactOverflowNeverThrowsOutward() = runBlocking {
        val h = newGatedHarness()
        preseed(h)
        // Pin the live revision at the addExact boundary.
        h.raw.edit { prefs -> prefs[ENDPOINT_CONFIG_REVISION_V1] = Long.MAX_VALUE }
        assertEquals(Long.MAX_VALUE, h.store.currentRevision())
        h.gated.gateBeforeUpdate = true
        h.vm.prefillForEdit(openAiConfig("m-old"))
        h.vm.onModelChange("m-new")
        h.vm.save()
        awaitGate(h.gated.entered)
        val job = h.vm.lastSaveJobForTest ?: error("no save job")
        var completion: Throwable? = null
        job.invokeOnCompletion { completion = it }
        job.cancel()
        job.join()
        // Probe overflow -> null -> rethrow Cancel (never ArithmeticException).
        assertFalse(h.vm.form.value.saving)
        assertNull(h.vm.form.value.errorCode)
        assertFalse(h.vm.form.value.saved)
        assertTrue(job.isCancelled)
        assertTrue(completion is kotlinx.coroutines.CancellationException)
        assertFalse(completion is ArithmeticException)
        assertEquals(Long.MAX_VALUE, h.store.currentRevision())
        assertEquals("m-old", h.store.snapshotStrict()?.model)
    }
}

/** DataStore wrapper failing only `data` reads on demand (probe-failure injection). */
private class S5ProbeFailingDataStore(
    private val delegate: DataStore<Preferences>,
    @Volatile var failReads: Boolean = false,
    private val error: IOException = IOException("S5_PROBE_READ_FAILED"),
) : DataStore<Preferences> {
    override val data: Flow<Preferences>
        get() = flow {
            if (failReads) throw error
            emitAll(delegate.data)
        }
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        delegate.updateData(transform)
}
