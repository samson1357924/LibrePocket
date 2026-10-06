package dev.librepocket.router

import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Projection
import dev.librepocket.tool.SideEffect
import dev.librepocket.tool.ToolDef

/**
 * Authorization gate for fast-lane execution (ARCHITECTURE §5, MATRIX §4).
 *
 * - [SideEffect.PRIVILEGED] tools (DIAL prefill, SMS prefill, screenshot)
 *   never execute without an explicit user confirmation ([NeedConfirm]).
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

    fun canExecute(tool: ToolDef, confirmed: Boolean, projection: Projection): Boolean =
        check(tool, confirmed, projection) is GateResult.Allowed
}
