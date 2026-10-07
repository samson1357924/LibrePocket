package dev.librepocket.systemb

import dev.librepocket.router.GateResult
import dev.librepocket.router.PrivilegeGate
import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.Projection
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.SideEffect
import dev.librepocket.tool.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1-C 通知全文測試：沿 NotificationTool 模式（標題優先，
 * 全文恆 PRIVILEGED + listener + 二次同意雙門）；
 * ToolRegistry `notification.read` schema 含 `fullText` 參數。
 */
class NotificationFullTest {

    private class RecordingSink : PermissionSink {
        val requests = mutableListOf<String>()
        override fun request(permission: String) {
            requests += permission
        }
    }

    private fun env(sink: RecordingSink, block: SystemBEnv.() -> SystemBEnv = { this }): SystemBEnv =
        SystemBEnv(permissionSink = sink).block()

    // ---- schema 參數 ----

    @Test fun registry_notificationReadHasFullTextParam() {
        val tool = ToolRegistry.find("notification.read")!!
        assertEquals(SideEffect.READ, tool.sideEffect)
        assertTrue("schema=${tool.jsonSchema}", "\"fullText\"" in tool.jsonSchema)
        assertTrue("\"limit\"" in tool.jsonSchema)
        assertEquals("notification", tool.annotations.requiresSwitch)
        assertEquals("listener:notification", tool.annotations.requiresPermission)
    }

    // ---- SystemB 雙門：listener + 二次同意 ----

    @Test fun titleOnlyByDefault_fullNeedsDoubleGate() {
        val sink = RecordingSink()
        val on = env(sink) { copy(notificationListenerEnabled = true) }
        // 未二次同意：即使要求全文也降為標題。
        val asked = NotificationTool.execute(on, mapOf("fullText" to "true"))
        assertTrue(asked.started)
        assertEquals("title_only", asked.intent!!.extras["mode"])
        // 雙門全開：全文。
        val consented = env(sink) {
            copy(notificationListenerEnabled = true, notificationFullTextConsented = true)
        }
        val full = NotificationTool.execute(consented, mapOf("fullText" to "true"))
        assertEquals("title_and_body", full.intent!!.extras["mode"])
        // MAJOR-1：listener 未開亦為 DEGRADED 標題可見（不再拒絕），全文同樣只給標題。
        val off = NotificationTool.execute(env(sink), mapOf("fullText" to "true"))
        assertTrue(off.started)
        assertEquals("title_only", off.intent!!.extras["mode"])
        assertTrue(sink.requests.isEmpty())
    }

    // ---- PrivilegeGate：全文恆需確認 ----

    @Test fun gate_titleOnlyNeedsNoConfirm_fullTextNeedsConfirm() {
        val tool = ToolRegistry.find("notification.read")!!
        val degraded = Projection(tool.name, CapabilityLevel.DEGRADED, dev.librepocket.tool.DenyReason.NO_PRIVILEGE)
        // 標題：沿舊語義，無需確認。
        assertTrue(PrivilegeGate.canExecute(tool, false, degraded))
        // 全文：未確認即 NeedConfirm（雙門之第二門）。
        val need = PrivilegeGate.check(tool, false, degraded, mapOf("fullText" to "true"))
        assertEquals(GateResult.NeedConfirm(tool.name), need)
        assertFalse(PrivilegeGate.canExecute(tool, false, degraded, mapOf("fullText" to "true")))
        // 全文：確認後放行（listener 門由投影負責，此處僅閘確認）。
        assertTrue(PrivilegeGate.canExecute(tool, true, degraded, mapOf("fullText" to "true")))
    }

    @Test fun gate_fullTextVariants() {
        assertTrue(PrivilegeGate.isFullTextRequest(mapOf("fullText" to "true")))
        assertTrue(PrivilegeGate.isFullTextRequest(mapOf("fullText" to "1")))
        assertFalse(PrivilegeGate.isFullTextRequest(mapOf("fullText" to "false")))
        assertFalse(PrivilegeGate.isFullTextRequest(emptyMap()))
        assertTrue(PrivilegeGate.isFullTextRequestJson("{\"limit\": 10, \"fullText\": true}"))
        assertTrue(PrivilegeGate.isFullTextRequestJson("{\"fullText\": \"true\"}"))
        assertFalse(PrivilegeGate.isFullTextRequestJson("{\"limit\": 10}"))
    }

    @Test fun gate_unavailableStillDeniedEvenForTitle() {
        val tool = ToolRegistry.find("notification.read")!!
        val off = Projection(tool.name, CapabilityLevel.UNAVAILABLE, dev.librepocket.tool.DenyReason.USER_DISABLED)
        assertEquals(
            GateResult.Denied(dev.librepocket.tool.DenyReason.USER_DISABLED),
            PrivilegeGate.check(tool, true, off, emptyMap()),
        )
    }

    @Test fun projection_playListenerOffDegrades() {
        val p = ToolRegistry.projectAll(ProjectionContext(flavor = Flavor.PLAY))["notification.read"]!!
        assertEquals(CapabilityLevel.DEGRADED, p.level)
        assertTrue(ToolRegistry.projectedTools(ProjectionContext(flavor = Flavor.PLAY)).any { it.name == "notification.read" })
    }

    @Test fun missingListener_bothLayersDegradedTitleVisible() {
        // MAJOR-1：缺 listener 時兩層一致為 DEGRADED/NO_PRIVILEGE（標題可見）。
        val projected = ToolRegistry.projectAll(ProjectionContext(flavor = Flavor.PLAY))["notification.read"]!!
        assertEquals(CapabilityLevel.DEGRADED, projected.level)
        assertEquals(dev.librepocket.tool.DenyReason.NO_PRIVILEGE, projected.reason)
        val sink = RecordingSink()
        val c = NotificationTool.check(env(sink), emptyMap())
        assertEquals(Availability.DEGRADED, c.availability)
        assertEquals(ReasonCode.NO_PRIVILEGE, c.reason)
        val e = NotificationTool.execute(env(sink), emptyMap())
        assertTrue(e.started)
        assertEquals("title_only", e.intent!!.extras["mode"])
        assertTrue(sink.requests.isEmpty())
    }

    @Test fun gate_bothNotificationNames_fullTextNeedsConfirm() {
        // MAJOR-3：全文門覆蓋兩個通知名；未同意全文一律 NeedConfirm，而非靜默降級。
        val registryTool = ToolRegistry.find("notification.read")!!
        val systemBTool = dev.librepocket.tool.ToolDef(
            name = "systemb.notification.titles",
            description = "SystemB notification titles",
            jsonSchema = "{}",
            sideEffect = SideEffect.READ,
        )
        assertEquals(
            setOf("notification.read", "systemb.notification.titles"),
            PrivilegeGate.NOTIFICATION_TOOLS,
        )
        for ((tool, projectionName) in listOf(
            registryTool to registryTool.name,
            systemBTool to systemBTool.name,
        )) {
            val degraded = Projection(projectionName, CapabilityLevel.DEGRADED, dev.librepocket.tool.DenyReason.NO_PRIVILEGE)
            // 標題：無需確認。
            assertTrue("${tool.name}", PrivilegeGate.canExecute(tool, false, degraded))
            // 全文未確認：NeedConfirm（兩名一致）。
            assertEquals(
                "${tool.name}",
                GateResult.NeedConfirm(tool.name),
                PrivilegeGate.check(tool, false, degraded, mapOf("fullText" to "true")),
            )
            assertFalse("${tool.name}", PrivilegeGate.canExecute(tool, false, degraded, mapOf("fullText" to "true")))
            // 全文確認後放行。
            assertTrue("${tool.name}", PrivilegeGate.canExecute(tool, true, degraded, mapOf("fullText" to "true")))
        }
    }
}
