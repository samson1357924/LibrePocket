package dev.librepocket.shell

import dev.librepocket.tool.Flavor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D05 受限 shell 測試（BACKLOG D05 驗收方向）：
 * 黑名單拒絕、超時殺、輸出截斷；另覆蓋白名單拒絕、參數衛生、配額。
 */
class ShellWhitelistTest {

    /** 固定輸出假跑道：讓截斷斷言不依賴宿主機二進位。 */
    private class FakeRunner(
        private val stdout: ByteArray,
        private val stderr: ByteArray = ByteArray(0),
    ) : ProcessRunner {
        var calls: Int = 0

        override fun run(argv: List<String>, timeoutMs: Long): RawOutput {
            calls++
            return RawOutput(stdout, stderr, 0, false)
        }
    }

    private fun shellWithFake(stdoutSize: Int): Pair<RestrictedShell, FakeRunner> {
        val runner = FakeRunner(ByteArray(stdoutSize) { 'x'.code.toByte() })
        return RestrictedShell(runner = runner) to runner
    }

    @Test fun nonWhitelistedBinaryDeniedWithoutSpawn() {
        val runner = FakeRunner(ByteArray(0))
        val shell = RestrictedShell(runner = runner)
        val result = shell.execute(listOf("foobar-no-such-bin-xyz", "a"))
        assertTrue(result is ShellResult.Denied)
        assertEquals(ShellDeny.NOT_WHITELISTED, (result as ShellResult.Denied).reason)
        assertEquals(0, runner.calls)
    }

    @Test fun blacklistRejectsRmRfRoot() {
        val runner = FakeRunner(ByteArray(0))
        val shell = RestrictedShell(runner = runner)
        for (argv in listOf(
            listOf("rm", "-rf", "/"),
            listOf("rm", "-fr", "/"),
            listOf("rm", "-rf", "/*"),
        )) {
            val result = shell.execute(argv)
            assertTrue("expected BLACKLISTED for $argv, got $result", result is ShellResult.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (result as ShellResult.Denied).reason)
        }
        assertEquals(0, runner.calls)
    }

    @Test fun blacklistCoversDestructiveBinaries() {
        val runner = FakeRunner(ByteArray(0))
        val shell = RestrictedShell(runner = runner)
        for (argv in listOf(
            listOf("dd", "if=/dev/zero", "of=/dev/sda"),
            listOf("su", "-c", "id"),
            listOf("sh", "-c", "echo hi"),
        )) {
            val result = shell.execute(argv)
            assertTrue("expected deny for $argv, got $result", result is ShellResult.Denied)
        }
        assertEquals(0, runner.calls)
    }

    @Test fun shellMetacharsRejected() {
        val runner = FakeRunner(ByteArray(0))
        val shell = RestrictedShell(runner = runner)
        for (argv in listOf(
            listOf("echo", "a;rm -rf /"),
            listOf("ls", "a|b"),
            listOf("echo", "\$(id)"),
            listOf("echo", "a>b"),
        )) {
            val result = shell.execute(argv)
            assertTrue("expected deny for $argv, got $result", result is ShellResult.Denied)
        }
        assertEquals(0, runner.calls)
    }

    @Test fun absoluteWhitelistedPathAllowed() {
        val (shell, _) = shellWithFake(3)
        val result = shell.execute(listOf("/bin/echo", "hi"))
        assertTrue("expected Ok, got $result", result is ShellResult.Ok)
    }

    @Test fun timeoutKillsSleep() {
        val shell = RestrictedShell()
        val startMs = System.currentTimeMillis()
        val result = shell.execute(listOf("sleep", "30"), timeoutMs = 500L)
        val elapsedMs = System.currentTimeMillis() - startMs
        assertTrue("expected TimedOut, got $result", result is ShellResult.TimedOut)
        assertTrue("kill took too long: ${elapsedMs}ms", elapsedMs < 15_000L)
    }

    @Test fun outputTruncatedAtCap() {
        val (shell, _) = shellWithFake(ShellPolicy.MAX_OUTPUT_BYTES + 1024)
        val result = shell.execute(listOf("echo", "x"))
        assertTrue("expected Ok, got $result", result is ShellResult.Ok)
        val ok = result as ShellResult.Ok
        assertTrue(ok.truncated)
        assertEquals(
            ShellPolicy.MAX_OUTPUT_BYTES,
            ok.stdout.toByteArray(Charsets.UTF_8).size,
        )
    }

    @Test fun smallOutputNotMarkedTruncated() {
        val (shell, _) = shellWithFake(16)
        val result = shell.execute(listOf("echo", "x"))
        assertTrue("expected Ok, got $result", result is ShellResult.Ok)
        assertEquals(false, (result as ShellResult.Ok).truncated)
    }

    @Test fun quotaExceededAfterLimit() {
        val runner = FakeRunner("ok".toByteArray())
        val quota = ShellQuota(maxCalls = 2, windowMs = 60_000L)
        val shell = RestrictedShell(quota = quota, runner = runner)
        assertTrue(shell.execute(listOf("echo", "1")) is ShellResult.Ok)
        assertTrue(shell.execute(listOf("echo", "2")) is ShellResult.Ok)
        val third = shell.execute(listOf("echo", "3"))
        assertTrue("expected quota deny, got $third", third is ShellResult.Denied)
        assertEquals(ShellDeny.QUOTA_EXCEEDED, (third as ShellResult.Denied).reason)
        assertEquals(2, runner.calls)
    }

    @Test fun deniedCallsDoNotConsumeQuota() {
        val runner = FakeRunner(ByteArray(0))
        val quota = ShellQuota(maxCalls = 1, windowMs = 60_000L)
        val shell = RestrictedShell(quota = quota, runner = runner)
        assertTrue(shell.execute(listOf("nope-bin-xyz")) is ShellResult.Denied)
        // 策略拒絕不佔配額：隨後一次合法呼叫仍應放行。
        assertTrue(shell.execute(listOf("echo", "hi")) is ShellResult.Ok)
        assertEquals(1, runner.calls)
    }

    @Test fun emptyCommandDenied() {
        val runner = FakeRunner(ByteArray(0))
        val shell = RestrictedShell(runner = runner)
        assertTrue(shell.execute(emptyList()) is ShellResult.Denied)
        assertEquals(0, runner.calls)
    }

    // ---- D05 review blocking：find 沙箱逃逸封堵 ----

    private val shellPrivateRoot = "/data/data/dev.librepocket.agent/files"

    @Test fun findExecEscapeDeniedWithoutSpawn() {
        val runner = FakeRunner(ByteArray(0))
        val shell = RestrictedShell(runner = runner, privateRoot = shellPrivateRoot)
        // 題面原例：藉 find 執行任意二進位，必須拒絕且不建子進程。
        val result = shell.execute(listOf("find", "/", "-exec", "rm", "{}", "+"))
        assertTrue("expected Denied, got $result", result is ShellResult.Denied)
        assertEquals(0, runner.calls)
    }

    @Test fun findDangerousPredicatesDeniedWithoutSpawn() {
        val runner = FakeRunner(ByteArray(0))
        val shell = RestrictedShell(runner = runner, privateRoot = shellPrivateRoot)
        for (argv in listOf(
            listOf("find", shellPrivateRoot, "-exec", "id", "{}", ";"),
            listOf("find", shellPrivateRoot, "-execdir", "id", "{}", "+"),
            listOf("find", shellPrivateRoot, "-ok", "id", "{}", ";"),
            listOf("find", shellPrivateRoot, "-okdir", "id", "{}", "+"),
            listOf("find", shellPrivateRoot, "-delete"),
            listOf("find", shellPrivateRoot, "-fls", "$shellPrivateRoot/out.txt"),
            listOf("find", shellPrivateRoot, "-fprint", "$shellPrivateRoot/out.txt"),
            listOf("find", shellPrivateRoot, "-fprintf", "$shellPrivateRoot/out.txt", "%p"),
        )) {
            // "-exec ... ;" 含 ';' 會先因 metachar 拒絕，其餘走 find 謂詞；
            // 重點是一律拒絕且不建子進程。
            val result = shell.execute(argv)
            assertTrue("expected Denied for $argv, got $result", result is ShellResult.Denied)
        }
        assertEquals(0, runner.calls)
    }

    @Test fun findRootStartDeniedAsCrossDomainWithoutSpawn() {
        val runner = FakeRunner(ByteArray(0))
        val shell = RestrictedShell(runner = runner, privateRoot = shellPrivateRoot)
        // 無高危謂詞，但 "/" 跨域（play），同樣拒絕且不建子進程。
        for (argv in listOf(
            listOf("find", "/", "-print"),
            listOf("find", "/", "-maxdepth", "1", "-print"),
            listOf("ls", "/"),
        )) {
            val result = shell.execute(argv)
            assertTrue("expected Denied for $argv, got $result", result is ShellResult.Denied)
        }
        assertEquals(0, runner.calls)
    }

    @Test fun absolutePathCrossDomainDeniedWithoutSpawn() {
        val runner = FakeRunner(ByteArray(0))
        val shell = RestrictedShell(runner = runner, privateRoot = shellPrivateRoot)
        for (argv in listOf(
            listOf("cat", "/etc/passwd"),
            listOf("cat", "/sdcard/Download/other/app.apk"),
            listOf("grep", "hi", "--file=/etc/passwd"),
        )) {
            val result = shell.execute(argv)
            assertTrue("expected Denied for $argv, got $result", result is ShellResult.Denied)
        }
        assertEquals(0, runner.calls)
    }

    @Test fun absolutePathDeniedWithoutScopeWithoutSpawn() {
        // 未配置 privateRoot（預設 null）時 fail-closed：任何絕對路徑拒絕。
        val runner = FakeRunner(ByteArray(0))
        val shell = RestrictedShell(runner = runner)
        val result = shell.execute(listOf("cat", "/etc/passwd"))
        assertTrue("expected Denied, got $result", result is ShellResult.Denied)
        assertEquals(0, runner.calls)
    }

    @Test fun privateAbsoluteAllowedWithScope() {
        val runner = FakeRunner("ok".toByteArray())
        val shell = RestrictedShell(runner = runner, privateRoot = shellPrivateRoot)
        val result = shell.execute(listOf("cat", "$shellPrivateRoot/chat/x.txt"))
        assertTrue("expected Ok, got $result", result is ShellResult.Ok)
        assertEquals(1, runner.calls)
    }

    @Test fun safGrantedAbsoluteAllowedWithScope() {
        val safTree = "/tree/primary:Download/docs"
        val runner = FakeRunner("ok".toByteArray())
        val shell = RestrictedShell(
            runner = runner,
            privateRoot = shellPrivateRoot,
            safRoots = listOf(safTree),
        )
        val result = shell.execute(listOf("cat", "$safTree/report.pdf"))
        assertTrue("expected Ok, got $result", result is ShellResult.Ok)
        assertEquals(1, runner.calls)
    }

    @Test fun selfInstallBridgeCrossDomainStillDeniedForDirectExec() {
        // foss/github + 橋接已授權時 FileScope 回 needsBridge，但直接 exec 仍拒絕
        // （需改走 D09 橋），不得放行子進程。
        for (flavor in listOf(Flavor.FOSS, Flavor.GITHUB)) {
            val runner = FakeRunner(ByteArray(0))
            val shell = RestrictedShell(
                runner = runner,
                privateRoot = shellPrivateRoot,
                flavor = flavor,
                bridgeGranted = true,
            )
            val result = shell.execute(listOf("cat", "/sdcard/Download/other/x.txt"))
            assertTrue("expected Denied, got $result", result is ShellResult.Denied)
            assertEquals(0, runner.calls)
        }
    }

    @Test fun elevatedRunnerNeverSpawns() {
        val runner = DenyingElevatedRunner()
        val result = runner.run(ElevatedRequest(listOf("id"), reasonCode = "TEST"))
        assertTrue(result is ShellResult.Denied)
    }

    @Test fun privilegeProbeFailsClosed() {
        val probe = PrivilegeProbe(shizukuProbe = { false }, suProbe = { false })
        assertEquals(false, probe.anyAvailable())
        assertEquals(2, probe.probe().size)
    }
}
