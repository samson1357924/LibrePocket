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

    /**
     * S3 提權工具名（BACKLOG D09，矩陣 §3/§4）。
     * `shell.elevated` 永遠走提權通道（Shizuku/Root，僅自裝風味實作）：
     * 三參 [check] 遇此工具一律 [NeedConfirm]（即使已確認，強制呼叫方
     * 改走四參 args-aware 版）；四參版還要求 reasonCode 非空
     * （見 args-aware [check]），空原因碼同樣只回 [NeedConfirm]，
     * 絕不靜默放行。
     */
    const val ELEVATED_TOOL = "shell.elevated"

    fun check(tool: ToolDef, confirmed: Boolean, projection: Projection): GateResult {
        require(projection.toolName == tool.name) {
            "projection is for ${projection.toolName}, not ${tool.name}"
        }
        if (projection.level == CapabilityLevel.UNAVAILABLE) {
            return GateResult.Denied(projection.reason ?: DenyReason.USER_DISABLED)
        }
        // 提權分支（S3 D09）：三參版遇 ELEVATED_TOOL 一律 NeedConfirm，
        // 無論 confirmed 為何 —— 呼叫方必須改走四參 args-aware 版並攜帶
        // 非空 reasonCode，否則永遠拿不到 Allowed（防三參繞過原因碼門）。
        if (tool.name == ELEVATED_TOOL) {
            return GateResult.NeedConfirm(tool.name)
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
        // 提權分支（args-aware）：原因碼缺席/空白即使用戶已勾確認框也只回
        // NeedConfirm（呼叫方必須補 reasonCode 再走一次確認）。
        // 原因碼有效時落到當輪確認門禁 —— 不得經三參版（其遇提權一律
        // NeedConfirm，防三參繞過原因碼門；此處顯式放行才是唯一的 Allowed 路）。
        if (tool.name == ELEVATED_TOOL) {
            if (!isElevatedRequest(args)) {
                return GateResult.NeedConfirm(tool.name)
            }
            if (!confirmed) {
                return GateResult.NeedConfirm(tool.name)
            }
            return GateResult.Allowed
        }
        if (tool.name in NOTIFICATION_TOOLS && isFullTextRequest(args) && !confirmed) {
            return GateResult.NeedConfirm(tool.name)
        }
        return check(tool, confirmed, projection)
    }

    /**
     * 提權請求是否攜帶有效原因碼（矩陣 §3 審計要求：每次跨權限邊界呼叫
     * 必須攜帶原因碼）。空白/缺席一律視為無效。
     */
    fun isElevatedRequest(args: Map<String, String>): Boolean =
        !args["reasonCode"].isNullOrBlank()

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
