package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ShellDeny
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B3 有界外部呼叫迴歸（owner P1-5，PR140 Phase B3）。
 *
 * 覆蓋 Root／Shizuku × 阻塞 grant／status／suExists／spawn／wait × timeout／cancel，
 * 並確認單一 deadline（呼叫方有界返回）＋有限併發（便宜席位前置，拒絕不耗 quota）＋
 * 無界 stall 追蹤（殘留 `markLingering`＋`outstanding`，daemon 不懸掛呼叫方）＋
 * 無 orphan（`elevated-supervisor／wait／verify／stdout／stderr／stdin-close／cleanup`
 * 全收斂）。
 *
 * 全 fake，零真機、零 su、零 binder。阻塞體一律吞 interrupt 續等（模擬不可靠
 * Binder 中斷；可中斷 stalls 由既有 hang 語義覆，此處專測不可中斷殘留追蹤）。
 *
 * P1-2 誠實化（PR140 Phase 2）：blocked-spawn 超時即 `cleanupIncomplete=true`
 *（worker 殘留＝late Process 缺口，Binder 已送達不可撤回，worker 內有界補償）；
 * late 交付的確定性補償斷言（destroyed＋無 orphan＋tracker 收斂）見
 * `ElevatedRunnerPhase2LateSpawnTest`。
 */
class ElevatedRunnerB3Test {

    private val privateRoot = "/data/data/files"

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

    private fun awaitNoElevatedThreads(boundMs: Long = 10_000L) {
        val names = setOf(
            "elevated-stdout",
            "elevated-stderr",
            "elevated-stdin-close",
            "elevated-cleanup",
            "elevated-supervisor",
            "elevated-wait",
            "elevated-verify",
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

    /** 不可中斷阻塞（吞 interrupt 續等，模擬 Binder stall；釋放 latch 才返回）。 */
    private fun stuckAwait(latch: CountDownLatch) {
        while (true) {
            try {
                latch.await()
                break
            } catch (_: InterruptedException) {
                // 吞 interrupt 續等（不可靠中斷，呼叫方有界返回，worker 殘留追蹤）。
            }
        }
    }

    /** 不可中斷 waitFor stall 假進程（kill 後仍卡在 latch，殘留追蹤）。 */
    private class StuckWaitProcess(private val latch: CountDownLatch) : Process() {
        @Volatile var destroyed = false
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // 吞 interrupt 續等（Binder 不可靠中斷）。
                }
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

    private fun rootRunner(
        grant: () -> GrantCheck = GrantFixtures.allowAlways,
        suExists: (String) -> Boolean = { true },
        spawn: (List<String>) -> Process = { FakeProcess("ok".toByteArray()) },
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
        killer: (Process) -> Unit = { it.destroy() },
    ) = RootSuRunner(
        privateRoot = privateRoot,
        bridgeGranted = GrantFixtures.bridgeOn,
        activationCheck = GrantFixtures.activationOn,
        spawn = spawn,
        suBinary = "/system/bin/su",
        suExists = suExists,
        killer = killer,
        isCancelled = isCancelled,
        grantCheck = grant,
    )

    private fun shizukuRunner(
        grant: () -> GrantCheck = GrantFixtures.allowAlways,
        status: () -> ShizukuStatus = { ShizukuStatus.Available(15) },
        spawn: (List<String>) -> Process = { FakeProcess("ok".toByteArray()) },
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
        killer: (Process) -> Unit = { it.destroy() },
    ) = ShizukuShellRunner(
        privateRoot = privateRoot,
        bridgeGranted = GrantFixtures.bridgeOn,
        activationCheck = GrantFixtures.activationOn,
        pingBinder = { true },
        spawn = spawn,
        status = status,
        killer = killer,
        isCancelled = isCancelled,
        grantCheck = grant,
    )

    // ---- Root blocked grant × timeout/cancel ----

    @Test fun rootBlockedGrantTimeout() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        val latch = CountDownLatch(1)
        val spawns = AtomicInteger(0)
        val runner = rootRunner(
            grant = {
                stuckAwait(latch)
                GrantCheck.Valid
            },
            spawn = {
                spawns.incrementAndGet()
                FakeProcess("ok".toByteArray())
            },
        )
        try {
            val start = System.currentTimeMillis()
            val r = runner.runBinary(ElevatedRequest(listOf("id"), "B3-T"), 300L)
            val elapsed = System.currentTimeMillis() - start
            assertTrue("$r", r is ElevatedBinaryResult.TimedOut)
            assertEquals(0, spawns.get())
            assertFalse((r as ElevatedBinaryResult.TimedOut).cleanupIncomplete)
            // 不可中斷 stall 即殘留追蹤（outstanding 1），呼叫方有界返回（<5s）。
            assertTrue("elapsed=$elapsed", elapsed < 12_000L)
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
        pollActiveTo(0)
    }

    @Test fun rootBlockedGrantCancel() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val latch = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val spawns = AtomicInteger(0)
        val runner = rootRunner(
            grant = {
                entered.countDown()
                stuckAwait(latch)
                GrantCheck.Valid
            },
            spawn = {
                spawns.incrementAndGet()
                FakeProcess("ok".toByteArray())
            },
            isCancelled = { cancelled.get() },
        )
        try {
            var result: ElevatedBinaryResult? = null
            val bg = Thread { result = runner.runBinary(ElevatedRequest(listOf("id"), "B3-T"), 10_000L) }
                .also { it.isDaemon = true; it.start() }
            assertTrue(entered.await(5_000L, TimeUnit.MILLISECONDS))
            cancelled.set(true)
            bg.join(12_000L)
            assertFalse(bg.isAlive)
            val r = result
            assertTrue("$r", r is ElevatedBinaryResult.TimedOut)
            assertEquals(0, spawns.get())
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            cancelled.set(true)
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    // ---- Shizuku blocked status × timeout/cancel ----

    @Test fun shizukuBlockedStatusTimeout() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        val latch = CountDownLatch(1)
        val spawns = AtomicInteger(0)
        val runner = shizukuRunner(
            status = {
                stuckAwait(latch)
                ShizukuStatus.Available(15)
            },
            spawn = {
                spawns.incrementAndGet()
                FakeProcess("ok".toByteArray())
            },
        )
        try {
            val start = System.currentTimeMillis()
            val r = runner.runBinary(ElevatedRequest(listOf("id"), "B3-T"), 300L)
            val elapsed = System.currentTimeMillis() - start
            assertTrue("$r", r is ElevatedBinaryResult.TimedOut)
            assertEquals(0, spawns.get())
            assertTrue("elapsed=$elapsed", elapsed < 12_000L)
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
        } finally {
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    @Test fun shizukuBlockedStatusCancel() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val latch = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val spawns = AtomicInteger(0)
        val runner = shizukuRunner(
            status = {
                entered.countDown()
                stuckAwait(latch)
                ShizukuStatus.Available(15)
            },
            spawn = {
                spawns.incrementAndGet()
                FakeProcess("ok".toByteArray())
            },
            isCancelled = { cancelled.get() },
        )
        try {
            var result: ElevatedBinaryResult? = null
            val bg = Thread { result = runner.runBinary(ElevatedRequest(listOf("id"), "B3-T"), 10_000L) }
                .also { it.isDaemon = true; it.start() }
            assertTrue(entered.await(5_000L, TimeUnit.MILLISECONDS))
            cancelled.set(true)
            bg.join(12_000L)
            assertFalse(bg.isAlive)
            assertTrue("${result}", result is ElevatedBinaryResult.TimedOut)
            assertEquals(0, spawns.get())
        } finally {
            cancelled.set(true)
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    // ---- Root blocked suExists × timeout/cancel ----

    @Test fun rootBlockedSuExistsTimeout() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val latch = CountDownLatch(1)
        val spawns = AtomicInteger(0)
        // 顯式 suBinary 為 null 即走自動解析（suExists 阻塞）；此處用自動路徑。
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            spawn = {
                spawns.incrementAndGet()
                FakeProcess("ok".toByteArray())
            },
            suExists = {
                stuckAwait(latch)
                true
            },
            grantCheck = GrantFixtures.allowAlways,
        )
        try {
            val start = System.currentTimeMillis()
            val r = runner.runBinary(ElevatedRequest(listOf("id"), "B3-T"), 300L)
            val elapsed = System.currentTimeMillis() - start
            assertTrue("$r", r is ElevatedBinaryResult.TimedOut)
            assertEquals(0, spawns.get())
            assertTrue("elapsed=$elapsed", elapsed < 12_000L)
            assertEquals(1, ElevatedCleanupTracker.outstanding())
        } finally {
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    // ---- Blocked spawn × timeout/cancel (Root/Shizuku) ----

    @Test fun rootBlockedSpawnTimeout() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val latch = CountDownLatch(1)
        val spawns = AtomicInteger(0)
        var kills = 0
        val runner = rootRunner(
            spawn = {
                spawns.incrementAndGet()
                stuckAwait(latch)
                FakeProcess("ok".toByteArray())
            },
            killer = {
                kills++
                it.destroy()
            },
        )
        try {
            val start = System.currentTimeMillis()
            val r = runner.runBinary(ElevatedRequest(listOf("id"), "B3-T"), 300L)
            val elapsed = System.currentTimeMillis() - start
            assertTrue("$r", r is ElevatedBinaryResult.TimedOut)
            // P1-2 誠實化（PR140 Phase 2）：`kills==0` 僅表示回傳前無進程可殺，
            // 不再聲稱「未建成」——逾時後 worker 仍可能交付 late Process（Binder
            // 已送達不可撤回），由 worker 內有界補償；本次即
            // `cleanupIncomplete=true`（見 Phase2 late-spawn 迴歸）。
            assertEquals(1, spawns.get())
            assertEquals(0, kills)
            assertTrue(
                "spawn supervisor linger must flag incomplete, got $r",
                (r as ElevatedBinaryResult.TimedOut).cleanupIncomplete,
            )
            assertTrue("elapsed=$elapsed", elapsed < 12_000L)
            assertEquals(1, ElevatedCleanupTracker.outstanding())
        } finally {
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    @Test fun shizukuBlockedSpawnTimeout() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val latch = CountDownLatch(1)
        val spawns = AtomicInteger(0)
        val runner = shizukuRunner(
            spawn = {
                spawns.incrementAndGet()
                stuckAwait(latch)
                FakeProcess("ok".toByteArray())
            },
        )
        try {
            val r = runner.runBinary(ElevatedRequest(listOf("id"), "B3-T"), 300L)
            assertTrue("$r", r is ElevatedBinaryResult.TimedOut)
            assertEquals(1, spawns.get())
            // P1-2 誠實化（同 root 側）：spawn worker 殘留即 late 缺口，旗標 true。
            assertTrue(
                "spawn supervisor linger must flag incomplete, got $r",
                (r as ElevatedBinaryResult.TimedOut).cleanupIncomplete,
            )
            assertEquals(1, ElevatedCleanupTracker.outstanding())
        } finally {
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    @Test fun rootBlockedSpawnCancel() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val latch = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val spawns = AtomicInteger(0)
        val runner = rootRunner(
            spawn = {
                spawns.incrementAndGet()
                entered.countDown()
                stuckAwait(latch)
                FakeProcess("ok".toByteArray())
            },
            isCancelled = { cancelled.get() },
        )
        try {
            var result: ElevatedBinaryResult? = null
            val bg = Thread { result = runner.runBinary(ElevatedRequest(listOf("id"), "B3-T"), 10_000L) }
                .also { it.isDaemon = true; it.start() }
            assertTrue(entered.await(5_000L, TimeUnit.MILLISECONDS))
            cancelled.set(true)
            bg.join(12_000L)
            assertFalse(bg.isAlive)
            assertTrue("$result", result is ElevatedBinaryResult.TimedOut)
        } finally {
            cancelled.set(true)
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    @Test fun shizukuBlockedSpawnCancel() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val latch = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val spawns = AtomicInteger(0)
        val runner = shizukuRunner(
            spawn = {
                spawns.incrementAndGet()
                entered.countDown()
                stuckAwait(latch)
                FakeProcess("ok".toByteArray())
            },
            isCancelled = { cancelled.get() },
        )
        try {
            var result: ElevatedBinaryResult? = null
            val bg = Thread { result = runner.runBinary(ElevatedRequest(listOf("id"), "B3-T"), 10_000L) }
                .also { it.isDaemon = true; it.start() }
            assertTrue(entered.await(5_000L, TimeUnit.MILLISECONDS))
            cancelled.set(true)
            bg.join(12_000L)
            assertFalse(bg.isAlive)
            assertTrue("$result", result is ElevatedBinaryResult.TimedOut)
        } finally {
            cancelled.set(true)
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    // ---- Blocked wait (remote waitFor stall) × timeout/cancel ----

    @Test fun rootBlockedWaitTimeout() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val latch = CountDownLatch(1)
        var kills = 0
        val runner = rootRunner(
            spawn = { StuckWaitProcess(latch) },
            killer = {
                kills++
                it.destroy()
            },
        )
        try {
            val start = System.currentTimeMillis()
            val r = runner.runBinary(ElevatedRequest(listOf("id"), "B3-T"), 300L)
            val elapsed = System.currentTimeMillis() - start
            assertTrue("$r", r is ElevatedBinaryResult.TimedOut)
            assertEquals(1, kills)
            assertTrue("elapsed=$elapsed", elapsed < 12_000L)
            // wait stall＋verify stall 殘留（outstanding>=1），verdict 仍 TimedOut。
            assertTrue("${ElevatedCleanupTracker.outstanding()}", ElevatedCleanupTracker.outstanding() >= 1)
            assertTrue((r as ElevatedBinaryResult.TimedOut).cleanupIncomplete)
        } finally {
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    @Test fun shizukuBlockedWaitTimeout() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val latch = CountDownLatch(1)
        var kills = 0
        val runner = shizukuRunner(
            spawn = { ShizukuRemoteProcess.wrap(StuckWaitRemote(latch)) },
            killer = {
                kills++
                it.destroy()
            },
        )
        try {
            val r = runner.runBinary(ElevatedRequest(listOf("id"), "B3-T"), 300L)
            assertTrue("$r", r is ElevatedBinaryResult.TimedOut)
            assertEquals(1, kills)
            assertTrue("${ElevatedCleanupTracker.outstanding()}", ElevatedCleanupTracker.outstanding() >= 1)
        } finally {
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    /** 遠端 wait stall 假物件（非 Process，直 cast 必 CCE，經包裝測 Binder 語義）。 */
    private class StuckWaitRemote(private val latch: CountDownLatch) {
        @Volatile var destroyed = false
        fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        fun waitFor(): Int = 0
        fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // 吞 interrupt 續等（Binder 不可靠中斷）。
                }
            }
            return destroyed
        }
        fun exitValue(): Int = 0
        fun destroy() {
            destroyed = true
        }
    }

    @Test fun rootBlockedWaitCancel() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val latch = CountDownLatch(1)
        val job = kotlinx.coroutines.Job()
        var kills = 0
        val runner = rootRunner(
            spawn = { StuckWaitProcess(latch) },
            killer = {
                kills++
                it.destroy()
            },
            isCancelled = { !job.isActive },
        )
        try {
            var result: ElevatedBinaryResult? = null
            val bg = Thread { result = runner.runBinary(ElevatedRequest(listOf("id"), "B3-T"), 10_000L) }
                .also { it.isDaemon = true; it.start() }
            Thread.sleep(200L)
            job.cancel()
            bg.join(12_000L)
            assertFalse(bg.isAlive)
            assertTrue("$result", result is ElevatedBinaryResult.TimedOut)
            assertEquals(1, kills)
        } finally {
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    @Test fun shizukuBlockedWaitCancel() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val latch = CountDownLatch(1)
        val job = kotlinx.coroutines.Job()
        var kills = 0
        val runner = shizukuRunner(
            spawn = { ShizukuRemoteProcess.wrap(StuckWaitRemote(latch)) },
            killer = {
                kills++
                it.destroy()
            },
            isCancelled = { !job.isActive },
        )
        try {
            var result: ElevatedBinaryResult? = null
            val bg = Thread { result = runner.runBinary(ElevatedRequest(listOf("id"), "B3-T"), 10_000L) }
                .also { it.isDaemon = true; it.start() }
            Thread.sleep(200L)
            job.cancel()
            bg.join(12_000L)
            assertFalse(bg.isAlive)
            assertTrue("$result", result is ElevatedBinaryResult.TimedOut)
            assertEquals(1, kills)
        } finally {
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    // ---- No unlimited blocked callers (8 concurrent, 1 slot) ----

    @Test fun rootBlockedStatusNoUnlimitedCallers() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        // Root 以 grant 阻塞代表「准入前阻塞」反例（Shizuku 側另測 status）：
        // 8 併發＋永久 latch，舊碼 8 全卡准入前（active==0）timeout 不生效；
        // 新碼便宜席位前置（實效串行，乾淨態僅 1 席），1 阻塞＋7 快拒。
        val latch = CountDownLatch(1)
        val spawns = AtomicInteger(0)
        val runner = rootRunner(
            grant = {
                stuckAwait(latch)
                GrantCheck.Valid
            },
            spawn = {
                spawns.incrementAndGet()
                FakeProcess("ok".toByteArray())
            },
        )
        try {
            val n = 8
            val results = arrayOfNulls<ElevatedBinaryResult>(n)
            val startGate = CountDownLatch(1)
            val threads = (0 until n).map { i ->
                Thread {
                    try {
                        startGate.await()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    results[i] = runner.runBinary(ElevatedRequest(listOf("id"), "B3-T"), 500L)
                }.also { it.isDaemon = true; it.start() }
            }
            startGate.countDown()
            threads.forEach { it.join(15_000L) }
            assertTrue(threads.none { it.isAlive })
            assertEquals(0, spawns.get())
            // 7 快拒（QUOTA_EXCEEDED backlog），1 超時（TimedOut）；皆有界返回。
            val denied = results.filterIsInstance<ElevatedBinaryResult.Denied>()
            val timedOut = results.filterIsInstance<ElevatedBinaryResult.TimedOut>()
            assertEquals("results=${results.toList()}", n, denied.size + timedOut.size)
            assertTrue("denied=${denied.size}", denied.isNotEmpty())
            assertTrue("timedOut=${timedOut.size}", timedOut.isNotEmpty())
            assertTrue(denied.all { it.reason == ShellDeny.QUOTA_EXCEEDED })
            // 有限併發：active 峰值有界（此處終態 0，過程中至多 1；8 全卡即 unlimited）。
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            // 無界 stall 追蹤：殘留有界（1 阻塞 worker），非 8 全殘留。
            assertTrue("outstanding=${ElevatedCleanupTracker.outstanding()}", ElevatedCleanupTracker.outstanding() <= 2)
            // 拒絕路徑不耗 quota：此處以零 spawn 斷言（配額未耗由零 spawn 體現）。
        } finally {
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
    }

    @Test fun shizukuBlockedStatusNoUnlimitedCallers() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        val latch = CountDownLatch(1)
        val spawns = AtomicInteger(0)
        val runner = shizukuRunner(
            status = {
                stuckAwait(latch)
                ShizukuStatus.Available(15)
            },
            spawn = {
                spawns.incrementAndGet()
                FakeProcess("ok".toByteArray())
            },
        )
        try {
            val n = 8
            val results = arrayOfNulls<ElevatedBinaryResult>(n)
            val startGate = CountDownLatch(1)
            val threads = (0 until n).map { i ->
                Thread {
                    try {
                        startGate.await()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    results[i] = runner.runBinary(ElevatedRequest(listOf("id"), "B3-T"), 500L)
                }.also { it.isDaemon = true; it.start() }
            }
            startGate.countDown()
            threads.forEach { it.join(15_000L) }
            assertTrue(threads.none { it.isAlive })
            assertEquals(0, spawns.get())
            val denied = results.filterIsInstance<ElevatedBinaryResult.Denied>()
            val timedOut = results.filterIsInstance<ElevatedBinaryResult.TimedOut>()
            assertEquals("results=${results.toList()}", n, denied.size + timedOut.size)
            assertTrue(denied.isNotEmpty())
            assertTrue(timedOut.isNotEmpty())
            assertTrue(denied.all { it.reason == ShellDeny.QUOTA_EXCEEDED })
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            assertTrue("outstanding=${ElevatedCleanupTracker.outstanding()}", ElevatedCleanupTracker.outstanding() <= 2)
        } finally {
            latch.countDown()
            pollOutstandingTo(0)
            ElevatedCleanupTracker.noteClean()
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
    }
}
