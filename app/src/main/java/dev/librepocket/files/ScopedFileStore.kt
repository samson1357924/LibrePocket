package dev.librepocket.files

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * 預算耗盡的 typed 失敗（[IOException] 子類：既有 `catch (Exception)` /
 * `catch (IOException)` 通道照常接住，呼叫方再以 [budget] 細分映射；
 * 見 [FileEditExecutor.patch] 的 `FILE_TOO_LARGE`、
 * [FileSearchExecutor] 的跳過計數與列舉失敗）。
 */
class FileBudgetException(val budget: FileBudgetCode, message: String) : IOException(message)

/** 預算種類（[detail] 為跨層傳遞的穩定機器碼）。 */
enum class FileBudgetCode(val detail: String) {
    /** read 超過位元組上限（sentinel 探針確認，絕不截斷回傳）。 */
    READ_TOO_LARGE("FILE_TOO_LARGE"),

    /** list 條目超過上限。 */
    LIST_TOO_MANY("LIST_TOO_MANY"),

    /** list 下探超過深度上限。 */
    LIST_TOO_DEEP("LIST_TOO_DEEP"),

    /** list 超過時間預算。 */
    LIST_TIMEOUT("LIST_TIMEOUT"),
}

/**
 * 私有域檔案庫（BACKLOG D05）。
 *
 * - 作用域限定在 [root] 之下：任何穿越/絕對路徑在 [resolve] 即拋
 *   [IllegalArgumentException]，不觸碰檔案系統。
 * - Symlink 政策（選項 a：禁 symlink，NOFOLLOW 語義）：[root] 自身、
 *   構成 [root] 的每一詞法路徑段，以及目標路徑上每一路徑段若為 symlink
 *   即拒絕（[resolve] 拋 [IllegalArgumentException]）；點存取（read/write/exists/delete）
 *   語義一致。列目（[list]）不跟隨任何連結：遇 symlink 直接略過、
 *   不下鑽，並以已造訪正規目錄集合防循環。
 *   信任邊界：呼叫方須傳入可信正規根（例如 `filesDir.canonicalFile` 直建的
 *   app 私有域），不得傳入不可信別名；`alias/.`、`alias/sub`、`alias/..`
 *   類毒根一律拒絕（含原始 `.`/`..` 段直接 fail-closed，因 kernel 先跟隨再回退）。
 *   若傳入非正規系統別名路徑，將 fail-closed 拒絕，呼叫方應改傳正規路徑。
 * - 讀寫有界：[read] 先以 `length()` 快篩、再以 `cap+1` sentinel
 *   串流讀取（抄 [dev.librepocket.tool.WebFetch.readCapped]／provider
 *   `readBoundedProviderBody` 模式：exact-cap 視為合法 EOF，超限才拋
 *   [FileBudgetException]，絕不把超大檔灌進記憶體；回傳前重驗鏈上無連結）；
 *   [write] 的冪等比對先比長度、再以 8 KiB 固定緩衝串流比對（記憶體 O(1)，
 *   不先讀完整舊檔；父目錄建立改用安全版 [mkdirParentNoFollow]）。
 * - 列舉有窗：[list] 具條目／深度／deadline 三預算，超限拋
 *   [FileBudgetException]（絕不靜默截斷）；同時 NOFOLLOW：基址及 walk 途中
 *   遇 symlink 一律略過不下鑽（兄弟 diamond 連結只列真實側，見
 *   ScopedFileStoreBudgetTest 的 NOFOLLOW 斷言；#57 語義凌駕 legacy 輸出），
 *   以正規目錄集合防循環；[root] 自身為連結時回空表（fail-closed）。
 *   開流／讀不到的子目錄視為空（沿舊 `listFiles()==null` 語義），不中斷兄弟列舉。
 * - 寫入冪等：內容相同即回 [WriteOutcome.Unchanged] 且不改 mtime；
 *   不同才原子落盤（同目錄暫存 +搬移），回 Created/Updated。
 * - 寫前驗 parent chain：[write] 在建父目錄前後各驗一次鏈上無
 *   symlink，並以 NOFOLLOW 逐段建父目錄（每段前驗連結、單層 `mkdir`，
 *   不用會跟隨連結的 `mkdirs`），縮小檢查-使用窗口。
 * - 點存取使用前重驗：[exists]/[delete] 在實際 `isFile`/`delete` 前重驗
 *   一次整鏈（[resolve] 內已驗第一次）；[read] 在回傳前重驗一次。
 *   重驗只能縮小窗口，不能消除——第二次驗證與實際 IO 之間攻擊者仍可能
 *   以 `rename`/`symlink` 替換路徑段（見下）。
 * - B2 root 定錨：[delete]/[write] 在初驗後以 [snapshotTrustedRoot] 固定可信
 *   root（正規路徑字串＋檔案系統物件鍵 fileKey〈inode+device〉雙定錨，不再把
 *   canon 單獨稱為物件身分；鍵缺席即拒），containment 與最終門一律比對該定錨，
 *   不再以可能被換掉的 `root.canonicalPath` 重定錨；操作前以 [reverifyRootIdentity]
 *   再驗 root 本體非連結且正規身分與定錨一致＋物件鍵相等。單次 root 本體置換
 *   （真目錄→指外連結）轉為拒絕；實目錄替換（同字串不同 inode）亦以 fileKey 拒絕
 *   （刪後重建若重用同一 ino 則鍵相同，屬殘餘，見下 TOCTOU 段）；
 *   雙重替換（驗→換入→用→換回）仍僅有界不消除。
 * - TOCTOU 殘餘風險：單次檢查無法解決併發替換——檢查與實際 IO 之間
 *   攻擊者仍可能以 `rename`/`symlink` 替換路徑段。[read] 在回傳前重驗
 *   一次鏈上無連結並丟棄可疑結果，可將單次替換轉為拒絕，但雙重替換
 *   （檢查→換入連結→IO→換回真檔）仍可能穿出；[write] 的父目錄逐段建立、
 *   暫存建立與搬移之間亦有同類窗口（B2 在暫存建立前先驗整鏈再建暫存＋寫入，
 *   寫完搬移前再驗整鏈＋最終目標非連結＋暫存域內擋落鏈外；建暫存→寫入窗口
 *   仍可瞬時外寫（僅能事後刪暫存），殘餘 TOCTOU 僅有界）；[exists] 的第二次驗證與
 *   `isFile` 之間同樣存在窗口。[delete] 刪前重驗最終目標（NOFOLLOW lstat
 *   是 link 即拒不刪、正規化後不在域內／逸出即拒）：單次置換的誤刪替換後外部檔已修
 *   （轉為拒絕），重驗→刪除之間仍有有界併發窗口（攻擊者可無限重試，
 *   rename 原子，窗口非時間極小，僅有界不消除）。
 *   hardlink／mount 均不在門內（isSymbolicLink=false、canon 域內：同 FS 硬鏈讀可見
 *   外部 inode 內容，刪只解域內項；canonicalPath／toRealPath 均不解 mount／bind，
 *   bind 到域外仍顯示域內）；與 symlink 分開論述，列 out-of-scope，與
 *   ShellPolicy 同。
 *   本類只保證有界行為：併發替換可能造成本次呼叫被拒
 *   （[IllegalArgumentException]）、讀到舊內容、讀到替換後外部內容、
 *   把本次寫入落在鏈外，或短暫列出鏈外檔名
 *   （後兩者為殘餘小機率，寫穿出與列目洩露另見壓力測試說明），
 *   不保證檢查後鏈上零變動；呼叫方不得假設檢查後路徑不可變。
 *   [list] 條目僅回相對路徑（不含 root 絕對前綴，不洩全路徑）；DirectoryStream 單遍
 *   best-effort，不保證單遍快照隔離；併發增刪中途可見，不保證跨調一致；併發替換下短暫洩名窗口已揭露（見 [list]）。
 *   併發壓力測試僅證明行為有界（無未處理異常、無懸掛、store 事後仍可用、
 *   固定 `secret.txt` 未被竄改，`outsideExtra` 僅診斷），不宣稱消除競態；翻轉路徑上的併發寫入仍有殘餘小機率
 *   把本次內容落在鏈外、併發列目仍有殘餘小機率短暫列出鏈外檔名
 *   （兩者僅記錄、不計為通過條件），系統性穿出由單線程確定性測試覆蓋。
 *   信任邊界：[root] 須為 app 私有域可信基直建；非 app UID 的併發 symlink 交換
 *   為 out-of-scope，殘餘 TOCTOU 僅對 in-scope 競態有界，不做消除承諾。
 *   Java 無 O_NOFOLLOW/openat，不以正規化一次比較充作消除。
 * - 檔名：中文/空格/深層嵌套原樣支援（見 [FileScope.sanitizeRelative]）；
 *   單名 255 位元組上限等非法輸入直接拒絕。
 * - SAF 側：本類不管；跨域路徑先經 [FileScope.decide] 裁決，
 *   授權範圍內的 SAF IO 由呼叫方經系統文件介面完成。
 */
class ScopedFileStore(val root: File) {

    init {
        require(!root.path.contains('\u0000')) { "root contains NUL" }
    }

    /** 寫入結果：冪等語義由呼叫方直接斷言。 */
    enum class WriteOutcome {
        Created,
        Updated,
        Unchanged,
    }

    /** root 可信身分：正規路徑字串＋檔案系統物件鍵（inode+device）。 */
    internal data class TrustedRoot(val canon: String, val fileKey: Any?)

    /**
     * 將相對路徑解析為根下檔案；穿越/絕對/非法名拋 [IllegalArgumentException]。
     * 回傳前二次確認正規化絕對路徑仍在根內（防 `a/../..` 類邊界），再驗
     * 鏈上無 symlink（含 [root] 自身；NOFOLLOW 語義，見類註解 TOCTOU 說明）。
     */
    fun resolve(relativePath: String): File {
        val safe = FileScope.sanitizeRelative(relativePath)
            ?: throw IllegalArgumentException("unsafe path: $relativePath")
        val target = File(root, safe)
        val rootNorm = FileScope.normalize(root.absolutePath)
        val targetNorm = FileScope.normalize(target.absolutePath)
        if (targetNorm != rootNorm && !targetNorm.startsWith(rootNorm + "/")) {
            throw IllegalArgumentException("path escapes root: $relativePath")
        }
        ensureNoSymlinkChain(safe, relativePath)
        return target
    }

    /**
     * 驗證自 [root] 至目標的每一路徑段（含兩端）皆非 symlink。
     * 另驗構成 [root] 自身的每一詞法路徑段（防 `alias/.`、`alias/sub`
     * 類毒根：`Files.isSymbolicLink(alias/.)` 為 false，但父段 `alias`
     * 仍是連結，必須拒絕）。
     * 不跟隨連結（NOFOLLOW）；懸空連結同樣拒絕。僅做單次檢查，
     * 不防檢查-使用之間的併發替換（見類註解）。
     */
    private fun ensureNoSymlinkChain(safe: String, original: String) {
        try {
            ensureRootChainNoSymlink(original)
            var cur = root
            for (seg in safe.split("/")) {
                cur = File(cur, seg)
                if (Files.isSymbolicLink(cur.toPath())) {
                    throw IllegalArgumentException("symlink not allowed: $original")
                }
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            // fail-closed：無法判定是否為連結時一律拒絕。
            throw IllegalArgumentException("symlink check failed: $original", e)
        }
    }

    /**
     * 驗構成 [root] 的每一詞法路徑段皆非 symlink。
     * 先拒原始路徑中的 `.`/`..` 段（`alias/..` 經 kernel 先跟隨再回退，
     * 詞法折疊後會誤判為無連結，必須 fail-closed；正規可信根永不含此類段）。
     * 再以 [FileScope.normalize] 折疊尾端 `/.` 後逐段 lstat；
     * 不存在路徑回 false（缺席根仍可檢查已存在的毒父段）。
     * 呼叫方須傳正規可信根；非正規系統別名將 fail-closed，屬預期行為。
     */
    private fun ensureRootChainNoSymlink(original: String) {
        val rawParts = root.absolutePath.split("/").filter { it.isNotEmpty() }
        if (rawParts.any { it == "." || it == ".." }) {
            throw IllegalArgumentException("symlink not allowed: $original")
        }
        val rootNorm = FileScope.normalize(root.absolutePath)
        if (rootNorm.isEmpty()) {
            throw IllegalArgumentException("symlink check failed: $original")
        }
        try {
            val parts = rootNorm.split("/").filter { it.isNotEmpty() }
            var cur = File("/")
            if (Files.isSymbolicLink(cur.toPath())) {
                throw IllegalArgumentException("symlink not allowed: $original")
            }
            for (part in parts) {
                cur = File(cur, part)
                if (Files.isSymbolicLink(cur.toPath())) {
                    throw IllegalArgumentException("symlink not allowed: $original")
                }
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("symlink check failed: $original", e)
        }
    }

    /**
     * B2 root 定錨：初驗後固定可信 root（正規路徑字串＋檔案系統物件鍵 fileKey
     * 〈inode+device〉雙定錨；canon 僅為路徑名字串，不單獨充作物件身分）。
     *
     * 先以 [ensureRootChainNoSymlink] 驗 root 鏈（含 root 本體段；[resolve]／
     * [ensureNoSymlinkChain] 已在入口驗過，故此處不新增誤殺，只加定錨），再驗
     * root 本體非連結，最後回傳當下正規身分＋NOFOLLOW 本體鍵。呼叫方須在初驗後
     * 立刻 snapshot，containment 與終驗一律比對該定錨。鍵讀不到即 fail-closed
     * （Android ext4/f2fs 與測試 tmpfs/overlay 恆有鍵，null 僅 exotic FS 響亮失敗；null 分支無單測覆蓋）。
     */
    private fun snapshotTrustedRoot(original: String): TrustedRoot {
        ensureRootChainNoSymlink(original)
        try {
            if (Files.isSymbolicLink(root.toPath())) throw IllegalArgumentException("symlink not allowed: $original")
            val canon = root.canonicalPath
            val key = try {
                Files.readAttributes(root.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
                    ?: throw IllegalArgumentException("symlink check failed: $original")
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                throw IllegalArgumentException("symlink check failed: $original", e)
            }
            return TrustedRoot(canon, key)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("symlink check failed: $original", e)
        }
    }

    /**
     * 最終操作前再驗 root：本體非連結＋正規身分與定錨一致＋物件鍵相等；任一不符即拒。
     * 任一側鍵為 null 即拒（fail-closed；正常 FS 恆有鍵，見 [snapshotTrustedRoot]）。
     */
    private fun reverifyRootIdentity(trusted: TrustedRoot, original: String) {
        try {
            if (Files.isSymbolicLink(root.toPath())) throw IllegalArgumentException("symlink not allowed: $original")
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("symlink check failed: $original", e)
        }
        val now = try {
            root.canonicalPath
        } catch (e: Exception) {
            throw IllegalArgumentException("symlink check failed: $original", e)
        }
        if (now != trusted.canon) throw IllegalArgumentException("path escapes root: $original")
        val nowKey = try {
            Files.readAttributes(root.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
        } catch (e: Exception) { throw IllegalArgumentException("symlink check failed: $original", e) }
        val ok = trusted.fileKey != null && nowKey != null && trusted.fileKey == nowKey
        if (!ok) throw IllegalArgumentException("root identity mismatch: $original")
    }

    /**
     * 讀檔；不存在拋 [FileNotFoundException]，超過 [maxBytes] 拋
     * [FileBudgetException]（[FileBudgetCode.READ_TOO_LARGE]）。
     * `length()` 快篩只是建議值；權威判定是串流中的 `cap+1` sentinel
     * （檔在檢查後變大仍會被擋下），恰等於上限視為合法 EOF。
     * 回傳前重驗鏈上無連結（見類註解 TOCTOU 說明；雙重替換仍為殘餘風險）。
     *
     * 測試接縫：實際經由 [read] internal 多載委派（`open` 預設為
     * [defaultOpenRead]，行為不變）；測試可注入「小檔大流」工廠，使
     * `length()` 預檢通過但串流超限，確定性命中 sentinel（見
     * ScopedFileStoreBudgetTest.readSentinelRejectsGrowthAfterStat）。
     */
    fun read(relativePath: String, maxBytes: Int = MAX_READ_BYTES): ByteArray =
        read(relativePath, maxBytes, ::defaultOpenRead)

    /**
     * 測試接縫多載（生產經由 [read] 委派，預設行為不變）。
     *
     * - [open]：預設為 [defaultOpenRead]（`FileInputStream` 直開）；測試可注入
     *   任意 [InputStream] 工廠，模擬 stat 後成長（磁碟小檔、串流大內容），
     *   證明預檢通過後 sentinel 仍拒絕。預檢、`cap+1` 串流上限與回傳前重驗
     *   皆在此多載內，公開函數僅一行情委派，不分叉。
     */
    internal fun read(
        relativePath: String,
        maxBytes: Int,
        open: (File) -> InputStream,
    ): ByteArray {
        require(maxBytes >= 1) { "maxBytes must be >= 1" }
        val target = resolve(relativePath)
        if (!target.isFile) throw FileNotFoundException(relativePath)
        if (target.length() > maxBytes) {
            throw FileBudgetException(
                FileBudgetCode.READ_TOO_LARGE,
                "file too large: $relativePath (${target.length()} bytes, limit $maxBytes)",
            )
        }
        val cap = maxBytes.toLong() + 1
        val bytes = open(target).use { input ->
            val out = ByteArrayOutputStream(minOf(cap, READ_CHUNK_BYTES.toLong()).toInt())
            val buf = ByteArray(READ_CHUNK_BYTES)
            var total = 0L
            while (true) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), cap - total).toInt())
                if (n == -1) break
                total += n
                if (total > maxBytes) {
                    throw FileBudgetException(
                        FileBudgetCode.READ_TOO_LARGE,
                        "file too large: $relativePath (over $maxBytes bytes)",
                    )
                }
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
        val safe = FileScope.sanitizeRelative(relativePath)
            ?: throw IllegalArgumentException("unsafe path: $relativePath")
        ensureNoSymlinkChain(safe, relativePath)
        return bytes
    }

    /** 生產預設的讀檔開流（直開 [FileInputStream]）。 */
    private fun defaultOpenRead(file: File): InputStream = FileInputStream(file)

    fun exists(relativePath: String): Boolean {
        val target = resolve(relativePath)
        // 使用前重驗：resolve→isFile 窗口內若路徑段被換成連結，isFile 會跟隨；
        // 重驗把單次替換轉為拒絕，但重驗→isFile 之間仍有殘餘窗口（見類註解）。
        val safe = FileScope.sanitizeRelative(relativePath)
            ?: throw IllegalArgumentException("unsafe path: $relativePath")
        ensureNoSymlinkChain(safe, relativePath)
        return target.isFile
    }

    /**
     * 冪等寫入：內容一致直接回 [WriteOutcome.Unchanged]（不改 mtime）；
     * 否則經暫存檔原子搬移落盤。父目錄以 NOFOLLOW 逐段建立（見
     * [mkdirParentNoFollow]），建前後各驗一次整鏈。
     *
     * 一致性比對有界：先比 `length()`（不等即不同，不讀內容），再以固定
     * 緩衝串流逐塊比對、首異即停；舊檔再大也不會被完整讀進記憶體。
     * （檢查與比對之間檔被竄改多半導向「不同→重寫」的安全方向；若恰相等則回
     * Unchanged（1-bit 相等 oracle，不持久破壞，見類註解有界行為）。）
     *
     * 殘餘：建前二驗→逐段建→建後三驗與暫存建立/搬移之間仍有窗口，
     * 併發 `rename`/`symlink` 替換理論上仍可能把暫存或目標落在鏈外，
     * 見類註解 TOCTOU 說明。B2 在暫存建立前先驗整鏈再建暫存＋寫入，
     * 寫完搬移前再驗整鏈（NOFOLLOW）＋最終目標非連結＋暫存域內擋落鏈外；
     * 建暫存→寫入窗口仍可瞬時外寫（僅能事後刪暫存），殘餘 TOCTOU 僅有界。
     * root 以定錨比對（見類註解 B2 root 定錨）：初驗後 snapshot，containment
     * 與搬移前重驗一律比對定錨，不以可能被換掉的 root 重定錨。
     * fallback 只接 [AtomicMoveNotSupportedException]（不支援原子搬移的檔案系統
     * 才退回普通覆蓋，退回前重跑整鏈＋落盤門；其餘異常直接拋，fail-closed）。
     *
     * 測試接縫：實際經由 [writeInternal] 委派（`preGateHook`／`moveFn` 預設為
     * 生產行為，測試可注入 hook 在初驗與終門之間置換 root、或注入 moveFn 模擬
     * fallback；見 ScopedFileStoreB2Test）。
     */
    fun write(relativePath: String, bytes: ByteArray): WriteOutcome =
        writeInternal(relativePath, bytes)

    /**
     * 測試接縫多載（生產經由 [write] 委派，預設行為不變）。
     *
     * - [preGateHook]：搬移前重驗之前調用（測試用來在初驗與終門之間 swap root）。
     * - [moveFn]：搬移執行（預設 `Files.move`；測試可注入首調拋
     *   [AtomicMoveNotSupportedException] 驗 fallback 重跑 gates）。
     * 注意：此接縫簽名為重驗門證據測試所依賴（見 ScopedFileStoreConcurrencyPressureTest），
     * 重構時保留參數與調用時機，僅註解變更。
     */
    internal fun writeInternal(
        relativePath: String,
        bytes: ByteArray,
        preGateHook: (() -> Unit)? = null,
        moveFn: (Path, Path, Array<out CopyOption>) -> Path = { s, d, o -> Files.move(s, d, *o) },
    ): WriteOutcome {
        val target = resolve(relativePath)
        val safe = FileScope.sanitizeRelative(relativePath)
            ?: throw IllegalArgumentException("unsafe path: $relativePath")
        mkdirParentNoFollow(safe, relativePath)
        // 建後三驗：mkdir 可能新建目錄，併發替換窗口下再次確認。
        ensureNoSymlinkChain(safe, relativePath)
        val trustedRoot = snapshotTrustedRoot(relativePath)
        // 冪等早返前先驗 root 身分：否則換根後內容碰巧相等即回成功
        //（僅讀比對 oracle，不落盤；終門重驗見下，僅有界）。
        reverifyRootIdentity(trustedRoot, relativePath)
        if (target.isFile && target.length() == bytes.size.toLong() && contentEquals(target, bytes)) {
            return WriteOutcome.Unchanged
        }
        // B2 寫入前先驗暫存目錄鏈，再建暫存＋寫入：比對→暫存建立窗口內父鏈可能被換成連結。
        // 建暫存→寫入窗口仍可瞬時外寫（僅能事後刪暫存），見類註解。
        ensureNoSymlinkChain(safe, relativePath)
        val created = !target.exists()
        val tmp = File.createTempFile(TMP_NAME_PREFIX, ".tmp", target.parentFile ?: root)
        try {
            tmp.writeBytes(bytes)
            // 測試 seam：搬移前重驗之前調用（初驗與終門之間置換 root 用）。
            preGateHook?.invoke()
            // B2 寫完搬移前再驗：暫存建立→搬移窗口內父鏈可能被換成連結；
            // 沿用 NOFOLLOW 語義再驗一次整鏈，並驗最終目標非連結、暫存仍在域內擋落鏈外；
            // 驗失敗拋出後由 finally 刪暫存，殘餘 TOCTOU 僅有界。
            // root 身分另以定錨再驗（防 root 本體置換）。
            ensureNoSymlinkChain(safe, relativePath)
            reverifyRootIdentity(trustedRoot, relativePath)
            ensurePreMoveNoSymlinkAndContained(target, tmp, relativePath, trustedRoot.canon)
            try {
                moveFn(
                    tmp.toPath(),
                    target.toPath(),
                    arrayOf(
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    ),
                )
            } catch (_: AtomicMoveNotSupportedException) {
                // 僅檔案系統不支援原子搬移時退回普通覆蓋（仍先寫暫存，保證不截斷原檔）。
                // 退回前重跑整鏈＋落盤門（用定錨 root）；其餘異常直接拋（fail-closed）。
                ensureNoSymlinkChain(safe, relativePath)
                reverifyRootIdentity(trustedRoot, relativePath)
                ensurePreMoveNoSymlinkAndContained(target, tmp, relativePath, trustedRoot.canon)
                moveFn(tmp.toPath(), target.toPath(), arrayOf(StandardCopyOption.REPLACE_EXISTING))
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return if (created) WriteOutcome.Created else WriteOutcome.Updated
    }

    /**
     * NOFOLLOW 逐段建立父目錄：每段前驗連結、僅用單層 `mkdir`，
     * 不用會跟隨連結的 `mkdirs`。入口先做建前二驗（[resolve] 內已做一驗），
     * 每段建後再驗該段，呼叫方（[write]）在建完後做建後三驗。
     * 缺席 [root] 以單層 `mkdir` 安全建立（父層為 OS 已建的可信基，不用 `mkdirs`），
     * 建後驗為目錄且非連結，否則 fail-closed。
     * 懸空連結視為連結一律拒絕；檢查失敗 fail-closed。
     * 殘餘：段前驗→`mkdir`→建後驗之間仍有併發替換窗口，見類註解。
     */
    private fun mkdirParentNoFollow(safe: String, original: String) {
        val segs = safe.split("/")
        if (segs.size <= 1) {
            ensureNoSymlinkChain(safe, original)
            ensureRootDirNoFollow(original)
            return
        }
        ensureNoSymlinkChain(safe, original)
        ensureRootDirNoFollow(original)
        try {
            ensureRootChainNoSymlink(original)
            var cur = root
            for (i in 0 until segs.size - 1) {
                if (Files.isSymbolicLink(cur.toPath())) {
                    throw IllegalArgumentException("symlink not allowed: $original")
                }
                val next = File(cur, segs[i])
                if (Files.isSymbolicLink(next.toPath())) {
                    throw IllegalArgumentException("symlink not allowed: $original")
                }
                if (!next.exists()) {
                    next.mkdir()
                    if (Files.isSymbolicLink(next.toPath())) {
                        throw IllegalArgumentException("symlink not allowed: $original")
                    }
                }
                if (!next.isDirectory) {
                    throw IllegalArgumentException("symlink check failed: $original")
                }
                cur = next
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("symlink check failed: $original", e)
        }
    }

    /**
     * B2 落盤門：驗最終目標非連結（NOFOLLOW lstat）、暫存非連結，
     * 且兩者正規路徑仍在定錨 root 域內（防父段／root 替換後指向外部；不再以可能
     * 被換掉的 `root.canonicalPath` 重定錨，一律比對呼叫方傳入的定錨）。
     * 失敗 fail-closed。僅做單次檢查，不消除檢查-搬移之間的併發窗口（見類註解）。
     * 注意 File.getCanonicalPath 對懸空連結不跟隨（返回詞法域內），故 containment
     * 不能替代 lstat，兩者缺一不可，順序不得對調；方法內 lstat→canonical 亦非原子。
     * fallback 退回前呼叫方須重跑整鏈＋本門（見 [writeInternal]）。
     */
    private fun ensurePreMoveNoSymlinkAndContained(
        target: File,
        tmp: File,
        original: String,
        trustedRootCanon: String,
    ) {
        try {
            if (Files.isSymbolicLink(target.toPath())) {
                throw IllegalArgumentException("symlink not allowed: $original")
            }
            if (Files.isSymbolicLink(tmp.toPath())) {
                throw IllegalArgumentException("symlink not allowed: $original")
            }
            val rootCanon = trustedRootCanon
            val targetCanon = target.canonicalPath
            val tmpCanon = tmp.canonicalPath
            if (targetCanon != rootCanon && !targetCanon.startsWith("$rootCanon/")) {
                throw IllegalArgumentException("path escapes root: $original")
            }
            if (!tmpCanon.startsWith("$rootCanon/")) {
                throw IllegalArgumentException("path escapes root: $original")
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("symlink check failed: $original", e)
        }
    }

    /**
     * 缺席根的安全建立：僅用單層 `mkdir`（不用跟隨連結的 `mkdirs`），
     * 建後驗為目錄且非連結。併發競建由建後驗自然容忍（他線程建好即通過）。
     * 毒根先由 [ensureRootChainNoSymlink] 拒絕；已存在普通檔亦 fail-closed。
     */
    private fun ensureRootDirNoFollow(original: String) {
        try {
            ensureRootChainNoSymlink(original)
            if (!root.exists()) {
                root.mkdir()
            }
            if (Files.isSymbolicLink(root.toPath())) {
                throw IllegalArgumentException("symlink not allowed: $original")
            }
            if (!root.isDirectory) {
                throw IllegalArgumentException("root not directory: $original")
            }
            ensureRootChainNoSymlink(original)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("symlink check failed: $original", e)
        }
    }

    /**
     * 列出相對前綴下的檔案（相對路徑回傳，排序穩定；前綴非法回空表）。
     *
     * 三預算（超限一律拋 [FileBudgetException]，不靜默截斷）：
     * - [maxEntries]：列出上限（恰滿合法，第 `maxEntries+1` 個才拋
     *   [FileBudgetCode.LIST_TOO_MANY]）；輸入側為 lazy 目錄迭代
     *  （`Files.newDirectoryStream` 逐項遍歷），找到第 `maxEntries+1` 筆即拋，
     *   不先一次配置整個目錄陣列；
     * - [maxDepth]：相對深度上限（根下直屬為 1，檔案不查 depth；
     *   子目錄再往下探才拋 [FileBudgetCode.LIST_TOO_DEEP]，恰滿合法）；
     * - [deadlineMs]：自起走的牆鐘預算（耗盡拋 [FileBudgetCode.LIST_TIMEOUT]）。
     *   迴圈中的牆鐘檢查為 best-effort：以「已訪問 entry」計數每 128 項、
     *   每遇子目錄、以及返回前各補一次檢查（含被略過的 symlink／`.lp-*`／
     *   特殊檔，不以收錄檔數為準）；但 blocking 的檔案系統呼叫
     *  （開流／迭代／canonical）本身不可中止，超時只能在下一個檢查點拋出，
     *   不作硬性 wall-clock 保證。
     *
     * NOFOLLOW 語義（#57）：[root] 鏈先驗，失敗 fail-closed 回空表；
     * 基址及遍歷途中遇 symlink 一律略過不下鑽，不跟隨；
     * 以祖先鏈正規目錄集合防循環（兄弟 diamond 可重入、真環裁剪；
     * canonical 取不到時 fail-closed 跳過該子樹）。
     * 條目僅回相對路徑（不含 root 絕對前綴，不洩全路徑；絕對路徑永不回，
     * 此為守衛保證；單檔基址同樣只回相對）；
     * DirectoryStream 單遍 best-effort，不保證單遍快照隔離；併發增刪中途可見，不保證跨調一致。
     * 殘餘：回傳檔名本身即洩露存在性，呼叫方不得將列目結果視為授權證明；
     * 併發替換下僅保證單遍有界（不跟隨；blocking 呼叫仍只到下一檢查點，見上），不保證與某次檢查原子一致；
     * 併發替換下鏈外名可短暫以域內相對形回（sub→out 後已開流，守衛擋不住，
     * 僅揭露存在性（授權證明限制見上；僅單遍 best-effort、不跨調一致））。
     * 開流失敗的子目錄視為空（沿舊 `listFiles()==null` 語義），不中斷兄弟列舉。
     * 最終排序僅在成功路徑最後一步做一次 `out.sorted()`（預算按發現序，
     * 不做 per-dir 預排序）。
     *
     * 資源保證：每個已開流目錄持有可關閉的 [DirectoryStream]；耗盡／迭代失敗時
     * 原子 `close + pathCanons.remove + pop`，拋預算異常時外層 `try/finally`
     * 關閉棧上殘留的全部開流，避免洩漏。
     *
     * 測試接縫：另有 `internal` 同名多載，追加 `nowNanos`／`openStream` 可注入參數
     * （生產預設分別為系統牆鐘與 lazy 目錄迭代工廠，行為不變；見該多載 KDoc）。
     * 主列舉禁止使用全量配置 API（見 ScopedFileStoreBudgetTest 靜態約束）。
     */
    fun list(
        relativePrefix: String = "",
        maxEntries: Int = MAX_LIST_ENTRIES,
        maxDepth: Int = MAX_LIST_DEPTH,
        deadlineMs: Long = LIST_DEADLINE_MS,
    ): List<String> = list(
        relativePrefix,
        maxEntries,
        maxDepth,
        deadlineMs,
        System::nanoTime,
        { dir -> defaultOpenStream(dir) },
    )

    /**
     * 測試接縫多載（生產經由 [list] 委派，預設行為不變）。
     *
     * - [nowNanos]：預設為系統牆鐘；測試可注入每次呼叫前進固定奈秒的 fake clock，
     *   使 deadline 確定性可測（無 sleep、無 flaky）。新碼以「已訪問 entry」計數
     *   每 128 項觸發檢查（含被略過者），舊邏輯僅計收錄檔故同場景不超時。
     * - [openStream]：預設為 lazy 目錄迭代工廠；測試可注入計數工廠，證明第
     *   `maxEntries+1` 筆即拋、未全量配置（fail-fast）。
     * 主列舉僅以 `Files.newDirectoryStream` 逐項遍歷，不一次配置整個目錄陣列。
     */
    internal fun list(
        relativePrefix: String,
        maxEntries: Int,
        maxDepth: Int,
        deadlineMs: Long,
        nowNanos: () -> Long,
        openStream: (File) -> DirectoryStream<Path>?,
    ): List<String> {
        require(maxEntries >= 1) { "maxEntries must be >= 1" }
        require(maxDepth >= 0) { "maxDepth must be >= 0" }
        require(deadlineMs >= 0) { "deadlineMs must be >= 0" }
        try {
            ensureRootChainNoSymlink(relativePrefix.ifEmpty { "<root>" })
        } catch (_: IllegalArgumentException) {
            return emptyList()
        }
        val base = if (relativePrefix.isEmpty()) {
            root
        } else {
            try {
                resolve(relativePrefix)
            } catch (_: IllegalArgumentException) {
                return emptyList()
            }
        }
        if (!base.exists()) return emptyList()
        if (Files.isSymbolicLink(base.toPath())) return emptyList()
        if (!base.isDirectory) {
            if (!base.isFile || base.name.startsWith(TMP_NAME_PREFIX)) return emptyList()
            val rel = try {
                base.relativeTo(root).path.replace('\\', '/')
            } catch (_: IllegalArgumentException) {
                return emptyList()
            }
            if (rel.startsWith("/")) return emptyList()
            return if (FileScope.sanitizeRelative(rel) != null) listOf(rel) else emptyList()
        }
        val deadlineNanos = nowNanos() + deadlineMs * 1_000_000L
        fun checkDeadline() {
            if (nowNanos() - deadlineNanos >= 0) {
                throw FileBudgetException(
                    FileBudgetCode.LIST_TIMEOUT,
                    "listing exceeded ${deadlineMs}ms budget under $relativePrefix",
                )
            }
        }
        checkDeadline()
        val out = ArrayList<String>()
        // 祖先鏈正規化路徑：只擋「回到自己祖先」的真環；NOFOLLOW 下 symlink
        // 子項已在下方直接略過（不下鑽），diamond 別名不再列出（#57 凌駕 legacy）。
        val pathCanons = HashSet<String>()
        pathCanons.add(canonicalOrAbsolute(base))
        // 已訪問 entry 計數（含被略過者）：每 128 項無條件檢查一次 deadline。
        var visited = 0
        fun noteVisited() {
            visited++
            if (visited % 128 == 0) checkDeadline()
        }
        val stack = ArrayDeque<DirFrame>()
        val baseStream = openStream(base) ?: return emptyList()
        stack.addLast(DirFrame(base, 0, canonicalOrAbsolute(base), baseStream, baseStream.iterator()))
        try {
            while (stack.isNotEmpty()) {
                val frame = stack.last()
                val childPath: Path? = try {
                    if (frame.iter.hasNext()) frame.iter.next() else null
                } catch (_: DirectoryIteratorException) {
                    null
                } catch (_: IOException) {
                    null
                } catch (_: SecurityException) {
                    null
                } catch (_: NoSuchElementException) {
                    null
                }
                if (childPath == null) {
                    try {
                        frame.stream.close()
                    } catch (_: Exception) {
                    }
                    pathCanons.remove(frame.canon)
                    stack.removeLast()
                    continue
                }
                noteVisited()
                // NOFOLLOW：遇 symlink 直接略過、不下鑽（含懸空連結）。
                if (Files.isSymbolicLink(childPath)) continue
                val child = childPath.toFile()
                if (child.isFile && !child.name.startsWith(TMP_NAME_PREFIX)) {
                    // B2：條目只回相對路徑，不洩絕對全路徑；競態下偏出域的相對化
                    // 失敗或含穿越段一律略過（fail-closed，不洩全路徑）。
                    val rel = try {
                        child.relativeTo(root).path.replace('\\', '/')
                    } catch (_: IllegalArgumentException) {
                        continue
                    }
                    if (rel.startsWith("/")) continue
                    if (FileScope.sanitizeRelative(rel) == null) continue
                    out.add(rel)
                    if (out.size > maxEntries) {
                        throw FileBudgetException(
                            FileBudgetCode.LIST_TOO_MANY,
                            "listing exceeded $maxEntries entries under $relativePrefix",
                        )
                    }
                } else if (child.isDirectory) {
                    checkDeadline()
                    val childDepth = frame.depth + 1
                    if (childDepth > maxDepth) {
                        throw FileBudgetException(
                            FileBudgetCode.LIST_TOO_DEEP,
                            "listing exceeded depth $maxDepth under $relativePrefix",
                        )
                    }
                    val canon = try {
                        child.canonicalPath
                    } catch (_: IOException) {
                        continue
                    }
                    if (!pathCanons.add(canon)) continue
                    val sub = openStream(child)
                    if (sub == null) {
                        pathCanons.remove(canon)
                        continue
                    }
                    stack.addLast(DirFrame(child, childDepth, canon, sub, sub.iterator()))
                }
                // 懸空連結已在上方略過；其他非檔非目錄沿舊行為忽略
                //（visited 已計數，deadline 仍覆蓋）。
            }
            checkDeadline()
            return out.sorted()
        } finally {
            for (f in stack) {
                try {
                    f.stream.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    /** 生產預設的 lazy 目錄開流（開流失敗視為空，不中斷兄弟列舉）。 */
    private fun defaultOpenStream(dir: File): DirectoryStream<Path>? {
        return try {
            Files.newDirectoryStream(dir.toPath())
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }
    }

    /** 刪除檔案；不存在回 false（冪等語義：重複刪不報錯）。
     * 實際刪除前重驗一次整鏈（[resolve] 內已驗第一次）；刪前再做最終目標
     * NOFOLLOW lstat（是 link 即拒不刪）＋正規路徑域內 containment：
     * 單次置換的誤刪替換後外部檔已修（轉為拒絕）。其中 final-link 置換
     * （刪鏈本身，unlink 不跟隨）是 fail-closed 政策選擇，parent-link 置換
     * （跟隨父鏈刪外部）才是真正安全修復。重驗→刪除之間仍有有界併發窗口
     * （攻擊者可無限重試，rename 原子，窗口非時間極小，僅有界不消除；
     * Java 無 O_NOFOLLOW/openat，見類註解），
     * 此為變更操作固有風險，呼叫方不得假設檢查後路徑不可變。
     * root 定錨：初驗後 snapshot 可信 root（正規路徑＋fileKey 雙定錨），containment 比對定錨，
     * 操作前以 [reverifyRootIdentity] 再驗 root 本體；單次 root 本體置換與實目錄替換皆拒絕，
     * 雙重替換仍僅有界。
     *
     * 測試接縫：實際經由 [deleteInternal] 委派（`preGateHook` 預設 null；
     * 測試可注入 hook 在初驗與終門之間 swap root）。 */
    fun delete(relativePath: String): Boolean = deleteInternal(relativePath)

    /**
     * 測試接縫多載（生產經由 [delete] 委派，預設行為不變）。
     *
     * - [preGateHook]：B2 終門之前調用（測試用來在初驗與終門之間 swap root）。
     */
    internal fun deleteInternal(relativePath: String, preGateHook: (() -> Unit)? = null): Boolean {
        val target = resolve(relativePath)
        val safe = FileScope.sanitizeRelative(relativePath)
            ?: throw IllegalArgumentException("unsafe path: $relativePath")
        ensureNoSymlinkChain(safe, relativePath)
        val trustedRoot = snapshotTrustedRoot(relativePath)
        // 測試 seam：B2 終門之前調用（初驗與終門之間置換 root 用）。
        preGateHook?.invoke()
        // B2 刪前重驗：NOFOLLOW lstat 最終目標，是 link 即拒不刪；
        // 再驗正規路徑仍在定錨域內（防父段／root 替換後指向外部，不重讀 root 定錨）。
        if (Files.isSymbolicLink(target.toPath())) {
            throw IllegalArgumentException("symlink not allowed: $relativePath")
        }
        val targetCanon = try {
            target.canonicalPath
        } catch (e: Exception) {
            throw IllegalArgumentException("symlink check failed: $relativePath", e)
        }
        if (targetCanon != trustedRoot.canon && !targetCanon.startsWith("${trustedRoot.canon}/")) {
            throw IllegalArgumentException("path escapes root: $relativePath")
        }
        reverifyRootIdentity(trustedRoot, relativePath)
        return target.delete()
    }

    /** 迭代下探幀（顯式棧，避免遞迴深度風險；持有可關閉的 lazy 目錄迭代）。 */
    private data class DirFrame(
        val dir: File,
        val depth: Int,
        val canon: String,
        val stream: DirectoryStream<Path>,
        val iter: Iterator<Path>,
    )

    /**
     * 固定記憶體比對：長度已由呼叫方確認相等；逐塊讀舊檔對新位元組，
     * 首異即停，尾端多位元組亦判異。
     */
    private fun contentEquals(target: File, bytes: ByteArray): Boolean {
        FileInputStream(target).use { input ->
            val buf = ByteArray(READ_CHUNK_BYTES)
            var offset = 0
            while (offset < bytes.size) {
                val n = input.read(buf, 0, minOf(buf.size, bytes.size - offset))
                if (n == -1) return false
                for (i in 0 until n) {
                    if (buf[i] != bytes[offset + i]) return false
                }
                offset += n
            }
            return input.read() == -1
        }
    }

    companion object {
        /** 讀入上限 1 MiB（對齊 provider model-list／WebFetch 絕對上限；patch 512 KiB 上限在其內）。 */
        const val MAX_READ_BYTES = 1 * 1024 * 1024

        /** 列舉條目上限（恰滿合法，超一才拋）。 */
        const val MAX_LIST_ENTRIES = 2_000

        /** 列舉相對深度上限（根下直屬為 1）。 */
        const val MAX_LIST_DEPTH = 32

        /** 列舉牆鐘預算（毫秒）。 */
        const val LIST_DEADLINE_MS = 10_000L

        /** 串流讀寫塊大小（抄 WebFetch／attach 的 8 KiB）。 */
        const val READ_CHUNK_BYTES = 8192

        /** 寫入暫存檔名前綴（列舉時過濾同類）。 */
        const val TMP_NAME_PREFIX = ".lp-"

        /** 內容雜湊（呼叫方做去重/審計指紋用）。 */
        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun canonicalOrAbsolute(dir: File): String =
            try {
                dir.canonicalPath
            } catch (_: IOException) {
                dir.absolutePath
            }
    }
}
