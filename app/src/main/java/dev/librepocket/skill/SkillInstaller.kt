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
 * - 受限解包：拒絕路徑穿越、絕對路徑、反斜線、重複條目、**裸頂層條目**
 *   （無 `/` 的頂層名——不給形狀理由的包裝不再落入 staging 寫入的
 *   mkdirs/writeBytes 隨機失敗語意）、嵌套 Skill、非法 frontmatter、未知
 *   工具，以及條目數/單檔/歸檔/總解壓四項預算；
 *   條目數預算的計數單位乃誠實差異（見 [unzipConstrained]）：ZIP 側含
 *   directory 條目、files 側僅檔案表鍵——兩者皆為保守方向（寧多不漏）；
 *   ZIP 的 directory 條目亦過同一名形狀閘（剝恰一個尾隨 '/' 後呼叫
 *   [checkEntryName]；照舊不產檔案表鍵，見 [unzipConstrained]）；
 *   另有單條目名位元組上限 [MAX_ENTRY_NAME_BYTES]（形狀閘**最前哨**——
 *   超限名一律先收斂到長度預算訊息、不經任何回顧車道，防在寫入端
 *   以 ENAMETOOLONG 漂移成 IO_ERROR）；
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
 *   崩潰窗口還原不問齡期：target 槽缺席而單一的合法 `.bak` 存在時立即恢復
 *   （「合法」含形狀、id 規則、**target 槽的 NOFOLLOW 探針判定缺席**
 *   （[targetStateNoFollow]：植在槽位的 link／屬性不可判定一律不還原）與
 *   **還原內容校驗**通過：bak 內 SKILL.md 可解析且
 *   manifest.name==bakId，見 [bakManifestName]；反例——偽 bak 帶不相符 name 或
 *   損毀 SKILL.md 者不被扶正，KEPT 待人工。否則預設 24h 內崩潰的版本要等一天）；
 *   僅有 target 槽被佔（真實檔案／目錄或植入 link）時的孤兒 `.bak`
 *   與 staging 才受 [STALE_STAGING_MAX_AGE_MILLIS] 保護，避免誤刪進行中安裝。
 *   崩潰還原不占刪除預算（兩預算不互相擠兌），但每 run 另受
 *   [MAX_RESTORES_PER_RUN] 專屬上限（還原含跨檔 SKILL.md 讀取＋解析成本，
 *   兩上限皆計 attempt——失敗亦計入，見各常量 KDoc），超出者本 run 保留、
 *   下次啟動收斂。
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

    /**
     * 單條目名上限（UTF-8 位元組，含 `/` 分隔的全相對路徑）。
     *
     * 對齊目標檔案系統的慣例上限（Linux／Android 常見 FS 的 NAME_MAX=255 按
     * segment 計、`PATH_MAX`=4096 按全路徑位元組計；本處取保守的全名位元組
     * 上限 4096，與 [MAX_SINGLE_FILE_BYTES] 同層級的規格常量，不依賴具體 FS）。
     * 超限名在彙整校驗階段即拒（形狀拒絕車道，[Failure.TRAVERSAL]）——否則會
     * 落到 staging 寫入才以 `ENAMETOOLONG` 收斂成 [Failure.IO_ERROR]（語意漂移：
     * 明明是包裝形狀違規，錯誤碼卻像環境故障）。
     *
     * 分析殘餘（刻意不在本層收）：本常量僅攔「全名位元組 &gt; 4096」的車；
     * 單段超 `NAME_MAX`=255、或「destRoot 前綴＋staging 前綴＋rel 拼接」總長
     * 超路徑上限（全名本身 &lt;4096 一樣可能）的名字仍由寫入端 `ENAMETOOLONG`
     * 收斂成 [Failure.IO_ERROR]——segment 級覆蓋刻意依賴 NAME_MAX 慣例，不在
     * 歸一層重複，兩層分工於此註明。
     */
    const val MAX_ENTRY_NAME_BYTES = 4096

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
     * 每次清理呼叫最多「刪除類」工作嘗試（attempt）數——超出者保留、留待下次啟動收斂。
     *
     * 上限約束的是**有償工作嘗試**而非「成功」：遞迴刪除與 symlink unlink 各自的
     * attempt 都在車道入口、其首 I/O 之前計入（**I/O＝車道工作量**——tree walk／
     * unlink／rename／SKILL.md 讀取＋解析；單一定義見 [RunBudget] KDoc，
     * NOFOLLOW 分類探針屬枚舉／分類成本、不占額），**失敗亦計入**（walk 部分失敗回
     * false、unlink 刪不掉皆不退回）——否則失敗佔多的殘留會讓單次啟動做無界 I/O
     * （成功計數不改帳，失敗候選永不耗盡本 run 額度；P2-1 的症狀形）。超限後的
     * 候選一律 KEPT 且本 run 零車道 I/O（不做 tree walk／unlink／rename／
     * SKILL.md 讀取＋解析；`listFiles()` 枚舉、NOFOLLOW 分類探針與齡期閘的
     * `lastModified()` stat 為 O(N) 固定成本、不占預算，不隨超限取消），下次
     * 啟動繼續收斂；成功數另由
     * [cleanupStaleStaging] 的回傳（removed）單獨計，供啟動 log。
     * 崩潰還原（same-dir rename，O(1)）**不共用在刪除預算**（兩者不互相擠兌，
     * 見下），但另受同源的 [MAX_RESTORES_PER_RUN] 專屬上限約束。
     */
    const val MAX_CLEANUP_ENTRIES_PER_RUN = 64

    /**
     * 每次清理呼叫最多「還原型」工作嘗試（attempt）數——超出者本 run 保留、下次啟動收斂。
     *
     * 誠實筆記：每個還原**不止**一次 rename（O(1)）——還要先跨檔讀 bak 內
     * `SKILL.md`（受 [MAX_SINGLE_FILE_BYTES] 上限）、解析 frontmatter，加上
     * 呼叫方與 per-skill 鎖內各一次的 NOFOLLOW 目標槽探針。本上限約束的正是
     * 這些**有償工作嘗試**而非「成功」：attempt 在歧義守衛之後計入（其後最先的
     * NOFOLLOW 重探屬分類成本、非 I/O——I/O 單一定義見 [RunBudget] KDoc；
     * 本 attempt 對應的車道首 I/O 是跨檔 SKILL.md 讀取＋解析，消耗點在其
     * 之前），**失敗亦計入**——錯名／損毀（解析失敗）bak 的內容
     * 證明、改名失敗的 rename 皆不退回；success-counting 舊計法（成功才改帳）
     * 下失敗候選永不耗盡額度，單次啟動可對同批殘留反覆付全額 I/O（P2-1）。
     * 超限後的候選本 run KEPT 且零車道 I/O（不做 SKILL.md 讀取＋解析與 rename；
     * 枚舉、NOFOLLOW 分類探針與齡期閘的 `lastModified()` stat 為 O(N) 固定
     * 成本、不占預算，不隨超限取消），下次啟動繼續收斂。歧義候選（同 id 多份、
     * 全保留待人工）刻意**不消耗** attempt：歧義永不收斂，若占額會讓其後的合法
     * crash-bak 永遠等不到還原機會；其查詢本身維持 O(1)/free。新殘留 ≤ 上限的
     * 常態崩潰窗口仍受「process restart 即恢復」保護，僅極端堆積時把耗費攤到
     * 多次啟動。
     */
    const val MAX_RESTORES_PER_RUN = 16

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
     * [installFiles] 的 destRoot 探針與覆蓋前 target 檢查、[bakManifestName] 的
     * SKILL.md 讀取、[installedVersion] 拒讀）一律用此變體：
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

    /** NOFOLLOW 目標槽三態（fail-closed），見 [targetStateNoFollow]。 */
    private enum class TargetState {
        /** 槽位被佔（真實檔案／目錄或 symlink——NOFOLLOW 讀得屬性即算被佔）。 */
        PRESENT,

        /** 確定缺席（NoSuchFileException）——唯一可作還原候選的形狀。 */
        ABSENT,

        /** 屬性不可判定（AccessDenied／IO 等）：不可信。 */
        UNKNOWN,
    }

    /**
     * `destRoot/<id>` 形狀「target 槽」的 NOFOLLOW 三態探針（fail-closed，與
     * [isSymlinkOrUnknown] 同一先例變體）。
     *
     * 刻意不用跟隨 symlink 的 `File.exists()`：懸空 link 的 `exists()` 回 false，
     * 會把「target 槽為植入 link（指外部或懸空）」誤判成還原候選，隨後的還原
     * rename 將把槽位 link 吃掉（還原分支現僅認可 NOFOLLOW 探得 [TargetState.ABSENT]
     * 者；link 構不成「缺席」）。屬性不可判定（AccessDenied／IO）同樣不可信：
     * 既不還原也不刪除該 bak（KEPT，與「不確定即不破壞」哲學一致）。
     */
    private fun targetStateNoFollow(target: File): TargetState = try {
        java.nio.file.Files.readAttributes(
            target.toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS,
        )
        TargetState.PRESENT
    } catch (_: java.nio.file.NoSuchFileException) {
        TargetState.ABSENT
    } catch (_: Exception) {
        TargetState.UNKNOWN
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
     * bak 還原物的內容證明（fail-closed）：SKILL.md 存在、非 symlink、大小受
     * [MAX_SINGLE_FILE_BYTES] 約束、可解析時回 manifest.name，否則回 null。
     * 反例——偽造 `.bak`（name 不符）、缺失／損毀／超限 SKILL.md、symlink 型
     * 或其他非一般檔案（含目錄型）的 SKILL.md 皆回 null：呼叫方視為「無法證明
     * 屬性」，僅 KEPT，不還原、不刪除
     * （與歧義同哲學）。
     *
     * 尺寸讀取改為**有界窗口**（TOCTOU 縱深）：lstat 的 length() 只作預閘，
     * 真讀取至多 `MAX_SINGLE_FILE_BYTES + 1` 位元組（Files.newInputStream ＋
     * readNBytes）——lstat 通過後到全文讀取前檔案被撐大的殘餘窗口內，實際
     * 讀得超限即視為無法證明（回 null），不再依賴無界 readText 全量讀取；
     * 解析僅吃這份受讀位元組前綴。
     *
     * 開檔一律以 [java.nio.file.LinkOption.NOFOLLOW_LINKS] 發起：lstat 預閘與
     * open 之間的換靶窗 fail-closed——窗口內 md 被（再）換成 symlink 時 open
     * 直接失敗（NOFOLLOW 下對 link 開檔即 ELOOP），經既有 catch 收斂 null
     *（與上段尺寸成長窗並列的縱深）。
     */
    private fun bakManifestName(bakDir: File): String? = try {
        val md = File(bakDir, SKILL_MD)
        if (isSymlinkOrUnknown(md) || !md.isFile || md.length() > MAX_SINGLE_FILE_BYTES) {
            null
        } else {
            java.nio.file.Files.newInputStream(
                md.toPath(),
                java.nio.file.LinkOption.NOFOLLOW_LINKS,
            ).use { ins ->
                // 至多 MAX+1 位元組：恰好 MAX 位元組照常可解析（與預閘一致），
                // 讀滿 MAX+1 即「窗口內被撐大到超限」，無法證明。
                val prefix = ins.readNBytes((MAX_SINGLE_FILE_BYTES + 1).toInt())
                if (prefix.size > MAX_SINGLE_FILE_BYTES) {
                    null
                } else {
                    SkillParser.parse(String(prefix, Charsets.UTF_8)).manifest.name
                }
            }
        }
    } catch (_: Exception) {
        null
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

    /**
     * 測試接縫（僅測試用，全域可變，非執行緒隔離，同 [renameHook] 慣例：
     * 生產不得設定、測試須於 `@After` 重置為 null；`@VisibleForTesting internal`
     * 同 VoiceSpeaker hook 慣例，annotation 供 lint 靜態把關）。
     *
     * 刪除車道 attempt 注入縫：在刪除類 attempt 消耗點（車道入口、其首 I/O 前
     * ——I/O＝車道工作量，單一定義見 [RunBudget] KDoc；attempt 語意另見
     * [MAX_CLEANUP_ENTRIES_PER_RUN] 的 KDoc）以條目名調用——回 true
     * （或 null 預設）照常執行真實刪除（[unlinkEntry]／[deleteTreeSafe]）；回
     * false 表「attempt 已計入、真實 I/O 本 run 跳過（skip-fail）」：條目 KEPT
     * 原狀、下次啟動收斂。生產路徑恆為 null，零成本 no-op（null 短路，不建
     * lambda）。
     */
    @VisibleForTesting
    @Volatile
    internal var deleteHook: ((name: String) -> Boolean)? = null

    /**
     * 自家 staging／backup 檔名形狀（嚴格錨定；見 [cleanupStaleStaging]）。
     *
     * 產名形狀合約（規格註記）：`System.nanoTime` 按 Javadoc 規格**允許為負**
     * （無跨基準點保證，跨 JVM／跨 epoch 皆可能為負）；負值若直拼會產出
     * `.tmp-<負數>-<tid>`，兩個後綴數字非 `\d+` 而掉出本形狀——掃描點永不
     * 匹配，對應殘留永不收斂。故 [installFiles] 的 staging／backup 產名一律
     * 取 `Math.abs(nanoTime)`，後綴恆為 `\d+-\d+` 與本掃描點匹配（規格上唯一
     * 例外：`Math.abs(Long.MIN_VALUE)` 仍為 `Long.MIN_VALUE` 本尊、帶 `-`——
     * 規格層面的死角、實務不可達，記錄於此不宣稱絕對保證）；regex 側刻意
     * 不動，維持「產名點與掃描點同一形狀真源」。
     */
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
     * 單次清理 run 的 attempt 預算（刪除類／還原型同源獨立兩帳；語意見兩常量
     * KDoc——上限計「工作嘗試」而非「成功」，失敗亦計入）。
     *
     * **「I/O」在清理契約中的單一定義處**：本物件與兩常量／各清理 KDoc 所說的
     * I/O 一律指**車道工作量**——tree walk（[deleteTreeSafe]）、unlink
     *（[unlinkEntry]）、rename、還原讀證與跨檔 SKILL.md 讀取＋解析
     *（[bakManifestName]）等有償寫讀；`listFiles()`／`bakCountById` 枚舉、
     * NOFOLLOW 分類探針（[isSymlinkOrUnknown]／[targetStateNoFollow]，lstat 類）
     * 與齡期閘的 `lastModified()` stat 屬 O(N)／O(1) 的**分類成本**，不占額、
     * 不計做 I/O。其餘 KDoc 不重複完整定義、至多簡引本段或併列簡式。
     *
     * 消耗點在各工作車道入口、其首 I/O 之前（[bindDelete] 在刪除車道的首 I/O
     *（unlink／deleteTreeSafe）之前；[bindRestore] 在還原車道的 NOFOLLOW
     * 分類重探與其首 I/O（跨檔 SKILL.md 讀取＋解析）之前）；成功數由
     * 呼叫方另行計數（removed 回傳值），不反映在本物件。
     */
    private class RunBudget(private val maxDeletes: Int, private val maxRestores: Int) {
        var deleteAttempts = 0
            private set
        var restoreAttempts = 0
            private set

        /** 還原車道可用性檢查（僅查詢、不消耗；歧義守衛刻意在此之前維持 free）。 */
        val restoreAvailable: Boolean get() = restoreAttempts < maxRestores

        /**
         * 刪除車道入口（其首 I/O 前——I/O＝車道工作量，單一定義見 [RunBudget] KDoc）：
         * 有額則計入 attempt 並過 [SkillInstaller.deleteHook]
         * （false＝本 run 跳過真實 I/O、KEPT 待下次收斂）；無額回 false（不消耗、不調 hook）。
         */
        fun bindDelete(name: String): Boolean {
            if (deleteAttempts >= maxDeletes) return false
            deleteAttempts++
            return SkillInstaller.deleteHook?.invoke(name) ?: true
        }

        /** 還原車道入口（其分類重探與首 I/O——跨檔 SKILL.md 讀取＋解析——之前；I/O 單一定義見 [RunBudget] KDoc）：有額才計入 attempt（其後的 NOFOLLOW 重探屬分類成本、不占額，內容證明與 rename 屬本 attempt 對應的車道工作）。 */
        fun bindRestore(): Boolean {
            if (restoreAttempts >= maxRestores) return false
            restoreAttempts++
            return true
        }
    }

    /** 最近一次 [cleanupStaleStaging] 的 attempt 預算（測試觀察縫；全域可變、非執行緒隔離，同 [renameHook] 慣例）。 */
    @Volatile
    private var lastRunBudget: RunBudget? = null

    /**
     * 測試觀察縫（test-only，生產不得依賴）：回傳最近一次 [cleanupStaleStaging]
     * 的 per-run attempt 總數（first＝刪除類 attempt、second＝還原型 attempt；
     * 失敗亦計入，見兩常量 KDoc）。早期返回（非目錄／symlink destRoot／列舉失敗）
     * 的 run 回報零 attempt。併發呼叫時值為最近完成登記 run 的快照，非隔離。
     */
    @VisibleForTesting
    internal fun cleanupAttemptsForTest(): Pair<Int, Int> {
        val b = lastRunBudget ?: return Pair(0, 0)
        return Pair(b.deleteAttempts, b.restoreAttempts)
    }

    /**
     * 重啟 staging 清理：刪除 [destRoot] 下過期的安裝器內部條目
     * （嚴格形狀 `.$id.tmp-<nano>-<tid>`），以及 target 槽被佔（NOFOLLOW 探針，
     * 見 [targetStateNoFollow]；真實檔案／目錄**或植入 link** 皆算被佔）時的
     * 孤兒 `.bak`（嚴格形狀 `.$id.tmp…` 之外只認 `.$id.bak-<nano>-<tid>`）；
     * 若 target 槽由 NOFOLLOW 探針判定**缺席**、形狀合法、id 通過 [SkillParser]
     * name 規則、**還原內容校驗**通過的 `.bak`
     * 仍在（崩潰窗口遺留；bak 內 SKILL.md 可解析且 manifest.name==bakId，見
     * [bakManifestName]），則盡力把舊版恢復為 target。
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
     * 內容校驗未過者（name 不符／SKILL.md 缺失或損毀／超單檔上限）同樣
     * **KEPT** 永續待人工：bak 形狀是安裝器的溝通槽，不對「幾乎像 skill」的
     * 內容動刪除路徑；[listResiduals] 照常列出（形狀正確者）供人工核對。
     *
     * 齡期語意（兩閾分離）：
     * - **崩潰窗口還原**：target 槽被 NOFOLLOW 探針判定缺席的單一合法 `.bak`
     *   （含還原內容校驗通過）**不問齡期**立即恢復
     *  （否則預設 24h 內崩潰的版本要等一天，與「process restart 即恢復」矛盾）；
     * - **進行中安裝保護**：新鮮 staging 與槽位被佔的孤兒 `.bak`（含植入
     *   link 槽）只在超過 [maxAgeMillis] 後才刪，避免誤刪另一行程剛啟動的安裝。
     *
     * [destRoot] 為 symlink（或偵測失敗）時直接回 0；destRoot 下形狀內的
     * **子條目 symlink** 一律只解除 link 本身（NOFOLLOW；探針先於齡期閘——
     * lastModified() 會跟隨 symlink，mtime 不可信，link 類節點不參與齡期判斷，
     * 新鮮 link 亦立即只解除 link 本身，見 [handleCleanupEntry]），
     * 絕不遞迴刪除其指向的外部內容、也絕不還原成正式 skill；真實目錄改走
     * NOFOLLOW 的 [deleteTreeSafe]（內部遭植入 symlink 亦只解除 link 本身，
     * 不波及外部）。屬性不可判定（[isSymlinkOrUnknown] 的 unknown 分支）的
     * 真實條目同入 link 桶（不參與齡期判斷）：解得動（檔案/空目錄 unlink）
     * 即刪、解不動（非空目錄）即 KEPT——僅奇異 FS 觸發，安全不變量不受影響；
     * KEPT 者每次啟動重試，仍不收斂者需人工。
     *
     * 每次呼叫的「刪除類」工作嘗試（attempt）有 [MAX_CLEANUP_ENTRIES_PER_RUN]
     * 上限、還原型 attempt 有同源獨立的 [MAX_RESTORES_PER_RUN] 上限（兩預算
     * 不互相擠兌；上限計 attempt 而非成功——失敗亦在車道入口、其首 I/O 前計入
     *（I/O＝車道工作量，單一定義見 [RunBudget] KDoc；還原除 rename 外每候選
     * 還有跨檔 SKILL.md 讀取＋解析成本，不是純 O(1)），語意見該兩常量 KDoc）：
     * 超出者保留原狀、本 run 零車道 I/O（不做 tree walk／unlink／rename／
     * SKILL.md 讀取＋解析；`listFiles()` 枚舉、NOFOLLOW 分類探針與齡期閘的
     * `lastModified()` stat 為 O(N) 固定成本、不占預算，不隨超限取消）、
     * 留待下次啟動繼續收斂（避免單次啟動無界 I/O）。
     *
     * 呼叫方契約：不應與 [installZip]/[installFiles] 對同一 [destRoot] 併發調用；
     * 本函式內僅對同一 id 取 per-skill 鎖做 best-effort 互斥（防與安裝提交交錯），
     * 跨 id 操作與 destRoot 建立仍需外部序列化。另注：`bakCountById` 為列舉時的
     * point-in-time 快照（鎖外計算），鎖內還原分支僅再重驗 NOFOLLOW 槽狀態與
     * 條目本身非 link，極端交錯下的最終一致性依賴「不與 install 併發」契約。
     *
     * @return 已成功處置（還原或刪除）的條目數（combo 計數，同一數位混合兩類；
     *   歧義保留者、預算耗盡者（attempt 已另帳，見兩常量 KDoc）與「屬性或內容
     *   不可證明」者均不計入——刪除類（含 symlink unlink）的 attempt 受
     *   [MAX_CLEANUP_ENTRIES_PER_RUN]、還原型受 [MAX_RESTORES_PER_RUN] 約束，
     *   耗盡者此刻保留原狀待下次收斂；呼叫方（啟動 log）僅需對 >0 警示，
     *   不依賴兩類拆除分項；per-run attempt 總數另有測試縫
     *   [cleanupAttemptsForTest] 可觀察）。
     */
    fun cleanupStaleStaging(destRoot: File, maxAgeMillis: Long = STALE_STAGING_MAX_AGE_MILLIS): Int {
        // per-run attempt 預算（失敗亦計入，見兩常量 KDoc）。先建先登記：早期
        // 返回路徑（非目錄／symlink destRoot／列舉失敗）亦令測試縫
        // [cleanupAttemptsForTest] 回報本 run 的零 attempt，而非殘留更早 run 的總數。
        val budget = RunBudget(MAX_CLEANUP_ENTRIES_PER_RUN, MAX_RESTORES_PER_RUN)
        lastRunBudget = budget
        if (!destRoot.isDirectory) return 0
        // 與 installFiles 的防衛一致（installFiles 同變體，項目已對齊）：
        // destRoot 為 symlink 時不經它刪改外部目錄；
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
        // 自支收斂（無死鎖）註記：同 id「一份可還原 bak ＋ 一份待刪垃圾 bak」
        //（後者如非目錄形 bak——無還原形狀、target 缺席下照常入刪除車道）時，
        // 歧義只是延遲而非死結：本 run 的計數快照把兩者同帳，垃圾那份先由
        // 刪除車道在本 run（未過期者為下個過齡期的 run）附清，下輪 count
        // 回落為 1、可還原那份即獲還原——計數隨刪除自支收斂、無死鎖。
        val bakCountById = HashMap<String, Int>()
        for (f in entries) {
            val bakId = BACKUP_RE.matchEntire(f.name)?.groupValues?.get(1) ?: continue
            if (!SkillParser.isValidName(bakId)) continue
            bakCountById[bakId] = (bakCountById[bakId] ?: 0) + 1
        }
        var removed = 0
        // 每次啟動的刪除類／還原型工作預算（同源獨立帳，attempt 語意見兩常量
        // KDoc）：失敗亦在車道入口（其首 I/O 前）計入，避免單次啟動無界 I/O
        // （超出者本 run 保留、待下次收斂）；成功數另計於 removed。
        for (f in entries) {
            val n = f.name
            val stagingId = STAGING_RE.matchEntire(n)?.groupValues?.get(1)
            val bakId = if (stagingId != null) null else BACKUP_RE.matchEntire(n)?.groupValues?.get(1)
            if (stagingId == null && bakId == null) continue
            // 1) NOFOLLOW 節點分類先於齡期閘：lastModified() 會跟隨 symlink（mtime 不可信），
            //    link 類節點不參與齡期判斷，一律進入「只解除 link 本身」的刪除路徑
            //    （占刪除預算、不觸指向物內容；crashBak 對 link 恆為 false——link 不可還原）。
            val isLinkEntry = isSymlinkOrUnknown(f)
            // 崩潰窗口還原：target 槽被 NOFOLLOW 探針判定缺席的合法 .bak 不問齡期
            // 即處理（否則預設 24h 內崩潰的版本要等一天）；還原前另經
            // [bakManifestName] 內容校驗。
            // 其餘（staging／target 槽被佔——含植入 link／屬性不可判定——的孤兒
            // bak）受 maxAgeMillis 保護，避免誤刪進行中安裝。
            // 槽位探針刻意 NOFOLLOW（[targetStateNoFollow]）：跟隨 link 的
            // `File.exists()` 會把懸空 link 槽誤判成「缺席」，隨後的還原 rename
            // 會把 link 吃掉；懸空／指向外部的 link 槽經 lstat 讀得屬性即為
            // [TargetState.PRESENT]，不豁免齡期、按孤兒語意走刪除路徑。
            val bakIdValid = bakId != null && SkillParser.isValidName(bakId)
            val targetState = if (bakIdValid) {
                targetStateNoFollow(File(destRoot, bakId))
            } else {
                TargetState.PRESENT
            }
            val crashBak = !isLinkEntry && bakIdValid &&
                targetState == TargetState.ABSENT
            if (!crashBak && !isLinkEntry && now - f.lastModified() < maxAgeMillis) continue
            // 刪除類／還原型 attempt 預算由 [RunBudget] 在各工作車道入口（首
            // I/O 前）檢查並消耗（失敗亦計入；超出者本 run KEPT、下次啟動收斂）。
            // 有效 id 的條目在 per-skill 鎖下操作，防與同 id 安裝提交交錯。
            val opId = stagingId?.takeIf { SkillParser.isValidName(it) }
                ?: bakId?.takeIf { SkillParser.isValidName(it) }
            val disp = if (opId != null) {
                val lock = lockFor(opId)
                lock.lock()
                try {
                    handleCleanupEntry(
                        f,
                        stagingId,
                        bakId,
                        destRoot,
                        bakCountById,
                        budget,
                    )
                } finally {
                    lock.unlock()
                }
            } else {
                handleCleanupEntry(
                    f,
                    stagingId,
                    bakId,
                    destRoot,
                    bakCountById,
                    budget,
                )
            }
            when (disp) {
                Disposition.RESTORED -> removed++
                Disposition.DELETED -> removed++
                Disposition.KEPT -> Unit
            }
        }
        return removed
    }

    /** 清理單一條目的處置結果（供呼叫方計數並區分預算歸屬）。 */
    private enum class Disposition {
        /** 已還原為正式 skill（rename；占還原型 attempt 預算 [MAX_RESTORES_PER_RUN] 與回傳計數）。 */
        RESTORED,

        /** 已刪除完成（unlink 或 NOFOLLOW 安全遞迴刪除，占刪除 attempt 預算 [MAX_CLEANUP_ENTRIES_PER_RUN]）。 */
        DELETED,

        /** 保留原狀（歧義／刪除類或還原型 attempt 預算耗盡／屬性或內容不可證明／單項失敗重試）。 */
        KEPT,
    }

    /**
     * 單一清理條目的實際處置（呼叫方已按 id 取鎖或 id 非法無需取鎖）。
     *
     * 子條目 symlink 防衛（NOFOLLOW，先於一切處置）：呼叫方已先行探測一次
     * （NOFOLLOW 分類先於齡期閘，mtime 不可信），此處為縱深重驗。安裝器寫出的
     * 條目理應是一般檔案／目錄，形狀內條目為 symlink 即不可信——`File.isDirectory` 會
     * 跟隨 symlink、rename 會搬走 link 本身、遞迴刪除會遍歷其指向的外部目錄。
     * 故 symlink 條目一律只解除 link 本身，不遞迴刪除、也絕不還原成正式 skill
     * （唯一例外：真實目錄內部遭植入的 symlink 由 [deleteTreeSafe] 的
     * NOFOLLOW 遍歷只解除 link 本身）；unlink 亦屬刪除類，attempt 同受
     * [MAX_CLEANUP_ENTRIES_PER_RUN] 約束，超出者保留待下次收斂。
     *
     * attempt 預算語意（[RunBudget]）：刪除類（unlink／deleteTreeSafe）與還原型
     * 的 attempt 都在**各工作車道入口、其首 I/O 前**消耗（I/O＝車道工作量，單一
     * 定義見 [RunBudget] KDoc）——失敗（回 false／解析
     * 失敗／改名失敗）不退回，budget 耗盡後的候選直接 KEPT、本 run 零車道 I/O
     *（不做 tree walk／unlink／rename／SKILL.md 讀取＋解析）。枚舉（呼叫方的
     * `listFiles()`／`bakCountById`）與縱深分類探針——子條目
     * [isSymlinkOrUnknown] 防衛（本函式 1) 步）與 [targetStateNoFollow] 槽位
     * 探針（bak 分支 when）——**先於** attempt 消耗點執行，屬 O(N) 固定成本、
     * 不占預算、不隨超限取消（超限候選每 run 仍照付這些分類探針）。
     * 還原車道的歧義守衛刻意在消耗點**之前**且不消耗 attempt（O(1)/free）：
     * 歧義候選永不收斂（全保留待人工），若占額會讓其後的合法 crash-bak 永遠
     * 等不到還原機會。
     *
     * target 槽狀態以 [targetStateNoFollow] 三態定案（鎖內縱深重驗，防「呼叫方
     * 探針後槽位被換」的 TOCTOU）：[TargetState.PRESENT]（真實檔案／目錄或
     * 植入 link——link 構不成「缺席」，絕不 rename 還原，否則 rename 會把槽位
     * link 吃掉）按孤兒 bak 走下放刪除路徑；[TargetState.UNKNOWN]（屬性不可
     * 判定）不可信，KEPT（不還原也不刪——刪除可能毀掉唯一可恢復版本，與
     * 「不確定即不破壞」哲學一致）；僅 [TargetState.ABSENT] 為還原候選。
     *
     * `.bak` 歧義檢查只守還原分支：同 id 多份候選無法證明新舊（列舉順序未定義），
     * 逕行還原其一併刪除其餘會造成資料遺失，故還原路徑保留全數；target 槽被佔
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
        budget: RunBudget,
    ): Disposition {
        // 1) 子條目 NOFOLLOW 防衛：symlink（或偵測失敗）一律只 unlink 本身
        //    （unlink 占刪除 attempt 預算，失敗／超出者保留待下次收斂）。
        if (isSymlinkOrUnknown(f)) {
            if (!budget.bindDelete(f.name)) return Disposition.KEPT
            return if (unlinkEntry(f)) Disposition.DELETED else Disposition.KEPT
        }
        if (bakId != null && SkillParser.isValidName(bakId)) {
            val target = File(destRoot, bakId)
            when (targetStateNoFollow(target)) {
                TargetState.PRESENT -> Unit // 槽位被佔（含植入 link）→ 下方孤兒刪除路徑。
                TargetState.UNKNOWN ->
                    // 屬性不可判定：不可信——不還原（可能踩 link）也不刪（可能
                    // 毀掉唯一可恢復版本），KEPT 待下次啟動重試或人工。
                    return Disposition.KEPT
                TargetState.ABSENT -> if (f.isDirectory) {
                    // 崩潰窗口遺留：舊版已搬離、NOFOLLOW 探針判定 target 缺席
                    // → 盡力恢復。還原型可用性檢查（同舊 restoreAllowed 閘位；
                    // attempt 語意見 [MAX_RESTORES_PER_RUN]）：耗盡者本 run KEPT。
                    if (!budget.restoreAvailable) return Disposition.KEPT
                    // 歧義守衛只在此分支：同 id 多份候選全保留、不還原、不刪除、
                    // 不計數，需人工介入（含「一份新鮮 + 一份過期」——逕行恢復過期版
                    // 可能是錯版勝出）。查詢為 O(1)/free、不消耗 attempt（見上）。
                    if ((bakCountById[bakId] ?: 0) > 1) return Disposition.KEPT
                    //（f 已通過上方 NOFOLLOW 檢查；還原型共用同源 attempt 預算，不占刪除預算。）
                    // 消耗還原 attempt（於其分類重探與首 I/O——跨檔 SKILL.md 讀取
                    // ＋解析——之前，I/O 單一定義見 [RunBudget] KDoc）：其後的
                    // NOFOLLOW 重探屬分類成本，跨檔
                    // SKILL.md 內容證明、rename 皆屬有償工作，解析失敗／改名失敗
                    // 亦不退回——超限者本 run KEPT、下次啟動收斂。
                    if (!budget.bindRestore()) return Disposition.KEPT
                    // 殘餘 TOCTOU：自上方檢查到 rename 之間存在同 uid 跨程序置換
                    // 窗口；rename 為 name-based，無純 JDK 原語可完全關閉，還原前
                    // 重驗一次限縮窗口——link／不可判定一律保留供人工處理，剩餘
                    // 風險依「不與 install 併發」的外部序列化契約覆蓋。
                    if (isSymlinkOrUnknown(f)) return Disposition.KEPT
                    // 還原內容校驗（fail-closed）：bak 內 SKILL.md 必須可解析且
                    // manifest.name == bakId，否則無法證明此 bak 屬於該 skill
                    // （防**形狀-only** 的 bak 經名扶正、錯版內容勝出；name 與
                    // bakId 相符的完整偽造不在本守衛反例範圍——bak 無雜湊認證，
                    // 界限見類 KDoc）；讀不得／解析失敗／
                    // 超單檔上限一律視為無法證明，KEPT 待人工（attempt 已計入，
                    // 見上；與歧義同哲學：不確定即不破壞、不還原、不刪除；
                    // listResiduals 照常列出）。
                    if (bakManifestName(f) != bakId) return Disposition.KEPT
                    val restored = try {
                        rename(f, target)
                    } catch (_: Exception) {
                        false
                    }
                    return if (restored) Disposition.RESTORED else Disposition.KEPT
                    // 恢復失敗則保留供人工處理，不計數（attempt 仍已計入）。
                }
                // （槽位缺席但 bak 非目錄：無還原形狀 → 下方刪除路徑，既有語意。）
            }
            // target 槽被佔的孤兒 bak（或 bak 非目錄、無還原形狀）：純垃圾，
            // 沿用下方刪除路徑（占刪除 attempt 預算，啟動間收斂），不因同 id 歧義滯留。
        }
        // staging、target 槽被佔的孤兒 bak、或 id 非法者：只刪不恢復。
        // 刪除類 attempt 在 deleteTreeSafe 前（其首 I/O——tree walk——前，I/O
        // 單一定義見 [RunBudget] KDoc）計入；walk 部分失敗
        //（回 false）亦不退回——超出者保留待下次收斂。
        if (!budget.bindDelete(f.name)) return Disposition.KEPT
        return if (deleteTreeSafe(f)) Disposition.DELETED else Disposition.KEPT
    }

    /**
     * 讀取已安裝版的 `version`（降版揭露用，見類 KDoc）。
     *
     * best-effort：SKILL.md 缺席、超單檔上限或解析失敗時回 null，不拋錯、
     * 不影響安裝結果。
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
            // 與 [bakManifestName] 同一單檔上限（fail-closed 對稱）：超限
            // SKILL.md 不讀、不解析、視為不可讀——揭露值本就是 best-effort
            //（回 null，覆蓋照常進行，不因揭露失敗受阻），防對植入的
            // 超限 frontmatter 做無界 readText。
            if (md.length() > MAX_SINGLE_FILE_BYTES) return null
            // lstat 與 readText 之間存在 swap 增長窗（同 [bakManifestName] 的
            // 修正）：以 bounded read 兜底——超過單檔上限的字首一律視為
            // 不可讀，讀取量本身有界。開檔同 [bakManifestName] 以 NOFOLLOW
            // 發起（lstat 與 open 之間的換靶窗 fail-closed：窗口內被換成
            // symlink 即開檔失敗〔ELOOP 類〕，經下方 catch 收斂 null）。
            val bytes = java.nio.file.Files.newInputStream(
                md.toPath(),
                java.nio.file.LinkOption.NOFOLLOW_LINKS,
            ).use { s ->
                s.readNBytes(MAX_SINGLE_FILE_BYTES.toInt() + 1)
            }
            if (bytes.size > MAX_SINGLE_FILE_BYTES) return null
            SkillParser.parse(String(bytes, Charsets.UTF_8)).manifest.version
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
     * 對照：清理會不問齡期直接解除的 link 類條目（NOFOLLOW 前置分類，不參與
     * 齡期判斷）未過期時不會先經本函式列出——觀察面與清理的分類語意刻意
     * 不完全重疊（本函式維持「形狀 + 齡期」單一規則，不重複清理的分類邏輯）。
     *
     * 接線狀態（誠實註記，防誤導消費者）：本函式**尚未接線**——main 目前沒有
     * 生產呼叫者，唯一呼叫者是測試（SkillStartupRecoveryTest／
     * SkillAtomicCommitTest）；「供啟動路徑記錄殘餘」是接線 PR 的目標用法，
     * 接線落地前啟動路徑並不含本觀察。
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
     *   （仍把實測雜湊存入 SkillDef.fileHashes；目前無消費者，供未來展示/審計）。
     *   非 null 時允許部分覆蓋（刻意）：僅清單內列出者逐項比對（清單引用缺檔
     *   或不符即 HASH_MISMATCH），未列出者不驗——供增量物料不必攜全量 manifest；
     *   反例——需全量完整性時呼叫方須自行列齊全部路徑，本參數不做全集推導。
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
     *
     * 條目預算計數單位（誠實註記）：本側的 [MAX_ENTRIES] 僅計檔案表
     * **鍵**；ZIP 側（[unzipConstrained]）把 directory 條目也計入同一上限
     * （見其 KDoc）。兩種計法皆為保守方向（寧多不漏），刻意不改行為。
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
            // 裸頂層條目顯式拒絕（形狀，fail-closed 同車道）：包裝形狀要求
            // 「恰一個頂層 skill 目錄」：
            // 所有條目皆須位於根目錄之下（形如 `$root/<rel>`），故合法名必含
            // `/`。放行裸 key（等於 root 名的頂層名）會在 staging 寫入端落成
            // 根內頂層雜檔（→ 深層條目 mkdirs 撞同名檔，以 IO_ERROR 失敗蓋過
            // 形狀 reason，語意漂移；zip 與 files 兩路徑皆經此段共用校驗）。
            // reason 取既有 MULTIPLE_ROOTS（頂層形狀違規車道），訊息僅含條目
            // 名（norm 為相對形，不洩內部路徑）。
            if ('/' !in norm) {
                return Result.Failure(
                    Failure.MULTIPLE_ROOTS,
                    "bare top-level entry not allowed: $norm",
                )
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
        // 單一頂層根 + 恰一個 SKILL.md。（裸頂層條目已在彙整階段逐 key 拒絕，
        // 此處按解析後的根集合縱深重驗——與換行式寫出端的形狀閘互為冗餘。）
        // 誠實註記：第二子句為既存冗餘（死條件），到達此處的 key 恆含 '/'
        // （裸 key 已於上方逐 key 拒、norm 又永非空——空形在 checkEntryName
        // 即拒），`none { ... }` 不可能為真；保守起見不改判定（minimal diff），
        // 僅註記，防未來誤把兩子句讀成兩條獨立語意。
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
            // fail-closed destRoot 探針（與 cleanupStaleStaging/listResiduals/installedVersion
            // 同一先例變體）：Files.isSymbolicLink 讀不到屬性時回 false（fail-open DIDM）
            // 不可作唯一探針；偵測失敗一律視為不可信，拒裝（訊息與真 symlink 相同，
            // 刻意收斂，免洩漏更多檔案系統屬性；僅含檔名、不含絕對路徑）。
            // 僅在已存在時探測——[isSymlinkOrUnknown] 對不存在路徑回 true，
            // 先判 exists 防「首次安裝建目錄」被誤殺（反例：全新 destRoot 直接安裝）。
            // 誠實等級註記：「屬性讀取失敗」分支（exists() 為真而 lstat 失敗）在本
            // JVM 測試組無注入縫，保真屬 KDoc 承諾＋code 一致性，僅得真機/手動驗。
            // 父層 symlink／check-then-use TOCTOU 需呼叫方保證同 destRoot 勿併發
            // （見 [cleanupStaleStaging] 契約），此處不承諾完整防禦。
            if (destRoot.exists()) {
                if (isSymlinkOrUnknown(destRoot)) {
                    return Result.Failure(Failure.IO_ERROR, "destRoot must not be symlink: ${destRoot.name}")
                }
            }
            if (!destRoot.exists()) {
                val made = try {
                    destRoot.mkdirs()
                } catch (_: Exception) {
                    false
                }
                // mkdirs 失敗的雙因消歧（fail-closed，先於舊「cannot create」
                // 路由）：跟隨 link 的 isDirectory() 會把 planted symlink
                // destRoot（本探針後置入，逃過上方 exists() 門的 symlink 防衛）
                // 誤判成「race 中他人建成的真目錄」而照常寫入連結指向的外部
                // 目錄——故 !made 分支先做 NOFOLLOW 探針（[isSymlinkOrUnknown]，
                // 與上方 symlink 防衛同一先例變體），link／屬性不可判定一律
                // 拒裝（IOException 會被下方統一收斂成 IO_ERROR；訊息與上方
                // 真 symlink 車道收斂、僅含檔名）；真實目錄（race 中他人建成）
                // 維持既有 silent-pass，僅「仍非目錄」者走「cannot create」舊
                // 形狀。made=true 路徑刻意不動（正常建成無需重探；本僅在
                // mkdirs 回 false 的灰分支加探針）。
                if (!made) {
                    if (isSymlinkOrUnknown(destRoot)) {
                        throw java.io.IOException("destRoot must not be symlink: ${destRoot.name}")
                    }
                    if (!destRoot.isDirectory) {
                        throw java.io.IOException("cannot create destRoot ${destRoot.name}")
                    }
                }
            }
            val target = File(destRoot, root)
            // target 槽三態 NOFOLLOW 探針（與 [handleCleanupEntry] 的 target 槽
            // 判定同一先例變體，見 [targetStateNoFollow]）：跟隨 link 的
            // `File.exists()` 把懸空 link 槽誤判成「缺席」——新鮮安裝會直接
            // rename 把植入 link 吃掉（下方 isSymlink 防衛同樣被 exists()
            // 短路跳過）、!replace 新鮮安裝對正 link 亦是同一 rename 吃 link
            // 行為。改以三態定案：
            // [TargetState.ABSENT]（確定缺席）→ 新鮮安裝路徑照常 proceed；
            // [TargetState.PRESENT]（真實檔案／目錄或植入 link——NOFOLLOW 讀得
            // 屬性即算被佔、dangling 亦然，與 cleanup 的「dangling ⊄ absent」
            // 立場一致）→ 按序套既有契約：link（含 dangling）→ IO_ERROR 拒絕
            //（更具體車道先於 ALREADY_EXISTS；訊息與原真 symlink 釘案字句
            // 收斂，僅含檔名），真實檔案／目錄 + !replace → ALREADY_EXISTS
            //（既有契約保留）；[TargetState.UNKNOWN]（屬性不可判定）→
            // fail-closed 拒裝（訊息與真 symlink 收斂，同 destRoot 探針慣例：
            // 不洩更多檔案系統屬性、僅含檔名）。
            // 縱深註記：本探針不是唯一守門——staging 寫入（可全量、有耗時）
            // 之後的 commit 點再做一次同形三態重探（見下方 fsync 之後的
            // when），探針到 commit 之間的同 uid 置換窗由該重探 fail-closed
            // 收口（link／UNKNOWN 拒裝、真實被佔依 [replace] 契約走
            // ALREADY_EXISTS 或替換車道；訊息與本探針車道收斂）。
            when (targetStateNoFollow(target)) {
                TargetState.ABSENT -> Unit
                TargetState.UNKNOWN ->
                    return Result.Failure(Failure.IO_ERROR, "target must not be symlink: ${target.name}")
                TargetState.PRESENT -> {
                    // target symlink 防衛（fail-closed，先於 ALREADY_EXISTS 的
                    // 佔位契約）：link 型 target（正 link 或 dangling）無論
                    // replace 與否一律拒絕——不對 link 進行 rename→bak（波及
                    // 外部內容），亦不讓新鮮 rename 把 link 吃掉。
                    if (isSymlinkOrUnknown(target)) {
                        return Result.Failure(Failure.IO_ERROR, "target must not be symlink: ${target.name}")
                    }
                    // 非 link 的被佔槽位（真實檔案／目錄）→ 既有契約：
                    // !replace 拒裝。
                    if (!replace) {
                        return Result.Failure(Failure.ALREADY_EXISTS, "skill '$root' already installed")
                    }
                }
            }
            // tid 僅用於 staging 產名唯一性：main 用 getId()（API 1 起存在、與
            // threadId() 同值）以保 API 33+（threadId() 需 API 36+）；host JVM
            // 測試端製作殘留檔名時用 threadId()。
            val tid = Thread.currentThread().id
            // 產名一律 abs(nanoTime)（見 STAGING_RE 產名形狀合約）：nanoTime 規格
            // 允許為負，負值直拼會掉出 STAGING_RE 形狀、殘留永不收斂。
            val staging = File(destRoot, ".$root.tmp-${Math.abs(System.nanoTime())}-$tid")
            // positive exists() 安全註記：staging 名是本程序自產的
            // abs(nanoTime)-tid（掃描形狀 \d+-\d+ 合約）；若同 uid 預測者搶植
            // link，NOFOLLOW 的 deleteTreeSafe 只解除 link 本身、dangling 亦同，
            // rename(2) 永不跟隨其 target——外部內容不受影響（勿併發前提見上）。
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
                // commit 點 NOFOLLOW 三態重探（fail-closed，與 [handleCleanupEntry]
                // 還原路徑 rename 前的重驗同向；見上方 target 探針的縱深註記）：
                // 探針到本點之間存在同 uid 置換窗，舊寫法僅以跟隨式 exists() 重判
                // 新鮮／替換分支——懸空 link 的 exists() 會誤判「缺席」→ 新鮮
                // rename 把自植 link 吃掉；窗內置入的真實條目則被誤入替換車道
                //（!replace 契約下更會搬走並刪除他人條目）。改以三態定案
                //（不洩屬性；拒絕訊息與探針車道同一字句；分支分派後一切車道
                // 本體與訊息與舊寫法逐字相同）：
                when (targetStateNoFollow(target)) {
                    // 確定缺席 → 新鮮 commit（rollback 語意同舊形）。
                    TargetState.ABSENT -> {
                        if (!rename(staging, target)) {
                            deleteTreeSafe(staging)
                            return Result.Failure(
                                Failure.IO_ERROR,
                                "commit failed: rename staging ${staging.name} -> ${target.name}",
                            )
                        }
                        fsyncBestEffort(destRoot)
                    }
                    // 屬性不可判定：不可信（探針車道同款 fail-closed）——staging
                    // 先清不留半成品，訊息沿用「target must not be symlink」收斂
                    // 字句（同探針慣例：不洩更多檔案系統屬性）。
                    TargetState.UNKNOWN -> {
                        deleteTreeSafe(staging)
                        return Result.Failure(Failure.IO_ERROR, "target must not be symlink: ${target.name}")
                    }
                    // 被佔（lstat 讀得屬性即算被佔，與探針同立場）：先分離 link 型
                    // fail-closed，再按既有契約分派。
                    TargetState.PRESENT -> {
                        // 探針後被置入／置換進槽位的 link（正 link 或 dangling）→
                        // 拒絕：不對 link rename→bak、不讓新鮮 rename 吃掉 link；
                        // staging 清淨後回 IO_ERROR（訊息同探針車道字句）。
                        if (isSymlinkOrUnknown(target)) {
                            deleteTreeSafe(staging)
                            return Result.Failure(Failure.IO_ERROR, "target must not be symlink: ${target.name}")
                        }
                        // 非 link 的真實被佔槽位：需 replace 契約才動（即使探針時
                        // 判缺席、置換窗內才出現者——照探針同一契約以 ALREADY_EXISTS
                        // 拒絕：不搬、不自刪，被佔既有條目原樣保留）。
                        if (!replace) {
                            deleteTreeSafe(staging)
                            return Result.Failure(Failure.ALREADY_EXISTS, "skill '$root' already installed")
                        }
                        // 降版揭露：先快照舊版 version（best-effort，失敗即 null），成功時帶回。
                        replacedVersion = installedVersion(target)
                        // 產名同取 abs(nanoTime)（同一產名形狀合約，見 STAGING_RE 註記），
                        // backup 名與 BACKUP_RE 掃描點匹配（規格層 Long.MIN_VALUE 死角見同處，不重述）。
                        val backup = File(destRoot, ".$root.bak-${Math.abs(System.nanoTime())}-$tid")
                        // positive exists() 安全註記（與 staging 同理由）：fresh 產名
                        // 由本程序自產；planted 正 link 經 NOFOLLOW deleteTreeSafe 只是
                        // unlink 本身、dangling 會被不跟隨的 rename 覆蓋——外部內容保持
                        // 完整（勿併發前提見 installFiles KDoc）。
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

    /**
     * 受限解包（zip → 檔案表）。
     *
     * 條目預算（[MAX_ENTRIES]）的計數單位**含 directory 條目**（誠實註記）：
     * 每讀到一個 entry（含目錄型，不產檔案表鍵）皆計數、超限即拒——只讓計數
     * 更早觸頂（保守方向，寧多不漏）。directory 條目同樣過**同名形狀閘**
     * （[checkEntryName]；ZIP 目錄條目名按慣例尾隨 '/'，而空段規則不容尾隨
     * 空段，故先剝掉**恰一個**尾隨 '/' 再檢——"a//b/" 剝一刀後仍含中段空段
     * 照樣拒、"x//" 亦留 "x/" 被拒（與檔表側同一空段規則）；良性形如
     * "dir/"、"./dir/" 照常通過）。照舊**不產檔案表鍵**：否則 "root/" 歸一
     * 後的裸頂層名會誤觸 installFiles 的 MULTIPLE_ROOTS，封殺一切帶目錄條目
     * 的正常物料；directory 條目亦無內容流、仍不計位元組預算。對照：files 側
     * （[installFiles] 的 `files.size`）僅計檔案表鍵。兩種計法刻意不一致
     * （ZIP 的目錄條目是包裝自陳形狀，解包層採最保守計法；files 表無此概念），
     * 不改行為以免耦合兩側解包層細節。
     */
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
                } else {
                    // directory 條目與檔型條目同一名形狀閘（注入面不因條目型別
                    // 豁免；條目計數已在上方統一計入、建制不變）：ZIP 目錄條目
                    // 名按慣例尾隨 '/'，本閘的空段規則不容尾隨空段，故剝掉
                    // 恰一個尾隨 '/' 再檢（見本函式 KDoc；檢查目標為剝後名，
                    // 訊息回顧亦僅經 [checkEntryName] 的既有車道）。目錄條目
                    // 照舊不產檔案表鍵、不讀流（無內容）、不計位元組預算。
                    val failure = checkEntryName(name.removeSuffix("/"))
                    if (failure != null) {
                        val r = failure as Result.Failure
                        throw ConstrainedException(r.reason, r.message)
                    }
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
        // 名稱位元組上限放在形狀規則**最前哨**（回顯車道把關；形狀拒絕車道，
        // 同空段／空名，見 [MAX_ENTRY_NAME_BYTES]）：超限名本身可為 hostile
        // 供給（zip 條目名可任意長），若先跑形狀規則，ABSOLUTE_PATH／
        // BAD_SEPARATOR／"path traversal"／"bad path segment" 的訊息會把
        // 全名（可達 64KB+ 的任意供給）回顯進錯誤訊息。故先收斂到既有
        // 「entry name too long」形狀（同 TRAVERSAL 車道，訊息僅回長度預算、
        // 不回顯全文，錯誤碼不再以 ENAMETOOLONG 漂移成 IO_ERROR）——
        // ≤上限的名照常走其後的形狀規則、既有 short-name 釘案字句不變。
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_ENTRY_NAME_BYTES) {
            return Result.Failure(
                Failure.TRAVERSAL,
                "entry name too long (max $MAX_ENTRY_NAME_BYTES bytes)",
            )
        }
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
            // 檔案表不含空段：解包側的目錄條目已剝掉恰一個尾隨 '/' 才到達
            //（見 [unzipConstrained]），"a//b/" 剝一刀後仍含空段在此照樣拒；
            // files 側框架帶尾隨 '/' 的 raw 名仍由本段形狀拒。
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
