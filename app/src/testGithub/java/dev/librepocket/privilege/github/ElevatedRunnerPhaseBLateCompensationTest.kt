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

    @Before fun setUpIsolation() {
        hermeticExec = HermeticExecFixture("phaseb-hermetic", "id")
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        ElevatedCleanupTracker.resetPeak()
    }

    @After fun tearDownIsolation() {
        hermeticExec?.close()
        hermeticExec = null
        repeat(8) { ElevatedCleanupTracker.releaseSlot() }
        repeat(8) { ElevatedCleanupTracker.leaveCall() }
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
            assertEquals(1, kills.get())
            assertEquals(1, spawns.get())
            // daemon 持槽殘留：outstanding/active/活 daemon 一致。
            pollTrue("late daemon must hold one slot") { ElevatedCleanupTracker.outstanding() == 1 }
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            pollTrue("elevated-late-spawn must stay alive") { countLive("elevated-late-spawn") == 1 }
            assertEquals(0, countLive("elevated-supervisor"))
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
            assertTrue((r as ElevatedBinaryResult.TimedOut).cleanupIncomplete)
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
            val peak = ElevatedCleanupTracker.peakOutstanding()
            assertTrue(
                "peak=$peak must stay within MAX=${ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS}",
                peak <= ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS,
            )
        } finally {
            release.countDown()
            repeat(prefilled) { ElevatedCleanupTracker.releaseSlot() }
            pollOutstandingTo(0)
            pollActiveTo(0)
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
            assertTrue(
                "caller must return within a bounded time, elapsed=$elapsed",
                elapsed < 3_000L,
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
            assertEquals("killer must never run on the saturation path", 0, kills.get())
            assertEquals("destroy() must never run on the saturation path", 0, late.destroyCalls.get())
            assertEquals(
                "destroyForcibly() must never run on the saturation path",
                0,
                late.destroyForciblyCalls.get(),
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
     * ARMED harness：late Process 為 latch 阻塞 destroy（teardown 才放），注入
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
            assertTrue(
                "caller must return within a bounded time, elapsed=$elapsed",
                elapsed < 3_000L,
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
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            assertEquals("killer must never run on the saturation claim path", 0, kills.get())
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
