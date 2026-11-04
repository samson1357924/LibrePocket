package dev.librepocket.agent.ui.chat

import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.policy.HostResolver
import dev.librepocket.policy.NetworkEgressPolicy
import dev.librepocket.policy.NetworkOptInStore
import dev.librepocket.provider.ChatCompletionsProvider
import dev.librepocket.provider.ChatMessage
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.KeyProvider
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderConfig
import dev.librepocket.provider.ProviderFailure
import dev.librepocket.provider.ProviderFailureCode
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase B：活躍 session 撤銷語義（同一物件 revoke／降級後再送被擋）。
 *
 * 選擇 wrapper 單測而非 factory 全鏈：factory 夾具需 Vault＋Policy＋SessionStore＋
 * TurnController＋ViewModel（見 ChatEndpointSessionRegressionTest），重且第二 turn
 * 的斷言需穿越 TurnController 重試／uiState；wrapper 單測用真實
 * [ChatCompletionsProvider]＋MockWebServer＋字面迴環（零真網、bounded takeRequest）
 * 直接證明同一 [OptInResolvingProvider] 物件 revoke 後再送即被 transport 閘擋下。
 * Factory 接線（create／open／openForked 三路經 [ChatSessionFactory.buildSession]
 * 包 wrapper，null store 仍快照）見 [ChatSessionFactory] KDoc 與原始碼。
 */
class OptInLiveProviderTest {

    private fun explodingResolver(): HostResolver = HostResolver { host ->
        throw UnknownHostException("must not resolve literals: $host")
    }

    private fun sseOk(): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n",
        )

    private fun modelsOk(): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("{\"data\":[{\"id\":\"test-model\"}]}")

    private fun endpointOf(base: String, revision: Long): EndpointConfig = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "t",
        baseUrl = base,
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "test-model",
        apiKeyRef = "provider_key/fake-ref",
        configRevision = revision,
    )

    private fun liveWrapper(
        endpoint: EndpointConfig,
        store: NetworkOptInStore,
        baseConfig: ProviderConfig,
        keys: KeyProvider,
    ): OptInResolvingProvider {
        val build: (ProviderConfig, KeyProvider) -> LlmProvider = { cfg, k ->
            ChatCompletionsProvider(cfg, { k.keyFor(cfg.apiKeyRef) }, OkHttpClient())
        }
        return OptInResolvingProvider(endpoint, store, baseConfig, keys, build)
    }

    @Test fun streamSameObjectRevokedByInvalidateIsBlockedWithZeroNewRequest() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val base = "http://127.0.0.1:${server.port}/v1"
            val store = NetworkOptInStore(allowTierUpgrade = true)
            store.confirm("preset:openai", base, NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE, 1L)
            val endpoint = endpointOf(base, 1L)
            val baseOptIn = store.optInFor(endpoint.providerId, endpoint.baseUrl, endpoint.configRevision)
            assertEquals(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE, baseOptIn.policy)
            val keyCalls = AtomicInteger(0)
            val keys = KeyProvider { keyCalls.incrementAndGet(); "K".toCharArray() }
            val baseConfig = ProviderConfig(
                id = endpoint.providerId,
                label = "t",
                baseUrl = base,
                protocol = ProviderProtocol.CHAT_COMPLETIONS,
                apiKeyRef = "provider_key/fake-ref",
                networkOptIn = baseOptIn,
                networkResolver = explodingResolver(),
            )
            val live = liveWrapper(endpoint, store, baseConfig, keys)

            // 第一次：放行成功。
            server.enqueue(sseOk())
            val first = withTimeout(5_000) {
                live.stream(ChatRequest("test-model", listOf(ChatMessage("user", "hi")))).toList()
            }
            assertTrue("expected Done, got: $first", first.last() is StreamEvent.Done)
            assertTrue(first.none { it is StreamEvent.Failed })
            assertEquals(1, server.requestCount)
            assertEquals(1, keyCalls.get())
            val recorded = server.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull(recorded)
            assertTrue(recorded!!.path.orEmpty().endsWith("/chat/completions"))

            // 撤銷：同一物件再送必須被擋，0 新增 request。
            store.invalidate(endpoint.providerId)
            val second = withTimeout(5_000) {
                live.stream(ChatRequest("test-model", listOf(ChatMessage("user", "hi2")))).toList()
            }
            val failed = second.filterIsInstance<StreamEvent.Failed>().single()
            assertFalse("policy deny must not retry", failed.retryable)
            assertEquals(ProviderFailureCode.NETWORK_POLICY_BLOCKED.name, failed.code)
            assertTrue(
                "deny carries code, got: ${failed.message}",
                failed.message.contains("CLEARTEXT_NOT_PERMITTED") ||
                    failed.message.contains("PRIVATE_ADDRESS_BLOCKED"),
            )
            assertEquals("revoked stream must send nothing", 1, server.requestCount)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
            assertEquals("denied stream must not fetch key", 1, keyCalls.get())
        } finally {
            server.shutdown()
        }
    }

    @Test fun listModelsSameObjectDowngradedToHttpsOnlyThrowsBlockedWithZeroNewRequest() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val base = "http://127.0.0.1:${server.port}/v1"
            val store = NetworkOptInStore(allowTierUpgrade = true)
            store.confirm("preset:openai", base, NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE, 1L)
            val endpoint = endpointOf(base, 1L)
            val baseOptIn = store.optInFor(endpoint.providerId, endpoint.baseUrl, endpoint.configRevision)
            val keyCalls = AtomicInteger(0)
            val keys = KeyProvider { keyCalls.incrementAndGet(); "K".toCharArray() }
            val baseConfig = ProviderConfig(
                id = endpoint.providerId,
                label = "t",
                baseUrl = base,
                protocol = ProviderProtocol.CHAT_COMPLETIONS,
                apiKeyRef = "provider_key/fake-ref",
                networkOptIn = baseOptIn,
                networkResolver = explodingResolver(),
            )
            val live = liveWrapper(endpoint, store, baseConfig, keys)

            // 第一次：放行成功。
            server.enqueue(modelsOk())
            val models = withTimeout(5_000) { live.listModels() }
            assertEquals(listOf("test-model"), models)
            assertEquals(1, server.requestCount)
            assertEquals(1, keyCalls.get())
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

            // 降級：同 revision confirm HTTPS_ONLY，同一物件再調必須拋 typed fatal。
            store.confirm(endpoint.providerId, base, NetworkEgressPolicy.HTTPS_ONLY, 1L)
            try {
                withTimeout(5_000) { live.listModels() }
                fail("downgraded listModels must throw")
            } catch (e: ProviderFailure) {
                assertFalse(e.retryable)
                assertEquals(ProviderFailureCode.NETWORK_POLICY_BLOCKED, e.code)
                assertTrue(
                    "deny carries code, got: ${e.message}",
                    e.message.orEmpty().contains("CLEARTEXT_NOT_PERMITTED") ||
                        e.message.orEmpty().contains("PRIVATE_ADDRESS_BLOCKED"),
                )
            }
            assertEquals("downgraded listModels must send nothing", 1, server.requestCount)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
            assertEquals("denied listModels must not fetch key", 1, keyCalls.get())
        } finally {
            server.shutdown()
        }
    }
}
