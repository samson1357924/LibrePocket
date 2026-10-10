package dev.librepocket.files

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
 * - 讀寫有界：[read] 先以 `length()` 快篩、再以 `cap+1` sentinel
 *   串流讀取（抄 [dev.librepocket.tool.WebFetch.readCapped]／provider
 *   `readBoundedProviderBody` 模式：exact-cap 視為合法 EOF，超限才拋
 *   [FileBudgetException]，絕不把超大檔灌進記憶體）；[write] 的冪等比對
 *   先比長度、再以 8 KiB 固定緩衝串流比對（記憶體 O(1)，不先讀完整舊檔）。
 * - 列舉有窗：[list] 具條目／深度／deadline 三預算，超限拋
 *   [FileBudgetException]（絕不靜默截斷）；目錄環以正規化路徑去重裁剪
 *   （無環樹輸出與過去逐字一致，僅真環被截斷；跟隨與否的語義由 #10 定奪，
 *   本類不重做）。
 * - 寫入冪等：內容相同即回 [WriteOutcome.Unchanged] 且不改 mtime；
 *   不同才原子落盤（同目錄暫存 +搬移），回 Created/Updated。
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
     * 回傳前二次確認正規化絕對路徑仍在根內（防 `a/../..` 類邊界）。
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
        return target
    }

    /**
     * 讀檔；不存在拋 [java.io.FileNotFoundException]，超過 [maxBytes] 拋
     * [FileBudgetException]（[FileBudgetCode.READ_TOO_LARGE]）。
     * `length()` 快篩只是建議值；權威判定是串流中的 `cap+1` sentinel
     * （檔在檢查後變大仍會被擋下），恰等於上限視為合法 EOF。
     */
    fun read(relativePath: String, maxBytes: Int = MAX_READ_BYTES): ByteArray {
        require(maxBytes >= 1) { "maxBytes must be >= 1" }
        val target = resolve(relativePath)
        if (!target.isFile) throw java.io.FileNotFoundException(relativePath)
        if (target.length() > maxBytes) {
            throw FileBudgetException(
                FileBudgetCode.READ_TOO_LARGE,
                "file too large: $relativePath (${target.length()} bytes, limit $maxBytes)",
            )
        }
        val cap = maxBytes.toLong() + 1
        FileInputStream(target).use { input ->
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
            return out.toByteArray()
        }
    }

    fun exists(relativePath: String): Boolean = resolve(relativePath).isFile

    /**
     * 冪等寫入：內容一致直接回 [WriteOutcome.Unchanged]（不改 mtime）；
     * 否則經暫存檔原子搬移落盤。父目錄自動建立。
     *
     * 一致性比對有界：先比 `length()`（不等即不同，不讀內容），再以固定
     * 緩衝串流逐塊比對、首異即停；舊檔再大也不會被完整讀進記憶體。
     * （檢查與比對之間檔被竄改只會導向「不同→重寫」的安全方向。）
     */
    fun write(relativePath: String, bytes: ByteArray): WriteOutcome {
        val target = resolve(relativePath)
        if (target.isFile && target.length() == bytes.size.toLong() && contentEquals(target, bytes)) {
            return WriteOutcome.Unchanged
        }
        val created = !target.exists()
        target.parentFile?.mkdirs()
        val tmp = File.createTempFile(TMP_NAME_PREFIX, ".tmp", target.parentFile ?: root)
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
     * 列出相對前綴下的檔案（相對路徑回傳，排序穩定；前綴非法回空表）。
     *
     * 三預算（超限一律拋 [FileBudgetException]，不靜默截斷）：
     * - [maxEntries]：列出上限（恰滿合法，第 `maxEntries+1` 個才拋
     *   [FileBudgetCode.LIST_TOO_MANY]）；
     * - [maxDepth]：相對深度上限（根下直屬為 1；再往下探才拋
     *   [FileBudgetCode.LIST_TOO_DEEP]）；
     * - [deadlineMs]：自起走的牆鐘預算（耗盡拋 [FileBudgetCode.LIST_TIMEOUT]）。
     *
     * 環守衛：僅裁剪「正規化路徑已在當前下探祖先鏈」的真環（兄弟 diamond
     * 連結照舊各自列出，無環樹輸出與舊 `walkTopDown` 一致；跟隨與否的語義
     * 由 #10 定奪，本類不重做）。正規化失敗的目錄 fail-closed 略過下探。
     * 讀不到的子目錄（`listFiles()` 回 null）視為空，不中斷兄弟列舉。
     */
    fun list(
        relativePrefix: String = "",
        maxEntries: Int = MAX_LIST_ENTRIES,
        maxDepth: Int = MAX_LIST_DEPTH,
        deadlineMs: Long = LIST_DEADLINE_MS,
    ): List<String> {
        require(maxEntries >= 1) { "maxEntries must be >= 1" }
        require(maxDepth >= 0) { "maxDepth must be >= 0" }
        require(deadlineMs >= 0) { "deadlineMs must be >= 0" }
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
        if (!base.isDirectory) {
            return if (base.isFile && !base.name.startsWith(TMP_NAME_PREFIX)) {
                listOf(base.relativeTo(root).path.replace('\\', '/'))
            } else {
                emptyList()
            }
        }
        val deadlineNanos = System.nanoTime() + deadlineMs * 1_000_000L
        fun checkDeadline() {
            if (System.nanoTime() - deadlineNanos >= 0) {
                throw FileBudgetException(
                    FileBudgetCode.LIST_TIMEOUT,
                    "listing exceeded ${deadlineMs}ms budget under $relativePrefix",
                )
            }
        }
        checkDeadline()
        val out = ArrayList<String>()
        // 祖先鏈正規化路徑：只擋「回到自己祖先」的真環；兄弟 diamond 連結
        // （正規化相同但不在同一條下探鏈）照舊列出，保持無環輸出不變。
        val pathCanons = HashSet<String>()
        pathCanons.add(canonicalOrAbsolute(base))
        var sinceDeadlineCheck = 0
        fun noteProgress() {
            sinceDeadlineCheck++
            if (sinceDeadlineCheck % 128 == 0) checkDeadline()
        }
        val stack = ArrayDeque<DirFrame>()
        stack.addLast(DirFrame(base, 0, canonicalOrAbsolute(base), base.listFiles() ?: emptyArray()))
        while (stack.isNotEmpty()) {
            val frame = stack.last()
            if (frame.index >= frame.children.size) {
                pathCanons.remove(frame.canon)
                stack.removeLast()
                continue
            }
            val child = frame.children[frame.index++]
            if (child.isFile && !child.name.startsWith(TMP_NAME_PREFIX)) {
                out.add(child.relativeTo(root).path.replace('\\', '/'))
                if (out.size > maxEntries) {
                    throw FileBudgetException(
                        FileBudgetCode.LIST_TOO_MANY,
                        "listing exceeded $maxEntries entries under $relativePrefix",
                    )
                }
                noteProgress()
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
                stack.addLast(DirFrame(child, childDepth, canon, child.listFiles() ?: emptyArray()))
            }
            // 懸空連結等非檔非目錄：沿舊行為忽略（舊 walkTopDown 經 isFile 濾掉同類）。
        }
        return out.sorted()
    }

    /** 刪除檔案；不存在回 false（冪等語義：重複刪不報錯）。 */
    fun delete(relativePath: String): Boolean = resolve(relativePath).delete()

    /** 迭代下探幀（顯式棧，避免遞迴深度風險）。 */
    private data class DirFrame(
        val dir: File,
        val depth: Int,
        val canon: String,
        val children: Array<File>,
        var index: Int = 0,
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
