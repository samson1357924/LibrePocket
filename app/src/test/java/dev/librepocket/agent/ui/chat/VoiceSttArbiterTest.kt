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
 * - onLaunchReported(t)  → slot held until exactly one onResultSettled();
 * - onResultSettled      → clears the slot so the next armed arm can be admitted;
 * - zero retrigger token → absent ([isRetriggerArmed]).
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
        arbiter.onResultSettled()
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
    fun `result settle admits the next armed arm in precedence order`() {
        val arbiter = VoiceSttArbiter()
        assertEquals(VoiceSttDecision.Admit(VoiceSttArmKind.RETRIGGER), decideLoaded(arbiter, retrigger = true))
        arbiter.onLaunchReported(VoiceSttArmKind.RETRIGGER, launched = true)
        arbiter.onResultSettled()
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.USER),
            decideLoaded(arbiter, user = true),
        )
    }

    @Test
    fun `result settle when idle is a no-op`() {
        val arbiter = VoiceSttArbiter()
        arbiter.onResultSettled()
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
        arbiter.onResultSettled()
        assertEquals(
            VoiceSttDecision.Admit(VoiceSttArmKind.RETRIGGER),
            decideLoaded(arbiter, retrigger = true, user = true),
        )
        arbiter.onResultSettled()
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
        arbiter.onResultSettled()
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
}
