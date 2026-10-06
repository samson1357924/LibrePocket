package dev.librepocket.agent.github

import dev.librepocket.automation.A11yAction
import dev.librepocket.automation.AutomationCore
import dev.librepocket.guard.ArbitrationCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3 github a11y 仲裁前攔截測試（BACKLOG B5/B8）：
 * 支付/刪除/發送強制 CONFIRM、越界攔截、良性放行、緊湊 JSON。
 * 全走純函數（[GithubAccessibilityService.gateAction] + [AutomationCore]），
 * 不碰 Android 框架。
 */
class GithubGateTest {

    @Test fun paymentDeleteSendForceConfirm() {
        val pay = GithubAccessibilityService.gateAction("幫我轉帳 500 元", A11yAction.Tap("n1"))
        assertTrue("$pay", pay is GateDecision.NeedConfirm)
        assertTrue((pay as GateDecision.NeedConfirm).codes.contains(ArbitrationCode.PAYMENT))

        val del = GithubAccessibilityService.gateAction("把對話刪除", A11yAction.Tap("n2"))
        assertTrue("$del", del is GateDecision.NeedConfirm)
        assertTrue((del as GateDecision.NeedConfirm).codes.contains(ArbitrationCode.DELETE))

        val send = GithubAccessibilityService.gateAction(
            "提交訂單",
            A11yAction.Input("n3", "hi"),
        )
        assertTrue("$send", send is GateDecision.NeedConfirm)
        assertTrue((send as GateDecision.NeedConfirm).codes.contains(ArbitrationCode.SEND))
    }

    @Test fun benignTapIsAllowed() {
        val verdict = GithubAccessibilityService.gateAction("點一下返回", A11yAction.Tap("n1"))
        assertEquals(GateDecision.Allow, verdict)
    }

    @Test fun mergedSlowHintsStillForceConfirmWithoutSlowAssess() {
        // S3 镜像统一：github 只走 AutomationCore（守衛），原慢核獨有關鍵詞
        // 已併入守衛，移除 slow.assess 不丟失攔截。
        for (goal in listOf("去結帳", "把這個帳號註銷", "送出表單", "checkout now")) {
            val verdict = GithubAccessibilityService.gateAction(goal, A11yAction.Tap("n1"))
            assertTrue("$goal -> $verdict", verdict is GateDecision.NeedConfirm)
        }
    }

    @Test fun defaultStateIsNotEffective() {
        assertFalse(GithubA11yState.effective())
        assertEquals(false, GithubAccessibilityService.DEFAULT_ENABLED)
    }

    @Test fun compactJsonHasNoWhitespace() {
        assertEquals("{\"tap\":\"n1\"}", AutomationCore.tapJson("n1"))
        assertEquals("{\"swipe\":[1,2,3,4]}", AutomationCore.swipeJson(1, 2, 3, 4))
        assertEquals("{\"back\":true}", AutomationCore.backJson())
    }
}
