package dev.librepocket.shell

import dev.librepocket.tool.Flavor
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1 實體解析門 RED 測試（先紅後修）：
 * - 詞法域內但實體指到域外的 symlink 操作數必須拒絕（直接/提權/guest host-tree 同門）。
 * - F1：原始含 `..` 段即 fail-closed 拒（不先詞法折疊；spawn 用原始操作數故驗側嚴於用側）。
 * - spawn 前重驗：解析結果在驗證與 spawn 間被置換必須拒且零 spawn。
 * - hardlink 不測（僅 KDoc 揭露：本門不解 hardlink，同域 hardlink 指域外不在承諾內）。
 */
class ShellPhysicalGateTest {

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    @Test fun symlinkOutsideDomain_deniedDirect() {
        val scope = tempDir("b1-scope")
        val outside = tempDir("b1-outside")
        try {
            val victim = File(outside, "secret.txt").apply { writeText("secret") }
            val link = File(scope, "link.txt").toPath()
            Files.createSymbolicLink(link, victim.toPath())
            val v = ShellPolicy.validate(
                listOf("cat", link.toString()),
                scope.absolutePath,
            )
            assertTrue("symlink escape must be denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun symlinkOutsideDomain_deniedElevatedEvenWithBridge() {
        val scope = tempDir("b1-elev-scope")
        val outside = tempDir("b1-elev-outside")
        try {
            val victim = File(outside, "secret.txt").apply { writeText("secret") }
            val link = File(scope, "link.txt").toPath()
            Files.createSymbolicLink(link, victim.toPath())
            val v = ShellPolicy.validateElevated(
                listOf("cat", link.toString()),
                scope.absolutePath,
                flavor = Flavor.GITHUB,
                bridgeGranted = true,
            )
            assertTrue("elevated symlink escape must be denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun symlinkOutsideDomain_deniedGuestHostTree() {
        val scope = tempDir("b1-guest-scope")
        val outside = tempDir("b1-guest-outside")
        try {
            val victim = File(outside, "secret.txt").apply { writeText("secret") }
            val link = File(scope, "link.txt").toPath()
            Files.createSymbolicLink(link, victim.toPath())
            // guest host-tree 路徑（privateRoot 內詞法）同樣走實體門，不因 guest 放行。
            val v = ShellPolicy.validate(
                listOf("cat", link.toString()),
                scope.absolutePath,
                isGuest = true,
            )
            assertTrue("guest host-tree symlink escape must be denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun dotdotViaSymlinkedDir_deniedDirect() {
        // F1 反例：/scope/sub/../escape，sub→域外。詞法折疊後為 /scope/escape（域內），
        // 但 spawn 用原始操作數，核心先解 sub→域外再回 ..，實為域外，故必須拒。
        val scope = tempDir("b1-dotdot-scope")
        val outside = tempDir("b1-dotdot-outside")
        try {
            Files.createSymbolicLink(File(scope, "sub").toPath(), outside.toPath())
            val evil = scope.absolutePath + "/sub/../escape.txt"
            val v = ShellPolicy.validate(
                listOf("cat", evil),
                scope.absolutePath,
            )
            assertTrue("dotdot via symlinked dir must be denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
            assertTrue("message must carry reason code, got ${v.message}", v.message.contains("DOTDOT"))
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun linkSlashDotDot_deniedDirect() {
        // F1 反例：/scope/link/..，link→域外目錄。詞法折疊後為 /scope（域內），
        // 但實為域外父目錄，故必須拒。
        val scope = tempDir("b1-linkdotdot-scope")
        val outside = tempDir("b1-linkdotdot-outside")
        try {
            Files.createSymbolicLink(File(scope, "link").toPath(), outside.toPath())
            val evil = scope.absolutePath + "/link/.."
            val v = ShellPolicy.validate(
                listOf("ls", evil),
                scope.absolutePath,
            )
            assertTrue("link/.. must be denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
            assertTrue("message must carry reason code, got ${v.message}", v.message.contains("DOTDOT"))
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun plainDotdotInsideStillDenied() {
        // F1 正策反例：即使無 symlink、折疊後仍在域內，原始含 `..` 一律拒（驗側嚴於用側）。
        val scope = tempDir("b1-plaindotdot-scope")
        try {
            File(scope, "a").mkdirs()
            val evil = scope.absolutePath + "/a/../b.txt"
            val v = ShellPolicy.validate(
                listOf("cat", evil),
                scope.absolutePath,
            )
            assertTrue("plain dotdot must be denied fail-closed, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
            assertTrue("message must carry reason code, got ${v.message}", v.message.contains("DOTDOT"))
        } finally {
            scope.deleteRecursively()
        }
    }

    @Test fun normalInsideFile_allowedDirect() {
        // F1 正例：無 `..`、無 symlink 的域內實檔維持放行（不誤殺）。
        val scope = tempDir("b1-normal-scope")
        try {
            val inner = File(scope, "inner.txt").apply { writeText("ok") }
            val v = ShellPolicy.validate(
                listOf("cat", inner.absolutePath),
                scope.absolutePath,
            )
            assertTrue("normal inside file must be allowed, got $v", v is Validation.Allowed)
        } finally {
            scope.deleteRecursively()
        }
    }

    @Test fun dotdotViaSymlinkedDir_deniedElevated() {
        // F1 提權同門：即使橋接已授權，`..` 仍先拒（提權不放寬詞法）。
        val scope = tempDir("b1-dotdot-elev-scope")
        val outside = tempDir("b1-dotdot-elev-outside")
        try {
            Files.createSymbolicLink(File(scope, "sub").toPath(), outside.toPath())
            val evil = scope.absolutePath + "/sub/../escape.txt"
            val v = ShellPolicy.validateElevated(
                listOf("cat", evil),
                scope.absolutePath,
                flavor = Flavor.GITHUB,
                bridgeGranted = true,
            )
            assertTrue("elevated dotdot must be denied, got $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
            assertTrue("message must carry reason code, got ${v.message}", v.message.contains("DOTDOT"))
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun spawnReverify_mismatchDeniedWithoutSpawn() {
        val scope = tempDir("b1-reverify-scope")
        try {
            var calls = 0
            val runner = object : ProcessRunner {
                var spawnCalls = 0
                override fun run(argv: List<String>, timeoutMs: Long, env: Map<String, String>?): RawOutput {
                    spawnCalls++
                    return RawOutput("ok".toByteArray(), ByteArray(0), 0, false)
                }
            }
            // 首次解析回 A，spawn 前重驗回 B（模擬驗過→spawn 窗口置換）：必須拒且零 spawn。
            val shell = RestrictedShell(
                runner = runner,
                privateRoot = scope.absolutePath,
                execResolver = {
                    calls++
                    if (calls == 1) ShellExecutables.ResolvedExec("/trusted/A", null)
                    else ShellExecutables.ResolvedExec("/trusted/B", null)
                },
            )
            val r = shell.execute(listOf("echo", "hi"))
            assertTrue("reverify mismatch must be denied, got $r", r is ShellResult.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (r as ShellResult.Denied).reason)
            assertEquals(0, runner.spawnCalls)
        } finally {
            scope.deleteRecursively()
        }
    }
}
