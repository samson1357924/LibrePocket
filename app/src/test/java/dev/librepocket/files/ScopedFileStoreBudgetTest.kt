package dev.librepocket.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * #16 ScopedFileStore 預算測試：read size budget、write 有界比對、
 * list 筆數／深度／deadline 上限＋symlink 環守衛，以及執行器相容映射。
 *
 * NOFOLLOW 語義（#57 凌駕 legacy）：列舉不跟隨任何 symlink，遇連結直接
 * 略過不下鑽；兄弟 diamond 別名不再列出，只列真實側（見
 * listDiamondSymlinksNofollowSkipsAlias）；真環測試只斷言受控終止。
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

    // ---- list：階段二 lazy 列舉上限 + visited-entry deadline ----

    @Test fun listSingleDirFarExceedsCapFailsFast() {
        val root = tempRoot()
        val store = ScopedFileStore(root)
        // 單一目錄 500 檔、cap 8：第 maxEntries+1 筆即拋 LIST_TOO_MANY。
        // 實作以 DirectoryStream 逐項遍歷，不一次配置整個目錄陣列
        //（無 listFiles 全量；見 ScopedFileStore.list KDoc 資源保證）。
        repeat(500) { i -> File(root, "f$i.txt").writeText("x") }
        assertBudgeted({ store.list(maxEntries = 8) }, FileBudgetCode.LIST_TOO_MANY)
        // 恰滿合法對照：cap 足夠時全量可見，且排序穩定。
        val all = store.list(maxEntries = 500)
        assertEquals(500, all.size)
        assertEquals(all.sorted(), all)
    }

    @Test fun listIgnoredEntriesStillEnforceDeadline() {
        val root = tempRoot()
        val store = ScopedFileStore(root)
        // 大量被忽略 entry、無普通檔：deadline 須以 visited-entry 計數觸發，
        // 不以收錄檔數為準；零預算確定性超時，不靠 sleep。
        // 注意：本測試起手檢查即拋，新舊碼皆過，僅作迴歸；visited-entry 證明見
        // listIgnoredEntriesVisitedCountTriggersTimeoutWithFakeClock。
        repeat(300) { i -> File(root, ".lp-stale-$i.tmp").writeText("stale") }
        assertBudgeted({ store.list(deadlineMs = 0) }, FileBudgetCode.LIST_TIMEOUT)
        // 對照：充足預算下被忽略 entry 不被收錄，回空表。
        assertEquals(emptyList<String>(), store.list(deadlineMs = 10_000))
    }

    @Test fun listIgnoredEntriesVisitedCountTriggersTimeoutWithFakeClock() {
        // 阻擋項 1 回歸：deterministic fake clock 證明 visited-entry（含略過者）修復。
        // 舊邏輯僅計收錄檔（accepted=0 → 無 periodic 檢查，僅起手+返回，見舊碼 noteProgress），
        // 同 fake 下必回空表 → 本測試在舊碼 FAIL；新碼在 256 處 LIST_TIMEOUT → PASS。
        // 全程無 sleep、無 wall-clock，每次 nowNanos 呼叫前進固定 1ms。
        val root = tempRoot()
        val store = ScopedFileStore(root)
        repeat(300) { i -> File(root, ".lp-stale-$i.tmp").writeText("stale") }
        val stepNanos = 1_000_000L
        fun freshFake(): () -> Long {
            var t = 0L
            return { t += stepNanos; t }
        }
        fun defaultFactory(dir: File): DirectoryStream<Path>? {
            return try {
                Files.newDirectoryStream(dir.toPath())
            } catch (_: Exception) {
                null
            }
        }
        // deadlineMs=3 → deadlineNanos=1ms+3ms=4ms。
        // 呼叫序：calc(t=1)→起手(t=2,2-4<0不過)→128(t=3,不過)→256(t=4,4-4>=0拋)。
        // 舊碼同場景僅 calc+起手+返回共 3 ticks(t=3<4)，不拋 → FAIL，證明區分度。
        assertBudgeted(
            {
                store.list(
                    "",
                    ScopedFileStore.MAX_LIST_ENTRIES,
                    ScopedFileStore.MAX_LIST_DEPTH,
                    3L,
                    freshFake(),
                    ::defaultFactory,
                )
            },
            FileBudgetCode.LIST_TIMEOUT,
        )
        // 對照：充足預算同 fake 下回空表（被略過者不收錄），證明超時源於預算而非列舉錯誤。
        // deadlineMs=10 → deadline=11ms，5 ticks(t=5<11)不過，全走完 300 個略過 entry。
        assertEquals(
            emptyList<String>(),
            store.list(
                "",
                ScopedFileStore.MAX_LIST_ENTRIES,
                ScopedFileStore.MAX_LIST_DEPTH,
                10L,
                freshFake(),
                ::defaultFactory,
            ),
        )
    }

    @Test fun listSingleDirFailsFastWithoutFullIteration() {
        // 阻擋項 2 回歸（b）：可注入 stream 工廠計數，證明未全量配置、fail-fast。
        // 500 檔、cap 8：第 maxEntries+1 筆即拋，next() 應恰 9 次、開流僅 base 一次，
        // 遠小於 500；若重引入全量配置或掃完全部才拋，nextCount 必 >>9 → FAIL。
        val root = tempRoot()
        val store = ScopedFileStore(root)
        repeat(500) { i -> File(root, "f$i.txt").writeText("x") }
        var openCount = 0
        var nextCount = 0
        fun countingFactory(dir: File): DirectoryStream<Path>? {
            openCount++
            val real = Files.newDirectoryStream(dir.toPath())
            return object : DirectoryStream<Path> by real {
                override fun iterator(): MutableIterator<Path> {
                    val it = real.iterator()
                    return object : MutableIterator<Path> by it {
                        override fun next(): Path {
                            nextCount++
                            return it.next()
                        }
                    }
                }
            }
        }
        assertBudgeted(
            {
                store.list(
                    "",
                    8,
                    ScopedFileStore.MAX_LIST_DEPTH,
                    ScopedFileStore.LIST_DEADLINE_MS,
                    System::nanoTime,
                    ::countingFactory,
                )
            },
            FileBudgetCode.LIST_TOO_MANY,
        )
        assertEquals("should open only base dir", 1, openCount)
        assertEquals("should stop at maxEntries+1", 9, nextCount)
        assertTrue("must not iterate all 500 (got $nextCount)", nextCount < 500)
    }

    @Test fun listImplementationDoesNotUseEagerListFiles() {
        // 阻擋項 2 回歸（a）：靜態約束，主列舉禁全量配置 API。
        // 實作僅以 newDirectoryStream 逐項遍歷；若重引入 .listFiles( 即 FAIL。
        // 註解提及舊語義用無點形式（listFiles)，不觸發；此處僅掃除註解後的程式碼。
        val source = findScopedFileStoreSource()
            ?: fail("cannot locate ScopedFileStore.kt from user.dir=${System.getProperty("user.dir")}").let {
                throw AssertionError("unreachable")
            }
        val codeOnly = stripComments(source.readText())
        val hits = Regex("""\.listFiles\s*\(""").findAll(codeOnly).toList()
        assertTrue(
            "ScopedFileStore.list must stay lazy (no .listFiles(), found ${hits.size})",
            hits.isEmpty(),
        )
    }

    private fun findScopedFileStoreSource(): File? {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        var cur: File? = userDir
        repeat(6) {
            val c = cur ?: return@repeat
            val a = File(c, "app/src/main/java/dev/librepocket/files/ScopedFileStore.kt")
            if (a.isFile) return a
            val b = File(c, "src/main/java/dev/librepocket/files/ScopedFileStore.kt")
            if (b.isFile) return b
            cur = c.parentFile
        }
        val fallback = File(
            "/tmp/opencode/LibrePocket-fix-filestore-budget/app/src/main/java/dev/librepocket/files/ScopedFileStore.kt",
        )
        return if (fallback.isFile) fallback else null
    }

    private fun stripComments(src: String): String {
        var s = src.replace(Regex("""(?s)/\*.*?\*/"""), "")
        s = s.lines().joinToString("\n") { line ->
            val idx = line.indexOf("//")
            if (idx >= 0) line.substring(0, idx) else line
        }
        return s
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

    @Test fun listDiamondSymlinksNofollowSkipsAlias() {
        val root = tempRoot()
        val target = File(root, "orig").apply { mkdirs() }
        File(target, "f.txt").writeText("v")
        try {
            Files.createSymbolicLink(File(root, "alias").toPath(), target.toPath())
        } catch (_: Exception) {
            return
        }
        val store = ScopedFileStore(root)
        // NOFOLLOW 語義（#57 凌駕 legacy）：別名連結不跟隨、不下鑽，只列真實側。
        assertEquals(listOf("orig/f.txt"), store.list())
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
