package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellExecutables
import dev.librepocket.shell.ShellPolicy
import dev.librepocket.tool.Flavor
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-3 Phase I P2-7 multicall parity（提權通道側，與直接通道
 * `ShellMulticallParityTest` 同形）。
 *
 * parity 規則：只有 `logicalBase != realBase`（經 symlink／別名呼叫）才插
 * applet；邏輯名本來就是 multicall 主程式自身時（`["toybox", "id"]`）不插入。
 * 例：邏輯 `["toybox", "id"]` spawn 須為 `[real, "id"]`，不得為
 * `[real, "toybox", "id"]`；別名 `["id"]`（`id -> toybox`）spawn 為
 * `[real, "id"]`（此形兩路徑收斂，但經不同分支：別名經插入、主程式經透傳）。
 *
 * 安全同步：dispatcher 可承載 `rm`／`sh` 等 applet，`["toybox", "sh"]` 與
 * `["toybox", "rm"]` 經 [ShellPolicy.validateElevated] 仍須 `BLACKLISTED`
 * 且雙軌 runner（Root／Shizuku）零 spawn（見 `multicallApplet_*`）。
 *
 * TODO(#136-B3): B3 子命令 allowlist（`cmd`／`package` 類）在此擴充 applet／
 * 子命令位置審查；本測試只鎖黑名單 parity，不做 allowlist。
 */
class ElevatedMulticallParityTest {

    private val systemOwned: (java.nio.file.Path) -> Boolean = { _ -> false }

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun executable(dir: File, name: String): File {
        val f = File(dir, name)
        f.writeText("#!/bin/sh\necho hi\n")
        assertTrue("setExecutable failed for $f", f.setExecutable(true))
        return f
    }

    /** multicall fixture：`toybox` 實體＋`ls/id` 別名 links，同目錄。 */
    private fun toyboxDir(): File {
        val bin = tempDir("p27-toybox")
        executable(bin, "toybox")
        Files.createSymbolicLink(File(bin, "ls").toPath(), File(bin, "toybox").toPath())
        Files.createSymbolicLink(File(bin, "id").toPath(), File(bin, "toybox").toPath())
        return bin
    }

    @Test fun resolve_aliasVsMain_parity() {
        val bin = toyboxDir()
        try {
            val toyboxReal = File(bin, "toybox").canonicalPath
            val roots = listOf(bin.canonicalPath)
            // 別名形：攜 applet。
            val aliasBare = ElevatedExecPolicy.resolveForSnapshot(
                "ls", listOf(bin.absolutePath), roots, systemOwned,
            ) as ShellExecutables.ResolveOutcome.Ok
            assertEquals(toyboxReal, aliasBare.exec.path)
            assertEquals("ls", aliasBare.exec.applet)
            val aliasAbs = ElevatedExecPolicy.resolveForSnapshot(
                "${bin.absolutePath}/ls", listOf(bin.absolutePath), roots, systemOwned,
            ) as ShellExecutables.ResolveOutcome.Ok
            assertEquals(toyboxReal, aliasAbs.exec.path)
            assertEquals("ls", aliasAbs.exec.applet)
            // 主程式自身：不攜（bare／絕對兩形）。
            val mainBare = ElevatedExecPolicy.resolveForSnapshot(
                "toybox", listOf(bin.absolutePath), roots, systemOwned,
            ) as ShellExecutables.ResolveOutcome.Ok
            assertEquals(toyboxReal, mainBare.exec.path)
            assertNull("main-self bare must not insert", mainBare.exec.applet)
            val mainAbs = ElevatedExecPolicy.resolveForSnapshot(
                "${bin.absolutePath}/toybox", listOf(bin.absolutePath), roots, systemOwned,
            ) as ShellExecutables.ResolveOutcome.Ok
            assertEquals(toyboxReal, mainAbs.exec.path)
            assertNull("main-self absolute must not insert", mainAbs.exec.applet)
        } finally {
            bin.deleteRecursively()
        }
    }

    @Test fun toSpawnArgv_aliasVsMain_noDoubleInsert() {
        val bin = toyboxDir()
        try {
            val toyboxReal = File(bin, "toybox").canonicalPath
            val roots = listOf(bin.canonicalPath)
            // 別名 ["ls", "-l"] → [real, ls, -l]。
            val aliasExec = (ElevatedExecPolicy.resolveForSnapshot(
                "ls", listOf(bin.absolutePath), roots, systemOwned,
            ) as ShellExecutables.ResolveOutcome.Ok).exec
            val aliasSnap = ElevatedExecPolicy.snapshot(ElevatedRequest(listOf("ls", "-l"), "P27"))!!
            assertEquals(listOf(toyboxReal, "ls", "-l"), ElevatedExecPolicy.toSpawnArgv(aliasSnap, aliasExec))
            // 主程式 ["toybox", "id"] → [real, id]（不得為 [real, toybox, id]）。
            val mainExec = (ElevatedExecPolicy.resolveForSnapshot(
                "toybox", listOf(bin.absolutePath), roots, systemOwned,
            ) as ShellExecutables.ResolveOutcome.Ok).exec
            val mainSnap = ElevatedExecPolicy.snapshot(ElevatedRequest(listOf("toybox", "id"), "P27"))!!
            assertEquals(listOf(toyboxReal, "id"), ElevatedExecPolicy.toSpawnArgv(mainSnap, mainExec))
            // 縱深：陳舊映射攜非 null applet 亦不重插。
            val stale = ShellExecutables.ResolvedExec(toyboxReal, "toybox")
            assertEquals(listOf(toyboxReal, "id"), ElevatedExecPolicy.toSpawnArgv(mainSnap, stale))
        } finally {
            bin.deleteRecursively()
        }
    }

    @Test fun customResolver_staleApplet_normalizedToNullForMain() {
        val bin = toyboxDir()
        try {
            val toyboxReal = File(bin, "toybox").canonicalPath
            val roots = listOf(bin.canonicalPath)
            // 映射自帶錯誤 applet（主程式自身回非 null）→ 重算為 null。
            val outcome = ElevatedExecPolicy.resolveForSnapshot(
                "toybox",
                listOf(bin.absolutePath),
                roots,
                systemOwned,
                customResolver = { ShellExecutables.ResolvedExec(toyboxReal, "stale-should-be-dropped") },
            ) as ShellExecutables.ResolveOutcome.Ok
            assertEquals(toyboxReal, outcome.exec.path)
            assertNull("custom stale applet for main-self must normalize to null", outcome.exec.applet)
            // 別名經映射仍正確攜 logical（不採信自帶字串值）。
            val alias = ElevatedExecPolicy.resolveForSnapshot(
                "ls",
                listOf(bin.absolutePath),
                roots,
                systemOwned,
                customResolver = { ShellExecutables.ResolvedExec(toyboxReal, "evil") },
            ) as ShellExecutables.ResolveOutcome.Ok
            assertEquals("ls", alias.exec.applet)
        } finally {
            bin.deleteRecursively()
        }
    }

    @Test fun multicallApplet_blacklisted_elevatedValidate() {
        val scope = tempDir("p27-scope").apply { deleteRecursively(); mkdirs() }
        try {
            for (argv in listOf(
                listOf("toybox", "sh"),
                listOf("toybox", "rm"),
                listOf("toybox", "su"),
                listOf("busybox", "sh"),
                listOf("busybox", "rm"),
            )) {
                val v = ShellPolicy.validateElevated(argv, scope.absolutePath, flavor = Flavor.GITHUB, bridgeGranted = true)
                assertTrue("$argv -> $v", v is dev.librepocket.shell.Validation.Denied)
                assertEquals("$argv", ShellDeny.BLACKLISTED, (v as dev.librepocket.shell.Validation.Denied).reason)
            }
            // 良性 applet 不誤殺。
            val ok = ShellPolicy.validateElevated(
                listOf("toybox", "ls", "-l"),
                scope.absolutePath,
                flavor = Flavor.GITHUB,
                bridgeGranted = true,
            )
            assertTrue("$ok", ok is dev.librepocket.shell.Validation.Allowed)
        } finally {
            scope.deleteRecursively()
        }
    }

    @Test fun multicallApplet_blacklisted_dualRunnerZeroSpawn() {
        val scope = tempDir("p27-scope-run")
        val bin = toyboxDir()
        try {
            val roots = listOf(bin.canonicalPath)
            for (argv in listOf(listOf("toybox", "sh"), listOf("toybox", "rm"))) {
                // Shizuku 軌。
                var shizukuSpawns = 0
                val shizuku = ShizukuShellRunner(
                    privateRoot = scope.absolutePath,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = GrantFixtures.activationOn,
                    pingBinder = { true },
                    spawn = { shizukuSpawns++; FakeProcess("ok".toByteArray()) },
                    status = { ShizukuStatus.Available(15) },
                    grantCheck = GrantFixtures.allowAlways,
                    execSearchDirs = listOf(bin.absolutePath),
                    execAllowedRoots = roots,
                    execIsWritable = systemOwned,
                )
                val s = shizuku.runBinary(ElevatedRequest(argv, "P27-BL"), 2_000L)
                assertTrue("shizuku $argv -> $s", s is ElevatedBinaryResult.Denied)
                assertEquals(ShellDeny.BLACKLISTED, (s as ElevatedBinaryResult.Denied).reason)
                assertEquals("shizuku $argv must not spawn", 0, shizukuSpawns)
                // Root 軌。
                var rootSpawns = 0
                val root = RootSuRunner(
                    privateRoot = scope.absolutePath,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = GrantFixtures.activationOn,
                    spawn = { rootSpawns++; FakeProcess("ok".toByteArray()) },
                    suBinary = "/system/bin/su",
                    grantCheck = GrantFixtures.allowAlways,
                    execSearchDirs = listOf(bin.absolutePath),
                    execAllowedRoots = roots,
                    execIsWritable = systemOwned,
                )
                val r = root.runBinary(ElevatedRequest(argv, "P27-BL"), 2_000L)
                assertTrue("root $argv -> $r", r is ElevatedBinaryResult.Denied)
                assertEquals(ShellDeny.BLACKLISTED, (r as ElevatedBinaryResult.Denied).reason)
                assertEquals("root $argv must not spawn", 0, rootSpawns)
            }
        } finally {
            scope.deleteRecursively()
            bin.deleteRecursively()
        }
    }

    @Test fun multicallMain_dualRunnerSpawnShape_noDoubleInsert() {
        val scope = tempDir("p27-scope-main")
        val bin = toyboxDir()
        try {
            val toyboxReal = File(bin, "toybox").canonicalPath
            val roots = listOf(bin.canonicalPath)
            // Shizuku：["toybox", "ls", "-l"] → [real, ls, -l]（不得含 toybox 重插）。
            var lastArgv: List<String> = emptyList()
            val shizuku = ShizukuShellRunner(
                privateRoot = scope.absolutePath,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                pingBinder = { true },
                spawn = { a -> lastArgv = a; FakeProcess("ok".toByteArray()) },
                status = { ShizukuStatus.Available(15) },
                grantCheck = GrantFixtures.allowAlways,
                execSearchDirs = listOf(bin.absolutePath),
                execAllowedRoots = roots,
                execIsWritable = systemOwned,
            )
            val s = shizuku.runBinary(ElevatedRequest(listOf("toybox", "ls", "-l"), "P27-MAIN"), 2_000L)
            assertTrue("$s", s is ElevatedBinaryResult.Ok)
            assertEquals(listOf(toyboxReal, "ls", "-l"), lastArgv)
            // Root：su -c 內層同形 exec <real> ls -l（不得為 exec <real> toybox ...）。
            var lastWrapped: List<String> = emptyList()
            val root = RootSuRunner(
                privateRoot = scope.absolutePath,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                spawn = { w -> lastWrapped = w; FakeProcess("ok".toByteArray()) },
                suBinary = "/system/bin/su",
                grantCheck = GrantFixtures.allowAlways,
                execSearchDirs = listOf(bin.absolutePath),
                execAllowedRoots = roots,
                execIsWritable = systemOwned,
            )
            val r = root.runBinary(ElevatedRequest(listOf("toybox", "ls", "-l"), "P27-MAIN"), 2_000L)
            assertTrue("$r", r is ElevatedBinaryResult.Ok)
            assertEquals(3, lastWrapped.size)
            val script = lastWrapped[2]
            assertTrue("script=$script", script.contains("exec $toyboxReal ls -l"))
            assertTrue("script must not double-insert applet: $script", !script.contains("$toyboxReal toybox"))
        } finally {
            scope.deleteRecursively()
            bin.deleteRecursively()
        }
    }
}
