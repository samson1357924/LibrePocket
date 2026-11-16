package dev.librepocket.privilege.github

import dev.librepocket.shell.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `ElevatedBinaryResult.toShellResult()` 橋接釘：僅 [ElevatedBinaryResult.TimedOut] 的 `cleanupIncomplete` 穿透；Denied／Failed 無此欄（型別上不可能，不釘）。 */
class ElevatedShellResultBridgeTest {

    @Test fun timedOutCleanupIncompleteTrueSurvivesToShellResult() {
        val r = ElevatedBinaryResult.TimedOut(
            "partial-out".toByteArray(),
            "partial-err".toByteArray(),
            1_000L,
            true,
            true,
        ).toShellResult()
        assertTrue("$r", r is ShellResult.TimedOut)
        r as ShellResult.TimedOut
        assertTrue("cleanupIncomplete=true must survive the text bridge", r.cleanupIncomplete)
        assertEquals(1_000L, r.timeoutMs)
        assertEquals("partial-out", r.partialStdout)
        assertEquals("partial-err", r.partialStderr)
        assertTrue(r.truncated)
    }

    @Test fun timedOutCleanupIncompleteFalseSurvivesToShellResult() {
        val r = ElevatedBinaryResult.TimedOut(
            ByteArray(0),
            ByteArray(0),
            250L,
            false,
            false,
        ).toShellResult()
        assertTrue("$r", r is ShellResult.TimedOut)
        r as ShellResult.TimedOut
        assertFalse("cleanupIncomplete=false must survive the text bridge", r.cleanupIncomplete)
        assertEquals(250L, r.timeoutMs)
        assertFalse(r.truncated)
    }
}
