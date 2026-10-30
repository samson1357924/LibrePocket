package dev.librepocket.agent.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

sealed interface ChatContentBlock {
    data class ThinkingBlock(
        val content: String,
        val isStreaming: Boolean,
    ) : ChatContentBlock

    data class CodeBlock(
        val language: String?,
        val code: String,
        val isStreaming: Boolean,
    ) : ChatContentBlock

    data class ParagraphBlock(
        val annotatedText: AnnotatedString,
    ) : ChatContentBlock
}

/**
 * Robust, linear O(N) parser for thinking blocks (<think>...</think>),
 * code blocks (```lang ... ```), and inline markdown (bold, italic, inline code, lists).
 * Strictly avoids backtracking regular expressions to ensure immunity against ReDoS attacks.
 */
object ChatMarkdownParser {

    private val THINK_TAG_PAIRS = listOf(
        "<think>" to "</think>",
        "<thought>" to "</thought>",
        "<thinking>" to "</thinking>",
    )
    private const val CODE_FENCE = "```"

    // Backslashes are preserved in output. An odd-length run escapes an inline
    // opener, but backslashes inside a code span cannot escape its closer.
    private fun escapedBackticks(text: String): BooleanArray {
        val escaped = BooleanArray(text.length)
        var oddBackslashes = false
        for (i in text.indices) {
            if (text[i] == '\\') {
                oddBackslashes = !oddBackslashes
            } else {
                if (text[i] == '`') escaped[i] = oddBackslashes
                oddBackslashes = false
            }
        }
        return escaped
    }

    // Preserve legacy inline fence openers, but do not treat an indented-code
    // line (four spaces or a tab before the fence) as a fenced block opener.
    private fun invalidIndentedFenceOpeners(text: String): BooleanArray {
        val invalid = BooleanArray(text.length)
        var lineStart = 0
        while (lineStart < text.length) {
            var pos = lineStart
            var tab = false
            while (pos < text.length && (text[pos] == ' ' || text[pos] == '\t')) {
                if (text[pos] == '\t') tab = true
                pos++
            }
            if ((tab || pos - lineStart > 3) && text.startsWith(CODE_FENCE, pos)) invalid[pos] = true
            val newline = text.indexOf('\n', pos)
            if (newline < 0) break
            lineStart = newline + 1
        }
        return invalid
    }

    // Index the starts of exact-length backtick runs for constant-time span lookup.
    // Inline spans support one or two backticks; longer runs remain fence syntax.
    private fun inlineTickRuns(text: String): Array<IntArray> {
        val single = IntArray(text.length + 1) { -1 }
        val double = IntArray(text.length + 1) { -1 }
        var pos = 0
        while (pos < text.length) {
            if (text[pos] != '`') {
                pos++
                continue
            }
            val start = pos
            while (pos < text.length && text[pos] == '`') pos++
            when (pos - start) {
                1 -> single[start] = start
                2 -> double[start] = start
            }
        }
        for (i in text.length - 1 downTo 0) {
            if (single[i] < 0) single[i] = single[i + 1]
            if (double[i] < 0) double[i] = double[i + 1]
        }
        return arrayOf(single, double)
    }

    private fun indentEnd(text: String, start: Int, required: Long = 4L): Int {
        var pos = start
        var columns = 0L
        while (pos < text.length && columns < required) {
            when (text[pos]) {
                ' ' -> columns++
                '\t' -> columns += 4L - columns % 4L
                else -> return -1
            }
            pos++
        }
        return if (columns >= required) pos else -1
    }

    // Recognize the existing unordered markers and their one-to-four-column padding.
    private fun listContentColumn(text: String, marker: Int, indent: Long): Long {
        val base = indent + 1L
        var column = base
        var pos = marker + 1
        while (pos < text.length && (text[pos] == ' ' || text[pos] == '\t')) {
            val next = if (text[pos] == '\t') column + 4L - column % 4L else column + 1L
            if (next - base > 4L) return base + 1L
            column = next
            pos++
        }
        return column
    }

    // Remove boundary blank lines, not indentation on the first nonblank line.
    private fun trimBoundary(text: String, startsAtLine: Boolean = true): String {
        var first = 0
        var lineStart = 0
        while (first < text.length && text[first].isWhitespace()) {
            if (text[first] == '\n') lineStart = first + 1
            first++
        }
        if (first == text.length) return ""
        val start = if (lineStart > 0 || startsAtLine) lineStart else first
        return text.substring(start).trimEnd()
    }

    private enum class Kind { TEXT, THINKING, CODE, INDENTED_CODE, PARTIAL_TAG }

    private data class Segment(
        val kind: Kind,
        val start: Int,
        val end: Int,
        val contentStart: Int = start,
        val contentEnd: Int = end,
        val language: String? = null,
        val unclosed: Boolean = false,
        val indentColumns: Long = 4L,
    )

    // Each character is visited a bounded number of times. Inline code shields tags,
    // and fenced code and thinking blocks shield everything until their own closer.
    private fun segments(text: String, streaming: Boolean): List<Segment> {
        val result = mutableListOf<Segment>()
        val length = text.length
        val escaped = escapedBackticks(text)
        val invalidFence = invalidIndentedFenceOpeners(text)
        val nextNewline = IntArray(length + 1) { -1 }
        val ticks = inlineTickRuns(text)
        for (i in length - 1 downTo 0) {
            nextNewline[i] = if (text[i] == '\n') i else nextNewline[i + 1]
        }
        var cursor = 0
        var plainStart = 0
        // Only the existing unordered-list syntax is recognized here. Blank lines
        // and indented continuation lines retain context; a real block ends it.
        val listContentColumns = mutableListOf<Long>()
        fun addPlain(end: Int) {
            if (end > plainStart) result.add(Segment(Kind.TEXT, plainStart, end))
        }
        while (cursor < length) {
            val atLineStart = cursor == 0 || text[cursor - 1] == '\n'
            val previousBlank = if (atLineStart && cursor > 0) {
                val prevStart = text.lastIndexOf('\n', cursor - 2) + 1
                text.substring(prevStart, cursor - 1).isBlank()
            } else {
                false
            }
            var lineMarker = cursor
            var indentColumns = 0L
            if (atLineStart) {
                while (lineMarker < length && (text[lineMarker] == ' ' || text[lineMarker] == '\t')) {
                    indentColumns = if (text[lineMarker] == '\t') indentColumns + 4L - indentColumns % 4L else indentColumns + 1L
                    lineMarker++
                }
            }
            val blankLine = atLineStart && (lineMarker == length || text[lineMarker] == '\r' || text[lineMarker] == '\n')
            val listItem = atLineStart && lineMarker + 1 < length &&
                (text[lineMarker] == '-' || text[lineMarker] == '*') && text[lineMarker + 1] == ' '
            // Returning to a parent's content column closes deeper list contexts.
            // Each entry is pushed and removed at most once.
            if (atLineStart && !blankLine) {
                while (listContentColumns.isNotEmpty() && indentColumns < listContentColumns.last()) {
                    listContentColumns.removeAt(listContentColumns.lastIndex)
                }
            }
            val previousListItem = listContentColumns.isNotEmpty()
            // Indented code inside this list begins four columns after its content.
            val codeIndent = if (previousListItem) listContentColumns.last() + 4L else 4L
            val listContinuation = previousListItem && indentColumns > 0 && indentColumns < codeIndent
            val nestedList = previousListItem && listItem && indentColumns < codeIndent
            val indent = if (atLineStart && !blankLine && !nestedList && !listContinuation &&
                (cursor == plainStart || previousBlank)) indentEnd(text, cursor, codeIndent) else -1
            if (indent >= 0 && indent < length && text[indent] != '\n' && text[indent] != '\r') {
                var end = cursor
                var line = cursor
                while (line < length) {
                    val newline = nextNewline[line]
                    val lineEnd = if (newline < 0) length else newline
                    var pos = line
                    while (pos < lineEnd && (text[pos] == ' ' || text[pos] == '\t' || text[pos] == '\r')) pos++
                    val next = if (newline < 0) length else newline + 1
                    if (pos == lineEnd) {
                        line = next
                        continue
                    }
                    if (indentEnd(text, line, codeIndent) < 0) break
                    end = next
                    line = next
                }
                if (end > cursor) {
                    addPlain(cursor)
                    result.add(Segment(Kind.INDENTED_CODE, cursor, end, unclosed = streaming && line == length,
                        indentColumns = codeIndent))
                    cursor = end
                    plainStart = cursor
                    continue
                }
            }
            if (atLineStart && !blankLine) {
                if (listItem) {
                    listContentColumns.add(listContentColumn(text, lineMarker, indentColumns))
                } else if (!listContinuation) {
                    listContentColumns.clear()
                }
            }
            val pair = if (text[cursor] == '<') {
                THINK_TAG_PAIRS.firstOrNull { text.regionMatches(cursor, it.first, 0, it.first.length, ignoreCase = true) }
            } else {
                null
            }
            if (pair != null) {
                listContentColumns.clear()
                addPlain(cursor)
                val start = cursor + pair.first.length
                val close = text.indexOf(pair.second, start, ignoreCase = true)
                var end = if (close < 0) length else close
                // Do not flash a partial closing tag as reasoning during streaming.
                if (close < 0 && streaming) {
                    val lastTag = text.lastIndexOf('<')
                    if (lastTag >= start && isTagPrefix(text, lastTag, pair.second)) end = lastTag
                }
                val next = if (close < 0) length else close + pair.second.length
                result.add(Segment(Kind.THINKING, cursor, next, start, end, unclosed = close < 0))
                cursor = next
                plainStart = cursor
            } else if (streaming && text[cursor] == '<' && THINK_TAG_PAIRS.any { isTagPrefix(text, cursor, it.first) }) {
                addPlain(cursor)
                result.add(Segment(Kind.PARTIAL_TAG, cursor, length))
                cursor = length
                plainStart = cursor
            } else if (invalidFence[cursor]) {
                while (cursor < length && text[cursor] == '`') cursor++
            } else if (!escaped[cursor] && text.startsWith(CODE_FENCE, cursor)) {
                listContentColumns.clear()
                addPlain(cursor)
                var afterFence = cursor + CODE_FENCE.length
                while (afterFence < length && text[afterFence] == '`') afterFence++
                val fenceLength = afterFence - cursor
                val newline = nextNewline[afterFence]
                val headerEnd = if (newline < 0) length else newline
                var scan = afterFence
                var inlineClose = -1
                var inlineCloseEnd = -1
                while (scan < headerEnd) {
                    if (text[scan] != '`') {
                        scan++
                    } else {
                        val runStart = scan
                        while (scan < headerEnd && text[scan] == '`') scan++
                        if (scan - runStart >= fenceLength) {
                            inlineClose = runStart
                            inlineCloseEnd = scan
                            break
                        }
                    }
                }
                if (inlineClose >= 0) {
                    val next = inlineCloseEnd
                    result.add(Segment(Kind.CODE, cursor, next, afterFence, inlineClose,
                        unclosed = streaming && next == length))
                    cursor = next
                } else if (newline < 0) {
                    result.add(Segment(Kind.CODE, cursor, length, length, length,
                        text.substring(afterFence).trim().ifEmpty { null }, unclosed = true))
                    cursor = length
                } else {
                    val language = text.substring(afterFence, newline).trim().ifEmpty { null }
                    val start = newline + 1
                    var lineStart = start
                    var close = -1
                    var closeEnd = -1
                    var pendingClose = false
                    while (lineStart < length) {
                        var pos = lineStart
                        while (pos < length && text[pos] == ' ') pos++
                        if (pos - lineStart <= 3 && pos < length && text[pos] == '`') {
                            var tail = pos
                            while (tail < length && text[tail] == '`') tail++
                            val runEnd = tail
                            // A short trailing run may still become a closer in the next chunk.
                            if (streaming && runEnd == length && runEnd - pos < fenceLength) {
                                close = pos
                                pendingClose = true
                                break
                            }
                            while (tail < length && (text[tail] == ' ' || text[tail] == '\t' || text[tail] == '\r')) tail++
                            // A suffix such as ```Done is ambiguous; preserve it as code,
                            // including any following tags, until a valid closer is found.
                            if (runEnd - pos >= fenceLength && (tail == length || text[tail] == '\n')) {
                                close = pos
                                closeEnd = runEnd
                                pendingClose = streaming && tail == length
                                break
                            }
                        }
                        val nextLine = text.indexOf('\n', lineStart)
                        if (nextLine < 0) break
                        lineStart = nextLine + 1
                    }
                    var end = if (close < 0) length else lineStart
                    if (close >= 0 && end > start && text[end - 1] == '\n') {
                        end--
                        if (end > start && text[end - 1] == '\r') end--
                    }
                    val next = if (close < 0 || pendingClose) length else closeEnd
                    result.add(Segment(Kind.CODE, cursor, next, start, end, language, close < 0 || pendingClose))
                    cursor = next
                }
                plainStart = cursor
            } else if (text[cursor] == '`' && !escaped[cursor]) {
                val size = if (cursor + 1 < length && text[cursor + 1] == '`') 2 else 1
                val close = ticks[size - 1][cursor + size]
                val newline = nextNewline[cursor + size]
                cursor = when {
                    close >= 0 && (newline < 0 || close < newline) -> close + size
                    // An unmatched delimiter is not evidence that tags are literal code.
                    // Keep tag handling identical while streaming and after completion.
                    else -> cursor + size
                }
            } else {
                cursor++
            }
        }
        addPlain(length)
        return result
    }

    private fun isTagPrefix(text: String, start: Int, tag: String): Boolean {
        val remaining = text.length - start
        return remaining > 0 && remaining < tag.length &&
            text.regionMatches(start, tag, 0, remaining, ignoreCase = true)
    }

    fun parse(text: String, isStreaming: Boolean = false): List<ChatContentBlock> {
        if (text.isEmpty()) {
            return if (isStreaming) listOf(ChatContentBlock.ParagraphBlock(AnnotatedString(""))) else emptyList()
        }
        return segments(text, isStreaming).mapNotNull { segment ->
            when (segment.kind) {
                Kind.TEXT -> trimBoundary(text.substring(segment.start, segment.end),
                    segment.start == 0 || text[segment.start - 1] == '\n').takeIf { it.isNotBlank() }
                    ?.let { ChatContentBlock.ParagraphBlock(parseInline(formatLists(it))) }
                Kind.THINKING -> ChatContentBlock.ThinkingBlock(
                    text.substring(segment.contentStart, segment.contentEnd).trim(), segment.unclosed && isStreaming,
                )
                Kind.CODE -> ChatContentBlock.CodeBlock(
                    segment.language, text.substring(segment.contentStart, segment.contentEnd), segment.unclosed && isStreaming,
                )
                Kind.INDENTED_CODE -> ChatContentBlock.CodeBlock(null, buildString {
                    var line = segment.start
                    var first = true
                    while (line < segment.end) {
                        val newline = text.indexOf('\n', line).let { if (it < 0 || it >= segment.end) segment.end else it }
                        var end = newline
                        if (end > line && text[end - 1] == '\r') end--
                        if (!first) append('\n')
                        first = false
                        // Measure tab stops in the original line. Keep a tab only if it
                        // spans the same number of columns after dedenting.
                        var content = line
                        var columns = 0L
                        var outputColumns = 0L
                        while (content < end && (text[content] == ' ' || text[content] == '\t')) {
                            val tab = text[content] == '\t'
                            val next = if (tab) columns + 4L - columns % 4L else columns + 1L
                            if (next > segment.indentColumns) {
                                val remaining = next - maxOf(columns, segment.indentColumns)
                                if (tab && columns >= segment.indentColumns && remaining == 4L - outputColumns % 4L) {
                                    append('\t')
                                } else {
                                    repeat(remaining.toInt()) { append(' ') }
                                }
                                outputColumns += remaining
                            }
                            columns = next
                            content++
                        }
                        if (columns >= segment.indentColumns) {
                            append(text, content, end)
                        }
                        line = if (newline < segment.end) newline + 1 else segment.end
                    }
                }, segment.unclosed && isStreaming)
                Kind.PARTIAL_TAG -> null
            }
        }
    }

    /** Removes exactly the thinking segments recognized by the non-streaming display parser. */
    fun stripThinking(text: String): String = buildString {
        for (segment in segments(text, streaming = false)) {
            if (segment.kind != Kind.THINKING) append(text, segment.start, segment.end)
        }
    }.let { trimBoundary(it) }

    /**
     * Replaces list prefixes such as `- ` or `* ` at line starts with bullet `• `.
     */
    internal fun formatLists(text: String): String {
        if (!text.contains("- ") && !text.contains("* ")) return text
        val lines = text.lines()
        val sb = java.lang.StringBuilder(text.length + lines.size * 2)
        for (i in lines.indices) {
            val line = lines[i]
            val trimmed = line.trimStart()
            val indent = line.substring(0, line.length - trimmed.length)
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                sb.append(indent).append("• ").append(trimmed.substring(2))
            } else {
                sb.append(line)
            }
            if (i < lines.size - 1) {
                sb.append('\n')
            }
        }
        return sb.toString()
    }

    /**
     * Parses inline markdown styles (inline code, bold, italic, bold+italic) into [AnnotatedString].
     */
    fun parseInlineMarkdown(rawText: String): AnnotatedString = parseInline(formatLists(rawText))

    // Nested styles receive already formatted text: list formatting belongs to the paragraph only.
    private fun parseInline(text: String): AnnotatedString {
        val builder = AnnotatedString.Builder()
        var i = 0
        val len = text.length
        val escaped = escapedBackticks(text)
        val nextNewline = IntArray(len + 1) { -1 }
        val ticks = inlineTickRuns(text)
        val nextDouble = IntArray(len + 1) { -1 }
        val nextTriple = IntArray(len + 1) { -1 }
        val nextSingle = IntArray(len + 1) { -1 }
        for (pos in len - 1 downTo 0) {
            nextNewline[pos] = if (text[pos] == '\n') pos else nextNewline[pos + 1]
        }
        val inCode = BooleanArray(len)
        var scan = 0
        while (scan < len) {
            if (text[scan] != '`' || escaped[scan]) {
                scan++
                continue
            }
            // Triple runs left in a paragraph are literal, not inline code spans.
            if (text.startsWith(CODE_FENCE, scan)) {
                while (scan < len && text[scan] == '`') scan++
                continue
            }
            val start = scan
            val size = if (start + 1 < len && text[start + 1] == '`') 2 else 1
            val lineEnd = nextNewline[start + size].let { if (it < 0) len else it }
            val close = ticks[size - 1][start + size]
            val end = when {
                close >= 0 && close < lineEnd -> close + size
                else -> start + size
            }
            if (end > start + 1) for (pos in start until end) inCode[pos] = true
            scan = end
        }
        for (pos in len - 1 downTo 0) {
            nextDouble[pos] = if (!inCode[pos] && text.startsWith("**", pos)) pos else nextDouble[pos + 1]
            nextTriple[pos] = if (!inCode[pos] && text.startsWith("***", pos)) pos else nextTriple[pos + 1]
            nextSingle[pos] = if (!inCode[pos] && text[pos] == '*' && (pos == 0 || text[pos - 1] != '*') &&
                (pos + 1 == len || text[pos + 1] != '*')) pos else nextSingle[pos + 1]
        }

        while (i < len) {
            val c = text[i]

            // Inline code: `code`
            if (c == '`' && !escaped[i]) {
                if (text.startsWith(CODE_FENCE, i)) {
                    while (i < len && text[i] == '`') {
                        builder.append('`')
                        i++
                    }
                    continue
                }
                val size = if (i + 1 < len && text[i + 1] == '`') 2 else 1
                val close = ticks[size - 1][i + size]
                if (close != -1 && (nextNewline[i + size] == -1 || nextNewline[i + size] > close)) {
                    val code = text.substring(i + size, close)
                    val start = builder.length
                    builder.append(code)
                    builder.addStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color(0x1F888888),
                        ),
                        start,
                        builder.length,
                    )
                    i = close + size
                    continue
                }
                repeat(size) { builder.append('`') }
                i += size
                continue
            }

            if (inCode[i]) {
                builder.append(c)
                i++
                continue
            }

            // Bold & Italic: ***text***
            if (text.startsWith("***", i) && i + 3 < len && !text[i + 3].isWhitespace()) {
                val close = nextTriple[i + 3]
                if (close != -1 && !text[close - 1].isWhitespace() &&
                    (nextNewline[i] == -1 || nextNewline[i] > close)) {
                    val content = text.substring(i + 3, close)
                    if (!content.contains('\n') && content.isNotBlank()) {
                        val start = builder.length
                        builder.append(parseInline(content))
                        builder.addStyle(
                            SpanStyle(
                                fontWeight = FontWeight.Bold,
                                fontStyle = FontStyle.Italic,
                            ),
                            start,
                            builder.length,
                        )
                        i = close + 3
                        continue
                    }
                }
            }

            // Bold: **text**
            if (text.startsWith("**", i) && i + 2 < len && !text[i + 2].isWhitespace()) {
                val close = nextDouble[i + 2]
                if (close != -1 && !text[close - 1].isWhitespace() &&
                    (nextNewline[i] == -1 || nextNewline[i] > close)) {
                    val content = text.substring(i + 2, close)
                    if (!content.contains('\n') && content.isNotBlank()) {
                        val start = builder.length
                        builder.append(parseInline(content))
                        builder.addStyle(
                            SpanStyle(fontWeight = FontWeight.Bold),
                            start,
                            builder.length,
                        )
                        i = close + 2
                        continue
                    }
                }
            }

            // Italic: *text*
            if (c == '*' && i + 1 < len && !text[i + 1].isWhitespace() && text[i + 1] != '*') {
                val close = nextSingle[i + 1]
                if (close != -1 && !text[close - 1].isWhitespace() &&
                    (nextNewline[i] == -1 || nextNewline[i] > close)) {
                    val content = text.substring(i + 1, close)
                    if (!content.contains('\n') && content.isNotBlank()) {
                        val start = builder.length
                        builder.append(parseInline(content))
                        builder.addStyle(
                            SpanStyle(fontStyle = FontStyle.Italic),
                            start,
                            builder.length,
                        )
                        i = close + 1
                        continue
                    }
                }
            }

            builder.append(c)
            i++
        }

        return builder.toAnnotatedString()
    }
}
