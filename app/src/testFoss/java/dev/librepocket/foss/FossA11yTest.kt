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
}
