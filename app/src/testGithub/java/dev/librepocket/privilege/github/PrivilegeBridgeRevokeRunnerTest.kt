package dev.librepocket.privilege.github

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.PrivilegeBridgeState
import dev.librepocket.shell.PrivilegeBridgeStore
import dev.librepocket.shell.PrivilegeGrantCheck
import dev.librepocket.shell.PrivilegeGrantPolicy
import dev.librepocket.shell.ShellDeny
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

    private var curProvider: String? = "provider-a"
    private var curRev: Long? = 1L

    @Before fun setUp() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        dir = kotlin.io.path.createTempDirectory("bridge-revoke-runner-test").toFile()
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
    }

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
        bridgeGranted = { state.bridgeGranted },
        activationCheck = GrantFixtures.activationOn,
        spawn = spawn::spawn,
        suBinary = "/system/bin/su",
        grantCheck = { runBlocking { shizukuStore.check(curProvider, curRev) } },
    )

    private fun shizukuRunner(state: PrivilegeBridgeState, spawn: SpawnCounter) = ShizukuShellRunner(
        privateRoot = privateRoot,
        bridgeGranted = { state.bridgeGranted },
        activationCheck = GrantFixtures.activationOn,
        pingBinder = { true },
        spawn = spawn::spawn,
        status = { ShizukuStatus.Available(15) },
        grantCheck = { runBlocking { shizukuStore.check(curProvider, curRev) } },
    )

    private fun grantBoth(state: PrivilegeBridgeState) = runBlocking {
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
        val state = PrivilegeBridgeState()
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
}
