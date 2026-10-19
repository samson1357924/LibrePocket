package dev.librepocket.chat

import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol as ProviderProto
import dev.librepocket.provider.ServerToolTranscript
import dev.librepocket.provider.StreamEvent
import dev.librepocket.router.SafeToolSlice
import dev.librepocket.tool.LspTools
import dev.librepocket.tool.ProjectionContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * P2 safe slice (#19) turn-engine tests: the `lsp.symbols` closed loop
 * through the REAL [TurnController].
 *
 * Chain per test: UI send → request tools → model ToolDone → schema
 * validation → latest scope/policy re-check → executor → typed result →
 * provider continuation → final UI + durable (recording) transcript.
 * Deny/cancel/schema-error paths pin backend invocation count zero.
 */
class TurnControllerToolSliceTest {

    private val fixedClock: Clock =
        Clock.fixed(Instant.parse("2026-10-09T01:41:22Z"), ZoneId.of("UTC"))

    private class SliceProvider(
        var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
    ) : LlmProvider {
        override val protocol: ProviderProto = ProviderProto.CHAT_COMPLETIONS
        val seenRequests: MutableList<ChatRequest> = Collections.synchronizedList(mutableListOf())

        override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
            seenRequests.add(request)
            emitAll(handler(request))
        }

        override suspend fun listModels(): List<String> = emptyList()
    }

    private class SliceSink : TranscriptSink {
        val started: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
        val succeeded: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
        data class Tool(val runId: String, val toolIndex: Int, val id: String, val name: String, val args: String)
        val tools: MutableList<Tool> = Collections.synchronizedList(mutableListOf())
        val cancelled: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
        data class FailedRecord(val runId: String, val partialText: String, val error: String, val isFinal: Boolean)
        val failedRecords: MutableList<FailedRecord> = Collections.synchronizedList(mutableListOf())

        override suspend fun onTurnStarted(runId: String, text: String) { started.add(runId to text) }
        override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) {
            succeeded.add(runId to text)
        }
        override suspend fun onTurnFailed(runId: String, error: String) = Unit
        override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
        override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) {
            cancelled.add(runId to partialText)
        }
        override suspend fun onTurnSucceeded(
            runId: String,
            text: String,
            parentRunId: String?,
            attemptIndex: Int?,
            hasReasoning: Boolean,
        ) = onTurnSucceeded(runId, text, hasReasoning)
        override suspend fun onTurnFailed(
            runId: String,
            partialText: String,
            error: String,
            parentRunId: String?,
            attemptIndex: Int?,
        ) = Unit
        override suspend fun onTurnFailed(
            runId: String,
            partialText: String,
            error: String,
            parentRunId: String?,
            attemptIndex: Int?,
            isFinal: Boolean,
        ) {
            failedRecords.add(FailedRecord(runId, partialText, error, isFinal))
        }
        override suspend fun onTurnFailed(
            runId: String,
            partialText: String,
            error: String,
            parentRunId: String?,
            attemptIndex: Int?,
            isFinal: Boolean,
            hasReasoning: Boolean,
        ) {
            failedRecords.add(FailedRecord(runId, partialText, error, isFinal))
        }
        override suspend fun onTurnCancelled(
            runId: String,
            partialText: String,
            parentRunId: String?,
            attemptIndex: Int?,
            hasReasoning: Boolean,
        ) = onTurnCancelled(runId, partialText, hasReasoning)
        override suspend fun onSteerQueued(text: String) = Unit
        override suspend fun onToolDone(runId: String, toolIndex: Int, id: String, name: String, argumentsJson: String) {
            tools.add(Tool(runId, toolIndex, id, name, argumentsJson))
        }
        override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) = Unit
    }

    private class CountingBackend : LspTools.Backend {
        val invocations = AtomicInteger(0)
        override fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> {
            invocations.incrementAndGet()
            return listOf(LspTools.Symbol("$query:example", "function", "app/src/main/Example.kt", 1))
        }
        override fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
    }

    /** Backend that returns a fixed symbol list (used to size tool results). */
    private class FatBackend(private val symbols: List<LspTools.Symbol>) : LspTools.Backend {
        val invocations = AtomicInteger(0)
        override fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> {
            invocations.incrementAndGet()
            return symbols
        }
        override fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
    }

    private fun policy(vararg rules: PolicyRule) = InMemoryPolicyStore(rules.toList())

    private fun slicePolicy(vararg extra: PolicyRule) = policy(
        PolicyRule("chat.send:*", Verdict.ALLOW, 10),
        PolicyRule("tool.exec:lsp.symbols", Verdict.ALLOW, 10),
        *extra,
    )

    private fun loop(
        policy: PolicyStore,
        backend: LspTools.Backend,
        switches: Map<String, Boolean> = mapOf("lsp" to true),
        maxCallsPerTurn: Int = 1,
    ) = SafeToolSlice(
        ctxSupplier = { ProjectionContext(userSwitches = switches) },
        policy = policy,
        backend = backend,
        clock = fixedClock,
        maxCallsPerTurn = maxCallsPerTurn,
    )

    private fun controller(
        provider: SliceProvider,
        policy: PolicyStore,
        sink: TranscriptSink,
        toolSlice: SafeToolSlice?,
    ): TurnController {
        var n = 0
        return TurnController(
            provider = provider,
            policy = policy,
            transcript = sink,
            retryConfig = TurnRetryConfig(),
            dispatcher = Dispatchers.Default,
            sleeper = {},
            newId = { "slice-${n++}" },
            toolSlice = toolSlice,
        )
    }

    private fun ChatRequest.toolResultTexts(): List<String> =
        messages.filter { it.role == "tool" }.map { it.text }

    @Test fun happyRoundTripPairsCallIdToFinalAnswer() = runBlocking {
        val provider = SliceProvider()
        val sink = SliceSink()
        val backend = CountingBackend()
        val pol = slicePolicy()
        val c = controller(provider, pol, sink, loop(pol, backend))
        provider.handler = { request ->
            if (request.messages.none { it.role == "tool" }) {
                flow {
                    emit(
                        StreamEvent.ToolDone(
                            toolIndex = 0,
                            id = "call-1",
                            name = "lsp.symbols",
                            argumentsJson = """{"query":"Foo"}""",
                        ),
                    )
                    emit(StreamEvent.Done("tool_calls"))
                }
            } else {
                flow {
                    emit(StreamEvent.TextDelta(0, 0, "found Foo:example"))
                    emit(StreamEvent.Done("stop"))
                }
            }
        }
        c.send("幫我查 Foo 這個符號")

        // Request tools: first request offers exactly the slice tool.
        assertEquals(2, provider.seenRequests.size)
        assertEquals(listOf("lsp.symbols"), provider.seenRequests[0].tools.map { it.name })
        // Continuation carries assistant toolCalls + the paired tool result.
        val cont = provider.seenRequests[1]
        assertTrue("continuation round must carry no tools (one-tool-round budget on the wire)", cont.tools.isEmpty())
        val assistant = cont.messages.first { it.role == "assistant" && it.toolCalls.isNotEmpty() }
        assertEquals("call-1", assistant.toolCalls.single().id)
        assertEquals("lsp.symbols", assistant.toolCalls.single().name)
        val toolMsg = cont.messages.first { it.role == "tool" }
        assertEquals("call-1", toolMsg.toolCallId)
        val body = Json.parseToJsonElement(toolMsg.text).jsonObject
        assertEquals("false", body["denied"]!!.jsonPrimitive.content)
        assertEquals(fixedClock.millis(), body["clockMillis"]!!.jsonPrimitive.content.toLong())
        // Executor ran exactly once; UI + transcript closed the family.
        assertEquals(1, backend.invocations.get())
        val ui = c.uiState.value
        assertEquals(ChatStatus.IDLE, ui.status)
        val final = ui.messages.last { it.role == "assistant" }
        assertTrue(final.text.contains("found Foo:example"))
        assertTrue(final.text.contains("[tool:lsp.symbols"))
        assertTrue(final.text.contains("[tool-result:lsp.symbols"))
        assertEquals(1, sink.started.size)
        assertEquals(1, sink.succeeded.size)
        assertTrue(sink.succeeded.single().second.contains("found Foo:example"))
        assertEquals(listOf("call-1"), sink.tools.map { it.id })
    }

    @Test fun schemaErrorStillPairsWithZeroSideEffects() = runBlocking {
        val provider = SliceProvider()
        val sink = SliceSink()
        val backend = CountingBackend()
        val pol = slicePolicy()
        val c = controller(provider, pol, sink, loop(pol, backend))
        provider.handler = { request ->
            if (request.messages.none { it.role == "tool" }) {
                flow {
                    emit(
                        StreamEvent.ToolDone(
                            toolIndex = 0,
                            id = "call-bad",
                            name = "lsp.symbols",
                            argumentsJson = "{oops",
                        ),
                    )
                    emit(StreamEvent.Done("tool_calls"))
                }
            } else {
                flow {
                    emit(StreamEvent.TextDelta(0, 0, "need a symbol name"))
                    emit(StreamEvent.Done("stop"))
                }
            }
        }
        c.send("查一下")

        assertEquals(0, backend.invocations.get())
        assertEquals(2, provider.seenRequests.size)
        val paired = provider.seenRequests[1].toolResultTexts().single()
        assertTrue(paired.contains(SafeToolSlice.DenyCodes.SCHEMA_INVALID))
        assertTrue(paired.contains("call-bad"))
        assertEquals(ChatStatus.IDLE, c.uiState.value.status)
        assertEquals(1, sink.succeeded.size)
    }

    @Test fun policyDenyStillPairsWithZeroSideEffects() = runBlocking {
        val provider = SliceProvider()
        val sink = SliceSink()
        val backend = CountingBackend()
        val pol = policy(
            PolicyRule("chat.send:*", Verdict.ALLOW, 10),
            PolicyRule("tool.exec:lsp.symbols", Verdict.DENY, 10),
        )
        val c = controller(provider, pol, sink, loop(pol, backend))
        provider.handler = { request ->
            if (request.messages.none { it.role == "tool" }) {
                flow {
                    emit(
                        StreamEvent.ToolDone(
                            toolIndex = 0,
                            id = "call-deny",
                            name = "lsp.symbols",
                            argumentsJson = """{"query":"Foo"}""",
                        ),
                    )
                    emit(StreamEvent.Done("tool_calls"))
                }
            } else {
                flow {
                    emit(StreamEvent.TextDelta(0, 0, "cannot run tools right now"))
                    emit(StreamEvent.Done("stop"))
                }
            }
        }
        c.send("幫我查 Foo")

        assertEquals(0, backend.invocations.get())
        assertEquals(2, provider.seenRequests.size)
        val paired = provider.seenRequests[1].toolResultTexts().single()
        assertTrue(paired.contains(SafeToolSlice.DenyCodes.POLICY_DENIED))
        assertTrue(paired.contains("call-deny"))
        assertEquals(ChatStatus.IDLE, c.uiState.value.status)
    }

    @Test fun switchOffDeniesAtExecutionWithZeroSideEffects() = runBlocking {
        // Request-time offer is empty (pure chat) AND execution re-checks the
        // latest scope: both layers deny, backend untouched.
        val provider = SliceProvider()
        val sink = SliceSink()
        val backend = CountingBackend()
        val pol = slicePolicy()
        val c = controller(provider, pol, sink, loop(pol, backend, switches = mapOf("lsp" to false)))
        provider.handler = { request ->
            if (request.messages.none { it.role == "tool" }) {
                flow {
                    emit(
                        StreamEvent.ToolDone(
                            toolIndex = 0,
                            id = "call-scope",
                            name = "lsp.symbols",
                            argumentsJson = """{"query":"Foo"}""",
                        ),
                    )
                    emit(StreamEvent.Done("tool_calls"))
                }
            } else {
                flow {
                    emit(StreamEvent.TextDelta(0, 0, "scope off"))
                    emit(StreamEvent.Done("stop"))
                }
            }
        }
        c.send("幫我查 Foo")

        assertTrue(provider.seenRequests[0].tools.isEmpty())
        assertEquals(0, backend.invocations.get())
        assertEquals(2, provider.seenRequests.size)
        val paired = provider.seenRequests[1].toolResultTexts().single()
        assertTrue(paired.contains(SafeToolSlice.DenyCodes.SCOPE_DENIED))
        assertEquals(ChatStatus.IDLE, c.uiState.value.status)
    }

    @Test fun nonSliceToolStaysRecordsOnlyWithLoopAttached() = runBlocking {
        val provider = SliceProvider()
        val sink = SliceSink()
        val backend = CountingBackend()
        val pol = slicePolicy()
        val c = controller(provider, pol, sink, loop(pol, backend))
        provider.handler = {
            flow {
                emit(
                    StreamEvent.ToolDone(
                        toolIndex = 0,
                        id = "call-other",
                        name = "shell.exec",
                        argumentsJson = """{"argv":["ls"]}""",
                    ),
                )
                emit(StreamEvent.TextDelta(0, 0, "noted"))
                emit(StreamEvent.Done("stop"))
            }
        }
        c.send("跑個命令")

        // No continuation: unwired tools never execute, even with a loop.
        assertEquals(1, provider.seenRequests.size)
        assertEquals(0, backend.invocations.get())
        assertEquals(ChatStatus.IDLE, c.uiState.value.status)
        assertEquals(listOf("call-other"), sink.tools.map { it.id })
        assertEquals(1, sink.succeeded.size)
    }

    @Test fun secondRoundToolCallIsRecordedButNotExecuted() = runBlocking {
        val provider = SliceProvider()
        val sink = SliceSink()
        val backend = CountingBackend()
        val pol = slicePolicy()
        val c = controller(provider, pol, sink, loop(pol, backend))
        provider.handler = { request ->
            if (request.messages.none { it.role == "tool" }) {
                flow {
                    emit(
                        StreamEvent.ToolDone(
                            toolIndex = 0,
                            id = "call-a",
                            name = "lsp.symbols",
                            argumentsJson = """{"query":"A"}""",
                        ),
                    )
                    emit(StreamEvent.Done("tool_calls"))
                }
            } else {
                flow {
                    // Budget: one tool round per turn — this second call must
                    // be recorded only, never executed.
                    emit(
                        StreamEvent.ToolDone(
                            toolIndex = 0,
                            id = "call-b",
                            name = "lsp.symbols",
                            argumentsJson = """{"query":"B"}""",
                        ),
                    )
                    emit(StreamEvent.TextDelta(0, 0, "first answer stands"))
                    emit(StreamEvent.Done("stop"))
                }
            }
        }
        c.send("查 A")

        assertEquals(2, provider.seenRequests.size)
        assertEquals(1, backend.invocations.get())
        assertEquals(listOf("call-a", "call-b"), sink.tools.map { it.id })
        assertEquals(ChatStatus.IDLE, c.uiState.value.status)
        assertTrue(c.uiState.value.messages.last { it.role == "assistant" }.text.contains("first answer stands"))
    }

    @Test fun retryAfterContinuationFailureReattachesExecutedPairWithoutReExecution() = runBlocking {
        // Turn-level budget: the retry after a retryable continuation failure
        // must not run the slice a second time, even if the model re-emits
        // the tool call on the fresh attempt — AND it must re-attach the
        // already-executed structured pair (tools=[] on the wire) so the
        // provider answers with the executed result in view, not blind.
        val provider = SliceProvider()
        val sink = SliceSink()
        val backend = CountingBackend()
        val pol = slicePolicy()
        val c = controller(provider, pol, sink, loop(pol, backend))
        var responses = 0
        provider.handler = { request ->
            responses++
            flow {
                when (responses) {
                    1 -> {
                        emit(
                            StreamEvent.ToolDone(
                                toolIndex = 0,
                                id = "call-a",
                                name = "lsp.symbols",
                                argumentsJson = """{"query":"A"}""",
                            ),
                        )
                        emit(StreamEvent.Done("tool_calls"))
                    }
                    2 -> emit(StreamEvent.Failed("flaky continuation", retryable = true))
                    else -> {
                        // Retry attempt: the model re-emits the call, but the
                        // slice must NOT run a second round this turn.
                        emit(
                            StreamEvent.ToolDone(
                                toolIndex = 0,
                                id = "call-a2",
                                name = "lsp.symbols",
                                argumentsJson = """{"query":"A"}""",
                            ),
                        )
                        emit(StreamEvent.TextDelta(0, 0, "retry answer"))
                        emit(StreamEvent.Done("stop"))
                    }
                }
            }
        }
        c.send("查 A")

        assertEquals(1, backend.invocations.get())
        assertEquals(3, provider.seenRequests.size)
        // Continuation request: executed pair rides the wire, tools stay empty.
        val cont = provider.seenRequests[1]
        assertTrue(cont.tools.isEmpty())
        assertEquals(listOf("call-a"), cont.messages.filter { it.role == "assistant" }.flatMap { it.toolCalls }.map { it.id })
        assertEquals(listOf("call-a"), cont.messages.filter { it.role == "tool" }.map { it.toolCallId })
        // Retried attempt: the executed pair is re-attached (tools stay empty),
        // the record-only re-emission ("call-a2") never rides the wire.
        val retry = provider.seenRequests[2]
        assertTrue(retry.tools.isEmpty())
        assertEquals(
            listOf("call-a"),
            retry.messages.filter { it.role == "assistant" }.flatMap { it.toolCalls }.map { it.id },
        )
        assertEquals(listOf("call-a"), retry.messages.filter { it.role == "tool" }.map { it.toolCallId })
        assertTrue(retry.messages.none { it.role == "assistant" && it.toolCalls.any { it.id == "call-a2" } })
        assertEquals(ChatStatus.IDLE, c.uiState.value.status)
        assertEquals(listOf("call-a", "call-a2"), sink.tools.map { it.id })
        assertEquals(1, sink.succeeded.size)
        assertTrue(sink.succeeded.single().second.contains("retry answer"))
    }

    @Test fun forcedRetryMissingTextDeltaKeepsToolResultAndNoTools() = runBlocking {
        // A continuation that ends Done with no final text is an incomplete
        // tool round: the controller forces a retryable failure. The retry
        // must re-attach the executed pair with tools=[] (result preserved)
        // and must not re-execute the slice.
        val provider = SliceProvider()
        val sink = SliceSink()
        val backend = CountingBackend()
        val pol = slicePolicy()
        val c = controller(provider, pol, sink, loop(pol, backend))
        var responses = 0
        provider.handler = { request ->
            responses++
            flow {
                when (responses) {
                    1 -> {
                        emit(
                            StreamEvent.ToolDone(
                                toolIndex = 0,
                                id = "call-a",
                                name = "lsp.symbols",
                                argumentsJson = """{"query":"A"}""",
                            ),
                        )
                        emit(StreamEvent.Done("tool_calls"))
                    }
                    2 -> {
                        // Continuation ends without any final TextDelta:
                        // incomplete tool continuation → forced retry.
                        emit(StreamEvent.Done("stop"))
                    }
                    else -> {
                        emit(StreamEvent.TextDelta(0, 0, "answered now"))
                        emit(StreamEvent.Done("stop"))
                    }
                }
            }
        }
        c.send("查 A")

        assertEquals(1, backend.invocations.get())
        assertEquals(3, provider.seenRequests.size)
        val retry = provider.seenRequests[2]
        assertTrue(retry.tools.isEmpty())
        assertEquals(listOf("call-a"), retry.messages.filter { it.role == "assistant" }.flatMap { it.toolCalls }.map { it.id })
        assertEquals(listOf("call-a"), retry.messages.filter { it.role == "tool" }.map { it.toolCallId })
        assertEquals(ChatStatus.IDLE, c.uiState.value.status)
        assertEquals(listOf("call-a"), sink.tools.map { it.id })
        assertEquals(1, sink.succeeded.size)
        assertTrue(sink.succeeded.single().second.contains("answered now"))
    }

    @Test fun emptyTextDeltaContinuationCountsAsMissingFinalAnswer() = runBlocking {
        // Maintainer P1: an empty TextDelta must NOT satisfy the continuation
        // final-answer completeness check. The old counter-snapshot check
        // counted any delta ARRIVAL, so a continuation that streamed
        // TextDelta("") frames and closed Done finalized a markers-only
        // "reply" as success. Now the round carries zero final-answer
        // evidence → the retryable "incomplete tool continuation" failure →
        // the executed pair is re-attached on the retry (tools=[], no
        // re-execution) and the turn only succeeds on real visible text.
        val provider = SliceProvider()
        val sink = SliceSink()
        val backend = CountingBackend()
        val pol = slicePolicy()
        val c = controller(provider, pol, sink, loop(pol, backend))
        var responses = 0
        provider.handler = { _ ->
            responses++
            flow {
                when (responses) {
                    1 -> {
                        emit(
                            StreamEvent.ToolDone(
                                toolIndex = 0,
                                id = "call-a",
                                name = "lsp.symbols",
                                argumentsJson = """{"query":"A"}""",
                            ),
                        )
                        emit(StreamEvent.Done("tool_calls"))
                    }
                    2 -> {
                        // Bypass script: deltas ARRIVE but are all empty
                        // (pre-fix this incremented the arrival counter and
                        // finalized the markers-only fragment as success).
                        emit(StreamEvent.TextDelta(0, 0, ""))
                        emit(StreamEvent.TextDelta(0, 0, ""))
                        emit(StreamEvent.Done("stop"))
                    }
                    else -> {
                        emit(StreamEvent.TextDelta(0, 0, "answered now"))
                        emit(StreamEvent.Done("stop"))
                    }
                }
            }
        }
        c.send("查 A")

        // The slice executed exactly once; the incomplete continuation forced
        // a retryable failure that was retried (third provider call), never
        // final.
        assertEquals(1, backend.invocations.get())
        assertEquals(3, provider.seenRequests.size)
        assertTrue(sink.failedRecords.any { it.error == "incomplete tool continuation" && !it.isFinal })
        assertTrue(sink.failedRecords.none { it.isFinal })
        // The retry re-attaches the executed pair (result preserved, tools
        // stay empty — no re-execution, no second tools offer).
        val retry = provider.seenRequests[2]
        assertTrue(retry.tools.isEmpty())
        assertEquals(listOf("call-a"), retry.messages.filter { it.role == "assistant" }.flatMap { it.toolCalls }.map { it.id })
        assertEquals(listOf("call-a"), retry.messages.filter { it.role == "tool" }.map { it.toolCallId })
        // The turn never succeeds with markers-only text: the success row is
        // the retry attempt's own visible text, not the tool-marker fragment.
        assertEquals(ChatStatus.IDLE, c.uiState.value.status)
        assertEquals(listOf("call-a"), sink.tools.map { it.id })
        assertEquals(1, sink.succeeded.size)
        assertTrue(sink.succeeded.single().second.contains("answered now"))
        assertFalse(sink.succeeded.single().second.contains("call-a"))
        assertFalse(sink.succeeded.single().second.contains("[tool-result:"))
    }

    @Test fun reasoningOnlyContinuationCountsAsMissingFinalAnswer() = runBlocking {
        // Maintainer P1 (tightened semantics): a reasoning-only continuation
        // is not a final answer. Thinking deltas streaming to Done used to
        // pass the arrival-counter check; now they carry zero final-answer
        // evidence → the retryable "incomplete tool continuation" failure
        // engages, and only a retry with real text completes the turn.
        val provider = SliceProvider()
        val sink = SliceSink()
        val backend = CountingBackend()
        val pol = slicePolicy()
        val c = controller(provider, pol, sink, loop(pol, backend))
        var responses = 0
        provider.handler = { _ ->
            responses++
            flow {
                when (responses) {
                    1 -> {
                        emit(
                            StreamEvent.ToolDone(
                                toolIndex = 0,
                                id = "call-a",
                                name = "lsp.symbols",
                                argumentsJson = """{"query":"A"}""",
                            ),
                        )
                        emit(StreamEvent.Done("tool_calls"))
                    }
                    2 -> {
                        emit(StreamEvent.ReasoningDelta(0, 0, "thinking about the answer…"))
                        emit(StreamEvent.Done("stop"))
                    }
                    else -> {
                        emit(StreamEvent.TextDelta(0, 0, "final answer after thinking"))
                        emit(StreamEvent.Done("stop"))
                    }
                }
            }
        }
        c.send("查 A")

        assertEquals(1, backend.invocations.get())
        assertEquals(3, provider.seenRequests.size)
        assertTrue(sink.failedRecords.any { it.error == "incomplete tool continuation" && !it.isFinal })
        assertTrue(sink.failedRecords.none { it.isFinal })
        assertEquals(ChatStatus.IDLE, c.uiState.value.status)
        assertEquals(1, sink.succeeded.size)
        assertTrue(sink.succeeded.single().second.contains("final answer after thinking"))
        assertFalse(sink.succeeded.single().second.contains("thinking about the answer"))
    }

    @Test fun serverToolMarkerDeltaDoesNotSatisfyContinuationEvidence() = runBlocking {
        // Maintainer P1 (server-tool exclusion): a TextDelta that is only the
        // `[SERVER_TOOL:…]` web-search marker is not final-answer evidence
        // either — a continuation closing Done with just the marker must not
        // succeed. The marker delta rides TextDelta per ServerTools
        // (ServerToolTranscript.webSearchMarker), so the evidence gate
        // excludes it explicitly.
        val provider = SliceProvider()
        val sink = SliceSink()
        val backend = CountingBackend()
        val pol = slicePolicy()
        val c = controller(provider, pol, sink, loop(pol, backend))
        var responses = 0
        provider.handler = { _ ->
            responses++
            flow {
                when (responses) {
                    1 -> {
                        emit(
                            StreamEvent.ToolDone(
                                toolIndex = 0,
                                id = "call-a",
                                name = "lsp.symbols",
                                argumentsJson = """{"query":"A"}""",
                            ),
                        )
                        emit(StreamEvent.Done("tool_calls"))
                    }
                    2 -> {
                        emit(StreamEvent.TextDelta(0, 0, ServerToolTranscript.webSearchMarker("started")))
                        emit(StreamEvent.Done("stop"))
                    }
                    else -> {
                        emit(StreamEvent.TextDelta(0, 0, "real answer now"))
                        emit(StreamEvent.Done("stop"))
                    }
                }
            }
        }
        c.send("查 A")

        assertEquals(1, backend.invocations.get())
        assertEquals(3, provider.seenRequests.size)
        assertTrue(sink.failedRecords.any { it.error == "incomplete tool continuation" && !it.isFinal })
        // The marker delta landed as visible text on the failed (partial)
        // attempt, but that attempt never became a success.
        assertTrue(
            sink.failedRecords.any { !it.isFinal && it.partialText.contains("[SERVER_TOOL:web_search") },
        )
        assertEquals(1, sink.succeeded.size)
        assertTrue(sink.succeeded.single().second.contains("real answer now"))
        assertFalse(sink.succeeded.single().second.contains("[SERVER_TOOL"))
        assertEquals(ChatStatus.IDLE, c.uiState.value.status)
    }

    @Test fun continuationAccountingIncludesToolCallsPayloadAndTrims() = runBlocking {
        // Request-level budget regression: the continuation accounting must
        // count the structured assistant toolCalls payload (the args echo
        // alone can reach MAX_INPUT_CHARS) and the tool-result
        // ChatMessage.toolCallId (shared `wirePayloadChars` helper), not only
        // ChatMessage.text. The
        // history length is calibrated so the discriminator holds: a
        // TEXT-ONLY accounting of the whole continuation fits the cap —
        // 98,500 history + the two user rows and time block (~100) + the
        // ~1.3k tool result ≈ 99.9k ≤ MAX_REQUEST_CHARS — so pre-fix
        // code skips the trim entirely and ships ≈104k serialized (this
        // assert would fail), while counting the structured toolCalls
        // payload (id + name + 4k args echo) breaches the cap, so the
        // builder head-trims until the serialized accounting fits
        // MAX_REQUEST_CHARS (the other exit being the typed over-budget
        // fail-close).
        val provider = SliceProvider()
        val sink = SliceSink()
        val backend = CountingBackend()
        val pol = slicePolicy()
        val c = controller(provider, pol, sink, loop(pol, backend))
        val nearCapHistory = "a".repeat(98_500)
        val echoArgs = """{"query":"${"x".repeat(4_000)}"}"""
        var responses = 0
        provider.handler = { _ ->
            responses++
            flow {
                when (responses) {
                    1 -> {
                        emit(StreamEvent.TextDelta(0, 0, nearCapHistory))
                        emit(StreamEvent.Done("stop"))
                    }
                    2 -> {
                        emit(
                            StreamEvent.ToolDone(
                                toolIndex = 0,
                                id = "call-a",
                                name = "lsp.symbols",
                                argumentsJson = echoArgs,
                            ),
                        )
                        emit(StreamEvent.Done("tool_calls"))
                    }
                    else -> {
                        emit(StreamEvent.TextDelta(0, 0, "trimmed answer"))
                        emit(StreamEvent.Done("stop"))
                    }
                }
            }
        }
        c.send("seed")
        c.send("查 A")

        assertEquals(1, backend.invocations.get())
        assertEquals(3, provider.seenRequests.size)
        val cont = provider.seenRequests[2]
        assertTrue(cont.tools.isEmpty())
        assertEquals(listOf("call-a"), cont.messages.filter { it.role == "assistant" }.flatMap { it.toolCalls }.map { it.id })
        assertTrue(cont.messages.any { it.role == "tool" })
        // Serialized accounting (single source of truth: the production
        // `wirePayloadChars` helper — text + toolCallId + the structured
        // assistant toolCalls) over the outgoing continuation request stays
        // within the request cap; a text-only accounting would have skipped
        // the trim entirely.
        val payload = cont.messages.sumOf { it.wirePayloadChars() }
        assertTrue(payload <= TurnController.MAX_REQUEST_CHARS)
        assertTrue("continuation must head-trim once the args echo rides along", c.requestTrimmedCount.value > 0)
        assertEquals(ChatStatus.IDLE, c.uiState.value.status)
        assertEquals(2, sink.succeeded.size)
        assertTrue(sink.succeeded.last().second.contains("trimmed answer"))
    }

    @Test fun continuationUntrimmableOverBudgetFailsClosedWithoutSending() = runBlocking {
        // Fail-closed exit: when even the untrimmable minimum (the newest
        // base row — here the near-admission-cap user message — plus the
        // structured round) exceeds MAX_REQUEST_CHARS, the turn fails closed
        // with a typed non-retryable terminal and the over-cap continuation
        // request is never sent to the provider.
        val provider = SliceProvider()
        val sink = SliceSink()
        val fatBackend = FatBackend(
            (1..12).map {
                LspTools.Symbol(
                    "\"".repeat(SafeToolSlice.MAX_STRING_CHARS),
                    "function",
                    "p".repeat(SafeToolSlice.MAX_STRING_CHARS),
                    it,
                )
            },
        )
        val pol = slicePolicy()
        val c = controller(provider, pol, sink, loop(pol, fatBackend))
        val fatResultUser = "a".repeat(90_000)
        provider.handler = { _ ->
            flow {
                emit(
                    StreamEvent.ToolDone(
                        toolIndex = 0,
                        id = "call-a",
                        name = "lsp.symbols",
                        argumentsJson = """{"query":"A"}""",
                    ),
                )
                emit(StreamEvent.Done("tool_calls"))
            }
        }
        c.send(fatResultUser)

        // The round executed exactly once, but the continuation was never
        // sent: only the attempt request reached the provider.
        assertEquals(1, fatBackend.invocations.get())
        assertEquals(1, provider.seenRequests.size)
        assertEquals(ChatStatus.ERROR, c.uiState.value.status)
        assertTrue(c.uiState.value.error.orEmpty().contains("over budget"))
        assertTrue(sink.succeeded.isEmpty())
        // Fail-closed durability is observable: the failed terminal was
        // recorded with the persisted executed-round marker.
        assertEquals(1, sink.failedRecords.size)
        val failedRecord = sink.failedRecords.single()
        assertTrue(failedRecord.isFinal)
        assertEquals("continuation request over budget", failedRecord.error)
        assertTrue(failedRecord.partialText.contains("[tool-result:lsp.symbols"))
    }

    @Test fun continuationRoundLevelBudgetFailsClosedWithEightMaxCalls() = runBlocking {
        // Round-level bound: each single result respects MAX_RESULT_CHARS,
        // but maxCallsPerTurn=8 lets the round compound to ~8× the per-result
        // cap — far above MAX_REQUEST_CHARS. The request-level accounting
        // catches the compounding and fails closed instead of shipping an
        // oversized request (the single-result cap alone cannot guarantee a
        // request bound).
        val provider = SliceProvider()
        val sink = SliceSink()
        val fatBackend = FatBackend(
            (1..12).map {
                LspTools.Symbol(
                    "\"".repeat(SafeToolSlice.MAX_STRING_CHARS),
                    "function",
                    "p".repeat(SafeToolSlice.MAX_STRING_CHARS),
                    it,
                )
            },
        )
        val pol = slicePolicy()
        val c = controller(provider, pol, sink, loop(pol, fatBackend, maxCallsPerTurn = 8))
        provider.handler = { _ ->
            flow {
                for (i in 0 until 8) {
                    emit(
                        StreamEvent.ToolDone(
                            toolIndex = i,
                            id = "call-$i",
                            name = "lsp.symbols",
                            argumentsJson = """{"query":"A"}""",
                        ),
                    )
                }
                emit(StreamEvent.Done("tool_calls"))
            }
        }
        c.send("查 A")

        // All eight calls executed (round-level legality), but the
        // continuation over-cap never shipped.
        assertEquals(8, fatBackend.invocations.get())
        assertEquals(1, provider.seenRequests.size)
        assertEquals(ChatStatus.ERROR, c.uiState.value.status)
        assertTrue(c.uiState.value.error.orEmpty().contains("over budget"))
        assertTrue(sink.succeeded.isEmpty())
        // Fail-closed durability is observable: the failed terminal was
        // recorded with the persisted executed-round marker.
        assertEquals(1, sink.failedRecords.size)
        val failedRecord = sink.failedRecords.single()
        assertTrue(failedRecord.isFinal)
        assertEquals("continuation request over budget", failedRecord.error)
        assertTrue(failedRecord.partialText.contains("[tool-result:lsp.symbols"))
    }

    @Test fun continuationAccountingIncludesToolCallIdFailsClosed() = runBlocking {
        // Maintainer P2: the tool-result `ChatMessage.toolCallId` serializes
        // on the wire through ALL three adapters (`tool_call_id` /
        // `function_call_output.call_id` / `tool_result.tool_use_id`), but the
        // pre-fix accounting summed only text + the assistant toolCalls
        // (id/name/argumentsJson) — an under-count of up to
        // 8 × SafeToolSlice.MAX_STRING_CHARS = 4,000 chars
        // (maxCallsPerTurn=8 × the redacted call-id echo cap per tool row).
        // Discriminator: everything EXCEPT the toolCallId term is built so
        // the untrimmable minimum (the newest base row + the structured
        // round) fits MAX_REQUEST_CHARS under the text-only counterfactual —
        // the eight 500-char ids are the DECISIVE surplus. Pre-fix code would
        // have shipped the over-cap continuation (seenRequests would grow);
        // the fixed accounting fails closed: typed non-retryable terminal,
        // no over-cap request sent (provider.seenRequests stays at the
        // attempt round).
        val provider = SliceProvider()
        val sink = SliceSink()
        val backend = CountingBackend()
        val pol = slicePolicy()
        val toolLoop = loop(pol, backend, maxCallsPerTurn = 8)
        val c = controller(provider, pol, sink, toolLoop)
        // Call ids that SURVIVE redaction unchanged at the full echo cap
        // (500 chars, alphanumeric+dash — no key/secret/EMAIL/phone/IP/geo
        // shape), distinct per call: "call-" + 492 × 'a' + index (100..107).
        val fatId = { i: Int -> "call-" + "a".repeat(492) + (100 + i) }
        // Calibrated so ALL non-toolCallId payload (user row + time block +
        // assistant id/name/args echo + result texts) stays ≤
        // MAX_REQUEST_CHARS ≊ 97.8k, and only the +4,000 id term breaches it.
        val history = "a".repeat(87_400)
        provider.handler = { _ ->
            flow {
                for (i in 0 until 8) {
                    emit(
                        StreamEvent.ToolDone(
                            toolIndex = i,
                            id = fatId(i),
                            name = "lsp.symbols",
                            argumentsJson = """{"query":"A"}""",
                        ),
                    )
                }
                emit(StreamEvent.Done("tool_calls"))
            }
        }
        c.send(history)

        // The round executed exactly once (all eight calls), but the
        // over-cap continuation never shipped.
        assertEquals(8, backend.invocations.get())
        assertEquals(8, sink.tools.size)
        assertEquals(1, provider.seenRequests.size)
        assertEquals(ChatStatus.ERROR, c.uiState.value.status)
        assertTrue(c.uiState.value.error.orEmpty().contains("over budget"))
        assertTrue(sink.succeeded.isEmpty())
        // Fail-closed durability is observable: the failed terminal was
        // recorded with the persisted executed-round marker.
        assertEquals(1, sink.failedRecords.size)
        val failedRecord = sink.failedRecords.single()
        assertTrue(failedRecord.isFinal)
        assertEquals("continuation request over budget", failedRecord.error)
        assertTrue(failedRecord.partialText.contains("[tool-result:lsp.symbols"))

        // Discriminator proof: rebuild the SAME wire-shaped structured block
        // through the PRODUCTION builder (continuationMessages), take the
        // base contribution from the REAL observed attempt request via the
        // SHARED accounting helper, and show the text-only counterfactual
        // fits the cap while the toolCallId-inclusive total breaches it.
        val markerLens = Regex("""\[tool-result:lsp\.symbols:(\d{1,8}):""")
            .findAll(failedRecord.partialText)
            .map { m ->
                val len = m.groupValues[1].toInt()
                // Length-prefixed framing asserts itself (TurnController's
                // marker format): the sliced JSON is closed by a `]` marker
                // terminator exactly <len> UTF-16 units after `<len>:`.
                assertEquals(']', failedRecord.partialText[m.range.last + 1 + len])
                failedRecord.partialText.substring(m.range.last + 1, m.range.last + 1 + len)
            }
            .toList()
        assertEquals(8, markerLens.size)
        val wireCalls = sink.tools.map { dev.librepocket.provider.ToolCall(it.id, it.name, it.args) }
        val wireResults = (0 until 8).map { i ->
            SafeToolSlice.ToolResult(callId = fatId(i), name = "lsp.symbols", resultJson = markerLens[i])
        }
        val structured = toolLoop.continuationMessages(wireCalls, wireResults)
        // Redaction honesty of the fixture: every call id arrives on the
        // rebuilt wire rows at the full echo cap — the ids are NOT the
        // under-count shadow of a redaction slimming.
        assertEquals(8, structured.count { it.toolCallId?.length == SafeToolSlice.MAX_STRING_CHARS })
        val basePayload = provider.seenRequests.single().messages.sumOf { it.wirePayloadChars() }
        // Text-only counterfactual — the PRE-fix formula (text + assistant
        // toolCalls id/name/args, NO ChatMessage.toolCallId).
        val textOnlyTotal = basePayload + structured.sumOf { m ->
            m.text.length + m.toolCalls.sumOf { t -> t.id.length + t.name.length + t.argumentsJson.length }
        }
        val toolCallIdTerm = structured.sumOf { (it.toolCallId?.length ?: 0) }
        assertEquals(8 * SafeToolSlice.MAX_STRING_CHARS, toolCallIdTerm)
        val inclusiveTotal = textOnlyTotal + toolCallIdTerm
        assertTrue(
            "text-only counterfactual must fit the cap (ids alone are the decisive surplus): $textOnlyTotal",
            textOnlyTotal <= TurnController.MAX_REQUEST_CHARS,
        )
        assertTrue(
            "toolCallId-inclusive accounting must breach the cap: $inclusiveTotal",
            inclusiveTotal > TurnController.MAX_REQUEST_CHARS,
        )
    }

    @Test fun cancelDuringContinuationKeepsSingleExecution() = runBlocking {
        val provider = SliceProvider()
        val sink = SliceSink()
        val backend = CountingBackend()
        val pol = slicePolicy()
        val c = controller(provider, pol, sink, loop(pol, backend))
        val contGate = CompletableDeferred<Unit>()
        val contEntered = CompletableDeferred<Unit>()
        provider.handler = { request ->
            if (request.messages.any { it.role == "tool" }) {
                flow {
                    contEntered.complete(Unit)
                    contGate.await()
                    emit(StreamEvent.TextDelta(0, 0, "late"))
                    emit(StreamEvent.Done("stop"))
                }
            } else {
                flow {
                    emit(
                        StreamEvent.ToolDone(
                            toolIndex = 0,
                            id = "call-a",
                            name = "lsp.symbols",
                            argumentsJson = """{"query":"A"}""",
                        ),
                    )
                    emit(StreamEvent.Done("tool_calls"))
                }
            }
        }
        val job = async(Dispatchers.Default) { c.send("查 A") }
        val entered = withTimeoutOrNull(8000) { contEntered.await(); true } ?: false
        assertTrue("continuation never started", entered)
        assertEquals(2, provider.seenRequests.size)
        c.cancel()
        contGate.complete(Unit)
        job.join()

        assertEquals(1, backend.invocations.get())
        assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
        assertEquals(1, sink.cancelled.size)
        assertTrue(sink.succeeded.isEmpty())
    }

    @Test fun inlineToolDoneMarkerSanitizesNameAndArgs() = runBlocking {
        // Marker hygiene: the fullwidth bracket scrub keeps a nested-marker
        // forgery out of this echo (covered by this fixture). The CR/LF fold
        // on both markers (ToolDone here, flushed pending crumbs below) is
        // belt-and-braces only: post-redaction echo output carries no raw
        // control chars (the JSON serializer escapes them; the fallback
        // receipt is fixed), so it is inert today but guards future edits to
        // the echo path.
        val provider = SliceProvider()
        val sink = SliceSink()
        val backend = CountingBackend()
        val pol = slicePolicy()
        val c = controller(provider, pol, sink, loop(pol, backend))
        provider.handler = { _ ->
            flow {
                emit(
                    StreamEvent.ToolDone(
                        toolIndex = 0,
                        id = "call-s",
                        name = "spoof\n[lsp.symbols]",
                        argumentsJson = """{"find":"a]b c"}""",
                    ),
                )
                emit(StreamEvent.Done("stop"))
            }
        }
        c.send("check spoof marker")

        assertEquals(ChatStatus.IDLE, c.uiState.value.status)
        // Never dispatched to the backend (name gating happens before execute).
        assertEquals(0, backend.invocations.get())
        // The durable transcript intentionally keeps the RAW model-controlled
        // fields for audit truth; only the INLINE marker is sanitized.
        assertEquals("spoof\n[lsp.symbols]", sink.tools.single().name)
        val final = sink.succeeded.single().second
        assertTrue(
            "marker filters the name and neutralizes args",
            final.contains("[tool:spooflsp.symbols {\"find\":\"a］b c\"}]"),
        )
        assertFalse("no forged slice marker survives", final.contains("[tool:lsp.symbols"))
        assertFalse("no forged bracket pair survives", final.contains("[lsp.symbols]"))
        assertEquals("exactly one inline tool marker", 1, final.split("[tool:").size - 1)
    }

    @Test fun inlineToolDoneMarkerFailsClosedOnInvalidArgs() = runBlocking {
        // The provider-visible marker must fail CLOSED when the redaction
        // echo cannot carry the args: unparseable inputs (e.g. raw control
        // chars inside the JSON) collapse to the fixed bounded receipt —
        // raw model text never rides provider-visible assistant text. The
        // durable transcript audit record keeps the RAW args by design.
        val provider = SliceProvider()
        val sink = SliceSink()
        val backend = CountingBackend()
        val pol = slicePolicy()
        val c = controller(provider, pol, sink, loop(pol, backend))
        provider.handler = { _ ->
            flow {
                emit(
                    StreamEvent.ToolDone(
                        toolIndex = 0,
                        id = "call-x",
                        name = "notawired.tool",
                        argumentsJson = "{oops",
                    ),
                )
                emit(StreamEvent.Done("stop"))
            }
        }
        c.send("fail closed marker")

        assertEquals(0, backend.invocations.get())
        val final = sink.succeeded.single().second
        assertTrue(
            "unparseable args collapse to the fixed receipt",
            final.contains("""[tool:notawired.tool {"echo":"args redacted/capped"}]"""),
        )
        assertFalse("no raw arg payload rides the marker", final.contains("\"oops\""))
        assertEquals("audit keeps the raw args", "{oops", sink.tools.single().args)
    }

    @Test fun inlineToolDoneMarkerMatchesRedactionPolicy() = runBlocking {
        // FIX B: the provider-visible inline marker follows the same redacted
        // args posture as the continuation echo (adapters re-send assistant
        // text on every later attempt/turn), while the durable transcript
        // audit record keeps the RAW args (deliberate raw audit truth).
        val provider = SliceProvider()
        val sink = SliceSink()
        val backend = CountingBackend()
        val pol = slicePolicy()
        val c = controller(provider, pol, sink, loop(pol, backend))
        provider.handler = { request ->
            if (request.messages.none { it.role == "tool" }) {
                flow {
                    emit(
                        StreamEvent.ToolDone(
                            toolIndex = 0,
                            id = "call-1",
                            name = "lsp.symbols",
                            argumentsJson = """{"password":"hunter2","query":"A"}""",
                        ),
                    )
                    emit(StreamEvent.Done("tool_calls"))
                }
            } else {
                flow {
                    emit(StreamEvent.TextDelta(0, 0, "done"))
                    emit(StreamEvent.Done("stop"))
                }
            }
        }
        c.send("查 A")

        // Executed slice round: marker + audit + continuation all present.
        assertEquals(1, backend.invocations.get())
        val final = sink.succeeded.single().second
        assertTrue("marker framing intact", final.contains("[tool:lsp.symbols "))
        assertTrue("marker carries the redaction receipt", final.contains("⟦REDACTED⟧"))
        assertFalse("marker carries no raw secret", final.contains("hunter2"))
        // Audit keeps the RAW args.
        assertEquals("""{"password":"hunter2","query":"A"}""", sink.tools.single().args)
        // Continuation echo too (belt-and-braces with the slice-side pin).
        val cont = provider.seenRequests[1]
        val assistant = cont.messages.first { it.role == "assistant" && it.toolCalls.isNotEmpty() }
        val echoed = assistant.toolCalls.single().argumentsJson
        assertFalse("continuation echo carries no raw secret", echoed.contains("hunter2"))
        assertTrue(echoed.contains("⟦REDACTED⟧"))
    }

    @Test fun flushedPendingMarkerMatchesRedactionPolicy() = runBlocking {
        // A custom provider may legally end its stream with Done while
        // aggregated ToolDelta args never got a terminal ToolDone (the
        // public LlmProvider contract allows it; built-in adapters drain
        // before Done, so in production this is only reachable through such
        // providers — exactly why the flushed marker must be redacted). The
        // clean-Done flush finalizes the pending crumb inline and its marker
        // rides assistant text like the ToolDone marker, so it takes the
        // same redact-then-scrub posture; the durable audit record keeps
        // the RAW crumb args.
        val provider = SliceProvider()
        val sink = SliceSink()
        val backend = CountingBackend()
        val pol = slicePolicy()
        val c = controller(provider, pol, sink, loop(pol, backend))
        val rawArgs = """{"password":"hunter2","query":"A"}"""
        provider.handler = { _ ->
            flow {
                // StreamEvent.ToolDelta(toolIndex, idChunk, nameChunk,
                // argsChunk): id/name ride the first chunk (first-chunk-wins);
                // the args aggregate across two deltas and no ToolDone follows.
                val head = rawArgs.indexOf(',') + 1
                emit(
                    StreamEvent.ToolDelta(
                        toolIndex = 0,
                        idChunk = "call-p",
                        nameChunk = "lsp.symbols",
                        argsChunk = rawArgs.take(head),
                    ),
                )
                emit(
                    StreamEvent.ToolDelta(
                        toolIndex = 0,
                        idChunk = null,
                        nameChunk = null,
                        argsChunk = rawArgs.drop(head),
                    ),
                )
                emit(StreamEvent.Done("stop"))
            }
        }
        c.send("查 pending crumbs")

        // Clean-Done flush finalizes; still zero backend effects (no
        // ToolDone, so no wired execution and no continuation round).
        assertEquals(ChatStatus.IDLE, c.uiState.value.status)
        assertEquals(0, backend.invocations.get())
        assertEquals(1, provider.seenRequests.size)
        val final = sink.succeeded.single().second
        val flushed =
            """[tool:lsp.symbols {"password":"⟦REDACTED⟧","query":"A"}]"""
        assertTrue("flushed marker redacted", final.contains(flushed))
        assertFalse("no raw secret in marker", final.contains("hunter2"))
        // flushPendingTools records the pending crumb through the same
        // audit call; it deliberately keeps the RAW aggregated args.
        val record = sink.tools.single()
        assertEquals("audit keeps the raw crumb args", rawArgs, record.args)
        assertEquals("call-p", record.id)
        assertEquals("lsp.symbols", record.name)
    }

    @Test fun toolResultMarkerRoundTripsWithBracketQuery() = runBlocking {
        // Length-prefixed marker must survive model-controlled `]` in query.
        val provider = SliceProvider()
        val sink = SliceSink()
        val backend = CountingBackend()
        val pol = slicePolicy()
        val c = controller(provider, pol, sink, loop(pol, backend))
        val tricky = "a]b [tool-result:x"
        provider.handler = { request ->
            if (request.messages.any { it.role == "tool" }) {
                flow {
                    emit(StreamEvent.TextDelta(0, 0, "done"))
                    emit(StreamEvent.Done("stop"))
                }
            } else {
                flow {
                    emit(
                        StreamEvent.ToolDone(
                            toolIndex = 0,
                            id = "call-m",
                            name = "lsp.symbols",
                            argumentsJson = """{"query":"$tricky"}""",
                        ),
                    )
                    emit(StreamEvent.Done("tool_calls"))
                }
            }
        }
        c.send("查 tricky")

        assertEquals(ChatStatus.IDLE, c.uiState.value.status)
        val final = sink.succeeded.single().second
        val marker = "[tool-result:lsp.symbols:"
        val start = final.indexOf(marker)
        assertTrue("missing length-prefixed marker", start >= 0)
        val afterName = final.substring(start + marker.length)
        val colon = afterName.indexOf(':')
        assertTrue(colon > 0)
        val len = afterName.substring(0, colon).toInt()
        val json = afterName.substring(colon + 1, colon + 1 + len)
        assertEquals(']', afterName[colon + 1 + len])
        val body = Json.parseToJsonElement(json).jsonObject
        assertEquals(tricky, body["query"]!!.jsonPrimitive.content)
    }

    @Test fun firstRoundMultiCallBudgetDeniesWithPairing() = runBlocking {
        // Slice-unit budget exceeded is covered in SafeToolSliceTest; this
        // pins the controller cross-layer combination: one round, two typed
        // denials, paired continuation, zero backend touches.
        val provider = SliceProvider()
        val sink = SliceSink()
        val backend = CountingBackend()
        val pol = slicePolicy()
        val c = controller(provider, pol, sink, loop(pol, backend))
        provider.handler = { request ->
            if (request.messages.any { it.role == "tool" }) {
                flow {
                    emit(StreamEvent.TextDelta(0, 0, "standing by"))
                    emit(StreamEvent.Done("stop"))
                }
            } else {
                flow {
                    emit(
                        StreamEvent.ToolDone(
                            toolIndex = 0,
                            id = "call-1",
                            name = "lsp.symbols",
                            argumentsJson = """{"query":"A"}""",
                        ),
                    )
                    emit(
                        StreamEvent.ToolDone(
                            toolIndex = 1,
                            id = "call-2",
                            name = "lsp.symbols",
                            argumentsJson = """{"query":"B"}""",
                        ),
                    )
                    emit(StreamEvent.Done("tool_calls"))
                }
            }
        }
        c.send("查 A 和 B")

        assertEquals(0, backend.invocations.get())
        assertEquals(2, provider.seenRequests.size)
        assertEquals(listOf("call-1", "call-2"), sink.tools.map { it.id })
        val toolMessages = provider.seenRequests[1].messages.filter { it.role == "tool" }
        assertEquals(listOf("call-1", "call-2"), toolMessages.map { it.toolCallId })
        val toolTexts = toolMessages.map { it.text }
        assertEquals(2, toolTexts.size)
        assertTrue(toolTexts.all { it.contains(SafeToolSlice.DenyCodes.BUDGET_EXCEEDED) })
        assertEquals(ChatStatus.IDLE, c.uiState.value.status)
        assertEquals(1, sink.succeeded.size)
        assertTrue(sink.succeeded.single().second.contains("standing by"))
    }

    @Test fun continuationFailureEndsTurnWithoutReplaying() = runBlocking {
        // The executed result is kept (backend ran once); a non-retryable
        // continuation failure ends the turn instead of replaying execution.
        val provider = SliceProvider()
        val sink = SliceSink()
        val backend = CountingBackend()
        val pol = slicePolicy()
        val c = controller(provider, pol, sink, loop(pol, backend))
        provider.handler = { request ->
            if (request.messages.any { it.role == "tool" }) {
                flow {
                    emit(StreamEvent.Failed("continuation exploded", retryable = false))
                }
            } else {
                flow {
                    emit(
                        StreamEvent.ToolDone(
                            toolIndex = 0,
                            id = "call-a",
                            name = "lsp.symbols",
                            argumentsJson = """{"query":"A"}""",
                        ),
                    )
                    emit(StreamEvent.Done("tool_calls"))
                }
            }
        }
        c.send("查 A")

        assertEquals(1, backend.invocations.get())
        assertEquals(2, provider.seenRequests.size)
        assertEquals(listOf("call-a"), sink.tools.map { it.id })
        assertEquals(ChatStatus.ERROR, c.uiState.value.status)
        assertTrue(sink.succeeded.isEmpty())
    }

    @Test fun cancelBeforeExecutionHasZeroSideEffects() = runBlocking {
        val provider = SliceProvider()
        val sink = SliceSink()
        val backend = CountingBackend()
        val pol = slicePolicy()
        val toolSlice = loop(pol, backend)
        val entered = CountDownLatch(1)
        val gate = CompletableDeferred<Unit>()
        toolSlice.beforeExecuteForTest = {
            entered.countDown()
            gate.await()
        }
        val c = controller(provider, pol, sink, toolSlice)
        provider.handler = {
            flow {
                emit(
                    StreamEvent.ToolDone(
                        toolIndex = 0,
                        id = "call-cancel",
                        name = "lsp.symbols",
                        argumentsJson = """{"query":"Foo"}""",
                    ),
                )
                emit(StreamEvent.Done("tool_calls"))
            }
        }
        val job = async(Dispatchers.Default) { c.send("幫我查 Foo") }
        assertTrue("execute never entered", entered.await(8, TimeUnit.SECONDS))
        c.cancel()
        gate.complete(Unit)
        job.join()

        assertEquals(0, backend.invocations.get())
        assertEquals(1, provider.seenRequests.size)
        assertEquals(ChatStatus.CANCELLED, c.uiState.value.status)
    }

    @Test fun noLoopStaysRecordsOnlyAndPureChat() = runBlocking {
        // Control: identical scripted tool call without a loop — no tools on
        // the request, no continuation, marker persisted, turn succeeds.
        val provider = SliceProvider()
        val sink = SliceSink()
        val pol = slicePolicy()
        val c = controller(provider, pol, sink, toolSlice = null)
        provider.handler = {
            flow {
                emit(
                    StreamEvent.ToolDone(
                        toolIndex = 0,
                        id = "call-plain",
                        name = "lsp.symbols",
                        argumentsJson = """{"query":"Foo"}""",
                    ),
                )
                emit(StreamEvent.TextDelta(0, 0, "plain answer"))
                emit(StreamEvent.Done("stop"))
            }
        }
        c.send("幫我查 Foo")

        assertEquals(1, provider.seenRequests.size)
        assertTrue(provider.seenRequests.single().tools.isEmpty())
        assertEquals(ChatStatus.IDLE, c.uiState.value.status)
        assertEquals(listOf("call-plain"), sink.tools.map { it.id })
        assertEquals(1, sink.succeeded.size)
        assertFalse(sink.succeeded.single().second.contains("[tool-result:"))
    }

    @Test fun duplicateCallIdWithinOneResponseExecutesOnlyOnce() = runBlocking {
        // ToolDone dedup is index-based (doneSeen); the same call id at a
        // SECOND index must not execute twice — only the first occurrence
        // flows to the slice, later ones stay records-only so the
        // calls/results pairing stays unique and the budget is not spent
        // twice on one id.
        val provider = SliceProvider()
        val sink = SliceSink()
        val backend = CountingBackend()
        val pol = slicePolicy()
        val c = controller(provider, pol, sink, loop(pol, backend))
        provider.handler = { request ->
            if (request.messages.none { it.role == "tool" }) {
                flow {
                    emit(
                        StreamEvent.ToolDone(
                            toolIndex = 0,
                            id = "dup-1",
                            name = "lsp.symbols",
                            argumentsJson = """{"query":"Foo"}""",
                        ),
                    )
                    // Same id at a new index: a malformed/hostile replay.
                    emit(
                        StreamEvent.ToolDone(
                            toolIndex = 1,
                            id = "dup-1",
                            name = "lsp.symbols",
                            argumentsJson = """{"query":"Foo"}""",
                        ),
                    )
                    emit(StreamEvent.Done("tool_calls"))
                }
            } else {
                flow {
                    emit(StreamEvent.TextDelta(0, 0, "found Foo:example"))
                    emit(StreamEvent.Done("stop"))
                }
            }
        }
        c.send("幫我查 Foo 這個符號")

        // Both ToolDones are recorded (records-only truth), but the executor
        // ran exactly once for the deduped id.
        assertEquals(listOf("dup-1", "dup-1"), sink.tools.map { it.id })
        assertEquals(1, backend.invocations.get())
        // Continuation pairing stays total and unique: ONE call + ONE result.
        val cont = provider.seenRequests[1]
        val assistant = cont.messages.first { it.role == "assistant" && it.toolCalls.isNotEmpty() }
        assertEquals(listOf("dup-1"), assistant.toolCalls.map { it.id })
        assertEquals(listOf("dup-1"), cont.messages.filter { it.role == "tool" }.map { it.toolCallId })
        assertEquals(ChatStatus.IDLE, c.uiState.value.status)
    }

    @Test fun blankToolDoneIdSynthesizesWireIdForPairing() = runBlocking {
        // A blank ToolDone id must not ride the wire verbatim: providers that
        // require non-empty tool-call ids would reject the whole continuation
        // round. Synthesize call_$index (same convention as
        // flushPendingTools' call_pending_$index); the transcript keeps the
        // raw "" for audit truth.
        val provider = SliceProvider()
        val sink = SliceSink()
        val backend = CountingBackend()
        val pol = slicePolicy()
        val c = controller(provider, pol, sink, loop(pol, backend))
        provider.handler = { request ->
            if (request.messages.none { it.role == "tool" }) {
                flow {
                    emit(
                        StreamEvent.ToolDone(
                            toolIndex = 3,
                            id = "",
                            name = "lsp.symbols",
                            argumentsJson = """{"query":"Foo"}""",
                        ),
                    )
                    emit(StreamEvent.Done("tool_calls"))
                }
            } else {
                flow {
                    emit(StreamEvent.TextDelta(0, 0, "found Foo:example"))
                    emit(StreamEvent.Done("stop"))
                }
            }
        }
        c.send("幫我查 Foo")

        val cont = provider.seenRequests[1]
        val assistant = cont.messages.first { it.role == "assistant" && it.toolCalls.isNotEmpty() }
        assertEquals("call_3", assistant.toolCalls.single().id)
        assertEquals("call_3", cont.messages.first { it.role == "tool" }.toolCallId)
        // Executor ran once with the synthesized id; the turn completes.
        assertEquals(1, backend.invocations.get())
        assertEquals(ChatStatus.IDLE, c.uiState.value.status)
        // Raw audit record keeps the VOID id.
        assertEquals("", sink.tools.single().id)
    }
}
