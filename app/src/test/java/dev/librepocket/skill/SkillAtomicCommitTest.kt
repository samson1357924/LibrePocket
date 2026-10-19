package dev.librepocket.skill

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 原子提交測試：覆蓋 [SkillInstaller.installFiles] 的原子提交區。
 *
 * - replace 成功：新版完整、無 `.tmp`/`.bak` 殘留；
 * - replace 失敗回滾：staging→target 失敗時舊版位元組一致、無半成品、`.bak` 被消費
 *   （回傳 false 與拋 SecurityException 兩種失敗語義皆覆蓋，雙重失敗訊息含根因與 backup 名）；
 * - backup 步失敗：舊版原地、staging 已清、訊息含 `backup old version`；
 * - 回滾亦失敗：訊息含 `backup kept at`、`.bak` 保留舊版；
 * - 新鮮安裝 rename 失敗：無 target、staging 已清；
 * - staging 寫入階段失敗（[SkillInstaller.writeHook] 注入）：新鮮安裝無
 *   target、覆蓋保留舊版位元組（backup 階段尚未開始）、staging 已清；
 * - 清理 attempt 預算（P2-1：上限計工作嘗試、失敗亦計入，見 SkillInstaller
 *   兩預算常量 KDoc）：還原／刪除 attempt 以 [SkillInstaller.cleanupAttemptsForTest]
 *   測試縫與 [SkillInstaller.deleteHook]（skip-fail 語意）注入釘死
 *   （restoreAttempts_／proofAttempts_／deleteAttempts_ 三釘）；
 * - 併發安裝同一 skill 序列化（per-skill 鎖 + hook 內最大並發斷言；
 *   新鮮安裝同 id 恰一勝出、其餘 ALREADY_EXISTS）；
 * - 重啟清理：過期 staging／孤兒 bak 收斂、嚴格檔名形狀、崩潰三窗口調 cleanup 即恢復、
 *   同 id 多份 orphan backup 歧義全保留、還原內容校驗（SKILL.md name==bakId，
 *   不符或不可解析者 KEPT 待人工）；
 * - 降版揭露不擋：覆蓋成功帶回 replacedVersion（舊版無 version/新鮮安裝為 null）、
 *   [SkillInstaller.installedVersion] 缺席/損毀回 null；
 * - 殘餘觀察：[SkillInstaller.listResiduals] 只列不刪（含歧義保留候選），
 *   計數與清理一致；
 *
 * Skip 語義：symlink 案例以 [org.junit.Assume.assumeNoException] 在不支援
 * symlink 的平台上跳過（未特權的 Windows FS 等）；destRootUnreadableAttrs_convergesToIoError
 * 另以 POSIX 權限探測＋非 root 閘 assume-skip（屬性判定分支本身無 JVM 注入縫）。
 * 綠但帶 skip 的 run 並非
 * symlink／屬性分支全覆蓋證明；CI 的 Linux runner 均可建 symlink，正式 gating 以
 * CI 執行為準。
 *
 * 測試 hook 採委託式：只擋指定 rename，其餘走真實 `from.renameTo(to)`，
 * 禁止「hook 回 true 卻沒搬」的空轉假成功。
 */
class SkillAtomicCommitTest {

    @After fun resetHooks() {
        SkillInstaller.renameHook = null
        SkillInstaller.writeHook = null
        SkillInstaller.deleteHook = null
    }

    private fun skillMd(
        name: String = "atomic-skill",
        body: String = "正文 v1",
        version: String? = null,
    ): String {
        // 顯式組行（不用 trimIndent 模板：條件行的縮排會破壞共同縮排計算）。
        val front = buildList {
            add("name: $name")
            add("description: 原子提交測試片段")
            add("allowed-tools: [calendar.create, alarm.create]")
            add("permissions: [needs calendar read]")
            if (version != null) add("version: $version")
        }.joinToString("\n")
        return "---\n$front\n---\n$body"
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun tempRoot(): File =
        java.nio.file.Files.createTempDirectory("skill-atomic").toFile().apply { deleteOnExit() }

    /**
     * 委託式 hook：命中 [failWhen] 的 rename 才失敗，其餘走真實搬移。
     * production 的 `renameHook?.invoke ?: renameTo` 語義不動。
     */
    private fun hookOnly(failWhen: (from: File, to: File) -> Boolean): (File, File) -> Boolean =
        { from, to ->
            if (failWhen(from, to)) false else from.renameTo(to)
        }

    private fun installV1(root: File, name: String = "atomic-skill", body: String = "正文 v1") {
        val v1 = zipOf("$name/SKILL.md" to skillMd(name = name, body = body).toByteArray(Charsets.UTF_8))
        assertTrue(SkillInstaller.installZip(v1, root) is SkillInstaller.Result.Success)
    }

    @Test fun atomicReplaceSuccess_leavesNoLeftovers() {
        val root = tempRoot()
        try {
            installV1(root)
            val v2 = zipOf(
                "atomic-skill/SKILL.md" to skillMd(body = "正文 v2").toByteArray(Charsets.UTF_8),
                "atomic-skill/refs/guide.md" to "新版附檔".toByteArray(Charsets.UTF_8),
            )
            val r2 = SkillInstaller.installZip(v2, root, replace = true)
            assertTrue("expected success, got $r2", r2 is SkillInstaller.Result.Success)
            assertTrue(
                File(root, "atomic-skill/SKILL.md").readText(Charsets.UTF_8).contains("正文 v2"),
            )
            assertTrue(File(root, "atomic-skill/refs/guide.md").isFile)
            val leftovers = root.listFiles()!!.filter { it.name.startsWith(".atomic-skill") }
            assertTrue("installer leftovers: $leftovers", leftovers.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun replaceStagingRenameFailure_rollsBack_oldBytesIntact_noHalfProduct() {
        val root = tempRoot()
        try {
            installV1(root)
            val before = File(root, "atomic-skill/SKILL.md").readBytes()

            // 只擋 staging→target；target→backup 與回滾走真實搬移。
            SkillInstaller.renameHook = hookOnly { from, _ -> from.name.contains(".tmp-") }
            val v2 = zipOf(
                "atomic-skill/SKILL.md" to skillMd(body = "正文 v2 半成品").toByteArray(Charsets.UTF_8),
                "atomic-skill/refs/new.md" to "半成品附檔".toByteArray(Charsets.UTF_8),
            )
            val r = SkillInstaller.installZip(v2, root, replace = true)
            assertTrue("expected failure, got $r", r is SkillInstaller.Result.Failure)
            val f = r as SkillInstaller.Result.Failure
            assertEquals(SkillInstaller.Failure.IO_ERROR, f.reason)
            assertTrue("message should name from→to: ${f.message}", "rename staging" in f.message)
            assertTrue("message should confirm rollback: ${f.message}", "rolled back" in f.message)

            // 舊版位元組一致、target 無半成品、staging 與 .bak 皆被消費。
            assertTrue(File(root, "atomic-skill/SKILL.md").readBytes().contentEquals(before))
            assertFalse(File(root, "atomic-skill/refs/new.md").exists())
            val stagingLeft = root.listFiles()!!.filter { it.name.contains(".tmp-") }
            assertTrue("staging leftovers: $stagingLeft", stagingLeft.isEmpty())
            val bakLeft = root.listFiles()!!.filter { it.name.contains(".bak-") }
            assertTrue("bak leftovers: $bakLeft", bakLeft.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun replaceBackupFailure_oldStaysStagingClean() {
        val root = tempRoot()
        try {
            installV1(root)
            val before = File(root, "atomic-skill/SKILL.md").readBytes()

            // 只擋 target→backup（to 為 .bak 形狀）；其餘真實搬移。
            SkillInstaller.renameHook = hookOnly { _, to -> to.name.contains(".bak-") }
            val v2 = zipOf(
                "atomic-skill/SKILL.md" to skillMd(body = "正文 v2").toByteArray(Charsets.UTF_8),
            )
            val r = SkillInstaller.installZip(v2, root, replace = true)
            assertTrue("expected failure, got $r", r is SkillInstaller.Result.Failure)
            val f = r as SkillInstaller.Result.Failure
            assertEquals(SkillInstaller.Failure.IO_ERROR, f.reason)
            assertTrue("message should mention backup: ${f.message}", "backup old version" in f.message)

            // 舊版原地、staging 已清、無 .bak 殘留。
            assertTrue(File(root, "atomic-skill/SKILL.md").readBytes().contentEquals(before))
            assertTrue("staging or bak left: ${root.listFiles()!!.toList()}", root.listFiles()!!.none {
                it.name.contains(".tmp-") || it.name.contains(".bak-")
            })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun replaceRollbackFailure_reportsBackupLocationAndKeepsBak() {
        val root = tempRoot()
        try {
            installV1(root)
            val before = File(root, "atomic-skill/SKILL.md").readBytes()

            // target→backup 真實搬移；staging→target 與 bak→target 皆失敗。
            SkillInstaller.renameHook = hookOnly { from, _ ->
                from.name.contains(".tmp-") || from.name.contains(".bak-")
            }
            val v2 = zipOf(
                "atomic-skill/SKILL.md" to skillMd(body = "正文 v2").toByteArray(Charsets.UTF_8),
            )
            val r = SkillInstaller.installZip(v2, root, replace = true)
            assertTrue("expected failure, got $r", r is SkillInstaller.Result.Failure)
            val f = r as SkillInstaller.Result.Failure
            assertEquals(SkillInstaller.Failure.IO_ERROR, f.reason)
            assertTrue("message should keep backup location: ${f.message}", "backup kept at" in f.message)

            // target 缺席、staging 已清、.bak 保留舊版位元組。
            assertFalse(File(root, "atomic-skill").exists())
            assertTrue(root.listFiles()!!.none { it.name.contains(".tmp-") })
            val baks = root.listFiles()!!.filter { it.name.contains(".bak-") }
            assertEquals("expected exactly one retained bak: $baks", 1, baks.size)
            assertTrue(baks.single().resolve("SKILL.md").readBytes().contentEquals(before))

            // hook 復位後調 cleanup：崩潰窗口可恢復為舊版。
            SkillInstaller.renameHook = null
            val removed = SkillInstaller.cleanupStaleStaging(root, maxAgeMillis = 0L)
            assertEquals(1, removed)
            assertTrue(File(root, "atomic-skill/SKILL.md").readBytes().contentEquals(before))
            SkillParser.parse(File(root, "atomic-skill/SKILL.md").readText(Charsets.UTF_8))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun replaceStagingRenameThrows_rollsBack_oldBytesIntact() {
        val root = tempRoot()
        try {
            installV1(root)
            val before = File(root, "atomic-skill/SKILL.md").readBytes()

            // P1-1：第二次 rename 拋 SecurityException（非回傳 false）。
            // target→backup 與回滾走真實搬移。
            SkillInstaller.renameHook = { from, to ->
                if (from.name.contains(".tmp-")) throw SecurityException("denied staging commit")
                else from.renameTo(to)
            }
            val v2 = zipOf(
                "atomic-skill/SKILL.md" to skillMd(body = "正文 v2 半成品").toByteArray(Charsets.UTF_8),
                "atomic-skill/refs/new.md" to "半成品附檔".toByteArray(Charsets.UTF_8),
            )
            val r = SkillInstaller.installZip(v2, root, replace = true)
            assertTrue("expected failure, got $r", r is SkillInstaller.Result.Failure)
            val f = r as SkillInstaller.Result.Failure
            assertEquals(SkillInstaller.Failure.IO_ERROR, f.reason)
            assertTrue("message should name from→to: ${f.message}", "rename staging" in f.message)
            assertTrue("message should confirm rollback: ${f.message}", "rolled back" in f.message)

            // 舊版位元組一致、target 無半成品、staging 與 .bak 皆被消費。
            assertTrue(File(root, "atomic-skill/SKILL.md").readBytes().contentEquals(before))
            assertFalse(File(root, "atomic-skill/refs/new.md").exists())
            val stagingLeft = root.listFiles()!!.filter { it.name.contains(".tmp-") }
            assertTrue("staging leftovers: $stagingLeft", stagingLeft.isEmpty())
            val bakLeft = root.listFiles()!!.filter { it.name.contains(".bak-") }
            assertTrue("bak leftovers: $bakLeft", bakLeft.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun replaceStagingRenameThrowsAndRollbackThrows_reportsBackupKept() {
        val root = tempRoot()
        try {
            installV1(root)
            val before = File(root, "atomic-skill/SKILL.md").readBytes()

            // P1-1 雙重失敗：target→backup 真實搬移；staging→target 與 bak→target 皆拋例外。
            SkillInstaller.renameHook = { from, to ->
                if (to.name.contains(".bak-")) from.renameTo(to)
                else throw SecurityException("denied commit and rollback")
            }
            val v2 = zipOf(
                "atomic-skill/SKILL.md" to skillMd(body = "正文 v2").toByteArray(Charsets.UTF_8),
            )
            val r = SkillInstaller.installZip(v2, root, replace = true)
            assertTrue("expected failure, got $r", r is SkillInstaller.Result.Failure)
            val f = r as SkillInstaller.Result.Failure
            assertEquals(SkillInstaller.Failure.IO_ERROR, f.reason)
            assertTrue("message should keep backup location: ${f.message}", "backup kept at" in f.message)
            assertTrue("message should carry root cause: ${f.message}", "denied commit" in f.message)
            assertTrue("message should mark rollback failure: ${f.message}", "rollback failed" in f.message)
            val baks = root.listFiles()!!.filter { it.name.contains(".bak-") }
            assertEquals("expected exactly one retained bak: $baks", 1, baks.size)
            assertTrue(
                "message should name retained bak: ${f.message}",
                baks.single().name in f.message,
            )

            // target 缺席（雙重失敗）、staging 已清、.bak 保留舊版位元組。
            assertFalse(File(root, "atomic-skill").exists())
            assertTrue(root.listFiles()!!.none { it.name.contains(".tmp-") })
            assertTrue(baks.single().resolve("SKILL.md").readBytes().contentEquals(before))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun multipleExpiredBaksSameId_keptAmbiguous_noRestore() {
        val root = tempRoot()
        try {
            // P1-2：同 id 兩份不同內容 expired .bak + target 缺席。
            // nanoTime 後綴非 wall-clock，不可用於選「最新」；歧義時全保留、不還原、不刪除。
            val tid = Thread.currentThread().id
            val bakV1 = File(root, ".dup-skill.bak-111-$tid").apply {
                mkdirs()
                File(this, "SKILL.md").writeText(
                    skillMd(name = "dup-skill", body = "舊版正文 v1"),
                    Charsets.UTF_8,
                )
                setLastModified(System.currentTimeMillis() - 120_000L)
            }
            val bakV2 = File(root, ".dup-skill.bak-999-$tid").apply {
                mkdirs()
                File(this, "SKILL.md").writeText(
                    skillMd(name = "dup-skill", body = "舊版正文 v2"),
                    Charsets.UTF_8,
                )
                setLastModified(System.currentTimeMillis() - 120_000L)
            }
            assertFalse(File(root, "dup-skill").exists())

            val removed = SkillInstaller.cleanupStaleStaging(root, maxAgeMillis = 60_000L)

            // target 仍缺席、兩份 .bak 都保留、計數不含它們。
            assertEquals(0, removed)
            assertFalse(File(root, "dup-skill").exists())
            assertTrue("bakV1 must be kept", bakV1.exists())
            assertTrue("bakV2 must be kept", bakV2.exists())
            assertTrue(bakV1.resolve("SKILL.md").readText(Charsets.UTF_8).contains("舊版正文 v1"))
            assertTrue(bakV2.resolve("SKILL.md").readText(Charsets.UTF_8).contains("舊版正文 v2"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun freshPlusExpiredBakSameId_keptAmbiguous_noRestore() {
        val root = tempRoot()
        try {
            // 一份新鮮 + 一份過期同 id .bak + target 缺席：同樣無法判定，
            // 逕行恢復過期版可能是錯版勝出，故全保留、不還原、不計數。
            val tid = Thread.currentThread().id
            val bakFresh = File(root, ".dup-skill.bak-111-$tid").apply {
                mkdirs()
                File(this, "SKILL.md").writeText(
                    skillMd(name = "dup-skill", body = "舊版正文 fresh"),
                    Charsets.UTF_8,
                )
            }
            val bakOld = File(root, ".dup-skill.bak-999-$tid").apply {
                mkdirs()
                File(this, "SKILL.md").writeText(
                    skillMd(name = "dup-skill", body = "舊版正文 old"),
                    Charsets.UTF_8,
                )
                setLastModified(System.currentTimeMillis() - 120_000L)
            }
            assertFalse(File(root, "dup-skill").exists())

            val removed = SkillInstaller.cleanupStaleStaging(root, maxAgeMillis = 60_000L)

            assertEquals(0, removed)
            assertFalse(File(root, "dup-skill").exists())
            assertTrue("fresh bak must be kept", bakFresh.exists())
            assertTrue("expired bak must be kept", bakOld.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun freshInstallRenameFailure_noTargetLeft() {
        val root = tempRoot()
        try {
            SkillInstaller.renameHook = hookOnly { from, _ -> from.name.contains(".tmp-") }
            val v1 = zipOf("atomic-skill/SKILL.md" to skillMd(body = "正文 v1").toByteArray(Charsets.UTF_8))
            val r = SkillInstaller.installZip(v1, root)
            assertTrue("expected failure, got $r", r is SkillInstaller.Result.Failure)
            val f = r as SkillInstaller.Result.Failure
            assertEquals(SkillInstaller.Failure.IO_ERROR, f.reason)
            assertTrue("message should name from→to: ${f.message}", "rename staging" in f.message)
            assertFalse(File(root, "atomic-skill").exists())
            assertTrue(root.listFiles()!!.none { it.name.contains(".tmp-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun destRootNotCreatable_failsIoError() {
        val root = tempRoot()
        try {
            // destRoot 是既存檔案：staging 無法在其下建目錄，mkdirs 檢查必須報 IO_ERROR。
            val blocker = File(root, "blocker").apply { writeText("x") }
            val v1 = zipOf("atomic-skill/SKILL.md" to skillMd(body = "正文 v1").toByteArray(Charsets.UTF_8))
            val r = SkillInstaller.installZip(v1, blocker)
            assertTrue("expected failure, got $r", r is SkillInstaller.Result.Failure)
            val f = r as SkillInstaller.Result.Failure
            assertEquals(SkillInstaller.Failure.IO_ERROR, f.reason)
            assertTrue("message should name the cause: ${f.message}", "cannot create" in f.message)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun lockFor_sameSkillSameInstance() {
        assertSame(SkillInstaller.lockFor("atomic-skill"), SkillInstaller.lockFor("atomic-skill"))
    }

    @Test fun concurrentReplaceSameSkill_serialized() {
        val root = tempRoot()
        val executor = Executors.newFixedThreadPool(8)
        try {
            installV1(root, name = "concurrent-skill", body = "正文 v1")

            // hook 內進出計數：臨界區最大並發必須 <= 1，其餘走真實搬移。
            val inside = AtomicInteger(0)
            val maxInside = AtomicInteger(0)
            SkillInstaller.renameHook = { from, to ->
                val c = inside.incrementAndGet()
                maxInside.accumulateAndGet(c) { a, b -> maxOf(a, b) }
                try {
                    // 刻意放大競態窗口（有界 5ms，Future.get(60s)兜底；PR body「無 sleep」指無 wall-clock 等待生產邏輯，此處為測試同步手段）。
                    Thread.sleep(5)
                    from.renameTo(to)
                } finally {
                    inside.decrementAndGet()
                }
            }

            val n = 8
            val start = CountDownLatch(1)
            val futures = (0 until n).map { i ->
                executor.submit(
                    Callable<SkillInstaller.Result> {
                        assertTrue("latch hang", start.await(60, TimeUnit.SECONDS))
                        val zip = zipOf(
                            "concurrent-skill/SKILL.md" to
                                skillMd(name = "concurrent-skill", body = "併發正文-$i").toByteArray(Charsets.UTF_8),
                        )
                        SkillInstaller.installZip(zip, root, replace = true)
                    },
                )
            }
            start.countDown()
            val results = futures.map { it.get(60, TimeUnit.SECONDS) }
            assertTrue("expected all success, got $results", results.all { it is SkillInstaller.Result.Success })
            assertEquals("commit critical section interleaved", 1, maxInside.get())

            // 最終為某一個完整版本（可解析、非截斷混合）；8 個 replace 全成功時
            // 終態必須是 8 版之一，不放行 stale v1（無鎖交錯會掉更新或留半成品，
            // 在全成功斷言或此處失敗）。
            val finalText = File(root, "concurrent-skill/SKILL.md").readText(Charsets.UTF_8)
            SkillParser.parse(finalText)
            val hits = (0 until n).count { "併發正文-$it" in finalText }
            assertEquals("final content is not one complete version: $finalText", 1, hits)
            assertFalse("stale v1 should have been replaced: $finalText", "正文 v1" in finalText)
            val leftovers = root.listFiles()!!.filter { it.name.startsWith(".concurrent-skill") }
            assertTrue("installer leftovers: $leftovers", leftovers.isEmpty())
        } finally {
            executor.shutdownNow()
            root.deleteRecursively()
        }
    }

    @Test fun staleStagingCleanup_removesExpiredKeepsFreshAndSkills() {
        val root = tempRoot()
        try {
            installV1(root)
            val tid = Thread.currentThread().id

            val now = System.currentTimeMillis()
            val stale = File(root, ".atomic-skill.tmp-${now * 1000L}-$tid").apply {
                mkdirs()
                File(this, "x").writeText("x")
                setLastModified(now - 120_000L)
            }
            val fresh = File(root, ".atomic-skill.tmp-${System.nanoTime()}-$tid").apply { mkdirs() }
            val staleBak = File(root, ".atomic-skill.bak-${System.nanoTime()}-$tid").apply {
                mkdirs()
                setLastModified(now - 120_000L)
            }

            val removed = SkillInstaller.cleanupStaleStaging(root, maxAgeMillis = 60_000L)
            assertEquals(2, removed)
            assertFalse(stale.exists())
            assertTrue(fresh.exists())
            // target 仍在時 orphan bak 應刪除。
            assertFalse(staleBak.exists())
            assertTrue(File(root, "atomic-skill/SKILL.md").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun staleBakWithoutTarget_restoresOnCleanup() {
        val root = tempRoot()
        try {
            // 模擬崩潰窗口：backup 已搬離、target 缺席（真實 nano-tid 格式名）。
            val tid = Thread.currentThread().id
            val bak = File(root, ".orphan-skill.bak-${System.nanoTime()}-$tid").apply {
                mkdirs()
                File(this, "SKILL.md").writeText(
                    skillMd(name = "orphan-skill", body = "舊版正文"),
                    Charsets.UTF_8,
                )
                setLastModified(System.currentTimeMillis() - 120_000L)
            }
            assertFalse(File(root, "orphan-skill").exists())

            val removed = SkillInstaller.cleanupStaleStaging(root, maxAgeMillis = 60_000L)

            assertEquals(1, removed)
            assertFalse("orphan bak should be consumed: ${root.listFiles()!!.toList()}", bak.exists())
            val restored = File(root, "orphan-skill/SKILL.md")
            assertTrue(restored.isFile)
            SkillParser.parse(restored.readText(Charsets.UTF_8))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun bakLookalike_notRestored() {
        val root = tempRoot()
        try {
            // 舊寬鬆比對會把 `.x.bak-y` 反解 id=x 並扶正為 target；嚴格正則必須不碰。
            val lookalike = File(root, ".x.bak-y").apply {
                mkdirs()
                File(this, "SKILL.md").writeText(skillMd(name = "x", body = "偽裝舊版"), Charsets.UTF_8)
                setLastModified(System.currentTimeMillis() - 120_000L)
            }
            val removed = SkillInstaller.cleanupStaleStaging(root, maxAgeMillis = 60_000L)
            assertEquals(0, removed)
            assertFalse("lookalike must not be promoted", File(root, "x").exists())
            assertTrue("non-shape residue must be left alone", lookalike.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun nonInstallerDotFiles_neverPromoted() {
        val root = tempRoot()
        try {
            val bakery = File(root, ".my.bakery").apply {
                writeText("pastry")
                setLastModified(System.currentTimeMillis() - 120_000L)
            }
            val nomedia = File(root, ".nomedia").apply {
                writeText("")
                setLastModified(System.currentTimeMillis() - 120_000L)
            }
            val random = File(root, ".random").apply {
                writeText("r")
                setLastModified(System.currentTimeMillis() - 120_000L)
            }
            val removed = SkillInstaller.cleanupStaleStaging(root, maxAgeMillis = 60_000L)
            assertEquals(0, removed)
            assertTrue(bakery.isFile)
            assertTrue(nomedia.isFile)
            assertTrue(random.isFile)
            assertFalse(File(root, "my").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun invalidIdBak_deletedNotRestored() {
        val root = tempRoot()
        try {
            // 形狀符合但 id 非法（NAME_RE 拒大寫）：只刪不恢復。
            val tid = Thread.currentThread().id
            val bad = File(root, ".UPPER.bak-${System.nanoTime()}-$tid").apply {
                mkdirs()
                File(this, "SKILL.md").writeText("junk")
                setLastModified(System.currentTimeMillis() - 120_000L)
            }
            val removed = SkillInstaller.cleanupStaleStaging(root, maxAgeMillis = 60_000L)
            assertEquals(1, removed)
            assertFalse(bad.exists())
            assertFalse("invalid id must not be promoted", File(root, "UPPER").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun crashWindows_cleanupRecovers() {
        // 三態殘留調 cleanup 即恢復（kill 窗口恢復能力鎖定；production 啟動接線為 Target）。
        // W1：新鮮安裝 kill（staging 殘留、無 target）→ 殘留清除。
        run {
            val root = tempRoot()
            try {
                val tid = Thread.currentThread().id
                val staging = File(root, ".w1-skill.tmp-${System.nanoTime()}-$tid").apply {
                    mkdirs()
                    File(this, "SKILL.md").writeText(skillMd(name = "w1-skill", body = "新版正文"))
                }
                val removed = SkillInstaller.cleanupStaleStaging(root, maxAgeMillis = 0L)
                assertEquals(1, removed)
                assertFalse(staging.exists())
                assertTrue(root.listFiles()!!.none { it.name.startsWith(".w1-skill") })
            } finally {
                root.deleteRecursively()
            }
        }
        // W2：replace 中 kill（backup 舊版 + staging 新版、無 target）→ 恢復舊版。
        run {
            val root = tempRoot()
            try {
                val tid = Thread.currentThread().id
                File(root, ".w2-skill.bak-${System.nanoTime()}-$tid").apply {
                    mkdirs()
                    File(this, "SKILL.md").writeText(skillMd(name = "w2-skill", body = "舊版正文"))
                }
                File(root, ".w2-skill.tmp-${System.nanoTime()}-$tid").apply {
                    mkdirs()
                    File(this, "SKILL.md").writeText(skillMd(name = "w2-skill", body = "新版正文"))
                }
                val removed = SkillInstaller.cleanupStaleStaging(root, maxAgeMillis = 0L)
                assertEquals(2, removed)
                val restored = File(root, "w2-skill/SKILL.md")
                assertTrue(restored.isFile)
                val text = restored.readText(Charsets.UTF_8)
                assertTrue("expected old version restored, got: $text", "舊版正文" in text)
                SkillParser.parse(text)
            } finally {
                root.deleteRecursively()
            }
        }
        // W3：commit 後刪 bak 前 kill（target 新版 + bak 舊版）→ bak 收斂、新版保留。
        run {
            val root = tempRoot()
            try {
                val tid = Thread.currentThread().id
                File(root, "w3-skill").apply {
                    mkdirs()
                    File(this, "SKILL.md").writeText(skillMd(name = "w3-skill", body = "新版正文"))
                }
                val bak = File(root, ".w3-skill.bak-${System.nanoTime()}-$tid").apply { mkdirs() }
                val removed = SkillInstaller.cleanupStaleStaging(root, maxAgeMillis = 0L)
                assertEquals(1, removed)
                assertFalse(bak.exists())
                val text = File(root, "w3-skill/SKILL.md").readText(Charsets.UTF_8)
                assertTrue("expected new version kept, got: $text", "新版正文" in text)
                SkillParser.parse(text)
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test fun stagingWriteFailure_freshInstall_noTarget_noLeftovers() {
        val root = tempRoot()
        try {
            // staging 寫入階段失敗（第二檔拋錯，模擬磁碟滿）：v1/v2 皆無，
            // 失敗即無半成品、staging 已清、無 .bak。
            SkillInstaller.writeHook = { _, rel, _ ->
                if (rel == "refs/guide.md") throw java.io.IOException("disk full")
            }
            val zip = zipOf(
                "atomic-skill/SKILL.md" to skillMd(body = "正文 v1").toByteArray(Charsets.UTF_8),
                "atomic-skill/refs/guide.md" to "附檔".toByteArray(Charsets.UTF_8),
            )
            val r = SkillInstaller.installZip(zip, root)
            assertTrue("expected failure, got $r", r is SkillInstaller.Result.Failure)
            assertEquals(SkillInstaller.Failure.IO_ERROR, (r as SkillInstaller.Result.Failure).reason)
            assertFalse(File(root, "atomic-skill").exists())
            assertTrue(
                "staging or bak left: ${root.listFiles()!!.toList()}",
                root.listFiles()!!.none { it.name.contains(".tmp-") || it.name.contains(".bak-") },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun stagingWriteFailure_replace_oldVersionIntact() {
        val root = tempRoot()
        try {
            installV1(root)
            val before = File(root, "atomic-skill/SKILL.md").readBytes()

            // 首檔即拋錯：backup 階段尚未開始，target 從未被搬移。
            SkillInstaller.writeHook = { _, _, _ -> throw java.io.IOException("disk full") }
            val v2 = zipOf(
                "atomic-skill/SKILL.md" to skillMd(body = "正文 v2").toByteArray(Charsets.UTF_8),
            )
            val r = SkillInstaller.installZip(v2, root, replace = true)
            assertTrue("expected failure, got $r", r is SkillInstaller.Result.Failure)
            assertEquals(SkillInstaller.Failure.IO_ERROR, (r as SkillInstaller.Result.Failure).reason)

            // v1 位元組完整、staging 已清、無 .bak（backup 從未建）。
            assertTrue(File(root, "atomic-skill/SKILL.md").readBytes().contentEquals(before))
            assertTrue(
                "staging or bak left: ${root.listFiles()!!.toList()}",
                root.listFiles()!!.none { it.name.contains(".tmp-") || it.name.contains(".bak-") },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun concurrentFreshInstallSameId_oneWinsRestExist() {
        val root = tempRoot()
        val executor = Executors.newFixedThreadPool(8)
        try {
            // 新鮮安裝同 id 併發：ALREADY_EXISTS 檢查在 per-skill 鎖內，恰一勝出。
            val n = 8
            val start = CountDownLatch(1)
            val futures = (0 until n).map { i ->
                executor.submit(
                    Callable<SkillInstaller.Result> {
                        assertTrue("latch hang", start.await(60, TimeUnit.SECONDS))
                        val zip = zipOf(
                            "fresh-skill/SKILL.md" to
                                skillMd(name = "fresh-skill", body = "新鮮正文-$i").toByteArray(Charsets.UTF_8),
                        )
                        SkillInstaller.installZip(zip, root)
                    },
                )
            }
            start.countDown()
            val results = futures.map { it.get(60, TimeUnit.SECONDS) }
            val ok = results.count { it is SkillInstaller.Result.Success }
            val exists = results.count {
                (it as? SkillInstaller.Result.Failure)?.reason == SkillInstaller.Failure.ALREADY_EXISTS
            }
            assertEquals("exactly one winner, got $results", 1, ok)
            assertEquals("rest must see ALREADY_EXISTS, got $results", n - 1, exists)

            // 終態為某一個完整版本（可解析）、無殘留。
            val finalText = File(root, "fresh-skill/SKILL.md").readText(Charsets.UTF_8)
            SkillParser.parse(finalText)
            val hits = (0 until n).count { "新鮮正文-$it" in finalText }
            assertEquals("final content is not one complete version: $finalText", 1, hits)
            assertTrue(
                "installer leftovers: ${root.listFiles()!!.toList()}",
                root.listFiles()!!.none { it.name.startsWith(".fresh-skill") },
            )
        } finally {
            executor.shutdownNow()
            root.deleteRecursively()
        }
    }

    @Test fun replace_disclosesDowngrade_notBlocked() {
        val root = tempRoot()
        try {
            val v2zip = zipOf(
                "downgrade-skill/SKILL.md" to
                    skillMd(name = "downgrade-skill", body = "高版正文", version = "2.0")
                        .toByteArray(Charsets.UTF_8),
            )
            assertTrue(SkillInstaller.installZip(v2zip, root) is SkillInstaller.Result.Success)

            // 降版（2.0 → 1.0）不阻擋，但成功須揭露舊版號供呼叫方並陳。
            val v1zip = zipOf(
                "downgrade-skill/SKILL.md" to
                    skillMd(name = "downgrade-skill", body = "低版正文", version = "1.0")
                        .toByteArray(Charsets.UTF_8),
            )
            val r = SkillInstaller.installZip(v1zip, root, replace = true)
            assertTrue("expected success, got $r", r is SkillInstaller.Result.Success)
            val s = r as SkillInstaller.Result.Success
            assertEquals("2.0", s.replacedVersion)
            assertEquals("1.0", s.skill.manifest.version)
            assertTrue(File(root, "downgrade-skill/SKILL.md").readText(Charsets.UTF_8).contains("低版正文"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun replace_replacedVersion_nullWhenOldHasNoVersion() {
        val root = tempRoot()
        try {
            // 舊版無 version：揭露為 null（非空字串猜測）。
            installV1(root, name = "noversion-skill", body = "無版正文")
            val v2 = zipOf(
                "noversion-skill/SKILL.md" to
                    skillMd(name = "noversion-skill", body = "有版正文", version = "1.0")
                        .toByteArray(Charsets.UTF_8),
            )
            val r = SkillInstaller.installZip(v2, root, replace = true)
            assertTrue("expected success, got $r", r is SkillInstaller.Result.Success)
            assertEquals(null, (r as SkillInstaller.Result.Success).replacedVersion)

            // 新鮮安裝同為 null。
            val fresh = zipOf(
                "brandnew-skill/SKILL.md" to
                    skillMd(name = "brandnew-skill", body = "新鮮正文", version = "9.9")
                        .toByteArray(Charsets.UTF_8),
            )
            val rf = SkillInstaller.installZip(fresh, root)
            assertTrue("expected success, got $rf", rf is SkillInstaller.Result.Success)
            assertEquals(null, (rf as SkillInstaller.Result.Success).replacedVersion)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun installedVersion_readsNullOnMissingOrCorrupt() {
        val root = tempRoot()
        try {
            installV1(root, name = "versioned-skill", body = "有版正文")
            // installV1 經 skillMd 預設無 version：先覆蓋一個有版舊版再讀。
            val v = zipOf(
                "versioned-skill/SKILL.md" to
                    skillMd(name = "versioned-skill", body = "有版正文", version = "3.1")
                        .toByteArray(Charsets.UTF_8),
            )
            assertTrue(SkillInstaller.installZip(v, root, replace = true) is SkillInstaller.Result.Success)
            assertEquals("3.1", SkillInstaller.installedVersion(File(root, "versioned-skill")))

            // 缺席與損毀皆回 null，不拋錯。
            assertEquals(null, SkillInstaller.installedVersion(File(root, "no-such-skill")))
            File(root, "broken-skill").apply {
                mkdirs()
                File(this, "SKILL.md").writeText("junk without frontmatter")
            }
            assertEquals(null, SkillInstaller.installedVersion(File(root, "broken-skill")))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun listResiduals_observesWithoutMutating() {
        val root = tempRoot()
        try {
            installV1(root)
            val tid = Thread.currentThread().id
            val now = System.currentTimeMillis()
            val stale = File(root, ".atomic-skill.tmp-${now * 1000L}-$tid").apply {
                mkdirs()
                setLastModified(now - 120_000L)
            }
            val fresh = File(root, ".atomic-skill.tmp-${System.nanoTime()}-$tid").apply { mkdirs() }
            // target 仍在時的孤兒 bak（清理會刪除並計數）。
            val staleBak = File(root, ".atomic-skill.bak-${System.nanoTime()}-$tid").apply {
                mkdirs()
                setLastModified(now - 120_000L)
            }

            // 只列不過期內部條目：新鮮者排除、真實 skill 目錄排除。
            val listed = SkillInstaller.listResiduals(root, maxAgeMillis = 60_000L)
            assertEquals(listed.map { it.name }.sorted(), listed.map { it.name })
            assertEquals(2, listed.size)
            assertTrue(listed.any { it.name == stale.name })
            assertTrue(listed.any { it.name == staleBak.name })

            // 觀察不改變任何檔案；清理回傳同數並消費。
            assertTrue(stale.exists())
            assertTrue(staleBak.exists())
            assertTrue(fresh.exists())
            assertTrue(File(root, "atomic-skill/SKILL.md").isFile)
            assertEquals(2, SkillInstaller.cleanupStaleStaging(root, maxAgeMillis = 60_000L))
            assertFalse(stale.exists())
            assertFalse(staleBak.exists())
            assertTrue(fresh.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun listResiduals_includesAmbiguousKeptCandidates() {
        val root = tempRoot()
        try {
            // 同 id 兩份 expired .bak + target 缺席：清理全保留（0），
            // 觀察仍列出兩份供人工核對（見 SkillInstaller KDoc）。
            val tid = Thread.currentThread().id
            val bakV1 = File(root, ".dup-skill.bak-111-$tid").apply {
                mkdirs()
                setLastModified(System.currentTimeMillis() - 120_000L)
            }
            val bakV2 = File(root, ".dup-skill.bak-999-$tid").apply {
                mkdirs()
                setLastModified(System.currentTimeMillis() - 120_000L)
            }
            val listed = SkillInstaller.listResiduals(root, maxAgeMillis = 60_000L)
            assertEquals(2, listed.size)
            assertEquals(0, SkillInstaller.cleanupStaleStaging(root, maxAgeMillis = 60_000L))
            assertTrue(bakV1.exists())
            assertTrue(bakV2.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun writeHook_runtimeException_convergesToIoError() {
        // writeHook 拋 SecurityException/RuntimeException 同樣收斂為 IO_ERROR（現僅測 IOException）。
        val root = tempRoot()
        try {
            for (ex in listOf(
                SecurityException("deny"),
                RuntimeException("boom"),
                java.io.IOException("disk full"),
            )) {
                SkillInstaller.writeHook = { _, _, _ -> throw ex }
                val zip = zipOf("hook-skill/SKILL.md" to skillMd(name = "hook-skill").toByteArray(Charsets.UTF_8))
                val r = SkillInstaller.installZip(zip, root)
                assertTrue("expected IO_ERROR for $ex, got $r", r is SkillInstaller.Result.Failure)
                assertEquals(SkillInstaller.Failure.IO_ERROR, (r as SkillInstaller.Result.Failure).reason)
                assertFalse(File(root, "hook-skill").exists())
                assertTrue(
                    "staging must be cleaned for $ex: ${root.listFiles()!!.toList()}",
                    root.listFiles()!!.none { it.name.startsWith(".hook-skill.tmp-") || it.name.startsWith(".hook-skill.bak-") },
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun destRoot_notDirectory_doesNotLeakAbsolutePath() {
        // destRoot 為檔案時拒裝，且訊息僅含檔名不含絕對路徑（脫敏）。
        val parent = tempRoot()
        try {
            val fileAsRoot = File(parent, "notadir").apply { writeText("x") }
            val zip = zipOf("leak-skill/SKILL.md" to skillMd(name = "leak-skill").toByteArray(Charsets.UTF_8))
            val r = SkillInstaller.installZip(zip, fileAsRoot)
            assertTrue(r is SkillInstaller.Result.Failure)
            val msg = (r as SkillInstaller.Result.Failure).message
            assertFalse("must not leak absolute path: $msg", msg.contains(fileAsRoot.absolutePath))
            assertTrue(msg.contains("notadir"))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test fun destRoot_symlink_rejected() {
        // best-effort 僅擋直接 symlink destRoot（父層/TOCTOU 需呼叫方保證同 destRoot 勿併發）。
        val parent = tempRoot()
        try {
            val real = File(parent, "real").apply { mkdirs() }
            val link = File(parent, "linkroot")
            try {
                java.nio.file.Files.createSymbolicLink(link.toPath(), real.toPath())
            } catch (e: UnsupportedOperationException) {
                org.junit.Assume.assumeNoException("platform without symlink", e)
            } catch (e: java.io.IOException) {
                org.junit.Assume.assumeNoException("cannot create symlink", e)
            }
            val zip = zipOf("link-skill/SKILL.md" to skillMd(name = "link-skill").toByteArray(Charsets.UTF_8))
            val r = SkillInstaller.installZip(zip, link)
            assertTrue("symlink destRoot must be rejected, got $r", r is SkillInstaller.Result.Failure)
            assertEquals(SkillInstaller.Failure.IO_ERROR, (r as SkillInstaller.Result.Failure).reason)
            assertFalse("symlink 拒裝不得回顯絕對路徑：${r.message}", r.message.contains(link.absolutePath))
            assertFalse(File(real, "link-skill").exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test fun cleanup_futureMtime_treatedAsFresh() {
        // 未來 mtime（時鐘偏斜）：now-lastModified<0<maxAge，視為 fresh 不刪除（釘選行為）。
        val root = tempRoot()
        try {
            val tid = Thread.currentThread().id
            val future = File(root, ".atomic-skill.tmp-${System.nanoTime()}-$tid").apply {
                mkdirs()
                assertTrue("setLastModified must succeed", setLastModified(System.currentTimeMillis() + 600_000L))
            }
            assertEquals(0, SkillInstaller.cleanupStaleStaging(root, maxAgeMillis = 60_000L))
            assertTrue(future.exists())
            assertEquals(0, SkillInstaller.listResiduals(root, maxAgeMillis = 60_000L).size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun cleanup_fileTypeBak_deletedNotRestored() {
        // 檔案型 .bak（非目錄）：只刪不恢復，不計歧義。
        val root = tempRoot()
        try {
            val tid = Thread.currentThread().id
            val fileBak = File(root, ".orphan2-skill.bak-${System.nanoTime()}-$tid").apply {
                writeText("not a dir")
                setLastModified(System.currentTimeMillis() - 120_000L)
            }
            assertEquals(1, SkillInstaller.cleanupStaleStaging(root, maxAgeMillis = 60_000L))
            assertFalse(fileBak.exists())
            assertFalse(File(root, "orphan2-skill").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun dottedId_installAndResidualRoundTrip() {
        // 點式 id（a.b-c）：貪婪 STAGING_RE/BACKUP_RE 正確往返，不誤判；安裝成功後
        // 手工殘留同形亦能被 list/cleanup 識別（list 列出、cleanup 按齡期收斂）。
        val root = tempRoot()
        try {
            val zip = zipOf("a.b-c/SKILL.md" to skillMd(name = "a.b-c", body = "點式").toByteArray(Charsets.UTF_8))
            val r = SkillInstaller.installZip(zip, root, replace = false)
            assertTrue("expected success, got $r", r is SkillInstaller.Result.Success)
            assertTrue(File(root, "a.b-c/SKILL.md").isFile)
            val tid = Thread.currentThread().id
            val stale = File(root, ".a.b-c.tmp-${System.nanoTime()}-$tid").apply {
                mkdirs()
                assertTrue(setLastModified(System.currentTimeMillis() - 120_000L))
            }
            assertEquals(1, SkillInstaller.listResiduals(root, maxAgeMillis = 60_000L).size)
            assertEquals(1, SkillInstaller.cleanupStaleStaging(root, maxAgeMillis = 60_000L))
            assertFalse(stale.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun installIntoNonexistentDestRoot_createsAndSucceeds() {
        // 首次安裝到「不存在」的 destRoot：探針僅在已存在時運作——不存在路徑
        // 不被判為不可信，mkdirs 建目錄後照常成功、enabled 預設 true。
        // 反例守衛：防 fail-closed 探針把「不存在」誤殺成拒裝（拿不到屬性≠不存在）。
        val parent = tempRoot()
        try {
            val destRoot = File(parent, "nested-root/skills")
            val r = SkillInstaller.installFiles(
                linkedMapOf<String, ByteArray>(
                    "atomic-skill/SKILL.md" to skillMd(name = "atomic-skill").toByteArray(Charsets.UTF_8),
                ),
                destRoot,
            )
            assertTrue("expected success, got $r", r is SkillInstaller.Result.Success)
            val skill = (r as SkillInstaller.Result.Success).skill
            assertTrue("destRoot 應由安裝建立", destRoot.isDirectory)
            assertTrue(File(destRoot, "atomic-skill/SKILL.md").isFile)
            assertTrue(skill.enabled)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test fun destRootUnreadableAttrs_convergesToIoError() {
        // destRoot 已存在、父層摘除 x bit：lstat/stat/exists 均讀不到屬性，
        // 頂層仍必須以 IO_ERROR 收斂（不拋出、不落半成品）。
        // 誠實註記：未知屬性分支本身（exists() 為真而 lstat 失敗）在本 JVM 測試組
        // 無注入縫；本案釘的是 mkdirs/lstat 失敗路由到 IO_ERROR 的「上層收斂」，
        // 探針未知分支的保真屬 KDoc 承諾＋code 一致性，只能真機/手動驗。
        // POSIX chmod 需非 Windows 且非 root（root 對 x bit 的繞過使閘失效），否則 assume-skip。
        val parent = tempRoot()
        try {
            val destRoot = File(parent, "skills").apply { mkdirs() }
            val supportsPosix = try {
                java.nio.file.Files.getPosixFilePermissions(parent.toPath())
                true
            } catch (_: Exception) {
                false
            }
            org.junit.Assume.assumeTrue("POSIX permissions unsupported on this FS", supportsPosix)
            org.junit.Assume.assumeTrue(
                "chmod gate ineffective as root",
                System.getProperty("user.name") != "root",
            )
            val original = java.nio.file.Files.getPosixFilePermissions(parent.toPath())
            try {
                java.nio.file.Files.setPosixFilePermissions(
                    parent.toPath(),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                )
                // 功能性閘：chmod 可能靜默失效（user.name 誤報 root、特殊 mount
                // 掛載選項）；未真摘 x bit 時後續路由與一般路徑無異，assume-skip
                // 而非讓斷言在失效閘下偶然通過。
                org.junit.Assume.assumeFalse(
                    "chmod gate ineffective (x bit still set)",
                    java.nio.file.Files.isExecutable(parent.toPath()),
                )
                val r = SkillInstaller.installFiles(
                    linkedMapOf<String, ByteArray>(
                        "conv-skill/SKILL.md" to skillMd(name = "conv-skill").toByteArray(Charsets.UTF_8),
                    ),
                    destRoot,
                )
                assertTrue("expected failure, got $r", r is SkillInstaller.Result.Failure)
                assertEquals(
                    SkillInstaller.Failure.IO_ERROR,
                    (r as SkillInstaller.Result.Failure).reason,
                )
                assertTrue(
                    "message 應循 mkdirs 失敗路由（同 destRootNotCreatable 款式）：${(r as SkillInstaller.Result.Failure).message}",
                    "cannot create" in (r as SkillInstaller.Result.Failure).message,
                )
            } finally {
                try {
                    java.nio.file.Files.setPosixFilePermissions(parent.toPath(), original)
                } catch (_: Exception) {
                    // best effort 還原；還原失敗會令本測 temp 清理殘餘（誠實代價，不改變斷言）。
                }
            }
            assertFalse("不得落半成品", File(destRoot, "conv-skill").exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test fun staleBakWithoutTarget_manifestNameMismatch_keptNotRestored() {
        // 還原內容校驗（fail-closed）：bak 內 SKILL.md 可解析但 manifest.name 與
        // bakId 不符 → 無法證明此 bak 屬於該 skill：KEPT、不扶正（bakId 與
        // other-name 皆不得出現）、不刪除；listResiduals 照常列出供人工核對。
        val root = tempRoot()
        try {
            val tid = Thread.currentThread().id
            val bak = File(root, ".mismatch-skill.bak-${System.nanoTime()}-$tid").apply {
                mkdirs()
                File(this, "SKILL.md").writeText(
                    skillMd(name = "other-name", body = "偽 bak 內容"),
                    Charsets.UTF_8,
                )
                setLastModified(System.currentTimeMillis() - 120_000L)
            }
            val removed = SkillInstaller.cleanupStaleStaging(root, maxAgeMillis = 60_000L)
            assertEquals(0, removed)
            assertFalse("bakId 不得被扶正", File(root, "mismatch-skill").exists())
            assertFalse("other-name 不得被扶正", File(root, "other-name").exists())
            assertTrue("bak 不得被刪除", bak.isDirectory)
            assertTrue(
                "listResiduals 照常列出：${SkillInstaller.listResiduals(root, maxAgeMillis = 60_000L).map { it.name }}",
                SkillInstaller.listResiduals(root, maxAgeMillis = 60_000L).any { it == bak },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun staleBakWithoutTarget_missingOrCorruptSkillMd_keptNotRestored() {
        // 還原內容校驗兩案：bak 內無 SKILL.md；SKILL.md 非 frontmatter（解析失敗）。
        // 兩者皆「無法證明歸屬」：KEPT、不還原、不刪除（listResiduals 照常列出）。
        val root = tempRoot()
        try {
            val tid = Thread.currentThread().id
            val missing = File(root, ".mdmiss-skill.bak-${System.nanoTime()}-$tid").apply {
                mkdirs()
                File(this, "orphan").writeText("not a skill", Charsets.UTF_8)
                setLastModified(System.currentTimeMillis() - 120_000L)
            }
            val corrupt = File(root, ".mdjunk-skill.bak-${System.nanoTime()}-$tid").apply {
                mkdirs()
                File(this, "SKILL.md").writeText("junk without frontmatter", Charsets.UTF_8)
                setLastModified(System.currentTimeMillis() - 120_000L)
            }
            val removed = SkillInstaller.cleanupStaleStaging(root, maxAgeMillis = 60_000L)
            assertEquals(0, removed)
            assertFalse(File(root, "mdmiss-skill").exists())
            assertFalse(File(root, "mdjunk-skill").exists())
            assertTrue("缺 SKILL.md 的 bak 不得被刪", missing.isDirectory)
            assertTrue("損毀 SKILL.md 的 bak 不得被刪", corrupt.isDirectory)
            val listed = SkillInstaller.listResiduals(root, maxAgeMillis = 60_000L).map { it.name }
            assertTrue("缺檔 bak 照常列出：$listed", missing.name in listed)
            assertTrue("損毀 bak 照常列出：$listed", corrupt.name in listed)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun staleBakWithoutTarget_oversizedSkillMd_keptNotRestored() {
        // 尺寸守衛：SKILL.md >MAX_SINGLE_FILE_BYTES 即不讀、不解析（防記憶體放大）。
        // 內容刻意為「可能合法」（name==bakId 的 frontmatter + 超限填充）：
        // 證明尺寸閘獨立於解析、超限即無法證明歸屬：KEPT、不還原、不刪除。
        val root = tempRoot()
        try {
            val tid = Thread.currentThread().id
            val mdText = skillMd(name = "oversize-skill", body = "正文") + "\n" +
                "A".repeat(SkillInstaller.MAX_SINGLE_FILE_BYTES.toInt() + 1)
            val bak = File(root, ".oversize-skill.bak-${System.nanoTime()}-$tid").apply {
                mkdirs()
                File(this, "SKILL.md").writeText(mdText, Charsets.UTF_8)
                setLastModified(System.currentTimeMillis() - 120_000L)
            }
            val removed = SkillInstaller.cleanupStaleStaging(root, maxAgeMillis = 60_000L)
            assertEquals(0, removed)
            assertFalse(File(root, "oversize-skill").exists())
            assertTrue("超限 bak 不得被刪", bak.isDirectory)
            assertTrue(
                "超限 bak 照常列出：${SkillInstaller.listResiduals(root, maxAgeMillis = 60_000L).map { it.name }}",
                SkillInstaller.listResiduals(root, maxAgeMillis = 60_000L).any { it == bak },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun staleBakWithoutTarget_symlinkSkillMd_keptNotRestored() {
        // bak 內 SKILL.md 為指向外部的 symlink：內容證明 fail-closed（不跟隨）
        // → KEPT、不還原、不刪；讀取不得穿透 link 觸碰外部 sentinel。
        val parent = tempRoot()
        try {
            val root = File(parent, "skills").apply { mkdirs() }
            val outside = File(parent, "outside").apply { mkdirs() }
            // sentinel 內容刻意可解析為 name == bakId（linkbak-skill）：若未來
            // 回歸成「跟隨 link 讀取」，bak 會被扶正而使下方不扶正斷言轉紅——
            // KEPT 結果因此與 sentinel 內容形成對照，而非僅靠 sentinel 存活。
            val sentinel = File(outside, "sentinel.txt").apply {
                writeText(skillMd(name = "linkbak-skill"), Charsets.UTF_8)
            }
            val tid = Thread.currentThread().id
            val bak = File(root, ".linkbak-skill.bak-${System.nanoTime()}-$tid").apply { mkdirs() }
            try {
                java.nio.file.Files.createSymbolicLink(
                    File(bak, "SKILL.md").toPath(),
                    sentinel.toPath(),
                )
            } catch (e: UnsupportedOperationException) {
                org.junit.Assume.assumeNoException("platform without symlink", e)
            } catch (e: java.io.IOException) {
                org.junit.Assume.assumeNoException("cannot create symlink", e)
            }
            bak.setLastModified(System.currentTimeMillis() - 120_000L)
            val removed = SkillInstaller.cleanupStaleStaging(root, maxAgeMillis = 60_000L)
            assertEquals(0, removed)
            assertFalse("bakId 不得被扶正", File(root, "linkbak-skill").exists())
            assertTrue("bak 不得被刪", bak.isDirectory)
            assertTrue(
                "linkbak 案亦照常列出：${SkillInstaller.listResiduals(root, maxAgeMillis = 60_000L).map { it.name }}",
                SkillInstaller.listResiduals(root, maxAgeMillis = 60_000L).any { it == bak },
            )
            assertTrue(
                "讀取不得穿透 link（外部 sentinel 存活且原樣）",
                sentinel.isFile && sentinel.readText(Charsets.UTF_8) == skillMd(name = "linkbak-skill"),
            )
        } finally {
            parent.deleteRecursively()
        }
    }

    // ---- 清理 attempt 預算（P2-1：上限計工作嘗試、失敗亦計入；見 SkillInstaller 兩常量 KDoc）----

    @Test fun restoreAttempts_cappedOnFailures_goodBakWaitsNextRun() {
        // 還原型 attempt 釘（殺死舊 success-counting 計法）：cap 份可解析、尺寸
        // 合法的「失敗型」bak（個人化混形：錯名型——內容證明 fail-closed 拒還原、
        // 不經 rename；rename 失敗型——renameHook=false 拒絕，hook 計數觀察 rename
        // 嘗試）+ 1 份已知合法 bak。單 run：17 份候選只付 cap 份 attempt
        //（cleanupAttemptsForTest().second 恰 cap；first==0 對照還原車道不占刪除
        // 帳），rename 嘗試 ≤ cap，合法 bak 本 run 不得被還原（attempt 被失敗型
        // 耗盡便零 I/O，或其 rename 被注入拒絕——皆 KEPT、內容完整、仍在 bak 形
        // 狀），人工移除失敗型 bak 後的下個 run 才還原（跨 run 收斂誠實）。
        // 舊行為（成功才改帳）：失敗型永不耗額——17 份全部付全額（lstat＋全檔讀
        // ＋解析，rename 車者再加 rename），合法 bak 同 run 被扶正；attempt 縫在
        // 舊計法下為 0～1（僅計成功）， 非 cap——本測以縫為主殺死形。
        // 誠實註記（列舉順序）：`listFiles()` 順序未定義，合法 bak 可能位於 cap
        // 之內——此形其 attempt 已付、rename 由 hook 拒絕；「cap 後零 I/O 的 17th」
        // 形由 attempt 縫恰 cap＋車道序（bindRestore 先於 bakManifestName）佐證，
        // 讀取本身無注入縫，屬手動審計（同 proofAttempts_ 款）。
        val root = tempRoot()
        try {
            val tid = Thread.currentThread().id
            val cap = SkillInstaller.MAX_RESTORES_PER_RUN
            val wrongIds = (0 until cap / 2).map { "atmw-" + it.toString().padStart(3, '0') }
            val renameFailIds = (cap / 2 until cap).map { "atmr-" + it.toString().padStart(3, '0') }
            fun bakFor(id: String, mdName: String): File =
                File(root, ".$id.bak-${Math.abs(System.nanoTime())}-$tid").apply {
                    mkdirs()
                    File(this, "SKILL.md").writeText(
                        skillMd(name = mdName, body = "attempt 正文"),
                        Charsets.UTF_8,
                    )
                }
            val wrongBaks = wrongIds.map { bakFor(it, "$it-alt") } // 可解析但 name != bakId
            val renameFailBaks = renameFailIds.map { bakFor(it, it) } // 內容合法、rename 端拒絕
            val goodBak = bakFor("atmg-skill", "atmg-skill")

            val hookCalls = AtomicInteger(0)
            SkillInstaller.renameHook = { _, _ ->
                hookCalls.incrementAndGet()
                false
            }
            val removed = SkillInstaller.cleanupStaleStaging(root)

            val attempts = SkillInstaller.cleanupAttemptsForTest()
            assertEquals(
                "cap 份失敗型 + 1 合法 bak 只付 cap 份還原 attempt（失敗亦計入）",
                cap,
                attempts.second,
            )
            assertEquals("純還原 run 不得占刪除帳", 0, attempts.first)
            assertTrue(
                "rename 嘗試以 hook 觀察、不得超過還原型 cap：${hookCalls.get()}",
                hookCalls.get() in 1..cap,
            )
            assertEquals("合法 bak 本 run 不得被還原", 0, removed)
            assertFalse("合法 bak 不得被扶正", File(root, "atmg-skill").exists())
            assertTrue(
                "合法 bak 原樣保留（KEPT 待下 run）",
                goodBak.isDirectory && File(goodBak, "SKILL.md").isFile,
            )
            assertEquals(
                "失敗型 bak 刻意不被清理（錯名型永續 KEPT 待人工；rename 失敗型下 run 重試）",
                cap + 1,
                root.listFiles()!!.size,
            )

            // 人工移除失敗型 bak（錯名型屬永續 KEPT 形需人工；rename 失敗型由本測
            // 代行人工消形），只留合法 bak：新 run 新額度，應還原。
            SkillInstaller.renameHook = null
            (wrongBaks + renameFailBaks).forEach { it.deleteRecursively() }
            val second = SkillInstaller.cleanupStaleStaging(root)
            assertEquals("合法 bak 於後續 run 還原（跨 run 收斂）", 1, second)
            assertTrue(File(root, "atmg-skill/SKILL.md").isFile)
            SkillParser.parse(File(root, "atmg-skill/SKILL.md").readText(Charsets.UTF_8))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun proofAttempts_stopAtCap_onExpensiveCorruptBaks() {
        // 內容證明 attempt 釘（殺死舊 success-counting 計法的敗證側）：cap+7 份
        // 「昂貴但敗證」bak——SKILL.md 全檔 60KB（＜MAX_SINGLE_FILE_BYTES 可全讀，
        // 且在 SkillParser body 預算內故「解析成功、純 name != bakId」——敗因恰為
        // fail-closed 的拒還原車道、全讀＋解析成本為真；刻意不走「body 超限」或
        // 「不可解析」敗因以避免與尺寸／損毀款形混形）。單 run attempt 恰停 cap；
        // cap 後候選本 run 不再付任何證明工作：
        // 「未被讀」無注入縫可觀察（bakManifestName 內 lstat＋read 無 hook）——
        // 誠實註記：由 bindRestore 先於 bakManifestName 的車道序＋attempt 縫恰
        // cap 佐證，正式覆證屬真機/手動審計（同 SkillInstaller KDoc honesty 款）。
        // 舊行為：敗證 bak 不占額——cap+7 份全在單 run 付 60KB 讀＋解析
        //（removed 同為 0，計數殺不動此形）；attempt 縫 0（或僅計成功）≠ cap。
        val root = tempRoot()
        try {
            val extra = 7
            val total = SkillInstaller.MAX_RESTORES_PER_RUN + extra
            val tid = Thread.currentThread().id
            val filler = "A".repeat(60_000)
            val baks = (0 until total).map { i ->
                val id = "proof-" + i.toString().padStart(3, '0')
                File(root, ".$id.bak-${Math.abs(System.nanoTime())}-$tid").apply {
                    mkdirs()
                    File(this, "SKILL.md").writeText(
                        skillMd(name = "$id-alt", body = filler),
                        Charsets.UTF_8,
                    )
                }
            }
            val removed = SkillInstaller.cleanupStaleStaging(root)
            val attempts = SkillInstaller.cleanupAttemptsForTest()
            assertEquals("敗證 bak 不得被還原", 0, removed)
            assertEquals(
                "attempt 恰停 cap（每份敗證 bak 的全檔讀＋解析都占 attempt）",
                SkillInstaller.MAX_RESTORES_PER_RUN,
                attempts.second,
            )
            assertEquals("純還原 run 不得占刪除帳", 0, attempts.first)
            assertTrue(
                "cap 後本 run 原樣保留（bak 未被代刪）：${root.listFiles()!!.size}",
                baks.all { it.isDirectory && File(it, "SKILL.md").isFile },
            )
            val second = SkillInstaller.cleanupStaleStaging(root)
            assertEquals("敗證形下個 run 仍不得還原（跨 run 語意不變）", 0, second)
            assertEquals(
                "敗證 bak 不得被移除（fail-closed 語意不變）",
                total,
                root.listFiles()!!.size,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun deleteAttempts_hookSkipFail_cappedExactly() {
        // 刪除類 attempt 釘（殺死舊 success-counting 計法的刪除側）：cap+7 個過期
        // staging 條目 + deleteHook=false（skip-fail 語意：attempt 已計入、真實
        // I/O 本 run 跳過）。單 run：hook 調用恰 cap（有額才調——cap 後條目無額
        // 不調、零 I/O），removed==0，全部條目（含其內 sentinel）原樣——「65th
        // 仍在」由全員 survive 佐證（誰恰逢第 65 位不可控，hook=false 下全員
        // KEPT）。舊行為：敗刪不改帳——cap+7 個全都會被 hook 調用（71≠cap，
        // 本測 behavioral 主殺死形，不僅靠縫）。hook 清回 null 後 64→7→0 跨 run
        // 收斂（成功車道仍受同 cap 約束；收斂對照同
        // SkillStartupRecoveryTest.cleanup_deleteBudget_cappedPerRun），且 hook
        // 計數不再增加（null 短路＝生產零成本 no-op 的可觀察面）。
        val root = tempRoot()
        try {
            val extra = 7
            val total = SkillInstaller.MAX_CLEANUP_ENTRIES_PER_RUN + extra
            val dirs = (0 until total).map { i ->
                File(root, ".capdel-" + i.toString().padStart(3, '0') + ".tmp-1-2").apply {
                    mkdirs()
                    File(this, "payload.txt").writeText("keep", Charsets.UTF_8)
                    setLastModified(System.currentTimeMillis() - (25L * 60 * 60 * 1000))
                }
            }
            val hookCalls = AtomicInteger(0)
            SkillInstaller.deleteHook = { _ ->
                hookCalls.incrementAndGet()
                false
            }
            val first = SkillInstaller.cleanupStaleStaging(root)
            val attempts = SkillInstaller.cleanupAttemptsForTest()
            assertEquals(
                "hook 調用恰 cap（skip-fail 亦耗刪除 attempt；cap 後不調）",
                SkillInstaller.MAX_CLEANUP_ENTRIES_PER_RUN,
                hookCalls.get(),
            )
            assertEquals(
                "attempt 縫與 hook 調用同帳（first＝刪除類 attempt）",
                SkillInstaller.MAX_CLEANUP_ENTRIES_PER_RUN,
                attempts.first,
            )
            assertEquals("刪除 run 不影響還原型帳", 0, attempts.second)
            assertEquals("hook=false 的 run 不得回報任何刪除", 0, first)
            assertTrue(
                "cap 前後全員 survive（I/O 已跳過）：${root.listFiles()!!.size}",
                dirs.all { it.isDirectory && File(it, "payload.txt").isFile },
            )

            // hook 清回 null：真實刪除恢復，64→7→0 跨 run 收斂；hook 計數不再變。
            SkillInstaller.deleteHook = null
            assertEquals(
                "run 2 仍只刪至 cap（attempt 預算對成敗中立的對照組）",
                SkillInstaller.MAX_CLEANUP_ENTRIES_PER_RUN,
                SkillInstaller.cleanupStaleStaging(root),
            )
            assertEquals(
                "null hook 後 hook 不得再被調（零成本 no-op）",
                SkillInstaller.MAX_CLEANUP_ENTRIES_PER_RUN,
                hookCalls.get(),
            )
            assertEquals("run 3 收斂剩餘", extra, SkillInstaller.cleanupStaleStaging(root))
            assertEquals(0, SkillInstaller.cleanupStaleStaging(root))
            assertTrue(
                "全部收斂後無 staging 殘餘：${root.listFiles()!!.toList()}",
                dirs.none { it.exists() },
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
