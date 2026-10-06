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
        assertEquals(sampleConfig(), store.observe().first())
        assertTrue(store.hasMetadata())
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
