package dev.librepocket.memory

/**
 * D02 记忆授权面（ARCH §10.3：记忆读取需声明用途，写入需标注来源）。
 *
 * 最小版：与 policy 包解耦的三级开关（READ/WRITE/DELETE），每次读写删前
 * 由 [MemoryStore]/[ResidentMemory] 强制检查，未授权抛 [MemoryDeniedException]。
 * 后续可桥接到统一 PolicyStore（`memory.read:<用途>` 等），调用点无需改动。
 */
enum class MemoryAction { READ, WRITE, DELETE }

/** 授权拒绝：fail-closed，消息中不携带任何记忆内容。 */
class MemoryDeniedException(val action: MemoryAction) :
    SecurityException("memory $action denied by gate")

/** 授权谓词：纯函数，便于单测（允许/拒绝矩阵）。 */
interface MemoryGate {
    fun check(action: MemoryAction): Boolean
}

/** 未授权立即抛，调用方无需再判返回值。 */
fun MemoryGate.require(action: MemoryAction) {
    if (!check(action)) throw MemoryDeniedException(action)
}

/** 默认全放行（产品侧由上层按用途装配更严的 Gate）。 */
class AllowMemoryGate : MemoryGate {
    override fun check(action: MemoryAction): Boolean = true
}

/**
 * 测试/受限场景用：拒绝 [denied] 中的动作，其余放行。
 * 空集=全放行；全集=全拒绝。
 */
class DenyMemoryGate(private val denied: Set<MemoryAction> = MemoryAction.entries.toSet()) : MemoryGate {
    override fun check(action: MemoryAction): Boolean = action !in denied
}
