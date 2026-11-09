package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round-4 Phase L 迴歸（P1-2 單 call watcher 累積上限＋P2-3 close 後 sticky 重驗，PR #140）：
 * - P1-2：slow gate 見自身 watcher lingering 即該 gate 失敗（`Expired` fail-closed，
 *   不 handoff 下一 gate／spawn）。單 call 至多 1 linger（首個慢 gate 即停），
 *   slot 常數 7 推導不變（見 [ElevatedCleanupTracker.SLOTS_PER_CALL] Phase L 段）。
 *   舊碼 lingering 仍回 `Ok` 繼續下一 gate，同一 seat 可順序累積 >7 真實存活 watcher
 *   （第 8 次 acquire 失敗不計帳但仍繼續，MAX 只是 counter cap）。
 * - P2-3：close 後重驗 sticky bit（及 deadline／中斷）。bit 單調閂，watcher 第二次
 *   求值可在 close grace（500ms）期間變 true；close 前快照 false 不代表 close 後仍
 *   false。命中即按取消收斂（gate 直回 `Expired`／spawn 走 expireWorker CAS＋late
 *   cleanup），不交付 Ok（違 CancelWatch KDoc）。
 * - spawn 側 lingering／sticky 一律走 expireWorker（holder CAS＋late daemon 補償，
 *   不漏殺不直接 handoff）；安靜 hook（恒 false）下 watcher 乾淨退出即仍 Ok
 *   （T1／T2 不變）。
 *
 * 全 fake，零真機、零 su、零 binder。排序一律 latch／barrier，不靠 sleep 猜時序
 * （僅 close grace 窗口內的確定性延遲：blockDone 後 100ms 釋放 hook，恒小於 500ms
 * grace 且大於首 POLL 20ms，故釋放恒發生於 close 期間）。
 */
class ElevatedRunnerPhaseLTest {

    private val privateRoot = "/data/data/files"

    @Before fun setUpIsolation() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        ElevatedCleanupTracker.resetPeak()
        pollOutstandingTo(0)
        pollActiveTo(0)
    }

    @After fun tearDownIsolation() {
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

    private fun countLive(name: String): Int =
        Thread.getAllStackTraces().keys.count { it.name == name && it.isAlive }

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

    // ---- P1-2：slow gate lingering 即停（Root／Shizuku） ----

    private fun doSlowGateLingeringStops(
        makeRunner: (
            isCancelled: () -> Boolean,
            spawns: AtomicInteger,
            bridgeSlow: () -> Boolean,
        ) -> BinaryElevatedRunner,
    ) {
        val hookLatch = CountDownLatch(1)
        val bridgeStarted = AtomicBoolean(false)
        val watcherPhase = AtomicInteger(0)
        val spawns = AtomicInteger(0)
        // bridge 未起前一律 false（入口門＋早段快 gate 放行，不啟 watcher）；
        // bridge 起後首求值 false（observedFalse），之後永久阻塞（吞 interrupt）。
        val hook = {
            if (!bridgeStarted.get()) {
                false
            } else if (watcherPhase.incrementAndGet() == 1) {
                false
            } else {
                awaitUninterruptibly(hookLatch)
                false
            }
        }
        val runner = makeRunner(
            hook,
            spawns,
        ) {
            bridgeStarted.set(true)
            try {
                Thread.sleep(60L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            true
        }
        try {
            ElevatedCleanupTracker.resetPeak()
            val resultRef = AtomicReference<ElevatedBinaryResult?>()
            val begin = System.currentTimeMillis()
            val bg = Thread {
                resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "PL-LINGER"), 5_000L))
            }.also { it.isDaemon = true; it.start() }
            bg.join(15_000L)
            val elapsed = System.currentTimeMillis() - begin
            assertFalse("lingering gate must not hang caller", bg.isAlive)
            assertTrue("elapsed=$elapsed must stay near block+close grace, not deadline", elapsed < 4_500L)
            val b = resultRef.get()
            // P1-2：linger 即該 gate 失敗——TimedOut（零 spawn），非 Ok handoff。
            assertTrue("lingering slow gate must expire, got $b", b is ElevatedBinaryResult.TimedOut)
            b as ElevatedBinaryResult.TimedOut
            // runner 經 preSpawnLinger(outstanding>0) 置旗，不再為 false。
            assertTrue("lingering gate expiry must flag cleanupIncomplete, got $b", b.cleanupIncomplete)
            assertEquals("lingering gate must not spawn", 0, spawns.get())
            assertEquals("seat must be released", 0, ElevatedCleanupTracker.activeCalls())
            // 單 call 至多 1 linger：block worker 已死，僅 watcher 持槽。
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            assertEquals(1, ElevatedCleanupTracker.peakOutstanding())
            assertEquals(1, ElevatedCleanupTracker.consecutiveCount())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            assertEquals(
                "alive elevated-supervisor must be exactly the lingering watcher",
                1,
                countLive("elevated-supervisor"),
            )
        } finally {
            hookLatch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
    }

    @Test fun rootSlowGateLingeringStopsCall() {
        doSlowGateLingeringStops { isCancelled, spawns, bridgeSlow ->
            RootSuRunner(
                privateRoot = privateRoot,
                bridgeGranted = bridgeSlow,
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

    @Test fun shizukuSlowGateLingeringStopsCall() {
        doSlowGateLingeringStops { isCancelled, spawns, bridgeSlow ->
            ShizukuShellRunner(
                privateRoot = privateRoot,
                bridgeGranted = bridgeSlow,
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

    // ---- P2-3：gate sticky（close 期間 bit 變 true→不 handoff，Root／Shizuku） ----
    //
    // 兩 latch 強制分支：
    // - watcherPhase 保證先看到 false（首求值 false→observedFalse）；
    // - blockDone 保證 block 已交付、caller 已進 cancelNow→close；
    // - 主執行緒在 blockDone 後 100ms（恒處於 500ms close grace 內、恒晚於首 POLL）
    //   釋放 letTrue，watcher 第二次求值在 close 期間回 true（bit latch）。
    // 舊碼 postcheck 只驗 deadline＋中斷即回 Ok；新碼重驗 bit 即 Expired。

    private fun doGateStickyDuringClose(
        makeRunner: (
            isCancelled: () -> Boolean,
            spawns: AtomicInteger,
            bridgeSlow: () -> Boolean,
        ) -> BinaryElevatedRunner,
    ) {
        val letTrue = CountDownLatch(1)
        val blockDone = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val bridgeStarted = AtomicBoolean(false)
        val watcherPhase = AtomicInteger(0)
        val spawns = AtomicInteger(0)
        val hook = {
            if (!bridgeStarted.get()) {
                false
            } else if (watcherPhase.incrementAndGet() == 1) {
                false
            } else {
                secondEntered.countDown()
                awaitUninterruptibly(letTrue)
                true
            }
        }
        val runner = makeRunner(
            hook,
            spawns,
        ) {
            bridgeStarted.set(true)
            try {
                Thread.sleep(60L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            blockDone.countDown()
            true
        }
        // 釋放執行緒：blockDone 後 100ms 釋放（caller 恒在 close grace 內）。
        val releaser = Thread {
            try {
                assertTrue("block must complete", blockDone.await(10_000L, TimeUnit.MILLISECONDS))
                Thread.sleep(100L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                letTrue.countDown()
            }
        }.also { it.isDaemon = true; it.start() }
        try {
            ElevatedCleanupTracker.resetPeak()
            val resultRef = AtomicReference<ElevatedBinaryResult?>()
            val bg = Thread {
                resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "PL-STICKY"), 5_000L))
            }.also { it.isDaemon = true; it.start() }
            bg.join(15_000L)
            assertFalse("sticky gate must not hang caller", bg.isAlive)
            // watcher 確實在 close 期間第二次求值（分支命中，非快照競態）。
            assertTrue("watcher must enter second eval", secondEntered.await(5_000L, TimeUnit.MILLISECONDS))
            val b = resultRef.get()
            assertTrue("sticky gate must expire, not handoff Ok, got $b", b is ElevatedBinaryResult.TimedOut)
            b as ElevatedBinaryResult.TimedOut
            assertEquals("sticky gate must not spawn", 0, spawns.get())
            assertEquals("seat must be released", 0, ElevatedCleanupTracker.activeCalls())
            // watcher 見 true 即乾淨退出（done 已置，bit break），無殘留、不計 breaker。
            assertEquals(0, ElevatedCleanupTracker.outstanding())
            assertEquals(0, ElevatedCleanupTracker.consecutiveCount())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
        } finally {
            letTrue.countDown()
            releaser.join(5_000L)
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
    }

    @Test fun rootGateStickyDuringCloseExpires() {
        doGateStickyDuringClose { isCancelled, spawns, bridgeSlow ->
            RootSuRunner(
                privateRoot = privateRoot,
                bridgeGranted = bridgeSlow,
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

    @Test fun shizukuGateStickyDuringCloseExpires() {
        doGateStickyDuringClose { isCancelled, spawns, bridgeSlow ->
            ShizukuShellRunner(
                privateRoot = privateRoot,
                bridgeGranted = bridgeSlow,
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

    // ---- P2-3：spawn sticky（close 期間 bit 變 true→expireWorker CAS＋late 清理） ----
    //
    // 同 gate 的兩 latch 排序，但 block 為 spawn（60ms 慢交付 Process）。
    // 舊碼直接 Ok handoff（late Process 洩漏給 caller 當成功）；新碼走 expireWorker：
    // holder CAS 取回＋late daemon 補償殺掉，本次 TimedOut(cleanupIncomplete=true)。

    private fun doSpawnStickyDuringClose(
        makeRunner: (() -> Boolean, () -> Process) -> BinaryElevatedRunner,
    ) {
        val spawns = AtomicInteger(0)
        val lateRef = AtomicReference<Process?>()
        val blockDone = CountDownLatch(1)
        val letTrue = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val spawnStarted = AtomicBoolean(false)
        val watcherPhase = AtomicInteger(0)
        val hook = {
            if (!spawnStarted.get()) {
                false
            } else if (watcherPhase.incrementAndGet() == 1) {
                false
            } else {
                secondEntered.countDown()
                awaitUninterruptibly(letTrue)
                true
            }
        }
        val spawnBody = {
            spawnStarted.set(true)
            try {
                Thread.sleep(60L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            spawns.incrementAndGet()
            FakeProcess("late".toByteArray()).also {
                lateRef.set(it)
                blockDone.countDown()
            }
        }
        val runner = makeRunner(hook, spawnBody)
        val releaser = Thread {
            try {
                assertTrue("spawn block must complete", blockDone.await(10_000L, TimeUnit.MILLISECONDS))
                Thread.sleep(100L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                letTrue.countDown()
            }
        }.also { it.isDaemon = true; it.start() }
        try {
            val resultRef = AtomicReference<ElevatedBinaryResult?>()
            val bg = Thread {
                resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "PL-SPAWN-STICKY"), 5_000L))
            }.also { it.isDaemon = true; it.start() }
            bg.join(15_000L)
            assertFalse("sticky spawn must not hang caller", bg.isAlive)
            assertTrue("watcher must enter second eval", secondEntered.await(5_000L, TimeUnit.MILLISECONDS))
            val b = resultRef.get()
            assertTrue("sticky spawn must expire, not handoff Ok, got $b", b is ElevatedBinaryResult.TimedOut)
            b as ElevatedBinaryResult.TimedOut
            assertTrue("sticky spawn expiry must flag late process, got $b", b.cleanupIncomplete)
            assertEquals("spawn body must run exactly once", 1, spawns.get())
            assertEquals("seat must be released", 0, ElevatedCleanupTracker.activeCalls())
            // late 補償：逾期 Process 被 CAS 認領＋daemon 殺掉，不洩漏為成功。
            val late = lateRef.get() as? FakeProcess
            assertTrue("late process must be delivered for compensation", late != null)
            val killDeadline = System.currentTimeMillis() + 15_000L
            while (true) {
                if (late!!.destroyed || late.forciblyDestroyed) break
                if (System.currentTimeMillis() >= killDeadline) {
                    throw AssertionError("late compensation must kill the sticky process")
                }
                Thread.sleep(20L)
            }
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
        } finally {
            letTrue.countDown()
            releaser.join(5_000L)
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
    }

    @Test fun rootSpawnStickyDuringCloseCompensates() {
        doSpawnStickyDuringClose { hook, spawnBody ->
            RootSuRunner(
                privateRoot = privateRoot,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                spawn = { spawnBody() },
                suBinary = "/system/bin/su",
                isCancelled = hook,
                grantCheck = GrantFixtures.allowAlways,
            )
        }
    }

    @Test fun shizukuSpawnStickyDuringCloseCompensates() {
        doSpawnStickyDuringClose { hook, spawnBody ->
            ShizukuShellRunner(
                privateRoot = privateRoot,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                pingBinder = { true },
                spawn = { spawnBody() },
                status = { ShizukuStatus.Available(15) },
                isCancelled = hook,
                grantCheck = GrantFixtures.allowAlways,
            )
        }
    }
}
