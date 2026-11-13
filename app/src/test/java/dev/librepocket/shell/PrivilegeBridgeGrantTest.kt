package dev.librepocket.shell

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * B1 橋授權持久化測試（Issue #136 B1）：
 * grant 綁定 endpoint/config revision＋可選到期，撤銷即失效，fail-closed。
 * DataStore 以檔案級假實例（`PreferenceDataStoreFactory`＋暫存檔）驅動，
 * 純 JVM，不碰真機/真 su/Shizuku 連線。
 */
class PrivilegeBridgeGrantTest {

    private lateinit var dir: File
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        dir = kotlin.io.path.createTempDirectory("privilege-bridge-grant-test").toFile()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @After fun tearDown() {
        PrivilegeBridgeState.resetProcessForTest()
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun store(name: String = "bridge_${System.nanoTime()}.preferences_pb"): PrivilegeBridgeStore {
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, name) }
        return PrivilegeBridgeStore(dataStore)
    }

    /** 全路徑失敗的 DataStore 假件：讀折 null、寫抛錯（fail-closed 斷言用）。 */
    private class ThrowingDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("boom") }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IOException("boom")
    }

    @Test fun neverGrantedIsInvalid() {
        val check = PrivilegeGrantPolicy.check(null, "preset:openai", 3L)
        assertTrue("$check", check is PrivilegeGrantCheck.Invalid)
        assertEquals(PrivilegeGrantPolicy.REASON_NEVER_GRANTED, (check as PrivilegeGrantCheck.Invalid).reason)
    }

    @Test fun malformedGrantIsInvalid() {
        // Blank providerId
        val blankCheck = PrivilegeGrantPolicy.check(
            PrivilegeGrant("", 3L, 1000L, null),
            "preset:openai",
            3L,
        )
        assertTrue("$blankCheck", blankCheck is PrivilegeGrantCheck.Invalid)
        assertEquals(PrivilegeGrantPolicy.REASON_MALFORMED, (blankCheck as PrivilegeGrantCheck.Invalid).reason)

        // ISO control chars in providerId
        val isoControlCheck = PrivilegeGrantPolicy.check(
            PrivilegeGrant("preset:\u0000openai", 3L, 1000L, null),
            "preset:\u0000openai",
            3L,
        )
        assertTrue("$isoControlCheck", isoControlCheck is PrivilegeGrantCheck.Invalid)
        assertEquals(PrivilegeGrantPolicy.REASON_MALFORMED, (isoControlCheck as PrivilegeGrantCheck.Invalid).reason)

        // Non-positive config revision
        val zeroRevCheck = PrivilegeGrantPolicy.check(
            PrivilegeGrant("preset:openai", 0L, 1000L, null),
            "preset:openai",
            0L,
        )
        assertTrue("$zeroRevCheck", zeroRevCheck is PrivilegeGrantCheck.Invalid)
        assertEquals(PrivilegeGrantPolicy.REASON_MALFORMED, (zeroRevCheck as PrivilegeGrantCheck.Invalid).reason)

        val negativeRevCheck = PrivilegeGrantPolicy.check(
            PrivilegeGrant("preset:openai", -1L, 1000L, null),
            "preset:openai",
            -1L,
        )
        assertTrue("$negativeRevCheck", negativeRevCheck is PrivilegeGrantCheck.Invalid)
        assertEquals(PrivilegeGrantPolicy.REASON_MALFORMED, (negativeRevCheck as PrivilegeGrantCheck.Invalid).reason)

        // Negative grantedAtMs
        val negAtCheck = PrivilegeGrantPolicy.check(
            PrivilegeGrant("preset:openai", 3L, -1L, null),
            "preset:openai",
            3L,
        )
        assertTrue("$negAtCheck", negAtCheck is PrivilegeGrantCheck.Invalid)
        assertEquals(PrivilegeGrantPolicy.REASON_MALFORMED, (negAtCheck as PrivilegeGrantCheck.Invalid).reason)

        // expiresAtMs < grantedAtMs
        val invertedExpiryCheck = PrivilegeGrantPolicy.check(
            PrivilegeGrant("preset:openai", 3L, 1000L, 999L),
            "preset:openai",
            3L,
        )
        assertTrue("$invertedExpiryCheck", invertedExpiryCheck is PrivilegeGrantCheck.Invalid)
        assertEquals(PrivilegeGrantPolicy.REASON_MALFORMED, (invertedExpiryCheck as PrivilegeGrantCheck.Invalid).reason)
    }

    @Test fun clockRollbackInvalidatesGrant() {
        val atMs = 1_700_000_000_000L
        val grant = PrivilegeGrantPolicy.grant("preset:openai", 3L, ttlMs = 60_000L, atMs = atMs)
        // System time rolled back before grantedAtMs
        val rollback = PrivilegeGrantPolicy.check(grant, "preset:openai", 3L, nowMs = atMs - 1L)
        assertTrue("$rollback", rollback is PrivilegeGrantCheck.Invalid)
        assertEquals(
            PrivilegeGrantPolicy.REASON_CLOCK_ROLLBACK,
            (rollback as PrivilegeGrantCheck.Invalid).reason,
        )
    }

    @Test fun unreadableConfigFailsClosed() {
        val grant = PrivilegeGrantPolicy.grant("preset:openai", 3L)
        assertTrue(PrivilegeGrantPolicy.check(grant, null, 3L) is PrivilegeGrantCheck.Invalid)
        assertTrue(PrivilegeGrantPolicy.check(grant, "", 3L) is PrivilegeGrantCheck.Invalid)
        assertTrue(PrivilegeGrantPolicy.check(grant, "preset:openai", null) is PrivilegeGrantCheck.Invalid)
        val unreadable = PrivilegeGrantPolicy.check(grant, null, 3L) as PrivilegeGrantCheck.Invalid
        assertEquals(PrivilegeGrantPolicy.REASON_CONFIG_UNREADABLE, unreadable.reason)
    }

    @Test fun endpointSwitchInvalidatesGrant() {
        val grant = PrivilegeGrantPolicy.grant("preset:openai", 3L)
        val check = PrivilegeGrantPolicy.check(grant, "custom:abcdef", 3L)
        assertTrue("$check", check is PrivilegeGrantCheck.Invalid)
        assertEquals(PrivilegeGrantPolicy.REASON_ENDPOINT_CHANGED, (check as PrivilegeGrantCheck.Invalid).reason)
    }

    @Test fun revisionBumpInvalidatesGrant() {
        val grant = PrivilegeGrantPolicy.grant("preset:openai", 3L)
        val check = PrivilegeGrantPolicy.check(grant, "preset:openai", 4L)
        assertTrue("$check", check is PrivilegeGrantCheck.Invalid)
        assertEquals(PrivilegeGrantPolicy.REASON_REVISION_STALE, (check as PrivilegeGrantCheck.Invalid).reason)
    }

    @Test fun matchingEndpointAndRevisionIsValid() {
        val grant = PrivilegeGrantPolicy.grant("preset:openai", 3L)
        assertTrue(PrivilegeGrantPolicy.check(grant, "preset:openai", 3L) is PrivilegeGrantCheck.Valid)
    }

    @Test fun ttlExpiryBoundary() {
        val atMs = 1_700_000_000_000L
        val grant = PrivilegeGrantPolicy.grant("preset:openai", 3L, ttlMs = 1_000L, atMs = atMs)
        assertEquals(atMs + 1_000L, grant.expiresAtMs)
        assertTrue(PrivilegeGrantPolicy.check(grant, "preset:openai", 3L, nowMs = atMs + 999L) is PrivilegeGrantCheck.Valid)
        // 到期瞬間（nowMs == expiresAtMs）已失效，非下一毫秒才失效。
        val expired = PrivilegeGrantPolicy.check(grant, "preset:openai", 3L, nowMs = atMs + 1_000L)
        assertTrue("$expired", expired is PrivilegeGrantCheck.Invalid)
        assertEquals(PrivilegeGrantPolicy.REASON_EXPIRED, (expired as PrivilegeGrantCheck.Invalid).reason)
        // 到期之後（nowMs > expiresAtMs）亦失效。
        val afterExpiry = PrivilegeGrantPolicy.check(grant, "preset:openai", 3L, nowMs = atMs + 1_001L)
        assertTrue("$afterExpiry", afterExpiry is PrivilegeGrantCheck.Invalid)
        assertEquals(PrivilegeGrantPolicy.REASON_EXPIRED, (afterExpiry as PrivilegeGrantCheck.Invalid).reason)
    }

    @Test fun nullTtlNeverExpires() {
        val grant = PrivilegeGrantPolicy.grant("preset:openai", 3L, ttlMs = null, atMs = 0L)
        assertNull(grant.expiresAtMs)
        assertTrue(PrivilegeGrantPolicy.check(grant, "preset:openai", 3L, nowMs = Long.MAX_VALUE) is PrivilegeGrantCheck.Valid)
    }

    @Test fun zeroTtlExpiresImmediately() {
        val atMs = 1_700_000_000_000L
        val grant = PrivilegeGrantPolicy.grant("preset:openai", 3L, ttlMs = 0L, atMs = atMs)
        val check = PrivilegeGrantPolicy.check(grant, "preset:openai", 3L, nowMs = atMs)
        assertTrue("$check", check is PrivilegeGrantCheck.Invalid)
        assertEquals(PrivilegeGrantPolicy.REASON_EXPIRED, (check as PrivilegeGrantCheck.Invalid).reason)
    }

    @Test fun negativeTtlFailsFast() {
        try {
            PrivilegeGrantPolicy.grant("preset:openai", 3L, ttlMs = -1L)
            fail("negative ttl must throw")
        } catch (_: IllegalArgumentException) {
            // 預期。
        }
    }

    @Test fun ttlOverflowThrows() {
        try {
            PrivilegeGrantPolicy.grant("preset:openai", 3L, ttlMs = 100L, atMs = Long.MAX_VALUE - 10L)
            fail("ttl overflow must throw")
        } catch (e: IllegalArgumentException) {
            assertEquals("TTL_OVERFLOW", e.message)
        }
    }

    @Test fun grantValidationRejectsInvalidInputs() {
        try {
            PrivilegeGrantPolicy.grant("  ", 3L)
            fail("blank providerId must throw")
        } catch (e: IllegalArgumentException) {
            assertEquals("PROVIDER_ID_INVALID", e.message)
        }
        try {
            PrivilegeGrantPolicy.grant("provider\u0007", 3L)
            fail("control char in providerId must throw")
        } catch (e: IllegalArgumentException) {
            assertEquals("PROVIDER_ID_INVALID", e.message)
        }
        try {
            PrivilegeGrantPolicy.grant("preset:openai", 0L)
            fail("revision 0 must throw")
        } catch (e: IllegalArgumentException) {
            assertEquals("CONFIG_REVISION_INVALID", e.message)
        }
        try {
            PrivilegeGrantPolicy.grant("preset:openai", -1L)
            fail("negative revision must throw")
        } catch (e: IllegalArgumentException) {
            assertEquals("CONFIG_REVISION_INVALID", e.message)
        }
        try {
            PrivilegeGrantPolicy.grant("preset:openai", 3L, atMs = -1L)
            fail("negative atMs must throw")
        } catch (e: IllegalArgumentException) {
            assertEquals("AT_MS_NEGATIVE", e.message)
        }
        try {
            PrivilegeGrantPolicy.grant("preset:openai", 3L, ttlMs = -1L)
            fail("negative ttlMs must throw")
        } catch (e: IllegalArgumentException) {
            assertEquals("TTL_NEGATIVE", e.message)
        }
    }

    @Test fun saveBlankProviderThrows() {
        try {
            PrivilegeGrantPolicy.grant("  ", 3L)
            fail("blank providerId must throw")
        } catch (_: IllegalArgumentException) {
            // 預期。
        }
        runBlocking {
            val store = store()
            try {
                store.save(PrivilegeGrant("", 3L, 0L, null))
                fail("blank providerId save must throw")
            } catch (e: IllegalArgumentException) {
                assertEquals("PROVIDER_ID_INVALID", e.message)
            }
            try {
                store.save(PrivilegeGrant("provider\u0000", 3L, 0L, null))
                fail("control char in providerId save must throw")
            } catch (e: IllegalArgumentException) {
                assertEquals("PROVIDER_ID_INVALID", e.message)
            }
            try {
                store.save(PrivilegeGrant("provider", 0L, 0L, null))
                fail("zero revision save must throw")
            } catch (e: IllegalArgumentException) {
                assertEquals("CONFIG_REVISION_INVALID", e.message)
            }
            try {
                store.save(PrivilegeGrant("provider", 3L, -1L, null))
                fail("negative atMs save must throw")
            } catch (e: IllegalArgumentException) {
                assertEquals("AT_MS_NEGATIVE", e.message)
            }
            try {
                store.save(PrivilegeGrant("provider", 3L, 1000L, 999L))
                fail("inverted expiry save must throw")
            } catch (e: IllegalArgumentException) {
                assertEquals("EXPIRES_AT_INVALID", e.message)
            }
        }
    }

    @Test fun storeRoundTripBindsRevisionAndExpiry() = runBlocking {
        val store = store()
        assertNull(store.load())
        val grant = PrivilegeGrantPolicy.grant("preset:openai", 7L, ttlMs = 60_000L, atMs = 1_700_000_000_000L)
        store.save(grant)
        assertEquals(grant, store.load())
        assertTrue(store.check("preset:openai", 7L, nowMs = 1_700_000_000_000L) is PrivilegeGrantCheck.Valid)
        // 設定推進即失效（endpoint 切換同）。
        assertTrue(store.check("preset:openai", 8L) is PrivilegeGrantCheck.Invalid)
        assertTrue(store.check("custom:other", 7L) is PrivilegeGrantCheck.Invalid)
        // 到期即失效。
        val expired = store.check("preset:openai", 7L, nowMs = 1_700_000_000_000L + 60_000L)
        assertTrue("$expired", expired is PrivilegeGrantCheck.Invalid)
        assertEquals(PrivilegeGrantPolicy.REASON_EXPIRED, (expired as PrivilegeGrantCheck.Invalid).reason)
    }

    @Test fun storeRoundTripWithoutExpiry() = runBlocking {
        val store = store()
        val grant = PrivilegeGrantPolicy.grant("preset:openai", 7L, ttlMs = null, atMs = 1L)
        store.save(grant)
        assertEquals(grant, store.load())
        assertNull(store.load()!!.expiresAtMs)
        // 永不過期授權被有到期授權覆寫後，缺鍵即 null（不得殘留舊到期）。
        store.save(PrivilegeGrantPolicy.grant("preset:openai", 7L, ttlMs = 1L, atMs = 1L))
        store.save(grant)
        assertNull(store.load()!!.expiresAtMs)
    }

    @Test fun revokeClearsPersistence() = runBlocking {
        val store = store()
        store.save(PrivilegeGrantPolicy.grant("preset:openai", 7L))
        store.clear()
        assertNull(store.load())
        val check = store.check("preset:openai", 7L)
        assertTrue("$check", check is PrivilegeGrantCheck.Invalid)
        assertEquals(PrivilegeGrantPolicy.REASON_NEVER_GRANTED, (check as PrivilegeGrantCheck.Invalid).reason)
    }

    @Test fun loadMissingKeysYieldsNull() = runBlocking {
        assertNull(store().load())
        val partial = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "partial_${System.nanoTime()}.preferences_pb")
        }
        partial.edit { it[PRIVILEGE_GRANT_PROVIDER_ID] = "preset:openai" }
        assertNull(PrivilegeBridgeStore(partial).load())
        val blank = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "blank_${System.nanoTime()}.preferences_pb")
        }
        blank.edit {
            it[PRIVILEGE_GRANT_PROVIDER_ID] = "  "
            it[PRIVILEGE_GRANT_CONFIG_REVISION] = 3L
            it[PRIVILEGE_GRANT_AT_MS] = 0L
        }
        assertNull(PrivilegeBridgeStore(blank).load())

        val missingAtMs = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "missing_at_ms_${System.nanoTime()}.preferences_pb")
        }
        missingAtMs.edit {
            it[PRIVILEGE_GRANT_PROVIDER_ID] = "preset:openai"
            it[PRIVILEGE_GRANT_CONFIG_REVISION] = 3L
        }
        assertNull(PrivilegeBridgeStore(missingAtMs).load())
    }

    @Test fun cancellationExceptionPropagatesWithoutBeingSwallowed() {
        runBlocking {
            val cancellingDataStore = object : DataStore<Preferences> {
                override val data: Flow<Preferences> = flow {
                    throw kotlinx.coroutines.CancellationException("test cancel")
                }
                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                    throw kotlinx.coroutines.CancellationException("test cancel")
                }
            }
            val store = PrivilegeBridgeStore(cancellingDataStore)
            assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                runBlocking { store.load() }
            }
            assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                runBlocking { store.save(PrivilegeGrantPolicy.grant("preset:openai", 1L)) }
            }
            assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                runBlocking { store.clear() }
            }
        }
    }

    @Test fun loadFailureFoldsToNull() = runBlocking {
        val store = PrivilegeBridgeStore(ThrowingDataStore())
        assertNull(store.load())
        val check = store.check("preset:openai", 3L)
        assertTrue("$check", check is PrivilegeGrantCheck.Invalid)
        assertEquals(PrivilegeGrantPolicy.REASON_NEVER_GRANTED, (check as PrivilegeGrantCheck.Invalid).reason)
    }

    @Test fun stateGrantPersistsAndSetsFlag() = runBlocking {
        val store = store()
        val state = PrivilegeBridgeState(bridgeSwitchOn = true)
        assertFalse(state.bridgeGranted)
        val grant = state.grant(store, "preset:openai", 7L)
        assertTrue(state.bridgeGranted)
        // grant 不動開關（UI 側意圖，見 PrivilegeBridgeState KDoc）。
        assertTrue(state.bridgeSwitchOn)
        assertEquals(grant, store.load())
    }

    @Test fun stateGrantFailureLeavesFlagOff() = runBlocking {
        val store = PrivilegeBridgeStore(ThrowingDataStore())
        val state = PrivilegeBridgeState()
        try {
            state.grant(store, "preset:openai", 7L)
            fail("save failure must throw")
        } catch (_: IOException) {
            // 預期。
        }
        assertFalse(state.bridgeGranted)
    }

    @Test fun stateRefreshSyncsFlagFromStore() = runBlocking {
        val store = store()
        val state = PrivilegeBridgeState()
        store.save(PrivilegeGrantPolicy.grant("preset:openai", 7L))
        assertTrue(state.refresh(store, "preset:openai", 7L) is PrivilegeGrantCheck.Valid)
        assertTrue(state.bridgeGranted)
        // revision 推進：次 refresh 即失效＋落旗。
        val stale = state.refresh(store, "preset:openai", 8L)
        assertTrue("$stale", stale is PrivilegeGrantCheck.Invalid)
        assertEquals(PrivilegeGrantPolicy.REASON_REVISION_STALE, (stale as PrivilegeGrantCheck.Invalid).reason)
        assertFalse(state.bridgeGranted)
    }

    @Test fun stateRefreshExpirySyncsFlag() = runBlocking {
        val store = store()
        val state = PrivilegeBridgeState()
        store.save(PrivilegeGrantPolicy.grant("preset:openai", 7L, ttlMs = 1_000L, atMs = 1_700_000_000_000L))
        assertTrue(
            state.refresh(store, "preset:openai", 7L, nowMs = 1_700_000_000_000L)
                is PrivilegeGrantCheck.Valid,
        )
        assertTrue(state.bridgeGranted)
        val expired = state.refresh(store, "preset:openai", 7L, nowMs = 1_700_000_000_000L + 1_000L)
        assertTrue("$expired", expired is PrivilegeGrantCheck.Invalid)
        assertEquals(PrivilegeGrantPolicy.REASON_EXPIRED, (expired as PrivilegeGrantCheck.Invalid).reason)
        assertFalse(state.bridgeGranted)
    }

    @Test fun stateRevokeClearsStoreAuditAndFlags() = runBlocking {
        val store = store()
        val state = PrivilegeBridgeState(bridgeSwitchOn = true)
        state.grant(store, "preset:openai", 7L)
        repeat(2) { state.audit.record(listOf("id"), "B1-$it", "DENY") }
        var scopeWithdrawn = false
        val cleared = state.revoke(store) { scopeWithdrawn = true }
        assertEquals(2, cleared)
        assertEquals(0, state.auditCount())
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
        assertNull(store.load())
        assertTrue("withdrawScope must run", scopeWithdrawn)
    }

    @Test fun stateRevokeScopeFailureStillDropsMemory() = runBlocking {
        val store = store()
        val state = PrivilegeBridgeState(bridgeSwitchOn = true)
        state.grant(store, "preset:openai", 7L)
        try {
            state.revoke(store) { throw IOException("scope boom") }
            fail("scope failure must propagate")
        } catch (_: IOException) {
            // 預期。
        }
        // 持久層已清（先於 scope），記憶體亦已收回（fail-closed）。
        assertNull(store.load())
        assertEquals(0, state.auditCount())
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
    }

    @Test fun stateRevokeStoreFailureStillDropsMemory() = runBlocking {
        val store = PrivilegeBridgeStore(ThrowingDataStore())
        val state = PrivilegeBridgeState(bridgeSwitchOn = true, bridgeGranted = true)
        state.audit.record(listOf("id"), "B1-x", "DENY")
        try {
            state.revoke(store)
            fail("store failure must propagate")
        } catch (_: IOException) {
            // 預期。
        }
        assertEquals(0, state.auditCount())
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
    }

    @Test fun stateRevokeStoreFailureStillRunsWithdrawScope() = runBlocking {
        val store = PrivilegeBridgeStore(ThrowingDataStore())
        val state = PrivilegeBridgeState(bridgeSwitchOn = true, bridgeGranted = true)
        state.audit.record(listOf("id"), "B1-x", "DENY")
        var withdrawScopeRan = false
        try {
            state.revoke(store) { withdrawScopeRan = true }
            fail("store failure must propagate")
        } catch (_: IOException) {
            // Expected
        }
        assertTrue("withdrawScope must run even if store.clear fails", withdrawScopeRan)
        assertEquals(0, state.auditCount())
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
    }

    @Test fun checkEvaluatesClockAfterLoadCompletes() = runBlocking {
        var loadCompleted = false
        val backing = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "clock_test.preferences_pb") }
        val delegating = object : DataStore<Preferences> by backing {
            override val data: Flow<Preferences> = backing.data.map {
                loadCompleted = true
                it
            }
        }
        var clockEvaluatedAfterLoad = false
        val store = PrivilegeBridgeStore(
            dataStore = delegating,
            clock = {
                clockEvaluatedAfterLoad = loadCompleted
                1_700_000_000_000L
            },
        )
        store.save(PrivilegeGrantPolicy.grant("preset:openai", 3L, ttlMs = 10_000L, atMs = 1_700_000_000_000L))
        loadCompleted = false
        val check = store.check("preset:openai", 3L)
        assertTrue(check is PrivilegeGrantCheck.Valid)
        assertTrue("clock must be queried AFTER load completes", clockEvaluatedAfterLoad)
    }

    private class FailOnceClearDataStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> by delegate {
        var failOnUpdate = false

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (failOnUpdate) {
                failOnUpdate = false
                throw IOException("simulated clear failure")
            }
            return delegate.updateData(transform)
        }
    }

    @Test fun pendingRevokeMarkerPreventsResurrectionAndSelfHeals() = runBlocking {
        val backing = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "pending_marker.preferences_pb") }
        val failOnceDs = FailOnceClearDataStore(backing)
        val markerFile = File(dir, PRIVILEGE_PENDING_REVOKE_FILE_NAME)
        val marker = PendingRevokeMarker(markerFile)
        val store = PrivilegeBridgeStore(failOnceDs, revokeMarker = marker)

        val grant = PrivilegeGrantPolicy.grant("preset:openai", 5L)
        store.save(grant)
        assertEquals(grant, store.load())
        assertFalse("Marker should not be pending after successful save", marker.isPending())

        // Simulate clear failure
        failOnceDs.failOnUpdate = true
        try {
            store.clear()
            fail("store.clear should fail")
        } catch (_: IOException) {
            // Expected
        }

        // Marker remains pending on disk
        assertTrue("Marker must remain pending after failed clear", marker.isPending())
        assertTrue(markerFile.exists())

        // Simulate app restart: create a new Store instance with normal DataStore and the same marker file
        val restartedStore = PrivilegeBridgeStore(backing, revokeMarker = PendingRevokeMarker(markerFile))
        // Load must detect pending marker, self-heal by clearing DataStore, and return null (no resurrection)
        assertNull("Pending marker must prevent resurrection on load", restartedStore.load())
        assertFalse("Self-healing must have cleared the pending marker", markerFile.exists())
        assertFalse(marker.isPending())

        // Subsequent load returns null
        assertNull(restartedStore.load())
    }

    @Test fun processSingletonIsStableUntilReset() {
        val first = PrivilegeBridgeState.process()
        assertSame(first, PrivilegeBridgeState.process())
        PrivilegeBridgeState.resetProcessForTest()
        val second = PrivilegeBridgeState.process()
        assertTrue("reset must yield a new instance", second !== first)
        assertSame(second, PrivilegeBridgeState.process())
    }
}
