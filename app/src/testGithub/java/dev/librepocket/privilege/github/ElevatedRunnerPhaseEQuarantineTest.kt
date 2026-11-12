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
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round-3 Phase E 未驗證死亡 process 持久 quarantine 迴歸（P1-1，PR #140）：
 * - late 內聯補償 no-op kill（verify=false）／正常 drain post-kill verify=false：
 *   worker 槽釋放（outstanding→0）、breaker 未開（consecutive==1）後，
 *   未驗證 handle 仍進 quarantine 登記，第二次 call 必須 0 spawn＋明確
 *   `Denied(QUARANTINE)`（不與 BACKLOG／BREAKER 混碼）；
 * - 正常成功 call 的 `noteClean` 不得清除 quarantine（quarantine 計數與
 *   consecutive breaker 計數分離）；
 * - 解除只兩途：確死（有界重驗見 `waitFor(0)==true` 即自動解除）或
 *   `clearUnverified` 顯式 recovery（B3 已驗證 recovery／測試隔離）。
 *
 * Root＋Shizuku 雙軌，全 fake，零真機、零 su、零 binder。
 * 排序一律 latch／barrier（polling 為 barrier，不靠 sleep 時序）：
 * 存活判定經 `waitFor(0)` 即時查詢（NoOpHang 恒 false、LatchDeath 按原子旗標翻轉），
 * 死亡翻轉經 [AtomicBoolean] 確定性觸發，不睡等待。
 */
class ElevatedRunnerPhaseEQuarantineTest {

    private val privateRoot = "/data/data/files"

    @Before fun setUpIsolation() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
    }

    @After fun tearDownIsolation() {
        ElevatedCleanupTracker.clearUnverified()
        repeat(8) { ElevatedCleanupTracker.releaseSlot() }
        repeat(8) { ElevatedCleanupTracker.leaveCall() }
        pollOutstandingTo(0)
        pollActiveTo(0)
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

    private fun <T> pollRefNonNull(ref: AtomicReference<T>, what: String, boundMs: Long = 10_000L): T {
        val deadline = System.currentTimeMillis() + boundMs
        while (true) {
            val v = ref.get()
            if (v != null) return v
            if (System.currentTimeMillis() >= deadline) throw AssertionError("$what was never delivered")
            Thread.sleep(20L)
        }
    }

    private fun pollTrue(what: String, boundMs: Long = 10_000L, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + boundMs
        while (!cond()) {
            if (System.currentTimeMillis() >= deadline) throw AssertionError("$what did not become true")
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
            "elevated-late-spawn",
            "elevated-wait",
            "elevated-verify",
            "elevated-quarantine-verify",
            // Round-5 Phase Q：背景 sweep owner（即席 daemon，單次即退）。
            "elevated-quarantine-sweep",
        )
        val deadline = System.currentTimeMillis() + boundMs
        while (true) {
            val alive = Thread.getAllStackTraces().keys.filter { it.name in names && it.isAlive }
            if (alive.isEmpty()) return
            if (System.currentTimeMillis() >= deadline) throw AssertionError("orphan elevated threads: $alive")
            Thread.sleep(50L)
        }
    }

    private fun stuckAwait(latch: CountDownLatch) {
        while (true) {
            try {
                latch.await()
                break
            } catch (_: InterruptedException) {
                // 吞 interrupt 續等（不可靠中斷）。
            }
        }
    }

    /** destroy no-op＋waitFor 恒 false：killer 調了等於沒調，驗證必失敗（恒存活）。 */
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

    /**
     * 確定性生死假件：`waitFor(timeout)` 即時回（不睡，deterministic latch 語義），
     * `death==false` 即存活（回 false；`exitValue` 抛表未退），`death==true`
     * 即確死（回 true；`exitValue` 回 0）。destroy 恒 no-op（killer 殺不死，
     * 只能經外部原子旗標放行死亡，模擬「補償失敗後 OS 進程稍後才真退」）。
     */
    private class LatchDeathProcess(private val death: AtomicBoolean) : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            while (!death.get()) {
                try {
                    Thread.sleep(20L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            return 0
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = death.get()
        override fun exitValue(): Int {
            if (!death.get()) throw IllegalThreadStateException("process has not exited")
            return 0
        }
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }

    /**
     * Round-4 Phase M stall 回歸模型（P1-3）：跨 Binder `waitFor(0)` 無時限保證——
     * 永不返回且忽略中斷（`latch` 放行前一直等，`InterruptedException` 吞掉續等，
     * 含 `timeout==0`）。舊碼在呼叫側直調 `waitFor(0)` 即無界卡死（try/catch
     * 攔不住 blocking IPC）；新碼 IPC 只跑背景 `elevated-quarantine-sweep`
     * owner＋內層 `elevated-quarantine-verify` worker（Round-5 Phase Q：
     * 呼叫側只做本地快照、零 IPC 永不等待，見 [ElevatedCleanupTracker.kickSweepAsync]），
     * 呼叫側有界拒（逾期記 sweepPending 保持 quarantine）。
     * `latch` 僅供 teardown 放行收斂（測中永不放行）。
     */
    private class StallQuarantineProcess(private val latch: CountDownLatch) : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // 吞 interrupt 續等。
                }
            }
            return 0
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // 吞 interrupt 續等（不可靠中斷，呼叫方有界返回，內層殘留追蹤）。
                }
            }
            return false
        }
        override fun exitValue(): Int = throw IllegalThreadStateException("process has not exited")
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }

    private fun rootRunner(
        spawn: (List<String>) -> Process,
        killer: (Process) -> Unit = { it.destroy() },
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

    private fun shizukuRunner(
        spawn: (List<String>) -> Process,
        killer: (Process) -> Unit = { it.destroy() },
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

    private fun assertQuarantineDenied(
        denied: ElevatedBinaryResult,
        spawnsBefore: Int,
        spawns: AtomicInteger,
    ) {
        assertTrue("$denied", denied is ElevatedBinaryResult.Denied)
        denied as ElevatedBinaryResult.Denied
        assertEquals(ShellDeny.QUARANTINE, denied.reason)
        assertTrue("msg=${denied.message}", denied.message.contains("quarantine"))
        assertTrue(
            "quarantine deny must keep incomplete signal, got $denied",
            denied.cleanupIncomplete,
        )
        assertEquals("quarantined spawn must not allow new spawn", spawnsBefore, spawns.get())
    }

    // ---- 1. late 內聯快速補償 no-op kill → quarantine → 次 call 零 spawn＋QUARANTINE ----
    //
    // spawn 阻塞→caller 先超時→放行後 worker 發佈逾期值並內聯補償
    // （killer no-op＋waitFor=false＋verify=false）：outstanding 歸零、
    // consecutive==1、breaker 關，但 handle 未驗證死亡，必須進 quarantine；
    // 次 call（process 仍 alive，`waitFor(0)==false` 確定性斷言）必須 0 spawn＋
    // 明確 QUARANTINE 拒絕（不與 BACKLOG／BREAKER 混碼）。

    private fun doLateNoOpKillQuarantines(makeRunner: (
        spawn: (List<String>) -> Process,
        killer: (Process) -> Unit,
    ) -> BinaryElevatedRunner) {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        val release = CountDownLatch(1)
        val lateRef = AtomicReference<Process?>()
        val spawns = AtomicInteger(0)
        val kills = AtomicInteger(0)
        val runner = makeRunner(
            {
                stuckAwait(release)
                spawns.incrementAndGet()
                NoOpHangProcess().also { lateRef.set(it) }
            },
            {
                kills.incrementAndGet()
                // no-op：調了等於沒調（不置旗不抛）。
            },
        )
        try {
            val r = runner.runBinary(ElevatedRequest(listOf("id"), "PE-LATE"), 300L)
            assertTrue("$r", r is ElevatedBinaryResult.TimedOut)
            r as ElevatedBinaryResult.TimedOut
            assertTrue("worker-late gap must flag, got $r", r.cleanupIncomplete)
            assertEquals(1, ElevatedCleanupTracker.outstanding())
            assertEquals(0, kills.get())
            // 放行→worker 發佈＋內聯快速補償（no-op＋false，verify 即時 false）。
            release.countDown()
            pollRefNonNull(lateRef, "late no-op spawn")
            pollTrue("killer must have run inline") { kills.get() == 1 }
            pollOutstandingTo(0)
            pollActiveTo(0)
            // breaker 恰好一次，不熔斷（既有語義保持）。
            assertEquals(1, ElevatedCleanupTracker.consecutiveCount())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            // Phase E：槽全釋放後未驗證 handle 必須仍被 quarantine 持有。
            assertTrue(ElevatedCleanupTracker.isQuarantined())
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            // process 仍 alive（確定性即時查詢，不靠 sleep 時序）。
            val late = lateRef.get()!!
            assertFalse(
                "late process must still be alive",
                late.waitFor(0L, TimeUnit.MILLISECONDS),
            )
            awaitNoElevatedThreads()
            // 次 call：0 spawn＋明確 QUARANTINE 拒絕。
            val denied = runner.runBinary(ElevatedRequest(listOf("id"), "PE-LATE"), 2_000L)
            assertQuarantineDenied(denied, 1, spawns)
            assertTrue(ElevatedCleanupTracker.isQuarantined())
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            awaitNoElevatedThreads()
        } finally {
            release.countDown()
            pollOutstandingTo(0)
            pollActiveTo(0)
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    @Test fun rootLateNoOpKillQuarantinesAndBlocksNextSpawn() {
        doLateNoOpKillQuarantines { s, k -> rootRunner(spawn = s, killer = k) }
    }

    @Test fun shizukuLateNoOpKillQuarantinesAndBlocksNextSpawn() {
        doLateNoOpKillQuarantines { s, k -> shizukuRunner(spawn = s, killer = k) }
    }

    // ---- 2. 正常 drain post-kill verify=false → quarantine → 次 call 零 spawn＋QUARANTINE ----
    //
    // spawn 即時交付（budget 內）→drain 等 deadline→kill（no-op）→verify=false：
    // 首 call 即 `TimedOut(cleanupIncomplete=true)`；outstanding 歸零、
    // consecutive==1 後，handle 未驗證死亡，必須進 quarantine；次 call 必須
    // 0 spawn＋明確 QUARANTINE 拒絕。

    private fun doNormalDrainNoOpKillQuarantines(makeRunner: (
        spawn: (List<String>) -> Process,
        killer: (Process) -> Unit,
    ) -> BinaryElevatedRunner) {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        val spawns = AtomicInteger(0)
        val kills = AtomicInteger(0)
        val liveRef = AtomicReference<Process?>()
        val runner = makeRunner(
            {
                spawns.incrementAndGet()
                NoOpHangProcess().also { liveRef.set(it) }
            },
            {
                kills.incrementAndGet()
                // no-op：調了等於沒調。
            },
        )
        try {
            val r = runner.runBinary(ElevatedRequest(listOf("id"), "PE-DRAIN"), 300L)
            assertTrue("$r", r is ElevatedBinaryResult.TimedOut)
            r as ElevatedBinaryResult.TimedOut
            assertTrue("post-kill unverified must flag, got $r", r.cleanupIncomplete)
            pollTrue("killer must have run") { kills.get() == 1 }
            pollOutstandingTo(0)
            pollActiveTo(0)
            assertEquals(1, ElevatedCleanupTracker.consecutiveCount())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            // Phase E：drain 槽全釋放後未驗證 handle 必須進 quarantine。
            assertTrue(ElevatedCleanupTracker.isQuarantined())
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            val live = liveRef.get()!!
            assertFalse(
                "drained process must still be alive",
                live.waitFor(0L, TimeUnit.MILLISECONDS),
            )
            awaitNoElevatedThreads()
            // 次 call：0 spawn＋明確 QUARANTINE 拒絕。
            val denied = runner.runBinary(ElevatedRequest(listOf("id"), "PE-DRAIN"), 2_000L)
            assertQuarantineDenied(denied, 1, spawns)
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            awaitNoElevatedThreads()
        } finally {
            pollOutstandingTo(0)
            pollActiveTo(0)
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    @Test fun rootNormalDrainNoOpKillQuarantinesAndBlocksNextSpawn() {
        doNormalDrainNoOpKillQuarantines { s, k -> rootRunner(spawn = s, killer = k) }
    }

    @Test fun shizukuNormalDrainNoOpKillQuarantinesAndBlocksNextSpawn() {
        doNormalDrainNoOpKillQuarantines { s, k -> shizukuRunner(spawn = s, killer = k) }
    }

    // ---- 3. 正常成功 call 的 noteClean 不得清除 quarantine（計數分離） ----
    //
    // quarantine 建立後：直接 `noteClean`（模擬後續成功 drain 在 outstanding==0
    // 時的計數重置）必須只歸零 consecutive、保留 quarantine；隨後一次「本可成功」
    // 的 call（好 spawn）仍須 0 spawn＋QUARANTINE 拒絕，quarantine 計數不變。

    private fun doSuccessDoesNotClearQuarantine(makeRunner: (
        spawn: (List<String>) -> Process,
        killer: (Process) -> Unit,
    ) -> BinaryElevatedRunner) {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        val spawns = AtomicInteger(0)
        val runner = makeRunner(
            {
                spawns.incrementAndGet()
                // 首 call 交未驗證 handle，後續交可成功 handle（若准入放行）。
                if (spawns.get() == 1) NoOpHangProcess() else FakeProcess("ok".toByteArray())
            },
            {
                // no-op killer：首 call 驗證必失敗。
            },
        )
        try {
            val r = runner.runBinary(ElevatedRequest(listOf("id"), "PE-SEP"), 300L)
            assertTrue("$r", r is ElevatedBinaryResult.TimedOut)
            assertTrue((r as ElevatedBinaryResult.TimedOut).cleanupIncomplete)
            pollOutstandingTo(0)
            pollActiveTo(0)
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            assertEquals(1, ElevatedCleanupTracker.consecutiveCount())
            // 成功路徑的 noteClean：consecutive 歸零，但 quarantine 必須保留。
            ElevatedCleanupTracker.noteClean()
            assertEquals(
                "noteClean must reset consecutive, got ${ElevatedCleanupTracker.consecutiveCount()}",
                0,
                ElevatedCleanupTracker.consecutiveCount(),
            )
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            assertEquals(
                "noteClean must not clear quarantine, got ${ElevatedCleanupTracker.quarantinedCount()}",
                1,
                ElevatedCleanupTracker.quarantinedCount(),
            )
            assertTrue(ElevatedCleanupTracker.isQuarantined())
            // 「本可成功」的次 call 仍須 0 spawn＋QUARANTINE 拒絕。
            val denied = runner.runBinary(ElevatedRequest(listOf("id"), "PE-SEP"), 2_000L)
            assertQuarantineDenied(denied, 1, spawns)
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            awaitNoElevatedThreads()
        } finally {
            pollOutstandingTo(0)
            pollActiveTo(0)
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    @Test fun rootSuccessDoesNotClearQuarantine() {
        doSuccessDoesNotClearQuarantine { s, k -> rootRunner(spawn = s, killer = k) }
    }

    @Test fun shizukuSuccessDoesNotClearQuarantine() {
        doSuccessDoesNotClearQuarantine { s, k -> shizukuRunner(spawn = s, killer = k) }
    }

    // ---- 4. 確死即自動解除（有界重驗回收）：LatchDeath 確定性翻轉 ----
    //
    // quarantine 建立後 process 仍 alive 即拒；外部原子旗標翻轉死亡後，
    // 下一次 call 的准入前 sweep（`waitFor(0)==true`）即解除，spawn 放行、
    // drain 正常成功（Ok，非 incomplete）。

    private fun doVerifiedDeathClearsQuarantine(makeRunner: (
        spawn: (List<String>) -> Process,
        killer: (Process) -> Unit,
    ) -> BinaryElevatedRunner) {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        val death = AtomicBoolean(false)
        val live = LatchDeathProcess(death)
        val spawns = AtomicInteger(0)
        val runner = makeRunner(
            {
                spawns.incrementAndGet()
                if (spawns.get() == 1) live else FakeProcess("ok".toByteArray())
            },
            {
                // no-op killer：首 call 驗證必失敗（死亡只能經外部旗標）。
            },
        )
        try {
            val r = runner.runBinary(ElevatedRequest(listOf("id"), "PE-REVIVE"), 300L)
            assertTrue("$r", r is ElevatedBinaryResult.TimedOut)
            assertTrue((r as ElevatedBinaryResult.TimedOut).cleanupIncomplete)
            pollOutstandingTo(0)
            pollActiveTo(0)
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            // 存活期間拒絕（0 spawn）。
            val denied = runner.runBinary(ElevatedRequest(listOf("id"), "PE-REVIVE"), 2_000L)
            assertQuarantineDenied(denied, 1, spawns)
            // 確定性死亡翻轉（不靠 sleep 時序）。
            death.set(true)
            assertTrue(live.waitFor(0L, TimeUnit.MILLISECONDS))
            // Round-5 Phase Q：解除為背景重掃（見 kickSweepAsync）——死亡翻轉後
            // 首次 call 觸發 kick 但仍可能因競態先拒（准入快照先於背景清除），
            // 故有界重試直到 Ok（每次 Denied 皆 QUARANTINE 且觸發一次 kick，
            // singleflight 下不疊加 verifier；確死條目背景即時清除，無 stall
            // 等待，兩輪內必收斂）。
            val okDeadline = System.currentTimeMillis() + 15_000L
            var ok: ElevatedBinaryResult.Ok? = null
            while (System.currentTimeMillis() < okDeadline) {
                when (val r = runner.runBinary(ElevatedRequest(listOf("id"), "PE-REVIVE"), 10_000L)) {
                    is ElevatedBinaryResult.Ok -> {
                        ok = r
                        break
                    }
                    is ElevatedBinaryResult.Denied -> {
                        assertEquals(ShellDeny.QUARANTINE, r.reason)
                        assertTrue(r.cleanupIncomplete)
                    }
                    else -> throw AssertionError("unexpected verdict during revive: $r")
                }
                Thread.sleep(50L)
            }
            val recovered = ok ?: throw AssertionError("background sweep never cleared verified death")
            assertFalse("recovered call must be verified clean", recovered.cleanupIncomplete)
            assertEquals(2, spawns.get())
            assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
            assertFalse(ElevatedCleanupTracker.isQuarantined())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
            pollOutstandingTo(0)
            pollActiveTo(0)
            awaitNoElevatedThreads()
        } finally {
            death.set(true)
            pollOutstandingTo(0)
            pollActiveTo(0)
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    @Test fun rootVerifiedDeathClearsQuarantine() {
        doVerifiedDeathClearsQuarantine { s, k -> rootRunner(spawn = s, killer = k) }
    }

    @Test fun shizukuVerifiedDeathClearsQuarantine() {
        doVerifiedDeathClearsQuarantine { s, k -> shizukuRunner(spawn = s, killer = k) }
    }

    // ---- 5. 顯式 clearUnverified recovery ----
    //
    // quarantine 建立後次 call 被拒；`clearUnverified`（B3 已驗證 recovery／
    // 測試隔離語義）後 spawn 放行且 drain 成功；空集合上調用為安全 no-op。

    private fun doExplicitClearRecovers(makeRunner: (
        spawn: (List<String>) -> Process,
        killer: (Process) -> Unit,
    ) -> BinaryElevatedRunner) {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        val spawns = AtomicInteger(0)
        val runner = makeRunner(
            {
                spawns.incrementAndGet()
                if (spawns.get() == 1) NoOpHangProcess() else FakeProcess("ok".toByteArray())
            },
            {
                // no-op killer。
            },
        )
        try {
            val r = runner.runBinary(ElevatedRequest(listOf("id"), "PE-CLEAR"), 300L)
            assertTrue("$r", r is ElevatedBinaryResult.TimedOut)
            assertTrue((r as ElevatedBinaryResult.TimedOut).cleanupIncomplete)
            pollOutstandingTo(0)
            pollActiveTo(0)
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            val denied = runner.runBinary(ElevatedRequest(listOf("id"), "PE-CLEAR"), 2_000L)
            assertQuarantineDenied(denied, 1, spawns)
            // 顯式 recovery：解除後 spawn 放行且 drain 成功。
            ElevatedCleanupTracker.clearUnverified()
            assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
            assertFalse(ElevatedCleanupTracker.isQuarantined())
            val ok = runner.runBinary(ElevatedRequest(listOf("id"), "PE-CLEAR"), 10_000L)
            assertTrue("$ok", ok is ElevatedBinaryResult.Ok)
            assertFalse((ok as ElevatedBinaryResult.Ok).cleanupIncomplete)
            assertEquals(2, spawns.get())
            // 空集合上調用為安全 no-op。
            ElevatedCleanupTracker.clearUnverified()
            assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
            pollOutstandingTo(0)
            pollActiveTo(0)
            awaitNoElevatedThreads()
        } finally {
            pollOutstandingTo(0)
            pollActiveTo(0)
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            awaitNoElevatedThreads()
        }
    }

    @Test fun rootExplicitClearRecovers() {
        doExplicitClearRecovers { s, k -> rootRunner(spawn = s, killer = k) }
    }

    @Test fun shizukuExplicitClearRecovers() {
        doExplicitClearRecovers { s, k -> shizukuRunner(spawn = s, killer = k) }
    }

    // ---- 6. tracker 語義單測（冪等／分離／准入末門／sweep／clear） ----
    @Test fun quarantineTrackerSemantics() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        repeat(8) { ElevatedCleanupTracker.leaveCall() }
        var entered = false
        try {
            val p = NoOpHangProcess()
            // 冪等：同一 handle 重複登記只記一次。
            ElevatedCleanupTracker.quarantineUnverified(p)
            ElevatedCleanupTracker.quarantineUnverified(p)
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            assertTrue(ElevatedCleanupTracker.isQuarantined())
            // 雙向分離：noteClean 不清除 quarantine；noteUnclean 不影響 quarantine。
            ElevatedCleanupTracker.noteClean()
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            ElevatedCleanupTracker.noteUnclean()
            assertEquals(1, ElevatedCleanupTracker.consecutiveCount())
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            ElevatedCleanupTracker.noteClean()
            assertEquals(0, ElevatedCleanupTracker.consecutiveCount())
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            // 准入末門：QUARANTINE 獨立碼，不佔席（active 不變）。
            assertEquals(
                ElevatedCleanupTracker.Admission.QUARANTINE,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            // 存活條目 sweep 不清除（0 清除）。
            assertEquals(0, ElevatedCleanupTracker.sweepQuarantine())
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            // 確死條目 sweep 清除（只清確死，存活保留）。
            val dead = FakeProcess("ok".toByteArray())
            ElevatedCleanupTracker.quarantineUnverified(dead)
            assertEquals(2, ElevatedCleanupTracker.quarantinedCount())
            assertEquals(1, ElevatedCleanupTracker.sweepQuarantine())
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            // 顯式解除後准入恢復。
            ElevatedCleanupTracker.clearUnverified()
            assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
            assertFalse(ElevatedCleanupTracker.isQuarantined())
            assertEquals(
                ElevatedCleanupTracker.Admission.OK,
                ElevatedCleanupTracker.tryEnterReserved(),
            )
            entered = true
        } finally {
            if (entered) ElevatedCleanupTracker.leaveCall()
            repeat(8) { ElevatedCleanupTracker.leaveCall() }
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
        }
        assertEquals(0, ElevatedCleanupTracker.activeCalls())
        assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
    }

    // ---- 7. Round-4 Phase M stall 有界化（P1-3，Root＋Shizuku 雙軌）
    //      ＋Round-5 Phase Q 非同步化（P2-4） ----
    //
    // quarantined 遠端 `waitFor(0)` 永不返回且忽略中斷（跨 Binder 無時限保證模型）＋
    // 並行多 caller：各呼叫必須有界 `Denied(QUARANTINE)`（Round-5 Phase Q 起含贏者
    // 在內一律零等待即拒——重掃跑背景 `elevated-quarantine-sweep`，呼叫側只做本地
    // 快照，見 [ElevatedCleanupTracker.kickSweepAsync]）、
    // 零新 spawn（quarantine 擋在 spawn 之前）、無 untracked checker thread
    // （同時至多一背景 sweep owner＋一 `elevated-quarantine-verify` 活躍內層＋
    // 一已登記殘留，`outstanding==0` 不動、`sweepPending==1` 可斷；舊碼呼叫側直調
    // 即多 Binder 並行卡死，連應立刻回的 Denied 都拿不到）。
    // 排序一律 latch／barrier，不靠 sleep 時序；`latch` 僅 teardown 放行收斂。

    private fun pollPendingTo(expected: Int, boundMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + boundMs
        while (ElevatedCleanupTracker.sweepPendingCount() != expected) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError(
                    "sweepPending=${ElevatedCleanupTracker.sweepPendingCount()} expected=$expected",
                )
            }
            Thread.sleep(20L)
        }
    }

    private fun aliveQuarantineVerifyCount(): Int =
        Thread.getAllStackTraces().keys.count { it.name == "elevated-quarantine-verify" && it.isAlive }

    private fun doStallParallelBoundedDenied(makeRunner: (
        spawn: (List<String>) -> Process,
        killer: (Process) -> Unit,
    ) -> BinaryElevatedRunner) {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        pollPendingTo(0)
        val latch = CountDownLatch(1)
        val stall = StallQuarantineProcess(latch)
        ElevatedCleanupTracker.quarantineUnverified(stall)
        assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
        assertEquals(0, ElevatedCleanupTracker.sweepPendingCount())
        val spawns = AtomicInteger(0)
        val runner = makeRunner(
            {
                spawns.incrementAndGet()
                FakeProcess("ok".toByteArray())
            },
            {
                // killer 不應被調（quarantine 擋在 spawn 之前，零 spawn 即零 kill）。
            },
        )
        val callers = 4
        val startGate = CountDownLatch(1)
        val results = arrayOfNulls<ElevatedBinaryResult>(callers)
        val elapsedMs = LongArray(callers)
        val threads = (0 until callers).map { i ->
            Thread({
                try {
                    startGate.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                val t0 = System.currentTimeMillis()
                val r = runner.runBinary(ElevatedRequest(listOf("id"), "PE-STALL"), 5_000L)
                elapsedMs[i] = System.currentTimeMillis() - t0
                results[i] = r
            }).also { it.isDaemon = true }
        }
        try {
            threads.forEach { it.start() }
            startGate.countDown()
            threads.forEach { it.join(20_000L) }
            threads.forEachIndexed { i, t ->
                assertFalse("caller $i must return bounded (stall must not pin caller)", t.isAlive)
            }
            results.forEachIndexed { i, r ->
                assertTrue("caller $i must deny, got $r", r is ElevatedBinaryResult.Denied)
                r as ElevatedBinaryResult.Denied
                assertEquals("caller $i must be QUARANTINE, got $r", ShellDeny.QUARANTINE, r.reason)
                assertTrue("caller $i msg=${r.message}", r.message.contains("quarantine"))
                assertTrue("caller $i must keep incomplete signal, got $r", r.cleanupIncomplete)
                assertTrue(
                    "caller $i must be bounded without waiting verifier (elapsed=${elapsedMs[i]}ms)",
                    elapsedMs[i] < 2_000L,
                )
            }
            assertEquals("quarantined stall must allow zero new spawn", 0, spawns.get())
            assertTrue(ElevatedCleanupTracker.isQuarantined())
            assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
            // sweep 獨立 permit：不碰槽／計數器（仍報 QUARANTINE，不翻轉 BACKLOG）。
            assertEquals(0, ElevatedCleanupTracker.outstanding())
            assertEquals(0, ElevatedCleanupTracker.activeCalls())
            // singleflight＋pending：至多一背景 sweep owner＋一活躍內層＋一已登記殘留，
            // 無 per-caller 爆炸（重試 kick 直到背景登記 pending：前測殘留 sweep
            // 若仍持 lease，本次 kick 即讓路，重試即收斂，不疊加 verifier）。
            pollTrue("background sweep must track stall") {
                ElevatedCleanupTracker.kickSweepAsync()
                ElevatedCleanupTracker.sweepPendingCount() == 1
            }
            // 可見性屏障：pending==1 可能搶在 worker.start() 之前可見，
            // 先等 verifier 線程現身再採 baseline，否則 aliveBefore 在 0 撕裂、
            // owner 隨後啟動 W1 即誤判「新生 checker」。
            pollTrue("quarantine verifier must be visible", boundMs = 10_000L) {
                aliveQuarantineVerifyCount() >= 1
            }
            val alive = aliveQuarantineVerifyCount()
            assertTrue(
                "at most one tracked quarantine-verify may linger, got $alive",
                alive <= 1,
            )
            // 後續 sweep 跳過 pending（零新線程、零等待即 0，不再累積殘留）。
            val aliveBefore = aliveQuarantineVerifyCount()
            val t0 = System.currentTimeMillis()
            assertEquals(0, ElevatedCleanupTracker.sweepQuarantine())
            val sweepElapsed = System.currentTimeMillis() - t0
            assertTrue("skip-pending sweep must be fast, elapsed=$sweepElapsed", sweepElapsed < 2_000L)
            assertEquals(1, ElevatedCleanupTracker.sweepPendingCount())
            assertTrue(
                "skip-pending sweep must not spawn new checker (before=$aliveBefore)",
                aliveQuarantineVerifyCount() <= aliveBefore,
            )
            assertEquals(0, spawns.get())
        } finally {
            // 放行 stall 內層收斂（worker 回 false，finally 移除 pending），再隔離。
            latch.countDown()
            pollPendingTo(0)
            threads.forEach { it.join(5_000L) }
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
        assertEquals(0, spawns.get())
        assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
    }

    @Test fun rootStallQuarantineParallelBoundedDenied() {
        doStallParallelBoundedDenied { s, k -> rootRunner(spawn = s, killer = k) }
    }

    @Test fun shizukuStallQuarantineParallelBoundedDenied() {
        doStallParallelBoundedDenied { s, k -> shizukuRunner(spawn = s, killer = k) }
    }

    // ---- 8. Round-5 Phase Q caller wall 內化（P2-4，Root＋Shizuku 雙軌） ----
    //
    // stalled quarantine handle 存在時，新 runBinary(timeoutMs=10／100) 必須在
    // deadline 內拿到 `Denied(QUARANTINE)`（舊碼 sweep 在整體 deadline 之外同步
    // 等 `SWEEP_VERIFY_MS＋SWEEP_GRACE_MS` 近 1s 才拒；新碼呼叫側只做本地快照＋
    // 背景 kick，wall 零依賴遠端）。wall 上界取 500ms：正常毫秒級返回，舊 1s
    // 行為必超界，CI 慢機亦有餘量。

    private fun doStalledQuarantineSmallTimeoutWallBounded(makeRunner: (
        spawn: (List<String>) -> Process,
        killer: (Process) -> Unit,
    ) -> BinaryElevatedRunner) {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        pollPendingTo(0)
        val latch = CountDownLatch(1)
        val stall = StallQuarantineProcess(latch)
        ElevatedCleanupTracker.quarantineUnverified(stall)
        assertEquals(1, ElevatedCleanupTracker.quarantinedCount())
        val spawns = AtomicInteger(0)
        val runner = makeRunner(
            {
                spawns.incrementAndGet()
                FakeProcess("ok".toByteArray())
            },
            {
                // killer 不應被調（quarantine 擋在 spawn 之前，零 spawn 即零 kill）。
            },
        )
        try {
            for (timeoutMs in listOf(10L, 100L)) {
                val before = spawns.get()
                val t0 = System.nanoTime()
                val r = runner.runBinary(ElevatedRequest(listOf("id"), "PE-WALL"), timeoutMs)
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
                assertQuarantineDenied(r, before, spawns)
                assertTrue(
                    "timeoutMs=$timeoutMs stalled quarantine must deny within " +
                        "deadline-internal budget, elapsed=${elapsedMs}ms",
                    elapsedMs < 500L,
                )
            }
            assertEquals("quarantined stall must allow zero new spawn", 0, spawns.get())
            assertTrue(ElevatedCleanupTracker.isQuarantined())
        } finally {
            // 放行 stall 內層收斂（背景 sweep worker 回 false，finally 移除 pending），再隔離。
            latch.countDown()
            pollPendingTo(0)
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
            pollOutstandingTo(0)
            pollActiveTo(0)
            awaitNoElevatedThreads()
        }
        assertEquals(0, spawns.get())
        assertEquals(0, ElevatedCleanupTracker.quarantinedCount())
    }

    @Test fun rootStalledQuarantineSmallTimeoutWallBounded() {
        doStalledQuarantineSmallTimeoutWallBounded { s, k -> rootRunner(spawn = s, killer = k) }
    }

    @Test fun shizukuStalledQuarantineSmallTimeoutWallBounded() {
        doStalledQuarantineSmallTimeoutWallBounded { s, k -> shizukuRunner(spawn = s, killer = k) }
    }
}
