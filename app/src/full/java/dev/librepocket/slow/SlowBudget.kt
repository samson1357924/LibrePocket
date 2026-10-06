package dev.librepocket.slow

/**
 * Dual budget for the slow loop (ARCHITECTURE §9.2 / §11): a step cap
 * and a wall-clock cap. Whichever trips first stops automation and
 * downgrades to a manual guide instead of retrying forever.
 */
data class SlowBudget(
    val maxSteps: Int = 20,
    val maxTimeMs: Long = 120_000L,
) {
    init {
        require(maxSteps > 0) { "maxSteps must be positive" }
        require(maxTimeMs > 0) { "maxTimeMs must be positive" }
    }
}

/** Why the loop stopped automating and handed over to the user. */
enum class SlowStopReason {
    STEP_BUDGET_EXCEEDED,
    TIME_BUDGET_EXCEEDED,
}

/**
 * Manual hand-over guide produced on budget exhaustion
 * (CAPABILITY_MATRIX §5 phrasing: 做不到 X（原因碼）→ 可替代 Y → 需要你做 Z).
 */
data class ManualGuide(
    val reasonCode: String,
    val goal: String,
    val stepsDone: Int,
    val text: String,
)

/**
 * Budget tracker: pure logic over an injected clock, so tests drive
 * time deterministically. Counts only executed atomic steps.
 */
class SlowBudgetTracker(
    private val budget: SlowBudget,
    private val startTimeMs: Long,
) {
    /** Non-null when automation must stop before the next step. */
    fun checkBeforeStep(nowMs: Long, stepsDone: Int): SlowStopReason? {
        if (stepsDone >= budget.maxSteps) return SlowStopReason.STEP_BUDGET_EXCEEDED
        if (nowMs - startTimeMs >= budget.maxTimeMs) return SlowStopReason.TIME_BUDGET_EXCEEDED
        return null
    }

    fun toManualGuide(reason: SlowStopReason, goal: String, stepsDone: Int): ManualGuide {
        val short = goal.trim().take(48).ifEmpty { "此操作" }
        val text = "做不到自動完成「$short」（${reason.name}，已執行 $stepsDone 步）" +
            "→ 可替代以下手動步驟 → 需要你按步驟手動完成：" +
            "第1步，回到「$short」的起始畫面；" +
            "第2步，對照已完成的 $stepsDone 步，從下一步繼續點選；" +
            "第3步，遇到付款／刪除／發送類畫面時停下確認後再繼續。"
        return ManualGuide(
            reasonCode = reason.name,
            goal = goal,
            stepsDone = stepsDone,
            text = text,
        )
    }
}
