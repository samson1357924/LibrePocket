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
 * 非法 frontmatter/四項預算的確切 Failure 值）、雜湊校驗（含
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
            val files = mapOf(
                "meeting-prep/SKILL.md" to skillMd(tools = "[no.such.tool]").toByteArray(Charsets.UTF_8),
            )
            val r1 = SkillInstaller.installFiles(files, root)
            assertTrue(r1 is SkillInstaller.Result.Failure)
            assertEquals(
                SkillInstaller.Failure.UNKNOWN_TOOL,
                (r1 as SkillInstaller.Result.Failure).reason,
            )
            assertFalse(File(root, "meeting-prep").exists())
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
            val r = SkillInstaller.installFiles(
                mapOf("/tmp/evil" to "x".toByteArray()),
                root,
            )
            assertTrue(r is SkillInstaller.Result.Failure)
            val reason = (r as SkillInstaller.Result.Failure).reason
            assertTrue(reason == SkillInstaller.Failure.ABSOLUTE_PATH || reason == SkillInstaller.Failure.MULTIPLE_ROOTS)
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
            // 非 replace 的新鮮安裝在 ALREADY_EXISTS 檢查就先擋，不受本測影響。
        } finally {
            parent.deleteRecursively()
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
