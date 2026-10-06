package dev.librepocket.policy

/** Three-state verdict: allow, ask-every-time, or deny. */
enum class Verdict { ALLOW, ASK, DENY }

/**
 * One ruleset entry. `pattern` is `action:resource` in the wildcard DSL:
 * action is exact or star; resource supports star (one segment) and
 * doublestar (cross-segment), e.g. `provider.call:openai/x`,
 * `session.export:doublestar`.
 */
data class PolicyRule(
  val pattern: String,
  val verdict: Verdict,
  val priority: Int = 0,
)

/** Result of one evaluation, carrying the matched rule for audit. */
data class PolicyDecision(
  val verdict: Verdict,
  val matchedRule: PolicyRule?,
  val recheckedAt: Long,
  /** True when several top-priority rules competed and DENY>ASK>ALLOW won. */
  val conflictResolved: Boolean = false,
)

interface PolicyStore {
  /**
   * Evaluate against the in-memory snapshot. Pure, no IO.
   * UI pre-display only — never an execution basis.
   */
  fun evaluate(action: String, resource: String): PolicyDecision

  suspend fun setRule(rule: PolicyRule)
  suspend fun removeRule(pattern: String)
  suspend fun listRules(): List<PolicyRule>

  /**
   * Reload the persisted ruleset and then evaluate. This is the ONLY
   * execution basis (TOCTOU guard); any controlled action must call this
   * immediately before executing.
   */
  suspend fun evaluateFresh(action: String, resource: String): PolicyDecision
}

/** P1 closed action set. Unknown query actions fail closed to DENY. */
internal val KNOWN_ACTIONS = setOf(
  "chat.send",
  "provider.call",
  "session.export",
  "key.read",
  "key.write",
)

/** Split + validate `action:resource`. Blank/empty halves are illegal. */
internal fun parseRulePattern(pattern: String): Pair<String, String> {
  val idx = pattern.indexOf(':')
  require(idx > 0) { "invalid pattern (want 'action:resource'): $pattern" }
  val action = pattern.substring(0, idx).trim()
  val resource = pattern.substring(idx + 1).trim()
  require(action.isNotEmpty() && resource.isNotEmpty()) {
    "invalid pattern (empty action/resource): $pattern"
  }
  return action to resource
}

internal fun actionMatches(ruleAction: String, action: String): Boolean =
  ruleAction == "*" || ruleAction == action

/**
 * Resource glob: `*` = any chars within one segment, `**` = anything
 * (cross-segment). Matching is case-sensitive over `/`-separated segments.
 */
internal fun resourceMatches(glob: String, value: String): Boolean {
  if (glob == "**") return true
  val regex = buildString {
    append('^')
    glob.split('/').forEachIndexed { i, seg ->
      if (i > 0) append('/')
      if (seg == "**") {
        append(".*")
      } else {
        for (c in seg) {
          if (c == '*') append("[^/]*") else append(Regex.escape(c.toString()))
        }
      }
    }
    append('$')
  }
  return Regex(regex).matches(value)
}

private fun strongestFirst(a: Verdict, b: Verdict): Int {
  fun rank(v: Verdict) = when (v) {
    Verdict.DENY -> 2
    Verdict.ASK -> 1
    Verdict.ALLOW -> 0
  }
  return rank(b).compareTo(rank(a))
}

internal fun evaluateSnapshot(
  action: String,
  resource: String,
  rules: List<PolicyRule>,
  nowMs: Long,
): PolicyDecision {
  if (action !in KNOWN_ACTIONS) return PolicyDecision(Verdict.DENY, null, nowMs)
  val matching = rules.filter { rule ->
    val (ruleAction, ruleResource) = parseRulePattern(rule.pattern)
    actionMatches(ruleAction, action) && resourceMatches(ruleResource, resource)
  }
  if (matching.isEmpty()) return PolicyDecision(Verdict.DENY, null, nowMs)
  val topPriority = matching.maxOf { it.priority }
  val winners = matching.filter { it.priority == topPriority }
    .sortedWith { a, b -> strongestFirst(a.verdict, b.verdict) }
  val winner = winners.first()
  val conflict = winners.size > 1 && winners.map { it.verdict }.toSet().size > 1
  return PolicyDecision(winner.verdict, winner, nowMs, conflict)
}
