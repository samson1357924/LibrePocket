package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.PrivilegeAuditLog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Z1 監督清理回歸（owner 新 P1＋P2＋P3；串行契約下預填代理多 stall 疊加，真實多 drain 疊加由 P1Fix 混合測試覆蓋）：
 * kill 路徑 `killer`＋已取流 close 監督化（daemon worker＋`min(max(remaining,500),3000)`
 * 上界＋interrupt＋500ms grace，永不跳過 killer），[ElevatedProcessIo.Drained.cleanupIncomplete]
 * 帶出未完成；stdin-closer 尾收同理。runner verdict 映射不變（timedOut 仍 TimedOut）。
 *
 * 全 fake（局部 latch／sleep 阻塞假進程），零真機、零 su、零 binder。本檔一律用預設
 * killer（不注入）直擊舊同步路徑（`killer(process)`＋已取流 close 同步無界）。
 * - (a) blocked destroy（latch，吞 interrupt 續等）× timeout：有界 TimedOut＋
 *   killer 已嘗試（destroyEntered）＋cleanupIncomplete=true（drain 直測斷旗標＋
 *   runner 層斷 verdict，兩層都做；finally 釋放防掛死）；
 * - (b) blocked 已取流 close（close latch，吞 interrupt 續等）× timeout：同上；
 * - (c) success-exit＋blocked stdin getter／close（latch 不釋放直到斷言完）：
 *   有界 Ok（stdout 完整）＋cleanupIncomplete=true＋capture 非 null；finally 釋放；
 * - (d) 永久 stall 模型（close 吞 interrupt 續睡 8s）：有界返回＋flag true
 *   （daemon 殘留 8s，測完等待其退出再做 orphan 斷言，避免污染後測）；
 * - (e) hang＋blocked stdin close（getter 立即返回可寫流對象、close 可中斷等待
 *   latch，destroy 不釋放僅標記）：runner 層有界 TimedOut＋capture null＋無未捕捉
 *   （drain 層僅覆蓋 close 阻塞，runner 層此前只測 getter 阻塞；本測證 stdin 有界關
 *   ＋kill 路徑 interrupt 放行，非靠 destroy 兜底釋放；finally 釋放防掛死）。
 */
class ElevatedRunnerZ1Test {

    private val privateRoot = "/data/data/files"

    /**
     * 終審殘留隔離：本檔多個用例觸發不清創（blocked destroy／close，drain
     * kill 路徑 `noteUnclean` 累計 consecutive）。用例間以 [Before]/[After]
     * 歸零 consecutive（各用例只做單次 drain 斷言，不依賴跨用例累計；
     * 跨呼叫累計語義由 `ElevatedRunnerAA1Test` 覆蓋），防同 JVM 內污染後測。
     * （outstanding 由各用例 finally 釋放 latch＋await 收斂，此處只歸零計數器；
     * 測試隔離重置，非#136-B3修復。）
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

    /** (a) 專用：destroy 吞 interrupt 續等 latch（釋放前永不返回）。 */
    private class BlockedDestroyProcess(
        private val latch: CountDownLatch,
    ) : Process() {
        @Volatile var destroyEntered = false
        @Volatile var destroyed = false
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (destroyed) return true
            try {
                Thread.sleep(unit.toMillis(timeout))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return destroyed
        }
        override fun exitValue(): Int = 0
        override fun destroy() {
            destroyEntered = true
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // Z1：吞 interrupt 續等（模擬不可中斷 Binder stall），清理 worker
                    // 的 interrupt＋grace 無法解開，drain 有界返回＋flag true。
                }
            }
            destroyed = true
        }
        override fun destroyForcibly(): Process {
            // killTree 順序：destroy 先阻塞，故此處正常不可達；若達亦同語義。
            destroy()
            return this
        }
    }

    /** (b) 專用：吞 interrupt 的阻塞 close 流（釋放前永不返回）。 */
    private class UninterruptibleBlockingInput(
        private val latch: CountDownLatch,
        data: ByteArray = ByteArray(0),
    ) : InputStream() {
        private val inner = ByteArrayInputStream(data)
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
        override fun close() {
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // 吞 interrupt 續等，見上。
                }
            }
        }
    }

    /** (b) 專用：單流 close 阻塞（另一流 EOF）；destroy 立即成功以便 kill 等待快收。 */
    private class BlockedCloseProcess(
        private val latch: CountDownLatch,
        blockStdout: Boolean = true,
    ) : Process() {
        @Volatile var destroyed = false
        private val stdoutStream: InputStream =
            if (blockStdout) UninterruptibleBlockingInput(latch) else ByteArrayInputStream(ByteArray(0))
        private val stderrStream: InputStream =
            if (!blockStdout) UninterruptibleBlockingInput(latch) else ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = stdoutStream
        override fun getErrorStream(): InputStream = stderrStream
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (destroyed) return true
            try {
                Thread.sleep(unit.toMillis(timeout))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return destroyed
        }
        override fun exitValue(): Int = 0
        override fun destroy() {
            destroyed = true
        }
        override fun destroyForcibly(): Process {
            destroyed = true
            return this
        }
    }

    /** (c) stdin close 阻塞用輸出流（吞 interrupt 續等）。 */
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

    /**
     * (c) 專用：success-exit（waitFor 即 true／exit 0）＋stdin 阻塞；
     * `blockGetter=true` 則 getOutputStream 吞 interrupt 續等，否則回阻塞 close 流。
     * stdout 固定 "ok"（完整），stderr 空。
     */
    private class BlockedStdinSuccessProcess(
        private val latch: CountDownLatch,
        private val blockGetter: Boolean,
        private val stdoutData: ByteArray = "ok".toByteArray(),
    ) : Process() {
        override fun getOutputStream(): OutputStream {
            if (blockGetter) {
                while (true) {
                    try {
                        latch.await()
                        break
                    } catch (_: InterruptedException) {
                        // 吞 interrupt 續等。
                    }
                }
                return ByteArrayOutputStream()
            }
            return BlockingStdinCloseOutput(latch)
        }
        override fun getInputStream(): InputStream = ByteArrayInputStream(stdoutData)
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }

    /** (d) 專用：close 吞 interrupt 續睡 8s 的永久 stall 模型。 */
    private class Sleep8sInput(data: ByteArray = ByteArray(0)) : InputStream() {
        private val inner = ByteArrayInputStream(data)
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
        override fun close() {
            val start = System.currentTimeMillis()
            var remaining = 8_000L
            while (remaining > 0) {
                try {
                    Thread.sleep(remaining)
                    break
                } catch (_: InterruptedException) {
                    // 吞 interrupt 續睡（模擬不可中斷 stall）。
                    remaining = 8_000L - (System.currentTimeMillis() - start)
                }
            }
        }
    }

    /** (d) 專用：hang＋stdout close 睡 8s；destroy 立即成功（kill 等待快收）。 */
    private class PermanentStallProcess : Process() {
        @Volatile var destroyed = false
        private val stdoutStream = Sleep8sInput()
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = stdoutStream
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (destroyed) return true
            try {
                Thread.sleep(unit.toMillis(timeout))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return destroyed
        }
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

    private fun runWithUncaughtGuard(block: (uncaught: MutableList<Throwable>) -> Unit) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val uncaught: MutableList<Throwable> = mutableListOf()
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            synchronized(uncaught) { uncaught.add(e) }
        }
        try {
            block(uncaught)
            // 固定 sleep(500ms) 改輪詢 2s：晚到未捕捉 fail-fast（首見即抛），2s 無逃逸即收斂；
            // 上界僅 CI 餘量（daemon 線程逃逸至多晚一個調度片），非 verdict 主證。
            val deadline = System.currentTimeMillis() + 2_000L
            while (true) {
                synchronized(uncaught) {
                    assertTrue("uncaught thread exceptions: $uncaught", uncaught.isEmpty())
                }
                if (System.currentTimeMillis() >= deadline) break
                Thread.sleep(50L)
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    // (a) drain 直測：預設 killer×blocked destroy×timeout → 有界 TimedOut＋flag true＋killer 已嘗試。
    @Test fun drainBlockedDestroyIsBoundedTimedOutWithFlag() {
        val latch = CountDownLatch(1)
        val proc = BlockedDestroyProcess(latch)
        try {
            runWithUncaughtGuard { uncaught ->
                val resultRef = AtomicReference<ElevatedProcessIo.Drained?>()
                val start = System.currentTimeMillis()
                val bg = Thread {
                    // 刻意不注入 killer：走預設 ElevatedProcessKiller.killTree。
                    resultRef.set(ElevatedProcessIo.drain(proc, 500L))
                }.also { it.isDaemon = true; it.start() }
                bg.join(20_000L)
                val elapsed = System.currentTimeMillis() - start
                assertFalse("drain must return bounded despite blocked destroy", bg.isAlive)
                // 有界返回為主證（上已斷 bg 不存活）；上界 25s 僅 CI 餘量
                //（timeout 0.5s＋cleanup 0.5s＋grace 0.5s＋kill等待 5s＋joins ≈7s），絕不無界。
                assertTrue("elapsed=$elapsed", elapsed < 25_000L)
                val d = resultRef.get()
                assertNotNull(d)
                d!!
                assertTrue("timedOut expected, got $d", d.timedOut)
                assertTrue("cleanupIncomplete expected, got $d", d.cleanupIncomplete)
                assertTrue("default killer must have attempted destroy", proc.destroyEntered)
                assertTrue(uncaught.isEmpty())
            }
        } finally {
            latch.countDown()
            awaitNoElevatedThreads(10_000L)
        }
    }

    private fun doRunnerBlockedDestroy(
        makeRunner: (spawn: (List<String>) -> Process) -> BinaryElevatedRunner,
    ) {
        val latch = CountDownLatch(1)
        val victims = mutableListOf<BlockedDestroyProcess>()
        val spawn: (List<String>) -> Process = {
            BlockedDestroyProcess(latch).also { victims.add(it) }
        }
        // 刻意不注入 killer：走預設 killTree。
        val runner = makeRunner(spawn)
        try {
            runWithUncaughtGuard { uncaught ->
                val resultRef = AtomicReference<ElevatedBinaryResult?>()
                val bg = Thread {
                    resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "Z1-A"), 500L))
                }.also { it.isDaemon = true; it.start() }
                bg.join(20_000L)
                assertFalse("runner must return bounded despite blocked destroy", bg.isAlive)
                val b = resultRef.get()
                assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
                assertTrue("default killer must have attempted", victims.isNotEmpty())
                assertTrue(
                    "default killer must have attempted all spawned",
                    victims.all { it.destroyEntered },
                )
                assertTrue(uncaught.isEmpty())
            }
        } finally {
            latch.countDown()
            awaitNoElevatedThreads(10_000L)
        }
    }

    @Test fun rootBlockedDestroyTimeoutIsTimedOut() {
        doRunnerBlockedDestroy(::makeRoot)
    }

    @Test fun shizukuBlockedDestroyTimeoutIsTimedOut() {
        doRunnerBlockedDestroy(::makeShizuku)
    }

    // (b) drain 直測：blocked 已取流 close×timeout → 同上。
    @Test fun drainBlockedAcquiredCloseIsBoundedTimedOutWithFlag() {
        val latch = CountDownLatch(1)
        val proc = BlockedCloseProcess(latch, blockStdout = true)
        try {
            runWithUncaughtGuard { uncaught ->
                val resultRef = AtomicReference<ElevatedProcessIo.Drained?>()
                val start = System.currentTimeMillis()
                val bg = Thread {
                    resultRef.set(ElevatedProcessIo.drain(proc, 500L))
                }.also { it.isDaemon = true; it.start() }
                bg.join(25_000L)
                val elapsed = System.currentTimeMillis() - start
                assertFalse("drain must return bounded despite blocked close", bg.isAlive)
                // 有界返回為主證（上已斷 bg 不存活）；上界 25s 僅 CI 餘量，絕不無界。
                assertTrue("elapsed=$elapsed", elapsed < 25_000L)
                val d = resultRef.get()
                assertNotNull(d)
                d!!
                assertTrue("timedOut expected, got $d", d.timedOut)
                assertTrue("cleanupIncomplete expected, got $d", d.cleanupIncomplete)
                assertTrue(uncaught.isEmpty())
            }
        } finally {
            latch.countDown()
            awaitNoElevatedThreads(10_000L)
        }
    }

    private fun doRunnerBlockedClose(
        makeRunner: (spawn: (List<String>) -> Process) -> BinaryElevatedRunner,
    ) {
        val latch = CountDownLatch(1)
        val spawn: (List<String>) -> Process = { BlockedCloseProcess(latch, blockStdout = true) }
        val runner = makeRunner(spawn)
        try {
            runWithUncaughtGuard { uncaught ->
                val resultRef = AtomicReference<ElevatedBinaryResult?>()
                val bg = Thread {
                    resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "Z1-B"), 500L))
                }.also { it.isDaemon = true; it.start() }
                bg.join(25_000L)
                assertFalse("runner must return bounded despite blocked close", bg.isAlive)
                val b = resultRef.get()
                assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
                assertTrue(uncaught.isEmpty())
            }
        } finally {
            latch.countDown()
            awaitNoElevatedThreads(10_000L)
        }
    }

    @Test fun rootBlockedAcquiredCloseTimeoutIsTimedOut() {
        doRunnerBlockedClose(::makeRoot)
    }

    @Test fun shizukuBlockedAcquiredCloseTimeoutIsTimedOut() {
        doRunnerBlockedClose(::makeShizuku)
    }

    // (c) drain 直測：success-exit＋blocked stdin getter → 有界 Ok＋flag true＋stdout 完整。
    @Test fun drainSuccessBlockedStdinGetterIsOkWithFlag() {
        val latch = CountDownLatch(1)
        val proc = BlockedStdinSuccessProcess(latch, blockGetter = true)
        try {
            runWithUncaughtGuard { uncaught ->
                val resultRef = AtomicReference<ElevatedProcessIo.Drained?>()
                val start = System.currentTimeMillis()
                val bg = Thread {
                    resultRef.set(ElevatedProcessIo.drain(proc, 5_000L))
                }.also { it.isDaemon = true; it.start() }
                bg.join(15_000L)
                val elapsed = System.currentTimeMillis() - start
                assertFalse("drain must return bounded despite blocked stdin", bg.isAlive)
                // 有界返回為主證（上已斷 bg 不存活）；上界 25s 僅 CI 餘量，絕不無界。
                assertTrue("elapsed=$elapsed", elapsed < 25_000L)
                val d = resultRef.get()
                assertNotNull(d)
                d!!
                assertFalse("timedOut must be false, got $d", d.timedOut)
                assertTrue("cleanupIncomplete expected, got $d", d.cleanupIncomplete)
                assertEquals("ok", String(d.stdout, Charsets.UTF_8))
                assertFalse(d.truncated)
                assertFalse(d.readFailed)
                assertTrue(uncaught.isEmpty())
            }
        } finally {
            latch.countDown()
            awaitNoElevatedThreads(10_000L)
        }
    }

    @Test fun drainSuccessBlockedStdinCloseIsOkWithFlag() {
        val latch = CountDownLatch(1)
        val proc = BlockedStdinSuccessProcess(latch, blockGetter = false)
        try {
            runWithUncaughtGuard { uncaught ->
                val resultRef = AtomicReference<ElevatedProcessIo.Drained?>()
                val bg = Thread {
                    resultRef.set(ElevatedProcessIo.drain(proc, 5_000L))
                }.also { it.isDaemon = true; it.start() }
                bg.join(15_000L)
                assertFalse("drain must return bounded despite blocked stdin close", bg.isAlive)
                val d = resultRef.get()
                assertNotNull(d)
                d!!
                assertFalse("timedOut must be false, got $d", d.timedOut)
                assertTrue("cleanupIncomplete expected, got $d", d.cleanupIncomplete)
                assertEquals("ok", String(d.stdout, Charsets.UTF_8))
                assertTrue(uncaught.isEmpty())
            }
        } finally {
            latch.countDown()
            awaitNoElevatedThreads(10_000L)
        }
    }

    private fun doRunnerSuccessBlockedStdin(
        blockGetter: Boolean,
        makeRunner: (spawn: (List<String>) -> Process) -> BinaryElevatedRunner,
    ) {
        val latch = CountDownLatch(1)
        // P1-2 預留（PR140 Phase1）：首 call 殘留 1 槽期間次 call 會被預留門拒，
        // 故先斷首 verdict，釋放收斂＋重置 breaker 後再斷 capture（clean Ok 非 null；
        // 重置屬測試隔離，非#136-B3修復）。
        val runner = makeRunner { BlockedStdinSuccessProcess(latch, blockGetter) }
        try {
            runWithUncaughtGuard { uncaught ->
                val resultRef = AtomicReference<ElevatedBinaryResult?>()
                val bg = Thread {
                    resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "Z1-C"), 5_000L))
                }.also { it.isDaemon = true; it.start() }
                bg.join(15_000L)
                assertFalse("runner must return bounded despite blocked stdin", bg.isAlive)
                val b = resultRef.get()
                assertTrue("$b", b is ElevatedBinaryResult.Ok)
                b as ElevatedBinaryResult.Ok
                assertEquals("ok", String(b.stdout, Charsets.UTF_8))
                assertTrue(uncaught.isEmpty())
            }
        } finally {
            latch.countDown()
            awaitNoElevatedThreads(10_000L)
        }
        // 收斂後重置（首 call linger 已計 unclean×1），再斷 capture 非 null。
        ElevatedCleanupTracker.noteClean()
        runWithUncaughtGuard { uncaught ->
            val audit = PrivilegeAuditLog()
            // B4：capture screencap 側須過 PNG 輕量門，故 capture 用乾淨合法 PNG
            // spawn（latch 已釋放，stdin 不再阻塞），斷非 null。
            // 輕量門代理（sig＋IEND），非解碼保證：僅證門通過，不證圖片可解碼。
            val png = RootFramebufferCapture.PNG_SIGNATURE +
                "z1-ok".toByteArray() +
                byteArrayOf(
                    0x00, 0x00, 0x00, 0x00,
                    0x49, 0x45, 0x4E, 0x44,
                    0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
                )
            val cleanRunner = makeRunner { FakeProcess(png, ByteArray(0), 0) }
            // capture 用乾淨 spawn（latch 已釋放，stdin 不再阻塞），斷非 null。
            val cap = RootFramebufferCapture.screencapViaBinary(cleanRunner, audit, "Z1-C", 5_000L)()
            assertNotNull("capture must be non-null for complete Ok", cap)
            assertTrue(uncaught.isEmpty())
        }
        awaitNoElevatedThreads(10_000L)
        ElevatedCleanupTracker.noteClean()
    }

    @Test fun rootSuccessBlockedStdinGetterIsOkAndCapture() {
        doRunnerSuccessBlockedStdin(blockGetter = true, makeRunner = ::makeRoot)
    }

    @Test fun shizukuSuccessBlockedStdinGetterIsOkAndCapture() {
        doRunnerSuccessBlockedStdin(blockGetter = true, makeRunner = ::makeShizuku)
    }

    /** (e) 專用：可中斷阻塞 stdin close 流（getter 立即返回，close 在 latch 上等待；中斷即抛）。 */
    private class InterruptibleBlockingStdinCloseOutput(private val latch: CountDownLatch) :
        ByteArrayOutputStream() {
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
     * (e) 專用：hang（waitFor 切片睡眠，被 destroy 即解）＋stdin close 阻塞；
     * getter 立即返回可寫流對象，close 在 latch 上等待（可中斷）；destroy 只標記，
     * 不釋放 latch（latch 僅測試 finally 釋放，證有界返回靠 stdin 有界關＋kill 路徑
     * interrupt，非靠 destroy 兜底釋放）。
     */
    private class HangBlockedStdinCloseProcess(
        private val latch: CountDownLatch,
    ) : Process() {
        @Volatile var destroyed = false
        override fun getOutputStream(): OutputStream = InterruptibleBlockingStdinCloseOutput(latch)
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (destroyed) return true
            try {
                Thread.sleep(unit.toMillis(timeout))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return destroyed
        }
        override fun exitValue(): Int = 0
        override fun destroy() {
            destroyed = true
        }
        override fun destroyForcibly(): Process {
            destroyed = true
            return this
        }
    }

    private fun doRunnerHangBlockedStdinClose(
        makeRunner: (spawn: (List<String>) -> Process) -> BinaryElevatedRunner,
    ) {
        val latch = CountDownLatch(1)
        // 刻意不注入 killer：走預設 killTree；latch 僅 finally 釋放。
        val runner = makeRunner { HangBlockedStdinCloseProcess(latch) }
        try {
            runWithUncaughtGuard { uncaught ->
                val resultRef = AtomicReference<ElevatedBinaryResult?>()
                val bg = Thread {
                    resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "Z1-E"), 300L))
                }.also { it.isDaemon = true; it.start() }
                bg.join(10_000L)
                assertFalse("runner must return bounded despite blocked stdin close", bg.isAlive)
                val b = resultRef.get()
                assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
                assertTrue(uncaught.isEmpty())
                val audit = PrivilegeAuditLog()
                assertNull(RootFramebufferCapture.screencapViaBinary(runner, audit, "Z1-E", 300L)())
                assertTrue(uncaught.isEmpty())
            }
        } finally {
            latch.countDown()
            awaitNoElevatedThreads(10_000L)
        }
    }

    @Test fun rootHangBlockedStdinCloseTimeoutIsTimedOut() {
        doRunnerHangBlockedStdinClose(::makeRoot)
    }

    @Test fun shizukuHangBlockedStdinCloseTimeoutIsTimedOut() {
        doRunnerHangBlockedStdinClose(::makeShizuku)
    }

    // (d) 永久 stall 模型（close 吞 interrupt 續睡 8s）：有界返回＋flag true。
    // daemon 殘留 8s，測完等待其退出再做 orphan 斷言，避免污染後測；放最後亦可，
    // 此處以等待收斂保證順序無關。
    @Test fun drainPermanentStall8sIsBoundedWithFlag() {
        val proc = PermanentStallProcess()
        runWithUncaughtGuard { uncaught ->
            val resultRef = AtomicReference<ElevatedProcessIo.Drained?>()
            val start = System.currentTimeMillis()
            val bg = Thread {
                resultRef.set(ElevatedProcessIo.drain(proc, 500L))
            }.also { it.isDaemon = true; it.start() }
            bg.join(25_000L)
            val elapsed = System.currentTimeMillis() - start
            assertFalse("drain must return bounded despite 8s stall", bg.isAlive)
                // 有界返回為主證（上已斷 bg 不存活）；上界 25s 僅 CI 餘量
                //（清理 budget 0.5s＋grace 0.5s＋kill等待快收＋gobbler join 5s ≈7s），絕不無界。
                assertTrue("elapsed=$elapsed", elapsed < 25_000L)
            val d = resultRef.get()
            assertNotNull(d)
            d!!
            assertTrue("timedOut expected, got $d", d.timedOut)
            assertTrue("cleanupIncomplete expected, got $d", d.cleanupIncomplete)
            assertTrue(uncaught.isEmpty())
            // daemon 殘留 8s：等待其退出再做 orphan 斷言（上界 15s：8s stall＋清理監督
            // join 餘量＋CI 抖動，避免污染後測）。
            awaitNoElevatedThreads(15_000L)
            assertTrue(uncaught.isEmpty())
        }
    }
}
