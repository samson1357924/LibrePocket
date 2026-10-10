package dev.librepocket.agent.ui.setup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.keystore.KeyVault
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
        // Policy checked twice: once before the dialog, once fresh before the write.
        assertEquals(2, policy.freshCalls)
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
        assertEquals(3, policy.freshCalls)
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
        assertEquals(2, policy.freshCalls)
        // The consent was consumed by the first confirm: replaying does nothing,
        // not even a policy re-evaluation.
        vm.confirmKeyWriteSave()
        assertTrue(vm.form.value.saved)
        assertEquals(1, vault.putKeys)
        assertEquals(2, policy.freshCalls)
    }
}
