package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.PrivilegeAuditLog
import dev.librepocket.shell.ShellExecTool
import dev.librepocket.shell.ShellResult
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
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
 * Round-2 Phase C 迴歸（review P2-1＋P2-2，PR #140）：
 * - P2-1 取消 hook 有界化：blocked cancel supplier（latch 永不放，吞 interrupt 續等，
 *   模擬以 blocking store 查詢充當 cancel）不能無限卡 caller。入口 preflight
 *   （deadline 前、尚未准入、`activeCalls==0`）＋配額前／後三門一律經
 *   [ElevatedRunnerCommon.checkPreSpawnCancel]（hook 只跑監督 worker，呼叫側只看
 *   中斷＋deadline），Expired→preSpawnExpired 有界回（timeoutMs＋grace 量級），
 *   零 spawn、不佔席（配額前／後門已占席由 finally 有界釋放）。兩 runner 雙軌。
 * - P2-2 tail verdict：drain 層 finished-tail 中止直接斷言（`timedOut` 仍為
 *   `!finished` 不動＋`tailIncomplete` 真＋exit 0，命令 exit vs request finished
 *   分開）；runner→tool→audit 鏈（`executeElevated`＋`TIMEOUT` 審計）兩 runner 雙軌
 *   （runner→TimedOut 本體由 `ElevatedRunnerPhase3HardeningTest` tail 雙測鎖定，
 *   此處鎖鏈路 verdict 與審計，不只看 truncated）。
 *
 * 全 fake，零真機、零 su、零 binder。阻塞體一律吞 interrupt 續等（模擬不可靠
 * Binder 中斷；呼叫方有界返回，worker 殘留追蹤，finally 釋放 latch 收斂）。
 */
class ElevatedRunnerPhaseCTest {

    private val privateRoot = "/data/data/files"

    @Before fun setUpIsolation() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
    }

    @After fun tearDownIsolation() {
        pollOutstandingTo(0)
        pollActiveTo(0)
        ElevatedCleanupTracker.clearUnverified()
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

    /** 不可中斷阻塞 hook（吞 interrupt 續等，模擬 blocking store 充當 cancel）。 */
    private fun stuckCancelHook(latch: CountDownLatch): Boolean {
        while (true) {
            try {
                latch.await()
                break
            } catch (_: InterruptedException) {
                // 吞 interrupt 續等：呼叫方靠 deadline＋grace 有界回，worker 殘留追蹤。
            }
        }
        return false
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

    /** 已 exit 但雙流永不 EOF（finished tail 用；latch 釋放後收斂 EOF）。 */
    private class ExitedStuckStreamsProcess(private val latch: CountDownLatch) : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = UninterruptibleReadInput(latch)
        override fun getErrorStream(): InputStream = UninterruptibleReadInput(latch)
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = true
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }

    // ---- P2-1：入口 blocked cancel supplier 有界（零 spawn、不佔席） ----

    private fun doBlockedCancelEntry(
        makeRunner: ((() -> Boolean), AtomicInteger) -> BinaryElevatedRunner,
    ) {
        val latch = CountDownLatch(1)
        val spawns = AtomicInteger(0)
        val runner = makeRunner({ stuckCancelHook(latch) }, spawns)
        try {
            val resultRef = AtomicReference<ElevatedBinaryResult?>()
            val start = System.currentTimeMillis()
            // timeoutMs=100（review 原例）：舊碼首個直調 hook 在 deadline 前，
            // 永不放的 latch 即永不返回；現 deadline＋grace 量級有界回。
            val bg = Thread {
                resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "P2C-CANCEL"), 100L))
            }.also { it.isDaemon = true; it.start() }
            bg.join(15_000L)
            val elapsed = System.currentTimeMillis() - start
            assertFalse("blocked cancel hook must not hang caller", bg.isAlive)
            assertTrue("elapsed=$elapsed must stay near timeout+grace, not hang", elapsed < 8_000L)
            val b = resultRef.get()
            assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
            b as ElevatedBinaryResult.TimedOut
            assertEquals("TIMEOUT", b.verdict())
            assertEquals("blocked entry must not spawn", 0, spawns.get())
            // 不佔席：入口門在准入之前，Expired 直接回，席位從未佔用。
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            // 殘留追蹤：吞 interrupt 的 hook worker 持 1 槽＋breaker 計 1（不熔斷）。
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            assertEquals(1, ElevatedCleanupTracker.consecutiveCount())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
        } finally {
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
    }

    @Test fun rootBlockedCancelEntryBoundedWithoutSpawnOrSeat() {
        doBlockedCancelEntry { isCancelled, spawns ->
            RootSuRunner(
                privateRoot = privateRoot,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                spawn = {
                    spawns.incrementAndGet()
                    FakeProcess("ok".toByteArray())
                },
                suBinary = "/system/bin/su",
                isCancelled = isCancelled,
                grantCheck = GrantFixtures.allowAlways,
            )
        }
    }

    @Test fun shizukuBlockedCancelEntryBoundedWithoutSpawnOrSeat() {
        doBlockedCancelEntry { isCancelled, spawns ->
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
                isCancelled = isCancelled,
                grantCheck = GrantFixtures.allowAlways,
            )
        }
    }

    // ---- P2-1：配額窗口 blocked cancel 有界（零 spawn、佔席有界釋放） ----

    private fun doBlockedCancelAtQuotaWindow(
        makeRunner: ((() -> Boolean), AtomicInteger) -> BinaryElevatedRunner,
    ) {
        // 首調 false（入口放行），之後永阻塞：命中配額前有界門。
        // 前提：入口→配額前之間各監督體皆快路徑（首 POLL 內完，不額外調鉤），
        // 故第 2 調必為配額前門（µs 體 vs 20ms POLL，餘量充足）。
        val latch = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val spawns = AtomicInteger(0)
        val runner = makeRunner(
            {
                if (calls.incrementAndGet() == 1) {
                    false
                } else {
                    stuckCancelHook(latch)
                }
            },
            spawns,
        )
        try {
            val resultRef = AtomicReference<ElevatedBinaryResult?>()
            val start = System.currentTimeMillis()
            val bg = Thread {
                resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "P2C-CANCEL"), 500L))
            }.also { it.isDaemon = true; it.start() }
            bg.join(15_000L)
            val elapsed = System.currentTimeMillis() - start
            assertFalse("blocked quota-window hook must not hang caller", bg.isAlive)
            assertTrue("elapsed=$elapsed", elapsed < 8_000L)
            val b = resultRef.get()
            assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
            assertEquals("blocked quota window must not spawn", 0, spawns.get())
            // 佔席有界釋放：配額前門已占席，Expired 經 finally 釋席。
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
        } finally {
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
    }

    @Test fun rootBlockedCancelAtQuotaWindowReleasesSeat() {
        doBlockedCancelAtQuotaWindow { isCancelled, spawns ->
            RootSuRunner(
                privateRoot = privateRoot,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                spawn = {
                    spawns.incrementAndGet()
                    FakeProcess("ok".toByteArray())
                },
                suBinary = "/system/bin/su",
                isCancelled = isCancelled,
                grantCheck = GrantFixtures.allowAlways,
            )
        }
    }

    @Test fun shizukuBlockedCancelAtQuotaWindowReleasesSeat() {
        doBlockedCancelAtQuotaWindow { isCancelled, spawns ->
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
                isCancelled = isCancelled,
                grantCheck = GrantFixtures.allowAlways,
            )
        }
    }

    // ---- P2-2：drain finished-tail 中止直接語義（命令 exit vs request finished） ----

    @Test fun drainFinishedTailTimeoutReportsTailIncomplete() {
        // 已 exit 但雙流不 EOF＋極短 timeout：tail budget 耗盡中止。
        // `timedOut` 仍為 `!finished`=false（命令 exit 語義不動），
        // `tailIncomplete` 真（request 未完）；runner 側升級 TimedOut
        // （見 Phase3HardeningTest tail 雙測），此處鎖 drain 層分開語義。
        val latch = CountDownLatch(1)
        try {
            val d = ElevatedProcessIo.drain(ExitedStuckStreamsProcess(latch), 300L)
            assertFalse("finished path must keep timedOut=false, got $d", d.timedOut)
            assertTrue("aborted tail must set tailIncomplete: $d", d.tailIncomplete)
            assertEquals("command exit stays 0: $d", 0, d.exitCode)
            assertTrue("aborted tail must truncate: $d", d.truncated)
            assertTrue("aborted tail must flag incomplete: $d", d.cleanupIncomplete)
            assertFalse("swallowing streams never throw: $d", d.readFailed)
            assertEquals(2, ElevatedCleanupTracker.outstanding())
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    // ---- P2-2：runner→tool→audit 鏈（verdict＋審計，雙軌） ----

    private fun doTailCancelAuditsTimeout(
        makeRunner: ((List<String>) -> Process, () -> Boolean) -> BinaryElevatedRunner,
    ) {
        val latch = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val runner = makeRunner({ ExitedStuckStreamsProcess(latch) }, { cancelled.get() })
        try {
            val audit = PrivilegeAuditLog()
            val resultRef = AtomicReference<ShellResult?>()
            val start = System.currentTimeMillis()
            val bg = Thread {
                resultRef.set(
                    ShellExecTool.executeElevated(
                        ElevatedRequest(listOf("id"), "P2C-TAIL"),
                        runner,
                        audit,
                        10_000L,
                    ),
                )
            }.also { it.isDaemon = true; it.start() }
            // waitFor 即回 true（~ms 即 finished），600ms 後取消必落於 tail 段。
            Thread.sleep(600L)
            cancelled.set(true)
            bg.join(15_000L)
            val elapsed = System.currentTimeMillis() - start
            assertFalse("tail must abort on cancel", bg.isAlive)
            assertTrue("elapsed=$elapsed", elapsed < 8_000L)
            val r = resultRef.get()
            // 上層僅看 Ok 即完成：tail 取消必須是 TimedOut（攜 partial），不得是 Ok；
            // 審計記 TIMEOUT（非 OK），不只看 truncated。
            assertTrue("$r", r is ShellResult.TimedOut)
            assertEquals("TIMEOUT", PrivilegeAuditLog.verdictName(r!!))
            assertEquals(1, audit.size())
            assertEquals(mapOf("TIMEOUT" to 1), audit.countByVerdict())
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            cancelled.set(true)
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    @Test fun rootTailCancelAuditsTimeout() {
        doTailCancelAuditsTimeout { spawn, isCancelled ->
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

    @Test fun shizukuTailCancelAuditsTimeout() {
        doTailCancelAuditsTimeout { spawn, isCancelled ->
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
}
