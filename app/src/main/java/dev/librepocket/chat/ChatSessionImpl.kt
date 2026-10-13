package dev.librepocket.chat

import dev.librepocket.policy.PolicyStore
import dev.librepocket.provider.ChatImage
import dev.librepocket.provider.ChatMessage
import dev.librepocket.provider.LlmProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow

/**
 * Product [ChatSession]: thin lifecycle wrapper around [TurnController].
 * Owns the session scope; [close] shuts the controller down and releases it.
 *
 * The controller consumes the unified M1 [dev.librepocket.provider.StreamEvent]
 * union via [LlmProvider] directly; [model] selects the ChatRequest model and
 * [imageLoader] maps [ChatImageRef] paths to [ChatImage] bytes.
 *
 * Ephemeral time context is passed through to [TurnController]. Prefer passing
 * `sessionStart` from the persisted `SessionMeta.createdAt` (see `ChatSessionFactory`,
 * which reuses the original creation instant on resume; defaults to null, which omits
 * the `Session started` line, for call compatibility).
 * [systemZone] is re-read every turn when no explicit timezone is set, so a
 * mid-session system timezone change is picked up on the next turn.
 *
 * Resumed history is passed through to [TurnController] the same way: the
 * caller (see `ChatSessionFactory.open`) restores the stored user/assistant
 * prefix (excluding partial rows) so the first request after resume already
 * carries prior context.
 */
class ChatSessionImpl(
  provider: LlmProvider,
  policy: PolicyStore,
  transcript: TranscriptSink = NoOpTranscriptSink(),
  retryConfig: TurnRetryConfig = TurnRetryConfig(),
  dispatcher: CoroutineDispatcher = Dispatchers.Default,
  sleeper: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
  newId: () -> String = { java.util.UUID.randomUUID().toString() },
  model: String = TurnController.DEFAULT_MODEL,
  imageLoader: (List<ChatImageRef>) -> List<ChatImage> = ::defaultChatImageLoader,
  clock: java.time.Clock = java.time.Clock.systemDefaultZone(),
  userTimezone: String? = null,
  sessionStart: java.time.Instant? = null,
  systemZone: () -> java.time.ZoneId = java.time.ZoneId::systemDefault,
  initialHistory: List<ChatMessage> = emptyList(),
  historyCap: HistoryWindowCap = HistoryWindowCap.Unbounded,
) : ChatSession {
  private val controller = TurnController(
    provider = provider,
    policy = policy,
    transcript = transcript,
    retryConfig = retryConfig,
    dispatcher = dispatcher,
    sleeper = sleeper,
    newId = newId,
    model = model,
    imageLoader = imageLoader,
    clock = clock,
    userTimezone = userTimezone,
    sessionStart = sessionStart,
    systemZone = systemZone,
    initialHistory = initialHistory,
    historyCap = historyCap,
  )

  @Suppress("unused")
  private val sessionScope = CoroutineScope(SupervisorJob() + dispatcher)

  override val uiState: StateFlow<ChatUiState> = controller.uiState

  /** R5-5: live send-time per-request trim count (see [TurnController.requestTrimmedCount]). */
  override val requestTrimmedCount: StateFlow<Int> = controller.requestTrimmedCount

  override suspend fun send(text: String, images: List<ChatImageRef>) = controller.send(text, images)

  override suspend fun startTurn(text: String, images: List<ChatImageRef>) = controller.startTurn(text, images)

  override suspend fun startOrEnqueue(
    text: String,
    images: List<ChatImageRef>,
    opId: Long?,
  ) = controller.startOrEnqueue(text, images, opId)

  override fun drainQueued(): List<QueuedIntent> = controller.drainQueued()

  override fun cancel() = controller.cancel()

  override suspend fun flush(timeoutMs: Long): Boolean = controller.flushTranscript(timeoutMs)

  override fun hasDurableFailures(): Boolean = controller.hasDurableFailures()

  override suspend fun flushDurable(timeoutMs: Long): Boolean = controller.flushDurable(timeoutMs)

  override fun steer(text: String) = controller.steer(text)

  override suspend fun settlePendingAdmission(timeoutMs: Long): Boolean =
    controller.settlePendingAdmission(timeoutMs)

  override fun hasPendingAdmission(): Boolean = controller.hasPendingAdmission()

  /** R19-1: live unsettled-receipt state for the close-barrier guard (see [ChatSession.hasUnsettledCancelReceipts]). */
  override fun hasUnsettledCancelReceipts(): Boolean = controller.hasUnsettledCancelReceipts()

  /** Phase 1 SEALED truth contract for the close-barrier guard (see [ChatSession.hasSealedUncompensatedReceipts]). */
  override fun hasSealedUncompensatedReceipts(): Boolean = controller.hasSealedUncompensatedReceipts()

  /** R6-3: live pending core set for the streaming-switch fence (see [ChatSession.pendingTranscriptRunIds]). */
  override fun pendingTranscriptRunIds(): List<String> = controller.pendingTranscriptRunIds()

  /** R10-2: live writer-sealed state for the backfill gate's expiry promotion proof. */
  override fun isTranscriptSealed(): Boolean = controller.isTranscriptSealed()

  /** R14-1: live writer-completion state for the backfill gate's expiry dead proof. */
  override fun isTranscriptWriterCompleted(): Boolean = controller.isTranscriptWriterCompleted()

  override fun close() {
    controller.close()
    sessionScope.cancel()
  }
}
