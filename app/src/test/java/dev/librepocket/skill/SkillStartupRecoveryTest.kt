package dev.librepocket.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #15 崩溃恢復清理硬化測試（純 JVM）。
 *
 * - 崩潰窗口還原不問齡期：target 缺席的合法 `.bak` 在預設 24h 期限下仍立即恢復
 *   （否則崩溃後要等一天，與「process restart 即恢復」矛盾）；
 * - 進行中安裝保護：新鲜 staging／target 仍在的孤兒 `.bak` 不受預設期限刪除；
 * - symlink 防衛：cleanup／listResiduals／installedVersion 不經 symlink 作用外部目錄。
 *
 * 啟動接線（[dev.librepocket.agent.LibrePocketApp.onCreate] 真的呼叫清理）由
 * `LibrePocketAppStartupTest`（Robolectric）覆蓋。
 */
class SkillStartupRecoveryTest {

    private fun skillMd(name: String, body: String = "正文"): String =
        "---\nname: $name\ndescription: 測試片段\nallowed-tools: [alarm.create]\n---\n$body"

    private fun tempRoot(): File =
        java.nio.file.Files.createTempDirectory("skill-recovery").toFile().apply { deleteOnExit() }

    // ---- 齡期語意（兩閾分離）----

    @Test fun crashWindowFreshBak_restoredImmediatelyAtDefaultAge() {
        // 崩溃後數秒的 .bak（target 缺席）：即使未達 24h 預設期限也必須立即恢復。
        val root = tempRoot()
        try {
            val tid = Thread.currentThread().id
            val bak = File(root, ".fresh-skill.bak-${System.nanoTime()}-$tid").apply {
                mkdirs()
                File(this, "SKILL.md").writeText(skillMd("fresh-skill", "舊版正文"), Charsets.UTF_8)
                // 刻意設為「新鮮」（剛剛），證明崩潰窗口不受 maxAge 限制。
                setLastModified(System.currentTimeMillis())
            }
            val removed = SkillInstaller.cleanupStaleStaging(root)
            assertEquals(1, removed)
            assertFalse("crash bak 應被消費：${root.listFiles()!!.toList()}", bak.exists())
            assertTrue(File(root, "fresh-skill/SKILL.md").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun freshStaging_keptAtDefaultAge() {
        // 進行中安裝保護：新鲜 staging 不受預設 24h 期限刪除（避免誤刪他方刚啟動的安裝）。
        val root = tempRoot()
        try {
            val tid = Thread.currentThread().id
            val staging = File(root, ".inflight-skill.tmp-${System.nanoTime()}-$tid").apply {
                mkdirs()
                File(this, "SKILL.md").writeText(skillMd("inflight-skill", "新版正文"), Charsets.UTF_8)
                setLastModified(System.currentTimeMillis())
            }
            val removed = SkillInstaller.cleanupStaleStaging(root)
            assertEquals(0, removed)
            assertTrue("進行中 staging 不得被刪", staging.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun orphanBakWithTargetIntact_keptAtDefaultAge() {
        // target 仍在的孤兒 .bak：新鮮時不刪（受期限保護），避免誤刪。
        val root = tempRoot()
        try {
            val name = "kept-skill"
            File(root, "$name/SKILL.md").apply {
                parentFile!!.mkdirs()
                writeText(skillMd(name, "現行版"), Charsets.UTF_8)
            }
            val tid = Thread.currentThread().id
            val bak = File(root, ".$name.bak-${System.nanoTime()}-$tid").apply {
                mkdirs()
                File(this, "SKILL.md").writeText(skillMd(name, "舊版"), Charsets.UTF_8)
                setLastModified(System.currentTimeMillis())
            }
            val removed = SkillInstaller.cleanupStaleStaging(root)
            assertEquals(0, removed)
            assertTrue("target 仍在的新鮮 bak 不得被刪", bak.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    // ---- symlink 防衛 ----

    @Test fun cleanup_symlinkDestRoot_isNoop() {
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
            val tid = Thread.currentThread().id
            File(link, ".sym-skill.bak-${System.nanoTime()}-$tid").apply {
                mkdirs()
                File(this, "SKILL.md").writeText(skillMd("sym-skill", "舊版"), Charsets.UTF_8)
                setLastModified(System.currentTimeMillis() - 120_000L)
            }
            assertEquals(0, SkillInstaller.cleanupStaleStaging(link))
            assertTrue("symlink destRoot 不得被刪除或改名", link.isDirectory)
            assertTrue("symlink 目標不得被寫入", real.listFiles()!!.none { it.name == "sym-skill" })
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test fun listResiduals_symlinkDestRoot_isEmpty() {
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
            // 直接在 symlink 目標（real）建立「過期」條目：若無 symlink 防衛，
            // listResiduals(link) 會 follow symlink 而列出它；有防衛則回空。
            val tid = Thread.currentThread().id
            File(real, ".sym-skill.tmp-${System.nanoTime()}-$tid").apply {
                mkdirs()
                setLastModified(System.currentTimeMillis() - (25L * 60 * 60 * 1000))
            }
            assertTrue(SkillInstaller.listResiduals(link).isEmpty())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test fun installedVersion_symlinkSkillMd_returnsNull() {
        val parent = tempRoot()
        try {
            val skillDir = File(parent, "sym-skill").apply { mkdirs() }
            val outside = File(parent, "secret.txt").apply { writeText("機密", Charsets.UTF_8) }
            val md = File(skillDir, SkillInstaller.SKILL_MD)
            try {
                java.nio.file.Files.createSymbolicLink(md.toPath(), outside.toPath())
            } catch (e: UnsupportedOperationException) {
                org.junit.Assume.assumeNoException("platform without symlink", e)
            } catch (e: java.io.IOException) {
                org.junit.Assume.assumeNoException("cannot create symlink", e)
            }
            assertNull(SkillInstaller.installedVersion(skillDir))
        } finally {
            parent.deleteRecursively()
        }
    }
}
