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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
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

    /**
     * 精準失敗第 N 次（1-based）updateData 的假件：
     * save() 消耗 call #1，clear() 的 journal 寫入為 call #2、擦除 edit 為 call #3。
     * failOnCall=2 亦有測試使用（journal 寫入失敗 → best-effort wipe →
     * rethrow 原始例外，見 journalWriteFailure_bestEffortWipesKeys_sameProcessAndRestartDeny）。
     */
    private class FailOnUpdateCallDataStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> by delegate {
        var failOnCall: Int? = null
        private var call = 0

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            call += 1
            if (failOnCall == call) {
                throw IOException("simulated update failure at call #$call")
            }
            return delegate.updateData(transform)
        }
    }

    @Test fun journalBlocksResurrection_whenClearFails_afterJournalWritten() = runBlocking {
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "journal_blocks.preferences_pb")
        }
        val failingDs = FailOnUpdateCallDataStore(backing)
        val markerFile = File(dir, "journal_blocks.pending")
        val store = PrivilegeBridgeStore(failingDs, revokeMarker = PendingRevokeMarker(markerFile))

        val grant = PrivilegeGrantPolicy.grant("preset:openai", 5L)
        store.save(grant) // updateData call #1（save 內 journal 清除）
        failingDs.failOnCall = 3 // call #2＝journal 寫入（成功）；call #3＝擦除 edit（失敗）
        try {
            store.clear()
            fail("clear must throw when the grant-removal edit fails after journal is written")
        } catch (_: IOException) {
            // 預期
        }

        // journal 已落盤、grant 鍵仍在 backing（擦除被阻斷）——缺 journal 保護必復活。
        val prefsBefore = backing.data.first()
        assertEquals(true, prefsBefore[PRIVILEGE_REVOKE_JOURNAL])
        assertEquals("preset:openai", prefsBefore[PRIVILEGE_GRANT_PROVIDER_ID])

        // 模擬重啟：新 Store 實例＋同一 backing 檔；刻意用 in-memory-only marker
        // 排除 marker 分支，單獨證明 journal 自癒的復活防護。
        val restarted = PrivilegeBridgeStore(backing, revokeMarker = PendingRevokeMarker(null))
        assertNull("journal must block resurrection even without any marker", restarted.load())
        // 自癒擦除了殘留 grant 鍵與 journal。
        val prefsAfter = backing.data.first()
        assertFalse(prefsAfter.contains(PRIVILEGE_REVOKE_JOURNAL))
        assertFalse(prefsAfter.contains(PRIVILEGE_GRANT_PROVIDER_ID))
        // 自癒完成後再 load 仍為 null。
        assertNull(restarted.load())
    }

    @Test fun journalWritten_clearThrows_restartLoadDenied() = runBlocking {
        // 跨「新 Store 實例＋同一 backing DataStore 檔」的復活防護：
        // 重啟實例的 marker 指向同一磁帶 marker 檔（前次 clear 已落盤），
        // marker 分支自癒並同時擦除 journal。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "journal_restart.preferences_pb")
        }
        val failingDs = FailOnUpdateCallDataStore(backing)
        val markerFile = File(dir, "journal_restart.pending")
        val store = PrivilegeBridgeStore(failingDs, revokeMarker = PendingRevokeMarker(markerFile))

        store.save(PrivilegeGrantPolicy.grant("preset:openai", 5L))
        failingDs.failOnCall = 3
        try {
            store.clear()
            fail("clear must throw")
        } catch (_: IOException) {
            // 預期
        }
        assertEquals(true, backing.data.first()[PRIVILEGE_REVOKE_JOURNAL])

        // 新實例＋同一 marker 檔＋同一 backing：load 必 null（marker 也好、journal 也好，絕不復活）。
        val restarted = PrivilegeBridgeStore(backing, revokeMarker = PendingRevokeMarker(markerFile))
        assertNull(restarted.load())
        val prefsAfter = backing.data.first()
        assertFalse("marker-branch self-heal must also remove the journal", prefsAfter.contains(PRIVILEGE_REVOKE_JOURNAL))
        assertFalse(prefsAfter.contains(PRIVILEGE_GRANT_PROVIDER_ID))
    }

    @Test fun pendingMarker_parentIsRegularFile_clearFails_restartGrantMustNotResurrect() = runBlocking {
        // marker 父路徑為普通檔案：markPending() 落盤必敗（回 false），journal 是唯一持久保障。
        val regularParent = File(dir, "regular_parent_marker_dir")
        assertTrue(regularParent.createNewFile())
        val markerFile = File(regularParent, "x.pending")

        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "parent_regular_file.preferences_pb")
        }
        val failingDs = FailOnUpdateCallDataStore(backing)
        val store = PrivilegeBridgeStore(failingDs, revokeMarker = PendingRevokeMarker(markerFile))

        store.save(PrivilegeGrantPolicy.grant("preset:openai", 5L))
        failingDs.failOnCall = 3 // clear() 擦除 edit 丟 IOException；journal（call #2）已成功落盤
        try {
            store.clear()
            fail("clear must throw")
        } catch (_: IOException) {
            // 預期
        }
        assertFalse("marker can never persist under a regular-file parent", markerFile.exists())
        assertEquals(true, backing.data.first()[PRIVILEGE_REVOKE_JOURNAL])

        // 重啟：新實例＋同一不可落盤 marker 路徑＋同一 backing → 僅 journal 能擋下復活。
        val restarted = PrivilegeBridgeStore(
            backing,
            revokeMarker = PendingRevokeMarker(markerFile),
        )
        assertFalse("fresh marker under regular-file parent is not pending", restarted.revokeMarker.isPending())
        assertNull("journal must deny resurrection when the marker cannot persist", restarted.load())
        // 自癒後 backing 乾淨。
        val prefsAfter = backing.data.first()
        assertFalse(prefsAfter.contains(PRIVILEGE_REVOKE_JOURNAL))
        assertFalse(prefsAfter.contains(PRIVILEGE_GRANT_PROVIDER_ID))
    }

    @Test fun saveClearsJournal() = runBlocking {
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "save_clears_journal.preferences_pb")
        }
        val failingDs = FailOnUpdateCallDataStore(backing)
        val markerFile = File(dir, "save_clears_journal.pending")
        val store = PrivilegeBridgeStore(failingDs, revokeMarker = PendingRevokeMarker(markerFile))

        store.save(PrivilegeGrantPolicy.grant("preset:openai", 5L))
        failingDs.failOnCall = 3
        try {
            store.clear()
            fail("clear must throw")
        } catch (_: IOException) {
            // 預期
        }
        assertEquals(true, backing.data.first()[PRIVILEGE_REVOKE_JOURNAL])

        // 重新授權：save 必須清除 journal（新授權取代撤權日誌）。
        val newGrant = PrivilegeGrantPolicy.grant("preset:openai", 6L)
        store.save(newGrant)
        val prefs = backing.data.first()
        assertFalse("save must remove the revoke journal", prefs.contains(PRIVILEGE_REVOKE_JOURNAL))
        assertEquals(newGrant, store.load())
    }

    @Test fun journalWriteFailure_bestEffortWipesKeys_sameProcessAndRestartDeny() = runBlocking {
        // journal 寫入（call #2）即失敗：clear() 盡力擦除 grant 鍵（best-effort，
        // 不刪 journal——journal 本來就沒寫成），仍 rethrow 原始例外；
        // backing 不含 grant 鍵、journal 未落盤、marker 已落盤。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "journal_write_fail.preferences_pb")
        }
        val failingDs = FailOnUpdateCallDataStore(backing)
        val markerFile = File(dir, "journal_write_fail.pending")
        val store = PrivilegeBridgeStore(failingDs, revokeMarker = PendingRevokeMarker(markerFile))

        store.save(PrivilegeGrantPolicy.grant("preset:openai", 5L)) // call #1
        failingDs.failOnCall = 2 // call #2 ＝ clear() 的 journal 寫入
        try {
            store.clear()
            fail("clear must throw when the journal write fails")
        } catch (_: IOException) {
            // 預期：原始例外 rethrow（不得遮蔽）
        }

        val prefs = backing.data.first()
        assertFalse("best-effort wipe must remove grant keys", prefs.contains(PRIVILEGE_GRANT_PROVIDER_ID))
        assertFalse(prefs.contains(PRIVILEGE_GRANT_CONFIG_REVISION))
        assertFalse(prefs.contains(PRIVILEGE_GRANT_AT_MS))
        assertFalse(prefs.contains(PRIVILEGE_GRANT_EXPIRES_AT_MS))
        assertFalse("journal must not be persisted (journal write itself failed)", prefs.contains(PRIVILEGE_REVOKE_JOURNAL))
        assertTrue("marker must still be persisted (markPending ran before journal edit)", markerFile.isFile)

        // 同行程：記憶體 pending 旗標 → load() == null（marker 分支自癒並刪 marker）。
        assertNull(store.load())
        // 重啟實例：marker 已由上一行 load 的 marker 分支 heal 刪除，journal 與鍵
        // 亦已擦除——重啟半段走 never-granted/鍵已擦路徑 → null（絕不復活）。
        val restarted = PrivilegeBridgeStore(backing, revokeMarker = PendingRevokeMarker(markerFile))
        assertNull(restarted.load())
        assertFalse("marker must be gone after marker-branch self-heal", markerFile.exists())
    }

    @Test fun saveAfterCrashedClear_survivesStaleJournalHeal() = runBlocking {
        // TOCTOU 回歸：crash 殘留（journal=true＋舊 grant 鍵）經 save() 復原後，
        // load() 首次讀到 stale 快照（journal=true＋舊鍵）時，heal edit 應對
        // 「最新值」做條件式判斷——journal 已不存在 → no-op → 新 grant 存活。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "save_after_crashed_clear.preferences_pb")
        }
        // 1) Seed 崩潰殘留狀態（journal 落盤後、擦除前被殺）。
        backing.edit { prefs ->
            prefs[PRIVILEGE_GRANT_PROVIDER_ID] = "preset:openai"
            prefs[PRIVILEGE_GRANT_CONFIG_REVISION] = 5L
            prefs[PRIVILEGE_GRANT_AT_MS] = 1L
            prefs[PRIVILEGE_REVOKE_JOURNAL] = true
        }
        val staleSnapshot = backing.data.first()

        // 2) 假件：首次 data 讀取回傳 stale 快照；updateData 一律走最新 backing。
        val staleFirst = object : DataStore<Preferences> by backing {
            private var reads = 0

            override val data: Flow<Preferences> = flow {
                if (reads == 0) {
                    reads += 1
                    emit(staleSnapshot)
                } else {
                    emitAll(backing.data)
                }
            }
        }
        val store = PrivilegeBridgeStore(staleFirst, revokeMarker = PendingRevokeMarker(null))

        // 3) 新授權取代撤權 journal（save 成功：寫新 grant＋清 journal）。
        val newGrant = PrivilegeGrantPolicy.grant("preset:openai", 6L)
        store.save(newGrant)

        // 4) load()：首次讀 stale（journal=true＋舊鍵）→ 條件式 heal →
        //    backing 最新值 journal 已不在 → no-op → fall-through re-read → 新 grant。
        val loaded = store.load()
        assertEquals("stale-journal heal must not wipe the newly saved grant", newGrant, loaded)

        // 5) backing 內新 grant 鍵存活、journal 已清。
        val prefsAfter = backing.data.first()
        assertEquals("preset:openai", prefsAfter[PRIVILEGE_GRANT_PROVIDER_ID])
        assertEquals(6L, prefsAfter[PRIVILEGE_GRANT_CONFIG_REVISION])
        assertFalse(prefsAfter.contains(PRIVILEGE_REVOKE_JOURNAL))
    }

    @Test fun check_withUnhealedJournalPending_returnInvalid() = runBlocking {
        // journal 殘留且自癒被阻擋（updateData 全失敗）：check() 必 Invalid
        // （fail-closed），絕不可因 stale grant 鍵而 Valid。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "check_unhealed_journal.preferences_pb")
        }
        backing.edit { prefs ->
            prefs[PRIVILEGE_GRANT_PROVIDER_ID] = "preset:openai"
            prefs[PRIVILEGE_GRANT_CONFIG_REVISION] = 7L
            prefs[PRIVILEGE_GRANT_AT_MS] = 1L
            prefs[PRIVILEGE_REVOKE_JOURNAL] = true
        }
        val failing = object : DataStore<Preferences> by backing {
            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences,
            ): Preferences = throw IOException("heal blocked")
        }
        // in-memory-only marker：排除 marker 分支，單獨驗證 journal 路徑 fail-closed。
        val store = PrivilegeBridgeStore(failing, revokeMarker = PendingRevokeMarker(null))
        val checked = store.check("preset:openai", 7L)
        assertTrue("$checked", checked is PrivilegeGrantCheck.Invalid)
        assertEquals(
            PrivilegeGrantPolicy.REASON_NEVER_GRANTED,
            (checked as PrivilegeGrantCheck.Invalid).reason,
        )
    }

    @Test fun refreshAndCheck_withPendingMarker_returnInvalid() = runBlocking {
        // marker 落盤成功的情況：refresh 與 check 皆 Invalid（fail-closed）。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "refresh_pending.preferences_pb")
        }
        val markerFile = File(dir, "refresh_pending.pending")
        val store = PrivilegeBridgeStore(backing, revokeMarker = PendingRevokeMarker(markerFile))
        store.save(PrivilegeGrantPolicy.grant("preset:openai", 7L))

        assertTrue("marker must persist to disk", store.revokeMarker.markPending())
        assertTrue(markerFile.isFile)

        val state = PrivilegeBridgeState()
        val refreshed = state.refresh(store, "preset:openai", 7L)
        assertTrue("$refreshed", refreshed is PrivilegeGrantCheck.Invalid)
        assertFalse(state.bridgeGranted)

        // 註：refresh() 已觸發 marker 分支自癒（grant 鍵與 marker 檔皆被擦除），
        // 此時再 check() 讀到的是「從未授權」→ Invalid，但該斷言無法區分
        // 「journal/marker 擋下」vs「已被擦除」，故不納入本測試——
        // check() 的 journal-pending fail-closed 行為由
        // check_withUnhealedJournalPending_returnInvalid 單獨覆蓋。
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
        assertFalse("restarted instance must clear its own marker state", restartedStore.revokeMarker.isPending())
        // #136-B1 P1（isPending OR 語義變更）：原 Store 實例的記憶體 pending 旗標不受
        // 他實例自癒重置（fail-closed）——原 marker 仍視為 pending。
        assertTrue("original in-memory pending flag must survive another instance's self-heal", marker.isPending())

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
