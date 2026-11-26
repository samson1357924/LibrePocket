package dev.librepocket.agent.ui.chat

import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.provider.ChatRequest
import dev.librepocket.chat.TurnStart
import dev.librepocket.keystore.KeyVault
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.router.SafeToolSlice
import dev.librepocket.session.FakeSessionStore
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Issue #18 production boundary contracts — the cells not covered by
 * [ProductionChatCompositionTest] (records-only tool projection) or
 * `FastDispatchSurfaceArchitectureTest` (no fast-lane source caller):
 *
 * 1. the PUBLIC constructor surface of the production composition root
 *    ([ChatViewModelFactory]) cannot inject a tool slice or a fast-lane
 *    component (`forTesting` is a test seam; `ChatSessionFactory` deliberately
 *    keeps its own `toolSlice` constructor parameter and is out of scope here);
 * 2. `key.read` ASK is never promoted to ALLOW at the session-factory boundary:
 *    no vault acquisition, no key read, no provider build;
 * 3. a fresh-policy evaluation failure fails closed with the same zero
 *    side effects (the stale `evaluate()` decoy returns ALLOW on purpose);
 * 4. cancelling a fetch parked in (simulated) blocking IO wakes the provider,
 *    propagates CancellationException, and performs zero continuation / tool
 *    side effects.
 *
 * 全部使用合成 endpoint／假 key／FakeSessionStore，零真網、零 keystore、零私人
 * transcript。此檔是 JVM contract source，不是裝置實跑證據；
 * 真正的 blocking OkHttp IO 取消見 `McpTransportCancelTest`（JVM）與
 * `CancelBoundaryInstrumentedTest`（裝置，待串行實跑）。
 */
class ProductionBoundaryContractTest {

    private fun endpoint() = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "gpt-4o-mini",
        apiKeyRef = "provider_key/preset:openai",
    )

    private class StubProvider : LlmProvider {
        override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
        override fun stream(request: ChatRequest): Flow<StreamEvent> = emptyFlow()
        override suspend fun listModels(): List<String> = emptyList()
    }

    private class RecordingVault : KeyVault {
        val reads = AtomicInteger(0)
        override suspend fun putKey(providerId: String, apiKey: CharArray) = Unit
        override suspend fun getKey(providerId: String): CharArray? {
            reads.incrementAndGet()
            return "synthetic-test-key".toCharArray()
        }
        override suspend fun deleteKey(providerId: String) = Unit
        override suspend fun hasKey(providerId: String): Boolean = true
    }

    @Test
    fun productionCompositionRootPublicConstructorCannotInjectToolSliceOrFastLane() {
        // Scope: ChatViewModelFactory is the production composition root.
        // ChatSessionFactory intentionally keeps a `toolSlice` test seam.
        val publicCtors = ChatViewModelFactory::class.java.constructors
        assertTrue("production factory must expose a public constructor", publicCtors.isNotEmpty())
        val forbiddenNames = listOf(
            SafeToolSlice::class.java.name,
            "dev.librepocket.router.FastDispatcher",
            "dev.librepocket.router.FastRouter",
            "dev.librepocket.router.ElevatedWiring",
        )
        // Kotlin emits public synthetic default-arg constructors for the
        // private primary constructor (parameter mask + DefaultConstructorMarker),
        // including one carrying the test-only `toolSlice` parameter. Filter
        // those out: only real source-level public constructors are in scope.
        for (ctor in publicCtors.filterNot { ctor ->
            ctor.isSynthetic ||
                ctor.parameterTypes.lastOrNull()?.name == "kotlin.jvm.internal.DefaultConstructorMarker"
        }) {
            for (type in ctor.parameterTypes) {
                assertTrue(
                    "public production constructor must not inject ${type.name}",
                    forbiddenNames.none { type.name == it },
                )
            }
        }
    }

    @Test
    fun askVerdictIsNeverPromotedToAllowAtFactoryBoundary() = runBlocking {
        // Default InMemoryPolicyStore keeps key.read ASK; no T0 consent proof is
        // threaded here (allowAsk=false). A promotion to ALLOW would read the key.
        val vault = RecordingVault()
        val vaultAcquisitions = AtomicInteger()
        val providerBuilds = AtomicInteger()
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = VaultSource {
                vaultAcquisitions.incrementAndGet()
                vault
            },
            buildProvider = { _, _ ->
                providerBuilds.incrementAndGet()
                StubProvider()
            },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = FakeSessionStore()
            },
        )
        assertNull("records-only factory must keep toolSlice null", factory.toolSlice)
        try {
            factory.create(endpoint(), "ask boundary", allowAsk = false, keyIsCurrent = { true })
            fail("ASK must never be promoted to ALLOW at the factory boundary")
        } catch (expected: CaptureDeniedException) {
            assertEquals(0, vaultAcquisitions.get())
            assertEquals("ASK must not read the key", 0, vault.reads.get())
            assertEquals(0, providerBuilds.get())
        }
    }

    @Test
    fun policyEvaluationFailureFailsClosedWithZeroSideEffects() = runBlocking {
        // Negative control: stale evaluate() says ALLOW, so a regression to the
        // cached path would pass and read the key (turning this test red).
        val policy = object : PolicyStore {
            override fun evaluate(action: String, resource: String) =
                PolicyDecision(Verdict.ALLOW, null, 0L)
            override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision =
                throw IllegalStateException("fresh-policy-boom")
            override suspend fun setRule(rule: PolicyRule) = Unit
            override suspend fun removeRule(pattern: String) = Unit
            override suspend fun listRules(): List<PolicyRule> = emptyList()
        }
        val vault = RecordingVault()
        val vaultAcquisitions = AtomicInteger()
        val providerBuilds = AtomicInteger()
        val factory = ChatSessionFactory(
            policy = policy,
            vaultSource = VaultSource {
                vaultAcquisitions.incrementAndGet()
                vault
            },
            buildProvider = { _, _ ->
                providerBuilds.incrementAndGet()
                StubProvider()
            },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = FakeSessionStore()
            },
        )
        try {
            factory.create(endpoint(), "policy failure boundary", allowAsk = false, keyIsCurrent = { true })
            fail("policy evaluation failure must fail closed")
        } catch (expected: CaptureDeniedException) {
            assertEquals(0, vaultAcquisitions.get())
            assertEquals(0, vault.reads.get())
            assertEquals(0, providerBuilds.get())
        }
    }

    @Test
    fun cancelReleasesBlockedFetchWithNoContinuationOrToolSideEffects() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val interrupted = CompletableDeferred<Unit>()
        val streams = AtomicInteger(0)
        val provider = object : LlmProvider {
            override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
            override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
                val n = streams.incrementAndGet()
                assertTrue("records-only session must offer no tools", request.tools.isEmpty())
                if (n == 1) {
                    entered.complete(Unit)
                    try {
                        awaitCancellation() // 替代不可中斷的 blocking IO 等待
                    } catch (e: CancellationException) {
                        interrupted.complete(Unit)
                        throw e
                    }
                } else {
                    emit(StreamEvent.TextDelta(0, 0, "不應到達的續聊"))
                    emit(StreamEvent.Done("stop"))
                }
            }
            override suspend fun listModels(): List<String> = emptyList()
        }
        val store = FakeSessionStore()
        val factory = ChatSessionFactory(
            policy = allowKeyReadPolicy(),
            vaultSource = VaultSource { RecordingVault() },
            buildProvider = { _, _ -> provider },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = store
            },
        )
        assertNull("records-only factory must keep toolSlice null", factory.toolSlice)
        val created = factory.create(endpoint(), "cancel boundary", allowAsk = false, keyIsCurrent = { true })
        try {
            val started = created.session.startOrEnqueue("cancel me")
            assertTrue("admission must start, was $started", started is TurnStart.Started)
            val host = (started as TurnStart.Started).host
            withTimeout(5_000) { entered.await() }
            created.session.cancel()
            // 取消必須喚醒被擋住的 fetch（信號導向，不是固定 sleep）。
            assertTrue("cancel must release the blocked fetch", withTimeout(5_000) { interrupted.await(); true })
            withTimeout(8_000) { host.join() }
            assertEquals("no continuation after cancel", 1, streams.get())
            assertTrue("transcript must drain after cancel", created.session.flush(8_000))
            val events = ArrayList(store.events)
            assertEquals(
                "exactly one assistant row for the cancelled attempt: $events",
                1,
                events.count { it.kind == "assistant" },
            )
            assertTrue("family must be closed: $events", findDanglingRunIds(events).isEmpty())
            assertFalse("no tool execution side effect", events.any { it.text.contains("[tool-result:") })
        } finally {
            created.session.close()
        }
    }
}
