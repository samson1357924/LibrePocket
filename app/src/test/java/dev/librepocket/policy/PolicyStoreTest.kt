package dev.librepocket.policy

import dev.librepocket.router.SafeToolSlice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PolicyStoreTest {

  private fun store(vararg rules: PolicyRule) = runBlocking {
    val s = InMemoryPolicyStore(emptyList())
    rules.forEach { s.setRule(it) }
    s
  }

  @Test fun defaultRulesetSnapshot() = runBlocking {
    val rules = InMemoryPolicyStore().listRules()
    assertEquals(
      setOf(
        PolicyRule("*:*", Verdict.ASK, 0),
        PolicyRule("key.read:*", Verdict.ASK, 10),
        PolicyRule("key.write:*", Verdict.ASK, 10),
        PolicyRule("session.export:**", Verdict.ASK, 10),
        PolicyRule("chat.send:*", Verdict.ALLOW, 10),
        PolicyRule("provider.call:*", Verdict.ALLOW, 10),
      ),
      rules.toSet(),
    )
    assertEquals(6, rules.size)
  }

  @Test fun defaultChatAndProviderAllow() {
    val s = InMemoryPolicyStore()
    assertEquals(Verdict.ALLOW, s.evaluate("chat.send", "chat").verdict)
    assertEquals(Verdict.ALLOW, s.evaluate("provider.call", "gpt-4o").verdict)
    // '*' is single-segment: a two-segment resource matches nothing → DENY.
    assertEquals(Verdict.DENY, s.evaluate("provider.call", "openai/gpt-4o").verdict)
  }

  @Test fun defaultSensitiveAsk() {
    val s = InMemoryPolicyStore()
    assertEquals(Verdict.ASK, s.evaluate("key.read", "provider-123").verdict)
    assertEquals(Verdict.ASK, s.evaluate("key.write", "provider-123").verdict)
    assertEquals(Verdict.ASK, s.evaluate("session.export", "session/abc").verdict)
  }

  @Test fun unknownActionIsDenyFailClosed() {
    val s = InMemoryPolicyStore()
    // `tool.exec` is now a KNOWN slice action (see SafeToolSlice); probe with
    // a still-unknown action so the closed-set pin keeps its meaning.
    val d = s.evaluate("tool.delete", "anything")
    assertEquals(Verdict.DENY, d.verdict)
    assertNull(d.matchedRule)
    // Even an explicit rule for an unknown action never authorizes it.
    val s2 = store(PolicyRule("tool.delete:*", Verdict.ALLOW, 99))
    assertEquals(Verdict.DENY, s2.evaluate("tool.delete", "x").verdict)
  }

  @Test fun toolExecIsKnownButDefaultsToAsk() {
    // Stage2 slice basis: known (explicit rules apply), but the default
    // ruleset has no slice rule — only `*:*` ASK matches, so the slice
    // (which requires ALLOW) denies fail-closed until explicitly allowed.
    val s = InMemoryPolicyStore()
    val d = s.evaluate(SafeToolSlice.POLICY_ACTION, SafeToolSlice.TOOL_NAME)
    assertEquals(Verdict.ASK, d.verdict)
  }

  @Test fun toolExecDefaultAskHoldsOnFreshRecheck() = runBlocking {
    // Pin the same ASK default on the execution-basis path the slice uses.
    val s = InMemoryPolicyStore()
    assertEquals(Verdict.ASK, s.evaluateFresh(SafeToolSlice.POLICY_ACTION, SafeToolSlice.TOOL_NAME).verdict)
  }

  @Test fun toolExecExplicitAllowAuthorizes() = runBlocking {
    val s = store(PolicyRule("${SafeToolSlice.POLICY_ACTION}:${SafeToolSlice.TOOL_NAME}", Verdict.ALLOW, 10))
    assertEquals(Verdict.ALLOW, s.evaluateFresh(SafeToolSlice.POLICY_ACTION, SafeToolSlice.TOOL_NAME).verdict)
    // Other tools stay denied without their own rule.
    assertEquals(Verdict.DENY, s.evaluateFresh(SafeToolSlice.POLICY_ACTION, "shell.exec").verdict)
  }

  @Test fun noMatchIsDeny() {
    val s = InMemoryPolicyStore(emptyList())
    val d = s.evaluate("chat.send", "chat")
    assertEquals(Verdict.DENY, d.verdict)
    assertNull(d.matchedRule)
  }

  @Test fun higherPriorityWins() = runBlocking {
    val s = store(
      PolicyRule("chat.send:*", Verdict.ALLOW, 10),
      PolicyRule("chat.send:*", Verdict.DENY, 20),
    )
    val d = s.evaluateFresh("chat.send", "chat")
    assertEquals(Verdict.DENY, d.verdict)
    assertEquals(20, d.matchedRule?.priority)
  }

  @Test fun samePriorityDenyBeatsAskBeatsAllow() {
    // Distinct patterns (pattern is the rule key) that all match one query.
    val s = store(
      PolicyRule("key.read:*", Verdict.ALLOW, 10),
      PolicyRule("key.read:k1", Verdict.ASK, 10),
      PolicyRule("*:k1", Verdict.DENY, 10),
    )
    val d = s.evaluate("key.read", "k1")
    assertEquals(Verdict.DENY, d.verdict)
    assertTrue(d.conflictResolved)
  }

  @Test fun noConflictFlagWhenSingleWinner() {
    val s = store(PolicyRule("key.read:*", Verdict.ASK, 10))
    val d = s.evaluate("key.read", "k1")
    assertEquals(Verdict.ASK, d.verdict)
    assertFalse(d.conflictResolved)
  }

  @Test fun wildcardSingleSegmentVsCrossSegment() {
    val s = store(
      PolicyRule("session.export:a/*", Verdict.ALLOW, 10),
      PolicyRule("session.export:b/**", Verdict.ALLOW, 10),
    )
    assertEquals(Verdict.ALLOW, s.evaluate("session.export", "a/one").verdict)
    // '*' does not cross segments → falls through (no rule) → DENY.
    assertEquals(Verdict.DENY, s.evaluate("session.export", "a/one/two").verdict)
    assertEquals(Verdict.ALLOW, s.evaluate("session.export", "b/one").verdict)
    assertEquals(Verdict.ALLOW, s.evaluate("session.export", "b/one/two").verdict)
    assertEquals(Verdict.DENY, s.evaluate("session.export", "c/one").verdict)
  }

  @Test fun starActionMatchesAnyKnownAction() {
    val s = store(PolicyRule("*:shared-doc", Verdict.ASK, 5))
    assertEquals(Verdict.ASK, s.evaluate("key.read", "shared-doc").verdict)
    assertEquals(Verdict.ASK, s.evaluate("chat.send", "shared-doc").verdict)
    assertEquals(Verdict.DENY, s.evaluate("key.read", "other").verdict)
  }

  @Test fun invalidPatternsThrow() = runBlocking {
    val s = InMemoryPolicyStore(emptyList())
    listOf("", "   ", "no-colon", ":res", "act:", " : ", ":").forEach { bad ->
      try {
        s.setRule(PolicyRule(bad, Verdict.ALLOW, 0))
        fail("expected IllegalArgumentException for pattern <$bad>")
      } catch (_: IllegalArgumentException) {
        // expected
      }
    }
  }

  @Test fun evaluateFreshSeesRuleUpdates() = runBlocking {
    val s = store(PolicyRule("chat.send:*", Verdict.ALLOW, 10))
    assertEquals(Verdict.ALLOW, s.evaluate("chat.send", "chat").verdict)
    s.setRule(PolicyRule("chat.send:*", Verdict.DENY, 20))
    assertEquals(Verdict.DENY, s.evaluateFresh("chat.send", "chat").verdict)
    s.removeRule("chat.send:*")
    // Only the DENY is removed by exact pattern; the ALLOW (same pattern) was
    // already replaced by setRule, so nothing matches now → DENY fallback.
    assertEquals(Verdict.DENY, s.evaluateFresh("chat.send", "chat").verdict)
  }

  @Test fun evaluateIsDeterministicPureRead() {
    val s = InMemoryPolicyStore()
    val first = s.evaluate("key.read", "k")
    val second = s.evaluate("key.read", "k")
    assertEquals(first.verdict, second.verdict)
    assertEquals(first.matchedRule, second.matchedRule)
  }

  @Test fun matchingIsCaseSensitive() {
    val s = InMemoryPolicyStore()
    assertEquals(Verdict.DENY, s.evaluate("CHAT.SEND", "chat").verdict)
  }

  @Test fun recheckedAtIsFresh() {
    val s = InMemoryPolicyStore()
    val before = System.currentTimeMillis()
    val d = s.evaluate("chat.send", "chat")
    assertTrue(d.recheckedAt >= before)
  }
}
