package dev.librepocket.agent.ui.chat

import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.policy.EndpointNetworkOptIn
import dev.librepocket.policy.HostResolver
import dev.librepocket.policy.SystemHostResolver
import dev.librepocket.preset.ProviderCatalog
import dev.librepocket.provider.ProviderConfig
import dev.librepocket.provider.ProviderProtocol
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Maps the persisted endpoint (Setup) to the transport config (provider).
 * Single source of truth for the EndpointConfig -> ProviderConfig boundary.
 *
 * S2 (Issue #135 A2): per-endpoint opt-in 經 [networkOptIn] 傳入，預設
 * deny-all（HTTPS_ONLY）。Play 維持此 tier，永不升級（註解＋測試釘死）；
 * foss／github pilot 才可經 `NetworkOptInStore.confirm` 明示確認升級。
 */
fun EndpointConfig.toProviderConfig(
    networkOptIn: EndpointNetworkOptIn = EndpointNetworkOptIn(),
    networkResolver: HostResolver = SystemHostResolver,
): ProviderConfig =
    if (presetId == ProviderCatalog.CUSTOM_ID) {
        ProviderCatalog.fromCustom(
            baseUrl = baseUrl,
            apiKeyRef = apiKeyRef,
            label = label.ifBlank { "自訂" },
            protocol = protocol,
            providerId = providerId,
            networkOptIn = networkOptIn,
            networkResolver = networkResolver,
        )
    } else {
        ProviderCatalog.fromPreset(
            presetId = presetId,
            apiKeyRef = apiKeyRef,
            providerId = providerId,
            networkOptIn = networkOptIn,
            networkResolver = networkResolver,
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

/**
 * Normalizes a base URL into an RFC 6454-style web origin: scheme://host[:port].
 * Default ports for known schemes (e.g. 80 for http, 443 for https) are omitted.
 * Returns an empty string if baseUrl is invalid or lacks scheme/host.
 * HttpUrl 視角解析（fail-closed：malformed 回 ""）；userinfo 不納入 origin。
 */
fun normalizeOrigin(baseUrl: String): String {
    val url = baseUrl.toHttpUrlOrNull() ?: return ""
    val scheme = url.scheme.lowercase(Locale.ROOT)
    val host = url.host.lowercase(Locale.ROOT)
    if (scheme.isEmpty() || host.isEmpty()) return ""
    val defaultPort = when (scheme) {
        "http" -> 80
        "https" -> 443
        else -> return ""
    }
    val port = if (url.port != defaultPort) ":${url.port}" else ""
    return "$scheme://$host$port"
}

fun EndpointConfig.toSessionBinding(model: String): EndpointSessionBinding {
    val effective = toProviderConfig()
    return EndpointSessionBinding(
        providerId = providerId,
        effectiveBaseUrl = effective.baseUrl,
        origin = normalizeOrigin(effective.baseUrl),
        protocol = effective.protocol,
        apiKeyRef = effective.apiKeyRef,
        configRevision = configRevision,
        model = model,
    )
}
