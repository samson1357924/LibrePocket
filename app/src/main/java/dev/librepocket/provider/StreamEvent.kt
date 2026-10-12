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

    /**
     * Opaque reasoning presence marker (with no replayable payload).
     *
     * Emitted when a protocol surface proves a reasoning origin with no
     * replayable payload (Responses `reasoning` output items, Anthropic
     * `thinking` / `redacted_thinking` blocks / `signature_delta`). Carries
     * no text, so display and persistence behavior are unchanged; it only
     * lets the turn controller flag the attempt's assistant block as
     * reasoning-sourced.
     */
    data object ReasoningPresent : StreamEvent

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

    /** Terminal: failure. [retryable] tells the turn controller whether to retry.
     *
     * [code] carries the typed provider-failure name (e.g. `"AGG_TOO_LARGE"`)
     * when the failure originates from a typed `ProviderFailure` via the
     * streaming transport; null for legacy/classified failures. The transport
     * fills it in `streamingFlow` (`code?.name` or inferred from the
     * `TOOL_ARGS_TOO_LARGE` / `SSE_FRAME_TOO_LARGE` / `SSE_LINE_TOO_LONG`
     * markers) and `pumpSse` sets it for SSE budget overflows. TurnController
     * uses `code` plus the same message markers to suppress
     * poison-adjacent pending-tool flushes without changing retry semantics.
     */
    data class Failed(val message: String, val retryable: Boolean, val code: String? = null) : StreamEvent

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
 *
 * Companion scopes live beside it: [requireTotalToolAggBudget] (same budget
 * summed across ALL tool indexes per attempt) and [requireToolCountBudget]
 * ([MAX_TOOL_CALLS_PER_ATTEMPT] distinct tools per attempt). All three share
 * one failure semantics (message + code + retryable=false).
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

/**
 * Per-attempt whole-round tool aggregation budget (chars summed across ALL
 * tool indexes' `args + id + name` fragments in one attempt, estimated ×3
 * to bytes).
 *
 * A per-call budget alone cannot stop a hostile stream that fans out across
 * many small tool calls, each under its own budget: without a round total,
 * N distinct calls × just-under-budget fragments grow memory without bound.
 * The total therefore reuses the SAME 1 MiB memory budget
 * ([MAX_TOOL_CALL_AGG_BYTES]) and the SAME typed failure
 * ([TOOL_ARGS_TOO_LARGE] / [ProviderFailureCode.AGG_TOO_LARGE],
 * retryable=false) — one semantics, two scopes (per-call + per-attempt
 * total), so no second error meaning is introduced. [ToolDone] arrivals
 * never decrement it (monotonic per attempt); terminal `finish()` /
 * `finishPendingTools()` traversals only read already-bounded buffers.
 *
 * @throws ProviderFailure non-retryable, [ProviderFailureCode.AGG_TOO_LARGE].
 */
internal fun requireTotalToolAggBudget(totalChars: Int, incomingChars: Int) =
    requireToolAggBudget(totalChars, incomingChars)

/**
 * Conservative per-attempt distinct-tool cap: at most this many tool indexes
 * (Chat Completions `index` / Responses `item_id` / Anthropic block `index`
 * / TurnController `toolIndex`) may appear in one attempt.
 *
 * No measured multi-tool fan-out budget exists yet (TODO #16: measure real
 * session fan-out before fixing a dedicated number), so the value is
 * deliberately conservative: legitimate single-turn fan-out is single
 * digits, and 64 leaves ample headroom while bounding map/entry growth
 * against a distinct-index flood. Exceeding it throws the SAME
 * non-retryable typed [ProviderFailure] ([TOOL_ARGS_TOO_LARGE] /
 * [ProviderFailureCode.AGG_TOO_LARGE]) as the char budgets — one semantics
 * for every aggregation overflow.
 */
internal const val MAX_TOOL_CALLS_PER_ATTEMPT = 64

/**
 * Fail-closed distinct-tool guard: [distinctCount] is the attempt's tool
 * count AFTER admitting the new index (i.e. callers pass `size + 1` for a
 * genuinely new key). Callers check BEFORE `getOrPut`/insert so the
 * overflowing entry is never buffered.
 *
 * @throws ProviderFailure non-retryable, [ProviderFailureCode.AGG_TOO_LARGE].
 */
internal fun requireToolCountBudget(distinctCount: Int) {
    if (distinctCount > MAX_TOOL_CALLS_PER_ATTEMPT) {
        throw ProviderFailure(false, TOOL_ARGS_TOO_LARGE, code = ProviderFailureCode.AGG_TOO_LARGE)
    }
}
