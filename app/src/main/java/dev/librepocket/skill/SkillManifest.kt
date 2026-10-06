package dev.librepocket.skill

/**
 * Skill 索引與清單（漸進揭露 L1：只含 name/description）。
 *
 * 對應 SKILL.md frontmatter 的最小可顯示欄位；列表頁只讀此層，
 * 不展開正文與 refs，避免一次把全部 Skill 內容塞入上下文。
 */
data class SkillIndex(
    val name: String,
    val description: String,
)

/**
 * Skill 清單（漸進揭露 L1+L2 解析結果）。
 *
 * @param allowedTools Skill 聲明的可用工具子集（收斂用；空集表示不收斂任何工具，
 *   由 [SkillScope] 決定語義）。
 * @param permissions 需向使用者展示的權限/副作用清單（原始字串；渲染見 [SkillPermissions]）。
 * @param version 選填版本字串，原樣展示，不參與校驗。
 */
data class SkillManifest(
    val name: String,
    val description: String,
    val allowedTools: Set<String> = emptySet(),
    val permissions: List<String> = emptyList(),
    val version: String? = null,
)

/**
 * SKILL.md 完整解析結果（漸進揭露 L2：frontmatter + 正文）。
 *
 * refs 內容不在此結構內；需經 [SkillRefs.readRef] 按需逐檔讀取（L3）。
 */
data class ParsedSkill(
    val manifest: SkillManifest,
    val index: SkillIndex,
    val body: String,
)
