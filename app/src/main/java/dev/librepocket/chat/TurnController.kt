package dev.librepocket.chat

import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatImage
import dev.librepocket.provider.ChatMessage
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderFailure
import dev.librepocket.provider.ProviderFailureCode
import dev.librepocket.provider.StreamEvent
import dev.librepocket.provider.requireToolAggBudget
import dev.librepocket.redact.Redactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * The sole retry budget for one logical turn (at most 3 retries / 4 requests).
 * Provider adapters make one classified attempt and never multiply this budget.
 */
data class TurnRetryConfig(
  val maxRetries: Int = 3,
  val retryDelaysMs: List<Long> = listOf(2_000L, 4_000L, 8_000L),
) {
  init {
    require(maxRetries in 0..3) { "a turn may make at most four provider attempts" }
  }

  /** 1-based retry index → delay. Extra retry indices reuse the last delay. */
  fun delayForRetry(retryIndex: Int): Long =
    retryDelaysMs.getOrElse(retryIndex - 1) { retryDelaysMs.lastOrNull() ?: 0L }
}

private data class PendingSteer(val opId: Long?, val text: String, val images: List<ChatImageRef>)

/** Explicit acknowledgment for [TurnController.startOrEnqueue]. */
sealed interface TurnStart {
  /** The text was accepted: gate passed, appended, turn running. Join it. */
  data class Started(val host: Job) : TurnStart

  /** A turn was already in flight: the text is queued FIFO for next round. */
  data object Queued : TurnStart

  /**
   * The controller is idle but has intents left by a denied or failed
   * promotion policy check. They
   * have been atomically removed from the FIFO for caller recovery; the
   * current admission is not accepted. In the second-lock branch it may
   * already have passed one fresh policy gate; retrying after recovery
   * evaluates policy again.
   */
  data class NeedsRecovery(val queuedIntents: List<QueuedIntent>) : TurnStart
}

/** Fresh chat.send policy evaluation failed; the admission was not accepted. */
class PolicyEvaluationException(cause: Exception) :
  Exception("chat.send policy evaluation failed", cause)

/** Fresh chat.send policy requires approval; this build has no interactive consent flow. */
class ApprovalRequiredException : Exception("chat.send approval required")

/**
 * Outgoing context window for one [ChatRequest] (Current, minimal).
 *
 * Mirrors the ledger branch's `HistoryWindowCap` shape (kept identical on
 * purpose so the pending merge converges): oldest-first truncation at
 * whole-message boundaries. Tool-pair integrity is structural — tool calls
 * ride inline in their assistant block (`[tool:name args]` marker text, never
 * separate provider `tool` messages) — so whole-message truncation can never
 * detach a tool result from its call. Any truncation is observable via
 * [TurnController.droppedHistoryCount], never silent.
 *
 * The default is [Unbounded] (window everything, truncate nothing): no
 * on-device token/context measurement exists in this branch to justify a
 * tighter default (TODO #16: measure real session sizes before fixing a
 * number). [MaxMessages]/[MaxChars] exist so callers and tests can lock the
 * truncation behavior today.
 */
sealed interface HistoryWindowCap {
  data object Unbounded : HistoryWindowCap
  data class MaxMessages(val maxMessages: Int) : HistoryWindowCap {
    init {
      require(maxMessages > 0) { "maxMessages must be positive" }
    }
  }
  data class MaxChars(val maxChars: Int) : HistoryWindowCap {
    init {
      require(maxChars > 0) { "maxChars must be positive" }
    }
  }
}

/**
 * Oldest-first, whole-message truncation for an outgoing request copy.
 * Whole messages only (tool-pair integrity is structural, see above). A
 * single newest message that already exceeds a char budget is still kept
 * whole — truncation drops messages, never splits one — so at least the
 * newest message always survives.
 */
internal fun applyHistoryCap(history: List<ChatMessage>, cap: HistoryWindowCap): List<ChatMessage> =
  when (cap) {
    is HistoryWindowCap.Unbounded -> history
    is HistoryWindowCap.MaxMessages -> history.takeLast(cap.maxMessages)
    is HistoryWindowCap.MaxChars -> {
      var kept = 0
      var chars = 0
      for (m in history.asReversed()) {
        if (kept > 0 && chars + m.text.length > cap.maxChars) break
        chars += m.text.length
        kept++
      }
      history.takeLast(kept)
    }
  }

/** A direct admission cannot overtake queued work that still needs explicit recovery. */
class RecoveryRequiredException : IllegalStateException("queued turn recovery required before admission")

/**
 * Turn controller: per-turn [Flow] collection + retry orchestration +
 * single-flight guard + steer queue + cooperative cancel.
 *
 * The controller consumes the unified [StreamEvent] union from [LlmProvider]
 * directly (no M3-local stub): [StreamEvent.TextDelta] and
 * [StreamEvent.ReasoningDelta] extend the current assistant block,
 * [StreamEvent.ToolDelta]/[StreamEvent.ToolDone] are aggregated and recorded
 * (marker text + [TranscriptSink.onToolDone]) so tool calls are never dropped,
 * [StreamEvent.Usage] is recorded ([TranscriptSink.onUsage] + [lastUsage]).
 * Retry ownership is here: each retryable terminal failure gets a new runId
 * and isolated assistant/tool aggregation, with at most four provider attempts
 * per logical turn.
 *
 * SCAFFOLD (PR#1 re-review, P1 scope): this PR records ToolDone only and never
 * executes tools — no ToolDispatcher/FastRouter/PrivilegeGate/ElevatedDispatch
 * product wiring yet. Projection NATIVE means switch/flavor semantics only;
 * model visibility additionally requires `ToolDef.executionReady` (see
 * [ToolRegistry.visibleTools], the ONLY list that may be sent to the model),
 * which is false for all 41 tools in this PR.
 * The true product tool loop (dispatcher + grant/confirm + Android executors +
 * E2E) lands in the wiring PR, which flips tools to ready one by one.
 *
 * Execution-basis policy: every turn (including steered follow-ups) is gated
 * by [PolicyStore.evaluateFresh]. [PolicyStore.evaluate] is UI pre-display
 * only and is never used here.
 *
 * Cancel is cooperative via the collecting coroutine's [Job] (the [LlmProvider]
 * contract): state flips to [ChatStatus.CANCELLED] first so the UI stops even
 * if the transport close blocks briefly, then the in-flight job is cancelled,
 * which cancels the underlying HTTP call promptly. No IO on this path.
 *
 * Retry: each attempt gets a fresh runId; failed partial output is kept with
 * `isPartial=true` and the next attempt starts a new assistant block instead
 * of backfilling the old one.
 *
 * Aggregation budget: ToolDelta fragments (args/id/name) for one tool index
 * share one memory budget (see `MAX_TOOL_CALL_AGG_BYTES`); exceeding it ends
 * the attempt with typed non-retryable `TOOL_ARGS_TOO_LARGE` (never silent
 * truncation, never a retry of the poisoned stream).
 *
 * UI update cost: every visible delta still performs one UI update (streaming
 * visibility and cancel snapshots depend on it), so no batching cadence is
 * claimed. The cost is observable via [streamedTextDeltaCount],
 * [uiTextUpdateCount] and [uiTextCopiedChars] as the measured baseline for a
 * future throttle (TODO #16: measure on-device first; no unmeasured
 * threshold is hardcoded here).
 *
 * Context window: the outgoing request carries [historyCap] (default
 * Unbounded — zero behavior change); truncation is oldest-first,
 * whole-message, and counted on [droppedHistoryCount].
 *
 * Ephemeral runtime time context (Phase 2): every outgoing [ChatRequest]
 * carries a [buildRuntimeTimeContext] block appended to a **copy** of the last
 * user message. The block never touches [_uiState] (no [UiMessage] residue)
 * and never flows through [TranscriptSink] (transcript keeps the raw user
 * text). Unknown time is still sent as `(time unknown)` so the model knows
 * the clock is unavailable instead of hallucinating one; fallback/unknown
 * only emits `Log.w` and never blocks the turn.
 *
 * @param clock clock used to stamp the ephemeral time block.
 * @param userTimezone IANA id preferred for the time block; blank/invalid
 *   falls back to the system zone (fail-closed, never throws).
 * @param sessionStart session creation instant for the `Session started`
 *   line; null omits the line. Callers should pass the session creation time.
 * @param systemZone supplier re-read every turn for the ephemeral time block
 *   when [userTimezone] is blank (defaults to `ZoneId.systemDefault`, so a
 *   mid-session system timezone change is picked up on the next turn instead
 *   of reusing the [clock] construction-time snapshot).
 */
class TurnController(
  private val provider: LlmProvider,
  private val policy: PolicyStore,
  private val transcript: TranscriptSink = NoOpTranscriptSink(),
  private val retryConfig: TurnRetryConfig = TurnRetryConfig(),
  dispatcher: CoroutineDispatcher = Dispatchers.Default,
  private val sleeper: suspend (Long) -> Unit = { delay(it) },
  private val newId: () -> String = { UUID.randomUUID().toString() },
  private val model: String = DEFAULT_MODEL,
  private val imageLoader: (List<ChatImageRef>) -> List<ChatImage> = ::defaultChatImageLoader,
  private val clock: Clock = Clock.systemDefaultZone(),
  private val userTimezone: String? = null,
  private val sessionStart: Instant? = null,
  private val systemZone: () -> ZoneId = ZoneId::systemDefault,
  private val historyCap: HistoryWindowCap = HistoryWindowCap.Unbounded,
) {
  companion object {
    const val POLICY_ACTION = "chat.send"
    const val POLICY_RESOURCE = "chat"
    const val DEFAULT_MODEL = "default"

    /** Single-image byte cap (spec §4: >10 MiB is rejected, never transcoded). */
    const val MAX_IMAGE_BYTES = 10 * 1024 * 1024L

    /** Spec §4: at most 4 images per turn; extras are omitted. */
    const val MAX_IMAGES_PER_TURN = 4
  }

  init {
    require(model.isNotBlank()) { "model must not be blank" }
  }

  private val lock = Any()
  private val scope = CoroutineScope(SupervisorJob() + dispatcher)
  private val _uiState = MutableStateFlow(
    ChatUiState(messages = emptyList(), status = ChatStatus.IDLE, pendingSteerCount = 0, error = null),
  )
  val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

  /** Last usage reported by the provider (billing/context accounting; P1 records only). */
  var lastUsage: StreamEvent.Usage? = null
    private set

  /**
   * Messages dropped from the last outgoing request by [historyCap] (0 when
   * [HistoryWindowCap.Unbounded]). The observable counterpart to context
   * truncation: the request copy never silently loses history. Updated on
   * every [buildRequest]; the UI list and transcript keep everything.
   */
  var droppedHistoryCount: Int = 0
    private set

  /**
   * Visible-text deltas collected this controller lifetime (text + reasoning).
   * Together with [uiTextUpdateCount]/[uiTextCopiedChars] this makes the
   * per-delta UI-update cost observable for the future throttle decision
   * (TODO #16): today every delta still performs one UI update (streaming
   * visibility and the cancel-before-flush snapshot depend on it — see
   * `cancelStopsStreamFastAndKeepsPartial`), so no batching cadence is
   * claimed here.
   *
   * TODO(#16): measure delta inter-arrival rates and Compose frame times on
   * a mid-range device across representative sessions FIRST, then introduce a
   * count- or time-based flush throttle justified by those numbers. An
   * unmeasured threshold must not be hardcoded; until it lands these counters
   * are the observable baseline (a throttle must reduce [uiTextUpdateCount]
   * and [uiTextCopiedChars] while keeping cancellation snapshots exact).
   */
  var streamedTextDeltaCount: Int = 0
    private set

  /** UI text writes performed (one per non-empty visible write; tool markers included). */
  var uiTextUpdateCount: Int = 0
    private set

  /**
   * Chars copied by immutable UI text updates (prior block length + delta
   * length per write, tool markers included). This is the quadratic term a
   * future throttle must cut; exact under the controller's single-writer
   * discipline.
   */
  var uiTextCopiedChars: Long = 0
    private set

  private var inFlight: Job? = null
  private val steerQueue: ArrayDeque<PendingSteer> = ArrayDeque()
  private var closed = false
  /** Monotonic under [lock]; invalidates fresh-gate failures after turn activity. */
  private var activityRevision = 0L

  /** Test seam between an idle admission snapshot and its fresh policy gate. */
  internal var beforeAdmissionGateForTest: (() -> Unit)? = null

  /** Test seam for racing an idle legacy steer with new recovery work. */
  internal var afterIdleSteerCheckForTest: (() -> Unit)? = null

  /** Test seam for pausing after follow-up policy approval, before handoff. */
  internal var afterFollowUpGateForTest: (() -> Unit)? = null

  /** Test seam for pausing after follow-up policy denial, before settlement. */
  internal var afterFollowUpDenyForTest: (() -> Unit)? = null

  /** Test seam for arranging a queue admission immediately before completion checks it. */
  internal var beforeCompletionQueueCheckForTest: (() -> Unit)? = null

  /** Test seam for pausing startOrEnqueue after its fresh gate and before its second lock. */
  internal var afterStartOrEnqueueGateForTest: (() -> Unit)? = null

  /** Test seam for pausing startTurn after its fresh gate and before its second lock. */
  internal var afterStartTurnGateForTest: (() -> Unit)? = null

  /** Returns the live in-flight job, dropping (and clearing) completed ones. */
  private fun activeLocked(): Job? {
    val current = inFlight ?: return null
    if (current.isCompleted) {
      inFlight = null
      return null
    }
    return current
  }

  suspend fun send(text: String, images: List<ChatImageRef> = emptyList()) {
    val host = startTurn(text, images)
    try {
      host.join()
    } catch (e: CancellationException) {
      // Our own turn was cancelled via cancel()/close(): state already flipped.
      // Only propagate if the *caller* itself was cancelled.
      if (coroutineContext[Job]?.isCancelled == true) throw e
    }
  }

  /**
   * Start a turn and return once the text is accepted (fresh `chat.send`
   * policy passed, user message appended, STREAMING) without waiting for the
   * hosted turn to finish. Callers that must retain an unsent draft across
   * endpoint invalidation (R1) clear it only after this returns; a
   * [CancellationException]/[SecurityException]/[IllegalStateException] before
   * return means the text was NOT accepted and must stay recoverable. A
   * [RecoveryRequiredException] means queued recovery work must be drained
   * before this direct admission can proceed; this method never drains it. If
   * recovery appears after the initial admission lock, this attempt may
   * already have passed a fresh policy gate; retrying evaluates policy again.
   */
  suspend fun startTurn(text: String, images: List<ChatImageRef> = emptyList()): Job {
    require(text.isNotBlank()) { "text must not be blank" }
    val revisionAtAdmission = synchronized(lock) {
      check(!closed) { "controller is closed" }
      if (activeLocked() != null) {
        _uiState.update { it.copy(error = "busy: another turn is in flight") }
        throw IllegalStateException("already in flight")
      }
      checkNoQueuedRecoveryLocked()
      activityRevision
    }
    beforeAdmissionGateForTest?.invoke()
    gateOrThrow(revisionAtAdmission)
    afterStartTurnGateForTest?.invoke()
    val host = synchronized(lock) {
      check(!closed) { "controller is closed" }
      if (activeLocked() != null) {
        _uiState.update { it.copy(error = "busy: another turn is in flight") }
        throw IllegalStateException("already in flight")
      }
      checkNoQueuedRecoveryLocked()
      activityRevision++
      appendUser(text)
      _uiState.update {
        it.copy(status = ChatStatus.STREAMING, error = null, queuedRecoveryRequired = false)
      }
      val job = scope.launch { hostedTurn(text, images) }
      inFlight = job
      job.invokeOnCompletion {
        synchronized(lock) {
          if (inFlight === job) inFlight = null
        }
      }
      job
    }
    return host
  }

  fun cancel() {
    synchronized(lock) {
      val current = activeLocked() ?: return
      activityRevision++
      _uiState.update {
        it.copy(
          status = ChatStatus.CANCELLED,
          pendingSteerCount = steerQueue.size,
          queuedRecoveryRequired = steerQueue.isNotEmpty(),
        )
      }
      // Cooperative: cancelling collection cancels the underlying HTTP call.
      current.cancel()
    }
    // No join, no IO: return immediately so the UI stops within 200ms.
  }

  /**
   * Atomic start-or-enqueue: the busy check and the FIFO insert happen under
   * the same lock, so the decision is never stale. Returns [TurnStart.Started]
   * once the text is accepted (fresh `chat.send` passed, user message
   * appended) without waiting for the turn to finish, [TurnStart.Queued]
   * when a turn was already in flight, or [TurnStart.NeedsRecovery] when an
   * idle controller still has promotion-gate-failed intents to return to the
   * caller before it retries this admission. A [CancellationException]/[SecurityException]/
   * [IllegalStateException] before return means NOT accepted. Unlike
   * [steer], the idle path never fire-and-forgets: callers always get an
   * explicit acknowledgment.
   *
   * N1 ownership: [TurnStart.Queued] is NOT acceptance (no gate, no append).
   * [TurnStart.NeedsRecovery] also does not accept the current text; its
   * returned queue has been atomically drained and must be surfaced for
   * explicit user recovery. In the second-lock branch, this admission may
   * already have passed one fresh policy gate; retrying after recovery runs
   * the policy gate again.
   * An admission that finds a turn active (either at the initial lock or
   * after losing the post-gate race) can queue text only. Non-empty [images]
   * throws [IllegalStateException] without adding a FIFO entry; the caller
   * retains ownership of both text and images and may explicitly retry later.
   * The caller keeps owning the text — pass [opId] so endpoint teardown can
   * reclaim it via [drainQueued] instead of losing it in [close]. Queued
   * intents are never auto-resent to a new endpoint; draining only surfaces
   * them for explicit user recovery.
   */
  suspend fun startOrEnqueue(
    text: String,
    images: List<ChatImageRef> = emptyList(),
    opId: Long? = null,
  ): TurnStart {
    require(text.isNotBlank()) { "text must not be blank" }
    val revisionAtAdmission = synchronized(lock) {
      check(!closed) { "controller is closed" }
      if (activeLocked() != null) {
        check(images.isEmpty()) { "cannot queue a turn with images; text and images remain caller-owned" }
        enqueueLocked(opId, text, images)
        return TurnStart.Queued
      }
      drainRecoveryLocked()?.let { return TurnStart.NeedsRecovery(it) }
      activityRevision
    }
    beforeAdmissionGateForTest?.invoke()
    gateOrThrow(revisionAtAdmission)
    afterStartOrEnqueueGateForTest?.invoke()
    val host = synchronized(lock) {
      check(!closed) { "controller is closed" }
      if (activeLocked() != null) {
        // Lost the race during the gate: queue instead of throwing, so the
        // caller still gets an explicit acceptance signal. Image turns are
        // the exception: QueuedIntent cannot preserve their image refs.
        check(images.isEmpty()) { "cannot queue a turn with images; text and images remain caller-owned" }
        enqueueLocked(opId, text, images)
        return TurnStart.Queued
      }
      drainRecoveryLocked()?.let { return TurnStart.NeedsRecovery(it) }
      activityRevision++
      appendUser(text, opId)
      _uiState.update {
        it.copy(status = ChatStatus.STREAMING, error = null, queuedRecoveryRequired = false)
      }
      val job = scope.launch { hostedTurn(text, images) }
      inFlight = job
      job.invokeOnCompletion {
        synchronized(lock) {
          if (inFlight === job) inFlight = null
        }
      }
      job
    }
    return TurnStart.Started(host)
  }

  /**
   * Queue behind an active turn, or start asynchronously when idle. An idle
   * call that finds retained recovery work throws [RecoveryRequiredException]
   * synchronously. If recovery wins after the idle check, this legacy intent
   * is retained for recovery and the UI exposes the failure instead of
   * silently dropping it or promoting it automatically.
   */
  fun steer(text: String) {
    require(text.isNotBlank()) { "text must not be blank" }
    synchronized(lock) {
      check(!closed) { "controller is closed" }
      if (activeLocked() != null) {
        // Never cancel the current turn: queue for the next round (FIFO).
        enqueueLocked(null, text, emptyList())
        return
      }
      if (steerQueue.isNotEmpty()) {
        // Keep the legacy synchronous recovery signal, but do not make its
        // caller's new text disappear behind the already-retained FIFO.
        retainSteerForRecoveryLocked(text)
        throw RecoveryRequiredException()
      }
    }
    afterIdleSteerCheckForTest?.invoke()
    // Idle: use atomic admission so a turn that wins this race queues the
    // instruction instead of making a stale busy check reject and lose it.
    scope.launch {
      try {
        when (val admission = startOrEnqueue(text, opId = null)) {
          is TurnStart.Started, TurnStart.Queued -> Unit
          is TurnStart.NeedsRecovery -> {
            // Put the transferred older FIFO back first, then retain this
            // unaccepted legacy intent. Recovery-owned work is never promoted.
            retainSteerForRecovery(text, recovered = admission.queuedIntents)
          }
        }
      } catch (_: SecurityException) {
        retainSteerForRecovery(text, error = "denied by policy")
      } catch (_: ApprovalRequiredException) {
        retainSteerForRecovery(text, error = "approval required")
      } catch (_: PolicyEvaluationException) {
        retainSteerForRecovery(text, error = "policy evaluation failed")
      } catch (_: CancellationException) {
        // close() may discard intents by lifecycle contract. Other admission
        // cancellation must leave the unaccepted text recoverable.
        retainSteerForRecovery(text, error = "turn admission cancelled")
      } catch (_: Exception) {
        // Never silently swallow an unaccepted intent. Closed controllers are
        // intentionally ignored by retainSteerForRecovery().
        retainSteerForRecovery(text, error = "turn admission failed")
      }
    }
  }

  fun close() {
    // Drop the FIFO under the same lock that seals the controller, so no
    // admission can slip in after the seal. Reclaim-then-seal ordering across
    // threads is the caller's job: the ViewModel drains-then-closes with no
    // suspension on its Main-confined path, so nothing slips between them
    // there. Callers that must keep unstarted work (endpoint-switch recovery)
    // drain it explicitly via drainQueued() BEFORE close().
    synchronized(lock) {
      if (closed) return
      closed = true
      activityRevision++
      steerQueue.clear()
      val current = activeLocked()
      _uiState.update {
        it.copy(
          pendingSteerCount = 0,
          status = if (current != null) ChatStatus.CANCELLED else it.status,
          queuedRecoveryRequired = false,
        )
      }
      current?.cancel()
    }
    scope.cancel()
  }

  // ---- internals ----

  /** Caller must hold [lock]. Direct starts cannot take ownership of recovery FIFO. */
  private fun checkNoQueuedRecoveryLocked() {
    if (steerQueue.isNotEmpty()) throw RecoveryRequiredException()
  }

  /** Retain an unaccepted steer and any transferred FIFO for explicit recovery. */
  private fun retainSteerForRecovery(
    text: String,
    error: String? = null,
    recovered: List<QueuedIntent> = emptyList(),
  ) {
    synchronized(lock) {
      if (closed) return
      retainSteerForRecoveryLocked(text, error, recovered)
    }
  }

  /** Caller must hold [lock]. Transferred intents stay ahead of existing/new work. */
  private fun retainSteerForRecoveryLocked(
    text: String,
    error: String? = null,
    recovered: List<QueuedIntent> = emptyList(),
  ) {
    recovered.asReversed().forEach { intent ->
      steerQueue.addFirst(PendingSteer(intent.opId, intent.text, emptyList()))
    }
    enqueueLocked(null, text, emptyList())
    val active = activeLocked() != null
    _uiState.update { state ->
      val mayProjectError = !active && state.status in setOf(ChatStatus.IDLE, ChatStatus.ERROR)
      state.copy(
        status = if (mayProjectError) ChatStatus.ERROR else state.status,
        error = if (!mayProjectError) state.error else if (state.queuedRecoveryRequired && state.error != null) {
          state.error
        } else {
          error ?: state.error ?: "queued turn recovery required before admission"
        },
        pendingSteerCount = steerQueue.size,
        queuedRecoveryRequired = true,
      )
    }
  }

  /** Caller must hold [lock]. Enqueues a follow-up and projects the count. */
  private fun enqueueLocked(opId: Long?, text: String, images: List<ChatImageRef>) {
    activityRevision++
    steerQueue.addLast(PendingSteer(opId, text, images))
    _uiState.update {
      it.copy(
        pendingSteerCount = steerQueue.size,
        // A cancelled-but-not-yet-completed host still owns the single-flight
        // slot. Sends in that window queue normally, but cannot be promoted.
        queuedRecoveryRequired = it.queuedRecoveryRequired || it.status == ChatStatus.CANCELLED,
      )
    }
    fireTranscript { transcript.onSteerQueued(text) }
  }

  /** Caller must hold [lock]; idle residual FIFO is transferred atomically. */
  private fun drainRecoveryLocked(): List<QueuedIntent>? {
    if (steerQueue.isEmpty()) return null
    val out = steerQueue.map { QueuedIntent(it.opId, it.text) }
    activityRevision++
    steerQueue.clear()
    _uiState.update { it.copy(pendingSteerCount = 0, queuedRecoveryRequired = false) }
    return out
  }

  /**
   * Reclaim queued-but-unstarted intents FIFO (N1). Returns the intents in
   * queue order and clears the FIFO without starting anything. The caller
   * owns them afterwards: surface for explicit user recovery, never
   * auto-resend. Safe to call on a closed controller (drains the remainder).
   */
  fun drainQueued(): List<QueuedIntent> {
    synchronized(lock) {
      if (steerQueue.isEmpty()) return emptyList()
      val out = steerQueue.map { QueuedIntent(it.opId, it.text) }
      activityRevision++
      steerQueue.clear()
      _uiState.update { it.copy(pendingSteerCount = 0, queuedRecoveryRequired = false) }
      return out
    }
  }

  private suspend fun gateOrThrow(revisionAtAdmission: Long) {
    val decision = try {
      policy.evaluateFresh(POLICY_ACTION, POLICY_RESOURCE)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      projectIdleGateFailure("policy evaluation failed", revisionAtAdmission)
      throw PolicyEvaluationException(e)
    }
    if (decision.verdict == Verdict.DENY) {
      projectIdleGateFailure("denied by policy", revisionAtAdmission)
      throw SecurityException("chat.send denied by policy")
    }
    if (decision.verdict == Verdict.ASK) {
      projectIdleGateFailure("approval required", revisionAtAdmission)
      throw ApprovalRequiredException()
    }
  }

  /** A stale gate result must not overwrite any controller activity since admission began. */
  private fun projectIdleGateFailure(error: String, revisionAtAdmission: Long) {
    synchronized(lock) {
      val status = _uiState.value.status
      if (
        closed || activityRevision != revisionAtAdmission || activeLocked() != null ||
        status == ChatStatus.CANCELLED || (status != ChatStatus.IDLE && status != ChatStatus.ERROR)
      ) return
      activityRevision++
      _uiState.update {
        val hasQueuedRecovery = steerQueue.isNotEmpty()
        it.copy(
          status = ChatStatus.ERROR,
          // A late fresh-gate result must not replace the promotion failure
          // that explains why this already-retained FIFO needs recovery.
          error = if (hasQueuedRecovery && it.queuedRecoveryRequired && it.error != null) it.error else error,
          pendingSteerCount = steerQueue.size,
          queuedRecoveryRequired = hasQueuedRecovery,
        )
      }
    }
  }

  private fun fireTranscript(block: suspend () -> Unit) {
    scope.launch {
      try {
        block()
      } catch (_: Exception) {
        // Best effort: persistence must never break the chat loop.
      }
    }
  }

  private suspend fun hostedTurn(text: String, images: List<ChatImageRef>) {
    val self = coroutineContext[Job]
    try {
      runTurnLoop(text, images)
    } catch (e: CancellationException) {
      // Snapshot before async hop: latest* reads uiState at execution time,
      // which may have moved on by the time the launched block runs.
      val cancelId = latestAssistantId()
      val cancelText = latestAssistantText()
      fireTranscript { transcript.onTurnCancelled(cancelId, cancelText) }
      throw e
    }
    // Normal completion only: hand off at most one queued steer as a detached
    // follow-up turn, so send() returns after its own turn. The follow-up
    // gate runs BEFORE dequeue, so a deny leaves the queue intact (still
    // reclaimable via drainQueued). The dequeue and complete follow-up handoff
    // then share the teardown lock, so the intent is either drained or fully
    // accepted; it cannot be stranded between the queue and transcript.
    beforeCompletionQueueCheckForTest?.invoke()
    val hasQueued = synchronized(lock) {
      if (closed || self?.isActive != true) {
        if (inFlight === self) inFlight = null
        false
      } else if (steerQueue.isEmpty()) {
        activityRevision++
        if (inFlight === self) inFlight = null
        _uiState.update { s ->
          if (s.status == ChatStatus.STREAMING || s.status == ChatStatus.WAITING_STEERED) {
            s.copy(status = ChatStatus.IDLE)
          } else {
            s
          }
        }
        false
      } else if (_uiState.value.queuedRecoveryRequired) {
        // Recovery-owned FIFO must not be auto-promoted, including a legacy
        // steer retained after its idle admission raced with recovery.
        activityRevision++
        if (inFlight === self) inFlight = null
        _uiState.update { state ->
          val status = if (state.status == ChatStatus.STREAMING || state.status == ChatStatus.WAITING_STEERED) {
            ChatStatus.ERROR
          } else {
            state.status
          }
          state.copy(
            status = status,
            error = if (status == ChatStatus.ERROR) {
              state.error ?: "queued turn recovery required before admission"
            } else {
              state.error
            },
            pendingSteerCount = steerQueue.size,
            queuedRecoveryRequired = steerQueue.isNotEmpty(),
          )
        }
        false
      } else {
        activityRevision++
        _uiState.update { it.copy(pendingSteerCount = steerQueue.size, status = ChatStatus.WAITING_STEERED) }
        true
      }
    }
    if (!hasQueued) {
      return
    }
    val decision = try {
      policy.evaluateFresh(POLICY_ACTION, POLICY_RESOURCE)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      failPromotion("policy evaluation failed", self)
      return
    }
    if (decision.verdict == Verdict.ASK) {
      failPromotion("approval required", self)
      return
    }
    if (decision.verdict == Verdict.DENY) {
      afterFollowUpDenyForTest?.invoke()
      failPromotion("denied by policy", self)
      return
    }
    afterFollowUpGateForTest?.invoke()
    synchronized(lock) {
      if (
        closed || self?.isActive != true || _uiState.value.status == ChatStatus.CANCELLED ||
        _uiState.value.queuedRecoveryRequired
      ) {
        if (inFlight === self) inFlight = null
        return
      }
      val next = steerQueue.removeFirstOrNull()
      if (next == null) {
        // drainQueued() won after the fresh gate. Mirror the empty-queue
        // transition while preserving a racing close()'s CANCELLED state.
        if (inFlight === self) inFlight = null
        _uiState.update { s ->
          if (s.status == ChatStatus.STREAMING || s.status == ChatStatus.WAITING_STEERED) {
            s.copy(status = ChatStatus.IDLE)
          } else {
            s
          }
        }
        return
      }
      activityRevision++
      appendUser(next.text, next.opId)
      _uiState.update { it.copy(status = ChatStatus.STREAMING, error = null, pendingSteerCount = steerQueue.size) }
      val follow = scope.launch { hostedTurn(next.text, next.images) }
      inFlight = follow
      follow.invokeOnCompletion {
        synchronized(lock) {
          if (inFlight === follow) inFlight = null
        }
      }
    }
  }

  /** Caller has completed the promotion gate; fail closed without consuming the FIFO. */
  private fun failPromotion(error: String, host: Job?) {
    synchronized(lock) {
      // A concurrent close/cancel owns the terminal projection. Otherwise
      // expose an idle error so the VM can explicitly recover the retained FIFO.
      if (closed || (host != null && !host.isActive) || _uiState.value.status == ChatStatus.CANCELLED) {
        if (inFlight === host) inFlight = null
        return
      }
      if (inFlight === host) inFlight = null
      activityRevision++
      _uiState.update {
        it.copy(
          status = ChatStatus.ERROR,
          error = error,
          pendingSteerCount = steerQueue.size,
          queuedRecoveryRequired = steerQueue.isNotEmpty(),
        )
      }
    }
  }

  private suspend fun runTurnLoop(text: String, images: List<ChatImageRef>) {
    var attempt = 0
    var runId = newId()
    // Snapshot: fireTranscript is scope.launch async; capturing the var
    // directly would race with later `runId = newId()` reassignments.
    runId.let { startedId -> fireTranscript { transcript.onTurnStarted(startedId, text) } }
    while (true) {
      val attemptRunId = runId
      appendAssistantPlaceholder(attemptRunId)
      val request = buildRequest(images)
      var failed: StreamEvent.Failed? = null
      var done = false
      // Tool aggregation for this attempt: deltas without a terminal ToolDone
      // are flushed as pending records so they are never silently dropped.
      val toolArgText = mutableMapOf<Int, StringBuilder>()
      val toolIdText = mutableMapOf<Int, StringBuilder>()
      val toolNameText = mutableMapOf<Int, StringBuilder>()
      val toolDoneSeen = mutableSetOf<Int>()
      fun flushPendingTools() {
        for ((index, args) in toolArgText) {
          if (index !in toolDoneSeen) {
            val id = toolIdText[index]?.toString().orEmpty().ifEmpty { "call_pending_$index" }
            val name = toolNameText[index]?.toString().orEmpty().ifEmpty { "pending" }
            // Snapshot args string before async hop (StringBuilder keeps mutating).
            val argsStr = args.toString()
            appendAssistantText(attemptRunId, "\n[tool:$name $argsStr]")
            fireTranscript { transcript.onToolDone(attemptRunId, index, id, name, argsStr) }
          }
        }
      }
      try {
        provider.stream(request).collect { event ->
          coroutineContext.ensureActive()
          // A provider terminal is authoritative; ignore buggy/legacy events
          // that an implementation emits after Done or Failed.
          if (done || failed != null) return@collect
          when (event) {
            is StreamEvent.TextDelta -> {
              streamedTextDeltaCount++
              appendAssistantText(attemptRunId, event.delta)
            }
            // Reasoning stays in the same assistant block: P1 keeps one visible
            // stream and never loses thinking content.
            is StreamEvent.ReasoningDelta -> {
              streamedTextDeltaCount++
              appendAssistantText(attemptRunId, event.delta)
            }
            is StreamEvent.ToolDelta -> {
              // Fail-closed per-call aggregation budget: a hostile stream of
              // tiny fragments (args or id/name chunks) must not grow memory
              // without bound. Throws typed non-retryable TOOL_ARGS_TOO_LARGE
              // (never silent truncation); the catch below skips the pending
              // flush so poison-adjacent fragments are never recorded.
              requireToolAggBudget(
                (toolArgText[event.toolIndex]?.length ?: 0) +
                  (toolIdText[event.toolIndex]?.length ?: 0) +
                  (toolNameText[event.toolIndex]?.length ?: 0),
                event.argsChunk.length +
                  (event.idChunk?.length ?: 0) +
                  (event.nameChunk?.length ?: 0),
              )
              toolArgText.getOrPut(event.toolIndex) { StringBuilder() }.append(event.argsChunk)
              event.idChunk?.let {
                if (it.isNotEmpty()) toolIdText.getOrPut(event.toolIndex) { StringBuilder() }.append(it)
              }
              event.nameChunk?.let {
                if (it.isNotEmpty()) toolNameText.getOrPut(event.toolIndex) { StringBuilder() }.append(it)
              }
            }
            is StreamEvent.ToolDone -> {
              toolDoneSeen.add(event.toolIndex)
              appendAssistantText(attemptRunId, "\n[tool:${event.name} ${event.argumentsJson}]")
              fireTranscript {
                transcript.onToolDone(attemptRunId, event.toolIndex, event.id, event.name, event.argumentsJson)
              }
            }
            is StreamEvent.Usage -> {
              lastUsage = event
              fireTranscript { transcript.onUsage(attemptRunId, event.inputTokens, event.outputTokens) }
            }
            is StreamEvent.Done -> {
              finalizeAssistant(attemptRunId)
              done = true
            }
            is StreamEvent.Failed -> failed = event
            is StreamEvent.Retrying -> {
              // Compatibility with legacy/custom providers. Built-in adapters
              // do not retry; this controller owns retries from Failed events.
              fireTranscript { transcript.onTurnRetried(attemptRunId, event.attempt, event.maxAttempts, event.delayMs) }
            }
          }
        }
        flushPendingTools()
        if (!done && failed == null) {
          failed = StreamEvent.Failed("truncated stream", retryable = true)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: ProviderFailure) {
        failed = StreamEvent.Failed(e.message ?: "provider error", retryable = e.retryable)
        if (e.code != ProviderFailureCode.AGG_TOO_LARGE) {
          flushPendingTools()
        }
        // AGG_TOO_LARGE is this collector's own guard: fail closed without
        // recording poison-adjacent pending tools (the typed terminal error
        // is the observable record). Visible text is already current because
        // text updates stay per-delta (see uiTextUpdateCount).
      } catch (e: Exception) {
        failed = StreamEvent.Failed(e.message ?: "provider error", retryable = true)
        flushPendingTools()
      }
      val failure = failed
      if (failure == null) {
        // Snapshot text: assistantTextOf reads uiState at execution time.
        val successText = assistantTextOf(attemptRunId)
        fireTranscript { transcript.onTurnSucceeded(attemptRunId, successText) }
        return
      }
      // Keep the failed fragment as-is (isPartial=true); the retry below
      // starts a brand-new assistant block with a brand-new runId.
      if (!failure.retryable || attempt >= retryConfig.maxRetries) {
        val clean = sanitizeError(failure.message)
        synchronized(lock) {
          activityRevision++
          _uiState.update { it.copy(status = ChatStatus.ERROR, error = clean) }
        }
        fireTranscript { transcript.onTurnFailed(attemptRunId, clean) }
        return
      }
      attempt += 1
      val waitMs = retryConfig.delayForRetry(attempt)
      // Snapshot mutable `attempt` before async hop: the launched block may
      // run after the next iteration increments it (both retries read 2).
      val firedAttempt = attempt
      val firedRunId = attemptRunId
      val firedMax = retryConfig.maxRetries
      fireTranscript { transcript.onTurnRetried(firedRunId, firedAttempt, firedMax, waitMs) }
      try {
        sleeper(waitMs)
      } catch (e: CancellationException) {
        throw e
      }
      runId = newId()
      runId.let { startedId -> fireTranscript { transcript.onTurnStarted(startedId, text) } }
    }
  }

  /**
   * Maps the current (settled) UI history plus this turn's images to one
   * [ChatRequest]. The current user message is already in [uiState] (appended
   * by [send] before the turn starts), so images attach to the last user line.
   *
   * Context window: [historyCap] truncates a **copy** of the settled
   * non-partial history oldest-first at whole-message boundaries before
   * images/time-block attach. UI state and transcript keep everything; only
   * the outgoing request is windowed. The drop count is published on
   * [droppedHistoryCount] (0 when Unbounded), never silent.
   *
   * Ephemeral time context: a [buildRuntimeTimeContext] block is appended to a
   * copy of the last user message only. No user message means no injection
   * (never fabricates a message for the clock). UI state and transcript keep
   * the raw user text.
   */
  private fun buildRequest(images: List<ChatImageRef>): ChatRequest {
    val settled = _uiState.value.messages
      .filter { !it.isPartial }
      .map { ChatMessage(role = it.role, text = it.text) }
    val base = applyHistoryCap(settled, historyCap).toMutableList()
    droppedHistoryCount = (settled.size - base.size).coerceAtLeast(0)
    val loaded = runCatching { imageLoader(images) }.getOrDefault(emptyList())
    if (loaded.isNotEmpty()) {
      val lastUser = base.indexOfLast { it.role == "user" }
      if (lastUser >= 0) {
        base[lastUser] = base[lastUser].copy(images = loaded)
      } else {
        base.add(ChatMessage(role = "user", text = "", images = loaded))
      }
    }
    val lastUser = base.indexOfLast { it.role == "user" }
    if (lastUser >= 0) {
      val block = buildRuntimeTimeContext(clock, userTimezone, sessionStart, systemZone)
      warnOnTimeContextDegraded(block)
      base[lastUser] = base[lastUser].copy(text = base[lastUser].text + "\n\n" + block)
    }
    return ChatRequest(model = model, messages = base)
    // NOTE (PR#1 scope-down): tools deliberately NOT attached here.
    // ChatRequest.tools may only carry ToolRegistry.visibleTools() output
    // (projection + executionReady); all 41 tools are executionReady=false
    // in this scaffold PR, so the request stays pure-chat by construction.
  }

  /**
   * Observability for the ephemeral time block: `Log.w` only, never blocks
   * the turn. Fires when the block renders `(time unknown)` or when a
   * non-blank [userTimezone] failed to parse (fell back to system zone).
   * A successful parse never warns, even when the resolved id is normalized
   * (e.g. `UTC+8` → `UTC+08:00`). `runCatching` keeps JVM unit tests (no
   * mocked `android.util.Log`) green.
   *
   * Log/block consistency: the reported `used` zone is resolved exactly like
   * [buildRequest] — blank [userTimezone] reads [systemZone] (with the same
   * `resolveZone(null)` fallback), so the log never names a different zone
   * than the block actually used.
   */
  private fun warnOnTimeContextDegraded(block: String) {
    val fellBack = isTimezoneFallback(userTimezone)
    if (!block.contains("(time unknown)") && !fellBack) return
    val used = if (userTimezone.isNullOrBlank()) {
      runCatching { systemZone().id }.getOrDefault(
        runCatching { resolveZone(null).id }.getOrDefault("?"),
      )
    } else {
      runCatching { resolveZone(userTimezone).id }.getOrDefault("?")
    }
    runCatching {
      android.util.Log.w(
        "TurnController",
        "runtime time context degraded: requested=$userTimezone used=$used unknown=${block.contains("(time unknown)")}",
      )
    }
  }

  // ---- uiState helpers (StateFlow.update is atomic; no lock needed) ----

  private fun appendUser(text: String, operationId: Long? = null) {
    _uiState.update { s ->
      s.copy(
        messages = s.messages + UiMessage(
          id = newId(),
          role = "user",
          text = text,
          isPartial = false,
          operationId = operationId,
        ),
      )
    }
  }

  private fun appendAssistantPlaceholder(runId: String) {
    _uiState.update { s ->
      s.copy(messages = s.messages + UiMessage(id = runId, role = "assistant", text = "", isPartial = true))
    }
  }

  private fun appendAssistantText(runId: String, delta: String) {
    if (delta.isEmpty()) return
    val prior = _uiState.value.messages.firstOrNull { it.id == runId && it.role == "assistant" }?.text?.length ?: 0
    _uiState.update { s ->
      s.copy(
        messages = s.messages.map { m ->
          if (m.id == runId && m.role == "assistant") m.copy(text = m.text + delta) else m
        },
      )
    }
    uiTextUpdateCount++
    uiTextCopiedChars += prior + delta.length
  }

  private fun finalizeAssistant(runId: String) {
    _uiState.update { s ->
      s.copy(
        messages = s.messages.map { m ->
          if (m.id == runId && m.role == "assistant") m.copy(isPartial = false) else m
        },
      )
    }
  }

  private fun assistantTextOf(runId: String): String =
    _uiState.value.messages.firstOrNull { it.id == runId }?.text.orEmpty()

  private fun latestAssistantId(): String =
    _uiState.value.messages.lastOrNull { it.role == "assistant" }?.id.orEmpty()

  private fun latestAssistantText(): String =
    _uiState.value.messages.lastOrNull { it.role == "assistant" }?.text.orEmpty()
}

/**
 * Pure fallback detector for the warn path: true only when a non-blank
 * [requested] timezone fails to parse (so [resolveZone] falls back to the
 * system zone). Successful parses — including normalized ids such as
 * `UTC+8` → `UTC+08:00` — return false; null/blank returns false (nothing
 * was requested). Never throws; JVM-pure (no `android.util.Log`) so it is
 * directly unit-testable. Fail-closed: any parse failure counts as
 * fallback so the caller warns.
 */
internal fun isTimezoneFallback(requested: String?): Boolean {
  if (requested.isNullOrBlank()) return false
  return runCatching { ZoneId.of(requested.trim()) }.isFailure
}

/**
 * Default image loader: reads app-private files into [ChatImage] bytes.
 * Bounded per spec §4 (10 MiB per image, first 4 images); unreadable or
 * oversize entries are omitted (never crash the turn, never log bytes).
 */
internal fun defaultChatImageLoader(refs: List<ChatImageRef>): List<ChatImage> {
  if (refs.isEmpty()) return emptyList()
  val out = mutableListOf<ChatImage>()
  for (ref in refs.take(TurnController.MAX_IMAGES_PER_TURN)) {
    val bytes = runCatching {
      val file = java.io.File(ref.filePath)
      if (!file.isFile) return@runCatching null
      if (file.length() > TurnController.MAX_IMAGE_BYTES) return@runCatching null
      file.readBytes().takeIf { it.size <= TurnController.MAX_IMAGE_BYTES }
    }.getOrNull() ?: continue
    out.add(ChatImage(bytes = bytes, mimeType = ref.mimeType, preserveOriginal = ref.preserveOriginal))
  }
  return out
}

/**
 * Error sanitizer for [ChatUiState.error]: [Redactor.redactError] strips
 * keys/tokens/URL credentials/private IPs, then truncates to 500 chars.
 * The sanitized string is the only form that reaches UI state, transcript
 * persistence, and logs; the raw message never leaves memory.
 */
internal fun sanitizeError(raw: String): String = Redactor.redactError(raw)
