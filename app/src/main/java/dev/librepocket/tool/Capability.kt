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
 *
 * Stage1 (P2 safe slice): [NEED_CLARIFICATION] is for FastRouter
 * guess-rejection (missing alarm time / missing phone number / empty
 * sms-calendar templates / negated intents). It is deliberately distinct
 * from [USER_DISABLED] (switch-off or not-wired this round) so audit can
 * tell "needs more info" apart from "disabled".
 *
 * 命名空間註記：本枚舉與 `dev.librepocket.systema.ReasonCodes`
 *（A 組快通道的字串原因碼）同名但分屬不同命名空間（typed enum vs String
 * 常量），審計字串相同但程式內絕不跨命名空間比較；linux 門禁（`ProotExec`／
 * `DecompileAnalyze` 的窮舉分支）永不產生本碼，僅為編譯窮舉 fail-closed。
 */
enum class DenyReason {
    FLAVOR_BLOCKED,
    NO_PRIVILEGE,
    USER_DISABLED,
    NEED_CLARIFICATION,
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
 * @param isForeground whether the app is currently in the foreground.
 *   Foreground-only tools (e.g. clipboard read/write, restricted by the
 *   platform on Android 10+) project UNAVAILABLE when this is false.
 */
data class ProjectionContext(
    val flavor: Flavor = Flavor.PLAY,
    val automationEnabled: Boolean = false,
    val userSwitches: Map<String, Boolean> = emptyMap(),
    val grantedPermissions: Set<String> = emptySet(),
    val isForeground: Boolean = true,
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
