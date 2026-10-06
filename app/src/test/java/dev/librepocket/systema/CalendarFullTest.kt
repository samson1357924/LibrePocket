package dev.librepocket.systema

import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.SideEffect
import dev.librepocket.tool.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1-C 日曆補齊測試：query（READ，需 READ_CALENDAR）+
 * update/delete（WRITE，需 WRITE_CALENDAR 或 EDIT 委託）；
 * 開關 `calendar`；沿 CalendarTool 模式（Provider 描述 + 委託優先）。
 */
class CalendarFullTest {

    private val okLauncher = IntentLauncher { true }
    private val env = SystemEnv(hasCalendarApp = true)

    // ---- 註冊 ----

    @Test fun registry_calendarQueryIsReadWithCalendarGrant() {
        val tool = ToolRegistry.find("calendar.query")!!
        assertEquals(SideEffect.READ, tool.sideEffect)
        assertEquals("calendar", tool.annotations.requiresSwitch)
        assertEquals("android.permission.READ_CALENDAR", tool.annotations.requiresPermission)
    }

    @Test fun registry_calendarUpdateDeleteAreWriteWithWriteGrant() {
        for (name in listOf("calendar.update", "calendar.delete")) {
            val tool = ToolRegistry.find(name)!!
            assertEquals(name, SideEffect.WRITE, tool.sideEffect)
            assertEquals(name, "calendar", tool.annotations.requiresSwitch)
            assertEquals(name, "android.permission.WRITE_CALENDAR", tool.annotations.requiresPermission)
        }
        // 保留既有 calendar.create。
        assertEquals(SideEffect.WRITE, ToolRegistry.find("calendar.create")!!.sideEffect)
    }

    @Test fun projection_queryNeedsReadGrant() {
        val noGrant = ToolRegistry.projectAll(ProjectionContext(flavor = Flavor.PLAY))["calendar.query"]!!
        assertEquals(CapabilityLevel.UNAVAILABLE, noGrant.level)
        assertEquals(DenyReason.NO_PRIVILEGE, noGrant.reason)
        val granted = ToolRegistry.projectAll(
            ProjectionContext(
                flavor = Flavor.PLAY,
                grantedPermissions = setOf("android.permission.READ_CALENDAR"),
            ),
        )["calendar.query"]!!
        assertEquals(CapabilityLevel.NATIVE, granted.level)
    }

    @Test fun projection_updateWithoutWriteGrantDegradesInsteadOfVanishing() {
        // EDIT 委託可免寫權限：降級仍可見（DEGRADED），不直接消失。
        val ctx = ProjectionContext(flavor = Flavor.PLAY)
        for (name in listOf("calendar.update", "calendar.delete")) {
            val p = ToolRegistry.projectAll(ctx)[name]!!
            assertEquals(name, CapabilityLevel.DEGRADED, p.level)
            assertTrue(ToolRegistry.visibleTools(ctx).any { it.name == name })
        }
        val granted = ProjectionContext(
            flavor = Flavor.PLAY,
            grantedPermissions = setOf("android.permission.WRITE_CALENDAR"),
        )
        for (name in listOf("calendar.update", "calendar.delete")) {
            assertEquals(name, CapabilityLevel.NATIVE, ToolRegistry.projectAll(granted)[name]!!.level)
        }
    }

    @Test fun projection_calendarSwitchOffHidesAll() {
        val ctx = ProjectionContext(
            flavor = Flavor.PLAY,
            userSwitches = mapOf("calendar" to false),
            grantedPermissions = setOf(
                "android.permission.READ_CALENDAR",
                "android.permission.WRITE_CALENDAR",
            ),
        )
        for (name in listOf("calendar.query", "calendar.update", "calendar.delete", "calendar.create")) {
            val p = ToolRegistry.projectAll(ctx)[name]!!
            assertEquals(name, CapabilityLevel.UNAVAILABLE, p.level)
            assertEquals(name, DenyReason.USER_DISABLED, p.reason)
        }
    }

    // ---- 查詢門禁 ----

    @Test fun queryWithoutGrantNeedsPermission() {
        val c = CalendarTool.checkQuery(env.copy(calendarReadGranted = false))
        assertEquals(Availability.DEGRADED, c.availability)
        assertEquals(ReasonCodes.NEED_PERMISSION, c.reasonCode)
        assertEquals(Availability.NATIVE, CalendarTool.checkQuery(env.copy(calendarReadGranted = true)).availability)
    }

    @Test fun queryDescriptorShape() {
        val (uri, selection) = CalendarTool.buildQueryDescriptor(
            CalendarTool.QueryParams(startMillis = 1000, endMillis = 2000),
        )
        assertTrue(uri.startsWith(CalendarTool.EVENTS_CONTENT_URI))
        assertTrue(selection.contains("dtstart"))
    }

    // ---- 更新門禁：委託優先，免寫權限 ----

    @Test fun updateWithCalendarAppNeedsNoWriteGrant() {
        val c = CalendarTool.checkUpdate(env.copy(calendarWriteGranted = false))
        assertEquals(Availability.NATIVE, c.availability)
        val spec = CalendarTool.buildEditIntent(
            CalendarTool.UpdateParams(eventId = "42", title = "新標題"),
        )
        assertEquals("android.intent.action.EDIT", spec.action)
        assertEquals("${CalendarTool.EVENTS_CONTENT_URI}/42", spec.dataUri)
        assertEquals("新標題", spec.extras["title"])
    }

    @Test fun updateWithoutAppNeedsWriteGrant() {
        val noApp = env.copy(hasCalendarApp = false)
        // 無 App 時純側不直寫 Provider：一律 DEGRADED + NEED_PERMISSION，
        // 執行回待寫描述（ok=false + usedFallback=true），不斷言 NATIVE。
        val denied = CalendarTool.checkUpdate(noApp.copy(calendarWriteGranted = false))
        assertEquals(Availability.DEGRADED, denied.availability)
        assertEquals(ReasonCodes.NEED_PERMISSION, denied.reasonCode)
        assertEquals(
            Availability.DEGRADED,
            CalendarTool.checkUpdate(noApp.copy(calendarWriteGranted = true)).availability,
        )
        assertEquals(
            ReasonCodes.NEED_PERMISSION,
            CalendarTool.checkUpdate(noApp.copy(calendarWriteGranted = true)).reasonCode,
        )
        assertEquals(
            Availability.DEGRADED,
            CalendarTool.checkDelete(noApp.copy(calendarWriteGranted = true)).availability,
        )
    }

    @Test fun updateDisabledIsUnavailable() {
        val c = CalendarTool.checkUpdate(env.copy(userEnabled = false))
        assertEquals(Availability.UNAVAILABLE, c.availability)
        assertEquals(ReasonCodes.USER_DISABLED, c.reasonCode)
        assertEquals(c, CalendarTool.checkDelete(env.copy(userEnabled = false)))
    }

    @Test fun executeUpdate_delegatesViaLauncher() {
        var launched: IntentSpec? = null
        val r = CalendarTool.executeUpdate(
            CalendarTool.UpdateParams(eventId = "7", title = "例會"),
            env,
            IntentLauncher { launched = it; true },
        )
        assertTrue(r.ok)
        assertEquals("android.intent.action.EDIT", launched?.action)
        assertTrue((launched?.dataUri ?: "").endsWith("/7"))
    }

    @Test fun executeUpdate_withoutApp_returnsPendingNotOk() {
        // BLOCKER-2：無系統日曆 App 時不得假成功；未調 launcher/ContentResolver，
        // 回 ok=false + usedFallback=true + NEED_PERMISSION，spec 攜帶待寫描述。
        for (writeGranted in listOf(false, true)) {
            var launched = 0
            val noApp = env.copy(hasCalendarApp = false, calendarWriteGranted = writeGranted)
            val r = CalendarTool.executeUpdate(
                CalendarTool.UpdateParams(eventId = "7", title = "例會"),
                noApp,
                IntentLauncher { launched++; true },
            )
            assertEquals("writeGranted=$writeGranted", false, r.ok)
            assertEquals("writeGranted=$writeGranted", true, r.usedFallback)
            assertEquals("writeGranted=$writeGranted", ReasonCodes.NEED_PERMISSION, r.reasonCode)
            assertTrue("writeGranted=$writeGranted", r.spec != null)
            assertTrue("writeGranted=$writeGranted", (r.spec!!.dataUri ?: "").endsWith("/7"))
            assertEquals("writeGranted=$writeGranted", "例會", r.spec!!.extras["title"])
            assertEquals("writeGranted=$writeGranted", 0, launched)
        }
    }

    @Test fun executeDelete_withoutApp_returnsPendingNotOk() {
        // BLOCKER-2：刪除同更新，無 App 時僅回待寫描述，不算成功。
        for (writeGranted in listOf(false, true)) {
            var launched = 0
            val noApp = env.copy(hasCalendarApp = false, calendarWriteGranted = writeGranted)
            val r = CalendarTool.executeDelete(
                CalendarTool.DeleteParams("9"),
                noApp,
                IntentLauncher { launched++; true },
            )
            assertEquals("writeGranted=$writeGranted", false, r.ok)
            assertEquals("writeGranted=$writeGranted", true, r.usedFallback)
            assertEquals("writeGranted=$writeGranted", ReasonCodes.NEED_PERMISSION, r.reasonCode)
            assertTrue("writeGranted=$writeGranted", (r.spec?.dataUri ?: "").endsWith("/9"))
            assertEquals("writeGranted=$writeGranted", 0, launched)
        }
    }

    @Test fun executeDelete_delegatesViaLauncher() {
        var launched: IntentSpec? = null
        val r = CalendarTool.executeDelete(
            CalendarTool.DeleteParams("9"),
            env,
            IntentLauncher { launched = it; true },
        )
        assertTrue(r.ok)
        assertTrue((launched?.dataUri ?: "").endsWith("/9"))
    }

    @Test fun deleteDescriptorShape() {
        val (uri, selection) = CalendarTool.buildDeleteDescriptor(CalendarTool.DeleteParams("11"))
        assertEquals("${CalendarTool.EVENTS_CONTENT_URI}/11", uri)
        assertEquals("_id=?", selection)
    }

    @Test(expected = IllegalArgumentException::class)
    fun editRejectsBlankEventId() {
        CalendarTool.buildEditIntent(CalendarTool.UpdateParams(eventId = "  "))
    }

    @Test fun fallbacksQuoteReasonCode() {
        val u = CalendarTool.fallbackUpdate(CalendarTool.UpdateParams("1"), ReasonCodes.NEED_PERMISSION)
        assertEquals(ReasonCodes.NEED_PERMISSION, u.reasonCode)
        assertTrue("（${ReasonCodes.NEED_PERMISSION}）" in u.userMessage)
        val d = CalendarTool.fallbackDelete(CalendarTool.DeleteParams("2"), ReasonCodes.USER_DISABLED)
        assertEquals(ReasonCodes.USER_DISABLED, d.reasonCode)
        assertTrue("（${ReasonCodes.USER_DISABLED}）" in d.userMessage)
    }
}
