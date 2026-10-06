package dev.librepocket.mcp

import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Projection
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.SideEffect
import dev.librepocket.tool.ToolAnnotations
import dev.librepocket.tool.ToolDef

/**
 * D03 第三方工具範圍門（投影 + 確認），與內建快工具同鏈路（ARCH §5/§6/§10.1）。
 *
 * 規則（原創，BACKLOG D03「再投影，預設 WRITE」）：
 * 1. 預設副作用 [SideEffect.WRITE]：除非伺服器另行聲明，第三方工具一律按
 *    WRITE 對待（比內建 WRITE 更嚴：內建 WRITE 在 [dev.librepocket.router.PrivilegeGate]
 *    可直接放行，第三方 WRITE 必須確認——見 [check]）。
 * 2. 投影（[project] 純函數）：伺服器主開關關閉，或該工具逐項關閉
 *    （[McpServerConfig.enabledTools] / [ProjectionContext.userSwitches]
 *    的 `mcp.<serverId>.<tool>` 覆寫），一律 UNAVAILABLE + USER_DISABLED；
 *    開啟即 NATIVE（MCP 只需網路權限，無風味封鎖）。
 * 3. 確認（[check]）：投影不可用一律 [McpGate.Denied]；READ 無需確認即可
 *    [McpGate.Allowed]；WRITE / PRIVILEGED 未確認一律 [McpGate.NeedConfirm]。
 *
 * 開關 key 形狀：`mcp.<serverId>.<toolName>`（例如 `mcp.files.search`）。
 */
object McpScope {

    const val SWITCH_PREFIX = "mcp."

    fun switchKey(serverId: String, toolName: String): String =
        "$SWITCH_PREFIX$serverId.$toolName"

    /** 第三方工具轉內建 [ToolDef] 視圖（預設 WRITE），便於複用 Schema/轉錄鏈路。 */
    fun toToolDef(serverId: String, toolName: String, sideEffect: SideEffect = SideEffect.WRITE): ToolDef =
        ToolDef(
            name = "mcp.$serverId.$toolName",
            description = "Third-party MCP tool $toolName on server $serverId (default WRITE).",
            jsonSchema = """{"type": "object"}""",
            sideEffect = sideEffect,
            annotations = ToolAnnotations(
                requiresSwitch = switchKey(serverId, toolName),
                switchDefault = true,
                fallbackHint = "ask the user to enable or confirm the MCP tool manually",
            ),
        )

    /** 純函數投影：相同輸入必得相同輸出。 */
    fun project(
        config: McpServerConfig,
        toolName: String,
        ctx: ProjectionContext = ProjectionContext(),
        sideEffect: SideEffect = SideEffect.WRITE,
    ): Projection {
        val qualified = "mcp.${config.id}.$toolName"
        if (!config.enabled || !config.isToolEnabled(toolName)) {
            return Projection(qualified, CapabilityLevel.UNAVAILABLE, DenyReason.USER_DISABLED)
        }
        if (!ctx.switchOn(switchKey(config.id, toolName), true)) {
            return Projection(qualified, CapabilityLevel.UNAVAILABLE, DenyReason.USER_DISABLED)
        }
        return Projection(qualified, CapabilityLevel.NATIVE, null)
    }

    fun check(
        config: McpServerConfig,
        toolName: String,
        confirmed: Boolean,
        projection: Projection,
        sideEffect: SideEffect = SideEffect.WRITE,
    ): McpGate {
        val qualified = "mcp.${config.id}.$toolName"
        require(projection.toolName == qualified) {
            "projection is for ${projection.toolName}, not $qualified"
        }
        if (projection.level == CapabilityLevel.UNAVAILABLE) {
            return McpGate.Denied(projection.reason ?: DenyReason.USER_DISABLED)
        }
        // 第三方收緊：WRITE 也要確認（內建 WRITE 在 PrivilegeGate 可直行）。
        if ((sideEffect == SideEffect.WRITE || sideEffect == SideEffect.PRIVILEGED) && !confirmed) {
            return McpGate.NeedConfirm(qualified)
        }
        return McpGate.Allowed
    }

    fun canExecute(
        config: McpServerConfig,
        toolName: String,
        confirmed: Boolean,
        projection: Projection,
        sideEffect: SideEffect = SideEffect.WRITE,
    ): Boolean = check(config, toolName, confirmed, projection, sideEffect) is McpGate.Allowed
}

/** MCP 授權結果（鏡像 PrivilegeGate 形狀，但 WRITE 亦需確認）。 */
sealed interface McpGate {
    data object Allowed : McpGate
    data class NeedConfirm(val toolName: String) : McpGate
    data class Denied(val reason: DenyReason) : McpGate
}
