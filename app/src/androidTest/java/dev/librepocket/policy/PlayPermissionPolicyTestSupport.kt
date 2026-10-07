package dev.librepocket.policy

import dev.librepocket.provider.LlmProvider

/** Result from the test harness gate, not a production authorization API. */
internal data class TestOnlyProviderCallResult(
    val decision: PolicyDecision,
    val modelIds: List<String>?,
)

/**
 * Test-only harness that evaluates fresh policy and calls the real provider
 * transport only for ALLOW. It is deliberately not production consent
 * enforcement or product ASK UI; it exists to test policy-to-HTTP behavior
 * without changing production code.
 */
internal suspend fun listModelsThroughTestOnlyPolicyGate(
    policyStore: PolicyStore,
    resource: String,
    provider: LlmProvider,
): TestOnlyProviderCallResult {
    val decision = policyStore.evaluateFresh("provider.call", resource)
    val modelIds = if (decision.verdict == Verdict.ALLOW) provider.listModels() else null
    return TestOnlyProviderCallResult(decision, modelIds)
}
