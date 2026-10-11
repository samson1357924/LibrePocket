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
    /**
     * Monotonic metadata revision, assigned atomically by [EndpointStore].
     *
     * WARNING: fencing callers MUST source the expected revision from
     * [EndpointStore.currentRevision()], never `observe().configRevision`.
     * [observe] folds missing/partial metadata (including the post-clear
     * tombstone) to `null`, and folds a missing revision key on present
     * metadata to `0L`, so reusing its value conflates fresh `null` with
     * dirty `0L` and loses the tombstone revision.
     */
    val configRevision: Long = 0L,
)

/**
 * Persists the active endpoint metadata. Shape mirrors
 * [dev.librepocket.policy.DataStorePolicyStore] conventions: dual constructors,
 * injectable [ioDispatcher], fail-closed reads.
 *
 * Revision model:
 * - `null` means fresh: [ENDPOINT_CONFIG_REVISION_V1] was never written.
 * - `0L` means a dirty/hand-written value (only reachable via a direct
 *   `dataStore.edit` that bypasses [save]/[saveIfRevision]/[clear]/[clearIfRevision]);
 *   it is distinct from fresh `null` and never produced by the store itself.
 * - Every successful [save]/[saveIfRevision]/[clear]/[clearIfRevision] advances
 *   the revision by exactly one via `Math.addExact`, so overflow fails closed with
 *   [ArithmeticException] instead of wrapping.
 *
 * CAS notes:
 * - [currentRevision] exposes the raw stored revision (including the value
 *   across [clear], where [observe] folds to `null`); it never catches, so
 *   DataStore failures propagate fail-closed to the caller.
 * - [saveIfRevision]/[clearIfRevision] perform the compare-and-set inside a
 *   single `dataStore.edit {}` transaction; a mismatch `return@edit`s without
 *   writing and reports `false`. There is no distributed transaction with [KeyVault]:
 *   callers (VM layer, phase 2) must coordinate metadata fencing with vault
 *   writes/reads.
 * - Single-process only; not safe across processes: DataStore is not
 *   multi-process safe, so the CAS only fences threads/coroutines in this process.
 */
class EndpointStore(
    internal val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(context.applicationContext.endpointDataStore)

    /**
     * Lenient UI read. Folds DataStore failures to `null` (via `catch`) and
     * folds missing/partial metadata (including the post-[clear] tombstone) to
     * `null`.
     *
     * ONLY for UI gate/[StateFlow] display ([readiness], [hasMetadata], gate).
     * FORBIDDEN for deleteKey/restore authorization: destructive decisions MUST
     * use [snapshotStrict] (or [snapshotWithRevisionStrict]), never this.
     * Lenient `null` conflates transient failure with absent winner metadata
     * and would mis-authorize an orphan delete.
     */
    fun observe(): Flow<EndpointConfig?> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> parseConfig(prefs) }

    private fun parseConfig(prefs: Preferences): EndpointConfig? {
        val providerId = prefs[ENDPOINT_PROVIDER_ID_V1] ?: return null
        val baseUrl = prefs[ENDPOINT_BASE_URL_V1] ?: return null
        val apiKeyRef = prefs[ENDPOINT_API_KEY_REF_V1] ?: return null
        val protocolName = prefs[ENDPOINT_PROTOCOL_V1] ?: return null
        if (providerId.isBlank() || baseUrl.isBlank() || apiKeyRef.isBlank()) return null
        val protocol = try {
            ProviderProtocol.valueOf(protocolName)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return EndpointConfig(
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

    /**
     * Strict read for destructive authorization (deleteKey/restore gating).
     *
     * Reads one `dataStore.data.first()` emission and parses directly WITHOUT
     * `catch`: DataStore failures propagate fail-closed to the caller, which
     * MUST treat any [Exception] ([CancellationException] rethrown) as
     * no-touch. Unlike [observe], a transient failure never folds to `null`,
     * so a missing-winner `null` is trustworthy (healthy read of absent
     * metadata) rather than a failure masquerading as absent.
     *
     * Same level as [currentRevision]: raw, fail-closed, single emission.
     */
    suspend fun snapshotStrict(): EndpointConfig? = withContext(ioDispatcher) {
        val prefs = dataStore.data.first()
        parseConfig(prefs)
    }

    /**
     * Strict single-emission read of revision + config together.
     *
     * Collects ONE `dataStore.data.first()` emission and derives both values
     * from the same [Preferences] snapshot, eliminating the TOCTOU window
     * between separate [currentRevision] + [snapshotStrict] collections (a
     * concurrent writer landing between two reads could otherwise advance the
     * revision after the first read but before the second).
     *
     * Fail-closed like [snapshotStrict]: no `catch`, failures propagate.
     * Stage 1 callers still use the two-read sequence (documented window);
     * stage 2 should migrate fenced paths to this.
     */
    suspend fun snapshotWithRevisionStrict(): Pair<EndpointConfig?, Long?> =
        withContext(ioDispatcher) {
            val prefs = dataStore.data.first()
            parseConfig(prefs) to prefs[ENDPOINT_CONFIG_REVISION_V1]
        }

    /**
     * Raw monotonic revision, or `null` when the key was never written (fresh).
     *
     * Unlike [observe] — which folds missing/partial metadata (including the
     * post-[clear] tombstone) to `null` — this exposes the underlying
     * [ENDPOINT_CONFIG_REVISION_V1] value so callers can fence stale sessions.
     * Fail-closed: DataStore failures are NOT caught and propagate to the caller.
     */
    suspend fun currentRevision(): Long? = withContext(ioDispatcher) {
        dataStore.data.map { prefs -> prefs[ENDPOINT_CONFIG_REVISION_V1] }.first()
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

    /**
     * Legacy unconditional write. Kept for tests / non-fencing paths only.
     *
     * New production code that needs stale-session fencing MUST use
     * [saveIfRevision] with the revision observed at read time instead.
     */
    suspend fun save(config: EndpointConfig) {
        validateForSave(config)
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
     * Atomic compare-and-set write. Single-process only; not safe across processes
     * (DataStore is not multi-process safe).
     *
     * Validates exactly like [save], then inside a single `dataStore.edit {}`
     * transaction compares the stored revision against [expectedRevision]:
     * - mismatch → `return@edit` without writing anything, returns `false`
     *   (the `edit` early-return does not advance the revision);
     * - match → writes all fields and bumps to
     *   `Math.addExact(expectedRevision ?: 0L, 1L)`, returns `true`.
     *
     * @param expectedRevision MUST come from [currentRevision()], never
     * `observe().configRevision`: [observe] folds missing/partial metadata
     * (including the post-[clear] tombstone) to `null` — losing the tombstone
     * revision — and folds a missing revision key on present metadata to `0L`,
     * so reusing its value conflates fresh `null` with dirty `0L` and can
     * silently clobber a concurrent writer.
     *
     * `null` expected means "must be fresh" (key never written); a dirty
     * hand-written `0L` does NOT equal fresh `null`. Fail-closed: validation
     * failures, revision overflow ([ArithmeticException]), and DataStore
     * failures propagate; only the expected contention path returns `false`.
     */
    suspend fun saveIfRevision(config: EndpointConfig, expectedRevision: Long?): Boolean {
        validateForSave(config)
        return withContext(ioDispatcher) {
            var wrote = false
            dataStore.edit { prefs ->
                val actual = prefs[ENDPOINT_CONFIG_REVISION_V1]
                if (actual != expectedRevision) {
                    return@edit
                }
                prefs[ENDPOINT_PROVIDER_ID_V1] = config.providerId
                prefs[ENDPOINT_PRESET_ID_V1] = config.presetId
                prefs[ENDPOINT_LABEL_V1] = config.label
                prefs[ENDPOINT_BASE_URL_V1] = config.baseUrl
                prefs[ENDPOINT_PROTOCOL_V1] = config.protocol.name
                prefs[ENDPOINT_MODEL_V1] = config.model
                prefs[ENDPOINT_API_KEY_REF_V1] = config.apiKeyRef
                prefs[ENDPOINT_CONFIG_REVISION_V1] = Math.addExact(expectedRevision ?: 0L, 1L)
                wrote = true
            }
            wrote
        }
    }

    /**
     * Privileged unconditional clear. Only explicit logout may call this;
     * stale callers are forbidden — use [clearIfRevision] for any fenced path.
     *
     * Intentionally asymmetric with [saveIfRevision]: logout is authoritative
     * teardown that must always advance the revision (invalidating outstanding
     * snapshots/consents), never fail with `false`. Fencing belongs on the
     * write path; a stale logout must not silently no-op and leave metadata
     * behind, so only the session owner reaching this privileged path may clear.
     *
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

    /**
     * Fenced clear. Single-process only; not safe across processes
     * (DataStore is not multi-process safe).
     *
     * Compares the stored revision against [expectedRevision] inside a single
     * `dataStore.edit {}` transaction: mismatch `return@edit`s without writing
     * and returns `false`; match removes all metadata fields, bumps to
     * `Math.addExact(expectedRevision ?: 0L, 1L)`, and returns `true`.
     *
     * @param expectedRevision MUST come from [currentRevision()], never
     * `observe().configRevision`: [observe] folds the post-[clear] tombstone to
     * `null`, so reusing its value loses the revision that must be fenced.
     *
     * Fail-closed: revision overflow ([ArithmeticException]) and DataStore
     * failures propagate; only the expected contention path returns `false`.
     * Like [clear], this removes metadata only — callers MUST also coordinate
     * `KeyVault.deleteKey(providerId)` (no distributed transaction with the vault).
     */
    suspend fun clearIfRevision(expectedRevision: Long?): Boolean {
        return withContext(ioDispatcher) {
            var wrote = false
            dataStore.edit { prefs ->
                val actual = prefs[ENDPOINT_CONFIG_REVISION_V1]
                if (actual != expectedRevision) {
                    return@edit
                }
                prefs.remove(ENDPOINT_PROVIDER_ID_V1)
                prefs.remove(ENDPOINT_PRESET_ID_V1)
                prefs.remove(ENDPOINT_LABEL_V1)
                prefs.remove(ENDPOINT_BASE_URL_V1)
                prefs.remove(ENDPOINT_PROTOCOL_V1)
                prefs.remove(ENDPOINT_MODEL_V1)
                prefs.remove(ENDPOINT_API_KEY_REF_V1)
                // Keep the revision across clear so a later endpoint cannot
                // accidentally compare equal to a pre-clear snapshot.
                prefs[ENDPOINT_CONFIG_REVISION_V1] = Math.addExact(expectedRevision ?: 0L, 1L)
                wrote = true
            }
            wrote
        }
    }

    private fun nextRevision(current: Long?): Long =
        Math.addExact(current ?: 0L, 1L)

    private fun validateForSave(config: EndpointConfig) {
        require(config.providerId.isNotBlank()) { "PROVIDER_ID_BLANK" }
        require(config.baseUrl.isNotBlank()) { "BASE_URL_BLANK" }
        require(config.apiKeyRef.isNotBlank()) { "PROVIDER_KEY_REF_BLANK" }
        // Boundary guard: the ref must be a vault alias, never pasted key material.
        require(config.apiKeyRef.startsWith("provider_key/")) { "PROVIDER_KEY_REF_MALFORMED" }
        require('\n' !in config.apiKeyRef && '\r' !in config.apiKeyRef) { "PROVIDER_KEY_REF_MALFORMED" }
        validateBaseUrl(config.baseUrl)
    }
}
