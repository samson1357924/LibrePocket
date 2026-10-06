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
 * 非法 frontmatter/四項預算）、雜湊校驗、權限清單、scripts 永不執行、
 * 失敗不留殘餘、同名預設不覆蓋。
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
            val reason = (r as SkillInstaller.Result.Failure).reason
            assertTrue(
                reason == SkillInstaller.Failure.FILE_TOO_LARGE ||
                    reason == SkillInstaller.Failure.TOTAL_TOO_LARGE,
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

    // 避免引入 Truth/Guava：用 JDK NIO 建臨時目錄。
    private object Files {
        fun createTempDirectory(prefix: String): java.nio.file.Path =
            java.nio.file.Files.createTempDirectory(prefix)
    }
}
