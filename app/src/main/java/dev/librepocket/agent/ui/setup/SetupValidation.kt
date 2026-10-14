package dev.librepocket.agent.ui.setup

import dev.librepocket.preset.ProviderCatalog
import dev.librepocket.provider.validateBaseUrl
import java.util.UUID

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
     * Form `<providerId>#staged-<suffix>` with a caller-supplied restart-unique
     * [suffix] (see [newStagedSuffix]). `#` is legal:
     * [dev.librepocket.keystore.EncryptedPrefsVault.requireValidProviderId]
     * rejects only blank/NUL, so the staged alias passes vault validation and
     * maps to the entry key `provider_key/<stagedAlias>`. Never derived from
     * user input.
     *
     * Uniqueness MUST be restart-unique, not merely process-wide monotonic: a
     * process-local counter restarts at zero after a relaunch and re-mints
     * `staged-1`, which then overwrites the previous round's committed live
     * `staged-1` in place (and a later abort/retire deletes the live key =
     * loss). The retire inequality guard (`oldAlias != stagedAlias`) also
     * misfires across a restart when the alias is reused. Alias uniqueness
     * therefore comes from the random suffix itself ([newStagedSuffix]), never
     * from the DataStore CAS: the CAS still fences the single metadata slot
     * (single-process only, documented on EndpointStore), but it cannot make
     * two identical aliases distinct.
     */
    fun stagedAlias(providerId: String, suffix: String): String {
        require(suffix.isNotBlank()) { "STAGED_SUFFIX_BLANK" }
        require('\u0000' !in suffix && '\n' !in suffix && '\r' !in suffix) { "STAGED_SUFFIX_MALFORMED" }
        return "$providerId#staged-$suffix"
    }

    /**
     * Fresh restart-unique suffix: full 32 lowercase hex chars from [UUID]
     * (dashes stripped, 122-bit randomness).
     *
     * Collision ~2^-122 per pair, plus a bounded mint-time inequality guard
     * against the snapshot live alias (and an existence-only [dev.librepocket.keystore.KeyVault.hasKey]
     * guard for crash-orphan placeholders) still applies, so even a collision
     * fails closed without touching live bytes. Vault-safe (no blank/NUL/newline,
     * so it passes [dev.librepocket.keystore.EncryptedPrefsVault.requireValidProviderId]).
     * A process-local counter alone is NOT sufficient here (see [stagedAlias]).
     */
    fun newStagedSuffix(): String =
        UUID.randomUUID().toString().replace("-", "").lowercase()

    /** Mint a fresh restart-unique staged alias for one save attempt. */
    fun newStagedAlias(providerId: String): String = stagedAlias(providerId, newStagedSuffix())

    /** Vault entry ref (`provider_key/...`) pointing at a staged alias. */
    fun stagedApiKeyRef(providerId: String, suffix: String): String = "provider_key/" + stagedAlias(providerId, suffix)

    /** Vault entry ref pointing at a freshly minted restart-unique staged alias. */
    fun newStagedApiKeyRef(providerId: String): String = "provider_key/" + newStagedAlias(providerId)

    /**
     * Resolves the vault alias addressed by an endpoint [apiKeyRef]
     * (`provider_key/<alias>` -> `<alias>`). Returns the input unchanged when
     * the prefix is absent; callers MUST treat a missing prefix / blank alias
     * as malformed and fail closed (never touch the vault).
     */
    fun aliasFromRef(apiKeyRef: String): String = apiKeyRef.removePrefix("provider_key/")

    fun validate(presetId: String, baseUrl: String, apiKey: String): String? =
        validate(presetId, baseUrl, apiKey, KeyMode.NEW)

    /**
     * Key-material intent for the length gate (Phase A2, #17 P2).
     *
     * - [NEW]: any fresh key entry (first-run, rotation re-paste, and the
     *   connectivity probes below, which need the key in memory). Blank and
     *   short keys are both rejected.
     * - [EDIT_KEEP_EXISTING]: editing an existing endpoint with the key field
     *   left blank means "reuse the securely stored key" — the length gate is
     *   skipped for a blank key ONLY. A non-blank short key is still rejected
     *   in both modes.
     *
     * The ViewModel (not this pure function) owns the identity binding:
     * EDIT_KEEP_EXISTING is offered only when the form identity (providerId +
     * effectiveBaseUrl 全字串相等（嚴於 origin）) still matches the [prefillForEdit] baseline;
     * any endpoint change falls back to NEW (fail closed, re-paste required).
     * The connectivity probes ([SetupViewModel.testConnection]/
     * [SetupViewModel.refreshModels]) always use NEW — they need the key
     * material in memory and must never trigger a vault read here.
     */
    enum class KeyMode {
        NEW,
        EDIT_KEEP_EXISTING,
    }

    fun validate(presetId: String, baseUrl: String, apiKey: String, mode: KeyMode): String? {
        if (ProviderCatalog.preset(presetId) == null) return "SETUP_UNKNOWN_PRESET"
        val url = baseUrl.trim()
        if (url.isEmpty()) return "SETUP_BASE_URL_BLANK"
        try {
            validateBaseUrl(url)
        } catch (e: IllegalArgumentException) {
            return e.message ?: "PROVIDER_BAD_URL"
        }
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            // Blank key reuses the stored key only under an explicit
            // EDIT_KEEP_EXISTING intent (VM-gated to the unchanged endpoint);
            // every other path still requires a pasted key.
            if (mode == KeyMode.EDIT_KEEP_EXISTING) return null
            return "SETUP_KEY_TOO_SHORT"
        }
        if (trimmed.length < 8) return "SETUP_KEY_TOO_SHORT"
        return null
    }
}
