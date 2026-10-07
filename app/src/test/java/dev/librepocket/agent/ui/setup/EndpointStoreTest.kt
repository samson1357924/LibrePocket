package dev.librepocket.agent.ui.setup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.provider.ProviderProtocol
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointStoreTest {

    private val tmpDirs = ArrayList<java.io.File>()
    private val scopes = ArrayList<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        tmpDirs.forEach { it.deleteRecursively() }
    }

    private fun newStore(): EndpointStore {
        val dir = Files.createTempDirectory("endpoint-test").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "endpoint.preferences_pb") },
        )
        return EndpointStore(dataStore)
    }

    private fun sampleConfig() = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "gpt-4o-mini",
        apiKeyRef = "provider_key/preset:openai",
    )

    @Test
    fun emptyStoreHasNoMetadata() = runBlocking {
        val store = newStore()
        assertNull(store.observe().first())
        assertFalse(store.hasMetadata())
    }

    @Test
    fun saveObserveRoundTrip() = runBlocking {
        val store = newStore()
        store.save(sampleConfig())
        assertEquals(sampleConfig().copy(configRevision = 1L), store.observe().first())
        assertTrue(store.hasMetadata())
    }

    @Test
    fun saveAndClearAdvancePersistentConfigRevision() = runBlocking {
        val store = newStore()
        store.save(sampleConfig())
        assertEquals(1L, store.observe().first()?.configRevision)
        store.save(sampleConfig().copy(baseUrl = "https://example.test/v1"))
        assertEquals(2L, store.observe().first()?.configRevision)
        store.clear()
        assertNull(store.observe().first())
        store.save(sampleConfig())
        assertEquals(4L, store.observe().first()?.configRevision)
    }

    @Test
    fun saveRejectsBlankIdUrlAndRef() {
        val store = newStore()
        runBlocking {
            try {
                store.save(sampleConfig().copy(providerId = " "))
                error("expected PROVIDER_ID_BLANK")
            } catch (e: IllegalArgumentException) {
                assertEquals("PROVIDER_ID_BLANK", e.message)
            }
            try {
                store.save(sampleConfig().copy(baseUrl = ""))
                error("expected BASE_URL_BLANK")
            } catch (e: IllegalArgumentException) {
                assertEquals("BASE_URL_BLANK", e.message)
            }
            try {
                store.save(sampleConfig().copy(apiKeyRef = "  "))
                error("expected PROVIDER_KEY_REF_BLANK")
            } catch (e: IllegalArgumentException) {
                assertEquals("PROVIDER_KEY_REF_BLANK", e.message)
            }
        }
    }

    @Test
    fun saveRejectsNonHttpsBaseUrl() {
        val store = newStore()
        runBlocking {
            try {
                store.save(sampleConfig().copy(baseUrl = "http://evil.example.com"))
                error("expected PROVIDER_URL_MUST_BE_HTTPS")
            } catch (e: IllegalArgumentException) {
                assertEquals("PROVIDER_URL_MUST_BE_HTTPS", e.message)
            }
        }
        runBlocking {
            assertNull(store.observe().first())
        }
    }

    @Test
    fun partialDirtyDataReadsAsNull() = runBlocking {
        val store = newStore()
        store.dataStore.edit { prefs ->
            prefs[ENDPOINT_PROVIDER_ID_V1] = "preset:openai"
        }
        assertNull(store.observe().first())
        assertFalse(store.hasMetadata())
    }

    @Test
    fun clearReturnsToNull() = runBlocking {
        val store = newStore()
        store.save(sampleConfig())
        store.clear()
        assertNull(store.observe().first())
        assertFalse(store.hasMetadata())
    }

    @Test
    fun saveRejectsMalformedRef() {
        val store = newStore()
        runBlocking {
            try {
                // Pasted key material must never land in the ref field.
                store.save(sampleConfig().copy(apiKeyRef = "sk-abcDEF1234567890"))
                error("expected PROVIDER_KEY_REF_MALFORMED")
            } catch (e: IllegalArgumentException) {
                assertEquals("PROVIDER_KEY_REF_MALFORMED", e.message)
            }
            try {
                store.save(sampleConfig().copy(apiKeyRef = "other/x"))
                error("expected PROVIDER_KEY_REF_MALFORMED")
            } catch (e: IllegalArgumentException) {
                assertEquals("PROVIDER_KEY_REF_MALFORMED", e.message)
            }
            try {
                store.save(sampleConfig().copy(apiKeyRef = "provider_key/a\nb"))
                error("expected PROVIDER_KEY_REF_MALFORMED")
            } catch (e: IllegalArgumentException) {
                assertEquals("PROVIDER_KEY_REF_MALFORMED", e.message)
            }
            assertNull(store.observe().first())
        }
    }

    @Test
    fun dirtyBaseUrlReadsAsNotReady() = runBlocking {
        val store = newStore()
        // Bypass save() validation with a direct dirty write.
        store.dataStore.edit { prefs ->
            prefs[ENDPOINT_PROVIDER_ID_V1] = "preset:openai"
            prefs[ENDPOINT_BASE_URL_V1] = "http://evil.example.com"
            prefs[ENDPOINT_PROTOCOL_V1] = "CHAT_COMPLETIONS"
            prefs[ENDPOINT_API_KEY_REF_V1] = "provider_key/preset:openai"
        }
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        vault.putKey("preset:openai", "sk-test-key-123".toCharArray())
        assertEquals(EndpointReadiness.INVALID_BASE_URL, store.readiness(vault))
    }

    @Test
    fun readinessRequiresMetadataUrlAndKey() = runBlocking {
        val store = newStore()
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        assertEquals(EndpointReadiness.NO_ENDPOINT_METADATA, store.readiness(vault))

        store.save(sampleConfig())
        assertEquals(EndpointReadiness.NO_KEY, store.readiness(vault))

        vault.putKey("preset:openai", "sk-test-key-123".toCharArray())
        assertEquals(EndpointReadiness.READY, store.readiness(vault))
    }
}
