package dev.librepocket.provider

import dev.librepocket.redact.Redactor
import java.util.UUID
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
            pumpSse(httpClient, postJson(endpoint(config.baseUrl), headers, body)) { frame ->
                emitAll(mapper.mapPayload(frame.data))
                if (mapper.terminalReached) SsePumpDecision.STOP else SsePumpDecision.CONTINUE
            }
            emitAll(mapper.finish())
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
    )

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
    // Mixed-identity correlation (best-effort, bounded by the same cap): an
    // id-carrying streamed block registers its `(output_index,
    // content_index)` position, so a terminal message item that omits `id`
    // still rejoins the streamed prefix by position instead of emitting the
    // full terminal text as a duplicate suffix. Registration is skipped once
    // the cap is reached (adversarial id churn degrades to the documented
    // positional fallback, never to unbounded growth); the map is dropped
    // with text tracking.
    private val textKeyByOutputIndex = LinkedHashMap<Pair<Int, Int>, String>()
    private var streamedTextChars = 0
    private var textTrackingDisabled = false
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
                    val outputIndex = root.int("output_index")
                    val contentIndex = root.int("content_index") ?: 0
                    val blockKey =
                        textBlockKey(root.string("item_id"), outputIndex, contentIndex)
                    // Same per-key length guard as the tool maps, before any
                    // retention: a frame-budget-sized item_id can never be
                    // stored as a map key.
                    requireToolIdBudget(blockKey.length)
                    val tracked = trackStreamedText(blockKey, delta)
                    val positionalKey = textBlockKey(null, outputIndex, contentIndex)
                    if (tracked && outputIndex != null && blockKey != positionalKey &&
                        textKeyByOutputIndex.size < MAX_TOOL_CALLS_PER_ATTEMPT
                    ) {
                        textKeyByOutputIndex[outputIndex to contentIndex] = blockKey
                    }
                    out.add(StreamEvent.TextDelta(round, blockOf(root), delta))
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
                if (!callsByItem.containsKey(itemId)) requireToolCountBudget(callsByItem.size + 1)
                val agg = callsByItem.getOrPut(itemId) { AggCall(toolIndex = nextToolIndex++) }
                // P1-2: once arguments.done latched the authoritative full
                // payload, later deltas are replays and must neither mutate
                // the aggregate nor surface a ToolDelta.
                if (agg.argsFinal) return emptyList()
                val frag = root.string("delta").orEmpty()
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
                if (!callsByItem.containsKey(itemId)) requireToolCountBudget(callsByItem.size + 1)
                val agg = callsByItem.getOrPut(itemId) { AggCall(toolIndex = nextToolIndex++) }
                if (agg.argsFinal) return emptyList() // first authoritative full wins
                val full = root.string("arguments") ?: return emptyList()
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
                    emitWebSearchMarker(root, "completed", out)
                    return out
                }
                if (item.string("type") != "function_call") {
                    // Same opaque-reasoning presence rule as added (above).
                    if (item.string("type") == "reasoning") {
                        reasoningPresentSeen = true
                        out.add(StreamEvent.ReasoningPresent)
                    }
                    return out
                }
                val itemId = requireFunctionCallKey(root, item)
                if (!callsByItem.containsKey(itemId)) requireToolCountBudget(callsByItem.size + 1)
                val agg = callsByItem.getOrPut(itemId) { AggCall(toolIndex = nextToolIndex++) }
                val newId = item.string("call_id")?.takeIf { it.isNotEmpty() }
                val newName = item.string("name")?.takeIf { it.isNotEmpty() }
                val full = item.string("arguments")
                // args non-empty => terminal full is ignored (no double-count);
                // otherwise the single terminal payload IS the args. P1-2: an
                // argsFinal pair latched by arguments.done is never clobbered.
                val authoritativeArgs =
                    if (!agg.argsFinal && agg.args.isEmpty() && !full.isNullOrEmpty()) full else null
                closeFunctionCall(agg, newId, newName, authoritativeArgs, out)
            }
            "response.completed" -> {
                val response = root.obj("response")
                val output = response?.arr("output")?.items
                // P2-8: usage still precedes every terminal projection.
                usageOf(response)?.let {
                    usage = it
                    out.add(it)
                }
                if (output == null || output.isEmpty()) {
                    // Authoritative terminal with empty output: recover from the
                    // already-streamed deltas (recorded by upper layers). This
                    // branch stays byte-identical to the pre-reconciliation path.
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
                var mismatch: StreamEvent.Failed? = null
                for ((itemIndex, j) in output.withIndex()) {
                    val item = j as? MiniJson.JObj ?: continue
                    when (item.string("type")) {
                        "message" -> mismatch = reconcileMessageItem(item, itemIndex, out)
                        "function_call" -> reconcileFunctionCallItem(item, itemIndex, out)
                        "reasoning" -> if (!reasoningPresentSeen) {
                            reasoningPresentSeen = true
                            out.add(StreamEvent.ReasoningPresent)
                        }
                    }
                    if (mismatch != null) break
                }
                if (mismatch != null) {
                    terminal = mismatch
                    out.add(mismatch)
                    terminalReached = true
                    return out
                }
                finishPendingTools(out)
                terminal = StreamEvent.Done("stop")
                out.add(terminal!!)
                terminalReached = true
            }
            "response.failed" -> {
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
            return listOf(
                StreamEvent.Failed(
                    TOOL_ARGS_TOO_LARGE,
                    retryable = false,
                    code = ProviderFailureCode.AGG_TOO_LARGE.name,
                ),
            )
        }
        terminal?.let { return emptyList() } // already terminal
        val out = ArrayList<StreamEvent>()
        finishPendingTools(out)
        val done = StreamEvent.Failed("SSE_TRUNCATED", retryable = true)
        out.add(done)
        return out
    }

    private fun finishPendingTools(out: MutableList<StreamEvent>) {
        for ((_, agg) in callsByItem) {
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
                    out.add(StreamEvent.ToolDone(agg.toolIndex, agg.id, agg.name, agg.args.toString()))
                }
            }
        }
    }

    private val emittedToolIds = LinkedHashSet<Pair<Int, String>>()

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
    }

    /**
     * P1-1: reconcile one `response.completed` message item against the
     * streamed text buffer. Identity is the non-empty item id (`item_id` on
     * the delta, `id` on the terminal item), falling back to the positional
     * block (`output_index` / array index) for id-less streams — see
     * [textBlockKey]. A terminal text that extends the tracked prefix emits
     * only the missing suffix (equal text emits nothing, so no duplicate); a
     * contradiction — or any non-empty terminal text once tracking was
     * disabled — returns the typed non-retryable mismatch.
     */
    private fun reconcileMessageItem(
        item: MiniJson.JObj,
        itemIndex: Int,
        out: MutableList<StreamEvent>,
    ): StreamEvent.Failed? {
        val content = item.arr("content")?.items.orEmpty()
        for ((contentIndex, j) in content.withIndex()) {
            val part = j as? MiniJson.JObj ?: continue
            val partType = part.string("type")
            if (partType != "output_text" && partType != "text") continue
            val terminalText = part.string("text") ?: continue
            val block = itemIndex * 1000 + contentIndex
            if (textTrackingDisabled) {
                if (terminalText.isNotEmpty()) return outputMismatch()
                continue
            }
            // Prefer the stable id key; when deltas never carried an id this
            // misses and the positional key carries the tracked prefix. An
            // id-less or empty-id terminal item must NOT synthesize an
            // `output_0` id key: it would falsely match positional block 0.
            val itemId = item.string("id")?.takeIf { it.isNotEmpty() }
            val idKey = itemId?.let { textBlockKey(it, null, contentIndex) }
            // An id-carrying stream registered its position; use it when the
            // terminal item omits `id` rather than emitting a duplicate.
            val correlatedKey = textKeyByOutputIndex[itemIndex to contentIndex]
            val positionalKey = textBlockKey(null, itemIndex, contentIndex)
            val streamed = (
                idKey?.let { streamedTextByBlock[it] }
                    ?: correlatedKey?.let { streamedTextByBlock[it] }
                    ?: streamedTextByBlock[positionalKey]
                )?.toString().orEmpty()
            if (!terminalText.startsWith(streamed)) return outputMismatch()
            val suffix = terminalText.substring(streamed.length)
            if (suffix.isNotEmpty()) out.add(StreamEvent.TextDelta(round, block, suffix))
        }
        return null
    }

    /** P1-1: typed non-retryable terminal for a streamed/terminal text contradiction. */
    private fun outputMismatch(): StreamEvent.Failed =
        StreamEvent.Failed("RESPONSES_OUTPUT_MISMATCH", retryable = false)

    /**
     * P1-1: reconcile one terminal-only `function_call` item. Identity is a
     * non-empty `item_id`/`id` first (P3-11: empty strings are "absent", never
     * a shared "" bucket), else the bucket registered for this `output_index`
     * by an id-carrying streamed frame (mixed-identity correlation), else the
     * positional `output_<itemIndex>` twin of the streamed `output_index`
     * fallback, else an already-buffered `call_id`.
     * A pair already emitted during streaming is a replay and is skipped;
     * otherwise the terminal full `arguments` (when present and not already
     * argsFinal) replaces the buffered deltas and exactly one ToolDone is
     * emitted through the same guard chain as `output_item.done`. An item with
     * no declared identity that matches no buffered bucket is dropped (there is
     * nothing trustworthy to close), mirroring the historical keyless drop.
     */
    private fun reconcileFunctionCallItem(item: MiniJson.JObj, itemIndex: Int, out: MutableList<StreamEvent>) {
        val declaredKey = item.string("item_id")?.takeIf { it.isNotEmpty() }
            ?: item.string("id")?.takeIf { it.isNotEmpty() }
        // Per-key guard BEFORE the promotion insert and before any lookup can
        // retain the declared key (mirrors requireFunctionCallKey). The
        // existing-bucket path returns before the itemKey guard below, so
        // without this an overlong terminal id would be inserted by the
        // promotion and never charged.
        if (declaredKey != null) requireToolIdBudget(declaredKey.length)
        val correlatedKey = toolKeyByOutputIndex[itemIndex]
        // Same reverse-mixed promotion as requireFunctionCallKey: an id-less
        // streamed bucket at this array position is adopted by the now-declared
        // id (same toolIndex/args), instead of being orphaned and drained as a
        // phantom second call. Only when no identified bucket exists yet and
        // no different id was registered for the position.
        if (declaredKey != null && correlatedKey == null &&
            !callsByItem.containsKey(declaredKey)
        ) {
            callsByItem.remove("output_$itemIndex")?.let { callsByItem[declaredKey] = it }
        }
        val itemKey = declaredKey ?: correlatedKey ?: "output_$itemIndex"
        val callId = item.string("call_id")?.takeIf { it.isNotEmpty() }
        val existing = findBufferedCall(itemKey, callId)
        val full = item.string("arguments")
        if (existing != null) {
            val candidateId = callId ?: existing.id
            if (candidateId.isNotEmpty() && (existing.toolIndex to candidateId) in emittedToolIds) {
                return
            }
            val authoritativeArgs =
                if (!existing.argsFinal && !full.isNullOrEmpty()) full else null
            closeFunctionCall(
                existing,
                callId,
                item.string("name")?.takeIf { it.isNotEmpty() },
                authoritativeArgs,
                out,
            )
            return
        }
        if (declaredKey == null && correlatedKey == null && callId == null) return
        // Same per-key cap as the added/delta/done paths; the positional
        // fallback key is charged here too, BEFORE any insert, so a giant key
        // is never retained (no insert, no emit).
        requireToolIdBudget(itemKey.length)
        requireToolCountBudget(callsByItem.size + 1)
        val agg = AggCall(toolIndex = nextToolIndex++)
        callsByItem[itemKey] = agg
        val authoritativeArgs = if (!full.isNullOrEmpty()) full else null
        closeFunctionCall(agg, callId, item.string("name")?.takeIf { it.isNotEmpty() }, authoritativeArgs, out)
    }

    /** Resolve a buffered call by map key first, then by its carried call_id. */
    private fun findBufferedCall(key: String?, callId: String?): AggCall? {
        if (key != null) callsByItem[key]?.let { return it }
        if (callId != null) {
            for (agg in callsByItem.values) if (agg.id == callId) return agg
        }
        return null
    }

    /**
     * Shared terminal-function-call guard chain + emit-once, used by
     * `output_item.done` and the `response.completed` reconciliation.
     * [authoritativeArgs] non-null replaces the buffered args and latches
     * `argsFinal`; null keeps them (the done path's
     * ignore-full-when-deltas-exist contract, and an argsFinal pair is never
     * clobbered).
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
     * output no-op (the agg write-back still refreshes id/name and the round
     * total, but nothing is emitted), mirroring the [finishPendingTools]
     * drain: at most one ToolDone per pair per attempt.
     */
    private fun closeFunctionCall(
        agg: AggCall,
        newId: String?,
        newName: String?,
        authoritativeArgs: String?,
        out: MutableList<StreamEvent>,
    ) {
        var effectiveId = newId ?: agg.id
        val effectiveName = newName ?: agg.name
        val effectiveArgsLen = authoritativeArgs?.length ?: agg.args.length
        if (effectiveId.isEmpty()) effectiveId = "call_" + UUID.randomUUID().toString().take(8)
        requireToolIdBudget(effectiveId.length)
        val candidateTotal = effectiveId.length + effectiveName.length + effectiveArgsLen
        requireToolAggBudget(0, candidateTotal)
        val oldBuffered = agg.id.length + agg.name.length + agg.args.length
        val growth = (candidateTotal - oldBuffered).coerceAtLeast(0)
        requireTotalToolAggBudget(totalAggChars, growth)
        if ((agg.toolIndex to effectiveId) !in emittedToolIds) {
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
        if (emittedToolIds.add(agg.toolIndex to agg.id)) {
            out.add(StreamEvent.ToolDone(agg.toolIndex, agg.id, agg.name, agg.args.toString()))
        }
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
     */
    private fun textBlockKey(itemId: String?, outputIndex: Int?, contentIndex: Int): String {
        val identity = itemId?.takeIf { it.isNotEmpty() }?.let { "id:$it" }
            ?: ("output_" + (outputIndex ?: 0))
        return "$identity#$contentIndex"
    }

    /**
     * Stable function-call bucket key. Identity order: non-empty `item_id`,
     * nested item `id`, then the bucket registered for this `output_index` by
     * an earlier id-carrying frame ([toolKeyByOutputIndex]), then the
     * positional `output_<output_index>` (present on every added/delta/done
     * item frame). Empty strings are "absent", never a shared "" bucket, so
     * `added` and its argument frames always agree even when a provider sends
     * `item_id:""` or omits the id after an identified item. A frame with none
     * of these is not pairable: fail closed typed non-retryable instead of
     * random-bucketing `added` while dropping its argument frames (which would
     * later drain an empty call).
     *
     * The S1 per-key length guard ([requireToolIdBudget]) runs HERE, before
     * the key can be retained in `callsByItem` or [toolKeyByOutputIndex].
     * Registration is best-effort and capped by [MAX_TOOL_CALLS_PER_ATTEMPT]
     * (an adversarial `output_index` flood degrades to the positional
     * fallback, never to unbounded growth).
     *
     * Reverse mixed identity: if an id-less earlier frame already admitted the
     * positional `output_<output_index>` bucket, a later frame that introduces
     * the declared id PROMOTES that bucket (same toolIndex/name/args) instead
     * of orphaning it into a phantom drain ToolDone. Promotion happens only
     * when the declared key is not already buffered, so two identified items
     * are never merged.
     */
    private fun requireFunctionCallKey(root: MiniJson.JObj, item: MiniJson.JObj? = null): String {
        val outputIndex = root.int("output_index")
        val declared = root.string("item_id")?.takeIf { it.isNotEmpty() }
            ?: item?.string("id")?.takeIf { it.isNotEmpty() }
        val key = declared
            ?: outputIndex?.let { toolKeyByOutputIndex[it] }
            ?: outputIndex?.let { "output_$it" }
            ?: throw ProviderFailure(false, RESPONSES_IDENTITY_MISSING)
        // Length guard BEFORE any retention/promotion: an overlong declared id
        // must never be inserted (as bucket key or correlation value).
        requireToolIdBudget(key.length)
        if (declared != null && outputIndex != null) {
            val positional = "output_$outputIndex"
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
}

/** 厂商事件后缀收敛为白名单状态，避免把厂商原文透传进转录。 */
internal fun webSearchStateOf(suffix: String): String = when (suffix) {
    "in_progress" -> "started"
    "searching" -> "searching"
    "completed" -> "completed"
    else -> "update"
}
