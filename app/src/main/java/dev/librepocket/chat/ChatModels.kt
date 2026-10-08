package dev.librepocket.chat

/** Image reference for a turn. Path must live in the app-private area. */
data class ChatImageRef(
  val filePath: String,
  val mimeType: String,
  val preserveOriginal: Boolean = true,
)

enum class ChatStatus { IDLE, STREAMING, CANCELLED, ERROR, WAITING_STEERED }

data class UiMessage(
  val id: String,
  val role: String,
  val text: String,
  val isPartial: Boolean,
  /** In-memory admission identity; not persisted or sent to the provider. */
  val operationId: Long? = null,
)

data class ChatUiState(
  val messages: List<UiMessage>,
  val status: ChatStatus,
  val pendingSteerCount: Int,
  val error: String? = null,
  /** True when a non-empty FIFO is retained for explicit recovery (policy failure or cancellation). */
  val queuedRecoveryRequired: Boolean = false,
)

/**
 * A queued-but-unstarted follow-up intent (N1 ownership).
 *
 * [TurnController.startOrEnqueue] returns [TurnStart.Queued] for text that is
 * only sitting in the FIFO: no `chat.send` gate has passed and no user message
 * was appended. [TurnStart.NeedsRecovery] atomically transfers intents left by
 * a previous promotion whose fresh policy check denied or failed to the
 * caller; the current admission is not
 * accepted. If recovery is discovered under the second admission lock, the
 * current admission may already have passed one fresh policy gate, so retrying
 * after recovery evaluates policy again. Neither result accepts the returned/
 * queued work — it must stay recoverable across endpoint teardown instead of
 * vanishing with `close()`. The nullable [opId] is the caller's operation
 * identity (null only for legacy callers that never provided one).
 *
 * Images are intentionally not carried. [TurnController.startOrEnqueue]
 * rejects a non-empty image list with [IllegalStateException] if a turn is
 * already active, including the post-policy-gate race; it never accepts an
 * image-bearing intent into the FIFO. The caller retains both text and images
 * after that exception. Idle image turns remain supported. If image queueing is
 * introduced later, this type must grow before the controller can accept it.
 */
data class QueuedIntent(
  val opId: Long?,
  val text: String,
)
