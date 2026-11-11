package dev.librepocket.shell

import dev.librepocket.tool.Flavor
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Stage I（P1-3 response-file indirection）deny-all 迴歸。
 *
 * 威脅原語：`du --files0-from=F`／`wc --files0-from=F`（double-dash）與
 * `find -files0-from F`（single-dash）在 spawn 後讀 MANIFEST `F` 內容所列
 * NUL 路徑（`/outside/...`），不經 [FileScope]。政策若只 vet MANIFEST 本體
 * （`/scope/list` 域內即放行），即穿出（無需 symlink／race／`-L`）。
 * GNU 實測三者皆真讀域外（見下 REAL 證據；Toybox per-ROM 未驗，
 * 拒絕對其 fail-closed 無害——無效旗標本就 exit 2）。
 *
 * 政策：[ShellPolicy.isResponseFileKey] 見即拒（key token 本體，不看次 argv
 * 值槽；分離形／`=v` 附著／唯一前綴縮寫／無效延伸同門；`--` 後／槽內／guest
 * 亦不豁免；multicall 有效形經 [ShellPolicy.checkPathArgs] 同門）。
 * 即使 MANIFEST 域內亦 `Denied(BLACKLISTED)`（vet 獨立性）。
 * 未來受控 helper（讀＋vet＋freeze＋替換 operands）屬 out-of-scope。
 *
 * 覆蓋（MANIFEST 域內、內容列域外；trailing-legal，唯 du 除外——
 * GNU 禁 --files0-from 與路徑操作數並存，故 du 無 trailing）：
 * - du／wc／find ×｛分離形、`=v`、縮寫分離、`=v` 縮寫｝直接＋提權 Denied；
 * - multicall toybox 有效形同門；
 * - 直接跑道層零 spawn；
 * - REAL load-bearing：繞過政策直跑 GNU 即讀到域外（證 indirection），
 *   政策拒絕且零 spawn（唯讀觀察，不修改）。
 *
 * Root／Shizuku 提權 runner 皆以 [ShellPolicy.validateElevated] 為先行門
 * （拒即零 spawn，見 `RootSuRunner`／`ShizukuShellRunner` KDoc），故提權側
 * `validateElevated` pin 即覆兩 runner 之門；直接側另以 [RestrictedShell]
 * 斷零 spawn。
 */
class ShellStageIResponseFileTest {

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().canonicalFile

    private fun assumeNonWindows() {
        val osName: String = System.getProperty("os.name") ?: ""
        assumeTrue(!osName.startsWith("Windows"))
    }

    private data class ResponseFixture(
        val scope: File,
        val outside: File,
        val manifestAbs: String,
        val insideAbs: String,
    )

    private fun responseFixture(prefix: String): ResponseFixture {
        assumeNonWindows()
        val scope = tempDir("$prefix-scope").canonicalFile
        val outside = tempDir("$prefix-outside").canonicalFile
        File(outside, "hidden.txt").writeText("OUTSIDE_MARK_xyz\n")
        File(scope, "inside.txt").writeText("INSIDE_MARK_abc\n")
        // MANIFEST 域內（/scope/list），內容列域外 NUL 路徑（indirection 載荷）。
        val manifest = File(scope, "list")
        File(outside, "hidden.txt").absolutePath.let { outsidePath ->
            Files.write(manifest.toPath(), (outsidePath + "\u0000").toByteArray(Charsets.UTF_8))
        }
        return ResponseFixture(
            scope,
            outside,
            manifest.absolutePath,
            File(scope, "inside.txt").absolutePath,
        )
    }

    private fun assertBlockedResponseFile(v: Validation, argv: List<String>) {
        assertTrue("expected Denied for $argv, got $v", v is Validation.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        assertTrue(
            "message must stay in response-file family, got ${v.message}",
            v.message.contains("response file denied"),
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

    private fun duVectors(manifest: String, scopeAbs: String): List<List<String>> = listOf(
        // 分離形＋`=v`（GNU 真讀）。
        listOf("du", "--files0-from", manifest, scopeAbs),
        listOf("du", "--files0-from=$manifest", scopeAbs),
        // 唯一前綴縮寫（GNU 真讀：`--f` 亦唯一映射 `--files0-from`，實測）。
        listOf("du", "--files0-f", manifest, scopeAbs),
        listOf("du", "--f", manifest, scopeAbs),
        listOf("du", "--files0-f=$manifest", scopeAbs),
        listOf("du", "--f=$manifest", scopeAbs),
    )

    private fun wcVectors(manifest: String): List<List<String>> = listOf(
        // wc `--files0-from` 不可與 file operands 併用（GNU exit 1），故 trailing-legal
        // 取非檔旗 `-c`（byte counts，合法 exit 0，見 REAL 證據）。
        listOf("wc", "--files0-from", manifest, "-c"),
        listOf("wc", "--files0-from=$manifest", "-c"),
        listOf("wc", "--files0-f", manifest, "-c"),
        listOf("wc", "--f", manifest, "-c"),
        listOf("wc", "--files0-f=$manifest", "-c"),
        listOf("wc", "--f=$manifest", "-c"),
    )

    private fun findVectors(manifest: String): List<List<String>> = listOf(
        // find `-files0-from` 為 single-dash，分離形為唯一真讀形；
        // `=v` 附著／縮寫／double-dash 在 GNU 皆 exit 1 `未知謂語`，
        // fail-closed 一併拒（trailing `-print` 為合法 expression）。
        listOf("find", "-files0-from", manifest, "-print"),
        listOf("find", "-files0-from=$manifest", "-print"),
        listOf("find", "-files0-f", manifest, "-print"),
        listOf("find", "--files0-from", manifest, "-print"),
    )

    // ---- Stage I 政策層（直接＋提權同門） ----

    @Test fun duDenied_directAndElevated() {
        val f = responseFixture("stageI-du")
        try {
            for (argv in duVectors(f.manifestAbs, f.scope.absolutePath)) {
                assertBlockedResponseFile(ShellPolicy.validate(argv, f.scope.absolutePath), argv)
                assertBlockedResponseFile(
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

    @Test fun wcDenied_directAndElevated() {
        val f = responseFixture("stageI-wc")
        try {
            for (argv in wcVectors(f.manifestAbs)) {
                assertBlockedResponseFile(ShellPolicy.validate(argv, f.scope.absolutePath), argv)
                assertBlockedResponseFile(
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

    @Test fun findDenied_directAndElevated() {
        val f = responseFixture("stageI-find")
        try {
            for (argv in findVectors(f.manifestAbs)) {
                assertBlockedResponseFile(ShellPolicy.validate(argv, f.scope.absolutePath), argv)
                assertBlockedResponseFile(
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

    @Test fun dashDashResponseFile_stillDenied_allPosition() {
        // All-position lock: `--` 終止符後同名檔名亦拒（fail-closed，罕見寫法誤殺可接受）。
        val f = responseFixture("stageI-dashdash")
        try {
            val vectors = listOf(
                listOf("du", f.scope.absolutePath, "--", "--files0-from"),
                listOf("du", f.scope.absolutePath, "--", "--files0-from=" + f.manifestAbs),
                listOf("du", f.scope.absolutePath, "--", "--f"),
                listOf("wc", "-c", "--", "--files0-from"),
                listOf("find", "-print", "--", "-files0-from"),
            )
            for (argv in vectors) {
                assertBlockedResponseFile(ShellPolicy.validate(argv, f.scope.absolutePath), argv)
                assertBlockedResponseFile(
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

    @Test fun guestResponseFile_stillDenied_noExempt() {
        // No-exempt lock: guest＋scope 亦不豁免（容器內 MANIFEST 內容仍可列容器內外路徑）。
        val f = responseFixture("stageI-guest")
        try {
            val vectors = listOf(
                listOf("du", "--files0-from", f.manifestAbs, f.scope.absolutePath),
                listOf("wc", "--files0-from=" + f.manifestAbs, "-c"),
                listOf("find", "-files0-from", f.manifestAbs, "-print"),
                listOf("toybox", "du", "--files0-from", f.manifestAbs, f.scope.absolutePath),
            )
            for (argv in vectors) {
                assertBlockedResponseFile(
                    ShellPolicy.validate(
                        argv,
                        f.scope.absolutePath,
                        isGuest = true,
                    ),
                    argv,
                )
                assertBlockedResponseFile(
                    ShellPolicy.validateElevated(
                        argv,
                        f.scope.absolutePath,
                        flavor = Flavor.GITHUB,
                        bridgeGranted = true,
                        isGuest = true,
                    ),
                    argv,
                )
            }
        } finally {
            f.scope.deleteRecursively()
            f.outside.deleteRecursively()
        }
    }

    @Test fun multicallEffective_deniedDirectAndElevated() {
        val f = responseFixture("stageI-multi")
        try {
            val vectors = listOf(
                listOf("toybox", "du", "--files0-from", f.manifestAbs, f.scope.absolutePath),
                listOf("toybox", "du", "--files0-from=" + f.manifestAbs, f.scope.absolutePath),
                listOf("toybox", "du", "--f", f.manifestAbs, f.scope.absolutePath),
                listOf("toybox", "wc", "--files0-from", f.manifestAbs, "-c"),
                listOf("toybox", "wc", "--files0-from=" + f.manifestAbs, "-c"),
                listOf("toybox", "wc", "--f=" + f.manifestAbs, "-c"),
                listOf("toybox", "find", "-files0-from", f.manifestAbs, "-print"),
                listOf("toybox", "find", "-files0-from=" + f.manifestAbs, "-print"),
            )
            for (argv in vectors) {
                assertBlockedResponseFile(ShellPolicy.validate(argv, f.scope.absolutePath), argv)
                assertBlockedResponseFile(
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

    // ---- 跑道層（直接通道）：零 spawn ----

    @Test fun directRunner_zeroSpawn() {
        val f = responseFixture("stageI-runner")
        try {
            val vectors = mutableListOf<List<String>>()
            vectors.addAll(duVectors(f.manifestAbs, f.scope.absolutePath))
            vectors.addAll(wcVectors(f.manifestAbs))
            vectors.addAll(findVectors(f.manifestAbs))
            vectors.add(listOf("toybox", "du", "--files0-from", f.manifestAbs, f.scope.absolutePath))
            vectors.add(listOf("toybox", "wc", "--files0-from", f.manifestAbs, "-c"))
            vectors.add(listOf("toybox", "find", "-files0-from", f.manifestAbs, "-print"))
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

    // ---- 真實語義 load-bearing（繞過政策直跑 GNU 即讀域外；政策拒絕＋零 spawn） ----

    @Test fun duRealIndirectionAndPolicyDenies() {
        assumeNonWindows()
        val duBin = firstExecutable("/bin/du", "/usr/bin/du")
        assumeTrue(duBin != null)
        val f = responseFixture("stageI-du-real")
        try {
            // 唯讀觀察：MANIFEST 域內、內容列域外；`du` 直讀即印域外（exit 0）。
            // 注意 GNU du 禁止 --files0-from 與路徑操作數並存（exit 1），故無
            // trailing 操作數（SYNOPSIS 第 2 式；uutils 較寬鬆，本地會過但 CI 不過）。
            val raw = DefaultProcessRunner(dirRoot = f.scope.absolutePath)
                .run(listOf(duBin!!, "--files0-from=" + f.manifestAbs), 10_000L, null)
            val out = String(raw.stdout, Charsets.UTF_8)
            assertEquals("OS must read outside via manifest (exit 0), got: $out", 0, raw.exitCode)
            assertTrue("OS must list outside path, got: $out", out.contains("hidden.txt"))
            // 政策拒絕（直接＋提權）且零 spawn（無 trailing 操作數：GNU 不允許
            // 其與 --files0-from 並存；拒絕僅來自 response-file 門）。
            val argv = listOf("du", "--files0-from=" + f.manifestAbs)
            assertBlockedResponseFile(ShellPolicy.validate(argv, f.scope.absolutePath), argv)
            assertBlockedResponseFile(
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

    @Test fun wcRealIndirectionAndPolicyDenies() {
        assumeNonWindows()
        val wcBin = firstExecutable("/bin/wc", "/usr/bin/wc")
        assumeTrue(wcBin != null)
        val f = responseFixture("stageI-wc-real")
        try {
            val raw = DefaultProcessRunner(dirRoot = f.scope.absolutePath)
                .run(listOf(wcBin!!, "--files0-from=" + f.manifestAbs, "-c"), 10_000L, null)
            val out = String(raw.stdout, Charsets.UTF_8)
            assertEquals("OS must read outside via manifest (exit 0), got: $out", 0, raw.exitCode)
            assertTrue("OS must report outside path, got: $out", out.contains("hidden.txt"))
            val argv = listOf("wc", "--files0-from=" + f.manifestAbs, "-c")
            assertBlockedResponseFile(ShellPolicy.validate(argv, f.scope.absolutePath), argv)
            assertBlockedResponseFile(
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

    @Test fun findRealIndirectionAndPolicyDenies() {
        assumeNonWindows()
        val findBin = firstExecutable("/bin/find", "/usr/bin/find")
        assumeTrue(findBin != null)
        val f = responseFixture("stageI-find-real")
        try {
            val raw = DefaultProcessRunner(dirRoot = f.scope.absolutePath)
                .run(listOf(findBin!!, "-files0-from", f.manifestAbs, "-print"), 10_000L, null)
            val out = String(raw.stdout, Charsets.UTF_8)
            assertEquals("OS must read outside via manifest (exit 0), got: $out", 0, raw.exitCode)
            assertTrue("OS must list outside path, got: $out", out.contains("hidden.txt"))
            val argv = listOf("find", "-files0-from", f.manifestAbs, "-print")
            assertBlockedResponseFile(ShellPolicy.validate(argv, f.scope.absolutePath), argv)
            assertBlockedResponseFile(
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
}
