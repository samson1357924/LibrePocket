package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ElevatedShellRunner
import dev.librepocket.shell.PrivilegeAuditLog
import dev.librepocket.shell.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * S3 Root 截圖測試（BACKLOG D09）：screencap 優先、fb0 兜底、
 * 僅摘要進轉錄（無像素欄位、相同位元組同摘要）。
 */
class RootCaptureTest {

    /**
     * 終審殘留隔離（Phase 1 P1-1）：每用例首尾歸零 consecutive＋輪詢收斂
     * outstanding/active，避免同 JVM 內熔斷污染後測。
     * （測試隔離重置，非#136-B3修復；不升級 noteClean 可見性。）
     */
    @Before fun setUpBreakerIsolation() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
    }

    @After fun tearDownBreakerIsolation() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
    }

    private fun pollOutstandingTo(expected: Int, boundMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + boundMs
        while (ElevatedCleanupTracker.outstanding() != expected) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError(
                    "outstanding=${ElevatedCleanupTracker.outstanding()} expected=$expected",
                )
            }
            Thread.sleep(50L)
        }
    }

    private fun pollActiveTo(expected: Int, boundMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + boundMs
        while (ElevatedCleanupTracker.activeCalls() != expected) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError(
                    "active=${ElevatedCleanupTracker.activeCalls()} expected=$expected",
                )
            }
            Thread.sleep(20L)
        }
    }

    /**
     * 合法 PNG fixture：sig＋fill＋IEND。
     * 輕量門代理（sig＋IEND），非解碼保證：僅證 isPlausiblePng 門通過，
     * 不證圖片可解碼（真解碼歸 Phase 2-5，不在此斷）。
     */

    private fun validPng(fill: ByteArray = "png-bytes".toByteArray()): ByteArray {
        val sig = RootFramebufferCapture.PNG_SIGNATURE
        val iend = byteArrayOf(
            0x00, 0x00, 0x00, 0x00,
            0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
        return sig + fill + iend
    }

    @Test fun screencapPreferredOverFb0() {
        val png = validPng()
        val capture = RootFramebufferCapture(
            readScreencap = { png },
            readFb0 = { "fb-bytes".toByteArray() },
        )
        val digest = capture.capture()!!
        assertEquals(FrameSource.SCREENCAP, digest.source)
        assertEquals(png.size, digest.byteSize)
        assertEquals(64, digest.digest.length)
    }

    @Test fun fb0FallbackWhenScreencapMissing() {
        val capture = RootFramebufferCapture(
            readScreencap = { null },
            readFb0 = { "fb-bytes".toByteArray() },
        )
        val digest = capture.capture()!!
        assertEquals(FrameSource.FB0, digest.source)
    }

    @Test fun bothMissingYieldsNullForManualFallback() {
        val capture = RootFramebufferCapture(
            readScreencap = { null },
            readFb0 = { ByteArray(0) },
        )
        assertNull(capture.capture())
    }

    @Test fun sameBytesSameDigestWithoutPixelFields() {
        // B4：capture() screencap 側須過 PNG 輕量門，故用合法 PNG（同位元組同摘要）。
        val same = validPng("same".toByteArray())
        val diff = validPng("different".toByteArray())
        val a = RootFramebufferCapture({ same }, { null }).capture()!!
        val b = RootFramebufferCapture({ same }, { null }).capture()!!
        assertEquals(a.digest, b.digest)
        val c = RootFramebufferCapture({ diff }, { null }).capture()!!
        assertTrue(a.digest != c.digest)
        // 僅摘要進轉錄：FrameDigest 無像素欄位（編譯期保證；此處再斷言一次）。
        val fieldNames = FrameDigest::class.java.declaredFields.map { it.name }.toSet()
        assertTrue(
            "fields=$fieldNames",
            fieldNames.containsAll(setOf("digest", "source", "byteSize", "atMs")),
        )
        assertTrue(
            "fields=$fieldNames",
            fieldNames.none { it == "pixels" || it == "raw" || it == "bytes" || it == "bitmap" },
        )
    }

    @Suppress("DEPRECATION")
    @Test fun gatedReadersRefuseWithoutReasonCodeAndAuditDenial() {
        // D09 門禁：無 reasonCode 時 runner 拒絕，讀取回 null 且寫 DENY 審計。
        val audit = dev.librepocket.shell.PrivilegeAuditLog()
        val deny = dev.librepocket.shell.DenyingElevatedRunner()
        val fb0 = RootFramebufferCapture.fb0ViaElevated(deny, audit, reasonCode = "")
        assertNull(fb0())
        val screen = RootFramebufferCapture.screencapViaElevated(deny, audit, reasonCode = "")
        assertNull(screen())
        assertEquals(2, audit.size())
        assertEquals(mapOf("DENY" to 2), audit.countByVerdict())
    }

    @Suppress("DEPRECATION")
    @Test fun truncatedOkYieldsNullWithoutSummarizingPartialFrame() {
        // 終審 MAJOR：Ok.truncated 部分幀不可摘要，必須回 null（呼叫方降級手動截圖）；
        // 審計仍記 verdict（OK），只清單元行為（binary + string 雙路徑）。
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x01)

        // 二進位路徑：screencap + fb0 皆 null。
        val binaryAudit = PrivilegeAuditLog()
        val binaryRunner = TruncatedBinaryRunner(png)
        assertNull(RootFramebufferCapture.screencapViaBinary(binaryRunner, binaryAudit, "T")())
        assertNull(RootFramebufferCapture.fb0ViaBinary(binaryRunner, binaryAudit, "T")())
        assertEquals(mapOf("OK" to 2), binaryAudit.countByVerdict())

        // String 回落路徑：同樣 truncated 即 null（審計仍記 OK）。
        val stringAudit = PrivilegeAuditLog()
        val stringRunner = TruncatedStringRunner()
        assertNull(RootFramebufferCapture.screencapViaElevated(stringRunner, stringAudit, "T")())
        assertNull(RootFramebufferCapture.fb0ViaElevated(stringRunner, stringAudit, "T")())
        assertEquals(mapOf("OK" to 2), stringAudit.countByVerdict())
    }

    @Test fun binaryOverloadBypassesStringFallback() {
        // R3-B：Binary 多載直接走位元組直達（無 as?）；run() 蓄意回損毀文字，
        // 若誤走 String 路徑即回 "lossy-garbage" 而非 PNG，位元組比對即抓包。
        // B4：screencap 側須過 PNG 輕量門，故用合法 PNG。
        val png = validPng(byteArrayOf(0xFF.toByte(), 0x00, 0x01))
        val audit = PrivilegeAuditLog()
        val runner: BinaryElevatedRunner = object : BinaryElevatedRunner {
            override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult =
                ShellResult.Ok("lossy-garbage", "", 0, false)
            override fun runBinary(request: ElevatedRequest, timeoutMs: Long): ElevatedBinaryResult =
                ElevatedBinaryResult.Ok(png, ByteArray(0), 0, false)
        }
        val screen = RootFramebufferCapture.screencapViaElevated(runner, audit, "T")()
        assertTrue(screen!!.contentEquals(png))
        val fb = RootFramebufferCapture.fb0ViaElevated(runner, audit, "T")()
        assertTrue(fb!!.contentEquals(png))
        assertEquals(mapOf("OK" to 2), audit.countByVerdict())
    }

    private class TruncatedBinaryRunner(
        private val bytes: ByteArray,
    ) : BinaryElevatedRunner {
        override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult =
            ShellResult.Ok("unused", "", 0, false)

        override fun runBinary(request: ElevatedRequest, timeoutMs: Long): ElevatedBinaryResult =
            ElevatedBinaryResult.Ok(bytes, ByteArray(0), 0, true)
    }

    private class TruncatedStringRunner : ElevatedShellRunner {
        override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult =
            ShellResult.Ok("partial-png", "", 0, true)
    }
}
