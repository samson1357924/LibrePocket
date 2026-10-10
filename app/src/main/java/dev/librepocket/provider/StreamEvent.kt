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

/**
 * Per-tool-call aggregation budget (chars across `args + id + name`
 * fragments for one tool index, estimated ×3 to bytes).
 *
 * The value reuses the measured single-SSE-line memory budget
 * ([SseFrameParser.MAX_LINE_BYTES]): no per-tool measured budget exists yet
 * (TODO #16: measure real tool-payload sizes across sessions before fixing a
 * dedicated number), so one tool call's aggregate may never exceed what a
 * single line may already hold. Exceeding it throws a non-retryable typed
 * [ProviderFailure] ([TOOL_ARGS_TOO_LARGE]) instead of silently truncating.
 */
internal const val MAX_TOOL_CALL_AGG_BYTES = 1024 * 1024

/** Typed aggregation-overflow failure, distinct from malformed/ignored payloads and `SSE_TRUNCATED`. */
internal const val TOOL_ARGS_TOO_LARGE = "TOOL_ARGS_TOO_LARGE"

/**
 * Fail-closed aggregation guard shared by every ToolDelta→ToolDone
 * aggregator (all three protocol mappers plus TurnController's collector).
 * [currentChars] is the already-buffered `args + id + name` length for one
 * tool index, [incomingChars] the fragments about to be appended. The ×3
 * char→byte estimate mirrors [SseFrameParser]'s worst-case CJK heuristic so
 * the same 1 MiB memory budget holds on every path.
 *
 * @throws ProviderFailure non-retryable, [ProviderFailureCode.AGG_TOO_LARGE].
 */
internal fun requireToolAggBudget(currentChars: Int, incomingChars: Int) {
    val estimatedBytes = (currentChars.toLong() + incomingChars.toLong()) * 3L
    if (estimatedBytes > MAX_TOOL_CALL_AGG_BYTES) {
        throw ProviderFailure(false, TOOL_ARGS_TOO_LARGE, code = ProviderFailureCode.AGG_TOO_LARGE)
    }
}
