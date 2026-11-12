package dev.librepocket.shell

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-1 fail-closed pins（[DirectSupervisor] 成功路徑 postcheck＋spawn 前預檢，
 * direct 通道）。
 *
 * - T1：worker 死於 deadline+5ms 即 `Expired`（覆寫 `Val`，不誤判 Ok）；
 *   另釘邊界（deadline+0ms 即逾期、deadline-1ms 仍 Ok）。
 * - T2：worker 內預檢（生產同式）先於 spawn 看門，期滿即抛
 *   [DirectSupervisor.PreSpawnTimeoutException]，runner 零呼叫、
 *   supervise 側 `Expired`（上層即 `TimedOut`）。
 * - T3：worker 正常完工的同時中斷 caller，任一交錯都不得回 Ok。
 *
 * 全確定性：fake clock（[AtomicLong]）＋latch 會合，零 sleep 猜測；
 * 每測 finally 釋 latch＋等 `direct-*` 歸零，避免全域 permits 污染後測。
 */
class ShellDirectPhaseP1Test {

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        while (true) {
            try {
                latch.await()
                return
            } catch (_: InterruptedException) {
                // 吞中斷、繼續等：模擬不可中斷阻塞。
            }
        }
    }

    private fun directThreadCount(): Int =
        Thread.getAllStackTraces().keys.count { it.name.startsWith("direct-") }

    private fun awaitNoDirectThreads(timeoutMs: Long = 10_000L) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (directThreadCount() == 0) return
            Thread.sleep(20)
        }
        throw AssertionError("direct-* threads still alive: ${directThreadCount()}")
    }

    // ---- T1：deadline+5ms 完工值被壓制（latch 會合版，任一監督路徑皆 Expired） ----

    @Test fun slightlyLateOkSuppressed() {
        val fake = AtomicLong(1_000_000_000_000L)
        val deadline = DirectSupervisor.deadlineNanos(100L, fake.get())
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        val result = AtomicReference<DirectSupervisor.Res<String>>()
        val caller = Thread({
            result.set(
                DirectSupervisor.supervise(
                    deadline,
                    "direct-p1-slightly-late",
                    {
                        started.countDown()
                        awaitUninterruptibly(done)
                        "v"
                    },
                    { fake.get() },
                ),
            )
        }, "p1-t1-caller")
        caller.isDaemon = true
        try {
            caller.start()
            assertTrue("worker must start", started.await(10, TimeUnit.SECONDS))
            // deadline 之後 5ms 才放行：完工值必須被壓制為 Expired。
            fake.set(deadline + 5_000_000L)
            done.countDown()
            caller.join(15_000L)
            assertTrue("caller must finish", !caller.isAlive)
            val r = result.get()
            assertTrue("slightly-late Ok must be suppressed, got $r", r is DirectSupervisor.Res.Expired)
        } finally {
            done.countDown()
            runCatching { caller.join(5_000L) }
            awaitNoDirectThreads()
        }
    }

    // ---- T1b：postcheck 確定性直擊（worker 回值前把時鐘推過門限） ----

    @Test fun postcheckSuppressesValueCompletedAfterDeadline() {
        val t0 = 3_000_000_000_000L
        val fake = AtomicLong(t0)
        val deadline = DirectSupervisor.deadlineNanos(100L, t0)
        // 入口未逾期、迴圈未來得及看門（worker 即時完工）：postcheck 必須覆寫 Val。
        // 無此修復即回 Ok（bug 復現針）。
        val r = DirectSupervisor.supervise(
            deadline,
            "direct-p1-postcheck",
            {
                fake.set(deadline + 5_000_000L)
                "v"
            },
            { fake.get() },
        )
        assertTrue("value completed after deadline must be Expired, got $r", r is DirectSupervisor.Res.Expired)
        awaitNoDirectThreads()
    }

    // ---- T1 邊界：deadline+0ms 即逾期、deadline-1ms 仍 Ok ----

    @Test fun boundaryDeadlineExactExpiredMinusOneOk() {
        val t0 = 2_000_000_000_000L
        val deadline = DirectSupervisor.deadlineNanos(100L, t0)
        val atExact = DirectSupervisor.supervise(
            deadline,
            "direct-p1-boundary-exact",
            { "v" },
            { deadline },
        )
        assertTrue("deadline+0ms must be Expired, got $atExact", atExact is DirectSupervisor.Res.Expired)
        val justBefore = DirectSupervisor.supervise(
            deadline,
            "direct-p1-boundary-before",
            { "v" },
            { deadline - 1_000_000L },
        )
        assertEquals("deadline-1ms must stay Ok", "v", (justBefore as? DirectSupervisor.Res.Ok)?.v)
        awaitNoDirectThreads()
    }

    // ---- T2：期滿預檢擋 spawn（runner 零呼叫，supervise 側 Expired＝上層 TimedOut） ----

    @Test fun preSpawnNoSpawnAfterExpiry() {
        val t0 = 4_000_000_000_000L
        val fake = AtomicLong(t0)
        val deadline = DirectSupervisor.deadlineNanos(100L, t0)
        val calls = AtomicInteger(0)
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val result = AtomicReference<DirectSupervisor.Res<String>>()
        val caller = Thread({
            result.set(
                DirectSupervisor.supervise(
                    deadline,
                    "direct-p1-prespawn",
                    {
                        started.countDown()
                        awaitUninterruptibly(gate)
                        // 生產同式預檢（executeFrozen direct-process 段一致）。
                        if (DirectSupervisor.isExpired(deadline, fake.get()) ||
                            Thread.currentThread().isInterrupted
                        ) {
                            throw DirectSupervisor.PreSpawnTimeoutException()
                        }
                        calls.incrementAndGet()
                        "v"
                    },
                    { fake.get() },
                ),
            )
        }, "p1-t2-caller")
        caller.isDaemon = true
        try {
            caller.start()
            assertTrue("worker must start", started.await(10, TimeUnit.SECONDS))
            fake.set(deadline + 5_000_000L)
            gate.countDown()
            caller.join(15_000L)
            assertTrue("caller must finish", !caller.isAlive)
            assertEquals("expired preflight must not spawn", 0, calls.get())
            val r = result.get()
            assertTrue("expired preflight must be Expired, got $r", r is DirectSupervisor.Res.Expired)
        } finally {
            gate.countDown()
            runCatching { caller.join(5_000L) }
            awaitNoDirectThreads()
        }
    }

    // ---- T3：worker 正常完工同時中斷 caller，不得回 Ok ----

    @Test fun callerInterruptDuringFinalJoinNotOk() {
        val deadline = DirectSupervisor.deadlineNanos(60_000L)
        val started = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val result = AtomicReference<DirectSupervisor.Res<String>>()
        val caller = Thread({
            result.set(
                DirectSupervisor.supervise(deadline, "direct-p1-interrupt", block = {
                    started.countDown()
                    awaitUninterruptibly(releaseWorker)
                    "v"
                }),
            )
        }, "p1-t3-caller")
        caller.isDaemon = true
        try {
            caller.start()
            assertTrue("worker must start", started.await(10, TimeUnit.SECONDS))
            // worker 被 releaseWorker 釘住（必存活），caller 必在 join 迴圈內：
            // 中斷＋放行會合，任一交錯都不得回 Ok。
            caller.interrupt()
            releaseWorker.countDown()
            caller.join(15_000L)
            assertTrue("caller must finish", !caller.isAlive)
            val r = result.get()
            assertTrue("interrupted caller must not see Ok, got $r", r !is DirectSupervisor.Res.Ok)
            assertTrue("interrupted caller must be Expired, got $r", r is DirectSupervisor.Res.Expired)
        } finally {
            releaseWorker.countDown()
            runCatching { caller.join(5_000L) }
            awaitNoDirectThreads()
        }
    }
}
