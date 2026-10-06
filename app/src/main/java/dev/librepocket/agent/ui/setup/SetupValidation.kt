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
