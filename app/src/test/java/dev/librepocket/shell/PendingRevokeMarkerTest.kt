package dev.librepocket.shell

import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * #136-B1 P1：[PendingRevokeMarker] fail-open 修復（撤權 journal 配套）測試。
 * 純 JVM＋暫存檔，不碰真機／真 DataStore。
 */
class PendingRevokeMarkerTest {

    private lateinit var dir: File

    @Before fun setUp() {
        dir = kotlin.io.path.createTempDirectory("pending-revoke-marker-test").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    /** exists()/isFile()/delete() 皆拋 SecurityException 的 File 子類（File 非 final 可 subclass）。 */
    private open class ThrowingFile(pathname: String) : File(pathname) {
        override fun exists(): Boolean = throw SecurityException("exists boom")
        override fun isFile(): Boolean = throw SecurityException("isFile boom")
        override fun delete(): Boolean = throw SecurityException("delete boom")
    }

    @Test fun inMemoryMode_markPendingThenIsPendingTrue_clearPendingFalse() {
        // file == null：純記憶體模式。
        val marker = PendingRevokeMarker(null)
        assertFalse(marker.isPending())
        assertTrue("in-memory mode markPending must return true", marker.markPending())
        assertTrue(marker.isPending())
        marker.clearPending()
        assertFalse(marker.isPending())
    }

    @Test fun isPending_filesystemSecurityException_returnsTrue() {
        // 檔案系統/安全例外 → fail-closed 回 true（grant 不得被載入）。
        val marker = PendingRevokeMarker(ThrowingFile(File(dir, "x.pending").path))
        assertTrue(marker.isPending())
    }

    @Test fun markPending_parentIsRegularFile_returnsFalse() {
        // 父路徑為「普通檔案」（mkdirs 必敗）→ 落盤失敗回 false；
        // 記憶體旗標已置，isPending() 仍為 true（同行程 load 必 fail-closed）。
        val regularParent = File(dir, "regular_parent")
        assertTrue(regularParent.createNewFile())
        val marker = PendingRevokeMarker(File(regularParent, "x.pending"))
        assertFalse("parent is a regular file must fail to persist marker", marker.markPending())
        assertTrue("in-memory flag must still be set after failed persist", marker.isPending())
    }

    @Test fun markPending_markerPathIsDirectory_returnsFalse() {
        // marker 路徑已是目錄：createNewFile() 回 false、isFile 亦 false → 落盤失敗回 false。
        val markerPath = File(dir, "dir_marker")
        assertTrue(markerPath.mkdirs())
        val marker = PendingRevokeMarker(markerPath)
        assertFalse(
            "marker path being a directory can never host a marker FILE → false",
            marker.markPending(),
        )
        // 記憶體旗標已置（OR 語義）→ isPending() == true；
        // 但 file 分支（isFile==false）獨立看則為 false——OR 後記憶體贏。
        assertTrue(
            "in-memory flag OR file branch: memory wins → pending",
            marker.isPending(),
        )
    }

    @Test fun isPending_fileIsDirectory_isFalse_butInMemoryTrue() {
        // fresh marker（記憶體 false）＋路徑為目錄 → isPending() == false
        //（不再被 exists()==true 誤導為永久 pending——反向失效已修）。
        val markerPath = File(dir, "dir_marker")
        assertTrue(markerPath.mkdirs())
        val fresh = PendingRevokeMarker(markerPath)
        assertFalse("directory is not a marker file → not pending (file branch)", fresh.isPending())

        // 同一路徑呼叫 markPending()：記憶體旗標置位（回傳 false）；isPending() 因 OR 語義翻 true。
        assertFalse(fresh.markPending())
        assertTrue("in-memory true OR file false → true", fresh.isPending())
    }

    @Test fun clearPending_deleteFails_doesNotThrow() {
        // delete() 拋例外 → best-effort 吞掉，不得上抛。
        val marker = PendingRevokeMarker(ThrowingFile(File(dir, "x.pending").path))
        try {
            marker.clearPending()
        } catch (t: Throwable) {
            fail("clearPending must swallow exceptions, got: $t")
        }
    }

    @Test fun clearPending_nonEmptyDirectoryMarker_doesNotThrow() {
        // marker 路徑為非空目錄：exists()==true 但 delete() 失敗（回 false）→ 不拋。
        val markerPath = File(dir, "dir_marker")
        assertTrue(markerPath.mkdirs())
        assertTrue(File(markerPath, "child").createNewFile())
        val marker = PendingRevokeMarker(markerPath)
        try {
            marker.clearPending()
        } catch (t: Throwable) {
            fail("clearPending must swallow exceptions, got: $t")
        }
        assertTrue("delete of non-empty directory must fail silently", markerPath.exists())
    }
}
