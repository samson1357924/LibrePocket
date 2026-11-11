package dev.librepocket.shell

import dev.librepocket.tool.Flavor
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Stage D（P1-2 recursive symlink-follow）迴歸：
 * `grep -R`／`find -L`／`du -L`／`ls -L` 類跟隨旗標在 spawn 後走訪
 * interior symlink 後代，操作數門（[ShellPolicy.checkPhysicalOperandGate] 本鏈）
 * 看不見，故必須拒旗標本身（fail-closed）。
 *
 * 威脅原語（fixture 有效性見 [traversalFixtureIsLoadBearing]）：
 * scope/subtree 內 `escape -> outside` dir symlink；未修補政策放行跟隨旗標，
 * OS 走訪即讀到域外（`grep -R TOKEN /scope/subtree` 印 outside/hidden.txt，
 * `find -L` 找到 outside/hidden.txt）。修前 [ShellPolicy.checkPathArgs] 只驗
 * CLI 操作數本鏈，`find -L`／`grep -R` 等直接放行。
 *
 * 決策 pin（見 [ShellPolicy.isFollowFlag] 決策表）：
 * - DENY：grep `-R`／`--dereference-recursive`／含 `R` 單橫、find `-L`／
 *   `-follow`／含 `L` 單橫、du `-L`／`--dereference`／含 `L` 單橫、ls `-L`／
 *   `--dereference*`／含 `L` 單橫。
 * - ALLOW：grep `-r`／`--recursive`、find `-H`／`-P`、du `-D`／
 *   `--dereference-args`、ls `-R` 單獨、`-eROOT` 附著 pattern。
 * - guest 容器內（`isGuest && privateRoot != null`）豁免，與既有 dash／bare
 *   同設計；guest＋null 仍 fail-closed。
 * - multicall 有效基同門（`toybox grep -R …` 即 `[grep -R …]` 語義）。
 */
class ShellTraversalTest {

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().canonicalFile

    private fun assumeNonWindows() {
        val osName: String = System.getProperty("os.name") ?: ""
        assumeTrue(!osName.startsWith("Windows"))
    }

    /**
     * 攻擊 fixture：scope/subtree 內 `escape -> outside` dir symlink。
     * inside.txt 含 INSIDE，outside/hidden.txt 含 OUTSIDE（相異 marker，
     * 正向斷言可用 `lack OUTSIDE` 證未穿出）。
     */
    private data class TraversalFixture(
        val scope: File,
        val outside: File,
        val subtree: File,
        val insideAbs: String,
        val subtreeAbs: String,
    )

    private fun traversalFixture(prefix: String): TraversalFixture {
        assumeNonWindows()
        val scope = tempDir("$prefix-scope").canonicalFile
        val outside = tempDir("$prefix-outside").canonicalFile
        File(outside, "hidden.txt").writeText("OUTSIDE_MARK_xyz\n")
        val subtree = File(scope, "subtree").apply { mkdirs() }
        File(subtree, "inside.txt").writeText("INSIDE_MARK_abc\n")
        Files.createSymbolicLink(File(subtree, "escape").toPath(), outside.toPath())
        return TraversalFixture(
            scope,
            outside,
            subtree,
            File(subtree, "inside.txt").absolutePath,
            subtree.absolutePath,
        )
    }

    private fun assertBlocked(v: Validation, argv: List<String>) {
        assertTrue("expected Denied for $argv, got $v", v is Validation.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        assertTrue(
            "message must stay in traversal family, got ${v.message}",
            v.message.contains("traversal flag denied"),
        )
    }

    private fun firstExecutable(vararg paths: String): String? =
        paths.firstOrNull { File(it).canExecute() }

    /** 計數＋捕獲 spawn argv 的假跑道（政策拒絕即零呼叫）。 */
    private class ArgvCaptureRunner : ProcessRunner {
        var calls: Int = 0
        var lastArgv: List<String> = emptyList()
        override fun run(argv: List<String>, timeoutMs: Long, env: Map<String, String>?): RawOutput {
            calls++
            lastArgv = argv.toList()
            return RawOutput("ok".toByteArray(), ByteArray(0), 0, false)
        }
    }

    // ---- 攻擊向量：政策層（直接＋提權同門） ----

    @Test fun grepFollowDenied_directAndElevated() {
        val f = traversalFixture("trav-grep")
        try {
            val vectors = listOf(
                listOf("grep", "-R", "TOKEN", f.subtreeAbs),
                listOf("grep", "--dereference-recursive", "TOKEN", f.subtreeAbs),
                listOf("grep", "-rR", "TOKEN", f.subtreeAbs),
                listOf("grep", "-iR", "TOKEN", f.subtreeAbs),
                listOf("grep", "-R", f.subtreeAbs),
                listOf("toybox", "grep", "-R", "TOKEN", f.subtreeAbs),
                // 尾位跟隨旗標亦拒（位置無關，fail-closed）。
                listOf("grep", "TOKEN", f.subtreeAbs, "-R"),
                listOf("grep", "TOKEN", f.subtreeAbs, "--dereference-recursive"),
            )
            for (argv in vectors) {
                assertBlocked(ShellPolicy.validate(argv, f.scope.absolutePath), argv)
                assertBlocked(
                    ShellPolicy.validateElevated(
                        argv,
                        f.scope.absolutePath,
                        flavor = Flavor.GITHUB,
                        bridgeGranted = true,
                    ),
                    argv,
                )
            }
            // `--dereference-recursive=…` 附著形亦拒。
            val eq = listOf("grep", "--dereference-recursive=yes", "TOKEN", f.subtreeAbs)
            assertBlocked(ShellPolicy.validate(eq, f.scope.absolutePath), eq)
            // `find` 位置無關：`-follow` 在路徑後亦拒。
            val followAfter = listOf("find", f.subtreeAbs, "-follow", "-name", "hidden.txt")
            assertBlocked(ShellPolicy.validate(followAfter, f.scope.absolutePath), followAfter)
        } finally {
            f.scope.deleteRecursively()
            f.outside.deleteRecursively()
        }
    }

    @Test fun grepValueFlagBoolean_noBypass_directAndElevated() {
        // Stage F：`-n`／`-c` 在 grep 是布林（--line-number／--count），不是取值旗標；
        // `grep -n -R …` 的 `-R` 不得被 `prevArg` 值豁免吞掉，必須仍觸 traversal 門。
        // （`-d` 在 grep 仍是取值 --directories，維持豁免，不在此列。）
        val f = traversalFixture("trav-grep-nc")
        try {
            val vectors = listOf(
                listOf("grep", "-n", "-R", "TOKEN", f.subtreeAbs),
                listOf("grep", "-c", "-R", "TOKEN", f.subtreeAbs),
                listOf("toybox", "grep", "-n", "-R", "TOKEN", f.subtreeAbs),
            )
            for (argv in vectors) {
                assertBlocked(ShellPolicy.validate(argv, f.scope.absolutePath), argv)
                assertBlocked(
                    ShellPolicy.validateElevated(
                        argv,
                        f.scope.absolutePath,
                        flavor = Flavor.GITHUB,
                        bridgeGranted = true,
                    ),
                    argv,
                )
            }
            // 跑道層零 spawn（直接通道）。
            for (argv in vectors) {
                val runner = ArgvCaptureRunner()
                val shell = RestrictedShell(
                    runner = runner,
                    privateRoot = f.scope.absolutePath,
                    execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
                )
                val result = shell.execute(argv)
                assertTrue("expected Denied for $argv, got $result", result is ShellResult.Denied)
                assertEquals(ShellDeny.BLACKLISTED, (result as ShellResult.Denied).reason)
                assertTrue(
                    "message must stay in traversal family for $argv",
                    (result as ShellResult.Denied).message.contains("traversal flag denied"),
                )
                assertEquals("spawn must be zero for $argv", 0, runner.calls)
            }
        } finally {
            f.scope.deleteRecursively()
            f.outside.deleteRecursively()
        }
    }

    @Test fun valueFlagPositives_preserved() {
        // 無迴歸 pin：grep `-n` 布林＋pattern 槽、`head -n 20`、`stat -c %s` 維持放行。
        val f = traversalFixture("trav-valpos")
        try {
            val vectors = listOf(
                listOf("grep", "-n", "hello", f.insideAbs),
                listOf("head", "-n", "20", f.insideAbs),
                listOf("stat", "-c", "%s", f.insideAbs),
            )
            for (argv in vectors) {
                assertTrue(
                    "positive must stay allowed, got ${ShellPolicy.validate(argv, f.scope.absolutePath)} for $argv",
                    ShellPolicy.validate(argv, f.scope.absolutePath) is Validation.Allowed,
                )
                assertTrue(
                    "elevated positive must stay allowed for $argv",
                    ShellPolicy.validateElevated(
                        argv,
                        f.scope.absolutePath,
                        flavor = Flavor.GITHUB,
                        bridgeGranted = true,
                    ) is Validation.Allowed,
                )
            }
        } finally {
            f.scope.deleteRecursively()
            f.outside.deleteRecursively()
        }
    }

    @Test fun findFollowDenied_directAndElevated() {
        val f = traversalFixture("trav-find")
        try {
            val vectors = listOf(
                listOf("find", "-L", f.subtreeAbs, "-name", "hidden.txt"),
                listOf("find", f.subtreeAbs, "-follow", "-name", "hidden.txt"),
                listOf("find", "-HL", f.subtreeAbs, "-name", "hidden.txt"),
                listOf("toybox", "find", "-L", f.subtreeAbs, "-name", "hidden.txt"),
            )
            for (argv in vectors) {
                assertBlocked(ShellPolicy.validate(argv, f.scope.absolutePath), argv)
                assertBlocked(
                    ShellPolicy.validateElevated(
                        argv,
                        f.scope.absolutePath,
                        flavor = Flavor.GITHUB,
                        bridgeGranted = true,
                    ),
                    argv,
                )
            }
        } finally {
            f.scope.deleteRecursively()
            f.outside.deleteRecursively()
        }
    }

    @Test fun duLsFollowDenied_directAndElevated() {
        val f = traversalFixture("trav-du-ls")
        try {
            val vectors = listOf(
                listOf("du", "-L", "-a", f.subtreeAbs),
                listOf("du", "--dereference", "-a", f.subtreeAbs),
                listOf("du", "-aL", f.subtreeAbs),
                listOf("ls", "-L", f.subtreeAbs),
                listOf("ls", "--dereference", f.subtreeAbs),
                listOf("ls", "-lL", f.subtreeAbs),
                listOf("toybox", "du", "-L", "-a", f.subtreeAbs),
                listOf("toybox", "ls", "-L", f.subtreeAbs),
                // `=value` 附著形亦拒。
                listOf("du", "--dereference=yes", "-a", f.subtreeAbs),
                listOf("ls", "--dereference=yes", f.subtreeAbs),
            )
            for (argv in vectors) {
                assertBlocked(ShellPolicy.validate(argv, f.scope.absolutePath), argv)
                assertBlocked(
                    ShellPolicy.validateElevated(
                        argv,
                        f.scope.absolutePath,
                        flavor = Flavor.GITHUB,
                        bridgeGranted = true,
                    ),
                    argv,
                )
            }
        } finally {
            f.scope.deleteRecursively()
            f.outside.deleteRecursively()
        }
    }

    // ---- 正向 pin：不得誤殺 ----

    @Test fun positives_preserved() {
        val f = traversalFixture("trav-pos")
        try {
            val vectors = listOf(
                // grep 小寫 -r 不跟隨。
                listOf("grep", "-r", "INSIDE_MARK_abc", f.subtreeAbs),
                listOf("grep", "--recursive", "INSIDE_MARK_abc", f.subtreeAbs),
                // -e 附著 pattern 含 R（ROOT）不得誤殺。
                listOf("grep", "-eROOT", f.insideAbs),
                listOf("grep", "-e", "ROOT", f.insideAbs),
                // find -H/-P 維持放行（-H 僅命令列，操作數門已拒 symlink）。
                listOf("find", "-H", f.subtreeAbs, "-name", "inside.txt"),
                listOf("find", "-P", f.subtreeAbs, "-name", "inside.txt"),
                // du -D/--dereference-args 僅命令列，維持放行。
                listOf("du", "-D", "-a", f.subtreeAbs),
                listOf("du", "--dereference-args", "-a", f.subtreeAbs),
                // ls -R 單獨僅列名，維持放行。
                listOf("ls", "-R", f.subtreeAbs),
            )
            for (argv in vectors) {
                assertTrue(
                    "positive must stay allowed, got ${ShellPolicy.validate(argv, f.scope.absolutePath)} for $argv",
                    ShellPolicy.validate(argv, f.scope.absolutePath) is Validation.Allowed,
                )
                assertTrue(
                    "elevated positive must stay allowed for $argv",
                    ShellPolicy.validateElevated(
                        argv,
                        f.scope.absolutePath,
                        flavor = Flavor.GITHUB,
                        bridgeGranted = true,
                    ) is Validation.Allowed,
                )
            }
            // find 合法小寫謂詞不受 L 門影響（-lname/-ilname/-links/-perm 皆小寫 l）。
            // 註：-lname 值槽不在 VALUE_TAKING_FLAGS 內（既有行為，值仍按路徑擋），
            // 此處僅 pin 謂詞 token 本身不觸發 traversal 誤殺（無值形即放行）。
            for (pred in listOf("-lname", "-ilname", "-links", "-perm")) {
                val argv = listOf("find", f.subtreeAbs, pred)
                assertTrue(
                    "predicate $pred must not trip L-gate, got ${ShellPolicy.validate(argv, f.scope.absolutePath)}",
                    ShellPolicy.validate(argv, f.scope.absolutePath) is Validation.Allowed,
                )
            }
        } finally {
            f.scope.deleteRecursively()
            f.outside.deleteRecursively()
        }
    }

    @Test fun guestChannelChoice_containerInternalAllowed_nullDenied() {
        val f = traversalFixture("trav-guest")
        try {
            val vectors = listOf(
                listOf("grep", "-R", "TOKEN", f.subtreeAbs),
                listOf("find", "-L", f.subtreeAbs, "-name", "hidden.txt"),
                listOf("du", "-L", "-a", f.subtreeAbs),
                listOf("ls", "-L", f.subtreeAbs),
            )
            for (argv in vectors) {
                assertTrue(
                    "guest+scope must stay container-internal allowed for $argv, got " +
                        ShellPolicy.validate(argv, f.scope.absolutePath, isGuest = true),
                    ShellPolicy.validate(argv, f.scope.absolutePath, isGuest = true) is Validation.Allowed,
                )
                val nullV = ShellPolicy.validate(argv, null, isGuest = true)
                assertTrue("guest+null must stay fail-closed for $argv, got $nullV", nullV is Validation.Denied)
                assertEquals(ShellDeny.BLACKLISTED, (nullV as Validation.Denied).reason)
            }
        } finally {
            f.scope.deleteRecursively()
            f.outside.deleteRecursively()
        }
    }

    // ---- 真實語義：fixture load-bearing＋政策零 spawn ----

    @Test fun traversalFixtureIsLoadBearing_grep() {
        // 未修補語義直跑（無政策，cwd=scope）：`grep -R OUTSIDE` 經 `escape`
        // 讀到 outside/hidden.txt（exit 0 且含 OUTSIDE），證 fixture 有效。
        assumeNonWindows()
        val grepBin = firstExecutable("/bin/grep", "/usr/bin/grep")
        assumeTrue(grepBin != null)
        val f = traversalFixture("trav-load-grep")
        try {
            val raw = DefaultProcessRunner(dirRoot = f.scope.absolutePath)
                .run(listOf(grepBin!!, "-R", "OUTSIDE_MARK_xyz", f.subtreeAbs), 10_000L, null)
            val out = String(raw.stdout, Charsets.UTF_8)
            assertEquals("OS must follow escape via -R (exit 0), got: $out", 0, raw.exitCode)
            assertTrue("OS must print outside content, got: $out", out.contains("OUTSIDE_MARK_xyz"))
            // 政策拒絕（直接＋提權）且零 spawn。
            val argv = listOf("grep", "-R", "OUTSIDE_MARK_xyz", f.subtreeAbs)
            assertBlocked(ShellPolicy.validate(argv, f.scope.absolutePath), argv)
            assertBlocked(
                ShellPolicy.validateElevated(
                    argv,
                    f.scope.absolutePath,
                    flavor = Flavor.GITHUB,
                    bridgeGranted = true,
                ),
                argv,
            )
        } finally {
            f.scope.deleteRecursively()
            f.outside.deleteRecursively()
        }
    }

    @Test fun traversalFixtureIsLoadBearing_find() {
        // `find -L subtree -name hidden.txt` 經 `escape` 找到 outside/hidden.txt。
        assumeNonWindows()
        val findBin = firstExecutable("/bin/find", "/usr/bin/find")
        assumeTrue(findBin != null)
        val f = traversalFixture("trav-load-find")
        try {
            val raw = DefaultProcessRunner(dirRoot = f.scope.absolutePath)
                .run(listOf(findBin!!, "-L", f.subtreeAbs, "-name", "hidden.txt"), 10_000L, null)
            val out = String(raw.stdout, Charsets.UTF_8)
            assertEquals("OS must follow escape via -L (exit 0), got: $out", 0, raw.exitCode)
            assertTrue("OS must list outside hidden.txt, got: $out", out.contains("hidden.txt"))
            val argv = listOf("find", "-L", f.subtreeAbs, "-name", "hidden.txt")
            assertBlocked(ShellPolicy.validate(argv, f.scope.absolutePath), argv)
            assertBlocked(
                ShellPolicy.validateElevated(
                    argv,
                    f.scope.absolutePath,
                    flavor = Flavor.GITHUB,
                    bridgeGranted = true,
                ),
                argv,
            )
        } finally {
            f.scope.deleteRecursively()
            f.outside.deleteRecursively()
        }
    }

    @Test fun positiveRealReadsInsideOnly_notOutside() {
        // `grep -r INSIDE` 真跑只讀域內 inside.txt，不含 OUTSIDE。
        assumeNonWindows()
        val grepBin = firstExecutable("/bin/grep", "/usr/bin/grep")
        assumeTrue(grepBin != null)
        val f = traversalFixture("trav-pos-real")
        try {
            val okArgv = listOf("grep", "-r", "INSIDE_MARK_abc", f.subtreeAbs)
            assertTrue(
                ShellPolicy.validate(okArgv, f.scope.absolutePath) is Validation.Allowed,
            )
            val grepDir = File(grepBin!!).parentFile.canonicalPath
            val realShell = RestrictedShell(
                runner = DefaultProcessRunner(dirRoot = f.scope.absolutePath),
                privateRoot = f.scope.absolutePath,
                execSearchDirs = listOf(grepDir),
                execAllowedRoots = listOf(grepDir),
                execIsWritable = { false },
            )
            val ok = realShell.execute(okArgv)
            assertTrue("expected Ok, got $ok", ok is ShellResult.Ok)
            val stdout = (ok as ShellResult.Ok).stdout
            assertTrue("must read inside, got: $stdout", stdout.contains("INSIDE_MARK_abc"))
            assertTrue("must never read outside, got: $stdout", !stdout.contains("OUTSIDE_MARK_xyz"))
            // 小寫 `-r` 真不跟隨：域外 marker 直接搜亦無命中且非零退出。
            val negRaw = DefaultProcessRunner(dirRoot = f.scope.absolutePath)
                .run(listOf(grepBin!!, "-r", "OUTSIDE_MARK_xyz", f.subtreeAbs), 10_000L, null)
            val negOut = String(negRaw.stdout, Charsets.UTF_8)
            assertTrue("grep -r must not follow escape, got: $negOut", !negOut.contains("OUTSIDE_MARK_xyz"))
            assertTrue("grep -r OUTSIDE must find nothing, got ${negRaw.exitCode}", negRaw.exitCode != 0)
            // 攻擊形真跑道零 spawn（政策先拒）。
            var delegateCalls = 0
            val counting: ProcessRunner = object : ProcessRunner {
                override fun run(argv: List<String>, timeoutMs: Long, env: Map<String, String>?): RawOutput {
                    delegateCalls++
                    return RawOutput("ok".toByteArray(), ByteArray(0), 0, false)
                }
            }
            val shell = RestrictedShell(
                runner = counting,
                privateRoot = f.scope.absolutePath,
                execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
            )
            val deniedArgv = listOf("grep", "-R", "OUTSIDE_MARK_xyz", f.subtreeAbs)
            val denied = shell.execute(deniedArgv)
            assertTrue("expected Denied, got $denied", denied is ShellResult.Denied)
            assertEquals(0, delegateCalls)
        } finally {
            f.scope.deleteRecursively()
            f.outside.deleteRecursively()
        }
    }

    // ---- 跑道層（直接通道）：零 spawn ----

    @Test fun directRunner_zeroSpawnOnTraversal() {
        val f = traversalFixture("trav-runner")
        try {
            val vectors = listOf(
                listOf("grep", "-R", "TOKEN", f.subtreeAbs),
                listOf("grep", "--dereference-recursive", "TOKEN", f.subtreeAbs),
                listOf("find", "-L", f.subtreeAbs, "-name", "hidden.txt"),
                listOf("find", f.subtreeAbs, "-follow", "-name", "hidden.txt"),
                listOf("du", "-L", "-a", f.subtreeAbs),
                listOf("ls", "-L", f.subtreeAbs),
                listOf("toybox", "grep", "-R", "TOKEN", f.subtreeAbs),
                listOf("toybox", "find", "-L", f.subtreeAbs),
            )
            for (argv in vectors) {
                val runner = ArgvCaptureRunner()
                val shell = RestrictedShell(
                    runner = runner,
                    privateRoot = f.scope.absolutePath,
                    execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
                )
                val result = shell.execute(argv)
                assertTrue("expected Denied for $argv, got $result", result is ShellResult.Denied)
                assertEquals(ShellDeny.BLACKLISTED, (result as ShellResult.Denied).reason)
                assertEquals("spawn must be zero for $argv", 0, runner.calls)
            }
        } finally {
            f.scope.deleteRecursively()
            f.outside.deleteRecursively()
        }
    }
}
