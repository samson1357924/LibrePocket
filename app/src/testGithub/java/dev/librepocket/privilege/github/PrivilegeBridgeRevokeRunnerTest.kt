package dev.librepocket.privilege.github

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.PRIVILEGE_GRANT_PROVIDER_ID
import dev.librepocket.shell.PRIVILEGE_REVOKE_JOURNAL
import dev.librepocket.shell.PendingRevokeMarker
import dev.librepocket.shell.PrivilegeBridgeState
import dev.librepocket.shell.PrivilegeBridgeStore
import dev.librepocket.shell.PrivilegeGrantCheck
import dev.librepocket.shell.PrivilegeGrantPolicy
import dev.librepocket.shell.ShellDeny
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import org.junit.Before
import org.junit.Test

/**
 * B1 一鍵收回整合測試（Issue #136 B1）：
 * 檔案級真 DataStore（bridge 授權＋Shizuku 授權）經
 * [PrivilegeBridgeState] 供兩 runner 的 `bridgeGranted`／`grantCheck`，
 * 收回後次 call 即 typed Denied 且零新增 spawn。
 *
 * - 全 fake 進程（[SpawnCounter]＋[FakeProcess]）＋假狀態，零真機/真 su/真 binder；
 * - activation 明示 allow（`GrantFixtures.activationOn`，測試 fixture，
 *   見 `PrivilegeActivation` KDoc：產品傳 `{ true }` 即審查紅線）；
 * - `ELEVATED_RUNNERS_READY` 維持 `false`，本檔不斷言翻旗，只證收回語義。
 */
class PrivilegeBridgeRevokeRunnerTest {

    private val privateRoot = "/data/data/dev.librepocket.agent/files"

    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private lateinit var bridgeStore: PrivilegeBridgeStore
    private lateinit var shizukuStore: ShizukuGrantStore
    private var hermeticExec: HermeticExecFixture? = null

    private var curProvider: String? = "provider-a"
    private var curRev: Long? = 1L

    @Before fun setUp() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        dir = kotlin.io.path.createTempDirectory("bridge-revoke-runner-test").toFile()
        // Hermetic 可信 executable fixture（`id`）：searchDirs/allowedRoots/systemOwned
        // 顯式注入兩 runner，不依賴宿主 /usr/bin/id 版面（symlink→containment 外），
        // 本機與 CI ubuntu-latest 皆綠。
        hermeticExec = HermeticExecFixture("bridge-revoke-hermetic", "id")
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        bridgeStore = PrivilegeBridgeStore(
            PreferenceDataStoreFactory.create(scope = scope) { File(dir, "bridge.preferences_pb") },
        )
        shizukuStore = ShizukuGrantStore(
            PreferenceDataStoreFactory.create(scope = scope) { File(dir, "shizuku.preferences_pb") },
        )
    }

    @After fun tearDown() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        scope.cancel()
        dir.deleteRecursively()
        hermeticExec?.close()
        hermeticExec = null
    }

    /**
     * 輪詢 [ElevatedCleanupTracker.outstanding] 至 [expected]；有界 10s deadline
     * （超出即以 AssertionError 失敗，絕不無界等待）。
     *
     * 環境相依註記：`ElevatedCleanupTracker` 為 JVM 內共享 statics；本模組單測
     * 未強制 fork-per-class，故與同 fork 內 cleanup-tracker 系測試的相對順序為
     * 環境相依（例如是否先於 `ElevatedCleanupTrackerTest` 執行皆合法）。可接受——
     * 輪詢有 deadline 上界保護，且 setUp／tearDown 皆以 `clearUnverified()`＋
     * `noteClean()` 重置為 0，跨測試不殘留漂移。
     */
    private fun pollOutstandingTo(expected: Int, boundMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + boundMs
        while (ElevatedCleanupTracker.outstanding() != expected) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError(
                    "outstanding=${ElevatedCleanupTracker.outstanding()} expected=$expected",
                )
            }
            Thread.sleep(50L)
        }
    }

    /**
     * 輪詢 [ElevatedCleanupTracker.activeCalls] 至 [expected]；有界 10s deadline
     * （超出即以 AssertionError 失敗，絕不無界等待）。
     *
     * 環境相依註記：`ElevatedCleanupTracker` 為 JVM 內共享 statics；本模組單測
     * 未強制 fork-per-class，故與同 fork 內 cleanup-tracker 系測試的相對順序為
     * 環境相依（例如是否先於 `ElevatedCleanupTrackerTest` 執行皆合法）。可接受——
     * 輪詢有 deadline 上界保護，且 setUp／tearDown 皆以 `clearUnverified()`＋
     * `noteClean()` 重置為 0，跨測試不殘留漂移。
     */
    private fun pollActiveTo(expected: Int, boundMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + boundMs
        while (ElevatedCleanupTracker.activeCalls() != expected) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError(
                    "active=${ElevatedCleanupTracker.activeCalls()} expected=$expected",
                )
            }
            Thread.sleep(20L)
        }
    }

    private fun request() = ElevatedRequest(listOf("id"), "B1-REVOKE-T")

    private fun rootRunner(state: PrivilegeBridgeState, spawn: SpawnCounter) = RootSuRunner(
        privateRoot = privateRoot,
        bridgeGranted = { state.bridgeEffective },
        activationCheck = GrantFixtures.activationOn,
        spawn = spawn::spawn,
        suBinary = "/system/bin/su",
        grantCheck = { runBlocking { shizukuStore.check(curProvider, curRev) } },
        execSearchDirs = hermeticExec!!.searchDirs,
        execAllowedRoots = hermeticExec!!.allowedRoots,
        execIsWritable = hermeticExec!!.systemOwned,
    )

    private fun shizukuRunner(state: PrivilegeBridgeState, spawn: SpawnCounter) = ShizukuShellRunner(
        privateRoot = privateRoot,
        bridgeGranted = { state.bridgeEffective },
        activationCheck = GrantFixtures.activationOn,
        pingBinder = { true },
        spawn = spawn::spawn,
        status = { ShizukuStatus.Available(15) },
        grantCheck = { runBlocking { shizukuStore.check(curProvider, curRev) } },
        execSearchDirs = hermeticExec!!.searchDirs,
        execAllowedRoots = hermeticExec!!.allowedRoots,
        execIsWritable = hermeticExec!!.systemOwned,
    )

    private fun grantBoth(state: PrivilegeBridgeState) = runBlocking {
        state.setBridgeSwitch(true)
        state.grant(bridgeStore, "provider-a", 1L)
        shizukuStore.save(ShizukuGrantPolicy.grant("provider-a", 1L, 15))
    }

    @Test fun rootFullRevokeDeniesWithoutSpawn() = runBlocking {
        val state = PrivilegeBridgeState()
        grantBoth(state)
        val spawn = SpawnCounter()
        val runner = rootRunner(state, spawn)
        val first = runner.runBinary(request(), 2_000L)
        assertTrue("$first", first is ElevatedBinaryResult.Ok)
        assertEquals(1, spawn.calls)

        // 一鍵收回：關 bridge＋清審計＋撤 scope（此處 scope＝Shizuku 授權 Store）。
        state.audit.record(listOf("id"), "B1-x", "DENY")
        val cleared = state.revoke(bridgeStore) { shizukuStore.clear() }
        assertEquals(1, cleared)
        assertNull(bridgeStore.load())
        assertNull(shizukuStore.load())

        val second = runner.runBinary(request(), 2_000L)
        assertTrue("$second", second is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.NOT_WHITELISTED, (second as ElevatedBinaryResult.Denied).reason)
        assertEquals(ShizukuGrantPolicy.REASON_NEVER_GRANTED, second.message)
        assertEquals(1, spawn.calls)
    }

    @Test fun rootBridgeOnlyRevokeDeniesAtBridgeGate() = runBlocking {
        val state = PrivilegeBridgeState()
        grantBoth(state)
        val spawn = SpawnCounter()
        val runner = rootRunner(state, spawn)
        val first = runner.runBinary(request(), 2_000L)
        assertTrue("$first", first is ElevatedBinaryResult.Ok)
        assertEquals(1, spawn.calls)

        // 只收回 bridge（Shizuku 授權仍有效）：橋門先拒，原因碼 pin。
        state.revoke(bridgeStore)
        assertTrue(shizukuStore.check(curProvider, curRev) is GrantCheck.Valid)

        val second = runner.runBinary(request(), 2_000L)
        assertTrue("$second", second is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.NOT_WHITELISTED, (second as ElevatedBinaryResult.Denied).reason)
        assertTrue("${second.message}", second.message.contains("privilege bridge off"))
        assertEquals(1, spawn.calls)
    }

    @Test fun rootGrantOnlyRevokeDeniesAtGrantGate() = runBlocking {
        val state = PrivilegeBridgeState()
        grantBoth(state)
        val spawn = SpawnCounter()
        val runner = rootRunner(state, spawn)
        val first = runner.runBinary(request(), 2_000L)
        assertTrue("$first", first is ElevatedBinaryResult.Ok)
        assertEquals(1, spawn.calls)

        // 只清 Shizuku 授權（bridge 旗仍在）：grant 門先拒。
        shizukuStore.clear()
        val second = runner.runBinary(request(), 2_000L)
        assertTrue("$second", second is ElevatedBinaryResult.Denied)
        assertEquals(ShizukuGrantPolicy.REASON_NEVER_GRANTED, (second as ElevatedBinaryResult.Denied).message)
        assertEquals(1, spawn.calls)
    }

    @Test fun shizukuFullRevokeDeniesWithoutSpawn() = runBlocking {
        val state = PrivilegeBridgeState()
        grantBoth(state)
        val spawn = SpawnCounter()
        val runner = shizukuRunner(state, spawn)
        val first = runner.runBinary(request(), 2_000L)
        assertTrue("$first", first is ElevatedBinaryResult.Ok)
        assertEquals(1, spawn.calls)

        state.revoke(bridgeStore) { shizukuStore.clear() }
        assertNull(bridgeStore.load())
        assertNull(shizukuStore.load())

        val second = runner.runBinary(request(), 2_000L)
        assertTrue("$second", second is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.NOT_WHITELISTED, (second as ElevatedBinaryResult.Denied).reason)
        assertEquals(ShizukuGrantPolicy.REASON_NEVER_GRANTED, (second as ElevatedBinaryResult.Denied).message)
        assertEquals(1, spawn.calls)
    }

    @Test fun shizukuBridgeOnlyRevokeDeniesAtBridgeGate() = runBlocking {
        val state = PrivilegeBridgeState()
        grantBoth(state)
        val spawn = SpawnCounter()
        val runner = shizukuRunner(state, spawn)
        val first = runner.runBinary(request(), 2_000L)
        assertTrue("$first", first is ElevatedBinaryResult.Ok)
        assertEquals(1, spawn.calls)

        state.revoke(bridgeStore)
        val second = runner.runBinary(request(), 2_000L)
        assertTrue("$second", second is ElevatedBinaryResult.Denied)
        assertTrue("${(second as ElevatedBinaryResult.Denied).message}", second.message.contains("privilege bridge off"))
        assertEquals(1, spawn.calls)
    }

    @Test fun bridgeExpiryRefreshDeniesWithoutSpawn() = runBlocking {
        val state = PrivilegeBridgeState(bridgeSwitchOn = true)
        // bridge 授權 1s 時效，Shizuku 授權不過期。
        state.grant(bridgeStore, "provider-a", 1L, ttlMs = 1_000L, atMs = 1_700_000_000_000L)
        shizukuStore.save(ShizukuGrantPolicy.grant("provider-a", 1L, 15))
        val spawn = SpawnCounter()
        val runner = rootRunner(state, spawn)

        // 到期前 refresh：放行。
        val fresh = state.refresh(bridgeStore, "provider-a", 1L, nowMs = 1_700_000_000_000L)
        assertTrue("$fresh", fresh is PrivilegeGrantCheck.Valid)
        val first = runner.runBinary(request(), 2_000L)
        assertTrue("$first", first is ElevatedBinaryResult.Ok)
        assertEquals(1, spawn.calls)

        // 到期後 refresh：旗標落下，次 call 橋門拒且零新增 spawn。
        val expired = state.refresh(bridgeStore, "provider-a", 1L, nowMs = 1_700_000_001_000L)
        assertTrue("$expired", expired is PrivilegeGrantCheck.Invalid)
        assertEquals(PrivilegeGrantPolicy.REASON_EXPIRED, (expired as PrivilegeGrantCheck.Invalid).reason)
        val second = runner.runBinary(request(), 2_000L)
        assertTrue("$second", second is ElevatedBinaryResult.Denied)
        assertTrue("${(second as ElevatedBinaryResult.Denied).message}", second.message.contains("privilege bridge off"))
        assertEquals(1, spawn.calls)
    }

    @Test fun switchOffWithGrantActiveDeniesWithoutSpawn() = runBlocking {
        val state = PrivilegeBridgeState(bridgeSwitchOn = false)
        // Persist grants and update bridgeGranted to true
        state.grant(bridgeStore, "provider-a", 1L)
        shizukuStore.save(ShizukuGrantPolicy.grant("provider-a", 1L, 15))
        assertTrue(state.bridgeGranted)
        assertFalse(state.bridgeSwitchOn)
        assertFalse("bridgeEffective must be false when switch is off", state.bridgeEffective)

        val spawn = SpawnCounter()
        val root = rootRunner(state, spawn)
        val rResult = root.runBinary(request(), 2_000L)
        assertTrue("$rResult", rResult is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.NOT_WHITELISTED, (rResult as ElevatedBinaryResult.Denied).reason)
        assertTrue("${rResult.message}", rResult.message.contains("privilege bridge off"))
        assertEquals("zero spawn required when bridgeSwitchOn is false", 0, spawn.calls)

        val shizuku = shizukuRunner(state, spawn)
        val sResult = shizuku.runBinary(request(), 2_000L)
        assertTrue("$sResult", sResult is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.NOT_WHITELISTED, (sResult as ElevatedBinaryResult.Denied).reason)
        assertTrue("${sResult.message}", sResult.message.contains("privilege bridge off"))
        assertEquals("zero spawn required when bridgeSwitchOn is false", 0, spawn.calls)
    }

    /**
     * clear() 的 updateData 失敗注入 DataStore 包裝：
     * [failNextUpdate] 連續呼叫可累計多個待注入失敗（B1 雙重失敗案：journal 寫入＋best-effort wipe 擦除各一次）；
     * [failOnUpdateDataCall] 指定絕對呼叫次序（1-based）失敗一次（B2 erase-only 案：第 3 次 updateData＝erase edit）。
     */
    private class FailOnceClearDataStore(
        private val backing: DataStore<Preferences>,
    ) : DataStore<Preferences> by backing {
        private val remainingFailures = java.util.concurrent.atomic.AtomicInteger(0)
        private val failOnCall = java.util.concurrent.atomic.AtomicInteger(-1)
        private val callCounter = java.util.concurrent.atomic.AtomicInteger(0)

        /** 讓接下來一次 updateData 呼叫拋 [IOException]（連續呼叫則累計多個待注入失敗）。 */
        fun failNextUpdate() {
            remainingFailures.incrementAndGet()
        }

        /** 讓第 [n] 次 updateData 呼叫（1-based，自本 DataStore 建立起計）拋 [IOException] 僅一次。 */
        fun failOnUpdateDataCall(n: Int) {
            failOnCall.set(n)
        }

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            val call = callCounter.incrementAndGet()
            if (failOnCall.get() == call && failOnCall.compareAndSet(call, -1)) {
                throw IOException("fail-once simulated clear failure (call #$call)")
            }
            if (remainingFailures.get() > 0) {
                remainingFailures.decrementAndGet()
                throw IOException("fail-once simulated clear failure")
            }
            return backing.updateData(transform)
        }
    }

    /**
     * 註（#136-B1 假件分歧）：本案例的 failingStore 是與 grant 所在 bridgeStore
     * 完全不同的 fake DataStore——其 clear() 失敗不會在 bridgeStore 落下
     * marker/journal，因此本案例無法證明「持久撤權失敗後可跨重啟 fail-closed」，
     * 只釘住記憶體旗標收回與 in-memory 拒絕行為。跨重啟語義由
     * [failedPersistentRevoke_markerPersists_restartRefreshDeniedAndZeroSpawn]
     * 以真 Store＋file-backed marker＋fail-once DataStore 覆蓋；舊案例保留作
     * 記憶體 fail-closed 釘住，不刪除。
     */
    @Test fun postFailedRevokeZeroSpawn() = runBlocking {
        val state = PrivilegeBridgeState(bridgeSwitchOn = true)
        grantBoth(state)
        val spawn = SpawnCounter()
        val root = rootRunner(state, spawn)
        val shizuku = shizukuRunner(state, spawn)

        val first = root.runBinary(request(), 2_000L)
        assertTrue(first is ElevatedBinaryResult.Ok)
        assertEquals(1, spawn.calls)

        // Attempt revoke with a failing store: clear throws, but in-memory state is dropped
        val failingStore = PrivilegeBridgeStore(object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { emit(androidx.datastore.preferences.core.emptyPreferences()) }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                throw IOException("simulated disk error during clear")
            }
        })

        try {
            state.revoke(failingStore)
            org.junit.Assert.fail("revoke must throw when store clear fails")
        } catch (_: IOException) {
            // expected
        }

        assertFalse("bridgeGranted must be false even after failed revoke", state.bridgeGranted)
        assertFalse("bridgeSwitchOn must be false even after failed revoke", state.bridgeSwitchOn)
        assertFalse(state.bridgeEffective)

        val rResult = root.runBinary(request(), 2_000L)
        assertTrue(rResult is ElevatedBinaryResult.Denied)
        val rDenied = rResult as ElevatedBinaryResult.Denied
        assertTrue(rDenied.message.contains("privilege bridge off"))
        assertEquals("zero spawn after failed revoke", 1, spawn.calls)

        val sResult = shizuku.runBinary(request(), 2_000L)
        assertTrue(sResult is ElevatedBinaryResult.Denied)
        val sDenied = sResult as ElevatedBinaryResult.Denied
        assertTrue(sDenied.message.contains("privilege bridge off"))
        assertEquals("zero spawn after failed revoke", 1, spawn.calls)
    }

    @Test fun failedPersistentRevoke_markerPersists_restartRefreshDeniedAndZeroSpawn() = runBlocking {
        // 真 PrivilegeBridgeStore＋file-backed PendingRevokeMarker＋fail-once DataStore：
        // 持久撤權的 journal 寫入第一次失敗 → revoke 拋錯（fail-closed），但 marker 已落盤；
        // 「重啟」（新 State＋fresh Store＋fresh file-backed marker）後 load() 見 marker 自癒擦除 → refresh Invalid(NEVER_GRANTED)
        // → runner Denied 且零 spawn（失敗撤權不得跨重啟死灰復燃）。
        val bridgeBacking = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "bridge_fail_once.preferences_pb")
        }
        val failingDataStore = FailOnceClearDataStore(bridgeBacking)
        val markerFile = File(dir, "bridge_fail_once.pending")
        val failingStore = PrivilegeBridgeStore(
            failingDataStore,
            revokeMarker = PendingRevokeMarker(markerFile),
        )

        val state = PrivilegeBridgeState(bridgeSwitchOn = true)
        state.grant(failingStore, "provider-a", 1L)
        shizukuStore.save(ShizukuGrantPolicy.grant("provider-a", 1L, 15))

        val spawn = SpawnCounter()
        val root = rootRunner(state, spawn)
        val first = root.runBinary(request(), 2_000L)
        assertTrue(first is ElevatedBinaryResult.Ok)
        assertEquals(1, spawn.calls)

        // journal 寫入 fail-once：revoke 必須拋錯（fail-closed），marker 必須已落盤。
        failingDataStore.failNextUpdate()
        try {
            state.revoke(failingStore)
            org.junit.Assert.fail("revoke must throw when store clear fails")
        } catch (e: IOException) {
            // expected：fail-once 的 journal 寫入失敗 → 原始 IOException 重拋（fail-closed）
        }
        assertFalse("bridgeGranted 必已落 false（記憶體 fail-closed）", state.bridgeGranted)
        assertFalse("bridgeSwitchOn 必已關閉（記憶體 fail-closed）", state.bridgeSwitchOn)
        assertTrue("marker 必須已落盤（重啟防死灰復燃的持久兜底）", markerFile.isFile)

        // 模擬重啟：新 State＋fresh Store＋fresh file-backed PendingRevokeMarker（檔案同一路徑），
        // file-only 隔離（不繼承原 Store 的記憶體 pending 旗標）。
        val restartedStore = PrivilegeBridgeStore(
            failingDataStore,
            revokeMarker = PendingRevokeMarker(markerFile),
        )
        val restarted = PrivilegeBridgeState(bridgeSwitchOn = true)
        val check = restarted.refresh(restartedStore, "provider-a", 1L)
        assertTrue("$check", check is PrivilegeGrantCheck.Invalid)
        assertEquals(
            PrivilegeGrantPolicy.REASON_NEVER_GRANTED,
            (check as PrivilegeGrantCheck.Invalid).reason,
        )
        assertFalse("重啟後 refresh 必判未授權", restarted.bridgeGranted)
        assertFalse("load() 自癒 heal 必刪除 marker 檔", markerFile.exists())

        val spawn2 = SpawnCounter()
        val rootAfterRestart = rootRunner(restarted, spawn2)
        val denied = rootAfterRestart.runBinary(request(), 2_000L)
        assertTrue(denied is ElevatedBinaryResult.Denied)
        assertEquals("重啟後 runner 必零 spawn", 0, spawn2.calls)
    }

    @Test fun failedClearWithJournalAndWipeFailure_restartDeniedAndZeroSpawn() = runBlocking {
        // 雙重失敗（security P2-1／test P2-3）：journal 寫入失敗＋best-effort wipe 擦除亦失敗
        // → revoke 拋 IOException；marker 已落盤（檔案分支兜底），backing 中 grant 鍵仍在盤上
        // （marker heal 之前）；「重啟」（新 State＋fresh Store＋fresh file-backed marker）後
        // refresh→Invalid(NEVER_GRANTED)；load() 見 marker（無條件擦除分支）擦掉 grant 鍵＋刪 marker；
        // runner Denied 且零 spawn（失敗撤權不得跨重啟死灰復燃）。
        // 註：Denied 訊息斷言經 hermetic fixture（注入 execSearchDirs/execAllowedRoots/
        // execIsWritable）下的 id resolve 到達橋門；與宿主 coreutils 版面無關，本機與 CI 皆綠。
        val bridgeBacking = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "bridge_fail_twice.preferences_pb")
        }
        val failingDataStore = FailOnceClearDataStore(bridgeBacking)
        val markerFile = File(dir, "bridge_fail_twice.pending")
        val failingStore = PrivilegeBridgeStore(
            failingDataStore,
            revokeMarker = PendingRevokeMarker(markerFile),
        )

        val state = PrivilegeBridgeState(bridgeSwitchOn = true)
        state.grant(failingStore, "provider-a", 1L)
        shizukuStore.save(ShizukuGrantPolicy.grant("provider-a", 1L, 15))

        // 連續兩次 fail-once：journal 寫入（第 2 次 updateData）＋best-effort wipe 擦除（第 3 次）皆失敗。
        failingDataStore.failNextUpdate()
        failingDataStore.failNextUpdate()
        try {
            state.revoke(failingStore)
            org.junit.Assert.fail("revoke must throw when journal write and wipe both fail")
        } catch (_: IOException) {
            // expected：fail-once 的 journal 寫入失敗 → 原始 IOException 重拋（fail-closed）
        }
        assertFalse("bridgeGranted 必已落 false（記憶體 fail-closed）", state.bridgeGranted)
        assertFalse("bridgeSwitchOn 必已關閉（記憶體 fail-closed）", state.bridgeSwitchOn)
        assertTrue("marker 必須已落盤（重啟防死灰復燃的持久兜底）", markerFile.isFile)

        // 直接驗 backing 中 grant 鍵仍在盤上（marker heal 之前）——雙重失敗殘留證據。
        val prefsBefore = bridgeBacking.data.first()
        assertEquals(
            "journal＋wipe 雙失敗時 grant 鍵必須仍在盤上",
            "provider-a",
            prefsBefore[PRIVILEGE_GRANT_PROVIDER_ID],
        )

        // 模擬重啟：新 State＋fresh Store＋fresh file-backed marker（檔案同一路徑），file-only 隔離。
        val restartedStore = PrivilegeBridgeStore(
            failingDataStore,
            revokeMarker = PendingRevokeMarker(markerFile),
        )
        val restarted = PrivilegeBridgeState(bridgeSwitchOn = true)
        val check = restarted.refresh(restartedStore, "provider-a", 1L)
        assertTrue("$check", check is PrivilegeGrantCheck.Invalid)
        assertEquals(
            PrivilegeGrantPolicy.REASON_NEVER_GRANTED,
            (check as PrivilegeGrantCheck.Invalid).reason,
        )
        assertFalse("重啟後 refresh 必判未授權", restarted.bridgeGranted)
        assertFalse("load() marker heal 必刪除 marker 檔", markerFile.exists())
        val prefsAfter = bridgeBacking.data.first()
        assertNull("marker heal 必擦除 backing grant 鍵", prefsAfter[PRIVILEGE_GRANT_PROVIDER_ID])

        val spawn2 = SpawnCounter()
        val rootAfterRestart = rootRunner(restarted, spawn2)
        val denied = rootAfterRestart.runBinary(request(), 2_000L)
        assertTrue("$denied", denied is ElevatedBinaryResult.Denied)
        // 零 spawn 與環境無關（resolve 失敗亦零 spawn）——先驗。
        assertEquals("重啟後 runner 必零 spawn", 0, spawn2.calls)
        // production Denied 字串（RootSuRunner「privilege bridge off (fail-closed, no spawn)」）：
        // bridge 門拒（reason＝ShellDeny.NOT_WHITELISTED）。經 hermetic fixture
        // 的 id resolve 到達橋門（與宿主版面無關，本機／CI 皆綠）。
        assertEquals(ShellDeny.NOT_WHITELISTED, (denied as ElevatedBinaryResult.Denied).reason)
        assertTrue("${denied.message}", denied.message.contains("privilege bridge off"))
    }

    @Test fun journalResidual_restartHealDeniedAndZeroSpawn() = runBlocking {
        // fail-erase-only 失敗分支（journal 成功、第 3 次 updateData＝erase edit 失敗）：
        // journal==true 殘留＋grant 鍵殘留；marker 落盤後模擬遺失（noBackupFilesDir 被清/卸載），
        // 「重啟」時 isPending()==false → load() 走 journal 條件式 heal（journal==true 才擦），
        // 擦除 grant 鍵與 journal → refresh Invalid(NEVER_GRANTED) → runner Denied 且零 spawn。
        // 計數：grant save＝第 1 次 updateData、journal 寫入＝第 2 次、erase 擦除＝第 3 次（本案只注入第 3 次失敗）。
        // 註：Denied 訊息斷言經 hermetic fixture（注入 execSearchDirs/execAllowedRoots/
        // execIsWritable）下的 id resolve 到達橋門；與宿主 coreutils 版面無關，本機與 CI 皆綠。
        val bridgeBacking = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "bridge_erase_fail.preferences_pb")
        }
        val failingDataStore = FailOnceClearDataStore(bridgeBacking)
        val markerFile = File(dir, "bridge_erase_fail.pending")
        val failingStore = PrivilegeBridgeStore(
            failingDataStore,
            revokeMarker = PendingRevokeMarker(markerFile),
        )

        val state = PrivilegeBridgeState(bridgeSwitchOn = true)
        state.grant(failingStore, "provider-a", 1L)
        shizukuStore.save(ShizukuGrantPolicy.grant("provider-a", 1L, 15))

        // journal 寫入（第 2 次）成功；第 3 次 updateData（erase edit）fail-once。
        failingDataStore.failOnUpdateDataCall(3)
        try {
            state.revoke(failingStore)
            org.junit.Assert.fail("revoke must throw when the erase edit fails")
        } catch (_: IOException) {
            // expected：erase edit 失敗 → 原始 IOException 重拋（fail-closed）
        }
        // journal 必已落盤：erase-only 失敗時 journal 與 grant 鍵皆殘留於盤上（重啟 heal 的前提）。
        val prefsAfterRevoke = bridgeBacking.data.first()
        assertEquals(
            "erase-only 失敗時 journal 必須殘留於盤上",
            true,
            prefsAfterRevoke[PRIVILEGE_REVOKE_JOURNAL],
        )
        assertEquals(
            "erase-only 失敗時 grant 鍵必須殘留於盤上",
            "provider-a",
            prefsAfterRevoke[PRIVILEGE_GRANT_PROVIDER_ID],
        )
        assertTrue("marker 必須已落盤", markerFile.isFile)

        // 模擬 marker 遺失：僅 journal 殘留 → 重啟時走 journal 條件式 heal 分支。
        assertTrue("模擬 marker 遺失（刪除標記檔）", markerFile.delete())

        // 模擬重啟：新 State＋fresh Store＋fresh file-backed marker（檔案同一路徑，已不存在），file-only 隔離。
        val restartedStore = PrivilegeBridgeStore(
            failingDataStore,
            revokeMarker = PendingRevokeMarker(markerFile),
        )
        val restarted = PrivilegeBridgeState(bridgeSwitchOn = true)
        val check = restarted.refresh(restartedStore, "provider-a", 1L)
        assertTrue("$check", check is PrivilegeGrantCheck.Invalid)
        assertEquals(
            PrivilegeGrantPolicy.REASON_NEVER_GRANTED,
            (check as PrivilegeGrantCheck.Invalid).reason,
        )
        assertFalse("重啟後 refresh 必判未授權", restarted.bridgeGranted)
        assertFalse("fresh marker 不得被無中生有", markerFile.exists())
        val prefsAfter = bridgeBacking.data.first()
        assertNull("journal heal 必擦除 backing grant 鍵", prefsAfter[PRIVILEGE_GRANT_PROVIDER_ID])
        assertNull("journal heal 必擦除 journal 鍵", prefsAfter[PRIVILEGE_REVOKE_JOURNAL])

        val spawn2 = SpawnCounter()
        val rootAfterRestart = rootRunner(restarted, spawn2)
        val denied = rootAfterRestart.runBinary(request(), 2_000L)
        assertTrue("$denied", denied is ElevatedBinaryResult.Denied)
        // 零 spawn 與環境無關（resolve 失敗亦零 spawn）——先驗。
        assertEquals("重啟後 runner 必零 spawn", 0, spawn2.calls)
        // production Denied 字串（RootSuRunner「privilege bridge off (fail-closed, no spawn)」）：
        // bridge 門拒（reason＝ShellDeny.NOT_WHITELISTED）。經 hermetic fixture
        // 的 id resolve 到達橋門（與宿主版面無關，本機／CI 皆綠）。
        assertEquals(ShellDeny.NOT_WHITELISTED, (denied as ElevatedBinaryResult.Denied).reason)
        assertTrue("${denied.message}", denied.message.contains("privilege bridge off"))
    }

    @Test fun shizukuExpiryRefreshDeniesWithoutSpawn() = runBlocking {
        val state = PrivilegeBridgeState(bridgeSwitchOn = true)
        state.grant(bridgeStore, "provider-a", 1L, ttlMs = 1_000L, atMs = 1_700_000_000_000L)
        shizukuStore.save(ShizukuGrantPolicy.grant("provider-a", 1L, 15))
        val spawn = SpawnCounter()
        val runner = shizukuRunner(state, spawn)

        val fresh = state.refresh(bridgeStore, "provider-a", 1L, nowMs = 1_700_000_000_000L)
        assertTrue("$fresh", fresh is PrivilegeGrantCheck.Valid)
        val first = runner.runBinary(request(), 2_000L)
        assertTrue("$first", first is ElevatedBinaryResult.Ok)
        assertEquals(1, spawn.calls)

        val expired = state.refresh(bridgeStore, "provider-a", 1L, nowMs = 1_700_000_001_000L)
        assertTrue("$expired", expired is PrivilegeGrantCheck.Invalid)
        assertEquals(PrivilegeGrantPolicy.REASON_EXPIRED, (expired as PrivilegeGrantCheck.Invalid).reason)
        val second = runner.runBinary(request(), 2_000L)
        assertTrue("$second", second is ElevatedBinaryResult.Denied)
        assertTrue("${(second as ElevatedBinaryResult.Denied).message}", second.message.contains("privilege bridge off"))
        assertEquals(1, spawn.calls)
    }
}
