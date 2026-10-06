package dev.librepocket.policy

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * First-boot ruleset (P1 conservative default):
 * pure chat + provider calls are allowed, everything sensitive asks,
 * and anything unmatched is denied (fail closed).
 */
object DefaultPolicyRuleset {
  val rules: List<PolicyRule> = listOf(
    PolicyRule("*:*", Verdict.ASK, 0),
    PolicyRule("key.read:*", Verdict.ASK, 10),
    PolicyRule("key.write:*", Verdict.ASK, 10),
    PolicyRule("session.export:**", Verdict.ASK, 10),
    PolicyRule("chat.send:*", Verdict.ALLOW, 10),
    PolicyRule("provider.call:*", Verdict.ALLOW, 10),
  )
}

/**
 * In-memory [PolicyStore] (unit tests + P1 product backing; a DataStore
 * backing can wrap this later without changing matching semantics).
 *
 * [evaluate] reads a volatile snapshot (pure, no IO).
 * [evaluateFresh] re-reads the snapshot under the write mutex first,
 * so a rule change racing the call cannot produce a stale execution basis.
 */
class InMemoryPolicyStore(
  initialRules: List<PolicyRule> = DefaultPolicyRuleset.rules,
) : PolicyStore {
  private val mutex = Mutex()

  @Volatile
  private var snapshot: List<PolicyRule> =
    initialRules.toList().also { rules -> rules.forEach { parseRulePattern(it.pattern) } }

  override fun evaluate(action: String, resource: String): PolicyDecision =
    evaluateSnapshot(action.trim(), resource.trim(), snapshot, System.currentTimeMillis())

  override suspend fun setRule(rule: PolicyRule) {
    parseRulePattern(rule.pattern)
    mutex.withLock {
      snapshot = snapshot.filterNot { it.pattern == rule.pattern } + rule
    }
  }

  override suspend fun removeRule(pattern: String) {
    mutex.withLock {
      snapshot = snapshot.filterNot { it.pattern == pattern }
    }
  }

  override suspend fun listRules(): List<PolicyRule> = mutex.withLock { snapshot.toList() }

  override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision {
    val fresh = mutex.withLock { snapshot.toList() }
    return evaluateSnapshot(action.trim(), resource.trim(), fresh, System.currentTimeMillis())
  }
}
