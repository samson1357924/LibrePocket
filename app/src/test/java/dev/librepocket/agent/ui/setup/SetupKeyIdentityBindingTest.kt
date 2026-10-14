package dev.librepocket.agent.ui.setup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.keystore.KeyVault
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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

private class SpyBindingProvider(
    var models: List<String> = listOf("m1", "m2"),
) : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    @Volatile var calls = 0
    override fun stream(request: ChatRequest): Flow<StreamEvent> = emptyFlow()
    override suspend fun listModels(): List<String> {
        calls++
        return models
    }
}

/** KeyVault whose deleteKey always throws: forces a logout failure terminal (form retained). */
private class DeleteFailingVault(
    private val delegate: KeyVault,
) : KeyVault {
    override suspend fun putKey(providerId: String, apiKey: CharArray) =
        delegate.putKey(providerId, apiKey)
    override suspend fun getKey(providerId: String): CharArray? =
        delegate.getKey(providerId)
    override suspend fun deleteKey(providerId: String): Unit =
        throw IOException("DELETE_FAILED")
    override suspend fun hasKey(providerId: String): Boolean =
        delegate.hasKey(providerId)
}

/** Stage S3: typed key bound to endpoint identity (providerId + effectiveBaseUrl). */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupKeyIdentityBindingTest {

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
        fake: SpyBindingProvider,
        buildCalls: AtomicInteger = AtomicInteger(0),
        dirCalls: AtomicInteger = AtomicInteger(0),
        vault: CountingVault = CountingVault(EncryptedPrefsVault(InMemoryPrefs())),
    ): Triple<SetupViewModel, VerdictPolicy, EndpointStore> {
        val dir = Files.createTempDirectory("setup-keybind-test").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val store = EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "keybind.preferences_pb") },
            ),
        )
        val policy = VerdictPolicy(Verdict.ALLOW)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = policy,
            buildProvider = { _, _ ->
                buildCalls.incrementAndGet()
                fake
            },
            fetchDirectory = { _: String ->
                dirCalls.incrementAndGet()
                """{"openai":{"models":{"snap-a":{"reasoning":false,"tool_call":true}}}}"""
            },
        )
        return Triple(vm, policy, store)
    }

    /** Pinned live endpoint (openai preset shape) for prefill/KeepExisting/logout preseeds. */
    private fun openaiLive(): EndpointConfig = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "gpt-4o-mini",
        apiKeyRef = "provider_key/preset:openai",
    )

    /**
     * Single idle waiter for every S3 entry (probe testing / model loading /
     * save / logout-in-flight all reset their flag synchronously on entry
     * edits and clear it on terminal, so one disjunction covers all cases;
     * untouched flags stay false and the wait is a no-op).
     */
    private fun awaitIdle(vm: SetupViewModel, timeoutMs: Long = 8000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (
            vm.form.value.testing ||
            vm.form.value.modelsLoading ||
            vm.form.value.saving ||
            vm.form.value.logoutInFlight
        ) {
            if (System.currentTimeMillis() > end) error("timed out waiting for idle")
            Thread.sleep(10)
        }
    }

    @Test
    fun presetSwitchProbeZeroRequests() {
        val fake = SpyBindingProvider()
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls)
        vm.onApiKeyChange("unit-test-key-123")
        vm.selectPreset("anthropic")
        // UX: key text retained, binding kept for mismatch detection.
        assertEquals("unit-test-key-123", vm.form.value.apiKey)
        vm.testConnection()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertNull(vm.form.value.testModels)
        assertFalse(vm.form.value.testing)
        assertEquals(0, fake.calls)
        assertEquals(0, buildCalls.get())
        assertEquals(0, dirCalls.get())
        assertEquals(0, policy.freshCalls)
    }

    @Test
    fun customPathOnlyRefreshZeroRequests() {
        val fake = SpyBindingProvider(models = listOf("live-x"))
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls)
        vm.selectPreset("custom")
        vm.onBaseUrlChange("http://127.0.0.1:11434/v1")
        vm.onApiKeyChange("unit-test-key-123")
        vm.onBaseUrlChange("http://127.0.0.1:11434/v2")
        assertEquals("unit-test-key-123", vm.form.value.apiKey)
        vm.refreshModels()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertTrue(vm.form.value.modelOptions.isEmpty())
        assertEquals(0, fake.calls)
        assertEquals(0, buildCalls.get())
        assertEquals(0, dirCalls.get())
        assertEquals(0, policy.freshCalls)
    }

    @Test
    fun presetSwitchSaveZeroVaultWrite() {
        val fake = SpyBindingProvider()
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val (vm, policy, store) = newVm(fake, AtomicInteger(0), AtomicInteger(0), vault)
        vm.onApiKeyChange("unit-test-key-123")
        vm.selectPreset("anthropic")
        vm.save()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertEquals(0, vault.putKeys)
        assertEquals(0, vault.getKeys)
        assertEquals(0, vault.deleteKeys)
        assertEquals(0, vault.hasKeys)
        assertEquals(0, policy.freshCalls)
        runBlocking { assertFalse(store.hasMetadata()) }
    }

    @Test
    fun backSwitchWithoutRepasteAllows() {
        val fake = SpyBindingProvider()
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls)
        vm.onApiKeyChange("unit-test-key-123")
        vm.selectPreset("anthropic")
        vm.selectPreset("openai")
        vm.testConnection()
        awaitIdle(vm)
        assertNull(vm.form.value.errorCode)
        assertEquals(2, vm.form.value.testModels)
        assertEquals(1, fake.calls)
        assertEquals(1, buildCalls.get())
        // testConnection never fetches the model directory.
        assertEquals(0, dirCalls.get())
        // Probe double-gate: entry fence + pre-IO re-check.
        assertEquals(2, policy.freshCalls)
        assertEquals("provider.call", policy.lastAction)
        assertEquals("preset:openai", policy.lastResource)
    }

    @Test
    fun repasteRebindsToNewIdentity() {
        val fake = SpyBindingProvider()
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls)
        vm.onApiKeyChange("unit-test-key-AAAA-123")
        vm.selectPreset("anthropic")
        // Rebind to B; returning to A must fail until re-pasted.
        vm.onApiKeyChange("unit-test-key-BBBB-123")
        vm.selectPreset("openai")
        vm.testConnection()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertEquals(0, fake.calls)
        assertEquals(0, buildCalls.get())
        assertEquals(0, dirCalls.get())
        assertEquals(0, policy.freshCalls)
        // Re-paste for A restores the binding.
        vm.onApiKeyChange("unit-test-key-AAAA-123")
        vm.testConnection()
        awaitIdle(vm)
        assertNull(vm.form.value.errorCode)
        assertEquals(2, vm.form.value.testModels)
        assertEquals(1, fake.calls)
        assertEquals(1, buildCalls.get())
        assertEquals(0, dirCalls.get())
        assertEquals(2, policy.freshCalls)
        assertEquals("provider.call", policy.lastAction)
        assertEquals("preset:openai", policy.lastResource)
    }

    @Test
    fun prefillPasteThenUrlChangeTestZeroRequests() {
        val fake = SpyBindingProvider()
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        // Preseed the delegate (not the counting wrapper) so setup I/O stays
        // out of the zero-request assertions below.
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        val custom = EndpointConfig(
            providerId = "preset:custom",
            presetId = "custom",
            label = "自訂",
            baseUrl = "http://127.0.0.1:11434/v1",
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            model = "local",
            apiKeyRef = "provider_key/preset:custom",
        )
        runBlocking {
            delegate.putKey("preset:custom", "test-key-123".toCharArray())
        }
        val vault = CountingVault(delegate)
        val (vm, policy, store) = newVm(fake, buildCalls, dirCalls, vault)
        runBlocking { store.save(custom) }
        vm.prefillForEdit(custom)
        assertEquals("", vm.form.value.apiKey)
        vm.onApiKeyChange("unit-test-key-new-123456")
        vm.onBaseUrlChange("http://127.0.0.1:11434/v2")
        assertEquals("unit-test-key-new-123456", vm.form.value.apiKey)
        vm.testConnection()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertEquals(0, fake.calls)
        assertEquals(0, buildCalls.get())
        assertEquals(0, dirCalls.get())
        assertEquals(0, policy.freshCalls)
    }

    @Test
    fun modelChangeKeepsBinding() {
        val fake = SpyBindingProvider()
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls)
        vm.onApiKeyChange("unit-test-key-123")
        vm.onModelChange("my-custom-model")
        vm.testConnection()
        awaitIdle(vm)
        assertNull(vm.form.value.errorCode)
        assertEquals(2, vm.form.value.testModels)
        assertEquals(1, fake.calls)
        assertEquals(1, buildCalls.get())
        // testConnection never fetches the model directory.
        assertEquals(0, dirCalls.get())
        assertEquals(2, policy.freshCalls)
        assertEquals("provider.call", policy.lastAction)
        assertEquals("preset:openai", policy.lastResource)
    }

    @Test
    fun refreshModelsSuccessListsLiveModels() {
        val fake = SpyBindingProvider(models = listOf("live-x"))
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls)
        vm.onApiKeyChange("unit-test-key-123")
        vm.refreshModels()
        awaitIdle(vm)
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.modelsLoading)
        assertEquals(1, fake.calls)
        assertEquals(1, buildCalls.get())
        assertEquals(1, dirCalls.get())
        // Probe triple-gate: entry fence + pre-IO re-check + post-live re-gate.
        assertEquals(3, policy.freshCalls)
        assertEquals("provider.call", policy.lastAction)
        assertEquals("preset:openai", policy.lastResource)
        assertTrue(vm.form.value.modelOptions.contains("live-x"))
        assertEquals(ModelDirectoryStatus.Remote, vm.form.value.modelDirectoryStatus)
    }

    @Test
    fun shortKeyMismatchReportsIdentityChanged() {
        // S3 precedes validation (aligned with save): a stale binding
        // deterministically reports SETUP_KEY_IDENTITY_CHANGED even for a
        // short key, so the user is told to re-paste rather than fix length.
        val fake = SpyBindingProvider()
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls)
        vm.onApiKeyChange("short")
        vm.selectPreset("anthropic")
        vm.testConnection()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertEquals(0, fake.calls)
        assertEquals(0, buildCalls.get())
        assertEquals(0, dirCalls.get())
        assertEquals(0, policy.freshCalls)
        vm.refreshModels()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertTrue(vm.form.value.modelOptions.isEmpty())
        assertEquals(0, fake.calls)
        assertEquals(0, buildCalls.get())
        assertEquals(0, dirCalls.get())
        assertEquals(0, policy.freshCalls)
    }

    @Test
    fun blankKeyBypassKeepsExisting() {
        // Blank key bypasses S3 (KeepExisting path): prefill an endpoint, keep
        // the key field blank, and save — must reuse the stored key, never
        // report SETUP_KEY_IDENTITY_CHANGED.
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "stored-key-12345".toCharArray())
        }
        val vault = CountingVault(delegate)
        val fake = SpyBindingProvider()
        val (vm, policy, store) = newVm(fake, AtomicInteger(0), AtomicInteger(0), vault)
        val live = openaiLive()
        runBlocking { store.save(live) }
        vm.prefillForEdit(live)
        assertEquals("", vm.form.value.apiKey)
        vm.save()
        awaitIdle(vm)
        assertNull(vm.form.value.errorCode)
        assertTrue(vm.form.value.saved)
        assertFalse(vm.form.value.confirmKeyWrite)
        // Metadata-only commit: zero staged writes/reads/deletes, one
        // existence-only hasKey precondition.
        assertEquals(0, vault.putKeys)
        assertEquals(0, vault.getKeys)
        assertEquals(0, vault.deleteKeys)
        assertEquals(1, vault.hasKeys)
        // KeepExisting commit: V1 + VCommit, both key.write ALLOW.
        assertEquals(2, policy.freshCalls)
        assertEquals("key.write", policy.lastAction)
        assertEquals("preset:openai", policy.lastResource)
        runBlocking {
            assertEquals("provider_key/preset:openai", store.observe().first()?.apiKeyRef)
        }
    }

    @Test
    fun logoutClearsBindingRequiresRepaste() {
        // Seed a live endpoint, then fail the teardown delete: the entry
        // already dropped the binding while the LOGOUT_DELETE_FAILED terminal
        // keeps the form — the retained text must fail closed until re-pasted.
        // (A successful logout resets the whole form instead; there the blank
        // field trivially requires a paste via validation, not via S3.)
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "stored-key-12345".toCharArray())
        }
        val vault = CountingVault(DeleteFailingVault(delegate))
        val fake = SpyBindingProvider()
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val (vm, policy, store) = newVm(fake, buildCalls, dirCalls, vault)
        runBlocking {
            store.save(openaiLive())
        }
        vm.onApiKeyChange("unit-test-key-123")
        vm.logout()
        awaitIdle(vm)
        assertFalse(vm.form.value.logoutInFlight)
        assertTrue(vm.form.value.errorCode?.startsWith("LOGOUT_") == true)
        // Failure terminal retains the typed text; the binding is gone.
        assertEquals("unit-test-key-123", vm.form.value.apiKey)
        vm.testConnection()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertEquals(0, fake.calls)
        assertEquals(0, buildCalls.get())
        assertEquals(0, dirCalls.get())
        assertEquals(0, policy.freshCalls)
    }

    @Test
    fun retryClearsBindingRequiresRepaste() {
        val fake = SpyBindingProvider()
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls)
        vm.onApiKeyChange("unit-test-key-123")
        // Empty store: pending absent + no metadata → StaleGone terminal keeps
        // the form — same binding drop as logout.
        vm.retryPendingDelete(PendingDelete(alias = "preset:openai", revision = null, timestampMs = 0))
        awaitIdle(vm)
        assertFalse(vm.form.value.logoutInFlight)
        assertEquals("unit-test-key-123", vm.form.value.apiKey)
        vm.testConnection()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertEquals(0, fake.calls)
        assertEquals(0, buildCalls.get())
        assertEquals(0, dirCalls.get())
        assertEquals(0, policy.freshCalls)
    }

    @Test
    fun whitespaceAppendProbeStillBlocked() {
        // S4-B: paste K for A → switch B (blocked) → trailing-space edit
        // (trimmed hash unchanged) must NOT rebind — stays
        // SETUP_KEY_IDENTITY_CHANGED with zero I/O.
        val fake = SpyBindingProvider()
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls)
        vm.onApiKeyChange("unit-test-key-123")
        vm.selectPreset("anthropic")
        vm.onApiKeyChange("unit-test-key-123 ")
        // Text retained (S3 UX); binding kept for mismatch detection.
        assertEquals("unit-test-key-123 ", vm.form.value.apiKey)
        vm.testConnection()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertNull(vm.form.value.testModels)
        assertEquals(0, fake.calls)
        assertEquals(0, buildCalls.get())
        assertEquals(0, dirCalls.get())
        assertEquals(0, policy.freshCalls)
    }

    @Test
    fun whitespaceAppendRefreshStillBlocked() {
        // S4-B refreshModels entry: same whitespace bypass shape via custom
        // URL switch — stays blocked with zero live/directory/policy I/O.
        val fake = SpyBindingProvider(models = listOf("live-x"))
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls)
        vm.selectPreset("custom")
        vm.onBaseUrlChange("http://127.0.0.1:11434/v1")
        vm.onApiKeyChange("unit-test-key-123")
        vm.onBaseUrlChange("http://127.0.0.1:11434/v2")
        vm.onApiKeyChange("unit-test-key-123   ")
        assertEquals("unit-test-key-123   ", vm.form.value.apiKey)
        vm.refreshModels()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertTrue(vm.form.value.modelOptions.isEmpty())
        assertEquals(0, fake.calls)
        assertEquals(0, buildCalls.get())
        assertEquals(0, dirCalls.get())
        assertEquals(0, policy.freshCalls)
    }

    @Test
    fun clearReinsertSameKeyProbeStillBlocked() {
        // S4-B: paste K → switch B → clear to blank (binding dropped, hash
        // retained) → reinsert same K (hash unchanged, no rebind) — stays
        // blocked with zero I/O. Only a different key rebinds.
        val fake = SpyBindingProvider()
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls)
        vm.onApiKeyChange("unit-test-key-123")
        vm.selectPreset("anthropic")
        vm.onApiKeyChange("")
        assertEquals("", vm.form.value.apiKey)
        vm.onApiKeyChange("unit-test-key-123")
        assertEquals("unit-test-key-123", vm.form.value.apiKey)
        vm.testConnection()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertNull(vm.form.value.testModels)
        assertEquals(0, fake.calls)
        assertEquals(0, buildCalls.get())
        assertEquals(0, dirCalls.get())
        assertEquals(0, policy.freshCalls)
    }

    @Test
    fun whitespaceAppendSaveZeroVaultWrite() {
        // S4-B save entry: whitespace bypass shape — zero vault writes,
        // zero vault reads, no consent mint, no metadata.
        val fake = SpyBindingProvider()
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val (vm, policy, store) = newVm(fake, AtomicInteger(0), AtomicInteger(0), vault)
        vm.onApiKeyChange("unit-test-key-123")
        vm.selectPreset("anthropic")
        vm.onApiKeyChange("unit-test-key-123 ")
        vm.save()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertEquals(0, vault.putKeys)
        assertEquals(0, vault.getKeys)
        assertEquals(0, vault.deleteKeys)
        assertEquals(0, vault.hasKeys)
        assertEquals(0, policy.freshCalls)
        runBlocking { assertFalse(store.hasMetadata()) }
    }

    @Test
    fun clearReinsertSameKeySaveZeroVaultWrite() {
        // S4-B save entry: clear→reinsert same K — same blocked shape as the
        // probe above, with vault-zero assertions.
        val fake = SpyBindingProvider()
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val (vm, policy, store) = newVm(fake, AtomicInteger(0), AtomicInteger(0), vault)
        vm.onApiKeyChange("unit-test-key-123")
        vm.selectPreset("anthropic")
        vm.onApiKeyChange("")
        vm.onApiKeyChange("unit-test-key-123")
        vm.save()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertEquals(0, vault.putKeys)
        assertEquals(0, vault.getKeys)
        assertEquals(0, vault.deleteKeys)
        assertEquals(0, vault.hasKeys)
        assertEquals(0, policy.freshCalls)
        runBlocking { assertFalse(store.hasMetadata()) }
    }

    @Test
    fun identityChangedHasCopyText() {
        assertEquals(
            "金鑰與目前伺服器不符（貼上後切換了供應商／位址），未發出任何請求／寫入；同字串重貼（含僅加空白、清空後重貼同一字串）不會解除，請貼上不同金鑰觸發重綁（後再貼回亦可）或切回原伺服器。",
            setupErrorText("SETUP_KEY_IDENTITY_CHANGED"),
        )
    }
}
