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
 * - `response.output_text.delta` -> [StreamEvent.TextDelta] with
 *   `blockIndex = output_index * 1000 + content_index` (§3.5 mapping).
 * - `reasoning_summary_text.delta` / `reasoning_text.delta` ->
 *   [StreamEvent.ReasoningDelta].
 * - `response.output_item.added/done` (function_call) -> [StreamEvent.ToolDelta] /
 *   [StreamEvent.ToolDone], distinguished by `item_id/output_index/content_index`.
 * - `response.completed` is the authoritative terminal; an empty `output`
 *   array recovers from already-streamed deltas (P1 simplification).
 * - `response.failed` / `response.incomplete` -> [StreamEvent.Failed].
 * - Opaque fields (encrypted reasoning etc.) stay in memory only and are
 *   never surfaced as events.
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
                sb.append("{\"type\":\"function\",\"name\":${q(t.name)},")
                sb.append("\"description\":${q(t.description)},\"parameters\":${t.jsonSchema}}}")
            }
            if (webSearch != null) {
                if (ti > 0) sb.append(',')
                sb.append(webSearchToolJson(webSearch.contextSize))
            }
            sb.append(']')
        }
        sb.append(",\"input\":[")
        var first = true
        for (m in request.messages) {
            if (m.role == "system") continue
            if (!first) sb.append(',')
            first = false
            val safeText = if (serverSearch) Redactor.redact(m.text).text else m.text
            sb.append("{\"type\":\"message\",\"role\":${q(if (m.role == "tool") "user" else m.role)},")
            sb.append("\"content\":[{\"type\":\"input_text\",\"text\":${q(safeText)}}")
            val infos = m.images.map { ImageFallbackPolicy.fromChatImage(it) }
            when (val d = ImageFallbackPolicy.decide(infos, supportsImages = true)) {
                is ImageFallbackPolicy.Decision.Reject ->
                    throw ProviderFailure(false, d.reason)
                is ImageFallbackPolicy.Decision.StripAll -> Unit
                is ImageFallbackPolicy.Decision.Send -> {
                    val encoder = java.util.Base64.getEncoder()
                    for (idx in d.indices) {
                        sb.append(",{\"type\":\"input_image\",\"image_url\":")
                        sb.append(q("data:${m.images[idx].mimeType};base64,${encoder.encodeToString(m.images[idx].bytes)}"))
                        sb.append('}')
                    }
                }
            }
            sb.append("]}")
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
    private var usage: StreamEvent.Usage? = null
    private var terminal: StreamEvent? = null
    var terminalReached: Boolean = false
        private set

    fun mapPayload(payload: String): List<StreamEvent> {
        if (terminal != null) return emptyList()
        val root = try {
            MiniJson.parse(payload) as? MiniJson.JObj ?: return emptyList()
        } catch (_: MiniJson.MiniJsonException) {
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
                out.add(StreamEvent.TextDelta(round, blockOf(root), delta))
            }
            "response.reasoning_summary_text.delta",
            "response.reasoning_text.delta",
            -> {
                val delta = root.string("delta") ?: return emptyList()
                out.add(StreamEvent.ReasoningDelta(round, blockOf(root), delta))
            }
            "response.output_item.added" -> {
                val item = root.obj("item") ?: return emptyList()
                if (item.string("type") == "web_search_call") {
                    out.add(StreamEvent.TextDelta(round, blockOf(root), ServerToolTranscript.webSearchMarker("started")))
                    return out
                }
                if (item.string("type") != "function_call") return emptyList()
                val itemId = root.string("item_id") ?: item.string("id") ?: ("item_" + UUID.randomUUID().toString().take(8))
                val agg = callsByItem.getOrPut(itemId) { AggCall(toolIndex = nextToolIndex++) }
                item.string("call_id")?.takeIf { it.isNotEmpty() }?.let { if (agg.id.isEmpty()) agg.id = it }
                item.string("name")?.takeIf { it.isNotEmpty() }?.let { if (agg.name.isEmpty()) agg.name = it }
                out.add(StreamEvent.ToolDelta(agg.toolIndex, agg.id.ifEmpty { null }, agg.name.ifEmpty { null }, ""))
            }
            "response.function_call_arguments.delta" -> {
                val itemId = root.string("item_id") ?: return emptyList()
                val agg = callsByItem.getOrPut(itemId) { AggCall(toolIndex = nextToolIndex++) }
                val frag = root.string("delta").orEmpty()
                agg.args.append(frag)
                out.add(StreamEvent.ToolDelta(agg.toolIndex, null, null, frag))
            }
            "response.output_item.done" -> {
                val item = root.obj("item") ?: return emptyList()
                if (item.string("type") == "web_search_call") {
                    out.add(StreamEvent.TextDelta(round, blockOf(root), ServerToolTranscript.webSearchMarker("completed")))
                    return out
                }
                if (item.string("type") != "function_call") return emptyList()
                val itemId = root.string("item_id") ?: item.string("id") ?: return emptyList()
                val agg = callsByItem.getOrPut(itemId) { AggCall(toolIndex = nextToolIndex++) }
                item.string("call_id")?.takeIf { it.isNotEmpty() }?.let { agg.id = it }
                item.string("name")?.takeIf { it.isNotEmpty() }?.let { agg.name = it }
                item.string("arguments")?.let { if (agg.args.isEmpty()) agg.args.append(it) }
                if (agg.id.isEmpty()) agg.id = "call_" + UUID.randomUUID().toString().take(8)
                emittedToolIds.add(agg.toolIndex to agg.id)
                out.add(StreamEvent.ToolDone(agg.toolIndex, agg.id, agg.name, agg.args.toString()))
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
                if (agg.id.isEmpty()) agg.id = "call_" + UUID.randomUUID().toString().take(8)
                // Avoid double-emitting tools already closed via output_item.done:
                // callers track ToolDone ids; duplicates collapse downstream by
                // (toolIndex, id). Only emit when never emitted — mappers own
                // this set per stream.
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
