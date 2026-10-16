package dev.librepocket.agent.ui.chat

import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.SetupValidation
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

    @Test
    fun perUrlEntityIdsKeepCustomBindingsDistinct() {
        // Stage1: same preset + same model, different custom URLs derive
        // distinct entity ids, so the bindings can never compare equal on a
        // shared providerId field.
        val urlA = "http://127.0.0.1:8123/v1"
        val urlB = "http://127.0.0.1:8124/v1"
        val idA = SetupValidation.deriveEndpointId("custom", urlA)
        val idB = SetupValidation.deriveEndpointId("custom", urlB)
        assertNotEquals(idA, idB)
        fun bindingFor(url: String, id: String, ref: String) = EndpointConfig(
            providerId = id,
            presetId = "custom",
            label = "custom",
            baseUrl = url,
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            model = "same-model",
            apiKeyRef = ref,
            configRevision = 4L,
        ).toSessionBinding("same-model")
        val a = bindingFor(urlA, idA, "provider_key/alias-a")
        val b = bindingFor(urlB, idB, "provider_key/alias-b")
        assertNotEquals(a.providerId, b.providerId)
        assertNotEquals(a, b)
    }
}
