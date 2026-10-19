package dev.librepocket.chat

import dev.librepocket.provider.AnthropicProvider
import dev.librepocket.provider.ChatCompletionsProvider
import dev.librepocket.provider.ChatMessage
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.ProviderConfig
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.ResponsesProvider
import dev.librepocket.provider.ToolCall
import dev.librepocket.provider.jsonEscape
import dev.librepocket.provider.q
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2B wire-budget pins for the conservative continuation accounting in
 * `TurnController.kt` ([jsonEscapedChars], [ChatMessage.wirePayloadChars],
 * [requestWireEstimate]).
 *
 * Two non-tautological anchors:
 * 1. [jsonEscapedChars] is compared against the REAL escapers of the three
 *    adapters (`jsonEscape`/`q` from ProviderTransport, the kotlinx
 *    `JsonPrimitive` path behind ProviderJson's `buildJsonObject` fragments)
 *    over an adversarial corpus. The estimate must be >= each actual escaped
 *    length, or the fail-close invariant is a lie.
 * 2. [serializedBodiesStayWithinRequestEstimate] serializes an adversarial
 *    message list through all three provider `buildBody` paths (tools empty,
 *    the continuation shape) and asserts each real body string fits the
 *    estimate — the pin that any wrapper/escaping gap fails CI instead of
 *    silently shipping an over-cap request.
 */
class TurnControllerWireEstimateTest {

    /** Adversarial corpus: every escaping class the three escapers handle. */
    private val corpus: List<String> = listOf(
        "",
        "plain ascii",
        "quote:\" backslash:\\ end",
        "named:\b \u000C \n \r \t end",
        "c0:\u0000\u0001\u001F end",
        "emoji pair:\uD83D\uDE00 end",
        "lone high:\uD83D end",
        "lone low:\uDC00 end",
        "mixed:\"\\\u0000\n\t\uD83D\uDE00\uD83D\uDC00\uD83D end",
        "non-bmp é水\u2028\u2029 end",
        "del:\u007F end",
    )

    private fun chatConfig() = ProviderConfig(
        id = "00000000-0000-0000-0000-00000000be01",
        label = "test",
        baseUrl = "https://api.example.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        apiKeyRef = "provider_key/00000000-0000-0000-0000-00000000be01",
    )

    private fun responsesConfig() = ProviderConfig(
        id = "00000000-0000-0000-0000-00000000be02",
        label = "test",
        baseUrl = "https://api.example.com/v1",
        protocol = ProviderProtocol.RESPONSES,
        apiKeyRef = "provider_key/00000000-0000-0000-0000-00000000be02",
    )

    private fun anthropicConfig() = ProviderConfig(
        id = "00000000-0000-0000-0000-00000000be03",
        label = "test",
        baseUrl = "https://api.example.com",
        protocol = ProviderProtocol.ANTHROPIC,
        apiKeyRef = "provider_key/00000000-0000-0000-0000-00000000be03",
    )

    @Test
    fun jsonEscapedCharsPinsEscapingSemantics() {
        assertEquals(0, jsonEscapedChars(""))
        assertEquals(1, jsonEscapedChars("x"))
        assertEquals(1, jsonEscapedChars("é"))
        assertEquals(2, jsonEscapedChars("\""))
        assertEquals(2, jsonEscapedChars("\\"))
        assertEquals(2, jsonEscapedChars("\b"))
        assertEquals(2, jsonEscapedChars("\u000C"))
        assertEquals(2, jsonEscapedChars("\n"))
        assertEquals(2, jsonEscapedChars("\r"))
        assertEquals(2, jsonEscapedChars("\t"))
        assertEquals(6, jsonEscapedChars("\u0000"))
        assertEquals(6, jsonEscapedChars("\u0001"))
        assertEquals(6, jsonEscapedChars("\u001F"))
        assertEquals(2, jsonEscapedChars("\uD83D\uDE00")) // valid pair stays one pair
        assertEquals(6, jsonEscapedChars("\uD83D")) // lone high: \uXXXX cost
        assertEquals(6, jsonEscapedChars("\uDC00")) // lone low: \uXXXX cost
        assertEquals(8, jsonEscapedChars("\uD83D\uDE00\uD83D")) // pair + lone high
    }

    @Test
    fun jsonEscapedCharsOverCountsEveryProjectEscaper() {
        for (s in corpus) {
            val estimate = jsonEscapedChars(s)
            val providerEscaped = jsonEscape(s).length
            val quotedEscaped = q(s).length - 2 // strip the two wrapper quotes
            val kotlinxEscaped = JsonPrimitive(s).toString().length - 2
            assertTrue(
                "provider jsonEscape(${s.length} chars) exceeds estimate: $providerEscaped > $estimate",
                providerEscaped <= estimate,
            )
            assertTrue(
                "provider q(${s.length} chars) exceeds estimate: $quotedEscaped > $estimate",
                quotedEscaped <= estimate,
            )
            assertTrue(
                "kotlinx JsonPrimitive(${s.length} chars) exceeds estimate: $kotlinxEscaped > $estimate",
                kotlinxEscaped <= estimate,
            )
        }
    }

    @Test
    fun wrappersAndEnvelopeAreCountedOncePerScope() {
        val empty = ChatMessage(role = "user", text = "")
        assertEquals(MESSAGE_WRAPPER_CHARS, empty.wirePayloadChars())
        val twoCalls = ChatMessage(
            role = "assistant",
            text = "",
            toolCalls = listOf(ToolCall("a", "b", "{}"), ToolCall("c", "d", "{}")),
        )
        assertEquals(
            MESSAGE_WRAPPER_CHARS + 2 * (TOOL_CALL_WRAPPER_CHARS + 1 + 1 + 2),
            twoCalls.wirePayloadChars(),
        )
        val messages = listOf(empty, twoCalls)
        assertEquals(
            messages.sumOf { it.wirePayloadChars() } + REQUEST_ENVELOPE_CHARS,
            requestWireEstimate(messages),
        )
        // The tool-result id serializes on every adapter: its escaped form is
        // a per-message term, not part of any wrapper constant.
        val withId = ChatMessage(role = "tool", text = "", toolCallId = "\"\\")
        assertEquals(MESSAGE_WRAPPER_CHARS + 4, withId.wirePayloadChars())
    }

    /** Adversarial request in the continuation shape: no tools, paired round. */
    private fun adversarialRequest(): ChatRequest {
        // Valid JSON object args exercising escaped quotes/backslashes, a JSON
        // \u0001 escape, a raw surrogate pair and a lone-surrogate escape.
        val args = "{\"q\":\"quote\\\" back\\\\ ctl\\u0001 emoji\uD83D\uDE00 lone\\uD83D end\"}"
        val text = "lead \"quote\" back\\slash \u0000\u0001\u001F \n\t\r\b\u000C " +
            "slash/ emoji \uD83D\uDE00 lone-high \uD83D lone-low \uDC00 é水"
        val id = "call-\"\\\u0001\uD83D\uDE00\uD83D"
        return ChatRequest(
            model = "slice-model",
            messages = listOf(
                ChatMessage(role = "system", text = text),
                ChatMessage(role = "user", text = text),
                ChatMessage(
                    role = "assistant",
                    text = text,
                    toolCalls = listOf(ToolCall(id, "lsp.\"symbols\\\u0002", args)),
                ),
                ChatMessage(role = "tool", text = text, toolCallId = id),
            ),
            tools = emptyList(),
        )
    }

    /** Every provider body for [request] must fit [requestWireEstimate]. */
    private fun assertBodiesWithinEstimate(request: ChatRequest, label: String) {
        val estimate = requestWireEstimate(request.messages)
        val bodies = mapOf(
            "Chat Completions" to ChatCompletionsProvider(chatConfig(), { null }).buildBody(request),
            "Responses" to ResponsesProvider(responsesConfig(), { null }).buildBody(request),
            "Anthropic" to AnthropicProvider(anthropicConfig(), { null }).buildBody(request),
        )
        for ((adapter, body) in bodies) {
            assertTrue(
                "$label: $adapter body ${body.length} exceeds request estimate $estimate",
                body.length <= estimate,
            )
        }
    }

    @Test
    fun serializedBodiesStayWithinRequestEstimate() {
        val request = adversarialRequest()
        assertBodiesWithinEstimate(request, "adversarial")
        // Non-tautology control: the naive raw-length basis (the pre-Phase-2B
        // formula) is strictly smaller on this corpus, i.e. the estimate is
        // carrying real escaping/wrapper expansion, yet still covers the wire.
        val estimate = requestWireEstimate(request.messages)
        val rawBasis = request.messages.sumOf { m ->
            m.text.length + (m.toolCallId?.length ?: 0) +
                m.toolCalls.sumOf { tc -> tc.id.length + tc.name.length + tc.argumentsJson.length }
        }
        assertTrue("corpus must exercise escaping expansion beyond raw lengths", rawBasis < estimate)
    }

    /** One assistant message with [calls] distinct tool calls plus results. */
    private fun multiCallRequest(calls: Int): ChatRequest {
        val toolCalls = (0 until calls).map { i ->
            ToolCall("call_$i", "lsp.symbols", """{"query":"q$i","maxResults":$i}""")
        }
        return ChatRequest(
            model = "slice-model",
            messages = buildList {
                add(ChatMessage(role = "user", text = "find symbols"))
                add(ChatMessage(role = "assistant", text = "working", toolCalls = toolCalls))
                for (tc in toolCalls) {
                    add(ChatMessage(role = "tool", text = "{\"symbols\":\"${tc.id}\"}", toolCallId = tc.id))
                }
            },
            tools = emptyList(),
        )
    }

    @Test
    fun serializedMultiCallAndEmptyTextShapesStayWithinRequestEstimate() {
        assertBodiesWithinEstimate(multiCallRequest(3), "multi-call")
        // Continuation assistant with no visible text: the call itself is the
        // whole assistant turn. (A completely empty assistant is rejected by
        // AnthropicProvider.buildBody, so it never reaches the wire.)
        assertBodiesWithinEstimate(
            ChatRequest(
                model = "slice-model",
                messages = listOf(
                    ChatMessage(role = "user", text = "u"),
                    ChatMessage(
                        role = "assistant",
                        text = "",
                        toolCalls = listOf(ToolCall("c-empty", "lsp.symbols", "{}")),
                    ),
                    ChatMessage(role = "tool", text = "{}", toolCallId = "c-empty"),
                ),
                tools = emptyList(),
            ),
            "empty-text assistant",
        )
    }

    @Test
    fun serializedToolResultContinuationShapesStayWithinRequestEstimate() {
        // The production continuation shape: assistant tool call(s) + paired
        // tool results, no tools array, one escaped-and-normal text tail.
        val request = ChatRequest(
            model = "slice-model",
            messages = listOf(
                ChatMessage(role = "user", text = "u"),
                ChatMessage(
                    role = "assistant",
                    text = "",
                    toolCalls = listOf(
                        ToolCall("c1", "lsp.symbols", """{"query":"a"}"""),
                        ToolCall("c2", "lsp.symbols", """{"query":"b"}"""),
                    ),
                ),
                ChatMessage(role = "tool", text = "{\"symbols\":1}", toolCallId = "c1"),
                ChatMessage(role = "tool", text = "{\"symbols\":2}", toolCallId = "c2"),
                ChatMessage(role = "user", text = "continue \"quoted\""),
            ),
            tools = emptyList(),
        )
        assertBodiesWithinEstimate(request, "tool-result continuation")
    }
}
