package dev.librepocket.skill

/**
 * SKILL.md 解析器（漸進揭露 L1/L2）。
 *
 * 格式（原創最小子集）：
 * ```text
 * ---
 * name: my-skill
 * description: 一句話簡介
 * allowed-tools: [navigate, alarm.create]
 * permissions: [calendar read]
 * version: 1.0
 * ---
 * 正文（提示片段），可為空。
 * ```
 * - frontmatter 必須以首行 `---` 開頭、由另一行 `---` 結束；
 * - `name` / `description` 必填且非空；name 僅允許小寫字母數字與 `._-`；
 * - `allowed-tools` 支援 `[a, b]` 或逗號分隔字串，缺省為空集；
 * - `permissions` 同上，缺省為空表；`version` 選填。
 */
object SkillParser {
    private val NAME_RE = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")
    private const val MAX_FRONTMATTER_CHARS = 8_192
    private const val MAX_BODY_CHARS = 64_000
    private const val MAX_DESCRIPTION_CHARS = 500

    class ParseException(message: String) : IllegalArgumentException(message)

    /** L1：只解析 frontmatter 的 name/description，不碰正文。 */
    fun parseIndex(markdown: String): SkillIndex {
        val parsed = parse(markdown)
        return parsed.index
    }

    /** L1+L2：解析 frontmatter 與正文；refs 仍需 [SkillRefs] 按需讀取。 */
    fun parse(markdown: String): ParsedSkill {
        val text = markdown.replace("\r\n", "\n")
        if (!text.startsWith("---\n") && text != "---") {
            throw ParseException("missing frontmatter start '---'")
        }
        val lines = text.split('\n')
        var end = -1
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") {
                end = i
                break
            }
        }
        if (end < 0) throw ParseException("missing frontmatter end '---'")
        val front = lines.subList(1, end).joinToString("\n")
        if (front.length > MAX_FRONTMATTER_CHARS) throw ParseException("frontmatter too large")
        val body = lines.subList(end + 1, lines.size).joinToString("\n").trim()
        if (body.length > MAX_BODY_CHARS) throw ParseException("body too large")

        val fields = parseFields(front)
        val name = fields["name"]?.trim().orEmpty()
        val description = fields["description"]?.trim().orEmpty()
        if (name.isEmpty()) throw ParseException("frontmatter: missing name")
        if (description.isEmpty()) throw ParseException("frontmatter: missing description")
        if (!NAME_RE.matches(name)) throw ParseException("frontmatter: illegal name '$name'")
        if (description.length > MAX_DESCRIPTION_CHARS) throw ParseException("frontmatter: description too long")

        val allowedTools = parseStringSet(fields["allowed-tools"] ?: fields["tools"] ?: "")
        for (t in allowedTools) {
            if (t.isEmpty() || t.length > 128 || !Regex("^[A-Za-z0-9._-]+$").matches(t)) {
                throw ParseException("frontmatter: illegal tool '$t'")
            }
        }
        val permissions = parseStringList(fields["permissions"] ?: "")
        val version = fields["version"]?.trim()?.takeIf { it.isNotEmpty() }
        if (version != null && version.length > 64) throw ParseException("frontmatter: version too long")

        // 拒絕未知鍵以外的拼寫錯誤？最小版僅拒絕非法 frontmatter 結構，
        // 未知鍵直接忽略，保持前向相容。
        val manifest = SkillManifest(
            name = name,
            description = description,
            allowedTools = allowedTools,
            permissions = permissions,
            version = version,
        )
        return ParsedSkill(manifest, SkillIndex(name, description), body)
    }

    private fun parseFields(front: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (raw in front.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val colon = line.indexOf(':')
            if (colon < 0) throw ParseException("frontmatter: bad line '$line'")
            val key = line.substring(0, colon).trim().lowercase()
            if (key.isEmpty()) throw ParseException("frontmatter: empty key")
            if (key in map) throw ParseException("frontmatter: duplicate key '$key'")
            var value = line.substring(colon + 1).trim()
            // 去掉首尾引號（"..." 或 '...'）。
            if (value.length >= 2 &&
                ((value.startsWith("\"") && value.endsWith("\"")) ||
                    (value.startsWith("'") && value.endsWith("'")))
            ) {
                value = value.substring(1, value.length - 1)
            }
            map[key] = value
        }
        return map
    }

    private fun parseStringSet(raw: String): Set<String> {
        val v = raw.trim()
        if (v.isEmpty()) return emptySet()
        return splitListValue(v).toSet()
    }

    private fun parseStringList(raw: String): List<String> {
        val v = raw.trim()
        if (v.isEmpty()) return emptyList()
        return splitListValue(v)
    }

    private fun splitListValue(raw: String): List<String> {
        var v = raw.trim()
        if (v.startsWith("[") && v.endsWith("]")) {
            v = v.substring(1, v.length - 1)
        }
        if (v.trim().isEmpty()) return emptyList()
        return v.split(',').map { it.trim().trim('"', '\'').trim() }.filter { it.isNotEmpty() }
    }
}
