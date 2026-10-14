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
 * - 併發安裝同一 skill 序列化（per-skill 鎖 + hook 內最大並發斷言）；
 * - 重啟清理：過期 staging／孤兒 bak 收斂、嚴格檔名形狀、崩潰三窗口調 cleanup 即恢復、
 *   同 id 多份 orphan backup 歧義全保留；
 *
 * 測試 hook 採委託式：只擋指定 rename，其餘走真實 `from.renameTo(to)`，
 * 禁止「hook 回 true 卻沒搬」的空轉假成功。
 */
class SkillAtomicCommitTest {

    @After fun resetRenameHook() {
        SkillInstaller.renameHook = null
    }

    private fun skillMd(
        name: String = "atomic-skill",
        body: String = "正文 v1",
    ): String = """
        ---
        name: $name
        description: 原子提交測試片段
        allowed-tools: [calendar.create, alarm.create]
        permissions: [needs calendar read]
        ---
        $body
    """.trimIndent()

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
                        start.await()
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
}
