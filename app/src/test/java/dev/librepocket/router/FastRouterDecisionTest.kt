package dev.librepocket.router

import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // Stage1: `sms.compose` 模板 to 恆空（待收件者抽取落地前一律補參），
    // 不送空字串猜測執行；原因碼與接線門 USER_DISABLED 分開以供審計區分。
    @Test fun smsWithoutNumberNeedsClarification() {
        val d = decide("傳簡訊給媽媽說我晚點到")
        assertEquals(RouteKind.DENIED, d.route)
        assertEquals(DenyReason.NEED_CLARIFICATION, d.reasonCode)
        assertNull(d.toolCall)
        assertTrue(d.fallbackMessage!!.contains("sms.compose"))
        assertTrue(d.fallbackMessage!!.contains("NEED_CLARIFICATION"))
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

    // ---- Stage1: 猜測執行一律轉補參/澄清（#19 前置，不翻 executionReady） ----

    @Test fun alarmWithoutTimeNeedsClarification() {
        for (text in listOf("幫我設個鬧鐘", "設鬧鐘", "幫我設鬧鐘叫我起床")) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(text, d.toolCall)
            assertTrue(text, d.fallbackMessage!!.contains("alarm.create"))
            assertTrue(text, d.fallbackMessage!!.contains("NEED_CLARIFICATION"))
            assertEquals(text, "NEED_CLARIFICATION", d.header.reasonCode)
        }
    }

    @Test fun phoneWithoutNumberNeedsClarification() {
        for (text in listOf("打電話給媽媽", "幫我打電話", "撥打電話")) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(text, d.toolCall)
            assertTrue(text, d.fallbackMessage!!.contains("phone.dial"))
            assertTrue(text, d.fallbackMessage!!.contains("NEED_CLARIFICATION"))
        }
    }

    @Test fun calendarWithGrantButEmptyStartNeedsClarification() {
        // 有 READ_CALENDAR 授權時投影通過，空 start/end 模板仍轉澄清（不猜時間）；
        // 無授權時投影 NO_PRIVILEGE 優先（見 calendarWithoutGrantDeniedNoPrivilege）。
        val d = decide("明天下午三點加個行程")
        assertEquals(RouteKind.DENIED, d.route)
        assertEquals(DenyReason.NEED_CLARIFICATION, d.reasonCode)
        assertNull(d.toolCall)
        assertTrue(d.fallbackMessage!!.contains("calendar.create"))
        assertTrue(d.fallbackMessage!!.contains("NEED_CLARIFICATION"))
        assertEquals("NEED_CLARIFICATION", d.header.reasonCode)
    }

    @Test fun negatedIntentsNeedClarification() {
        for (text in listOf(
            "不要打電話給 0912345678",
            "別設鬧鐘，明天早上7點叫我",
            "不用幫我截圖",
            "不要導航到台北車站",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(text, d.toolCall)
            assertTrue(text, d.fallbackMessage!!.contains("NEED_CLARIFICATION"))
            assertEquals(text, "NEED_CLARIFICATION", d.header.reasonCode)
        }
    }

    @Test fun negationDoesNotMisFireOnPartialMatch() {
        // 「特別」含「別」但非否定：不得誤判為澄清，仍走接線門 USER_DISABLED。
        val d = decide("特別想聽音樂，播放周杰倫的歌")
        assertEquals(RouteKind.DENIED, d.route)
        assertEquals(DenyReason.USER_DISABLED, d.reasonCode)
        assertTrue(d.fallbackMessage!!.contains("music.control"))
    }

    @Test fun negationDoesNotMisFireOnSimplifiedPartialMatch() {
        // 簡體「别人」含「别」但非否定：不得誤判為澄清，仍走接線門 USER_DISABLED。
        val d = decide("别人想聽音樂，播放周杰倫的歌")
        assertEquals(RouteKind.DENIED, d.route)
        assertEquals(DenyReason.USER_DISABLED, d.reasonCode)
        assertTrue(d.fallbackMessage!!.contains("music.control"))
    }

    @Test fun simplifiedNegationNeedsClarification() {
        // 簡體否定（别+動詞）與繁體對稱，同樣轉澄清、絕不執行。
        for (text in listOf(
            "别打电话给 0912345678",
            "别设闹钟，明天早上7点叫我",
            "不用帮我截图",
            "不要导航到台北车站",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(text, d.toolCall)
            assertTrue(text, d.fallbackMessage!!.contains("NEED_CLARIFICATION"))
            assertEquals(text, "NEED_CLARIFICATION", d.header.reasonCode)
        }
    }

    @Test fun clarificationReasonDistinctFromUserDisabled() {
        // 審計可區分：缺參/否定（NEED_CLARIFICATION）≠ 未接線/開關關（USER_DISABLED）。
        assertTrue(DenyReason.NEED_CLARIFICATION != DenyReason.USER_DISABLED)
        val missing = decide("幫我設個鬧鐘")
        val wired = decide("明天早上7點30分叫我起床，設個鬧鐘")
        assertEquals(DenyReason.NEED_CLARIFICATION, missing.reasonCode)
        assertEquals(DenyReason.USER_DISABLED, wired.reasonCode)
        assertEquals("NEED_CLARIFICATION", missing.header.reasonCode)
        assertEquals("USER_DISABLED", wired.header.reasonCode)
        assertTrue(missing.header.projectedTools.contains("alarm.create"))
    }

    @Test fun missingArgsClarificationSharedAcrossFlavors() {
        // 三 flavor 共用邏輯：缺參判定與否定判定不分 flavor（投影通過即澄清）。
        for (flavor in listOf(Flavor.PLAY, Flavor.FOSS, Flavor.GITHUB)) {
            val ctx = ProjectionContext(
                flavor = flavor,
                grantedPermissions = setOf("android.permission.READ_CALENDAR"),
            )
            val alarm = decide("幫我設個鬧鐘", ctx)
            assertEquals("$flavor", RouteKind.DENIED, alarm.route)
            assertEquals("$flavor", DenyReason.NEED_CLARIFICATION, alarm.reasonCode)
            val phone = decide("打電話給媽媽", ctx)
            assertEquals("$flavor", RouteKind.DENIED, phone.route)
            assertEquals("$flavor", DenyReason.NEED_CLARIFICATION, phone.reasonCode)
            val negated = decide("不要打電話給 0912345678", ctx)
            assertEquals("$flavor", RouteKind.DENIED, negated.route)
            assertEquals("$flavor", DenyReason.NEED_CLARIFICATION, negated.reasonCode)
        }
    }

    @Test fun compoundCancelAndRecreateIsClarificationNotExecution() {
        // 已知侷限（fail-safe 方向）：複合句同時命中模板與取消/停止詞時轉澄清，
        // 屬誤澄清但絕不執行；行為釘住，待否定語義精化後再收斂。
        val d = decide("取消舊的鬧鐘再設一個新的")
        assertEquals(RouteKind.DENIED, d.route)
        assertEquals(DenyReason.NEED_CLARIFICATION, d.reasonCode)
        assertNull(d.toolCall)
        assertTrue(d.fallbackMessage!!.contains("NEED_CLARIFICATION"))
        assertEquals("NEED_CLARIFICATION", d.header.reasonCode)
    }

    @Test fun cjkNumeralAlarmTimeNeedsClarification() {
        // 中文數字時間（七點半）目前不被解析為明確時間：fail-safe 轉澄清，
        // 不預設 7:00；行為釘住，待中文數字解析落地後再支援。
        val d = decide("明早七點半設個鬧鐘")
        assertEquals(RouteKind.DENIED, d.route)
        assertEquals(DenyReason.NEED_CLARIFICATION, d.reasonCode)
        assertNull(d.toolCall)
        assertTrue(d.fallbackMessage!!.contains("NEED_CLARIFICATION"))
        assertEquals("NEED_CLARIFICATION", d.header.reasonCode)
    }

    @Test fun negationWithMissingGrantStaysNoPrivilege() {
        // 遮罩順序：投影 UNAVAILABLE（無授權）優先於否定澄清；否定句在無授權
        // 下仍回 NO_PRIVILEGE，絕不被誤讀為補參澄清。
        val d = decide(
            "不要加行程，明天開會",
            play.copy(grantedPermissions = emptySet()),
        )
        assertEquals(RouteKind.DENIED, d.route)
        assertEquals(DenyReason.NO_PRIVILEGE, d.reasonCode)
        assertNull(d.toolCall)
        assertTrue(d.fallbackMessage!!.contains("NO_PRIVILEGE"))
        assertFalse(d.fallbackMessage!!.contains("NEED_CLARIFICATION"))
    }
}
