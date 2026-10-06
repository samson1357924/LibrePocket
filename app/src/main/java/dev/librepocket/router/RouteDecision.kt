package dev.librepocket.router

import dev.librepocket.tool.DenyReason

/** Which lane served this turn. */
enum class RouteKind {
    FAST,
    DENIED,
}

/** One deterministic tool invocation proposed by the fast lane. */
data class ToolCall(
    val toolName: String,
    val argumentsJson: String,
    val callId: String,
)

/**
 * Transcript header for this turn (ARCHITECTURE §8.1 / §9.3): route
 * decision plus the capability-projection snapshot, so audits can prove
 * what the model saw. [reasonCode] is the [DenyReason] name or null.
 */
data class TranscriptHeader(
    val sessionId: String?,
    val turnIndex: Int?,
    val route: RouteKind,
    val toolName: String?,
    val reasonCode: String?,
    val visibleTools: List<String>,
)

/** Fast-lane outcome: either a [ToolCall] or a denial with fallback text. */
data class RouteDecision(
    val route: RouteKind,
    val toolCall: ToolCall?,
    val reasonCode: DenyReason?,
    val fallbackMessage: String?,
    val header: TranscriptHeader,
)

/**
 * Downgrade phrasing (CAPABILITY_MATRIX §5):
 * `做不到 X（原因碼）→ 可替代 Y → 需要你做 Z`.
 */
object FallbackMessages {
    fun format(
        action: String,
        reason: DenyReason?,
        alternative: String,
        userStep: String,
    ): String = "做不到${action}（${reason?.name ?: "NO_MATCH"}）" +
        "→ 可替代${alternative} → 需要你做${userStep}"

    fun forTool(toolName: String, reason: DenyReason, fallbackHint: String): String =
        format(
            action = toolName,
            reason = reason,
            alternative = fallbackHint,
            userStep = "手動完成或換個說法重試",
        )

    fun forNoMatch(intentText: String): String {
        val short = intentText.trim().take(24)
        return format(
            action = "理解「$short」",
            reason = null,
            alternative = "換個說法（例如指名要開的 App 或要去的地點）",
            userStep = "手動操作或補充細節",
        )
    }
}
