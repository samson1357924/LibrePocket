package dev.librepocket.shell

import dev.librepocket.tool.Flavor
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeTrue

/**
 * Round-4 Phase J P1-1 multicall 有效 applet 完整 policy parity（直接通道側）。
 *
 * 規範化規則（與提權通道同規則，見 `ElevatedMulticallEffectiveTest`）：
 * - `[dispatcher, applet, ...args]`（dispatcher ∈ toybox/toolbox/busybox）按有效形
 *   `[applet, ...args]` 套用全部拒絕門：applet 黑名單（BLACKLISTED）→ 未知 applet
 *   明示拒（NOT_WHITELISTED，TODO #136-B3，不 blanket allow）→ dispatcher 可信路徑門
 *   → find 高危謂詞（僅有效基為 find 時掃有效操作數）→ 檔案域（有效基／有效操作數，
 *   含 FILE_OPERAND／relative／realpath scope／implicit cwd／取值旗標值槽）。
 * - 有效門全過後，直接通道仍回 NOT_WHITELISTED（dispatcher 本體不在白名單，維持
 *   Phase I 直接拒；提權同形放行，差異僅白名單門）。
 * - 未知 applet 維持拒絕（fail-closed）；B3 `cmd`／`am` 等子命令 allowlist 不做，
 *   只做現有拒絕規則 parity＋TODO。
 *
 * 全 deterministic、負測零 spawn（CapRunner 計數）。
 */
class ShellMulticallEffectiveTest {

    private class CapRunner : ProcessRunner {
        var calls: Int = 0
        var lastArgv: List<String>? = null
        override fun run(argv: List<String>, timeoutMs: Long, env: Map<String, String>?): RawOutput {
            calls++
            lastArgv = argv
            return RawOutput("ok".toByteArray(), ByteArray(0), 0, false)
        }
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().canonicalFile

    private fun assumeNonWindows() {
        val os = System.getProperty("os.name") ?: ""
        assumeTrue(!os.startsWith("Windows"))
    }

    private fun deniedReason(v: Validation, argv: List<String>): ShellDeny {
        assertTrue("$argv -> $v", v is Validation.Denied)
        return (v as Validation.Denied).reason
    }

    private fun assertDirectDeniedZeroSpawn(
        argv: List<String>,
        scope: String?,
        expected: ShellDeny,
    ) {
        val v = ShellPolicy.validate(argv, scope)
        assertEquals("$argv", expected, deniedReason(v, argv))
        val runner = CapRunner()
        val shell = RestrictedShell(
            runner = runner,
            privateRoot = scope,
            execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
        )
        val r = shell.execute(argv)
        assertTrue("$argv -> $r", r is ShellResult.Denied)
        assertEquals("$argv", expected, (r as ShellResult.Denied).reason)
        assertEquals("$argv must not spawn", 0, runner.calls)
    }

    @Test fun findDelete_main_parityWithBare() {
        val scope = tempDir("j-scope")
        try {
            val sub = File(scope, "tree").apply { mkdirs() }
            val bare = listOf("find", sub.absolutePath, "-delete")
            assertEquals(ShellDeny.BLACKLISTED, deniedReason(ShellPolicy.validate(bare, scope.absolutePath), bare))
            val main = listOf("toybox", "find", sub.absolutePath, "-delete")
            assertDirectDeniedZeroSpawn(main, scope.absolutePath, ShellDeny.BLACKLISTED)
            // busybox/toolbox 同門。
            assertDirectDeniedZeroSpawn(
                listOf("busybox", "find", sub.absolutePath, "-delete"),
                scope.absolutePath,
                ShellDeny.BLACKLISTED,
            )
        } finally {
            scope.deleteRecursively()
        }
    }

    @Test fun findExec_main_parityWithBare() {
        val scope = tempDir("j-exec-scope")
        try {
            val sub = File(scope, "tree").apply { mkdirs() }
            val bare = listOf("find", sub.absolutePath, "-exec", "rm", "{}", "+")
            assertEquals(ShellDeny.BLACKLISTED, deniedReason(ShellPolicy.validate(bare, scope.absolutePath), bare))
            assertDirectDeniedZeroSpawn(
                listOf("toybox", "find", sub.absolutePath, "-exec", "rm", "{}", "+"),
                scope.absolutePath,
                ShellDeny.BLACKLISTED,
            )
            assertDirectDeniedZeroSpawn(
                listOf("toybox", "find", sub.absolutePath, "-execdir", "id", "{}", "+"),
                scope.absolutePath,
                ShellDeny.BLACKLISTED,
            )
        } finally {
            scope.deleteRecursively()
        }
    }

    @Test fun catRelative_main_parityWithBare() {
        val scope = tempDir("j-rel-scope")
        try {
            val bare = listOf("cat", "entry.txt")
            assertEquals(ShellDeny.BLACKLISTED, deniedReason(ShellPolicy.validate(bare, scope.absolutePath), bare))
            assertDirectDeniedZeroSpawn(
                listOf("toybox", "cat", "entry.txt"),
                scope.absolutePath,
                ShellDeny.BLACKLISTED,
            )
            // 取值旗標值槽仍豁免（不誤殺）：head -n 20 <scope/file> 有效形放行門，
            // 直接通道最終仍以 dispatcher NOT_WHITELISTED 拒（非 BLACKLISTED）。
            val f = File(scope, "x.txt").apply { writeText("hi") }
            val v = ShellPolicy.validate(
                listOf("toybox", "head", "-n", "20", f.absolutePath),
                scope.absolutePath,
            )
            assertTrue("$v", v is Validation.Denied)
            assertEquals(ShellDeny.NOT_WHITELISTED, (v as Validation.Denied).reason)
        } finally {
            scope.deleteRecursively()
        }
    }

    @Test fun catSymlink_main_parityWithBare() {
        assumeNonWindows()
        val scope = tempDir("j-link-scope")
        val outside = tempDir("j-link-out")
        try {
            val victim = File(outside, "secret.txt").apply { writeText("secret") }
            val link = File(scope, "link.txt").toPath()
            Files.createSymbolicLink(link, victim.toPath())
            val bare = listOf("cat", link.toString())
            assertEquals(ShellDeny.BLACKLISTED, deniedReason(ShellPolicy.validate(bare, scope.absolutePath), bare))
            assertDirectDeniedZeroSpawn(
                listOf("toybox", "cat", link.toString()),
                scope.absolutePath,
                ShellDeny.BLACKLISTED,
            )
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun unknownApplet_main_staysDenied() {
        val scope = tempDir("j-unknown-scope")
        try {
            for (argv in listOf(
                listOf("toybox", "ps"),
                listOf("toybox", "foobar123"),
                listOf("busybox", "ps"),
            )) {
                assertDirectDeniedZeroSpawn(argv, scope.absolutePath, ShellDeny.NOT_WHITELISTED)
                // 提權同策亦拒（B3 TODO，不 blanket allow）。
                val e = ShellPolicy.validateElevated(
                    argv,
                    scope.absolutePath,
                    flavor = Flavor.GITHUB,
                    bridgeGranted = true,
                )
                assertTrue("elevated $argv -> $e", e is Validation.Denied)
                assertEquals(ShellDeny.NOT_WHITELISTED, (e as Validation.Denied).reason)
            }
        } finally {
            scope.deleteRecursively()
        }
    }

    @Test fun implicitCwd_main_parityWithBareWhenNullScope() {
        // 有效基 find/ls 無明確絕對路徑＋null 作用域即隱式 cwd，必須 BLACKLISTED。
        for (argv in listOf(
            listOf("find", "-maxdepth", "1"),
            listOf("toybox", "find", "-maxdepth", "1"),
            listOf("toybox", "ls"),
            listOf("toybox", "find"),
        )) {
            val v = ShellPolicy.validate(argv, privateRoot = null)
            assertTrue("$argv -> $v", v is Validation.Denied)
            assertEquals("$argv", ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
            val runner = CapRunner()
            val shell = RestrictedShell(
                runner = runner,
                privateRoot = null,
                execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
            )
            val r = shell.execute(argv)
            assertTrue("$argv -> $r", r is ShellResult.Denied)
            assertEquals(0, runner.calls)
        }
    }

    @Test fun safeMain_staysDispatcherDenied_notBlacklisted() {
        // 有效門全過的安全形（如 find -print）直接通道仍以 dispatcher NOT_WHITELISTED
        // 拒（維持 Phase I），不誤報 BLACKLISTED；提權同形放行（見 github 側）。
        val scope = tempDir("j-safe-scope")
        try {
            val sub = File(scope, "tree").apply { mkdirs() }
            val main = listOf("toybox", "find", sub.absolutePath, "-print")
            val v = ShellPolicy.validate(main, scope.absolutePath)
            assertTrue("$main -> $v", v is Validation.Denied)
            assertEquals(ShellDeny.NOT_WHITELISTED, (v as Validation.Denied).reason)
            val e = ShellPolicy.validateElevated(
                main,
                scope.absolutePath,
                flavor = Flavor.GITHUB,
                bridgeGranted = true,
            )
            assertTrue("elevated $main -> $e", e is Validation.Allowed)
            assertEquals("find", (e as Validation.Allowed).binary)
            // 別名對照：同有效形 bare 即 Allowed（無 dispatcher 門）。
            val bare = listOf("find", sub.absolutePath, "-print")
            assertTrue("${ShellPolicy.validate(bare, scope.absolutePath)}", ShellPolicy.validate(bare, scope.absolutePath) is Validation.Allowed)
        } finally {
            scope.deleteRecursively()
        }
    }

    @Test fun absoluteDispatcher_main_sameAsBare() {
        val scope = tempDir("j-abs-scope")
        // 暫存可信目錄注入（產品用 /system/bin；單測同步傳同表給 validate）。
        val bin = tempDir("j-abs-bin")
        try {
            val sub = File(scope, "tree").apply { mkdirs() }
            val abs = listOf("${bin.absolutePath}/toybox", "find", sub.absolutePath, "-delete")
            val v = ShellPolicy.validate(abs, scope.absolutePath, trustedBinDirs = listOf(bin.absolutePath))
            assertTrue("$abs -> $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        } finally {
            scope.deleteRecursively()
            bin.deleteRecursively()
        }
    }
}
