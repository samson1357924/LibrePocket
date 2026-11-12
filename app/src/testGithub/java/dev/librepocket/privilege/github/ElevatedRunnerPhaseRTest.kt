package dev.librepocket.privilege.github

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round-4 Phase R（P2-1 全域 verifier 上界＋P3 冷卻）迴歸：
 * - 12 輪跨代 stress：stall handle 每代 `quarantine → kick → clear`，存活 verifier
 *   總數恆 `<=MAX_SWEEP_VERIFIERS`（預佔＋飽和跳過 fail-closed；pre-fix  pending 累積至 12）；
 * - fast-churn：`waitFor(0)` 即時 false，50x back-to-back kick 僅 1 次發起、
 *   每次 wall <5ms，冷卻窗內重 kick 即 false，推進假時鐘逾冷卻即放行，verdict 恆 QUARANTINE。
 *
 * Tracker 層確定性單測（無 runner／無真機／無 binder）：排序一律 latch／barrier，
 * 不靠 sleep 時序；冷卻時鐘經 `nowNanos` 假時鐘注入（沿用 supervisor 模式）。
 */
class ElevatedRunnerPhaseRTest {

    @Before fun setUpIsolation() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
    }

    @After fun tearDownIsolation() {
        ElevatedCleanupTracker.clearUnverified()
        repeat(8) { ElevatedCleanupTracker.releaseSlot() }
        repeat(8) { ElevatedCleanupTracker.leaveCall() }
        pollOutstandingTo(0)
        pollActiveTo(0)
        ElevatedCleanupTracker.noteClean()
    }

    private fun pollOutstandingTo(expected: Int, boundMs: Long = 15_000L) {
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

    private fun pollTrue(what: String, boundMs: Long = 15_000L, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + boundMs
        while (!cond()) {
            if (System.currentTimeMillis() >= deadline) throw AssertionError("$what did not become true")
            Thread.sleep(20L)
        }
    }

    private fun awaitNoElevatedThreads(boundMs: Long = 15_000L) {
        val names = setOf(
            "elevated-quarantine-verify",
            "elevated-quarantine-sweep",
        )
        val deadline = System.currentTimeMillis() + boundMs
        while (true) {
            val alive = Thread.getAllStackTraces().keys.filter { it.name in names && it.isAlive }
            if (alive.isEmpty()) return
            if (System.currentTimeMillis() >= deadline) throw AssertionError("orphan elevated threads: $alive")
            Thread.sleep(50L)
        }
    }

    private fun aliveQuarantineVerifyCount(): Int =
        Thread.getAllStackTraces().keys.count { it.name == "elevated-quarantine-verify" && it.isAlive }

    /**
     * P2-1 stall 模型：`waitFor(0)` 永不返回且吞中斷（跨 Binder 無時限保證），
     * 阻塞至 `latch` 放行（teardown 收斂用；測中永不放行），隨後回 false（仍存活）。
     * `exitValue` 恒抛（未退出），故預設 `isAlive()` 為 true。
     */
    private class StallSwallowProcess(private val latch: CountDownLatch) : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            stuckAwait()
            return 0
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            stuckAwait()
            return false
        }
        override fun exitValue(): Int = throw IllegalThreadStateException("process has not exited")
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
        private fun stuckAwait() {
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // 吞 interrupt 續等（不可靠中斷模型）。
                }
            }
        }
    }

    /** P3 fast 模型：`waitFor(0)` 即時回 false（存活、非 stall；呼叫側零等待）。 */
    private class FastAliveProcess : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = false
        override fun exitValue(): Int = throw IllegalThreadStateException("process has not exited")
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }

    @Test fun generationStressAcrossClearIsGloballyBounded() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        val latch = CountDownLatch(1)
        val bound = ElevatedCleanupTracker.MAX_SWEEP_VERIFIERS
        try {
            repeat(12) { i ->
                val p = StallSwallowProcess(latch)
                ElevatedCleanupTracker.quarantineUnverified(p)
                assertEquals("gen $i quarantine must hold one", 1, ElevatedCleanupTracker.quarantinedCount())
                // clear 重置冷卻（代際邊界語義），故每代 kick 皆應發起（真時鐘即可）。
                val kicked = ElevatedCleanupTracker.kickSweepAsync()
                assertTrue("gen $i kick must start (lease free + cooldown reset by clear)", kicked)
                // 等背景 sweep 收斂（stall 代 ~VERIFY+GRACE≈1s；飽和跳過代即時）。
                pollTrue("gen $i sweep must finish", 15_000L) { !ElevatedCleanupTracker.isSweepInFlight() }
                val pending = ElevatedCleanupTracker.sweepPendingCount()
                assertTrue(
                    "gen $i pending=$pending must stay <= bound=$bound (pre-fix grows to ${i + 1})",
                    pending <= bound,
                )
                assertTrue(
                    "gen $i alive verify=${aliveQuarantineVerifyCount()} must stay <= bound=$bound",
                    aliveQuarantineVerifyCount() <= bound,
                )
                ElevatedCleanupTracker.clearUnverified()
                assertEquals("gen $i quarantine must clear", 0, ElevatedCleanupTracker.quarantinedCount())
                // clear != 資源釋放：存活 worker 保留（設計），但總數仍有界。
                assertTrue(
                    "gen $i post-clear pending must stay <= bound=$bound",
                    ElevatedCleanupTracker.sweepPendingCount() <= bound,
                )
            }
            assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
            assertTrue(
                "final pending must stay <= bound=$bound",
                ElevatedCleanupTracker.sweepPendingCount() <= bound,
            )
            assertTrue(
                "final alive verify must stay <= bound=$bound",
                aliveQuarantineVerifyCount() <= bound,
            )
            // 新代驗證被飽和跳過即保持 QUARANTINE（安全方向，不翻轉放行）。
            val fresh = FastAliveProcess()
            ElevatedCleanupTracker.quarantineUnverified(fresh)
            assertEquals(
                ElevatedCleanupTracker.Admission.QUARANTINE,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            latch.countDown()
            pollTrue("stall workers must exit after latch", 15_000L) {
                ElevatedCleanupTracker.sweepPendingCount() == 0
            }
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
        assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
        assertEquals(0, ElevatedCleanupTracker.sweepPendingCount())
    }

    @Test fun fastChurnIsCooldownBoundedAndWallBounded() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        val p = FastAliveProcess()
        ElevatedCleanupTracker.quarantineUnverified(p)
        assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
        // 凍結假時鐘：50x back-to-back 皆同一時刻，冷卻只允首 kick 發起。
        val fakeNow = AtomicLong(System.nanoTime())
        val nowNanos: () -> Long = { fakeNow.get() }
        var started = 0
        try {
            repeat(50) { i ->
                val t0 = System.nanoTime()
                val kicked = ElevatedCleanupTracker.kickSweepAsync(nowNanos)
                val wallMs = (System.nanoTime() - t0) / 1_000_000L
                assertTrue(
                    "kick $i wall=${wallMs}ms must stay <5ms (local snapshot only, zero IPC)",
                    wallMs < 5L,
                )
                if (kicked) started++
            }
            assertEquals(
                "frozen-clock 50x back-to-back must start exactly once (pre-fix restarts per lease window)",
                1,
                started,
            )
            pollTrue("background sweep must finish", 15_000L) { !ElevatedCleanupTracker.isSweepInFlight() }
            // verdict 恆 QUARANTINE（fail-closed，零 spawn 語義由准入門保證）。
            assertEquals(
                ElevatedCleanupTracker.Admission.QUARANTINE,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            assertEquals(0, ElevatedCleanupTracker.sweepPendingCount())
            // 冷卻窗內重 kick 即 false（零線程）；推進假時鐘逾冷卻即放行。
            assertFalse(
                "cooldown window must suppress restart",
                ElevatedCleanupTracker.kickSweepAsync(nowNanos),
            )
            fakeNow.addAndGet(TimeUnit.MILLISECONDS.toNanos(ElevatedCleanupTracker.SWEEP_COOLDOWN_MS + 1000L))
            // per-handle 節流為 5s：同 handle 在 2s 後仍被跳過，故先換新 handle 驗冷卻放行。
            val p2 = FastAliveProcess()
            ElevatedCleanupTracker.quarantineUnverified(p2)
            assertTrue(
                "after cooldown a new handle must be allowed one sweep",
                ElevatedCleanupTracker.kickSweepAsync(nowNanos),
            )
            pollTrue("second sweep must finish", 15_000L) { !ElevatedCleanupTracker.isSweepInFlight() }
            assertEquals(
                ElevatedCleanupTracker.Admission.QUARANTINE,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
        } finally {
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
        assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
        assertEquals(0, ElevatedCleanupTracker.sweepPendingCount())
    }
}
