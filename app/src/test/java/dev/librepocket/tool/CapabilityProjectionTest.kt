package dev.librepocket.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B7 capability-projection tests (ARCHITECTURE §6): pure-function
 * projection over flavor + user switches + grants; play hides the
 * slow channel; snapshots feed the transcript header.
 */
class CapabilityProjectionTest {

    private val playBase = ProjectionContext(flavor = Flavor.PLAY)
    private val fossAuto = ProjectionContext(
        flavor = Flavor.FOSS,
        automationEnabled = true,
        grantedPermissions = setOf("android.permission.READ_CALENDAR", "listener:notification"),
    )
    private val githubAuto = ProjectionContext(
        flavor = Flavor.GITHUB,
        automationEnabled = true,
        grantedPermissions = setOf("android.permission.READ_CALENDAR", "listener:notification"),
    )

    @Test fun fastToolsCountIsEleven() {
        assertEquals(11, ToolRegistry.FAST_TOOLS.size)
    }

    @Test fun toolNamesUnique() {
        val names = ToolRegistry.ALL.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test fun playHidesSlowChannel() {
        val projected = ToolRegistry.projectAll(playBase)[ToolRegistry.SLOW_TOOL_NAME]!!
        assertEquals(CapabilityLevel.UNAVAILABLE, projected.level)
        assertEquals(DenyReason.FLAVOR_BLOCKED, projected.reason)
        assertTrue(ToolRegistry.visibleTools(playBase).none { it.name == ToolRegistry.SLOW_TOOL_NAME })
    }

    @Test fun fossWithAutomationShowsSlowChannel() {
        assertTrue(ToolRegistry.visibleTools(fossAuto).any { it.name == ToolRegistry.SLOW_TOOL_NAME })
    }

    @Test fun githubWithAutomationShowsSlowChannel() {
        assertTrue(ToolRegistry.visibleTools(githubAuto).any { it.name == ToolRegistry.SLOW_TOOL_NAME })
    }

    @Test fun fossWithoutAutomationDeniesSlowAsUserDisabled() {
        val ctx = ProjectionContext(flavor = Flavor.FOSS, automationEnabled = false)
        val projected = ToolRegistry.projectAll(ctx)[ToolRegistry.SLOW_TOOL_NAME]!!
        assertEquals(CapabilityLevel.UNAVAILABLE, projected.level)
        assertEquals(DenyReason.USER_DISABLED, projected.reason)
    }

    @Test fun githubWithoutAutomationDeniesSlowAsUserDisabled() {
        val ctx = ProjectionContext(flavor = Flavor.GITHUB, automationEnabled = false)
        val projected = ToolRegistry.projectAll(ctx)[ToolRegistry.SLOW_TOOL_NAME]!!
        assertEquals(CapabilityLevel.UNAVAILABLE, projected.level)
        assertEquals(DenyReason.USER_DISABLED, projected.reason)
    }

    @Test fun playSeesAllElevenFastToolsByDefault() {
        // Calendar needs a grant, notification degrades (still visible): 9 native + 1 degraded.
        val visible = ToolRegistry.visibleTools(
            playBase.copy(grantedPermissions = setOf("android.permission.READ_CALENDAR")),
        ).map { it.name }
        assertEquals(11, visible.size)
        assertTrue(visible.containsAll(ToolRegistry.FAST_TOOLS.map { it.name }))
    }

    @Test fun calendarWithoutGrantIsNoPrivilege() {
        val projected = ToolRegistry.projectAll(playBase)["calendar.create"]!!
        assertEquals(CapabilityLevel.UNAVAILABLE, projected.level)
        assertEquals(DenyReason.NO_PRIVILEGE, projected.reason)
    }

    @Test fun calendarWithGrantIsNative() {
        val ctx = playBase.copy(grantedPermissions = setOf("android.permission.READ_CALENDAR"))
        val projected = ToolRegistry.projectAll(ctx)["calendar.create"]!!
        assertEquals(CapabilityLevel.NATIVE, projected.level)
    }

    @Test fun notificationWithoutGrantDegradesInsteadOfVanishing() {
        val projected = ToolRegistry.projectAll(playBase)["notification.read"]!!
        assertEquals(CapabilityLevel.DEGRADED, projected.level)
        assertTrue(ToolRegistry.visibleTools(playBase).any { it.name == "notification.read" })
    }

    @Test fun userSwitchOffYieldsUserDisabled() {
        val ctx = playBase.copy(userSwitches = mapOf("screenshot" to false))
        val projected = ToolRegistry.projectAll(ctx)["screenshot.capture"]!!
        assertEquals(CapabilityLevel.UNAVAILABLE, projected.level)
        assertEquals(DenyReason.USER_DISABLED, projected.reason)
    }

    @Test fun projectionIsPureFunction() {
        assertEquals(ToolRegistry.projectAll(playBase), ToolRegistry.projectAll(playBase))
    }

    @Test fun schemasLookLikeJsonObjects() {
        for (tool in ToolRegistry.ALL) {
            val s = tool.jsonSchema.trim()
            assertTrue("${tool.name} schema", s.startsWith("{") && s.endsWith("}"))
            assertTrue("${tool.name} schema", "\"type\"" in s && "\"properties\"" in s)
        }
    }

    @Test fun phoneIsDialOnly() {
        val phone = ToolRegistry.find("phone.dial")!!
        assertEquals("android.intent.action.DIAL", phone.annotations.intentAction)
        assertFalse(phone.description.contains("ACTION_CALL"))
        assertFalse(phone.jsonSchema.contains("ACTION_CALL"))
    }

    @Test fun smsRequestsNoSmsPermission() {
        val sms = ToolRegistry.find("sms.compose")!!
        assertEquals(null, sms.annotations.requiresPermission)
        for (tool in ToolRegistry.ALL) {
            val blob = tool.name + tool.description + tool.jsonSchema +
                (tool.annotations.requiresPermission ?: "")
            assertFalse(blob, blob.contains("SEND_SMS") || blob.contains("READ_SMS"))
        }
    }

    @Test fun sideEffectTiersMatchMatrix() {
        assertEquals(SideEffect.READ, ToolRegistry.find("navigate")!!.sideEffect)
        assertEquals(SideEffect.WRITE, ToolRegistry.find("alarm.create")!!.sideEffect)
        assertEquals(SideEffect.WRITE, ToolRegistry.find("calendar.create")!!.sideEffect)
        assertEquals(SideEffect.PRIVILEGED, ToolRegistry.find("phone.dial")!!.sideEffect)
        assertEquals(SideEffect.PRIVILEGED, ToolRegistry.find("sms.compose")!!.sideEffect)
        assertEquals(SideEffect.PRIVILEGED, ToolRegistry.find("screenshot.capture")!!.sideEffect)
    }
}
