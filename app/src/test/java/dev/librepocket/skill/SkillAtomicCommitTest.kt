package dev.librepocket.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * D3 原子提交測試（#15 有界切片）。
 *
 * 覆蓋 SkillInstaller.kt:205-211 的非原子缺口：
 * replace 先 deleteRecursively 舊版才 renameTo，失敗退回 copyRecursively；
 * 中斷/磁碟滿/程序終止即丟舊版＋半成品。
 *
 * - replace 失敗回滾（舊版仍在、target 無半成品）；
 * - 原子切換成功（新版完整、無 .tmp/.bak 殘留）；
 * - 併發安裝同一 skill 序列化；
 * - 重啟 staging 清理（過期 staging 刪除、技能本體保留）。
 */
class SkillAtomicCommitTest {

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

    @Test fun atomicReplaceSuccess_leavesNoLeftovers() {
        val root = tempRoot()
        try {
            val v1 = zipOf("atomic-skill/SKILL.md" to skillMd(body = "正文 v1").toByteArray(Charsets.UTF_8))
            assertTrue(SkillInstaller.installZip(v1, root) is SkillInstaller.Result.Success)
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

    @Test fun replaceRenameFailure_rollsBack_oldIntact_noHalfProduct() {
        val root = tempRoot()
        try {
            val v1 = zipOf("atomic-skill/SKILL.md" to skillMd(body = "正文 v1").toByteArray(Charsets.UTF_8))
            assertTrue(SkillInstaller.installZip(v1, root) is SkillInstaller.Result.Success)
            val before = File(root, "atomic-skill/SKILL.md").readText(Charsets.UTF_8)

            // 只讓 staging→target 的改名失敗；target→backup 與回滾仍走真實 rename。
            SkillInstaller.renameHook = { from, _ -> !from.name.contains(".tmp-") }
            try {
                val v2 = zipOf(
                    "atomic-skill/SKILL.md" to skillMd(body = "正文 v2 半成品").toByteArray(Charsets.UTF_8),
                    "atomic-skill/refs/new.md" to "半成品附檔".toByteArray(Charsets.UTF_8),
                )
                val r = SkillInstaller.installZip(v2, root, replace = true)
                assertTrue("expected failure, got $r", r is SkillInstaller.Result.Failure)
                assertEquals(
                    SkillInstaller.Failure.IO_ERROR,
                    (r as SkillInstaller.Result.Failure).reason,
                )
            } finally {
                SkillInstaller.renameHook = null
            }

            // 舊版仍在、target 無半成品。
            assertEquals(before, File(root, "atomic-skill/SKILL.md").readText(Charsets.UTF_8))
            assertFalse(File(root, "atomic-skill/refs/new.md").exists())
            val stagingLeft = root.listFiles()!!.filter { it.name.contains(".tmp-") }
            assertTrue("staging leftovers: $stagingLeft", stagingLeft.isEmpty())
            // 回滾後不應留下 .bak。
            val bakLeft = root.listFiles()!!.filter { it.name.contains(".bak") }
            assertTrue("bak leftovers: $bakLeft", bakLeft.isEmpty())
        } finally {
            SkillInstaller.renameHook = null
            root.deleteRecursively()
        }
    }

    @Test fun concurrentReplaceSameSkill_serialized() {
        val root = tempRoot()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val v1 = zipOf(
                "concurrent-skill/SKILL.md" to
                    skillMd(name = "concurrent-skill", body = "正文 v1").toByteArray(Charsets.UTF_8),
            )
            assertTrue(SkillInstaller.installZip(v1, root) is SkillInstaller.Result.Success)

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
            val v1 = zipOf("atomic-skill/SKILL.md" to skillMd(body = "正文 v1").toByteArray(Charsets.UTF_8))
            assertTrue(SkillInstaller.installZip(v1, root) is SkillInstaller.Result.Success)

            val now = System.currentTimeMillis()
            val stale = File(root, ".atomic-skill.tmp-1").apply {
                mkdirs()
                File(this, "x").writeText("x")
                setLastModified(now - 120_000L)
            }
            val fresh = File(root, ".atomic-skill.tmp-2").apply { mkdirs() }
            val staleBak = File(root, ".atomic-skill.bak-1").apply {
                mkdirs()
                setLastModified(now - 120_000L)
            }

            val removed = SkillInstaller.cleanupStaleStaging(root, maxAgeMillis = 60_000L)
            assertTrue("expected removals, got $removed", removed >= 2)
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
            // 模擬崩潰窗口：backup 已搬離、target 缺席。
            val bak = File(root, ".orphan-skill.bak-1").apply {
                mkdirs()
                File(this, "SKILL.md").writeText(
                    skillMd(name = "orphan-skill", body = "舊版正文"),
                    Charsets.UTF_8,
                )
                setLastModified(System.currentTimeMillis() - 120_000L)
            }
            assertFalse(File(root, "orphan-skill").exists())

            SkillInstaller.cleanupStaleStaging(root, maxAgeMillis = 60_000L)

            assertFalse("orphan bak should be consumed: ${root.listFiles()!!.toList()}", bak.exists())
            assertTrue(File(root, "orphan-skill/SKILL.md").isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
