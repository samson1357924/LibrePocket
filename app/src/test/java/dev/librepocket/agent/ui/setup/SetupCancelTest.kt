package dev.librepocket.agent.ui.setup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.keystore.KeyVault
import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

/** Suspending policy stub: suspends once on [gateOnCall]-th fresh evaluation. */
class SuspendPolicy(
    var verdict: Verdict,
    var gateOnCall: Int? = null,
    val entered: CompletableDeferred<Unit> = CompletableDeferred(),
    val release: CompletableDeferred<Unit> = CompletableDeferred(),
) : PolicyStore {
    var freshCalls = 0
    override fun evaluate(action: String, resource: String) =
        PolicyDecision(verdict, null, System.currentTimeMillis())
    override suspend fun setRule(rule: PolicyRule) = Unit
    override suspend fun removeRule(pattern: String) = Unit
    override suspend fun listRules(): List<PolicyRule> = emptyList()
    override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision {
        freshCalls++
        if (freshCalls == gateOnCall) {
            entered.complete(Unit)
            release.await()
        }
        return PolicyDecision(verdict, null, System.currentTimeMillis())
    }
}

/** Counting vault over a delegate (suspending-safe, no latches). */
class CancelCountingVault(
    val delegate: KeyVault = EncryptedPrefsVault(InMemoryPrefs()),
) : KeyVault {
    var putKeys = 0
    var deleteKeys = 0
    var getKeys = 0
    val putIds = ArrayList<String>()
    val deletedIds = ArrayList<String>()
    override suspend fun putKey(providerId: String, apiKey: CharArray) {
        putKeys++
        putIds.add(providerId)
        delegate.putKey(providerId, apiKey)
    }
    override suspend fun getKey(providerId: String): CharArray? {
        getKeys++
        return delegate.getKey(providerId)
    }
    override suspend fun deleteKey(providerId: String) {
        deleteKeys++
        deletedIds.add(providerId)
        delegate.deleteKey(providerId)
    }
    override suspend fun hasKey(providerId: String): Boolean = delegate.hasKey(providerId)
}

/** Vault gating putKey entry via suspending deferreds. */
class GatedPutVault(
    val delegate: KeyVault = EncryptedPrefsVault(InMemoryPrefs()),
    var gatePut: Boolean = false,
    val putEntered: CompletableDeferred<Unit> = CompletableDeferred(),
    val putRelease: CompletableDeferred<Unit> = CompletableDeferred(),
) : KeyVault {
    var putKeys = 0
    var deleteKeys = 0
    val putIds = ArrayList<String>()
    val deletedIds = ArrayList<String>()
    override suspend fun putKey(providerId: String, apiKey: CharArray) {
        putKeys++
        putIds.add(providerId)
        if (gatePut) {
            putEntered.complete(Unit)
            putRelease.await()
        }
        delegate.putKey(providerId, apiKey)
    }
    override suspend fun getKey(providerId: String): CharArray? = delegate.getKey(providerId)
    override suspend fun deleteKey(providerId: String) {
        deleteKeys++
        deletedIds.add(providerId)
        delegate.deleteKey(providerId)
    }
    override suspend fun hasKey(providerId: String): Boolean = delegate.hasKey(providerId)
}

/** Vault gating deleteKey entry via suspending deferreds (retire-cancel window). */
class GatedDeleteVault(
    val delegate: KeyVault = EncryptedPrefsVault(InMemoryPrefs()),
    var gateDelete: Boolean = false,
    val deleteEntered: CompletableDeferred<Unit> = CompletableDeferred(),
    val deleteRelease: CompletableDeferred<Unit> = CompletableDeferred(),
) : KeyVault {
    var putKeys = 0
    var deleteKeys = 0
    val putIds = ArrayList<String>()
    val deletedIds = ArrayList<String>()
    override suspend fun putKey(providerId: String, apiKey: CharArray) {
        putKeys++
        putIds.add(providerId)
        delegate.putKey(providerId, apiKey)
    }
    override suspend fun getKey(providerId: String): CharArray? = delegate.getKey(providerId)
    override suspend fun deleteKey(providerId: String) {
        deleteKeys++
        deletedIds.add(providerId)
        if (gateDelete) {
            deleteEntered.complete(Unit)
            deleteRelease.await()
        }
        delegate.deleteKey(providerId)
    }
    override suspend fun hasKey(providerId: String): Boolean = delegate.hasKey(providerId)
}

/** DataStore wrapper gating updateData (CAS) with suspending deferreds. */
class GatedUpdateDataStore(
    private val delegate: DataStore<Preferences>,
    var gateBeforeUpdate: Boolean = false,
    var commitThenGate: Boolean = false,
    val entered: CompletableDeferred<Unit> = CompletableDeferred(),
    val release: CompletableDeferred<Unit> = CompletableDeferred(),
) : DataStore<Preferences> {
    override val data: Flow<Preferences> get() = delegate.data
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        if (commitThenGate) {
            val result = delegate.updateData(transform)
            entered.complete(Unit)
            release.await()
            return result
        }
        if (gateBeforeUpdate) {
            entered.complete(Unit)
            release.await()
        }
        return delegate.updateData(transform)
    }
}

private fun cancelAssertVaultKeyEquals(vault: KeyVault, alias: String, expected: String) {
    val e = expected.toCharArray()
    try {
        val a = runBlocking { vault.getKey(alias) }
        try {
            assertTrue("vault key mismatch for $alias", a != null && a.contentEquals(e))
        } finally {
            a?.fill('\u0000')
        }
    } finally {
        e.fill('\u0000')
    }
}

private fun cancelPreseed(model: String = "m-old"): EndpointConfig = EndpointConfig(
    providerId = "preset:openai",
    presetId = "openai",
    label = "OpenAI",
    baseUrl = "https://api.openai.com/v1",
    protocol = ProviderProtocol.CHAT_COMPLETIONS,
    model = model,
    apiKeyRef = "provider_key/preset:openai",
)

@OptIn(ExperimentalCoroutinesApi::class)
class SetupCancelTest {
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

    private fun newVm(
        policy: PolicyStore,
        vault: KeyVault,
        gated: GatedUpdateDataStore? = null,
        realStore: EndpointStore? = null,
    ): Triple<SetupViewModel, KeyVault, EndpointStore> {
        val dir = Files.createTempDirectory("setup-cancel-test").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val raw = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "cancel.preferences_pb") },
        )
        val store = if (gated != null) {
            error("use newVmGated for gated stores")
        } else if (realStore != null) {
            realStore
        } else {
            EndpointStore(raw)
        }
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = policy,
            buildProvider = { _, _ -> error("no provider needed") as LlmProvider },
        )
        return Triple(vm, vault, store)
    }

    private fun newVmGated(
        policy: PolicyStore,
        vault: KeyVault,
    ): Triple<SetupViewModel, KeyVault, Pair<EndpointStore, GatedUpdateDataStore>> {
        val dir = Files.createTempDirectory("setup-cancel-gated").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val raw = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "cancel-gated.preferences_pb") },
        )
        val gated = GatedUpdateDataStore(raw)
        val store = EndpointStore(gated)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = policy,
            buildProvider = { _, _ -> error("no provider needed") as LlmProvider },
        )
        return Triple(vm, vault, store to gated)
    }

    private suspend fun awaitGate(d: CompletableDeferred<Unit>) {
        withTimeout(10_000) { d.await() }
    }

    private suspend fun awaitSaving(vm: SetupViewModel) {
        withTimeout(10_000) {
            while (!vm.form.value.saving) delay(5)
        }
    }

    private suspend fun awaitSettledSave(vm: SetupViewModel) {
        withTimeout(10_000) {
            while (vm.form.value.saving) delay(5)
        }
    }

    // ---- T1: cancel before put (gate V1) ----

    @Test
    fun t1FreshPutBeforeCancel() = runBlocking {
        val policy = SuspendPolicy(Verdict.ALLOW, gateOnCall = 1)
        val vault = CancelCountingVault()
        val (vm, _, store) = newVm(policy, vault)
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        awaitGate(policy.entered)
        assertTrue(vm.form.value.saving)
        val job = vm.lastSaveJobForTest ?: error("no save job")
        job.cancel()
        job.join()
        assertFalse(vm.form.value.saving)
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertEquals(0, vault.putKeys)
        assertEquals(0, vault.deleteKeys)
        assertTrue(vault.deletedIds.isEmpty())
        assertFalse(store.hasMetadata())
        assertEquals(EndpointReadiness.NO_ENDPOINT_METADATA, store.readiness(vault))
    }

    @Test
    fun t1RotationPutBeforeCancel() = runBlocking {
        val policy = SuspendPolicy(Verdict.ALLOW, gateOnCall = 1)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val vault = CancelCountingVault(delegate)
        val (vm, _, store) = newVm(policy, vault)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(cancelPreseed())
        }
        val beforeMeta = runBlocking { store.observe().first() }
        val beforeRev = runBlocking { store.currentRevision() }
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        awaitGate(policy.entered)
        val job = vm.lastSaveJobForTest ?: error("no save job")
        job.cancel()
        job.join()
        assertFalse(vm.form.value.saving)
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertEquals(0, vault.putKeys)
        assertEquals(0, vault.deleteKeys)
        assertTrue(vault.deletedIds.isEmpty())
        cancelAssertVaultKeyEquals(delegate, "preset:openai", "unit-test-old-key-123456")
        assertEquals(beforeMeta, runBlocking { store.observe().first() })
        assertEquals(beforeRev, runBlocking { store.currentRevision() })
        assertEquals(EndpointReadiness.READY, runBlocking { store.readiness(delegate) })
    }

    // ---- T1b: cancel during put (gate putKey) — exercises GatedPutVault ----

    @Test
    fun t1FreshPutDuringCancel() = runBlocking {
        val policy = SuspendPolicy(Verdict.ALLOW)
        val vault = GatedPutVault(gatePut = true)
        val (vm, _, store) = newVm(policy, vault)
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        awaitGate(vault.putEntered)
        assertTrue(vm.form.value.saving)
        assertEquals(1, vault.putKeys)
        val staged = vault.putIds.single()
        val job = vm.lastSaveJobForTest ?: error("no save job")
        job.cancel()
        job.join()
        assertFalse(vm.form.value.saving)
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertEquals(1, vault.deleteKeys)
        assertEquals(listOf(staged), vault.deletedIds)
        assertFalse(vault.hasKey(staged))
        assertFalse(store.hasMetadata())
        assertEquals(EndpointReadiness.NO_ENDPOINT_METADATA, store.readiness(vault))
    }

    @Test
    fun t1RotationPutDuringCancel() = runBlocking {
        val policy = SuspendPolicy(Verdict.ALLOW)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val vault = GatedPutVault(delegate = delegate, gatePut = true)
        val (vm, _, store) = newVm(policy, vault)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(cancelPreseed())
        }
        val beforeMeta = runBlocking { store.observe().first() }
        val beforeRev = runBlocking { store.currentRevision() }
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        awaitGate(vault.putEntered)
        assertTrue(vm.form.value.saving)
        assertEquals(1, vault.putKeys)
        val staged = vault.putIds.single()
        val job = vm.lastSaveJobForTest ?: error("no save job")
        job.cancel()
        job.join()
        assertFalse(vm.form.value.saving)
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertEquals(1, vault.deleteKeys)
        assertEquals(listOf(staged), vault.deletedIds)
        assertFalse(delegate.hasKey(staged))
        cancelAssertVaultKeyEquals(delegate, "preset:openai", "unit-test-old-key-123456")
        assertEquals(beforeMeta, runBlocking { store.observe().first() })
        assertEquals(beforeRev, runBlocking { store.currentRevision() })
        assertEquals(EndpointReadiness.READY, runBlocking { store.readiness(delegate) })
    }

    // ---- T2: cancel after put, during V2 (core) ----

    @Test
    fun t2FreshPutAfterV2BeforeCancel() = runBlocking {
        val policy = SuspendPolicy(Verdict.ALLOW, gateOnCall = 2)
        val vault = CancelCountingVault()
        val (vm, _, store) = newVm(policy, vault)
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        awaitGate(policy.entered)
        assertEquals(1, vault.putKeys)
        val staged = vault.putIds.single()
        val job = vm.lastSaveJobForTest ?: error("no save job")
        job.cancel()
        job.join()
        assertFalse(vm.form.value.saving)
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertEquals(2, policy.freshCalls)
        assertEquals(1, vault.deleteKeys)
        assertEquals(listOf(staged), vault.deletedIds)
        assertFalse(runBlocking { vault.hasKey(staged) })
        assertFalse(store.hasMetadata())
        assertEquals(EndpointReadiness.NO_ENDPOINT_METADATA, store.readiness(vault))
    }

    @Test
    fun t2RotationPutAfterV2BeforeCancel() = runBlocking {
        val policy = SuspendPolicy(Verdict.ALLOW, gateOnCall = 2)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val vault = CancelCountingVault(delegate)
        val (vm, _, store) = newVm(policy, vault)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(cancelPreseed())
        }
        val beforeMeta = runBlocking { store.observe().first() }
        val beforeRev = runBlocking { store.currentRevision() }
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        awaitGate(policy.entered)
        assertEquals(1, vault.putKeys)
        val staged = vault.putIds.single()
        val job = vm.lastSaveJobForTest ?: error("no save job")
        job.cancel()
        job.join()
        assertFalse(vm.form.value.saving)
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertEquals(1, vault.deleteKeys)
        assertEquals(listOf(staged), vault.deletedIds)
        assertFalse(runBlocking { delegate.hasKey(staged) })
        cancelAssertVaultKeyEquals(delegate, "preset:openai", "unit-test-old-key-123456")
        assertEquals(beforeMeta, runBlocking { store.observe().first() })
        assertEquals(beforeRev, runBlocking { store.currentRevision() })
        assertEquals(EndpointReadiness.READY, runBlocking { store.readiness(delegate) })
    }

    // ---- T3: cancel around CAS ----

    @Test
    fun t3FreshCasBeforeCancelCleansStaged() = runBlocking {
        val policy = SuspendPolicy(Verdict.ALLOW)
        val vault = CancelCountingVault()
        val (vm, _, pair) = newVmGated(policy, vault)
        val store = pair.first
        val gated = pair.second
        gated.gateBeforeUpdate = true
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        awaitGate(gated.entered)
        assertEquals(1, vault.putKeys)
        val staged = vault.putIds.single()
        val job = vm.lastSaveJobForTest ?: error("no save job")
        job.cancel()
        job.join()
        assertFalse(vm.form.value.saving)
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertEquals(1, vault.deleteKeys)
        assertEquals(listOf(staged), vault.deletedIds)
        assertFalse(runBlocking { vault.hasKey(staged) })
        assertFalse(store.hasMetadata())
    }

    @Test
    fun t3RotationCasBeforeCancelKeepsOld() = runBlocking {
        val policy = SuspendPolicy(Verdict.ALLOW)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val vault = CancelCountingVault(delegate)
        val (vm, _, pair) = newVmGated(policy, vault)
        val store = pair.first
        val gated = pair.second
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(cancelPreseed())
        }
        val beforeMeta = runBlocking { store.observe().first() }
        gated.gateBeforeUpdate = true
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        awaitGate(gated.entered)
        val staged = vault.putIds.single()
        val job = vm.lastSaveJobForTest ?: error("no save job")
        job.cancel()
        job.join()
        assertFalse(vm.form.value.saving)
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertEquals(listOf(staged), vault.deletedIds)
        assertFalse(runBlocking { delegate.hasKey(staged) })
        cancelAssertVaultKeyEquals(delegate, "preset:openai", "unit-test-old-key-123456")
        assertEquals(beforeMeta, runBlocking { store.observe().first() })
        assertEquals(EndpointReadiness.READY, runBlocking { store.readiness(delegate) })
    }

    @Test
    fun t3FreshCommittedCancelReportsSuccess() = runBlocking {
        val policy = SuspendPolicy(Verdict.ALLOW)
        val vault = CancelCountingVault()
        val (vm, _, pair) = newVmGated(policy, vault)
        val store = pair.first
        val gated = pair.second
        gated.commitThenGate = true
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        awaitGate(gated.entered)
        val staged = vault.putIds.single()
        val job = vm.lastSaveJobForTest ?: error("no save job")
        job.cancel()
        job.join()
        assertFalse(vm.form.value.saving)
        assertNull(vm.form.value.errorCode)
        assertTrue(vm.form.value.saved)
        assertTrue(store.hasMetadata())
        assertEquals(1L, runBlocking { store.currentRevision() })
        val ref = runBlocking { store.observe().first()!!.apiKeyRef }
        assertEquals("provider_key/$staged", ref)
        cancelAssertVaultKeyEquals(vault, staged, "unit-test-new-key-abcdef")
        assertEquals(EndpointReadiness.READY, store.readiness(vault))
        assertFalse(vault.deletedIds.contains(staged))
    }

    @Test
    fun t3RotationCommittedCancelReportsSuccess() = runBlocking {
        val policy = SuspendPolicy(Verdict.ALLOW)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val vault = CancelCountingVault(delegate)
        val (vm, _, pair) = newVmGated(policy, vault)
        val store = pair.first
        val gated = pair.second
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(cancelPreseed())
        }
        gated.commitThenGate = true
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        awaitGate(gated.entered)
        val staged = vault.putIds.single()
        val job = vm.lastSaveJobForTest ?: error("no save job")
        job.cancel()
        job.join()
        assertFalse(vm.form.value.saving)
        assertNull(vm.form.value.errorCode)
        assertTrue(vm.form.value.saved)
        assertEquals(2L, runBlocking { store.currentRevision() })
        val ref = runBlocking { store.observe().first()!!.apiKeyRef }
        assertEquals("provider_key/$staged", ref)
        cancelAssertVaultKeyEquals(delegate, staged, "unit-test-new-key-abcdef")
        assertEquals(EndpointReadiness.READY, runBlocking { store.readiness(delegate) })
        assertFalse(vault.deletedIds.contains(staged))
    }

    // ---- T3b: cancel during retire (metadataCommitted==true) reports success ----
    // Rotation-only: fresh has no oldAlias, hence no retire window to gate.

    @Test
    fun t3RetireCancelReportsSuccess() = runBlocking {
        val policy = SuspendPolicy(Verdict.ALLOW)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val vault = GatedDeleteVault(delegate = delegate, gateDelete = true)
        val (vm, _, store) = newVm(policy, vault)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(cancelPreseed())
        }
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        // Retire entered => CAS already returned true (metadataCommitted==true).
        awaitGate(vault.deleteEntered)
        assertTrue(vm.form.value.saving)
        val staged = vault.putIds.single()
        val job = vm.lastSaveJobForTest ?: error("no save job")
        job.cancel()
        job.join()
        // Committed cancel swallows to success; staged (now live) is never deleted.
        assertFalse(vm.form.value.saving)
        assertNull(vm.form.value.errorCode)
        assertTrue(vm.form.value.saved)
        assertFalse(vault.deletedIds.contains(staged))
        assertEquals(listOf("preset:openai"), vault.deletedIds)
        cancelAssertVaultKeyEquals(delegate, staged, "unit-test-new-key-abcdef")
        assertEquals(2L, runBlocking { store.currentRevision() })
        assertEquals("provider_key/$staged", runBlocking { store.observe().first()!!.apiKeyRef })
        assertEquals(EndpointReadiness.READY, runBlocking { store.readiness(delegate) })
        // Retire swallowed the cancel, so the predecessor leaks at rest
        // (documented orphan class, strictly better than loss).
        assertTrue(runBlocking { delegate.hasKey("preset:openai") })
    }

    // ---- T4: CAS-false winner protection under cancel ----

    @Test
    fun t4LoserCancelWinnerProtectedFresh() = runBlocking {
        val dir = Files.createTempDirectory("setup-cancel-t4").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val raw = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "t4.preferences_pb") },
        )
        val store = EndpointStore(raw)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val policyLoser = SuspendPolicy(Verdict.ALLOW, gateOnCall = 2)
        val policyWinner = SuspendPolicy(Verdict.ALLOW)
        val vaultLoser = CancelCountingVault(delegate)
        val vaultWinner = CancelCountingVault(delegate)
        val noProvider: (dev.librepocket.provider.ProviderConfig, dev.librepocket.provider.KeyProvider) -> LlmProvider =
            { _, _ -> error("no provider needed") as LlmProvider }
        val vmLoser = SetupViewModel(store, VaultSource { vaultLoser }, policyLoser, buildProvider = noProvider)
        val vmWinner = SetupViewModel(store, VaultSource { vaultWinner }, policyWinner, buildProvider = noProvider)
        vmLoser.onApiKeyChange("unit-test-loser-key-AAAAAAAA")
        vmLoser.save()
        awaitGate(policyLoser.entered)
        assertEquals(1, vaultLoser.putKeys)
        val loserStaged = vaultLoser.putIds.single()
        vmWinner.onApiKeyChange("unit-test-winner-key-BBBBBBBB")
        vmWinner.save()
        awaitSettledSave(vmWinner)
        assertTrue(vmWinner.form.value.saved)
        val winnerStaged = vaultWinner.putIds.single()
        assertTrue(winnerStaged != loserStaged)
        val jobLoser = vmLoser.lastSaveJobForTest ?: error("no loser job")
        jobLoser.cancel()
        jobLoser.join()
        assertFalse(vmLoser.form.value.saving)
        assertNull(vmLoser.form.value.errorCode)
        assertFalse(vmLoser.form.value.saved)
        assertEquals(listOf(loserStaged), vaultLoser.deletedIds)
        assertFalse(runBlocking { delegate.hasKey(loserStaged) })
        assertTrue(runBlocking { delegate.hasKey(winnerStaged) })
        cancelAssertVaultKeyEquals(delegate, winnerStaged, "unit-test-winner-key-BBBBBBBB")
        assertEquals(1L, runBlocking { store.currentRevision() })
        assertEquals("provider_key/$winnerStaged", runBlocking { store.observe().first()!!.apiKeyRef })
        assertEquals(EndpointReadiness.READY, runBlocking { store.readiness(delegate) })
        assertFalse(vaultLoser.deletedIds.contains(winnerStaged))
        assertFalse(vaultWinner.deletedIds.contains(winnerStaged))
    }

    @Test
    fun t4LoserCancelWinnerProtectedRotation() = runBlocking {
        val dir = Files.createTempDirectory("setup-cancel-t4r").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val raw = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "t4r.preferences_pb") },
        )
        val store = EndpointStore(raw)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(cancelPreseed())
        }
        val policyLoser = SuspendPolicy(Verdict.ALLOW, gateOnCall = 2)
        val policyWinner = SuspendPolicy(Verdict.ALLOW)
        val vaultLoser = CancelCountingVault(delegate)
        val vaultWinner = CancelCountingVault(delegate)
        val noProvider: (dev.librepocket.provider.ProviderConfig, dev.librepocket.provider.KeyProvider) -> LlmProvider =
            { _, _ -> error("no provider needed") as LlmProvider }
        val vmLoser = SetupViewModel(store, VaultSource { vaultLoser }, policyLoser, buildProvider = noProvider)
        val vmWinner = SetupViewModel(store, VaultSource { vaultWinner }, policyWinner, buildProvider = noProvider)
        vmLoser.onApiKeyChange("unit-test-loser-key-AAAAAAAA")
        vmLoser.save()
        awaitGate(policyLoser.entered)
        val loserStaged = vaultLoser.putIds.single()
        vmWinner.onApiKeyChange("unit-test-winner-key-BBBBBBBB")
        vmWinner.save()
        awaitSettledSave(vmWinner)
        assertTrue(vmWinner.form.value.saved)
        val winnerStaged = vaultWinner.putIds.single()
        assertTrue(winnerStaged != loserStaged)
        val jobLoser = vmLoser.lastSaveJobForTest ?: error("no loser job")
        jobLoser.cancel()
        jobLoser.join()
        assertFalse(vmLoser.form.value.saving)
        assertNull(vmLoser.form.value.errorCode)
        assertEquals(listOf(loserStaged), vaultLoser.deletedIds)
        assertFalse(runBlocking { delegate.hasKey(loserStaged) })
        cancelAssertVaultKeyEquals(delegate, winnerStaged, "unit-test-winner-key-BBBBBBBB")
        assertFalse(runBlocking { delegate.hasKey("preset:openai") })
        assertEquals(2L, runBlocking { store.currentRevision() })
        assertEquals("provider_key/$winnerStaged", runBlocking { store.observe().first()!!.apiKeyRef })
        assertEquals(EndpointReadiness.READY, runBlocking { store.readiness(delegate) })
    }

    // ---- T5: saving resets on every cancel path ----

    @Test
    fun t5SavingResetAcrossCancelPoints(): Unit = runBlocking {
        suspend fun oneGate(gateCall: Int?) {
            val policy = SuspendPolicy(Verdict.ALLOW, gateOnCall = gateCall)
            val vault = CancelCountingVault()
            val (vm, _, _) = newVm(policy, vault)
            vm.onApiKeyChange("unit-test-new-key-abcdef")
            vm.save()
            if (gateCall != null) {
                awaitGate(policy.entered)
            } else {
                awaitSaving(vm)
            }
            assertTrue(vm.form.value.saving)
            val job = vm.lastSaveJobForTest ?: error("no save job")
            job.cancel()
            job.join()
            assertFalse(vm.form.value.saving)
            assertNull(vm.form.value.errorCode)
        }
        oneGate(1)
        oneGate(2)
    }

    @Test
    fun t5CancelNeverMasksWithPolicyDenied() = runBlocking {
        val policy = SuspendPolicy(Verdict.ALLOW, gateOnCall = 2)
        val vault = CancelCountingVault()
        val (vm, _, store) = newVm(policy, vault)
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        awaitGate(policy.entered)
        val job = vm.lastSaveJobForTest ?: error("no save job")
        job.cancel()
        job.join()
        assertFalse(vm.form.value.saving)
        assertFalse(vm.form.value.saved)
        assertTrue(vm.form.value.errorCode != "POLICY_DENIED_KEY_WRITE")
        assertFalse(store.hasMetadata())
    }

    // ---- T5b: saveMutex released after cancel — same VM can save again ----

    @Test
    fun t5SaveMutexReleasedAfterCancel() = runBlocking {
        val policy = SuspendPolicy(Verdict.ALLOW, gateOnCall = 1)
        val vault = CancelCountingVault()
        val (vm, _, store) = newVm(policy, vault)
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        awaitGate(policy.entered)
        assertTrue(vm.form.value.saving)
        val job = vm.lastSaveJobForTest ?: error("no save job")
        job.cancel()
        job.join()
        assertFalse(vm.form.value.saving)
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertEquals(0, vault.putKeys)
        // Same VM reuses the still-typed key: saving reset + mutex released, so
        // the second save (V1 now passes, freshCalls==2) must commit to READY.
        vm.save()
        awaitSettledSave(vm)
        assertFalse(vm.form.value.saving)
        assertNull(vm.form.value.errorCode)
        assertTrue(vm.form.value.saved)
        assertEquals(1, vault.putKeys)
        assertTrue(store.hasMetadata())
        assertEquals(1L, store.currentRevision())
        val staged = vault.putIds.single()
        assertEquals("provider_key/$staged", store.observe().first()!!.apiKeyRef)
        cancelAssertVaultKeyEquals(vault, staged, "unit-test-new-key-abcdef")
        assertEquals(EndpointReadiness.READY, store.readiness(vault))
    }
}

