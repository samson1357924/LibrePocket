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
    fun singleLineOverOneMiBLatchesError() {
        val p = SseFrameParser()
        val big = "data: " + "x".repeat(SseFrameParser.MAX_LINE_BYTES)
        val frames = p.feed(big)
        assertTrue(p.lineTooLong)
        assertTrue(frames.isEmpty())
        assertTrue(p.flush().isEmpty())
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

    private fun resourceBytes(name: String): ByteArray {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
            ?: error("missing test resource: $name")
        return stream.use { it.readBytes() }
    }
}
