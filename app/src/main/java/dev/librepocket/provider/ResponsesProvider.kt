package dev.librepocket.provider

import dev.librepocket.redact.Redactor
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import okhttp3.OkHttpClient

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
 *   [StreamEvent.ToolDone], distinguished by `item_id/output_index/content_index`.
 * - `response.completed` is the authoritative terminal; an empty `output`
 *   array recovers from already-streamed deltas (P1 simplification).
 * - `response.failed` / `response.incomplete` -> [StreamEvent.Failed].
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
    )

    private val callsByItem = LinkedHashMap<String, AggCall>()
    private var nextToolIndex = 0
    // Per-attempt whole-round total across all item_ids (Finding #1),
    // monotonic: ToolDone arrivals never decrement it.
    private var totalAggChars = 0
    private var usage: StreamEvent.Usage? = null
    private var terminal: StreamEvent? = null
    // Sticky JSON-depth breach latch (fail-closed): once a frame exceeds
    // MiniJson.MAX_DEPTH, mapPayload throws typed non-retryable and every
    // later mapPayload rethrows the same way, so no later terminal frame can
    // become Done; finish() reports the typed failure instead of
    // SSE_TRUNCATED. Set only from the MiniJsonDepthException branch below.
    private var jsonDepthFailed = false
    var terminalReached: Boolean = false
        private set

    fun mapPayload(payload: String): List<StreamEvent> {
        if (jsonDepthFailed) throw ProviderFailure(false, TOOL_ARGS_TOO_LARGE, code = ProviderFailureCode.AGG_TOO_LARGE)
        if (terminal != null) return emptyList()
        val root = try {
            MiniJson.parse(payload) as? MiniJson.JObj ?: return emptyList()
        } catch (e: MiniJson.MiniJsonException) {
            if (e is MiniJson.MiniJsonDepthException) {
                // A depth breach is NOT a droppable malformed frame: dropping
                // it would lose its delta and let a later terminal succeed
                // without it (fake success), or degrade at EOF to a retryable
                // SSE_TRUNCATED that retries the poisoned stream. Fail the
                // attempt with the same typed non-retryable failure as every
                // aggregation-budget overflow; streamingFlow converts the throw
                // into a single Failed terminal. Other malformed input still
                // drops just this frame below.
                jsonDepthFailed = true
                throw ProviderFailure(false, TOOL_ARGS_TOO_LARGE, code = ProviderFailureCode.AGG_TOO_LARGE)
            }
            return emptyList()
        }
        val type = root.string("type") ?: return emptyList()
        val out = ArrayList<StreamEvent>()
        // 服务端搜索生命周期事件：固定走 TextDelta 标记，不进本地 Tool 聚合。
        if (type.startsWith("response.web_search_call.")) {
            val state = webSearchStateOf(type.substringAfterLast('.'))
            out.add(StreamEvent.TextDelta(round, blockOf(root), ServerToolTranscript.webSearchMarker(state)))
            return out
        }
        when (type) {
            "response.output_text.delta" -> {
                val delta = root.string("delta") ?: return emptyList()
                // Empty-delta suppression (parity with ChatCompletions): an
                // empty frame yields NO event, so it can never park an empty
                // block in the UI or masquerade as final-answer evidence
                // downstream (continuation completeness, maintainer P1).
                if (delta.isNotEmpty()) out.add(StreamEvent.TextDelta(round, blockOf(root), delta))
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
                    out.add(StreamEvent.TextDelta(round, blockOf(root), ServerToolTranscript.webSearchMarker("started")))
                    return out
                }
                if (item.string("type") != "function_call") {
                    // Opaque reasoning items carry no replayable text: record
                    // presence only, so history rebuilds fail closed instead of
                    // replaying a reasoning origin as plain text.
                    if (item.string("type") == "reasoning") out.add(StreamEvent.ReasoningPresent)
                    return out
                }
                val itemId = root.string("item_id") ?: item.string("id") ?: ("item_" + UUID.randomUUID().toString().take(8))
                // S1 per-key length guard (orthogonal to the count cap below):
                // a 300KB item_id with an empty delta keeps every char/total
                // budget silent while retaining ~300KB per entry (64 x 300KB
                // ~= 19.2M chars). Check BEFORE insert so the giant key is
                // never retained (no entry inserted). Synthetic "item_"+8
                // fallbacks are far below the cap; empty strings still
                // admitted/billed (unchanged).
                requireToolIdBudget(itemId.length)
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
                val itemId = root.string("item_id") ?: return emptyList()
                // S1 per-key length guard: same orthogonal admission half as
                // the added path — checked BEFORE insert, giant key never
                // retained (no entry inserted).
                requireToolIdBudget(itemId.length)
                if (!callsByItem.containsKey(itemId)) requireToolCountBudget(callsByItem.size + 1)
                val agg = callsByItem.getOrPut(itemId) { AggCall(toolIndex = nextToolIndex++) }
                val frag = root.string("delta").orEmpty()
                // Fail-closed: a hostile stream of tiny fragments must not
                // grow one call's aggregate without bound (typed, non-retryable).
                requireToolAggBudget(agg.id.length + agg.name.length + agg.args.length, frag.length)
                requireTotalToolAggBudget(totalAggChars, frag.length)
                agg.args.append(frag)
                totalAggChars += frag.length
                out.add(StreamEvent.ToolDelta(agg.toolIndex, null, null, frag))
            }
            "response.output_item.done" -> {
                val item = root.obj("item") ?: return emptyList()
                if (item.string("type") == "web_search_call") {
                    out.add(StreamEvent.TextDelta(round, blockOf(root), ServerToolTranscript.webSearchMarker("completed")))
                    return out
                }
                if (item.string("type") != "function_call") {
                    // Same opaque-reasoning presence rule as added (above).
                    if (item.string("type") == "reasoning") out.add(StreamEvent.ReasoningPresent)
                    return out
                }
                val itemId = root.string("item_id") ?: item.string("id") ?: return emptyList()
                // S1 per-key length guard on the map key (same as added/delta:
                // BEFORE insert, giant key never retained — no entry inserted).
                requireToolIdBudget(itemId.length)
                if (!callsByItem.containsKey(itemId)) requireToolCountBudget(callsByItem.size + 1)
                val agg = callsByItem.getOrPut(itemId) { AggCall(toolIndex = nextToolIndex++) }
                // Finding #4: terminal full payloads arrive without prior delta
                // budgeting, so validate the whole candidate ToolDone BEFORE
                // any write-back. Read into locals; throw AGG_TOO_LARGE with
                // no emit and no agg mutation on overflow.
                val newId = item.string("call_id")?.takeIf { it.isNotEmpty() }
                val newName = item.string("name")?.takeIf { it.isNotEmpty() }
                val full = item.string("arguments")
                // args non-empty => terminal full is ignored (no double-count);
                // otherwise the single terminal payload IS the args.
                val useFull = agg.args.isEmpty() && !full.isNullOrEmpty()
                var effectiveId = newId ?: agg.id
                val effectiveName = newName ?: agg.name
                val effectiveArgsLen = if (useFull) full!!.length else agg.args.length
                if (effectiveId.isEmpty()) effectiveId = "call_" + UUID.randomUUID().toString().take(8)
                // S1 per-key length guard on the emitted id: a rotating
                // equal-length giant call_id keeps per-candidate growth at 0
                // (old id retired, lengths equal) so char/total/count guards
                // all stay silent while emittedToolIds retains ~300KB per
                // pair. Merely summing key lengths into totalAggChars cannot
                // close this — retired ids are already out of the live total —
                // so the per-key cap is the correct primitive. Checked BEFORE
                // any write-back/emit: overflow neither mutates agg/total nor
                // enters the emitted set. Synthetic "call_"+8 unaffected.
                requireToolIdBudget(effectiveId.length)
                val candidateTotal = effectiveId.length + effectiveName.length + effectiveArgsLen
                // Whole-ToolDone per-call guard: catches terminal-only
                // oversized payloads (e.g. 200k id + 60k name + 120k args).
                requireToolAggBudget(0, candidateTotal)
                // Round-total monotonic growth (never a decrement on arrival).
                val oldBuffered = agg.id.length + agg.name.length + agg.args.length
                val growth = (candidateTotal - oldBuffered).coerceAtLeast(0)
                requireTotalToolAggBudget(totalAggChars, growth)
                // Dedup-set bound (same per-attempt collection invariant as
                // callsByItem): a hostile stream re-sending one item_id's
                // output_item.done with a rotating equal-length call_id keeps
                // per-candidate growth 0 and item_id fixed, so every char and
                // distinct-count guard above stays silent while emittedToolIds
                // — and the emitted ToolDone count — grows without bound. Reuse
                // the SAME distinct per-attempt cap + typed failure; check a
                // genuinely-new (toolIndex, id) BEFORE insert so the overflowing
                // entry is never retained (no set insert; duplicates are dedup
                // no-ops — neither new admissions nor new emits, mirroring the
                // callsByItem guard above and the drain below).
                if ((agg.toolIndex to effectiveId) !in emittedToolIds) {
                    requireToolCountBudget(emittedToolIds.size + 1)
                }
                // All guards passed: single write-back, then emit-once per
                // (toolIndex, id) pair — only a genuinely-new admission emits.
                // A resend of an already-emitted pair is an output no-op (no
                // event, no throw, no set growth; the agg write-back above
                // still refreshes id/name/args and the round total, but
                // nothing is emitted), mirroring the
                // finishPendingTools drain below: at most one ToolDone per
                // pair per attempt, however often the server repeats it.
                agg.id = effectiveId
                agg.name = effectiveName
                if (useFull) agg.args.append(full!!)
                totalAggChars += growth
                if (emittedToolIds.add(agg.toolIndex to agg.id)) {
                    out.add(StreamEvent.ToolDone(agg.toolIndex, agg.id, agg.name, agg.args.toString()))
                }
            }
            "response.completed" -> {
                val response = root.obj("response")
                val output = response?.arr("output")?.items
                response?.obj("usage")?.let { u ->
                    usage = StreamEvent.Usage(u.int("input_tokens"), u.int("output_tokens"))
                    out.add(usage!!)
                }
                if (output == null || output.isEmpty()) {
                    // Authoritative terminal with empty output: recover from the
                    // already-streamed deltas (recorded by upper layers).
                    finishPendingTools(out)
                    terminal = StreamEvent.Done("stop")
                } else {
                    finishPendingTools(out)
                    terminal = StreamEvent.Done("stop")
                }
                out.add(terminal!!)
                terminalReached = true
            }
            "response.failed" -> {
                val err = root.obj("response")?.obj("error") ?: root.obj("error")
                val msg = err?.string("message") ?: "RESPONSE_FAILED"
                val kind = ProviderErrorClassifier.classify(null, null, msg)
                terminal = StreamEvent.Failed(redactedError("RESPONSE_FAILED", msg), kind == FailureKind.RETRYABLE)
                out.add(terminal!!)
                terminalReached = true
            }
            "response.incomplete" -> {
                val reason = root.obj("response")?.string("incomplete_details")
                    ?: "RESPONSE_INCOMPLETE"
                terminal = StreamEvent.Failed(redactedError("RESPONSE_INCOMPLETE", reason), retryable = false)
                out.add(terminal!!)
                terminalReached = true
            }
        }
        return out
    }

    fun finish(): List<StreamEvent> {
        // A latched depth breach dominates the honest-EOF path: the poisoned
        // attempt reports the typed non-retryable failure, never a retryable
        // SSE_TRUNCATED (which would retry the poisoned stream) and never a
        // success. Carries the AGG_TOO_LARGE code so TurnController's
        // pending-flush guard treats it like every other budget overflow.
        if (jsonDepthFailed) {
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

    private fun blockOf(root: MiniJson.JObj): Int {
        val oi = root.int("output_index") ?: 0
        val ci = root.int("content_index") ?: 0
        return oi * 1000 + ci
    }
}

/** 厂商事件后缀收敛为白名单状态，避免把厂商原文透传进转录。 */
internal fun webSearchStateOf(suffix: String): String = when (suffix) {
    "in_progress" -> "started"
    "searching" -> "searching"
    "completed" -> "completed"
    else -> "update"
}
