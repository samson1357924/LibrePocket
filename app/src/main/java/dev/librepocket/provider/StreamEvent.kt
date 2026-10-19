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
     * [code] carries the typed failure-code name (e.g. `"AGG_TOO_LARGE"`
     * or `"UNKNOWN_RUNTIME"`) when the failure originates from a typed
     * `ProviderFailure` or the unknown-runtime fallback via the streaming
     * transport; null for legacy/classified failures. The transport fills it
     * in `streamingFlow` (`code?.name` or inferred from the
     * `TOOL_ARGS_TOO_LARGE` / `TEXT_TOO_LARGE` / `SSE_FRAME_TOO_LARGE` / `SSE_LINE_TOO_LONG`
     * markers; unknown runtime fallback uses `UNKNOWN_RUNTIME`; `pumpSse`
     * throws typed `ProviderFailure` for SSE budget overflows which
     * `streamingFlow` projects to `code`). TurnController uses `code` plus
     * the same message markers to suppress poison-adjacent pending-tool
     * flushes without changing retry semantics.
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
 * summed across ALL tool indexes per attempt), [requireToolCountBudget]
 * ([MAX_TOOL_CALLS_PER_ATTEMPT] distinct admissions per attempt — the
 * collections it bounds are listed on that constant), and
 * [requireToolIdBudget] ([MAX_TOOL_ID_CHARS] per single Responses
 * String key — the orthogonal length half of the admission guard). All four
 * share one failure semantics (message + code + retryable=false).
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
 * never decrement it; the total is monotonic per attempt in Chat
 * Completions / Responses / TurnController, while `AnthropicMapper` retires
 * a replaced TOOL block's chars on TOOL→TOOL re-start / TOOL→non-TOOL
 * demotion so its total stays equal to the LIVE buffered sum (see
 * `AnthropicMapper`). Terminal `finish()` / `finishPendingTools()`
 * traversals only read already-bounded buffers.
 *
 * @throws ProviderFailure non-retryable, [ProviderFailureCode.AGG_TOO_LARGE].
 */
// Deliberate delegation: same 1 MiB, same semantics; do not tune independently (see TODO #16).
internal fun requireTotalToolAggBudget(totalChars: Int, incomingChars: Int) =
    requireToolAggBudget(totalChars, incomingChars)

/**
 * Conservative per-attempt distinct-admission budget — a memory bound on
 * fail-closed per-attempt collections, not a tool-count feature limit. At
 * most this many keys may be admitted per attempt into any of:
 * - Chat Completions `tools` (streamed tool `index`), Responses
 *   `callsByItem` (`item_id`) and TurnController `distinctToolIndexes`
 *   (`toolIndex`): one entry per distinct tool index / item id;
 * - Anthropic `blocks` (content block `index`): one entry per distinct
 *   index of ANY kind (TEXT / THINKING / TOOL / OTHER), so a flood of
 *   non-tool blocks cannot grow the map freely; the TOOL-only tally
 *   `toolBlockCount` shares the same cap and is not re-billed when one
 *   index restarts as TOOL;
 * - Responses `emittedToolIds`: one entry per distinct (`toolIndex`, id)
 *   pair already emitted (its `output_item.done` path is pre-checked here;
 *   the `finishPendingTools` drain adds at most one pair per already-capped
 *   `callsByItem` entry, so the set stays within 2x the cap);
 * - Responses `streamedTextByBlock`: one entry per distinct streamed
 *   `output_text` block key; Responses `webSearchStates`: one entry per
 *   distinct web-search item key; the two mixed-identity correlation maps
 *   (`toolKeyByOutputIndex`, `textKeyByOutputIndex`) register at most one
 *   entry per admitted key/index and are likewise capped (best-effort
 *   registration is skipped at the cap).
 * Admissions are checked BEFORE insert, so the overflowing entry is never
 * buffered; a key that only replaces a known one is not re-billed.
 *
 * No measured fan-out budget exists yet (TODO #16: measure real session
 * fan-out before fixing a dedicated number), so the value stays
 * deliberately conservative: legitimate single-turn fan-out is single
 * digits, and 64 leaves ample headroom while bounding map/entry growth
 * against a distinct-index flood. Exceeding it throws the SAME
 * non-retryable typed [ProviderFailure] ([TOOL_ARGS_TOO_LARGE] /
 * [ProviderFailureCode.AGG_TOO_LARGE]) as the char budgets — one semantics
 * for every aggregation overflow.
 */
internal const val MAX_TOOL_CALLS_PER_ATTEMPT = 64

/**
 * Fail-closed distinct-admission guard: [distinctCount] is the admitted
 * collection size AFTER admitting the new key (i.e. callers pass `size + 1`
 * for a genuinely new key — tool index, item id, content block index or an
 * already-emitted pair). Callers check BEFORE `getOrPut`/insert so the
 * overflowing entry is never buffered.
 *
 * @throws ProviderFailure non-retryable, [ProviderFailureCode.AGG_TOO_LARGE].
 */
internal fun requireToolCountBudget(distinctCount: Int) {
    if (distinctCount > MAX_TOOL_CALLS_PER_ATTEMPT) {
        throw ProviderFailure(false, TOOL_ARGS_TOO_LARGE, code = ProviderFailureCode.AGG_TOO_LARGE)
    }
}

/**
 * Per-key length cap (chars) for every Responses String-keyed producer
 * collection: `callsByItem` item ids, emitted `(toolIndex, id)` pair ids,
 * streamed-text block keys (`id:<item_id>#<content_index>`), and web-search
 * item keys. Each single key may hold at most this many chars.
 *
 * 4096 rationale: legitimate fixture ids are all < 20 chars, so 4096 leaves
 * ~200x headroom for real provider ids while bounding retention.
 * Per collection: at most 64 admissions x 4096 chars = 262144 chars
 * (256 Ki chars) of key retention. The four Responses keyed collections
 * (`callsByItem` keys, `emittedToolIds` pair ids — up to 2x the admission cap
 * because the `output_item.done` pre-check and the `finishPendingTools` drain
 * can each admit one pair per item — `streamedTextByBlock` block keys, and
 * `webSearchStates` item keys) merge to at most 1310720 chars worst-case
 * (262144 + 524288 + 262144 + 262144 = 1310720 = 320 x 4096), still ~15x
 * below the pre-fix 19.2M hostile shape (64 x 300KB) and bounded where the
 * pre-fix retention was unbounded. The mixed-identity correlation maps
 * (`toolKeyByOutputIndex`, `textKeyByOutputIndex`) store references to
 * already-gated keys only (plus short synthetic/positional keys), so they add
 * no new horizontal retention. Synthetic fallbacks (`"call_"` + 8 random
 * chars, 13 chars) are far below it and unaffected; empty strings are
 * "absent" identities (never a shared "" bucket) since the P2 identity
 * hardening.
 *
 * Orthogonal dual guard with [requireToolCountBudget]: the count cap bounds
 * HOW MANY keys are admitted, this cap bounds HOW LONG each single key may
 * be. Merely adding key lengths into `totalAggChars` would NOT close
 * `emittedToolIds` — retired old ids leave the live aggregate (equal-length
 * rotation keeps per-candidate growth at 0) while staying retained in the
 * emitted set — so a per-key length cap, not a summed total, is the correct
 * primitive. Same typed non-retryable failure as every other aggregation
 * overflow ([TOOL_ARGS_TOO_LARGE] / [ProviderFailureCode.AGG_TOO_LARGE]),
 * thrown BEFORE insert (BEFORE write-back/emit on the added-carried-id and
 * done paths) so the giant key is never retained, never emitted, and never
 * mutates the aggregate or the round total (fail-closed; on the added path
 * an empty placeholder may remain but holds no giant chars).
 */
internal const val MAX_TOOL_ID_CHARS = 4096

/**
 * Fail-closed per-key length guard: [idLength] is the length of one
 * `item_id` map key or one emitted tool-call id about to be admitted.
 * Callers check BEFORE `getOrPut`/insert (and, on the done path, before
 * any aggregate write-back or emit) so the overflowing key is never
 * retained.
 *
 * @throws ProviderFailure non-retryable, [ProviderFailureCode.AGG_TOO_LARGE].
 */
internal fun requireToolIdBudget(idLength: Int) {
    if (idLength > MAX_TOOL_ID_CHARS) {
        throw ProviderFailure(false, TOOL_ARGS_TOO_LARGE, code = ProviderFailureCode.AGG_TOO_LARGE)
    }
}

/**
 * Per-attempt visible-text budget (chars of `TextDelta` + `ReasoningDelta`
 * accepted into one attempt's assistant block, #83).
 *
 * The value reuses the durable-row cap ([dev.librepocket.session.RoomSessionStore.MAX_TEXT_CHARS]):
 * a text-only attempt that passes this budget fits the store without silent
 * truncation, so exceeding it fails closed here instead. Tool-marker text
 * (`"\n[tool:...]"`) is excluded — it has its own per-call / round-total /
 * distinct-index budgets above; a marker-heavy attempt may still hit the
 * store truncate path (pre-existing residual, disclosed in
 * `TurnController`, not silently introduced here).
 *
 * Same failure semantics as every other aggregation overflow (message +
 * [ProviderFailureCode.AGG_TOO_LARGE], retryable=false): never silent
 * truncation, never a retry of the poisoned stream. Checked BEFORE the delta
 * is buffered or displayed, so the tripping delta is never retained.
 * Per-attempt isolated (retries start fresh, like the tool budgets).
 */
internal const val MAX_TURN_TEXT_CHARS = 100_000

/** Typed visible-text overflow failure, distinct from `TOOL_ARGS_TOO_LARGE` but the same code. */
internal const val TEXT_TOO_LARGE = "TEXT_TOO_LARGE"

/**
 * Fail-closed visible-text guard: [currentChars] is the already-accepted
 * `TextDelta` + `ReasoningDelta` length for one attempt, [incomingChars] the
 * delta about to be appended. Plain chars (the store cap counts chars, not
 * the tool path's x3 CJK byte estimate).
 *
 * @throws ProviderFailure non-retryable, [ProviderFailureCode.AGG_TOO_LARGE].
 */
internal fun requireTurnTextBudget(currentChars: Int, incomingChars: Int) {
    if (currentChars.toLong() + incomingChars.toLong() > MAX_TURN_TEXT_CHARS) {
        throw ProviderFailure(false, TEXT_TOO_LARGE, code = ProviderFailureCode.AGG_TOO_LARGE)
    }
}
