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
 * Honest-durable model backend (Stage R1).
 *
 * `putDurable`/`removeDurable` persist to both memory and durable synchronously
 * and report `true` — the test-model equivalent of the production `commit()`
 * path. [flush] persists memory→durable (no-op when in sync, kept for
 * restart-simulation call sites); [restart] drops memory for durable
 * (process-kill simulation: with an honest backend nothing is lost).
 * The pre-R1 `apply()`-gap demonstration (memory-only write + restart loss)
 * is intentionally gone: production no longer has that path.
 */
class DeferredFlushPrefs : PrefBackend {
    private val memory = LinkedHashMap<String, String>()
    private val durable = LinkedHashMap<String, String>()

    @Synchronized
    override fun get(key: String): String? = memory[key]

    @Synchronized
    override fun putDurable(key: String, value: String): Boolean {
        memory[key] = value
        durable[key] = value
        return true
    }

    @Synchronized
    override fun removeDurable(key: String): Boolean {
        memory.remove(key)
        durable.remove(key)
        return true
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

/**
 * Durability-failing backend (Stage R1 fault injection): `putDurable`/
 * `removeDurable` always report `false` (fsync refused). Production must fail
 * closed — save publishes nothing, logout keeps the journal. Seeded entries
 * model pre-existing durable state.
 */
class DurabilityFailingPrefs : PrefBackend {
    private val durable = LinkedHashMap<String, String>()
    var putAttempts = 0
        private set
    var removeAttempts = 0
        private set

    @Synchronized
    fun seed(key: String, value: String) {
        durable[key] = value
    }

    @Synchronized
    override fun get(key: String): String? = durable[key]

    @Synchronized
    override fun putDurable(key: String, value: String): Boolean {
        putAttempts++
        return false
    }

    @Synchronized
    override fun removeDurable(key: String): Boolean {
        removeAttempts++
        return false
    }

    @Synchronized
    override fun contains(key: String): Boolean = durable.containsKey(key)
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
    fun saveDurablePutThenRestartStaysReady() {
        // Stage R1: the vault put is durable-ACKed before the metadata CAS, so
        // a restart after save keeps both sides: metadata points at a present
        // alias (READY). The pre-R1 apply-gap loss (unflushed put + durable
        // CAS = NO_KEY after restart) cannot happen on this path.
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
        // Durable on both layers before the CAS reported success.
        runBlocking { assertTrue(vault.hasKey(staged)) }
        assertTrue(prefs.memoryHas(EncryptedPrefsVault.prefsKey(staged)))
        assertTrue(prefs.durableHas(EncryptedPrefsVault.prefsKey(staged)))
        // Crash/restart: nothing is lost.
        prefs.restart()
        runBlocking {
            assertTrue("metadata is durable across the restart", store.hasMetadata())
            assertTrue("durable key bytes survive the restart", vault.hasKey(staged))
            assertEquals(EndpointReadiness.READY, store.readiness(vault))
        }
    }

    @Test
    fun saveDurabilityFalseAbortsWithoutMetadata() {
        // Stage R1 N1: backend refuses durability (fsync false) — the save must
        // fail closed with zero publish (no metadata), never saved=true.
        val (store, _) = rawStore("setup-durability-false-save")
        val prefs = DurabilityFailingPrefs()
        val delegate = EncryptedPrefsVault(prefs)
        val vault = CountingVault(delegate)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        vm.onApiKeyChange("unit-test-save-key-123456")
        vm.save()
        awaitSavingSettled(vm)
        assertEquals("POLICY_DENIED_KEY_WRITE", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertEquals(1, prefs.putAttempts)
        runBlocking {
            assertFalse("refused put must publish no metadata", store.hasMetadata())
        }
    }

    @Test
    fun saveDurabilityThrowAbortsWithoutMetadata() {
        // Stage R1: backend throws on write — same fail-closed branch as a
        // `false` ACK (cause chained for observability).
        val (store, _) = rawStore("setup-durability-throw-save")
        val throwing = object : PrefBackend {
            override fun get(key: String): String? = null
            override fun putDurable(key: String, value: String): Boolean =
                throw java.io.IOException("FSYNC_THROW")
            override fun removeDurable(key: String): Boolean = true
            override fun contains(key: String): Boolean = false
        }
        val vault = EncryptedPrefsVault(throwing)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        vm.onApiKeyChange("unit-test-save-key-123456")
        vm.save()
        awaitSavingSettled(vm)
        assertEquals("POLICY_DENIED_KEY_WRITE", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        runBlocking {
            assertFalse("thrown write must publish no metadata", store.hasMetadata())
        }
    }

    @Test
    fun logoutDurableDeleteThenRestartStaysTornDown() {
        // Stage R1: the vault delete is durable-ACKed before the fenced clear,
        // so a restart after logout stays torn down (no revived orphan).
        val (store, _) = rawStore("setup-durable-logout")
        val prefs = DeferredFlushPrefs()
        val vault = EncryptedPrefsVault(prefs)
        runBlocking {
            vault.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(logoutClearPreseed())
            assertTrue(vault.hasKey("preset:openai"))
        }
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        val done = CompletableDeferred<Boolean>()
        vm.logout { done.complete(it) }
        assertTrue(awaitLogoutDone(done))
        assertNull(vm.form.value.errorCode)
        prefs.restart()
        runBlocking {
            assertFalse(store.hasMetadata())
            assertFalse("no orphan revives after a durable teardown", vault.hasKey("preset:openai"))
            assertNull("journal finalized with the clear", store.pendingDelete())
        }
    }

    @Test
    fun logoutDurabilityFalseKeepsJournal() {
        // Stage R1 N3: backend refuses durability on delete — logout must fail
        // closed (LOGOUT_DELETE_FAILED) with the journal kept and metadata
        // untouched, never reporting success.
        val (store, _) = rawStore("setup-durability-false-logout")
        val prefs = DurabilityFailingPrefs()
        prefs.seed(EncryptedPrefsVault.prefsKey("preset:openai"), "unit-test-old-key-123456")
        val vault = EncryptedPrefsVault(prefs)
        runBlocking {
            store.save(logoutClearPreseed())
            assertEquals(1L, store.currentRevision())
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
        assertEquals(false, awaitLogoutDone(done))
        assertEquals("LOGOUT_DELETE_FAILED", vm.form.value.errorCode)
        assertTrue(vm.form.value.logoutErrorSourceJournal != null)
        assertEquals(1, prefs.removeAttempts)
        runBlocking {
            // Journal present folds lenient readers to null by design — assert
            // the strict shape instead: revision untouched (no clear ran) and
            // the journal kept for replay; the seeded key bytes are untouched.
            assertEquals(revBefore, store.currentRevision())
            assertTrue("journal kept for replay", store.pendingDelete() != null)
            assertTrue(vault.hasKey("preset:openai"))
        }
    }
}
