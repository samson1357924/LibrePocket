package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellQuota
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR140 Phase1 迴歸（P1-1 ghost／P1-2 雙預算／P1-3 kill 少計／P0-1 breaker 沖掉）。
 * 全 fake，零真機、零 su、零 binder。
 *
 * 串行契約下預填代理多 stall 疊加，真實多 drain 疊加由本檔混合測試覆蓋
 * （Z1／AA1／AB1 逐次累積＋預填為串行契約下的代理，見各檔 header）。
 */
class ElevatedRunnerP1FixTest {

    private val privateRoot = "/data/data/files"

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
        val names = setOf("elevated-stdout", "elevated-stderr", "elevated-stdin-close", "elevated-cleanup")
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

    private fun makeRoot(spawn: (List<String>) -> Process): RootSuRunner = RootSuRunner(
        privateRoot = privateRoot,
        bridgeGranted = GrantFixtures.bridgeOn,
        activationCheck = GrantFixtures.activationOn,
        spawn = spawn,
        suBinary = "/system/bin/su",
        grantCheck = GrantFixtures.allowAlways,
    )

    // ---- P1-1: ghost race ----

    /**
     * ghost 機率性迴歸（P0-1 三態）：短命 worker finally 與 mark 賽跑，終值必須回 0。
     * 機率性（非 barrier 精控）：精確交錯由下兩確定性測試覆蓋
     *（[markBeforeFinallyReleasesOnce]／[finallyBeforeMarkTakesNoSlot]），此處僅以
     * 200 次循環掃描殘留 ghost（舊碼 CAS＋雙讀窗口下偶發 +1 永久殘留）。
     */
    @Test fun ghostRaceShortLivedVsMarkFinalZeroPeakAtMostOne() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        try {
            var peak = 0
            repeat(200) {
                val state = AtomicInteger(0)
                val worker = Thread({
                    try {
                        // 極短命：立即返回，finally 與 mark 賽跑。
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
                worker.start()
                ElevatedCleanupTracker.markLingering(worker, state)
                val cur = ElevatedCleanupTracker.outstanding()
                if (cur > peak) peak = cur
                worker.join(5_000L)
                assertFalse("worker must exit", worker.isAlive)
                // 若 mark 持槽（worker 仍存活時標記），finally 已釋放或將釋放；
                // 等待收斂到 0（grace 內）。
                val d = System.currentTimeMillis() + 2_000L
                while (state.get() == 1 && System.currentTimeMillis() < d) Thread.sleep(5L)
                // 新碼：ghost 不可能，終值必須回 0；峰值單次至多 1。
                assertTrue("peak=$peak", peak <= 1)
            }
            pollOutstandingTo(0)
            assertEquals(0, ElevatedCleanupTracker.outstanding())
            assertTrue("peak=$peak", peak <= 1)
        } finally {
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
            awaitNoElevatedThreads()
        }
    }

    /**
     * 確定交錯 A（mark 前-finally）：worker 阻塞於 latch，mark 先持槽，
     * 再釋放 latch，finally 釋放，終值回 0（恰好一次）。
     */
    @Test fun markBeforeFinallyReleasesOnce() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val latch = CountDownLatch(1)
        val state = AtomicInteger(0)
        val entered = CountDownLatch(1)
        val worker = Thread({
            try {
                entered.countDown()
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
            assertTrue(entered.await(5_000L, TimeUnit.MILLISECONDS))
            // mark 先（worker 存活）：持槽。
            ElevatedCleanupTracker.markLingering(worker, state)
            assertEquals(1, state.get())
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            // finally 後（釋放 latch）：釋放回 0。
            latch.countDown()
            worker.join(5_000L)
            pollOutstandingTo(0)
            assertEquals(2, state.get())
            assertEquals(0, ElevatedCleanupTracker.outstanding())
        } finally {
            latch.countDown()
            worker.join(5_000L)
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
        }
    }

    /**
     * 確定交錯 B（finally 前-mark）：worker 先退出（finally 置 2），
     * 後 mark 見 TERMINATED 不佔槽（state 2，outstanding 0）。
     */
    @Test fun finallyBeforeMarkTakesNoSlot() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val state = AtomicInteger(0)
        val worker = Thread({
            try {
                // 立即返回。
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
        try {
            worker.start()
            worker.join(5_000L)
            assertFalse(worker.isAlive)
            // finally 已先執行（state==2），mark 不得重佔。
            // 等 finally 收斂（至多 2s）。
            val d = System.currentTimeMillis() + 2_000L
            while (state.get() == 0 && System.currentTimeMillis() < d) Thread.sleep(5L)
            assertEquals(2, state.get())
            ElevatedCleanupTracker.markLingering(worker, state)
            assertEquals(2, state.get())
            assertEquals(0, ElevatedCleanupTracker.outstanding())
        } finally {
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
        }
    }

    @Test fun markLingeringExitedThreadTakesNoSlot() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        try {
            val t = Thread({ })
            t.start()
            t.join(5_000L)
            assertFalse(t.isAlive)
            val state = AtomicInteger(0)
            ElevatedCleanupTracker.markLingering(t, state)
            assertEquals(2, state.get())
            assertEquals(0, ElevatedCleanupTracker.outstanding())
        } finally {
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
        }
    }

    @Test fun markLingeringSameThreadTwiceTakesOneSlot() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
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
            // 等存活。
            val d = System.currentTimeMillis() + 5_000L
            while (!worker.isAlive && System.currentTimeMillis() < d) Thread.sleep(5L)
            assertTrue(worker.isAlive)
            ElevatedCleanupTracker.markLingering(worker, state)
            assertEquals(1, state.get())
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            // 第二次同線程：已計數，不重複佔槽。
            ElevatedCleanupTracker.markLingering(worker, state)
            assertEquals(1, state.get())
            assertEquals(1, ElevatedCleanupTracker.outstanding())
        } finally {
            latch.countDown()
            worker.join(5_000L)
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
        }
        assertEquals(0, ElevatedCleanupTracker.outstanding())
    }

    @Test fun markLingeringCountedTrueNoReacquire() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val latch = CountDownLatch(1)
        val state = AtomicInteger(1)
        // 預置 1=held（模擬已持槽），mark 不得重複 acquire。
        // 為避免污染全域計數，先佔一槽與 state 對應，測完釋放。
        ElevatedCleanupTracker.acquireSlot()
        val worker = Thread({
            try {
                latch.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        })
        worker.isDaemon = true
        try {
            worker.start()
            val d = System.currentTimeMillis() + 5_000L
            while (!worker.isAlive && System.currentTimeMillis() < d) Thread.sleep(5L)
            ElevatedCleanupTracker.markLingering(worker, state)
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            assertEquals(1, state.get())
        } finally {
            latch.countDown()
            worker.join(5_000L)
            // 手動釋放預置槽。
            state.set(2)
            ElevatedCleanupTracker.releaseSlot()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
        }
    }

    // ---- P1-2: mixed backlog ----

    /** 不可中斷 destroy stall（同 AA1 語義）。 */
    private class StallDestroyProcess(latch: CountDownLatch) : Process() {
        private val l = latch
        @Volatile private var destroyed = false
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = destroyed
        override fun exitValue(): Int = 0
        override fun destroy() {
            while (true) {
                try {
                    l.await()
                    break
                } catch (_: InterruptedException) {
                    // 吞 interrupt 續等。
                }
            }
            destroyed = true
        }
        override fun destroyForcibly(): Process {
            destroyed = true
            return this
        }
    }

    @Test fun mixedBacklogPrefill3Burst8PeakBoundedReasonsBacklog() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        // 預填 3 槽。
        repeat(3) { ElevatedCleanupTracker.acquireSlot() }
        assertEquals(3, ElevatedCleanupTracker.outstanding())
        // 同步峰值（替代 sleep 採樣）：acquire 同鎖內記錄，無遺漏。
        ElevatedCleanupTracker.resetPeak()
        val destroyLatch = CountDownLatch(1)
        val spawns = AtomicInteger(0)
        val runner = makeRoot {
            spawns.incrementAndGet()
            StallDestroyProcess(destroyLatch)
        }
        try {
            val n = 8
            val results = arrayOfNulls<ElevatedBinaryResult>(n)
            val start = CountDownLatch(1)
            val threads = (0 until n).map { i ->
                Thread {
                    try {
                        start.await()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    results[i] = runner.runBinary(ElevatedRequest(listOf("id"), "P1MIX"), 400L)
                }.also { it.isDaemon = true; it.start() }
            }
            start.countDown()
            threads.forEach { it.join(30_000L) }
            assertTrue(threads.none { it.isAlive })
            // 新碼：3 預填＋預留 4/呼叫 → 3+4>4，全拒，零 spawn，峰值仍 3。
            // 舊碼：isSaturated(3<4) 放行 4 併發，各 1 槽 → 峰值 7，4 拒為 concurrency。
            assertEquals("spawns=${spawns.get()}", 0, spawns.get())
            assertEquals(3, ElevatedCleanupTracker.outstanding())
            val peak = ElevatedCleanupTracker.peakOutstanding()
            assertTrue("peak=$peak", peak <= ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS)
            val denied = results.filterIsInstance<ElevatedBinaryResult.Denied>()
            assertEquals(n, denied.size)
            assertTrue(denied.all { it.reason == ShellDeny.QUOTA_EXCEEDED })
            assertTrue(
                "msgs=${denied.map { it.message }}",
                denied.all { it.message.contains("backlog full") },
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            destroyLatch.countDown()
            // 釋放預填。
            repeat(3) { ElevatedCleanupTracker.releaseSlot() }
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
        pollActiveTo(0)
    }

    // ---- P1-3: kill + lingering gobbler ----

    /** read 吞 interrupt 阻塞（latch 釋放前永不 EOF）；close 立即返回（清理 worker 可完成）。 */
    private class UninterruptibleReadInput(private val latch: CountDownLatch) : InputStream() {
        override fun read(): Int {
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // 吞 interrupt 續等。
                }
            }
            return -1
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val v = read()
            return v
        }
    }

    private class KillOkGobblerStuckProcess(private val latch: CountDownLatch) : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = UninterruptibleReadInput(latch)
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            try {
                Thread.sleep(unit.toMillis(timeout))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return false
        }
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }

    @Test fun killWithLingeringGobblerCountsUncleanOnce() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val latch = CountDownLatch(1)
        val runner = makeRoot { KillOkGobblerStuckProcess(latch) }
        try {
            val b = runner.runBinary(ElevatedRequest(listOf("id"), "P1KILL"), 300L)
            assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
            b as ElevatedBinaryResult.TimedOut
            assertTrue("flag expected", b.cleanupIncomplete)
            // P1-3：kill 清理完成但 gobbler 殘留 → 終審 noteUnclean，consecutive==1。
            // 舊碼 kill 路徑早終審 noteClean，後續 linger 只置旗不計 → 0。
            assertEquals(1, ElevatedCleanupTracker.consecutiveCount())
            assertEquals(1, ElevatedCleanupTracker.outstanding())
        } finally {
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    // ---- P0-1: clean-during-backlog ----

    @Test fun cleanDuringBacklogDoesNotResetBreaker() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        try {
            // P0-1 用 drain 直測（runner 入口 backlog 門會拒 spawn，無法跑到 drain；
            // drain 層才是終審歸零點，見 ElevatedProcessIo）。
            // 階段一：3 linger＋1 clean drain，outstanding 保持 3，breaker 不歸零。
            repeat(3) { ElevatedCleanupTracker.acquireSlot() }
            assertEquals(3, ElevatedCleanupTracker.outstanding())
            val d = ElevatedProcessIo.drain(FakeProcess("ok".toByteArray()), 2_000L)
            assertFalse(d.timedOut)
            assertFalse(d.cleanupIncomplete)
            assertEquals(3, ElevatedCleanupTracker.outstanding())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            assertEquals(0, ElevatedCleanupTracker.consecutiveCount())
            // 再 1 unclean → 1（spec 字面）。
            ElevatedCleanupTracker.noteUnclean()
            assertEquals(1, ElevatedCleanupTracker.consecutiveCount())
            // 階段二（P0-1 區分新舊）：consecutive=2＋outstanding=3 時 clean drain
            // 不得歸零（舊碼無條件 noteClean → 0，新碼保持 → 2）。
            // 先釋放階段一的 unclean 計數：outstanding 仍 3 時 noteClean 保持，
            // 故先清槽再重置，避免閂鎖污染。
            repeat(3) { ElevatedCleanupTracker.releaseSlot() }
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            assertEquals(0, ElevatedCleanupTracker.consecutiveCount())
            repeat(2) { ElevatedCleanupTracker.noteUnclean() }
            assertEquals(2, ElevatedCleanupTracker.consecutiveCount())
            repeat(3) { ElevatedCleanupTracker.acquireSlot() }
            val d2 = ElevatedProcessIo.drain(FakeProcess("ok".toByteArray()), 2_000L)
            assertFalse(d2.timedOut)
            assertFalse(d2.cleanupIncomplete)
            assertEquals(
                "clean during backlog must not reset breaker",
                2,
                ElevatedCleanupTracker.consecutiveCount(),
            )
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            // 舊碼此處 consecutive 會被沖成 0；新碼保持 2，再 2 次 unclean 即熔斷。
            repeat(2) { ElevatedCleanupTracker.noteUnclean() }
            assertTrue(ElevatedCleanupTracker.isBreakerOpen())
        } finally {
            repeat(8) { ElevatedCleanupTracker.releaseSlot() }
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
        assertEquals(0, ElevatedCleanupTracker.consecutiveCount())
    }

    // ---- 雙 gobbler 同 call 佔 2 槽 ----

    @Test fun dualGobblerSameCallTakesTwoSlots() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val latch = CountDownLatch(1)
        val c1 = AtomicInteger(0)
        val c2 = AtomicInteger(0)
        // 兩存活執行緒（latch 阻塞）各 mark 一次 → outstanding+=2。
        val t1 = Thread({
            try {
                latch.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                synchronized(c1) {
                    if (c1.get() == 1) {
                        c1.set(2)
                        ElevatedCleanupTracker.releaseSlot()
                    } else if (c1.get() == 0) {
                        c1.set(2)
                    }
                }
            }
        })
        val t2 = Thread({
            try {
                latch.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                synchronized(c2) {
                    if (c2.get() == 1) {
                        c2.set(2)
                        ElevatedCleanupTracker.releaseSlot()
                    } else if (c2.get() == 0) {
                        c2.set(2)
                    }
                }
            }
        })
        t1.isDaemon = true
        t2.isDaemon = true
        try {
            t1.start()
            t2.start()
            val d = System.currentTimeMillis() + 5_000L
            while ((!t1.isAlive || !t2.isAlive) && System.currentTimeMillis() < d) Thread.sleep(5L)
            assertTrue(t1.isAlive && t2.isAlive)
            ElevatedCleanupTracker.markLingering(t1, c1)
            ElevatedCleanupTracker.markLingering(t2, c2)
            assertEquals(2, ElevatedCleanupTracker.outstanding())
        } finally {
            latch.countDown()
            t1.join(5_000L)
            t2.join(5_000L)
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
        }
        assertEquals(0, ElevatedCleanupTracker.outstanding())
    }

    // ---- activeCalls 洩漏 ----

    @Test fun zeroSpawnRejectionsLeaveNoActiveCall() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        try {
            fun assertNoLeak(tag: String, block: () -> ElevatedBinaryResult) {
                val r = block()
                assertTrue("$tag: $r", r is ElevatedBinaryResult.Denied)
                assertEquals("$tag: active=${ElevatedCleanupTracker.activeCalls()}", 0, ElevatedCleanupTracker.activeCalls())
            }
            // validation 拒（黑名單）。
            assertNoLeak("validate") {
                makeRoot { FakeProcess("ok".toByteArray()) }
                    .runBinary(ElevatedRequest(listOf("rm", "-rf", "/"), "LEAK"), 1_000L)
            }
            // reasonCode 空白。
            assertNoLeak("reason") {
                makeRoot { FakeProcess("ok".toByteArray()) }
                    .runBinary(ElevatedRequest(listOf("id"), ""), 1_000L)
            }
            // grant invalid。
            assertNoLeak("grantInvalid") {
                RootSuRunner(
                    privateRoot = privateRoot,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = GrantFixtures.activationOn,
                    spawn = { FakeProcess("ok".toByteArray()) },
                    suBinary = "/system/bin/su",
                    grantCheck = GrantFixtures.invalid("nope"),
                ).runBinary(ElevatedRequest(listOf("id"), "LEAK"), 1_000L)
            }
            // grant 抛。
            assertNoLeak("grantThrow") {
                RootSuRunner(
                    privateRoot = privateRoot,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = GrantFixtures.activationOn,
                    spawn = { FakeProcess("ok".toByteArray()) },
                    suBinary = "/system/bin/su",
                    grantCheck = { throw RuntimeException("boom") },
                ).runBinary(ElevatedRequest(listOf("id"), "LEAK"), 1_000L)
            }
            // activation off。
            assertNoLeak("activationOff") {
                RootSuRunner(
                    privateRoot = privateRoot,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = { false },
                    spawn = { FakeProcess("ok".toByteArray()) },
                    suBinary = "/system/bin/su",
                    grantCheck = GrantFixtures.allowAlways,
                ).runBinary(ElevatedRequest(listOf("id"), "LEAK"), 1_000L)
            }
            // bridge off。
            assertNoLeak("bridgeOff") {
                RootSuRunner(
                    privateRoot = privateRoot,
                    bridgeGranted = GrantFixtures.bridgeOff,
                    activationCheck = GrantFixtures.activationOn,
                    spawn = { FakeProcess("ok".toByteArray()) },
                    suBinary = "/system/bin/su",
                    grantCheck = GrantFixtures.allowAlways,
                ).runBinary(ElevatedRequest(listOf("id"), "LEAK"), 1_000L)
            }
            // quota 耗盡（先佔滿配額，runner 准入成功後配額拒，finally 釋放）。
            val tightQuota = ShellQuota(maxCalls = 1)
            val qRunner = RootSuRunner(
                privateRoot = privateRoot,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                quota = tightQuota,
                spawn = { FakeProcess("ok".toByteArray()) },
                suBinary = "/system/bin/su",
                grantCheck = GrantFixtures.allowAlways,
            )
            val first = qRunner.runBinary(ElevatedRequest(listOf("id"), "LEAK"), 1_000L)
            assertTrue("$first", first is ElevatedBinaryResult.Ok)
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            val second = qRunner.runBinary(ElevatedRequest(listOf("id"), "LEAK"), 1_000L)
            assertTrue("$second", second is ElevatedBinaryResult.Denied)
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            // spawn 抛（准入成功後抛，finally 釋放）。
            val spawnThrow = makeRoot { throw RuntimeException("spawn boom") }
                .runBinary(ElevatedRequest(listOf("id"), "LEAK"), 1_000L)
            assertTrue("$spawnThrow", spawnThrow is ElevatedBinaryResult.Failed)
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            // backlog 拒（預填滿）。
            repeat(4) { ElevatedCleanupTracker.acquireSlot() }
            try {
                val denied = makeRoot { FakeProcess("ok".toByteArray()) }
                    .runBinary(ElevatedRequest(listOf("id"), "LEAK"), 1_000L)
                assertTrue("$denied", denied is ElevatedBinaryResult.Denied)
                assertEquals(0, ElevatedCleanupTracker.activeCalls())
            } finally {
                repeat(4) { ElevatedCleanupTracker.releaseSlot() }
            }
            pollOutstandingTo(0)
            // breaker 拒。
            repeat(4) { ElevatedCleanupTracker.noteUnclean() }
            try {
                assertTrue(ElevatedCleanupTracker.isBreakerOpen())
                val denied = makeRoot { FakeProcess("ok".toByteArray()) }
                    .runBinary(ElevatedRequest(listOf("id"), "LEAK"), 1_000L)
                assertTrue("$denied", denied is ElevatedBinaryResult.Denied)
                assertEquals(0, ElevatedCleanupTracker.activeCalls())
            } finally {
                ElevatedCleanupTracker.noteClean()
            }
            // concurrency 拒：佔滿 4 席後再調 runner（預留檢查下亦拒，零 spawn）。
            // 直接以 tryEnterCall 佔滿（legacy 路徑同計 activeCalls）。
            repeat(4) { assertTrue(ElevatedCleanupTracker.tryEnterCall()) }
            try {
                val denied = makeRoot { FakeProcess("ok".toByteArray()) }
                    .runBinary(ElevatedRequest(listOf("id"), "LEAK"), 1_000L)
                assertTrue("$denied", denied is ElevatedBinaryResult.Denied)
            } finally {
                repeat(4) { ElevatedCleanupTracker.leaveCall() }
            }
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            repeat(4) { ElevatedCleanupTracker.releaseSlot() }
            repeat(4) { ElevatedCleanupTracker.leaveCall() }
            pollOutstandingTo(0)
            pollActiveTo(0)
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }
}
