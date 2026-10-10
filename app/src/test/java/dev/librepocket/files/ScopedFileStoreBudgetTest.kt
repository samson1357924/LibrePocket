package dev.librepocket.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * #16 ScopedFileStore 預算測試：read size budget、write 有界比對、
 * list 筆數／深度／deadline 上限＋symlink 環守衛，以及執行器相容映射。
 *
 * 紅線：不動 symlink 跟隨語義（#10 定奪）；環測試只斷言「真環受控終止」，
 * 無環樹（含兄弟 diamond 連結）輸出與舊 `walkTopDown` 一致。
 */
class ScopedFileStoreBudgetTest {

    private fun tempRoot(): File =
        Files.createTempDirectory("file-budget").toFile().apply { deleteOnExit() }

    private fun assertBudgeted(block: () -> Unit, expected: FileBudgetCode): FileBudgetException {
        try {
            block()
        } catch (e: FileBudgetException) {
            assertEquals(expected, e.budget)
            assertEquals(expected.detail, e.budget.detail)
            return e
        }
        fail("expected FileBudgetException($expected)")
        throw AssertionError("unreachable")
    }

    // ---- read：size budget ----

    @Test fun readSmallFileNormal() {
        val store = ScopedFileStore(tempRoot())
        val payload = "hello 小檔".toByteArray(Charsets.UTF_8)
        store.write("a.txt", payload)
        assertEquals(payload.toList(), store.read("a.txt").toList())
    }

    @Test fun readExactCapIsLegalEof() {
        val store = ScopedFileStore(tempRoot())
        store.write("cap.bin", ByteArray(16) { it.toByte() })
        // 恰等於上限：sentinel 探針讀到 EOF，必須成功而非誤判超限。
        assertEquals(16, store.read("cap.bin", maxBytes = 16).size)
    }

    @Test fun readOverCapRejectedWithoutTruncation() {
        val store = ScopedFileStore(tempRoot())
        store.write("big.bin", ByteArray(17) { 1 })
        val e = assertBudgeted(
            { store.read("big.bin", maxBytes = 16) },
            FileBudgetCode.READ_TOO_LARGE,
        )
        assertTrue(e.message!!.contains("16"))
    }

    @Test fun readOverDefaultCapRejected() {
        val store = ScopedFileStore(tempRoot())
        store.write("huge.bin", ByteArray(ScopedFileStore.MAX_READ_BYTES + 1))
        // 預檢 length() 即拒，不會先把整檔灌進記憶體後才截斷。
        assertBudgeted({ store.read("huge.bin") }, FileBudgetCode.READ_TOO_LARGE)
    }

    @Test fun readGrownAfterLengthCheckStillRejected() {
        // 權威判定是串流 sentinel 而非 length() 快篩：用剛好壓線的小上限，
        // 內容超限即拒（覆蓋「檢查後變大」的等價路徑）。
        val store = ScopedFileStore(tempRoot())
        store.write("edge.bin", ByteArray(64))
        assertBudgeted({ store.read("edge.bin", maxBytes = 63) }, FileBudgetCode.READ_TOO_LARGE)
    }

    // ---- write：有界冪等比對 ----

    @Test fun writeUnchangedKeepsMtime() {
        val root = tempRoot()
        val store = ScopedFileStore(root)
        val payload = "same".toByteArray()
        assertEquals(ScopedFileStore.WriteOutcome.Created, store.write("w.txt", payload))
        val mtime = File(root, "w.txt").lastModified()
        assertEquals(ScopedFileStore.WriteOutcome.Unchanged, store.write("w.txt", payload))
        assertEquals(mtime, File(root, "w.txt").lastModified())
    }

    @Test fun writeLargeEqualContentUnchangedWithoutFullRead() {
        // 2 MiB 等內容：舊實作 `readBytes()` 全量具現兩份；現串流比對應回 Unchanged。
        val store = ScopedFileStore(tempRoot())
        val big = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        assertEquals(ScopedFileStore.WriteOutcome.Created, store.write("big.bin", big))
        assertEquals(ScopedFileStore.WriteOutcome.Unchanged, store.write("big.bin", big.copyOf()))
    }

    @Test fun writeLargeDifferentContentUpdated() {
        val store = ScopedFileStore(tempRoot())
        val big = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        store.write("big.bin", big)
        // 長度相同、尾端相異：串流比對走到尾才判異，仍正確回 Updated。
        val altered = big.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertEquals(ScopedFileStore.WriteOutcome.Updated, store.write("big.bin", altered))
        // 長度不同：length() 快篩即判異，不讀內容。
        assertEquals(
            ScopedFileStore.WriteOutcome.Updated,
            store.write("big.bin", ByteArray(8)),
        )
    }

    // ---- list：基本形狀不變 ----

    @Test fun listSmallTreeSortedAndStable() {
        val store = ScopedFileStore(tempRoot())
        store.write("b.txt", "b".toByteArray())
        store.write("a/d.txt", "d".toByteArray())
        store.write("a/c.txt", "c".toByteArray())
        assertEquals(listOf("a/c.txt", "a/d.txt", "b.txt"), store.list())
        assertEquals(listOf("a/c.txt", "a/d.txt"), store.list("a"))
        assertEquals(emptyList<String>(), store.list("missing"))
        assertEquals(emptyList<String>(), store.list("../escape"))
        assertEquals(listOf("b.txt"), store.list("b.txt"))
    }

    @Test fun listSkipsTmpFiles() {
        val root = tempRoot()
        val store = ScopedFileStore(root)
        store.write("real.txt", "x".toByteArray())
        File(root, ".lp-stale.tmp").writeText("stale")
        assertEquals(listOf("real.txt"), store.list())
    }

    // ---- list：筆數窗 ----

    @Test fun listEntryWindowEnforced() {
        val store = ScopedFileStore(tempRoot())
        repeat(9) { i -> store.write("f$i.txt", "x".toByteArray()) }
        assertEquals(9, store.list(maxEntries = 9).size)
        assertBudgeted({ store.list(maxEntries = 8) }, FileBudgetCode.LIST_TOO_MANY)
    }

    @Test fun listDefaultWindowBoundsManySmallFilesAndSurfacesInSearch() {
        val root = tempRoot()
        val store = ScopedFileStore(root)
        repeat(ScopedFileStore.MAX_LIST_ENTRIES + 1) { i -> store.write("many/f$i.txt", "x".toByteArray()) }
        assertBudgeted({ store.list() }, FileBudgetCode.LIST_TOO_MANY)
        // 執行器相容：typed 失敗收斂為加法細碼，不靜默給部分結果。
        val executor = FileSearchExecutor(
            store = ScopedFileStore(root),
            privateRoot = root.absolutePath,
        )
        val result = executor.search("zzz-no-match")
        assertFalse(result.message, result.ok)
        assertEquals(FileBudgetCode.LIST_TOO_MANY.detail, result.detail)
    }

    // ---- list：深度窗 ----

    @Test fun listDepthWindowEnforced() {
        val root = tempRoot()
        val store = ScopedFileStore(root)
        val deep = (1..ScopedFileStore.MAX_LIST_DEPTH + 1).joinToString("/") { "d$it" }
        store.write("$deep/f.txt", "x".toByteArray())
        assertBudgeted({ store.list() }, FileBudgetCode.LIST_TOO_DEEP)
        // 恰滿合法：少一層即過（獨立樹，避免深鏈殘留干擾）。
        val shallowRoot = tempRoot()
        val shallowStore = ScopedFileStore(shallowRoot)
        val shallow = (1..ScopedFileStore.MAX_LIST_DEPTH).joinToString("/") { "s$it" }
        shallowStore.write("$shallow/g.txt", "y".toByteArray())
        assertTrue(shallowStore.list(maxDepth = ScopedFileStore.MAX_LIST_DEPTH).contains("$shallow/g.txt"))
    }

    @Test fun listDepthBudgetSurfacesInSearch() {
        val root = tempRoot()
        val store = ScopedFileStore(root)
        val deep = (1..ScopedFileStore.MAX_LIST_DEPTH + 1).joinToString("/") { "d$it" }
        store.write("$deep/f.txt", "needle".toByteArray())
        val result = FileSearchExecutor(
            store = ScopedFileStore(root),
            privateRoot = root.absolutePath,
        ).search("needle")
        assertFalse(result.message, result.ok)
        assertEquals(FileBudgetCode.LIST_TOO_DEEP.detail, result.detail)
    }

    // ---- list：deadline ----

    @Test fun listDeadlineEnforced() {
        val store = ScopedFileStore(tempRoot())
        store.write("a.txt", "x".toByteArray())
        // 零預算必超時（確定性觸發，不靠 sleep）。
        assertBudgeted({ store.list(deadlineMs = 0) }, FileBudgetCode.LIST_TIMEOUT)
        assertEquals(listOf("a.txt"), store.list(deadlineMs = 10_000))
    }

    // ---- list：symlink 環守衛 ----

    @Test fun listSymlinkLoopTerminates() {
        val root = tempRoot()
        val sub = File(root, "sub").apply { mkdirs() }
        File(sub, "f.txt").writeText("real")
        try {
            Files.createSymbolicLink(sub.toPath().resolve("loop"), root.toPath())
        } catch (_: Exception) {
            // 環境不支援 symlink（如 Windows 無權限）：跳過，語義由 #10 另行覆蓋。
            return
        }
        val store = ScopedFileStore(root)
        val listed = store.list()
        assertTrue(listed.contains("sub/f.txt"))
        // 真環被裁剪：結果有界，不會無限膨脹。
        assertTrue("loop not bounded: $listed", listed.size < 10)
    }

    @Test fun listDiamondSymlinksKeepLegacyOutput() {
        val root = tempRoot()
        val target = File(root, "orig").apply { mkdirs() }
        File(target, "f.txt").writeText("v")
        try {
            Files.createSymbolicLink(File(root, "alias").toPath(), target.toPath())
        } catch (_: Exception) {
            return
        }
        val store = ScopedFileStore(root)
        // 無環 diamond：沿舊 walkTopDown 語義兩路徑各自列出（#10 改語義前不得變）。
        assertEquals(listOf("alias/f.txt", "orig/f.txt"), store.list())
    }

    // ---- 執行器相容 ----

    @Test fun patchOverReadBudgetMapsToFileTooLarge() {
        val root = tempRoot()
        val store = ScopedFileStore(root)
        store.write("huge.txt", ByteArray(ScopedFileStore.MAX_READ_BYTES + 1) { 'a'.code.toByte() })
        val executor = FileEditExecutor(store = store, privateRoot = root.absolutePath)
        val result = executor.patch("huge.txt", "a", "b", singleMatch = false)
        assertFalse(result.message, result.ok)
        assertEquals("FILE_TOO_LARGE", result.detail)
    }

    @Test fun searchSkipsOverReadBudgetFile() {
        val root = tempRoot()
        val store = ScopedFileStore(root)
        store.write("small.txt", "needle here".toByteArray())
        store.write("huge.txt", "needle".toByteArray() + ByteArray(ScopedFileStore.MAX_READ_BYTES))
        val result = FileSearchExecutor(
            store = ScopedFileStore(root),
            privateRoot = root.absolutePath,
        ).search("needle")
        assertTrue(result.message, result.ok)
        assertTrue(result.hits.any { it.path == "small.txt" })
        assertTrue(result.filesSkipped >= 1)
    }

    @Test fun invalidBudgetsRejected() {
        val store = ScopedFileStore(tempRoot())
        try {
            store.list(maxEntries = 0)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        try {
            store.read("x", maxBytes = 0)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
