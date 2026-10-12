package dev.librepocket.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Symlink 負向測試（禁 symlink 政策，NOFOLLOW 語義）：
 * workspace 內 symlink 一律拒絕，列目不跟隨、防循環。
 */
class ScopedFileSymlinkTest {

    private fun workspace(): File =
        Files.createTempDirectory("ws-symlink").toFile().apply { deleteOnExit() }

    private fun outside(): File =
        Files.createTempDirectory("outside-symlink").toFile().apply { deleteOnExit() }

    private fun link(link: File, target: File) {
        Files.createSymbolicLink(link.toPath(), target.toPath())
    }

    private fun assertRejected(block: () -> Unit, label: String) {
        try {
            block()
            fail("expected IllegalArgumentException: $label")
        } catch (e: IllegalArgumentException) {
            // 預期：NOFOLLOW 拒絕。
            assertTrue(label, (e.message ?: "").contains("symlink"))
        }
    }

    @Test fun readThroughSymlinkDenied() {
        val ws = workspace()
        val out = outside()
        File(out, "secret.txt").writeText("OUTSIDE")
        link(File(ws, "link"), out)
        val store = ScopedFileStore(ws)
        assertRejected({ store.read("link/secret.txt") }, "read via link")
        assertRejected({ store.resolve("link/secret.txt") }, "resolve via link")
        // 外部檔不受影響，合法路徑仍可用。
        store.write("real.txt", "inside".toByteArray())
        assertEquals("inside", String(store.read("real.txt"), Charsets.UTF_8))
        assertEquals("OUTSIDE", File(out, "secret.txt").readText())
    }

    @Test fun writeThroughParentSymlinkDenied() {
        val ws = workspace()
        val out = outside()
        link(File(ws, "link"), out)
        val store = ScopedFileStore(ws)
        assertRejected({ store.write("link/evil.txt", "x".toByteArray()) }, "write via link")
        assertFalse(File(out, "evil.txt").exists())
    }

    @Test fun writeDoesNotMkdirThroughSymlink() {
        val ws = workspace()
        val out = outside()
        File(out, "secret.txt").writeText("OUTSIDE")
        link(File(ws, "link"), out)
        val store = ScopedFileStore(ws)
        val before = out.listFiles()?.map { it.name }?.toSet() ?: emptySet<String>()
        // 經連結的多段父目錄寫入必須被拒，且不得在外部新建任何目錄/檔案
        //（逐段 NOFOLLOW 建目錄，不用會跟隨連結的 mkdirs）。
        assertRejected({ store.write("link/newdir/evil.txt", "x".toByteArray()) }, "write via link subdir")
        assertRejected({ store.write("link/a/b/c.txt", "x".toByteArray()) }, "write via link deep")
        assertFalse(File(out, "newdir").exists())
        assertFalse(File(out, "a").exists())
        assertFalse(File(out, "evil.txt").exists())
        assertEquals(before, out.listFiles()?.map { it.name }?.toSet() ?: emptySet<String>())
        assertEquals("OUTSIDE", File(out, "secret.txt").readText())
        // 巢狀：真目錄下的連結同樣不穿出建目錄。
        File(ws, "a").mkdirs()
        link(File(ws, "a/link"), out)
        assertRejected(
            { store.write("a/link/newdir2/evil.txt", "x".toByteArray()) },
            "nested write via link subdir",
        )
        assertFalse(File(out, "newdir2").exists())
        assertEquals(before, out.listFiles()?.map { it.name }?.toSet() ?: emptySet<String>())
    }

    @Test fun listDoesNotFollowParentSymlink() {
        val ws = workspace()
        val out = outside()
        File(out, "secret.txt").writeText("OUTSIDE")
        link(File(ws, "link"), out)
        val store = ScopedFileStore(ws)
        store.write("real.txt", "v".toByteArray())
        val listed = store.list()
        assertEquals(listOf("real.txt"), listed)
        // 以連結為前綴的列目回空（resolve 拒絕 → 空表，fail-closed）。
        assertTrue(store.list("link").isEmpty())
        assertTrue(store.list("link/secret.txt").isEmpty())
    }

    @Test fun deleteAndExistsThroughSymlinkDenied() {
        val ws = workspace()
        val out = outside()
        File(out, "secret.txt").writeText("OUTSIDE")
        link(File(ws, "link"), out)
        val store = ScopedFileStore(ws)
        assertRejected({ store.delete("link/secret.txt") }, "delete via link")
        assertRejected({ store.exists("link/secret.txt") }, "exists via link")
        assertTrue("outside must survive", File(out, "secret.txt").exists())
    }

    @Test fun rootItselfSymlinkDenied() {
        val ws = workspace()
        ws.resolve("real.txt").writeText("inside")
        val parent = Files.createTempDirectory("rootparent-symlink").toFile().apply { deleteOnExit() }
        val rootLink = File(parent, "rootLink")
        link(rootLink, ws)
        val store = ScopedFileStore(rootLink)
        assertRejected({ store.resolve("real.txt") }, "root link resolve")
        assertRejected({ store.read("real.txt") }, "root link read")
        assertRejected({ store.write("a.txt", "x".toByteArray()) }, "root link write")
        assertRejected({ store.delete("real.txt") }, "root link delete")
        assertRejected({ store.exists("real.txt") }, "root link exists")
        // list fail-closed：回空而非列出外部。
        assertTrue(store.list().isEmpty())
    }

    @Test fun rootAliasDotDenied() {
        // P1: root=alias/. 词法上看似在 ws 下，但 alias 本身是指外連結；
        // Files.isSymbolicLink(alias/.) 为 false，必须檢查父段 alias。
        val parent = Files.createTempDirectory("ws-alias-dot").toFile().apply { deleteOnExit() }
        val out = outside()
        File(out, "secret.txt").writeText("OUTSIDE")
        val alias = File(parent, "alias")
        link(alias, out)
        val store = ScopedFileStore(File(alias, "."))
        assertRejected({ store.resolve("secret.txt") }, "alias/. resolve")
        assertRejected({ store.read("secret.txt") }, "alias/. read")
        assertRejected({ store.write("evil.txt", "x".toByteArray()) }, "alias/. write")
        assertRejected({ store.exists("secret.txt") }, "alias/. exists")
        assertRejected({ store.delete("secret.txt") }, "alias/. delete")
        assertTrue("alias/. list must be empty", store.list().isEmpty())
        assertEquals("OUTSIDE", File(out, "secret.txt").readText())
        assertFalse(File(out, "evil.txt").exists())
    }

    @Test fun rootAliasSubdirDenied() {
        // P1: root=alias/existingSubdir，中段 alias 指外，同樣必須拒絕。
        val parent = Files.createTempDirectory("ws-alias-sub").toFile().apply { deleteOnExit() }
        val out = outside()
        File(out, "secret.txt").writeText("OUTSIDE")
        File(out, "existingSubdir").mkdirs()
        File(out, "existingSubdir/inner.txt").writeText("OUTSIDE-INNER")
        val alias = File(parent, "alias")
        link(alias, out)
        val store = ScopedFileStore(File(alias, "existingSubdir"))
        assertRejected({ store.resolve("inner.txt") }, "alias/sub resolve")
        assertRejected({ store.read("inner.txt") }, "alias/sub read")
        assertRejected({ store.write("evil.txt", "x".toByteArray()) }, "alias/sub write")
        assertRejected({ store.exists("inner.txt") }, "alias/sub exists")
        assertRejected({ store.delete("inner.txt") }, "alias/sub delete")
        assertTrue("alias/sub list must be empty", store.list().isEmpty())
        assertEquals("OUTSIDE-INNER", File(out, "existingSubdir/inner.txt").readText())
        assertFalse(File(out, "existingSubdir/evil.txt").exists())
    }

    @Test fun absentRootSingleSegmentWriteCreatesRoot() {
        // P2: 缺席根的單段寫入須自動建根（舊 mkdirs 語義），不用 mkdirs 穿連結。
        val parent = Files.createTempDirectory("ws-absent-single").toFile().apply { deleteOnExit() }
        val absent = File(parent, "not-created")
        assertFalse(absent.exists())
        val store = ScopedFileStore(absent)
        store.write("a.txt", byteArrayOf(1))
        assertTrue("root must be created", absent.isDirectory)
        assertTrue(File(absent, "a.txt").isFile)
        assertEquals(1, store.read("a.txt").size)
    }

    @Test fun absentRootNestedWriteCreatesRoot() {
        // P2: 缺席根的巢狀寫入同樣建根+逐段建父目錄。
        val parent = Files.createTempDirectory("ws-absent-nested").toFile().apply { deleteOnExit() }
        val absent = File(parent, "not-created")
        val store = ScopedFileStore(absent)
        store.write("sub/a.txt", byteArrayOf(2))
        assertTrue(absent.isDirectory)
        assertTrue(store.read("sub/a.txt").contentEquals(byteArrayOf(2)))
    }

    @Test fun rootIsFileFailClosed() {
        val parent = Files.createTempDirectory("ws-root-is-file").toFile().apply { deleteOnExit() }
        val fileRoot = File(parent, "fileRoot")
        fileRoot.writeText("i am a file")
        val store = ScopedFileStore(fileRoot)
        try {
            store.write("a.txt", byteArrayOf(1))
            fail("expected IllegalArgumentException: root is file")
        } catch (_: IllegalArgumentException) {
            // 預期 fail-closed。
        }
    }

    @Test fun absentRootUnderPoisonedParentDenied() {
        // 缺席根位於毒父段下仍須拒，不建外部目錄。
        val parent = Files.createTempDirectory("ws-absent-poison").toFile().apply { deleteOnExit() }
        val out = outside()
        val alias = File(parent, "alias")
        link(alias, out)
        val absent = File(alias, "not-created")
        val store = ScopedFileStore(absent)
        assertRejected({ store.write("a.txt", "x".toByteArray()) }, "poisoned absent root write")
        assertRejected({ store.write("sub/a.txt", "x".toByteArray()) }, "poisoned absent nested write")
        assertFalse(File(out, "not-created").exists())
    }

    @Test fun rootAliasDotDotDenied() {
        // alias/.. 經 kernel 先跟隨 alias 再回退，詞法折疊後看似無連結，必須直接拒原始 ../. 段。
        val parent = Files.createTempDirectory("ws-alias-dotdot").toFile().apply { deleteOnExit() }
        val out = outside()
        File(out, "secret.txt").writeText("OUTSIDE")
        val alias = File(parent, "alias")
        link(alias, out)
        val poisoned = File(alias, "..")
        val before = parent.listFiles()?.map { it.name }?.toSet() ?: emptySet<String>()
        val store = ScopedFileStore(poisoned)
        assertRejected({ store.resolve("evil.txt") }, "alias/.. resolve")
        assertRejected({ store.read("evil.txt") }, "alias/.. read")
        assertRejected({ store.write("evil.txt", "x".toByteArray()) }, "alias/.. write")
        assertRejected({ store.exists("evil.txt") }, "alias/.. exists")
        assertRejected({ store.delete("evil.txt") }, "alias/.. delete")
        assertTrue("alias/.. list must be empty", store.list().isEmpty())
        // 不得在父層或 /tmp 落下外部檔案。
        assertEquals(before, parent.listFiles()?.map { it.name }?.toSet() ?: emptySet<String>())
        assertFalse(File(parent, "evil.txt").exists())
    }

    @Test fun nestedSymlinkDenied() {
        val ws = workspace()
        val out = outside()
        File(out, "secret.txt").writeText("OUTSIDE")
        File(ws, "a/b").mkdirs()
        link(File(ws, "a/link"), out)
        val store = ScopedFileStore(ws)
        assertRejected({ store.read("a/link/secret.txt") }, "nested read")
        assertRejected({ store.write("a/link/evil.txt", "x".toByteArray()) }, "nested write")
        assertTrue(store.list("a").none { it.startsWith("a/link") })
    }

    @Test fun circularSymlinkListTerminates() {
        val ws = workspace()
        val dir = File(ws, "d").apply { mkdirs() }
        // d/self → d（若跟隨即無窮）；root-link → ws（指回祖先）。
        link(File(dir, "self"), dir)
        link(File(dir, "toRoot"), ws)
        File(dir, "real.txt").writeText("v")
        val store = ScopedFileStore(ws)
        val listed = store.list()
        assertEquals(listOf("d/real.txt"), listed)
        // 點存取遇連結路徑一律拒絕。
        assertRejected({ store.read("d/self/real.txt") }, "cycle read")
    }

    @Test fun danglingSymlinkDeniedAndSkipped() {
        val ws = workspace()
        val store = ScopedFileStore(ws)
        Files.createSymbolicLink(
            File(ws, "dangling").toPath(),
            ws.toPath().resolve("no-such-target-xyz"),
        )
        store.write("real.txt", "v".toByteArray())
        assertRejected({ store.read("dangling") }, "dangling read")
        assertRejected({ store.write("dangling", "x".toByteArray()) }, "dangling write")
        assertRejected({ store.delete("dangling") }, "dangling delete")
        // 列目略過懸空連結，不拋錯。
        assertEquals(listOf("real.txt"), store.list())
    }

    @Test fun concurrentSwapStressBounded() {
        val ws = workspace()
        val out = outside()
        File(out, "secret.txt").writeText("OUTSIDE-SECRET")
        val store = ScopedFileStore(ws)
        val stop = CountDownLatch(1)
        val errors = AtomicReference<Throwable?>(null)
        // 有界行為的觀察計數：outsideReads 為已知 TOCTOU 殘餘（雙重替換下
        // 仍可能回傳替換後外部內容，見 ScopedFileStore 類註解），只記錄不失敗；
        // 本測試斷言的是：無未處理異常、無穿出寫入、無懸掛、事後仍可用。
        val observedOutside = AtomicInteger(0)
        val observedInside = AtomicInteger(0)
        val denied = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(10)
        // 翻轉者：在真實檔與指外連結之間反覆替換 ws/flip。
        val flipper = Runnable {
            try {
                stop.await(30, TimeUnit.SECONDS)
                repeat(200) {
                    val flip = File(ws, "flip")
                    try {
                        if (flip.exists() || Files.isSymbolicLink(flip.toPath())) {
                            Files.deleteIfExists(flip.toPath())
                        }
                    } catch (_: Exception) {
                    }
                    try {
                        if (it % 2 == 0) {
                            Files.createSymbolicLink(flip.toPath(), File(out, "secret.txt").toPath())
                        } else {
                            // 原子落盤，避免讀者看到截斷中的空檔（與安全無關，只為測試穩定）。
                            val tmp = File.createTempFile("flip", ".tmp", ws)
                            try {
                                tmp.writeText("inside")
                                try {
                                    Files.move(
                                        tmp.toPath(),
                                        flip.toPath(),
                                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                    )
                                } catch (_: Exception) {
                                    Files.move(
                                        tmp.toPath(),
                                        flip.toPath(),
                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                    )
                                }
                            } finally {
                                if (tmp.exists()) tmp.delete()
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            } catch (t: Throwable) {
                errors.compareAndSet(null, t)
            }
        }
        // 讀者：結果只能是 inside / 已記錄的殘餘 outside / 被拒 / 找不到；
        // 不允許撕裂內容、未處理異常或懸掛。
        val reader = Runnable {
            try {
                stop.await(30, TimeUnit.SECONDS)
                repeat(200) {
                    try {
                        val bytes = store.read("flip")
                        val text = String(bytes, Charsets.UTF_8)
                        when (text) {
                            "OUTSIDE-SECRET" -> observedOutside.incrementAndGet()
                            "inside" -> observedInside.incrementAndGet()
                            "" -> denied.incrementAndGet() // 防禦性：第三方非原子寫入的撕裂。
                            else -> errors.compareAndSet(
                                null,
                                AssertionError("unexpected content: $text"),
                            )
                        }
                    } catch (_: IllegalArgumentException) {
                        denied.incrementAndGet() // symlink 拒絕：預期有界行為。
                    } catch (_: java.io.FileNotFoundException) {
                        denied.incrementAndGet() // 刪除空窗：預期有界行為。
                    } catch (t: Throwable) {
                        errors.compareAndSet(null, t)
                    }
                }
            } catch (t: Throwable) {
                errors.compareAndSet(null, t)
            }
        }
        // 寫者：寫入與翻轉無關的穩定路徑，驗證高併發下 store 仍可用、
        // 寫入不被翻轉干擾。翻轉路徑的寫入由下面的 raceWriter 覆蓋。
        val writer = Runnable {
            try {
                stop.await(30, TimeUnit.SECONDS)
                repeat(100) {
                    try {
                        store.write("stable/file.txt", "v$it".toByteArray())
                    } catch (_: IllegalArgumentException) {
                        denied.incrementAndGet()
                    } catch (_: Exception) {
                        denied.incrementAndGet()
                    }
                }
            } catch (t: Throwable) {
                errors.compareAndSet(null, t)
            }
        }
        // 競態寫者：真經翻轉路徑 race-sub/... 寫入，與 dirFlipper 對打
        //（淺/深路徑交替，覆蓋逐段建父目錄）。
        // 成功寫入只應落在真目錄時期，連結時期須被拒。已知 TOCTOU 殘餘：
        // 建後三驗→暫存建立/搬移窗口內若被換成連結，本次內容仍可能落在鏈外
        //（見 ScopedFileStore 類註解）；本測試把穿出檔案記入診斷輸出、
        // 不計為失敗，系統性穿出由單線程確定性測試嚴格覆蓋（見下）。
        val raceWriter = Runnable {
            try {
                stop.await(30, TimeUnit.SECONDS)
                repeat(100) {
                    try {
                        if (it % 2 == 0) {
                            store.write("race-sub/race-file.txt", "race-v$it".toByteArray())
                        } else {
                            store.write("race-sub/deep/file-$it.txt", "race-v$it".toByteArray())
                        }
                    } catch (_: IllegalArgumentException) {
                        denied.incrementAndGet() // 連結時期拒絕：預期。
                    } catch (_: Exception) {
                        denied.incrementAndGet() // 父目錄刪除等併發 IO 空窗：預期有界行為。
                    }
                }
            } catch (t: Throwable) {
                errors.compareAndSet(null, t)
            }
        }
        // 列目者：併發列根，驗證不懸掛、不拋未處理異常。
        // 已知殘餘：walk 的 pop 驗連結→listFiles 窗口內若被換成連結，
        // 可能短暫列出鏈外檔名（檔名洩露殘餘，見 list KDoc）；僅記錄不失敗。
        // 結構性質（回傳皆為相對路徑）與競態無關，嚴格斷言。
        val listEscapes = AtomicInteger(0)
        val lister = Runnable {
            try {
                stop.await(30, TimeUnit.SECONDS)
                repeat(100) {
                    try {
                        val listed = store.list()
                        if (listed.any { it.startsWith("/") || it.contains("..") }) {
                            errors.compareAndSet(
                                null,
                                AssertionError("list malformed: $listed"),
                            )
                        }
                        if (listed.any { it.startsWith("race-sub") && it.contains("secret") }) {
                            listEscapes.incrementAndGet()
                        }
                    } catch (t: Throwable) {
                        errors.compareAndSet(null, t)
                    }
                }
            } catch (t: Throwable) {
                errors.compareAndSet(null, t)
            }
        }
        // 父目錄翻轉者：在真實目錄與指外連結之間反覆替換 ws/race-sub；
        // 寫者併發經 store 寫入該路徑，驗證無穿出寫入。
        val dirFlipper = Runnable {
            try {
                stop.await(30, TimeUnit.SECONDS)
                repeat(100) {
                    val sub = File(ws, "race-sub")
                    try {
                        if (sub.exists() || Files.isSymbolicLink(sub.toPath())) {
                            if (sub.isDirectory && !Files.isSymbolicLink(sub.toPath())) {
                                sub.deleteRecursively()
                            } else {
                                Files.deleteIfExists(sub.toPath())
                            }
                        }
                    } catch (_: Exception) {
                    }
                    try {
                        if (it % 2 == 0) {
                            Files.createSymbolicLink(sub.toPath(), out.toPath())
                        } else {
                            sub.mkdirs()
                        }
                    } catch (_: Exception) {
                    }
                }
            } catch (t: Throwable) {
                errors.compareAndSet(null, t)
            }
        }
        repeat(2) { pool.submit(flipper) }
        pool.submit(dirFlipper)
        repeat(3) { pool.submit(reader) }
        pool.submit(writer)
        pool.submit(raceWriter)
        pool.submit(lister)
        stop.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        errors.get()?.let { throw AssertionError("stress failed", it) }
        // 有界斷言：無未處理異常、無懸掛、固定外部檔未被竄改、store 事後仍可用。
        // raceWriter 真經翻轉路徑寫入，使「翻轉路徑寫入仍有界」名實相符；
        // 舊註解稱「避免不可能斷言」而只寫穩定路徑、卻斷言外部無穿出，已修正。
        // 讀到外部殘餘（observedOutside）與寫穿出皆為已知 TOCTOU，只記錄不斷言為零：
        // 系統性穿出（如 mkdir 跟隨連結）由單線程確定性測試嚴格覆蓋
        //（writeThroughParentSymlinkDenied、writeDoesNotMkdirThroughSymlink）。
        assertFalse("stable writer must not escape", File(out, "file.txt").exists())
        assertEquals("OUTSIDE-SECRET", File(out, "secret.txt").readText())
        val outsideExtra = (out.listFiles()?.map { it.name }?.toSet() ?: emptySet<String>()) -
            setOf("secret.txt")
        println(
            "stress diagnostics: denied=${denied.get()} inside=${observedInside.get()} " +
                "outsideReads=${observedOutside.get()} listEscapes=${listEscapes.get()} " +
                "outsideExtra=$outsideExtra",
        )
        assertTrue(
            "expected some denials, got ${denied.get()} " +
                "(inside=${observedInside.get()} outside=${observedOutside.get()})",
            denied.get() > 0,
        )
        // store 事後仍可用（先清理競爭殘留，再寫讀驗證）。
        try {
            val sub = File(ws, "race-sub")
            if (Files.isSymbolicLink(sub.toPath())) Files.deleteIfExists(sub.toPath())
        } catch (_: Exception) {
        }
        File(ws, "flip").delete()
        File(ws, "race-sub").deleteRecursively()
        store.write("after.txt", "ok".toByteArray())
        assertEquals("ok", String(store.read("after.txt"), Charsets.UTF_8))
    }
}
