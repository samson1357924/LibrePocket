package dev.librepocket.linux

import dev.librepocket.shell.DefaultProcessRunner
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
        var lastEnv: Map<String, String>? = null
        override fun run(argv: List<String>, timeoutMs: Long, env: Map<String, String>?): RawOutput {
            calls++
            lastArgv = argv
            lastEnv = env
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
            // Guest 命名空間：容器內路徑用 guest 絕對路徑（/etc），
            // host 側 `.../linux/containers/.../rootfs/...` 一律 fail-closed
            //（見 hostAbsolutePaths_deniedWithoutSpawn），此處不再當正例。
            listOf("ls", "/etc"),
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

    @Test fun unstagedHostInboxPath_deniedWithoutSpawn() {
        // PR#1 re-review head 4e3a6c6 blocker 2：host inbox 路徑是 rootfs 的
        // sibling，真實 PRoot 下 guest 不可見；未經 LinuxInboxStager.stageFile
        // 的直傳必須 fail-closed 且不建子進程。
        val runner = FakeRunner()
        val inboxFile = "$filesDir/linux/tmp/inbox/x.txt"
        val result = exec(listOf("cat", inboxFile), runner = runner)
        assertTrue("expected Denied, got $result", result is ShellResult.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (result as ShellResult.Denied).reason)
        assertEquals(0, runner.calls)
    }

    @Test fun stagedGuestPath_allowed() {
        // 經 stage 後的 guest 路徑（/inbox/...）才是 guest 真正看得到的形狀。
        val runner = FakeRunner()
        val result = exec(listOf("cat", "/inbox/1a2b3c4d-x.txt"), runner = runner)
        assertTrue("expected Ok, got $result", result is ShellResult.Ok)
        // 實際 spawn 的是 proot 包裝 argv（--get-proot-cmd 可觀測）。
        val spawned = runner.lastArgv!!
        assertEquals(LinuxEnv.prootBin(filesDir), spawned[0])
        assertEquals(listOf("-r", "$filesDir/linux/containers/alpine/rootfs"), spawned.subList(1, 3))
        assertTrue(spawned.none { it == "-b" || it == "--bind" })
        assertEquals(listOf("cat", "/inbox/1a2b3c4d-x.txt"), spawned.takeLast(2))
    }

    @Test fun hostAbsolutePaths_deniedWithoutSpawn() {
        // PR#1 comment 6038761291 blocker 2：guest argv 一律 guest namespace；
        // 任何指向 filesDir/linux 整樹的 host 絕對路徑在真實 proot -r 下只會
        // ENOENT，故 fail-closed 且不建子進程（不只 tmp/inbox 一支）。
        val cases = listOf(
            listOf("ls", "$filesDir/linux/containers/alpine/rootfs/etc"),
            listOf("ls", "$filesDir/linux/containers/other/rootfs/etc"),
            listOf("cat", "$filesDir/linux/cache/x.bin"),
            listOf("cat", "$filesDir/linux/tmp/outbox/x.bin"),
            listOf("ls", "$filesDir/linux/bin/proot"),
            listOf("cat", "$filesDir/linux/image/alpine.tar.gz"),
            listOf("cat", "--file=$filesDir/linux/containers/alpine/rootfs/etc/passwd"),
        )
        for (argv in cases) {
            val runner = FakeRunner()
            val result = exec(argv, runner = runner)
            assertTrue("expected Denied for $argv, got $result", result is ShellResult.Denied)
            assertEquals("$argv", ShellDeny.BLACKLISTED, (result as ShellResult.Denied).reason)
            assertEquals("$argv", 0, runner.calls)
        }
    }

    @Test fun hostLinuxAbsoluteRef_normalizesVariants() {
        val root = LinuxEnv.root(filesDir)
        assertTrue(LinuxInboxStager.hostLinuxAbsoluteRef(listOf("cat", "$root/containers/alpine/rootfs/etc"), filesDir) != null)
        assertTrue(LinuxInboxStager.hostLinuxAbsoluteRef(listOf("cat", "$root//containers/alpine/rootfs/etc"), filesDir) != null)
        assertTrue(LinuxInboxStager.hostLinuxAbsoluteRef(listOf("cat", "--file=$root/cache/x"), filesDir) != null)
        assertEquals(null, LinuxInboxStager.hostLinuxAbsoluteRef(listOf("cat", "/inbox/x.txt"), filesDir))
        assertEquals(null, LinuxInboxStager.hostLinuxAbsoluteRef(listOf("cat", "/etc/passwd"), filesDir))
        assertEquals(null, LinuxInboxStager.hostLinuxAbsoluteRef(listOf("echo", "hi"), filesDir))
    }

    @Test fun guestEnv_enforcedOnSpawn() {
        // PR#1 comment 6038761291 blocker 3：乾淨 guest env 必須是執行不變量，
        // 不只常量斷言。FakeRunner 必須收到 GUEST_ENV 全等表。
        val runner = FakeRunner()
        val result = exec(listOf("echo", "hi"), runner = runner)
        assertTrue("expected Ok, got $result", result is ShellResult.Ok)
        assertEquals(LinuxEnv.GUEST_ENV, runner.lastEnv)
        // 拒絕路徑不建子進程、不洩 env。
        val deniedRunner = FakeRunner()
        val denied = exec(listOf("cat", "$filesDir/linux/containers/alpine/rootfs/etc"), runner = deniedRunner)
        assertTrue(denied is ShellResult.Denied)
        assertEquals(0, deniedRunner.calls)
        assertEquals(null, deniedRunner.lastEnv)
    }

    @Test fun defaultRunner_clearsHostEnv() {
        // 真實 DefaultProcessRunner：非 null env 即 clear()+putAll，不繼承宿主。
        // 用 `env` 二進位回顯子進程環境（Linux CI 必備；缺失則跳過）。
        val probe = try {
            DefaultProcessRunner().run(listOf("env"), 5_000L, mapOf("ONLY_GUEST_VAR" to "guest123"))
            true
        } catch (_: Exception) {
            false
        }
        if (!probe) return
        val out = DefaultProcessRunner().run(listOf("env"), 5_000L, LinuxEnv.GUEST_ENV)
        val text = String(out.stdout, Charsets.UTF_8)
        val lines = text.lineSequence().map { it.substringBefore('=') }.toSet()
        assertTrue("PATH missing", "PATH" in lines)
        assertTrue(text.contains("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"))
        assertTrue(text.contains("HOME=/root"))
        assertTrue("LD_PRELOAD leaked", lines.none { it == "LD_PRELOAD" })
        assertTrue("PROOT_* leaked", lines.none { it.startsWith("PROOT_") })
        assertTrue("proxy leaked", lines.none { it.equals("http_proxy", ignoreCase = true) || it.equals("https_proxy", ignoreCase = true) })
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
        val result = exec(listOf("cat", "/inbox/x.txt"), runner = FakeRunner(stdout = leak))
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
