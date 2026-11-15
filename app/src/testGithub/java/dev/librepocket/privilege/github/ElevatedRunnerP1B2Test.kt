package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ShellDeny
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * B2 P1-3 post-kill 驗證＋P1-4 spawn 前取消（owner 新 P1-3／P1-4，PR140 Phase B2）
 * ＋ Stage 2A drain 起線程失敗／飽和殘留加固直測（P1-A2/A3：untrackedResidual、
 *   kill-never-attempted、drain-OOM／thread-start-failure 硬化路徑）。
 *
 * P1-3：killer 真≠進程真死（destroy／destroyForcibly 全 runCatching，遠端 destroy
 * 吞失敗；cleanup worker 離開只代表本機 thread 結束）。drain kill 路徑經
 * `verifyParentStoppedBounded`（`waitFor` 回傳／liveness，binder 不可達視 unknown）驗證
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

    private var hermeticExec: HermeticExecFixture? = null

    @Before fun setUpIsolation() {
        hermeticExec = HermeticExecFixture("p1b2-hermetic", "id")
        assertFalse(
            "#136-B3 gate must stay closed",
            PrivilegeActivation.ELEVATED_RUNNERS_READY,
        )
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
    }

    @After fun tearDownIsolation() {
        hermeticExec?.close()
        hermeticExec = null
        val leakedOutstanding = ElevatedCleanupTracker.outstanding()
        val leakedActive = ElevatedCleanupTracker.activeCalls()
        val leakedQuarantine = ElevatedCleanupTracker.quarantinedCount()
        Thread.interrupted()
        ElevatedCleanupTracker.clearUnverified()
        pollOutstandingTo(0)
        awaitNoElevatedThreads()
        ElevatedCleanupTracker.noteClean()
        assertEquals("test leaked outstanding slots", 0, leakedOutstanding)
        assertEquals("test leaked active calls", 0, leakedActive)
        assertEquals("test leaked quarantined processes", 0, leakedQuarantine)
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
            "elevated-late-kill",
            "elevated-quarantine-sweep",
            "elevated-quarantine-verify",
        )
        val deadline = System.currentTimeMillis() + boundMs
        while (true) {
            val alive = Thread.getAllStackTraces().keys.filter { it.name in names && it.isAlive }
            if (alive.isEmpty()) return
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("orphan elevated threads: $alive")
            }
            Thread.sleep(50L)
        }
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

    private fun pollTrue(what: String, boundMs: Long = 10_000L, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + boundMs
        while (!cond()) {
            if (System.currentTimeMillis() >= deadline) throw AssertionError("$what did not become true")
            Thread.sleep(20L)
        }
    }

    private fun countLive(name: String): Int =
        Thread.getAllStackTraces().keys.count { it.name == name && it.isAlive }

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
        execSearchDirs = hermeticExec!!.searchDirs,
    execAllowedRoots = hermeticExec!!.allowedRoots,
    execIsWritable = hermeticExec!!.systemOwned,
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
        execSearchDirs = hermeticExec!!.searchDirs,
    execAllowedRoots = hermeticExec!!.allowedRoots,
    execIsWritable = hermeticExec!!.systemOwned,
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
        override fun exitValue(): Int = throw IllegalThreadStateException("still alive")
        override fun isAlive(): Boolean = true
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

    /**
     * 自然死亡模型（Stage 3 F6）：`waitFor(0L, MILLISECONDS)` 零超時 liveness 探針
     * 即回 true（sweep 見確死，無需 destroy）；非零超時片仍回 false（drain wait 圈
     * 不誤判完成，正常走 kill 路徑）；destroy 從未被調用＝真自然死亡。
     */
    private class NaturalDeathProcess : Process() {
        var destroyCalls = 0
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean =
            unit.toMillis(timeout) == 0L
        override fun exitValue(): Int = 0
        override fun destroy() {
            destroyCalls++
        }
        override fun destroyForcibly(): Process {
            destroyCalls++
            return this
        }
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
        ElevatedCleanupTracker.clearUnverified()
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
        ElevatedCleanupTracker.clearUnverified()
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
        ElevatedCleanupTracker.clearUnverified()
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
        ElevatedCleanupTracker.clearUnverified()
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
        ElevatedCleanupTracker.clearUnverified()
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
        ElevatedCleanupTracker.clearUnverified()
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

        // Round-3 Phase E（P1-1）：首 call 未驗證 handle 進 quarantine；Shizuku 側
        // 仍須驗「binder 死亡亦 incomplete」，故先顯式 recovery（不清 breaker 計數），
        // 見 consecutiveUncleanOpensBreaker 同例。
        ElevatedCleanupTracker.clearUnverified()
        val shizuku = makeShizuku(
            spawn = { ShizukuRemoteProcess.wrap(BinderDiedRemote()) },
        )
        val sb = shizuku.runBinary(ElevatedRequest(listOf("id"), "P1-3-T"), 300L)
        assertTrue("$sb", sb is ElevatedBinaryResult.TimedOut)
        assertTrue((sb as ElevatedBinaryResult.TimedOut).cleanupIncomplete)
        pollOutstandingTo(0)
        assertFalse(ElevatedCleanupTracker.isSaturated())
        ElevatedCleanupTracker.clearUnverified()
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
            // Round-3 Phase E（P1-1）：每次未驗證 handle 進 quarantine（次 call 即
            // QUARANTINE 拒）。本用例只驗 consecutive 累計→熔斷語義，故每輪顯式
            // recovery quarantine（不清 consecutive、不调 breaker 計數），保持
            // 「連續 4 次不清創即熔斷」原意；quarantine 擋次 call 語義見 PhaseE 測試。
            ElevatedCleanupTracker.clearUnverified()
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
                execSearchDirs = hermeticExec!!.searchDirs,
        execAllowedRoots = hermeticExec!!.allowedRoots,
        execIsWritable = hermeticExec!!.systemOwned,
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
                execSearchDirs = hermeticExec!!.searchDirs,
        execAllowedRoots = hermeticExec!!.allowedRoots,
        execIsWritable = hermeticExec!!.systemOwned,
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

    // ---- Phase 1 迴歸：drain Thread 分配/啟動 OOM、InputStream OOM、Res.Err linger、null privateRoot + 路徑遮罩 ----

    @Test fun drainWithThreadStarterOomCleansUpAndQuarantinesUnverified() {
        for (failName in listOf("elevated-stdin-close", "elevated-stdout", "elevated-stderr")) {
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
            val streamClosed = AtomicBoolean(false)
            val closeLatch = CountDownLatch(1)
            val blockingInput = object : InputStream() {
                override fun read(): Int {
                    if (streamClosed.get()) return -1
                    try {
                        closeLatch.await(10, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    return -1
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (streamClosed.get()) throw java.io.IOException("closed")
                    try {
                        closeLatch.await(10, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw java.io.IOException("interrupted")
                    }
                    throw java.io.IOException("closed")
                }
                override fun close() {
                    streamClosed.set(true)
                    closeLatch.countDown()
                }
            }
            val unverifiedProc = object : Process() {
                override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
                override fun getInputStream(): InputStream = blockingInput
                override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
                override fun waitFor(): Int = 0
                override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = false
                override fun exitValue(): Int = throw IllegalThreadStateException("running")
                override fun destroy() = Unit
                override fun destroyForcibly(): Process = this
            }
            val killerCalls = AtomicInteger(0)
            val started = Collections.synchronizedList(mutableListOf<Thread>())
            val d = try {
                ElevatedProcessIo.drainWithThreadStarter(
                    process = unverifiedProc,
                    timeoutMs = 2_000L,
                    killer = { killerCalls.incrementAndGet() },
                    isCancelled = { false },
                    startThread = { t ->
                        if (t.name == failName) throw OutOfMemoryError("simulated OOM on $failName")
                        started.add(t)
                        t.start()
                    },
                )
            } finally {
                closeLatch.countDown()
            }
            assertEquals("$failName must invoke killer", 1, killerCalls.get())
            assertTrue("$failName must set readFailed=true", d.readFailed)
            assertFalse("$failName must keep timedOut=false", d.timedOut)
            assertEquals(-1, d.exitCode)
            assertTrue("$failName unverified process must flag cleanupIncomplete", d.cleanupIncomplete)
            assertEquals(
                "$failName must quarantine unverified process",
                1,
                ElevatedCleanupTracker.quarantinedCount(),
            )
            assertTrue(
                "$failName already-started threads must be joined cleanly",
                started.all { !it.isAlive },
            )
            pollOutstandingTo(0)
            awaitNoElevatedThreads()

            // 可驗證已殺進程：killer 調用、readFailed=true、timedOut=false、無 quarantine。
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            val killable = KillableImmediateProcess()
            val killableCalls = AtomicInteger(0)
            val d2 = ElevatedProcessIo.drainWithThreadStarter(
                process = killable,
                timeoutMs = 2_000L,
                killer = {
                    killableCalls.incrementAndGet()
                    it.destroy()
                },
                isCancelled = { false },
                startThread = { t ->
                    if (t.name == failName) throw OutOfMemoryError("simulated OOM on $failName")
                    t.start()
                },
            )
            assertEquals(1, killableCalls.get())
            assertTrue(d2.readFailed)
            assertFalse(d2.timedOut)
            assertFalse(d2.cleanupIncomplete)
            assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
            pollOutstandingTo(0)
            awaitNoElevatedThreads()
        }

        // 子例：stdinCloser 卡在 outputStream.close()，tOut 起線程 OOM → startedStdin 必被 interrupt/join/markLingering。
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val stdinEntered = CountDownLatch(1)
        val stdinRelease = CountDownLatch(1)
        val stdinSawInterrupt = AtomicBoolean(false)
        val stdinCloseProc = object : Process() {
            var destroyed = false
            override fun getOutputStream(): OutputStream = object : OutputStream() {
                override fun write(b: Int) = Unit
                override fun close() {
                    stdinEntered.countDown()
                    while (true) {
                        try {
                            if (stdinRelease.await(10, TimeUnit.SECONDS)) break
                            break
                        } catch (_: InterruptedException) {
                            stdinSawInterrupt.set(true)
                        }
                    }
                }
            }
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
        try {
            val dStdin = ElevatedProcessIo.drainWithThreadStarter(
                process = stdinCloseProc,
                timeoutMs = 200L,
                killer = { it.destroy() },
                isCancelled = { false },
                startThread = { t ->
                    if (t.name == "elevated-stdout") {
                        assertTrue(stdinEntered.await(5, TimeUnit.SECONDS))
                        throw OutOfMemoryError("simulated OOM on elevated-stdout while stdinCloser blocked")
                    }
                    t.start()
                },
            )
            assertTrue("readFailed must be true", dStdin.readFailed)
            assertFalse("timedOut must be false", dStdin.timedOut)
            assertTrue("lingering stdinCloser must set cleanupIncomplete", dStdin.cleanupIncomplete)
            assertTrue("startedStdin must be interrupted", stdinSawInterrupt.get())
            assertEquals("lingering stdinCloser must hold 1 slot", 1, ElevatedCleanupTracker.outstanding())
            assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
        } finally {
            stdinRelease.countDown()
            pollOutstandingTo(0)
            awaitNoElevatedThreads()
            ElevatedCleanupTracker.noteClean()
        }
    }

    // ---- Stage 2A（P1-A2）：飽和 cleanup worker → Drained.untrackedResidual＋tracker 計數 ----

    @Test fun drainCleanupSaturationRecordsUntrackedResidualWorker() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val untrackedBefore = ElevatedCleanupTracker.untrackedResidualCount()
        val killNeverBefore = ElevatedCleanupTracker.killNeverAttemptedCount()
        val quarantineBefore = ElevatedCleanupTracker.quarantinedCount()
        var prefilled = 0
        val killerCalls = AtomicInteger(0)
        try {
            // 預填至 MAX：drain kill 路徑 runCleanupBounded 佔槽必敗 → worker 未佔槽起動。
            repeat(ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS) {
                assertTrue(ElevatedCleanupTracker.acquireSlot())
                prefilled++
            }
            val d = ElevatedProcessIo.drainWithThreadStarter(
                process = KillableImmediateProcess(),
                timeoutMs = 300L,
                killer = {
                    killerCalls.incrementAndGet()
                    it.destroy()
                },
                isCancelled = { false },
                startThread = { it.start() },
            )
            // killer 仍被嘗試（飽和不跳過），但 worker 無槽可記 → 不完整。
            assertEquals("kill path must still attempt the killer", 1, killerCalls.get())
            assertTrue(
                "saturated cleanup worker must flag cleanupIncomplete",
                d.cleanupIncomplete,
            )
            assertTrue(
                "saturated cleanup worker must surface untrackedResidual",
                d.untrackedResidual,
            )
            assertEquals(
                "untracked residual counter must increment exactly once (cleanup worker only)",
                1,
                ElevatedCleanupTracker.untrackedResidualCount() - untrackedBefore,
            )
            assertEquals(
                "thread start succeeded ⇒ kill-never-attempted must NOT increment",
                killNeverBefore,
                ElevatedCleanupTracker.killNeverAttemptedCount(),
            )
            // Stage 2A（M1）：飽和臂改經 acquireSlotOrQuarantine——handle 已在同一
            // 鎖內原子登記 quarantine（准入自此密封至 sweep 驗證）；telemetry
            // 計數（untrackedResidual）並列不變。
            assertEquals(
                "saturated cleanup handle must be registered in quarantine atomically",
                quarantineBefore + 1,
                ElevatedCleanupTracker.quarantinedCount(),
            )
            // 預填槽保持（untracked worker 未佔而釋不了、也不得釋）。
            assertEquals(
                ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS,
                ElevatedCleanupTracker.outstanding(),
            )
        } finally {
            repeat(prefilled) { ElevatedCleanupTracker.releaseSlot() }
            pollOutstandingTo(0)
            // M1：飽和 cleanup handle 仍在 quarantine——明確清除（tearDownIsolation
            // 亦會清，此處顯式呼叫以隔離本測試的登記）。
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    // ---- Stage 2A（P1-A3）：cleanup worker 起線程失敗 → killNeverAttempted（既有語義不變） ----

    @Test fun drainCleanupThreadStartFailureRecordsKillNeverAttempted() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        ElevatedCleanupTracker.resetPeak()
        pollOutstandingTo(0)
        val neverBefore = ElevatedCleanupTracker.killNeverAttemptedCount()
        val untrackedBefore = ElevatedCleanupTracker.untrackedResidualCount()
        val quarantineBefore = ElevatedCleanupTracker.quarantinedCount()
        val killerCalls = AtomicInteger(0)
        try {
            val d = ElevatedProcessIo.drainWithThreadStarter(
                process = KillableImmediateProcess(),
                timeoutMs = 300L,
                killer = {
                    // 起線程失敗即無 worker 執行 block() ⇒ killer 從未被信號。
                    killerCalls.incrementAndGet()
                    it.destroy()
                },
                isCancelled = { false },
                startThread = { t ->
                    if (t.name == "elevated-cleanup") {
                        throw OutOfMemoryError("simulated OOM on elevated-cleanup")
                    }
                    t.start()
                },
            )
            assertEquals(
                "killer must never run on the thread-start-failure path",
                0,
                killerCalls.get(),
            )
            assertEquals(
                "kill-never-attempted counter must increment exactly once",
                1,
                ElevatedCleanupTracker.killNeverAttemptedCount() - neverBefore,
            )
            assertEquals(
                "no worker existed ⇒ untracked residual must NOT increment",
                untrackedBefore,
                ElevatedCleanupTracker.untrackedResidualCount(),
            )
            // Stage 2A（P1）：起線程失敗＝killer 從未信號、handle 可能仍存活——
            // 新增無條件 quarantineUnverified（飽和臂已由 acquireSlotOrQuarantine 登記，
            // 此處 === 去重冪等），fail-closed 密封 admission 直至 sweep 驗證。
            assertEquals(
                "thread-start-failure must quarantine the never-killed handle",
                1,
                ElevatedCleanupTracker.quarantinedCount() - quarantineBefore,
            )
            // 既有語義不變：不完整＋timedOut=true（kill 路徑）＋exitCode=-1。
            // Stage 3（F5）：恰一次 acquire（起線程失敗前）⇒ peakOutstanding==1（release 後不清零）。
            assertEquals(
                "exactly one acquire before thread-start failure ⇒ peak must be 1",
                1,
                ElevatedCleanupTracker.peakOutstanding(),
            )
            assertTrue("cleanupIncomplete must stay true", d.cleanupIncomplete)
            assertTrue("timedOut must stay true (kill path)", d.timedOut)
            assertEquals(-1, d.exitCode)
            assertFalse(
                "untrackedResidual must stay false (no worker was ever started)",
                d.untrackedResidual,
            )
        } finally {
            pollOutstandingTo(0)
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    // ---- Stage 3（N1）：飽和 drain kill → sweep 驗證死亡 → admission 恢復（防 seal 永久化回歸） ----

    @Test fun saturatedDrainKillSweepRestoresAdmission() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val quarantineBefore = ElevatedCleanupTracker.quarantinedCount()
        var prefilled = 0
        val killerCalls = AtomicInteger(0)
        try {
            // 預填至 MAX（incremental assertTrue）。
            repeat(ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS) { i ->
                assertTrue("prefill slot $i must grant below MAX", ElevatedCleanupTracker.acquireSlot())
                prefilled++
            }
            // 飽和下 drain kill 路徑：runCleanupBounded 佔槽必敗 → worker 無槽起動，
            // killer 仍被嘗試；handle 已由 acquireSlotOrQuarantine 同鎖原子登記 quarantine。
            val d = ElevatedProcessIo.drainWithThreadStarter(
                process = KillableImmediateProcess(),
                timeoutMs = 300L,
                killer = {
                    killerCalls.incrementAndGet()
                    it.destroy()
                },
                isCancelled = { false },
                startThread = { it.start() },
            )
            assertEquals("kill path must still attempt the killer", 1, killerCalls.get())
            assertTrue(
                "saturated cleanup worker must flag cleanupIncomplete",
                d.cleanupIncomplete,
            )
            assertTrue(
                "saturated cleanup worker must surface untrackedResidual",
                d.untrackedResidual,
            )
            assertEquals(
                "saturated drain must register quarantine exactly once",
                quarantineBefore + 1,
                ElevatedCleanupTracker.quarantinedCount(),
            )
            assertEquals(
                ElevatedCleanupTracker.MAX_OUTSTANDING_CLEANUPS,
                ElevatedCleanupTracker.outstanding(),
            )
            // Stage 3（F7）：drain 終審 noteUnclean（consecutive 由 0 升至 1，飽和值封頂語義）。
            assertEquals(
                "saturated drain must count exactly one consecutive unclean",
                1,
                ElevatedCleanupTracker.consecutiveCount(),
            )
            // 釋預填槽（drain 不自行清 quarantine——飽和登記解除只能等下一次
            // admission-triggered sweep 驗證；先放行歸零再看 admission）。
            repeat(prefilled) { ElevatedCleanupTracker.releaseSlot() }
            prefilled = 0
            pollOutstandingTo(0)
            // 密封此刻仍在位（QUARANTINE 末門獨力阻擋）。
            assertEquals(
                ElevatedCleanupTracker.Admission.QUARANTINE,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            // production 入口（runner 准入前同式 kick）：victim destroy 置旗，
            // sweep 經 waitFor(0)==true 有界驗證即解除登記。
            assertTrue(
                "admission-triggered sweep must kick for the saturated registration",
                ElevatedCleanupTracker.kickSweepAsync(),
            )
            pollTrue("sweep must clear quarantine after verified death", 10_000L) {
                ElevatedCleanupTracker.quarantinedCount() == quarantineBefore
            }
            // 鏈末端：admission 恢復 OK（seal 未永久化）。
            assertEquals(
                ElevatedCleanupTracker.Admission.OK,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            ElevatedCleanupTracker.leaveCall()
        } finally {
            repeat(prefilled) { ElevatedCleanupTracker.releaseSlot() }
            ElevatedCleanupTracker.clearUnverified()
            pollOutstandingTo(0)
            awaitNoElevatedThreads()
            ElevatedCleanupTracker.noteClean()
        }
    }

    // ---- Stage 3（F6）：起線程失敗 quarantine → sweep 驗證死亡 → admission 恢復（鏈式迴歸） ----

    @Test fun startFailureQuarantineThenSweepRecoversAdmission() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val quarantineBefore = ElevatedCleanupTracker.quarantinedCount()
        val victim = NaturalDeathProcess()
        val killerCalls = AtomicInteger(0)
        try {
            // 非飽和（無預填）：acquireSlotOrQuarantine 成功佔槽 → elevated-cleanup
            // 起線程即 OOM ⇒ runCleanupBounded catch 無條件登記 quarantine（先登記再釋槽），
            // killer 從未信號；waitFor(0)==true 自然死亡供 sweep 驗證解除。
            val d = ElevatedProcessIo.drainWithThreadStarter(
                process = victim,
                timeoutMs = 300L,
                killer = {
                    killerCalls.incrementAndGet()
                    it.destroy()
                },
                isCancelled = { false },
                startThread = { t ->
                    if (t.name == "elevated-cleanup") {
                        throw OutOfMemoryError("simulated OOM on elevated-cleanup")
                    }
                    t.start()
                },
            )
            assertEquals(
                "killer must never run on the thread-start-failure path",
                0,
                killerCalls.get(),
            )
            assertEquals(
                "thread-start-failure must quarantine the never-killed handle",
                quarantineBefore + 1,
                ElevatedCleanupTracker.quarantinedCount(),
            )
            assertTrue("cleanupIncomplete must stay true", d.cleanupIncomplete)
            assertEquals(
                "this path acquires+releases cleanly (acquired==true) ⇒ outstanding back to 0",
                0,
                ElevatedCleanupTracker.outstanding(),
            )
            assertEquals(
                "fixture must never be declared dead via destroy()",
                0,
                victim.destroyCalls,
            )
            // 密封此刻仍在位（QUARANTINE 末門獨力阻擋；tryEnterReserved 拒入即不佔席）。
            assertEquals(
                ElevatedCleanupTracker.Admission.QUARANTINE,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            // production 入口（runner 准入前同式 kick）：sweep 經 waitFor(0)==true
            // 有界驗證即解除登記（不經 destroy、不經 noteClean）。
            assertTrue(
                "admission-triggered sweep must kick for the start-failure registration",
                ElevatedCleanupTracker.kickSweepAsync(),
            )
            pollTrue("sweep must clear quarantine after verified death", 10_000L) {
                ElevatedCleanupTracker.quarantinedCount() == quarantineBefore
            }
            // 鏈末端：admission 恢復 OK（seal 未永久化）。
            assertEquals(
                ElevatedCleanupTracker.Admission.OK,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            ElevatedCleanupTracker.leaveCall()
        } finally {
            // 此路徑 acquired==true 自行配對釋放，無預填可還；僅隔離清場。
            ElevatedCleanupTracker.clearUnverified()
            pollOutstandingTo(0)
            awaitNoElevatedThreads()
            ElevatedCleanupTracker.noteClean()
        }
    }

    @Test fun drainInputStreamOomMarksReadFailedNeverPartialOk() {
        val exec = HermeticExecFixture("p1-oom-stream", "screencap", "id")
        try {
            fun makeOomStreamProcess(oomStdout: Boolean): Process {
                val partial = "partial-png-data".toByteArray()
                fun oomInput(): InputStream = object : InputStream() {
                    private var firstReadDone = false
                    override fun read(): Int = throw OutOfMemoryError("oom read")
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (!firstReadDone) {
                            firstReadDone = true
                            val n = minOf(len, partial.size)
                            System.arraycopy(partial, 0, b, off, n)
                            return n
                        }
                        throw OutOfMemoryError("oom during 8MB capture")
                    }
                }
                return object : Process() {
                    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
                    override fun getInputStream(): InputStream =
                        if (oomStdout) oomInput() else ByteArrayInputStream("ok".toByteArray())
                    override fun getErrorStream(): InputStream =
                        if (oomStdout) ByteArrayInputStream(ByteArray(0)) else oomInput()
                    override fun waitFor(): Int = 0
                    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
                    override fun exitValue(): Int = 0
                    override fun destroy() = Unit
                    override fun destroyForcibly(): Process = this
                }
            }

            // 直接 drain：stdout OOM -> readFailed=true（不回完整 Ok）。
            val direct = ElevatedProcessIo.drain(
                makeOomStreamProcess(oomStdout = true),
                2_000L,
                hardCapBytes = ElevatedProcessIo.BINARY_SCREENSHOT_HARD_CAP_BYTES,
                hardCapErrBytes = dev.librepocket.shell.ShellPolicy.HARD_READ_CAP_BYTES,
            )
            assertFalse(direct.timedOut)
            assertTrue("OOM in InputStream.read must mark readFailed", direct.readFailed)
            assertEquals(0, direct.exitCode)

            // Root & Shizuku 截圖雙軌：OOM 絕不回 partial Ok(truncated=false)，一律 Failed。
            for (oomStdout in listOf(true, false)) {
                val root = RootSuRunner(
                    privateRoot = privateRoot,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = GrantFixtures.activationOn,
                    spawn = { makeOomStreamProcess(oomStdout) },
                    suBinary = "/system/bin/su",
                    grantCheck = GrantFixtures.allowAlways,
                    execSearchDirs = exec.searchDirs,
                    execAllowedRoots = exec.allowedRoots,
                    execIsWritable = exec.systemOwned,
                )
                val rr = root.runBinary(ElevatedRequest(listOf("screencap", "-p"), "P1-OOM"), 2_000L)
                assertTrue("root must fail on OOM, got $rr", rr is ElevatedBinaryResult.Failed)
                assertFalse((rr as ElevatedBinaryResult.Failed).cleanupIncomplete)

                val shizuku = ShizukuShellRunner(
                    privateRoot = privateRoot,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = GrantFixtures.activationOn,
                    pingBinder = { true },
                    spawn = { makeOomStreamProcess(oomStdout) },
                    status = { ShizukuStatus.Available(15) },
                    grantCheck = GrantFixtures.allowAlways,
                    execSearchDirs = exec.searchDirs,
                    execAllowedRoots = exec.allowedRoots,
                    execIsWritable = exec.systemOwned,
                )
                val sr = shizuku.runBinary(ElevatedRequest(listOf("screencap", "-p"), "P1-OOM"), 2_000L)
                assertTrue("shizuku must fail on OOM, got $sr", sr is ElevatedBinaryResult.Failed)
                assertFalse((sr as ElevatedBinaryResult.Failed).cleanupIncomplete)
            }
            pollOutstandingTo(0)
            awaitNoElevatedThreads()
        } finally {
            exec.close()
        }
    }

    @Test fun resErrWithLingeringCancelWatchPropagatesCleanupIncomplete() {
        val exec = HermeticExecFixture("p1-res-err-linger", "id")
        fun awaitUninterruptibly(latch: CountDownLatch) {
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // 吞中斷模擬 blocking hook stall。
                }
            }
        }
        try {
            // 1. RootSuRunner: throwing grantCheck + lingering CancelWatch -> Denied(cleanupIncomplete=true)
            run {
                val grantStarted = AtomicBoolean(false)
                val watchEntered = CountDownLatch(1)
                val watchRelease = CountDownLatch(1)
                val grantRelease = CountDownLatch(1)
                val runner = RootSuRunner(
                    privateRoot = privateRoot,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = GrantFixtures.activationOn,
                    spawn = { FakeProcess("ok".toByteArray()) },
                    suBinary = "/system/bin/su",
                    isCancelled = {
                        if (grantStarted.get()) {
                            watchEntered.countDown()
                            awaitUninterruptibly(watchRelease)
                        }
                        false
                    },
                    grantCheck = {
                        grantStarted.set(true)
                        awaitUninterruptibly(grantRelease)
                        throw RuntimeException("grant boom")
                    },
                    execSearchDirs = exec.searchDirs,
                    execAllowedRoots = exec.allowedRoots,
                    execIsWritable = exec.systemOwned,
                )
                val resultRef = AtomicReference<ElevatedBinaryResult?>()
                val bg = Thread {
                    resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "P1-ERR-LINGER"), 5_000L))
                }.also { it.isDaemon = true; it.start() }
                try {
                    assertTrue(watchEntered.await(10, TimeUnit.SECONDS))
                    grantRelease.countDown()
                    bg.join(10_000L)
                    assertFalse(bg.isAlive)
                    val res = resultRef.get()
                    assertTrue("expected Denied, got $res", res is ElevatedBinaryResult.Denied)
                    res as ElevatedBinaryResult.Denied
                    assertEquals(ShellDeny.NOT_WHITELISTED, res.reason)
                    assertTrue("Res.Err with lingering CancelWatch must set cleanupIncomplete", res.cleanupIncomplete)
                    assertEquals(1, ElevatedCleanupTracker.outstanding())
                } finally {
                    grantRelease.countDown()
                    watchRelease.countDown()
                    bg.join(5_000L)
                    pollOutstandingTo(0)
                    ElevatedCleanupTracker.noteClean()
                    awaitNoElevatedThreads()
                }
            }

            // 2. ShizukuShellRunner: throwing grantCheck + lingering CancelWatch -> Denied(cleanupIncomplete=true)
            run {
                val grantStarted = AtomicBoolean(false)
                val watchEntered = CountDownLatch(1)
                val watchRelease = CountDownLatch(1)
                val grantRelease = CountDownLatch(1)
                val runner = ShizukuShellRunner(
                    privateRoot = privateRoot,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = GrantFixtures.activationOn,
                    pingBinder = { true },
                    spawn = { FakeProcess("ok".toByteArray()) },
                    status = { ShizukuStatus.Available(15) },
                    isCancelled = {
                        if (grantStarted.get()) {
                            watchEntered.countDown()
                            awaitUninterruptibly(watchRelease)
                        }
                        false
                    },
                    grantCheck = {
                        grantStarted.set(true)
                        awaitUninterruptibly(grantRelease)
                        throw RuntimeException("grant boom")
                    },
                    execSearchDirs = exec.searchDirs,
                    execAllowedRoots = exec.allowedRoots,
                    execIsWritable = exec.systemOwned,
                )
                val resultRef = AtomicReference<ElevatedBinaryResult?>()
                val bg = Thread {
                    resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "P1-ERR-LINGER"), 5_000L))
                }.also { it.isDaemon = true; it.start() }
                try {
                    assertTrue(watchEntered.await(10, TimeUnit.SECONDS))
                    grantRelease.countDown()
                    bg.join(10_000L)
                    assertFalse(bg.isAlive)
                    val res = resultRef.get()
                    assertTrue("expected Denied, got $res", res is ElevatedBinaryResult.Denied)
                    res as ElevatedBinaryResult.Denied
                    assertEquals(ShellDeny.NOT_WHITELISTED, res.reason)
                    assertTrue("Res.Err with lingering CancelWatch must set cleanupIncomplete", res.cleanupIncomplete)
                    assertEquals(1, ElevatedCleanupTracker.outstanding())
                } finally {
                    grantRelease.countDown()
                    watchRelease.countDown()
                    bg.join(5_000L)
                    pollOutstandingTo(0)
                    ElevatedCleanupTracker.noteClean()
                    awaitNoElevatedThreads()
                }
            }

            // 3. ShizukuShellRunner: throwing status() -> ShizukuStatusGate.denyFor Denied(cleanupIncomplete=true)
            run {
                val statusStarted = AtomicBoolean(false)
                val watchEntered = CountDownLatch(1)
                val watchRelease = CountDownLatch(1)
                val statusRelease = CountDownLatch(1)
                val runner = ShizukuShellRunner(
                    privateRoot = privateRoot,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = GrantFixtures.activationOn,
                    pingBinder = { true },
                    spawn = { FakeProcess("ok".toByteArray()) },
                    status = {
                        statusStarted.set(true)
                        awaitUninterruptibly(statusRelease)
                        throw RuntimeException("status binder boom")
                    },
                    isCancelled = {
                        if (statusStarted.get()) {
                            watchEntered.countDown()
                            awaitUninterruptibly(watchRelease)
                        }
                        false
                    },
                    grantCheck = GrantFixtures.allowAlways,
                    execSearchDirs = exec.searchDirs,
                    execAllowedRoots = exec.allowedRoots,
                    execIsWritable = exec.systemOwned,
                )
                val resultRef = AtomicReference<ElevatedBinaryResult?>()
                val bg = Thread {
                    resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "P1-STATUS-ERR"), 5_000L))
                }.also { it.isDaemon = true; it.start() }
                try {
                    assertTrue(watchEntered.await(10, TimeUnit.SECONDS))
                    statusRelease.countDown()
                    bg.join(10_000L)
                    assertFalse(bg.isAlive)
                    val res = resultRef.get()
                    assertTrue("expected Denied, got $res", res is ElevatedBinaryResult.Denied)
                    res as ElevatedBinaryResult.Denied
                    assertEquals(ShellDeny.NOT_WHITELISTED, res.reason)
                    assertTrue("status Res.Err with lingering CancelWatch must set cleanupIncomplete", res.cleanupIncomplete)
                    assertEquals(1, ElevatedCleanupTracker.outstanding())
                } finally {
                    statusRelease.countDown()
                    watchRelease.countDown()
                    bg.join(5_000L)
                    pollOutstandingTo(0)
                    ElevatedCleanupTracker.noteClean()
                    awaitNoElevatedThreads()
                }
            }

            // 4. RootSuRunner: suAliveRes Res.Err -> Failed(cleanupIncomplete=true)
            run {
                val reVerifyStarted = AtomicBoolean(false)
                val suCalls = AtomicInteger(0)
                val watchEntered = CountDownLatch(1)
                val watchRelease = CountDownLatch(1)
                val suRelease = CountDownLatch(1)
                val runner = RootSuRunner(
                    privateRoot = privateRoot,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = GrantFixtures.activationOn,
                    spawn = { FakeProcess("ok".toByteArray()) },
                    suBinary = null,
                    suExists = { path ->
                        val call = suCalls.incrementAndGet()
                        if (call == 1) {
                            true
                        } else {
                            reVerifyStarted.set(true)
                            awaitUninterruptibly(suRelease)
                            throw RuntimeException("su reverify boom on $path")
                        }
                    },
                    isCancelled = {
                        if (reVerifyStarted.get()) {
                            watchEntered.countDown()
                            awaitUninterruptibly(watchRelease)
                        }
                        false
                    },
                    grantCheck = GrantFixtures.allowAlways,
                    execSearchDirs = exec.searchDirs,
                    execAllowedRoots = exec.allowedRoots,
                    execIsWritable = exec.systemOwned,
                )
                val resultRef = AtomicReference<ElevatedBinaryResult?>()
                val bg = Thread {
                    resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "P1-SU-ALIVE-ERR"), 5_000L))
                }.also { it.isDaemon = true; it.start() }
                try {
                    assertTrue(watchEntered.await(10, TimeUnit.SECONDS))
                    suRelease.countDown()
                    bg.join(10_000L)
                    assertFalse(bg.isAlive)
                    val res = resultRef.get()
                    assertTrue("expected Failed, got $res", res is ElevatedBinaryResult.Failed)
                    assertTrue(
                        "suAliveRes Res.Err with lingering CancelWatch must set cleanupIncomplete",
                        (res as ElevatedBinaryResult.Failed).cleanupIncomplete,
                    )
                    assertEquals(1, ElevatedCleanupTracker.outstanding())
                } finally {
                    suRelease.countDown()
                    watchRelease.countDown()
                    bg.join(5_000L)
                    pollOutstandingTo(0)
                    awaitNoElevatedThreads()
                    ElevatedCleanupTracker.noteClean()
                }
            }

            // 5. Direct ElevatedCallSupervisor.awaitPreSpawn: Res.Err.hasLinger == true (unmasked by outstanding()>0)
            run {
                val blockStarted = AtomicBoolean(false)
                val watchEntered = CountDownLatch(1)
                val watchRelease = CountDownLatch(1)
                val blockRelease = CountDownLatch(1)
                val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                val resRef = AtomicReference<ElevatedCallSupervisor.Res<String>?>()
                val bg = Thread {
                    resRef.set(
                        ElevatedCallSupervisor.awaitPreSpawn(
                            deadlineNanos = deadlineNanos,
                            isCancelled = {
                                if (blockStarted.get()) {
                                    watchEntered.countDown()
                                    awaitUninterruptibly(watchRelease)
                                }
                                false
                            },
                        ) {
                            blockStarted.set(true)
                            awaitUninterruptibly(blockRelease)
                            throw RuntimeException("direct pre-spawn boom")
                        },
                    )
                }.also { it.isDaemon = true; it.start() }
                try {
                    assertTrue(watchEntered.await(10, TimeUnit.SECONDS))
                    blockRelease.countDown()
                    bg.join(10_000L)
                    assertFalse(bg.isAlive)
                    val res = resRef.get()
                    assertTrue("expected Res.Err, got $res", res is ElevatedCallSupervisor.Res.Err)
                    res as ElevatedCallSupervisor.Res.Err
                    assertTrue("awaitPreSpawn Res.Err must set hasLinger=true directly", res.hasLinger)
                    assertEquals(1, ElevatedCleanupTracker.outstanding())
                } finally {
                    blockRelease.countDown()
                    watchRelease.countDown()
                    bg.join(5_000L)
                    pollOutstandingTo(0)
                    awaitNoElevatedThreads()
                    ElevatedCleanupTracker.noteClean()
                }
            }

            // 6. Direct ElevatedCallSupervisor.awaitSpawn: Res.Err.hasLinger == true (unmasked by outstanding()>0)
            run {
                val blockStarted = AtomicBoolean(false)
                val watchEntered = CountDownLatch(1)
                val watchRelease = CountDownLatch(1)
                val blockRelease = CountDownLatch(1)
                val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                val resRef = AtomicReference<ElevatedCallSupervisor.Res<Process>?>()
                val bg = Thread {
                    resRef.set(
                        ElevatedCallSupervisor.awaitSpawn(
                            deadlineNanos = deadlineNanos,
                            isCancelled = {
                                if (blockStarted.get()) {
                                    watchEntered.countDown()
                                    awaitUninterruptibly(watchRelease)
                                }
                                false
                            },
                            killer = { it.destroy() },
                        ) {
                            blockStarted.set(true)
                            awaitUninterruptibly(blockRelease)
                            throw RuntimeException("direct spawn boom")
                        },
                    )
                }.also { it.isDaemon = true; it.start() }
                try {
                    assertTrue(watchEntered.await(10, TimeUnit.SECONDS))
                    blockRelease.countDown()
                    bg.join(10_000L)
                    assertFalse(bg.isAlive)
                    val res = resRef.get()
                    assertTrue("expected Res.Err, got $res", res is ElevatedCallSupervisor.Res.Err)
                    res as ElevatedCallSupervisor.Res.Err
                    assertTrue("awaitSpawn Res.Err must set hasLinger=true directly", res.hasLinger)
                    assertEquals(1, ElevatedCleanupTracker.outstanding())
                } finally {
                    blockRelease.countDown()
                    watchRelease.countDown()
                    bg.join(5_000L)
                    pollOutstandingTo(0)
                    awaitNoElevatedThreads()
                    ElevatedCleanupTracker.noteClean()
                }
            }

            // 7. Root & Shizuku: spawn throws with lingering CancelWatch (spawnRes.Err -> Failed(cleanupIncomplete=true))
            for (useShizuku in listOf(false, true)) {
                val spawnStarted = AtomicBoolean(false)
                val watchEntered = CountDownLatch(1)
                val watchRelease = CountDownLatch(1)
                val spawnRelease = CountDownLatch(1)
                val hook: () -> Boolean = {
                    if (spawnStarted.get()) {
                        watchEntered.countDown()
                        awaitUninterruptibly(watchRelease)
                    }
                    false
                }
                val spawnBody: (List<String>) -> Process = {
                    spawnStarted.set(true)
                    awaitUninterruptibly(spawnRelease)
                    throw RuntimeException("spawn boom")
                }
                val runner: BinaryElevatedRunner = if (useShizuku) {
                    ShizukuShellRunner(
                        privateRoot = privateRoot,
                        bridgeGranted = GrantFixtures.bridgeOn,
                        activationCheck = GrantFixtures.activationOn,
                        pingBinder = { true },
                        spawn = spawnBody,
                        status = { ShizukuStatus.Available(15) },
                        isCancelled = hook,
                        grantCheck = GrantFixtures.allowAlways,
                        execSearchDirs = exec.searchDirs,
                        execAllowedRoots = exec.allowedRoots,
                        execIsWritable = exec.systemOwned,
                    )
                } else {
                    RootSuRunner(
                        privateRoot = privateRoot,
                        bridgeGranted = GrantFixtures.bridgeOn,
                        activationCheck = GrantFixtures.activationOn,
                        spawn = spawnBody,
                        suBinary = "/system/bin/su",
                        isCancelled = hook,
                        grantCheck = GrantFixtures.allowAlways,
                        execSearchDirs = exec.searchDirs,
                        execAllowedRoots = exec.allowedRoots,
                        execIsWritable = exec.systemOwned,
                    )
                }
                val resultRef = AtomicReference<ElevatedBinaryResult?>()
                val bg = Thread {
                    resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "P1-SPAWN-ERR"), 5_000L))
                }.also { it.isDaemon = true; it.start() }
                try {
                    assertTrue(watchEntered.await(10, TimeUnit.SECONDS))
                    spawnRelease.countDown()
                    bg.join(10_000L)
                    assertFalse(bg.isAlive)
                    val res = resultRef.get()
                    assertTrue("expected Failed (shizuku=$useShizuku), got $res", res is ElevatedBinaryResult.Failed)
                    assertTrue(
                        "spawnRes.Err with lingering CancelWatch must set cleanupIncomplete",
                        (res as ElevatedBinaryResult.Failed).cleanupIncomplete,
                    )
                    assertEquals(1, ElevatedCleanupTracker.outstanding())
                } finally {
                    spawnRelease.countDown()
                    watchRelease.countDown()
                    bg.join(5_000L)
                    pollOutstandingTo(0)
                    awaitNoElevatedThreads()
                    ElevatedCleanupTracker.noteClean()
                }
            }

            // 8. Direct awaitSpawn + Root & Shizuku: spawn exits within GRACE_MS while CancelWatch lingers (spawnRes.Expired)
            run {
                val blockStarted = AtomicBoolean(false)
                val watchEntered = CountDownLatch(1)
                val watchRelease = CountDownLatch(1)
                val t0 = 1_000_000_000_000L
                val fakeNow = AtomicLong(t0)
                val deadlineNanos = t0 + TimeUnit.MILLISECONDS.toNanos(500L)
                val resRef = AtomicReference<ElevatedCallSupervisor.Res<Process>?>()
                val bg = Thread {
                    resRef.set(
                        ElevatedCallSupervisor.awaitSpawn(
                            deadlineNanos = deadlineNanos,
                            isCancelled = {
                                if (blockStarted.get()) {
                                    watchEntered.countDown()
                                    awaitUninterruptibly(watchRelease)
                                }
                                false
                            },
                            killer = { it.destroy() },
                            nowNanos = { fakeNow.get() },
                        ) {
                            blockStarted.set(true)
                            try {
                                Thread.sleep(10_000L)
                            } catch (e: InterruptedException) {
                                throw RuntimeException("interrupted", e)
                            }
                            FakeProcess("never".toByteArray())
                        },
                    )
                }.also { it.isDaemon = true; it.start() }
                try {
                    assertTrue(watchEntered.await(10, TimeUnit.SECONDS))
                    fakeNow.set(t0 + TimeUnit.MILLISECONDS.toNanos(1_000L))
                    bg.join(10_000L)
                    assertFalse(bg.isAlive)
                    val res = resRef.get()
                    assertTrue("expected Res.Expired, got $res", res is ElevatedCallSupervisor.Res.Expired)
                    res as ElevatedCallSupervisor.Res.Expired
                    assertTrue(
                        "expireWorker must set hasLinger=true when CancelWatch lingers even if spawn exited within GRACE_MS",
                        res.hasLinger,
                    )
                    assertEquals(1, ElevatedCleanupTracker.outstanding())
                } finally {
                    watchRelease.countDown()
                    bg.join(5_000L)
                    pollOutstandingTo(0)
                    awaitNoElevatedThreads()
                    ElevatedCleanupTracker.noteClean()
                }
            }
            for (useShizuku in listOf(false, true)) {
                val spawnStarted = AtomicBoolean(false)
                val watchEntered = CountDownLatch(1)
                val watchRelease = CountDownLatch(1)
                val hook: () -> Boolean = {
                    if (spawnStarted.get()) {
                        watchEntered.countDown()
                        awaitUninterruptibly(watchRelease)
                    }
                    false
                }
                val spawnBody: (List<String>) -> Process = {
                    spawnStarted.set(true)
                    try {
                        Thread.sleep(10_000L)
                    } catch (e: InterruptedException) {
                        throw RuntimeException("interrupted", e)
                    }
                    FakeProcess("never".toByteArray())
                }
                val runner: BinaryElevatedRunner = if (useShizuku) {
                    ShizukuShellRunner(
                        privateRoot = privateRoot,
                        bridgeGranted = GrantFixtures.bridgeOn,
                        activationCheck = GrantFixtures.activationOn,
                        pingBinder = { true },
                        spawn = spawnBody,
                        status = { ShizukuStatus.Available(15) },
                        isCancelled = hook,
                        grantCheck = GrantFixtures.allowAlways,
                        execSearchDirs = exec.searchDirs,
                        execAllowedRoots = exec.allowedRoots,
                        execIsWritable = exec.systemOwned,
                    )
                } else {
                    RootSuRunner(
                        privateRoot = privateRoot,
                        bridgeGranted = GrantFixtures.bridgeOn,
                        activationCheck = GrantFixtures.activationOn,
                        spawn = spawnBody,
                        suBinary = "/system/bin/su",
                        isCancelled = hook,
                        grantCheck = GrantFixtures.allowAlways,
                        execSearchDirs = exec.searchDirs,
                        execAllowedRoots = exec.allowedRoots,
                        execIsWritable = exec.systemOwned,
                    )
                }
                val resultRef = AtomicReference<ElevatedBinaryResult?>()
                val bg = Thread {
                    resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "P1-SPAWN-EXP-LINGER"), 500L))
                }.also { it.isDaemon = true; it.start() }
                try {
                    assertTrue(watchEntered.await(10, TimeUnit.SECONDS))
                    bg.join(10_000L)
                    assertFalse(bg.isAlive)
                    val res = resultRef.get()
                    assertTrue("expected TimedOut (shizuku=$useShizuku), got $res", res is ElevatedBinaryResult.TimedOut)
                    assertTrue(
                        "spawnRes.Expired with lingering CancelWatch must set cleanupIncomplete",
                        (res as ElevatedBinaryResult.TimedOut).cleanupIncomplete,
                    )
                    assertEquals(1, ElevatedCleanupTracker.outstanding())
                } finally {
                    watchRelease.countDown()
                    bg.join(5_000L)
                    pollOutstandingTo(0)
                    awaitNoElevatedThreads()
                    ElevatedCleanupTracker.noteClean()
                }
            }
        } finally {
            exec.close()
        }
    }

    @Test fun rootNullPrivateRootUsesExecAndUntrustedPathIsRedacted() {
        val exec = HermeticExecFixture("p1-null-root-exec", "id")
        try {
            val realId = java.io.File(exec.dir, "id").canonicalPath
            var lastWrapped: List<String> = emptyList()
            val root = RootSuRunner(
                privateRoot = null,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                spawn = { argv ->
                    lastWrapped = argv
                    FakeProcess("ok".toByteArray())
                },
                suBinary = "/system/bin/su",
                grantCheck = GrantFixtures.allowAlways,
                execSearchDirs = exec.searchDirs,
                execAllowedRoots = exec.allowedRoots,
                execIsWritable = exec.systemOwned,
            )
            val ok = root.runBinary(ElevatedRequest(listOf("id"), "P1-EXEC"), 2_000L)
            assertTrue("expected Ok, got $ok", ok is ElevatedBinaryResult.Ok)
            assertEquals(listOf("/system/bin/su", "-c", "exec $realId"), lastWrapped)

            // 不可信路徑經預設 TRUSTED_BIN_DIRS 由 Validation.Denied 拒時，亦須對 /data/data/... 遮罩。
            val secretBin = "/data/data/secret/bin"
            run {
                var rootSpawns = 0
                val rootDefaultDirs = RootSuRunner(
                    privateRoot = null,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = GrantFixtures.activationOn,
                    spawn = {
                        rootSpawns++
                        FakeProcess("ok".toByteArray())
                    },
                    suBinary = "/system/bin/su",
                    grantCheck = GrantFixtures.allowAlways,
                )
                val rValDenied = rootDefaultDirs.runBinary(ElevatedRequest(listOf(secretBin), "P1-VAL-REDACT"), 2_000L)
                assertTrue("root expected Denied, got $rValDenied", rValDenied is ElevatedBinaryResult.Denied)
                rValDenied as ElevatedBinaryResult.Denied
                assertEquals(ShellDeny.BLACKLISTED, rValDenied.reason)
                assertTrue("message=${rValDenied.message}", rValDenied.message.contains("untrusted executable path"))
                assertFalse("message must redact path: ${rValDenied.message}", rValDenied.message.contains("/data/data"))
                assertEquals(0, rootSpawns)

                var shizukuSpawns = 0
                val shizukuDefaultDirs = ShizukuShellRunner(
                    privateRoot = null,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = GrantFixtures.activationOn,
                    pingBinder = { true },
                    spawn = {
                        shizukuSpawns++
                        FakeProcess("ok".toByteArray())
                    },
                    status = { ShizukuStatus.Available(15) },
                    grantCheck = GrantFixtures.allowAlways,
                )
                val sValDenied = shizukuDefaultDirs.runBinary(ElevatedRequest(listOf(secretBin), "P1-VAL-REDACT"), 2_000L)
                assertTrue("shizuku expected Denied, got $sValDenied", sValDenied is ElevatedBinaryResult.Denied)
                sValDenied as ElevatedBinaryResult.Denied
                assertEquals(ShellDeny.BLACKLISTED, sValDenied.reason)
                assertTrue("message=${sValDenied.message}", sValDenied.message.contains("untrusted executable path"))
                assertFalse("message must redact path: ${sValDenied.message}", sValDenied.message.contains("/data/data"))
                assertEquals(0, shizukuSpawns)
            }

            // 不可信路徑經 resolveForSnapshot 拒／拋（含 RuntimeException 與 AssertionError）時，Denied.message 必須對 /data/data/... 遮罩。
            val resolverModes: List<((String) -> dev.librepocket.shell.ShellExecutables.ResolvedExec?)?> = listOf(
                null,
                { _ -> throw RuntimeException("resolver boom") },
                { _ -> throw AssertionError("resolver assertion boom") },
            )
            for (resolver in resolverModes) {
                var rootSpawns = 0
                val rootUntrusted = RootSuRunner(
                    privateRoot = null,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = GrantFixtures.activationOn,
                    spawn = {
                        rootSpawns++
                        FakeProcess("ok".toByteArray())
                    },
                    suBinary = "/system/bin/su",
                    grantCheck = GrantFixtures.allowAlways,
                    execSearchDirs = listOf("/data/data/secret"),
                    execAllowedRoots = listOf("/system/bin"),
                    execIsWritable = { false },
                    execResolver = resolver,
                )
                val rDenied = rootUntrusted.runBinary(ElevatedRequest(listOf(secretBin), "P1-REDACT"), 2_000L)
                assertTrue("root expected Denied, got $rDenied", rDenied is ElevatedBinaryResult.Denied)
                rDenied as ElevatedBinaryResult.Denied
                assertEquals(ShellDeny.BLACKLISTED, rDenied.reason)
                assertTrue("message=${rDenied.message}", rDenied.message.contains("untrusted executable:"))
                assertFalse("message must redact path: ${rDenied.message}", rDenied.message.contains("/data/data"))
                assertEquals(0, rootSpawns)

                var shizukuSpawns = 0
                val shizukuUntrusted = ShizukuShellRunner(
                    privateRoot = null,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = GrantFixtures.activationOn,
                    pingBinder = { true },
                    spawn = {
                        shizukuSpawns++
                        FakeProcess("ok".toByteArray())
                    },
                    status = { ShizukuStatus.Available(15) },
                    grantCheck = GrantFixtures.allowAlways,
                    execSearchDirs = listOf("/data/data/secret"),
                    execAllowedRoots = listOf("/system/bin"),
                    execIsWritable = { false },
                    execResolver = resolver,
                )
                val sDenied = shizukuUntrusted.runBinary(ElevatedRequest(listOf(secretBin), "P1-REDACT"), 2_000L)
                assertTrue("shizuku expected Denied, got $sDenied", sDenied is ElevatedBinaryResult.Denied)
                sDenied as ElevatedBinaryResult.Denied
                assertEquals(ShellDeny.BLACKLISTED, sDenied.reason)
                assertTrue("message=${sDenied.message}", sDenied.message.contains("untrusted executable:"))
                assertFalse("message must redact path: ${sDenied.message}", sDenied.message.contains("/data/data"))
                assertEquals(0, shizukuSpawns)
            }
            pollOutstandingTo(0)
            awaitNoElevatedThreads()
        } finally {
            exec.close()
        }
    }

    @Test fun cancelWatchPoll2ErrorAfterSeenFalseFailsClosedNeverOk() {
        fun awaitUninterruptibly(latch: CountDownLatch) {
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // 吞中斷模擬不可中斷阻塞。
                }
            }
        }
        val exec = HermeticExecFixture("p1-cw-poll2", "id")
        try {
            val errors: List<() -> Throwable> = listOf(
                { AssertionError("poll 2 assertion boom") },
                { OutOfMemoryError("poll 2 oom boom") },
            )
            for (makeError in errors) {
                // 1. awaitPreSpawn: poll 1 false (seenFalse=true), poll 2 throws Error -> Res.Expired (never Ok)
                run {
                    val hookCalls = AtomicInteger(0)
                    val poll2Attempted = CountDownLatch(1)
                    val blockRelease = CountDownLatch(1)
                    val swallowedBefore = ElevatedProcessIo.swallowedIsCancelledThrows.get()
                    val resRef = AtomicReference<ElevatedCallSupervisor.Res<String>?>()
                    val bg = Thread {
                        resRef.set(
                            ElevatedCallSupervisor.awaitPreSpawn(
                                deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10),
                                isCancelled = {
                                    if (hookCalls.incrementAndGet() == 1) {
                                        false
                                    } else {
                                        poll2Attempted.countDown()
                                        throw makeError()
                                    }
                                },
                            ) {
                                awaitUninterruptibly(blockRelease)
                                "should-not-succeed"
                            },
                        )
                    }.also { it.isDaemon = true; it.start() }
                    try {
                        assertTrue(poll2Attempted.await(10, TimeUnit.SECONDS))
                        val swDeadline = System.currentTimeMillis() + 5_000L
                        while (ElevatedProcessIo.swallowedIsCancelledThrows.get() <= swallowedBefore &&
                            System.currentTimeMillis() < swDeadline
                        ) {
                            Thread.sleep(10L)
                        }
                        assertTrue("swallowedIsCancelledThrows did not increment before deadline", ElevatedProcessIo.swallowedIsCancelledThrows.get() > swallowedBefore)
                        blockRelease.countDown()
                        bg.join(10_000L)
                        assertFalse(bg.isAlive)
                        val r = resRef.get()
                        assertTrue("awaitPreSpawn must fail closed on poll 2 Error, got $r", r is ElevatedCallSupervisor.Res.Expired)
                    } finally {
                        blockRelease.countDown()
                        bg.join(5_000L)
                        pollOutstandingTo(0)
                        awaitNoElevatedThreads()
                        ElevatedCleanupTracker.noteClean()
                    }
                }

                // 2. awaitSpawn: poll 1 false (seenFalse=true), poll 2 throws Error -> Res.Expired + compensates Process
                run {
                    val hookCalls = AtomicInteger(0)
                    val poll2Attempted = CountDownLatch(1)
                    val blockRelease = CountDownLatch(1)
                    val swallowedBefore = ElevatedProcessIo.swallowedIsCancelledThrows.get()
                    val proc = FakeProcess("late".toByteArray())
                    val resRef = AtomicReference<ElevatedCallSupervisor.Res<Process>?>()
                    val bg = Thread {
                        resRef.set(
                            ElevatedCallSupervisor.awaitSpawn(
                                deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10),
                                isCancelled = {
                                    if (hookCalls.incrementAndGet() == 1) {
                                        false
                                    } else {
                                        poll2Attempted.countDown()
                                        throw makeError()
                                    }
                                },
                                killer = { it.destroy() },
                            ) {
                                awaitUninterruptibly(blockRelease)
                                proc
                            },
                        )
                    }.also { it.isDaemon = true; it.start() }
                    try {
                        assertTrue(poll2Attempted.await(10, TimeUnit.SECONDS))
                        val swDeadline = System.currentTimeMillis() + 5_000L
                        while (ElevatedProcessIo.swallowedIsCancelledThrows.get() <= swallowedBefore &&
                            System.currentTimeMillis() < swDeadline
                        ) {
                            Thread.sleep(10L)
                        }
                        assertTrue("swallowedIsCancelledThrows did not increment before deadline", ElevatedProcessIo.swallowedIsCancelledThrows.get() > swallowedBefore)
                        blockRelease.countDown()
                        bg.join(10_000L)
                        assertFalse(bg.isAlive)
                        val r = resRef.get()
                        assertTrue("awaitSpawn must fail closed on poll 2 Error, got $r", r is ElevatedCallSupervisor.Res.Expired)
                        val killDeadline = System.currentTimeMillis() + 10_000L
                        while (!proc.destroyed && System.currentTimeMillis() < killDeadline) {
                            Thread.sleep(20L)
                        }
                        assertTrue("late process must be killed", proc.destroyed)
                    } finally {
                        blockRelease.countDown()
                        bg.join(5_000L)
                        pollOutstandingTo(0)
                        awaitNoElevatedThreads()
                        ElevatedCleanupTracker.noteClean()
                    }
                }

                // 3. RootSuRunner & ShizukuShellRunner: spawn slow path poll 2 Error -> TimedOut (never Ok)
                for (useShizuku in listOf(false, true)) {
                    val spawnStarted = AtomicBoolean(false)
                    val hookCalls = AtomicInteger(0)
                    val poll2Attempted = CountDownLatch(1)
                    val spawnRelease = CountDownLatch(1)
                    val proc = FakeProcess("late".toByteArray())
                    val hook: () -> Boolean = {
                        if (!spawnStarted.get()) {
                            false
                        } else if (hookCalls.incrementAndGet() == 1) {
                            false
                        } else {
                            poll2Attempted.countDown()
                            throw makeError()
                        }
                    }
                    val spawnBody: (List<String>) -> Process = {
                        spawnStarted.set(true)
                        awaitUninterruptibly(spawnRelease)
                        proc
                    }
                    val runner: BinaryElevatedRunner = if (useShizuku) {
                        ShizukuShellRunner(
                            privateRoot = privateRoot,
                            bridgeGranted = GrantFixtures.bridgeOn,
                            activationCheck = GrantFixtures.activationOn,
                            pingBinder = { true },
                            spawn = spawnBody,
                            status = { ShizukuStatus.Available(15) },
                            isCancelled = hook,
                            grantCheck = GrantFixtures.allowAlways,
                            execSearchDirs = exec.searchDirs,
                            execAllowedRoots = exec.allowedRoots,
                            execIsWritable = exec.systemOwned,
                        )
                    } else {
                        RootSuRunner(
                            privateRoot = privateRoot,
                            bridgeGranted = GrantFixtures.bridgeOn,
                            activationCheck = GrantFixtures.activationOn,
                            spawn = spawnBody,
                            suBinary = "/system/bin/su",
                            isCancelled = hook,
                            grantCheck = GrantFixtures.allowAlways,
                            execSearchDirs = exec.searchDirs,
                            execAllowedRoots = exec.allowedRoots,
                            execIsWritable = exec.systemOwned,
                        )
                    }
                    val swallowedBefore = ElevatedProcessIo.swallowedIsCancelledThrows.get()
                    val resRef = AtomicReference<ElevatedBinaryResult?>()
                    val bg = Thread {
                        resRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "P1-CW-POLL2"), 10_000L))
                    }.also { it.isDaemon = true; it.start() }
                    try {
                        assertTrue(poll2Attempted.await(10, TimeUnit.SECONDS))
                        val swDeadline = System.currentTimeMillis() + 5_000L
                        while (ElevatedProcessIo.swallowedIsCancelledThrows.get() <= swallowedBefore &&
                            System.currentTimeMillis() < swDeadline
                        ) {
                            Thread.sleep(10L)
                        }
                        assertTrue("swallowedIsCancelledThrows did not increment before deadline", ElevatedProcessIo.swallowedIsCancelledThrows.get() > swallowedBefore)
                        spawnRelease.countDown()
                        bg.join(10_000L)
                        assertFalse(bg.isAlive)
                        val r = resRef.get()
                        assertTrue("runner(shizuku=$useShizuku) must return TimedOut, got $r", r is ElevatedBinaryResult.TimedOut)
                    } finally {
                        spawnRelease.countDown()
                        bg.join(5_000L)
                        pollOutstandingTo(0)
                        awaitNoElevatedThreads()
                        ElevatedCleanupTracker.noteClean()
                    }
                }
            }

            // 4. CancelWatch poll 2 nowNanos throws AssertionError after seenFalse=true -> Res.Expired (never Ok)
            run {
                val hookCalls = AtomicInteger(0)
                val clockThrew = CountDownLatch(1)
                val blockRelease = CountDownLatch(1)
                val t0 = 1_000_000_000_000L
                val deadlineNanos = t0 + TimeUnit.SECONDS.toNanos(10)
                val resRef = AtomicReference<ElevatedCallSupervisor.Res<String>?>()
                val bg = Thread {
                    resRef.set(
                        ElevatedCallSupervisor.awaitPreSpawn(
                            deadlineNanos = deadlineNanos,
                            isCancelled = {
                                hookCalls.incrementAndGet()
                                false
                            },
                            nowNanos = {
                                if (Thread.currentThread().name == "elevated-supervisor" && hookCalls.get() >= 1) {
                                    clockThrew.countDown()
                                    throw AssertionError("poll 2 clock boom")
                                }
                                t0
                            },
                        ) {
                            awaitUninterruptibly(blockRelease)
                            "should-not-succeed"
                        },
                    )
                }.also { it.isDaemon = true; it.start() }
                try {
                    assertTrue(clockThrew.await(10, TimeUnit.SECONDS))
                    // 不需 sleep：clockThrew.await 已保證 watcher 的失敗先於此處可見；bg.join 界定其餘收斂。
                    blockRelease.countDown()
                    bg.join(10_000L)
                    assertFalse(bg.isAlive)
                    val r = resRef.get()
                    assertTrue("CancelWatch poll 2 clock Error must fail closed, got $r", r is ElevatedCallSupervisor.Res.Expired)
                } finally {
                    blockRelease.countDown()
                    bg.join(5_000L)
                    pollOutstandingTo(0)
                    awaitNoElevatedThreads()
                    ElevatedCleanupTracker.noteClean()
                }
            }
        } finally {
            exec.close()
        }
    }

    @Test fun lateCompensateSpawnKillerOomClosesStreamsQuarantinesAndCountsBreaker() {
        fun awaitUninterruptibly(latch: CountDownLatch) {
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // 吞中斷模擬不可中斷阻塞。
                }
            }
        }
        fun makeTrackableProcess(
            inClosed: AtomicBoolean,
            errClosed: AtomicBoolean,
            outClosed: AtomicBoolean,
        ): Process = object : Process() {
            override fun getInputStream(): InputStream = object : ByteArrayInputStream(ByteArray(0)) {
                override fun close() {
                    inClosed.set(true)
                }
            }
            override fun getErrorStream(): InputStream = object : ByteArrayInputStream(ByteArray(0)) {
                override fun close() {
                    errClosed.set(true)
                }
            }
            override fun getOutputStream(): OutputStream = object : ByteArrayOutputStream() {
                override fun close() {
                    outClosed.set(true)
                }
            }
            override fun waitFor(): Int = 0
            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = false
            override fun exitValue(): Int = 0
            override fun destroy() = Unit
            override fun destroyForcibly(): Process = this
        }

        // 1. Worker inline lateCompensateSpawn with killer throwing OutOfMemoryError
        run {
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
            val inClosed = AtomicBoolean(false)
            val errClosed = AtomicBoolean(false)
            val outClosed = AtomicBoolean(false)
            val proc = makeTrackableProcess(inClosed, errClosed, outClosed)
            val spawnEntered = CountDownLatch(1)
            val spawnRelease = CountDownLatch(1)
            val beforeKiller = ElevatedProcessIo.swallowedKillerThrows.get()
            try {
                val res = ElevatedCallSupervisor.awaitSpawn(
                    deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100L),
                    isCancelled = { false },
                    killer = { throw OutOfMemoryError("inline killer oom") },
                ) {
                    spawnEntered.countDown()
                    awaitUninterruptibly(spawnRelease)
                    proc
                }
                assertTrue(spawnEntered.await(10, TimeUnit.SECONDS))
                assertTrue("expected Expired, got $res", res is ElevatedCallSupervisor.Res.Expired)
                assertTrue((res as ElevatedCallSupervisor.Res.Expired).hasLinger)
                spawnRelease.countDown()
                pollOutstandingTo(0)
                awaitNoElevatedThreads()
                assertTrue("inputStream must be closed", inClosed.get())
                assertTrue("errorStream must be closed", errClosed.get())
                assertTrue("outputStream must be closed", outClosed.get())
                assertEquals("unverified process must be quarantined", 1, ElevatedCleanupTracker.quarantinedCount())
                assertTrue("breaker must count unclean", ElevatedCleanupTracker.consecutiveCount() >= 1)
                assertTrue(ElevatedProcessIo.swallowedKillerThrows.get() > beforeKiller)
            } finally {
                spawnRelease.countDown()
                ElevatedCleanupTracker.clearUnverified()
                pollOutstandingTo(0)
                awaitNoElevatedThreads()
                ElevatedCleanupTracker.noteClean()
            }
        }

        // 2. Caller startLateDaemon path with killer throwing OutOfMemoryError
        run {
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
            val inClosed = AtomicBoolean(false)
            val errClosed = AtomicBoolean(false)
            val outClosed = AtomicBoolean(false)
            val proc = makeTrackableProcess(inClosed, errClosed, outClosed)
            val spawnEntered = CountDownLatch(1)
            val spawnRelease = CountDownLatch(1)
            val spawnDone = CountDownLatch(1)
            val cancelBlockEntered = CountDownLatch(1)
            val cancelGate = CountDownLatch(1)
            val beforeKiller = ElevatedProcessIo.swallowedKillerThrows.get()
            val resRef = AtomicReference<ElevatedCallSupervisor.Res<Process>?>()
            val bg = Thread {
                resRef.set(
                    ElevatedCallSupervisor.awaitSpawn(
                        deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10),
                        isCancelled = {
                            if (spawnEntered.count > 0) {
                                false
                            } else {
                                cancelBlockEntered.countDown()
                                awaitUninterruptibly(cancelGate)
                                true
                            }
                        },
                        killer = { throw OutOfMemoryError("daemon killer oom") },
                    ) {
                        spawnEntered.countDown()
                        awaitUninterruptibly(spawnRelease)
                        spawnDone.countDown()
                        proc
                    },
                )
            }.also { it.isDaemon = true; it.start() }
            try {
                assertTrue(spawnEntered.await(10, TimeUnit.SECONDS))
                assertTrue(cancelBlockEntered.await(10, TimeUnit.SECONDS))
                spawnRelease.countDown()
                assertTrue(spawnDone.await(10, TimeUnit.SECONDS))
                cancelGate.countDown()
                // elevated-supervisor 命名同時涵蓋 worker 與 watcher；cancelGate 先行放開 watcher，
                // 此處等兩者皆終止（worker 先行，watcher 隨 caller 讀取結果後退出）才代表 supervisor 完全收斂。
                pollTrue("supervisor worker must terminate after publishing before cancel", 5_000L) {
                    countLive("elevated-supervisor") == 0
                }
                bg.join(10_000L)
                assertFalse(bg.isAlive)
                val res = resRef.get()
                assertTrue("expected Expired, got $res", res is ElevatedCallSupervisor.Res.Expired)
                assertTrue((res as ElevatedCallSupervisor.Res.Expired).hasLinger)
                pollOutstandingTo(0)
                awaitNoElevatedThreads()
                assertTrue("inputStream must be closed", inClosed.get())
                assertTrue("errorStream must be closed", errClosed.get())
                assertTrue("outputStream must be closed", outClosed.get())
                assertEquals("unverified process must be quarantined", 1, ElevatedCleanupTracker.quarantinedCount())
                assertTrue("breaker must count unclean", ElevatedCleanupTracker.consecutiveCount() >= 1)
                assertTrue(ElevatedProcessIo.swallowedKillerThrows.get() > beforeKiller)
            } finally {
                spawnRelease.countDown()
                cancelGate.countDown()
                bg.join(5_000L)
                ElevatedCleanupTracker.clearUnverified()
                pollOutstandingTo(0)
                awaitNoElevatedThreads()
                ElevatedCleanupTracker.noteClean()
            }
        }
    }

    @Test fun drainAbortedStreamAcquisitionRaceAndThrowingClockFailClosed() {
        fun awaitUninterruptibly(latch: CountDownLatch) {
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // 吞中斷模擬不可中斷阻塞。
                }
            }
        }

        // 1. tOut acquires InputStream AFTER runCleanupBounded ran inAcquired.get()?.close():
        // aborted flag must make tOut markFailed, close the newly acquired stream, and skip copyCapped.
        run {
            val killerRan = CountDownLatch(1)
            val streamAcquired = CountDownLatch(1)
            val lateStreamClosed = AtomicBoolean(false)
            val lateStreamReadCalled = AtomicBoolean(false)
            val proc = object : Process() {
                var destroyed = false
                override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
                override fun getInputStream(): InputStream {
                    // Wait until killer has already run inside runCleanupBounded (after inAcquired.get()?.close())
                    awaitUninterruptibly(killerRan)
                    streamAcquired.countDown()
                    return object : InputStream() {
                        override fun read(): Int {
                            lateStreamReadCalled.set(true)
                            return -1
                        }
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            lateStreamReadCalled.set(true)
                            return -1
                        }
                        override fun close() {
                            lateStreamClosed.set(true)
                        }
                    }
                }
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
            val d = ElevatedProcessIo.drainWithThreadStarter(
                process = proc,
                timeoutMs = 2_000L,
                killer = {
                    it.destroy()
                    killerRan.countDown()
                },
                isCancelled = { false },
                startThread = { t ->
                    if (t.name == "elevated-stderr") {
                        throw OutOfMemoryError("simulated OOM on elevated-stderr")
                    }
                    t.start()
                },
            )
            assertTrue(streamAcquired.await(5, TimeUnit.SECONDS))
            assertTrue(d.readFailed)
            assertFalse(d.timedOut)
            assertFalse("tOut must exit without lingering", d.cleanupIncomplete)
            assertTrue("late acquired stream must be closed by tOut", lateStreamClosed.get())
            assertFalse("tOut must skip copyCapped when aborted", lateStreamReadCalled.get())
            pollOutstandingTo(0)
            awaitNoElevatedThreads()
            ElevatedCleanupTracker.noteClean()
        }

        // 2. drain wait loop nowNanos throws AssertionError -> fails closed into kill path (timedOut=true)
        run {
            val clockCalls = AtomicInteger(0)
            val p = FakeProcess("ok".toByteArray(), ByteArray(0), 0, hang = true)
            val d = ElevatedProcessIo.drain(
                process = p,
                timeoutMs = 2_000L,
                killer = { it.destroy() },
                isCancelled = { false },
                nowNanos = {
                    if (clockCalls.incrementAndGet() >= 4) {
                        throw AssertionError("wait-loop clock boom")
                    }
                    1_000_000_000_000L
                },
            )
            assertTrue("clock AssertionError in wait loop must fail closed with timedOut=true", d.timedOut)
            assertEquals(-1, d.exitCode)
            assertTrue(p.destroyed)
            pollOutstandingTo(0)
            awaitNoElevatedThreads()
            ElevatedCleanupTracker.noteClean()
        }

        // 3. awaitWaitSlice Res.Err.hasLinger == true when CancelWatch lingers
        run {
            val waitStarted = AtomicBoolean(false)
            val watchEntered = CountDownLatch(1)
            val watchRelease = CountDownLatch(1)
            val waitRelease = CountDownLatch(1)
            val throwingWaitProc = object : Process() {
                override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
                override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
                override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
                override fun waitFor(): Int = 0
                override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
                    waitStarted.set(true)
                    awaitUninterruptibly(waitRelease)
                    throw RuntimeException("waitFor boom")
                }
                override fun exitValue(): Int = 0
                override fun destroy() = Unit
                override fun destroyForcibly(): Process = this
            }
            val resRef = AtomicReference<ElevatedCallSupervisor.Res<Boolean>?>()
            val bg = Thread {
                resRef.set(
                    ElevatedCallSupervisor.awaitWaitSlice(
                        process = throwingWaitProc,
                        sliceMs = 50L,
                        deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10),
                        isCancelled = {
                            if (waitStarted.get()) {
                                watchEntered.countDown()
                                awaitUninterruptibly(watchRelease)
                            }
                            false
                        },
                    ),
                )
            }.also { it.isDaemon = true; it.start() }
            try {
                assertTrue(watchEntered.await(10, TimeUnit.SECONDS))
                waitRelease.countDown()
                bg.join(10_000L)
                assertFalse(bg.isAlive)
                val res = resRef.get()
                assertTrue("expected Res.Err, got $res", res is ElevatedCallSupervisor.Res.Err)
                assertTrue("awaitWaitSlice Res.Err must set hasLinger=true", (res as ElevatedCallSupervisor.Res.Err).hasLinger)
                assertEquals(1, ElevatedCleanupTracker.outstanding())
            } finally {
                waitRelease.countDown()
                watchRelease.countDown()
                bg.join(5_000L)
                pollOutstandingTo(0)
                awaitNoElevatedThreads()
                ElevatedCleanupTracker.noteClean()
            }
        }
    }

    @Test fun finishedTailBudgetExhaustionGraceSettlesInterruptibleStdoutGetterToFailed() {
        val exec = HermeticExecFixture("p1-tail-grace", "id")
        fun makeFinishedInterruptibleBlockedStdoutProcess(latch: CountDownLatch): Process = object : Process() {
            override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
            override fun getInputStream(): InputStream {
                try {
                    latch.await()
                } catch (e: InterruptedException) {
                    // 不 yield：立即重拋；中斷後 markFailed() 能否在 500ms grace 內結算由
                    // CLEANUP_GRACE_MS 保證，不靠 Thread.yield() 碰排程運氣。
                    Thread.currentThread().interrupt()
                    throw RuntimeException("interrupted stdout getter", e)
                }
                return ByteArrayInputStream(ByteArray(0))
            }
            override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun waitFor(): Int = 0
            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
            override fun exitValue(): Int = 0
            override fun destroy() = Unit
            override fun destroyForcibly(): Process = this
        }
        try {
            // 1. Direct drain: tail budget (2s) exhausts, interrupt() wakes getter,
            //    CLEANUP_GRACE_MS (500ms) lets markFailed() settle and tOut exit cleanly.
            run {
                ElevatedCleanupTracker.noteClean()
                pollOutstandingTo(0)
                val latch = CountDownLatch(1)
                try {
                    val d = ElevatedProcessIo.drain(
                        makeFinishedInterruptibleBlockedStdoutProcess(latch),
                        30_000L,
                    )
                    assertFalse("finished process must keep timedOut=false: $d", d.timedOut)
                    assertEquals(0, d.exitCode)
                    assertTrue("interrupted stdout getter must set readFailed=true: $d", d.readFailed)
                    assertFalse("settled gobbler within CLEANUP_GRACE_MS must keep tailIncomplete=false: $d", d.tailIncomplete)
                    assertFalse("settled gobbler within CLEANUP_GRACE_MS must keep cleanupIncomplete=false: $d", d.cleanupIncomplete)
                    assertFalse("settled gobbler must keep truncated=false: $d", d.truncated)
                    assertEquals(0, ElevatedCleanupTracker.outstanding())
                    assertEquals(0, ElevatedCleanupTracker.consecutiveCount())
                } finally {
                    latch.countDown()
                    pollOutstandingTo(0)
                    awaitNoElevatedThreads()
                    ElevatedCleanupTracker.noteClean()
                }
            }

            // 2. Root & Shizuku runners: settle to Failed (never TimedOut) with cleanupIncomplete=false.
            for (useShizuku in listOf(false, true)) {
                ElevatedCleanupTracker.noteClean()
                pollOutstandingTo(0)
                val latch = CountDownLatch(1)
                val spawnBody: (List<String>) -> Process = {
                    makeFinishedInterruptibleBlockedStdoutProcess(latch)
                }
                val runner: BinaryElevatedRunner = if (useShizuku) {
                    ShizukuShellRunner(
                        privateRoot = privateRoot,
                        bridgeGranted = GrantFixtures.bridgeOn,
                        activationCheck = GrantFixtures.activationOn,
                        pingBinder = { true },
                        spawn = spawnBody,
                        status = { ShizukuStatus.Available(15) },
                        grantCheck = GrantFixtures.allowAlways,
                        execSearchDirs = exec.searchDirs,
                        execAllowedRoots = exec.allowedRoots,
                        execIsWritable = exec.systemOwned,
                    )
                } else {
                    RootSuRunner(
                        privateRoot = privateRoot,
                        bridgeGranted = GrantFixtures.bridgeOn,
                        activationCheck = GrantFixtures.activationOn,
                        spawn = spawnBody,
                        suBinary = "/system/bin/su",
                        grantCheck = GrantFixtures.allowAlways,
                        execSearchDirs = exec.searchDirs,
                        execAllowedRoots = exec.allowedRoots,
                        execIsWritable = exec.systemOwned,
                    )
                }
                try {
                    val res = runner.runBinary(ElevatedRequest(listOf("id"), "P1-TAIL-GRACE"), 30_000L)
                    assertTrue("expected Failed (shizuku=$useShizuku), got $res", res is ElevatedBinaryResult.Failed)
                    res as ElevatedBinaryResult.Failed
                    assertFalse("settled gobbler must keep cleanupIncomplete=false (shizuku=$useShizuku): $res", res.cleanupIncomplete)
                    assertTrue("msg=${res.message}", res.message.contains("elevated IO read failed"))
                    assertEquals(0, ElevatedCleanupTracker.outstanding())
                    assertEquals(0, ElevatedCleanupTracker.consecutiveCount())
                    assertFalse(ElevatedCleanupTracker.isBreakerOpen())
                } finally {
                    latch.countDown()
                    pollOutstandingTo(0)
                    awaitNoElevatedThreads()
                    ElevatedCleanupTracker.noteClean()
                }
            }
        } finally {
            exec.close()
        }
    }

    @Test fun drainKillerThrowableRecoverySetsIncompleteAndCounts() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val before = ElevatedProcessIo.swallowedKillerThrows.get()
        val p = FakeProcess("ok".toByteArray(), ByteArray(0), 0, hang = true)
        try {
            val d = ElevatedProcessIo.drain(
                process = p,
                timeoutMs = 100L,
                killer = { throw OutOfMemoryError("killer boom") },
                isCancelled = { false },
            )
            assertTrue("drain must time out on hanging process", d.timedOut)
            assertTrue(
                "swallowedKillerThrows must increment on throwing killer",
                ElevatedProcessIo.swallowedKillerThrows.get() > before,
            )
            assertTrue("cleanupIncomplete must be true on throwing killer", d.cleanupIncomplete)
            // hang=true 的 FakeProcess 從未被 destroy（killer 拋 OOM 先於 destroy），
            // post-kill verify（waitFor=false）必敗 → handle 轉入 quarantine。
            assertEquals(
                "hung process must be quarantined",
                1,
                ElevatedCleanupTracker.quarantinedCount(),
            )
            pollOutstandingTo(0)
            awaitNoElevatedThreads()
        } finally {
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
        }
    }

    @Test fun drainHangMidVerifyHoldsCleanupSlotUntilVerifyOutcomeKnown() {
        // Stage 3b（slot-ownership transfer）直測：清理 worker 已退出（killer 抛 OOM）
        // 之後，post-kill verify 窗口內 outstanding 不得落回 0——slot 必須橫跨有界
        // verify（本假件 waitFor 切片睡滿 5s）持有至 verify 結果已知、quarantine
        // 先行登記後，caller 才釋放一次（verified-false → quarantine + release）。
        // 舊行為（worker finally 自釋）下 verify 窗口內 outstanding==0，本斷言必掛。
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val p = FakeProcess("ok".toByteArray(), ByteArray(0), 0, hang = true)
        val killEntered = CountDownLatch(1)
        val drainDone = CountDownLatch(1)
        val result = AtomicReference<ElevatedProcessIo.Drained?>()
        val bg = Thread {
            val d = ElevatedProcessIo.drain(
                process = p,
                timeoutMs = 100L,
                killer = {
                    killEntered.countDown()
                    throw OutOfMemoryError("killer boom")
                },
                isCancelled = { false },
            )
            result.set(d)
            drainDone.countDown()
        }.also { it.isDaemon = true; it.start() }
        try {
            assertTrue("killer must be entered", killEntered.await(10, TimeUnit.SECONDS))
            // verify 窗口遠早於其 5s waitFor 上界：slot 必須持續為 1（drain 未返回）。
            pollOutstandingTo(1, boundMs = 10_000L)
            repeat(3) {
                Thread.sleep(250L)
                assertEquals(
                    "cleanup slot must stay held across the post-kill verify window",
                    1,
                    ElevatedCleanupTracker.outstanding(),
                )
            }
            assertFalse(
                "drain must still be inside the bounded verify window",
                drainDone.await(0, TimeUnit.NANOSECONDS),
            )
        } finally {
            bg.join(30_000L)
        }
        val d = result.get()
        assertTrue("drain must return", d != null)
        assertTrue("drain must time out on hanging process", d!!.timedOut)
        assertTrue("cleanupIncomplete must be true on throwing killer", d.cleanupIncomplete)
        // hang=true 的 FakeProcess 從未被 destroy（killer 拋 OOM 先於 destroy），
        // post-kill verify（waitFor=false）必敗 → handle 轉入 quarantine。
        assertEquals(
            "hung process must be quarantined",
            1,
            ElevatedCleanupTracker.quarantinedCount(),
        )
        // caller 於 verify 結果已知後已釋放一次；drain 返回後 outstanding 必為 0。
        pollOutstandingTo(0)
        awaitNoElevatedThreads()
        try {
            bg.join(5_000L)
        } finally {
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
        }
    }

    @Test fun drainBlockingKillerReturnsBoundedAndSetsIncomplete() {
        // Deterministic JVM regression: a killer that never returns must not make
        // drain unbounded. runCleanupBounded must interrupt + grace + set incomplete.
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val killerLatch = CountDownLatch(1)
        fun stuckAwait(latch: CountDownLatch) {
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // swallow interrupt, keep waiting
                }
            }
        }
        val p = FakeProcess("ok".toByteArray(), ByteArray(0), 0, hang = true)
        val t0 = System.currentTimeMillis()
        try {
            val d = ElevatedProcessIo.drain(
                process = p,
                timeoutMs = 100L,
                killer = { stuckAwait(killerLatch) },
                isCancelled = { false },
            )
            val wallMs = System.currentTimeMillis() - t0
            // 上限推導：runCleanupBounded join 上界 min(max(remaining, CLEANUP_MIN_MS),
            // CLEANUP_JOIN_MS=3000) + 超界時 CLEANUP_GRACE_MS≈500 重 join →
            // kill 路徑有界 ≈3.5s（gobbler joins／verify 另有自己的界）；15s 留大餘量，
            // 超界即代表 killer 真卡死（非慢 CI 可致）。
            assertTrue("drain must return bounded under blocking killer, wall=${wallMs}ms", wallMs < 15_000L)
            assertTrue("drain must time out on hanging process", d.timedOut)
            assertTrue("cleanupIncomplete must be true", d.cleanupIncomplete)
            // hang=true 的 FakeProcess 從未被 destroy（killer 永久阻塞，destroy 未執行），
            // post-kill verify（waitFor=false）必敗 → handle 轉入 quarantine。
            assertEquals(
                "hung process must be quarantined",
                1,
                ElevatedCleanupTracker.quarantinedCount(),
            )
            // Release the blocking killer so the detached worker can exit.
            killerLatch.countDown()
            pollOutstandingTo(0)
            awaitNoElevatedThreads()
        } finally {
            killerLatch.countDown()
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
        }
    }

    @Test fun rootRawSuResLingerPathMarksTimedOut() {
        fun awaitUninterruptibly(latch: CountDownLatch) {
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // swallow
                }
            }
        }
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val suEntered = CountDownLatch(1)
        val suRelease = CountDownLatch(1)
        val watchEntered = CountDownLatch(1)
        val watchRelease = CountDownLatch(1)
        val suStarted = AtomicBoolean(false)
        val resultRef = AtomicReference<ElevatedBinaryResult?>()
        val bg = Thread {
            val runner = RootSuRunner(
                privateRoot = privateRoot,
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                spawn = { FakeProcess("ok".toByteArray()) },
                suBinary = null,
                suExists = { _ ->
                    suStarted.set(true)
                    suEntered.countDown()
                    awaitUninterruptibly(suRelease)
                    true
                },
                isCancelled = {
                    if (suStarted.get()) {
                        watchEntered.countDown()
                        awaitUninterruptibly(watchRelease)
                    }
                    false
                },
                grantCheck = GrantFixtures.allowAlways,
            )
            resultRef.set(runner.runBinary(ElevatedRequest(listOf("id"), "P1-RAW-SU-LINGER"), 5_000L))
        }.also { it.isDaemon = true; it.start() }
        try {
            assertTrue(suEntered.await(10, TimeUnit.SECONDS))
            assertTrue(watchEntered.await(10, TimeUnit.SECONDS))
            suRelease.countDown()
            bg.join(10_000L)
            assertFalse(bg.isAlive)
            val res = resultRef.get()
            assertTrue("expected TimedOut, got $res", res is ElevatedBinaryResult.TimedOut)
            assertTrue("rawSuRes linger must set cleanupIncomplete", (res as ElevatedBinaryResult.TimedOut).cleanupIncomplete)
        } finally {
            suRelease.countDown()
            watchRelease.countDown()
            bg.join(5_000L)
            pollOutstandingTo(0)
            awaitNoElevatedThreads()
            ElevatedCleanupTracker.noteClean()
        }
    }
}
