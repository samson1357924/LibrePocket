package dev.librepocket.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import okhttp3.OkHttpClient

/**
 * Anthropic Messages adapter (SPEC §3.1–§3.2).
 *
 * - `POST {base}/v1/messages` with `anthropic-version: 2023-06-01`,
 *   `Accept: text/event-stream`, `"stream": true`; `max_tokens` required.
 * - `content_block_start/delta/stop` + `message_stop`; the provider keeps the
 *   native `content_block.index` as `blockIndex` (§3.5).
 * - `thinking_delta` -> [StreamEvent.ReasoningDelta]; a thinking or
 *   `redacted_thinking` block start and every `signature_delta` additionally
 *   emit [StreamEvent.ReasoningPresent] (presence marker only). Signature /
 *   encrypted payloads are never displayed and never persisted.
 * - Phase 2 history: assistant-carried calls ride `tool_use` blocks;
 *   tool turns ride `user` messages carrying `tool_result` blocks paired by
 *   `tool_use_id`. Every request self-contains the full pairing (stateless);
 *   illegal pairings fail closed. P1 executed nothing; P2 still executes
 *   nothing (no dispatcher — #19 vertical slice scope).
 *   Non-thinking only; reasoning-bearing histories fail closed
 *   (`THINKING_ROUND_TRIP_NOT_SUPPORTED`, checked first in `buildBody`).
 * - Missing `message_stop` or unclosed visible/tool blocks ->
 *   `Failed(retryable=true)`.
 */
class AnthropicProvider(
    private val config: ProviderConfig,
    private val apiKey: suspend () -> CharArray?,
    client: OkHttpClient? = null,
) : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.ANTHROPIC
    private val httpClient: OkHttpClient by lazy { providerTransportClient(client ?: defaultOkHttpClient(config.http)) }

    override fun stream(request: ChatRequest): Flow<StreamEvent> = streamingFlow {
        val key = apiKey() ?: throw ProviderFailure(false, "HTTP 401 missing_api_key")
        try {
            val body = buildBody(request)
            val headers = linkedMapOf(
                "Content-Type" to "application/json",
                "Accept" to "text/event-stream",
                "anthropic-version" to ANTHROPIC_VERSION,
                "x-api-key" to String(key),
            )
            val mapper = AnthropicMapper()
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
            val url = joinEndpoint(config.baseUrl, "/v1/models")
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("x-api-key", String(key))
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

    internal fun endpoint(base: String): String = joinEndpoint(base, "/v1/messages")

    internal fun buildBody(request: ChatRequest): String {
        requireNoReasoningHistory(request)
        requireNoAssistantImages(request.messages)
        val sb = StringBuilder()
        sb.append("{\"model\":${q(request.model)},\"stream\":true")
        sb.append(",\"max_tokens\":${request.maxTokens ?: DEFAULT_MAX_TOKENS}")
        request.temperature?.let { sb.append(",\"temperature\":$it") }
        val systems = ArrayList<String>()
        if (request.systemPromptOverride != null) systems.add(request.systemPromptOverride)
        for (m in request.messages) if (m.role == "system") systems.add(m.text)
        if (systems.isNotEmpty()) sb.append(",\"system\":${q(systems.joinToString("\n"))}")
        if (request.tools.isNotEmpty()) {
            sb.append(",\"tools\":[")
            request.tools.forEachIndexed { i, t ->
                if (i > 0) sb.append(',')
                sb.append(anthropicToolJson(t))
            }
            sb.append(']')
        }
        sb.append(",\"messages\":[")
        var first = true
        val resolved = validateToolPairing(request.messages, false)
        val pendingResults = ArrayList<String>()
        fun flushPendingResults() {
            if (pendingResults.isEmpty()) return
            if (!first) sb.append(',')
            first = false
            sb.append("{\"role\":\"user\",\"content\":[")
            pendingResults.forEachIndexed { idx, r ->
                if (idx > 0) sb.append(',')
                sb.append(r)
            }
            sb.append("]}")
            pendingResults.clear()
        }
        for ((i, m) in request.messages.withIndex()) {
            if (m.role == "system") continue
            if (m.role == "tool") {
                // Phase 2: tool turns are user-carried tool_result blocks paired
                // with the assistant tool_use id. Consecutive outputs merge
                // into one user message (single framing); validation stays
                // per-message fail-closed via resolved ids.
                val toolUseId = resolved[i]!!
                pendingResults.add(anthropicToolResultJson(toolUseId, m.text))
                continue
            }
            flushPendingResults()
            if (!first) sb.append(',')
            first = false
            val parts = ArrayList<String>()
            if (m.text.isNotEmpty()) parts.add("{\"type\":\"text\",\"text\":${q(m.text)}}")
            val infos = m.images.map { ImageFallbackPolicy.fromChatImage(it) }
            when (val d = ImageFallbackPolicy.decide(infos, supportsImages = true)) {
                is ImageFallbackPolicy.Decision.Reject ->
                    throw ProviderFailure(false, d.reason)
                is ImageFallbackPolicy.Decision.StripAll -> Unit
                is ImageFallbackPolicy.Decision.Send -> {
                    val encoder = java.util.Base64.getEncoder()
                    for (idx in d.indices) {
                        val img = m.images[idx]
                        val part = StringBuilder("{\"type\":\"image\",\"source\":{\"type\":\"base64\",")
                        part.append("\"media_type\":${q(img.mimeType)},")
                        part.append("\"data\":${q(encoder.encodeToString(img.bytes))}}}")
                        parts.add(part.toString())
                    }
                }
            }
            if (m.toolCalls.isNotEmpty()) {
                for (tc in m.toolCalls) {
                    parts.add(anthropicToolUseJson(tc))
                }
            }
            // Defense-in-depth: a user-empty message is unreachable in production
            // (non-blank admission plus the ephemeral time block always add text, and
            // StripAll needs supportsImages=false while production always passes true),
            // but an empty assistant history entry is reachable, so the guard stays.
            if (parts.isEmpty()) throw ProviderFailure(false, "ANTHROPIC_EMPTY_CONTENT")
            sb.append("{\"role\":${q(m.role)},\"content\":[${parts.joinToString(",")}]}")
        }
        flushPendingResults()
        sb.append("]}")
        return sb.toString()
    }

    companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val DEFAULT_MAX_TOKENS = 1024
    }
}

/** Pure Anthropic SSE projection; no I/O, JVM-testable. */
internal class AnthropicMapper(val round: Int = 0) {
    private enum class BlockKind { TEXT, THINKING, TOOL, OTHER }

    private class Block(var kind: BlockKind, var toolId: String = "", var toolName: String = "") {
        val args = StringBuilder()
        var closed = false
    }

    private val blocks = LinkedHashMap<Int, Block>()
    // Per-attempt whole-round tool total across all block indexes (Finding
    // #1). Monotonic across content_block_delta / content_block_stop arrivals.
    // The two non-monotonic transitions are TOOL→TOOL re-start at a known
    // index (retires the outgoing block, then re-bills the incoming pair) and
    // TOOL→non-TOOL demotion (retires only), so the total stays the true sum
    // of currently-buffered chars.
    private var totalAggChars = 0
    // Distinct TOOL-kind blocks only; TEXT/THINKING/OTHER do not bump it.
    // The shared cap is broader: any distinct block index of any kind is
    // charged pre-insert against MAX_TOOL_CALLS_PER_ATTEMPT, while a
    // replacement at a known index is not charged a new admission (the char
    // total above still re-bills a TOOL->TOOL pair, by design).
    private var toolBlockCount = 0
    private var inputTokens: Int? = null
    private var outputTokens: Int? = null
    private var stopReason: String? = null
    private var terminal: StreamEvent? = null
    // Sticky JSON-depth breach latch (fail-closed): once a frame exceeds
    // MiniJson.MAX_DEPTH, mapPayload throws typed non-retryable and every
    // later mapPayload rethrows the same way, so message_stop can never
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
                // it would lose its delta and let message_stop succeed without
                // it (fake success), or degrade at EOF to a retryable
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
        when (type) {
            "message_start" -> {
                root.obj("message")?.obj("usage")?.let { u ->
                    inputTokens = u.int("input_tokens")
                    out.add(StreamEvent.Usage(inputTokens, null))
                }
            }
            "content_block_start" -> {
                val index = root.int("index") ?: return emptyList()
                val cb = root.obj("content_block") ?: return emptyList()
                // C1: bound the blocks map itself. A distinct-index flood of
                // non-tool blocks (text/thinking/other) must not grow memory
                // without bound: without this, only TOOL blocks counted toward
                // the cap and N distinct text blocks grew the map freely.
                // Reuses the same distinct-index budget and typed failure
                // (TOOL_ARGS_TOO_LARGE / AGG_TOO_LARGE) as tools — one
                // semantics for every aggregation overflow. Checked BEFORE
                // insert so the overflowing entry is never buffered;
                // replacements at a known index do not grow the map.
                if (!blocks.containsKey(index)) {
                    requireToolCountBudget(blocks.size + 1)
                }
                val kind = when (cb.string("type")) {
                    "text" -> BlockKind.TEXT
                    "thinking", "redacted_thinking" -> BlockKind.THINKING
                    "tool_use" -> BlockKind.TOOL
                    else -> BlockKind.OTHER
                }
                val b = Block(kind)
                val prev = blocks[index]
                if (prev?.kind == BlockKind.TOOL) {
                    // Retire the outgoing TOOL block's counted chars whenever
                    // its index is re-started as TOOL (re-billed below) or
                    // demoted to a non-TOOL kind: totalAggChars then stays the
                    // true sum of the chars still buffered, so a hostile cycle
                    // re-sending one index with equal-length id/name (or
                    // tool→text→tool churn) cannot inflate it into a spurious
                    // TOOL_ARGS_TOO_LARGE. Floored at 0 defensively: those
                    // chars were counted when the block arrived.
                    totalAggChars = (
                        totalAggChars - prev.toolId.length - prev.toolName.length - prev.args.length
                        ).coerceAtLeast(0)
                }
                if (kind == BlockKind.TOOL) {
                    if (prev?.kind != BlockKind.TOOL) {
                        // Belt-and-braces: toolBlockCount equals the number of
                        // TOOL entries in blocks after every applied event (a
                        // TOOL start adds one, a demotion drops one, a re-start
                        // re-bills chars only), and blocks.size is capped
                        // pre-insert above, so on entry here the pre-charge
                        // tally is <= 63 and after the increment below the
                        // post-insert total is <= 64: this charge never
                        // rejects; an all-TOOL flood is stopped by the
                        // pre-insert check.
                        requireToolCountBudget(toolBlockCount + 1)
                        toolBlockCount++
                    }
                    b.toolId = cb.string("id").orEmpty()
                    b.toolName = cb.string("name").orEmpty()
                    requireTotalToolAggBudget(totalAggChars, b.toolId.length + b.toolName.length)
                    totalAggChars += b.toolId.length + b.toolName.length
                    out.add(StreamEvent.ToolDelta(index, b.toolId.ifEmpty { null }, b.toolName.ifEmpty { null }, ""))
                } else if (prev?.kind == BlockKind.TOOL) {
                    toolBlockCount--
                }
                if (kind == BlockKind.THINKING) {
                    // Thinking / redacted_thinking block: reasoning origin with
                    // no replayable payload — presence marker only.
                    out.add(StreamEvent.ReasoningPresent)
                }
                blocks[index] = b
            }
            "content_block_delta" -> {
                val index = root.int("index") ?: return emptyList()
                val block = blocks[index] ?: return emptyList()
                val delta = root.obj("delta") ?: return emptyList()
                when (delta.string("type")) {
                    "text_delta" -> {
                        val t = delta.string("text") ?: return emptyList()
                        if (block.kind == BlockKind.OTHER) block.kind = BlockKind.TEXT
                        // Empty-delta suppression (parity with
                        // ChatCompletions): an empty frame yields NO event —
                        // it must not count as final-answer evidence
                        // downstream (continuation completeness, maintainer
                        // P1). Block-kind bookkeeping above is unchanged.
                        if (t.isNotEmpty()) out.add(StreamEvent.TextDelta(round, index, t))
                    }
                    "thinking_delta" -> {
                        val t = delta.string("thinking") ?: return emptyList()
                        // Same suppression: an empty reasoning frame would
                        // flip the reasoning-source flag downstream with zero
                        // payload; it is dropped here instead.
                        if (t.isNotEmpty()) out.add(StreamEvent.ReasoningDelta(round, index, t))
                    }
                    "signature_delta" -> out.add(StreamEvent.ReasoningPresent) // presence marker only; never displayed, never persisted
                    "input_json_delta" -> {
                        if (block.kind != BlockKind.TOOL) return emptyList()
                        val frag = delta.string("partial_json").orEmpty()
                        // Fail-closed aggregation budget (typed, non-retryable).
                        requireToolAggBudget(block.toolId.length + block.toolName.length + block.args.length, frag.length)
                        requireTotalToolAggBudget(totalAggChars, frag.length)
                        block.args.append(frag)
                        totalAggChars += frag.length
                        out.add(StreamEvent.ToolDelta(index, null, null, frag))
                    }
                }
            }
            "content_block_stop" -> {
                val index = root.int("index") ?: return emptyList()
                val block = blocks[index] ?: return emptyList()
                block.closed = true
                if (block.kind == BlockKind.TOOL) {
                    // Blank-id repair at the terminal (parity with
                    // ResponsesProvider's output_item.done): a tool_use block
                    // that started WITHOUT an `id` must not emit an empty
                    // tool-call id — the internal pairing validator
                    // (ProviderJson TOOL_CALL_MISSING_ID, non-retryable) and
                    // providers alike fail closed on one. Same synthetic shape
                    // (`call_` + 8 hex chars). The synthetic default was
                    // billed as 0 chars at content_block_start, so its growth
                    // is charged here (per-key + round total, checked BEFORE
                    // write/emit) — same posture as the Responses mapper.
                    // NOTE blank tool `name`: no fabrication — a nameless
                    // block rides its (possibly empty) name verbatim; the
                    // turn layer scrubs blank names to `tool` in records and
                    // its records-only gate on the exact slice tool name can
                    // never execute a nameless call (fail-closed).
                    val doneId = block.toolId.ifEmpty {
                        val syntheticId = "call_" + java.util.UUID.randomUUID().toString().take(8)
                        requireToolIdBudget(syntheticId.length)
                        requireTotalToolAggBudget(totalAggChars, syntheticId.length)
                        totalAggChars += syntheticId.length
                        syntheticId
                    }
                    out.add(StreamEvent.ToolDone(index, doneId, block.toolName, block.args.toString()))
                }
            }
            "message_delta" -> {
                root.obj("delta")?.string("stop_reason")?.let { stopReason = it }
                root.obj("usage")?.int("output_tokens")?.let {
                    outputTokens = it
                    out.add(StreamEvent.Usage(inputTokens, outputTokens))
                }
            }
            "message_stop" -> {
                val open = blocks.values.any { !it.closed && (it.kind == BlockKind.TEXT || it.kind == BlockKind.TOOL) }
                if (open || stopReason == null) {
                    terminal = StreamEvent.Failed("SSE_TRUNCATED", retryable = true)
                } else {
                    terminal = StreamEvent.Done(stopReason!!)
                }
                out.add(terminal!!)
                terminalReached = true
            }
            "ping" -> Unit
            "message_error", "error" -> {
                val msg = root.obj("error")?.string("message")
                    ?: root.obj("message")?.string("text")
                    ?: "ANTHROPIC_ERROR"
                val kind = ProviderErrorClassifier.classify(null, null, msg)
                terminal = StreamEvent.Failed(redactedError("ANTHROPIC_ERROR", msg), kind == FailureKind.RETRYABLE)
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
        terminal?.let { return emptyList() }
        return listOf(StreamEvent.Failed("SSE_TRUNCATED", retryable = true))
    }
}
