package dev.librepocket.skill

import dev.librepocket.tool.ToolDef
import dev.librepocket.tool.ToolRegistry

/**
 * Skill 作用域收斂（ARCHITECTURE §10.2）。
 *
 * 啟用 Skill 後，模型可用工具被收斂到 Skill 聲明子集與能力投影可見集的交集：
 * `converge = baseVisible ∩ (⋃ enabled.allowedTools)`。
 * - 未啟用任何 Skill（或啟用的 Skill 都未聲明 allowedTools）→ 不收斂，原樣返回；
 * - 未知工具名防禦性忽略（安裝期已拒絕未知工具，此處為縱深）；
 * - 保持 baseVisible 原順序；不改變投影等級，只做子集過濾。
 */
object SkillScope {
    fun converge(baseVisible: List<ToolDef>, enabled: List<SkillDef>): List<ToolDef> {
        val active = enabled.filter { it.enabled }
        if (active.isEmpty()) return baseVisible
        val allowed = active.flatMap { it.manifest.allowedTools }.toSet()
        if (allowed.isEmpty()) return baseVisible
        return baseVisible.filter { it.name in allowed }
    }

    /** 啟用 Skill 的提示片段（按 Skill id 排序，保證純函數穩定）。 */
    fun promptFragments(enabled: List<SkillDef>): List<String> =
        enabled.filter { it.enabled }.sortedBy { it.id }.map { it.promptBody }.filter { it.isNotEmpty() }

    /** L1 索引列表（安裝頁/規劃頁只展示此層）。 */
    fun indexList(all: List<SkillDef>): List<SkillIndex> =
        all.sortedBy { it.id }.map { it.index() }
}

/**
 * Skill 登記表：安裝清單 + 使用者開關 + 本輪凍結快照。
 *
 * 「下輪生效」語義：模型调用前已凍結本輪可見索引（見 Eta 安裝邊界思想）。
 * [setEnabled]/[install] 只改變登記態；[beginRound] 後快照才更新，
 * [convergeVisible]/[promptForRound] 永遠讀快照，不讀即時開關。
 */
class SkillRegistry(initial: List<SkillDef> = emptyList()) {
    private val installed = LinkedHashMap<String, SkillDef>()
    private val enabledIds = LinkedHashSet<String>()
    private var frozen: List<SkillDef> = emptyList()

    init {
        for (s in initial) {
            installed[s.id] = s
            if (s.enabled) enabledIds.add(s.id)
        }
        frozen = snapshot()
    }

    private fun snapshot(): List<SkillDef> =
        enabledIds.mapNotNull { installed[it] }.sortedBy { it.id }.map { it.copy(enabled = true) }

    fun install(skill: SkillDef) {
        installed[skill.id] = skill
        if (skill.enabled) enabledIds.add(skill.id) else enabledIds.remove(skill.id)
        // 不碰 frozenIds：新 Skill 下輪才可見。
    }

    fun uninstall(id: String): Boolean {
        val removed = installed.remove(id) != null
        enabledIds.remove(id)
        return removed
    }

    /** 使用者開關；只影響登記態，本輪快照不變（下輪生效）。 */
    fun setEnabled(id: String, enabled: Boolean) {
        val cur = installed[id] ?: return
        installed[id] = cur.copy(enabled = enabled)
        if (enabled) enabledIds.add(id) else enabledIds.remove(id)
    }

    fun isEnabled(id: String): Boolean = id in enabledIds

    fun all(): List<SkillDef> = installed.values.sortedBy { it.id }

    /** 即時啟用集（設定頁顯示用；規劃一律用 [frozenEnabled]）。 */
    fun enabledNow(): List<SkillDef> = enabledIds.mapNotNull { installed[it] }.sortedBy { it.id }

    /** 本輪凍結的啟用集（規劃用）。 */
    fun frozenEnabled(): List<SkillDef> = frozen

    /** 凍結當前開關為下一輪快照；上一輪進行中不受影響。 */
    fun beginRound() {
        frozen = snapshot()
    }

    /** 本輪可用工具（baseVisible 先經能力投影，再經 Skill 收斂）。 */
    fun convergeVisible(baseVisible: List<ToolDef>): List<ToolDef> =
        SkillScope.converge(baseVisible, frozenEnabled())

    /** 本輪提示片段（已凍結）。 */
    fun promptForRound(): List<String> = SkillScope.promptFragments(frozenEnabled())

    fun find(id: String): SkillDef? = installed[id]

    companion object {
        /** 無 Skill 時的透傳：與 [SkillScope.converge] 空啟用語義一致。 */
        fun convergeStatic(baseVisible: List<ToolDef>, enabled: List<SkillDef>): List<ToolDef> =
            SkillScope.converge(baseVisible, enabled)
    }
}

/**
 * 權限清單展示（安裝前向使用者明示）。
 *
 * 每行對應 Skill 聲明的一個工具：`tool 名 (副作用等級) [+ 額外權限說明]`；
 * 未知工具標 `unknown`（安裝期本應已拒絕，此處僅展示層防禦）。
 * Skill 自帶的 `permissions` 原字串逐條透出為 `note:` 行。
 */
object SkillPermissions {
    fun summary(skill: SkillDef): List<String> {
        val lines = ArrayList<String>()
        val tools = skill.manifest.allowedTools.sorted()
        if (tools.isEmpty()) {
            lines.add("tools: (none declared — no planning restriction)")
        } else {
            for (name in tools) {
                val def: ToolDef? = ToolRegistry.find(name)
                if (def == null) {
                    lines.add("tool:$name (unknown)")
                } else {
                    val extra = def.annotations.requiresPermission?.let { " needs $it" } ?: ""
                    lines.add("tool:$name (${def.sideEffect})$extra")
                }
            }
        }
        for (p in skill.manifest.permissions) {
            lines.add("note:$p")
        }
        if (skill.hasScripts) {
            lines.add("note:contains scripts/ (stored only, never executed)")
        }
        return lines
    }

    fun summaryText(skill: SkillDef): String = summary(skill).joinToString("\n")
}
