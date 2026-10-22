package dev.librepocket.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Nesting depth contract mirrored from the production parser's ceiling
 * (MiniJson). Kept as a self-contained literal (not a reference to the
 * production symbol) so this spec compiles and holds independently: the
 * tests assert the CONTRACT itself — CEILING levels parse, CEILING + 1 fail
 * closed. Keep in sync with MiniJson's ceiling.
 *
 * The literal is deliberate RED hygiene, not duplication: building the
 * nest() payloads directly from MiniJson.MAX_DEPTH would make the ceiling
 * tests tautological (they would pass for any value). The independent
 * literal plus the ceilingMatchesProductionContract tripwire below fails
 * loudly on drift, so a ceiling change surfaces as one pointed failure
 * instead of several contract failures whose messages do not name the
 * root cause.
 */
private const val CEILING = 64

/**
 * Node-count contract mirrored from the production parser's ceiling
 * (`MiniJson.MAX_NODES`), same RED-hygiene rationale as [CEILING]: the
 * boundary tests build payloads whose node count they can state from the
 * shape alone (flat array of `0`s = values + 1) instead of from the
 * production symbol, and [nodeCeilingMatchesProductionContract] is the single
 * drift tripwire. Counting semantics mirrored here: every value node AND
 * every object key counts (both allocate).
 */
private const val NODE_CEILING = 65_536

/**
 * MiniJson recursion-depth guard (Stage 1, F3; S2 typed fail-closed).
 *
 * A hostile SSE frame can nest JSON far deeper than the JVM call stack
 * tolerates (hundreds of thousands of levels fit in 1 MiB); without a ceiling
 * the parser throws [StackOverflowError], which the mappers (catching only
 * [MiniJson.MiniJsonException]) do not swallow, so the transport mislabels the
 * whole poisoned stream as a retryable failure. The parser fails closed at
 * CEILING with [MiniJson.MiniJsonDepthException] (a [MiniJson.MiniJsonException]
 * subtype, so old catches still catch it), and the mappers translate the depth
 * subtype into a typed non-retryable aggregation failure
 * (`TOOL_ARGS_TOO_LARGE` / `AGG_TOO_LARGE`) — never a dropped frame folded
 * into a later success, never a retryable `SSE_TRUNCATED`.
 *
 * Depth rationale: the deepest legitimate payload in the project fixtures
 * nests 7 (`sse/chat-basic.txt` `tool_calls[].function`), so 64 leaves ample
 * headroom while rejecting a hostile frame far below the native stack limit.
 */
class MiniJsonTest {
    private val ceiling = CEILING

    /** `depth` nested arrays wrapping a `0` scalar. */
    private fun nest(depth: Int): String = buildString {
        repeat(depth) { append('[') }
        append('0')
        repeat(depth) { append(']') }
    }

    /**
     * Flat array with exactly [values] `0` elements: [values] + 1 nodes under
     * the documented counting model (one array node + one node per element),
     * stated by this test from the shape, not by the production counter.
     */
    private fun flatArrayValues(values: Int): String = buildString {
        append('[')
        repeat(values) { i ->
            if (i > 0) append(',')
            append('0')
        }
        append(']')
    }

    @Test
    fun ceilingMatchesProductionContract() {
        // Drift tripwire (the only place that couples to the production
        // symbol): the mirrored CEILING above must equal MiniJson.MAX_DEPTH.
        // The other tests assert the contract at CEILING / CEILING+1 without
        // referencing the symbol, so without this tripwire a ceiling change
        // would fail them with messages that do not point at the root cause.
        assertEquals(
            "mirrored CEILING drifted from MiniJson.MAX_DEPTH; update the contract literal and rationale together",
            MiniJson.MAX_DEPTH,
            CEILING,
        )
    }

    @Test
    fun parsesNestingAtCeiling() {
        val v = MiniJson.parse(nest(ceiling))
        assertTrue(v is MiniJson.JArr)
    }

    @Test
    fun rejectsNestingBeyondCeiling() {
        // Exactly one level deeper than the ceiling fails closed with the
        // SAME exception as any malformed frame (not StackOverflowError).
        try {
            MiniJson.parse(nest(ceiling + 1))
            fail("over-ceiling nesting must fail closed with MiniJsonException")
        } catch (e: MiniJson.MiniJsonException) {
            // expected
        }
    }

    @Test
    fun rejectsArbitrarilyDeepHostileInputWithoutStackOverflow() {
        // 100k levels would overflow the native stack unguarded; the ceiling
        // trips long before, so this throws MiniJsonException, never escaping
        // as a StackOverflowError.
        try {
            MiniJson.parse(nest(100_000))
            fail("deeply nested input must fail closed")
        } catch (e: StackOverflowError) {
            fail("depth ceiling must prevent StackOverflowError: $e")
        } catch (e: MiniJson.MiniJsonException) {
            // expected
        }
    }

    @Test
    fun restoresDepthForSiblingContainers() {
        // The depth counter must pop on exit, not only increment: an array
        // holding a max-depth nested array followed by a shallow sibling array
        // parses fine (a leaked depth would reject the shallow sibling).
        val payload = "[" + nest(ceiling - 1) + ",[]]"
        val v = MiniJson.parse(payload) as MiniJson.JArr
        assertEquals(2, v.items.size)
        assertTrue(v.items[1] is MiniJson.JArr)
    }

    @Test
    fun parsesRealShapedToolCallPayload() {
        // Real depth-7 chat tool_calls shape (matches sse/chat-basic.txt) parses
        // to the expected structure — the depth ceiling must not false-trip.
        val chat = """{"id":"x","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c","type":"function","function":{"name":"f","arguments":"{}"}}]},"finish_reason":null}]}"""
        val root = MiniJson.parse(chat) as MiniJson.JObj
        val choice = root.arr("choices")!!.items.single() as MiniJson.JObj
        val fn = (choice.obj("delta")!!.arr("tool_calls")!!.items.single() as MiniJson.JObj)
            .obj("function")!!
        assertEquals("f", fn.string("name"))
        assertEquals("{}", fn.string("arguments"))
    }

    @Test
    fun depthBreachIsADistinctSubtypeOfMiniJsonException() {
        // The depth signal must survive old `catch (MiniJsonException)` sites
        // (it IS one) while letting mappers discriminate it from malformed
        // frames.
        try {
            MiniJson.parse(nest(ceiling + 1))
            fail("over-ceiling nesting must fail closed")
        } catch (e: MiniJson.MiniJsonException) {
            assertTrue(
                "depth breach must be the discriminable depth subtype",
                e is MiniJson.MiniJsonDepthException,
            )
        }
    }

    @Test
    fun mapperFailsClosedTypedOnOverDeepFrame() {
        // (a) An over-deep frame throws the typed non-retryable aggregation
        // failure (same triple as every budget overflow) — no
        // StackOverflowError escapes and the valid delta riding the poisoned
        // frame is never surfaced — and (b) the breach latches: a later legal
        // frame still throws instead of projecting, so the attempt can never
        // fold the loss into a success.
        val mapper = ResponsesMapper()
        val deep = """{"type":"response.output_text.delta","delta":"hi","junk":${nest(ceiling + 1)}}"""
        try {
            mapper.mapPayload(deep)
            fail("over-deep frame must fail closed with typed ProviderFailure")
        } catch (e: ProviderFailure) {
            assertEquals(TOOL_ARGS_TOO_LARGE, e.message)
            assertFalse("depth breach must not retry the poisoned stream", e.retryable)
            assertEquals(ProviderFailureCode.AGG_TOO_LARGE, e.code)
        }
        try {
            mapper.mapPayload("""{"type":"response.output_text.delta","delta":"ok"}""")
            fail("post-breach frames must keep failing closed, never project")
        } catch (e: ProviderFailure) {
            assertEquals(TOOL_ARGS_TOO_LARGE, e.message)
            assertFalse(e.retryable)
            assertEquals(ProviderFailureCode.AGG_TOO_LARGE, e.code)
        }
    }

    // ---- Phase-0 review (B): signed / non-hex \uXXXX escapes fail closed ----

    @Test
    fun parseAcceptsValidHexUnicodeEscapes() {
        // Control (pre-fix behavior retained for well-formed input): mixed-case
        // hex decodes, BMP target intact.
        assertEquals("»", (MiniJson.parse("\"\\u00bb\"") as MiniJson.JStr).v)
        assertEquals("»", (MiniJson.parse("\"\\u00BB\"") as MiniJson.JStr).v)
        assertEquals("水", (MiniJson.parse("\"\\u6c34\"") as MiniJson.JStr).v)
    }

    @Test
    fun parseRejectsSignedAndNonHexUnicodeEscapes() {
        // `toIntOrNull(16)` accepted signed prefixes: `\u-123` decoded to the
        // U+FEDD overflow value and `\u+0bb` silently read 0x00bb; RFC 8259
        // only admits hex digits (same rule McpWire.parseString pins at its
        // own parser). Every such escape now fails closed with the standard
        // parser error — the same mechanism as `bad escape '\x'` — not a
        // silent decode.
        for (bad in listOf(
            "\"\\u-123\"", // signed negative
            "\"\\u+0bb\"", // signed positive
            "\"\\u 0bb\"", // delimiter/whitespace unit
            "\"\\u0x0b\"", // radix prefix inside the 4 units
            "\"\\u0bb\"",  // only 3 usable units → 'b' is not hex-4
        )) {
            try {
                MiniJson.parse(bad)
                fail("signed/non-hex escape must fail closed: $bad")
            } catch (e: MiniJson.MiniJsonException) {
                assertTrue(
                    "expected the parse-error mechanism for $bad, got: ${e.message}",
                    e.message!!.contains("bad unicode escape"),
                )
            }
        }
    }

    // ---- Phase 2B: node ceiling (typed fatal latch) ----

    @Test
    fun nodeCeilingMatchesProductionContract() {
        // Drift tripwire (the only place coupling to MiniJson.MAX_NODES): the
        // mirrored NODE_CEILING above must equal the production symbol; the
        // boundary tests assert the contract at N / N+1 from the shape alone.
        assertEquals(
            "mirrored NODE_CEILING drifted from MiniJson.MAX_NODES; update the contract literal and rationale together",
            MiniJson.MAX_NODES,
            NODE_CEILING,
        )
    }

    @Test
    fun parsesExactlyAtNodeCeilingAndRejectsOneOver() {
        // Independently calibrated flat shape: `[0,0,…]` with k values is
        // k+1 nodes by this test's own counting model. CEILING-1 values ==
        // exactly CEILING nodes parses; one more value is N+1 nodes and must
        // fail closed (no off-by-one either way).
        val atCeiling = MiniJson.parse(flatArrayValues(NODE_CEILING - 1)) as MiniJson.JArr
        assertEquals(NODE_CEILING - 1, atCeiling.items.size)
        try {
            MiniJson.parse(flatArrayValues(NODE_CEILING))
            fail("N+1 nodes must fail closed with the node-limit subtype")
        } catch (e: MiniJson.MiniJsonException) {
            assertTrue(
                "over-ceiling node count must be the discriminable node subtype, got: $e",
                e is MiniJson.MiniJsonNodeLimitException,
            )
        }
    }

    @Test
    fun objectKeysCountTowardNodeCeiling() {
        // `{"a":0,"b":0}` is 5 nodes under the documented model (object + 2
        // keys + 2 numbers); a values-only counter would see 3 and accept at
        // maxNodes=4. Keys are Strings too — they must be charged.
        assertTrue(MiniJson.parse("""{"a":0,"b":0}""", maxNodes = 5) is MiniJson.JObj)
        try {
            MiniJson.parse("""{"a":0,"b":0}""", maxNodes = 4)
            fail("object keys must count as nodes")
        } catch (e: MiniJson.MiniJsonException) {
            assertTrue(
                "key-count breach must be the node-limit subtype, got: $e",
                e is MiniJson.MiniJsonNodeLimitException,
            )
        }
        // Sanity: same node budget, equally sized flat array of values parses.
        assertTrue(MiniJson.parse(flatArrayValues(3), maxNodes = 4) is MiniJson.JArr)
    }

    @Test
    fun nodeBreachIsFatalButNotATrailingJunkShape() {
        // The node breach is a MiniJsonFatalException: old
        // `catch (MiniJsonException)` sites still catch it, and future mapper
        // integration can discriminate it from a droppable malformed frame.
        try {
            MiniJson.parse(flatArrayValues(NODE_CEILING))
            fail("over-ceiling node count must fail closed")
        } catch (e: MiniJson.MiniJsonException) {
            assertTrue(e is MiniJson.MiniJsonNodeLimitException)
            assertTrue(e is MiniJson.MiniJsonFatalException)
        }
        // Depth breaches share the same fatal base...
        try {
            MiniJson.parse(nest(ceiling + 1))
            fail("over-ceiling nesting must fail closed")
        } catch (e: MiniJson.MiniJsonException) {
            assertTrue(e is MiniJson.MiniJsonDepthException)
            assertTrue(e is MiniJson.MiniJsonFatalException)
        }
        // ...while plain malformed frames stay NON-fatal (droppable as today).
        try {
            MiniJson.parse("{")
            fail("malformed frame must fail closed")
        } catch (e: MiniJson.MiniJsonException) {
            assertFalse(
                "plain malformed frames must not become fatal (mappers still drop them)",
                e is MiniJson.MiniJsonFatalException,
            )
        }
    }

    @Test
    fun depthAndNodeCeilingsAreIndependentlyDiagnosedByEncounterOrder() {
        // Deep but sparse: only the depth subtype can fire.
        try {
            MiniJson.parse(nest(ceiling + 1))
            fail("deep-only breach must fail closed")
        } catch (e: MiniJson.MiniJsonException) {
            assertTrue("deep-only: depth subtype, got $e", e is MiniJson.MiniJsonDepthException)
        }
        // Flat but dense: only the node subtype can fire.
        try {
            MiniJson.parse(flatArrayValues(NODE_CEILING))
            fail("dense-only breach must fail closed")
        } catch (e: MiniJson.MiniJsonException) {
            assertTrue("flat-only: node subtype, got $e", e is MiniJson.MiniJsonNodeLimitException)
        }
        // Dense prefix then a deep suffix: the node budget is charged as
        // values are parsed left-to-right, so an exhausted budget must report
        // the node subtype BEFORE the parser reaches the deep branch.
        val denseThenDeep = "[" + "0,".repeat(20) + nest(ceiling + 1) + "]"
        try {
            MiniJson.parse(denseThenDeep, maxNodes = 10)
            fail("dense-then-deep must fail closed")
        } catch (e: MiniJson.MiniJsonException) {
            assertTrue("dense-then-deep: node subtype, got $e", e is MiniJson.MiniJsonNodeLimitException)
        }
        // Deep prefix first: the depth guard trips while descending, before
        // the dense suffix is ever charged.
        val deepThenDense = "[" + nest(ceiling + 1) + "," + "0,".repeat(20) + "0]"
        try {
            MiniJson.parse(deepThenDense)
            fail("deep-then-dense must fail closed")
        } catch (e: MiniJson.MiniJsonException) {
            assertTrue("deep-then-dense: depth subtype, got $e", e is MiniJson.MiniJsonDepthException)
        }
    }

    // ---- Phase 2B: strict document shape (trailing junk, numbers, controls) ----

    @Test
    fun rejectsTrailingJunkAfterRootValue() {
        for (bad in listOf("{}x", "1 2", "1_000", "0x10", """{"a":1}{"b":2}""", "\"a\"b", "truex")) {
            try {
                MiniJson.parse(bad)
                fail("trailing junk must fail closed: $bad")
            } catch (e: MiniJson.MiniJsonException) {
                assertFalse(
                    "trailing junk stays a droppable malformed frame: $bad",
                    e is MiniJson.MiniJsonFatalException,
                )
            }
        }
    }

    @Test
    fun acceptsLeadingAndTrailingWhitespace() {
        val v = MiniJson.parse(" \t\n{\"a\": 1}\r\n ") as MiniJson.JObj
        assertEquals(1.0, v.num("a")!!, 0.0)
        assertEquals(1.0, (MiniJson.parse("\n 1 \t") as MiniJson.JNum).v, 0.0)
    }

    @Test
    fun rejectsNumbersOutsideStrictRfc8259Grammar() {
        for (bad in listOf(
            "01", "-01", "00", "1.", ".5", "+1", "--1", "1e", "1e+", "1..2",
            "1e999", "-1e999", "1E", "0.e1",
        )) {
            try {
                MiniJson.parse(bad)
                fail("non-RFC-8259 number must fail closed: $bad")
            } catch (e: MiniJson.MiniJsonException) {
                // expected
            }
        }
    }

    @Test
    fun acceptsValidRfc8259Numbers() {
        assertEquals(0.0, (MiniJson.parse("0") as MiniJson.JNum).v, 0.0)
        assertEquals(0.0, (MiniJson.parse("-0") as MiniJson.JNum).v, 0.0)
        assertEquals(123.0, (MiniJson.parse("123") as MiniJson.JNum).v, 0.0)
        assertEquals(0.5, (MiniJson.parse("0.5") as MiniJson.JNum).v, 0.0)
        assertEquals(100.0, (MiniJson.parse("1e2") as MiniJson.JNum).v, 1e-9)
        assertEquals(100.0, (MiniJson.parse("1E+2") as MiniJson.JNum).v, 1e-9)
        assertEquals(0.01, (MiniJson.parse("1e-2") as MiniJson.JNum).v, 1e-12)
        assertEquals(-12.5, (MiniJson.parse("-12.5e0") as MiniJson.JNum).v, 1e-9)
    }

    @Test
    fun rejectsRawControlCharactersInsideStrings() {
        for (bad in listOf(
            "\"a\nb\"", // raw LF
            "\"a\rb\"", // raw CR
            "\"a\tb\"", // raw TAB
            "\"a\u0000b\"", // raw NUL
            "\"a\u001Fb\"", // raw US
        )) {
            try {
                MiniJson.parse(bad)
                fail("raw control character must fail closed")
            } catch (e: MiniJson.MiniJsonException) {
                assertFalse(
                    "raw controls stay droppable malformed frames",
                    e is MiniJson.MiniJsonFatalException,
                )
            }
        }
    }

    @Test
    fun acceptsEscapedControlCharactersWithExistingSemantics() {
        assertEquals("a\nb", (MiniJson.parse("\"a\\nb\"") as MiniJson.JStr).v)
        assertEquals("a\rb", (MiniJson.parse("\"a\\rb\"") as MiniJson.JStr).v)
        assertEquals("a\tb", (MiniJson.parse("\"a\\tb\"") as MiniJson.JStr).v)
        assertEquals("a\u0000b", (MiniJson.parse("\"a\\u0000b\"") as MiniJson.JStr).v)
        assertEquals("a\u001Fb", (MiniJson.parse("\"a\\u001Fb\"") as MiniJson.JStr).v)
    }
}
