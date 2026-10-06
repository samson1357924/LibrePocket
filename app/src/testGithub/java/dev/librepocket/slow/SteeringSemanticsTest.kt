package dev.librepocket.slow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SteeringSemanticsTest (BACKLOG B9 / ARCHITECTURE §7.2): steering is
 * accepted in RUNNING/PAUSED, merges as old goal + new instruction on
 * the next round, and never preempts the in-flight atomic step.
 */
class SteeringSemanticsTest {

    private class Harness {
        val seenGoals = mutableListOf<String>()
        val executed = mutableListOf<StepProposal>()
        var now = 0L
        val router = SlowRouter(
            budget = SlowBudget(maxSteps = 10, maxTimeMs = 60_000L),
            timeMs = { now },
            proposer = StepProposer { goal, _ ->
                seenGoals.add(goal)
                StepProposal(SlowAction.Tap("n$seenGoals"), targetDescription = "下一步")
            },
            executor = StepExecutor { proposal ->
                executed.add(proposal)
                StepResult(ok = true)
            },
        )

        fun observation(): SlowObservation = SlowObservation(
            nodes = listOf(UiNode("n", "button", text = "下一步", clickable = true)),
        )
    }

    @Test fun steerMergesOnNextRoundWithoutPreempting() {
        val h = Harness()
        h.router.start("買咖啡")
        h.router.observe(h.observation())
        assertEquals(1, h.executed.size)

        assertTrue(h.router.steer("改成大杯"))
        // Queued only: nothing re-executes until the next round.
        assertEquals(1, h.executed.size)

        h.router.observe(h.observation())
        assertEquals(2, h.executed.size)
        val secondGoal = h.seenGoals[1]
        assertTrue(secondGoal.contains("買咖啡"))
        assertTrue(secondGoal.contains("改成大杯"))
        // First step ran with the original goal (not preempted).
        assertEquals("買咖啡", h.seenGoals[0])
    }

    @Test fun steerAcceptedWhilePaused() {
        val h = Harness()
        h.router.start("買咖啡")
        assertTrue(h.router.pause())
        assertTrue(h.router.steer("改成去冰"))

        assertTrue(h.router.resume())
        h.router.observe(h.observation())
        assertTrue(h.seenGoals.last().contains("改成去冰"))
    }

    @Test fun steerRejectedInTerminalStates() {
        val h = Harness()
        assertFalse(h.router.steer("太早")) // IDLE
        h.router.start("買咖啡")
        h.router.cancel()
        assertFalse(h.router.steer("太晚"))
        assertEquals(SlowState.CANCELLED, h.router.state)
    }

    @Test fun steerWhileWaitingConfirmReplans() {
        val seen = mutableListOf<String>()
        val router = SlowRouter(
            budget = SlowBudget(maxSteps = 10, maxTimeMs = 60_000L),
            proposer = StepProposer { goal, _ ->
                seen.add(goal)
                StepProposal(
                    SlowAction.Tap("pay"),
                    targetDescription = "支付訂單",
                    rationale = "需要支付",
                )
            },
            executor = StepExecutor { StepResult(ok = true) },
        )
        router.start("結帳")
        val obs = SlowObservation(
            nodes = listOf(UiNode("pay", "button", text = "支付", clickable = true)),
        )
        val first = router.observe(obs)
        assertTrue(first is SlowOutcome.AwaitingConfirm)

        assertTrue(router.steer("先不要付，看看明細"))
        // Re-plan: pending confirmation discarded, loop back to RUNNING.
        assertEquals(SlowState.RUNNING, router.state)
        router.observe(obs)
        assertTrue(seen.last().contains("先不要付"))
    }
}
