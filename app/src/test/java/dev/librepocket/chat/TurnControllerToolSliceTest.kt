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
        ) = Unit
        override suspend fun onTurnFailed(
            runId: String,
            partialText: String,
            error: String,
            parentRunId: String?,
            attemptIndex: Int?,
            isFinal: Boolean,
            hasReasoning: Boolean,
        ) = Unit
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
    ) = SafeToolSlice(
        ctxSupplier = { ProjectionContext(userSwitches = switches) },
        policy = policy,
        backend = backend,
        clock = fixedClock,
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
}
