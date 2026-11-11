package dev.librepocket.shell

import dev.librepocket.tool.Flavor
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Stage A（P1 dash bypass）迴歸：`--` 後／`grep -f` 值槽的短橫形態
 * （`-secret`）即相對檔名操作數，必須 fail-closed（`Denied(BLACKLISTED)`）
 * 且零 spawn，不得洞穿 FileScope＋NOFOLLOW 實體門。
 *
 * 威脅原語（fixture 有效性見 [dashSymlinkFixtureIsLoadBearing]）：
 * scope 內預植 `-secret -> outside/secret.txt` symlink；政策若放行短橫形態，
 * OS 打開 cwd 相對 `-secret` 即讀到域外。修前 [ShellPolicy.checkPathArgs]
 * 對 `startsWith("-")` 無 `=` 形態一律放行（`checkBareFileOperand` 早返 null），
 * 且 `--` 只翻 `endOfOptions` 而不改變短橫處置，故直接／提權（Root／Shizuku
 * `cd privateRoot && exec` 釘死 cwd）／multicall 有效形全受影響。
 *
 * 豁免 pin（防誤殺，見各用例）：
 * - `grep -- -r` 的首位短橫仍是 pattern（pattern 槽先於短橫封堵消費）；
 * - `-` stdin 哨兵（含 `grep -f -`、旗標位的 `--file=-`）維持放行；
 *   但 `--` 後／`-f` 槽操作數位的 `--file=-|/abs` 一律拒（deny-all，
 *   無旗標語義，即字面檔名，見 `eqOperandExemptions_guestAndNoFileAllowed`）；
 * - [ShellPolicy.NO_FILE_BINARIES]（`echo -- -secret` 只是字串）維持放行；
 * - guest 容器內（`isGuest && privateRoot != null`）維持放行（cwd 已釘
 *   `-w /`＋`-r rootfs`，容器命名空間相對路徑；與既有 bare 放行同設計，
 *   見 [ShellPolicy] KDoc Guest 通道抉擇）；
 * - `--color=always` 等 `--opt=value` 非路徑鍵旗標位維持放行；
 * - `ls -f`（排序旗標）不設 REQUIRED-FILENAME 槽，只限 grep。
 *
 * 殘餘揭露（刻意不封，超出本次 Stage A 範圍）：
 * - 旗標位的 `--opt=-dash` 值（如未知 binary 的 `--db=-secret`）仍靠 cwd
 *   釘死放行（已知 FILE_OPERAND／`--file` 鍵已拒；見 [ShellPolicy] LOW 註記）；
 * - 旗標位的尾 `=` 空值（`--opt=`）維持既有用法定罪（用法錯誤，無檔案觸及）；
 *   `--` 後／`-f` 槽內的操作數 `--opt=`（含 `--file=`）一律 BLACKLISTED 拒。
 * （`=` 操作數位的 `--opt=v`（含 `--file=-|/abs`，deny-all）如
 * `grep -f --color=auto`／`cat -- --file=/abs` 已由操作數門拒絕，
 * 見 `eqOperandAfterDashDash_deniedDirectAndElevated`。）
 */
class ShellDashScopeTest {

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().canonicalFile

    private fun assumeNonWindows() {
        val osName: String = System.getProperty("os.name") ?: ""
        assumeTrue(!osName.startsWith("Windows"))
    }

    /**
     * 攻擊 fixture：scope 內 `-secret` symlink → outside/secret.txt。
     * 回傳 Triple(scope, outside, link)；呼叫方負責 deleteRecursively。
     */
    private fun dashFixture(prefix: String): Triple<File, File, File> {
        assumeNonWindows()
        val scope = tempDir("${prefix}-scope")
        val outside = tempDir("${prefix}-outside")
        File(outside, "secret.txt").writeText("OUTSIDE")
        val work = File(scope, "work").apply { mkdirs() }
        File(work, "known.txt").writeText("INSIDE")
        val link = File(scope, "-secret")
        Files.createSymbolicLink(link.toPath(), File(outside, "secret.txt").toPath())
        return Triple(scope, outside, link)
    }

    private fun assertBlocked(v: Validation, argv: List<String>) {
        assertTrue("expected Denied for $argv, got $v", v is Validation.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        assertTrue(
            "message must stay in relative-path family, got ${v.message}",
            v.message.contains("relative path denied"),
        )
    }

    // ---- 攻擊向量：政策層（直接＋提權同門） ----

    @Test fun dashAfterDashDash_deniedDirectAndElevated() {
        val (scope, outside) = dashFixture("dash-dd")
        try {
            val vectors = listOf(
                listOf("cat", "--", "-secret"),
                listOf("grep", "--", "pattern", "-secret"),
                listOf("grep", "-f", "-patterns", "allowed.txt"),
                listOf("toybox", "cat", "--", "-secret"),
            )
            for (argv in vectors) {
                assertBlocked(ShellPolicy.validate(argv, scope.absolutePath), argv)
                assertBlocked(
                    ShellPolicy.validateElevated(
                        argv,
                        scope.absolutePath,
                        flavor = Flavor.GITHUB,
                        bridgeGranted = true,
                    ),
                    argv,
                )
            }
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun dashFileSlotVariants_denied() {
        val (scope, outside) = dashFixture("dash-slot")
        try {
            val vectors = listOf(
                // `-f` 精確 token 槽：下一位短橫即檔名。
                listOf("grep", "-f", "-patterns"),
                // `--file` 精確 token 槽。
                listOf("grep", "--file", "-patterns"),
                // `--file=` 單 token：短橫值即檔名（未知提權＋scope 亦拒）。
                listOf("grep", "--file=-patterns"),
                // 槽內 `--` 是檔名，不是終止符。
                listOf("grep", "-f", "--"),
                // `-f` 附著值：`-fX` 的 X 恒為檔名。
                listOf("grep", "-fpatterns"),
            )
            for (argv in vectors) {
                assertBlocked(ShellPolicy.validate(argv, scope.absolutePath), argv)
                assertBlocked(
                    ShellPolicy.validateElevated(
                        argv,
                        scope.absolutePath,
                        flavor = Flavor.GITHUB,
                        bridgeGranted = true,
                    ),
                    argv,
                )
            }
            // 未知提權 binary＋scope：短橫形在檔位亦拒（旗標應在 `--` 之前）；
            // 非短橫 bare（子命令／包名）維持 cwd 釘死放行（既有設計）。
            assertBlocked(
                ShellPolicy.validateElevated(
                    listOf("dumpsys", "--", "-secret"),
                    scope.absolutePath,
                    flavor = Flavor.GITHUB,
                    bridgeGranted = true,
                ),
                listOf("dumpsys", "--", "-secret"),
            )
            assertTrue(
                ShellPolicy.validateElevated(
                    listOf("dumpsys", "activity"),
                    scope.absolutePath,
                    flavor = Flavor.GITHUB,
                    bridgeGranted = true,
                ) is Validation.Allowed,
            )
            // null 作用域未知 binary 短橫檔位亦拒（fail-closed）。
            assertBlocked(
                ShellPolicy.validateElevated(
                    listOf("dumpsys", "--", "-secret"),
                    null,
                    flavor = Flavor.GITHUB,
                    bridgeGranted = true,
                ),
                listOf("dumpsys", "--", "-secret"),
            )
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun absoluteSymlinkContrast_stillDeniedByPhysicalGate() {
        // 對照：絕對形態的同一 symlink 經 B1 實體門拒（deny 理由不同）；
        // 短橫形態則由 Stage A 詞法門拒（上兩案）。兩門互補，不互相掩蓋。
        val (scope, outside, link) = dashFixture("dash-contrast")
        try {
            val v = ShellPolicy.validate(listOf("cat", link.absolutePath), scope.absolutePath)
            assertTrue("absolute symlink must stay denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
            assertTrue(
                "absolute symlink must be denied by physical gate, got ${v.message}",
                v.message.contains("symlink") || v.message.contains("cross-domain"),
            )
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun dashSymlinkFixtureIsLoadBearing() {
        // Fixture 有效性：若政策放行短橫形態，OS 確實會跟隨 `-secret` 讀到域外
        // （NIO 跟隨連結讀即 OUTSIDE）。本斷言只證 fixture 有效，不經政策。
        val (scope, outside, link) = dashFixture("dash-load")
        try {
            val bytes = Files.readAllBytes(link.toPath())
            assertEquals("OUTSIDE", String(bytes, Charsets.UTF_8))
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    // ---- 正向：絕對路徑 `--` 後仍放行 ----

    @Test fun positiveAbsoluteAfterDashDash_allowedDirectAndElevated() {
        val (scope, outside) = dashFixture("dash-pos")
        try {
            val known = File(scope, "work/known.txt").absolutePath
            val argv = listOf("cat", "--", known)
            assertTrue(
                "positive must stay allowed, got ${ShellPolicy.validate(argv, scope.absolutePath)}",
                ShellPolicy.validate(argv, scope.absolutePath) is Validation.Allowed,
            )
            assertTrue(
                ShellPolicy.validateElevated(
                    argv,
                    scope.absolutePath,
                    flavor = Flavor.GITHUB,
                    bridgeGranted = true,
                ) is Validation.Allowed,
            )
            // `ls -f` 是排序旗標，不設槽：`-f` 後絕對路徑維持放行。
            val sub = File(scope, "work")
            assertTrue(
                ShellPolicy.validate(
                    listOf("ls", "-f", sub.absolutePath),
                    scope.absolutePath,
                ) is Validation.Allowed,
            )
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    // ---- 豁免 pin：不得誤殺 ----

    @Test fun exemptions_preserved() {
        val (scope, outside) = dashFixture("dash-exempt")
        try {
            // `grep -- -r`：首位短橫是 pattern（既有豁免，null scope 亦放行）。
            assertTrue(
                ShellPolicy.validate(listOf("grep", "--", "-r"), null) is Validation.Allowed,
            )
            // NO_FILE：`echo -- -secret` 只是字串。
            assertTrue(
                ShellPolicy.validate(
                    listOf("echo", "--", "-secret"),
                    scope.absolutePath,
                ) is Validation.Allowed,
            )
            // 非路徑 `--opt=value` 旗標位。
            assertTrue(
                ShellPolicy.validate(
                    listOf("ls", "--color=auto", File(scope, "work").absolutePath),
                    scope.absolutePath,
                ) is Validation.Allowed,
            )
            // `-` stdin 哨兵：`grep -f -` 與 `--file=-`。
            assertTrue(
                ShellPolicy.validate(
                    listOf("grep", "-f", "-", File(scope, "work/known.txt").absolutePath),
                    scope.absolutePath,
                ) is Validation.Allowed,
            )
            assertTrue(
                ShellPolicy.validate(
                    listOf("grep", "--file=-", File(scope, "work/known.txt").absolutePath),
                    scope.absolutePath,
                ) is Validation.Allowed,
            )
            // 旗標位 `--file=<域內絕對>` 正向 pin（與操作數位 deny-all 對照，
            // 鎖定旗標/操作數區分，防未來過緊）。
            val known = File(scope, "work/known.txt").absolutePath
            val other = File(scope, "work/other.txt").apply { writeText("hello\n") }.absolutePath
            assertTrue(
                ShellPolicy.validate(
                    listOf("grep", "--file=$known", other),
                    scope.absolutePath,
                ) is Validation.Allowed,
            )
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun guestChannelChoice_containerInternalAllowed_nullDenied() {
        // Guest 通道抉擇 pin：容器內（scope 非 null）短橫維持放行，
        // 與既有 bare 放行同設計；null 作用域 fail-closed。
        val (scope, outside) = dashFixture("dash-guest")
        try {
            val argv = listOf("cat", "--", "-secret")
            assertTrue(
                "guest+scope must stay container-internal allowed, got " +
                    ShellPolicy.validate(argv, scope.absolutePath, isGuest = true),
                ShellPolicy.validate(argv, scope.absolutePath, isGuest = true) is Validation.Allowed,
            )
            assertBlocked(
                ShellPolicy.validate(argv, null, isGuest = true),
                argv,
            )
            // `grep -f -patterns` 容器內同理放行（與 bare `allowed.txt` 同策）。
            assertTrue(
                ShellPolicy.validate(
                    listOf("grep", "-f", "-patterns", "allowed.txt"),
                    scope.absolutePath,
                    isGuest = true,
                ) is Validation.Allowed,
            )
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    // ---- 跑道層（直接通道）：零 spawn＋spawn argv 潔淨 ----

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

    @Test fun directRunner_zeroSpawnOnDashBypass() {
        val (scope, outside) = dashFixture("dash-runner")
        try {
            val vectors = listOf(
                listOf("cat", "--", "-secret"),
                listOf("grep", "-f", "-patterns", "allowed.txt"),
                listOf("grep", "--", "pattern", "-secret"),
                listOf("toybox", "cat", "--", "-secret"),
            )
            for (argv in vectors) {
                val runner = ArgvCaptureRunner()
                val shell = RestrictedShell(
                    runner = runner,
                    privateRoot = scope.absolutePath,
                    execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
                )
                val result = shell.execute(argv)
                assertTrue("expected Denied for $argv, got $result", result is ShellResult.Denied)
                assertEquals(ShellDeny.BLACKLISTED, (result as ShellResult.Denied).reason)
                assertEquals("spawn must be zero for $argv", 0, runner.calls)
            }
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun directRunner_spawnArgvCleanOnPositive() {
        // spawn argv 語義：放行形的 spawn argv 含絕對路徑、不含短橫檔名。
        val (scope, outside) = dashFixture("dash-argv")
        try {
            val runner = ArgvCaptureRunner()
            val known = File(scope, "work/known.txt").absolutePath
            val shell = RestrictedShell(
                runner = runner,
                privateRoot = scope.absolutePath,
                execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
            )
            val result = shell.execute(listOf("cat", "--", known))
            assertTrue("expected Ok, got $result", result is ShellResult.Ok)
            assertEquals(1, runner.calls)
            assertTrue("spawn argv must carry absolute path: ${runner.lastArgv}", runner.lastArgv.contains(known))
            assertTrue(
                "spawn argv must not carry dash operand: ${runner.lastArgv}",
                runner.lastArgv.none { it.startsWith("-") && it != "--" && it.contains("secret") },
            )
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun directRunner_realSpawnReadsInside_notOutside() {
        // 真實 spawn＋讀語義（DefaultProcessRunner＋自建 `cat` fixture，
        // cwd 釘死到 scope，模擬 Root/Shizuku `cd privateRoot && exec`）：
        // - 放行形真讀到 INSIDE（絕對路徑經政策＋OS 兩道）；
        // - 攻擊形零 spawn（政策先拒，delegate 計數為零）。
        // 需宿主 /bin/sh＋/bin/cat；缺失即跳過（與 timeoutKillsSleep 同約束）。
        assumeNonWindows()
        assumeTrue(File("/bin/sh").canExecute())
        assumeTrue(File("/bin/cat").canExecute())
        val (scope, outside) = dashFixture("dash-real")
        val bin = Files.createTempDirectory("dash-real-bin").toFile()
        try {
            val catFixture = File(bin, "cat")
            catFixture.writeText("#!/bin/sh\nexec /bin/cat \"$@\"\n")
            assertTrue("setExecutable failed for $catFixture", catFixture.setExecutable(true))
            var delegateCalls = 0
            val delegate = DefaultProcessRunner(dirRoot = scope.absolutePath)
            val counting: ProcessRunner = object : ProcessRunner {
                override fun run(argv: List<String>, timeoutMs: Long, env: Map<String, String>?): RawOutput {
                    delegateCalls++
                    return delegate.run(argv, timeoutMs, env)
                }
            }
            val systemOwned: (java.nio.file.Path) -> Boolean = { _ -> false }
            val shell = RestrictedShell(
                runner = counting,
                privateRoot = scope.absolutePath,
                execSearchDirs = listOf(bin.absolutePath),
                execAllowedRoots = listOf(bin.canonicalPath),
                execIsWritable = systemOwned,
            )
            val known = File(scope, "work/known.txt").absolutePath
            val ok = shell.execute(listOf("cat", "--", known))
            assertTrue("expected Ok, got $ok", ok is ShellResult.Ok)
            assertEquals("INSIDE", (ok as ShellResult.Ok).stdout)
            assertEquals(1, delegateCalls)
            // 攻擊形：零 spawn（短橫檔名永不到達 OS）。
            val denied = shell.execute(listOf("cat", "--", "-secret"))
            assertTrue("expected Denied, got $denied", denied is ShellResult.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (denied as ShellResult.Denied).reason)
            assertEquals(1, delegateCalls)
            // 第二攻擊面同門：pattern 槽耗盡後的短橫檔名。
            val denied2 = shell.execute(listOf("grep", "--", "pattern", "-secret"))
            assertTrue("expected Denied, got $denied2", denied2 is ShellResult.Denied)
            assertEquals(1, delegateCalls)
        } finally {
            bin.deleteRecursively()
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun eqOperandAfterDashDash_deniedDirectAndElevated() {
        // Stage A `=` operand fail-closed：`--` 後／`-f` 槽內的 `--opt=v` 無旗標語義，
        // 即檔名（`--color=auto` 是合法 Linux 檔名，可為預植 symlink）；NON_PATH 豁免僅限旗標位。
        val (scope, outside) = dashFixture("dash-eq")
        try {
            val vectors = listOf(
                listOf("cat", "--", "--color=auto"),
                listOf("grep", "-f", "--color=auto"),
                listOf("grep", "--", "pattern", "--color=auto"),
                listOf("cat", "--", "--file="),
            )
            for (argv in vectors) {
                assertBlocked(ShellPolicy.validate(argv, scope.absolutePath), argv)
                assertBlocked(
                    ShellPolicy.validateElevated(
                        argv,
                        scope.absolutePath,
                        flavor = Flavor.GITHUB,
                        bridgeGranted = true,
                    ),
                    argv,
                )
            }
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun eqOperandExemptions_guestAndNoFileAllowed() {
        val (scope, outside) = dashFixture("dash-eq-exempt")
        try {
            // guest 容器內（scope 非 null）維持放行，與既有 bare／短橫同設計。
            assertTrue(
                ShellPolicy.validate(
                    listOf("cat", "--", "--color=auto"),
                    scope.absolutePath,
                    isGuest = true,
                ) is Validation.Allowed,
            )
            assertTrue(
                ShellPolicy.validate(
                    listOf("grep", "-f", "--color=auto"),
                    scope.absolutePath,
                    isGuest = true,
                ) is Validation.Allowed,
            )
            // NO_FILE 無檔案語義維持放行。
            assertTrue(
                ShellPolicy.validate(
                    listOf("echo", "--", "--color=auto"),
                    scope.absolutePath,
                ) is Validation.Allowed,
            )
            // `--file=-|/abs` 操作数位亦拒（deny-all）：`--` 后无旗标语义，
            // 即字面文件名（`cat -- --file=-` 打开 `--file=-` 而非 stdin，
            // `cat -- --file=/abs` 打开 `<cwd>/--file=/abs` 而非 `/abs`）。
            assertBlocked(
                ShellPolicy.validate(
                    listOf("cat", "--", "--file=-"),
                    scope.absolutePath,
                ),
                listOf("cat", "--", "--file=-"),
            )
            assertBlocked(
                ShellPolicy.validateElevated(
                    listOf("cat", "--", "--file=-"),
                    scope.absolutePath,
                    flavor = Flavor.GITHUB,
                    bridgeGranted = true,
                ),
                listOf("cat", "--", "--file=-"),
            )
            val known = File(scope, "work/known.txt").absolutePath
            val fileAbsArgv = listOf("cat", "--", "--file=$known")
            assertBlocked(
                ShellPolicy.validate(fileAbsArgv, scope.absolutePath),
                fileAbsArgv,
            )
            assertBlocked(
                ShellPolicy.validateElevated(
                    fileAbsArgv,
                    scope.absolutePath,
                    flavor = Flavor.GITHUB,
                    bridgeGranted = true,
                ),
                fileAbsArgv,
            )
            // 零 spawn：操作数位 `--opt=v` 永不到达 OS。
            for (argv in listOf(listOf("cat", "--", "--file=-"), fileAbsArgv)) {
                val runner = ArgvCaptureRunner()
                val shell = RestrictedShell(
                    runner = runner,
                    privateRoot = scope.absolutePath,
                    execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
                )
                val result = shell.execute(argv)
                assertTrue("expected Denied for $argv, got $result", result is ShellResult.Denied)
                assertEquals(ShellDeny.BLACKLISTED, (result as ShellResult.Denied).reason)
                assertEquals("spawn must be zero for $argv", 0, runner.calls)
            }
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun directRunner_zeroSpawnOnEqOperand() {
        val (scope, outside) = dashFixture("dash-eq-runner")
        try {
            val known = File(scope, "work/known.txt").absolutePath
            val vectors = listOf(
                listOf("cat", "--", "--color=auto"),
                listOf("grep", "-f", "--color=auto"),
                listOf("grep", "--", "pattern", "--color=auto"),
                listOf("cat", "--", "--file="),
                listOf("cat", "--", "--file=-"),
                listOf("cat", "--", "--file=$known"),
            )
            for (argv in vectors) {
                val runner = ArgvCaptureRunner()
                val shell = RestrictedShell(
                    runner = runner,
                    privateRoot = scope.absolutePath,
                    execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
                )
                val result = shell.execute(argv)
                assertTrue("expected Denied for $argv, got $result", result is ShellResult.Denied)
                assertEquals(ShellDeny.BLACKLISTED, (result as ShellResult.Denied).reason)
                assertEquals("spawn must be zero for $argv", 0, runner.calls)
            }
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    // ---- Stage C（P1-1 attached file-options）：單橫 `=` 附著＋`--exclude-from` ----

    /**
     * Stage C fixture：scope 內 benign patterns/input（絕對尾參合法，
     * load-bearing：拒絕只能來自危險槽，不能來自尾參），outside 內
     * shadow `<outside><scope>/patterns` 含可觀測行，scope 內 `=` symlink →
     * outside。未修補政策驗後綴 benign 而 OS 打開 shadow（use≠validated），
     * 見 [stageC_shortEq_realSemanticsMismatchAndPolicyDenies]。
     */
    private data class StageCFixture(
        val scope: File,
        val outside: File,
        val patternsAbs: String,
        val inputAbs: String,
    )

    private fun stageCFixture(prefix: String): StageCFixture {
        assumeNonWindows()
        val scope = tempDir("$prefix-scope").canonicalFile
        val outside = tempDir("$prefix-outside").canonicalFile
        File(scope, "patterns").writeText("zzz-no-match\n")
        File(scope, "input").writeText("hello world\n")
        val shadowDir = File(outside.absolutePath + scope.absolutePath).apply { mkdirs() }
        File(shadowDir, "patterns").writeText("hello\n")
        Files.createSymbolicLink(File(scope, "=").toPath(), outside.toPath())
        return StageCFixture(
            scope,
            outside,
            File(scope, "patterns").absolutePath,
            File(scope, "input").absolutePath,
        )
    }

    @Test fun stageC_shortEqAttached_deniedDirectAndElevated() {
        // 單橫 `-f=/abs`：短選項附著取整 rest `=/abs` 當相對檔名，
        // 後綴再乾淨（域內 benign）也不得信任。
        val f = stageCFixture("stageC-eq")
        try {
            val vectors = listOf(
                listOf("grep", "-f=" + f.patternsAbs, f.inputAbs),
                listOf("grep", "-f=-secret", f.inputAbs),
                listOf("grep", "-f=bare/relative", f.inputAbs),
                listOf("toybox", "grep", "-f=" + f.patternsAbs, f.inputAbs),
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

    @Test fun stageC_excludeFrom_deniedDirectAndElevated() {
        // `--exclude-from` 是 grep 文件鍵（GNU 支援分離形＋附著形）：
        // 分離形下一位短橫即檔名，附著形值恆為檔名。
        val f = stageCFixture("stageC-excl")
        try {
            val vectors = listOf(
                listOf("grep", "hello", "--exclude-from", "-secret", f.inputAbs),
                listOf("grep", "--exclude-from", "-secret", f.inputAbs),
                listOf("grep", "--exclude-from=-secret", f.inputAbs),
                listOf("grep", "--exclude-from=bare/relative", f.inputAbs),
                listOf("toybox", "grep", "hello", "--exclude-from", "-secret", f.inputAbs),
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

    @Test fun stageC_allowForms_preservedDirectAndElevated() {
        // 放行 pin：分離 `-f /abs`（flag 位真同一檔案）＋附著長
        // `--file=/abs`／`--exclude-from=/abs`（use==validated）维持放行；
        // `=` symlink 不污染放行形（實體門走候選鏈，不經 `=`）。
        val f = stageCFixture("stageC-allow")
        try {
            val vectors = listOf(
                listOf("grep", "-f", f.patternsAbs, f.inputAbs),
                listOf("grep", "--file=" + f.patternsAbs, f.inputAbs),
                listOf("grep", "hello", "--exclude-from", f.patternsAbs, f.inputAbs),
                listOf("grep", "hello", "--exclude-from=" + f.patternsAbs, f.inputAbs),
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

    @Test fun stageC_directRunner_zeroSpawn() {
        val f = stageCFixture("stageC-runner")
        try {
            val vectors = listOf(
                listOf("grep", "-f=" + f.patternsAbs, f.inputAbs),
                listOf("grep", "-f=-secret", f.inputAbs),
                listOf("grep", "hello", "--exclude-from", "-secret", f.inputAbs),
                listOf("grep", "--exclude-from=-secret", f.inputAbs),
                listOf("grep", "--exclude-from=bare/relative", f.inputAbs),
                listOf("toybox", "grep", "-f=" + f.patternsAbs, f.inputAbs),
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

    @Test fun stageC_shortEq_realSemanticsMismatchAndPolicyDenies() {
        // 驗用分叉實證（load-bearing 核心）：同一 argv 繞過政策直 spawn
        // （cwd=scope）讀到 shadow（`=` symlink 跟隨），而政策必須拒絕。
        // 另附放行形真跑讀到域內（real-file positive）。
        assumeNonWindows()
        val grepBin = listOf("/bin/grep", "/usr/bin/grep").firstOrNull { File(it).canExecute() }
        assumeTrue(grepBin != null)
        val f = stageCFixture("stageC-real")
        try {
            val argv = listOf("grep", "-f=" + f.patternsAbs, f.inputAbs)
            // 1. OS 語義（無政策，直 spawn）：`-f=` 的 optarg 是相對 `=/…`，
            //    經 `=` 指向 shadow（含 `hello`），故命中 input 的 `hello world`
            //    （benign 後綴是 `zzz-no-match`，命中即證打開的是 shadow）。
            val raw = DefaultProcessRunner(dirRoot = f.scope.absolutePath)
                .run(listOf(grepBin!!, "-f=" + f.patternsAbs, f.inputAbs), 10_000L, null)
            val out = String(raw.stdout, Charsets.UTF_8)
            assertEquals("OS must open shadow via `=` symlink (exit 0), got: $out", 0, raw.exitCode)
            assertTrue("OS opened shadow, not benign suffix, got: $out", out.contains("hello world"))
            // 2. 政策拒絕（直接＋提權）且零 spawn（實際打開目標永不到達 OS，
            //    上式 shadow 命中反證修前信任後綴即穿出）。
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
            assertTrue("expected Denied for $argv, got $result", result is ShellResult.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (result as ShellResult.Denied).reason)
            assertEquals("spawn must be zero for $argv", 0, delegateCalls)
            // 3. 放行形真跑（real-file positive）：`--file=/abs` 經政策放行，
            //    真 spawn 讀到域內 input（benign patterns 無命中，exit 1，無輸出）。
            val okArgv = listOf("grep", "--file=" + f.patternsAbs, f.inputAbs)
            assertTrue(
                ShellPolicy.validate(okArgv, f.scope.absolutePath) is Validation.Allowed,
            )
            val grepDir = File(grepBin).parentFile.canonicalPath
            val realShell = RestrictedShell(
                runner = DefaultProcessRunner(dirRoot = f.scope.absolutePath),
                privateRoot = f.scope.absolutePath,
                execSearchDirs = listOf(grepDir),
                execAllowedRoots = listOf(grepDir),
                execIsWritable = { false },
            )
            val ok = realShell.execute(okArgv)
            assertTrue("expected Ok, got $ok", ok is ShellResult.Ok)
            // benign patterns 無命中 → exit 1、空輸出：證真 spawn 讀的是域內
            // benign（若誤讀 shadow 即命中 `hello world`、exit 0）。
            assertEquals(1, (ok as ShellResult.Ok).exitCode)
            assertEquals("", ok.stdout)
        } finally {
            f.scope.deleteRecursively()
            f.outside.deleteRecursively()
        }
    }
}
