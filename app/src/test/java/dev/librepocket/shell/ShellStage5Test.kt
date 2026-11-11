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
        )
        val root = candidates.firstOrNull { it.isDirectory } ?: return
        val hits = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
            // 僅掃產品源集（main），跳過定義檔本身；註解/KDoc 命中不計，
            // 字串字面量內命中亦不計（僅計程式碼區實際調用 `.executeElevated(`）。
            if (f.name == "ShellExecTool.kt") return@forEach
            if (codeContainsExecuteElevated(f.readText())) {
                hits.add(f.relativeTo(root).path)
            }
        }
        assertTrue("main 源集直調 executeElevated（繞 PrivilegeGate）：$hits", hits.isEmpty())
    }

    /**
     * 程式碼區調用探測（token 級，非正則 grep）：逐行掃描並追蹤區塊註解／
     * 行註解／字串（`"`、三引號、`'…'`）狀態，僅程式碼區的
     * `.executeElevated(` 才算命中，避免註解與字串字面量誤報。
     */
    private fun codeContainsExecuteElevated(text: String): Boolean {
        var inBlock = false
        var inTriple = false
        for (rawLine in text.lines()) {
            var i = 0
            var inStr = false
            val code = StringBuilder()
            while (i < rawLine.length) {
                if (inTriple) {
                    val end = rawLine.indexOf("\"\"\"", i)
                    if (end < 0) break
                    i = end + 3
                    inTriple = false
                    continue
                }
                if (inBlock) {
                    val end = rawLine.indexOf("*/", i)
                    if (end < 0) break
                    i = end + 2
                    inBlock = false
                    continue
                }
                val c = rawLine[i]
                if (inStr) {
                    if (c == '\\') {
                        i += 2
                        continue
                    }
                    if (c == '"') inStr = false
                    i++
                    continue
                }
                if (c == '"') {
                    if (rawLine.startsWith("\"\"\"", i)) {
                        inTriple = true
                        i += 3
                    } else {
                        inStr = true
                        i++
                    }
                    continue
                }
                if (c == '\'') {
                    // 字元字面量（如 `'/'`）：跳過至配對 `'`，避免其內 `/` 干擾註解判定。
                    i = if (i + 2 < rawLine.length && rawLine[i + 1] == '\\') {
                        if (i + 3 < rawLine.length) i + 4 else rawLine.length
                    } else {
                        val end = rawLine.indexOf('\'', i + 1)
                        if (end < 0) rawLine.length else end + 1
                    }
                    continue
                }
                if (c == '/' && i + 1 < rawLine.length) {
                    val n = rawLine[i + 1]
                    if (n == '/') break
                    if (n == '*') {
                        inBlock = true
                        i += 2
                        continue
                    }
                }
                code.append(c)
                i++
            }
            if (code.contains(".executeElevated(")) return true
        }
        return false
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

    // ---- Stage B (P2 length-first)：巨型 ASCII reason 粗門有界 ----

    @Test fun hugeAsciiReasonCoarseGateBoundedZeroSpawnNoHash() {
        // 8M ASCII 自校準比值 pin（取代單發 <15ms 絕對 SLA，避免 GC/調度誤紅）：
        // 粗門 `length > 512` 應 O(1) 即拒，不跑任何 Unicode 掃描。
        // - 修前舊碼先全掃 hasLoneSurrogate 再算 reasonUtf8Length（2 次 O(n)
        //   全掃）才 early-exit：gate ≈ 2× 單次參考掃描 → gateMedian*5 < scanMedian
        //   不成立（比值≈0.5，遠低於 5× 門檻），修前必紅；
        // - 修後粗門 O(1)：gateMedian（µs 級）<< scanMedian（ms 級），
        //   比值 >>5，在任何硬體皆大幅通過。
        // 交錯採樣 N=7（gate/參考掃描逐輪交替，GC/降頻同等影響兩側），取中位數
        // 抗單輪毛刺；另以 <1000ms 絕對上界僅防 hang（永不緊到誤紅）。
        // 運行時間：8M×7 參考觸碰 ≈56M char（<1s）＋ 7 次 O(1) gate。
        val huge = "x".repeat(8_000_000)
        val rounds = 7
        val gateNs = LongArray(rounds)
        val scanNs = LongArray(rounds)
        var gateHits = 0
        var scanSink = 0L
        repeat(rounds) { r ->
            val g0 = System.nanoTime()
            val gated = PrivilegeAuditLog.isReasonCodeTooLong(huge)
            val g1 = System.nanoTime()
            assertTrue("coarse gate must deny huge reason (round $r)", gated)
            gateNs[r] = g1 - g0
            if (gated) gateHits++
            // 參考全掃（摹舊演算法 O(n) 形態）：逐字觸碰並累加 checksum，
            // 使 JIT 無法消除迴圈；單次掃描即足（舊門 2 次掃描≈2× 本參考）。
            val s0 = System.nanoTime()
            var acc = 0
            for (i in huge.indices) {
                acc += huge[i].code
            }
            val s1 = System.nanoTime()
            scanNs[r] = s1 - s0
            scanSink += acc
        }
        assertEquals("coarse gate must deny on every round", rounds, gateHits)
        assertTrue("reference scan must observe input, sink=$scanSink", scanSink != 0L)
        gateNs.sort()
        scanNs.sort()
        val gateMedianNs = gateNs[rounds / 2]
        val scanMedianNs = scanNs[rounds / 2]
        val gateMaxMs = (gateNs.max() / 1_000_000.0)
        assertTrue(
            "coarse gate must stay bounded (hang pin), max=${gateMaxMs}ms",
            gateMaxMs < 1000.0,
        )
        assertTrue(
            "O(1) coarse gate must beat O(n) full scan by >=5x: " +
                "gateMedianNs=$gateMedianNs scanMedianNs=$scanMedianNs",
            gateMedianNs * 5 < scanMedianNs,
        )
        // 端到端：Tool 入口同樣先行門禁，零 spawn、零 snapshot worker，
        // 審計記空串標記（不哈希 8M 無界輸入）。
        var spawns = 0
        val runner = object : ElevatedShellRunner {
            override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult {
                spawns++
                return ShellResult.Ok("ok", "", 0, false)
            }
        }
        val audit = PrivilegeAuditLog()
        val r = ShellExecTool.executeElevated(
            ElevatedRequest(listOf("id"), huge),
            runner,
            audit,
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (r as ShellResult.Denied).reason)
        assertEquals("huge reason must not spawn", 0, spawns)
        assertEquals(1, audit.size())
        val row = audit.snapshot().single()
        assertEquals("DENY", row.verdict)
        assertEquals(PrivilegeAuditLog.argvHash(emptyList()), row.argvHash)
        assertEquals(PrivilegeAuditLog.sha256Hex(""), row.reasonHash)
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
        var lastArgv: List<String>? = null
        override fun run(argv: List<String>, timeoutMs: Long, env: Map<String, String>?): RawOutput {
            calls++
            lastArgv = argv.toList()
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

    // ---- 跨段 pin：自訂映射＋trimmed argv[0] 端到端 ----

    @Test fun customResolverSpacedArgvZeroSpawnsTrimmedAppletParity() {
        // gate 以 frozen[0].trim() 調自訂映射、自訂映射 applet 由白名單放行值重算：
        // "  ls  " 與 "ls" 端到端必須同一 spawn 形（[toybox 實體, "ls"]），
        // 零 spawn（拒）／滿 spawn（放行）行為一致。
        val dir = Files.createTempDirectory("s5-direct-trim").toFile().canonicalFile
        try {
            val toy = File(dir, "toybox").apply {
                writeText("#!/bin/sh\necho hi\n")
                assertTrue(setExecutable(true))
            }
            val toyReal = toy.canonicalPath
            val seenPlain = mutableListOf<String>()
            val seenSpaced = mutableListOf<String>()
            val plainRunner = CapRunner()
            val spacedRunner = CapRunner()
            fun shellFor(runner: CapRunner, seen: MutableList<String>) = RestrictedShell(
                runner = runner,
                privateRoot = dir.absolutePath,
                execSearchDirs = listOf(dir.absolutePath),
                execAllowedRoots = listOf(dir.canonicalPath),
                execIsWritable = { _ -> false },
                // 惡意映射攜污染 applet：生產以白名單放行值重算，不採信此字串。
                execResolver = { name ->
                    seen += name
                    ShellExecutables.ResolvedExec(toyReal, "  ls  ")
                },
            )
            val rPlain = shellFor(plainRunner, seenPlain).execute(listOf("ls"), timeoutMs = 5_000L)
            assertTrue("$rPlain", rPlain is ShellResult.Ok)
            val rSpaced = shellFor(spacedRunner, seenSpaced).execute(listOf("  ls  "), timeoutMs = 5_000L)
            assertTrue("$rSpaced", rSpaced is ShellResult.Ok)
            // trim 發生於調 resolver 之前：gate＋reverify 所見全為 "ls"。
            assertTrue("resolver must be consulted", seenPlain.isNotEmpty())
            assertEquals("resolver path must match", seenPlain.size, seenSpaced.size)
            assertTrue("resolver must only see trimmed name, got $seenSpaced", seenSpaced.all { it == "ls" })
            // spawn 形 parity：污染 applet 被放行值覆寫，首元為驗證後實體。
            assertEquals(1, plainRunner.calls)
            assertEquals(1, spacedRunner.calls)
            assertEquals(listOf(toyReal, "ls"), plainRunner.lastArgv)
            assertEquals(plainRunner.lastArgv, spacedRunner.lastArgv)
            assertEquals("ls", spacedRunner.lastArgv!![1])
            assertTrue(
                "no untrimmed argv[0] may reach spawn",
                spacedRunner.lastArgv!!.none { it.contains("  ") },
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
