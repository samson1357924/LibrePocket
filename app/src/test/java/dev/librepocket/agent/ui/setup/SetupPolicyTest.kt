package dev.librepocket.agent.ui.setup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
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
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeout
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

/** KeyVault decorator counting put/get/delete/has calls (single-write / replay assertions). */
class CountingVault(
    private val delegate: KeyVault = EncryptedPrefsVault(InMemoryPrefs()),
) : KeyVault {
    var putKeys = 0
        private set
    var getKeys = 0
        private set
    var deleteKeys = 0
        private set
    var hasKeys = 0
        private set
    /** Vault aliases (providerIds) passed to putKey, in order. */
    val putIds = ArrayList<String>()
    /** Vault aliases (providerIds) passed to deleteKey, in order. */
    val deletedIds = ArrayList<String>()

    override suspend fun putKey(providerId: String, apiKey: CharArray) {
        putKeys++
        putIds.add(providerId)
        delegate.putKey(providerId, apiKey)
    }

    // Returns the delegate's fresh copy directly; the TEST wipes the returned
    // array (never the vault). Counting only — no extra copy here.
    override suspend fun getKey(providerId: String): CharArray? {
        getKeys++
        return delegate.getKey(providerId)
    }
    override suspend fun deleteKey(providerId: String) {
        deleteKeys++
        deletedIds.add(providerId)
        delegate.deleteKey(providerId)
    }
    override suspend fun hasKey(providerId: String): Boolean {
        hasKeys++
        return delegate.hasKey(providerId)
    }
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
    var getKeys = 0
        private set
    var deleteKeys = 0
        private set
    var hasKeys = 0
        private set
    /** Vault aliases (providerIds) passed to putKey, in order. */
    val putIds = ArrayList<String>()
    /** Vault aliases (providerIds) passed to deleteKey, in order. */
    val deletedIds = ArrayList<String>()

    override suspend fun putKey(providerId: String, apiKey: CharArray) {
        putKeys++
        putIds.add(providerId)
        if (block) {
            entered.countDown()
            if (!release.await(10, TimeUnit.SECONDS)) error("blocking vault timeout")
        }
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
    override suspend fun hasKey(providerId: String): Boolean {
        hasKeys++
        return delegate.hasKey(providerId)
    }
}

/**
 * KeyVault whose deleteKey always throws: models a vault failure during
 * logout teardown. Logout must abort before the metadata clear, so the
 * metadata, key bytes, and revision stay untouched.
 */
class ThrowingDeleteVault(
    private val delegate: KeyVault = EncryptedPrefsVault(InMemoryPrefs()),
    private val error: Exception = IOException("VAULT_DELETE_FAILED"),
) : KeyVault {
    var deleteAttempts = 0
        private set
    /** Vault aliases passed to deleteKey, in order (recorded before throwing). */
    val deletedIds = ArrayList<String>()

    override suspend fun putKey(providerId: String, apiKey: CharArray) =
        delegate.putKey(providerId, apiKey)
    override suspend fun getKey(providerId: String): CharArray? =
        delegate.getKey(providerId)
    override suspend fun deleteKey(providerId: String) {
        deleteAttempts++
        deletedIds.add(providerId)
        throw error
    }
    override suspend fun hasKey(providerId: String): Boolean =
        delegate.hasKey(providerId)
}

/**
 * KeyVault gating deleteKey entry via suspending deferreds (logout-stale
 * window): the logout parks inside deleteKey after snapshotting, the test
 * lets a save commit, then releases the logout into a CAS loss.
 */
class DeleteGatingLogoutVault(
    private val delegate: KeyVault = EncryptedPrefsVault(InMemoryPrefs()),
    val deleteEntered: CompletableDeferred<Unit> = CompletableDeferred(),
    val deleteRelease: CompletableDeferred<Unit> = CompletableDeferred(),
) : KeyVault {
    var deleteKeys = 0
        private set
    /** Vault aliases passed to deleteKey, in order (recorded before gating). */
    val deletedIds = ArrayList<String>()

    override suspend fun putKey(providerId: String, apiKey: CharArray) =
        delegate.putKey(providerId, apiKey)
    override suspend fun getKey(providerId: String): CharArray? =
        delegate.getKey(providerId)
    override suspend fun deleteKey(providerId: String) {
        deleteKeys++
        deletedIds.add(providerId)
        deleteEntered.complete(Unit)
        deleteRelease.await()
        delegate.deleteKey(providerId)
    }
    override suspend fun hasKey(providerId: String): Boolean =
        delegate.hasKey(providerId)
}

/**
 * DataStore whose reads always throw but counts writes: models a metadata
 * read failure during logout. Logout must abort before any side effect, so
 * [updateCalls] stays zero (no clear attempted).
 */
private class ReadFailingLogoutDataStore(
    private val error: IOException = IOException("DATASTORE_UNAVAILABLE"),
) : DataStore<Preferences> {
    var updateCalls = 0
        private set
    override val data: Flow<Preferences> = flow { throw error }
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        updateCalls++
        throw error
    }
}

/**
 * Action-aware PolicyStore stub: per-action verdicts, recording every
 * fresh action for key.read/key.write separation assertions.
 *
 * Mirrors ChatViewModel semantics in tests: only DENY blocks; ASK/ALLOW pass.
 * Callers assert via [actions]/[freshCalls] that key.read was (or was not)
 * consulted, and via vault counters that DENY blocked before backup.
 */
class MapActionPolicy(
    private val byAction: Map<String, Verdict>,
    private val default: Verdict = Verdict.ALLOW,
) : PolicyStore {
    var freshCalls = 0
        private set
    val actions = ArrayList<String>()
    var lastAction: String? = null
        private set
    var lastResource: String? = null
        private set

    override fun evaluate(action: String, resource: String) =
        PolicyDecision(byAction[action] ?: default, null, System.currentTimeMillis())

    override suspend fun setRule(rule: PolicyRule) = Unit
    override suspend fun removeRule(pattern: String) = Unit
    override suspend fun listRules(): List<PolicyRule> = emptyList()

    override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision {
        freshCalls++
        actions.add(action)
        lastAction = action
        lastResource = resource
        return PolicyDecision(byAction[action] ?: default, null, System.currentTimeMillis())
    }
}

/** Throwing action-aware stub: one action throws, others return [fallback]. */
class ThrowingActionPolicy(
    private val throwAction: String,
    private val error: Exception = IOException("POLICY_UNAVAILABLE"),
    private val fallback: Verdict = Verdict.ALLOW,
) : PolicyStore {
    var freshCalls = 0
        private set
    val actions = ArrayList<String>()

    override fun evaluate(action: String, resource: String) =
        PolicyDecision(Verdict.DENY, null, System.currentTimeMillis())

    override suspend fun setRule(rule: PolicyRule) = Unit
    override suspend fun removeRule(pattern: String) = Unit
    override suspend fun listRules(): List<PolicyRule> = emptyList()

    override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision {
        freshCalls++
        actions.add(action)
        if (action == throwAction) throw error
        return PolicyDecision(fallback, null, System.currentTimeMillis())
    }
}

private class FailingSetupDataStore(
    private val error: IOException = IOException("DATASTORE_UNAVAILABLE"),
) : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw error }
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        throw error
}

/**
 * Stage3: reads serve a fixed snapshot, writes always fail. Models a store
 * whose metadata CAS/save path is broken while reads still work, so the
 * save-then-rollback path (not the pre-put fail-closed path) is exercised.
 */
private class SaveFailingDataStore(
    private val snapshot: Preferences,
    private val error: IOException = IOException("DATASTORE_SAVE_FAILED"),
) : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { emit(snapshot) }
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        throw error
}

/** Policy stub gating only the Nth fresh evaluation (post-put V2 race tests). */
class NthGatedPolicy(
    var verdict: Verdict,
    private val gateCall: Int = 2,
) : PolicyStore {
    var freshCalls = 0
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun evaluate(action: String, resource: String) =
        PolicyDecision(verdict, null, System.currentTimeMillis())

    override suspend fun setRule(rule: PolicyRule) = Unit
    override suspend fun removeRule(pattern: String) = Unit
    override suspend fun listRules(): List<PolicyRule> = emptyList()

    override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision {
        freshCalls++
        if (freshCalls == gateCall) {
            entered.countDown()
            if (!release.await(10, TimeUnit.SECONDS)) error("nth-gated policy timeout")
        }
        return PolicyDecision(verdict, null, System.currentTimeMillis())
    }
}

/**
 * Asserts vault key content equality with mandatory wiping: both the retrieved
 * array and the expected copy are wiped in `finally` blocks.
 */
private fun assertVaultKeyEquals(vault: KeyVault, providerId: String, expected: String) {
    val expectedChars = expected.toCharArray()
    try {
        val actual = runBlocking { vault.getKey(providerId) }
        try {
            assertTrue("vault key mismatch for $providerId", actual != null && actual.contentEquals(expectedChars))
        } finally {
            actual?.fill('\u0000')
        }
    } finally {
        expectedChars.fill('\u0000')
    }
}

/** Pinned preseed endpoint metadata (openai preset shape, revision managed by the store). */
private fun preseedConfig(model: String = "m-old"): EndpointConfig = EndpointConfig(
    providerId = "preset:openai",
    presetId = "openai",
    label = "OpenAI",
    baseUrl = "https://api.openai.com/v1",
    protocol = ProviderProtocol.CHAT_COMPLETIONS,
    model = model,
    apiKeyRef = "provider_key/preset:openai",
)

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
        stagedAliasProvider: (String) -> String = SetupValidation::newStagedAlias,
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
            stagedAliasProvider = stagedAliasProvider,
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
        vm.onApiKeyChange("unit-test-key-123")
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
        vm.onApiKeyChange("unit-test-key-123")
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
        // the staged write, once fresh after the staged write (stage2 second
        // verification; no key.read gate — the staged path never reads live).
        assertEquals(3, policy.freshCalls)
        runBlocking {
            // The committed live key sits at the per-operation staged alias.
            val ref = store.observe().first()?.apiKeyRef
            assertTrue(ref?.startsWith("provider_key/preset:openai#staged-") == true)
            assertTrue(vault.hasKey(SetupValidation.aliasFromRef(ref!!)))
            assertFalse(vault.hasKey("preset:openai"))
            assertTrue(store.hasMetadata())
        }
    }

    @Test
    fun allowSavesDirectlyWithoutDialog() {
        val policy = VerdictPolicy(Verdict.ALLOW)
        val (vm, vault, store) = newVm(policy)
        vm.onApiKeyChange("unit-test-key-123")
        vm.save()
        awaitSettled(vm)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertTrue(vm.form.value.saved)
        // Staged path: fresh key.write check before AND fresh key.write check
        // after the staged vault write (no key.read gate — no live read exists).
        assertEquals(2, policy.freshCalls)
        runBlocking {
            val ref = store.observe().first()?.apiKeyRef
            assertTrue(ref?.startsWith("provider_key/preset:openai#staged-") == true)
            assertTrue(vault.hasKey(SetupValidation.aliasFromRef(ref!!)))
        }
        runBlocking { assertTrue(store.hasMetadata()) }
    }

    @Test
    fun dismissCancelsWithoutSaving() {
        val (vm, vault, _) = newVm(VerdictPolicy(Verdict.ASK))
        vm.onApiKeyChange("unit-test-key-123")
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
        vm.onApiKeyChange("unit-test-key-123")
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
        vm.onApiKeyChange("unit-test-key-123")
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
        vm.onApiKeyChange("unit-test-key-123")
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
        // Initial ASK dialog (1) + re-save V1 (2) + confirm V1 (3) + post-write
        // V2 (4). No key.read evaluation: the staged path never reads live.
        assertEquals(4, policy.freshCalls)
        assertEquals(1, vault.putKeys)
        runBlocking {
            val ref = store.observe().first()?.apiKeyRef
            assertTrue(ref?.startsWith("provider_key/preset:anthropic#staged-") == true)
            assertTrue(vault.hasKey(SetupValidation.aliasFromRef(ref!!)))
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
        vm.onApiKeyChange("unit-test-key-123")
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
        vm.onApiKeyChange("unit-test-key-123")
        vm.save()
        awaitSettled(vm)
        vm.confirmKeyWriteSave()
        awaitSettled(vm)
        assertTrue(vm.form.value.saved)
        assertEquals(1, vault.putKeys)
        // V1 before dialog, V1 before staged write, V2 after staged write.
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
        vm.onApiKeyChange("unit-test-key-123")
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
        vm.onApiKeyChange("unit-test-key-123")
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
        vm.onApiKeyChange("unit-test-key-123")
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
        // V1 ALLOW + post-write V2 DENY (no key.read gate — no live read).
        assertEquals(2, policy.freshCalls)
        assertEquals(1, vault.putKeys)
        runBlocking {
            assertFalse(store.hasMetadata())
            // Abort deletes only the attempt's own staged alias (live was never
            // written): own-staged cleanup, never an unconditional live delete.
            assertEquals(1, vault.deleteKeys)
            assertFalse(vault.hasKey("preset:openai"))
        }
    }

    @Test
    fun allowToAskDuringPutKeyDemandsConfirm() {
        val policy = MutablePolicy(Verdict.ALLOW)
        val vault = BlockingVault()
        val (vm, _, store) = newVm(policy, vault)
        vm.onApiKeyChange("unit-test-key-123")
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
            // The flip-path staged key is deleted before showing the re-confirm
            // dialog (fresh start: live was never written). The confirm tap
            // performs a clean staged put of its own.
            assertEquals(1, vault.deleteKeys)
            assertFalse(vault.hasKey("preset:openai"))
        }
        // Confirming still completes the save with a fresh staged put.
        vm.confirmKeyWriteSave()
        awaitSettled(vm)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertTrue(vm.form.value.saved)
        // Confirm path re-evaluates fresh before and after its own staged write.
        assertEquals(4, policy.freshCalls)
        assertEquals(2, vault.putKeys)
        runBlocking {
            val ref = store.observe().first()?.apiKeyRef
            assertTrue(ref?.startsWith("provider_key/preset:openai#staged-") == true)
            assertTrue(vault.hasKey(SetupValidation.aliasFromRef(ref!!)))
            assertTrue(store.hasMetadata())
        }
    }

    @Test
    fun confirmToAskWithValidConsentStillSaves() {
        val policy = MutablePolicy(Verdict.ASK)
        val vault = CountingVault()
        val (vm, _, store) = newVm(policy, vault)
        vm.onApiKeyChange("unit-test-key-123")
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
            val ref = store.observe().first()?.apiKeyRef
            assertTrue(ref?.startsWith("provider_key/preset:openai#staged-") == true)
            assertTrue(vault.hasKey(SetupValidation.aliasFromRef(ref!!)))
            assertTrue(store.hasMetadata())
        }
    }

    @Test
    fun policyFailureFailsClosed() {
        val policy = ThrowingPolicy()
        val vault = CountingVault()
        val (vm, _, store) = newVm(policy, vault)
        vm.onApiKeyChange("unit-test-key-123")
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
        vm.onApiKeyChange("unit-test-key-123")
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
            // Abort deletes only the attempt's own staged alias (live was never
            // written).
            assertEquals(1, vault.deleteKeys)
            assertFalse(vault.hasKey("preset:openai"))
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
        vmB.onApiKeyChange("unit-test-key-123")
        vmB.save()
        assertTrue(enteredB.await(10, TimeUnit.SECONDS))
        // A wins the race: fresh null -> 1.
        vmA.onApiKeyChange("unit-test-key-123")
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
            // P1 same-bytes: A and B wrote byte-identical content under distinct
            // staged aliases. The loser (B, CAS loss) deletes only its own
            // staged alias; the winner's staged live key is untouched.
            val winnerRef = store.observe().first()?.apiKeyRef
            assertTrue(winnerRef?.startsWith("provider_key/preset:openai#staged-") == true)
            assertEquals(EndpointReadiness.READY, store.readiness(delegate))
        }
        assertVaultKeyEquals(
            delegate,
            SetupValidation.aliasFromRef(runBlocking { store.observe().first()!!.apiKeyRef }),
            "unit-test-key-123",
        )
    }

    @Test
    fun sameBytesFreshLoserDoesNotDeleteWinner() {
        // P1 regression: byte-identical concurrent saves are indistinguishable
        // by content alone. The CAS loser must leave the winner's key untouched
        // (no vault delete), not delete it as "still ours".
        val dir = Files.createTempDirectory("setup-samebytes-fresh-test").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val store = EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "samebytes-fresh.preferences_pb") },
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
        vmB.onApiKeyChange("unit-test-same-key-abcdef")
        vmB.save()
        assertTrue(enteredB.await(10, TimeUnit.SECONDS))
        // A wins with byte-identical content: fresh null -> 1.
        vmA.onApiKeyChange("unit-test-same-key-abcdef")
        vmA.save()
        awaitSettled(vmA)
        assertTrue(vmA.form.value.saved)
        assertEquals(2, policyA.freshCalls)
        // B resumes: stale null loses the CAS but must not delete the winner.
        releaseB.countDown()
        awaitSettled(vmB)
        assertEquals("POLICY_DENIED_KEY_WRITE", vmB.form.value.errorCode)
        assertFalse(vmB.form.value.saved)
        assertEquals(2, policyB.freshCalls)
        // One staged put each; the loser deletes only its own staged alias
        // (pre-fix the loser saw live == newKeyCopy and deleted the winner).
        assertEquals(1, vaultA.putKeys)
        assertEquals(1, vaultB.putKeys)
        assertEquals(0, vaultA.deleteKeys)
        assertEquals(1, vaultB.deleteKeys)
        runBlocking {
            assertTrue(store.hasMetadata())
            assertEquals(1L, store.currentRevision())
            val winnerRef = store.observe().first()?.apiKeyRef
            assertTrue(winnerRef?.startsWith("provider_key/preset:openai#staged-") == true)
            assertTrue(delegate.hasKey(SetupValidation.aliasFromRef(winnerRef!!)))
            assertEquals(vaultA.putIds.single(), SetupValidation.aliasFromRef(winnerRef))
            assertEquals(EndpointReadiness.READY, store.readiness(delegate))
            // The loser's staged alias is gone; the fixed live slot was never used.
            assertFalse(delegate.hasKey(vaultB.putIds.single()))
            assertFalse(delegate.hasKey("preset:openai"))
        }
        assertVaultKeyEquals(
            delegate,
            SetupValidation.aliasFromRef(runBlocking { store.observe().first()!!.apiKeyRef }),
            "unit-test-same-key-abcdef",
        )
    }

    @Test
    fun sameBytesHadKeyLoserDoesNotOverwriteWinner() {
        // P1 regression (hadKey): predecessor exists, winner and loser rotate to
        // byte-identical content. The CAS loser must not restore the predecessor
        // over the winner (same-content indistinguishable): leave the winner.
        val dir = Files.createTempDirectory("setup-samebytes-hadkey-test").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val store = EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "samebytes-hadkey.preferences_pb") },
            ),
        )
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseedConfig())
            assertEquals(1L, store.currentRevision())
        }
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
        // B snapshots rev 1 (backup = old), then blocks in putKey.
        vmB.onApiKeyChange("unit-test-new-key-SAMEabcdef")
        vmB.save()
        assertTrue(enteredB.await(10, TimeUnit.SECONDS))
        // A wins with byte-identical content: rev 1 -> 2.
        vmA.onApiKeyChange("unit-test-new-key-SAMEabcdef")
        vmA.save()
        awaitSettled(vmA)
        assertTrue(vmA.form.value.saved)
        assertEquals(2, policyA.freshCalls)
        runBlocking { assertEquals(2L, store.currentRevision()) }
        // B resumes: stale rev 1 loses the CAS and deletes only its own staged
        // alias — never the predecessor-restore over the winner (pre-fix the
        // loser saw live == newKeyCopy and overwrote the winner with old).
        releaseB.countDown()
        awaitSettled(vmB)
        assertEquals("POLICY_DENIED_KEY_WRITE", vmB.form.value.errorCode)
        assertFalse(vmB.form.value.saved)
        assertEquals(2, policyB.freshCalls)
        // One staged put each; winner retires the predecessor, loser cleans its
        // own staged alias.
        assertEquals(1, vaultA.putKeys)
        assertEquals(1, vaultB.putKeys)
        assertEquals(1, vaultA.deleteKeys)
        assertEquals(listOf("preset:openai"), vaultA.deletedIds)
        assertEquals(1, vaultB.deleteKeys)
        runBlocking {
            assertTrue(store.hasMetadata())
            assertEquals(2L, store.currentRevision())
            val winnerRef = store.observe().first()?.apiKeyRef
            assertTrue(winnerRef?.startsWith("provider_key/preset:openai#staged-") == true)
            assertEquals(vaultA.putIds.single(), SetupValidation.aliasFromRef(winnerRef!!))
            // Predecessor retired by the winner; loser staged cleaned.
            assertFalse(delegate.hasKey("preset:openai"))
            assertFalse(delegate.hasKey(vaultB.putIds.single()))
            assertTrue(delegate.hasKey(SetupValidation.aliasFromRef(winnerRef)))
            assertEquals(EndpointReadiness.READY, store.readiness(delegate))
        }
        assertVaultKeyEquals(
            delegate,
            SetupValidation.aliasFromRef(runBlocking { store.observe().first()!!.apiKeyRef }),
            "unit-test-new-key-SAMEabcdef",
        )
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
        vm.onApiKeyChange("unit-test-key-123")
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
        vm.onApiKeyChange("unit-test-key-123")
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
        vm.onApiKeyChange("unit-test-key-123")
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
            // The flip-path staged key is deleted on the failed mint->dialog
            // window, so no vault residue remains.
            assertEquals(1, vault.deleteKeys)
            assertFalse(vault.hasKey("preset:openai"))
        }
    }

    // ---- Stage3: ownership-checked vault commit/rollback ----

    @Test
    fun preSaveDenyPreservesExistingKey() {
        val policy = MutablePolicy(Verdict.ALLOW)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val vault = BlockingVault(delegate)
        val (vm, _, store) = newVm(policy, vault)
        runBlocking {
            // Preseed through the delegate: the blocking wrapper would hang.
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseedConfig())
        }
        val before = runBlocking { store.observe().first() }
        runBlocking { assertEquals(EndpointReadiness.READY, store.readiness(vault)) }
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        assertTrue(vault.entered.await(10, TimeUnit.SECONDS))
        // Policy flips ALLOW->DENY while the staged write is in flight: the
        // post-write V2 aborts the metadata write and only the attempt's own
        // staged alias is deleted. The live predecessor was never overwritten
        // (survival by non-overwrite, not by restore).
        policy.verdict = Verdict.DENY
        vault.release.countDown()
        awaitSettled(vm)
        assertEquals("POLICY_DENIED_KEY_WRITE", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertEquals(2, policy.freshCalls)
        // One staged put for the save attempt plus one delete of that staged
        // alias (no predecessor-restore put: the live slot was never touched).
        assertEquals(1, vault.putKeys)
        assertEquals(1, vault.deleteKeys)
        assertEquals(0, vault.getKeys)
        // Key content compared inside try/finally with wiping (never logged).
        assertVaultKeyEquals(vault, "preset:openai", "unit-test-old-key-123456")
        runBlocking {
            assertTrue(vault.hasKey("preset:openai"))
            assertTrue(store.hasMetadata())
            assertEquals(before, store.observe().first())
            assertEquals(EndpointReadiness.READY, store.readiness(vault))
        }
    }

    @Test
    fun storeSaveFailurePreservesExistingKey() {
        val snapshot = mutablePreferencesOf(
            ENDPOINT_PROVIDER_ID_V1 to "preset:openai",
            ENDPOINT_PRESET_ID_V1 to "openai",
            ENDPOINT_LABEL_V1 to "OpenAI",
            ENDPOINT_BASE_URL_V1 to "https://api.openai.com/v1",
            ENDPOINT_PROTOCOL_V1 to ProviderProtocol.CHAT_COMPLETIONS.name,
            ENDPOINT_MODEL_V1 to "m-old",
            ENDPOINT_API_KEY_REF_V1 to "provider_key/preset:openai",
            ENDPOINT_CONFIG_REVISION_V1 to 1L,
        )
        val failingStore = EndpointStore(SaveFailingDataStore(snapshot))
        val policy = VerdictPolicy(Verdict.ALLOW)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val vault = CountingVault(delegate)
        val vm = SetupViewModel(
            store = failingStore,
            vaultSource = VaultSource { vault },
            policy = policy,
            buildProvider = { _, _ -> error("no provider needed") as LlmProvider },
        )
        // Preseed through the delegate so putKeys counts only the save attempt.
        runBlocking { delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray()) }
        runBlocking {
            assertTrue(failingStore.hasMetadata())
            assertEquals(EndpointReadiness.READY, failingStore.readiness(vault))
        }
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        awaitSettled(vm)
        // The metadata CAS threw: abort deletes only the attempt's own staged
        // alias. With a predecessor present the live key MUST survive — by
        // non-overwrite (the staged write never touched the live slot), not by
        // restore. This overturns the empty-start hasKey==false expectation.
        assertEquals("POLICY_DENIED_KEY_WRITE", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertEquals(2, policy.freshCalls)
        // One staged put for the save attempt plus one delete of that staged
        // alias (preseed bypassed this counter).
        assertEquals(1, vault.putKeys)
        assertEquals(1, vault.deleteKeys)
        assertEquals(0, vault.getKeys)
        assertVaultKeyEquals(vault, "preset:openai", "unit-test-old-key-123456")
        runBlocking {
            assertTrue(vault.hasKey("preset:openai"))
            assertTrue(failingStore.hasMetadata())
            assertEquals(EndpointReadiness.READY, failingStore.readiness(vault))
        }
    }

    @Test
    fun externalWriterInterleavingNeverDeletesForeignKey() {
        // V1 key.write (1) + post-write V2 key.write (2, gated). The external
        // writer lands while V2 is gated. (No key.read gate: the staged path
        // never reads live.)
        val policy = NthGatedPolicy(Verdict.ALLOW, gateCall = 2)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val vault = CountingVault(delegate)
        val (vm, _, store) = newVm(policy, vault)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseedConfig())
        }
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        // Our staged putKey has completed when the post-write V2 blocks: the
        // external writer lands strictly between our staged put and our abort
        // decision. The staged write never touched the live slot, so the
        // external live key is intact regardless of timing.
        assertTrue(policy.entered.await(10, TimeUnit.SECONDS))
        runBlocking {
            delegate.putKey("preset:openai", "sk-ext-key-789012".toCharArray())
            store.save(preseedConfig(model = "m-ext"))
        }
        val extObserved = runBlocking { store.observe().first() }
        policy.release.countDown()
        awaitSettled(vm)
        // Trailing CAS loses (revision 1 -> 2): abort deletes only our own
        // staged alias; the foreign live key observed in place survives (never
        // overwritten, never deleted — it was never in our slot to begin with).
        assertEquals("POLICY_DENIED_KEY_WRITE", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertEquals(2, policy.freshCalls)
        assertEquals(1, vault.putKeys)
        assertEquals(1, vault.deleteKeys)
        assertEquals(0, vault.getKeys)
        assertVaultKeyEquals(vault, "preset:openai", "sk-ext-key-789012")
        runBlocking {
            assertTrue(vault.hasKey("preset:openai"))
            assertEquals(extObserved, store.observe().first())
            assertEquals("m-ext", store.observe().first()?.model)
            assertEquals(EndpointReadiness.READY, store.readiness(vault))
        }
    }

    @Test
    fun freshSetupAbortCleansOwnOrphan() {
        val policy = MutablePolicy(Verdict.ALLOW)
        val vault = BlockingVault()
        val (vm, _, store) = newVm(policy, vault)
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        assertTrue(vault.entered.await(10, TimeUnit.SECONDS))
        // Race an edit while the staged putKey is in flight: the post-write
        // generation fence aborts the metadata write and deletes only the
        // attempt's own staged alias (live was never written).
        vm.onApiKeyChange("sk-edited-key-654321")
        vault.release.countDown()
        awaitSettled(vm)
        assertEquals("POLICY_DENIED_KEY_WRITE", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertEquals(2, policy.freshCalls)
        assertEquals(1, vault.putKeys)
        assertEquals(1, vault.deleteKeys)
        runBlocking {
            assertFalse(vault.hasKey("preset:openai"))
            assertFalse(store.hasMetadata())
        }
    }

    @Test
    fun rotationSuccess() {
        val policy = MutablePolicy(Verdict.ALLOW)
        val vault = CountingVault()
        val (vm, _, store) = newVm(policy, vault)
        vm.onApiKeyChange("unit-test-old-key-123456")
        vm.save()
        awaitSettled(vm)
        assertTrue(vm.form.value.saved)
        val firstRef = runBlocking { store.observe().first()!!.apiKeyRef }
        assertTrue(firstRef.startsWith("provider_key/preset:openai#staged-"))
        assertVaultKeyEquals(vault, SetupValidation.aliasFromRef(firstRef), "unit-test-old-key-123456")
        vm.consumeSaved()
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        awaitSettled(vm)
        assertTrue(vm.form.value.saved)
        // Two saves, two fresh evaluations each
        // (pre-write V1 key.write + post-write V2 key.write; no key.read gate).
        assertEquals(2, vault.putKeys)
        assertEquals(4, policy.freshCalls)
        val secondRef = runBlocking { store.observe().first()!!.apiKeyRef }
        assertTrue(secondRef.startsWith("provider_key/preset:openai#staged-"))
        // Per-operation aliases: the rotation minted a fresh staged alias and
        // retired the previous live one.
        assertTrue(firstRef != secondRef)
        assertVaultKeyEquals(vault, SetupValidation.aliasFromRef(secondRef), "unit-test-new-key-abcdef")
        runBlocking {
            assertTrue(store.hasMetadata())
            assertEquals(2L, store.currentRevision())
            assertFalse(vault.hasKey(SetupValidation.aliasFromRef(firstRef)))
            assertEquals(EndpointReadiness.READY, store.readiness(vault))
        }
        // Winner retire accounts for the single delete.
        assertEquals(1, vault.deleteKeys)
        assertEquals(listOf(SetupValidation.aliasFromRef(firstRef)), vault.deletedIds)
    }

    // ---- Staged alias: no live read exists, so key.read is never consulted ----

    @Test
    fun keyReadDenyNeverConsultedOnFreshSave() {
        // The backup scheme (and its key.read gate) is gone by construction:
        // the staged path never reads a live key, so even an explicit key.read
        // DENY cannot block a save. Only key.write verdicts matter.
        val policy = MapActionPolicy(mapOf("key.write" to Verdict.ALLOW, "key.read" to Verdict.DENY))
        val vault = CountingVault()
        val (vm, _, store) = newVm(policy, vault)
        vm.onApiKeyChange("unit-test-key-123")
        vm.save()
        awaitSettled(vm)
        assertTrue(vm.form.value.saved)
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertEquals(0, vault.getKeys)
        assertEquals(1, vault.putKeys)
        assertEquals(0, vault.deleteKeys)
        assertEquals(2, policy.freshCalls)
        assertEquals(listOf("key.write", "key.write"), policy.actions)
        runBlocking {
            val ref = store.observe().first()?.apiKeyRef
            assertTrue(ref?.startsWith("provider_key/preset:openai#staged-") == true)
            assertTrue(vault.hasKey(SetupValidation.aliasFromRef(ref!!)))
            assertTrue(store.hasMetadata())
        }
        assertVaultKeyEquals(
            vault,
            SetupValidation.aliasFromRef(runBlocking { store.observe().first()!!.apiKeyRef }),
            "unit-test-key-123",
        )
    }

    @Test
    fun keyReadDenyNeverConsultedOnConfirmPath() {
        val policy = MapActionPolicy(mapOf("key.write" to Verdict.ASK, "key.read" to Verdict.DENY))
        val vault = CountingVault()
        val (vm, _, store) = newVm(policy, vault)
        vm.onApiKeyChange("unit-test-key-123")
        vm.save()
        awaitSettled(vm)
        assertTrue(vm.form.value.confirmKeyWrite)
        assertEquals(1, policy.freshCalls)
        assertEquals(0, vault.putKeys)
        assertEquals(0, vault.getKeys)
        vm.confirmKeyWriteSave()
        awaitSettled(vm)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertTrue(vm.form.value.saved)
        assertEquals(3, policy.freshCalls)
        assertEquals(listOf("key.write", "key.write", "key.write"), policy.actions)
        assertEquals(1, vault.putKeys)
        assertEquals(0, vault.getKeys)
        assertEquals(0, vault.deleteKeys)
        runBlocking {
            val ref = store.observe().first()?.apiKeyRef
            assertTrue(ref?.startsWith("provider_key/preset:openai#staged-") == true)
            assertTrue(vault.hasKey(SetupValidation.aliasFromRef(ref!!)))
            assertTrue(store.hasMetadata())
        }
    }

    @Test
    fun keyReadThrowingNeverTriggered() {
        // The throwing key.read evaluation is unreachable: the staged path
        // performs zero key.read evaluations, so the save succeeds.
        val policy = ThrowingActionPolicy(throwAction = "key.read", fallback = Verdict.ALLOW)
        val vault = CountingVault()
        val (vm, _, store) = newVm(policy, vault)
        vm.onApiKeyChange("unit-test-key-123")
        vm.save()
        awaitSettled(vm)
        assertTrue(vm.form.value.saved)
        assertEquals(1, vault.putKeys)
        assertEquals(0, vault.getKeys)
        assertEquals(0, vault.deleteKeys)
        assertEquals(2, policy.freshCalls)
        assertEquals(listOf("key.write", "key.write"), policy.actions)
        runBlocking {
            assertTrue(store.hasMetadata())
        }
    }

    @Test
    fun keyReadAskStillSaves() {
        val policy = MapActionPolicy(mapOf("key.write" to Verdict.ALLOW, "key.read" to Verdict.ASK))
        val vault = CountingVault()
        val (vm, _, store) = newVm(policy, vault)
        vm.onApiKeyChange("unit-test-key-123")
        vm.save()
        awaitSettled(vm)
        assertTrue(vm.form.value.saved)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertEquals(2, policy.freshCalls)
        assertEquals(listOf("key.write", "key.write"), policy.actions)
        assertEquals(1, vault.putKeys)
        assertEquals(0, vault.getKeys)
        assertEquals(0, vault.deleteKeys)
        runBlocking {
            val ref = store.observe().first()?.apiKeyRef
            assertTrue(ref?.startsWith("provider_key/preset:openai#staged-") == true)
            assertTrue(vault.hasKey(SetupValidation.aliasFromRef(ref!!)))
            assertTrue(store.hasMetadata())
        }
        assertVaultKeyEquals(
            vault,
            SetupValidation.aliasFromRef(runBlocking { store.observe().first()!!.apiKeyRef }),
            "unit-test-key-123",
        )
    }

    @Test
    fun keyReadAllowRotationSucceeds() {
        val policy = MapActionPolicy(mapOf("key.write" to Verdict.ALLOW, "key.read" to Verdict.ALLOW))
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val vault = CountingVault(delegate)
        val (vm, _, store) = newVm(policy, vault)
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseedConfig())
            assertEquals(1L, store.currentRevision())
        }
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        awaitSettled(vm)
        assertTrue(vm.form.value.saved)
        assertEquals(2, policy.freshCalls)
        assertEquals(listOf("key.write", "key.write"), policy.actions)
        assertEquals(1, vault.putKeys)
        assertEquals(0, vault.getKeys)
        // Rotation retires the predecessor live alias.
        assertEquals(1, vault.deleteKeys)
        assertEquals(listOf("preset:openai"), vault.deletedIds)
        val winnerRef = runBlocking { store.observe().first()!!.apiKeyRef }
        assertTrue(winnerRef.startsWith("provider_key/preset:openai#staged-"))
        assertVaultKeyEquals(vault, SetupValidation.aliasFromRef(winnerRef), "unit-test-new-key-abcdef")
        runBlocking {
            assertEquals(2L, store.currentRevision())
            assertFalse(vault.hasKey("preset:openai"))
            assertEquals(EndpointReadiness.READY, store.readiness(vault))
        }
    }

    // ---- P1-2: versioned/staged vault alias ----

    /**
     * Shared two-VM race rig: one store + one delegate vault, a blocking loser
     * VM and a counting winner VM. The loser snapshots first and blocks in its
     * staged put; the winner then commits fully; the loser resumes into a CAS
     * loss. Both vaults record per-operation staged aliases in
     * [CountingVault.putIds]/[BlockingVault.putIds].
     */
    private class SharedRace(
        val store: EndpointStore,
        val delegate: EncryptedPrefsVault,
        val vmLoser: SetupViewModel,
        val vaultLoser: BlockingVault,
        val policyLoser: MutablePolicy,
        val vmWinner: SetupViewModel,
        val vaultWinner: CountingVault,
        val policyWinner: MutablePolicy,
    )

    private fun newSharedRace(dirPrefix: String): SharedRace {
        val dir = Files.createTempDirectory(dirPrefix).toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val store = EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "$dirPrefix.preferences_pb") },
            ),
        )
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val policyLoser = MutablePolicy(Verdict.ALLOW)
        val policyWinner = MutablePolicy(Verdict.ALLOW)
        val vaultLoser = BlockingVault(delegate)
        val vaultWinner = CountingVault(delegate)
        val noProvider: (dev.librepocket.provider.ProviderConfig, dev.librepocket.provider.KeyProvider) -> LlmProvider =
            { _, _ -> error("no provider needed") as LlmProvider }
        val vmLoser = SetupViewModel(store, VaultSource { vaultLoser }, policyLoser, buildProvider = noProvider)
        val vmWinner = SetupViewModel(store, VaultSource { vaultWinner }, policyWinner, buildProvider = noProvider)
        return SharedRace(store, delegate, vmLoser, vaultLoser, policyLoser, vmWinner, vaultWinner, policyWinner)
    }

    @Test
    fun differentKeyFreshLoserKeepsWinner() {
        // Same provider (openai), DIFFERENT keys: the loser must never produce
        // metadata=B + key=A. Per-operation staged aliases keep the two attempts
        // disjoint in the vault; the metadata CAS decides, and the loser only
        // deletes its own staged alias.
        val race = newSharedRace("setup-diffkey-fresh")
        val store = race.store
        val delegate = race.delegate
        race.vmLoser.onApiKeyChange("unit-test-loser-key-AAAAAAAA")
        race.vmLoser.save()
        assertTrue(race.vaultLoser.entered.await(10, TimeUnit.SECONDS))
        race.vmWinner.onApiKeyChange("unit-test-winner-key-BBBBBBBB")
        race.vmWinner.save()
        awaitSettled(race.vmWinner)
        assertTrue(race.vmWinner.form.value.saved)
        assertEquals(2, race.policyWinner.freshCalls)
        race.vaultLoser.release.countDown()
        awaitSettled(race.vmLoser)
        assertEquals("POLICY_DENIED_KEY_WRITE", race.vmLoser.form.value.errorCode)
        assertFalse(race.vmLoser.form.value.saved)
        assertEquals(2, race.policyLoser.freshCalls)
        // Staged invariant: exactly one put per attempt, distinct per-operation
        // aliases (custom same-slot clobber is gone by construction), revision
        // advanced exactly once (fresh null -> 1), winner performs no delete.
        assertEquals(1, race.vaultWinner.putKeys)
        assertEquals(1, race.vaultLoser.putKeys)
        assertEquals(0, race.vaultWinner.deleteKeys)
        assertEquals(1, race.vaultLoser.deleteKeys)
        val winnerStaged = race.vaultWinner.putIds.single()
        val loserStaged = race.vaultLoser.putIds.single()
        assertTrue(winnerStaged != loserStaged)
        assertTrue(winnerStaged.startsWith("preset:openai#staged-"))
        assertEquals(listOf(loserStaged), race.vaultLoser.deletedIds)
        runBlocking {
            assertEquals(1L, store.currentRevision())
            val meta = store.observe().first()!!
            // Metadata points at the WINNER live alias...
            assertEquals("provider_key/$winnerStaged", meta.apiKeyRef)
            assertEquals("https://api.openai.com/v1", meta.baseUrl)
            // ...and the vault holds the WINNER key there: never metadata=B + key=A.
            assertTrue(delegate.hasKey(winnerStaged))
            assertFalse(delegate.hasKey(loserStaged))
            assertFalse(delegate.hasKey("preset:openai"))
            assertEquals(EndpointReadiness.READY, store.readiness(delegate))
        }
        assertVaultKeyEquals(
            delegate,
            SetupValidation.aliasFromRef(runBlocking { store.observe().first()!!.apiKeyRef }),
            "unit-test-winner-key-BBBBBBBB",
        )
    }

    @Test
    fun differentKeyCustomUrlMismatch() {
        // Same preset:custom slot, DIFFERENT baseUrl + key: the winner's
        // (baseUrl, key) pair must stay jointly consistent — metadata.baseUrl is
        // B's URL and the vault holds B's key. Under the fixed alias the loser
        // would have overwritten the live slot before losing the CAS.
        val race = newSharedRace("setup-diffkey-custom")
        val store = race.store
        val delegate = race.delegate
        val urlA = "https://custom-a.example.com/v1"
        val urlB = "https://custom-b.example.com/v1"
        race.vmLoser.selectPreset("custom")
        race.vmLoser.onBaseUrlChange(urlA)
        race.vmLoser.onApiKeyChange("unit-test-loser-key-AAAAAAAA")
        race.vmLoser.save()
        assertTrue(race.vaultLoser.entered.await(10, TimeUnit.SECONDS))
        race.vmWinner.selectPreset("custom")
        race.vmWinner.onBaseUrlChange(urlB)
        race.vmWinner.onApiKeyChange("unit-test-winner-key-BBBBBBBB")
        race.vmWinner.save()
        awaitSettled(race.vmWinner)
        assertTrue(race.vmWinner.form.value.saved)
        race.vaultLoser.release.countDown()
        awaitSettled(race.vmLoser)
        assertEquals("POLICY_DENIED_KEY_WRITE", race.vmLoser.form.value.errorCode)
        assertFalse(race.vmLoser.form.value.saved)
        val winnerStaged = race.vaultWinner.putIds.single()
        val loserStaged = race.vaultLoser.putIds.single()
        assertTrue(winnerStaged != loserStaged)
        runBlocking {
            assertEquals(1L, store.currentRevision())
            val meta = store.observe().first()!!
            assertEquals("preset:custom", meta.providerId)
            assertEquals(urlB, meta.baseUrl)
            assertEquals("provider_key/$winnerStaged", meta.apiKeyRef)
            assertTrue(delegate.hasKey(winnerStaged))
            assertFalse(delegate.hasKey(loserStaged))
            assertEquals(EndpointReadiness.READY, store.readiness(delegate))
        }
        assertVaultKeyEquals(
            delegate,
            SetupValidation.aliasFromRef(runBlocking { store.observe().first()!!.apiKeyRef }),
            "unit-test-winner-key-BBBBBBBB",
        )
    }

    @Test
    fun differentKeyHadKeyRotation() {
        // Preseeded predecessor + two racers with DIFFERENT new keys: the winner
        // owns metadata + vault + retire jointly; the loser performs no
        // restore-old (the predecessor stays retired by the winner, never
        // resurrected over the winner) and cleans only its own staged alias.
        val race = newSharedRace("setup-diffkey-rotation")
        val store = race.store
        val delegate = race.delegate
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseedConfig())
            assertEquals(1L, store.currentRevision())
        }
        race.vmLoser.onApiKeyChange("unit-test-loser-key-AAAAAAAA")
        race.vmLoser.save()
        assertTrue(race.vaultLoser.entered.await(10, TimeUnit.SECONDS))
        race.vmWinner.onApiKeyChange("unit-test-winner-key-BBBBBBBB")
        race.vmWinner.save()
        awaitSettled(race.vmWinner)
        assertTrue(race.vmWinner.form.value.saved)
        assertEquals(2, race.policyWinner.freshCalls)
        runBlocking { assertEquals(2L, store.currentRevision()) }
        race.vaultLoser.release.countDown()
        awaitSettled(race.vmLoser)
        assertEquals("POLICY_DENIED_KEY_WRITE", race.vmLoser.form.value.errorCode)
        assertFalse(race.vmLoser.form.value.saved)
        assertEquals(2, race.policyLoser.freshCalls)
        val winnerStaged = race.vaultWinner.putIds.single()
        val loserStaged = race.vaultLoser.putIds.single()
        assertTrue(winnerStaged != loserStaged)
        // Winner retires the predecessor; loser cleans only its own staged alias.
        assertEquals(listOf("preset:openai"), race.vaultWinner.deletedIds)
        assertEquals(listOf(loserStaged), race.vaultLoser.deletedIds)
        runBlocking {
            assertEquals(2L, store.currentRevision())
            val meta = store.observe().first()!!
            assertEquals("provider_key/$winnerStaged", meta.apiKeyRef)
            assertTrue(delegate.hasKey(winnerStaged))
            // No restore-old: the predecessor stays retired, the loser staged is gone.
            assertFalse(delegate.hasKey("preset:openai"))
            assertFalse(delegate.hasKey(loserStaged))
            assertEquals(EndpointReadiness.READY, store.readiness(delegate))
        }
        assertVaultKeyEquals(
            delegate,
            SetupValidation.aliasFromRef(runBlocking { store.observe().first()!!.apiKeyRef }),
            "unit-test-winner-key-BBBBBBBB",
        )
    }

    @Test
    fun logoutDeletesLiveStagedAliasAndClears() {
        // Commit, then logout: the live key addressed by metadata (a staged
        // alias after a staged commit) is deleted and metadata is cleared with
        // the revision kept advancing. Crash-orphaned staged aliases are
        // unknowable here (no vault list API) and documented as at-rest leak.
        val policy = MutablePolicy(Verdict.ALLOW)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val vault = CountingVault(delegate)
        val (vm, _, store) = newVm(policy, vault)
        vm.onApiKeyChange("fake-unit-logout-key-123456")
        vm.save()
        awaitSettled(vm)
        assertTrue(vm.form.value.saved)
        val liveAlias = SetupValidation.aliasFromRef(runBlocking { store.observe().first()!!.apiKeyRef })
        assertTrue(liveAlias.startsWith("preset:openai#staged-"))
        runBlocking {
            assertTrue(delegate.hasKey(liveAlias))
            assertEquals(EndpointReadiness.READY, store.readiness(vault))
        }
        var done = false
        vm.logout { done = it }
        val end = System.currentTimeMillis() + 8000
        while (!done || runBlocking { store.hasMetadata() }) {
            if (System.currentTimeMillis() > end) error("timed out waiting for logout")
            Thread.sleep(10)
        }
        assertNull(vm.form.value.errorCode)
        runBlocking {
            assertFalse(store.hasMetadata())
            assertFalse(delegate.hasKey(liveAlias))
            assertEquals(2L, store.currentRevision())
        }
        // Fresh save performed no delete; logout deleted exactly the live alias.
        assertEquals(listOf(liveAlias), vault.deletedIds)
    }

    @Test
    fun logoutReadFailureAbortsWithoutSideEffects() {
        // T-ReadFailure: the strict snapshot read throws. Logout must abort
        // before any side effect: no vault delete, no metadata clear
        // (updateData never called), no form reset, no success report.
        val policy = MutablePolicy(Verdict.ALLOW)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val vault = CountingVault(delegate)
        val failing = ReadFailingLogoutDataStore()
        val store = EndpointStore(failing)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = policy,
            buildProvider = { _, _ -> error("no provider needed") as LlmProvider },
        )
        vm.onApiKeyChange("unit-test-logout-read-fail-key")
        val done = CompletableDeferred<Boolean>()
        vm.logout { done.complete(it) }
        val ok = runBlocking { withTimeout(10_000) { done.await() } }
        assertFalse("read failure must not report success", ok)
        assertEquals("LOGOUT_READ_FAILED", vm.form.value.errorCode)
        // Abort preserves the form instead of resetting to a fresh state.
        assertEquals("unit-test-logout-read-fail-key", vm.form.value.apiKey)
        assertEquals(0, vault.deleteKeys)
        assertTrue(vault.deletedIds.isEmpty())
        assertEquals("no clear may be attempted after a read failure", 0, failing.updateCalls)
    }

    @Test
    fun logoutDeleteFailureAbortsBeforeClear() {
        // T-DeleteFailure: deleteKey throws. Logout must abort before the
        // fenced clear: metadata, key bytes, and revision stay untouched,
        // and failure (not success) is reported.
        val policy = MutablePolicy(Verdict.ALLOW)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val vault = ThrowingDeleteVault(delegate)
        val (vm, _, store) = newVm(policy, vault)
        vm.onApiKeyChange("fake-unit-logout-delete-fail-key-123")
        vm.save()
        awaitSettled(vm)
        assertTrue(vm.form.value.saved)
        // Fresh save performed no delete; the live key sits at a staged alias.
        assertEquals(0, vault.deleteAttempts)
        val liveAlias = SetupValidation.aliasFromRef(runBlocking { store.observe().first()!!.apiKeyRef })
        assertTrue(liveAlias.startsWith("preset:openai#staged-"))
        val revBefore = runBlocking { store.currentRevision() }
        val done = CompletableDeferred<Boolean>()
        vm.logout { done.complete(it) }
        val ok = runBlocking { withTimeout(10_000) { done.await() } }
        assertFalse("delete failure must not report success", ok)
        assertEquals("LOGOUT_DELETE_FAILED", vm.form.value.errorCode)
        assertEquals("logout must attempt the live alias", listOf(liveAlias), vault.deletedIds)
        runBlocking {
            assertTrue("metadata must survive a delete failure", store.hasMetadata())
            assertEquals(revBefore, store.currentRevision())
            assertEquals(
                "provider_key/$liveAlias",
                store.observe().first()!!.apiKeyRef,
            )
            assertTrue(delegate.hasKey(liveAlias))
            assertEquals(EndpointReadiness.READY, store.readiness(delegate))
        }
        assertVaultKeyEquals(delegate, liveAlias, "fake-unit-logout-delete-fail-key-123")
    }

    @Test
    fun logoutStaleLosesToConcurrentSave() {
        // T-StaleFenced: L snapshots (A, rev1) and parks inside deleteKey(A);
        // S commits B (rev2); L resumes into a CAS loss. L deleted only the
        // stale predecessor (harmless — S already retired it), B metadata
        // survives, and L reports STALE instead of success.
        val dir = Files.createTempDirectory("setup-logout-stale").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val store = EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "logout-stale.preferences_pb") },
            ),
        )
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            store.save(preseedConfig())
            assertEquals(1L, store.currentRevision())
        }
        val noProvider: (dev.librepocket.provider.ProviderConfig, dev.librepocket.provider.KeyProvider) -> LlmProvider =
            { _, _ -> error("no provider needed") as LlmProvider }
        val logoutVault = DeleteGatingLogoutVault(delegate)
        val vmLogout = SetupViewModel(
            store,
            VaultSource { logoutVault },
            MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        val saveVault = CountingVault(delegate)
        val vmSave = SetupViewModel(
            store,
            VaultSource { saveVault },
            MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        val logoutDone = CompletableDeferred<Boolean>()
        vmLogout.logout { logoutDone.complete(it) }
        // L has snapshot (A, rev1) and is parked inside deleteKey(A).
        runBlocking { withTimeout(10_000) { logoutVault.deleteEntered.await() } }
        assertEquals(listOf("preset:openai"), logoutVault.deletedIds)
        // S publishes B while L is parked (per-VM mutexes do not exclude
        // cross-VM writers; the metadata CAS is the fence).
        vmSave.onApiKeyChange("unit-test-winner-key-BBBBBBBB")
        vmSave.save()
        awaitSettled(vmSave)
        assertTrue(vmSave.form.value.saved)
        val winnerStaged = saveVault.putIds.single()
        assertTrue(winnerStaged.startsWith("preset:openai#staged-"))
        runBlocking { assertEquals(2L, store.currentRevision()) }
        // Release L into the CAS loss.
        logoutVault.deleteRelease.complete(Unit)
        val ok = runBlocking { withTimeout(10_000) { logoutDone.await() } }
        assertFalse("stale logout must not report success", ok)
        assertEquals("LOGOUT_STALE", vmLogout.form.value.errorCode)
        runBlocking {
            assertEquals(2L, store.currentRevision())
            val meta = store.observe().first()!!
            assertEquals("provider_key/$winnerStaged", meta.apiKeyRef)
            assertTrue("winner key must survive the stale logout", delegate.hasKey(winnerStaged))
            assertEquals(EndpointReadiness.READY, store.readiness(delegate))
        }
        assertVaultKeyEquals(delegate, winnerStaged, "unit-test-winner-key-BBBBBBBB")
    }

    @Test
    fun legitClearStillDeletesOwnOrphan() {
        val policy = MutablePolicy(Verdict.ALLOW)
        val vault = BlockingVault()
        val (vm, _, store) = newVm(policy, vault)
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        assertTrue(vault.entered.await(10, TimeUnit.SECONDS))
        runBlocking { store.clear() }
        vault.release.countDown()
        awaitSettled(vm)
        assertEquals("POLICY_DENIED_KEY_WRITE", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertEquals(2, policy.freshCalls)
        assertEquals(1, vault.putKeys)
        // No live read exists on the staged path: zero gets, one own-staged delete.
        assertEquals(0, vault.getKeys)
        assertEquals(1, vault.deleteKeys)
        runBlocking {
            assertFalse(vault.hasKey("preset:openai"))
            assertFalse(store.hasMetadata())
            assertEquals(1L, store.currentRevision())
        }
    }

    @Test
    fun restartMintNeverReusesCommittedLiveAlias() {
        // Regression: a process-local counter nonce restarts at zero after a
        // relaunch, so the second round's `put staged-1` overwrites the first
        // round's already-committed live `staged-1` in place (and a later
        // abort/retire then deletes live bytes = loss; the retire inequality
        // guard also misfires when the alias is reused). Staged aliases must
        // therefore be restart-unique (UUID suffix): with the counter scheme
        // this test collides, with UUID it passes.
        //
        // Restart simulation: one shared store + one shared delegate vault, a
        // first ViewModel commits round 1, then a NEW ViewModel instance on
        // the SAME store/vault commits round 2 with a different key (the
        // relaunch: fresh ViewModel, same persisted metadata + vault). A
        // best-effort reflection reset of the legacy static counter (no-op now
        // that the counter is gone) reproduces the restart-zeroing on the old
        // code so the test collides there and passes here.
        val dir = Files.createTempDirectory("setup-restart-nonce").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val store = EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "restart-nonce.preferences_pb") },
            ),
        )
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val noProvider: (dev.librepocket.provider.ProviderConfig, dev.librepocket.provider.KeyProvider) -> LlmProvider =
            { _, _ -> error("no provider needed") as LlmProvider }

        // Round 1: fresh save commits staged-X; metadata points at X.
        val policy1 = MutablePolicy(Verdict.ALLOW)
        val vault1 = CountingVault(delegate)
        val vm1 = SetupViewModel(store, VaultSource { vault1 }, policy1, buildProvider = noProvider)
        vm1.onApiKeyChange("unit-test-first-key-AAAAAAAA")
        vm1.save()
        awaitSettled(vm1)
        assertTrue(vm1.form.value.saved)
        assertEquals(2, policy1.freshCalls)
        val firstRef = runBlocking { store.observe().first()!!.apiKeyRef }
        assertTrue(firstRef.startsWith("provider_key/preset:openai#staged-"))
        val firstAlias = SetupValidation.aliasFromRef(firstRef)
        assertVaultKeyEquals(delegate, firstAlias, "unit-test-first-key-AAAAAAAA")
        runBlocking { assertEquals(1L, store.currentRevision()) }
        // Fresh save retires nothing.
        assertEquals(0, vault1.deleteKeys)

        // Simulate process restart: zero the legacy static counter if it still
        // exists (old code). On the UUID scheme there is no such field and
        // this is a no-op — the second mint is distinct regardless.
        try {
            val candidates = listOfNotNull(
                runCatching { SetupViewModel::class.java.getDeclaredField("stagedNonceSeq") }.getOrNull(),
                runCatching {
                    SetupViewModel::class.java.declaredClasses
                        .firstOrNull { it.simpleName == "Companion" }
                        ?.getDeclaredField("stagedNonceSeq")
                }.getOrNull(),
            )
            for (f in candidates) {
                f.isAccessible = true
                val holder = if (java.lang.reflect.Modifier.isStatic(f.modifiers)) {
                    null
                } else {
                    SetupViewModel::class.java.getField("Companion").get(null)
                }
                (f.get(holder) as? java.util.concurrent.atomic.AtomicLong)?.set(0L)
            }
        } catch (_: Exception) {
            // No legacy counter (UUID scheme): nothing to reset.
        }

        // Round 2: NEW ViewModel on the SAME store/vault, different key.
        val policy2 = MutablePolicy(Verdict.ALLOW)
        val vault2 = CountingVault(delegate)
        val vm2 = SetupViewModel(store, VaultSource { vault2 }, policy2, buildProvider = noProvider)
        vm2.onApiKeyChange("unit-test-second-key-BBBBBBBB")
        vm2.save()
        awaitSettled(vm2)
        assertTrue(vm2.form.value.saved)
        assertEquals(2, policy2.freshCalls)
        val secondRef = runBlocking { store.observe().first()!!.apiKeyRef }
        assertTrue(secondRef.startsWith("provider_key/preset:openai#staged-"))
        val secondAlias = SetupValidation.aliasFromRef(secondRef)

        // Core restart-uniqueness: the second staged alias MUST differ from
        // the committed live alias (counter+reset would mint it identical).
        assertTrue("restart reused live staged alias: $secondAlias", firstAlias != secondAlias)
        // No clobber: the second put went to its own alias, never overwrote
        // the first live slot in place.
        assertEquals(listOf(secondAlias), vault2.putIds)
        assertFalse(vault2.putIds.contains(firstAlias))
        // Old live correctly retired (deleted) after the commit — not
        // overwritten, not leaked, and never the new live key.
        assertEquals(listOf(firstAlias), vault2.deletedIds)
        runBlocking {
            assertEquals(2L, store.currentRevision())
            assertEquals("provider_key/$secondAlias", store.observe().first()!!.apiKeyRef)
            assertTrue(delegate.hasKey(secondAlias))
            assertFalse(delegate.hasKey(firstAlias))
            assertEquals(EndpointReadiness.READY, store.readiness(delegate))
        }
        assertVaultKeyEquals(delegate, secondAlias, "unit-test-second-key-BBBBBBBB")
    }

    // ---- Staged alias collision guard (full-UUID 32-hex + bounded redraw) ----

    @Test
    fun stagedCollisionRedrawSucceedsWithoutOverwritingLive() {
        // Deterministic collision: the injected provider's first candidate
        // equals the snapshot live alias; the bounded inequality guard must
        // redraw before any vault write, so the put never overwrites live in
        // place. Success path: second candidate is fresh, CAS commits, the
        // predecessor is retired (deleted) and the new live key survives.
        val liveAlias = "preset:openai"
        val freshAlias = "preset:openai#staged-" + "a".repeat(32)
        val candidates = java.util.ArrayDeque(listOf(liveAlias, freshAlias))
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey(liveAlias, "unit-test-old-key-123456".toCharArray())
        }
        val policy = MutablePolicy(Verdict.ALLOW)
        val vault = CountingVault(delegate)
        val (vm, _, store) = newVm(
            policy,
            vault,
            stagedAliasProvider = { _ -> candidates.pollFirst() ?: SetupValidation.newStagedAlias("preset:openai") },
        )
        runBlocking {
            store.save(preseedConfig())
            assertEquals(1L, store.currentRevision())
        }
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        awaitSettled(vm)
        assertTrue(vm.form.value.saved)
        assertNull(vm.form.value.errorCode)
        assertEquals(2, policy.freshCalls)
        // Put went to the redrawn alias only — live was never overwritten.
        assertEquals(listOf(freshAlias), vault.putIds)
        assertFalse(vault.putIds.contains(liveAlias))
        // New live bytes survive; retire deleted only the predecessor, never
        // the new live key.
        assertVaultKeyEquals(delegate, freshAlias, "unit-test-new-key-abcdef")
        assertEquals(listOf(liveAlias), vault.deletedIds)
        assertFalse(vault.deletedIds.contains(freshAlias))
        runBlocking {
            assertEquals(2L, store.currentRevision())
            assertEquals("provider_key/$freshAlias", store.observe().first()!!.apiKeyRef)
            assertTrue(delegate.hasKey(freshAlias))
            assertFalse(delegate.hasKey(liveAlias))
            assertEquals(EndpointReadiness.READY, store.readiness(delegate))
        }
    }

    @Test
    fun stagedPersistentCollisionFailsClosedWithoutTouchingLive() {
        // Persistent collision: every mint equals the snapshot live alias.
        // The bounded guard exhausts MAX_STAGED_MINT_ATTEMPTS and fails closed
        // with POLICY_DENIED before any vault I/O (no put, no delete, no
        // hasKey): live bytes survive and metadata is untouched.
        val liveAlias = "preset:openai"
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey(liveAlias, "unit-test-old-key-123456".toCharArray())
        }
        val policy = MutablePolicy(Verdict.ALLOW)
        val vault = CountingVault(delegate)
        val (vm, _, store) = newVm(
            policy,
            vault,
            stagedAliasProvider = { _ -> liveAlias },
        )
        runBlocking {
            store.save(preseedConfig())
            assertEquals(1L, store.currentRevision())
        }
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        awaitSettled(vm)
        assertEquals("POLICY_DENIED_KEY_WRITE", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        // Fail-closed before V2/put/CAS: only the pre-write V1 ran.
        assertEquals(1, policy.freshCalls)
        // Never overwrote live, never deleted live (zero vault mutation, zero
        // existence probe — the inequality guard fails before hasKey).
        assertTrue(vault.putIds.isEmpty())
        assertTrue(vault.deletedIds.isEmpty())
        assertFalse(vault.deletedIds.contains(liveAlias))
        assertEquals(0, vault.hasKeys)
        assertVaultKeyEquals(delegate, liveAlias, "unit-test-old-key-123456")
        runBlocking {
            assertEquals(1L, store.currentRevision())
            assertEquals("provider_key/$liveAlias", store.observe().first()!!.apiKeyRef)
            assertTrue(delegate.hasKey(liveAlias))
        }
    }

    @Test
    fun stagedCollisionDenyVariantFailsBeforeMint() {
        // DENY variant: V1 DENY wins before any mint/guard/vault side effect,
        // even when the injected first candidate would collide with live.
        val liveAlias = "preset:openai"
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey(liveAlias, "unit-test-old-key-123456".toCharArray())
        }
        val policy = MutablePolicy(Verdict.DENY)
        val vault = CountingVault(delegate)
        val (vm, _, store) = newVm(
            policy,
            vault,
            stagedAliasProvider = { _ -> liveAlias },
        )
        runBlocking {
            store.save(preseedConfig())
            assertEquals(1L, store.currentRevision())
        }
        vm.onApiKeyChange("unit-test-new-key-abcdef")
        vm.save()
        awaitSettled(vm)
        assertEquals("POLICY_DENIED_KEY_WRITE", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertEquals(1, policy.freshCalls)
        assertTrue(vault.putIds.isEmpty())
        assertTrue(vault.deletedIds.isEmpty())
        assertVaultKeyEquals(delegate, liveAlias, "unit-test-old-key-123456")
        runBlocking {
            assertEquals(1L, store.currentRevision())
            assertEquals("provider_key/$liveAlias", store.observe().first()!!.apiKeyRef)
        }
    }
}
