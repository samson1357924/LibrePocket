package dev.librepocket.memory

/**
 * D02 工作记忆（ARCH §10.3 第 1 层）：当前会话最近 N 轮原文，纯内存，
 * 随会话结束丢弃（可由转录重建）。零 Android 依赖，纯 JVM 可测。
 */
class WorkingMemory(private val maxTurns: Int = DEFAULT_MAX_TURNS) {
    init {
        require(maxTurns > 0) { "maxTurns must be positive" }
    }

    data class Turn(val role: String, val text: String, val createdAt: Long = 0)

    private val turns = ArrayDeque<Turn>()

    @Synchronized
    fun addTurn(role: String, text: String, createdAt: Long = 0) {
        require(role == "user" || role == "assistant") { "unknown role: $role" }
        require(text.isNotBlank()) { "text must not be blank" }
        turns.addLast(Turn(role, text, createdAt))
        while (turns.size > maxTurns) turns.removeFirst()
    }

    @Synchronized
    fun recent(limit: Int = maxTurns): List<Turn> {
        require(limit > 0) { "limit must be positive" }
        return turns.takeLast(limit.coerceAtMost(turns.size)).toList()
    }

    @Synchronized
    fun clear() {
        turns.clear()
    }

    @Synchronized
    fun size(): Int = turns.size

    companion object {
        const val DEFAULT_MAX_TURNS = 20
    }
}
