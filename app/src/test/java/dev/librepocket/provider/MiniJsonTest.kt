package dev.librepocket.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Nesting depth contract mirrored from the production parser's ceiling
 * (MiniJson). Kept as a self-contained literal (not a reference to the
 * production symbol) so this spec compiles and holds independently: the
 * tests assert the CONTRACT itself — CEILING levels parse, CEILING + 1 fail
 * closed. Keep in sync with MiniJson's ceiling.
 */
private const val CEILING = 64

/**
 * MiniJson recursion-depth guard (Stage 1, F3).
 *
 * A hostile SSE frame can nest JSON far deeper than the JVM call stack
 * tolerates (hundreds of thousands of levels fit in 1 MiB); without a ceiling
 * the parser throws [StackOverflowError], which the mappers (catching only
 * [MiniJson.MiniJsonException]) do not swallow, so the transport mislabels the
 * whole poisoned stream as a retryable failure. The parser now fails closed at
 * CEILING with the same [MiniJson.MiniJsonException] used for any malformed
 * frame, so a mapper drops just that frame and the stream continues.
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
    fun parsesNestingAtCeiling() {
        // Drift tripwire: the mirrored spec and the production ceiling must
        // never diverge silently (the const is visible to this source set).
        assertEquals(MiniJson.MAX_DEPTH, CEILING)
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
    fun mapperDropsOverDeepFrameAndStreamContinues() {
        // (a) An over-deep frame is dropped (emptyList) by the mapper's existing
        // MiniJsonException catch — no StackOverflowError escapes — and (b) the
        // stream keeps projecting later legal frames.
        val mapper = ResponsesMapper()
        val deep = """{"type":"response.output_text.delta","delta":"hi","junk":${nest(ceiling + 1)}}"""
        assertTrue(mapper.mapPayload(deep).isEmpty())
        val ok = mapper.mapPayload("""{"type":"response.output_text.delta","delta":"ok"}""")
        val texts = ok.filterIsInstance<StreamEvent.TextDelta>()
        assertEquals(1, texts.size)
        assertEquals("ok", texts[0].delta)
    }
}
