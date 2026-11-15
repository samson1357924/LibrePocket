package dev.librepocket.agent.assistant

internal object VoiceHandoffContract {
    const val METHOD_PUBLISH = "publishRetrigger"
    const val METHOD_CONSUME = "consumeRetrigger"
    const val METHOD_CLEAR = "clearRetrigger"
    const val METHOD_PEEK = "peekRetrigger"

    /**
     * Retrigger tokens are one-shot and expire after this window (monotonic elapsed realtime,
     * includes deep sleep, consistent across processes). Prevents a stale gesture from firing
     * STT long after the user's interaction ended.
     */
    const val RETRIGGER_TTL_MS = 10_000L

    const val KEY_TOKEN = "token"
    const val KEY_TARGET_TASK_ID = "targetTaskId"
    const val KEY_SESSION_INSTANCE_ID = "sessionInstanceId"
    const val KEY_ISSUED_ELAPSED_REALTIME_MS = "issuedElapsedRealtimeMs"

    fun authority(packageName: String): String = "$packageName.voice.handoff"
}

/**
 * Identity of the activity hosting the voice task, passed down from MainActivity;
 * used to bind retrigger delivery/consume to the trusted voice task (not the
 * normal-task MainActivity instance that also collects the same state flow).
 */
data class VoiceTaskIdentity(val taskId: Int, val sessionInstanceId: Long)

/**
 * One-shot retrigger token for a trusted voice task. [issuedElapsedRealtimeMs] is stamped with
 * [android.os.SystemClock.elapsedRealtime] at publish time (monotonic elapsed realtime, includes
 * deep sleep) and drives every TTL gate across processes.
 */
data class PendingVoiceRetrigger(
    val token: Long,
    val targetTaskId: Int,
    val sessionInstanceId: Long,
    val issuedElapsedRealtimeMs: Long,
)
