package dev.librepocket.provider

import dev.librepocket.redact.Redactor
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import okhttp3.OkHttpClient

/**
 * Typed non-retryable terminal for a function-call frame with no stable
 * identity (empty/absent `item_id` and item `id`, and no `output_index`).
 * Dropping such a frame would lose its args while the `added` bucket still
 * drains; failing typed keeps the poisoned attempt observable and
 * non-retryable.
 */
private const val RESPONSES_IDENTITY_MISSING = "RESPONSES_IDENTITY_MISSING"

/** Legacy-shaped declared item id (`output_1`) used only for collision detection. */
private val POSITIONAL_LABEL = Regex("^output_(\\d+)$")

/** Frames that end the response and must honour a latched identity conflict. */
private val RESPONSES_TERMINAL_TYPES = setOf(
    "response.completed",
    "response.failed",
    "response.incomplete",
    "error",
)

/**
 * Authoritative per-block `*.done` frames. After a conflict is latched they
 * project the same immediate non-retryable mismatch, even if their text
 * payload is missing, instead of returning empty and waiting for a terminal.
 */
private val RESPONSES_TEXT_DONE_TYPES = setOf(
    "response.output_text.done",
    "response.content_part.done",
    "response.output_item.done",
)

/**
 * Responses adapter (SPEC §3.1–§3.2).
 *
 * - `POST {base}/responses` with `"stream": true, "store": false`;
 *   never sends `previous_response_id`.
 * - Phase 2 history (stateless): every request self-contains the full
 *   pairing — user/assistant `{"type":"message"}` items, assistant-carried
 *   calls as `{"type":"function_call"}` (`call_id`/`name`/`arguments`),
 *   tool turns as `{"type":"function_call_output"}` (`call_id`/`output`).
 *   Nothing is read back from a server store (`store=false`), so a truncated
 *   history without its call side fails closed instead of being sent.
 *   Non-thinking only; reasoning-bearing histories fail closed
 *   (`THINKING_ROUND_TRIP_NOT_SUPPORTED`, checked first in `buildBody`).
 * - `response.output_text.delta` -> [StreamEvent.TextDelta] with
 *   `blockIndex = output_index * 1000 + content_index` (§3.5 mapping).
 * - `reasoning_summary_text.delta` / `reasoning_text.delta` ->
 *   [StreamEvent.ReasoningDelta].
 * - `response.output_item.added/done` (function_call) -> [StreamEvent.ToolDelta] /
 *   [StreamEvent.ToolDone]. Bucket identity is the non-empty `item_id`, else
 *   the nested item `id`, else `output_<output_index>` (present on every item
 *   event); a frame with no identity at all fails closed typed
 *   (`RESPONSES_IDENTITY_MISSING`) instead of random-bucketing `added` while
 *   dropping the matching argument frames.
 * - `response.function_call_arguments.done` carries the authoritative full
 *   `arguments` for one call: it replaces (never appends to) buffered deltas,
 *   latches the call's args as final so later deltas cannot mutate them, and
 *   emits no [StreamEvent.ToolDone] of its own — closure stays on
 *   `output_item.done` / `response.completed` / the EOF drain.
 * - Stream-scoped `output_text.done` / `content_part.done` / message
 *   `output_item.done` reconcile through the same text buffer, register their
 *   `(output_index, content_index)` position, promote an id-less positional
 *   prefix onto the now-declared id, and close the block; a later delta for a
 *   closed block fails closed typed non-retryable. A `response.completed`
 *   array index is never registered as a stream position.
 * - `response.completed` is the authoritative terminal; an empty `output`
 *   array recovers from already-streamed deltas (P1 simplification), while a
 *   non-empty `output` is reconciled item-by-item against the streamed
 *   projection (P1-1). Streamed `output_text` characters are buffered per
 *   block within bounded caps; a terminal text that contradicts its streamed
 *   prefix fails closed typed and non-retryable
 *   (`RESPONSES_OUTPUT_MISMATCH`). Text blocks are matched by non-empty item
 *   id (`item_id` on the delta, `item.id` on the terminal item) with
 *   `output_index` / array position as the fallback, so a reordered terminal
 *   `output` array cannot compare one item's text against another's prefix; a
 *   stream that never carries ids falls back to the documented positional
 *   shape (streamed `output_index` == terminal array index). Terminal-only
 *   function calls are admitted through the same budgets as
 *   `output_item.done` and emitted once each; already-emitted pairs are
 *   replays and never re-emit.
 * - `response.failed` / `response.incomplete` -> [StreamEvent.Failed] (with a
 *   preceding [StreamEvent.Usage] when `response.usage` is present, P2-8);
 *   `incomplete_details` is read as an object (`{"reason":…}`) with the old
 *   string shape kept as fallback (P2-6). A top-level `error` envelope
 *   classifies `error.code`/`error.type` structurally (P1-4).
 * - web-search lifecycle markers are deduped per item (`item_id` / `id` /
 *   `output_<output_index>`), emitting only when the item's state changes
 *   (P2-7).
 * - Opaque payloads (encrypted reasoning etc.) are never surfaced as text:
 *   a `reasoning` output item only emits [StreamEvent.ReasoningPresent]
 *   (presence marker, no payload). Visible reasoning deltas still surface as
 *   [StreamEvent.ReasoningDelta]; only opaque items skip text conversion.
 */
class ResponsesProvider(
    private val config: ProviderConfig,
    private val apiKey: suspend () -> CharArray?,
    client: OkHttpClient? = null,
) : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.RESPONSES
    private val httpClient: OkHttpClient by lazy { providerTransportClient(client ?: defaultOkHttpClient(config.http)) }

    override fun stream(request: ChatRequest): Flow<StreamEvent> = streamingFlow {
        val key = apiKey() ?: throw ProviderFailure(false, "HTTP 401 missing_api_key")
        try {
            val body = buildBody(request)
            val headers = linkedMapOf(
                "Content-Type" to "application/json",
                "Accept" to "text/event-stream",
                "Authorization" to "Bearer ${String(key)}",
            )
            val mapper = ResponsesMapper()
            // A conflict latched by the mapper poisons the attempt even if the
            // transport dies before a terminal frame arrives. In that case the
            // failure is the non-retryable output mismatch, never a retryable
            // SSE_TRANSPORT (which would replay the deterministic malformed
            // stream). Mapper/downstream exceptions stay transparent — the
            // rewritten branch is reachable ONLY with a latched conflict,
            // since `mapper.finish()` returns emptyList once the terminal is
            // reached. Cancellation/VM-fatal interruptions are never
            // swallowed (`Error` covers AssertionError/LinkageError/
            // VirtualMachineError); an interrupted pump rethrows after
            // restoring the thread's interrupt flag.
            try {
                pumpSse(httpClient, postJson(endpoint(config.baseUrl), headers, body)) { frame ->
                    emitAll(mapper.mapPayload(frame.data))
                    if (mapper.terminalReached) SsePumpDecision.STOP else SsePumpDecision.CONTINUE
                }
                emitAll(mapper.finish())
            } catch (e: Throwable) {
                if (e is CancellationException || e is Error) throw e
                if (e is InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
                if (!mapper.hasLatchedConflict()) throw e
                emitAll(mapper.finish())
            }
        } finally {
            key.fill('\u0000')
        }
    }

    override suspend fun listModels(): List<String> {
        val key = apiKey() ?: throw ProviderFailure(false, "HTTP 401 missing_api_key")
        try {
            val url = joinEndpoint(config.baseUrl, "/models")
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${String(key)}")
                .get()
                .build()
            return executeProviderRequest(httpClient, req) { resp, cancelCall ->
                if (!resp.isSuccessful) {
                    val errorPrefix = readProviderErrorPrefix(resp.body, cancelCall).orEmpty()
                    val kind = ProviderErrorClassifier.classify(resp.code, null, errorPrefix)
                    throw ProviderFailure(
                        kind == FailureKind.RETRYABLE,
                        redactedError("HTTP ${resp.code}", errorPrefix),
                    )
                }
                val text = resp.body?.let { readBoundedProviderBody(it, cancelCall) }.orEmpty()
                extractModelIds(text)
            }
        } finally {
            key.fill('\u0000')
        }
    }

    internal fun endpoint(base: String): String = joinEndpoint(base, "/responses")

    internal fun buildBody(request: ChatRequest): String {
        requireNoReasoningHistory(request)
        requireNoAssistantImages(request.messages)
        val sb = StringBuilder()
        sb.append("{\"model\":${q(request.model)},\"stream\":true,\"store\":false")
        request.maxTokens?.let { sb.append(",\"max_output_tokens\":$it") }
        request.temperature?.let { sb.append(",\"temperature\":$it") }
        val webSearch = request.webSearchOrNull()
        // 服务端搜索会把提示词送往厂商 hosted 检索：开启时先过 Redactor 脱敏；
        // 关闭时保持原样（零行为变化）。
        val serverSearch = webSearch != null
        val instructions = ArrayList<String>()
        if (request.systemPromptOverride != null) instructions.add(request.systemPromptOverride)
        for (m in request.messages) if (m.role == "system") instructions.add(m.text)
        if (instructions.isNotEmpty()) {
            val joined = instructions.joinToString("\n")
            val safe = if (serverSearch) Redactor.redact(joined).text else joined
            sb.append(",\"instructions\":${q(safe)}")
        }
        if (request.tools.isNotEmpty() || webSearch != null) {
            sb.append(",\"tools\":[")
            var ti = 0
            request.tools.forEach { t ->
                if (ti > 0) sb.append(',')
                ti++
                sb.append(responsesFunctionToolJson(t))
            }
            if (webSearch != null) {
                if (ti > 0) sb.append(',')
                sb.append(webSearchToolJson(webSearch.contextSize))
            }
            sb.append(']')
        }
        sb.append(",\"input\":[")
        var first = true
        fun emit(fragment: String) {
            if (!first) sb.append(',')
            first = false
            sb.append(fragment)
        }
        val resolved = validateToolPairing(request.messages, false)
        for ((i, m) in request.messages.withIndex()) {
            if (m.role == "system") continue
            if (m.role == "tool") {
                // Phase 2: tool turns are function_call_output paired by call_id,
                // never rewritten to user. Illegal pairings fail closed.
                val callId = resolved[i]!!
                val safeText = if (serverSearch) Redactor.redact(m.text).text else m.text
                emit(responsesFunctionCallOutputJson(callId, safeText))
                continue
            }
            if (m.role == "assistant" && m.toolCalls.isNotEmpty()) {
                val safeText = if (serverSearch) Redactor.redact(m.text).text else m.text
                if (safeText.isNotEmpty() || m.images.isNotEmpty()) {
                    emit(responsesMessageItem("assistant", safeText, m.images))
                }
                for (tc in m.toolCalls) {
                    emit(responsesFunctionCallJson(tc))
                }
                continue
            }
            val safeText = if (serverSearch) Redactor.redact(m.text).text else m.text
            emit(responsesMessageItem(if (m.role == "assistant") "assistant" else m.role, safeText, m.images))
        }
        sb.append("]}")
        return sb.toString()
    }

    /** `{"type":"message",…}` item with optional `input_image` parts. */
    internal fun responsesMessageItem(role: String, text: String, images: List<ChatImage>): String {
        val sb = StringBuilder()
        sb.append("{\"type\":\"message\",\"role\":${q(role)},")
        sb.append("\"content\":[{\"type\":\"input_text\",\"text\":${q(text)}}")
        val infos = images.map { ImageFallbackPolicy.fromChatImage(it) }
        when (val d = ImageFallbackPolicy.decide(infos, supportsImages = true)) {
            is ImageFallbackPolicy.Decision.Reject ->
                throw ProviderFailure(false, d.reason)
            is ImageFallbackPolicy.Decision.StripAll -> Unit
            is ImageFallbackPolicy.Decision.Send -> {
                val encoder = java.util.Base64.getEncoder()
                for (idx in d.indices) {
                    sb.append(",{\"type\":\"input_image\",\"image_url\":")
                    sb.append(q("data:${images[idx].mimeType};base64,${encoder.encodeToString(images[idx].bytes)}"))
                    sb.append('}')
                }
            }
        }
        sb.append("]}")
        return sb.toString()
    }
}

/** Pure Responses envelope projection; no I/O, JVM-testable. */
internal class ResponsesMapper(val round: Int = 0) {
    private class AggCall(
        var id: String = "",
        var name: String = "",
        val args: StringBuilder = StringBuilder(),
        var toolIndex: Int = -1,
        // P1-2: set by `response.function_call_arguments.done` — the buffered
        // args are the authoritative full payload and later deltas/fulls may
        // not mutate them.
        var argsFinal: Boolean = false,
        // P1B-B: an authoritative `output_item.done` already closed this call.
        // Argument frames arriving after closure are malformed identity churn,
        // not replayable deltas (terminal `response.completed` replays still
        // compare against the emitted fingerprint below).
        var closed: Boolean = false,
    )

    /**
     * P1B-B bounded fingerprint retained for emit-once / terminal-replay
     * conflict checks. It deliberately does NOT keep a second copy of the tool
     * args: rotation of call ids previously retained one full payload per
     * emitted pair, bypassing the aggregate budget. The fingerprint covers
     * the name plus canonical (whitespace/key-order-insensitive) args and
     * their length.
     */
    private class EmittedTool(val fingerprint: String)

    private val callsByItem = LinkedHashMap<String, AggCall>()
    // Mixed-identity correlation (best-effort, bounded by the same cap): an
    // id-carrying frame registers `output_index -> bucket key`, so a later
    // argument/done frame that omits the id but carries the same
    // `output_index` joins the identified call instead of opening a positional
    // phantom bucket. Horizontal retention is bounded because the key was
    // already length-gated by `requireFunctionCallKey`; registration is
    // skipped once the cap is reached (adversarial index churn degrades to
    // the positional fallback), so the map never grows unbounded.
    private val toolKeyByOutputIndex = LinkedHashMap<Int, String>()
    private var nextToolIndex = 0
    // Per-attempt whole-round total across all item_ids (Finding #1),
    // monotonic: ToolDone arrivals never decrement it.
    private var totalAggChars = 0
    private var usage: StreamEvent.Usage? = null
    private var terminal: StreamEvent? = null
    // P1-1: streamed visible text per `output_text` block, replayed to
    // reconcile `response.completed`'s non-empty `output`. Key identity is the
    // non-empty `item_id` (`item.id` on the terminal item), falling back to
    // `output_<output_index>#<content_index>` (see [textBlockKey]) so a
    // reordered terminal array reconciles by stable item identity instead of
    // array position. Bounded three times: at most
    // MAX_TOOL_CALLS_PER_ATTEMPT distinct block keys, MAX_TOOL_ID_CHARS per
    // key, and MAX_TURN_TEXT_CHARS tracked chars. On a count/char breach
    // tracking stops (`textTrackingDisabled`), the maps are dropped, and a
    // later non-empty terminal text fails closed (RESPONSES_OUTPUT_MISMATCH)
    // instead of guessing a prefix that was never retained; an overlong key
    // throws the same typed non-retryable per-key overflow as every other
    // Responses keyed collection, before any retention.
    private val streamedTextByBlock = LinkedHashMap<String, StringBuilder>()
    // Sticky mixed-identity correlation: every streamed text block registers
    // its `(output_index, content_index)` position (id-less deltas to the
    // positional key, id-carrying deltas to the id key; an id appearing after
    // a positional prefix promotes that prefix onto the id key). A terminal
    // message item that omits `id` therefore rejoins the streamed prefix by
    // position instead of emitting the full terminal text as a duplicate
    // suffix, and a suffix keeps the streamed block's `output_index` even
    // when the terminal array is reordered. Registration is skipped once the
    // cap is reached (the maps are dropped with text tracking); the map is
    // bounded by the same per-attempt block cap.
    private val textKeyByOutputIndex = LinkedHashMap<Pair<Int, Int>, String>()
    private var streamedTextChars = 0
    private var textTrackingDisabled = false
    // A second DISTINCT non-empty item id at one (output_index, content_index)
    // is malformed identity churn. Keep the sticky first key and latch the
    // conflict so terminal reconciliation fails closed (never merges msg_b's
    // suffix into msg_a's buffer and then accepts msg_b by position). Sticky
    // for the attempt: disabling text tracking must never clear it.
    private var textIdentityConflict = false
    // Blocks that an authoritative `*.done` frame already reconciled. A later
    // delta for the same block is malformed (the provider declared the block
    // final) and must fail mismatch instead of appending after closure.
    // Bounded by the same per-attempt block cap as the text buffers: once the
    // set is full a further distinct done would be untrackable, so the
    // reconciliation fails closed rather than risking an append after close.
    private val closedTextBlocks = LinkedHashSet<String>()
    // Sticky tool-identity churn: a second declared item id at one
    // output_index, a terminal replay whose name/args contradict the emitted
    // pair, or a terminal call that would overwrite another call's bucket.
    // Every terminal path fails closed with RESPONSES_OUTPUT_MISMATCH before
    // draining/emitting anything.
    private var toolIdentityConflict = false
    // P1-1: any presence marker already emitted during streaming
    // (reasoning item added/done); completed reconciliation emits at most
    // one more marker for terminal-only reasoning items.
    private var reasoningPresentSeen = false
    // P2-7: last emitted web-search state per item key, so a repeated
    // lifecycle frame for the same item emits only on a state change.
    private val webSearchStates = LinkedHashMap<String, String>()
    // Sticky fatal-parser-breach latch (fail-closed): once a frame exceeds
    // MiniJson.MAX_DEPTH or MiniJson.MAX_NODES, mapPayload throws typed
    // non-retryable and every later mapPayload rethrows the same way, so no
    // later terminal frame can become Done; finish() reports the typed
    // failure instead of SSE_TRUNCATED. Set only from the
    // MiniJsonFatalException branch below.
    private var jsonFatalFailed = false
    var terminalReached: Boolean = false
        private set

    /**
     * True once an identity/output contradiction latched, even if no terminal
     * event was projected yet. The provider stream uses this to turn a
     * subsequent pump/transport failure into the non-retryable mismatch
     * instead of a retryable `SSE_TRANSPORT`.
     */
    internal fun hasLatchedConflict(): Boolean = textIdentityConflict || toolIdentityConflict

    fun mapPayload(payload: String): List<StreamEvent> {
        if (jsonFatalFailed) throw ProviderFailure(false, TOOL_ARGS_TOO_LARGE, code = ProviderFailureCode.AGG_TOO_LARGE)
        if (terminal != null) return emptyList()
        val root = try {
            MiniJson.parse(payload) as? MiniJson.JObj ?: return emptyList()
        } catch (e: MiniJson.MiniJsonException) {
            if (e is MiniJson.MiniJsonFatalException) {
                // A fatal parser breach (depth or node-count ceiling) is NOT a
                // droppable malformed frame: dropping it would lose its delta
                // and let a later terminal succeed without it (fake success),
                // or degrade at EOF to a retryable SSE_TRUNCATED that retries
                // the poisoned stream. Fail the attempt with the same typed
                // non-retryable failure as every aggregation-budget overflow;
                // streamingFlow converts the throw into a single Failed
                // terminal. Other malformed input still drops just this frame
                // below.
                jsonFatalFailed = true
                throw ProviderFailure(false, TOOL_ARGS_TOO_LARGE, code = ProviderFailureCode.AGG_TOO_LARGE)
            }
            return emptyList()
        }
        val type = root.string("type") ?: return emptyList()
        val out = ArrayList<StreamEvent>()
        // A latched identity conflict poisons the attempt. Terminal frames and
        // authoritative `*.done` frames must still project exactly ONE
        // RESPONSES_OUTPUT_MISMATCH immediately (even when their payload is
        // absent); every other frame is suppressed so no text/tool/marker is
        // emitted after the contradiction. Projecting here also precedes all
        // per-key guards, so an overlong id on a suppressed later frame can
        // never turn the latched mismatch into AGG_TOO_LARGE. Setting the
        // terminal latch makes the projection happen only once.
        if (textIdentityConflict || toolIdentityConflict) {
            if (type in RESPONSES_TERMINAL_TYPES || type in RESPONSES_TEXT_DONE_TYPES) {
                return projectLatchedConflict(out)
            }
            return emptyList()
        }
        // 服务端搜索生命周期事件：固定走 TextDelta 标记，不进本地 Tool 聚合。
        // P2-7: same-item repeats collapse (last state per item, bounded map).
        if (type.startsWith("response.web_search_call.")) {
            val state = webSearchStateOf(type.substringAfterLast('.'))
            emitWebSearchMarker(root, state, out)
            return out
        }
        when (type) {
            "response.output_text.delta" -> {
                val delta = root.string("delta") ?: return emptyList()
                // Empty-delta suppression (parity with ChatCompletions): an
                // empty frame yields NO event, so it can never park an empty
                // block in the UI or masquerade as final-answer evidence
                // downstream (continuation completeness, maintainer P1).
                if (delta.isNotEmpty()) {
                    val hadConflict = textIdentityConflict || toolIdentityConflict
                    val outputIndex = root.int("output_index")
                    val contentIndex = root.int("content_index") ?: 0
                    val position = outputIndex?.let { it to contentIndex }
                    val priorPositionKey = position?.let { textKeyByOutputIndex[it] }
                    val blockKey =
                        streamingTextKey(root.string("item_id"), outputIndex, contentIndex)
                    // Same per-key length guard as the tool maps, before any
                    // retention: a frame-budget-sized item_id can never be
                    // stored as a map key.
                    requireToolIdBudget(blockKey.length)
                    if (hadConflict || textIdentityConflict) {
                        // No emit after a sticky conflict. Keep the text
                        // tracking budget accounting alive so an overflow can
                        // still disable tracking — that must never clear the
                        // latched conflict.
                        trackStreamedText(blockKey, delta)
                        return emptyList()
                    }
                    // An authoritative `*.done` already closed this block.
                    // Appending now would resurrect final text; fail closed
                    // instead (the mismatch is projected exactly once).
                    val positionalProbe = textBlockKey(null, outputIndex, contentIndex)
                    if (textBlockIsClosed(blockKey) ||
                        textBlockIsClosed(positionalProbe) ||
                        (priorPositionKey != null && textBlockIsClosed(priorPositionKey))
                    ) {
                        return projectLatchedConflict(out)
                    }
                    trackStreamedText(blockKey, delta)
                    out.add(StreamEvent.TextDelta(round, blockOf(root), delta))
                }
            }
            "response.output_text.done" -> {
                // Authoritative text for one streamed block. Reconcile through
                // the same buffer as completed: emit only the missing suffix
                // and advance the buffer so a later completed item is a no-op.
                val text = root.string("text") ?: return emptyList()
                val failed = reconcileTextBlock(
                    root.string("item_id"),
                    root.int("output_index"),
                    root.int("content_index") ?: 0,
                    text,
                    out,
                    streamScoped = true,
                )
                if (failed != null) {
                    terminal = failed
                    out.add(failed)
                    terminalReached = true
                }
            }
            "response.content_part.done" -> {
                val part = root.obj("part") ?: return emptyList()
                val partType = part.string("type")
                if (partType != "output_text" && partType != "text") return emptyList()
                val text = part.string("text") ?: return emptyList()
                val failed = reconcileTextBlock(
                    root.string("item_id"),
                    root.int("output_index"),
                    root.int("content_index") ?: 0,
                    text,
                    out,
                    streamScoped = true,
                )
                if (failed != null) {
                    terminal = failed
                    out.add(failed)
                    terminalReached = true
                }
            }
            "response.reasoning_summary_text.delta",
            "response.reasoning_text.delta",
            -> {
                val delta = root.string("delta") ?: return emptyList()
                // Same suppression: an empty reasoning frame would flip the
                // reasoning-source flag downstream (hasReasoning) with zero
                // payload; it is dropped here instead.
                if (delta.isNotEmpty()) out.add(StreamEvent.ReasoningDelta(round, blockOf(root), delta))
            }
            "response.output_item.added" -> {
                val item = root.obj("item") ?: return emptyList()
                if (item.string("type") == "web_search_call") {
                    emitWebSearchMarker(root, "started", out)
                    return out
                }
                if (item.string("type") != "function_call") {
                    // Opaque reasoning items carry no replayable text: record
                    // presence only, so history rebuilds fail closed instead of
                    // replaying a reasoning origin as plain text.
                    if (item.string("type") == "reasoning") {
                        reasoningPresentSeen = true
                        out.add(StreamEvent.ReasoningPresent)
                    }
                    return out
                }
                // P3-11/P2 identity: empty strings fall through to a
                // previously registered `output_index` bucket, then to the
                // positional `output_<output_index>` fallback, so `added` and
                // the matching argument frames agree on one bucket; a frame
                // with no identity at all fails closed typed instead of
                // random-bucketing here while dropping the args. The S1
                // per-key length guard runs inside requireFunctionCallKey
                // BEFORE any retention (map key or correlation entry).
                val itemId = requireFunctionCallKey(root, item)
                if (toolIdentityConflict) return emptyList()
                if (!callsByItem.containsKey(itemId)) requireToolCountBudget(callsByItem.size + 1)
                val agg = callsByItem.getOrPut(itemId) { AggCall(toolIndex = nextToolIndex++) }
                val addedId = item.string("call_id")?.takeIf { it.isNotEmpty() }
                val addedName = item.string("name")?.takeIf { it.isNotEmpty() }
                // S1 per-key fail-fast on the carried call_id: when agg.id is
                // still empty, the incoming addedId WOULD become the retained
                // id and the emitted (toolIndex, id) pair. A 10KB call_id fits
                // the per-call/total char budgets, so check its length BEFORE
                // the char/total guards and BEFORE write-back. On overflow the
                // giant call_id is never retained (the empty AggCall placeholder
                // may remain but holds no id chars, emits nothing, and drains
                // nothing); first-wins resends (agg.id already set) skip this.
                // Cap value and rationale live with MAX_TOOL_ID_CHARS in
                // StreamEvent.kt.
                if (addedId != null && agg.id.isEmpty()) requireToolIdBudget(addedId.length)
                val addedIncoming =
                    (if (addedId != null && agg.id.isEmpty()) addedId.length else 0) +
                        (if (addedName != null && agg.name.isEmpty()) addedName.length else 0)
                // First-wins candidate check BEFORE write-back: buffered id/name
                // already present make the incoming side 0, so resends never
                // grow the aggregate (Finding #5).
                requireToolAggBudget(
                    agg.id.length + agg.name.length + agg.args.length,
                    addedIncoming,
                )
                requireTotalToolAggBudget(totalAggChars, addedIncoming)
                if (addedId != null && agg.id.isEmpty()) agg.id = addedId
                if (addedName != null && agg.name.isEmpty()) agg.name = addedName
                totalAggChars += addedIncoming
                out.add(StreamEvent.ToolDelta(agg.toolIndex, agg.id.ifEmpty { null }, agg.name.ifEmpty { null }, ""))
            }
            "response.function_call_arguments.delta" -> {
                // Same identity order (declared id -> registered output_index
                // -> positional) and the same per-key length guard as added.
                val itemId = requireFunctionCallKey(root)
                if (toolIdentityConflict) return emptyList()
                val existing = callsByItem[itemId]
                // P1B-B: the call was authoritatively closed by
                // output_item.done. A post-close delta is identity churn, not a
                // replay: fail closed immediately instead of mutating the
                // emitted aggregate.
                if (existing?.closed == true) {
                    toolIdentityConflict = true
                    return projectLatchedConflict(out)
                }
                val frag = root.string("delta").orEmpty()
                // Empty fragments are dropped (parity with text/reasoning) and
                // must not park an empty bucket.
                if (frag.isEmpty()) return emptyList()
                // P1-2: once arguments.done latched the authoritative full
                // payload, later deltas are replays and must neither mutate
                // the aggregate nor surface a ToolDelta.
                if (existing?.argsFinal == true) return emptyList()
                if (existing == null) requireToolCountBudget(callsByItem.size + 1)
                val agg = existing ?: callsByItem.getOrPut(itemId) { AggCall(toolIndex = nextToolIndex++) }
                // Fail-closed: a hostile stream of tiny fragments must not
                // grow one call's aggregate without bound (typed, non-retryable).
                requireToolAggBudget(agg.id.length + agg.name.length + agg.args.length, frag.length)
                requireTotalToolAggBudget(totalAggChars, frag.length)
                agg.args.append(frag)
                totalAggChars += frag.length
                out.add(StreamEvent.ToolDelta(agg.toolIndex, null, null, frag))
            }
            "response.function_call_arguments.done" -> {
                // P1-2: authoritative full arguments for one call. Key by the
                // same stable identity as added/delta (non-empty item_id, else
                // the registered/correlated output_index, else positional;
                // empty strings fall through) and admit exactly like the delta
                // path so a terminal-only full still consumes its
                // distinct-item slot before insert.
                val itemId = requireFunctionCallKey(root)
                if (toolIdentityConflict) return emptyList()
                val existing = callsByItem[itemId]
                // P1B-B: arguments.done after output_item.done is malformed,
                // even when it repeats the same full payload.
                if (existing?.closed == true) {
                    toolIdentityConflict = true
                    return projectLatchedConflict(out)
                }
                if (existing == null) requireToolCountBudget(callsByItem.size + 1)
                val agg = existing ?: callsByItem.getOrPut(itemId) { AggCall(toolIndex = nextToolIndex++) }
                val full = root.string("arguments") ?: return emptyList()
                if (agg.argsFinal) {
                    // Duplicate authoritative full: same semantics is a replay
                    // no-op; any contradiction fails closed (the old code
                    // silently kept the first value and could execute stale
                    // args).
                    if (!toolArgsEquivalent(agg.args.toString(), full)) {
                        toolIdentityConflict = true
                        return projectLatchedConflict(out)
                    }
                    return emptyList()
                }
                // Budget the whole replacement candidate BEFORE mutation:
                // per-call + round total (monotonic growth — a shorter
                // authoritative full never decrements the total). Overflow
                // throws typed with no mutation and no emit.
                val candidateTotal = agg.id.length + agg.name.length + full.length
                requireToolAggBudget(0, candidateTotal)
                val oldBuffered = agg.id.length + agg.name.length + agg.args.length
                val growth = (candidateTotal - oldBuffered).coerceAtLeast(0)
                requireTotalToolAggBudget(totalAggChars, growth)
                agg.args.setLength(0)
                agg.args.append(full)
                agg.argsFinal = true
                totalAggChars += growth
                // No immediate ToolDone: closure still comes from
                // output_item.done / response.completed / the drain.
            }
            "response.output_item.done" -> {
                val item = root.obj("item") ?: return emptyList()
                if (item.string("type") == "web_search_call") {
                    if (textIdentityConflict || toolIdentityConflict) return emptyList()
                    emitWebSearchMarker(root, "completed", out)
                    return out
                }
                if (item.string("type") == "message") {
                    // Authoritative message done: reconcile through the same
                    // streamed-text buffer as `response.completed`, emitting
                    // only a missing suffix (never the full duplicate).
                    val failed = reconcileMessageItem(item, root.int("output_index"), out, streamScoped = true)
                    if (failed != null) {
                        terminal = failed
                        out.add(failed)
                        terminalReached = true
                    }
                    return out
                }
                if (item.string("type") != "function_call") {
                    if (textIdentityConflict || toolIdentityConflict) return emptyList()
                    // Same opaque-reasoning presence rule as added (above).
                    if (item.string("type") == "reasoning") {
                        reasoningPresentSeen = true
                        out.add(StreamEvent.ReasoningPresent)
                    }
                    return out
                }
                if (textIdentityConflict || toolIdentityConflict) return emptyList()
                val itemId = requireFunctionCallKey(root, item)
                // P1B-B: the done frame is authoritative. A conflict in its
                // identity resolution must project the non-retryable mismatch
                // NOW, not at EOF (reviewer P3).
                if (toolIdentityConflict) return projectLatchedConflict(out)
                val newId = item.string("call_id")?.takeIf { it.isNotEmpty() }
                val newName = item.string("name")?.takeIf { it.isNotEmpty() }
                val full = item.string("arguments")
                val existing = callsByItem[itemId]
                // A call_id may not be re-hosted into another item bucket /
                // toolIndex: that would let one call-id execute twice.
                if (newId != null &&
                    callsByItem.values.any { it !== existing && it.id == newId }
                ) {
                    toolIdentityConflict = true
                    return projectLatchedConflict(out)
                }
                if (existing == null) requireToolCountBudget(callsByItem.size + 1)
                val agg = existing ?: callsByItem.getOrPut(itemId) { AggCall(toolIndex = nextToolIndex++) }
                // Keep the historical guard priority: an oversized terminal
                // call_id still fails typed before argument reconciliation.
                if (newId != null) requireToolIdBudget(newId.length)
                // The terminal full must not contradict an authoritative
                // arguments.done full or the already-streamed delta buffer.
                // Structural comparison ignores whitespace/key order.
                if (!full.isNullOrEmpty() && (agg.argsFinal || agg.args.isNotEmpty()) &&
                    !toolArgsEquivalent(agg.args.toString(), full)
                ) {
                    toolIdentityConflict = true
                    return projectLatchedConflict(out)
                }
                // args non-empty => terminal full is ignored (no double-count);
                // otherwise the single terminal payload IS the args. P1-2: an
                // argsFinal pair latched by arguments.done is never clobbered.
                val authoritativeArgs =
                    if (!agg.argsFinal && agg.args.isEmpty() && !full.isNullOrEmpty()) full else null
                // A done replay contradicts an already-emitted pair => latch.
                // No ToolDone is surfaced by this branch on conflict.
                val ok = closeFunctionCall(
                    agg,
                    newId,
                    newName,
                    authoritativeArgs,
                    out,
                    observedArgs = full,
                )
                if (!ok) return projectLatchedConflict(out)
            }
            "response.completed" -> {
                // A latched identity conflict dominates the terminal: no
                // usage, pending-tool drain, suffix or Done may be emitted.
                if (latchedConflict(out)) return out
                val response = root.obj("response")
                val output = response?.arr("output")?.items
                val terminalUsage = usageOf(response)
                if (output == null || output.isEmpty()) {
                    // Authoritative terminal with empty output: recover from the
                    // already-streamed deltas (recorded by upper layers). The
                    // legacy drain stays, except for an id-only call whose only
                    // safe shape is fail-closed (no executable empty ToolDone).
                    if (hasUnsafeIdOnlyPendingTool()) {
                        val failed = outputMismatch()
                        terminal = failed
                        out.add(failed)
                        terminalReached = true
                        return out
                    }
                    terminalUsage?.let {
                        usage = it
                        out.add(it)
                    }
                    finishPendingTools(out)
                    terminal = StreamEvent.Done("stop")
                    out.add(terminal!!)
                    terminalReached = true
                    return out
                }
                // P1-1: non-empty authoritative output. Reconcile item-by-item
                // in array order; text differences surface only as the missing
                // suffix, terminal-only calls are admitted through the same
                // guard chain as output_item.done, and a contradiction latches
                // a typed non-retryable failure instead of a second Done.
                // Effects are staged: a conflict discovered on a later item
                // must not leave earlier suffixes/ToolDones in the output.
                val reconciled = ArrayList<StreamEvent>()
                val emittedBefore = emittedCalls.keys.toSet()
                val representedPairs = HashSet<Pair<Int, String>>()
                var mismatch: StreamEvent.Failed? = null
                for ((itemIndex, j) in output.withIndex()) {
                    val item = j as? MiniJson.JObj ?: continue
                    when (item.string("type")) {
                        "message" ->
                            mismatch = reconcileMessageItem(item, itemIndex, reconciled, streamScoped = false)
                        "function_call" ->
                            mismatch = reconcileFunctionCallItem(
                                item,
                                itemIndex,
                                reconciled,
                                representedPairs,
                            )
                        "reasoning" -> if (!reasoningPresentSeen) {
                            reasoningPresentSeen = true
                            reconciled.add(StreamEvent.ReasoningPresent)
                        }
                    }
                    if (mismatch != null || textIdentityConflict || toolIdentityConflict) break
                }
                if (mismatch == null && (textIdentityConflict || toolIdentityConflict)) {
                    mismatch = outputMismatch()
                }
                if (mismatch == null && hasPendingUnemittedTool()) {
                    // A non-empty authoritative output that omits a buffered,
                    // not-yet-emitted call is a contradiction, not a licence to
                    // silently drain/execute it.
                    mismatch = outputMismatch()
                }
                if (mismatch == null && emittedBefore.any { it !in representedPairs }) {
                    // Every already-emitted pair must be represented in the
                    // non-empty authoritative output (a same-item/call replay
                    // counts). Otherwise TurnController would execute a tool
                    // the provider's terminal no longer acknowledges.
                    mismatch = outputMismatch()
                }
                if (mismatch != null) {
                    terminal = mismatch
                    out.add(mismatch)
                    terminalReached = true
                    return out
                }
                terminalUsage?.let {
                    usage = it
                    out.add(it)
                }
                out.addAll(reconciled)
                terminal = StreamEvent.Done("stop")
                out.add(terminal!!)
                terminalReached = true
            }
            "response.failed" -> {
                if (latchedConflict(out)) return out
                val response = root.obj("response")
                usageOf(response)?.let {
                    usage = it
                    out.add(it)
                }
                val err = response?.obj("error") ?: root.obj("error")
                val msg = err?.string("message") ?: "RESPONSE_FAILED"
                val kind = ProviderErrorClassifier.classify(
                    null,
                    null,
                    msg,
                    structuredCode = err?.string("code"),
                    structuredType = err?.string("type"),
                )
                terminal = StreamEvent.Failed(redactedError("RESPONSE_FAILED", msg), kind == FailureKind.RETRYABLE)
                out.add(terminal!!)
                terminalReached = true
            }
            "response.incomplete" -> {
                if (latchedConflict(out)) return out
                val response = root.obj("response")
                usageOf(response)?.let {
                    usage = it
                    out.add(it)
                }
                // P2-6: incomplete_details is an object ({"reason":…}); the old
                // string shape stays a fallback. The reason rides the terminal
                // message (e.g. "RESPONSE_INCOMPLETE max_output_tokens").
                val reason = response?.obj("incomplete_details")?.string("reason")
                    ?: response?.string("incomplete_details")
                terminal = if (reason != null) {
                    StreamEvent.Failed(redactedError("RESPONSE_INCOMPLETE", reason), retryable = false)
                } else {
                    StreamEvent.Failed("RESPONSE_INCOMPLETE", retryable = false)
                }
                out.add(terminal!!)
                terminalReached = true
            }
            "error" -> {
                if (latchedConflict(out)) return out
                // P1-4: top-level Responses error envelope (type=="error"),
                // optionally nested ({"error":{...}}). One redacted terminal
                // whose retryability comes from the structured code/type
                // (e.g. invalid_api_key -> fatal, overloaded_error -> retryable).
                val err = root.obj("error") ?: root
                val msg = err.string("message") ?: "RESPONSE_ERROR"
                val kind = ProviderErrorClassifier.classify(
                    null,
                    null,
                    msg,
                    structuredCode = err.string("code"),
                    structuredType = err.string("type"),
                )
                terminal = StreamEvent.Failed(redactedError("RESPONSE_ERROR", msg), kind == FailureKind.RETRYABLE)
                out.add(terminal!!)
                terminalReached = true
            }
        }
        return out
    }

    fun finish(): List<StreamEvent> {
        // A latched fatal parser breach (depth or node count) dominates the
        // honest-EOF path: the poisoned attempt reports the typed
        // non-retryable failure, never a retryable SSE_TRUNCATED (which would
        // retry the poisoned stream) and never a success. Carries the
        // AGG_TOO_LARGE code so TurnController's pending-flush guard treats it
        // like every other budget overflow.
        if (jsonFatalFailed) {
            terminalReached = true
            return listOf(
                StreamEvent.Failed(
                    TOOL_ARGS_TOO_LARGE,
                    retryable = false,
                    code = ProviderFailureCode.AGG_TOO_LARGE.name,
                ),
            )
        }
        terminal?.let { return emptyList() } // already terminal
        if (textIdentityConflict || toolIdentityConflict) {
            // Sticky identity conflict at honest EOF: never SSE_TRUNCATED
            // (retrying a deterministic malformed stream) and never a drain.
            val failed = outputMismatch()
            terminal = failed
            terminalReached = true
            return listOf(failed)
        }
        if (hasUnsafeIdOnlyPendingTool()) {
            // An id-only buffered call has no safe ToolDone shape (empty name
            // and args would be executable garbage). Fail closed instead of
            // leaving a dangling ToolDelta under a retryable truncation.
            val failed = outputMismatch()
            terminal = failed
            terminalReached = true
            return listOf(failed)
        }
        val out = ArrayList<StreamEvent>()
        finishPendingTools(out)
        val done = StreamEvent.Failed("SSE_TRUNCATED", retryable = true)
        out.add(done)
        return out
    }

    private fun finishPendingTools(out: MutableList<StreamEvent>) {
        for ((_, agg) in callsByItem) {
            if (agg.closed) continue
            if (agg.args.isNotEmpty() || agg.name.isNotEmpty()) {
                // Synthetic fallback first: "call_"+8 is 13 chars, far below
                // the per-key cap, so this assignment itself never throws —
                // the guard below only fires on a previously admitted giant id.
                if (agg.id.isEmpty()) agg.id = "call_" + UUID.randomUUID().toString().take(8)
                // S1 per-key backstop on the drain: with the added fail-fast
                // above, a giant call_id is normally rejected at added BEFORE
                // write-back, so this is defense-in-depth for any future
                // admission path. Enforced BEFORE emit — overflow throws typed
                // with no emit and no set insert, giant id never retained in
                // the emitted set.
                requireToolIdBudget(agg.id.length)
                // Terminal guard (Finding #4): whole pending ToolDone must fit
                // the per-call budget before emit; fail-closed typed throw.
                requireToolAggBudget(0, agg.id.length + agg.name.length + agg.args.length)
                // Avoid double-emitting tools already closed via output_item.done:
                // callers track ToolDone ids; duplicates collapse downstream by
                // (toolIndex, id). Only emit when never emitted — mappers own
                // this set per stream.
                //
                // Bounded without a local budget check: this drain adds at most
                // one pair per callsByItem entry, every entry's item_id was
                // admitted through the same distinct cap above. finish() sets
                // no terminal latch, so a repeated finish() re-runs this drain;
                // the re-run converges idempotently via the emittedToolIds
                // dedup (already-emitted pairs add nothing), so the set stays
                // within 2x MAX_TOOL_CALLS_PER_ATTEMPT overall (loose safe
                // upper bound, at most 128 total pairs: interleavings such as
                // 64 done-path pairs plus 63 pending drain pairs can exceed
                // 64 actually emitted, but never exceed 128; each pair is
                // emitted at most once).
                if (emittedToolIds.add(agg.toolIndex to agg.id)) {
                    val pair = agg.toolIndex to agg.id
                    val args = agg.args.toString()
                    emittedCalls[pair] = EmittedTool(toolFingerprint(agg.name, args))
                    out.add(StreamEvent.ToolDone(agg.toolIndex, agg.id, agg.name, args))
                }
                agg.closed = true
            }
        }
    }

    private val emittedToolIds = LinkedHashSet<Pair<Int, String>>()
    // Bounded fingerprint per emitted pair, used only to reject a terminal
    // replay whose name/args contradict what was already handed to the caller
    // (a duplicate execution guard). Populated exactly when emittedToolIds
    // grows; never stores another full args/name copy.
    private val emittedCalls = LinkedHashMap<Pair<Int, String>, EmittedTool>()

    /**
     * P2-8: project `response.usage` (shared by completed/failed/incomplete);
     * null when the envelope carries no usage object. Callers emit the result
     * BEFORE the terminal event, so completed ordering is unchanged and
     * failed/incomplete now also carry billing data when the provider sends it.
     */
    private fun usageOf(response: MiniJson.JObj?): StreamEvent.Usage? {
        val u = response?.obj("usage") ?: return null
        return StreamEvent.Usage(u.int("input_tokens"), u.int("output_tokens"))
    }

    /**
     * P1-1: append one streamed `output_text` delta to the reconciliation
     * buffer under both bounds. On any breach tracking is disabled wholesale
     * (map dropped, no further appends): the completed path then refuses a
     * non-empty terminal text because no trustworthy prefix exists. Caps:
     * [MAX_TOOL_CALLS_PER_ATTEMPT] distinct blocks, [MAX_TURN_TEXT_CHARS] chars.
     */
    /** @return true when the delta was retained (false once tracking is disabled). */
    private fun trackStreamedText(blockKey: String, delta: String): Boolean {
        if (textTrackingDisabled) return false
        if (!streamedTextByBlock.containsKey(blockKey) &&
            streamedTextByBlock.size >= MAX_TOOL_CALLS_PER_ATTEMPT
        ) {
            disableTextTracking()
            return false
        }
        if (streamedTextChars.toLong() + delta.length > MAX_TURN_TEXT_CHARS) {
            disableTextTracking()
            return false
        }
        val sb = streamedTextByBlock.getOrPut(blockKey) { StringBuilder() }
        sb.append(delta)
        streamedTextChars += delta.length
        return true
    }

    private fun disableTextTracking() {
        textTrackingDisabled = true
        streamedTextByBlock.clear()
        textKeyByOutputIndex.clear()
        streamedTextChars = 0
        // textIdentityConflict is deliberately NOT cleared: a contradiction
        // observed before the cap breach stays latched for the whole attempt.
        // closedTextBlocks is likewise retained: a delta after an already
        // closed `*.done` must still fail even if the text buffers were
        // dropped by the cap.
    }

    /**
     * P1-1: reconcile one authoritative message item (completed output array
     * or `output_item.done`) against the streamed text buffer. Identity is the
     * non-empty item id (`item_id` on the delta, `id` on the terminal item),
     * falling back to the positional block (`output_index` / array index) for
     * id-less streams — see [textBlockKey]. A terminal text that extends the
     * tracked prefix emits only the missing suffix (equal text emits nothing,
     * so no duplicate); a contradiction — or any non-empty terminal text once
     * tracking was disabled — returns the typed non-retryable mismatch.
     */
    private fun reconcileMessageItem(
        item: MiniJson.JObj,
        outputIndex: Int?,
        out: MutableList<StreamEvent>,
        streamScoped: Boolean,
    ): StreamEvent.Failed? {
        if (textIdentityConflict || toolIdentityConflict) return outputMismatch()
        val content = item.arr("content")?.items.orEmpty()
        val staged = ArrayList<StreamEvent>()
        for ((contentIndex, j) in content.withIndex()) {
            val part = j as? MiniJson.JObj ?: continue
            val partType = part.string("type")
            if (partType != "output_text" && partType != "text") continue
            val terminalText = part.string("text") ?: continue
            val failed = reconcileTextBlock(
                item.string("id"),
                outputIndex,
                contentIndex,
                terminalText,
                staged,
                streamScoped = streamScoped,
            )
            if (failed != null) return failed
        }
        out.addAll(staged)
        return null
    }

    /**
     * Shared authoritative-text reconciliation for `output_text.done`,
     * `content_part.done`, message `output_item.done` and `response.completed`.
     *
     * [streamScoped] distinguishes the two position domains: done-frame
     * `output_index` values are stream positions and are registered into
     * [textKeyByOutputIndex] (and may promote a positional prefix onto a
     * now-declared id), while a `response.completed` array index is NOT a
     * stream `output_index` and is never written back as one.
     *
     * Emits only the missing suffix and advances the buffer (consume-once), so
     * a later completed/message replay is a no-op. A stream-scoped done closes
     * its block: a later delta for the same block fails mismatch instead of
     * appending after closure.
     *
     * Key budget: the id check is applied to the ENCODED block key
     * (`id:<itemId>#<contentIndex>`), not the raw id, because that string is
     * the key that would be retained. The exact boundary is
     * `encoded.length <= MAX_TOOL_ID_CHARS` accepted, `+1` typed
     * non-retryable ([ProviderFailureCode.AGG_TOO_LARGE]); with
     * `content_index == 0` the maximum raw id is
     * `MAX_TOOL_ID_CHARS - 5` (`id:` = 3 chars, `#0` = 2 chars).
     */
    private fun reconcileTextBlock(
        itemId: String?,
        outputIndex: Int?,
        contentIndex: Int,
        terminalText: String,
        out: MutableList<StreamEvent>,
        streamScoped: Boolean,
    ): StreamEvent.Failed? {
        if (textIdentityConflict || toolIdentityConflict) return outputMismatch()
        // Empty strings are the id-less shape: never build an `id:#…` key.
        val idKey = itemId?.takeIf { it.isNotEmpty() }
            ?.let { textBlockKey(it, null, contentIndex) }
        // Same per-key cap as streamingTextKey, BEFORE the key is queried or
        // retained. An overlong terminal id is typed non-retryable and never
        // emitted.
        if (idKey != null) requireToolIdBudget(idKey.length)
        // Always non-null: a done frame with neither id nor output_index uses
        // the stable anonymous positional key `output_0#contentIndex`, and the
        // suffix is written back to it so a repeated done is a no-op.
        val positionalKey = textBlockKey(null, outputIndex, contentIndex)
        if (textTrackingDisabled) {
            if (terminalText.isNotEmpty()) return outputMismatch()
            if (streamScoped) {
                val key = idKey ?: positionalKey
                if (key !in closedTextBlocks && closedTextBlocks.size >= MAX_TOOL_CALLS_PER_ATTEMPT) {
                    return outputMismatch()
                }
                closedTextBlocks.add(key)
                // Keep the position -> closed-key link even though the text
                // buffers were dropped, so an id-less delta at the same stream
                // position is still recognized as post-done.
                outputIndex?.let { registerTextPosition(it to contentIndex, key) }
            }
            return null
        }
        val position = outputIndex?.let { it to contentIndex }
        val correlatedKey = position?.let { textKeyByOutputIndex[it] }
        if (idKey != null && correlatedKey != null && correlatedKey != idKey &&
            !streamedTextByBlock.containsKey(idKey)
        ) {
            if (isPositionalTextKey(correlatedKey)) {
                // An id-less prefix at this position is adopted by the
                // now-declared terminal id (same buffer, no duplicate). A
                // completed array index merely reuses an already-registered
                // stream position for this positional promotion; it never
                // becomes authoritative for that position (registration below
                // is stream-scoped only).
                promoteTextPrefix(correlatedKey, idKey)
            } else if (streamScoped || !streamedTextByBlock.containsKey(idKey)) {
                // A different identified item owns this stream position (or
                // an ambiguous reorder cannot be joined to an existing id
                // buffer): accepting the terminal id would merge/duplicate
                // output.
                textIdentityConflict = true
                return outputMismatch()
            }
        }
        val trackedKey: String? = when {
            idKey != null && streamedTextByBlock.containsKey(idKey) -> idKey
            correlatedKey != null && streamedTextByBlock.containsKey(correlatedKey) -> correlatedKey
            streamedTextByBlock.containsKey(positionalKey) -> positionalKey
            else -> null
        }
        // Terminal-only new blocks (new id or new position) are admitted: emit
        // the full text and write it back to the buffer. Only true identity
        // conflicts fail closed above.
        val keyForBuffer = trackedKey ?: idKey ?: correlatedKey ?: positionalKey
        // A distinct block beyond the retained-block cap cannot be remembered:
        // fail before emitting a suffix that a later duplicate could replay.
        if (streamScoped && keyForBuffer !in closedTextBlocks &&
            closedTextBlocks.size >= MAX_TOOL_CALLS_PER_ATTEMPT
        ) {
            return outputMismatch()
        }
        val streamed = trackedKey?.let { streamedTextByBlock[it]?.toString() }.orEmpty()
        if (!terminalText.startsWith(streamed)) return outputMismatch()
        val suffix = terminalText.substring(streamed.length)
        if (suffix.isNotEmpty()) {
            // Keep the streamed block's own identity for the suffix: a
            // reordered terminal array position is not necessarily the
            // streamed `output_index`.
            val suffixBlock = trackedKey?.let { blockIndexOfTextKey(it) }
                ?: (outputIndex ?: 0) * 1000 + contentIndex
            out.add(StreamEvent.TextDelta(round, suffixBlock, suffix))
            appendReconciledText(keyForBuffer, suffix)
        }
        if (streamScoped) {
            // Register AFTER a successful reconciliation, so a mismatching
            // done never makes its position authoritative. The position map is
            // stream-scoped only; completed array indices never enter it.
            if (position != null) registerTextPosition(position, keyForBuffer)
            closedTextBlocks.add(keyForBuffer)
        }
        return null
    }

    /**
     * Sticky streaming key per `(output_index, content_index)`: an id-less
     * delta followed by an id-carrying delta (or the reverse) must append to
     * ONE block buffer, not split "Hel" + "lo" into two buffers that later
     * compare as a mismatch or duplicate the suffix. When an id first appears,
     * the positional prefix is promoted onto the id key exactly once.
     */
    private fun streamingTextKey(itemId: String?, outputIndex: Int?, contentIndex: Int): String {
        val positionalKey = textBlockKey(null, outputIndex, contentIndex)
        // Empty item_id is the id-less shape everywhere else; filtering it is
        // essential here because textBlockKey("", ...) would otherwise alias
        // every output_index onto output_0#contentIndex.
        val idKey = itemId?.takeIf { it.isNotEmpty() }?.let { textBlockKey(it, null, contentIndex) }
        if (outputIndex == null) {
            if (idKey != null) requireToolIdBudget(idKey.length)
            return idKey ?: positionalKey
        }
        val position = outputIndex to contentIndex
        val existing = textKeyByOutputIndex[position]
        val chosen = when {
            existing == null -> {
                if (idKey != null) promoteTextPrefix(positionalKey, idKey)
                idKey ?: positionalKey
            }
            idKey == null -> existing
            existing == idKey -> existing
            existing == positionalKey -> {
                promoteTextPrefix(positionalKey, idKey)
                idKey
            }
            // A second distinct id at one position is malformed; keep the
            // sticky key and latch the conflict for terminal reconciliation.
            else -> {
                textIdentityConflict = true
                existing
            }
        }
        if (idKey != null) requireToolIdBudget(idKey.length)
        if (existing != null || textKeyByOutputIndex.size < MAX_TOOL_CALLS_PER_ATTEMPT) {
            registerTextPosition(position, chosen)
        }
        return chosen
    }

    private fun promoteTextPrefix(from: String, to: String) {
        if (from == to) return
        streamedTextByBlock.remove(from)?.let { prefix ->
            streamedTextByBlock.getOrPut(to) { StringBuilder() }.append(prefix)
        }
    }

    private fun blockIndexOfTextKey(key: String): Int? =
        textKeyByOutputIndex.entries.firstOrNull { it.value == key }?.key?.let { (oi, ci) ->
            oi * 1000 + ci
        }

    private fun isPositionalTextKey(key: String): Boolean = key.startsWith("output_")

    // P1B-B disjoint tool namespaces: a declared item id is always `id:<raw>`,
    // the output_index fallback is always `pos:<index>`. A legitimate item id
    // such as "output_1" can therefore never alias the positional bucket for
    // output_index 1 (or be guessed positional by a prefix test).
    private fun declaredToolKey(itemId: String): String = "id:$itemId"
    private fun positionalToolKey(outputIndex: Int): String = "pos:$outputIndex"
    private fun isPositionalToolKey(key: String): Boolean = key.startsWith("pos:")

    /** Index already registered for an encoded tool key, if any (bounded scan). */
    private fun registeredToolIndex(key: String): Int? =
        toolKeyByOutputIndex.entries.firstOrNull { it.value == key }?.key

    /**
     * A declared item id literally shaped like the legacy positional label
     * (`output_1`) must not silently merge the call with a real positional
     * bucket for a DIFFERENT output_index. The namespaces are disjoint, but a
     * provider mixing both shapes is malformed and fails closed instead of
     * producing two silently-aliased calls. Same-index promotion is the
     * supported reverse-mixed shape and stays legal.
     */
    private fun positionalNamespaceCollision(declared: String, outputIndex: Int?): Boolean {
        val match = POSITIONAL_LABEL.matchEntire(declared) ?: return false
        val index = match.groupValues[1].toIntOrNull() ?: return false
        if (index.toString() != match.groupValues[1]) return false
        if (index == outputIndex) return false
        val positional = positionalToolKey(index)
        return toolKeyByOutputIndex[index] == positional || callsByItem.containsKey(positional)
    }

    /**
     * Structural equality for authoritative tool args. Raw equality is the
     * fast path; otherwise both sides are canonicalized (sorted object keys,
     * no whitespace) so a replay that only differs in formatting is not
     * mistaken for a contradiction. If either side is not valid JSON the
     * comparison fails closed (raw inequality).
     */
    private fun toolArgsEquivalent(a: String, b: String): Boolean {
        if (a == b) return true
        val ca = canonicalJsonOrNull(a) ?: return false
        val cb = canonicalJsonOrNull(b) ?: return false
        return ca == cb
    }

    private fun canonicalJsonOrNull(raw: String): String? = try {
        buildString { appendCanonicalJson(MiniJson.parse(raw)) }
    } catch (_: MiniJson.MiniJsonException) {
        null
    }

    /**
     * Canonical JSON used for structural tool-args equality and for the
     * emitted-call fingerprint. Object keys are sorted, whitespace is dropped
     * and strings are re-escaped canonically, so a replay that differs only in
     * formatting is not mistaken for a contradiction.
     *
     * Numbers are canonicalized from their RAW literal via
     * `BigDecimal(raw).stripTrailingZeros().toPlainString()` — never from the
     * parsed `Double` ([MiniJson.JNum.v]) and never from the raw text:
     *
     * - `1`, `1.0` and `1e2`/`100` denote the same decimal value and must
     *   compare EQUAL, so an aliasing replay is not reported as a mismatch;
     * - integers above 2^53 (e.g. `9007199254740993`) are NOT representable
     *   as Double, so a Double-based canonical form would fold
     *   `9007199254740993` onto `9007199254740992` and silently accept a
     *   contradictory replay as if it were the same call. Canonicalizing the
     *   exact decimal literal keeps such values DISTINCT.
     *
     * This is replay-identity relevant: the canonical form decides whether a
     * terminal replay is the same tool call (safe to emit) or a contradiction
     * (must fail closed with RESPONSES_OUTPUT_MISMATCH), and it feeds the
     * bounded payload fingerprint that detects a replay with different args.
     */
    private fun StringBuilder.appendCanonicalJson(value: MiniJson.J) {
        when (value) {
            is MiniJson.JObj -> {
                append('{')
                var first = true
                for (key in value.map.keys.sorted()) {
                    if (!first) append(',')
                    first = false
                    appendJsonString(key)
                    append(':')
                    appendCanonicalJson(value.map.getValue(key))
                }
                append('}')
            }
            is MiniJson.JArr -> {
                append('[')
                for ((i, item) in value.items.withIndex()) {
                    if (i > 0) append(',')
                    appendCanonicalJson(item)
                }
                append(']')
            }
            is MiniJson.JStr -> appendJsonString(value.v)
            is MiniJson.JNum -> {
                // Canonical number: the exact decimal literal, never the
                // Double value (see the invariant on appendCanonicalJson).
                val normalized = try {
                    java.math.BigDecimal(value.raw).stripTrailingZeros().toPlainString()
                } catch (_: Throwable) {
                    null
                }
                append(normalized ?: value.raw)
            }
            is MiniJson.JBool -> append(if (value.v) "true" else "false")
            MiniJson.JNull -> append("null")
        }
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\b' -> append("\\b")
                c == '\u000C' -> append("\\f")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
        append('"')
    }

    /**
     * P1B-B bounded payload identity for an emitted tool call: SHA-256 over
     * length-prefixed name + canonical args. The raw args are canonicalized so
     * a terminal replay with another key order/whitespace still matches; if
     * either side is not valid JSON the raw form is used (fail closed on a
     * genuine contradiction, never a false success).
     */
    private fun toolFingerprint(name: String, args: String): String {
        val canonicalArgs = canonicalJsonOrNull(args) ?: args
        val digest = MessageDigest.getInstance("SHA-256")
        digest.updateLengthPrefixed(name)
        digest.updateLengthPrefixed(canonicalArgs)
        return java.util.Base64.getEncoder().encodeToString(digest.digest())
    }

    private fun MessageDigest.updateLengthPrefixed(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        update((bytes.size ushr 24).toByte())
        update((bytes.size ushr 16).toByte())
        update((bytes.size ushr 8).toByte())
        update(bytes.size.toByte())
        update(bytes)
    }

    /** Advance the tracked buffer after a reconciled suffix so a duplicate
     *  terminal item is a replay no-op (never emits the suffix twice). */
    private fun appendReconciledText(key: String, suffix: String) {
        if (textTrackingDisabled) return
        if (!streamedTextByBlock.containsKey(key) &&
            streamedTextByBlock.size >= MAX_TOOL_CALLS_PER_ATTEMPT
        ) {
            // A suffix that cannot be retained could be re-emitted by a later
            // duplicate; disable tracking so any further non-empty terminal
            // text fails closed instead.
            disableTextTracking()
            return
        }
        if (streamedTextChars.toLong() + suffix.length > MAX_TURN_TEXT_CHARS) {
            disableTextTracking()
            return
        }
        streamedTextByBlock.getOrPut(key) { StringBuilder() }.append(suffix)
        streamedTextChars += suffix.length
    }

    /** P1-1: typed non-retryable terminal for a streamed/terminal text contradiction. */
    private fun outputMismatch(): StreamEvent.Failed =
        StreamEvent.Failed("RESPONSES_OUTPUT_MISMATCH", retryable = false)

    /**
     * Project the latched conflict as the attempt's single terminal and set the
     * terminal latch so it can only happen once. Non-terminal suppression is
     * handled at the `mapPayload` entry.
     */
    private fun projectLatchedConflict(out: MutableList<StreamEvent>): List<StreamEvent> {
        val failed = outputMismatch()
        terminal = failed
        out.add(failed)
        terminalReached = true
        return out
    }

    private fun textBlockIsClosed(key: String): Boolean = key in closedTextBlocks

    /** Bounded stream-position registration (id and positional domain only). */
    private fun registerTextPosition(position: Pair<Int, Int>, key: String) {
        if (textKeyByOutputIndex.size < MAX_TOOL_CALLS_PER_ATTEMPT ||
            textKeyByOutputIndex.containsKey(position)
        ) {
            textKeyByOutputIndex[position] = key
        }
    }

    /**
     * Sticky identity conflict check shared by every terminal path. When a
     * conflict is latched the ONLY thing this attempt may project is the
     * non-retryable mismatch: no usage, no pending drain, no ToolDone, no Done.
     */
    private fun latchedConflict(out: MutableList<StreamEvent>): Boolean {
        if (!textIdentityConflict && !toolIdentityConflict) return false
        projectLatchedConflict(out)
        return true
    }

    /** A buffered call that a terminal would otherwise silently drain/execute. */
    private fun hasPendingUnemittedTool(): Boolean =
        callsByItem.values.any { agg ->
            !agg.closed && (agg.toolIndex to agg.id) !in emittedToolIds
        }

    /**
     * P1B-B: an id-only call cannot be drained safely (a ToolDone with an
     * empty name/args would be executable garbage). EOF and the legacy empty
     * `response.completed` drain fail closed instead of leaving a dangling
     * ToolDelta behind a retryable truncation.
     */
    private fun hasUnsafeIdOnlyPendingTool(): Boolean =
        callsByItem.values.any { agg ->
            !agg.closed && agg.id.isNotEmpty() && agg.name.isEmpty() && agg.args.isEmpty()
        }

    /**
     * Reconcile one terminal `function_call` item from `response.completed`.
     * Identity preference:
     * 1. a carried non-empty `call_id` resolves the buffered call first, so a
     *    reordered id-less terminal array cannot cross-wire c2's args into c1;
     * 2. the declared `item_id`/`id` (if buffered);
     * 3. the bucket registered for this array position (mixed identity);
     * 4. the positional `output_<itemIndex>` fallback.
     *
     * A terminal item that declares a different id for an already-identified
     * call/position, that would overwrite another call's bucket, or that
     * replays different name/args for an already-emitted pair latches
     * [toolIdentityConflict] and fails closed. A keyless item with a payload
     * and no call_id is likewise fatal (never silently dropped).
     */
    private fun reconcileFunctionCallItem(
        item: MiniJson.JObj,
        itemIndex: Int,
        out: MutableList<StreamEvent>,
        representedPairs: MutableSet<Pair<Int, String>>? = null,
    ): StreamEvent.Failed? {
        if (textIdentityConflict || toolIdentityConflict) return outputMismatch()
        // The terminal item carries both `item_id` and `id` on the same object.
        val declaredRaw = declaredItemId(item, item)
        if (toolIdentityConflict) return outputMismatch()
        // Per-key guard BEFORE any lookup/promotion can retain the key.
        if (declaredRaw != null) requireToolIdBudget(declaredRaw.length)
        val declaredKey = declaredRaw?.let(::declaredToolKey)
        val callId = item.string("call_id")?.takeIf { it.isNotEmpty() }
        val positionalKey = positionalToolKey(itemIndex)
        val correlatedKey = toolKeyByOutputIndex[itemIndex]
        // B: id-less terminal reordering without arguments cannot be safely
        // paired; fail closed instead of cross-binding name/args.
        if (callId == null && declaredKey == null && correlatedKey != null) {
            val regIdx = registeredToolIndex(correlatedKey)
            if (regIdx != null && regIdx != itemIndex) {
                toolIdentityConflict = true
                return outputMismatch()
            }
        }
        if (callId == null && declaredKey == null &&
            (item.string("arguments")?.isNullOrEmpty() != false) &&
            correlatedKey == null && callsByItem.size >= 2
        ) {
            toolIdentityConflict = true
            return outputMismatch()
        }
        // A terminal array slot owned by an identified call may not open a new
        // call there. A legal reorder is only the case where the terminal's own
        // declared item id is already buffered (or, for an id-less reorder, the
        // carried call_id resolves an existing buffered call). Otherwise the
        // new terminal item would execute a second tool beside the emitted one.
        if (correlatedKey != null && !isPositionalToolKey(correlatedKey) &&
            correlatedKey != declaredKey
        ) {
            val legalDeclaredReorder =
                declaredKey != null && callsByItem.containsKey(declaredKey)
            val legalCallIdReorder = declaredKey == null && callId != null &&
                callsByItem.values.any { it.id == callId }
            // No declared id and no call_id targets the position's own call.
            val replayAtPosition = declaredKey == null && callId == null
            if (!legalDeclaredReorder && !legalCallIdReorder && !replayAtPosition) {
                toolIdentityConflict = true
                return outputMismatch()
            }
        }
        val lookupKey = when {
            declaredKey != null && callsByItem.containsKey(declaredKey) -> declaredKey
            callId != null -> declaredKey ?: correlatedKey ?: positionalKey
            correlatedKey != null -> correlatedKey
            else -> positionalKey
        }
        var existing = findBufferedCall(lookupKey, callId)
        if (existing == null && (declaredKey == null || !callsByItem.containsKey(declaredKey))) {
            // Reverse-mixed identity: an id-less positional bucket at this
            // array position is adopted by the now-declared id (same call),
            // instead of being orphaned and drained as a phantom duplicate.
            existing = findBufferedCall(positionalKey, callId)
        }
        if (existing != null) {
            val existingKey = keyOfToolCall(existing)
            if (declaredKey != null && existingKey != null && existingKey != declaredKey &&
                !isPositionalToolKey(existingKey)
            ) {
                // The same call is now declared under a different stable id.
                toolIdentityConflict = true
                return outputMismatch()
            }
            if (callId != null && existing.id.isNotEmpty() && existing.id != callId) {
                // A terminal replay must not re-identify an already buffered /
                // emitted call (that would execute the same item twice).
                toolIdentityConflict = true
                return outputMismatch()
            }
            val targetKey = when {
                declaredKey != null -> declaredKey
                callId == null -> correlatedKey
                existingKey == positionalKey -> correlatedKey
                // A call_id-resolved bucket from another position is not
                // evidence that this terminal array slot owns it: never
                // relabel it from the (reorder-untrusted) position map.
                else -> null
            }
            if (targetKey != null && existingKey != null && existingKey != targetKey) {
                if (!isPositionalToolKey(existingKey) || callsByItem.containsKey(targetKey)) {
                    toolIdentityConflict = true
                    return outputMismatch()
                }
                // Adopt the id-less positional bucket under the declared id:
                // same toolIndex/args, never a phantom duplicate drain.
                callsByItem.remove(existingKey)
                callsByItem[targetKey] = existing
            }
            val full = item.string("arguments")
            // A terminal replay may not contradict an authoritative
            // arguments.done full. (A still-unfinalized delta buffer keeps the
            // documented completed-full-wins recovery shape below.)
            if (existing.argsFinal && !full.isNullOrEmpty() &&
                !toolArgsEquivalent(existing.args.toString(), full)
            ) {
                toolIdentityConflict = true
                return outputMismatch()
            }
            val authoritativeArgs =
                if (!existing.argsFinal && !full.isNullOrEmpty()) full else null
            val ok = closeFunctionCall(
                existing,
                callId,
                item.string("name")?.takeIf { it.isNotEmpty() },
                authoritativeArgs,
                out,
                observedArgs = full,
            )
            if (!ok) return outputMismatch()
            representedPairs?.add(existing.toolIndex to existing.id)
            return null
        }
        // No buffered bucket matched by call_id, declared id or position.
        if (declaredKey == null && correlatedKey == null && callId == null) {
            val hasPayload = item.string("name")?.isNotEmpty() == true ||
                item.string("arguments")?.isNotEmpty() == true
            // A keyless terminal call with real payload cannot be paired:
            // silently dropping it would lose the call while claiming Done.
            return if (hasPayload) outputMismatch() else null
        }
        val itemKey = declaredKey ?: correlatedKey ?: positionalKey
        if (callsByItem.containsKey(itemKey)) {
            // The fallback bucket belongs to a different call id.
            toolIdentityConflict = true
            return outputMismatch()
        }
        // Same per-key cap as the added/delta/done paths; the positional
        // fallback key is charged here too, BEFORE any insert, so a giant key
        // is never retained (no insert, no emit).
        requireToolIdBudget(declaredRaw?.length ?: itemKey.length)
        requireToolCountBudget(callsByItem.size + 1)
        val agg = AggCall(toolIndex = nextToolIndex++)
        callsByItem[itemKey] = agg
        val full = item.string("arguments")
        val authoritativeArgs = if (!full.isNullOrEmpty()) full else null
        val ok = closeFunctionCall(
            agg,
            callId,
            item.string("name")?.takeIf { it.isNotEmpty() },
            authoritativeArgs,
            out,
            observedArgs = full,
        )
        if (!ok) return outputMismatch()
        representedPairs?.add(agg.toolIndex to agg.id)
        return null
    }

    /**
     * Resolve a buffered call. A carried call_id is authoritative and wins over
     * the positional/map key (terminal arrays may be reordered); the key is
     * only used when no call_id is present, or when its bucket has not been
     * assigned a call_id yet. A key bucket owned by a DIFFERENT call_id is
     * never returned, so arguments cannot cross-wire.
     */
    private fun findBufferedCall(key: String?, callId: String?): AggCall? {
        if (callId != null) {
            for (agg in callsByItem.values) if (agg.id == callId) return agg
            val byKey = key?.let { callsByItem[it] }
            return byKey?.takeIf { it.id.isEmpty() }
        }
        return key?.let { callsByItem[it] }
    }

    private fun keyOfToolCall(agg: AggCall): String? =
        callsByItem.entries.firstOrNull { it.value === agg }?.key

    /**
     * Shared terminal-function-call guard chain + emit-once, used by
     * `output_item.done` and the `response.completed` reconciliation.
     * [authoritativeArgs] non-null replaces the buffered args and latches
     * `argsFinal`; null keeps them (the done path's
     * ignore-full-when-deltas-exist contract, and an argsFinal pair is never
     * clobbered). [observedArgs] is the raw terminal full (when present) and
     * is used only to reject a replay that contradicts an already-emitted
     * pair — the ignored-full contract above still applies before first emit.
     *
     * Guard order mirrors the historical done path: per-key id budget on the
     * emitted id (a rotating equal-length giant call_id keeps per-candidate
     * growth at 0, so only this cap closes it), whole-candidate per-call
     * budget, monotonic round-total growth (never a decrement on arrival),
     * then the emitted-pair-identity count check (same distinct cap as
     * callsByItem). Everything is validated BEFORE write-back/emit: overflow
     * neither mutates agg/total nor enters the emitted set.
     *
     * Emit-once per (toolIndex, id): a resend of an already-emitted pair is an
     * output no-op, unless its terminal name/args contradict the already
     * emitted payload — then [toolIdentityConflict] latches and this returns
     * false without emitting or overwriting anything.
     */
    private fun closeFunctionCall(
        agg: AggCall,
        newId: String?,
        newName: String?,
        authoritativeArgs: String?,
        out: MutableList<StreamEvent>,
        observedArgs: String? = null,
    ): Boolean {
        var effectiveId = newId ?: agg.id
        val effectiveName = newName ?: agg.name
        val effectiveArgsLen = authoritativeArgs?.length ?: agg.args.length
        if (effectiveId.isEmpty()) effectiveId = "call_" + UUID.randomUUID().toString().take(8)
        // D: never emit an empty ToolDone with no executable payload.
        if (effectiveName.isEmpty() && effectiveArgsLen == 0) {
            toolIdentityConflict = true
            return false
        }
        // B: identity evidence checks.
        if (newName != null && agg.name.isNotEmpty() && agg.name != newName) {
            toolIdentityConflict = true
            return false
        }
        val terminalFullArgs = observedArgs ?: authoritativeArgs
        // Only enforce equality when the terminal full is NOT authoritative
        // (i.e. it is being ignored in favour of buffered deltas); an
        // authoritative terminal full legitimately replaces a partial buffer.
        if (authoritativeArgs == null && terminalFullArgs != null && terminalFullArgs.isNotEmpty() &&
            agg.args.isNotEmpty() && !toolArgsEquivalent(agg.args.toString(), terminalFullArgs)
        ) {
            toolIdentityConflict = true
            return false
        }
        requireToolIdBudget(effectiveId.length)
        // P1B-B identity churn: a call already carrying a call_id may not be
        // re-identified, and a call_id may not move to another bucket/
        // toolIndex. Either would execute the same logical tool twice. Checked
        // after the per-key budget so a giant rotating id still fails typed
        // before any identity write-back.
        if (newId != null && agg.id.isNotEmpty() && agg.id != newId) {
            toolIdentityConflict = true
            return false
        }
        if (newId != null && callsByItem.values.any { it !== agg && it.id == newId }) {
            toolIdentityConflict = true
            return false
        }
        val candidateTotal = effectiveId.length + effectiveName.length + effectiveArgsLen
        requireToolAggBudget(0, candidateTotal)
        val oldBuffered = agg.id.length + agg.name.length + agg.args.length
        val growth = (candidateTotal - oldBuffered).coerceAtLeast(0)
        requireTotalToolAggBudget(totalAggChars, growth)
        val pair = agg.toolIndex to effectiveId
        val alreadyEmitted = emittedCalls[pair]
        if (alreadyEmitted != null) {
            // Replay comparison is structural (canonical args) and bounded
            // (fingerprint only), never a raw-string compare of a retained
            // full copy.
            val replayName = newName ?: agg.name
            val replayArgs = observedArgs ?: agg.args.toString()
            if (toolFingerprint(replayName, replayArgs) != alreadyEmitted.fingerprint) {
                toolIdentityConflict = true
                return false
            }
            agg.closed = true
            return true
        }
        if (pair !in emittedToolIds) {
            requireToolCountBudget(emittedToolIds.size + 1)
        }
        agg.id = effectiveId
        agg.name = effectiveName
        if (authoritativeArgs != null) {
            agg.args.setLength(0)
            agg.args.append(authoritativeArgs)
            agg.argsFinal = true
        }
        totalAggChars += growth
        if (emittedToolIds.add(pair)) {
            val args = agg.args.toString()
            emittedCalls[pair] = EmittedTool(toolFingerprint(agg.name, args))
            out.add(StreamEvent.ToolDone(agg.toolIndex, agg.id, agg.name, args))
        }
        agg.closed = true
        return true
    }

    /**
     * P2-7: emit a web-search lifecycle marker only when the item's state
     * actually changes. Key: non-empty `item_id`, else the nested item's
     * non-empty `id`, else `output_<output_index>`. The per-item state map is
     * bounded by the same distinct-admission cap (checked BEFORE insert) and
     * the same per-key length cap as every other per-attempt collection
     * (also checked BEFORE insert; typed non-retryable on overflow).
     */
    private fun emitWebSearchMarker(root: MiniJson.JObj, state: String, out: MutableList<StreamEvent>) {
        val key = root.string("item_id")?.takeIf { it.isNotEmpty() }
            ?: root.obj("item")?.string("id")?.takeIf { it.isNotEmpty() }
            ?: ("output_" + (root.int("output_index") ?: 0))
        // Same S1 per-key length guard as every other Responses keyed map:
        // check BEFORE admission so a frame-budget-sized item_id is never
        // retained (typed non-retryable, consistent with the tool paths).
        requireToolIdBudget(key.length)
        if (!webSearchStates.containsKey(key)) requireToolCountBudget(webSearchStates.size + 1)
        if (webSearchStates[key] == state) return
        webSearchStates[key] = state
        out.add(StreamEvent.TextDelta(round, blockOf(root), ServerToolTranscript.webSearchMarker(state)))
    }

    private fun blockOf(root: MiniJson.JObj): Int {
        val oi = root.int("output_index") ?: 0
        val ci = root.int("content_index") ?: 0
        return oi * 1000 + ci
    }

    /**
     * Streamed-text reconciliation key. Identity is the non-empty item id
     * (`item_id` on `output_text.delta`, `item.id` on the terminal message
     * item), else the positional `output_<output_index>#<content_index>`.
     * The positional fallback is the documented id-less protocol shape:
     * streamed `output_index` equals the terminal `output` array index.
     *
     * The per-key budget ([requireToolIdBudget]) is applied to THIS encoded
     * string, not the raw id: `id:<id>#<content_index>`. With
     * `content_index == 0`, the exact accepted boundary is a raw id of
     * `MAX_TOOL_ID_CHARS - 5` chars (`id:` + `#0`); one more char is typed
     * non-retryable [ProviderFailureCode.AGG_TOO_LARGE].
     */
    private fun textBlockKey(itemId: String?, outputIndex: Int?, contentIndex: Int): String {
        val identity = itemId?.takeIf { it.isNotEmpty() }?.let { "id:$it" }
            ?: ("output_" + (outputIndex ?: 0))
        return "$identity#$contentIndex"
    }

    /**
     * Stable function-call bucket key. Identity order: non-empty `item_id`
     * (or nested item `id`; both must agree), then the bucket registered for
     * this `output_index` by an earlier id-carrying frame
     * ([toolKeyByOutputIndex]), then the positional output_index fallback.
     * Keys use disjoint namespaces: declared `id:<raw item id>` vs positional
     * `pos:<output_index>`, so a legitimate item id such as `output_1` can
     * never alias the fallback bucket for index 1. Empty strings are "absent",
     * never a shared "" bucket, so `added` and its argument frames always
     * agree even when a provider sends `item_id:""` or omits the id after an
     * identified item. A frame with none of these is not pairable: fail closed
     * typed non-retryable instead of random-bucketing `added` while dropping
     * its argument frames (which would later drain an empty call).
     *
     * The S1 per-key length guard ([requireToolIdBudget]) runs HERE, before
     * the key can be retained in `callsByItem` or [toolKeyByOutputIndex].
     * Registration is best-effort and capped by [MAX_TOOL_CALLS_PER_ATTEMPT]
     * (an adversarial `output_index` flood degrades to the positional
     * fallback, never to unbounded growth).
     *
     * Reverse mixed identity: if an id-less earlier frame already admitted the
     * positional `pos:<output_index>` bucket, a later frame that introduces
     * the declared id PROMOTES that bucket (same toolIndex/name/args) instead
     * of orphaning it into a phantom drain ToolDone. Promotion happens only
     * when the declared key is not already buffered, so two identified items
     * are never merged. The same declared id at a different output_index, and
     * a declared id sharing the legacy `output_<n>` label with a live
     * positional bucket at another index, both latch identity conflict.
     */
    private fun requireFunctionCallKey(root: MiniJson.JObj, item: MiniJson.JObj? = null): String {
        val outputIndex = root.int("output_index")
        val declaredRaw = declaredItemId(root, item)
        // A frame carrying BOTH declarations that disagree is identity churn,
        // never a silent `item_id` win. Callers project the latched mismatch.
        if (toolIdentityConflict) {
            return outputIndex?.let(::positionalToolKey)
                ?: declaredRaw?.let(::declaredToolKey)
                ?: ""
        }
        val declared = declaredRaw?.let(::declaredToolKey)
        if (declaredRaw != null && positionalNamespaceCollision(declaredRaw, outputIndex)) {
            toolIdentityConflict = true
            return declared!!
        }
        val registered = outputIndex?.let { toolKeyByOutputIndex[it] }
        // Mirror of the declared-first collision shape: an id-less frame that
        // would fall back to pos:<index> must not silently join/alias a call
        // whose declared item id is literally `output_<index>`. If that
        // declared bucket is the one registered at THIS index it is the same
        // call (post-promotion) and stays legal.
        if (declared == null && outputIndex != null &&
            (registered == null || isPositionalToolKey(registered))
        ) {
            val legacyNamedKey = declaredToolKey("output_$outputIndex")
            if (callsByItem.containsKey(legacyNamedKey)) {
                toolIdentityConflict = true
                return positionalToolKey(outputIndex)
            }
        }
        // The same declared item id at a different output_index would merge
        // two frames into one bucket. Fail closed instead (bounded scan).
        if (declared != null && outputIndex != null) {
            val registeredIndex = registeredToolIndex(declared)
            if (registeredIndex != null && registeredIndex != outputIndex) {
                toolIdentityConflict = true
                return declared
            }
        }
        // One output_index may not change its declared item id mid-stream:
        // latch (and keep the first identity) so terminal reconciliation fails
        // closed instead of merging two items' args under one bucket. A
        // positional bucket at this index is the supported promotion shape.
        if (declared != null && registered != null && registered != declared) {
            if (!isPositionalToolKey(registered)) {
                toolIdentityConflict = true
                return registered
            }
        }
        val key = declared
            ?: registered
            ?: outputIndex?.let(::positionalToolKey)
            ?: throw ProviderFailure(false, RESPONSES_IDENTITY_MISSING)
        requireToolIdBudget(if (declaredRaw != null) declaredRaw.length else key.length)
        if (declared != null && outputIndex != null) {
            val positional = positionalToolKey(outputIndex)
            if (declared != positional && !callsByItem.containsKey(declared)) {
                callsByItem.remove(positional)?.let { callsByItem[declared] = it }
            }
            if (toolKeyByOutputIndex.size < MAX_TOOL_CALLS_PER_ATTEMPT ||
                toolKeyByOutputIndex.containsKey(outputIndex)
            ) {
                toolKeyByOutputIndex[outputIndex] = key
            }
        }
        return key
    }

    /**
     * Resolve the declared item id from `item_id` (frame level) and the nested
     * item `id`. Both present but different is contradiction, not a
     * preference: latch so no terminal can accept either identity.
     */
    private fun declaredItemId(root: MiniJson.JObj, item: MiniJson.JObj? = null): String? {
        val rootId = root.string("item_id")?.takeIf { it.isNotEmpty() }
        val itemId = item?.string("id")?.takeIf { it.isNotEmpty() }
        if (rootId != null && itemId != null && rootId != itemId) {
            toolIdentityConflict = true
        }
        return rootId ?: itemId
    }
}

/** 厂商事件后缀收敛为白名单状态，避免把厂商原文透传进转录。 */
internal fun webSearchStateOf(suffix: String): String = when (suffix) {
    "in_progress" -> "started"
    "searching" -> "searching"
    "completed" -> "completed"
    else -> "update"
}
