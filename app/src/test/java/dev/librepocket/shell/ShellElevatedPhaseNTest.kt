package dev.librepocket.shell

import dev.librepocket.router.ElevatedDispatch
import dev.librepocket.router.PrivilegeGate
import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Projection
import dev.librepocket.tool.ToolRegistry
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Round-4 Phase N pins（P2-1 main 快照有界化＋SLA 統一＋P2-2 reasonCode 上限，
 * PR #140）。
 *
 * - P2-1：20 並行吞中斷永久卡住 iterator → 全員有界 `Denied(BLACKLISTED)`、
 *   零 spawn、池內活 worker 恆 ≤ [ElevatedSnapshotPool.POOL_SIZE]（deterministic
 *   barrier 同時起跑，測量窗內 hold 不釋放；斷言後釋放以回收池槽，不污染後測）。
 * - gate-first：`confirmed=false`／projection blocked／reason 空白＋阻塞 argv
 *   → 零觸碰（`iterator()` 調用計數恆 0）、快速 DENY（< 快照門界）、零 spawn。
 * - P2-2：reasonCode 超限（>512 UTF-8 位元組）→ gate 無效＋Dispatch／Tool／
 *   frozen 多路 `Denied(BLACKLISTED)` 零 spawn、有審計（審計不哈希無界輸入，
 *   記空串標記）；512B 邊界放行、513B 拒絕（ASCII 與多字節雙口徑）；`record`
 *   直調超限即抛且無行寫入。
 * - SLA：dispatch 允許路徑只凍結一次（`iterator()` 計數恆 1，不再雙重凍結），
 *   `timeoutMs` 原值透傳 runner（准入後預算）。
 *
 * 全 JVM（零 Android／零真機）：runner 皆為同步 fake（計數 spawn）。
 */
class ShellElevatedPhaseNTest {

    private fun countingRunner(spawns: AtomicInteger) =
        object : ElevatedShellRunner {
            override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult {
                spawns.incrementAndGet()
                return ShellResult.Ok("ok", "", 0, false)
            }
        }

    private val tool = ToolRegistry.find("shell.elevated")!!
    private fun native() = Projection(tool.name, CapabilityLevel.NATIVE)

    // ---- P2-1：20 並行吞中斷卡住 → 活 worker 有界＋拒絕有界 ----

    @Test fun snapshotPoolBoundedUnderSwallowingFlood() {
        val callers = 20
        val hold = CountDownLatch(1) // 測量窗內永不釋放：吞中斷 worker 恆卡。
        val startGate = CountDownLatch(1) // deterministic barrier：20 caller 同時起跑。
        fun swallowingArgv(): List<String> = object : AbstractList<String>() {
            override val size: Int get() = 1
            override fun get(index: Int): String = "id"
            override fun iterator(): Iterator<String> {
                while (hold.count > 0) {
                    try {
                        hold.await()
                    } catch (_: InterruptedException) {
                        // 吞中斷：模擬自訂 List 永久卡住且不響應中斷。
                    }
                }
                return listOf("id").iterator()
            }
        }
        val audit = PrivilegeAuditLog()
        val spawns = AtomicInteger(0)
        val runner = countingRunner(spawns)
        val results = ConcurrentLinkedQueue<ShellResult>()
        val threads = List(callers) {
            Thread({
                startGate.await()
                results.add(
                    ShellExecTool.executeElevated(
                        ElevatedRequest(swallowingArgv(), "N-P2-1"),
                        runner,
                        audit,
                    ),
                )
            }).apply { isDaemon = true }
        }
        threads.forEach { it.start() }
        val wallStart = System.currentTimeMillis()
        startGate.countDown()
        // 測量窗：caller 進行中時採樣池活 worker 峰值（全域恆 ≤ POOL_SIZE）。
        var maxActive = 0
        while (threads.any { it.isAlive }) {
            maxActive = maxOf(maxActive, ElevatedSnapshotPool.activeCount())
            if (System.currentTimeMillis() - wallStart > 15_000L) break
            Thread.sleep(20L)
        }
        threads.forEach { it.join(15_000L) }
        assertFalse("all callers must return bounded", threads.any { it.isAlive })
        val wallElapsed = System.currentTimeMillis() - wallStart
        try {
            assertTrue("pool alive must stay bounded, maxActive=$maxActive", maxActive <= ElevatedSnapshotPool.POOL_SIZE)
            assertTrue("pool must actually admit work, maxActive=$maxActive", maxActive >= 1)
            assertEquals(callers, results.size)
            for (r in results) {
                assertTrue("$r", r is ShellResult.Denied)
                assertEquals(ShellDeny.BLACKLISTED, (r as ShellResult.Denied).reason)
            }
            assertEquals(0, spawns.get())
            assertEquals(callers, audit.size())
            assertEquals(mapOf("DENY" to callers), audit.countByVerdict())
            assertTrue("rejections must be bounded, elapsed=$wallElapsed", wallElapsed < 15_000L)
        } finally {
            // 回收：釋放 hold，吞中斷 worker 完成，池歸零，不污染後測。
            hold.countDown()
            val idleEnd = System.currentTimeMillis() + 10_000L
            while (ElevatedSnapshotPool.activeCount() != 0 && System.currentTimeMillis() < idleEnd) {
                Thread.sleep(20L)
            }
            assertEquals(0, ElevatedSnapshotPool.activeCount())
        }
    }

    // ---- P2-1 gate-first：cheap 門未過零觸碰 argv ----

    private fun touchingBlockingArgv(touches: AtomicInteger, latch: CountDownLatch): List<String> =
        object : AbstractList<String>() {
            override val size: Int get() = 1
            override fun get(index: Int): String = "id"
            override fun iterator(): Iterator<String> {
                touches.incrementAndGet()
                latch.await()
                return listOf("id").iterator()
            }
        }

    @Test fun gateFirstConfirmedFalseZeroWorker() {
        val audit = PrivilegeAuditLog()
        val spawns = AtomicInteger(0)
        val touches = AtomicInteger(0)
        val latch = CountDownLatch(1) // 永不釋放：若被觸碰即卡 1s＋grace。
        val start = System.currentTimeMillis()
        val r = ElevatedDispatch.execute(
            tool = tool,
            confirmed = false,
            projection = native(),
            request = ElevatedRequest(touchingBlockingArgv(touches, latch), "N-GATE"),
            runner = countingRunner(spawns),
            audit = audit,
        )
        val elapsed = System.currentTimeMillis() - start
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(0, touches.get())
        assertEquals(0, spawns.get())
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
        assertTrue("gate-first must not pay snapshot gate, elapsed=$elapsed", elapsed < SNAPSHOT_MAX_WAIT_MS)
    }

    @Test fun gateFirstProjectionBlockedZeroWorker() {
        val audit = PrivilegeAuditLog()
        val spawns = AtomicInteger(0)
        val touches = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val blocked = Projection(tool.name, CapabilityLevel.UNAVAILABLE, DenyReason.USER_DISABLED)
        val start = System.currentTimeMillis()
        val r = ElevatedDispatch.execute(
            tool = tool,
            confirmed = true,
            projection = blocked,
            request = ElevatedRequest(touchingBlockingArgv(touches, latch), "N-GATE"),
            runner = countingRunner(spawns),
            audit = audit,
        )
        val elapsed = System.currentTimeMillis() - start
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(0, touches.get())
        assertEquals(0, spawns.get())
        assertEquals(1, audit.size())
        assertTrue("gate-first must not pay snapshot gate, elapsed=$elapsed", elapsed < SNAPSHOT_MAX_WAIT_MS)
    }

    @Test fun gateFirstBlankReasonZeroWorker() {
        val audit = PrivilegeAuditLog()
        val spawns = AtomicInteger(0)
        val touches = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val r = ElevatedDispatch.execute(
            tool = tool,
            confirmed = true,
            projection = native(),
            request = ElevatedRequest(touchingBlockingArgv(touches, latch), "  "),
            runner = countingRunner(spawns),
            audit = audit,
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(0, touches.get())
        assertEquals(0, spawns.get())
        assertEquals(1, audit.size())
    }

    // ---- P2-2：reasonCode 上限 ----

    @Test fun reasonLengthGateBoundary() {
        // 512B 放行（ASCII 口径）、513B 拒绝；多字节按 UTF-8 字节计。
        assertFalse(PrivilegeAuditLog.isReasonCodeTooLong("r".repeat(512)))
        assertTrue(PrivilegeAuditLog.isReasonCodeTooLong("r".repeat(513)))
        assertFalse(PrivilegeAuditLog.isReasonCodeTooLong("中".repeat(170))) // 510B
        assertTrue(PrivilegeAuditLog.isReasonCodeTooLong("中".repeat(171))) // 513B
        assertEquals(512, PrivilegeAuditLog.MAX_REASON_CODE_BYTES)
        // gate 口径一致：512B 有效、513B 无效（同空白）。
        assertTrue(PrivilegeGate.isElevatedRequest(mapOf("reasonCode" to "r".repeat(512))))
        assertFalse(PrivilegeGate.isElevatedRequest(mapOf("reasonCode" to "r".repeat(513))))
    }

    @Test fun dispatchReasonTooLongDeniedWithoutSpawnOrHash() {
        for (confirmed in listOf(false, true)) {
            val audit = PrivilegeAuditLog()
            val spawns = AtomicInteger(0)
            val huge = "x".repeat(600)
            val r = ElevatedDispatch.execute(
                tool = tool,
                confirmed = confirmed,
                projection = native(),
                request = ElevatedRequest(listOf("id"), huge),
                runner = countingRunner(spawns),
                audit = audit,
            )
            assertTrue("confirmed=$confirmed -> $r", r is ShellResult.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (r as ShellResult.Denied).reason)
            assertEquals(0, spawns.get())
            assertEquals(1, audit.size())
            assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
            // 审计未哈希無界输入：记空串标记（与直接哈希巨串不等）。
            val row = audit.snapshot().single()
            assertEquals(PrivilegeAuditLog.sha256Hex(""), row.reasonHash)
            assertFalse(row.reasonHash == PrivilegeAuditLog.sha256Hex(huge))
        }
    }

    @Test fun toolReasonTooLongDeniedWithoutSpawnOrSnapshot() {
        val audit = PrivilegeAuditLog()
        val spawns = AtomicInteger(0)
        val touches = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val r = ShellExecTool.executeElevated(
            ElevatedRequest(touchingBlockingArgv(touches, latch), "y".repeat(10_000)),
            countingRunner(spawns),
            audit,
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (r as ShellResult.Denied).reason)
        assertEquals(0, touches.get())
        assertEquals(0, spawns.get())
        assertEquals(1, audit.size())
        assertEquals(PrivilegeAuditLog.sha256Hex(""), audit.snapshot().single().reasonHash)
    }

    @Test fun frozenReasonTooLongDeniedWithoutSpawn() {
        // frozen 版防御性复检：直调超限 frozen 同样 fail-closed。
        val audit = PrivilegeAuditLog()
        val spawns = AtomicInteger(0)
        val r = ShellExecTool.executeElevatedFrozen(
            ElevatedRequest(listOf("id"), "z".repeat(513)),
            countingRunner(spawns),
            audit,
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (r as ShellResult.Denied).reason)
        assertEquals(0, spawns.get())
        assertEquals(1, audit.size())
    }

    @Test fun auditRecordRejectsTooLongReasonWithoutRow() {
        val audit = PrivilegeAuditLog()
        try {
            audit.record(listOf("id"), "q".repeat(513), "DENY")
            fail("expected IllegalArgumentException for over-long reasonCode")
        } catch (_: IllegalArgumentException) {
            // fail-closed：抛且无行写入。
        }
        assertEquals(0, audit.size())
        // 512B 仍可记。
        audit.record(listOf("id"), "q".repeat(512), "DENY")
        assertEquals(1, audit.size())
    }

    // ---- SLA：单次冻结＋timeout 原值透传 ----

    @Test fun dispatchFreezesOnlyOnceAndPassesTimeoutThrough() {
        val snapshots = AtomicInteger(0)
        val argv = object : AbstractList<String>() {
            override val size: Int get() = 1
            override fun get(index: Int): String = "id"
            override fun iterator(): Iterator<String> {
                snapshots.incrementAndGet()
                return listOf("id").iterator()
            }
        }
        val audit = PrivilegeAuditLog()
        val spawns = AtomicInteger(0)
        var seenTimeout = -1L
        var seenArgv: List<String>? = null
        val runner = object : ElevatedShellRunner {
            override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult {
                spawns.incrementAndGet()
                seenTimeout = timeoutMs
                seenArgv = request.argv.toList()
                return ShellResult.Ok("ok", "", 0, false)
            }
        }
        val r = ElevatedDispatch.execute(
            tool = tool,
            confirmed = true,
            projection = native(),
            request = ElevatedRequest(argv, "N-SLA"),
            runner = runner,
            audit = audit,
            timeoutMs = 7_000L,
        )
        assertTrue("$r", r is ShellResult.Ok)
        // 单次冻结：Dispatch 与 Tool 传递同一 frozen，不各冻一次。
        assertEquals(1, snapshots.get())
        assertEquals(1, spawns.get())
        assertEquals(listOf("id"), seenArgv)
        // SLA：timeoutMs 原值透传（准入后预算，不扣快照耗时）。
        assertEquals(7_000L, seenTimeout)
        assertEquals(1, audit.size())
        assertEquals(mapOf("OK" to 1), audit.countByVerdict())
    }

    @Test fun toolDirectPathFreezesOnlyOnce() {
        val snapshots = AtomicInteger(0)
        val argv = object : AbstractList<String>() {
            override val size: Int get() = 1
            override fun get(index: Int): String = "id"
            override fun iterator(): Iterator<String> {
                snapshots.incrementAndGet()
                return listOf("id").iterator()
            }
        }
        val audit = PrivilegeAuditLog()
        val spawns = AtomicInteger(0)
        val r = ShellExecTool.executeElevated(
            ElevatedRequest(argv, "N-SLA-D"),
            countingRunner(spawns),
            audit,
        )
        assertTrue("$r", r is ShellResult.Ok)
        assertEquals(1, snapshots.get())
        assertEquals(1, spawns.get())
        assertEquals(1, audit.size())
    }
}
