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
