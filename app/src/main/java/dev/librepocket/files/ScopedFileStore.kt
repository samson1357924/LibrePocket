package dev.librepocket.files

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

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
 *   app 私有域），不得傳入不可信別名；`alias/.`、`alias/sub` 類毒根一律拒絕。
 *   若傳入非正規系統別名路徑，將 fail-closed 拒絕，呼叫方應改傳正規路徑。
 * - 寫入冪等：內容相同即回 [WriteOutcome.Unchanged] 且不改 mtime；
 *   不同才原子落盤（同目錄暫存 +搬移），回 Created/Updated。
 * - 寫前驗 parent chain：[write] 在建父目錄前後各驗一次鏈上無
 *   symlink，並以 NOFOLLOW 逐段建父目錄（每段前驗連結、單層 `mkdir`，
 *   不用會跟隨連結的 `mkdirs`），縮小檢查-使用窗口。
 * - 點存取使用前重驗：[exists]/[delete] 在實際 `isFile`/`delete` 前重驗
 *   一次整鏈（[resolve] 內已驗第一次）；[read] 在回傳前重驗一次。
 *   重驗只能縮小窗口，不能消除——第二次驗證與實際 IO 之間攻擊者仍可能
 *   以 `rename`/`symlink` 替換路徑段（見下）。
 * - TOCTOU 殘餘風險：單次檢查無法解決併發替換——檢查與實際 IO 之間
 *   攻擊者仍可能以 `rename`/`symlink` 替換路徑段。[read] 在回傳前重驗
 *   一次鏈上無連結並丟棄可疑結果，可將單次替換轉為拒絕，但雙重替換
 *   （檢查→換入連結→IO→換回真檔）仍可能穿出；[write] 的父目錄逐段建立、
 *   暫存建立與搬移之間亦有同類窗口；[exists] 的第二次驗證與 `isFile` 之間、
 *   [delete] 的第二次驗證與實際刪除之間同樣存在窗口（[delete] 為變更操作，
 *   殘餘窗口內可能誤刪替換後的外部檔）。本類只保證有界行為：併發替換最多造成本次呼叫被拒
 *   （[IllegalArgumentException]）、讀到舊內容、讀到替換後外部內容、
 *   或把本次寫入落在鏈外（後兩者為殘餘小機率，寫穿出另見壓力測試說明），
 *   不保證檢查後鏈上零變動；呼叫方不得假設檢查後路徑不可變。
 *   併發壓力測試僅證明行為有界（無未處理異常、無懸掛、store 事後仍可用、
 *   固定外部檔未被竄改），不宣稱消除競態；翻轉路徑上的併發寫入仍有殘餘小機率
 *   把本次內容落在鏈外、併發列目仍有殘餘小機率短暫列出鏈外檔名
 *   （兩者僅記錄、不計為通過條件），系統性穿出由單線程確定性測試覆蓋。
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
     * 以 [FileScope.normalize] 先折疊尾端 `/.`、`sub/..`，再逐段 lstat；
     * 不存在路徑回 false（缺席根仍可檢查已存在的毒父段）。
     * 呼叫方須傳正規可信根；非正規系統別名將 fail-closed，屬預期行為。
     */
    private fun ensureRootChainNoSymlink(original: String) {
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

    /** 讀檔；不存在拋 [java.io.FileNotFoundException]。
     * 回傳前重驗鏈上無連結（見類註解 TOCTOU 說明；雙重替換仍為殘餘風險）。 */
    fun read(relativePath: String): ByteArray {
        val target = resolve(relativePath)
        if (!target.isFile) throw java.io.FileNotFoundException(relativePath)
        val bytes = target.readBytes()
        val safe = FileScope.sanitizeRelative(relativePath)
            ?: throw IllegalArgumentException("unsafe path: $relativePath")
        ensureNoSymlinkChain(safe, relativePath)
        return bytes
    }

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
     * 殘餘：建前二驗→逐段建→建後三驗與暫存建立/搬移之間仍有窗口，
     * 併發 `rename`/`symlink` 替換理論上仍可能把暫存或目標落在鏈外，
     * 見類註解 TOCTOU 說明。
     */
    fun write(relativePath: String, bytes: ByteArray): WriteOutcome {
        val target = resolve(relativePath)
        val safe = FileScope.sanitizeRelative(relativePath)
            ?: throw IllegalArgumentException("unsafe path: $relativePath")
        mkdirParentNoFollow(safe, relativePath)
        // 建後三驗：mkdir 可能新建目錄，併發替換窗口下再次確認。
        ensureNoSymlinkChain(safe, relativePath)
        if (target.isFile && target.readBytes().contentEquals(bytes)) {
            return WriteOutcome.Unchanged
        }
        val created = !target.exists()
        val tmp = File.createTempFile(".lp-", ".tmp", target.parentFile ?: root)
        try {
            tmp.writeBytes(bytes)
            try {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                // 檔案系統不支援原子搬移時退回普通覆蓋（仍先寫暫存，保證不截斷原檔）。
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
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
     * 懸空連結視為連結一律拒絕；檢查失敗 fail-closed。
     * 殘餘：段前驗→`mkdir`→建後驗之間仍有併發替換窗口，見類註解。
     */
    private fun mkdirParentNoFollow(safe: String, original: String) {
        val segs = safe.split("/")
        if (segs.size <= 1) {
            ensureNoSymlinkChain(safe, original)
            return
        }
        ensureNoSymlinkChain(safe, original)
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
                cur = next
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("symlink check failed: $original", e)
        }
    }

    /** 列出相對前綴下的檔案（相對路徑回傳，排序穩定；前綴非法回空表）。
     * 不跟隨 symlink：基址及 walk 途中遇連結一律略過不下鑽；
     * 以正規目錄集合防循環。[root] 自身為連結時回空表（fail-closed）。
     * 殘餘：回傳檔名本身即洩露存在性，呼叫方不得將列目結果視為授權證明；
     * 併發替換下僅保證快照有界（不跟隨、不懸掛），不保證與某次檢查原子一致。 */
    fun list(relativePrefix: String = ""): List<String> {
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
        val roots = if (base.isDirectory) walkNoFollow(base) else sequenceOf(base)
        return roots
            .filter { !Files.isSymbolicLink(it.toPath()) && it.isFile && !it.name.startsWith(".lp-") }
            .map { it.relativeTo(root).path.replace('\\', '/') }
            .filter { FileScope.sanitizeRelative(it) != null }
            .sorted()
            .toList()
    }

    /** NOFOLLOW 目錄 walk：略過一切 symlink，不下鑽；正規路徑去重防循環。 */
    private fun walkNoFollow(base: File): Sequence<File> = sequence {
        val stack = ArrayDeque<File>()
        stack.add(base)
        val visited = mutableSetOf<String>()
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            if (Files.isSymbolicLink(cur.toPath())) continue
            if (cur.isFile) {
                yield(cur)
            } else if (cur.isDirectory) {
                val canon = try {
                    cur.canonicalPath
                } catch (_: Exception) {
                    cur.absolutePath
                }
                if (!visited.add(canon)) continue
                val children = try {
                    cur.listFiles()
                } catch (_: Exception) {
                    null
                } ?: continue
                for (child in children) {
                    if (Files.isSymbolicLink(child.toPath())) continue
                    if (child.isDirectory) {
                        stack.add(child)
                    } else if (child.isFile) {
                        yield(child)
                    }
                    // 其他類型（懸空連結已略過、特殊檔）一律忽略。
                }
            }
            // 不存在或非檔非目錄：忽略（懸空連結已在上方略過）。
        }
    }

    /** 刪除檔案；不存在回 false（冪等語義：重複刪不報錯）。
     * 實際刪除前重驗一次整鏈（[resolve] 內已驗第一次）；重驗→刪除之間
     * 仍有殘餘窗口，併發替換下可能誤刪替換後的外部檔（見類註解），
     * 此為變更操作固有風險，呼叫方不得假設檢查後路徑不可變。 */
    fun delete(relativePath: String): Boolean {
        val target = resolve(relativePath)
        val safe = FileScope.sanitizeRelative(relativePath)
            ?: throw IllegalArgumentException("unsafe path: $relativePath")
        ensureNoSymlinkChain(safe, relativePath)
        return target.delete()
    }

    companion object {
        /** 內容雜湊（呼叫方做去重/審計指紋用）。 */
        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
