package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.Validation
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
 * Phase 3 資源上界＋生命週期硬化迴歸（P1-4，PR #140 Phase 3）：
 * - 六點同卡 barrier（wait＋cleanup＋verify＋stdout＋stderr＋stdin-close）：
 *   同步峰值 `peakOutstanding() <= MAX_OUTSTANDING_CLEANUPS`（替代 sleep 採樣，
 *   `acquireSlot` 同鎖內同步更新，無遺漏）；終止後 slot 全釋、verdict
 *   `TimedOut(cleanupIncomplete=true)`、breaker 只計 1（不熔斷）、殘留期間新
 *   call 即預留拒；
 * - finished tail budget：已 exit 但雙流不 EOF＋極短 timeout／取消即有界返回
 *   （舊兩段 10s 可釘 20s＋，現雙流共享 2s）；
 * - validate FS 有界化：注入阻塞 validate（FUSE-stall 模擬）＋timeout 即有界
 *   `TimedOut(cleanupIncomplete=true)`，零 spawn；
 * - wait 殘留歸因：他 call 殘留（prefill）下本 call 無殘留超時不置旗、不計 breaker；
 * - exitValue：tail 期間呼叫執行緒被中斷，健康進程仍取回真 exit（非 -1 誤報）。
 * - acquireSlot 飽和即拒：填滿至 MAX 後再佔即 false、不增計數（`outstanding`
 *   封頂不超 MAX）；飽和下 kill 路徑 drain 仍嘗試 killer（永不跳過）但 verdict
 *   保守記 `cleanupIncomplete=true`（無法記槽即無法自證有界完成）。
 *
 * 全 fake，零真機、零 su、零 binder。阻塞體一律吞 interrupt 續等（模擬不可靠
 * Binder 中斷；可中斷 stalls 由既有 hang 語義覆，此處專測不可中斷殘留追蹤）。
 */
class ElevatedRunnerPhase3HardeningTest {

    private val privateRoot = "/data/data/files"

    @Before fun setUpIsolation() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        ElevatedCleanupTracker.resetPeak()
    }

    @After fun tearDownIsolation() {
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

    /** 不可中斷阻塞讀流（latch 釋放前永不 EOF；釋放後回 -1 收斂）。 */
    private class UninterruptibleReadInput(private val latch: CountDownLatch) : InputStream() {
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
        override fun read(): Int {
            awaitLatch()
            return -1
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            awaitLatch()
            return -1
        }
    }

    /** 不可中斷阻塞 stdin 關流（吞 interrupt 續等）。 */
    private class BlockingStdinCloseOutput(private val latch: CountDownLatch) : ByteArrayOutputStream() {
        override fun close() {
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // 吞 interrupt 續等。
                }
            }
            super.close()
        }
    }

    /**
     * 六點同卡假進程（kill 路徑）：waitFor 切片／destroy／verify 用 waitFor／
     * 雙流 read／stdin close 六處同 latch 阻塞（皆吞 interrupt）。
     */
    private class SixPointStuckProcess(private val latch: CountDownLatch) : Process() {
        @Volatile var destroyed = false
        private fun stuck() {
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // 吞 interrupt 續等。
                }
            }
        }
        override fun getOutputStream(): OutputStream = BlockingStdinCloseOutput(latch)
        override fun getInputStream(): InputStream = UninterruptibleReadInput(latch)
        override fun getErrorStream(): InputStream = UninterruptibleReadInput(latch)
        override fun waitFor(): Int {
            stuck()
            return 0
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (destroyed) return true
            stuck()
            return destroyed
        }
        override fun exitValue(): Int = 0
        override fun destroy() {
            stuck()
            destroyed = true
        }
        override fun destroyForcibly(): Process {
            stuck()
            destroyed = true
            return this
        }
    }

    /** 六點同卡遠端假物件（非 Process，經 [ShizukuRemoteProcess.wrap] 包裝）。 */
    private class SixPointStuckRemote(private val latch: CountDownLatch) {
        @Volatile var destroyed = false
        private fun stuck() {
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // 吞 interrupt 續等。
                }
            }
        }
        fun getOutputStream(): OutputStream = BlockingStdinCloseOutput(latch)
        fun getInputStream(): InputStream = UninterruptibleReadInput(latch)
        fun getErrorStream(): InputStream = UninterruptibleReadInput(latch)
        fun waitFor(): Int {
            stuck()
            return 0
        }
        fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (destroyed) return true
            stuck()
            return destroyed
        }
        fun exitValue(): Int = 0
        fun destroy() {
            stuck()
            destroyed = true
        }
    }

    /** 已 exit 但雙流永不 EOF（finished tail 上界用；latch 釋放後收斂 EOF）。 */
    private class ExitedStuckStreamsProcess(private val latch: CountDownLatch) : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = UninterruptibleReadInput(latch)
        override fun getErrorStream(): InputStream = UninterruptibleReadInput(latch)
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }

    // ---- 六點同卡 barrier（Root／remote） ----

    @Test fun rootSixPointStuckPeakBounded() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        ElevatedCleanupTracker.resetPeak()
        val latch = CountDownLatch(1)
        val kills = AtomicInteger(0)
        val spawns = AtomicInteger(0)
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            spawn = {
                spawns.incrementAndGet()
                SixPointStuckProcess(latch)
            },
            suBinary = "/system/bin/su",
            killer = {
                kills.incrementAndGet()
                it.destroy()
            },
            grantCheck = GrantFixtures.allowAlways,
        )
        try {
            val resultRef = AtomicReference<ElevatedBinaryResult?>()
            val start = System.currentTimeMillis()
            // timeout 3000ms：stdin 起手關先耗 2000ms 上界，剩餘 ≈1000ms 仍進 wait
            // 輪詢（wait worker 照常 stall＋記槽），六點方能同卡；300ms 下 wait 段
            // 被跳過（remaining<=0 直 break），只能卡 5 點。
            val bg = Thread {
                resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "P3-6PT"), 3_000L))
            }.also { it.isDaemon = true; it.start() }
            bg.join(40_000L)
            val elapsed = System.currentTimeMillis() - start
            assertFalse("drain must return bounded despite 6 stuck points", bg.isAlive)
            // 有界返回為主證；上界 40s 僅 CI 餘量（stdin 2s＋wait 1.5s＋cleanup 1s＋
            // verify 6s＋gobblers 10s＋stdin 0.5s ≈21s），絕不無界。
            assertTrue("elapsed=$elapsed", elapsed < 40_000L)
            val b = resultRef.get()
            assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
            b as ElevatedBinaryResult.TimedOut
            assertTrue("cleanupIncomplete expected", b.cleanupIncomplete)
            assertEquals(1, kills.get())
            assertEquals(1, spawns.get())
            // 六槽同持（wait＋cleanup＋verify＋stdout＋stderr＋stdin），同步峰值有界。
            assertEquals(
                "outstanding=${ElevatedCleanupTracker.outstanding()}",
                6,
                ElevatedCleanupTracker.outstanding(),
            )
            val peak = ElevatedCleanupTracker.peakOutstanding()
            assertTrue(
                "peak=$peak must stay within MAX=${ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS}",
                peak <= ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS,
            )
            // 終審：單次不清創計 1，不熔斷；席已釋。
            assertEquals(1, ElevatedCleanupTracker.consecutiveCount())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            // 殘留期間新 call 即預留拒（6+7>7），零新增 spawn。
            val denied = runner.runBinary(ElevatedRequest(listOf("id"), "P3-6PT"), 300L)
            assertTrue("$denied", denied is ElevatedBinaryResult.Denied)
            denied as ElevatedBinaryResult.Denied
            assertEquals(ShellDeny.QUOTA_EXCEEDED, denied.reason)
            assertTrue("msg=${denied.message}", denied.message.contains("backlog full"))
            assertEquals(1, spawns.get())
        } finally {
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
    }

    @Test fun shizukuSixPointStuckPeakBounded() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        ElevatedCleanupTracker.resetPeak()
        val latch = CountDownLatch(1)
        val kills = AtomicInteger(0)
        val spawns = AtomicInteger(0)
        val runner = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            pingBinder = { true },
            spawn = {
                spawns.incrementAndGet()
                ShizukuRemoteProcess.wrap(SixPointStuckRemote(latch))
            },
            status = { ShizukuStatus.Available(15) },
            killer = {
                kills.incrementAndGet()
                it.destroy()
            },
            grantCheck = GrantFixtures.allowAlways,
        )
        try {
            val resultRef = AtomicReference<ElevatedBinaryResult?>()
            val start = System.currentTimeMillis()
            // timeout 3000ms：stdin 起手關先耗 2000ms 上界，剩餘 ≈1000ms 仍進 wait
            // 輪詢（wait worker 照常 stall＋記槽），六點方能同卡（見 Root 側註記）。
            val bg = Thread {
                resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "P3-6PT"), 3_000L))
            }.also { it.isDaemon = true; it.start() }
            bg.join(40_000L)
            val elapsed = System.currentTimeMillis() - start
            assertFalse("drain must return bounded despite 6 stuck points", bg.isAlive)
            assertTrue("elapsed=$elapsed", elapsed < 40_000L)
            val b = resultRef.get()
            assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
            b as ElevatedBinaryResult.TimedOut
            assertTrue("cleanupIncomplete expected", b.cleanupIncomplete)
            assertEquals(1, kills.get())
            assertEquals(1, spawns.get())
            assertEquals(
                "outstanding=${ElevatedCleanupTracker.outstanding()}",
                6,
                ElevatedCleanupTracker.outstanding(),
            )
            val peak = ElevatedCleanupTracker.peakOutstanding()
            assertTrue(
                "peak=$peak must stay within MAX=${ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS}",
                peak <= ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS,
            )
            assertEquals(1, ElevatedCleanupTracker.consecutiveCount())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            val denied = runner.runBinary(ElevatedRequest(listOf("id"), "P3-6PT"), 300L)
            assertTrue("$denied", denied is ElevatedBinaryResult.Denied)
            denied as ElevatedBinaryResult.Denied
            assertEquals(ShellDeny.QUOTA_EXCEEDED, denied.reason)
            assertTrue("msg=${denied.message}", denied.message.contains("backlog full"))
            assertEquals(1, spawns.get())
        } finally {
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
    }

    // ---- finished tail 上界（極短 timeout／取消） ----

    private fun doFinishedTailShortTimeout(
        makeRunner: ((List<String>) -> Process) -> BinaryElevatedRunner,
    ) {
        val latch = CountDownLatch(1)
        val runner = makeRunner { ExitedStuckStreamsProcess(latch) }
        try {
            val start = System.currentTimeMillis()
            val b = runner.runBinary(ElevatedRequest(listOf("id"), "P3-TAIL"), 300L)
            val elapsed = System.currentTimeMillis() - start
            // 已 exit：finished 路徑（非 TimedOut），舊碼雙 10s joins 可釘 20s＋，
            // 現 tail 共享 2s（此處剩餘 ≈300ms 即收）。
            assertTrue("elapsed=$elapsed must be far below legacy 20s tail", elapsed < 8_000L)
            assertTrue("$b", b is ElevatedBinaryResult.Ok)
            b as ElevatedBinaryResult.Ok
            assertEquals(0, b.exitCode)
            assertTrue("stuck streams must truncate", b.truncated)
            assertTrue("stuck streams must flag incomplete", b.cleanupIncomplete)
            // 雙流同持 2 槽。
            assertEquals(2, ElevatedCleanupTracker.outstanding())
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    @Test fun rootFinishedTailShortTimeoutBounded() {
        doFinishedTailShortTimeout { spawn ->
            RootSuRunner(
                privateRoot = privateRoot,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                spawn = spawn,
                suBinary = "/system/bin/su",
                grantCheck = GrantFixtures.allowAlways,
            )
        }
    }

    @Test fun shizukuFinishedTailShortTimeoutBounded() {
        doFinishedTailShortTimeout { spawn ->
            ShizukuShellRunner(
                privateRoot = privateRoot,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                pingBinder = { true },
                spawn = spawn,
                status = { ShizukuStatus.Available(15) },
                grantCheck = GrantFixtures.allowAlways,
            )
        }
    }

    private fun doFinishedTailCancel(
        makeRunner: ((List<String>) -> Process, () -> Boolean) -> BinaryElevatedRunner,
    ) {
        val latch = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val runner = makeRunner({ ExitedStuckStreamsProcess(latch) }, { cancelled.get() })
        try {
            val resultRef = AtomicReference<ElevatedBinaryResult?>()
            val start = System.currentTimeMillis()
            val bg = Thread {
                resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "P3-TAIL"), 10_000L))
            }.also { it.isDaemon = true; it.start() }
            // waitFor 即回 true（~ms 即 finished），600ms 後取消必落於 tail 段
            //（tail 無取消將跑滿 2s，餘量充足，不競態）。
            Thread.sleep(600L)
            cancelled.set(true)
            bg.join(15_000L)
            val elapsed = System.currentTimeMillis() - start
            assertFalse("tail must abort on cancel", bg.isAlive)
            assertTrue("elapsed=$elapsed", elapsed < 8_000L)
            val b = resultRef.get()
            // finished 已成立（取消落於 tail）：仍走 finished 路徑，中斷＋雙旗＋break。
            assertTrue("$b", b is ElevatedBinaryResult.Ok)
            b as ElevatedBinaryResult.Ok
            assertEquals(0, b.exitCode)
            assertTrue("cancelled tail must flag incomplete", b.cleanupIncomplete)
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            cancelled.set(true)
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    @Test fun rootFinishedTailCancelBounded() {
        doFinishedTailCancel { spawn, isCancelled ->
            RootSuRunner(
                privateRoot = privateRoot,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                spawn = spawn,
                suBinary = "/system/bin/su",
                isCancelled = isCancelled,
                grantCheck = GrantFixtures.allowAlways,
            )
        }
    }

    @Test fun shizukuFinishedTailCancelBounded() {
        doFinishedTailCancel { spawn, isCancelled ->
            ShizukuShellRunner(
                privateRoot = privateRoot,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                pingBinder = { true },
                spawn = spawn,
                status = { ShizukuStatus.Available(15) },
                isCancelled = isCancelled,
                grantCheck = GrantFixtures.allowAlways,
            )
        }
    }

    // ---- validate FS 有界化（注入阻塞 validate 模擬 FUSE stall） ----

    private fun doValidateStall(
        makeRunner: (((List<String>, Boolean) -> Validation)?, AtomicInteger) -> BinaryElevatedRunner,
    ) {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        val latch = CountDownLatch(1)
        val spawns = AtomicInteger(0)
        val runner = makeRunner(
            { _, _ ->
                stuckAwait(latch)
                Validation.Allowed("id")
            },
            spawns,
        )
        try {
            val start = System.currentTimeMillis()
            val r = runner.runBinary(ElevatedRequest(listOf("id"), "P3-VAL"), 300L)
            val elapsed = System.currentTimeMillis() - start
            // FUSE-stall 模擬：呼叫方有界返回（supervisor deadline＋grace ≈0.8s），
            // 殘留 worker 記槽＋breaker 計數，verdict 穿透 hasLinger=true。
            assertTrue("elapsed=$elapsed", elapsed < 8_000L)
            assertTrue("$r", r is ElevatedBinaryResult.TimedOut)
            r as ElevatedBinaryResult.TimedOut
            assertTrue("validate stall must flag incomplete", r.cleanupIncomplete)
            assertEquals("validate stall must not spawn", 0, spawns.get())
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            assertEquals(1, ElevatedCleanupTracker.consecutiveCount())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
    }

    @Test fun rootValidateStallIsBoundedIncomplete() {
        doValidateStall { validateFn, spawns ->
            RootSuRunner(
                privateRoot = privateRoot,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                spawn = {
                    spawns.incrementAndGet()
                    FakeProcess("ok".toByteArray())
                },
                suBinary = "/system/bin/su",
                grantCheck = GrantFixtures.allowAlways,
                validateFn = validateFn,
            )
        }
    }

    @Test fun shizukuValidateStallIsBoundedIncomplete() {
        doValidateStall { validateFn, spawns ->
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
                grantCheck = GrantFixtures.allowAlways,
                validateFn = validateFn,
            )
        }
    }

    @Test fun validateFnDeniedHonoredWithoutSpawn() {
        // 接縫語義：注入快路徑 Denied 即按產品同碼拒，零 spawn、不佔殘留。
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val spawns = AtomicInteger(0)
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            spawn = {
                spawns.incrementAndGet()
                FakeProcess("ok".toByteArray())
            },
            suBinary = "/system/bin/su",
            grantCheck = GrantFixtures.allowAlways,
            validateFn = { _, _ -> Validation.Denied(ShellDeny.BLACKLISTED, "nono") },
        )
        val r = runner.runBinary(ElevatedRequest(listOf("id"), "P3-VAL"), 2_000L)
        assertTrue("$r", r is ElevatedBinaryResult.Denied)
        assertEquals(0, spawns.get())
        assertEquals(0, ElevatedCleanupTracker.outstanding())
        assertEquals(0, ElevatedCleanupTracker.activeCalls())
    }

    // ---- wait 殘留歸因（per-worker，不用全域 outstanding 推斷） ----

    @Test fun waitExpiredWithoutLingerDoesNotAttributeForeignResidue() {
        // 他 call 殘留（prefill 1 槽）下，本 call wait 逾期但 worker 已收
        // （`Expired(hasLinger=false)`）：不得置 cleanupIncomplete，不得計 breaker。
        // 舊碼以 `outstanding>0` 推斷即誤置旗＋誤計（consecutive 0→1）。
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        ElevatedCleanupTracker.acquireSlot()
        assertEquals(1, ElevatedCleanupTracker.outstanding())
        try {
            // hang＋可中斷：deadline 到時 supervisor interrupt 即退出（grace 內必收，
            // hasLinger=false 確定）；kill 後 destroy 即解，後續無殘留。
            val d = ElevatedProcessIo.drain(
                FakeProcess("ok".toByteArray(), ByteArray(0), 0, hang = true),
                100L,
                { it.destroy() },
                { Thread.currentThread().isInterrupted },
            )
            assertTrue("timedOut expected, got $d", d.timedOut)
            assertFalse("foreign residue must not flag this call: $d", d.cleanupIncomplete)
            assertEquals(0, ElevatedCleanupTracker.consecutiveCount())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            assertEquals(1, ElevatedCleanupTracker.outstanding())
        } finally {
            ElevatedCleanupTracker.releaseSlot()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
        }
    }

    // ---- exitValue：tail 中斷後仍取真 exit（非 -1 誤報） ----

    @Test fun midTailInterruptStillReadsTrueExitValue() {
        // finished 後 tail 期間呼叫執行緒被中斷：joins restore 旗標；舊碼 exitValue
        // 見旗標即 Expired→-1 誤報（runner 誤判 Failed）；新碼快照清除＋獨立
        // deadline，健康進程取回真 exit 0。
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val slowStdout = object : InputStream() {
            override fun read(): Int {
                try {
                    Thread.sleep(2_000L)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.IOException("interrupted slow read", e)
                }
                return -1
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int = read()
        }
        val proc = object : Process() {
            override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
            override fun getInputStream(): InputStream = slowStdout
            override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun waitFor(): Int = 0
            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
            override fun exitValue(): Int = 0
            override fun destroy() = Unit
            override fun destroyForcibly(): Process = this
        }
        val resultRef = AtomicReference<ElevatedProcessIo.Drained?>()
        val bg = Thread {
            resultRef.set(ElevatedProcessIo.drain(proc, 10_000L))
        }.also { it.isDaemon = true; it.start() }
        try {
            // tail 段（~ms 即進入，2s 上界）中段打斷背景執行緒。
            Thread.sleep(300L)
            bg.interrupt()
            bg.join(15_000L)
            assertFalse("drain must return after interrupt", bg.isAlive)
            val d = resultRef.get()
            assertTrue("finished expected (not timedOut): $d", d != null && !d.timedOut)
            d!!
            assertEquals("true exit must survive tail interrupt, got $d", 0, d.exitCode)
            assertTrue("aborted tail must flag incomplete: $d", d.cleanupIncomplete)
        } finally {
            bg.join(5_000L)
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    // ---- acquireSlot 飽和即拒（P1-4 bounded accounting） ----

    @Test fun acquireSlotRefusesAtSaturationWithoutGrowth() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        ElevatedCleanupTracker.resetPeak()
        try {
            repeat(ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS) {
                assertTrue("slot $it must be granted below MAX", ElevatedCleanupTracker.acquireSlot())
            }
            assertEquals(
                ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS,
                ElevatedCleanupTracker.outstanding(),
            )
            assertTrue(ElevatedCleanupTracker.isSaturated())
            // 飽和即拒：不遞增、不動峰值。
            assertFalse("saturated acquire must refuse", ElevatedCleanupTracker.acquireSlot())
            assertEquals(
                ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS,
                ElevatedCleanupTracker.outstanding(),
            )
            val peak = ElevatedCleanupTracker.peakOutstanding()
            assertTrue(
                "peak=$peak must stay within MAX=${ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS}",
                peak <= ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS,
            )
        } finally {
            repeat(ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS) {
                ElevatedCleanupTracker.releaseSlot()
            }
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            pollActiveTo(0)
        }
    }

    @Test fun saturatedDrainKillPathStillKillsButFlagsIncomplete() {
        // 飽和（預填滿 MAX）下 kill 路徑 drain：killer 仍被嘗試（FakeProcess
        // destroyed 置 true，不跳過），但無槽可記即保守 `cleanupIncomplete=true`
        // （經終審計 breaker 1 次），且 `outstanding` 封頂不增（仍為 MAX）。
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        repeat(ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS) {
            assertTrue(ElevatedCleanupTracker.acquireSlot())
        }
        assertEquals(
            ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS,
            ElevatedCleanupTracker.outstanding(),
        )
        val proc = FakeProcess("ok".toByteArray(), ByteArray(0), 0, hang = true)
        try {
            val d = ElevatedProcessIo.drain(
                proc,
                300L,
                { it.destroy() },
                { Thread.currentThread().isInterrupted },
            )
            assertTrue("killer must still run under saturation", proc.destroyed)
            assertTrue("timedOut expected, got $d", d.timedOut)
            assertTrue("saturated cleanup must flag incomplete: $d", d.cleanupIncomplete)
            assertEquals(
                "outstanding must stay capped at MAX, got ${ElevatedCleanupTracker.outstanding()}",
                ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS,
                ElevatedCleanupTracker.outstanding(),
            )
            assertEquals(1, ElevatedCleanupTracker.consecutiveCount())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
        } finally {
            repeat(ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS) {
                ElevatedCleanupTracker.releaseSlot()
            }
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
    }
}
