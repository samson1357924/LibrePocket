package dev.librepocket.policy

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DataStorePolicyStore] tests (SPEC §1.7 / §9.3).
 *
 * - Empty store seeds the default ruleset on first [PolicyStore.evaluateFresh].
 * - `evaluateFresh` re-reads the persisted snapshot (TOCTOU guard): an
 *   out-of-band DataStore edit is visible to `evaluateFresh` immediately.
 * - Rules survive across store instances (real persistence, temp file).
 * - Malformed persisted data rejects the whole ruleset, never retaining ALLOW.
 */
class DataStorePolicyStoreTest {

    private val tmpDirs = ArrayList<java.io.File>()
    private val scopes = ArrayList<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        tmpDirs.forEach { it.deleteRecursively() }
    }

    private fun newStore(initial: List<PolicyRule> = emptyList()): DataStorePolicyStore {
        val dir = Files.createTempDirectory("policy-test").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "policy.preferences_pb") },
        )
        return DataStorePolicyStore(dataStore, initial)
    }

    @Test
    fun emptyStoreSeedsDefaultsOnFreshEvaluate() = runBlocking {
        val store = newStore()
        val decision = store.evaluateFresh("key.read", "openai")
        assertEquals(Verdict.ASK, decision.verdict)
        assertTrue(decision.recheckedAt > 0)
        assertEquals(DefaultPolicyRuleset.rules.toSet(), store.listRules().toSet())
    }

    @Test
    fun evaluateFreshReReadsPersistedSnapshot() = runBlocking {
        val store = newStore()
        // Warm the snapshot (seeds defaults).
        assertEquals(Verdict.ALLOW, store.evaluateFresh("chat.send", "x").verdict)

        // Out-of-band edit, simulating a concurrent writer / settings change.
        store.dataStore.edit { prefs ->
            prefs[RULES_KEY] = DataStorePolicyStore.encodeRules(
                listOf(PolicyRule("chat.send:*", Verdict.DENY, 10)),
            )
        }

        val fresh = store.evaluateFresh("chat.send", "x")
        assertEquals(Verdict.DENY, fresh.verdict)
        assertTrue(fresh.recheckedAt > 0)
    }

    @Test
    fun setRulePersistsAcrossInstances() = runBlocking {
        val dir = Files.createTempDirectory("policy-persist").toFile()
        tmpDirs.add(dir)
        val openedScopes = ArrayList<CoroutineScope>()
        fun open(): DataStorePolicyStore {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            scopes.add(scope)
            openedScopes.add(scope)
            val dataStore = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "policy.preferences_pb") },
            )
            return DataStorePolicyStore(dataStore)
        }

        val first = open()
        first.evaluateFresh("key.read", "x") // seed
        first.setRule(PolicyRule("key.read:*", Verdict.DENY, 10))
        // 耐久屏障：等 DataStore 把本次寫入落盤後再模擬重啟。
        // 舊碼在 setRule 後立刻 cancel scope，會競掉後台 IO（CI 單發 flake
        // `DataStorePolicyStoreTest.setRulePersistsAcrossInstances` 即此）。
        first.dataStore.data.first()

        // DataStore is single-instance-per-file per process: retire the first
        // instance's scope before opening the second (mirrors process restart).
        openedScopes[0].cancel()

        val second = open()
        // A fresh instance serves its in-memory snapshot until the first
        // persisted re-read; after evaluateFresh both paths agree on DENY.
        assertEquals(Verdict.DENY, second.evaluateFresh("key.read", "x").verdict)
        assertEquals(Verdict.DENY, second.evaluate("key.read", "x").verdict)
    }

    @Test
    fun malformedLinesRejectWholeRuleset() {
        val raw = DataStorePolicyStore.encodeRules(
            listOf(PolicyRule("chat.send:*", Verdict.ALLOW, 10)),
        ) + "\nnot-a-rule\n|||also-bad"
        assertThrows(IllegalArgumentException::class.java) {
            DataStorePolicyStore.decodeRules(raw)
        }
    }
}
