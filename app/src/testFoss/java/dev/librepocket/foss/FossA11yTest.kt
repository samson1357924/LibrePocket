package dev.librepocket.foss

import dev.librepocket.agent.foss.FossA11yState
import dev.librepocket.agent.foss.FossAccessibilityService
import dev.librepocket.agent.foss.FossGateDecision
import dev.librepocket.automation.A11yAction
import dev.librepocket.guard.ArbitrationCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3 foss a11y 鏡像測試（BACKLOG B5/B8，純 OSS 路徑）：
 * 與 github 側同語義的仲裁前攔截 + 預設關。
 */
class FossA11yTest {

    @Test fun paymentDeleteSendForceConfirm() {
        val pay = FossAccessibilityService.gateAction("幫我轉帳 500 元", A11yAction.Tap("n1"))
        assertTrue("$pay", pay is FossGateDecision.NeedConfirm)
        assertTrue((pay as FossGateDecision.NeedConfirm).codes.contains(ArbitrationCode.PAYMENT))

        val del = FossAccessibilityService.gateAction("把對話刪除", A11yAction.Tap("n2"))
        assertTrue("$del", del is FossGateDecision.NeedConfirm)

        val send = FossAccessibilityService.gateAction("提交訂單", A11yAction.Input("n3", "hi"))
        assertTrue("$send", send is FossGateDecision.NeedConfirm)
        assertTrue((send as FossGateDecision.NeedConfirm).codes.contains(ArbitrationCode.SEND))
    }

    @Test fun benignTapIsAllowed() {
        assertEquals(
            FossGateDecision.Allow,
            FossAccessibilityService.gateAction("點一下返回", A11yAction.Tap("n1")),
        )
    }

    @Test fun defaultStateIsNotEffective() {
        assertFalse(FossA11yState.effective())
        assertEquals(false, FossAccessibilityService.DEFAULT_ENABLED)
    }

    @Test fun outOfScopeGateIsDeniedNotConfirmable() {
        // 越界種類 → Denied（確認不可覆寫；executeConfirmed 遇 Denied 永拒）。
        val verdict = FossAccessibilityService.gateAction(
            "點一下確定",
            A11yAction.Back,
            allowedKinds = dev.librepocket.guard.SlowArbitrator.DEFAULT_ALLOWED
                .filter { it != dev.librepocket.guard.SlowActionKind.BACK }.toSet(),
        )
        assertTrue("$verdict", verdict is FossGateDecision.Denied)
    }

    @Test fun oneShotConfirmationLifecycle() {       // P1 lifecycle（PR#1 review blocker，判定層；perform 需真機）：
        // NeedConfirm（攜指紋）→ 武裝三同意（effective）→ exact-match 消耗一次 → 重放被擋。
        val gate = FossAccessibilityService.gateAction("幫我轉帳 500 元", A11yAction.Tap("n1"))
        assertTrue("$gate", gate is FossGateDecision.NeedConfirm)
        val fp = (gate as FossGateDecision.NeedConfirm).fingerprint
        assertTrue(fp.startsWith("a11y-v2:"))

        val prevSwitch = FossA11yState.switchOn
        val prevGrant = FossA11yState.serviceGranted
        try {
            FossA11yState.switchOn = true
            FossA11yState.serviceGranted = true
            FossA11yState.clearConfirmation()
            assertFalse(FossA11yState.effective())
            assertFalse(FossA11yState.consumeConfirmation(fp))

            FossA11yState.grantConfirmation(fp)
            assertTrue(FossA11yState.effective())
            assertFalse(FossA11yState.consumeConfirmation(fp + "00"))
            assertTrue(FossA11yState.effective())
            assertTrue(FossA11yState.consumeConfirmation(fp))

            assertFalse(FossA11yState.userConfirmed)
            assertFalse(FossA11yState.effective())
            assertFalse(FossA11yState.consumeConfirmation(fp))
        } finally {
            FossA11yState.switchOn = prevSwitch
            FossA11yState.serviceGranted = prevGrant
            FossA11yState.clearConfirmation()
        }
    }

    @Test fun confirmationBoundToAction() {
        val fpA = (FossAccessibilityService.gateAction("幫我轉帳 1 元", A11yAction.Tap("n1")) as FossGateDecision.NeedConfirm).fingerprint
        val fpB = dev.librepocket.automation.AutomationCore.fingerprintFor(A11yAction.Tap("n99"), "幫我轉帳 1 元")
        assertTrue(fpA != fpB)
        val prevSwitch = FossA11yState.switchOn
        val prevGrant = FossA11yState.serviceGranted
        try {
            FossA11yState.switchOn = true
            FossA11yState.serviceGranted = true
            FossA11yState.grantConfirmation(fpA)
            assertFalse(FossA11yState.consumeConfirmation(fpB))
            assertTrue(FossA11yState.consumeConfirmation(fpA))
        } finally {
            FossA11yState.switchOn = prevSwitch
            FossA11yState.serviceGranted = prevGrant
            FossA11yState.clearConfirmation()
        }
    }

    @Test fun benignAllowDoesNotNeedConfirmation() {
        // 與 github 鏡像同語義：良性 Allow 不吃確認額度；無確認時仍 armed。
        val prevSwitch = FossA11yState.switchOn
        val prevGrant = FossA11yState.serviceGranted
        try {
            FossA11yState.switchOn = true
            FossA11yState.serviceGranted = true
            FossA11yState.clearConfirmation()
            assertTrue(FossA11yState.isArmed())
            assertFalse(FossA11yState.effective())
            val fp = (FossAccessibilityService.gateAction("幫我轉帳 1 元", A11yAction.Tap("n1")) as FossGateDecision.NeedConfirm).fingerprint
            FossA11yState.grantConfirmation(fp)
            val gate = FossAccessibilityService.gateAction("點一下返回", A11yAction.Tap("n1"))
            assertEquals(FossGateDecision.Allow, gate)
            assertTrue(FossA11yState.userConfirmed)
            assertTrue(FossA11yState.consumeConfirmation(fp))
        } finally {
            FossA11yState.switchOn = prevSwitch
            FossA11yState.serviceGranted = prevGrant
            FossA11yState.clearConfirmation()
        }
    }

    @Test fun delimiterCollisionBoundedByLengthPrefix() {
        val fpA = dev.librepocket.automation.AutomationCore.fingerprintFor(
            A11yAction.Input("n1", "x|幫我轉帳"),
            "幫我轉帳",
        )
        val fpB = dev.librepocket.automation.AutomationCore.fingerprintFor(
            A11yAction.Input("n1", "x"),
            "幫我轉帳|幫我轉帳",
        )
        assertTrue(fpA != fpB)
        val fpC = dev.librepocket.automation.AutomationCore.fingerprintFor(A11yAction.Tap("n1"), "a|b", "c")
        val fpD = dev.librepocket.automation.AutomationCore.fingerprintFor(A11yAction.Tap("n1"), "a", "b|c")
        assertTrue(fpC != fpD)
    }

    @Test fun confirmationExpiresAfterTtl() {
        val prevSwitch = FossA11yState.switchOn
        val prevGrant = FossA11yState.serviceGranted
        val prevClock = FossA11yState.clockMs
        try {
            var now = 2_000_000L
            FossA11yState.clockMs = { now }
            FossA11yState.switchOn = true
            FossA11yState.serviceGranted = true
            FossA11yState.clearConfirmation()
            val fp = (FossAccessibilityService.gateAction("幫我轉帳 1 元", A11yAction.Tap("n1")) as FossGateDecision.NeedConfirm).fingerprint
            FossA11yState.grantConfirmation(fp)
            assertTrue(FossA11yState.effective())
            now += FossA11yState.CONFIRM_TTL_MS + 1
            assertFalse(FossA11yState.effective())
            assertFalse(FossA11yState.consumeConfirmation(fp))
        } finally {
            FossA11yState.switchOn = prevSwitch
            FossA11yState.serviceGranted = prevGrant
            FossA11yState.clockMs = prevClock
            FossA11yState.clearConfirmation()
        }
    }
}
