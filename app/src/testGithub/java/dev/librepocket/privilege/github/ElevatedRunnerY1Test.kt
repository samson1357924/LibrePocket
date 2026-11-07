package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.PrivilegeAuditLog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y1 回歸（owner 新 P1 兩入口＋P2；預設 killer 路徑）：
 * 舊碼預設 `killer=ElevatedProcessKiller.killTree` 內含
 * `process.inputStream/errorStream/outputStream.close()`（getter 先求值）＋起手
 * `process.outputStream.close()` 同步無界，X1 單測一律注入 `killer={it.destroy()}`
 * 繞過此路徑。Y1 修復後（產品碼只動 `ElevatedProcessIo`＋`ElevatedProcessKiller`）：
 * killTree 不觸 getter（只管 destroy，串流由 drain 關已取引用）、stdin 關有界化
 * （daemon＋`min(剩餘 budget, 2s)` join）、finished 路徑 join 超時 interrupt＋grace。
 *
 * 全 fake（局部 latch 阻塞假進程，共用 fixture 不支援 getter/close 阻塞故不動它），
 * 零真機、零 su、零 binder。本檔一律用預設 killer（不注入 `killer`），直擊舊路徑：
 * - (a) blocked stdout/stderr getter × timeout：舊 killTree 在 kill 路徑重調
 *   getter 即卡死（`runCatching{close}` 永不返回）；新 killer 不觸 getter，有界
 *   TimedOut＋capture null＋無未捕捉，Root＋Shizuku × 雙流＝4 測；
 * - (b) blocked outputStream getter/close × timeout：舊起手同步關即卡死；
 *   新 stdin 有界關（2s 預算）放行，有界 TimedOut＋capture null＋無未捕捉，
 *   Root＋Shizuku ×（getter 阻塞／close 阻塞）＝4 測；
 * - (c) success-exit（waitFor 即 true／exit 0）＋blocked stdout getter 永不釋放：
 *   舊碼成功路徑 join(10s) 超界即回（孤兒線程殘留；`readFailed=false` 誤判
 *   Ok-truncated）；Y1 起 interrupt＋1s grace 收線程，經 markFailed 轉 Failed，
 *   有界返回（約 10s＋grace，不超過 ~15s）；P1-4 起 finished tail 雙流共享 2s
 *   （`FINISHED_TAIL_BUDGET_MS`，剩餘 30s 下取 2s），有界返回約 2s（1s..8s CI
 *   餘量）＋非 Ok-完整（Failed）＋capture null，Root＋Shizuku＝2 測。
 *
 * Fake 的 `destroy()` 釋放 latch 以便線程退出（(c) 除外：(c) 靠 interrupt 退出，
 * 不釋放亦可；測試 finally 仍釋放防掛死污染後測）。
 */
class ElevatedRunnerY1Test {

    private val privateRoot = "/data/data/files"

    /**
     * (a) 專用：stdout/stderr getter latch 阻塞假進程（hang waitFor，被 destroy 即解）。
     * `destroy()` 釋放雙 latch（已被中斷的 gobbler 已退出，此處僅兜底未進 await 者）。
     */
    private class DefaultKillerBlockedGetterProcess(
        private val stdoutLatch: CountDownLatch?,
        private val stderrLatch: CountDownLatch?,
        private val hang: Boolean = true,
    ) : Process() {
        @Volatile var destroyed = false
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream {
            try {
                stdoutLatch?.await()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw RuntimeException("interrupted stdout getter", e)
            }
            return ByteArrayInputStream(ByteArray(0))
        }
        override fun getErrorStream(): InputStream {
            try {
                stderrLatch?.await()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw RuntimeException("interrupted stderr getter", e)
            }
            return ByteArrayInputStream(ByteArray(0))
        }
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (destroyed) return true
            if (hang) {
                try {
                    Thread.sleep(unit.toMillis(timeout))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                return destroyed
            }
            return true
        }
        override fun exitValue(): Int = 0
        override fun destroy() {
            destroyed = true
            stdoutLatch?.countDown()
            stderrLatch?.countDown()
        }
        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }

    /** (b) close 阻塞用流：`close()` 先 await（不釋放即永不返回；中斷即抛）。 */
    private class BlockingCloseStream(private val latch: CountDownLatch) : ByteArrayOutputStream() {
        override fun close() {
            try {
                latch.await()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw RuntimeException("interrupted stdin close", e)
            }
            super.close()
        }
    }

    /**
     * (b) 專用：stdin 阻塞假進程（`blockGetter=true` 則 `getOutputStream` 阻塞，
     * 否則回阻塞 close 流；stdout/stderr EOF；hang waitFor）。`destroy()` 釋放
     * stdin latch（stdin-closer daemon 藉此退出）。
     */
    private class BlockedStdinProcess(
        private val stdinLatch: CountDownLatch,
        private val blockGetter: Boolean,
        private val hang: Boolean = true,
    ) : Process() {
        @Volatile var destroyed = false
        override fun getOutputStream(): OutputStream {
            if (blockGetter) {
                try {
                    stdinLatch.await()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw RuntimeException("interrupted stdin getter", e)
                }
                return ByteArrayOutputStream()
            }
            return BlockingCloseStream(stdinLatch)
        }
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (destroyed) return true
            if (hang) {
                try {
                    Thread.sleep(unit.toMillis(timeout))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                return destroyed
            }
            return true
        }
        override fun exitValue(): Int = 0
        override fun destroy() {
            destroyed = true
            stdinLatch.countDown()
        }
        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }

    /**
     * (c) 專用：success-exit（`waitFor` 即 true／exit 0）＋stdout getter 永不釋放。
     * `destroy()` 刻意不釋放 latch（靠 Y1 成功路徑 interrupt 退出，證 interrupt 生效）；
     * 測試 finally 釋放兜底防掛死。
     */
    private class SuccessBlockedStdoutProcess(
        val stdoutLatch: CountDownLatch,
    ) : Process() {
        @Volatile var destroyed = false
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream {
            try {
                stdoutLatch.await()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw RuntimeException("interrupted stdout getter", e)
            }
            return ByteArrayInputStream(ByteArray(0))
        }
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = 0
        override fun destroy() {
            destroyed = true
        }
        override fun destroyForcibly(): Process {
            destroyed = true
            return this
        }
    }

    private fun makeRoot(spawn: (List<String>) -> Process): BinaryElevatedRunner = RootSuRunner(
        privateRoot = privateRoot,
        bridgeGranted = GrantFixtures.bridgeOn,
        activationCheck = GrantFixtures.activationOn,
        spawn = spawn,
        suBinary = "/system/bin/su",
        grantCheck = GrantFixtures.allowAlways,
    )

    private fun makeShizuku(spawn: (List<String>) -> Process): BinaryElevatedRunner =
        ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            pingBinder = { true },
            spawn = spawn,
            status = { ShizukuStatus.Available(15) },
            grantCheck = GrantFixtures.allowAlways,
        )

    private fun awaitNoElevatedThreads(boundMs: Long = 5_000L) {
        val names = setOf("elevated-stdout", "elevated-stderr", "elevated-stdin-close")
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

    private fun runWithUncaughtGuard(block: (uncaught: MutableList<Throwable>) -> Unit) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val uncaught: MutableList<Throwable> = mutableListOf()
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            synchronized(uncaught) { uncaught.add(e) }
        }
        try {
            block(uncaught)
            Thread.sleep(500L)
            synchronized(uncaught) {
                assertTrue("uncaught thread exceptions: $uncaught", uncaught.isEmpty())
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    private fun doDefaultKillerBlockedGetter(
        blockStdout: Boolean,
        makeRunner: (spawn: (List<String>) -> Process) -> BinaryElevatedRunner,
    ) {
        val victims =
            Collections.synchronizedList(mutableListOf<DefaultKillerBlockedGetterProcess>())
        val spawn: (List<String>) -> Process = {
            DefaultKillerBlockedGetterProcess(
                stdoutLatch = if (blockStdout) CountDownLatch(1) else null,
                stderrLatch = if (!blockStdout) CountDownLatch(1) else null,
                hang = true,
            ).also { victims.add(it) }
        }
        // 刻意不注入 killer：走預設 ElevatedProcessKiller.killTree（Y1 已去串流關閉）。
        val runner = makeRunner(spawn)
        runWithUncaughtGuard { uncaught ->
            val resultRef = AtomicReference<ElevatedBinaryResult?>()
            val bg = Thread {
                resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "Y1-A"), 500L))
            }.also { it.isDaemon = true; it.start() }
            bg.join(10_000L)
            assertFalse(
                "default killer must not block on getters; drain must return bounded",
                bg.isAlive,
            )
            val b = resultRef.get()
            assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
            assertTrue("default killer must have destroyed", victims.isNotEmpty())
            assertTrue(
                "default killer must have destroyed all spawned",
                victims.all { it.destroyed },
            )
            assertTrue(uncaught.isEmpty())
            val audit = PrivilegeAuditLog()
            assertNull(RootFramebufferCapture.screencapViaBinary(runner, audit, "Y1-A", 500L)())
            assertTrue(uncaught.isEmpty())
            awaitNoElevatedThreads()
        }
    }

    private fun doBlockedStdin(
        blockGetter: Boolean,
        makeRunner: (spawn: (List<String>) -> Process) -> BinaryElevatedRunner,
    ) {
        val victims = Collections.synchronizedList(mutableListOf<BlockedStdinProcess>())
        val spawn: (List<String>) -> Process = {
            BlockedStdinProcess(CountDownLatch(1), blockGetter, hang = true)
                .also { victims.add(it) }
        }
        // 刻意不注入 killer：stdin 釋放靠預設 killer 的 destroy（Y1 有界關放行＋兜底）。
        val runner = makeRunner(spawn)
        runWithUncaughtGuard { uncaught ->
            val resultRef = AtomicReference<ElevatedBinaryResult?>()
            val bg = Thread {
                resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "Y1-B"), 5_000L))
            }.also { it.isDaemon = true; it.start() }
            bg.join(15_000L)
            assertFalse(
                "bounded stdin close must release caller; drain must return bounded",
                bg.isAlive,
            )
            val b = resultRef.get()
            assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
            assertTrue("default killer must have destroyed", victims.isNotEmpty())
            assertTrue(
                "default killer must have destroyed all spawned",
                victims.all { it.destroyed },
            )
            assertTrue(uncaught.isEmpty())
            val audit = PrivilegeAuditLog()
            assertNull(RootFramebufferCapture.screencapViaBinary(runner, audit, "Y1-B", 5_000L)())
            assertTrue(uncaught.isEmpty())
            awaitNoElevatedThreads()
        }
    }

    private fun doSuccessBlockedStdout(
        makeRunner: (spawn: (List<String>) -> Process) -> BinaryElevatedRunner,
    ) {
        val latch = CountDownLatch(1)
        // 刻意不注入 killer：成功路徑不經 killer，(c) 只驗 finished join＋interrupt。
        val runner = makeRunner { SuccessBlockedStdoutProcess(latch) }
        try {
            runWithUncaughtGuard { uncaught ->
                val resultRef = AtomicReference<ElevatedBinaryResult?>()
                val start = System.currentTimeMillis()
                val bg = Thread {
                    resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "Y1-C"), 30_000L))
                }.also { it.isDaemon = true; it.start() }
                bg.join(25_000L)
                val elapsed = System.currentTimeMillis() - start
                assertFalse(
                    "success-path stuck getter must return bounded (~2s shared tail)",
                    bg.isAlive,
                )
                // 有界性以 verdict／flag 為主證（上已斷 bg 必回；下斷 Failed＋capture null，
                // 舊碼此處為 Ok(truncated)＋孤兒線程）；耗時僅記 CI 餘量帶
                // （P1-4 finished tail 共享 2s，1s..8s），非 hard-fail 主證。
                assertTrue("elapsed=$elapsed (CI margin 1s..8s)", elapsed >= 1_000L)
                assertTrue("elapsed=$elapsed (CI margin 1s..8s)", elapsed < 8_000L)
                // interrupt→gobbler 抛→markFailed→readFailed：非 Ok-完整（Failed），
                // 舊碼此處為 Ok(truncated=true)＋孤兒線程。
                val b = resultRef.get()
                assertTrue("$b", b is ElevatedBinaryResult.Failed)
                assertTrue(uncaught.isEmpty())
                val audit = PrivilegeAuditLog()
                assertNull(
                    RootFramebufferCapture.screencapViaBinary(runner, audit, "Y1-C", 30_000L)(),
                )
                assertTrue(uncaught.isEmpty())
            }
        } finally {
            // 兜底：正常路徑靠 interrupt 退出，此處僅防回归掛死污染後測。
            latch.countDown()
            awaitNoElevatedThreads()
        }
    }

    @Test fun rootDefaultKillerBlockedStdoutTimeout() {
        doDefaultKillerBlockedGetter(blockStdout = true, makeRunner = ::makeRoot)
    }

    @Test fun rootDefaultKillerBlockedStderrTimeout() {
        doDefaultKillerBlockedGetter(blockStdout = false, makeRunner = ::makeRoot)
    }

    @Test fun shizukuDefaultKillerBlockedStdoutTimeout() {
        doDefaultKillerBlockedGetter(blockStdout = true, makeRunner = ::makeShizuku)
    }

    @Test fun shizukuDefaultKillerBlockedStderrTimeout() {
        doDefaultKillerBlockedGetter(blockStdout = false, makeRunner = ::makeShizuku)
    }

    @Test fun rootBlockedStdinGetterTimeout() {
        doBlockedStdin(blockGetter = true, makeRunner = ::makeRoot)
    }

    @Test fun rootBlockedStdinCloseTimeout() {
        doBlockedStdin(blockGetter = false, makeRunner = ::makeRoot)
    }

    @Test fun shizukuBlockedStdinGetterTimeout() {
        doBlockedStdin(blockGetter = true, makeRunner = ::makeShizuku)
    }

    @Test fun shizukuBlockedStdinCloseTimeout() {
        doBlockedStdin(blockGetter = false, makeRunner = ::makeShizuku)
    }

    @Test fun rootSuccessBlockedStdoutIsFailedNotOkComplete() {
        doSuccessBlockedStdout(makeRunner = ::makeRoot)
    }

    @Test fun shizukuSuccessBlockedStdoutIsFailedNotOkComplete() {
        doSuccessBlockedStdout(makeRunner = ::makeShizuku)
    }
}
