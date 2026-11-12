package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 2 late-spawn ownership＋deadline preflight 迴歸（PR140 Phase 2，P1-2／P1-3）。
 *
 * Root＋Shizuku 雙軌，全 fake，零真機、零 su、零 binder。
 *
 * 誠實前提（見 [ElevatedCallSupervisor] P1-2 段）：Binder 已送達不可撤回。
 * spawn 阻塞體一旦向系統提交建進程，呼叫方超時只能 late compensation，
 * 不能宣稱「未建成」。本檔鎖定：
 * - 先 timeout、之後才釋放 latch 讓 spawn 回傳：late Process 必被 destroy、
 *   無 live orphan（`elevated-supervisor／late-spawn` 全收斂）、tracker 收斂
 *   （outstanding 回 0、單次不清創不熔斷）、verdict `TimedOut(cleanupIncomplete=true)`；
 * - `timeoutMs=0`（及負值）：零特權進程（spawn 計數 0、supervisor worker 從未啟動，
 *   `cleanupIncomplete=false` 誠實無缺口）；
 * - supervisor 入口 preflight：已逾期 deadline 不啟 worker（block 計數 0）；
 * - drain 入口 preflight：零 budget 不起 gobbler／stdin-closer，仍監督殺＋有界驗證。
 */
class ElevatedRunnerPhase2LateSpawnTest {

    private val privateRoot = "/data/data/files"

    /**
     * 終審殘留隔離（Phase 1 P1-1 風格）：每用例首尾歸零 consecutive＋輪詢收斂
     * outstanding/active，避免同 JVM 內熔斷污染後測。
     */
    @Before fun setUpBreakerIsolation() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
    }

    @After fun tearDownBreakerIsolation() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
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

    private fun awaitNoElevatedThreads(boundMs: Long = 10_000L) {
        val names = setOf(
            "elevated-stdout",
            "elevated-stderr",
            "elevated-stdin-close",
            "elevated-cleanup",
            "elevated-supervisor",
            "elevated-late-spawn",
            "elevated-wait",
            "elevated-verify",
        )
        val deadline = System.currentTimeMillis() + boundMs
        while (true) {
            val alive = Thread.getAllStackTraces().keys.filter {
                it.name in names && it.isAlive
            }
            if (alive.isEmpty()) return
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("orphan elevated threads: $alive")
            }
            Thread.sleep(50L)
        }
    }

    /** 不可中斷阻塞（吞 interrupt 續等，模擬 Binder stall；釋放 latch 才返回）。 */
    private fun stuckAwait(latch: CountDownLatch) {
        while (true) {
            try {
                latch.await()
                break
            } catch (_: InterruptedException) {
                // 吞 interrupt 續等（不可靠中斷，呼叫方有界返回，worker 殘留追蹤）。
            }
        }
    }

    private fun <T> pollRefNonNull(ref: AtomicReference<T>, what: String, boundMs: Long = 10_000L): T {
        val deadline = System.currentTimeMillis() + boundMs
        while (true) {
            val v = ref.get()
            if (v != null) return v
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("$what was never delivered")
            }
            Thread.sleep(20L)
        }
    }

    private fun pollTrue(what: String, boundMs: Long = 10_000L, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + boundMs
        while (!cond()) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("$what did not become true")
            }
            Thread.sleep(20L)
        }
    }

    private fun rootRunner(
        spawn: (List<String>) -> Process,
        killer: (Process) -> Unit = { it.destroy() },
    ) = RootSuRunner(
        privateRoot = privateRoot,
        bridgeGranted = GrantFixtures.bridgeOn,
        activationCheck = GrantFixtures.activationOn,
        spawn = spawn,
        suBinary = "/system/bin/su",
        killer = killer,
        grantCheck = GrantFixtures.allowAlways,
    )

    private fun shizukuRunner(
        spawn: (List<String>) -> Process,
        killer: (Process) -> Unit = { it.destroy() },
    ) = ShizukuShellRunner(
        privateRoot = privateRoot,
        bridgeGranted = GrantFixtures.bridgeOn,
        activationCheck = GrantFixtures.activationOn,
        pingBinder = { true },
        spawn = spawn,
        status = { ShizukuStatus.Available(15) },
        killer = killer,
        grantCheck = GrantFixtures.allowAlways,
    )

    // ---- P1-2：先 timeout、後交付的 late Process 必補償（Root／Shizuku） ----

    @Test fun rootLateSpawnAfterTimeoutIsCompensated() {
        val latch = CountDownLatch(1)
        val lateRef = AtomicReference<Process?>()
        val runner = rootRunner(
            spawn = {
                stuckAwait(latch)
                FakeProcess("late".toByteArray()).also { lateRef.set(it) }
            },
        )
        try {
            val start = System.currentTimeMillis()
            val r = runner.runBinary(ElevatedRequest(listOf("id"), "P2-LATE"), 300L)
            val elapsed = System.currentTimeMillis() - start
            assertTrue("$r", r is ElevatedBinaryResult.TimedOut)
            r as ElevatedBinaryResult.TimedOut
            // 逾期即缺口：返回當下不知 late Process 是否送達，不宣稱 cleaned。
            assertTrue("late-spawn gap must flag, got $r", r.cleanupIncomplete)
            assertTrue("elapsed=$elapsed", elapsed < 12_000L)
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            // supervisor worker 仍殘留於 latch（outstanding 1），呼叫方已返回。
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            // 釋放 latch → worker 交付逾期 Process → worker 內有界補償（destroy）。
            latch.countDown()
            val late = pollRefNonNull(lateRef, "late spawn")
            pollTrue("late process must be destroyed") { (late as FakeProcess).destroyed }
            pollOutstandingTo(0)
            pollActiveTo(0)
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            awaitNoElevatedThreads()
        } finally {
            latch.countDown()
            pollOutstandingTo(0)
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
    }

    @Test fun shizukuLateSpawnAfterTimeoutIsCompensated() {
        val latch = CountDownLatch(1)
        val lateRef = AtomicReference<Process?>()
        val runner = shizukuRunner(
            spawn = {
                stuckAwait(latch)
                FakeProcess("late".toByteArray()).also { lateRef.set(it) }
            },
        )
        try {
            val r = runner.runBinary(ElevatedRequest(listOf("id"), "P2-LATE"), 300L)
            assertTrue("$r", r is ElevatedBinaryResult.TimedOut)
            r as ElevatedBinaryResult.TimedOut
            assertTrue("late-spawn gap must flag, got $r", r.cleanupIncomplete)
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            latch.countDown()
            val late = pollRefNonNull(lateRef, "late spawn")
            pollTrue("late process must be destroyed") { (late as FakeProcess).destroyed }
            pollOutstandingTo(0)
            pollActiveTo(0)
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            awaitNoElevatedThreads()
        } finally {
            latch.countDown()
            pollOutstandingTo(0)
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
    }

    // ---- P1-3：timeoutMs=0／負值零特權進程（Root／Shizuku） ----

    @Test fun rootZeroTimeoutSpawnsNothing() {
        val spawns = AtomicInteger(0)
        val runner = rootRunner(
            spawn = {
                spawns.incrementAndGet()
                FakeProcess("ok".toByteArray())
            },
        )
        for (t in listOf(0L, -100L)) {
            val start = System.currentTimeMillis()
            val r = runner.runBinary(ElevatedRequest(listOf("id"), "P2-ZERO"), t)
            val elapsed = System.currentTimeMillis() - start
            assertTrue("timeoutMs=$t got $r", r is ElevatedBinaryResult.TimedOut)
            assertEquals("timeoutMs=$t must not spawn", 0, spawns.get())
            // worker 從未啟動：無 late 缺口，旗標誠實為 false。
            assertFalse("timeoutMs=$t clean preflight must not flag", (r as ElevatedBinaryResult.TimedOut).cleanupIncomplete)
            assertTrue("elapsed=$elapsed", elapsed < 5_000L)
        }
        assertEquals(0, ElevatedCleanupTracker.outstanding())
        assertEquals(0, ElevatedCleanupTracker.activeCalls())
        assertFalse(ElevatedCleanupTracker.isBreakerOpen())
        awaitNoElevatedThreads()
    }

    @Test fun shizukuZeroTimeoutSpawnsNothing() {
        val spawns = AtomicInteger(0)
        val runner = shizukuRunner(
            spawn = {
                spawns.incrementAndGet()
                FakeProcess("ok".toByteArray())
            },
        )
        for (t in listOf(0L, -100L)) {
            val r = runner.runBinary(ElevatedRequest(listOf("id"), "P2-ZERO"), t)
            assertTrue("timeoutMs=$t got $r", r is ElevatedBinaryResult.TimedOut)
            assertEquals("timeoutMs=$t must not spawn", 0, spawns.get())
            assertFalse("timeoutMs=$t clean preflight must not flag", (r as ElevatedBinaryResult.TimedOut).cleanupIncomplete)
        }
        assertEquals(0, ElevatedCleanupTracker.outstanding())
        assertEquals(0, ElevatedCleanupTracker.activeCalls())
        assertFalse(ElevatedCleanupTracker.isBreakerOpen())
        awaitNoElevatedThreads()
    }

    // ---- P1-3：supervisor 入口 preflight（已逾期即不啟 worker） ----

    @Test fun spawnWorkerNeverStartsWhenDeadlineExpired() {
        val started = AtomicInteger(0)
        val past = System.nanoTime() - 1_000_000L
        val res = ElevatedCallSupervisor.awaitSpawn(
            past,
            { false },
            { it.destroy() },
        ) {
            started.incrementAndGet()
            FakeProcess("x".toByteArray())
        }
        assertTrue("$res", res is ElevatedCallSupervisor.Res.Expired)
        assertFalse((res as ElevatedCallSupervisor.Res.Expired).hasLinger)
        assertEquals("block must never run", 0, started.get())
        assertEquals(0, ElevatedCleanupTracker.outstanding())
    }

    // ---- P1-3：drain 入口 preflight（零 budget 不起 gobbler，仍監督殺） ----

    @Test fun drainZeroBudgetKillsWithoutThreads() {
        var kills = 0
        val p = FakeProcess("ok".toByteArray())
        val start = System.currentTimeMillis()
        val d = ElevatedProcessIo.drain(
            p,
            0L,
            {
                kills++
                it.destroy()
            },
            { Thread.currentThread().isInterrupted },
        )
        val elapsed = System.currentTimeMillis() - start
        assertTrue("must time out, got timedOut=${d.timedOut}", d.timedOut)
        assertEquals(-1, d.exitCode)
        assertEquals("preflight must still attempt killer once", 1, kills)
        assertTrue("process must be destroyed", p.destroyed)
        // kill 已驗證（FakeProcess destroy→waitFor 真）：無殘留，旗標誠實為 false。
        assertFalse("verified kill must not flag, got $d", d.cleanupIncomplete)
        assertTrue("elapsed=$elapsed", elapsed < 5_000L)
        assertEquals(0, ElevatedCleanupTracker.outstanding())
    }

    /** drain preflight 殺不可驗證（no-op killer＋waitFor 恒 false）即旗標 true（監測穿透）。 */
    private class UnverifiableHangProcess : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = false
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }

    @Test fun drainZeroBudgetUnverifiableKillFlagsIncomplete() {
        val d = ElevatedProcessIo.drain(
            UnverifiableHangProcess(),
            0L,
            { /* no-op killer：驗證必失敗 */ },
            { Thread.currentThread().isInterrupted },
        )
        assertTrue(d.timedOut)
        assertTrue("unverifiable kill must flag, got $d", d.cleanupIncomplete)
        pollOutstandingTo(0)
        assertFalse(ElevatedCleanupTracker.isBreakerOpen())
    }
}
