package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellPolicy
import dev.librepocket.shell.Validation
import dev.librepocket.tool.Flavor
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Round-4 Phase J P1-1 multicall 有效 applet 完整 parity（提權雙軌側）。
 *
 * 規範化與直接通道同規則（見 `ShellMulticallEffectiveTest`）：
 * `[toybox, applet, ...args]` 按 `[applet, ...args]` 套用 applet 黑名單／未知明示拒
 * （TODO #136-B3）／find 謂詞／FILE_OPERAND／relative／realpath／implicit cwd／
 * 取值旗標值槽。未知 applet 維持拒絕（NOT_WHITELISTED，不 blanket allow）。
 *
 * 矩陣：Root／Shizuku × alias（`find`／`cat` 經 symlink 指 toybox）／main（`toybox`
 * 自身）× 正常（find -print 放行形）／破壞性（-delete／-exec rm）／相對路徑
 * （cat entry.txt）／symlink（scope 內預植指域外 link）＋未知／implicit／值槽。
 * 全 deterministic，負測零 spawn（SpawnCounter）。
 */
class ElevatedMulticallEffectiveTest {

    private val systemOwned: (java.nio.file.Path) -> Boolean = { _ -> false }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().canonicalFile

    private fun executable(dir: File, name: String) {
        val f = File(dir, name)
        f.writeText("#!/bin/sh\necho hi\n")
        assertTrue("setExecutable failed for $f", f.setExecutable(true))
    }

    /** hermetic toybox fixture：toybox 實體＋find/cat/ls/id 別名 links。 */
    private fun toyboxBin(): File {
        val bin = tempDir("j-toybox")
        executable(bin, "toybox")
        for (a in listOf("find", "cat", "ls", "id")) {
            Files.createSymbolicLink(File(bin, a).toPath(), File(bin, "toybox").toPath())
        }
        return bin
    }

    private fun assumeNonWindows() {
        val os = System.getProperty("os.name") ?: ""
        assumeTrue(!os.startsWith("Windows"))
    }

    private fun elevatedDenied(
        argv: List<String>,
        scope: String,
        expected: ShellDeny = ShellDeny.BLACKLISTED,
    ) {
        val v = ShellPolicy.validateElevated(
            argv,
            scope,
            flavor = Flavor.GITHUB,
            bridgeGranted = true,
        )
        assertTrue("$argv -> $v", v is Validation.Denied)
        assertEquals("$argv", expected, (v as Validation.Denied).reason)
    }

    private fun dualRunnerDeniedZeroSpawn(
        argv: List<String>,
        scope: File,
        bin: File,
    ) {
        val roots = listOf(bin.canonicalPath)
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
        val s = shizuku.runBinary(ElevatedRequest(argv, "J-DENY"), 2_000L)
        assertTrue("shizuku $argv -> $s", s is ElevatedBinaryResult.Denied)
        assertEquals(0, shizukuSpawns)
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
        val r = root.runBinary(ElevatedRequest(argv, "J-DENY"), 2_000L)
        assertTrue("root $argv -> $r", r is ElevatedBinaryResult.Denied)
        assertEquals(0, rootSpawns)
    }

    // ---- 1. 正常 find -print：alias 與 main 同有效形放行（elevated Allowed） ----

    @Test fun findPrint_aliasAndMain_bothAllowedElevated() {
        val scope = tempDir("j-eff-scope")
        val bin = toyboxBin()
        try {
            val sub = File(scope, "tree").apply { mkdirs() }
            val alias = listOf("find", sub.absolutePath, "-print")
            val main = listOf("toybox", "find", sub.absolutePath, "-print")
            for (argv in listOf(alias, main)) {
                val v = ShellPolicy.validateElevated(
                    argv,
                    scope.absolutePath,
                    flavor = Flavor.GITHUB,
                    bridgeGranted = true,
                )
                assertTrue("$argv -> $v", v is Validation.Allowed)
                assertEquals("find", (v as Validation.Allowed).binary)
            }
            // 雙軌 spawn 形收斂：alias [real, find, sub, -print]；main [real, find, sub, -print]。
            val toyboxReal = File(bin, "toybox").canonicalPath
            val roots = listOf(bin.canonicalPath)
            // Shizuku alias。
            var lastAlias: List<String> = emptyList()
            val shAlias = ShizukuShellRunner(
                privateRoot = scope.absolutePath,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                pingBinder = { true },
                spawn = { a -> lastAlias = a; FakeProcess("ok".toByteArray()) },
                status = { ShizukuStatus.Available(15) },
                grantCheck = GrantFixtures.allowAlways,
                execSearchDirs = listOf(bin.absolutePath),
                execAllowedRoots = roots,
                execIsWritable = systemOwned,
            )
            val sa = shAlias.runBinary(ElevatedRequest(alias, "J-OK"), 2_000L)
            assertTrue("$sa", sa is ElevatedBinaryResult.Ok)
            assertEquals(listOf(toyboxReal, "find", sub.absolutePath, "-print"), lastAlias)
            // Shizuku main：同形（不重插 toybox）。
            var lastMain: List<String> = emptyList()
            val shMain = ShizukuShellRunner(
                privateRoot = scope.absolutePath,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                pingBinder = { true },
                spawn = { a -> lastMain = a; FakeProcess("ok".toByteArray()) },
                status = { ShizukuStatus.Available(15) },
                grantCheck = GrantFixtures.allowAlways,
                execSearchDirs = listOf(bin.absolutePath),
                execAllowedRoots = roots,
                execIsWritable = systemOwned,
            )
            val sm = shMain.runBinary(ElevatedRequest(main, "J-OK"), 2_000L)
            assertTrue("$sm", sm is ElevatedBinaryResult.Ok)
            assertEquals(listOf(toyboxReal, "find", sub.absolutePath, "-print"), lastMain)
            // Root main：su -c 內層同形 exec <real> find <sub> -print。
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
            val rr = root.runBinary(ElevatedRequest(main, "J-OK"), 2_000L)
            assertTrue("$rr", rr is ElevatedBinaryResult.Ok)
            assertEquals(3, lastWrapped.size)
            assertTrue("${lastWrapped[2]}", lastWrapped[2].contains("exec $toyboxReal find"))
            assertTrue("${lastWrapped[2]}", !lastWrapped[2].contains("$toyboxReal toybox"))
        } finally {
            scope.deleteRecursively()
            bin.deleteRecursively()
        }
    }

    // ---- 2. 破壞性 -delete：alias 與 main 同拒（BLACKLISTED，零 spawn） ----

    @Test fun findDelete_aliasAndMain_dualDeniedZeroSpawn() {
        val scope = tempDir("j-del-scope")
        val bin = toyboxBin()
        try {
            val sub = File(scope, "tree").apply { mkdirs() }
            for (argv in listOf(
                listOf("find", sub.absolutePath, "-delete"),
                listOf("toybox", "find", sub.absolutePath, "-delete"),
                listOf("toybox", "find", sub.absolutePath, "-fls", "$sub/out.txt"),
            )) {
                elevatedDenied(argv, scope.absolutePath, ShellDeny.BLACKLISTED)
                dualRunnerDeniedZeroSpawn(argv, scope, bin)
            }
        } finally {
            scope.deleteRecursively()
            bin.deleteRecursively()
        }
    }

    // ---- 3. 破壞性 -exec：alias 與 main 同拒 ----

    @Test fun findExec_aliasAndMain_dualDeniedZeroSpawn() {
        val scope = tempDir("j-exec-scope")
        val bin = toyboxBin()
        try {
            val sub = File(scope, "tree").apply { mkdirs() }
            for (argv in listOf(
                listOf("find", sub.absolutePath, "-exec", "rm", "{}", "+"),
                listOf("toybox", "find", sub.absolutePath, "-exec", "rm", "{}", "+"),
                listOf("toybox", "find", sub.absolutePath, "-execdir", "id", "{}", "+"),
                listOf("toybox", "find", sub.absolutePath, "-ok", "id", "{}", "+"),
            )) {
                elevatedDenied(argv, scope.absolutePath, ShellDeny.BLACKLISTED)
                dualRunnerDeniedZeroSpawn(argv, scope, bin)
            }
        } finally {
            scope.deleteRecursively()
            bin.deleteRecursively()
        }
    }

    // ---- 4. 相對路徑 cat entry.txt：alias 與 main 同拒 ----

    @Test fun catRelative_aliasAndMain_dualDeniedZeroSpawn() {
        val scope = tempDir("j-rel-scope")
        val bin = toyboxBin()
        try {
            for (argv in listOf(
                listOf("cat", "entry.txt"),
                listOf("toybox", "cat", "entry.txt"),
                listOf("toybox", "cat", "sub/entry.txt"),
            )) {
                elevatedDenied(argv, scope.absolutePath, ShellDeny.BLACKLISTED)
                dualRunnerDeniedZeroSpawn(argv, scope, bin)
                // 直接通道同拒（FILE_OPERAND 有效形）。
                val d = ShellPolicy.validate(argv, scope.absolutePath)
                // alias 與 main 有效形皆 BLACKLISTED（main 有效門先拒，不落 dispatcher 門）。
                assertTrue("direct $argv -> $d", d is Validation.Denied)
                assertEquals(ShellDeny.BLACKLISTED, (d as Validation.Denied).reason)
            }
        } finally {
            scope.deleteRecursively()
            bin.deleteRecursively()
        }
    }

    // ---- 5. symlink：scope 內預植指域外 link，alias 與 main 同拒 ----

    @Test fun catSymlink_aliasAndMain_dualDeniedZeroSpawn() {
        assumeNonWindows()
        val scope = tempDir("j-sym-scope")
        val outside = tempDir("j-sym-out")
        val bin = toyboxBin()
        try {
            val victim = File(outside, "secret.txt").apply { writeText("secret") }
            val link = File(scope, "link.txt").toPath()
            Files.createSymbolicLink(link, victim.toPath())
            for (argv in listOf(
                listOf("cat", link.toString()),
                listOf("toybox", "cat", link.toString()),
            )) {
                elevatedDenied(argv, scope.absolutePath, ShellDeny.BLACKLISTED)
                dualRunnerDeniedZeroSpawn(argv, scope, bin)
            }
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
            bin.deleteRecursively()
        }
    }

    // ---- 6. 未知 applet：維持拒絕（NOT_WHITELISTED，B3 TODO） ----

    @Test fun unknownApplet_main_staysDenied() {
        val scope = tempDir("j-unk-scope")
        val bin = toyboxBin()
        try {
            for (argv in listOf(
                listOf("toybox", "ps"),
                listOf("toybox", "foobar123"),
            )) {
                elevatedDenied(argv, scope.absolutePath, ShellDeny.NOT_WHITELISTED)
                dualRunnerDeniedZeroSpawn(argv, scope, bin)
            }
        } finally {
            scope.deleteRecursively()
            bin.deleteRecursively()
        }
    }

    // ---- 7. implicit cwd：無明確路徑＋null 作用域，main 與 bare 同拒 ----

    @Test fun implicitCwd_main_parityWithBareWhenNullScope() {
        for (argv in listOf(
            listOf("find", "-maxdepth", "1"),
            listOf("toybox", "find", "-maxdepth", "1"),
            listOf("toybox", "ls"),
        )) {
            val v = ShellPolicy.validateElevated(argv, privateRoot = null)
            assertTrue("$argv -> $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        }
        // 雙軌零 spawn（null 作用域 runner）。
        val bin = toyboxBin()
        try {
            for (argv in listOf(
                listOf("toybox", "find", "-maxdepth", "1"),
                listOf("toybox", "ls"),
            )) {
                var shizukuSpawns = 0
                val shizuku = ShizukuShellRunner(
                    privateRoot = null,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = GrantFixtures.activationOn,
                    pingBinder = { true },
                    spawn = { shizukuSpawns++; FakeProcess("ok".toByteArray()) },
                    status = { ShizukuStatus.Available(15) },
                    grantCheck = GrantFixtures.allowAlways,
                    execSearchDirs = listOf(bin.absolutePath),
                    execAllowedRoots = listOf(bin.canonicalPath),
                    execIsWritable = systemOwned,
                )
                val s = shizuku.runBinary(ElevatedRequest(argv, "J-NULL"), 2_000L)
                assertTrue("shizuku $argv -> $s", s is ElevatedBinaryResult.Denied)
                assertEquals(0, shizukuSpawns)
                var rootSpawns = 0
                val root = RootSuRunner(
                    privateRoot = null,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = GrantFixtures.activationOn,
                    spawn = { rootSpawns++; FakeProcess("ok".toByteArray()) },
                    suBinary = "/system/bin/su",
                    grantCheck = GrantFixtures.allowAlways,
                    execSearchDirs = listOf(bin.absolutePath),
                    execAllowedRoots = listOf(bin.canonicalPath),
                    execIsWritable = systemOwned,
                )
                val r = root.runBinary(ElevatedRequest(argv, "J-NULL"), 2_000L)
                assertTrue("root $argv -> $r", r is ElevatedBinaryResult.Denied)
                assertEquals(0, rootSpawns)
            }
        } finally {
            bin.deleteRecursively()
        }
    }

    // ---- 8. 取值旗標值槽：main 有效形不誤殺（与 bare 同放行） ----

    @Test fun valueFlag_main_parityWithBare() {
        val scope = tempDir("j-val-scope")
        val bin = toyboxBin()
        try {
            val f = File(scope, "x.txt").apply { writeText("hi") }
            val sub = File(scope, "tree").apply { mkdirs() }
            for (argv in listOf(
                listOf("head", "-n", "20", f.absolutePath),
                listOf("toybox", "head", "-n", "20", f.absolutePath),
                listOf("find", sub.absolutePath, "-name", "*.db", "-print"),
                listOf("toybox", "find", sub.absolutePath, "-name", "*.db", "-print"),
            )) {
                val v = ShellPolicy.validateElevated(
                    argv,
                    scope.absolutePath,
                    flavor = Flavor.GITHUB,
                    bridgeGranted = true,
                )
                assertTrue("$argv -> $v", v is Validation.Allowed)
            }
        } finally {
            scope.deleteRecursively()
            bin.deleteRecursively()
        }
    }
}
