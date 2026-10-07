package dev.librepocket.agent.ui.chat

import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.provider.ProviderProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EndpointSessionBindingTest {
    @Test
    fun bindingTracksEffectiveOriginProtocolKeyReferenceAndRevision() {
        val endpoint = EndpointConfig(
            providerId = "preset:custom",
            presetId = "custom",
            label = "custom",
            baseUrl = "http://127.0.0.1:8123/v1",
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            model = "same-model",
            apiKeyRef = "provider_key/shared",
            configRevision = 4L,
        )
        val binding = endpoint.toSessionBinding("same-model")
        val otherOrigin = endpoint.copy(baseUrl = "http://127.0.0.1:8124/v1")
            .toSessionBinding("same-model")

        assertEquals("http://127.0.0.1:8123/v1", binding.effectiveBaseUrl)
        assertEquals("http://127.0.0.1:8123", binding.origin)
        assertNotEquals(binding, otherOrigin)
        assertNotEquals(binding, endpoint.copy(protocol = ProviderProtocol.RESPONSES).toSessionBinding("same-model"))
        assertNotEquals(binding, endpoint.copy(apiKeyRef = "provider_key/rotated").toSessionBinding("same-model"))
        assertNotEquals(binding, endpoint.copy(configRevision = 5L).toSessionBinding("same-model"))
    }
}
