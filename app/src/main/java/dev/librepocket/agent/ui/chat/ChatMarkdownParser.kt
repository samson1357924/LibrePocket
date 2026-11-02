package dev.librepocket.agent.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Structured content block parsed from chat turn text.
 * Each block provides a [key] structural identity anchor used by Compose
 * to maintain UI state (such as expansion in [ThinkingBlock] or copy feedback in [CodeBlock])
 * across streaming updates and content progress without recreating nodes.
 */
sealed interface ChatContentBlock {
    val key: String

    fun stableKey(index: Int = 0): Any = key

    data class ThinkingBlock(
        val content: String,
        val isStreaming: Boolean,
        override val key: String = "think_0",
    ) : ChatContentBlock

    data class CodeBlock(
        val language: String?,
        val code: String,
        val isStreaming: Boolean,
        override val key: String = "code_0",
    ) : ChatContentBlock

    data class ParagraphBlock(
        val annotatedText: AnnotatedString,
        override val key: String = "para_0",
    ) : ChatContentBlock
}

/**
 * Robust, linear O(N) parser for thinking blocks (`<think>...</think>`),
 * code blocks (```` ```lang ... ``` ````), and inline markdown (bold, italic, inline code, lists).
 * Strictly avoids backtracking regular expressions to ensure immunity against ReDoS attacks.
 *
 * ### Core Invariants
 *
 * 1. **Delimiter Precedence (定界符優先級)**:
 *    - Structural block-level delimiters (fenced code blocks, indented code blocks, and XML-like thinking tags)
 *      have strict precedence over inline delimiters.
 *    - Code blocks (both fenced and indented) shield all enclosed content: thinking tags and inline markdown markers
 *      (`*`, `_`, `` ` ``) are treated as literal text.
 *    - Inline code spans (`` `...` `` or ```` ``...`` ````) shield enclosed content from inline style parsing and XML tags.
 *    - Inline formatting precedence evaluates triple asterisks (`***` bold+italic) first, followed by double (`**` bold)
 *      and single (`*` italic), disambiguating shared triple closers with left-to-right lookahead.
 *
 * 2. **Streaming Cursor Mutual Exclusion (串流游標互斥與單調推進)**:
 *    - The parser progresses text linearly through a monotonically advancing cursor (`cursor++` or segment skips)
 *      with zero backtracking, guaranteeing strict O(N) runtime.
 *    - At each cursor position, candidate token constructs mutually exclude each other: an indented code line,
 *      a thinking tag opener/closer, a fenced code block opener, an inline backtick run, or plain text.
 *    - During streaming (`isStreaming = true`), trailing partial tag prefixes (e.g. `<think`, `</think`)
 *      are classified into [Kind.PARTIAL_TAG] or held back from content emission, preventing transient raw syntax
 *      leakage before subsequent stream chunks arrive.
 *
 * 3. **Bracket Matching & Monotonic Interval Absorption (括號匹配與單調區間吸收)**:
 *    - Nested thinking tags maintain a doubly-linked tag stack with [lastNodeOfKind] pointers for O(1) push,
 *      pop, and middle unlink without array copies or linear stack searches.
 *    - In [stripMalformedThinkingTags], open tag positions are pushed onto a LIFO stack. When a valid closing tag
 *      is matched, the enclosing range `[start, close.tagEnd)` is absorbed. Any previously recorded inner intervals
 *      starting at or after `start` are popped and superseded by the outer enclosing interval in a single monotonic pass.
 *    - If unclosed suspect open tags remain at EOF, all intervals from the earliest unclosed start to EOF are absorbed
 *      under fail-closed handling to prevent thinking content leakage.
 *    - This guarantees strict O(N) linear time with zero quadratic allocation or substring rebuilding overhead.
 */
object ChatMarkdownParser {

    private val THINK_TAG_NAMES = listOf("thinking", "thought", "think")
    private const val CODE_FENCE = "```"

    /** Number of distinct recognized thinking tag types (thinking, thought, think). */
    private const val THINK_TAG_KIND_COUNT = 3

    /** Maximum characters to scan within a tag looking for closing '>' before giving up. */
    private const val MAX_TAG_ATTRIBUTE_SCAN_CHARS = 512

    /** Maximum character buffer length to inspect for trailing partial tag prefixes during streaming. */
    private const val MAX_PARTIAL_TAG_PREFIX_CHARS = 32

    /** Maximum spaces of indentation allowed before a fenced code block opener per CommonMark. */
    private const val MAX_FENCE_INDENT_SPACES = 3

    /** Standard column width for tabs and indented code blocks per CommonMark. */
    private const val INDENT_CODE_COLUMNS = 4L

    private fun thinkTagId(name: String): Int = when (name.lowercase()) {
        "thinking" -> 0
        "thought" -> 1
        "think" -> 2
        else -> -1
    }

    private class TagNode(
        val tagId: Int,
        var prev: TagNode? = null,
        var next: TagNode? = null,
        var prevSameKind: TagNode? = null,
    )

    private class TagTracker {
        private var head: TagNode? = null
        private var tail: TagNode? = null
        private val lastNodeOfKind = arrayOfNulls<TagNode>(THINK_TAG_KIND_COUNT)
        var totalCount: Int = 0
            private set

        fun push(tagId: Int) {
            if (tagId !in 0 until THINK_TAG_KIND_COUNT) return
            val node = TagNode(
                tagId = tagId,
                prev = tail,
                next = null,
                prevSameKind = lastNodeOfKind[tagId],
            )
            if (tail != null) {
                tail!!.next = node
            } else {
                head = node
            }
            tail = node
            lastNodeOfKind[tagId] = node
            totalCount++
        }

        fun pop(tagId: Int): Boolean {
            if (tagId !in 0 until THINK_TAG_KIND_COUNT) return false
            val node = lastNodeOfKind[tagId] ?: return false
            lastNodeOfKind[tagId] = node.prevSameKind

            val p = node.prev
            val n = node.next
            if (p != null) {
                p.next = n
            } else {
                head = n
            }
            if (n != null) {
                n.prev = p
            } else {
                tail = p
            }
            node.prev = null
            node.next = null

            totalCount--
            return totalCount == 0
        }
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

    private data class SuspectOpenTag(
        val isSelfClosing: Boolean,
        val tagEnd: Int,
        val nextScanPos: Int,
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

        val maxScan = minOf(text.length, afterTag + MAX_TAG_ATTRIBUTE_SCAN_CHARS)
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
        if (rem > MAX_PARTIAL_TAG_PREFIX_CHARS) return false
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
        if (rem > MAX_PARTIAL_TAG_PREFIX_CHARS) return false
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
            if ((tab || pos - lineStart > MAX_FENCE_INDENT_SPACES) && text.startsWith(CODE_FENCE, pos)) invalid[pos] = true
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

    private fun indentEnd(text: String, start: Int, required: Long = INDENT_CODE_COLUMNS): Int {
        var pos = start
        var columns = 0L
        while (pos < text.length && columns < required) {
            when (text[pos]) {
                ' ' -> columns++
                '\t' -> columns += INDENT_CODE_COLUMNS - columns % INDENT_CODE_COLUMNS
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
            val next = if (text[pos] == '\t') column + INDENT_CODE_COLUMNS - column % INDENT_CODE_COLUMNS else column + 1L
            if (next - base > INDENT_CODE_COLUMNS) return base + 1L
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

    /**
     * Splits raw input text into non-overlapping, sequential [Segment] records in linear O(N) time.
     *
     * Invariants enforced:
     * - Monotonic cursor progression: cursor advances strictly forward with no backtracking.
     * - Delimiter shielding: code blocks and thinking blocks shield internal characters until closed.
     * - Streaming mutual exclusion: trailing partial tag prefixes are isolated as [Kind.PARTIAL_TAG]
     *   so that unclosed delimiters do not pollute user-visible text while streaming.
     */
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
            val codeIndent = if (previousListItem) listContentColumns.last() + INDENT_CODE_COLUMNS else INDENT_CODE_COLUMNS
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
                val tagTracker = TagTracker()
                val rootId = thinkTagId(openMatch.tagName)
                if (rootId >= 0) {
                    tagTracker.push(rootId)
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
                                tagTracker.push(openId)
                            }
                        }
                        scan = innerOpen.tagEnd
                        continue
                    }
                    val innerClose = matchCloseThinkTag(text, nextLt)
                    if (innerClose != null) {
                        val closeId = thinkTagId(innerClose.tagName)
                        if (closeId >= 0) {
                            if (tagTracker.pop(closeId)) {
                                closeTagStart = nextLt
                                closeTagEnd = innerClose.tagEnd
                                break
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
                        if (pos - lineStart <= MAX_FENCE_INDENT_SPACES && pos < length && text[pos] == '`') {
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

    /**
     * Parses the incoming chat text into a list of [ChatContentBlock] instances.
     *
     * @param text The complete raw text of the message turn received so far.
     * @param isStreaming Whether generation is actively ongoing. When true, unclosed blocks
     *                    are flagged with `isStreaming = true` to allow streaming cursor rendering,
     *                    and trailing partial tags are buffered.
     */
    fun parse(text: String, isStreaming: Boolean = false): List<ChatContentBlock> {
        if (text.isEmpty()) {
            return if (isStreaming) listOf(ChatContentBlock.ParagraphBlock(AnnotatedString(""), key = "para_0")) else emptyList()
        }
        val segments = segments(text, isStreaming)
        var thinkingIndex = 0
        var codeIndex = 0
        var paraIndex = 0

        val blocks = ArrayList<ChatContentBlock>(segments.size)
        for (segment in segments) {
            when (segment.kind) {
                Kind.TEXT -> {
                    val trimmed = trimBoundary(
                        text.substring(segment.start, segment.end),
                        segment.start == 0 || text[segment.start - 1] == '\n',
                    )
                    if (trimmed.isNotBlank()) {
                        blocks.add(
                            ChatContentBlock.ParagraphBlock(
                                annotatedText = parseInline(formatLists(trimmed)),
                                key = "para_${paraIndex++}",
                            )
                        )
                    }
                }
                Kind.THINKING -> {
                    blocks.add(
                        ChatContentBlock.ThinkingBlock(
                            content = text.substring(segment.contentStart, segment.contentEnd).trim(),
                            isStreaming = segment.unclosed && isStreaming,
                            key = "think_${thinkingIndex++}",
                        )
                    )
                }
                Kind.CODE -> {
                    blocks.add(
                        ChatContentBlock.CodeBlock(
                            language = segment.language,
                            code = text.substring(segment.contentStart, segment.contentEnd),
                            isStreaming = segment.unclosed && isStreaming,
                            key = "code_${codeIndex++}",
                        )
                    )
                }
                Kind.INDENTED_CODE -> {
                    val codeContent = buildString {
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
                                val next = if (tab) columns + INDENT_CODE_COLUMNS - columns % INDENT_CODE_COLUMNS else columns + 1L
                                if (next > segment.indentColumns) {
                                    val remaining = next - maxOf(columns, segment.indentColumns)
                                    if (tab && columns >= segment.indentColumns && remaining == INDENT_CODE_COLUMNS - outputColumns % INDENT_CODE_COLUMNS) {
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
                    }
                    blocks.add(
                        ChatContentBlock.CodeBlock(
                            language = null,
                            code = codeContent,
                            isStreaming = segment.unclosed && isStreaming,
                            key = "code_${codeIndex++}",
                        )
                    )
                }
                Kind.PARTIAL_TAG -> Unit
            }
        }
        return blocks
    }

    private fun matchSuspectOpenThinkTag(text: String, pos: Int): SuspectOpenTag? {
        if (pos >= text.length || text[pos] != '<') return null
        for (name in THINK_TAG_NAMES) {
            val next = pos + 1 + name.length
            if (text.regionMatches(pos + 1, name, 0, name.length, ignoreCase = true)) {
                if (next < text.length && (text[next] == ' ' || text[next] == '\t' || text[next] == '/' || text[next] == '>')) {
                    val maxScan = minOf(text.length, next + MAX_TAG_ATTRIBUTE_SCAN_CHARS)
                    var gt = -1
                    var p = next
                    var inQuote = '\u0000'
                    while (p < maxScan) {
                        val ch = text[p]
                        if (ch == '\n' || ch == '\r') break
                        if (inQuote != '\u0000') {
                            if (ch == inQuote) {
                                inQuote = '\u0000'
                            }
                        } else {
                            if (ch == '<') break
                            if (ch == '"' || ch == '\'') {
                                inQuote = ch
                            } else if (ch == '>') {
                                gt = p
                                break
                            }
                        }
                        p++
                    }

                    if (gt != -1 && inQuote == '\u0000') {
                        var s = gt - 1
                        while (s >= next && (text[s] == ' ' || text[s] == '\t')) s--
                        val isSelfClosing = s >= next && text[s] == '/'
                        return SuspectOpenTag(
                            isSelfClosing = isSelfClosing,
                            tagEnd = gt + 1,
                            nextScanPos = gt + 1,
                        )
                    }

                    return SuspectOpenTag(
                        isSelfClosing = false,
                        tagEnd = next,
                        nextScanPos = next,
                    )
                }
            }
        }
        return null
    }

    private fun isSuspectOpenThinkTag(text: String, pos: Int): Boolean =
        matchSuspectOpenThinkTag(text, pos) != null

    /**
     * Strips malformed or residual thinking tags from text using single-pass bracket matching
     * and monotonic interval absorption.
     *
     * Invariants enforced:
     * - Monotonic Interval Absorption: [openStarts] tracks matched opening bracket indices. When a matching
     *   closing tag is found at `close.tagEnd`, any previously recorded inner intervals starting at or after
     *   `start` are discarded from [removedIntervals] because they are completely encompassed by the outer
     *   range `start until close.tagEnd`.
     * - Delimiter Shielding: Code blocks and inline code spans shield XML tags from being considered as thinking tags.
     * - Strict Linear Time O(N): The text is traversed linearly, and each character is processed at most a bounded
     *   number of times without string rebuilding until the final interval subtraction phase.
     */
    private fun stripMalformedThinkingTags(text: String): String {
        val len = text.length
        if (len == 0) return ""
        val escaped = escapedBackticks(text)
        val ticks = inlineTickRuns(text)
        val nextNewline = IntArray(len + 1) { -1 }
        for (i in len - 1 downTo 0) {
            nextNewline[i] = if (text[i] == '\n') i else nextNewline[i + 1]
        }

        val removedIntervals = mutableListOf<IntRange>()
        val openStarts = ArrayList<Int>()
        var i = 0

        while (i < len) {
            if (text[i] == '`' && !escaped[i]) {
                if (text.startsWith(CODE_FENCE, i)) {
                    var fenceEnd = i + CODE_FENCE.length
                    while (fenceEnd < len && text[fenceEnd] == '`') fenceEnd++
                    val fenceLen = fenceEnd - i
                    val nextFence = text.indexOf(text.substring(i, fenceEnd), fenceEnd)
                    val close = if (nextFence >= 0) nextFence + fenceLen else len
                    i = close
                    continue
                }
                val size = if (i + 1 < len && text[i + 1] == '`') 2 else 1
                val close = ticks[size - 1][i + size]
                val newline = nextNewline[i + size]
                if (close >= 0 && (newline < 0 || close < newline)) {
                    i = close + size
                    continue
                }
            }

            if (text[i] == '<') {
                val openMatch = matchSuspectOpenThinkTag(text, i)
                if (openMatch != null) {
                    if (openMatch.isSelfClosing) {
                        removedIntervals.add(i until openMatch.tagEnd)
                        i = openMatch.tagEnd
                        continue
                    }
                    openStarts.add(i)
                    i = openMatch.nextScanPos
                    continue
                }
                val close = matchCloseThinkTag(text, i)
                if (close != null) {
                    if (openStarts.isNotEmpty()) {
                        val start = openStarts.removeAt(openStarts.lastIndex)
                        while (removedIntervals.isNotEmpty() && removedIntervals.last().first >= start) {
                            removedIntervals.removeAt(removedIntervals.lastIndex)
                        }
                        removedIntervals.add(start until close.tagEnd)
                    }
                    i = close.tagEnd
                    continue
                }
            }

            i++
        }

        if (openStarts.isNotEmpty()) {
            val unclosedStart = openStarts.first()
            while (removedIntervals.isNotEmpty() && removedIntervals.last().first >= unclosedStart) {
                removedIntervals.removeAt(removedIntervals.lastIndex)
            }
            removedIntervals.add(unclosedStart until len)
        }

        if (removedIntervals.isEmpty()) return text

        val sb = java.lang.StringBuilder(len)
        var cursor = 0
        for (range in removedIntervals) {
            if (range.first > cursor) {
                sb.append(text, cursor, range.first)
            }
            cursor = range.last + 1
        }
        if (cursor < len) {
            sb.append(text, cursor, len)
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

    /**
     * Parses inline markdown styles (inline code, bold, italic, bold+italic) into an [AnnotatedString] in linear O(N) time.
     *
     * Invariants enforced:
     * - Delimiter Precedence: Inline code spans (`` ` `` or ```` `` ````) take absolute precedence over asterisk styles.
     *   Any asterisks inside code spans are preserved literally.
     * - Formatting Hierarchy: Triple delimiters (`***`) take precedence over double (`**`), which in turn take precedence
     *   over single (`*`).
     * - Shared Triple Closer Disambiguation: Precomputed lookup arrays (`nextDouble`, `nextTriple`, `openBold`, `openItalic`)
     *   resolve ambiguous closures (such as `**bold *italic***`) in O(1) without exponential backtracking.
     */
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
