package dev.librepocket.guard

/**
 * 慢通道审计目标种类（ARCH §9.2）：坐标动作与节点动作独立统计。
 */
enum class SlowTargetKind {
    COORDINATE,
    NODE,
    UNKNOWN,
}

/**
 * 审计记录：只记 stepId / 结论 / 理由码 / 目标种类 + 时间戳，
 * 绝不记录 goal 全文、目标全文等敏感原文。
 */
data class SlowAuditRecord(
    val stepId: String,
    val verdict: String,
    val reasonCodes: List<String>,
    val targetKind: SlowTargetKind,
    val atMs: Long,
)

/**
 * 慢通道独立审计表（BACKLOG B5，ARCH §9.2/§8.2）。
 *
 * - 与快通道 Intent 调用区分统计；
 * - 坐标 / 节点动作独立计数（[countByTarget]）；
 * - [record]/[recordVerdict] 的签名本身就不接收敏感全文，
 *   从类型层面保证审计表写不进原文。
 */
class SlowAuditLog {
    private val records = mutableListOf<SlowAuditRecord>()
    private val lock = Any()

    fun record(
        stepId: String,
        verdict: String,
        reasonCodes: List<String>,
        targetKind: SlowTargetKind,
        atMs: Long = System.currentTimeMillis(),
    ): SlowAuditRecord {
        require(stepId.isNotBlank()) { "stepId must not be blank" }
        val row = SlowAuditRecord(
            stepId = stepId,
            verdict = verdict,
            reasonCodes = reasonCodes.toList(),
            targetKind = targetKind,
            atMs = atMs,
        )
        synchronized(lock) { records += row }
        return row
    }

    /**
     * 从仲裁结论直接记账：只提取 id + 结论名 + 理由码，
     * [proposal] 携带的 goal/target 全文不会被持久化。
     */
    fun recordVerdict(
        proposal: SlowProposal,
        verdict: ArbitrationVerdict,
        targetKind: SlowTargetKind,
        atMs: Long = System.currentTimeMillis(),
    ): SlowAuditRecord = record(
        stepId = proposal.id,
        verdict = verdictName(verdict),
        reasonCodes = verdict.codes.map { it.name },
        targetKind = targetKind,
        atMs = atMs,
    )

    fun snapshot(): List<SlowAuditRecord> = synchronized(lock) { records.toList() }

    fun size(): Int = synchronized(lock) { records.size }

    /** 坐标 / 节点 / 未知三类独立计数（缺席的种类计 0，保证调用方总能拿到三行）。 */
    fun countByTarget(): Map<SlowTargetKind, Int> = synchronized(lock) {
        val base = mutableMapOf(
            SlowTargetKind.COORDINATE to 0,
            SlowTargetKind.NODE to 0,
            SlowTargetKind.UNKNOWN to 0,
        )
        for (row in records) {
            base[row.targetKind] = (base[row.targetKind] ?: 0) + 1
        }
        base.toMap()
    }

    fun countByVerdict(): Map<String, Int> = synchronized(lock) {
        records.groupingBy { it.verdict }.eachCount()
    }

    private fun verdictName(verdict: ArbitrationVerdict): String = when (verdict) {
        is ArbitrationVerdict.Allow -> "ALLOW"
        is ArbitrationVerdict.NeedConfirm -> "NEED_CONFIRM"
        is ArbitrationVerdict.Deny -> "DENY"
    }
}
