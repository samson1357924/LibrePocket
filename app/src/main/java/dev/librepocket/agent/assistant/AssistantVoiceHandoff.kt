package dev.librepocket.agent.assistant

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.Flow

/**
 * Cross-process handoff facade for C2 voice retrigger (F1/F2, #137).
 *
 * Source of truth lives in [VoiceHandoffProvider] (main process). The trusted voice session runs
 * in :voice process and publishes/clears via [android.content.ContentResolver.call]; main-process
 * UI observes provider state and consumes tokens under delivery/identity gates.
 *
 * The remote `call()` is the only publish path — there is no local mirror. In-process/Robolectric
 * observers should assert via the provider (Stage 2 registers the provider with
 * ShadowContentResolver), since `requestRetrigger` returns a token and never writes provider state
 * directly.
 */
object AssistantVoiceHandoff {

    private const val TAG = "AssistantVoiceHandoff"

    fun pendingFlow(): Flow<PendingVoiceRetrigger?> = VoiceHandoffProvider.pending

    fun peek(context: Context? = null): Long = currentPending(context)?.token ?: 0L

    fun currentPending(context: Context? = null): PendingVoiceRetrigger? {
        val local = VoiceHandoffProvider.pending.value
        val resolved = if (local != null || context == null) {
            local
        } else {
            try {
                val b = context.contentResolver.call(uri(context), VoiceHandoffContract.METHOD_PEEK, null, null)
                b?.toPending()
            } catch (_: Exception) {
                null
            }
        }
        // TTL filter: expired (or clock-skewed, negative-age) retriggers are treated as absent
        // (callers clear; never delivered).
        if (resolved != null) {
            val age = SystemClock.elapsedRealtime() - resolved.issuedElapsedRealtimeMs
            if (age < 0 || age > VoiceHandoffContract.RETRIGGER_TTL_MS) {
                return null
            }
        }
        return resolved
    }

    /**
     * Mints a one-shot retrigger token for the active voice task.
     *
     * Failure path clears only the token minted by this call: on either failure branch
     * (exception, or no ack / rejected publish) this performs a token-matched best-effort
     * [clear] so a stray remote publish (lost ack) is removed; a newer token published
     * meanwhile survives because the clear is token-matched.
     */
    fun requestRetrigger(
        context: Context,
        targetTaskId: Int,
        sessionInstanceId: Long,
    ): Long {
        val token = VoiceHandoffProvider.nextToken()
        val pending = PendingVoiceRetrigger(
            token = token,
            targetTaskId = targetTaskId,
            sessionInstanceId = sessionInstanceId,
            issuedElapsedRealtimeMs = SystemClock.elapsedRealtime(),
        )
        // Cross-process publish is authoritative (the provider's call() handler stores it).
        // No local mirror: a failed IPC must never look like a successful handoff (the caller
        // can retry the gesture), and same-process observers read the provider state directly.
        val ack = try {
            context.contentResolver.call(
                uri(context),
                VoiceHandoffContract.METHOD_PUBLISH,
                null,
                pending.toBundle(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "voice retrigger publish failed; gesture dropped (user may retry)", e)
            // Best-effort token-matched cleanup of any stray remote publish (lost ack).
            clear(context, targetTaskId = targetTaskId, sessionInstanceId = sessionInstanceId, token = token)
            return 0L
        }
        if (ack == null) {
            Log.w(TAG, "voice retrigger publish rejected (no ack); gesture dropped")
            // Best-effort token-matched cleanup of any stray remote publish (lost ack).
            clear(context, targetTaskId = targetTaskId, sessionInstanceId = sessionInstanceId, token = token)
            return 0L
        }
        // Any non-null Bundle counts as ack.
        return token
    }

    fun consume(
        token: Long,
        context: Context? = null,
        targetTaskId: Int = Int.MIN_VALUE,
        sessionInstanceId: Long = Long.MIN_VALUE,
    ): Boolean {
        if (token == 0L) return false
        val local = VoiceHandoffProvider.consume(token, targetTaskId, sessionInstanceId)
        if (local) {
            // Local CAS already removed the entry; no extra IPC clear (it would ignore the token
            // and could wipe a newer token from the same session cycle).
            return true
        }
        // Cross-process consumer fallback.
        if (context != null) {
            return try {
                val b = context.contentResolver.call(
                    uri(context),
                    VoiceHandoffContract.METHOD_CONSUME,
                    null,
                    identityBundle(targetTaskId, sessionInstanceId).apply {
                        putLong(VoiceHandoffContract.KEY_TOKEN, token)
                    },
                )
                b?.getBoolean("ok", false) == true
            } catch (_: Exception) {
                false
            }
        }
        return false
    }

    fun clear(
        context: Context? = null,
        targetTaskId: Int = Int.MIN_VALUE,
        sessionInstanceId: Long = Long.MIN_VALUE,
        token: Long = 0L,
    ) {
        val hasIdentity = targetTaskId != Int.MIN_VALUE || sessionInstanceId != Long.MIN_VALUE
        if (hasIdentity) {
            VoiceHandoffProvider.clearMatching(targetTaskId, sessionInstanceId, token)
        } else if (token != 0L) {
            VoiceHandoffProvider.clearIfToken(token)
        } else {
            VoiceHandoffProvider.clearAll()
        }
        context?.let {
            try {
                it.contentResolver.call(
                    uri(it),
                    VoiceHandoffContract.METHOD_CLEAR,
                    null,
                    if (hasIdentity || token != 0L) {
                        identityBundle(targetTaskId, sessionInstanceId).apply {
                            if (token != 0L) putLong(VoiceHandoffContract.KEY_TOKEN, token)
                        }
                    } else {
                        null
                    },
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun uri(context: Context): Uri =
        Uri.parse("content://${VoiceHandoffContract.authority(context.packageName)}")

    private fun PendingVoiceRetrigger.toBundle(): Bundle = Bundle().apply {
        putLong(VoiceHandoffContract.KEY_TOKEN, token)
        putInt(VoiceHandoffContract.KEY_TARGET_TASK_ID, targetTaskId)
        putLong(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID, sessionInstanceId)
        putLong(VoiceHandoffContract.KEY_ISSUED_ELAPSED_REALTIME_MS, issuedElapsedRealtimeMs)
    }

    private fun Bundle.toPending(): PendingVoiceRetrigger? {
        val token = getLong(VoiceHandoffContract.KEY_TOKEN, 0L)
        if (token == 0L) return null
        return PendingVoiceRetrigger(
            token = token,
            targetTaskId = getInt(VoiceHandoffContract.KEY_TARGET_TASK_ID, -1),
            sessionInstanceId = getLong(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID, 0L),
            issuedElapsedRealtimeMs = getLong(VoiceHandoffContract.KEY_ISSUED_ELAPSED_REALTIME_MS, 0L),
        )
    }

    private fun identityBundle(targetTaskId: Int, sessionInstanceId: Long): Bundle = Bundle().apply {
        if (targetTaskId != Int.MIN_VALUE) putInt(VoiceHandoffContract.KEY_TARGET_TASK_ID, targetTaskId)
        if (sessionInstanceId != Long.MIN_VALUE) putLong(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID, sessionInstanceId)
    }
}
