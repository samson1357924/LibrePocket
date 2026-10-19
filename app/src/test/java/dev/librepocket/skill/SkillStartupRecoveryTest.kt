package dev.librepocket.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #15 崩潰恢復清理硬化測試（純 JVM）。
 *
 * - 崩潰窗口還原不問齡期：target 缺席的合法 `.bak` 在預設 24h 期限下仍立即恢復
 *   （否則崩潰後要等一天，與「process restart 即恢復」矛盾）；
 * - 進行中安裝保護：新鮮 staging／target 仍在的孤兒 `.bak` 不受預設期限刪除；
 * - symlink 防衛：cleanup／listResiduals／installedVersion 不經 symlink 作用外部目錄；
 * - 子條目 symlink 防衛（NOFOLLOW）：形狀內的 symlink 條目只解除 link 本身——
 *   不遞迴刪除其指向的外部內容、也不還原為正式 skill；真實目錄走
 *   NOFOLLOW 遞迴刪除（內部遭植入 symlink 亦不波及外部）；
 * - 每次啟動刪除類處置設 [SkillInstaller.MAX_CLEANUP_ENTRIES_PER_RUN] 上限：
 *   超出者保留、下次啟動繼續收斂（崩潰還原型豁免上限，見
 *   cleanupBudget_midRun_restoreExempt）。
 *
 * Skip 語義：以下測試需平台可建 symlink，未支援時以 [org.junit.Assume.assumeNoException]
 * 跳過，非 silent-fail——綠但帶 skip 的 run 並非 symlink 防衛全覆蓋證明，
 * 正式 gating 以可建 symlink 的 CI（Linux）為準：
 * cleanup_symlinkStaleStaging_externalContentsSurvive、cleanup_freshSymlinkCrashBak_notRestoredAsSkill、
 * cleanup_danglingSymlinkStaleEntry_unlinkOnly、cleanup_realStagingWithInnerSymlink_externalContentsSurvive、
 * cleanup_symlinkUnlink_capped_perRun、cleanup_symlinkDestRoot_isNoop、
 * listResiduals_symlinkDestRoot_isEmpty、installedVersion_symlinkSkillMd_returnsNull、
 * installedVersion_symlinkSkillDir_returnsNull、cleanup_freshLinkStagingShape_unlinkedRegardlessOfAge。
 *
 * 啟動接線（[dev.librepocket.agent.LibrePocketApp.onCreate] 真的呼叫清理）屬
 * Android 行為，由 `LibrePocketAppStartupTest`（Robolectric）覆蓋；
 * 「屬性讀取失敗（AccessDenied 等）一律回 true」的分岔無 JVM 注入縫，
 * 只能真機/手動驗——本檔主張以 CI 綠為準、skip 不計入 symlink 分支覆蓋。
 */
class SkillStartupRecoveryTest {

    private fun skillMd(name: String, body: String = "正文"): String =
        "---\nname: $name\ndescription: 測試片段\nallowed-tools: [alarm.create]\n---\n$body"

    private fun tempRoot(): File =
        java.nio.file.Files.createTempDirectory("skill-recovery").toFile().apply { deleteOnExit() }

    // ---- 齡期語意（兩閾分離）----

    @Test fun crashWindowFreshBak_restoredImmediatelyAtDefaultAge() {
        // 崩潰後數秒的 .bak（target 缺席）：即使未達 24h 預設期限也必須立即恢復。
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
        // 進行中安裝保護：新鮮 staging 不受預設 24h 期限刪除（避免誤刪他方剛啟動的安裝）。
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

    // ---- 子條目 symlink 防衛（NOFOLLOW）----

    /** 平台支援 symlink 時才測（無 symlink 環境下 noop skip）。 */
    private fun assumeSymlinkSupported(e: Exception) {
        when (e) {
            is UnsupportedOperationException ->
                org.junit.Assume.assumeNoException("platform without symlink", e)
            is java.io.IOException ->
                org.junit.Assume.assumeNoException("cannot create symlink", e)
        }
    }

    @Test fun cleanup_symlinkStaleStaging_externalContentsSurvive() {
        val parent = tempRoot()
        try {
            val root = File(parent, "skills").apply { mkdirs() }
            val outside = File(parent, "outside").apply { mkdirs() }
            val sentinel = File(outside, "sentinel.txt").apply { writeText("keep", Charsets.UTF_8) }
            val link = File(root, ".evil-skill.tmp-${System.nanoTime()}-1")
            try {
                java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            } catch (e: UnsupportedOperationException) {
                assumeSymlinkSupported(e)
            } catch (e: java.io.IOException) {
                assumeSymlinkSupported(e)
            }
            // 探針先行（NOFOLLOW 前置分類）後，link 條目不參與齡期判斷——
            // lastModified() 跟隨 symlink 的舊因果已不適用，新舊一律只解除 link
            // 本身；外部目錄設為過期僅為縱深對照（結果不依賴外部 mtime）。
            outside.setLastModified(System.currentTimeMillis() - (25L * 60 * 60 * 1000))
            val removed = SkillInstaller.cleanupStaleStaging(root)
            assertEquals(1, removed)
            assertFalse("symlink 條目應被解除（只刪 link 本身）", link.exists())
            assertTrue("外部 sentinel 必須存活（不得遞迴刪入外部目錄）", sentinel.isFile)
            assertTrue("外部目錄必須保留", outside.isDirectory)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test fun cleanup_freshSymlinkCrashBak_notRestoredAsSkill() {
        val parent = tempRoot()
        try {
            val root = File(parent, "skills").apply { mkdirs() }
            val outside = File(parent, "outside").apply { mkdirs() }
            val sentinel = File(outside, "sentinel.txt").apply { writeText("keep", Charsets.UTF_8) }
            val link = File(root, ".evil-skill.bak-${System.nanoTime()}-1")
            try {
                java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            } catch (e: UnsupportedOperationException) {
                assumeSymlinkSupported(e)
            } catch (e: java.io.IOException) {
                assumeSymlinkSupported(e)
            }
            // target 缺席 + 合法 id：崩潰還原語意不問齡期，會立刻進入處置。
            // 防衛要求：link 不得被還原成正式 skill，只解除 link 本身。
            val removed = SkillInstaller.cleanupStaleStaging(root)
            assertEquals(1, removed)
            assertFalse("外部目錄不得成為正式 skill（不得 rename link）",
                File(root, "evil-skill").exists())
            assertFalse("link 本身應被解除", link.exists())
            assertTrue("外部內容必須存活", sentinel.isFile)
            assertTrue("外部目錄必須保留", outside.isDirectory)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test fun cleanup_danglingSymlinkStaleEntry_unlinkOnly() {
        val parent = tempRoot()
        try {
            val root = File(parent, "skills").apply { mkdirs() }
            val link = File(root, ".ghost-skill.tmp-${System.nanoTime()}-1")
            try {
                java.nio.file.Files.createSymbolicLink(
                    link.toPath(),
                    File(parent, "no-such-target").toPath(),
                )
            } catch (e: UnsupportedOperationException) {
                assumeSymlinkSupported(e)
            } catch (e: java.io.IOException) {
                assumeSymlinkSupported(e)
            }
            // 探針先行後本測不依賴齡期：dangling link 經 NOFOLLOW 前置分類直接
            // 進入「只解除 link 本身」路徑（lastModified()==0 的舊因果已不適用）。
            val removed = SkillInstaller.cleanupStaleStaging(root)
            assertEquals(1, removed)
            assertFalse("dangling link 應被解除", link.exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test fun cleanup_freshLinkStagingShape_unlinkedRegardlessOfAge() {
        // 新鮮（mtime=now）的 .tmp-* symlink 條目也必須只解除 link 本身：
        // lastModified() 會跟隨 link（mtime 不可信），故 NOFOLLOW 分類必須先於
        // 齡期閘——不得因「新鮮」而把 link 留待下次收斂，外部內容更絕不可觸。
        val parent = tempRoot()
        try {
            val root = File(parent, "skills").apply { mkdirs() }
            val outside = File(parent, "outside").apply { mkdirs() }
            val sentinel = File(outside, "sentinel.txt").apply { writeText("keep", Charsets.UTF_8) }
            val link = File(root, ".evil-skill.tmp-${System.nanoTime()}-1")
            try {
                java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            } catch (e: UnsupportedOperationException) {
                assumeSymlinkSupported(e)
            } catch (e: java.io.IOException) {
                assumeSymlinkSupported(e)
            }
            // 指向物刻意保持「新鮮」：若探針晚於齡期閘，此條目會被誤保留（removed==0）。
            assertEquals(1, SkillInstaller.cleanupStaleStaging(root))
            assertFalse("新鮮 link 亦應立即解除", link.exists())
            assertTrue("外部 sentinel 必須存活", sentinel.isFile)
            assertTrue("外部目錄必須保留", outside.isDirectory)
            assertFalse("不得還原為正式 skill", File(root, "evil-skill").exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test fun cleanup_realStagingWithInnerSymlink_externalContentsSurvive() {
        val parent = tempRoot()
        try {
            val root = File(parent, "skills").apply { mkdirs() }
            val outsideFile = File(parent, "outside-file").apply { mkdirs() }
            val outsideDir = File(parent, "outside-dir").apply { mkdirs() }
            val sentinelFile = File(outsideFile, "sentinel.txt").apply {
                writeText("keep", Charsets.UTF_8)
            }
            val sentinelDir = File(outsideDir, "nested.txt").apply {
                writeText("keep", Charsets.UTF_8)
            }
            val staging = File(root, ".evil-skill.tmp-${System.nanoTime()}-1").apply { mkdirs() }
            val linkToFile = File(staging, "to-file.lnk")
            val linkToDir = File(staging, "to-dir.lnk")
            try {
                java.nio.file.Files.createSymbolicLink(
                    linkToFile.toPath(),
                    sentinelFile.toPath(),
                )
                java.nio.file.Files.createSymbolicLink(linkToDir.toPath(), outsideDir.toPath())
            } catch (e: UnsupportedOperationException) {
                assumeSymlinkSupported(e)
            } catch (e: java.io.IOException) {
                assumeSymlinkSupported(e)
            }
            staging.setLastModified(System.currentTimeMillis() - (25L * 60 * 60 * 1000))
            val removed = SkillInstaller.cleanupStaleStaging(root)
            assertEquals(1, removed)
            assertFalse("真實 staging 目錄應被安全遞迴刪除", staging.exists())
            assertTrue("內部 link 指向外部的檔案內容必須存活", sentinelFile.isFile)
            assertTrue("內部 link 指向外部的目錄內容必須存活", sentinelDir.isFile)
            assertTrue("外部目錄必須保留", outsideDir.isDirectory)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test fun cleanup_deleteBudget_cappedPerRun() {
        val root = tempRoot()
        try {
            // 建立「上限 + 7」個過期 staging 條目，證明單次呼叫只刪至上限，
            // 其餘保留、下次啟動收斂。
            val extra = 7
            val total = SkillInstaller.MAX_CLEANUP_ENTRIES_PER_RUN + extra
            for (i in 0 until total) {
                File(root, ".cap-skill-" + i.toString().padStart(3, '0') + ".tmp-1-2").apply {
                    mkdirs()
                    setLastModified(System.currentTimeMillis() - (25L * 60 * 60 * 1000))
                }
            }
            val first = SkillInstaller.cleanupStaleStaging(root)
            assertEquals("單次刪除只到上限", SkillInstaller.MAX_CLEANUP_ENTRIES_PER_RUN, first)
            val second = SkillInstaller.cleanupStaleStaging(root)
            assertEquals("剩餘條目下次啟動收斂", extra, second)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun cleanupBudget_midRun_restoreExempt() {
        // 刪除類預算吃滿的 run 內，崩潰還原（rename，O(1)）仍必須豁免上限：
        // 嶄新 destRoot（無 installed 干擾），64 個過期 staging（不同合法 id）+
        // 1 份新鮮 crash-bak（name==bakId、SKILL.md 可解析、target 缺席）→
        // 無論列舉順序：bak 照常還原（還原分支不檢查 deletionAllowed，豁免成立）、
        // 64 刪除恰好吃滿上限，removed == 上限 + 1（bak 從不擠兌刪除預算，反之亦然）。
        val root = tempRoot()
        try {
            val now = System.currentTimeMillis()
            for (i in 0 until SkillInstaller.MAX_CLEANUP_ENTRIES_PER_RUN) {
                File(root, ".cap-rst-skill-" + i.toString().padStart(3, '0') + ".tmp-1-2").apply {
                    mkdirs()
                    setLastModified(now - (25L * 60 * 60 * 1000))
                }
            }
            val tid = Thread.currentThread().id
            val bak = File(root, ".rst-skill.bak-${System.nanoTime()}-$tid").apply {
                mkdirs()
                File(this, "SKILL.md").writeText(skillMd("rst-skill", "舊版正文"), Charsets.UTF_8)
                setLastModified(System.currentTimeMillis())
            }
            val removed = SkillInstaller.cleanupStaleStaging(root)
            assertEquals(SkillInstaller.MAX_CLEANUP_ENTRIES_PER_RUN + 1, removed)
            assertTrue("crash-bak 應已還原為正式 skill", File(root, "rst-skill/SKILL.md").isFile)
            assertFalse("bak 應被消費：${root.listFiles()!!.toList()}", bak.exists())
            assertTrue(
                "64 個過期 staging 應全部收斂：${root.listFiles()!!.toList()}",
                root.listFiles()!!.none { it.name.startsWith(".cap-rst-skill") },
            )
            assertEquals(
                "第二次呼叫不該再有東西可收斂",
                0,
                SkillInstaller.cleanupStaleStaging(root),
            )
            assertTrue("還原後的 rst-skill 不得被誤觸", File(root, "rst-skill/SKILL.md").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun cleanup_symlinkUnlink_capped_perRun() {
        val parent = tempRoot()
        try {
            val root = File(parent, "skills").apply { mkdirs() }
            val outside = File(parent, "outside").apply { mkdirs() }
            val sentinel = File(outside, "sentinel.txt").apply { writeText("keep", Charsets.UTF_8) }
            val tid = Thread.currentThread().id
            val extra = 7
            val total = SkillInstaller.MAX_CLEANUP_ENTRIES_PER_RUN + extra
            val links = (0 until total).map { i ->
                val link = File(root, ".lnk-skill-" + i.toString().padStart(3, '0') + ".bak-$tid-$i")
                try {
                    java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
                } catch (e: UnsupportedOperationException) {
                    assumeSymlinkSupported(e)
                } catch (e: java.io.IOException) {
                    assumeSymlinkSupported(e)
                }
                link
            }
            // symlink unlink 同屬刪除類：單次呼叫只刪至上限，其餘保留待下次收斂；
            // 收斂期間絕不可穿透 link 觸碰外部內容。
            val first = SkillInstaller.cleanupStaleStaging(root)
            assertEquals("link 只刪至上限", SkillInstaller.MAX_CLEANUP_ENTRIES_PER_RUN, first)
            assertEquals(7, links.count { it.exists() })
            assertTrue("外部 sentinel 必須存活", sentinel.isFile)
            val second = SkillInstaller.cleanupStaleStaging(root)
            assertEquals("剩餘 link 下次啟動收斂", extra, second)
            assertEquals(0, links.count { it.exists() })
            assertTrue("外部 sentinel 必須存活", sentinel.isFile)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test fun cleanup_twoStaleBaksWithLiveTarget_convergentDelete() {
        val root = tempRoot()
        try {
            val tid = Thread.currentThread().id
            val id = "bakconv-skill"
            // target 仍在：同 id 的孤兒 .bak 形狀注定不可還原，屬純垃圾——
            // 不得因「同 id 多份」歧義而永久滯留，應在刪除預算內逐次清空。
            File(root, id).apply { mkdirs() }
            val bak1 = File(root, ".$id.bak-$tid-1").apply {
                mkdirs()
                File(this, "SKILL.md").writeText(skillMd(id, "0.1.0"), Charsets.UTF_8)
                setLastModified(System.currentTimeMillis() - (25L * 60 * 60 * 1000))
            }
            val bak2 = File(root, ".$id.bak-$tid-2").apply {
                mkdirs()
                File(this, "SKILL.md").writeText(skillMd(id, "0.2.0"), Charsets.UTF_8)
                setLastModified(System.currentTimeMillis() - (25L * 60 * 60 * 1000))
            }
            val removed = SkillInstaller.cleanupStaleStaging(root)
            assertEquals("target 仍在時兩份孤兒 bak 皆可收斂刪除", 2, removed)
            assertFalse("bak1 應刪除", bak1.exists())
            assertFalse("bak2 應刪除", bak2.exists())
            assertTrue("live target 必須原樣保留（不得被 bak 內容覆蓋或移除）", File(root, id).isDirectory)
            assertFalse(
                "live target 不得被塞入 bak 的 SKILL.md（版本無關斷言）",
                File(File(root, id), "SKILL.md").exists(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun cleanup_twoStaleBaksWithoutTarget_ambiguityKept_forRestore() {
        val root = tempRoot()
        try {
            val tid = Thread.currentThread().id
            val id = "bakamb-skill"
            // target 缺席 + 同 id 多份（形狀須匹配 BACKUP_RE 的 \d+-\d+ 尾碼，
            // 否則本測試空轉）：還原路徑無法證明新舊，全保留（資料防遺失）。
            listOf("0.1.0" to 1, "0.2.0" to 2).forEach { (ver, suffix) ->
                File(root, ".$id.bak-$tid-$suffix").apply {
                    mkdirs()
                    File(this, "SKILL.md").writeText(skillMd(id, ver), Charsets.UTF_8)
                    setLastModified(System.currentTimeMillis() - (25L * 60 * 60 * 1000))
                }
            }
            val removed = SkillInstaller.cleanupStaleStaging(root)
            assertEquals("歧義還原候選全保留", 0, removed)
            assertEquals(2, root.listFiles()!!.size)
        } finally {
            root.deleteRecursively()
        }
    }

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

    @Test fun installedVersion_symlinkSkillDir_returnsNull() {
        val parent = tempRoot()
        try {
            // 外部「已安裝」的 skill 目錄（一般檔案，SKILL.md 解析正常）。
            val real = File(parent, "real-skill").apply { mkdirs() }
            File(real, SkillInstaller.SKILL_MD).writeText(
                "---\nname: real-skill\ndescription: 正常解析\nversion: 1.2.3\n---\n正文",
                Charsets.UTF_8,
            )
            // 正向對照：真實目錄可讀出版本。
            assertEquals("1.2.3", SkillInstaller.installedVersion(real))
            val link = File(parent, "link-skill")
            try {
                java.nio.file.Files.createSymbolicLink(link.toPath(), real.toPath())
            } catch (e: UnsupportedOperationException) {
                org.junit.Assume.assumeNoException("platform without symlink", e)
            } catch (e: java.io.IOException) {
                org.junit.Assume.assumeNoException("cannot create symlink", e)
            }
            // 目錄級防衛：拒讀 symlink 型 skill 目錄（不經 link 讀外部內容）。
            assertNull(SkillInstaller.installedVersion(link))
        } finally {
            parent.deleteRecursively()
        }
    }
}
