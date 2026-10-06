package dev.librepocket.slow

/**
 * Slow-loop state machine (ARCHITECTURE §7, full-only per MATRIX §1).
 *
 * ```text
 * IDLE → RUNNING ⇄ PAUSED → (resume→RUNNING)
 * RUNNING → DONE / CANCELLED / ERROR / WAITING_CONFIRM / MANUAL_GUIDE
 * WAITING_CONFIRM → RUNNING (confirm) / CANCELLED (reject)
 * INTERRUPTED → RUNNING (explicit start) / CANCELLED
 * ```
 */
enum class SlowState {
    IDLE,
    RUNNING,
    PAUSED,
    WAITING_CONFIRM,
    DONE,
    CANCELLED,
    ERROR,
    MANUAL_GUIDE,
    INTERRUPTED,
}

/** Planner hook: propose the next single atomic step (wired to the model later). */
fun interface StepProposer {
    fun propose(goal: String, observation: CompressedScreen): StepProposal?
}

/** Execution result for one atomic step. */
data class StepResult(
    /** Step ran without transport errors. */
    val ok: Boolean,
    /** Goal reached; the loop finishes. */
    val done: Boolean = false,
    val note: String = "",
    /** True when the step changed the outside world (for cancel auditing). */
    val sideEffect: Boolean = false,
)

/** Executor hook: performs one atomic step (full-flavor wiring provides this). */
fun interface StepExecutor {
    fun execute(proposal: StepProposal): StepResult
}

/** Executed step with its idempotency key (prevents replay after resume). */
data class StepRecord(
    val key: String,
    val proposal: StepProposal,
    val result: StepResult,
)

/** Outcome of one serial loop iteration. */
sealed interface SlowOutcome {
    data class Acted(val record: StepRecord, val state: SlowState) : SlowOutcome
    data class AwaitingConfirm(val proposal: StepProposal) : SlowOutcome
    data class Finished(val state: SlowState, val stepsDone: Int) : SlowOutcome
    data class Guided(val guide: ManualGuide) : SlowOutcome
    data class Idle(val state: SlowState) : SlowOutcome
}

/**
 * Single-step-serial GUI loop core (ARCHITECTURE §7 / §9.2). Pure JVM:
 * no platform imports; the proposer/executor are injected seams.
 *
 * Semantics kept here (tested by SlowBudgetTest / SteeringSemanticsTest /
 * PauseResumeTest):
 * - steering is queued and merged as "舊目標 + 新指示" on the next
 *   round; the in-flight atomic step always runs to completion;
 * - pause takes effect at step boundaries only;
 * - resume never replays completed steps (idempotency keys);
 * - cancel is cooperative; [cancelledAfterSideEffect] records whether a
 *   world-changing step already ran.
 */
class SlowRouter(
    private val budget: SlowBudget = SlowBudget(),
    private val timeMs: () -> Long = System::currentTimeMillis,
    private val proposer: StepProposer,
    private val executor: StepExecutor,
) {
    var state: SlowState = SlowState.IDLE
        private set
    var goal: String = ""
        private set
    var stepsDone: Int = 0
        private set

    private val records = mutableListOf<StepRecord>()
    private var pendingSteer: String? = null
    private var pendingProposal: StepProposal? = null
    private var tracker: SlowBudgetTracker? = null
    private var lastError: String? = null

    /** True once a cancelled run is known to have changed outside state. */
    var cancelledAfterSideEffect: Boolean = false
        private set

    fun records(): List<StepRecord> = records.toList()
    fun lastError(): String? = lastError

    /** Explicit start (also the explicit recovery path from INTERRUPTED). */
    fun start(goal: String): SlowState {
        require(goal.isNotBlank()) { "goal must not be blank" }
        this.goal = goal
        this.stepsDone = 0
        this.records.clear()
        this.pendingSteer = null
        this.pendingProposal = null
        this.lastError = null
        this.cancelledAfterSideEffect = false
        this.tracker = SlowBudgetTracker(budget, timeMs())
        this.state = SlowState.RUNNING
        return state
    }

    /**
     * Steering: accepted in RUNNING / PAUSED (and WAITING_CONFIRM, where it
     * discards the pending confirmation and forces a re-plan). The queued
     * instruction merges with the old goal on the next [observe]; the
     * currently executing atomic step is never preempted.
     */
    fun steer(instruction: String): Boolean {
        if (instruction.isBlank()) return false
        when (state) {
            SlowState.RUNNING, SlowState.PAUSED -> {
                pendingSteer = mergeSteer(pendingSteer, instruction)
                return true
            }
            SlowState.WAITING_CONFIRM -> {
                pendingProposal = null
                pendingSteer = mergeSteer(pendingSteer, instruction)
                state = SlowState.RUNNING
                return true
            }
            else -> return false
        }
    }

    /** Pause at the step boundary; never interrupts a step mid-flight. */
    fun pause(): Boolean {
        if (state != SlowState.RUNNING) return false
        state = SlowState.PAUSED
        return true
    }

    /** Resume from PAUSED; completed steps are not replayed. */
    fun resume(): Boolean {
        if (state != SlowState.PAUSED) return false
        state = SlowState.RUNNING
        return true
    }

    /** Cooperative cancel: terminal state; audit flag records side effects. */
    fun cancel(): SlowState {
        when (state) {
            SlowState.RUNNING, SlowState.PAUSED, SlowState.WAITING_CONFIRM -> {
                cancelledAfterSideEffect = records.any { it.result.sideEffect }
                pendingProposal = null
                pendingSteer = null
                state = SlowState.CANCELLED
            }
            else -> Unit
        }
        return state
    }

    /** Crash-recovery marker: RUNNING/PAUSED work becomes INTERRUPTED. */
    fun markInterrupted() {
        if (state == SlowState.RUNNING || state == SlowState.PAUSED) {
            pendingProposal = null
            state = SlowState.INTERRUPTED
        }
    }

    /** Approve the pending proposal and execute it as the next step. */
    fun confirm(): SlowOutcome {
        val proposal = pendingProposal
        if (state != SlowState.WAITING_CONFIRM || proposal == null) {
            return SlowOutcome.Idle(state)
        }
        pendingProposal = null
        state = SlowState.RUNNING
        return runStep(proposal)
    }

    /** Reject the pending proposal: the run is cancelled. */
    fun rejectConfirm(): SlowState {
        if (state == SlowState.WAITING_CONFIRM) {
            pendingProposal = null
            cancelledAfterSideEffect = records.any { it.result.sideEffect }
            state = SlowState.CANCELLED
        }
        return state
    }

    /**
     * One serial iteration: observe → arbitrate → execute at most one
     * step. The production driver calls this per new screen snapshot;
     * tests drive it directly with fakes.
     */
    fun observe(observation: SlowObservation): SlowOutcome {
        when (state) {
            SlowState.PAUSED -> return SlowOutcome.Idle(state)
            SlowState.DONE, SlowState.CANCELLED, SlowState.ERROR,
            SlowState.MANUAL_GUIDE, SlowState.IDLE, SlowState.INTERRUPTED,
            -> return SlowOutcome.Finished(state, stepsDone)
            SlowState.WAITING_CONFIRM -> {
                // A queued steer re-plans instead of waiting (see steer()).
                val pending = pendingProposal ?: return SlowOutcome.Idle(state)
                return SlowOutcome.AwaitingConfirm(pending)
            }
            SlowState.RUNNING -> Unit
        }

        // Steering merges before planning: old goal + new instruction.
        pendingSteer?.let {
            goal = "$goal\n補充指示：$it"
            pendingSteer = null
        }

        val tracker = tracker ?: SlowBudgetTracker(budget, timeMs()).also { this.tracker = it }
        val stop = tracker.checkBeforeStep(timeMs(), stepsDone)
        if (stop != null) {
            state = SlowState.MANUAL_GUIDE
            return SlowOutcome.Guided(tracker.toManualGuide(stop, goal, stepsDone))
        }

        val compressed = observation.compress()
        val proposal = proposer.propose(goal, compressed)
        if (proposal == null) {
            state = SlowState.DONE
            return SlowOutcome.Finished(state, stepsDone)
        }
        if (SlowArbitrator.assess(goal, proposal) == SlowRisk.NEEDS_CONFIRM) {
            pendingProposal = proposal
            state = SlowState.WAITING_CONFIRM
            return SlowOutcome.AwaitingConfirm(proposal)
        }
        return runStep(proposal)
    }

    private fun runStep(proposal: StepProposal): SlowOutcome {
        val result = executor.execute(proposal)
        val record = StepRecord(key = "slow-step-$stepsDone", proposal = proposal, result = result)
        records.add(record)
        stepsDone++
        if (!result.ok) {
            lastError = result.note.ifEmpty { "step failed" }
            state = SlowState.ERROR
            return SlowOutcome.Finished(state, stepsDone)
        }
        if (result.done) {
            state = SlowState.DONE
            return SlowOutcome.Finished(state, stepsDone)
        }
        return SlowOutcome.Acted(record, state)
    }

    private fun mergeSteer(existing: String?, instruction: String): String {
        val clean = instruction.trim()
        return if (existing.isNullOrBlank()) clean else "$existing；$clean"
    }
}
