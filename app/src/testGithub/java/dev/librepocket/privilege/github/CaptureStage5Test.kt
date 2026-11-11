package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.PrivilegeAuditLog
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellExecutables
import dev.librepocket.shell.ShellResult
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 5 P2-1／P3-1 回歸（github 風味）。
 *
 * - P2-1：capture 路徑 reason 長度門 fail-closed（1024B 即 null＋DENY＋zero spawn，
 *   不抛、不哈希無界輸入；binary／String 四路徑同門）。
 * - P3-1：toSpawnArgv 空白 logicalBase 修剪（`["  ls  "]` 驗為 `ls` 且 spawn
 *   `[real,ls,…]`，不帶空格 applet）。
 */
class CaptureStage5Test {

    private fun countingBinary(spawns: IntArray): BinaryElevatedRunner =
        object : BinaryElevatedRunner {
            override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult {
                spawns[0]++
                return ShellResult.Ok("unused", "", 0, false)
            }

            override fun runBinary(request: ElevatedRequest, timeoutMs: Long): ElevatedBinaryResult {
                spawns[0]++
                return ElevatedBinaryResult.Denied(ShellDeny.NOT_WHITELISTED, "should not reach")
            }
        }

    @Test fun captureReasonTooLongDeniedZeroSpawn() {
        val longReason = "n".repeat(1024)
        assertTrue(PrivilegeAuditLog.isReasonCodeTooLong(longReason))
        // binary 雙路徑。
        for (via in listOf("screencap", "fb0")) {
            val audit = PrivilegeAuditLog()
            val spawns = intArrayOf(0)
            val runner = countingBinary(spawns)
            val read = if (via == "screencap") {
                RootFramebufferCapture.screencapViaBinary(runner, audit, longReason)
            } else {
                RootFramebufferCapture.fb0ViaBinary(runner, audit, longReason)
            }
            assertNull("$via long reason must yield null frame", read())
            assertEquals("$via must not spawn", 0, spawns[0])
            assertEquals(1, audit.size())
            assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
            val row = audit.snapshot().single()
            assertEquals(PrivilegeAuditLog.sha256Hex(""), row.reasonHash)
        }
        // String 回落雙路徑（Deprecated 通用版，文字 runner）。
        for (via in listOf("screencap", "fb0")) {
            val audit = PrivilegeAuditLog()
            var spawns = 0
            val runner = object : dev.librepocket.shell.ElevatedShellRunner {
                override fun run(
                    request: ElevatedRequest,
                    timeoutMs: Long,
                ): ShellResult {
                    spawns++
                    return ShellResult.Ok("ok", "", 0, false)
                }
            }
            @Suppress("DEPRECATION")
            val read = if (via == "screencap") {
                RootFramebufferCapture.screencapViaElevated(runner, audit, longReason)
            } else {
                RootFramebufferCapture.fb0ViaElevated(runner, audit, longReason)
            }
            assertNull(read())
            assertEquals(0, spawns)
            assertEquals(1, audit.size())
            assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
        }
    }

    @Test fun captureReasonLoneSurrogateDeniedZeroSpawn() {
        val audit = PrivilegeAuditLog()
        val spawns = intArrayOf(0)
        val runner = countingBinary(spawns)
        assertNull(RootFramebufferCapture.screencapViaBinary(runner, audit, "\uD800")())
        assertEquals(0, spawns[0])
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }

    @Test fun toSpawnArgvTrimsSpacedLogicalBase() {
        val bin = Files.createTempDirectory("s5-spaced").toFile()
        try {
            val toybox = File(bin, "toybox").apply {
                writeText("#!/bin/sh\necho hi\n")
                assertTrue(setExecutable(true))
            }
            Files.createSymbolicLink(File(bin, "ls").toPath(), toybox.toPath())
            val toyboxReal = toybox.canonicalPath
            val roots = listOf(bin.canonicalPath)
            val systemOwned: (java.nio.file.Path) -> Boolean = { _ -> false }
            val exec = (ElevatedExecPolicy.resolveForSnapshot(
                "ls", listOf(bin.absolutePath), roots, systemOwned,
            ) as ShellExecutables.ResolveOutcome.Ok).exec
            // 空白 argv[0]：驗證側 trim 後為 ls，spawn 側須同口徑去空格。
            val snap = ElevatedExecPolicy.snapshot(ElevatedRequest(listOf("  ls  ", "-l"), "P31"))!!
            val spawn = ElevatedExecPolicy.toSpawnArgv(snap, exec)
            assertEquals(listOf(toyboxReal, "ls", "-l"), spawn)
            // 主程式自身空白同理不插。
            val mainExec = (ElevatedExecPolicy.resolveForSnapshot(
                "toybox", listOf(bin.absolutePath), roots, systemOwned,
            ) as ShellExecutables.ResolveOutcome.Ok).exec
            val mainSnap = ElevatedExecPolicy.snapshot(ElevatedRequest(listOf("  toybox  ", "id"), "P31"))!!
            assertEquals(
                listOf(toyboxReal, "id"),
                ElevatedExecPolicy.toSpawnArgv(mainSnap, mainExec),
            )
        } finally {
            bin.deleteRecursively()
        }
    }
}
