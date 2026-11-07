package dev.librepocket.privilege.github

import dev.librepocket.shell.PrivilegeAuditLog
import dev.librepocket.shell.ShellDeny
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 5 B2 雙映射一致性（僅測試，不合併實作）：
 * `ElevatedBinaryResult.verdict()`（二進位側）與
 * `PrivilegeAuditLog.verdictName()`（經 [toShellResult] 的文字側）
 * 須手動同步為同四碼（`OK / DENY / TIMEOUT / FAILED`）。
 * 遍歷全變體斷言一致；新增分支兩處未同步即紅。
 */
class ElevatedVerdictMappingTest {

    private fun assertConsistent(result: ElevatedBinaryResult, expected: String) {
        assertEquals(expected, result.verdict())
        assertEquals(expected, PrivilegeAuditLog.verdictName(result.toShellResult()))
        assertEquals(result.verdict(), PrivilegeAuditLog.verdictName(result.toShellResult()))
    }

    @Test fun okMapsToOk() {
        assertConsistent(
            ElevatedBinaryResult.Ok("ok".toByteArray(), ByteArray(0), 0, false),
            "OK",
        )
    }

    @Test fun okTruncatedStillOk() {
        // Phase 4 決定：OK_TRUNCATED 不引入，truncated-Ok 仍 OK。
        assertConsistent(
            ElevatedBinaryResult.Ok("part".toByteArray(), ByteArray(0), 0, true),
            "OK",
        )
    }

    @Test fun okCleanupIncompleteStillOk() {
        assertConsistent(
            ElevatedBinaryResult.Ok("ok".toByteArray(), ByteArray(0), 0, false, true),
            "OK",
        )
    }

    @Test fun deniedMapsToDeny() {
        assertConsistent(
            ElevatedBinaryResult.Denied(ShellDeny.QUOTA_EXCEEDED, "quota"),
            "DENY",
        )
    }

    @Test fun timedOutMapsToTimeout() {
        assertConsistent(
            ElevatedBinaryResult.TimedOut("p".toByteArray(), ByteArray(0), 1_000L, false),
            "TIMEOUT",
        )
    }

    @Test fun tailCancelTimedOutMapsToTimeout() {
        // R2-PhaseC P2-2：tail 取消／超時升級的 TimedOut（攜 partial＋truncated＋
        // cleanupIncomplete，不再是 Ok）雙映射同為 TIMEOUT（審計非 OK）。
        assertConsistent(
            ElevatedBinaryResult.TimedOut(
                "partial".toByteArray(),
                ByteArray(0),
                10_000L,
                true,
                true,
            ),
            "TIMEOUT",
        )
    }

    @Test fun failedMapsToFailed() {
        assertConsistent(
            ElevatedBinaryResult.Failed("boom"),
            "FAILED",
        )
    }
}
