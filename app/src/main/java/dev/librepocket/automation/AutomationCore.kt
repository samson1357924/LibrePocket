package dev.librepocket.automation

import dev.librepocket.guard.ArbitrationCode
import dev.librepocket.guard.ArbitrationVerdict
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
 * 3. [verdictFor]：SlowRouter 仲裁前攔截的正典三態裁決 —— 支付/刪除/發送類
 *    （關鍵詞或種類命中）強制 CONFIRM，越界種類拒絕（Deny，確認不可覆寫）；
 *    只有 Allow 算放行。[needsConfirm]/[interceptCodes] 皆由此衍生。
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
     * 仲裁前攔截的完整裁決（P1 one-shot 確認的判定基礎，PR#1 review blocker）：
     * 把一步動作映射為 [SlowProposal] 再經 [SlowArbitrator]，原樣回傳
     * [ArbitrationVerdict]（Allow / NeedConfirm / Deny 三態，呼叫方可區分
     * 「確認可放行」與「越界永拒」）。[needsConfirm]/[interceptCodes] 皆由此衍生。
     */
    fun verdictFor(
        action: A11yAction,
        goalText: String,
        targetText: String = "",
        allowedKinds: Set<SlowActionKind> = SlowArbitrator.DEFAULT_ALLOWED,
    ): ArbitrationVerdict {
        val kind = when (action) {
            is A11yAction.Tap -> SlowActionKind.TAP
            is A11yAction.Swipe -> SlowActionKind.SCROLL
            is A11yAction.Input -> SlowActionKind.INPUT
            is A11yAction.Back -> SlowActionKind.BACK
        }
        return SlowArbitrator.arbitrate(
            SlowProposal(
                id = "a11y-${action.key()}",
                kind = kind,
                goalText = goalText,
                targetText = targetText,
            ),
            allowedKinds = allowedKinds,
        )
    }

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
        return SlowArbitrator.isIntercepted(verdictFor(action, goalText, targetText, allowedKinds))
    }

    /** 攔截理由碼（審計/轉錄用，不含敏感原文）。 */
    fun interceptCodes(
        action: A11yAction,
        goalText: String,
        targetText: String = "",
        allowedKinds: Set<SlowActionKind> = SlowArbitrator.DEFAULT_ALLOWED,
    ): List<ArbitrationCode> {
        return verdictFor(action, goalText, targetText, allowedKinds).codes
    }

    /**
     * Proposal 指紋（P1 綁定確認，PR#1 re-review + P0 delimiter 封堵）：
     * `a11y-v2:<sha256hex>`，覆蓋種類 + 動作全量（含 Input 全文、Swipe 全座標、
     * Tap nodeId）+ goal/target 規範化（小寫+trim+空白摺疊，與仲裁 haystack
     * 一致）+ allowedKinds 排序集。任一變更即指紋變更，確認必須 exact-match。
     * 純 JVM（MessageDigest），零 Android 依賴。
     *
     * v2 封堵（PR#1 P1）：v1 用 `|` 直接串接，action text / goal / target 含
     * `|` 時欄位邊界可塌縮碰撞（例 Input `x|幫我轉帳` + goal `幫我轉帳` vs
     * Input `x` + goal `幫我轉帳|幫我轉帳` 同 raw）。v2 改長度前綴逐段綴接，
     * 欄位邊界不再依賴分隔符轉義，SHA-256 前即 collision-free。
     */
    fun fingerprintFor(
        action: A11yAction,
        goalText: String,
        targetText: String = "",
        allowedKinds: Set<SlowActionKind> = SlowArbitrator.DEFAULT_ALLOWED,
    ): String {
        val kind = when (action) {
            is A11yAction.Tap -> SlowActionKind.TAP
            is A11yAction.Swipe -> SlowActionKind.SCROLL
            is A11yAction.Input -> SlowActionKind.INPUT
            is A11yAction.Back -> SlowActionKind.BACK
        }
        val actionCanon = when (action) {
            is A11yAction.Tap -> "tap:${action.nodeId}"
            is A11yAction.Swipe -> "swipe:${action.fromX},${action.fromY},${action.toX},${action.toY}"
            is A11yAction.Input -> "input:${action.nodeId}:${action.text}"
            is A11yAction.Back -> "back"
        }
        fun norm(s: String): String = s.trim().lowercase().replace(Regex("\\s+"), " ")
        val allowedSorted = allowedKinds.map { it.name }.sorted().joinToString(",")
        // 長度前綴：len(bytes)+":"+bytes，逐段綴接，避免分隔符注入塌縮。
        fun field(s: String): String {
            val b = s.toByteArray(Charsets.UTF_8)
            return "${b.size}:$s;"
        }
        val raw = buildString {
            append("a11y-v2;")
            append(field(kind.name))
            append(field(actionCanon))
            append(field(norm(goalText)))
            append(field(norm(targetText)))
            append(field(allowedSorted))
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hex = digest.digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") {
            "%02x".format(it)
        }
        return "a11y-v2:$hex"
    }

    private fun A11yAction.key(): String = when (this) {
        is A11yAction.Tap -> "tap-$nodeId"
        is A11yAction.Swipe -> "swipe-$fromX-$fromY-$toX-$toY"
        // P1 修正：Input 納全文雜湊，避免同 node 不同 text 碰撞（見 fingerprintFor）。
        is A11yAction.Input -> {
            val h = java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                .take(12)
            "input-$nodeId-$h"
        }
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
