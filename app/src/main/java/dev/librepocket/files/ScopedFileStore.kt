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

    /** 讀檔；不存在拋 [java.io.FileNotFoundException]。 */
    fun read(relativePath: String): ByteArray {
        val target = resolve(relativePath)
        if (!target.isFile) throw java.io.FileNotFoundException(relativePath)
        return target.readBytes()
    }

    fun exists(relativePath: String): Boolean = resolve(relativePath).isFile

    /**
     * 冪等寫入：內容一致直接回 [WriteOutcome.Unchanged]（不改 mtime）；
     * 否則經暫存檔原子搬移落盤。父目錄自動建立。
     */
    fun write(relativePath: String, bytes: ByteArray): WriteOutcome {
        val target = resolve(relativePath)
        if (target.isFile && target.readBytes().contentEquals(bytes)) {
            return WriteOutcome.Unchanged
        }
        val created = !target.exists()
        target.parentFile?.mkdirs()
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

    /** 列出相對前綴下的檔案（相對路徑回傳，排序穩定；前綴非法回空表）。 */
    fun list(relativePrefix: String = ""): List<String> {
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
        val roots = if (base.isDirectory) base.walkTopDown() else sequenceOf(base)
        return roots
            .filter { it.isFile && !it.name.startsWith(".lp-") }
            .map { it.relativeTo(root).path.replace('\\', '/') }
            .sorted()
            .toList()
    }

    /** 刪除檔案；不存在回 false（冪等語義：重複刪不報錯）。 */
    fun delete(relativePath: String): Boolean = resolve(relativePath).delete()

    companion object {
        /** 內容雜湊（呼叫方做去重/審計指紋用）。 */
        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
