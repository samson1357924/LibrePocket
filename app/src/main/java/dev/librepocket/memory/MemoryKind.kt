package dev.librepocket.memory

/** D02 记忆种类（ARCH §10.3）：情节=跨会话可回想，语义=长期偏好与事实。 */
enum class MemoryKind(val serial: String) {
    EPISODIC("episodic"),
    SEMANTIC("semantic"),
    ;

    companion object {
        fun fromSerial(serial: String): MemoryKind =
            entries.firstOrNull { it.serial == serial }
                ?: throw IllegalArgumentException("unknown memory kind: $serial")
    }
}
