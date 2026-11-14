package dev.librepocket.agent.assistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local, trusted handoff for C2 voice retrigger requests (Issue #137).
 *
 * `LibreVoiceInteractionSession` (system-trusted voice session path) publishes a new token when
 * it accepts a real follow-up `onShow` while an active voice task is already running. UI code
 * running in the same process may consume the token once under existing delivery/trust gates to
 * relaunch system STT on the already-active trusted voice activity.
 *
 * This is not an external-intent trust signal and must not be writable/authoritative from
 * untrusted callers; only the session retrigger path should publish tokens.
 */
object AssistantVoiceHandoff {
    private val seq = AtomicLong(0L)
    private val _token = MutableStateFlow(0L)
    val token: StateFlow<Long> = _token.asStateFlow()

    /** Returns the current pending retrigger token, or 0 if none. */
    fun peek(): Long = _token.value

    /** Publishes a new one-shot retrigger token (trusted session path only). */
    fun requestRetrigger(): Long {
        val next = seq.incrementAndGet()
        _token.value = next
        return next
    }

    /** Consumes [token] if it is still pending; returns true when consumed. */
    fun consume(token: Long): Boolean {
        if (token == 0L) return false
        synchronized(this) {
            if (_token.value != token) return false
            _token.value = 0L
            return true
        }
    }

    /** Clears any pending token (e.g. task/session end). */
    fun clear() {
        _token.value = 0L
    }
}
