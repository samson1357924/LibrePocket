package dev.librepocket.slow

/**
 * Single GUI steps the slow loop may propose (ARCHITECTURE §9.2).
 * Coordinates are screen pixels captured at proposal time; the
 * full-flavor executor re-validates them before acting.
 */
sealed interface SlowAction {
    data class Tap(val nodeId: String) : SlowAction
    data class Swipe(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int) : SlowAction
    data class Input(val nodeId: String, val text: String) : SlowAction
    data object Back : SlowAction
    data object Home : SlowAction
}

/** One proposed atomic step with its human-readable justification. */
data class StepProposal(
    val action: SlowAction,
    val targetDescription: String = "",
    val rationale: String = "",
    /** Planner self-estimate in 0..1; informational only. */
    val confidence: Float = 0f,
)

/** Arbitration outcome for one proposal (BACKLOG B5). */
enum class SlowRisk {
    NORMAL,
    NEEDS_CONFIRM,
}

/**
 * Minimal in-core arbitration predicate: payment / deletion / outbound
 * submission steps always require explicit user confirmation before the
 * executor runs. Pure function, no platform dependencies; the confirm
 * UI itself is wired by the full-flavor workstream.
 */
object SlowArbitrator {
    private val SENSITIVE_HINTS = listOf(
        // Payment / funds movement.
        "支付", "付款", "轉帳", "转账", "扣款", "下單", "下单", "結帳", "结账",
        "pay", "payment", "checkout", "transfer",
        // Deletion / destructive steps.
        "刪除", "删除", "移除帳號", "移除账号", "清空", "註銷", "注销",
        "delete", "remove account",
        // Outbound submission.
        "發送", "发送", "送出", "提交訂單", "提交订单", "送出表單", "送出表单",
        "send", "submit order",
    )

    fun assess(goal: String, proposal: StepProposal): SlowRisk {
        val haystack = (goal + "\n" + proposal.targetDescription + "\n" + proposal.rationale)
            .lowercase()
        val hit = SENSITIVE_HINTS.any { it.lowercase() in haystack }
        return if (hit) SlowRisk.NEEDS_CONFIRM else SlowRisk.NORMAL
    }
}
