package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * R1-B runner grant 強制測試（Issue #136 R1-B，owner P1-3＋PocketGuard WARN grant）：
 * endpoint 切換/revision 推進/grant clear 經 [grantCheck] 使已建 runner 拒絕且零 spawn。
 *
 * - 兩 runner 各三測：Invalid 拒（至少 endpoint-changed＋never-granted）／抛異常拒／
 *   同實例跨 call 翻轉（首 call Valid 放行、次 call Invalid 零 spawn，證每次 call 新鮮求值）；
 * - 兩 runner 各一全鏈路：FakeGrantStore＋可變 endpoint/revision 供給
 *   `grantCheck = { ShizukuGrantPolicy.check(store.load(), curProvider(), curRev()) }`，
 *   授權後放行→切換 endpoint 次 call 拒→revoke 後再 call 拒，一律零新增 spawn；
 * - 全 fake（假進程＋假狀態＋假 su＋記憶體 grant store 同步語義），零真機/真 su/真 binder；
 * - S1 fail-closed：兩 runner `grantCheck` 無預設值（mandatory），本檔全部明示供給；
 *   非 grant 主題測試的 allow 接線見 `GrantFixtures.allowAlways`。
 */
class ElevatedRunnerGrantTest {

    private val privateRoot = "/data/data/dev.librepocket.agent/files"

    private class FakeProcess(
        private val stdout: ByteArray = "ok".toByteArray(),
        private val stderr: ByteArray = ByteArray(0),
        private val exit: Int = 0,
    ) : Process() {
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(stdout)
        override fun getErrorStream() = ByteArrayInputStream(stderr)
        override fun waitFor(): Int = exit
        override fun exitValue(): Int = exit
        override fun destroy() = Unit
    }

    private class SpawnCounter {
        var calls = 0
        fun spawn(argv: List<String>): Process {
            calls++
            return FakeProcess()
        }
    }

    private fun shizukuRunner(spawn: SpawnCounter, grantCheck: () -> GrantCheck) = ShizukuShellRunner(
        privateRoot = privateRoot,
        bridgeGranted = true,
        pingBinder = { true },
        spawn = spawn::spawn,
        status = { ShizukuStatus.Available(15) },
        grantCheck = grantCheck,
    )

    private fun rootRunner(spawn: SpawnCounter, grantCheck: () -> GrantCheck) = RootSuRunner(
        privateRoot = privateRoot,
        bridgeGranted = true,
        spawn = spawn::spawn,
        suBinary = "/system/bin/su",
        grantCheck = grantCheck,
    )

    private fun request() = ElevatedRequest(listOf("id"), reasonCode = "R1B-T")

    // ---- Shizuku：三測 ----

    @Test fun shizukuGrantInvalidDeniesWithoutSpawn() {
        val reasons = listOf(
            ShizukuGrantPolicy.REASON_ENDPOINT_CHANGED,
            ShizukuGrantPolicy.REASON_NEVER_GRANTED,
            ShizukuGrantPolicy.REASON_REVISION_STALE,
            ShizukuGrantPolicy.REASON_CONFIG_UNREADABLE,
            ShizukuGrantPolicy.REASON_MALFORMED,
        )
        for (reason in reasons) {
            val spawn = SpawnCounter()
            val runner = shizukuRunner(spawn) { GrantCheck.Invalid(reason) }
            val b = runner.runBinary(request(), 2_000L)
            assertTrue("$reason -> $b", b is ElevatedBinaryResult.Denied)
            assertEquals(ShellDeny.NOT_WHITELISTED, (b as ElevatedBinaryResult.Denied).reason)
            assertEquals(reason, b.message)
            assertEquals(0, spawn.calls)
            val t = runner.run(request(), 2_000L)
            assertTrue("$reason -> $t", t is ShellResult.Denied)
            assertEquals(ShellDeny.NOT_WHITELISTED, (t as ShellResult.Denied).reason)
            assertEquals(0, spawn.calls)
        }
    }

    @Test fun shizukuGrantThrowDeniesWithoutSpawn() {
        val spawn = SpawnCounter()
        val runner = shizukuRunner(spawn) { throw IllegalStateException("grant store boom") }
        val b = runner.runBinary(request(), 2_000L)
        assertTrue("$b", b is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.NOT_WHITELISTED, (b as ElevatedBinaryResult.Denied).reason)
        assertEquals(0, spawn.calls)
        val t = runner.run(request(), 2_000L)
        assertTrue("$t", t is ShellResult.Denied)
        assertEquals(0, spawn.calls)
    }

    @Test fun shizukuGrantFlipsAcrossCalls() {
        val spawn = SpawnCounter()
        var valid = true
        val runner = shizukuRunner(spawn) {
            if (valid) GrantCheck.Valid else GrantCheck.Invalid(ShizukuGrantPolicy.REASON_ENDPOINT_CHANGED)
        }
        val first = runner.runBinary(request(), 2_000L)
        assertTrue("$first", first is ElevatedBinaryResult.Ok)
        assertEquals(1, spawn.calls)
        valid = false
        val second = runner.runBinary(request(), 2_000L)
        assertTrue("$second", second is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.NOT_WHITELISTED, (second as ElevatedBinaryResult.Denied).reason)
        assertEquals(1, spawn.calls)
    }

    // ---- Root：三測 ----

    @Test fun rootGrantInvalidDeniesWithoutSpawn() {
        val reasons = listOf(
            ShizukuGrantPolicy.REASON_ENDPOINT_CHANGED,
            ShizukuGrantPolicy.REASON_NEVER_GRANTED,
            ShizukuGrantPolicy.REASON_REVISION_STALE,
            ShizukuGrantPolicy.REASON_CONFIG_UNREADABLE,
            ShizukuGrantPolicy.REASON_MALFORMED,
        )
        for (reason in reasons) {
            val spawn = SpawnCounter()
            val runner = rootRunner(spawn) { GrantCheck.Invalid(reason) }
            val b = runner.runBinary(request(), 2_000L)
            assertTrue("$reason -> $b", b is ElevatedBinaryResult.Denied)
            assertEquals(ShellDeny.NOT_WHITELISTED, (b as ElevatedBinaryResult.Denied).reason)
            assertEquals(reason, b.message)
            assertEquals(0, spawn.calls)
            val t = runner.run(request(), 2_000L)
            assertTrue("$reason -> $t", t is ShellResult.Denied)
            assertEquals(ShellDeny.NOT_WHITELISTED, (t as ShellResult.Denied).reason)
            assertEquals(0, spawn.calls)
        }
    }

    @Test fun rootGrantThrowDeniesWithoutSpawn() {
        val spawn = SpawnCounter()
        val runner = rootRunner(spawn) { throw IllegalStateException("grant store boom") }
        val b = runner.runBinary(request(), 2_000L)
        assertTrue("$b", b is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.NOT_WHITELISTED, (b as ElevatedBinaryResult.Denied).reason)
        assertEquals(0, spawn.calls)
        val t = runner.run(request(), 2_000L)
        assertTrue("$t", t is ShellResult.Denied)
        assertEquals(0, spawn.calls)
    }

    @Test fun rootGrantFlipsAcrossCalls() {
        val spawn = SpawnCounter()
        var valid = true
        val runner = rootRunner(spawn) {
            if (valid) GrantCheck.Valid else GrantCheck.Invalid(ShizukuGrantPolicy.REASON_NEVER_GRANTED)
        }
        val first = runner.runBinary(request(), 2_000L)
        assertTrue("$first", first is ElevatedBinaryResult.Ok)
        assertEquals(1, spawn.calls)
        valid = false
        val second = runner.runBinary(request(), 2_000L)
        assertTrue("$second", second is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.NOT_WHITELISTED, (second as ElevatedBinaryResult.Denied).reason)
        assertEquals(1, spawn.calls)
    }

    // ---- S1 全鏈路：FakeGrantStore＋可變 endpoint/revision（同步語義模擬 DataStore） ----

    /**
     * 記憶體 grant store：同步 save/load/clear，模擬 [ShizukuGrantStore] 的
     * DataStore 語義（load 讀不到即 null，clear 即撤銷），供全鏈路測試驅動
     * `ShizukuGrantPolicy.check` 新鮮求值，不碰產品 suspend 簽名。
     */
    private class FakeGrantStore {
        private var grant: ShizukuGrant? = null
        fun save(grant: ShizukuGrant) {
            this.grant = grant
        }
        fun load(): ShizukuGrant? = grant
        fun clear() {
            grant = null
        }
    }

    @Test fun shizukuGrantLifecycleEndpointSwitchAndRevokeDeniesWithoutSpawn() {
        val store = FakeGrantStore()
        var curProvider: String? = "provider-a"
        var curRev: Long? = 1L
        val spawn = SpawnCounter()
        val runner = shizukuRunner(spawn) {
            ShizukuGrantPolicy.check(store.load(), curProvider, curRev)
        }
        // 授權後放行。
        store.save(ShizukuGrantPolicy.grant("provider-a", 1L, 15))
        val first = runner.runBinary(request(), 2_000L)
        assertTrue("$first", first is ElevatedBinaryResult.Ok)
        assertEquals(1, spawn.calls)
        // 切換 endpoint：同一 runner 次 call 即拒，零新增 spawn。
        curProvider = "provider-b"
        val second = runner.runBinary(request(), 2_000L)
        assertTrue("$second", second is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.NOT_WHITELISTED, (second as ElevatedBinaryResult.Denied).reason)
        assertEquals(ShizukuGrantPolicy.REASON_ENDPOINT_CHANGED, second.message)
        assertEquals(1, spawn.calls)
        // revoke（clear）：再 call 仍拒，零新增 spawn。
        store.clear()
        val third = runner.runBinary(request(), 2_000L)
        assertTrue("$third", third is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.NOT_WHITELISTED, (third as ElevatedBinaryResult.Denied).reason)
        assertEquals(ShizukuGrantPolicy.REASON_NEVER_GRANTED, third.message)
        assertEquals(1, spawn.calls)
    }

    @Test fun rootGrantLifecycleEndpointSwitchAndRevokeDeniesWithoutSpawn() {
        val store = FakeGrantStore()
        var curProvider: String? = "provider-a"
        var curRev: Long? = 1L
        val spawn = SpawnCounter()
        val runner = rootRunner(spawn) {
            ShizukuGrantPolicy.check(store.load(), curProvider, curRev)
        }
        // 授權後放行。
        store.save(ShizukuGrantPolicy.grant("provider-a", 1L, 15))
        val first = runner.runBinary(request(), 2_000L)
        assertTrue("$first", first is ElevatedBinaryResult.Ok)
        assertEquals(1, spawn.calls)
        // 切換 endpoint：同一 runner 次 call 即拒，零新增 spawn。
        curProvider = "provider-b"
        val second = runner.runBinary(request(), 2_000L)
        assertTrue("$second", second is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.NOT_WHITELISTED, (second as ElevatedBinaryResult.Denied).reason)
        assertEquals(ShizukuGrantPolicy.REASON_ENDPOINT_CHANGED, second.message)
        assertEquals(1, spawn.calls)
        // revoke（clear）：再 call 仍拒，零新增 spawn。
        store.clear()
        val third = runner.runBinary(request(), 2_000L)
        assertTrue("$third", third is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.NOT_WHITELISTED, (third as ElevatedBinaryResult.Denied).reason)
        assertEquals(ShizukuGrantPolicy.REASON_NEVER_GRANTED, third.message)
        assertEquals(1, spawn.calls)
    }
}
