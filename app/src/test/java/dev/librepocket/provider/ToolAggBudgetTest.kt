package dev.librepocket.provider

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Aggregation-budget regressions (#16): every ToolDelta→ToolDone aggregator
 * shares one per-call memory budget ([MAX_TOOL_CALL_AGG_BYTES]); exceeding it
 * is a typed non-retryable failure ([TOOL_ARGS_TOO_LARGE]), distinct from
 * malformed payloads (ignored) and clean-EOF truncation (`SSE_TRUNCATED`,
 * retryable). Nothing is silently truncated.
 *
 * Finding #1 adds the per-attempt whole-round total
 * ([requireTotalToolAggBudget]) and the distinct-tool cap
 * ([MAX_TOOL_CALLS_PER_ATTEMPT]/[requireToolCountBudget]) with identical
 * failure semantics: many small calls, each under its own per-call budget,
 * still trip the shared 1 MiB total, and a distinct-index flood trips the
 * count cap.
 *
 * Boundary math: the char→byte estimate is ×3, so with a 1 MiB budget
 * 349525 chars (×3 = 1048575) still fit while one more char overflows.
 */
class ToolAggBudgetTest {

    private fun toolFailureOf(block: () -> Unit): ProviderFailure {
        try {
            block()
        } catch (e: ProviderFailure) {
            return e
        }
        fail("expected typed TOOL_ARGS_TOO_LARGE ProviderFailure")
        error("unreachable")
    }

    @Test
    fun requireToolAggBudgetExactBoundary() {
        requireToolAggBudget(349_525, 0)
        requireToolAggBudget(0, 0)
        val over = toolFailureOf { requireToolAggBudget(349_525, 1) }
        assertEquals(TOOL_ARGS_TOO_LARGE, over.message)
        assertFalse(over.retryable)
        assertEquals(ProviderFailureCode.AGG_TOO_LARGE, over.code)
    }

    @Test
    fun chatCompletionsSingleFragmentOverBudgetThrowsTyped() {
        val mapper = ChatCompletionsMapper()
        val frag = "y".repeat(400_000)
        val payload = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"f","arguments":"$frag"}}]}}]}"""
        val failure = toolFailureOf { mapper.mapPayload(payload) }
        assertEquals(TOOL_ARGS_TOO_LARGE, failure.message)
        assertFalse("aggregation overflow must not retry the poisoned stream", failure.retryable)
        assertEquals(ProviderFailureCode.AGG_TOO_LARGE, failure.code)
    }

    @Test
    fun chatCompletionsManySmallFragmentsTripAggregate() {
        val mapper = ChatCompletionsMapper()
        val frag = "y".repeat(4_000)
        var threw = false
        repeat(120) {
            val payload = """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"$frag"}}]}}]}"""
            try {
                mapper.mapPayload(payload)
            } catch (e: ProviderFailure) {
                assertEquals(TOOL_ARGS_TOO_LARGE, e.message)
                assertFalse(e.retryable)
                threw = true
                return@repeat
            }
        }
        assertTrue("120 x 4k-char fragments must exceed the aggregate budget", threw)
    }

    @Test
    fun chatCompletionsUnderBudgetAccumulatesUnchanged() {
        val mapper = ChatCompletionsMapper()
        val frag = "y".repeat(1_000)
        repeat(2) {
            val payload = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"f","arguments":"$frag"}}]}}]}"""
            mapper.mapPayload(payload)
        }
        val terminal = mapper.finish()
        val done = terminal.filterIsInstance<StreamEvent.ToolDone>().single()
        assertEquals(2_000, done.argumentsJson.length)
    }

    @Test
    fun responsesDeltaStreamOverBudgetThrowsTyped() {
        val mapper = ResponsesMapper()
        val frag = "x".repeat(4_000)
        var threw = false
        repeat(120) {
            try {
                mapper.mapPayload("""{"type":"response.function_call_arguments.delta","item_id":"it1","delta":"$frag"}""")
            } catch (e: ProviderFailure) {
                assertEquals(TOOL_ARGS_TOO_LARGE, e.message)
                assertFalse(e.retryable)
                assertEquals(ProviderFailureCode.AGG_TOO_LARGE, e.code)
                threw = true
                return@repeat
            }
        }
        assertTrue("120 x 4k-char fragments must exceed the aggregate budget", threw)
    }

    @Test
    fun responsesTerminalArgumentsOverBudgetThrowsTyped() {
        val mapper = ResponsesMapper()
        val big = "x".repeat(400_000)
        val failure = toolFailureOf {
            mapper.mapPayload(
                """{"type":"response.output_item.done","item_id":"it1","item":{"type":"function_call","call_id":"c1","name":"f","arguments":"$big"}}""",
            )
        }
        assertEquals(TOOL_ARGS_TOO_LARGE, failure.message)
        assertFalse(failure.retryable)
    }

    @Test
    fun anthropicInputJsonOverBudgetThrowsTyped() {
        val mapper = AnthropicMapper()
        mapper.mapPayload(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t1","name":"n"}}""",
        )
        val frag = "z".repeat(4_000)
        var threw = false
        repeat(120) {
            try {
                mapper.mapPayload(
                    """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"$frag"}}""",
                )
            } catch (e: ProviderFailure) {
                assertEquals(TOOL_ARGS_TOO_LARGE, e.message)
                assertFalse(e.retryable)
                assertEquals(ProviderFailureCode.AGG_TOO_LARGE, e.code)
                threw = true
                return@repeat
            }
        }
        assertTrue("120 x 4k-char fragments must exceed the aggregate budget", threw)
    }

    @Test
    fun overflowMarkersClassifyAsFatal() {
        assertEquals(
            FailureKind.FATAL,
            ProviderErrorClassifier.classify(null, null, TOOL_ARGS_TOO_LARGE),
        )
        assertEquals(
            FailureKind.FATAL,
            ProviderErrorClassifier.classify(null, null, SseFrameParser.FRAME_TOO_LARGE_MESSAGE),
        )
        assertEquals(
            FailureKind.RETRYABLE,
            ProviderErrorClassifier.classify(null, null, "SSE_TRUNCATED"),
        )
    }

    @Test
    fun stackedSmallLinesAbortStreamWithTypedFrameFailure() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            // 300 individually-legal (~4 KiB) data lines in ONE event: no line
            // trips the per-line cap, but the joined payload (~1.2 M chars,
            // ~3.6 M estimated bytes) must trip the per-event total.
            val line = "data: " + "q".repeat(4_096) + "\n"
            val body = buildString {
                repeat(300) { append(line) }
                append("\n")
            }
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(body),
            )
            val config = ProviderConfig(
                id = "agg-budget",
                label = "test",
                baseUrl = server.url("/").toString().trimEnd('/'),
                protocol = ProviderProtocol.CHAT_COMPLETIONS,
                apiKeyRef = "fake",
                http = ProviderHttpConfig(readTimeoutMs = TimeUnit.MINUTES.toMillis(5)),
            )
            val provider = ChatCompletionsProvider(config, { "fake-key".toCharArray() }, OkHttpClient())
            val events = provider.stream(
                ChatRequest(model = "m", messages = listOf(ChatMessage("user", "hi"))),
            ).toList()
            val failure = events.last() as StreamEvent.Failed
            assertTrue(
                "stacked event must fail typed, got: ${failure.message}",
                failure.message.contains(SseFrameParser.FRAME_TOO_LARGE_MESSAGE),
            )
            assertFalse("frame overflow must not retry the poisoned stream", failure.retryable)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    // ---- Finding #1: whole-round total + distinct-index cap ----

    private fun assertAggOverflow(e: ProviderFailure) {
        assertEquals(TOOL_ARGS_TOO_LARGE, e.message)
        assertFalse(e.retryable)
        assertEquals(ProviderFailureCode.AGG_TOO_LARGE, e.code)
    }

    @Test
    fun requireTotalToolAggBudgetSharesExactBoundary() {
        requireTotalToolAggBudget(349_525, 0)
        requireTotalToolAggBudget(0, 0)
        val over = toolFailureOf { requireTotalToolAggBudget(349_525, 1) }
        assertAggOverflow(over)
    }

    @Test
    fun requireToolCountBudgetBoundary() {
        requireToolCountBudget(1)
        requireToolCountBudget(MAX_TOOL_CALLS_PER_ATTEMPT)
        val over = toolFailureOf { requireToolCountBudget(MAX_TOOL_CALLS_PER_ATTEMPT + 1) }
        assertAggOverflow(over)
    }

    @Test
    fun chatCompletionsManyDistinctToolsTripCountCapTyped() {
        // The distinct-index count cap trips first at 65 (before any char
        // total); the char-total path is covered by TotalAcross below.
        val mapper = ChatCompletionsMapper()
        val frag = "y".repeat(4_000)
        var asserted = false
        for (i in 0 until 1_000) {
            val payload = """{"choices":[{"delta":{"tool_calls":[{"index":$i,"id":"c$i","function":{"name":"f$i","arguments":"$frag"}}]}}]}"""
            try {
                mapper.mapPayload(payload)
            } catch (e: ProviderFailure) {
                if (!asserted) {
                    assertAggOverflow(e)
                    asserted = true
                    break
                }
            }
        }
        assertTrue("1000 distinct 4k-char tools must trip the attempt budget", asserted)
    }

    @Test
    fun chatCompletionsDistinctCountCapBoundary() {
        val mapper = ChatCompletionsMapper()
        for (i in 0 until MAX_TOOL_CALLS_PER_ATTEMPT) {
            mapper.mapPayload(
                """{"choices":[{"delta":{"tool_calls":[{"index":$i,"function":{"arguments":"x"}}]}}]}""",
            )
        }
        // 65 one-char fragments total 65 chars: only the distinct-index cap can trip.
        val over = toolFailureOf {
            mapper.mapPayload(
                """{"choices":[{"delta":{"tool_calls":[{"index":$MAX_TOOL_CALLS_PER_ATTEMPT,"function":{"arguments":"x"}}]}}]}""",
            )
        }
        assertAggOverflow(over)
    }

    @Test
    fun chatCompletionsTotalAcrossToolsTripsTyped() {
        // 4 tools x 30 x 4k chars = 480k total (trips); per-tool 120k stays
        // under the per-call budget, so only the attempt total can trip.
        val mapper = ChatCompletionsMapper()
        val frag = "y".repeat(4_000)
        var asserted = false
        for (r in 0 until 30) {
            for (t in 0 until 4) {
                try {
                    mapper.mapPayload(
                        """{"choices":[{"delta":{"tool_calls":[{"index":$t,"function":{"arguments":"$frag"}}]}}]}""",
                    )
                } catch (e: ProviderFailure) {
                    if (!asserted) {
                        assertAggOverflow(e)
                        asserted = true
                        break
                    }
                }
            }
            if (asserted) break
        }
        assertTrue("4 x 30 x 4k-char fragments must exceed the round total", asserted)
    }

    @Test
    fun chatCompletionsFewToolsUnderTotalAccumulateUnchanged() {
        val mapper = ChatCompletionsMapper()
        val frag = "y".repeat(1_000)
        for (t in 0 until 3) {
            repeat(2) {
                mapper.mapPayload(
                    """{"choices":[{"delta":{"tool_calls":[{"index":$t,"id":"c$t","function":{"name":"f$t","arguments":"$frag"}}]}}]}""",
                )
            }
        }
        val dones = mapper.finish().filterIsInstance<StreamEvent.ToolDone>()
        assertEquals(3, dones.size)
        dones.forEach { assertEquals(2_000, it.argumentsJson.length) }
    }

    @Test
    fun responsesManyDistinctToolsTripCountCapTyped() {
        // The distinct-index count cap trips first at 65 (before any char
        // total); the char-total path is covered by TotalAcross below.
        val mapper = ResponsesMapper()
        val frag = "x".repeat(4_000)
        var asserted = false
        for (i in 0 until 1_000) {
            try {
                mapper.mapPayload(
                    """{"type":"response.function_call_arguments.delta","item_id":"it$i","delta":"$frag"}""",
                )
            } catch (e: ProviderFailure) {
                if (!asserted) {
                    assertAggOverflow(e)
                    asserted = true
                    break
                }
            }
        }
        assertTrue("1000 distinct 4k-char items must trip the attempt budget", asserted)
    }

    @Test
    fun responsesDistinctCountCapBoundary() {
        val mapper = ResponsesMapper()
        for (i in 0 until MAX_TOOL_CALLS_PER_ATTEMPT) {
            mapper.mapPayload(
                """{"type":"response.function_call_arguments.delta","item_id":"it$i","delta":"x"}""",
            )
        }
        // 65 one-char fragments total 65 chars: only the distinct-index cap can trip.
        val over = toolFailureOf {
            mapper.mapPayload(
                """{"type":"response.function_call_arguments.delta","item_id":"it$MAX_TOOL_CALLS_PER_ATTEMPT","delta":"x"}""",
            )
        }
        assertAggOverflow(over)
    }

    @Test
    fun responsesTotalAcrossToolsTripsTyped() {
        // 4 items x 30 x 4k chars = 480k total (trips); per-item 120k stays
        // under the per-call budget, so only the attempt total can trip.
        val mapper = ResponsesMapper()
        val frag = "x".repeat(4_000)
        var asserted = false
        for (r in 0 until 30) {
            for (t in 0 until 4) {
                try {
                    mapper.mapPayload(
                        """{"type":"response.function_call_arguments.delta","item_id":"it$t","delta":"$frag"}""",
                    )
                } catch (e: ProviderFailure) {
                    if (!asserted) {
                        assertAggOverflow(e)
                        asserted = true
                        break
                    }
                }
            }
            if (asserted) break
        }
        assertTrue("4 x 30 x 4k-char fragments must exceed the round total", asserted)
    }

    @Test
    fun responsesFewToolsUnderTotalAccumulateUnchanged() {
        val mapper = ResponsesMapper()
        val frag = "x".repeat(1_000)
        for (t in 0 until 3) {
            repeat(2) {
                mapper.mapPayload(
                    """{"type":"response.function_call_arguments.delta","item_id":"it$t","delta":"$frag"}""",
                )
            }
        }
        val dones = mapper.finish().filterIsInstance<StreamEvent.ToolDone>()
        assertEquals(3, dones.size)
        dones.forEach { assertEquals(2_000, it.argumentsJson.length) }
    }

    @Test
    fun anthropicManyDistinctToolsTripCountCapTyped() {
        // The distinct-index count cap trips first at 65 (before any char
        // total); the char-total path is covered by TotalAcross below.
        val mapper = AnthropicMapper()
        val frag = "z".repeat(4_000)
        var asserted = false
        for (i in 0 until 1_000) {
            try {
                mapper.mapPayload(
                    """{"type":"content_block_start","index":$i,"content_block":{"type":"tool_use","id":"t$i","name":"n"}}""",
                )
                mapper.mapPayload(
                    """{"type":"content_block_delta","index":$i,"delta":{"type":"input_json_delta","partial_json":"$frag"}}""",
                )
            } catch (e: ProviderFailure) {
                if (!asserted) {
                    assertAggOverflow(e)
                    asserted = true
                    break
                }
            }
        }
        assertTrue("1000 distinct 4k-char tool blocks must trip the attempt budget", asserted)
    }

    @Test
    fun anthropicDistinctCountCapBoundary() {
        val mapper = AnthropicMapper()
        for (i in 0 until MAX_TOOL_CALLS_PER_ATTEMPT) {
            mapper.mapPayload(
                """{"type":"content_block_start","index":$i,"content_block":{"type":"tool_use","id":"t$i","name":"n"}}""",
            )
        }
        // 64 tiny tool blocks total far below any char budget: only the count cap can trip.
        val over = toolFailureOf {
            mapper.mapPayload(
                """{"type":"content_block_start","index":$MAX_TOOL_CALLS_PER_ATTEMPT,"content_block":{"type":"tool_use","id":"tN","name":"n"}}""",
            )
        }
        assertAggOverflow(over)
    }

    @Test
    fun anthropicTotalAcrossToolsTripsTyped() {
        // 4 blocks x 30 x 4k chars = 480k total (trips); per-block 120k stays
        // under the per-call budget, so only the attempt total can trip.
        val mapper = AnthropicMapper()
        for (t in 0 until 4) {
            mapper.mapPayload(
                """{"type":"content_block_start","index":$t,"content_block":{"type":"tool_use","id":"t$t","name":"n"}}""",
            )
        }
        val frag = "z".repeat(4_000)
        var asserted = false
        for (r in 0 until 30) {
            for (t in 0 until 4) {
                try {
                    mapper.mapPayload(
                        """{"type":"content_block_delta","index":$t,"delta":{"type":"input_json_delta","partial_json":"$frag"}}""",
                    )
                } catch (e: ProviderFailure) {
                    if (!asserted) {
                        assertAggOverflow(e)
                        asserted = true
                        break
                    }
                }
            }
            if (asserted) break
        }
        assertTrue("4 x 30 x 4k-char fragments must exceed the round total", asserted)
    }

    @Test
    fun anthropicFewToolsUnderTotalAccumulateUnchanged() {
        val mapper = AnthropicMapper()
        val frag = "z".repeat(1_000)
        for (t in 0 until 3) {
            mapper.mapPayload(
                """{"type":"content_block_start","index":$t,"content_block":{"type":"tool_use","id":"t$t","name":"n$t"}}""",
            )
        }
        repeat(2) {
            for (t in 0 until 3) {
                mapper.mapPayload(
                    """{"type":"content_block_delta","index":$t,"delta":{"type":"input_json_delta","partial_json":"$frag"}}""",
                )
            }
        }
        val dones = ArrayList<StreamEvent.ToolDone>()
        for (t in 0 until 3) {
            mapper.mapPayload("""{"type":"content_block_stop","index":$t}""")
                .filterIsInstanceTo(dones)
        }
        assertEquals(3, dones.size)
        dones.forEach { assertEquals(2_000, it.argumentsJson.length) }
    }

    // ---- Stage C Finding #4/#5: Responses overwrite guard + first-wins ----

    @Test
    fun responsesDoneReviewCounterexampleThrowsWithoutEmit() {
        // Review counterexample: 200k id + 120k args + 60k name = 380k chars
        // (x3 = 1.14 MiB) must fail as one terminal candidate, with no emit
        // and no agg mutation.
        val mapper = ResponsesMapper()
        val bigId = "i".repeat(200_000)
        val bigName = "n".repeat(60_000)
        val bigArgs = "a".repeat(120_000)
        val failure = toolFailureOf {
            mapper.mapPayload(
                """{"type":"response.output_item.done","item_id":"it1","item":{"type":"function_call","call_id":"$bigId","name":"$bigName","arguments":"$bigArgs"}}""",
            )
        }
        assertAggOverflow(failure)
    }

    @Test
    fun responsesDoneOverwriteLegalEmitsNewValues() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_item.added","item_id":"it1","item":{"type":"function_call","call_id":"old-id","name":"old-name"}}""",
        )
        val out = mapper.mapPayload(
            """{"type":"response.output_item.done","item_id":"it1","item":{"type":"function_call","call_id":"new-id","name":"new-name","arguments":"{}"}}""",
        )
        val done = out.filterIsInstance<StreamEvent.ToolDone>().single()
        assertEquals("new-id", done.id)
        assertEquals("new-name", done.name)
        assertEquals("{}", done.argumentsJson)
    }

    @Test
    fun responsesDoneIgnoresFullWhenArgsPresentNoDoubleCount() {
        // Deltas already hold 1k args; a hostile 400k terminal full must be
        // ignored (not appended, not budgeted) when args are non-empty.
        val mapper = ResponsesMapper()
        mapper.mapPayload("""{"type":"response.function_call_arguments.delta","item_id":"it1","delta":"${"x".repeat(1_000)}"}""")
        val big = "y".repeat(400_000)
        val out = mapper.mapPayload(
            """{"type":"response.output_item.done","item_id":"it1","item":{"type":"function_call","call_id":"c1","name":"f","arguments":"$big"}}""",
        )
        val done = out.filterIsInstance<StreamEvent.ToolDone>().single()
        assertEquals(1_000, done.argumentsJson.length)
        assertTrue(done.argumentsJson.all { it == 'x' })
    }

    @Test
    fun responsesFailedDoneLeavesNoPoisonForFinish() {
        // Legal delta first; oversized terminal throws without mutating agg;
        // a later finishPendingTools path (via response.completed) must emit
        // only the prior legal state, never the poisoned payload.
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_item.added","item_id":"it1","item":{"type":"function_call","call_id":"c1","name":"f"}}""",
        )
        mapper.mapPayload("""{"type":"response.function_call_arguments.delta","item_id":"it1","delta":"{}"}""")
        val bigId = "i".repeat(200_000)
        // agg.args already "{}" so terminal arguments are ignored (useFull=false);
        // overflow must come from metadata alone: 200k+150k+2=350002 chars > 349525.
        val bigName = "n".repeat(150_000)
        val bigArgs = "a".repeat(120_000)
        try {
            mapper.mapPayload(
                """{"type":"response.output_item.done","item_id":"it1","item":{"type":"function_call","call_id":"$bigId","name":"$bigName","arguments":"$bigArgs"}}""",
            )
            fail("expected typed TOOL_ARGS_TOO_LARGE ProviderFailure")
        } catch (e: ProviderFailure) {
            assertAggOverflow(e)
        }
        // Poison never entered agg: completing the stream recovers the legal
        // pending tool, not the oversized terminal.
        val tail = mapper.mapPayload("""{"type":"response.completed","response":{"output":[]}}""")
        val done = tail.filterIsInstance<StreamEvent.ToolDone>().single()
        assertEquals("c1", done.id)
        assertEquals("f", done.name)
        assertEquals("{}", done.argumentsJson)
    }

    @Test
    fun responsesAddedResendMetadataStaysSingleCopy() {
        // N=10000 resends of the same id/name must not throw and must not
        // grow the aggregate (first-wins: incoming 0 once buffered).
        val mapper = ResponsesMapper()
        repeat(10_000) {
            mapper.mapPayload(
                """{"type":"response.output_item.added","item_id":"it1","item":{"type":"function_call","call_id":"c1","name":"f"}}""",
            )
        }
        mapper.mapPayload("""{"type":"response.function_call_arguments.delta","item_id":"it1","delta":"{}"}""")
        val out = mapper.mapPayload(
            """{"type":"response.output_item.done","item_id":"it1","item":{"type":"function_call","call_id":"c1","name":"f"}}""",
        )
        val done = out.filterIsInstance<StreamEvent.ToolDone>().single()
        assertEquals("c1", done.id)
        assertEquals("f", done.name)
        assertEquals("{}", done.argumentsJson)
    }
}
