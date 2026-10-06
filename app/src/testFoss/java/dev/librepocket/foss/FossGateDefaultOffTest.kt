package dev.librepocket.foss

import dev.librepocket.agent.foss.FossAccessibilityService
import dev.librepocket.agent.foss.FossAutomationGate
import dev.librepocket.guard.AutomationPolicy
import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Foss automation-gate tests (Phase2, foss flavor only):
 * default-off pinning + slow-channel visibility (PLAY hidden,
 * FOSS visible only when effectively automated).
 */
class FossGateDefaultOffTest {

    @Test fun defaultIsOffAndMatchesPolicy() {
        assertEquals(false, FossAutomationGate.DEFAULT_ENABLED)
        assertEquals(AutomationPolicy.DEFAULT_ENABLED, FossAutomationGate.DEFAULT_ENABLED)
        assertEquals(
            AutomationPolicy.DEFAULT_ENABLED,
            FossAccessibilityService.DEFAULT_ENABLED,
        )
    }

    @Test fun effectiveAutomationDefaultsToOff() {
        assertFalse(FossAutomationGate.effectiveAutomation())
    }

    @Test fun effectiveAutomationNeedsAllThreeConsents() {
        assertFalse(FossAutomationGate.effectiveAutomation(switchOn = false, serviceGranted = true, userConfirmed = true))
        assertFalse(FossAutomationGate.effectiveAutomation(switchOn = true, serviceGranted = false, userConfirmed = true))
        assertFalse(FossAutomationGate.effectiveAutomation(switchOn = true, serviceGranted = true, userConfirmed = false))
        assertTrue(FossAutomationGate.effectiveAutomation(switchOn = true, serviceGranted = true, userConfirmed = true))
    }

    @Test fun playHidesSlowChannel() {
        val ctx = ProjectionContext(flavor = Flavor.PLAY)
        val projected = ToolRegistry.projectAll(ctx)[ToolRegistry.SLOW_TOOL_NAME]!!
        assertEquals(CapabilityLevel.UNAVAILABLE, projected.level)
        assertEquals(DenyReason.FLAVOR_BLOCKED, projected.reason)
        assertFalse(FossAutomationGate.isSlowVisible())
    }

    @Test fun fossShowsSlowOnlyWhenEffectivelyAutomated() {
        assertFalse(
            FossAutomationGate.isSlowVisible(
                switchOn = true,
                serviceGranted = true,
                userConfirmed = false,
            ),
        )
        assertTrue(
            FossAutomationGate.isSlowVisible(
                switchOn = true,
                serviceGranted = true,
                userConfirmed = true,
            ),
        )
    }
}
