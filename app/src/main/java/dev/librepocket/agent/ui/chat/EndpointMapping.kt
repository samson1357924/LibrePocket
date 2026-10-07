package dev.librepocket.agent.ui.chat

import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.preset.ProviderCatalog
import dev.librepocket.provider.ProviderConfig
import dev.librepocket.provider.ProviderProtocol
import java.net.URL
import java.util.Locale

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

/**
 * Immutable connection identity for one live session. This intentionally
 * records the *effective* transport config after preset/custom mapping rather
 * than relying on the UI preset id as a proxy for the destination.
 */
data class EndpointSessionBinding(
    val providerId: String,
    val effectiveBaseUrl: String,
    val origin: String,
    val protocol: ProviderProtocol,
    val apiKeyRef: String,
    val configRevision: Long,
    val model: String,
)

fun EndpointConfig.toSessionBinding(model: String): EndpointSessionBinding {
    val effective = toProviderConfig()
    val url = URL(effective.baseUrl)
    val scheme = url.protocol.lowercase(Locale.ROOT)
    val host = url.host.lowercase(Locale.ROOT)
    val port = url.port.takeIf { it >= 0 && it != url.defaultPort }?.let { ":$it" }.orEmpty()
    return EndpointSessionBinding(
        providerId = providerId,
        effectiveBaseUrl = effective.baseUrl,
        origin = "$scheme://$host$port",
        protocol = effective.protocol,
        apiKeyRef = effective.apiKeyRef,
        configRevision = configRevision,
        model = model,
    )
}
