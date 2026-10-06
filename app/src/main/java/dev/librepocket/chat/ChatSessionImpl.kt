package dev.librepocket.chat

import dev.librepocket.policy.PolicyStore
import dev.librepocket.provider.ChatImage
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
  )

  @Suppress("unused")
  private val sessionScope = CoroutineScope(SupervisorJob() + dispatcher)

  override val uiState: StateFlow<ChatUiState> = controller.uiState

  override suspend fun send(text: String, images: List<ChatImageRef>) = controller.send(text, images)

  override fun cancel() = controller.cancel()

  override fun steer(text: String) = controller.steer(text)

  override fun close() {
    controller.close()
    sessionScope.cancel()
  }
}
