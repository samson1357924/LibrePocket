package dev.librepocket.linux

import dev.librepocket.shell.ProcessRunner
import dev.librepocket.shell.RawOutput
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellPolicy
import dev.librepocket.shell.ShellQuota
import dev.librepocket.shell.ShellResult
import dev.librepocket.tool.Flavor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S4 `linux.exec` + `linux.pkg` 測試（SMOKE 除外，見 [LinuxSmokeTest]）：
 * 白名單聯集、黑名單繼承（ShellWhitelist 沿用：同一 [ShellPolicy.validate]
 * 程式碼路徑）、超時/配額/截斷沿用、SAF bind 封堵、apt/dnf/apk 子集。
 *
 * 純 JVM（假 [ProcessRunner]，不依賴宿主機 proot）。
 */
class ProotExecTest {

    private val filesDir = "/data/data/dev.librepocket.agent/files"

    private class FakeRunner(
        private val stdout: ByteArray = "ok".toByteArray(),
        private val stderr: ByteArray = ByteArray(0),
        private val timedOut: Boolean = false,
    ) : ProcessRunner {
        var calls: Int = 0
        var lastArgv: List<String>? = null
        override fun run(argv: List<String>, timeoutMs: Long): RawOutput {
            calls++
            lastArgv = argv
            return RawOutput(stdout, stderr, 0, timedOut)
        }
    }

    private fun exec(
        argv: List<String>,
        flavor: Flavor = Flavor.GITHUB,
        switchOn: Boolean = true,
        runner: ProcessRunner = FakeRunner(),
        quota: ShellQuota = ShellQuota(),
        timeoutMs: Long = ShellPolicy.DEFAULT_TIMEOUT_MS,
    ): ShellResult = ProotExec.execute(argv, filesDir, "alpine", flavor, switchOn, runner, quota, timeoutMs)

    // ---- 白名單聯集：宿主白名單 ∪ guest 增量 ----

    @Test fun union_allowsHostAndGuestBinaries() {
        for (argv in listOf(
            listOf("echo", "hi"),
            listOf("ls", "/data/data/dev.librepocket.agent/files/linux/containers/alpine/rootfs/etc"),
            listOf("python3", "--version"),
            listOf("gcc", "--version"),
            listOf("make", "-j4"),
            listOf("cmake", "--version"),
            listOf("apktool", "--version"),
            listOf("jadx", "--version"),
            listOf("aapt2", "version"),
        )) {
            val runner = FakeRunner()
            val result = exec(argv, runner = runner)
            assertTrue("expected Ok for $argv, got $result", result is ShellResult.Ok)
            assertEquals(1, runner.calls)
        }
    }

    @Test fun union_stillDeniesUnknown() {
        val runner = FakeRunner()
        for (argv in listOf(listOf("curl", "https://x"), listOf("wget", "https://x"))) {
            val result = exec(argv, runner = runner)
            assertTrue("expected deny for $argv, got $result", result is ShellResult.Denied)
            assertEquals(ShellDeny.NOT_WHITELISTED, (result as ShellResult.Denied).reason)
        }
        assertEquals(0, runner.calls)
    }

    // ---- 黑名單繼承（ShellWhitelist 沿用：同 validate 路徑） ----

    @Test fun blacklist_inheritedWithoutSpawn() {
        val runner = FakeRunner()
        // 黑名單二進位 / 全文片段 / find 高危謂詞 / --bind → BLACKLISTED。
        for (argv in listOf(
            listOf("rm", "-rf", "/"),
            listOf("su", "-c", "id"),
            listOf("sh", "-c", "echo hi"),
            listOf("echo", "a;rm -rf /"),
            listOf("find", "/", "-exec", "rm", "x"),
            listOf("echo", "--bind", "/sdcard"),
        )) {
            val result = exec(argv, runner = runner)
            assertTrue("expected deny for $argv, got $result", result is ShellResult.Denied)
            assertEquals(
                "$argv",
                ShellDeny.BLACKLISTED,
                (result as ShellResult.Denied).reason,
            )
        }
        // 參數衛生（metachar）→ BAD_ARGUMENT（同 ShellPolicy 判定序）。
        for (argv in listOf(
            listOf("ls", "a|b"),
            listOf("echo", "a>b"),
            listOf("echo", "\$(id)"),
        )) {
            val result = exec(argv, runner = runner)
            assertTrue("expected deny for $argv, got $result", result is ShellResult.Denied)
            assertEquals(
                "$argv",
                ShellDeny.BAD_ARGUMENT,
                (result as ShellResult.Denied).reason,
            )
        }
        assertEquals(0, runner.calls)
    }

    // ---- 風味/開關/容器門禁 ----

    @Test fun playBlocked_switchOff_badContainer() {
        assertTrue(exec(listOf("echo", "hi"), flavor = Flavor.PLAY) is ShellResult.Denied)
        assertTrue(exec(listOf("echo", "hi"), switchOn = false) is ShellResult.Denied)
        val bad = ProotExec.execute(
            listOf("echo", "hi"), filesDir, "../evil", Flavor.GITHUB, true, FakeRunner(),
        )
        assertTrue(bad is ShellResult.Denied)
    }

    // ---- SAF：樹路徑直行拒絕（禁 bind），inbox 複製放行 ----

    @Test fun safTreePath_deniedEvenWithBridge() {
        // SAF 樹在私有域外：guest argv 直寫 SAF 絕對路徑一律拒絕
        //（bridgeGranted 固定 false，foss/github 亦無 needsBridge 直行）。
        val runner = FakeRunner()
        val result = exec(listOf("cat", "/sdcard/Download/x.txt"), runner = runner)
        assertTrue(result is ShellResult.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (result as ShellResult.Denied).reason)
        assertEquals(0, runner.calls)
    }

    @Test fun inboxCopy_allowed() {
        val runner = FakeRunner()
        val inboxFile = "$filesDir/linux/tmp/inbox/x.txt"
        val result = exec(listOf("cat", inboxFile), runner = runner)
        assertTrue("expected Ok, got $result", result is ShellResult.Ok)
        // 實際 spawn 的是 proot 包裝 argv（--get-proot-cmd 可觀測）。
        val spawned = runner.lastArgv!!
        assertEquals(LinuxEnv.prootBin(filesDir), spawned[0])
        assertEquals(listOf("-r", "$filesDir/linux/containers/alpine/rootfs"), spawned.subList(1, 3))
        assertTrue(spawned.none { it == "-b" || it == "--bind" })
    }

    // ---- 超時/配額/截斷沿用宿主同一套 ----

    @Test fun quotaExceeded_noSpawn() {
        val quota = ShellQuota(maxCalls = 1, windowMs = 60_000L)
        val runner = FakeRunner()
        assertTrue(exec(listOf("echo", "a"), runner = runner, quota = quota) is ShellResult.Ok)
        val second = exec(listOf("echo", "b"), runner = runner, quota = quota)
        assertTrue(second is ShellResult.Denied)
        assertEquals(ShellDeny.QUOTA_EXCEEDED, (second as ShellResult.Denied).reason)
        assertEquals(1, runner.calls)
    }

    @Test fun truncation_marksAndRedacts() {
        val big = ByteArray(ShellPolicy.MAX_OUTPUT_BYTES + 16) { 'y'.code.toByte() }
        val result = exec(listOf("echo", "big"), runner = FakeRunner(stdout = big))
        assertTrue(result is ShellResult.Ok)
        assertTrue((result as ShellResult.Ok).truncated)
    }

    @Test fun timeout_propagatesTimeoutMs() {
        val result = exec(listOf("sleep", "9"), runner = FakeRunner(timedOut = true), timeoutMs = 123L)
        assertTrue(result is ShellResult.TimedOut)
        assertEquals(123L, (result as ShellResult.TimedOut).timeoutMs)
    }

    @Test fun output_redactsSecretsAndPaths() {
        val leak = "token=SECRETVALUE123 at $filesDir/linux/containers/alpine/rootfs/x".toByteArray()
        val result = exec(listOf("cat", "$filesDir/linux/tmp/inbox/x.txt"), runner = FakeRunner(stdout = leak))
        assertTrue(result is ShellResult.Ok)
        val out = (result as ShellResult.Ok).stdout
        assertTrue("secret leaked: $out", "SECRETVALUE123" !in out)
        assertTrue("path leaked: $out", filesDir !in out)
    }

    // ---- linux.pkg：apt/dnf/apk 子集 ----

    @Test fun pkg_subset() {
        assertEquals(null, LinuxPkg.pkgVeto(listOf("apt", "update")))
        assertEquals(null, LinuxPkg.pkgVeto(listOf("apt-get", "install", "python3")))
        assertEquals(null, LinuxPkg.pkgVeto(listOf("dnf", "search", "gcc")))
        assertEquals(null, LinuxPkg.pkgVeto(listOf("apk", "show", "musl")))
        assertEquals(null, LinuxPkg.pkgVeto(listOf("yum", "list")))
        assertEquals(null, LinuxPkg.pkgVeto(listOf("apk", "remove", "strace")))
        assertEquals("MANAGER_DENIED:pacman", LinuxPkg.pkgVeto(listOf("pacman", "install", "x")))
        assertEquals("SUBCOMMAND_DENIED:dist-upgrade", LinuxPkg.pkgVeto(listOf("apt", "dist-upgrade")))
        assertEquals("SUBCOMMAND_DENIED:full-upgrade", LinuxPkg.pkgVeto(listOf("dnf", "full-upgrade")))
        assertEquals("SUBCOMMAND_DENIED:purge", LinuxPkg.pkgVeto(listOf("apt", "purge", "x")))
        assertEquals("SUBCOMMAND_REQUIRED", LinuxPkg.pkgVeto(listOf("apt")))
        assertEquals("EMPTY_COMMAND", LinuxPkg.pkgVeto(emptyList()))
        assertTrue(
            (LinuxPkg.pkgVeto(listOf("apt", "install", "-y", "x")) ?: "").startsWith("OPTION_FORBIDDEN"),
        )
        assertTrue(
            (LinuxPkg.pkgVeto(listOf("apt", "install", "../evil")) ?: "").startsWith("BAD_PACKAGE"),
        )
    }

    @Test fun pkg_managerBinaries_inGuestUnion() {
        // pkg argv 經同一 exec 白名單：管理員二進位必須在聯集內。
        for (mgr in LinuxPkg.MANAGERS) {
            assertTrue("$mgr missing from union", mgr in ProotExec.GUEST_BINARIES)
        }
        val runner = FakeRunner()
        val result = exec(listOf("apt", "update"), runner = runner)
        assertTrue("expected Ok, got $result", result is ShellResult.Ok)
    }

    @Test fun git_notInUnion_sourceViaInboxOnly() {
        assertTrue("git must not be in union", "git" !in ProotExec.GUEST_BINARIES)
        val runner = FakeRunner()
        val result = exec(listOf("git", "clone", "https://x"), runner = runner)
        assertTrue("expected deny, got $result", result is ShellResult.Denied)
        assertEquals(ShellDeny.NOT_WHITELISTED, (result as ShellResult.Denied).reason)
        assertEquals(0, runner.calls)
    }

    @Test fun diskQuota_blocksLandingWithoutSpawn() {
        val runner = FakeRunner()
        val result = ProotExec.execute(
            listOf("echo", "hi"), filesDir, "alpine", Flavor.GITHUB, true, runner,
            usedContainerBytes = LinuxEnv.PER_CONTAINER_BYTES, estimatedWriteBytes = 1,
        )
        assertTrue(result is ShellResult.Denied)
        assertEquals(ShellDeny.QUOTA_EXCEEDED, (result as ShellResult.Denied).reason)
        assertEquals(0, runner.calls)
        // pkg 編排同樣先裁落盤配額。
        val pkgDenied = LinuxPkg.execute(
            listOf("apt", "update"), filesDir, "alpine", Flavor.GITHUB, true, FakeRunner(),
            usedTotalBytes = LinuxEnv.TOTAL_BYTES, estimatedWriteBytes = 1,
        )
        assertTrue(pkgDenied is ShellResult.Denied)
        assertEquals(ShellDeny.QUOTA_EXCEEDED, (pkgDenied as ShellResult.Denied).reason)
    }

    @Test fun prootMode_mismatchDenied() {
        val runner = FakeRunner()
        val result = ProotExec.execute(
            listOf("echo", "hi"), filesDir, "alpine", Flavor.GITHUB, true, runner,
            prootBinMode = "0755",
        )
        assertTrue(result is ShellResult.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (result as ShellResult.Denied).reason)
        assertEquals(0, runner.calls)
    }

    @Test fun denied_containsFallbackAndPerfNotice_viaDenyReasonFor() {
        assertEquals(dev.librepocket.tool.DenyReason.FLAVOR_BLOCKED, ProotExec.denyReasonFor(Flavor.PLAY, true))
        assertEquals(dev.librepocket.tool.DenyReason.USER_DISABLED, ProotExec.denyReasonFor(Flavor.GITHUB, false))
        assertEquals(null, ProotExec.denyReasonFor(Flavor.GITHUB, true))
        val play = exec(listOf("echo", "hi"), flavor = Flavor.PLAY, runner = FakeRunner())
        assertTrue((play as ShellResult.Denied).message.contains(ProotExec.FALLBACK_HINT))
        assertTrue(play.message.contains(dev.librepocket.linux.LinuxTools.PERF_NOTICE))
        val off = exec(listOf("echo", "hi"), switchOn = false, runner = FakeRunner())
        assertTrue((off as ShellResult.Denied).message.contains(dev.librepocket.linux.LinuxTools.PERF_NOTICE))
    }

    @Test fun bareDashB_allowedAsGuestFlag() {
        // 裸 -b 是 guest 普通旗標（ls -b），不得誤殺；--bind 才禁。
        val runner = FakeRunner()
        val ok = exec(listOf("ls", "-b"), runner = runner)
        assertTrue("expected Ok for ls -b, got $ok", ok is ShellResult.Ok)
        assertEquals(1, runner.calls)
        val runner2 = FakeRunner()
        val denied = exec(listOf("echo", "--bind=/sdcard:/mnt"), runner = runner2)
        assertTrue(denied is ShellResult.Denied)
        assertEquals(0, runner2.calls)
    }

    @Test fun pkgExecute_orchestration() {
        val runner = FakeRunner()
        val ok = LinuxPkg.execute(listOf("apt", "update"), filesDir, "alpine", Flavor.GITHUB, true, runner)
        assertTrue("expected Ok, got $ok", ok is ShellResult.Ok)
        val play = LinuxPkg.execute(listOf("apt", "update"), filesDir, "alpine", Flavor.PLAY, true, FakeRunner())
        assertTrue(play is ShellResult.Denied)
        assertTrue((play as ShellResult.Denied).message.contains(dev.librepocket.linux.LinuxTools.PERF_NOTICE))
        val badMgr = LinuxPkg.execute(listOf("pacman", "install", "x"), filesDir, "alpine", Flavor.GITHUB, true, FakeRunner())
        assertTrue(badMgr is ShellResult.Denied)
    }
}
