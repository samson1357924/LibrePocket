package dev.librepocket.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * #10 併發壓力補充（測試為主，不動生產）。
 * Current：併發替換有界行為釘住；Target：TOCTOU 消除需平台原語，屬 follow-up。
 *
 * 覆蓋既有 [ScopedFileSymlinkTest.concurrentSwapStressBounded] 未覆蓋的組合：
 * 以 `rename`（[Files.move] 原子搬移＋退回）與 symlink 建立交替替換
 * `ws/flip`（檔）與 `ws/race-sub`（目錄），穿插 read／write／list／delete。
 * 合成 fixture：宿主機臨時 workspace＋outside 目錄＋假資料（`OUTSIDE-SECRET`／
 * `inside-*`／`v*`／`race-v*`），無真實個資、不碰生產端點。
 *
 * 有界斷言（僅證明行為有界，不宣稱消除競態；Java 無 O_NOFOLLOW／openat，
 * 見 [ScopedFileStore] 類註解 TOCTOU 段限制）：
 * - 無未捕異常逃逸（[errors] 為空；翻轉路徑預期內的 [IllegalArgumentException]／
 *   [FileNotFoundException]／併發窗口 [IOException] 計入 [denied] 混合桶，其中僅
 *   [IllegalArgumentException] 計入 [rejected] 門證據桶；穩定路徑／ephemeral 獨佔
 *   路徑任何異常皆進 [errors]；[FileBudgetException] 屬真 bug 進 [errors]）；
 * - 無懸掛（起跑閘＋[awaitTermination] 期限，失敗先 shutdownNow 再超時斷言）；
 * - outside 秘密未被竄改（內容位元一致）且穩定路徑寫入未穿出
 *   （`out/file.txt` 不存在）；翻轉路徑的讀殘餘（`outsideReads`）與
 *   外部新增檔（`outsideExtra`）僅診斷記錄，不計為通過條件——系統性穿出由
 *   單線程確定性測試（`writeThroughParentSymlinkDenied`、
 *   `writeDoesNotMkdirThroughSymlink`）嚴格覆蓋；
 * - 拒絕或安全重試：併發 [rejected]（僅 IAE）為重驗門證據，首條須屬鏈族
 *   symlink／escapes（本測試只翻轉 `flip`／`race-sub` 子路徑不動 root，
 *   root 定錨族 mismatch／identity 由 [ScopedFileStoreB2Test] T1／F1 確定性
 *   覆蓋）；事後清理＋可寫可讀證明安全重試／恢復（store 事後仍可用）。
 *   壓力有效性另以 fileLinkOk／fileRealOk／dirLinkOk／dirRealOk 雙態計數與
 *   讀列刪進度計數證明非零覆蓋空轉（讀成功零在高翻轉下合法，進度以 readAttempts 計；
 *   寫成功由無 errors 間接證明，失敗計入 denied 混合桶）。
 *
 * 時序：起跑閘（[CountDownLatch]）＋背景線程池＋`await` 期限；全檔不用
 * 睡眠猜時序（僅 latch／await 期限）。
 *
 * Robolectric vs JVM 區分（本檔為純 JVM 單元測試）：
 * - 未使用 `RobolectricTestRunner`／shadows；直寫宿主機 tmpfs（POSIX symlink、
 *   inode fileKey 語義，見 `assumeNonWindows`）。
 * - 宿主機語義≠裝置語義：裝置 ext4／f2fs、排程器、SELinux、程序隔離皆不同，
 *   本測試不宣稱任何裝置結論；裝置行為另由 `androidTest`／裝置驗證覆蓋。
 * - flipper 僅建模 symlink＋rename（hardlink／mount 同生產列 out-of-scope，
 *   見 [ScopedFileStore] 類註解）；`outsideExtra` 僅頂層診斷（`deep` 穿出落
 *   `out/deep` 下只見頂層名，內容遞迴未驗，刻意 flakiness 取捨）。
 */
class ScopedFileStoreConcurrencyPressureTest {

    private companion object {
        const val SECRET = "OUTSIDE-SECRET-pressure"
        const val FILE_FLIP_ROUNDS = 150
        const val DIR_FLIP_ROUNDS = 100
        const val READER_ROUNDS = 150
        const val WRITER_ROUNDS = 80
        const val LISTER_ROUNDS = 80
        const val DELETER_ROUNDS = 80
        const val GATE_TIMEOUT_SEC = 30L
        const val POOL_TIMEOUT_SEC = 60L
        const val POOL_SIZE = 7
        // staging 前綴錨定生產常數：若 TMP_NAME_PREFIX 變更，此處同步跟隨，
        // 避免清理塊靜默變 no-op（PocketGuard PR91 WARN）。
        val STAGE_FILE_INFIX = "stage-f-"
        val STAGE_DIR_INFIX = "stage-d-"
        fun stageFilePrefix(): String = ScopedFileStore.TMP_NAME_PREFIX + STAGE_FILE_INFIX
        fun stageDirPrefix(): String = ScopedFileStore.TMP_NAME_PREFIX + STAGE_DIR_INFIX
        fun stageAnyPrefix(): String = ScopedFileStore.TMP_NAME_PREFIX + "stage-"
    }

    /** best-effort 靜默清理（壓力攻擊側／收尾段共用，失敗即忽略）。 */
    private fun deleteQuietly(f: File) {
        try {
            Files.deleteIfExists(f.toPath())
        } catch (_: Exception) {
        }
    }

    /**
     * NOFOLLOW 遞迴刪除：絕不跟隨目錄 symlink（`File.deleteRecursively()` 會跟隨，
     * 在 `race-sub -> out` 殘留時會越界刪 outside）。以 `walkFileTree` 預設
     * 不傳 `FOLLOW_LINKS` 語義：遇連結只刪連結本體並跳過子樹。best-effort。
     */
    private fun deleteNoFollowRecursively(root: File) {
        try {
            val start = root.toPath()
            if (Files.isSymbolicLink(start)) {
                Files.deleteIfExists(start)
                return
            }
            if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) return
            Files.walkFileTree(
                start,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        // walk 預設不跟隨連結；此處若 provider 仍回目錄屬性，以 link 檢查兜底。
                        if (Files.isSymbolicLink(dir)) {
                            try {
                                Files.deleteIfExists(dir)
                            } catch (_: Exception) {
                            }
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        try {
                            Files.deleteIfExists(file)
                        } catch (_: Exception) {
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: Path,
                        exc: IOException?,
                    ): FileVisitResult {
                        try {
                            Files.deleteIfExists(dir)
                        } catch (_: Exception) {
                        }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (_: Exception) {
        }
    }

    /** 移除可能被翻轉為 symlink 的路徑：先刪連結本體，殘留為實目錄才 NOFOLLOW 遞迴。 */
    private fun removePossiblyFlipped(sub: File) {
        try {
            if (Files.isSymbolicLink(sub.toPath())) {
                Files.deleteIfExists(sub.toPath())
                return
            }
        } catch (_: Exception) {
        }
        try {
            if (sub.isDirectory) {
                deleteNoFollowRecursively(sub)
                return
            }
        } catch (_: Exception) {
        }
        deleteQuietly(sub)
    }

    /**
     * 宿主機暫存：沿用 `files/` 全目錄既有模式（`createTempDirectory＋deleteOnExit＋
     * try-finally NOFOLLOW deleteNoFollowRecursively`）。`deleteOnExit` 非遞迴，
     * 僅作 kill 殘留第二道網；主清理為 `finally`（bot TemporaryFolder 建議不採納：
     * `files/` 全目錄皆手動模式，`@Rule` 混用反增分歧，且仍需 NOFOLLOW 語義自理）。
     */
    private fun workspace(): File =
        Files.createTempDirectory("ws-pressure").toFile().apply { deleteOnExit() }

    private fun outside(): File =
        Files.createTempDirectory("outside-pressure").toFile().apply { deleteOnExit() }

    /** symlink 需 POSIX 語義；Windows 下跳過（宿主機語義限制，非裝置結論）。 */
    private fun assumeNonWindows() {
        val osName: String = System.getProperty("os.name") ?: ""
        assumeTrue(!osName.startsWith("Windows", ignoreCase = true))
    }

    /**
     * 原子替換：暫存→目標。先試 `ATOMIC_MOVE＋REPLACE_EXISTING`，不支援原子
     * 搬移才退回普通覆蓋；其餘異常視為併發窗口（best-effort，呼叫方忽略），
     * 並清理暫存殘留。回傳是否成功，供壓力有效性計數（不計為通過條件）。
     */
    private fun renameReplace(staged: File, target: File): Boolean {
        try {
            try {
                Files.move(
                    staged.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    staged.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: UnsupportedOperationException) {
                // 部分 provider 拋裸 UOE 而非子類，語義同不支援原子搬移。
                Files.move(
                    staged.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            return true
        } catch (_: Exception) {
            deleteQuietly(staged)
            return false
        }
    }

    private fun isRejectionMessage(msg: String?): Boolean {
        val m = msg ?: return false
        return m.contains("symlink") || m.contains("escapes") ||
            m.contains("mismatch") || m.contains("identity")
    }

    /** 壓力下實際可達的鏈族（翻轉 flip/race-sub）；定錨族由 B2 T1/F1 確定性覆蓋。 */
    private fun isChainRejectionMessage(msg: String?): Boolean {
        val m = msg ?: return false
        return m.contains("symlink") || m.contains("escapes")
    }

    /** 壓力後清理＋恢復驗證抽取（bot 單體建議善意收斂；workers 閉包共享計數器保留內聯）。 */
    private fun cleanupAfterPressure(ws: File) {
        // 先 NOFOLLOW 移除翻轉殘留（絕不跟隨 symlink 進 outside，人審 P2#1）。
        removePossiblyFlipped(File(ws, "race-sub"))
        deleteQuietly(File(ws, "flip"))
        (ws.listFiles()?.toList() ?: emptyList())
            .filter { it.name.startsWith(stageAnyPrefix()) }
            .forEach {
                if (it.isDirectory && !Files.isSymbolicLink(it.toPath())) {
                    deleteNoFollowRecursively(it)
                } else {
                    deleteQuietly(it)
                }
            }
        // 清理後毒鏈必須已除，否則恢復證明空洞；且 race-sub 本體（無論連結／實目錄）
        // 必須不存在（僅查連結會漏普通目錄殘留，人審 P3#2）。
        // 呼叫前 workers 須已確認全退出（見 poolQuiesced 門），否則不得呼叫。
        assertFalse(
            "race-sub staging must be cleaned",
            File(ws, "race-sub").let { Files.isSymbolicLink(it.toPath()) } ||
                (ws.listFiles()?.any { it.name.startsWith(stageAnyPrefix()) } == true),
        )
        assertFalse(
            "race-sub must be gone after cleanup",
            Files.exists(File(ws, "race-sub").toPath(), LinkOption.NOFOLLOW_LINKS),
        )
    }

    /** 恢復證明＋事後毒鏈錨點（讀寫刪三拒；exists 由單線程既有測試覆蓋）。 */
    private fun verifyRecoveryAndAnchor(ws: File, out: File, store: ScopedFileStore) {
        store.write("after-pressure.txt", "ok".toByteArray(Charsets.UTF_8))
        assertEquals("ok", String(store.read("after-pressure.txt"), Charsets.UTF_8))
        // 恢復後列目 sane＋刪除往返。
        assertTrue(store.list().none { it.contains(ScopedFileStore.TMP_NAME_PREFIX) })
        assertTrue(store.delete("after-pressure.txt"))
        store.write("after-pressure.txt", "ok".toByteArray(Charsets.UTF_8))
        assertEquals("ok", String(store.read("after-pressure.txt"), Charsets.UTF_8))
        // 確定性錨點：事後毒鏈讀寫刪仍拒（併發 rejected>0 為壓力證據，本段為確定性對照）。
        deleteQuietly(File(ws, "post-link"))
        Files.createSymbolicLink(
            File(ws, "post-link").toPath(),
            File(out, "secret.txt").toPath(),
        )
        try {
            store.read("post-link")
            fail("expected IllegalArgumentException: post-link read")
        } catch (e: IllegalArgumentException) {
            assertTrue(isRejectionMessage(e.message))
        }
        try {
            store.write("post-link", "x".toByteArray(Charsets.UTF_8))
            fail("expected IllegalArgumentException: post-link write")
        } catch (e: IllegalArgumentException) {
            assertTrue(isRejectionMessage(e.message))
        }
        try {
            store.delete("post-link")
            fail("expected IllegalArgumentException: post-link delete")
        } catch (e: IllegalArgumentException) {
            assertTrue(isRejectionMessage(e.message))
        }
        assertEquals(SECRET, File(out, "secret.txt").readText(Charsets.UTF_8))
    }

    @Test fun renameSymlinkPressureBounded() {
        assumeNonWindows()
        // 生產常數耦合守衛：staging／清理／列目斷言皆由此派生，空洞即 fail-fast。
        assertTrue(ScopedFileStore.TMP_NAME_PREFIX.isNotEmpty())
        assertTrue(stageFilePrefix().startsWith(ScopedFileStore.TMP_NAME_PREFIX))
        assertTrue(stageDirPrefix().startsWith(ScopedFileStore.TMP_NAME_PREFIX))
        val ws = workspace()
        val out = outside()
        // workers 退出確認旗：僅 pool.awaitTermination 成功才置 true；outer finally
        // 據此決定是否可遞迴清理共享路徑（人審 P2：超時未退出不得 teardown）。
        var poolQuiesced = false
        try {
            File(out, "secret.txt").writeText(SECRET, Charsets.UTF_8)
            val store = ScopedFileStore(ws)
            store.write("stable/file.txt", "seed".toByteArray(Charsets.UTF_8))

            val startGate = CountDownLatch(1)
            // ready 屏障：確保 7 workers 皆已啟動再同時放行，縮小零交錯窗口。
            // 非嚴格同步（放行後仍由排程器決定交錯），僅防 flipper 早退空轉。
            val readyLatch = CountDownLatch(POOL_SIZE)
            val errors = AtomicReference<Throwable?>(null)
            val denied = AtomicInteger(0)
            val rejected = AtomicInteger(0)
            val firstRejection = AtomicReference<String?>(null)
            val observedOutside = AtomicInteger(0)
            val observedInside = AtomicInteger(0)
            val readAttempts = AtomicInteger(0)
            val listOk = AtomicInteger(0)
            val deleteOk = AtomicInteger(0)
            // 雙態計數：symlink 與實檔／實目錄各至少成功一次，證明翻轉非單態空轉。
            val fileLinkOk = AtomicInteger(0)
            val fileRealOk = AtomicInteger(0)
            val dirLinkOk = AtomicInteger(0)
            val dirRealOk = AtomicInteger(0)

            fun noteDenied(e: IllegalArgumentException) {
                denied.incrementAndGet()
                rejected.incrementAndGet()
                firstRejection.compareAndSet(null, e.message ?: ("IAE:" + e.javaClass.simpleName))
            }

            // 攻擊者一：rename 式檔案翻轉（真檔 ↔ 指外 symlink），暫存前綴
            // 以 ScopedFileStore.TMP_NAME_PREFIX 錨定（檔暫存被列目守衛忽略；
            // 目錄暫存為空故無條目，見 ScopedFileStore list 過濾段）。
            val fileFlipper = Runnable {
                try {
                    readyLatch.countDown()
                    if (!startGate.await(GATE_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                        errors.compareAndSet(null, AssertionError("startGate timeout"))
                        return@Runnable
                    }
                    repeat(FILE_FLIP_ROUNDS) { i ->
                        val flip = File(ws, "flip")
                        // 線程唯一性以 name 取得（pool 線程名唯一，無 deprecated API）。
                        val tid = Thread.currentThread().name
                        val staged = File(ws, stageFilePrefix() + "${tid}-$i")
                        try {
                            deleteQuietly(staged)
                            val isLink = (i % 2 == 0)
                            if (isLink) {
                                Files.createSymbolicLink(
                                    staged.toPath(),
                                    File(out, "secret.txt").toPath(),
                                )
                            } else {
                                staged.writeText("inside-$i", Charsets.UTF_8)
                            }
                            if (renameReplace(staged, flip)) {
                                if (isLink) fileLinkOk.incrementAndGet() else fileRealOk.incrementAndGet()
                            }
                        } catch (_: Exception) {
                            deleteQuietly(staged)
                        }
                    }
                } catch (t: Throwable) {
                    errors.compareAndSet(null, t)
                }
            }

            // 攻擊者二：rename 式目錄翻轉（真目錄 ↔ 指外 symlink）。
            // 非空目錄無法 rename 覆蓋，故先移走現態（best-effort），再把
            // staged rename 進來；失敗即下一輪重試（壓力來源，不保證每次翻轉成功）。
            val dirFlipper = Runnable {
                try {
                    readyLatch.countDown()
                    if (!startGate.await(GATE_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                        errors.compareAndSet(null, AssertionError("startGate timeout"))
                        return@Runnable
                    }
                    repeat(DIR_FLIP_ROUNDS) { i ->
                        val sub = File(ws, "race-sub")
                        // 線程唯一性見 fileFlipper 註解（name，無 deprecated API）。
                        val tid = Thread.currentThread().name
                        val staged = File(ws, stageDirPrefix() + "${tid}-$i")
                        try {
                            deleteQuietly(staged)
                            // NOFOLLOW 移除現態：絕不跟隨 symlink 進 outside（見人審 P2#1）。
                            removePossiblyFlipped(sub)
                            try {
                                val isLink = (i % 2 == 0)
                                if (isLink) {
                                    Files.createSymbolicLink(staged.toPath(), out.toPath())
                                } else {
                                    staged.mkdirs()
                                }
                                if (renameReplace(staged, sub)) {
                                    if (isLink) dirLinkOk.incrementAndGet() else dirRealOk.incrementAndGet()
                                }
                            } catch (_: Exception) {
                                deleteQuietly(staged)
                            }
                        } catch (t: Throwable) {
                            errors.compareAndSet(null, t)
                            return@Runnable
                        }
                    }
                } catch (t: Throwable) {
                    errors.compareAndSet(null, t)
                }
            }

            // 讀者：合法內容僅 `inside-*`；SECRET 屬已知 TOCTOU 殘餘只記錄；
            // IAE／找不到／併發 IO 窗口計入有界桶。
            val reader = Runnable {
                try {
                    readyLatch.countDown()
                    if (!startGate.await(GATE_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                        errors.compareAndSet(null, AssertionError("startGate timeout"))
                        return@Runnable
                    }
                    repeat(READER_ROUNDS) {
                        readAttempts.incrementAndGet()
                        try {
                            val text = String(store.read("flip"), Charsets.UTF_8)
                            when {
                                text == SECRET -> observedOutside.incrementAndGet()
                                text.startsWith("inside-") -> observedInside.incrementAndGet()
                                else -> errors.compareAndSet(
                                    null,
                                    AssertionError("unexpected flip content: $text"),
                                )
                            }
                        } catch (e: IllegalArgumentException) {
                            noteDenied(e)
                        } catch (_: FileNotFoundException) {
                            denied.incrementAndGet()
                        } catch (e: FileBudgetException) {
                            // 預算異常屬 IOException 子類但本測試資料量極小，命中即真 bug，不進有界桶。
                            errors.compareAndSet(null, e)
                        } catch (_: IOException) {
                            denied.incrementAndGet()
                        } catch (t: Throwable) {
                            errors.compareAndSet(null, t)
                        }
                    }
                } catch (t: Throwable) {
                    errors.compareAndSet(null, t)
                }
            }

            // 寫者：穩定路徑（永不翻轉）＋翻轉路徑（淺／深交替，覆蓋逐段建父目錄）。
            // 穩定路徑穿出即真缺陷（下文嚴格斷言）；翻轉路徑殘餘僅診斷。
            val writer = Runnable {
                try {
                    readyLatch.countDown()
                    if (!startGate.await(GATE_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                        errors.compareAndSet(null, AssertionError("startGate timeout"))
                        return@Runnable
                    }
                    repeat(WRITER_ROUNDS) { i ->
                        // 穩定路徑永不翻轉：任何 IAE 皆為誤拒（false-positive），記 errors 不進 denied。
                        try {
                            store.write("stable/file.txt", "v$i".toByteArray(Charsets.UTF_8))
                        } catch (t: Throwable) {
                            errors.compareAndSet(null, t)
                        }
                        try {
                            if (i % 2 == 0) {
                                store.write("race-sub/race-file.txt", "race-v$i".toByteArray(Charsets.UTF_8))
                            } else {
                                store.write("race-sub/deep/file-$i.txt", "race-v$i".toByteArray(Charsets.UTF_8))
                            }
                        } catch (e: IllegalArgumentException) {
                            noteDenied(e)
                        } catch (_: FileNotFoundException) {
                            denied.incrementAndGet()
                        } catch (e: FileBudgetException) {
                            errors.compareAndSet(null, e)
                        } catch (_: IOException) {
                            denied.incrementAndGet()
                        } catch (t: Throwable) {
                            errors.compareAndSet(null, t)
                        }
                    }
                } catch (t: Throwable) {
                    errors.compareAndSet(null, t)
                }
            }

            // 列目者：結構性質與競態無關，嚴格斷言（相對路徑、無穿越段、無暫存洩露）。
            // `..` 以路徑段比對（合法名 `report..bak` 允許，見 FileScope.isSafeName）。
            val lister = Runnable {
                try {
                    readyLatch.countDown()
                    if (!startGate.await(GATE_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                        errors.compareAndSet(null, AssertionError("startGate timeout"))
                        return@Runnable
                    }
                    repeat(LISTER_ROUNDS) {
                        try {
                            val listed = store.list()
                            if (listed.any { it.startsWith("/") || it.split("/").any { seg -> seg == ".." } }) {
                                errors.compareAndSet(
                                    null,
                                    AssertionError("list malformed: $listed"),
                                )
                            }
                            if (listed.any { FileScope.sanitizeRelative(it) == null }) {
                                errors.compareAndSet(
                                    null,
                                    AssertionError("list unsanitized: $listed"),
                                )
                            }
                            // staging 過濾：檔暫存以前綴被守衛忽略（BudgetTest.listSkipsTmpFiles
                            // 靜態覆蓋，本處併發覆蓋）；目錄暫存為空故無條目。
                            if (listed.any { it.contains(ScopedFileStore.TMP_NAME_PREFIX) }) {
                                errors.compareAndSet(
                                    null,
                                    AssertionError("list leaks staging: $listed"),
                                )
                            }
                            listOk.incrementAndGet()
                        } catch (t: Throwable) {
                            errors.compareAndSet(null, t)
                        }
                    }
                } catch (t: Throwable) {
                    errors.compareAndSet(null, t)
                }
            }

            // 刪除者：自有 ephemeral 檔寫後刪＋翻轉路徑刪除嘗試；IAE／併發窗口有界化。
            val deleter = Runnable {
                try {
                    readyLatch.countDown()
                    if (!startGate.await(GATE_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                        errors.compareAndSet(null, AssertionError("startGate timeout"))
                        return@Runnable
                    }
                    repeat(DELETER_ROUNDS) { i ->
                        val ephemeral = "ephemeral-$i.txt"
                        // ephemeral 為 deleter 獨佔檔名（flipper 不碰）：IAE 屬誤拒記 errors。
                        try {
                            store.write(ephemeral, "e".toByteArray(Charsets.UTF_8))
                        } catch (t: Throwable) {
                            errors.compareAndSet(null, t)
                        }
                        try {
                            if (store.delete(ephemeral)) deleteOk.incrementAndGet()
                        } catch (t: Throwable) {
                            errors.compareAndSet(null, t)
                        }
                        try {
                            store.delete("race-sub/race-file.txt")
                        } catch (e: IllegalArgumentException) {
                            noteDenied(e)
                        } catch (_: FileNotFoundException) {
                            denied.incrementAndGet()
                        } catch (_: IOException) {
                            denied.incrementAndGet()
                        } catch (t: Throwable) {
                            errors.compareAndSet(null, t)
                        }
                    }
                } catch (t: Throwable) {
                    errors.compareAndSet(null, t)
                }
            }

            val pool = Executors.newFixedThreadPool(POOL_SIZE)
            try {
                pool.submit(fileFlipper)
                pool.submit(dirFlipper)
                pool.submit(reader)
                pool.submit(reader)
                pool.submit(writer)
                pool.submit(lister)
                pool.submit(deleter)
                // ready 屏障：7 workers 就緒後才放行；超時記錯並強制收尾。
                if (!readyLatch.await(GATE_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                    errors.compareAndSet(null, AssertionError("workers not ready"))
                }
                startGate.countDown()
                pool.shutdown()
                // 無懸掛：期限內必須收斂，否則超時失敗（非無限等待）。
                // 超時先 shutdownNow＋有界再等；仍活則置 poolQuiesced=false 並拋，
                // outer finally 據旗跳過共享路徑遞迴清理（人審 P2：assert 先拋導致
                // 後續 isTerminated 檢查永不可達，且 finally 無條件清理與活 worker 競爭）。
                val terminated = pool.awaitTermination(POOL_TIMEOUT_SEC, TimeUnit.SECONDS)
                if (!terminated) {
                    pool.shutdownNow()
                    val reaped = pool.awaitTermination(10, TimeUnit.SECONDS)
                    if (!reaped) {
                        throw AssertionError(
                            "stress pool must terminate (timeout); workers leaked, teardown skipped",
                        )
                    }
                }
                if (!pool.isTerminated) {
                    throw AssertionError("stress pool leaked workers; skip teardown to avoid races")
                }
                poolQuiesced = true
            } catch (t: Throwable) {
                try {
                    startGate.countDown()
                } catch (_: Exception) {
                }
                pool.shutdownNow()
                throw t
            }
            errors.get()?.let { throw AssertionError("stress failed", it) }

            // 有界斷言一：outside 秘密未被竄改；穩定路徑寫入未穿出。
            assertEquals(SECRET, File(out, "secret.txt").readText(Charsets.UTF_8))
            assertFalse("stable writer must not escape", File(out, "file.txt").exists())
            val outsideExtra = (out.listFiles()?.map { it.name }?.toSet() ?: emptySet()) -
                setOf("secret.txt")
            println(
                "[ConcurrencyPressure] denied=${denied.get()} rejected=${rejected.get()} " +
                    "inside=${observedInside.get()} readAttempts=${readAttempts.get()} " +
                    "outsideReads=${observedOutside.get()} listOk=${listOk.get()} " +
                    "deleteOk=${deleteOk.get()} fileLinkOk=${fileLinkOk.get()} " +
                    "fileRealOk=${fileRealOk.get()} dirLinkOk=${dirLinkOk.get()} " +
                    "dirRealOk=${dirRealOk.get()} firstRejection=${firstRejection.get()} " +
                    "outsideExtra=$outsideExtra",
            )

            // 有界斷言二：拒絕門有觸發。denied 為混合桶（IAE＋FNFE／IO 窗口），
            // 真正門證據為 rejected（僅 IAE）＋首條鏈族訊息；注意此僅證明「有呼叫被拒」，
            // 不等於「初驗後重驗命中」（靜態毒鏈初驗亦同訊息；極端單 vCPU 排程飢餓下
            // 碰撞密度可降，門檻僅 >0），重驗命中以 `writeRevalidationGateRefusesMidOperationSwap`
            // 為主要確定性門檢查（preGateHook 確定性證明）；
            // root 定錨族 mismatch／identity 由 B2Test T1／F1 確定性覆蓋。
            assertTrue(
                "expected some rejections, got denied=${denied.get()} rejected=${rejected.get()} " +
                    "(inside=${observedInside.get()} outsideReads=${observedOutside.get()} " +
                    "readAttempts=${readAttempts.get()})",
                rejected.get() > 0,
            )
            assertTrue(
                "first rejection must be a chain reverify family, got ${firstRejection.get()}",
                isChainRejectionMessage(firstRejection.get()),
            )
            // 壓力有效性與進度證明：雙態翻轉確實發生、各操作確實執行（非零覆蓋空轉）。
            // 讀進度以 attempts（300 次嘗試必達）證明執行；成功讀零（inside＋outside＝0）
            // 在高翻轉下為合法全拒，Foss 曾以此 flaky（人審 P1#3），不得計為失敗。
            assertTrue(
                "expected file link flips, got fileLinkOk=${fileLinkOk.get()}",
                fileLinkOk.get() > 0,
            )
            assertTrue(
                "expected file real flips, got fileRealOk=${fileRealOk.get()}",
                fileRealOk.get() > 0,
            )
            assertTrue(
                "expected dir link flips, got dirLinkOk=${dirLinkOk.get()}",
                dirLinkOk.get() > 0,
            )
            assertTrue(
                "expected dir real flips, got dirRealOk=${dirRealOk.get()}",
                dirRealOk.get() > 0,
            )
            assertTrue(
                "expected readers to run, got readAttempts=${readAttempts.get()}",
                readAttempts.get() == READER_ROUNDS * 2,
            )
            assertTrue("expected lists to happen, got listOk=${listOk.get()}", listOk.get() > 0)
            assertTrue("expected deletes to happen, got deleteOk=${deleteOk.get()}", deleteOk.get() > 0)

            // 安全重試／恢復（helpers 見 cleanupAfterPressure／verifyRecoveryAndAnchor；
            // workers 閉包共享 10＋ 計數器，保留內聯以免參數透傳遮蔽門關係）。
            // 到此 poolQuiesced 必為 true（超時已在上文拋出），清理無活 worker 競爭。
            cleanupAfterPressure(ws)
            verifyRecoveryAndAnchor(ws, out, store)
        } finally {
            // NOFOLLOW 終兜；但 workers 未確認退出（poolQuiesced=false，即超時洩漏）
            // 時跳過共享路徑遞迴清理，避免與存活翻轉競爭（人審 P2），殘留供診斷。
            if (poolQuiesced) {
                // ws 可能殘留 race-sub 指外連結，NOFOLLOW 絕不跟隨進 outside。
                deleteNoFollowRecursively(ws)
                deleteNoFollowRecursively(out)
            }
        }
    }

    /**
     * 確定性重驗證據（人審 P2#2）：壓力測試的 `rejected` 只證明「有呼叫被拒」，
     * 靜態毒鏈初驗亦同訊息，不等於初驗後重驗命中。本測試以 `preGateHook` 在
     * 初驗通過之後、搬移前重驗之前把父段換成指外連結，`hookRan`＋事後 `sub`
     * 為連結證明拒絕必來自重驗門（非入口先拒）；B2 T1/F1 另覆 root 置換。
     */
    @Test fun writeRevalidationGateRefusesMidOperationSwap() {
        assumeNonWindows()
        assertTrue(ScopedFileStore.TMP_NAME_PREFIX.isNotEmpty())
        val ws = workspace()
        val out = outside()
        try {
            File(out, "secret.txt").writeText(SECRET, Charsets.UTF_8)
            val store = ScopedFileStore(ws)
            store.write("sub/seed.txt", "seed".toByteArray(Charsets.UTF_8))
            var hookRan = false
            try {
                store.writeInternal(
                    "sub/evil.txt",
                    "x".toByteArray(Charsets.UTF_8),
                    preGateHook = {
                        hookRan = true
                        deleteNoFollowRecursively(File(ws, "sub"))
                        Files.createSymbolicLink(File(ws, "sub").toPath(), out.toPath())
                    },
                )
                fail("expected IllegalArgumentException: mid-operation parent swap")
            } catch (e: IllegalArgumentException) {
                assertTrue(
                    "revalidation must hit chain family, got ${e.message}",
                    isChainRejectionMessage(e.message),
                )
            }
            assertTrue("hook must have run (rejection is post-initial)", hookRan)
            assertTrue(
                "sub must be swapped to symlink (rejection is post-initial)",
                Files.isSymbolicLink(File(ws, "sub").toPath()),
            )
            assertFalse(File(out, "evil.txt").exists())
            assertEquals(SECRET, File(out, "secret.txt").readText(Charsets.UTF_8))
        } finally {
            removePossiblyFlipped(File(ws, "sub"))
            deleteNoFollowRecursively(ws)
            deleteNoFollowRecursively(out)
        }
    }

    /**
     * NOFOLLOW 回歸（人審 P2#1 最小重現断言化）：靜態 `ws/race-sub -> out` 下，
     * `deleteNoFollowRecursively(ws)` 只刪連結本體，`out/secret.txt` 必須存活；
     * 舊 `File.deleteRecursively()` 會跟隨進 target 刪外部內容。
     */
    @Test fun nofollowCleanupNeverFollowsStaticDirSymlink() {
        assumeNonWindows()
        val ws = workspace()
        val out = outside()
        try {
            File(out, "secret.txt").writeText(SECRET, Charsets.UTF_8)
            Files.createSymbolicLink(File(ws, "race-sub").toPath(), out.toPath())
            deleteNoFollowRecursively(ws)
            assertEquals(SECRET, File(out, "secret.txt").readText(Charsets.UTF_8))
            assertFalse(
                Files.exists(File(ws, "race-sub").toPath(), LinkOption.NOFOLLOW_LINKS),
            )
        } finally {
            deleteNoFollowRecursively(ws)
            deleteNoFollowRecursively(out)
        }
    }
}
