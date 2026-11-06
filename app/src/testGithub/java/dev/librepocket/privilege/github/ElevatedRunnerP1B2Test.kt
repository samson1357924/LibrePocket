package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * B2 P1-3 post-kill 驗證＋P1-4 spawn 前取消（owner 新 P1-3／P1-4，PR140 Phase B2）。
 *
 * P1-3：killer 真≠進程真死（destroy／destroyForcibly 全 runCatching，遠端 destroy
 * 吞失敗；cleanup worker 離開只代表本機 thread 結束）。drain kill 路徑經
 * `verifyParentStopped`（`waitFor` 回傳／liveness，binder 不可達視 unknown）驗證
 * 父進程，失敗或不可驗證即 `cleanupIncomplete=true` 並進資源／breaker 終審，
 * 不因 killer 未抛而宣稱 cleaned。子孫全滅仍為真機 blocker（JVM 真樹已驗，
 * 裝置 SELinux／Root 語義差異 E2E 歸 #136-B3）。
 *
 * P1-4：任何 spawn 前檢查取消（入口＋最後授權重驗後配額前＋配額→spawn 窗口三點，
 * 見兩 runner；Thread 中斷不清旗標或鉤真即已取消；鉤抛即已取消，未建 fail-closed），
 * 任一命中即零 spawn 回 TimedOut（空 partial，無殘留；interrupt 原樣保留，不清除，
 * 不把已取消翻成未取消）。drain 層入口清中斷＋健康收尾語義不變（drain 不吞中斷，
 * 出口恢復），runner 層已取消一律不建（與 P2 改名測試分工）。
 *
 * 全 fake，零真機、零 su、零 binder。post-wait 假件一律立即回 false／抛（不睡滿
 * 5s grace），驗證語義不變且測試有界（單測不為 5s 等待付費）。
 */
class ElevatedRunnerP1B2Test {

    private val privateRoot = "/data/data/files"

    @Before fun setUpIsolation() {
        ElevatedCleanupTracker.noteClean()
    }

    @After fun tearDownIsolation() {
        ElevatedCleanupTracker.noteClean()
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

    private fun makeRoot(
        spawn: (List<String>) -> Process,
        killer: (Process) -> Unit = { ElevatedProcessKiller.killTree(it) },
        isCancelled: () -> Boolean = { false },
    ): RootSuRunner = RootSuRunner(
        privateRoot = privateRoot,
        bridgeGranted = GrantFixtures.bridgeOn,
        activationCheck = GrantFixtures.activationOn,
        spawn = spawn,
        suBinary = "/system/bin/su",
        killer = killer,
        isCancelled = isCancelled,
        grantCheck = GrantFixtures.allowAlways,
    )

    private fun makeShizuku(
        spawn: (List<String>) -> Process,
        killer: (Process) -> Unit = { ElevatedProcessKiller.killTree(it) },
        isCancelled: () -> Boolean = { false },
    ): ShizukuShellRunner = ShizukuShellRunner(
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

    // ---- P1-3 假件（立即回，不睡 grace） ----

    /** destroy no-op（不標記）＋waitFor 立即 false：killer 調了等於沒調。 */
    private class NoOpHangProcess : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = false
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }

    /** destroy／destroyForcibly 皆抛＋waitFor 立即 false：吞抛≠已停。 */
    private class ThrowingDestroyProcess : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = false
        override fun exitValue(): Int = 0
        override fun destroy(): Unit = throw RuntimeException("destroy boom")
        override fun destroyForcibly(): Process = throw RuntimeException("forcibly boom")
    }

    /** destroy 成功置旗但 waitFor 仍立即 false：killer 真≠已停（post-wait false）。 */
    private class UnkillableWaitFalseProcess : Process() {
        var destroyed = false
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = false
        override fun exitValue(): Int = 0
        override fun destroy() {
            destroyed = true
        }
        override fun destroyForcibly(): Process {
            destroyed = true
            return this
        }
    }

    /** binder 死亡模型：waitFor／isAlive／exitValue 全抛（unknown→incomplete）。 */
    private class BinderDiedProcess : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = throw RuntimeException("binder died")
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean =
            throw RuntimeException("binder died")
        override fun exitValue(): Int = throw RuntimeException("binder died")
        override fun isAlive(): Boolean = throw RuntimeException("binder died")
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }

    /** 可殺模型：destroy 置旗，waitFor 即回旗標（驗證可過，宣告無殘留才準）。 */
    private class KillableImmediateProcess : Process() {
        var destroyed = false
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = destroyed
        override fun exitValue(): Int = 0
        override fun destroy() {
            destroyed = true
        }
        override fun destroyForcibly(): Process {
            destroyed = true
            return this
        }
    }

    /** 遠端 hang：waitFor 即回 destroyed（立即，不睡 grace）。 */
    private open class HangRemote(var destroyed: Boolean = false) {
        fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        fun waitFor(): Int = 0
        open fun waitFor(timeout: Long, unit: TimeUnit): Boolean = destroyed
        fun exitValue(): Int = 0
        open fun destroy() {
            destroyed = true
        }
    }

    /** 遠端 destroy 抛（吞≠已停）。 */
    private class ThrowingDestroyRemote : HangRemote() {
        override fun destroy(): Unit = throw RuntimeException("remote destroy boom")
    }

    /** 遠端 no-op destroy（不置旗）。 */
    private class NoOpDestroyRemote : HangRemote() {
        override fun destroy() = Unit
    }

    /** 遠端 binder 死亡：waitFor／exitValue 全抛。 */
    private class BinderDiedRemote {
        fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        fun waitFor(): Int = throw RuntimeException("binder died")
        fun waitFor(timeout: Long, unit: TimeUnit): Boolean =
            throw RuntimeException("binder died")
        fun exitValue(): Int = throw RuntimeException("binder died")
        fun destroy() = Unit
    }

    // ---- P1-3：Root／Shizuku × destroy throws／no-op／post-wait=false ----

    @Test fun rootNoOpKillerPostWaitFalseMarksIncomplete() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val before = ElevatedCleanupTracker.consecutiveCount()
        val runner = makeRoot(
            spawn = { NoOpHangProcess() },
            killer = { /* no-op：調了等於沒調 */ },
        )
        val b = runner.runBinary(ElevatedRequest(listOf("id"), "P1-3-T"), 300L)
        assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
        b as ElevatedBinaryResult.TimedOut
        assertTrue("no-op kill must flag incomplete", b.cleanupIncomplete)
        pollOutstandingTo(0)
        assertTrue(ElevatedCleanupTracker.consecutiveCount() > before)
        assertFalse(ElevatedCleanupTracker.isSaturated())
    }

    @Test fun shizukuNoOpKillerPostWaitFalseMarksIncomplete() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val before = ElevatedCleanupTracker.consecutiveCount()
        val runner = makeShizuku(
            spawn = { NoOpHangProcess() },
            killer = { /* no-op */ },
        )
        val b = runner.runBinary(ElevatedRequest(listOf("id"), "P1-3-T"), 300L)
        assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
        assertTrue((b as ElevatedBinaryResult.TimedOut).cleanupIncomplete)
        pollOutstandingTo(0)
        assertTrue(ElevatedCleanupTracker.consecutiveCount() > before)
    }

    @Test fun rootDestroyThrowsStillIncomplete() {
        // 預設 killer（killTree 全 runCatching 吞抛）＋本地 destroy 抛：吞≠已停。
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val runner = makeRoot(spawn = { ThrowingDestroyProcess() })
        val b = runner.runBinary(ElevatedRequest(listOf("id"), "P1-3-T"), 300L)
        assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
        assertTrue((b as ElevatedBinaryResult.TimedOut).cleanupIncomplete)
        pollOutstandingTo(0)
        assertFalse(ElevatedCleanupTracker.isSaturated())
    }

    @Test fun shizukuRemoteDestroyThrowsStillIncomplete() {
        // 遠端 destroy 抛經雙層 best-effort 吞，waitFor 仍 false → incomplete。
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val runner = makeShizuku(
            spawn = { ShizukuRemoteProcess.wrap(ThrowingDestroyRemote()) },
        )
        val b = runner.runBinary(ElevatedRequest(listOf("id"), "P1-3-T"), 300L)
        assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
        assertTrue((b as ElevatedBinaryResult.TimedOut).cleanupIncomplete)
        pollOutstandingTo(0)
        assertFalse(ElevatedCleanupTracker.isSaturated())
    }

    @Test fun shizukuRemoteNoOpDestroyStillIncomplete() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val runner = makeShizuku(
            spawn = { ShizukuRemoteProcess.wrap(NoOpDestroyRemote()) },
        )
        val b = runner.runBinary(ElevatedRequest(listOf("id"), "P1-3-T"), 300L)
        assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
        assertTrue((b as ElevatedBinaryResult.TimedOut).cleanupIncomplete)
        pollOutstandingTo(0)
    }

    @Test fun rootPostWaitFalseDespiteDestroyMarksIncomplete() {
        // killer 成功置旗（destroyed=true）但 post-wait 仍 false：killer 真≠已停。
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        var victim: UnkillableWaitFalseProcess? = null
        val runner = makeRoot(
            spawn = {
                UnkillableWaitFalseProcess().also { victim = it }
            },
            killer = { it.destroy() },
        )
        val b = runner.runBinary(ElevatedRequest(listOf("id"), "P1-3-T"), 300L)
        assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
        assertTrue("post-wait false must flag even though destroy ran", (b as ElevatedBinaryResult.TimedOut).cleanupIncomplete)
        assertTrue(victim?.destroyed == true)
        pollOutstandingTo(0)
    }

    @Test fun binderDiedUnknownMarksIncomplete() {
        // waitFor／isAlive 全抛（binder 不可達視 unknown）：兩 runner 皆 incomplete。
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val root = makeRoot(spawn = { BinderDiedProcess() })
        val rb = root.runBinary(ElevatedRequest(listOf("id"), "P1-3-T"), 300L)
        assertTrue("$rb", rb is ElevatedBinaryResult.TimedOut)
        assertTrue((rb as ElevatedBinaryResult.TimedOut).cleanupIncomplete)
        pollOutstandingTo(0)

        val shizuku = makeShizuku(
            spawn = { ShizukuRemoteProcess.wrap(BinderDiedRemote()) },
        )
        val sb = shizuku.runBinary(ElevatedRequest(listOf("id"), "P1-3-T"), 300L)
        assertTrue("$sb", sb is ElevatedBinaryResult.TimedOut)
        assertTrue((sb as ElevatedBinaryResult.TimedOut).cleanupIncomplete)
        pollOutstandingTo(0)
        assertFalse(ElevatedCleanupTracker.isSaturated())
    }

    @Test fun verifiedKillMarksCleanNoResidue() {
        // 可驗證已停（destroy 置旗＋waitFor 真）→ 無殘留宣告為真，不計 breaker。
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val before = ElevatedCleanupTracker.consecutiveCount()
        for (make in listOf<(spawn: (List<String>) -> Process) -> BinaryElevatedRunner>(
            { s -> makeRoot(s, killer = { it.destroy() }) },
            { s -> makeShizuku(s, killer = { it.destroy() }) },
        )) {
            val runner = make { KillableImmediateProcess() }
            val b = runner.runBinary(ElevatedRequest(listOf("id"), "P1-3-T"), 300L)
            assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
            // 驗證已停：killer 置旗＋waitFor 真＋gobbler 已 EOF，故不殘留。
            assertFalse("$b must not flag when verified stopped", (b as ElevatedBinaryResult.TimedOut).cleanupIncomplete)
        }
        pollOutstandingTo(0)
        assertEquals(before, ElevatedCleanupTracker.consecutiveCount())
        assertFalse(ElevatedCleanupTracker.isBreakerOpen())
    }

    @Test fun consecutiveUncleanOpensBreaker() {
        // 資源／breaker 知悉：連續不可驗證即累計，達上限即熔斷（零 spawn 拒絕由 AA1 覆）。
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        assertFalse(ElevatedCleanupTracker.isBreakerOpen())
        repeat(ElevatedCleanupTracker.MAX_CONSECUTIVE_UNCLEAN) {
            val runner = makeRoot(
                spawn = { NoOpHangProcess() },
                killer = { /* no-op */ },
            )
            val b = runner.runBinary(ElevatedRequest(listOf("id"), "P1-3-T"), 300L)
            assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
            assertTrue((b as ElevatedBinaryResult.TimedOut).cleanupIncomplete)
            pollOutstandingTo(0)
        }
        assertTrue(ElevatedCleanupTracker.isBreakerOpen())
    }

    // ---- P1-4：spawn 前取消（入口＋重驗後＋競態，零新 spawn） ----

    @Test fun rootPresetInterruptZeroSpawnPreservesInterrupt() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        var spawns = 0
        val runner = makeRoot(spawn = {
            spawns++
            FakeProcess("ok".toByteArray(), ByteArray(0), 0)
        })
        Thread.currentThread().interrupt()
        try {
            val b = runner.runBinary(ElevatedRequest(listOf("id"), "P1-4-T"), 2_000L)
            assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
            assertEquals(0, spawns)
            // interrupt 原樣保留（不清除），且 TimedOut 無殘留（無進程）。
            assertTrue(Thread.currentThread().isInterrupted)
            assertFalse((b as ElevatedBinaryResult.TimedOut).cleanupIncomplete)
        } finally {
            Thread.interrupted()
        }
        pollOutstandingTo(0)
        assertFalse(ElevatedCleanupTracker.isBreakerOpen())
        assertEquals(0, ElevatedCleanupTracker.activeCalls())
    }

    @Test fun shizukuPresetInterruptZeroSpawnPreservesInterrupt() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        var spawns = 0
        val runner = makeShizuku(spawn = {
            spawns++
            FakeProcess("ok".toByteArray(), ByteArray(0), 0)
        })
        Thread.currentThread().interrupt()
        try {
            val b = runner.runBinary(ElevatedRequest(listOf("id"), "P1-4-T"), 2_000L)
            assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
            assertEquals(0, spawns)
            assertTrue(Thread.currentThread().isInterrupted)
            assertFalse((b as ElevatedBinaryResult.TimedOut).cleanupIncomplete)
        } finally {
            Thread.interrupted()
        }
        pollOutstandingTo(0)
        assertFalse(ElevatedCleanupTracker.isBreakerOpen())
    }

    @Test fun jobAlreadyCancelledZeroSpawn() {
        // Job 已 cancel（isCancelled 真）：兩 runner 皆零 spawn。
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        run {
            val job = Job()
            job.cancel()
            assertTrue(job.isCancelled)
            var spawns = 0
            val runner = makeRoot(
                spawn = {
                    spawns++
                    FakeProcess("ok".toByteArray(), ByteArray(0), 0)
                },
                isCancelled = { !job.isActive },
            )
            val b = runner.runBinary(ElevatedRequest(listOf("id"), "P1-4-T"), 2_000L)
            assertTrue("root $b", b is ElevatedBinaryResult.TimedOut)
            assertEquals("root must not spawn", 0, spawns)
            assertFalse((b as ElevatedBinaryResult.TimedOut).cleanupIncomplete)
            pollOutstandingTo(0)
        }
        run {
            val job = Job()
            job.cancel()
            assertTrue(job.isCancelled)
            var spawns = 0
            val runner = makeShizuku(
                spawn = {
                    spawns++
                    FakeProcess("ok".toByteArray(), ByteArray(0), 0)
                },
                isCancelled = { !job.isActive },
            )
            val b = runner.runBinary(ElevatedRequest(listOf("id"), "P1-4-T"), 2_000L)
            assertTrue("shizuku $b", b is ElevatedBinaryResult.TimedOut)
            assertEquals("shizuku must not spawn", 0, spawns)
            assertFalse((b as ElevatedBinaryResult.TimedOut).cleanupIncomplete)
            pollOutstandingTo(0)
        }
        assertFalse(ElevatedCleanupTracker.isBreakerOpen())
    }

    @Test fun cancelledHookThrowFailsClosedZeroSpawn() {
        // 鉤不可用（抛）：未建 fail-closed，零 spawn 回 TimedOut（drain 內抛折 false 對照見 P2）。
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val before = ElevatedProcessIo.swallowedIsCancelledThrows.get()
        run {
            var spawns = 0
            val runner = makeRoot(
                spawn = {
                    spawns++
                    FakeProcess("ok".toByteArray(), ByteArray(0), 0)
                },
                isCancelled = { throw IllegalStateException("cancel boom") },
            )
            val b = runner.runBinary(ElevatedRequest(listOf("id"), "P1-4-T"), 2_000L)
            assertTrue("root $b", b is ElevatedBinaryResult.TimedOut)
            assertEquals("root must not spawn", 0, spawns)
        }
        run {
            var spawns = 0
            val runner = makeShizuku(
                spawn = {
                    spawns++
                    FakeProcess("ok".toByteArray(), ByteArray(0), 0)
                },
                isCancelled = { throw IllegalStateException("cancel boom") },
            )
            val b = runner.runBinary(ElevatedRequest(listOf("id"), "P1-4-T"), 2_000L)
            assertTrue("shizuku $b", b is ElevatedBinaryResult.TimedOut)
            assertEquals("shizuku must not spawn", 0, spawns)
        }
        assertTrue(ElevatedProcessIo.swallowedIsCancelledThrows.get() > before)
        pollOutstandingTo(0)
    }

    @Test fun rootSlowSuExistsMidCancelZeroSpawn() {
        // 慢 suExists 阻塞中取消：重驗前翻轉即零 spawn（不耗配額由 Phase3 覆，此處斷零 spawn）。
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        var spawns = 0
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            spawn = {
                spawns++
                FakeProcess("ok".toByteArray(), ByteArray(0), 0)
            },
            suExists = {
                entered.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
                true
            },
            isCancelled = { cancelled.get() },
            grantCheck = GrantFixtures.allowAlways,
        )
        var result: ElevatedBinaryResult? = null
        val bg = Thread { result = runner.runBinary(ElevatedRequest(listOf("id"), "P1-4-T"), 10_000L) }
            .also { it.isDaemon = true; it.start() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        cancelled.set(true)
        release.countDown()
        bg.join(10_000L)
        assertFalse(bg.isAlive)
        val b = result
        assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
        assertEquals(0, spawns)
        pollOutstandingTo(0)
        assertEquals(0, ElevatedCleanupTracker.activeCalls())
    }

    @Test fun shizukuSlowStatusMidCancelZeroSpawn() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        var spawns = 0
        val runner = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            pingBinder = { true },
            spawn = {
                spawns++
                FakeProcess("ok".toByteArray(), ByteArray(0), 0)
            },
            status = {
                entered.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
                ShizukuStatus.Available(15)
            },
            isCancelled = { cancelled.get() },
            grantCheck = GrantFixtures.allowAlways,
        )
        var result: ElevatedBinaryResult? = null
        val bg = Thread { result = runner.runBinary(ElevatedRequest(listOf("id"), "P1-4-T"), 10_000L) }
            .also { it.isDaemon = true; it.start() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        cancelled.set(true)
        release.countDown()
        bg.join(10_000L)
        assertFalse(bg.isAlive)
        val b = result
        assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
        assertEquals(0, spawns)
        pollOutstandingTo(0)
        assertEquals(0, ElevatedCleanupTracker.activeCalls())
    }

    @Test fun spawnRaceFinalCheckZeroSpawn() {
        // spawn 競態：入口未取消、重驗後已取消（鉤第 2 次起真）→ 零 spawn。
        // 覆蓋入口→重驗窗口中途取消；配額→spawn 窄競態由三點中第三點同理覆（同鉤第 3 次）。
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        // Root：入口第 1 次 false，重驗後第 2 次起 true → 配額前預檢即拒。
        run {
            val calls = AtomicInteger(0)
            var spawns = 0
            val runner = makeRoot(
                spawn = {
                    spawns++
                    FakeProcess("ok".toByteArray(), ByteArray(0), 0)
                },
                isCancelled = { calls.incrementAndGet() > 1 },
            )
            val b = runner.runBinary(ElevatedRequest(listOf("id"), "P1-4-T"), 2_000L)
            assertTrue("root $b", b is ElevatedBinaryResult.TimedOut)
            assertEquals("root race must not spawn", 0, spawns)
            assertTrue("hook must have been consulted", calls.get() >= 2)
            pollOutstandingTo(0)
        }
        // Shizuku：同理。
        run {
            val calls = AtomicInteger(0)
            var spawns = 0
            val runner = makeShizuku(
                spawn = {
                    spawns++
                    FakeProcess("ok".toByteArray(), ByteArray(0), 0)
                },
                isCancelled = { calls.incrementAndGet() > 1 },
            )
            val b = runner.runBinary(ElevatedRequest(listOf("id"), "P1-4-T"), 2_000L)
            assertTrue("shizuku $b", b is ElevatedBinaryResult.TimedOut)
            assertEquals("shizuku race must not spawn", 0, spawns)
            assertTrue("hook must have been consulted", calls.get() >= 2)
            pollOutstandingTo(0)
        }
        assertEquals(0, ElevatedCleanupTracker.activeCalls())
    }
}
