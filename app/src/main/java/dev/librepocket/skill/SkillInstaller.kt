package dev.librepocket.skill

import dev.librepocket.tool.ToolRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Skill 安裝器（D04 最小版；Eta 安裝邊界思想的原創最小實現）。
 *
 * - ZIP 與 GitHub 物料共用同一受限解包/校驗流程（[installFiles] 為真相來源，
 *   [installZip] 先解包再委託）；
 * - 受限解包：拒絕路徑穿越、絕對路徑、反斜線、重複條目、嵌套 Skill、
 *   非法 frontmatter、未知工具，以及條目數/單檔/歸檔/總解壓四項預算；
 * - 每個 ZIP 只允許一個 Skill（單一頂層目錄 + 恰一個 `SKILL.md`）；
 * - 安裝只保存檔案、登記索引並預設啟用：永不執行 `scripts/` 下任何內容；
 * - 校驗失敗拒裝：先在記憶體完整驗證，再原子提交；失敗時目標目錄保持不變；
 * - 同名 Skill 預設保持不變（需 `replace=true` 才覆蓋）。
 */
object SkillInstaller {
    const val MAX_ENTRIES = 64
    const val MAX_SINGLE_FILE_BYTES = 256 * 1024L
    const val MAX_TOTAL_BYTES = 1 * 1024 * 1024L
    const val MAX_ARCHIVE_BYTES = 2 * 1024 * 1024L
    const val SKILL_MD = "SKILL.md"

    enum class Failure {
        EMPTY_PACKAGE,
        ARCHIVE_TOO_LARGE,
        TOO_MANY_ENTRIES,
        FILE_TOO_LARGE,
        TOTAL_TOO_LARGE,
        ABSOLUTE_PATH,
        TRAVERSAL,
        BAD_SEPARATOR,
        DUPLICATE_ENTRY,
        MULTIPLE_ROOTS,
        NESTED_SKILL,
        MISSING_SKILL_MD,
        BAD_FRONTMATTER,
        NAME_MISMATCH,
        UNKNOWN_TOOL,
        HASH_MISMATCH,
        ALREADY_EXISTS,
        IO_ERROR,
    }

    sealed interface Result {
        data class Success(val skill: SkillDef) : Result
        data class Failure(val reason: SkillInstaller.Failure, val message: String) : Result
    }

    /**
     * 由 ZIP 位元組安裝。
     *
     * @param expectedHashes 相對路徑 → 期望 SHA-256 hex；為 null 時跳過雜湊比對
     *   （仍記錄實測雜湊供展示/審計）。
     */
    fun installZip(
        zipBytes: ByteArray,
        destRoot: File,
        expectedHashes: Map<String, String>? = null,
        replace: Boolean = false,
    ): Result {
        if (zipBytes.size > MAX_ARCHIVE_BYTES) {
            return Result.Failure(Failure.ARCHIVE_TOO_LARGE, "archive exceeds budget")
        }
        val files: Map<String, ByteArray> = try {
            unzipConstrained(zipBytes)
        } catch (e: ConstrainedException) {
            return Result.Failure(e.failure, e.message ?: e.failure.name)
        } catch (e: Exception) {
            return Result.Failure(Failure.IO_ERROR, "unzip failed: ${e.message}")
        }
        return installFiles(files, destRoot, expectedHashes, replace)
    }

    /**
     * 由已具現化檔案表安裝（GitHub 下載具現後走同一校驗鏈）。
     * 鍵為 `/` 分隔相對路徑（如 `my-skill/SKILL.md`）。
     */
    fun installFiles(
        files: Map<String, ByteArray>,
        destRoot: File,
        expectedHashes: Map<String, String>? = null,
        replace: Boolean = false,
    ): Result {
        if (files.isEmpty()) return Result.Failure(Failure.EMPTY_PACKAGE, "empty package")
        if (files.size > MAX_ENTRIES) {
            return Result.Failure(Failure.TOO_MANY_ENTRIES, "too many entries")
        }
        // 路徑形狀校驗（與解包側同一規則，防 GitHub 物料繞過）。
        val normalized = LinkedHashMap<String, ByteArray>()
        for ((raw, bytes) in files) {
            val check = checkEntryName(raw)
            if (check != null) return check
            val norm = normalize(raw)
            if (normalized.containsKey(norm)) {
                return Result.Failure(Failure.DUPLICATE_ENTRY, "duplicate entry $norm")
            }
            if (bytes.size > MAX_SINGLE_FILE_BYTES) {
                return Result.Failure(Failure.FILE_TOO_LARGE, "file too large: $norm")
            }
            normalized[norm] = bytes
        }
        var total = 0L
        for (b in normalized.values) {
            total += b.size
            if (total > MAX_TOTAL_BYTES) {
                return Result.Failure(Failure.TOTAL_TOO_LARGE, "total unpacked exceeds budget")
            }
        }
        // 單一頂層根 + 恰一個 SKILL.md。
        val roots = normalized.keys.map { it.substringBefore('/') }.toSet()
        if (roots.size != 1 || normalized.keys.none { '/' in it }) {
            return Result.Failure(Failure.MULTIPLE_ROOTS, "package must hold exactly one skill directory")
        }
        val root = roots.single()
        if (root.isEmpty() || root == SKILL_MD) {
            return Result.Failure(Failure.MISSING_SKILL_MD, "missing skill root")
        }
        val skillMdKeys = normalized.keys.filter { it == "$root/$SKILL_MD" || it.endsWith("/$SKILL_MD") }
        if (skillMdKeys.isEmpty()) {
            return Result.Failure(Failure.MISSING_SKILL_MD, "missing $SKILL_MD")
        }
        if (skillMdKeys.size != 1) {
            return Result.Failure(Failure.NESTED_SKILL, "nested skills not allowed")
        }
        // 拒絕根外 SKILL.md（理論上已被 roots 約束，此為縱深）。
        for (k in normalized.keys) {
            if (k.endsWith("/$SKILL_MD") && k != "$root/$SKILL_MD") {
                return Result.Failure(Failure.NESTED_SKILL, "nested skill: $k")
            }
        }
        val skillMdBytes = normalized["$root/$SKILL_MD"]!!
        val parsed: ParsedSkill = try {
            SkillParser.parse(skillMdBytes.toString(Charsets.UTF_8))
        } catch (e: SkillParser.ParseException) {
            return Result.Failure(Failure.BAD_FRONTMATTER, e.message ?: "bad frontmatter")
        } catch (e: Exception) {
            return Result.Failure(Failure.BAD_FRONTMATTER, "unreadable $SKILL_MD")
        }
        if (parsed.manifest.name != root) {
            return Result.Failure(
                Failure.NAME_MISMATCH,
                "manifest name '${parsed.manifest.name}' != directory '$root'",
            )
        }
        for (t in parsed.manifest.allowedTools) {
            if (ToolRegistry.find(t) == null) {
                return Result.Failure(Failure.UNKNOWN_TOOL, "unknown tool '$t'")
            }
        }
        // 雜湊校驗（相對 skill 根的路徑 → hex）。
        val relHashes = LinkedHashMap<String, String>()
        for ((path, bytes) in normalized) {
            val rel = path.removePrefix("$root/")
            relHashes[rel] = SkillHashes.sha256Hex(bytes)
        }
        if (expectedHashes != null) {
            for ((rel, expect) in expectedHashes) {
                val actual = relHashes[rel]
                    ?: return Result.Failure(Failure.HASH_MISMATCH, "hash ref missing file: $rel")
                if (!actual.equals(expect, ignoreCase = true)) {
                    return Result.Failure(Failure.HASH_MISMATCH, "hash mismatch: $rel")
                }
            }
        }
        val hasScripts = relHashes.keys.any { it == "scripts" || it.startsWith("scripts/") }
        val skill = SkillDef(
            id = root,
            manifest = parsed.manifest,
            promptBody = parsed.body,
            fileHashes = relHashes,
            hasScripts = hasScripts,
            enabled = true,
        )
        // 原子提交：先寫臨時目錄再改名；失敗不留殘餘。
        try {
            if (!destRoot.exists()) destRoot.mkdirs()
            val target = File(destRoot, root)
            if (target.exists() && !replace) {
                return Result.Failure(Failure.ALREADY_EXISTS, "skill '$root' already installed")
            }
            val tmp = File(destRoot, ".$root.tmp-${System.nanoTime()}")
            if (tmp.exists()) tmp.deleteRecursively()
            tmp.mkdirs()
            try {
                for ((path, bytes) in normalized) {
                    val rel = path.removePrefix("$root/")
                    val out = File(tmp, rel)
                    // 寫盤前再做一次 canonical 約束（縱深）。
                    val canonicalRoot = tmp.canonicalFile
                    val canonicalOut = out.canonicalFile
                    if (canonicalOut.path != canonicalRoot.path &&
                        !canonicalOut.path.startsWith(canonicalRoot.path + File.separator)
                    ) {
                        throw IllegalStateException("escape: $rel")
                    }
                    out.parentFile?.mkdirs()
                    out.writeBytes(bytes)
                }
                if (target.exists() && replace) target.deleteRecursively()
                if (!tmp.renameTo(target)) {
                    // rename 跨卷失敗時退回拷貝。
                    target.mkdirs()
                    tmp.copyRecursively(target, overwrite = true)
                    tmp.deleteRecursively()
                }
            } catch (e: Exception) {
                tmp.deleteRecursively()
                throw e
            }
        } catch (e: Exception) {
            return Result.Failure(Failure.IO_ERROR, "commit failed: ${e.message}")
        }
        return Result.Success(skill)
    }

    private class ConstrainedException(val failure: Failure, message: String) : Exception(message)

    private fun unzipConstrained(zipBytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
            var entry: ZipEntry? = zin.nextEntry
            var count = 0
            while (entry != null) {
                val name = entry.name ?: ""
                count++
                if (count > MAX_ENTRIES) {
                    throw ConstrainedException(Failure.TOO_MANY_ENTRIES, "too many entries")
                }
                if (!entry.isDirectory) {
                    val failure = checkEntryName(name)
                    if (failure != null) {
                        val r = failure as Result.Failure
                        throw ConstrainedException(r.reason, r.message)
                    }
                    val norm = normalize(name)
                    if (out.containsKey(norm)) {
                        throw ConstrainedException(Failure.DUPLICATE_ENTRY, "duplicate entry $norm")
                    }
                    val buf = ByteArrayOutputStream()
                    val chunk = ByteArray(8_192)
                    var fileSize = 0L
                    while (true) {
                        val n = zin.read(chunk)
                        if (n < 0) break
                        fileSize += n
                        total += n
                        if (fileSize > MAX_SINGLE_FILE_BYTES) {
                            throw ConstrainedException(Failure.FILE_TOO_LARGE, "file too large: $norm")
                        }
                        if (total > MAX_TOTAL_BYTES) {
                            throw ConstrainedException(Failure.TOTAL_TOO_LARGE, "total unpacked exceeds budget")
                        }
                        buf.write(chunk, 0, n)
                    }
                    out[norm] = buf.toByteArray()
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
        if (out.isEmpty()) throw ConstrainedException(Failure.EMPTY_PACKAGE, "empty package")
        return out
    }

    private fun checkEntryName(raw: String): Result.Failure? {
        if (raw.isEmpty()) return Result.Failure(Failure.TRAVERSAL, "empty entry name")
        if (raw.startsWith("/") || raw.startsWith("\\")) {
            return Result.Failure(Failure.ABSOLUTE_PATH, "absolute path: $raw")
        }
        if (raw.length >= 2 && raw[1] == ':') {
            return Result.Failure(Failure.ABSOLUTE_PATH, "absolute path: $raw")
        }
        if ('\\' in raw) return Result.Failure(Failure.BAD_SEPARATOR, "backslash separator: $raw")
        val norm = normalize(raw)
        if (norm.isEmpty()) return Result.Failure(Failure.TRAVERSAL, "empty entry: $raw")
        if (norm.startsWith("/")) return Result.Failure(Failure.ABSOLUTE_PATH, "absolute path: $raw")
        val parts = norm.split('/')
        if (parts.any { it == ".." }) return Result.Failure(Failure.TRAVERSAL, "path traversal: $raw")
        if (parts.any { it.isEmpty() }) {
            // 允許尾隨 '/' 的目錄條目（解包側已跳過）；檔案表不應含空段。
            return Result.Failure(Failure.TRAVERSAL, "bad path segment: $raw")
        }
        return null
    }

    private fun normalize(raw: String): String {
        var s = raw.replace('\\', '/').trim()
        while (s.startsWith("./")) s = s.substring(2)
        // 壓掉中間的 "./"（保留 ".." 供上層拒絕，而非靜默歸一）。
        s = s.replace("/./", "/")
        return s.trimStart('/')
    }
}
