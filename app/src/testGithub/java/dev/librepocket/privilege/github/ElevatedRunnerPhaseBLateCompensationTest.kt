package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ShellDeny
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
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
 * Round-2 Phase B late-compensation 缺口迴歸（P1-2，PR #140）：
 * - caller CAS 認領已發佈 Process 後 `elevated-late-spawn` daemon 受全域槽管控
 *  （補償前先 acquireSlot，stall 即持槽擋 admission，不再有 untracked 永久卡住）；
 * - worker 內聯快速補償（destroy no-op＋waitFor=false）必經 parent／reap 驗證，
 *   未驗證即 `cleanupIncomplete=true`＋breaker 計數，不恢復 admission；
 * - 飽和無槽即不起無界 daemon，經旗標＋breaker 終審（fail-closed，不丟 owner）；
 * - 飽和無槽／起線程失敗一律 quarantine-only（Round-3 Phase E 契約）：零 kill 嘗試
 *   （killer／destroy／destroyForcibly 呼叫 0），handle 登記 quarantine 阻擋准入，
 *   回收經 sweep 路徑（detached best-effort kill 已刪除）。
 *
 * Root＋Shizuku 雙軌，全 fake，零真機、零 su、零 binder。
 * 排序一律 latch／barrier（polling 為 barrier，不靠 sleep 時序）：
 * caller 認領分支以 `isCancelled` 阻塞門強制序列化（spawn 先發佈、caller 後過期），
 * worker 分支以 release latch 觸發逾期交付，飽和分支以結果 barrier 後再預填。
 * 阻塞體一律吞 interrupt 續等（模擬 Binder 不可靠中斷）。
 */
class ElevatedRunnerPhaseBLateCompensationTest {

    private val privateRoot = "/data/data/files"

    private var hermeticExec: HermeticExecFixture? = null
    private var floorBefore: Int = 0

    @Before fun setUpIsolation() {
        hermeticExec = HermeticExecFixture("phaseb-hermetic", "id")
        assertFalse(
            "#136-B3 gate must stay closed",
            PrivilegeActivation.ELEVATED_RUNNERS_READY,
        )
        floorBefore = ElevatedCleanupTracker.releaseFloorHits()
        ElevatedProcessIo.quarantineRegistrar = { ElevatedCleanupTracker.quarantineUnverified(it) }
        ElevatedCleanupTracker.forceAcquireSlotOrQuarantineThrow = false
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.clearRegistrationFailedStickyForTest()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        ElevatedCleanupTracker.resetPeak()
    }

    @After fun tearDownIsolation() {
        hermeticExec?.close()
        hermeticExec = null
        val leakedOutstanding = ElevatedCleanupTracker.outstanding()
        val leakedActive = ElevatedCleanupTracker.activeCalls()
        val leakedQuarantine = ElevatedCleanupTracker.quarantinedCount()
        Thread.interrupted()
        // fail-closed：不盲發 releaseSlot/leaveCall；洩漏即輪詢逾時丟 AssertionError。
        pollOutstandingTo(0)
        pollActiveTo(0)
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        awaitNoElevatedThreads()
        assertEquals("test leaked outstanding slots", 0, leakedOutstanding)
        assertEquals("test leaked active calls", 0, leakedActive)
        assertEquals("test leaked quarantined processes", 0, leakedQuarantine)
        assertEquals("release floor must not be hit during this test", floorBefore, ElevatedCleanupTracker.releaseFloorHits())
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

    private fun <T> pollRefNonNull(ref: AtomicReference<T>, what: String, boundMs: Long = 10_000L): T {
        val deadline = System.currentTimeMillis() + boundMs
        while (true) {
            val v = ref.get()
            if (v != null) return v
            if (System.currentTimeMillis() >= deadline) throw AssertionError("$what was never delivered")
            Thread.sleep(20L)
        }
    }

    private fun pollTrue(what: String, boundMs: Long = 10_000L, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + boundMs
        while (!cond()) {
            if (System.currentTimeMillis() >= deadline) throw AssertionError("$what did not become true")
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
            "elevated-late-spawn",
            "elevated-late-kill",
            "elevated-late-close",
            "elevated-quarantine-sweep",
            "elevated-quarantine-verify",
            "elevated-wait",
            "elevated-verify",
        )
        val deadline = System.currentTimeMillis() + boundMs
        while (true) {
            val alive = Thread.getAllStackTraces().keys.filter { it.name in names && it.isAlive }
            if (alive.isEmpty()) return
            if (System.currentTimeMillis() >= deadline) throw AssertionError("orphan elevated threads: $alive")
            Thread.sleep(50L)
        }
    }

    private fun countLive(name: String): Int =
        Thread.getAllStackTraces().keys.count { it.name == name && it.isAlive }

    private fun stuckAwait(latch: CountDownLatch) {
        while (true) {
            try {
                latch.await()
                break
            } catch (_: InterruptedException) {
                // 吞 interrupt 續等（不可靠中斷）。
            }
        }
    }

    /** destroy no-op＋waitFor 恒 false：killer 調了等於沒調，驗證必失敗。 */
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
     * destroy／destroyForcibly 皆記數＋阻塞於 [blockLatch]（吞 interrupt 續等，
     * 模擬 Binder 不可靠中斷 stall；teardown 放行才返回）：飽和／起線程失敗
     * 路徑不得呼叫本 Process 的任何終止方法（零 kill 嘗試 tripwire）。
     */
    private class LatchBlockedDestroyProcess(
        private val blockLatch: CountDownLatch,
    ) : Process() {
        val destroyCalls = AtomicInteger(0)
        val destroyForciblyCalls = AtomicInteger(0)
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = false
        override fun exitValue(): Int = 0
        override fun destroy() {
            destroyCalls.incrementAndGet()
        }
        override fun destroyForcibly(): Process {
            destroyForciblyCalls.incrementAndGet()
            while (true) {
                try {
                    blockLatch.await()
                    break
                } catch (_: InterruptedException) {
                    // 吞 interrupt 續等（Binder stall 不可靠中斷）。
                }
            }
            return this
        }
    }

    /**
     * Round-7 Phase-5A：可驗證死亡＋阻塞假件（對齊 ElevatedRunnerP1B2Test.KillableBlockedDestroyProcess）：
     * `destroy()` 置 `destroyed=true` 後才永久阻塞於 [destroyLatch]（吞 interrupt）；
     * `waitFor(timeout, unit)` 回 `destroyed`——destroy 信號後即可驗證死亡，
     * compensation 路徑 verify 段走 verified==true 臂（releaseCleanupSlotAfterVerify
     * 單次釋槽、不登記 quarantine）。latch 由測試於 POST 階段 countDown。
     */
    private class KillableBlockedDestroyProcess(
        private val destroyLatch: CountDownLatch,
    ) : Process() {
        @Volatile
        var destroyed = false
            private set
        val destroyEntered = AtomicInteger(0)
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = destroyed
        override fun exitValue(): Int = 0
        override fun destroy() {
            destroyed = true
            destroyEntered.incrementAndGet()
            awaitLatchUninterruptibly()
        }
        override fun destroyForcibly(): Process {
            destroy()
            return this
        }

        private fun awaitLatchUninterruptibly() {
            while (true) {
                try {
                    destroyLatch.await()
                    return
                } catch (_: InterruptedException) {
                    // 吞中斷：Binder 不可中斷 stall——caller 永遠卡在 latch 上
                }
            }
        }
    }

    /**
     * 可殺瞬時模型（W2 winner-agnostic pin 用）：與 [KillableBlockedDestroyProcess]
     * 同契約但 destroy 立即返回——專供 claim-then-throw 之即刻收斂（kill／reap／
     * verify 皆瞬斷）。
     */
    private class KillableImmediateProcess : Process() {
        @Volatile
        var destroyed = false
            private set
        val destroyEntered = AtomicInteger(0)
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = destroyed
        override fun exitValue(): Int = 0
        override fun destroy() {
            destroyed = true
            destroyEntered.incrementAndGet()
        }
        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }

    private fun rootRunner(
        spawn: (List<String>) -> Process,
        killer: (Process) -> Unit = { it.destroy() },
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ) = RootSuRunner(
        privateRoot = privateRoot,
        bridgeGranted = GrantFixtures.bridgeOn,
        activationCheck = GrantFixtures.activationOn,
        spawn = spawn,
        suBinary = "/system/bin/su",
        killer = killer,
        isCancelled = isCancelled,
        grantCheck = GrantFixtures.allowAlways,
        execSearchDirs = hermeticExec!!.searchDirs,
    execAllowedRoots = hermeticExec!!.allowedRoots,
    execIsWritable = hermeticExec!!.systemOwned,
)

    private fun shizukuRunner(
        spawn: (List<String>) -> Process,
        killer: (Process) -> Unit = { it.destroy() },
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ) = ShizukuShellRunner(
        privateRoot = privateRoot,
        bridgeGranted = GrantFixtures.bridgeOn,
        activationCheck = GrantFixtures.activationOn,
        pingBinder = { true },
        spawn = spawn,
        status = { ShizukuStatus.Available(15) },
        killer = killer,
        isCancelled = isCancelled,
        grantCheck = GrantFixtures.allowAlways,
        execSearchDirs = hermeticExec!!.searchDirs,
    execAllowedRoots = hermeticExec!!.allowedRoots,
    execIsWritable = hermeticExec!!.systemOwned,
)

    // ---- 1. caller CAS 認領＋遲到補償永久吞 interrupt（Root／Shizuku） ----
    //
    // 確定性序列化（不靠 sleep 時序）：
    // - spawn 先發佈（lateRef barrier），caller 後過期（isCancelled 阻塞門）：
    //   spawn 未進入前 isCancelled 直回 false（pre-spawn 門不擋），進入後阻塞於
    //   cancelGate，worker 發佈死亡後 caller 仍卡門內，測方見 lateRef 後放行，
    //   caller 即 expireWorker 見 holder Val→CAS 認領→持槽起 daemon；
    // - killer 永久吞 interrupt（killerLatch 至 teardown 才放），daemon 持槽殘留。

    private fun doCallerClaimStuckCompensation(makeRunner: (
        spawn: (List<String>) -> Process,
        killer: (Process) -> Unit,
        isCancelled: () -> Boolean,
    ) -> BinaryElevatedRunner) {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        ElevatedCleanupTracker.resetPeak()
        val killerLatch = CountDownLatch(1)
        val cancelGate = CountDownLatch(1)
        val spawnEntered = CountDownLatch(1)
        val spawnRelease = CountDownLatch(1)
        val cancelBlockEntered = CountDownLatch(1)
        val lateRef = AtomicReference<Process?>()
        val spawns = AtomicInteger(0)
        val kills = AtomicInteger(0)
        val resultRef = AtomicReference<ElevatedBinaryResult?>()
        // spawn 進入前不擋門，進入後阻塞門（caller 發佈後才過期，強制 caller 認領）。
        // 首 call（result 仍 null）才阻塞；次 call（首結果已出）直回 false，
        // 使殘留期間新 call 走 admission 預留拒（而非提前取消），可斷零 spawn。
        val isCancelled: () -> Boolean = {
            if (spawnEntered.count > 0) {
                false
            } else if (resultRef.get() != null) {
                false
            } else {
                // 確定性 barrier：測方等本門進入後才放行 spawn，確保 publish 發生於
                // caller 阻塞期間（worker 見 expired==false 不認領，留 Val 給 caller）。
                cancelBlockEntered.countDown()
                stuckAwait(cancelGate)
                true
            }
        }
        val runner = makeRunner(
            {
                spawnEntered.countDown()
                //  worker 先阻塞（caller 必先卡門內），放行後才發佈，確保 caller 認領
                // （publish 發生於 caller 阻塞期間，worker 見 expired==false 不認領，
                //  留 Val 給 caller CAS，deterministic barrier，非 sleep 時序）。
                // 次 call（首結果已出）不阻塞，直返（admission 拒絕路徑不耗 spawn）。
                if (resultRef.get() != null) {
                    spawns.incrementAndGet()
                    FakeProcess("late".toByteArray()).also { lateRef.set(it) }
                } else {
                    stuckAwait(spawnRelease)
                    spawns.incrementAndGet()
                    FakeProcess("late".toByteArray()).also { lateRef.set(it) }
                }
            },
            {
                kills.incrementAndGet()
                stuckAwait(killerLatch)
            },
            isCancelled,
        )
        val bg = Thread {
            resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "PB-CALLER"), 10_000L))
        }.also { it.isDaemon = true; it.start() }
        try {
            // barrier：spawn 已進入（worker 阻塞中）＋caller 已卡門內（阻塞中），
            // 此時才放行 spawn，publish 必發生於 caller 阻塞期間（deterministic）。
            assertTrue("spawn must enter", spawnEntered.await(10_000L, TimeUnit.MILLISECONDS))
            assertTrue("caller must block in cancel gate", cancelBlockEntered.await(10_000L, TimeUnit.MILLISECONDS))
            // 放行 spawn→worker 發佈（expired 仍 false，留 Val 給 caller）。
            spawnRelease.countDown()
            // barrier：spawn 已發佈（worker 已公佈 Val，caller 仍卡門內未判 Ok）。
            pollRefNonNull(lateRef, "late spawn")
            // 放行取消門→caller expireWorker→CAS 認領→持槽 daemon（killer STALL）。
            cancelGate.countDown()
            val r = pollRefNonNull(resultRef, "caller result")
            assertTrue("$r", r is ElevatedBinaryResult.TimedOut)
            r as ElevatedBinaryResult.TimedOut
            assertTrue("caller-claimed late must flag, got $r", r.cleanupIncomplete)
            // killer 由 elevated-late-spawn daemon 非同步觸發；以 daemon barrier 等待其呼叫，
            // 不做無界裸讀（裸 assertEquals(1, kills.get()) 對該競態不穩定）。
            pollTrue("late compensation daemon must have invoked the killer") { kills.get() == 1 }
            assertEquals(1, spawns.get())
            // daemon 持槽殘留：outstanding/active/活 daemon 一致。
            pollTrue("late daemon must hold one slot") { ElevatedCleanupTracker.outstanding() == 1 }
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            pollTrue("elevated-late-spawn must stay alive") { countLive("elevated-late-spawn") == 1 }
            assertEquals(0, countLive("elevated-supervisor"))
            // 非確定性上界：lingering mark 與 spawn worker 退出競爭——worker 先退出則
            // mark 落既終態（不佔槽，daemon 槽 1），否則 mark 先佔（瞬態 2 槽），
            // 然後 worker 退出釋 lingering 槽（仍 1）；daemon 槽恆在、MAX 恆不超——
            // 故只釘 lower bound 1＋upper bound MAX，不猜確定性峰值。
            val peak = ElevatedCleanupTracker.peakOutstanding()
            assertTrue(
                "peak=$peak must stay within MAX=${ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS}",
                peak in 1..ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS,
            )
            // 殘留期間新 call 即預留拒（1+7>7），零新增 spawn（admission 被擋）。
            val denied = runner.runBinary(ElevatedRequest(listOf("id"), "PB-CALLER"), 300L)
            assertTrue("$denied", denied is ElevatedBinaryResult.Denied)
            denied as ElevatedBinaryResult.Denied
            assertEquals(ShellDeny.QUOTA_EXCEEDED, denied.reason)
            assertTrue("msg=${denied.message}", denied.message.contains("backlog full"))
            assertTrue("backlog-full Denied must carry cleanupIncomplete=true (Round-6 D-D4)", denied.cleanupIncomplete)
            assertEquals("saturated late must not allow new spawn", 1, spawns.get())
            assertFalse(bg.isAlive)
        } finally {
            killerLatch.countDown()
            cancelGate.countDown()
            spawnRelease.countDown()
            bg.join(10_000L)
            pollOutstandingTo(0)
            pollActiveTo(0)
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    @Test fun rootCallerClaimStuckCompensationHoldsSlotAndBlocksAdmission() {
        doCallerClaimStuckCompensation { s, k, c ->
            rootRunner(spawn = s, killer = k, isCancelled = c)
        }
    }

    @Test fun shizukuCallerClaimStuckCompensationHoldsSlotAndBlocksAdmission() {
        doCallerClaimStuckCompensation { s, k, c ->
            shizukuRunner(spawn = s, killer = k, isCancelled = c)
        }
    }

    // ---- 2. worker 內聯快速補償＋no-op kill（Root／Shizuku） ----
    //
    // spawn 阻塞→caller 先超時（lingering 1）→放行後 worker 發佈逾期值並認領，
    // killer no-op＋waitFor=false＋verify=false 立即返回（不睡 grace），必
    // `cleanupIncomplete=true`＋breaker 計 1（guard 恰好一次，不雙計），
    // outstanding 歸零後 consecutive 不歸零（不恢復 admission 語義）。

    private fun doWorkerNoOpFastIncomplete(makeRunner: (
        spawn: (List<String>) -> Process,
        killer: (Process) -> Unit,
    ) -> BinaryElevatedRunner) {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        val release = CountDownLatch(1)
        val lateRef = AtomicReference<Process?>()
        val kills = AtomicInteger(0)
        val runner = makeRunner(
            {
                stuckAwait(release)
                NoOpHangProcess().also { lateRef.set(it) }
            },
            {
                kills.incrementAndGet()
                // no-op：調了等於沒調（不置旗不抛）。
            },
        )
        try {
            val r = runner.runBinary(ElevatedRequest(listOf("id"), "PB-WORKER"), 300L)
            assertTrue("$r", r is ElevatedBinaryResult.TimedOut)
            r as ElevatedBinaryResult.TimedOut
            assertTrue("worker-late gap must flag, got $r", r.cleanupIncomplete)
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            assertEquals(0, kills.get())
            // 放行→worker 發佈＋內聯快速補償（no-op＋false，verify 即時 false）。
            release.countDown()
            pollRefNonNull(lateRef, "late no-op spawn")
            pollTrue("killer must have run inline") { kills.get() == 1 }
            pollOutstandingTo(0)
            pollActiveTo(0)
            // breaker 恰好一次（lingering＋late 雙因不雙計），不熔斷但不歸零。
            assertEquals(
                "fast unverified late must count exactly once, got ${ElevatedCleanupTracker.consecutiveCount()}",
                1,
                ElevatedCleanupTracker.consecutiveCount(),
            )
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            awaitNoElevatedThreads()
        } finally {
            release.countDown()
            pollOutstandingTo(0)
            pollActiveTo(0)
            // late 驗證失敗已登記 quarantine：此處顯式清除以隔離本測試登記
            //（tearDownIsolation 亦會清＋斷言無洩漏，故各測試須自行清場）。
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    @Test fun rootWorkerNoOpFastIncompleteCountsBreaker() {
        doWorkerNoOpFastIncomplete { s, k -> rootRunner(spawn = s, killer = k) }
    }

    @Test fun shizukuWorkerNoOpFastIncompleteCountsBreaker() {
        doWorkerNoOpFastIncomplete { s, k -> shizukuRunner(spawn = s, killer = k) }
    }

    // ---- 3. 飽和無槽即不起 daemon＋明確 incomplete（Root／Shizuku） ----
    //
    // 亦覆 t.start() 失敗語義（同為「無 daemon、明確 incomplete、不丟 owner」）：
    // caller 先超時（lingering 1）→結果 barrier 後預填至 MAX→放行後 worker
    // 發佈即無槽可佔，不起 daemon，直接置旗＋breaker（fail-closed），
    // outstanding 封頂不增，無 orphan late daemon。

    private fun doSaturatedLateExplicitIncomplete(makeRunner: (
        spawn: (List<String>) -> Process,
        killer: (Process) -> Unit,
    ) -> BinaryElevatedRunner) {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        val release = CountDownLatch(1)
        val lateRef = AtomicReference<Process?>()
        val runner = makeRunner(
            {
                stuckAwait(release)
                FakeProcess("late".toByteArray()).also { lateRef.set(it) }
            },
            { it.destroy() },
        )
        var prefilled = 0
        try {
            val r = runner.runBinary(ElevatedRequest(listOf("id"), "PB-SAT"), 300L)
            assertTrue("$r", r is ElevatedBinaryResult.TimedOut)
            assertTrue("got $r", (r as ElevatedBinaryResult.TimedOut).cleanupIncomplete)
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            // barrier 後預填至 MAX（worker 仍卡 release，lingering 1 保持）。
            val need = ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS - 1
            repeat(need) { assertTrue(ElevatedCleanupTracker.acquireSlot()) }
            prefilled = need
            assertTrue(ElevatedCleanupTracker.isSaturated())
            // 放行→worker 發佈即飽和，無槽不起 daemon，明確 incomplete。
            release.countDown()
            pollRefNonNull(lateRef, "late spawn under saturation")
            // worker 退出釋 lingering 槽，預填 6 留存（7-1），封頂不增。
            pollTrue("outstanding must settle at MAX-1 after worker exit") {
                ElevatedCleanupTracker.outstanding() == ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS - 1
            }
            pollActiveTo(0)
            assertEquals(0, countLive("elevated-late-spawn"))
            assertEquals(
                1,
                ElevatedCleanupTracker.consecutiveCount(),
            )
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            // lingering 1＋預填 MAX-1＝確定性峰值 MAX（setUp 已 resetPeak；worker 逾期交付前持槽）。
            assertEquals(
                "peak must reach exactly MAX (lingering 1 + pre-filled MAX-1)",
                ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS,
                ElevatedCleanupTracker.peakOutstanding(),
            )
        } finally {
            release.countDown()
            repeat(prefilled) { ElevatedCleanupTracker.releaseSlot() }
            pollOutstandingTo(0)
            pollActiveTo(0)
            // 飽和登記 quarantine：此處顯式清除以隔離本測試登記（同上）。
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    @Test fun rootSaturatedLateNoDaemonExplicitIncomplete() {
        doSaturatedLateExplicitIncomplete { s, k -> rootRunner(spawn = s, killer = k) }
    }

    @Test fun shizukuSaturatedLateNoDaemonExplicitIncomplete() {
        doSaturatedLateExplicitIncomplete { s, k -> shizukuRunner(spawn = s, killer = k) }
    }

    // ---- 4. 飽和／起線程失敗 = 只 quarantine（零 kill 嘗試，不變量迴歸） ----
    //
    // Round-3 Phase E（P1-1）quarantine-only 契約：飽和無槽／起線程失敗時，無槽即
    // 無法保證有界 supervision，任何 kill 嘗試（即便 detached daemon）都會成為
    // 無追蹤殘留。故 handle 一律轉入 quarantine 登記（准入阻擋＋verifier 復核），
    // 置 incomplete/unclean，零 kill／destroyForcibly 呼叫，caller 有界返回；
    // 回收經 sweep 路徑。以下三用例以 latch 阻塞 killer／destroyForcibly 作
    // tripwire：任何終止嘗試都會被計數（或使測試卡死），必然被斷言抓住。

    /**
     * (a) 逾期後交付＋全域槽飽和（MAX 預填）：worker 內聯補償飽和路徑。
     * killer／destroyForcibly 全 latch 阻塞（teardown 才放）：caller 於逾期界內
     * 返回（TimedOut cleanupIncomplete=true）。caller 逾期時 lingering 佔 1 槽＋
     * 預填 MAX-1 槽＝MAX（飽和）；worker 逾期交付後退出，其 finally 釋放 lingering
     * 槽——故放行後 outstanding 收斂在 MAX-1（預填槽留存、lingering 已釋放），
     * 並非持久飽和 MAX；handle 轉入 quarantine（quarantinedCount=1），
     * killer／destroy／destroyForcibly 呼叫 0，無 `elevated-late-kill` 線程。
     */
    @Test fun lateSpawnDeliveredAfterTimeoutUnderSaturationQuarantinesWithoutKillAttempt() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        ElevatedCleanupTracker.resetPeak()
        // PG-8：late-spawn quarantine delta——登記數須恰為 before＋1（additive-only）。
        val quarantineBefore = ElevatedCleanupTracker.quarantinedCount()
        // T6：飽和臂永不進入 start-failure 路徑 ⇒ killNeverAttempted 計數器不得動（stability 釘）。
        val killNeverBefore = ElevatedCleanupTracker.killNeverAttemptedCount()
        // 釋放 spawn 使 worker 發佈逾期 Process（latch barrier，非 sleep 時序）。
        val release = CountDownLatch(1)
        // killer／destroyForcibly 阻塞門：teardown 才放，期間任何調用皆會被記數。
        val killerLatch = CountDownLatch(1)
        val lateRef = AtomicReference<LatchBlockedDestroyProcess?>()
        val kills = AtomicInteger(0)
        val runner = rootRunner(
            {
                stuckAwait(release)
                LatchBlockedDestroyProcess(killerLatch).also { lateRef.set(it) }
            },
            {
                kills.incrementAndGet()
                stuckAwait(killerLatch)
            },
        )
        var prefilled = 0
        try {
            val start = System.currentTimeMillis()
            val r = runner.runBinary(ElevatedRequest(listOf("id"), "PB-LATE-SAT"), 300L)
            val elapsed = System.currentTimeMillis() - start
            assertTrue("$r", r is ElevatedBinaryResult.TimedOut)
            // invariant 為有界返回，非 wall-clock budget（3s 視平台排程而 flaky）。
            assertTrue(
                "caller must return within a bounded time, elapsed=$elapsed",
                elapsed < 10_000L,
            )
            r as ElevatedBinaryResult.TimedOut
            assertTrue("late gap must flag, got $r", r.cleanupIncomplete)
            // lingering 1：caller 逾期時 worker 仍卡 spawn，markLingering 佔 1 槽。
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            // barrier 後預填至 MAX（worker 仍卡 release，lingering 1 保持）。
            val need = ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS - 1
            repeat(need) { k ->
                if (!ElevatedCleanupTracker.acquireSlot()) {
                    throw AssertionError(
                        "prefill failed at $k/$need outstanding=${ElevatedCleanupTracker.outstanding()}",
                    )
                }
                prefilled = k + 1
            }
            assertTrue(ElevatedCleanupTracker.isSaturated())
            // 放行→worker 逾期交付→飽和無槽→僅 quarantine 登記（零 kill 嘗試）。
            release.countDown()
            val late = pollRefNonNull(lateRef, "late spawn under saturation")!!
            pollTrue("outstanding must settle at MAX-1 after worker exit") {
                ElevatedCleanupTracker.outstanding() ==
                    ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS - 1
            }
            pollActiveTo(0)
            // 先斷線程活性（named-thread liveness checks），再斷計數器
            // （best-effort entry counters，只作殘留證據斷言，可能落後線程退出）。
            assertEquals(0, countLive("elevated-late-spawn"))
            val lateKillThreads = Thread.getAllStackTraces().keys.filter {
                it.name == "elevated-late-kill" && it.isAlive
            }
            assertTrue(
                "no thread named elevated-late-kill may exist: $lateKillThreads",
                lateKillThreads.isEmpty(),
            )
            assertEquals(
                "saturated late handle must stay quarantined",
                1,
                ElevatedCleanupTracker.quarantinedCount(),
            )
            assertEquals(
                "PG-8: late-spawn saturation arm must add exactly one quarantine entry over the pre-test baseline",
                quarantineBefore + 1,
                ElevatedCleanupTracker.quarantinedCount(),
            )
            assertEquals("killer must never run on the saturation path", 0, kills.get())
            assertEquals("destroy() must never run on the saturation path", 0, late.destroyCalls.get())
            assertEquals(
                "destroyForcibly() must never run on the saturation path",
                0,
                late.destroyForciblyCalls.get(),
            )
            assertEquals(
                "killNeverAttemptedCount must not move on the saturation path (never reaches start-failure)",
                killNeverBefore,
                ElevatedCleanupTracker.killNeverAttemptedCount(),
            )
            assertEquals(
                "saturation untouched by caller (pre-filled slots retained)",
                ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS - 1,
                ElevatedCleanupTracker.outstanding(),
            )
            // 計數器（best-effort entry counters）：lingering 恰好計 1 次
            // （worker 內聯補償 incomplete 計數被 guard 擋住，不雙計）。
            assertEquals(1, ElevatedCleanupTracker.consecutiveCount())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            assertEquals(
                "peak must reach exactly MAX (lingering 1 + pre-filled MAX-1)",
                ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS,
                ElevatedCleanupTracker.peakOutstanding(),
            )
        } finally {
            release.countDown()
            killerLatch.countDown()
            repeat(prefilled) { ElevatedCleanupTracker.releaseSlot() }
            pollOutstandingTo(0)
            pollActiveTo(0)
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    /**
     * (b) `elevated-late-spawn` 起線程失敗（注入 starter 抛 OutOfMemoryError／
     * RuntimeException）：caller CAS 認領成功後 `startLateDaemon` 佔槽→起線程
     * 失敗→釋槽＋quarantine＋incomplete/unclean（同步有界收斂，零 kill 嘗試，
     * 不重試起線程）。確定性 barrier：spawn 發佈後 worker 立即退出（expired=false
     * 不認領）；caller 等 `countLive("elevated-supervisor")==1`（僅 watcher 卡
     * cancelGate）後才放行取消門，保證 caller 側 CAS 認領成功。
     * ARMED harness：late Process 為 destroyForcibly 阻塞於 latch 的假件（teardown 才放），注入
     * killer 亦記數後 latch 阻塞——任何終止嘗試要麼被計數、要麼卡死 detached
     * 線程，必然被零 kill 斷言抓住（detached-kill 迴歸確定性 turn red）。
     */
    @Test fun lateSpawnDaemonThreadStartFailureQuarantinesAndFlagsIncomplete() {
        val startFailures: List<() -> Throwable> = listOf(
            { OutOfMemoryError("simulated OOM on elevated-late-spawn") },
            { RuntimeException("simulated start failure on elevated-late-spawn") },
        )
        for (boom in startFailures) {
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
            pollActiveTo(0)
            ElevatedCleanupTracker.resetPeak()
            // PG-8：每次迭代一個全新 baseline——登記數須恰為 before＋1（additive-only）。
            val quarantineBefore = ElevatedCleanupTracker.quarantinedCount()
            val spawnEntered = CountDownLatch(1)
            val spawnRelease = CountDownLatch(1)
            val cancelGate = CountDownLatch(1)
            val cancelBlockEntered = CountDownLatch(1)
            // armed killer 阻塞門：teardown（所有斷言之後）才放；期間任何 kill
            // 嘗試皆被 kills 計數且卡死於 latch，必然被斷言抓住。
            val killerLatch = CountDownLatch(1)
            val lateRef = AtomicReference<LatchBlockedDestroyProcess?>()
            val resultRef = AtomicReference<ElevatedCallSupervisor.Res<Process>?>()
            val kills = AtomicInteger(0)
            // 確定性 barrier：spawn 進入前不擋門，進入後阻塞門（watcher 卡門內，
            // caller 仍輪詢），放行後 hook 回 true，caller 經 expireWorker 認領。
            val isCancelled: () -> Boolean = {
                if (spawnEntered.count > 0) {
                    false
                } else if (resultRef.get() != null) {
                    false
                } else {
                    cancelBlockEntered.countDown()
                    stuckAwait(cancelGate)
                    true
                }
            }
            val bg = Thread {
                resultRef.set(
                    ElevatedCallSupervisor.awaitSpawnWithThreadStarter(
                        deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(60),
                        isCancelled = isCancelled,
                        killer = {
                            kills.incrementAndGet()
                            stuckAwait(killerLatch)
                        },
                        startThread = { t ->
                            if (t.name == "elevated-late-spawn") throw boom()
                            t.start()
                        },
                    ) {
                        spawnEntered.countDown()
                        stuckAwait(spawnRelease)
                        LatchBlockedDestroyProcess(killerLatch).also { lateRef.set(it) }
                    },
                )
            }.also { it.isDaemon = true; it.start() }
            try {
                assertTrue("spawn must enter", spawnEntered.await(10_000L, TimeUnit.MILLISECONDS))
                assertTrue(
                    "caller watcher must block in cancel gate",
                    cancelBlockEntered.await(10_000L, TimeUnit.MILLISECONDS),
                )
                // 放行 spawn→worker 發佈（expired 仍 false，留 Val 給 caller）。
                spawnRelease.countDown()
                // barrier：lateRef 非空即已發佈；worker 退出由下條 countLive 收斂。
                pollRefNonNull(lateRef, "late spawn")
                // 等 worker 完全退出：countLive("elevated-supervisor") 從 2
                // （spawn worker＋watcher）降到 1（僅 watcher 卡 cancelGate）。
                // worker 死亡⇒holder 已含 Val＋worker 重讀 expired==false 不認領，
                // 保證 caller 側 CAS 認領成功，t.start() 失敗路徑被確定性觸發。
                pollTrue("spawn worker must exit before cancel gate opens") {
                    countLive("elevated-supervisor") == 1
                }
                // 放行取消門→watcher bit 閂→caller expireWorker→CAS 認領→
                // startLateDaemon 佔槽→起線程失敗→釋槽＋quarantine（零 kill）。
                cancelGate.countDown()
                val res = pollRefNonNull(resultRef, "caller result")
                assertTrue("expected Res.Expired, got $res", res is ElevatedCallSupervisor.Res.Expired)
                res as ElevatedCallSupervisor.Res.Expired
                assertTrue(
                    "daemon start failure must flag cleanupIncomplete, got $res",
                    res.hasLinger,
                )
                val late = pollRefNonNull(lateRef, "late spawn")!!
                // 限制：ElevatedCleanupTracker 無 identity probe，不能斷言 quarantine
                // 條目即 late handle——只斷登記數；quarantine 誤歸因他 handle 不在
                // 本用例斷言範圍。
                assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
                assertEquals(
                    "PG-8: late-spawn start-failure arm must add exactly one quarantine entry over the pre-arm baseline",
                    quarantineBefore + 1,
                    ElevatedCleanupTracker.quarantinedCount(),
                )
                assertEquals("killer must never run on the start-failure path", 0, kills.get())
                assertEquals(
                    "destroy() must never run on the start-failure path",
                    0,
                    late.destroyCalls.get(),
                )
                assertEquals(
                    "destroyForcibly() must never run on the start-failure path",
                    0,
                    late.destroyForciblyCalls.get(),
                )
                pollTrue("outstanding must release back to 0 after failed start") {
                    ElevatedCleanupTracker.outstanding() == 0
                }
                assertEquals(1, ElevatedCleanupTracker.consecutiveCount())
                assertEquals(0, countLive("elevated-late-spawn"))
                pollTrue("watcher must exit") { countLive("elevated-supervisor") == 0 }
                // 確定性峰值：本迭代 startLateDaemon 恰好佔槽＋釋槽一次（lingering 0，
                // 預填 0；resetPeak 已於迭代入口呼叫），故 peak==1（< MAX）。
                assertEquals(1, ElevatedCleanupTracker.peakOutstanding())
                val lateKillThreads = Thread.getAllStackTraces().keys.filter {
                    it.name == "elevated-late-kill" && it.isAlive
                }
                assertTrue(
                    "no thread named elevated-late-kill may exist: $lateKillThreads",
                    lateKillThreads.isEmpty(),
                )
            } finally {
                // 所有斷言完成後才放 armed killer latch（teardown 放行，daemon 若被迴歸
                // 觸發阻塞於 killer，此處解卡以免 join 懸掛）；countDown 只此一次。
                killerLatch.countDown()
                cancelGate.countDown()
                spawnRelease.countDown()
                bg.join(10_000L)
                pollOutstandingTo(0)
                pollActiveTo(0)
                ElevatedCleanupTracker.clearUnverified()
                ElevatedCleanupTracker.noteClean()
                awaitNoElevatedThreads()
            }
        }
    }

    /**
     * (b3.5) `elevated-late-spawn` 起線程「已起 OS 線程後再拋」極角（Round-6 D-A5 pin）：
     * startThread ＝`{ t -> t.start(); throw OutOfMemoryError(...) }`——OS 線程已起、daemon
     * 體與 catch 臂之間以 boundary ownership CAS（startLateDaemon 之 `daemonClaimed`）恰好
     * 一次收斂。daemon 先 CAS ⇒ daemon 體收斂（catch 臂 observe 即 benignly no-op）；
     * catch 臂先 CAS ⇒ daemon 醒後 observe false、直接退出（不重複登記／釋放／計數）。
     * 勝負不由測試決定性假設， invariant 在兩臂任一成立：
     * outstanding() 歸零、quarantinedCount()==1、releaseFloorHits()==floorBefore（無雙釋）、
     * consecutiveCount()==1（恰好一次 breaker 計數）、admission 以 QUARANTINE 拒（gate 7）。
     * harness 同 (b)（spawn→worker 退出→cancelGate 放行→expireWorker 認領）；
     * armed killer 於最終 barrier 放行（任一臂勝出皆不依賴 killer 是否被呼叫——daemon 臂
     * 會呼叫 killer 並阻塞於 latch，catch 臂則不會——故不斷言 killer 次數）。
     */
    @Test fun lateDaemonStartThreadThrowsAfterStartConvergesExactlyOnce() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        ElevatedCleanupTracker.resetPeak()
        val spawnEntered = CountDownLatch(1)
        val spawnRelease = CountDownLatch(1)
        val cancelGate = CountDownLatch(1)
        val cancelBlockEntered = CountDownLatch(1)
        val killerLatch = CountDownLatch(1)
        val lateRef = AtomicReference<LatchBlockedDestroyProcess?>()
        val resultRef = AtomicReference<ElevatedCallSupervisor.Res<Process>?>()
        val kills = AtomicInteger(0)
        val isCancelled: () -> Boolean = {
            if (spawnEntered.count > 0) {
                false
            } else if (resultRef.get() != null) {
                false
            } else {
                cancelBlockEntered.countDown()
                stuckAwait(cancelGate)
                true
            }
        }
        val bg = Thread {
            resultRef.set(
                ElevatedCallSupervisor.awaitSpawnWithThreadStarter(
                    deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(60),
                    isCancelled = isCancelled,
                    killer = {
                        kills.incrementAndGet()
                        stuckAwait(killerLatch)
                    },
                    startThread = { t ->
                        if (t.name == "elevated-late-spawn") {
                            t.start()
                            throw OutOfMemoryError("simulated OOM after start (test seam)")
                        }
                        t.start()
                    },
                ) {
                    spawnEntered.countDown()
                    stuckAwait(spawnRelease)
                    LatchBlockedDestroyProcess(killerLatch).also { lateRef.set(it) }
                },
            )
        }.also { it.isDaemon = true; it.start() }
        try {
            assertTrue("spawn must enter", spawnEntered.await(10_000L, TimeUnit.MILLISECONDS))
            assertTrue(
                "caller watcher must block in cancel gate",
                cancelBlockEntered.await(10_000L, TimeUnit.MILLISECONDS),
            )
            spawnRelease.countDown()
            pollRefNonNull(lateRef, "late spawn")
            pollTrue("spawn worker must exit before cancel gate opens") {
                countLive("elevated-supervisor") == 1
            }
            cancelGate.countDown()
            val res = pollRefNonNull(resultRef, "caller result")
            assertTrue("expected Res.Expired, got $res", res is ElevatedCallSupervisor.Res.Expired)
            // 不斷言 res.hasLinger：daemon 臂勝出時 lateIncomplete 由 blocked-on-killer
            // daemon 非同步置位（res 可能先產生），catch 臂勝出時則同步置 true——
            // 兩臂勝出皆合法，hasLinger 非本 invariant 範圍。
            // 放行 armed killer：daemon 臂勝出時 daemon 阻塞於此；catch 臂勝出時零 kill。
            killerLatch.countDown()
            // exactly-once 不變量（兩臂任一勝出皆成立）：
            pollOutstandingTo(0)
            pollTrue("late-spawn daemon must exit") { countLive("elevated-late-spawn") == 0 }
            pollTrue("watcher must exit") { countLive("elevated-supervisor") == 0 }
            assertEquals(
                "late victim must be quarantined exactly once",
                1,
                ElevatedCleanupTracker.quarantinedCount(),
            )
            assertEquals(
                "no double release (floor must not be hit)",
                floorBefore,
                ElevatedCleanupTracker.releaseFloorHits(),
            )
            assertEquals(
                "breaker must count exactly one incomplete cleanup",
                1,
                ElevatedCleanupTracker.consecutiveCount(),
            )
            assertEquals(1, ElevatedCleanupTracker.peakOutstanding())
            val admission = ElevatedCleanupTracker.tryEnterReserved()
            if (admission == ElevatedCleanupTracker.Admission.OK) {
                ElevatedCleanupTracker.leaveCall()
            }
            assertEquals(ElevatedCleanupTracker.Admission.QUARANTINE, admission)
        } finally {
            killerLatch.countDown()
            cancelGate.countDown()
            spawnRelease.countDown()
            bg.join(10_000L)
            pollOutstandingTo(0)
            pollActiveTo(0)
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    /**
     * (b3.7) Round-7 Phase-5A（P1 pin）：spawn worker「已起 OS 線程後 starter 再拋」
     * （start-then-throw 且 worker 已 claim）——catch 臂觀察到 `spawnClaimed==true`
     * （worker 贏）時的正式 expiry/ownership handoff pin：
     * `expired.set(true)`＋`countUncleanOnce()`（恰好一次）＋`markLingering(worker, state)`。
     *
     * harness 同 (b3.5) 的 start-then-throw 形狀，但注入對象是 spawn worker（`elevated-supervisor`）
     * 而非 late-spawn daemon：custom starter `{ t.start(); blockEntered.await(10s); throw OOME }`
     * —— worker 先入體（claim 先於 block()）、starter 後拋 ⇒ catch 臂 CAS 必敗 ⇒ handoff 臂。
     * spawn body 計數 [blockEntered] 後阻塞於 [destroyLatch]（victim 為
     * [KillableBlockedDestroyProcess]）：caller 於 startThread 內即拋（先於輪詢迴圈進入），
     * 故 [isCancelled] 恆 false、不參與本流程（不經 expireWorker）。
     * PRE-RELEASE：caller 已返回 `Res.Expired(hasLinger=true)`（二 runner 映射
     * `TimedOut(cleanupIncomplete=true)`）；admission 以 BACKLOG_FULL 密封（markLingering
     * 槽持有，state cell 四態 0→1）；outstanding==1；breaker 恰計一次；killCount==0；
     * floor 不動；sticky 不置；debt 不變；registrar-failure 計數不變。
     * POST（`destroyLatch.countDown()`）：worker 發佈 Val ⇒ 見 expired==true ⇒ 自路由
     * compensateInline ⇒ acquireSlotOrQuarantine 成功 ⇒ lateCompensateSpawn ⇒ kill 恰好
     * 一次（`it.destroy()` ⇒ destroyed=true ⇒ reap／verify 皆 verified==true）⇒
     * releaseCleanupSlotAfterVerify 恰好釋放補償槽；worker finally（state 1→2）釋放
     * markLingering 槽 ⇒ outstanding 歸零、admission OK、debt／floor／sticky／killNever／
     * registrar-failure 全數不變（breaker 維持 +1，verified-clean 不再計數）。
     */
    @Test fun spawnWorkerStartThenThrowAfterClaimHandsOffToInlineCompensationAndSealsAdmission() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        ElevatedCleanupTracker.resetPeak()
        val breakerBefore = ElevatedCleanupTracker.consecutiveCount()
        val debtBefore = ElevatedCleanupTracker.lingerDebtCount()
        val killNeverBefore = ElevatedCleanupTracker.killNeverAttemptedCount()
        val failuresBefore = ElevatedCleanupTracker.quarantineRegistrationFailedCount()
        val blockEntered = CountDownLatch(1)
        val observed = AtomicBoolean(false)
        val destroyLatch = CountDownLatch(1)
        val victim = KillableBlockedDestroyProcess(destroyLatch)
        val resultRef = AtomicReference<ElevatedCallSupervisor.Res<Process>?>()
        val kills = AtomicInteger(0)
        val bg = Thread {
            resultRef.set(
                ElevatedCallSupervisor.awaitSpawnWithThreadStarter(
                    deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(60),
                    // isCancelled 不參與本流程（starter 於 startThread 內即拋，caller 不入輪詢迴圈）；
                    // 恆 false 排除 expireWorker 路徑干擾。
                    isCancelled = { false },
                    killer = { p ->
                        kills.incrementAndGet()
                        p.destroy()
                    },
                    startThread = { t ->
                        if (t.name == "elevated-supervisor") {
                            t.start()
                            if (blockEntered.await(10, TimeUnit.SECONDS)) observed.set(true)
                            throw OutOfMemoryError("OOM after OS start (claim-lost fixture)")
                        }
                        t.start()
                    },
                ) {
                    // 記必須先行：worker 入體即先置 blockEntered，再阻塞於 destroyLatch——
                    // caller 返回時 killCount 必為 0（compensateInline 尚未發生）。
                    blockEntered.countDown()
                    stuckAwait(destroyLatch)
                    victim
                },
            )
        }.also { it.isDaemon = true; it.start() }
        try {
            // PRE-RELEASE：caller 於 block 阻塞期間即返回（starter 拋在 startThread 內）。
            val res = pollRefNonNull(resultRef, "caller result", 15_000L)
            assertTrue("worker must have entered block before OOM", observed.get())
            assertTrue(
                "expected Res.Expired(hasLinger=true), got $res",
                res is ElevatedCallSupervisor.Res.Expired && res.hasLinger,
            )
            // seal 在位：markLingering 持槽（state 0→1）⇒ outstanding==1 ⇒ admission 拒。
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            val admission = ElevatedCleanupTracker.tryEnterReserved()
            if (admission == ElevatedCleanupTracker.Admission.OK) {
                ElevatedCleanupTracker.leaveCall()
            }
            assertEquals(ElevatedCleanupTracker.Admission.BACKLOG_FULL, admission)
            assertEquals(0, kills.get())
            assertEquals(
                "breaker must count exactly once (handoff arm)",
                breakerBefore + 1,
                ElevatedCleanupTracker.consecutiveCount(),
            )
            assertFalse(ElevatedCleanupTracker.isRegistrationFailedSticky())
            assertEquals(debtBefore, ElevatedCleanupTracker.lingerDebtCount())
            assertEquals(floorBefore, ElevatedCleanupTracker.releaseFloorHits())
            // POST：放行 victim ⇒ worker 發佈 Val ⇒ 見 expired==true ⇒ 自路由 compensateInline。
            destroyLatch.countDown()
            pollTrue("worker must kill the victim exactly once", 10_000L) { kills.get() == 1 }
            pollTrue("spawn worker must exit after compensation", 10_000L) {
                countLive("elevated-supervisor") == 0
            }
            pollOutstandingTo(0)
            val admissionAfter = ElevatedCleanupTracker.tryEnterReserved()
            if (admissionAfter == ElevatedCleanupTracker.Admission.OK) {
                ElevatedCleanupTracker.leaveCall()
            }
            assertEquals(ElevatedCleanupTracker.Admission.OK, admissionAfter)
            assertEquals(floorBefore, ElevatedCleanupTracker.releaseFloorHits())
            assertEquals(debtBefore, ElevatedCleanupTracker.lingerDebtCount())
            assertEquals(killNeverBefore, ElevatedCleanupTracker.killNeverAttemptedCount())
            assertEquals(
                failuresBefore,
                ElevatedCleanupTracker.quarantineRegistrationFailedCount(),
            )
            assertFalse(ElevatedCleanupTracker.isRegistrationFailedSticky())
            assertEquals(
                "verified-clean compensation must not recount the breaker",
                breakerBefore + 1,
                ElevatedCleanupTracker.consecutiveCount(),
            )
        } finally {
            destroyLatch.countDown()
            bg.join(10_000L)
            pollOutstandingTo(0)
            pollActiveTo(0)
            ElevatedCleanupTracker.forceAcquireSlotOrQuarantineThrow = false
            ElevatedProcessIo.quarantineRegistrar =
                { ElevatedCleanupTracker.quarantineUnverified(it) }
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    /**
     * (Phase-5A W2 pin；security MEDIUM／code-reviewer Optional-W2 採納)Round-7 fixup：
     * starter `{ t.start(); blockEntered.await(10s); throw OOM }`（spawn worker
     * `elevated-supervisor`）＋ block 立即返回 [KillableImmediateProcess]——caller 於
     * startThread 內即拋（不入輪詢迴圈）；worker 發佈 Val 與 catch 臂 `expired.set(true)`
     * 之間為真實競態（非 latch 決定序）——winner 可為 worker（inline compensation）、
     * 本臂（publish 後搶 Val ⇒ startLateDaemon）、或 worker 發佈時見 expired==true 自
     * 路由補償；三者恰贏其一（`holder` 同一 CAS 仲裁，恰好一次）。
     * 任一 winner 皆收斂同一組斷言（winner-agnostic）：caller 返回
     * `Res.Expired(hasLinger=true)`；kills 恰好 1（不雙補、不漏補）；outstanding 有界
     * 歸零；admission 回 OK；debt 不動；breaker `before+1`（catch 臂 handoff 恰計一次，
     * 補償 verified-clean 不再計數——`countUncleanOnce` CAS 保證 worker 補償不雙計）；
     * quarantine 不增（verify==true ⇒ 無登記）；killNever／failures／sticky／floor 全不動。
     * 無 block latch（block 即刻返回）；兩種 interleaving 皆通過同一組斷言。
     */
    @Test fun spawnWorkerStartThenThrowAfterClaimCompensatesExactlyOnce() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        ElevatedCleanupTracker.resetPeak()
        val breakerBefore = ElevatedCleanupTracker.consecutiveCount()
        val debtBefore = ElevatedCleanupTracker.lingerDebtCount()
        val killNeverBefore = ElevatedCleanupTracker.killNeverAttemptedCount()
        val failuresBefore = ElevatedCleanupTracker.quarantineRegistrationFailedCount()
        val quarantineBefore = ElevatedCleanupTracker.quarantinedCount()
        val blockEntered = CountDownLatch(1)
        val observed = AtomicBoolean(false)
        val victim = KillableImmediateProcess()
        val kills = AtomicInteger(0)
        val res = ElevatedCallSupervisor.awaitSpawnWithThreadStarter(
            deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(60),
            // starter 於 startThread 內即拋 ⇒ caller 不入輪詢迴圈；恆 false 排除 expireWorker 干擾。
            isCancelled = { false },
            killer = { p ->
                kills.incrementAndGet()
                p.destroy()
            },
            startThread = { t ->
                if (t.name == "elevated-supervisor") {
                    t.start()
                    if (blockEntered.await(10, TimeUnit.SECONDS)) observed.set(true)
                    throw OutOfMemoryError("OOM after OS start (test seam)")
                }
                t.start()
            },
        ) {
            blockEntered.countDown()
            victim
        }
        try {
            assertTrue("worker must have entered block before OOM", observed.get())
            assertTrue(
                "expected Res.Expired(hasLinger=true), got $res",
                res is ElevatedCallSupervisor.Res.Expired && res.hasLinger,
            )
            pollTrue("compensator must kill the victim exactly once", 10_000L) { kills.get() == 1 }
            pollOutstandingTo(0)
            pollActiveTo(0)
            awaitNoElevatedThreads()
            assertEquals("kills must be exactly once (no double/no missed compensation)", 1, kills.get())
            assertEquals(0, ElevatedCleanupTracker.outstanding())
            val admission = ElevatedCleanupTracker.tryEnterReserved()
            if (admission == ElevatedCleanupTracker.Admission.OK) {
                ElevatedCleanupTracker.leaveCall()
            }
            assertEquals(ElevatedCleanupTracker.Admission.OK, admission)
            assertEquals(debtBefore, ElevatedCleanupTracker.lingerDebtCount())
            assertEquals(
                "breaker must count exactly one incomplete cleanup (arm handoff)",
                breakerBefore + 1,
                ElevatedCleanupTracker.consecutiveCount(),
            )
            assertEquals(
                "verified-clean compensation must not register quarantine",
                quarantineBefore,
                ElevatedCleanupTracker.quarantinedCount(),
            )
            assertEquals(killNeverBefore, ElevatedCleanupTracker.killNeverAttemptedCount())
            assertEquals(
                failuresBefore,
                ElevatedCleanupTracker.quarantineRegistrationFailedCount(),
            )
            assertFalse(ElevatedCleanupTracker.isRegistrationFailedSticky())
            assertEquals(floorBefore, ElevatedCleanupTracker.releaseFloorHits())
        } finally {
            pollOutstandingTo(0)
            pollActiveTo(0)
            ElevatedProcessIo.quarantineRegistrar = { ElevatedCleanupTracker.quarantineUnverified(it) }
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    /**
     * (R1) Round-8 pin：spawn block 於 OS 線程已起後立即拋（非 start-then-throw、default
     * starter）——spawn worker catch 臂現標 `lateIncomplete`＋`countUncleanOnce()`
     * （fail-toward：歧義 arm 視為 incomplete，不永久 seal）：
     * caller 側 Thr 消費臂（`Res.Err(h.t, watchLingering || lateIncomplete.get())`）透傳
     * `hasLinger=true` ⇒ runner 映射 `Failed(… cleanupIncomplete = preSpawnLinger(true))`。
     * 確定性（無 latch；block 即刻拋、worker 先入體，caller 見 worker 死亡⇒h==Thr⇒Err 臂）；
     * breaker 恰好 +1（worker catch 臂先 `countUncleanOnce`，caller handoff 臂經
     * `uncleanCounted` CAS 敗 ⇒ 不雙計）；sticky 不置；outstanding／debt／quarantine／
     * registrar-failure／killNever 全不動；admission 不自動密封（歧義 arm 非 permanent
     * seal——見 docs/REVIEW_TAGS.md B3 ledger），本測不斷 admission。
     */
    @Test fun spawnBlockThrowSurfacesCleanupIncompleteAndCountsBreakerOnce() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        ElevatedCleanupTracker.resetPeak()
        val outstandingBefore = ElevatedCleanupTracker.outstanding()
        val breakerBefore = ElevatedCleanupTracker.consecutiveCount()
        val debtBefore = ElevatedCleanupTracker.lingerDebtCount()
        val killNeverBefore = ElevatedCleanupTracker.killNeverAttemptedCount()
        val failuresBefore = ElevatedCleanupTracker.quarantineRegistrationFailedCount()
        val quarantineBefore = ElevatedCleanupTracker.quarantinedCount()
        val res = ElevatedCallSupervisor.awaitSpawnWithThreadStarter(
            deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(60),
            // block 即刻拋 ⇒ caller 入輪詢迴圈；恆 false 排除 expireWorker／cancel 干擾。
            isCancelled = { false },
            // block-throw 臂零補償 ⇒ killer 不會被呼叫（計數由計數器承載，無 latch）。
            killer = { p -> p.destroy() },
            startThread = { t -> t.start() },
        ) {
            throw IllegalStateException("spawn boom (test seam)")
        }
        try {
            assertTrue(
                "expected Res.Err(hasLinger=true), got $res",
                res is ElevatedCallSupervisor.Res.Err && res.hasLinger,
            )
            pollOutstandingTo(outstandingBefore)
            pollActiveTo(0)
            awaitNoElevatedThreads()
            assertEquals(
                "breaker must count exactly one incomplete cleanup (worker catch arm)",
                breakerBefore + 1,
                ElevatedCleanupTracker.consecutiveCount(),
            )
            assertFalse(ElevatedCleanupTracker.isRegistrationFailedSticky())
            assertEquals(outstandingBefore, ElevatedCleanupTracker.outstanding())
            assertEquals(debtBefore, ElevatedCleanupTracker.lingerDebtCount())
            assertEquals(
                failuresBefore,
                ElevatedCleanupTracker.quarantineRegistrationFailedCount(),
            )
            assertEquals(
                "ambiguous spawn arm must not quarantine the (possibly nonexistent) child",
                quarantineBefore,
                ElevatedCleanupTracker.quarantinedCount(),
            )
            assertEquals(killNeverBefore, ElevatedCleanupTracker.killNeverAttemptedCount())
        } finally {
            pollOutstandingTo(0)
            pollActiveTo(0)
            ElevatedProcessIo.quarantineRegistrar = { ElevatedCleanupTracker.quarantineUnverified(it) }
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    /**
     * (b3.6) startLateDaemon acquire-throw＋second-chance registrar 拋 ⇒ sticky brick 的
     * F4 pin（harness 同 (b3.5)；`forceAcquireSlotOrQuarantineThrow` 注入＋registrar 拋，
     * finally 還原兩者＋clear sticky）：caller CAS 認領成功後 `startLateDaemon` 進
     * acquire 臂且 `acquireSlotOrQuarantine` 一律拋 ⇒ 首嘗
     * `noteQuarantineRegistrationFailed`＋`noteKillNeverAttempted`；second-chance 登記
     * 亦拋（OOM 注入）⇒ 再計一次＋`noteRegistrationFailedSticky()`、`lateIncomplete=true`；
     * 本臂不佔槽、不 spawn daemon ⇒ outstanding 歸零、quarantine 登記不增（雙登記皆拋）；
     * `releaseFloorHits()` 不動。Res.Expired(hasLinger=true) 即 lateIncomplete 的等價 verdict。
     */
    @Test fun startLateDaemonAcquireThrowWithSecondChanceFailureBricksAdmission() {
        val spawnEntered = CountDownLatch(1)
        val spawnRelease = CountDownLatch(1)
        val cancelGate = CountDownLatch(1)
        val cancelBlockEntered = CountDownLatch(1)
        val killerLatch = CountDownLatch(1)
        val lateRef = AtomicReference<LatchBlockedDestroyProcess?>()
        val resultRef = AtomicReference<ElevatedCallSupervisor.Res<Process>?>()
        val kills = AtomicInteger(0)
        val quarantineBefore = ElevatedCleanupTracker.quarantinedCount()
        val failuresBefore = ElevatedCleanupTracker.quarantineRegistrationFailedCount()
        val killNeverBefore = ElevatedCleanupTracker.killNeverAttemptedCount()
        val isCancelled: () -> Boolean = {
            if (spawnEntered.count > 0) {
                false
            } else if (resultRef.get() != null) {
                false
            } else {
                cancelBlockEntered.countDown()
                stuckAwait(cancelGate)
                true
            }
        }
        ElevatedCleanupTracker.forceAcquireSlotOrQuarantineThrow = true
        ElevatedProcessIo.quarantineRegistrar = {
            throw OutOfMemoryError("simulated OOM in quarantine registrar (test seam)")
        }
        val bg = Thread {
            resultRef.set(
                ElevatedCallSupervisor.awaitSpawnWithThreadStarter(
                    deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(60),
                    isCancelled = isCancelled,
                    killer = {
                        kills.incrementAndGet()
                        stuckAwait(killerLatch)
                    },
                    startThread = { t ->
                        t.start()
                    },
                ) {
                    spawnEntered.countDown()
                    stuckAwait(spawnRelease)
                    LatchBlockedDestroyProcess(killerLatch).also { lateRef.set(it) }
                },
            )
        }.also { it.isDaemon = true; it.start() }
        try {
            assertTrue("spawn must enter", spawnEntered.await(10_000L, TimeUnit.MILLISECONDS))
            assertTrue(
                "caller watcher must block in cancel gate",
                cancelBlockEntered.await(10_000L, TimeUnit.MILLISECONDS),
            )
            spawnRelease.countDown()
            pollRefNonNull(lateRef, "late spawn")
            pollTrue("exactly one elevated-supervisor remains (watch blocked in cancel gate)") {
                countLive("elevated-supervisor") == 1
            }
            // 放行取消門→caller expireWorker→CAS 認領→startLateDaemon acquire-throw 臂（同步收斂）。
            cancelGate.countDown()
            val res = pollRefNonNull(resultRef, "caller result")
            assertTrue("expected Res.Expired, got $res", res is ElevatedCallSupervisor.Res.Expired)
            res as ElevatedCallSupervisor.Res.Expired
            // lateIncomplete 由 acquire-throw 臂於 expireWorker 內同步置位——此處 hasLinger==true
            // 與 lateProcess=true 同成立（exited worker 留 Val 供 caller 認領），兩路徑皆合法；
            // 本 pin 不區分來源（與 (b3.5) 同一立場）。
            assertTrue(
                "acquire-throw arm must flag cleanupIncomplete, got $res",
                res.hasLinger,
            )
            // acquire-throw 臂同步收斂：res 產生時 sticky 已置位。
            assertTrue(
                "double registration failure must brick admission",
                ElevatedCleanupTracker.isRegistrationFailedSticky(),
            )
            assertEquals(
                ElevatedCleanupTracker.Admission.REGISTRATION_FAILED,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            assertEquals(0, ElevatedCleanupTracker.outstanding())
            // 雙登記皆拋 ⇒ quarantine 不增。
            assertEquals(quarantineBefore, ElevatedCleanupTracker.quarantinedCount())
            // 首嘗＋second-chance，至多 2 次失敗計數（此臂無 helper 重試）。
            assertEquals(
                failuresBefore + 2,
                ElevatedCleanupTracker.quarantineRegistrationFailedCount(),
            )
            assertEquals(killNeverBefore + 1, ElevatedCleanupTracker.killNeverAttemptedCount())
            assertEquals(0, kills.get())
            assertEquals(floorBefore, ElevatedCleanupTracker.releaseFloorHits())
        } finally {
            killerLatch.countDown()
            cancelGate.countDown()
            spawnRelease.countDown()
            bg.join(10_000L)
            ElevatedCleanupTracker.forceAcquireSlotOrQuarantineThrow = false
            ElevatedProcessIo.quarantineRegistrar = { ElevatedCleanupTracker.quarantineUnverified(it) }
            ElevatedCleanupTracker.clearRegistrationFailedStickyForTest()
            pollOutstandingTo(0)
            pollActiveTo(0)
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    /**
     * (b4.6) compensateInline acquire-throw＋second-chance registrar 拋 ⇒ sticky brick 的
     * F4 pin（harness 同 (b4)；`forceAcquireSlotOrQuarantineThrow` 注入＋registrar 拋，
     * finally 還原兩者＋clear sticky）：caller 先過期（deadline 先行，interrupt 無效、
     * grace 後 lingering 記 1 槽），worker 放行後 publish→CAS 認領→`compensateInline`
     * ⇒ acquire 一律拋 ⇒ 首嘗 `noteQuarantineRegistrationFailed`＋`noteKillNeverAttempted`；
     * second-chance 登記亦拋 ⇒ 再計一次＋ sticky brick、`lateIncomplete=true`；
     * 補償不佔槽（acquire 拋）⇒ killer 不執行、lingering 槽由 worker finally 恰好釋放；
     * quarantine 不增（雙登記皆拋）、`releaseFloorHits()` 不動。Res.Expired(hasLinger=true)
     * 為 lateIncomplete 的等價 verdict（caller 端 lingering=true 先行成立，兩路徑不區分）。
     */
    @Test fun compensateInlineAcquireThrowWithSecondChanceFailureBricksAdmission() {
        fun awaitUninterruptibly(latch: CountDownLatch) {
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // 吞中斷模擬不可中斷阻塞。
                }
            }
        }
        val before = ElevatedCleanupTracker.quarantineRegistrationFailedCount()
        val quarantineBefore = ElevatedCleanupTracker.quarantinedCount()
        val killNeverBefore = ElevatedCleanupTracker.killNeverAttemptedCount()
        val spawnEntered = CountDownLatch(1)
        val spawnRelease = CountDownLatch(1)
        val kills = AtomicInteger(0)
        ElevatedCleanupTracker.forceAcquireSlotOrQuarantineThrow = true
        ElevatedProcessIo.quarantineRegistrar = {
            throw OutOfMemoryError("simulated OOM in quarantine registrar (test seam)")
        }
        val res = ElevatedCallSupervisor.awaitSpawn(
            deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100L),
            isCancelled = { false },
            killer = {
                kills.incrementAndGet()
                throw OutOfMemoryError("inline killer oom")
            },
        ) {
            spawnEntered.countDown()
            awaitUninterruptibly(spawnRelease)
            LatchBlockedDestroyProcess(CountDownLatch(0))
        }
        try {
            assertTrue(spawnEntered.await(10, TimeUnit.SECONDS))
            assertTrue("expected Expired, got $res", res is ElevatedCallSupervisor.Res.Expired)
            // caller 過期返回當下：lingering 槽已記（deadline 先行逾期）。
            spawnRelease.countDown()
            // barrier：worker publish→CAS 認領→compensateInline（acquire 一律拋）→收斂。
            pollTrue("inline compensation must brick admission after double registration failure") {
                ElevatedCleanupTracker.isRegistrationFailedSticky()
            }
            assertEquals(
                ElevatedCleanupTracker.Admission.REGISTRATION_FAILED,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            // lingering 槽由 worker finally 恰好釋放（補償不佔槽，acquire 拋）。
            pollOutstandingTo(0)
            // acquire 拋於 killer 之前 ⇒ killer 不執行、quarantine 不增（雙登記皆拋）。
            assertEquals(0, kills.get())
            assertEquals(quarantineBefore, ElevatedCleanupTracker.quarantinedCount())
            // 首嘗＋second-chance，至多 2 次失敗計數（此臂無 helper 重試）。
            assertEquals(before + 2, ElevatedCleanupTracker.quarantineRegistrationFailedCount())
            assertEquals(killNeverBefore + 1, ElevatedCleanupTracker.killNeverAttemptedCount())
            assertEquals(floorBefore, ElevatedCleanupTracker.releaseFloorHits())
        } finally {
            spawnRelease.countDown()
            ElevatedCleanupTracker.forceAcquireSlotOrQuarantineThrow = false
            ElevatedProcessIo.quarantineRegistrar = { ElevatedCleanupTracker.quarantineUnverified(it) }
            ElevatedCleanupTracker.clearRegistrationFailedStickyForTest()
            pollOutstandingTo(0)
            pollActiveTo(0)
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    /**
     * (b2) `elevated-late-spawn` 起線程失敗＋registrar 拋（Stage 2 pin；harness 同 (b)）：
     * caller CAS 認領成功後 `startLateDaemon` 佔槽→起線程失敗→start-failure 臂收斂至
     * `releaseCleanupSlotAfterVerify(v, verified=false, slotHeld=true)`；registrar 拋 ⇒
     * 登記不落地：`quarantineRegistrationFailedCount` +1、槽保留（outstanding==1）、
     * quarantine 空——fail-closed。確定性 barrier 結構與 (b) 完全相同（spawn→worker
     * 退出→cancelGate 放行→expireWorker 認領），registrar 於 try 前注入、於 finally 還原（@Before 亦統一還原預設）。
     * ARMED harness：late Process 為 destroyForcibly 阻塞於 latch 的假件（teardown 才放），注入 killer
     * 亦記數後 latch 阻塞。
     */
    @Test fun lateSpawnDaemonThreadStartFailureWithThrowingRegistrarKeepsSlotFailClosed() {
        val before = ElevatedCleanupTracker.quarantineRegistrationFailedCount()
        val spawnEntered = CountDownLatch(1)
        val spawnRelease = CountDownLatch(1)
        val cancelGate = CountDownLatch(1)
        val cancelBlockEntered = CountDownLatch(1)
        val killerLatch = CountDownLatch(1)
        val lateRef = AtomicReference<LatchBlockedDestroyProcess?>()
        val resultRef = AtomicReference<ElevatedCallSupervisor.Res<Process>?>()
        val kills = AtomicInteger(0)
        val isCancelled: () -> Boolean = {
            if (spawnEntered.count > 0) {
                false
            } else if (resultRef.get() != null) {
                false
            } else {
                cancelBlockEntered.countDown()
                stuckAwait(cancelGate)
                true
            }
        }
        ElevatedProcessIo.quarantineRegistrar = { throw RuntimeException("registrar boom") }
        val bg = Thread {
            resultRef.set(
                ElevatedCallSupervisor.awaitSpawnWithThreadStarter(
                    deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(60),
                    isCancelled = isCancelled,
                    killer = {
                        kills.incrementAndGet()
                        stuckAwait(killerLatch)
                    },
                    startThread = { t ->
                        if (t.name == "elevated-late-spawn") {
                            throw RuntimeException("simulated start failure on elevated-late-spawn")
                        }
                        t.start()
                    },
                ) {
                    spawnEntered.countDown()
                    stuckAwait(spawnRelease)
                    LatchBlockedDestroyProcess(killerLatch).also { lateRef.set(it) }
                },
            )
        }.also { it.isDaemon = true; it.start() }
        try {
            assertTrue("spawn must enter", spawnEntered.await(10_000L, TimeUnit.MILLISECONDS))
            assertTrue(
                "caller watcher must block in cancel gate",
                cancelBlockEntered.await(10_000L, TimeUnit.MILLISECONDS),
            )
            spawnRelease.countDown()
            pollRefNonNull(lateRef, "late spawn")
            pollTrue("spawn worker must exit before cancel gate opens") {
                countLive("elevated-supervisor") == 1
            }
            cancelGate.countDown()
            val res = pollRefNonNull(resultRef, "caller result")
            assertTrue("expected Res.Expired, got $res", res is ElevatedCallSupervisor.Res.Expired)
            res as ElevatedCallSupervisor.Res.Expired
            assertTrue(
                "daemon start failure must flag cleanupIncomplete, got $res",
                res.hasLinger,
            )
            val late = pollRefNonNull(lateRef, "late spawn")!!
            // registrar 拋 ⇒ 登記未落地：計數恰好 +1（start-failure 臂單站點）、槽保留、
            // quarantine 空（登記從未成功）。
            assertEquals(before + 1, ElevatedCleanupTracker.quarantineRegistrationFailedCount())
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
            assertEquals("killer must never run on the start-failure path", 0, kills.get())
            assertEquals(0, late.destroyCalls.get())
            assertEquals(0, late.destroyForciblyCalls.get())
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
            // 恰好一次：floor 零命中。
            assertEquals(floorBefore, ElevatedCleanupTracker.releaseFloorHits())
            // 還原 registrar 後重試 helper（有意保留的 1 槽）：登記成功 ⇒ 釋放恰好一次。
            ElevatedProcessIo.quarantineRegistrar = { ElevatedCleanupTracker.quarantineUnverified(it) }
            ElevatedProcessIo.releaseCleanupSlotAfterVerify(late, verified = false, slotHeld = true)
            assertEquals(0, ElevatedCleanupTracker.outstanding())
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            assertEquals(before + 1, ElevatedCleanupTracker.quarantineRegistrationFailedCount())
            assertEquals(floorBefore, ElevatedCleanupTracker.releaseFloorHits())
        } finally {
            // 還原預設 registrar；所有斷言完成後才放 armed killer latch。
            ElevatedProcessIo.quarantineRegistrar = { ElevatedCleanupTracker.quarantineUnverified(it) }
            killerLatch.countDown()
            cancelGate.countDown()
            spawnRelease.countDown()
            bg.join(10_000L)
            // 主體未走到回收重試（斷言先失敗）時的兜底：有意保留的 1 槽在此收斂，
            // 避免 tearDown 輪詢誤判；helper 為 registered→release，恰好一次。
            val late = lateRef.get()
            if (late != null && ElevatedCleanupTracker.outstanding() == 1) {
                ElevatedProcessIo.releaseCleanupSlotAfterVerify(late, verified = false, slotHeld = true)
            }
            pollOutstandingTo(0)
            pollActiveTo(0)
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    /**
     * (b3) `elevated-late-spawn` daemon 本體＋registrar 拋（Stage 4 pin；harness 同 (b)）：
     * caller CAS 認領成功後 daemon 起動（正常起線程）→ lateCompensateSpawn：killer 抛
     * OutOfMemoryError → waitFor=false（never verified）→ verify=false → 三流 best-effort 關
     * （C1 有界監督 `elevated-late-close` worker，不翻 verdict）→ 內層 guarded 登記直呼
     * [ElevatedCleanupTracker.quarantineUnverified]（**不經** registrar seam）→ handle 落 quarantine；
     * 外層 helper [ElevatedProcessIo.releaseCleanupSlotAfterVerify] 經 registrar 登記**拋** ⇒
     * `quarantineRegistrationFailedCount` +1、槽**保留**（outstanding==1、fail-closed）。
     * `before`/登記數／admission/floor 斷言對齊 (b2) 紀律；teardown 統一還原 registrar 並
     * 清 quarantine（有意保留的 1 槽在主體內收斂；fallback 於 finally）。
     */
    @Test fun lateDaemonBodyWithThrowingRegistrarKeepsSlotFailClosed() {
        val before = ElevatedCleanupTracker.quarantineRegistrationFailedCount()
        // PG-8：late-spawn quarantine delta——daemon 本體臂登記數須恰為 before＋1（additive-only）。
        val quarantineBefore = ElevatedCleanupTracker.quarantinedCount()
        val spawnEntered = CountDownLatch(1)
        val spawnRelease = CountDownLatch(1)
        val cancelGate = CountDownLatch(1)
        val cancelBlockEntered = CountDownLatch(1)
        val lateRef = AtomicReference<LatchBlockedDestroyProcess?>()
        val resultRef = AtomicReference<ElevatedCallSupervisor.Res<Process>?>()
        val kills = AtomicInteger(0)
        val isCancelled: () -> Boolean = {
            if (spawnEntered.count > 0) {
                false
            } else if (resultRef.get() != null) {
                false
            } else {
                cancelBlockEntered.countDown()
                stuckAwait(cancelGate)
                true
            }
        }
        ElevatedProcessIo.quarantineRegistrar = { throw RuntimeException("registrar boom") }
        val bg = Thread {
            resultRef.set(
                ElevatedCallSupervisor.awaitSpawnWithThreadStarter(
                    deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(60),
                    isCancelled = isCancelled,
                    killer = {
                        kills.incrementAndGet()
                        throw OutOfMemoryError("daemon killer oom")
                    },
                    // 預設 startThread：elevated-late-spawn 正常起線程，走 daemon 本體臂。
                ) {
                    spawnEntered.countDown()
                    stuckAwait(spawnRelease)
                    LatchBlockedDestroyProcess(CountDownLatch(0)).also { lateRef.set(it) }
                },
            )
        }.also { it.isDaemon = true; it.start() }
        try {
            assertTrue("spawn must enter", spawnEntered.await(10_000L, TimeUnit.MILLISECONDS))
            assertTrue(
                "caller watcher must block in cancel gate",
                cancelBlockEntered.await(10_000L, TimeUnit.MILLISECONDS),
            )
            spawnRelease.countDown()
            pollRefNonNull(lateRef, "late spawn")
            // worker publish（expired==false）後退出：caller CAS 認領後起 daemon。
            pollTrue("spawn worker must exit before cancel gate opens") {
                countLive("elevated-supervisor") == 1
            }
            cancelGate.countDown()
            val res = pollRefNonNull(resultRef, "caller result")
            assertTrue("expected Res.Expired, got $res", res is ElevatedCallSupervisor.Res.Expired)
            res as ElevatedCallSupervisor.Res.Expired
            assertTrue(
                "late daemon compensation failure must flag cleanupIncomplete, got $res",
                res.hasLinger,
            )
            val late = pollRefNonNull(lateRef, "late spawn")!!
            // barrier：daemon 本體完成 lateCompensateSpawn＋helper 收斂（註冊拋出→計數）。
            pollTrue("daemon body must converge fail-closed and keep the slot") {
                ElevatedCleanupTracker.quarantineRegistrationFailedCount() == before + 1
            }
            pollTrue("daemon must exit") { countLive("elevated-late-spawn") == 0 }
            // 內層直呼 quarantineUnverified 落地（不走 registrar）；外層 helper 拋出僅計數、不落地。
            assertEquals(before + 1, ElevatedCleanupTracker.quarantineRegistrationFailedCount())
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            assertEquals(
                "PG-8: late-spawn daemon-body arm must add exactly one quarantine entry over the pre-test baseline",
                quarantineBefore + 1,
                ElevatedCleanupTracker.quarantinedCount(),
            )
            assertEquals(1, kills.get())
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
            // 恰好一次：floor 零命中。
            assertEquals(floorBefore, ElevatedCleanupTracker.releaseFloorHits())
            // 還原 registrar 後重試 helper（有意保留的 1 槽）：登記成功（quarantine 內 handle
            // 經 `===` 去重，數量不變）⇒ 恰好釋放恰好一次。
            ElevatedProcessIo.quarantineRegistrar = { ElevatedCleanupTracker.quarantineUnverified(it) }
            ElevatedProcessIo.releaseCleanupSlotAfterVerify(late, verified = false, slotHeld = true)
            assertEquals(0, ElevatedCleanupTracker.outstanding())
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            assertEquals(
                "PG-8: retry must not double-register (quarantine stays at baseline+1)",
                quarantineBefore + 1,
                ElevatedCleanupTracker.quarantinedCount(),
            )
            assertEquals(before + 1, ElevatedCleanupTracker.quarantineRegistrationFailedCount())
            assertEquals(floorBefore, ElevatedCleanupTracker.releaseFloorHits())
        } finally {
            // 還原預設 registrar；所有斷言完成後才放 cancel/spawn latch。
            ElevatedProcessIo.quarantineRegistrar = { ElevatedCleanupTracker.quarantineUnverified(it) }
            cancelGate.countDown()
            spawnRelease.countDown()
            bg.join(10_000L)
            // 主體未走到回收重試（斷言先失敗）時的兜底：有意保留的 1 槽在此收斂，
            // 避免 tearDown 輪詢誤判；helper 為 registered→release，恰好一次。
            val late = lateRef.get()
            if (late != null && ElevatedCleanupTracker.outstanding() == 1) {
                ElevatedProcessIo.releaseCleanupSlotAfterVerify(late, verified = false, slotHeld = true)
            }
            pollOutstandingTo(0)
            pollActiveTo(0)
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    /**
     * (b4) worker 內聯補償＋registrar 拋（Stage 4 pin；harness 同 P1B2Test S4 內聯 arm）：
     * spawn 阻塞於 pre-publish gate，deadline 先行逾期 ⇒ caller 先過期（interrupt 無效、
     * grace 後 lingering 記 1 槽），worker 放行後 publish、重讀 expired==true 自行
     * `compensateInline` 認領補償：killer 抛 OutOfMemoryError → waitFor=false → verify=false
     * → 三流有界關 → 內層直呼 `quarantineUnverified`（落 quarantine）；外層 helper registrar
     * **拋** ⇒ `quarantineRegistrationFailedCount` +1、補償槽**保留**。lingering 槽由
     * worker 本體 finally 恰好釋放（2→1 收斂於 outstanding==1）。admission/floor 斷言對齊 (b2)。
     */
    @Test fun compensateInlineWithThrowingRegistrarKeepsSlotFailClosed() {
        fun awaitUninterruptibly(latch: CountDownLatch) {
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // 吞中斷模擬不可中斷阻塞。
                }
            }
        }
        val before = ElevatedCleanupTracker.quarantineRegistrationFailedCount()
        // PG-8：inline 補償臂 quarantine delta（additive-only，baseline-relative）。
        val quarantineBefore = ElevatedCleanupTracker.quarantinedCount()
        val spawnEntered = CountDownLatch(1)
        val spawnRelease = CountDownLatch(1)
        val kills = AtomicInteger(0)
        val procRef = AtomicReference<LatchBlockedDestroyProcess?>()
        ElevatedProcessIo.quarantineRegistrar = { throw RuntimeException("registrar boom") }
        val res = ElevatedCallSupervisor.awaitSpawn(
            deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100L),
            isCancelled = { false },
            killer = {
                kills.incrementAndGet()
                throw OutOfMemoryError("inline killer oom")
            },
        ) {
            spawnEntered.countDown()
            awaitUninterruptibly(spawnRelease)
            LatchBlockedDestroyProcess(CountDownLatch(0)).also { procRef.set(it) }
        }
        try {
            assertTrue(spawnEntered.await(10, TimeUnit.SECONDS))
            assertTrue("expected Expired, got $res", res is ElevatedCallSupervisor.Res.Expired)
            assertTrue((res as ElevatedCallSupervisor.Res.Expired).hasLinger)
            // caller 過期返回當下：lingering 槽已記（deadline 先行逾期）。
            spawnRelease.countDown()
            // barrier：worker 完成 publish→CAS 認領→compensateInline（helper 計數至 before+1）。
            pollTrue("inline compensation must converge fail-closed and keep the slot") {
                ElevatedCleanupTracker.quarantineRegistrationFailedCount() == before + 1
            }
            // worker lingering 槽由本體 finally 恰好釋放：2（lingering＋補償）→ 1（補償槽保留）。
            pollTrue("worker must exit and release the lingering slot") {
                ElevatedCleanupTracker.outstanding() == 1
            }
            val proc = procRef.get()
                ?: throw AssertionError("spawn block must deliver the late process")
            // 內層直呼 quarantineUnverified 落地（不走 registrar）；外層 helper 拋出僅計數、不落地。
            assertEquals(before + 1, ElevatedCleanupTracker.quarantineRegistrationFailedCount())
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            assertEquals(
                "PG-8: inline compensation arm must add exactly one quarantine entry over the pre-test baseline",
                quarantineBefore + 1,
                ElevatedCleanupTracker.quarantinedCount(),
            )
            assertEquals(1, kills.get())
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
            // 恰好一次：floor 零命中。
            assertEquals(floorBefore, ElevatedCleanupTracker.releaseFloorHits())
            // 還原 registrar 後重試 helper（有意保留的 1 補償槽）：登記成功（quarantine 內
            // handle 經 `===` 去重，數量不變）⇒ 恰好釋放恰好一次。
            ElevatedProcessIo.quarantineRegistrar = { ElevatedCleanupTracker.quarantineUnverified(it) }
            ElevatedProcessIo.releaseCleanupSlotAfterVerify(proc, verified = false, slotHeld = true)
            assertEquals(0, ElevatedCleanupTracker.outstanding())
            assertEquals(
                "PG-8: retry must not double-register (quarantine stays at baseline+1)",
                quarantineBefore + 1,
                ElevatedCleanupTracker.quarantinedCount(),
            )
            assertEquals(before + 1, ElevatedCleanupTracker.quarantineRegistrationFailedCount())
            assertEquals(floorBefore, ElevatedCleanupTracker.releaseFloorHits())
        } finally {
            // 還原預設 registrar；所有斷言完成後才放 gate。
            ElevatedProcessIo.quarantineRegistrar = { ElevatedCleanupTracker.quarantineUnverified(it) }
            spawnRelease.countDown()
            // 主體未走到回收重試（斷言先失敗）時的兜底：有意保留的 1 補償槽在此收斂，
            // 避免 tearDown 輪詢誤判；helper 為 registered→release，恰好一次。
            val proc = procRef.get()
            if (proc != null && ElevatedCleanupTracker.outstanding() == 1) {
                ElevatedProcessIo.releaseCleanupSlotAfterVerify(proc, verified = false, slotHeld = true)
            }
            pollOutstandingTo(0)
            pollActiveTo(0)
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    /**
     * (c) caller 側 CAS 認領瞬間槽飽和（startLateDaemon 飽和 arm）：spawn 逾期交付
     * 發佈、worker 退出後、caller 取消門放行之前，預填至 MAX 使 acquireSlot
     * 必敗——expireWorker 認領成功後 startLateDaemon 無槽可佔→quarantine 登記
     * ＋incomplete/unclean（零 kill、不發起 daemon、不同步阻塞）；caller 有界返回
     * Res.Expired(hasLinger=true)。確定性 barrier 結構同 (b)（預填夾在 publish
     * barrier 與 cancelGate 放行之間）。ARMED harness：late Process 為 latch 阻塞
     * destroy（teardown 才放）、注入 killer 記數後 latch 阻塞，任何終止嘗試皆被
     * 計數／阻塞，必然被零 kill 斷言抓住。
     */
    @Test fun lateSpawnCallerSaturatedAtClaimQuarantinesWithoutKillAttempt() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        ElevatedCleanupTracker.resetPeak()
        // PG-8：caller-side saturation claim quarantine delta（additive-only，baseline-relative）。
        val quarantineBefore = ElevatedCleanupTracker.quarantinedCount()
        // T6：caller-side 飽和認領永不進入 start-failure 路徑 ⇒ 計數器不得動（killNeverAttempted 穩定性釘）。
        val killNeverBefore = ElevatedCleanupTracker.killNeverAttemptedCount()
        val spawnEntered = CountDownLatch(1)
        val spawnRelease = CountDownLatch(1)
        val cancelGate = CountDownLatch(1)
        val cancelBlockEntered = CountDownLatch(1)
        // armed killer 阻塞門：teardown（所有斷言之後）才放；期間任何 kill
        // 嘗試皆被 kills 計數且卡死於 latch，必然被斷言抓住。
        val killerLatch = CountDownLatch(1)
        val lateRef = AtomicReference<LatchBlockedDestroyProcess?>()
        val resultRef = AtomicReference<ElevatedCallSupervisor.Res<Process>?>()
        val kills = AtomicInteger(0)
        // 確定性 barrier：spawn 進入前不擋門，進入後阻塞門（watcher 卡門內，
        // caller 仍輪詢），放行後 hook 回 true，caller 經 expireWorker 認領。
        val isCancelled: () -> Boolean = {
            if (spawnEntered.count > 0) {
                false
            } else if (resultRef.get() != null) {
                false
            } else {
                cancelBlockEntered.countDown()
                stuckAwait(cancelGate)
                true
            }
        }
        val bg = Thread {
            resultRef.set(
                ElevatedCallSupervisor.awaitSpawnWithThreadStarter(
                    deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(60),
                    isCancelled = isCancelled,
                    killer = {
                        kills.incrementAndGet()
                        stuckAwait(killerLatch)
                    },
                ) {
                    spawnEntered.countDown()
                    stuckAwait(spawnRelease)
                    LatchBlockedDestroyProcess(killerLatch).also { lateRef.set(it) }
                },
            )
        }.also { it.isDaemon = true; it.start() }
        var prefilled = 0
        try {
            assertTrue("spawn must enter", spawnEntered.await(10_000L, TimeUnit.MILLISECONDS))
            assertTrue(
                "caller watcher must block in cancel gate",
                cancelBlockEntered.await(10_000L, TimeUnit.MILLISECONDS),
            )
            // 放行 spawn→worker 發佈（expired 仍 false，留 Val 給 caller）。
            spawnRelease.countDown()
            // barrier：lateRef 非空即已發佈；worker 退出由下條 countLive 收斂。
            pollRefNonNull(lateRef, "late spawn")
            // 等 worker 完全退出：countLive("elevated-supervisor") 從 2
            //（spawn worker＋watcher）降到 1（僅 watcher 卡 cancelGate）。
            pollTrue("spawn worker must exit before saturation pre-fill") {
                countLive("elevated-supervisor") == 1
            }
            // 預填至 MAX（worker 已退出，caller 尚未認領；lingering 0）：
            // expireWorker CAS 認領後 startLateDaemon acquireSlot 必敗（飽和 arm）。
            repeat(ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS) { k ->
                if (!ElevatedCleanupTracker.acquireSlot()) {
                    throw AssertionError(
                        "prefill failed at $k/${ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS}" +
                            " outstanding=${ElevatedCleanupTracker.outstanding()}",
                    )
                }
                prefilled = k + 1
            }
            assertTrue(ElevatedCleanupTracker.isSaturated())
            // 放行取消門→watcher bit 閂→caller expireWorker→CAS 認領→startLateDaemon
            // 佔槽失敗→quarantine（零 kill），caller 有界返回。
            val start = System.currentTimeMillis()
            cancelGate.countDown()
            val res = pollRefNonNull(resultRef, "caller result")
            val elapsed = System.currentTimeMillis() - start
            // invariant 為有界返回，非 wall-clock budget（3s 視平台排程而 flaky）。
            assertTrue(
                "caller must return within a bounded time, elapsed=$elapsed",
                elapsed < 10_000L,
            )
            assertTrue("expected Res.Expired, got $res", res is ElevatedCallSupervisor.Res.Expired)
            res as ElevatedCallSupervisor.Res.Expired
            assertTrue(
                "caller-side saturation claim must flag hasLinger, got $res",
                res.hasLinger,
            )
            val late = pollRefNonNull(lateRef, "late spawn")!!
            // 限制：ElevatedCleanupTracker 無 identity probe，不能斷言 quarantine
            // 條目即 late handle——只斷登記數；誤歸因不在本用例斷言範圍。
            assertEquals(
                "PG-8: caller-side saturation claim must add exactly one quarantine entry over the pre-test baseline",
                quarantineBefore + 1,
                ElevatedCleanupTracker.quarantinedCount(),
            )
            assertEquals("killer must never run on the saturation claim path", 0, kills.get())
            assertEquals(
                "killNeverAttemptedCount must not move on the saturation claim path (never reaches start-failure)",
                killNeverBefore,
                ElevatedCleanupTracker.killNeverAttemptedCount(),
            )
            assertEquals(
                "destroy() must never run on the saturation claim path",
                0,
                late.destroyCalls.get(),
            )
            assertEquals(
                "destroyForcibly() must never run on the saturation claim path",
                0,
                late.destroyForciblyCalls.get(),
            )
            assertEquals(0, countLive("elevated-late-spawn"))
            val lateKillThreads = Thread.getAllStackTraces().keys.filter {
                it.name == "elevated-late-kill" && it.isAlive
            }
            assertTrue(
                "no thread named elevated-late-kill may exist: $lateKillThreads",
                lateKillThreads.isEmpty(),
            )
            // caller 認領前不持槽（預填槽留存、lingering 0），startLateDaemon 飽和即
            // 不佔槽不釋槽，故 outstanding 恰為預填的 MAX，不增不減。
            assertEquals(
                "caller-side saturated claim must retain pre-filled slots exactly",
                ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS,
                ElevatedCleanupTracker.outstanding(),
            )
            assertEquals(1, ElevatedCleanupTracker.consecutiveCount())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            // 確定性峰值：預填恰好佔滿 MAX 即峰值（無額外佔槽／釋槽）。
            assertEquals(
                ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS,
                ElevatedCleanupTracker.peakOutstanding(),
            )
        } finally {
            // 所有斷言完成後才放 armed killer latch；countDown 只此一次。
            killerLatch.countDown()
            cancelGate.countDown()
            spawnRelease.countDown()
            bg.join(10_000L)
            repeat(prefilled) { ElevatedCleanupTracker.releaseSlot() }
            pollOutstandingTo(0)
            pollActiveTo(0)
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }
}
