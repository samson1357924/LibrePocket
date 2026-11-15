package dev.librepocket.privilege.github

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * B2 JVM 驗收（Issue #136 B2）：[ElevatedCleanupTracker] 飽和／熔斷／quarantine
 * 的 tracker 層直接驗收＋殘留回收 fail-closed 語義。
 *
 * 範圍警戒：
 * - 純 tracker 直接斷言（不經 runner、不碰執行語義、不翻旗、不接線）；
 * - 零真機、零 su、零 binder；排序一律 latch／barrier＋假時鐘（有界輪詢），不用固定 sleep 當 oracle；
 * - 裝置 E2E 明確不做（見完成回報）。
 *
 * 與既有覆蓋的分工（不重複 runner 整合語義，只補 tracker 直接缺口）：
 * - `ElevatedCleanupTrackerTest`：容量／併發／飽和釋放／reservation 矩陣／breaker 開關；
 * - `ElevatedRunnerPhaseEQuarantineTest`：runner 層 quarantine 進出＋stall 並行＋wall；
 * - `ElevatedRunnerPhaseRTest`：跨代 stress＋kick 冷卻；
 * - 本檔：飽和 mark fail-closed、noteClean/outstanding 分離直測、cap 精確值、
 *   准入多門優先級、identity 去重、throwing 句柄、per-handle 節流計數、
 *   nowNanos 拋 fallback、clear 代際語義、peak/reset、拒絕不佔席。
 */
class ElevatedCleanupTrackerB2Test {

    private var floorBefore: Int = 0
    private var lingerFloorBefore: Int = 0

    @Before fun setUpIsolation() {
        assertFalse(
            "#136-B3 gate must stay closed",
            PrivilegeActivation.ELEVATED_RUNNERS_READY,
        )
        floorBefore = ElevatedCleanupTracker.releaseFloorHits()
        lingerFloorBefore = ElevatedCleanupTracker.lingerDebtFloorHits()
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.clearRegistrationFailedStickyForTest()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        pollPendingTo(0)
    }

    @After fun tearDownIsolation() {
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
        awaitNoQuarantineThreads()
        assertEquals("test leaked outstanding slots", 0, leakedOutstanding)
        assertEquals("test leaked active calls", 0, leakedActive)
        assertEquals("test leaked quarantined processes", 0, leakedQuarantine)
        assertEquals("release floor must not be hit during this test", floorBefore, ElevatedCleanupTracker.releaseFloorHits())
        pollTrue("linger debt must unwind to zero", 10_000L) {
            ElevatedCleanupTracker.lingerDebtCount() == 0
        }
        assertEquals("linger debt floor must not be hit during this test", lingerFloorBefore, ElevatedCleanupTracker.lingerDebtFloorHits())
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

    private fun pollTrue(what: String, boundMs: Long = 15_000L, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + boundMs
        while (!cond()) {
            if (System.currentTimeMillis() >= deadline) throw AssertionError("$what did not become true")
            Thread.sleep(20L)
        }
    }

    private fun awaitNoQuarantineThreads(boundMs: Long = 15_000L) {
        val names = setOf("elevated-quarantine-verify", "elevated-quarantine-sweep")
        val deadline = System.currentTimeMillis() + boundMs
        while (true) {
            val alive = Thread.getAllStackTraces().keys.filter { it.name in names && it.isAlive }
            if (alive.isEmpty()) return
            if (System.currentTimeMillis() >= deadline) throw AssertionError("orphan quarantine threads: $alive")
            Thread.sleep(50L)
        }
    }

    /** 存活假件：`waitFor(0)` 即時 false（非 stall，呼叫側零等待）。 */
    private class FastAliveProcess : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = false
        override fun exitValue(): Int = throw IllegalThreadStateException("not exited")
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }

    /** 計數存活假件：記錄 `waitFor(0)` 調用次數（per-handle 節流直測用）。 */
    private class CountingAliveProcess : Process() {
        val waitCalls = AtomicInteger(0)
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            // 只計跨 Binder 查詢（timeout==0 即 sweep 驗證路徑）。
            if (timeout == 0L) waitCalls.incrementAndGet()
            return false
        }
        override fun exitValue(): Int = throw IllegalThreadStateException("not exited")
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }

    /** 拋異常假件：`waitFor(0)` 即時拋（sweep 必須 fail-closed 保持 quarantine，不外拋）。 */
    private class ThrowingWaitProcess : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = throw RuntimeException("boom")
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = throw RuntimeException("boom")
        override fun exitValue(): Int = throw RuntimeException("boom")
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }

    /** stall 假件：`waitFor(0)` 永不返回且吞中斷（pending 保留模型；latch 僅 teardown 收斂）。 */
    private class StallSwallowProcess(private val latch: CountDownLatch) : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // 吞 interrupt 續等。
                }
            }
            return 0
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // 吞 interrupt 續等。
                }
            }
            return false
        }
        override fun exitValue(): Int = throw IllegalThreadStateException("not exited")
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }

    /**
     * equals 詭計假件：distinct 實例間 `equals==true`（驗 `quarantineUnverified`
     * 以 `===` 去重，而非 `equals`）。
     */
    private class EqualsAlwaysProcess : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = false
        override fun exitValue(): Int = throw IllegalThreadStateException("not exited")
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
        override fun equals(other: Any?): Boolean = other is EqualsAlwaysProcess
        override fun hashCode(): Int = 42
    }

    // ---- 1. 飽和 acquire 有界＋峰值封頂（tracker 直測，不經 drain） ----

    @Test fun saturatedAcquireRefusesWithoutGrowthAndPeakCapped() {
        ElevatedCleanupTracker.resetPeak()
        var prefilled = 0
        try {
            repeat(ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS) {
                assertTrue("slot $it must grant below MAX", ElevatedCleanupTracker.acquireSlot())
                prefilled++
            }
            assertEquals(
                ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS,
                ElevatedCleanupTracker.outstanding(),
            )
            assertTrue(ElevatedCleanupTracker.isSaturated())
            assertFalse("saturated acquire must refuse", ElevatedCleanupTracker.acquireSlot())
            assertEquals(
                ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS,
                ElevatedCleanupTracker.outstanding(),
            )
            assertTrue(
                "peak=${ElevatedCleanupTracker.peakOutstanding()} must stay <= MAX",
                ElevatedCleanupTracker.peakOutstanding() <= ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS,
            )
            // 飽和不佔 active（准入層職責，此處只斷 tracker 計數正交）。
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            repeat(prefilled) {
                ElevatedCleanupTracker.releaseSlot()
            }
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
        }
    }

    // ---- 2. 飽和 markLingering fail-closed（不佔槽、state=3＋ debt 持有至 worker finally） ----

    @Test fun markLingeringAtSaturationTakesNoSlotFailClosed() {
        var prefilled = 0
        val debtBefore = ElevatedCleanupTracker.lingerDebtCount()
        repeat(ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS) {
            assertTrue(ElevatedCleanupTracker.acquireSlot())
            prefilled++
        }
        assertTrue(ElevatedCleanupTracker.isSaturated())
        val latch = CountDownLatch(1)
        val state = AtomicInteger(0)
        val worker = Thread({
            try {
                latch.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                synchronized(state) {
                    if (state.get() == 1) {
                        state.set(2)
                        ElevatedCleanupTracker.releaseSlot()
                    } else if (state.get() == 0) {
                        state.set(2)
                    } else if (state.get() == 3) {
                        state.set(2)
                        ElevatedCleanupTracker.endLingerDebt()
                    }
                }
            }
        })
        worker.isDaemon = true
        try {
            worker.start()
            val d = System.currentTimeMillis() + 5_000L
            while (!worker.isAlive && System.currentTimeMillis() < d) Thread.sleep(5L)
            assertTrue(worker.isAlive)
            ElevatedCleanupTracker.markLingering(worker, state)
            // 飽和即終態 3（state=3＝飽和無槽＋債務；不佔槽，outstanding 封頂不增）：
            // debt 持有至 worker 本體 finally 見 3 即歸零（admission 此時對密封直至 worker 退出）。
            assertEquals(3, state.get())
            assertEquals(debtBefore + 1, ElevatedCleanupTracker.lingerDebtCount())
            assertEquals(
                ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS,
                ElevatedCleanupTracker.outstanding(),
            )
            // 同 state 重複 mark 不重記、不重佔。
            ElevatedCleanupTracker.markLingering(worker, state)
            assertEquals(3, state.get())
            assertEquals(debtBefore + 1, ElevatedCleanupTracker.lingerDebtCount())
            assertEquals(
                ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS,
                ElevatedCleanupTracker.outstanding(),
            )
        } finally {
            latch.countDown()
            worker.join(5_000L)
            assertTrue("worker must exit within 5s", !worker.isAlive)
            // 先釋槽＋清零 outstanding，再斷言 debt baseline——fail-fast 斷言不得阻止共享狀態清理。
            repeat(prefilled) {
                ElevatedCleanupTracker.releaseSlot()
            }
            pollOutstandingTo(0)
            // worker 已退出 ⇒ finally 恰好歸債一次——debt 必須回到 baseline 才算收斂。
            assertEquals(debtBefore, ElevatedCleanupTracker.lingerDebtCount())
            ElevatedCleanupTracker.noteClean()
        }
    }

    // ---- 3. noteClean／outstanding 分離直測（P0-1，不經 drain） ----

    @Test fun noteCleanDuringOutstandingKeepsBreaker() {
        try {
            repeat(2) { ElevatedCleanupTracker.noteUnclean() }
            assertEquals(2, ElevatedCleanupTracker.consecutiveCount())
            assertTrue(ElevatedCleanupTracker.acquireSlot())
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            // 殘留期間乾淨 drain 的 noteClean 不得歸零（保持 2）。
            ElevatedCleanupTracker.noteClean()
            assertEquals(2, ElevatedCleanupTracker.consecutiveCount())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            ElevatedCleanupTracker.releaseSlot()
            pollOutstandingTo(0)
            // 全域無殘留才歸零。
            ElevatedCleanupTracker.noteClean()
            assertEquals(0, ElevatedCleanupTracker.consecutiveCount())
        } finally {
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
        }
    }

    // ---- 4. noteUnclean 封頂精確值（不溢出） ----

    @Test fun noteUncleanCapsExactlyAtMax() {
        try {
            repeat(100) { ElevatedCleanupTracker.noteUnclean() }
            assertTrue(ElevatedCleanupTracker.isBreakerOpen())
            assertEquals(
                ElevatedCleanupTracker.MAX_CONSECUTIVE_UNCLEAN,
                ElevatedCleanupTracker.consecutiveCount(),
            )
            ElevatedCleanupTracker.noteClean()
            assertEquals(0, ElevatedCleanupTracker.consecutiveCount())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
        } finally {
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
        }
    }

    // ---- 5. 准入多門優先級（backlog＞breaker＞reservation＞quarantine）＋拒絕不佔席 ----

    @Test fun admissionPriorityMatrixAndDenialHoldsNoSeat() {
        // 子例間完全隔離：每段首尾重置 breaker＋槽＋quarantine＋active。
        // Stage 2 契約加固：counted boundary check——先核對該子例應留下的精確數量
        // （outstanding／activeCalls／quarantinedCount），再清登記／釋放恰好該數量，
        // 不再盲 while-loop 洗掉子例洩漏（洩漏會直接 fail，而非靜默回收）。
        fun resetAll(expectedOutstanding: Int, expectedActive: Int, expectedQuarantined: Int) {
            assertEquals("resetAll: sub-case left outstanding slots", expectedOutstanding, ElevatedCleanupTracker.outstanding())
            assertEquals("resetAll: sub-case left active seats", expectedActive, ElevatedCleanupTracker.activeCalls())
            assertEquals("resetAll: sub-case quarantine registrations", expectedQuarantined, ElevatedCleanupTracker.quarantinedCount())
            ElevatedCleanupTracker.clearUnverified()
            repeat(expectedOutstanding) { ElevatedCleanupTracker.releaseSlot() }
            repeat(expectedActive) { ElevatedCleanupTracker.leaveCall() }
            pollOutstandingTo(0)
            pollActiveTo(0)
            ElevatedCleanupTracker.noteClean()
        }
        var activeHeld = 0
        try {
            // a) backlog＋breaker＋quarantine 三門同立 → BACKLOG（首門優先）。
            resetAll(0, 0, 0)
            repeat(ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS) {
                assertTrue(ElevatedCleanupTracker.acquireSlot())
            }
            repeat(ElevatedCleanupTracker.MAX_CONSECUTIVE_UNCLEAN) {
                ElevatedCleanupTracker.noteUnclean()
            }
            ElevatedCleanupTracker.quarantineUnverified(FastAliveProcess())
            assertEquals(
                ElevatedCleanupTracker.Admission.BACKLOG_FULL,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())

            // b) breaker＋quarantine（outstanding 清空）→ BREAKER（次優先）。
            // 上一子例 a) 有意留下 MAX_OUTSTANDING_CLEANUPS 槽＋1 筆 quarantine 登記。
            resetAll(ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS, 0, 1)
            repeat(ElevatedCleanupTracker.MAX_CONSECUTIVE_UNCLEAN) {
                ElevatedCleanupTracker.noteUnclean()
            }
            ElevatedCleanupTracker.quarantineUnverified(FastAliveProcess())
            assertTrue(ElevatedCleanupTracker.isBreakerOpen())
            assertTrue(ElevatedCleanupTracker.isQuarantined())
            assertEquals(
                ElevatedCleanupTracker.Admission.BREAKER_OPEN,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())

            // c) reservation（outstanding=1）＋quarantine → BACKLOG（預留先於末門）。
            // 上一子例 b) 有意留下 1 筆 quarantine 登記（無槽、無席）。
            resetAll(0, 0, 1)
            assertTrue(ElevatedCleanupTracker.acquireSlot())
            ElevatedCleanupTracker.quarantineUnverified(FastAliveProcess())
            assertEquals(
                ElevatedCleanupTracker.Admission.BACKLOG_FULL,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())

            // d) quarantine 單門（outstanding=0、consecutive=1、預留通過）→ QUARANTINE。
            // 上一子例 c) 有意留下 1 槽＋1 筆 quarantine 登記。
            resetAll(1, 0, 1)
            ElevatedCleanupTracker.noteUnclean()
            ElevatedCleanupTracker.quarantineUnverified(FastAliveProcess())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            assertFalse(ElevatedCleanupTracker.isSaturated())
            assertEquals(
                ElevatedCleanupTracker.Admission.QUARANTINE,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())

            // e) CONCURRENCY_FULL 死分支仍可達：僅測用 tryEnterCall 預填 active=MAX_CONCURRENT_CALLS。
            // 上一子例 d) 有意留下 1 筆 quarantine 登記（無槽、無席）。
            resetAll(0, 0, 1)
            repeat(ElevatedCleanupTracker.MAX_CONCURRENT_CALLS) {
                assertTrue(ElevatedCleanupTracker.tryEnterCall())
                activeHeld += 1
            }
            assertEquals(ElevatedCleanupTracker.MAX_CONCURRENT_CALLS, ElevatedCleanupTracker.activeCalls())
            // e) active=MAX_CONCURRENT_CALLS 時 reservation 必為 CONCURRENCY_FULL：classifyAdmissionLocked
            // 先查 active>=MAX_CONCURRENT_CALLS（併發門）才查 reservation，順序調換即迴歸，
            // 故此處精確斷言（既有 reservationMatrixDirect 亦同）。
            val verdict = ElevatedCleanupTracker.tryEnterReserved()
            assertEquals(
                ElevatedCleanupTracker.Admission.CONCURRENCY_FULL,
                verdict,
            )
            assertEquals(ElevatedCleanupTracker.MAX_CONCURRENT_CALLS, ElevatedCleanupTracker.activeCalls())
        } finally {
            // 子例 e) 有意留下 activeHeld 席（tryEnterCall 預填）；越界即 fail，不盲放。
            assertEquals("sub-case e left outstanding slots", 0, ElevatedCleanupTracker.outstanding())
            assertEquals("sub-case e left active seats", activeHeld, ElevatedCleanupTracker.activeCalls())
            assertEquals("sub-case e left quarantine registrations", 0, ElevatedCleanupTracker.quarantinedCount())
            ElevatedCleanupTracker.clearUnverified()
            repeat(activeHeld) { ElevatedCleanupTracker.leaveCall() }
            pollOutstandingTo(0)
            pollActiveTo(0)
            ElevatedCleanupTracker.noteClean()
        }
    }

    // ---- 6. quarantine identity 去重（===，非 equals） ----

    @Test fun quarantineDistinctHandlesBothHeldIdentityBased() {
        try {
            val p1 = FakeProcess("a".toByteArray())
            val p2 = FakeProcess("b".toByteArray())
            ElevatedCleanupTracker.quarantineUnverified(p1)
            ElevatedCleanupTracker.quarantineUnverified(p1)
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            ElevatedCleanupTracker.quarantineUnverified(p2)
            assertEquals(2, ElevatedCleanupTracker.quarantinedCount())
            // equals 詭計：distinct 實例 equals==true，仍須雙計（=== 語義）。
            val e1 = EqualsAlwaysProcess()
            val e2 = EqualsAlwaysProcess()
            assertTrue(e1 == e2)
            assertFalse(e1 === e2)
            ElevatedCleanupTracker.quarantineUnverified(e1)
            ElevatedCleanupTracker.quarantineUnverified(e2)
            assertEquals(4, ElevatedCleanupTracker.quarantinedCount())
            // 准入末門仍 QUARANTINE，不佔席。
            assertEquals(
                ElevatedCleanupTracker.Admission.QUARANTINE,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            // outstanding／breaker 正交：quarantine 不佔槽、不計 consecutive。
            assertEquals(0, ElevatedCleanupTracker.outstanding())
        } finally {
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
        }
    }

    // ---- 7. sweep：確死清除、存活／拋保持（fail-closed，不外拋） ----

    @Test fun sweepQuarantineDeadClearsAliveAndThrowingKeep() {
        try {
            val dead = FakeProcess("ok".toByteArray())
            val alive = FastAliveProcess()
            val throwing = ThrowingWaitProcess()
            ElevatedCleanupTracker.quarantineUnverified(dead)
            ElevatedCleanupTracker.quarantineUnverified(alive)
            ElevatedCleanupTracker.quarantineUnverified(throwing)
            assertEquals(3, ElevatedCleanupTracker.quarantinedCount())
            val cleared = ElevatedCleanupTracker.sweepQuarantine()
            assertEquals("only verified-dead may clear, got $cleared", 1, cleared)
            assertEquals(2, ElevatedCleanupTracker.quarantinedCount())
            assertTrue(ElevatedCleanupTracker.isQuarantined())
            // 准入仍末門 QUARANTINE（不翻轉 BACKLOG）。
            assertEquals(
                ElevatedCleanupTracker.Admission.QUARANTINE,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(0, ElevatedCleanupTracker.outstanding())
        } finally {
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
            awaitNoQuarantineThreads()
        }
    }

    // ---- 8. per-handle 節流：同 handle 同一時刻不重驗（計數直測） ----

    @Test fun sweepPerHandleCooldownThrottlesSameHandle() {
        val fakeNow = AtomicLong(System.nanoTime())
        val nowNanos: () -> Long = { fakeNow.get() }
        try {
            val p = CountingAliveProcess()
            ElevatedCleanupTracker.quarantineUnverified(p)
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            assertEquals(0, ElevatedCleanupTracker.sweepQuarantine(nowNanos))
            assertEquals("first sweep must attempt once", 1, p.waitCalls.get())
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            // 同一時刻重掃即節流（不另起 verifier，不重調 waitFor）。
            assertEquals(0, ElevatedCleanupTracker.sweepQuarantine(nowNanos))
            assertEquals("throttled sweep must not re-verify", 1, p.waitCalls.get())
            // 推進逾 per-handle 冷卻即放行再驗。
            fakeNow.addAndGet(
                TimeUnit.MILLISECONDS.toNanos(
                    ElevatedCleanupTracker.SWEEP_PER_HANDLE_COOLDOWN_MS + 1000L,
                ),
            )
            assertEquals(0, ElevatedCleanupTracker.sweepQuarantine(nowNanos))
            assertEquals("after cooldown must re-verify", 2, p.waitCalls.get())
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
        } finally {
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
            awaitNoQuarantineThreads()
        }
    }

    // ---- 8b. Stage 4：stale per-handle 節流條目剪枝（>1h 無動靜即移除，隔次 sweep 一次 re-attempt） ----

    @Test fun sweepPrunesStaleLastAttemptEntryPastPruneThreshold() {
        val fakeNow = AtomicLong(System.nanoTime())
        val nowNanos: () -> Long = { fakeNow.get() }
        try {
            // Stage 4 review R3：直接以 lastAttemptNanosCount() 釘住 map 現況
            // （delta 斷言，容忍其他測試可能殘留的條目；clearUnverified 會清空）。
            val base = ElevatedCleanupTracker.lastAttemptNanosCount()
            val p = CountingAliveProcess()
            ElevatedCleanupTracker.quarantineUnverified(p)
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            // 首掃：attempt 一次（記 t0 於 lastAttemptNanos）。
            assertEquals(0, ElevatedCleanupTracker.sweepQuarantine(nowNanos))
            assertEquals("first sweep must attempt once", 1, p.waitCalls.get())
            assertEquals(
                "first sweep must record one lastAttemptNanos entry (delta +1)",
                base + 1, ElevatedCleanupTracker.lastAttemptNanosCount(),
            )
            // 推進逾剪枝閾值（1h＋1s）：stale 條目剪除、本次不 attempt（節流語義不變）。
            fakeNow.addAndGet(
                TimeUnit.MILLISECONDS.toNanos(
                    ElevatedCleanupTracker.SWEEP_LAST_ATTEMPT_PRUNE_MS + 1000L,
                ),
            )
            assertEquals(0, ElevatedCleanupTracker.sweepQuarantine(nowNanos))
            assertEquals(
                "stale sweep must prune (not re-attempt) in this pass",
                1, p.waitCalls.get(),
            )
            // R3：剪枝 sweep 結束後 map 不再留存 p 的條目（size delta 回到 base）。
            // （清理登記成功或空條目情境皆不適用本測試：alive 的 CountingAliveProcess
            //  驗證不通過，p 的 lastAttemptNanos 條目唯一來源即首掃記錄。）
            assertEquals(
                "stale sweep must remove the lastAttemptNanos entry",
                base, ElevatedCleanupTracker.lastAttemptNanosCount(),
            )
            // 下次 sweep：條目已剪（map 不再留存 p）——視同未嘗試，一次 re-attempt。
            // （R3：「不留存」現由上方 lastAttemptNanosCount() 直接釘住，不再僅以
            // re-attempt 行為代理；此處保留行為斷言雙釘。）
            assertEquals(0, ElevatedCleanupTracker.sweepQuarantine(nowNanos))
            assertEquals(
                "next sweep after prune must re-attempt once (stale entry gone)",
                2, p.waitCalls.get(),
            )
            assertEquals(
                "re-attempt sweep must re-record one lastAttemptNanos entry (delta +1)",
                base + 1, ElevatedCleanupTracker.lastAttemptNanosCount(),
            )
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
        } finally {
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
            awaitNoQuarantineThreads()
        }
    }

    // ---- 9. kick 空集合即 false（零線程、零等待） ----

    @Test fun kickSweepAsyncEmptyReturnsFalseWithoutThread() {
        assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
        assertEquals(0, ElevatedCleanupTracker.sweepPendingCount())
        var threadStarted = false
        val kicked = ElevatedCleanupTracker.kickSweepAsyncWithThreadStarter(
            startThread = {
                threadStarted = true
                it.start()
            },
        )
        assertFalse("empty kick must return false", kicked)
        assertFalse("empty kick must not start a sweep thread", threadStarted)
        assertEquals(0, ElevatedCleanupTracker.sweepPendingCount())
        assertFalse(ElevatedCleanupTracker.isSweepInFlight())
    }

    // ---- 10. nowNanos 拋 fallback（fail-closed，不外拋、quarantine 保持） ----

    @Test fun throwingClockFallbackKeepsQuarantineFailClosed() {
        val throwingClock: () -> Long = { throw RuntimeException("clock boom") }
        try {
            ElevatedCleanupTracker.quarantineUnverified(FastAliveProcess())
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            // 同步 sweep：拋時鐘即 fallback，不外拋，存活即 0 清除。
            val cleared = ElevatedCleanupTracker.sweepQuarantine(throwingClock)
            assertEquals(0, cleared)
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            // 背景 kick：拋時鐘即 fallback（@Before 已 clearUnverified 重置冷卻，
            // 此處必須成功發起；fallback 時鐘為單調 System.nanoTime，首 kick 不受冷卻限）。
            val kicked = ElevatedCleanupTracker.kickSweepAsync(throwingClock)
            assertTrue("fallback clock must still start the sweep", kicked)
            // 無論是否發起，quarantine 必須保持（fail-closed）。
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            assertTrue(
                "quarantine must hold after throwing-clock kick(kicked=$kicked)",
                ElevatedCleanupTracker.isQuarantined(),
            )
            pollTrue("background sweep must finish", 15_000L) {
                !ElevatedCleanupTracker.isSweepInFlight()
            }
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
        } finally {
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
            awaitNoQuarantineThreads()
        }
    }

    // ---- 11. clear 代際語義：清 quarantine＋冷卻＋attempts，但保留 stall pending ----

    @Test fun clearUnverifiedResetsCooldownButRetainsPendingStall() {
        val latch = CountDownLatch(1)
        val fakeNow = AtomicLong(System.nanoTime())
        val nowNanos: () -> Long = { fakeNow.get() }
        try {
            // Fill all MAX_SWEEP_VERIFIERS slots with StallSwallowProcess.
            val stalls = (0 until ElevatedCleanupTracker.MAX_SWEEP_VERIFIERS).map {
                StallSwallowProcess(latch)
            }
            for (s in stalls) ElevatedCleanupTracker.quarantineUnverified(s)
            assertEquals(ElevatedCleanupTracker.MAX_SWEEP_VERIFIERS, ElevatedCleanupTracker.quarantinedCount())
            assertTrue(
                "first kick must start (lease free + cooldown clear)",
                ElevatedCleanupTracker.kickSweepAsync(nowNanos),
            )
            pollTrue("stalls must be tracked as pending", 15_000L) {
                ElevatedCleanupTracker.sweepPendingCount() == ElevatedCleanupTracker.MAX_SWEEP_VERIFIERS
            }
            pollTrue("background sweep must finish", 15_000L) {
                !ElevatedCleanupTracker.isSweepInFlight()
            }
            // 代際邊界：clear 即 quarantine 空＋冷卻重置，但 stall pending 保留。
            ElevatedCleanupTracker.clearUnverified()
            assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
            assertFalse(ElevatedCleanupTracker.isQuarantined())
            assertEquals(
                "live stall workers must be retained (safe direction)",
                ElevatedCleanupTracker.MAX_SWEEP_VERIFIERS,
                ElevatedCleanupTracker.sweepPendingCount(),
            )
            // Quarantine a dead process; verification skipped because sweepPending saturated.
            val dead = FakeProcess("dead".toByteArray())
            ElevatedCleanupTracker.quarantineUnverified(dead)
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            assertTrue(
                "after clear the same frozen clock must allow one sweep (cooldown reset)",
                ElevatedCleanupTracker.kickSweepAsync(nowNanos),
            )
            pollTrue("second sweep must finish", 15_000L) {
                !ElevatedCleanupTracker.isSweepInFlight()
            }
            // Dead process must NOT be cleared because sweepPending.size >= MAX skipped verification.
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            assertTrue(ElevatedCleanupTracker.isQuarantined())
            assertEquals(
                ElevatedCleanupTracker.Admission.QUARANTINE,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
        } finally {
            latch.countDown()
            pollPendingTo(0)
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
            pollActiveTo(0)
            awaitNoQuarantineThreads()
        }
    }

    // ---- 12. 拒絕不佔席（BACKLOG／BREAKER／QUARANTINE 全門） ----

    @Test fun denialsNeverOccupyActiveSeat() {
        // Stage 2 契約加固：counted boundary check——先核對該子例應留下的精確數量
        // （outstanding／activeCalls／quarantinedCount），再清登記／釋放恰好該數量，
        // 不再盲 while-loop 洗掉子例洩漏（洩漏會直接 fail，而非靜默回收）。
        fun resetAll(expectedOutstanding: Int, expectedActive: Int, expectedQuarantined: Int) {
            assertEquals("resetAll: sub-case left outstanding slots", expectedOutstanding, ElevatedCleanupTracker.outstanding())
            assertEquals("resetAll: sub-case left active seats", expectedActive, ElevatedCleanupTracker.activeCalls())
            assertEquals("resetAll: sub-case quarantine registrations", expectedQuarantined, ElevatedCleanupTracker.quarantinedCount())
            ElevatedCleanupTracker.clearUnverified()
            repeat(expectedOutstanding) { ElevatedCleanupTracker.releaseSlot() }
            repeat(expectedActive) { ElevatedCleanupTracker.leaveCall() }
            pollOutstandingTo(0)
            pollActiveTo(0)
            ElevatedCleanupTracker.noteClean()
        }
        try {
            // BACKLOG（outstanding=MAX_OUTSTANDING_CLEANUPS）。
            resetAll(0, 0, 0)
            repeat(ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS) {
                assertTrue(ElevatedCleanupTracker.acquireSlot())
            }
            assertEquals(
                ElevatedCleanupTracker.Admission.BACKLOG_FULL,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())

            // BREAKER（consecutive=MAX_CONSECUTIVE_UNCLEAN）。
            // 上一子例 BACKLOG 有意留下 MAX_OUTSTANDING_CLEANUPS 槽（無席、無登記）。
            resetAll(ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS, 0, 0)
            repeat(ElevatedCleanupTracker.MAX_CONSECUTIVE_UNCLEAN) {
                ElevatedCleanupTracker.noteUnclean()
            }
            assertEquals(
                ElevatedCleanupTracker.Admission.BREAKER_OPEN,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())

            // QUARANTINE（單門）。
            // 上一子例 BREAKER 有意留下 0 槽（無席、無登記）。
            resetAll(0, 0, 0)
            ElevatedCleanupTracker.quarantineUnverified(FastAliveProcess())
            assertEquals(
                ElevatedCleanupTracker.Admission.QUARANTINE,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())

            // OK 才佔席（乾淨態首呼叫）。
            // 上一子例 QUARANTINE 有意留下 1 筆 quarantine 登記（無槽、無席）。
            resetAll(0, 0, 1)
            assertEquals(
                ElevatedCleanupTracker.Admission.OK,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(1, ElevatedCleanupTracker.activeCalls())
            ElevatedCleanupTracker.leaveCall()
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            // 子例 OK 已 leaveCall、各拒門子例零收量；越界即 fail，不盲放。
            assertEquals("sub-case left outstanding slots", 0, ElevatedCleanupTracker.outstanding())
            assertEquals("sub-case left active seats", 0, ElevatedCleanupTracker.activeCalls())
            assertEquals("sub-case left quarantine registrations", 0, ElevatedCleanupTracker.quarantinedCount())
            ElevatedCleanupTracker.clearUnverified()
            pollOutstandingTo(0)
            pollActiveTo(0)
            ElevatedCleanupTracker.noteClean()
        }
    }

    // ---- 13. peak／resetPeak 同步語義 ----

    @Test fun peakTracksMaxAndResetPeakRebases() {
        ElevatedCleanupTracker.resetPeak()
        var held = 0
        try {
            assertEquals(0, ElevatedCleanupTracker.outstanding())
            repeat(2) {
                assertTrue(ElevatedCleanupTracker.acquireSlot())
                held += 1
            }
            assertEquals(2, ElevatedCleanupTracker.outstanding())
            assertEquals(2, ElevatedCleanupTracker.peakOutstanding())
            ElevatedCleanupTracker.releaseSlot()
            held -= 1
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            assertEquals("peak must hold max, not current", 2, ElevatedCleanupTracker.peakOutstanding())
            ElevatedCleanupTracker.resetPeak()
            assertEquals("resetPeak must rebase to current", 1, ElevatedCleanupTracker.peakOutstanding())
            assertTrue(ElevatedCleanupTracker.acquireSlot())
            held += 1
            assertEquals(2, ElevatedCleanupTracker.outstanding())
            assertEquals(2, ElevatedCleanupTracker.peakOutstanding())
        } finally {
            repeat(held) { ElevatedCleanupTracker.releaseSlot() }
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
        }
    }

    // ---- 14. quarantine 不佔槽／不計 consecutive（與 breaker 分離） ----

    @Test fun quarantineHoldsWithoutSlotsOrBreakerCount() {
        ElevatedCleanupTracker.resetPeak()
        val peakBefore = ElevatedCleanupTracker.peakOutstanding()
        try {
            ElevatedCleanupTracker.quarantineUnverified(FastAliveProcess())
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            assertEquals(0, ElevatedCleanupTracker.outstanding())
            assertEquals(0, ElevatedCleanupTracker.consecutiveCount())
            assertFalse(ElevatedCleanupTracker.isSaturated())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            assertEquals(peakBefore, ElevatedCleanupTracker.peakOutstanding())
        } finally {
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
        }
    }

    // ---- 15. kickSweepAsync nowNanos == 0L 仍強制 SWEEP_COOLDOWN_MS（修復 0L sentinel 繞過） ----

    @Test fun kickSweepAsyncAtZeroClockStillEnforcesCooldown() {
        val fakeNow = AtomicLong(0L)
        val nowNanos: () -> Long = { fakeNow.get() }
        try {
            ElevatedCleanupTracker.quarantineUnverified(FastAliveProcess())
            assertTrue(
                "initial kick at 0L must succeed",
                ElevatedCleanupTracker.kickSweepAsync(nowNanos),
            )
            pollTrue("first sweep at 0L must finish", 15_000L) {
                !ElevatedCleanupTracker.isSweepInFlight()
            }
            assertFalse(
                "repeat kick at 0L within cooldown must be suppressed (0L sentinel bug)",
                ElevatedCleanupTracker.kickSweepAsync(nowNanos),
            )
            fakeNow.set(
                TimeUnit.MILLISECONDS.toNanos(ElevatedCleanupTracker.SWEEP_COOLDOWN_MS - 1L),
            )
            assertFalse(
                "kick just before SWEEP_COOLDOWN_MS must still be suppressed",
                ElevatedCleanupTracker.kickSweepAsync(nowNanos),
            )
            fakeNow.set(TimeUnit.MILLISECONDS.toNanos(ElevatedCleanupTracker.SWEEP_COOLDOWN_MS))
            assertTrue(
                "kick at SWEEP_COOLDOWN_MS after 0L must be allowed",
                ElevatedCleanupTracker.kickSweepAsync(nowNanos),
            )
            pollTrue("second sweep must finish", 15_000L) {
                !ElevatedCleanupTracker.isSweepInFlight()
            }
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
        } finally {
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
            awaitNoQuarantineThreads()
        }
    }

    // ---- 16. throwing Error (Throwable) clock fallback in sweepQuarantine & kickSweepAsync ----

    @Test fun throwingThrowableClockFallbackKeepsQuarantineFailClosed() {
        val throwingClock: () -> Long = { throw AssertionError("clock throwable") }
        try {
            val syncProc = CountingAliveProcess()
            ElevatedCleanupTracker.quarantineUnverified(syncProc)
            assertEquals(0, ElevatedCleanupTracker.sweepQuarantine(throwingClock))
            assertEquals("sync sweep must attempt waitFor(0) on fallback", 1, syncProc.waitCalls.get())
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())

            ElevatedCleanupTracker.clearUnverified()
            val asyncProc = CountingAliveProcess()
            ElevatedCleanupTracker.quarantineUnverified(asyncProc)
            assertTrue(
                "kickSweepAsync must succeed on throwing clock fallback",
                ElevatedCleanupTracker.kickSweepAsync(throwingClock),
            )
            pollTrue("background sweep must finish", 15_000L) {
                !ElevatedCleanupTracker.isSweepInFlight()
            }
            assertEquals("async sweep must execute waitFor(0) on fallback", 1, asyncProc.waitCalls.get())
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
        } finally {
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
            awaitNoQuarantineThreads()
        }
    }

    // ---- 17. singleflight 併發爭奪不繞過冷卻，且不覆蓋 clearUnverified() 重置 ----

    @Test fun kickSweepAsyncSingleflightContentionEnforcesCooldown() {
        val fakeNow = AtomicLong(0L)
        val nowNanos: () -> Long = { fakeNow.get() }
        try {
            ElevatedCleanupTracker.quarantineUnverified(FastAliveProcess())
            val startGate = CountDownLatch(1)
            val successes = AtomicInteger(0)
            val workers = (0 until 8).map {
                Thread {
                    assertTrue(startGate.await(10, TimeUnit.SECONDS))
                    if (ElevatedCleanupTracker.kickSweepAsync(nowNanos)) {
                        successes.incrementAndGet()
                    }
                }.also { it.isDaemon = true; it.start() }
            }
            startGate.countDown()
            workers.forEach { it.join(10_000L) }
            assertEquals("concurrent wave at 0L must start at most once", 1, successes.get())
            pollTrue("wave 1 sweep must finish", 15_000L) {
                !ElevatedCleanupTracker.isSweepInFlight()
            }
            val wave2Gate = CountDownLatch(1)
            val wave2Successes = AtomicInteger(0)
            val wave2 = (0 until 8).map {
                Thread {
                    assertTrue(wave2Gate.await(10, TimeUnit.SECONDS))
                    if (ElevatedCleanupTracker.kickSweepAsync(nowNanos)) {
                        wave2Successes.incrementAndGet()
                    }
                }.also { it.isDaemon = true; it.start() }
            }
            wave2Gate.countDown()
            wave2.forEach { it.join(10_000L) }
            assertEquals("wave 2 within cooldown must start 0 sweeps", 0, wave2Successes.get())
        } finally {
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
            awaitNoQuarantineThreads()
        }
    }

    @Test fun concurrentKickSweepAsyncAndClearUnverifiedDoNotClobberReset() {
        val waitEntered = CountDownLatch(1)
        val waitRelease = CountDownLatch(1)
        val blockingProcess = object : Process() {
            override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
            override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun waitFor(): Int = 0
            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
                waitEntered.countDown()
                waitRelease.await(10, TimeUnit.SECONDS)
                return false
            }
            override fun exitValue(): Int = throw IllegalThreadStateException("not exited")
            override fun destroy() = Unit
            override fun destroyForcibly(): Process = this
        }
        val fakeNow = AtomicLong(123_456_789L)
        val nowNanos: () -> Long = { fakeNow.get() }
        try {
            ElevatedCleanupTracker.quarantineUnverified(blockingProcess)
            assertTrue(
                "initial kick must acquire lease",
                ElevatedCleanupTracker.kickSweepAsync(nowNanos),
            )
            assertTrue(
                "verifier must enter waitFor(0)",
                waitEntered.await(10, TimeUnit.SECONDS),
            )
            val contentionGate = CountDownLatch(1)
            val contentionWins = AtomicInteger(0)
            val contenders = (0 until 4).map {
                Thread {
                    assertTrue(contentionGate.await(10, TimeUnit.SECONDS))
                    if (ElevatedCleanupTracker.kickSweepAsync(nowNanos)) {
                        contentionWins.incrementAndGet()
                    }
                }.also { it.isDaemon = true; it.start() }
            }
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.quarantineUnverified(FastAliveProcess())
            contentionGate.countDown()
            contenders.forEach { it.join(10_000L) }
            assertEquals("in-flight contention must never steal lease", 0, contentionWins.get())

            waitRelease.countDown()
            pollTrue("in-flight sweep must release lease", 15_000L) {
                !ElevatedCleanupTracker.isSweepInFlight()
            }
            assertTrue(
                "frozen clock after clearUnverified must allow fresh sweep",
                ElevatedCleanupTracker.kickSweepAsync(nowNanos),
            )
            pollTrue("post-clear sweep must finish", 15_000L) {
                !ElevatedCleanupTracker.isSweepInFlight()
            }
            assertFalse(
                "post-clear repeat kick within cooldown must be throttled",
                ElevatedCleanupTracker.kickSweepAsync(nowNanos),
            )
        } finally {
            waitRelease.countDown()
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
            pollPendingTo(0)
            awaitNoQuarantineThreads()
        }
    }

    // ---- Stage 2A（P1-A1）：acquireSlotOrQuarantine 原子 acquire-or-quarantine ----
    //
    // code-reviewer 標記（pre-Stage-2A gap）：飽和臂舊碼 `acquireSlot()==false →（gap）
    // → quarantineUnverified(v)` 兩次獨立取鎖——併發 drain 可在縫隙內釋槽至 0，並行
    // tryEnterReserved 可能在 quarantine 登記前被准入（fail-open）。helper 把預留
    // 檢查＋佔席（或登記）壓成同一 [lock] 持有內的單一原子判定，本組測試直接斷
    // 言其語義（無法以測試強制交錯的縫隙——縫隙已不存在）。

    @Test fun acquireSlotOrQuarantineSaturatedRegistersQuarantineAtomically() {
        ElevatedCleanupTracker.resetPeak()
        val victim = FastAliveProcess()
        var prefilled = 0
        try {
            repeat(ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS) {
                assertTrue("slot $it must grant below MAX", ElevatedCleanupTracker.acquireSlot())
                prefilled++
            }
            assertTrue(ElevatedCleanupTracker.isSaturated())
            // 飽和：helper 同鎖內登記 quarantine，不佔槽、outstanding 封頂不增。
            assertFalse("saturated acquire-or-quarantine must refuse the slot",
                ElevatedCleanupTracker.acquireSlotOrQuarantine(victim))
            assertEquals(
                "saturated acquire-or-quarantine must leave outstanding unchanged",
                ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS,
                ElevatedCleanupTracker.outstanding(),
            )
            assertEquals(
                "saturated acquire-or-quarantine must register quarantine under the same lock",
                1,
                ElevatedCleanupTracker.quarantinedCount(),
            )
            // 冪等閂：同 handle 重複登記不雙計、outstanding 不增。
            assertFalse(ElevatedCleanupTracker.acquireSlotOrQuarantine(victim))
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            assertEquals(
                ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS,
                ElevatedCleanupTracker.outstanding(),
            )
        } finally {
            repeat(prefilled) {
                ElevatedCleanupTracker.releaseSlot()
            }
            ElevatedCleanupTracker.clearUnverified()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
        }
    }

    @Test fun acquireSlotOrQuarantineGrantsSlotWhenAvailableLeavesQuarantineEmpty() {
        ElevatedCleanupTracker.resetPeak()
        val victim = FastAliveProcess()
        try {
            assertTrue(ElevatedCleanupTracker.acquireSlotOrQuarantine(victim))
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
            assertFalse(ElevatedCleanupTracker.isQuarantined())
        } finally {
            ElevatedCleanupTracker.releaseSlot()
            ElevatedCleanupTracker.clearUnverified()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
        }
    }

    @Test fun acquireSlotOrQuarantineSealedAdmissionWindowWhenSaturated() {
        // 飽和登記完成後即使 outstanding 歸零（併發 drain 釋槽的最壞情境），
        // 並行准入仍被 quarantine 槍末門擋下（QUARANTINE，不佔席、零 spawn）。
        ElevatedCleanupTracker.resetPeak()
        val victim = FastAliveProcess()
        var prefilled = 0
        try {
            repeat(ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS) { i ->
                assertTrue("prefill slot $i must grant below MAX",
                    ElevatedCleanupTracker.acquireSlot())
                prefilled++
            }
            assertFalse(ElevatedCleanupTracker.acquireSlotOrQuarantine(victim))
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            // 併發 drain 全部釋槽（最壞的歸零窗口）。
            repeat(prefilled) { ElevatedCleanupTracker.releaseSlot() }
            prefilled = 0
            pollOutstandingTo(0)
            // 歸零後並行准入：gate 6（quarantine）於 debt==0 時獨力封死 fail-open 窗口。
            assertEquals(
                ElevatedCleanupTracker.Admission.QUARANTINE,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            // 恰好釋放仍持有的槽（body 已釋則 0）；絕不同超過 acquire 數。
            repeat(prefilled) { ElevatedCleanupTracker.releaseSlot() }
            ElevatedCleanupTracker.clearUnverified()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
        }
    }

    @Test fun acquireSlotOrQuarantineConcurrentCallersNeverLoseOrDoubleCount() {
        // burst 個不同 handle 併發競爭 MAX 槽：成功者恰好 MAX（無人釋放）、
        // 其餘逐一登記 quarantine（identity 去重互不影響）——完成態斷言
        // acquired==MAX、quarantined==burst-MAX，無丟失、無雙計。
        ElevatedCleanupTracker.resetPeak()
        val burst = ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS + 4
        val handles = List(burst) { FastAliveProcess() }
        val successes = AtomicInteger(0)
        val gate = CountDownLatch(1)
        try {
            val threads = handles.map { h ->
                Thread({
                    val awaited = gate.await(5, TimeUnit.SECONDS)
                    assertTrue("burst gate must open within 5s", awaited)
                    if (ElevatedCleanupTracker.acquireSlotOrQuarantine(h)) {
                        successes.incrementAndGet()
                    }
                }, "acq-or-quarantine").also { it.isDaemon = true; it.start() }
            }
            gate.countDown()
            threads.forEach { it.join(10_000L) }
            assertTrue(threads.none { it.isAlive })
            assertEquals(
                ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS,
                successes.get(),
            )
            assertEquals(
                burst - ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS,
                ElevatedCleanupTracker.quarantinedCount(),
            )
            assertEquals(
                ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS,
                ElevatedCleanupTracker.outstanding(),
            )
        } finally {
            // 僅釋放本 burst 成功者實際佔的槽（恰好 successes.get()）；
            // quarantine 登記的敗者未佔槽，經 clearUnverified 清除。
            repeat(successes.get()) {
                ElevatedCleanupTracker.releaseSlot()
            }
            ElevatedCleanupTracker.clearUnverified()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
        }
    }

    @Test fun kickSweepAsyncStartThreadOomAfterConcurrentClearPreservesResetAndReleasesLease() {
        try {
            // 1. Seed a previous sweep at t1 = 10s so prevHasStart=true, prevStart=10s.
            val t1 = TimeUnit.SECONDS.toNanos(10L)
            ElevatedCleanupTracker.quarantineUnverified(FastAliveProcess())
            assertTrue(ElevatedCleanupTracker.kickSweepAsync { t1 })
            pollTrue("seed sweep must finish", 15_000L) {
                !ElevatedCleanupTracker.isSweepInFlight()
            }

            // 2. Start kick at t2 = 12s (past cooldown), run clearUnverified() concurrently
            // while startThread is in progress, then throw OutOfMemoryError from startThread.
            val t2 = TimeUnit.SECONDS.toNanos(12L)
            val startEntered = CountDownLatch(1)
            val clearDone = CountDownLatch(1)
            val bg = Thread {
                try {
                    assertTrue(startEntered.await(10, TimeUnit.SECONDS))
                    ElevatedCleanupTracker.clearUnverified()
                } finally {
                    clearDone.countDown()
                }
            }.also { it.isDaemon = true; it.start() }

            ElevatedCleanupTracker.quarantineUnverified(FastAliveProcess())
            val kicked = ElevatedCleanupTracker.kickSweepAsyncWithThreadStarter(
                nowNanos = { t2 },
                startThread = {
                    try {
                        startEntered.countDown()
                        assertTrue(clearDone.await(10, TimeUnit.SECONDS))
                        throw OutOfMemoryError("simulated OOM starting sweep thread")
                    } finally {
                        startEntered.countDown()
                    }
                },
            )
            bg.join(10_000L)
            assertFalse(bg.isAlive)
            assertFalse("failed startThread must return false", kicked)
            assertFalse("sweepLease must be rolled back on startThread failure", ElevatedCleanupTracker.isSweepInFlight())

            // 3. Probe at tProbe = 10.5s (within cooldown of t1 = 10s):
            // Because clearUnverified() bumped sweepGeneration, the failed startThread must NOT restore t1.
            val tProbe = TimeUnit.SECONDS.toNanos(10L) + TimeUnit.MILLISECONDS.toNanos(500L)
            ElevatedCleanupTracker.quarantineUnverified(FastAliveProcess())
            assertTrue(
                "sweepGeneration must prevent restoring stale prevStart after concurrent clearUnverified",
                ElevatedCleanupTracker.kickSweepAsync { tProbe },
            )
            pollTrue("post-OOM sweep must finish", 15_000L) {
                !ElevatedCleanupTracker.isSweepInFlight()
            }

            // 4. Verify normal rollback (no concurrent clearUnverified): failing at t2 = 12s restores tProbe = 10.5s,
            // so a retry at t2 = 12s (1.5s >= 1s after 10.5s) immediately succeeds rather than staying throttled at 12s.
            val failedNormal = ElevatedCleanupTracker.kickSweepAsyncWithThreadStarter(
                nowNanos = { t2 },
                startThread = { throw OutOfMemoryError("simulated OOM without concurrent clear") },
            )
            assertFalse(failedNormal)
            assertFalse(ElevatedCleanupTracker.isSweepInFlight())
            assertTrue(
                "failed startThread without concurrent clear must roll back lastSweepStartNanos",
                ElevatedCleanupTracker.kickSweepAsync { t2 },
            )
            pollTrue("rollback retry sweep must finish", 15_000L) {
                !ElevatedCleanupTracker.isSweepInFlight()
            }
        } finally {
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
            pollPendingTo(0)
            awaitNoQuarantineThreads()
        }
    }
}
