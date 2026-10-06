package dev.librepocket.agent.ui.chat

import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.preset.ProviderCatalog
import dev.librepocket.provider.ProviderConfig

/**
 * Maps the persisted endpoint (Setup) to the transport config (provider).
 * Single source of truth for the EndpointConfig -> ProviderConfig boundary.
 */
fun EndpointConfig.toProviderConfig(): ProviderConfig =
    if (presetId == ProviderCatalog.CUSTOM_ID) {
        ProviderCatalog.fromCustom(
            baseUrl = baseUrl,
            apiKeyRef = apiKeyRef,
            label = label.ifBlank { "自訂" },
            protocol = protocol,
            providerId = providerId,
        )
    } else {
        ProviderCatalog.fromPreset(
            presetId = presetId,
            apiKeyRef = apiKeyRef,
            providerId = providerId,
        )
    }
