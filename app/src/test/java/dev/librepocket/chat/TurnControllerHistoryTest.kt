package dev.librepocket.chat

import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatMessage
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol as ProviderProto
import dev.librepocket.provider.StreamEvent
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class HistoryFakeProvider(
    var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
    override val protocol: ProviderProto = ProviderProto.CHAT_COMPLETIONS
    val seenRequests: MutableList<ChatRequest> = Collections.synchronizedList(mutableListOf())
    val streamCalls = AtomicInteger(0)

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        streamCalls.incrementAndGet()
        seenRequests.add(request)
        emitAll(handler(request))
    }

    override suspend fun listModels(): List<String> = emptyList()
}

private class HistoryAllowPolicy : PolicyStore {
    override fun evaluate(action: String, resource: String) =
        PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
    override suspend fun setRule(rule: PolicyRule) = Unit
    override suspend fun removeRule(pattern: String) = Unit
    override suspend fun listRules(): List<PolicyRule> = emptyList()
    override suspend fun evaluateFresh(action: String, resource: String) =
        PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

private class PartialCapturingSink : TranscriptSink {
    data class Failed(val runId: String, val partialText: String, val error: String)
    val failedThreeArg = Collections.synchronizedList(mutableListOf<Failed>())

    override suspend fun onTurnStarted(runId: String, text: String) = Unit
    override suspend fun onTurnSucceeded(runId: String, text: String) = Unit
    override suspend fun onTurnFailed(runId: String, error: String) = Unit
    override suspend fun onTurnFailed(runId: String, partialText: String, error: String) {
        failedThreeArg.add(Failed(runId, partialText, error))
    }
    override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
    override suspend fun onTurnCancelled(runId: String, partialText: String) = Unit
    override suspend fun onSteerQueued(text: String) = Unit
    override suspend fun onToolDone(
        runId: String,
        toolIndex: Int,
        id: String,
        name: String,
        argumentsJson: String,
    ) = Unit
    override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) = Unit
}

/**
 * Phase 3 hydration regressions (pure JVM, all fake data).
 *
 * - F8: the first request after resume carries the restored prefix.
 * - [HistoryWindowCap] truncates oldest-first at whole-message boundaries
 *   (tool markers ride inside their assistant block, so pairs stay intact)
 *   and every truncation is observable via [TurnController.droppedHistoryCount].
 * - The terminal failure path forwards the live partial fragment plus the
 *   sanitized reason through the three-arg sink form.
 */
class TurnControllerHistoryTest {

    private fun doneOnce(text: String = "ok"): (ChatRequest) -> Flow<StreamEvent> = {
        flow {
            emit(StreamEvent.TextDelta(0, 0, text))
            emit(StreamEvent.Done("stop"))
        }
    }

    private fun historyOf(vararg pairs: Pair<String, String>): List<ChatMessage> =
        pairs.map { (role, text) -> ChatMessage(role = role, text = text) }

    @Test fun resumeFirstRequestCarriesRestoredHistory() {
        val provider = HistoryFakeProvider(doneOnce())
        var n = 0
        val c = TurnController(
            provider = provider,
            policy = HistoryAllowPolicy(),
            dispatcher = Dispatchers.Default,
            sleeper = {},
            newId = { "hist-${n++}" },
            initialHistory = historyOf("user" to "q1", "assistant" to "a1"),
        )
        try {
            runBlocking { c.send("q2") }
            val req = provider.seenRequests.single()
            assertEquals(listOf("user", "assistant", "user"), req.messages.map { it.role })
            assertEquals("q1", req.messages[0].text)
            assertEquals("a1", req.messages[1].text)
            // The live message keeps its text; only the ephemeral time block is suffixed.
            assertTrue(req.messages[2].text.startsWith("q2"))
            assertEquals(0, c.droppedHistoryCount)
        } finally {
            c.close()
        }
    }

    @Test fun emptyHistoryKeepsLegacyRequestShape() {
        val provider = HistoryFakeProvider(doneOnce())
        var n = 0
        val c = TurnController(
            provider = provider,
            policy = HistoryAllowPolicy(),
            dispatcher = Dispatchers.Default,
            sleeper = {},
            newId = { "legacy-${n++}" },
        )
        try {
            runBlocking { c.send("hello") }
            val req = provider.seenRequests.single()
            assertEquals(1, req.messages.size)
            assertEquals("user", req.messages.single().role)
            assertTrue(req.messages.single().text.startsWith("hello"))
            assertEquals(0, c.droppedHistoryCount)
        } finally {
            c.close()
        }
    }

    @Test fun maxMessagesCapTruncatesOldestFirstAndReports() {
        val provider = HistoryFakeProvider(doneOnce())
        var n = 0
        val c = TurnController(
            provider = provider,
            policy = HistoryAllowPolicy(),
            dispatcher = Dispatchers.Default,
            sleeper = {},
            newId = { "cap-${n++}" },
            initialHistory = historyOf(
                "user" to "drop-q1",
                "assistant" to "drop-a1",
                "user" to "keep-q2",
                "assistant" to "keep-a2",
            ),
            historyCap = HistoryWindowCap.MaxMessages(2),
        )
        try {
            runBlocking { c.send("q3") }
            val req = provider.seenRequests.single()
            assertEquals(
                listOf("user", "assistant", "user"),
                req.messages.map { it.role },
            )
            assertEquals("keep-q2", req.messages[0].text)
            assertEquals("keep-a2", req.messages[1].text)
            assertEquals(2, c.droppedHistoryCount)
        } finally {
            c.close()
        }
    }

    @Test fun maxCharsCapKeepsWholeMessagesSoToolPairsStayIntact() {
        val toolBlock = "answer\n[tool:lookup {\"q\":\"hi\"}]"
        val kept = applyHistoryCap(
            historyOf(
                "user" to "drop me, way too long for the budget",
                "assistant" to toolBlock,
                "user" to "tail",
            ),
            HistoryWindowCap.MaxChars(20),
        )
        // "tail"(4) + toolBlock(30) exceeds 20, so only "tail" survives — but
        // the tool-carrying assistant block is never split, only dropped whole.
        assertEquals(listOf("tail"), kept.map { it.text })
        for (m in kept) {
            assertTrue("no half tool marker may survive", !m.text.contains("[tool:") || m.text == toolBlock)
        }

        val fitting = applyHistoryCap(
            historyOf("user" to "qq", "assistant" to toolBlock),
            HistoryWindowCap.MaxChars(10_000),
        )
        assertEquals(listOf("qq", toolBlock), fitting.map { it.text })
    }

    @Test fun unboundedDefaultHydratesEverything() {
        val history = historyOf("user" to "q1", "assistant" to "a1", "user" to "q2")
        assertEquals(history, applyHistoryCap(history, HistoryWindowCap.Unbounded))
        var n = 0
        val c = TurnController(
            provider = HistoryFakeProvider(doneOnce()),
            policy = HistoryAllowPolicy(),
            dispatcher = Dispatchers.Default,
            sleeper = {},
            newId = { "unb-${n++}" },
            initialHistory = history,
        )
        try {
            assertEquals(0, c.droppedHistoryCount)
        } finally {
            c.close()
        }
    }

    @Test fun terminalFailureForwardsPartialFragmentAndReason() {
        val provider = HistoryFakeProvider {
            flow {
                emit(StreamEvent.TextDelta(0, 0, "half"))
                emit(StreamEvent.Failed("HTTP 500 boom", retryable = false))
            }
        }
        val sink = PartialCapturingSink()
        var n = 0
        val c = TurnController(
            provider = provider,
            policy = HistoryAllowPolicy(),
            transcript = sink,
            dispatcher = Dispatchers.Default,
            sleeper = {},
            newId = { "fail-${n++}" },
        )
        try {
            runBlocking { c.send("hi") }
            val failure = sink.failedThreeArg.single()
            assertEquals("half", failure.partialText)
            assertTrue("reason must be sanitized, got: ${failure.error}", failure.error.contains("500"))
            // Memory keeps the failed fragment flagged, matching the ledger row.
            val assistants = c.uiState.value.messages.filter { it.role == "assistant" }
            assertEquals(1, assistants.size)
            assertTrue(assistants.single().isPartial)
            assertEquals("half", assistants.single().text)
        } finally {
            c.close()
        }
    }
}
