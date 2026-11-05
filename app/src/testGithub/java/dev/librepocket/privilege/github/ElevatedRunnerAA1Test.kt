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
 * AA1 回歸（owner 新 P1＋P2；PocketGuard 無動作項）：
 * 跨呼叫清理監督＋旗標穿透。全 fake（latch／空流假進程），零真機、零 su、零 binder。
 *
 * - 飽和門：連續 4 次不可中斷 destroy stall（吞 interrupt、不釋放直到 finally）timeout
 *   後，第 5 次 spawn 即 `Denied(QUOTA_EXCEEDED)` 零 spawn（Root 側；另測 Shizuku 側
 *   被同一全域計數擋下，證跨 runner 共用）；finally 釋放全部 latch＋輪詢 outstanding
 *   回 0（防污染）。每次 stall 耗時約 cleanup budget 下界（500ms join＋500ms grace
 *   ＋300ms poll），4 次約數秒；
 * - 恢復：釋放後 outstanding 歸 0 即 spawn 重新放行；
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
        pollOutstandingTo(0)
        val latch = CountDownLatch(1)
        val rootSpawns = AtomicInteger(0)
        val root = makeRoot {
            rootSpawns.incrementAndGet()
            StallDestroyProcess(latch)
        }
        try {
            // 4 次不可中斷 stall timeout：每次有界 TimedOut＋flag true，殘留計數遞增。
            repeat(4) { i ->
                val b = root.runBinary(ElevatedRequest(listOf("id"), "AA1-SAT"), 300L)
                assertTrue("call ${i + 1}: $b", b is ElevatedBinaryResult.TimedOut)
                assertTrue(
                    "call ${i + 1}: cleanupIncomplete expected",
                    (b as ElevatedBinaryResult.TimedOut).cleanupIncomplete,
                )
                assertEquals(i + 1, ElevatedCleanupTracker.outstanding())
            }
            assertTrue(ElevatedCleanupTracker.isSaturated())
            assertEquals(4, rootSpawns.get())
            // 第 5 次 Root spawn 即拒：零 spawn（計數不變）。
            val denied = root.runBinary(ElevatedRequest(listOf("id"), "AA1-SAT"), 300L)
            assertTrue("$denied", denied is ElevatedBinaryResult.Denied)
            denied as ElevatedBinaryResult.Denied
            assertEquals(ShellDeny.QUOTA_EXCEEDED, denied.reason)
            assertTrue("msg=${denied.message}", denied.message.contains("cleanup backlog full"))
            assertEquals(4, rootSpawns.get())
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
            pollOutstandingTo(0)
            awaitNoElevatedThreads()
        }
        // 恢復：釋放後 outstanding 歸 0 即 spawn 重新放行（雙側各一次正常成功）。
        pollOutstandingTo(0)
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
}
