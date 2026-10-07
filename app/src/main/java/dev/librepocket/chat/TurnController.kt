package dev.librepocket.chat

import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatImage
import dev.librepocket.provider.ChatMessage
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
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
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Retry budget for one turn (P1 defaults: 3 retries, fixed 2s/4s/8s backoff). */
data class TurnRetryConfig(
  val maxRetries: Int = 3,
  val retryDelaysMs: List<Long> = listOf(2_000L, 4_000L, 8_000L),
) {
  /** 1-based retry index → delay. Extra retries reuse the last delay. */
  fun delayForRetry(retryIndex: Int): Long =
    retryDelaysMs.getOrElse(retryIndex - 1) { retryDelaysMs.lastOrNull() ?: 0L }
}

private data class PendingSteer(val text: String, val images: List<ChatImageRef>)

/**
 * Turn controller: per-turn [Flow] collection + retry orchestration +
 * single-flight guard + steer queue + cooperative cancel.
 *
 * The controller consumes the unified [StreamEvent] union from [LlmProvider]
 * directly (no M3-local stub): [StreamEvent.TextDelta] and
 * [StreamEvent.ReasoningDelta] extend the current assistant block,
 * [StreamEvent.ToolDelta]/[StreamEvent.ToolDone] are aggregated and recorded
 * (marker text + [TranscriptSink.onToolDone]) so tool calls are never dropped,
 * [StreamEvent.Usage] is recorded ([TranscriptSink.onUsage] + [lastUsage]),
 * and provider [StreamEvent.Retrying] notices are forwarded to
 * [TranscriptSink.onTurnRetried] while the turn stays alive.
 *
 * SCAFFOLD (PR#1 re-review, P1 scope): this PR records ToolDone only and never
 * executes tools — no ToolDispatcher/FastRouter/PrivilegeGate/ElevatedDispatch
 * product wiring yet. Projection NATIVE means switch/flavor semantics only;
 * the true product tool loop (dispatcher + grant/confirm + Android executors +
 * E2E) lands in the wiring PR.
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
    try {
      host.join()
    } catch (e: CancellationException) {
      // Our own turn was cancelled via cancel()/close(): state already flipped.
      // Only propagate if the *caller* itself was cancelled.
      if (coroutineContext[Job]?.isCancelled == true) throw e
    }
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

  fun steer(text: String) {
    require(text.isNotBlank()) { "text must not be blank" }
    synchronized(lock) {
      check(!closed) { "controller is closed" }
      if (activeLocked() != null) {
        // Never cancel the current turn: queue for the next round (FIFO).
        steerQueue.addLast(PendingSteer(text, emptyList()))
        _uiState.update { it.copy(pendingSteerCount = steerQueue.size) }
        fireTranscript { transcript.onSteerQueued(text) }
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
      fireTranscript { transcript.onTurnCancelled(latestAssistantId(), latestAssistantText()) }
      throw e
    }
    // Normal completion only: hand off at most one queued steer as a detached
    // follow-up turn, so send() returns after its own turn.
    val next = synchronized(lock) { steerQueue.removeFirstOrNull() }
    if (next == null) {
      synchronized(lock) {
        if (inFlight === self) inFlight = null
        _uiState.update { s ->
          if (s.status == ChatStatus.STREAMING || s.status == ChatStatus.WAITING_STEERED) {
            s.copy(status = ChatStatus.IDLE)
          } else {
            s
          }
        }
      }
      return
    }
    _uiState.update { it.copy(pendingSteerCount = queueSize(), status = ChatStatus.WAITING_STEERED) }
    val decision = policy.evaluateFresh(POLICY_ACTION, POLICY_RESOURCE)
    if (decision.verdict == Verdict.DENY) {
      _uiState.update { it.copy(status = ChatStatus.ERROR, error = "denied by policy") }
      synchronized(lock) {
        if (inFlight === self) inFlight = null
      }
      return
    }
    synchronized(lock) {
      appendUser(next.text)
      _uiState.update { it.copy(status = ChatStatus.STREAMING, error = null) }
      val follow = scope.launch { hostedTurn(next.text, next.images) }
      inFlight = follow
      follow.invokeOnCompletion {
        synchronized(lock) {
          if (inFlight === follow) inFlight = null
        }
      }
    }
  }

  private fun queueSize(): Int = synchronized(lock) { steerQueue.size }

  private suspend fun runTurnLoop(text: String, images: List<ChatImageRef>) {
    var attempt = 0
    var runId = newId()
    fireTranscript { transcript.onTurnStarted(runId, text) }
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
            appendAssistantText(attemptRunId, "\n[tool:$name $args]")
            fireTranscript { transcript.onToolDone(attemptRunId, index, id, name, args.toString()) }
          }
        }
      }
      try {
        provider.stream(request).collect { event ->
          coroutineContext.ensureActive()
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
              // Provider-internal retry notice: surface progress, keep the turn alive.
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
      } catch (e: Exception) {
        failed = StreamEvent.Failed(e.message ?: "provider error", retryable = true)
        flushPendingTools()
      }
      val failure = failed
      if (failure == null) {
        fireTranscript { transcript.onTurnSucceeded(attemptRunId, assistantTextOf(attemptRunId)) }
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
      fireTranscript { transcript.onTurnRetried(attemptRunId, attempt, retryConfig.maxRetries, waitMs) }
      try {
        sleeper(waitMs)
      } catch (e: CancellationException) {
        throw e
      }
      runId = newId()
      fireTranscript { transcript.onTurnStarted(runId, text) }
    }
  }

  /**
   * Maps the current (settled) UI history plus this turn's images to one
   * [ChatRequest]. The current user message is already in [uiState] (appended
   * by [send] before the turn starts), so images attach to the last user line.
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
    return ChatRequest(model = model, messages = base)
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
