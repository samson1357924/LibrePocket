package dev.librepocket.shell

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 5 P2/P3 回歸（main 源集）。
 *
 * - P2-2：[ShellExecTool.executeElevated] 已收斂為 internal，產品唯一入口為
 *   [dev.librepocket.router.ElevatedDispatch]（經 frozen 版）；本檔案以
 *   grep 式斷言鎖定 main 源集零直調（見 [soleEntryNoDirectToolCallsInMain])。
 * - P3-2：reason 孤立代理 fail-closed（[PrivilegeAuditLog.isReasonCodeTooLong]
 *   含 lone 拒；`"\uD800"` 與 `"?"` 同哈希碰撞不再可達門後）。
 * - P3-3：validate* bound-first（argc 超限零 join 即拒）。
 * - P3-4：直接通道自訂映射再驗證（可寫目標即拒，零 spawn）。
 */
class ShellStage5Test {

    // ---- P2-2：產品源集零直調（grep 式） ----

    @Test fun soleEntryNoDirectToolCallsInMain() {
        // 定位 main 源集（多路徑相容：module cwd／repo root／CI 差異；找不到即跳過）。
        val candidates = listOf(
            File("app/src/main/java"),
            File("src/main/java"),
            File("../app/src/main/java"),
            File("/mnt/240G_SSD/opencode_tmp/worktrees/fix-github-root-probe-runner/app/src/main/java"),
        )
        val root = candidates.firstOrNull { it.isDirectory } ?: return
        val hits = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
            // 僅掃產品源集（main），跳過定義檔本身；註解/KDoc 命中不計（僅計實際調用 `.executeElevated(`）。
            if (f.name == "ShellExecTool.kt") return@forEach
            val text = f.readText()
            // 去註解簡化：逐行剔除 `//` 尾註與 `*` KDoc 行，仍含調用即記。
            val codeLines = text.lines().filter { line ->
                val t = line.trim()
                !(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*"))
            }
            val code = codeLines.joinToString("\n")
            if (code.contains(".executeElevated(")) {
                hits.add(f.relativeTo(root).path)
            }
        }
        assertTrue("main 源集直調 executeElevated（繞 PrivilegeGate）：$hits", hits.isEmpty())
    }

    // ---- P3-2：reason 孤立代理 ----

    @Test fun loneSurrogateReasonDenied() {
        assertTrue(PrivilegeAuditLog.isReasonCodeTooLong("\uD800"))
        assertTrue(PrivilegeAuditLog.isReasonCodeTooLong("a\uD800b"))
        assertTrue(PrivilegeAuditLog.isReasonCodeTooLong("\uDC00"))
        assertFalse(PrivilegeAuditLog.isReasonCodeTooLong("?"))
        assertFalse(PrivilegeAuditLog.isReasonCodeTooLong("r".repeat(512)))
        assertTrue(PrivilegeAuditLog.hasLoneSurrogate("\uD800"))
        assertFalse(PrivilegeAuditLog.hasLoneSurrogate("?"))
        // 配對代理放行（正常增補字元 4B，不誤殺）。
        assertFalse(PrivilegeAuditLog.hasLoneSurrogate("\uD83D\uDE00"))
        assertFalse(PrivilegeAuditLog.isReasonCodeTooLong("\uD83D\uDE00"))
    }

    @Test fun loneSurrogateReasonHashesDistinctViaEmptyMarker() {
        // 直接哈希仍碰撞（替換語義保留，不承諾單射；見 encodeArgv 注記）。
        assertEquals(
            PrivilegeAuditLog.sha256Hex("\uD800"),
            PrivilegeAuditLog.sha256Hex("?"),
        )
        // 門後不進哈希：Tool 入口超限（含 lone）即记空串标记，与 "?" 哈希相异。
        val audit = PrivilegeAuditLog()
        val r = ShellExecTool.executeElevated(
            ElevatedRequest(listOf("id"), "\uD800"),
            DenyingElevatedRunner(),
            audit,
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (r as ShellResult.Denied).reason)
        assertEquals(1, audit.size())
        val row = audit.snapshot().single()
        assertEquals(PrivilegeAuditLog.sha256Hex(""), row.reasonHash)
        assertFalse(row.reasonHash == PrivilegeAuditLog.sha256Hex("?"))
    }

    // ---- P3-3：validate bound-first ----

    @Test fun oversizedArgcDeniedWithoutJoin() {
        // 65 > 64 即拒（訊息帶 argc bound），不做 joinToString。
        val over = List(65) { "a" }
        val v = ShellPolicy.validate(over, privateRoot = null)
        assertTrue("$v", v is Validation.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        assertTrue(v.message.contains("argc bound"))
        val ve = ShellPolicy.validateElevated(over, privateRoot = null)
        assertTrue("$ve", ve is Validation.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (ve as Validation.Denied).reason)
        // 巨大 size（謊報大、無背板）同樣零物化即拒：AbstractList 不物化元素。
        val giant = object : AbstractList<String>() {
            override val size: Int get() = 100_000
            override fun get(index: Int): String = throw AssertionError("must not touch elements (zero join)")
        }
        val start = System.currentTimeMillis()
        val vg = ShellPolicy.validate(giant, privateRoot = null)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("$vg", vg is Validation.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (vg as Validation.Denied).reason)
        assertTrue("bound-first must be fast, elapsed=$elapsed", elapsed < 2_000L)
    }

    // ---- P3-4：自訂映射再驗證 ----

    private class CapRunner : ProcessRunner {
        var calls = 0
        override fun run(argv: List<String>, timeoutMs: Long, env: Map<String, String>?): RawOutput {
            calls++
            return RawOutput("ok".toByteArray(), ByteArray(0), 0, false)
        }
    }

    @Test fun maliciousCustomResolverWritableTargetDeniedZeroSpawn() {
        val dir = Files.createTempDirectory("s5-direct-custom").toFile().canonicalFile
        try {
            val tool = File(dir, "ls").apply {
                writeText("#!/bin/sh\necho hi\n")
                assertTrue(setExecutable(true))
            }
            val target = tool.canonicalPath
            // containment 通過（允許集含本目錄），但目標本體可寫即拒。
            val runner = CapRunner()
            val shell = RestrictedShell(
                runner = runner,
                privateRoot = dir.absolutePath,
                execSearchDirs = listOf(dir.absolutePath),
                execAllowedRoots = listOf(dir.canonicalPath),
                execIsWritable = { p -> p.toString() == target },
                execResolver = { ShellExecutables.ResolvedExec(target, null) },
            )
            val r = shell.execute(listOf("ls"), timeoutMs = 5_000L)
            assertTrue("$r", r is ShellResult.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (r as ShellResult.Denied).reason)
            assertEquals("writable custom target must not spawn", 0, runner.calls)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun customResolverLinkEscapeDeniedZeroSpawn() {
        val dir = Files.createTempDirectory("s5-direct-link").toFile().canonicalFile
        val outside = Files.createTempDirectory("s5-direct-outside").toFile().canonicalFile
        try {
            val real = File(outside, "real-ls").apply {
                writeText("#!/bin/sh\necho hi\n")
                assertTrue(setExecutable(true))
            }
            val link = File(dir, "ls")
            Files.createSymbolicLink(link.toPath(), real.toPath())
            val runner = CapRunner()
            val shell = RestrictedShell(
                runner = runner,
                privateRoot = dir.absolutePath,
                execSearchDirs = listOf(dir.absolutePath),
                execAllowedRoots = listOf(dir.canonicalPath),
                execIsWritable = { _ -> false },
                execResolver = { ShellExecutables.ResolvedExec(link.canonicalPath, null) },
            )
            // link.canonicalPath 已解為 outside 實體 → OUTSIDE_ROOTS 即拒。
            val r = shell.execute(listOf("ls"), timeoutMs = 5_000L)
            assertTrue("$r", r is ShellResult.Denied)
            assertEquals(0, runner.calls)
        } finally {
            dir.deleteRecursively()
            outside.deleteRecursively()
        }
    }
}
