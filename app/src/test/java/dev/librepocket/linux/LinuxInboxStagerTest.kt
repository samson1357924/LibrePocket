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

    @Test fun collectFile_outboxSymlinkEscape_denied() {
        // PR#1 comment 6038761291 blocker 1：rootfs/outbox -> <rootfs 外> 目錄
        // symlink 時，canonical 塌縮會讓舊 `parent == outbox` 恆成立；
        // 新門禁要求 outbox 根 canonical 嚴格等於 rootfs/outbox。
        val rootfs = File(LinuxEnv.containerRootfs(filesDir, "alpine")).apply { mkdirs() }
        val outside = File(treeRoot, "outside-secret").apply { mkdirs() }
        File(outside, "secret.txt").writeText("host-secret")
        val outboxLink = File(rootfs, "outbox")
        // 若已存在實目錄（前序測試建立），先清空再換成 symlink。
        if (outboxLink.isDirectory && !java.nio.file.Files.isSymbolicLink(outboxLink.toPath())) {
            outboxLink.deleteRecursively()
        }
        if (!outboxLink.exists()) {
            java.nio.file.Files.createSymbolicLink(outboxLink.toPath(), outside.toPath())
        } else if (!java.nio.file.Files.isSymbolicLink(outboxLink.toPath())) {
            // 已是實目錄且非空：改用新容器名隔離此逃逸場景。
            val altOutbox = LinuxInboxStager.guestOutboxDir(filesDir, "evil").apply { parentFile.mkdirs() }
            altOutbox.parentFile.mkdirs()
            File(LinuxEnv.containerRootfs(filesDir, "evil")).mkdirs()
            val altLink = File(LinuxEnv.containerRootfs(filesDir, "evil"), "outbox")
            if (altLink.exists()) altLink.deleteRecursively()
            java.nio.file.Files.createSymbolicLink(altLink.toPath(), outside.toPath())
            val r = LinuxInboxStager.collectFile(filesDir, "evil", "/outbox/secret.txt")
            assertTrue("expected Denied, got $r", r is LinuxInboxStager.CollectOutcome.Denied)
            assertEquals("OUTBOX_ESCAPES_ROOTFS", (r as LinuxInboxStager.CollectOutcome.Denied).code)
            assertTrue(!File(LinuxInboxStager.hostOutboxDir(filesDir), "secret.txt").exists())
            return
        }
        val r = LinuxInboxStager.collectFile(filesDir, "alpine", "/outbox/secret.txt")
        assertTrue("expected Denied, got $r", r is LinuxInboxStager.CollectOutcome.Denied)
        assertEquals("OUTBOX_ESCAPES_ROOTFS", (r as LinuxInboxStager.CollectOutcome.Denied).code)
        assertTrue(!File(LinuxInboxStager.hostOutboxDir(filesDir), "secret.txt").exists())
    }

    @Test fun collectFile_deniedNearTotalQuota_noCopy() {
        // PR#1 comment 6038761291 blocker 4：collect 是複製（rootfs/outbox ->
        // linux/tmp/outbox，兩端同屬 linux 樹），必須納入 TOTAL/CONTAINER 配額。
        val outbox = LinuxInboxStager.guestOutboxDir(filesDir, "quota").apply { mkdirs() }
        // 若 outbox 曾被前一測試換成 symlink，先恢復成實目錄。
        if (java.nio.file.Files.isSymbolicLink(File(LinuxEnv.containerRootfs(filesDir, "quota"), "outbox").toPath())) {
            File(LinuxEnv.containerRootfs(filesDir, "quota"), "outbox").deleteRecursively()
            outbox.mkdirs()
        }
        File(outbox, "victim.bin").writeBytes(ByteArray(8 * 1024) { 1 })
        val nearTotal = LinuxInboxStager.collectFile(
            filesDir, "quota", "/outbox/victim.bin",
            usedTotalBytes = LinuxEnv.TOTAL_BYTES - 1024,
        )
        assertTrue("expected Denied, got $nearTotal", nearTotal is LinuxInboxStager.CollectOutcome.Denied)
        assertEquals("TOTAL_QUOTA_4G", (nearTotal as LinuxInboxStager.CollectOutcome.Denied).code)
        assertTrue(!File(LinuxInboxStager.hostOutboxDir(filesDir), "victim.bin").exists())
        // 邊界：usedTotal + size == TOTAL 即放行（此容器用獨立檔名避免污染）。
        File(outbox, "edge.bin").writeBytes(ByteArray(1024) { 2 })
        val edge = LinuxInboxStager.collectFile(
            filesDir, "quota", "/outbox/edge.bin",
            usedTotalBytes = LinuxEnv.TOTAL_BYTES - 1024,
        )
        assertTrue("expected Collected, got $edge", edge is LinuxInboxStager.CollectOutcome.Collected)
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
                env: Map<String, String>?,
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

    // ---- TOCTOU（PR#1 comment 6039436820 P1）：check-then-use 封閉 ----

    @Test fun stageFile_symlinkSource_deniedWithoutCopy() {
        // inbox 內 symlink 源直拒（不跟隨到 rootfs 外敏感檔）。
        val outside = File(treeRoot, "stage-symlink-outside.txt").apply { writeText("host-secret") }
        val inbox = File(LinuxEnv.inboxDir(filesDir)).apply { mkdirs() }
        val link = File(inbox, "link.txt")
        try {
            link.delete()
        } catch (_: Exception) {
        }
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        val r = LinuxInboxStager.stageFile(filesDir, "symlinksrc", link, id = "abcd1234")
        assertTrue("expected Denied, got $r", r is LinuxInboxStager.StageOutcome.Denied)
        assertEquals("NOT_A_FILE", (r as LinuxInboxStager.StageOutcome.Denied).code)
        // 保證沒有把外部敏感內容拷入 rootfs。
        val stagedCandidates = File(LinuxEnv.containerRootfs(filesDir, "symlinksrc") + "/inbox")
            .listFiles()?.toList().orEmpty()
        assertTrue("no exfil copy expected, got $stagedCandidates", stagedCandidates.none { it.readText() == "host-secret" })
        assertTrue(!File(LinuxInboxStager.hostOutboxDir(filesDir), "link.txt").exists())
    }

    @Test fun collectFile_symlinkProduct_deniedWithoutCopy() {
        // guest outbox 內 symlink 產物直拒（不跟隨讀宿主檔）。
        val container = "symlinkcollect"
        val outbox = LinuxInboxStager.guestOutboxDir(filesDir, container).apply { mkdirs() }
        val outside = File(treeRoot, "collect-outside.txt").apply { writeText("collect-secret") }
        val link = File(outbox, "evil.apk")
        try {
            link.delete()
        } catch (_: Exception) {
        }
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        val r = LinuxInboxStager.collectFile(filesDir, container, "/outbox/evil.apk")
        assertTrue("expected Denied, got $r", r is LinuxInboxStager.CollectOutcome.Denied)
        assertEquals("NOT_A_FILE", (r as LinuxInboxStager.CollectOutcome.Denied).code)
        assertTrue(!File(LinuxInboxStager.hostOutboxDir(filesDir), "evil.apk").exists())
    }

    @Test fun stageFile_destPreplantedSymlink_noOverwriteOutside() {
        // 攻擊者預測 id，預植 rootfs/inbox/<id>-<name> symlink 到外部：
        // stage 不得跟隨覆寫外部受害檔。
        val container = "preplant"
        val src = hostInboxFile("victim-src.txt", "good-payload".toByteArray())
        val victimOutside = File(treeRoot, "preplant-victim.txt").apply { writeText("do-not-overwrite") }
        val destDir = File(LinuxEnv.containerRootfs(filesDir, container) + "/inbox").apply { mkdirs() }
        val predicted = File(destDir, "deadbeef-victim-src.txt")
        try {
            predicted.delete()
        } catch (_: Exception) {
        }
        Files.createSymbolicLink(predicted.toPath(), victimOutside.toPath())
        val out = LinuxInboxStager.stageFile(filesDir, container, src, id = "deadbeef")
        // 允許成功（先刪 link 再建 regular file）或明確拒絕，但絕不能覆寫外部。
        assertEquals("do-not-overwrite", victimOutside.readText())
        if (out is LinuxInboxStager.StageOutcome.Staged) {
            assertTrue(!Files.isSymbolicLink(out.hostFile.toPath()))
            assertEquals("good-payload", out.hostFile.readText())
        } else {
            assertTrue(out is LinuxInboxStager.StageOutcome.Denied)
        }
    }

    @Test fun containerLocks_serializeStageCollectAndExec() {
        // 同容器 stage/collect/exec 共用同一鎖：併發跑不應拋、不應逃逸。
        val container = "raceser"
        val src = hostInboxFile("race.txt", "race-payload".toByteArray())
        // 保證 outbox 為實目錄（隔離前序 symlink 污染）。
        val outbox = LinuxInboxStager.guestOutboxDir(filesDir, container).apply { mkdirs() }
        if (Files.isSymbolicLink(File(LinuxEnv.containerRootfs(filesDir, container), "outbox").toPath())) {
            File(LinuxEnv.containerRootfs(filesDir, container), "outbox").deleteRecursively()
            outbox.mkdirs()
        }
        File(outbox, "race.apk").writeBytes("race-artifact".toByteArray())
        // 同 key 同實例，不同容器不同實例。
        assertTrue(LinuxInboxStager.lockFor(filesDir, container) === LinuxInboxStager.lockFor(filesDir, container))
        assertTrue(LinuxInboxStager.lockFor(filesDir, container) !== LinuxInboxStager.lockFor(filesDir, "other"))

        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val start = java.util.concurrent.CountDownLatch(1)
            val done = java.util.concurrent.CountDownLatch(12)
            val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
            repeat(4) { i ->
                pool.submit {
                    try {
                        start.await()
                        val r = LinuxInboxStager.stageFile(filesDir, container, src, id = "race${i}abc")
                        if (r !is LinuxInboxStager.StageOutcome.Staged && r !is LinuxInboxStager.StageOutcome.Denied) {
                            error("unexpected $r")
                        }
                    } catch (t: Throwable) {
                        errors.add(t)
                    } finally {
                        done.countDown()
                    }
                }
                pool.submit {
                    try {
                        start.await()
                        val r = LinuxInboxStager.collectFile(filesDir, container, "/outbox/race.apk")
                        if (r !is LinuxInboxStager.CollectOutcome.Collected && r !is LinuxInboxStager.CollectOutcome.Denied) {
                            error("unexpected $r")
                        }
                    } catch (t: Throwable) {
                        errors.add(t)
                    } finally {
                        done.countDown()
                    }
                }
                pool.submit {
                    try {
                        start.await()
                        val runner = object : dev.librepocket.shell.ProcessRunner {
                            override fun run(
                                argv: List<String>,
                                timeoutMs: Long,
                                env: Map<String, String>?,
                            ): dev.librepocket.shell.RawOutput {
                                Thread.sleep(5)
                                return dev.librepocket.shell.RawOutput("ok".toByteArray(), ByteArray(0), 0, false)
                            }
                        }
                        val r = ProotExec.execute(
                            listOf("echo", "hi"), filesDir, container, Flavor.GITHUB, true, runner,
                        )
                        if (r !is ShellResult.Ok && r !is ShellResult.Denied) {
                            error("unexpected $r")
                        }
                    } catch (t: Throwable) {
                        errors.add(t)
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("concurrent errors: $errors", errors.isEmpty())
        } finally {
            pool.shutdownNow()
        }
    }
}
