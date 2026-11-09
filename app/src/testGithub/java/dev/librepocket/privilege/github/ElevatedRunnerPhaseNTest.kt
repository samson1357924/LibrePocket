package dev.librepocket.privilege.github

import dev.librepocket.router.ElevatedDispatch
import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ElevatedShellRunner
import dev.librepocket.shell.PrivilegeAuditLog
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellQuota
import dev.librepocket.shell.ShellResult
import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.Projection
import dev.librepocket.tool.ToolRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round-4 Phase N（P2-2）root／shizuku 多路 pin（PR #140）：
 * 超限 reasonCode（>512 UTF-8 位元組）經 [ElevatedDispatch] 在 runner 之前
 * fail-closed——真實 [RootSuRunner]／[ShizukuShellRunner]（全 allow 夾具＋
 * 計數 spawn）皆零 spawn、一筆 DENY 審計（審計不哈希無界輸入）。
 *
 * runner 本體不動（E/F/G/H/M/L 已定）：超限在 main 側入口即拒，
 * runner 的 `reasonSnapshot.isBlank()` 門恆不可達超限輸入（縱深：入口＋
 * `PrivilegeAuditLog.record` 雙門）。
 *
 * 全 JVM（假子進程注入，不碰真實 binder/su）。
 */
class ElevatedRunnerPhaseNTest {

    private var hermeticExec: HermeticExecFixture? = null

    @Before fun setUp() {
        hermeticExec = HermeticExecFixture("phaseN-hermetic", "dumpsys", "getprop", "id")
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
    }

    @After fun tearDown() {
        hermeticExec?.close()
        hermeticExec = null
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
    }

    private fun shizukuRunner(spawn: SpawnCounter) = ShizukuShellRunner(
        privateRoot = "/data/data/dev.librepocket.agent/files",
        quota = ShellQuota(),
        bridgeGranted = GrantFixtures.bridgeOn,
        activationCheck = GrantFixtures.activationOn,
        pingBinder = { true },
        spawn = spawn::spawn,
        status = { ShizukuStatus.Available(15) },
        grantCheck = GrantFixtures.allowAlways,
        execSearchDirs = hermeticExec!!.searchDirs,
        execAllowedRoots = hermeticExec!!.allowedRoots,
        execIsWritable = hermeticExec!!.systemOwned,
    )

    private fun rootRunner(spawn: SpawnCounter) = RootSuRunner(
        privateRoot = "/data/data/dev.librepocket.agent/files",
        quota = ShellQuota(),
        bridgeGranted = GrantFixtures.bridgeOn,
        activationCheck = GrantFixtures.activationOn,
        spawn = spawn::spawn,
        suBinary = "/system/bin/su",
        grantCheck = GrantFixtures.allowAlways,
        execSearchDirs = hermeticExec!!.searchDirs,
        execAllowedRoots = hermeticExec!!.allowedRoots,
        execIsWritable = hermeticExec!!.systemOwned,
    )

    @Test fun overLongReasonDeniedBeforeSpawnOnBothRunners() {
        val tool = ToolRegistry.find("shell.elevated")!!
        val native = Projection(tool.name, CapabilityLevel.NATIVE)
        val huge = "n".repeat(PrivilegeAuditLog.MAX_REASON_CODE_BYTES + 1)
        for ((name, factory) in listOf(
            "shizuku" to ::shizukuRunner,
            "root" to ::rootRunner,
        )) {
            val spawn = SpawnCounter()
            val runner = factory(spawn) as ElevatedShellRunner
            val audit = PrivilegeAuditLog()
            val r = ElevatedDispatch.execute(
                tool = tool,
                confirmed = true,
                projection = native,
                request = ElevatedRequest(listOf("id"), huge),
                runner = runner,
                audit = audit,
            )
            assertTrue("$name -> $r", r is ShellResult.Denied)
            assertEquals(name, ShellDeny.BLACKLISTED, (r as ShellResult.Denied).reason)
            assertEquals("$name must not spawn", 0, spawn.calls)
            assertEquals(name, 1, audit.size())
            assertEquals(name, mapOf("DENY" to 1), audit.countByVerdict())
            assertEquals(name, PrivilegeAuditLog.sha256Hex(""), audit.snapshot().single().reasonHash)
        }
    }

    @Test fun boundaryReasonStillReachesBothRunners() {
        // 512B 恰上限仍放行（入口不誤殺，後續門另判；此處斷言能抵達 runner）。
        val tool = ToolRegistry.find("shell.elevated")!!
        val native = Projection(tool.name, CapabilityLevel.NATIVE)
        val edge = "n".repeat(PrivilegeAuditLog.MAX_REASON_CODE_BYTES)
        for ((name, factory) in listOf(
            "shizuku" to ::shizukuRunner,
            "root" to ::rootRunner,
        )) {
            val spawn = SpawnCounter()
            val runner = factory(spawn) as ElevatedShellRunner
            val audit = PrivilegeAuditLog()
            val r = ElevatedDispatch.execute(
                tool = tool,
                confirmed = true,
                projection = native,
                request = ElevatedRequest(listOf("id"), edge),
                runner = runner,
                audit = audit,
            )
            assertTrue("$name -> $r", r is ShellResult.Ok)
            assertEquals(name, 1, spawn.calls)
            assertEquals(name, 1, audit.size())
            assertEquals(name, PrivilegeAuditLog.sha256Hex(edge), audit.snapshot().single().reasonHash)
        }
    }
}
