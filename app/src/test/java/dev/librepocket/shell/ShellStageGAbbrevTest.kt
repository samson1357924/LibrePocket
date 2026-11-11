package dev.librepocket.shell

import dev.librepocket.tool.Flavor
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Stage G（P1-2 GNU long-option 唯一前綴縮寫）迴歸：
 * GNU `getopt_long` 接受唯一前綴縮寫，政策必須先規範化後判定
 *（canonicalize-then-judge），不得只比對全鍵。
 *
 * 威脅原語（fixture 與 [ShellTraversalTest] 同源）：
 * scope/subtree 內 `escape -> outside` dir symlink；未修補政策放行縮寫，
 * OS 走訪即讀到域外（`grep --deref TOKEN /scope/subtree` 印 outside/hidden.txt，
 * 實測 GNU grep 3.12；BusyBox 1.37 對縮寫直接 exit 2 `unrecognized option`，
 * 拒絕對其無害）。
 *
 * 覆蓋：
 * - follow 縮寫拒絕（直接＋提權同門，見 [ShellPolicy.isGrepFollowAbbrev] 等）；
 * - 開檔鍵縮寫（`--exclude-f…`、`--files0-from` 家族）分離形槽＋`=v` 嚴審，
 *   見 [ShellPolicy.canonicalFileKey]；
 * - 正向 pin（`--directories=*`／`--devices=*` 列舉、du `--dereference-…`
 *   消歧前綴維持放行）；
 * - 真實語義 load-bearing（`--deref` 真跟隨讀域外；`--exclude-f` 真開檔）＋
 *   跑道層零 spawn（含 multicall toybox 有效形）。
 *
 * Root／Shizuku 提權 runner 皆以 [ShellPolicy.validateElevated] 為先行門
 *（拒即零 spawn，見 `RootSuRunner`／`ShizukuShellRunner` KDoc），故提權側
 * `validateElevated` pin 即覆兩 runner 之門；直接側另以 [RestrictedShell]
 * 斷零 spawn。
 */
class ShellStageGAbbrevTest {

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().canonicalFile

    private fun assumeNonWindows() {
        val osName: String = System.getProperty("os.name") ?: ""
        assumeTrue(!osName.startsWith("Windows"))
    }

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

    private data class FileKeyFixture(
        val scope: File,
        val outside: File,
        val patternsAbs: String,
        val inputAbs: String,
    )

    private fun fileKeyFixture(prefix: String): FileKeyFixture {
        assumeNonWindows()
        val scope = tempDir("$prefix-scope").canonicalFile
        val outside = tempDir("$prefix-outside").canonicalFile
        File(scope, "patterns").writeText("zzz-no-match\n")
        File(scope, "input").writeText("hello world\n")
        return FileKeyFixture(
            scope,
            outside,
            File(scope, "patterns").absolutePath,
            File(scope, "input").absolutePath,
        )
    }

    private fun assertBlockedTraversal(v: Validation, argv: List<String>) {
        assertTrue("expected Denied for $argv, got $v", v is Validation.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        assertTrue(
            "message must stay in traversal family, got ${v.message}",
            v.message.contains("traversal flag denied"),
        )
    }

    private fun assertBlockedRelative(v: Validation, argv: List<String>) {
        assertTrue("expected Denied for $argv, got $v", v is Validation.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        assertTrue(
            "message must stay in relative-path family, got ${v.message}",
            v.message.contains("relative path denied"),
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

    // ---- Stage G follow 縮寫：政策層（直接＋提權同門） ----

    @Test fun grepAbbrevFollowDenied_directAndElevated() {
        val f = traversalFixture("stageG-grep-abbr")
        try {
            val vectors = listOf(
                // GNU 唯一前綴（實測皆跟隨讀域外）。
                listOf("grep", "--deref", "TOKEN", f.subtreeAbs),
                listOf("grep", "--der", "TOKEN", f.subtreeAbs),
                listOf("grep", "--dereference", "TOKEN", f.subtreeAbs),
                listOf("grep", "--dereferenc", "TOKEN", f.subtreeAbs),
                listOf("grep", "--dereference-r", "TOKEN", f.subtreeAbs),
                listOf("grep", "--dereference-re", "TOKEN", f.subtreeAbs),
                // `=value` 附著形（GNU 報錯無參，但 fail-closed 同門拒）。
                listOf("grep", "--dereference=yes", "TOKEN", f.subtreeAbs),
                listOf("grep", "--deref=yes", "TOKEN", f.subtreeAbs),
                // 尾位縮寫亦拒（位置無關，fail-closed）。
                listOf("grep", "TOKEN", f.subtreeAbs, "--deref"),
                listOf("grep", "TOKEN", f.subtreeAbs, "--dereference-r"),
                // multicall 有效形同門。
                listOf("toybox", "grep", "--deref", "TOKEN", f.subtreeAbs),
                listOf("toybox", "grep", "--dereference-r", "TOKEN", f.subtreeAbs),
                // `--de` 在 GNU 屬歧義報錯（devices），拒絕無害，fail-closed pin。
                listOf("grep", "--de", "TOKEN", f.subtreeAbs),
            )
            for (argv in vectors) {
                assertBlockedTraversal(ShellPolicy.validate(argv, f.scope.absolutePath), argv)
                assertBlockedTraversal(
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

    @Test fun duLsAbbrevFollowDenied_directAndElevated() {
        val f = traversalFixture("stageG-du-ls-abbr")
        try {
            val vectors = listOf(
                listOf("du", "--dereferenc", "-a", f.subtreeAbs),
                listOf("du", "--deref", "-a", f.subtreeAbs),
                listOf("du", "--dereference=yes", "-a", f.subtreeAbs),
                listOf("ls", "--dereferenc", f.subtreeAbs),
                listOf("ls", "--deref", f.subtreeAbs),
                listOf("ls", "--dereference=yes", f.subtreeAbs),
                listOf("toybox", "du", "--dereferenc", "-a", f.subtreeAbs),
                listOf("toybox", "ls", "--dereferenc", f.subtreeAbs),
            )
            for (argv in vectors) {
                assertBlockedTraversal(ShellPolicy.validate(argv, f.scope.absolutePath), argv)
                assertBlockedTraversal(
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

    // ---- Stage G 正向 pin：消歧前綴＋列舉值維持放行 ----

    @Test fun abbrevPositives_preserved() {
        val f = traversalFixture("stageG-pos")
        try {
            val vectors = listOf(
                // du 消歧向 `--dereference-args`（僅命令列，不跟隨 interior）。
                listOf("du", "--dereference-", "-a", f.subtreeAbs),
                listOf("du", "--dereference-a", "-a", f.subtreeAbs),
                listOf("du", "--dereference-args", "-a", f.subtreeAbs),
                // grep 列舉值（不跟隨 symlink；`--directories=recurse` ≈ `-r`）。
                listOf("grep", "--directories=recurse", "INSIDE_MARK_abc", f.subtreeAbs),
                listOf("grep", "--directories=skip", "INSIDE_MARK_abc", f.subtreeAbs),
                listOf("grep", "--devices=skip", "INSIDE_MARK_abc", f.subtreeAbs),
                // 尾位合法列舉（trailing-legal pin）。
                listOf("grep", "INSIDE_MARK_abc", f.subtreeAbs, "--directories=recurse"),
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

    // ---- Stage G 真實語義：縮寫 load-bearing＋政策零 spawn ----

    @Test fun abbrevFollowFixtureIsLoadBearing_grepDeref() {
        // 未修補語義直跑（無政策，cwd=scope）：`grep --deref OUTSIDE` 經 `escape`
        // 讀到 outside/hidden.txt（exit 0 且含 OUTSIDE），證縮寫即跟隨。
        assumeNonWindows()
        val grepBin = firstExecutable("/bin/grep", "/usr/bin/grep")
        assumeTrue(grepBin != null)
        val f = traversalFixture("stageG-load-deref")
        try {
            val raw = DefaultProcessRunner(dirRoot = f.scope.absolutePath)
                .run(listOf(grepBin!!, "--deref", "OUTSIDE_MARK_xyz", f.subtreeAbs), 10_000L, null)
            val out = String(raw.stdout, Charsets.UTF_8)
            assertEquals("OS must follow escape via --deref (exit 0), got: $out", 0, raw.exitCode)
            assertTrue("OS must print outside content, got: $out", out.contains("OUTSIDE_MARK_xyz"))
            // 政策拒絕（直接＋提權）且零 spawn。
            val argv = listOf("grep", "--deref", "OUTSIDE_MARK_xyz", f.subtreeAbs)
            assertBlockedTraversal(ShellPolicy.validate(argv, f.scope.absolutePath), argv)
            assertBlockedTraversal(
                ShellPolicy.validateElevated(
                    argv,
                    f.scope.absolutePath,
                    flavor = Flavor.GITHUB,
                    bridgeGranted = true,
                ),
                argv,
            )
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
            val result = shell.execute(argv)
            assertTrue("expected Denied, got $result", result is ShellResult.Denied)
            assertEquals(0, delegateCalls)
        } finally {
            f.scope.deleteRecursively()
            f.outside.deleteRecursively()
        }
    }

    @Test fun directoriesRecurseRealReadsInsideOnly_notOutside() {
        // `--directories=recurse` 真跑（≈ `-r`）只讀域內，不跟隨 `escape`。
        assumeNonWindows()
        val grepBin = firstExecutable("/bin/grep", "/usr/bin/grep")
        assumeTrue(grepBin != null)
        val f = traversalFixture("stageG-pos-real")
        try {
            val okArgv = listOf("grep", "--directories=recurse", "INSIDE_MARK_abc", f.subtreeAbs)
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
            val negRaw = DefaultProcessRunner(dirRoot = f.scope.absolutePath)
                .run(listOf(grepBin, "--directories=recurse", "OUTSIDE_MARK_xyz", f.subtreeAbs), 10_000L, null)
            val negOut = String(negRaw.stdout, Charsets.UTF_8)
            assertTrue("--directories=recurse must not follow escape, got: $negOut", !negOut.contains("OUTSIDE_MARK_xyz"))
        } finally {
            f.scope.deleteRecursively()
            f.outside.deleteRecursively()
        }
    }

    // ---- Stage G 開檔鍵縮寫：分離形槽＋`=v` 嚴審 ----

    @Test fun excludeFromAbbrev_deniedDirectAndElevated() {
        // grep `--exclude-f…` 為 `--exclude-from` 唯一前綴（實測分離形＋附著形皆開檔）：
        // 分離形下一位短橫即檔名，附著形值恆為檔名。
        // `--fil/--fi` 歧義於 `--files-with-matches*`（GNU 報錯），不映射，刻意不測。
        val f = fileKeyFixture("stageG-exclf")
        try {
            val vectors = listOf(
                listOf("grep", "hello", "--exclude-f", "-secret", f.inputAbs),
                listOf("grep", "--exclude-f", "-secret", f.inputAbs),
                listOf("grep", "--exclude-fr", "-secret", f.inputAbs),
                listOf("grep", "--exclude-fro", "-secret", f.inputAbs),
                listOf("grep", "hello", "--exclude-f=-secret", f.inputAbs),
                listOf("grep", "hello", "--exclude-fr=bare/relative", f.inputAbs),
                listOf("grep", "hello", "--exclude-from=bare/relative", f.inputAbs),
                listOf("toybox", "grep", "hello", "--exclude-f", "-secret", f.inputAbs),
                listOf("toybox", "grep", "hello", "--exclude-fr=-secret", f.inputAbs),
            )
            for (argv in vectors) {
                assertBlockedRelative(ShellPolicy.validate(argv, f.scope.absolutePath), argv)
                assertBlockedRelative(
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

    @Test fun duFileKeyAbbrev_deniedDirectAndElevated() {
        // du `--files0-from`（NUL 名單檔）＋`--exclude-from` 分離形短橫即檔名；
        // uutils 實測接受唯一前綴（`--f/--fi/--files/--exclude-` 皆開檔）。
        // du `-X` 短旗殘餘見下注記（超出 Stage G 有界切片）。
        val f = fileKeyFixture("stageG-dufile")
        try {
            val vectors = listOf(
                listOf("du", "--files0-from", "-secret", f.scope.absolutePath),
                listOf("du", "--files0-from=-secret", f.scope.absolutePath),
                listOf("du", "--files0-from=bare/relative", f.scope.absolutePath),
                listOf("du", "--files0-f", "-secret", f.scope.absolutePath),
                listOf("du", "--files0-f=-secret", f.scope.absolutePath),
                listOf("du", "--f", "-secret", f.scope.absolutePath),
                listOf("du", "--exclude-from", "-secret", f.scope.absolutePath),
                listOf("du", "--exclude-from=-secret", f.scope.absolutePath),
                listOf("du", "--exclude-", "-secret", f.scope.absolutePath),
                listOf("toybox", "du", "--files0-from", "-secret", f.scope.absolutePath),
            )
            for (argv in vectors) {
                assertBlockedRelative(ShellPolicy.validate(argv, f.scope.absolutePath), argv)
                assertBlockedRelative(
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

    @Test fun fileKeyAbbrevAllowForms_preservedDirectAndElevated() {
        // 放行 pin：縮寫 `=域內絕對`（use==validated）维持放行。
        val f = fileKeyFixture("stageG-fallow")
        try {
            val vectors = listOf(
                listOf("grep", "hello", "--exclude-f=" + f.patternsAbs, f.inputAbs),
                listOf("grep", "hello", "--exclude-fr=" + f.patternsAbs, f.inputAbs),
                listOf("du", "--files0-from=" + f.patternsAbs, f.scope.absolutePath),
                listOf("du", "--files0-f=" + f.patternsAbs, f.scope.absolutePath),
                listOf("du", "--exclude-from=" + f.patternsAbs, f.scope.absolutePath),
                // 附著 `=v` 不設次位槽：其後的 `-e` 仍是顯式 pattern（非檔名），
                // 整串合法即放行（slot 誤設會把 `-e` 當檔拒掉）。
                listOf("grep", "--exclude-from=" + f.patternsAbs, "-e", "hello", f.inputAbs),
            )
            for (argv in vectors) {
                assertTrue(
                    "positive must stay allowed, got ${ShellPolicy.validate(argv, f.scope.absolutePath)}",
                    ShellPolicy.validate(argv, f.scope.absolutePath) is Validation.Allowed,
                )
                assertTrue(
                    "elevated positive must stay allowed, got ${
                        ShellPolicy.validateElevated(
                            argv,
                            f.scope.absolutePath,
                            flavor = Flavor.GITHUB,
                            bridgeGranted = true,
                        )
                    }",
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

    @Test fun excludeFromAbbrevRealSemanticsMismatchAndPolicyDenies() {
        // 驗用一致實證：`grep --exclude-f <missing> …` 繞過政策直跑，OS 把縮寫解為
        // `--exclude-from` 並開檔（exit 2 且報路徑），證縮寫即開檔鍵；
        // 而短橫分離形政策必須拒絕且零 spawn。
        assumeNonWindows()
        val grepBin = firstExecutable("/bin/grep", "/usr/bin/grep")
        assumeTrue(grepBin != null)
        val f = fileKeyFixture("stageG-exclf-real")
        try {
            val missing = File(f.scope, "stageG-no-such-exclude").absolutePath
            val raw = DefaultProcessRunner(dirRoot = f.scope.absolutePath)
                .run(listOf(grepBin!!, "--exclude-f", missing, "hello", f.inputAbs), 10_000L, null)
            val err = String(raw.stderr, Charsets.UTF_8)
            assertEquals("OS must try opening abbrev as file (exit 2), got: $err", 2, raw.exitCode)
            assertTrue("OS must report the file path, got: $err", err.contains("stageG-no-such-exclude"))
            // 短橫分離形：政策拒絕（直接＋提權）且零 spawn。
            val argv = listOf("grep", "hello", "--exclude-f", "-secret", f.inputAbs)
            assertBlockedRelative(ShellPolicy.validate(argv, f.scope.absolutePath), argv)
            assertBlockedRelative(
                ShellPolicy.validateElevated(
                    argv,
                    f.scope.absolutePath,
                    flavor = Flavor.GITHUB,
                    bridgeGranted = true,
                ),
                argv,
            )
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
            val result = shell.execute(argv)
            assertTrue("expected Denied, got $result", result is ShellResult.Denied)
            assertEquals(0, delegateCalls)
        } finally {
            f.scope.deleteRecursively()
            f.outside.deleteRecursively()
        }
    }

    // ---- 跑道層（直接通道）：零 spawn（含 multicall toybox） ----

    @Test fun stageG_directRunner_zeroSpawn() {
        val f = traversalFixture("stageG-runner")
        try {
            val vectors = listOf(
                listOf("grep", "--deref", "TOKEN", f.subtreeAbs),
                listOf("grep", "--dereference-r", "TOKEN", f.subtreeAbs),
                listOf("grep", "TOKEN", f.subtreeAbs, "--deref"),
                listOf("du", "--dereferenc", "-a", f.subtreeAbs),
                listOf("ls", "--dereferenc", f.subtreeAbs),
                listOf("toybox", "grep", "--deref", "TOKEN", f.subtreeAbs),
                listOf("toybox", "du", "--dereferenc", "-a", f.subtreeAbs),
                listOf("toybox", "ls", "--dereferenc", f.subtreeAbs),
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

    @Test fun stageG_fileKey_directRunner_zeroSpawn() {
        val f = fileKeyFixture("stageG-frunner")
        try {
            val vectors = listOf(
                listOf("grep", "hello", "--exclude-f", "-secret", f.inputAbs),
                listOf("grep", "hello", "--exclude-f=-secret", f.inputAbs),
                listOf("du", "--files0-from", "-secret", f.scope.absolutePath),
                listOf("du", "--files0-from=-secret", f.scope.absolutePath),
                listOf("du", "--exclude-from", "-secret", f.scope.absolutePath),
                listOf("toybox", "grep", "hello", "--exclude-f", "-secret", f.inputAbs),
                listOf("toybox", "du", "--files0-from", "-secret", f.scope.absolutePath),
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
