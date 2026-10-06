package dev.librepocket.guard

import dev.librepocket.tool.Flavor

/**
 * 自动化总开关策略（BACKLOG B8 + CAPABILITY_MATRIX §2，纯逻辑部分）。
 *
 * - 默认关闭（[DEFAULT_ENABLED] = false），与 foss/github 双风味的
 *   `FossAutomationGate.DEFAULT_ENABLED` / `GithubAutomationGate.DEFAULT_ENABLED`
 *   同源，Dump/单测均可证明；
 * - 两步同意：App 内开关 + 系统无障碍授权 + 当轮二次确认，三者缺一不可；
 * - play 风味永远 false（商店合规，物理无 a11y 服务）；
 * - 本对象零 Android 依赖，供 `src/foss` / `src/github` 的 Android 包装直接委托。
 */
object AutomationPolicy {
    const val SWITCH_KEY = "automation"
    const val DEFAULT_ENABLED = false

    /**
     * @param flavor 编译风味（play 永远 false；foss/github 走三同意门禁）。
     * @param switchOn App 内自动化总开关。
     * @param serviceGranted 系统无障碍服务是否已授权。
     * @param userConfirmed 当轮是否已二次确认。
     */
    fun effectiveAutomation(
        flavor: Flavor = Flavor.PLAY,
        switchOn: Boolean = DEFAULT_ENABLED,
        serviceGranted: Boolean = false,
        userConfirmed: Boolean = false,
    ): Boolean = flavor != Flavor.PLAY && switchOn && serviceGranted && userConfirmed

    /** 模型可见性 = 投影侧 `automationEnabled` 的唯一输入。 */
    fun slowVisibleToModel(effectiveAutomation: Boolean): Boolean = effectiveAutomation
}
