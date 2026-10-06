package dev.librepocket.router

import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Projection
import dev.librepocket.tool.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PrivilegeGate tests (BACKLOG B4): PRIVILEGED tools never execute
 * without confirmation; unavailable tools are denied with reasons.
 * S3: `shell.elevated` three-arg [check] always gates to [NeedConfirm]
 * (callers must use the args-aware four-arg overload with reasonCode).
 */
class PrivilegeGateTest {

    private fun projectionOf(name: String, level: CapabilityLevel, reason: DenyReason? = null) =
        Projection(name, level, reason)

    @Test fun privilegedWithoutConfirmNeedsConfirm() {
        val tool = ToolRegistry.find("phone.dial")!!
        val result = PrivilegeGate.check(tool, false, projectionOf(tool.name, CapabilityLevel.NATIVE))
        assertEquals(GateResult.NeedConfirm(tool.name), result)
        assertFalse(PrivilegeGate.canExecute(tool, false, projectionOf(tool.name, CapabilityLevel.NATIVE)))
    }

    @Test fun privilegedWithConfirmAllowed() {
        for (name in listOf("phone.dial", "sms.compose", "screenshot.capture")) {
            val tool = ToolRegistry.find(name)!!
            assertTrue(
                name,
                PrivilegeGate.canExecute(tool, true, projectionOf(name, CapabilityLevel.NATIVE)),
            )
        }
    }

    @Test fun readNeedsNoConfirm() {
        val tool = ToolRegistry.find("navigate")!!
        assertTrue(PrivilegeGate.canExecute(tool, false, projectionOf(tool.name, CapabilityLevel.NATIVE)))
    }

    @Test fun writeNeedsNoGateConfirm() {
        val tool = ToolRegistry.find("alarm.create")!!
        assertTrue(PrivilegeGate.canExecute(tool, false, projectionOf(tool.name, CapabilityLevel.NATIVE)))
    }

    @Test fun degradedProjectionStillExecutable() {
        val tool = ToolRegistry.find("notification.read")!!
        assertTrue(
            PrivilegeGate.canExecute(
                tool, false, projectionOf(tool.name, CapabilityLevel.DEGRADED, DenyReason.NO_PRIVILEGE),
            ),
        )
    }

    @Test fun unavailableDeniedEvenWhenConfirmed() {
        val tool = ToolRegistry.find("phone.dial")!!
        val projection = projectionOf(tool.name, CapabilityLevel.UNAVAILABLE, DenyReason.FLAVOR_BLOCKED)
        assertEquals(GateResult.Denied(DenyReason.FLAVOR_BLOCKED), PrivilegeGate.check(tool, true, projection))
        assertFalse(PrivilegeGate.canExecute(tool, true, projection))
    }

    @Test fun deniedCarriesProjectionReason() {
        val tool = ToolRegistry.find("calendar.create")!!
        val projection = projectionOf(tool.name, CapabilityLevel.UNAVAILABLE, DenyReason.NO_PRIVILEGE)
        assertEquals(GateResult.Denied(DenyReason.NO_PRIVILEGE), PrivilegeGate.check(tool, true, projection))
    }

    @Test fun elevatedThreeArgAlwaysNeedsConfirmEvenWhenConfirmed() {
        // S3 D09：三參版遇 shell.elevated 一律 NeedConfirm（即使 confirmed=true），
        // 強制呼叫方改走四參 args-aware 版攜 reasonCode，防原因碼門被繞過。
        val tool = ToolRegistry.find("shell.elevated")!!
        val native = projectionOf(tool.name, CapabilityLevel.NATIVE)
        assertEquals(GateResult.NeedConfirm(tool.name), PrivilegeGate.check(tool, false, native))
        assertEquals(GateResult.NeedConfirm(tool.name), PrivilegeGate.check(tool, true, native))
        assertFalse(PrivilegeGate.canExecute(tool, true, native))
        // 四參版：有 reasonCode + 已確認才放行。
        assertEquals(
            GateResult.Allowed,
            PrivilegeGate.check(tool, true, native, mapOf("reasonCode" to "D09-T")),
        )
        assertEquals(
            GateResult.NeedConfirm(tool.name),
            PrivilegeGate.check(tool, true, native, mapOf("reasonCode" to " ")),
        )
    }

    @Test fun fullTextJsonBoundaryRejectsPrefixNumbers() {
        // MAJOR-7：`1` 不得為 `10`/`11` 的前綴誤判。
        assertTrue(PrivilegeGate.isFullTextRequestJson("{\"limit\": 10, \"fullText\": true}"))
        assertTrue(PrivilegeGate.isFullTextRequestJson("{\"fullText\": 1}"))
        assertFalse(PrivilegeGate.isFullTextRequestJson("{\"fullText\": 10}"))
        assertFalse(PrivilegeGate.isFullTextRequestJson("{\"fullText\": 11}"))
        assertFalse(PrivilegeGate.isFullTextRequestJson("{\"limit\": 10}"))
    }
}
