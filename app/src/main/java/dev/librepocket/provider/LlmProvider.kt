package dev.librepocket.provider

import kotlinx.coroutines.flow.Flow

/**
 * Provider client facade (SPEC §1.2).
 *
 * Each [stream] collection performs at most one provider HTTP attempt.
 * Providers classify a failure into one terminal [StreamEvent.Failed]; they
 * never retry internally. Turn-level retries and the total attempt budget are
 * owned by TurnController.
 *
 * Cancellation is bound to the whole blocking HTTP call, including response
 * body reads: cancelling collection calls OkHttp Call.cancel() immediately.
 * Protocol terminal frames close the response and stop reading even if the
 * peer leaves the stream open.
 */
interface LlmProvider {
    val protocol: ProviderProtocol

    /**
     * Stream one provider attempt. Failures are classified (SPEC §5.2) and
     * surfaced as one terminal [StreamEvent.Failed]. Retryable failures are
     * retried only by the logical-turn owner, not inside this flow.
     */
    fun stream(request: ChatRequest): Flow<StreamEvent>

    /** One-shot model listing (settings "test connection"); never SSE. Redirects fail closed. */
    suspend fun listModels(): List<String>
}

interface ProviderFactory {
    fun create(config: ProviderConfig): LlmProvider
}

/** Supplies the secret for an [ProviderConfig.apiKeyRef]; wiped after use. */
fun interface KeyProvider {
    suspend fun keyFor(apiKeyRef: String): CharArray?
}

/**
 * Default factory: validates [ProviderConfig] (§7.2) and builds the
 * per-protocol adapter. The factory never logs or stores key material.
 */
class DefaultProviderFactory(
    private val keys: KeyProvider,
    private val clientFactory: (ProviderHttpConfig) -> okhttp3.OkHttpClient = ::defaultOkHttpClient,
) : ProviderFactory {
    override fun create(config: ProviderConfig): LlmProvider {
        validateBaseUrl(config.baseUrl)
        val apiKeys: suspend () -> CharArray? = { keys.keyFor(config.apiKeyRef) }
        return when (config.protocol) {
            ProviderProtocol.CHAT_COMPLETIONS ->
                ChatCompletionsProvider(config, apiKeys, clientFactory(config.http))
            ProviderProtocol.RESPONSES ->
                ResponsesProvider(config, apiKeys, clientFactory(config.http))
            ProviderProtocol.ANTHROPIC ->
                AnthropicProvider(config, apiKeys, clientFactory(config.http))
        }
    }
}

/**
 * Base-URL validation (SPEC §7.2 + §10.2 TLS row):
 * `https://` required; `http://` only for loopback / RFC1918 development
 * hosts, otherwise [IllegalArgumentException].
 *
 * Runtime note: release builds deny ALL cleartext via
 * `res/xml/network_security_config.xml`, so even a validated http:// URL only
 * connects in debug builds (whose overlay permits loopback +
 * emulator-host cleartext) — LAN http:// stays refused at runtime by design.
 */
fun validateBaseUrl(baseUrl: String) {
    val url = try {
        java.net.URL(baseUrl)
    } catch (e: java.net.MalformedURLException) {
        throw IllegalArgumentException("PROVIDER_BAD_URL", e)
    }
    val scheme = url.protocol.lowercase()
    if (scheme == "https") return
    if (scheme == "http") {
        // URL.getHost() returns bracketed IPv6 ("[::1]"): strip brackets.
        val host = url.host.lowercase().trim('[', ']')
        val local = host == "localhost" ||
            host == "127.0.0.1" ||
            host == "::1" ||
            host.startsWith("10.") ||
            host.startsWith("192.168.") ||
            host.startsWith("172.") && isPrivate172(host)
        if (local) return
    }
    throw IllegalArgumentException("PROVIDER_URL_MUST_BE_HTTPS")
}

private fun isPrivate172(host: String): Boolean {
    val second = host.removePrefix("172.").substringBefore('.').toIntOrNull() ?: return false
    return second in 16..31
}
