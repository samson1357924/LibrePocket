package dev.librepocket.skill

import dev.librepocket.tool.ToolRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
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
 * - 原子提交：staging 目錄寫入並 best-effort fsync 後，同目錄 rename 切換
 *   （目錄項原子、可掉電丟失：fsync 失敗靜默忽略（best-effort，不影響呼叫方），
 *   不保證完整落盤；刻意不設 strict-fsync 開關——fsync 只關耐久不關正確性，
 *   原子性由 rename 語義保證）；
 *   replace 時舊版先搬到 `.bak`，成功後刪 bak，失敗回滾 bak；rename 失敗
 *   直接失敗並保留 from→to 根因，不退回拷貝；
 * - 同 skill 安裝互斥（per-skill 鎖），防併發交錯（新鮮安裝與覆蓋同 id 皆序列化）；
 *   Current 為錯誤回傳路徑原子（失敗回滾舊版，staging 寫入階段故障經 [writeHook]
 *   注入同樣回滾：新鮮安裝無 target、覆蓋保留舊版）；kill -9 等崩潰窗口殘留由
 *   [cleanupStaleStaging] 收斂（窗口由測試鎖定：新鮮 staging、覆蓋中雙殘留、
 *   commit 後孤兒 bak、雙重失敗 bak；同 id 多份 bak 歧義時全保留待人工）。
 * - 啟動接線決策（#15：明確不接）：[cleanupStaleStaging] 尚未接入
 *   LibrePocketApp 啟動路徑——LibrePocketApp 仍為空殼、尚無 destRoot 目錄約定
 *   與登記表持久化，不硬造接線。殘餘記錄：孤兒僅限 destRoot 下嚴格形狀
 *   `.$id.tmp-…` / `.$id.bak-…`（真實 skill 目錄永不以 `.` 開頭，不會誤傷），
 *   可經 [listResiduals] 觀察、經下次 [cleanupStaleStaging] 收斂（回傳計數即
 *   收斂記錄）；接線時呼叫方須記錄回傳計數。未呼叫則孤兒留存，成功路徑的
 *   `.bak` 殘留同由下次清理收斂；
 * - 同名 Skill 預設保持不變（需 `replace=true` 才覆蓋）。
 * - 降版政策（#15：揭露不擋）：`version` 為選填自由字串、無可靠全序，
 *   阻擋「降版」等於猜測比較，故永不因版本阻擋安裝；`replace=true` 已是
 *   顯式覆蓋意圖，[Result.Success.replacedVersion] 攜帶被覆蓋舊版 version
 *  （新鮮安裝或舊版無 version 時為 null），呼叫方（安裝確認 UI）須向使用者
 *   並陳新舊版本。[installedVersion] 供讀取已安裝版。
 * - 已知限制（Target）：`expectedHashes=null` 跳過雜湊比對。
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
        /**
         * @param replacedVersion 被覆蓋舊版的 `version`（降版揭露用，見類 KDoc）：
         *   新鮮安裝或舊版無 version 時為 null；讀取舊版 SKILL.md 失敗時亦為 null
         *  （best-effort，不影響安裝結果）。
         */
        data class Success(val skill: SkillDef, val replacedVersion: String? = null) : Result
        data class Failure(val reason: SkillInstaller.Failure, val message: String) : Result
    }

    /** 過期 staging 的預設保留期限（啟動清理用）。 */
    const val STALE_STAGING_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000L

    /** 同 skill 互斥鎖（per-skill 序列化併發安裝；實務 skill 數量有界，條目永不驅逐，洩漏可接受）。 */
    private val skillLocks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * 取 per-skill 鎖。呼叫方必須只傳 [SkillParser] NAME_RE 合法 id：
     * [installFiles] 在 [SkillParser.parse] 校驗＋NAME_MISMATCH 比對之後才取鎖
     *（`root == manifest.name`，已受 NAME_RE 約束），[cleanupStaleStaging]
     * 僅對 [SkillParser.isValidName] 通過者取鎖；故 key 空間有界（長度/字符集受限），
     * 條目永不驅逐。
     */
    internal fun lockFor(skillId: String): ReentrantLock =
        skillLocks.computeIfAbsent(skillId) { ReentrantLock() }

    /** 測試接縫：為 null 時走真實 [File.renameTo]；測試可注入失敗以驗證回滾。 */
    @Volatile
    internal var renameHook: ((from: File, to: File) -> Boolean)? = null

    private fun rename(from: File, to: File): Boolean =
        renameHook?.invoke(from, to) ?: from.renameTo(to)

    /**
     * 測試接縫：staging 寫入階段故障注入。為 null 時直接寫盤；非 null 時每寫一檔
     * 前先調用，拋錯即模擬該階段失敗（磁碟滿/IO 錯），安裝以 IO_ERROR 失敗並回滾
     * （新鮮安裝無 target、覆蓋保留舊版，staging 已清）。
     */
    @Volatile
    internal var writeHook: ((staging: File, rel: String, bytes: ByteArray) -> Unit)? = null

    /** 自家 staging／backup 檔名形狀（嚴格錨定；見 [cleanupStaleStaging]）。 */
    private val STAGING_RE = Regex("^\\.(.+)\\.tmp-\\d+-\\d+$")
    private val BACKUP_RE = Regex("^\\.(.+)\\.bak-\\d+-\\d+$")

    /** best-effort fsync（檔與目錄統一走 `FileChannel.force(true)`；目錄 fsync 依平台而定，失敗吞掉，不影響安裝結果（掉電語義見類 KDoc））。 */
    private fun fsyncBestEffort(f: File) {
        try {
            java.nio.channels.FileChannel.open(f.toPath()).use { it.force(true) }
        } catch (_: Exception) {
            // best-effort：sync 不到就留下，呼叫方不受影響。
        }
    }

    /**
     * 重啟 staging 清理：刪除 [destRoot] 下過期的安裝器內部條目
     * （嚴格形狀 `.$id.tmp-<nano>-<tid>`），以及 target 仍在時的孤兒 `.bak`
     * （嚴格形狀 `.$id.tmp…` 之外只認 `.$id.bak-<nano>-<tid>`）；若 target 缺席而
     * 形狀合法、id 通過 [SkillParser] name 規則的 `.bak` 仍在（崩潰窗口遺留），
     * 則盡力把舊版恢復為 target。
     *
     * 形狀不符（`.nomedia`／`.random`／`.my.bakery`／`x.bak-y` 等）一律不碰；
     * 形狀符合但 id 非法者只刪不恢復。真實 skill 目錄永不以 `.` 開頭
     * （見 [SkillParser] 的 name 規則），不會被誤刪。
     *
     * 同 id 有多份 `.bak` 候選時視為歧義：一律保留、不還原、不刪除、
     * 不計數，需人工介入。檔名 nanoTime 後綴是單調計數器而非 wall-clock
     * 時間戳，不可跨重啟比較新舊，故不用後綴數字選「最新」。
     *
     * 呼叫方契約：不應與 [installZip]/[installFiles] 對同一 [destRoot] 併發調用；
     * 本函式內僅對同一 id 取 per-skill 鎖做 best-effort 互斥（防與安裝提交交錯），
     * 跨 id 操作與 destRoot 建立仍需外部序列化。
     *
     * @return 刪除或恢復的條目數（歧義保留者不計入）。
     */
    fun cleanupStaleStaging(destRoot: File, maxAgeMillis: Long = STALE_STAGING_MAX_AGE_MILLIS): Int {
        if (!destRoot.isDirectory) return 0
        val entries = try {
            destRoot.listFiles()
        } catch (_: Exception) {
            null
        } ?: return 0
        val now = System.currentTimeMillis()
        // 先按 skill id 分組同形 .bak 候選：同 id 多份時無法證明新舊，
        // 列舉順序未定義，逕行還原其一併刪除其餘會造成資料遺失，故全保留。
        // 計數含未過期者——「一份新鮮 + 一份過期」同樣無法判定，逕行恢復過期版
        // 可能是錯版勝出，故亦視為歧義。
        val bakCountById = HashMap<String, Int>()
        for (f in entries) {
            val bakId = BACKUP_RE.matchEntire(f.name)?.groupValues?.get(1) ?: continue
            if (!SkillParser.isValidName(bakId)) continue
            bakCountById[bakId] = (bakCountById[bakId] ?: 0) + 1
        }
        var removed = 0
        for (f in entries) {
            val n = f.name
            val stagingId = STAGING_RE.matchEntire(n)?.groupValues?.get(1)
            val bakId = if (stagingId != null) null else BACKUP_RE.matchEntire(n)?.groupValues?.get(1)
            if (stagingId == null && bakId == null) continue
            if (now - f.lastModified() < maxAgeMillis) continue
            // 有效 id 的條目在 per-skill 鎖下操作，防與同 id 安裝提交交錯。
            val opId = stagingId?.takeIf { SkillParser.isValidName(it) }
                ?: bakId?.takeIf { SkillParser.isValidName(it) }
            if (opId != null) {
                val lock = lockFor(opId)
                lock.lock()
                try {
                    if (handleCleanupEntry(f, stagingId, bakId, destRoot, bakCountById)) removed++
                } finally {
                    lock.unlock()
                }
                continue
            }
            if (handleCleanupEntry(f, stagingId, bakId, destRoot, bakCountById)) removed++
        }
        return removed
    }

    /**
     * 單一清理條目的實際處置（呼叫方已按 id 取鎖或 id 非法無需取鎖）。
     *
     * @return true 表示已刪除或已恢復（計入回傳計數）。
     */
    private fun handleCleanupEntry(
        f: File,
        stagingId: String?,
        bakId: String?,
        destRoot: File,
        bakCountById: Map<String, Int>,
    ): Boolean {
        if (bakId != null && SkillParser.isValidName(bakId)) {
            if ((bakCountById[bakId] ?: 0) > 1) {
                // 歧義：同 id 多份候選，全保留、不還原、不刪除、不計數，需人工介入。
                return false
            }
            val target = File(destRoot, bakId)
            if (!target.exists() && f.isDirectory) {
                // 崩潰窗口遺留：舊版已搬離、target 缺席 → 盡力恢復。
                val restored = try {
                    rename(f, target)
                } catch (_: Exception) {
                    false
                }
                if (restored) return true
                // 恢復失敗則保留供人工處理，不計數。
                return false
            }
        }
        // staging、target 仍在的孤兒 bak、或 id 非法者：只刪不恢復。
        return try {
            f.deleteRecursively()
        } catch (_: Exception) {
            // best-effort：刪不掉就留下，不影響呼叫方。
            false
        }
    }

    /**
     * 讀取已安裝版的 `version`（降版揭露用，見類 KDoc）。
     *
     * best-effort：SKILL.md 缺席或解析失敗時回 null，不拋錯、不影響安裝結果。
     */
    fun installedVersion(skillDir: File): String? {
        return try {
            val md = File(skillDir, SKILL_MD)
            if (!md.isFile) return null
            SkillParser.parse(md.readText(Charsets.UTF_8)).manifest.version
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 殘餘觀察（不刪除、不恢復）：列出 [destRoot] 下已過期的安裝器內部條目
     * （與 [cleanupStaleStaging] 同形狀同齡期規則），供啟動路徑接線前記錄殘餘。
     *
     * 與清理的差異：僅列形狀符合且過期者（含同 id 多份 bak 歧義候選——清理時
     * 全保留，此處一併列出供人工核對）；實際收斂仍以 [cleanupStaleStaging]
     * 回傳計數為準。回傳按檔名排序，保證穩定。
     */
    fun listResiduals(destRoot: File, maxAgeMillis: Long = STALE_STAGING_MAX_AGE_MILLIS): List<File> {
        if (!destRoot.isDirectory) return emptyList()
        val entries = try {
            destRoot.listFiles()
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val now = System.currentTimeMillis()
        return entries.filter { f ->
            val n = f.name
            val stagingId = STAGING_RE.matchEntire(n)?.groupValues?.get(1)
            val bakId = if (stagingId != null) null else BACKUP_RE.matchEntire(n)?.groupValues?.get(1)
            if (stagingId == null && bakId == null) return@filter false
            if (now - f.lastModified() < maxAgeMillis) return@filter false
            true
        }.sortedBy { it.name }
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
        // 原子提交：staging 先寫入並 best-effort fsync，再同目錄 rename 切換；
        // replace 時舊版先搬到 .bak，成功後刪 bak，失敗回滾 bak。rename 失敗
        // 直接失敗並保留 from→to 根因，不退回拷貝。覆蓋成功另經 [installedVersion]
        // 帶回 replacedVersion（降版揭露，best-effort）。
        val lock = lockFor(root)
        lock.lock()
        // 覆蓋成功時帶回的舊版 version（新鮮安裝保持 null；失敗路徑直接回傳，不用此值）。
        var replacedVersion: String? = null
        try {
            if (destRoot.exists() && !destRoot.isDirectory) {
                return Result.Failure(
                    Failure.IO_ERROR,
                    "cannot create destRoot: not a directory: ${destRoot.path}",
                )
            }
            if (!destRoot.exists()) {
                val made = try {
                    destRoot.mkdirs()
                } catch (_: Exception) {
                    false
                }
                if (!made && !destRoot.isDirectory) {
                    throw java.io.IOException("cannot create destRoot ${destRoot.path}")
                }
            }
            val target = File(destRoot, root)
            if (target.exists() && !replace) {
                return Result.Failure(Failure.ALREADY_EXISTS, "skill '$root' already installed")
            }
            val tid = Thread.currentThread().id
            val staging = File(destRoot, ".$root.tmp-${System.nanoTime()}-$tid")
            if (staging.exists()) staging.deleteRecursively()
            if (!staging.mkdirs() && !staging.isDirectory) {
                throw java.io.IOException("cannot create staging ${staging.name}")
            }
            try {
                for ((path, bytes) in normalized) {
                    val rel = path.removePrefix("$root/")
                    val out = File(staging, rel)
                    // 寫盤前再做一次 canonical 約束（縱深）。
                    val canonicalRoot = staging.canonicalFile
                    val canonicalOut = out.canonicalFile
                    if (canonicalOut.path != canonicalRoot.path &&
                        !canonicalOut.path.startsWith(canonicalRoot.path + File.separator)
                    ) {
                        throw IllegalStateException("escape: $rel")
                    }
                    val parent = out.parentFile
                    if (parent != null && !parent.exists()) {
                        if (!parent.mkdirs() && !parent.isDirectory) {
                            throw java.io.IOException("cannot create parent ${staging.name}/$rel")
                        }
                    }
                    // staging 寫入階段故障注入點（null 時直寫；拋錯即該階段失敗）。
                    writeHook?.invoke(staging, rel, bytes)
                    out.writeBytes(bytes)
                    fsyncBestEffort(out)
                }
                fsyncBestEffort(staging)
                if (!target.exists()) {
                    if (!rename(staging, target)) {
                        staging.deleteRecursively()
                        return Result.Failure(
                            Failure.IO_ERROR,
                            "commit failed: rename staging ${staging.name} -> ${target.name}",
                        )
                    }
                    fsyncBestEffort(destRoot)
                } else {
                    // 降版揭露：先快照舊版 version（best-effort，失敗即 null），成功時帶回。
                    replacedVersion = installedVersion(target)
                    val backup = File(destRoot, ".$root.bak-${System.nanoTime()}-$tid")
                    if (backup.exists()) backup.deleteRecursively()
                    if (!rename(target, backup)) {
                        staging.deleteRecursively()
                        return Result.Failure(
                            Failure.IO_ERROR,
                            "commit failed: backup old version ${target.name} -> ${backup.name}",
                        )
                    }
                    // 第二次 rename：回傳 false 與拋例外（renameTo 的 SecurityException、
                    // renameHook 拋錯）統一走同一回滾區塊，維持「失敗保留舊版」。
                    val commitError: String? = try {
                        if (rename(staging, target)) null else "rename returned false"
                    } catch (e: Exception) {
                        e.message ?: e.javaClass.simpleName
                    }
                    if (commitError != null) {
                        val rollbackError: String? = try {
                            if (rename(backup, target)) null else "rename returned false"
                        } catch (e: Exception) {
                            e.message ?: e.javaClass.simpleName
                        }
                        try {
                            staging.deleteRecursively()
                        } catch (_: Exception) {
                            // best-effort：清不掉不影響已組好的失敗訊息與回滾結果。
                        }
                        return if (rollbackError == null) {
                            fsyncBestEffort(destRoot)
                            Result.Failure(
                                Failure.IO_ERROR,
                                "commit failed: rename staging ${staging.name} -> ${target.name} " +
                                    "failed ($commitError), rolled back",
                            )
                        } else {
                            Result.Failure(
                                Failure.IO_ERROR,
                                "commit failed: rename staging ${staging.name} -> ${target.name} " +
                                    "failed ($commitError), rollback failed ($rollbackError), " +
                                    "backup kept at ${backup.name}",
                            )
                        }
                    }
                    // 成功路徑的 bak 刪除為 best-effort：回傳忽略，
                    // 殘留由下次 [cleanupStaleStaging] 收斂。
                    backup.deleteRecursively()
                    fsyncBestEffort(destRoot)
                }
            } catch (e: Exception) {
                staging.deleteRecursively()
                throw e
            }
        } catch (e: Exception) {
            return Result.Failure(Failure.IO_ERROR, "commit failed: ${e.message}")
        } finally {
            lock.unlock()
            // skill 數量有界，lock 條目永不驅逐：unlock 後再 hasQueuedThreads+remove
            // 有 TOCTOU 互斥弱化窗口，leak 可接受。
        }
        return Result.Success(skill, replacedVersion)
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
