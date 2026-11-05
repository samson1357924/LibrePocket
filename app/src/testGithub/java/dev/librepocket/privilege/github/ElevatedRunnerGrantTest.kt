package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellResult
import dev.librepocket.tool.Flavor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1-B runner grant 強制測試（Issue #136 R1-B，owner P1-3＋PocketGuard WARN grant）：
 * endpoint 切換/revision 推進/grant clear 經 [grantCheck] 使已建 runner 拒絕且零 spawn。
 *
 * - 兩 runner 各三測：Invalid 拒（至少 endpoint-changed＋never-granted）／抛異常拒／
 *   同實例跨 call 翻轉（首 call Valid 放行、次 call Invalid 零 spawn，證每次 call 新鮮求值）；
 * - 兩 runner 各一全鏈路：FakeGrantStore＋可變 endpoint/revision 供給
 *   `grantCheck = { ShizukuGrantPolicy.check(store.load(), curProvider(), curRev()) }`，
 *   授權後放行→切換 endpoint 次 call 拒→revoke 後再 call 拒，一律零新增 spawn；
 * - U1 執行門（owner 新 P1-1＋P1-2）：bridge off 對無路徑命令（`id`/`getprop`）
 *   仍 Denied 零 spawn（兩 runner×兩命令）；activation=false＋grant Valid＋Available
 *   仍 Denied 零 spawn；bridge true→false 翻轉同一實例次 call 即拒（fresh 證明）；
 *   activation/bridge supplier 抛異常一律 fail-closed Denied 零 spawn；
 * - 全 fake（假進程＋假狀態＋假 su＋記憶體 grant store 同步語義），零真機/真 su/真 binder；
 * - S1 fail-closed：兩 runner `grantCheck` 無預設值（mandatory），本檔全部明示供給；
 *   非 grant 主題測試的 allow 接線見 `GrantFixtures.allowAlways`。
 * - V1 全域 flavor 門（owner 新 P1-1）：PLAY/FOSS × `id`/`getprop` × 兩 runner 共 8 組，
 *   grant/bridge/activation 全 allow 下仍 `Denied(NOT_WHITELISTED, "github-only")` 且零 spawn。
 */
class ElevatedRunnerGrantTest {

    private val privateRoot = "/data/data/dev.librepocket.agent/files"

    private fun shizukuRunner(spawn: SpawnCounter, grantCheck: () -> GrantCheck) = ShizukuShellRunner(
        privateRoot = privateRoot,
        bridgeGranted = GrantFixtures.bridgeOn,
        activationCheck = GrantFixtures.activationOn,
        pingBinder = { true },
        spawn = spawn::spawn,
        status = { ShizukuStatus.Available(15) },
        grantCheck = grantCheck,
    )

    private fun rootRunner(spawn: SpawnCounter, grantCheck: () -> GrantCheck) = RootSuRunner(
        privateRoot = privateRoot,
        bridgeGranted = GrantFixtures.bridgeOn,
        activationCheck = GrantFixtures.activationOn,
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

    // ---- U1 執行門回歸（owner 新 P1-1＋P1-2）：bridge off 全命令＋activation 門＋fresh 翻轉 ----

    @Test fun bridgeOffDeniesPathlessCommandsWithoutSpawn() {
        // U1：bridge off 對一切命令生效，含無路徑的 id/getprop
        //（validate 只把 bridge 傳給路徑 scope，無路徑即 Allowed，故 runner 顯式橋門再擋）。
        // Root & Shizuku × 2 無路徑命令，一律 Denied(NOT_WHITELISTED) 且零 spawn。
        for (argv in listOf(listOf("id"), listOf("getprop"))) {
            var shizukuSpawns = 0
            val shizuku = ShizukuShellRunner(
                privateRoot = privateRoot,
                bridgeGranted = GrantFixtures.bridgeOff,
                activationCheck = GrantFixtures.activationOn,
                pingBinder = { true },
                spawn = {
                    shizukuSpawns++
                    FakeProcess("ok".toByteArray(), ByteArray(0), 0)
                },
                status = { ShizukuStatus.Available(15) },
                grantCheck = GrantFixtures.allowAlways,
            )
            val sb = shizuku.runBinary(ElevatedRequest(argv, "U1-T"), 2_000L)
            assertTrue("$argv -> $sb", sb is ElevatedBinaryResult.Denied)
            assertEquals(ShellDeny.NOT_WHITELISTED, (sb as ElevatedBinaryResult.Denied).reason)
            assertTrue("$argv -> ${sb.message}", sb.message.contains("privilege bridge off"))
            assertEquals(0, shizukuSpawns)

            var rootSpawns = 0
            val root = RootSuRunner(
                privateRoot = privateRoot,
                bridgeGranted = GrantFixtures.bridgeOff,
                activationCheck = GrantFixtures.activationOn,
                spawn = {
                    rootSpawns++
                    FakeProcess("ok".toByteArray(), ByteArray(0), 0)
                },
                suBinary = "/system/bin/su",
                grantCheck = GrantFixtures.allowAlways,
            )
            val rb = root.runBinary(ElevatedRequest(argv, "U1-T"), 2_000L)
            assertTrue("$argv -> $rb", rb is ElevatedBinaryResult.Denied)
            assertEquals(ShellDeny.NOT_WHITELISTED, (rb as ElevatedBinaryResult.Denied).reason)
            assertTrue("$argv -> ${rb.message}", rb.message.contains("privilege bridge off"))
            assertEquals(0, rootSpawns)
        }
    }

    @Test fun activationOffDeniesWithoutSpawn() {
        // U1：activation=false＋grant Valid＋Available 仍 Denied 且零 spawn（兩 runner）。
        // Root 側省略 activationCheck 以鎖定預設 default-deny；Shizuku 側明示 false。
        var shizukuSpawns = 0
        val shizuku = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = { false },
            pingBinder = { true },
            spawn = {
                shizukuSpawns++
                FakeProcess("ok".toByteArray(), ByteArray(0), 0)
            },
            status = { ShizukuStatus.Available(15) },
            grantCheck = GrantFixtures.allowAlways,
        )
        val sr = shizuku.runBinary(request(), 2_000L)
        assertTrue("$sr", sr is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.NOT_WHITELISTED, (sr as ElevatedBinaryResult.Denied).reason)
        assertTrue(sr.message.contains("activation not ready"))
        assertEquals(0, shizukuSpawns)

        var rootSpawns = 0
        val root = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            spawn = {
                rootSpawns++
                FakeProcess("ok".toByteArray(), ByteArray(0), 0)
            },
            suBinary = "/system/bin/su",
            grantCheck = GrantFixtures.allowAlways,
        )
        val rr = root.runBinary(request(), 2_000L)
        assertTrue("$rr", rr is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.NOT_WHITELISTED, (rr as ElevatedBinaryResult.Denied).reason)
        assertTrue(rr.message.contains("activation not ready"))
        assertEquals(0, rootSpawns)
    }

    @Test fun bridgeFlipsAcrossCalls() {
        // U1 fresh 證明：bridge true→false 翻轉，同一實例首 call 放行、次 call 即拒，零新增 spawn。
        var shizukuBridged = true
        var shizukuSpawns = 0
        val shizuku = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = { shizukuBridged },
            activationCheck = GrantFixtures.activationOn,
            pingBinder = { true },
            spawn = {
                shizukuSpawns++
                FakeProcess("ok".toByteArray(), ByteArray(0), 0)
            },
            status = { ShizukuStatus.Available(15) },
            grantCheck = GrantFixtures.allowAlways,
        )
        val sFirst = shizuku.runBinary(request(), 2_000L)
        assertTrue("$sFirst", sFirst is ElevatedBinaryResult.Ok)
        assertEquals(1, shizukuSpawns)
        shizukuBridged = false
        val sSecond = shizuku.runBinary(request(), 2_000L)
        assertTrue("$sSecond", sSecond is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.NOT_WHITELISTED, (sSecond as ElevatedBinaryResult.Denied).reason)
        assertEquals(1, shizukuSpawns)

        var rootBridged = true
        var rootSpawns = 0
        val root = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = { rootBridged },
            activationCheck = GrantFixtures.activationOn,
            spawn = {
                rootSpawns++
                FakeProcess("ok".toByteArray(), ByteArray(0), 0)
            },
            suBinary = "/system/bin/su",
            grantCheck = GrantFixtures.allowAlways,
        )
        val rFirst = root.runBinary(request(), 2_000L)
        assertTrue("$rFirst", rFirst is ElevatedBinaryResult.Ok)
        assertEquals(1, rootSpawns)
        rootBridged = false
        val rSecond = root.runBinary(request(), 2_000L)
        assertTrue("$rSecond", rSecond is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.NOT_WHITELISTED, (rSecond as ElevatedBinaryResult.Denied).reason)
        assertEquals(1, rootSpawns)
    }

    @Test fun gateSupplierThrowDeniesWithoutSpawn() {
        // U1：activationCheck／bridgeGranted 抛異常一律 fail-closed Denied 且零 spawn（兩 runner）。
        val throwing: () -> Boolean = { throw IllegalStateException("gate boom") }
        for (gate in listOf("activation", "bridge")) {
            var shizukuSpawns = 0
            val shizuku = ShizukuShellRunner(
                privateRoot = privateRoot,
                bridgeGranted = if (gate == "bridge") throwing else GrantFixtures.bridgeOn,
                activationCheck = if (gate == "activation") throwing else GrantFixtures.activationOn,
                pingBinder = { true },
                spawn = {
                    shizukuSpawns++
                    FakeProcess("ok".toByteArray(), ByteArray(0), 0)
                },
                status = { ShizukuStatus.Available(15) },
                grantCheck = GrantFixtures.allowAlways,
            )
            val sb = shizuku.runBinary(request(), 2_000L)
            assertTrue("$gate -> $sb", sb is ElevatedBinaryResult.Denied)
            assertEquals(ShellDeny.NOT_WHITELISTED, (sb as ElevatedBinaryResult.Denied).reason)
            assertEquals(0, shizukuSpawns)

            var rootSpawns = 0
            val root = RootSuRunner(
                privateRoot = privateRoot,
                bridgeGranted = if (gate == "bridge") throwing else GrantFixtures.bridgeOn,
                activationCheck = if (gate == "activation") throwing else GrantFixtures.activationOn,
                spawn = {
                    rootSpawns++
                    FakeProcess("ok".toByteArray(), ByteArray(0), 0)
                },
                suBinary = "/system/bin/su",
                grantCheck = GrantFixtures.allowAlways,
            )
            val rb = root.runBinary(request(), 2_000L)
            assertTrue("$gate -> $rb", rb is ElevatedBinaryResult.Denied)
            assertEquals(ShellDeny.NOT_WHITELISTED, (rb as ElevatedBinaryResult.Denied).reason)
            assertEquals(0, rootSpawns)
        }
    }

    @Test fun unsupportedFlavorDeniesWithoutSpawn() {
        // V1 全域 flavor 門（owner 新 P1-1，github-only）：PLAY/FOSS × id/getprop ×
        // Root/Shizuku 共 8 組，一律 Denied(NOT_WHITELISTED, "github-only") 且零 spawn。
        // grant/bridge/activation 全 allow＋status Available＋可信 su，孤立 flavor 變因
        //（validate 只把 flavor 傳給路徑 scope，無路徑命令不靠 validate 擋，須此門兜底）。
        for (flavor in listOf(Flavor.PLAY, Flavor.FOSS)) {
            for (argv in listOf(listOf("id"), listOf("getprop"))) {
                var shizukuSpawns = 0
                val shizuku = ShizukuShellRunner(
                    privateRoot = privateRoot,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = GrantFixtures.activationOn,
                    flavor = flavor,
                    pingBinder = { true },
                    spawn = {
                        shizukuSpawns++
                        FakeProcess("ok".toByteArray(), ByteArray(0), 0)
                    },
                    status = { ShizukuStatus.Available(15) },
                    grantCheck = GrantFixtures.allowAlways,
                )
                val sb = shizuku.runBinary(ElevatedRequest(argv, "V1-T"), 2_000L)
                assertTrue("$flavor $argv -> $sb", sb is ElevatedBinaryResult.Denied)
                assertEquals(ShellDeny.NOT_WHITELISTED, (sb as ElevatedBinaryResult.Denied).reason)
                assertTrue("$flavor $argv -> ${sb.message}", sb.message.contains("github-only"))
                assertEquals(0, shizukuSpawns)

                var rootSpawns = 0
                val root = RootSuRunner(
                    privateRoot = privateRoot,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = GrantFixtures.activationOn,
                    flavor = flavor,
                    spawn = {
                        rootSpawns++
                        FakeProcess("ok".toByteArray(), ByteArray(0), 0)
                    },
                    suBinary = "/system/bin/su",
                    grantCheck = GrantFixtures.allowAlways,
                )
                val rb = root.runBinary(ElevatedRequest(argv, "V1-T"), 2_000L)
                assertTrue("$flavor $argv -> $rb", rb is ElevatedBinaryResult.Denied)
                assertEquals(ShellDeny.NOT_WHITELISTED, (rb as ElevatedBinaryResult.Denied).reason)
                assertTrue("$flavor $argv -> ${rb.message}", rb.message.contains("github-only"))
                assertEquals(0, rootSpawns)
            }
        }
    }
}
