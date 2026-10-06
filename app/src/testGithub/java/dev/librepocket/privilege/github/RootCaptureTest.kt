package dev.librepocket.privilege.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3 Root 截圖測試（BACKLOG D09）：screencap 優先、fb0 兜底、
 * 僅摘要進轉錄（無像素欄位、相同位元組同摘要）。
 */
class RootCaptureTest {

    @Test fun screencapPreferredOverFb0() {
        val capture = RootFramebufferCapture(
            readScreencap = { "png-bytes".toByteArray() },
            readFb0 = { "fb-bytes".toByteArray() },
        )
        val digest = capture.capture()!!
        assertEquals(FrameSource.SCREENCAP, digest.source)
        assertEquals("png-bytes".toByteArray().size, digest.byteSize)
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
        val a = RootFramebufferCapture({ "same".toByteArray() }, { null }).capture()!!
        val b = RootFramebufferCapture({ "same".toByteArray() }, { null }).capture()!!
        assertEquals(a.digest, b.digest)
        val c = RootFramebufferCapture({ "different".toByteArray() }, { null }).capture()!!
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
}
