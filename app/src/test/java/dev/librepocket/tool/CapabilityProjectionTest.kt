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

    @Test fun fastToolsCountIsEighteen() {
        // P2 11 + S1-C 7（shell.exec + calendar.query/update/delete + contact.search/list/get）
        // + S3 1（shell.elevated，自裝風味提權橋）。
        assertEquals(19, ToolRegistry.FAST_TOOLS.size)
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

    @Test fun playSeesFifteenToolsByDefault() {
        // P2 11（calendar.create 已授權；notification 缺 listener 降級仍可見）
        // + S1-B web.fetch（預設開；websearch/database/lsp 預設關）
        // + S1-C calendar.query（已授權）/ update+delete（缺 WRITE_CALENDAR 時經 EDIT 委託降級，仍可見）；
        // shell.exec（開關預設關）與全量聯繫人（play FLAVOR_BLOCKED）隱藏；
        // S3 shell.elevated（自裝風味 + privilege_bridge 預設關）同樣隱藏。
        // + S1-A 6（clipboard.read/write 前台預設開；file.edit/patch/search/attach 開關 files 預設開）。
        // + S2 2（voice.transcribe / voice.speak 開關預設開；voice.speak.azure 僅 GITHUB，play 隱藏）。
        val visible = ToolRegistry.visibleTools(
            playBase.copy(grantedPermissions = setOf("android.permission.READ_CALENDAR")),
        ).map { it.name }
        assertEquals(23, visible.size)
        assertTrue(
            visible.containsAll(
                ToolRegistry.FAST_TOOLS.map { it.name } -
                    setOf("shell.exec", "shell.elevated", "contact.search", "contact.list", "contact.get"),
            ),
        )
        assertTrue(visible.containsAll(listOf("calendar.query", "calendar.update", "calendar.delete")))
        assertTrue(visible.contains(WebFetch.TOOL_NAME))
        assertTrue(visible.containsAll(ToolRegistry.S1A_TOOLS.map { it.name }))
        assertFalse(visible.contains("shell.exec"))
        assertFalse(visible.contains("shell.elevated"))
        assertFalse(visible.contains("contact.search"))
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

    @Test fun scaffoldToolsHonestlyMarkedUntilWired() {
        // PR#1 re-review P1 scope：無產品 dispatcher 接線的工具必須標 SCAFFOLD，
        // 避免「模型看得到、App 做不到」。已標：voice.speak.azure、linux.boot。
        for (name in listOf(
            "shell.elevated",
            WebFetch.TOOL_NAME,
            DbTools.QUERY_NAME,
            DbTools.EXEC_NAME,
            ToolRegistry.SLOW_TOOL_NAME,
        )) {
            val desc = ToolRegistry.find(name)!!.description
            assertTrue("$name desc=$desc", desc.contains("SCAFFOLD"))
        }
    }
}
