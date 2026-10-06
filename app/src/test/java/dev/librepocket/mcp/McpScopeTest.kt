package dev.librepocket.mcp

import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.SideEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D03 範圍測試（BACKLOG D03）：第三方工具同走投影 + 確認，預設 WRITE。
 */
class McpScopeTest {

    private val open = McpServerConfig(id = "s1", baseUrl = "https://mcp.example.com")

    @Test fun defaultSideEffectIsWrite() {
        val def = McpScope.toToolDef("s1", "search")
        assertEquals(SideEffect.WRITE, def.sideEffect)
        assertEquals("mcp.s1.search", def.name)
    }

    @Test fun enabledToolProjectsNative() {
        val p = McpScope.project(open, "search")
        assertEquals(CapabilityLevel.NATIVE, p.level)
        assertEquals(null, p.reason)
    }

    @Test fun perToolDisableProjectsUserDisabled() {
        val cfg = open.copy(enabledTools = mapOf("search" to false))
        val p = McpScope.project(cfg, "search")
        assertEquals(CapabilityLevel.UNAVAILABLE, p.level)
        assertEquals(DenyReason.USER_DISABLED, p.reason)
        // 同伺服器其他工具不受影響。
        assertEquals(CapabilityLevel.NATIVE, McpScope.project(cfg, "other").level)
    }

    @Test fun serverDisabledProjectsUserDisabled() {
        val cfg = open.copy(enabled = false)
        val p = McpScope.project(cfg, "search")
        assertEquals(CapabilityLevel.UNAVAILABLE, p.level)
        assertEquals(DenyReason.USER_DISABLED, p.reason)
    }

    @Test fun userSwitchOffProjectsUserDisabled() {
        val ctx = ProjectionContext(userSwitches = mapOf("mcp.s1.search" to false))
        val p = McpScope.project(open, "search", ctx)
        assertEquals(CapabilityLevel.UNAVAILABLE, p.level)
        assertEquals(DenyReason.USER_DISABLED, p.reason)
    }

    @Test fun writeNeedsConfirmByDefault() {
        val p = McpScope.project(open, "search")
        val need = McpScope.check(open, "search", false, p)
        assertEquals(McpGate.NeedConfirm("mcp.s1.search"), need)
        assertFalse(McpScope.canExecute(open, "search", false, p))
        assertTrue(McpScope.canExecute(open, "search", true, p))
    }

    @Test fun readNeedsNoConfirm() {
        val p = McpScope.project(open, "lookup", sideEffect = SideEffect.READ)
        assertTrue(McpScope.canExecute(open, "lookup", false, p, SideEffect.READ))
    }

    @Test fun privilegedNeedsConfirm() {
        val p = McpScope.project(open, "exec", sideEffect = SideEffect.PRIVILEGED)
        assertEquals(
            McpGate.NeedConfirm("mcp.s1.exec"),
            McpScope.check(open, "exec", false, p, SideEffect.PRIVILEGED),
        )
        assertTrue(McpScope.canExecute(open, "exec", true, p, SideEffect.PRIVILEGED))
    }

    @Test fun unavailableDeniedEvenWhenConfirmed() {
        val cfg = open.copy(enabledTools = mapOf("search" to false))
        val p = McpScope.project(cfg, "search")
        assertEquals(
            McpGate.Denied(DenyReason.USER_DISABLED),
            McpScope.check(cfg, "search", true, p),
        )
        assertFalse(McpScope.canExecute(cfg, "search", true, p))
    }

    @Test fun projectionIsPureFunction() {
        assertEquals(McpScope.project(open, "a"), McpScope.project(open, "a"))
    }

    @Test fun timeoutClampedTo15to30s() {
        assertEquals(15_000L, McpServerConfig(id = "x", baseUrl = "https://h", timeoutMs = 1_000L).effectiveTimeoutMs())
        assertEquals(30_000L, McpServerConfig(id = "x", baseUrl = "https://h", timeoutMs = 120_000L).effectiveTimeoutMs())
        assertEquals(20_000L, McpServerConfig(id = "x", baseUrl = "https://h").effectiveTimeoutMs())
        assertEquals(15_000L, McpTimeouts.MIN_MS)
        assertEquals(30_000L, McpTimeouts.MAX_MS)
    }

    @Test fun auditMapsContainNoArgsOrToken() {
        val call = McpCallResult("s1", "search", McpStatus.OK, 12L, """{"data":"s3cr3t"}""")
        val audit = call.auditMap().toString()
        assertTrue(audit.contains("s1") && audit.contains("search"))
        assertFalse(audit.contains("s3cr3t"))
        val list = McpListResult("s1", emptyList(), McpStatus.OK, 3L)
        assertTrue(list.auditMap().containsKey("serverId"))
    }

    @Test fun switchKeyShape() {
        assertEquals("mcp.s1.search", McpScope.switchKey("s1", "search"))
    }
}
