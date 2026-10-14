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

    // ---- Stage 1 (F2): emittedToolIds dedup-set bound (same distinct cap) ----

    @Test
    fun responsesDoneRotatingCallIdTripsEmittedIdCapTyped() {
        // Hostile: one fixed item_id re-done with a rotating equal-length
        // call_id. Per-candidate round-total growth stays 0 (equal lengths) and
        // item_id is fixed, so requireToolAggBudget / requireTotalToolAggBudget
        // / the callsByItem distinct count all stay silent. The only unbounded
        // surface is emittedToolIds (and the emitted ToolDone count), which the
        // same distinct per-attempt cap must now bound: the 65th distinct
        // (toolIndex, id) throws typed, non-retryable.
        val mapper = ResponsesMapper()
        val over = toolFailureOf {
            for (i in 0 until MAX_TOOL_CALLS_PER_ATTEMPT + 1) {
                val callId = String.format("c%03d", i) // 4 chars throughout
                mapper.mapPayload(
                    """{"type":"response.output_item.done","item_id":"it1","item":{"type":"function_call","call_id":"$callId","name":"f"}}""",
                )
            }
        }
        assertAggOverflow(over)
    }

    @Test
    fun responsesDoneDistinctIdsAtCapAllEmit() {
        // Up to MAX_TOOL_CALLS_PER_ATTEMPT distinct (toolIndex, id) ToolDones
        // are legal: 64 equal-length rotating re-dones all emit, none throw,
        // and the round total never grows beyond one candidate (growth 0), so
        // the emitted-id cap fires only on the 65th — not before it.
        val mapper = ResponsesMapper()
        var emitted = 0
        for (i in 0 until MAX_TOOL_CALLS_PER_ATTEMPT) {
            val callId = String.format("c%03d", i)
            val out = mapper.mapPayload(
                """{"type":"response.output_item.done","item_id":"it1","item":{"type":"function_call","call_id":"$callId","name":"f"}}""",
            )
            emitted += out.filterIsInstance<StreamEvent.ToolDone>().size
        }
        assertEquals(MAX_TOOL_CALLS_PER_ATTEMPT, emitted)
    }

    @Test
    fun responsesDoneResendAcceptedIdAtCapIsNoOp() {
        // At-cap re-send of an already-admitted (toolIndex, id) must stay a
        // dedup no-op: the guard charges only genuinely-new pairs, so a legal
        // duplicate after the cap is never misread as an overflow.
        val mapper = ResponsesMapper()
        for (i in 0 until MAX_TOOL_CALLS_PER_ATTEMPT) {
            mapper.mapPayload(
                """{"type":"response.output_item.done","item_id":"it1","item":{"type":"function_call","call_id":"${String.format("c%03d", i)}","name":"f"}}""",
            )
        }
        val out = mapper.mapPayload(
            """{"type":"response.output_item.done","item_id":"it1","item":{"type":"function_call","call_id":"c000","name":"f"}}""",
        )
        assertEquals(1, out.filterIsInstance<StreamEvent.ToolDone>().size)
    }

    // ---- Stage 1 (F4): Anthropic TOOL->TOOL restart re-bills id/name ----

    @Test
    fun anthropicToolRestartCyclingEqualLenIdStaysBounded() {
        // TOOL→TOOL re-start at one index must retire the outgoing id/name
        // before re-billing the incoming pair. A hostile cycle of equal-length
        // id restarts (only one block is buffered, then replaced) previously
        // re-added the id/name length every time, inflating totalAggChars by
        // ~10k chars per restart and tripping a spurious TOOL_ARGS_TOO_LARGE
        // well before any real overflow. Now the total tracks the real
        // one-block sum, so 100 restarts never throw and the block still
        // closes cleanly.
        val mapper = AnthropicMapper()
        for (i in 0 until 100) {
            val id = "x".repeat(9_988) + String.format("%012d", i) // 10_000 chars, rotating tail
            val start = mapper.mapPayload(
                """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"$id","name":"n"}}""",
            )
            assertEquals(1, start.filterIsInstance<StreamEvent.ToolDelta>().size)
        }
        val done = mapper.mapPayload("""{"type":"content_block_stop","index":0}""")
            .filterIsInstance<StreamEvent.ToolDone>().single()
        assertEquals(10_000, done.id.length)
        assertEquals("n", done.name)
    }

    @Test
    fun anthropicSingleOversizedToolIdNameStillThrowsTyped() {
        // Negative control: the re-bill fix must not weaken the single-shot
        // oversized guard. One start whose id+name alone exceed the round total
        // (300k+60k=360k chars, x3 = 1.08 MiB) still fails closed.
        val mapper = AnthropicMapper()
        val bigId = "i".repeat(300_000)
        val bigName = "n".repeat(60_000)
        val over = toolFailureOf {
            mapper.mapPayload(
                """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"$bigId","name":"$bigName"}}""",
            )
        }
        assertAggOverflow(over)
    }

    // ---- PR #82 follow-up: the blocks-map cap is one distinct-admission
    //      budget, not a TOOL-only tally ----

    @Test
    fun anthropicThinkingBlockFloodTripsDistinctCapTyped() {
        // Non-TOOL kinds are charged by the same pre-insert distinct budget:
        // 64 thinking blocks are legal, the 65th distinct index throws.
        val mapper = AnthropicMapper()
        for (i in 0 until MAX_TOOL_CALLS_PER_ATTEMPT) {
            val out = mapper.mapPayload(
                """{"type":"content_block_start","index":$i,"content_block":{"type":"thinking","thinking":""}}""",
            )
            assertEquals(
                "a thinking block start projects the presence marker only",
                1,
                out.filterIsInstance<StreamEvent.ReasoningPresent>().size,
            )
        }
        val over = toolFailureOf {
            mapper.mapPayload(
                """{"type":"content_block_start","index":$MAX_TOOL_CALLS_PER_ATTEMPT,"content_block":{"type":"thinking","thinking":""}}""",
            )
        }
        assertAggOverflow(over)
    }

    @Test
    fun anthropicMixedKindBlocksShareOneDistinctAdmissionBudget() {
        // text / thinking / redacted_thinking / tool_use / an unknown opaque
        // type all draw on ONE distinct-index budget. The overflowing index
        // arrives as an opaque kind, so only the pre-insert check (shared by
        // every kind) can explain the failure — a TOOL-only tally would stay
        // silent on all four non-tool kinds.
        val kinds = listOf("text", "thinking", "redacted_thinking", "tool_use", "server_tool_use")
        val mapper = AnthropicMapper()
        for (i in 0 until MAX_TOOL_CALLS_PER_ATTEMPT) {
            val kind = kinds[i % kinds.size]
            mapper.mapPayload(
                """{"type":"content_block_start","index":$i,"content_block":{"type":"$kind","id":"t$i","name":"n"}}""",
            )
        }
        val over = toolFailureOf {
            mapper.mapPayload(
                """{"type":"content_block_start","index":$MAX_TOOL_CALLS_PER_ATTEMPT,"content_block":{"type":"server_tool_use","id":"x","name":"y"}}""",
            )
        }
        assertAggOverflow(over)
    }

    @Test
    fun anthropicTextBlockRestartAtSameIndexIsNotANewAdmission() {
        // Replacement, not admission: many restarts of ONE index must never
        // charge the distinct budget, and the surviving entry must still
        // project deltas (a restart replaces the buffered block, never evicts
        // it), so the remaining 63 admissions stay available.
        val mapper = AnthropicMapper()
        repeat(70) {
            val out = mapper.mapPayload(
                """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            )
            assertTrue("a TEXT block start projects no event", out.isEmpty())
        }
        assertEquals(
            1,
            mapper.mapPayload(
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"after-restart"}}""",
            ).filterIsInstance<StreamEvent.TextDelta>().size,
        )
        for (i in 1 until MAX_TOOL_CALLS_PER_ATTEMPT) {
            mapper.mapPayload(
                """{"type":"content_block_start","index":$i,"content_block":{"type":"text","text":""}}""",
            )
        }
        // Self-contained negative control: the budget really is at its limit
        // (index 0 was never evicted), so one more distinct index still throws.
        val over = toolFailureOf {
            mapper.mapPayload(
                """{"type":"content_block_start","index":$MAX_TOOL_CALLS_PER_ATTEMPT,"content_block":{"type":"text","text":""}}""",
            )
        }
        assertAggOverflow(over)
        // No message_delta stop_reason ever arrived and no block was closed,
        // so the honest terminal is the retryable truncation, never a success
        // Done.
        assertEquals(
            StreamEvent.Failed("SSE_TRUNCATED", retryable = true),
            mapper.mapPayload("""{"type":"message_stop"}""").single(),
        )
    }

    @Test
    fun anthropicToolToTextDemotionReleasesOneToolAdmission() {
        // A TOOL index restarted as TEXT demotes the block: the TOOL-only tally
        // decrements (reversible) while the blocks map keeps its entry
        // (replacement, not eviction). Both halves are observable — a delta at
        // the demoted index still projects, and re-promoting that index to
        // TOOL re-charges the tally (which must see 63 + 1, not a stale 64 + 1).
        val mapper = AnthropicMapper()
        for (i in 0 until MAX_TOOL_CALLS_PER_ATTEMPT) {
            mapper.mapPayload(
                """{"type":"content_block_start","index":$i,"content_block":{"type":"tool_use","id":"t$i","name":"n"}}""",
            )
        }
        val demoted = mapper.mapPayload(
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        )
        assertTrue("a demoted TEXT block projects no event", demoted.isEmpty())
        assertEquals(
            1,
            mapper.mapPayload(
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"demoted"}}""",
            ).filterIsInstance<StreamEvent.TextDelta>().size,
        )
        val promoted = mapper.mapPayload(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t0b","name":"n"}}""",
        )
        assertEquals(1, promoted.filterIsInstance<StreamEvent.ToolDelta>().size)
        // Char-total half of the SAME retirement: demotion must also retire the
        // outgoing TOOL block's id/name, so re-promoting the index with an
        // equally large id re-bills one block instead of two (removing the
        // demotion-side retirement makes this throw a spurious
        // TOOL_ARGS_TOO_LARGE at 2 x 349_000 chars).
        val mapper2 = AnthropicMapper()
        val bigId = "i".repeat(349_000)
        mapper2.mapPayload(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"$bigId","name":"n"}}""",
        )
        mapper2.mapPayload(
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        )
        val rePromoted = mapper2.mapPayload(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"$bigId","name":"n"}}""",
        )
        assertEquals(1, rePromoted.filterIsInstance<StreamEvent.ToolDelta>().size)
        // Negative control: the shared cap stays live. A genuinely new index
        // after 64 admissions is still refused, so nothing above disabled it.
        val over = toolFailureOf {
            mapper.mapPayload(
                """{"type":"content_block_start","index":$MAX_TOOL_CALLS_PER_ATTEMPT,"content_block":{"type":"tool_use","id":"tX","name":"n"}}""",
            )
        }
        assertAggOverflow(over)
    }

    @Test
    fun anthropicBlockCapOverflowLeavesNoBufferedOverflowEntry() {
        // Negative control for the post-overflow mapper state: AnthropicMapper
        // carries no failed latch, so the SAME mapper keeps projecting after a
        // budget throw. The overflowing entry must never have been buffered
        // (checked before insert), so its stop projects nothing, and the
        // half-closed attempt degrades to the retryable truncation it always
        // produced — never a resurrected Done and never a poisoned ToolDone.
        val mapper = AnthropicMapper()
        for (i in 0 until MAX_TOOL_CALLS_PER_ATTEMPT) {
            mapper.mapPayload(
                """{"type":"content_block_start","index":$i,"content_block":{"type":"tool_use","id":"t$i","name":"n"}}""",
            )
        }
        toolFailureOf {
            mapper.mapPayload(
                """{"type":"content_block_start","index":$MAX_TOOL_CALLS_PER_ATTEMPT,"content_block":{"type":"tool_use","id":"tX","name":"n"}}""",
            )
        }
        val stopOverflow = mapper.mapPayload(
            """{"type":"content_block_stop","index":$MAX_TOOL_CALLS_PER_ATTEMPT}""",
        )
        assertTrue("the never-buffered overflow entry projects nothing on stop", stopOverflow.isEmpty())
        val delta = mapper.mapPayload(
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":7}}""",
        )
        assertEquals(1, delta.filterIsInstance<StreamEvent.Usage>().size)
        val stop = mapper.mapPayload("""{"type":"message_stop"}""")
        assertEquals(
            StreamEvent.Failed("SSE_TRUNCATED", retryable = true),
            stop.filterIsInstance<StreamEvent.Failed>().single(),
        )
        assertTrue(
            "no Done is manufactured after an overflow",
            (stop + delta).none { it is StreamEvent.Done },
        )
        assertTrue(
            "no ToolDone is manufactured after an overflow",
            (stop + delta).none { it is StreamEvent.ToolDone },
        )
    }

    @Test
    fun anthropicSixtyFourTextBlocksCloseIntoDone() {
        // Positive control for the shared distinct budget: the cap must not
        // kill a legal fan-out. 64 text blocks, all closed, with a stop_reason,
        // project exactly one Done.
        val mapper = AnthropicMapper()
        for (i in 0 until MAX_TOOL_CALLS_PER_ATTEMPT) {
            mapper.mapPayload(
                """{"type":"content_block_start","index":$i,"content_block":{"type":"text","text":"b$i"}}""",
            )
        }
        for (i in 0 until MAX_TOOL_CALLS_PER_ATTEMPT) {
            assertTrue(
                mapper.mapPayload("""{"type":"content_block_stop","index":$i}""").isEmpty(),
            )
        }
        mapper.mapPayload("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")
        assertEquals(
            listOf(StreamEvent.Done("end_turn")),
            mapper.mapPayload("""{"type":"message_stop"}"""),
        )
    }

    // ---- S1 (P1 blocking): Responses String-key heap amplification ----
    //
    // First three payload tests are RED-phase: they use only literal lengths
    // (no new API symbols) so they compile before the fix and must FAIL
    // (no throw, silent multi-MB retention) until the per-key guard lands.
    // The last two are post-fix boundary controls using the new
    // requireToolIdBudget / MAX_TOOL_ID_CHARS symbols (compile only after).

    @Test
    fun responsesGiantItemIdFailsClosedBeforeInsert() {
        // Hostile: one 300KB item_id key with an empty delta. Every char
        // budget stays silent (total only counts agg id/name/args, here
        // "c1"+"f" = 3 chars) and the count cap sees a single admission,
        // so the pre-fix mapper retains ~300KB with zero failure.
        val mapper = ResponsesMapper()
        val giant = "k".repeat(300_000)
        val failure = toolFailureOf {
            mapper.mapPayload(
                """{"type":"response.output_item.added","item_id":"$giant","item":{"type":"function_call","call_id":"c1","name":"f"}}""",
            )
        }
        assertAggOverflow(failure)
        // Giant key never retained: the drain emits nothing tool-shaped and
        // the honest terminal is the retryable truncation, never a Done.
        val tail = mapper.finish()
        assertTrue("giant key must leave no buffered ToolDone", tail.filterIsInstance<StreamEvent.ToolDone>().isEmpty())
        assertEquals(
            StreamEvent.Failed("SSE_TRUNCATED", retryable = true),
            tail.single(),
        )
    }

    @Test
    fun responsesRotatingGiantCallIdFailsOnFirstDone() {
        // Hostile rotation: a fixed short item_id re-done with two different
        // same-length 10KB call_ids. Pre-fix, the second arrival would have
        // per-candidate growth 0 (equal lengths, had the first been buffered)
        // so char/total/count guards stay silent while emittedToolIds retains
        // 10KB per pair — the first step toward 64 x 300KB ~= 19.2M retained.
        // The per-key guard must fire on EACH giant id, including the second
        // with (hypothetical) growth 0, long before the 65th-entry count cap.
        val mapper = ResponsesMapper()
        val giantA = "c".repeat(9_999) + "A"
        val giantB = "c".repeat(9_999) + "B"
        assertEquals(10_000, giantA.length)
        assertEquals(giantA.length, giantB.length)
        val first = toolFailureOf {
            mapper.mapPayload(
                """{"type":"response.output_item.done","item_id":"it1","item":{"type":"function_call","call_id":"$giantA","name":"f"}}""",
            )
        }
        assertAggOverflow(first)
        // Second same-length rotation still fails closed via the per-key cap
        // even though (had the first been retained) growth would be 0.
        val second = toolFailureOf {
            mapper.mapPayload(
                """{"type":"response.output_item.done","item_id":"it1","item":{"type":"function_call","call_id":"$giantB","name":"f"}}""",
            )
        }
        assertAggOverflow(second)
        val tail = mapper.finish()
        assertTrue(
            "retired giant ids must leave no buffered ToolDone",
            tail.filterIsInstance<StreamEvent.ToolDone>().isEmpty(),
        )
        assertEquals(
            StreamEvent.Failed("SSE_TRUNCATED", retryable = true),
            tail.single(),
        )
    }

    @Test
    fun responsesDrainGuardsGiantPendingId() {
        // Drain backstop: pre-fix, a 10KB call_id was admitted via added
        // (per-call/total char budgets both admit a single 10KB id) with a
        // non-empty name so finishPendingTools wanted to emit it, and only
        // the drain per-key guard could fail closed. With the added fail-fast,
        // this shape now throws AT added (before write-back), so the drain
        // never sees it — the drain guard remains as defense-in-depth and is
        // asserted here via the same typed failure.
        val mapper = ResponsesMapper()
        val giantCall = "c".repeat(10_000)
        // Fail-fast now fires at added (not at completed): wrap both so the
        // test locks fail-closed regardless of which gate fires first.
        val failure = toolFailureOf {
            mapper.mapPayload(
                """{"type":"response.output_item.added","item_id":"it1","item":{"type":"function_call","call_id":"$giantCall","name":"f"}}""",
            )
            mapper.mapPayload("""{"type":"response.completed","response":{"output":[]}}""")
        }
        assertAggOverflow(failure)
        val tail = mapper.finish()
        assertTrue(
            "giant pending id must leave no buffered ToolDone",
            tail.filterIsInstance<StreamEvent.ToolDone>().isEmpty(),
        )
        assertEquals(
            StreamEvent.Failed("SSE_TRUNCATED", retryable = true),
            tail.single(),
        )
    }

    @Test
    fun requireToolIdBudgetBoundary() {
        requireToolIdBudget(0)
        requireToolIdBudget(MAX_TOOL_ID_CHARS)
        val over = toolFailureOf { requireToolIdBudget(MAX_TOOL_ID_CHARS + 1) }
        assertAggOverflow(over)
    }

    @Test
    fun responsesCapBoundaryItemIdAdmitted() {
        // Exact-boundary positive control: an item_id of exactly
        // MAX_TOOL_ID_CHARS is admitted (proves the 200x headroom does not
        // break legal shapes); one char more fails closed before insert.
        val mapper = ResponsesMapper()
        val atCap = "k".repeat(MAX_TOOL_ID_CHARS)
        val out = mapper.mapPayload(
            """{"type":"response.output_item.added","item_id":"$atCap","item":{"type":"function_call","call_id":"c1","name":"f"}}""",
        )
        assertEquals(1, out.filterIsInstance<StreamEvent.ToolDelta>().size)
        val mapper2 = ResponsesMapper()
        val over = toolFailureOf {
            mapper2.mapPayload(
                """{"type":"response.output_item.added","item_id":"${atCap}x","item":{"type":"function_call","call_id":"c1","name":"f"}}""",
            )
        }
        assertAggOverflow(over)
        val tail = mapper2.finish()
        assertTrue(tail.filterIsInstance<StreamEvent.ToolDone>().isEmpty())
        assertEquals(
            StreamEvent.Failed("SSE_TRUNCATED", retryable = true),
            tail.single(),
        )
    }

    // ---- S1 follow-up: added-carried id + delta/done item_id + empty control ----
    //
    // Literals-only payload tests (compile pre-fix, fail pre-fix): the added
    // call_id fail-fast, the delta item_id guard, and the done item_id guard
    // (with a short call_id so only the map-key guard can fire) each throw
    // typed with no ToolDelta/ToolDone pollution; finish stays the retryable
    // truncation. The empty-id test is the positive control (no throw).

    @Test
    fun responsesAddedGiantCallIdFailsClosed() {
        // Added fail-fast: a 10KB call_id carried in added (short item_id)
        // fits the per-call/total char budgets, so only the per-key cap can
        // fail closed. Must throw typed BEFORE char/total checks and BEFORE
        // write-back: no ToolDelta emitted, giant never retained, finish is
        // the retryable truncation with no ToolDone.
        val mapper = ResponsesMapper()
        val giantCall = "c".repeat(10_000)
        val failure = toolFailureOf {
            mapper.mapPayload(
                """{"type":"response.output_item.added","item_id":"it1","item":{"type":"function_call","call_id":"$giantCall","name":"f"}}""",
            )
        }
        assertAggOverflow(failure)
        val tail = mapper.finish()
        assertTrue(
            "giant added id must leave no buffered ToolDone",
            tail.filterIsInstance<StreamEvent.ToolDone>().isEmpty(),
        )
        assertEquals(
            StreamEvent.Failed("SSE_TRUNCATED", retryable = true),
            tail.single(),
        )
    }

    @Test
    fun responsesDeltaGiantItemIdFailsClosed() {
        // Delta path: a 300KB item_id with a tiny fragment. Char/total/count
        // all stay silent (single admission, tiny frag), so only the per-key
        // cap fails closed BEFORE insert — giant key never retained.
        val mapper = ResponsesMapper()
        val giant = "k".repeat(300_000)
        val failure = toolFailureOf {
            mapper.mapPayload(
                """{"type":"response.function_call_arguments.delta","item_id":"$giant","delta":"x"}""",
            )
        }
        assertAggOverflow(failure)
        val tail = mapper.finish()
        assertTrue(tail.filterIsInstance<StreamEvent.ToolDone>().isEmpty())
        assertEquals(
            StreamEvent.Failed("SSE_TRUNCATED", retryable = true),
            tail.single(),
        )
    }

    @Test
    fun responsesDoneGiantItemIdFailsClosed() {
        // Done path map-key guard: a 300KB item_id with a short call_id.
        // The emitted-id guard would pass (short "c1"), so this locks that
        // the ITEM_ID guard fires first, BEFORE insert — no entry, no emit.
        val mapper = ResponsesMapper()
        val giant = "k".repeat(300_000)
        val failure = toolFailureOf {
            mapper.mapPayload(
                """{"type":"response.output_item.done","item_id":"$giant","item":{"type":"function_call","call_id":"c1","name":"f"}}""",
            )
        }
        assertAggOverflow(failure)
        val tail = mapper.finish()
        assertTrue(tail.filterIsInstance<StreamEvent.ToolDone>().isEmpty())
        assertEquals(
            StreamEvent.Failed("SSE_TRUNCATED", retryable = true),
            tail.single(),
        )
    }

    @Test
    fun responsesEmptyIdsStillAdmitted() {
        // Positive control: empty/missing ids keep current quota behavior
        // (still admitted, still billed) — the per-key cap only rejects
        // OVERLONG keys, never empty ones.
        val mapper = ResponsesMapper()
        // Missing call_id/name: emits ToolDelta with nulls, no throw.
        val out = mapper.mapPayload(
            """{"type":"response.output_item.added","item_id":"it1","item":{"type":"function_call"}}""",
        )
        assertEquals(1, out.filterIsInstance<StreamEvent.ToolDelta>().size)
        // Empty call_id/name strings are treated as missing (not billed, not thrown).
        val out2 = mapper.mapPayload(
            """{"type":"response.output_item.added","item_id":"it1","item":{"type":"function_call","call_id":"","name":""}}""",
        )
        assertEquals(1, out2.filterIsInstance<StreamEvent.ToolDelta>().size)
        // Empty item_id string is admitted (length 0 <= cap), not rejected.
        val mapper2 = ResponsesMapper()
        val out3 = mapper2.mapPayload(
            """{"type":"response.output_item.added","item_id":"","item":{"type":"function_call","call_id":"c1","name":"f"}}""",
        )
        assertEquals(1, out3.filterIsInstance<StreamEvent.ToolDelta>().size)
    }
}
