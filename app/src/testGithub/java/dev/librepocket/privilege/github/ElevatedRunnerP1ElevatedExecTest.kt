package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ShellDeny
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-1＋P1-2 提權可信身分＋不變快照迴歸（owner 新 re-review，最高安全優先）。
 *
 * - P1-1：提權與直接同等 argv0 實體驗證。bare 經受控搜尋固定為已驗證實體，
 *   絕對經詞法＋實體雙門，spawn 用固定目標。覆蓋 Root/Shizuku ×
 *   `/data/local/tmp/cat`、`/app-private/cat`、同名 PATH shadow（可寫前序污染）、
 *   可信系統 binary positive（bare＋絕對兩形＋toybox applet 形）；
 *   不可信者一律 `Denied(BLACKLISTED)` 且零 spawn。
 * - P1-2：入口快照。`MutableList` 在 grant／status supplier 內竄改亦不影響
 *   已核驗快照（validation／容量特判／spawn 全用快照），黑名單變體零執行。
 * - 全 hermetic（暫存 fixture＋`systemOwned` 模擬裝置不可寫），零真機、零 su、零 binder。
 */
class ElevatedRunnerP1ElevatedExecTest {

    private val systemOwned: (java.nio.file.Path) -> Boolean = { _ -> false }

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun executable(dir: File, name: String, body: String = "#!/bin/sh\necho hi\n"): File {
        val f = File(dir, name)
        f.writeText(body)
        assertTrue("setExecutable failed for $f", f.setExecutable(true))
        return f
    }

    private fun scopeWithFile(prefix: String): Pair<File, File> {
        val scope = tempDir(prefix)
        val target = File(scope, "x.txt").apply { writeText("hello\n") }
        return scope to target
    }

    // ---- P1-1: 絕對不可信（詞法門即拒，零 spawn） ----

    @Test fun rootUntrustedAbsoluteDeniedWithoutSpawn() {
        val (scope, _) = scopeWithFile("p1-scope-r-abs")
        val trusted = tempDir("p1-trusted-r-abs")
        try {
            executable(trusted, "cat")
            val trustedReal = trusted.canonicalPath
            for (argv0 in listOf("/data/local/tmp/cat", "/app-private/cat")) {
                var spawns = 0
                val runner = RootSuRunner(
                    privateRoot = scope.absolutePath,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = GrantFixtures.activationOn,
                    spawn = {
                        spawns++
                        FakeProcess("ok".toByteArray())
                    },
                    suBinary = "/system/bin/su",
                    grantCheck = GrantFixtures.allowAlways,
                    execSearchDirs = listOf(trusted.absolutePath),
                    execAllowedRoots = listOf(trustedReal),
                    execIsWritable = systemOwned,
                )
                val r = runner.runBinary(ElevatedRequest(listOf(argv0), "P1-1-ABS"), 2_000L)
                assertTrue("$argv0 -> $r", r is ElevatedBinaryResult.Denied)
                assertEquals(ShellDeny.BLACKLISTED, (r as ElevatedBinaryResult.Denied).reason)
                assertEquals("argv0=$argv0 must not spawn", 0, spawns)
            }
        } finally {
            scope.deleteRecursively()
            trusted.deleteRecursively()
        }
    }

    @Test fun shizukuUntrustedAbsoluteDeniedWithoutSpawn() {
        val (scope, _) = scopeWithFile("p1-scope-s-abs")
        val trusted = tempDir("p1-trusted-s-abs")
        try {
            executable(trusted, "cat")
            val trustedReal = trusted.canonicalPath
            for (argv0 in listOf("/data/local/tmp/cat", "/app-private/cat")) {
                var spawns = 0
                val runner = ShizukuShellRunner(
                    privateRoot = scope.absolutePath,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = GrantFixtures.activationOn,
                    pingBinder = { true },
                    spawn = {
                        spawns++
                        FakeProcess("ok".toByteArray())
                    },
                    status = { ShizukuStatus.Available(15) },
                    grantCheck = GrantFixtures.allowAlways,
                    execSearchDirs = listOf(trusted.absolutePath),
                    execAllowedRoots = listOf(trustedReal),
                    execIsWritable = systemOwned,
                )
                val r = runner.runBinary(ElevatedRequest(listOf(argv0), "P1-1-ABS"), 2_000L)
                assertTrue("$argv0 -> $r", r is ElevatedBinaryResult.Denied)
                assertEquals(ShellDeny.BLACKLISTED, (r as ElevatedBinaryResult.Denied).reason)
                assertEquals("argv0=$argv0 must not spawn", 0, spawns)
            }
        } finally {
            scope.deleteRecursively()
            trusted.deleteRecursively()
        }
    }

    // ---- P1-1: 同名 PATH shadow（可寫前序污染即拒，零 spawn） ----

    @Test fun rootBareShadowDeniedWithoutSpawn() {
        val (scope, target) = scopeWithFile("p1-scope-r-shadow")
        val writableEmpty = tempDir("p1-writable-r")
        val clean = tempDir("p1-clean-r")
        try {
            executable(clean, "cat")
            val cleanReal = clean.canonicalPath
            val writableReal = writableEmpty.canonicalPath
            val split: (java.nio.file.Path) -> Boolean = { p ->
                val s = p.toString()
                s == writableReal || s.startsWith("$writableReal/")
            }
            var spawns = 0
            var lastWrapped: List<String> = emptyList()
            val runner = RootSuRunner(
                privateRoot = scope.absolutePath,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                spawn = { argv ->
                    spawns++
                    lastWrapped = argv
                    FakeProcess("ok".toByteArray())
                },
                suBinary = "/system/bin/su",
                grantCheck = GrantFixtures.allowAlways,
                execSearchDirs = listOf(writableEmpty.absolutePath, clean.absolutePath),
                execAllowedRoots = listOf(cleanReal),
                execIsWritable = split,
            )
            val r = runner.runBinary(
                ElevatedRequest(listOf("cat", target.absolutePath), "P1-1-SHADOW"),
                2_000L,
            )
            assertTrue("$r", r is ElevatedBinaryResult.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (r as ElevatedBinaryResult.Denied).reason)
            assertTrue("reason=${r.message}", r.message.contains("SEARCH_DIR_UNTRUSTED"))
            assertEquals(0, spawns)
            assertTrue(lastWrapped.isEmpty())
        } finally {
            scope.deleteRecursively()
            writableEmpty.deleteRecursively()
            clean.deleteRecursively()
        }
    }

    @Test fun shizukuBareShadowDeniedWithoutSpawn() {
        val (scope, target) = scopeWithFile("p1-scope-s-shadow")
        val writableEmpty = tempDir("p1-writable-s")
        val clean = tempDir("p1-clean-s")
        try {
            executable(clean, "cat")
            val cleanReal = clean.canonicalPath
            val writableReal = writableEmpty.canonicalPath
            val split: (java.nio.file.Path) -> Boolean = { p ->
                val s = p.toString()
                s == writableReal || s.startsWith("$writableReal/")
            }
            var spawns = 0
            val runner = ShizukuShellRunner(
                privateRoot = scope.absolutePath,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                pingBinder = { true },
                spawn = {
                    spawns++
                    FakeProcess("ok".toByteArray())
                },
                status = { ShizukuStatus.Available(15) },
                grantCheck = GrantFixtures.allowAlways,
                execSearchDirs = listOf(writableEmpty.absolutePath, clean.absolutePath),
                execAllowedRoots = listOf(cleanReal),
                execIsWritable = split,
            )
            val r = runner.runBinary(
                ElevatedRequest(listOf("cat", target.absolutePath), "P1-1-SHADOW"),
                2_000L,
            )
            assertTrue("$r", r is ElevatedBinaryResult.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (r as ElevatedBinaryResult.Denied).reason)
            assertTrue("reason=${r.message}", r.message.contains("SEARCH_DIR_UNTRUSTED"))
            assertEquals(0, spawns)
        } finally {
            scope.deleteRecursively()
            writableEmpty.deleteRecursively()
            clean.deleteRecursively()
        }
    }

    // ---- P1-1: 可信系統 binary positive（bare＋絕對兩形，spawn 用固定目標） ----

    @Test fun rootTrustedPositiveUsesPinnedTarget() {
        val (scope, target) = scopeWithFile("p1-scope-r-pos")
        val trusted = tempDir("p1-trusted-r-pos")
        try {
            executable(trusted, "cat")
            val trustedReal = trusted.canonicalPath
            val realCat = File(trusted, "cat").canonicalPath
            assertEquals("$trustedReal/cat", realCat)
            for (argv in listOf(
                listOf("cat", target.absolutePath),
                listOf("${trusted.absolutePath}/cat", target.absolutePath),
            )) {
                var spawns = 0
                var lastWrapped: List<String> = emptyList()
                val runner = RootSuRunner(
                    privateRoot = scope.absolutePath,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = GrantFixtures.activationOn,
                    spawn = { wrapped ->
                        spawns++
                        lastWrapped = wrapped
                        FakeProcess("ok".toByteArray())
                    },
                    suBinary = "/system/bin/su",
                    grantCheck = GrantFixtures.allowAlways,
                    execSearchDirs = listOf(trusted.absolutePath),
                    execAllowedRoots = listOf(trustedReal),
                    execIsWritable = systemOwned,
                )
                val r = runner.runBinary(ElevatedRequest(argv, "P1-1-POS"), 2_000L)
                assertTrue("$argv -> $r", r is ElevatedBinaryResult.Ok)
                assertEquals(1, spawns)
                // su -c 傳送層：wrapped=[su, -c, script]，script 內必須含固定實體，
                // 不得含原始 bare 歧義（絕對形亦須 фиксировано到 real）。
                assertEquals(3, lastWrapped.size)
                assertEquals("/system/bin/su", lastWrapped[0])
                assertEquals("-c", lastWrapped[1])
                val script = lastWrapped[2]
                assertTrue("script=$script", script.contains(realCat))
                // 原始未固定形態不得出現：bare 形不得以 `exec cat ` 直行，
                // 絕對形不得以詞法路徑（symlink 未解）直行——此處 fixture 無 link，
                // 故斷言 script 含 real 且不含裸 ` cat ` 歧義需精確：檢查 `exec $realCat`。
                assertTrue("script=$script", script.contains("exec $realCat"))
            }
        } finally {
            scope.deleteRecursively()
            trusted.deleteRecursively()
        }
    }

    @Test fun shizukuTrustedPositiveUsesPinnedTarget() {
        val (scope, target) = scopeWithFile("p1-scope-s-pos")
        val trusted = tempDir("p1-trusted-s-pos")
        try {
            executable(trusted, "cat")
            val trustedReal = trusted.canonicalPath
            val realCat = File(trusted, "cat").canonicalPath
            for (argv in listOf(
                listOf("cat", target.absolutePath),
                listOf("${trusted.absolutePath}/cat", target.absolutePath),
            )) {
                var spawns = 0
                var lastArgv: List<String> = emptyList()
                val runner = ShizukuShellRunner(
                    privateRoot = scope.absolutePath,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = GrantFixtures.activationOn,
                    pingBinder = { true },
                    spawn = { a ->
                        spawns++
                        lastArgv = a
                        FakeProcess("ok".toByteArray())
                    },
                    status = { ShizukuStatus.Available(15) },
                    grantCheck = GrantFixtures.allowAlways,
                    execSearchDirs = listOf(trusted.absolutePath),
                    execAllowedRoots = listOf(trustedReal),
                    execIsWritable = systemOwned,
                )
                val r = runner.runBinary(ElevatedRequest(argv, "P1-1-POS"), 2_000L)
                assertTrue("$argv -> $r", r is ElevatedBinaryResult.Ok)
                assertEquals(1, spawns)
                // spawn 直接固定實體，不再用原始 argv[0]。
                assertEquals(realCat, lastArgv[0])
                assertEquals(listOf(target.absolutePath), lastArgv.drop(1))
            }
        } finally {
            scope.deleteRecursively()
            trusted.deleteRecursively()
        }
    }

    // ---- P1-1: toybox applet 形（multicall 還原，不丟子命令） ----

    private fun toyboxDir(): File {
        val bin = tempDir("p1-toybox")
        val toybox = File(bin, "toybox")
        toybox.writeText("#!/bin/sh\necho hi\n")
        assertTrue(toybox.setExecutable(true))
        Files.createSymbolicLink(File(bin, "ls").toPath(), toybox.toPath())
        return bin
    }

    @Test fun shizukuToyboxAppletRestored() {
        val scope = tempDir("p1-scope-toybox-s")
        val bin = toyboxDir()
        try {
            val toyboxReal = File(bin, "toybox").canonicalPath
            val roots = listOf(bin.canonicalPath)
            var lastArgv: List<String> = emptyList()
            val runner = ShizukuShellRunner(
                privateRoot = scope.absolutePath,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                pingBinder = { true },
                spawn = { a ->
                    lastArgv = a
                    FakeProcess("ok".toByteArray())
                },
                status = { ShizukuStatus.Available(15) },
                grantCheck = GrantFixtures.allowAlways,
                execSearchDirs = listOf(bin.absolutePath),
                execAllowedRoots = roots,
                execIsWritable = systemOwned,
            )
            val r = runner.runBinary(ElevatedRequest(listOf("ls", "-l"), "P1-1-TOYBOX"), 2_000L)
            assertTrue("$r", r is ElevatedBinaryResult.Ok)
            // multicall：[real, applet, ...args]，applet 取快照 basename。
            assertEquals(listOf(toyboxReal, "ls", "-l"), lastArgv)
        } finally {
            scope.deleteRecursively()
            bin.deleteRecursively()
        }
    }

    @Test fun rootToyboxAppletRestoredInSuScript() {
        val scope = tempDir("p1-scope-toybox-r")
        val bin = toyboxDir()
        try {
            val toyboxReal = File(bin, "toybox").canonicalPath
            val roots = listOf(bin.canonicalPath)
            var lastWrapped: List<String> = emptyList()
            val runner = RootSuRunner(
                privateRoot = scope.absolutePath,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                spawn = { w ->
                    lastWrapped = w
                    FakeProcess("ok".toByteArray())
                },
                suBinary = "/system/bin/su",
                grantCheck = GrantFixtures.allowAlways,
                execSearchDirs = listOf(bin.absolutePath),
                execAllowedRoots = roots,
                execIsWritable = systemOwned,
            )
            val r = runner.runBinary(ElevatedRequest(listOf("ls", "-l"), "P1-1-TOYBOX"), 2_000L)
            assertTrue("$r", r is ElevatedBinaryResult.Ok)
            assertEquals(3, lastWrapped.size)
            val script = lastWrapped[2]
            // su -c 內層同形：exec <real> ls -l（applet 還原，不丟成 toybox -l）。
            assertTrue("script=$script", script.contains("exec $toyboxReal ls -l"))
            assertFalse("script=$script must not lose applet", script.contains("exec $toyboxReal -l"))
        } finally {
            scope.deleteRecursively()
            bin.deleteRecursively()
        }
    }

    // ---- P1-2: grant／status supplier 竄改 mutable argv，黑名單變體零執行 ----

    @Test fun rootMutableArgvMutatedInGrantNeverExecutesBlacklisted() {
        val trusted = tempDir("p1-mut-r")
        val scope = tempDir("p1-mut-r-scope")
        try {
            executable(trusted, "id")
            val trustedReal = trusted.canonicalPath
            val realId = File(trusted, "id").canonicalPath
            val mutable = mutableListOf("id")
            var spawns = 0
            var lastWrapped: List<String> = emptyList()
            val runner = RootSuRunner(
                privateRoot = scope.absolutePath,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                spawn = { w ->
                    spawns++
                    lastWrapped = w
                    FakeProcess("ok".toByteArray())
                },
                suBinary = "/system/bin/su",
                grantCheck = {
                    // validate 已見快照 ["id"]（Allowed），此處竄改为黑名單。
                    mutable[0] = "sh"
                    GrantCheck.Valid
                },
                execSearchDirs = listOf(trusted.absolutePath),
                execAllowedRoots = listOf(trustedReal),
                execIsWritable = systemOwned,
            )
            val r = runner.runBinary(ElevatedRequest(mutable, "P1-2-MUT"), 2_000L)
            // 快照為 ["id"]（Allowed），故本次按 ["id"] 執行（Ok），絕不執行 ["sh"]。
            assertTrue("$r", r is ElevatedBinaryResult.Ok)
            assertEquals(1, spawns)
            val script = lastWrapped[2]
            assertTrue("script=$script", script.contains("exec $realId"))
            assertFalse("mutated sh must never execute: $script", script.contains(" sh"))
            // 變體若作為快照本應拒（黑名單縱深 pin，不放弱）。
            val denied = runner.runBinary(ElevatedRequest(listOf("sh"), "P1-2-MUT"), 2_000L)
            assertTrue("$denied", denied is ElevatedBinaryResult.Denied)
        } finally {
            trusted.deleteRecursively()
            scope.deleteRecursively()
        }
    }

    @Test fun shizukuMutableArgvMutatedInGrantNeverExecutesBlacklisted() {
        val trusted = tempDir("p1-mut-s-grant")
        val scope = tempDir("p1-mut-s-grant-scope")
        try {
            executable(trusted, "id")
            val trustedReal = trusted.canonicalPath
            val realId = File(trusted, "id").canonicalPath
            val mutable = mutableListOf("id")
            var spawns = 0
            var lastArgv: List<String> = emptyList()
            val runner = ShizukuShellRunner(
                privateRoot = scope.absolutePath,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                pingBinder = { true },
                spawn = { a ->
                    spawns++
                    lastArgv = a
                    FakeProcess("ok".toByteArray())
                },
                status = { ShizukuStatus.Available(15) },
                grantCheck = {
                    mutable[0] = "sh"
                    GrantCheck.Valid
                },
                execSearchDirs = listOf(trusted.absolutePath),
                execAllowedRoots = listOf(trustedReal),
                execIsWritable = systemOwned,
            )
            val r = runner.runBinary(ElevatedRequest(mutable, "P1-2-MUT"), 2_000L)
            assertTrue("$r", r is ElevatedBinaryResult.Ok)
            assertEquals(1, spawns)
            assertEquals(realId, lastArgv[0])
            assertFalse("mutated sh must never spawn: $lastArgv", lastArgv.contains("sh"))
            val denied = runner.runBinary(ElevatedRequest(listOf("sh"), "P1-2-MUT"), 2_000L)
            assertTrue("$denied", denied is ElevatedBinaryResult.Denied)
        } finally {
            trusted.deleteRecursively()
            scope.deleteRecursively()
        }
    }

    @Test fun shizukuMutableArgvMutatedInStatusNeverExecutesBlacklisted() {
        val trusted = tempDir("p1-mut-s-status")
        val scope = tempDir("p1-mut-s-status-scope")
        try {
            executable(trusted, "id")
            val trustedReal = trusted.canonicalPath
            val realId = File(trusted, "id").canonicalPath
            val mutable = mutableListOf("id")
            var spawns = 0
            var lastArgv: List<String> = emptyList()
            val runner = ShizukuShellRunner(
                privateRoot = scope.absolutePath,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                pingBinder = { true },
                spawn = { a ->
                    spawns++
                    lastArgv = a
                    FakeProcess("ok".toByteArray())
                },
                status = {
                    // validate＋早段 status 已見快照 ["id"]，重估前竄改亦不影響快照。
                    mutable[0] = "sh"
                    ShizukuStatus.Available(15)
                },
                grantCheck = GrantFixtures.allowAlways,
                execSearchDirs = listOf(trusted.absolutePath),
                execAllowedRoots = listOf(trustedReal),
                execIsWritable = systemOwned,
            )
            val r = runner.runBinary(ElevatedRequest(mutable, "P1-2-MUT"), 2_000L)
            assertTrue("$r", r is ElevatedBinaryResult.Ok)
            assertEquals(1, spawns)
            assertEquals(realId, lastArgv[0])
            assertFalse("mutated sh must never spawn: $lastArgv", lastArgv.contains("sh"))
        } finally {
            trusted.deleteRecursively()
            scope.deleteRecursively()
        }
    }
}
