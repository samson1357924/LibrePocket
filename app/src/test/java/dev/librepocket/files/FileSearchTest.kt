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
 * S1-A `file.search` 測試：註冊/投影 + 私有域檔名/內容檢索 +
 * ShellPolicy 路徑門 + SAF 授權樹檢索。
 */
class FileSearchTest {

    private val safTree = "/tree/primary:docs"

    private fun tempRoot(): File =
        Files.createTempDirectory("file-search").toFile().apply { deleteOnExit() }

    private class FakeSaf(val files: MutableMap<String, ByteArray> = mutableMapOf()) : SafFileBridge {
        override fun exists(absolutePath: String): Boolean = files.containsKey(absolutePath)
        override fun read(absolutePath: String): ByteArray =
            files[absolutePath] ?: throw FileNotFoundException(absolutePath)
        override fun write(absolutePath: String, bytes: ByteArray) {
            files[absolutePath] = bytes.copyOf()
        }
        override fun list(absolutePrefix: String): List<String> =
            files.keys.filter { it == absolutePrefix || it.startsWith("$absolutePrefix/") }.sorted()
    }

    private fun seed(root: File) {
        val store = ScopedFileStore(root)
        store.write("notes/todo.txt", "買牛奶\n開會 10 點\n買麵包".toByteArray())
        store.write("notes/中文 日誌.md", "今天學了中文剪貼簿\n心得：前台才可讀".toByteArray())
        store.write("code/main.kt", "fun main() {\n  println(\"hi\")\n}".toByteArray())
        store.write("bin/blob.dat", byteArrayOf(0, 1, 2, 3, 0))
    }

    private fun executor(
        root: File,
        flavor: Flavor = Flavor.PLAY,
        saf: SafFileBridge = MissingSafBridge(),
        enabled: Boolean = true,
        maxFileBytes: Int = FileSearchTools.MAX_FILE_BYTES,
    ) = FileSearchExecutor(
        store = ScopedFileStore(root),
        privateRoot = root.absolutePath,
        safRoots = listOf(safTree),
        flavor = flavor,
        safBridge = saf,
        filesEnabled = enabled,
        maxFileBytes = maxFileBytes,
    )

    // ---- 註冊與投影 ----

    @Test fun toolRegisteredAsReadWithFilesSwitch() {
        val tool = ToolRegistry.find(FileSearchTools.SEARCH_NAME)!!
        assertEquals(SideEffect.READ, tool.sideEffect)
        assertEquals(FileSearchTools.SWITCH, tool.annotations.requiresSwitch)
        assertEquals(true, tool.annotations.switchDefault)
        assertEquals(null, tool.annotations.requiresPermission)
        val projected = ToolRegistry.projectAll(ProjectionContext(flavor = Flavor.PLAY))
        assertEquals(CapabilityLevel.NATIVE, projected[FileSearchTools.SEARCH_NAME]!!.level)
    }

    @Test fun backgroundDoesNotAffectSearch() {
        // file.search 非前台限定：背景仍可搜私有域。
        val ctx = ProjectionContext(flavor = Flavor.PLAY, isForeground = false)
        assertEquals(
            CapabilityLevel.NATIVE,
            ToolRegistry.projectAll(ctx)[FileSearchTools.SEARCH_NAME]!!.level,
        )
    }

    @Test fun switchOffYieldsUserDisabled() {
        val ctx = ProjectionContext(
            flavor = Flavor.PLAY,
            userSwitches = mapOf(FileSearchTools.SWITCH to false),
        )
        val projected = ToolRegistry.projectAll(ctx)[FileSearchTools.SEARCH_NAME]!!
        assertEquals(CapabilityLevel.UNAVAILABLE, projected.level)
        assertEquals(DenyReason.USER_DISABLED, projected.reason)
        val result = executor(tempRoot(), enabled = false).search("x")
        assertFalse(result.ok)
        assertEquals(DenyReason.USER_DISABLED, result.reason)
    }

    @Test fun privilegeGateNeedsNoConfirm() {
        val tool = ToolRegistry.find(FileSearchTools.SEARCH_NAME)!!
        assertTrue(PrivilegeGate.canExecute(tool, false, Projection(tool.name, CapabilityLevel.NATIVE)))
    }

    // ---- 私有域檢索 ----

    @Test fun contentSearchFindsLines() {
        val root = tempRoot()
        seed(root)
        val result = executor(root).search("買")
        assertTrue(result.message, result.ok)
        assertEquals(2, result.hits.size)
        assertTrue(result.hits.all { it.path == "notes/todo.txt" })
        assertEquals(listOf(1, 3), result.hits.map { it.lineNumber })
        assertTrue(result.hits[0].snippet.contains("買牛奶"))
    }

    @Test fun fileNameHitHasNullLineNumber() {
        val root = tempRoot()
        seed(root)
        val result = executor(root).search("中文")
        assertTrue(result.message, result.ok)
        assertEquals(2, result.hits.size)
        val nameHit = result.hits.first { it.lineNumber == null }
        assertEquals("notes/中文 日誌.md", nameHit.path)
        val contentHit = result.hits.first { it.lineNumber != null }
        assertEquals("notes/中文 日誌.md", contentHit.path)
        assertEquals(1, contentHit.lineNumber)
    }

    @Test fun binarySkippedAndCounted() {
        val root = tempRoot()
        seed(root)
        val result = executor(root).search(" bears-no-match-xyz ")
        assertTrue(result.ok)
        assertTrue(result.hits.isEmpty())
        assertEquals(1, result.filesSkipped) // blob.dat
        assertEquals(3, result.filesSearched)
    }

    @Test fun oversizeFileSkipped() {
        val root = tempRoot()
        seed(root)
        ScopedFileStore(root).write("big.txt", "needle".toByteArray() + ByteArray(1024))
        val result = executor(root, maxFileBytes = 16).search("needle")
        assertTrue(result.ok)
        assertTrue(result.hits.isEmpty())
        assertTrue(result.filesSkipped >= 1)
    }

    @Test fun maxHitsTruncates() {
        val root = tempRoot()
        val store = ScopedFileStore(root)
        repeat(10) { i -> store.write("f$i.txt", "same same".toByteArray()) }
        val result = executor(root).search("same", maxHits = 5)
        assertTrue(result.ok)
        assertEquals(5, result.hits.size)
        assertTrue(result.truncated)
    }

    @Test fun scopedRootPrefix() {
        val root = tempRoot()
        seed(root)
        val result = executor(root).search("買", root = "code")
        assertTrue(result.ok)
        assertTrue(result.hits.isEmpty())
        val inNotes = executor(root).search("買", root = "notes")
        assertEquals(2, inNotes.hits.size)
    }

    @Test fun absolutePrivateRootAllowed() {
        val root = tempRoot()
        seed(root)
        val sub = File(root, "notes").absolutePath
        val result = executor(root).search("買", root = sub)
        assertTrue(result.message, result.ok)
        assertEquals(2, result.hits.size)
    }

    // ---- 門禁 ----

    @Test fun badQueryRejected() {
        val root = tempRoot()
        seed(root)
        assertEquals("BAD_QUERY", executor(root).search("").detail)
        assertEquals("BAD_QUERY", executor(root).search("a\nb").detail)
        assertEquals("BAD_QUERY", executor(root).search("a\u0000b").detail)
    }

    @Test fun traversalRootRejected() {
        val root = tempRoot()
        seed(root)
        val result = executor(root).search("買", root = "../escape")
        assertFalse(result.ok)
        assertEquals("BAD_PATH", result.detail)
    }

    @Test fun crossDomainRootPlayFlavorBlocked() {
        val root = tempRoot()
        seed(root)
        val result = executor(root, flavor = Flavor.PLAY).search("買", root = "/sdcard/Download/other")
        assertFalse(result.ok)
        assertEquals(DenyReason.FLAVOR_BLOCKED, result.reason)
        assertTrue(result.message.contains("FLAVOR_BLOCKED"))
        assertTrue(result.message.contains("→"))
    }

    @Test fun crossDomainRootSelfInstallNoPrivilege() {
        val root = tempRoot()
        seed(root)
        for (flavor in listOf(Flavor.FOSS, Flavor.GITHUB)) {
            val result = executor(root, flavor = flavor).search("買", root = "/sdcard/Download/other")
            assertFalse(flavor.name, result.ok)
            assertEquals(flavor.name, DenyReason.NO_PRIVILEGE, result.reason)
        }
    }

    // ---- SAF 檢索 ----

    @Test fun safTreeSearch() {
        val root = tempRoot()
        seed(root)
        val saf = FakeSaf(
            mutableMapOf(
                "$safTree/a.txt" to "授權樹內容\n第二行".toByteArray(),
                "$safTree/sub/b.txt" to "無關".toByteArray(),
            ),
        )
        val result = executor(root, saf = saf).search("授權樹", root = safTree)
        assertTrue(result.message, result.ok)
        assertEquals(1, result.hits.size)
        assertEquals("$safTree/a.txt", result.hits[0].path)
        assertEquals(1, result.hits[0].lineNumber)
        // 私有域不受 SAF 根影響：空根仍只搜私有域。
        val local = executor(root, saf = saf).search("授權樹")
        assertTrue(local.hits.isEmpty())
    }

    @Test fun safRootWithoutGrantDenied() {
        val root = tempRoot()
        seed(root)
        val result = executor(root).search("x", root = "/tree/other/dir")
        assertFalse(result.ok)
        assertEquals(DenyReason.FLAVOR_BLOCKED, result.reason)
    }

    @Test fun queryWithShellMetacharsIsLiteral() {
        // 內容掃描在行程內完成：特殊字元只是字面，不建子進程、不拒絕。
        val root = tempRoot()
        ScopedFileStore(root).write("a.txt", "price; rm -rf /".toByteArray())
        val result = executor(root).search("rm -rf /")
        assertTrue(result.message, result.ok)
        assertEquals(1, result.hits.size)
        assertEquals(1, result.hits[0].lineNumber)
    }
}
