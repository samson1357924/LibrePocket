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
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

/**
 * S1-A `file.edit` + `file.patch` 測試：註冊/投影（開關 `files`）+
 * 私有域冪等寫入 + patch 原子寫入與備份 + 跨域風味口徑 + SAF 直行。
 */
class FileEditTest {

    private val safTree = "/tree/primary:docs"

    private fun tempRoot(): File =
        Files.createTempDirectory("file-edit").toFile().apply { deleteOnExit() }

    private class FakeSaf : SafFileBridge {
        val files = mutableMapOf<String, ByteArray>()
        override fun exists(absolutePath: String): Boolean = files.containsKey(absolutePath)
        override fun read(absolutePath: String): ByteArray =
            files[absolutePath] ?: throw FileNotFoundException(absolutePath)
        override fun write(absolutePath: String, bytes: ByteArray) {
            files[absolutePath] = bytes.copyOf()
        }
        override fun list(absolutePrefix: String): List<String> =
            files.keys.filter { it == absolutePrefix || it.startsWith("$absolutePrefix/") }.sorted()
    }

    private fun executor(
        root: File,
        flavor: Flavor = Flavor.PLAY,
        bridgeGranted: Boolean = false,
        saf: SafFileBridge = MissingSafBridge(),
        enabled: Boolean = true,
    ) = FileEditExecutor(
        store = ScopedFileStore(root),
        privateRoot = root.absolutePath,
        safRoots = listOf(safTree),
        flavor = flavor,
        bridgeGranted = bridgeGranted,
        safBridge = saf,
        filesEnabled = enabled,
    )

    // ---- 註冊與投影 ----

    @Test fun toolsRegisteredAsWriteWithFilesSwitch() {
        for (name in listOf(FileEditTools.EDIT_NAME, FileEditTools.PATCH_NAME)) {
            val tool = ToolRegistry.find(name)!!
            assertEquals(name, SideEffect.WRITE, tool.sideEffect)
            assertEquals(name, FileEditTools.SWITCH, tool.annotations.requiresSwitch)
            assertEquals(name, true, tool.annotations.switchDefault)
            assertEquals(name, null, tool.annotations.requiresPermission)
            assertTrue(name, tool.description.isNotBlank())
            assertTrue(name, tool.annotations.fallbackHint.isNotBlank())
        }
        val ctx = ProjectionContext(flavor = Flavor.PLAY)
        val projected = ToolRegistry.projectAll(ctx)
        assertEquals(CapabilityLevel.NATIVE, projected[FileEditTools.EDIT_NAME]!!.level)
        assertEquals(CapabilityLevel.NATIVE, projected[FileEditTools.PATCH_NAME]!!.level)
    }

    @Test fun switchOffYieldsUserDisabled() {
        val ctx = ProjectionContext(
            flavor = Flavor.PLAY,
            userSwitches = mapOf(FileEditTools.SWITCH to false),
        )
        val projected = ToolRegistry.projectAll(ctx)
        for (name in listOf(FileEditTools.EDIT_NAME, FileEditTools.PATCH_NAME)) {
            assertEquals(name, CapabilityLevel.UNAVAILABLE, projected[name]!!.level)
            assertEquals(name, DenyReason.USER_DISABLED, projected[name]!!.reason)
        }
        // 執行器縱深：開關關閉同樣拒絕。
        val root = tempRoot()
        val result = executor(root, enabled = false).editText("a.txt", "x")
        assertFalse(result.ok)
        assertEquals(DenyReason.USER_DISABLED, result.reason)
        assertTrue(result.message.contains("USER_DISABLED"))
        assertTrue(result.message.contains("→"))
    }

    @Test fun privilegeGateNeedsNoConfirm() {
        for (name in listOf(FileEditTools.EDIT_NAME, FileEditTools.PATCH_NAME)) {
            val tool = ToolRegistry.find(name)!!
            assertTrue(
                name,
                PrivilegeGate.canExecute(tool, false, Projection(name, CapabilityLevel.NATIVE)),
            )
        }
    }

    // ---- 私有域寫入 ----

    @Test fun editCreateUpdateUnchanged() {
        val root = tempRoot()
        val ex = executor(root)
        val created = ex.editText("中文 筆記.txt", "hello")
        assertTrue(created.ok)
        assertEquals(FileWriteOutcome.Created, created.outcome)
        assertEquals(
            ScopedFileStore.sha256Hex("hello".toByteArray()),
            created.sha256Hex,
        )
        val mtime = File(root, "中文 筆記.txt").lastModified()
        val unchanged = ex.editText("中文 筆記.txt", "hello")
        assertEquals(FileWriteOutcome.Unchanged, unchanged.outcome)
        assertEquals(mtime, File(root, "中文 筆記.txt").lastModified())
        val updated = ex.editText("中文 筆記.txt", "hello2")
        assertEquals(FileWriteOutcome.Updated, updated.outcome)
        assertEquals("hello2", ScopedFileStore(root).read("中文 筆記.txt").toString(Charsets.UTF_8))
    }

    @Test fun editAbsolutePrivatePath() {
        val root = tempRoot()
        val abs = File(root, "sub/a.txt").absolutePath
        val result = executor(root).editText(abs, "v")
        assertTrue(result.message, result.ok)
        assertEquals("v", File(root, "sub/a.txt").readText())
    }

    @Test fun traversalRejected() {
        val root = tempRoot()
        for (bad in listOf("../escape.txt", "/abs-nope/x.txt", "a/../../escape.txt", "")) {
            val result = executor(root).editText(bad, "x")
            assertFalse(bad, result.ok)
        }
        // 歸一化後逃出私有域的絕對路徑同樣拒絕。
        val escaped = File(root, "../evil.txt").absolutePath
        val result = executor(root).editText(escaped, "x")
        assertFalse(result.ok)
        assertTrue(result.message.contains("FLAVOR_BLOCKED") || result.message.contains("NO_PRIVILEGE"))
    }

    // ---- 跨域口徑 ----

    @Test fun playCrossDomainFlavorBlocked() {
        val root = tempRoot()
        val result = executor(root, flavor = Flavor.PLAY).editText("/sdcard/Download/other/x.txt", "x")
        assertFalse(result.ok)
        assertEquals(DenyReason.FLAVOR_BLOCKED, result.reason)
        assertEquals(FileScope.CODE_CROSS_DOMAIN, result.detail)
        assertTrue(result.message.contains("FLAVOR_BLOCKED"))
        assertTrue(result.message.contains("→"))
    }

    @Test fun selfInstallCrossDomainNeedsBridge() {
        val root = tempRoot()
        for (flavor in listOf(Flavor.FOSS, Flavor.GITHUB)) {
            val result = executor(root, flavor = flavor).editText("/sdcard/Download/other/x.txt", "x")
            assertFalse(flavor.name, result.ok)
            assertEquals(flavor.name, DenyReason.NO_PRIVILEGE, result.reason)
            assertEquals(flavor.name, FileScope.CODE_NEEDS_BRIDGE, result.detail)
        }
    }

    @Test fun selfInstallCrossDomainStillDeniedWhenBridgeGranted() {
        // main 源集無 D09 提權橋實現：decide 雖放行（needsBridge），執行器仍誠實拒絕。
        val root = tempRoot()
        for (flavor in listOf(Flavor.FOSS, Flavor.GITHUB)) {
            val result = executor(root, flavor = flavor, bridgeGranted = true)
                .editText("/sdcard/Download/other/x.txt", "x")
            assertFalse(flavor.name, result.ok)
            assertEquals(flavor.name, DenyReason.NO_PRIVILEGE, result.reason)
            assertEquals(flavor.name, FileScope.CODE_NEEDS_BRIDGE, result.detail)
        }
    }

    // ---- SAF 直行 ----

    @Test fun safGrantedDirectWriteAndRead() {
        val root = tempRoot()
        val saf = FakeSaf()
        val ex = executor(root, saf = saf)
        val path = "$safTree/report.txt"
        val written = ex.editText(path, "saf-content")
        assertTrue(written.message, written.ok)
        assertEquals(FileWriteOutcome.SafWritten, written.outcome)
        assertEquals("saf-content", String(saf.read(path)))
        // 相對路徑不受影響：仍走私有域。
        assertTrue(ex.editText("local.txt", "p").ok)
        assertEquals("p", ScopedFileStore(root).read("local.txt").toString(Charsets.UTF_8))
    }

    // ---- patch ----

    @Test fun patchReplacesAndKeepsBackup() {
        val root = tempRoot()
        val ex = executor(root)
        assertTrue(ex.editText("a.txt", "hello world").ok)
        val patched = ex.patch("a.txt", "world", "there")
        assertTrue(patched.message, patched.ok)
        assertEquals(FileWriteOutcome.Updated, patched.outcome)
        assertEquals("a.txt.bak", patched.backupPath)
        assertEquals("hello there", ScopedFileStore(root).read("a.txt").toString(Charsets.UTF_8))
        assertEquals("hello world", ScopedFileStore(root).read("a.txt.bak").toString(Charsets.UTF_8))
    }

    @Test fun patchNoMatchLeavesFileUntouched() {
        val root = tempRoot()
        val ex = executor(root)
        assertTrue(ex.editText("a.txt", "hello").ok)
        val result = ex.patch("a.txt", "missing", "x")
        assertFalse(result.ok)
        assertEquals("NO_MATCH", result.detail)
        assertEquals("hello", ScopedFileStore(root).read("a.txt").toString(Charsets.UTF_8))
        assertFalse(File(root, "a.txt.bak").exists())
    }

    @Test fun patchMultiMatchRequiresSingleByDefault() {
        val root = tempRoot()
        val ex = executor(root)
        assertTrue(ex.editText("a.txt", "x-x-x").ok)
        val refused = ex.patch("a.txt", "x", "y")
        assertFalse(refused.ok)
        assertEquals("MULTI_MATCH", refused.detail)
        assertEquals("x-x-x", ScopedFileStore(root).read("a.txt").toString(Charsets.UTF_8))
        val replaced = ex.patch("a.txt", "x", "y", singleMatch = false)
        assertTrue(replaced.message, replaced.ok)
        assertEquals("y-y-y", ScopedFileStore(root).read("a.txt").toString(Charsets.UTF_8))
    }

    @Test fun patchMissingFileAndEmptyMatch() {
        val root = tempRoot()
        val ex = executor(root)
        assertEquals("NOT_FOUND", ex.patch("nope.txt", "a", "b").detail)
        assertTrue(ex.editText("a.txt", "v").ok)
        assertEquals("EMPTY_MATCH", ex.patch("a.txt", "", "b").detail)
    }

    @Test fun patchSafFileWithBackup() {
        val root = tempRoot()
        val saf = FakeSaf()
        val ex = executor(root, saf = saf)
        val path = "$safTree/note.txt"
        assertTrue(ex.editText(path, "v1 hello").ok)
        val patched = ex.patch(path, "hello", "bye")
        assertTrue(patched.message, patched.ok)
        assertEquals("$path.bak", patched.backupPath)
        assertEquals("v1 bye", String(saf.read(path)))
        assertEquals("v1 hello", String(saf.read("$path.bak")))
    }

    @Test fun patchCrossDomainDenied() {
        val root = tempRoot()
        val result = executor(root).patch("/sdcard/Download/other/x.txt", "a", "b")
        assertFalse(result.ok)
        assertEquals(DenyReason.FLAVOR_BLOCKED, result.reason)
    }
}
