package dev.librepocket.chat

import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol as ProviderProto
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

    @Test fun continuationAccountingIncludesToolCallsPayloadAndTrims() = runBlocking {
        // Request-level budget regression: the continuation accounting must
        // count the structured assistant toolCalls payload (the args echo
        // alone can reach MAX_INPUT_CHARS), not only ChatMessage.text. The
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
        // Serialized accounting (text + structured toolCalls) over the
        // outgoing continuation request stays within the request cap; a
        // text-only accounting would have skipped the trim entirely.
        val payload = cont.messages
            .sumOf { m -> m.text.length + m.toolCalls.sumOf { it.id.length + it.name.length + it.argumentsJson.length } }
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
        // The inline `[tool:...]` marker embeds model-controlled call fields;
        // before rendering, the name is filtered to letter/digit/._- and the
        // args get `[`/`]` mapped to full-width plus CR/LF folded to spaces,
        // so no forged nested marker can ride inside one call's echo
        // (8faf631). A non-slice tool name is records-only: never executed,
        // never paired, no continuation round.
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
                        argumentsJson = """{"find":"a]b
c"}""",
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
