package dev.librepocket.tool

/**
 * Static annotations for one tool: how it executes and where it degrades.
 *
 * @param intentAction Android intent action used for hand-off, if any.
 * @param timeoutMs per-call timeout budget in milliseconds.
 * @param fallbackHint manual alternative quoted in downgrade replies.
 * @param requiresSwitch user-toggle key guarding this tool, or null.
 * @param switchDefault value when the key is absent from [ProjectionContext].
 * @param requiresSwitch2 second user-toggle key that must ALSO be on, or null
 *   when the tool needs only one switch (S2 `voice.speak.azure` needs both
 *   `azure_tts` and `voice_output`; either off yields UNAVAILABLE +
 *   USER_DISABLED, mirroring `AzureSpeechGate`).
 * @param switchDefault2 value when [requiresSwitch2] is absent from
 *   [ProjectionContext].
 * @param requiresPermission runtime/system grant needed, or null when the
 *   tool needs none (e.g. SMS prefill deliberately requests no SMS
 *   permission; DIAL needs no CALL_PHONE permission).
 * @param degradedWithoutPermission when true, a missing permission yields
 *   [CapabilityLevel.DEGRADED] (partial scope) instead of UNAVAILABLE.
 * @param foregroundOnly when true, the tool only works while the app is in
 *   the foreground ([ProjectionContext.isForeground]). Background rounds
 *   project [CapabilityLevel.UNAVAILABLE] + [DenyReason.NO_PRIVILEGE]
 *   (e.g. clipboard access, blocked by the platform for background apps).
 */
data class ToolAnnotations(
    val intentAction: String? = null,
    val timeoutMs: Long = 10_000L,
    val fallbackHint: String = "",
    val requiresSwitch: String? = null,
    val switchDefault: Boolean = true,
    val requiresSwitch2: String? = null,
    val switchDefault2: Boolean = true,
    val requiresPermission: String? = null,
    val degradedWithoutPermission: Boolean = false,
    val foregroundOnly: Boolean = false,
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
    val supportedFlavors: Set<Flavor> = setOf(Flavor.PLAY, Flavor.FOSS, Flavor.GITHUB),
) {
    fun project(ctx: ProjectionContext): Projection {
        if (annotations.foregroundOnly && !ctx.isForeground) {
            return Projection(name, CapabilityLevel.UNAVAILABLE, DenyReason.NO_PRIVILEGE)
        }
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
        // S2 雙開關（voice.speak.azure）：第二開關關閉同樣不可用（USER_DISABLED）。
        val switchKey2 = annotations.requiresSwitch2
        if (switchKey2 != null && !ctx.switchOn(switchKey2, annotations.switchDefault2)) {
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
