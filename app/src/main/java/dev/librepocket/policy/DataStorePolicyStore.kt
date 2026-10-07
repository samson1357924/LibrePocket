package dev.librepocket.policy

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
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
 * - Only a missing rules key is seeded from [DefaultPolicyRuleset]. An explicit
 *   empty ruleset stays empty; unreadable or malformed data never uses defaults
 *   or a previously permissive snapshot. Decode is all-or-nothing.
 * - Mutations decode the current rules inside the DataStore transaction and
 *   publish a snapshot only after persistence succeeds. Cancellation propagates.
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
    mutateRules { rules -> rules.filterNot { it.pattern == rule.pattern } + rule }
  }

  override suspend fun removeRule(pattern: String) {
    mutateRules { rules -> rules.filterNot { it.pattern == pattern } }
  }

  private suspend fun mutateRules(transform: (List<PolicyRule>) -> List<PolicyRule>) {
    withContext(ioDispatcher) {
      mutex.withLock {
        refreshSnapshotLocked {
          // Each wrapper has its own mutex. Only the DataStore transaction
          // serializes read/decode/mutate/encode across all wrappers.
          val persisted = dataStore.edit { prefs ->
            val current = prefs[RULES_KEY]?.let(::decodeRules) ?: DefaultPolicyRuleset.rules
            prefs[RULES_KEY] = encodeRules(transform(current))
          }
          decodeRules(checkNotNull(persisted[RULES_KEY]))
        }
      }
    }
  }

  override suspend fun listRules(): List<PolicyRule> =
    withContext(ioDispatcher) {
      mutex.withLock { refreshSnapshotLocked { readPersistedOrSeedLocked() }.toList() }
    }

  override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision {
    val fresh = try {
      withContext(ioDispatcher) {
        mutex.withLock { refreshSnapshotLocked { readPersistedOrSeedLocked() }.toList() }
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      // No rule can authorize execution when this fresh check failed. The
      // snapshot is also invalidated by refreshSnapshotLocked, never reused.
      emptyList()
    }
    return evaluateSnapshot(action.trim(), resource.trim(), fresh, System.currentTimeMillis())
  }

  /** Caller holds the wrapper mutex; never publish an uncommitted candidate. */
  private suspend fun refreshSnapshotLocked(read: suspend () -> List<PolicyRule>): List<PolicyRule> {
    try {
      val persisted = read().toList()
      snapshot = persisted
      return persisted
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      snapshot = emptyList()
      throw failure
    }
  }

  private suspend fun readPersistedOrSeedLocked(): List<PolicyRule> {
    val raw = dataStore.data.map { prefs -> prefs[RULES_KEY] }.first()
    if (raw != null) return decodeRules(raw)

    // Missing means first boot, not corrupt/unavailable. Recheck inside edit:
    // a different wrapper may have written restrictions since the read above.
    val persisted = dataStore.edit { prefs ->
      if (prefs[RULES_KEY] == null) {
        prefs[RULES_KEY] = encodeRules(DefaultPolicyRuleset.rules)
      } else {
        decodeRules(checkNotNull(prefs[RULES_KEY]))
      }
    }
    return decodeRules(checkNotNull(persisted[RULES_KEY]))
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

    /** Reject the entire ruleset if any row is invalid, including a lost DENY. */
    internal fun decodeRules(raw: String): List<PolicyRule> {
      // encodeRules(emptyList()) is exactly "". Whitespace/blank rows are not
      // emitted by this format and must not silently erase a damaged rule.
      if (raw.isEmpty()) return emptyList()
      val decoder = java.util.Base64.getUrlDecoder()
      val utf8 = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
      return raw.lineSequence().map { line ->
        val parts = line.split('|')
        require(parts.size == 3) { "Invalid persisted policy row" }
        val pattern = try {
          utf8.decode(ByteBuffer.wrap(decoder.decode(parts[0]))).toString()
        } catch (failure: CharacterCodingException) {
          throw IllegalArgumentException("Invalid policy pattern encoding", failure)
        }
        val verdict = Verdict.valueOf(parts[1])
        val priority = requireNotNull(parts[2].toIntOrNull()) { "Invalid policy priority" }
        parseRulePattern(pattern)
        PolicyRule(pattern, verdict, priority)
      }.toList()
    }
  }
}
