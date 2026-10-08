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
}

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

  private var inFlight: Job? = null
  private val steerQueue: ArrayDeque<PendingSteer> = ArrayDeque()
  private var closed = false

  /** Test seam for pausing after follow-up policy approval, before handoff. */
  internal var afterFollowUpGateForTest: (() -> Unit)? = null

  /** Test seam for arranging a queue admission immediately before completion checks it. */
  internal var beforeCompletionQueueCheckForTest: (() -> Unit)? = null

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
   * return means the text was NOT accepted and must stay recoverable.
   */
  suspend fun startTurn(text: String, images: List<ChatImageRef> = emptyList()): Job {
    require(text.isNotBlank()) { "text must not be blank" }
    synchronized(lock) {
      check(!closed) { "controller is closed" }
      if (activeLocked() != null) {
        _uiState.update { it.copy(error = "busy: another turn is in flight") }
        throw IllegalStateException("already in flight")
      }
    }
    gateOrThrow()
    val host = synchronized(lock) {
      check(!closed) { "controller is closed" }
      if (activeLocked() != null) {
        _uiState.update { it.copy(error = "busy: another turn is in flight") }
        throw IllegalStateException("already in flight")
      }
      appendUser(text)
      _uiState.update { it.copy(status = ChatStatus.STREAMING, error = null) }
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
      _uiState.update { it.copy(status = ChatStatus.CANCELLED) }
      // Cooperative: cancelling collection cancels the underlying HTTP call.
      current.cancel()
    }
    // No join, no IO: return immediately so the UI stops within 200ms.
  }

  /**
   * Atomic start-or-enqueue: the busy check and the FIFO insert happen under
   * the same lock, so the decision is never stale. Returns [TurnStart.Started]
   * once the text is accepted (fresh `chat.send` passed, user message
   * appended) without waiting for the turn to finish, or [TurnStart.Queued]
   * when a turn was already in flight (the text is queued FIFO and the
   * transcript records it). A [CancellationException]/[SecurityException]/
   * [IllegalStateException] before return means NOT accepted. Unlike
   * [steer], the idle path never fire-and-forgets: callers always get an
   * explicit acknowledgment.
   *
   * N1 ownership: [TurnStart.Queued] is NOT acceptance (no gate, no append).
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
    synchronized(lock) {
      check(!closed) { "controller is closed" }
      if (activeLocked() != null) {
        enqueueLocked(opId, text, images)
        return TurnStart.Queued
      }
    }
    gateOrThrow()
    val host = synchronized(lock) {
      check(!closed) { "controller is closed" }
      if (activeLocked() != null) {
        // Lost the race during the gate: queue instead of throwing, so the
        // caller still gets an explicit acceptance signal.
        enqueueLocked(opId, text, images)
        return TurnStart.Queued
      }
      appendUser(text)
      _uiState.update { it.copy(status = ChatStatus.STREAMING, error = null) }
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

  fun steer(text: String) {
    require(text.isNotBlank()) { "text must not be blank" }
    synchronized(lock) {
      check(!closed) { "controller is closed" }
      if (activeLocked() != null) {
        // Never cancel the current turn: queue for the next round (FIFO).
        enqueueLocked(null, text, emptyList())
        return
      }
    }
    // Idle: behave like send, asynchronously (this entry is non-suspending).
    scope.launch {
      try {
        send(text)
      } catch (_: Exception) {
        // Surfaced via uiState (busy/denied/closed); nothing more to do here.
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
      steerQueue.clear()
      val current = activeLocked()
      _uiState.update {
        it.copy(
          pendingSteerCount = 0,
          status = if (current != null) ChatStatus.CANCELLED else it.status,
        )
      }
      current?.cancel()
    }
    scope.cancel()
  }

  // ---- internals ----

  /** Caller must hold [lock]. Enqueues a follow-up and projects the count. */
  private fun enqueueLocked(opId: Long?, text: String, images: List<ChatImageRef>) {
    steerQueue.addLast(PendingSteer(opId, text, images))
    _uiState.update { it.copy(pendingSteerCount = steerQueue.size) }
    fireTranscript { transcript.onSteerQueued(text) }
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
      steerQueue.clear()
      _uiState.update { it.copy(pendingSteerCount = 0) }
      return out
    }
  }

  private suspend fun gateOrThrow() {
    val decision = policy.evaluateFresh(POLICY_ACTION, POLICY_RESOURCE)
    if (decision.verdict == Verdict.DENY) {
      _uiState.update { it.copy(status = ChatStatus.ERROR, error = "denied by policy") }
      throw SecurityException("chat.send denied by policy")
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
      if (steerQueue.isEmpty()) {
        if (inFlight === self) inFlight = null
        _uiState.update { s ->
          if (s.status == ChatStatus.STREAMING || s.status == ChatStatus.WAITING_STEERED) {
            s.copy(status = ChatStatus.IDLE)
          } else {
            s
          }
        }
        false
      } else {
        _uiState.update { it.copy(pendingSteerCount = steerQueue.size, status = ChatStatus.WAITING_STEERED) }
        true
      }
    }
    if (!hasQueued) {
      return
    }
    val decision = policy.evaluateFresh(POLICY_ACTION, POLICY_RESOURCE)
    if (decision.verdict == Verdict.DENY) {
      _uiState.update { it.copy(status = ChatStatus.ERROR, error = "denied by policy") }
      synchronized(lock) {
        if (inFlight === self) inFlight = null
      }
      return
    }
    afterFollowUpGateForTest?.invoke()
    synchronized(lock) {
      if (closed) {
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
      appendUser(next.text)
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
        flushPendingTools()
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
        _uiState.update { it.copy(status = ChatStatus.ERROR, error = clean) }
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
   * Ephemeral time context: a [buildRuntimeTimeContext] block is appended to a
   * copy of the last user message only. No user message means no injection
   * (never fabricates a message for the clock). UI state and transcript keep
   * the raw user text.
   */
  private fun buildRequest(images: List<ChatImageRef>): ChatRequest {
    val base = _uiState.value.messages
      .filter { !it.isPartial }
      .map { ChatMessage(role = it.role, text = it.text) }
      .toMutableList()
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

  private fun appendUser(text: String) {
    _uiState.update { s ->
      s.copy(messages = s.messages + UiMessage(id = newId(), role = "user", text = text, isPartial = false))
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
