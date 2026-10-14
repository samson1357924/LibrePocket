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
}
