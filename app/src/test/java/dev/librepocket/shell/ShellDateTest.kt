package dev.librepocket.shell

import dev.librepocket.tool.Flavor
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Stage H（P1-1 clock-set＋P1-2 file-read）迴歸：
 * `date` 不得在 NO_FILE（已移除），走只讀 allowlist＋檔名 vet。
 *
 * 背景（Help 證據，不執行 `date -s`；GNU/uutils `date --help` 實測＋Toybox HELP_date）：
 * - `-s/--set STRING` SET the system clock（Root 提權下真設時鐘，絕不執行）；
 * - Toybox SET positional（`date 010101002030` 形）同樣設時鐘；
 * - `-r/--reference FILE` 顯示 FILE mtime（open），`-f/--file DATEFILE` 逐行讀
 *   （open）。GNU 實測 `-r` 讀域外 mtime、`--file` 讀域外。
 * 故 P1-1（修 `-s` 不關 P1-2）與 P1-2（`-r/-f` 開檔）須同修。
 *
 * 本檔零 `date -s` 真執行：mutation 向量一律政策＋計數跑道零 spawn 斷言；
 * 真執行僅限只讀 `-r`（mtime 讀，無副作用，見 [dateFileProof_rawReadsOutside_policyDenies]）。
 * 系統時鐘未被修改（無任何 `-s/--set`/SET positional 真執行）。
 */
class ShellDateTest {

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().canonicalFile

    private fun assumeNonWindows() {
        val osName: String = System.getProperty("os.name") ?: ""
        assumeTrue(!osName.startsWith("Windows"))
    }

    private fun assumeDateAvailable() {
        assumeNonWindows()
        assumeTrue(File("/bin/date").canExecute())
        assumeTrue(File("/bin/sh").canExecute())
    }

    /**
     * 攻擊 fixture：scope 內 `-secret` symlink → outside/secret.txt；
     * scope/work/known.txt 為域內對照。回 Triple(scope, outside, link)。
     */
    private fun dateFixture(prefix: String): Triple<File, File, File> {
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

    private fun assertSetDenied(v: Validation, argv: List<String>) {
        assertTrue("expected Denied for $argv, got $v", v is Validation.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        assertTrue(
            "SET message must stay in date-set family, got ${v.message}",
            v.message.contains("date set denied"),
        )
    }

    private fun assertFileDashDenied(v: Validation, argv: List<String>) {
        assertTrue("expected Denied for $argv, got $v", v is Validation.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        assertTrue(
            "file-dash message must stay in relative-path family, got ${v.message}",
            v.message.contains("relative path denied") && v.message.contains("date"),
        )
    }

    private fun assertFlagDenied(v: Validation, argv: List<String>) {
        assertTrue("expected Denied for $argv, got $v", v is Validation.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        assertTrue(
            "flag message must stay in date-flag family, got ${v.message}",
            v.message.contains("date"),
        )
    }

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

    // ---- P1-1：clock-set 向量（政策層，直接＋提權同門） ----

    @Test fun dateSetVectors_deniedDirectAndElevated() {
        val (scope, outside) = dateFixture("date-set")
        try {
            val vectors = listOf(
                listOf("date", "-s", "2030-01-01"),
                listOf("date", "-s2030-01-01"),
                listOf("date", "--set", "2030-01-01"),
                listOf("date", "--set=2030-01-01 00:00:00"),
                listOf("date", "--s", "2030-01-01"),
                listOf("date", "--se=2030-01-01"),
                listOf("date", "010101002030"),
                listOf("date", "2030-01-01"),
                listOf("date", "/abs"),
                listOf("date", "sub/file"),
                listOf("date", "-us", "2030-01-01"),
                listOf("date", "--", "-s"),
                listOf("date", "-u", "010101002030"),
            )
            for (argv in vectors) {
                assertSetDenied(ShellPolicy.validate(argv, scope.absolutePath), argv)
                assertSetDenied(
                    ShellPolicy.validateElevated(
                        argv,
                        scope.absolutePath,
                        flavor = Flavor.GITHUB,
                        bridgeGranted = true,
                    ),
                    argv,
                )
            }
            // multicall 有效形同門（toybox applet）：直接＋提權皆 BLACKLISTED。
            val multiVectors = listOf(
                listOf("toybox", "date", "-s", "2030-01-01"),
                listOf("toybox", "date", "--set=2030-01-01"),
                listOf("toybox", "date", "010101002030"),
                listOf("busybox", "date", "-s", "2030-01-01"),
            )
            for (argv in multiVectors) {
                val direct = ShellPolicy.validate(argv, scope.absolutePath)
                assertTrue("expected multicall Denied for $argv, got $direct", direct is Validation.Denied)
                assertEquals(ShellDeny.BLACKLISTED, (direct as Validation.Denied).reason)
                val elevated = ShellPolicy.validateElevated(
                    argv,
                    scope.absolutePath,
                    flavor = Flavor.GITHUB,
                    bridgeGranted = true,
                )
                assertTrue("expected multicall elevated Denied for $argv, got $elevated", elevated is Validation.Denied)
                assertEquals(ShellDeny.BLACKLISTED, (elevated as Validation.Denied).reason)
            }
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun dateSet_zeroSpawnDirectAndMulticall() {
        val (scope, outside) = dateFixture("date-set-runner")
        try {
            val vectors = listOf(
                listOf("date", "-s", "2030-01-01"),
                listOf("date", "--set=2030-01-01"),
                listOf("date", "010101002030"),
                listOf("toybox", "date", "-s", "2030-01-01"),
                listOf("toybox", "date", "010101002030"),
                listOf("busybox", "date", "-s", "2030-01-01"),
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

    // ---- P1-2：file-read 向量（短橫／相對／bare，trailing-legal） ----

    @Test fun dateFileDashVectors_deniedDirectAndElevated() {
        val (scope, outside) = dateFixture("date-file")
        try {
            val vectors = listOf(
                listOf("date", "-r", "-secret"),
                listOf("date", "--reference", "-secret"),
                listOf("date", "--reference=-secret"),
                listOf("date", "--ref=-secret"),
                listOf("date", "--refe", "-secret"),
                listOf("date", "-f", "-secret"),
                listOf("date", "--file", "-secret"),
                listOf("date", "--file=-secret"),
                listOf("date", "--fi=-secret"),
                listOf("date", "-r", "-"),
                listOf("date", "--reference=-"),
                listOf("date", "-u", "-r", "-secret"),
                listOf("date", "-r", "relative.txt"),
                listOf("date", "--file=relative.txt"),
                listOf("date", "-r", "sub/file"),
            )
            for (argv in vectors) {
                assertFileDashDenied(ShellPolicy.validate(argv, scope.absolutePath), argv)
                assertFileDashDenied(
                    ShellPolicy.validateElevated(
                        argv,
                        scope.absolutePath,
                        flavor = Flavor.GITHUB,
                        bridgeGranted = true,
                    ),
                    argv,
                )
            }
            val multiVectors = listOf(
                listOf("toybox", "date", "-r", "-secret"),
                listOf("toybox", "date", "--reference=-secret"),
                listOf("toybox", "date", "-f", "-secret"),
            )
            for (argv in multiVectors) {
                val direct = ShellPolicy.validate(argv, scope.absolutePath)
                assertTrue("expected multicall Denied for $argv, got $direct", direct is Validation.Denied)
                assertEquals(ShellDeny.BLACKLISTED, (direct as Validation.Denied).reason)
                val elevated = ShellPolicy.validateElevated(
                    argv,
                    scope.absolutePath,
                    flavor = Flavor.GITHUB,
                    bridgeGranted = true,
                )
                assertTrue("expected multicall elevated Denied for $argv, got $elevated", elevated is Validation.Denied)
                assertEquals(ShellDeny.BLACKLISTED, (elevated as Validation.Denied).reason)
            }
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun dateFileDash_zeroSpawnDirectAndMulticall() {
        val (scope, outside) = dateFixture("date-file-runner")
        try {
            val vectors = listOf(
                listOf("date", "-r", "-secret"),
                listOf("date", "--reference=-secret"),
                listOf("date", "-f", "-secret"),
                listOf("date", "--file=-secret"),
                listOf("toybox", "date", "-r", "-secret"),
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

    @Test fun dateFileCrossDomain_deniedDirectAndElevated() {
        val (scope, outside) = dateFixture("date-cross")
        try {
            val outsideFile = File(outside, "secret.txt").absolutePath
            for (argv in listOf(
                listOf("date", "-r", outsideFile),
                listOf("date", "--reference=$outsideFile"),
                listOf("date", "-f", outsideFile),
            )) {
                val direct = ShellPolicy.validate(argv, scope.absolutePath)
                assertTrue("expected cross-domain Denied for $argv, got $direct", direct is Validation.Denied)
                assertEquals(ShellDeny.BLACKLISTED, (direct as Validation.Denied).reason)
                val elevated = ShellPolicy.validateElevated(
                    argv,
                    scope.absolutePath,
                    flavor = Flavor.GITHUB,
                    bridgeGranted = false,
                )
                assertTrue("expected elevated Denied for $argv, got $elevated", elevated is Validation.Denied)
                assertEquals(ShellDeny.BLACKLISTED, (elevated as Validation.Denied).reason)
            }
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    // ---- 正向：只讀 pin（不得誤殺） ----

    @Test fun datePositives_allowedDirectAndElevated() {
        val (scope, outside) = dateFixture("date-pos")
        try {
            val known = File(scope, "work/known.txt").absolutePath
            // 無檔語義（null scope 亦放行，無隱式 cwd）。
            for (argv in listOf(
                listOf("date"),
                listOf("date", "-u"),
                listOf("date", "+%Y-%m-%d"),
                listOf("date", "-u", "+%Y-%m-%d"),
                listOf("date", "+%Y/%m/%d"),
                listOf("date", "-R"),
                listOf("date", "-I"),
                listOf("date", "--iso-8601"),
                listOf("date", "--iso-8601=date"),
                listOf("date", "--rfc-email"),
                listOf("date", "--rfc-3339=seconds"),
                listOf("date", "--resolution"),
                listOf("date", "--debug"),
                listOf("date", "-d", "yesterday"),
                listOf("date", "--date=yesterday"),
                listOf("date", "--date", "2024-01-01"),
            )) {
                assertTrue(
                    "expected direct Allowed for $argv, got ${ShellPolicy.validate(argv, scope.absolutePath)}",
                    ShellPolicy.validate(argv, scope.absolutePath) is Validation.Allowed,
                )
                assertTrue(
                    "expected elevated Allowed for $argv, got ${
                        ShellPolicy.validateElevated(
                            argv, scope.absolutePath, flavor = Flavor.GITHUB, bridgeGranted = true,
                        )
                    }",
                    ShellPolicy.validateElevated(
                        argv, scope.absolutePath, flavor = Flavor.GITHUB, bridgeGranted = true,
                    ) is Validation.Allowed,
                )
            }
            // 開檔正向（域內絕對，FileScope＋實體門放行）。
            for (argv in listOf(
                listOf("date", "-r", known),
                listOf("date", "--reference", known),
                listOf("date", "--reference=$known"),
                listOf("date", "--ref=$known"),
                listOf("date", "-f", known),
                listOf("date", "--file=$known"),
            )) {
                assertTrue(
                    "expected direct Allowed for $argv, got ${ShellPolicy.validate(argv, scope.absolutePath)}",
                    ShellPolicy.validate(argv, scope.absolutePath) is Validation.Allowed,
                )
                assertTrue(
                    "expected elevated Allowed for $argv, got ${
                        ShellPolicy.validateElevated(
                            argv, scope.absolutePath, flavor = Flavor.GITHUB, bridgeGranted = true,
                        )
                    }",
                    ShellPolicy.validateElevated(
                        argv, scope.absolutePath, flavor = Flavor.GITHUB, bridgeGranted = true,
                    ) is Validation.Allowed,
                )
            }
            // null scope 無檔形仍放行；開檔形 fail-closed。
            assertTrue(ShellPolicy.validate(listOf("date"), null) is Validation.Allowed)
            assertTrue(ShellPolicy.validate(listOf("date", "-u"), null) is Validation.Allowed)
            assertTrue(ShellPolicy.validate(listOf("date", "+%Y-%m-%d"), null) is Validation.Allowed)
            assertTrue(
                ShellPolicy.validate(listOf("date", "-r", known), null) is Validation.Denied,
            )
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun dateUnknownFlag_denied() {
        val (scope, outside) = dateFixture("date-unknown")
        try {
            for (argv in listOf(
                listOf("date", "--color=auto"),
                listOf("date", "-uR"),
                listOf("date", "-dfoo"),
                listOf("date", "-r/abs"),
                listOf("date", "--rfc-3339=bogus"),
                listOf("date", "--iso-8601=bogus"),
                listOf("date", "--universal=foo"),
            )) {
                assertFlagDenied(ShellPolicy.validate(argv, scope.absolutePath), argv)
                assertFlagDenied(
                    ShellPolicy.validateElevated(
                        argv, scope.absolutePath, flavor = Flavor.GITHUB, bridgeGranted = true,
                    ),
                    argv,
                )
            }
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    // ---- P1-2 實證：raw `-r -secret` 讀到域外（只讀，無時鐘修改）＋政策零 spawn ----

    @Test fun dateFileProof_rawReadsOutside_policyDenies() {
        assumeDateAvailable()
        // Host `/bin/date` 為 uutils（clap 對 `-r -secret` 分離形誤報缺值，不開檔），
        // 故 raw 證明用真 GNU `/bin/gnudate`（若缺則 busybox），二者皆接受分離短橫
        // 並跟隨 symlink 讀到域外（busybox/GNU 實測 rc 0，見下）。uutils 附著形
        // `--reference=-secret` 同樣開檔（已驗），政策對兩形一律拒。
        val rawDateBin: String = when {
            File("/bin/gnudate").canExecute() -> "/bin/gnudate"
            File("/usr/bin/gnudate").canExecute() -> "/usr/bin/gnudate"
            else -> "busybox"
        }
        val rawDateArgvPrefix: List<String> = if (rawDateBin == "busybox") {
            assumeTrue(File("/usr/bin/busybox").canExecute() || File("/bin/busybox").canExecute())
            listOf("busybox", "date")
        } else {
            listOf(rawDateBin)
        }
        val (scope, outside, _) = dateFixture("date-proof")
        try {
            val outsideFile = File(outside, "secret.txt")
            val knownFile = File(scope, "work/known.txt")
            outsideFile.writeText("OUTSIDE")
            knownFile.writeText("INSIDE")
            assertTrue(outsideFile.setLastModified(1577836800000L))
            assertTrue(knownFile.setLastModified(1622505600000L))
            val rawRunner = DefaultProcessRunner(dirRoot = scope.absolutePath)
            val rawLink = rawRunner.run(rawDateArgvPrefix + listOf("-r", "-secret"), 5_000L, null)
            assertEquals("raw date -r -secret must exit 0, stderr=${String(rawLink.stderr)}", 0, rawLink.exitCode)
            val rawOutside = rawRunner.run(rawDateArgvPrefix + listOf("-r", outsideFile.absolutePath), 5_000L, null)
            assertEquals(0, rawOutside.exitCode)
            val rawKnown = rawRunner.run(rawDateArgvPrefix + listOf("-r", knownFile.absolutePath), 5_000L, null)
            assertEquals(0, rawKnown.exitCode)
            val linkOut = String(rawLink.stdout, Charsets.UTF_8)
            val outsideOut = String(rawOutside.stdout, Charsets.UTF_8)
            val knownOut = String(rawKnown.stdout, Charsets.UTF_8)
            // `-secret`（scope 內 symlink）讀到域外 mtime：與域外直讀一致，與域內已知不同。
            assertEquals(
                "raw date -r -secret must follow symlink to outside (proves open), link=[$linkOut] outside=[$outsideOut]",
                outsideOut, linkOut,
            )
            assertTrue(
                "raw outside vs inside must differ (fixture load-bearing), outside=[$outsideOut] known=[$knownOut]",
                outsideOut != knownOut,
            )
            // 政策層：同一相對短橫必須拒＋零 spawn（直接＋提權）。
            val argv = listOf("date", "-r", "-secret")
            assertFileDashDenied(ShellPolicy.validate(argv, scope.absolutePath), argv)
            assertFileDashDenied(
                ShellPolicy.validateElevated(
                    argv, scope.absolutePath, flavor = Flavor.GITHUB, bridgeGranted = true,
                ),
                argv,
            )
            val runner = ArgvCaptureRunner()
            val shell = RestrictedShell(
                runner = runner,
                privateRoot = scope.absolutePath,
                execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
            )
            val result = shell.execute(argv)
            assertTrue("expected Denied, got $result", result is ShellResult.Denied)
            assertEquals(0, runner.calls)
            // 正向真執行（只讀）：域內 `-r` 經政策放行，真讀到域內 mtime。
            val bin = Files.createTempDirectory("date-proof-bin").toFile()
            try {
                val dateFixtureBin = File(bin, "date")
                dateFixtureBin.writeText("#!/bin/sh\nexec /bin/date \"$@\"\n")
                assertTrue("setExecutable failed for $dateFixtureBin", dateFixtureBin.setExecutable(true))
                val systemOwned: (java.nio.file.Path) -> Boolean = { _ -> false }
                val realShell = RestrictedShell(
                    runner = DefaultProcessRunner(dirRoot = scope.absolutePath),
                    privateRoot = scope.absolutePath,
                    execSearchDirs = listOf(bin.absolutePath),
                    execAllowedRoots = listOf(bin.canonicalPath),
                    execIsWritable = systemOwned,
                )
                val ok = realShell.execute(listOf("date", "-r", knownFile.absolutePath))
                assertTrue("expected Ok for inside -r, got $ok", ok is ShellResult.Ok)
                // Same-binary comparison: both sides go through the /bin/date shim
                // (uutils), so zh_TW (or any) locale renders identically. Comparing
                // GNU gnudate output (knownOut, above) against uutils output diverges.
                val uutilsRaw = DefaultProcessRunner(dirRoot = scope.absolutePath).run(
                    listOf("/bin/date", "-r", knownFile.absolutePath),
                    5_000L,
                    null,
                )
                assertEquals(0, uutilsRaw.exitCode)
                assertEquals(String(uutilsRaw.stdout, Charsets.UTF_8), (ok as ShellResult.Ok).stdout)
            } finally {
                bin.deleteRecursively()
            }
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }
}
