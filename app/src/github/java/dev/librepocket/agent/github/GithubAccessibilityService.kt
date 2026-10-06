package dev.librepocket.agent.github

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * GitHub-flavor placeholder. Declared so the merged github manifest is valid;
 * intentionally implements no behavior. Real functionality is future work.
 *
 * 接线约束（B5/B8）：本类只存在于 `src/github`（play 物理缺失）；
 * 是否“已武装”唯一由 [GithubAutomationGate.effectiveAutomation] 判定，
 * 默认关（switch=false），且 onAccessibilityEvent 保持空实现，
 * 不读取窗口内容、不执行点击（仲裁/执行属 SlowRouter 未来工作）。
 */
class GithubAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        /** 与总开关同源的默认关常量，供设置页 dump 举证。 */
        const val DEFAULT_ENABLED: Boolean = GithubAutomationGate.DEFAULT_ENABLED
    }
}
