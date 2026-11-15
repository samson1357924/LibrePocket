package dev.librepocket.agent.assistant

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Defense-in-depth caller check for the app-private handoff provider.
 *
 * UID tiebreak: when both UIDs are known (>= 0) they must be equal — a different UID is
 * rejected even if the package name matches (e.g. a reinstalled/shared-UID lookalike).
 * Package check remains as belt-and-suspenders: in-process/Robolectric callers and
 * same-package components (incl. the `:voice` process, which shares
 * [android.content.Context.packageName]) are allowed; a different package is rejected
 * even with no UID info. Exported=false via the manifest remains the hard boundary; this
 * is belt-and-suspenders so it is not the only one. The `-1` defaults keep the previous
 * pure package semantics for existing call sites/tests that pass no UIDs.
 */
internal fun isCallerAllowed(callerPackage: String?, ownPackage: String?, callerUid: Int = -1, ownUid: Int = -1): Boolean {
    if (callerUid >= 0 && ownUid >= 0 && callerUid != ownUid) return false
    return callerPackage == null || callerPackage == ownPackage
}

/**
 * Main-process, app-private handoff state/provider for C2 voice retrigger (F1/F2, #137).
 *
 * The trusted voice session runs in :voice process and publishes retrigger requests via
 * [android.content.ContentResolver.call]; UI in the main process observes [pending] and consumes
 * a token only when target identity matches and delivery gates allow STT relaunch.
 *
 * Security: exported=false, same-UID only by manifest; contract is internal and intended for the
 * trusted session path. Tokens are one-shot, replaceable by newer gestures, and expire after RETRIGGER_TTL_MS.
 * The IPC boundary is provider-enforced: METHOD_CONSUME requires full identity (taskId + sid),
 * METHOD_PUBLISH stamps the stored entry with the provider's clock (caller-supplied issued is
 * ignored), and no bare (empty-extras) IPC path can reach clearAll — the bare clear is
 * same-process only. See METHOD_CLEAR_EXPIRED for the fresh-cycle convergence affordance.
 */
class VoiceHandoffProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        // Defense-in-depth: reject callers from other same-UID packages. Same package (incl. the
        // :voice process, which shares this packageName) and in-process/Robolectric calls
        // (callingPackage == null) are allowed; see [isCallerAllowed].
        if (!isCallerAllowed(
                callingPackage,
                context?.packageName,
                android.os.Binder.getCallingUid(),
                context?.applicationInfo?.uid ?: -1,
            )
        ) {
            return null
        }
        // L-1: this runs on a Binder thread in the main process; Bundle parsing can throw
        // (ClassCastException from a wrong-typed value, etc.). Never let an uncaught throwable
        // escape into the framework — reject the envelope instead. Log no payload contents.
        return try {
            when (method) {
            VoiceHandoffContract.METHOD_PUBLISH -> {
                val b = extras ?: Bundle()
                val token = b.getLong(VoiceHandoffContract.KEY_TOKEN, 0L)
                val targetTaskId = b.getInt(VoiceHandoffContract.KEY_TARGET_TASK_ID, -1)
                val sessionInstanceId = b.getLong(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID, 0L)
                // M-2: the caller-supplied KEY_ISSUED_ELAPSED_REALTIME_MS is never read.
                // The provider (main process) stamps the stored entry with its own clock;
                // the caller cannot outsource/forward-anchor the TTL.
                val issued = SystemClock.elapsedRealtime()
                // Refuse publishes carrying an undeliverable identity (sentinel/negative/zero axes):
                // fail-closed at the boundary instead of occupying the slot until TTL.
                if (token <= 0L || targetTaskId < 0 ||
                    sessionInstanceId <= 0L
                ) {
                    return null
                }
                // Ack semantics: a non-null Bundle means the entry was stored, and the stored
                // entry carries the PROVIDER's clock stamp (peek reads it back from _pending, so
                // the stamp round-trips). A null ack means "not stored" so the caller can retry
                // instead of believing a dead token lives.
                if (publish(PendingVoiceRetrigger(token, targetTaskId, sessionInstanceId, issued))) {
                    Bundle.EMPTY
                } else {
                    null
                }
            }
            VoiceHandoffContract.METHOD_CONSUME -> {
                // M-1: the IPC boundary requires full identity. A token-only / wildcard
                // METHOD_CONSUME is rejected here (ok=false, entry preserved) — the
                // "voice task can only consume its own token" binding is provider-enforced,
                // not a caller-side convention. The in-process companion consume() stays
                // wildcard-capable for same-process/test use; see its KDoc.
                val b = extras
                // Gate before any typed read: presence of both identity keys is required first
                // (wildcard rejected). A wrong-typed value throws here and is caught by the
                // envelope catch → null ack, entry preserved.
                if (b == null ||
                    !b.containsKey(VoiceHandoffContract.KEY_TARGET_TASK_ID) ||
                    !b.containsKey(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID)
                ) {
                    return Bundle().apply { putBoolean(VoiceHandoffContract.KEY_OK, false) }
                }
                val targetTaskId = b.getInt(VoiceHandoffContract.KEY_TARGET_TASK_ID)
                val sessionInstanceId = b.getLong(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID)
                if (targetTaskId == VoiceHandoffContract.WILDCARD_TASK_ID ||
                    sessionInstanceId == VoiceHandoffContract.WILDCARD_SESSION_INSTANCE_ID
                ) {
                    return Bundle().apply { putBoolean(VoiceHandoffContract.KEY_OK, false) }
                }
                val token = b.getLong(VoiceHandoffContract.KEY_TOKEN, 0L)
                val ok = consume(token, targetTaskId, sessionInstanceId)
                Bundle().apply { putBoolean(VoiceHandoffContract.KEY_OK, ok) }
            }
            VoiceHandoffContract.METHOD_CLEAR -> {
                // L-4: the bare no-arg path is same-process only (facade/tests call the
                // companion clearAll() directly). IPC can never reach clearAll: with no
                // extras (or extras carrying no token and no identity keys) this is a no-op.
                if (arg == null && extras == null) {
                    Bundle.EMPTY
                } else {
                    val b = extras ?: Bundle()
                    val token = b.getLong(VoiceHandoffContract.KEY_TOKEN, 0L)
                    // An explicitly-sent wildcard sentinel is never a legitimate IPC clear
                    // (identityBundle omits wildcard axes entirely) — refuse it, entry preserved.
                    // Wildcards remain the *omission* default for the other axis only.
                    val targetRaw = b.getInt(VoiceHandoffContract.KEY_TARGET_TASK_ID, VoiceHandoffContract.WILDCARD_TASK_ID)
                    val sessionRaw = b.getLong(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID, VoiceHandoffContract.WILDCARD_SESSION_INSTANCE_ID)
                    val targetExplicitWildcard = b.containsKey(VoiceHandoffContract.KEY_TARGET_TASK_ID) && targetRaw == VoiceHandoffContract.WILDCARD_TASK_ID
                    val sessionExplicitWildcard = b.containsKey(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID) && sessionRaw == VoiceHandoffContract.WILDCARD_SESSION_INSTANCE_ID
                    if (targetExplicitWildcard || sessionExplicitWildcard) {
                        // Explicit wildcard sentinel(s) are never legitimate over IPC —
                        // refuse, entry preserved. Wildcards remain the *omission* default
                        // for the unspecified axis only.
                        Bundle.EMPTY
                    } else {
                        val hasTarget = b.containsKey(VoiceHandoffContract.KEY_TARGET_TASK_ID) ||
                            b.containsKey(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID)
                        if (hasTarget) {
                            clearMatching(targetRaw, sessionRaw, token)
                        } else if (token != 0L) {
                            clearIfToken(token)
                        }
                        // else: no token and no identity keys -> no-op (no clearAll from IPC).
                        Bundle.EMPTY
                    }
                }
            }
            VoiceHandoffContract.METHOD_CLEAR_EXPIRED -> {
                // Expired-only convergence for a fresh session cycle: drop the slot iff the
                // entry is expired (age < 0 or age > TTL); unexpired entries are preserved.
                clearExpired()
                Bundle.EMPTY
            }
            VoiceHandoffContract.METHOD_PEEK -> {
                val p = _pending.value
                // Same TTL gate as consume: an expired (or clock-skewed) entry must not leak
                // its token payload to peekers; report it as absent instead.
                val stale = p != null &&
                    VoiceHandoffContract.isExpired(p.issuedElapsedRealtimeMs, SystemClock.elapsedRealtime())
                if (stale) {
                    Bundle.EMPTY
                } else {
                    Bundle().apply {
                        if (p != null) {
                            putLong(VoiceHandoffContract.KEY_TOKEN, p.token)
                            putInt(VoiceHandoffContract.KEY_TARGET_TASK_ID, p.targetTaskId)
                            putLong(VoiceHandoffContract.KEY_SESSION_INSTANCE_ID, p.sessionInstanceId)
                            putLong(VoiceHandoffContract.KEY_ISSUED_ELAPSED_REALTIME_MS, p.issuedElapsedRealtimeMs)
                        }
                    }
                }
            }
            else -> null
            }
        } catch (t: Throwable) {
            // L-1: no payload, no extras contents — only the method name, stripped and truncated.
            val safeMethod = method.take(32).replace('\n', ' ').replace('\r', ' ')
            if (t is Error) {
                Log.e(TAG, "voice-handoff IPC failure (method=$safeMethod)", t)
            } else {
                Log.w(TAG, "voice-handoff IPC envelope rejected (method=$safeMethod)")
            }
            null
        }
    }

    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "VoiceHandoffProvider"

        private val _pending = MutableStateFlow<PendingVoiceRetrigger?>(null)
        val pending: StateFlow<PendingVoiceRetrigger?> = _pending.asStateFlow()
        // Seeded from monotonic elapsed realtime so tokens stay monotonic across :voice-process restarts
        // (a fresh process must not re-issue token 1 and collide with/impersonate a live token).
        // (elapsed realtime shl 12) keeps bounded headroom below Long overflow and starts odd; any residual
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

        /**
         * Same-process/in-process consume; wildcard-capable ([VoiceHandoffContract.WILDCARD_TASK_ID] /
         * [VoiceHandoffContract.WILDCARD_SESSION_INSTANCE_ID] = "unspecified" axis).
         * The IPC boundary ([call]) rejects token-only/wildcard consumes — callers there must
         * carry full identity. This companion path stays permissive for same-process use
         * (e.g. in-process delivery, tests); it never crosses a process boundary.
         */
        @Synchronized
        internal fun consume(token: Long, targetTaskId: Int, sessionInstanceId: Long): Boolean {
            val cur = _pending.value ?: return false
            if (token == 0L || cur.token != token) return false
            // If caller provides identity constraints, enforce them; the wildcard means "unspecified".
            if (targetTaskId != VoiceHandoffContract.WILDCARD_TASK_ID && cur.targetTaskId != targetTaskId) return false
            if (sessionInstanceId != VoiceHandoffContract.WILDCARD_SESSION_INSTANCE_ID && cur.sessionInstanceId != sessionInstanceId) return false
            // TTL: an expired retrigger must never fire STT; drop it on consume attempt.
            // Negative age (clock skew / bogus issued timestamp) is treated as expired too.
            if (VoiceHandoffContract.isExpired(cur.issuedElapsedRealtimeMs, SystemClock.elapsedRealtime())) {
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
            val taskMatches = targetTaskId == VoiceHandoffContract.WILDCARD_TASK_ID || cur.targetTaskId == targetTaskId
            val sessionMatches = sessionInstanceId == VoiceHandoffContract.WILDCARD_SESSION_INSTANCE_ID ||
                cur.sessionInstanceId == sessionInstanceId
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

        /**
         * Expired-only convergence: drops the pending entry iff it is expired-or-skewed
         * (see [VoiceHandoffContract.isExpired]); an unexpired entry is preserved. No return value.
         * Used by a fresh session cycle to converge dead prior-cycle residue without
         * touching a live entry another session instance just published.
         */
        @Synchronized
        internal fun clearExpired() {
            val cur = _pending.value ?: return
            if (VoiceHandoffContract.isExpired(cur.issuedElapsedRealtimeMs, SystemClock.elapsedRealtime())) {
                _pending.value = null
            }
        }

        /** Test/reset hook (main-process/in-process only). */
        @Synchronized
        internal fun resetForTest() {
            _pending.value = null
            seq.set((SystemClock.elapsedRealtime() shl 12) or 1L)
        }
    }
}
