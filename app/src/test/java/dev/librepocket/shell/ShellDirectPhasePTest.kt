package dev.librepocket.shell

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Round-5 Phase P pins（P1-2 跨呼叫准入＋P2-3 受監督 process 段，direct 通道）。
 *
 * - P1-2：freeze／gate／reverify／process 共用 [DirectAdmission]（N=3 原子
 *   reservation，worker `finally` 才釋放；飽和零 thread 即時 fail-closed）。
 *   24 barrier 混合阻塞（freeze 自訂 iterator＋gate 自訂 resolver，皆吞中斷
 *   不可中斷）→ `direct-*` 真實峰值 ≤3、飽和拒絕（`TimedOut`）不耗 quota、
 *   零 spawn，釋 latch 後全歸零（idle 零 thread，非池化常駐）。
 * - P2-3：`runner.run` 全包（spawn＋wait＋kill＋gobbler joins）跑同一 deadline
 *   監督 worker（`direct-process`）；注入阻塞／慢 tail 只滯 worker，caller wall
 *   恆 ≤`timeoutMs`＋小常數（grace＋poll＋調度）；真 `/bin/sleep` 超短 timeout
 *   即 `TimedOut` 且子程序被殺（kill＋reap 斷言）。
 * - `timeoutMs` 整體上限語義（Tool API 不變，直傳）：本檔 wall 斷言即背書。
 *
 * 全 JVM（零真機，`/bin/sleep` 缺失即 skip 對應項）：假 runner 阻塞皆吞中斷；
 * 每測 finally 釋 latch＋等 `direct-*` 歸零，避免全域 permits 洩漏污染後測。
 */
class ShellDirectPhasePTest {

    private class CapRunner : ProcessRunner {
        val calls = AtomicInteger(0)
        @Volatile var lastTimeoutMs: Long = -1L
        override fun run(argv: List<String>, timeoutMs: Long, env: Map<String, String>?): RawOutput {
            calls.incrementAndGet()
            lastTimeoutMs = timeoutMs
            return RawOutput("ok".toByteArray(), ByteArray(0), 0, false)
        }
    }

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        while (true) {
            try {
                latch.await()
                return
            } catch (_: InterruptedException) {
                // 吞中斷、繼續等：模擬不可中斷阻塞（FUSE／壞 FS／吞中斷 resolver）。
            }
        }
    }

    private fun blockingFreezeArgv(latch: CountDownLatch): List<String> =
        object : AbstractList<String>() {
            override val size: Int get() = 2
            override fun get(index: Int): String = if (index == 0) "echo" else "hi"
            override fun iterator(): Iterator<String> {
                awaitUninterruptibly(latch)
                return listOf("echo", "hi").iterator()
            }
        }

    private fun directThreadCount(): Int =
        Thread.getAllStackTraces().keys.count { it.name.startsWith("direct-") }

    private fun awaitNoDirectThreads(timeoutMs: Long = 10_000L) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (directThreadCount() == 0) return
            Thread.sleep(20)
        }
        throw AssertionError("direct-* threads still alive: ${directThreadCount()}")
    }

    // ---- P1-2：24 barrier 混合阻塞 → 峰值有界、拒絕不耗 quota、不 spawn、釋後歸零 ----

    @Test fun crossCallAdmissionBoundedPeakNoQuotaNoSpawnThenZero() {
        val n = 24
        val blockLatch = CountDownLatch(1)
        val startLatch = CountDownLatch(1)
        val runner = CapRunner()
        val quota = ShellQuota(maxCalls = 10, windowMs = 60_000L)
        val shell = RestrictedShell(
            quota = quota,
            runner = runner,
            privateRoot = null,
            execResolver = {
                awaitUninterruptibly(blockLatch)
                ShellExecutables.ResolvedExec("/trusted/$it", null)
            },
        )
        val results = arrayOfNulls<ShellResult>(n)
        val threads = (0 until n).map { i ->
            val argv: List<String> =
                if (i % 2 == 0) blockingFreezeArgv(blockLatch) else listOf("echo", "hi")
            Thread({
                try {
                    startLatch.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
                results[i] = try {
                    shell.execute(argv, timeoutMs = 15_000L)
                } catch (t: Throwable) {
                    ShellResult.Failed("caller threw ${t.javaClass.simpleName}")
                }
            }, "phaseP-caller-$i").apply { isDaemon = true }
        }
        try {
            threads.forEach { it.start() }
            Thread.sleep(200) // 確保 24 caller 皆已就緒於 startLatch。
            startLatch.countDown()
            // 峰值採樣：holders 阻塞於 latch（15s budget 內不醒），飽和者已即時回。
            var peak = 0
            repeat(75) {
                Thread.sleep(20)
                peak = maxOf(peak, directThreadCount())
            }
            assertTrue("direct-* peak must be bounded, peak=$peak", peak <= DirectAdmission.MAX_WORKERS)
            assertTrue("expected holders blocked, peak=$peak", peak >= 1)
            assertEquals("peak must saturate the pool", DirectAdmission.MAX_WORKERS, peak)
            // 此刻：holders（3）仍阻塞未 spawn；飽和者（21）已 TimedOut。
            assertEquals("no spawn while holders blocked, calls=${runner.calls.get()}", 0, runner.calls.get())
            var timedOut = 0
            var pending = 0
            for (r in results) {
                if (r == null) pending++ else if (r is ShellResult.TimedOut) timedOut++
            }
            assertEquals("holders still blocked", DirectAdmission.MAX_WORKERS, pending)
            assertEquals("rest must be saturated TimedOut", n - DirectAdmission.MAX_WORKERS, timedOut)
            // 釋放：holders 完成 Ok，總 spawn 恰為池容量。
            blockLatch.countDown()
            threads.forEach { it.join(15_000L) }
            assertTrue("all callers must finish", threads.none { it.isAlive })
            var ok = 0
            var expired = 0
            for (r in results) {
                when (r) {
                    is ShellResult.Ok -> ok++
                    is ShellResult.TimedOut -> expired++
                    else -> throw AssertionError("unexpected $r")
                }
            }
            assertEquals(DirectAdmission.MAX_WORKERS, ok)
            assertEquals(n - DirectAdmission.MAX_WORKERS, expired)
            assertEquals(DirectAdmission.MAX_WORKERS, runner.calls.get())
            // 飽和拒絕不耗 quota：holders 僅耗 3（上限 10），後續探測仍放行。
            val probe = shell.execute(listOf("echo", "hi"), timeoutMs = 10_000L)
            assertTrue("saturated rejections must not consume quota, got $probe", probe is ShellResult.Ok)
            assertEquals(DirectAdmission.MAX_WORKERS + 1, runner.calls.get())
        } finally {
            blockLatch.countDown()
            threads.forEach { runCatching { it.join(5_000L) } }
            awaitNoDirectThreads()
        }
    }

    // ---- P2-3：注入阻塞 runner → caller wall 有界 TimedOut（空 partial） ----

    @Test fun processStageBlockingRunnerWallBounded() {
        val latch = CountDownLatch(1)
        val runner = object : ProcessRunner {
            override fun run(argv: List<String>, timeoutMs: Long, env: Map<String, String>?): RawOutput {
                awaitUninterruptibly(latch)
                return RawOutput("late".toByteArray(), ByteArray(0), 0, false)
            }
        }
        val shell = RestrictedShell(
            runner = runner,
            privateRoot = null,
            execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
        )
        try {
            val start = System.nanoTime()
            val r = shell.execute(listOf("echo", "hi"), timeoutMs = 200L)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue("blocking runner must be supervised to TimedOut, got $r", r is ShellResult.TimedOut)
            assertTrue(
                "wall must be bounded (timeoutMs + small const), elapsed=$elapsedMs",
                elapsedMs < 200L + 5_000L,
            )
        } finally {
            latch.countDown()
            awaitNoDirectThreads()
        }
    }

    // ---- P2-3：慢 tail（latch 後回 outputIncomplete）→ caller 先行 TimedOut，禁完整 Ok ----

    @Test fun processStageSlowTailWallBounded() {
        val latch = CountDownLatch(1)
        val runner = object : ProcessRunner {
            override fun run(argv: List<String>, timeoutMs: Long, env: Map<String, String>?): RawOutput {
                awaitUninterruptibly(latch)
                // 慢 EOF：即使最終 would-be Ok，caller 早已按 deadline 回 TimedOut。
                return RawOutput("part".toByteArray(), ByteArray(0), 0, false, outputIncomplete = true)
            }
        }
        val shell = RestrictedShell(
            runner = runner,
            privateRoot = null,
            execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
        )
        try {
            val start = System.nanoTime()
            val r = shell.execute(listOf("echo", "hi"), timeoutMs = 200L)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue("slow tail must not become complete Ok, got $r", r is ShellResult.TimedOut)
            assertTrue("wall bounded, elapsed=$elapsedMs", elapsedMs < 200L + 5_000L)
        } finally {
            latch.countDown()
            awaitNoDirectThreads()
        }
    }

    // ---- P2-3：真 sleep＋超短 timeout → wall 有界 TimedOut＋子程序被殺 ----

    @Test fun processStageRealSleepKilledAndBounded() {
        assumeTrue("/bin/sleep missing, skipping", File("/bin/sleep").canExecute())
        var child: Process? = null
        val runner = DefaultProcessRunner()
        runner.processObserver = { child = it }
        // Stage 5 P3-4：同 Linger T1b，真系統二進位須配允許集＋不可寫模擬。
        val realSleep = File("/bin/sleep").canonicalPath
        val sleepRoot = File(realSleep).parentFile.canonicalPath
        val shell = RestrictedShell(
            runner = runner,
            privateRoot = null,
            execAllowedRoots = listOf(sleepRoot),
            execIsWritable = { _ -> false },
            execResolver = { ShellExecutables.ResolvedExec(realSleep, null) },
        )
        try {
            val start = System.nanoTime()
            val r = shell.execute(listOf("sleep", "30"), timeoutMs = 300L)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue("real sleep over budget must be TimedOut, got $r", r is ShellResult.TimedOut)
            assertTrue("wall bounded, elapsed=$elapsedMs", elapsedMs < 300L + 8_000L)
            val c = child
            assertTrue("runner must have spawned before kill", c != null)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) {
                if (!runCatching { c!!.isAlive }.getOrDefault(true)) break
                Thread.sleep(20)
            }
            assertTrue("child must be reaped after TimedOut", runCatching { c!!.isAlive }.getOrDefault(true).not())
        } finally {
            runCatching { child?.destroyForcibly() }
            runCatching { child?.waitFor(5, TimeUnit.SECONDS) }
            awaitNoDirectThreads()
        }
    }
}
