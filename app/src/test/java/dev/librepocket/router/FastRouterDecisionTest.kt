package dev.librepocket.router

import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FastRouter decision tests (BACKLOG B4, ARCHITECTURE §9.3): template
 * routing, denial reason codes, and transcript-header bookkeeping.
 */
class FastRouterDecisionTest {

    private val router = FastRouter()
    private val play = ProjectionContext(
        flavor = Flavor.PLAY,
        grantedPermissions = setOf("android.permission.READ_CALENDAR"),
    )

    private fun decide(text: String, ctx: ProjectionContext = play): RouteDecision =
        router.decide(text, ctx, sessionId = "s1", turnIndex = 3)

    @Test fun navigateIntentGoesFast() {
        val d = decide("幫我導航到台北車站")
        assertEquals(RouteKind.FAST, d.route)
        assertEquals("navigate", d.toolCall!!.toolName)
        assertNull(d.reasonCode)
        assertNull(d.fallbackMessage)
    }

    @Test fun openAppIntentGoesFast() {
        val d = decide("打開相機")
        assertEquals(RouteKind.FAST, d.route)
        assertEquals("open_app", d.toolCall!!.toolName)
    }

    @Test fun phoneDialIntentGoesFast() {
        val d = decide("打電話給 0912345678")
        assertEquals(RouteKind.FAST, d.route)
        assertEquals("phone.dial", d.toolCall!!.toolName)
        assertTrue(d.toolCall.argumentsJson.contains("0912345678"))
    }

    @Test fun smsIntentGoesFastOnPlay() {
        val d = decide("傳簡訊給媽媽說我晚點到")
        assertEquals(RouteKind.FAST, d.route)
        assertEquals("sms.compose", d.toolCall!!.toolName)
    }

    @Test fun alarmIntentExtractsTime() {
        val d = decide("明天早上7點30分叫我起床，設個鬧鐘")
        assertEquals(RouteKind.FAST, d.route)
        assertEquals("alarm.create", d.toolCall!!.toolName)
        assertTrue(d.toolCall.argumentsJson.contains("\"hour\": 7"))
        assertTrue(d.toolCall.argumentsJson.contains("\"minute\": 30"))
    }

    @Test fun calendarWithoutGrantDeniedNoPrivilege() {
        val d = decide(
            "明天下午三點加個行程",
            play.copy(grantedPermissions = emptySet()),
        )
        assertEquals(RouteKind.DENIED, d.route)
        assertEquals(DenyReason.NO_PRIVILEGE, d.reasonCode)
        assertNotNull(d.fallbackMessage)
        assertTrue(d.fallbackMessage!!.contains("NO_PRIVILEGE"))
        assertTrue(d.fallbackMessage.contains("→"))
    }

    @Test fun screenshotSwitchOffDeniedUserDisabled() {
        val d = decide("幫我截圖", play.copy(userSwitches = mapOf("screenshot" to false)))
        assertEquals(RouteKind.DENIED, d.route)
        assertEquals(DenyReason.USER_DISABLED, d.reasonCode)
        assertTrue(d.fallbackMessage!!.contains("USER_DISABLED"))
    }

    @Test fun slowIntentOnPlayDeniedFlavorBlocked() {
        val d = decide("幫我自動點擊螢幕上的確定按鈕")
        assertEquals(RouteKind.DENIED, d.route)
        assertEquals(DenyReason.FLAVOR_BLOCKED, d.reasonCode)
        assertTrue(d.fallbackMessage!!.contains("FLAVOR_BLOCKED"))
    }

    @Test fun slowIntentOnSelfInstallWithAutomationGoesFast() {
        for (flavor in listOf(Flavor.FOSS, Flavor.GITHUB)) {
            val ctx = ProjectionContext(
                flavor = flavor,
                automationEnabled = true,
                grantedPermissions = setOf("android.permission.READ_CALENDAR"),
            )
            val d = decide("幫我自動點擊螢幕上的確定按鈕", ctx)
            assertEquals(RouteKind.FAST, d.route)
            assertEquals(ToolRegistry.SLOW_TOOL_NAME, d.toolCall!!.toolName)
        }
    }

    @Test fun unknownIntentDeniedWithGuidance() {
        val d = decide("今天心情不錯，隨便聊聊")
        assertEquals(RouteKind.DENIED, d.route)
        assertNull(d.toolCall)
        assertNotNull(d.fallbackMessage)
    }

    @Test fun headerRecordsDecisionAndSnapshot() {
        val d = decide("把音量調小聲一點")
        assertEquals(RouteKind.FAST, d.header.route)
        assertEquals("volume.set", d.header.toolName)
        assertNull(d.header.reasonCode)
        assertEquals("s1", d.header.sessionId)
        assertEquals(3, d.header.turnIndex)
        assertTrue(d.header.visibleTools.contains("volume.set"))
    }

    @Test fun deniedHeaderCarriesReasonCode() {
        val d = decide(
            "明天下午三點加個行程",
            play.copy(grantedPermissions = emptySet()),
        )
        assertEquals(RouteKind.DENIED, d.header.route)
        assertEquals("calendar.create", d.header.toolName)
        assertEquals("NO_PRIVILEGE", d.header.reasonCode)
    }

    @Test fun callIdsAreUnique() {
        val a = decide("打開相機").toolCall!!.callId
        val b = decide("打開相機").toolCall!!.callId
        assertTrue(a != b)
    }

    @Test fun fallbackTemplateShape() {
        val msg = FallbackMessages.format("X", DenyReason.FLAVOR_BLOCKED, "Y", "Z")
        assertEquals("做不到X（FLAVOR_BLOCKED）→ 可替代Y → 需要你做Z", msg)
    }
}
