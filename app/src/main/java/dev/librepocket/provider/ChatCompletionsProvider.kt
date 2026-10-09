package dev.librepocket.provider

import dev.librepocket.redact.Redactor
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import okhttp3.OkHttpClient

/**
 * Baseline adapter: OpenAI Chat Completions (SPEC §3.1–§3.2).
 *
 * - `POST {base}/chat/completions` with `"stream": true`.
 * - `choices[].delta.content` -> [StreamEvent.TextDelta];
 *   `delta.reasoning_content` (compat `reasoning` / `reasoning_details`
 *   summary, first non-blank wins per chunk) -> [StreamEvent.ReasoningDelta].
 * - `delta.tool_calls[]` aggregated by `index`; empty id chunks never
 *   overwrite a valid id; missing/conflicting ids are repaired at the end
 *   with response-scoped unique `call_<uuid8>`.
 * - Phase 2 history: assistant-carried `tool_calls[]` paired with `tool`
 *   messages by `tool_call_id`. A stored id is verified; a missing id is
 *   filled only for the single-call case (otherwise fail-closed, no request).
 *   Every request self-contains the full pairing (stateless).
 * - `finish_reason` + `[DONE]` -> [StreamEvent.Done]; missing either ->
 *   `Failed(retryable=true)`.
 */
class ChatCompletionsProvider(
    private val config: ProviderConfig,
    private val apiKey: suspend () -> CharArray?,
    client: OkHttpClient? = null,
) : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
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
            val mapper = ChatCompletionsMapper()
            pumpSse(httpClient, postJson(endpoint(config.baseUrl), headers, body)) { frame ->
                if (frame.isDone) {
                    mapper.markDone()
                    SsePumpDecision.STOP
                } else {
                    for (e in mapper.mapPayload(frame.data)) emit(e)
                    SsePumpDecision.CONTINUE
                }
            }
            for (e in mapper.finish()) emit(e)
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

    internal fun endpoint(base: String): String = joinEndpoint(base, "/chat/completions")

    /**
     * Protocol request JSON. System messages collapse into a single leading
     * `system` message (order preserved); images ride multimodal `content[]`
     * parts; `>4` images are cut with the omission recorded in [BuiltBody].
     */
    internal fun buildBody(request: ChatRequest): String {
        val sb = StringBuilder()
        sb.append("{\"model\":${q(request.model)},\"stream\":true")
        request.maxTokens?.let { sb.append(",\"max_tokens\":$it") }
        request.temperature?.let { sb.append(",\"temperature\":$it") }
        val webSearch = request.webSearchOrNull()
        // 与 Responses 一致：服务端搜索外发提示词，开启时先脱敏；关闭时零变化。
        val serverSearch = webSearch != null
        fun safeText(raw: String): String = if (serverSearch) Redactor.redact(raw).text else raw
        if (request.tools.isNotEmpty()) {
            sb.append(",\"tools\":[")
            request.tools.forEachIndexed { i, t ->
                if (i > 0) sb.append(',')
                sb.append(chatFunctionToolJson(t))
            }
            sb.append(']')
        }
        if (webSearch != null) {
            // Additive：与 tools 并存；厂商不支持时由 HTTP 错误分类为 FATAL 上报。
            sb.append(",${webSearchOptionsJson(webSearch.contextSize)}")
        }
        sb.append(",\"messages\":[")
        var first = true
        val emitMsg = { role: String, contentJson: String ->
            if (!first) sb.append(',')
            first = false
            sb.append("{\"role\":${q(role)},\"content\":$contentJson}")
        }
        val systems = ArrayList<String>()
        if (request.systemPromptOverride != null) systems.add(request.systemPromptOverride)
        for (m in request.messages) if (m.role == "system") systems.add(m.text)
        if (systems.isNotEmpty()) emitMsg("system", q(safeText(systems.joinToString("\n"))))
        val knownToolIds = collectAssistantToolIds(request.messages)
        for (m in request.messages) {
            if (m.role == "system") continue
            val safeM = if (serverSearch) m.copy(text = Redactor.redact(m.text).text) else m
            when {
                m.role == "tool" -> {
                    // Phase 2: stored tool_call_id is verified; a missing id is
                    // filled only for the single-call case, otherwise fail-closed.
                    val resolved = resolveChatToolOutputId(m.toolCallId, knownToolIds)
                    if (!first) sb.append(',')
                    first = false
                    sb.append("{\"role\":\"tool\",\"tool_call_id\":${q(resolved)},")
                    sb.append("\"content\":${q(safeM.text)}}")
                }
                m.images.isEmpty() && m.toolCalls.isEmpty() -> emitMsg(m.role, q(safeM.text))
                else -> {
                    if (!first) sb.append(',')
                    first = false
                    sb.append("{\"role\":${q(m.role)},\"content\":${contentParts(safeM)},")
                    if (m.toolCalls.isNotEmpty()) {
                        sb.append("\"tool_calls\":[")
                        m.toolCalls.forEachIndexed { i, tc ->
                            if (i > 0) sb.append(',')
                            sb.append(chatToolCallJson(tc))
                        }
                        sb.append("],")
                    }
                    sb.append("\"text\":${q(safeM.text)}}")
                }
            }
        }
        sb.append("]}")
        return sb.toString()
    }

    /** Multimodal content parts; images beyond the policy cap are dropped (counted by caller). */
    internal fun contentParts(m: ChatMessage): String {
        val infos = m.images.map { ImageFallbackPolicy.fromChatImage(it) }
        return when (val d = ImageFallbackPolicy.decide(infos, supportsImages = true)) {
            is ImageFallbackPolicy.Decision.Reject ->
                throw ProviderFailure(false, d.reason)
            is ImageFallbackPolicy.Decision.StripAll ->
                "[{\"type\":\"text\",\"text\":${q(m.text)}}]"
            is ImageFallbackPolicy.Decision.Send -> {
                val sb = StringBuilder("[{\"type\":\"text\",\"text\":${q(m.text)}}")
                val encoder = java.util.Base64.getEncoder()
                for (idx in d.indices) {
                    val img = m.images[idx]
                    val b64 = encoder.encodeToString(img.bytes)
                    sb.append(",{\"type\":\"image_url\",\"image_url\":{\"url\":")
                    sb.append(q("data:${img.mimeType};base64,$b64"))
                    sb.append("}}")
                }
                sb.append(']')
                sb.toString()
            }
        }
    }
}

/** Pure payload->event projection; no I/O, JVM-testable. */
internal class ChatCompletionsMapper(val round: Int = 0) {
    private enum class Kind { TEXT, REASONING, TOOL }

    private class AggCall(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

    private var blockIndex = 0
    private var lastKind: Kind? = null
    private val tools = LinkedHashMap<Int, AggCall>()
    private var finishReason: String? = null
    private var usage: StreamEvent.Usage? = null
    private var sawDone = false
    private var failed: StreamEvent.Failed? = null

    fun markDone() {
        sawDone = true
    }

    fun mapPayload(payload: String): List<StreamEvent> {
        if (failed != null) return emptyList()
        val root = try {
            MiniJson.parse(payload) as? MiniJson.JObj ?: return emptyList()
        } catch (_: MiniJson.MiniJsonException) {
            return emptyList() // sniffing rule §3.4: not our shape, ignore
        }
        val out = ArrayList<StreamEvent>()
        val choices = root.arr("choices")?.items?.filterIsInstance<MiniJson.JObj>()
        val choice = choices?.firstOrNull()
        val delta = choice?.obj("delta")
        if (delta != null) {
            // Text (string or multimodal parts array).
            val text = deltaText(delta)
            if (!text.isNullOrEmpty()) {
                switchTo(Kind.TEXT)
                out.add(StreamEvent.TextDelta(round, blockIndex, text))
            }
            // Reasoning (first non-blank representation wins per chunk).
            val reasoning = deltaReasoning(delta)
            if (!reasoning.isNullOrEmpty()) {
                switchTo(Kind.REASONING)
                out.add(StreamEvent.ReasoningDelta(round, blockIndex, reasoning))
            }
            // Tool calls aggregated by index.
            val toolCalls = delta.arr("tool_calls")?.items?.filterIsInstance<MiniJson.JObj>()
            if (toolCalls != null) {
                for (tc in toolCalls) {
                    val index = tc.int("index") ?: 0
                    val agg = tools.getOrPut(index) { AggCall() }
                    switchTo(Kind.TOOL)
                    val idChunk = tc.string("id")?.takeIf { it.isNotEmpty() }
                    if (idChunk != null && agg.id.isEmpty()) agg.id = idChunk
                    val fn = tc.obj("function")
                    val nameChunk = fn?.string("name")?.takeIf { it.isNotEmpty() }
                    if (nameChunk != null && agg.name.isEmpty()) agg.name = nameChunk
                    val argsChunk = fn?.string("arguments").orEmpty()
                    agg.args.append(argsChunk)
                    out.add(StreamEvent.ToolDelta(index, idChunk, nameChunk, argsChunk))
                }
            }
            // 服务端搜索引用标注：之前直接忽略，现以 SERVER_TOOL 文本标记透出；
            // 无标注时零行为变化，且永不产生 ToolDelta/ToolDone。
            if (hasWebSearchCitation(delta)) {
                switchTo(Kind.TEXT)
                out.add(StreamEvent.TextDelta(round, blockIndex, ServerToolTranscript.webSearchMarker("completed")))
            }
        }
        choice?.string("finish_reason")?.let { if (it.isNotEmpty() && it != "null") finishReason = it }
        root.obj("usage")?.let { u ->
            val inp = u.int("prompt_tokens")
            val outp = u.int("completion_tokens")
            if (inp != null || outp != null) {
                usage = StreamEvent.Usage(inp, outp)
                out.add(usage!!)
            }
        }
        return out
    }

    /** Terminal projection: ToolDones -> Usage? -> Done, else Failed(retryable). */
    fun finish(): List<StreamEvent> {
        failed?.let { return listOf(it) }
        val out = ArrayList<StreamEvent>()
        val usedIds = LinkedHashSet<String>()
        for ((index, agg) in tools) {
            var id = agg.id
            if (id.isEmpty() || !usedIds.add(id)) {
                do {
                    id = "call_" + UUID.randomUUID().toString().take(8)
                } while (!usedIds.add(id))
            }
            out.add(StreamEvent.ToolDone(index, id, agg.name, agg.args.toString()))
        }
        // Usage was already emitted inline when seen; keep terminal compact.
        if (finishReason != null && sawDone) {
            out.add(StreamEvent.Done(finishReason!!))
        } else {
            out.add(StreamEvent.Failed("SSE_TRUNCATED", retryable = true))
        }
        return out
    }

    private fun switchTo(kind: Kind) {
        if (lastKind != null && lastKind != kind) blockIndex++
        lastKind = kind
    }

    private fun deltaText(delta: MiniJson.JObj): String? {
        delta.string("content")?.let { return it }
        val parts = delta.arr("content")?.items?.filterIsInstance<MiniJson.JObj>() ?: return null
        val sb = StringBuilder()
        for (p in parts) {
            val t = p.string("text")
            if (!t.isNullOrEmpty()) sb.append(t)
        }
        return sb.toString().ifEmpty { null }
    }

    private fun deltaReasoning(delta: MiniJson.JObj): String? {
        delta.string("reasoning_content")?.takeIf { it.isNotEmpty() }?.let { return it }
        delta.string("reasoning")?.takeIf { it.isNotEmpty() }?.let { return it }
        val details = delta.arr("reasoning_details")?.items?.filterIsInstance<MiniJson.JObj>()
        if (details != null) {
            for (d in details) {
                d.string("summary")?.takeIf { it.isNotEmpty() }?.let { return it }
                d.string("text")?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return null
    }
}

/** `delta.annotations[]` 中出现 `url_citation` 即视为服务端搜索引用（静态标记，不透传 URL）。 */
internal fun hasWebSearchCitation(delta: MiniJson.JObj): Boolean {
    val annotations = delta.arr("annotations")?.items?.filterIsInstance<MiniJson.JObj>() ?: return false
    return annotations.any { it.string("type") == "url_citation" }
}

/** Extract `data[].id` from a `GET /models` response (tolerates envelope variants). */
internal fun extractModelIds(json: String): List<String> {
    val root = try {
        MiniJson.parse(json) as? MiniJson.JObj ?: return emptyList()
    } catch (_: MiniJson.MiniJsonException) {
        return emptyList()
    }
    val arr = root.arr("data")?.items?.filterIsInstance<MiniJson.JObj>() ?: return emptyList()
    return arr.mapNotNull { it.string("id") }
}

/** Narrowing collector helper to keep provider bodies small. */
internal suspend fun FlowCollector<StreamEvent>.emitAll(events: List<StreamEvent>) {
    for (e in events) emit(e)
}
