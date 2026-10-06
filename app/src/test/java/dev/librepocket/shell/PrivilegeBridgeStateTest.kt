package dev.librepocket.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3 提權橋一鍵收回測試（BACKLOG D09，矩陣 §3）：
 * [PrivilegeBridgeState.revoke] 原子完成清審計表 + 關
 * `privilege_bridge` 開關 + 撤橋接授權（設定頁接線的語義保證）。
 */
class PrivilegeBridgeStateTest {

    @Test fun revokeClearsAuditAndDropsBridgeGrant() {
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        repeat(2) { state.audit.record(listOf("id"), "D09-$it", "DENY") }
        assertEquals(2, state.auditCount())

        val cleared = state.revoke()

        assertEquals(2, cleared)
        assertEquals(0, state.auditCount())
        assertTrue(state.audit.countByVerdict().isEmpty())
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
    }

    @Test fun revokeOnEmptyStateIsNoop() {
        val state = PrivilegeBridgeState()
        assertEquals(0, state.revoke())
        assertFalse(state.bridgeSwitchOn)
        assertFalse(state.bridgeGranted)
    }

    @Test fun auditLogIsBounded() {
        // MINOR-15：審計表有界，超過上限丟棄最舊。
        val audit = PrivilegeAuditLog()
        repeat(PrivilegeAuditLog.MAX_RECORDS + 10) {
            audit.record(listOf("id"), "D09-$it", "DENY")
        }
        assertEquals(PrivilegeAuditLog.MAX_RECORDS, audit.size())
        val seqIds = audit.snapshot().map { it.seqId }
        assertTrue(seqIds.none { it == "priv-1" })
        assertTrue(seqIds.last() == "priv-${PrivilegeAuditLog.MAX_RECORDS + 10}")
    }
}
