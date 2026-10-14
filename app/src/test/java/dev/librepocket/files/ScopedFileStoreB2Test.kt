package dev.librepocket.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Path

/**
 * B2 有界切片回歸（#10，不碰 shell 政策本體）：
 * - delete：置換後拒刪外部（最終目標 NOFOLLOW lstat＋正規域內重驗）。
 * - list：條目只回相對路徑（不洩絕對全路徑）；快照語義揭露為靜態約束。
 * - write：落盤前重驗父鏈（靜態約束＋單線程置換拒絕）。
 */
class ScopedFileStoreB2Test {

    private fun workspace(): File =
        Files.createTempDirectory("ws-b2").toFile().apply { deleteOnExit() }

    private fun outside(): File =
        Files.createTempDirectory("outside-b2").toFile().apply { deleteOnExit() }

    private fun link(link: File, target: File) {
        Files.createSymbolicLink(link.toPath(), target.toPath())
    }

    /** symlink 需 POSIX 語義；Windows 下跳過（整檔 symlink 案一致）。 */
    private fun assumeNonWindows() {
        val osName: String = System.getProperty("os.name") ?: ""
        assumeTrue(!osName.startsWith("Windows"))
    }

    private fun assertRejected(block: () -> Unit, label: String) {
        try {
            block()
            fail("expected IllegalArgumentException: $label")
        } catch (e: IllegalArgumentException) {
            assertTrue(label, (e.message ?: "").contains("symlink") || (e.message ?: "").contains("escapes"))
        }
    }

    // ---- delete：最終目標置換後拒刪外部 ----

    @Test fun deleteReplacedFinalLinkRefusesAndOutsideSurvives() {
        // symlink 需 POSIX；Windows 語義不同故限 Linux。
        assumeNonWindows()
        val ws = workspace()
        val out = outside()
        val secret = File(out, "secret.txt").apply { writeText("OUTSIDE") }
        val store = ScopedFileStore(ws)
        store.write("victim.txt", "inside".toByteArray())
        // 單線程置換：真檔→指外連結（模擬檢查後被換掉的終態）。
        File(ws, "victim.txt").delete()
        link(File(ws, "victim.txt"), secret)
        assertRejected({ store.delete("victim.txt") }, "delete replaced final link")
        assertEquals("OUTSIDE", secret.readText())
        // 是 link 即拒不刪：連結本身仍在（fail-closed，不刪鏈）。
        assertTrue(Files.isSymbolicLink(File(ws, "victim.txt").toPath()))
    }

    @Test fun deleteReplacedParentLinkRefusesAndOutsideSurvives() {
        assumeNonWindows()
        val ws = workspace()
        val out = outside()
        val secret = File(out, "secret.txt").apply { writeText("OUTSIDE") }
        val store = ScopedFileStore(ws)
        File(ws, "sub").mkdirs()
        File(ws, "sub/inner.txt").writeText("inside")
        // 單線程置換：真目錄→指外連結。
        File(ws, "sub").deleteRecursively()
        link(File(ws, "sub"), out)
        assertRejected({ store.delete("sub/secret.txt") }, "delete via replaced parent link")
        assertEquals("OUTSIDE", secret.readText())
        // 注：舊 `assertFalse(File(out, "inner.txt").exists())` 為 vacuous
        //（inner.txt 從未在外部存在過，永遠 pass），已移除，改斷言連結本身存活。
        // parent-link 即拒不刪：連結本身仍在（fail-closed，不跟隨父鏈刪外部）。
        assertTrue(Files.isSymbolicLink(File(ws, "sub").toPath()))
    }

    @Test fun deletePreDeleteGateExists() {
        // 結構守衛（非行為證明）：基底 ensureNoSymlinkChain 已擋單線程前置換，
        // 故無單線程 RED 差分；本測試只鎖定 deleteInternal 體內的用時重驗門存在
        //（公開 delete 經 deleteInternal 委派，行為不分叉），
        // B2 增量（驗→刪窗口縮小＋root 定錨）由 code review 驗證。
        val source = findStoreSource() ?: fail("cannot locate ScopedFileStore.kt").let { throw AssertionError() }
        val text = source.readText()
        assertTrue("public delete must delegate to deleteInternal", text.contains("fun delete(relativePath: String): Boolean = deleteInternal(relativePath)"))
        val deleteBody = text.substringAfter("fun deleteInternal(").substringBefore("\n    /**")
        assertTrue(
            "delete must lstat final target NOFOLLOW before delete (got: $deleteBody)",
            deleteBody.contains("isSymbolicLink") && deleteBody.contains("target.toPath()"),
        )
        assertTrue(
            "delete must re-verify canonical containment before delete",
            deleteBody.contains("canonicalPath") && deleteBody.contains(".delete()"),
        )
        assertTrue(
            "delete must anchor root and reverify identity (no re-read of root.canonicalPath)",
            deleteBody.contains("snapshotTrustedRoot") && deleteBody.contains("reverifyRootIdentity") &&
                deleteBody.contains("trustedRoot") && !deleteBody.contains("root.canonicalPath"),
        )
        // 順序鎖定：lstat＋canonical 重驗必須先於實際 delete，否則重排 bug 可穿透。
        val lstatIdx = deleteBody.indexOf("Files.isSymbolicLink(target.toPath())")
        val canonIdx = deleteBody.indexOf("target.canonicalPath")
        val delIdx = deleteBody.indexOf("return target.delete()")
        assertTrue(
            "lstat + canonical 必須先於 delete，否則重排 bug 可穿透 (got indices $lstatIdx/$canonIdx/$delIdx)",
            lstatIdx >= 0 && canonIdx > lstatIdx && delIdx > canonIdx,
        )
    }

    // ---- list：只回相對、不洩全路徑 ----

    @Test fun listEntriesAreRelativeNeverAbsolute() {
        val root = workspace()
        val store = ScopedFileStore(root)
        store.write("b.txt", "b".toByteArray())
        store.write("a/d.txt", "d".toByteArray())
        val listed = store.list()
        assertEquals(listOf("a/d.txt", "b.txt"), listed)
        for (entry in listed) {
            assertFalse("must not leak absolute path: $entry", File(entry).isAbsolute)
            assertFalse("must not leak absolute path: $entry", entry.startsWith("/"))
            assertFalse("must not contain traversal: $entry", entry.contains(".."))
            assertFalse("must not leak root prefix: $entry", entry.startsWith(root.absolutePath))
            assertTrue("must stay sanitized relative: $entry", FileScope.sanitizeRelative(entry) != null)
            // basename 性質：相對路徑的檔名段非空（不回空名、不回目錄前綴外的絕對體）。
            assertTrue(File(entry).name.isNotEmpty())
        }
        // 單檔前綴同樣只回相對（不洩全路徑）。
        assertEquals(listOf("b.txt"), store.list("b.txt"))
    }

    @Test fun listSnapshotDisclosureExists() {
        // 結構守衛（非行為證明）：鎖定 list KDoc 如實揭露
        // （單遍 best-effort、不保證單遍快照隔離與跨調一致、短暫洩名窗口）。
        val source = findStoreSource() ?: fail("cannot locate ScopedFileStore.kt").let { throw AssertionError() }
        val text = source.readText()
        assertTrue("list KDoc must disclose snapshot enumeration", text.contains("快照"))
        assertTrue("list KDoc must disclose cross-call consistency limit", text.contains("跨調"))
    }

    // ---- write：落盤前重驗 ----

    @Test fun writeParentSwappedBeforeCallRefuses() {
        assumeNonWindows()
        val ws = workspace()
        val out = outside()
        link(File(ws, "link"), out)
        val store = ScopedFileStore(ws)
        assertRejected({ store.write("link/evil.txt", "x".toByteArray()) }, "write via swapped parent")
        assertFalse(File(out, "evil.txt").exists())
    }

    @Test fun writeDenied_leavesNoTmp() {
        // 拒寫不留暫存：ws/link→out，write("link/evil.txt") 本 fixture 下 resolve→ensureNoSymlinkChain
        // 即拋（見 write/resolve；本案鎖定終態無殘留，不區分未建／建後清）；
        // 斷言 ws/out 內無 `.lp-*`（用 TMP_NAME_PREFIX 常數）+ 外部未落地。
        // 沿用 workspace()/outside() 但本案加 try-finally（不重構既有 7 案 deleteOnExit）。
        assumeNonWindows()
        val ws = workspace()
        val out = outside()
        try {
            link(File(ws, "link"), out)
            val store = ScopedFileStore(ws)
            try {
                store.write("link/evil.txt", "x".toByteArray())
                fail("expected IllegalArgumentException: write via parent link")
            } catch (e: IllegalArgumentException) {
                assertTrue(
                    "message must mention symlink/escapes, got ${e.message}",
                    (e.message ?: "").contains("symlink") || (e.message ?: "").contains("escapes"),
                )
            }
            val wsTop = (ws.listFiles()?.toList() ?: emptyList())
                .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && it.name.startsWith(ScopedFileStore.TMP_NAME_PREFIX) }
                .sortedBy { it.name }
            assertTrue("denied write must leave no tmp in ws, got $wsTop", wsTop.isEmpty())
            val outTop = (out.listFiles()?.toList() ?: emptyList())
                .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && it.name.startsWith(ScopedFileStore.TMP_NAME_PREFIX) }
                .sortedBy { it.name }
            assertTrue("denied write must leave no tmp in out, got $outTop", outTop.isEmpty())
            assertFalse(File(out, "evil.txt").exists())
        } finally {
            ws.deleteRecursively()
            out.deleteRecursively()
        }
    }

    @Test fun writeRevalidatesBeforeMove() {
        // 結構守衛（非行為證明）：基底已有建後三驗，單線程無 RED 差分；
        // 本測試只鎖定 writeInternal 搬移前再驗門存在（公開 write 經委派），
        // 用時窗口縮小＋root 定錨＋fallback 收窄由 review 驗證。
        val source = findStoreSource() ?: fail("cannot locate ScopedFileStore.kt").let { throw AssertionError() }
        val text = source.readText()
        assertTrue("public write must delegate to writeInternal", text.contains("fun write(relativePath: String, bytes: ByteArray): WriteOutcome ="))
        val writeBody = text.substringAfter("fun writeInternal(").substringBefore("private fun mkdirParentNoFollow")
        val tmpIdx = writeBody.indexOf("createTempFile")
        val moveIdx = writeBody.indexOf("moveFn(")
        assertTrue("write must create tmp then move (got indices $tmpIdx/$moveIdx)", tmpIdx >= 0 && moveIdx > tmpIdx)
        val preMove = writeBody.substring(tmpIdx, moveIdx)
        assertTrue(
            "write must re-verify symlink chain between tmp creation and move",
            preMove.contains("ensureNoSymlinkChain") || preMove.contains("ensurePreMove"),
        )
        assertTrue(
            "write must anchor root and use moveFn with narrowed fallback",
            writeBody.contains("snapshotTrustedRoot") && writeBody.contains("reverifyRootIdentity") &&
                writeBody.contains("AtomicMoveNotSupportedException") && writeBody.contains("preGateHook"),
        )
        assertTrue(
            "write must not re-anchor root from possibly-swapped root",
            !writeBody.contains("root.canonicalPath") && writeBody.contains("trustedRoot"),
        )
        val snapIdx = writeBody.indexOf("snapshotTrustedRoot")
        val hookIdx = writeBody.indexOf("preGateHook?.invoke()")
        val reverifyIdx = writeBody.indexOf("reverifyRootIdentity")
        assertTrue(
            "snapshot + hook must precede re-verify (got indices $snapIdx/$hookIdx/$reverifyIdx)",
            snapIdx >= 0 && hookIdx > snapIdx && reverifyIdx > hookIdx,
        )
    }

    // ---- T1 root-swap 確定性（B2 定錨；初驗與終門之間 swap root） ----

    @Test fun deleteRootSwappedBetweenGatesRefusesAndOutsideSurvives() {
        assumeNonWindows()
        val parent = Files.createTempDirectory("b2-roots-sw").toFile()
        val ws = File(parent, "ws").apply { mkdirs() }
        val out = Files.createTempDirectory("b2-roots-out").toFile()
        try {
            File(out, "victim.txt").writeText("OUTSIDE")
            val store = ScopedFileStore(ws)
            store.write("victim.txt", "inside".toByteArray())
            assertRejected({
                store.deleteInternal("victim.txt", preGateHook = {
                    val old = File(parent, "ws-old")
                    Files.move(ws.toPath(), old.toPath())
                    Files.createSymbolicLink(ws.toPath(), out.toPath())
                })
            }, "delete with swapped root must be denied")
            // 外部 victim 存活（定錨 containment 擋下，未刪真外部檔）。
            assertEquals("OUTSIDE", File(out, "victim.txt").readText())
            // 舊域內檔隨 rename 留在 ws-old（未被誤刪）。
            assertEquals("inside", File(File(parent, "ws-old"), "victim.txt").readText())
        } finally {
            try {
                if (Files.isSymbolicLink(ws.toPath())) Files.deleteIfExists(ws.toPath())
            } catch (_: Exception) {
            }
            try { File(parent, "ws-old").deleteRecursively() } catch (_: Exception) { }
            try { ws.deleteRecursively() } catch (_: Exception) { }
            try { parent.deleteRecursively() } catch (_: Exception) { }
            try { out.deleteRecursively() } catch (_: Exception) { }
        }
    }

    @Test fun writeRootSwappedBetweenGatesRefusesWithoutLandingOrTmp() {
        assumeNonWindows()
        val ws = workspace()
        val out = outside()
        try {
            val store = ScopedFileStore(ws)
            try {
                store.writeInternal(
                    "evil.txt",
                    "x".toByteArray(),
                    preGateHook = {
                        // 刪原域後以同名 symlink 指外（tmp 隨舊域同刪，不殘留到外部）。
                        ws.deleteRecursively()
                        Files.createSymbolicLink(ws.toPath(), out.toPath())
                    },
                )
                fail("expected IllegalArgumentException: write with swapped root")
            } catch (e: IllegalArgumentException) {
                assertTrue(
                    "message must mention symlink/escapes, got ${e.message}",
                    (e.message ?: "").contains("symlink") || (e.message ?: "").contains("escapes"),
                )
            }
            // hook 確實跑過（ws 已被換成 symlink），非入口先拒。
            assertTrue("hook must have run (ws is now symlink)", Files.isSymbolicLink(ws.toPath()))
            // 外部未落地。
            assertFalse(File(out, "evil.txt").exists())
            // 無 tmp 殘留（外部域內無 `.lp-*`）。
            val outTop = (out.listFiles()?.toList() ?: emptyList())
                .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && it.name.startsWith(ScopedFileStore.TMP_NAME_PREFIX) }
            assertTrue("denied write must leave no tmp in out, got $outTop", outTop.isEmpty())
        } finally {
            try {
                if (Files.isSymbolicLink(ws.toPath())) Files.deleteIfExists(ws.toPath())
            } catch (_: Exception) {
            }
            try { ws.deleteRecursively() } catch (_: Exception) { }
            try { out.deleteRecursively() } catch (_: Exception) { }
        }
    }

    // ---- T4 move fallback 故障注入（B2 收窄＋重驗） ----

    @Test fun writeFallbackAtomicUnsupportedRetriesAndLands() {
        val ws = workspace()
        try {
            val store = ScopedFileStore(ws)
            var calls = 0
            val outcome = store.writeInternal(
                "t.txt",
                "hello".toByteArray(),
                moveFn = { s: Path, d: Path, o: Array<out CopyOption> ->
                    calls++
                    if (calls == 1) throw AtomicMoveNotSupportedException(s.toString(), d.toString(), "injected")
                    Files.move(s, d, *o)
                },
            )
            assertEquals(ScopedFileStore.WriteOutcome.Created, outcome)
            // fallback 重試成功落盤（兩次 move；gates 重跑由結構守衛＋review 驗證，
            // 本案僅鎖重試＋落盤＋無暫留）。
            assertEquals(2, calls)
            assertEquals("hello", File(ws, "t.txt").readText())
            val tmps = (ws.listFiles()?.toList() ?: emptyList())
                .filter { it.isFile && it.name.startsWith(ScopedFileStore.TMP_NAME_PREFIX) }
            assertTrue("fallback must leave no tmp, got $tmps", tmps.isEmpty())
        } finally {
            ws.deleteRecursively()
        }
    }

    @Test fun writeFallbackNonAtomicErrorFailsClosedWithoutTmpOrOverwrite() {
        val ws = workspace()
        try {
            val store = ScopedFileStore(ws)
            store.write("t.txt", "orig".toByteArray())
            var calls = 0
            try {
                store.writeInternal(
                    "t.txt",
                    "new".toByteArray(),
                    moveFn = { _: Path, _: Path, _: Array<out CopyOption> ->
                        calls++
                        throw IOException("injected")
                    },
                )
                fail("expected IOException from moveFn")
            } catch (_: IOException) {
                // 預期向外拋（fail-closed；舊廣 catch 會重試，本案 calls==1 鎖定收窄）。
            }
            assertEquals(1, calls)
            // tmp 已清＋target 未被覆寫。
            val tmps = (ws.listFiles()?.toList() ?: emptyList())
                .filter { it.isFile && it.name.startsWith(ScopedFileStore.TMP_NAME_PREFIX) }
            assertTrue("failed move must leave no tmp, got $tmps", tmps.isEmpty())
            assertEquals("orig", File(ws, "t.txt").readText())
        } finally {
            ws.deleteRecursively()
        }
    }

    private fun findStoreSource(): File? {
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
        return null
    }
}
