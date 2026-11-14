package dev.librepocket.agent.assistant

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Cross-process handoff facade for C2 voice retrigger (F1/F2, #137).
 *
 * Source of truth lives in [VoiceHandoffProvider] (main process). The trusted voice session runs
 * in :voice process and publishes/clears via [android.content.ContentResolver.call]; main-process
 * UI observes provider state and consumes tokens under delivery/identity gates.
 */
object AssistantVoiceHandoff {

    fun pendingFlow(): Flow<PendingVoiceRetrigger?> = VoiceHandoffProvider.pending

    fun tokenFlow(): Flow<Long> = VoiceHandoffProvider.pending.map { it?.token ?: 0L }

    fun peek(context: Context? = null): Long = currentPending(context)?.token ?: 0L

    fun currentPending(context: Context? = null): PendingVoiceRetrigger? {
        val local = VoiceHandoffProvider.pending.value
        if (local != null || context == null) return local
        return try {
            val b = context.contentResolver.call(uri(context), VoiceHandoffContract.METHOD_PEEK, null, null)
            b?.toPending()
        } catch (_: Exception) {
            null
        }
    }

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
            issuedUptimeMs = SystemClock.uptimeMillis(),
        )
        // Optimistic local publish for main-process callers/tests; authoritative cross-process path is call().
        VoiceHandoffProvider.publish(pending)
        try {
            context.contentResolver.call(
                uri(context),
                VoiceHandoffContract.METHOD_PUBLISH,
                null,
                pending.toBundle(),
            )
        } catch (_: Exception) {
            // Best effort: keep local state for same-process scenarios; caller may retry.
        }
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
            context?.let {
                try {
                    it.contentResolver.call(
                        uri(it),
                        VoiceHandoffContract.METHOD_CLEAR,
                        null,
                        identityBundle(targetTaskId, sessionInstanceId).apply {
                            putLong(VoiceHandoffContract.KEY_TOKEN, token)
                        },
                    )
                } catch (_: Exception) {
                }
            }
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
    ) {
        val hasIdentity = targetTaskId != Int.MIN_VALUE || sessionInstanceId != Long.MIN_VALUE
        if (hasIdentity) VoiceHandoffProvider.clearMatching(targetTaskId, sessionInstanceId) else VoiceHandoffProvider.clearAll()
        context?.let {
            try {
                it.contentResolver.call(
                    uri(it),
                    VoiceHandoffContract.METHOD_CLEAR,
                    null,
                    if (hasIdentity) identityBundle(targetTaskId, sessionInstanceId) else null,
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
        putLong(VoiceHandoffContract.KEY_ISSUED_UPTIME_MS, issuedUptimeMs)
    }

    private fun Bundle.toPending(): PendingVoiceRetrigger? {
        val token = getLong(VoiceHandoffContract.KEY_TOKEN, 0L)
        if (token == 0L) return null
        return PendingVoiceRetrigger(
            token = token,
            targetTaskId = getInt(VoiceHandoffContract.KEY_TARGET_TASK_ID, -1),
            sessionInstanceId = getLong(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID, 0L),
            issuedUptimeMs = getLong(VoiceHandoffContract.KEY_ISSUED_UPTIME_MS, 0L),
        )
    }

    private fun identityBundle(targetTaskId: Int, sessionInstanceId: Long): Bundle = Bundle().apply {
        if (targetTaskId != Int.MIN_VALUE) putInt(VoiceHandoffContract.KEY_TARGET_TASK_ID, targetTaskId)
        if (sessionInstanceId != Long.MIN_VALUE) putLong(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID, sessionInstanceId)
    }
}
