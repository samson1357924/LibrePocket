package dev.librepocket.agent.foss

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Foss-flavor 占位。声明使合并后的 foss manifest 合法；
 * 刻意不实现任何行为，真实功能属未来工作。
 *
 * 接线约束（B5/B8）：本类只存在于 `src/foss`（play 物理缺失）；
 * 是否“已武装”唯一由 [FossAutomationGate.effectiveAutomation] 判定，
 * 默认关（switch=false），且 onAccessibilityEvent 保持空实现，
 * 不读取窗口内容、不执行点击（仲裁/执行属 SlowRouter 未来工作）。
 */
class FossAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        /** 与总开关同源的默认关常量，供设置页 dump 举证。 */
        const val DEFAULT_ENABLED: Boolean = FossAutomationGate.DEFAULT_ENABLED
    }
}
