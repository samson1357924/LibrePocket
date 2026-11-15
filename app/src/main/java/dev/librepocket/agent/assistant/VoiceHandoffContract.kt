package dev.librepocket.agent.assistant

internal object VoiceHandoffContract {
    /** Publish one-shot token for the voice task; provider stamps `issued` with its own clock. */
    const val METHOD_PUBLISH = "publishRetrigger"
    /** Consume by token; requires FULL identity keys in extras (wildcards rejected at the IPC boundary). */
    const val METHOD_CONSUME = "consumeRetrigger"
    /** Clear by token or identity; no bare/wildcard IPC clear exists. */
    const val METHOD_CLEAR = "clearRetrigger"
    /** Same-UID diagnostic read; NOT identity-gated — same-UID callers can observe the entry. */
    const val METHOD_PEEK = "peekRetrigger"

    /**
     * Drops the pending entry iff it is expired (age < 0 or age > TTL); used by a fresh
     * session cycle to converge dead prior-cycle residue without touching live entries.
     */
    const val METHOD_CLEAR_EXPIRED = "clearExpiredRetrigger"

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
    const val KEY_OK = "ok"

    fun authority(packageName: String): String = "$packageName.voice.handoff"
}

/**
 * Identity of the activity hosting the voice task, passed down from MainActivity;
 * used to bind retrigger delivery/consume to the trusted voice task (not the
 * normal-task MainActivity instance that also collects the same state flow).
 */
data class VoiceTaskIdentity(val taskId: Int, val sessionInstanceId: Long)

/**
 * One-shot retrigger token for a trusted voice task. [issuedElapsedRealtimeMs] is stamped by the
 * provider (main process) with [android.os.SystemClock.elapsedRealtime] at store time — the
 * caller-supplied value is ignored — and drives every TTL gate across processes.
 */
data class PendingVoiceRetrigger(
    val token: Long,
    val targetTaskId: Int,
    val sessionInstanceId: Long,
    val issuedElapsedRealtimeMs: Long,
)
