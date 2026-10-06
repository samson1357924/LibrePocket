package dev.librepocket.provider

import java.io.ByteArrayOutputStream
import java.nio.charset.CodingErrorAction

/**
 * Incremental SSE frame parser (SPEC §3.4). Pure Kotlin, zero OkHttp dependency.
 *
 * Contract:
 * - Lines are split on LF (`\r\n` tolerated); a blank line is an event boundary.
 * - `data:` prefixes are stripped of one leading space and joined with `\n`
 *   into a single payload when an event carries multiple `data:` lines.
 * - Lines starting with `:` are comment heartbeats: discarded, never an error.
 * - [Frame.isDone] marks the Chat Completions `data: [DONE]` end marker,
 *   which is surfaced as a frame and never JSON-parsed.
 * - UTF-8 is decoded from the raw byte stream: multi-byte characters split
 *   across [feed] chunks are buffered, never decoded per-Char.
 * - A single line longer than [MAX_LINE_BYTES] (1 MiB) latches [lineTooLong];
 *   providers translate that into `Failed(retryable=false, "SSE_LINE_TOO_LONG")`.
 * - Without an `event:` field the payload is sniffed by the providers
 *   (each tries its own shape), so the parser keeps the raw payload only.
 */
class SseFrameParser {
    data class Frame(
        val eventName: String?,
        val data: String,
    ) {
        /** Chat Completions end-of-stream marker (`data: [DONE]`). */
        val isDone: Boolean get() = data.trim() == DONE_PAYLOAD
    }

    companion object {
        const val DONE_PAYLOAD = "[DONE]"
        const val MAX_LINE_BYTES = 1024 * 1024
    }

    private val byteBuf = ByteArrayOutputStream()
    private val textBuf = StringBuilder()
    private var pendingEvent: String? = null
    private val pendingData = ArrayList<String>()

    /** Sticky once a >1MiB line is observed; the current stream must abort. */
    var lineTooLong: Boolean = false
        private set

    /**
     * Feed a raw network chunk. Returns complete frames; incomplete bytes
     * (partial UTF-8 sequence or unterminated line) stay buffered.
     */
    fun feed(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size - offset): List<Frame> {
        if (lineTooLong) return emptyList()
        byteBuf.write(chunk, offset, length)
        return drainLines(atEof = false)
    }

    fun feed(text: String): List<Frame> =
        feed(text.toByteArray(Charsets.UTF_8))

    /** Drain any trailing buffered event at end of stream. */
    fun flush(): List<Frame> {
        if (lineTooLong) return emptyList()
        val out = drainLines(atEof = true)
        if (pendingData.isNotEmpty() || pendingEvent != null) {
            val frame = Frame(pendingEvent, pendingData.joinToString("\n"))
            pendingEvent = null
            pendingData.clear()
            return out + frame
        }
        return out
    }

    private fun drainLines(atEof: Boolean): List<Frame> {
        val out = ArrayList<Frame>()
        val raw = byteBuf.toByteArray()
        // Decode only the complete prefix; keep an incomplete trailing UTF-8
        // sequence buffered (never split a multi-byte char).
        val completeLen = if (atEof) raw.size else completeUtf8PrefixLen(raw)
        if (completeLen > 0) {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            textBuf.append(decoder.decode(java.nio.ByteBuffer.wrap(raw, 0, completeLen)))
            val rest = raw.copyOfRange(completeLen, raw.size)
            byteBuf.reset()
            byteBuf.write(rest)
        } else if (atEof && raw.isNotEmpty()) {
            // Only malformed trailing bytes remain; decode with replacement.
            textBuf.append(String(raw, Charsets.UTF_8))
            byteBuf.reset()
        }
        var lineStart = 0
        val text = textBuf.toString()
        var i = 0
        while (i < text.length) {
            if (text[i] == '\n') {
                var line = text.substring(lineStart, i)
                if (line.endsWith("\r")) line = line.dropLast(1)
                handleLine(line, out)
                lineStart = i + 1
                if (lineTooLong) break
            }
            i++
        }
        if (lineStart > 0) {
            textBuf.delete(0, lineStart)
        }
        // Lone over-long line without any newline yet: enforce the cap on the
        // buffered tail as well (bytes, worst case 3x chars for CJK).
        if (!lineTooLong && textBuf.length * 3 > MAX_LINE_BYTES && !textBuf.contains('\n')) {
            lineTooLong = true
            pendingEvent = null
            pendingData.clear()
        }
        return out
    }

    private fun handleLine(line: String, out: MutableList<Frame>) {
        if (lineTooLong) return
        if (line.length * 3 > MAX_LINE_BYTES) {
            lineTooLong = true
            pendingEvent = null
            pendingData.clear()
            return
        }
        when {
            line.isEmpty() -> {
                // Event boundary: dispatch only when something was buffered.
                if (pendingData.isNotEmpty() || pendingEvent != null) {
                    out.add(Frame(pendingEvent, pendingData.joinToString("\n")))
                    pendingEvent = null
                    pendingData.clear()
                }
            }
            line.startsWith(":") -> {
                // Comment heartbeat: discard.
            }
            line.startsWith("data:") -> {
                var value = line.removePrefix("data:")
                if (value.startsWith(" ")) value = value.substring(1)
                pendingData.add(value)
            }
            line.startsWith("event:") -> {
                var value = line.removePrefix("event:")
                if (value.startsWith(" ")) value = value.substring(1)
                pendingEvent = value
            }
            // Other SSE fields (id:, retry:) are irrelevant for P1: ignore.
        }
    }

    /** Length of the longest prefix that ends on a UTF-8 code-point boundary. */
    private fun completeUtf8PrefixLen(raw: ByteArray): Int {
        var end = raw.size
        // Walk back over trailing continuation bytes (10xxxxxx), at most 3.
        var cont = 0
        var k = end - 1
        while (k >= 0 && cont < 3 && (raw[k].toInt() and 0xC0) == 0x80) {
            cont++
            k--
        }
        if (k < 0) return 0 // whole buffer is continuation bytes; wait for more
        val lead = raw[k].toInt() and 0xFF
        val need = when {
            lead and 0x80 == 0 -> 0 // ASCII
            lead and 0xE0 == 0xC0 -> 1
            lead and 0xF0 == 0xE0 -> 2
            lead and 0xF8 == 0xF0 -> 3
            else -> return end // stray byte; let the decoder replace it
        }
        return if (cont < need) k else end
    }
}
