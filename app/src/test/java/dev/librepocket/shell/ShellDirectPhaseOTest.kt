package dev.librepocket.shell

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Round-5 Phase O pins（P1-1 owner try/finally＋P2-1 drain-and-discard＋P2-2 gobbler 完整性，
 * direct 通道 [DefaultProcessRunner]/[Gobbler]/[RawOutput]）。
 *
 * - P1-1：`pb.start()` 成功後全路徑 owner 清理（關流＋監督 kill+reap＋中斷恢復）：
 *   真 [DefaultProcessRunner]＋真 JVM 子程序（絕對路徑 `/bin/sleep`，缺失即 skip），
 *   PID／[ProcessHandle] 經 [DefaultProcessRunner.processObserver] 可觀測；
 *   `waitFor` 中斷／stdin 關閉抛／gobbler 啟動抛 → 子程序不存活且不回健康 Ok（抛，不回
 *   `RawOutput`；`execute` 層映 `Failed`，中斷另恢復旗標）。
 * - P2-1：到 cap 後 drain-and-discard 至 EOF（真大輸出 `cat`／`dd`，exit 0＋
 *   `truncated=true`，無 EPIPE/SIGPIPE 改寫退出碼）。
 * - P2-2：[Gobbler.finished]/[Gobbler.failed]/[Gobbler.truncated] 單調旗＋有界 join
 *   `joinIncomplete`；`read` 超時或抛必帶不完整（[RawOutput.outputIncomplete]／
 *   [RawOutput.readFailed]），`execute` 側禁回完整 Ok（`readFailed`→`Failed`，
 *   `outputIncomplete`→`TimedOut` 攜 partial）。
 *
 * 全 hermetic：每測 finally `destroyForcibly` 所捕獲子程序；gobbler 殘留皆為 daemon。
 */
class ShellDirectPhaseOTest {

    private fun sleepBin(): String {
        assumeTrue("/bin/sleep missing, skipping", File("/bin/sleep").canExecute())
        return "/bin/sleep"
    }

    private fun catBin(): String {
        assumeTrue("/bin/cat missing, skipping", File("/bin/cat").canExecute())
        return "/bin/cat"
    }

    private fun ddBin(): String {
        assumeTrue("/bin/dd missing, skipping", File("/bin/dd").canExecute())
        return "/bin/dd"
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().canonicalFile

    private fun killQuietly(p: Process?) {
        if (p == null) return
        runCatching { p.destroyForcibly() }
        runCatching { p.waitFor(5, TimeUnit.SECONDS) }
    }

    private fun awaitAlive(p: Process, timeoutMs: Long = 10_000L) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (runCatching { p.isAlive }.getOrDefault(false)) return
            Thread.sleep(10)
        }
        fail("process never became alive: $p")
    }

    private fun awaitDead(p: Process, timeoutMs: Long = 10_000L) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (!runCatching { p.isAlive }.getOrDefault(true)) return
            Thread.sleep(10)
        }
        fail("process still alive after bounded cleanup: $p")
    }

    // ---- P1-1：waitFor 中斷 → kill+reap＋重抛，不回 Ok ----

    @Test fun waitForInterruptedKillsAndThrows() {
        val sleep = sleepBin()
        val captured = AtomicReference<Process?>(null)
        val runner = DefaultProcessRunner().apply {
            processObserver = { captured.set(it) }
        }
        val result = AtomicReference<RawOutput?>(null)
        val error = AtomicReference<Throwable?>(null)
        val t = Thread({
            try {
                result.set(runner.run(listOf(sleep, "30"), 15_000L, null))
            } catch (th: Throwable) {
                error.set(th)
            }
        }, "phaseO-interrupt-victim")
        t.isDaemon = true
        var child: Process? = null
        try {
            t.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline && captured.get() == null) {
                Thread.sleep(10)
            }
            child = captured.get()
            assertTrue("runner must have started the child before interrupt", child != null)
            awaitAlive(child!!)
            t.interrupt()
            t.join(25_000L)
            assertFalse("runner thread must not hang after interrupt", t.isAlive)
            val th = error.get()
            assertTrue("interrupted run must throw, not return: result=${result.get()}", th != null)
            assertTrue("expected InterruptedException, got $th", th is InterruptedException)
            assertTrue("must not return healthy output", result.get() == null)
            awaitDead(child!!)
        } finally {
            killQuietly(child ?: captured.get())
            runCatching { t.interrupt() }
            t.join(5_000L)
        }
    }

    // ---- P1-1：stdin 關閉抛 → kill，不回 Ok ----

    @Test fun stdinCloseFailureKillsAndThrows() {
        val sleep = sleepBin()
        val captured = AtomicReference<Process?>(null)
        val runner = DefaultProcessRunner().apply {
            processObserver = { captured.set(it) }
            failStdinClose = true
        }
        var child: Process? = null
        try {
            try {
                runner.run(listOf(sleep, "30"), 10_000L, null)
                fail("stdin-close failure must throw, not return healthy Ok")
            } catch (_: IOException) {
                // 預期：注入的 stdin 失敗。
            }
            child = captured.get()
            assertTrue("process must have been started before stdin failure", child != null)
            awaitDead(child!!)
        } finally {
            killQuietly(child ?: captured.get())
        }
    }

    // ---- P1-1：gobbler 啟動抛 → kill，不回 Ok ----

    @Test fun gobblerStartFailureKillsAndThrows() {
        val sleep = sleepBin()
        val captured = AtomicReference<Process?>(null)
        val runner = DefaultProcessRunner().apply {
            processObserver = { captured.set(it) }
            failGobblerStart = true
        }
        var child: Process? = null
        try {
            try {
                runner.run(listOf(sleep, "30"), 10_000L, null)
                fail("gobbler-start failure must throw, not return healthy Ok")
            } catch (_: IllegalStateException) {
                // 預期：注入的 gobbler 啟動失敗。
            }
            child = captured.get()
            assertTrue("process must have been started before gobbler failure", child != null)
            awaitDead(child!!)
        } finally {
            killQuietly(child ?: captured.get())
        }
    }

    // ---- P2-1：大 stdout drain-and-discard，exit 0＋溢出（run 層） ----

    @Test fun largeStdoutDrainsWithExitZeroAndOverflow() {
        val cat = catBin()
        val scope = tempDir("phaseO-bigout")
        var child: Process? = null
        try {
            val big = File(scope, "big.bin").apply { writeBytes(ByteArray(700 * 1024) { 0x41 }) }
            val captured = AtomicReference<Process?>(null)
            val runner = DefaultProcessRunner().apply {
                processObserver = { captured.set(it) }
            }
            val raw = runner.run(listOf(cat, big.absolutePath), 30_000L, null)
            child = captured.get()
            assertEquals(0, raw.exitCode)
            assertFalse(raw.timedOut)
            assertFalse(raw.readFailed)
            assertFalse(raw.outputIncomplete)
            assertTrue("700K stdout must mark hard-cap overflow", raw.overflowed)
            assertEquals(ShellPolicy.HARD_READ_CAP_BYTES, raw.stdout.size)
            assertTrue(raw.stderr.isEmpty())
            assertTrue(raw.stdout.contentEquals(ByteArray(ShellPolicy.HARD_READ_CAP_BYTES) { 0x41 }))
        } finally {
            killQuietly(child)
            scope.deleteRecursively()
        }
    }

    // ---- P2-1：大 stderr drain-and-discard，exit 0＋溢出（run 層） ----

    @Test fun largeStderrDrainsWithExitZeroAndOverflow() {
        val dd = ddBin()
        val scope = tempDir("phaseO-bigerr")
        var child: Process? = null
        try {
            val big = File(scope, "big.bin").apply { writeBytes(ByteArray(700 * 1024) { 0x42 }) }
            val captured = AtomicReference<Process?>(null)
            val runner = DefaultProcessRunner().apply {
                processObserver = { captured.set(it) }
            }
            val raw = runner.run(
                listOf(dd, "if=${big.absolutePath}", "of=/dev/stderr", "bs=65536"),
                30_000L,
                null,
            )
            child = captured.get()
            assertEquals(0, raw.exitCode)
            assertFalse(raw.timedOut)
            assertFalse(raw.readFailed)
            assertFalse(raw.outputIncomplete)
            assertTrue("700K stderr must mark hard-cap overflow", raw.overflowed)
            assertEquals(ShellPolicy.HARD_READ_CAP_BYTES, raw.stderr.size)
            assertTrue(raw.stderr.copyOf(1024).contentEquals(ByteArray(1024) { 0x42 }))
        } finally {
            killQuietly(child)
            scope.deleteRecursively()
        }
    }

    // ---- P2-1：大輸出 execute 層 Ok(exit 0, truncated=true)，無 SIGPIPE ----

    @Test fun largeStdoutExecuteOkTruncated() {
        catBin()
        val scope = tempDir("phaseO-exec-big")
        try {
            val big = File(scope, "big.bin").apply { writeBytes(ByteArray(700 * 1024) { 0x43 }) }
            val shell = RestrictedShell(
                runner = DefaultProcessRunner(),
                privateRoot = scope.absolutePath,
                execResolver = { ShellExecutables.ResolvedExec("/bin/cat", null) },
            )
            val r = shell.execute(listOf("cat", big.absolutePath), timeoutMs = 30_000L)
            assertTrue("$r", r is ShellResult.Ok)
            r as ShellResult.Ok
            assertEquals(0, r.exitCode)
            assertTrue("large output must be honestly marked truncated", r.truncated)
            assertEquals(ShellPolicy.MAX_OUTPUT_BYTES, r.stdout.toByteArray(Charsets.UTF_8).size)
        } finally {
            scope.deleteRecursively()
        }
    }

    // ---- P2-2：gobbler 單調旗（小流／抛／超 cap） ----

    @Test fun gobblerFlagsFinishedFailedTruncated() {
        // 小流：真 EOF 即 finished，無 failed／truncated。
        run {
            val g = DefaultProcessRunner.Gobbler(ByteArrayInputStream("hi".toByteArray()))
            g.run()
            val r = g.read()
            assertTrue(r.finished)
            assertFalse(r.failed)
            assertFalse(r.truncated)
            assertFalse(r.joinIncomplete)
            assertTrue(r.bytes.contentEquals("hi".toByteArray()))
        }
        // 抛：read 抛即 failed，不 finished。
        run {
            val throwing = object : InputStream() {
                override fun read(): Int = throw IOException("boom")
                override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("boom")
            }
            val g = DefaultProcessRunner.Gobbler(throwing)
            g.run()
            val r = g.read()
            assertFalse(r.finished)
            assertTrue(r.failed)
            assertTrue(r.bytes.isEmpty())
        }
        // 超 cap：保留硬上限前綴＋truncated，續讀至 EOF 故仍 finished。
        run {
            val g = DefaultProcessRunner.Gobbler(ByteArrayInputStream(ByteArray(600 * 1024) { 0x44 }))
            g.run()
            val r = g.read()
            assertTrue(r.finished)
            assertFalse(r.failed)
            assertTrue(r.truncated)
            assertFalse(r.joinIncomplete)
            assertEquals(ShellPolicy.HARD_READ_CAP_BYTES, r.bytes.size)
        }
    }

    // ---- P2-2：EOF 延遲 → join 超界即 joinIncomplete（禁偽造完整） ----

    @Test fun gobblerReadTimeoutMarksJoinIncomplete() {
        val pipeOut = PipedOutputStream()
        val pipeIn = PipedInputStream(pipeOut, 8192)
        val gobbler = DefaultProcessRunner.Gobbler(pipeIn)
        val t = Thread(gobbler, "phaseO-delayed-eof").also {
            it.isDaemon = true
            it.start()
        }
        gobbler.thread = t
        try {
            // 寫端保持開啟（父 exit 但子孫繼承 write-end 模型）：5s join 必超界。
            val r = gobbler.read()
            assertTrue("delayed EOF must report joinIncomplete, not fake-complete", r.joinIncomplete)
            assertFalse(r.finished)
        } finally {
            runCatching { pipeOut.close() }
            runCatching { pipeIn.close() }
            t.join(5_000L)
        }
    }

    private class FlagRunner(val raw: RawOutput) : ProcessRunner {
        override fun run(argv: List<String>, timeoutMs: Long, env: Map<String, String>?): RawOutput = raw
    }

    private fun mappingShell(runner: ProcessRunner): RestrictedShell =
        RestrictedShell(
            runner = runner,
            privateRoot = null,
            execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
        )

    // ---- P2-2：readFailed → Failed（禁 Ok） ----

    @Test fun executeReadFailedMapsToFailed() {
        val shell = mappingShell(
            FlagRunner(RawOutput("part".toByteArray(), ByteArray(0), 0, false, readFailed = true)),
        )
        val r = shell.execute(listOf("echo", "hi"), timeoutMs = 10_000L)
        assertTrue("$r", r is ShellResult.Failed)
    }

    // ---- P2-2：outputIncomplete（exit 0）→ TimedOut 攜 partial（禁完整 Ok） ----

    @Test fun executeOutputIncompleteMapsToTimedOut() {
        val shell = mappingShell(
            FlagRunner(
                RawOutput("part".toByteArray(), ByteArray(0), 0, false, outputIncomplete = true),
            ),
        )
        val r = shell.execute(listOf("echo", "hi"), timeoutMs = 10_000L)
        assertTrue("$r", r is ShellResult.TimedOut)
        r as ShellResult.TimedOut
        assertEquals("part", r.partialStdout)
    }

    // ---- P2-2：outputIncomplete（exit 非零）亦禁 Ok ----

    @Test fun executeOutputIncompleteNonZeroNotOk() {
        val shell = mappingShell(
            FlagRunner(
                RawOutput("part".toByteArray(), ByteArray(0), 1, false, outputIncomplete = true),
            ),
        )
        val r = shell.execute(listOf("echo", "hi"), timeoutMs = 10_000L)
        assertFalse("incomplete output must never be reported as complete Ok: $r", r is ShellResult.Ok)
        assertTrue("$r", r is ShellResult.TimedOut)
    }

    // ---- P2-2：readFailed＋outputIncomplete 同真 → Failed（數據錯誤優先於排空超時） ----

    @Test fun executeReadFailedPlusOutputIncompleteMapsToFailed() {
        val shell = mappingShell(
            FlagRunner(
                RawOutput(
                    "part".toByteArray(),
                    ByteArray(0),
                    0,
                    false,
                    readFailed = true,
                    outputIncomplete = true,
                ),
            ),
        )
        val r = shell.execute(listOf("echo", "hi"), timeoutMs = 10_000L)
        assertTrue("$r", r is ShellResult.Failed)
    }

    // ---- P2-2：timedOut＋readFailed 同真 → TimedOut（超時路徑先回，預期 close 收斂保留） ----

    @Test fun executeTimedOutPlusReadFailedMapsToTimedOut() {
        val shell = mappingShell(
            FlagRunner(
                RawOutput(
                    "part".toByteArray(),
                    ByteArray(0),
                    -1,
                    true,
                    readFailed = true,
                ),
            ),
        )
        val r = shell.execute(listOf("echo", "hi"), timeoutMs = 10_000L)
        assertTrue("$r", r is ShellResult.TimedOut)
        r as ShellResult.TimedOut
        assertEquals("part", r.partialStdout)
    }

    // ---- P2-2：overflowed（誠實截斷）仍 Ok(truncated=true) ----

    @Test fun executeOverflowedStillOkTruncated() {
        val shell = mappingShell(
            FlagRunner(
                RawOutput(ByteArray(70 * 1024) { 0x45 }, ByteArray(0), 0, false, overflowed = true),
            ),
        )
        val r = shell.execute(listOf("echo", "hi"), timeoutMs = 10_000L)
        assertTrue("$r", r is ShellResult.Ok)
        r as ShellResult.Ok
        assertTrue(r.truncated)
    }

    // ---- P1-1：execute 層中斷 → Failed＋恢復旗標 ----

    @Test fun executeInterruptedMapsToFailedAndRestoresFlag() {
        val shell = mappingShell(
            object : ProcessRunner {
                override fun run(argv: List<String>, timeoutMs: Long, env: Map<String, String>?): RawOutput =
                    throw InterruptedException("test-interrupt")
            },
        )
        assertFalse(Thread.currentThread().isInterrupted)
        val r = shell.execute(listOf("echo", "hi"), timeoutMs = 10_000L)
        assertTrue("$r", r is ShellResult.Failed)
        assertTrue("execute must restore caller interrupt", Thread.currentThread().isInterrupted)
        // 清旗，避免污染後續測試。
        Thread.interrupted()
    }
}
