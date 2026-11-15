package dev.librepocket.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Decision-table tests for [VoiceSttArbiter] (pure JVM, no Android).
 *
 * Covers the single-owner single-flight contract:
 * - switches not loaded  → Wait, nothing armed, nothing consumed, gate untouched;
 * - idle + arms          → precedence INITIAL > RETRIGGER > USER, admission marks the gate;
 * - busy                 → Idle, pending arms stay armed (no concurrent launches);
 * - onLaunchReported(f)  → slot released immediately (degrade / launch failure path);
 * - onLaunchReported(t)  → slot held until exactly one onResultSettled(gen);
 * - onResultSettled(gen) → clears the slot only when gen pairs with the admitting generation,
 *                          so the next armed arm can be admitted;
 * - watchdog             → a launch with no result for RECOGNIZER_WATCHDOG_NANOS is treated
 *                          as settled; no arm is consumed by the watchdog itself;
 * - generations          → one generation per admit; a stale onResultSettled(gen) from a
 *                          pre-watchdog launch cannot release a successor admitted afterwards;
 *                          onLaunchReported applies the same pairing to its launched = false release;
 * - zero retrigger token → absent ([isRetriggerArmed]);
 * - isStaleVoiceResult   → pure predicate mirror of the result-callback stale判定; watchdog-evicted
 *                          late results are stale, fresh results are not (predicate only, no behavior asserted).
 */
class VoiceSttArbiterTest {

    private fun decideLoaded(
        arbiter: VoiceSttArbiter,
        initial: Boolean = false,
        retrigger: Boolean = false,
        user: Boolean = false,
    ) = arbiter.decide(
        initialRequested = initial,
        retriggerArmed = retrigger,
        userRequested = user,
        switchesLoaded = true,
    )

    @Test
    fun `switches not loaded returns Wait and consumes nothing`() {
        val arbiter = VoiceSttArbiter()
        val decision = arbiter.decide(
            initialRequested = true,
            retriggerArmed = true,
            userRequested = true,
            switchesLoaded = false,
        )
        assertSame(VoiceSttDecision.Wait, decision)
        assertNull(arbiter.currentInFlight)
        // The gate is free and nothing was marked in flight: once loaded, it admits.
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.INITIAL),
            arbiter.decide(true, true, true, switchesLoaded = true),
        )
    }

    @Test
    fun `switches not loaded repeated decisions never admit`() {
        val arbiter = VoiceSttArbiter()
        repeat(3) {
            assertSame(
                VoiceSttDecision.Wait,
                arbiter.decide(true, true, true, switchesLoaded = false),
            )
        }
        assertNull(arbiter.currentInFlight)
    }

    @Test
    fun `loading then loaded admits initial`() {
        val arbiter = VoiceSttArbiter()
        assertSame(VoiceSttDecision.Wait, arbiter.decide(true, true, false, switchesLoaded = false))
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.INITIAL),
            arbiter.decide(true, true, false, switchesLoaded = true),
        )
        assertEquals(VoiceSttArmKind.INITIAL, arbiter.currentInFlight)
    }

    @Test
    fun `idle with initial only admits INITIAL and marks in flight`() {
        val arbiter = VoiceSttArbiter()
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.INITIAL),
            decideLoaded(arbiter, initial = true),
        )
        assertEquals(VoiceSttArmKind.INITIAL, arbiter.currentInFlight)
    }

    @Test
    fun `idle with retrigger only admits RETRIGGER`() {
        val arbiter = VoiceSttArbiter()
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.RETRIGGER),
            decideLoaded(arbiter, retrigger = true),
        )
        assertEquals(VoiceSttArmKind.RETRIGGER, arbiter.currentInFlight)
    }

    @Test
    fun `idle with user only admits USER`() {
        val arbiter = VoiceSttArbiter()
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.USER),
            decideLoaded(arbiter, user = true),
        )
        assertEquals(VoiceSttArmKind.USER, arbiter.currentInFlight)
    }

    @Test
    fun `tie initial over retrigger`() {
        val arbiter = VoiceSttArbiter()
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.INITIAL),
            decideLoaded(arbiter, initial = true, retrigger = true),
        )
    }

    @Test
    fun `tie initial over user`() {
        val arbiter = VoiceSttArbiter()
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.INITIAL),
            decideLoaded(arbiter, initial = true, user = true),
        )
    }

    @Test
    fun `tie retrigger over user`() {
        val arbiter = VoiceSttArbiter()
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.RETRIGGER),
            decideLoaded(arbiter, retrigger = true, user = true),
        )
    }

    @Test
    fun `all three armed admits INITIAL`() {
        val arbiter = VoiceSttArbiter()
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.INITIAL),
            decideLoaded(arbiter, initial = true, retrigger = true, user = true),
        )
    }

    @Test
    fun `busy gate returns Idle and admits nothing`() {
        val arbiter = VoiceSttArbiter()
        assertEquals(VoiceSttDecision.Admit(VoiceSttArmKind.INITIAL), decideLoaded(arbiter, initial = true))
        assertSame(VoiceSttDecision.Idle, decideLoaded(arbiter, retrigger = true, user = true))
        assertSame(VoiceSttDecision.Idle, decideLoaded(arbiter))
        assertEquals(VoiceSttArmKind.INITIAL, arbiter.currentInFlight)
    }

    @Test
    fun `launch reported false releases the slot and admits the next armed arm`() {
        val arbiter = VoiceSttArbiter()
        assertEquals(VoiceSttDecision.Admit(VoiceSttArmKind.INITIAL), decideLoaded(arbiter, initial = true))
        arbiter.onLaunchReported(VoiceSttArmKind.INITIAL, launched = false)
        assertNull(arbiter.currentInFlight)
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.RETRIGGER),
            decideLoaded(arbiter, retrigger = true, user = true),
        )
    }

    @Test
    fun `launch reported true keeps the slot until result settles`() {
        val arbiter = VoiceSttArbiter()
        assertEquals(VoiceSttDecision.Admit(VoiceSttArmKind.USER), decideLoaded(arbiter, user = true))
        arbiter.onLaunchReported(VoiceSttArmKind.USER, launched = true)
        assertEquals(VoiceSttArmKind.USER, arbiter.currentInFlight)
        // A settle is required to release: further decisions stay Idle while busy.
        assertSame(VoiceSttDecision.Idle, decideLoaded(arbiter, initial = true))
        val settleGen = arbiter.currentGeneration
        arbiter.onResultSettled(settleGen)
        assertNull(arbiter.currentInFlight)
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.INITIAL),
            decideLoaded(arbiter, initial = true),
        )
    }

    @Test
    fun `launch reported false with mismatched arm does not release a different slot`() {
        val arbiter = VoiceSttArbiter()
        assertEquals(VoiceSttDecision.Admit(VoiceSttArmKind.INITIAL), decideLoaded(arbiter, initial = true))
        arbiter.onLaunchReported(VoiceSttArmKind.RETRIGGER, launched = false)
        assertEquals(VoiceSttArmKind.INITIAL, arbiter.currentInFlight)
    }

    @Test
    fun `launch reported false with matching generation releases the slot`() {
        val arbiter = VoiceSttArbiter()
        assertEquals(VoiceSttDecision.Admit(VoiceSttArmKind.INITIAL), decideLoaded(arbiter, initial = true))
        // A launched = true report keeps the slot even with the admitting generation.
        arbiter.onLaunchReported(VoiceSttArmKind.INITIAL, launched = true, generation = arbiter.currentGeneration)
        assertEquals(VoiceSttArmKind.INITIAL, arbiter.currentInFlight)
        // The matching generation then pairs with the admitting admit: the slot is released.
        arbiter.onLaunchReported(VoiceSttArmKind.INITIAL, launched = false, generation = arbiter.currentGeneration)
        assertNull(arbiter.currentInFlight)
    }

    @Test
    fun `launch reported false with non-matching generation does not release the slot`() {
        val arbiter = VoiceSttArbiter()
        assertEquals(VoiceSttDecision.Admit(VoiceSttArmKind.INITIAL), decideLoaded(arbiter, initial = true))
        val gen = arbiter.currentGeneration
        // Non-zero generation that does not match the admitting admit: the gate stays in flight.
        arbiter.onLaunchReported(VoiceSttArmKind.INITIAL, launched = false, generation = gen + 1L)
        assertEquals(VoiceSttArmKind.INITIAL, arbiter.currentInFlight)
        // The 2-arg form (default 0L = "ungated") keeps the pre-generation semantics.
        arbiter.onLaunchReported(VoiceSttArmKind.INITIAL, launched = false)
        assertNull(arbiter.currentInFlight)
    }

    @Test
    fun `launch reported false with mismatched arm kind does not release even with matching generation`() {
        val arbiter = VoiceSttArbiter()
        assertEquals(VoiceSttDecision.Admit(VoiceSttArmKind.INITIAL), decideLoaded(arbiter, initial = true))
        val gen = arbiter.currentGeneration
        // Same generation, wrong arm kind: no release.
        arbiter.onLaunchReported(VoiceSttArmKind.RETRIGGER, launched = false, generation = gen)
        assertEquals(VoiceSttArmKind.INITIAL, arbiter.currentInFlight)
        // Same generation, matching arm kind: the slot is released.
        arbiter.onLaunchReported(VoiceSttArmKind.INITIAL, launched = false, generation = gen)
        assertNull(arbiter.currentInFlight)
    }

    @Test
    fun `result settle admits the next armed arm in precedence order`() {
        val arbiter = VoiceSttArbiter()
        assertEquals(VoiceSttDecision.Admit(VoiceSttArmKind.RETRIGGER), decideLoaded(arbiter, retrigger = true))
        arbiter.onLaunchReported(VoiceSttArmKind.RETRIGGER, launched = true)
        val settleGen = arbiter.currentGeneration
        arbiter.onResultSettled(settleGen)
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.USER),
            decideLoaded(arbiter, user = true),
        )
    }

    @Test
    fun `result settle when idle is a no-op`() {
        val arbiter = VoiceSttArbiter()
        arbiter.onResultSettled(arbiter.currentGeneration)
        assertNull(arbiter.currentInFlight)
        assertSame(VoiceSttDecision.Idle, decideLoaded(arbiter))
    }

    @Test
    fun `zero retrigger token is absent`() {
        assertEquals(false, isRetriggerArmed(0L))
        assertEquals(true, isRetriggerArmed(1L))
        assertEquals(true, isRetriggerArmed(Long.MAX_VALUE))
        // A zero-derived "armed" flag must not admit through the decision table.
        val arbiter = VoiceSttArbiter()
        assertSame(
            VoiceSttDecision.Idle,
            decideLoaded(arbiter, retrigger = isRetriggerArmed(0L)),
        )
    }

    @Test
    fun `watchdog stale in-flight is treated as settled and admits the next armed arm`() {
        var now = 0L
        val arbiter = VoiceSttArbiter(clockNanos = { now })
        assertEquals(VoiceSttDecision.Admit(VoiceSttArmKind.INITIAL), decideLoaded(arbiter, initial = true))
        arbiter.onLaunchReported(VoiceSttArmKind.INITIAL, launched = true)
        // Exactly at the watchdog boundary (not strictly greater): still busy.
        now = VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS
        assertSame(VoiceSttDecision.Idle, decideLoaded(arbiter, retrigger = true))
        assertEquals(VoiceSttArmKind.INITIAL, arbiter.currentInFlight)
        // Past the watchdog: the stale launch is settled; the next armed arm is admitted.
        now = VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.RETRIGGER),
            decideLoaded(arbiter, retrigger = true),
        )
        assertEquals(VoiceSttArmKind.RETRIGGER, arbiter.currentInFlight)
    }

    @Test
    fun `in-flight within the watchdog window stays busy`() {
        var now = 0L
        val arbiter = VoiceSttArbiter(clockNanos = { now })
        assertEquals(VoiceSttDecision.Admit(VoiceSttArmKind.RETRIGGER), decideLoaded(arbiter, retrigger = true))
        arbiter.onLaunchReported(VoiceSttArmKind.RETRIGGER, launched = true)
        now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS
        assertSame(VoiceSttDecision.Idle, decideLoaded(arbiter, initial = true, user = true))
        assertEquals(VoiceSttArmKind.RETRIGGER, arbiter.currentInFlight)
    }

    @Test
    fun `watchdog with nothing armed returns Idle then admits after arming`() {
        var now = 0L
        val arbiter = VoiceSttArbiter(clockNanos = { now })
        assertEquals(VoiceSttDecision.Admit(VoiceSttArmKind.INITIAL), decideLoaded(arbiter, initial = true))
        arbiter.onLaunchReported(VoiceSttArmKind.INITIAL, launched = true)
        now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1
        assertSame(VoiceSttDecision.Idle, decideLoaded(arbiter))
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.USER),
            decideLoaded(arbiter, user = true),
        )
    }

    @Test
    fun `watchdog does not consume pending arms they stay armed until admitted`() {
        var now = 0L
        val arbiter = VoiceSttArbiter(clockNanos = { now })
        assertEquals(VoiceSttDecision.Admit(VoiceSttArmKind.INITIAL), decideLoaded(arbiter, initial = true))
        arbiter.onLaunchReported(VoiceSttArmKind.INITIAL, launched = true)
        now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1
        // Both pending arms are still armed when the watchdog fires; none are consumed.
        // Precedence decides who goes first.
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.RETRIGGER),
            decideLoaded(arbiter, retrigger = true, user = true),
        )
        // USER was consumed neither by the watchdog nor by the RETRIGGER admission.
        arbiter.onResultSettled(arbiter.currentGeneration)
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.USER),
            decideLoaded(arbiter, user = true),
        )
    }

    @Test
    fun `staleSettleAfterWatchdog_doesNotClearSuccessor`() {
        var now = 0L
        val arbiter = VoiceSttArbiter(clockNanos = { now })
        assertEquals(VoiceSttDecision.Admit(VoiceSttArmKind.INITIAL), decideLoaded(arbiter, initial = true))
        arbiter.onLaunchReported(VoiceSttArmKind.INITIAL, launched = true)
        val initialGen = arbiter.currentGeneration
        assertEquals(1L, initialGen)
        // Past the watchdog: the stale launch is treated as settled; the next armed arm is admitted.
        now = VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.RETRIGGER),
            decideLoaded(arbiter, retrigger = true),
        )
        val retriggerGen = arbiter.currentGeneration
        assertEquals(2L, retriggerGen)
        assertEquals(VoiceSttArmKind.RETRIGGER, arbiter.currentInFlight)
        // Stale callback from the pre-watchdog launch: generation pairing rejects it, successor intact.
        arbiter.onResultSettled(1L)
        assertEquals(VoiceSttArmKind.RETRIGGER, arbiter.currentInFlight)
        // Gate still held: a further decision stays Idle.
        assertSame(VoiceSttDecision.Idle, decideLoaded(arbiter, user = true))
        // The successor settles with its own generation: slot clears, next arm admits.
        arbiter.onResultSettled(2L)
        assertNull(arbiter.currentInFlight)
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.USER),
            decideLoaded(arbiter, user = true),
        )
    }

    @Test
    fun `sameKindSuccession_staleSettleDoesNotClearSuccessor`() {
        var now = 0L
        val arbiter = VoiceSttArbiter(clockNanos = { now })
        assertEquals(VoiceSttDecision.Admit(VoiceSttArmKind.INITIAL), decideLoaded(arbiter, initial = true))
        arbiter.onLaunchReported(VoiceSttArmKind.INITIAL, launched = true)
        val firstGen = arbiter.currentGeneration
        assertEquals(1L, firstGen)
        // Watchdog fires; the same arm kind is admitted again as the successor (generation 2).
        now = VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.INITIAL),
            decideLoaded(arbiter, initial = true),
        )
        val secondGen = arbiter.currentGeneration
        assertEquals(2L, secondGen)
        assertEquals(VoiceSttArmKind.INITIAL, arbiter.currentInFlight)
        // Stale settle for the pre-watchdog INITIAL must not clear the successor's slot.
        arbiter.onResultSettled(firstGen)
        assertEquals(VoiceSttArmKind.INITIAL, arbiter.currentInFlight)
        // Successor settles with its own generation: slot clears.
        arbiter.onResultSettled(secondGen)
        assertNull(arbiter.currentInFlight)
    }

    @Test
    fun staleVoiceResult_watchdogEvicted_returnsTrueOrSameGen() {
        // S3-style: watchdog evicted the launch; gate empty, generation unchanged → stale.
        assertEquals(true, isStaleVoiceResult(gen = 1L, admitGeneration = 1L, inFlight = null))
        // Successor admitted after the watchdog: same stale gen vs the successor's admit generation → stale.
        assertEquals(true, isStaleVoiceResult(gen = 1L, admitGeneration = 2L, inFlight = VoiceSttArmKind.INITIAL))
        // Fresh successor result: gen pairs with the live admission → not stale.
        assertEquals(false, isStaleVoiceResult(gen = 2L, admitGeneration = 2L, inFlight = VoiceSttArmKind.RETRIGGER))
        // gen == 0L is conservative-fresh (direct callers/tests), whatever the gate looks like.
        assertEquals(false, isStaleVoiceResult(gen = 0L, admitGeneration = 0L, inFlight = null))
        assertEquals(false, isStaleVoiceResult(gen = 0L, admitGeneration = 2L, inFlight = null))
        assertEquals(false, isStaleVoiceResult(gen = 0L, admitGeneration = 2L, inFlight = VoiceSttArmKind.USER))
        // Fresh settle CAS ordering is unaffected — predicate only; the CAS stays in onResultSettled.
    }

    @Test
    fun `reset clears the in-flight slot`() {
        val arbiter = VoiceSttArbiter()
        assertEquals(VoiceSttDecision.Admit(VoiceSttArmKind.INITIAL), decideLoaded(arbiter, initial = true))
        arbiter.reset()
        assertNull(arbiter.currentInFlight)
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.INITIAL),
            decideLoaded(arbiter, initial = true),
        )
    }

    @Test
    fun threeArmSuccession_idleThenSettleAdmitsInOrder() {
        val arbiter = VoiceSttArbiter()
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.INITIAL),
            decideLoaded(arbiter, initial = true, retrigger = true, user = true),
        )
        val initialGen = arbiter.currentGeneration
        arbiter.onResultSettled(initialGen)
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.RETRIGGER),
            decideLoaded(arbiter, retrigger = true, user = true),
        )
        val retriggerGen = arbiter.currentGeneration
        arbiter.onResultSettled(retriggerGen)
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.USER),
            decideLoaded(arbiter, user = true),
        )
    }

    @Test
    fun twoPendingArms_decideAdmitsOnePerCall() {
        val arbiter = VoiceSttArbiter()
        // First decide admits exactly one arm; the other stays armed.
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.RETRIGGER),
            decideLoaded(arbiter, retrigger = true, user = true),
        )
        // While that admission is in flight, decide admits nothing.
        assertSame(VoiceSttDecision.Idle, decideLoaded(arbiter, user = true))
        assertEquals(VoiceSttArmKind.RETRIGGER, arbiter.currentInFlight)
        // After settle, the remaining arm is admitted — one per call.
        arbiter.onResultSettled(arbiter.currentGeneration)
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.USER),
            decideLoaded(arbiter, user = true),
        )
        assertEquals(VoiceSttArmKind.USER, arbiter.currentInFlight)
    }

    @Test
    fun `idle with nothing armed is Idle`() {
        val arbiter = VoiceSttArbiter()
        assertSame(VoiceSttDecision.Idle, decideLoaded(arbiter))
        // Zero token derives "absent" and likewise admits nothing.
        assertSame(VoiceSttDecision.Idle, decideLoaded(arbiter, retrigger = isRetriggerArmed(0L)))
    }

    @Test
    fun arbiter_watchdogCycle_preservesGenerationPairingAboveThree() {
        var now = 1_000_000_000L
        val arbiter = VoiceSttArbiter(clockNanos = { now })

        // gen1: INITIAL admits and never reports back (recognizer hangs).
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.INITIAL),
            decideLoaded(arbiter, initial = true),
        )
        val gen1 = arbiter.currentGeneration
        assertEquals(1L, gen1)
        arbiter.onLaunchReported(VoiceSttArmKind.INITIAL, launched = true, generation = gen1)

        // Watchdog fires; RETRIGGER admitted as gen2.
        now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.RETRIGGER),
            decideLoaded(arbiter, retrigger = true),
        )
        val gen2 = arbiter.currentGeneration
        assertEquals(2L, gen2)
        arbiter.onLaunchReported(VoiceSttArmKind.RETRIGGER, launched = true, generation = gen2)

        // gen2 settles normally; USER admitted as gen3.
        arbiter.onResultSettled(gen2)
        assertNull(arbiter.currentInFlight)
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.USER),
            decideLoaded(arbiter, user = true),
        )
        val gen3 = arbiter.currentGeneration
        assertEquals(3L, gen3)
        arbiter.onLaunchReported(VoiceSttArmKind.USER, launched = true, generation = gen3)

        // Watchdog fires again; a fresh INITIAL flag is admitted as gen4.
        now += VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS + 1
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.INITIAL),
            decideLoaded(arbiter, initial = true),
        )
        val gen4 = arbiter.currentGeneration
        assertEquals(4L, gen4)
        assertEquals(VoiceSttArmKind.INITIAL, arbiter.currentInFlight)

        // Stale settles from every pre-gen4 generation cannot release gen4's slot.
        arbiter.onResultSettled(gen1)
        arbiter.onResultSettled(gen2)
        arbiter.onResultSettled(gen3)
        assertEquals(VoiceSttArmKind.INITIAL, arbiter.currentInFlight)
        // Busy gate stays Idle while gen4 is in flight.
        assertSame(VoiceSttDecision.Idle, decideLoaded(arbiter, retrigger = true, user = true))

        // gen4 settles with its own generation: the slot clears.
        arbiter.onResultSettled(gen4)
        assertNull(arbiter.currentInFlight)
        assertSame(VoiceSttDecision.Idle, decideLoaded(arbiter))
    }
}
