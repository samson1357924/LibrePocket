package dev.librepocket.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B5 审计测试（BACKLOG B5/B9，ARCH §9.2/§8.2）：
 * 坐标 / 节点独立统计；审计行只记 id / 结论 / 理由码，不记敏感全文。
 */
class SlowAuditTest {

    @Test fun coordinateAndNodeCountsAreIndependent() {
        val log = SlowAuditLog()
        log.record("c1", "ALLOW", emptyList(), SlowTargetKind.COORDINATE)
        log.record("c2", "NEED_CONFIRM", listOf("PAYMENT"), SlowTargetKind.COORDINATE)
        log.record("n1", "ALLOW", emptyList(), SlowTargetKind.NODE)
        log.record("n2", "DENY", listOf("OUT_OF_SCOPE"), SlowTargetKind.NODE)
        log.record("n3", "DENY", listOf("OUT_OF_SCOPE"), SlowTargetKind.NODE)

        val counts = log.countByTarget()
        assertEquals(2, counts[SlowTargetKind.COORDINATE])
        assertEquals(3, counts[SlowTargetKind.NODE])
        assertEquals(0, counts[SlowTargetKind.UNKNOWN])
        assertEquals(5, log.size())
    }

    @Test fun auditRowKeepsOnlyIdVerdictAndReasonCodes() {
        val log = SlowAuditLog()
        val row = log.record(
            stepId = "step-7",
            verdict = "NEED_CONFIRM",
            reasonCodes = listOf("PAYMENT"),
            targetKind = SlowTargetKind.COORDINATE,
        )
        assertEquals("step-7", row.stepId)
        assertEquals("NEED_CONFIRM", row.verdict)
        assertEquals(listOf("PAYMENT"), row.reasonCodes)
        assertEquals(SlowTargetKind.COORDINATE, row.targetKind)

        val blob = log.snapshot().toString()
        assertFalse(blob.contains("转账"))
        assertFalse(blob.contains("sk-"))
    }

    @Test fun recordVerdictNeverPersistsSensitiveFullText() {
        val log = SlowAuditLog()
        val sensitiveGoal = "帮我转账 9999 元到银行卡 6228****，附言 sk-secret-key"
        val proposal = SlowProposal(id = "s-pay-1", kind = SlowActionKind.TAP, goalText = sensitiveGoal)
        val verdict = SlowArbitrator.arbitrate(proposal)
        log.recordVerdict(proposal, verdict, SlowTargetKind.NODE)

        val rows = log.snapshot()
        assertEquals(1, rows.size)
        assertEquals("s-pay-1", rows[0].stepId)
        assertTrue(rows[0].reasonCodes.contains("PAYMENT"))
        val blob = rows.toString()
        assertFalse(blob, blob.contains("9999"))
        assertFalse(blob, blob.contains("6228"))
        assertFalse(blob, blob.contains("sk-secret-key"))
        assertFalse(blob, blob.contains(sensitiveGoal))
    }

    @Test fun countByVerdictGroupsCorrectly() {
        val log = SlowAuditLog()
        log.record("a1", "ALLOW", emptyList(), SlowTargetKind.COORDINATE)
        log.record("a2", "NEED_CONFIRM", listOf("SEND"), SlowTargetKind.NODE)
        log.record("a3", "DENY", listOf("OUT_OF_SCOPE"), SlowTargetKind.UNKNOWN)

        val byVerdict = log.countByVerdict()
        assertEquals(1, byVerdict["ALLOW"])
        assertEquals(1, byVerdict["NEED_CONFIRM"])
        assertEquals(1, byVerdict["DENY"])
    }

    @Test fun deleteFlowEndToEndUsesCodesOnly() {
        val log = SlowAuditLog()
        val proposal = SlowProposal("s-del-1", SlowActionKind.TAP, "把和他的对话删除")
        val verdict = SlowArbitrator.arbitrate(proposal)
        assertTrue(verdict is ArbitrationVerdict.NeedConfirm)
        log.recordVerdict(proposal, verdict, SlowTargetKind.NODE)

        val row = log.snapshot().single()
        assertEquals("NEED_CONFIRM", row.verdict)
        assertTrue(row.reasonCodes.contains("DELETE"))
    }
}
