package dev.librepocket.agent.ui.chat

import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.SetupValidation
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.FakeSessionStore
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class ScopeFakeProvider : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    override fun stream(request: ChatRequest): Flow<StreamEvent> = emptyFlow()
    override suspend fun listModels(): List<String> = emptyList()
}

/**
 * Stage1 entity/policy split pins (C1): `key.read` is evaluated against the
 * preset-shared authorization scope (`preset:<presetId>`), never the
 * per-endpoint transport identity (`custom:<hash>`).
 *
 * Existing key.read tests use wildcard rules (`key.read:*`), under which a
 * wrong resource still matches — these pins use exact-resource rules so a
 * resource mix-up fails closed and red.
 */
class ChatKeyReadPolicyScopeTest {

    private val customUrl = "http://127.0.0.1:11434/v1"

    private fun customEndpoint(): EndpointConfig {
        val id = SetupValidation.deriveEndpointId("custom", customUrl)
        return EndpointConfig(
            providerId = id,
            presetId = "custom",
            label = "Custom",
            baseUrl = customUrl,
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            model = "local-model",
            apiKeyRef = SetupValidation.deriveApiKeyRef(id),
        )
    }

    private fun factoryWith(policy: InMemoryPolicyStore, vaultAcquisitions: AtomicInteger) =
        ChatSessionFactory(
            policy = policy,
            vaultSource = VaultSource {
                vaultAcquisitions.incrementAndGet()
                val vault = EncryptedPrefsVault(InMemoryPrefs())
                vault.putKey(
                    SetupValidation.deriveEndpointId("custom", customUrl),
                    "test-key-12345678".toCharArray(),
                )
                vault
            },
            buildProvider = { _, _ -> ScopeFakeProvider() },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = FakeSessionStore()
            },
        )

    @Test fun createWithExactPresetScopeRuleSucceeds() = runBlocking {
        // Only the preset-shared resource is allowed; the endpoint id has no
        // rule. Success proves create() queries the preset scope.
        val policy = InMemoryPolicyStore(
            initialRules = listOf(PolicyRule("key.read:preset:custom", Verdict.ALLOW, 10)),
        )
        val vaultAcquisitions = AtomicInteger(0)
        val created = factoryWith(policy, vaultAcquisitions).create(customEndpoint(), "scope pin")
        try {
            assertTrue(vaultAcquisitions.get() >= 1)
        } finally {
            created.session.close()
        }
    }

    @Test fun createWithExactEndpointIdRuleIsDeniedWithZeroVaultReads() = runBlocking {
        // Only the per-endpoint identity is allowed; the preset scope has no
        // rule (unmatched = DENY). Denial proves create() never queries the
        // endpoint id — an ALLOW on the transport identity grants nothing.
        val endpointId = SetupValidation.deriveEndpointId("custom", customUrl)
        val policy = InMemoryPolicyStore(
            initialRules = listOf(PolicyRule("key.read:$endpointId", Verdict.ALLOW, 10)),
        )
        val vaultAcquisitions = AtomicInteger(0)
        try {
            factoryWith(policy, vaultAcquisitions).create(customEndpoint(), "must deny")
            fail("endpoint-id-scoped ALLOW must not authorize key.read")
        } catch (e: CaptureDeniedException) {
            assertEquals(0, vaultAcquisitions.get())
        }
    }

    @Test fun openForkPathWithExactPresetScopeRuleSucceeds() = runBlocking {
        // Same pin through the fork path: a cross-origin ledger must fork
        // (not deny) when the preset scope allows.
        val policy = InMemoryPolicyStore(
            initialRules = listOf(PolicyRule("key.read:preset:custom", Verdict.ALLOW, 10)),
        )
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession(
            "old chat",
            "preset:openai@https://api.openai.com/gpt-4o-mini",
        )
        val vaultAcquisitions = AtomicInteger(0)
        val factory = ChatSessionFactory(
            policy = policy,
            vaultSource = VaultSource {
                vaultAcquisitions.incrementAndGet()
                val vault = EncryptedPrefsVault(InMemoryPrefs())
                vault.putKey(
                    SetupValidation.deriveEndpointId("custom", customUrl),
                    "test-key-12345678".toCharArray(),
                )
                vault
            },
            buildProvider = { _, _ -> ScopeFakeProvider() },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = transcripts
            },
        )
        val opened = factory.open(customEndpoint(), sid)
        try {
            assertTrue(opened.historyWithheld)
        } finally {
            opened.session.close()
        }
    }
}
