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
 * - 操作數 schema（`--opt=value`、短旗標合併、值槽、pattern 槽）維持既有嚴格語義；
 * - null 作用域的隱式 cwd bare fail-closed，且放行者 spawn 仍是固定路徑 + 乾淨 env；
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
                execResolver = { resolves++; "/should-never-be-used/$it" },
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
                execResolver = { ShellExecutables.resolve(it, listOf(trusted.absolutePath)) },
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
            assertEquals(real.canonicalPath, ShellExecutables.verifiedTarget(link.toString()))
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
                    ShellExecutables.resolve("mybin", listOf(emptyDir.absolutePath, okDir.absolutePath)),
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
            val stub: (String) -> String? = { raw ->
                if ('/' in raw || '\\' in raw) null else "/trusted/$raw"
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
        val stub: (String) -> String? = { resolves++; "/trusted/$it" }
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
                execResolver = { "/trusted/$it" },
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
                execResolver = { if (failNext) null else "/trusted/$it" },
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
}
