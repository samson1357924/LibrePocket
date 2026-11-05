package dev.librepocket.privilege.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AB1 tracker 直接單測（owner 新 P1-A＋P1-B 順手補；核實缺失後新增）：
 * 併發上限／殘留飽和／熔斷／歸零／封頂。全域計數器隔離（首尾 noteClean＋
 * outstanding 歸零輪詢），零真機、零 su、零 binder。
 *
 * 核實結論（駁回不編碼項，見對應既有測試）：
 * - `activationOffDeniesWithoutSpawn` 已覆蓋（`ElevatedRunnerGrantTest`）；
 * - `truncatedOkYieldsNull` 已覆蓋（`RootCaptureTest`）；
 * - grant 翻轉已覆蓋（`ElevatedRunnerGrantTest` 的 Invalid／翻轉／全鏈路）；
 * - AA1 drain 直測已覆蓋（`ElevatedRunnerZ1Test`：blocked destroy／close、
 *   success 阻塞 stdin、8s 永久 stall）。
 */
class ElevatedCleanupTrackerTest {

    private fun pollOutstandingTo(expected: Int, boundMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + boundMs
        while (ElevatedCleanupTracker.outstanding() != expected) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError(
                    "outstanding=${ElevatedCleanupTracker.outstanding()} expected=$expected",
                )
            }
            Thread.sleep(50L)
        }
    }

    @Test fun constantsAreFour() {
        assertEquals(4, ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS)
        assertEquals(4, ElevatedCleanupTracker.MAX_CONSECUTIVE_UNCLEAN)
        assertEquals(4, ElevatedCleanupTracker.MAX_CONCURRENT_CALLS)
    }

    @Test fun concurrentCallsAdmitFourThenFloorAtZero() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        assertEquals(0, ElevatedCleanupTracker.activeCalls())
        try {
            repeat(4) { assertTrue(ElevatedCleanupTracker.tryEnterCall()) }
            assertEquals(4, ElevatedCleanupTracker.activeCalls())
            assertFalse("5th must be refused", ElevatedCleanupTracker.tryEnterCall())
            assertEquals(4, ElevatedCleanupTracker.activeCalls())
            repeat(4) { ElevatedCleanupTracker.leaveCall() }
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            // floor：未進入而釋放不變負。
            ElevatedCleanupTracker.leaveCall()
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            repeat(4) { ElevatedCleanupTracker.leaveCall() }
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
        }
        assertEquals(0, ElevatedCleanupTracker.activeCalls())
    }

    @Test fun outstandingSaturatesAndReleases() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        try {
            repeat(4) { ElevatedCleanupTracker.acquireSlot() }
            assertEquals(4, ElevatedCleanupTracker.outstanding())
            assertTrue(ElevatedCleanupTracker.isSaturated())
            repeat(4) { ElevatedCleanupTracker.releaseSlot() }
            pollOutstandingTo(0)
            assertFalse(ElevatedCleanupTracker.isSaturated())
            // floor：未佔而釋放不變負。
            ElevatedCleanupTracker.releaseSlot()
            assertEquals(0, ElevatedCleanupTracker.outstanding())
        } finally {
            repeat(4) { ElevatedCleanupTracker.releaseSlot() }
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
        }
    }

    @Test fun consecutiveBreakerTripsResetsAndCaps() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        try {
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            repeat(3) { ElevatedCleanupTracker.noteUnclean() }
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            ElevatedCleanupTracker.noteUnclean()
            assertTrue(ElevatedCleanupTracker.isBreakerOpen())
            // 封頂：重複累計不溢出（仍 open，單次 noteClean 即關）。
            repeat(100) { ElevatedCleanupTracker.noteUnclean() }
            assertTrue(ElevatedCleanupTracker.isBreakerOpen())
            ElevatedCleanupTracker.noteClean()
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
        } finally {
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
        }
    }
}
