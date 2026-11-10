package dev.librepocket.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Round-5 Phase Q frozen API 契約迴歸（P2-5，PR #140）：
 * - [ShellExecTool.executeElevatedFrozen] 已收斂為 internal（僅 gate 產生的
 *   frozen 可傳；公開入口一律 [ShellExecTool.executeElevated]）：本檔案鎖定
 *   frozen 物件的執行期不可變（不只 KDoc）——即使 runner 具敵意，收到的 argv
 *   亦不可竄改，審計雜湊與執行所見恆一致；
 * - 公開入口端到端：原始 `MutableList` 在執行期被竄改（舊 snapshot TOCTOU
 *   向量）＋runner 試圖直改收到的 frozen（活引用攻擊）雙向下，spawn 所見與
 *   審計雜湊仍為入口快照（凍結語義）。
 */
class ShellElevatedPhaseQFrozenTest {

    @Test fun gateFrozenArgvIsRuntimeImmutableAgainstHostileRunner() {
        // gate 產物經 frozenOrNull（有界拷貝＋不可變包裝）：敵意 runner 試圖
        // 寫入收到的 argv 必須被拒絕（UnsupportedOperationException），且執行
        // 所見與審計雜湊恆為凍結快照 ["id"]。
        val audit = PrivilegeAuditLog()
        val frozen = ElevatedRequest(listOf("id"), "Q-FROZEN").frozenOrNull()
            ?: throw AssertionError("snapshot of plain list must succeed")
        var seenByRunner: List<String>? = null
        var mutationRejected = false
        val hostile = object : ElevatedShellRunner {
            override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult {
                seenByRunner = request.argv.toList()
                try {
                    @Suppress("UNCHECKED_CAST")
                    (request.argv as MutableList<String>).add("evil")
                    fail("frozen argv must reject mutation")
                } catch (_: UnsupportedOperationException) {
                    mutationRejected = true
                } catch (_: ClassCastException) {
                    mutationRejected = true
                }
                return ShellResult.Ok("ok", "", 0, false)
            }
        }
        val r = ShellExecTool.executeElevatedFrozen(frozen, hostile, audit)
        assertTrue("$r", r is ShellResult.Ok)
        assertTrue("hostile mutation of frozen argv must be rejected", mutationRejected)
        assertEquals(listOf("id"), seenByRunner)
        assertEquals(1, audit.size())
        val row = audit.snapshot().single()
        assertEquals(PrivilegeAuditLog.argvHash(listOf("id")), row.argvHash)
        assertEquals(PrivilegeAuditLog.sha256Hex("Q-FROZEN"), row.reasonHash)
        assertEquals(mapOf("OK" to 1), audit.countByVerdict())
    }

    @Test fun publicEntryKeepsAuditConsistentUnderBidirectionalTamper() {
        // 公開入口：呼叫方保留原始 MutableList 並在執行期竄改（舊 TOCTOU 向量），
        // 同時 runner 試圖直改收到的 frozen——兩者皆不得影響執行所見與審計雜湊
        // （皆為入口快照 ["id"]，凍結語義）。
        val audit = PrivilegeAuditLog()
        val mutable = mutableListOf("id")
        var seenByRunner: List<String>? = null
        var frozenRejectedDirectMutation = false
        val tampering = object : ElevatedShellRunner {
            override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult {
                seenByRunner = request.argv.toList()
                // 方向一：竄改呼叫方保留的原始 MutableList。
                mutable.add("sh")
                mutable[0] = "sh"
                // 方向二：試圖直改收到的 frozen（活引用攻擊）。
                try {
                    @Suppress("UNCHECKED_CAST")
                    (request.argv as MutableList<String>).add("evil")
                    fail("frozen argv must reject mutation")
                } catch (_: UnsupportedOperationException) {
                    frozenRejectedDirectMutation = true
                } catch (_: ClassCastException) {
                    frozenRejectedDirectMutation = true
                }
                return ShellResult.Ok("ok", "", 0, false)
            }
        }
        val r = ShellExecTool.executeElevated(ElevatedRequest(mutable, "Q-TAMPER"), tampering, audit)
        assertTrue("$r", r is ShellResult.Ok)
        assertTrue("direct mutation of frozen argv must be rejected", frozenRejectedDirectMutation)
        assertEquals(listOf("id"), seenByRunner)
        assertEquals(1, audit.size())
        val row = audit.snapshot().single()
        assertEquals(PrivilegeAuditLog.argvHash(listOf("id")), row.argvHash)
        assertEquals(PrivilegeAuditLog.sha256Hex("Q-TAMPER"), row.reasonHash)
        assertEquals(mapOf("OK" to 1), audit.countByVerdict())
    }
}
