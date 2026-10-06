package dev.librepocket.files

import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * D05 檔案域測試（BACKLOG D05 驗收方向）：
 * play 拒跨域；檔名 fuzz（中文/空格/長路徑）；寫入冪等。
 */
class FileScopeTest {

    private val privateRoot = "/data/data/dev.librepocket.agent/files"
    private val safTree = "/tree/primary:Download/docs"

    private fun tempRoot(): File =
        Files.createTempDirectory("file-scope").toFile().apply { deleteOnExit() }

    // ---- 域裁決 ----

    @Test fun privatePathAllowed() {
        val d = FileScope.decide(
            "$privateRoot/chat/session.jsonl",
            privateRoot,
            flavor = Flavor.PLAY,
        )
        assertTrue(d.allowed)
        assertEquals(FileZone.PRIVATE, d.zone)
    }

    @Test fun safGrantedPathAllowed() {
        val d = FileScope.decide(
            "$safTree/report.pdf",
            privateRoot,
            safRoots = listOf(safTree),
            flavor = Flavor.PLAY,
        )
        assertTrue(d.allowed)
        assertEquals(FileZone.SAF_GRANTED, d.zone)
    }

    @Test fun playDeniesCrossDomain() {
        val d = FileScope.decide(
            "/sdcard/Download/other/app.apk",
            privateRoot,
            flavor = Flavor.PLAY,
        )
        assertFalse(d.allowed)
        assertEquals(FileZone.CROSS_DOMAIN, d.zone)
        assertEquals(FileScope.CODE_CROSS_DOMAIN, d.code)
        assertEquals(DenyReason.FLAVOR_BLOCKED, d.denyReason)
    }

    @Test fun privateRootItselfAllowed() {
        val d = FileScope.decide(privateRoot, privateRoot, flavor = Flavor.PLAY)
        assertTrue(d.allowed)
    }

    @Test fun siblingPrefixNotConfusedWithPrivate() {
        // "$privateRoot-evil/..." 僅是字首相同，不是子目錄，必須拒絕。
        val d = FileScope.decide("$privateRoot-evil/x", privateRoot, flavor = Flavor.PLAY)
        assertFalse(d.allowed)
    }

    @Test fun relativePathDenied() {
        val d = FileScope.decide("chat/x.txt", privateRoot, flavor = Flavor.PLAY)
        assertFalse(d.allowed)
    }

    @Test fun fullCrossDomainNeedsBridgeWithoutGrant() {
        val d = FileScope.decide(
            "/sdcard/Download/other/x.txt",
            privateRoot,
            flavor = Flavor.FULL,
            bridgeGranted = false,
        )
        assertFalse(d.allowed)
        assertEquals(FileScope.CODE_NEEDS_BRIDGE, d.code)
        assertEquals(DenyReason.NO_PRIVILEGE, d.denyReason)
    }

    @Test fun fullCrossDomainAllowedOnlyWithBridge() {
        val d = FileScope.decide(
            "/sdcard/Download/other/x.txt",
            privateRoot,
            flavor = Flavor.FULL,
            bridgeGranted = true,
        )
        assertTrue(d.allowed)
        assertTrue(d.needsBridge)
    }

    // ---- 檔名衛生 ----

    @Test fun safeNamesAllowChineseAndSpaces() {
        assertTrue(FileScope.isSafeName("中文 筆記.txt"))
        assertTrue(FileScope.isSafeName("space name .md"))
        assertTrue(FileScope.isSafeName("a".repeat(200) + ".txt"))
        assertFalse(FileScope.isSafeName("../x"))
        assertFalse(FileScope.isSafeName("a/b"))
        assertFalse(FileScope.isSafeName(""))
        assertFalse(FileScope.isSafeName(".."))
    }

    @Test fun traversalRejected() {
        val store = ScopedFileStore(tempRoot())
        for (bad in listOf("../escape.txt", "/abs/path.txt", "a/../../escape.txt", "")) {
            try {
                store.resolve(bad)
                fail("expected reject: $bad")
            } catch (_: IllegalArgumentException) {
                // 預期行為。
            }
        }
    }

    // ---- 檔名 fuzz：中文/空格/長路徑往返 ----

    @Test fun fileNameFuzzRoundTrip() {
        val store = ScopedFileStore(tempRoot())
        val longStem = "深層".repeat(40) // 80 字，中英混排長檔名
        val names = listOf(
            "中文 筆記.txt",
            "space name .md",
            "mixed 中文 space.jsonl",
            "$longStem.txt",
            "巢狀/目 錄/檔名 中文.txt",
            "deep/" + "d".repeat(60) + "/" + "e".repeat(60) + ".bin",
        )
        for (name in names) {
            val payload = "payload:$name".toByteArray(Charsets.UTF_8)
            store.write(name, payload)
            assertEquals(payload.toList(), store.read(name).toList())
        }
        assertEquals(names.size, store.list().size)
    }

    // ---- 寫入冪等 ----

    @Test fun writeIsIdempotent() {
        val root = tempRoot()
        val store = ScopedFileStore(root)
        val name = "中文 冪等.txt"
        val first = "hello".toByteArray()
        assertEquals(ScopedFileStore.WriteOutcome.Created, store.write(name, first))
        val mtime = File(root, name).lastModified()
        assertEquals(ScopedFileStore.WriteOutcome.Unchanged, store.write(name, first))
        assertEquals(mtime, File(root, name).lastModified())
        assertEquals(
            ScopedFileStore.WriteOutcome.Updated,
            store.write(name, "hello2".toByteArray()),
        )
        assertEquals("hello2", String(store.read(name), Charsets.UTF_8))
    }

    @Test fun deleteIsIdempotent() {
        val store = ScopedFileStore(tempRoot())
        store.write("a.txt", "x".toByteArray())
        assertTrue(store.delete("a.txt"))
        // 重複刪除不報錯，回 false。
        assertFalse(store.delete("a.txt"))
    }
}
