package dev.librepocket.privilege.github

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-6（#136-B2 review D-C9×3 / PG-1 精簡並入）：ElevatedCallSupervisor／ElevatedProcessIo
 * 時鐘接縫的 fail-closed 回歸。
 *
 * 覆蓋：computeDeadlineNanos 的非正 timeout／上溢夾頂、isExpired 拋鐘時的收斂契約
 * （收斂點在 call site，見各測試體 KDoc）、awaitPreSpawn 入口拋鐘直回
 * [ElevatedCallSupervisor.Res.Expired] 且 block 從不執行、drain 入口拋鐘進
 * `entryClockFailed` ⇒ preflightExpired kill 臂（有界返回 Drained(timedOut=true)）。
 *
 * 注意：`isExpired` 本身不吞拋——fail-closed 收斂（拋⇒視為逾期）由所有 call site
 * 的 `try/catch` 落實；本檔如實釘住該分工（見 [isExpiredClockThrowPropagatesRaw]）。
 */
class ElevatedClockFallbackTest {
    @Test fun computeDeadlineNanosNegativeTimeoutReturnsNow() {
        val now = 1_000_000_000L
        assertEquals(now, ElevatedProcessIo.computeDeadlineNanos(-1, { now }))
    }

    @Test fun computeDeadlineNanosOverflowClampsToLongHalfMax() {
        // Long.MAX_VALUE ms → 內部 multiplyExact 上溢 ⇒ 回落 Long.MAX_VALUE/2
        // （now==0 時 addExact 不再上溢；見 computeDeadlineNanos 實作）。
        val now = 0L
        assertEquals(
            Long.MAX_VALUE / 2,
            ElevatedProcessIo.computeDeadlineNanos(Long.MAX_VALUE, { now }),
        )
    }

    @Test fun computeDeadlineNanosAddExactOverflowSaturatesToMax() {
        // now 已接近上限、合法但大 timeoutMs：multiplyExact 成功、addExact 上溢 ⇒ 回落 Long.MAX_VALUE。
        val now = Long.MAX_VALUE - 1000L
        assertEquals(
            Long.MAX_VALUE,
            ElevatedProcessIo.computeDeadlineNanos(Long.MAX_VALUE / 4, { now }),
        )
    }

    @Test fun isExpiredClockThrowPropagatesRaw() {
        // isExpired 本身對拋鐘不吞——fail-closed 收斂由 call site 的 try/catch 落實
        // （awaitInternal 入口 preflight／cancelNow／legacyCancel 皆拋⇒true，見 def）。
        // 本測試釘住該分工：caller 負責收斂拋出（見 awaitInternal／awaitSpawn），裸 isExpired
        // 透傳拋出。處理至 'fail-closed at call sites' 的邏輯屬 call-site，不在本測試重複。
        assertThrows(RuntimeException::class.java) {
            ElevatedCallSupervisor.isExpired(0L) { throw RuntimeException("clock boom") }
        }
    }

    @Test fun awaitPreSpawnClockThrowYieldsExpiredNoSpawn() {
        // awaitPreSpawn 入口 preflight 拋鐘 ⇒ preExpired 直接 Expired(hasLinger=false)，
        // block 從不執行（零 spawn）。
        val r = ElevatedCallSupervisor.awaitPreSpawn(0L, { false }, nowNanos = {
            throw RuntimeException("clock boom")
        }) { "must-not-run" }
        assertTrue("throwing clock must converge to Expired (was $r)", r is ElevatedCallSupervisor.Res.Expired)
        assertEquals(
            false,
            (r as ElevatedCallSupervisor.Res.Expired).hasLinger,
        )
    }

    @Test fun awaitSpawnClockThrowYieldsExpiredNoSpawn() {
        // awaitSpawn 入口 preflight：budget 已盡即不提交 spawn（零特權進程、block 從不執行）。
        // 注入拋鐘 ⇒ preExpired=true（call site try/catch→true）⇒ Expired(hasLinger=false)，
        // killer／block 皆不執行（晚補償缺口誤報 cleaned 不可能發生）。
        var killerCalled = false
        var blockCalled = false
        val r = ElevatedCallSupervisor.awaitSpawn(
            deadlineNanos = 0L,
            isCancelled = { false },
            killer = { killerCalled = true },
            nowNanos = { throw RuntimeException("clock boom") },
            block = { blockCalled = true; KillableImmediateProcess() },
        )
        assertTrue("throwing clock must converge to Expired (was $r)", r is ElevatedCallSupervisor.Res.Expired)
        assertEquals(false, (r as ElevatedCallSupervisor.Res.Expired).hasLinger)
        assertEquals(false, killerCalled)
        assertEquals(false, blockCalled)
    }

    @Test fun drainEntryThrowingClockEntersClockFailedConvergesFailClosed() {
        // drainWithThreadStarter 入口 computeDeadlineNanos 拋鐘 ⇒ entryClockFailed ⇒
        // preflightExpired 直接 kill 臂：有界返回 Drained(timedOut=true)（不偽造成功、
        // 不起 gobbler／stdin-closer）。kill 完成後 verify=true（destroy 置旗）⇒
        // slot 同步釋放、 outstanding 歸零終態。
        val victim = KillableImmediateProcess()
        try {
            val d = ElevatedProcessIo.drainWithThreadStarter(
                process = victim,
                timeoutMs = 300L,
                killer = { it.destroy() },
                isCancelled = { false },
                nowNanos = { throw RuntimeException("clock boom") },
            )
            assertTrue(
                "entry-clock failure must converge to a fail-closed Drained (was $d)",
                d.timedOut,
            )
            // cleanup 收斂終態：outstanding 歸零（verify=true ⇒ caller 側 releaseCleanupSlotAfterVerify
            // 同步釋放；無 quarantine 登記）。
            pollOutstandingTo(0)
        } finally {
            pollOutstandingTo(0)
            ElevatedCleanupTracker.clearUnverified()
            ElevatedCleanupTracker.noteClean()
        }
    }

    private fun pollOutstandingTo(expected: Int, boundMs: Long = 5_000L) {
        val deadline = System.currentTimeMillis() + boundMs
        while (ElevatedCleanupTracker.outstanding() != expected) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError(
                    "outstanding=${ElevatedCleanupTracker.outstanding()} expected=$expected",
                )
            }
            Thread.sleep(20L)
        }
    }

    /** 最小可驗證 kill 假件（destroy 置旗 ⇒ waitFor 回 true ⇒ verify=true）。 */
    private class KillableImmediateProcess : Process() {
        private var destroyed = false

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
}
