package dev.librepocket.router

import dev.librepocket.shell.DenyingElevatedRunner
import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.PrivilegeAuditLog
import dev.librepocket.shell.ShellResult
import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.Projection
import dev.librepocket.tool.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3 提權執行分派測試（BACKLOG D09，矩陣 §3/§4）：
 * 先四參門禁（含 reasonCode），Allowed 才執行審計版；
 * 門禁未過不建子進程且同樣寫 DENY 審計（全路徑留痕）。
 */
class ElevatedDispatchTest {

    private val tool = ToolRegistry.find("shell.elevated")!!
    private val native = Projection(tool.name, CapabilityLevel.NATIVE)

    @Test fun blockedWithoutConfirmNeverSpawnsButAudits() {
        val audit = PrivilegeAuditLog()
        val r = ElevatedDispatch.execute(
            tool = tool,
            confirmed = false,
            projection = native,
            request = ElevatedRequest(listOf("id"), reasonCode = "D09-T"),
            runner = DenyingElevatedRunner(),
            audit = audit,
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }

    @Test fun blockedWithoutReasonCodeNeverSpawnsButAudits() {
        val audit = PrivilegeAuditLog()
        val r = ElevatedDispatch.execute(
            tool = tool,
            confirmed = true,
            projection = native,
            request = ElevatedRequest(listOf("id"), reasonCode = "  "),
            runner = DenyingElevatedRunner(),
            audit = audit,
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(1, audit.size())
    }

    @Test fun allowedWithConfirmAndReasonCodeExecutesAudited() {
        val audit = PrivilegeAuditLog()
        val r = ElevatedDispatch.execute(
            tool = tool,
            confirmed = true,
            projection = native,
            request = ElevatedRequest(listOf("id"), reasonCode = "D09-T"),
            runner = DenyingElevatedRunner(),
            audit = audit,
        )
        // 佔位 runner 拒絕，但門禁已過：執行側 DENY 同樣留痕。
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }
}
