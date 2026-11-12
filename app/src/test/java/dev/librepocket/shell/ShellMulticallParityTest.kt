package dev.librepocket.shell

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-3 Phase I P2-7 multicall parity（直接通道側）。
 *
 * parity 規則（與提權通道 [dev.librepocket.privilege.github.ElevatedExecPolicy]
 * 同形，見 `ElevatedMulticallParityTest`）：
 * - 別名形（`logicalBase != realBase`，如 `ls -> toybox`）：`resolve` 攜
 *   `applet = logicalBase`，spawn 為 `[real, applet, ...args]`；
 * - 主程式自身（`logicalBase == realBase`，如 `["toybox", "id"]` 的 `toybox`）：
 *   `resolve` 回 `applet = null`，spawn 為 `[real, ...args]`（`args` 首位即
 *   applet，不重插；否則形成 `[real, "toybox", "id"]` 錯誤形）。
 *
 * 安全同步（與提權同策）：dispatcher 可承載 `rm`／`sh` 等 applet，`argv[1]`
 * 同樣受 [ShellPolicy.DENIED_BINARIES] 約束（見 `multicallApplet_*`）；
 * 直接通道 `["toybox", "sh"]` 即使無此門亦會以 `NOT_WHITELISTED` 拒，
 * 此處顯式 `BLACKLISTED` 以與提權一致。
 *
 * TODO(#136-B3): B3 子命令 allowlist（`cmd`／`package` 類）在此擴充 applet
 * 位置審查；本測試只鎖黑名單 parity，不做 allowlist。
 */
class ShellMulticallParityTest {

    private class CapRunner : ProcessRunner {
        var calls: Int = 0
        var lastArgv: List<String>? = null
        var lastEnv: Map<String, String>? = null
        override fun run(argv: List<String>, timeoutMs: Long, env: Map<String, String>?): RawOutput {
            calls++
            lastArgv = argv
            lastEnv = env
            return RawOutput("ok".toByteArray(), ByteArray(0), 0, false)
        }
    }

    private val systemOwned: (java.nio.file.Path) -> Boolean = { _ -> false }

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun executable(dir: File, name: String, body: String = "#!/bin/sh\necho hi\n"): File {
        val f = File(dir, name)
        f.writeText(body)
        assertTrue("setExecutable failed for $f", f.setExecutable(true))
        return f
    }

    /** multicall fixture：`toybox`＋`busybox` 實體＋`ls/id` 別名 links，同目錄。 */
    private fun multicallDir(): File {
        val bin = tempDir("p27-mcb")
        executable(bin, "toybox")
        executable(bin, "busybox")
        Files.createSymbolicLink(File(bin, "ls").toPath(), File(bin, "toybox").toPath())
        Files.createSymbolicLink(File(bin, "id").toPath(), File(bin, "toybox").toPath())
        return bin
    }

    @Test fun resolve_aliasInsertsApplet_bareAndAbsolute() {
        val bin = multicallDir()
        try {
            val toyboxReal = File(bin, "toybox").canonicalPath
            // bare 別名：ls -> toybox。
            val bare = ShellExecutables.resolve("ls", listOf(bin.absolutePath), isWritable = systemOwned)!!
            assertEquals(toyboxReal, bare.path)
            assertEquals("ls", bare.applet)
            // 絕對別名：<bin>/ls -> toybox。
            val abs = ShellExecutables.resolve(
                "${bin.absolutePath}/ls",
                listOf(bin.absolutePath),
                isWritable = systemOwned,
            )!!
            assertEquals(toyboxReal, abs.path)
            assertEquals("ls", abs.applet)
            // busybox 別名對照：busybox 實體＋ls 別名指向 busybox 的變體。
            val busyBin = tempDir("p27-busy")
            try {
                executable(busyBin, "busybox")
                Files.createSymbolicLink(File(busyBin, "ls").toPath(), File(busyBin, "busybox").toPath())
                val busyReal = File(busyBin, "busybox").canonicalPath
                val busyBare = ShellExecutables.resolve("ls", listOf(busyBin.absolutePath), isWritable = systemOwned)!!
                assertEquals(busyReal, busyBare.path)
                assertEquals("ls", busyBare.applet)
            } finally {
                busyBin.deleteRecursively()
            }
        } finally {
            bin.deleteRecursively()
        }
    }

    @Test fun resolve_mainSelfDoesNotInsert_bareAndAbsolute() {
        val bin = multicallDir()
        try {
            val toyboxReal = File(bin, "toybox").canonicalPath
            val busyReal = File(bin, "busybox").canonicalPath
            // bare 主程式自身：不插。
            val toyBare = ShellExecutables.resolve("toybox", listOf(bin.absolutePath), isWritable = systemOwned)!!
            assertEquals(toyboxReal, toyBare.path)
            assertNull("toybox main-self must not insert", toyBare.applet)
            val busyBare = ShellExecutables.resolve("busybox", listOf(bin.absolutePath), isWritable = systemOwned)!!
            assertEquals(busyReal, busyBare.path)
            assertNull("busybox main-self must not insert", busyBare.applet)
            // 絕對主程式自身：不插。
            val toyAbs = ShellExecutables.resolve(
                "${bin.absolutePath}/toybox",
                listOf(bin.absolutePath),
                isWritable = systemOwned,
            )!!
            assertEquals(toyboxReal, toyAbs.path)
            assertNull("absolute toybox main-self must not insert", toyAbs.applet)
        } finally {
            bin.deleteRecursively()
        }
    }

    @Test fun restrictedShell_aliasSpawnShape_parityWithElevated() {
        val bin = multicallDir()
        val scope = tempDir("p27-scope")
        try {
            val toyboxReal = File(bin, "toybox").canonicalPath
            // 直接通道別名形全鏈：[real, applet, ...args]（與提權同形）。
            val runner = CapRunner()
            val shell = RestrictedShell(
                runner = runner,
                privateRoot = scope.absolutePath,
                execSearchDirs = listOf(bin.absolutePath),
                execIsWritable = systemOwned,
            )
            val r = shell.execute(listOf("ls", "-l"))
            assertTrue("$r", r is ShellResult.Ok)
            assertEquals(listOf(toyboxReal, "ls", "-l"), runner.lastArgv)
            assertEquals(ShellExecutables.CLEAN_ENV, runner.lastEnv)
        } finally {
            bin.deleteRecursively()
            scope.deleteRecursively()
        }
    }

    @Test fun restrictedShell_staleCustomApplet_overwrittenByWhitelist() {
        val scope = tempDir("p27-stale-scope")
        try {
            // 自訂映射不得注入未過審的 applet 名：回傳 "evil" 仍被白名單放行值覆寫。
            val runner = CapRunner()
            val shell = RestrictedShell(
                runner = runner,
                privateRoot = scope.absolutePath,
                execResolver = { raw ->
                    ShellExecutables.ResolvedExec("/trusted/$raw", "evil-should-be-ignored")
                },
            )
            val r = shell.execute(listOf("echo", "hi"))
            assertTrue("$r", r is ShellResult.Ok)
            // 非 multicall（real `echo` 不在表）不插入；重點是自帶字串未落地。
            assertTrue("stale applet must not land: ${runner.lastArgv}", runner.lastArgv!!.none { it == "evil-should-be-ignored" })
        } finally {
            scope.deleteRecursively()
        }
    }

    @Test fun multicallApplet_blacklisted_directAndElevated() {
        val scope = "/data/data/dev.librepocket.agent/files"
        // 直接通道：BLACKLISTED（優先於 NOT_WHITELISTED）且零 spawn。
        for (argv in listOf(
            listOf("toybox", "sh"),
            listOf("toybox", "rm"),
            listOf("toybox", "su"),
            listOf("busybox", "sh"),
            listOf("busybox", "rm"),
            listOf("toolbox", "bash"),
        )) {
            val v = ShellPolicy.validate(argv, scope)
            assertTrue("$argv -> $v", v is Validation.Denied)
            assertEquals("$argv", ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
            val runner = CapRunner()
            val shell = RestrictedShell(
                runner = runner,
                privateRoot = scope,
                execResolver = { ShellExecutables.ResolvedExec("/trusted/${it}", null) },
            )
            val r = shell.execute(argv)
            assertTrue("$argv -> $r", r is ShellResult.Denied)
            assertEquals("$argv", ShellDeny.BLACKLISTED, (r as ShellResult.Denied).reason)
            assertEquals("$argv must not spawn", 0, runner.calls)
        }
        // 提權通道同策：同 argv 同樣 BLACKLISTED（parity）。
        for (argv in listOf(
            listOf("toybox", "sh"),
            listOf("toybox", "rm"),
            listOf("busybox", "sh"),
        )) {
            val v = ShellPolicy.validateElevated(argv, scope, flavor = dev.librepocket.tool.Flavor.GITHUB, bridgeGranted = true)
            assertTrue("elevated $argv -> $v", v is Validation.Denied)
            assertEquals("elevated $argv", ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        }
        // 良性 applet 不誤殺（直接通道仍以 NOT_WHITELISTED 拒 toybox 本體，
        // 提權通道放行；重點是黑名單門不咬良性）。
        val directBenign = ShellPolicy.validate(listOf("toybox", "ls"), scope)
        assertTrue("$directBenign", directBenign is Validation.Denied)
        val elevatedBenign = ShellPolicy.validateElevated(
            listOf("toybox", "ls"),
            scope,
            flavor = dev.librepocket.tool.Flavor.GITHUB,
            bridgeGranted = true,
        )
        assertTrue("$elevatedBenign", elevatedBenign is Validation.Allowed)
    }

    @Test fun multicallApplet_absolutePathForm_alsoDenied() {
        val scope = "/data/data/dev.librepocket.agent/files"
        val argv = listOf("/system/bin/toybox", "sh")
        val direct = ShellPolicy.validate(argv, scope, trustedBinDirs = listOf("/system/bin"))
        assertTrue("$direct", direct is Validation.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (direct as Validation.Denied).reason)
        val elevated = ShellPolicy.validateElevated(
            argv,
            scope,
            flavor = dev.librepocket.tool.Flavor.GITHUB,
            bridgeGranted = true,
            trustedBinDirs = listOf("/system/bin"),
        )
        assertTrue("$elevated", elevated is Validation.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (elevated as Validation.Denied).reason)
    }
}
