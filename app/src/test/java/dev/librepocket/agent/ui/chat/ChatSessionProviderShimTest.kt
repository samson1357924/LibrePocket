package dev.librepocket.agent.ui.chat

import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.provider.ChatMessage
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.session.SessionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D2 shim regression: the canonical forms are the 4-arg [ChatSessionProvider.create]
 * and 4-arg [ChatSessionProvider.open]; the 3-arg overloads are concrete compat
 * shims (`initialHistory = emptyList()`, `backfillInterrupted = true`,
 * symmetric). A provider that overrides only the canonical forms inherits both
 * shims without a silent param drop.
 */
class ChatSessionProviderShimTest {

  private fun sampleEndpoint() = EndpointConfig(
    providerId = "preset:openai",
    presetId = "openai",
    label = "OpenAI",
    baseUrl = "https://api.openai.com/v1",
    protocol = ProviderProtocol.CHAT_COMPLETIONS,
    model = "gpt-4o-mini",
    apiKeyRef = "provider_key/preset:openai",
  )

  private class CanonicalOnlyProvider : ChatSessionProvider {
    var lastInitialHistory: List<ChatMessage>? = null
    var lastBackfill: Boolean? = null

    override suspend fun create(
      endpoint: EndpointConfig,
      title: String,
      initialHistory: List<ChatMessage>,
      keyIsCurrent: suspend () -> Boolean,
    ): CreatedSession {
      lastInitialHistory = initialHistory
      throw UnsupportedOperationException("create shim probe: no live session")
    }

    override suspend fun open(
      endpoint: EndpointConfig,
      sessionId: String,
      keyIsCurrent: suspend () -> Boolean,
      backfillInterrupted: Boolean,
    ): CreatedSession {
      lastBackfill = backfillInterrupted
      throw UnsupportedOperationException("open shim probe: backfill=$backfillInterrupted")
    }

    override suspend fun storeOrNull(): SessionStore? = null

    override fun modelFor(endpoint: EndpointConfig): String = endpoint.model
  }

  @Test fun threeArgCreate_forwardsEmptyPrefixToCanonical() = runBlocking {
    val provider = CanonicalOnlyProvider()
    try {
      provider.create(sampleEndpoint(), "t", { true })
      throw AssertionError("canonical probe must throw")
    } catch (e: UnsupportedOperationException) {
      assertTrue(e.message!!.startsWith("create shim probe"))
    }
    assertEquals(emptyList<ChatMessage>(), provider.lastInitialHistory)
  }

  @Test fun threeArgOpen_forwardsBackfillTrueToCanonical() = runBlocking {
    val provider = CanonicalOnlyProvider()
    try {
      provider.open(sampleEndpoint(), "sid", { true })
      throw AssertionError("canonical probe must throw")
    } catch (e: UnsupportedOperationException) {
      assertTrue(e.message!!.contains("backfill=true"))
    }
    assertEquals(true, provider.lastBackfill)
  }
}
