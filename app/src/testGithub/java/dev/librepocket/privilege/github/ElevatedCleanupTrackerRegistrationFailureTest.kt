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
        ElevatedCleanupTracker.clearRegistrationFailedStickyForTest()
        pollOutstandingTo(0)
        pollActiveTo(0)
        pollPendingTo(0)
    }

    @After fun tearDownIsolation() {
        ElevatedProcessIo.quarantineRegistrar = { ElevatedCleanupTracker.quarantineUnverified(it) }
        ElevatedCleanupTracker.forceAcquireSlotOrQuarantineThrow = false
        ElevatedProcessIo.forceDrainPostAcquireFailure = false
        ElevatedCleanupTracker.clearRegistrationFailedStickyForTest()
        val leakedOutstanding = ElevatedCleanupTracker.outstanding()
        val leakedActive = ElevatedCleanupTracker.activeCalls()
        val leakedQuarantine = ElevatedCleanupTracker.quarantinedCount()
        ElevatedCleanupTracker.clearUnverified()
        // 有界強制收斂：避免無界 while 在洩漏時卡死 CI；pollOutstandingTo(0) 隨後仍 fail-fast 報錯。
        val forcedDeadline = System.currentTimeMillis() + 5_000L
        while (ElevatedCleanupTracker.outstanding() > 0 && System.currentTimeMillis() < forcedDeadline) {
            ElevatedCleanupTracker.releaseSlot()
        }
        val forcedActiveDeadline = System.currentTimeMillis() + 5_000L
        while (ElevatedCleanupTracker.activeCalls() > 0 && System.currentTimeMillis() < forcedActiveDeadline) {
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

    /**
     * 記錄型假件（preflight 臂判別子）：getInputStream/getErrorStream/getOutputStream
     * 只記錄 requested-ness（三個旗標，不擲出）；destroy no-op；waitFor(任一 timeout) false。
     * preflight 臂不起線程、永不觸碰流；kill 路徑必經 gobbler（getInputStream／getErrorStream）
     * ＋stdin-closer（getOutputStream）——drain 返回前兩 gobbler 已 join，故若臂選擇
     * 迴歸至 kill 路徑，三旗標必為 true（本測試斷言其全為 false，即為正向判別）。
     * （NoOpHangProcess 保留供 kill-path 測試用。）
     */
    private class StreamRecordingProcess : Process() {
        @Volatile var inputRequested = false
        @Volatile var errorRequested = false
        @Volatile var outputRequested = false
        override fun getOutputStream(): OutputStream {
            outputRequested = true
            return ByteArrayOutputStream()
        }
        override fun getInputStream(): InputStream {
            inputRequested = true
            return ByteArrayInputStream(ByteArray(0))
        }
        override fun getErrorStream(): InputStream {
            errorRequested = true
            return ByteArrayInputStream(ByteArray(0))
        }
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
        val hanger = StreamRecordingProcess()
        val before = ElevatedCleanupTracker.quarantineRegistrationFailedCount()
        try {
            ElevatedProcessIo.quarantineRegistrar = { throw RuntimeException("registrar boom") }
            val d = ElevatedProcessIo.drain(
                process = hanger,
                timeoutMs = 0L,
                killer = { it.destroy() },
                isCancelled = { false },
            )
            // preflight 臂不起線程、永不觸碰流：三旗標必 false（正向判別子——
            // 若臂選擇迴歸至 kill 路徑，gobbler／stdin-closer 必經三個 getter，
            // 旗標必 true）。
            assertFalse("preflight must not request input stream", hanger.inputRequested)
            assertFalse("preflight must not request error stream", hanger.errorRequested)
            assertFalse("preflight must not request output stream", hanger.outputRequested)
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
            // tailIncomplete=true、cleanupIncomplete=true；Inner 臂內，untrackedResidual 現由
            // saturation（acquireSlotOrQuarantine 敗）／起線程失敗／acquire 拋三臂同置 true——
            // 此 drain 的 elevated-cleanup 照常佔槽起動 ⇒ false（fallback `drainFailClosedDrained` 無條件置 true，該兜底此處不成立）。
            assertTrue("aborted arm must flag cleanupIncomplete", d.cleanupIncomplete)
            assertFalse("aborted arm is local resource exhaustion, not budget ⇒ timedOut=false", d.timedOut)
            assertTrue("aborted arm must flag readFailed", d.readFailed)
            assertTrue("aborted arm must flag tailIncomplete", d.tailIncomplete)
            assertFalse("untrackedResidual stays false when cleanup worker acquires its slot", d.untrackedResidual)
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

    // ---- (d2) Round-6（#136-B2 IR-P2／D-B3／D-A10）：登記雙失敗 brick — admission 永久密封 ----

    /**
     * D-C1：releaseCleanupSlotAfterVerify 零追蹤 arm（verified=false、slotHeld=false、登記拋）
     * ⇒ quarantineRegistrationFailed +1、sticky 置位、admission 以 REGISTRATION_FAILED 永久拒
     * （gate 1，app 重啟恢復）、activeCalls 零、outstanding 零（無槽可留）。
     */
    @Test fun releaseCleanupSlotAfterVerifyDoubleFailureBricksAdmission() {
        val p = FakeProcess("ok".toByteArray())
        try {
            ElevatedCleanupTracker.clearRegistrationFailedStickyForTest()
            val before = ElevatedCleanupTracker.quarantineRegistrationFailedCount()
            ElevatedProcessIo.quarantineRegistrar = { throw RuntimeException("registrar boom") }
            ElevatedProcessIo.releaseCleanupSlotAfterVerify(p, verified = false, slotHeld = false)
            assertEquals(before + 1, ElevatedCleanupTracker.quarantineRegistrationFailedCount())
            assertTrue("double registration failure must brick admission", ElevatedCleanupTracker.isRegistrationFailedSticky())
            assertEquals(
                ElevatedCleanupTracker.Admission.REGISTRATION_FAILED,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            assertEquals(0, ElevatedCleanupTracker.outstanding())
        } finally {
            ElevatedProcessIo.quarantineRegistrar = { ElevatedCleanupTracker.quarantineUnverified(it) }
            ElevatedCleanupTracker.clearRegistrationFailedStickyForTest()
            ElevatedCleanupTracker.clearUnverified()
        }
    }

    /** D-C1 對照臂：同一 call 但 registrar 成功（dedup 登記）⇒ 不置 brick、sticky 保持 false、
     *  admission 以 QUARANTINE 拒（gate 7），非 REGISTRATION_FAILED。 */
    @Test fun releaseCleanupSlotAfterVerifySucceedingRegistrarDoesNotBrick() {
        val p = FakeProcess("ok".toByteArray())
        try {
            ElevatedCleanupTracker.clearRegistrationFailedStickyForTest()
            val before = ElevatedCleanupTracker.quarantineRegistrationFailedCount()
            ElevatedProcessIo.releaseCleanupSlotAfterVerify(p, verified = false, slotHeld = false)
            assertEquals(before, ElevatedCleanupTracker.quarantineRegistrationFailedCount())
            assertFalse("succeeding registrar must not brick", ElevatedCleanupTracker.isRegistrationFailedSticky())
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            assertEquals(
                ElevatedCleanupTracker.Admission.QUARANTINE,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
        } finally {
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.clearRegistrationFailedStickyForTest()
        }
    }

    /**
     * acquire-throw seam 測試：[ElevatedCleanupTracker.forceAcquireSlotOrQuarantineThrow] 置位
     * ⇒ runCleanupBounded 進 acquire-throw 臂；second-chance 登記再拋 ⇒ sticky brick；
     * caller verify 臂（NoOpHangProcess 恒 verify=false）helper 重試再拋 ⇒ 再計 1 次。
     * 期望：cleanupIncomplete／untrackedResidual／timedOut=true、admission REGISTRATION_FAILED、
     * 槽與 active 零殘留（acquire 從未遞增）。
     */
    @Test fun drainAcquireThrowWithSecondChanceFailureBricksAdmission() {
        val hanger = NoOpHangProcess()
        val before = ElevatedCleanupTracker.quarantineRegistrationFailedCount()
        try {
            ElevatedCleanupTracker.forceAcquireSlotOrQuarantineThrow = true
            ElevatedProcessIo.quarantineRegistrar = { throw RuntimeException("registrar boom") }
            val d = ElevatedProcessIo.drainWithThreadStarter(
                process = hanger,
                timeoutMs = 300L,
                killer = { it.destroy() },
                isCancelled = { false },
            )
            assertTrue("drain must flag cleanupIncomplete", d.cleanupIncomplete)
            assertTrue("drain must flag untrackedResidual", d.untrackedResidual)
            assertTrue("NoOpHangProcess never exits ⇒ timedOut", d.timedOut)
            assertTrue(
                "admission must be bricked after double registration failure",
                ElevatedCleanupTracker.isRegistrationFailedSticky(),
            )
            assertEquals(
                "admission must be REGISTRATION_FAILED (gate 1)",
                ElevatedCleanupTracker.Admission.REGISTRATION_FAILED,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            // 三個計數站點：acquire-throw 臂首嘗＋second-chance＋caller verify 臂 helper 重試。
            assertEquals(before + 3, ElevatedCleanupTracker.quarantineRegistrationFailedCount())
            assertEquals(0, ElevatedCleanupTracker.outstanding())
            assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
        } finally {
            ElevatedProcessIo.quarantineRegistrar = { ElevatedCleanupTracker.quarantineUnverified(it) }
            ElevatedCleanupTracker.forceAcquireSlotOrQuarantineThrow = false
            ElevatedCleanupTracker.clearRegistrationFailedStickyForTest()
            ElevatedCleanupTracker.clearUnverified()
        }
    }

    /**
     * D-C4／D-D2：forceDrainPostAcquireFailure seam 釘頂層 catch-all fail-closed 契約。
     * kill 路徑在 runCleanupBounded 佔槽（outstanding=1）之後、verify 之前拋出 ⇒
     * drain 不外抛，回 drainFailClosedDrained 形狀 Drained（readFailed／cleanupIncomplete／
     * untrackedResidual／truncated／tailIncomplete=true，exitCode=-1）；slot 不釋放
     * （catch-all 臂不重釋，admission 續拒）；quarantine 盡力登記（default registrar 成功）。
     */
    @Test fun drainTopLevelCatchAllFailsClosedAndPinsSlotHeldNotReleased() {
        val hanger = NoOpHangProcess()
        try {
            ElevatedProcessIo.forceDrainPostAcquireFailure = true
            val d = ElevatedProcessIo.drainWithThreadStarter(
                process = hanger,
                timeoutMs = 300L,
                killer = { it.destroy() },
                isCancelled = { false },
            )
            assertEquals(-1, d.exitCode)
            assertFalse("catch-all arm is local failure, not budget ⇒ timedOut=false", d.timedOut)
            assertTrue("catch-all arm must flag readFailed", d.readFailed)
            assertTrue("catch-all arm must flag truncated", d.truncated)
            assertTrue("catch-all arm must flag tailIncomplete", d.tailIncomplete)
            assertTrue("catch-all arm must flag cleanupIncomplete", d.cleanupIncomplete)
            assertTrue("catch-all arm must flag untrackedResidual", d.untrackedResidual)
            assertEquals(
                "cleanup slot must stay held (fail-closed, no double release)",
                1,
                ElevatedCleanupTracker.outstanding(),
            )
            assertFalse(
                "registrar succeeds in catch-all arm ⇒ sticky must NOT be set",
                ElevatedCleanupTracker.isRegistrationFailedSticky(),
            )
            assertEquals(
                "catch-all arm registers the victim best-effort",
                1,
                ElevatedCleanupTracker.quarantinedCount(),
            )
            val admission = ElevatedCleanupTracker.tryEnterReserved()
            if (admission == ElevatedCleanupTracker.Admission.OK) {
                ElevatedCleanupTracker.leaveCall()
            }
            assertEquals(
                "admission must stay fail-closed while the slot is held",
                ElevatedCleanupTracker.Admission.BACKLOG_FULL,
                admission,
            )
        } finally {
            // 清理：catch-all 臂不重釋槽——測試手動歸零，保持 tearDown 零洩漏斷言有效。
            if (ElevatedCleanupTracker.outstanding() > 0) {
                runCatching { ElevatedCleanupTracker.releaseSlot() }
            }
            pollOutstandingTo(0)
            ElevatedCleanupTracker.clearUnverified()
            ElevatedProcessIo.forceDrainPostAcquireFailure = false
        }
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
