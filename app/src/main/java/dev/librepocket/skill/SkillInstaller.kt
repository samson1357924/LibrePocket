package dev.librepocket.skill

import androidx.annotation.VisibleForTesting
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
 *   Commit 為錯誤回傳路徑原子（失敗回滾舊版，staging 寫入階段故障經 [writeHook]
 *   注入（test-only，全域非隔離）同樣回滾：新鮮安裝無 target、覆蓋保留舊版）；kill -9 等崩潰窗口殘留由
 *   [cleanupStaleStaging] 收斂（窗口由測試鎖定：新鮮 staging、覆蓋中雙殘留、
 *   commit 後孤兒 bak、雙重失敗 bak；同 id 多份 bak 歧義時還原路徑全保留
 *   待人工——target 仍在的孤兒 bak 照常入刪除預算收斂，見 [handleCleanupEntry]）。
 * - 啟動接線（#15：已接入）：[cleanupStaleStaging] 已於 [dev.librepocket.agent.LibrePocketApp]
 *   `onCreate` 以 [STALE_STAGING_MAX_AGE_MILLIS] 呼叫，destRoot 由
 *   [SkillPaths.skillRoot] 提供（`<filesDir>/skills`）。殘餘記錄：孤兒僅限
 *   destRoot 下嚴格形狀 `.$id.tmp-…` / `.$id.bak-…`（真實 skill 目錄永不以 `.`
 *   開頭，不會誤傷），可經 [listResiduals] 觀察、經 [cleanupStaleStaging]
 *   收斂（回傳計數即收斂記錄）；啟動路徑已記錄回傳計數。
 *   崩潰窗口還原不問齡期：target 缺席而單一的合法 `.bak` 存在時立即恢復
 *   （否則預設 24h 內崩潰的版本要等一天）；僅有 target 在時的孤兒 `.bak`
 *   與 staging 才受 [STALE_STAGING_MAX_AGE_MILLIS] 保護，避免誤刪進行中安裝。
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

    /**
     * 過期 staging／target 仍在時的孤兒 `.bak` 之預設保留期限（啟動清理用）。
     *
     * 只保護「進行中安裝」：新鮮 staging 未過期前不刪，避免誤刪另一行程剛啟動
     * 的安裝。崩潰窗口還原（target 缺席的單一合法 `.bak`）不受此期限限制，
     * 見 [cleanupStaleStaging]。
     */
    const val STALE_STAGING_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000L

    /**
     * 每次清理呼叫最多「刪除類」處置的條目數（超出者保留、留待下次啟動收斂）。
     *
     * 上限約束一切刪除類工作（遞迴刪除與 symlink unlink 皆同類——避免單次啟動
     * 在主執行緒做無界 I/O 拖慢 cold start／引發 ANR，超出者同批保留下次收斂）；
     * 崩潰還原（same-dir rename，O(1)）不受此上限約束，維持「process restart
     * 即恢復」的窗口語意。
     */
    const val MAX_CLEANUP_ENTRIES_PER_RUN = 64

    /** 同 skill 互斥鎖（per-skill 序列化併發安裝；distinct id 數量無界，條目永不驅逐，
     *  呼叫方須限流；leak 可接受但已文件化，見 [lockFor]）。 */
    private val skillLocks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * 取 per-skill 鎖。呼叫方必須只傳 [SkillParser] NAME_RE 合法 id：
     * [installFiles] 在 [SkillParser.parse] 校驗＋NAME_MISMATCH 比對之後才取鎖
     *（`root == manifest.name`，已受 NAME_RE 約束），[cleanupStaleStaging]
     * 僅對 [SkillParser.isValidName] 通過者取鎖；單 id 長度/字符集受限，
     * 但 distinct id 數量無界（NAME_RE 未界定個數），呼叫方須限流，
     * 條目永不驅逐。
     */
    internal fun lockFor(skillId: String): ReentrantLock =
        skillLocks.computeIfAbsent(skillId) { ReentrantLock() }

    /**
     * 測試接縫（僅測試用，全域可變，非執行緒隔離：同一 JVM 內一執行緒設 hook
     * 影響所有併發安裝；生產不得設定，測試須在 `@After` 重置為 null）。
     * 為 null 時走真實 [File.renameTo]；測試可注入失敗以驗證回滾。
     * `@VisibleForTesting internal` 慣例同 VoiceSpeaker preEmitHook/postCheckHook：
     * 生產呼叫點不得依賴（annotation 供 lint 靜態把關）。
     */
    @VisibleForTesting
    @Volatile
    internal var renameHook: ((from: File, to: File) -> Boolean)? = null

    private fun rename(from: File, to: File): Boolean =
        renameHook?.invoke(from, to) ?: from.renameTo(to)

    /**
     * 安全向 symlink 偵測（fail-closed）：偵測失敗時回 true（視為不可信）。
     *
     * 以 `Files.readAttributes(..., NOFOLLOW_LINKS)` 實作（與 `ShellPolicy` 的
     * `lstatNoFollow` 同一先例）：`Files.isSymbolicLink` 按 JDK 規格「無法判定時
     * 回 false」且幾乎不拋例，會把 unknown 當成一般檔案（fail-open），與本函式
     * 的 fail-closed 承諾相悖；本變體讀得成功時由 `BasicFileAttributes.isSymbolicLink`
     * 定案，讀不到（AccessDenied／IO／已消失等）一律回 true，不降級為「非 link」。
     *
     * 「會刪改外部內容」的路徑（[cleanupStaleStaging] 的 destRoot／條目檢查、
     * [installFiles] 覆蓋前 target 檢查、[installedVersion] 拒讀）一律用此變體：
     * 偵測不確定時寧可跳過／拒絕，也不跟隨 symlink 作用。觀察型 API
     * （[listResiduals]）同樣採用，與清理行為保持一致。
     */
    private fun isSymlinkOrUnknown(f: File): Boolean = try {
        java.nio.file.Files.readAttributes(
            f.toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS,
        ).isSymbolicLink
    } catch (_: Exception) {
        true
    }

    /**
     * 純 unlink（NOFOLLOW）：只解除 symlink 目錄項本身，決不刪除其指向的
     * 外部內容（dangling link 亦可刪）；刪不掉（權限／EIO）時保留原狀回 false。
     */
    private fun unlinkEntry(f: File): Boolean = try {
        java.nio.file.Files.delete(f.toPath())
        true
    } catch (_: Exception) {
        // best-effort：刪不掉就留下（下次啟動重試或人工處理），不影響呼叫方。
        false
    }

    /**
     * NOFOLLOW 的安全遞迴刪除（清理用途，取代 [File.deleteRecursively]）：
     * 以 `Files.walkFileTree`（預設不跟隨 symlink）遍歷——
     * - symlink 條目（含根本身）只被 unlink，其指向的外部內容不受波及；
     * - 一般檔案／目錄正常刪除；個別子項刪除失敗保持 best-effort、繼續其餘回收。
     *
     * @return 根條目是否已消失（回收完成）；部分失敗保留殘餘並回 false，
     *   由呼叫方下次啟動重試收斂。
     */
    private fun deleteTreeSafe(entry: File): Boolean = try {
        val root = entry.toPath()
        java.nio.file.Files.walkFileTree(
            root,
            object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(
                    file: java.nio.file.Path,
                    attrs: java.nio.file.attribute.BasicFileAttributes,
                ): java.nio.file.FileVisitResult {
                    // followLinks 關閉時 symlink 亦走此分支：只解除 link 本身。
                    try {
                        java.nio.file.Files.delete(file)
                    } catch (_: Exception) {
                        // best-effort：單檔刪不掉不阻斷其餘回收。
                    }
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: java.nio.file.Path,
                    exc: java.io.IOException,
                ): java.nio.file.FileVisitResult {
                    // 讀不到子條目（權限等）：best-effort，繼續其餘回收。
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: java.nio.file.Path,
                    exc: java.io.IOException?,
                ): java.nio.file.FileVisitResult {
                    try {
                        java.nio.file.Files.delete(dir)
                    } catch (_: Exception) {
                        // best-effort：清不掉保留，影響最終回傳值（下次重試）。
                    }
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            },
        )
        !java.nio.file.Files.exists(root)
    } catch (_: Exception) {
        false
    }

    /**
     * 測試接縫（僅測試用，全域可變，非執行緒隔離，同 [renameHook]）：
     * staging 寫入階段故障注入。為 null 時直接寫盤；非 null 時每寫一檔
     * 前先調用，拋錯即模擬該階段失敗（磁碟滿/IO 錯），安裝以 IO_ERROR 失敗並回滾
     * （新鮮安裝無 target、覆蓋保留舊版，staging 已清）。
     */
    @VisibleForTesting
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
     * 同 id 有多份 `.bak` 候選時，還原路徑（target 缺席者）視為歧義：一律保留、
     * 不還原、不刪除、不計數，需人工介入（target 仍在的孤兒 bak 不受歧義
     * 保護，照常入刪除預算收斂，見 [handleCleanupEntry]）。檔名 nanoTime 後綴
     * 是單調計數器而非 wall-clock 時間戳，不可跨重啟比較新舊，故不用後綴
     * 數字選「最新」。
     *
     * 齡期語意（兩閾分離）：
     * - **崩潰窗口還原**：target 缺席的單一合法 `.bak` **不問齡期**立即恢復
     *   （否則預設 24h 內崩潰的版本要等一天，與「process restart 即恢復」矛盾）；
     * - **進行中安裝保護**：新鮮 staging 與 target 仍在的孤兒 `.bak` 只在超過
     *   [maxAgeMillis] 後才刪，避免誤刪另一行程剛啟動的安裝。
     *
     * [destRoot] 為 symlink（或偵測失敗）時直接回 0；destRoot 下形狀內的
     * **子條目 symlink** 一律只解除 link 本身（NOFOLLOW，見 [handleCleanupEntry]），
     * 絕不遞迴刪除其指向的外部內容、也絕不還原成正式 skill；真實目錄改走
     * NOFOLLOW 的 [deleteTreeSafe]（內部遭植入 symlink 亦只解除 link 本身，
     * 不波及外部）。
     *
     * 每次呼叫的「刪除類」處置有 [MAX_CLEANUP_ENTRIES_PER_RUN] 上限：超出者
     * 保留原狀、留待下次啟動繼續收斂（避免單次啟動無界遞迴 I/O）；崩潰還原型
     * （rename，O(1)）不受上限約束。
     *
     * 呼叫方契約：不應與 [installZip]/[installFiles] 對同一 [destRoot] 併發調用；
     * 本函式內僅對同一 id 取 per-skill 鎖做 best-effort 互斥（防與安裝提交交錯），
     * 跨 id 操作與 destRoot 建立仍需外部序列化。另注：`bakCountById` 為列舉時的
     * point-in-time 快照（鎖外計算），鎖內僅還原前重驗 `target.exists()`，
     * 極端交錯下的最終一致性依賴「不與 install 併發」契約。
     *
     * @return 刪除或恢復的條目數（歧義保留者不計入；刪除類——含 symlink unlink——
     *   受每次啟動上限約束，超出者此刻不計；崩潰還原型豁免上限）。
     */
    fun cleanupStaleStaging(destRoot: File, maxAgeMillis: Long = STALE_STAGING_MAX_AGE_MILLIS): Int {
        if (!destRoot.isDirectory) return 0
        // 與 installFiles 的防衛一致：destRoot 為 symlink 時不經它刪改外部目錄；
        // 偵測失敗時一併回 0（fail-closed）。
        if (isSymlinkOrUnknown(destRoot)) return 0
        val entries = try {
            destRoot.listFiles()
        } catch (_: Exception) {
            null
        } ?: return 0
        val now = System.currentTimeMillis()
        // 先按 skill id 分組同形 .bak 候選：同 id 多份時無法證明新舊，
        // 列舉順序未定義，逕行還原其一併刪除其餘會造成資料遺失，故還原路徑
        //（target 缺席者）全保留。計數含未過期者——「一份新鮮 + 一份過期」
        // 同樣無法判定，逕行恢復過期版可能是錯版勝出，故亦視為歧義。
        // target 仍在的孤兒 bak 不受歧義保護：其形狀注定不可還原，照常入
        // 刪除預算逐次收斂（避免永久滯留，見 [handleCleanupEntry]）。
        val bakCountById = HashMap<String, Int>()
        for (f in entries) {
            val bakId = BACKUP_RE.matchEntire(f.name)?.groupValues?.get(1) ?: continue
            if (!SkillParser.isValidName(bakId)) continue
            bakCountById[bakId] = (bakCountById[bakId] ?: 0) + 1
        }
        var removed = 0
        // 每次啟動的刪除類工作預算：避免無界遞迴 I/O（超出者待下次收斂）。
        var deleted = 0
        for (f in entries) {
            val n = f.name
            val stagingId = STAGING_RE.matchEntire(n)?.groupValues?.get(1)
            val bakId = if (stagingId != null) null else BACKUP_RE.matchEntire(n)?.groupValues?.get(1)
            if (stagingId == null && bakId == null) continue
            // 崩潰窗口還原：target 缺席的合法 .bak 不問齡期即處理（否則預設
            // 24h 內崩潰的版本要等一天）。其餘（staging／target 仍在的孤兒 bak）
            // 受 maxAgeMillis 保護，避免誤刪進行中安裝。
            val crashBak = bakId != null && SkillParser.isValidName(bakId) &&
                !File(destRoot, bakId).exists()
            if (!crashBak && now - f.lastModified() < maxAgeMillis) continue
            // 刪除類處置受每次啟動上限約束；還原型（rename，O(1)）不設限。
            val deletionAllowed = deleted < MAX_CLEANUP_ENTRIES_PER_RUN
            // 有效 id 的條目在 per-skill 鎖下操作，防與同 id 安裝提交交錯。
            val opId = stagingId?.takeIf { SkillParser.isValidName(it) }
                ?: bakId?.takeIf { SkillParser.isValidName(it) }
            val disp = if (opId != null) {
                val lock = lockFor(opId)
                lock.lock()
                try {
                    handleCleanupEntry(f, stagingId, bakId, destRoot, bakCountById, deletionAllowed)
                } finally {
                    lock.unlock()
                }
            } else {
                handleCleanupEntry(f, stagingId, bakId, destRoot, bakCountById, deletionAllowed)
            }
            when (disp) {
                Disposition.RESTORED -> removed++
                Disposition.DELETED -> {
                    removed++
                    deleted++
                }
                Disposition.KEPT -> Unit
            }
        }
        return removed
    }

    /** 清理單一條目的處置結果（供呼叫方計數並區分預算歸屬）。 */
    private enum class Disposition {
        /** 已還原為正式 skill（rename，O(1)，占用回傳計數）。 */
        RESTORED,

        /** 已刪除完成（unlink 或 NOFOLLOW 安全遞迴刪除，占刪除預算）。 */
        DELETED,

        /** 保留原狀（歧義／超出預算（deletionAllowed=false）／單項失敗重試）。 */
        KEPT,
    }

    /**
     * 單一清理條目的實際處置（呼叫方已按 id 取鎖或 id 非法無需取鎖）。
     *
     * 子條目 symlink 防衛（NOFOLLOW，先於一切處置）：安裝器寫出的條目理應是
     * 一般檔案／目錄，形狀內條目為 symlink 即不可信——`File.isDirectory` 會
     * 跟隨 symlink、rename 會搬走 link 本身、遞迴刪除會遍歷其指向的外部目錄。
     * 故 symlink 條目一律只解除 link 本身，不遞迴刪除、也絕不還原成正式 skill
     * （唯一例外：真實目錄內部遭植入的 symlink 由 [deleteTreeSafe] 的
     * NOFOLLOW 遍歷只解除 link 本身）；unlink 亦屬刪除類，同受
     * [MAX_CLEANUP_ENTRIES_PER_RUN] 約束，超出預算者保留待下次收斂。
     *
     * `.bak` 歧義檢查只守還原分支：同 id 多份候選無法證明新舊（列舉順序未定義），
     * 逕行還原其一併刪除其餘會造成資料遺失，故還原路徑保留全數；target 仍在
     * 的孤兒 bak（或 bak 非目錄，無還原形狀者）為純垃圾，走刪除路徑逐次收斂，
     * 不再永久滯留。
     *
     * @return 處置結果（[Disposition.RESTORED]/[Disposition.DELETED] 計入呼叫方
     *   回傳計數；[Disposition.KEPT] 保留待人工或下次收斂）。
     */
    private fun handleCleanupEntry(
        f: File,
        stagingId: String?,
        bakId: String?,
        destRoot: File,
        bakCountById: Map<String, Int>,
        deletionAllowed: Boolean,
    ): Disposition {
        // 1) 子條目 NOFOLLOW 防衛：symlink（或偵測失敗）一律只 unlink 本身
        //    （unlink 占刪除預算，超出者保留待下次收斂）。
        if (isSymlinkOrUnknown(f)) {
            if (!deletionAllowed) return Disposition.KEPT
            return if (unlinkEntry(f)) Disposition.DELETED else Disposition.KEPT
        }
        if (bakId != null && SkillParser.isValidName(bakId)) {
            val target = File(destRoot, bakId)
            if (!target.exists() && f.isDirectory) {
                // 崩潰窗口遺留：舊版已搬離、target 缺席 → 盡力恢復。
                // 歧義守衛只在此分支：同 id 多份候選全保留、不還原、不刪除、
                // 不計數，需人工介入（含「一份新鮮 + 一份過期」——逕行恢復過期版
                // 可能是錯版勝出）。
                if ((bakCountById[bakId] ?: 0) > 1) return Disposition.KEPT
                //（f 已通過上方 NOFOLLOW 檢查；還原型不占刪除預算，O(1)。）
                // 殘餘 TOCTOU：自上方檢查到 rename 之間存在同 uid 跨程序置換
                // 窗口；rename 為 name-based，無純 JDK 原語可完全關閉，還原前
                // 重驗一次限縮窗口——link／不可判定一律保留供人工處理，剩餘
                // 風險依「不與 install 併發」的外部序列化契約覆蓋。
                if (isSymlinkOrUnknown(f)) return Disposition.KEPT
                val restored = try {
                    rename(f, target)
                } catch (_: Exception) {
                    false
                }
                return if (restored) Disposition.RESTORED else Disposition.KEPT
                // 恢復失敗則保留供人工處理，不計數。
            }
            // target 仍在的孤兒 bak（或 bak 非目錄、無還原形狀）：純垃圾，
            // 沿用下方刪除路徑（占刪除預算，啟動間收斂），不因同 id 歧義滯留。
        }
        // staging、target 仍在的孤兒 bak、或 id 非法者：只刪不恢復。
        // 每次啟動的刪除類工作設上限：超出者保留待下次收斂。
        if (!deletionAllowed) return Disposition.KEPT
        return if (deleteTreeSafe(f)) Disposition.DELETED else Disposition.KEPT
    }

    /**
     * 讀取已安裝版的 `version`（降版揭露用，見類 KDoc）。
     *
     * best-effort：SKILL.md 缺席或解析失敗時回 null，不拋錯、不影響安裝結果。
     */
    fun installedVersion(skillDir: File): String? {
        return try {
            // 目錄級防衛（與 destRoot 防衛同類）：拒讀 symlink 型 skill 目錄——
            // 其下 SKILL.md 可能經父層 link 逃逸讀取外部內容。
            if (isSymlinkOrUnknown(skillDir)) return null
            val md = File(skillDir, SKILL_MD)
            // 與 installFiles 的防衛一致：拒讀 symlink 型 SKILL.md（避免路徑逃逸讀取）；
            // 偵測失敗時視為不可信（fail-closed）。
            if (isSymlinkOrUnknown(md)) return null
            if (!md.isFile) return null
            SkillParser.parse(md.readText(Charsets.UTF_8)).manifest.version
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 殘餘觀察（不刪除、不恢復）：列出 [destRoot] 下已過期的安裝器內部條目
     * （與 [cleanupStaleStaging] 同形狀規則），供啟動路徑記錄殘餘。
     *
     * 與清理的差異：僅列形狀符合且過期者（含同 id 多份 bak 歧義候選——清理時
     * 還原路徑全保留，此處一併列出供人工核對）；實際收斂仍以
     * [cleanupStaleStaging] 回傳計數為準。回傳按檔名排序，保證穩定。
     *
     * 注意：本函式只依「形狀 + 齡期」過濾，**不區分 target 是否存在**——
     * 會被 [cleanupStaleStaging] 不問齡期立即還原的崩潰窗口候選（target 缺席的
     * 新鮮 `.bak`）若已過期，仍會在此列出供觀察；是否還原以清理結果為準。
     */
    fun listResiduals(destRoot: File, maxAgeMillis: Long = STALE_STAGING_MAX_AGE_MILLIS): List<File> {
        if (!destRoot.isDirectory) return emptyList()
        // 與 cleanupStaleStaging 的防衛一致：symlink destRoot 不經它列外部目錄；
        // 偵測失敗時一併回空（fail-closed）。
        if (isSymlinkOrUnknown(destRoot)) return emptyList()
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
        // 跨進程同 id 併發需外部序列化（staging/bak 後綴 nanoTime-tid 在跨進程可撞，撞即誤刪他方 staging）；
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
                    // 脫敏：僅含檔名，不含絕對路徑。
                    "cannot create destRoot: not a directory: ${destRoot.name}",
                )
            }
            try {
                // best-effort 僅擋直接 symlink destRoot；父層 symlink/ check-then-use TOCTOU
                // 需呼叫方保證同 destRoot 勿併發（見 cleanupStaleStaging 契約），此處不承諾完整防禦。
                if (java.nio.file.Files.isSymbolicLink(destRoot.toPath())) {
                    return Result.Failure(Failure.IO_ERROR, "destRoot must not be symlink: ${destRoot.name}")
                }
            } catch (_: Exception) {
                // best-effort：判斷失敗即視為不可信，拒裝。
                return Result.Failure(Failure.IO_ERROR, "cannot trust destRoot: ${destRoot.name}")
            }
            if (!destRoot.exists()) {
                val made = try {
                    destRoot.mkdirs()
                } catch (_: Exception) {
                    false
                }
                if (!made && !destRoot.isDirectory) {
                    throw java.io.IOException("cannot create destRoot ${destRoot.name}")
                }
            }
            val target = File(destRoot, root)
            if (target.exists() && !replace) {
                return Result.Failure(Failure.ALREADY_EXISTS, "skill '$root' already installed")
            }
            // 覆蓋前 target symlink 防衛（fail-closed）：不對 symlink target 進行
            // rename/刪除——planted target 會經由 bak 刪除波及外部內容。
            if (target.exists() && isSymlinkOrUnknown(target)) {
                return Result.Failure(Failure.IO_ERROR, "target must not be symlink: ${target.name}")
            }
            val tid = Thread.currentThread().id
            val staging = File(destRoot, ".$root.tmp-${System.nanoTime()}-$tid")
            if (staging.exists()) deleteTreeSafe(staging)
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
                        deleteTreeSafe(staging)
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
                    if (backup.exists()) deleteTreeSafe(backup)
                    if (!rename(target, backup)) {
                        deleteTreeSafe(staging)
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
                            deleteTreeSafe(staging)
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
                    // 殘留由下次 [cleanupStaleStaging] 收斂。NOFOLLOW 刪除
                    // （[deleteTreeSafe]）：live skill 內若遭植入 symlink，
                    // 只解除 link 本身、不波及外部內容。
                    deleteTreeSafe(backup)
                    fsyncBestEffort(destRoot)
                }
            } catch (e: Exception) {
                deleteTreeSafe(staging)
                throw e
            }
        } catch (e: Exception) {
            return Result.Failure(Failure.IO_ERROR, "commit failed: ${e.message}")
        } finally {
            lock.unlock()
            // distinct id 數量無界，lock 條目永不驅逐：unlock 後再 hasQueuedThreads+remove
            // 有 TOCTOU 互斥弱化窗口，leak 可接受但呼叫方須限流。
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

    /** 路徑歸一（`/` 分隔、去 `./`、去前導 `/`）；反斜線由 [checkEntryName] 先行拒絕。 */
    private fun normalize(raw: String): String {
        var s = raw.trim()
        while (s.startsWith("./")) s = s.substring(2)
        // 壓掉中間的 "./"（保留 ".." 供上層拒絕，而非靜默歸一）。
        s = s.replace("/./", "/")
        return s.trimStart('/')
    }
}
