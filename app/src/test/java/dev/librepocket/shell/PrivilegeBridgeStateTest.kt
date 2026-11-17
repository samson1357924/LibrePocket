package dev.librepocket.shell

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        // second-read 單層防線的確定性 pin：賦值後、回讀前單獨推進 revoke epoch（grantFence 內）
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

    @Test(timeout = 30_000L) fun grantRevokedWhileQueuedOnMutexFailsClosed() = runBlocking {
        val store = store()
        val state = PrivilegeBridgeState(bridgeSwitchOn = true)
        val slowStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "slow.preferences_pb") }

        val lockHoldStarted = java.util.concurrent.CountDownLatch(1)
        val lockHoldRelease = java.util.concurrent.CountDownLatch(1)

        val blockerStore = PrivilegeBridgeStore(object : DataStore<Preferences> by slowStore {
            override val data: Flow<Preferences> = flow {
                lockHoldStarted.countDown()
                lockHoldRelease.await(10, TimeUnit.SECONDS)
                emit(androidx.datastore.preferences.core.emptyPreferences())
            }
        })

        // Launch job holding opMutex via refresh
        val job1 = launch(Dispatchers.IO) {
            state.refresh(blockerStore, "preset:openai", 1L)
        }
        assertTrue(lockHoldStarted.await(10, TimeUnit.SECONDS))

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

    @Test fun grantAtExactlyNow_accepted() = runBlocking {
        // atMs == 當下毫秒：fail-fast 判準為 atMs > now（require），等於邊界必須被接受
        // （若誤收成 atMs >= now 才拒會誤拒合法授權；毫秒級精度足以釘住邊界）。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "atms_equal_now.preferences_pb")
        }
        val store = PrivilegeBridgeStore(backing)
        val state = PrivilegeBridgeState(bridgeSwitchOn = true)
        val atMs = System.currentTimeMillis()

        state.grant(store, "preset:openai", 1L, ttlMs = null, atMs = atMs)

        assertTrue("atMs == now 必為合法授權，旗標必為 true", state.bridgeGranted)
        assertEquals(atMs, store.load()?.grantedAtMs)
    }

    @Test fun failedSecondGrant_preservesPreviousBridgeGranted() = runBlocking {
        // 首次授權成功（旗標 true、持久庫為第一份授權）；第二次授權落庫失敗 →
        // 例外上拋、旗標保持 true：「重新授權失敗」不得回溯作廢首份授權的記憶體
        // 授權（fail-closed 僅施加於撤權側／落庫後 policy 判定，不施加於再簽發側；
        // 持久庫亦仍為首份授權，與記憶體旗標一致）。此為語義 pin，非 tautology。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "failed_re_grant.preferences_pb")
        }
        // fail-once-per-call 假件（同檔既有模式）：第一次 updateData（首次授權落庫）
        // 走真實 DataStore；之後（第二次 grant 的落庫）直接拋 IOException。
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val failingDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                if (saveCalls.getAndIncrement() >= 1) {
                    throw java.io.IOException("simulated re-grant save failure")
                }
                return backing.updateData(transform)
            }
        }
        val store = PrivilegeBridgeStore(failingDs)
        val state = PrivilegeBridgeState(bridgeSwitchOn = true)
        val atMs = System.currentTimeMillis()

        val first = state.grant(store, "preset:openai", 1L, ttlMs = null, atMs = atMs)
        assertTrue("首次授權必置旗標", state.bridgeGranted)

        val error = assertThrows(java.io.IOException::class.java) {
            runBlocking {
                state.grant(store, "preset:openai", 1L, ttlMs = null, atMs = System.currentTimeMillis())
            }
        }
        assertEquals("simulated re-grant save failure", error.message)
        assertTrue("重新授權失敗不得回溯作廢首份授權", state.bridgeGranted)
        assertEquals("持久庫仍為首份授權（再簽發未落庫）", first, store.load())
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
        assertTrue(lockHoldStarted.await(10, TimeUnit.SECONDS))

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

    @Test(timeout = 30_000L) fun revokeWithStuckMutex_bestEffortClearPersists() = runBlocking {
        // grant 落庫以 latch 持 opMutex（lockHoldRelease，grant 卡在 updateData 至 revoke 逾時）；revokeOpTimeoutMs=100
        // → opMutex 等待逾時 → PRIVILEGE_REVOKE_TIMEOUT；revoke 的鎖外 best-effort clear
        // 因 DataStore actor 被 grant 佔用以 critical section 被佔 >100ms，故逾時
        //（suppressed `PRIVILEGE_REVOKE_BEST_EFFORT_CLEAR_TIMEOUT`）；持久擦除由
        // grant 落庫後的有界 epilogue compensation clear（Item A）完成，故 store.load() == null；
        // 旗標全 false。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "stuck_mutex_best_effort.preferences_pb")
        }
        val lockHoldStarted = java.util.concurrent.CountDownLatch(1)
        val lockHoldRelease = java.util.concurrent.CountDownLatch(1)
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val slowDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                // 第一次 updateData 為 grant 落庫：countDown 後掛住（latch-gated，
                // 帶 10s 上限，絕不裸 await）直到測試於 revoke 逾時後放行；
                // 後續 clear 路徑的 edit 不延遲，讓 grant 落庫後的有界 epilogue compensation
                // clear 可在時限內落地（鎖外 best-effort clear 因 DataStore actor 被佔而逾時）。
                if (saveCalls.getAndIncrement() == 0) {
                    lockHoldStarted.countDown()
                    lockHoldRelease.await(10, TimeUnit.SECONDS)
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
        assertTrue("grant 落庫必已進入臨界區", lockHoldStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))

        val error = try {
            assertThrows(IllegalStateException::class.java) {
                runBlocking { state.revoke(store) }
            }
        } finally {
            // assertThrows 返回（revoke 於 revokeOpTimeoutMs=100 逾時後拋出）即放行 grant 落庫，
            // 讓 grantJob 得以完成；finally 保證放行。
            lockHoldRelease.countDown()
        }
        grantJob.join()

        assertEquals("PRIVILEGE_REVOKE_TIMEOUT", error.message)
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
        assertNull("持久擦除由 grant epilogue compensation 有界完成（鎖外 best-effort clear 逾時），持久授權必已撤銷", store.load())
    }

    @Test(timeout = 30_000L) fun revokeMutexTimeout_thenMutexBecomesUsableForNextGrant() = runBlocking {
        // 回歸釘住（Re-review #7 Finding 1，P1/B1）：opMutex 取得路徑不得洩漏鎖持有。
        // 舊路徑 withTimeoutOrNull { opMutex.lock(); true }：逾時事件可於 lock() 成功後、
        // 結果移交前抵達 → acquired=null → 逾時分支不 unlock → opMutex 永久被佔，
        // 後續 grant()／refresh()（opMutex 等待無 timeout）即永久吊掛。
        // 釘住性質 = 可用性不變量：逾時 revoke 返回後 opMutex 必須可再取得。
        // 注意：本測試的交錯（grant 全程持鎖）下舊實作並不會觸發該微競態——
        // acquired-but-discarded 需要 mutex 在 deadline 瞬間恰為可取，屬調度相依的
        // 納秒尺度窗口，無法以 latch 交錯確定性重現；真正的防線是取得路徑改為
        // 非暫停 tryLock 輪詢（成功即同步可知，不存在「取了但結果被逾時丟棄」）。
        // 本測試作為可用性 invariant 的回歸釘（註：此案為 availability-invariant 釘測，不判別舊新實作競態窗口）。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "mutex_timeout_then_grant.preferences_pb")
        }
        val lockHoldStarted = java.util.concurrent.CountDownLatch(1)
        val lockHoldRelease = java.util.concurrent.CountDownLatch(1)
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val slowDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                // 第一次 updateData 為 grant 落庫：countDown 後掛住（latch-gated，
                // 帶 10s 上限，絕不裸 await）直到測試於 revoke 逾時後放行；
                // 後續呼叫不延遲。
                if (saveCalls.getAndIncrement() == 0) {
                    lockHoldStarted.countDown()
                    lockHoldRelease.await(10, TimeUnit.SECONDS)
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
                // 預期：revoke 推進 epoch → grant 收場拋 PRIVILEGE_GRANT_REVOKED_CONCURRENTLY
            }
        }
        assertTrue("grant 落庫必已進入臨界區", lockHoldStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))

        val error = try {
            assertThrows(IllegalStateException::class.java) {
                runBlocking { state.revoke(store) }
            }
        } finally {
            // assertThrows 返回（revoke 於 revokeOpTimeoutMs=100 逾時後拋出）即放行 grant 落庫；
            // finally 保證放行。
            lockHoldRelease.countDown()
        }
        grantJob.join()

        assertEquals("PRIVILEGE_REVOKE_TIMEOUT", error.message)
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
        assertTrue("逾時 revoke 入口必已封存 seal（sticky，明確新授權解除）", state.isRevocationSealedForTest())

        // 逾時路徑不得洩漏 opMutex：同一 state 的新 grant 必能取得 opMutex 並完成
        //（seal 由此明確新授權 commit 解封）。
        val issued = state.grant(store, "preset:openai", 1L)
        assertEquals("preset:openai", issued.providerId)
        assertTrue("逾時後 grant 必能完成並置 true（opMutex 未被洩漏佔用）", state.bridgeGranted)
        assertFalse("新 grant commit 必解封 seal", state.isRevocationSealedForTest())
    }

    @Test(timeout = 30_000L) fun revokeAcquiresMutexFreely_completesAndNextGrantProceeds() = runBlocking {
        // 釘住 opMutex unlock 契約：無競爭的 revoke 直接取得 opMutex，臨界區 finally 必須
        // unlock；若 finally 的 unlock 被移除，revoke 自身仍正常返回（其殘留持鎖無人內部偵測），
        // 但後續 grant（opMutex 等待無 timeout）會永久吊掛 → @Test 30s 上限判紅。
        // 註：此案為 availability-invariant 釘測，不判別舊新實作競態窗口。
        val store = PrivilegeBridgeStore(
            PreferenceDataStoreFactory.create(scope = scope) {
                File(dir, "revoke_acquires_mutex_freely.preferences_pb")
            },
        )
        val state = PrivilegeBridgeState()

        // 無競爭 revoke：必正常返回（不得以 unlock-guessing 或逾時路徑打轉），旗標與授權落 false。
        state.revoke(store)
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)

        // opMutex 必已於 revoke 臨界區 finally 釋放：新 grant 取得 opMutex 並完成（30s @Test 上限封頂）。
        val issued = state.grant(store, "preset:openai", 1L)
        assertEquals("preset:openai", issued.providerId)
        assertTrue("revoke 釋放後 grant 必能完成並置 true（opMutex 未被洩漏佔用）", state.bridgeGranted)
    }

    @Test(timeout = 30_000L) fun revokeMutexTimeout_bestEffortClearFailsFast_suppressedClearFailed() = runBlocking {
        // grant 落庫以 latch 持 opMutex（lockHoldRelease，grant 卡在 updateData 至 revoke 逾時）；clear()（含臨界區內
        // 與 best-effort）路徑的 updateData 直接拋 IOException（不 hang）。
        // → opMutex 等待逾時 → PRIVILEGE_REVOKE_TIMEOUT；鎖外 best-effort clear 因
        // IOException 快速失敗 → suppressed ISE(PRIVILEGE_REVOKE_BEST_EFFORT_CLEAR_FAILED)，
        // 其 cause 為該 IOException；例外鏈上不得含 CancellationException。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "stuck_mutex_best_effort_clear_fails.preferences_pb")
        }
        val lockHoldStarted = java.util.concurrent.CountDownLatch(1)
        val lockHoldRelease = java.util.concurrent.CountDownLatch(1)
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val ioFailure = java.io.IOException("simulated clear IO failure")
        val failingDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                // 第一次 updateData 為 grant 落庫：countDown 後掛住（latch-gated，
                // 帶 10s 上限，絕不裸 await）直到測試於 revoke 逾時後放行；
                // clear 路徑的 edit 直接拋 IOException（不 hang）。
                if (saveCalls.getAndIncrement() == 0) {
                    lockHoldStarted.countDown()
                    lockHoldRelease.await(10, TimeUnit.SECONDS)
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
        assertTrue("grant 落庫必已進入臨界區", lockHoldStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))

        val error = try {
            assertThrows(IllegalStateException::class.java) {
                runBlocking { state.revoke(store) }
            }
        } finally {
            // assertThrows 返回（revoke 於 revokeOpTimeoutMs=100 逾時後拋出）即放行 grant 落庫，
            // 讓 grantJob 得以完成；finally 保證放行。
            lockHoldRelease.countDown()
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

    @Test(timeout = 30_000L) fun revokeMutexTimeout_bestEffortClearVirtualMachineError_propagatesFatal() = runBlocking {
        // 鎖外 best-effort clear 腿（第二腿）拋 VirtualMachineError：
        // fatal 不得淪為 suppressed（uniform fatal precedence；Stage 7B 第四站點收齊）——
        // grant 落庫以 latch 持 opMutex（lockHoldRelease，grant 卡在 updateData 至 revoke 逾時），
        // revokeOpTimeoutMs=100 → opMutex 等待逾時 → 主失敗為 PRIVILEGE_REVOKE_TIMEOUT ISE；
        // 鎖外 best-effort clear 的第二次起 updateData 直接拋 OutOfMemoryError（不 hang）→
        // 補償腿 fatal 成為終端外傳例外，原 ISE 反向附加為其 suppressed。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "stuck_mutex_best_effort_clear_oom.preferences_pb")
        }
        val lockHoldStarted = java.util.concurrent.CountDownLatch(1)
        val lockHoldRelease = java.util.concurrent.CountDownLatch(1)
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val fatal = OutOfMemoryError("simulated best-effort clear OOM")
        val oomDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                // 第一次 updateData 為 grant 落庫：countDown 後掛住（latch-gated，
                // 帶 10s 上限，絕不裸 await）直到測試於 revoke 逾時後放行；
                // clear 路徑的 edit 直接拋 OutOfMemoryError（不 hang）。
                if (saveCalls.getAndIncrement() == 0) {
                    lockHoldStarted.countDown()
                    lockHoldRelease.await(10, TimeUnit.SECONDS)
                    return backing.updateData(transform)
                }
                throw fatal
            }
        }
        val store = PrivilegeBridgeStore(oomDs)
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
        assertTrue("grant 落庫必已進入臨界區", lockHoldStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))

        val error = try {
            assertThrows(OutOfMemoryError::class.java) {
                runBlocking { state.revoke(store) }
            }
        } finally {
            // assertThrows 返回（revoke 於 revokeOpTimeoutMs=100 逾時後拋出）即放行 grant 落庫，
            // 讓 grantJob 得以完成；finally 保證放行。
            lockHoldRelease.countDown()
        }
        grantJob.join()

        // 註（邊界複製 caveat，實測）：本 runtime 跨 withContext(NonCancellable) 時異常被複製，
        // 交付的複製體 suppressed=[]，鏈上僅見 [OOM, OOM] 兩件、suppressed 皆空——
        // 反向附加的 ISE(PRIVILEGE_REVOKE_TIMEOUT) 在呼叫端不可觀測（同
        // revokeStoreClearIoExceptionThenWithdrawScopeOutOfMemory_propagatesFatal 的紀錄）。
        // 不 fake 該斷言；此處 pin：type=OOM（不是包裝後的 ISE——未修復時此處必紅）
        // ＋message 原樣＋旗標 false。
        assertEquals("simulated best-effort clear OOM", error.message)
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
        val lockHoldRelease = java.util.concurrent.CountDownLatch(1)
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val hangingDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                // 第一次 updateData 為 grant 落庫：countDown 後掛住（latch-gated，
                // 帶 10s 上限，絕不裸 await）直到測試於 revoke 逾時後放行；
                // clear 路徑的 edit 掛起（while delay，可取消），由 best-effort 逾時取消。
                if (saveCalls.getAndIncrement() == 0) {
                    lockHoldStarted.countDown()
                    lockHoldRelease.await(10, TimeUnit.SECONDS)
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
        assertTrue("grant 落庫必已進入臨界區", lockHoldStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))

        val t0 = System.currentTimeMillis()
        val error = try {
            assertThrows(IllegalStateException::class.java) {
                runBlocking { state.revoke(store) }
            }
        } finally {
            // assertThrows 返回（revoke 於 revokeOpTimeoutMs=100 逾時後拋出）即放行 grant 落庫；
            // finally 保證放行。
            lockHoldRelease.countDown()
        }
        val elapsed = System.currentTimeMillis() - t0
        // grant 落庫後的 epilogue 會 best-effort 再 clear() 一次（同一個無限 hang 假件），
        // 該路徑現由 revokeOpTimeoutMs 有界（含補償清庫有界化）；
        // 以取消代 join 仍安全且對取消語義無影響。
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

    @Test fun revokeStoreClearCancellation_withWithdrawScopeCancellation_bothCatastrophic() = runBlocking {
        // store.clear() 拋 CE＋withdrawScope 拋 CE → CE 原樣上拋（非包裝、非 ISE）；
        // scope 的 CE 以 suppressed 併入主失敗（primary = store 的 CE）。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "ce_ce.preferences_pb")
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
        state.audit.record(listOf("id"), "B1-ce-ce", "DENY")

        val scopeBoom = CancellationException("simulated scope cancellation")
        val error = assertThrows(CancellationException::class.java) {
            runBlocking { state.revoke(ceStore) { throw scopeBoom } }
        }
        assertEquals("主失敗必為 store 的 CE", "simulated store cancellation", error.message)
        // kotlinx 除錯模式把跨 coroutine 失敗邊界的例外複製：呼叫端拿到的副本
        // suppressed=[]，原件（帶合併的 suppressed）位於 cause 鏈——以 throwableChain
        // 定位（與 revokeStoreTimeout_withWithdrawScopeIllegalState 同一模式）。
        // 註：本 runtime（kotlinx-coroutines debug 複製）下，scope 併入的例外在
        // 呼叫端不可觀測（實測鏈=[scope boom 不可見]）；suppressed 合併機制的
        // 確定性 pin 見 revokeStoreTimeout_withWithdrawScopeIllegalState_scopeExceptionAsSuppressed。
        // 此處只釘：CE 以原型別＋訊息逃出、旗標落 false。
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
    }

    @Test fun revokeStoreClearCancellation_withWithdrawScopeRuntimeFailure_primaryIsCancellation() = runBlocking {
        // store.clear() 拋 CE＋withdrawScope 拋 IllegalStateException →
        // CE 原樣上拋；scope 的 ISE 以 suppressed 併入（主失敗維持 CE-first）。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "ce_ise.preferences_pb")
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
        state.audit.record(listOf("id"), "B1-ce-ise", "DENY")

        val scopeBoom = IllegalStateException("simulated scope boom")
        val error = assertThrows(CancellationException::class.java) {
            runBlocking { state.revoke(ceStore) { throw scopeBoom } }
        }
        assertEquals("主失敗必為 store 的 CE", "simulated store cancellation", error.message)
        // 同上註：scope 併入的 ISE 在呼叫端不可觀測；只釘 CE-first 語義與旗標。
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
    }

    @Test fun revokeStoreClearRuntimeFailure_withWithdrawScopeCancellation_primaryIsRuntimeFailure() = runBlocking {
        // store.clear() 拋 RuntimeException＋withdrawScope 拋 CE →
        // 主失敗為 store 的 RuntimeException；scope 的 CE 以 suppressed 併入。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "re_ce.preferences_pb")
        }
        val reStore = PrivilegeBridgeStore(object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                throw RuntimeException("simulated clear boom")
            }
        })
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        state.audit.record(listOf("id"), "B1-re-ce", "DENY")

        val scopeBoom = CancellationException("simulated scope cancellation")
        val error = assertThrows(RuntimeException::class.java) {
            runBlocking { state.revoke(reStore) { throw scopeBoom } }
        }
        assertEquals("主失敗必為 store 的 RuntimeException", "simulated clear boom", error.message)
        assertFalse("主失敗不得為 CancellationException", error is CancellationException)
        // 同上註：scope 併入的 CE 在呼叫端不可觀測；只釘主失敗為 store 的 RuntimeException 與旗標。
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

    @Test(timeout = 30_000L)
    fun revokeStoreClearIoExceptionThenWithdrawScopeOutOfMemory_propagatesFatal() = runBlocking {
        // store.clear() 拋 IOException("simulated clear")＋withdrawScope 拋 OutOfMemoryError：
        // 第二腿 fatal 不得淪為 suppressed——
        // 拋出的例外必為 OutOfMemoryError，message 未被包裝/替換。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "clear_ioe_scope_oom.preferences_pb")
        }
        val clearIoFailure = java.io.IOException("simulated clear")
        val failingStore = PrivilegeBridgeStore(object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                throw clearIoFailure
            }
        })
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        state.audit.record(listOf("id"), "B1-clear-ioe-scope-oom", "DENY")

        val scopeFatal = OutOfMemoryError("simulated withdraw boom")
        val error = assertThrows(OutOfMemoryError::class.java) {
            runBlocking { state.revoke(failingStore) { throw scopeFatal } }
        }
        assertEquals("fatal 的 message 必與 scope 拋出者一致（未被包裝/替換）", "simulated withdraw boom", error.message)
        // 註：fatal 的反向附加（store 的 IOException 附於 scope 的 OutOfMemoryError）在本 runtime
        // 無法於呼叫端觀測——scope 拋出的例外於 withTimeoutOrNull 子協程邊界即被複製，addSuppressed
        // 落在該副本上，再跨 withContext(NonCancellable) 邊界時複製體再次丟棄 suppressed 並把 cause
        // 重指向原例外的 cause（實測鏈=[OOM, OOM]，IOException 整串不可達）。此處只釘 type＋message
        // ＋旗標/審計（fatal 未被合併壓回 IOException——未修復時此處必紅）；scope 例外可觀測併入的
        // suppressed 釘 pin 見 revokeStoreTimeout_withWithdrawScopeIllegalState_scopeExceptionAsSuppressed
        //（合併目標為 revoke 內部建立的 ISE，可定位）。
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
        assertEquals("撤權後審計表必已清空", 0, state.auditCount())
    }

    @Test(timeout = 30_000L)
    fun revokeBothLegsVirtualMachineError_firstFatalWins_clearLegPrimary() = runBlocking {
        // 兩腿皆拋 VirtualMachineError（store.clear 腿先遇、withdrawScope 腿後遇）：
        // uniform fatal precedence——首個 fatal（執行序在前的 clear 腿）為終端外傳例外；
        // scope 腿的 OOM 反向附加為其 suppressed（first-fatal-wins，後遇者不得取代先遇者）。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "both_legs_vmerror.preferences_pb")
        }
        val failingStore = PrivilegeBridgeStore(object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                throw OutOfMemoryError("clear leg OOM")
            }
        })
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        state.audit.record(listOf("id"), "B1-both-legs-vmerror", "DENY")

        val error = assertThrows(OutOfMemoryError::class.java) {
            runBlocking { state.revoke(failingStore) { throw OutOfMemoryError("scope leg OOM") } }
        }
        assertEquals("clear leg OOM", error.message)
        // 註（邊界複製 caveat，實測）：本 runtime 跨 withContext(NonCancellable) 時異常被複製——
        // 實測鏈=[clear leg OOM, clear leg OOM]，suppressed 皆空（scope leg OOM 反向附加不可觀測）。
        // 不 fake 該斷言；此處 pin：首個 fatal（clear 腿）為主失敗——message 未被替換為
        // scope leg OOM（即若第二腿 fatal 取代先遇者，本斷言必紅）。
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
        assertEquals("撤權後審計表必已清空", 0, state.auditCount())
    }

    @Test(timeout = 30_000L)
    fun revokeCePrimaryWithScopeVirtualMachineError_fatalPromotes_ceSuppressed() = runBlocking {
        // clear 腿拋 CancellationException（primaryFailure=CE）＋withdrawScope 腿拋 VMError：
        // uniform fatal precedence——第二腿 fatal 不得淪為 suppressed：
        // scope 腿的 OOM 升格為終端外傳例外，既有 CE 主失敗反向附加為其 suppressed。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "ce_primary_scope_vmerror.preferences_pb")
        }
        val failingStore = PrivilegeBridgeStore(object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                throw CancellationException("simulated clear CE")
            }
        })
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        val error = assertThrows(OutOfMemoryError::class.java) {
            runBlocking { state.revoke(failingStore) { throw OutOfMemoryError("simulated withdraw boom") } }
        }
        assertEquals("simulated withdraw boom", error.message)
        // 註（邊界複製 caveat，實測）：本 runtime 跨 withContext(NonCancellable) 時異常被複製——
        // 實測鏈=[withdraw boom, withdraw boom]，suppressed 皆空（反向附加的 CE 在呼叫端不可觀測）。
        // 不 fake 該斷言；此處 pin：type=OOM（若 fatal 淪為 suppressed、CE 成為主失敗，
        // 本斷言必紅）＋message 原樣＋flags false。
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
    }

    // #136-B3 → #166 移植（#168）：B3 的 two-step 撤權 API 已被 B1 持久版 supersede；
    // 以下測試為等效語義移植（成功路徑 exactly-once、caller-cancel 非同步清理），
    // 以及 Stage-7B uniform fatal precedence 對 ThreadDeath 的覆蓋（VMError ∪ ThreadDeath）。
    // 與 B3 的差異：B1 的 revoke 為記憶體先落＋鎖內有界 store.clear()＋鎖外有界
    // withdrawScope；例外跨 coroutine 邊界會被複製（message 保留、suppressed 不可觀測），
    // 故 pin type（＋可觀測時 message），不釘 identity／suppressed 圖。
    @Test fun persistentRevokeClearsStoreScopeAndMemory() = runBlocking {
        val audit = PrivilegeAuditLog()
        audit.record(listOf("id"), "B3-x", "DENY")
        val state = PrivilegeBridgeState(audit, bridgeSwitchOn = true, bridgeGranted = true)
        val store = store()
        var scopeCalls = 0

        val cleared = state.revoke(store) { scopeCalls++ }

        assertEquals(1, cleared)
        assertEquals(1, scopeCalls)
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
        assertEquals(0, state.auditCount())
        assertNull("store.clear 已落地，持久授權必已撤銷", store.load())
    }

    @Test(timeout = 30_000L) fun persistentRevokeSurvivesCallerCancellationNonCancellable() = runBlocking {
        // caller 協程在 store.clear() 掛起期間被取消：NonCancellable 清理區不隨 caller
        // 取消而提前返回或撕裂——caller 取消後放行 clear，兩個清除動作（store.clear 與
        // withdrawScope）仍各自跑到完成；記憶體閘早已同步關閉（memory-first）。
        // 以 CompletableDeferred 精確控制 clear 的完成點，並額外斷言「取消後、放行前」
        // 清理尚未提前完成（scope 未被呼叫、job 未完成）。
        val audit = PrivilegeAuditLog()
        audit.record(listOf("id"), "B3-x", "DENY")
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "persistent_revoke_caller_cancel.preferences_pb")
        }
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val clearCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val gatedDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                // 第一次 updateData（journal 寫入）進入即掛起直至放行；其後照常落盤。
                if (clearCalls.getAndIncrement() == 0) {
                    entered.complete(Unit)
                    release.await()
                }
                return backing.updateData(transform)
            }
        }
        val store = PrivilegeBridgeStore(gatedDs)
        val state = PrivilegeBridgeState(audit, bridgeSwitchOn = true, bridgeGranted = true)
        var scopeCalls = 0
        val job = launch {
            state.revoke(store) { scopeCalls++ }
        }
        withTimeout(2_000) { entered.await() }
        job.cancel()
        assertEquals("取消不得提前觸發 withdrawScope", 0, scopeCalls)
        assertFalse("取消不得提前撕裂清理區", job.isCompleted)
        assertTrue(job.isCancelled)
        release.complete(Unit)
        withTimeout(5_000) { job.join() }
        assertEquals(1, scopeCalls)
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
        assertEquals(0, state.auditCount())
        assertNull("store.clear 已落地，持久授權必已撤銷", store.load())
    }

    @Test(timeout = 30_000L)
    fun revokeStoreClearIoExceptionThenWithdrawScopeThreadDeath_FatalPrecedence() = runBlocking {
        // store.clear() 拋 IOException＋withdrawScope 拋 ThreadDeath：
        // 第二腿 fatal 不得淪為 suppressed——拋出的例外必為 ThreadDeath。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "clear_ioe_scope_threaddeath.preferences_pb")
        }
        val clearIoFailure = java.io.IOException("simulated clear")
        val failingStore = PrivilegeBridgeStore(object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                throw clearIoFailure
            }
        })
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        state.audit.record(listOf("id"), "B1-clear-ioe-scope-threaddeath", "DENY")

        val error = assertThrows(ThreadDeath::class.java) {
            runBlocking { state.revoke(failingStore) { throw ThreadDeath() } }
        }
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
        assertEquals("撤權後審計表必已清空", 0, state.auditCount())
    }

    @Test(timeout = 30_000L)
    fun revokeClearLegThreadDeathVsScopeLegVirtualMachineError_FatalPrecedence_firstFatalWins() = runBlocking {
        // 兩腿皆 fatal：clear 腿 ThreadDeath 先到、scope 腿 OOM 後到——
        // first-fatal-wins：clear 腿的 ThreadDeath 留 primary，scope 腿 OOM 反向掛入。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "clear_threaddeath_scope_oom.preferences_pb")
        }
        val failingStore = PrivilegeBridgeStore(object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                throw ThreadDeath()
            }
        })
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        state.audit.record(listOf("id"), "B1-clear-td-scope-oom", "DENY")

        val error = assertThrows(ThreadDeath::class.java) {
            runBlocking { state.revoke(failingStore) { throw OutOfMemoryError("scope leg OOM") } }
        }
        // pin：首個 fatal（clear 腿 ThreadDeath）為主失敗；若 scope 腿 OOM 取代之，本斷言必紅。
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
        assertEquals("撤權後審計表必已清空", 0, state.auditCount())
    }

    @Test(timeout = 30_000L)
    fun revokeClearLegVirtualMachineErrorVsScopeLegThreadDeath_FatalPrecedence_firstFatalWins() = runBlocking {
        // 兩腿皆 fatal：clear 腿 OOM 先到、scope 腿 ThreadDeath 後到——
        // first-fatal-wins：clear 腿的 OOM 留 primary（message 未被替換為 scope 腿）。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "clear_oom_scope_threaddeath.preferences_pb")
        }
        val failingStore = PrivilegeBridgeStore(object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                throw OutOfMemoryError("clear leg OOM")
            }
        })
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        state.audit.record(listOf("id"), "B1-clear-oom-scope-td", "DENY")

        val error = assertThrows(OutOfMemoryError::class.java) {
            runBlocking { state.revoke(failingStore) { throw ThreadDeath() } }
        }
        assertEquals("clear leg OOM", error.message)
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
        assertEquals("撤權後審計表必已清空", 0, state.auditCount())
    }

    @Test(timeout = 30_000L)
    fun revokeClearLegThreadDeathWithScopeCancellation_FatalPrecedence_clearLegPrimary() = runBlocking {
        // clear 腿 ThreadDeath（fatal primary）＋scope 腿 CE（非 fatal）：
        // CE 以 suppressed 附加（不取代 fatal）；fatal 留 primary。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "clear_threaddeath_scope_ce.preferences_pb")
        }
        val failingStore = PrivilegeBridgeStore(object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                throw ThreadDeath()
            }
        })
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        state.audit.record(listOf("id"), "B1-clear-td-scope-ce", "DENY")

        val error = assertThrows(ThreadDeath::class.java) {
            runBlocking { state.revoke(failingStore) { throw CancellationException("simulated scope CE") } }
        }
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
        assertEquals("撤權後審計表必已清空", 0, state.auditCount())
    }

    @Test(timeout = 30_000L)
    fun revokeCePrimaryWithScopeThreadDeath_FatalPrecedence_fatalPromotes() = runBlocking {
        // clear 腿拋 CancellationException（primaryFailure=CE）＋withdrawScope 腿拋 ThreadDeath：
        // uniform fatal precedence——第二腿 fatal 不得淪為 suppressed：
        // scope 腿的 ThreadDeath 升格為終端外傳例外（既有 CE 反向附加，呼叫端不可觀測）。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "ce_primary_scope_threaddeath.preferences_pb")
        }
        val failingStore = PrivilegeBridgeStore(object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                throw CancellationException("simulated clear CE")
            }
        })
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        val error = assertThrows(ThreadDeath::class.java) {
            runBlocking { state.revoke(failingStore) { throw ThreadDeath() } }
        }
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
    }

    @Test(timeout = 30_000L) fun revokeStoreOkWithdrawScopeThreadDeath_FatalPrecedence_scopeFatalPropagates() = runBlocking {
        // store 正常＋withdrawScope 拋 ThreadDeath（無既有主失敗）：
        // scope 腿 fatal 直接成為 primary 並原樣外傳；store.clear 已落地。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "store_ok_scope_threaddeath.preferences_pb")
        }
        val store = PrivilegeBridgeStore(backing)
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        state.audit.record(listOf("id"), "B1-store-ok-scope-td", "DENY")

        val error = assertThrows(ThreadDeath::class.java) {
            runBlocking { state.revoke(store) { throw ThreadDeath() } }
        }
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
        assertEquals("撤權後審計表必已清空", 0, state.auditCount())
        assertNull("store.clear 已落地，持久授權必已撤銷", store.load())
    }

    @Test(timeout = 30_000L) fun revokeStoreOkWithdrawScopeOutOfMemory_FatalPrecedence_scopeFatalPropagates() = runBlocking {
        // store 正常＋withdrawScope 拋 OOM（無既有主失敗）：scope 腿 fatal 原樣外傳；store.clear 已落地。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "store_ok_scope_oom.preferences_pb")
        }
        val store = PrivilegeBridgeStore(backing)
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        state.audit.record(listOf("id"), "B1-store-ok-scope-oom", "DENY")

        val error = assertThrows(OutOfMemoryError::class.java) {
            runBlocking { state.revoke(store) { throw OutOfMemoryError("simulated withdraw oom") } }
        }
        assertEquals("simulated withdraw oom", error.message)
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
        assertEquals("撤權後審計表必已清空", 0, state.auditCount())
        assertNull("store.clear 已落地，持久授權必已撤銷", store.load())
    }

    @Test(timeout = 30_000L) fun revokeMutexTimeout_bestEffortClearThreadDeath_FatalPrecedence_propagatesFatal() = runBlocking {
        // 鎖外 best-effort clear 腿（第二腿）拋 ThreadDeath：
        // fatal 不得淪為 suppressed（uniform fatal precedence；Stage 7B 補齊 ThreadDeath）——
        // grant 落庫以 latch 持 opMutex，revokeOpTimeoutMs=100 → opMutex 等待逾時 →
        // 主失敗為 PRIVILEGE_REVOKE_TIMEOUT ISE；鎖外 best-effort clear 的第二次起
        // updateData 直接拋 ThreadDeath → 補償腿 fatal 成為終端外傳例外（原 ISE 反向附加）。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "stuck_mutex_best_effort_clear_threaddeath.preferences_pb")
        }
        val lockHoldStarted = java.util.concurrent.CountDownLatch(1)
        val lockHoldRelease = java.util.concurrent.CountDownLatch(1)
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val fatal = ThreadDeath()
        val oomDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                // 第一次 updateData 為 grant 落庫：掛住直到測試於 revoke 逾時後放行；
                // clear 路徑的 edit 直接拋 ThreadDeath（不 hang）。
                if (saveCalls.getAndIncrement() == 0) {
                    lockHoldStarted.countDown()
                    lockHoldRelease.await(10, TimeUnit.SECONDS)
                    return backing.updateData(transform)
                }
                throw fatal
            }
        }
        val store = PrivilegeBridgeStore(oomDs)
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
        assertTrue("grant 落庫必已進入臨界區", lockHoldStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))

        val error = try {
            assertThrows(ThreadDeath::class.java) {
                runBlocking { state.revoke(store) }
            }
        } finally {
            lockHoldRelease.countDown()
        }
        grantJob.join()

        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
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

    @Test(timeout = 30_000L) fun refreshAfterFailedRevoke_withStaleValidGrantOnDisk_staysFailClosed() = runBlocking {
        // stuck grant 持 opMutex → revoke 有界等待 opMutex 逾時 → REVOKE_TIMEOUT；
        // 鎖外 best-effort clear 因 IOException 快速失敗（suppressed）；
        // 落盤 fresh grant saved by the grant that then threw 保留在 backing → seal 封存壓住，refresh 一律 Invalid。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "refresh_after_failed_revoke.preferences_pb")
        }
        val lockHoldStarted = java.util.concurrent.CountDownLatch(1)
        val lockHoldRelease = java.util.concurrent.CountDownLatch(1)
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val ioFailure = java.io.IOException("simulated clear IO failure")
        val failingDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                // 第一次 updateData 為 grant 落庫：countDown 後掛住（latch-gated，
                // 帶 10s 上限，絕不裸 await）直到測試於 revoke 逾時後放行；
                // 之後 clear 路徑的 edit 直接拋 IOException（不 hang）。
                if (saveCalls.getAndIncrement() == 0) {
                    lockHoldStarted.countDown()
                    lockHoldRelease.await(10, TimeUnit.SECONDS)
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
                state.grant(store, "provider-a", 1L)
            } catch (_: Throwable) {
                // 預期：revoke 搶先推進 epoch → grant 拋 PRIVILEGE_GRANT_REVOKED_CONCURRENTLY
            }
        }
        assertTrue(lockHoldStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))

        val error = try {
            assertThrows(IllegalStateException::class.java) {
                runBlocking { state.revoke(store) }
            }
        } finally {
            // assertThrows 返回（revoke 於 revokeOpTimeoutMs=100 逾時後拋出）即放行 grant 落庫；
            // finally 保證放行。
            lockHoldRelease.countDown()
        }
        grantJob.join()

        assertEquals("PRIVILEGE_REVOKE_TIMEOUT", error.message)
        // 沿用現有斷言慣例：走訪 cause／suppressed 鏈定位 best-effort clear 失敗
        val chain = throwableChain(error)
        assertTrue(
            "例外鏈上必含 IllegalStateException(PRIVILEGE_REVOKE_BEST_EFFORT_CLEAR_FAILED)，實際=$chain",
            chain.any {
                it is IllegalStateException && it.message == "PRIVILEGE_REVOKE_BEST_EFFORT_CLEAR_FAILED"
            },
        )
        // fresh grant saved by the grant that then threw 仍在磁碟（marker/journal 為復活防護，非磁碟已清）：
        // load() 會被 in-memory marker 擋下回 null，故直接讀 backing 驗證 grant 鍵未擦除。
        val prefs = backing.data.first()
        assertEquals(
            "落盤 fresh grant 必仍在（撤權未完成）",
            "provider-a",
            prefs[PRIVILEGE_GRANT_PROVIDER_ID],
        )
        // 同一實例 refresh：seal 封存 → Invalid(NEVER_GRANTED)，旗標不復活
        val check = state.refresh(store, "provider-a", 1L)
        assertTrue(check is PrivilegeGrantCheck.Invalid)
        assertEquals(
            PrivilegeGrantPolicy.REASON_NEVER_GRANTED,
            (check as PrivilegeGrantCheck.Invalid).reason,
        )
        assertFalse(state.bridgeGranted)
        assertFalse(state.bridgeEffective)
        // 以「全新 store 實例＋全新 marker」重讀同一 backing：盤面持久授權仍
        // 視同有效（marker 未落盤／無 journal，load() → Valid）；唯有 seal 能壓住。
        // 此為 marker-free seal 判別：seal 若失效，此 refresh 必回 Valid。
        val freshStore = PrivilegeBridgeStore(backing)
        val freshCheck = state.refresh(freshStore, "provider-a", 1L)
        assertTrue(freshCheck is PrivilegeGrantCheck.Invalid)
        assertEquals(
            PrivilegeGrantPolicy.REASON_NEVER_GRANTED,
            (freshCheck as PrivilegeGrantCheck.Invalid).reason,
        )
        assertFalse(state.bridgeGranted)
        assertEquals(0, state.auditCount())
    }

    @Test fun refreshAfterSuccessfulRevoke_storeCleared_staysInvalid() = runBlocking {
        // 完整成功的 revoke 後 refresh 仍 Invalid；且 revoke 後重開開關，
        // bridgeEffective 仍 false（seal 封存壓住旗標，開關本身不構成授權）。
        // 注意：本測試釘住的是「store 已被擦除的 happy path」＋開關的 cosmetic 性質，
        // 不釘 seal——store 為空 ⇒ refresh 無論 seal 與否皆 Invalid。
        // （seal 的判別測試見 refreshAfterFailedRevoke_withStaleValidGrantOnDisk_staysFailClosed 與
        // grantAfterSettledFailedRevoke_* 等 seal 測試。）
        val store = store()
        val state = PrivilegeBridgeState(bridgeSwitchOn = true)

        state.grant(store, "preset:openai", 1L)
        assertTrue("前置：首次成功 grant 後旗標必為 true", state.bridgeGranted)

        state.revoke(store)
        assertNull("revoke 完整成功後持久授權必已清除", store.load())

        val check = state.refresh(store, "preset:openai", 1L)
        assertTrue(check is PrivilegeGrantCheck.Invalid)
        assertEquals(
            PrivilegeGrantPolicy.REASON_NEVER_GRANTED,
            (check as PrivilegeGrantCheck.Invalid).reason,
        )
        assertFalse(state.bridgeGranted)
        // 重開開關亦不得復活
        state.setBridgeSwitch(true)
        assertFalse("seal 封存期間 bridgeEffective 恆 false", state.bridgeEffective)
        assertEquals(0, state.auditCount())
    }

    @Test(timeout = 30_000L) fun refreshRevokedWhileQueuedOnMutexFailsClosed() = runBlocking {
        // 鏡像 grantRevokedWhileQueuedOnMutexFailsClosed 的 idiom：
        // blocker 持有 opMutex（DataStore stuck），refresh 以 Unconfined 排隊，
        // 佇列中插入 revokeInMemoryForTest() → 排隊中的 refresh 必 fail-closed。
        // 註：本測試釘住的是 epoch fence（排隊中的 refresh 於醒後見 epoch 不符 ⇒
        // Invalid(NEVER_GRANTED)），不釘 seal——seal 於入口即封存、refresh 直接回
        // Invalid 的路徑見 refreshAfterFailedRevoke_*／grantAfterSettledFailedRevoke_* 等 seal 測試。
        val store = store()
        val state = PrivilegeBridgeState(bridgeSwitchOn = true)
        val slowStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "rev_queue_refresh.preferences_pb") }

        val lockHoldStarted = java.util.concurrent.CountDownLatch(1)
        val lockHoldRelease = java.util.concurrent.CountDownLatch(1)

        val blockerStore = PrivilegeBridgeStore(object : DataStore<Preferences> by slowStore {
            override val data: Flow<Preferences> = flow {
                lockHoldStarted.countDown()
                lockHoldRelease.await(10, TimeUnit.SECONDS)
                emit(androidx.datastore.preferences.core.emptyPreferences())
            }
        })

        // Launch job holding opMutex via refresh
        val job1 = launch(Dispatchers.IO) {
            state.refresh(blockerStore, "preset:openai", 1L)
        }
        assertTrue(lockHoldStarted.await(10, TimeUnit.SECONDS))

        // 以 Unconfined 明確驅動：launch 返回時 job2 已執行到 opMutex.lock() 的掛起點
        //（epochBefore 已捕捉且排隊等鎖）——不再靠 Thread.sleep 猜測排程。
        val refreshResult = java.util.concurrent.atomic.AtomicReference<PrivilegeGrantCheck?>()
        val job2 = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            refreshResult.set(state.refresh(store, "preset:openai", 1L))
        }
        // launch 返回即代表 job2 已排隊於 opMutex（Mutex 由 job1 持有）。

        // User revokes while job2 is queued
        state.revokeInMemoryForTest()

        // Release job1
        lockHoldRelease.countDown()
        job1.join()
        job2.join()

        val check = refreshResult.get()
        assertTrue("排隊中的 refresh 必回 Invalid，實際=$check", check is PrivilegeGrantCheck.Invalid)
        assertEquals(
            PrivilegeGrantPolicy.REASON_NEVER_GRANTED,
            (check as PrivilegeGrantCheck.Invalid).reason,
        )
        assertFalse(state.bridgeGranted)
    }

    @Test(timeout = 30_000L) fun grantDuringRevokeInFlight_failsFast() = runBlocking {
        // revoke 卡在 store.clear() 的 hang 假件上（inFlight 於入口設置；opMutex 已取得、
        // clear 掛起等待測試放開）：期間 grant() 必 fail-fast 拋 REVOKE_IN_PROGRESS，
        // seal 維持封存（refresh 一律 Invalid）、旗標 false。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "grant_during_in_flight.preferences_pb")
        }
        val clearStarted = java.util.concurrent.CountDownLatch(1)
        val hangClear = java.util.concurrent.CountDownLatch(1)
        // 先落盤一份有效授權（plain store，直接走 backing.updateData 不經 hang 假件）：
        // 供下方 seal 判別——seal 若失效，marker-free 全新 store 的 refresh 必回 Valid。
        PrivilegeBridgeStore(backing).save(PrivilegeGrantPolicy.grant("preset:openai", 1L))
        val hangingDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                clearStarted.countDown()
                hangClear.await(10, java.util.concurrent.TimeUnit.SECONDS)
                return backing.updateData(transform)
            }
        }
        val store = PrivilegeBridgeStore(hangingDs)
        val state = PrivilegeBridgeState(bridgeSwitchOn = true, bridgeGranted = true)

        val revokeJob = launch(Dispatchers.IO) {
            try {
                state.revoke(store)
            } catch (_: Throwable) {
                // 允許成功或失敗——本測試只關心 inFlight 窗口內的行為
            }
        }
        // revoke 進入 clear()：opMutex 自由取得 → 臨界區內 store.clear() 掛起（inFlight == true）
        assertTrue(clearStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { state.grant(store, "preset:openai", 1L) }
        }
        assertEquals("PRIVILEGE_REVOKE_IN_PROGRESS", error.message)
        assertFalse(state.bridgeGranted)
        // seal 仍封存的可觀測後果：refresh 一律 Invalid（不依賴私有欄位）。
        // 以「全新 healthy store 實例（marker-free）」重讀同一 backing：盤面有持久
        // 授權，唯有 seal 能壓住（seal 若失效，此 refresh 必回 Valid）。
        // 不用會 hang 的 hanging store——seal 若回歸，heal 路徑會吊進 hangClear 假件。
        val freshStore = PrivilegeBridgeStore(backing)
        val sealedCheck = state.refresh(freshStore, "preset:openai", 1L)
        assertTrue(sealedCheck is PrivilegeGrantCheck.Invalid)
        assertEquals(
            PrivilegeGrantPolicy.REASON_NEVER_GRANTED,
            (sealedCheck as PrivilegeGrantCheck.Invalid).reason,
        )

        hangClear.countDown()
        revokeJob.join()
    }

    @Test fun grantAfterSettledFailedRevoke_explicitReGrant_liftsSeal() = runBlocking {
        // 撤權落敗（clear 全失敗：journal／best-effort wipe 皆拋 IOException）→
        // seal 維持；隨後明確新授權（grant 成功落庫）解除 seal：
        // 旗標 true，refresh 回 Valid。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "regrant_lifts_seal.preferences_pb")
        }
        val clearFails = java.util.concurrent.atomic.AtomicBoolean(false)
        val ioFailure = java.io.IOException("simulated clear IO failure")
        val flakyDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                if (clearFails.get()) throw ioFailure
                return backing.updateData(transform)
            }
        }
        val store = PrivilegeBridgeStore(flakyDs)
        val state = PrivilegeBridgeState(bridgeSwitchOn = true)

        // 前置：一次成功 grant（盤面有真實授權）
        state.grant(store, "preset:openai", 1L)
        assertTrue("前置：首次成功 grant 後旗標必為 true", state.bridgeGranted)

        // 撤權落敗：clear 全失敗 → 例外上拋 → seal 維持封存
        clearFails.set(true)
        assertThrows(java.io.IOException::class.java) {
            runBlocking { state.revoke(store) }
        }
        assertFalse(state.bridgeGranted)
        val sealedCheck = state.refresh(store, "preset:openai", 1L)
        assertTrue("seal 封存期間 refresh 必 Invalid", sealedCheck is PrivilegeGrantCheck.Invalid)
        assertEquals(
            PrivilegeGrantPolicy.REASON_NEVER_GRANTED,
            (sealedCheck as PrivilegeGrantCheck.Invalid).reason,
        )

        // 明確新授權：grant 成功落庫 → seal 解除 → 旗標 true → refresh 回 Valid
        clearFails.set(false)
        val regranted = state.grant(store, "preset:openai", 1L)
        assertTrue("explicit grant commit 必解除 seal 並置旗標", state.bridgeGranted)
        // 新授權必落盤（內容比對，鏡像既有 pin 的斷言風格）
        assertEquals("明確新授權必落盤", regranted, store.load())
        val check = state.refresh(store, "preset:openai", 1L)
        assertTrue("seal 解除後 refresh 必 Valid，實際=$check", check is PrivilegeGrantCheck.Valid)
    }

    @Test fun grantAfterSettledFailedRevoke_saveThrows_keepsSeal() = runBlocking {
        // 復現落敗撤權前置（同 refreshAfterFailedRevoke_withStaleValidGrantOnDisk_staysFailClosed）：
        // clear 全失敗（IOException）→ revoke 例外上拋 → seal 維持封存；
        // 盤上 fresh grant 未被擦除。隨後 re-grant 的 save 亦拋 IOException →
        // grant 拋錯 → seal 仍封存 → refresh 一律 Invalid
        // （判別性：以 marker-free 全新 store 重讀同一 backing，seal 若失效，load() → Valid）。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "save_throws_keeps_seal.preferences_pb")
        }
        val clearFails = java.util.concurrent.atomic.AtomicBoolean(false)
        val saveFails = java.util.concurrent.atomic.AtomicBoolean(false)
        val clearFailure = java.io.IOException("simulated clear IO failure")
        val saveFailure = java.io.IOException("simulated save IO failure")
        val flakyDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                if (saveFails.get()) throw saveFailure
                if (clearFails.get()) throw clearFailure
                return backing.updateData(transform)
            }
        }
        val store = PrivilegeBridgeStore(flakyDs)
        val state = PrivilegeBridgeState(bridgeSwitchOn = true)

        // 前置：一次成功 grant（盤面有真實授權；隨後撤權落敗無法擦除）
        state.grant(store, "provider-a", 1L)
        assertTrue(state.bridgeGranted)

        // 落敗撤權：clear 全失敗 → 例外上拋 → seal 維持封存
        clearFails.set(true)
        assertThrows(java.io.IOException::class.java) {
            runBlocking { state.revoke(store) }
        }
        assertFalse(state.bridgeGranted)
        // 落敗撤權後盤面 grant 鍵未擦除（journal 未寫成、wipe 亦敗）
        val prefs = backing.data.first()
        assertEquals(
            "落敗撤權後 fresh grant 必仍在盤上（撤權未完成）",
            "provider-a",
            prefs[PRIVILEGE_GRANT_PROVIDER_ID],
        )

        // re-grant 的 save 拋 IOException → grant 拋錯 → seal 必維持
        clearFails.set(false)
        saveFails.set(true)
        assertThrows(java.io.IOException::class.java) {
            runBlocking { state.grant(store, "provider-a", 1L) }
        }
        assertFalse(state.bridgeGranted)
        val sealedCheck = state.refresh(store, "provider-a", 1L)
        assertTrue("save 落敗後 seal 必維持封存：refresh 一律 Invalid", sealedCheck is PrivilegeGrantCheck.Invalid)
        assertEquals(
            PrivilegeGrantPolicy.REASON_NEVER_GRANTED,
            (sealedCheck as PrivilegeGrantCheck.Invalid).reason,
        )
        // marker-free seal 判別：fresh store 讀同一 backing，無 seal 時 load() → Valid
        val freshStore = PrivilegeBridgeStore(backing)
        val freshCheck = state.refresh(freshStore, "provider-a", 1L)
        assertTrue("fresh store 亦必 Invalid（seal 判別：無 seal 時 refresh 必回 Valid）", freshCheck is PrivilegeGrantCheck.Invalid)
        assertEquals(
            PrivilegeGrantPolicy.REASON_NEVER_GRANTED,
            (freshCheck as PrivilegeGrantCheck.Invalid).reason,
        )
    }

    @Test fun grantAfterSettledFailedRevoke_expiredGrantDoesNotLiftSeal() = runBlocking {
        // 復現落敗撤權前置（同 grantAfterSettledFailedRevoke_explicitReGrant_liftsSeal／
        // grantAfterSettledFailedRevoke_saveThrows_keepsSeal）：
        // clear 全失敗（IOException）→ revoke 例外上拋 → seal 維持封存；
        // 盤上 stale grant 未被擦除。隨後以 ttlMs = 0L 明確 re-grant：
        // 落庫成功但 postCheck Invalid（即刻過期）⇒ seal 不得解除。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "regrant_expired_keeps_seal.preferences_pb")
        }
        val clearFails = java.util.concurrent.atomic.AtomicBoolean(false)
        val ioFailure = java.io.IOException("simulated clear IO failure")
        val flakyDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                if (clearFails.get()) throw ioFailure
                return backing.updateData(transform)
            }
        }
        val store = PrivilegeBridgeStore(flakyDs)
        val state = PrivilegeBridgeState(bridgeSwitchOn = true)

        // 前置：一次成功 grant（盤面有真實授權）
        state.grant(store, "provider-a", 1L)
        assertTrue(state.bridgeGranted)

        // 落敗撤權：clear 全失敗 → 例外上拋 → seal 維持封存
        clearFails.set(true)
        assertThrows(java.io.IOException::class.java) {
            runBlocking { state.revoke(store) }
        }
        assertFalse(state.bridgeGranted)
        // 落敗撤權後盤面 grant 鍵未擦除（journal 未寫成、wipe 亦敗）
        val prefs = backing.data.first()
        assertEquals(
            "落敗撤權後 stale grant 必仍在盤上（撤權未完成）",
            "provider-a",
            prefs[PRIVILEGE_GRANT_PROVIDER_ID],
        )

        // 明確 re-grant 但即刻過期：ttlMs = 0L → postCheck Invalid ⇒ seal 不得解除
        clearFails.set(false)
        state.grant(store, "provider-a", 1L, ttlMs = 0L)
        assertTrue("postCheck Invalid ⇒ seal 不得解除", state.isRevocationSealedForTest())
        assertFalse(state.bridgeGranted)
        // 同實例 refresh 回 Invalid
        val sealedCheck = state.refresh(store, "provider-a", 1L)
        assertTrue(
            "seal 封存期間 refresh 必 Invalid，實際=$sealedCheck",
            sealedCheck is PrivilegeGrantCheck.Invalid,
        )
        // marker-free 新 store refresh 亦回 Invalid（EXPIRED 或 NEVER_GRANTED 皆可，
        // 不判別理由碼，只斷言 is Invalid）
        val freshStore = PrivilegeBridgeStore(backing)
        val freshCheck = state.refresh(freshStore, "provider-a", 1L)
        assertTrue(
            "marker-free 新 store refresh 亦必 Invalid，實際=$freshCheck",
            freshCheck is PrivilegeGrantCheck.Invalid,
        )
        assertFalse(state.bridgeGranted)
        assertFalse(state.bridgeSwitchOn)
    }

    @Test(timeout = 30_000L) fun revokeTimeoutPath_reassertsSwitchAndFlag() = runBlocking {
        // grant 卡住 opMutex（lockHold latch，不再靠 delay 猜排程）→ revoke 開始
        //（入口關旗、封存 seal）→ revoke 等待 opMutex 逾時後進入鎖外 best-effort clear
        //（掛起於 hangClear）→ 假件「於 clear 路徑、revoke inFlight 窗口內、revoke 尚未
        // 回到呼叫端」確定性重新打開開關 → revoke 走完 → 最外層 finally 必重新斷言 switch=false、flag=false。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "revoke_timeout_reassert.preferences_pb")
        }
        val lockHoldStarted = java.util.concurrent.CountDownLatch(1)
        val lockHold = java.util.concurrent.CountDownLatch(1)
        val clearStarted = java.util.concurrent.CountDownLatch(1)
        val hangClear = java.util.concurrent.CountDownLatch(1)
        val reopenedOnce = java.util.concurrent.atomic.AtomicBoolean(false)
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        // state 先建立：clear 路徑的假件需於 revoke 的 inFlight 窗口內直接操作 state。
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
            revokeOpTimeoutMs = 100L,
        )
        val slowDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                // 第一次 updateData 為 grant 落庫：countDown 後掛起於 lockHold（由測試於
                // revoke 結束後放開）；後續 clear 路徑的 edit：宣告 clearStarted 後於窗口內
                // 重新打開開關，再掛起於 hangClear（由測試放開），確定性建立
                // 「revoke 已進入 best-effort clear、尚未回到呼叫端」的窗口。
                if (saveCalls.getAndIncrement() == 0) {
                    lockHoldStarted.countDown()
                    lockHold.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    return backing.updateData(transform)
                }
                clearStarted.countDown()
                if (reopenedOnce.compareAndSet(false, true)) {
                    // revoke inFlight 窗口內、revoke 尚未返回呼叫端：模擬 UI 重新打開開關
                    state.setBridgeSwitch(true)
                }
                hangClear.await(10, java.util.concurrent.TimeUnit.SECONDS)
                return backing.updateData(transform)
            }
        }
        val store = PrivilegeBridgeStore(slowDs)
        val grantJob = launch(Dispatchers.IO) {
            try {
                state.grant(store, "preset:openai", 1L)
            } catch (_: Throwable) {
                // 預期：revoke 搶先推進 epoch → grant 拋 PRIVILEGE_GRANT_REVOKED_CONCURRENTLY
            }
        }
        assertTrue(lockHoldStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))

        val revokeError = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val revokeJob = launch(Dispatchers.IO) {
            try {
                state.revoke(store)
            } catch (t: Throwable) {
                revokeError.set(t)
            }
        }
        // 等 revoke 進入鎖外 best-effort clear（opMutex 等待已逾時 PRIVILEGE_REVOKE_TIMEOUT）；
        // 假件已於該窗口內重新打開開關（reopenedOnce 已被消耗）：
        assertTrue(clearStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))
        // 放開 clear → revoke 走完 → 最外層 finally 重新斷言 switch+flag：
        hangClear.countDown()
        revokeJob.join()

        val error = revokeError.get()
        assertTrue(
            "revoke 必 fail-closed 拋 IllegalStateException(PRIVILEGE_REVOKE_TIMEOUT)，實際=$error",
            error is IllegalStateException && error.message == "PRIVILEGE_REVOKE_TIMEOUT",
        )
        assertFalse("revoke 最外層 finally 必重新斷言 switch=false", state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)

        // 放開 grant 的 lockHold → grant 醒後見 epoch 不符 → 收場（best-effort clear 已 counted-down，立即放行）
        lockHold.countDown()
        grantJob.join()
    }

    @Test fun grantAfterSettledFailedRevoke_saveCancellation_keepsSeal() = runBlocking {
        // 落敗撤權（clear 全失敗：IOException）settle 後 seal 維持封存；
        // 隨後明確新授權的 save 拋 CancellationException（假件直接拋，非父協程預取消）：
        // CE 原樣傳播（CE-first，不得包裝/折 ISE），seal 維持封存 →
        // refresh 一律 Invalid（reason == REASON_NEVER_GRANTED）。
        // 判別性：以 marker-free 全新 store 重讀同一 backing，seal 若失效，load() → Valid。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "save_cancellation_keeps_seal.preferences_pb")
        }
        val clearFails = java.util.concurrent.atomic.AtomicBoolean(false)
        val saveFailsWithCe = java.util.concurrent.atomic.AtomicBoolean(false)
        val clearFailure = java.io.IOException("simulated clear IO failure")
        val flakyDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                if (saveFailsWithCe.get()) throw CancellationException("simulated save cancellation")
                if (clearFails.get()) throw clearFailure
                return backing.updateData(transform)
            }
        }
        val store = PrivilegeBridgeStore(flakyDs)
        val state = PrivilegeBridgeState(bridgeSwitchOn = true)

        // 前置：一次成功 grant（盤面有真實授權）
        state.grant(store, "provider-a", 1L)
        assertTrue(state.bridgeGranted)

        // 落敗撤權並 settle：clear 全失敗 → 例外上拋 → seal 維持
        clearFails.set(true)
        assertThrows(java.io.IOException::class.java) {
            runBlocking { state.revoke(store) }
        }
        assertFalse(state.bridgeGranted)

        // re-grant 的 save 拋 CancellationException → CE 原樣傳播 → seal 必維持
        clearFails.set(false)
        saveFailsWithCe.set(true)
        val error = assertThrows(CancellationException::class.java) {
            runBlocking { state.grant(store, "provider-a", 1L) }
        }
        assertEquals("simulated save cancellation", error.message)
        // 註：JVM CancellationException 本身是 IllegalStateException 的子類；
        // "不折 ISE" 已由 assertThrows(CancellationException::class.java) 釘住（型別不可為純 ISE）。
        assertFalse(state.bridgeGranted)
        val sealedCheck = state.refresh(store, "provider-a", 1L)
        assertTrue(sealedCheck is PrivilegeGrantCheck.Invalid)
        assertEquals(
            PrivilegeGrantPolicy.REASON_NEVER_GRANTED,
            (sealedCheck as PrivilegeGrantCheck.Invalid).reason,
        )
        // marker-free seal 判別：fresh store 讀同一 backing，無 seal 時 load() → Valid
        val freshStore = PrivilegeBridgeStore(backing)
        val freshCheck = state.refresh(freshStore, "provider-a", 1L)
        assertTrue(freshCheck is PrivilegeGrantCheck.Invalid)
        assertEquals(
            PrivilegeGrantPolicy.REASON_NEVER_GRANTED,
            (freshCheck as PrivilegeGrantCheck.Invalid).reason,
        )
    }

    @Test fun publishBridgeGranted_sealSetBeforeWrite_doesNotEndTrue() = runBlocking {
        // seal 置位下旗標絕不能落 true；write conjunct 與 second-read 兩臂共同擋下。
        // write conjunct 不可單獨判別（見產品 KDoc precision note），此測試固定
        // 「sealed ⇒ 旗標不可為 true」的整體不變式，不判別兩臂各自。
        val store = store()
        store.save(PrivilegeGrantPolicy.grant("preset:openai", 1L))
        val state = PrivilegeBridgeState(bridgeSwitchOn = true)
        state.bridgeGrantedPublishHookForTest = { state.setRevocationSealOnlyForTest() }
        try {
            state.refresh(store, "preset:openai", 1L)
        } finally {
            state.bridgeGrantedPublishHookForTest = null
        }
        assertFalse("seal 置位後 write 必不得落 true", state.bridgeGranted)
    }

    @Test fun publishBridgeGranted_sealSetBetweenWriteAndSecondRead_secondReadForcesFalse() = runBlocking {
        // second-read 單層 seal 防線 pin：賦值後（旗標可能已寫 true）、second-read 前
        // 插入 seal；second-read 必把旗標壓回 false。
        // second-read 若移除 seal 臂，本測試必紅（epoch 未變，epoch 臂不會觸發）。
        val store = store()
        store.save(PrivilegeGrantPolicy.grant("preset:openai", 1L))
        val state = PrivilegeBridgeState(bridgeSwitchOn = true)
        state.bridgeGrantedEpochBumpHookForTest = { state.setRevocationSealOnlyForTest() }
        try {
            state.refresh(store, "preset:openai", 1L)
        } finally {
            state.bridgeGrantedEpochBumpHookForTest = null
        }
        assertFalse("賦值→回讀間 seal 置位 ⇒ second-read 必壓回 false", state.bridgeGranted)
    }

    @Test(timeout = 30_000L) fun grantEpilogueCompensationClearHangs_grantDoesNotHang() = runBlocking {
        // 補償清庫有界 pin（revokeOpTimeoutMs）：grant commit 窗口插入並發撤銷
        //（grantCommitHookForTest 內 revokeInMemoryForTest() 推進 epoch＋置 seal；
        // 不寫 Store、不重入 revoke）。commit RMW 見 epoch 不符 ⇒
        // failGrantRevokedConcurrently ⇒ best-effort 補償清庫。
        // fake 的第二次起 updateData（補償清庫路徑）以 CountDownLatch 掛住、
        // 僅測試 finally 放開；hang 迴圈內以 ensureActive() 響應協作式取消——
        // withTimeoutOrNull(revokeOpTimeoutMs=100) 必於上限內取消該假件並返回 null
        //（latch 放開前絕不返回的無界路徑不存在）。
        // 斷言：grant 以 ISE(PRIVILEGE_GRANT_REVOKED_CONCURRENTLY) 結束且 <5s；
        // 掛住的補償清庫已在 latch 放開前被取消。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "grant_epilogue_hang.preferences_pb")
        }
        val hangLatch = java.util.concurrent.CountDownLatch(1)
        val compensationClearEntered = java.util.concurrent.atomic.AtomicBoolean(false)
        val compensationClearCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        // 第一次 updateData 為 grant 落庫（成功）；其後每次（補償清庫）掛住直到 finally 放開，
        // 但於 5ms 切片輪詢中以 ensureActive() 響應取消（絕不裸 await、絕不靠時序猜測）。
        val hangingDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                if (saveCalls.getAndIncrement() == 0) {
                    return backing.updateData(transform)
                }
                compensationClearEntered.set(true)
                try {
                    while (!hangLatch.await(5, TimeUnit.MILLISECONDS)) {
                        currentCoroutineContext().ensureActive()
                    }
                    return backing.updateData(transform)
                } catch (t: Throwable) {
                    if (t is CancellationException) {
                        compensationClearCancelled.set(true)
                    }
                    throw t
                }
            }
        }
        val store = PrivilegeBridgeStore(hangingDs)
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            revokeOpTimeoutMs = 100L,
        )
        state.grantCommitHookForTest = { state.revokeInMemoryForTest() }
        try {
            val t0 = System.currentTimeMillis()
            val error = assertThrows(IllegalStateException::class.java) {
                runBlocking { withTimeout(5_000L) { state.grant(store, "preset:openai", 1L) } }
            }
            val elapsed = System.currentTimeMillis() - t0
            assertEquals("PRIVILEGE_GRANT_REVOKED_CONCURRENTLY", error.message)
            assertTrue("epilogue 補償清庫必有界返回，took ${elapsed}ms", elapsed < 5_000L)
            assertTrue("補償清庫路徑必已進入", compensationClearEntered.get())
            assertTrue(
                "掛住的補償清庫必已被 withTimeoutOrNull 取消（latch 放開前即離開）",
                compensationClearCancelled.get(),
            )
            assertFalse("commit 窗口並發撤銷 ⇒ 旗標必 false", state.bridgeGranted)
        } finally {
            state.grantCommitHookForTest = null
            hangLatch.countDown()
        }
    }

    @Test(timeout = 30_000L)
    fun failGrantRevokedConcurrently_compensationClearOutOfMemory_propagatesFatal() = runBlocking {
        // 補償清庫（epilogue best-effort clear）於第二次起 updateData 拋 OutOfMemoryError：
        // 補償腿 fatal 不得淪為 logcat-only 吞掉——
        // grant() 必以 OutOfMemoryError 上拋（不合成 PRIVILEGE_GRANT_REVOKED_CONCURRENTLY ISE 覆蓋 fatal），
        // 併發撤銷 claim 的 ISE 以 suppressed 保留；旗標 false、審計清空。
        // harness 同 grantEpilogueCompensationClearHangs_grantDoesNotHang：
        // 第一次 updateData 為 grant 落庫（成功）；其後（補償清庫）拋 OOM。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "grant_epilogue_clear_oom.preferences_pb")
        }
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val fatal = OutOfMemoryError("simulated compensation clear OOM")
        val flakyDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                if (saveCalls.getAndIncrement() == 0) {
                    return backing.updateData(transform)
                }
                throw fatal
            }
        }
        val store = PrivilegeBridgeStore(flakyDs)
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        state.audit.record(listOf("id"), "B1-comp-clear-oom", "DENY")
        state.grantCommitHookForTest = { state.revokeInMemoryForTest() }
        try {
            val error = assertThrows(OutOfMemoryError::class.java) {
                runBlocking { state.grant(store, "preset:openai", 1L) }
            }
            assertEquals("simulated compensation clear OOM", error.message)
            assertTrue(
                "例外鏈上必含 IllegalStateException(PRIVILEGE_GRANT_REVOKED_CONCURRENTLY)（claim 碼保留），實際=${throwableChain(error)}",
                throwableChain(error).any { it is IllegalStateException && it.message == "PRIVILEGE_GRANT_REVOKED_CONCURRENTLY" },
            )
            assertFalse("commit 窗口並發撤銷 ⇒ 旗標必 false", state.bridgeGranted)
            assertEquals("撤權（hook）後審計表必已清空", 0, state.auditCount())
        } finally {
            state.grantCommitHookForTest = null
        }
    }

    @Test(timeout = 30_000L)
    fun failGrantRevokedConcurrently_compensationClearIllegalState_keepsRevokedConcurrentlyIse() = runBlocking {
        // 補償清庫拋非 VMError／非 CE 的 IllegalStateException（既有行為 pin）：
        // 仍合成 PRIVILEGE_GRANT_REVOKED_CONCURRENTLY（原例外不上拋、不以 suppressed 併入）。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "grant_epilogue_clear_ise.preferences_pb")
        }
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val ioFailure = IllegalStateException("simulated compensation clear failure")
        val flakyDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                if (saveCalls.getAndIncrement() == 0) {
                    return backing.updateData(transform)
                }
                throw ioFailure
            }
        }
        val store = PrivilegeBridgeStore(flakyDs)
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        state.grantCommitHookForTest = { state.revokeInMemoryForTest() }
        try {
            val error = assertThrows(IllegalStateException::class.java) {
                runBlocking { state.grant(store, "preset:openai", 1L) }
            }
            assertEquals("PRIVILEGE_GRANT_REVOKED_CONCURRENTLY", error.message)
            assertFalse("commit 窗口並發撤銷 ⇒ 旗標必 false", state.bridgeGranted)
        } finally {
            state.grantCommitHookForTest = null
        }
    }

    @Test(timeout = 30_000L)
    fun failGrantRevokedConcurrently_compensationClearCancellation_ceRethrownRaw() = runBlocking {
        // 補償清庫於第二次起 updateData 拋 CancellationException：
        // CE 不得被包成 ISE／不得以 logcat-only 吞掉——
        // failGrantRevokedConcurrently 的 catch 原樣上拋 CE（raw：不經
        // PRIVILEGE_GRANT_REVOKED_CONCURRENTLY ISE 包裝、不吞）。
        // harness 同 grantEpilogueCompensationClearHangs_grantDoesNotHang：
        // 第一次 updateData 為 grant 落庫（成功）；其後（補償清庫）拋 CE。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "grant_epilogue_clear_ce.preferences_pb")
        }
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val flakyDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                if (saveCalls.getAndIncrement() == 0) {
                    return backing.updateData(transform)
                }
                throw CancellationException("simulated compensation CE")
            }
        }
        val store = PrivilegeBridgeStore(flakyDs)
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        state.grantCommitHookForTest = { state.revokeInMemoryForTest() }
        try {
            val error = assertThrows(CancellationException::class.java) {
                runBlocking { state.grant(store, "preset:openai", 1L) }
            }
            assertEquals("simulated compensation CE", error.message)
            assertFalse("補償清庫 CE 原樣上拋 ⇒ 旗標必 false", state.bridgeGranted)
        } finally {
            state.grantCommitHookForTest = null
        }
    }

    @Test fun grantCommit_revokeEntryDuringCommit_sealSticks() = runBlocking {
        // 回歸 pin（本次 BLOCK 根因）：grant commit 點緊前模擬「撤銷入口落於 commit 窗口」——
        // hook 僅推進 epoch＋置 seal（不寫旗標、不重入 revoke）。
        // commit RMW 必見 epoch 不符 → 不解封 → failGrantRevokedConcurrently（best-effort
        // clear 失敗亦不覆蓋 seal）→ 拋 PRIVILEGE_GRANT_REVOKED_CONCURRENTLY；
        // 旗標 false；此後 marker-free 全新 store 的 refresh 亦 Invalid（seal sticks）——
        // seal 若被無條件 set(false) 解除，盤上殘留的新授權必使 refresh 回 Valid，本測試必紅。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "grant_commit_revoke_window.preferences_pb")
        }
        val saveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val ioFailure = java.io.IOException("simulated clear IO failure")
        // 第一次 updateData 為 grant 落庫（成功）；之後 clear 路徑的 edit 皆拋 IOException
        // （best-effort 清庫失敗 → 新授權鍵留盤，供 seal 判別）。
        val flakyDs = object : DataStore<Preferences> by backing {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                if (saveCalls.getAndIncrement() == 0) {
                    return backing.updateData(transform)
                }
                throw ioFailure
            }
        }
        val store = PrivilegeBridgeStore(flakyDs)
        val state = PrivilegeBridgeState(bridgeSwitchOn = true)
        state.grantCommitHookForTest = {
            state.incrementRevokeEpochOnlyForTest()
            state.setRevocationSealOnlyForTest()
        }
        try {
            val error = assertThrows(IllegalStateException::class.java) {
                runBlocking { state.grant(store, "preset:openai", 1L) }
            }
            assertEquals("PRIVILEGE_GRANT_REVOKED_CONCURRENTLY", error.message)
        } finally {
            state.grantCommitHookForTest = null
        }
        assertFalse("commit 窗口並發撤銷 ⇒ 不解封、旗標必 false", state.bridgeGranted)
        // 新授權落盤但 seal 維持：marker-free 全新 store 重讀必 Invalid（seal 判別）
        val freshStore = PrivilegeBridgeStore(backing)
        assertEquals(
            "re-grant 落盤的授權必仍在（clear 失敗）",
            "preset:openai",
            freshStore.load()?.providerId,
        )
        val check = state.refresh(freshStore, "preset:openai", 1L)
        assertTrue(check is PrivilegeGrantCheck.Invalid)
        assertEquals(
            PrivilegeGrantPolicy.REASON_NEVER_GRANTED,
            (check as PrivilegeGrantCheck.Invalid).reason,
        )
    }
}
