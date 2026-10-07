package dev.librepocket.policy

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Deterministic transaction/failure controls; real-file persistence is tested separately. */
class DataStorePolicyStoreRegressionTest {
    private val allow = PolicyRule("provider.call:*", Verdict.ALLOW, 10)
    private val deny = PolicyRule("provider.call:target", Verdict.DENY, 20)
    private val unrelated = PolicyRule("session.export:**", Verdict.ASK, 10)

    private class ControlledDataStore(raw: String? = null) : DataStore<Preferences> {
        private val mutex = Mutex()
        @Volatile
        var persisted: Preferences = emptyPreferences().toMutablePreferences().apply {
            if (raw != null) this[RULES_KEY] = raw
        }
            private set
        @Volatile
        var readFailure: Exception? = null
        @Volatile
        var writeFailure: Exception? = null
        @Volatile
        var beforeUpdate: (suspend () -> Unit)? = null
        @Volatile
        var updateCount = 0
            private set

        override val data: Flow<Preferences> = flow {
            readFailure?.let { throw it }
            emit(persisted)
        }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            beforeUpdate?.invoke()
            return mutex.withLock {
                readFailure?.let { throw it }
                val updated = transform(persisted)
                writeFailure?.let { throw it }
                persisted = updated
                updateCount++
                persisted
            }
        }

        fun rules(): List<PolicyRule> = DataStorePolicyStore.decodeRules(persisted[RULES_KEY]!!)
    }

    private fun data(vararg rules: PolicyRule) =
        ControlledDataStore(DataStorePolicyStore.encodeRules(rules.toList()))

    private suspend fun expectFailure(expected: Exception, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected ${expected.javaClass.simpleName}")
        } catch (actual: Exception) {
            assertEquals(expected.javaClass, actual.javaClass)
            assertEquals(expected.message, actual.message)
            // Coroutine stack-trace recovery may copy an exception and retain
            // the original as its cause instead of preserving identity.
            assertTrue("Original failure must propagate", actual === expected || actual.cause === expected)
        }
    }

    @Test
    fun freshReadIOExceptionDeniesDefaultAndPreviouslyAllowedSnapshots() = runBlocking {
        val backing = data(allow)
        val cold = DataStorePolicyStore(backing)
        val warm = DataStorePolicyStore(backing)
        assertEquals(Verdict.ALLOW, warm.evaluateFresh("provider.call", "target").verdict)
        backing.readFailure = IOException("test read unavailable")

        for (store in listOf(cold, warm)) {
            for (action in listOf("provider.call", "chat.send", "key.read", "key.write")) {
                val decision = store.evaluateFresh(action, "target")
                assertEquals(Verdict.DENY, decision.verdict)
                assertNull(decision.matchedRule)
                assertTrue(decision.recheckedAt > 0)
                assertEquals(Verdict.DENY, store.evaluate(action, "target").verdict)
            }
        }
        assertEquals(0, backing.updateCount)
        assertEquals(listOf(allow), backing.rules())

        backing.readFailure = null
        assertEquals(Verdict.ALLOW, warm.evaluateFresh("provider.call", "target").verdict)
    }

    @Test
    fun malformedHighPriorityDenyRejectsAllRowsWithoutRewritingData() = runBlocking {
        val encodedPattern = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(deny.pattern.toByteArray(Charsets.UTF_8))
        val malformedRows = listOf(
            "$encodedPattern|BROKEN|20", // unknown verdict
            "$encodedPattern|DENY|bad", // unknown priority
            "$encodedPattern|DENY|2147483648", // overflowing priority
            "$encodedPattern|DENY", // truncated row
            "%%%|DENY|20", // invalid base64
            "${Base64.getUrlEncoder().encodeToString("no-colon".toByteArray())}|DENY|20",
            "_w|DENY|20", // invalid UTF-8; must not become a replacement-character pattern
            "", // truncated to a blank row, not an intentionally empty ruleset
        )
        for (malformed in malformedRows) {
            val raw = DataStorePolicyStore.encodeRules(listOf(allow)) + "\n" + malformed
            val backing = ControlledDataStore(raw)
            val store = DataStorePolicyStore(backing)
            val decision = store.evaluateFresh("provider.call", "target")
            assertEquals("Malformed row <$malformed>", Verdict.DENY, decision.verdict)
            assertNull(decision.matchedRule)
            assertEquals(Verdict.DENY, store.evaluate("provider.call", "target").verdict)
            assertEquals(raw, backing.persisted[RULES_KEY])
            assertEquals(0, backing.updateCount)
        }
    }

    @Test
    fun validEncodingRoundTripsUnicodeDelimitersAndPriorityLimits() {
        val rules = listOf(
            PolicyRule("provider.call:端點|名稱", Verdict.DENY, Int.MAX_VALUE),
            PolicyRule("session.export:folder/**", Verdict.ASK, Int.MIN_VALUE),
            allow,
        )
        assertEquals(rules, DataStorePolicyStore.decodeRules(DataStorePolicyStore.encodeRules(rules)))
    }

    @Test
    fun validPriorityDenyAndAllowPositiveControls() = runBlocking {
        val store = DataStorePolicyStore(data(allow, deny))
        val denied = store.evaluateFresh("provider.call", "target")
        assertEquals(Verdict.DENY, denied.verdict)
        assertEquals(deny, denied.matchedRule)
        assertEquals(Verdict.ALLOW, store.evaluateFresh("provider.call", "other").verdict)
        assertEquals(listOf(allow, deny), store.listRules())
    }

    @Test
    fun firstBootSeedsDefaultsButPersistedEmptyRulesStayEmpty() = runBlocking {
        val missing = ControlledDataStore()
        val firstBoot = DataStorePolicyStore(missing, emptyList())
        assertEquals(Verdict.ALLOW, firstBoot.evaluateFresh("provider.call", "target").verdict)
        assertEquals(DefaultPolicyRuleset.rules, missing.rules())
        assertEquals(1, missing.updateCount)

        val empty = ControlledDataStore("")
        val persistedEmpty = DataStorePolicyStore(empty)
        assertEquals(Verdict.DENY, persistedEmpty.evaluateFresh("provider.call", "target").verdict)
        assertEquals(emptyList<PolicyRule>(), persistedEmpty.listRules())
        assertEquals(0, empty.updateCount)

        val whitespace = ControlledDataStore(" ")
        assertEquals(Verdict.DENY,
            DataStorePolicyStore(whitespace).evaluateFresh("provider.call", "target").verdict)
        assertEquals(0, whitespace.updateCount)
    }

    @Test
    fun seedFailureDeniesRatherThanReturningUnpersistedDefaults() = runBlocking {
        val backing = ControlledDataStore()
        backing.writeFailure = IOException("test seed failure")
        val store = DataStorePolicyStore(backing)
        assertEquals(Verdict.DENY, store.evaluateFresh("provider.call", "target").verdict)
        assertEquals(Verdict.DENY, store.evaluate("provider.call", "target").verdict)
        assertNull(backing.persisted[RULES_KEY])
        assertEquals(0, backing.updateCount)
        backing.writeFailure = null
        assertEquals(Verdict.ALLOW, store.evaluateFresh("provider.call", "target").verdict)
    }

    @Test
    fun coldSetAndRemoveOperateOnPersistedRulesNotConstructorDefaults() = runBlocking {
        val backing = data(allow, deny)
        val coldSetter = DataStorePolicyStore(backing)
        coldSetter.setRule(unrelated)
        assertEquals(listOf(allow, deny, unrelated), backing.rules())
        assertEquals(Verdict.DENY, coldSetter.evaluate("provider.call", "target").verdict)

        val coldRemover = DataStorePolicyStore(backing)
        coldRemover.removeRule(unrelated.pattern)
        assertEquals(listOf(allow, deny), backing.rules())
        assertEquals(Verdict.DENY, coldRemover.evaluate("provider.call", "target").verdict)
        assertEquals(backing.rules(), coldSetter.listRules())
        coldRemover.removeRule(deny.pattern)
        assertEquals(listOf(allow), backing.rules())
        assertEquals(Verdict.ALLOW, coldRemover.evaluate("provider.call", "target").verdict)
    }

    @Test(timeout = 10_000)
    fun twoHydratedWrappersBarrierWritesPreserveBothChanges() = runBlocking {
        val backing = data(allow)
        val first = DataStorePolicyStore(backing)
        val second = DataStorePolicyStore(backing)
        first.evaluateFresh("provider.call", "target")
        second.evaluateFresh("provider.call", "target")
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var arrivals = 0
        backing.beforeUpdate = {
            synchronized(backing) {
                arrivals++
                if (arrivals == 2) arrived.complete(Unit)
            }
            release.await()
        }
        val revoke = async { first.setRule(deny) }
        val export = async { second.setRule(unrelated) }
        arrived.await() // both wrappers have entered updateData before either transaction commits
        release.complete(Unit)
        revoke.await()
        export.await()
        assertEquals(setOf(allow, deny, unrelated), backing.rules().toSet())
        assertEquals(Verdict.DENY, first.evaluateFresh("provider.call", "target").verdict)
        assertEquals(Verdict.DENY, second.evaluateFresh("provider.call", "target").verdict)
        assertEquals(backing.rules(), second.listRules())
    }

    @Test(timeout = 10_000)
    fun twoHydratedWrappersBarrierRemoveAndSetPreserveBothChanges() = runBlocking {
        val backing = data(allow, deny, unrelated)
        val remover = DataStorePolicyStore(backing)
        val setter = DataStorePolicyStore(backing)
        remover.evaluateFresh("provider.call", "target")
        setter.evaluateFresh("provider.call", "target")
        val replacement = unrelated.copy(verdict = Verdict.DENY)
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var arrivals = 0
        backing.beforeUpdate = {
            synchronized(backing) {
                arrivals++
                if (arrivals == 2) arrived.complete(Unit)
            }
            release.await()
        }
        val removal = async { remover.removeRule(allow.pattern) }
        val update = async { setter.setRule(replacement) }
        arrived.await()
        release.complete(Unit)
        removal.await()
        update.await()
        assertEquals(setOf(deny, replacement), backing.rules().toSet())
        assertEquals(Verdict.DENY, setter.evaluateFresh("provider.call", "other").verdict)
        assertEquals(Verdict.DENY, remover.evaluateFresh("session.export", "folder/item").verdict)
    }

    @Test
    fun listingHydratesPersistedRulesAndPropagatesUnavailability() = runBlocking {
        val backing = data(allow, deny)
        val store = DataStorePolicyStore(backing)
        assertEquals(listOf(allow, deny), store.listRules())
        assertEquals(Verdict.DENY, store.evaluate("provider.call", "target").verdict)
        val failure = IOException("test list read failure")
        backing.readFailure = failure
        expectFailure(failure) { store.listRules() }
    }

    @Test
    fun failedSetAndRemoveDoNotPublishPermissiveSnapshots() = runBlocking {
        for (remove in listOf(false, true)) {
            val backing = data(allow, deny)
            val store = DataStorePolicyStore(backing)
            store.evaluateFresh("provider.call", "target")
            val failure = IOException("test failed commit")
            backing.writeFailure = failure
            expectFailure(failure) {
                if (remove) store.removeRule(deny.pattern)
                else store.setRule(deny.copy(verdict = Verdict.ALLOW))
            }
            assertEquals(listOf(allow, deny), backing.rules())
            assertEquals(0, backing.updateCount)
            assertEquals(Verdict.DENY, store.evaluate("provider.call", "target").verdict)
            backing.writeFailure = null
            assertEquals(listOf(allow, deny), store.listRules())
        }
    }

    @Test
    fun unreadableStoreCannotBeOverwrittenByMutators() = runBlocking {
        val backing = data(allow, deny)
        val store = DataStorePolicyStore(backing)
        store.evaluateFresh("provider.call", "target")
        val failure = IOException("test mutation read failure")
        backing.readFailure = failure
        expectFailure(failure) { store.setRule(unrelated) }
        expectFailure(failure) { store.removeRule(deny.pattern) }
        assertEquals(listOf(allow, deny), backing.rules())
        assertEquals(0, backing.updateCount)
        assertEquals(Verdict.DENY, store.evaluate("provider.call", "target").verdict)
    }

    @Test
    fun corruptStoreCannotBeOverwrittenByMutatorsOrListedAsHealthy() = runBlocking {
        val raw = DataStorePolicyStore.encodeRules(listOf(allow)) + "\nbroken-deny-row"
        val backing = ControlledDataStore(raw)
        val store = DataStorePolicyStore(backing)
        for (operation in listOf<suspend () -> Unit>(
            { store.setRule(unrelated) },
            { store.removeRule(deny.pattern) },
            { store.listRules() },
        )) {
            try {
                operation()
                fail("Corrupt ruleset must be rejected")
            } catch (_: IllegalArgumentException) {
                // expected: no partial success or replacement with defaults
            }
            assertEquals(raw, backing.persisted[RULES_KEY])
            assertEquals(0, backing.updateCount)
            assertEquals(Verdict.DENY, store.evaluate("provider.call", "target").verdict)
        }
    }

    @Test(timeout = 10_000)
    fun missingSeedRechecksInsideTransactionAndDoesNotOverwriteConcurrentRules() = runBlocking {
        val backing = ControlledDataStore()
        val reader = DataStorePolicyStore(backing)
        val writer = DataStorePolicyStore(backing)
        val seedEntered = CompletableDeferred<Unit>()
        val releaseSeed = CompletableDeferred<Unit>()
        backing.beforeUpdate = {
            seedEntered.complete(Unit)
            releaseSeed.await()
        }
        val pendingRead = async { reader.evaluateFresh("provider.call", "target") }
        seedEntered.await()
        backing.beforeUpdate = null
        writer.setRule(deny)
        releaseSeed.complete(Unit)
        assertEquals(Verdict.DENY, pendingRead.await().verdict)
        assertTrue(deny in backing.rules())
    }

    @Test
    fun cancellationsPropagateDuringReadSeedAndMutations() = runBlocking {
        val readFailure = CancellationException("test read cancellation")
        val unreadable = data(allow).apply { this.readFailure = readFailure }
        val reader = DataStorePolicyStore(unreadable)
        expectFailure(readFailure) { reader.evaluateFresh("provider.call", "target") }
        expectFailure(readFailure) { reader.listRules() }

        val seedFailure = CancellationException("test seed cancellation")
        val unseeded = ControlledDataStore().apply { writeFailure = seedFailure }
        expectFailure(seedFailure) {
            DataStorePolicyStore(unseeded).evaluateFresh("provider.call", "target")
        }
        assertNull(unseeded.persisted[RULES_KEY])

        val commitFailure = CancellationException("test write cancellation")
        val backing = data(allow, deny)
        val store = DataStorePolicyStore(backing)
        store.evaluateFresh("provider.call", "target")
        backing.writeFailure = commitFailure
        expectFailure(commitFailure) { store.setRule(deny.copy(verdict = Verdict.ALLOW)) }
        expectFailure(commitFailure) { store.removeRule(deny.pattern) }
        assertEquals(listOf(allow, deny), backing.rules())
        assertEquals(Verdict.DENY, store.evaluate("provider.call", "target").verdict)
    }
}
