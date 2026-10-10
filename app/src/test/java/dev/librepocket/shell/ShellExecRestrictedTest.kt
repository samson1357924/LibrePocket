package dev.librepocket.shell

import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.Projection
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.SideEffect
import dev.librepocket.tool.ToolRegistry
import dev.librepocket.router.GateResult
import dev.librepocket.router.PrivilegeGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1-C `shell.exec` 受限版測試：argv 直達、開關預設關、
 * 白名單/黑名單/配額/截斷沿 RestrictedShell；提權一律拒絕；
 * 直接 exec 跨域一律 Denied。
 */
class ShellExecRestrictedTest {

    private class FakeRunner(
        private val stdout: ByteArray,
        private val stderr: ByteArray = ByteArray(0),
    ) : ProcessRunner {
        var calls: Int = 0
        override fun run(argv: List<String>, timeoutMs: Long, env: Map<String, String>?): RawOutput {
            calls++
            return RawOutput(stdout, stderr, 0, false)
        }
    }

    private val privateRoot = "/data/data/dev.librepocket.agent/files"

    // ---- 註冊：PRIVILEGED + switch shell 預設 false ----

    @Test fun registry_shellExecIsPrivilegedWithSwitchOffByDefault() {
        val tool = ToolRegistry.find("shell.exec")!!
        assertEquals(SideEffect.PRIVILEGED, tool.sideEffect)
        assertEquals("shell", tool.annotations.requiresSwitch)
        assertEquals(false, tool.annotations.switchDefault)
    }

    @Test fun projection_switchOffByDefaultIsUserDisabled() {
        val ctx = ProjectionContext(flavor = Flavor.PLAY)
        val p = ToolRegistry.projectAll(ctx)["shell.exec"]!!
        assertEquals(CapabilityLevel.UNAVAILABLE, p.level)
        assertEquals(DenyReason.USER_DISABLED, p.reason)
    }

    @Test fun projection_switchOnIsNative() {
        val ctx = ProjectionContext(flavor = Flavor.PLAY, userSwitches = mapOf("shell" to true))
        val p = ToolRegistry.projectAll(ctx)["shell.exec"]!!
        assertEquals(CapabilityLevel.NATIVE, p.level)
    }

    @Test fun gate_privilegedNeedsConfirm() {
        val tool = ToolRegistry.find("shell.exec")!!
        val native = Projection(tool.name, CapabilityLevel.NATIVE)
        assertEquals(GateResult.NeedConfirm(tool.name), PrivilegeGate.check(tool, false, native))
        assertTrue(PrivilegeGate.canExecute(tool, true, native))
    }

    // ---- argv 直達：不經 sh -c ----

    @Test fun shellStringNeverExecuted() {
        val runner = FakeRunner(ByteArray(0))
        val shell = RestrictedShell(runner = runner, privateRoot = privateRoot)
        // 任何 sh -c 形式都在黑名單：一律拒絕且不建子進程。
        for (argv in listOf(
            listOf("sh", "-c", "echo hi"),
            listOf("bash", "-c", "id"),
        )) {
            val r = ShellExecTool.execute(argv, shell, switchOn = true)
            assertTrue("$argv", r is ShellResult.Denied)
        }
        assertEquals(0, runner.calls)
    }

    @Test fun switchOffDeniedWithoutSpawn() {
        val runner = FakeRunner(ByteArray(0))
        val shell = RestrictedShell(runner = runner, privateRoot = privateRoot)
        val r = ShellExecTool.execute(listOf("echo", "hi"), shell, switchOn = false)
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(0, runner.calls)
        assertEquals(false, ShellExecTool.checkSwitch(false))
        assertEquals(true, ShellExecTool.checkSwitch(true))
    }

    @Test fun allowlistedArgvExecutes() {
        val runner = FakeRunner("ok".toByteArray())
        // S3 hermetic：FakeRunner 不執行，只驗 argv 直達；解析用桩（宿主
        // /bin/echo 實體落點隨發行版而異，見 ShellWhitelistTest 註解）。
        val shell = RestrictedShell(
            runner = runner,
            privateRoot = privateRoot,
            execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
        )
        val r = ShellExecTool.execute(listOf("echo", "hi"), shell, switchOn = true)
        assertTrue("$r", r is ShellResult.Ok)
        assertEquals(1, runner.calls)
    }

    @Test fun blacklistAndMetacharsDenied() {
        val runner = FakeRunner(ByteArray(0))
        val shell = RestrictedShell(runner = runner, privateRoot = privateRoot)
        for (argv in listOf(
            listOf("rm", "-rf", "/"),
            listOf("su", "-c", "id"),
            listOf("echo", "a;rm -rf /"),
            listOf("echo", "a|b"),
        )) {
            val r = ShellExecTool.execute(argv, shell, switchOn = true)
            assertTrue("$argv -> $r", r is ShellResult.Denied)
        }
        assertEquals(0, runner.calls)
    }

    @Test fun quotaAndTruncationApply() {
        // 配額。
        val runner = FakeRunner("ok".toByteArray())
        val quota = ShellQuota(maxCalls = 1, windowMs = 60_000L)
        val stub: (String) -> ShellExecutables.ResolvedExec? =
            { ShellExecutables.ResolvedExec("/trusted/$it", null) }
        val shell = RestrictedShell(quota = quota, runner = runner, privateRoot = privateRoot, execResolver = stub)
        assertTrue(ShellExecTool.execute(listOf("echo", "1"), shell, switchOn = true) is ShellResult.Ok)
        val second = ShellExecTool.execute(listOf("echo", "2"), shell, switchOn = true)
        assertTrue("$second", second is ShellResult.Denied)
        assertEquals(ShellDeny.QUOTA_EXCEEDED, (second as ShellResult.Denied).reason)
        // 截斷。
        val big = FakeRunner(ByteArray(ShellPolicy.MAX_OUTPUT_BYTES + 8))
        val bigShell = RestrictedShell(runner = big, privateRoot = privateRoot, execResolver = stub)
        val r = ShellExecTool.execute(listOf("echo", "x"), bigShell, switchOn = true)
        assertTrue("$r", r is ShellResult.Ok)
        assertEquals(true, (r as ShellResult.Ok).truncated)
    }

    // ---- 跨域一律 Denied；提權一律拒絕 ----

    @Test fun crossDomainDeniedForDirectExec() {
        for (flavor in listOf(Flavor.PLAY, Flavor.FOSS, Flavor.GITHUB)) {
            val runner = FakeRunner(ByteArray(0))
            val shell = RestrictedShell(
                runner = runner,
                privateRoot = privateRoot,
                flavor = flavor,
                bridgeGranted = true,
            )
            val r = ShellExecTool.execute(listOf("cat", "/etc/passwd"), shell, switchOn = true)
            assertTrue("$flavor -> $r", r is ShellResult.Denied)
            assertEquals(0, runner.calls)
        }
    }

    @Test fun relativePathDeniedForDirectExecWithoutSpawn() {
        // P0 fail-closed（PR#1 review blocker）：直接通道相對路徑一律拒且不建子進程。
        val runner = FakeRunner(ByteArray(0))
        val shell = RestrictedShell(runner = runner, privateRoot = privateRoot)
        for (argv in listOf(
            listOf("cat", "../../etc/passwd"),
            listOf("grep", "-r", "password", ".."),
            listOf("cat", "--db=../secret.db"),
            listOf("cat", "chat/x.txt"),
        )) {
            val r = ShellExecTool.execute(argv, shell, switchOn = true)
            assertTrue("$argv -> $r", r is ShellResult.Denied)
        }
        assertEquals(0, runner.calls)
    }

    @Test fun elevatedDeniedAndAuditedWithoutSpawn() {
        // S3 D09：提權唯一路徑為審計版 executeElevated（無 audit 過載已刪除，
        // 全路徑強制留痕；見 ElevatedDispatch）。
        val runner = FakeRunner(ByteArray(0))
        val audit = PrivilegeAuditLog()
        // 佔位 DenyingElevatedRunner：拒絕且不建子進程，同時寫 DENY 審計。
        val r = ShellExecTool.executeElevated(
            ElevatedRequest(listOf("id"), reasonCode = "TEST"),
            DenyingElevatedRunner(),
            audit,
        )
        assertTrue("$r", r is ShellResult.Denied)
        // 顯式注入亦同（本階段無真實現）。
        val r2 = ShellExecTool.executeElevated(
            ElevatedRequest(listOf("id"), reasonCode = "TEST"),
            DenyingElevatedRunner(),
            audit,
        )
        assertTrue("$r2", r2 is ShellResult.Denied)
        assertEquals(0, runner.calls)
        assertEquals(2, audit.size())
    }

    @Test fun elevatedNameRegisteredForS3() {
        // S3（D09）：提權工具名已註冊（PRIVILEGED + 自裝風味 + 預設關），見 ShellElevatedTest。
        assertEquals("shell.elevated", ShellExecTool.ELEVATED_NAME)
        val tool = ToolRegistry.find(ShellExecTool.ELEVATED_NAME)!!
        assertEquals(SideEffect.PRIVILEGED, tool.sideEffect)
        assertEquals("privilege_bridge", tool.annotations.requiresSwitch)
        assertEquals(false, tool.annotations.switchDefault)
    }
}
