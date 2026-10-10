package dev.librepocket.provider

/** Wire protocol selector (SPEC §1.2). Baseline is [CHAT_COMPLETIONS]. */
enum class ProviderProtocol {
    CHAT_COMPLETIONS,
    RESPONSES,
    ANTHROPIC,
}

/** Protocol-agnostic chat message.
 *
 * @param hasReasoning source marker only (no reasoning payload is carried):
 *   true means this history item came from a thinking/reasoning turn whose
 *   opaque payload (Responses reasoning items, Anthropic thinking/signature
 *   blocks) cannot be rebuilt statelessly. Adapters fail closed on true
 *   (see `requireNoReasoningHistory`). Defaults to false: zero behavior change.
 */
data class ChatMessage(
    val role: String, // "system" | "user" | "assistant" | "tool"
    val text: String,
    val images: List<ChatImage> = emptyList(),
    val toolCallId: String? = null, // required when role == "tool"
    val toolCalls: List<ToolCall> = emptyList(), // assistant-carried tool calls
    val hasReasoning: Boolean = false,
)

/**
 * Raw image bytes (never pre-scaled; the fallback policy decides).
 *
 * Equality is by content so tests and diffing behave sanely.
 */
data class ChatImage(
    val bytes: ByteArray,
    val mimeType: String, // "image/png" | "image/jpeg" | "image/webp"
    val preserveOriginal: Boolean = true, // true = no transcode/scale; over-limit => error, never degrade
    val widthPx: Int? = null, // known edge length when the caller decoded bounds; null = unknown
    val heightPx: Int? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChatImage) return false
        return mimeType == other.mimeType &&
            preserveOriginal == other.preserveOriginal &&
            widthPx == other.widthPx &&
            heightPx == other.heightPx &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + preserveOriginal.hashCode()
        result = 31 * result + (widthPx ?: 0)
        result = 31 * result + (heightPx ?: 0)
        return result
    }
}

data class ToolCall(val id: String, val name: String, val argumentsJson: String)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSchema> = emptyList(), // P1 allows empty; loop starts with pure chat
    val maxTokens: Int? = null,
    val temperature: Float? = null,
    val systemPromptOverride: String? = null,
    /** 服务端工具（默认空 = 关闭；开启后请求体会透传 hosted tool 字段）。 */
    val serverTools: List<ServerTool> = emptyList(),
    /** Thinking/reasoning mode request (null = non-thinking, default). */
    val thinking: ThinkingConfig? = null,
)

data class ToolSchema(val name: String, val description: String, val jsonSchema: String)

/**
 * Minimal thinking-mode marker (Stage 4 fail-closed gate).
 *
 * Null (default) = non-thinking request: zero behavior change. Any non-null
 * value means the caller wants thinking/reasoning mode; adapters currently
 * refuse such requests statelessly via `requireNoReasoningHistory` because
 * thinking histories need opaque passthrough (not yet supported). The budget
 * field is reserved for a future passthrough; it is never serialized today.
 */
data class ThinkingConfig(val budgetTokens: Int? = null)

/**
 * HTTP transport knobs (global defaults, not overridable per request).
 *
 * Retries are owned by [dev.librepocket.chat.TurnRetryConfig]. The deprecated
 * retry fields remain in the constructor for source/config compatibility but
 * are intentionally ignored by providers; changing them does not change the
 * number of requests in a logical turn.
 *
 * @param readTimeoutMs idle timeout waiting for the next SSE chunk,
 *   not a whole-turn deadline. Cancellation closes the active call immediately.
 */
data class ProviderHttpConfig(
    val connectTimeoutMs: Long = 15_000,
    val writeTimeoutMs: Long = 30_000,
    val readTimeoutMs: Long = 300_000,
    @Deprecated("Provider retries are disabled; configure whole-turn retries in TurnRetryConfig.")
    val maxRetries: Int = 3,
    @Deprecated("Provider retries are disabled; configure whole-turn retries in TurnRetryConfig.")
    val retryDelaysMs: List<Long> = listOf(2_000, 4_000, 8_000),
)

data class ProviderConfig(
    val id: String, // stable UUID; the keystore keys off this
    val label: String, // display name
    val baseUrl: String, // must be https (§7.2); adapters append the full path
    val protocol: ProviderProtocol,
    val apiKeyRef: String, // keystore reference, never plaintext
    val http: ProviderHttpConfig = ProviderHttpConfig(),
)
