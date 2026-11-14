package dev.librepocket.agent.assistant

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Defense-in-depth caller check for the app-private handoff provider.
 *
 * Same package (including the `:voice` process, which shares [android.content.Context.packageName])
 * is allowed; in-process/Robolectric direct calls (callingPackage == null) are allowed; any other
 * package is rejected. Exported=false in the manifest is the primary boundary; this is
 * belt-and-suspenders so a same-UID third-party package cannot ride the channel.
 */
internal fun isCallerAllowed(callerPackage: String?, ownPackage: String?): Boolean =
    callerPackage == null || callerPackage == ownPackage

/**
 * Main-process, app-private handoff state/provider for C2 voice retrigger (F1/F2, #137).
 *
 * The trusted voice session runs in :voice process and publishes retrigger requests via
 * [android.content.ContentResolver.call]; UI in the main process observes [pending] and consumes
 * a token only when target identity matches and delivery gates allow STT relaunch.
 *
 * Security: exported=false, same-UID only by manifest; contract is internal and intended for the
 * trusted session path. Tokens are one-shot, replaceable by newer gestures, and expire after RETRIGGER_TTL_MS.
 */
class VoiceHandoffProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        // Defense-in-depth: reject callers from other same-UID packages. Same package (incl. the
        // :voice process, which shares this packageName) and in-process/Robolectric calls
        // (callingPackage == null) are allowed; see [isCallerAllowed].
        if (!isCallerAllowed(callingPackage, context?.packageName)) return null
        return when (method) {
            VoiceHandoffContract.METHOD_PUBLISH -> {
                val b = extras ?: Bundle()
                val token = b.getLong(VoiceHandoffContract.KEY_TOKEN, 0L)
                val targetTaskId = b.getInt(VoiceHandoffContract.KEY_TARGET_TASK_ID, -1)
                val sessionInstanceId = b.getLong(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID, 0L)
                val issued = b.getLong(VoiceHandoffContract.KEY_ISSUED_UPTIME_MS, 0L)
                // Bundle.EMPTY only when the publish was actually stored; a null ack means
                // "not stored" so the caller can retry instead of believing a dead token lives.
                if (publish(PendingVoiceRetrigger(token, targetTaskId, sessionInstanceId, issued))) {
                    Bundle.EMPTY
                } else {
                    null
                }
            }
            VoiceHandoffContract.METHOD_CONSUME -> {
                val b = extras ?: Bundle()
                val token = b.getLong(VoiceHandoffContract.KEY_TOKEN, 0L)
                val targetTaskId = b.getInt(VoiceHandoffContract.KEY_TARGET_TASK_ID, Int.MIN_VALUE)
                val sessionInstanceId = b.getLong(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID, Long.MIN_VALUE)
                val ok = consume(token, targetTaskId, sessionInstanceId)
                Bundle().apply { putBoolean("ok", ok) }
            }
            VoiceHandoffContract.METHOD_CLEAR -> {
                val b = extras ?: Bundle()
                val token = b.getLong(VoiceHandoffContract.KEY_TOKEN, 0L)
                val hasTarget = b.containsKey(VoiceHandoffContract.KEY_TARGET_TASK_ID) ||
                    b.containsKey(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID)
                if (hasTarget) {
                    clearMatching(
                        b.getInt(VoiceHandoffContract.KEY_TARGET_TASK_ID, Int.MIN_VALUE),
                        b.getLong(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID, Long.MIN_VALUE),
                        token,
                    )
                } else if (token != 0L) {
                    clearIfToken(token)
                } else {
                    clearAll()
                }
                Bundle.EMPTY
            }
            VoiceHandoffContract.METHOD_PEEK -> {
                val p = _pending.value
                Bundle().apply {
                    if (p != null) {
                        putLong(VoiceHandoffContract.KEY_TOKEN, p.token)
                        putInt(VoiceHandoffContract.KEY_TARGET_TASK_ID, p.targetTaskId)
                        putLong(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID, p.sessionInstanceId)
                        putLong(VoiceHandoffContract.KEY_ISSUED_UPTIME_MS, p.issuedUptimeMs)
                    }
                }
            }
            else -> null
        }
    }

    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private val _pending = MutableStateFlow<PendingVoiceRetrigger?>(null)
        val pending: StateFlow<PendingVoiceRetrigger?> = _pending.asStateFlow()
        // Seeded from monotonic uptime so tokens stay monotonic across :voice-process restarts
        // (a fresh process must not re-issue token 1 and collide with/impersonate a live token).
        // (uptime shl 12) keeps bounded headroom below Long overflow and starts odd; any residual
        // collision is harmless because delivery is sid-bound and TTL-limited.
        private val seq = AtomicLong((SystemClock.elapsedRealtime() shl 12) or 1L)

        /** For session-side token generation in any process (IDs are still owned by session). */
        fun nextToken(): Long = seq.incrementAndGet()

        /** @return true when stored; false when rejected (token <= 0, not persisted). */
        @Synchronized
        internal fun publish(p: PendingVoiceRetrigger): Boolean {
            if (p.token <= 0L) return false
            _pending.value = p
            return true
        }

        @Synchronized
        internal fun consume(token: Long, targetTaskId: Int, sessionInstanceId: Long): Boolean {
            val cur = _pending.value ?: return false
            if (token == 0L || cur.token != token) return false
            // If caller provides identity constraints, enforce them; MIN_VALUE means "unspecified".
            if (targetTaskId != Int.MIN_VALUE && cur.targetTaskId != targetTaskId) return false
            if (sessionInstanceId != Long.MIN_VALUE && cur.sessionInstanceId != sessionInstanceId) return false
            // TTL: an expired retrigger must never fire STT; drop it on consume attempt.
            // Negative age (clock skew / bogus issued timestamp) is treated as expired too.
            val age = SystemClock.uptimeMillis() - cur.issuedUptimeMs
            if (age < 0 || age > VoiceHandoffContract.RETRIGGER_TTL_MS) {
                _pending.value = null
                return false
            }
            _pending.value = null
            return true
        }

        @Synchronized
        internal fun clearMatching(targetTaskId: Int, sessionInstanceId: Long, token: Long = 0L) {
            val cur = _pending.value ?: return
            // When a token is supplied, only clear the pending entry if it is exactly that token;
            // never wipe a newer token from the same session cycle.
            if (token != 0L && cur.token != token) return
            val taskMatches = targetTaskId == Int.MIN_VALUE || cur.targetTaskId == targetTaskId
            val sessionMatches = sessionInstanceId == Long.MIN_VALUE || cur.sessionInstanceId == sessionInstanceId
            if (taskMatches && sessionMatches) _pending.value = null
        }

        /** Clears pending only if its token matches [token]; leaves newer tokens intact. */
        @Synchronized
        internal fun clearIfToken(token: Long) {
            if (token == 0L) return
            if (_pending.value?.token == token) _pending.value = null
        }

        @Synchronized
        internal fun clearAll() {
            _pending.value = null
        }

        /** Test/reset hook (main-process/in-process only). */
        internal fun resetForTest() {
            _pending.value = null
            seq.set((SystemClock.elapsedRealtime() shl 12) or 1L)
        }
    }
}
