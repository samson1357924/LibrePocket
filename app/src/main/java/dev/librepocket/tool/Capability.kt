package dev.librepocket.tool

/** Build flavor: store-safe (`play`) vs self-installed (`foss`, fully
 * open-source; `github`, direct download with proprietary services). */
enum class Flavor {
    PLAY,
    FOSS,
    GITHUB,
}

/** Per-tool availability for this round (ARCHITECTURE §6.3). */
enum class CapabilityLevel {
    NATIVE,
    DEGRADED,
    UNAVAILABLE,
}

/**
 * Why a tool is degraded/unavailable. Codes are user-visible: downgrade
 * replies must quote them verbatim (CAPABILITY_MATRIX §5).
 */
enum class DenyReason {
    FLAVOR_BLOCKED,
    NO_PRIVILEGE,
    USER_DISABLED,
}

/**
 * Inputs to the capability projection pure function (ARCHITECTURE §6.2).
 *
 * @param flavor compile flavor.
 * @param automationEnabled master switch for screen automation (foss/github only).
 * @param userSwitches per-category user toggles, e.g. "screenshot" to false.
 *   Missing keys fall back to each tool's declared default.
 * @param grantedPermissions runtime/system grants already held, e.g.
 *   "android.permission.READ_CALENDAR" or the pseudo grant
 *   "listener:notification".
 */
data class ProjectionContext(
    val flavor: Flavor = Flavor.PLAY,
    val automationEnabled: Boolean = false,
    val userSwitches: Map<String, Boolean> = emptyMap(),
    val grantedPermissions: Set<String> = emptySet(),
) {
    fun switchOn(key: String, default: Boolean): Boolean =
        userSwitches.getOrDefault(key, default)
}

/** Projection outcome for one tool. [reason] explains DEGRADED/UNAVAILABLE. */
data class Projection(
    val toolName: String,
    val level: CapabilityLevel,
    val reason: DenyReason? = null,
)
