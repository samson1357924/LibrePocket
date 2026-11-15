package dev.librepocket.shell

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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

    /** 走訪 [t] 的 cause／suppressed 鏈（含自身），回傳不重複的例外清單。 */
    private fun throwableChain(t: Throwable): List<Throwable> {
        val out = mutableListOf<Throwable>()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        val queue: ArrayDeque<Throwable> = ArrayDeque()
        queue.add(t)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (!seen.add(cur)) continue
            out.add(cur)
            cur.cause?.let(queue::add)
            cur.suppressed.forEach(queue::add)
        }
        return out
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

        // Wrap dataStore so when updateData is called, revokeInMemoryForTest() is triggered concurrently
        val hookingDataStore = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                // Trigger in-memory revoke while grant is saving
                stateRef.revokeInMemoryForTest()
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
                // @Deprecated 記憶體版 revoke()（語義同 revokeInMemoryForTest()）：
                // 只清記憶體旗標與審計表，盤上 grant 一律不動。
                stateRef.revokeInMemoryForTest()
                it
            }
        }
        val store = PrivilegeBridgeStore(hookingDataStore)
        val grant = PrivilegeGrantPolicy.grant("preset:openai", 1L)
        store.save(grant)

        val state = PrivilegeBridgeState(bridgeSwitchOn = true, bridgeGranted = true)
        stateRef = state

        val check = state.refresh(store, "preset:openai", 1L)

        assertTrue(check is PrivilegeGrantCheck.Invalid)
        assertEquals(PrivilegeGrantPolicy.REASON_NEVER_GRANTED, (check as PrivilegeGrantCheck.Invalid).reason)
        assertFalse("bridgeGranted must be false after concurrent revoke during refresh", state.bridgeGranted)
        // 語義缺口 pin：hookingDataStore 內觸發的是 in-memory 撤權——盤上 grant 未被清。
        // 上面 Invalid(NEVER_GRANTED) 來自 epoch 競態折疊（並發撤銷的保守語義），而非
        // 磁碟已無授權；重新 load 仍讀得到有效 grant。
        // 產品撤權必須走 suspend revoke(store, …) 持久版（marker/journal 防死灰復燃），
        // 只靠記憶體撤權重啟後舊授權可能復活。若把此處改為持久 revoke，下列斷言即不成立——
        // 此分歧正是本測試釘住的 in-memory 語義缺口（僅註解標明，不在本案例修復）。
        assertEquals("In-memory revoke must leave the backing grant untouched on disk", grant, store.load())
    }

    @Test
    fun grantAfterRevokeLandsBeforePublication_doesNotRepublishTrue() = runBlocking {
        // 旗標發布窗口（寫入前一刻）插入外部撤銷：revoke 先落 → epoch 不符 →
        // bridgeGranted 不得被復活為 true（write＋second-read 雙重檢查）。
        // 還原 publishBridgeGranted 的 epoch 雙重檢查「整體」後本測試必紅；
        // 整體刪除 publish 呼叫（旗標停留 true）亦必紅；
        // 僅移除 conjunct（或 second-read 單層留住）時本測試仍綠——conjunct 獨有
        // 窗口無法以單執行緒 seam 判別；second-read 獨層由
        // grantWhenRevokeLandsBetweenWriteAndSecondRead_forcesFalse pin。
        val store = store()
        val state = PrivilegeBridgeState(bridgeSwitchOn = true)
        // 先做一次成功 grant 使旗標進 true：刪除 publish 邏輯時旗標不再被壓回
        // false，本測試必紅（釘住 publish 本身的存在）。
        state.grant(store, "preset:openai", 1L)
        assertTrue("前置：首次成功 grant 後旗標必為 true", state.bridgeGranted)
        state.bridgeGrantedPublishHookForTest = { state.revokeInMemoryForTest() }
        try {
            state.grant(store, "preset:openai", 1L)
        } finally {
            state.bridgeGrantedPublishHookForTest = null
        }
        assertFalse(
            "發布窗口插入撤銷後 bridgeGranted 必不得為 true",
            state.bridgeGranted,
        )
    }

    @Test
    fun refreshAfterRevokeLandsBeforePublication_doesNotRepublishTrue() = runBlocking {
        // 同上，針對 refresh：持久授權有效（check=Valid），但旗標發布窗口被撤銷
        // 搶先 → bridgeGranted 必落 false（即使回傳的 check 仍是 Valid）。
        val store = store()
        val grant = PrivilegeGrantPolicy.grant("preset:openai", 1L)
        store.save(grant)
        val state = PrivilegeBridgeState(bridgeSwitchOn = true, bridgeGranted = true)
        state.bridgeGrantedPublishHookForTest = { state.revokeInMemoryForTest() }
        try {
            state.refresh(store, "preset:openai", 1L)
        } finally {
            state.bridgeGrantedPublishHookForTest = null
        }
        assertFalse(
            "發布窗口插入撤銷後 bridgeGranted 必不得為 true",
            state.bridgeGranted,
        )
    }

    @Test
    fun grantWhenRevokeLandsBetweenWriteAndSecondRead_forcesFalse() = runBlocking {
        // second-read 單層防線的確定性 pin：賦值後、回讀前單獨推進 revokeEpoch
        // （不寫旗標）。若 second-read 被移除，本測試必紅
        // （旗標會停留在賦值寫下的 true，授權復活）。
        val store = store()
        val state = PrivilegeBridgeState(bridgeSwitchOn = true)
        state.bridgeGrantedEpochBumpHookForTest = { state.incrementRevokeEpochOnlyForTest() }
        try {
            state.grant(store, "preset:openai", 1L)
        } finally {
            state.bridgeGrantedEpochBumpHookForTest = null
        }
        assertFalse(
            "賦值後、second-read 前撤銷落地 ⇒ second-read 必把旗標壓回 false",
            state.bridgeGranted,
        )
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
        lockHoldStarted.await(10, java.util.concurrent.TimeUnit.SECONDS)

        // 以 Unconfined 明確驅動：launch 返回時 job2 已執行到 opMutex.lock() 的掛起點
        //（epochBefore 已捕捉且排隊等鎖）——不再靠 Thread.sleep 猜測排程。
        val grantError = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val job2 = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            try {
                state.grant(store, "preset:openai", 1L)
            } catch (t: Throwable) {
                grantError.set(t)
            }
        }
        // launch 返回即代表 job2 已排隊於 opMutex（Mutex 由 job1 持有）。

        // User revokes while job2 is queued
        state.revokeInMemoryForTest()

        // Release job1
        lockHoldRelease.countDown()
        job1.join()
        job2.join()

        val grantFailure = grantError.get()
        assertTrue(
            "grant 必 fail-closed 拋 IllegalStateException，實際=$grantFailure",
            grantFailure is IllegalStateException,
        )
        assertEquals("PRIVILEGE_GRANT_REVOKED_CONCURRENTLY", grantFailure?.message)
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

    @Test fun grantWithTtlElapsingDuringSlowSave_doesNotSetBridgeGranted() = runBlocking {
        // 落庫延遲超過 TTL：postCheck 必以新鮮時鐘判定（不得重用簽發 atMs），
        // 授權在落庫後已過期 → bridgeGranted 不得被置 true。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "ttl_elapse_slow_save.preferences_pb")
        }
        val slowDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                delay(150)
                return backing.updateData(transform)
            }
        }
        val store = PrivilegeBridgeStore(slowDs)
        val state = PrivilegeBridgeState(bridgeSwitchOn = true)
        val atMs = System.currentTimeMillis()

        state.grant(store, "preset:openai", 1L, ttlMs = 100L, atMs = atMs)

        assertFalse("落庫耗時跨過 TTL 後 postCheck 必判失效，不得置 bridgeGranted", state.bridgeGranted)
        assertFalse(state.bridgeEffective)
    }

    @Test fun grantWithFutureAtMs_failsFast() = runBlocking {
        // atMs 晚於當下：fail-fast（AT_MS_IN_FUTURE），防止簽發一個「未來才生效」的授權。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "future_atms.preferences_pb")
        }
        val store = PrivilegeBridgeStore(backing)
        val state = PrivilegeBridgeState(bridgeSwitchOn = true)

        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                state.grant(store, "preset:openai", 1L, ttlMs = 1_000L, atMs = System.currentTimeMillis() + 60_000L)
            }
        }
        assertEquals("AT_MS_IN_FUTURE", error.message)
        assertNull(store.load())
    }

    @Test fun revokeWithStuckDataStore_failsBoundedlyAndMemoryRevoked() = runBlocking {
        // 假 Store 的 clear() 永不返回：revoke 必須在 revokeOpTimeoutMs 量級內
        // fail-closed 拋 IllegalStateException（不得無界掛起），且記憶體旗標已落 false。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "stuck_clear.preferences_pb")
        }
        val stuckDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                while (true) delay(1_000)
            }
        }
        val store = PrivilegeBridgeStore(stuckDs)
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
            revokeOpTimeoutMs = 100L,
        )
        state.audit.record(listOf("id"), "B1-stuck", "DENY")

        val t0 = System.currentTimeMillis()
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { state.revoke(store) }
        }
        val elapsed = System.currentTimeMillis() - t0

        assertEquals("PRIVILEGE_REVOKE_STORE_TIMEOUT", error.message)
        assertTrue("revoke must fail boundedly, took ${elapsed}ms", elapsed < 2_000L)
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
        assertEquals(0, state.auditCount())
    }

    @Test fun revokeWithStuckWithdrawScope_failsBoundedly() = runBlocking {
        // store.clear() 正常，withdrawScope 永不返回：鎖外有界執行，
        // 超時 → IllegalStateException（PRIVILEGE_REVOKE_SCOPE_TIMEOUT），記憶體已收回。
        val store = store()
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
            revokeOpTimeoutMs = 100L,
        )

        val t0 = System.currentTimeMillis()
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                state.revoke(store) { kotlinx.coroutines.delay(Long.MAX_VALUE) }
            }
        }
        val elapsed = System.currentTimeMillis() - t0

        assertEquals("PRIVILEGE_REVOKE_SCOPE_TIMEOUT", error.message)
        assertTrue("revoke must fail boundedly, took ${elapsed}ms", elapsed < 2_000L)
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
        assertNull("store.clear 已正常落庫，持久授權必已撤銷", store.load())
    }

    @Test fun revokeWithStuckMutexAcquisition_failsBoundedly() = runBlocking {
        // opMutex 被 grant 的慢 save 持有：revoke 有界等待鎖，
        // 超時 → IllegalStateException（PRIVILEGE_REVOKE_TIMEOUT），記憶體已收回，呼叫端可重試。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "stuck_mutex.preferences_pb")
        }
        val lockHoldStarted = java.util.concurrent.CountDownLatch(1)
        val slowDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                lockHoldStarted.countDown()
                delay(5_000)
                return backing.updateData(transform)
            }
        }
        val slowStore = PrivilegeBridgeStore(slowDs)
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
            revokeOpTimeoutMs = 100L,
        )
        val grantJob = launch(Dispatchers.IO) {
            // revoke 會使 epoch 推進，grant 落庫後必失敗收場（這裡只關心持鎖窗口）。
            try {
                state.grant(slowStore, "preset:openai", 1L)
            } catch (_: Throwable) {
                // 預期：revoke 搶先 → epoch 變更 → grant 拋 PRIVILEGE_GRANT_REVOKED_CONCURRENTLY
            }
        }
        lockHoldStarted.await(10, java.util.concurrent.TimeUnit.SECONDS)

        val t0 = System.currentTimeMillis()
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { state.revoke(slowStore) }
        }
        val elapsed = System.currentTimeMillis() - t0

        assertEquals("PRIVILEGE_REVOKE_TIMEOUT", error.message)
        assertTrue("revoke must fail boundedly, took ${elapsed}ms", elapsed < 2_000L)
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
        assertEquals(0, state.auditCount())

        grantJob.cancelAndJoin()
    }

    @Test fun revokeWithStuckMutex_bestEffortClearPersists() = runBlocking {
        // grant 落庫以 delay 持 opMutex 慢放（約 300ms、可取消）；revokeOpTimeoutMs=100
        // → opMutex 等待逾時 → PRIVILEGE_REVOKE_TIMEOUT；鎖外 best-effort clear
        //（不延遲）必落地 → store.load() == null；旗標全 false。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "stuck_mutex_best_effort.preferences_pb")
        }
        val lockHoldStarted = java.util.concurrent.CountDownLatch(1)
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val slowDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                // 第一次 updateData 為 grant 落庫：countDown 後延遲約 300ms（可取消）；
                // 後續 clear 路徑的 edit 不延遲，確保鎖外 best-effort clear 可在時限內落地。
                if (saveCalls.getAndIncrement() == 0) {
                    lockHoldStarted.countDown()
                    kotlinx.coroutines.delay(300)
                }
                return backing.updateData(transform)
            }
        }
        val store = PrivilegeBridgeStore(slowDs)
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
            revokeOpTimeoutMs = 100L,
        )
        val grantJob = launch(Dispatchers.IO) {
            try {
                state.grant(store, "preset:openai", 1L)
            } catch (_: Throwable) {
                // 預期：revoke 搶先推進 epoch → grant 拋 PRIVILEGE_GRANT_REVOKED_CONCURRENTLY
            }
        }
        lockHoldStarted.await(10, java.util.concurrent.TimeUnit.SECONDS)

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { state.revoke(store) }
        }
        grantJob.join()

        assertEquals("PRIVILEGE_REVOKE_TIMEOUT", error.message)
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
        assertNull("鎖外 best-effort clear 已落地，持久授權必已撤銷", store.load())
    }

    @Test fun revokeMutexTimeout_bestEffortClearFailsFast_suppressedClearFailed() = runBlocking {
        // grant 落庫以 delay 持 opMutex（> revokeOpTimeoutMs=100）；clear()（含臨界區內
        // 與 best-effort）路徑的 updateData 直接拋 IOException（不 hang）。
        // → opMutex 等待逾時 → PRIVILEGE_REVOKE_TIMEOUT；鎖外 best-effort clear 因
        // IOException 快速失敗 → suppressed ISE(PRIVILEGE_REVOKE_BEST_EFFORT_CLEAR_FAILED)，
        // 其 cause 為該 IOException；例外鏈上不得含 CancellationException。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "stuck_mutex_best_effort_clear_fails.preferences_pb")
        }
        val lockHoldStarted = java.util.concurrent.CountDownLatch(1)
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val ioFailure = java.io.IOException("simulated clear IO failure")
        val failingDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                // 第一次 updateData 為 grant 落庫：countDown 後延遲約 300ms（可取消）；
                // clear 路徑的 edit 直接拋 IOException（不 hang）。
                if (saveCalls.getAndIncrement() == 0) {
                    lockHoldStarted.countDown()
                    kotlinx.coroutines.delay(300)
                    return backing.updateData(transform)
                }
                throw ioFailure
            }
        }
        val store = PrivilegeBridgeStore(failingDs)
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
            revokeOpTimeoutMs = 100L,
        )
        val grantJob = launch(Dispatchers.IO) {
            try {
                state.grant(store, "preset:openai", 1L)
            } catch (_: Throwable) {
                // 預期：revoke 搶先推進 epoch → grant 拋 PRIVILEGE_GRANT_REVOKED_CONCURRENTLY
            }
        }
        lockHoldStarted.await(10, java.util.concurrent.TimeUnit.SECONDS)

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { state.revoke(store) }
        }
        grantJob.join()

        assertEquals("PRIVILEGE_REVOKE_TIMEOUT", error.message)
        // kotlinx 除錯模式會在 coroutine 邊界複製異常（suppressed 落在複製體的
        // cause 鏈上）；統一走訪 cause／suppressed 鏈，兩種模式皆適用。
        val chain = throwableChain(error)

        assertTrue(
            "例外鏈上必含 IllegalStateException(PRIVILEGE_REVOKE_BEST_EFFORT_CLEAR_FAILED)，實際=$chain",
            chain.any {
                it is IllegalStateException &&
                    it.message == "PRIVILEGE_REVOKE_BEST_EFFORT_CLEAR_FAILED" &&
                    // kotlinx 除錯模式會複製異常：cause 是 ioFailure 的複本，以型別＋訊息比對
                    it.cause is java.io.IOException &&
                    it.cause?.message == "simulated clear IO failure"
            },
        )
        assertTrue(
            "例外鏈上必含該 IOException cause，實際=$chain",
            chain.any { it is java.io.IOException && it.message == "simulated clear IO failure" },
        )
        assertFalse("revoke 例外不得為 CancellationException", error is CancellationException)
        assertTrue(
            "例外鏈上不得含 CancellationException，實際=$chain",
            chain.none { it is CancellationException },
        )
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
    }

    @Test fun revokeMutexTimeout_bestEffortClearTimesOut_suppressedClearTimeout() = runBlocking {
        // 同上，但 clear() 路徑的 updateData 為無限 delay（可取消 hang）：
        // best-effort clear 有界（revokeOpTimeoutMs=100）→ 逾時 → suppressed
        // ISE(PRIVILEGE_REVOKE_BEST_EFFORT_CLEAR_TIMEOUT)；例外鏈上不得含 CancellationException。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "stuck_mutex_best_effort_clear_timeout.preferences_pb")
        }
        val lockHoldStarted = java.util.concurrent.CountDownLatch(1)
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val hangingDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                // 第一次 updateData 為 grant 落庫：countDown 後延遲約 300ms（可取消）；
                // clear 路徑的 edit 掛起（while delay，可取消），由 best-effort 逾時取消。
                if (saveCalls.getAndIncrement() == 0) {
                    lockHoldStarted.countDown()
                    kotlinx.coroutines.delay(300)
                    return backing.updateData(transform)
                }
                while (true) kotlinx.coroutines.delay(1_000)
            }
        }
        val store = PrivilegeBridgeStore(hangingDs)
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
            revokeOpTimeoutMs = 100L,
        )
        val grantJob = launch(Dispatchers.IO) {
            try {
                state.grant(store, "preset:openai", 1L)
            } catch (_: Throwable) {
                // 預期：revoke 搶先推進 epoch → grant 拋 PRIVILEGE_GRANT_REVOKED_CONCURRENTLY
            }
        }
        lockHoldStarted.await(10, java.util.concurrent.TimeUnit.SECONDS)

        val t0 = System.currentTimeMillis()
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { state.revoke(store) }
        }
        val elapsed = System.currentTimeMillis() - t0
        // grant 落庫後的 epilogue 會 best-effort 再 clear() 一次（同一個無限 hang 假件），
        // 該路徑無界；以取消代 join，避免 grantJob 永久掛起。
        grantJob.cancelAndJoin()

        assertEquals("PRIVILEGE_REVOKE_TIMEOUT", error.message)
        assertTrue("revoke must fail boundedly, took ${elapsed}ms", elapsed < 5_000L)
        // kotlinx 除錯模式會在 coroutine 邊界複製異常（suppressed 落在複製體的
        // cause 鏈上）；統一走訪 cause／suppressed 鏈，兩種模式皆適用。
        val chain = throwableChain(error)

        assertTrue(
            "例外鏈上必含 IllegalStateException(PRIVILEGE_REVOKE_BEST_EFFORT_CLEAR_TIMEOUT)，實際=$chain",
            chain.any { it is IllegalStateException && it.message == "PRIVILEGE_REVOKE_BEST_EFFORT_CLEAR_TIMEOUT" },
        )
        assertTrue(
            "例外鏈上不得含 CancellationException，實際=$chain",
            chain.none { it is CancellationException },
        )
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
    }

    @Test fun revokeRetry_afterTimeout_succeeds() = runBlocking {
        // 假 Store 的 clear 第一次永久掛起（可取消）：revoke 逾時失敗；
        // 同一 store 放開後重試 → 成功（無例外、journal 已清、store.load()==null、
        // 旗標 false、cleared == 2）。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "retry_after_timeout.preferences_pb")
        }
        val hangClear = java.util.concurrent.atomic.AtomicBoolean(false)
        val flakyDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                if (hangClear.getAndSet(false)) {
                    while (true) kotlinx.coroutines.delay(1_000)
                }
                return backing.updateData(transform)
            }
        }
        val store = PrivilegeBridgeStore(flakyDs)
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            revokeOpTimeoutMs = 100L,
        )
        state.grant(store, "preset:openai", 1L)
        assertTrue(state.bridgeGranted)
        state.audit.record(listOf("id"), "B1-retry-1", "DENY")

        hangClear.set(true)
        val firstError = assertThrows(IllegalStateException::class.java) {
            runBlocking { state.revoke(store) }
        }
        assertEquals("PRIVILEGE_REVOKE_STORE_TIMEOUT", firstError.message)

        // 注意：第一次逾時 revoke 在頂部即已清空審計表（revokeAll 早於鎖等待，
        // cleared=1 隨例外丟棄）；此處補兩筆，重試時 cleared 必為 2。
        state.audit.record(listOf("id"), "B1-retry-2", "OK")
        state.audit.record(listOf("id"), "B1-retry-3", "DENY")
        val cleared = state.revoke(store)

        assertEquals("重試成功時 cleared 必為 2（僅計算首次逾時後新增筆數）", 2, cleared)
        assertNull("journal 已清／持久授權已撤銷", store.load())
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
    }

    @Test fun revokeStoreClearCancellation_propagates() = runBlocking {
        // store.clear() 拋 CancellationException → revoke 原樣 rethrow CE
        //（非 IllegalStateException、非吞）。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "ce_clear.preferences_pb")
        }
        val ceStore = PrivilegeBridgeStore(object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                throw CancellationException("simulated store cancellation")
            }
        })
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        state.audit.record(listOf("id"), "B1-ce", "DENY")

        val error = assertThrows(CancellationException::class.java) {
            runBlocking { state.revoke(ceStore) }
        }
        assertEquals("simulated store cancellation", error.message)
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
    }

    @Test fun revokeStoreClearCancellation_withCompletingWithdrawScope_cancellationPropagates() = runBlocking {
        // store.clear() 拋 CancellationException＋withdrawScope 正常返回 →
        // CE 原樣上拋（非 IllegalStateException、非包裝、非 ISE 折疊）。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "ce_clear_scope_ok.preferences_pb")
        }
        val ceStore = PrivilegeBridgeStore(object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                throw CancellationException("simulated store cancellation")
            }
        })
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        state.audit.record(listOf("id"), "B1-ce-scope-ok", "DENY")

        var scopeRan = false
        val error = assertThrows(CancellationException::class.java) {
            runBlocking {
                state.revoke(ceStore) { scopeRan = true }
            }
        }
        assertEquals("simulated store cancellation", error.message)
        assertTrue("store 失敗時 withdrawScope 仍應執行（盡力撤銷）", scopeRan)
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
    }

    @Test fun revokeStoreClearRuntimeFailure_withWithdrawScopeIllegalState_primaryIsRuntimeFailure() = runBlocking {
        // store.clear() 拋 RuntimeException＋withdrawScope 拋 IllegalStateException →
        // 主失敗為該 RuntimeException（不包裝、不折成 ISE、非 CE）。
        // scope 例外的 suppressed 附加語義由 revokeStoreTimeout_withWithdrawScopeIllegalState_scopeExceptionAsSuppressed 釘。
        val scopeBoom = IllegalStateException("simulated scope boom")
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "runtime_clear_scope_ise.preferences_pb")
        }
        val failingStore = PrivilegeBridgeStore(object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                throw RuntimeException("simulated clear boom")
            }
        })
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        state.audit.record(listOf("id"), "B1-runtime-scope-ise", "DENY")

        var scopeRan = false
        val error = assertThrows(RuntimeException::class.java) {
            runBlocking {
                state.revoke(failingStore) { scopeRan = true; throw scopeBoom }
            }
        }
        assertEquals("simulated clear boom", error.message)
        assertTrue("主失敗必為原始 RuntimeException，不得被包裝成 ISE", error !is IllegalStateException)
        assertTrue("主失敗不得為 CancellationException", error !is CancellationException)
        assertTrue("withdrawScope 仍應執行", scopeRan)
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
        // 註（kotlinx-coroutines debug 例外複製）：本環境的測試 runner 會複製跨
        // coroutine「失敗交付」邊界的例外（DataStore actor→呼叫端），複製體丟棄
        // suppressed、並把 cause 重指向原例外的 cause——因此 store 端送達的
        // RuntimeException 作為主失敗時，scope 例外附加於其上的 suppressed 不在
        // 測試可觀測的例外圖上。revoke() 在拋出前把 scope 例外附加到主失敗
        // （非 CE 合併分支）的行為，由 revokeStoreTimeout_withWithdrawScopeIllegalState_scopeExceptionAsSuppressed
        // （主失敗為 revoke() 內部建立、suppressed 合併於 revoke() 內完成、早於
        // 任何 DataStore actor→awaiter 邊界的 ISE）確定性 pin。
    }

    @Test fun revokeStoreTimeout_withWithdrawScopeIllegalState_scopeExceptionAsSuppressed() = runBlocking {
        // store.clear 永久 hang＋withdrawScope 拋 IllegalStateException（非逾時）→
        // 主失敗為 revoke() 內部建立的 ISE(PRIVILEGE_REVOKE_STORE_TIMEOUT)：
        // ISE 建立與 suppressed 合併皆發生在任何 DataStore actor→awaiter
        // 邊界之前（withdrawScope 拋出 → catch → addSuppressed，全在 revoke() 內）。
        // 但本 runtime 跨 withContext(NonCancellable) 時異常被複製（probe 實證例外圖：
        // 交付的複製體 suppressed=[]，帶 suppressed 的原件位於 cause 鏈），
        // 故以「原件」（cause 鏈上第一件 suppressed 非空者）斷言 scope 例外——
        // 仍保留 suppressed-vs-cause 的判別力（斷言落在原件 suppressed 槽位，
        // 而非鏈上任意匹配）。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "store_timeout_scope_ise.preferences_pb")
        }
        val stuckDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                while (true) kotlinx.coroutines.delay(1_000)
            }
        }
        val store = PrivilegeBridgeStore(stuckDs)
        val scopeBoom = IllegalStateException("simulated scope boom")
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
            revokeOpTimeoutMs = 100L,
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                state.revoke(store) { throw scopeBoom }
            }
        }
        assertEquals("PRIVILEGE_REVOKE_STORE_TIMEOUT", error.message)
        // 原件定位：交付的複製體 suppressed=[]，原件（第一件 suppressed 非空者）帶真值。
        // 以 throwableChain（帶 seen-set，cycle-safe）定位，不用裸 generateSequence。
        val original = throwableChain(error as Throwable).firstOrNull { it.suppressed.isNotEmpty() }
        assertTrue(
            "例外圖上必能定位帶 suppressed 的原件，實際 root suppressed=${error.suppressed.toList()}",
            original != null,
        )
        val primary = original!!
        assertEquals(
            "帶 suppressed 的原件必為 revoke() 內部建立的 PRIVILEGE_REVOKE_STORE_TIMEOUT 主失敗",
            "PRIVILEGE_REVOKE_STORE_TIMEOUT",
            primary.message,
        )
        assertTrue(
            "scope 的 IllegalStateException 必以 suppressed 附加（原件上），實際 suppressed=${primary.suppressed.toList()}",
            primary.suppressed.any { it.message == "simulated scope boom" },
        )
        assertTrue(
            "原件 suppressed 不得含 CancellationException，實際 suppressed=${primary.suppressed.toList()}",
            primary.suppressed.none { it is CancellationException },
        )
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
    }

    @Test fun revokeStoreOk_withWithdrawScopeCancellation_cancellationPropagates() = runBlocking {
        // store 正常＋withdrawScope 拋 CancellationException → CE 原樣上拋。
        val store = store()
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        state.audit.record(listOf("id"), "B1-store-ok-scope-ce", "DENY")

        val error = assertThrows(CancellationException::class.java) {
            runBlocking {
                state.revoke(store) { throw CancellationException("scope cancelled") }
            }
        }
        assertEquals("scope cancelled", error.message)
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
        assertNull("store.clear 已落地，持久授權必已撤銷", store.load())
    }

    @Test fun revokeStoreOk_withWithdrawScopeIllegalState_scopeExceptionPropagates() = runBlocking {
        // store 正常＋withdrawScope 拋 IllegalStateException → 該 scope 例外
        //（或 kotlinx-debug 複本）原樣上拋——非包裝、非折疊、非 CE。
        val store = store()
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        state.audit.record(listOf("id"), "B1-store-ok-scope-ise", "DENY")
        val scopeBoom = IllegalStateException("scope boom")

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                state.revoke(store) { throw scopeBoom }
            }
        }
        // 註（kotlinx-coroutines debug 例外複製）：scope 拋出的例外在到達 revoke()
        // 的 catch 時即被複製（withTimeoutOrNull 子協程失敗交付邊界），跨出
        // withContext(NonCancellable) 時再次被複製；單一實例語義在此 runtime 不可觀測。
        // 此處 pin：例外型別＋訊息（非包裝、非折疊、非 CE）。
        assertEquals("scope boom", error.message)
        assertTrue("不得為 CancellationException", error !is CancellationException)
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
        assertNull("store.clear 已落地，持久授權必已撤銷", store.load())
    }

    @Test fun revokeStoreAndWithdrawTimeout_mergedFailure_suppressedScopeTimeout() = runBlocking {
        // store.clear 永久 hang＋withdrawScope 永久 hang：
        // primaryFailure＝ISE(PRIVILEGE_REVOKE_STORE_TIMEOUT)；
        // suppressed 必含 IllegalStateException("PRIVILEGE_REVOKE_SCOPE_TIMEOUT")（非 CE）。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "clear_and_scope_timeout.preferences_pb")
        }
        val stuckDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                while (true) kotlinx.coroutines.delay(1_000)
            }
        }
        val store = PrivilegeBridgeStore(stuckDs)
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
            revokeOpTimeoutMs = 100L,
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                state.revoke(store) { kotlinx.coroutines.delay(Long.MAX_VALUE) }
            }
        }

        // kotlinx 除錯模式會在 coroutine 邊界複製異常（suppressed 落在複製體的
        // cause 鏈上）；統一走訪 cause／suppressed 鏈，兩種模式皆適用。
        val chain = throwableChain(error)

        assertEquals("PRIVILEGE_REVOKE_STORE_TIMEOUT", error.message)
        assertTrue(
            "例外鏈上必含 IllegalStateException(PRIVILEGE_REVOKE_SCOPE_TIMEOUT)，實際=$chain",
            chain.any { it is IllegalStateException && it.message == "PRIVILEGE_REVOKE_SCOPE_TIMEOUT" },
        )
        assertTrue(
            "例外鏈上不得含 CancellationException，實際=$chain",
            chain.none { it is CancellationException },
        )
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
