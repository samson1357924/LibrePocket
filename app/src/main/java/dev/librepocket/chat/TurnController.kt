package dev.librepocket.chat

import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatImage
import dev.librepocket.provider.ChatMessage
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderFailure
import dev.librepocket.provider.StreamEvent
import dev.librepocket.redact.Redactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
 * Resumed-history window cap (Phase 3).
 *
 * Target: a measured token/size budget for the resumed prefix (TODO: measure
 * real provider payloads across representative sessions before fixing a
 * number — no threshold is hardcoded here). Until that measurement lands the
 * default is [Unbounded] (hydrate everything, truncate nothing); the
 * message/char-count variants exist so callers and tests can lock the
 * truncation behavior (oldest-first, whole messages only) today.
 *
 * Tool-pair integrity: tool calls are recorded inline in their assistant
 * block (`[tool:name args]` marker text, never separate provider `tool`
 * messages), so truncating at whole-message boundaries can never detach a
 * tool result from its call. Any truncation is observable via
 * [TurnController.droppedHistoryCount], never silent.
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
 * Oldest-first, whole-message truncation for a resumed prefix. Whole messages
 * only (tool-pair integrity is structural: tools live inline in their
 * assistant block). A single oldest message that already exceeds a char
 * budget is still kept whole — truncation drops messages, never splits one.
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
 * Transcript durability: every transcript event goes through the
 * session-owned [OrderedTranscriptSink] (bounded channel, single writer, core
 * events durably acked). The writer runs outside the turn scope, so
 * cancelling the network never discards an admitted event; [close] settles
 * the doomed host and drains with a bounded timeout instead of cancelling the
 * scope out from under pending writes.
 *
 * Retry: each attempt gets a fresh runId; failed partial output is kept with
 * `isPartial=true` and the next attempt starts a new assistant block instead
 * of backfilling the old one. Stage F: the intermediate fragment is
 * non-final (`isFinal=false`) and never closes the logical family; only the
 * final success / terminal-failure / cancel row (`isFinal=true`) does. A
 * cancel in a retry backoff gap writes one system-kind final mark instead of
 * a second assistant row for the same attempt.
 *
 * Resumed history (Phase 3): [initialHistory] carries the pre-truncation
 * user/assistant prefix restored from the transcript store (partial rows are
 * excluded by the caller — they replay in the UI flagged, but never read as
 * completed model context, mirroring the live `!isPartial` filter below).
 * [historyCap] bounds that prefix oldest-first at whole-message boundaries
 * ([HistoryWindowCap]); the default hydrates everything while the measured
 * token budget is still a TODO. Every outgoing [ChatRequest] is
 * history-prefix + live messages, so the first request after resume already
 * sees the prior conversation.
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
/** Admission details for a turn that passed policy and appended a user row but whose host has not yet started. */
data class PendingAdmission(
  val logicalTurnId: String,
  val text: String,
  val images: List<ChatImageRef>,
  val attemptRef: AtomicReference<String>,
  val attemptIndexRef: AtomicInteger,
)

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
  initialHistory: List<ChatMessage> = emptyList(),
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
  private val writerScope = CoroutineScope(SupervisorJob() + dispatcher)
  /**
   * Session-owned serialized transcript writer. All [TranscriptSink] calls
   * below go through it (never directly to [transcript]), so persistence
   * order matches admission order and core events are durably acked. Its
   * scope is independent of [scope], so turn cancellation cannot strand an
   * admitted event.
   */
  private val orderedTranscript = OrderedTranscriptSink(transcript, dispatcher)
  /** Background settle+drain+seal launched by [close]; awaited by [flushTranscript]. Guarded by [lock]. */
  private var shutdownJob: Job? = null
  private var pendingAdmission: PendingAdmission? = null
  private var orphanDrainJob: Job? = null

  private fun drainOrphanLocked(pending: PendingAdmission): Job {
    if (pendingAdmission?.logicalTurnId == pending.logicalTurnId) {
      pendingAdmission = null
    }
    val drain = writerScope.launch(NonCancellable) {
      try {
        orderedTranscript.onTurnStarted(pending.logicalTurnId, pending.text)
        orderedTranscript.onLogicalTurnCancelled(
          pending.logicalTurnId,
          pending.attemptRef.get(),
          "",
          pending.attemptIndexRef.get(),
        )
      } catch (_: Exception) {
        orderedTranscript.markInterrupted(pending.logicalTurnId)
      }
    }
    orphanDrainJob = drain
    return drain
  }
  private val _uiState = MutableStateFlow(
    ChatUiState(messages = emptyList(), status = ChatStatus.IDLE, pendingSteerCount = 0, error = null),
  )
  val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

  /** Last usage reported by the provider (billing/context accounting; P1 records only). */
  var lastUsage: StreamEvent.Usage? = null
    private set

  /** Resumed-history prefix after [historyCap] (oldest-first, whole messages). */
  private val cappedHistory: List<ChatMessage> = applyHistoryCap(initialHistory, historyCap)

  /**
   * Resumed-history messages dropped by [historyCap] (0 when Unbounded).
   * The observable counterpart to truncation: the request never silently
   * loses prefix context.
   */
  val droppedHistoryCount: Int = (initialHistory.size - cappedHistory.size).coerceAtLeast(0)

  private var inFlight: Job? = null
  private val steerQueue: ArrayDeque<PendingSteer> = ArrayDeque()
  private var closed = false
  /** Monotonic under [lock]; invalidates fresh-gate failures after turn activity. */
  private var activityRevision = 0L
  /**
   * Stage F: attempt ids whose terminal row is already durably acked
   * (success final, terminal-failure final, or retryable intermediate partial
   * non-final). A cancel that lands on a contained id writes one system-kind
   * final mark instead of a second assistant row for the same attempt. Ids
   * are unique per attempt (UUID/newId), so the set stays bounded by the
   * session's attempt count; entries are added only after the ack succeeds.
   */
  private val terminalizedAttempts: MutableSet<String> =
    java.util.concurrent.ConcurrentHashMap.newKeySet()

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
      // Stage B: the logical turn id exists before the first suspension
      // (onTurnStarted ack), so a cancel parked on that ack still attributes
      // to this turn instead of falling back to the previous assistant.
      // Stage C: the attempt index ref travels with the attempt id ref so a
      // cancel in any retry gap attributes to the live attempt's family.
      val logicalTurnId = newId()
      val attemptRef = AtomicReference(logicalTurnId)
      val attemptIndexRef = AtomicInteger(0)
      pendingAdmission = PendingAdmission(logicalTurnId, text, images, attemptRef, attemptIndexRef)
      val job = scope.launch { hostedTurn(text, images, logicalTurnId, attemptRef, attemptIndexRef) }
      inFlight = job
      job.invokeOnCompletion {
        synchronized(lock) {
          if (inFlight === job) inFlight = null
          val pending = pendingAdmission
          if (pending?.logicalTurnId == logicalTurnId) {
            drainOrphanLocked(pending)
          }
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
      // Same Stage B ownership as startTurn: id before first suspension.
      // Stage C: attempt index ref travels with the attempt id ref.
      val logicalTurnId = newId()
      val attemptRef = AtomicReference(logicalTurnId)
      val attemptIndexRef = AtomicInteger(0)
      pendingAdmission = PendingAdmission(logicalTurnId, text, images, attemptRef, attemptIndexRef)
      val job = scope.launch { hostedTurn(text, images, logicalTurnId, attemptRef, attemptIndexRef) }
      inFlight = job
      job.invokeOnCompletion {
        synchronized(lock) {
          if (inFlight === job) inFlight = null
          val pending = pendingAdmission
          if (pending?.logicalTurnId == logicalTurnId) {
            drainOrphanLocked(pending)
          }
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

  fun close(drainTimeoutMs: Long = OrderedTranscriptSink.DEFAULT_DRAIN_TIMEOUT_MS) {
    // Drop the FIFO under the same lock that seals the controller, so no
    // admission can slip in after the seal. Reclaim-then-seal ordering across
    // threads is the caller's job: the ViewModel drains-then-closes with no
    // suspension on its Main-confined path, so nothing slips between them
    // there. Callers that must keep unstarted work (endpoint-switch recovery)
    // drain it explicitly via drainQueued() BEFORE close().
    val doomed: Job?
    val orphanJob: Job?
    synchronized(lock) {
      if (closed) return
      closed = true
      activityRevision++
      steerQueue.clear()
      val current = activeLocked()
      doomed = current
      val orphaned = pendingAdmission
      orphanJob = if (orphaned != null) {
        drainOrphanLocked(orphaned)
      } else {
        orphanDrainJob
      }
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
    // Bounded ledger drain on the session-owned writer (independent of
    // [scope], so it survives the cancel above): settle the doomed host first
    // so its non-cancellable terminal record is admitted ahead of the flush
    // barrier, then drain, then seal. Core runIds still unacked afterwards are
    // exposed via [interruptedTranscriptRunIds], never silently dropped. The
    // synchronous part only launches this work, so close() stays fast even
    // when the store stalls; await it via [flushTranscript]. The job is
    // published under [lock] so a concurrent [flushTranscript] observes it.
    val shutdown = orderedTranscript.shutdown(drainTimeoutMs) {
      try {
        doomed?.join()
      } catch (_: CancellationException) {
        // Hosts rethrow cancellation after persisting the terminal record.
      }
      try {
        val drain = synchronized(lock) { orphanDrainJob } ?: orphanJob
        drain?.join()
      } catch (_: CancellationException) {
      }
    }
    shutdown?.invokeOnCompletion {
      writerScope.cancel()
    }
    synchronized(lock) { shutdownJob = shutdown }
  }

  /**
   * Suspends until the session ledger is fully settled: first the background
   * [close] drain (doomed-host settle + admitted drain + seal, all bounded),
   * then every event admitted so far. Each phase gets its own [timeoutMs]
   * budget — the [close] shutdown-join first, then the admitted-event drain —
   * so the total wait is bounded by roughly 2x[timeoutMs], not [timeoutMs]
   * (true = drained, false = timed out). Send returning already implies
   * durability for the terminal record (core writes are acked inline); use
   * this after [close]/[cancel] to await the asynchronous terminal record.
   * Cancellation of the caller still propagates.
   *
   * Concurrency: [shutdownJob] is read under the same [lock] that [close]
   * publishes it with, so a concurrent [flushTranscript] either observes the
   * drain job or — if it raced ahead of [close] — only flushes admitted
   * events; call again after [close] returns to await the full drain.
   */
  suspend fun flushTranscript(timeoutMs: Long = OrderedTranscriptSink.DEFAULT_FLUSH_TIMEOUT_MS): Boolean {
    val shutdown = synchronized(lock) { shutdownJob }
    try {
      if (shutdown != null && withTimeoutOrNull(timeoutMs) { shutdown.join() } == null) return false
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      // Best effort: the drain below still waits for what was admitted.
    }
    return orderedTranscript.flush(timeoutMs)
  }

  /**
   * Core runIds admitted but never acked (drain timeout / seal race).
   * Explicit INTERRUPTED marks for Phase 2/3 recovery; no Room schema change
   * in Phase 1.
   */
  fun interruptedTranscriptRunIds(): List<String> = orderedTranscript.interruptedRunIds()

  /** Core runIds admitted and still awaiting the session writer. */
  fun pendingTranscriptRunIds(): List<String> = orderedTranscript.pendingRunIds()

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
    // Session-ordered (never fire-and-forget): the writer admits this behind
    // the caller's lock ordering, so queue order matches transcript order.
    orderedTranscript.offerSteer(text)
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

  /**
   * Hosts one logical turn. [logicalTurnId] and [attemptRef]/[attemptIndexRef]
   * are created before the host launches (before the first suspension), so a
   * cancel parked on the `onTurnStarted` ack still attributes to this turn.
   * Cancel always records the current attempt id + that attempt's text
   * (pre-placeholder partial is "" with this turn's id); it never falls
   * back to a previous turn's id and never uses an empty runId. If the user
   * row itself is not yet durable, the cancel terminal still uses this
   * turn's id (ordered behind the started entry, or INTERRUPTED-marked on
   * store/seal failure). Stage C: the cancel terminal carries the logical
   * family linkage (`parentRunId` + `attemptIndex`) like every other attempt
   * terminal. Stage F: a cancel that lands on an already-terminalized
   * attempt (retry backoff gap: the just-failed partial is already durable)
   * writes one system-kind final mark instead of a second assistant row for
   * the same attempt; otherwise the cancel assistant row is final
   * (`isFinal=1`).
   */
  private suspend fun hostedTurn(
    text: String,
    images: List<ChatImageRef>,
    logicalTurnId: String,
    attemptRef: AtomicReference<String>,
    attemptIndexRef: AtomicInteger,
  ) {
    synchronized(lock) {
      if (pendingAdmission?.logicalTurnId == logicalTurnId) {
        pendingAdmission = null
      }
    }
    val self = coroutineContext[Job]
    try {
      runTurnLoop(text, images, logicalTurnId, attemptRef, attemptIndexRef)
    } catch (e: CancellationException) {
      // Current attempt only: attemptRef tracks the live attempt across
      // retries/sleeper gaps, so this never reuses a previous turn's id.
      val cancelId = attemptRef.get()
      val cancelIndex = attemptIndexRef.get()
      val cancelParent = parentFor(cancelId, logicalTurnId)
      val cancelText = assistantTextOf(cancelId)
      // Stage F dedup: the terminalized set holds every attempt whose row is
      // already durably acked (success final, terminal-failure final, or
      // retryable intermediate partial non-final). A backoff-gap cancel hits
      // the just-failed id, so it writes one system-kind final mark instead
      // of a second assistant row for the same attempt. A streaming cancel
      // misses and keeps the original final assistant row.
      val deduped = terminalizedAttempts.contains(cancelId)
      // Durable and non-cancellable: the host is cancelled but the session
      // writer is independent, so this record still drains on close (or is
      // explicitly marked INTERRUPTED instead of silently dropped). A
      // sealed/closed writer after close()/shutdown fails this write fast
      // (ClosedSendChannelException or the sealed fail-fast
      // CancellationException, already marked INTERRUPTED in the sink): swallow
      // only that seal race so it cannot mask the original cancellation, then
      // still rethrow the original. A durable store failure is likewise
      // already INTERRUPTED-marked: keep the original cancellation.
      withContext(NonCancellable) {
        try {
          if (deduped) {
            orderedTranscript.onLogicalTurnCancelled(logicalTurnId, cancelId, cancelText, cancelIndex)
          } else {
            orderedTranscript.onTurnCancelled(cancelId, cancelText, cancelParent, cancelIndex)
          }
        } catch (_: ClosedSendChannelException) {
          // Sealed after close(): explicit INTERRUPTED mark already recorded.
        } catch (sealFailure: CancellationException) {
          if (!orderedTranscript.isSealed()) throw sealFailure
          // Sealed fail-fast: INTERRUPTED already marked, keep original cancel.
        } catch (_: Exception) {
          // Durable store failure for the cancel terminal itself: the sink
          // already marked this runId INTERRUPTED; keep original cancel.
        }
      }
      throw e
    } catch (e: Exception) {
      // Durable core-write failure (e.g. store IOException on started/
      // succeeded/failed): the sink already failed the ack loudly and marked
      // the runId INTERRUPTED. Project ERROR so the UI never sticks in
      // STREAMING, and never report success. Falls through to the normal
      // completion path below (same as a provider failure): empty queue keeps
      // ERROR, queued work may still promote.
      synchronized(lock) {
        activityRevision++
        _uiState.update {
          it.copy(status = ChatStatus.ERROR, error = sanitizeError(e.message ?: "transcript store failed"))
        }
      }
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
      // Follow-up owns a fresh logical id, also created before launch.
      val followLogicalId = newId()
      val followAttemptRef = AtomicReference(followLogicalId)
      val followAttemptIndexRef = AtomicInteger(0)
      pendingAdmission = PendingAdmission(followLogicalId, next.text, next.images, followAttemptRef, followAttemptIndexRef)
      val follow = scope.launch { hostedTurn(next.text, next.images, followLogicalId, followAttemptRef, followAttemptIndexRef) }
      inFlight = follow
      follow.invokeOnCompletion {
        synchronized(lock) {
          if (inFlight === follow) inFlight = null
          val pending = pendingAdmission
          if (pending?.logicalTurnId == followLogicalId) {
            drainOrphanLocked(pending)
          }
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

  /**
   * Best-effort ordered notice (tool / usage / retry): the sink throws
   * [ClosedSendChannelException] on the post-[close] seal race instead of
   * succeeding silently, and that race is owned here — the notice is dropped
   * explicitly (tool/usage notices have no drainQueued recovery path, and
   * `close()` clears the FIFO). Cancellation of the caller still propagates.
   */
  private suspend fun writeNotice(block: suspend () -> Unit) {
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (_: ClosedSendChannelException) {
      // Sealed after close(): explicit drop, no recovery path.
    }
  }

  /**
   * Stage C family linkage: the first attempt reuses the logical id (parent
   * stays null for byte-compat with pre-C rows); later attempts get
   * `parentRunId=logicalTurnId`. Retry notices already bind the logical id.
   */
  private fun parentFor(attemptRunId: String, logicalTurnId: String): String? =
    if (attemptRunId == logicalTurnId) null else logicalTurnId

  private suspend fun runTurnLoop(
    text: String,
    images: List<ChatImageRef>,
    logicalTurnId: String,
    attemptRef: AtomicReference<String>,
    attemptIndexRef: AtomicInteger,
  ) {
    // One logical turn owns exactly one user row (logicalTurnId, created
    // before launch), durably acked before the first provider attempt.
    // Retries add attempt records bound to that id and start a new assistant
    // block each; they never emit another user row. Direct suspend calls (no
    // fire-and-forget hop), so no snapshot copies are needed: each write
    // completes in call order. attemptRef always holds the live attempt id
    // (sleeper gaps keep the just-failed id until the next id is minted), so
    // the hostedTurn cancel handler never needs the global latest assistant.
    // Stage C: every attempt terminal (success, terminal failure, retried
    // partial, cancel) carries parentRunId/attemptIndex so the logical family
    // (parentRunId ?: runId) owns the full attempt chain; retry notices stay
    // bound to the logical id.
    orderedTranscript.onTurnStarted(logicalTurnId, text)
    var attempt = 0
    var attemptRunId = logicalTurnId
    while (true) {
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
      suspend fun flushPendingTools() {
        for ((index, args) in toolArgText) {
          if (index !in toolDoneSeen) {
            val id = toolIdText[index]?.toString().orEmpty().ifEmpty { "call_pending_$index" }
            val name = toolNameText[index]?.toString().orEmpty().ifEmpty { "pending" }
            val argsStr = args.toString()
            appendAssistantText(attemptRunId, "\n[tool:$name $argsStr]")
            writeNotice { orderedTranscript.onToolDone(attemptRunId, index, id, name, argsStr) }
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
            is StreamEvent.TextDelta -> appendAssistantText(attemptRunId, event.delta)
            // Reasoning stays in the same assistant block: P1 keeps one visible
            // stream and never loses thinking content.
            is StreamEvent.ReasoningDelta -> appendAssistantText(attemptRunId, event.delta)
            is StreamEvent.ToolDelta -> {
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
              writeNotice {
                orderedTranscript.onToolDone(attemptRunId, event.toolIndex, event.id, event.name, event.argumentsJson)
              }
            }
            is StreamEvent.Usage -> {
              lastUsage = event
              writeNotice { orderedTranscript.onUsage(attemptRunId, event.inputTokens, event.outputTokens) }
            }
            is StreamEvent.Done -> {
              finalizeAssistant(attemptRunId)
              done = true
            }
            is StreamEvent.Failed -> failed = event
            is StreamEvent.Retrying -> {
              // Compatibility with legacy/custom providers. Built-in adapters
              // do not retry; this controller owns retries from Failed events.
              // A per-attempt provider notice keeps the attempt id.
              writeNotice { orderedTranscript.onTurnRetried(attemptRunId, event.attempt, event.maxAttempts, event.delayMs) }
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
        flushPendingTools()
      } catch (e: Exception) {
        failed = StreamEvent.Failed(e.message ?: "provider error", retryable = true)
        flushPendingTools()
      }
      val failure = failed
      if (failure == null) {
        orderedTranscript.onTurnSucceeded(
          attemptRunId, assistantTextOf(attemptRunId),
          parentFor(attemptRunId, logicalTurnId), attempt,
        )
        terminalizedAttempts.add(attemptRunId)
        return
      }
      // Keep the failed fragment as-is (isPartial=true); the retry below
      // starts a brand-new assistant block with a brand-new runId. Phase 3
      // persists the failed partial structurally: the ledger row keeps the
      // partial text with isPartial=1 plus the sanitized reason (parent-aware
      // onTurnFailed), while UI memory keeps the same fragment flagged.
      if (!failure.retryable || attempt >= retryConfig.maxRetries) {
        val clean = sanitizeError(failure.message)
        synchronized(lock) {
          activityRevision++
          _uiState.update { it.copy(status = ChatStatus.ERROR, error = clean) }
        }
        orderedTranscript.onTurnFailed(
          attemptRunId, assistantTextOf(attemptRunId), clean,
          parentFor(attemptRunId, logicalTurnId), attempt, true,
        )
        terminalizedAttempts.add(attemptRunId)
        return
      }
      // Stage C: persist the retried partial BEFORE the retry notice, so a
      // retryable failure's intermediate fragment survives reopen/export even
      // when the next attempt later succeeds. Same durable form as the
      // terminal failure above (isPartial=1 + sanitized reason), bound to the
      // same logical family. Stage F: intermediate partials are non-final
      // (isFinal=false) so the family still dangles until a final row lands.
      val retriedClean = sanitizeError(failure.message)
      orderedTranscript.onTurnFailed(
        attemptRunId, assistantTextOf(attemptRunId), retriedClean,
        parentFor(attemptRunId, logicalTurnId), attempt, false,
      )
      terminalizedAttempts.add(attemptRunId)
      attempt += 1
      val waitMs = retryConfig.delayForRetry(attempt)
      // Bound to the logical turn (single user row), not to the failed
      // attempt: retries never re-emit onTurnStarted.
      writeNotice { orderedTranscript.onTurnRetried(logicalTurnId, attempt, retryConfig.maxRetries, waitMs) }
      try {
        sleeper(waitMs)
      } catch (e: CancellationException) {
        // A cancel during the backoff gap still attributes to the just-failed
        // attempt (its partial is already durable above); the new id is minted
        // only after the sleep, so rethrow without minting.
        throw e
      }
      attemptRunId = newId()
      // Publish before the next placeholder: a cancel in the gap still sees
      // the just-failed id only until the new id is minted here. The index
      // travels with the id so the cancel terminal keeps the family linkage.
      attemptRef.set(attemptRunId)
      attemptIndexRef.set(attempt)
    }
  }

  /**
   * Maps the resumed history plus the current (settled) UI history plus this
   * turn's images to one [ChatRequest]: the capped history prefix first, then
   * live messages. The current user message is already in [uiState] (appended
   * by [send] before the turn starts), so images attach to the last user line.
   *
   * Ephemeral time context: a [buildRuntimeTimeContext] block is appended to a
   * copy of the last user message only. No user message means no injection
   * (never fabricates a message for the clock). UI state and transcript keep
   * the raw user text.
   */
  private fun buildRequest(images: List<ChatImageRef>): ChatRequest {
    val base = ArrayList<ChatMessage>(cappedHistory.size + 8)
    base.addAll(cappedHistory)
    base.addAll(
      _uiState.value.messages
        .filter { !it.isPartial }
        .map { ChatMessage(role = it.role, text = it.text) },
    )
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
    _uiState.update { s ->
      s.copy(
        messages = s.messages.map { m ->
          if (m.id == runId && m.role == "assistant") m.copy(text = m.text + delta) else m
        },
      )
    }
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
