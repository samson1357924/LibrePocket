package dev.librepocket.agent.assistant

internal object VoiceHandoffContract {
    const val METHOD_PUBLISH = "publishRetrigger"
    const val METHOD_CONSUME = "consumeRetrigger"
    const val METHOD_CLEAR = "clearRetrigger"
    const val METHOD_PEEK = "peekRetrigger"

    const val KEY_TOKEN = "token"
    const val KEY_TARGET_TASK_ID = "targetTaskId"
    const val KEY_SESSION_INSTANCE_ID = "sessionInstanceId"
    const val KEY_ISSUED_UPTIME_MS = "issuedUptimeMs"

    fun authority(packageName: String): String = "$packageName.voice.handoff"
}

data class PendingVoiceRetrigger(
    val token: Long,
    val targetTaskId: Int,
    val sessionInstanceId: Long,
    val issuedUptimeMs: Long,
)
