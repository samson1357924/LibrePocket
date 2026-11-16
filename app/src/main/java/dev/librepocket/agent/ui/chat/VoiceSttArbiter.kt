package dev.librepocket.agent.ui.chat

import androidx.annotation.VisibleForTesting

/**
 * Single-owner arbiter for every system-STT (`RecognizerIntent`) launch.
 *
 * Stage C (F1/P2) single-flight: At most one arm holds the gate at any time; a watchdog-released
 * hung recognizer may coexist with its successor — result attribution stays exact via per-launch
 * slots (ChatScreen) and generations (this class). Arm sources: the one-shot initial voice
 * request, the C2 retrigger handoff token, and the mic button. The pending arm is never
 * consumed while another recognizer is in flight; instead it stays armed and is admitted in
 * deterministic precedence order — INITIAL > RETRIGGER > USER — once the in-flight launch reports
 * its result ([onResultSettled]) or reports it could not launch ([onLaunchReported] with
 * `launched = false`).
 *
 * Consumption stays caller-side: the caller consumes the one-shot arm (and, for RETRIGGER, may
 * fail the consume) BEFORE launching the recognizer. If the launch itself does not happen
 * (degrade, exception, or a rejected retrigger consume), the caller reports
 * `onLaunchReported(arm, launched = false)` and the gate is released immediately; a failed
 * launch never holds the slot. If the launch did happen, the caller reports
 * `onLaunchReported(arm, launched = true)` and the slot is held until exactly one
 * [onResultSettled] (the top of the result callback — every path). Production callers pass
 * [currentGeneration] (the admitting generation at call time) as [onLaunchReported]'s
 * `generation` argument; the generation guard protects against cross-admit misuse and reordered
 * reports.
 *
 * Watchdog: a launch whose result callback never fires would otherwise hold the single-flight
 * slot forever (voice would decide [VoiceSttDecision.Idle] indefinitely). The watchdog bounds
 * that pathological case: a launch with no result for [RECOGNIZER_WATCHDOG_NANOS] (2 minutes,
 * generous vs OEM recognizers) is treated as settled and the slot is released. A negative delta
 * ([clockNanos] reading earlier than the recorded admit time — only reachable via a restored/
 * crafted far-future admittedAtNanos, since production clocks are monotonic within a boot) is
 * treated the same: the launch is evicted, not trusted. Both release
 * paths release the slot WITHOUT consuming any arm. The watchdog is lazy: it fires only at the
 * next decision evaluation ([decide] checks it first). The Compose shell additionally installs
 * a one-shot timer effect that forces exactly one decision evaluation at expiry (by dropping the
 * compose mirror of the in-flight arm to null), so the slot is released even if no other state
 * change ever re-evaluates the decision table.
 *
 * Generations: every admission ([decide] returning [VoiceSttDecision.Admit]) bumps a monotonic
 * generation counter by one (one generation per admit; see [currentGeneration]). [onResultSettled]
 * must be called with the generation captured at admit time; it releases the slot only when that
 * generation still pairs with the admitting admit. The watchdog releases the slot WITHOUT
 * consuming any arm and WITHOUT minting a new generation, so a stale result callback from a
 * pre-watchdog launch (generation N) can never release a successor that was admitted afterwards
 * (generation N+1) — [onResultSettled] for the stale generation is a no-op. [onLaunchReported]
 * applies the same generation pairing to its `launched = false` release (see its KDoc).
 * [reset] resets [currentGeneration] to 0 as well.
 *
 * Restore semantics (process death / Activity recreation): the arbiter can be constructed with
 * [initialAdmitGeneration] / [initialInFlight] / [initialAdmittedAtNanos] restored from a persisted
 * snapshot (see `SttLaunchState`). The generation watermark NEVER resets below the restored value —
 * the next admit mints `initialAdmitGeneration + 1`, so a restored generation still pairs exactly
 * with its launch and a pre-recreation result remains attributable. Restored [initialInFlight] is
 * accepted as-is (trusted snapshot); the watchdog stays armed from [initialAdmittedAtNanos], so a
 * restored launch whose recognizer never reports is evicted at the next [decide] as usual — this
 * covers a restored [initialAdmittedAtNanos] that lies in the future relative to the clock (a
 * negative delta), which is treated as expired rather than trusted. 0L is
 * never minted as a generation by production admits (every admit increments from a non-negative
 * watermark), which is what lets the shell treat 0L as "unattributable" (see
 * `isUnattributedVoiceResult`).
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

internal class VoiceSttArbiter(
    internal val clockNanos: () -> Long = System::nanoTime,
    initialAdmitGeneration: Long = 0L,
    initialInFlight: VoiceSttArmKind? = null,
    initialAdmittedAtNanos: Long = 0L,
) {
    /**
     * Restored as-is from the snapshot (trusted input); when no snapshot exists the defaults
     * (null / 0L) reproduce the fresh-construction behaviour.
     */
    private var inFlight: VoiceSttArmKind? = initialInFlight

    /** Monotonic timestamp (clock source: [clockNanos]) at which the current in-flight arm was admitted; 0 is only a sentinel for unadmitted (clock nanos may legitimately return any sign). */
    private var admittedAtNanos: Long = initialAdmittedAtNanos

    /**
     * Generation counter, bumped on every admit (one generation per admit). Never decreases
     * during the object lifetime except via [reset]. The result callback pairs itself with the
     * admitting generation via [onResultSettled]; a stale callback from a pre-watchdog launch
     * cannot release a successor that was admitted afterwards. When constructed with
     * [initialAdmitGeneration] (restore path), the watermark starts there and only moves up.
     */
    private var admitGeneration: Long = initialAdmitGeneration

    val currentInFlight: VoiceSttArmKind? get() = inFlight

    /** Generation of the most recent admit; 0 when nothing has ever been admitted (or after [reset]). */
    internal val currentGeneration: Long get() = admitGeneration

    /**
     * Timestamp (clock source: [clockNanos]) of the admit that paired with the current in-flight
     * arm — the value a restore snapshot persists (see `SttLaunchState`). 0 when no arm is in flight.
     * Invariant: 0L exactly when [currentInFlight] is null — every gate-clear path (watchdog in
     * [decide], [onResultSettled], [onLaunchReported], [reset]) zeroes it, so a snapshot captured
     * at any such moment satisfies the saveable invariant.
     */
    internal val admittedAtNanosSnapshot: Long get() = admittedAtNanos

    /**
     * Drop the in-flight marker without touching anything else.
     * @VisibleForTesting 僅測試用；production 不呼叫（恢復路徑由 ChatScreen 的「重設語音辨識」
     * 走 [hardReset]／resetEpoch 世代承擔）。
     * Also resets [currentGeneration] to 0.
     */
    @VisibleForTesting
    fun reset() {
        inFlight = null
        admittedAtNanos = 0L
        admitGeneration = 0L
    }

    /**
     * User-triggered hard reset for a wedged session (voice recovery affordance): drop any
     * in-flight arm and its admit timestamp, but keep the generation watermark monotonic — a
     * post-reset admit can never reuse a pre-reset generation. Used ONLY by the explicit user
     * recovery action; never by internal bookkeeping.
     *
     * Attribution caveat (honest): a pre-reset recognizer's request code can never reach the
     * shell's result handler with an attributable effect post-reset (in-process). Its launcher
     * is unregistered at epoch advance, the pre-reset registry key is never re-registered within
     * the same entry, and the registry parks the late result undeliverable under the dead key.
     * Cross-entry late results terminate at the registry boundary (keys embed the per-entry
     * screenId, so a NEW ChatScreen entry never re-registers the destroyed entry's key; precondition:
     * CHAT back-stack entries are never restored with restoreState=true — see screenId KDoc / docs). The
     * remaining framework-level path is the same-entry re-registration and re-dispatch of a
     * pending result across process death (androidx contract, not simulated here): it enters the
     * handler; attribution is preserved when the SttLaunchState snapshot restores (delivery is
     * the designed outcome, pinned by the recreation tests), and gen==0L fail-closed drops only
     * when the snapshot failed to restore. Post-reset the pre-reset key never re-registers in
     * any restore whose parcel carries the post-reset (or later) saved state — the normal case
     * for configuration change and process death. If process death occurs before the first
     * post-reset onSaveInstanceState, the parcel predates the reset: epoch and screenId restore
     * as-pre-reset, the key re-registers identically, and the parked result re-enters under the
     * pre-reset identity — residual (a) (delivery with restored attribution, prefill only; or
     * gen==0L fail-closed drop). Never cross-entry misattribution. See
     * CAPABILITY_MATRIX and THREAT_MODEL residual (a). The slot/generation
     * pairing (slot tables read 0L → fail-closed drop) is only the second layer — defense in
     * depth. Documented in CAPABILITY_MATRIX/THREAT_MODEL.
     */
    fun hardReset() {
        inFlight = null
        admittedAtNanos = 0L
    }

    /**
     * Decide the next arm to admit given the pending arms, or a [VoiceSttDecision.Wait] /
     * [VoiceSttDecision.Idle]. Never decides a switch while [switchesLoaded] is false; never
     * admits while a previous launch is still in flight. Admitting marks the gate in flight;
     * consumption happens outside (caller invokes the one-shot consume callbacks exactly once).
     *
     * Watchdog: checked first. A launch with no result for [RECOGNIZER_WATCHDOG_NANOS] is
     * treated as settled — the in-flight marker is cleared (consuming no arm and minting no
     * new generation) and the pending arms stay armed (no arm is consumed by the watchdog
     * itself); the decision then proceeds with the normal evaluation, so the caller
     * effectively re-decides in the same call. A negative delta (clock moved backwards —
     * only reachable via a restored/crafted far-future [initialAdmittedAtNanos], since
     * production clocks are monotonic within a boot) is likewise treated as expired and
     * evicted the same way.
     */
    fun decide(
        initialRequested: Boolean,
        retriggerArmed: Boolean,
        userRequested: Boolean,
        switchesLoaded: Boolean,
    ): VoiceSttDecision {
        val now = clockNanos()
        val delta = now - admittedAtNanos
        if (inFlight != null && (delta > RECOGNIZER_WATCHDOG_NANOS || delta < 0)) {
            // Watchdog fired (too long since admit, or a negative delta — i.e. a restored/crafted
            // far-future admittedAtNanos): treat the recognizer as settled. Consumes nothing.
            inFlight = null
            admittedAtNanos = 0L
        }
        if (!switchesLoaded) return VoiceSttDecision.Wait
        if (inFlight != null) return VoiceSttDecision.Idle
        val arm = when {
            initialRequested -> VoiceSttArmKind.INITIAL
            retriggerArmed -> VoiceSttArmKind.RETRIGGER
            userRequested -> VoiceSttArmKind.USER
            else -> return VoiceSttDecision.Idle
        }
        inFlight = arm
        admitGeneration += 1L
        admittedAtNanos = now
        return VoiceSttDecision.Admit(arm)
    }

    /**
     * Report whether the admitted arm actually launched. `launched = false` (degrade path,
     * launch exception, or a rejected retrigger consume) releases the slot immediately;
     * `launched = true` keeps the slot until [onResultSettled].
     *
     * [generation] (default `0L`, meaning "ungated"): when non-zero, the slot is released only
     * while [inFlight] == [admitted] AND [admitGeneration] == [generation] — the same pairing
     * discipline as [onResultSettled]. Production callers pass [currentGeneration] (the admitting
     * generation at call time); the generation guard protects against cross-admit misuse and
     * reordered reports. The default `0L` keeps the
     * pre-generation semantics (release whenever the kind matches) so existing direct callers
     * and unit tests behave as before.
     */
    fun onLaunchReported(admitted: VoiceSttArmKind, launched: Boolean, generation: Long = 0L) {
        if (!launched && inFlight == admitted && (generation == 0L || admitGeneration == generation)) {
            inFlight = null
            admittedAtNanos = 0L
        }
    }

    /**
     * Clear the in-flight slot, exactly once per settled launch (result callback top).
     * [generation] must be the [currentGeneration] captured at the admit that produced this
     * launch: the slot is cleared only when the launch is still in flight AND [generation]
     * equals the current [currentGeneration]. A stale callback from a pre-watchdog launch
     * (a generation older than the successor's admit) is a no-op and cannot release the
     * successor.
     */
    fun onResultSettled(generation: Long) {
        if (inFlight != null && generation == admitGeneration) {
            inFlight = null
            admittedAtNanos = 0L
        }
    }

    internal companion object {
        /**
         * Bounds the pathological case where a recognizer launches but no result callback ever
         * fires — STT would otherwise decide [VoiceSttDecision.Idle] forever and the single-flight
         * slot would leak for the rest of the process lifetime. 2 minutes is generous versus OEM
         * recognizers; when it elapses, the slot is released and the pending arms stay armed
         * until admitted by the normal decision table.
         */
        const val RECOGNIZER_WATCHDOG_NANOS = 120_000_000_000L
    }
}

/** True iff a result belonging to [gen] (a launch's admission generation) is stale by the time it
 *  is delivered — watchdog-released arms minted no generation, so a stale late result can carry the
 *  pre-eviction admitting generation; successor arms mint a new one. Anything equal to 0L is
 *  conservative-fresh (direct callers/tests must not regress); the shell filters 0L upstream via
 *  `isUnattributedVoiceResult`, so 0L never reaches this predicate in production. */
internal fun isStaleVoiceResult(gen: Long, admitGeneration: Long, inFlight: VoiceSttArmKind?): Boolean =
    gen != 0L && (gen != admitGeneration || inFlight == null)
