package dev.librepocket.privilege.github

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage 3b-final 驗收：quarantine 登記失敗（registrar seam）的 fail-closed 槽保留語義。
 *
 * 範圍警戒：
 * - 注入 [ElevatedProcessIo.quarantineRegistrar] 拋出（RuntimeException／OutOfMemoryError），
 *   直接斷言 [ElevatedProcessIo.releaseCleanupSlotAfterVerify] 在登記失敗時**保留**槽、
 *   遞增 [ElevatedCleanupTracker.quarantineRegistrationFailedCount]、准入續拒（BACKLOG_FULL）；
 * - 補 pin verified=true 路徑（slotHeld=true → 釋放、slotHeld=false → 不動）與
 *   `===`-dedup no-op 路徑（已登記 handle 重入 helper → 釋放恰好一次、不新增計數）；
 * - drain 級整合 pin：`elevated-cleanup` 起線程 OOM＋registrar 拋 → 二次登記與
 *   verify 臂 helper 各計 1 次失敗、槽保留、`cleanupIncomplete=true`；另補三臂
 *   registrar-throw pin：kill 臂／preflight 臂（timeoutMs=0L 確定性入口過期）／
 *   `elevated-stdout` 起線程失敗（`cleanupOnThreadStartFailureInner`）臂；
 * - 純 JVM、零真機、零 su、零 binder；排序一律同步呼叫（helper 直調）。
 *
 * 隔離：@Before/@After 強制還原預設 registrar（`{ ElevatedCleanupTracker.quarantineUnverified(it) }`），
 * 清 quarantine／槽／active／breaker，並斷言本類零洩漏。
 */
class ElevatedCleanupTrackerRegistrationFailureTest {

    private var floorBefore: Int = 0

    @Before fun setUpIsolation() {
        assertFalse(
            "#136-B3 gate must stay closed",
            PrivilegeActivation.ELEVATED_RUNNERS_READY,
        )
        floorBefore = ElevatedCleanupTracker.releaseFloorHits()
        ElevatedProcessIo.quarantineRegistrar = { ElevatedCleanupTracker.quarantineUnverified(it) }
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        pollPendingTo(0)
    }

    @After fun tearDownIsolation() {
        ElevatedProcessIo.quarantineRegistrar = { ElevatedCleanupTracker.quarantineUnverified(it) }
        val leakedOutstanding = ElevatedCleanupTracker.outstanding()
        val leakedActive = ElevatedCleanupTracker.activeCalls()
        val leakedQuarantine = ElevatedCleanupTracker.quarantinedCount()
        ElevatedCleanupTracker.clearUnverified()
        while (ElevatedCleanupTracker.outstanding() > 0) {
            ElevatedCleanupTracker.releaseSlot()
        }
        while (ElevatedCleanupTracker.activeCalls() > 0) {
            ElevatedCleanupTracker.leaveCall()
        }
        pollOutstandingTo(0)
        pollActiveTo(0)
        pollPendingTo(0)
        ElevatedCleanupTracker.noteClean()
        assertEquals("test leaked outstanding slots", 0, leakedOutstanding)
        assertEquals("test leaked active calls", 0, leakedActive)
        assertEquals("test leaked quarantined processes", 0, leakedQuarantine)
        assertEquals("release floor must not be hit during this test", floorBefore, ElevatedCleanupTracker.releaseFloorHits())
    }

    private fun pollOutstandingTo(expected: Int, boundMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + boundMs
        while (ElevatedCleanupTracker.outstanding() != expected) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError(
                    "outstanding=${ElevatedCleanupTracker.outstanding()} expected=$expected",
                )
            }
            Thread.sleep(20L)
        }
    }

    private fun pollActiveTo(expected: Int, boundMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + boundMs
        while (ElevatedCleanupTracker.activeCalls() != expected) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError(
                    "active=${ElevatedCleanupTracker.activeCalls()} expected=$expected",
                )
            }
            Thread.sleep(20L)
        }
    }

    private fun pollPendingTo(expected: Int, boundMs: Long = 15_000L) {
        val deadline = System.currentTimeMillis() + boundMs
        while (ElevatedCleanupTracker.sweepPendingCount() != expected) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError(
                    "pending=${ElevatedCleanupTracker.sweepPendingCount()} expected=$expected",
                )
            }
            Thread.sleep(20L)
        }
    }

    /** destroy no-op＋waitFor 恒 false：killer 調了等於沒調，驗證必失敗（drain 整合 pin 用）。 */
    private class NoOpHangProcess : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = false
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }

    // ---- (a) verified=false＋registrar 拋 → 槽保留、計數 +1、准入 BACKLOG_FULL ----

    @Test fun registrationFailureKeepsSlotAndCountsRuntimeException() {
        val p = FakeProcess("ok".toByteArray())
        try {
            assertTrue("prefill slot must grant", ElevatedCleanupTracker.acquireSlot())
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            val before = ElevatedCleanupTracker.quarantineRegistrationFailedCount()
            ElevatedProcessIo.quarantineRegistrar = { throw RuntimeException("registrar boom") }
            ElevatedProcessIo.releaseCleanupSlotAfterVerify(p, verified = false, slotHeld = true)
            // 登記失敗 ⇒ 槽保留：outstanding 不變、quarantine 空（登記從未落地）、計數 +1。
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
            assertEquals(before + 1, ElevatedCleanupTracker.quarantineRegistrationFailedCount())
            assertEquals(
                ElevatedCleanupTracker.Admission.BACKLOG_FULL,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            ElevatedProcessIo.quarantineRegistrar = { ElevatedCleanupTracker.quarantineUnverified(it) }
        }
        // 還原 registrar 後再呼叫：登記成功 ⇒ 釋放恰好一次、outstanding==0。
        val pRecovery = p
        ElevatedProcessIo.releaseCleanupSlotAfterVerify(pRecovery, verified = false, slotHeld = true)
        assertEquals(0, ElevatedCleanupTracker.outstanding())
        assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
        // 清 quarantine 後 admission 恢復 OK。
        ElevatedCleanupTracker.clearUnverified()
        assertEquals(
            ElevatedCleanupTracker.Admission.OK,
            ElevatedCleanupTracker.tryEnterReserved(),
        )
        assertEquals(1, ElevatedCleanupTracker.activeCalls())
        ElevatedCleanupTracker.leaveCall()
        pollActiveTo(0)
    }

    @Test fun registrationFailureKeepsSlotAndCountsOutOfMemoryError() {
        val p = FakeProcess("ok".toByteArray())
        try {
            assertTrue("prefill slot must grant", ElevatedCleanupTracker.acquireSlot())
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            val before = ElevatedCleanupTracker.quarantineRegistrationFailedCount()
            ElevatedProcessIo.quarantineRegistrar = { throw OutOfMemoryError("registrar OOM") }
            ElevatedProcessIo.releaseCleanupSlotAfterVerify(p, verified = false, slotHeld = true)
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
            assertEquals(before + 1, ElevatedCleanupTracker.quarantineRegistrationFailedCount())
            assertEquals(
                ElevatedCleanupTracker.Admission.BACKLOG_FULL,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            // 第二次登記仍拋：槽仍保留（不二釋），計數再 +1。
            ElevatedProcessIo.releaseCleanupSlotAfterVerify(p, verified = false, slotHeld = true)
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
            assertEquals(before + 2, ElevatedCleanupTracker.quarantineRegistrationFailedCount())
        } finally {
            ElevatedProcessIo.quarantineRegistrar = { ElevatedCleanupTracker.quarantineUnverified(it) }
        }
        // 還原 registrar 後再呼叫：登記成功 ⇒ 釋放、outstanding==0、計數不再增。
        val before = ElevatedCleanupTracker.quarantineRegistrationFailedCount()
        ElevatedProcessIo.releaseCleanupSlotAfterVerify(p, verified = false, slotHeld = true)
        assertEquals(0, ElevatedCleanupTracker.outstanding())
        assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
        assertEquals(before, ElevatedCleanupTracker.quarantineRegistrationFailedCount())
        // 清 quarantine 殘留（本測試有意登記），保持 tearDown 零洩漏斷言有效。
        ElevatedCleanupTracker.clearUnverified()
    }

    // ---- (b) verified=true：slotHeld=true → 釋放；slotHeld=false → 不動 ----

    @Test fun verifiedTrueWithSlotHeldReleasesExactlyOnce() {
        val p = FakeProcess("ok".toByteArray())
        val floorBefore = ElevatedCleanupTracker.releaseFloorHits()
        assertTrue(ElevatedCleanupTracker.acquireSlot())
        assertEquals(1, ElevatedCleanupTracker.outstanding())
        ElevatedProcessIo.releaseCleanupSlotAfterVerify(p, verified = true, slotHeld = true)
        assertEquals(0, ElevatedCleanupTracker.outstanding())
        // 恰好一次：floor 吸收型 double-release 不得發生。
        assertEquals(floorBefore, ElevatedCleanupTracker.releaseFloorHits())
        // verified=true 不碰登記：quarantine 空。
        assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
    }

    @Test fun verifiedTrueWithoutSlotHeldLeavesOutstandingUnchanged() {
        val p = FakeProcess("ok".toByteArray())
        val before = ElevatedCleanupTracker.outstanding()
        ElevatedProcessIo.releaseCleanupSlotAfterVerify(p, verified = true, slotHeld = false)
        assertEquals(before, ElevatedCleanupTracker.outstanding())
        assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
    }

    /** (a2) dedup pin：handle 已登記（===-dedup no-op）→ registered=true → 釋放恰好一次、不計失敗。 */
    @Test fun verifiedFalseWithDedupHitReleasesAndKeepsRegistration() {
        val p = FakeProcess("ok".toByteArray())
        ElevatedCleanupTracker.quarantineUnverified(p) // 手動首登記
        assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
        assertTrue(ElevatedCleanupTracker.acquireSlot())
        val before = ElevatedCleanupTracker.quarantineRegistrationFailedCount()
        ElevatedProcessIo.releaseCleanupSlotAfterVerify(p, verified = false, slotHeld = true)
        // dedup no-op（登記冪等）仍算 registered ⇒ 釋放恰好一次。
        assertEquals(0, ElevatedCleanupTracker.outstanding())
        assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
        assertEquals(before, ElevatedCleanupTracker.quarantineRegistrationFailedCount())
        // 清 quarantine 殘留（本測試有意登記），保持 tearDown 零洩漏斷言有效。
        ElevatedCleanupTracker.clearUnverified()
    }

    // ---- (c) drain 整合 pin：elevated-cleanup 起線程 OOM＋registrar 拋 → fail-closed 全鏈 ----

    @Test fun drainCleanupThreadStartFailureWithThrowingRegistrarKeepsSlotAndCounts() {
        val hanger = NoOpHangProcess()
        val before = ElevatedCleanupTracker.quarantineRegistrationFailedCount()
        try {
            ElevatedProcessIo.quarantineRegistrar = { throw RuntimeException("registrar boom") }
            val d = ElevatedProcessIo.drainWithThreadStarter(
                process = hanger,
                timeoutMs = 2_000L,
                killer = { it.destroy() },
                isCancelled = { false },
                startThread = { t ->
                    if (t.name == "elevated-cleanup") throw OutOfMemoryError("simulated OOM on elevated-cleanup")
                    t.start()
                },
            )
            // 起線程失敗臂二次登記拋 → +1；post-kill verify=false（NoOpHangProcess 恒 false）
            // → helper 登記再拋 → +1、槽保留。killNeverAttempted 亦遞增（見 tracker）。
            assertEquals(before + 2, ElevatedCleanupTracker.quarantineRegistrationFailedCount())
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
            assertTrue("drain must flag cleanupIncomplete", d.cleanupIncomplete)
            assertTrue(
                "admission must stay BACKLOG_FULL while the slot is held",
                ElevatedCleanupTracker.tryEnterReserved() == ElevatedCleanupTracker.Admission.BACKLOG_FULL,
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            // 本測試有意保留 1 槽（fail-closed 語義之核心 pin）：測畢自行釋放，
            // 保持 tearDown 零洩漏斷言有效。
            ElevatedCleanupTracker.releaseSlot()
            pollOutstandingTo(0)
        } finally {
            // 還原預設 registrar；殘留 quarantine／槽／breaker 由測畢自釋＋@After 收斂。
            ElevatedProcessIo.quarantineRegistrar = { ElevatedCleanupTracker.quarantineUnverified(it) }
        }
    }

    // ---- (c2) drain 整合 pin：kill 臂／preflight 臂／elevated-stdout 起線程失敗臂 ----

    @Test fun drainKillPathVerifyFalseWithThrowingRegistrarKeepsSlotFailClosed() {
        val hanger = NoOpHangProcess()
        val before = ElevatedCleanupTracker.quarantineRegistrationFailedCount()
        try {
            ElevatedProcessIo.quarantineRegistrar = { throw RuntimeException("registrar boom") }
            val d = ElevatedProcessIo.drain(
                process = hanger,
                timeoutMs = 300L,
                killer = { it.destroy() },
                isCancelled = { false },
            )
            // post-kill verify=false（NoOpHangProcess 恒 false）→ helper 登記拋 ⇒ +1、槽保留。
            assertEquals(before + 1, ElevatedCleanupTracker.quarantineRegistrationFailedCount())
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
            assertTrue("kill-path drain must flag cleanupIncomplete", d.cleanupIncomplete)
            assertTrue("waitFor never completes ⇒ timedOut must be true", d.timedOut)
            val admission = ElevatedCleanupTracker.tryEnterReserved()
            if (admission == ElevatedCleanupTracker.Admission.OK) {
                // bug path（OK）才可能佔席：立即歸還，避免洩漏 active 席。
                ElevatedCleanupTracker.leaveCall()
            }
            assertEquals(
                "admission must stay BACKLOG_FULL while the slot is held",
                ElevatedCleanupTracker.Admission.BACKLOG_FULL,
                admission,
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            ElevatedProcessIo.quarantineRegistrar = { ElevatedCleanupTracker.quarantineUnverified(it) }
        }
        // 還原 registrar 後重試 helper（同 handle 重登、冪等）：登記成功 ⇒ 釋放恰好一次。
        ElevatedProcessIo.releaseCleanupSlotAfterVerify(hanger, verified = false, slotHeld = true)
        assertEquals(0, ElevatedCleanupTracker.outstanding())
        assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
        assertEquals(before + 1, ElevatedCleanupTracker.quarantineRegistrationFailedCount())
        // 恰好一次：floor 零命中。
        assertEquals(floorBefore, ElevatedCleanupTracker.releaseFloorHits())
        pollOutstandingTo(0)
        // 本測試有意登記 1 筆 quarantine：清殘留保持 tearDown 零洩漏斷言有效。
        ElevatedCleanupTracker.clearUnverified()
    }

    @Test fun drainPreflightVerifyFalseWithThrowingRegistrarKeepsSlotFailClosed() {
        // timeoutMs=0L：computeDeadlineNanos(0L, now)==now，isExpired 以
        // (deadline - nowNanos()).coerceAtLeast(0L) <= 0L 判定——入口即過期，
        // 確定性選中 preflight 臂（不靠時間窗口猜調度）。
        val hanger = NoOpHangProcess()
        val before = ElevatedCleanupTracker.quarantineRegistrationFailedCount()
        try {
            ElevatedProcessIo.quarantineRegistrar = { throw RuntimeException("registrar boom") }
            val d = ElevatedProcessIo.drain(
                process = hanger,
                timeoutMs = 0L,
                killer = { it.destroy() },
                isCancelled = { false },
            )
            // preflight 臂 post-kill verify=false → helper 登記拋 ⇒ +1、槽保留；
            // preflight 固定回 timedOut=true。
            assertEquals(before + 1, ElevatedCleanupTracker.quarantineRegistrationFailedCount())
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
            assertTrue("preflight drain must flag cleanupIncomplete", d.cleanupIncomplete)
            assertTrue("preflight arm always returns timedOut=true", d.timedOut)
            val admission = ElevatedCleanupTracker.tryEnterReserved()
            if (admission == ElevatedCleanupTracker.Admission.OK) {
                ElevatedCleanupTracker.leaveCall()
            }
            assertEquals(
                "admission must stay BACKLOG_FULL while the slot is held",
                ElevatedCleanupTracker.Admission.BACKLOG_FULL,
                admission,
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            ElevatedProcessIo.quarantineRegistrar = { ElevatedCleanupTracker.quarantineUnverified(it) }
        }
        // 還原 registrar 後重試 helper：登記成功 ⇒ 釋放恰好一次。
        ElevatedProcessIo.releaseCleanupSlotAfterVerify(hanger, verified = false, slotHeld = true)
        assertEquals(0, ElevatedCleanupTracker.outstanding())
        assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
        assertEquals(before + 1, ElevatedCleanupTracker.quarantineRegistrationFailedCount())
        assertEquals(floorBefore, ElevatedCleanupTracker.releaseFloorHits())
        pollOutstandingTo(0)
        ElevatedCleanupTracker.clearUnverified()
    }

    @Test fun drainAbortedArmVerifyFalseWithThrowingRegistrarKeepsSlotFailClosed() {
        val hanger = NoOpHangProcess()
        val before = ElevatedCleanupTracker.quarantineRegistrationFailedCount()
        try {
            ElevatedProcessIo.quarantineRegistrar = { throw RuntimeException("registrar boom") }
            // 只讓 elevated-stdout gobbler 起線程失敗（stdin-closer／elevated-cleanup
            // 照常起）：drain 收斂至 cleanupOnThreadStartFailure（Inner 臂），
            // post-kill verify=false → helper 登記拋 ⇒ +1、槽保留。
            val d = ElevatedProcessIo.drainWithThreadStarter(
                process = hanger,
                timeoutMs = 300L,
                killer = { it.destroy() },
                isCancelled = { false },
                startThread = { t ->
                    if (t.name == "elevated-stdout") {
                        throw RuntimeException("simulated gobbler start failure")
                    } else {
                        t.start()
                    }
                },
            )
            // Inner 臂實值（非 drainFailClosedDrained 兜底）：timedOut=false、readFailed=true、
            // tailIncomplete=true、cleanupIncomplete=true；untrackedResidual 僅由
            // saturation（acquireSlotOrQuarantine 敗）置 true——此處未飽和 ⇒ false。
            assertTrue("aborted arm must flag cleanupIncomplete", d.cleanupIncomplete)
            assertFalse("aborted arm is local resource exhaustion, not budget ⇒ timedOut=false", d.timedOut)
            assertTrue("aborted arm must flag readFailed", d.readFailed)
            assertTrue("aborted arm must flag tailIncomplete", d.tailIncomplete)
            assertFalse("inner arm only sets untrackedResidual on saturation", d.untrackedResidual)
            assertEquals(before + 1, ElevatedCleanupTracker.quarantineRegistrationFailedCount())
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
            val admission = ElevatedCleanupTracker.tryEnterReserved()
            if (admission == ElevatedCleanupTracker.Admission.OK) {
                ElevatedCleanupTracker.leaveCall()
            }
            assertEquals(
                "admission must stay BACKLOG_FULL while the slot is held",
                ElevatedCleanupTracker.Admission.BACKLOG_FULL,
                admission,
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            ElevatedProcessIo.quarantineRegistrar = { ElevatedCleanupTracker.quarantineUnverified(it) }
        }
        // 還原 registrar 後重試 helper：登記成功 ⇒ 釋放恰好一次。
        ElevatedProcessIo.releaseCleanupSlotAfterVerify(hanger, verified = false, slotHeld = true)
        assertEquals(0, ElevatedCleanupTracker.outstanding())
        assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
        assertEquals(before + 1, ElevatedCleanupTracker.quarantineRegistrationFailedCount())
        assertEquals(floorBefore, ElevatedCleanupTracker.releaseFloorHits())
        pollOutstandingTo(0)
        ElevatedCleanupTracker.clearUnverified()
    }

    // ---- (d) counter wiring：@VisibleForTesting reader 直接讀數＋note 單調 +1 ----

    @Test fun quarantineRegistrationFailedCountReadsAndIncrementsMonotonically() {
        val before = ElevatedCleanupTracker.quarantineRegistrationFailedCount()
        ElevatedCleanupTracker.noteQuarantineRegistrationFailed()
        assertEquals(before + 1, ElevatedCleanupTracker.quarantineRegistrationFailedCount())
        ElevatedCleanupTracker.noteQuarantineRegistrationFailed()
        assertEquals(before + 2, ElevatedCleanupTracker.quarantineRegistrationFailedCount())
    }
}
