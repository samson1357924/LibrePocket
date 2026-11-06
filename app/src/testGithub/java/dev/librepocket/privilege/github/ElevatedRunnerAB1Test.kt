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
 * AB1 回歸（owner 新 P1-A＋P1-B；串行契約下預填代理多 stall 疊加，真實多 drain 疊加由 P1Fix 混合測試覆蓋）：
 * 呼叫級併發准入＋殘留 gobbler／stdin 計數＋linger 成功走 noteUnclean。
 * 全 fake（latch 阻塞假進程），零真機、零 su、零 binder。
 *
 * - P1-A：4 次 success-exit＋不可中斷 stdin 阻塞（getter／close 吞 interrupt，
 *   latch 保持）→ 每次 `Ok(cleanupIncomplete=true)`；outstanding 逐次 1→4；
 *   第 5 次 Denied（backlog 或 breaker，斷零 spawn）；finally 釋放＋輪詢歸零＋
 *   手動 noteClean（模擬 B3 remediation）→ 恢復放行。Root（getter 阻塞）＋
 *   Shizuku（close 阻塞）混合疊加，證跨 runner 共用同一全域計數；
 * - P1-B：8 執行緒 barrier，fake spawn 內 `entered` 計數後 latch 等待；
 *   斷言 spawn==4（恰好，另 4 被併發門拒，reason 含 "concurrency full"）；
 *   釋放後 4 個走 timeout＋不可中斷 destroy → outstanding 達上限；後續 denied。
 *   finally 全釋放＋輪詢歸零。
 */
class ElevatedRunnerAB1Test {

    private val privateRoot = "/data/data/files"

    /** success-exit＋stdin getter 吞 interrupt 續等（latch 釋放前永不返回）。 */
    private class GetterLingerSuccessProcess(
        private val latch: CountDownLatch,
    ) : Process() {
        override fun getOutputStream(): OutputStream {
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // 吞 interrupt 續等（模擬不可中斷 Binder stall）。
                }
            }
            return ByteArrayOutputStream()
        }
        override fun getInputStream(): InputStream = ByteArrayInputStream("ok".toByteArray())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }

    /** 吞 interrupt 的阻塞 stdin close（釋放前永不返回）。 */
    private class LingerStdinCloseOutput(private val latch: CountDownLatch) : ByteArrayOutputStream() {
        override fun close() {
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // 吞 interrupt 續等，尾收 grace 無法解開，flag true＋計數。
                }
            }
            super.close()
        }
    }

    /** success-exit＋stdin close 阻塞；stdout 固定 "ok"（完整）。 */
    private class CloseLingerSuccessProcess(
        private val latch: CountDownLatch,
    ) : Process() {
        override fun getOutputStream(): OutputStream = LingerStdinCloseOutput(latch)
        override fun getInputStream(): InputStream = ByteArrayInputStream("ok".toByteArray())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }

    /** 不可中斷 destroy stall（吞 interrupt 續等 latch；waitFor 即時回 destroyed，快 hang）。 */
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

    @Test fun lingeringSuccessCountsAndSharesAcrossRunners() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        assertEquals(0, ElevatedCleanupTracker.activeCalls())
        // P1-2 預留（PR140 Phase1，特權宜序列）：逐次累積 4 已不可達（第 2 次即拒）。
        // 本測以 1 Root linger（0→1）證計數＋跨 runner 共享（次 call Shizuku 即被
        // 預留門拒，零 spawn），再預填 3 達飽和（1+3=4），另補計數使熔斷亦成立。
        val latch = CountDownLatch(1)
        val rootSpawns = AtomicInteger(0)
        val shizukuSpawns = AtomicInteger(0)
        // Root 側 getter 阻塞、Shizuku 側 close 阻塞：兩種 stdin 殘留形態。
        val root = makeRoot { rootSpawns.incrementAndGet(); GetterLingerSuccessProcess(latch) }
        val shizuku = makeShizuku { shizukuSpawns.incrementAndGet(); CloseLingerSuccessProcess(latch) }
        try {
            // 1 次 Root success＋殘留 stdin：Ok(flag true)，outstanding 0→1。
            val b1 = root.runBinary(ElevatedRequest(listOf("id"), "AB1-A"), 5_000L)
            assertTrue("$b1", b1 is ElevatedBinaryResult.Ok)
            b1 as ElevatedBinaryResult.Ok
            assertEquals("ok", String(b1.stdout, Charsets.UTF_8))
            assertTrue("blocked stdin getter must surface flag", b1.cleanupIncomplete)
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            // Shizuku 次 call 即被預留門拒（1+4>4），零 spawn，證跨 runner 共用同一全域。
            val blocked = shizuku.runBinary(ElevatedRequest(listOf("id"), "AB1-A"), 5_000L)
            assertTrue("$blocked", blocked is ElevatedBinaryResult.Denied)
            assertEquals(0, shizukuSpawns.get())
            // 預填 3 達飽和（1+3=4）。
            repeat(3) { ElevatedCleanupTracker.acquireSlot() }
            assertEquals(4, ElevatedCleanupTracker.outstanding())
            assertTrue(ElevatedCleanupTracker.isSaturated())
            // linger 成功走 noteUnclean：此處僅 1 次 drain（consecutive=1，未熔斷）；
            // 補 3 次計數使熔斷亦成立（與飽和同時成立）。
            repeat(3) { ElevatedCleanupTracker.noteUnclean() }
            assertTrue(ElevatedCleanupTracker.isBreakerOpen())
            assertEquals(1, rootSpawns.get())
            assertEquals(0, shizukuSpawns.get())
            // 第 5 次：兩 runner 皆拒（backlog 或 breaker，訊息區分），零新增 spawn。
            val deniedR = root.runBinary(ElevatedRequest(listOf("id"), "AB1-A"), 5_000L)
            assertTrue("$deniedR", deniedR is ElevatedBinaryResult.Denied)
            deniedR as ElevatedBinaryResult.Denied
            assertEquals(ShellDeny.QUOTA_EXCEEDED, deniedR.reason)
            assertTrue(
                "msg=${deniedR.message}",
                deniedR.message.contains("backlog full") || deniedR.message.contains("breaker open"),
            )
            val deniedS = shizuku.runBinary(ElevatedRequest(listOf("id"), "AB1-A"), 5_000L)
            assertTrue("$deniedS", deniedS is ElevatedBinaryResult.Denied)
            deniedS as ElevatedBinaryResult.Denied
            assertEquals(ShellDeny.QUOTA_EXCEEDED, deniedS.reason)
            assertTrue(
                "msg=${deniedS.message}",
                deniedS.message.contains("backlog full") || deniedS.message.contains("breaker open"),
            )
            assertEquals(1, rootSpawns.get())
            assertEquals(0, shizukuSpawns.get())
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            latch.countDown()
            // 釋放預填 3 槽（latch 釋放還 1 linger 槽，併發收斂）。
            repeat(3) { ElevatedCleanupTracker.releaseSlot() }
            pollOutstandingTo(0)
        }
        // late 完成只釋放 slot，不歸零 consecutive（fail-closed 閂鎖，AA1 同語義）。
        assertTrue(
            "late completion must not reset breaker",
            ElevatedCleanupTracker.isBreakerOpen(),
        )
        // 模擬 B3 remediation 後重置 → 恢復放行（雙側各一次正常成功，flag false）。
        ElevatedCleanupTracker.noteClean()
        assertFalse(ElevatedCleanupTracker.isBreakerOpen())
        val okR = makeRoot { FakeProcess("ok".toByteArray()) }
            .runBinary(ElevatedRequest(listOf("id"), "AB1-A-REC"), 2_000L)
        assertTrue("$okR", okR is ElevatedBinaryResult.Ok)
        assertFalse((okR as ElevatedBinaryResult.Ok).cleanupIncomplete)
        val okS = makeShizuku { FakeProcess("ok".toByteArray()) }
            .runBinary(ElevatedRequest(listOf("id"), "AB1-A-REC"), 2_000L)
        assertTrue("$okS", okS is ElevatedBinaryResult.Ok)
        assertFalse((okS as ElevatedBinaryResult.Ok).cleanupIncomplete)
        assertEquals(0, ElevatedCleanupTracker.activeCalls())
        pollOutstandingTo(0)
        awaitNoElevatedThreads()
    }

    @Test fun concurrencyGateAdmitsExactlyOneSerial() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        assertEquals(0, ElevatedCleanupTracker.activeCalls())
        val spawnGate = CountDownLatch(1)
        val destroyLatch = CountDownLatch(1)
        val entered = AtomicInteger(0)
        val spawns = AtomicInteger(0)
        val runner = makeRoot {
            spawns.incrementAndGet()
            entered.incrementAndGet()
            try {
                spawnGate.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            StallDestroyProcess(destroyLatch)
        }
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
                results[i] = runner.runBinary(ElevatedRequest(listOf("id"), "AB1-B"), 300L)
            }.also { it.isDaemon = true; it.start() }
        }
        try {
            start.countDown()
            // P1-2 預留（PR140 Phase1，特權宜序列）：乾淨態僅允單一進行中呼叫
            //（0+1*4<=4 通過，0+2*4<=4 拒絕）；另 7 應被預留門（backlog）拒絕，不進 spawn。
            val deadline = System.currentTimeMillis() + 10_000L
            while (entered.get() < 1) {
                if (System.currentTimeMillis() >= deadline) {
                    throw AssertionError("entered=${entered.get()} spawns=${spawns.get()}")
                }
                Thread.sleep(20L)
            }
            Thread.sleep(500L)
            assertEquals(1, spawns.get())
            // 釋放 spawn 門 → 1 個走 timeout＋不可中斷 destroy → outstanding=1。
            spawnGate.countDown()
            threads.forEach { it.join(30_000L) }
            assertTrue("all calls must return bounded", threads.none { it.isAlive })
            val timedOut = results.filterIsInstance<ElevatedBinaryResult.TimedOut>()
            assertEquals(1, timedOut.size)
            assertTrue(timedOut.all { it.cleanupIncomplete })
            val denied = results.filterIsInstance<ElevatedBinaryResult.Denied>()
            assertEquals(7, denied.size)
            assertTrue(denied.all { it.reason == ShellDeny.QUOTA_EXCEEDED })
            assertTrue(
                "denied msgs=${denied.map { it.message }}",
                denied.all { it.message.contains("backlog full") },
            )
            pollOutstandingTo(1)
            assertFalse(ElevatedCleanupTracker.isSaturated())
            // 後續呼叫仍被預留門拒（outstanding=1，1+4>4），零新增 spawn。
            val extra = runner.runBinary(ElevatedRequest(listOf("id"), "AB1-B"), 300L)
            assertTrue("$extra", extra is ElevatedBinaryResult.Denied)
            extra as ElevatedBinaryResult.Denied
            assertEquals(ShellDeny.QUOTA_EXCEEDED, extra.reason)
            assertTrue(
                "msg=${extra.message}",
                extra.message.contains("backlog full"),
            )
            assertEquals(1, spawns.get())
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            spawnGate.countDown()
            destroyLatch.countDown()
            threads.forEach { it.join(30_000L) }
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
        // 重置後放行（防污染）。
        assertFalse(ElevatedCleanupTracker.isBreakerOpen())
        val ok = makeRoot { FakeProcess("ok".toByteArray()) }
            .runBinary(ElevatedRequest(listOf("id"), "AB1-B-REC"), 2_000L)
        assertTrue("$ok", ok is ElevatedBinaryResult.Ok)
        assertFalse((ok as ElevatedBinaryResult.Ok).cleanupIncomplete)
        pollOutstandingTo(0)
        awaitNoElevatedThreads()
    }
}
