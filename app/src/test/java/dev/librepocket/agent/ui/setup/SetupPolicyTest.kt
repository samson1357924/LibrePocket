package dev.librepocket.agent.ui.setup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.LlmProvider
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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

    private fun newVm(policy: PolicyStore): Triple<SetupViewModel, EncryptedPrefsVault, EndpointStore> {
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
        val vault = EncryptedPrefsVault(InMemoryPrefs())
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
        // Policy checked once; the confirmation bypasses the second check.
        assertEquals(1, policy.freshCalls)
        runBlocking {
            assertTrue(vault.hasKey("preset:openai"))
            assertTrue(store.hasMetadata())
        }
    }

    @Test
    fun allowSavesDirectlyWithoutDialog() {
        val (vm, vault, store) = newVm(VerdictPolicy(Verdict.ALLOW))
        vm.onApiKeyChange("sk-test-key-123")
        vm.save()
        awaitSettled(vm)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertTrue(vm.form.value.saved)
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
}
