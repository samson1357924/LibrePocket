package dev.librepocket.linux

import dev.librepocket.shell.ShellPolicy
import dev.librepocket.shell.ShellResult
import dev.librepocket.shell.Validation
import dev.librepocket.tool.Flavor
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S4 inbox staging 測試（PR#1 re-review head 4e3a6c6 blocker 2 收斂）：
 * 真實暫存目錄（非 FakeRunner 形狀斷言），驗證 stage 落點、
 * guest 可見性、反向取回與 fail-closed 語義。
 */
class LinuxInboxStagerTest {

    private val treeRoot: File = Files.createTempDirectory("inboxStager").toFile()
    private val filesDir: String = File(treeRoot, "files").absolutePath

    private fun hostInboxFile(name: String, bytes: ByteArray): File {
        val inbox = File(LinuxEnv.inboxDir(filesDir))
        assertTrue(inbox.mkdirs() || inbox.isDirectory)
        val f = File(inbox, name)
        f.writeBytes(bytes)
        return f
    }

    // ---- 進件：落點與 guest 路徑 ----

    @Test fun stageFile_landsInsideRootfs_withGuestPath() {
        val src = hostInboxFile("x.txt", "hello-guest".toByteArray())
        val out = LinuxInboxStager.stageFile(filesDir, "alpine", src, id = "1a2b3c4d")
        assertTrue("expected Staged, got $out", out is LinuxInboxStager.StageOutcome.Staged)
        val staged = out as LinuxInboxStager.StageOutcome.Staged
        assertEquals("/inbox/1a2b3c4d-x.txt", staged.guestPath)
        assertEquals(src.length(), staged.sizeBytes)
        // 落點確在 rootfs 內（非 sibling 直傳）。
        val rootfs = File(LinuxEnv.containerRootfs(filesDir, "alpine")).canonicalFile
        assertTrue(staged.hostFile.canonicalPath.startsWith(rootfs.path + File.separator))
        assertEquals("hello-guest", staged.hostFile.readText())
    }

    @Test fun stagedGuestPath_resolvesInsideRootfs_whileHostInboxPathDoesNot() {
        // 本輪 blocker 的根因：host inbox 是 rootfs 的 sibling，
        // proot -r 下 guest 絕對路徑一律掛到 rootfs 之下解析。
        val src = hostInboxFile("y.txt", "payload".toByteArray())
        val staged = LinuxInboxStager.stageFile(filesDir, "alpine", src, id = "deadbeef")
            as LinuxInboxStager.StageOutcome.Staged
        val rootfs = File(LinuxEnv.containerRootfs(filesDir, "alpine"))
        // guest 視角：/inbox/<f> → $rootfs/inbox/<f>，真實可讀。
        val guestVisible = File(rootfs, staged.guestPath.removePrefix("/"))
        assertTrue(guestVisible.isFile)
        assertEquals("payload", guestVisible.readText())
        // 反例：host inbox 絕對路徑在 guest 視角下 → $rootfs/$hostPath，不存在。
        val hostPathAsSeenByGuest = File(rootfs, src.canonicalPath.removePrefix("/"))
        assertTrue(!hostPathAsSeenByGuest.exists())
    }

    @Test fun stageFile_rejectsNonInboxSource_traversal_badContainer_oversize() {
        val outside = File(treeRoot, "evil.txt").apply { writeText("evil") }
        val r1 = LinuxInboxStager.stageFile(filesDir, "alpine", outside, id = "aaaaaaaa")
        assertTrue(r1 is LinuxInboxStager.StageOutcome.Denied)
        assertEquals("NOT_INBOX_SOURCE", (r1 as LinuxInboxStager.StageOutcome.Denied).code)
        // 目錄 traversal：inbox 內子目錄 ../ 逃逸仍擋。
        val sub = File(LinuxEnv.inboxDir(filesDir) + "/sub").apply { mkdirs() }
        assertTrue(sub.isDirectory)
        val r2 = LinuxInboxStager.stageFile(filesDir, "../evil", hostInboxFile("z.txt", "z".toByteArray()))
        assertTrue(r2 is LinuxInboxStager.StageOutcome.Denied)
        // 壞檔名 id。
        val r3 = LinuxInboxStager.stageFile(filesDir, "alpine", hostInboxFile("w.txt", "w".toByteArray()), id = "../x")
        assertTrue(r3 is LinuxInboxStager.StageOutcome.Denied)
        assertEquals("BAD_STAGE_ID", (r3 as LinuxInboxStager.StageOutcome.Denied).code)
        // 超 10 MiB（稀疏檔，不實際寫 10M）。
        val bigPath = File(LinuxEnv.inboxDir(filesDir), "big.bin")
        java.io.RandomAccessFile(bigPath, "rw").use { it.setLength(LinuxEnv.INBOX_MAX_BYTES + 1) }
        val r4 = LinuxInboxStager.stageFile(filesDir, "alpine", bigPath)
        assertTrue(r4 is LinuxInboxStager.StageOutcome.Denied)
        assertEquals("INBOX_TOO_LARGE", (r4 as LinuxInboxStager.StageOutcome.Denied).code)
        // 配額：已用 + 本次超 2G 單容器上限。
        val r5 = LinuxInboxStager.stageFile(
            filesDir, "alpine", hostInboxFile("q.txt", "q".toByteArray()),
            usedContainerBytes = LinuxEnv.PER_CONTAINER_BYTES,
        )
        assertTrue(r5 is LinuxInboxStager.StageOutcome.Denied)
        assertEquals("CONTAINER_QUOTA_2G", (r5 as LinuxInboxStager.StageOutcome.Denied).code)
    }

    @Test fun safeName_neverAllowsSeparators() {
        assertEquals("x.txt", LinuxInboxStager.safeName("x.txt"))
        assertEquals("a_b", LinuxInboxStager.safeName("a/b"))
        assertEquals("file", LinuxInboxStager.safeName("..."))
        assertEquals("file", LinuxInboxStager.safeName(""))
        assertTrue('/' !in LinuxInboxStager.safeName("../../etc/passwd"))
    }

    // ---- 取回：bounded copy ----

    @Test fun collectFile_roundTrip_bounded() {
        // 模擬 guest 產物：直接寫入 host 側 outbox（真實 proot 下 guest 寫入即落此）。
        val outbox = LinuxInboxStager.guestOutboxDir(filesDir, "alpine").apply { mkdirs() }
        File(outbox, "app.apk").writeBytes("artifact".toByteArray())
        val out = LinuxInboxStager.collectFile(filesDir, "alpine", "/outbox/app.apk")
        assertTrue("expected Collected, got $out", out is LinuxInboxStager.CollectOutcome.Collected)
        val collected = out as LinuxInboxStager.CollectOutcome.Collected
        assertEquals("artifact", collected.hostFile.readText())
        assertTrue(collected.hostFile.path.startsWith(LinuxInboxStager.hostOutboxDir(filesDir).path))
        // 超上限拒。
        val denied = LinuxInboxStager.collectFile(filesDir, "alpine", "/outbox/app.apk", maxBytes = 2)
        assertTrue(denied is LinuxInboxStager.CollectOutcome.Denied)
        assertEquals("OUTPUT_TOO_LARGE", (denied as LinuxInboxStager.CollectOutcome.Denied).code)
        // 非 /outbox 一律拒（/inbox 輸入、巢狀、traversal）。
        for (p in listOf("/inbox/app.apk", "/outbox/a/b.apk", "/outbox/../x", "/etc/passwd", "/outbox/")) {
            val r = LinuxInboxStager.collectFile(filesDir, "alpine", p)
            assertTrue("expected Denied for $p, got $r", r is LinuxInboxStager.CollectOutcome.Denied)
        }
    }

    @Test fun unstagedInboxRef_normalizesVariants() {
        val inbox = LinuxEnv.inboxDir(filesDir)
        assertTrue(LinuxInboxStager.unstagedInboxRef(listOf("cat", "$inbox/x.txt"), filesDir) != null)
        assertTrue(LinuxInboxStager.unstagedInboxRef(listOf("cat", "$inbox//x.txt"), filesDir) != null)
        assertTrue(LinuxInboxStager.unstagedInboxRef(listOf("cat", "--file=$inbox/x.txt"), filesDir) != null)
        assertEquals(null, LinuxInboxStager.unstagedInboxRef(listOf("cat", "/inbox/x.txt"), filesDir))
        assertEquals(null, LinuxInboxStager.unstagedInboxRef(listOf("echo", "hi"), filesDir))
    }

    // ---- Guest 路徑政策（ShellPolicy 層） ----

    @Test fun guestInternalAbsolutePaths_allowed_selectively() {
        val tree = LinuxEnv.root(filesDir)
        // stage 後的 guest 路徑 + 容器內系統路徑：放行。
        for (argv in listOf(listOf("cat", "/inbox/1a2b3c4d-x.txt"), listOf("ls", "/etc"))) {
            val v = ShellPolicy.validate(argv, privateRoot = tree, flavor = Flavor.GITHUB, isGuest = true)
            assertTrue("expected Allowed for $argv, got $v", v is Validation.Allowed)
        }
        // null 作用域：guest 絕對路徑仍 fail-closed。
        val nullScoped = ShellPolicy.validate(
            listOf("cat", "/inbox/1a2b3c4d-x.txt"), privateRoot = null, isGuest = true,
        )
        assertTrue("expected Denied, got $nullScoped", nullScoped is Validation.Denied)
        // 直接通道不受影響：宿主樹外絕對路徑仍拒。
        val direct = ShellPolicy.validate(listOf("cat", "/inbox/x.txt"), privateRoot = tree)
        assertTrue("expected Denied, got $direct", direct is Validation.Denied)
        // 宿主外掛點在 guest 內保留拒絕。
        val ext = ShellPolicy.validate(
            listOf("cat", "/sdcard/Download/x.txt"), privateRoot = tree,
            flavor = Flavor.GITHUB, isGuest = true,
        )
        assertTrue("expected Denied, got $ext", ext is Validation.Denied)
    }

    @Test fun stagedGuestArgv_executesThroughProotCmdShape() {
        // stage → guest argv → ProotExec：FakeRunner 只驗形狀，
        // 真實可達性由 stagedGuestPath_resolvesInsideRootfs_whileHostInboxPathDoesNot 保證。
        val src = hostInboxFile("exec.txt", "data".toByteArray())
        val staged = LinuxInboxStager.stageFile(filesDir, "alpine", src, id = "feedface")
            as LinuxInboxStager.StageOutcome.Staged
        var spawned: List<String>? = null
        val runner = object : dev.librepocket.shell.ProcessRunner {
            override fun run(
                argv: List<String>,
                timeoutMs: Long,
            ): dev.librepocket.shell.RawOutput {
                spawned = argv
                return dev.librepocket.shell.RawOutput("ok".toByteArray(), ByteArray(0), 0, false)
            }
        }
        val result = ProotExec.execute(
            listOf("cat", staged.guestPath), filesDir, "alpine", Flavor.GITHUB, true, runner,
        )
        assertTrue("expected Ok, got $result", result is ShellResult.Ok)
        val cmd = spawned!!
        assertEquals(LinuxEnv.prootBin(filesDir), cmd[0])
        assertEquals(
            listOf("-r", LinuxEnv.containerRootfs(filesDir, "alpine")) + listOf("-w", "/", "-0"),
            cmd.subList(1, 6),
        )
        assertEquals(listOf("cat", staged.guestPath), cmd.takeLast(2))
        assertTrue(cmd.none { it == "-b" || it == "--bind" })
    }
}
