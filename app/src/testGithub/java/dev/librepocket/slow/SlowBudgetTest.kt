package dev.librepocket.slow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SlowBudgetTest (BACKLOG B9): step/time dual budget trips the loop and
 * downgrades to a manual guide carrying the reason code.
 *
 * NOTE: goals here are deliberately benign ("整理書籤"): the in-core
 * arbitrator flags payment-like goals (e.g. 結帳) to WAITING_CONFIRM
 * before any step runs, which would mask the budget logic under test.
 */
class SlowBudgetTest {

    private fun tapRouter(
        budget: SlowBudget,
        now: () -> Long = { 0L },
    ): SlowRouter = SlowRouter(
        budget = budget,
        timeMs = now,
        proposer = StepProposer { _, _ ->
            StepProposal(SlowAction.Tap("ok-btn"), targetDescription = "確認按鈕")
        },
        executor = StepExecutor { StepResult(ok = true) },
    )

    private fun observation(): SlowObservation = SlowObservation(
        appPackage = "com.example.shop",
        nodes = listOf(UiNode("ok-btn", "button", text = "確認", clickable = true)),
    )

    @Test fun stepBudgetExceededTurnsToGuide() {
        val router = tapRouter(SlowBudget(maxSteps = 2, maxTimeMs = 60_000L))
        router.start("整理書籤")

        router.observe(observation())
        router.observe(observation())
        val outcome = router.observe(observation())

        assertTrue(outcome is SlowOutcome.Guided)
        val guide = (outcome as SlowOutcome.Guided).guide
        assertEquals(SlowStopReason.STEP_BUDGET_EXCEEDED.name, guide.reasonCode)
        assertEquals(2, guide.stepsDone)
        assertTrue(guide.text.contains(SlowStopReason.STEP_BUDGET_EXCEEDED.name))
        assertTrue(guide.text.contains("需要你"))
        assertEquals(SlowState.MANUAL_GUIDE, router.state)
    }

    @Test fun stepBudgetBoundaryAllowsExactlyMaxSteps() {
        val router = tapRouter(SlowBudget(maxSteps = 1, maxTimeMs = 60_000L))
        router.start("整理書籤")

        val first = router.observe(observation())
        assertTrue(first is SlowOutcome.Acted)
        val second = router.observe(observation())
        assertTrue(second is SlowOutcome.Guided)
    }

    @Test fun timeBudgetExceededTurnsToGuide() {
        var now = 1_000L
        val router = tapRouter(SlowBudget(maxSteps = 20, maxTimeMs = 5_000L), now = { now })
        router.start("整理書籤")

        val first = router.observe(observation())
        assertTrue(first is SlowOutcome.Acted)

        now += 10_000L
        val outcome = router.observe(observation())

        assertTrue(outcome is SlowOutcome.Guided)
        val guide = (outcome as SlowOutcome.Guided).guide
        assertEquals(SlowStopReason.TIME_BUDGET_EXCEEDED.name, guide.reasonCode)
        assertTrue(guide.text.contains(SlowStopReason.TIME_BUDGET_EXCEEDED.name))
        assertEquals(SlowState.MANUAL_GUIDE, router.state)
    }

    @Test fun guideIsTerminal() {
        val router = tapRouter(SlowBudget(maxSteps = 1, maxTimeMs = 60_000L))
        router.start("整理書籤")
        router.observe(observation())
        router.observe(observation())

        val again = router.observe(observation())
        assertTrue(again is SlowOutcome.Finished)
        assertEquals(SlowState.MANUAL_GUIDE, (again as SlowOutcome.Finished).state)
    }
}
