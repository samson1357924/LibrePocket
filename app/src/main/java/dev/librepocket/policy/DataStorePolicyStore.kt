package dev.librepocket.policy

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** DataStore file holding the persisted P1 permission ruleset. */
val Context.policyDataStore: DataStore<Preferences> by preferencesDataStore(
  name = "librepocket_policy",
)

internal val RULES_KEY = stringPreferencesKey("rules_v1")

/**
 * Product [PolicyStore] (M6, spec §1.7 / §9.3): DataStore backing, reads and
 * writes confined to an IO dispatcher.
 *
 * - [evaluate] is pure: it reads a volatile in-memory snapshot (UI
 *   pre-display only, never an execution basis).
 * - [evaluateFresh] re-reads the DataStore snapshot first (TOCTOU guard) and
 *   stamps [PolicyDecision.recheckedAt]; it is the ONLY execution basis.
 * - An empty DataStore is seeded from [DefaultPolicyRuleset] on first
 *   [evaluateFresh]; malformed persisted lines are skipped (fail closed to
 *   the remaining rules, never to open).
 */
class DataStorePolicyStore(
  internal val dataStore: DataStore<Preferences>,
  initialRules: List<PolicyRule> = DefaultPolicyRuleset.rules,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PolicyStore {

  private val mutex = Mutex()

  @Volatile
  private var snapshot: List<PolicyRule> =
    initialRules.toList().also { rules -> rules.forEach { parseRulePattern(it.pattern) } }

  /** Product convenience: wraps [Context.policyDataStore]. */
  constructor(
    context: Context,
    initialRules: List<PolicyRule> = DefaultPolicyRuleset.rules,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  ) : this(context.applicationContext.policyDataStore, initialRules, ioDispatcher)

  override fun evaluate(action: String, resource: String): PolicyDecision =
    evaluateSnapshot(action.trim(), resource.trim(), snapshot, System.currentTimeMillis())

  override suspend fun setRule(rule: PolicyRule) {
    parseRulePattern(rule.pattern)
    withContext(ioDispatcher) {
      mutex.withLock {
        snapshot = snapshot.filterNot { it.pattern == rule.pattern } + rule
        persistLocked(snapshot)
      }
    }
  }

  override suspend fun removeRule(pattern: String) {
    withContext(ioDispatcher) {
      mutex.withLock {
        snapshot = snapshot.filterNot { it.pattern == pattern }
        persistLocked(snapshot)
      }
    }
  }

  override suspend fun listRules(): List<PolicyRule> =
    withContext(ioDispatcher) {
      mutex.withLock { snapshot.toList() }
    }

  override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision {
    val fresh = withContext(ioDispatcher) {
      mutex.withLock {
        snapshot = readPersistedOrSeedLocked()
        snapshot.toList()
      }
    }
    return evaluateSnapshot(action.trim(), resource.trim(), fresh, System.currentTimeMillis())
  }

  private suspend fun persistLocked(rules: List<PolicyRule>) {
    dataStore.edit { prefs -> prefs[RULES_KEY] = encodeRules(rules) }
  }

  private suspend fun readPersistedOrSeedLocked(): List<PolicyRule> {
    val raw: String? = try {
      dataStore.data.map { prefs -> prefs[RULES_KEY] }.first()
    } catch (_: Exception) {
      // Corrupt/unreadable store: keep the in-memory snapshot (fail closed
      // to whatever was last known good, never to an empty open ruleset).
      return snapshot
    }
    if (raw == null) {
      val seeded = DefaultPolicyRuleset.rules
      try {
        dataStore.edit { prefs -> prefs[RULES_KEY] = encodeRules(seeded) }
      } catch (_: Exception) {
        // Seeding is best-effort; the in-memory snapshot still serves reads.
      }
      return seeded
    }
    val decoded = decodeRules(raw)
    return decoded
  }

  companion object {
    /**
     * One rule per line: `base64(pattern)|VERDICT|priority`.
     * Base64 keeps arbitrary pattern text (`:`, `/`, `*`) unambiguous.
     */
    internal fun encodeRules(rules: List<PolicyRule>): String {
      val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
      return rules.joinToString("\n") { rule ->
        val pattern = encoder.encodeToString(rule.pattern.toByteArray(Charsets.UTF_8))
        "$pattern|${rule.verdict.name}|${rule.priority}"
      }
    }

    internal fun decodeRules(raw: String): List<PolicyRule> {
      if (raw.isBlank()) return emptyList()
      val decoder = java.util.Base64.getUrlDecoder()
      val out = ArrayList<PolicyRule>()
      for (line in raw.lineSequence()) {
        if (line.isBlank()) continue
        val parts = line.split('|')
        if (parts.size != 3) continue
        val pattern = try {
          String(decoder.decode(parts[0]), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
          continue
        }
        val verdict = try {
          Verdict.valueOf(parts[1])
        } catch (_: IllegalArgumentException) {
          continue
        }
        val priority = parts[2].toIntOrNull() ?: continue
        try {
          parseRulePattern(pattern)
        } catch (_: IllegalArgumentException) {
          continue
        }
        out.add(PolicyRule(pattern, verdict, priority))
      }
      return out
    }
  }
}
