package dev.librepocket.memory

/**
 * D02 单条记忆（读模型）。[text] 为已脱敏文本，绝不含敏感原文。
 */
data class MemoryItem(
    val id: Long,
    val kind: MemoryKind,
    val text: String,
    val sourceSessionId: String? = null,
    val sourceRunId: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

internal fun MemoryEntity.toItem(): MemoryItem = MemoryItem(
    id = rowId,
    kind = MemoryKind.fromSerial(kind),
    text = text,
    sourceSessionId = sourceSessionId,
    sourceRunId = sourceRunId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
