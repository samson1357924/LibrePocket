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
 * - 零真機、零 su、零 binder；排序一律 latch／barrier＋假時鐘，不靠 sleep 時序；
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

    @Before fun setUpIsolation() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        pollPendingTo(0)
    }

    @After fun tearDownIsolation() {
        ElevatedCleanupTracker.clearUnverified()
        repeat(8) { ElevatedCleanupTracker.releaseSlot() }
        repeat(8) { ElevatedCleanupTracker.leaveCall() }
        pollOutstandingTo(0)
        pollActiveTo(0)
        pollPendingTo(0)
        ElevatedCleanupTracker.noteClean()
        awaitNoQuarantineThreads()
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
        try {
            repeat(ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS) {
                assertTrue("slot $it must grant below MAX", ElevatedCleanupTracker.acquireSlot())
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
            repeat(ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS + 1) {
                ElevatedCleanupTracker.releaseSlot()
            }
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
        }
        assertFalse(ElevatedCleanupTracker.isSaturated())
    }

    // ---- 2. 飽和 markLingering fail-closed（不佔槽、終態、不欠釋放） ----

    @Test fun markLingeringAtSaturationTakesNoSlotFailClosed() {
        repeat(ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS) {
            assertTrue(ElevatedCleanupTracker.acquireSlot())
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
            // 飽和即終態、不佔槽（outstanding 封頂不增）。
            assertEquals(2, state.get())
            assertEquals(
                ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS,
                ElevatedCleanupTracker.outstanding(),
            )
            // 同 state 重複 mark 不重佔。
            ElevatedCleanupTracker.markLingering(worker, state)
            assertEquals(2, state.get())
            assertEquals(
                ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS,
                ElevatedCleanupTracker.outstanding(),
            )
        } finally {
            latch.countDown()
            worker.join(5_000L)
            repeat(ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS + 1) {
                ElevatedCleanupTracker.releaseSlot()
            }
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
        }
        assertEquals(0, ElevatedCleanupTracker.outstanding())
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
            repeat(8) { ElevatedCleanupTracker.releaseSlot() }
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
        }
        assertEquals(0, ElevatedCleanupTracker.consecutiveCount())
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
        fun resetAll() {
            ElevatedCleanupTracker.clearUnverified()
            repeat(8) { ElevatedCleanupTracker.releaseSlot() }
            repeat(8) { ElevatedCleanupTracker.leaveCall() }
            pollOutstandingTo(0)
            pollActiveTo(0)
            ElevatedCleanupTracker.noteClean()
        }
        try {
            // a) backlog＋breaker＋quarantine 三門同立 → BACKLOG（首門優先）。
            resetAll()
            repeat(ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS) {
                ElevatedCleanupTracker.acquireSlot()
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
            resetAll()
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
            resetAll()
            ElevatedCleanupTracker.acquireSlot()
            ElevatedCleanupTracker.quarantineUnverified(FastAliveProcess())
            assertEquals(
                ElevatedCleanupTracker.Admission.BACKLOG_FULL,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())

            // d) quarantine 單門（outstanding=0、consecutive=1、預留通過）→ QUARANTINE。
            resetAll()
            ElevatedCleanupTracker.noteUnclean()
            ElevatedCleanupTracker.quarantineUnverified(FastAliveProcess())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            assertFalse(ElevatedCleanupTracker.isSaturated())
            assertEquals(
                ElevatedCleanupTracker.Admission.QUARANTINE,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())

            // e) CONCURRENCY_FULL 死分支仍可達：僅測用 tryEnterCall 預填 active=4。
            resetAll()
            repeat(4) { assertTrue(ElevatedCleanupTracker.tryEnterCall()) }
            assertEquals(4, ElevatedCleanupTracker.activeCalls())
            // e) active=4 時 reservation 必為 CONCURRENCY_FULL：classifyAdmissionLocked
            // 先查 active>=MAX_SLOTS（併發門）才查 reservation，順序調換即迴歸，
            // 故此處精確斷言（既有 reservationMatrixDirect 亦同）。
            val verdict = ElevatedCleanupTracker.tryEnterReserved()
            assertEquals(
                ElevatedCleanupTracker.Admission.CONCURRENCY_FULL,
                verdict,
            )
            assertEquals(4, ElevatedCleanupTracker.activeCalls())
        } finally {
            ElevatedCleanupTracker.clearUnverified()
            repeat(8) { ElevatedCleanupTracker.releaseSlot() }
            repeat(8) { ElevatedCleanupTracker.leaveCall() }
            pollOutstandingTo(0)
            pollActiveTo(0)
            ElevatedCleanupTracker.noteClean()
        }
        assertEquals(0, ElevatedCleanupTracker.activeCalls())
        assertEquals(0, ElevatedCleanupTracker.outstanding())
        assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
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
        assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
        assertFalse(ElevatedCleanupTracker.isQuarantined())
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
        assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
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

    // ---- 9. kick 空集合即 false（零線程、零等待） ----

    @Test fun kickSweepAsyncEmptyReturnsFalseWithoutThread() {
        assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
        assertEquals(0, ElevatedCleanupTracker.sweepPendingCount())
        val t0 = System.nanoTime()
        assertFalse(ElevatedCleanupTracker.kickSweepAsync())
        val wallMs = (System.nanoTime() - t0) / 1_000_000L
        assertTrue("empty kick must be local fast, wall=${wallMs}ms", wallMs < 500L)
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
            // 背景 kick：拋時鐘即 fallback，不外拋（回 true/false 皆合法，只斷不拋＋保持）。
            val kicked = ElevatedCleanupTracker.kickSweepAsync(throwingClock)
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
            val stall = StallSwallowProcess(latch)
            ElevatedCleanupTracker.quarantineUnverified(stall)
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            assertTrue(
                "first kick must start (lease free + cooldown clear)",
                ElevatedCleanupTracker.kickSweepAsync(nowNanos),
            )
            pollTrue("stall must be tracked as pending", 15_000L) {
                ElevatedCleanupTracker.sweepPendingCount() == 1
            }
            pollTrue("background sweep must finish", 15_000L) {
                !ElevatedCleanupTracker.isSweepInFlight()
            }
            // 代際邊界：clear 即 quarantine 空＋冷卻重置，但 stall pending 保留。
            ElevatedCleanupTracker.clearUnverified()
            assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
            assertFalse(ElevatedCleanupTracker.isQuarantined())
            assertEquals(
                "live stall worker must be retained (safe direction)",
                1,
                ElevatedCleanupTracker.sweepPendingCount(),
            )
            // 同一假時刻新 handle 即時可探（冷卻已重置；若未重置即 false）。
            val fresh = FastAliveProcess()
            ElevatedCleanupTracker.quarantineUnverified(fresh)
            assertTrue(
                "after clear the same frozen clock must allow one sweep (cooldown reset)",
                ElevatedCleanupTracker.kickSweepAsync(nowNanos),
            )
            pollTrue("second sweep must finish", 15_000L) {
                !ElevatedCleanupTracker.isSweepInFlight()
            }
            // 新代驗證被飽和跳過即保持 QUARANTINE（pending 仍 1，不翻轉放行）。
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
        assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
        assertEquals(0, ElevatedCleanupTracker.sweepPendingCount())
    }

    // ---- 12. 拒絕不佔席（BACKLOG／BREAKER／QUARANTINE 全門） ----

    @Test fun denialsNeverOccupyActiveSeat() {
        fun resetAll() {
            ElevatedCleanupTracker.clearUnverified()
            repeat(8) { ElevatedCleanupTracker.releaseSlot() }
            repeat(8) { ElevatedCleanupTracker.leaveCall() }
            pollOutstandingTo(0)
            pollActiveTo(0)
            ElevatedCleanupTracker.noteClean()
        }
        try {
            // BACKLOG（outstanding=7）。
            resetAll()
            repeat(7) { ElevatedCleanupTracker.acquireSlot() }
            assertEquals(
                ElevatedCleanupTracker.Admission.BACKLOG_FULL,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())

            // BREAKER（consecutive=4）。
            resetAll()
            repeat(4) { ElevatedCleanupTracker.noteUnclean() }
            assertEquals(
                ElevatedCleanupTracker.Admission.BREAKER_OPEN,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())

            // QUARANTINE（單門）。
            resetAll()
            ElevatedCleanupTracker.quarantineUnverified(FastAliveProcess())
            assertEquals(
                ElevatedCleanupTracker.Admission.QUARANTINE,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())

            // OK 才佔席（乾淨態首呼叫）。
            resetAll()
            assertEquals(
                ElevatedCleanupTracker.Admission.OK,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(1, ElevatedCleanupTracker.activeCalls())
            ElevatedCleanupTracker.leaveCall()
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            ElevatedCleanupTracker.clearUnverified()
            repeat(8) { ElevatedCleanupTracker.releaseSlot() }
            repeat(8) { ElevatedCleanupTracker.leaveCall() }
            pollOutstandingTo(0)
            pollActiveTo(0)
            ElevatedCleanupTracker.noteClean()
        }
    }

    // ---- 13. peak／resetPeak 同步語義 ----

    @Test fun peakTracksMaxAndResetPeakRebases() {
        ElevatedCleanupTracker.resetPeak()
        try {
            assertEquals(0, ElevatedCleanupTracker.outstanding())
            repeat(2) { ElevatedCleanupTracker.acquireSlot() }
            assertEquals(2, ElevatedCleanupTracker.outstanding())
            assertEquals(2, ElevatedCleanupTracker.peakOutstanding())
            ElevatedCleanupTracker.releaseSlot()
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            assertEquals("peak must hold max, not current", 2, ElevatedCleanupTracker.peakOutstanding())
            ElevatedCleanupTracker.resetPeak()
            assertEquals("resetPeak must rebase to current", 1, ElevatedCleanupTracker.peakOutstanding())
            ElevatedCleanupTracker.acquireSlot()
            assertEquals(2, ElevatedCleanupTracker.outstanding())
            assertEquals(2, ElevatedCleanupTracker.peakOutstanding())
        } finally {
            repeat(8) { ElevatedCleanupTracker.releaseSlot() }
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
        }
        assertEquals(0, ElevatedCleanupTracker.outstanding())
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
}
