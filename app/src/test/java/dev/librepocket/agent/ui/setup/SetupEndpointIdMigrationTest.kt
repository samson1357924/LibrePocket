package dev.librepocket.agent.ui.setup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.viewModelScope
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.keystore.KeyVault
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage1 (R2) endpoint-id migration (plain JVM; SetupViewModel has no
 * Android dependencies — Main is faked, no Robolectric needed).
 *
 * - A pre-Stage1 custom row (shared `preset:custom` entity id over the fixed
 *   vault slot) gates NoEndpoint (never Ready, even with the key present),
 *   reports NO_KEY readiness, forces a key re-paste (blank KeepExisting is
 *   rejected), and the re-save mints the per-endpoint id + staged alias while
 *   retiring the legacy slot — the old key is never sent to a new URL.
 * - Fresh custom saves mint per-URL entity ids; same-URL rotation keeps the
 *   id while the vault alias rotates; policy stays preset-scoped throughout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupEndpointIdMigrationTest {

    private val tmpDirs = ArrayList<java.io.File>()
    private val scopes = ArrayList<CoroutineScope>()
    private val vms = ArrayList<SetupViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        // Stop each gate's WhileSubscribed upstream (child of viewModelScope)
        // before cancelling the DataStore scopes; otherwise the lingering 5s
        // sharing window crashes scope cancel (see
        // SetupStartupReadRetryTest's gateJob handling).
        vms.forEach { runCatching { it.viewModelScope.cancel() } }
        vms.clear()
        Dispatchers.resetMain()
        scopes.forEach { it.cancel() }
        tmpDirs.forEach { it.deleteRecursively() }
    }

    private fun newStore(): EndpointStore {
        val dir = Files.createTempDirectory("setup-endpoint-id-test").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        return EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "endpoint-id.preferences_pb") },
            ),
        )
    }

    private val noProvider: (dev.librepocket.provider.ProviderConfig, dev.librepocket.provider.KeyProvider) -> LlmProvider =
        { _, _ -> error("no provider needed") as LlmProvider }

    private suspend fun awaitSaveIdle(vm: SetupViewModel) {
        withTimeout(10_000) {
            while (vm.form.value.saving) delay(5)
        }
    }

    private fun legacyCustom(url: String) = EndpointConfig(
        providerId = "preset:custom",
        presetId = "custom",
        label = "自訂",
        baseUrl = url,
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "local",
        apiKeyRef = "provider_key/preset:custom",
    )

    @Test
    fun legacyCustomRowGatesNoEndpointRequiresRepasteAndSavesNewEntityId() = runBlocking {
        val url = "http://127.0.0.1:11434/v1"
        val store = newStore()
        val vault: KeyVault = EncryptedPrefsVault(InMemoryPrefs())
        vault.putKey("preset:custom", "stage1-legacy-key-12345678".toCharArray())
        store.save(legacyCustom(url))
        val policy = VerdictPolicy(Verdict.ALLOW)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = policy,
            buildProvider = noProvider,
        )
        vms.add(vm)

        // The key is present, yet the legacy row is never usable: readiness
        // stays NO_KEY and the gate stays NoEndpoint (re-key in Setup).
        assertTrue(vault.hasKey("preset:custom"))
        assertEquals(EndpointReadiness.NO_KEY, store.readiness(vault))
        val gate = withTimeout(10_000) { vm.gate.first { it !is EndpointGate.Loading } }
        assertTrue("legacy custom row must gate NoEndpoint, got $gate", gate is EndpointGate.NoEndpoint)

        // Prefill + blank save: KeepExisting falls back to NEW (legacy entity
        // id never equals the derived one) — re-paste required.
        val stored = withTimeout(10_000) { store.snapshotStrict() }
        assertNotNull(stored)
        vm.prefillForEdit(stored!!)
        vm.save()
        awaitSaveIdle(vm)
        assertEquals("SETUP_KEY_TOO_SHORT", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)

        // Re-paste + save: the new row carries the per-endpoint entity id and
        // a staged alias under it; the legacy fixed slot is retired.
        vm.onApiKeyChange("stage1-migration-key-12345678")
        vm.save()
        awaitSaveIdle(vm)
        assertTrue(vm.form.value.saved)
        val expectedId = SetupValidation.deriveEndpointId("custom", url)
        assertNotEquals("preset:custom", expectedId)
        val meta = withTimeout(10_000) { store.snapshotStrict() }
        assertNotNull(meta)
        assertEquals(expectedId, meta!!.providerId)
        assertTrue(meta.apiKeyRef.startsWith("provider_key/$expectedId#staged-"))
        val liveAlias = SetupValidation.aliasFromRef(meta.apiKeyRef)
        assertTrue(vault.hasKey(liveAlias))
        assertFalse(vault.hasKey("preset:custom"))
        // Policy stayed preset-scoped: key.write evaluated against the shared
        // preset resource, never the per-endpoint id (Stage2 #13 owns that).
        assertEquals("key.write", policy.lastAction)
        assertEquals("preset:custom", policy.lastResource)
        // Re-keyed row gates Ready.
        val ready = withTimeout(10_000) { vm.gate.first { it is EndpointGate.Ready } }
        assertEquals(expectedId, (ready as EndpointGate.Ready).config.providerId)
    }

    @Test
    fun customSaveMintsPerUrlEntityAndRotationKeepsIt() = runBlocking {
        val urlA = "http://127.0.0.1:11431/v1"
        val urlB = "http://127.0.0.1:11432/v1"
        val store = newStore()
        val vault: KeyVault = EncryptedPrefsVault(InMemoryPrefs())
        val policy = VerdictPolicy(Verdict.ALLOW)
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = policy,
            buildProvider = noProvider,
        )
        vms.add(vm)

        // Save endpoint A: per-URL entity id + staged alias under it.
        vm.selectPreset("custom")
        vm.onBaseUrlChange(urlA)
        vm.onApiKeyChange("stage1-key-a-11111111")
        vm.save()
        awaitSaveIdle(vm)
        assertTrue(vm.form.value.saved)
        val idA = SetupValidation.deriveEndpointId("custom", urlA)
        var meta = withTimeout(10_000) { store.snapshotStrict() }
        assertNotNull(meta)
        assertEquals(idA, meta!!.providerId)
        assertTrue(meta.apiKeyRef.startsWith("provider_key/$idA#staged-"))
        val aliasA = SetupValidation.aliasFromRef(meta.apiKeyRef)
        assertEquals("preset:custom", policy.lastResource)

        // Save endpoint B (same preset, different URL): a distinct entity id;
        // A's alias is retired, never reused.
        vm.onBaseUrlChange(urlB)
        vm.onApiKeyChange("stage1-key-b-22222222")
        vm.save()
        awaitSaveIdle(vm)
        assertTrue(vm.form.value.saved)
        val idB = SetupValidation.deriveEndpointId("custom", urlB)
        assertNotEquals(idA, idB)
        meta = withTimeout(10_000) { store.snapshotStrict() }
        assertNotNull(meta)
        assertEquals(idB, meta!!.providerId)
        assertTrue(meta.apiKeyRef.startsWith("provider_key/$idB#staged-"))
        val aliasB = SetupValidation.aliasFromRef(meta.apiKeyRef)
        assertNotEquals(aliasA, aliasB)
        assertFalse(vault.hasKey(aliasA))
        assertTrue(vault.hasKey(aliasB))

        // Rotation on B (same URL, new key): the entity id is stable while
        // the vault alias rotates; the pre-rotation alias is retired.
        vm.onApiKeyChange("stage1-key-b-rotated-33333333")
        vm.save()
        awaitSaveIdle(vm)
        assertTrue(vm.form.value.saved)
        meta = withTimeout(10_000) { store.snapshotStrict() }
        assertNotNull(meta)
        assertEquals(idB, meta!!.providerId)
        assertTrue(meta.apiKeyRef.startsWith("provider_key/$idB#staged-"))
        val aliasB2 = SetupValidation.aliasFromRef(meta.apiKeyRef)
        assertNotEquals(aliasB, aliasB2)
        assertFalse(vault.hasKey(aliasB))
        assertTrue(vault.hasKey(aliasB2))
    }

    /**
     * Stage1 read-layer ordering (M3/M4): URL validity is checked before the
     * legacy detector, so a malformed legacy row still reports
     * INVALID_BASE_URL readiness (never READY, zero vault reads). The Setup
     * gate maps the same row to NoEndpoint (legacy short-circuits there).
     * Seeded via direct dirty write — save() validation would reject the
     * malformed URL before the ordering is even reached.
     */
    @Test
    fun malformedLegacyUrlReportsInvalidBaseUrlAndGatesNoEndpoint() = runBlocking {
        val store = newStore()
        store.dataStore.edit { prefs ->
            prefs[ENDPOINT_PROVIDER_ID_V1] = "preset:custom"
            prefs[ENDPOINT_PRESET_ID_V1] = "custom"
            prefs[ENDPOINT_LABEL_V1] = "自訂"
            prefs[ENDPOINT_BASE_URL_V1] = "http://[::1"
            prefs[ENDPOINT_PROTOCOL_V1] = "CHAT_COMPLETIONS"
            prefs[ENDPOINT_MODEL_V1] = "local"
            prefs[ENDPOINT_API_KEY_REF_V1] = "provider_key/preset:custom"
        }
        val vault = CountingVault()
        assertEquals(EndpointReadiness.INVALID_BASE_URL, store.readiness(vault))
        assertEquals(0, vault.hasKeys)
        assertEquals(0, vault.getKeys)
        assertEquals(0, vault.putKeys)
        assertEquals(0, vault.deleteKeys)

        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = VerdictPolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        vms.add(vm)
        val gate = withTimeout(10_000) { vm.gate.first { it !is EndpointGate.Loading } }
        assertTrue("malformed legacy row must gate NoEndpoint, got $gate", gate is EndpointGate.NoEndpoint)
        assertEquals(0, vault.hasKeys)
        assertEquals(0, vault.getKeys)
        assertEquals(0, vault.putKeys)
        assertEquals(0, vault.deleteKeys)
    }

    /**
     * Legacy quarantine performs zero vault I/O (C3): readiness and the gate
     * both fail closed on a legacy custom row without reading, writing, or
     * probing the vault — even though the legacy fixed slot holds a key.
     */
    @Test
    fun legacyQuarantineTouchesNoVault() = runBlocking {
        val url = "http://127.0.0.1:11434/v1"
        val store = newStore()
        val vault = CountingVault()
        vault.putKey("preset:custom", "stage1-legacy-key-12345678".toCharArray())
        val seededPuts = vault.putKeys
        store.save(legacyCustom(url))

        assertEquals(EndpointReadiness.NO_KEY, store.readiness(vault))
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = VerdictPolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        vms.add(vm)
        val gate = withTimeout(10_000) { vm.gate.first { it !is EndpointGate.Loading } }
        assertTrue("legacy custom row must gate NoEndpoint, got $gate", gate is EndpointGate.NoEndpoint)

        // Only the explicit test seeding touched the vault; every production
        // read path above observed without I/O.
        assertEquals(seededPuts, vault.putKeys)
        assertEquals(0, vault.hasKeys)
        assertEquals(0, vault.getKeys)
        assertEquals(0, vault.deleteKeys)
    }

    /**
     * Keep-existing fallback on a legacy row writes nothing (M11): a blank
     * save over a legacy custom row falls back to NEW, demands a re-paste,
     * and performs zero vault reads/writes while leaving the stored row
     * untouched.
     */
    @Test
    fun keepExistingLegacyFallbackWritesNothing() = runBlocking {
        val url = "http://127.0.0.1:11434/v1"
        val store = newStore()
        val vault = CountingVault()
        vault.putKey("preset:custom", "stage1-legacy-key-12345678".toCharArray())
        val seededPuts = vault.putKeys
        store.save(legacyCustom(url))

        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = VerdictPolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        vms.add(vm)
        val stored = withTimeout(10_000) { store.snapshotStrict() }
        assertNotNull(stored)
        vm.prefillForEdit(stored!!)
        vm.save()
        awaitSaveIdle(vm)
        assertEquals("SETUP_KEY_TOO_SHORT", vm.form.value.errorCode)
        assertFalse(vm.form.value.saved)

        assertEquals(seededPuts, vault.putKeys)
        assertEquals(0, vault.hasKeys)
        assertEquals(0, vault.getKeys)
        assertEquals(0, vault.deleteKeys)
        val after = withTimeout(10_000) { store.snapshotStrict() }
        assertEquals("preset:custom", after?.providerId)
    }

    /**
     * Triple rejection (write side): a non-legacy row whose vault alias falls
     * outside its own providerId namespace is rejected by save() — the old
     * mixed shape (per-URL id over the shared fixed slot) can never be
     * written, even by hand.
     */
    @Test
    fun mismatchedAliasRowIsRejectedOnSave() = runBlocking {
        val url = "http://127.0.0.1:11434/v1"
        val store = newStore()
        val id = SetupValidation.deriveEndpointId("custom", url)
        try {
            store.save(
                EndpointConfig(
                    providerId = id,
                    presetId = "custom",
                    label = "自訂",
                    baseUrl = url,
                    protocol = ProviderProtocol.CHAT_COMPLETIONS,
                    model = "local",
                    apiKeyRef = "provider_key/preset:custom",
                ),
            )
            error("expected PROVIDER_KEY_REF_NAMESPACE_MISMATCH")
        } catch (e: IllegalArgumentException) {
            assertEquals("PROVIDER_KEY_REF_NAMESPACE_MISMATCH", e.message)
        }
        assertNull(store.observe().first())
    }

    /**
     * Triple rejection (read side): the same mixed shape, if present via a
     * dirty write, reports NO_KEY readiness with zero vault reads — it can
     * never become READY.
     */
    @Test
    fun mismatchedAliasRowReadsAsNoKeyWithZeroVaultReads() = runBlocking {
        val url = "http://127.0.0.1:11434/v1"
        val id = SetupValidation.deriveEndpointId("custom", url)
        val store = newStore()
        store.dataStore.edit { prefs ->
            prefs[ENDPOINT_PROVIDER_ID_V1] = id
            prefs[ENDPOINT_PRESET_ID_V1] = "custom"
            prefs[ENDPOINT_LABEL_V1] = "自訂"
            prefs[ENDPOINT_BASE_URL_V1] = url
            prefs[ENDPOINT_PROTOCOL_V1] = "CHAT_COMPLETIONS"
            prefs[ENDPOINT_MODEL_V1] = "local"
            prefs[ENDPOINT_API_KEY_REF_V1] = "provider_key/preset:custom"
        }
        val vault = CountingVault()
        assertEquals(EndpointReadiness.NO_KEY, store.readiness(vault))
        assertEquals(0, vault.hasKeys)
        assertEquals(0, vault.getKeys)
        assertEquals(0, vault.putKeys)
        assertEquals(0, vault.deleteKeys)
    }

    /**
     * Built-in control (M3): the legacy detector never misfires on built-in
     * presets — a well-formed openai row with its key present is READY and
     * gates Ready.
     */
    @Test
    fun builtinRowIsNotLegacyAndGatesReady() = runBlocking {
        val store = newStore()
        val vault = CountingVault()
        vault.putKey("preset:openai", "stage1-builtin-key-12345678".toCharArray())
        store.save(
            EndpointConfig(
                providerId = "preset:openai",
                presetId = "openai",
                label = "OpenAI",
                baseUrl = "https://api.openai.com/v1",
                protocol = ProviderProtocol.CHAT_COMPLETIONS,
                model = "gpt-4o-mini",
                apiKeyRef = "provider_key/preset:openai",
            ),
        )
        assertFalse(
            SetupValidation.isLegacyEndpointId("preset:openai", "openai", "https://api.openai.com/v1"),
        )
        assertEquals(EndpointReadiness.READY, store.readiness(vault))

        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = VerdictPolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        vms.add(vm)
        val gate = withTimeout(10_000) { vm.gate.first { it !is EndpointGate.Loading } }
        assertTrue("builtin row must gate Ready, got $gate", gate is EndpointGate.Ready)
    }
}
