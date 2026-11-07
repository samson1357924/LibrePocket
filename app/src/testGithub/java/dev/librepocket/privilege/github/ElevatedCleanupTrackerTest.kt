package dev.librepocket.privilege.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
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

    // Round-3 Phase E（P1-1）：quarantine 為跨用例全域閂，首尾清空（計數器隔離
    // 仍由各用例首尾 noteClean＋歸零輪詢承擔，此處只補新閂）。
    @Before fun setUpQuarantineIsolation() {
        ElevatedCleanupTracker.clearUnverified()
    }

    @After fun tearDownQuarantineIsolation() {
        ElevatedCleanupTracker.clearUnverified()
    }

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

    @Test fun capacityConstantsMatchSlotDerivation() {
        // P1-4 重推導：單呼叫最壞 7 槽（drain 4＋監督 3，見 SLOTS_PER_CALL KDoc），
        // 總量上限同取 7（任一殘留即擋新呼叫，fail-closed 最保守）。
        assertEquals(7, ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS)
        assertEquals(7, ElevatedCleanupTracker.SLOTS_PER_CALL)
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
            repeat(7) { ElevatedCleanupTracker.acquireSlot() }
            assertEquals(7, ElevatedCleanupTracker.outstanding())
            assertTrue(ElevatedCleanupTracker.isSaturated())
            repeat(7) { ElevatedCleanupTracker.releaseSlot() }
            pollOutstandingTo(0)
            assertFalse(ElevatedCleanupTracker.isSaturated())
            // floor：未佔而釋放不變負。
            ElevatedCleanupTracker.releaseSlot()
            assertEquals(0, ElevatedCleanupTracker.outstanding())
        } finally {
            repeat(7) { ElevatedCleanupTracker.releaseSlot() }
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
        }
    }

    /**
     * reservation 矩陣直測（不經 runner）：驗證 [ElevatedCleanupTracker.tryEnterReserved]
     * 原子預留表。現參數 MAX=7、SLOTS=7 下實效串行＋任一殘留即拒（0+1*7<=7 過，
     * 0+2*7>7 拒，1+7>7 拒），[ElevatedCleanupTracker.Admission.CONCURRENCY_FULL]
     * 保留但實效不可達（第二呼叫即走 BACKLOG_FULL），為未來放寬保留；直測以
     * tryEnterCall 預填 active=4 證該分支仍可達。
     */
    @Test fun reservationMatrixDirect() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        // 隔離 active（前測洩漏即先清）。
        repeat(8) { ElevatedCleanupTracker.leaveCall() }
        assertEquals(0, ElevatedCleanupTracker.activeCalls())
        try {
            // 乾淨態首呼叫放行（0+1*7<=7）。
            assertEquals(
                ElevatedCleanupTracker.Admission.OK,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(1, ElevatedCleanupTracker.activeCalls())
            // 乾淨態次呼叫即預留拒（0+2*7>7 → BACKLOG_FULL，非 CONCURRENCY_FULL）。
            assertEquals(
                ElevatedCleanupTracker.Admission.BACKLOG_FULL,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(1, ElevatedCleanupTracker.activeCalls())
            ElevatedCleanupTracker.leaveCall()
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            // 殘留 1 即拒後續（1+7>7 → BACKLOG_FULL，fail-closed 最保守）。
            ElevatedCleanupTracker.acquireSlot()
            assertEquals(
                ElevatedCleanupTracker.Admission.BACKLOG_FULL,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            ElevatedCleanupTracker.releaseSlot()
            pollOutstandingTo(0)
            // CONCURRENCY_FULL 死分支仍可達：以僅測用 tryEnterCall 預填 active=4。
            repeat(4) { assertTrue(ElevatedCleanupTracker.tryEnterCall()) }
            assertEquals(4, ElevatedCleanupTracker.activeCalls())
            assertEquals(
                ElevatedCleanupTracker.Admission.CONCURRENCY_FULL,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            repeat(4) { ElevatedCleanupTracker.leaveCall() }
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            // backlog 優先：outstanding=7 即 BACKLOG_FULL。
            repeat(7) { ElevatedCleanupTracker.acquireSlot() }
            assertEquals(
                ElevatedCleanupTracker.Admission.BACKLOG_FULL,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            repeat(7) { ElevatedCleanupTracker.releaseSlot() }
            pollOutstandingTo(0)
            // breaker 優先於預留：consecutive=4 即 BREAKER_OPEN。
            repeat(4) { ElevatedCleanupTracker.noteUnclean() }
            assertTrue(ElevatedCleanupTracker.isBreakerOpen())
            assertEquals(
                ElevatedCleanupTracker.Admission.BREAKER_OPEN,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
        } finally {
            repeat(8) { ElevatedCleanupTracker.leaveCall() }
            repeat(8) { ElevatedCleanupTracker.releaseSlot() }
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
        }
        assertEquals(0, ElevatedCleanupTracker.activeCalls())
        assertEquals(0, ElevatedCleanupTracker.outstanding())
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
