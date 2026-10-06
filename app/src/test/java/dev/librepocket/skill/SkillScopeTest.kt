package dev.librepocket.skill

import dev.librepocket.tool.Flavor
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D04 Skill 作用域測試（BACKLOG D04 驗收方向）：
 * 啟用後可用工具被收斂 + 漸進揭露 + 權限清單 + 下輪生效。
 */
class SkillScopeTest {

    private fun playVisible() = ToolRegistry.visibleTools(
        ProjectionContext(
            flavor = Flavor.PLAY,
            grantedPermissions = setOf("android.permission.READ_CALENDAR"),
        ),
    )

    private fun skill(
        id: String,
        tools: Set<String>,
        body: String = "prompt $id",
        enabled: Boolean = true,
    ) = SkillDef(
        id = id,
        manifest = SkillManifest(
            name = id,
            description = "desc $id",
            allowedTools = tools,
            permissions = listOf("uses $id"),
        ),
        promptBody = body,
        enabled = enabled,
    )

    @Test fun enabledSkillConvergesVisibleTools() {
        val base = playVisible()
        assertEquals(23, base.size) // P2 11 + web.fetch (S1-B，預設開) + calendar.query/update/delete (S1-C) + clipboard/files 6 (S1-A，預設開) + voice.transcribe/speak 2 (S2，預設開；azure 僅 GITHUB)。
        val s = skill("meeting-prep", setOf("calendar.create", "alarm.create"))
        val converged = SkillScope.converge(base, listOf(s))
        assertEquals(setOf("calendar.create", "alarm.create"), converged.map { it.name }.toSet())
    }

    @Test fun disabledSkillDoesNotConverge() {
        val base = playVisible()
        val s = skill("meeting-prep", setOf("calendar.create"), enabled = false)
        assertEquals(base.map { it.name }, SkillScope.converge(base, listOf(s)).map { it.name })
    }

    @Test fun noSkillKeepsBaseUnchanged() {
        val base = playVisible()
        assertEquals(base.map { it.name }, SkillScope.converge(base, emptyList()).map { it.name })
    }

    @Test fun emptyAllowedToolsMeansNoRestriction() {
        val base = playVisible()
        val s = skill("empty", emptySet())
        assertEquals(base.size, SkillScope.converge(base, listOf(s)).size)
    }

    @Test fun multipleSkillsUnionBeforeIntersect() {
        val base = playVisible()
        val a = skill("a", setOf("navigate"))
        val b = skill("b", setOf("alarm.create"))
        val converged = SkillScope.converge(base, listOf(a, b))
        assertEquals(setOf("navigate", "alarm.create"), converged.map { it.name }.toSet())
    }

    @Test fun unknownToolNamesAreIgnoredDefensively() {
        val base = playVisible()
        val s = skill("weird", setOf("navigate", "no.such.tool"))
        val converged = SkillScope.converge(base, listOf(s))
        assertEquals(listOf("navigate"), converged.map { it.name })
    }

    @Test fun convergenceRespectsProjectionBase() {
        // calendar.create 在無授權時已不可見：即使 Skill 允許，也不會憑空出現。
        val base = ToolRegistry.visibleTools(ProjectionContext(flavor = Flavor.PLAY))
        assertTrue(base.none { it.name == "calendar.create" })
        val s = skill("meeting-prep", setOf("calendar.create", "navigate"))
        val converged = SkillScope.converge(base, listOf(s))
        assertEquals(listOf("navigate"), converged.map { it.name })
    }

    @Test fun progressiveDisclosure_indexBodyRefs() {
        val md = """
            ---
            name: invoice-tidy
            description: 整理發票的提示片段
            allowed-tools: [navigate]
            ---
            先看正文，再按需讀 refs。
        """.trimIndent()
        val index = SkillParser.parseIndex(md)
        assertEquals("invoice-tidy", index.name)
        assertEquals("整理發票的提示片段", index.description)

        val parsed = SkillParser.parse(md)
        assertEquals("invoice-tidy", parsed.manifest.name)
        assertTrue(parsed.body.contains("按需讀"))

        val dir = java.nio.file.Files.createTempDirectory("skill-refs").toFile()
        try {
            File(dir, "refs").mkdirs()
            File(dir, "refs/guide.md").writeText("詳細步驟", Charsets.UTF_8)
            val ref = SkillRefs.readRef(dir, "refs/guide.md")
            assertEquals("詳細步驟", ref)
            assertEquals(listOf("refs/guide.md"), SkillRefs.listTextRefs(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun permissionSummaryListsToolsAndNotes() {
        val s = skill("meeting-prep", setOf("calendar.create", "phone.dial"))
        val lines = SkillPermissions.summary(s)
        assertTrue(lines.any { it.contains("calendar.create") && it.contains("WRITE") })
        assertTrue(lines.any { it.contains("phone.dial") && it.contains("PRIVILEGED") })
        assertTrue(lines.any { it.startsWith("note:") })
    }

    @Test fun scriptsFlagShowsInPermissionSummary() {
        val s = skill("x", setOf("navigate")).copy(hasScripts = true)
        assertTrue(SkillPermissions.summary(s).any { it.contains("scripts") })
    }

    @Test fun registryFreezesUntilNextRound() {
        val base = playVisible()
        val registry = SkillRegistry(listOf(skill("a", setOf("navigate"))))
        // 構造：初始快照已含 a；關閉後本輪仍收斂，直到 beginRound。
        assertEquals(listOf("navigate"), registry.convergeVisible(base).map { it.name })
        registry.setEnabled("a", false)
        assertEquals(listOf("navigate"), registry.convergeVisible(base).map { it.name })
        registry.beginRound()
        assertEquals(base.map { it.name }, registry.convergeVisible(base).map { it.name })
    }

    @Test fun newlyInstalledSkillTakesEffectNextRound() {
        val base = playVisible()
        val registry = SkillRegistry()
        assertEquals(base.size, registry.convergeVisible(base).size)
        registry.install(skill("a", setOf("navigate")))
        // 登記態已啟用，但快照未更新：本輪不受影響。
        assertEquals(base.size, registry.convergeVisible(base).size)
        registry.beginRound()
        assertEquals(listOf("navigate"), registry.convergeVisible(base).map { it.name })
    }

    @Test fun promptFragmentsFollowFrozenSnapshot() {
        val registry = SkillRegistry(listOf(skill("a", setOf("navigate"), body = "AAA")))
        assertEquals(listOf("AAA"), registry.promptForRound())
        registry.setEnabled("a", false)
        assertEquals(listOf("AAA"), registry.promptForRound())
        registry.beginRound()
        assertEquals(emptyList<String>(), registry.promptForRound())
    }
}
