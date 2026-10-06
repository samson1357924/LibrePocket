package dev.librepocket.router

import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Projection
import dev.librepocket.tool.SideEffect
import dev.librepocket.tool.ToolDef

/**
 * Authorization gate for fast-lane execution (ARCHITECTURE §5, MATRIX §4).
 *
 * - [SideEffect.PRIVILEGED] tools (DIAL prefill, SMS prefill, screenshot,
 *   restricted `shell.exec`) never execute without an explicit user
 *   confirmation ([NeedConfirm]).
 * - S1-C: notification title-only stays [SideEffect.READ], but a
 *   `fullText` request on either notification name is always PRIVILEGED: it needs the listener grant
 *   plus a second consent ([check] with args double-gates it to
 *   [NeedConfirm] until confirmed).
 * - [SideEffect.WRITE] tools are allowed here; their one-time in-app
 *   confirmation ("rememberable") is tracked by the caller, not this gate.
 * - Anything projected UNAVAILABLE is [Denied] with the projection reason.
 */
sealed interface GateResult {
    data object Allowed : GateResult
    data class NeedConfirm(val toolName: String) : GateResult
    data class Denied(val reason: DenyReason) : GateResult
}

object PrivilegeGate {
    /** ToolRegistry name of the notification reader (title READ, fullText PRIVILEGED). */
    const val NOTIFICATION_TOOL = "notification.read"

    /** Full-text gate covers both the ToolRegistry and SystemB notification names. */
    val NOTIFICATION_TOOLS: Set<String> = setOf(NOTIFICATION_TOOL, "systemb.notification.titles")

    fun check(tool: ToolDef, confirmed: Boolean, projection: Projection): GateResult {
        require(projection.toolName == tool.name) {
            "projection is for ${projection.toolName}, not ${tool.name}"
        }
        if (projection.level == CapabilityLevel.UNAVAILABLE) {
            return GateResult.Denied(projection.reason ?: DenyReason.USER_DISABLED)
        }
        if (tool.sideEffect == SideEffect.PRIVILEGED && !confirmed) {
            return GateResult.NeedConfirm(tool.name)
        }
        return GateResult.Allowed
    }

    /**
     * S1-C args-aware gate: either notification name with `fullText=true`
     * is always PRIVILEGED (listener grant is checked by projection;
     * second consent + explicit confirmation are gated here).
     * Unconsented full text returns [NeedConfirm], never a silent
     * title-only downgrade. All other tools fall through to [check].
     */
    fun check(
        tool: ToolDef,
        confirmed: Boolean,
        projection: Projection,
        args: Map<String, String>,
    ): GateResult {
        require(projection.toolName == tool.name) {
            "projection is for ${projection.toolName}, not ${tool.name}"
        }
        if (projection.level == CapabilityLevel.UNAVAILABLE) {
            return GateResult.Denied(projection.reason ?: DenyReason.USER_DISABLED)
        }
        if (tool.name in NOTIFICATION_TOOLS && isFullTextRequest(args) && !confirmed) {
            return GateResult.NeedConfirm(tool.name)
        }
        return check(tool, confirmed, projection)
    }

    /** `fullText=true` (or `1`) requests the privileged body path. */
    fun isFullTextRequest(args: Map<String, String>): Boolean {
        val v = args["fullText"]?.trim()?.lowercase() ?: return false
        return v == "true" || v == "1"
    }

    /** JSON-args variant for FastRouter `argumentsJson` (tolerates `"true"`/`1`；詞界+結尾錨定避免 `10`/`truex` 誤判）。 */
    fun isFullTextRequestJson(argumentsJson: String): Boolean =
        Regex(""""fullText"\s*:\s*(true|"true"|1)(?=\s*[,}\]])""").containsMatchIn(argumentsJson)

    fun canExecute(tool: ToolDef, confirmed: Boolean, projection: Projection): Boolean =
        check(tool, confirmed, projection) is GateResult.Allowed

    fun canExecute(
        tool: ToolDef,
        confirmed: Boolean,
        projection: Projection,
        args: Map<String, String>,
    ): Boolean = check(tool, confirmed, projection, args) is GateResult.Allowed
}
