package dev.librepocket.provider

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * retryable). Nothing is silently truncated. Over-ceiling JSON nesting/density
 * (S2/Phase 2B) throws the same typed triple via the MiniJson fatal parser
 * subtypes (depth or node count), latched per attempt so no later terminal
 * becomes Done; done-path ToolDone resends of an already-emitted pair emit
 * nothing (emit-once).
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

    // ---- P1-2: function_call_arguments.done authoritative-full budgeting ----

    @Test
    fun responsesArgumentsDoneOverBudgetFailsTypedWithoutMutationOrEmit() {
        // The authoritative full is budgeted as a whole replacement BEFORE
        // mutation: a 400k full (x3 = 1.2 MiB) throws typed, leaves the
        // truncated delta untouched, and emits nothing — the later terminal
        // can only recover the pre-overflow buffered state.
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.function_call_arguments.delta","item_id":"it1","delta":"{\"a\":1"}""",
        )
        val over = toolFailureOf {
            mapper.mapPayload(
                """{"type":"response.function_call_arguments.done","item_id":"it1","arguments":"${"x".repeat(400_000)}"}""",
            )
        }
        assertAggOverflow(over)
        val tail = mapper.mapPayload("""{"type":"response.completed","response":{"output":[]}}""")
        val done = tail.filterIsInstance<StreamEvent.ToolDone>().single()
        assertEquals("{\"a\":1", done.argumentsJson)
        assertEquals(StreamEvent.Done("stop"), tail.last())
    }

    @Test
    fun responsesArgumentsDoneShorterThanDeltasKeepsTotalMonotonic() {
        // An authoritative replacement may shrink the live buffer (growth
        // floored at 0); the round total stays monotonic, so a hostile
        // shrink/expand cycle cannot reset the billed chars and re-fan-out.
        // 300k buffered, then replaced by "{}": the live buffer is 2 chars but
        // the billed total stays 300k. A NEW 60k call must therefore overflow
        // (360k > 349525) — if the shrink had decremented the total it would
        // fit. The 40k control then proves the rejected 60k call did not
        // mutate or poison the total (300k + 40k still fits).
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.function_call_arguments.delta","item_id":"it1","delta":"${"d".repeat(300_000)}"}""",
        )
        mapper.mapPayload(
            """{"type":"response.function_call_arguments.done","item_id":"it1","arguments":"{}"}""",
        )
        val over = toolFailureOf {
            mapper.mapPayload(
                """{"type":"response.function_call_arguments.done","item_id":"it2","arguments":"${"e".repeat(60_000)}"}""",
            )
        }
        assertAggOverflow(over)
        // Positive control in the SAME attempt: 300k + 40k + framing is still
        // under the per-attempt ceiling, so this must be admitted (no throw)
        // and the returned events contain no failure.
        val control = mapper.mapPayload(
            """{"type":"response.function_call_arguments.done","item_id":"it3","arguments":"${"f".repeat(40_000)}"}""",
        )
        assertTrue(control.none { it is StreamEvent.Failed })
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
    fun responsesDoneResendAcceptedIdAtCapDuplicateIsTrueNoOp() {
        // Emit-once per (toolIndex, id) pair: at-cap re-send of an
        // already-emitted pair emits NOTHING (output no-op, no throw) — the
        // guard charges only genuinely-new pairs, so a legal duplicate after
        // the cap is never misread as an overflow and never re-emits.
        val mapper = ResponsesMapper()
        for (i in 0 until MAX_TOOL_CALLS_PER_ATTEMPT) {
            mapper.mapPayload(
                """{"type":"response.output_item.done","item_id":"it1","item":{"type":"function_call","call_id":"${String.format("c%03d", i)}","name":"f"}}""",
            )
        }
        val out = mapper.mapPayload(
            """{"type":"response.output_item.done","item_id":"it1","item":{"type":"function_call","call_id":"c000","name":"f"}}""",
        )
        assertTrue("duplicate of an already-emitted pair emits nothing", out.isEmpty())
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

    @Test
    fun completedPromotionRejectsOverlongDeclaredIdBeforeRetention() {
        // Reverse-mixed promotion in terminal reconciliation must run the same
        // per-key guard as requireFunctionCallKey BEFORE the declared key is
        // inserted. Pre-fix the promoted existing-bucket path returned before
        // the itemKey guard, so a frame-budget-sized terminal id was retained
        // as a callsByItem key and closed as a real call.
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","name":"f"}}""",
        )
        mapper.mapPayload(
            """{"type":"response.function_call_arguments.delta","output_index":0,"delta":"{}"}""",
        )
        val giant = "k".repeat(MAX_TOOL_ID_CHARS + 1)
        val failure = toolFailureOf {
            mapper.mapPayload(
                """{"type":"response.completed","response":{"output":[{"type":"function_call","id":"$giant","call_id":"c1","name":"f","arguments":"{}"}]}}""",
            )
        }
        assertAggOverflow(failure)
        val tail = mapper.finish()
        assertTrue(
            "overlong terminal id must never be emitted",
            tail.filterIsInstance<StreamEvent.ToolDone>().none { it.id == giant },
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
    fun responsesEmptyItemIdFallsBackToOutputIndexOrFailsClosed() {
        // Positive control: missing call_id/name still emit ToolDelta with
        // nulls, no throw — the per-key cap only rejects overlong keys. Empty
        // strings are identity-"absent" now, never a shared "" bucket.
        val mapper = ResponsesMapper()
        val out = mapper.mapPayload(
            """{"type":"response.output_item.added","item_id":"it1","item":{"type":"function_call"}}""",
        )
        assertEquals(1, out.filterIsInstance<StreamEvent.ToolDelta>().size)
        // Empty call_id/name strings are treated as missing (not billed, not thrown).
        val out2 = mapper.mapPayload(
            """{"type":"response.output_item.added","item_id":"it1","item":{"type":"function_call","call_id":"","name":""}}""",
        )
        assertEquals(1, out2.filterIsInstance<StreamEvent.ToolDelta>().size)
        // Empty item_id falls through to the frame's output_index.
        val mapper2 = ResponsesMapper()
        val out3 = mapper2.mapPayload(
            """{"type":"response.output_item.added","item_id":"","output_index":3,"item":{"type":"function_call","call_id":"c1","name":"f"}}""",
        )
        assertEquals(1, out3.filterIsInstance<StreamEvent.ToolDelta>().size)
        // No identity at all (empty item_id, no output_index, no item id):
        // fail closed typed non-retryable instead of random-bucketing.
        val missing = toolFailureOf {
            ResponsesMapper().mapPayload(
                """{"type":"response.output_item.added","item_id":"","item":{"type":"function_call","call_id":"c1","name":"f"}}""",
            )
        }
        assertEquals("RESPONSES_IDENTITY_MISSING", missing.message)
        assertFalse(missing.retryable)
    }

    // ---- S2: JSON depth fail-closed (typed, sticky) + done emit-once ----
    //
    // Over-ceiling frames throw MiniJsonDepthException or
    // MiniJsonNodeLimitException, both fatal MiniJsonException subtypes (the
    // common base is MiniJsonFatalException). Mappers translate ONLY the fatal
    // base into the typed non-retryable aggregation failure; plain malformed
    // frames still drop per frame. The breach latches per attempt: later
    // terminals can never become Done, and finish() reports the typed failure —
    // never a retryable SSE_TRUNCATED. Done-path resends of an already-emitted
    // (toolIndex, id) pair emit nothing (emit-once, mirroring the drain).

    /** `levels` nested arrays wrapping a `0` scalar (over-ceiling junk). */
    private fun deepJunk(levels: Int = MiniJson.MAX_DEPTH + 1): String =
        "[".repeat(levels) + "0" + "]".repeat(levels)

    private fun assertDepthFailed(e: ProviderFailure) = assertFatalBreach(e, "depth breach")

    private fun assertNodeFailed(e: ProviderFailure) = assertFatalBreach(e, "node breach")

    /** Same typed triple as every aggregation overflow; [what] labels the breach. */
    private fun assertFatalBreach(e: ProviderFailure, what: String) {
        assertEquals(TOOL_ARGS_TOO_LARGE, e.message)
        assertFalse("$what must not retry the poisoned stream", e.retryable)
        assertEquals(ProviderFailureCode.AGG_TOO_LARGE, e.code)
    }

    private fun assertDepthFailedTerminal(events: List<StreamEvent>) =
        assertFatalBreachTerminal(events, "depth breach")

    private fun assertNodeFailedTerminal(events: List<StreamEvent>) =
        assertFatalBreachTerminal(events, "node breach")

    private fun assertFatalBreachTerminal(events: List<StreamEvent>, what: String) {
        val failed = events.filterIsInstance<StreamEvent.Failed>().single()
        assertEquals(TOOL_ARGS_TOO_LARGE, failed.message)
        assertFalse("$what must not retry the poisoned stream", failed.retryable)
        assertEquals(ProviderFailureCode.AGG_TOO_LARGE.name, failed.code)
        assertTrue("no Done after a $what", events.none { it is StreamEvent.Done })
        assertTrue("no ToolDone after a $what", events.none { it is StreamEvent.ToolDone })
        assertTrue(
            "$what is never the retryable truncation",
            events.none { it == StreamEvent.Failed("SSE_TRUNCATED", retryable = true) },
        )
    }

    @Test
    fun plainMalformedFrameStillDropsAndStreamContinues() {
        // Negative control for the fatal-parser branch: a malformed frame that
        // breaches neither depth nor the node cap still drops per frame
        // (emptyList) and the stream keeps projecting — only fatal subtypes
        // throw.
        val mapper = ResponsesMapper()
        assertTrue(mapper.mapPayload("{not json").isEmpty())
        val ok = mapper.mapPayload("""{"type":"response.output_text.delta","delta":"ok"}""")
        val texts = ok.filterIsInstance<StreamEvent.TextDelta>()
        assertEquals(1, texts.size)
        assertEquals("ok", texts[0].delta)
    }

    @Test
    fun responsesDeepFrameWithValidDeltaThrowsTypedAndTerminalNeverDone() {
        // The poisoned frame carries a VALID delta ("hi"): pre-fix the whole
        // frame was dropped and a later terminal succeeded without it (fake
        // success). Now the frame throws typed, the valid delta is never
        // surfaced, and the legal authoritative terminal rethrows instead of
        // becoming Done.
        val mapper = ResponsesMapper()
        val over = toolFailureOf {
            mapper.mapPayload(
                """{"type":"response.output_text.delta","delta":"hi","junk":${deepJunk()}}""",
            )
        }
        assertDepthFailed(over)
        val terminalThrow = toolFailureOf {
            mapper.mapPayload("""{"type":"response.completed","response":{"output":[]}}""")
        }
        assertDepthFailed(terminalThrow)
        assertDepthFailedTerminal(mapper.finish())
    }

    @Test
    fun chatCompletionsDeepFrameThrowsTypedAndDoneNeverFollows() {
        // Same fake-success shape on the Chat Completions mapper: a deep frame
        // with valid content throws typed, and a later finish_reason + [DONE]
        // pair can never become Done.
        val mapper = ChatCompletionsMapper()
        val over = toolFailureOf {
            mapper.mapPayload("""{"choices":[{"delta":{"content":"hi"}}],"junk":${deepJunk()}}""")
        }
        assertDepthFailed(over)
        val terminalThrow = toolFailureOf {
            mapper.mapPayload("""{"choices":[{"finish_reason":"stop"}]}""")
        }
        assertDepthFailed(terminalThrow)
        // Even a transport-level [DONE] after the breach cannot resurrect it.
        mapper.markDone()
        assertDepthFailedTerminal(mapper.finish())
    }

    @Test
    fun anthropicOnlyDeepFrameThenEofIsTypedNonRetryable() {
        // Only an over-deep frame, then EOF: finish() is the typed
        // non-retryable failure — never the retryable SSE_TRUNCATED that
        // would retry the poisoned stream — and message_stop can never Done.
        val mapper = AnthropicMapper()
        val over = toolFailureOf {
            mapper.mapPayload(
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"},"junk":${deepJunk()}}""",
            )
        }
        assertDepthFailed(over)
        val stopThrow = toolFailureOf {
            mapper.mapPayload("""{"type":"message_stop"}""")
        }
        assertDepthFailed(stopThrow)
        assertDepthFailedTerminal(mapper.finish())
    }

    // ---- Phase 2B: JSON node-ceiling fail-closed (typed, sticky) ----
    //
    // Over-ceiling node count throws MiniJsonNodeLimitException, the sibling
    // fatal subtype of MiniJsonDepthException: depth bounds recursion, the
    // node cap bounds FLAT allocation (a dense junk array nests one level). A
    // poisoned frame carrying a valid delta must not be dropped (fake
    // success) nor retried (poisoned stream): the mappers discriminate the
    // fatal base, so translation, latch, and terminal semantics are exactly
    // the S2 depth contract — typed non-retryable AGG_TOO_LARGE, later
    // terminals rethrow, finish() never SSE_TRUNCATED, never Done.

    /** Flat dense array of [values] zeros: `values + 1` nodes (one array + one per element). */
    private fun denseJunk(values: Int): String = "[" + "0" + ",0".repeat(values - 1) + "]"

    @Test
    fun responsesNodeCeilingBoundaryIsExact() {
        // Frame nodes: 6 fixed (object + 3 keys + 2 string values) plus
        // denseJunk(k)'s k + 1 (its array + k elements). k = MAX_NODES - 7
        // puts the frame exactly at the ceiling; one more junk element is N+1
        // and must fail closed.
        val k = MiniJson.MAX_NODES - 7
        val atCeiling = ResponsesMapper().mapPayload(
            """{"type":"response.output_text.delta","delta":"hi","junk":${denseJunk(k)}}""",
        )
        assertEquals(
            "at-ceiling frame must parse and project its delta",
            "hi",
            atCeiling.filterIsInstance<StreamEvent.TextDelta>().single().delta,
        )
        val over = toolFailureOf {
            ResponsesMapper().mapPayload(
                """{"type":"response.output_text.delta","delta":"hi","junk":${denseJunk(k + 1)}}""",
            )
        }
        assertNodeFailed(over)
    }

    @Test
    fun chatCompletionsNodeCeilingBoundaryIsExact() {
        // Frame nodes: 9 fixed (object + 4 keys + choices array + nested
        // object + delta object + content string) plus denseJunk(k)'s k + 1.
        val k = MiniJson.MAX_NODES - 10
        val atCeiling = ChatCompletionsMapper().mapPayload(
            """{"choices":[{"delta":{"content":"hi"}}],"junk":${denseJunk(k)}}""",
        )
        assertEquals(
            "at-ceiling frame must parse and project its delta",
            "hi",
            atCeiling.filterIsInstance<StreamEvent.TextDelta>().single().delta,
        )
        val over = toolFailureOf {
            ChatCompletionsMapper().mapPayload(
                """{"choices":[{"delta":{"content":"hi"}}],"junk":${denseJunk(k + 1)}}""",
            )
        }
        assertNodeFailed(over)
    }

    @Test
    fun anthropicNodeCeilingBoundaryIsExact() {
        // Frame nodes: 12 fixed (object + 6 keys + 3 string values + number +
        // nested object) plus denseJunk(k)'s k + 1. A text delta needs an
        // open block first (an unknown index would latch the P1-3 protocol
        // breach instead of projecting the delta).
        val k = MiniJson.MAX_NODES - 13
        val atCeiling = AnthropicMapper().apply {
            mapPayload("""{"type":"content_block_start","index":0,"content_block":{"type":"text"}}""")
        }.mapPayload(
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"},"junk":${denseJunk(k)}}""",
        )
        assertEquals(
            "at-ceiling frame must parse and project its delta",
            "hi",
            atCeiling.filterIsInstance<StreamEvent.TextDelta>().single().delta,
        )
        val over = toolFailureOf {
            val mapper = AnthropicMapper()
            mapper.mapPayload("""{"type":"content_block_start","index":0,"content_block":{"type":"text"}}""")
            mapper.mapPayload(
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"},"junk":${denseJunk(k + 1)}}""",
            )
        }
        assertNodeFailed(over)
    }

    @Test
    fun responsesNodeLimitFrameWithValidDeltaThrowsTypedAndTerminalNeverDone() {
        // The dense frame carries a VALID delta ("hi"): it is not a
        // droppable malformed frame, so the attempt fails typed, the legal
        // authoritative terminal rethrows instead of becoming Done, and the
        // latched finish() is the typed non-retryable failure.
        val mapper = ResponsesMapper()
        val over = toolFailureOf {
            mapper.mapPayload(
                """{"type":"response.output_text.delta","delta":"hi","junk":${denseJunk(MiniJson.MAX_NODES)}}""",
            )
        }
        assertNodeFailed(over)
        val terminalThrow = toolFailureOf {
            mapper.mapPayload("""{"type":"response.completed","response":{"output":[]}}""")
        }
        assertNodeFailed(terminalThrow)
        val first = mapper.finish()
        assertNodeFailedTerminal(first)
        // Repeated finish() after the latch stays the same typed failure
        // (no second emit, no SSE_TRUNCATED, no Done).
        assertEquals("latched finish() is idempotent", first, mapper.finish())
    }

    @Test
    fun chatCompletionsNodeLimitFrameThrowsTypedAndDoneNeverFollows() {
        val mapper = ChatCompletionsMapper()
        val over = toolFailureOf {
            mapper.mapPayload(
                """{"choices":[{"delta":{"content":"hi"}}],"junk":${denseJunk(MiniJson.MAX_NODES)}}""",
            )
        }
        assertNodeFailed(over)
        val terminalThrow = toolFailureOf {
            mapper.mapPayload("""{"choices":[{"finish_reason":"stop"}]}""")
        }
        assertNodeFailed(terminalThrow)
        // Even a transport-level [DONE] after the breach cannot resurrect it.
        mapper.markDone()
        assertNodeFailedTerminal(mapper.finish())
    }

    @Test
    fun anthropicOnlyNodeLimitFrameThenEofIsTypedNonRetryable() {
        val mapper = AnthropicMapper()
        val over = toolFailureOf {
            mapper.mapPayload(
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"},"junk":${denseJunk(MiniJson.MAX_NODES)}}""",
            )
        }
        assertNodeFailed(over)
        val stopThrow = toolFailureOf {
            mapper.mapPayload("""{"type":"message_stop"}""")
        }
        assertNodeFailed(stopThrow)
        assertNodeFailedTerminal(mapper.finish())
    }

    @Test
    fun responsesDuplicateToolDoneReplayEmitsOnce() {
        // N=100 resends of the SAME (toolIndex, id) pair: exactly one ToolDone
        // total, no throw, and the drain stays silent (already-emitted). The
        // honest EOF terminal is unchanged.
        val mapper = ResponsesMapper()
        var emitted = 0
        repeat(100) {
            emitted += mapper.mapPayload(
                """{"type":"response.output_item.done","item_id":"it1","item":{"type":"function_call","call_id":"c1","name":"f"}}""",
            ).filterIsInstance<StreamEvent.ToolDone>().size
        }
        assertEquals("one pair emits exactly once across 100 resends", 1, emitted)
        val tail = mapper.finish()
        assertTrue(
            "already-emitted pair drains silently",
            tail.filterIsInstance<StreamEvent.ToolDone>().isEmpty(),
        )
        assertEquals(
            StreamEvent.Failed("SSE_TRUNCATED", retryable = true),
            tail.single(),
        )
    }

    // ---- S4: moved back from ProviderTransportC1Test (mapper-level, no wire) ----
    //
    // Original RED case of the blocks-map cap: 65 distinct TEXT block indexes
    // (a legal small SSE line each) must trip the shared distinct-index budget
    // with the same typed non-retryable failure as tool overflows. Lives here
    // with the other mapper-level discriminating variants (thinking flood,
    // mixed kinds, restart-is-not-admission, demotion); the wire twin is
    // ProviderTransportC1Test.anthropicStreamSixtyFiveDistinctBlocksFailsTypedWithoutDone.

    @Test
    fun anthropicManyDistinctTextBlocksTripBlockCapTyped() {
        val mapper = AnthropicMapper()
        for (i in 0 until MAX_TOOL_CALLS_PER_ATTEMPT) {
            mapper.mapPayload(
                """{"type":"content_block_start","index":$i,"content_block":{"type":"text","text":""}}""",
            )
        }
        try {
            mapper.mapPayload(
                """{"type":"content_block_start","index":$MAX_TOOL_CALLS_PER_ATTEMPT,"content_block":{"type":"text","text":""}}""",
            )
            fail("expected 65th distinct Anthropic block to trip the distinct-index budget")
        } catch (e: ProviderFailure) {
            assertEquals(TOOL_ARGS_TOO_LARGE, e.message)
            assertFalse(e.retryable)
            assertEquals(ProviderFailureCode.AGG_TOO_LARGE, e.code)
        }
    }

    // ---- S4: guard-priority determinism (same item, rotating call_id) ----
    //
    // Responses done-path guard order is id (4096) -> per-call -> total ->
    // emitted-count. A 10KB rotating call_id trips the per-key id guard on the
    // very first arrival (iteration 0); per-candidate growth is irrelevant and
    // total/emitted are never reached. A 4-char rotation keeps every char
    // guard silent (equal lengths => growth 0) so the emitted-count cap wins
    // deterministically on the 65th distinct pair (iteration 64).

    @Test
    fun responsesRotatingGiantCallIdIdGuardWinsFirstIteration() {
        val mapper = ResponsesMapper()
        var firstIteration = -1
        var failure: ProviderFailure? = null
        for (i in 0 until MAX_TOOL_CALLS_PER_ATTEMPT + 1) {
            val callId = "c".repeat(10_000 - 4) + String.format("%04d", i)
            assertEquals(10_000, callId.length)
            try {
                mapper.mapPayload(
                    """{"type":"response.output_item.done","item_id":"it1","item":{"type":"function_call","call_id":"$callId","name":"f"}}""",
                )
            } catch (e: ProviderFailure) {
                if (failure == null) {
                    failure = e
                    firstIteration = i
                    break
                }
            }
        }
        assertTrue("10KB rotation must throw on the first arrival", failure != null)
        assertEquals("id guard wins before total/emitted: iteration 0", 0, firstIteration)
        assertAggOverflow(failure!!)
        val tail = mapper.finish()
        assertTrue(tail.filterIsInstance<StreamEvent.ToolDone>().isEmpty())
        assertEquals(
            StreamEvent.Failed("SSE_TRUNCATED", retryable = true),
            tail.single(),
        )
    }

    @Test
    fun responsesRotatingSmallCallIdEmittedWinsAtSixtyFifth() {
        // Same fixed item_id, 4-char rotating call_ids ("c000".."c064"): every
        // candidate is ~5 chars, growth 0 after the first, so per-call/total
        // stay silent and only the emitted (toolIndex, id) count can trip —
        // deterministically on the 65th distinct pair (iteration 64).
        val mapper = ResponsesMapper()
        var firstIteration = -1
        var failure: ProviderFailure? = null
        for (i in 0 until MAX_TOOL_CALLS_PER_ATTEMPT + 1) {
            val callId = String.format("c%03d", i)
            try {
                mapper.mapPayload(
                    """{"type":"response.output_item.done","item_id":"it1","item":{"type":"function_call","call_id":"$callId","name":"f"}}""",
                )
            } catch (e: ProviderFailure) {
                if (failure == null) {
                    failure = e
                    firstIteration = i
                    break
                }
            }
        }
        assertTrue("small rotation must trip the emitted cap", failure != null)
        assertEquals(
            "emitted-count wins deterministically on the 65th pair (iteration 64)",
            MAX_TOOL_CALLS_PER_ATTEMPT,
            firstIteration,
        )
        assertAggOverflow(failure!!)
    }

    // ---- S4: finishPendingTools drain executes at the cap (2x loose bound) ----
    //
    // The drain adds at most one pair per already-capped callsByItem entry, so
    // the emitted set stays within 2x the admission cap (at most 128 total
    // pairs, loose upper bound; each pair emitted at most once). Both halves
    // are executed here: 64 pending added
    // entries drain into exactly 64 ToolDone + Done, and 64 already-emitted
    // done-path pairs drain silently into Done alone.

    @Test
    fun responsesDrainAtCapEmitsSixtyFourToolDonePlusDone() {
        val mapper = ResponsesMapper()
        for (i in 0 until MAX_TOOL_CALLS_PER_ATTEMPT) {
            val out = mapper.mapPayload(
                """{"type":"response.output_item.added","item_id":"it$i","item":{"type":"function_call","call_id":"c$i","name":"f"}}""",
            )
            assertEquals(1, out.filterIsInstance<StreamEvent.ToolDelta>().size)
        }
        val tail = mapper.mapPayload("""{"type":"response.completed","response":{"output":[]}}""")
        val dones = tail.filterIsInstance<StreamEvent.ToolDone>()
        assertEquals(
            "64 pending added entries drain into exactly 64 ToolDone",
            MAX_TOOL_CALLS_PER_ATTEMPT,
            dones.size,
        )
        assertEquals(
            (0 until MAX_TOOL_CALLS_PER_ATTEMPT).toSet(),
            dones.map { it.toolIndex }.toSet(),
        )
        assertEquals(StreamEvent.Done("stop"), tail.last())
        assertTrue("drain at cap is clean: no Failed", tail.none { it is StreamEvent.Failed })
        // Terminal latches: a repeated finish() stays silent (converged).
        assertTrue(mapper.finish().isEmpty())
    }

    @Test
    fun responsesCompletedAfterSixtyFourDonesDrainsSilently() {
        val mapper = ResponsesMapper()
        for (i in 0 until MAX_TOOL_CALLS_PER_ATTEMPT) {
            val out = mapper.mapPayload(
                """{"type":"response.output_item.done","item_id":"it$i","item":{"type":"function_call","call_id":"c$i","name":"f"}}""",
            )
            assertEquals(1, out.filterIsInstance<StreamEvent.ToolDone>().size)
        }
        val tail = mapper.mapPayload("""{"type":"response.completed","response":{"output":[]}}""")
        assertTrue(
            "already-emitted pairs drain silently",
            tail.filterIsInstance<StreamEvent.ToolDone>().isEmpty(),
        )
        assertEquals(listOf(StreamEvent.Done("stop")), tail)
        assertTrue(mapper.finish().isEmpty())
    }

    // ---- S4: object + mixed-alternate nesting 64/65 (arrays already locked) ----
    //
    // MiniJson counts objects and arrays equally toward MAX_DEPTH (the only
    // two recursive productions share one withDepth guard). 64 nested objects
    // parse; the 65th fails closed with the discriminable depth subtype. The
    // mixed alternate (array/object churn) proves the counter is shared, not
    // per-kind. Mapper-level twins prove the depth subtype translates to the
    // same typed triple with the same latch (no later Done).

    /** `levels` nested objects wrapping a `0` scalar. */
    private fun deepObjJunk(levels: Int): String =
        "{\"a\":".repeat(levels) + "0" + "}".repeat(levels)

    /** `levels` alternating array/object nestings wrapping a `0` scalar. */
    private fun deepMixedJunk(levels: Int): String = buildString {
        for (i in 0 until levels) {
            if (i % 2 == 0) append('[') else append("{\"a\":")
        }
        append('0')
        for (i in levels - 1 downTo 0) {
            if (i % 2 == 0) append(']') else append('}')
        }
    }

    @Test
    fun objectNestingAtCeilingParsesBeyondFailsClosed() {
        val atCap = MiniJson.parse(deepObjJunk(MiniJson.MAX_DEPTH))
        assertTrue(atCap is MiniJson.JObj)
        try {
            MiniJson.parse(deepObjJunk(MiniJson.MAX_DEPTH + 1))
            fail("65 nested objects must fail closed")
        } catch (e: MiniJson.MiniJsonException) {
            assertTrue(
                "object depth breach must be the discriminable depth subtype",
                e is MiniJson.MiniJsonDepthException,
            )
        }
    }

    @Test
    fun mixedAlternateNestingAtCeilingParsesBeyondFailsClosed() {
        val atCap = MiniJson.parse(deepMixedJunk(MiniJson.MAX_DEPTH))
        assertTrue(atCap is MiniJson.JArr)
        try {
            MiniJson.parse(deepMixedJunk(MiniJson.MAX_DEPTH + 1))
            fail("65 mixed-alternate nestings must fail closed")
        } catch (e: MiniJson.MiniJsonException) {
            assertTrue(
                "mixed depth breach must be the discriminable depth subtype",
                e is MiniJson.MiniJsonDepthException,
            )
        }
    }

    @Test
    fun objectDeepFrameViaMapperThrowsTypedAndTerminalNeverDone() {
        val mapper = ResponsesMapper()
        val over = toolFailureOf {
            mapper.mapPayload(
                """{"type":"response.output_text.delta","delta":"hi","junk":${deepObjJunk(MiniJson.MAX_DEPTH + 1)}}""",
            )
        }
        assertAggOverflow(over)
        val terminalThrow = toolFailureOf {
            mapper.mapPayload("""{"type":"response.completed","response":{"output":[]}}""")
        }
        assertAggOverflow(terminalThrow)
        val tail = mapper.finish()
        assertEquals(TOOL_ARGS_TOO_LARGE, (tail.single() as StreamEvent.Failed).message)
        assertFalse((tail.single() as StreamEvent.Failed).retryable)
        assertEquals(ProviderFailureCode.AGG_TOO_LARGE.name, (tail.single() as StreamEvent.Failed).code)
        assertTrue(tail.none { it is StreamEvent.Done })
        assertTrue(tail.none { it is StreamEvent.ToolDone })
    }

    @Test
    fun mixedDeepFrameViaMapperThrowsTypedAndTerminalNeverDone() {
        val mapper = ResponsesMapper()
        val over = toolFailureOf {
            mapper.mapPayload(
                """{"type":"response.output_text.delta","delta":"hi","junk":${deepMixedJunk(MiniJson.MAX_DEPTH + 1)}}""",
            )
        }
        assertAggOverflow(over)
        val terminalThrow = toolFailureOf {
            mapper.mapPayload("""{"type":"response.completed","response":{"output":[]}}""")
        }
        assertAggOverflow(terminalThrow)
        val tail = mapper.finish()
        assertEquals(TOOL_ARGS_TOO_LARGE, (tail.single() as StreamEvent.Failed).message)
        assertFalse((tail.single() as StreamEvent.Failed).retryable)
        assertEquals(ProviderFailureCode.AGG_TOO_LARGE.name, (tail.single() as StreamEvent.Failed).code)
        assertTrue(tail.none { it is StreamEvent.Done })
        assertTrue(tail.none { it is StreamEvent.ToolDone })
    }

    // ---- Phase 2: terminal-only reconciliation is behind the same guards ----
    //
    // `response.completed`'s non-empty `output` is a second admission path
    // (no prior added/delta frames). It must consume the same distinct-item
    // slot per function_call and enforce the same per-call char budget; a
    // hostile terminal-only array cannot bypass either.

    /** Terminal-only `response.completed` with [calls] function_call items. */
    private fun terminalOnlyCalls(calls: Int, args: String = "{}"): String {
        val items = (0 until calls).joinToString(",") {
            """{"type":"function_call","id":"it$it","call_id":"c$it","name":"f","arguments":"$args"}"""
        }
        return """{"type":"response.completed","response":{"output":[$items]}}"""
    }

    @Test
    fun responsesTerminalOnlyCompletedOutputCountCapIsExact() {
        val atCap = ResponsesMapper().mapPayload(
            terminalOnlyCalls(MAX_TOOL_CALLS_PER_ATTEMPT),
        )
        assertEquals(
            MAX_TOOL_CALLS_PER_ATTEMPT,
            atCap.filterIsInstance<StreamEvent.ToolDone>().size,
        )
        assertEquals(StreamEvent.Done("stop"), atCap.last())
        val over = toolFailureOf {
            ResponsesMapper().mapPayload(
                terminalOnlyCalls(MAX_TOOL_CALLS_PER_ATTEMPT + 1),
            )
        }
        assertAggOverflow(over)
    }

    @Test
    fun responsesTerminalOnlyCompletedOutputPerCallBudgetIsBounded() {
        // 400k chars > 349525 at x3: the terminal-only full arguments must
        // trip the same per-call budget as the delta/added paths.
        val over = toolFailureOf {
            ResponsesMapper().mapPayload(terminalOnlyCalls(1, args = "a".repeat(400_000)))
        }
        assertAggOverflow(over)
    }

    @Test
    fun responsesWebSearchStateMapIsDistinctItemBounded() {
        // P2-7: one state entry per distinct web-search item, bounded by the
        // same per-attempt distinct-admission cap (checked BEFORE insert).
        val mapper = ResponsesMapper()
        for (i in 0 until MAX_TOOL_CALLS_PER_ATTEMPT) {
            mapper.mapPayload(
                """{"type":"response.web_search_call.in_progress","item_id":"ws$i"}""",
            )
        }
        val over = toolFailureOf {
            mapper.mapPayload(
                """{"type":"response.web_search_call.in_progress","item_id":"ws${MAX_TOOL_CALLS_PER_ATTEMPT}"}""",
            )
        }
        assertAggOverflow(over)
    }

    @Test
    fun responsesTextAndWebSearchKeysArePerKeyLengthBounded() {
        // S1 parity for the two Phase-2 String-keyed maps: a
        // frame-budget-sized item_id must throw typed non-retryable BEFORE
        // the key is retained (count caps alone would allow 64 x ~350KB).
        val giant = "k".repeat(10_000)
        val text = toolFailureOf {
            ResponsesMapper().mapPayload(
                """{"type":"response.output_text.delta","item_id":"$giant","output_index":0,"delta":"hi"}""",
            )
        }
        assertAggOverflow(text)
        val web = toolFailureOf {
            ResponsesMapper().mapPayload(
                """{"type":"response.web_search_call.in_progress","item_id":"$giant"}""",
            )
        }
        assertAggOverflow(web)
    }

    // ---- Phase 2: Anthropic synthetic-id write-back is retired on restart ----
    //
    // A blank-id tool block gets `call_`+8 at its stop and the id is written
    // back so a TOOL->TOOL restart retires that synthetic growth with the rest
    // of the old block. Without the write-back the 13 synthetic chars would
    // leak into the round total and a restart that exactly fills the budget
    // would fail a char early. The exact-ceiling delta below discriminates.

    @Test
    fun anthropicSyntheticIdWriteBackIsRetiredOnRestart() {
        val mapper = AnthropicMapper()
        mapper.mapPayload(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","name":"f"}}""",
        )
        val done = mapper.mapPayload("""{"type":"content_block_stop","index":0}""")
            .filterIsInstance<StreamEvent.ToolDone>()
            .single()
        assertTrue("synthetic id shape", done.id.startsWith("call_"))
        assertEquals(13, done.id.length)
        // Restart at the same index with a real id (2 chars) and name (1 char):
        // retire synthetic(13)+0+0, re-bill 3 -> total 3.
        mapper.mapPayload(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"c1","name":"f"}}""",
        )
        // id 2 + name 1 + args 349522 = 349525 (x3 = 1048575, exactly at the
        // 1 MiB per-call ceiling) and total 3 + 349522 = 349525. If the
        // synthetic 13 chars had leaked, the total would be 349538 and this
        // would throw.
        val atCeiling = mapper.mapPayload(
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"${"a".repeat(349_522)}"}}""",
        )
        assertEquals(1, atCeiling.filterIsInstance<StreamEvent.ToolDelta>().size)
        val closed = mapper.mapPayload("""{"type":"content_block_stop","index":0}""")
            .filterIsInstance<StreamEvent.ToolDone>()
            .single()
        assertEquals("c1", closed.id)
        assertEquals("f", closed.name)
    }

    // ---- S4: single-line exact-cap boundary (parser layer) ----
    //
    // Char->byte estimate is x3 against the 1 MiB line budget: a line of
    // 349525 chars (x3 = 1048575) still fits, one more char latches. The
    // "data: " prefix counts (6 chars), so 349519 value chars fit while 349520
    // latch. Mirrors requireToolAggBudgetExactBoundary at the SSE layer.

    @Test
    fun sseSingleLineExactCapBoundary() {
        val fit = SseFrameParser()
        val fitFrames = fit.feed("data: " + "x".repeat(349_519) + "\n\n") + fit.flush()
        assertFalse(fit.lineTooLong)
        assertFalse(fit.frameTooLong)
        assertEquals(1, fitFrames.size)
        assertEquals(349_519, fitFrames[0].data.length)
        val over = SseFrameParser()
        val overFrames = over.feed("data: " + "x".repeat(349_520) + "\n\n") + over.flush()
        assertTrue("349526-char line must latch lineTooLong", over.lineTooLong)
        assertFalse(over.frameTooLong)
        assertTrue(overFrames.isEmpty())
    }

    // ---- S4: Responses/Anthropic terminal-before-overlong-tail (Chat twin exists) ----
    //
    // Symmetric to ProviderTransportC1Test.chatCompletionsDoneBeforeOverlongLineKeepsSingleTerminal:
    // the protocol terminal arrives first, the pump stops reading, and the
    // over-long tail that follows on the wire never becomes a second terminal.
    // Exactly one Done, no Failed, one request.

    @Test
    fun responsesTerminalBeforeOverlongTailKeepsSingleDone() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val body = buildString {
                append("data: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\n")
                append("data: ")
                append("x".repeat(SseFrameParser.MAX_LINE_BYTES + 1024))
            }
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(body),
            )
            val config = ProviderConfig(
                id = "s4-responses-terminal",
                label = "test",
                baseUrl = server.url("/").toString().trimEnd('/'),
                protocol = ProviderProtocol.RESPONSES,
                apiKeyRef = "fake",
                http = ProviderHttpConfig(readTimeoutMs = TimeUnit.MINUTES.toMillis(5)),
            )
            val provider = ResponsesProvider(config, { "fake-key".toCharArray() }, OkHttpClient())
            val events = withTimeout(10_000) {
                provider.stream(
                    ChatRequest(model = "m", messages = listOf(ChatMessage("user", "hi"))),
                ).toList()
            }
            assertEquals(1, events.count { it is StreamEvent.Done })
            assertTrue("the overlong tail must not append a failure, got: $events", events.none { it is StreamEvent.Failed })
            assertEquals(StreamEvent.Done("stop"), events.last())
            assertEquals("one terminal is one request", 1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun anthropicTerminalBeforeOverlongTailKeepsSingleDone() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val body = buildString {
                append("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n")
                append("data: {\"type\":\"message_stop\"}\n\n")
                append("data: ")
                append("x".repeat(SseFrameParser.MAX_LINE_BYTES + 1024))
            }
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(body),
            )
            val config = ProviderConfig(
                id = "s4-anthropic-terminal",
                label = "test",
                baseUrl = server.url("/").toString().trimEnd('/'),
                protocol = ProviderProtocol.ANTHROPIC,
                apiKeyRef = "fake",
                http = ProviderHttpConfig(readTimeoutMs = TimeUnit.MINUTES.toMillis(5)),
            )
            val provider = AnthropicProvider(config, { "fake-key".toCharArray() }, OkHttpClient())
            val events = withTimeout(10_000) {
                provider.stream(
                    ChatRequest(model = "m", messages = listOf(ChatMessage("user", "hi"))),
                ).toList()
            }
            assertEquals(1, events.count { it is StreamEvent.Done })
            assertTrue("the overlong tail must not append a failure, got: $events", events.none { it is StreamEvent.Failed })
            assertEquals(StreamEvent.Done("end_turn"), events.last())
            assertEquals("one terminal is one request", 1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    // ---- S4: 64 x 20k poison path (triple only, no scope-name lock) ----
    //
    // 64 distinct tools x 20k-char fragments: per-call (20k x3 = 60k) and the
    // distinct count (64) both pass in isolation, but the whole-round total
    // (1.28M chars x3) exceeds the shared 1 MiB budget partway through. Which
    // guard fires first (total vs count) is an ordering detail; this locks
    // only the shared typed triple — message + non-retryable + code — so a
    // future guard reorder does not churn the test.

    @Test
    fun chatSixtyFourByTwentyKTripsTypedWithoutLockingScope() {
        val mapper = ChatCompletionsMapper()
        val frag = "y".repeat(20_000)
        var failure: ProviderFailure? = null
        for (i in 0 until MAX_TOOL_CALLS_PER_ATTEMPT) {
            try {
                mapper.mapPayload(
                    """{"choices":[{"delta":{"tool_calls":[{"index":$i,"id":"c$i","function":{"name":"f$i","arguments":"$frag"}}]}}]}""",
                )
            } catch (e: ProviderFailure) {
                if (failure == null) failure = e
                break
            }
        }
        assertTrue("64 x 20k-char tools must exceed the attempt budget", failure != null)
        assertAggOverflow(failure!!)
    }
}
