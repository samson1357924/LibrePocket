package dev.librepocket.agent.ui.chat

import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.preset.ProviderCatalog
import dev.librepocket.provider.ProviderHttpConfig
import dev.librepocket.provider.ProviderProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointMappingTest {

    private fun sample() = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "gpt-4o-mini",
        apiKeyRef = "provider_key/preset:openai",
    )

    @Test
    fun presetMappingPreservesFields() {
        val config = sample().toProviderConfig()
        assertEquals("preset:openai", config.id)
        assertEquals("OpenAI", config.label)
        assertEquals("https://api.openai.com/v1", config.baseUrl)
        assertEquals(ProviderProtocol.CHAT_COMPLETIONS, config.protocol)
        assertEquals("provider_key/preset:openai", config.apiKeyRef)
        assertEquals(ProviderHttpConfig(), config.http)
    }

    @Test
    fun anthropicKeepsNativeProtocol() {
        val config = sample().copy(
            providerId = "preset:anthropic",
            presetId = "anthropic",
            label = "Anthropic",
            baseUrl = ProviderCatalog.ANTHROPIC_BASE_URL,
            protocol = ProviderProtocol.ANTHROPIC,
            apiKeyRef = "provider_key/preset:anthropic",
        ).toProviderConfig()
        assertEquals(ProviderProtocol.ANTHROPIC, config.protocol)
        assertEquals(ProviderCatalog.ANTHROPIC_BASE_URL, config.baseUrl)
    }

    @Test
    fun customPassthroughAndBlankLabel() {
        val config = sample().copy(
            providerId = "preset:custom",
            presetId = "custom",
            label = "",
            baseUrl = "http://127.0.0.1:11434/v1",
            model = "local-model",
        ).toProviderConfig()
        assertEquals("preset:custom", config.id)
        assertEquals("自訂", config.label)
        assertEquals("http://127.0.0.1:11434/v1", config.baseUrl)
    }

    @Test
    fun normalizeOriginHandlesPortsAndPaths() {
        assertEquals("https://api.openai.com", normalizeOrigin("https://api.openai.com/v1"))
        assertEquals("https://api.openai.com", normalizeOrigin("https://api.openai.com:443/v1"))
        assertEquals("http://127.0.0.1:11434", normalizeOrigin("http://127.0.0.1:11434/v1"))
        assertEquals("http://localhost", normalizeOrigin("http://localhost:80/path"))
        assertEquals("", normalizeOrigin("invalid-url"))
    }

    @Test
    fun normalizeOriginBracketsIpv6() {
        // Phase4：字面 IPv6 補括號才成合法 origin；預設埠照樣省略。
        assertEquals("http://[::1]:8080", normalizeOrigin("http://[::1]:8080/v1"))
        assertEquals("http://[::1]", normalizeOrigin("http://[::1]:80/"))
        assertEquals("https://[fd00::1]", normalizeOrigin("https://[fd00::1]/v1"))
        assertEquals("https://[fd00::1]:8443", normalizeOrigin("https://[fd00::1]:8443/v1"))
        assertEquals("", normalizeOrigin("http://[::1"))
    }
}
