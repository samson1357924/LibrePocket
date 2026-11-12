package dev.librepocket.shell

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * P2-3 Stage 4 pins（[ShellResult.TimedOut.cleanupIncomplete] 誠實化，direct 通道）。
 *
 * 背景：`executeFrozen` 曾把三態坍縮為無旗標 `TimedOut`——(a) 飽和零 thread
 * `Expired(true)`（無 worker、無清理可言，但全域 backlog 存在）、(b) linger
 * `Expired(true)`（worker 在 grace 後仍存活，kill 可能在 worker 內尚未執行）、
 * (c) 乾淨期滿 `Expired(false)`（entry 期滿／spawn 前預檢，零 worker）。
 * 舊 KDoc「`TimedOut` 即含已殺語義」在 (a)(b) 上皆為假，呼叫方無法區分
 * 「已殺／殺中／從未啟動」。本檔鎖定穿透映射（mirroring 提權
 * `ElevatedVerdictMappingTest` 的 `hasLinger || outstanding>0 → true` 契約形狀，
 * direct 側以 `hasLinger` 單源判定）：
 *
 * - T1a：process 段阻塞 runner（吞中斷）→ caller 按 deadline 回
 *   `TimedOut(cleanupIncomplete=true)`，且返回當刻 worker 仍存活（linger daemon，
 *   可能仍持活子進程／gobbler）；釋 latch 後全歸零。
 * - T1b：真 `/bin/sleep`＋超短 timeout → `TimedOut` 且子程序被殺收割
 *   （runner 內 kill 已執行且收斂，`cleanupIncomplete=false`）。
 * - T2：3 阻塞者佔滿 [DirectAdmission]，第 4 呼叫即時飽和
 *   `TimedOut(cleanupIncomplete=true)`、零 spawn、不耗 quota；釋放後持有者全 Ok。
 * - T3：映射單元——`Expired(true)→true`（飽和零 thread）、`Expired(false)→false`
 *   （entry 期滿零 worker）、`outputIncomplete→true`（would-be Ok 升級，鏡像提權
 *   `tailIncomplete→TimedOut+旗標`）；舊 4 參構造仍編譯且預設 false
 *   （source-compatible：data class `equals/hashCode/componentN/copy` 由編譯器
 *   隨新欄自動更新，本型別全 `String`/primitives，無需提權側手寫式）。
 *
 * 全 JVM（`/bin/sleep` 缺失即 skip T1b）；每測 finally 釋 latch＋等 `direct-*`
 * 歸零，避免全域 permits 污染後測。
 */
class ShellDirectPhaseLingerTest {

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        while (true) {
            try {
                latch.await()
                return
            } catch (_: InterruptedException) {
                // 吞中斷、繼續等：模擬不可中斷阻塞（FUSE／壞 FS／吞中斷 runner）。
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

    private fun awaitDirectThreads(n: Int, timeoutMs: Long = 10_000L) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (directThreadCount() >= n) return
            Thread.sleep(20)
        }
        throw AssertionError("direct-* threads did not reach $n: ${directThreadCount()}")
    }

    // ---- T1a：process 段阻塞 → TimedOut(true)，返回當刻 worker 仍存活 ----

    @Test fun stuckProcessStageLingerFlaggedWhileWorkerAlive() {
        val latch = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val runner = object : ProcessRunner {
            override fun run(argv: List<String>, timeoutMs: Long, env: Map<String, String>?): RawOutput {
                calls.incrementAndGet()
                awaitUninterruptibly(latch)
                return RawOutput("late".toByteArray(), ByteArray(0), 0, false)
            }
        }
        val shell = RestrictedShell(
            runner = runner,
            privateRoot = null,
            execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
        )
        try {
            val start = System.nanoTime()
            val r = shell.execute(listOf("echo", "hi"), timeoutMs = 200L)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue("blocking runner must be supervised to TimedOut, got $r", r is ShellResult.TimedOut)
            val t = r as ShellResult.TimedOut
            // hasLinger 穿透：worker 在 grace 後仍存活（吞中斷阻塞），kill 可能尚未執行。
            assertTrue("stuck process worker must flag linger, got $t", t.cleanupIncomplete)
            assertTrue(
                "worker must still be alive at return (kill may not have run), threads=${directThreadCount()}",
                directThreadCount() >= 1,
            )
            assertTrue("wall bounded, elapsed=$elapsedMs", elapsedMs < 200L + 5_000L)
            assertEquals("runner must have been entered once", 1, calls.get())
        } finally {
            latch.countDown()
            awaitNoDirectThreads()
        }
    }

    // ---- T1b：真 sleep＋超短 timeout → TimedOut(false)，子程序被殺收割 ----

    @Test fun realSleepKilledAndReapedWithoutLingerFlag() {
        assumeTrue("/bin/sleep missing, skipping", File("/bin/sleep").canExecute())
        var child: Process? = null
        val runner = DefaultProcessRunner()
        runner.processObserver = { child = it }
        // Stage 5 P3-4：自訂映射須過再驗證；真系統二進位以 real 父目錄為允許集、
        // 裝置不可寫模擬（宿主 /tmp 可寫不影響系統路徑判定），否則 fail-closed 拒。
        val realSleep = File("/bin/sleep").canonicalPath
        val sleepRoot = File(realSleep).parentFile.canonicalPath
        val shell = RestrictedShell(
            runner = runner,
            privateRoot = null,
            execAllowedRoots = listOf(sleepRoot),
            execIsWritable = { _ -> false },
            execResolver = { ShellExecutables.ResolvedExec(realSleep, null) },
        )
        try {
            val r = shell.execute(listOf("sleep", "30"), timeoutMs = 300L)
            assertTrue("real sleep over budget must be TimedOut, got $r", r is ShellResult.TimedOut)
            val t = r as ShellResult.TimedOut
            // runner 內 kill＋reap 已執行且收斂（無 worker 滯留）：不置旗標。
            assertFalse("reaped runner timeout must not flag linger, got $t", t.cleanupIncomplete)
            val c = child
            assertTrue("runner must have spawned before kill", c != null)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) {
                if (!runCatching { c!!.isAlive }.getOrDefault(true)) break
                Thread.sleep(20)
            }
            assertTrue("child must be reaped after TimedOut", runCatching { c!!.isAlive }.getOrDefault(true).not())
        } finally {
            runCatching { child?.destroyForcibly() }
            runCatching { child?.waitFor(5, TimeUnit.SECONDS) }
            awaitNoDirectThreads()
        }
    }

    // ---- T2：3 阻塞者佔滿，第 4 呼叫飽和 TimedOut(true)、零 spawn、不耗 quota ----

    @Test fun saturationFourthCallFlagsLingerWithoutSpawnOrQuota() {
        val blockLatch = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val runner = object : ProcessRunner {
            override fun run(argv: List<String>, timeoutMs: Long, env: Map<String, String>?): RawOutput {
                calls.incrementAndGet()
                return RawOutput("ok".toByteArray(), ByteArray(0), 0, false)
            }
        }
        val quota = ShellQuota(maxCalls = 10, windowMs = 60_000L)
        val shell = RestrictedShell(
            quota = quota,
            runner = runner,
            privateRoot = null,
            execResolver = {
                awaitUninterruptibly(blockLatch)
                ShellExecutables.ResolvedExec("/trusted/$it", null)
            },
        )
        val holders = (0 until DirectAdmission.MAX_WORKERS).map { i ->
            Thread({
                try {
                    shell.execute(listOf("echo", "holder-$i"), timeoutMs = 15_000L)
                } catch (_: Throwable) {
                }
            }, "linger-holder-$i").apply { isDaemon = true }
        }
        try {
            holders.forEach { it.start() }
            // 3 workers 皆阻塞於 gate resolver（各持一 permit）。
            awaitDirectThreads(DirectAdmission.MAX_WORKERS)
            // 佔用數 rendezvous（取代固定 sleep）：三 permit 全被持有才算齊。
            // DirectAdmission 無 current()（僅 GobblerAdmission 有），
            // 以 availablePermits()==0 判定全佔滿；freeze 工為純 CPU（µs 級），
            // 穩定性再確認一次以排除 freeze→gate 交接瞬間的採樣。
            val occDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (DirectAdmission.availablePermits() > 0 && System.nanoTime() < occDeadline) Thread.sleep(20)
            assertEquals(0, DirectAdmission.availablePermits())
            Thread.sleep(50)
            assertEquals(
                "holders must all be gate-blocked, permits=${DirectAdmission.availablePermits()}",
                0,
                DirectAdmission.availablePermits(),
            )
            assertEquals("no spawn while holders blocked, calls=${calls.get()}", 0, calls.get())
            // 第 4 呼叫：freeze 段准入飽和，零 thread 即時 TimedOut(true)。
            val saturated = shell.execute(listOf("echo", "victim"), timeoutMs = 15_000L)
            assertTrue("saturated call must be TimedOut, got $saturated", saturated is ShellResult.TimedOut)
            assertTrue(
                "saturated zero-thread expiry must flag global backlog, got $saturated",
                (saturated as ShellResult.TimedOut).cleanupIncomplete,
            )
            assertEquals("saturated call must not spawn, calls=${calls.get()}", 0, calls.get())
            // 釋放：持有者全 Ok，共 spawn 恰 3 次；飽和拒絕不耗 quota（10−3＝7 剩餘）。
            blockLatch.countDown()
            holders.forEach { it.join(15_000L) }
            assertTrue("holders must finish", holders.none { it.isAlive })
            assertEquals("only holders spawn", DirectAdmission.MAX_WORKERS, calls.get())
            repeat(7) { i ->
                assertTrue("quota slot $i must remain (saturation consumes none)", quota.tryAcquire())
            }
            assertFalse("quota must be exhausted after 3+7 acquires", quota.tryAcquire())
        } finally {
            blockLatch.countDown()
            holders.forEach { runCatching { it.join(5_000L) } }
            awaitNoDirectThreads()
        }
    }

    // ---- T3a：entry 期滿零 worker → TimedOut(false)，零 spawn ----

    @Test fun entryExpiredMapsToNoLinger() {
        val calls = AtomicInteger(0)
        val runner = object : ProcessRunner {
            override fun run(argv: List<String>, timeoutMs: Long, env: Map<String, String>?): RawOutput {
                calls.incrementAndGet()
                return RawOutput("ok".toByteArray(), ByteArray(0), 0, false)
            }
        }
        val shell = RestrictedShell(
            runner = runner,
            privateRoot = null,
            execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
        )
        val r = shell.execute(listOf("echo", "hi"), timeoutMs = 0L)
        assertTrue("zero budget must be TimedOut, got $r", r is ShellResult.TimedOut)
        assertFalse(
            "entry expiry starts no worker and needs no cleanup, got $r",
            (r as ShellResult.TimedOut).cleanupIncomplete,
        )
        assertEquals("entry expiry must not spawn", 0, calls.get())
    }

    // ---- T3b：飽和零 thread（手動持有 permits，確定性）→ TimedOut(true)，quota 未動 ----

    @Test fun saturationExpiredTrueMapsToLingerWithoutQuota() {
        val held = mutableListOf<Boolean>()
        repeat(DirectAdmission.MAX_WORKERS) {
            assertTrue("admission must have room before saturation test", DirectAdmission.tryEnter())
            held += true
        }
        try {
            val calls = AtomicInteger(0)
            val quota = ShellQuota(maxCalls = 1, windowMs = 60_000L)
            val shell = RestrictedShell(
                quota = quota,
                runner = object : ProcessRunner {
                    override fun run(
                        argv: List<String>,
                        timeoutMs: Long,
                        env: Map<String, String>?,
                    ): RawOutput {
                        calls.incrementAndGet()
                        return RawOutput("ok".toByteArray(), ByteArray(0), 0, false)
                    }
                },
                privateRoot = null,
                execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
            )
            val r = shell.execute(listOf("echo", "hi"), timeoutMs = 10_000L)
            assertTrue("saturated call must be TimedOut, got $r", r is ShellResult.TimedOut)
            assertTrue(
                "saturated zero-thread expiry means global backlog exists, got $r",
                (r as ShellResult.TimedOut).cleanupIncomplete,
            )
            assertEquals("saturated call must not spawn", 0, calls.get())
            // 飽和發生在 quota 之前：配額未動（1 槽仍在）。
            assertTrue("saturated rejection must not consume quota", quota.tryAcquire())
        } finally {
            repeat(held.size) { DirectAdmission.release() }
            awaitNoDirectThreads()
        }
    }

    // ---- T3c：outputIncomplete（would-be Ok 升級）→ TimedOut(true)，攜 partial ----

    @Test fun outputIncompleteUpgradesToTimedOutWithLinger() {
        val shell = RestrictedShell(
            runner = object : ProcessRunner {
                override fun run(argv: List<String>, timeoutMs: Long, env: Map<String, String>?): RawOutput {
                    return RawOutput(
                        stdout = "part".toByteArray(),
                        stderr = ByteArray(0),
                        exitCode = 0,
                        timedOut = false,
                        outputIncomplete = true,
                    )
                }
            },
            privateRoot = null,
            execResolver = { ShellExecutables.ResolvedExec("/trusted/$it", null) },
        )
        val r = shell.execute(listOf("echo", "hi"), timeoutMs = 10_000L)
        assertTrue("incomplete tail must upgrade to TimedOut, got $r", r is ShellResult.TimedOut)
        val t = r as ShellResult.TimedOut
        assertTrue("lingering gobbler tail must flag incomplete, got $t", t.cleanupIncomplete)
        assertEquals("partial must be carried", "part", t.partialStdout)
        assertEquals("TIMEOUT", PrivilegeAuditLog.verdictName(t))
    }

    // ---- T3d：舊 4 參構造仍編譯，預設 false；data class 語義隨新欄自動更新 ----

    @Test fun legacyFourArgConstructionDefaultsToNoLinger() {
        // 舊 4 參構造仍可編譯（source-compatible），新欄預設 false。
        val legacy = ShellResult.TimedOut("a", "b", 1L, false)
        assertFalse(legacy.cleanupIncomplete)
        assertEquals("TIMEOUT", PrivilegeAuditLog.verdictName(legacy))
        // data class 相等／copy／component5 隨新欄自動更新（String 承載，無需手寫）。
        assertEquals(legacy, ShellResult.TimedOut("a", "b", 1L, false, cleanupIncomplete = false))
        assertFalse(legacy == legacy.copy(cleanupIncomplete = true))
        val (_, _, _, _, linger) = legacy
        assertFalse(linger)
        assertTrue(legacy.copy(cleanupIncomplete = true).cleanupIncomplete)
    }

    // ---- P1：gate 阻塞超期 → TimedOut，零 spawn（生產 pre-spawn 線直連） ----

    @Test fun gateBlockedPastDeadlineTimesOutWithoutSpawn() {
        // 生產接線 pin：gate resolver 在監督 worker 內阻塞過 deadline，
        // caller 按 deadline 回 TimedOut（worker grace 後仍存活即 linger）；
        // worker 逾期放行完成後生產亦不再建子進程（該呼叫的 executeFrozen
        // 永不執行；若有 worker 逾期進入直接 process 段，spawn 前預檢即抛
        // DirectSupervisor.PreSpawnTimeoutException，零 spawn 映 TimedOut）。
        // 本測試不複刻任何到期判定（resolver 只阻塞，判定全在生產）：
        // 刪除 gate 逾期的 Expired→TimedOut 映射或清出管線重入，
        // 逾期放行的 worker 結果將被接受或補 spawn，本 pin 即紅。
        // （直接 process 段的 spawn 前預檢由 P1 T2 覆蓋：該 µs 窗口無法經
        // 公開 API 決定性觸發，此處不斷言其紅綠。）
        val blockLatch = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val runner = object : ProcessRunner {
            override fun run(argv: List<String>, timeoutMs: Long, env: Map<String, String>?): RawOutput {
                calls.incrementAndGet()
                return RawOutput("ok".toByteArray(), ByteArray(0), 0, false)
            }
        }
        val shell = RestrictedShell(
            runner = runner,
            privateRoot = null,
            execResolver = {
                awaitUninterruptibly(blockLatch)
                ShellExecutables.ResolvedExec("/trusted/$it", null)
            },
        )
        val releaser = Thread({
            Thread.sleep(600)
            blockLatch.countDown()
        }, "linger-prespawn-releaser").apply { isDaemon = true }
        try {
            releaser.start()
            val start = System.nanoTime()
            val r = shell.execute(listOf("echo", "hi"), timeoutMs = 200L)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue("blocked gate must supervise to TimedOut, got $r", r is ShellResult.TimedOut)
            assertTrue(
                "stuck gate worker must flag linger, got $r",
                (r as ShellResult.TimedOut).cleanupIncomplete,
            )
            assertTrue("wall bounded, elapsed=$elapsedMs", elapsedMs < 200L + 5_000L)
            assertEquals("blocked gate must not spawn", 0, calls.get())
            // 逾期 worker 放行完成後仍不補 spawn（生產丟棄過期 holder，不回填管線）。
            releaser.join(5_000L)
            awaitNoDirectThreads()
            assertEquals("late gate completion must not spawn either", 0, calls.get())
        } finally {
            blockLatch.countDown()
            runCatching { releaser.join(5_000L) }
            awaitNoDirectThreads()
        }
    }
}
