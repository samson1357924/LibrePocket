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
        // NeedConfirm（攜指紋）→ 武裝三同意（effective）→ exact-match 消耗一次 → 重放被擋。
        val gate = GithubAccessibilityService.gateAction("幫我轉帳 500 元", A11yAction.Tap("n1"))
        assertTrue("$gate", gate is GateDecision.NeedConfirm)
        val fp = (gate as GateDecision.NeedConfirm).fingerprint
        assertTrue(fp.startsWith("a11y-v1:"))

        val prevSwitch = GithubA11yState.switchOn
        val prevGrant = GithubA11yState.serviceGranted
        try {
            // 未確認：effective false，消耗失敗。
            GithubA11yState.switchOn = true
            GithubA11yState.serviceGranted = true
            GithubA11yState.clearConfirmation()
            assertFalse(GithubA11yState.effective())
            assertFalse(GithubA11yState.consumeConfirmation(fp))

            // 確認後：effective true，可消耗一次（exact-match）。
            GithubA11yState.grantConfirmation(fp)
            assertTrue(GithubA11yState.effective())
            // 錯誤指紋不消耗且保留額度。
            assertFalse(GithubA11yState.consumeConfirmation(fp + "00"))
            assertTrue(GithubA11yState.effective())
            assertTrue(GithubA11yState.consumeConfirmation(fp))

            // one-shot 已消耗：effective 回 false，重放再擋。
            assertFalse(GithubA11yState.userConfirmed)
            assertFalse(GithubA11yState.effective())
            assertFalse(GithubA11yState.consumeConfirmation(fp))
        } finally {
            GithubA11yState.switchOn = prevSwitch
            GithubA11yState.serviceGranted = prevGrant
            GithubA11yState.clearConfirmation()
        }
    }

    @Test fun confirmationBoundToAction() {
        // P1 re-review：TOCTOU 替換必須失敗（目標/金額/正文任一變更即指紋變更）。
        val gateA = GithubAccessibilityService.gateAction("幫我轉帳 1 元", A11yAction.Tap("n1"))
        val fpA = (gateA as GateDecision.NeedConfirm).fingerprint
        val fpB = AutomationCore.fingerprintFor(A11yAction.Tap("n99"), "幫我轉帳 1 元")
        val fpC = AutomationCore.fingerprintFor(A11yAction.Tap("n1"), "幫我轉帳 50000 元")
        val fpD = AutomationCore.fingerprintFor(A11yAction.Input("n3", "hi"), "提交訂單")
        val fpE = AutomationCore.fingerprintFor(A11yAction.Input("n3", "attacker"), "提交訂單")
        assertTrue(fpA != fpB)
        assertTrue(fpA != fpC)
        assertTrue(fpD != fpE)
        // 穩定性：同輸入同指紋；規範化（大小寫/空白）一致。
        val fpNorm1 = AutomationCore.fingerprintFor(A11yAction.Tap("n1"), "  幫我轉帳 500 元 ")
        val fpNorm2 = AutomationCore.fingerprintFor(A11yAction.Tap("n1"), "幫我轉帳 500 元")
        assertTrue(fpNorm1 == fpNorm2)

        val prevSwitch = GithubA11yState.switchOn
        val prevGrant = GithubA11yState.serviceGranted
        try {
            GithubA11yState.switchOn = true
            GithubA11yState.serviceGranted = true
            GithubA11yState.grantConfirmation(fpA)
            // 替換提案消耗失敗且額度保留。
            assertFalse(GithubA11yState.consumeConfirmation(fpB))
            assertTrue(GithubA11yState.effective())
            assertTrue(GithubA11yState.consumeConfirmation(fpA))
        } finally {
            GithubA11yState.switchOn = prevSwitch
            GithubA11yState.serviceGranted = prevGrant
            GithubA11yState.clearConfirmation()
        }
    }

    @Test fun benignAllowDoesNotNeedConfirmation() {
        // 良性 Allow 不吃確認額度：武裝後不消耗，指紋原樣保留。
        val prevSwitch = GithubA11yState.switchOn
        val prevGrant = GithubA11yState.serviceGranted
        try {
            GithubA11yState.switchOn = true
            GithubA11yState.serviceGranted = true
            val gateFp = (GithubAccessibilityService.gateAction("幫我轉帳 1 元", A11yAction.Tap("n1")) as GateDecision.NeedConfirm).fingerprint
            GithubA11yState.grantConfirmation(gateFp)
            val gate = GithubAccessibilityService.gateAction("點一下返回", A11yAction.Tap("n1"))
            assertEquals(GateDecision.Allow, gate)
            assertTrue(GithubA11yState.userConfirmed)
            assertTrue(GithubA11yState.consumeConfirmation(gateFp))
        } finally {
            GithubA11yState.switchOn = prevSwitch
            GithubA11yState.serviceGranted = prevGrant
            GithubA11yState.clearConfirmation()
        }
    }
}
