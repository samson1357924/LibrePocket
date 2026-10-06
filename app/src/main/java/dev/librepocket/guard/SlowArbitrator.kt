package dev.librepocket.guard

/**
 * 慢通道单步动作种类（P3/ARCH §9.2 原创表述）。
 *
 * 仲裁只看种类 + 文本关键词，不执行任何 Android 调用；
 * 纯 Kotlin，可在 JVM 单测中直接断言。
 */
enum class SlowActionKind {
    OBSERVE,
    TAP,
    SCROLL,
    INPUT,
    BACK,
    SEND,
    DELETE,
    PAYMENT,
    UNKNOWN,
}

/** 仲裁理由码：对外可见，可直接写入转录/审计，不含敏感原文。 */
enum class ArbitrationCode {
    PAYMENT,
    DELETE,
    SEND,
    OUT_OF_SCOPE,
    UNKNOWN_ACTION,
}

/**
 * 待仲裁的单步提议。[goalText]/[targetText] 仅用于关键词匹配，
 * 绝不写入审计表（见 [SlowAuditLog]）。
 */
data class SlowProposal(
    val id: String,
    val kind: SlowActionKind,
    val goalText: String,
    val targetText: String = "",
)

/** 仲裁结论：放行 / 需二次确认 / 拒绝。非 [Allow] 一律视为已拦截。 */
sealed interface ArbitrationVerdict {
    val stepId: String
    val codes: List<ArbitrationCode>

    data class Allow(override val stepId: String) : ArbitrationVerdict {
        override val codes: List<ArbitrationCode> = emptyList()
    }

    data class NeedConfirm(
        override val stepId: String,
        override val codes: List<ArbitrationCode>,
    ) : ArbitrationVerdict

    data class Deny(
        override val stepId: String,
        override val codes: List<ArbitrationCode>,
    ) : ArbitrationVerdict
}

/**
 * 慢通道仲裁器（BACKLOG B5，ARCH §9.2）：
 * 支付 / 删除 / 发送类强制 [ArbitrationVerdict.NeedConfirm]，
 * 越界种类（不在 [allowedKinds] 内）一律 [ArbitrationVerdict.Deny]。
 *
 * 红队基线：转账 / 删对话无论措辞如何都必须被拦截（≠ Allow）。
 */
object SlowArbitrator {

    /** 默认允许的低风险种类；SEND/DELETE/PAYMENT/UNKNOWN 永远不在其中。 */
    val DEFAULT_ALLOWED: Set<SlowActionKind> = setOf(
        SlowActionKind.OBSERVE,
        SlowActionKind.TAP,
        SlowActionKind.SCROLL,
        SlowActionKind.INPUT,
        SlowActionKind.BACK,
    )

    private val PAYMENT_HINTS = listOf(
        "转账", "转帐", "轉帳", "轉賬", "支付", "付款", "汇款", "匯款",
        "银行卡", "信用卡", "红包", "收付款", "提现", "提現",
        "pay", "transfer", "payment",
    )
    private val DELETE_HINTS = listOf(
        "删除", "刪除", "删掉", "刪掉", "删对话", "刪對話", "删除对话",
        "清空", "清除记录", "清除紀錄", "delete", "remove conversation",
        "clear history",
    )
    private val SEND_HINTS = listOf(
        "发送", "發送", "寄出", "送出", "分享", "发布", "發佈", "公开发布",
        "提交订单", "提交訂單", "提交", "订单", "訂單", "下单", "送出表单",
        "send", "share", "post", "submit",
    )

    fun arbitrate(
        proposal: SlowProposal,
        allowedKinds: Set<SlowActionKind> = DEFAULT_ALLOWED,
    ): ArbitrationVerdict {
        require(proposal.id.isNotBlank()) { "proposal id must not be blank" }
        val haystack = "${proposal.goalText} ${proposal.targetText}".lowercase()

        val sensitive = LinkedHashSet<ArbitrationCode>()
        if (proposal.kind == SlowActionKind.PAYMENT || containsAny(haystack, PAYMENT_HINTS)) {
            sensitive += ArbitrationCode.PAYMENT
        }
        if (proposal.kind == SlowActionKind.DELETE || containsAny(haystack, DELETE_HINTS)) {
            sensitive += ArbitrationCode.DELETE
        }
        if (proposal.kind == SlowActionKind.SEND || containsAny(haystack, SEND_HINTS)) {
            sensitive += ArbitrationCode.SEND
        }

        if (proposal.kind == SlowActionKind.UNKNOWN) {
            return ArbitrationVerdict.Deny(
                proposal.id,
                (sensitive + ArbitrationCode.UNKNOWN_ACTION).toList(),
            )
        }
        // 敏感类优先强制 CONFIRM：即使种类同时越界，也先以 NeedConfirm
        // 拦截（并附带 OUT_OF_SCOPE），保证“支付/删除/发送类强制 CONFIRM”。
        val outOfScope = proposal.kind !in allowedKinds
        if (sensitive.isNotEmpty()) {
            val codes = if (outOfScope) {
                (sensitive + ArbitrationCode.OUT_OF_SCOPE).toList()
            } else {
                sensitive.toList()
            }
            return ArbitrationVerdict.NeedConfirm(proposal.id, codes)
        }
        if (outOfScope) {
            return ArbitrationVerdict.Deny(
                proposal.id,
                listOf(ArbitrationCode.OUT_OF_SCOPE),
            )
        }
        return ArbitrationVerdict.Allow(proposal.id)
    }

    /** 是否被拦截（NeedConfirm 或 Deny 都算拦截；只有 Allow 算放行）。 */
    fun isIntercepted(verdict: ArbitrationVerdict): Boolean = verdict !is ArbitrationVerdict.Allow

    private fun containsAny(haystack: String, hints: List<String>): Boolean =
        hints.any { it.lowercase() in haystack }
}
