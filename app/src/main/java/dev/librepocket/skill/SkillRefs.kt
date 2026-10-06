package dev.librepocket.skill

import java.io.File
import java.nio.charset.CharacterCodingException
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 已安裝 Skill 定義（只存資料，不執行程式碼）。
 *
 * @param id Skill 唯一鍵（等於 manifest.name，也等於安裝目錄名）。
 * @param promptBody SKILL.md 正文（提示片段；啟用後拼入規劃上下文）。
 * @param fileHashes 相對路徑 → SHA-256 hex（安裝時快照，供校驗與審計）。
 * @param hasScripts 是否含 `scripts/`（僅標記；安裝與讀取永不執行）。
 * @param enabled 使用者開關（寫入登記表；是否對本輪生效由 [SkillRegistry] 快照決定）。
 */
data class SkillDef(
    val id: String,
    val manifest: SkillManifest,
    val promptBody: String,
    val fileHashes: Map<String, String> = emptyMap(),
    val hasScripts: Boolean = false,
    val enabled: Boolean = true,
) {
    fun index(): SkillIndex = SkillIndex(manifest.name, manifest.description)
}

/**
 * Skill 附屬文本按需讀取（漸進揭露 L3）。
 *
 * - 只允許相對路徑；拒絕絕對路徑、`..`、反斜線、空路徑；
 * - 以 canonical root 約束在 Skill 目錄內；
 * - `scripts/` 下任何檔案拒絕讀取（腳本永不被當作上下文）；
 * - UTF-8 嚴格解碼 + 單檔大小上限，避免二進位/巨檔進入上下文。
 */
object SkillRefs {
    const val MAX_REF_BYTES = 64 * 1024L
    const val SCRIPTS_PREFIX = "scripts/"

    class RefException(message: String) : IllegalArgumentException(message)

    fun readRef(skillDir: File, relativePath: String, maxBytes: Long = MAX_REF_BYTES): String {
        val rel = relativePath.replace('\\', '/').trim()
        if (rel.isEmpty()) throw RefException("empty ref path")
        if (rel.startsWith("/")) throw RefException("absolute ref path")
        if (rel.split('/').any { it == ".." }) {
            throw RefException("traversal ref path")
        }
        if ('\\' in relativePath) throw RefException("backslash ref path")
        if (rel.startsWith(SCRIPTS_PREFIX)) throw RefException("scripts are never loaded as context")
        val root = skillDir.canonicalFile
        val target = File(root, rel).canonicalFile
        if (!target.path.startsWith(root.path + File.separator) && target.path != root.path) {
            throw RefException("ref escapes skill root")
        }
        if (!target.isFile) throw RefException("ref not found")
        if (target.length() > maxBytes) throw RefException("ref too large")
        val bytes = target.readBytes()
        if (bytes.size > maxBytes) throw RefException("ref too large")
        return strictUtf8(bytes)
    }

    /** 列出可按需讀取的文本 refs（僅 `refs/` 下的 `.md/.txt`，不含 scripts）。 */
    fun listTextRefs(skillDir: File): List<String> {
        val root = skillDir.canonicalFile
        val refsRoot = File(root, "refs")
        if (!refsRoot.isDirectory) return emptyList()
        return refsRoot.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".md") || it.name.endsWith(".txt")) }
            .map { root.toURI().relativize(it.toURI()).path.trimEnd('/') }
            .sorted()
            .toList()
    }

    private fun strictUtf8(bytes: ByteArray): String {
        val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        try {
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw RefException("ref is not valid UTF-8")
        }
    }
}
