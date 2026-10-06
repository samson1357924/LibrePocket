package dev.librepocket.provider

/**
 * Unified stream event model (P1 SPEC §2).
 *
 * All three wire protocols (Chat Completions / Responses / Anthropic Messages)
 * are projected into this single sealed type. UI, storage and retry layers
 * only consume [StreamEvent] and never touch protocol-native JSON.
 *
 * Projection rules (cross-protocol):
 * - When the visible kind switches (text <-> reasoning <-> tool), the previous
 *   visible block is finalized; later same-kind content never backfills it.
 * - [Done] is the success terminal; [Failed] the failure terminal.
 * - [Failed.message] must not leak keys, URL tokens or body text
 *   (providers only emit codes/reasons plus short sanitized snippets).
 */
sealed interface StreamEvent {
    /** Visible body delta, already split by block identity (SPEC §3.5). */
    data class TextDelta(val round: Int, val blockIndex: Int, val delta: String) : StreamEvent

    /** Visible reasoning delta (reasoning_content / summary / thinking text). */
    data class ReasoningDelta(val round: Int, val blockIndex: Int, val delta: String) : StreamEvent

    /** Tool-call argument delta, aggregated by index; terminal state is [ToolDone]. */
    data class ToolDelta(
        val toolIndex: Int,
        val idChunk: String?,
        val nameChunk: String?,
        val argsChunk: String,
    ) : StreamEvent

    /** One tool call finished, carrying the final aggregated id + arguments. */
    data class ToolDone(
        val toolIndex: Int,
        val id: String,
        val name: String,
        val argumentsJson: String,
    ) : StreamEvent

    /** Usage of this turn (kept for billing/context accounting; P1 records only). */
    data class Usage(val inputTokens: Int?, val outputTokens: Int?) : StreamEvent

    /** Terminal: success, carrying the finish reason. */
    data class Done(val finishReason: String) : StreamEvent

    /** Terminal: failure. [retryable] tells the turn controller whether to retry. */
    data class Failed(val message: String, val retryable: Boolean) : StreamEvent

    /** Retry notice (attempt counter + wait); UI shows "retrying i/n...". */
    data class Retrying(val attempt: Int, val maxAttempts: Int, val delayMs: Long) : StreamEvent
}
