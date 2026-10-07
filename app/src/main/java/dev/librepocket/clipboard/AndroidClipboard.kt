package dev.librepocket.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * S1-A 剪貼簿 Android 落地（main 源集）。
 *
 * - [ClipboardManager] 前台讀寫：不需任何權限聲明（故不碰 Manifest，
 *   不新增 `uses-permission`）。
 * - 背景讀在 Android 10+ 由系統直接回 null；本類不自作聰明，
 *   前台門禁由 [ClipboardTools.checkRead]/[checkWrite] 在呼叫前執行。
 * - 呼叫方請傳 Application context，避免持有 Activity。
 */
class AndroidClipboardBridge(private val appContext: Context) : ClipboardBridge {

    private fun manager(): ClipboardManager =
        appContext.getSystemService(ClipboardManager::class.java)

    override fun readText(): CharSequence? {
        val clip = manager().primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        return clip.getItemAt(0).coerceToText(appContext)?.toString()
    }

    override fun writeText(label: String, text: CharSequence) {
        manager().setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
