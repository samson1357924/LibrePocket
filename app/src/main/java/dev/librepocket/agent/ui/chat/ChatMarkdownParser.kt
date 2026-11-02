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

    private fun thinkTagId(name: String): Int = when (name.lowercase()) {
        "thinking" -> 0
        "thought" -> 1
        "think" -> 2
        else -> -1
    }

    private data class OpenThinkTag(
        val tagName: String,
        val tagEnd: Int,
        val isSelfClosing: Boolean = false,
    )

    private data class CloseThinkTag(
        val tagName: String,
        val tagEnd: Int,
    )

    private fun isXmlNameStartChar(c: Char): Boolean =
        (c in 'a'..'z') || (c in 'A'..'Z') || c == '_' || c == ':'

    private fun isXmlNameChar(c: Char): Boolean =
        isXmlNameStartChar(c) || (c in '0'..'9') || c == '-' || c == '.'

    private fun validateAttributes(rawAttrs: String): Boolean {
        if (rawAttrs.isBlank()) return true
        var i = 0
        val len = rawAttrs.length
        while (i < len) {
            while (i < len && (rawAttrs[i] == ' ' || rawAttrs[i] == '\t')) i++
            if (i == len) break

            if (!isXmlNameStartChar(rawAttrs[i])) return false
            i++
            while (i < len && isXmlNameChar(rawAttrs[i])) i++

            while (i < len && (rawAttrs[i] == ' ' || rawAttrs[i] == '\t')) i++
            if (i >= len || rawAttrs[i] != '=') return false
            i++

            while (i < len && (rawAttrs[i] == ' ' || rawAttrs[i] == '\t')) i++
            if (i >= len) return false

            val quote = rawAttrs[i]
            if (quote == '"' || quote == '\'') {
                i++
                val quoteEnd = rawAttrs.indexOf(quote, i)
                if (quoteEnd == -1) return false
                i = quoteEnd + 1
            } else {
                val valStart = i
                while (i < len && !rawAttrs[i].isWhitespace() && rawAttrs[i] != '"' && rawAttrs[i] != '\'' && rawAttrs[i] != '=' && rawAttrs[i] != '<' && rawAttrs[i] != '>' && rawAttrs[i] != '`') {
                    i++
                }
                if (i == valStart) return false
            }
        }
        return true
    }

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
        if (afterTag >= text.length) return null
        val firstChar = text[afterTag]
        if (firstChar != '>' && firstChar != '/' && firstChar != ' ' && firstChar != '\t') {
            return null
        }

        val maxScan = minOf(text.length, afterTag + 512)
        var gt = -1
        var p = afterTag
        var inQuote = '\u0000'
        while (p < maxScan) {
            val ch = text[p]
            if (ch == '\n' || ch == '\r') return null
            if (inQuote != '\u0000') {
                if (ch == inQuote) {
                    inQuote = '\u0000'
                }
            } else {
                if (ch == '<') return null
                if (ch == '"' || ch == '\'') {
                    inQuote = ch
                } else if (ch == '>') {
                    gt = p
                    break
                }
            }
            p++
        }
        if (gt == -1 || inQuote != '\u0000') return null

        var s = gt - 1
        while (s >= afterTag && (text[s] == ' ' || text[s] == '\t')) s--
        val isSelfClosing = s >= afterTag && text[s] == '/'
        val attrEnd = if (isSelfClosing) s else gt
        val rawAttrs = text.substring(afterTag, attrEnd)
        if (!validateAttributes(rawAttrs)) return null

        return OpenThinkTag(matchedName, gt + 1, isSelfClosing)
    }

    private fun matchCloseThinkTag(text: String, pos: Int, targetTagName: String? = null): CloseThinkTag? {
        if (!text.startsWith("</", pos)) return null
        val matchedName = if (targetTagName != null) {
            if (text.regionMatches(pos + 2, targetTagName, 0, targetTagName.length, ignoreCase = true)) {
                targetTagName
            } else {
                return null
            }
        } else {
            THINK_TAG_NAMES.firstOrNull { name ->
                text.regionMatches(pos + 2, name, 0, name.length, ignoreCase = true)
            } ?: return null
        }
        val afterTag = pos + 2 + matchedName.length
        var p = afterTag
        while (p < text.length && (text[p] == ' ' || text[p] == '\t')) {
            p++
        }
        if (p < text.length && text[p] == '>') {
            return CloseThinkTag(matchedName, p + 1)
        }
        return null
    }

    private fun isOpenThinkTagPrefix(text: String, pos: Int): Boolean {
        if (pos >= text.length || text[pos] != '<') return false
        val rem = text.length - pos
        if (rem > 32) return false
        for (i in pos until text.length) {
            val ch = text[i]
            if (ch == '>' || ch == '\n' || ch == '\r') return false
        }
        for (name in THINK_TAG_NAMES) {
            val prefixLen = 1 + name.length
            if (rem <= prefixLen) {
                if (text.regionMatches(pos + 1, name, 0, rem - 1, ignoreCase = true)) {
                    return true
                }
            } else {
                if (text.regionMatches(pos + 1, name, 0, name.length, ignoreCase = true)) {
                    val nextChar = text[pos + prefixLen]
                    if (nextChar.isWhitespace() || nextChar == '/') {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun isCloseThinkTagPrefix(text: String, pos: Int, targetTagName: String? = null): Boolean {
        if (pos >= text.length || text[pos] != '<') return false
        val rem = text.length - pos
        if (rem > 32) return false
        for (i in pos until text.length) {
            val ch = text[i]
            if (ch == '>' || ch == '\n' || ch == '\r') return false
        }
        if (rem == 1) return true
        if (text[pos + 1] != '/') return false

        if (targetTagName != null) {
            return isCloseTagNamePrefix(text, pos, rem, targetTagName)
        }
        for (name in THINK_TAG_NAMES) {
            if (isCloseTagNamePrefix(text, pos, rem, name)) return true
        }
        return false
    }

    private fun isCloseTagNamePrefix(text: String, pos: Int, rem: Int, name: String): Boolean {
        val closePrefixLen = 2 + name.length
        if (rem <= closePrefixLen) {
            return text.regionMatches(pos + 2, name, 0, rem - 2, ignoreCase = true)
        }
        if (!text.regionMatches(pos + 2, name, 0, name.length, ignoreCase = true)) {
            return false
        }
        for (i in (pos + closePrefixLen) until text.length) {
            if (text[i] != ' ' && text[i] != '\t') return false
        }
        return true
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

    private fun isRangeBlank(text: String, start: Int, end: Int): Boolean {
        val s = maxOf(0, start)
        val e = minOf(text.length, end)
        for (i in s until e) {
            if (!text[i].isWhitespace()) return false
        }
        return true
    }

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
        var hasSeenOpenThinkTag = false
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
                isRangeBlank(text, prevStart, cursor - 1)
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
            val orphanCloseMatch = if (openMatch == null && !hasSeenOpenThinkTag && result.isEmpty() && text[cursor] == '<') {
                matchCloseThinkTag(text, cursor)
            } else {
                null
            }
            if (openMatch != null) {
                hasSeenOpenThinkTag = true
                listContentColumns.clear()
                addPlain(cursor)
                if (openMatch.isSelfClosing) {
                    result.add(Segment(Kind.THINKING, cursor, openMatch.tagEnd, openMatch.tagEnd, openMatch.tagEnd, unclosed = false))
                    cursor = openMatch.tagEnd
                    plainStart = cursor
                    continue
                }
                val contentStart = openMatch.tagEnd
                val tagStack = ArrayList<Int>()
                val tagCounts = IntArray(3)
                val rootId = thinkTagId(openMatch.tagName)
                if (rootId >= 0) {
                    tagStack.add(rootId)
                    tagCounts[rootId]++
                }
                var scan = contentStart
                var closeTagStart = -1
                var closeTagEnd = -1

                while (scan < length) {
                    val nextLt = text.indexOf('<', scan)
                    if (nextLt == -1) break
                    val innerOpen = matchOpenThinkTag(text, nextLt)
                    if (innerOpen != null) {
                        if (!innerOpen.isSelfClosing) {
                            val openId = thinkTagId(innerOpen.tagName)
                            if (openId >= 0) {
                                tagStack.add(openId)
                                tagCounts[openId]++
                            }
                        }
                        scan = innerOpen.tagEnd
                        continue
                    }
                    val innerClose = matchCloseThinkTag(text, nextLt)
                    if (innerClose != null) {
                        val closeId = thinkTagId(innerClose.tagName)
                        if (closeId >= 0 && tagCounts[closeId] > 0) {
                            if (tagStack.isNotEmpty() && tagStack.last() == closeId) {
                                tagStack.removeAt(tagStack.lastIndex)
                                tagCounts[closeId]--
                                if (tagStack.isEmpty()) {
                                    closeTagStart = nextLt
                                    closeTagEnd = innerClose.tagEnd
                                    break
                                }
                            } else {
                                val idx = tagStack.lastIndexOf(closeId)
                                if (idx != -1) {
                                    tagStack.removeAt(idx)
                                    tagCounts[closeId]--
                                    if (tagStack.isEmpty()) {
                                        closeTagStart = nextLt
                                        closeTagEnd = innerClose.tagEnd
                                        break
                                    }
                                }
                            }
                        }
                        scan = innerClose.tagEnd
                        continue
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
                        if (lastLt >= contentStart && isCloseThinkTagPrefix(text, lastLt)) {
                            end = lastLt
                        }
                    }
                    result.add(Segment(Kind.THINKING, cursor, length, contentStart, end, unclosed = true))
                    cursor = length
                    plainStart = cursor
                }
            } else if (orphanCloseMatch != null) {
                hasSeenOpenThinkTag = true
                listContentColumns.clear()
                result.add(Segment(Kind.THINKING, start = 0, end = orphanCloseMatch.tagEnd, contentStart = 0, contentEnd = cursor, unclosed = false))
                cursor = orphanCloseMatch.tagEnd
                plainStart = cursor
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

    private fun isSuspectOpenThinkTag(text: String, pos: Int): Boolean {
        if (pos >= text.length || text[pos] != '<') return false
        for (name in THINK_TAG_NAMES) {
            val next = pos + 1 + name.length
            if (text.regionMatches(pos + 1, name, 0, name.length, ignoreCase = true)) {
                if (next < text.length && (text[next] == ' ' || text[next] == '\t' || text[next] == '/' || text[next] == '>')) {
                    return true
                }
            }
        }
        return false
    }

    private fun stripMalformedThinkingTags(text: String): String {
        val len = text.length
        if (len == 0) return ""
        val escaped = escapedBackticks(text)
        val ticks = inlineTickRuns(text)
        val nextNewline = IntArray(len + 1) { -1 }
        for (i in len - 1 downTo 0) {
            nextNewline[i] = if (text[i] == '\n') i else nextNewline[i + 1]
        }

        val sb = java.lang.StringBuilder(len)
        var i = 0
        while (i < len) {
            if (text[i] == '`' && !escaped[i]) {
                if (text.startsWith(CODE_FENCE, i)) {
                    var fenceEnd = i + CODE_FENCE.length
                    while (fenceEnd < len && text[fenceEnd] == '`') fenceEnd++
                    val fenceLen = fenceEnd - i
                    val nextFence = text.indexOf(text.substring(i, fenceEnd), fenceEnd)
                    val close = if (nextFence >= 0) nextFence + fenceLen else len
                    sb.append(text, i, close)
                    i = close
                    continue
                }
                val size = if (i + 1 < len && text[i + 1] == '`') 2 else 1
                val close = ticks[size - 1][i + size]
                val newline = nextNewline[i + size]
                if (close >= 0 && (newline < 0 || close < newline)) {
                    sb.append(text, i, close + size)
                    i = close + size
                    continue
                }
            }

            if (text[i] == '<' && isSuspectOpenThinkTag(text, i)) {
                var scan = i + 1
                var depth = 1
                var closeTagEnd = -1
                while (scan < len) {
                    val nextLt = text.indexOf('<', scan)
                    if (nextLt == -1) break
                    if (isSuspectOpenThinkTag(text, nextLt)) {
                        depth++
                        scan = nextLt + 1
                        continue
                    }
                    val close = matchCloseThinkTag(text, nextLt)
                    if (close != null) {
                        depth--
                        if (depth == 0) {
                            closeTagEnd = close.tagEnd
                            break
                        }
                        scan = close.tagEnd
                        continue
                    }
                    scan = nextLt + 1
                }
                if (closeTagEnd >= 0) {
                    i = closeTagEnd
                    continue
                }
            }

            sb.append(text[i])
            i++
        }
        return sb.toString()
    }

    /** Removes exactly the thinking segments recognized by the non-streaming display parser. */
    fun stripThinking(text: String): String = buildString {
        for (segment in segments(text, streaming = false)) {
            when (segment.kind) {
                Kind.THINKING -> Unit
                Kind.TEXT -> append(stripMalformedThinkingTags(text.substring(segment.start, segment.end)))
                else -> append(text, segment.start, segment.end)
            }
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
        val starOrTickCount = IntArray(len + 1)
        for (pos in 0 until len) {
            val ch = text[pos]
            starOrTickCount[pos + 1] = starOrTickCount[pos] + if (ch == '*' || ch == '`') 1 else 0
        }
        val escaped = escapedBackticks(text)
        val nextNewline = IntArray(len + 1) { -1 }
        val ticks = inlineTickRuns(text)
        val nextDouble = IntArray(len + 1) { -1 }
        val nextTriple = IntArray(len + 1) { -1 }
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
        val nextAsteriskRun = IntArray(len + 1) { -1 }
        val nextSingleCloser = IntArray(len + 1) { -1 }
        for (pos in len - 1 downTo 0) {
            nextDouble[pos] = if (!inCode[pos] && text.startsWith("**", pos)) pos else nextDouble[pos + 1]
            nextTriple[pos] = if (!inCode[pos] && text.startsWith("***", pos)) pos else nextTriple[pos + 1]
            nextAsteriskRun[pos] = if (!inCode[pos] && text[pos] == '*' && (pos == 0 || text[pos - 1] != '*')) pos else nextAsteriskRun[pos + 1]
            nextSingleCloser[pos] = if (!inCode[pos] && text[pos] == '*' &&
                (pos == 0 || text[pos - 1] != '*') &&
                (pos + 1 == len || text[pos + 1] != '*') &&
                pos > 0 && !text[pos - 1].isWhitespace()) pos else nextSingleCloser[pos + 1]
        }
        // Track unmatched openers only within the opposing delimiter boundaries.
        // A previous bold span's orphan italic must not affect the next span.
        // Indexing both directions permits shared triple closers without rescans.
        val openItalic = IntArray(len) { -1 }
        val openBold = IntArray(len) { -1 }
        var italic = -1
        var bold = -1
        var pos = 0
        while (pos < len) {
            if (text[pos] == '\n') {
                italic = -1
                bold = -1
            }
            openItalic[pos] = italic
            openBold[pos] = bold
            if (inCode[pos] || text[pos] != '*') {
                pos++
                continue
            }
            val start = pos
            while (pos < len && text[pos] == '*' && !inCode[pos]) pos++
            val size = pos - start
            if (size == 1) {
                bold = -1
                if (italic >= 0 && start > 0 && !text[start - 1].isWhitespace()) {
                    italic = -1
                } else if (pos < len && !text[pos].isWhitespace()) {
                    italic = start
                }
            } else if (size == 2) {
                italic = -1
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
            } else {
                italic = -1
                bold = -1
            }
        }
        val nextSharedTriple = IntArray(len + 1) { -1 }
        for (p in len - 1 downTo 0) {
            nextSharedTriple[p] = if (!inCode[p] && text.startsWith("***", p) &&
                (p + 3 == len || text[p + 3] != '*') &&
                p > 0 && !text[p - 1].isWhitespace() &&
                openBold[p] >= 0) p else nextSharedTriple[p + 1]
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
                fun hasStarsOrTicks(from: Int, to: Int): Boolean =
                    from < to && (starOrTickCount[to] - starOrTickCount[from] > 0)

                val firstRun = nextAsteriskRun[i + 1]
                if (firstRun in (i + 1) until lineEnd && !hasStarsOrTicks(i + 1, firstRun)) {
                    var runLen = 0
                    while (firstRun + runLen < len && text[firstRun + runLen] == '*' && !inCode[firstRun + runLen]) {
                        runLen++
                    }
                    if (firstRun > 0 && !text[firstRun - 1].isWhitespace()) {
                        if (runLen == 1) {
                            close = firstRun
                        } else if (runLen == 2) {
                            close = firstRun
                        } else if (runLen == 3) {
                            close = if (openBold[firstRun] >= i + 1) firstRun + 2 else firstRun
                        } else {
                            close = firstRun
                        }
                    }
                }

                if (close == -1) {
                    val s = nextSingleCloser[i + 1]
                    var t = nextSharedTriple[i + 1]
                    while (t in (i + 1) until lineEnd && openBold[t] < i + 1) {
                        t = nextSharedTriple[t + 3]
                    }
                    val validS = if (s in (i + 1) until lineEnd) s else -1
                    val validT = if (t in (i + 1) until lineEnd && openBold[t] >= i + 1) t + 2 else -1

                    close = when {
                        validS != -1 && validT != -1 -> if (t < validS) validT else validS
                        validS != -1 -> validS
                        validT != -1 -> validT
                        else -> -1
                    }
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
