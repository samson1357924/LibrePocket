package dev.librepocket.files

import java.io.FileNotFoundException

/**
 * S1-A 已授權 SAF 樹的 IO 抽象（BACKLOG D05「SAF/私有域文件」）。
 *
 * 分工（見 [ScopedFileStore] 類註解）：私有域 IO 由 [ScopedFileStore]
 * 直行；已授權 SAF 樹（使用者經系統 picker 授予，[FileScope.decide] 判為
 * SAF_GRANTED 的前綴範圍）的实际 IO 走本橋；跨域一律不經本橋
 * （play 拒絕，foss/github 需 D09 提權橋，見 [FileEditTools]）。
 *
 * 產品實現見 [AndroidSafFileBridge]（ContentResolver 直行，無需新權限）；
 * 單測用 map-backed 假實現。
 */
interface SafFileBridge {
    fun exists(absolutePath: String): Boolean

    @Throws(FileNotFoundException::class)
    fun read(absolutePath: String): ByteArray

    @Throws(FileNotFoundException::class)
    fun write(absolutePath: String, bytes: ByteArray)

    /** 列出 [absolutePrefix] 之下的檔案（絕對路徑回傳，排序穩定）。 */
    fun list(absolutePrefix: String): List<String>
}

/** 尚未接線時的佔位橋：讀寫一律抛 [FileNotFoundException]，呼叫方收斂為誠實失敗。 */
class MissingSafBridge : SafFileBridge {
    override fun exists(absolutePath: String): Boolean = false

    override fun read(absolutePath: String): ByteArray =
        throw FileNotFoundException("SAF bridge not wired: $absolutePath")

    override fun write(absolutePath: String, bytes: ByteArray): Unit =
        throw FileNotFoundException("SAF bridge not wired: $absolutePath")

    override fun list(absolutePrefix: String): List<String> = emptyList()
}
