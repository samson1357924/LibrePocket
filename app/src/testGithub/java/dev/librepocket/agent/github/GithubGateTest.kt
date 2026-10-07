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

    @Test fun outOfScopeGateIsDeniedNotConfirmable() {
        // 越界種類 → Denied（確認不可覆寫；executeConfirmed 遇 Denied 永拒）。
        val verdict = GithubAccessibilityService.gateAction(
            "點一下確定",
            A11yAction.Back,
            allowedKinds = dev.librepocket.guard.SlowArbitrator.DEFAULT_ALLOWED
                .filter { it != dev.librepocket.guard.SlowActionKind.BACK }.toSet(),
        )
        assertTrue("$verdict", verdict is GateDecision.Denied)
    }

    @Test fun oneShotConfirmationLifecycle() {
        // P1 lifecycle（PR#1 review blocker，判定層；perform 需真機）：
        // NeedConfirm → 武裝三同意（effective）→ 消耗一次確認 → 重放被擋。
        val gate = GithubAccessibilityService.gateAction("幫我轉帳 500 元", A11yAction.Tap("n1"))
        assertTrue("$gate", gate is GateDecision.NeedConfirm)

        val prevSwitch = GithubA11yState.switchOn
        val prevGrant = GithubA11yState.serviceGranted
        val prevConfirm = GithubA11yState.userConfirmed
        try {
            // 未確認：effective false，連判定都過不了。
            GithubA11yState.switchOn = true
            GithubA11yState.serviceGranted = true
            GithubA11yState.userConfirmed = false
            assertFalse(GithubA11yState.effective())
            assertFalse(GithubA11yState.consumeConfirmation())

            // 確認後：effective true，可消耗一次。
            GithubA11yState.userConfirmed = true
            assertTrue(GithubA11yState.effective())
            assertTrue(GithubA11yState.consumeConfirmation())

            // one-shot 已消耗：effective 回 false，重放再擋。
            assertFalse(GithubA11yState.userConfirmed)
            assertFalse(GithubA11yState.effective())
            assertFalse(GithubA11yState.consumeConfirmation())
        } finally {
            GithubA11yState.switchOn = prevSwitch
            GithubA11yState.serviceGranted = prevGrant
            GithubA11yState.userConfirmed = prevConfirm
        }
    }

    @Test fun benignAllowDoesNotNeedConfirmation() {
        // 良性 Allow 不吃確認額度：武裝後不消耗，userConfirmed 原樣保留。
        val prevSwitch = GithubA11yState.switchOn
        val prevGrant = GithubA11yState.serviceGranted
        val prevConfirm = GithubA11yState.userConfirmed
        try {
            GithubA11yState.switchOn = true
            GithubA11yState.serviceGranted = true
            GithubA11yState.userConfirmed = true
            val gate = GithubAccessibilityService.gateAction("點一下返回", A11yAction.Tap("n1"))
            assertEquals(GateDecision.Allow, gate)
            assertTrue(GithubA11yState.userConfirmed)
        } finally {
            GithubA11yState.switchOn = prevSwitch
            GithubA11yState.serviceGranted = prevGrant
            GithubA11yState.userConfirmed = prevConfirm
        }
    }
}
