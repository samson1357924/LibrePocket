package dev.librepocket.provider

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
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

    private fun testConfig(baseUrl: String = "https://api.example.com/v1") = ProviderConfig(
        id = "00000000-0000-0000-0000-000000000002",
        label = "test",
        baseUrl = baseUrl,
        protocol = ProviderProtocol.RESPONSES,
        apiKeyRef = "provider_key/00000000-0000-0000-0000-000000000002",
    )

    /**
     * Test transport that delivers one full SSE body and then fails the next
     * read with an [IOException]. This lets the pump process and latch the
     * conflict before the transport dies — the case that must become the
     * non-retryable output mismatch instead of a retryable SSE_TRANSPORT.
     */
    private fun failAfterBodyClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val response = chain.proceed(chain.request())
                val original = response.body
                val contentType = original.contentType()
                val buffer = Buffer().write(original.bytes())
                var delivered = false
                val source = object : ForwardingSource(buffer) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (delivered) throw IOException("injected transport failure")
                        val read = super.read(sink, byteCount)
                        if (buffer.size == 0L) delivered = true
                        return read
                    }
                }.buffer()
                val body = object : ResponseBody() {
                    override fun contentType(): MediaType? = contentType
                    override fun contentLength(): Long = -1L
                    override fun source(): BufferedSource = source
                }
                return response.newBuilder().body(body).build()
            }
        })
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

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
    fun mixedTextIdentityMidStreamPromotesOneBlock() {
        // An id-less delta followed by an id-carrying delta for the same block
        // must share one buffer: "Hel" + "lo" = "Hello", so the terminal item
        // emits no duplicate suffix and no false mismatch.
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"Hel"}""",
        )
        mapper.mapPayload(
            """{"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"lo"}""",
        )
        val events = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"message","id":"msg_1","role":"assistant","content":[{"type":"output_text","text":"Hello"}]}]}}""",
        )
        assertTrue(
            "terminal must not duplicate the merged prefix: $events",
            events.filterIsInstance<StreamEvent.TextDelta>().isEmpty(),
        )
        assertEquals(StreamEvent.Done("stop"), events.last())
    }

    @Test
    fun textIdentityDisappearsMidStreamKeepsIdBuffer() {
        // Reverse mixed identity: the id appears first, then an id-less delta
        // must append to the sticky id buffer instead of creating a positional
        // twin that later re-emits "lo" after the terminal suffix.
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"Hel"}""",
        )
        mapper.mapPayload(
            """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"lo"}""",
        )
        val events = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"message","id":"msg_1","role":"assistant","content":[{"type":"output_text","text":"Hello"}]}]}}""",
        )
        assertTrue(
            "terminal must not duplicate the id buffer: $events",
            events.filterIsInstance<StreamEvent.TextDelta>().isEmpty(),
        )
        assertEquals(StreamEvent.Done("stop"), events.last())
    }

    @Test
    fun duplicateTerminalMessageItemEmitsSuffixOnce() {
        // A duplicated terminal item is a replay: advancing the buffer after
        // the first suffix makes the second item a no-op, not "lo" twice.
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"Hel"}""",
        )
        val events = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"message","id":"msg_1","role":"assistant","content":[{"type":"output_text","text":"Hello"}]},{"type":"message","id":"msg_1","role":"assistant","content":[{"type":"output_text","text":"Hello"}]}]}}""",
        )
        assertEquals(listOf("lo"), events.filterIsInstance<StreamEvent.TextDelta>().map { it.delta })
        assertEquals(StreamEvent.Done("stop"), events.last())
    }

    @Test
    fun reorderedTerminalTextSuffixUsesStreamedBlockIndex() {
        // A reordered terminal array must not relabel the suffix with the
        // array position: the streamed block identity is output_index 5.
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_text.delta","item_id":"msg_a","output_index":5,"content_index":0,"delta":"hello"}""",
        )
        val events = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"message","id":"msg_a","role":"assistant","content":[{"type":"output_text","text":"hello!"}]}]}}""",
        )
        assertEquals(
            listOf(StreamEvent.TextDelta(0, 5_000, "!")),
            events.filterIsInstance<StreamEvent.TextDelta>(),
        )
        assertEquals(StreamEvent.Done("stop"), events.last())
    }

    @Test
    fun emptyItemIdDoesNotAliasDistinctOutputIndexes() {
        // Empty item_id is the id-less shape. It must stay positional per
        // output_index, not collapse every block onto output_0.
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"alpha"}""",
        )
        mapper.mapPayload(
            """{"type":"response.output_text.delta","output_index":1,"content_index":0,"item_id":"","delta":"beta"}""",
        )
        val events = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"alpha"}]},{"type":"message","role":"assistant","content":[{"type":"output_text","text":"beta"}]}]}}""",
        )
        assertTrue(
            "distinct positional blocks must not merge: $events",
            events.filterIsInstance<StreamEvent.TextDelta>().isEmpty(),
        )
        assertEquals(StreamEvent.Done("stop"), events.last())
    }

    @Test
    fun distinctItemIdsAtOnePositionFailClosed() {
        // msg_b at the same position must not append into msg_a's buffer and
        // then be accepted by position; the identity conflict is non-retryable.
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_text.delta","item_id":"msg_a","output_index":0,"content_index":0,"delta":"Hel"}""",
        )
        mapper.mapPayload(
            """{"type":"response.output_text.delta","item_id":"msg_b","output_index":0,"content_index":0,"delta":"lo"}""",
        )
        val events = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"message","id":"msg_b","role":"assistant","content":[{"type":"output_text","text":"Hello"}]}]}}""",
        )
        val failed = events.filterIsInstance<StreamEvent.Failed>().single()
        assertEquals("RESPONSES_OUTPUT_MISMATCH", failed.message)
        assertFalse(failed.retryable)
        assertTrue(events.none { it is StreamEvent.Done })
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
    fun deltaAfterArgumentsDoneIsIgnoredAndContradictoryDoneFailsClosed() {
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
        // The old contract silently kept argsFinal and ignored a contradictory
        // output_item.done full. That executes stale args; the authoritative
        // full now fails closed instead of being clobbered or ignored.
        assertOnlyOutputMismatch(
            mapper.mapPayload(
                """{"type":"response.output_item.done","item_id":"it1","item":{"type":"function_call","call_id":"c1","name":"f","arguments":"{\"other\":2}"}}""",
            ),
        )
        assertTrue(mapper.terminalReached)
    }

    @Test
    fun equivalentWhitespaceArgumentsDoneThenOutputItemDoneEmitsOnce() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.function_call_arguments.done","item_id":"it1","arguments":"{\"b\": [2, 3], \"a\": 1}"}""",
        )
        // Same JSON semantics, different whitespace/key order: not a conflict.
        val done = mapper.mapPayload(
            """{"type":"response.output_item.done","item_id":"it1","item":{"type":"function_call","call_id":"c1","name":"f","arguments":"{\"a\":1,\"b\":[2,3]}"}}""",
        ).filterIsInstance<StreamEvent.ToolDone>()
        assertEquals(1, done.size)
        assertEquals("c1", done.single().id)
        assertTrue(mapper.mapPayload("""{"type":"response.completed","response":{"output":[]}}""").last() is StreamEvent.Done)
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

    /** Exact raw id length for an encoded text-block key of [encodedLength]. */
    private fun idForEncodedKeyLength(encodedLength: Int, contentIndex: Int = 0): String {
        val fixed = "id:".length + "#$contentIndex".length
        return "m".repeat(encodedLength - fixed)
    }

    private fun assertOnlyOutputMismatch(events: List<StreamEvent>) {
        assertEquals(
            listOf(StreamEvent.Failed("RESPONSES_OUTPUT_MISMATCH", retryable = false)),
            events,
        )
        assertTrue("no Done after a mismatch", events.none { it is StreamEvent.Done })
        assertTrue("no ToolDone after a mismatch", events.none { it is StreamEvent.ToolDone })
    }

    /** Two distinct streamed ids at one position latch the sticky text conflict. */
    private fun ResponsesMapper.latchTextConflict() {
        mapPayload(
            """{"type":"response.output_text.delta","item_id":"msg_a","output_index":0,"content_index":0,"delta":"Hel"}""",
        )
        mapPayload(
            """{"type":"response.output_text.delta","item_id":"msg_b","output_index":0,"content_index":0,"delta":"lo"}""",
        )
    }

    @Test
    fun textConflictThenEmptyCompletedFailsClosed() {
        val mapper = ResponsesMapper()
        mapper.latchTextConflict()
        assertOnlyOutputMismatch(
            mapper.mapPayload("""{"type":"response.completed","response":{"output":[]}}"""),
        )
        assertTrue(mapper.terminalReached)
        assertEquals(emptyList<StreamEvent>(), mapper.finish())
    }

    @Test
    fun textConflictThenCompletedWithoutOutputFailsClosed() {
        val mapper = ResponsesMapper()
        mapper.latchTextConflict()
        assertOnlyOutputMismatch(
            mapper.mapPayload("""{"type":"response.completed","response":{}}"""),
        )
        assertTrue(mapper.terminalReached)
    }

    @Test
    fun textConflictThenCompletedWithOnlyReasoningAndCallFailsClosed() {
        val mapper = ResponsesMapper()
        mapper.latchTextConflict()
        assertOnlyOutputMismatch(
            mapper.mapPayload(
                """{"type":"response.completed","response":{"output":[{"type":"reasoning","id":"rs_1","content":[]},{"type":"function_call","id":"fc_1","call_id":"c1","name":"f","arguments":"{}"}]}}""",
            ),
        )
    }

    @Test
    fun textConflictStaysStickyAfterTrackingIsDisabled() {
        val mapper = ResponsesMapper()
        mapper.latchTextConflict()
        // More than MAX_TURN_TEXT_CHARS of legal streamed text disables text
        // tracking. Disabling must NOT clear the already-latched conflict.
        val chunk = "x".repeat(50_000)
        repeat(3) {
            assertEquals(
                emptyList<StreamEvent>(),
                mapper.mapPayload(
                    """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"$chunk"}""",
                ),
            )
        }
        assertOnlyOutputMismatch(
            // Empty authoritative output: without the sticky conflict this
            // would be a clean Done, so it discriminates disable-clearing.
            mapper.mapPayload("""{"type":"response.completed","response":{"output":[]}}"""),
        )
    }

    @Test
    fun textConflictAtEofFailsClosedWithoutTruncationRetry() {
        val mapper = ResponsesMapper()
        mapper.latchTextConflict()
        assertEquals(
            listOf(StreamEvent.Failed("RESPONSES_OUTPUT_MISMATCH", retryable = false)),
            mapper.finish(),
        )
    }

    @Test
    fun textConflictDominatesRetryableProviderFailure() {
        val mapper = ResponsesMapper()
        mapper.latchTextConflict()
        assertOnlyOutputMismatch(
            mapper.mapPayload(
                """{"type":"response.failed","response":{"error":{"type":"server_error","message":"busy"},"usage":{"input_tokens":1,"output_tokens":2}}}""",
            ),
        )
    }

    @Test
    fun textConflictDominatesIncompleteAndTopLevelError() {
        for (terminal in listOf(
            """{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"}}}""",
            """{"type":"error","code":"server_error","message":"busy"}""",
        )) {
            val mapper = ResponsesMapper()
            mapper.latchTextConflict()
            assertOnlyOutputMismatch(mapper.mapPayload(terminal))
        }
    }

    @Test
    fun reorderedIdlessTerminalCallsPairByCallIdNotPosition() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","call_id":"c1","name":"f1"}}""",
        )
        mapper.mapPayload(
            """{"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\"a\":1}"}""",
        )
        mapper.mapPayload(
            """{"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","call_id":"c2","name":"f2"}}""",
        )
        mapper.mapPayload(
            """{"type":"response.function_call_arguments.delta","output_index":1,"delta":"{\"b\":2}"}""",
        )
        // Terminal array is reordered and omits arguments: only call_id can
        // pair each terminal item with its streamed args.
        val events = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"function_call","call_id":"c2","name":"f2"},{"type":"function_call","call_id":"c1","name":"f1"}]}}""",
        )
        val dones = events.filterIsInstance<StreamEvent.ToolDone>()
        assertEquals(listOf("c2", "c1"), dones.map { it.id })
        assertEquals(listOf("f2", "f1"), dones.map { it.name })
        assertEquals(listOf("{\"b\":2}", "{\"a\":1}"), dones.map { it.argumentsJson })
        assertEquals(StreamEvent.Done("stop"), events.last())
    }

    @Test
    fun distinctDeclaredToolItemIdsAtOnePositionFailClosed() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_item.added","item_id":"it1","output_index":0,"item":{"type":"function_call","call_id":"c1","name":"f"}}""",
        )
        assertEquals(
            emptyList<StreamEvent>(),
            mapper.mapPayload(
                """{"type":"response.output_item.added","item_id":"it2","output_index":0,"item":{"type":"function_call","call_id":"c2","name":"g"}}""",
            ),
        )
        assertOnlyOutputMismatch(
            mapper.mapPayload("""{"type":"response.completed","response":{"output":[]}}"""),
        )
    }

    @Test
    fun terminalMessageIdDifferentFromStreamedIdFailsClosed() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_text.delta","item_id":"msg_a","output_index":0,"content_index":0,"delta":"Hel"}""",
        )
        // msg_b was never streamed, but the position is owned by msg_a.
        assertOnlyOutputMismatch(
            mapper.mapPayload(
                """{"type":"response.completed","response":{"output":[{"type":"message","id":"msg_b","role":"assistant","content":[{"type":"output_text","text":"Hello"}]}]}}""",
            ),
        )
    }

    @Test
    fun terminalMessageIdAtEncodedKeyCapIsAccepted() {
        val id = idForEncodedKeyLength(MAX_TOOL_ID_CHARS)
        // Encoded boundary is exact: "id:" + id + "#0" == MAX_TOOL_ID_CHARS.
        assertEquals(MAX_TOOL_ID_CHARS, "id:$id#0".length)
        val mapper = ResponsesMapper()
        val events = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"message","id":"$id","role":"assistant","content":[{"type":"output_text","text":"ok"}]}]}}""",
        )
        assertEquals(
            listOf(StreamEvent.TextDelta(0, 0, "ok")),
            events.filterIsInstance<StreamEvent.TextDelta>(),
        )
        assertEquals(StreamEvent.Done("stop"), events.last())
    }

    @Test
    fun terminalMessageIdOverEncodedKeyCapFailsTypedNonRetryable() {
        val id = idForEncodedKeyLength(MAX_TOOL_ID_CHARS + 1)
        // One char over the encoded-key boundary, not a wildly oversized id.
        assertEquals(MAX_TOOL_ID_CHARS + 1, "id:$id#0".length)
        val mapper = ResponsesMapper()
        try {
            mapper.mapPayload(
                """{"type":"response.completed","response":{"output":[{"type":"message","id":"$id","role":"assistant","content":[{"type":"output_text","text":"leak"}]}]}}""",
            )
            fail("over-cap terminal message id must fail closed")
        } catch (e: ProviderFailure) {
            assertFalse(e.retryable)
            assertEquals(TOOL_ARGS_TOO_LARGE, e.message)
            assertEquals(ProviderFailureCode.AGG_TOO_LARGE, e.code)
        }
        val tail = mapper.finish()
        assertTrue(tail.none { it is StreamEvent.TextDelta })
        assertTrue(tail.none { it is StreamEvent.ToolDone })
        assertTrue(tail.none { it is StreamEvent.Done })
    }

    @Test
    fun keylessTerminalFunctionCallWithPayloadFailsClosed() {
        assertOnlyOutputMismatch(
            ResponsesMapper().mapPayload(
                """{"type":"response.completed","response":{"output":[{"type":"function_call","name":"f","arguments":"{}"}]}}""",
            ),
        )
    }

    @Test
    fun outputTextDoneEmitsOnlyMissingSuffixAndCompletedDoesNotDuplicate() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"Hel"}""",
        )
        val done = mapper.mapPayload(
            """{"type":"response.output_text.done","output_index":0,"content_index":0,"text":"Hello"}""",
        )
        assertEquals(listOf(StreamEvent.TextDelta(0, 0, "lo")), done)
        val completed = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello"}]}]}}""",
        )
        assertTrue(
            "completed must not repeat the consumed suffix: $completed",
            completed.filterIsInstance<StreamEvent.TextDelta>().isEmpty(),
        )
        assertEquals(StreamEvent.Done("stop"), completed.last())
    }

    @Test
    fun contentPartDoneEmitsOnlyMissingSuffixAndCompletedDoesNotDuplicate() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"Hel"}""",
        )
        val done = mapper.mapPayload(
            """{"type":"response.content_part.done","output_index":0,"content_index":0,"part":{"type":"output_text","text":"Hello!"}}""",
        )
        assertEquals(listOf(StreamEvent.TextDelta(0, 0, "lo!")), done)
        val completed = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello!"}]}]}}""",
        )
        assertTrue(completed.filterIsInstance<StreamEvent.TextDelta>().isEmpty())
        assertEquals(StreamEvent.Done("stop"), completed.last())
    }

    @Test
    fun outputTextDoneWhenTrackingDisabledFailsClosed() {
        val mapper = ResponsesMapper()
        val chunk = "x".repeat(50_000)
        repeat(3) {
            mapper.mapPayload(
                """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"$chunk"}""",
            )
        }
        val events = mapper.mapPayload(
            """{"type":"response.output_text.done","output_index":0,"content_index":0,"text":"${chunk.repeat(3)}tail"}""",
        )
        assertOnlyOutputMismatch(events)
        assertTrue(mapper.terminalReached)
    }

    @Test
    fun outputItemDoneMessageUsesSameSuffixBuffer() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"Hel"}""",
        )
        val done = mapper.mapPayload(
            """{"type":"response.output_item.done","output_index":0,"item":{"type":"message","id":"msg_1","role":"assistant","content":[{"type":"output_text","text":"Hello"}]}}""",
        )
        assertEquals(listOf(StreamEvent.TextDelta(0, 0, "lo")), done)
        val completed = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"message","id":"msg_1","role":"assistant","content":[{"type":"output_text","text":"Hello"}]}]}}""",
        )
        assertTrue(completed.filterIsInstance<StreamEvent.TextDelta>().isEmpty())
        assertEquals(StreamEvent.Done("stop"), completed.last())
    }

    // ---- Phase 1B-A: stream-scoped done reconciliation / closure ----

    @Test
    fun doneWithIdThenIdlessCompletedEmitsTextOnce() {
        val completed =
            """{"type":"response.completed","response":{"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello"}]}]}}"""
        val dones = listOf(
            """{"type":"response.output_text.done","item_id":"msg_1","output_index":0,"content_index":0,"text":"Hello"}""",
            """{"type":"response.content_part.done","item_id":"msg_1","output_index":0,"content_index":0,"part":{"type":"output_text","text":"Hello"}}""",
            """{"type":"response.output_item.done","output_index":0,"item":{"type":"message","id":"msg_1","role":"assistant","content":[{"type":"output_text","text":"Hello"}]}}""",
        )
        for ((i, done) in dones.withIndex()) {
            val mapper = ResponsesMapper()
            val events = mapper.mapPayload(done) + mapper.mapPayload(completed)
            assertEquals(
                "done variant $i must emit its text exactly once",
                listOf("Hello"),
                events.filterIsInstance<StreamEvent.TextDelta>().map { it.delta },
            )
            assertEquals("done variant $i must still finish", StreamEvent.Done("stop"), events.last())
            assertTrue(mapper.terminalReached)
        }
    }

    @Test
    fun duplicateIdlessIndexlessDoneEmitsFullTextOnce() {
        val mapper = ResponsesMapper()
        val done = """{"type":"response.output_text.done","text":"Hello"}"""
        assertEquals(
            listOf(StreamEvent.TextDelta(0, 0, "Hello")),
            mapper.mapPayload(done),
        )
        assertEquals(emptyList<StreamEvent>(), mapper.mapPayload(done))
        assertEquals(
            listOf(StreamEvent.Done("stop")),
            mapper.mapPayload("""{"type":"response.completed","response":{"output":[]}}"""),
        )
        assertTrue(mapper.terminalReached)
    }

    @Test
    fun identifiedDoneThenCompletedWithDifferentIdFailsClosed() {
        val mapper = ResponsesMapper()
        assertEquals(
            listOf(StreamEvent.TextDelta(0, 0, "Hello")),
            mapper.mapPayload(
                """{"type":"response.output_text.done","item_id":"msg_a","output_index":0,"content_index":0,"text":"Hello"}""",
            ),
        )
        assertOnlyOutputMismatch(
            mapper.mapPayload(
                """{"type":"response.completed","response":{"output":[{"type":"message","id":"msg_b","role":"assistant","content":[{"type":"output_text","text":"Hello"}]}]}}""",
            ),
        )
        assertTrue(mapper.terminalReached)
        assertEquals(emptyList<StreamEvent>(), mapper.finish())
    }

    @Test
    fun idlessDeltaThenIdentifiedDonePromotesThenTerminalIdChurnFailsClosed() {
        val mapper = ResponsesMapper()
        assertEquals(
            listOf(StreamEvent.TextDelta(0, 0, "Hel")),
            mapper.mapPayload(
                """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"Hel"}""",
            ),
        )
        assertEquals(
            listOf(StreamEvent.TextDelta(0, 0, "lo")),
            mapper.mapPayload(
                """{"type":"response.output_text.done","item_id":"msg_a","output_index":0,"content_index":0,"text":"Hello"}""",
            ),
        )
        assertOnlyOutputMismatch(
            mapper.mapPayload(
                """{"type":"response.completed","response":{"output":[{"type":"message","id":"msg_b","role":"assistant","content":[{"type":"output_text","text":"Hello!"}]}]}}""",
            ),
        )
        assertTrue(mapper.terminalReached)
    }

    @Test
    fun latchedConflictThenPayloadlessDoneProjectsOnlyOneMismatch() {
        val payloadlessDones = listOf(
            """{"type":"response.output_text.done","output_index":0,"content_index":0}""",
            """{"type":"response.content_part.done","output_index":0,"content_index":0}""",
            """{"type":"response.output_item.done","output_index":0}""",
        )
        for (done in payloadlessDones) {
            val mapper = ResponsesMapper()
            mapper.latchTextConflict()
            assertOnlyOutputMismatch(mapper.mapPayload(done))
            assertTrue("conflict projection must latch: $done", mapper.terminalReached)
            assertEquals(
                emptyList<StreamEvent>(),
                mapper.mapPayload("""{"type":"response.completed","response":{"output":[]}}"""),
            )
            assertEquals(emptyList<StreamEvent>(), mapper.finish())
        }
    }

    @Test
    fun latchedConflictSuppressesOverlongDeltaWithoutAggOverflow() {
        val mapper = ResponsesMapper()
        mapper.latchTextConflict()
        // The entry gate suppresses non-terminal frames before any per-key
        // guard, so this cannot surface AGG_TOO_LARGE instead of the latched
        // mismatch.
        val giant = "g".repeat(MAX_TOOL_ID_CHARS * 2)
        assertEquals(
            emptyList<StreamEvent>(),
            mapper.mapPayload(
                """{"type":"response.output_text.delta","item_id":"$giant","output_index":0,"content_index":0,"delta":"x"}""",
            ),
        )
        assertOnlyOutputMismatch(
            mapper.mapPayload("""{"type":"response.completed","response":{"output":[]}}"""),
        )
    }

    @Test
    fun deltaAfterAuthoritativeTextDoneFailsClosedWithoutAppend() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"Hel"}""",
        )
        assertEquals(
            listOf(StreamEvent.TextDelta(0, 0, "lo")),
            mapper.mapPayload(
                """{"type":"response.output_text.done","output_index":0,"content_index":0,"text":"Hello"}""",
            ),
        )
        assertOnlyOutputMismatch(
            mapper.mapPayload(
                """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"!"}""",
            ),
        )
        assertTrue(mapper.terminalReached)
        assertEquals(emptyList<StreamEvent>(), mapper.finish())
    }

    @Test
    fun terminalReplayWithDifferentNameOrArgsFailsClosed() {
        val mapper = ResponsesMapper()
        val emitted = mapper.mapPayload(
            """{"type":"response.output_item.done","item_id":"it1","item":{"type":"function_call","call_id":"c1","name":"f","arguments":"{}"}}""",
        ).filterIsInstance<StreamEvent.ToolDone>().single()
        assertEquals("c1", emitted.id)
        assertOnlyOutputMismatch(
            mapper.mapPayload(
                """{"type":"response.completed","response":{"output":[{"type":"function_call","item_id":"it1","id":"it1","call_id":"c1","name":"g","arguments":"{\"x\":1}"}]}}""",
            ),
        )
    }

    @Test
    fun completedOmittingPendingToolFailsClosedWithoutDrain() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_item.added","item_id":"it1","item":{"type":"function_call","call_id":"c1","name":"f"}}""",
        )
        mapper.mapPayload(
            """{"type":"response.function_call_arguments.delta","item_id":"it1","delta":"{}"}""",
        )
        // Non-empty terminal output that omits the buffered, not-yet-emitted
        // call: fail closed instead of silently draining/executing it.
        assertOnlyOutputMismatch(
            mapper.mapPayload(
                """{"type":"response.completed","response":{"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"hi"}]}]}}""",
            ),
        )
    }

    @Test
    fun transportFailureAfterConflictLatchIsNonRetryableMismatch() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val body =
                "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_a\",\"output_index\":0,\"content_index\":0,\"delta\":\"A\"}\n\n" +
                    "data: {\"type\":\"response.output_text.delta\",\"item_id\":\"msg_b\",\"output_index\":0,\"content_index\":0,\"delta\":\"B\"}\n\n"
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(body),
            )
            val provider = ResponsesProvider(
                testConfig(server.url("/").toString().trimEnd('/')),
                { "k".toCharArray() },
                failAfterBodyClient(),
            )
            val events = withTimeout(10_000) {
                provider.stream(
                    ChatRequest(model = "m", messages = listOf(ChatMessage("user", "hi"))),
                ).toList()
            }
            val failures = events.filterIsInstance<StreamEvent.Failed>()
            assertEquals(1, failures.size)
            assertEquals("RESPONSES_OUTPUT_MISMATCH", failures.single().message)
            assertFalse("conflict must never retry as SSE_TRANSPORT", failures.single().retryable)
            assertTrue(
                "no Done may leak after a latched conflict: $events",
                events.none { it is StreamEvent.Done },
            )
        } finally {
            server.shutdown()
        }
    }

    // ---- Phase 1B-B: tool identity / reorder / authoritative args ----

    @Test
    fun outputItemDoneChangedCallIdFailsImmediatelyWithSingleMismatch() {
        val mapper = ResponsesMapper()
        val first = mapper.mapPayload(
            """{"type":"response.output_item.done","item_id":"it1","output_index":0,"item":{"type":"function_call","call_id":"c1","name":"f","arguments":"{}"}}""",
        ).filterIsInstance<StreamEvent.ToolDone>().single()
        assertEquals("c1", first.id)
        // Same item/position, different call_id: never a second ToolDone, and
        // the mismatch must project on this frame (not wait for EOF).
        assertOnlyOutputMismatch(
            mapper.mapPayload(
                """{"type":"response.output_item.done","item_id":"it1","output_index":0,"item":{"type":"function_call","call_id":"c2","name":"f","arguments":"{}"}}""",
            ),
        )
        assertTrue(mapper.terminalReached)
        assertEquals(emptyList<StreamEvent>(), mapper.finish())
    }

    @Test
    fun sameCallIdInDifferentItemBucketFailsClosed() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_item.done","item_id":"it1","output_index":0,"item":{"type":"function_call","call_id":"c1","name":"f","arguments":"{}"}}""",
        )
        assertOnlyOutputMismatch(
            mapper.mapPayload(
                """{"type":"response.output_item.done","item_id":"it2","output_index":1,"item":{"type":"function_call","call_id":"c1","name":"f","arguments":"{}"}}""",
            ),
        )
        assertTrue(mapper.terminalReached)
    }

    @Test
    fun sameCallIdInTwoPositionalBucketsFailsClosedBeforeAnyToolDone() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","call_id":"c1","name":"f"}}""",
        )
        mapper.mapPayload(
            """{"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","call_id":"c1","name":"f"}}""",
        )
        // One call_id cannot own two positional buckets; the first done finds
        // the duplicate and projects the mismatch without emitting either.
        assertOnlyOutputMismatch(
            mapper.mapPayload(
                """{"type":"response.output_item.done","output_index":0,"item":{"type":"function_call","call_id":"c1","name":"f","arguments":"{}"}}""",
            ),
        )
        assertTrue(mapper.terminalReached)
    }

    @Test
    fun sameItemIdAtTwoOutputIndexesFailsClosed() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_item.added","item_id":"it1","output_index":0,"item":{"type":"function_call","call_id":"c1","name":"f"}}""",
        )
        assertEquals(
            emptyList<StreamEvent>(),
            mapper.mapPayload(
                """{"type":"response.output_item.added","item_id":"it1","output_index":1,"item":{"type":"function_call","call_id":"c1","name":"f"}}""",
            ),
        )
        assertOnlyOutputMismatch(mapper.finish())
        assertTrue("tool conflict at EOF must be a terminal mismatch", mapper.terminalReached)
    }

    @Test
    fun declaredOutputLikeItemIdDoesNotMergeWithPositionalBucket() {
        val mapper = ResponsesMapper()
        // Real positional bucket for output_index 1 (id-less fallback).
        mapper.mapPayload(
            """{"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","call_id":"c_pos","name":"f_pos"}}""",
        )
        // A declared item id that merely LOOKS like the legacy positional
        // label must not merge into pos:1. Mixing both shapes is ambiguous and
        // fails closed instead of combining args under one call.
        assertEquals(
            emptyList<StreamEvent>(),
            mapper.mapPayload(
                """{"type":"response.output_item.added","item_id":"output_1","output_index":0,"item":{"type":"function_call","call_id":"c_id","name":"f_id"}}""",
            ),
        )
        assertOnlyOutputMismatch(mapper.finish())
        assertTrue(mapper.terminalReached)
    }

    @Test
    fun positionalFallbackAfterOutputLikeDeclaredIdFailsClosed() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_item.added","item_id":"output_1","output_index":0,"item":{"type":"function_call","call_id":"c_id","name":"f_id"}}""",
        )
        // Declared-first order: the later id-less frame would fall back to
        // pos:1, which must not alias the declared `output_1` call.
        assertEquals(
            emptyList<StreamEvent>(),
            mapper.mapPayload(
                """{"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","call_id":"c_pos","name":"f_pos"}}""",
            ),
        )
        assertOnlyOutputMismatch(mapper.finish())
        assertTrue(mapper.terminalReached)
    }

    @Test
    fun declaredOutputLikeItemIdAloneIsALegalIdentity() {
        val mapper = ResponsesMapper()
        val events = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"function_call","item_id":"output_1","id":"output_1","call_id":"c1","name":"f","arguments":"{}"}]}}""",
        )
        assertEquals("c1", events.filterIsInstance<StreamEvent.ToolDone>().single().id)
        assertEquals(StreamEvent.Done("stop"), events.last())
    }

    @Test
    fun completedNewCallAtEmittedPositionFailsClosed() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_item.done","item_id":"it1","output_index":0,"item":{"type":"function_call","call_id":"c1","name":"f","arguments":"{}"}}""",
        )
        val events = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"function_call","item_id":"it2","id":"it2","call_id":"c2","name":"g","arguments":"{}"}]}}""",
        )
        // The emitted c1 must stay the only ToolDone ever projected.
        assertOnlyOutputMismatch(events)
        assertTrue(events.none { it is StreamEvent.ToolDone })
        assertTrue(mapper.terminalReached)
    }

    @Test
    fun completedOmittingEmittedCallFailsClosedWithoutDone() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_item.done","item_id":"it1","output_index":0,"item":{"type":"function_call","call_id":"c1","name":"f","arguments":"{}"}}""",
        )
        // Non-empty authoritative output that does not represent the emitted
        // pair would let TurnController execute a tool the terminal omits.
        assertOnlyOutputMismatch(
            mapper.mapPayload(
                """{"type":"response.completed","response":{"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"done"}]}]}}""",
            ),
        )
        assertTrue(mapper.terminalReached)
        assertEquals(emptyList<StreamEvent>(), mapper.finish())
    }

    @Test
    fun completedRepresentingEmittedCallsInReorderIsCleanDone() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_item.done","item_id":"it1","output_index":0,"item":{"type":"function_call","call_id":"c1","name":"f","arguments":"{\"a\": 1}"}}""",
        )
        mapper.mapPayload(
            """{"type":"response.output_item.done","item_id":"it2","output_index":1,"item":{"type":"function_call","call_id":"c2","name":"g","arguments":"{\"b\": 2}"}}""",
        )
        val events = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"function_call","item_id":"it2","id":"it2","call_id":"c2","name":"g","arguments":"{\"b\":2}"},{"type":"function_call","item_id":"it1","id":"it1","call_id":"c1","name":"f","arguments":"{\"a\":1}"}]}}""",
        )
        assertTrue("replays must not re-emit ToolDone: $events", events.none { it is StreamEvent.ToolDone })
        assertTrue(events.none { it is StreamEvent.Failed })
        assertEquals(StreamEvent.Done("stop"), events.last())
    }

    @Test
    fun completedOmittingIdOnlyPendingCallFailsClosed() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_item.added","item_id":"it1","output_index":0,"item":{"type":"function_call","call_id":"c1"}}""",
        )
        assertOnlyOutputMismatch(
            mapper.mapPayload(
                """{"type":"response.completed","response":{"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"hi"}]}]}}""",
            ),
        )
        assertTrue(mapper.terminalReached)
    }

    @Test
    fun idOnlyPendingCallAtEofFailsNonRetryableNotTruncated() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_item.added","item_id":"it1","output_index":0,"item":{"type":"function_call","call_id":"c1"}}""",
        )
        assertOnlyOutputMismatch(mapper.finish())
        assertTrue(mapper.terminalReached)
    }

    @Test
    fun completedFullDifferentFromArgumentsDoneFullFailsClosed() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.function_call_arguments.done","item_id":"it1","arguments":"{\"a\": 1}"}""",
        )
        assertOnlyOutputMismatch(
            mapper.mapPayload(
                """{"type":"response.completed","response":{"output":[{"type":"function_call","item_id":"it1","id":"it1","call_id":"c1","name":"f","arguments":"{\"a\":2}"}]}}""",
            ),
        )
        assertTrue(mapper.terminalReached)
    }

    @Test
    fun completedFullEquivalentToArgumentsDoneCompletes() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.function_call_arguments.done","item_id":"it1","arguments":"{\"b\": [2, 3], \"a\": 1}"}""",
        )
        val events = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"function_call","item_id":"it1","id":"it1","call_id":"c1","name":"f","arguments":"{\"a\":1,\"b\":[2,3]}"}]}}""",
        )
        assertEquals("c1", events.filterIsInstance<StreamEvent.ToolDone>().single().id)
        assertTrue(events.none { it is StreamEvent.Failed })
        assertEquals(StreamEvent.Done("stop"), events.last())
    }

    @Test
    fun outputItemDoneFullDifferentFromDeltasFailsClosed() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_item.added","item_id":"it1","output_index":0,"item":{"type":"function_call","call_id":"c1","name":"f"}}""",
        )
        mapper.mapPayload(
            """{"type":"response.function_call_arguments.delta","item_id":"it1","delta":"{\"a\": 1}"}""",
        )
        assertOnlyOutputMismatch(
            mapper.mapPayload(
                """{"type":"response.output_item.done","item_id":"it1","output_index":0,"item":{"type":"function_call","call_id":"c1","name":"f","arguments":"{\"a\":2}"}}""",
            ),
        )
        assertTrue(mapper.terminalReached)
    }

    @Test
    fun outputItemDoneFullEquivalentToDeltasEmitsBufferedArgs() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_item.added","item_id":"it1","output_index":0,"item":{"type":"function_call","call_id":"c1","name":"f"}}""",
        )
        mapper.mapPayload(
            """{"type":"response.function_call_arguments.delta","item_id":"it1","delta":"{\"a\": 1}"}""",
        )
        val done = mapper.mapPayload(
            """{"type":"response.output_item.done","item_id":"it1","output_index":0,"item":{"type":"function_call","call_id":"c1","name":"f","arguments":"{ \"a\" : 1 }"}}""",
        ).filterIsInstance<StreamEvent.ToolDone>().single()
        assertEquals("{\"a\": 1}", done.argumentsJson)
        assertEquals(StreamEvent.Done("stop"), mapper.mapPayload("""{"type":"response.completed","response":{"output":[]}}""").last())
    }

    @Test
    fun duplicateArgumentsDoneDifferentFullFailsClosed() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.function_call_arguments.done","item_id":"it1","arguments":"{\"a\":1}"}""",
        )
        assertOnlyOutputMismatch(
            mapper.mapPayload(
                """{"type":"response.function_call_arguments.done","item_id":"it1","arguments":"{\"a\":2}"}""",
            ),
        )
        assertTrue(mapper.terminalReached)
    }

    @Test
    fun duplicateArgumentsDoneEquivalentFullIsNoOp() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.function_call_arguments.done","item_id":"it1","arguments":"{\"a\": 1}"}""",
        )
        assertEquals(
            emptyList<StreamEvent>(),
            mapper.mapPayload(
                """{"type":"response.function_call_arguments.done","item_id":"it1","arguments":"{\"a\":1}"}""",
            ),
        )
        val done = mapper.mapPayload(
            """{"type":"response.output_item.done","item_id":"it1","item":{"type":"function_call","call_id":"c1","name":"f","arguments":"{\"a\":1}"}}""",
        ).filterIsInstance<StreamEvent.ToolDone>().single()
        assertEquals("{\"a\": 1}", done.argumentsJson)
    }

    @Test
    fun deltaAfterOutputItemDoneFailsClosedWithoutMutation() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_item.done","item_id":"it1","output_index":0,"item":{"type":"function_call","call_id":"c1","name":"f","arguments":"{}"}}""",
        )
        assertOnlyOutputMismatch(
            mapper.mapPayload(
                """{"type":"response.function_call_arguments.delta","item_id":"it1","delta":"GARBAGE"}""",
            ),
        )
        assertTrue(mapper.terminalReached)
    }

    @Test
    fun argumentsDoneAfterOutputItemDoneFailsClosedEvenWhenIdentical() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_item.done","item_id":"it1","output_index":0,"item":{"type":"function_call","call_id":"c1","name":"f","arguments":"{}"}}""",
        )
        assertOnlyOutputMismatch(
            mapper.mapPayload(
                """{"type":"response.function_call_arguments.done","item_id":"it1","arguments":"{}"}""",
            ),
        )
        assertTrue(mapper.terminalReached)
    }

    @Test
    fun completedValidThenConflictingCallStagesOnlyMismatch() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_item.added","item_id":"it1","output_index":0,"item":{"type":"function_call","call_id":"c1","name":"f"}}""",
        )
        mapper.mapPayload(
            """{"type":"response.function_call_arguments.delta","item_id":"it1","delta":"{}"}""",
        )
        mapper.mapPayload(
            """{"type":"response.output_item.added","item_id":"it2","output_index":1,"item":{"type":"function_call","call_id":"c2","name":"g"}}""",
        )
        mapper.mapPayload(
            """{"type":"response.function_call_arguments.done","item_id":"it2","arguments":"{}"}""",
        )
        // c1 is a valid reconciliation; c2's terminal full contradicts its
        // authoritative arguments.done. The later conflict must discard the
        // staged c1 ToolDone rather than emit it before the mismatch.
        assertOnlyOutputMismatch(
            mapper.mapPayload(
                """{"type":"response.completed","response":{"output":[{"type":"function_call","item_id":"it1","id":"it1","call_id":"c1","name":"f","arguments":"{}"},{"type":"function_call","item_id":"it2","id":"it2","call_id":"c2","name":"g","arguments":"{\"x\":1}"}]}}""",
            ),
        )
        assertTrue(mapper.terminalReached)
    }

    @Test
    fun toolConflictThenEofIsNonRetryableMismatch() {
        val mapper = ResponsesMapper()
        mapper.mapPayload(
            """{"type":"response.output_item.added","item_id":"it1","output_index":0,"item":{"type":"function_call","call_id":"c1","name":"f"}}""",
        )
        // Distinct declared id at an owned position latches without projecting.
        mapper.mapPayload(
            """{"type":"response.output_item.added","item_id":"it2","output_index":0,"item":{"type":"function_call","call_id":"c2","name":"g"}}""",
        )
        assertOnlyOutputMismatch(mapper.finish())
        assertTrue("conflict terminal must be reached at EOF", mapper.terminalReached)
    }

    @Test
    fun keylessPayloadlessTerminalFunctionCallIsPositiveNoOp() {
        val mapper = ResponsesMapper()
        val events = mapper.mapPayload(
            """{"type":"response.completed","response":{"output":[{"type":"function_call"}]}}""",
        )
        assertTrue(events.none { it is StreamEvent.ToolDone })
        assertEquals(StreamEvent.Done("stop"), events.last())
    }

    @Test
    fun exactDoneArgsReplayDoesNotEmitAgain() {
        val mapper = ResponsesMapper()
        val frame =
            """{"type":"response.output_item.done","item_id":"it1","output_index":0,"item":{"type":"function_call","call_id":"c1","name":"f","arguments":"{\"a\": 1}"}}"""
        assertEquals(1, mapper.mapPayload(frame).filterIsInstance<StreamEvent.ToolDone>().size)
        // Same pair/name/args (semantic whitespace variant) is a no-op.
        assertEquals(
            emptyList<StreamEvent>(),
            mapper.mapPayload(
                """{"type":"response.output_item.done","item_id":"it1","output_index":0,"item":{"type":"function_call","call_id":"c1","name":"f","arguments":"{\"a\":1}"}}""",
            ),
        )
    }

    @Test
    fun mismatchedItemIdAndIdFieldsFailClosed() {
        val mapper = ResponsesMapper()
        assertOnlyOutputMismatch(
            mapper.mapPayload(
                """{"type":"response.output_item.done","item_id":"it1","output_index":0,"item":{"type":"function_call","id":"it2","call_id":"c1","name":"f","arguments":"{}"}}""",
            ),
        )
        assertTrue(mapper.terminalReached)
    }

    @Test
    fun emptyArgumentsDeltaEmitsNoToolDelta() {
        val mapper = ResponsesMapper()
        assertEquals(
            emptyList<StreamEvent>(),
            mapper.mapPayload(
                """{"type":"response.function_call_arguments.delta","item_id":"it1","delta":""}""",
            ),
        )
        val real = mapper.mapPayload(
            """{"type":"response.function_call_arguments.delta","item_id":"it1","delta":"{}"}""",
        )
        assertEquals(1, real.filterIsInstance<StreamEvent.ToolDelta>().size)
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
