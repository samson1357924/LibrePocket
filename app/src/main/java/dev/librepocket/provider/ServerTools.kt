package dev.librepocket.provider

/**
 * 服务端联网搜索透传（默认关闭）。
 *
 * - [ChatRequest.serverTools] 为空时行为与之前完全一致（零行为变化）。
 * - 目前只支持一种服务端工具：[ServerTool.WebSearch]，映射到 Responses 的
 *   hosted `type:web_search` 与 Chat Completions 的 `web_search_options`。
 * - 本地 function tool（[ToolSchema]/[ToolCall]）语义保持不变：服务端搜索
 *   永远不产生 [StreamEvent.ToolDelta]/[StreamEvent.ToolDone]，其可见文本
 *   仍走 [StreamEvent.TextDelta]，转录层以 [ServerToolTranscript] 标记区分。
 */
enum class WebSearchContextSize(val wireValue: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
}

/** 服务端工具集合（wire 侧由各 provider 透传，本地不执行）。 */
sealed interface ServerTool {
    /** 服务端联网搜索；[contextSize] 只影响引用篇幅，不改变本地语义。 */
    data class WebSearch(
        val contextSize: WebSearchContextSize = WebSearchContextSize.MEDIUM,
    ) : ServerTool
}

/** 取第一个 WebSearch（目前只支持一个；多个时取首个，保持可预测）。 */
internal fun ChatRequest.webSearchOrNull(): ServerTool.WebSearch? =
    serverTools.filterIsInstance<ServerTool.WebSearch>().firstOrNull()

/** Responses hosted tool 片段：`{"type":"web_search","search_context_size":"…"}`。 */
internal fun webSearchToolJson(size: WebSearchContextSize): String =
    "{\"type\":\"web_search\",\"search_context_size\":${q(size.wireValue)}}"

/** Chat Completions 附加字段片段：`"web_search_options":{"search_context_size":"…"}`。 */
internal fun webSearchOptionsJson(size: WebSearchContextSize): String =
    "\"web_search_options\":{\"search_context_size\":${q(size.wireValue)}}"

/**
 * 转录标记：服务端搜索与本地 tool call 的区分点。
 *
 * - 本地 tool 由 [TurnController][dev.librepocket.chat.TurnController] 写成
 *   `[tool:<name> <args>]` 并经 `TranscriptSink.onToolDone` 记录；
 * - 服务端搜索不走该路径，而是以 `[SERVER_TOOL:web_search <state>]`
 *   的纯文本增量（[StreamEvent.TextDelta]）出现在助手文本中。
 *
 * 标记为静态字符串，不携带用户原文，避免把敏感查询带入转录。
 */
object ServerToolTranscript {
    const val MARK = "SERVER_TOOL"
    const val WEB_SEARCH_NAME = "web_search"

    /** 服务端搜索状态标记（`state` 仅限白名单字面，避免透传厂商原文）。 */
    fun webSearchMarker(state: String): String = "\n[SERVER_TOOL:web_search $state]\n"

    fun isServerToolMarker(text: String): Boolean = text.contains("[$MARK:")

    fun isLocalToolMarker(text: String): Boolean = text.contains("[tool:")
}
