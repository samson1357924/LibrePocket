package dev.librepocket.provider

import java.util.Locale
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
 * http 例外僅字面 loopback／RFC1918（`localhost`、`127.0.0.1`、`::1`、
 * 字面 `10/8`、`172.16/12`、`192.168/16` 精確比對）；域名一律 https
 * （如 `10.evil.com` 不放行，不做 DNS，只做字面比對）。
 * IPv6 字面 loopback 已覆蓋 `::1`，其餘一律不放行。
 * 與 NetworkPolicy 位元組分類一致方向，transport 仍是最終閘。
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
    val scheme = url.protocol.lowercase(Locale.ROOT)
    if (scheme == "https") return
    if (scheme == "http") {
        // URL.getHost() returns bracketed IPv6 ("[::1]"): strip brackets.
        val host = url.host.lowercase(Locale.ROOT).trim('[', ']')
        if (host == "localhost" || host == "127.0.0.1" || host == "::1") return
        // 僅 IPv4 字面精確比對（RFC1918）；域名一律不算 local
        //（如 10.evil.com 不再放行）。不用 DNS，只做字面比對。
        if (isPrivateIpv4Literal(host)) return
    }
    throw IllegalArgumentException("PROVIDER_URL_MUST_BE_HTTPS")
}

private val IPV4_LITERAL = Regex("""^\d+\.\d+\.\d+\.\d+$""")

private fun isPrivateIpv4Literal(host: String): Boolean {
    if (!IPV4_LITERAL.matches(host)) return false
    val parts = host.split('.').map { it.toIntOrNull() ?: return false }
    if (parts.size != 4 || parts.any { it !in 0..255 }) return false
    if (parts[0] == 10) return true // 10/8
    if (parts[0] == 192 && parts[1] == 168) return true // 192.168/16
    if (parts[0] == 172 && parts[1] in 16..31) return true // 172.16/12
    return false
}
