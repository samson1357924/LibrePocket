package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.PrivilegeAuditLog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * X1 有界 acquisition 回歸（owner 新 P1 lifecycle，W1 引入的 timeout 繞過）：
 * W1 在呼叫執行緒先取雙流 → Shizuku getter 為同步 Binder IPC，stall 即呼叫執行緒
 * 卡死（deadline／cancel 永不進入）；kill 路徑重調 getter 同樣阻塞。
 * X1 將 getter 移回 gobbler 內整段 try/catch（throw 即 markFailed，W1 語義保留；
 * block 則 caller 照常進 deadline/poll/kill），kill 只關已取流＋先 interrupt。
 *
 * 全 fake（局部 Latch 阻塞 getter 假進程，共用 fixture 不支援 getter 阻塞故不動它），
 * 零真機、零 su、零 binder。8 測：兩 runner ×（stdout／stderr 阻塞）×（timeout／cancel）。
 * 每測另設 `defaultUncaughtExceptionHandler` 記錄並斷言無未捕捉例外（測完恢復）。
 *
 * - timeout：hang 進程（`waitFor(timeout)` 切片睡眠、被 killer `destroy()` 即解，
 *   `exitValue` 0）＋單流 getter 阻塞於 `CountDownLatch.await()`（不釋放即永不返回；
 *   中斷即抛，經 gobbler 整段 catch 收斂為 markFailed）。`timeoutMs=500ms`，背景執行緒
 *   跑 `runBinary`，上界 10s 內回 TimedOut＋killer 被調＋capture null＋無未捕捉；
 * - cancel：同上 hang＋阻塞，但 `timeoutMs=10s`＋`isCancelled = { !job.isActive }`，
 *   主執行緒 sleep 200ms 後 `job.cancel()`（真 Job、跨執行緒 racing，不在 callback 內 cancel），
 *   背景 drain 回 TimedOut＋killer 被調；
 * - orphan：釋放 latch 後 getter 回 EOF 空流，輪詢 `elevated-stdout`／`elevated-stderr`
 *   線程名消失（上界 5s），證無 orphan。只斷 verdict/killer/capture/無 orphan，不斷精確耗時。
 *
 * 註：括號內「waitFor true + exit 0」指進程最終可收斂態（被 killer destroy 後
 * `waitFor` 回 true、`exitValue` 0）；timeout/cancel 用例本身必須 hang（否則首個
 * `waitFor` 切片即回 true 走成功路徑，不進 kill，更不斷 killer/TimedOut）。
 */
class ElevatedRunnerBlockedGetterTest {

    private val privateRoot = "/data/data/files"

    /**
     * 終審殘留隔離（Phase 1 P1-1）：每用例首尾歸零 consecutive＋輪詢收斂
     * outstanding/active，避免同 JVM 內熔斷污染後測。
     * （測試隔離重置，非#136-B3修復；不升級 noteClean 可見性。）
     */
    @Before fun setUpBreakerIsolation() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
    }

    @After fun tearDownBreakerIsolation() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
    }

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

    /**
     * Latch 阻塞 getter 假進程（X1 專用，不動共用 fixture）：
     * - `stdoutLatch`／`stderrLatch` 非空即該流 getter 先 `await()`（不釋放即永不返回；
     *   中斷即 restore 後轉 RuntimeException，經 gobbler 整段 catch 收斂為 markFailed）；
     *   釋放後回 EOF 空流；
     * - `hang=true` 時 `waitFor(timeout)` 切片睡眠、被 killer `destroy()` 即解
     *  （同 [FakeProcess] hang 語義；已 destroyed 即直回 true，kill 後等待不睡滿 5s）；
     * - `exitValue` 恆 0（最終可收斂態）。
     */
    private class BlockedGetterProcess(
        private val stdoutLatch: CountDownLatch? = null,
        private val stderrLatch: CountDownLatch? = null,
        private val hang: Boolean = true,
        private val exit: Int = 0,
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
        override fun waitFor(): Int = exit
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
        override fun exitValue(): Int = exit
        override fun destroy() {
            destroyed = true
        }
        override fun destroyForcibly(): Process {
            destroyed = true
            return this
        }
    }

    private fun shizukuRunner(
        spawn: (List<String>) -> Process,
        killer: (Process) -> Unit,
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
    )

    private fun rootRunner(
        spawn: (List<String>) -> Process,
        killer: (Process) -> Unit,
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
    )

    private fun awaitNoElevatedThreads(boundMs: Long = 5_000L) {
        val deadline = System.currentTimeMillis() + boundMs
        while (true) {
            val alive = Thread.getAllStackTraces().keys.filter {
                (it.name == "elevated-stdout" || it.name == "elevated-stderr") && it.isAlive
            }
            if (alive.isEmpty()) return
            if (System.currentTimeMillis() >= deadline) {
                fail("orphan elevated gobbler threads: $alive")
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

    private fun doTimeoutRound(
        blockStdout: Boolean,
        makeRunner: (
            spawn: (List<String>) -> Process,
            killer: (Process) -> Unit,
        ) -> BinaryElevatedRunner,
    ) {
        val latch = CountDownLatch(1)
        val kills = AtomicInteger(0)
        val spawn: (List<String>) -> Process = {
            BlockedGetterProcess(
                stdoutLatch = if (blockStdout) latch else null,
                stderrLatch = if (!blockStdout) latch else null,
                hang = true,
                exit = 0,
            )
        }
        val runner = makeRunner(spawn, {
            kills.incrementAndGet()
            it.destroy()
        })
        runWithUncaughtGuard { uncaught ->
            val resultRef = AtomicReference<ElevatedBinaryResult?>()
            val bg = Thread {
                resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "X1-T"), 500L))
            }.also { it.isDaemon = true; it.start() }
            bg.join(10_000L)
            assertFalse("background drain should finish within bound", bg.isAlive)
            val b = resultRef.get()
            assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
            assertEquals(1, kills.get())
            assertTrue(uncaught.isEmpty())
            // capture 側：同 runner 再跑一次 screencap（同樣阻塞→同樣 TimedOut→null）。
            val audit = PrivilegeAuditLog()
            assertNull(RootFramebufferCapture.screencapViaBinary(runner, audit, "X1-T", 500L)())
            assertEquals(2, kills.get())
            assertTrue(uncaught.isEmpty())
            // 釋放 latch 後 getter 回 EOF，gobbler 應退出，無 orphan。
            latch.countDown()
            awaitNoElevatedThreads()
        }
    }

    private fun doCancelRound(
        blockStdout: Boolean,
        makeRunner: (
            spawn: (List<String>) -> Process,
            killer: (Process) -> Unit,
            isCancelled: () -> Boolean,
        ) -> BinaryElevatedRunner,
    ) {
        val latch = CountDownLatch(1)
        val job = Job()
        val kills = AtomicInteger(0)
        val spawn: (List<String>) -> Process = {
            BlockedGetterProcess(
                stdoutLatch = if (blockStdout) latch else null,
                stderrLatch = if (!blockStdout) latch else null,
                hang = true,
                exit = 0,
            )
        }
        val runner = makeRunner(spawn, {
            kills.incrementAndGet()
            it.destroy()
        }, { !job.isActive })
        runWithUncaughtGuard { uncaught ->
            val resultRef = AtomicReference<ElevatedBinaryResult?>()
            val bg = Thread {
                resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "X1-T"), 10_000L))
            }.also { it.isDaemon = true; it.start() }
            Thread.sleep(200L)
            job.cancel()
            bg.join(10_000L)
            assertFalse("background drain should finish within bound", bg.isAlive)
            val b = resultRef.get()
            assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
            assertEquals(1, kills.get())
            assertTrue(job.isCancelled)
            assertTrue(uncaught.isEmpty())
            // capture 側：同一已取消 Job 即進 spawn 前取消→TimedOut→null（B2 P1-4：
            // 已取消零新 spawn，故 kills 仍 1，不再 2；drain 層未建，無 gobbler 阻塞）。
            val audit = PrivilegeAuditLog()
            assertNull(RootFramebufferCapture.screencapViaBinary(runner, audit, "X1-T", 10_000L)())
            assertEquals(1, kills.get())
            assertTrue(uncaught.isEmpty())
            latch.countDown()
            awaitNoElevatedThreads()
        }
    }

    @Test fun rootBlockedStdoutTimeout() {
        doTimeoutRound(blockStdout = true) { spawn, killer ->
            rootRunner(spawn, killer)
        }
    }

    @Test fun rootBlockedStderrTimeout() {
        doTimeoutRound(blockStdout = false) { spawn, killer ->
            rootRunner(spawn, killer)
        }
    }

    @Test fun rootBlockedStdoutCancel() {
        doCancelRound(blockStdout = true) { spawn, killer, isCancelled ->
            rootRunner(spawn, killer, isCancelled)
        }
    }

    @Test fun rootBlockedStderrCancel() {
        doCancelRound(blockStdout = false) { spawn, killer, isCancelled ->
            rootRunner(spawn, killer, isCancelled)
        }
    }

    @Test fun shizukuBlockedStdoutTimeout() {
        doTimeoutRound(blockStdout = true) { spawn, killer ->
            shizukuRunner(spawn, killer)
        }
    }

    @Test fun shizukuBlockedStderrTimeout() {
        doTimeoutRound(blockStdout = false) { spawn, killer ->
            shizukuRunner(spawn, killer)
        }
    }

    @Test fun shizukuBlockedStdoutCancel() {
        doCancelRound(blockStdout = true) { spawn, killer, isCancelled ->
            shizukuRunner(spawn, killer, isCancelled)
        }
    }

    @Test fun shizukuBlockedStderrCancel() {
        doCancelRound(blockStdout = false) { spawn, killer, isCancelled ->
            shizukuRunner(spawn, killer, isCancelled)
        }
    }
}
