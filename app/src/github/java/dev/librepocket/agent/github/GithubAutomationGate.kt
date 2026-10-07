package dev.librepocket.agent.github

import dev.librepocket.guard.AutomationPolicy
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.ToolRegistry

/**
 * GitHub 风味自动化接线（BACKLOG B5/B8，MATRIX §1/§2 原创实现）。
 *
 * - 本文件只存在于 `src/github`，play 构建物理缺失；
 * - 总开关默认关（[DEFAULT_ENABLED] = false，与 [AutomationPolicy] 同源）；
 * - 两步同意：App 内开关 + 系统无障碍授权 + 当轮二次确认；
 * - 可见性唯一经由 [ToolRegistry.projectAll] 计算，不手写白名单。
 */
object GithubAutomationGate {

    const val SWITCH_KEY: String = AutomationPolicy.SWITCH_KEY

    /** 默认关：新安装/清数据后 dump 此值必为 false。 */
    const val DEFAULT_ENABLED: Boolean = AutomationPolicy.DEFAULT_ENABLED

    fun effectiveAutomation(
        switchOn: Boolean = DEFAULT_ENABLED,
        serviceGranted: Boolean = false,
        userConfirmed: Boolean = false,
    ): Boolean = AutomationPolicy.effectiveAutomation(
        flavor = Flavor.GITHUB,
        switchOn = switchOn,
        serviceGranted = serviceGranted,
        userConfirmed = userConfirmed,
    )

    fun projectionContext(
        switchOn: Boolean = DEFAULT_ENABLED,
        serviceGranted: Boolean = false,
        userConfirmed: Boolean = false,
        grantedPermissions: Set<String> = emptySet(),
        userSwitches: Map<String, Boolean> = emptyMap(),
    ): ProjectionContext = ProjectionContext(
        flavor = Flavor.GITHUB,
        automationEnabled = effectiveAutomation(switchOn, serviceGranted, userConfirmed),
        grantedPermissions = grantedPermissions,
        userSwitches = userSwitches,
    )

    /** 慢通道 `gui.automate` 本輪投影是否通過門禁（投影語義；非 model 可執行集）。 */
    fun isSlowVisible(
        switchOn: Boolean = DEFAULT_ENABLED,
        serviceGranted: Boolean = false,
        userConfirmed: Boolean = false,
    ): Boolean {
        val ctx = projectionContext(switchOn, serviceGranted, userConfirmed)
        // 門禁看投影（projectedTools），非 model 可執行集：gui.automate 尚無
        // StepExecutor 接線（executionReady=false），故 isSlowVisible 為投影語義。
        return ToolRegistry.projectedTools(ctx).any { it.name == ToolRegistry.SLOW_TOOL_NAME }
    }
}
