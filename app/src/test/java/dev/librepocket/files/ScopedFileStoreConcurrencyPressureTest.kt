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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
 *   壓力有效性另以 flipOk／dirFlipOk／讀寫列刪進度計數證明非零覆蓋空轉。
 *
 * 時序：起跑閘（[CountDownLatch]）＋背景線程池＋`await` 期限；全檔不用
 * 睡眠猜時序（僅 latch／await 期限）。
 *
 * Robolectric vs JVM 區分（本檔為純 JVM 單元測試）：
 * - 未使用 `RobolectricTestRunner`／shadows；直寫宿主機 tmpfs（POSIX symlink、
 *   inode fileKey 語義，見 `assumeNonWindows`）。
 * - 宿主機語義≠裝置語義：裝置 ext4／f2fs、排程器、SELinux、程序隔離皆不同，
 *   本測試不宣稱任何裝置結論；裝置行為另由 `androidTest`／裝置驗證覆蓋。
 */
class ScopedFileStoreConcurrencyPressureTest {

    private companion object {
        const val SECRET = "OUTSIDE-SECRET-pressure"
    }

    private fun workspace(): File =
        Files.createTempDirectory("ws-pressure").toFile().apply { deleteOnExit() }

    private fun outside(): File =
        Files.createTempDirectory("outside-pressure").toFile().apply { deleteOnExit() }

    /** symlink 需 POSIX 語義；Windows 下跳過（宿主機語義限制，非裝置結論）。 */
    private fun assumeNonWindows() {
        val osName: String = System.getProperty("os.name") ?: ""
        assumeTrue(!osName.startsWith("Windows"))
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
            try {
                Files.deleteIfExists(staged.toPath())
            } catch (_: Exception) {
            }
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

    @Test fun renameSymlinkPressureBounded() {
        assumeNonWindows()
        val ws = workspace()
        val out = outside()
        try {
            File(out, "secret.txt").writeText(SECRET)
            val store = ScopedFileStore(ws)
            store.write("stable/file.txt", "seed".toByteArray())

            val startGate = CountDownLatch(1)
            val errors = AtomicReference<Throwable?>(null)
            val denied = AtomicInteger(0)
            val rejected = AtomicInteger(0)
            val firstRejection = AtomicReference<String?>(null)
            val observedOutside = AtomicInteger(0)
            val observedInside = AtomicInteger(0)
            val listOk = AtomicInteger(0)
            val deleteOk = AtomicInteger(0)
            val flipOk = AtomicInteger(0)
            val dirFlipOk = AtomicInteger(0)

            fun noteDenied(e: IllegalArgumentException) {
                denied.incrementAndGet()
                rejected.incrementAndGet()
                firstRejection.compareAndSet(null, e.message ?: ("IAE:" + e.javaClass.simpleName))
            }

            // 攻擊者一：rename 式檔案翻轉（真檔 ↔ 指外 symlink），暫存前綴
            // `.lp-` 使列目守衛忽略 staging 殘留（見 TMP_NAME_PREFIX）。
            val fileFlipper = Runnable {
                try {
                    if (!startGate.await(30, TimeUnit.SECONDS)) {
                        errors.compareAndSet(null, AssertionError("startGate timeout"))
                        return@Runnable
                    }
                    repeat(150) { i ->
                        val flip = File(ws, "flip")
                        // JDK17 相容：threadId() 需 19+，測試僅需唯一性用 deprecated id 即可。
                        val staged = File(ws, ".lp-stage-f-${Thread.currentThread().id}-$i")
                        try {
                            try {
                                Files.deleteIfExists(staged.toPath())
                            } catch (_: Exception) {
                            }
                            if (i % 2 == 0) {
                                Files.createSymbolicLink(
                                    staged.toPath(),
                                    File(out, "secret.txt").toPath(),
                                )
                            } else {
                                staged.writeText("inside-$i")
                            }
                            if (renameReplace(staged, flip)) flipOk.incrementAndGet()
                        } catch (_: Exception) {
                            try {
                                Files.deleteIfExists(staged.toPath())
                            } catch (_: Exception) {
                            }
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
                    if (!startGate.await(30, TimeUnit.SECONDS)) {
                        errors.compareAndSet(null, AssertionError("startGate timeout"))
                        return@Runnable
                    }
                    repeat(100) { i ->
                        val sub = File(ws, "race-sub")
                        // JDK17 相容：見 fileFlipper 註解。
                        val staged = File(ws, ".lp-stage-d-${Thread.currentThread().id}-$i")
                        try {
                            try {
                                Files.deleteIfExists(staged.toPath())
                            } catch (_: Exception) {
                            }
                            try {
                                if (Files.isSymbolicLink(sub.toPath())) {
                                    Files.deleteIfExists(sub.toPath())
                                } else if (sub.isDirectory) {
                                    sub.deleteRecursively()
                                } else {
                                    Files.deleteIfExists(sub.toPath())
                                }
                            } catch (_: Exception) {
                            }
                            try {
                                if (i % 2 == 0) {
                                    Files.createSymbolicLink(staged.toPath(), out.toPath())
                                } else {
                                    staged.mkdirs()
                                }
                                if (renameReplace(staged, sub)) dirFlipOk.incrementAndGet()
                            } catch (_: Exception) {
                                try {
                                    Files.deleteIfExists(staged.toPath())
                                } catch (_: Exception) {
                                }
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
                    if (!startGate.await(30, TimeUnit.SECONDS)) {
                        errors.compareAndSet(null, AssertionError("startGate timeout"))
                        return@Runnable
                    }
                    repeat(150) {
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
                    if (!startGate.await(30, TimeUnit.SECONDS)) {
                        errors.compareAndSet(null, AssertionError("startGate timeout"))
                        return@Runnable
                    }
                    repeat(80) { i ->
                        // 穩定路徑永不翻轉：任何 IAE 皆為誤拒（false-positive），記 errors 不進 denied。
                        try {
                            store.write("stable/file.txt", "v$i".toByteArray())
                        } catch (t: Throwable) {
                            errors.compareAndSet(null, t)
                        }
                        try {
                            if (i % 2 == 0) {
                                store.write("race-sub/race-file.txt", "race-v$i".toByteArray())
                            } else {
                                store.write("race-sub/deep/file-$i.txt", "race-v$i".toByteArray())
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

            // 列目者：結構性質與競態無關，嚴格斷言（相對路徑、無穿越段）。
            val lister = Runnable {
                try {
                    if (!startGate.await(30, TimeUnit.SECONDS)) {
                        errors.compareAndSet(null, AssertionError("startGate timeout"))
                        return@Runnable
                    }
                    repeat(80) {
                        try {
                            val listed = store.list()
                            if (listed.any { it.startsWith("/") || it.contains("..") }) {
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
                    if (!startGate.await(30, TimeUnit.SECONDS)) {
                        errors.compareAndSet(null, AssertionError("startGate timeout"))
                        return@Runnable
                    }
                    repeat(80) { i ->
                        val ephemeral = "ephemeral-$i.txt"
                        // ephemeral 為 deleter 獨佔檔名（flipper 不碰）：IAE 屬誤拒記 errors。
                        try {
                            store.write(ephemeral, "e".toByteArray())
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

            val pool = Executors.newFixedThreadPool(7)
            try {
                pool.submit(fileFlipper)
                pool.submit(dirFlipper)
                pool.submit(reader)
                pool.submit(reader)
                pool.submit(writer)
                pool.submit(lister)
                pool.submit(deleter)
                startGate.countDown()
                pool.shutdown()
                // 無懸掛：期限內必須收斂，否則超時失敗（非無限等待）。
                // 失敗路徑先 shutdownNow 避免 non-daemon worker 洩漏並與清理段競爭。
                try {
                    assertTrue("stress pool must terminate", pool.awaitTermination(60, TimeUnit.SECONDS))
                } finally {
                    if (!pool.isTerminated) pool.shutdownNow()
                }
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
            assertEquals(SECRET, File(out, "secret.txt").readText())
            assertFalse("stable writer must not escape", File(out, "file.txt").exists())
            val outsideExtra = (out.listFiles()?.map { it.name }?.toSet() ?: emptySet()) -
                setOf("secret.txt")
            println(
                "pressure diagnostics: denied=${denied.get()} rejected=${rejected.get()} " +
                    "inside=${observedInside.get()} " +
                    "outsideReads=${observedOutside.get()} listOk=${listOk.get()} " +
                    "deleteOk=${deleteOk.get()} flipOk=${flipOk.get()} " +
                    "dirFlipOk=${dirFlipOk.get()} firstRejection=${firstRejection.get()} " +
                    "outsideExtra=$outsideExtra",
            )

            // 有界斷言二：重驗門有觸發拒絕。denied 為混合桶（IAE＋FNFE／IO 窗口），
            // 真正門證據為 rejected（僅 IAE）＋首條鏈族訊息；root 定錨族
            // mismatch／identity 由 B2Test T1／F1 確定性覆蓋，壓力下僅期望 symlink／escapes。
            assertTrue(
                "expected some rejections, got denied=${denied.get()} rejected=${rejected.get()} " +
                    "(inside=${observedInside.get()} outsideReads=${observedOutside.get()} " +
                    "flipOk=${flipOk.get()} dirFlipOk=${dirFlipOk.get()})",
                rejected.get() > 0,
            )
            assertTrue(
                "first rejection must be a chain reverify family, got ${firstRejection.get()}",
                isChainRejectionMessage(firstRejection.get()),
            )
            // 壓力有效性與進度證明：翻轉確實發生、各操作確實執行（非零覆蓋空轉）。
            // flipOk 為檔翻轉主訊號（REPLACE 恆可覆蓋）；dirFlipOk 受非空目錄競爭影響
            // 拆訊息以利歸因；讀進度以 inside＋outsideReads 之和證明（全撞窗口亦算執行）。
            assertTrue(
                "expected file flips to happen, got flipOk=${flipOk.get()}",
                flipOk.get() > 0,
            )
            assertTrue(
                "expected dir flips to happen, got dirFlipOk=${dirFlipOk.get()}",
                dirFlipOk.get() > 0,
            )
            assertTrue(
                "expected reads to happen, got inside=${observedInside.get()} " +
                    "outsideReads=${observedOutside.get()}",
                observedInside.get() + observedOutside.get() > 0,
            )
            assertTrue("expected lists to happen, got listOk=${listOk.get()}", listOk.get() > 0)
            assertTrue("expected deletes to happen, got deleteOk=${deleteOk.get()}", deleteOk.get() > 0)

            // 安全重試／恢復：清理競爭殘留後 store 仍可寫可讀；毒連結單線程仍拒。
            try {
                val sub = File(ws, "race-sub")
                if (Files.isSymbolicLink(sub.toPath())) Files.deleteIfExists(sub.toPath())
            } catch (_: Exception) {
            }
            try {
                Files.deleteIfExists(File(ws, "flip").toPath())
            } catch (_: Exception) {
            }
            try {
                File(ws, "race-sub").deleteRecursively()
            } catch (_: Exception) {
            }
            (ws.listFiles()?.toList() ?: emptyList())
                .filter { it.name.startsWith(".lp-stage-") }
                .forEach {
                    try {
                        if (it.isDirectory) it.deleteRecursively() else it.delete()
                    } catch (_: Exception) {
                    }
                }
            store.write("after-pressure.txt", "ok".toByteArray())
            assertEquals("ok", String(store.read("after-pressure.txt"), Charsets.UTF_8))
            // 確定性錨點：事後毒鏈仍拒（併發 denied>0 為壓力證據，本段為確定性對照）。
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
            assertEquals(SECRET, File(out, "secret.txt").readText())
        } finally {
            try {
                ws.deleteRecursively()
            } catch (_: Exception) {
            }
            try {
                out.deleteRecursively()
            } catch (_: Exception) {
            }
        }
    }
}
