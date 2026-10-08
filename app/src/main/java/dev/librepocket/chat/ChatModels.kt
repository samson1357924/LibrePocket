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
)

data class ChatUiState(
  val messages: List<UiMessage>,
  val status: ChatStatus,
  val pendingSteerCount: Int,
  val error: String? = null,
)

/**
 * A queued-but-unstarted follow-up intent (N1 ownership).
 *
 * [TurnController.startOrEnqueue] returns [TurnStart.Queued] for text that is
 * only sitting in the FIFO: no `chat.send` gate has passed and no user message
 * was appended. Unlike [TurnStart.Started], Queued is NOT acceptance — the
 * intent must stay recoverable across endpoint teardown instead of vanishing
 * with `close()`. The nullable [opId] is the caller's operation identity (null
 * only for legacy callers that never provided one).
 *
 * Images are intentionally not carried: every current admission path queues
 * text only (`emptyList()` images), so image turns never sit in the FIFO and
 * there is nothing to reclaim. If image queueing is ever introduced, this
 * type must grow before teardown can claim to preserve queued work.
 */
data class QueuedIntent(
  val opId: Long?,
  val text: String,
)
