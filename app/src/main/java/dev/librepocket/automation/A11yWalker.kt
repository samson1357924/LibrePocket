package dev.librepocket.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 無障礙節點快照 Walker（S3，BACKLOG B5/B8，純 framework API）。
 *
 * foss/github 兩風味的 a11y 服務共用同一快照語義（play 物理缺失）；
 * 本檔案只用 `android.view.accessibility` + `android.graphics`，
 * 不引用任何專有依賴，foss 側引用不污染其純 OSS 屬性。
 *
 * - 廣度優先 walk（首屏穩定序），原始節點上限 [maxRaw]，超出丟棄；
 * - 類名 → kind：Button/EditText/TextView/ImageView 等取小寫短名，
 *   未知容器記 `container`；
 * - 可編輯 = [AccessibilityNodeInfo.isEditable] 或類名含 EditText；
 * - 中心點由 [AccessibilityNodeInfo.getBoundsInScreen] 換算；
 * - 本層不脫敏：呼叫方經 [AutomationCore.compressNodes]（內含
 *   [dev.librepocket.redact.Redactor]）再壓縮，原文不出本機管線。
 */
object A11yWalker {
    fun snapshot(
        root: AccessibilityNodeInfo?,
        maxRaw: Int = 256,
    ): List<A11yNode> {
        if (root == null || maxRaw <= 0) return emptyList()
        val out = ArrayList<A11yNode>(minOf(maxRaw, 64))
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val bounds = Rect()
        var index = 0
        try {
            while (queue.isNotEmpty() && out.size < maxRaw) {
                val node = queue.removeFirst()
                try {
                    val className = node.className?.toString().orEmpty()
                    node.getBoundsInScreen(bounds)
                    val hasBounds = !bounds.isEmpty
                    out.add(
                        A11yNode(
                            id = "n$index",
                            kind = kindOf(className),
                            text = node.text?.toString().orEmpty(),
                            contentDescription = node.contentDescription?.toString().orEmpty(),
                            clickable = node.isClickable,
                            editable = node.isEditable || "EditText" in className,
                            centerX = if (hasBounds) bounds.centerX() else null,
                            centerY = if (hasBounds) bounds.centerY() else null,
                        ),
                    )
                    index++
                    if (out.size >= maxRaw) break
                    for (i in 0 until node.childCount) {
                        if (out.size + queue.size >= maxRaw) break
                        node.getChild(i)?.let { queue.add(it) } ?: Unit
                    }
                } finally {
                    if (node !== root) {
                        try {
                            node.recycle()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        } finally {
            while (queue.isNotEmpty()) {
                runCatching { queue.removeFirst().recycle() }
            }
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
        return out
    }

    /** 類名 → 精簡 kind（原始表述，供壓縮行與轉錄消費）。 */
    fun kindOf(className: String): String {
        val simple = className.substringAfterLast('.').lowercase()
        return when {
            "button" in simple -> "button"
            "edittext" in simple -> "input"
            "textview" in simple -> "text"
            "imageview" in simple || "image" in simple -> "image"
            "checkbox" in simple -> "checkbox"
            "switch" in simple -> "switch"
            "seekbar" in simple || "slider" in simple -> "slider"
            "listview" in simple || "recyclerview" in simple || "gridview" in simple -> "list"
            "scrollview" in simple -> "scroll"
            simple.isEmpty() -> "container"
            else -> "container"
        }
    }
}
