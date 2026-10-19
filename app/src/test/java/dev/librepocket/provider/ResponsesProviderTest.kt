package dev.librepocket.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Responses adapter tests (SPEC §3.2 / §3.5 / §10.3).
 *
 * The main path replays the sanitized real-capture-style
 * `sse/responses-recover.txt` fixture through [SseFrameParser] (odd chunking)
 * into [ResponsesMapper]: text recovery from an empty-`output`
 * `response.completed`, reasoning deltas, tool aggregation, and the opaque
 * (encrypted-reasoning) non-persistence rule.
 */
class ResponsesProviderTest {

    private fun testConfig() = ProviderConfig(
        id = "00000000-0000-0000-0000-000000000002",
        label = "test",
        baseUrl = "https://api.example.com/v1",
        protocol = ProviderProtocol.RESPONSES,
        apiKeyRef = "provider_key/00000000-0000-0000-0000-000000000002",
    )

    private fun replayFixture(): List<StreamEvent> {
        val bytes = javaClass.classLoader!!.getResourceAsStream("sse/responses-recover.txt")!!.readBytes()
        val parser = SseFrameParser()
        val mapper = ResponsesMapper()
        val out = ArrayList<StreamEvent>()
        var off = 0
        while (off < bytes.size) {
            val len = minOf(41, bytes.size - off)
            for (f in parser.feed(bytes, off, len)) {
                out += mapper.mapPayload(f.data)
                if (mapper.terminalReached) break
            }
            off += len
        }
        for (f in parser.flush()) {
            out += mapper.mapPayload(f.data)
        }
        out += mapper.finish()
        return out
    }

    @Test
    fun fixtureRecoversEmptyCompletedOutputFromStreamedDeltas() {
        val events = replayFixture()

        val texts = events.filterIsInstance<StreamEvent.TextDelta>()
        assertEquals(listOf("今天台北", "天氣晴。"), texts.map { it.delta })
        // (output_index, content_index) -> blockIndex mapping (§3.5).
        assertEquals(0, texts[0].blockIndex)

        val reasoning = events.filterIsInstance<StreamEvent.ReasoningDelta>()
        assertEquals(listOf("先查天氣再回覆"), reasoning.map { it.delta })
        assertEquals(1000, reasoning[0].blockIndex)

        val dones = events.filterIsInstance<StreamEvent.ToolDone>()
        assertEquals(1, dones.size)
        assertEquals("call_wx8fQ2zA", dones[0].id)
        assertEquals("get_weather", dones[0].name)
        assertEquals("{\"city\":\"Taipei\"}", dones[0].argumentsJson)

        val usage = events.filterIsInstance<StreamEvent.Usage>()
        assertEquals(listOf(StreamEvent.Usage(128, 64)), usage)

        // Empty `output` array: authoritative terminal still lands, with the
        // already-streamed deltas kept (P1 recover simplification).
        assertEquals(StreamEvent.Done("stop"), events.last())
    }

    @Test
    fun responseFailedIsFatal() {
        val mapper = ResponsesMapper()
        val events = mapper.mapPayload(
            """{"type":"response.failed","response":{"error":{"message":"invalid_api_key"}}}""",
        )
        val failed = events.filterIsInstance<StreamEvent.Failed>()
        assertEquals(1, failed.size)
        assertFalse(failed[0].retryable)
        assertTrue(mapper.terminalReached)
    }

    @Test
    fun responseIncompleteIsFatal() {
        // P2-6: incomplete_details is an object ({"reason":…}); the reason
        // rides the terminal message.
        val mapper = ResponsesMapper()
        val events = mapper.mapPayload(
            """{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"}}}""",
        )
        val failed = events.filterIsInstance<StreamEvent.Failed>()
        assertEquals(1, failed.size)
        assertFalse(failed[0].retryable)
        assertTrue(
            "reason must ride the terminal message: ${failed[0].message}",
            failed[0].message.contains("max_output_tokens"),
        )
    }

    @Test
    fun responseIncompleteLegacyStringShapeStillReadsReason() {
        // Old wire shape kept as fallback.
        val mapper = ResponsesMapper()
        val events = mapper.mapPayload(
            """{"type":"response.incomplete","response":{"incomplete_details":"max_output_tokens"}}""",
        )
        val failed = events.filterIsInstance<StreamEvent.Failed>().single()
        assertFalse(failed.retryable)
        assertTrue(failed.message.contains("max_output_tokens"))
    }

    // ---- P1-1: response.completed non-empty output reconciliation ----

    @Test
    fun completedNonEmptyOutputEmitsOnlyMissingTextSuffix() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"今天"}""",
        )
        val events = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"今天台北"}]}]}}""",
        )
        assertEquals(
            listOf(StreamEvent.TextDelta(0, 0, "台北")),
            events.filterIsInstance<StreamEvent.TextDelta>(),
        )
        assertEquals(StreamEvent.Done("stop"), events.last())
        assertTrue(mapper.terminalReached)
        // Latched terminal: no later frame can reopen the stream.
        assertEquals(
            emptyList<StreamEvent>(),
            mapper.mapPayload(
                """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"x"}""",
            ),
        )
    }

    @Test
    fun completedEqualTextEmitsNoDuplicate() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"abc"}""",
        )
        val events = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"abc"}]}]}}""",
        )
        assertTrue(events.filterIsInstance<StreamEvent.TextDelta>().isEmpty())
        assertEquals(listOf(StreamEvent.Done("stop")), events)
    }

    @Test
    fun completedTerminalOnlyTextEmitsFullText() {
        val mapper = ResponsesMapper()
        val events = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"只有終端"}]}]}}""",
        )
        assertEquals(
            listOf(StreamEvent.TextDelta(0, 0, "只有終端")),
            events.filterIsInstance<StreamEvent.TextDelta>(),
        )
        assertEquals(StreamEvent.Done("stop"), events.last())
    }

    @Test
    fun completedTextMismatchFailsTypedNonRetryableWithoutDone() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"abc"}""",
        )
        val events = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"abd"}]}]}}""",
        )
        val failed = events.filterIsInstance<StreamEvent.Failed>().single()
        assertEquals("RESPONSES_OUTPUT_MISMATCH", failed.message)
        assertFalse(failed.retryable)
        assertTrue("no Done after a mismatch", events.none { it is StreamEvent.Done })
        assertTrue(mapper.terminalReached)
        // The mismatch latches: no later frame can fold it into a success.
        assertEquals(
            emptyList<StreamEvent>(),
            mapper.mapPayload("""{"type":"response.completed","response":{"output":[]}}"""),
        )
        assertEquals(emptyList<StreamEvent>(), mapper.finish())
    }

    @Test
    fun completedTextMismatchFailsClosedAfterTrackingDisabled() {
        // Streamed text tracking is disabled past MAX_TURN_TEXT_CHARS; a
        // non-empty terminal text then has no trustworthy prefix and must
        // fail closed instead of being re-emitted wholesale. The terminal text
        // here EQUALS the streamed text plus "tail": if tracking were wrongly
        // kept, the mapper would emit only the "tail" suffix and then Done —
        // this assertion would fail. Only the disabled-tracking path yields
        // the typed mismatch, so the test discriminates the cap behavior from
        // an ordinary prefix contradiction.
        val mapper = ResponsesMapper()
        val chunk = "x".repeat(50_000)
        repeat(3) {
            mapper.mapPayload(
                """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"$chunk"}""",
            )
        }
        val events = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"${chunk.repeat(3)}tail"}]}]}}""",
        )
        assertEquals(
            listOf(StreamEvent.Failed("RESPONSES_OUTPUT_MISMATCH", retryable = false)),
            events,
        )
    }

    @Test
    fun completedReorderedOutputReconcilesTextByItemIdNotPosition() {
        // P1-1 identity: streamed blocks are keyed by item_id, so a terminal
        // output array that lists the same items in another order still
        // matches each item's own streamed prefix — no false mismatch and no
        // duplicated full text from comparing against the wrong item.
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_text.delta","item_id":"msg_a","output_index":0,"content_index":0,"delta":"alpha"}""",
        )
        mapper.mapPayload(
            """{"type":"response.output_text.delta","item_id":"msg_b","output_index":1,"content_index":0,"delta":"beta"}""",
        )
        val events = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"message","id":"msg_b","role":"assistant","content":[{"type":"output_text","text":"beta!"}]},{"type":"message","id":"msg_a","role":"assistant","content":[{"type":"output_text","text":"alpha!"}]}]}}""",
        )
        assertEquals(
            "each item contributes only its own missing suffix",
            listOf("!", "!"),
            events.filterIsInstance<StreamEvent.TextDelta>().map { it.delta },
        )
        assertEquals(StreamEvent.Done("stop"), events.last())
    }

    @Test
    fun completedOutputOmittingStreamedItemReconcilesOnlyPresentItems() {
        // A terminal array that omits one streamed item must not compare the
        // remaining text against the wrong prefix. The omitted item's text was
        // already streamed, so the terminal is a clean Done with no duplicate.
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_text.delta","item_id":"msg_a","output_index":0,"content_index":0,"delta":"alpha"}""",
        )
        mapper.mapPayload(
            """{"type":"response.output_text.delta","item_id":"msg_b","output_index":1,"content_index":0,"delta":"beta"}""",
        )
        val events = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"message","id":"msg_b","role":"assistant","content":[{"type":"output_text","text":"beta"}]}]}}""",
        )
        assertTrue(events.filterIsInstance<StreamEvent.TextDelta>().isEmpty())
        assertEquals(StreamEvent.Done("stop"), events.last())
    }

    @Test
    fun identityLessFunctionCallAggregatesByOutputIndex() {
        // A provider that OMITS item_id/id entirely (not merely sends "") must
        // not split the call across a random `added` bucket and dropped
        // argument frames (the historical random-bucket/drop shape). The
        // output_index fallback keeps added/delta/done on one bucket, so the
        // call aggregates to a single ToolDone with args.
        val mapper = ResponsesMapper()
        val added = mapper.mapPayload(
            """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","call_id":"call_1","name":"get_weather"}}""",
        )
        assertEquals(1, added.filterIsInstance<StreamEvent.ToolDelta>().size)
        mapper.mapPayload(
            """{"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\"city\":"}""",
        )
        mapper.mapPayload(
            """{"type":"response.function_call_arguments.delta","output_index":0,"delta":"\"Taipei\"}"}""",
        )
        val events = mapper.mapPayload(
            """{"type":"response.output_item.done","output_index":0,"item":{"type":"function_call","call_id":"call_1","name":"get_weather"}}""",
        )
        val done = events.filterIsInstance<StreamEvent.ToolDone>().single()
        assertEquals("call_1", done.id)
        assertEquals("get_weather", done.name)
        assertEquals("{\"city\":\"Taipei\"}", done.argumentsJson)
    }

    @Test
    fun mixedIdentityArgumentFramesJoinRegisteredBucket() {
        // Mixed identity: `added` carries item_id "it1", the argument/done
        // frames omit it but carry output_index 0. Without the correlation
        // map the delta opens a positional "output_0" bucket, so the drain
        // later emits a second (partial) ToolDone. All frames must join the
        // identified bucket: one ToolDone with the full args, no phantom.
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_item.added","output_index":0,"item_id":"it1","item":{"type":"function_call","call_id":"c1","name":"f"}}""",
        )
        mapper.mapPayload(
            """{"type":"response.function_call_arguments.delta","output_index":0,"delta":"{}"}""",
        )
        val done = mapper.mapPayload(
            """{"type":"response.output_item.done","output_index":0,"item":{"type":"function_call","call_id":"c1","name":"f"}}""",
        ).filterIsInstance<StreamEvent.ToolDone>().single()
        assertEquals("c1", done.id)
        assertEquals("{}", done.argumentsJson)
        val tail = mapper.mapPayload("""{"type":"response.completed","response":{"output":[]}}""")
        assertTrue(
            "no phantom positional bucket may drain after the identified call",
            tail.filterIsInstance<StreamEvent.ToolDone>().isEmpty(),
        )
    }

    @Test
    fun reverseMixedIdentityPromotesPositionalBucket() {
        // Reverse mixed identity: the earlier added/delta frames are id-less
        // (positional output_0 bucket), then the done frame introduces
        // item_id. The positional bucket (including its streamed args) must be
        // promoted to the declared id instead of orphaned: otherwise the done
        // emits an empty ToolDone and the drain emits a phantom partial one.
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","name":"f"}}""",
        )
        mapper.mapPayload(
            """{"type":"response.function_call_arguments.delta","output_index":0,"delta":"{}"}""",
        )
        val done = mapper.mapPayload(
            """{"type":"response.output_item.done","output_index":0,"item_id":"it1","item":{"type":"function_call","call_id":"c1","name":"f"}}""",
        ).filterIsInstance<StreamEvent.ToolDone>().single()
        assertEquals("c1", done.id)
        assertEquals("f", done.name)
        assertEquals("{}", done.argumentsJson)
        val tail = mapper.mapPayload("""{"type":"response.completed","response":{"output":[]}}""")
        assertTrue(
            "no orphaned positional bucket may drain a phantom ToolDone",
            tail.filterIsInstance<StreamEvent.ToolDone>().isEmpty(),
        )
        assertEquals(StreamEvent.Done("stop"), tail.last())
    }

    @Test
    fun completedPromotesIdlessPositionalBucketWithoutDoneFrame() {
        // Same reverse-mixed shape but with no `output_item.done` frame: the
        // id appears only on the terminal `response.completed` item. The
        // positional bucket (with its streamed args) must be adopted by the
        // declared id; otherwise the terminal closes an empty new bucket and
        // the drain emits a phantom partial ToolDone.
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","name":"f"}}""",
        )
        mapper.mapPayload(
            """{"type":"response.function_call_arguments.delta","output_index":0,"delta":"{}"}""",
        )
        val events = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"function_call","id":"it1","call_id":"c1","name":"f","arguments":"{}"}]}}""",
        )
        val done = events.filterIsInstance<StreamEvent.ToolDone>().single()
        assertEquals("c1", done.id)
        assertEquals("f", done.name)
        assertEquals("{}", done.argumentsJson)
        assertEquals(StreamEvent.Done("stop"), events.last())
    }

    @Test
    fun terminalIdLessMessageRejoinsStreamedTextByPosition() {
        // Mixed identity for text: the delta carries item_id, the terminal
        // message omits id. Without position correlation the terminal text
        // would be emitted in full (duplicate) instead of only its suffix.
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_text.delta","item_id":"msg_a","output_index":0,"content_index":0,"delta":"hello"}""",
        )
        val events = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"message","content":[{"type":"output_text","text":"hello!"}]}]}}""",
        )
        val suffixes = events.filterIsInstance<StreamEvent.TextDelta>().map { it.delta }
        assertEquals(listOf("!"), suffixes)
        assertEquals(StreamEvent.Done("stop"), events.last())
    }

    @Test
    fun functionCallFramesWithoutAnyIdentityFailTypedNonRetryable() {
        // No non-empty item_id/id AND no output_index: the call cannot be
        // paired with any bucket. Dropping such a frame would silently lose
        // its args and later drain an empty call; fail typed non-retryable
        // instead (never a retry of the deterministic malformed stream).
        val mapper = ResponsesMapper()
        try {
            mapper.mapPayload(
                """{"type":"response.output_item.added","item_id":"","item":{"type":"function_call","id":"","call_id":"c1","name":"f"}}""",
            )
            fail("identity-less added frame must fail closed")
        } catch (e: ProviderFailure) {
            assertEquals("RESPONSES_IDENTITY_MISSING", e.message)
            assertFalse(e.retryable)
        }
        try {
            mapper.mapPayload(
                """{"type":"response.function_call_arguments.delta","item_id":"","delta":"{}"}""",
            )
            fail("identity-less argument frame must fail closed")
        } catch (e: ProviderFailure) {
            assertEquals("RESPONSES_IDENTITY_MISSING", e.message)
            assertFalse(e.retryable)
        }
    }

    @Test
    fun completedTerminalOnlyFunctionCallAdmitsThroughDoneGuards() {
        val mapper = ResponsesMapper()
        val events = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"function_call","id":"fc_1","call_id":"call_1","name":"get_weather","arguments":"{\"city\":\"Taipei\"}"}]}}""",
        )
        val done = events.filterIsInstance<StreamEvent.ToolDone>().single()
        assertEquals("call_1", done.id)
        assertEquals("get_weather", done.name)
        assertEquals("{\"city\":\"Taipei\"}", done.argumentsJson)
        assertEquals(StreamEvent.Done("stop"), events.last())
    }

    @Test
    fun completedReconcilesBufferedCallWithAuthoritativeFullArgs() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_item.added","item_id":"it1","item":{"type":"function_call","call_id":"call_1","name":"f"}}""",
        )
        // Truncated delta; for a not-yet-emitted call the terminal full wins.
        mapper.mapPayload(
            """{"type":"response.function_call_arguments.delta","item_id":"it1","delta":"{\"a\":1"}""",
        )
        val events = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"function_call","item_id":"it1","id":"it1","call_id":"call_1","name":"f","arguments":"{\"a\":1}"}]}}""",
        )
        val done = events.filterIsInstance<StreamEvent.ToolDone>().single()
        assertEquals("call_1", done.id)
        assertEquals("{\"a\":1}", done.argumentsJson)
        assertEquals(StreamEvent.Done("stop"), events.last())
    }

    @Test
    fun completedSkipsAlreadyEmittedFunctionCall() {
        val mapper = ResponsesMapper()
        val streamed = mapper.mapPayload(
            """{"type":"response.output_item.done","item_id":"it1","item":{"type":"function_call","call_id":"call_1","name":"f","arguments":"{}"}}""",
        ).filterIsInstance<StreamEvent.ToolDone>().single()
        assertEquals("call_1", streamed.id)
        val events = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"function_call","item_id":"it1","id":"it1","call_id":"call_1","name":"f","arguments":"{}"}]}}""",
        )
        assertTrue(
            "an already-emitted pair is a replay, never a second ToolDone",
            events.filterIsInstance<StreamEvent.ToolDone>().isEmpty(),
        )
        assertEquals(listOf(StreamEvent.Done("stop")), events)
    }

    @Test
    fun completedTerminalOnlyReasoningMarksPresenceOnce() {
        val mapper = ResponsesMapper()
        val events = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"reasoning","id":"rs_1","content":[]},{"type":"reasoning","id":"rs_2","content":[]}]}}""",
        )
        assertEquals(1, events.filterIsInstance<StreamEvent.ReasoningPresent>().size)
        assertEquals(StreamEvent.Done("stop"), events.last())
    }

    @Test
    fun completedReasoningDoesNotDuplicateStreamedPresence() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_item.added","item_id":"rs_1","item":{"type":"reasoning","id":"rs_1"}}""",
        )
        val events = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"reasoning","id":"rs_1","content":[]}]}}""",
        )
        assertTrue(events.none { it is StreamEvent.ReasoningPresent })
        assertEquals(listOf(StreamEvent.Done("stop")), events)
    }

    // ---- P1-2: response.function_call_arguments.done ----

    @Test
    fun argumentsDoneReplacesTruncatedDeltasWithFullArgs() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_item.added","item_id":"it1","item":{"type":"function_call","call_id":"c1","name":"f"}}""",
        )
        mapper.mapPayload(
            """{"type":"response.function_call_arguments.delta","item_id":"it1","delta":"{\"city\":\"Tai"}""",
        )
        val argumentsDone = mapper.mapPayload(
            """{"type":"response.function_call_arguments.done","item_id":"it1","arguments":"{\"city\":\"Taipei\"}"}""",
        )
        assertTrue("arguments.done emits no ToolDone of its own", argumentsDone.isEmpty())
        val done = mapper.mapPayload(
            """{"type":"response.output_item.done","item_id":"it1","item":{"type":"function_call","call_id":"c1","name":"f","arguments":"{\"city\":\"Taipei\"}"}}""",
        ).filterIsInstance<StreamEvent.ToolDone>().single()
        assertEquals("{\"city\":\"Taipei\"}", done.argumentsJson)
    }

    @Test
    fun argumentsDoneOnlyCallIsDrainedAtCompleted() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.function_call_arguments.done","item_id":"it1","arguments":"{\"a\":1}"}""",
        )
        val events = mapper.mapPayload("""{"type":"response.completed","response":{"output":[]}}""")
        val done = events.filterIsInstance<StreamEvent.ToolDone>().single()
        assertEquals("{\"a\":1}", done.argumentsJson)
        assertTrue(done.id.startsWith("call_"))
        assertEquals(StreamEvent.Done("stop"), events.last())
    }

    @Test
    fun deltaAfterArgumentsDoneIsIgnoredAndFullNeverClobbered() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.function_call_arguments.done","item_id":"it1","arguments":"{\"final\":1}"}""",
        )
        assertEquals(
            emptyList<StreamEvent>(),
            mapper.mapPayload(
                """{"type":"response.function_call_arguments.delta","item_id":"it1","delta":"GARBAGE"}""",
            ),
        )
        val done = mapper.mapPayload(
            """{"type":"response.output_item.done","item_id":"it1","item":{"type":"function_call","call_id":"c1","name":"f","arguments":"{\"other\":2}"}}""",
        ).filterIsInstance<StreamEvent.ToolDone>().single()
        assertEquals("{\"final\":1}", done.argumentsJson)
    }

    // ---- P1-4 / P2-8: structured errors + usage on failure terminals ----

    @Test
    fun topLevelErrorInvalidApiKeyIsFatalAndTerminal() {
        val mapper = ResponsesMapper()
        val events = mapper.mapPayload(
            """{"type":"error","code":"invalid_api_key","message":"bad sk-testSECRET1234567890"}""",
        )
        val failed = events.filterIsInstance<StreamEvent.Failed>().single()
        assertFalse(failed.retryable)
        assertFalse("key material leaked: ${failed.message}", failed.message.contains("sk-testSECRET1234567890"))
        assertTrue(mapper.terminalReached)
        // Terminal latched: the pump stops and no later frame projects.
        assertEquals(
            emptyList<StreamEvent>(),
            mapper.mapPayload("""{"type":"response.completed","response":{"output":[]}}"""),
        )
        assertEquals(emptyList<StreamEvent>(), mapper.finish())
    }

    @Test
    fun topLevelErrorStructuredCodeOverloadIsRetryable() {
        // Top-level Responses error envelopes carry `code`; a retryable
        // structured token keeps the terminal retryable (unknown would too,
        // but a fatal token in the same slot must not be softened).
        val mapper = ResponsesMapper()
        val failed = mapper.mapPayload(
            """{"type":"error","code":"server_error","message":"busy"}""",
        ).filterIsInstance<StreamEvent.Failed>().single()
        assertTrue(failed.retryable)
    }

    @Test
    fun nestedErrorStructuredTypeOverloadIsRetryable() {
        val mapper = ResponsesMapper()
        val failed = mapper.mapPayload(
            """{"type":"error","error":{"type":"overloaded_error","message":"busy"}}""",
        ).filterIsInstance<StreamEvent.Failed>().single()
        assertTrue(failed.retryable)
    }

    @Test
    fun responseFailedStructuredInvalidApiKeyIsFatal() {
        val mapper = ResponsesMapper()
        val failed = mapper.mapPayload(
            """{"type":"response.failed","response":{"error":{"code":"invalid_api_key","message":"key rejected"}}}""",
        ).filterIsInstance<StreamEvent.Failed>().single()
        assertFalse(failed.retryable)
    }

    @Test
    fun responseFailedStructuredQuotaIsFatal() {
        val mapper = ResponsesMapper()
        val failed = mapper.mapPayload(
            """{"type":"response.failed","response":{"error":{"code":"insufficient_quota","message":"out of credits"}}}""",
        ).filterIsInstance<StreamEvent.Failed>().single()
        assertFalse(failed.retryable)
    }

    @Test
    fun responseFailedEmitsUsageBeforeTerminal() {
        val mapper = ResponsesMapper()
        val events = mapper.mapPayload(
            """{"type":"response.failed","response":{"error":{"message":"boom"},"usage":{"input_tokens":9,"output_tokens":3}}}""",
        )
        assertEquals(2, events.size)
        assertEquals(StreamEvent.Usage(9, 3), events.first())
        assertTrue(events.last() is StreamEvent.Failed)
    }

    @Test
    fun responseIncompleteEmitsUsageBeforeTerminal() {
        val mapper = ResponsesMapper()
        val events = mapper.mapPayload(
            """{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"},"usage":{"input_tokens":10,"output_tokens":20}}}""",
        )
        assertEquals(2, events.size)
        assertEquals(StreamEvent.Usage(10, 20), events.first())
        assertTrue(events.last() is StreamEvent.Failed)
    }

    @Test
    fun truncatedStreamFailsRetryable() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"half"}""",
        )
        val terminal = mapper.finish().last()
        assertEquals(StreamEvent.Failed("SSE_TRUNCATED", retryable = true), terminal)
    }

    @Test
    fun emptyOutputTextDeltaFrameYieldsNoEvent() {
        // Empty-delta suppression (parity with ChatCompletions): an empty
        // frame yields NO TextDelta — an empty increment must never pose as
        // final-answer evidence downstream (continuation completeness,
        // maintainer P1). A following non-empty frame still lands (the guard
        // only mutes the empty frame, not the arm).
        val mapper = ResponsesMapper()
        assertEquals(
            emptyList<StreamEvent>(),
            mapper.mapPayload(
                """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":""}""",
            ),
        )
        val real = mapper.mapPayload(
            """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"real"}""",
        )
        assertEquals(listOf(StreamEvent.TextDelta(0, 0, "real")), real)
    }

    @Test
    fun emptyReasoningDeltaFrameYieldsNoEvent() {
        // Same suppression on the reasoning arm: an empty reasoning frame is
        // dropped (zero payload must not flip the downstream reasoning-source
        // flag either).
        val mapper = ResponsesMapper()
        assertEquals(
            emptyList<StreamEvent>(),
            mapper.mapPayload(
                """{"type":"response.reasoning_summary_text.delta","output_index":1,"content_index":0,"delta":""}""",
            ),
        )
        val real = mapper.mapPayload(
            """{"type":"response.reasoning_summary_text.delta","output_index":1,"content_index":0,"delta":"summarized"}""",
        )
        assertEquals(1, real.size)
        assertEquals(StreamEvent.ReasoningDelta(0, 1000, "summarized"), real.single())
    }

    @Test
    fun opaqueFieldsNeverSurfaceAsEvents() {
        val events = replayFixture()
        for (e in events) {
            assertFalse("opaque leak: $e", e.toString().contains("OPAQUE-SECRET-XYZ"))
        }
    }

    @Test
    fun reasoningOutputItemMarksPresenceWithoutDelta() {
        val mapper = ResponsesMapper()
        val added = mapper.mapPayload(
            """{"type":"response.output_item.added","output_index":1,"item_id":"item_rs1","item":{"type":"reasoning","id":"rs_1"}}""",
        )
        assertEquals(listOf(StreamEvent.ReasoningPresent), added)
        val done = mapper.mapPayload(
            """{"type":"response.output_item.done","output_index":1,"item_id":"item_rs1","item":{"type":"reasoning","id":"rs_1"}}""",
        )
        assertEquals(listOf(StreamEvent.ReasoningPresent), done)
    }

    @Test
    fun reasoningPresenceMarkerCarriesNoPayload() {
        val mapper = ResponsesMapper()
        val events = mapper.mapPayload(
            """{"type":"response.output_item.added","output_index":1,"item_id":"item_rs1","item":{"type":"reasoning","id":"rs_1","summary":[],"content":[]}}""",
        )
        assertEquals(1, events.size)
        assertTrue(events.single() is StreamEvent.ReasoningPresent)
    }

    @Test
    fun functionCallItemsDoNotMarkReasoning() {
        val mapper = ResponsesMapper()
        val added = mapper.mapPayload(
            """{"type":"response.output_item.added","output_index":2,"item_id":"item_wx8f","item":{"type":"function_call","id":"","call_id":"call_x","name":"get_weather"}}""",
        )
        assertTrue(added.none { it is StreamEvent.ReasoningPresent })
    }

    @Test
    fun requestBodyIsUnstoredStreamWithoutPreviousResponse() {
        val provider = ResponsesProvider(testConfig(), { null })
        val body = provider.buildBody(
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage("user", "hi")),
            ),
        )
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"store\":false"))
        assertFalse(body.contains("previous_response_id"))
        assertTrue(body.contains("\"type\":\"message\""))
    }

    @Test
    fun endpointJoining() {
        val provider = ResponsesProvider(testConfig(), { null })
        assertEquals("https://h/v1/responses", provider.endpoint("https://h/v1"))
        assertEquals("https://h/v1/responses", provider.endpoint("https://h/v1/"))
        assertEquals("https://h/v1/responses", provider.endpoint("https://h/v1/responses"))
    }
}
