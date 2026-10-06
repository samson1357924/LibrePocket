package dev.librepocket.files

import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.S1bFallback
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.UUID

/**
 * S1-A `file.attach`（READ，開關 `files`，矩陣 Photo Picker / SAF 文件行）。
 *
 * 定位：Photo Picker / SAF 入口的 tool + executor 骨架，為 ChatScreen
 * 附件佔位鋪路——**不改 UI**（`ChatScreen` 的 `+` 按鈕仍是佔位）。
 * 後續切面只需：系統 picker 回傳 Uri → [UriAttachmentSource] →
 * [AttachmentStager.stage] → 私有域工作區 → 聊天圖片鏈
 * （見 P1_SPEC §4：`content://` 先複製進工作區，有界 10 MiB）。
 *
 * 規則：
 * - 來源抽象 [AttachmentSource]：產品接 ContentResolver（見
 *   [UriAttachmentSource]），單測用假實現；本檔零 Android 依賴。
 * - 大小有界：宣告/實際超過 [MAX_SIZE_BYTES] 一律拒收（先讀 `max+1`
 *   位元組判定，不把超大檔灌進記憶體）。
 * - 類型白名單：預設僅圖片（MIME 前綴 image/）；呼叫方可放寬（如 SAF 通用檔）。
 * - 落盤位置：`attachments/<uuid>-<安全檔名>`（私有域，[ScopedFileStore]
 *   原子寫入；檔名非法回退 `attachment`）。
 */
object FileAttachTools {

    const val ATTACH_NAME = "file.attach"
    const val SWITCH = FileEditTools.SWITCH
    const val SWITCH_DEFAULT = FileEditTools.SWITCH_DEFAULT

    const val FALLBACK_HINT =
        "pick the file manually with the system Photo Picker or Files app"

    /** 附件上限 10 MiB（對齊 P1_SPEC §4 圖片鏈）。 */
    const val MAX_SIZE_BYTES = 10L * 1024 * 1024

    const val STAGING_DIR = "attachments"

    val DEFAULT_MIME_TYPES: List<String> = listOf("image/*")

    /**
     * MIME 允收判定：精確匹配或按斜線前類型前綴匹配；`mime` 未知（null/空）
     * 視為允收（後續仍有大小上限兜底；呼叫方應盡量提供真實類型）。
     */
    fun mimeAllowed(mime: String?, accepted: List<String>): Boolean {
        if (mime.isNullOrBlank()) return true
        val m = mime.substringBefore(";").trim().lowercase()
        if (m.isEmpty()) return true
        return accepted.any { rule ->
            val p = rule.trim().lowercase()
            p == "*/*" || p == m || (p.endsWith("/*") && m.startsWith(p.removeSuffix("*")))
        }
    }
}

/** 附件位元組來源（產品：ContentResolver 包裝；單測：記憶體假實現）。 */
interface AttachmentSource {
    fun displayName(): String?
    fun mimeType(): String?
    /** 已知回位元組數，未知回 null（此時以實際讀取量為準）。 */
    fun sizeBytes(): Long?
    fun openInputStream(): InputStream
}

data class StagedAttachment(
    /** 私有域相對路徑（可直接餵聊天圖片鏈）。 */
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256Hex: String,
)

data class StageResult(
    val ok: Boolean,
    val attachment: StagedAttachment? = null,
    val reason: DenyReason? = null,
    val detail: String? = null,
    val message: String = "",
)

/** `file.attach` 執行器（S1-A 骨架）：暫存進私有域工作區。 */
class AttachmentStager(
    private val store: ScopedFileStore,
    private val stagingDir: String = FileAttachTools.STAGING_DIR,
    private val filesEnabled: Boolean = true,
) {
    fun stage(
        source: AttachmentSource,
        acceptedMimes: List<String> = FileAttachTools.DEFAULT_MIME_TYPES,
        maxSizeBytes: Long = FileAttachTools.MAX_SIZE_BYTES,
    ): StageResult {
        if (!filesEnabled) {
            val msg = S1bFallback.message(
                what = "暫存附件（SWITCH_OFF）",
                reason = DenyReason.USER_DISABLED,
                detail = "SWITCH_OFF",
                alternative = FileAttachTools.FALLBACK_HINT,
                needFromUser = "到設定開啟「檔案」開關後重試",
            )
            return StageResult(false, reason = DenyReason.USER_DISABLED, detail = "SWITCH_OFF", message = msg)
        }
        val mime = source.mimeType()?.substringBefore(";")?.trim()?.lowercase().orEmpty()
        if (!FileAttachTools.mimeAllowed(mime, acceptedMimes)) {
            return StageResult(
                ok = false,
                detail = "MIME_NOT_ALLOWED",
                message = "不支援的類型「$mime」，僅接受 ${acceptedMimes.joinToString()}，未暫存",
            )
        }
        val declared = try {
            source.sizeBytes()
        } catch (_: Exception) {
            null
        }
        if (declared != null && declared < 0) {
            return StageResult(false, detail = "BAD_SIZE", message = "附件大小未知非法，未暫存")
        }
        if (declared != null && declared > maxSizeBytes) {
            return StageResult(
                ok = false,
                detail = "FILE_TOO_LARGE",
                message = "附件過大（$declared 位元組，上限 $maxSizeBytes），未暫存",
            )
        }
        val bytes = try {
            readCapped(source.openInputStream(), maxSizeBytes + 1)
        } catch (e: FileNotFoundException) {
            return StageResult(false, detail = "NOT_FOUND", message = "附件讀取不到（授權可能已失效），未暫存")
        } catch (e: Exception) {
            return StageResult(false, detail = "READ_FAILED", message = "附件讀取失敗：${e.message}，未暫存")
        }
        if (bytes.size > maxSizeBytes) {
            return StageResult(
                ok = false,
                detail = "FILE_TOO_LARGE",
                message = "附件過大（超過 $maxSizeBytes 位元組），未暫存",
            )
        }
        val rawName = source.displayName()
            ?.substringAfterLast("/")
            ?.substringAfterLast("\\")
            ?.trim()
            .orEmpty()
        val safeBase = if (FileScope.isSafeName(rawName)) rawName else "attachment"
        val relativePath = "$stagingDir/${UUID.randomUUID()}-$safeBase"
        try {
            store.write(relativePath, bytes)
        } catch (e: Exception) {
            return StageResult(false, detail = "WRITE_FAILED", message = "附件暫存失敗：${e.message}")
        }
        return StageResult(
            ok = true,
            attachment = StagedAttachment(
                relativePath = relativePath,
                mimeType = mime.ifEmpty { "application/octet-stream" },
                sizeBytes = bytes.size.toLong(),
                sha256Hex = ScopedFileStore.sha256Hex(bytes),
            ),
            message = "已暫存附件（${bytes.size} 位元組）",
        )
    }

    private fun readCapped(stream: InputStream, cap: Long): ByteArray {
        stream.use { input ->
            val out = java.io.ByteArrayOutputStream(minOf(cap, 8192).toInt())
            val buf = ByteArray(8192)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                total += n
                if (total > cap) {
                    // 已超上限：多讀的部分照實保留（呼叫方以此判定超限），但不再續讀。
                    out.write(buf, 0, n)
                    break
                }
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}

/**
 * 附件系統入口常數（S1-A 只定錨，不接 UI）。
 *
 * - 圖片：`ActivityResultContracts.PickVisualMedia`（免 `READ_MEDIA_IMAGES`）。
 * - 通用檔：`ActivityResultContracts.OpenDocument` / `ACTION_OPEN_DOCUMENT`
 *  （SAF 逐次授權；持久授權由後續切面的 grant store 持有）。
 * - ChatScreen 的 `+` 按鈕接線是後續切面；本期不改任何 UI 檔。
 */
object AttachEntryPoints {
    const val PHOTO_PICKER_CONTRACT =
        "androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia"
    const val SAF_OPEN_DOCUMENT_CONTRACT =
        "androidx.activity.result.contract.ActivityResultContracts.OpenDocument"
    const val SAF_ACTION_OPEN_DOCUMENT = "android.intent.action.OPEN_DOCUMENT"

    val PHOTO_PICKER_MIMES: List<String> = listOf("image/*")
}
