package dev.librepocket.agent.ui.chat

import androidx.annotation.VisibleForTesting

/**
 * Single-owner arbiter for every system-STT (`RecognizerIntent`) launch.
 *
 * Stage C (F1/P2) single-flight: there is at most one RecognizerIntent launch in flight
 * at any time, across ALL arm sources (the one-shot initial voice request, the C2 retrigger
 * handoff token, and the mic button). The pending arm is never consumed while another
 * recognizer is in flight; instead it stays armed and is admitted in deterministic precedence
 * order — INITIAL > RETRIGGER > USER — once the in-flight launch reports its result
 * ([onResultSettled]) or reports it could not launch ([onLaunchReported] with `launched = false`).
 *
 * Consumption stays caller-side: the caller consumes the one-shot arm (and, for RETRIGGER, may
 * fail the consume) BEFORE `sttLauncher.launch(...)`. If the launch itself does not happen
 * (degrade, exception, or a rejected retrigger consume), the caller reports
 * `onLaunchReported(arm, launched = false)` and the gate is released immediately; a failed
 * launch never holds the slot. If the launch did happen, the caller reports
 * `onLaunchReported(arm, launched = true)` and the slot is held until exactly one
 * [onResultSettled] (the top of the `sttLauncher` result callback — every path).
 *
 * Not Android-aware on purpose (pure Kotlin) so the decision table is JVM-unit-testable
 * (see `VoiceSttArbiterTest`). A retrigger arm is derived from its one-shot token by the
 * caller via [isRetriggerArmed] (`token != 0L`); a zero token is absent.
 */
internal enum class VoiceSttArmKind { INITIAL, RETRIGGER, USER }

internal sealed interface VoiceSttDecision {
    /**
     * Not decidable yet / busy with nothing to admit this evaluation: do not consume anything.
     * Returned only when switches have not loaded (the arbiter never admits — and therefore
     * never consumes — before the load settles). A busy gate returns [Idle] instead; the
     * pending arm stays armed for a later evaluation.
     */
    object Wait : VoiceSttDecision

    /** Admit this arm now. The gate is marked in flight as part of the decision. */
    data class Admit(val arm: VoiceSttArmKind) : VoiceSttDecision

    /** Nothing to admit this evaluation (nothing armed, or the gate is busy). */
    object Idle : VoiceSttDecision
}

/** One-shot retrigger tokens are absent at zero; derivation single-sourced here for the caller. */
internal fun isRetriggerArmed(token: Long): Boolean = token != 0L

internal class VoiceSttArbiter {
    private var inFlight: VoiceSttArmKind? = null

    val currentInFlight: VoiceSttArmKind? get() = inFlight

    /**
     * Drop the in-flight marker without touching anything else.
     * Test/lifecycle hook; not used in production.
     */
    @VisibleForTesting
    fun reset() {
        inFlight = null
    }

    /**
     * Decide the next arm to admit given the pending arms, or a [VoiceSttDecision.Wait] /
     * [VoiceSttDecision.Idle]. Never decides a switch while [switchesLoaded] is false; never
     * admits while a previous launch is still in flight. Admitting marks the gate in flight;
     * consumption happens outside (caller invokes the one-shot consume callbacks exactly once).
     */
    fun decide(
        initialRequested: Boolean,
        retriggerArmed: Boolean,
        userRequested: Boolean,
        switchesLoaded: Boolean,
    ): VoiceSttDecision {
        if (!switchesLoaded) return VoiceSttDecision.Wait
        if (inFlight != null) return VoiceSttDecision.Idle
        val arm = when {
            initialRequested -> VoiceSttArmKind.INITIAL
            retriggerArmed -> VoiceSttArmKind.RETRIGGER
            userRequested -> VoiceSttArmKind.USER
            else -> return VoiceSttDecision.Idle
        }
        inFlight = arm
        return VoiceSttDecision.Admit(arm)
    }

    /**
     * Report whether the admitted arm actually launched. `launched = false` (degrade path,
     * launch exception, or a rejected retrigger consume) releases the slot immediately;
     * `launched = true` keeps the slot until [onResultSettled].
     */
    fun onLaunchReported(admitted: VoiceSttArmKind, launched: Boolean) {
        if (!launched && inFlight == admitted) {
            inFlight = null
        }
    }

    /** Clear the in-flight slot. Call exactly once per settled launch (result callback top). */
    fun onResultSettled() {
        inFlight = null
    }
}
