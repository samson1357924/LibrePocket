package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellQuota
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round-3 Phase F 迴歸（P1-2 pre-admission 有界取消＋P2-1 慢輪詢 hook 統一，PR #140）：
 * - P1-2：cheap [ElevatedCleanupTracker.tryEnterReserved] 已移到任何 worker 啟動
 *   之前（含首個 cancel 門）。混合 20+ barrier 壞 hook（吞 interrupt 永久阻塞）：
 *   併發 caller 在准入處即序列化，僅單一呼叫取席並啟一個門 worker，其餘零線程
 *   拒絕（Denied，不耗 quota、零 spawn）；實際存活 `elevated-supervisor` 線程、
 *   outstanding、active 皆受明示上界；latch 釋放後完全回收。
 * - P2-1：首 false、後 blocked 的 hook 交錯全請求有界。慢輪詢 hook 一律跑
 *   [ElevatedCallSupervisor.CancelWatch] worker（caller 只看中斷＋deadline＋
 *   緩存 bit）：gate 慢路徑（bridge 阻塞＋hook 第二次求值起永久阻塞）經 deadline
 *   有界回 TimedOut（零 spawn；block worker＋watcher 各記一槽，breaker 恰好一次；
 *   非 spawn 語境旗標經 R3-PhaseG P2-4 決策 a 置 true（background worker 殘留
 *   即 `cleanupIncomplete=true`）；drain 直測（finished 但流卡住
 *   ＋hook 第二次求值起阻塞）經 tail budget 有界中止（`tailIncomplete` 真，
 *   gobbler 雙槽＋watcher 一槽，終審恰好一次）。
 * - R3-PhaseF review G1 決策 (a) 慢成功照常成功（T1／T2）：watcher 啟動後已交付
 *   Val 仍須消費——安靜 hook（恒 false）下慢於首 POLL（20ms）的 gate／spawn 成功
 *   即 Ok handoff，不燒滿 deadline 變 TimedOut（舊空分支 defer＋睡眠循環真 bug，
 *   fakes 全瞬時或永卡故全量綠未爆）。取消證據明確時仍按取消收斂（fail-closed）。
 * - T3 spawn 慢路徑首 false、後 blocked：入口 false，spawn 體卡死＋hook 第二次
 *   阻塞→有界 `TimedOut(cleanupIncomplete=true)`，殘留 worker＋watcher 各一槽，
 *   late 補償殺進程，完全回收。
 * - T4 verdict 優先級：飽和＋已取消→Denied 先於 TimedOut（准入拒絕在取消門前，
 *   hook 零求值、零 worker）。
 * - T5 准入拒絕不耗 quota：BACKLOG 拒絕前後同一 tight quota 許可數不變
 *   （`tryAcquire` 仍成功）＋零 spawn。
 * - T6 watcher 抛語義分歧：gates 側 `throwMeansCancelled=true`（hook 第二次求值
 *   抛→按取消收斂 TimedOut＋計數）；drain 側 false（抛折為未取消繼續觀察＋計數，
 *   不誤殺，tail 照常中止）。
 *
 * 全 fake，零真機、零 su、零 binder。排序一律 latch／barrier（polling 為 barrier，
 * 不靠 sleep 猜時序）；阻塞體一律吞 interrupt 續等（模擬不可靠 Binder 中斷）。
 */
class ElevatedRunnerPhaseFCancelBoundTest {

    private val privateRoot = "/data/data/files"

    @Before fun setUpIsolation() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
    }

    @After fun tearDownIsolation() {
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

    /** 不可中斷阻塞 hook（吞 interrupt 續等，模擬 blocking store 充當 cancel）。 */
    private fun stuckCancelHook(latch: CountDownLatch): Boolean {
        while (true) {
            try {
                latch.await()
                break
            } catch (_: InterruptedException) {
                // 吞 interrupt 續等：呼叫方靠 deadline＋grace 有界回，worker 殘留追蹤。
            }
        }
        return false
    }

    /** 不可中斷阻塞門體（吞 interrupt 續等，模擬 Binder supplier stall）。 */
    private fun stuckGate(release: CountDownLatch, entered: CountDownLatch? = null) {
        entered?.countDown()
        while (true) {
            try {
                release.await()
                break
            } catch (_: InterruptedException) {
                // 吞 interrupt 續等。
            }
        }
    }

    /** 已 exit 但雙流永不 EOF（latch 釋放後收斂 EOF）。 */
    private class ExitedStuckStreamsProcess(private val latch: CountDownLatch) : Process() {
        private fun awaitLatch() {
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // 吞 interrupt 續等。
                }
            }
        }
        private inner class StuckInput : InputStream() {
            override fun read(): Int {
                awaitLatch()
                return -1
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                awaitLatch()
                return -1
            }
        }
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = StuckInput()
        override fun getErrorStream(): InputStream = StuckInput()
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = true
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }

    // ---- P1-2：兩 runner 混合 24 barrier 壞 hook（pre-admission 有界） ----

    @Test fun mixedRunnersBarrierBadHookBoundedThreads() {
        val latch = CountDownLatch(1)
        val spawns = AtomicInteger(0)
        val badHook = { stuckCancelHook(latch) }
        val root = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            spawn = {
                spawns.incrementAndGet()
                FakeProcess("ok".toByteArray())
            },
            suBinary = "/system/bin/su",
            isCancelled = badHook,
            grantCheck = GrantFixtures.allowAlways,
        )
        val shizuku = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            pingBinder = { true },
            spawn = {
                spawns.incrementAndGet()
                FakeProcess("ok".toByteArray())
            },
            status = { ShizukuStatus.Available(15) },
            isCancelled = badHook,
            grantCheck = GrantFixtures.allowAlways,
        )
        // 24 caller 同時起跑（Root／Shizuku 交錯）：准入鎖內序列化，僅首個取席
        // （其門 worker 即唯一 elevated-supervisor），其餘 23 在准入處零線程拒絕。
        val n = 24
        val results = arrayOfNulls<ElevatedBinaryResult>(n)
        val start = CountDownLatch(1)
        val threads = (0 until n).map { i ->
            Thread {
                try {
                    start.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                results[i] = if (i % 2 == 0) {
                    root.runBinary(ElevatedRequest(listOf("id"), "PF-BARRIER"), 300L)
                } else {
                    shizuku.runBinary(ElevatedRequest(listOf("id"), "PF-BARRIER"), 300L)
                }
            }.also { it.isDaemon = true; it.start() }
        }
        try {
            val begin = System.currentTimeMillis()
            start.countDown()
            threads.forEach { it.join(20_000L) }
            val elapsed = System.currentTimeMillis() - begin
            assertTrue("all callers must return bounded, elapsed=$elapsed", threads.none { it.isAlive })
            assertTrue("elapsed=$elapsed must stay near timeout+grace, not hang", elapsed < 10_000L)
            // 恰好 1 取席（入口門 hook 阻塞→deadline＋grace 有界回 TimedOut），
            // 餘 23 皆准入拒絕（BACKLOG_FULL，零 spawn、不耗 quota）。
            val timedOut = results.filterIsInstance<ElevatedBinaryResult.TimedOut>()
            assertEquals("exactly one admitted call, got $results", 1, timedOut.size)
            val denied = results.filterIsInstance<ElevatedBinaryResult.Denied>()
            assertEquals("rest must be admission rejections, got $results", n - 1, denied.size)
            assertTrue(denied.all { it.reason == ShellDeny.QUOTA_EXCEEDED })
            assertTrue(
                "denied msgs=${denied.map { it.message }}",
                denied.all { it.message.contains("backlog full") },
            )
            assertEquals("no rejection may spawn", 0, spawns.get())
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            // 明示上界：存活 elevated-supervisor 恰 1（取席者門 worker），
            // outstanding 恰 1（同—殘留），breaker 恰 1（不熔斷）。
            assertEquals(
                "alive elevated-supervisor must stay bounded at 1",
                1,
                countLive("elevated-supervisor"),
            )
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

    // ---- P2-1：首 false、後 blocked（gate 慢路徑，Root／Shizuku） ----

    private fun doFirstFalseThenBlockedGate(
        makeRunner: (
            isCancelled: () -> Boolean,
            spawns: AtomicInteger,
            bridgeEntered: CountDownLatch,
            bridgeRelease: CountDownLatch,
        ) -> BinaryElevatedRunner,
    ) {
        val hookLatch = CountDownLatch(1)
        val bridgeEntered = CountDownLatch(1)
        val bridgeRelease = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val spawns = AtomicInteger(0)
        // 首求值 false（入口門放行），之後永久阻塞：bridge 慢路徑 watcher 第二次
        // 求值即卡住（吞 interrupt），caller 須經 deadline 有界返回，不跟著卡死。
        val runner = makeRunner(
            {
                if (calls.incrementAndGet() == 1) {
                    false
                } else {
                    stuckCancelHook(hookLatch)
                }
            },
            spawns,
            bridgeEntered,
            bridgeRelease,
        )
        try {
            val resultRef = AtomicReference<ElevatedBinaryResult?>()
            val begin = System.currentTimeMillis()
            val bg = Thread {
                resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "PF-GATE"), 800L))
            }.also { it.isDaemon = true; it.start() }
            bg.join(15_000L)
            val elapsed = System.currentTimeMillis() - begin
            assertFalse("blocked second hook eval must not hang caller", bg.isAlive)
            assertTrue("elapsed=$elapsed must stay near timeout+graces, not hang", elapsed < 8_000L)
            val b = resultRef.get()
            assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
            b as ElevatedBinaryResult.TimedOut
            // R3-PhaseG P2-4 決策 a：background worker 殘留即置旗（`hasLinger ||
            // outstanding>0`，此處 outstanding==2），不再為 false。
            assertTrue("blocked gate expiry must flag linger, got $b", b.cleanupIncomplete)
            assertEquals("blocked gate must not spawn", 0, spawns.get())
            // hook 恰求值兩次（入口 false＋watcher 第二次阻塞）：交錯確定，不多不少。
            assertEquals("hook evals=${calls.get()}", 2, calls.get())
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            // 殘留封包恰 2：bridge block worker＋hook watcher 各一槽；breaker 恰好一次。
            assertEquals(2, ElevatedCleanupTracker.outstanding())
            assertEquals(1, ElevatedCleanupTracker.consecutiveCount())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
        } finally {
            hookLatch.countDown()
            bridgeRelease.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
    }

    @Test fun rootFirstFalseThenBlockedGateBounded() {
        doFirstFalseThenBlockedGate { isCancelled, spawns, bridgeEntered, bridgeRelease ->
            RootSuRunner(
                privateRoot = privateRoot,
                bridgeGranted = {
                    stuckGate(bridgeRelease, bridgeEntered)
                    true
                },
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

    @Test fun shizukuFirstFalseThenBlockedGateBounded() {
        doFirstFalseThenBlockedGate { isCancelled, spawns, bridgeEntered, bridgeRelease ->
            ShizukuShellRunner(
                privateRoot = privateRoot,
                bridgeGranted = {
                    stuckGate(bridgeRelease, bridgeEntered)
                    true
                },
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

    // ---- P2-1：首 false、後 blocked（drain finished tail 直測） ----

    @Test fun drainFirstFalseThenBlockedHookBounded() {
        val hookLatch = CountDownLatch(1)
        val streamLatch = CountDownLatch(1)
        val calls = AtomicInteger(0)
        try {
            val begin = System.currentTimeMillis()
            val d = ElevatedProcessIo.drain(
                ExitedStuckStreamsProcess(streamLatch),
                800L,
                { ElevatedProcessKiller.killTree(it) },
                {
                    if (calls.incrementAndGet() == 1) {
                        false
                    } else {
                        stuckCancelHook(hookLatch)
                    }
                },
            )
            val elapsed = System.currentTimeMillis() - begin
            assertTrue("elapsed=$elapsed must stay near tail budget, not hang", elapsed < 8_000L)
            // 命令已 exit（timedOut 仍 false），request 未完（tail 中止）。
            assertFalse("finished path must keep timedOut=false, got $d", d.timedOut)
            assertTrue("aborted tail must set tailIncomplete: $d", d.tailIncomplete)
            assertEquals("command exit stays 0: $d", 0, d.exitCode)
            assertTrue("aborted tail must truncate: $d", d.truncated)
            assertTrue("aborted tail must flag incomplete: $d", d.cleanupIncomplete)
            // hook 恰求值兩次（watcher 首 false＋第二次阻塞）：交錯確定。
            assertEquals("hook evals=${calls.get()}", 2, calls.get())
            // 殘留封包恰 3：雙 gobbler＋hook watcher；終審恰好一次，不熔斷。
            assertEquals(3, ElevatedCleanupTracker.outstanding())
            assertEquals(1, ElevatedCleanupTracker.consecutiveCount())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            hookLatch.countDown()
            streamLatch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    // ---- T1（G1 決策 a）：慢 gate 成功照常成功（bridge ~60ms 回 true＋安靜 hook） ----

    private fun doSlowGateSuccess(
        makeRunner: (() -> Boolean, AtomicInteger, () -> Boolean) -> BinaryElevatedRunner,
    ) {
        val spawns = AtomicInteger(0)
        val runner = makeRunner({ false }, spawns) {
            try {
                Thread.sleep(60L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            true
        }
        try {
            val resultRef = AtomicReference<ElevatedBinaryResult?>()
            val begin = System.currentTimeMillis()
            val bg = Thread {
                resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "PF-SLOW-GATE"), 5_000L))
            }.also { it.isDaemon = true; it.start() }
            bg.join(15_000L)
            val elapsed = System.currentTimeMillis() - begin
            assertFalse("slow gate success must not hang", bg.isAlive)
            // G1 回歸：60ms 慢交付＋安靜 hook 必須 Ok handoff；舊空分支 defer＋睡眠循環
            // 燒滿 5s deadline 才變 TimedOut。
            assertTrue("elapsed=$elapsed must not burn the deadline", elapsed < 4_500L)
            val r = resultRef.get()
            assertTrue("slow gate success must hand off Ok, got $r", r is ElevatedBinaryResult.Ok)
            assertEquals("slow gate must spawn once", 1, spawns.get())
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
    }

    @Test fun rootSlowGateSuccessHandsOff() {
        doSlowGateSuccess { isCancelled, spawns, bridge ->
            RootSuRunner(
                privateRoot = privateRoot,
                bridgeGranted = bridge,
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

    @Test fun shizukuSlowGateSuccessHandsOff() {
        doSlowGateSuccess { isCancelled, spawns, bridge ->
            ShizukuShellRunner(
                privateRoot = privateRoot,
                bridgeGranted = bridge,
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

    // ---- T2（G1 決策 a）：慢 spawn 成功照常成功（spawn ~50ms 交付＋安靜 hook） ----

    private fun doSlowSpawnSuccess(
        makeRunner: (() -> Boolean, AtomicInteger, AtomicReference<Process?>) -> BinaryElevatedRunner,
    ) {
        val spawns = AtomicInteger(0)
        val procRef = AtomicReference<Process?>()
        val runner = makeRunner({ false }, spawns, procRef)
        try {
            val resultRef = AtomicReference<ElevatedBinaryResult?>()
            val begin = System.currentTimeMillis()
            val bg = Thread {
                resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "PF-SLOW-SPAWN"), 5_000L))
            }.also { it.isDaemon = true; it.start() }
            bg.join(15_000L)
            val elapsed = System.currentTimeMillis() - begin
            assertFalse("slow spawn success must not hang", bg.isAlive)
            // G1 回歸：50ms 慢交付＋安靜 hook 必須成功 handoff；舊代碼進 expireWorker
            // CAS 認領＋late-daemon 殺掉剛建好的進程再變 TimedOut。
            assertTrue("elapsed=$elapsed must not burn the deadline", elapsed < 4_500L)
            val r = resultRef.get()
            assertTrue("slow spawn success must hand off Ok, got $r", r is ElevatedBinaryResult.Ok)
            assertEquals("slow spawn must build once", 1, spawns.get())
            val proc = procRef.get() as? FakeProcess
            assertTrue(
                "handed-off process must not be late-killed",
                proc != null && !proc.destroyed && !proc.forciblyDestroyed,
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
    }

    @Test fun rootSlowSpawnSuccessHandsOff() {
        doSlowSpawnSuccess { isCancelled, spawns, procRef ->
            RootSuRunner(
                privateRoot = privateRoot,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                spawn = {
                    try {
                        Thread.sleep(50L)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    spawns.incrementAndGet()
                    FakeProcess("ok".toByteArray()).also { procRef.set(it) }
                },
                suBinary = "/system/bin/su",
                isCancelled = isCancelled,
                grantCheck = GrantFixtures.allowAlways,
            )
        }
    }

    @Test fun shizukuSlowSpawnSuccessHandsOff() {
        doSlowSpawnSuccess { isCancelled, spawns, procRef ->
            ShizukuShellRunner(
                privateRoot = privateRoot,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                pingBinder = { true },
                spawn = {
                    try {
                        Thread.sleep(50L)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    spawns.incrementAndGet()
                    FakeProcess("ok".toByteArray()).also { procRef.set(it) }
                },
                status = { ShizukuStatus.Available(15) },
                isCancelled = isCancelled,
                grantCheck = GrantFixtures.allowAlways,
            )
        }
    }

    // ---- T3（G2）：spawn 慢路徑首 false、後 blocked（spawn 體卡死＋hook 第 4 次阻塞） ----

    private fun doSpawnFirstFalseThenBlocked(
        makeRunner: (() -> Boolean, AtomicInteger, AtomicReference<Process?>, CountDownLatch) -> BinaryElevatedRunner,
    ) {
        val hookLatch = CountDownLatch(1)
        val spawnRelease = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val spawns = AtomicInteger(0)
        val lateRef = AtomicReference<Process?>()
        // 兩 runner 皆三有界門（入口＋配額前／後，見 checkPreSpawnCancel 呼叫點）：
        // 前 3 次求值 false 放行，第 4 次（spawn watcher 首求值）起永久阻塞。
        val runner = makeRunner(
            {
                if (calls.incrementAndGet() <= 3) {
                    false
                } else {
                    stuckCancelHook(hookLatch)
                }
            },
            spawns,
            lateRef,
            spawnRelease,
        )
        try {
            val resultRef = AtomicReference<ElevatedBinaryResult?>()
            val begin = System.currentTimeMillis()
            val bg = Thread {
                resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "PF-SPAWN-BLOCK"), 800L))
            }.also { it.isDaemon = true; it.start() }
            bg.join(15_000L)
            val elapsed = System.currentTimeMillis() - begin
            assertFalse("blocked spawn-watcher hook must not hang caller", bg.isAlive)
            assertTrue("elapsed=$elapsed must stay near timeout+graces, not hang", elapsed < 8_000L)
            val b = resultRef.get()
            assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
            b as ElevatedBinaryResult.TimedOut
            assertTrue("stuck spawn expiry must flag, got $b", b.cleanupIncomplete)
            assertEquals("stuck spawn entered once", 1, spawns.get())
            // hook 恰求值四次（三門各一＋watcher 首求值阻塞）：交錯確定，不多不少。
            assertEquals("hook evals=${calls.get()}", 4, calls.get())
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            // 殘留封包恰 2：spawn worker＋hook watcher 各一槽（late 尚未交付，無 daemon）；
            // breaker 恰好一次，不熔斷。
            assertEquals(2, ElevatedCleanupTracker.outstanding())
            assertEquals(1, ElevatedCleanupTracker.consecutiveCount())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            // late 補償：釋放 spawn 體→worker 見 expired 內聯補償殺進程。
            spawnRelease.countDown()
            val late = pollProcess(lateRef, "late spawn")
            val killDeadline = System.currentTimeMillis() + 15_000L
            while (true) {
                val p = late as FakeProcess
                if (p.destroyed || p.forciblyDestroyed) break
                if (System.currentTimeMillis() >= killDeadline) {
                    throw AssertionError("late compensation must kill the process")
                }
                Thread.sleep(20L)
            }
        } finally {
            hookLatch.countDown()
            spawnRelease.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
    }

    private fun pollProcess(ref: AtomicReference<Process?>, what: String, boundMs: Long = 10_000L): Process {
        val deadline = System.currentTimeMillis() + boundMs
        while (true) {
            val v = ref.get()
            if (v != null) return v
            if (System.currentTimeMillis() >= deadline) throw AssertionError("$what was never delivered")
            Thread.sleep(20L)
        }
    }

    @Test fun rootSpawnFirstFalseThenBlockedBounded() {
        doSpawnFirstFalseThenBlocked { isCancelled, spawns, lateRef, spawnRelease ->
            RootSuRunner(
                privateRoot = privateRoot,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                spawn = {
                    spawns.incrementAndGet()
                    stuckGate(spawnRelease)
                    FakeProcess("late".toByteArray()).also { lateRef.set(it) }
                },
                suBinary = "/system/bin/su",
                isCancelled = isCancelled,
                grantCheck = GrantFixtures.allowAlways,
            )
        }
    }

    @Test fun shizukuSpawnFirstFalseThenBlockedBounded() {
        doSpawnFirstFalseThenBlocked { isCancelled, spawns, lateRef, spawnRelease ->
            ShizukuShellRunner(
                privateRoot = privateRoot,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                pingBinder = { true },
                spawn = {
                    spawns.incrementAndGet()
                    stuckGate(spawnRelease)
                    FakeProcess("late".toByteArray()).also { lateRef.set(it) }
                },
                status = { ShizukuStatus.Available(15) },
                isCancelled = isCancelled,
                grantCheck = GrantFixtures.allowAlways,
            )
        }
    }

    // ---- T4（G3）：verdict 優先級（飽和＋已取消→Denied 先於 TimedOut） ----

    @Test fun saturatedPlusCancelledStillDenied() {
        val laneLatch = CountDownLatch(1)
        val lane = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = {
                stuckGate(laneLatch)
                true
            },
            activationCheck = GrantFixtures.activationOn,
            spawn = { FakeProcess("ok".toByteArray()) },
            suBinary = "/system/bin/su",
            isCancelled = { false },
            grantCheck = GrantFixtures.allowAlways,
        )
        val laneRef = AtomicReference<ElevatedBinaryResult?>()
        val laneBg = Thread {
            laneRef.set(lane.runBinary(ElevatedRequest(listOf("id"), "PF-LANE"), 10_000L))
        }.also { it.isDaemon = true; it.start() }
        try {
            // 佔住唯一席位（串行契約 active<=1）：後續呼叫在准入處即拒。
            val seatDeadline = System.currentTimeMillis() + 10_000L
            while (ElevatedCleanupTracker.activeCalls() != 1) {
                if (System.currentTimeMillis() >= seatDeadline) {
                    throw AssertionError("lane call never took the seat")
                }
                Thread.sleep(20L)
            }
            val hookCalls = AtomicInteger(0)
            val spawns = AtomicInteger(0)
            val rejected = RootSuRunner(
                privateRoot = privateRoot,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                spawn = {
                    spawns.incrementAndGet()
                    FakeProcess("ok".toByteArray())
                },
                suBinary = "/system/bin/su",
                isCancelled = {
                    hookCalls.incrementAndGet()
                    true
                },
                grantCheck = GrantFixtures.allowAlways,
            )
            val r = rejected.runBinary(ElevatedRequest(listOf("id"), "PF-PRIORITY"), 2_000L)
            // 准入拒絕先於取消判定：已取消 hook 仍回 Denied（QUOTA_EXCEEDED 系），
            // 且 hook 零求值（pre-admission 零 worker）。
            assertTrue("saturated+cancelled must stay Denied, got $r", r is ElevatedBinaryResult.Denied)
            r as ElevatedBinaryResult.Denied
            assertEquals(ShellDeny.QUOTA_EXCEEDED, r.reason)
            assertEquals("admission rejection must not evaluate the hook", 0, hookCalls.get())
            assertEquals("admission rejection must not spawn", 0, spawns.get())
        } finally {
            laneLatch.countDown()
            laneBg.join(15_000L)
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
    }

    // ---- T5（G5）：准入拒絕不耗 quota（許可數不變＋零 spawn） ----

    @Test fun admissionRejectionConsumesNoQuota() {
        val laneLatch = CountDownLatch(1)
        val lane = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = {
                stuckGate(laneLatch)
                true
            },
            activationCheck = GrantFixtures.activationOn,
            spawn = { FakeProcess("ok".toByteArray()) },
            suBinary = "/system/bin/su",
            isCancelled = { false },
            grantCheck = GrantFixtures.allowAlways,
        )
        val laneBg = Thread {
            lane.runBinary(ElevatedRequest(listOf("id"), "PF-LANE"), 10_000L)
        }.also { it.isDaemon = true; it.start() }
        try {
            val seatDeadline = System.currentTimeMillis() + 10_000L
            while (ElevatedCleanupTracker.activeCalls() != 1) {
                if (System.currentTimeMillis() >= seatDeadline) {
                    throw AssertionError("lane call never took the seat")
                }
                Thread.sleep(20L)
            }
            val tightQuota = ShellQuota(maxCalls = 1, windowMs = 60_000L)
            val spawns = AtomicInteger(0)
            val rejected = RootSuRunner(
                privateRoot = privateRoot,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                quota = tightQuota,
                spawn = {
                    spawns.incrementAndGet()
                    FakeProcess("ok".toByteArray())
                },
                suBinary = "/system/bin/su",
                isCancelled = { false },
                grantCheck = GrantFixtures.allowAlways,
            )
            val r = rejected.runBinary(ElevatedRequest(listOf("id"), "PF-QUOTA"), 2_000L)
            assertTrue("saturated call must be Denied, got $r", r is ElevatedBinaryResult.Denied)
            assertEquals("admission rejection must not spawn", 0, spawns.get())
            // 許可數不變：同一 tight quota 仍可足額放行一次（若拒絕已耗配額，此處即 false）。
            assertTrue("admission rejection must not consume quota", tightQuota.tryAcquire())
            tightQuota.reset()
        } finally {
            laneLatch.countDown()
            laneBg.join(15_000L)
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
    }

    // ---- T6（G4）：watcher 抛語義分歧（gates 取消收斂 vs drain 存活＋計數） ----

    private fun doGateThrowConvergesToCancel(
        makeRunner: (() -> Boolean, AtomicInteger, CountDownLatch) -> BinaryElevatedRunner,
    ) {
        val bridgeRelease = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val spawns = AtomicInteger(0)
        val swallowedBefore = ElevatedProcessIo.swallowedIsCancelledThrows.get()
        // 首求值 false（入口門放行），之後恒抛：gates watcher 抛即取消（fail-closed）。
        val runner = makeRunner(
            {
                if (calls.incrementAndGet() == 1) {
                    false
                } else {
                    throw IllegalStateException("simulated hook drift T6-MARKER")
                }
            },
            spawns,
            bridgeRelease,
        )
        try {
            val resultRef = AtomicReference<ElevatedBinaryResult?>()
            val begin = System.currentTimeMillis()
            val bg = Thread {
                resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "PF-GATE-THROW"), 800L))
            }.also { it.isDaemon = true; it.start() }
            bg.join(15_000L)
            val elapsed = System.currentTimeMillis() - begin
            assertFalse("throwing hook must not hang caller", bg.isAlive)
            assertTrue("elapsed=$elapsed must stay bounded, not hang", elapsed < 8_000L)
            val b = resultRef.get()
            assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
            b as ElevatedBinaryResult.TimedOut
            // R3-PhaseG P2-4 決策 a：bridge block worker 殘留（outstanding==1）
            // 即置旗，不再為 false。
            assertTrue("cancelled gate expiry must flag linger, got $b", b.cleanupIncomplete)
            assertEquals("cancelled gate must not spawn", 0, spawns.get())
            assertTrue(
                "throwing hook must be counted",
                ElevatedProcessIo.swallowedIsCancelledThrows.get() > swallowedBefore,
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            // 殘留封包恰 1：bridge block worker（watcher 見抛即閂定退出，乾淨無槽）；
            // breaker 恰好一次，不熔斷。
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            assertEquals(1, ElevatedCleanupTracker.consecutiveCount())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
        } finally {
            bridgeRelease.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
    }

    @Test fun rootGateThrowConvergesToCancel() {
        doGateThrowConvergesToCancel { isCancelled, spawns, bridgeRelease ->
            RootSuRunner(
                privateRoot = privateRoot,
                bridgeGranted = {
                    stuckGate(bridgeRelease)
                    true
                },
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

    @Test fun shizukuGateThrowConvergesToCancel() {
        doGateThrowConvergesToCancel { isCancelled, spawns, bridgeRelease ->
            ShizukuShellRunner(
                privateRoot = privateRoot,
                bridgeGranted = {
                    stuckGate(bridgeRelease)
                    true
                },
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

    @Test fun drainThrowSurvivesWithCount() {
        val streamLatch = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val swallowedBefore = ElevatedProcessIo.swallowedIsCancelledThrows.get()
        try {
            val begin = System.currentTimeMillis()
            val d = ElevatedProcessIo.drain(
                ExitedStuckStreamsProcess(streamLatch),
                800L,
                { ElevatedProcessKiller.killTree(it) },
                {
                    if (calls.incrementAndGet() == 1) {
                        false
                    } else {
                        throw IllegalStateException("simulated hook drift T6-MARKER")
                    }
                },
            )
            val elapsed = System.currentTimeMillis() - begin
            assertTrue("elapsed=$elapsed must stay near tail budget, not hang", elapsed < 8_000L)
            // drain 側抛折為未取消：不誤殺，照常走 finished-tail 中止語義。
            assertFalse("finished path must keep timedOut=false, got $d", d.timedOut)
            assertTrue("aborted tail must set tailIncomplete: $d", d.tailIncomplete)
            assertEquals("command exit stays 0: $d", 0, d.exitCode)
            assertTrue("aborted tail must truncate: $d", d.truncated)
            assertTrue("aborted tail must flag incomplete: $d", d.cleanupIncomplete)
            assertTrue(
                "throwing hook must be counted",
                ElevatedProcessIo.swallowedIsCancelledThrows.get() > swallowedBefore,
            )
            assertTrue("hook must be evaluated more than once, got ${calls.get()}", calls.get() >= 2)
            // 殘留封包恰 2：雙 gobbler（watcher 見抛繼續觀察，乾淨退出不佔槽）；
            // 終審恰好一次，不熔斷。
            assertEquals(2, ElevatedCleanupTracker.outstanding())
            assertEquals(1, ElevatedCleanupTracker.consecutiveCount())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            streamLatch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }
}
