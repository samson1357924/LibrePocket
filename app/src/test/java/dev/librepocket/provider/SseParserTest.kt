package dev.librepocket.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SseFrameParser] contract tests (SPEC §3.4 / §10.3).
 * The `sse/chat-basic.txt` fixture is a sanitized real-capture-style
 * Chat Completions stream (ids, model stamp, fingerprint, split tool-call
 * args, usage trailer, `[DONE]`), replayed in odd-sized chunks.
 */
class SseParserTest {

    @Test
    fun multilineDataMergedWithNewline() {
        val p = SseFrameParser()
        val frames = p.feed("event: update\ndata: line1\ndata: line2\n\n") + p.flush()
        assertEquals(1, frames.size)
        assertEquals("update", frames[0].eventName)
        assertEquals("line1\nline2", frames[0].data)
    }

    @Test
    fun commentHeartbeatDiscarded() {
        val p = SseFrameParser()
        val frames = p.feed(": ping\n\n: another\n\n") + p.flush()
        assertTrue(frames.isEmpty())
    }

    @Test
    fun doneMarkerIdentifiedNotParsed() {
        val p = SseFrameParser()
        val frames = p.feed("data: [DONE]\n\n") + p.flush()
        assertEquals(1, frames.size)
        assertTrue(frames[0].isDone)
        assertNull(frames[0].eventName)
    }

    @Test
    fun blankLineSplitsEvents() {
        val p = SseFrameParser()
        val frames = p.feed("data: a\n\ndata: b\n\n") + p.flush()
        assertEquals(listOf("a", "b"), frames.map { it.data })
    }

    @Test
    fun crlfTolerated() {
        val p = SseFrameParser()
        val frames = p.feed("data: a\r\n\r\n") + p.flush()
        assertEquals(1, frames.size)
        assertEquals("a", frames[0].data)
    }

    @Test
    fun dataPrefixStripsSingleSpace() {
        val p = SseFrameParser()
        val frames = p.feed("data:  two spaces\n\n") + p.flush()
        assertEquals(" two spaces", frames[0].data)
    }

    @Test
    fun utf8MultibyteSplitAcrossChunks() {
        // "嘿" = E5 98 BF; split the character across two feed() calls.
        val raw = "data: 嘿嘿\n\n".toByteArray(Charsets.UTF_8)
        val cut = raw.indexOfFirst { it == 0xE5.toByte() } + 1
        val p = SseFrameParser()
        val first = p.feed(raw, 0, cut)
        assertTrue(first.isEmpty()) // nothing complete yet
        val rest = p.feed(raw, cut, raw.size - cut) + p.flush()
        assertEquals(1, rest.size)
        assertEquals("嘿嘿", rest[0].data)
    }

    @Test
    fun incompleteTrailingUtf8SequenceIsHeldBackNotReplaced() {
        // completeUtf8PrefixLen must HOLD an incomplete trailing sequence
        // instead of decoding it as U+FFFD. ProviderTransportC1Test's EOF-latch
        // boundary case depends on exactly this: the final char of a line must
        // stay undecoded (and thus uncounted toward the tail latch) until its
        // continuation bytes arrive.
        val raw = "data: 嘿\n\n".toByteArray(Charsets.UTF_8) // "嘿" = E5 98 BF
        val cut = raw.size - 4 // "data: " + lead byte only: nothing completes yet
        val p = SseFrameParser()
        assertTrue("an incomplete trailing sequence yields no frame", p.feed(raw, 0, cut).isEmpty())
        val frames = p.feed(raw, cut, raw.size - cut) + p.flush()
        assertEquals(1, frames.size)
        assertEquals("嘿", frames[0].data)
    }

    @Test
    fun singleLineOverOneMiBLatchesError() {
        val p = SseFrameParser()
        val big = "data: " + "x".repeat(SseFrameParser.MAX_LINE_BYTES)
        val frames = p.feed(big)
        assertTrue(p.lineTooLong)
        assertTrue(frames.isEmpty())
        assertTrue(p.flush().isEmpty())
    }

    @Test
    fun stackedSmallLinesLatchFrameBudgetNotLine() {
        // Small-line stacking attack: 300 individually-legal (~4 KiB) data
        // lines in one event. No line trips the per-line cap, but the joined
        // payload (~1.2 M chars) trips the per-event total. Typed distinctly
        // from a single-line breach (lineTooLong stays false).
        val p = SseFrameParser()
        val line = "data: " + "q".repeat(4_096) + "\n"
        val frames = p.feed(buildString {
            repeat(300) { append(line) }
            append("\n")
        })
        assertTrue("stacked event must latch the frame budget", p.frameTooLong)
        assertFalse("stacking must not masquerade as a single-line breach", p.lineTooLong)
        assertTrue(frames.isEmpty())
        assertTrue(p.flush().isEmpty())
        // Sticky: later bytes are dropped, never parsed.
        assertTrue(p.feed("data: late\n\n").isEmpty())
        assertTrue(p.frameTooLong)
    }

    @Test
    fun frameTotalExactCapStillDispatchesAndResetsPerEvent() {
        // Values 174762 + 174762 + 1 separator = 349525 joined chars x3 = 1048575 fits.
        val p = SseFrameParser()
        val frames = p.feed("data: " + "a".repeat(174_762) + "\ndata: " + "b".repeat(174_762) + "\n\n")
        assertFalse(p.frameTooLong)
        assertEquals(1, frames.size)
        assertEquals("a".repeat(174_762) + "\n" + "b".repeat(174_762), frames[0].data)
        // The budget is per event: the next event starts fresh.
        val next = p.feed("data: ok\n\n") + p.flush()
        assertFalse(p.frameTooLong)
        assertEquals(listOf("ok"), next.map { it.data })
    }

    @Test
    fun frameTotalCountsSeparatorOverBudget() {
        // Joined (174762 + 174763 + 1 separator = 349526) x3 = 1048578 > 1 MiB: latch.
        val p = SseFrameParser()
        val frames = p.feed("data: " + "a".repeat(174_762) + "\ndata: " + "b".repeat(174_763) + "\n\n")
        assertTrue("separator must count toward the frame budget", p.frameTooLong)
        assertFalse(p.lineTooLong)
        assertTrue(frames.isEmpty())
        assertTrue(p.flush().isEmpty())
    }

    @Test
    fun manyEmptyDataLinesLatchFrameBudget() {
        // 400k empty data lines: values are 0 chars but "\n" separators accumulate.
        val p = SseFrameParser()
        val frames = p.feed(buildString {
            repeat(400_000) { append("data:\n") }
            append("\n")
        })
        assertTrue("empty lines must still latch via separators", p.frameTooLong)
        assertFalse(p.lineTooLong)
        assertTrue(frames.isEmpty())
        assertTrue(p.flush().isEmpty())
        // Sticky: later bytes are dropped, never parsed.
        assertTrue(p.feed("data: late\n\n").isEmpty())
        assertTrue(p.frameTooLong)
    }

    @Test
    fun singleCharLinesBoundaryDispatchThenLatch() {
        // 174763 x 1-char lines: joined 174763 + 174762 separators = 349525 x3 = 1048575 fits.
        val fit = SseFrameParser()
        val fitFrames = fit.feed(buildString {
            repeat(174_763) { append("data: x\n") }
            append("\n")
        })
        assertFalse(fit.frameTooLong)
        assertEquals(1, fitFrames.size)
        assertEquals(349_525, fitFrames[0].data.length)
        // One more 1-char line tips over: joined 349527 x3 = 1048581 > 1 MiB.
        val over = SseFrameParser()
        val overFrames = over.feed(buildString {
            repeat(174_764) { append("data: x\n") }
            append("\n")
        })
        assertTrue("174764 single-char lines must latch", over.frameTooLong)
        assertFalse(over.lineTooLong)
        assertTrue(overFrames.isEmpty())
        assertTrue(over.flush().isEmpty())
    }

    @Test
    fun realCaptureFixtureSurvivesOddChunking() {
        val bytes = resourceBytes("sse/chat-basic.txt")
        val whole = SseFrameParser().let { it.feed(bytes) + it.flush() }

        // 8 data frames + [DONE]; the `: ping` heartbeat yields nothing.
        assertEquals(9, whole.size)
        assertFalse(whole.any { it.data == "ping" })
        assertTrue(whole.last().isDone)

        // Re-feed in 37-byte chunks (splits UTF-8 + JSON mid-token).
        val chunked = SseFrameParser()
        val out = ArrayList<SseFrameParser.Frame>()
        var off = 0
        while (off < bytes.size) {
            val len = minOf(37, bytes.size - off)
            out += chunked.feed(bytes, off, len)
            off += len
        }
        out += chunked.flush()
        assertEquals(whole, out)
        // Spot-check: CJK body survived chunking intact.
        assertTrue(whole.any { it.data.contains("我來幫你查一下") })
    }

    @Test
    fun flushDeliversTailEventWithoutTrailingBlankLine() {
        // The final `data:` line is LF-terminated but the stream ends without a
        // blank line: only flush dispatches the tail event. No budget latch
        // fires in this shape; a flush-time lineTooLong latch would drop it.
        val p = SseFrameParser()
        assertTrue(p.feed("data: tail").isEmpty())
        assertTrue(p.feed(" value\n").isEmpty())
        val tail = p.flush()
        assertEquals(1, tail.size)
        assertEquals("tail value", tail[0].data)
        assertNull(tail[0].eventName)
        assertFalse(p.lineTooLong)
        assertFalse(p.frameTooLong)
    }

    @Test
    fun flushTailLatchAtEofDropsPendingEvent() {
        // flush-time lineTooLong tail latch: while feed() runs, the unterminated
        // tail sits exactly one char under the x3 bound, and the final 中 is
        // truncated to its lead byte. flush() decodes that byte as U+FFFD,
        // pushes the tail over MAX_LINE_BYTES, latches lineTooLong and clears the
        // pending `data: x` event. The event must be dropped, never dispatched.
        val p = SseFrameParser()
        p.feed("data: x\n") // pendingData = ["x"]

        // "data: " = 6 chars; 6 + 349_519 = 349_525 chars.
        // 349_525 * 3 = 1_048_575 <= 1_048_576 (MAX_LINE_BYTES): no latch yet.
        p.feed("data: " + "中".repeat(349_519))

        // 0xE4 is the lead byte of 中 (it needs two continuation bytes), so
        // completeUtf8PrefixLen holds it back: it adds no char to the tail check.
        p.feed(byteArrayOf(0xE4.toByte()))
        assertFalse(p.lineTooLong)

        // EOF decodes the lone 0xE4 as U+FFFD (one char on the JVM):
        // 349_526 * 3 = 1_048_578 > 1 MiB. The tail check clears pendingData
        // before the tail-event branch, so flush() returns empty.
        assertTrue("the EOF tail check must latch lineTooLong", p.flush().isEmpty())
        assertTrue(p.lineTooLong)
        assertFalse("the line cap, not the frame cap, must latch", p.frameTooLong)

        // lineTooLong is sticky; a later flush can never resurrect the event.
        assertTrue(p.flush().isEmpty())
    }

    private fun resourceBytes(name: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
            ?: error("missing test resource: $name")
        return stream.use { it.readBytes() }
    }
}
