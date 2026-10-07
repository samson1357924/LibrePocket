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

    // PR#1 scope-down: 投影通過但 executionReady=false 的工具一律 DENIED
    // （USER_DISABLED，本輪未啟用；接線 PR 翻 flag 後恢復 FAST）。
    // fallback 含工具名 ⇒ 證明模板仍正確命中（路由邏輯覆蓋保留）。
    @Test fun navigateIntentDeniedUntilWired() {
        val d = decide("幫我導航到台北車站")
        assertEquals(RouteKind.DENIED, d.route)
        assertEquals(DenyReason.USER_DISABLED, d.reasonCode)
        assertNull(d.toolCall)
        assertTrue(d.fallbackMessage!!.contains("navigate"))
    }

    @Test fun openAppIntentDeniedUntilWired() {
        val d = decide("打開相機")
        assertEquals(RouteKind.DENIED, d.route)
        assertEquals(DenyReason.USER_DISABLED, d.reasonCode)
        assertTrue(d.fallbackMessage!!.contains("open_app"))
    }

    @Test fun phoneDialIntentDeniedUntilWired() {
        val d = decide("打電話給 0912345678")
        assertEquals(RouteKind.DENIED, d.route)
        assertEquals(DenyReason.USER_DISABLED, d.reasonCode)
        assertTrue(d.fallbackMessage!!.contains("phone.dial"))
    }

    @Test fun smsIntentDeniedUntilWiredOnPlay() {
        val d = decide("傳簡訊給媽媽說我晚點到")
        assertEquals(RouteKind.DENIED, d.route)
        assertEquals(DenyReason.USER_DISABLED, d.reasonCode)
        assertTrue(d.fallbackMessage!!.contains("sms.compose"))
    }

    @Test fun alarmIntentDeniedUntilWired() {
        val d = decide("明天早上7點30分叫我起床，設個鬧鐘")
        assertEquals(RouteKind.DENIED, d.route)
        assertEquals(DenyReason.USER_DISABLED, d.reasonCode)
        assertTrue(d.fallbackMessage!!.contains("alarm.create"))
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

    @Test fun slowIntentOnSelfInstallWithAutomationDeniedUntilWired() {
        for (flavor in listOf(Flavor.FOSS, Flavor.GITHUB)) {
            val ctx = ProjectionContext(
                flavor = flavor,
                automationEnabled = true,
                grantedPermissions = setOf("android.permission.READ_CALENDAR"),
            )
            val d = decide("幫我自動點擊螢幕上的確定按鈕", ctx)
            assertEquals(RouteKind.DENIED, d.route)
            assertEquals(DenyReason.USER_DISABLED, d.reasonCode)
            assertTrue(d.fallbackMessage!!.contains(ToolRegistry.SLOW_TOOL_NAME))
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
        assertEquals(RouteKind.DENIED, d.header.route)
        assertEquals("volume.set", d.header.toolName)
        assertEquals("USER_DISABLED", d.header.reasonCode)
        assertEquals("s1", d.header.sessionId)
        assertEquals(3, d.header.turnIndex)
        // 雙欄快照：模型名單為空（本輪未接線），投影快照保留供審計。
        assertTrue(d.header.visibleTools.isEmpty())
        assertTrue(d.header.projectedTools.contains("volume.set"))
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

    @Test fun deniedDecisionsAreDeterministic() {
        // Scaffold 期無 FAST（callId 只在 FAST 遞增）：相同輸入得相同 DENIED。
        val a = decide("打開相機")
        val b = decide("打開相機")
        assertEquals(RouteKind.DENIED, a.route)
        assertEquals(a.reasonCode, b.reasonCode)
        assertEquals(a.fallbackMessage, b.fallbackMessage)
        assertEquals(a.header.projectedTools, b.header.projectedTools)
    }

    @Test fun fallbackTemplateShape() {
        val msg = FallbackMessages.format("X", DenyReason.FLAVOR_BLOCKED, "Y", "Z")
        assertEquals("做不到X（FLAVOR_BLOCKED）→ 可替代Y → 需要你做Z", msg)
    }
}
