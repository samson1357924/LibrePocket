package dev.librepocket.tool

/**
 * Static annotations for one tool: how it executes and where it degrades.
 *
 * @param intentAction Android intent action used for hand-off, if any.
 * @param timeoutMs per-call timeout budget in milliseconds.
 * @param fallbackHint manual alternative quoted in downgrade replies.
 * @param requiresSwitch user-toggle key guarding this tool, or null.
 * @param switchDefault value when the key is absent from [ProjectionContext].
 * @param requiresPermission runtime/system grant needed, or null when the
 *   tool needs none (e.g. SMS prefill deliberately requests no SMS
 *   permission; DIAL needs no CALL_PHONE permission).
 * @param degradedWithoutPermission when true, a missing permission yields
 *   [CapabilityLevel.DEGRADED] (partial scope) instead of UNAVAILABLE.
 */
data class ToolAnnotations(
    val intentAction: String? = null,
    val timeoutMs: Long = 10_000L,
    val fallbackHint: String = "",
    val requiresSwitch: String? = null,
    val switchDefault: Boolean = true,
    val requiresPermission: String? = null,
    val degradedWithoutPermission: Boolean = false,
)

/**
 * One callable tool (ARCHITECTURE §5.1).
 *
 * The projection predicate ([project]) is a pure function of flavor,
 * user switches and granted permissions (ARCHITECTURE §6.3): identical
 * input always yields identical output.
 */
data class ToolDef(
    val name: String,
    val description: String,
    /** JSON Schema (as text) for the tool arguments. */
    val jsonSchema: String,
    val sideEffect: SideEffect,
    val annotations: ToolAnnotations = ToolAnnotations(),
    val supportedFlavors: Set<Flavor> = setOf(Flavor.PLAY, Flavor.FULL),
) {
    fun project(ctx: ProjectionContext): Projection {
        if (ctx.flavor !in supportedFlavors) {
            return Projection(name, CapabilityLevel.UNAVAILABLE, DenyReason.FLAVOR_BLOCKED)
        }
        val switchKey = annotations.requiresSwitch
        if (switchKey == SWITCH_AUTOMATION) {
            if (!ctx.automationEnabled) {
                return Projection(name, CapabilityLevel.UNAVAILABLE, DenyReason.USER_DISABLED)
            }
        } else if (switchKey != null && !ctx.switchOn(switchKey, annotations.switchDefault)) {
            return Projection(name, CapabilityLevel.UNAVAILABLE, DenyReason.USER_DISABLED)
        }
        val permission = annotations.requiresPermission
        if (permission != null && permission !in ctx.grantedPermissions) {
            return if (annotations.degradedWithoutPermission) {
                Projection(name, CapabilityLevel.DEGRADED, DenyReason.NO_PRIVILEGE)
            } else {
                Projection(name, CapabilityLevel.UNAVAILABLE, DenyReason.NO_PRIVILEGE)
            }
        }
        return Projection(name, CapabilityLevel.NATIVE, null)
    }

    companion object {
        /** Sentinel switch key wired to [ProjectionContext.automationEnabled]. */
        const val SWITCH_AUTOMATION = "automation"
    }
}
