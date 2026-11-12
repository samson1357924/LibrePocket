package dev.librepocket.shell

import dev.librepocket.router.ElevatedDispatch
import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.Projection
import dev.librepocket.tool.ToolRegistry
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Round-3 Phase H pins（P2-5 runner throw 審計＋P2-6 argv 入站上限＋P3a 孤立代理，
 * PR #140）。
 *
 * - P2-5：fake runner `run()` 抛 `IllegalStateException`（spawn 前／已 spawn 後）
 *   → `Failed`（redacted 訊息）＋審計恰一筆 FAILED（`audit.size()==1`），不外抛；
 *   dispatch 直調同契約（工具層單點保證，dispatch 不重複審計故仍單筆）。
 *   review follow-up：`InterruptedException` → 中斷位恢復＋`Failed`＋單筆 FAILED；
 *   `VirtualMachineError` 子類（`StackOverflowError`／`OutOfMemoryError` 實例）
 *   → 重抛同一實例＋審計仍一筆 FAILED；審計成功時致命重抛 `suppressed` 為空
 *   （「原始 throw 優先」正向側；審計失敗側因 `PrivilegeAuditLog` 為 final
 *   無法注入 double，故不可觸發，僅以此等效 pin 鎖定）。
 * - P2-6：阻塞 iterator（latch 永不釋放）→ 有界（<8s，門界 1s＋grace）fail-closed
 *   `Denied(BLACKLISTED)` 且零 spawn；argc／單項／總量超限各一 → 同碼零 spawn；
 *   審計皆恰一筆 DENY。
 * - P3a：孤立代理（lone surrogate）入站即拒（`Denied(BLACKLISTED)` 零 spawn），
 *   不進雜湊替換分支（替換行為見 `PrivilegeAuditLog` KDoc 實證注記）。
 *
 * 全 JVM（零 Android／零真機）：runner 皆為同步 fake（計數 spawn），阻塞只用
 * 可中斷 latch（門超界即 interrupt，worker 正常回收，無殘留執行緒）。
 */
class ShellElevatedPhaseHTest {

    private fun throwingRunner(
        onRun: () -> Nothing = { throw IllegalStateException("injected runner boom") },
        spawns: (() -> Unit)? = null,
    ) = object : ElevatedShellRunner {
        override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult {
            spawns?.invoke()
            return onRun()
        }
    }

    private fun countingRunner(spawns: java.util.concurrent.atomic.AtomicInteger) =
        object : ElevatedShellRunner {
            override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult {
                spawns.incrementAndGet()
                return ShellResult.Ok("ok", "", 0, false)
            }
        }

    private fun blockingArgv(latch: CountDownLatch): List<String> =
        object : AbstractList<String>() {
            override val size: Int get() = 1
            override fun get(index: Int): String = "id"
            override fun iterator(): Iterator<String> {
                latch.await()
                return listOf("id").iterator()
            }
        }

    // ---- P2-5：runner throw → Failed＋單筆 FAILED 審計 ----

    @Test fun toolRunnerThrowMapsFailedAndAuditsOnce() {
        val audit = PrivilegeAuditLog()
        var calls = 0
        val runner = object : ElevatedShellRunner {
            override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult {
                calls++
                throw IllegalStateException("injected runner boom")
            }
        }
        val r = ShellExecTool.executeElevated(
            ElevatedRequest(listOf("id"), "H-P2-5"),
            runner,
            audit,
        )
        assertTrue("$r", r is ShellResult.Failed)
        assertEquals(1, calls)
        assertEquals(1, audit.size())
        assertEquals(mapOf("FAILED" to 1), audit.countByVerdict())
    }

    @Test fun toolRunnerThrowAfterSpawnStillAuditsFailedOnce() {
        // 已 spawn 後才抛：spawn 計數保留（補償由 runner 側既有路徑負責），
        // 工具層只保證審計不漏（單筆 FAILED，不外抛）。
        val audit = PrivilegeAuditLog()
        var spawns = 0
        val r = ShellExecTool.executeElevated(
            ElevatedRequest(listOf("id"), "H-P2-5-spawned"),
            throwingRunner(spawns = { spawns++ }),
            audit,
        )
        assertTrue("$r", r is ShellResult.Failed)
        assertEquals(1, spawns)
        assertEquals(1, audit.size())
        assertEquals(mapOf("FAILED" to 1), audit.countByVerdict())
        val row = audit.snapshot().single()
        assertEquals(PrivilegeAuditLog.argvHash(listOf("id")), row.argvHash)
    }

    @Test fun dispatchRunnerThrowMapsFailedAndAuditsOnce() {
        val tool = ToolRegistry.find("shell.elevated")!!
        val native = Projection(tool.name, CapabilityLevel.NATIVE)
        val audit = PrivilegeAuditLog()
        var calls = 0
        val runner = object : ElevatedShellRunner {
            override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult {
                calls++
                throw IllegalStateException("injected runner boom")
            }
        }
        val r = ElevatedDispatch.execute(
            tool = tool,
            confirmed = true,
            projection = native,
            request = ElevatedRequest(listOf("id"), reasonCode = "H-P2-5-D"),
            runner = runner,
            audit = audit,
        )
        assertTrue("$r", r is ShellResult.Failed)
        assertEquals(1, calls)
        assertEquals(1, audit.size())
        assertEquals(mapOf("FAILED" to 1), audit.countByVerdict())
    }

    // ---- P2-5 review follow-up：中斷／致命 throw 語義 pin ----

    @Test fun toolRunnerThrowInterruptedRestoresFlagAndAuditsOnce() {
        // runner 抛 InterruptedException → 中斷位恢復（isInterrupted 為 true）＋
        // Failed（訊息攜類名）＋單筆 FAILED 審計，不外抛。
        // 中斷位是執行緒全域狀態：finally 內以 Thread.interrupted() 清除，
        // 避免污染同執行緒後續測試。
        val audit = PrivilegeAuditLog()
        var calls = 0
        val runner = object : ElevatedShellRunner {
            override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult {
                calls++
                throw InterruptedException("injected interrupt")
            }
        }
        try {
            val r = ShellExecTool.executeElevated(
                ElevatedRequest(listOf("id"), "H-P2-5-interrupted"),
                runner,
                audit,
            )
            assertTrue("$r", r is ShellResult.Failed)
            assertTrue(
                (r as ShellResult.Failed).message,
                r.message.contains("InterruptedException"),
            )
            assertEquals(1, calls)
            assertEquals(1, audit.size())
            assertEquals(mapOf("FAILED" to 1), audit.countByVerdict())
            assertTrue("interrupt flag must be restored", Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun toolRunnerThrowStackOverflowRethrowsAndAuditsOnce() {
        // runner 抛 VirtualMachineError 子類（StackOverflowError 實例，未真耗盡堆疊）
        // → 重抛同一實例（assertSame），且審計已盡力記一筆 FAILED。
        val audit = PrivilegeAuditLog()
        val boom = StackOverflowError("injected stack boom")
        val runner = object : ElevatedShellRunner {
            override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult {
                throw boom
            }
        }
        try {
            ShellExecTool.executeElevated(
                ElevatedRequest(listOf("id"), "H-P2-5-vmerr"),
                runner,
                audit,
            )
            fail("expected StackOverflowError to propagate")
        } catch (e: StackOverflowError) {
            assertSame(boom, e)
        }
        assertEquals(1, audit.size())
        assertEquals(mapOf("FAILED" to 1), audit.countByVerdict())
    }

    @Test fun toolFatalRethrowPreservesIdentityWithoutSuppressed() {
        // 審計抛等效 pin（正向側）：PrivilegeAuditLog 為 final（無 mock 依賴、
        // 方法皆非 open），可抛的 audit double 無法注入，故審計失敗側不可觸發；
        // 此處鎖定可觀測等效行為——審計成功時致命重抛不掩蓋／不包裝原始異常
        // （同一實例、suppressed 為空），即「原始 throw 優先」。
        // （另一 VirtualMachineError 子類 OutOfMemoryError 實例，未真 OOM。）
        val audit = PrivilegeAuditLog()
        val boom = OutOfMemoryError("injected oom boom")
        val runner = object : ElevatedShellRunner {
            override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult {
                throw boom
            }
        }
        try {
            ShellExecTool.executeElevated(
                ElevatedRequest(listOf("id"), "H-P2-5-auditmask"),
                runner,
                audit,
            )
            fail("expected OutOfMemoryError to propagate")
        } catch (e: OutOfMemoryError) {
            assertSame(boom, e)
            assertEquals(0, e.suppressed.size)
        }
        assertEquals(1, audit.size())
        assertEquals(mapOf("FAILED" to 1), audit.countByVerdict())
    }

    // ---- P2-6：阻塞 iterator 有界 fail-closed ----

    @Test fun toolBlockingArgvBoundedDeniedWithoutSpawn() {
        val audit = PrivilegeAuditLog()
        val spawns = java.util.concurrent.atomic.AtomicInteger(0)
        val latch = CountDownLatch(1) // 永不釋放：門超界靠 interrupt 回收 worker。
        val start = System.currentTimeMillis()
        val r = ShellExecTool.executeElevated(
            ElevatedRequest(blockingArgv(latch), "H-P2-6-block"),
            countingRunner(spawns),
            audit,
            timeoutMs = 10_000L,
        )
        val elapsed = System.currentTimeMillis() - start
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (r as ShellResult.Denied).reason)
        assertEquals(0, spawns.get())
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
        assertTrue("must return within snapshot gate bound, elapsed=$elapsed", elapsed < 8_000L)
    }

    // ---- P2-6：argc／單項／總量超限各一 ----

    @Test fun toolArgcOverLimitDeniedWithoutSpawn() {
        val audit = PrivilegeAuditLog()
        val spawns = java.util.concurrent.atomic.AtomicInteger(0)
        val argv = List(ShellPolicy.MAX_ELEVATED_ARGC + 1) { "a" }
        val r = ShellExecTool.executeElevated(
            ElevatedRequest(argv, "H-P2-6-argc"),
            countingRunner(spawns),
            audit,
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (r as ShellResult.Denied).reason)
        assertEquals(0, spawns.get())
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }

    @Test fun toolSingleArgOverLimitDeniedWithoutSpawn() {
        val audit = PrivilegeAuditLog()
        val spawns = java.util.concurrent.atomic.AtomicInteger(0)
        val argv = listOf("id", "x".repeat(ShellPolicy.MAX_ELEVATED_ARG_CHARS + 1))
        val r = ShellExecTool.executeElevated(
            ElevatedRequest(argv, "H-P2-6-single"),
            countingRunner(spawns),
            audit,
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (r as ShellResult.Denied).reason)
        assertEquals(0, spawns.get())
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }

    @Test fun toolTotalBytesOverLimitDeniedWithoutSpawn() {
        val audit = PrivilegeAuditLog()
        val spawns = java.util.concurrent.atomic.AtomicInteger(0)
        // 20×4000＝80K＞64K；argc 20≤64、單項 4000≤4K，唯總量超限。
        val argv = List(20) { "y".repeat(4000) }
        assertTrue(argv.sumOf { it.toByteArray(Charsets.UTF_8).size } > ShellPolicy.MAX_ELEVATED_ARGV_BYTES)
        val r = ShellExecTool.executeElevated(
            ElevatedRequest(argv, "H-P2-6-total"),
            countingRunner(spawns),
            audit,
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (r as ShellResult.Denied).reason)
        assertEquals(0, spawns.get())
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }

    @Test fun toolArgcAtLimitStillReachesRunner() {
        // 邊界 pin：argc 恰上限（64）不被入站門誤殺（後續政策門另判，此處只鎖入站放行）。
        val audit = PrivilegeAuditLog()
        val spawns = java.util.concurrent.atomic.AtomicInteger(0)
        val argv = List(ShellPolicy.MAX_ELEVATED_ARGC) { "a" }
        val r = ShellExecTool.executeElevated(
            ElevatedRequest(argv, "H-P2-6-edge"),
            countingRunner(spawns),
            audit,
        )
        assertEquals(1, spawns.get())
        assertEquals(1, audit.size())
    }

    // ---- P3a：孤立代理入站即拒 ----

    @Test fun toolLoneSurrogateDeniedWithoutSpawn() {
        val audit = PrivilegeAuditLog()
        val spawns = java.util.concurrent.atomic.AtomicInteger(0)
        val r = ShellExecTool.executeElevated(
            ElevatedRequest(listOf("id", "\uD800"), "H-P3a"),
            countingRunner(spawns),
            audit,
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (r as ShellResult.Denied).reason)
        assertEquals(0, spawns.get())
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }

    // ---- schema 直測：上限恰值放行／超限與謊報 size 拒絕 ----

    @Test fun schema_copyArgvBoundedPinsLimits() {
        assertEquals(listOf("a"), ShellPolicy.copyArgvBounded(listOf("a")))
        assertEquals(emptyList<String>(), ShellPolicy.copyArgvBounded(emptyList()))
        // argc：64 放行、65 拒絕。
        assertNotNull(ShellPolicy.copyArgvBounded(List(ShellPolicy.MAX_ELEVATED_ARGC) { "a" }))
        assertNull(ShellPolicy.copyArgvBounded(List(ShellPolicy.MAX_ELEVATED_ARGC + 1) { "a" }))
        // 單項：4096 放行、4097 拒絕。
        assertNotNull(ShellPolicy.copyArgvBounded(listOf("x".repeat(ShellPolicy.MAX_ELEVATED_ARG_CHARS))))
        assertNull(ShellPolicy.copyArgvBounded(listOf("x".repeat(ShellPolicy.MAX_ELEVATED_ARG_CHARS + 1))))
        // 總量：16×4096＝64K 恰上限放行、20×4000＝80K 拒絕。
        assertNotNull(ShellPolicy.copyArgvBounded(List(16) { "z".repeat(4096) }))
        assertNull(ShellPolicy.copyArgvBounded(List(20) { "y".repeat(4000) }))
        // 孤立代理拒絕；配對代理（增補字元）放行。
        assertNull(ShellPolicy.copyArgvBounded(listOf("id", "\uD800")))
        assertNotNull(ShellPolicy.copyArgvBounded(listOf("id", "\uD800\uDC00")))
        // size 謊報偏小（稱 1 實產出 2）即拒。
        val lying = object : AbstractList<String>() {
            override val size: Int get() = 1
            override fun get(index: Int): String = "a"
            override fun iterator(): Iterator<String> = listOf("a", "b").iterator()
        }
        assertNull(ShellPolicy.copyArgvBounded(lying))
    }

    // ---- dispatch 入站 parity（超限） ----
    @Test fun dispatchArgcOverLimitDeniedWithoutSpawn() {
        val tool = ToolRegistry.find("shell.elevated")!!
        val native = Projection(tool.name, CapabilityLevel.NATIVE)
        val audit = PrivilegeAuditLog()
        val spawns = java.util.concurrent.atomic.AtomicInteger(0)
        val r = ElevatedDispatch.execute(
            tool = tool,
            confirmed = true,
            projection = native,
            request = ElevatedRequest(
                List(ShellPolicy.MAX_ELEVATED_ARGC + 1) { "a" },
                reasonCode = "H-P2-6-D",
            ),
            runner = countingRunner(spawns),
            audit = audit,
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (r as ShellResult.Denied).reason)
        assertEquals(0, spawns.get())
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }
}
