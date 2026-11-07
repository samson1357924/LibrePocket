package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ShellDeny
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
 * AA1 回歸（owner 新 P1＋P2；PocketGuard 無動作項；串行契約下預填代理多 stall 疊加，真實多 drain 疊加由 P1Fix 混合測試覆蓋）：
 * 跨呼叫清理監督＋旗標穿透。全 fake（latch／空流假進程），零真機、零 su、零 binder。
 *
 * - 飽和門：連續 4 次不可中斷 destroy stall（吞 interrupt、不釋放直到 finally）timeout
 *   後，第 5 次 spawn 即 `Denied(QUOTA_EXCEEDED)` 零 spawn（Root 側；另測 Shizuku 側
 *   被同一全域計數擋下，證跨 runner 共用）；finally 釋放全部 latch＋輪詢 outstanding
 *   回 0（防污染）。每次 stall 耗時約 cleanup budget 下界（500ms join＋500ms grace
 *   ＋300ms poll），4 次約數秒；
 * - 恢復：釋放後 outstanding 歸 0 但 breaker 仍開（late 完成只釋放 slot，不歸零
 *   consecutive，fail-closed 閂鎖），需 noteClean（模擬#136-B3介入）才放行；
 * - 旗標：blocked destroy → `TimedOut(cleanupIncomplete=true)`；success＋blocked
 *   stdin → `Ok(cleanupIncomplete=true)`；正常 → false。Root＋Shizuku 雙側。
 */
class ElevatedRunnerAA1Test {

    private val privateRoot = "/data/data/files"

    /** 不可中斷 destroy stall：吞 interrupt 續等 latch；waitFor 即時回 destroyed（快 hang，不睡）。 */
    private class StallDestroyProcess(
        private val latch: CountDownLatch,
    ) : Process() {
        @Volatile var destroyEntered = false
        @Volatile private var destroyed = false
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = destroyed
        override fun exitValue(): Int = 0
        override fun destroy() {
            destroyEntered = true
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // 吞 interrupt 續等（模擬不可中斷 Binder stall）。
                }
            }
            destroyed = true
        }
        override fun destroyForcibly(): Process {
            destroyed = true
            return this
        }
    }

    /** 吞 interrupt 的阻塞 stdin close（釋放前永不返回）。 */
    private class BlockingStdinCloseOutput(private val latch: CountDownLatch) : ByteArrayOutputStream() {
        override fun close() {
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // 吞 interrupt 續等，尾收 grace 無法解開，flag true。
                }
            }
            super.close()
        }
    }

    /** success-exit＋stdin close 阻塞；stdout 固定 "ok"（完整）。 */
    private class BlockedStdinSuccessProcess(
        private val latch: CountDownLatch,
    ) : Process() {
        override fun getOutputStream(): OutputStream = BlockingStdinCloseOutput(latch)
        override fun getInputStream(): InputStream = ByteArrayInputStream("ok".toByteArray())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }

    private fun makeRoot(spawn: (List<String>) -> Process): RootSuRunner = RootSuRunner(
        privateRoot = privateRoot,
        bridgeGranted = GrantFixtures.bridgeOn,
        activationCheck = GrantFixtures.activationOn,
        spawn = spawn,
        suBinary = "/system/bin/su",
        grantCheck = GrantFixtures.allowAlways,
    )

    private fun makeShizuku(spawn: (List<String>) -> Process): ShizukuShellRunner =
        ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            pingBinder = { true },
            spawn = spawn,
            status = { ShizukuStatus.Available(15) },
            grantCheck = GrantFixtures.allowAlways,
        )

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

    @Test fun saturationGateBlocksBothRunnersAndRecovers() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean() // 測試隔離重置，非#136-B3修復。
        pollOutstandingTo(0)
        // P1-2 預留（PR140 Phase1，特權宜序列）：乾淨態僅允單一進行中呼叫。
        // 先 1 stall（0+7<=7 放行，outstanding 0→1），再預填 6 達飽和（1+6=7）；
        // 另補 3 次 noteUnclean 使熔斷亦成立（drain 已計 1，補 3 即連續 4）。
        val latch = CountDownLatch(1)
        val rootSpawns = AtomicInteger(0)
        val root = makeRoot {
            rootSpawns.incrementAndGet()
            StallDestroyProcess(latch)
        }
        try {
            // 1 次不可中斷 stall timeout：有界 TimedOut＋flag true，outstanding 0→1。
            val b = root.runBinary(ElevatedRequest(listOf("id"), "AA1-SAT"), 300L)
            assertTrue("call 1: $b", b is ElevatedBinaryResult.TimedOut)
            assertTrue(
                "call 1: cleanupIncomplete expected",
                (b as ElevatedBinaryResult.TimedOut).cleanupIncomplete,
            )
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            repeat(6) { ElevatedCleanupTracker.acquireSlot() }
            assertEquals(7, ElevatedCleanupTracker.outstanding())
            // 補足熔斷計數（drain 已計 1，再補 3 即連續 4；與飽和同時成立）。
            repeat(3) { ElevatedCleanupTracker.noteUnclean() }
            assertTrue(ElevatedCleanupTracker.isSaturated())
            // 終審殘留相容：連續不清創亦觸熔斷（與飽和同時成立，第 5 次兩因皆拒，優先 backlog）。
            assertTrue(ElevatedCleanupTracker.isBreakerOpen())
            assertEquals(1, rootSpawns.get())
            // 第 5 次 Root spawn 即拒：零 spawn（計數不變）。
            val denied = root.runBinary(ElevatedRequest(listOf("id"), "AA1-SAT"), 300L)
            assertTrue("$denied", denied is ElevatedBinaryResult.Denied)
            denied as ElevatedBinaryResult.Denied
            assertEquals(ShellDeny.QUOTA_EXCEEDED, denied.reason)
            assertTrue("msg=${denied.message}", denied.message.contains("cleanup backlog full"))
            assertEquals(1, rootSpawns.get())
            // Shizuku 側被同一全域計數擋下（證跨 runner 共用），零 spawn。
            val shizukuSpawns = AtomicInteger(0)
            val shizuku = makeShizuku {
                shizukuSpawns.incrementAndGet()
                StallDestroyProcess(latch)
            }
            val deniedS = shizuku.runBinary(ElevatedRequest(listOf("id"), "AA1-SAT"), 300L)
            assertTrue("$deniedS", deniedS is ElevatedBinaryResult.Denied)
            deniedS as ElevatedBinaryResult.Denied
            assertEquals(ShellDeny.QUOTA_EXCEEDED, deniedS.reason)
            assertTrue("msg=${deniedS.message}", deniedS.message.contains("cleanup backlog full"))
            assertEquals(0, shizukuSpawns.get())
        } finally {
            latch.countDown()
            // 釋放預填 6 槽（與 stall 槽併發釋放，poll 等兩者收斂）。
            repeat(6) { ElevatedCleanupTracker.releaseSlot() }
            pollOutstandingTo(0)
            awaitNoElevatedThreads()
        }
        // 恢復：釋放後 outstanding 歸 0 即 spawn 重新放行（雙側各一次正常成功）。
        // 終審殘留：late 完成只釋放 outstanding，不歸零 consecutive；此處以 noteClean
        // 模擬#136-B3介入後重置（否則熔斷閂鎖，fail-closed 本應持續拒絕）。
        // 首尾 noteClean 呼叫屬測試隔離重置，非#136-B3修復（見本檔各用例首尾）。
        pollOutstandingTo(0)
        assertTrue(
            "late completion must not reset breaker",
            ElevatedCleanupTracker.isBreakerOpen(),
        )
        ElevatedCleanupTracker.noteClean()
        assertFalse(ElevatedCleanupTracker.isSaturated())
        val okRoot = makeRoot { FakeProcess("ok".toByteArray()) }
            .runBinary(ElevatedRequest(listOf("id"), "AA1-REC"), 2_000L)
        assertTrue("$okRoot", okRoot is ElevatedBinaryResult.Ok)
        assertFalse((okRoot as ElevatedBinaryResult.Ok).cleanupIncomplete)
        val okShizuku = makeShizuku { FakeProcess("ok".toByteArray()) }
            .runBinary(ElevatedRequest(listOf("id"), "AA1-REC"), 2_000L)
        assertTrue("$okShizuku", okShizuku is ElevatedBinaryResult.Ok)
        assertFalse((okShizuku as ElevatedBinaryResult.Ok).cleanupIncomplete)
        pollOutstandingTo(0)
        awaitNoElevatedThreads()
    }

    private fun doFlagPassthrough(
        makeRunner: ((List<String>) -> Process) -> BinaryElevatedRunner,
    ) {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean() // 測試隔離重置，非#136-B3修復。
        pollOutstandingTo(0)
        // blocked destroy → TimedOut(cleanupIncomplete=true)。
        val latchKill = CountDownLatch(1)
        val runnerKill = makeRunner { StallDestroyProcess(latchKill) }
        try {
            val b = runnerKill.runBinary(ElevatedRequest(listOf("id"), "AA1-FLAG"), 300L)
            assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
            assertTrue(
                "blocked destroy must surface flag",
                (b as ElevatedBinaryResult.TimedOut).cleanupIncomplete,
            )
        } finally {
            latchKill.countDown()
            pollOutstandingTo(0)
        }
        // success＋blocked stdin → Ok(cleanupIncomplete=true)，stdout 完整。
        val latchStdin = CountDownLatch(1)
        val runnerStdin = makeRunner { BlockedStdinSuccessProcess(latchStdin) }
        try {
            val b = runnerStdin.runBinary(ElevatedRequest(listOf("id"), "AA1-FLAG"), 5_000L)
            assertTrue("$b", b is ElevatedBinaryResult.Ok)
            b as ElevatedBinaryResult.Ok
            assertEquals("ok", String(b.stdout, Charsets.UTF_8))
            assertTrue("blocked stdin must surface flag", b.cleanupIncomplete)
        } finally {
            latchStdin.countDown()
            pollOutstandingTo(0)
        }
        // 正常：success → Ok(false)；hang timeout（可回收）→ TimedOut(false)。
        val okNormal = makeRunner { FakeProcess("ok".toByteArray()) }
            .runBinary(ElevatedRequest(listOf("id"), "AA1-FLAG"), 2_000L)
        assertTrue("$okNormal", okNormal is ElevatedBinaryResult.Ok)
        assertFalse((okNormal as ElevatedBinaryResult.Ok).cleanupIncomplete)
        val timeoutNormal = makeRunner { FakeProcess(hang = true) }
            .runBinary(ElevatedRequest(listOf("id"), "AA1-FLAG"), 300L)
        assertTrue("$timeoutNormal", timeoutNormal is ElevatedBinaryResult.TimedOut)
        assertFalse((timeoutNormal as ElevatedBinaryResult.TimedOut).cleanupIncomplete)
        pollOutstandingTo(0)
        awaitNoElevatedThreads()
    }

    @Test fun rootFlagPassthrough() {
        doFlagPassthrough(::makeRoot)
    }

    @Test fun shizukuFlagPassthrough() {
        doFlagPassthrough(::makeShizuku)
    }

    /**
     * 終審殘留 (a)：可恢復序列永不熔斷。
     * 4 次「超時但清理完成」（killer 立即成功，hang 可回收）timeout →
     * 第 5 次仍放行（outstanding 歸零且 consecutive 歸零；良性慢命令不累計）。
     */
    @Test fun recoverableTimeoutNeverTripsBreaker() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean() // 測試隔離重置，非#136-B3修復。
        pollOutstandingTo(0)
        try {
            val spawns = AtomicInteger(0)
            val runner = makeRoot {
                spawns.incrementAndGet()
                FakeProcess(hang = true)
            }
            repeat(4) { i ->
                val b = runner.runBinary(ElevatedRequest(listOf("id"), "AA1-REC"), 300L)
                assertTrue("call ${i + 1}: $b", b is ElevatedBinaryResult.TimedOut)
                assertFalse(
                    "call ${i + 1}: clean killer must not flag",
                    (b as ElevatedBinaryResult.TimedOut).cleanupIncomplete,
                )
                pollOutstandingTo(0)
                assertFalse(ElevatedCleanupTracker.isSaturated())
                assertFalse(
                    "call ${i + 1}: clean cleanup must reset consecutive",
                    ElevatedCleanupTracker.isBreakerOpen(),
                )
            }
            assertEquals(4, spawns.get())
            // 第 5 次仍放行（成功）。
            val ok = makeRoot { FakeProcess("ok".toByteArray()) }
                .runBinary(ElevatedRequest(listOf("id"), "AA1-REC"), 2_000L)
            assertTrue("$ok", ok is ElevatedBinaryResult.Ok)
            pollOutstandingTo(0)
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
        } finally {
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
            awaitNoElevatedThreads()
        }
    }

    /**
     * 終審殘留 (b) 孤立變體：連續不清創熔斷獨立生效。
     * 4 次 uninterruptible stall 後先釋放 latch＋輪詢 outstanding==0
     * （late 完成釋放 slot 但 consecutive 留 4），再發第 5 次 → 若仍 Denied
     * 則為 breaker 生效（outstanding 已 0，非飽和擋；訊息區分兩因）。
     */
    @Test fun breakerOpenBlocksWhenOutstandingZero() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean() // 測試隔離重置，非#136-B3修復。
        pollOutstandingTo(0)
        // P1-2 預留（PR140 Phase1）：逐次累積不可達，改逐次釋放累計 consecutive
        //（每次 outstanding 0→1→0，consecutive 逐次+1；4 次後 breaker 開而飽和關）。
        val spawns = AtomicInteger(0)
        try {
            repeat(4) { i ->
                val latch = CountDownLatch(1)
                val runner = makeRoot {
                    spawns.incrementAndGet()
                    StallDestroyProcess(latch)
                }
                try {
                    val b = runner.runBinary(ElevatedRequest(listOf("id"), "AA1-BRK"), 300L)
                    assertTrue("call ${i + 1}: $b", b is ElevatedBinaryResult.TimedOut)
                    assertTrue(
                        "call ${i + 1}: blocked destroy must flag",
                        (b as ElevatedBinaryResult.TimedOut).cleanupIncomplete,
                    )
                    assertEquals(1, ElevatedCleanupTracker.outstanding())
                } finally {
                    latch.countDown()
                    pollOutstandingTo(0)
                }
            }
            assertFalse(ElevatedCleanupTracker.isSaturated())
            assertTrue(ElevatedCleanupTracker.isBreakerOpen())
            assertEquals(4, spawns.get())
            pollOutstandingTo(0)
            assertFalse(ElevatedCleanupTracker.isSaturated())
            assertTrue(
                "late completion releases slot but must not reset consecutive",
                ElevatedCleanupTracker.isBreakerOpen(),
            )
            // 第 5 次被 breaker 擋（非飽和擋），零 spawn、不耗配額。
            val runner5 = makeRoot { StallDestroyProcess(CountDownLatch(1)) }
            val denied = runner5.runBinary(ElevatedRequest(listOf("id"), "AA1-BRK"), 300L)
            assertTrue("$denied", denied is ElevatedBinaryResult.Denied)
            denied as ElevatedBinaryResult.Denied
            assertEquals(ShellDeny.QUOTA_EXCEEDED, denied.reason)
            assertTrue("msg=${denied.message}", denied.message.contains("breaker open"))
            assertEquals(4, spawns.get())
        } finally {
            pollOutstandingTo(0)
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
        // 重置後放行（防污染）。
        pollOutstandingTo(0)
        assertFalse(ElevatedCleanupTracker.isBreakerOpen())
        val ok = makeRoot { FakeProcess("ok".toByteArray()) }
            .runBinary(ElevatedRequest(listOf("id"), "AA1-BRK-REC"), 2_000L)
        assertTrue("$ok", ok is ElevatedBinaryResult.Ok)
        pollOutstandingTo(0)
        awaitNoElevatedThreads()
    }

    /**
     * 終審殘留 (c)：成功路徑歸零。
     * unclean×2 後一次成功 → 計數器歸零 → 後續 2 次 unclean 不觸熔斷
     * （若未歸零則累計 4 次即開；outstanding 每次即時釋放，孤立 consecutive）。
     */
    @Test fun successResetsConsecutiveCounter() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean() // 測試隔離重置，非#136-B3修復。
        pollOutstandingTo(0)
        try {
            fun doOneStall(tag: String) {
                val latch = CountDownLatch(1)
                val runner = makeRoot { StallDestroyProcess(latch) }
                try {
                    val b = runner.runBinary(ElevatedRequest(listOf("id"), tag), 300L)
                    assertTrue("$tag: $b", b is ElevatedBinaryResult.TimedOut)
                    assertTrue(
                        "$tag: blocked destroy must flag",
                        (b as ElevatedBinaryResult.TimedOut).cleanupIncomplete,
                    )
                } finally {
                    latch.countDown()
                    pollOutstandingTo(0)
                }
            }
            doOneStall("AA1-RST1")
            doOneStall("AA1-RST2")
            assertFalse(ElevatedCleanupTracker.isSaturated())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            // 一次成功即歸零。
            val ok = makeRoot { FakeProcess("ok".toByteArray()) }
                .runBinary(ElevatedRequest(listOf("id"), "AA1-RST-OK"), 2_000L)
            assertTrue("$ok", ok is ElevatedBinaryResult.Ok)
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            // 若成功未歸零，此處累計即 4→熔斷；歸零則為 2→放行。
            doOneStall("AA1-RST3")
            doOneStall("AA1-RST4")
            assertFalse(ElevatedCleanupTracker.isSaturated())
            assertFalse(
                "success must have reset consecutive (else 2+2=4 would trip)",
                ElevatedCleanupTracker.isBreakerOpen(),
            )
            val ok2 = makeRoot { FakeProcess("ok".toByteArray()) }
                .runBinary(ElevatedRequest(listOf("id"), "AA1-RST-OK2"), 2_000L)
            assertTrue("$ok2", ok2 is ElevatedBinaryResult.Ok)
        } finally {
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
            awaitNoElevatedThreads()
        }
        pollOutstandingTo(0)
        assertFalse(ElevatedCleanupTracker.isBreakerOpen())
    }
}
