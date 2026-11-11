package dev.librepocket.shell

import dev.librepocket.tool.Flavor
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Stage J（P1-4 find `-fprint0` 寫動作＋P2 pathless `df`）迴歸。
 *
 * P1-4（find `-fprint0` 寫檔）：
 * - PoC（GNU 實測，見 [findFprint0RealOverwritesSentinel]）：
 *   `find /scope -maxdepth 0 -fprint0 /scope/sentinel.txt`
 *   以 NUL 結尾路徑覆寫 sentinel（location-valid ≠ read-only-safe）。
 * - 政策：[ShellPolicy.isFindDeniedPredicate] 精確集＋`-fprint0` 族前綴
 *   （附著／無效延伸 fail-closed 一併拒；find 謂詞無縮寫語義），直接／提權／
 *   multicall 有效形四門同拒，零 spawn。
 * - Toybox variance 注記：toybox `find` 語義 per-ROM 未驗（`-fprint0`
 *   可用性未知），拒絕對其 fail-closed 無害（無效謂詞本就 exit 1/2，
 *   拒不破壞合法用）。
 * - Allowlist 評估（本切片決策：維持 deny-list，不做 read-only allowlist）：
 *   見 [ShellPolicy.FIND_DENIED_PREDICATES] KDoc——三實現謂詞窮舉成本高、
 *   漏一寫／執行謂詞即逃逸；file-taker（`-newer`／`-samefile`）參考檔操作數
 *   已由 absoluteCandidates＋FileScope＋實體門同門 vet（域外即拒），殘餘僅為
 *   未來未知寫／執行謂詞（Refs #9 follow-up）。
 *
 * P2（pathless `df` 全域掛載）：
 * - `df` 無操作數／`-a` 列全部掛載（不跟隨 pinned cwd；FileScope 契約＝
 *   域內元數據可見即洩露），故 scoped READ 要求 ≥1 裸絕對路徑操作數，
 *   pathless（含 `-a/--all` 無路徑）一律 `Denied(BLACKLISTED)`（新訊息族
 *   `df requires scoped path`）；`-a/--all` 即使帶路徑亦拒
 *   （fail-closed：帶路徑輸出仍含 dummy 檔案系統，全域可見；決策：完全禁，
 *   不採「帶路徑放行」）。
 * - 既有測試檢查（grep `"df"` 全倉）：僅 ShellWhitelistTest.implicitCwd…
 *   覆 null 側 pathless（`df`／`df -h`，verdict 維持 Denied，訊息族改為 df 族，
 *   該測試只斷 verdict 不受影響）——無 scoped-allow 需翻轉。
 *
 * Root／Shizuku 提權 runner 皆以 [ShellPolicy.validateElevated] 為先行門
 * （拒即零 spawn），故提權側 `validateElevated` pin 即覆兩 runner 之門；
 * 直接側另以 [RestrictedShell] 斷零 spawn。
 */
class ShellStageJTest {

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().canonicalFile

    private fun assumeNonWindows() {
        val osName: String = System.getProperty("os.name") ?: ""
        assumeTrue(!osName.startsWith("Windows"))
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

    private fun assertFindPredicateDenied(v: Validation, argv: List<String>) {
        assertTrue("expected Denied for $argv, got $v", v is Validation.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        assertTrue(
            "message must stay in find-predicate family, got ${v.message}",
            v.message.contains("find predicate denied"),
        )
    }

    private fun assertDfRequiresPath(v: Validation, argv: List<String>) {
        assertTrue("expected Denied for $argv, got $v", v is Validation.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        assertTrue(
            "message must stay in df-scoped-path family, got ${v.message}",
            v.message.contains("df requires scoped path"),
        )
    }

    private fun assertDfAllDenied(v: Validation, argv: List<String>) {
        assertTrue("expected Denied for $argv, got $v", v is Validation.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        assertTrue(
            "message must stay in df-all family, got ${v.message}",
            v.message.contains("df all denied"),
        )
    }

    // ---- P1-4：`-fprint0` 政策層（直接＋提權同門） ----

    @Test fun findFprint0_deniedDirectAndElevated() {
        val scope = tempDir("stageJ-fprint0")
        try {
            val sentinel = File(scope, "sentinel.txt").apply { writeText("ORIGINAL") }
            val vectors = listOf(
                // 題面 PoC 形：scoped-valid 操作數＋寫謂詞（location-valid ≠ read-only-safe）。
                listOf("find", scope.absolutePath, "-maxdepth", "0", "-fprint0", sentinel.absolutePath),
                listOf("find", scope.absolutePath, "-fprint0", sentinel.absolutePath),
                // 附著／無效延伸 fail-closed（非合法 find 謂詞，拒無害）。
                listOf("find", scope.absolutePath, "-fprint0junk", sentinel.absolutePath),
            )
            for (argv in vectors) {
                assertFindPredicateDenied(ShellPolicy.validate(argv, scope.absolutePath), argv)
                assertFindPredicateDenied(
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
        }
    }

    @Test fun findFprint0_multicallDeniedDirectAndElevated() {
        val scope = tempDir("stageJ-fprint0-multi")
        try {
            val sentinel = File(scope, "sentinel.txt").apply { writeText("ORIGINAL") }
            val vectors = listOf(
                listOf("toybox", "find", scope.absolutePath, "-fprint0", sentinel.absolutePath),
                listOf(
                    "toybox", "find", scope.absolutePath, "-maxdepth", "0",
                    "-fprint0", sentinel.absolutePath,
                ),
                listOf("busybox", "find", scope.absolutePath, "-fprint0", sentinel.absolutePath),
            )
            for (argv in vectors) {
                assertFindPredicateDenied(ShellPolicy.validate(argv, scope.absolutePath), argv)
                assertFindPredicateDenied(
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
        }
    }

    @Test fun findFprint0_directRunnerZeroSpawn() {
        val scope = tempDir("stageJ-fprint0-runner")
        try {
            val sentinel = File(scope, "sentinel.txt").apply { writeText("ORIGINAL") }
            val vectors = listOf(
                listOf("find", scope.absolutePath, "-maxdepth", "0", "-fprint0", sentinel.absolutePath),
                listOf("toybox", "find", scope.absolutePath, "-fprint0", sentinel.absolutePath),
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
            // 政策拒絕故 sentinel 未被觸及（仍為 ORIGINAL）。
            assertEquals("ORIGINAL", sentinel.readText())
        } finally {
            scope.deleteRecursively()
        }
    }

    // ---- P1-4：host GNU 真實寫語義 load-bearing（繞過政策直跑即覆寫） ----

    @Test fun findFprint0RealOverwritesSentinel() {
        assumeNonWindows()
        val findBin = firstExecutable("/bin/find", "/usr/bin/find")
        assumeTrue(findBin != null)
        val scope = tempDir("stageJ-fprint0-real")
        try {
            // 唯寫觀察（temp 沙箱，不觸倉庫）：sentinel 預植 ORIGINAL。
            val sentinel = File(scope, "sentinel.txt").apply { writeText("ORIGINAL") }
            val raw = DefaultProcessRunner(dirRoot = scope.absolutePath)
                .run(
                    listOf(findBin!!, scope.absolutePath, "-maxdepth", "0", "-fprint0", sentinel.absolutePath),
                    10_000L,
                    null,
                )
            assertEquals("GNU find -fprint0 must exit 0, got: ${raw.exitCode}", 0, raw.exitCode)
            val bytes = sentinel.readBytes()
            assertTrue(
                "sentinel must be overwritten (not ORIGINAL)",
                !bytes.contentEquals("ORIGINAL".toByteArray(Charsets.UTF_8)),
            )
            assertTrue("sentinel must be NUL-terminated path", bytes.isNotEmpty() && bytes.last() == 0.toByte())
            assertTrue(
                "sentinel must start with scope path",
                String(bytes, Charsets.UTF_8).startsWith(scope.absolutePath),
            )
            // 政策側同形拒絕（直接＋提權）且零 spawn（trailing-legal PoC 形）。
            val argv = listOf("find", scope.absolutePath, "-maxdepth", "0", "-fprint0", sentinel.absolutePath)
            assertFindPredicateDenied(ShellPolicy.validate(argv, scope.absolutePath), argv)
            assertFindPredicateDenied(
                ShellPolicy.validateElevated(
                    argv,
                    scope.absolutePath,
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
                privateRoot = scope.absolutePath,
                execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
            )
            val result = shell.execute(argv)
            assertTrue("expected Denied, got $result", result is ShellResult.Denied)
            assertEquals(0, delegateCalls)
        } finally {
            scope.deleteRecursively()
        }
    }

    // ---- P2：pathless `df` 政策層（直接＋提權同門） ----

    @Test fun dfPathless_deniedDirectAndElevated() {
        val scope = tempDir("stageJ-df-pathless")
        try {
            // scoped 下 pathless 仍拒（舊 implicit-cwd 門僅 null 側；Stage J 新門有無皆拒）。
            val vectors = listOf(
                listOf("df"),
                listOf("df", "-h"),
                listOf("df", "-a"),
                listOf("df", "--all"),
            )
            for (argv in vectors) {
                val direct = ShellPolicy.validate(argv, scope.absolutePath)
                assertTrue("expected Denied for $argv, got $direct", direct is Validation.Denied)
                assertEquals(ShellDeny.BLACKLISTED, (direct as Validation.Denied).reason)
                val elevated = ShellPolicy.validateElevated(
                    argv,
                    scope.absolutePath,
                    flavor = Flavor.GITHUB,
                    bridgeGranted = true,
                )
                assertTrue("expected Denied for $argv, got $elevated", elevated is Validation.Denied)
                assertEquals(ShellDeny.BLACKLISTED, (elevated as Validation.Denied).reason)
            }
            // pathless 訊息族：`-a/--all` 走 df-all 族，其餘走 df-scoped-path 族。
            assertDfAllDenied(
                ShellPolicy.validate(listOf("df", "-a"), scope.absolutePath),
                listOf("df", "-a"),
            )
            assertDfAllDenied(
                ShellPolicy.validate(listOf("df", "--all"), scope.absolutePath),
                listOf("df", "--all"),
            )
            assertDfRequiresPath(
                ShellPolicy.validate(listOf("df"), scope.absolutePath),
                listOf("df"),
            )
            assertDfRequiresPath(
                ShellPolicy.validate(listOf("df", "-h"), scope.absolutePath),
                listOf("df", "-h"),
            )
        } finally {
            scope.deleteRecursively()
        }
    }

    @Test fun dfAllWithPath_stillDeniedDirectAndElevated() {
        // 決策：`-a/--all` 即使帶絕對路徑亦拒（fail-closed；帶路徑輸出仍含 dummy
        // 檔案系統，全域可見；不採「帶路徑放行」）。
        val scope = tempDir("stageJ-df-all")
        try {
            val vectors = listOf(
                listOf("df", "-a", scope.absolutePath),
                listOf("df", "--all", scope.absolutePath),
                listOf("df", "-ah", scope.absolutePath),
                listOf("df", "--a", scope.absolutePath),
                listOf("df", "--all=" + scope.absolutePath, scope.absolutePath),
            )
            for (argv in vectors) {
                assertDfAllDenied(ShellPolicy.validate(argv, scope.absolutePath), argv)
                assertDfAllDenied(
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
        }
    }

    @Test fun dfScopedPath_allowedDirectAndElevated() {
        val scope = tempDir("stageJ-df-scoped")
        try {
            val vectors = listOf(
                listOf("df", scope.absolutePath),
                listOf("df", "-h", scope.absolutePath),
            )
            for (argv in vectors) {
                val direct = ShellPolicy.validate(argv, scope.absolutePath)
                assertTrue("expected Allowed for $argv, got $direct", direct is Validation.Allowed)
                val elevated = ShellPolicy.validateElevated(
                    argv,
                    scope.absolutePath,
                    flavor = Flavor.GITHUB,
                    bridgeGranted = true,
                )
                assertTrue("expected Allowed for $argv, got $elevated", elevated is Validation.Allowed)
            }
        } finally {
            scope.deleteRecursively()
        }
    }

    @Test fun dfMulticall_pathlessAndAllDenied_scopedAllowed() {
        val scope = tempDir("stageJ-df-multi")
        try {
            val denied = listOf(
                listOf("toybox", "df"),
                listOf("toybox", "df", "-a"),
                listOf("toybox", "df", "--all"),
                listOf("toybox", "df", "-a", scope.absolutePath),
                listOf("toybox", "df", "--all", scope.absolutePath),
            )
            for (argv in denied) {
                val direct = ShellPolicy.validate(argv, scope.absolutePath)
                assertTrue("expected Denied for $argv, got $direct", direct is Validation.Denied)
                assertEquals(ShellDeny.BLACKLISTED, (direct as Validation.Denied).reason)
                val elevated = ShellPolicy.validateElevated(
                    argv,
                    scope.absolutePath,
                    flavor = Flavor.GITHUB,
                    bridgeGranted = true,
                )
                assertTrue("expected Denied for $argv, got $elevated", elevated is Validation.Denied)
                assertEquals(ShellDeny.BLACKLISTED, (elevated as Validation.Denied).reason)
            }
            val allowed = listOf("toybox", "df", scope.absolutePath)
            // 直接通道有效門全過仍以 dispatcher NOT_WHITELISTED 拒（維持 Phase I，
            // 見 ShellMulticallEffectiveTest.safeMain_staysDispatcherDenied_notBlacklisted）；
            // 提權同形放行。
            val directAllowed = ShellPolicy.validate(allowed, scope.absolutePath)
            assertTrue("expected dispatcher Denied for $allowed, got $directAllowed", directAllowed is Validation.Denied)
            assertEquals(
                ShellDeny.NOT_WHITELISTED,
                (directAllowed as Validation.Denied).reason,
            )
            assertTrue(
                "expected elevated Allowed for $allowed",
                ShellPolicy.validateElevated(
                    allowed,
                    scope.absolutePath,
                    flavor = Flavor.GITHUB,
                    bridgeGranted = true,
                ) is Validation.Allowed,
            )
        } finally {
            scope.deleteRecursively()
        }
    }

    @Test fun dfDirectRunner_zeroSpawnForPathlessAndAll() {
        val scope = tempDir("stageJ-df-runner")
        try {
            val vectors = listOf(
                listOf("df"),
                listOf("df", "-a"),
                listOf("df", "--all"),
                listOf("df", "-a", scope.absolutePath),
                listOf("df", "--all", scope.absolutePath),
                listOf("toybox", "df"),
                listOf("toybox", "df", "-a", scope.absolutePath),
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
        }
    }

    // ---- P2：host GNU `df /scope` 真實 scoped 讀（唯讀觀察） ----

    @Test fun dfScopedRealRunAndPolicyAllows() {
        assumeNonWindows()
        val dfBin = firstExecutable("/bin/df", "/usr/bin/df")
        assumeTrue(dfBin != null)
        val scope = tempDir("stageJ-df-real")
        try {
            // 唯讀觀察：`df /scope` 只印該路徑所在 fs 行（exit 0），不列全域掛載表語義由政策保證。
            val raw = DefaultProcessRunner(dirRoot = scope.absolutePath)
                .run(listOf(dfBin!!, scope.absolutePath), 10_000L, null)
            assertEquals("df scoped must exit 0, got: ${raw.exitCode}", 0, raw.exitCode)
            val out = String(raw.stdout, Charsets.UTF_8)
            assertTrue("df scoped output must be non-empty, got: $out", out.isNotBlank())
            // 政策側同形放行（直接＋提權）。
            val argv = listOf("df", scope.absolutePath)
            assertTrue(
                "expected Allowed, got ${ShellPolicy.validate(argv, scope.absolutePath)}",
                ShellPolicy.validate(argv, scope.absolutePath) is Validation.Allowed,
            )
            assertTrue(
                "expected elevated Allowed",
                ShellPolicy.validateElevated(
                    argv,
                    scope.absolutePath,
                    flavor = Flavor.GITHUB,
                    bridgeGranted = true,
                ) is Validation.Allowed,
            )
            // 跑道層同形真放行（FakeRunner 計數 spawn==1，零拒）。
            val runner = ArgvCaptureRunner()
            val shell = RestrictedShell(
                runner = runner,
                privateRoot = scope.absolutePath,
                execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
            )
            val result = shell.execute(argv)
            assertTrue("expected Ok, got $result", result is ShellResult.Ok)
            assertEquals(1, runner.calls)
        } finally {
            scope.deleteRecursively()
        }
    }
}
