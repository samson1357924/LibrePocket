package dev.librepocket.agent.ui.setup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.keystore.KeyVault
import dev.librepocket.keystore.PrefBackend
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
 * Phase 1 logout clear-phase hardening (Re-review #7 Finding 1, short-term half).
 *
 * Covers clear-fault / cancel / ack-loss with deterministic fault injection,
 * mirroring the save-side NonCancellable strict-verify. Bounded: one strict
 * read + at most one same-rev retry, no storm. No KeyVault interface, schema,
 * migration, or vault list API change.
 *
 * Residual (long term, not this phase): journal/GC for crash orphans
 * (vault list API), cross-process DataStore fencing.
 */

/** Read-OK / write-always-fails store: models clear-transport failure. */
class LogoutClearFailingDataStore(
    private val delegate: DataStore<Preferences>,
    /**
     * Leading writes that pass straight to the delegate.
     *
     * Journal-phase tests set this to 1 so the pending-delete journal write
     * succeeds while the trailing metadata clears keep failing — modelling a
     * permanent clear-transport failure with the journal retained.
     */
    var passThroughWrites: Int = 0,
) : DataStore<Preferences> {
    var updateCalls = 0
        private set
    override val data: Flow<Preferences> get() = delegate.data
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        updateCalls++
        if (updateCalls <= passThroughWrites) {
            return delegate.updateData(transform)
        }
        throw IOException("CLEAR_TRANSPORT_FAILED")
    }
}

/** Ack-loss store: commits via delegate, then throws (flush won, ack lost). */
class LogoutAckLossDataStore(
    private val delegate: DataStore<Preferences>,
    /**
     * Leading writes that commit cleanly without the post-commit throw.
     *
     * Journal-phase tests set this to 1 so the pending-delete journal write is
     * clean while the trailing metadata clear exhibits ack-loss.
     */
    var cleanWrites: Int = 0,
) : DataStore<Preferences> {
    var updateCalls = 0
        private set
    override val data: Flow<Preferences> get() = delegate.data
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        updateCalls++
        if (updateCalls <= cleanWrites) {
            return delegate.updateData(transform)
        }
        val result = delegate.updateData(transform)
        throw IOException("ACK_LOSS_AFTER_COMMIT")
    }
}

/**
 * Deferred-flush model of `apply()` memory-vs-durable gap (test model only,
 * prod behavior unchanged).
 *
 * `put`/`remove` touch memory synchronously (like `apply()`'s in-memory
 * cache); [flush] persists to durable; [restart] discards unflushed memory
 * (process kill before the async fsync). [InMemoryPrefs] is synchronous
 * (memory == durable) and masks this gap, which is why this double exists.
 */
class DeferredFlushPrefs : PrefBackend {
    private val memory = LinkedHashMap<String, String>()
    private val durable = LinkedHashMap<String, String>()

    @Synchronized
    override fun get(key: String): String? = memory[key]

    @Synchronized
    override fun put(key: String, value: String) {
        memory[key] = value
    }

    @Synchronized
    override fun remove(key: String) {
        memory.remove(key)
    }

    @Synchronized
    override fun contains(key: String): Boolean = memory.containsKey(key)

    @Synchronized
    fun flush() {
        durable.clear()
        durable.putAll(memory)
    }

    @Synchronized
    fun restart() {
        memory.clear()
        memory.putAll(durable)
    }

    @Synchronized
    fun durableHas(key: String): Boolean = durable.containsKey(key)

    @Synchronized
    fun memoryHas(key: String): Boolean = memory.containsKey(key)
}

private fun logoutClearPreseed(model: String = "m-old"): EndpointConfig = EndpointConfig(
    providerId = "preset:openai",
    presetId = "openai",
    label = "OpenAI",
    baseUrl = "https://api.openai.com/v1",
    protocol = ProviderProtocol.CHAT_COMPLETIONS,
    model = model,
    apiKeyRef = "provider_key/preset:openai",
)

@OptIn(ExperimentalCoroutinesApi::class)
class SetupLogoutClearTest {
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

    private fun rawStore(dirName: String): Pair<EndpointStore, DataStore<Preferences>> {
        val dir = Files.createTempDirectory(dirName).toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val raw = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "logout-clear.preferences_pb") },
        )
        return EndpointStore(raw) to raw
    }

    private val noProvider: (dev.librepocket.provider.ProviderConfig, dev.librepocket.provider.KeyProvider) -> LlmProvider =
        { _, _ -> error("no provider needed") as LlmProvider }

    private fun awaitLogoutDone(done: CompletableDeferred<Boolean>): Boolean =
        runBlocking { withTimeout(10_000) { done.await() } }

    private fun awaitSavingSettled(vm: SetupViewModel, timeoutMs: Long = 8000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (vm.form.value.saving) {
            if (System.currentTimeMillis() > end) error("timed out waiting for save")
            Thread.sleep(10)
        }
    }

    @Test
    fun logoutClearTransportFailure() {
        // Read OK + journal write passes + clears always throw: delete wins,
        // the initial clear and one bounded same-rev retry both fail, then
        // CLEAR_FAILED with the journal RETAINED (folded readers: metadata
        // hidden, NO_KEY), the key explicitly deleted, form preserved, no
        // success. The next startup/logout replay completes the teardown.
        val (realStore, raw) = rawStore("setup-logout-clear-fail")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val vault = CountingVault(delegate)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            realStore.save(logoutClearPreseed())
            assertEquals(1L, realStore.currentRevision())
        }
        val revBefore = runBlocking { realStore.currentRevision() }
        val failing = LogoutClearFailingDataStore(raw, passThroughWrites = 1)
        val store = EndpointStore(failing)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        val typedKey = "typed-form-key-retain-abcdef"
        vm.onApiKeyChange(typedKey)
        val done = CompletableDeferred<Boolean>()
        vm.logout { done.complete(it) }
        val ok = awaitLogoutDone(done)
        assertFalse("clear transport failure must not report success", ok)
        assertEquals("LOGOUT_CLEAR_FAILED", vm.form.value.errorCode)
        // Bounded: journal write + initial clear + exactly one retry, no storm.
        assertEquals("bounded single retry", 3, failing.updateCalls)
        runBlocking {
            // Journal retained for replay: folded (observe null) but raw
            // metadata still present at the same revision.
            val journal = store.pendingDelete()
            assertEquals("preset:openai", journal?.alias)
            assertEquals(revBefore, journal?.revision)
            assertNull(store.observe().first())
            assertFalse(store.hasMetadata())
            assertEquals("provider_key/preset:openai", store.snapshotStrict()!!.apiKeyRef)
            assertEquals(revBefore, store.currentRevision())
            // Key state explicit: delete succeeded before the clear, so the
            // live bytes are gone while metadata still points at the alias.
            assertFalse(delegate.hasKey("preset:openai"))
            assertEquals(EndpointReadiness.NO_KEY, store.readiness(delegate))
        }
        // Delete attempted exactly once (no second delete on the retry path).
        assertEquals(listOf("preset:openai"), vault.deletedIds)
        // Form preserved (not reset to fresh), retryable semantics.
        assertEquals(typedKey, vm.form.value.apiKey)
    }

    @Test
    fun logoutCancelBetweenDeleteAndClear() {
        // Gate the clear (journal write passes, delete wins, clear parks) +
        // cancel: must rethrow with no code and no onDone. Strict probe
        // classifies FAILED (metadata still present, rev unchanged); no retry
        // on the cancel path. The journal is KEPT: readers stay folded and the
        // next startup/logout replay completes the teardown.
        val dir = Files.createTempDirectory("setup-logout-cancel-clear").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val raw = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "cancel-clear.preferences_pb") },
        )
        // Preseed through the raw delegate so the gate counter below starts
        // clean (update #1 = journal write passes, update #2 = clear parks).
        val preseed = EndpointStore(raw)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val vault = CountingVault(delegate)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            preseed.save(logoutClearPreseed())
            assertEquals(1L, preseed.currentRevision())
        }
        val revBefore = runBlocking { preseed.currentRevision() }
        val gated = GatedUpdateDataStore(raw, gateBeforeUpdate = true, updatesToPass = 1)
        val store = EndpointStore(gated)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        val typedKey = "typed-cancel-form-key-abcdef"
        vm.onApiKeyChange(typedKey)
        var onDoneCalled = false
        vm.logout { onDoneCalled = true }
        runBlocking { withTimeout(10_000) { gated.entered.await() } }
        val job = vm.lastLogoutJobForTest ?: error("no logout job")
        runBlocking {
            job.cancel()
            job.join()
        }
        assertTrue("cancelled logout must end cancelled", job.isCancelled)
        assertNull("cancel must not stamp any errorCode", vm.form.value.errorCode)
        assertFalse("cancel must not call onDone", onDoneCalled)
        runBlocking {
            // Journal kept for replay: folded, but raw metadata still present
            // at the same revision.
            val journal = store.pendingDelete()
            assertEquals("preset:openai", journal?.alias)
            assertEquals(revBefore, journal?.revision)
            assertNull(store.observe().first())
            assertFalse(store.hasMetadata())
            assertEquals("provider_key/preset:openai", store.snapshotStrict()!!.apiKeyRef)
            assertEquals(revBefore, store.currentRevision())
            // Delete already won before the gate; clear never committed.
            assertFalse(delegate.hasKey("preset:openai"))
            assertEquals(EndpointReadiness.NO_KEY, store.readiness(delegate))
        }
        assertEquals(listOf("preset:openai"), vault.deletedIds)
        assertEquals(typedKey, vm.form.value.apiKey)
    }

    @Test
    fun logoutCommittedThenCancelReportsCommitted() {
        // Commit-then-gate + cancel: the clear won before the cancel, so the
        // NonCancellable probe classifies COMMITTED and swallows to success
        // (mirrors save committed-then-cancel). The journal leaves atomically
        // with the clear.
        val dir = Files.createTempDirectory("setup-logout-committed-cancel").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val raw = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "committed-cancel.preferences_pb") },
        )
        // Preseed through the raw delegate so the gate counter below starts
        // clean (update #1 = journal write passes, update #2 = clear commits
        // then parks).
        val preseed = EndpointStore(raw)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val vault = CountingVault(delegate)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            preseed.save(logoutClearPreseed())
            assertEquals(1L, preseed.currentRevision())
        }
        val revBefore = runBlocking { preseed.currentRevision() }
        val gated = GatedUpdateDataStore(raw, commitThenGate = true, updatesToPass = 1)
        val store = EndpointStore(gated)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        val done = CompletableDeferred<Boolean>()
        vm.logout { done.complete(it) }
        runBlocking { withTimeout(10_000) { gated.entered.await() } }
        val job = vm.lastLogoutJobForTest ?: error("no logout job")
        runBlocking {
            job.cancel()
            job.join()
        }
        // Swallowed: completes normally with success despite the cancel.
        val ok = runBlocking { withTimeout(10_000) { done.await() } }
        assertTrue("committed-then-cancel must report success", ok)
        assertNull(vm.form.value.errorCode)
        runBlocking {
            assertFalse(store.hasMetadata())
            assertNull("journal leaves atomically with the clear", store.pendingDelete())
            assertEquals((revBefore ?: 0L) + 1L, store.currentRevision())
            assertFalse(delegate.hasKey("preset:openai"))
        }
        assertEquals(listOf("preset:openai"), vault.deletedIds)
        // Success resets the form (mirrors save committed path).
        assertEquals("", vm.form.value.apiKey)
    }

    @Test
    fun logoutAckLossReportsCommitted() {
        // Ack-loss: updateData commits then throws (non-cancel). The strict
        // re-read must classify COMMITTED and report success — never a blanket
        // CLEAR_FAILED.
        val (realStore, raw) = rawStore("setup-logout-ack-loss")
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val vault = CountingVault(delegate)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            realStore.save(logoutClearPreseed())
            assertEquals(1L, realStore.currentRevision())
        }
        val revBefore = runBlocking { realStore.currentRevision() }
        // Journal write commits cleanly; only the trailing clear is ack-loss.
        val ackLoss = LogoutAckLossDataStore(raw, cleanWrites = 1)
        val store = EndpointStore(ackLoss)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        val done = CompletableDeferred<Boolean>()
        vm.logout { done.complete(it) }
        val ok = awaitLogoutDone(done)
        assertTrue("ack-loss after commit must report success, not CLEAR_FAILED", ok)
        assertNull(vm.form.value.errorCode)
        // No retry for COMMITTED: journal write + exactly the initial clear.
        assertEquals(2, ackLoss.updateCalls)
        runBlocking {
            assertFalse(store.hasMetadata())
            assertNull("journal leaves atomically with the clear", store.pendingDelete())
            assertEquals((revBefore ?: 0L) + 1L, store.currentRevision())
            assertFalse(delegate.hasKey("preset:openai"))
        }
        assertEquals(listOf("preset:openai"), vault.deletedIds)
        assertEquals("", vm.form.value.apiKey)
    }

    @Test
    fun saveUnflushedPutThenRestartYieldsNoKey() {
        // Durability-gap model case 1 (test-model demonstration, prod unchanged):
        // staged put stays in apply-memory while the metadata CAS is durable.
        // A restart before flush loses the key bytes -> metadata points at a
        // missing alias (NO_KEY). InMemoryPrefs masks this (synchronous).
        val (store, _) = rawStore("setup-deferred-save")
        val prefs = DeferredFlushPrefs()
        val vault = EncryptedPrefsVault(prefs)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        vm.onApiKeyChange("unit-test-save-unflushed-key-123456")
        vm.save()
        awaitSavingSettled(vm)
        assertTrue(vm.form.value.saved)
        val ref = runBlocking { store.observe().first()!!.apiKeyRef }
        val staged = SetupValidation.aliasFromRef(ref)
        assertTrue(staged.isNotBlank())
        // Memory has it, durable does not (no flush yet).
        runBlocking { assertTrue(vault.hasKey(staged)) }
        assertTrue(prefs.memoryHas(EncryptedPrefsVault.prefsKey(staged)))
        assertFalse(prefs.durableHas(EncryptedPrefsVault.prefsKey(staged)))
        // Crash/restart before the apply flush.
        prefs.restart()
        runBlocking {
            assertTrue("metadata is durable across the restart", store.hasMetadata())
            assertFalse("unflushed key bytes are lost on restart", vault.hasKey(staged))
            assertEquals(EndpointReadiness.NO_KEY, store.readiness(vault))
        }
    }

    @Test
    fun logoutUnflushedDeleteThenRestartRevivesOrphan() {
        // Durability-gap model case 2 (test-model demonstration, prod unchanged):
        // delete stays in apply-memory while the metadata clear is durable.
        // A restart before flush revives the key bytes as an unreferenced
        // orphan (metadata gone, hasKey true). InMemoryPrefs masks this.
        val (store, _) = rawStore("setup-deferred-logout")
        val prefs = DeferredFlushPrefs()
        val vault = EncryptedPrefsVault(prefs)
        runBlocking {
            vault.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
        }
        prefs.flush()
        runBlocking {
            store.save(logoutClearPreseed())
            assertEquals(1L, store.currentRevision())
            assertTrue(vault.hasKey("preset:openai"))
        }
        val revBefore = runBlocking { store.currentRevision() }
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        val done = CompletableDeferred<Boolean>()
        vm.logout { done.complete(it) }
        val ok = awaitLogoutDone(done)
        assertTrue(ok)
        assertNull(vm.form.value.errorCode)
        // In-process: memory deleted, metadata cleared. Durable still holds
        // the deleted bytes (delete unflushed).
        runBlocking { assertFalse(vault.hasKey("preset:openai")) }
        assertFalse(prefs.memoryHas(EncryptedPrefsVault.prefsKey("preset:openai")))
        assertTrue(prefs.durableHas(EncryptedPrefsVault.prefsKey("preset:openai")))
        runBlocking {
            assertFalse(store.hasMetadata())
            assertEquals((revBefore ?: 0L) + 1L, store.currentRevision())
        }
        // Crash/restart before the apply flush revives the orphan.
        prefs.restart()
        runBlocking {
            assertFalse("metadata stays cleared across the restart", store.hasMetadata())
            assertTrue("unflushed delete revives the key as an orphan", vault.hasKey("preset:openai"))
            assertEquals(EndpointReadiness.NO_ENDPOINT_METADATA, store.readiness(vault))
        }
    }
}
