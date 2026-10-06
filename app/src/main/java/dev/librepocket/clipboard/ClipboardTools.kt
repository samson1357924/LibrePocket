package dev.librepocket.clipboard

import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.S1bFallback

/**
 * S1-A 剪貼簿工具（BACKLOG D05 後續，矩陣「剪貼簿（讀/寫）」行）。
 *
 * - 前台免權限：[ClipboardManager] 前台讀寫不需宣告任何權限
 *   （本包不新增權限；背景讀在 Android 10+ 本就被系統擋下）。
 * - 背景一律不可用：投影層以前台旗標
 *   （[dev.librepocket.tool.ToolAnnotations.foregroundOnly] +
 *   [dev.librepocket.tool.ProjectionContext.isForeground]）回
 *   UNAVAILABLE + NO_PRIVILEGE；執行器再擋一次（縱深防禦）。
 * - 開關 `clipboard` 預設開；關閉即 USER_DISABLED。
 * - 本檔零 Android 依賴：真正的 [ClipboardManager] IO 在
 *   [AndroidClipboardBridge]，單測用假 [ClipboardBridge]。
 */
object ClipboardTools {

    const val READ_NAME = "clipboard.read"
    const val WRITE_NAME = "clipboard.write"

    const val SWITCH = "clipboard"
    const val SWITCH_DEFAULT = true

    const val FALLBACK_READ_HINT =
        "bring the app to the foreground and retry, or copy the text manually with long-press"
    const val FALLBACK_WRITE_HINT =
        "copy the text manually with long-press select"

    /** 單次讀寫字元上限（防巨型剪貼簿撐爆轉錄；寫入超限直接拒絕，讀取超限截斷）。 */
    const val MAX_CHARS = 64 * 1024

    sealed interface Check {
        data object Allowed : Check
        data class Denied(val reason: DenyReason, val detail: String, val message: String) : Check
        data class Invalid(val detail: String, val message: String) : Check
    }

    fun checkRead(isForeground: Boolean, enabled: Boolean = true): Check {
        if (!enabled) {
            return Check.Denied(
                DenyReason.USER_DISABLED,
                "SWITCH_OFF",
                S1bFallback.message(
                    what = "讀取剪貼簿（SWITCH_OFF）",
                    reason = DenyReason.USER_DISABLED,
                    detail = "SWITCH_OFF",
                    alternative = FALLBACK_READ_HINT,
                    needFromUser = "到設定開啟「剪貼簿」開關後重試",
                ),
            )
        }
        if (!isForeground) {
            return Check.Denied(
                DenyReason.NO_PRIVILEGE,
                "BACKGROUND",
                S1bFallback.message(
                    what = "讀取剪貼簿（BACKGROUND）",
                    reason = DenyReason.NO_PRIVILEGE,
                    detail = "BACKGROUND",
                    alternative = FALLBACK_READ_HINT,
                    needFromUser = "將 App 切回前台後重試",
                ),
            )
        }
        return Check.Allowed
    }

    fun checkWrite(text: CharSequence, isForeground: Boolean, enabled: Boolean = true): Check {
        if (!enabled) {
            return Check.Denied(
                DenyReason.USER_DISABLED,
                "SWITCH_OFF",
                S1bFallback.message(
                    what = "寫入剪貼簿（SWITCH_OFF）",
                    reason = DenyReason.USER_DISABLED,
                    detail = "SWITCH_OFF",
                    alternative = FALLBACK_WRITE_HINT,
                    needFromUser = "到設定開啟「剪貼簿」開關後重試",
                ),
            )
        }
        if (!isForeground) {
            return Check.Denied(
                DenyReason.NO_PRIVILEGE,
                "BACKGROUND",
                S1bFallback.message(
                    what = "寫入剪貼簿（BACKGROUND）",
                    reason = DenyReason.NO_PRIVILEGE,
                    detail = "BACKGROUND",
                    alternative = FALLBACK_WRITE_HINT,
                    needFromUser = "將 App 切回前台後重試",
                ),
            )
        }
        if (text.isBlank()) {
            return Check.Invalid("EMPTY_TEXT", "寫入文字不可為空，未修改剪貼簿")
        }
        if (text.length > MAX_CHARS) {
            return Check.Invalid(
                "TEXT_TOO_LONG",
                "寫入文字過長（${text.length} 字，上限 $MAX_CHARS 字），未修改剪貼簿",
            )
        }
        return Check.Allowed
    }
}

/** 剪貼簿 IO 抽象：產品走 [AndroidClipboardBridge]，單測走假實現。 */
interface ClipboardBridge {
    /** 回傳 null/空表示剪貼簿為空（非錯誤）。 */
    fun readText(): CharSequence?

    fun writeText(label: String, text: CharSequence)
}

data class ClipboardResult(
    val ok: Boolean,
    val text: String? = null,
    val truncated: Boolean = false,
    val reason: DenyReason? = null,
    val detail: String? = null,
    val message: String = "",
)

/**
 * 剪貼簿執行器（S1-A 骨架）。
 *
 * 順序：開關/前台門禁 → 橋接 IO → 上限處理。橋接異常一律收斂為
 * `ok=false`（不拋給呼叫方）；Android 13+ 寫入時的系統覆蓋提示由系統負責。
 */
class ClipboardExecutor(
    private val bridge: ClipboardBridge,
    private val maxChars: Int = ClipboardTools.MAX_CHARS,
) {
    fun read(isForeground: Boolean, enabled: Boolean = true): ClipboardResult {
        when (val c = ClipboardTools.checkRead(isForeground, enabled)) {
            is ClipboardTools.Check.Denied ->
                return ClipboardResult(false, reason = c.reason, detail = c.detail, message = c.message)
            is ClipboardTools.Check.Invalid ->
                return ClipboardResult(false, detail = c.detail, message = c.message)
            is ClipboardTools.Check.Allowed -> Unit
        }
        val raw = try {
            bridge.readText()?.toString()
        } catch (e: Exception) {
            return ClipboardResult(false, detail = "BRIDGE_FAILED", message = "讀取剪貼簿失敗：${e.message}")
        }
        if (raw.isNullOrEmpty()) {
            return ClipboardResult(true, text = "", message = "剪貼簿為空")
        }
        return if (raw.length > maxChars) {
            ClipboardResult(
                ok = true,
                text = raw.take(maxChars),
                truncated = true,
                message = "剪貼簿內容過長，已截斷為前 $maxChars 字",
            )
        } else {
            ClipboardResult(true, text = raw)
        }
    }

    fun write(
        text: CharSequence,
        isForeground: Boolean,
        label: String = "LibrePocket",
        enabled: Boolean = true,
    ): ClipboardResult {
        when (val c = ClipboardTools.checkWrite(text, isForeground, enabled)) {
            is ClipboardTools.Check.Denied ->
                return ClipboardResult(false, reason = c.reason, detail = c.detail, message = c.message)
            is ClipboardTools.Check.Invalid ->
                return ClipboardResult(false, detail = c.detail, message = c.message)
            is ClipboardTools.Check.Allowed -> Unit
        }
        return try {
            bridge.writeText(label, text)
            ClipboardResult(true, message = "已寫入剪貼簿（${text.length} 字）")
        } catch (e: Exception) {
            ClipboardResult(false, detail = "BRIDGE_FAILED", message = "寫入剪貼簿失敗：${e.message}")
        }
    }
}
