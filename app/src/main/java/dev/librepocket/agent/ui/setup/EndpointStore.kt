package dev.librepocket.agent.ui.setup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.librepocket.keystore.KeyVault
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.validateBaseUrl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

val Context.endpointDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "librepocket_endpoint",
)

// Versioned keys: bump the suffix (and migrate) when fields change.
internal val ENDPOINT_PROVIDER_ID_V1 = stringPreferencesKey("endpoint_provider_id_v1")
internal val ENDPOINT_PRESET_ID_V1 = stringPreferencesKey("endpoint_preset_id_v1")
internal val ENDPOINT_LABEL_V1 = stringPreferencesKey("endpoint_label_v1")
internal val ENDPOINT_BASE_URL_V1 = stringPreferencesKey("endpoint_base_url_v1")
internal val ENDPOINT_PROTOCOL_V1 = stringPreferencesKey("endpoint_protocol_v1")
internal val ENDPOINT_MODEL_V1 = stringPreferencesKey("endpoint_model_v1")
internal val ENDPOINT_API_KEY_REF_V1 = stringPreferencesKey("endpoint_api_key_ref_v1")
internal val ENDPOINT_CONFIG_REVISION_V1 = longPreferencesKey("endpoint_config_revision_v1")

/** Usability verdict for the endpoint gate (log the code only, never key material). */
enum class EndpointReadiness {
    READY,
    NO_ENDPOINT_METADATA,
    INVALID_BASE_URL,
    NO_KEY,
}

/**
 * Active BYOK endpoint metadata (single-endpoint MVP; multi-endpoint is P2).
 *
 * Never carries the plaintext key: the key itself lives in
 * [dev.librepocket.keystore.KeyVault] under [providerId], referenced here
 * only by [apiKeyRef] (form `provider_key/<...>`). [providerId] follows
 * `ProviderCatalog.fromPreset` convention (`preset:<presetId>`). The local
 * [configRevision] is atomically advanced by [EndpointStore.save]/[clear] and
 * is for stale-session fencing, not endpoint identity or vault-write atomicity.
 */
data class EndpointConfig(
    val providerId: String,
    val presetId: String,
    val label: String,
    val baseUrl: String,
    val protocol: ProviderProtocol,
    val model: String,
    val apiKeyRef: String,
    /** Monotonic metadata revision, assigned atomically by [EndpointStore]. */
    val configRevision: Long = 0L,
)

/**
 * Persists the active endpoint metadata. Shape mirrors
 * [dev.librepocket.policy.DataStorePolicyStore] conventions: dual constructors,
 * injectable [ioDispatcher], fail-closed reads.
 */
class EndpointStore(
    internal val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(context.applicationContext.endpointDataStore)

    fun observe(): Flow<EndpointConfig?> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            val providerId = prefs[ENDPOINT_PROVIDER_ID_V1] ?: return@map null
            val baseUrl = prefs[ENDPOINT_BASE_URL_V1] ?: return@map null
            val apiKeyRef = prefs[ENDPOINT_API_KEY_REF_V1] ?: return@map null
            val protocolName = prefs[ENDPOINT_PROTOCOL_V1] ?: return@map null
            if (providerId.isBlank() || baseUrl.isBlank() || apiKeyRef.isBlank()) return@map null
            val protocol = try {
                ProviderProtocol.valueOf(protocolName)
            } catch (_: IllegalArgumentException) {
                return@map null
            }
            EndpointConfig(
                providerId = providerId,
                presetId = prefs[ENDPOINT_PRESET_ID_V1].orEmpty(),
                label = prefs[ENDPOINT_LABEL_V1].orEmpty(),
                baseUrl = baseUrl,
                protocol = protocol,
                model = prefs[ENDPOINT_MODEL_V1].orEmpty(),
                apiKeyRef = apiKeyRef,
                configRevision = prefs[ENDPOINT_CONFIG_REVISION_V1] ?: 0L,
            )
        }

    /** Metadata present (does NOT imply a usable endpoint; see [readiness]). */
    suspend fun hasMetadata(): Boolean = withContext(ioDispatcher) {
        try {
            observe().first() != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Gate verdict: metadata complete + baseUrl valid + [KeyVault] holds the key.
     * Network reachability is deliberately excluded (checked via listModels on demand).
     */
    suspend fun readiness(vault: KeyVault): EndpointReadiness {
        val config = try {
            withContext(ioDispatcher) { observe().first() }
        } catch (_: Exception) {
            return EndpointReadiness.NO_ENDPOINT_METADATA
        } ?: return EndpointReadiness.NO_ENDPOINT_METADATA
        try {
            validateBaseUrl(config.baseUrl)
        } catch (_: IllegalArgumentException) {
            return EndpointReadiness.INVALID_BASE_URL
        }
        val hasKey = try {
            vault.hasKey(config.providerId)
        } catch (_: Exception) {
            false
        }
        return if (hasKey) EndpointReadiness.READY else EndpointReadiness.NO_KEY
    }

    suspend fun save(config: EndpointConfig) {
        require(config.providerId.isNotBlank()) { "PROVIDER_ID_BLANK" }
        require(config.baseUrl.isNotBlank()) { "BASE_URL_BLANK" }
        require(config.apiKeyRef.isNotBlank()) { "PROVIDER_KEY_REF_BLANK" }
        // Boundary guard: the ref must be a vault alias, never pasted key material.
        require(config.apiKeyRef.startsWith("provider_key/")) { "PROVIDER_KEY_REF_MALFORMED" }
        require('\n' !in config.apiKeyRef && '\r' !in config.apiKeyRef) { "PROVIDER_KEY_REF_MALFORMED" }
        validateBaseUrl(config.baseUrl)
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                prefs[ENDPOINT_PROVIDER_ID_V1] = config.providerId
                prefs[ENDPOINT_PRESET_ID_V1] = config.presetId
                prefs[ENDPOINT_LABEL_V1] = config.label
                prefs[ENDPOINT_BASE_URL_V1] = config.baseUrl
                prefs[ENDPOINT_PROTOCOL_V1] = config.protocol.name
                prefs[ENDPOINT_MODEL_V1] = config.model
                prefs[ENDPOINT_API_KEY_REF_V1] = config.apiKeyRef
                prefs[ENDPOINT_CONFIG_REVISION_V1] = nextRevision(prefs[ENDPOINT_CONFIG_REVISION_V1])
            }
        }
    }

    /**
     * Clears metadata only. Callers rotating/logging out MUST also call
     * `KeyVault.deleteKey(providerId)` or orphan keys linger in EncryptedSharedPreferences.
     */
    suspend fun clear() {
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                prefs.remove(ENDPOINT_PROVIDER_ID_V1)
                prefs.remove(ENDPOINT_PRESET_ID_V1)
                prefs.remove(ENDPOINT_LABEL_V1)
                prefs.remove(ENDPOINT_BASE_URL_V1)
                prefs.remove(ENDPOINT_PROTOCOL_V1)
                prefs.remove(ENDPOINT_MODEL_V1)
                prefs.remove(ENDPOINT_API_KEY_REF_V1)
                // Keep the revision across logout so a later endpoint cannot
                // accidentally compare equal to a pre-logout snapshot.
                prefs[ENDPOINT_CONFIG_REVISION_V1] = nextRevision(prefs[ENDPOINT_CONFIG_REVISION_V1])
            }
        }
    }

    private fun nextRevision(current: Long?): Long =
        Math.addExact(current ?: 0L, 1L)
}
