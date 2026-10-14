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
import org.junit.Assert.assertNotNull
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

/**
 * S4-C 顯式重綁：typed key 綁定 endpoint 身份，身份切換永不自動重綁。
 *
 * 擋下 ⟺ T 非空 ∧ (B==null ∨ B!=I)，與內容無關（K→Kx 追加亦擋下，關閉 BLOCKER-1）。
 * 解除僅 switch-back／顯式 confirm／fresh 終端清 B 後重貼。每個負向斷言
 * fake.calls／buildCalls／dirCalls／policy.freshCalls／vault.* 全零。
 */
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

    private fun assertZeroProbeIo(
        fake: SpyBindingProvider,
        buildCalls: AtomicInteger,
        dirCalls: AtomicInteger,
        policy: VerdictPolicy,
        vault: CountingVault,
    ) {
        assertEquals(0, fake.calls)
        assertEquals(0, buildCalls.get())
        assertEquals(0, dirCalls.get())
        assertEquals(0, policy.freshCalls)
        assertEquals(0, vault.putKeys)
        assertEquals(0, vault.getKeys)
        assertEquals(0, vault.deleteKeys)
        assertEquals(0, vault.hasKeys)
    }

    @Test
    fun presetSwitchProbeZeroRequests() {
        val fake = SpyBindingProvider()
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls, vault)
        vm.onApiKeyChange("unit-test-key-123")
        vm.selectPreset("anthropic")
        // UX: key text retained, binding kept for mismatch detection.
        assertEquals("unit-test-key-123", vm.form.value.apiKey)
        vm.testConnection()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertTrue(vm.form.value.confirmRebind)
        assertNotNull(vm.form.value.rebindSummary)
        assertEquals("OpenAI", vm.form.value.rebindSummary?.oldLabel)
        assertEquals("Anthropic", vm.form.value.rebindSummary?.newLabel)
        assertNull(vm.form.value.testModels)
        assertFalse(vm.form.value.testing)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertZeroProbeIo(fake, buildCalls, dirCalls, policy, vault)
    }

    @Test
    fun customPathOnlyRefreshZeroRequests() {
        val fake = SpyBindingProvider(models = listOf("live-x"))
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls, vault)
        vm.selectPreset("custom")
        vm.onBaseUrlChange("http://127.0.0.1:11434/v1")
        vm.onApiKeyChange("unit-test-key-123")
        vm.onBaseUrlChange("http://127.0.0.1:11434/v2")
        assertEquals("unit-test-key-123", vm.form.value.apiKey)
        vm.refreshModels()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertTrue(vm.form.value.confirmRebind)
        assertNotNull(vm.form.value.rebindSummary)
        assertTrue(vm.form.value.modelOptions.isEmpty())
        assertZeroProbeIo(fake, buildCalls, dirCalls, policy, vault)
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
        assertTrue(vm.form.value.confirmRebind)
        assertNotNull(vm.form.value.rebindSummary)
        assertFalse(vm.form.value.saved)
        assertFalse(vm.form.value.saving)
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
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls, vault)
        vm.onApiKeyChange("unit-test-key-123")
        vm.selectPreset("anthropic")
        vm.selectPreset("openai")
        assertFalse(vm.form.value.confirmRebind)
        assertNull(vm.form.value.rebindSummary)
        vm.testConnection()
        awaitIdle(vm)
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.confirmRebind)
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
    fun differentKeyReplaceStillBlocked() {
        // S4-C: paste K for A → switch B → paste DIFFERENT key still blocks
        // (content-agnostic, no auto-rebind). Only explicit confirm unblocks.
        val fake = SpyBindingProvider()
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls, vault)
        vm.onApiKeyChange("unit-test-key-AAAA-123")
        vm.selectPreset("anthropic")
        vm.onApiKeyChange("unit-test-key-BBBB-123")
        assertEquals("unit-test-key-BBBB-123", vm.form.value.apiKey)
        vm.testConnection()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertTrue(vm.form.value.confirmRebind)
        assertNotNull(vm.form.value.rebindSummary)
        assertEquals("OpenAI", vm.form.value.rebindSummary?.oldLabel)
        assertEquals("Anthropic", vm.form.value.rebindSummary?.newLabel)
        assertZeroProbeIo(fake, buildCalls, dirCalls, policy, vault)
        // Explicit confirm rebinds to B; retry then sends to B with zero extra paste.
        vm.confirmRebind()
        assertFalse(vm.form.value.confirmRebind)
        assertNull(vm.form.value.rebindSummary)
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.testing)
        assertFalse(vm.form.value.saving)
        assertZeroProbeIo(fake, buildCalls, dirCalls, policy, vault)
        vm.testConnection()
        awaitIdle(vm)
        assertNull(vm.form.value.errorCode)
        assertEquals(2, vm.form.value.testModels)
        assertEquals(1, fake.calls)
        assertEquals(1, buildCalls.get())
        assertEquals(0, dirCalls.get())
        assertEquals(2, policy.freshCalls)
        assertEquals("provider.call", policy.lastAction)
        assertEquals("preset:anthropic", policy.lastResource)
    }

    @Test
    fun tailAppendKxProbeStillBlocked() {
        // BLOCKER-1: paste K for A → switch B → append x (Kx) must NOT rebind.
        val fake = SpyBindingProvider()
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls, vault)
        vm.onApiKeyChange("unit-test-key-123")
        vm.selectPreset("anthropic")
        vm.onApiKeyChange("unit-test-key-123x")
        assertEquals("unit-test-key-123x", vm.form.value.apiKey)
        vm.testConnection()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertTrue(vm.form.value.confirmRebind)
        assertNotNull(vm.form.value.rebindSummary)
        assertNull(vm.form.value.testModels)
        assertFalse(vm.form.value.testing)
        assertZeroProbeIo(fake, buildCalls, dirCalls, policy, vault)
    }

    @Test
    fun tailAppendKxRefreshStillBlocked() {
        val fake = SpyBindingProvider(models = listOf("live-x"))
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls, vault)
        vm.selectPreset("custom")
        vm.onBaseUrlChange("http://127.0.0.1:11434/v1")
        vm.onApiKeyChange("unit-test-key-123")
        vm.onBaseUrlChange("http://127.0.0.1:11434/v2")
        vm.onApiKeyChange("unit-test-key-123x")
        assertEquals("unit-test-key-123x", vm.form.value.apiKey)
        vm.refreshModels()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertTrue(vm.form.value.confirmRebind)
        assertTrue(vm.form.value.modelOptions.isEmpty())
        assertZeroProbeIo(fake, buildCalls, dirCalls, policy, vault)
    }

    @Test
    fun tailAppendKxSaveZeroVaultWrite() {
        val fake = SpyBindingProvider()
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val (vm, policy, store) = newVm(fake, buildCalls, dirCalls, vault)
        vm.onApiKeyChange("unit-test-key-123")
        vm.selectPreset("anthropic")
        vm.onApiKeyChange("unit-test-key-123x")
        vm.save()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertTrue(vm.form.value.confirmRebind)
        assertNotNull(vm.form.value.rebindSummary)
        assertFalse(vm.form.value.saved)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertEquals(0, vault.putKeys)
        assertEquals(0, vault.getKeys)
        assertEquals(0, vault.deleteKeys)
        assertEquals(0, vault.hasKeys)
        assertEquals(0, policy.freshCalls)
        assertEquals(0, buildCalls.get())
        assertEquals(0, dirCalls.get())
        runBlocking { assertFalse(store.hasMetadata()) }
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
        assertFalse(vm.form.value.confirmRebind)
        vm.onApiKeyChange("unit-test-key-new-123456")
        vm.onBaseUrlChange("http://127.0.0.1:11434/v2")
        assertEquals("unit-test-key-new-123456", vm.form.value.apiKey)
        vm.testConnection()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertTrue(vm.form.value.confirmRebind)
        assertZeroProbeIo(fake, buildCalls, dirCalls, policy, vault)
    }

    @Test
    fun modelChangeKeepsBinding() {
        val fake = SpyBindingProvider()
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls, vault)
        vm.onApiKeyChange("unit-test-key-123")
        vm.onModelChange("my-custom-model")
        assertFalse(vm.form.value.confirmRebind)
        vm.testConnection()
        awaitIdle(vm)
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.confirmRebind)
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
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls, vault)
        vm.onApiKeyChange("unit-test-key-123")
        vm.refreshModels()
        awaitIdle(vm)
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.confirmRebind)
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
        // S4-C precedes validation (aligned with save): a stale binding
        // deterministically reports SETUP_KEY_IDENTITY_CHANGED＋dialog even for a
        // short key, so the user confirms rebind rather than fixing length.
        val fake = SpyBindingProvider()
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls, vault)
        vm.onApiKeyChange("short")
        vm.selectPreset("anthropic")
        vm.testConnection()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertTrue(vm.form.value.confirmRebind)
        assertZeroProbeIo(fake, buildCalls, dirCalls, policy, vault)
        // Dismiss so the next entry re-mints fresh (same mismatch, still dialog).
        vm.dismissRebindConfirm()
        assertFalse(vm.form.value.confirmRebind)
        vm.refreshModels()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertTrue(vm.form.value.confirmRebind)
        assertTrue(vm.form.value.modelOptions.isEmpty())
        assertZeroProbeIo(fake, buildCalls, dirCalls, policy, vault)
    }

    @Test
    fun shortKeyMismatchSaveMintsDialog() {
        val fake = SpyBindingProvider()
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val (vm, policy, store) = newVm(fake, buildCalls, dirCalls, vault)
        vm.onApiKeyChange("short")
        vm.selectPreset("anthropic")
        vm.save()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertTrue(vm.form.value.confirmRebind)
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
    fun blankKeyBypassKeepsExisting() {
        // Blank key bypasses S4-C (KeepExisting path): prefill an endpoint, keep
        // the key field blank, and save — must reuse the stored key, never
        // report SETUP_KEY_IDENTITY_CHANGED and never show the rebind dialog.
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
        assertFalse(vm.form.value.confirmRebind)
        assertNull(vm.form.value.rebindSummary)
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
        // keeps the form — the retained text must mint the rebind dialog
        // (fail closed) until confirmed or fresh-repasted.
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
        assertFalse(vm.form.value.confirmRebind)
        val logoutPolicyCalls = policy.freshCalls
        val logoutVaultPuts = vault.putKeys
        val logoutVaultHas = vault.hasKeys
        val logoutBuilds = buildCalls.get()
        vm.testConnection()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertTrue(vm.form.value.confirmRebind)
        assertNotNull(vm.form.value.rebindSummary)
        assertNull(vm.form.value.rebindSummary?.oldLabel)
        assertEquals(0, fake.calls)
        assertEquals(logoutBuilds, buildCalls.get())
        assertEquals(0, dirCalls.get())
        // Probe mint is zero-I/O beyond the logout's own calls.
        assertEquals(logoutPolicyCalls, policy.freshCalls)
        assertEquals(logoutVaultPuts, vault.putKeys)
        assertEquals(logoutVaultHas, vault.hasKeys)
    }

    @Test
    fun retryClearsBindingRequiresRepaste() {
        val fake = SpyBindingProvider()
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls, vault)
        vm.onApiKeyChange("unit-test-key-123")
        // Empty store: pending absent + no metadata → StaleGone terminal keeps
        // the form — same binding drop as logout.
        vm.retryPendingDelete(PendingDelete(alias = "preset:openai", revision = null, timestampMs = 0))
        awaitIdle(vm)
        assertFalse(vm.form.value.logoutInFlight)
        assertEquals("unit-test-key-123", vm.form.value.apiKey)
        assertFalse(vm.form.value.confirmRebind)
        vm.testConnection()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertTrue(vm.form.value.confirmRebind)
        assertEquals(0, fake.calls)
        assertEquals(0, buildCalls.get())
        assertEquals(0, dirCalls.get())
        assertEquals(0, policy.freshCalls)
        assertEquals(0, vault.putKeys)
        assertEquals(0, vault.getKeys)
        assertEquals(0, vault.deleteKeys)
        assertEquals(0, vault.hasKeys)
    }

    @Test
    fun whitespaceAppendProbeStillBlocked() {
        // S4-C: paste K for A → switch B (blocked＋dialog) → trailing-space edit
        // (binding retained, content-agnostic) invalidates then re-mints——
        // stays SETUP_KEY_IDENTITY_CHANGED with zero I/O.
        val fake = SpyBindingProvider()
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls, vault)
        vm.onApiKeyChange("unit-test-key-123")
        vm.selectPreset("anthropic")
        vm.testConnection()
        awaitIdle(vm)
        assertTrue(vm.form.value.confirmRebind)
        // Dialog-period whitespace edit invalidates (hides); text retained.
        vm.onApiKeyChange("unit-test-key-123 ")
        assertEquals("unit-test-key-123 ", vm.form.value.apiKey)
        assertFalse(vm.form.value.confirmRebind)
        assertNull(vm.form.value.rebindSummary)
        vm.testConnection()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertTrue(vm.form.value.confirmRebind)
        assertNull(vm.form.value.testModels)
        assertZeroProbeIo(fake, buildCalls, dirCalls, policy, vault)
    }

    @Test
    fun whitespaceAppendRefreshStillBlocked() {
        // S4-C refreshModels entry: same whitespace shape via custom URL switch.
        val fake = SpyBindingProvider(models = listOf("live-x"))
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls, vault)
        vm.selectPreset("custom")
        vm.onBaseUrlChange("http://127.0.0.1:11434/v1")
        vm.onApiKeyChange("unit-test-key-123")
        vm.onBaseUrlChange("http://127.0.0.1:11434/v2")
        vm.testConnection()
        awaitIdle(vm)
        assertTrue(vm.form.value.confirmRebind)
        vm.onApiKeyChange("unit-test-key-123   ")
        assertEquals("unit-test-key-123   ", vm.form.value.apiKey)
        assertFalse(vm.form.value.confirmRebind)
        vm.refreshModels()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertTrue(vm.form.value.confirmRebind)
        assertTrue(vm.form.value.modelOptions.isEmpty())
        assertZeroProbeIo(fake, buildCalls, dirCalls, policy, vault)
    }

    @Test
    fun clearReinsertSameKeyProbeStillBlocked() {
        // S4-C: paste K → switch B → clear to blank (binding retained) →
        // reinsert same K (binding retained, no rebind) — stays blocked.
        val fake = SpyBindingProvider()
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls, vault)
        vm.onApiKeyChange("unit-test-key-123")
        vm.selectPreset("anthropic")
        vm.onApiKeyChange("")
        assertEquals("", vm.form.value.apiKey)
        assertFalse(vm.form.value.confirmRebind)
        vm.onApiKeyChange("unit-test-key-123")
        assertEquals("unit-test-key-123", vm.form.value.apiKey)
        vm.testConnection()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertTrue(vm.form.value.confirmRebind)
        assertNull(vm.form.value.testModels)
        assertZeroProbeIo(fake, buildCalls, dirCalls, policy, vault)
    }

    @Test
    fun whitespaceAppendSaveZeroVaultWrite() {
        // S4-C save entry: whitespace shape — zero vault writes/reads,
        // no consent mint, no metadata, plus dialog.
        val fake = SpyBindingProvider()
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val (vm, policy, store) = newVm(fake, AtomicInteger(0), AtomicInteger(0), vault)
        vm.onApiKeyChange("unit-test-key-123")
        vm.selectPreset("anthropic")
        vm.save()
        awaitIdle(vm)
        assertTrue(vm.form.value.confirmRebind)
        vm.onApiKeyChange("unit-test-key-123 ")
        assertFalse(vm.form.value.confirmRebind)
        vm.save()
        awaitIdle(vm)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertTrue(vm.form.value.confirmRebind)
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
        // S4-C save entry: clear→reinsert same K — same blocked shape.
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
        assertTrue(vm.form.value.confirmRebind)
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
    fun dialogEditInvalidates() {
        // Dialog-period selectPreset/onBaseUrlChange/onApiKeyChange invalidate
        // (drop pending＋hide, retain binding); next entry re-mints fresh.
        val fake = SpyBindingProvider()
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls, vault)
        vm.onApiKeyChange("unit-test-key-123")
        vm.selectPreset("anthropic")
        vm.testConnection()
        awaitIdle(vm)
        assertTrue(vm.form.value.confirmRebind)
        assertEquals("Anthropic", vm.form.value.rebindSummary?.newLabel)
        // 1. selectPreset invalidates (still mismatched, new identity deepseek).
        vm.selectPreset("deepseek")
        assertFalse(vm.form.value.confirmRebind)
        assertNull(vm.form.value.rebindSummary)
        assertNull(vm.form.value.errorCode)
        assertEquals("unit-test-key-123", vm.form.value.apiKey)
        vm.testConnection()
        awaitIdle(vm)
        assertTrue(vm.form.value.confirmRebind)
        assertEquals("DeepSeek", vm.form.value.rebindSummary?.newLabel)
        assertEquals("OpenAI", vm.form.value.rebindSummary?.oldLabel)
        assertZeroProbeIo(fake, buildCalls, dirCalls, policy, vault)
        // 2. onApiKeyChange invalidates (same identity, new nonce on re-mint).
        vm.onApiKeyChange("unit-test-key-123-rotated")
        assertFalse(vm.form.value.confirmRebind)
        assertNull(vm.form.value.errorCode)
        vm.testConnection()
        awaitIdle(vm)
        assertTrue(vm.form.value.confirmRebind)
        assertEquals("DeepSeek", vm.form.value.rebindSummary?.newLabel)
        assertZeroProbeIo(fake, buildCalls, dirCalls, policy, vault)
        // 3. onBaseUrlChange invalidates (custom path exercises new baseUrl).
        vm.selectPreset("custom")
        vm.onBaseUrlChange("http://127.0.0.1:11434/v1")
        // Fresh custom identity still mismatched (bound is openai) → mint.
        vm.testConnection()
        awaitIdle(vm)
        assertTrue(vm.form.value.confirmRebind)
        vm.onBaseUrlChange("http://127.0.0.1:11434/v2")
        assertFalse(vm.form.value.confirmRebind)
        vm.testConnection()
        awaitIdle(vm)
        assertTrue(vm.form.value.confirmRebind)
        assertEquals("http://127.0.0.1:11434/v2", vm.form.value.rebindSummary?.newBaseUrl)
        assertZeroProbeIo(fake, buildCalls, dirCalls, policy, vault)
    }

    @Test
    fun dialogABAIdentityFails() {
        // Mint for B → switch back to A (invalidate) → switch forward to B →
        // confirm with stale (null) pending must NOT rebind.
        val fake = SpyBindingProvider()
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls, vault)
        vm.onApiKeyChange("unit-test-key-123")
        vm.selectPreset("anthropic")
        vm.testConnection()
        awaitIdle(vm)
        assertTrue(vm.form.value.confirmRebind)
        vm.selectPreset("openai")
        assertFalse(vm.form.value.confirmRebind)
        assertNull(vm.form.value.errorCode)
        vm.selectPreset("anthropic")
        assertFalse(vm.form.value.confirmRebind)
        // No pending: confirm is silent, no rebind, zero I/O.
        vm.confirmRebind()
        assertFalse(vm.form.value.confirmRebind)
        assertNull(vm.form.value.rebindSummary)
        assertZeroProbeIo(fake, buildCalls, dirCalls, policy, vault)
        // Still blocked: next probe re-mints fresh (proves no rebind happened).
        vm.testConnection()
        awaitIdle(vm)
        assertTrue(vm.form.value.confirmRebind)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertZeroProbeIo(fake, buildCalls, dirCalls, policy, vault)
    }

    @Test
    fun dialogReentrySilent() {
        // During the rebind dialog, all three entries silent-return with zero I/O.
        val fake = SpyBindingProvider(models = listOf("live-x"))
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val (vm, policy, store) = newVm(fake, buildCalls, dirCalls, vault)
        vm.onApiKeyChange("unit-test-key-123")
        vm.selectPreset("anthropic")
        vm.testConnection()
        awaitIdle(vm)
        assertTrue(vm.form.value.confirmRebind)
        val summary = vm.form.value.rebindSummary
        assertNotNull(summary)
        vm.testConnection()
        vm.refreshModels()
        vm.save()
        Thread.sleep(200)
        assertTrue(vm.form.value.confirmRebind)
        assertEquals(summary, vm.form.value.rebindSummary)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertFalse(vm.form.value.testing)
        assertFalse(vm.form.value.modelsLoading)
        assertFalse(vm.form.value.saving)
        assertZeroProbeIo(fake, buildCalls, dirCalls, policy, vault)
        assertEquals(0, vault.putKeys)
        runBlocking { assertFalse(store.hasMetadata()) }
    }

    @Test
    fun doubleConfirmSingleUse() {
        // First confirm rebinds (zero I/O); second tap is silent single-use.
        val fake = SpyBindingProvider()
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls, vault)
        vm.onApiKeyChange("unit-test-key-123")
        vm.selectPreset("anthropic")
        vm.testConnection()
        awaitIdle(vm)
        assertTrue(vm.form.value.confirmRebind)
        vm.confirmRebind()
        assertFalse(vm.form.value.confirmRebind)
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.testing)
        assertFalse(vm.form.value.saving)
        assertZeroProbeIo(fake, buildCalls, dirCalls, policy, vault)
        // Second tap: silent, no error flip, no I/O.
        vm.confirmRebind()
        assertFalse(vm.form.value.confirmRebind)
        assertNull(vm.form.value.errorCode)
        assertZeroProbeIo(fake, buildCalls, dirCalls, policy, vault)
        // Proves single rebind: retry now sends once to the new identity.
        vm.testConnection()
        awaitIdle(vm)
        assertNull(vm.form.value.errorCode)
        assertEquals(2, vm.form.value.testModels)
        assertEquals(1, fake.calls)
    }

    @Test
    fun confirmThenRetrySends() {
        val fake = SpyBindingProvider()
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls, vault)
        vm.onApiKeyChange("unit-test-key-123")
        vm.selectPreset("anthropic")
        vm.testConnection()
        awaitIdle(vm)
        assertTrue(vm.form.value.confirmRebind)
        assertZeroProbeIo(fake, buildCalls, dirCalls, policy, vault)
        vm.confirmRebind()
        assertFalse(vm.form.value.confirmRebind)
        assertNull(vm.form.value.errorCode)
        assertZeroProbeIo(fake, buildCalls, dirCalls, policy, vault)
        vm.testConnection()
        awaitIdle(vm)
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.confirmRebind)
        assertEquals(2, vm.form.value.testModels)
        assertEquals(1, fake.calls)
        assertEquals(1, buildCalls.get())
        assertEquals(0, dirCalls.get())
        assertEquals(2, policy.freshCalls)
        assertEquals("preset:anthropic", policy.lastResource)
    }

    @Test
    fun confirmThenSavePersists() {
        val fake = SpyBindingProvider()
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val (vm, policy, store) = newVm(fake, buildCalls, dirCalls, vault)
        vm.onApiKeyChange("unit-test-key-123456")
        vm.selectPreset("anthropic")
        vm.save()
        awaitIdle(vm)
        assertTrue(vm.form.value.confirmRebind)
        assertEquals(0, vault.putKeys)
        assertEquals(0, policy.freshCalls)
        vm.confirmRebind()
        assertFalse(vm.form.value.confirmRebind)
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)
        assertEquals(0, vault.putKeys)
        assertEquals(0, policy.freshCalls)
        vm.save()
        awaitIdle(vm)
        assertNull(vm.form.value.errorCode)
        assertTrue(vm.form.value.saved)
        assertFalse(vm.form.value.confirmRebind)
        assertFalse(vm.form.value.confirmKeyWrite)
        runBlocking {
            val cfg = store.observe().first()
            assertNotNull(cfg)
            assertEquals("preset:anthropic", cfg?.providerId)
        }
    }

    @Test
    fun dismissKeepsBlocked() {
        val fake = SpyBindingProvider()
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls, vault)
        vm.onApiKeyChange("unit-test-key-123")
        vm.selectPreset("anthropic")
        vm.testConnection()
        awaitIdle(vm)
        assertTrue(vm.form.value.confirmRebind)
        vm.dismissRebindConfirm()
        assertFalse(vm.form.value.confirmRebind)
        assertNull(vm.form.value.rebindSummary)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertZeroProbeIo(fake, buildCalls, dirCalls, policy, vault)
        // Still blocked: next probe re-mints fresh.
        vm.testConnection()
        awaitIdle(vm)
        assertTrue(vm.form.value.confirmRebind)
        assertEquals("SETUP_KEY_IDENTITY_CHANGED", vm.form.value.errorCode)
        assertZeroProbeIo(fake, buildCalls, dirCalls, policy, vault)
    }

    @Test
    fun backSwitchStillAllows() {
        // Mint dialog for B, then switch back to A: hides without confirm and allows.
        val fake = SpyBindingProvider()
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls, vault)
        vm.onApiKeyChange("unit-test-key-123")
        vm.selectPreset("anthropic")
        vm.testConnection()
        awaitIdle(vm)
        assertTrue(vm.form.value.confirmRebind)
        vm.selectPreset("openai")
        assertFalse(vm.form.value.confirmRebind)
        assertNull(vm.form.value.rebindSummary)
        assertNull(vm.form.value.errorCode)
        vm.testConnection()
        awaitIdle(vm)
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.confirmRebind)
        assertEquals(2, vm.form.value.testModels)
        assertEquals(1, fake.calls)
    }

    @Test
    fun freshPasteBindsWithoutDialog() {
        // Fresh terminal (prefill blank) + paste binds silently, no dialog.
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "stored-key-12345".toCharArray())
        }
        val vault = CountingVault(delegate)
        val fake = SpyBindingProvider()
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val (vm, policy, store) = newVm(fake, buildCalls, dirCalls, vault)
        runBlocking { store.save(openaiLive()) }
        vm.prefillForEdit(openaiLive())
        assertFalse(vm.form.value.confirmRebind)
        vm.onApiKeyChange("unit-test-key-fresh-123")
        assertFalse(vm.form.value.confirmRebind)
        vm.testConnection()
        awaitIdle(vm)
        assertNull(vm.form.value.errorCode)
        assertFalse(vm.form.value.confirmRebind)
        assertEquals(2, vm.form.value.testModels)
        assertEquals(1, fake.calls)
    }

    @Test
    fun rebindDoesNotTouchKeyWriteConsent() {
        val fake = SpyBindingProvider()
        val buildCalls = AtomicInteger(0)
        val dirCalls = AtomicInteger(0)
        val vault = CountingVault(EncryptedPrefsVault(InMemoryPrefs()))
        val (vm, policy, _) = newVm(fake, buildCalls, dirCalls, vault)
        vm.onApiKeyChange("unit-test-key-123")
        vm.selectPreset("anthropic")
        vm.testConnection()
        awaitIdle(vm)
        assertTrue(vm.form.value.confirmRebind)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertNull(vm.form.value.pendingKeyWriteIntent)
        assertNull(vm.form.value.confirmSummary)
        assertZeroProbeIo(fake, buildCalls, dirCalls, policy, vault)
        vm.confirmRebind()
        assertFalse(vm.form.value.confirmRebind)
        assertFalse(vm.form.value.confirmKeyWrite)
        assertNull(vm.form.value.pendingKeyWriteIntent)
        assertNull(vm.form.value.confirmSummary)
        assertNull(vm.form.value.errorCode)
        assertZeroProbeIo(fake, buildCalls, dirCalls, policy, vault)
    }

    @Test
    fun identityChangedHasCopyText() {
        assertEquals(
            "金鑰與目前伺服器不符（貼上後切換了供應商／位址），未發出任何請求／寫入；請在確認對話框中確認重綁（確認後仍需再按一次測試／儲存才會傳送），或切回原伺服器。",
            setupErrorText("SETUP_KEY_IDENTITY_CHANGED"),
        )
    }
}
