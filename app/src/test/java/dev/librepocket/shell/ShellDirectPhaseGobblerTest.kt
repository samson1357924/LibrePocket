package dev.librepocket.shell

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Round-5 Stage 3 pins（P2-2 gobbler cap：[GobblerAdmission]＋`shell-gobbler-<seq>`＋
 * 共享 tail budget＋parent-only kill 誠實化，direct 通道 [DefaultProcessRunner]）。
 *
 * - T1：父 exit0 但 fork-but-no-exec 子孫握 pipe 寫端 → [DefaultProcessRunner.run]
 *   直調回 `exitCode==0`＋部分位元組＋`outputIncomplete==true`（禁偽造完整；
 *   收斂發生於 holder 被殺後）。缺 `/bin/sh`＋python3 即 skip。
 * - T2：多輪（K=6，每輪 2 gobbler＞8 上界）順序准入；全程 `shell-gobbler-*` 存活
 *   與 [GobblerAdmission.peak] 恆 ≤[GobblerAdmission.MAX_GOBBLERS]；holder 全殺後
 *   收斂至基線、`current()==0`（permit 持至 thread 實際退出之證明）。
 * - T3：[GobblerAdmission] 耗盡 → `run` 飽和即 [RawOutput.readFailed]、零 thread
 *   啟動（fail-closed）；permit 全數歸還。
 * - T4：飽和經 `execute` 層映 `Failed`（既有 P2-2 `readFailed→Failed` 映射），永不 Ok。
 *
 * 全 hermetic：holder 睡眠上限 120s 但每測 finally `kill -9` 全數 holder＋等 gobbler
 * 歸零，避免 daemon／permit 污染後測（leak 斷言用全域計數，故清理必須完整）。
 */
class ShellDirectPhaseGobblerTest {

    @Before fun resetPeak() {
        GobblerAdmission.resetPeak()
    }

    private fun hasSh(): Boolean = File("/bin/sh").canExecute()

    private fun hasPython3(): Boolean = runCatching {
        ProcessBuilder(listOf("python3", "--version"))
            .redirectErrorStream(true)
            .start().let { p ->
                try {
                    p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
                } finally {
                    runCatching { p.destroyForcibly() }
                }
            }
    }.getOrDefault(false)

    private fun gobblerThreadCount(): Int =
        Thread.getAllStackTraces().keys.count { it.name.startsWith("shell-gobbler-") && it.isAlive }

    private fun awaitGobblersAtMost(baseline: Int, timeoutMs: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (gobblerThreadCount() <= baseline) return
            Thread.sleep(50)
        }
        throw AssertionError(
            "shell-gobbler-* threads did not converge to baseline=$baseline: ${gobblerThreadCount()}",
        )
    }

    private fun killPids(pids: List<Long>) {
        if (pids.isEmpty()) return
        runCatching {
            ProcessBuilder(listOf("/bin/sh", "-c", "kill -9 " + pids.joinToString(" ")))
                .start().waitFor(10, TimeUnit.SECONDS)
        }
    }

    /**
     * 父寫部分輸出後 fork-but-no-exec：父即 `os._exit(0)`（exit 0），子握雙流寫端
     * 睡眠 [holdSecs]（EOF 延遲源）；父另把 holder pid 寫 stderr（`HOLDER=<pid>`，
     * 測試回收用，不影響 `*-OUT`/`*-ERR` contains 斷言）。
     */
    private fun holderArgv(marker: String, holdSecs: Int): List<String> {
        val code = """
            import os, sys, time
            sys.stdout.write("$marker-OUT")
            sys.stdout.flush()
            sys.stderr.write("$marker-ERR")
            sys.stderr.flush()
            pid = os.fork()
            if pid == 0:
                time.sleep($holdSecs)
                os._exit(0)
            sys.stderr.write("HOLDER=%d\n" % pid)
            sys.stderr.flush()
            os._exit(0)
        """.trimIndent()
        return listOf("python3", "-c", code)
    }

    private fun holderPidsOf(stderr: ByteArray): List<Long> =
        Regex("HOLDER=(\\d+)").findAll(stderr.toString(Charsets.UTF_8))
            .map { it.groupValues[1].toLong() }.toList()

    // ---- T1：parent-exit0-no-EOF → exit 0＋部分輸出＋outputIncomplete ----

    @Test fun parentExitZeroWithoutEofReturnsPartialIncomplete() {
        assumeTrue("need /bin/sh + python3", hasSh() && hasPython3())
        assertEquals(0, GobblerAdmission.current())
        val baseline = gobblerThreadCount()
        val runner = DefaultProcessRunner()
        val holders = mutableListOf<Long>()
        try {
            val raw = runner.run(holderArgv("GOBT1", 60), 30_000L, null)
            holders += holderPidsOf(raw.stderr)
            assertEquals(0, raw.exitCode)
            assertFalse(raw.timedOut)
            assertFalse(raw.readFailed)
            assertTrue("fork-holder must delay EOF past join budget: $raw", raw.outputIncomplete)
            assertTrue(raw.stdout.toString(Charsets.UTF_8).contains("GOBT1-OUT"))
            assertTrue(raw.stderr.toString(Charsets.UTF_8).contains("GOBT1-ERR"))
            assertTrue("holder pid must be reported for cleanup", holders.isNotEmpty())
            assertTrue(
                "peak must stay bounded, peak=${GobblerAdmission.peak()}",
                GobblerAdmission.peak() <= GobblerAdmission.MAX_GOBBLERS,
            )
        } finally {
            killPids(holders)
            awaitGobblersAtMost(baseline, 30_000L)
            assertEquals(0, GobblerAdmission.current())
        }
    }

    // ---- T2：多輪洩漏有界＋holder 死後收斂 ----

    @Test fun multiRoundLeakStaysBoundedAndConvergesAfterKill() {
        assumeTrue("need /bin/sh + python3", hasSh() && hasPython3())
        assertEquals(0, GobblerAdmission.current())
        val baseline = gobblerThreadCount()
        val runner = DefaultProcessRunner()
        val holders = mutableListOf<Long>()
        val rounds = 6
        try {
            var incomplete = 0
            var readFailed = 0
            repeat(rounds) { i ->
                val raw = runner.run(holderArgv("GOBT2R$i", 120), 30_000L, null)
                holders += holderPidsOf(raw.stderr)
                if (raw.outputIncomplete) incomplete++
                if (raw.readFailed) readFailed++
                assertTrue("round $i must not look complete: $raw", raw.outputIncomplete || raw.readFailed)
                assertFalse(raw.timedOut)
                assertTrue(
                    "live shell-gobbler-* must stay bounded at round $i",
                    gobblerThreadCount() - baseline <= GobblerAdmission.MAX_GOBBLERS,
                )
                assertTrue(
                    "peak must stay bounded at round $i, peak=${GobblerAdmission.peak()}",
                    GobblerAdmission.peak() <= GobblerAdmission.MAX_GOBBLERS,
                )
            }
            assertTrue("early rounds must show EOF-delayed incomplete, incomplete=$incomplete", incomplete >= 1)
            // 6 輪 ×2 gobbler＝12＞8：後輪必飽和 fail-closed（readFailed，無新 thread）。
            assertTrue("late rounds must saturate to readFailed, readFailed=$readFailed", readFailed >= 1)
            assertEquals(rounds, incomplete + readFailed)
        } finally {
            killPids(holders)
            awaitGobblersAtMost(baseline, 60_000L)
            assertEquals(0, GobblerAdmission.current())
        }
    }

    // ---- T3：飽和 → readFailed、零 thread 啟動 ----

    @Test fun saturationFailsClosedWithoutStartingThread() {
        assumeTrue("/bin/true missing, skipping", File("/bin/true").canExecute())
        assertEquals(0, GobblerAdmission.current())
        val baseline = gobblerThreadCount()
        var held = 0
        repeat(GobblerAdmission.MAX_GOBBLERS) {
            assertTrue("admission must have room before saturation test", GobblerAdmission.tryEnter())
            held++
        }
        try {
            assertEquals(0, GobblerAdmission.availablePermits())
            val runner = DefaultProcessRunner()
            val raw = runner.run(listOf("/bin/true"), 10_000L, null)
            assertTrue("saturated gobbler start must report readFailed: $raw", raw.readFailed)
            assertFalse(raw.timedOut)
            assertEquals(
                "saturation must not start any gobbler thread",
                baseline,
                gobblerThreadCount(),
            )
        } finally {
            repeat(held) { GobblerAdmission.release() }
        }
        assertEquals(GobblerAdmission.MAX_GOBBLERS, GobblerAdmission.availablePermits())
        assertEquals(0, GobblerAdmission.current())
    }

    // ---- T4：飽和經 execute 層映 Failed（永不 Ok） ----

    @Test fun saturationExecuteMapsToFailed() {
        assumeTrue("/bin/true missing, skipping", File("/bin/true").canExecute())
        assertEquals(0, GobblerAdmission.current())
        val shell = RestrictedShell(
            runner = DefaultProcessRunner(),
            privateRoot = null,
            execResolver = { ShellExecutables.ResolvedExec("/bin/true", null) },
        )
        repeat(GobblerAdmission.MAX_GOBBLERS) {
            assertTrue("admission must have room before saturation test", GobblerAdmission.tryEnter())
        }
        try {
            val r = shell.execute(listOf("echo", "hi"), timeoutMs = 10_000L)
            assertTrue("saturated gobblers must fail closed, got $r", r is ShellResult.Failed)
        } finally {
            repeat(GobblerAdmission.MAX_GOBBLERS) { GobblerAdmission.release() }
        }
        assertEquals(0, GobblerAdmission.current())
    }

    // ---- T5：零 budget 讀跳過 join(0)（join(0)==無限等待），立即快照 joinIncomplete ----

    @Test(timeout = 10_000L) fun zeroBudgetReadSkipsJoinWithoutHanging() {
        val pipeIn = java.io.PipedInputStream()
        val pipeOut = java.io.PipedOutputStream(pipeIn)
        val gobbler = DefaultProcessRunner.Gobbler(pipeIn)
        val t = Thread(gobbler, "shell-gobbler-test-join0").apply { isDaemon = true; start() }
        gobbler.thread = t
        try {
            val start = System.nanoTime()
            val snap = gobbler.read(0L)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000L
            assertTrue("read(0) must snapshot live reader, got $snap", snap.joinIncomplete)
            assertTrue("read(0) must not join(0)-hang, took ${elapsedMs}ms", elapsedMs < 2_000L)
        } finally {
            runCatching { pipeOut.close() }; runCatching { pipeIn.close() }
            t.join(5_000L)
        }
    }
}
