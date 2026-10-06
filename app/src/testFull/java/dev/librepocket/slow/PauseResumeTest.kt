package dev.librepocket.slow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PauseResumeTest (BACKLOG B9 / ARCHITECTURE §7.3–§7.4): pause stops at
 * the step boundary, resume continues without replaying completed
 * steps, and cancel is cooperative with side-effect auditing.
 */
class PauseResumeTest {

    private class Harness {
        val executed = mutableListOf<StepProposal>()
        var sideEffectSteps = false
        val router = SlowRouter(
            budget = SlowBudget(maxSteps = 10, maxTimeMs = 60_000L),
            proposer = StepProposer { _, _ ->
                StepProposal(SlowAction.Tap("next"), targetDescription = "下一步")
            },
            executor = StepExecutor { proposal ->
                executed.add(proposal)
                StepResult(ok = true, sideEffect = sideEffectSteps)
            },
        )

        fun observation(): SlowObservation = SlowObservation(
            nodes = listOf(UiNode("next", "button", text = "下一步", clickable = true)),
        )
    }

    @Test fun pauseStopsAtStepBoundary() {
        val h = Harness()
        h.router.start("填表")
        h.router.observe(h.observation())
        assertEquals(1, h.executed.size)

        assertTrue(h.router.pause())
        val outcome = h.router.observe(h.observation())

        assertTrue(outcome is SlowOutcome.Idle)
        assertEquals(SlowState.PAUSED, (outcome as SlowOutcome.Idle).state)
        assertEquals(1, h.executed.size) // no step ran while paused
    }

    @Test fun resumeContinuesWithoutReplay() {
        val h = Harness()
        h.router.start("填表")
        h.router.observe(h.observation())
        assertTrue(h.router.pause())
        assertTrue(h.router.resume())

        val outcome = h.router.observe(h.observation())
        assertTrue(outcome is SlowOutcome.Acted)
        assertEquals(2, h.executed.size)
        // Idempotency keys are unique per executed step: no replay.
        val keys = h.router.records().map { it.key }.toSet()
        assertEquals(2, keys.size)
        assertEquals(2, h.router.stepsDone)
    }

    @Test fun pauseResumeGuards() {
        val h = Harness()
        assertFalse(h.router.pause()) // IDLE: nothing to pause
        assertFalse(h.router.resume()) // not paused
        h.router.start("填表")
        assertFalse(h.router.resume()) // RUNNING is not resumable
        assertTrue(h.router.pause())
        assertFalse(h.router.pause()) // already paused
    }

    @Test fun cancelStopsLoopAndAuditsSideEffects() {
        val h = Harness()
        h.sideEffectSteps = true
        h.router.start("填表")
        h.router.observe(h.observation())

        h.router.cancel()
        assertEquals(SlowState.CANCELLED, h.router.state)
        assertTrue(h.router.cancelledAfterSideEffect)

        val outcome = h.router.observe(h.observation())
        assertTrue(outcome is SlowOutcome.Finished)
        assertEquals(1, h.executed.size) // nothing ran after cancel
    }

    @Test fun cancelBeforeAnyStepHasNoSideEffect() {
        val h = Harness()
        h.router.start("填表")
        h.router.cancel()
        assertEquals(SlowState.CANCELLED, h.router.state)
        assertFalse(h.router.cancelledAfterSideEffect)
    }
}
