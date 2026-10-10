package dev.librepocket.shell

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 直接 shell 通道可信 executable + 乾淨 env 負向矩陣。
 *
 * - 同名不同路徑的假二進位（`/tmp/evil/ls`）在政策層即拒，零解析、零 spawn；
 * - bare 經受控搜尋固定到驗證後絕對路徑，不查 `PATH`、不看 cwd，
 *   惡意同名檔（不同目錄）永遠落選；
 * - symlink：嚴格 NOFOLLOW 判 link 本體，實體驗證固定實體；
 *   dangling / 環 / 非正規檔 / 不可執行一律拒；
 *   S3 containment + writability：可信 dir 內 entry 經 symlink 指到集外
 *   app-writable 目標即拒（resolve 回 null，全鏈零 spawn）；同集內（含
 *   `/bin -> usr/bin` 形合併）放行；real 本體/parent 可寫即拒（宿主暫存檔
 *   屬主可寫故經 `isWritable` 注入模擬裝置不可寫，見 `systemOwned`）；
 * - 操作數 schema（`--opt=value`、短旗標合併、值槽、pattern 槽）維持既有嚴格語義；
 * - null 作用域的隱式 cwd bare fail-closed，且放行者 spawn 仍是固定路徑 + 乾淨 env；
 * - multicall（toybox/toolbox/busybox）：實體 basename 落顯式表時 spawn 還原
 *   applet（`[real, applet, ...]`），bare/絕對 `argv[0]` 全鏈覆蓋 + 真跑 e2e；
 *   舊形狀（無 applet）負向對照證非 vacuous；非表內（正規檔/同名 link/
 *   版本化名）維持無插入；
 * - `env == null` 不再繼承宿主（真實 runner 回落 [ShellExecutables.CLEAN_ENV]）。
 */
class ShellTrustedExecTest {

    private class CapRunner(
        private val stdout: ByteArray = "ok".toByteArray(),
    ) : ProcessRunner {
        var calls: Int = 0
        var lastArgv: List<String>? = null
        var lastEnv: Map<String, String>? = null
        override fun run(argv: List<String>, timeoutMs: Long, env: Map<String, String>?): RawOutput {
            calls++
            lastArgv = argv
            lastEnv = env
            return RawOutput(stdout, ByteArray(0), 0, false)
        }
    }

    private val privateRoot = "/data/data/dev.librepocket.agent/files"

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun executable(dir: File, name: String): File {
        val f = File(dir, name)
        f.writeText("#!/bin/sh\necho hi\n")
        assertTrue("setExecutable failed for $f", f.setExecutable(true))
        return f
    }

    /**
     * 模擬裝置「系統自帶不可寫」：宿主單測的暫存檔屬主可寫（`Files.isWritable`
     * 真查必回 true），直接用預設判定會把合法 fixture 全拒；故凡「合法系統
     * 二進位」情境一律注入此判定。writability 閘本身的存活另由
     * `defaultWritabilityGate_liveOnHost` 以預設值真查證明（屬主可寫即拒）。
     */
    private val systemOwned: (java.nio.file.Path) -> Boolean = { _ -> false }

    // ---- 同名不同路徑：政策層即拒，零解析、零 spawn ----

    @Test fun untrustedAbsoluteArgv0_deniedWithoutSpawn() {
        val evilArgvs = listOf(
            listOf("/tmp/evil/ls", "-l"),
            listOf("/tmp/evil/echo", "hi"),
            listOf("/data/local/tmp/ls"),
            listOf("/sdcard/ls"),
            listOf("/bin/../tmp/evil/ls"),
            listOf("./ls"),
            listOf("../bin/ls"),
            listOf("chat/ls"),
        )
        for (argv in evilArgvs) {
            val v = ShellPolicy.validate(argv, privateRoot)
            assertTrue("$argv -> $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
            assertTrue("$argv", v.message.contains("untrusted executable"))
            var resolves = 0
            val runner = CapRunner()
            val shell = RestrictedShell(
                runner = runner,
                privateRoot = privateRoot,
                execResolver = { resolves++; ShellExecutables.ResolvedExec("/should-never-be-used/$it", null) },
            )
            val r = shell.execute(argv)
            assertTrue("$argv -> $r", r is ShellResult.Denied)
            assertEquals(0, runner.calls)
            assertEquals("$argv must not reach resolver", 0, resolves)
            assertEquals(null, runner.lastEnv)
        }
    }

    // ---- 詞法門（無 FS 依賴）：可信目錄放行，其餘全拒 ----

    @Test fun trustedAbsoluteArgv0_lexical() {
        assertTrue(ShellExecutables.isTrustedBinDir("/bin"))
        assertTrue(ShellExecutables.isTrustedBinDir("/system/bin"))
        assertFalse(ShellExecutables.isTrustedBinDir("/tmp"))
        assertFalse(ShellExecutables.isTrustedBinDir(""))
        assertTrue(ShellExecutables.isTrustedAbsoluteArgv0("/bin/echo"))
        assertTrue(ShellExecutables.isTrustedAbsoluteArgv0("/usr/bin/ls"))
        assertTrue(ShellExecutables.isTrustedAbsoluteArgv0("/system/bin/getprop"))
        assertFalse(ShellExecutables.isTrustedAbsoluteArgv0("ls"))
        assertFalse(ShellExecutables.isTrustedAbsoluteArgv0("/tmp/evil/ls"))
        assertFalse(ShellExecutables.isTrustedAbsoluteArgv0("/bin"))
        assertFalse(ShellExecutables.isTrustedAbsoluteArgv0("/bin/"))
        assertFalse(ShellExecutables.isTrustedAbsoluteArgv0("/bin/../tmp/evil/ls"))
        assertFalse(ShellExecutables.isTrustedAbsoluteArgv0(""))
        // 政策層：可信絕對 argv0 維持放行（純詞法，無 FS 依賴）。
        assertTrue(ShellPolicy.validate(listOf("/bin/echo", "hi"), null) is Validation.Allowed)
    }

    // ---- bare 受控解析：固定可信目錄，惡意同名落選，spawn 絕對路徑 ----

    @Test fun bareResolvesViaControlledSearch_evilSameNameIgnored() {
        val trusted = tempDir("trusted-bin")
        val evil = tempDir("evil-bin")
        val scope = tempDir("scope")
        try {
            val good = executable(trusted, "ls")
            executable(evil, "ls")
            val runner = CapRunner()
            val shell = RestrictedShell(
                runner = runner,
                privateRoot = scope.absolutePath,
                execResolver = { ShellExecutables.resolve(it, listOf(trusted.absolutePath), isWritable = systemOwned) },
            )
            val r = shell.execute(listOf("ls", "-l"))
            assertTrue("$r", r is ShellResult.Ok)
            val spawned = runner.lastArgv!!
            assertEquals(good.canonicalPath, spawned[0])
            assertTrue(spawned[0].startsWith("/"))
            assertFalse(spawned[0].startsWith(evil.absolutePath))
            assertEquals(listOf("-l"), spawned.drop(1))
            assertEquals(ShellExecutables.CLEAN_ENV, runner.lastEnv)
        } finally {
            trusted.deleteRecursively()
            evil.deleteRecursively()
            scope.deleteRecursively()
        }
    }

    // ---- symlink 矩陣：NOFOLLOW 判 link，實體驗證固定實體 ----

    @Test fun symlinkMatrix_noFollowLinkAndPinTarget() {
        val dir = tempDir("symlinkmx")
        try {
            val real = executable(dir, "real")
            val link = dir.toPath().resolve("link")
            Files.createSymbolicLink(link, real.toPath())
            val dangling = dir.toPath().resolve("dangling")
            Files.createSymbolicLink(dangling, dir.toPath().resolve("no-such-target"))
            val loopA = dir.toPath().resolve("loopA")
            val loopB = dir.toPath().resolve("loopB")
            Files.createSymbolicLink(loopA, loopB)
            Files.createSymbolicLink(loopB, loopA)
            val plain = File(dir, "plain").apply { writeText("x") }
            val linkToDir = dir.toPath().resolve("linkToDir")
            Files.createSymbolicLink(linkToDir, dir.toPath())
            // 嚴格 NOFOLLOW：終端 symlink 即 false。
            assertTrue(ShellExecutables.isExecutableNoFollow(real.absolutePath))
            assertFalse(ShellExecutables.isExecutableNoFollow(link.toString()))
            assertFalse(ShellExecutables.isExecutableNoFollow(dangling.toString()))
            assertFalse(ShellExecutables.isExecutableNoFollow(plain.absolutePath))
            assertFalse(ShellExecutables.isExecutableNoFollow(dir.absolutePath))
            // 實體驗證：link 固定到實體；dangling / 環 / 指向目錄一律 null。
            // （合法 fixture 經 systemOwned 模擬裝置不可寫；dangling/環/目錄
            // 在閘前即拒，預設值亦同。）
            assertEquals(
                real.canonicalPath,
                ShellExecutables.verifiedTarget(
                    link.toString(),
                    allowedRoots = listOf(dir.canonicalPath),
                    isWritable = systemOwned,
                ),
            )
            assertNull(ShellExecutables.verifiedTarget(dangling.toString()))
            assertNull(ShellExecutables.verifiedTarget(loopA.toString()))
            assertNull(ShellExecutables.verifiedTarget(linkToDir.toString()))
            // 受控解析：bare 搜尋跳過不可執行與 dangling；暫存絕對路徑非可信即 null。
            val okDir = tempDir("okdir")
            val emptyDir = tempDir("emptydir")
            try {
                executable(okDir, "mybin")
                assertEquals(
                    File(okDir, "mybin").canonicalPath,
                    ShellExecutables.resolve(
                        "mybin",
                        listOf(emptyDir.absolutePath, okDir.absolutePath),
                        isWritable = systemOwned,
                    )?.path,
                )
                assertNull(ShellExecutables.resolve("mybin", listOf(emptyDir.absolutePath)))
                assertNull(ShellExecutables.resolve("dangling", listOf(dir.absolutePath)))
                assertNull(ShellExecutables.resolve("${dir.absolutePath}/real"))
                assertNull(ShellExecutables.resolve("/bin/no-such-bin-xyz-123"))
            } finally {
                okDir.deleteRecursively()
                emptyDir.deleteRecursively()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- 操作數 schema：既有嚴格語義不放寬 ----

    @Test fun operandSchema_strictSemanticsKept() {
        val scope = tempDir("scope")
        try {
            val scopePath = scope.absolutePath
            val absFile = "$scopePath/x.txt"
            val stub: (String) -> ShellExecutables.ResolvedExec? = { raw ->
                if ('/' in raw || '\\' in raw) null else ShellExecutables.ResolvedExec("/trusted/$raw", null)
            }
            fun exec(argv: List<String>): Pair<ShellResult, CapRunner> {
                val runner = CapRunner()
                val shell = RestrictedShell(runner = runner, privateRoot = scopePath, execResolver = stub)
                return shell.execute(argv) to runner
            }
            // 放行形狀：argv[0] 改寫絕對，操作數原樣透傳。
            val (ok1, cap1) = exec(listOf("ls", "-l", absFile))
            assertTrue("$ok1", ok1 is ShellResult.Ok)
            assertEquals(listOf("/trusted/ls", "-l", absFile), cap1.lastArgv)
            assertEquals(ShellExecutables.CLEAN_ENV, cap1.lastEnv)
            val (ok2, cap2) = exec(listOf("ls", "-la"))
            assertTrue("$ok2", ok2 is ShellResult.Ok)
            assertEquals(listOf("/trusted/ls", "-la"), cap2.lastArgv)
            val (ok3, cap3) = exec(listOf("grep", "-rn", "password", absFile))
            assertTrue("$ok3", ok3 is ShellResult.Ok)
            assertEquals(listOf("/trusted/grep", "-rn", "password", absFile), cap3.lastArgv)
            val (ok4, cap4) = exec(listOf("head", "-n", "20", absFile))
            assertTrue("$ok4", ok4 is ShellResult.Ok)
            assertEquals(listOf("/trusted/head", "-n", "20", absFile), cap4.lastArgv)
            // 既有拒絕一律維持：相對、`--opt=bare`、含 `/` 短旗標、bare 檔名。
            for (argv in listOf(
                listOf("cat", "chat/x.txt"),
                listOf("cat", "--db=secret.db"),
                listOf("cat", "-foo/bar"),
                listOf("cat", "init.rc"),
                listOf("grep", "-r", "pw", "secrets"),
            )) {
                val (r, cap) = exec(argv)
                assertTrue("$argv -> $r", r is ShellResult.Denied)
                assertEquals(0, cap.calls)
            }
        } finally {
            scope.deleteRecursively()
        }
    }

    // ---- null 作用域：隱式 cwd bare 不落未授權目錄；放行者仍固定 spawn ----

    @Test fun nullScope_bareDoesNotEscape() {
        var resolves = 0
        val stub: (String) -> ShellExecutables.ResolvedExec? = { resolves++; ShellExecutables.ResolvedExec("/trusted/$it", null) }
        // 隱式讀 cwd 的 bare 在 null 作用域 fail-closed：零解析、零 spawn。
        for (argv in listOf(
            listOf("ls"),
            listOf("du"),
            listOf("pwd"),
            listOf("grep", "-r", "password"),
        )) {
            val runner = CapRunner()
            val shell = RestrictedShell(runner = runner, execResolver = stub)
            val r = shell.execute(argv)
            assertTrue("$argv -> $r", r is ShellResult.Denied)
            assertEquals(0, runner.calls)
        }
        assertEquals(0, resolves)
        // 無 cwd 語義的 bare 仍放行，但 spawn 已是固定絕對路徑 + 乾淨 env。
        val runner = CapRunner()
        val shell = RestrictedShell(runner = runner, execResolver = stub)
        val r = shell.execute(listOf("echo", "hi"))
        assertTrue("$r", r is ShellResult.Ok)
        assertEquals(1, resolves)
        assertEquals(listOf("/trusted/echo", "hi"), runner.lastArgv)
        assertEquals(ShellExecutables.CLEAN_ENV, runner.lastEnv)
    }

    // ---- 乾淨 env：spawn 必攜固定表，無危險變數 ----

    @Test fun cleanEnv_enforcedOnSpawn() {
        val scope = tempDir("scope")
        try {
            val runner = CapRunner()
            val shell = RestrictedShell(
                runner = runner,
                privateRoot = scope.absolutePath,
                execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
            )
            val r = shell.execute(listOf("echo", "hi"))
            assertTrue("$r", r is ShellResult.Ok)
            val env = runner.lastEnv
            assertNotNull(env)
            assertEquals(ShellExecutables.CLEAN_ENV, env)
            assertEquals(ShellExecutables.CLEAN_PATH, env!!["PATH"])
            assertFalse(env.containsKey("LD_PRELOAD"))
            assertFalse(env.containsKey("LD_LIBRARY_PATH"))
            assertTrue(env.keys.none { it.startsWith("PROOT_") })
            assertTrue(env.keys.none { it.equals("http_proxy", ignoreCase = true) })
            assertTrue(env.keys.none { it.equals("https_proxy", ignoreCase = true) })
        } finally {
            scope.deleteRecursively()
        }
    }

    // ---- 映射失敗：拒絕零 spawn 且不佔配額 ----

    @Test fun resolveFailure_deniedWithoutQuotaConsume() {
        val scope = tempDir("scope")
        try {
            var failNext = true
            val runner = CapRunner()
            val shell = RestrictedShell(
                quota = ShellQuota(maxCalls = 1, windowMs = 60_000L),
                runner = runner,
                privateRoot = scope.absolutePath,
                execResolver = { if (failNext) null else ShellExecutables.ResolvedExec("/trusted/$it", null) },
            )
            val denied = shell.execute(listOf("echo", "hi"))
            assertTrue("$denied", denied is ShellResult.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (denied as ShellResult.Denied).reason)
            assertEquals(0, runner.calls)
            failNext = false
            val ok = shell.execute(listOf("echo", "hi"))
            assertTrue("$ok", ok is ShellResult.Ok)
            assertEquals(1, runner.calls)
        } finally {
            scope.deleteRecursively()
        }
    }

    // ---- 真實 runner：null env 回落乾淨表；非 dir cwd 照舊拋 ----

    @Test fun defaultRunner_nullEnvGetsCleanEnv() {
        // 用 `env` 二進位回顯子進程環境（缺失則跳過；與既有測試同式）。
        val probe = try {
            DefaultProcessRunner().run(listOf("env"), 5_000L, mapOf("ONLY_GUEST_VAR" to "guest123"))
            true
        } catch (_: Exception) {
            false
        }
        if (!probe) return
        val out = DefaultProcessRunner().run(listOf("env"), 5_000L, null)
        val text = String(out.stdout, Charsets.UTF_8)
        val keys = text.lineSequence()
            .filter { it.contains('=') }
            .map { it.substringBefore('=') }
            .toSet()
        assertEquals(ShellExecutables.CLEAN_ENV.keys, keys)
        assertTrue(text.contains("PATH=${ShellExecutables.CLEAN_PATH}"))
        assertTrue("LD_PRELOAD leaked", keys.none { it == "LD_PRELOAD" })
    }

    @Test fun pinnedCwd_nonDirThrowsInsteadOfInheriting() {
        try {
            DefaultProcessRunner("/nonexistent-dir-xyz-123").run(listOf("echo"), 5_000L, emptyMap())
            fail("expected IllegalStateException for non-dir cwd")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("/nonexistent-dir-xyz-123"))
        }
    }

    // ---- multicall（toybox/toolbox/busybox）：spawn 還原 applet ----
    //
    // 生產預設路徑（validate → 預設解析 → spawn）全鏈直測，不用假映射：
    // execSearchDirs 注入暫存 fixture（validate 詞法門與 resolve 同表），
    // 解析一律走真實 ShellExecutables.resolve。

    /** toybox multicall fixture：零宿主委派（不找系統 cat/ls/echo）。 */
    private val toyboxScript: String = """
        #!/bin/sh
        # 被呼叫名是 toybox 本體時取 ${'$'}1 為 applet，否則取 basename(${'$'}0)；
        # 只實作 echo/ls/cat 最小語義，未知 applet 即 stderr + exit 1
        #（模擬真機 `toybox -l` 的 unknown-applet 失敗）。
        self=${'$'}{0##*/}
        if [ "${'$'}self" = "toybox" ]; then
          if [ ${'$'}# -eq 0 ]; then echo "toybox: no applet" >&2; exit 1; fi
          applet=${'$'}1; shift
        else
          applet="${'$'}self"
        fi
        case "${'$'}applet" in
          echo) echo "${'$'}@" ;;
          ls) printf 'total 0\n-rwxr-xr-x 1 root root 8 Jan  1  1970 toybox\n' ;;
          cat) for f in "${'$'}@"; do while IFS= read -r line || [ -n "${'$'}line" ]; do printf '%s\n' "${'$'}line"; done < "${'$'}f"; done ;;
          *) echo "toybox: unknown applet: ${'$'}applet" >&2; exit 1 ;;
        esac
    """.trimIndent()

    private val toyboxLsOut = "total 0\n-rwxr-xr-x 1 root root 8 Jan  1  1970 toybox\n"

    /** fixture：`toybox` 可執行腳本 + `ls/echo/cat` symlinks，全部同目錄。 */
    private fun multicallDir(): File {
        val bin = tempDir("mcb-bin")
        val toybox = File(bin, "toybox")
        toybox.writeText(toyboxScript)
        assertTrue("setExecutable failed for $toybox", toybox.setExecutable(true))
        for (applet in listOf("ls", "echo", "cat")) {
            Files.createSymbolicLink(File(bin, applet).toPath(), toybox.toPath())
        }
        return bin
    }

    @Test fun multicall_bareSpawnShape_insertsApplet() {
        val bin = multicallDir()
        val scope = tempDir("mcb-scope")
        try {
            val toyboxReal = File(bin, "toybox").canonicalPath
            val input = File(scope, "in.txt").apply { writeText("hello\n") }
            // resolve 層：實體落表即攜 applet（取自 argv[0] basename）。
            // systemOwned 模擬裝置不可寫（宿主 fixture 屬主可寫，見 helper 註解）。
            val bare = ShellExecutables.resolve("ls", listOf(bin.absolutePath), isWritable = systemOwned)!!
            assertEquals(toyboxReal, bare.path)
            assertEquals("ls", bare.applet)
            // 全鏈 spawn 形狀：[real, applet, ...args]。
            val cases = listOf(
                listOf("ls", "-l") to listOf(toyboxReal, "ls", "-l"),
                listOf("echo", "hi") to listOf(toyboxReal, "echo", "hi"),
                listOf("cat", input.absolutePath) to
                    listOf(toyboxReal, "cat", input.absolutePath),
            )
            for ((argv, expected) in cases) {
                val runner = CapRunner()
                val shell = RestrictedShell(
                    runner = runner,
                    privateRoot = scope.absolutePath,
                    execSearchDirs = listOf(bin.absolutePath),
                    execIsWritable = systemOwned,
                )
                val r = shell.execute(argv)
                assertTrue("$argv -> $r", r is ShellResult.Ok)
                assertEquals("$argv", expected, runner.lastArgv)
                assertEquals(ShellExecutables.CLEAN_ENV, runner.lastEnv)
            }
        } finally {
            bin.deleteRecursively()
            scope.deleteRecursively()
        }
    }

    @Test fun multicall_absoluteSpawnShape_insertsApplet() {
        val bin = multicallDir()
        val scope = tempDir("mcb-scope")
        try {
            val toyboxReal = File(bin, "toybox").canonicalPath
            val input = File(scope, "in.txt").apply { writeText("hello\n") }
            // resolve 層：絕對 argv[0]（link 路徑）同樣固定實體 + 還原 applet。
            val abs = ShellExecutables.resolve(
                "${bin.absolutePath}/ls",
                listOf(bin.absolutePath),
                isWritable = systemOwned,
            )!!
            assertEquals(toyboxReal, abs.path)
            assertEquals("ls", abs.applet)
            // 全鏈 spawn 形狀：[real, applet, ...args]（validate 詞法門與
            // resolve 同用 execSearchDirs，故暫存絕對路徑可全鏈不斷言系統目錄）。
            val cases = listOf(
                listOf("${bin.absolutePath}/ls", "-l") to listOf(toyboxReal, "ls", "-l"),
                listOf("${bin.absolutePath}/echo", "hi") to listOf(toyboxReal, "echo", "hi"),
                listOf("${bin.absolutePath}/cat", input.absolutePath) to
                    listOf(toyboxReal, "cat", input.absolutePath),
            )
            for ((argv, expected) in cases) {
                val runner = CapRunner()
                val shell = RestrictedShell(
                    runner = runner,
                    privateRoot = scope.absolutePath,
                    execSearchDirs = listOf(bin.absolutePath),
                    execIsWritable = systemOwned,
                )
                val r = shell.execute(argv)
                assertTrue("$argv -> $r", r is ShellResult.Ok)
                assertEquals("$argv", expected, runner.lastArgv)
                assertEquals(ShellExecutables.CLEAN_ENV, runner.lastEnv)
            }
        } finally {
            bin.deleteRecursively()
            scope.deleteRecursively()
        }
    }

    @Test fun multicall_endToEnd_defaultRunner() {
        val bin = multicallDir()
        val scope = tempDir("mcb-scope")
        try {
            val input = File(scope, "in.txt").apply { writeText("hello\n") }
            // echo/ls/cat × bare/絕對：真跑道、真 fixture，斷言 exit + stdout。
            // execIsWritable 注入 systemOwned：宿主 fixture 屬主可寫，預設值
            // 會 fail-closed 全拒；validate → resolve → spawn 全鏈仍走預設解析。
            val cases = listOf(
                listOf("echo", "hi") to "hi\n",
                listOf("ls", "-l") to toyboxLsOut,
                listOf("cat", input.absolutePath) to "hello\n",
                listOf("${bin.absolutePath}/echo", "hi") to "hi\n",
                listOf("${bin.absolutePath}/ls", "-l") to toyboxLsOut,
                listOf("${bin.absolutePath}/cat", input.absolutePath) to "hello\n",
            )
            for ((argv, expected) in cases) {
                val shell = RestrictedShell(
                    runner = DefaultProcessRunner(),
                    privateRoot = scope.absolutePath,
                    execSearchDirs = listOf(bin.absolutePath),
                    execIsWritable = systemOwned,
                )
                val r = shell.execute(argv)
                assertTrue("$argv -> $r", r is ShellResult.Ok)
                val ok = r as ShellResult.Ok
                assertEquals("$argv", 0, ok.exitCode)
                assertEquals("$argv", expected, ok.stdout)
            }
        } finally {
            bin.deleteRecursively()
            scope.deleteRecursively()
        }
    }

    @Test fun multicall_oldShapeWithoutApplet_fails() {
        val bin = multicallDir()
        try {
            val toyboxReal = File(bin, "toybox").canonicalPath
            // 負向對照：舊形狀 [toyboxReal, "-l"]（無 applet）真跑一次，
            // 必須非零 exit + unknown-applet 錯誤——證正向斷言非 vacuous
            //（fixture 確實會因缺 applet 而死，新形狀的 applet 不可省）。
            val raw = DefaultProcessRunner().run(
                listOf(toyboxReal, "-l"),
                10_000L,
                ShellExecutables.CLEAN_ENV,
            )
            assertTrue("old shape must fail: exit=${raw.exitCode}", raw.exitCode != 0)
            assertTrue(
                "stderr=${String(raw.stderr, Charsets.UTF_8)}",
                String(raw.stderr, Charsets.UTF_8).contains("unknown applet"),
            )
        } finally {
            bin.deleteRecursively()
        }
    }

    @Test fun nonMulticall_noAppletInserted() {
        val first = tempDir("nm-first")
        val second = tempDir("nm-second")
        val scope = tempDir("nm-scope")
        try {
            // 正規檔：名為 echo 的普通腳本（實體 basename 不在 multicall 表）。
            val plain = File(second, "echo")
            plain.writeText("#!/bin/sh\necho plain\n")
            assertTrue("setExecutable failed for $plain", plain.setExecutable(true))
            // 同 basename symlink：first/echo -> second/echo（兩端皆名 echo）。
            Files.createSymbolicLink(File(first, "echo").toPath(), plain.toPath())
            val dirs = listOf(first.absolutePath, second.absolutePath)
            // resolve 層：link 與直找一律 applet == null。
            val viaLink = ShellExecutables.resolve("echo", dirs, isWritable = systemOwned)!!
            assertEquals(plain.canonicalPath, viaLink.path)
            assertNull(viaLink.applet)
            val direct = ShellExecutables.resolve("echo", listOf(second.absolutePath), isWritable = systemOwned)!!
            assertEquals(plain.canonicalPath, direct.path)
            assertNull(direct.applet)
            // 全鏈 spawn 維持 [real, ...args]，不插入。
            val runner = CapRunner()
            val shell = RestrictedShell(
                runner = runner,
                privateRoot = scope.absolutePath,
                execSearchDirs = dirs,
                execIsWritable = systemOwned,
            )
            val r = shell.execute(listOf("echo", "hi"))
            assertTrue("$r", r is ShellResult.Ok)
            assertEquals(listOf(plain.canonicalPath, "hi"), runner.lastArgv)
            // 真跑：普通腳本確實執行（非 multicall 語義無損）。
            val real = RestrictedShell(
                runner = DefaultProcessRunner(),
                privateRoot = scope.absolutePath,
                execSearchDirs = dirs,
                execIsWritable = systemOwned,
            )
            val e2e = real.execute(listOf("echo", "hi"))
            assertTrue("$e2e", e2e is ShellResult.Ok)
            assertEquals("plain\n", (e2e as ShellResult.Ok).stdout)
        } finally {
            first.deleteRecursively()
            second.deleteRecursively()
            scope.deleteRecursively()
        }
    }

    @Test fun multicallTable_exactMatchOnly() {
        val dir = tempDir("mcb-table")
        try {
            assertEquals(
                setOf("toybox", "toolbox", "busybox"),
                ShellExecutables.MULTICALL_BINARIES,
            )
            for (name in listOf("toybox", "toolbox", "busybox")) {
                val f = File(dir, name)
                f.writeText("#!/bin/sh\necho marker\n")
                assertTrue("setExecutable failed for $f", f.setExecutable(true))
                val hit = ShellExecutables.resolve(name, listOf(dir.absolutePath), isWritable = systemOwned)!!
                assertEquals("$name", name, hit.applet)
            }
            // 版本化／改名／普通二進位不在表：一律無插入（拒「名不同即插」）。
            for (name in listOf("toybox-arm", "coreutils", "myecho")) {
                val f = File(dir, name)
                f.writeText("#!/bin/sh\necho marker\n")
                assertTrue("setExecutable failed for $f", f.setExecutable(true))
                val hit = ShellExecutables.resolve(name, listOf(dir.absolutePath), isWritable = systemOwned)!!
                assertNull("$name", hit.applet)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- S3 containment：可信 entry 指到集外即拒（writability 無關） ----

    @Test fun symlinkEscapeOutsideRoots_rejected() {
        val trusted = tempDir("s3-trusted")
        val evilDir = tempDir("s3-evil")
        try {
            val evil = executable(evilDir, "payload")
            Files.createSymbolicLink(File(trusted, "ls").toPath(), evil.toPath())
            // 即使「全不可寫」（containment 單獨作用），逃逸仍拒：
            // bare 與絕對 argv0 兩形。
            assertNull(
                ShellExecutables.resolve("ls", listOf(trusted.absolutePath), isWritable = systemOwned),
            )
            assertNull(
                ShellExecutables.resolve(
                    "${trusted.absolutePath}/ls",
                    listOf(trusted.absolutePath),
                    isWritable = systemOwned,
                ),
            )
            // 預設判定（宿主屬主可寫）同樣拒。
            assertNull(ShellExecutables.resolve("ls", listOf(trusted.absolutePath)))
        } finally {
            trusted.deleteRecursively()
            evilDir.deleteRecursively()
        }
    }

    @Test fun escape_zeroSpawnAtShell() {
        val trusted = tempDir("s3-escape")
        val evilDir = tempDir("s3-escape-evil")
        val scope = tempDir("s3-escape-scope")
        try {
            val evil = executable(evilDir, "payload")
            Files.createSymbolicLink(File(trusted, "ls").toPath(), evil.toPath())
            // 外層可寫、內層視為系統自帶：containment 拒，與 writability 無關。
            val evilRoot = evilDir.canonicalPath
            val outsideWritable: (java.nio.file.Path) -> Boolean = { p ->
                p.toString() == evil.canonicalPath || p.toString().startsWith("$evilRoot/")
            }
            for (argv in listOf(
                listOf("ls", "-l"),
                listOf("${trusted.absolutePath}/ls", "-l"),
            )) {
                val runner = CapRunner()
                val shell = RestrictedShell(
                    runner = runner,
                    privateRoot = scope.absolutePath,
                    execSearchDirs = listOf(trusted.absolutePath),
                    execIsWritable = outsideWritable,
                )
                val r = shell.execute(argv)
                assertTrue("$argv -> $r", r is ShellResult.Denied)
                assertEquals("$argv", ShellDeny.BLACKLISTED, (r as ShellResult.Denied).reason)
                assertEquals("$argv must not spawn", 0, runner.calls)
                assertEquals("$argv", null, runner.lastEnv)
            }
        } finally {
            trusted.deleteRecursively()
            evilDir.deleteRecursively()
            scope.deleteRecursively()
        }
    }

    // ---- S3 writability：集內但可寫即拒（containment 放行才輪到此閘） ----

    @Test fun writableTarget_rejectedByWritabilityGate() {
        val dir = tempDir("s3-writable")
        try {
            val target = executable(dir, "tool")
            Files.createSymbolicLink(File(dir, "ls").toPath(), target.toPath())
            val targetReal = target.canonicalPath
            val dirReal = dir.canonicalPath
            // 對照：全不可寫即放行（containment 本就通過，writability 不擋）。
            val ok = ShellExecutables.resolve("ls", listOf(dir.absolutePath), isWritable = systemOwned)!!
            assertEquals(targetReal, ok.path)
            // real 本體可寫 → 拒。
            assertNull(
                ShellExecutables.resolve(
                    "ls",
                    listOf(dir.absolutePath),
                    isWritable = { p -> p.toString() == targetReal },
                ),
            )
            // parent dir 可寫（本體自稱不可寫）→ 仍拒（rename 置換同等致命）。
            assertNull(
                ShellExecutables.resolve(
                    "ls",
                    listOf(dir.absolutePath),
                    isWritable = { p -> p.toString() == dirReal },
                ),
            )
            // 判定抛異常 → 視為可寫而拒（fail-closed）。
            assertNull(
                ShellExecutables.resolve(
                    "ls",
                    listOf(dir.absolutePath),
                    isWritable = { throw IllegalStateException("fs owner query blew up") },
                ),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun defaultWritabilityGate_liveOnHost() {
        // 存活證明：宿主暫存檔屬主可寫，預設判定必須拒（這正是其餘 fixture
        // 測試注入 systemOwned 的原因；裝置上系統自帶檔對 App 不可寫即放行）。
        val dir = tempDir("s3-live")
        try {
            val target = executable(dir, "tool")
            assertTrue(
                "test premise broken: temp file must be owner-writable on host",
                ShellExecutables.defaultIsWritable(target.toPath()),
            )
            assertNull(ShellExecutables.resolve("tool", listOf(dir.absolutePath)))
            assertNull(
                ShellExecutables.verifiedTarget(
                    target.absolutePath,
                    allowedRoots = listOf(dir.canonicalPath),
                ),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- S3 正常系統 symlink 形（/bin -> usr/bin 合併、同集內 shim）放行 ----

    @Test fun usrMergeShape_withinRoots_allowed() {
        val root = tempDir("s3-merge")
        try {
            val usrbin = File(root, "usrbin").apply { mkdir() }
            Files.createSymbolicLink(File(root, "bin").toPath(), usrbin.toPath())
            val tool = executable(usrbin, "echo")
            val dirs = listOf(File(root, "bin").absolutePath, usrbin.absolutePath)
            // bare：經 link 目錄命中，real 落第二根內 → 放行。
            val bare = ShellExecutables.resolve("echo", dirs, isWritable = systemOwned)!!
            assertEquals(tool.canonicalPath, bare.path)
            assertNull(bare.applet)
            // 絕對：link 路徑本身即 argv[0] → 同樣放行。
            val abs = ShellExecutables.resolve(
                "${File(root, "bin").absolutePath}/echo",
                dirs,
                isWritable = systemOwned,
            )!!
            assertEquals(tool.canonicalPath, abs.path)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun snapshotRealRoots_bestEffortAndBoundary() {
        val dir = tempDir("s3-snap")
        try {
            val snap = ShellExecutables.snapshotRealRoots(listOf(dir.absolutePath, "/nonexistent-xyz-123"))
            // 存在即 real 化（與 canonical 一致），去重後仍含系統實體前綴。
            assertTrue("snapshot=$snap", snap.contains(dir.canonicalPath))
            assertTrue("snapshot=$snap", snap.containsAll(ShellExecutables.SYSTEM_REAL_PREFIXES))
            // 邊界感知：同名前綴兄弟（/a/bc）不得沾 /a/b 的光。
            assertTrue(ShellExecutables.isUnderRoots("${dir.canonicalPath}/x", snap))
            assertFalse(ShellExecutables.isUnderRoots("${dir.canonicalPath}-sibling/x", snap))
            assertFalse(ShellExecutables.isUnderRoots("/tmp/s3-evil-payload", emptyList()))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- S3 × S2：multicall 不豁免 containment（逃逸照拒） ----

    @Test fun multicallEscape_rejected() {
        val bin = multicallDir()
        val evilDir = tempDir("s3-mcb-evil")
        try {
            // 集外放一個同名 toybox 形狀的 payable script，可信 dir 內以 applet
            // 名 link 過去：multicall 表不得把它救回來。
            val evilToybox = File(evilDir, "toybox")
            evilToybox.writeText("#!/bin/sh\necho pwned\n")
            assertTrue("setExecutable failed for $evilToybox", evilToybox.setExecutable(true))
            Files.delete(File(bin, "ls").toPath())
            Files.createSymbolicLink(File(bin, "ls").toPath(), evilToybox.toPath())
            assertNull(ShellExecutables.resolve("ls", listOf(bin.absolutePath), isWritable = systemOwned))
            assertNull(
                ShellExecutables.resolve(
                    "${bin.absolutePath}/ls",
                    listOf(bin.absolutePath),
                    isWritable = systemOwned,
                ),
            )
        } finally {
            bin.deleteRecursively()
            evilDir.deleteRecursively()
        }
    }
}
