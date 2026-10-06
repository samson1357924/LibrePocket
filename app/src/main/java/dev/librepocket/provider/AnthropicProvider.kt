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
 * - `thinking_delta` -> [StreamEvent.ReasoningDelta]; signature/encrypted
 *   blocks are never displayed and never persisted (simply not emitted).
 * - `tool_use` blocks are recorded only (P1 executes nothing); a later turn
 *   never auto-returns `tool_result` (`toolResultPending` is a P2 concern).
 * - Missing `message_stop` or unclosed visible/tool blocks ->
 *   `Failed(retryable=true)`.
 */
class AnthropicProvider(
    private val config: ProviderConfig,
    private val apiKey: suspend () -> CharArray?,
    client: OkHttpClient? = null,
) : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.ANTHROPIC
    private val httpClient: OkHttpClient by lazy { client ?: defaultOkHttpClient(config.http) }

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
            val url = endpoint(config.baseUrl)
            runWithRetry(config.http) { _ ->
                val mapper = AnthropicMapper()
                pumpSse(httpClient, postJson(url, headers, body)) { frame ->
                    emitAll(mapper.mapPayload(frame.data))
                    if (mapper.terminalReached) return@pumpSse
                }
                emitAll(mapper.finish())
            }
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
            httpClient.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val kind = ProviderErrorClassifier.classify(resp.code, null, text)
                    throw ProviderFailure(kind == FailureKind.RETRYABLE, redactedError("HTTP ${resp.code}", text))
                }
                return extractModelIds(text)
            }
        } finally {
            key.fill('\u0000')
        }
    }

    internal fun endpoint(base: String): String = joinEndpoint(base, "/v1/messages")

    internal fun buildBody(request: ChatRequest): String {
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
                sb.append("{\"name\":${q(t.name)},\"description\":${q(t.description)},")
                sb.append("\"input_schema\":${t.jsonSchema}}}")
            }
            sb.append(']')
        }
        sb.append(",\"messages\":[")
        var first = true
        for (m in request.messages) {
            if (m.role == "system") continue
            if (!first) sb.append(',')
            first = false
            if (m.role == "tool") {
                // P1 records tool_use without executing; a tool turn is kept
                // as a user turn carrying the result text (no auto tool_result
                // round-trip until P2).
                sb.append("{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",")
                sb.append("\"tool_use_id\":${q(m.toolCallId.orEmpty())},")
                sb.append("\"content\":${q(m.text)}}]}")
                continue
            }
            val blocks = StringBuilder("[{\"type\":\"text\",\"text\":${q(m.text)}}")
            val infos = m.images.map { ImageFallbackPolicy.fromChatImage(it) }
            when (val d = ImageFallbackPolicy.decide(infos, supportsImages = true)) {
                is ImageFallbackPolicy.Decision.Reject ->
                    throw ProviderFailure(false, d.reason)
                is ImageFallbackPolicy.Decision.StripAll -> Unit
                is ImageFallbackPolicy.Decision.Send -> {
                    val encoder = java.util.Base64.getEncoder()
                    for (idx in d.indices) {
                        val img = m.images[idx]
                        blocks.append(",{\"type\":\"image\",\"source\":{\"type\":\"base64\",")
                        blocks.append("\"media_type\":${q(img.mimeType)},")
                        blocks.append("\"data\":${q(encoder.encodeToString(img.bytes))}}}")
                    }
                }
            }
            if (m.toolCalls.isNotEmpty()) {
                for (tc in m.toolCalls) {
                    blocks.append(",{\"type\":\"tool_use\",\"id\":${q(tc.id)},")
                    blocks.append("\"name\":${q(tc.name)},\"input\":${tc.argumentsJson.ifEmpty { "{}" }}}")
                }
            }
            blocks.append(']')
            sb.append("{\"role\":${q(m.role)},\"content\":$blocks}")
        }
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
    private var inputTokens: Int? = null
    private var outputTokens: Int? = null
    private var stopReason: String? = null
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
                val kind = when (cb.string("type")) {
                    "text" -> BlockKind.TEXT
                    "thinking", "redacted_thinking" -> BlockKind.THINKING
                    "tool_use" -> BlockKind.TOOL
                    else -> BlockKind.OTHER
                }
                val b = Block(kind)
                if (kind == BlockKind.TOOL) {
                    b.toolId = cb.string("id").orEmpty()
                    b.toolName = cb.string("name").orEmpty()
                    out.add(StreamEvent.ToolDelta(index, b.toolId.ifEmpty { null }, b.toolName.ifEmpty { null }, ""))
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
                        out.add(StreamEvent.TextDelta(round, index, t))
                    }
                    "thinking_delta" -> {
                        val t = delta.string("thinking") ?: return emptyList()
                        out.add(StreamEvent.ReasoningDelta(round, index, t))
                    }
                    "signature_delta" -> Unit // never displayed, never persisted
                    "input_json_delta" -> {
                        val frag = delta.string("partial_json").orEmpty()
                        block.args.append(frag)
                        out.add(StreamEvent.ToolDelta(index, null, null, frag))
                    }
                }
            }
            "content_block_stop" -> {
                val index = root.int("index") ?: return emptyList()
                val block = blocks[index] ?: return emptyList()
                block.closed = true
                if (block.kind == BlockKind.TOOL) {
                    out.add(StreamEvent.ToolDone(index, block.toolId, block.toolName, block.args.toString()))
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
        terminal?.let { return emptyList() }
        return listOf(StreamEvent.Failed("SSE_TRUNCATED", retryable = true))
    }
}
