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

    private val THINK_TAG_NAMES = listOf("thinking", "thought", "think")
    private const val CODE_FENCE = "```"

    private data class OpenThinkTag(val tagName: String, val tagEnd: Int)

    private fun matchOpenThinkTag(text: String, pos: Int, targetTagName: String? = null): OpenThinkTag? {
        if (pos >= text.length || text[pos] != '<') return null
        val matchedName = if (targetTagName != null) {
            if (text.regionMatches(pos + 1, targetTagName, 0, targetTagName.length, ignoreCase = true)) {
                targetTagName
            } else {
                return null
            }
        } else {
            THINK_TAG_NAMES.firstOrNull { name ->
                text.regionMatches(pos + 1, name, 0, name.length, ignoreCase = true)
            } ?: return null
        }
        val afterTag = pos + 1 + matchedName.length
        if (afterTag == text.length) return null
        return when {
            text[afterTag] == '>' -> OpenThinkTag(matchedName, afterTag + 1)
            text[afterTag].isWhitespace() -> {
                val gt = text.indexOf('>', afterTag + 1)
                if (gt >= 0) OpenThinkTag(matchedName, gt + 1) else null
            }
            else -> null
        }
    }

    private fun matchCloseThinkTag(text: String, pos: Int, targetTagName: String): Int? {
        if (!text.startsWith("</", pos)) return null
        if (!text.regionMatches(pos + 2, targetTagName, 0, targetTagName.length, ignoreCase = true)) return null
        var p = pos + 2 + targetTagName.length
        while (p < text.length && text[p].isWhitespace()) p++
        return if (p < text.length && text[p] == '>') p + 1 else null
    }

    private fun isOpenThinkTagPrefix(text: String, pos: Int): Boolean {
        if (pos >= text.length || text[pos] != '<') return false
        val sub = text.substring(pos)
        if (sub.contains('>')) return false
        for (name in THINK_TAG_NAMES) {
            val openPrefix = "<$name"
            if (openPrefix.startsWith(sub, ignoreCase = true)) return true
            if (sub.length > openPrefix.length &&
                sub.regionMatches(0, openPrefix, 0, openPrefix.length, ignoreCase = true) &&
                sub[openPrefix.length].isWhitespace()) {
                return true
            }
        }
        return false
    }

    private fun isCloseThinkTagPrefix(text: String, pos: Int, tagName: String): Boolean {
        if (pos >= text.length || text[pos] != '<') return false
        val sub = text.substring(pos)
        if (sub.contains('>')) return false
        val closePrefix = "</$tagName"
        if (closePrefix.startsWith(sub, ignoreCase = true)) return true
        if (sub.length >= closePrefix.length &&
            sub.regionMatches(0, closePrefix, 0, closePrefix.length, ignoreCase = true)) {
            return (closePrefix.length until sub.length).all { sub[it].isWhitespace() }
        }
        return false
    }

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
    private fun listContentColumn(text: String, marker: Int, indent: Long, markerLength: Int = 1): Long {
        val base = indent + markerLength
        var column = base
        var pos = marker + markerLength
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
            var listMarkerLength = 0
            if (atLineStart && !blankLine) {
                var d = lineMarker
                while (d < length && text[d].isDigit()) d++
                val numDigits = d - lineMarker
                if (numDigits in 1..9 && d + 1 < length && text[d] == '.' && (text[d + 1] == ' ' || text[d + 1] == '\t')) {
                    listMarkerLength = numDigits + 1
                } else if (lineMarker + 1 < length &&
                    (text[lineMarker] == '-' || text[lineMarker] == '*' || text[lineMarker] == '+') &&
                    (text[lineMarker + 1] == ' ' || text[lineMarker + 1] == '\t')) {
                    val markerChar = text[lineMarker]
                    val lineEnd = nextNewline[lineMarker].let { if (it < 0) length else it }
                    var markerCount = 0
                    var onlyMarkersAndSpaces = true
                    for (idx in lineMarker until lineEnd) {
                        val ch = text[idx]
                        if (ch == markerChar) {
                            markerCount++
                        } else if (ch != ' ' && ch != '\t' && ch != '\r') {
                            onlyMarkersAndSpaces = false
                            break
                        }
                    }
                    if (!(onlyMarkersAndSpaces && markerCount >= 3) && !onlyMarkersAndSpaces) {
                        listMarkerLength = 1
                    }
                }
            }
            val listItem = listMarkerLength > 0
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
                    listContentColumns.add(listContentColumn(text, lineMarker, indentColumns, listMarkerLength))
                } else if (!listContinuation) {
                    listContentColumns.clear()
                }
            }
            val openMatch = if (text[cursor] == '<') matchOpenThinkTag(text, cursor) else null
            if (openMatch != null) {
                listContentColumns.clear()
                addPlain(cursor)
                val tagName = openMatch.tagName
                val contentStart = openMatch.tagEnd
                var depth = 1
                var scan = contentStart
                var closeTagStart = -1
                var closeTagEnd = -1

                while (scan < length) {
                    val nextLt = text.indexOf('<', scan)
                    if (nextLt == -1) break
                    val innerOpen = matchOpenThinkTag(text, nextLt, targetTagName = tagName)
                    if (innerOpen != null) {
                        depth++
                        scan = innerOpen.tagEnd
                        continue
                    }
                    val innerClose = matchCloseThinkTag(text, nextLt, targetTagName = tagName)
                    if (innerClose != null) {
                        depth--
                        if (depth == 0) {
                            closeTagStart = nextLt
                            closeTagEnd = innerClose
                            break
                        } else {
                            scan = innerClose
                            continue
                        }
                    }
                    scan = nextLt + 1
                }

                if (closeTagStart >= 0) {
                    val next = closeTagEnd
                    result.add(Segment(Kind.THINKING, cursor, next, contentStart, closeTagStart, unclosed = false))
                    cursor = next
                    plainStart = cursor
                } else {
                    var end = length
                    if (streaming) {
                        val lastLt = text.lastIndexOf('<')
                        if (lastLt >= contentStart && isCloseThinkTagPrefix(text, lastLt, tagName)) {
                            end = lastLt
                        }
                    }
                    result.add(Segment(Kind.THINKING, cursor, length, contentStart, end, unclosed = true))
                    cursor = length
                    plainStart = cursor
                }
            } else if (streaming && isOpenThinkTagPrefix(text, cursor)) {
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
     * Replaces unordered list prefixes such as `- `, `* `, or `+ ` at line starts with bullet `• `.
     */
    private fun formatLists(text: String): String {
        if (!text.contains("- ") && !text.contains("* ") && !text.contains("+ ")) return text
        val lines = text.lines()
        val sb = java.lang.StringBuilder(text.length + lines.size * 2)
        for (i in lines.indices) {
            val line = lines[i]
            val trimmed = line.trimStart()
            val indent = line.substring(0, line.length - trimmed.length)
            val isBullet = when {
                trimmed.startsWith("- ") || trimmed.startsWith("+ ") -> {
                    val nonSpace = trimmed.filter { it != ' ' && it != '\t' && it != '\r' }
                    !(nonSpace.all { it == trimmed[0] } && nonSpace.length >= 3) && trimmed.substring(2).isNotBlank()
                }
                trimmed.startsWith("* ") -> {
                    val nonSpace = trimmed.filter { it != ' ' && it != '\t' && it != '\r' }
                    !(nonSpace.all { it == '*' } && nonSpace.length >= 3) && trimmed.substring(2).isNotBlank()
                }
                else -> false
            }
            if (isBullet) {
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
        val nextAsteriskCloser = IntArray(len + 1) { -1 }
        for (pos in len - 1 downTo 0) {
            nextDouble[pos] = if (!inCode[pos] && text.startsWith("**", pos)) pos else nextDouble[pos + 1]
            nextTriple[pos] = if (!inCode[pos] && text.startsWith("***", pos)) pos else nextTriple[pos + 1]
            nextSingle[pos] = if (!inCode[pos] && text[pos] == '*' && (pos == 0 || text[pos - 1] != '*') &&
                (pos + 1 == len || text[pos + 1] != '*')) pos else nextSingle[pos + 1]
            nextAsteriskCloser[pos] = if (!inCode[pos] && text[pos] == '*' && (pos == 0 || text[pos - 1] != '*') &&
                pos > 0 && !text[pos - 1].isWhitespace()) pos else nextAsteriskCloser[pos + 1]
        }
        // Track unmatched openers only within the opposing delimiter boundaries.
        // A previous bold span's orphan italic must not affect the next span.
        // Indexing both directions permits shared triple closers without rescans.
        val openItalic = IntArray(len) { -1 }
        val openBold = IntArray(len) { -1 }
        val openTriple = IntArray(len) { -1 }
        var italic = -1
        var bold = -1
        var triple = -1
        var pos = 0
        while (pos < len) {
            if (text[pos] == '\n') {
                italic = -1
                bold = -1
                triple = -1
            }
            openItalic[pos] = italic
            openBold[pos] = bold
            openTriple[pos] = triple
            if (inCode[pos] || text[pos] != '*') {
                pos++
                continue
            }
            val start = pos
            while (pos < len && text[pos] == '*' && !inCode[pos]) pos++
            val size = pos - start
            if (size == 1) {
                bold = -1
                triple = -1
                if (italic >= 0 && start > 0 && !text[start - 1].isWhitespace()) {
                    italic = -1
                } else if (pos < len && !text[pos].isWhitespace()) {
                    italic = start
                }
            } else if (size == 2) {
                italic = -1
                triple = -1
                bold = if (bold >= 0 && start > 0 && !text[start - 1].isWhitespace()) {
                    -1
                } else if (pos < len && !text[pos].isWhitespace()) {
                    start
                } else {
                    -1
                }
            } else if (size == 3) {
                italic = -1
                bold = -1
                triple = if (triple >= 0 && start > 0 && !text[start - 1].isWhitespace()) {
                    -1
                } else if (pos < len && !text[pos].isWhitespace()) {
                    start
                } else {
                    -1
                }
            } else {
                italic = -1
                bold = -1
                triple = -1
            }
        }
        // Index only triple runs that can close an earlier bold opener. Looking
        // up the first triple of any kind would let an unrelated ***both***
        // hide a later shared closer; scanning past it per opener is quadratic.
        val nextBoldItalicClose = IntArray(len + 1) { -1 }
        for (index in len - 1 downTo 0) {
            val opener = openBold[index]
            nextBoldItalicClose[index] = if (!inCode[index] && opener >= 0 && index > opener + 2 &&
                text.startsWith("***", index) && (index == 0 || text[index - 1] != '*') &&
                (index + 3 == len || text[index + 3] != '*') &&
                !text[index - 1].isWhitespace()) index else nextBoldItalicClose[index + 1]
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
                var close = nextDouble[i + 2]
                if (close >= 0 && text.startsWith("***", close) &&
                    (close + 3 == len || text[close + 3] != '*') &&
                    openItalic[close] >= i + 2 && close > openItalic[close] + 1 &&
                    !text[close - 1].isWhitespace()) {
                    val line = nextNewline[i].let { if (it < 0) len else it }
                    val nextBold = nextDouble[close + 3]
                    if (nextBold in (close + 3) until line && !text[nextBold - 1].isWhitespace()) {
                        close = nextBold
                    } else {
                        close++
                    }
                }
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
                val lineEnd = nextNewline[i].let { if (it < 0) len else it }
                var close = -1
                var scanCloser = nextAsteriskCloser[i + 1]
                fun hasStarsOrTicks(from: Int, to: Int): Boolean {
                    for (idx in from until to) {
                        if (text[idx] == '*' || text[idx] == '`') return true
                    }
                    return false
                }
                while (scanCloser in (i + 1) until lineEnd) {
                    var runLen = 0
                    while (scanCloser + runLen < len && text[scanCloser + runLen] == '*' && !inCode[scanCloser + runLen]) {
                        runLen++
                    }
                    if (runLen == 1) {
                        close = scanCloser
                        break
                    } else if (runLen == 2) {
                        if (!hasStarsOrTicks(i + 1, scanCloser)) {
                            close = scanCloser
                            break
                        }
                    } else if (runLen == 3) {
                        if (openBold[scanCloser] >= i + 1) {
                            close = scanCloser + 2
                            break
                        } else if (!hasStarsOrTicks(i + 1, scanCloser)) {
                            close = scanCloser
                            break
                        }
                    } else {
                        if (!hasStarsOrTicks(i + 1, scanCloser)) {
                            close = scanCloser
                            break
                        }
                    }
                    scanCloser = nextAsteriskCloser[scanCloser + runLen]
                }
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
