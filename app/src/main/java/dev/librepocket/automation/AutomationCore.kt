package dev.librepocket.automation

import dev.librepocket.guard.ArbitrationCode
import dev.librepocket.guard.SlowActionKind
import dev.librepocket.guard.SlowArbitrator
import dev.librepocket.guard.SlowProposal
import dev.librepocket.redact.Redactor

/**
 * 無障礙自動化共用純核心（S3，BACKLOG B5/B8，零 Android 依賴）。
 *
 * foss/github 兩風味的 a11y 服務（`src/foss` / `src/github`，play 物理缺失）
 * 共用同一套脫敏→壓縮→緊湊 JSON→仲裁前攔截語義，保證兩邊行為一致；
 * 本檔案只依賴 OSS 純函數（[Redactor]、[SlowArbitrator]），foss 側引用
 * 不會污染其純 OSS 屬性。
 *
 * 管線：
 * 1. [compressNodes]：節點快照 → 先 [Redactor] 脫敏 → 語義壓縮
 *    （可操作/帶文本優先，布局容器丟棄，計數與字數設限）；
 * 2. [tapJson]/[swipeJson]/[inputJson]/[backJson]：執行動作的緊湊 JSON
 *    （無空白，供轉錄與執行器消費；input 正文同樣先脫敏）；
 * 3. [needsConfirm]：SlowRouter 仲裁前攔截 —— 支付/刪除/發送類
 *    （關鍵詞或種類命中）強制 CONFIRM，越界種類拒絕；只有 Allow 算放行。
 */
data class A11yNode(
    val id: String,
    val kind: String,
    val text: String = "",
    val contentDescription: String = "",
    val clickable: Boolean = false,
    val editable: Boolean = false,
    /** 觸控中心（平台層換算好的螢幕像素；未知則 null，壓縮行省略座標）。 */
    val centerX: Int? = null,
    val centerY: Int? = null,
)

object AutomationCore {
    const val DEFAULT_MAX_NODES: Int = 40
    const val DEFAULT_MAX_TEXT_LEN: Int = 60

    data class CompressedNodes(
        val lines: List<String>,
        val droppedNodes: Int,
        val truncated: Boolean,
    )

    /**
     * 節點快照 → 脫敏語義壓縮。文本/描述先過 [Redactor]（郵箱/電話/證件等
     * 不進壓縮行），再按「可操作/可編輯 > 帶文本 > 布局容器」排序取前
     * [maxNodes] 個；布局容器（無文本、不可操作）直接計入丟棄。
     */
    fun compressNodes(
        nodes: List<A11yNode>,
        maxNodes: Int = DEFAULT_MAX_NODES,
        maxTextLen: Int = DEFAULT_MAX_TEXT_LEN,
    ): CompressedNodes {
        require(maxNodes > 0) { "maxNodes must be positive" }
        require(maxTextLen > 0) { "maxTextLen must be positive" }

        fun labelOf(node: A11yNode): String {
            val raw = node.text.ifEmpty { node.contentDescription }
            val redacted = Redactor.redact(raw).text
            return if (redacted.length > maxTextLen) redacted.take(maxTextLen) + "…" else redacted
        }

        val ranked = nodes.sortedWith(
            compareByDescending<A11yNode> { it.clickable || it.editable }
                .thenByDescending { it.text.isNotEmpty() || it.contentDescription.isNotEmpty() },
        )
        val kept = mutableListOf<String>()
        var dropped = 0
        for (node in ranked) {
            val meaningful = node.clickable || node.editable ||
                node.text.isNotEmpty() || node.contentDescription.isNotEmpty()
            if (!meaningful) {
                dropped++
                continue
            }
            if (kept.size >= maxNodes) {
                dropped++
                continue
            }
            val marker = when {
                node.editable -> "[input]"
                node.clickable -> "[tap]"
                else -> "[info]"
            }
            val center = if (node.centerX != null && node.centerY != null) {
                " @(${node.centerX},${node.centerY})"
            } else {
                ""
            }
            kept.add("$marker ${node.id} ${node.kind} \"${labelOf(node)}\"$center")
        }
        return CompressedNodes(kept, dropped, dropped > 0)
    }

    /** 緊湊 tap JSON：`{"tap":"<nodeId>"}`（無空白）。 */
    fun tapJson(nodeId: String): String {
        require(nodeId.isNotBlank()) { "nodeId must not be blank" }
        return "{\"tap\":${quote(nodeId)}}"
    }

    /** 緊湊 swipe JSON：`{"swipe":[x1,y1,x2,y2]}`（無空白）。 */
    fun swipeJson(fromX: Int, fromY: Int, toX: Int, toY: Int): String =
        "{\"swipe\":[$fromX,$fromY,$toX,$toY]}"

    /**
     * 緊湊 input JSON：`{"input":{"node":"<id>","text":"<…>"}}`。
     * 正文先 [Redactor] 脫敏再寫入，轉錄側只見遮罩後文本。
     */
    fun inputJson(nodeId: String, text: String): String {
        require(nodeId.isNotBlank()) { "nodeId must not be blank" }
        return "{\"input\":{\"node\":${quote(nodeId)},\"text\":${quote(Redactor.redact(text).text)}}}"
    }

    /** 緊湊 back JSON：`{"back":true}`。 */
    fun backJson(): String = "{\"back\":true}"

    /**
     * 仲裁前攔截：把一步動作映射為 [SlowProposal] 再經 [SlowArbitrator]。
     * 支付/刪除/發送類強制 CONFIRM（回 true）；越界種類拒絕（同樣回 true，
     * 呼叫方一律視為已攔截）；只有 Allow 回 false（可放行）。
     */
    fun needsConfirm(
        action: A11yAction,
        goalText: String,
        targetText: String = "",
        allowedKinds: Set<SlowActionKind> = SlowArbitrator.DEFAULT_ALLOWED,
    ): Boolean {
        val kind = when (action) {
            is A11yAction.Tap -> SlowActionKind.TAP
            is A11yAction.Swipe -> SlowActionKind.SCROLL
            is A11yAction.Input -> SlowActionKind.INPUT
            is A11yAction.Back -> SlowActionKind.BACK
        }
        val verdict = SlowArbitrator.arbitrate(
            SlowProposal(
                id = "a11y-${action.key()}",
                kind = kind,
                goalText = goalText,
                targetText = targetText,
            ),
            allowedKinds = allowedKinds,
        )
        return SlowArbitrator.isIntercepted(verdict)
    }

    /** 攔截理由碼（審計/轉錄用，不含敏感原文）。 */
    fun interceptCodes(
        action: A11yAction,
        goalText: String,
        targetText: String = "",
        allowedKinds: Set<SlowActionKind> = SlowArbitrator.DEFAULT_ALLOWED,
    ): List<ArbitrationCode> {
        val kind = when (action) {
            is A11yAction.Tap -> SlowActionKind.TAP
            is A11yAction.Swipe -> SlowActionKind.SCROLL
            is A11yAction.Input -> SlowActionKind.INPUT
            is A11yAction.Back -> SlowActionKind.BACK
        }
        return SlowArbitrator.arbitrate(
            SlowProposal("a11y-${action.key()}", kind, goalText, targetText),
            allowedKinds,
        ).codes
    }

    private fun A11yAction.key(): String = when (this) {
        is A11yAction.Tap -> "tap-$nodeId"
        is A11yAction.Swipe -> "swipe-$fromX-$fromY-$toX-$toY"
        is A11yAction.Input -> "input-$nodeId"
        is A11yAction.Back -> "back"
    }

    private fun quote(raw: String): String = buildString {
        append('"')
        for (ch in raw) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.isISOControl()) append(String.format("\\u%04x", ch.code)) else append(ch)
            }
        }
        append('"')
    }
}

/** 平台無關的一步動作（a11y 執行器與仲裁共用，JSON 序列化見 [AutomationCore]）。 */
sealed interface A11yAction {
    data class Tap(val nodeId: String) : A11yAction
    data class Swipe(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int) : A11yAction
    data class Input(val nodeId: String, val text: String) : A11yAction
    data object Back : A11yAction
}
