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
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Fixed-verdict PolicyStore stub for the key.write gate tests. */
class VerdictPolicy(private val verdict: Verdict) : PolicyStore {
    var freshCalls = 0

    override fun evaluate(action: String, resource: String) =
        PolicyDecision(verdict, null, System.currentTimeMillis())

    override suspend fun setRule(rule: PolicyRule) = Unit
    override suspend fun removeRule(pattern: String) = Unit
    override suspend fun listRules(): List<PolicyRule> = emptyList()

    override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision {
        freshCalls++
        return PolicyDecision(verdict, null, System.currentTimeMillis())
    }
}

/** PolicyStore stub with a mutable verdict (policy-flip regression tests). */
class MutablePolicy(var verdict: Verdict) : PolicyStore {
    var freshCalls = 0
    var lastAction: String? = null
        private set
    var lastResource: String? = null
        private set

    override fun evaluate(action: String, resource: String) =
        PolicyDecision(verdict, null, System.currentTimeMillis())

    override suspend fun setRule(rule: PolicyRule) = Unit
    override suspend fun removeRule(pattern: String) = Unit
    override suspend fun listRules(): List<PolicyRule> = emptyList()

    override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision {
        freshCalls++
        lastAction = action
        lastResource = resource
        return PolicyDecision(verdict, null, System.currentTimeMillis())
    }
}

/** Policy stub that can gate evaluateFresh with latches for TOCTOU race tests. */
class GatedPolicy(var verdict: Verdict) : PolicyStore {
    var freshCalls = 0

    @Volatile var gateNext: Boolean = false
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun evaluate(action: String, resource: String) =
        PolicyDecision(verdict, null, System.currentTimeMillis())

    override suspend fun setRule(rule: PolicyRule) = Unit
    override suspend fun removeRule(pattern: String) = Unit
    override suspend fun listRules(): List<PolicyRule> = emptyList()

    override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision {
        freshCalls++
        if (gateNext) {
            entered.countDown()
            if (!release.await(10, TimeUnit.SECONDS)) error("gated policy timeout")
        }
        return PolicyDecision(verdict, null, System.currentTimeMillis())
    }
}

/** Policy stub whose evaluateFresh always throws (fail-closed coverage). */
class ThrowingPolicy(private val error: Exception = IOException("POLICY_UNAVAILABLE")) : PolicyStore {
    var freshCalls = 0

    override fun evaluate(action: String, resource: String) =
        PolicyDecision(Verdict.DENY, null, System.currentTimeMillis())

    override suspend fun setRule(rule: PolicyRule) = Unit
    override suspend fun removeRule(pattern: String) = Unit
    override suspend fun listRules(): List<PolicyRule> = emptyList()

    override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision {
        freshCalls++
        throw error
    }
}

/** KeyVault decorator counting putKey calls (single-write / replay assertions). */
class CountingVault(
    private val delegate: KeyVault = EncryptedPrefsVault(InMemoryPrefs()),
) : KeyVault {
    var putKeys = 0
        private set

    override suspend fun putKey(providerId: String, apiKey: CharArray) {
        putKeys++
        delegate.putKey(providerId, apiKey)
    }

    override suspend fun getKey(providerId: String): CharArray? = delegate.getKey(providerId)
    override suspend fun deleteKey(providerId: String) = delegate.deleteKey(providerId)
    override suspend fun hasKey(providerId: String): Boolean = delegate.hasKey(providerId)
}

/** KeyVault that blocks inside putKey so the test can race policy/edit/revision changes. */
class BlockingVault(
    private val delegate: KeyVault = EncryptedPrefsVault(InMemoryPrefs()),
    val entered: CountDownLatch = CountDownLatch(1),
    val release: CountDownLatch = CountDownLatch(1),
    @Volatile var block: Boolean = true,
) : KeyVault {
    var putKeys = 0
        private set

    override suspend fun putKey(providerId: String, apiKey: CharArray) {
        putKeys++
        if (block) {
            entered.countDown()
            if (!release.await(10, TimeUnit.SECONDS)) error("blocking vault timeout")
        }
        delegate.putKey(providerId, apiKey)
    }

    override suspend fun getKey(providerId: String): CharArray? = delegate.getKey(providerId)
    override suspend fun deleteKey(providerId: String) = delegate.deleteKey(providerId)
    override suspend fun hasKey(providerId: String): Boolean = delegate.hasKey(providerId)
}

private class FailingSetupDataStore(
    private val error: IOException = IOException("DATASTORE_UNAVAILABLE"),
) : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw error }
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        throw error
}

@OptIn(ExperimentalCoroutinesApi::class)
class SetupPolicyTest {

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
        vault: KeyVault = EncryptedPrefsVault(InMemoryPrefs()),
    ): Triple<SetupViewModel, KeyVault, EndpointStore> {
        val dir = Files.createTempDirectory("setup-policy-test").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val store = EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "policy.preferences_pb") },
            ),
        )
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = policy,
            buildProvider = { _, _ -> error("no provider needed") as LlmProvider },
        )
        return Triple(vm, vault, store)
    }

    private fun awaitSettled(vm: SetupViewModel, timeoutMs: Long = 8000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (vm.form.value.saving) {
            if (System.currentTimeMillis() > end) error("timed out waiting for save")
            Thread.sleep(10)
        }
    }

    @Test
    fun denyBlocksWriteWithNoKeyPersisted() {
        val (vm, vault, store) = newVm(VerdictPolicy(Verdict.DENY))
        vm.onApiKeyChange("sk-test-key-123")
        vm.save()
        awaitSettled(vm)
        assertEquals("POLICY_DENIED_KEY_WRITE", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        runBlocking {
            assertFalse(vault.hasKey("preset:openai"))
            assertFalse(store.hasMetadata())
        }
    }

    @Test
    fun askShowsDialogThenSavesOnConfirm() {
        val policy = VerdictPolicy(Verdict.ASK)
        val (vm, vault, store) = newVm(policy)
        vm.onApiKeyChange("sk-test-key-123")
        vm.save()
        awaitSettled(vm)
        // Dialog requested, nothing persisted yet.
        assertTrue(vm.form.value.confirmKeyWrite)
        assertFalse(vm.form.value.saved)
        runBlocking {
            assertFalse(vault.hasKey("preset:openai"))
            assertFalse(store.hasMetadata())
        }
        vm.confirmKeyWriteSave()
        awaitSettled(vm)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertTrue(vm.form.value.saved)
        // Policy checked three times: once before the dialog, once fresh before
        // the write, once fresh after the write (stage2 second verification).
        assertEquals(3, policy.freshCalls)
        runBlocking {
            assertTrue(vault.hasKey("preset:openai"))
            assertTrue(store.hasMetadata())
        }
    }

    @Test
    fun allowSavesDirectlyWithoutDialog() {
        val policy = VerdictPolicy(Verdict.ALLOW)
        val (vm, vault, store) = newVm(policy)
        vm.onApiKeyChange("sk-test-key-123")
        vm.save()
        awaitSettled(vm)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertTrue(vm.form.value.saved)
        // Stage2: fresh check before AND after the vault write.
        assertEquals(2, policy.freshCalls)
        runBlocking { assertTrue(vault.hasKey("preset:openai")) }
        runBlocking { assertTrue(store.hasMetadata()) }
    }

    @Test
    fun dismissCancelsWithoutSaving() {
        val (vm, vault, _) = newVm(VerdictPolicy(Verdict.ASK))
        vm.onApiKeyChange("sk-test-key-123")
        vm.save()
        awaitSettled(vm)
        assertTrue(vm.form.value.confirmKeyWrite)
        vm.dismissKeyWriteConfirm()
        assertFalse(vm.form.value.confirmKeyWrite)
        assertFalse(vm.form.value.saved)
        runBlocking { assertFalse(vault.hasKey("preset:openai")) }
    }

    @Test
    fun prefillForEditPopulatesFormWithoutKey() {
        val (vm, _, _) = newVm(VerdictPolicy(Verdict.ALLOW))
        vm.prefillForEdit(
            EndpointConfig(
                providerId = "preset:anthropic",
                presetId = "anthropic",
                label = "Anthropic",
                baseUrl = "https://api.anthropic.com",
                protocol = dev.librepocket.provider.ProviderProtocol.ANTHROPIC,
                model = "claude-x",
                apiKeyRef = "provider_key/preset:anthropic",
            ),
        )
        val form = vm.form.value
        assertEquals("anthropic", form.presetId)
        assertEquals("https://api.anthropic.com", form.baseUrl)
        assertEquals("claude-x", form.model)
        assertEquals("", form.apiKey)
        assertNull(form.errorCode)
    }

    @Test
    fun prefillForEditUnknownPresetFallsBackToCustom() {
        val (vm, _, _) = newVm(VerdictPolicy(Verdict.ALLOW))
        vm.prefillForEdit(
            EndpointConfig(
                providerId = "preset:ghost",
                presetId = "ghost",
                label = "Ghost",
                baseUrl = "https://ghost.example.com/v1",
                protocol = dev.librepocket.provider.ProviderProtocol.CHAT_COMPLETIONS,
                model = "m",
                apiKeyRef = "provider_key/preset:ghost",
            ),
        )
        val form = vm.form.value
        assertEquals("custom", form.presetId)
        assertEquals("https://ghost.example.com/v1", form.baseUrl)
    }

    @Test
    fun keyEditInvalidatesConfirmation() {
        val (vm, _, _) = newVm(VerdictPolicy(Verdict.ASK))
        vm.onApiKeyChange("sk-test-key-123")
        vm.save()
        awaitSettled(vm)
        assertTrue(vm.form.value.confirmKeyWrite)
        vm.confirmKeyWriteSave()
        awaitSettled(vm)
        assertTrue(vm.form.value.saved)
        // Editing the key resets the one-shot confirmation.
        vm.onApiKeyChange("sk-test-key-456")
        vm.save()
        awaitSettled(vm)
        assertTrue(vm.form.value.confirmKeyWrite)
        assertNull(vm.form.value.errorCode)
    }

    @Test
    fun askThenDenyBlocksConfirmWithZeroWrites() {
        val policy = MutablePolicy(Verdict.ASK)
        val vault = CountingVault()
        val (vm, _, store) = newVm(policy, vault)
        vm.onApiKeyChange("sk-test-key-123")
        vm.save()
        awaitSettled(vm)
        assertTrue(vm.form.value.confirmKeyWrite)
        assertEquals(1, policy.freshCalls)
        // Policy flips to DENY after the dialog was shown: DENY always wins.
        policy.verdict = Verdict.DENY
        vm.confirmKeyWriteSave()
        awaitSettled(vm)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertEquals("POLICY_DENIED_KEY_WRITE", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        // The confirm path re-evaluated fresh against key.write for this provider.
        assertEquals(2, policy.freshCalls)
        assertEquals("key.write", policy.lastAction)
        assertEquals("preset:openai", policy.lastResource)
        assertEquals(0, vault.putKeys)
        runBlocking {
            assertFalse(vault.hasKey("preset:openai"))
            assertFalse(store.hasMetadata())
        }
    }

    @Test
    fun presetChangeInvalidatesUnconsumedConsent() {
        val policy = MutablePolicy(Verdict.ASK)
        val vault = CountingVault()
        val (vm, _, store) = newVm(policy, vault)
        vm.onApiKeyChange("sk-test-key-123")
        vm.save()
        awaitSettled(vm)
        assertTrue(vm.form.value.confirmKeyWrite)
        // Switching endpoint identity discards the minted consent and the dialog.
        vm.selectPreset("anthropic")
        assertFalse(vm.form.value.confirmKeyWrite)
        vm.confirmKeyWriteSave()
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertEquals(1, policy.freshCalls)
        assertEquals(0, vault.putKeys)
        // A fresh save re-evaluates and mints a new consent for the new identity.
        vm.save()
        awaitSettled(vm)
        assertTrue(vm.form.value.confirmKeyWrite)
        assertEquals(2, policy.freshCalls)
        vm.confirmKeyWriteSave()
        awaitSettled(vm)
        assertTrue(vm.form.value.saved)
        // Stage2: confirm path does V1 + post-write V2.
        assertEquals(4, policy.freshCalls)
        assertEquals(1, vault.putKeys)
        runBlocking {
            assertTrue(vault.hasKey("preset:anthropic"))
            assertFalse(vault.hasKey("preset:openai"))
            assertEquals("preset:anthropic", store.observe().first()?.providerId)
        }
    }

    @Test
    fun baseUrlChangeInvalidatesUnconsumedConsent() {
        val policy = MutablePolicy(Verdict.ASK)
        val vault = CountingVault()
        val (vm, _, store) = newVm(policy, vault)
        vm.selectPreset("custom")
        vm.onBaseUrlChange("https://custom-a.example.com/v1")
        vm.onApiKeyChange("sk-test-key-123")
        vm.save()
        awaitSettled(vm)
        assertTrue(vm.form.value.confirmKeyWrite)
        vm.onBaseUrlChange("https://custom-b.example.com/v1")
        assertFalse(vm.form.value.confirmKeyWrite)
        vm.confirmKeyWriteSave()
        assertFalse(vm.form.value.saved)
        assertEquals(1, policy.freshCalls)
        assertEquals(0, vault.putKeys)
        vm.save()
        awaitSettled(vm)
        assertTrue(vm.form.value.confirmKeyWrite)
        vm.confirmKeyWriteSave()
        awaitSettled(vm)
        assertTrue(vm.form.value.saved)
        assertEquals(1, vault.putKeys)
        runBlocking {
            assertEquals("https://custom-b.example.com/v1", store.observe().first()?.baseUrl)
        }
    }

    @Test
    fun confirmReplayIsRefusedWithSingleWrite() {
        val policy = MutablePolicy(Verdict.ASK)
        val vault = CountingVault()
        val (vm, _, _) = newVm(policy, vault)
        vm.onApiKeyChange("sk-test-key-123")
        vm.save()
        awaitSettled(vm)
        vm.confirmKeyWriteSave()
        awaitSettled(vm)
        assertTrue(vm.form.value.saved)
        assertEquals(1, vault.putKeys)
        // Stage2: V1 before dialog, V1 before write, V2 after write.
        assertEquals(3, policy.freshCalls)
        // The consent was consumed by the first confirm: replaying does nothing,
        // not even a policy re-evaluation.
        vm.confirmKeyWriteSave()
        assertTrue(vm.form.value.saved)
        assertEquals(1, vault.putKeys)
        assertEquals(3, policy.freshCalls)
    }

    // ---- Stage2: form fence + second policy check ----

    @Test
    fun editDuringFirstEvaluateAbortsMint() {
        val policy = GatedPolicy(Verdict.ASK).apply { gateNext = true }
        val vault = CountingVault()
        val (vm, _, store) = newVm(policy, vault)
        vm.onApiKeyChange("sk-test-key-123")
        vm.save()
        assertTrue(policy.entered.await(10, TimeUnit.SECONDS))
        // Race: edit the key while the first fresh evaluation is in flight.
        // The pre-mint form fence must abort the mint.
        vm.onApiKeyChange("sk-edited-key-456")
        policy.release.countDown()
        awaitSettled(vm)
        assertEquals("POLICY_DENIED_KEY_WRITE", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertEquals(1, policy.freshCalls)
        assertEquals(0, vault.putKeys)
        runBlocking {
            assertFalse(vault.hasKey("preset:openai"))
            assertFalse(store.hasMetadata())
        }
    }

    @Test
    fun editDuringConfirmReevaluationAborts() {
        val policy = GatedPolicy(Verdict.ASK)
        val vault = CountingVault()
        val (vm, _, store) = newVm(policy, vault)
        vm.selectPreset("custom")
        vm.onBaseUrlChange("https://custom-a.example.com/v1")
        vm.onApiKeyChange("sk-test-key-123")
        vm.save()
        awaitSettled(vm)
        assertTrue(vm.form.value.confirmKeyWrite)
        assertEquals(1, policy.freshCalls)
        // Gate the confirm-path reevaluation (V1) so the test can race edits.
        policy.gateNext = true
        vm.confirmKeyWriteSave()
        assertTrue(policy.entered.await(10, TimeUnit.SECONDS))
        // Race: edit key + URL; the confirm path must re-validate against the
        // LATEST form (not the entry snapshot) and fail closed.
        vm.onApiKeyChange("sk-edited-key-456")
        vm.onBaseUrlChange("https://custom-b.example.com/v1")
        policy.release.countDown()
        awaitSettled(vm)
        assertEquals("POLICY_DENIED_KEY_WRITE", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertFalse(vm.form.value.confirmKeyWrite)
        // Only the confirm-path V1 ran; the post-write V2 never ran (no putKey).
        assertEquals(2, policy.freshCalls)
        assertEquals(0, vault.putKeys)
        runBlocking {
            assertFalse(vault.hasKey("preset:custom"))
            assertFalse(store.hasMetadata())
        }
    }

    @Test
    fun policyFlipDuringPutKeyBlocksMetadata() {
        val policy = MutablePolicy(Verdict.ALLOW)
        val vault = BlockingVault()
        val (vm, _, store) = newVm(policy, vault)
        vm.onApiKeyChange("sk-test-key-123")
        vm.save()
        assertTrue(vault.entered.await(10, TimeUnit.SECONDS))
        // Policy flips ALLOW->DENY while the vault write is in flight: the
        // second fresh check must block the metadata write.
        policy.verdict = Verdict.DENY
        vault.release.countDown()
        awaitSettled(vm)
        assertEquals("POLICY_DENIED_KEY_WRITE", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertFalse(vm.form.value.confirmKeyWrite)
        // V1 ALLOW + post-write V2 DENY.
        assertEquals(2, policy.freshCalls)
        assertEquals(1, vault.putKeys)
        runBlocking {
            assertFalse(store.hasMetadata())
            // Stage2 never deletes on abort: the just-written key is left as an
            // orphan for stage3 vault reconciliation (no deleteKey here).
            // TODO(stage3): reconcile/delete orphan on abort paths.
            assertTrue(vault.hasKey("preset:openai"))
        }
    }

    @Test
    fun allowToAskDuringPutKeyDemandsConfirm() {
        val policy = MutablePolicy(Verdict.ALLOW)
        val vault = BlockingVault()
        val (vm, _, store) = newVm(policy, vault)
        vm.onApiKeyChange("sk-test-key-123")
        vm.save()
        assertTrue(vault.entered.await(10, TimeUnit.SECONDS))
        // Policy flips ALLOW->ASK during putKey: metadata must NOT be saved;
        // instead a fresh consent is minted and confirmation is re-demanded.
        policy.verdict = Verdict.ASK
        vault.release.countDown()
        awaitSettled(vm)
        assertFalse(vm.form.value.saved)
        assertTrue(vm.form.value.confirmKeyWrite)
        assertNull(vm.form.value.errorCode)
        assertEquals(2, policy.freshCalls)
        assertEquals(1, vault.putKeys)
        runBlocking {
            assertFalse(store.hasMetadata())
            // Orphan key already written; stage3 will reconcile on the next
            // confirm/abandon outcome. This stage must not delete or save.
            // TODO(stage3): reconcile/delete orphan on abort paths.
            assertTrue(vault.hasKey("preset:openai"))
        }
    }

    @Test
    fun confirmToAskWithValidConsentStillSaves() {
        val policy = MutablePolicy(Verdict.ASK)
        val vault = CountingVault()
        val (vm, _, store) = newVm(policy, vault)
        vm.onApiKeyChange("sk-test-key-123")
        vm.save()
        awaitSettled(vm)
        assertTrue(vm.form.value.confirmKeyWrite)
        // Policy stays ASK: a valid matching consent still proceeds to save
        // (V2 ASK + valid consent is not a re-demand).
        vm.confirmKeyWriteSave()
        awaitSettled(vm)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertTrue(vm.form.value.saved)
        assertEquals(3, policy.freshCalls)
        assertEquals(1, vault.putKeys)
        runBlocking {
            assertTrue(vault.hasKey("preset:openai"))
            assertTrue(store.hasMetadata())
        }
    }

    @Test
    fun policyFailureFailsClosed() {
        val policy = ThrowingPolicy()
        val vault = CountingVault()
        val (vm, _, store) = newVm(policy, vault)
        vm.onApiKeyChange("sk-test-key-123")
        vm.save()
        awaitSettled(vm)
        assertEquals("POLICY_DENIED_KEY_WRITE", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertEquals(1, policy.freshCalls)
        assertEquals(0, vault.putKeys)
        runBlocking {
            assertFalse(vault.hasKey("preset:openai"))
            assertFalse(store.hasMetadata())
        }
    }

    @Test
    fun emptyExternalClearBlocksSave() {
        val policy = MutablePolicy(Verdict.ALLOW)
        val vault = BlockingVault()
        val (vm, _, store) = newVm(policy, vault)
        vm.onApiKeyChange("sk-test-key-123")
        vm.save()
        assertTrue(vault.entered.await(10, TimeUnit.SECONDS))
        // External clear advances fresh null -> tombstone 1 while putKey is in
        // flight; the trailing saveIfRevision(null) must lose the CAS.
        runBlocking { store.clear() }
        runBlocking { assertEquals(1L, store.currentRevision()) }
        vault.release.countDown()
        awaitSettled(vm)
        assertEquals("POLICY_DENIED_KEY_WRITE", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertEquals(2, policy.freshCalls)
        assertEquals(1, vault.putKeys)
        runBlocking {
            assertFalse(store.hasMetadata())
            // Orphan left for stage3 (no delete in this stage).
            // TODO(stage3): reconcile/delete orphan on abort paths.
            assertTrue(vault.hasKey("preset:openai"))
            // Failed CAS must not advance the revision again.
            assertEquals(1L, store.currentRevision())
        }
    }

    @Test
    fun crossVmStaleSaveDenied() {
        val dir = Files.createTempDirectory("setup-crossvm-test").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val store = EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "crossvm.preferences_pb") },
            ),
        )
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val policyA = MutablePolicy(Verdict.ALLOW)
        val policyB = MutablePolicy(Verdict.ALLOW)
        val enteredB = CountDownLatch(1)
        val releaseB = CountDownLatch(1)
        val vaultB = BlockingVault(delegate, enteredB, releaseB)
        val vaultA = CountingVault(delegate)
        val noProvider: (dev.librepocket.provider.ProviderConfig, dev.librepocket.provider.KeyProvider) -> LlmProvider =
            { _, _ -> error("no provider needed") as LlmProvider }
        val vmB = SetupViewModel(store, VaultSource { vaultB }, policyB, buildProvider = noProvider)
        val vmA = SetupViewModel(store, VaultSource { vaultA }, policyA, buildProvider = noProvider)
        // B snapshots fresh null, then blocks in putKey.
        vmB.onApiKeyChange("sk-test-key-123")
        vmB.save()
        assertTrue(enteredB.await(10, TimeUnit.SECONDS))
        // A wins the race: fresh null -> 1.
        vmA.onApiKeyChange("sk-test-key-123")
        vmA.save()
        awaitSettled(vmA)
        assertTrue(vmA.form.value.saved)
        assertEquals(2, policyA.freshCalls)
        // B resumes: its stale null expectation must lose the CAS.
        releaseB.countDown()
        awaitSettled(vmB)
        assertEquals("POLICY_DENIED_KEY_WRITE", vmB.form.value.errorCode)
        assertFalse(vmB.form.value.saved)
        assertEquals(2, policyB.freshCalls)
        runBlocking {
            assertTrue(store.hasMetadata())
            assertEquals(1L, store.currentRevision())
        }
    }

    @Test
    fun revisionReadFailureFailsClosed() {
        val policy = VerdictPolicy(Verdict.ALLOW)
        val vault = CountingVault()
        val failingStore = EndpointStore(FailingSetupDataStore())
        val vm = SetupViewModel(
            store = failingStore,
            vaultSource = VaultSource { vault },
            policy = policy,
            buildProvider = { _, _ -> error("no provider needed") as LlmProvider },
        )
        vm.onApiKeyChange("sk-test-key-123")
        vm.save()
        awaitSettled(vm)
        // currentRevision() failure before putKey fails closed with no orphan.
        assertEquals("POLICY_DENIED_KEY_WRITE", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertEquals(1, policy.freshCalls)
        assertEquals(0, vault.putKeys)
    }

    @Test
    fun editBetweenMintAndDialogAbortsWithoutDialog() {
        val policy = VerdictPolicy(Verdict.ASK)
        val vault = CountingVault()
        val (vm, _, store) = newVm(policy, vault)
        vm.onApiKeyChange("sk-test-key-123")
        // Gated skeleton: pause the IO save after mint success but before the
        // dialog set, so the test can race an edit into the mint->dialog window.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        vm.afterMintBeforeDialogForTest = {
            entered.countDown()
            if (!release.await(10, TimeUnit.SECONDS)) error("mint-dialog probe timeout")
        }
        vm.save()
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        // Race: edit the key after mint completed but before the dialog shows.
        // Pre-fix this revived the dialog with a discarded consent; post-fix the
        // pre-show fence must discard the just-minted consent and fail closed.
        vm.onApiKeyChange("sk-edited-key-456")
        release.countDown()
        awaitSettled(vm)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertEquals("POLICY_DENIED_KEY_WRITE", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        // Only the pre-dialog V1 ran; no vault write happened.
        assertEquals(1, policy.freshCalls)
        assertEquals(0, vault.putKeys)
        runBlocking {
            assertFalse(vault.hasKey("preset:openai"))
            assertFalse(store.hasMetadata())
        }
        // The minted consent was discarded: a late confirm is a refused replay
        // with no further policy evaluation or write (fail-closed, no deleteKey).
        vm.confirmKeyWriteSave()
        assertFalse(vm.form.value.confirmKeyWrite)
        assertEquals(1, policy.freshCalls)
        assertEquals(0, vault.putKeys)
    }

    @Test
    fun editBetweenFlipMintAndDialogAbortsWithoutDialog() {
        val policy = MutablePolicy(Verdict.ALLOW)
        val vault = BlockingVault()
        val (vm, _, store) = newVm(policy, vault)
        vm.onApiKeyChange("sk-test-key-123")
        // Blocking skeleton for the ALLOW->ASK flip path: block putKey, flip
        // policy, then gate the flip mint->dialog window with the probe.
        val mintEntered = CountDownLatch(1)
        val mintRelease = CountDownLatch(1)
        vm.afterMintBeforeDialogForTest = {
            mintEntered.countDown()
            if (!mintRelease.await(10, TimeUnit.SECONDS)) error("flip mint-dialog probe timeout")
        }
        vm.save()
        assertTrue(vault.entered.await(10, TimeUnit.SECONDS))
        policy.verdict = Verdict.ASK
        vault.release.countDown()
        assertTrue(mintEntered.await(10, TimeUnit.SECONDS))
        // Race: edit after the flip mint but before its dialog shows.
        vm.onApiKeyChange("sk-edited-key-456")
        mintRelease.countDown()
        awaitSettled(vm)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertEquals("POLICY_DENIED_KEY_WRITE", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        // V1 ALLOW + post-write V2 ASK; metadata never written.
        assertEquals(2, policy.freshCalls)
        assertEquals(1, vault.putKeys)
        runBlocking {
            assertFalse(store.hasMetadata())
            // Orphan from putKey stays (no deleteKey; stage3 reconciles).
            // TODO(stage3): reconcile/delete orphan on abort paths.
            assertTrue(vault.hasKey("preset:openai"))
        }
    }
}
