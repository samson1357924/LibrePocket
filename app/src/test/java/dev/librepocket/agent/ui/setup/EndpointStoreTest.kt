package dev.librepocket.agent.ui.setup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.KeyVault
import dev.librepocket.provider.ProviderProtocol
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointStoreTest {

    private val tmpDirs = ArrayList<java.io.File>()
    private val scopes = ArrayList<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        tmpDirs.forEach { it.deleteRecursively() }
    }

    private fun newStore(): EndpointStore {
        val dir = Files.createTempDirectory("endpoint-test").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "endpoint.preferences_pb") },
        )
        return EndpointStore(dataStore)
    }

    private fun sampleConfig() = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "gpt-4o-mini",
        apiKeyRef = "provider_key/preset:openai",
    )

    @Test
    fun emptyStoreHasNoMetadata() = runBlocking {
        val store = newStore()
        assertNull(store.observe().first())
        assertFalse(store.hasMetadata())
    }

    @Test
    fun saveObserveRoundTrip() = runBlocking {
        val store = newStore()
        store.save(sampleConfig())
        assertEquals(sampleConfig().copy(configRevision = 1L), store.observe().first())
        assertTrue(store.hasMetadata())
    }

    @Test
    fun saveAndClearAdvancePersistentConfigRevision() = runBlocking {
        val store = newStore()
        store.save(sampleConfig())
        assertEquals(1L, store.observe().first()?.configRevision)
        store.save(sampleConfig().copy(baseUrl = "https://example.test/v1"))
        assertEquals(2L, store.observe().first()?.configRevision)
        store.clear()
        assertNull(store.observe().first())
        store.save(sampleConfig())
        assertEquals(4L, store.observe().first()?.configRevision)
    }

    @Test
    fun saveRejectsBlankIdUrlAndRef() {
        val store = newStore()
        runBlocking {
            try {
                store.save(sampleConfig().copy(providerId = " "))
                error("expected PROVIDER_ID_BLANK")
            } catch (e: IllegalArgumentException) {
                assertEquals("PROVIDER_ID_BLANK", e.message)
            }
            try {
                store.save(sampleConfig().copy(baseUrl = ""))
                error("expected BASE_URL_BLANK")
            } catch (e: IllegalArgumentException) {
                assertEquals("BASE_URL_BLANK", e.message)
            }
            try {
                store.save(sampleConfig().copy(apiKeyRef = "  "))
                error("expected PROVIDER_KEY_REF_BLANK")
            } catch (e: IllegalArgumentException) {
                assertEquals("PROVIDER_KEY_REF_BLANK", e.message)
            }
        }
    }

    @Test
    fun saveRejectsNonHttpsBaseUrl() {
        val store = newStore()
        runBlocking {
            try {
                store.save(sampleConfig().copy(baseUrl = "http://evil.example.com"))
                error("expected PROVIDER_URL_MUST_BE_HTTPS")
            } catch (e: IllegalArgumentException) {
                assertEquals("PROVIDER_URL_MUST_BE_HTTPS", e.message)
            }
        }
        runBlocking {
            assertNull(store.observe().first())
        }
    }

    @Test
    fun partialDirtyDataReadsAsNull() = runBlocking {
        val store = newStore()
        store.dataStore.edit { prefs ->
            prefs[ENDPOINT_PROVIDER_ID_V1] = "preset:openai"
        }
        assertNull(store.observe().first())
        assertFalse(store.hasMetadata())
    }

    @Test
    fun clearReturnsToNull() = runBlocking {
        val store = newStore()
        store.save(sampleConfig())
        store.clear()
        assertNull(store.observe().first())
        assertFalse(store.hasMetadata())
    }

    @Test
    fun saveRejectsMalformedRef() {
        val store = newStore()
        runBlocking {
            try {
                // Pasted key material must never land in the ref field.
                store.save(sampleConfig().copy(apiKeyRef = "sk-abcDEF1234567890"))
                error("expected PROVIDER_KEY_REF_MALFORMED")
            } catch (e: IllegalArgumentException) {
                assertEquals("PROVIDER_KEY_REF_MALFORMED", e.message)
            }
            try {
                store.save(sampleConfig().copy(apiKeyRef = "other/x"))
                error("expected PROVIDER_KEY_REF_MALFORMED")
            } catch (e: IllegalArgumentException) {
                assertEquals("PROVIDER_KEY_REF_MALFORMED", e.message)
            }
            try {
                store.save(sampleConfig().copy(apiKeyRef = "provider_key/a\nb"))
                error("expected PROVIDER_KEY_REF_MALFORMED")
            } catch (e: IllegalArgumentException) {
                assertEquals("PROVIDER_KEY_REF_MALFORMED", e.message)
            }
            assertNull(store.observe().first())
        }
    }

    @Test
    fun dirtyBaseUrlReadsAsNotReady() = runBlocking {
        val store = newStore()
        // Bypass save() validation with a direct dirty write.
        store.dataStore.edit { prefs ->
            prefs[ENDPOINT_PROVIDER_ID_V1] = "preset:openai"
            prefs[ENDPOINT_BASE_URL_V1] = "http://evil.example.com"
            prefs[ENDPOINT_PROTOCOL_V1] = "CHAT_COMPLETIONS"
            prefs[ENDPOINT_API_KEY_REF_V1] = "provider_key/preset:openai"
        }
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        vault.putKey("preset:openai", "unit-test-key-123".toCharArray())
        assertEquals(EndpointReadiness.INVALID_BASE_URL, store.readiness(vault))
    }

    @Test
    fun readinessRequiresMetadataUrlAndKey() = runBlocking {
        val store = newStore()
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        assertEquals(EndpointReadiness.NO_ENDPOINT_METADATA, store.readiness(vault))

        store.save(sampleConfig())
        assertEquals(EndpointReadiness.NO_KEY, store.readiness(vault))

        vault.putKey("preset:openai", "unit-test-key-123".toCharArray())
        assertEquals(EndpointReadiness.READY, store.readiness(vault))
    }

    @Test
    fun readinessResolvesLiveAliasFromRef() = runBlocking {
        // Ref-driven: metadata committed through the staged scheme points at a
        // per-operation alias; readiness must honor the ref, not providerId.
        val store = newStore()
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        val staged = "preset:openai#staged-7"
        store.save(sampleConfig().copy(apiKeyRef = "provider_key/$staged"))
        // Key under the fixed alias alone is NOT enough.
        vault.putKey("preset:openai", "unit-test-key-123".toCharArray())
        assertEquals(EndpointReadiness.NO_KEY, store.readiness(vault))
        // Key under the staged alias addressed by the ref is READY.
        vault.putKey(staged, "unit-test-key-123".toCharArray())
        assertEquals(EndpointReadiness.READY, store.readiness(vault))
    }

    @Test
    fun readinessMalformedRefFailsClosedWithoutVaultTouch() = runBlocking {
        val store = newStore()
        store.dataStore.edit { prefs ->
            prefs[ENDPOINT_PROVIDER_ID_V1] = "preset:openai"
            prefs[ENDPOINT_BASE_URL_V1] = "https://api.openai.com/v1"
            prefs[ENDPOINT_PROTOCOL_V1] = "CHAT_COMPLETIONS"
            prefs[ENDPOINT_API_KEY_REF_V1] = "pasted-fake-key-material"
        }
        var touched = false
        val probe = object : KeyVault by EncryptedPrefsVault(InMemoryPrefs()) {
            override suspend fun hasKey(providerId: String): Boolean {
                touched = true
                return false
            }
        }
        assertEquals(EndpointReadiness.NO_KEY, store.readiness(probe))
        assertFalse(touched)
    }

    // ---- Phase 1: CAS fencing ----

    private class FailingDataStore(
        private val error: IOException = IOException("DATASTORE_UNAVAILABLE"),
    ) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw error }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw error
    }

    @Test
    fun currentRevisionExposesRawRevisionAcrossClear() = runBlocking {
        val store = newStore()
        assertNull(store.currentRevision())
        assertNull(store.observe().first())

        store.save(sampleConfig())
        assertEquals(1L, store.currentRevision())
        assertEquals(1L, store.observe().first()?.configRevision)

        store.save(sampleConfig().copy(baseUrl = "https://example.test/v1"))
        assertEquals(2L, store.currentRevision())
        assertEquals(2L, store.observe().first()?.configRevision)

        store.clear()
        // observe() folds the tombstone to null, but the raw revision stays visible.
        assertNull(store.observe().first())
        assertEquals(3L, store.currentRevision())

        store.save(sampleConfig())
        assertEquals(4L, store.currentRevision())
        assertEquals(4L, store.observe().first()?.configRevision)
    }

    @Test
    fun saveIfRevision_staleExpected_doesNotWrite() = runBlocking {
        val store = newStore()
        store.save(sampleConfig())
        assertEquals(1L, store.currentRevision())
        val before = store.observe().first()

        // Stale fresh-null expectation must lose against revision 1.
        assertFalse(store.saveIfRevision(sampleConfig().copy(label = "Stale"), expectedRevision = null))
        assertEquals(before, store.observe().first())
        assertEquals(1L, store.currentRevision())

        // Stale numeric expectation must also lose without writing.
        assertFalse(store.saveIfRevision(sampleConfig().copy(label = "Stale2"), expectedRevision = 0L))
        assertEquals(before, store.observe().first())
        assertEquals(1L, store.currentRevision())

        // Fresh expectation on the right revision wins.
        assertTrue(store.saveIfRevision(sampleConfig().copy(label = "Fresh"), expectedRevision = 1L))
        assertEquals(2L, store.currentRevision())
        assertEquals("Fresh", store.observe().first()?.label)
    }

    @Test
    fun saveIfRevision_freshNull_winsOnce() = runBlocking {
        val store = newStore()
        assertNull(store.currentRevision())

        assertTrue(store.saveIfRevision(sampleConfig(), expectedRevision = null))
        assertEquals(1L, store.currentRevision())
        assertEquals(sampleConfig().copy(configRevision = 1L), store.observe().first())

        // A second contender with the same stale fresh-null expectation must lose.
        assertFalse(
            store.saveIfRevision(sampleConfig().copy(label = "Second"), expectedRevision = null),
        )
        assertEquals(1L, store.currentRevision())
        assertEquals("OpenAI", store.observe().first()?.label)
    }

    @Test
    fun nullVsZero_distinct() = runBlocking {
        // Fresh store: null revision accepts a null-expected CAS.
        val fresh = newStore()
        assertNull(fresh.currentRevision())
        assertTrue(fresh.saveIfRevision(sampleConfig(), expectedRevision = null))
        assertEquals(1L, fresh.currentRevision())

        // Dirty hand-write: revision 0L with otherwise complete metadata.
        val dirty = newStore()
        dirty.dataStore.edit { prefs ->
            prefs[ENDPOINT_PROVIDER_ID_V1] = "preset:openai"
            prefs[ENDPOINT_PRESET_ID_V1] = "openai"
            prefs[ENDPOINT_LABEL_V1] = "OpenAI"
            prefs[ENDPOINT_BASE_URL_V1] = "https://api.openai.com/v1"
            prefs[ENDPOINT_PROTOCOL_V1] = "CHAT_COMPLETIONS"
            prefs[ENDPOINT_MODEL_V1] = "gpt-4o-mini"
            prefs[ENDPOINT_API_KEY_REF_V1] = "provider_key/preset:openai"
            prefs[ENDPOINT_CONFIG_REVISION_V1] = 0L
        }
        assertEquals(0L, dirty.currentRevision())
        assertEquals(0L, dirty.observe().first()?.configRevision)

        // Fresh-null expectation must NOT match dirty zero.
        assertFalse(dirty.saveIfRevision(sampleConfig(), expectedRevision = null))
        assertEquals(0L, dirty.currentRevision())

        // Explicit zero expectation wins and bumps to 1.
        assertTrue(dirty.saveIfRevision(sampleConfig(), expectedRevision = 0L))
        assertEquals(1L, dirty.currentRevision())
    }

    @Test
    fun saveIfRevision_rejectsInvalidInputWithoutWriting() = runBlocking {
        val store = newStore()
        assertNull(store.currentRevision())
        try {
            store.saveIfRevision(sampleConfig().copy(apiKeyRef = "sk-pasted-key"), expectedRevision = null)
            error("expected PROVIDER_KEY_REF_MALFORMED")
        } catch (e: IllegalArgumentException) {
            assertEquals("PROVIDER_KEY_REF_MALFORMED", e.message)
        }
        try {
            store.saveIfRevision(sampleConfig().copy(baseUrl = "http://evil.example.com"), expectedRevision = null)
            error("expected PROVIDER_URL_MUST_BE_HTTPS")
        } catch (e: IllegalArgumentException) {
            assertEquals("PROVIDER_URL_MUST_BE_HTTPS", e.message)
        }
        assertNull(store.currentRevision())
        assertNull(store.observe().first())
    }

    @Test
    fun concurrentWriters_exactlyOneWins() = runBlocking {
        val store = newStore()
        store.save(sampleConfig())
        val base = store.currentRevision()
        assertEquals(1L, base)

        // 10 racing writers on the same stale revision: DataStore serializes
        // edit{} transforms, so exactly one must win. Deterministic — no timing
        // assertions, only the CAS outcome count and final revision.
        val deferred = (0 until 10).map { i ->
            async {
                store.saveIfRevision(sampleConfig().copy(label = "W$i"), expectedRevision = base)
            }
        }
        val results = deferred.map { it.await() }
        assertEquals(1, results.count { it })
        assertEquals(9, results.count { !it })
        assertEquals(2L, store.currentRevision())
        val finalLabel = store.observe().first()?.label
        assertTrue((0 until 10).any { "W$it" == finalLabel })
    }

    @Test
    fun saveIfRevision_overflowFailsClosedWithoutPartialWrite() = runBlocking {
        val store = newStore()
        // Dirty-write MAX directly: only reachable via bypassing save()/saveIfRevision().
        store.dataStore.edit { prefs ->
            prefs[ENDPOINT_PROVIDER_ID_V1] = "preset:openai"
            prefs[ENDPOINT_PRESET_ID_V1] = "openai"
            prefs[ENDPOINT_LABEL_V1] = "OpenAI"
            prefs[ENDPOINT_BASE_URL_V1] = "https://api.openai.com/v1"
            prefs[ENDPOINT_PROTOCOL_V1] = "CHAT_COMPLETIONS"
            prefs[ENDPOINT_MODEL_V1] = "gpt-4o-mini"
            prefs[ENDPOINT_API_KEY_REF_V1] = "provider_key/preset:openai"
            prefs[ENDPOINT_CONFIG_REVISION_V1] = Long.MAX_VALUE
        }
        assertEquals(Long.MAX_VALUE, store.currentRevision())
        val before = store.observe().first()

        try {
            store.saveIfRevision(sampleConfig().copy(label = "Overflow"), expectedRevision = Long.MAX_VALUE)
            error("expected ArithmeticException")
        } catch (e: ArithmeticException) {
            // Math.addExact(MAX, 1) must fail closed.
        }
        // Revision stays MAX and no field was partially written.
        assertEquals(Long.MAX_VALUE, store.currentRevision())
        assertEquals(before, store.observe().first())
    }

    @Test
    fun clearIfRevision_fencedClear() = runBlocking {
        val store = newStore()
        // Fresh-null expectation wins once, like saveIfRevision.
        assertTrue(store.clearIfRevision(null))
        assertEquals(1L, store.currentRevision())
        assertNull(store.observe().first())

        store.save(sampleConfig())
        assertEquals(2L, store.currentRevision())

        // Stale expectation must lose without writing.
        assertFalse(store.clearIfRevision(1L))
        assertEquals(2L, store.currentRevision())
        assertEquals(sampleConfig().copy(configRevision = 2L), store.observe().first())

        // Correct expectation clears metadata but keeps the revision advancing.
        assertTrue(store.clearIfRevision(2L))
        assertNull(store.observe().first())
        assertEquals(3L, store.currentRevision())
    }

    @Test
    fun failuresPropagateFailClosed() = runBlocking {
        val failing = EndpointStore(FailingDataStore())
        try {
            failing.currentRevision()
            error("expected DATASTORE_UNAVAILABLE")
        } catch (e: IOException) {
            assertEquals("DATASTORE_UNAVAILABLE", e.message)
        }
        try {
            failing.saveIfRevision(sampleConfig(), expectedRevision = null)
            error("expected DATASTORE_UNAVAILABLE")
        } catch (e: IOException) {
            assertEquals("DATASTORE_UNAVAILABLE", e.message)
        }
        // Validation still runs before I/O and surfaces its own contract error.
        try {
            failing.saveIfRevision(sampleConfig().copy(apiKeyRef = "sk-pasted"), expectedRevision = null)
            error("expected PROVIDER_KEY_REF_MALFORMED")
        } catch (e: IllegalArgumentException) {
            assertEquals("PROVIDER_KEY_REF_MALFORMED", e.message)
        }
    }

    @Test
    fun snapshotStrictThrowsWhileObserveStaysLenient() = runBlocking {
        val failing = EndpointStore(FailingDataStore())
        // Lenient observe folds the failure to null (UI gate only).
        assertNull(failing.observe().first())
        // Strict read propagates fail-closed for destructive authorization.
        try {
            failing.snapshotStrict()
            error("expected DATASTORE_UNAVAILABLE")
        } catch (e: IOException) {
            assertEquals("DATASTORE_UNAVAILABLE", e.message)
        }
        try {
            failing.snapshotWithRevisionStrict()
            error("expected DATASTORE_UNAVAILABLE")
        } catch (e: IOException) {
            assertEquals("DATASTORE_UNAVAILABLE", e.message)
        }
    }

    @Test
    fun snapshotStrictRoundTripMatchesObserve() = runBlocking {        val store = newStore()
        assertNull(store.snapshotStrict())
        val (freshCfg, freshRev) = store.snapshotWithRevisionStrict()
        assertNull(freshCfg)
        assertNull(freshRev)
        store.save(sampleConfig())
        assertEquals(sampleConfig().copy(configRevision = 1L), store.snapshotStrict())
        assertEquals(sampleConfig().copy(configRevision = 1L), store.observe().first())
        val (cfg, rev) = store.snapshotWithRevisionStrict()
        assertEquals(1L, rev)
        assertEquals(sampleConfig().copy(configRevision = 1L), cfg)
        store.clear()
        // Tombstone: strict and lenient agree on null config, strict still
        // exposes the advancing raw revision in the pair (save 1 -> clear 2).
        assertNull(store.snapshotStrict())
        assertNull(store.observe().first())
        val (clearedCfg, clearedRev) = store.snapshotWithRevisionStrict()
        assertNull(clearedCfg)
        assertEquals(2L, clearedRev)
        assertEquals(2L, store.currentRevision())
    }

    // ---- Phase 1: pending-delete journal ----

    @Test
    fun pendingDeleteFormatParseRoundTrip() {
        val full = PendingDelete("preset:openai#staged-" + "c".repeat(32), 7L, 1700000000000L)
        assertEquals(full, PendingDelete.parse(full.format()))
        val freshNullRev = PendingDelete("preset:openai", null, 0L)
        assertEquals("v1|preset:openai||0", freshNullRev.format())
        assertEquals(freshNullRev, PendingDelete.parse(freshNullRev.format()))
    }

    @Test
    fun pendingDeleteParseRejectsMalformed() {
        for (raw in listOf(
            "",
            "garbage",
            "v1|only-two",
            "v1|a|1|2|extra",
            "v2|preset:openai|1|2",
            "v1||1|2",
            "v1|   |1|2",
            "v1|a|b|not-a-number",
            "v1|preset:openai|x|1700000000000",
            "v1|preset:openai|1|not-a-ts",
            "v1|preset:openai|1|-1",
            "v1|a|b|c|1|2",
        )) {
            try {
                PendingDelete.parse(raw)
                error("expected ENDPOINT_PENDING_DELETE_MALFORMED for $raw")
            } catch (e: IllegalStateException) {
                assertEquals("ENDPOINT_PENDING_DELETE_MALFORMED", e.message)
            }
        }
        try {
            PendingDelete("a|b", 1L, 0L)
            error("expected PENDING_DELETE_ALIAS_MALFORMED")
        } catch (e: IllegalArgumentException) {
            assertEquals("PENDING_DELETE_ALIAS_MALFORMED", e.message)
        }
    }

    @Test
    fun writePendingDeleteIfAbsentNeverOverwritesAndNeverBumpsRevision() = runBlocking {
        val store = newStore()
        store.save(sampleConfig())
        assertEquals(1L, store.currentRevision())
        assertTrue(store.writePendingDeleteIfAbsent(PendingDelete("preset:openai", 1L, 100L)))
        assertEquals(1L, store.currentRevision())
        // A concurrent teardown must not clobber the first journal.
        assertFalse(store.writePendingDeleteIfAbsent(PendingDelete("other", 1L, 200L)))
        assertEquals(PendingDelete("preset:openai", 1L, 100L), store.pendingDelete())
        assertEquals(1L, store.currentRevision())
        val (pending, config, rev) = store.snapshotDeleteStateStrict()
        assertEquals(PendingDelete("preset:openai", 1L, 100L), pending)
        assertEquals(sampleConfig().copy(configRevision = 1L), config)
        assertEquals(1L, rev)
    }

    @Test
    fun pendingFoldsLenientReadersButNotStrict() = runBlocking {
        val store = newStore()
        store.save(sampleConfig())
        store.writePendingDeleteIfAbsent(PendingDelete("preset:openai", 1L, 100L))
        // Lenient readers fold first-unavailable (even though the raw metadata
        // is intact underneath).
        assertNull(store.observe().first())
        assertFalse(store.hasMetadata())
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        vault.putKey("preset:openai", "unit-test-key-123".toCharArray())
        assertEquals(EndpointReadiness.NO_KEY, store.readiness(vault))
        // Strict readers stay raw for fencing/reconcile.
        assertEquals(sampleConfig().copy(configRevision = 1L), store.snapshotStrict())
        assertEquals(1L, store.currentRevision())
        assertEquals(PendingDelete("preset:openai", 1L, 100L), store.pendingDelete())
    }

    @Test
    fun malformedPendingFoldsLenientReaders() = runBlocking {
        val store = newStore()
        store.save(sampleConfig())
        store.dataStore.edit { prefs ->
            prefs[ENDPOINT_PENDING_DELETE_V1] = "v9|broken"
        }
        assertNull(store.observe().first())
        assertFalse(store.hasMetadata())
        try {
            store.pendingDelete()
            error("expected ENDPOINT_PENDING_DELETE_MALFORMED")
        } catch (e: IllegalStateException) {
            assertEquals("ENDPOINT_PENDING_DELETE_MALFORMED", e.message)
        }
        try {
            store.snapshotDeleteStateStrict()
            error("expected ENDPOINT_PENDING_DELETE_MALFORMED")
        } catch (e: IllegalStateException) {
            assertEquals("ENDPOINT_PENDING_DELETE_MALFORMED", e.message)
        }
        // Raw metadata survives underneath the fold.
        assertEquals(sampleConfig().copy(configRevision = 1L), store.snapshotStrict())
        assertEquals(1L, store.currentRevision())
    }

    @Test
    fun clearIfRevisionRemovesJournalOnWinKeepsOnLoss() = runBlocking {
        val store = newStore()
        store.save(sampleConfig())
        store.writePendingDeleteIfAbsent(PendingDelete("preset:openai", 1L, 100L))
        // Stale expectation loses without writing: journal AND metadata stay.
        assertFalse(store.clearIfRevision(0L))
        assertEquals(PendingDelete("preset:openai", 1L, 100L), store.pendingDelete())
        assertEquals(1L, store.currentRevision())
        // Win removes metadata and the journal in the same atomic edit.
        assertTrue(store.clearIfRevision(1L))
        assertNull(store.pendingDelete())
        assertNull(store.observe().first())
        assertEquals(2L, store.currentRevision())
    }

    @Test
    fun clearRemovesJournalAndRemovePendingKeepsMetadata() = runBlocking {
        val store = newStore()
        store.save(sampleConfig())
        store.writePendingDeleteIfAbsent(PendingDelete("preset:openai", 1L, 100L))
        // STALE-path discard: only the journal goes, metadata + revision stay.
        store.removePendingDelete()
        assertNull(store.pendingDelete())
        assertEquals(sampleConfig().copy(configRevision = 1L), store.observe().first())
        assertEquals(1L, store.currentRevision())
        // Authoritative clear resets the journal too.
        store.writePendingDeleteIfAbsent(PendingDelete("preset:openai", 1L, 100L))
        store.clear()
        assertNull(store.pendingDelete())
        assertNull(store.observe().first())
        assertEquals(2L, store.currentRevision())
    }
}
