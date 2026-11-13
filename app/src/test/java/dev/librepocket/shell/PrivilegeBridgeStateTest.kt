package dev.librepocket.shell

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S3 提權橋產品態測試（BACKLOG D09，矩陣 §3，Phase 2）：
 * [PrivilegeBridgeState] 三位一體管理：審計表 + `privilege_bridge`
 * 開關 + 橋接授權，及並發 revoke-wins 與時效判定。
 */
class PrivilegeBridgeStateTest {

    private lateinit var dir: File
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        dir = kotlin.io.path.createTempDirectory("privilege-bridge-state-test").toFile()
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

    @Test fun revokeClearsAuditAndDropsBridgeGrant() {
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        repeat(2) { state.audit.record(listOf("id"), "D09-$it", "DENY") }
        assertEquals(2, state.auditCount())

        val cleared = state.revoke()

        assertEquals(2, cleared)
        assertEquals(0, state.auditCount())
        assertTrue(state.audit.countByVerdict().isEmpty())
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
    }

    @Test fun revokeOnEmptyStateIsNoop() {
        val state = PrivilegeBridgeState()
        assertEquals(0, state.revoke())
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
    }

    @Test fun auditCountFlowUpdatesAtomically() {
        val state = PrivilegeBridgeState()
        assertEquals(0, state.auditCountFlow.value)

        state.audit.record(listOf("id"), "t1", "OK")
        assertEquals(1, state.auditCountFlow.value)

        state.audit.record(listOf("id"), "t2", "DENY")
        assertEquals(2, state.auditCountFlow.value)

        state.revoke()
        assertEquals(0, state.auditCountFlow.value)
    }

    @Test fun bridgeEffectiveRequiresBothSwitchAndGrant() {
        val state = PrivilegeBridgeState()
        assertFalse(state.bridgeEffective)

        // switch=true, granted=false -> false
        state.setBridgeSwitch(true)
        assertTrue(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
        assertFalse(state.bridgeEffective)

        // switch=false, granted=true -> false
        val stateWithGrant = PrivilegeBridgeState(bridgeSwitchOn = false, bridgeGranted = true)
        assertFalse(stateWithGrant.bridgeSwitchOn)
        assertTrue(stateWithGrant.bridgeGranted)
        assertFalse(stateWithGrant.bridgeEffective)

        // switch=true, granted=true -> true
        stateWithGrant.setBridgeSwitch(true)
        assertTrue(stateWithGrant.bridgeEffective)

        // revoking drops bridgeEffective to false
        stateWithGrant.revoke()
        assertFalse(stateWithGrant.bridgeEffective)
    }

    @Test fun zeroTtlDoesNotSetBridgeGranted() = runBlocking {
        val store = store()
        val state = PrivilegeBridgeState(bridgeSwitchOn = true)
        val atMs = 1_700_000_000_000L
        val grant = state.grant(store, "preset:openai", 1L, ttlMs = 0L, atMs = atMs)

        assertEquals(atMs, grant.expiresAtMs)
        // Since ttlMs == 0, grant is immediately expired at atMs, so postCheck fails and bridgeGranted is false
        assertFalse("ttl=0 grant must not set bridgeGranted", state.bridgeGranted)
        assertFalse(state.bridgeEffective)
    }

    @Test fun concurrentRevokeWinsDuringGrant() = runBlocking {
        val backing = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "concurrent_grant.preferences_pb") }
        lateinit var stateRef: PrivilegeBridgeState

        // Wrap dataStore so when updateData is called, revoke() is triggered concurrently
        val hookingDataStore = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                // Trigger revoke while grant is saving
                stateRef.revoke()
                return backing.updateData(transform)
            }
        }
        val store = PrivilegeBridgeStore(hookingDataStore)
        val state = PrivilegeBridgeState(bridgeSwitchOn = true)
        stateRef = state

        assertThrows(IllegalStateException::class.java) {
            runBlocking { state.grant(store, "preset:openai", 1L) }
        }

        // Revoke-wins: grant detects epoch change, clears store, and sets bridgeGranted to false
        assertFalse("bridgeGranted must be false when concurrent revoke wins", state.bridgeGranted)
        assertFalse(state.bridgeSwitchOn)
        assertNull("Store must be cleared when concurrent revoke wins during grant", store.load())
    }

    @Test fun concurrentRevokeWinsDuringRefresh() = runBlocking {
        val backing = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "concurrent_refresh.preferences_pb") }
        lateinit var stateRef: PrivilegeBridgeState

        // Wrap dataStore so reading data triggers revoke()
        val hookingDataStore = object : DataStore<Preferences> by backing {
            override val data: Flow<Preferences> = backing.data.map {
                stateRef.revoke()
                it
            }
        }
        val store = PrivilegeBridgeStore(hookingDataStore)
        store.save(PrivilegeGrantPolicy.grant("preset:openai", 1L))

        val state = PrivilegeBridgeState(bridgeSwitchOn = true, bridgeGranted = true)
        stateRef = state

        val check = state.refresh(store, "preset:openai", 1L)

        assertTrue(check is PrivilegeGrantCheck.Invalid)
        assertEquals(PrivilegeGrantPolicy.REASON_NEVER_GRANTED, (check as PrivilegeGrantCheck.Invalid).reason)
        assertFalse("bridgeGranted must be false after concurrent revoke during refresh", state.bridgeGranted)
    }

    @Test fun grantRevokedWhileQueuedOnMutexFailsClosed() = runBlocking {
        val store = store()
        val state = PrivilegeBridgeState(bridgeSwitchOn = true)
        val slowStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "slow.preferences_pb") }

        val lockHoldStarted = java.util.concurrent.CountDownLatch(1)
        val lockHoldRelease = java.util.concurrent.CountDownLatch(1)

        val blockerStore = PrivilegeBridgeStore(object : DataStore<Preferences> by slowStore {
            override val data: Flow<Preferences> = flow {
                lockHoldStarted.countDown()
                lockHoldRelease.await()
                emit(androidx.datastore.preferences.core.emptyPreferences())
            }
        })

        // Launch job holding opMutex via refresh
        val job1 = launch(Dispatchers.IO) {
            state.refresh(blockerStore, "preset:openai", 1L)
        }
        lockHoldStarted.await()

        // Now Coroutine 2 calls grant - it samples epochBefore (0), and suspends waiting for opMutex
        val grantStarted = java.util.concurrent.CountDownLatch(1)
        var grantError: Throwable? = null
        val job2 = launch(Dispatchers.IO) {
            grantStarted.countDown()
            try {
                state.grant(store, "preset:openai", 1L)
            } catch (t: Throwable) {
                grantError = t
            }
        }
        grantStarted.await()
        Thread.sleep(50) // ensure job2 is queued on opMutex

        // User revokes while job2 is queued
        state.revoke()

        // Release job1
        lockHoldRelease.countDown()
        job1.join()
        job2.join()

        assertTrue(grantError is IllegalStateException)
        assertEquals("PRIVILEGE_GRANT_REVOKED_CONCURRENTLY", grantError?.message)
        assertFalse(state.bridgeGranted)
    }

    @Test fun revokeInMemoryForTestMatchesRevoke() {
        val state = PrivilegeBridgeState(bridgeSwitchOn = true, bridgeGranted = true)
        state.audit.record(listOf("id"), "test", "OK")
        assertEquals(1, state.auditCount())

        val cleared = state.revokeInMemoryForTest()
        assertEquals(1, cleared)
        assertEquals(0, state.auditCount())
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
    }

    @Test fun auditLogIsBounded() {
        // MINOR-15：審計表有界，超過上限丟棄最舊。
        val audit = PrivilegeAuditLog()
        repeat(PrivilegeAuditLog.MAX_RECORDS + 10) {
            audit.record(listOf("id"), "D09-$it", "DENY")
        }
        assertEquals(PrivilegeAuditLog.MAX_RECORDS, audit.size())
        val seqIds = audit.snapshot().map { it.seqId }
        assertTrue(seqIds.none { it == "priv-1" })
        assertTrue(seqIds.last() == "priv-${PrivilegeAuditLog.MAX_RECORDS + 10}")
    }
}
