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

    /** Drop the pending entry iff [isExpired] holds for it; used by a fresh session cycle
     *  to converge dead prior-cycle residue without touching live entries. No arguments,
     *  no selectivity. */
    const val METHOD_CLEAR_EXPIRED = "clearExpiredRetrigger"

    /**
     * Retrigger tokens are one-shot and expire after this window (monotonic elapsed realtime,
     * includes deep sleep, consistent across processes). Prevents a stale gesture from firing
     * STT long after the user's interaction ended.
     */
    const val RETRIGGER_TTL_MS = 10_000L

    /**
     * Sentinel value meaning "unspecified axis" (matches any), shared by the facade, provider and session.
     * A `targetTaskId` equal to this wildcard means "no task axis constraint" — identity/clear
     * matching skips the task axis instead of comparing against a real task id.
     */
    const val WILDCARD_TASK_ID = Int.MIN_VALUE

    /**
     * Sentinel value meaning "unspecified axis" (matches any), shared by the facade, provider and session.
     * A `sessionInstanceId` equal to this wildcard means "no session axis constraint" — identity/clear
     * matching skips the sid axis instead of comparing against a real session instance id.
     */
    const val WILDCARD_SESSION_INSTANCE_ID = Long.MIN_VALUE

    /**
     * True iff [issuedElapsedRealtimeMs] is expired or clock-skewed relative to
     * [nowElapsedRealtimeMs]: age = now - issued is either negative (clock skew / future-dated
     * entry) or strictly greater than [RETRIGGER_TTL_MS]. Boundary: age == TTL is FRESH, not
     * expired. Corrupt-huge `issued` values (e.g. `Long.MIN_VALUE` wrapping, `Long.MAX_VALUE`
     * naturally) yield a negative age and are treated as expired (fail-closed in every
     * corrupt-input direction).
     */
    fun isExpired(issuedElapsedRealtimeMs: Long, nowElapsedRealtimeMs: Long): Boolean {
        val age = nowElapsedRealtimeMs - issuedElapsedRealtimeMs
        return age < 0 || age > RETRIGGER_TTL_MS
    }

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
