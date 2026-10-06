package dev.librepocket.files

import dev.librepocket.router.PrivilegeGate
import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.Projection
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.SideEffect
import dev.librepocket.tool.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files

/**
 * S1-A `file.attach` 測試：註冊/投影 + MIME 白名單 + 暫存上限與落盤。
 */
class FileAttachTest {

    private class FakeSource(
        private val name: String?,
        private val mime: String?,
        private val declaredSize: Long?,
        private val bytes: ByteArray,
    ) : AttachmentSource {
        override fun displayName(): String? = name
        override fun mimeType(): String? = mime
        override fun sizeBytes(): Long? = declaredSize
        override fun openInputStream(): InputStream = ByteArrayInputStream(bytes)
    }

    private fun stager(enabled: Boolean = true): Pair<AttachmentStager, ScopedFileStore> {
        val root = Files.createTempDirectory("file-attach").toFile().apply { deleteOnExit() }
        val store = ScopedFileStore(root)
        return AttachmentStager(store, filesEnabled = enabled) to store
    }

    // ---- 註冊與投影 ----

    @Test fun toolRegisteredAsReadWithFilesSwitch() {
        val tool = ToolRegistry.find(FileAttachTools.ATTACH_NAME)!!
        assertEquals(SideEffect.READ, tool.sideEffect)
        assertEquals(FileAttachTools.SWITCH, tool.annotations.requiresSwitch)
        assertEquals(true, tool.annotations.switchDefault)
        assertEquals(null, tool.annotations.requiresPermission)
        val projected = ToolRegistry.projectAll(ProjectionContext(flavor = Flavor.PLAY))
        assertEquals(CapabilityLevel.NATIVE, projected[FileAttachTools.ATTACH_NAME]!!.level)
    }

    @Test fun privilegeGateNeedsNoConfirm() {
        val tool = ToolRegistry.find(FileAttachTools.ATTACH_NAME)!!
        assertTrue(PrivilegeGate.canExecute(tool, false, Projection(tool.name, CapabilityLevel.NATIVE)))
    }

    // ---- MIME ----

    @Test fun mimeAllowlist() {
        assertTrue(FileAttachTools.mimeAllowed("image/png", listOf("image/*")))
        assertTrue(FileAttachTools.mimeAllowed("image/jpeg; charset=binary", listOf("image/*")))
        assertFalse(FileAttachTools.mimeAllowed("application/pdf", listOf("image/*")))
        assertTrue(FileAttachTools.mimeAllowed("application/pdf", listOf("image/*", "application/pdf")))
        assertTrue(FileAttachTools.mimeAllowed("anything/x", listOf("*/*")))
        assertTrue(FileAttachTools.mimeAllowed(null, listOf("image/*")))
    }

    // ---- 暫存 ----

    @Test fun stageImageRoundTrip() {
        val (stager, store) = stager()
        val bytes = "fake-png-bytes".toByteArray()
        val result = stager.stage(FakeSource("照片.png", "image/png", bytes.size.toLong(), bytes))
        assertTrue(result.message, result.ok)
        val attachment = result.attachment!!
        assertTrue(attachment.relativePath.startsWith("${FileAttachTools.STAGING_DIR}/"))
        assertTrue(attachment.relativePath.endsWith("照片.png"))
        assertEquals("image/png", attachment.mimeType)
        assertEquals(bytes.size.toLong(), attachment.sizeBytes)
        assertEquals(ScopedFileStore.sha256Hex(bytes), attachment.sha256Hex)
        assertEquals(bytes.toList(), store.read(attachment.relativePath).toList())
    }

    @Test fun disallowedMimeRejected() {
        val (stager, _) = stager()
        val bytes = "%PDF".toByteArray()
        val result = stager.stage(FakeSource("a.pdf", "application/pdf", 4, bytes))
        assertFalse(result.ok)
        assertEquals("MIME_NOT_ALLOWED", result.detail)
    }

    @Test fun declaredOversizeRejected() {
        val (stager, _) = stager()
        val result = stager.stage(
            FakeSource("big.png", "image/png", FileAttachTools.MAX_SIZE_BYTES + 1, ByteArray(4)),
            maxSizeBytes = FileAttachTools.MAX_SIZE_BYTES,
        )
        assertFalse(result.ok)
        assertEquals("FILE_TOO_LARGE", result.detail)
    }

    @Test fun actualOversizeRejectedWhenSizeUnknown() {
        val (stager, _) = stager()
        val result = stager.stage(
            FakeSource("big.png", "image/png", null, ByteArray(64)),
            maxSizeBytes = 16,
        )
        assertFalse(result.ok)
        assertEquals("FILE_TOO_LARGE", result.detail)
    }

    @Test fun unsafeNameFallsBack() {
        val (stager, store) = stager()
        val bytes = "x".toByteArray()
        // ".." 單段非法 → 回退固定名。
        val fallback = stager.stage(FakeSource("..", "image/png", 1, bytes))
        assertTrue(fallback.message, fallback.ok)
        assertTrue(fallback.attachment!!.relativePath.endsWith("attachment"))
        // "../evil.png" 取 basename 後為合法名：遍歷被中和，不逃出暫存目錄。
        val neutralized = stager.stage(FakeSource("../evil.png", "image/png", 1, bytes))
        assertTrue(neutralized.message, neutralized.ok)
        assertTrue(neutralized.attachment!!.relativePath.endsWith("evil.png"))
        assertFalse(neutralized.attachment.relativePath.contains(".."))
        assertEquals(bytes.toList(), store.read(neutralized.attachment.relativePath).toList())
    }

    @Test fun switchOffDeniedUserDisabled() {
        val (stager, _) = stager(enabled = false)
        val result = stager.stage(FakeSource("a.png", "image/png", 1, "x".toByteArray()))
        assertFalse(result.ok)
        assertEquals(DenyReason.USER_DISABLED, result.reason)
        assertTrue(result.message.contains("USER_DISABLED"))
    }

    @Test fun entryPointsDocumentedWithoutUiChange() {
        // Photo Picker / SAF 入口常數存在，供後續 ChatScreen 切面接線。
        assertTrue(AttachEntryPoints.PHOTO_PICKER_CONTRACT.contains("PickVisualMedia"))
        assertTrue(AttachEntryPoints.SAF_OPEN_DOCUMENT_CONTRACT.contains("OpenDocument"))
        assertEquals("android.intent.action.OPEN_DOCUMENT", AttachEntryPoints.SAF_ACTION_OPEN_DOCUMENT)
    }
}
