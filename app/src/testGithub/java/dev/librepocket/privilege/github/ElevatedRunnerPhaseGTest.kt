package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round-3 Phase G 迴歸（P2-2 deadline postcheck＋P2-3 drain pre-interrupt＋
 * P2-4 非 spawn linger 旗標，PR #140）：
 * - P2-2：[ElevatedCallSupervisor.awaitInternal] 快路徑 `Ok` 前重驗 deadline＋
 *   中斷（worker 在 deadline 後交付但 caller 延遲醒來不得回 Ok）；drain 等待循環
 *   置 `finished` 前重驗（整體逾期完成走 kill 路徑 `timedOut=true`，與 tail 中止
 *   `tailIncomplete` 區分，不新增 verdict 常數）。確定性经 latch 排序＋可注入
 *   時鐘接縫（`nowNanos`），不靠 sleep 猜調度窗口；
 * - P2-3：drain 入口 `Thread.interrupted()` 快照轉為一次性 [cancelledAtEntry]
 *   決策（預置中斷進 drain 即受監督 kill，不當健康進程跑；清旗＋出口恢復不變）；
 *   after-handoff（spawn→drain 窗口取消）＋直接 pre-interrupt 負測；
 * - P2-4：旗標契約決策 (a)（[ElevatedRunnerCommon.preSpawnLinger]）：
 *   `cleanupIncomplete` 涵蓋 background worker，`hasLinger || outstanding>0`
 *   即置旗；blocked hook／grant／status 雙 runner 一致性（`result=false` 與同
 *   request `outstanding==1` 並存即為契約違反）。
 *
 * 全 fake，零真機、零 su、零 binder。阻塞體一律吞 interrupt 續等（模擬不可靠
 * Binder 中斷；呼叫方有界返回，worker 殘留追蹤，finally 釋放 latch 收斂）。
 */
class ElevatedRunnerPhaseGTest {

    private val privateRoot = "/data/data/files"

    @Before fun setUpIsolation() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
    }

    @After fun tearDownIsolation() {
        // 測試內若預置中斷自身執行緒，須先清旗，否則污染後續測試。
        Thread.interrupted()
        pollOutstandingTo(0)
        pollActiveTo(0)
        ElevatedCleanupTracker.clearUnverified()
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

    private fun awaitNoElevatedThreads(boundMs: Long = 15_000L) {
        val names = setOf(
            "elevated-stdout",
            "elevated-stderr",
            "elevated-stdin-close",
            "elevated-cleanup",
            "elevated-supervisor",
            "elevated-wait",
            "elevated-verify",
            "elevated-late-spawn",
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

    /** 不可中斷等待（吞 interrupt 續等，模擬 blocking store／Binder stall）。 */
    private fun awaitUninterruptibly(latch: CountDownLatch) {
        while (true) {
            try {
                latch.await()
                break
            } catch (_: InterruptedException) {
                // 吞 interrupt 續等：呼叫方靠 deadline＋grace 有界回，worker 殘留追蹤。
            }
        }
    }

    /** 不可中斷阻塞 hook（充當 cancel，永不放即卡 worker 不卡 caller）。 */
    private fun stuckHook(latch: CountDownLatch): Boolean {
        awaitUninterruptibly(latch)
        return false
    }

    /** waitFor latch 門控、流 EOF、exit 0 的假進程（drain 逾期語義用）。 */
    private class LatchWaitProcess(
        private val waitEntered: CountDownLatch,
        private val waitRelease: CountDownLatch,
    ) : Process() {
        var destroyed = false
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream("ok".toByteArray())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitEntered.countDown()
            awaitUninterruptibly(waitRelease)
            return true
        }
        override fun exitValue(): Int = 0
        override fun destroy() {
            destroyed = true
        }
        override fun destroyForcibly(): Process {
            destroyed = true
            return this
        }

        private fun awaitUninterruptibly(latch: CountDownLatch) {
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // 吞 interrupt 續等（kill 中斷不解此門，靠 release 收斂）。
                }
            }
        }
    }

    // ---- P2-2：快路徑逾期交付 postcheck（確定性：advance→release 同執行緒排序） ----
    //
    // 時序保證：worker 交付（release 後）恒晚於時鐘推進（advance，先序同執行緒），
    // caller 只能在 worker 死後才見 !isAlive，故 postcheck 必見已逾期。
    // 舊碼（無 postcheck）此用例回 Ok("late-value")；新碼回 Expired。

    @Test fun supervisorFastPathOverdueDeliveryExpires() {
        val t0 = 1_000_000_000_000L
        val fakeNow = AtomicLong(t0)
        val nowNanos = { fakeNow.get() }
        val deadlineNanos = t0 + TimeUnit.MILLISECONDS.toNanos(100L)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val hookEvals = AtomicInteger(0)
        val resultRef = AtomicReference<ElevatedCallSupervisor.Res<String>?>()
        try {
            val bg = Thread {
                resultRef.set(
                    ElevatedCallSupervisor.awaitPreSpawn(
                        deadlineNanos,
                        {
                            hookEvals.incrementAndGet()
                            false
                        },
                        nowNanos,
                    ) {
                        entered.countDown()
                        awaitUninterruptibly(release)
                        "late-value"
                    },
                )
            }.also { it.isDaemon = true; it.start() }
            assertTrue("worker must enter block", entered.await(5_000L, TimeUnit.MILLISECONDS))
            // 先推進假時鐘過 deadline，再放 worker 交付：交付恒逾期。
            fakeNow.set(t0 + TimeUnit.MILLISECONDS.toNanos(5_000L))
            release.countDown()
            bg.join(15_000L)
            assertFalse("overdue delivery must not hang caller", bg.isAlive)
            val r = resultRef.get()
            assertTrue("overdue fast-path delivery must expire, got $r", r is ElevatedCallSupervisor.Res.Expired)
            // 快路徑零 hook 求值（B2 計數語義不變，watcher 從未啟動）。
            assertEquals("fast path must not evaluate hook, evals=${hookEvals.get()}", 0, hookEvals.get())
            // worker 已交付即退出，無殘留、不計 breaker（非 spawn 無 owner 缺口）。
            assertEquals(0, ElevatedCleanupTracker.outstanding())
            assertEquals(0, ElevatedCleanupTracker.consecutiveCount())
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            release.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
    }

    // ---- P2-2：drain 整體逾期完成 verdict（kill 路徑，與 tail 中止區分） ----
    //
    // waitFor 在整體 deadline 之後回 true（假時鐘 advance→release 排序保證）：
    // drain 不得置 finished 回 Ok，須走 kill 路徑（timedOut=true，
    // tailIncomplete=false——kill 路徑永不置 tail 旗）。

    @Test fun drainOverdueWaitSuccessKillsWithTimedOut() {
        val t0 = 2_000_000_000_000L
        val fakeNow = AtomicLong(t0)
        val nowNanos = { fakeNow.get() }
        val waitEntered = CountDownLatch(1)
        val waitRelease = CountDownLatch(1)
        val proc = LatchWaitProcess(waitEntered, waitRelease)
        val resultRef = AtomicReference<ElevatedProcessIo.Drained?>()
        try {
            val bg = Thread {
                resultRef.set(
                    ElevatedProcessIo.drain(
                        proc,
                        500L,
                        { it.destroy() },
                        { false },
                        nowNanos = nowNanos,
                    ),
                )
            }.also { it.isDaemon = true; it.start() }
            assertTrue("wait must be entered", waitEntered.await(5_000L, TimeUnit.MILLISECONDS))
            // 整體 deadline（t0＋500ms）已過後才放 waitFor 回 true：整體逾期完成。
            fakeNow.set(t0 + TimeUnit.MILLISECONDS.toNanos(5_000L))
            waitRelease.countDown()
            bg.join(20_000L)
            assertFalse("overdue wait must not hang caller", bg.isAlive)
            val d = resultRef.get()
            assertTrue("overdue wait success must take kill path, got $d", d!!.timedOut)
            assertFalse("kill path must not set tailIncomplete, got $d", d.tailIncomplete)
            assertEquals(-1, d.exitCode)
            assertTrue("kill must be attempted on overdue completion", proc.destroyed)
            pollOutstandingTo(0)
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            waitRelease.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
    }

    // ---- P2-3：直接 drain pre-interrupt（一次性決策＋恢復保證） ----

    @Test fun drainPreInterruptTakesSupervisedKillAndRestores() {
        val proc = FakeProcess("ok".toByteArray(), ByteArray(0), 0)
        Thread.currentThread().interrupt()
        try {
            val d = ElevatedProcessIo.drain(proc, 2_000L)
            // 預置中斷進 drain 即受監督 kill，不當健康進程跑 finished。
            assertTrue("pre-interrupt drain must take supervised kill path, got $d", d.timedOut)
            assertEquals(-1, d.exitCode)
            assertFalse("kill path must not set tailIncomplete, got $d", d.tailIncomplete)
            assertTrue("kill must be attempted on pre-interrupt entry", proc.destroyed || proc.forciblyDestroyed)
            // 出口恢復保證：caller 中斷不丟失。
            assertTrue("caller interrupt must be restored", Thread.currentThread().isInterrupted)
            pollOutstandingTo(0)
            assertFalse(ElevatedCleanupTracker.isSaturated())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
        } finally {
            Thread.interrupted()
        }
        pollOutstandingTo(0)
        assertFalse(ElevatedCleanupTracker.isBreakerOpen())
        assertEquals(0, ElevatedCleanupTracker.activeCalls())
    }

    // ---- P2-3：after-handoff 取消（spawn→drain 窄窗口，cancel token 鎖定） ----
    //
    // spawn 本體置 `handedOff=true` 後交健康進程：drain 級 watcher 在首片內觀測到
    // 位，hang 進程保證觀測窗口（50ms slice ≫ 20ms 節拍），必走 kill 而非 Ok。

    private fun doHandoffCancelKills(
        makeRunner: ((() -> Boolean), AtomicInteger, AtomicBoolean, MutableList<Process>) -> BinaryElevatedRunner,
    ) {
        val handedOff = AtomicBoolean(false)
        val spawns = AtomicInteger(0)
        val killed: MutableList<Process> = java.util.Collections.synchronizedList(mutableListOf())
        val runner = makeRunner({ handedOff.get() }, spawns, handedOff, killed)
        try {
            val resultRef = AtomicReference<ElevatedBinaryResult?>()
            val bg = Thread {
                resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "PG-HANDOFF"), 5_000L))
            }.also { it.isDaemon = true; it.start() }
            bg.join(20_000L)
            assertFalse("handoff-cancelled call must not hang", bg.isAlive)
            val b = resultRef.get()
            assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
            // spawn 已交付（窄窗口之後），kill 已監督執行。
            assertEquals("handoff must build exactly once", 1, spawns.get())
            assertEquals("handed-off process must be killed exactly once", 1, killed.size)
            val proc = killed[0] as? FakeProcess
            assertTrue("handed-off process must be destroyed", proc?.destroyed == true)
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            pollOutstandingTo(0)
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
        } finally {
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
    }

    @Test fun rootHandoffCancelKillsInsteadOfOk() {
        doHandoffCancelKills { isCancelled, spawns, handedOff, killed ->
            RootSuRunner(
                privateRoot = privateRoot,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                spawn = {
                    spawns.incrementAndGet()
                    handedOff.set(true)
                    FakeProcess("ok".toByteArray(), ByteArray(0), 0, hang = true)
                },
                suBinary = "/system/bin/su",
                killer = {
                    killed.add(it)
                    it.destroy()
                },
                isCancelled = isCancelled,
                grantCheck = GrantFixtures.allowAlways,
            )
        }
    }

    @Test fun shizukuHandoffCancelKillsInsteadOfOk() {
        doHandoffCancelKills { isCancelled, spawns, handedOff, killed ->
            ShizukuShellRunner(
                privateRoot = privateRoot,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                pingBinder = { true },
                spawn = {
                    spawns.incrementAndGet()
                    handedOff.set(true)
                    FakeProcess("ok".toByteArray(), ByteArray(0), 0, hang = true)
                },
                status = { ShizukuStatus.Available(15) },
                killer = {
                    killed.add(it)
                    it.destroy()
                },
                isCancelled = isCancelled,
                grantCheck = GrantFixtures.allowAlways,
            )
        }
    }

    // ---- P2-4：blocked hook／grant／status 旗標一致性（雙 runner） ----
    //
    // 契約 (a)：`result=false` 與同 request `outstanding==1` 並存即違反。
    // 三場景皆須 `TimedOut(cleanupIncomplete=true)`＋outstanding==1＋
    // consecutive==1＋零 spawn。

    private fun doBlockedEntryFlagsLinger(
        makeRunner: ((() -> Boolean), AtomicInteger) -> BinaryElevatedRunner,
    ) {
        val latch = CountDownLatch(1)
        val spawns = AtomicInteger(0)
        val runner = makeRunner({ stuckHook(latch) }, spawns)
        try {
            val resultRef = AtomicReference<ElevatedBinaryResult?>()
            val bg = Thread {
                resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "PG-FLAG"), 100L))
            }.also { it.isDaemon = true; it.start() }
            bg.join(15_000L)
            assertFalse("blocked entry hook must not hang caller", bg.isAlive)
            val b = resultRef.get()
            assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
            b as ElevatedBinaryResult.TimedOut
            assertTrue("blocked entry expiry must flag linger, got $b", b.cleanupIncomplete)
            assertEquals("blocked entry must not spawn", 0, spawns.get())
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            assertEquals(1, ElevatedCleanupTracker.consecutiveCount())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
        } finally {
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
    }

    @Test fun rootBlockedEntryFlagsLinger() {
        doBlockedEntryFlagsLinger { isCancelled, spawns ->
            RootSuRunner(
                privateRoot = privateRoot,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                spawn = {
                    spawns.incrementAndGet()
                    FakeProcess("ok".toByteArray())
                },
                suBinary = "/system/bin/su",
                isCancelled = isCancelled,
                grantCheck = GrantFixtures.allowAlways,
            )
        }
    }

    @Test fun shizukuBlockedEntryFlagsLinger() {
        doBlockedEntryFlagsLinger { isCancelled, spawns ->
            ShizukuShellRunner(
                privateRoot = privateRoot,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                pingBinder = { true },
                spawn = {
                    spawns.incrementAndGet()
                    FakeProcess("ok".toByteArray())
                },
                status = { ShizukuStatus.Available(15) },
                isCancelled = isCancelled,
                grantCheck = GrantFixtures.allowAlways,
            )
        }
    }

    private fun doBlockedGrantFlagsLinger(
        makeRunner: (() -> GrantCheck, () -> Boolean, AtomicInteger) -> BinaryElevatedRunner,
    ) {
        val latch = CountDownLatch(1)
        val spawns = AtomicInteger(0)
        val runner = makeRunner(
            {
                stuckHook(latch)
                GrantCheck.Valid
            },
            { false },
            spawns,
        )
        try {
            val resultRef = AtomicReference<ElevatedBinaryResult?>()
            val bg = Thread {
                resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "PG-FLAG"), 500L))
            }.also { it.isDaemon = true; it.start() }
            bg.join(15_000L)
            assertFalse("blocked grant must not hang caller", bg.isAlive)
            val b = resultRef.get()
            assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
            b as ElevatedBinaryResult.TimedOut
            assertTrue("blocked grant expiry must flag linger, got $b", b.cleanupIncomplete)
            assertEquals("blocked grant must not spawn", 0, spawns.get())
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            assertEquals(1, ElevatedCleanupTracker.consecutiveCount())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
        } finally {
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
    }

    @Test fun rootBlockedGrantFlagsLinger() {
        doBlockedGrantFlagsLinger { grantCheck, isCancelled, spawns ->
            RootSuRunner(
                privateRoot = privateRoot,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                spawn = {
                    spawns.incrementAndGet()
                    FakeProcess("ok".toByteArray())
                },
                suBinary = "/system/bin/su",
                isCancelled = isCancelled,
                grantCheck = grantCheck,
            )
        }
    }

    @Test fun shizukuBlockedGrantFlagsLinger() {
        doBlockedGrantFlagsLinger { grantCheck, isCancelled, spawns ->
            ShizukuShellRunner(
                privateRoot = privateRoot,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                pingBinder = { true },
                spawn = {
                    spawns.incrementAndGet()
                    FakeProcess("ok".toByteArray())
                },
                status = { ShizukuStatus.Available(15) },
                isCancelled = isCancelled,
                grantCheck = grantCheck,
            )
        }
    }

    @Test fun rootBlockedSuProbeFlagsLinger() {
        val latch = CountDownLatch(1)
        val spawns = AtomicInteger(0)
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            spawn = {
                spawns.incrementAndGet()
                FakeProcess("ok".toByteArray())
            },
            suBinary = null,
            suExists = {
                stuckHook(latch)
                true
            },
            isCancelled = { false },
            grantCheck = GrantFixtures.allowAlways,
        )
        try {
            val resultRef = AtomicReference<ElevatedBinaryResult?>()
            val bg = Thread {
                resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "PG-FLAG"), 500L))
            }.also { it.isDaemon = true; it.start() }
            bg.join(15_000L)
            assertFalse("blocked su probe must not hang caller", bg.isAlive)
            val b = resultRef.get()
            assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
            b as ElevatedBinaryResult.TimedOut
            assertTrue("blocked su probe expiry must flag linger, got $b", b.cleanupIncomplete)
            assertEquals("blocked su probe must not spawn", 0, spawns.get())
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            assertEquals(1, ElevatedCleanupTracker.consecutiveCount())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
        } finally {
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
    }

    @Test fun shizukuBlockedStatusFlagsLinger() {
        val latch = CountDownLatch(1)
        val spawns = AtomicInteger(0)
        val runner = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            pingBinder = { true },
            spawn = {
                spawns.incrementAndGet()
                FakeProcess("ok".toByteArray())
            },
            status = {
                stuckHook(latch)
                ShizukuStatus.Available(15)
            },
            isCancelled = { false },
            grantCheck = GrantFixtures.allowAlways,
        )
        try {
            val resultRef = AtomicReference<ElevatedBinaryResult?>()
            val bg = Thread {
                resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "PG-FLAG"), 500L))
            }.also { it.isDaemon = true; it.start() }
            bg.join(15_000L)
            assertFalse("blocked status must not hang caller", bg.isAlive)
            val b = resultRef.get()
            assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
            b as ElevatedBinaryResult.TimedOut
            assertTrue("blocked status expiry must flag linger, got $b", b.cleanupIncomplete)
            assertEquals("blocked status must not spawn", 0, spawns.get())
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            assertEquals(1, ElevatedCleanupTracker.consecutiveCount())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
        } finally {
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
    }
}
