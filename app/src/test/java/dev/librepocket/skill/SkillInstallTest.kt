package dev.librepocket.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * D04 Skill 安裝測試（BACKLOG D04 驗收方向）：匯入校驗失敗拒裝。
 *
 * 覆蓋 Eta 安裝邊界思想的最小實現：受限解包（穿越/絕對/重複/嵌套/
 * 裸頂層條目/條目名長度上限/非法 frontmatter/四項預算的確切 Failure 值）、雜湊校驗（含
 * expectedHashes 部分覆蓋：僅清單內路徑逐項比對、引用缺檔即 HASH_MISMATCH）、
 * 權限清單、scripts 永不執行、失敗不留殘餘、同名預設不覆蓋。
 *
 * Skip 語義：installOntoSymlinkTarget_isRefused_ioError 以 [org.junit.Assume.assumeNoException]
 * 在不支援 symlink 的平台（未特權的 Windows FS 等）跳過——綠但帶 skip 的 run
 * 並非「planted symlink target 防衛」分支的覆蓋證明。除了 symlink 與
 * 「屬性不可判定」分支（後者無測試縫，行為只能真機/手動驗），本檔其餘測試
 * 為純 JVM 可重現（解包規則、預算、雜湊、frontmatter）。
 */
class SkillInstallTest {

    private fun skillMd(
        name: String = "meeting-prep",
        tools: String = "[calendar.create, alarm.create]",
    ): String = """
        ---
        name: $name
        description: 排會議提示片段
        allowed-tools: $tools
        permissions: [needs calendar read]
        ---
        幫我排會議的提示正文。
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
        Files.createTempDirectory("skill-install").toFile().apply { deleteOnExit() }

    @Test fun validZipInstallsAndEnabledByDefault() {
        val root = tempRoot()
        try {
            val zip = zipOf(
                "meeting-prep/SKILL.md" to skillMd().toByteArray(Charsets.UTF_8),
                "meeting-prep/refs/guide.md" to "步驟".toByteArray(Charsets.UTF_8),
            )
            val result = SkillInstaller.installZip(zip, root)
            assertTrue("expected success, got $result", result is SkillInstaller.Result.Success)
            val skill = (result as SkillInstaller.Result.Success).skill
            assertEquals("meeting-prep", skill.id)
            assertTrue(skill.enabled)
            assertTrue(File(root, "meeting-prep/SKILL.md").isFile)
            assertTrue(File(root, "meeting-prep/refs/guide.md").isFile)
            assertTrue(skill.fileHashes.isNotEmpty())
            assertFalse(skill.hasScripts)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun filesAndZipShareSameValidation_unknownToolRejected() {
        val root = tempRoot()
        try {
            val md = skillMd(tools = "[no.such.tool]").toByteArray(Charsets.UTF_8)
            // files 路徑（直接檔案表）：UNKNOWN_TOOL exact pin。
            val r1 = SkillInstaller.installFiles(
                mapOf("meeting-prep/SKILL.md" to md),
                root,
            )
            assertTrue(r1 is SkillInstaller.Result.Failure)
            assertEquals(
                SkillInstaller.Failure.UNKNOWN_TOOL,
                (r1 as SkillInstaller.Result.Failure).reason,
            )
            assertFalse(File(root, "meeting-prep").exists())
            assertTrue("不得留下半成品：${root.listFiles()!!.toList()}", root.listFiles()!!.isEmpty())
            // zip 路徑（壓縮同一物料）：installZip 解包後落入同一校驗鏈
            // （installFiles），同一 reason 同一形狀——本測試名稱所主張的
            // files/zip 共用驗證以實證涵蓋，不再只是推斷。
            val zip = zipOf("meeting-prep/SKILL.md" to md)
            val r2 = SkillInstaller.installZip(zip, root)
            assertTrue(r2 is SkillInstaller.Result.Failure)
            assertEquals(
                SkillInstaller.Failure.UNKNOWN_TOOL,
                (r2 as SkillInstaller.Result.Failure).reason,
            )
            assertFalse(File(root, "meeting-prep").exists())
            assertTrue("不得留下半成品：${root.listFiles()!!.toList()}", root.listFiles()!!.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun hashMismatchRejectsAndLeavesDestUntouched() {
        val root = tempRoot()
        try {
            val skillBytes = skillMd().toByteArray(Charsets.UTF_8)
            val zip = zipOf("meeting-prep/SKILL.md" to skillBytes)
            val good = mapOf("SKILL.md" to SkillHashes.sha256Hex(skillBytes))
            val ok = SkillInstaller.installZip(zip, root, expectedHashes = good)
            assertTrue(ok is SkillInstaller.Result.Success)

            val root2 = tempRoot()
            try {
                val bad = mapOf("SKILL.md" to "0".repeat(64))
                val r = SkillInstaller.installZip(zip, root2, expectedHashes = bad)
                assertTrue(r is SkillInstaller.Result.Failure)
                assertEquals(
                    SkillInstaller.Failure.HASH_MISMATCH,
                    (r as SkillInstaller.Result.Failure).reason,
                )
                assertFalse(File(root2, "meeting-prep").exists())
            } finally {
                root2.deleteRecursively()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun expectedHashes_partialCoverage_onlyListedPathsVerified() {
        val root = tempRoot()
        try {
            // 部分覆蓋（刻意設計，見 installZip KDoc）：expectedHashes 只列 SKILL.md，
            // 未列入的 refs/extra.md 不參與比對但照常落盤——增量物料不必攜全量 manifest。
            val mdBytes = skillMd().toByteArray(Charsets.UTF_8)
            val zip = zipOf(
                "meeting-prep/SKILL.md" to mdBytes,
                "meeting-prep/refs/extra.md" to "未列入的附檔".toByteArray(Charsets.UTF_8),
            )
            val expected = mapOf("SKILL.md" to SkillHashes.sha256Hex(mdBytes))
            val r = SkillInstaller.installZip(zip, root, expectedHashes = expected)
            assertTrue("expected success, got $r", r is SkillInstaller.Result.Success)
            assertTrue(File(root, "meeting-prep/refs/extra.md").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun expectedHashes_refMissingFile_rejected() {
        val root = tempRoot()
        try {
            // 清單引用了不存在的檔：與「不符」同因 HASH_MISMATCH（引用缺檔），
            // 失敗先於 staging → destRoot 不落半成品。
            val mdBytes = skillMd().toByteArray(Charsets.UTF_8)
            val zip = zipOf("meeting-prep/SKILL.md" to mdBytes)
            val expected = mapOf(
                "SKILL.md" to SkillHashes.sha256Hex(mdBytes),
                "no-such.md" to "0".repeat(64),
            )
            val r = SkillInstaller.installZip(zip, root, expectedHashes = expected)
            assertTrue("expected failure, got $r", r is SkillInstaller.Result.Failure)
            assertEquals(
                SkillInstaller.Failure.HASH_MISMATCH,
                (r as SkillInstaller.Result.Failure).reason,
            )
            assertFalse(File(root, "meeting-prep").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun pathTraversalRejected() {
        val root = tempRoot()
        try {
            val zip = zipOf(
                "meeting-prep/SKILL.md" to skillMd().toByteArray(Charsets.UTF_8),
                "meeting-prep/../../evil.txt" to "x".toByteArray(),
            )
            val r = SkillInstaller.installZip(zip, root)
            assertTrue(r is SkillInstaller.Result.Failure)
            assertEquals(
                SkillInstaller.Failure.TRAVERSAL,
                (r as SkillInstaller.Result.Failure).reason,
            )
            assertFalse(File(root, "meeting-prep").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun absolutePathRejected() {
        val root = tempRoot()
        try {
            // 絕對路徑條目（前導 '/'）：per-entry 形狀閘 checkEntryName 先於彙整閘
            // 命中 → reason 恰為 ABSOLUTE_PATH（exact，不再以 OR 收斂
            // MULTIPLE_ROOTS——那是截然不同的車道，由下方對照案釘住）。
            val r = SkillInstaller.installFiles(
                mapOf("/tmp/evil" to "x".toByteArray()),
                root,
            )
            assertTrue(r is SkillInstaller.Result.Failure)
            assertEquals(
                SkillInstaller.Failure.ABSOLUTE_PATH,
                (r as SkillInstaller.Result.Failure).reason,
            )
            assertFalse("destRoot 不得被動", File(root, "tmp").exists())
            assertTrue("不得留下半成品：${root.listFiles()!!.toList()}", root.listFiles()!!.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun multiRootPackage_rejected_exactReason() {
        // 對照案（exact 車道分流）：兩個頂層根確實走 MULTIPLE_ROOTS（彙整閘文案），
        // 與絕對路徑車道（per-entry checkEntryName）分流清楚。
        val root = tempRoot()
        try {
            val r = SkillInstaller.installFiles(
                mapOf(
                    "alpha/SKILL.md" to skillMd(name = "alpha").toByteArray(Charsets.UTF_8),
                    "beta/notes.txt" to "x".toByteArray(Charsets.UTF_8),
                ),
                root,
            )
            assertTrue(r is SkillInstaller.Result.Failure)
            val f = r as SkillInstaller.Result.Failure
            assertEquals(SkillInstaller.Failure.MULTIPLE_ROOTS, f.reason)
            assertTrue(
                "message 應指名單一根主張：${f.message}",
                "exactly one skill directory" in f.message,
            )
            assertFalse(File(root, "alpha").exists())
            assertFalse(File(root, "beta").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun duplicateEntryRejected() {
        val root = tempRoot()
        try {
            // ZipOutputStream 本身拒絕同名條目，故以具現化檔案表覆蓋同一歸一化路徑：
            // "meeting-prep/./SKILL.md" 與 "meeting-prep/SKILL.md" 歸一後重複。
            val r = SkillInstaller.installFiles(
                mapOf(
                    "meeting-prep/SKILL.md" to skillMd().toByteArray(Charsets.UTF_8),
                    "meeting-prep/./SKILL.md" to skillMd().toByteArray(Charsets.UTF_8),
                ),
                root,
            )
            assertTrue(r is SkillInstaller.Result.Failure)
            assertEquals(
                SkillInstaller.Failure.DUPLICATE_ENTRY,
                (r as SkillInstaller.Result.Failure).reason,
            )
            assertFalse(File(root, "meeting-prep").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun nestedSkillRejected() {
        val root = tempRoot()
        try {
            val zip = zipOf(
                "meeting-prep/SKILL.md" to skillMd("meeting-prep").toByteArray(Charsets.UTF_8),
                "meeting-prep/nested/SKILL.md" to skillMd("nested").toByteArray(Charsets.UTF_8),
            )
            val r = SkillInstaller.installZip(zip, root)
            assertTrue(r is SkillInstaller.Result.Failure)
            assertEquals(
                SkillInstaller.Failure.NESTED_SKILL,
                (r as SkillInstaller.Result.Failure).reason,
            )
            assertFalse(File(root, "meeting-prep").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun bareTopLevelEntry_files_rejectedShaped_notIoError() {
        val root = tempRoot()
        try {
            // 裸頂層 key（無 '/'，等於 root 名）必須在彙整校驗被顯式拒絕：
            // 修前會把該 key 當 rel 落成根內頂層雜檔（staging 寫出 stray file）、
            // 或同名深層條目 mkdirs 撞案後以 IO_ERROR 失敗蓋過形狀 reason（語意
            // 漂移）。修後：MULTIPLE_ROOTS（頂層形狀違規的既有車道）＋ 訊息僅
            // 回顯條目名（相對形，不洩內部路徑）；destRoot 無半成品。
            val r = SkillInstaller.installFiles(
                linkedMapOf<String, ByteArray>(
                    // manifest name 用合法 id（xx）；本 key（裸頂層「x」）在彙整
                    // 階段即被拒，frontmatter 根本不會被解析。
                    "x" to "stray".toByteArray(Charsets.UTF_8),
                    "x/SKILL.md" to skillMd(name = "xx").toByteArray(Charsets.UTF_8),
                ),
                root,
            )
            assertTrue("expected failure, got $r", r is SkillInstaller.Result.Failure)
            val f = r as SkillInstaller.Result.Failure
            assertEquals(SkillInstaller.Failure.MULTIPLE_ROOTS, f.reason)
            assertTrue("message 應指名裸頂層條目：${f.message}", "bare top-level" in f.message)
            assertTrue(
                "訊息僅回顯條目名（不洩內部路徑）：${f.message}",
                f.message.endsWith(": x"),
            )
            assertFalse("destRoot 不得被動", File(root, "x").exists())
            assertTrue(
                "不得留下半成品：${root.listFiles()!!.toList()}",
                root.listFiles()!!.isEmpty(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun bareTopLevelEntry_zip_rejectedShaped_notIoError() {
        val root = tempRoot()
        try {
            // zip 條目同名（檔案型裸頂層條目）：解包後與 files 路徑共用彙整
            // 校驗，同一形狀 reason；錯誤碼面不得出現 IO_ERROR。
            val zip = zipOf(
                "x" to "stray".toByteArray(Charsets.UTF_8),
                "x/SKILL.md" to skillMd(name = "xx").toByteArray(Charsets.UTF_8),
            )
            val r = SkillInstaller.installZip(zip, root)
            assertTrue("expected failure, got $r", r is SkillInstaller.Result.Failure)
            val f = r as SkillInstaller.Result.Failure
            assertEquals(SkillInstaller.Failure.MULTIPLE_ROOTS, f.reason)
            assertTrue("message 應指名裸頂層條目：${f.message}", "bare top-level" in f.message)
            assertFalse(File(root, "x").exists())
            assertTrue(
                "不得留下半成品：${root.listFiles()!!.toList()}",
                root.listFiles()!!.isEmpty(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun entryNameOverLimit_rejectedShaped_notEnametoolongIoDrift() {
        val root = tempRoot()
        try {
            // 名稱位元組上限（[SkillInstaller.MAX_ENTRY_NAME_BYTES]＝4096）：
            // 超限名在彙整校驗（[SkillInstaller.checkEntryName]——zip 與 files
            // 兩路徑共用）即以形狀 reason（TRAVERSAL 車道）拒絕，不再落入
            // staging 寫入端以 ENAMETOOLONG 收斂成 IO_ERROR（語意漂移防住）；
            // 訊息僅回長度預算、不回顯全文（超限名可為 hostile 供給）。
            val longName = "a".repeat(SkillInstaller.MAX_ENTRY_NAME_BYTES + 1)
            val rFiles = SkillInstaller.installFiles(
                linkedMapOf<String, ByteArray>(
                    "meeting-prep/SKILL.md" to skillMd().toByteArray(Charsets.UTF_8),
                    longName to "x".toByteArray(),
                ),
                root,
            )
            assertTrue("expected failure, got $rFiles", rFiles is SkillInstaller.Result.Failure)
            val fFiles = rFiles as SkillInstaller.Result.Failure
            assertEquals(SkillInstaller.Failure.TRAVERSAL, fFiles.reason)
            assertTrue(
                "message 應指名長度預算：${fFiles.message}",
                "entry name too long" in fFiles.message,
            )
            assertFalse("訊息不回顯全文", longName in fFiles.message)
            assertFalse(File(root, "meeting-prep").exists())
            assertTrue(
                "不得留下半成品：${root.listFiles()!!.toList()}",
                root.listFiles()!!.isEmpty(),
            )

            val zip = zipOf(
                "meeting-prep/SKILL.md" to skillMd().toByteArray(Charsets.UTF_8),
                longName to "x".toByteArray(),
            )
            val rZip = SkillInstaller.installZip(zip, root)
            assertTrue("expected failure, got $rZip", rZip is SkillInstaller.Result.Failure)
            assertEquals(
                SkillInstaller.Failure.TRAVERSAL,
                (rZip as SkillInstaller.Result.Failure).reason,
            )
            assertTrue("entry name too long" in rZip.message)
            assertTrue(root.listFiles()!!.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun badFrontmatterRejected_missingName() {
        val root = tempRoot()
        try {
            val bad = """
                ---
                description: 缺少 name
                ---
                body
            """.trimIndent()
            val zip = zipOf("meeting-prep/SKILL.md" to bad.toByteArray(Charsets.UTF_8))
            val r = SkillInstaller.installZip(zip, root)
            assertTrue(r is SkillInstaller.Result.Failure)
            assertEquals(
                SkillInstaller.Failure.BAD_FRONTMATTER,
                (r as SkillInstaller.Result.Failure).reason,
            )
            assertFalse(File(root, "meeting-prep").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun missingSkillMdRejected() {
        val root = tempRoot()
        try {
            val zip = zipOf("meeting-prep/refs/a.md" to "hi".toByteArray())
            val r = SkillInstaller.installZip(zip, root)
            assertTrue(r is SkillInstaller.Result.Failure)
            assertEquals(
                SkillInstaller.Failure.MISSING_SKILL_MD,
                (r as SkillInstaller.Result.Failure).reason,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun nameMismatchRejected() {
        val root = tempRoot()
        try {
            val zip = zipOf(
                "dir-a/SKILL.md" to skillMd("other-name").toByteArray(Charsets.UTF_8),
            )
            val r = SkillInstaller.installZip(zip, root)
            assertTrue(r is SkillInstaller.Result.Failure)
            assertEquals(
                SkillInstaller.Failure.NAME_MISMATCH,
                (r as SkillInstaller.Result.Failure).reason,
            )
            assertFalse(File(root, "dir-a").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun singleFileBudgetRejected() {
        val root = tempRoot()
        try {
            val big = ByteArray((SkillInstaller.MAX_SINGLE_FILE_BYTES + 1).toInt()) { 0x41 }
            val zip = zipOf(
                "meeting-prep/SKILL.md" to skillMd().toByteArray(Charsets.UTF_8),
                "meeting-prep/big.bin" to big,
            )
            val r = SkillInstaller.installZip(zip, root)
            assertTrue(r is SkillInstaller.Result.Failure)
            // 確切 reason 釘選（非 OR 收斂）：SKILL.md 極小且總量未達 MAX_TOTAL_BYTES，
            // 解包流式與 files 兩路徑的 fileSize 閘都先於 total 閘觸發 → FILE_TOO_LARGE。
            assertEquals(
                SkillInstaller.Failure.FILE_TOO_LARGE,
                (r as SkillInstaller.Result.Failure).reason,
            )
            assertFalse(File(root, "meeting-prep").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun scriptsAreStoredButNeverExecutedAndNeverLoadedAsRefs() {
        val root = tempRoot()
        try {
            val zip = zipOf(
                "meeting-prep/SKILL.md" to skillMd().toByteArray(Charsets.UTF_8),
                "meeting-prep/scripts/run.sh" to "echo pwned".toByteArray(Charsets.UTF_8),
            )
            val r = SkillInstaller.installZip(zip, root)
            assertTrue(r is SkillInstaller.Result.Success)
            val skill = (r as SkillInstaller.Result.Success).skill
            assertTrue(skill.hasScripts)
            // 落盤但測試全程未執行任何外部進程；此處斷言附屬讀取拒絕 scripts。
            val dir = File(root, "meeting-prep")
            try {
                SkillRefs.readRef(dir, "scripts/run.sh")
                fail("scripts must not be loadable as refs")
            } catch (e: SkillRefs.RefException) {
                // 預期拒絕。
            }
            assertTrue(SkillPermissions.summary(skill).any { it.contains("never executed") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun sameNameKeepsExistingByDefault() {
        val root = tempRoot()
        try {
            val v1 = zipOf("meeting-prep/SKILL.md" to skillMd().toByteArray(Charsets.UTF_8))
            assertTrue(SkillInstaller.installZip(v1, root) is SkillInstaller.Result.Success)
            val before = File(root, "meeting-prep/SKILL.md").readText(Charsets.UTF_8)

            val v2md = skillMd().replace("幫我排會議", "第二版正文")
            val v2 = zipOf("meeting-prep/SKILL.md" to v2md.toByteArray(Charsets.UTF_8))
            val r = SkillInstaller.installZip(v2, root, replace = false)
            assertTrue(r is SkillInstaller.Result.Failure)
            assertEquals(
                SkillInstaller.Failure.ALREADY_EXISTS,
                (r as SkillInstaller.Result.Failure).reason,
            )
            assertEquals(before, File(root, "meeting-prep/SKILL.md").readText(Charsets.UTF_8))

            val r2 = SkillInstaller.installZip(v2, root, replace = true)
            assertTrue(r2 is SkillInstaller.Result.Success)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun installOntoSymlinkTarget_isRefused_ioError() {
        val parent = Files.createTempDirectory("skill-install-link").toFile().apply {
            deleteOnExit()
        }
        try {
            val root = File(parent, "skills").apply { mkdirs() }
            val outside = File(parent, "outside").apply { mkdirs() }
            val sentinel = File(outside, "sentinel.txt").apply { writeText("keep", Charsets.UTF_8) }
            val target = File(root, "meeting-prep")
            try {
                java.nio.file.Files.createSymbolicLink(target.toPath(), outside.toPath())
            } catch (e: UnsupportedOperationException) {
                org.junit.Assume.assumeNoException("platform without symlink", e)
            } catch (e: java.io.IOException) {
                org.junit.Assume.assumeNoException("cannot create symlink", e)
            }
            val zip = zipOf(
                "meeting-prep/SKILL.md" to skillMd().toByteArray(Charsets.UTF_8),
            )
            // 覆蓋（replace=true）到 planted symlink target：必須被拒（fail-closed），
            // 不得對 link 進行 rename→bak→deleteRecursively（波及外部內容）。
            val r = SkillInstaller.installZip(zip, root, replace = true)
            assertTrue("expected failure, got $r", r is SkillInstaller.Result.Failure)
            assertEquals(
                SkillInstaller.Failure.IO_ERROR,
                (r as SkillInstaller.Result.Failure).reason,
            )
            assertTrue("外部 sentinel 必須存活", sentinel.isFile)
            assertTrue("planted link 不得被搬移或刪除", java.nio.file.Files.isSymbolicLink(target.toPath()))
            // 非 replace 的新鮮安裝對 link target 同樣以 IO_ERROR 拒絕（target
            // NOFOLLOW 三態探針先於 ALREADY_EXISTS 的佔位契約，link 型一律
            // 拒絕，見 SkillInstaller target 探針註記），不受本測影響。
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test fun installOntoDanglingLinkTarget_isRefused_ioError_withControls() {
        val parent = Files.createTempDirectory("skill-install-link").toFile().apply {
            deleteOnExit()
        }
        try {
            val root = File(parent, "skills").apply { mkdirs() }
            val nowhere = File(parent, "nowhere") // 刻意不存在：link 恆懸空
            val zip = zipOf(
                "meeting-prep/SKILL.md" to skillMd().toByteArray(Charsets.UTF_8),
            )
            val target = File(root, "meeting-prep")
            try {
                java.nio.file.Files.createSymbolicLink(target.toPath(), nowhere.toPath())
            } catch (e: UnsupportedOperationException) {
                org.junit.Assume.assumeNoException("platform without symlink", e)
            } catch (e: java.io.IOException) {
                org.junit.Assume.assumeNoException("cannot create symlink", e)
            }
            // 懸空 link：跟隨 link 的 exists() 曾把它誤判成「缺席」——新鮮安裝
            // （replace=false）過去會 rename 把植入 link 吃掉。NOFOLLOW 三態
            // 探針讀得屬性 → PRESENT（被佔，與 cleanup 的「dangling ⊄ absent」
            // 立場一致），fresh 安裝即走 symlink 拒絕車道，訊息與上方真 link
            // 釘案同一字句（installOntoSymlinkTarget_isRefused_ioError）。
            val r = SkillInstaller.installZip(zip, root)
            assertTrue("expected failure, got $r", r is SkillInstaller.Result.Failure)
            assertEquals(
                SkillInstaller.Failure.IO_ERROR,
                (r as SkillInstaller.Result.Failure).reason,
            )
            assertTrue(
                "訊息應與真 symlink target 案同字句：${(r as SkillInstaller.Result.Failure).message}",
                "target must not be symlink" in (r as SkillInstaller.Result.Failure).message,
            )
            // 實證「不波及據點」：植入 link 本身存活、未被 rename 吃掉；
            // destRoot 無半成品（唯一允許存在的條目就是植入 link 本身——
            // 設定的 link 不是本程序的財產，拒絕路徑不移除它；
            // staging 已清理）；連結指向物（缺席）未被創造。
            assertTrue(
                "懸空 link 不得被搬移或吃掉",
                java.nio.file.Files.isSymbolicLink(target.toPath()),
            )
            val entries = root.listFiles()!!.toList()
            assertEquals(
                "不得殘留半成品（僅植入 link 本身存活）：$entries",
                listOf(target),
                entries,
            )
            assertFalse("不得經 link 造出外部內容", nowhere.exists())

            // 正向對照一：fresh 安裝到真缺席 target（無 link）照常成功。
            val root2 = File(parent, "skills2").apply { mkdirs() }
            val rOk = SkillInstaller.installZip(zip, root2)
            assertTrue("fresh install onto ABSENT target must succeed, got $rOk",
                rOk is SkillInstaller.Result.Success)
            assertTrue(File(root2, "meeting-prep/SKILL.md").isFile)

            // 正向對照二：非 link 的被佔槽位（真安裝目錄）+ replace=false
            // 仍走既有 ALREADY_EXISTS 契約。
            val rAgain = SkillInstaller.installZip(zip, root2)
            assertTrue("expected failure, got $rAgain", rAgain is SkillInstaller.Result.Failure)
            assertEquals(
                SkillInstaller.Failure.ALREADY_EXISTS,
                (rAgain as SkillInstaller.Result.Failure).reason,
            )
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test fun zipDirEntryTraversalName_rejected_sameShapeLaneAsFiles() {
        val root = tempRoot()
        try {
            // 目錄條目（尾隨 '/'）與檔型條目同一名形狀閘（條目計數建制不變）：
            // `evil/../x/` 剝掉恰一個尾隨 '/' 後的 "evil/../x" 仍屬穿越——
            // 與檔名形狀同一 TRAVERSAL 車道、同一「path traversal: …」訊息。
            // 解包在記憶體中止、未到 staging → destRoot 無半成品、無 extraction
            // 殘餘。目錄條目置於首位：證明拒絕在消費任何檔條目之前命中。
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { zos ->
                val dir = ZipEntry("evil/../x/")
                zos.putNextEntry(dir)
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("meeting-prep/SKILL.md"))
                zos.write(skillMd().toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            val r = SkillInstaller.installZip(bos.toByteArray(), root)
            assertTrue("expected failure, got $r", r is SkillInstaller.Result.Failure)
            assertEquals(
                SkillInstaller.Failure.TRAVERSAL,
                (r as SkillInstaller.Result.Failure).reason,
            )
            assertTrue(
                "訊息應指名穿越：${(r as SkillInstaller.Result.Failure).message}",
                "path traversal" in (r as SkillInstaller.Result.Failure).message,
            )
            assertFalse("未落盤任何檔條目", File(root, "meeting-prep").exists())
            assertFalse("不容穿越目錄名實體化", File(root, "evil").exists())
            assertTrue(
                "不得留下任何解包殘餘：${root.listFiles()!!.toList()}",
                root.listFiles()!!.isEmpty(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun zipDirEntries_benignStillInstall_noKeysForDirs() {
        val root = tempRoot()
        try {
            // 對照（良性）：帶目錄條目的正常物料照常安裝成功——形狀閘剝尾隨
            // '/' 後放行 "meeting-prep/refs/"，且目錄條目照舊不產檔案表鍵
            //（否則裸頂層名 "meeting-prep" 會誤觸 installFiles 的
            // MULTIPLE_ROOTS、封殺帶目錄條目的正常物料）。目錄條目亦不實體化
            //（鍵僅屬檔條目），故只斷言檔案落盤與成功。
            //（誠實標註：本測為 positive control，非 fail-old 判別——舊碼
            // 跳過目錄條目本就成功；它擋的是「尾斜體剝離缺漏 / 目錄條目誤產鍵」。）
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { zos ->
                val dir = ZipEntry("meeting-prep/refs/")
                zos.putNextEntry(dir)
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("meeting-prep/SKILL.md"))
                zos.write(skillMd().toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            val r = SkillInstaller.installZip(bos.toByteArray(), root)
            assertTrue("expected success, got $r", r is SkillInstaller.Result.Success)
            assertEquals("meeting-prep", (r as SkillInstaller.Result.Success).skill.id)
            assertTrue(File(root, "meeting-prep/SKILL.md").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun oversizedEntryName_hitsLengthCapBeforeShapeEchoLanes() {
        val root = tempRoot()
        try {
            // >4KB 且形狀違規（含中段 "../"）的條目名：位元組上限現為形狀規則
            // 最前哨——超限名整體只收斂到「entry name too long」（TRAVERSAL
            // 車道，訊息僅回長度預算、訊息全文被釘死故必不含 4KB+ 原名），
            // 不再先經形狀回顯車道把全名洩進訊息（舊序會先命中
            // "path traversal: $raw" 回顯全文）。
            val longName = "a/../" + "b".repeat(SkillInstaller.MAX_ENTRY_NAME_BYTES)
            val rFiles = SkillInstaller.installFiles(
                linkedMapOf<String, ByteArray>(
                    "meeting-prep/SKILL.md" to skillMd().toByteArray(Charsets.UTF_8),
                    longName to "x".toByteArray(),
                ),
                root,
            )
            assertTrue("expected failure, got $rFiles", rFiles is SkillInstaller.Result.Failure)
            val fFiles = rFiles as SkillInstaller.Result.Failure
            assertEquals(SkillInstaller.Failure.TRAVERSAL, fFiles.reason)
            assertEquals(
                "訊息全文釘死（超限名不得有任何回顯）：${fFiles.message}",
                "entry name too long (max ${SkillInstaller.MAX_ENTRY_NAME_BYTES} bytes)",
                fFiles.message,
            )
            assertFalse("must not echo the oversized raw name", longName in fFiles.message)
            assertTrue(
                "不得留下半成品：${root.listFiles()!!.toList()}",
                root.listFiles()!!.isEmpty(),
            )

            // zip 路徑（同一共用 helper，zip 與 files 兩框架匯流處一致）。
            val zip = zipOf(
                "meeting-prep/SKILL.md" to skillMd().toByteArray(Charsets.UTF_8),
                longName to "x".toByteArray(),
            )
            val rZip = SkillInstaller.installZip(zip, root)
            assertTrue("expected failure, got $rZip", rZip is SkillInstaller.Result.Failure)
            val fZip = rZip as SkillInstaller.Result.Failure
            assertEquals(SkillInstaller.Failure.TRAVERSAL, fZip.reason)
            assertEquals(
                "entry name too long (max ${SkillInstaller.MAX_ENTRY_NAME_BYTES} bytes)",
                fZip.message,
            )
            assertFalse("must not echo the oversized raw name", longName in fZip.message)
            assertTrue(root.listFiles()!!.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun tooManyEntries_zip_rejected() {
        val root = tempRoot()
        try {
            // MAX_ENTRIES+1 條目（先一個合法 SKILL.md，再 64 個 filler）：
            // 解包側條目計數必須在第 65 個條目中止，給出確切 TOO_MANY_ENTRIES；
            // 解包在記憶體中止、未及落盤 → destRoot 無半成品、無 staging 殘餘。
            val entries: Array<Pair<String, ByteArray>> =
                arrayOf("meeting-prep/SKILL.md" to skillMd().toByteArray(Charsets.UTF_8)) +
                    (0 until SkillInstaller.MAX_ENTRIES).map { i ->
                        "filler-" + i.toString().padStart(3, '0') + ".dat" to "x".toByteArray()
                    }
            val zip = zipOf(*entries)
            val r = SkillInstaller.installZip(zip, root)
            assertTrue("expected failure, got $r", r is SkillInstaller.Result.Failure)
            assertEquals(
                SkillInstaller.Failure.TOO_MANY_ENTRIES,
                (r as SkillInstaller.Result.Failure).reason,
            )
            assertFalse(File(root, "meeting-prep").exists())
            assertTrue(
                "不得留下任何解包殘餘：${root.listFiles()!!.toList()}",
                root.listFiles()!!.isEmpty(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun archiveBudgetExceeded_zip_rejected() {
        val root = tempRoot()
        try {
            // 壓縮後歸檔 >MAX_ARCHIVE_BYTES：隨機（不可壓縮）資料鬆弛不出縮減，
            // 單條目即超——歸檔預算在解包「之前」檢查，必須給出確切
            // ARCHIVE_TOO_LARGE，destRoot 無半成品。
            val random = java.util.Random(524L)
            val incompressible = ByteArray(2_400_000).also { random.nextBytes(it) }
            val zip = zipOf(
                "meeting-prep/SKILL.md" to skillMd().toByteArray(Charsets.UTF_8),
                "meeting-prep/big.bin" to incompressible,
            )
            val r = SkillInstaller.installZip(zip, root)
            assertTrue("expected failure, got $r", r is SkillInstaller.Result.Failure)
            assertEquals(
                SkillInstaller.Failure.ARCHIVE_TOO_LARGE,
                (r as SkillInstaller.Result.Failure).reason,
            )
            // M2 收緊（review 建議）：歸檔閘在解包前 ⇒ 失敗時 destRoot 必空。
            assertTrue(
                "解包前失敗，destRoot 不得有任何殘餘：${root.listFiles()!!.toList()}",
                root.listFiles()!!.isEmpty(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun singleFileBudget_filesPath_exactReason_rejected() {
        val root = tempRoot()
        try {
            // files 路徑單檔恰超 MAX_SINGLE_FILE_BYTES（總量未達 MAX_TOTAL_BYTES）：
            // 規格化迴圈逐檔比對必須給出確切 FILE_TOO_LARGE（非 OR 收斂），
            // 失敗先於 staging → destRoot 不落半成品。
            val big = ByteArray((SkillInstaller.MAX_SINGLE_FILE_BYTES + 1).toInt()) { 0x41 }
            val r = SkillInstaller.installFiles(
                linkedMapOf<String, ByteArray>(
                    "meeting-prep/SKILL.md" to skillMd().toByteArray(Charsets.UTF_8),
                    "meeting-prep/big.bin" to big,
                ),
                root,
            )
            assertTrue("expected failure, got $r", r is SkillInstaller.Result.Failure)
            assertEquals(
                SkillInstaller.Failure.FILE_TOO_LARGE,
                (r as SkillInstaller.Result.Failure).reason,
            )
            assertFalse(File(root, "meeting-prep").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun totalBudgetMidStream_filesAndZip_exactReason_rejected() {
        val root = tempRoot()
        try {
            // 六檔各 180_000B（每檔 < MAX_SINGLE_FILE_BYTES、五檔後合計仍 < MAX_TOTAL_BYTES）：
            // 總量在第六檔內部中途越過 MAX_TOTAL_BYTES。files 與 zip 解包流式兩路徑
            // 都必須收斂到同一確切 reason TOTAL_TOO_LARGE（非 OR）；
            // 靜態推導定案：zip 路徑在 unzipConstrained 的逐條目流式累加中於
            // data-05.bin 剩餘位元組內觸發（900,2xx + <180,000 > 1,048,576），
            // 屬解包中段、非前置規格化階段。
            val big = ByteArray(180_000) { 0x41 }
            val files = linkedMapOf<String, ByteArray>(
                "meeting-prep/SKILL.md" to skillMd().toByteArray(Charsets.UTF_8),
            )
            (0 until 6).forEach { i ->
                files["meeting-prep/data-" + i.toString().padStart(2, '0') + ".bin"] = big
            }
            val rFiles = SkillInstaller.installFiles(files, root)
            assertTrue("expected failure, got $rFiles", rFiles is SkillInstaller.Result.Failure)
            assertEquals(
                SkillInstaller.Failure.TOTAL_TOO_LARGE,
                (rFiles as SkillInstaller.Result.Failure).reason,
            )
            assertFalse(File(root, "meeting-prep").exists())

            val zip = zipOf(
                "meeting-prep/SKILL.md" to skillMd().toByteArray(Charsets.UTF_8),
                "meeting-prep/data-00.bin" to big,
                "meeting-prep/data-01.bin" to big,
                "meeting-prep/data-02.bin" to big,
                "meeting-prep/data-03.bin" to big,
                "meeting-prep/data-04.bin" to big,
                "meeting-prep/data-05.bin" to big,
            )
            val rZip = SkillInstaller.installZip(zip, root)
            assertTrue("expected failure, got $rZip", rZip is SkillInstaller.Result.Failure)
            assertEquals(
                SkillInstaller.Failure.TOTAL_TOO_LARGE,
                (rZip as SkillInstaller.Result.Failure).reason,
            )
            assertFalse(File(root, "meeting-prep").exists())
            assertTrue(
                "zip 路徑不得留下 staging 半成品：${root.listFiles()!!.toList()}",
                root.listFiles()!!.none { it.name.startsWith(".") },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    // 避免引入 Truth/Guava：用 JDK NIO 建臨時目錄。
    private object Files {
        fun createTempDirectory(prefix: String): java.nio.file.Path =
            java.nio.file.Files.createTempDirectory(prefix)
    }
}
