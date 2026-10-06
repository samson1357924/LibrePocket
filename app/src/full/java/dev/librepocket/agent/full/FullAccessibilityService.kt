package dev.librepocket.agent.full

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Full-flavor placeholder. Declared so the merged full manifest is valid;
 * intentionally implements no behavior. Real functionality is future work.
 */
class FullAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
