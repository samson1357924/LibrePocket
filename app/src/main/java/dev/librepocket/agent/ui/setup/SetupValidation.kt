package dev.librepocket.agent.ui.setup

import dev.librepocket.preset.ProviderCatalog
import dev.librepocket.provider.validateBaseUrl

/**
 * Pure setup-input validation (unit-testable without Android).
 * Returns an error code, or null when the input is acceptable.
 */
object SetupValidation {
    fun deriveProviderId(presetId: String): String = "preset:$presetId"

    fun deriveApiKeyRef(providerId: String): String = "provider_key/$providerId"

    /**
     * Per-operation staged vault alias for the versioned/staged commit scheme.
     *
     * Form `<providerId>#staged-<nonce>` with a per-operation unique [nonce].
     * `#` is legal: [dev.librepocket.keystore.EncryptedPrefsVault.requireValidProviderId]
     * rejects only blank/NUL, so the staged alias passes vault validation and
     * maps to the entry key `provider_key/<stagedAlias>`. Never derived from
     * user input; [nonce] must be unique per save attempt PROCESS-wide (a
     * per-ViewModel counter is insufficient: two ViewModels sharing one vault
     * would mint identical staged aliases — see the process-wide sequence in
     * `SetupViewModel`).
     */
    fun stagedAlias(providerId: String, nonce: Long): String = "$providerId#staged-$nonce"

    /** Vault entry ref (`provider_key/...`) pointing at a staged alias. */
    fun stagedApiKeyRef(providerId: String, nonce: Long): String = "provider_key/" + stagedAlias(providerId, nonce)

    /**
     * Resolves the vault alias addressed by an endpoint [apiKeyRef]
     * (`provider_key/<alias>` -> `<alias>`). Returns the input unchanged when
     * the prefix is absent; callers MUST treat a missing prefix / blank alias
     * as malformed and fail closed (never touch the vault).
     */
    fun aliasFromRef(apiKeyRef: String): String = apiKeyRef.removePrefix("provider_key/")

    fun validate(presetId: String, baseUrl: String, apiKey: String): String? {
        if (ProviderCatalog.preset(presetId) == null) return "SETUP_UNKNOWN_PRESET"
        val url = baseUrl.trim()
        if (url.isEmpty()) return "SETUP_BASE_URL_BLANK"
        try {
            validateBaseUrl(url)
        } catch (e: IllegalArgumentException) {
            return e.message ?: "PROVIDER_BAD_URL"
        }
        if (apiKey.trim().length < 8) return "SETUP_KEY_TOO_SHORT"
        return null
    }
}
