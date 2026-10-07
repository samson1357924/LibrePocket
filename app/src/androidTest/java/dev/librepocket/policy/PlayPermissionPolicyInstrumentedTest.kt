package dev.librepocket.policy

import android.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.librepocket.agent.MainActivity
import dev.librepocket.provider.ChatCompletionsProvider
import dev.librepocket.provider.ProviderConfig
import dev.librepocket.provider.ProviderProtocol
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import org.hamcrest.CoreMatchers.allOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Play-flavor policy/transport contract checks (SPEC §10.3 / §11.5 [I]).
 *
 * The policy decision tests use the actual single-segment endpoint provider id
 * `preset:openai` (the colon is not a resource-path separator). The dialog
 * test constructs a test-hosted fixture only; it does not exercise product ASK
 * consent wiring. The test-only gate drives the real
 * `listModels` HTTP request for its ALLOW positive control and proves that both
 * DENY and ASK return before transport. Setup/dispatch ASK behavior (issue #13)
 * remains outside this scope and is not claimed as fixed here.
 *
 * The nightly workflow is configured for API 33/34 across Play, Foss, and
 * Github. That configuration is not evidence of a device run; see
 * `docs/specs/P1_ANDROIDTEST_RUNBOOK.md`.
 */
@RunWith(AndroidJUnit4::class)
class PlayPermissionPolicyInstrumentedTest {

    @Test
    fun keyRead_defaultsToAskForProviderPreset() = runBlocking {
        val decision = InMemoryPolicyStore().evaluateFresh("key.read", PROVIDER_PRESET)
        assertEquals(Verdict.ASK, decision.verdict)
        assertEquals(PolicyRule("key.read:*", Verdict.ASK, 10), decision.matchedRule)
    }

    @Test
    fun askVerdict_testHostedDialogFixtureCanBeDismissed() {
        val decision = runBlocking {
            InMemoryPolicyStore().evaluateFresh("key.read", PROVIDER_PRESET)
        }
        assertEquals(Verdict.ASK, decision.verdict)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // This AlertDialog is created by the test, not by product UI.
                AlertDialog.Builder(activity)
                    .setTitle("讀取 API Key？")
                    .setMessage("將讀取該 Provider 的 API Key 以發送本次請求。")
                    .setPositiveButton("允許", null)
                    .setNegativeButton("拒絕", null)
                    .create()
                    .show()
            }
            onView(allOf(withText("允許"))).check(matches(isDisplayed()))
            onView(allOf(withText("拒絕"))).perform(click())
            onView(allOf(withText("允許"))).check(doesNotExist())
        }
    }

    @Test
    fun deniedProviderCall_sendsZeroRequests() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(modelsResponse())
            val store = InMemoryPolicyStore()
            store.setRule(PolicyRule("provider.call:$PROVIDER_PRESET", Verdict.DENY, 20))

            val result = listModelsThroughTestOnlyPolicyGate(
                policyStore = store,
                resource = PROVIDER_PRESET,
                provider = testProvider(server),
            )

            assertEquals(Verdict.DENY, result.decision.verdict)
            assertEquals(
                PolicyRule("provider.call:$PROVIDER_PRESET", Verdict.DENY, 20),
                result.decision.matchedRule,
            )
            assertNull(result.modelIds)
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun askProviderCall_sendsZeroRequests() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(modelsResponse())
            val store = InMemoryPolicyStore()
            store.setRule(PolicyRule("provider.call:$PROVIDER_PRESET", Verdict.ASK, 20))

            val result = listModelsThroughTestOnlyPolicyGate(
                policyStore = store,
                resource = PROVIDER_PRESET,
                provider = testProvider(server),
            )

            assertEquals(Verdict.ASK, result.decision.verdict)
            assertEquals(
                PolicyRule("provider.call:$PROVIDER_PRESET", Verdict.ASK, 20),
                result.decision.matchedRule,
            )
            assertNull(result.modelIds)
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun allowedProviderCall_requestsAndParsesModelsEndpoint() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(modelsResponse())
            val result = listModelsThroughTestOnlyPolicyGate(
                policyStore = InMemoryPolicyStore(),
                resource = PROVIDER_PRESET,
                provider = testProvider(server),
            )

            assertEquals(Verdict.ALLOW, result.decision.verdict)
            assertEquals(
                PolicyRule("provider.call:*", Verdict.ALLOW, 10),
                result.decision.matchedRule,
            )
            assertEquals(listOf("gpt-4o-mini", "gpt-4.1-mini"), result.modelIds)
            assertEquals(1, server.requestCount)

            val request = server.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull("expected one provider request within 2 seconds", request)
            val receivedRequest = request ?: throw AssertionError(
                "requestCount was 1 but MockWebServer returned no request within 2 seconds",
            )
            assertEquals("GET", receivedRequest.method)
            assertEquals("/v1/models", receivedRequest.path)
            assertEquals("Bearer local-test-key", receivedRequest.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun staleAllowButFreshDeny_sendsZeroRequests() = runBlocking {
        // Regression guard: the test-only gate must use evaluateFresh().
        // This fake diverges (stale ALLOW vs fresh DENY); if the helper is
        // ever changed to evaluate(), this test turns red (ALLOW + 1 request).
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(modelsResponse())
            val store = DivergentVerdictPolicyStore(
                staleDecision = PolicyDecision(
                    Verdict.ALLOW,
                    PolicyRule("provider.call:*", Verdict.ALLOW, 10),
                    0L,
                ),
                freshDecision = PolicyDecision(
                    Verdict.DENY,
                    PolicyRule("provider.call:$PROVIDER_PRESET", Verdict.DENY, 20),
                    0L,
                ),
            )

            val result = listModelsThroughTestOnlyPolicyGate(
                policyStore = store,
                resource = PROVIDER_PRESET,
                provider = testProvider(server),
            )

            assertEquals(Verdict.DENY, result.decision.verdict)
            assertEquals(
                PolicyRule("provider.call:$PROVIDER_PRESET", Verdict.DENY, 20),
                result.decision.matchedRule,
            )
            assertNull(result.modelIds)
            assertEquals(0, server.requestCount)
            assertEquals(0, store.evaluateCalls)
            assertEquals(1, store.evaluateFreshCalls)
            assertEquals("provider.call", store.lastFreshAction)
            assertEquals(PROVIDER_PRESET, store.lastFreshResource)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun allowThenDeny_picksUpRuleChangeOnSecondCall() = runBlocking {
        // Freshness: the gate re-evaluates per call, so a DENY set after an
        // ALLOW takes effect on the next call (no cached ALLOW decision).
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(modelsResponse())
            val store = InMemoryPolicyStore()

            val first = listModelsThroughTestOnlyPolicyGate(
                policyStore = store,
                resource = PROVIDER_PRESET,
                provider = testProvider(server),
            )
            assertEquals(Verdict.ALLOW, first.decision.verdict)
            assertNotNull(first.modelIds)
            assertEquals(1, server.requestCount)

            store.setRule(PolicyRule("provider.call:$PROVIDER_PRESET", Verdict.DENY, 20))

            val second = listModelsThroughTestOnlyPolicyGate(
                policyStore = store,
                resource = PROVIDER_PRESET,
                provider = testProvider(server),
            )
            assertEquals(Verdict.DENY, second.decision.verdict)
            assertNull(second.modelIds)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun policyEvaluationFailure_sendsZeroRequests() = runBlocking {
        // Fail closed: a throwing fresh evaluation must propagate without
        // running the provider. Stale reads ALLOW here, so using evaluate()
        // instead would send one request and miss the exception (red).
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(modelsResponse())
            val store = ThrowingFreshPolicyStore()

            try {
                listModelsThroughTestOnlyPolicyGate(
                    policyStore = store,
                    resource = PROVIDER_PRESET,
                    provider = testProvider(server),
                )
                fail("expected policy evaluation failure to propagate")
            } catch (expected: IllegalStateException) {
                assertEquals("fresh read failed", expected.message)
            }
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sessionExport_defaultsToAsk() = runBlocking {
        ApplicationProvider.getApplicationContext<android.content.Context>()
        val decision = InMemoryPolicyStore().evaluateFresh("session.export", "abc123")
        assertEquals(Verdict.ASK, decision.verdict)
        assertEquals(PolicyRule("session.export:**", Verdict.ASK, 10), decision.matchedRule)
    }

    private fun testProvider(server: MockWebServer) = ChatCompletionsProvider(
        config = ProviderConfig(
            id = PROVIDER_PRESET,
            label = "local-test-provider",
            baseUrl = server.url("/v1").toString().removeSuffix("/"),
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            apiKeyRef = "provider_key/$PROVIDER_PRESET",
        ),
        apiKey = { "local-test-key".toCharArray() },
    )

    private fun modelsResponse() = MockResponse()
        .setBody("""{"data":[{"id":"gpt-4o-mini"},{"id":"gpt-4.1-mini"}]}""")

    /**
     * Regression-guard fake: stale [evaluate] and [evaluateFresh] diverge, so
     * a gate reading stale policy instead of fresh policy turns tests red.
     */
    private class DivergentVerdictPolicyStore(
        private val staleDecision: PolicyDecision,
        private val freshDecision: PolicyDecision,
    ) : PolicyStore {
        var evaluateCalls = 0
            private set
        var evaluateFreshCalls = 0
            private set
        var lastFreshAction: String? = null
            private set
        var lastFreshResource: String? = null
            private set

        override fun evaluate(action: String, resource: String): PolicyDecision {
            evaluateCalls++
            return staleDecision
        }

        override suspend fun setRule(rule: PolicyRule) = Unit

        override suspend fun removeRule(pattern: String) = Unit

        override suspend fun listRules(): List<PolicyRule> = emptyList()

        override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision {
            evaluateFreshCalls++
            lastFreshAction = action
            lastFreshResource = resource
            return freshDecision
        }
    }

    /**
     * Fail-closed fake: fresh evaluation always throws while stale reads
     * ALLOW, so only the fresh path propagates the failure with zero requests.
     */
    private class ThrowingFreshPolicyStore : PolicyStore {
        override fun evaluate(action: String, resource: String): PolicyDecision =
            PolicyDecision(
                Verdict.ALLOW,
                PolicyRule("provider.call:*", Verdict.ALLOW, 10),
                0L,
            )

        override suspend fun setRule(rule: PolicyRule) = Unit

        override suspend fun removeRule(pattern: String) = Unit

        override suspend fun listRules(): List<PolicyRule> = emptyList()

        override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision =
            throw IllegalStateException("fresh read failed")
    }

    private companion object {
        const val PROVIDER_PRESET = "preset:openai"
    }
}
