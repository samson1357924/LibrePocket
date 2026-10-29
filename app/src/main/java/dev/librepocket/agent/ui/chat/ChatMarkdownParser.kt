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

    /**
     * Parses the given [text] into a list of [ChatContentBlock]s.
     *
     * @param text The input message text to parse.
     * @param isStreaming Whether the message is currently being streamed.
     */
    fun parse(
        text: String,
        isStreaming: Boolean = false,
    ): List<ChatContentBlock> {
        if (text.isEmpty()) {
            return if (isStreaming) {
                listOf(ChatContentBlock.ParagraphBlock(AnnotatedString("")))
            } else {
                emptyList()
            }
        }

        val blocks = mutableListOf<ChatContentBlock>()
        var cursor = 0
        val length = text.length

        while (cursor < length) {
            // Find earliest thinking tag
            var earliestThinkStart = -1
            var matchedOpenTag = ""
            var matchedCloseTag = ""

            for ((openTag, closeTag) in THINK_TAG_PAIRS) {
                val start = text.indexOf(openTag, cursor, ignoreCase = true)
                if (start != -1 && (earliestThinkStart == -1 || start < earliestThinkStart)) {
                    earliestThinkStart = start
                    matchedOpenTag = openTag
                    matchedCloseTag = closeTag
                }
            }

            // Find earliest code fence
            val codeStart = text.indexOf(CODE_FENCE, cursor)

            val nextSpecial = when {
                earliestThinkStart != -1 && codeStart != -1 -> minOf(earliestThinkStart, codeStart)
                earliestThinkStart != -1 -> earliestThinkStart
                codeStart != -1 -> codeStart
                else -> -1
            }

            if (nextSpecial == -1) {
                // Remainder is plain text / paragraph
                val remaining = text.substring(cursor).trim()
                if (remaining.isNotBlank()) {
                    blocks.add(ChatContentBlock.ParagraphBlock(parseInlineMarkdown(remaining)))
                }
                break
            }

            // Paragraph before the special block
            if (nextSpecial > cursor) {
                val preText = text.substring(cursor, nextSpecial).trim()
                if (preText.isNotBlank()) {
                    blocks.add(ChatContentBlock.ParagraphBlock(parseInlineMarkdown(preText)))
                }
            }

            if (nextSpecial == earliestThinkStart) {
                val contentStart = earliestThinkStart + matchedOpenTag.length
                val thinkEnd = text.indexOf(matchedCloseTag, contentStart, ignoreCase = true)
                if (thinkEnd != -1) {
                    val content = text.substring(contentStart, thinkEnd).trim()
                    blocks.add(ChatContentBlock.ThinkingBlock(content = content, isStreaming = false))
                    cursor = thinkEnd + matchedCloseTag.length
                } else {
                    // Unclosed thinking tag
                    val content = text.substring(contentStart).trim()
                    blocks.add(ChatContentBlock.ThinkingBlock(content = content, isStreaming = isStreaming))
                    cursor = length
                }
            } else {
                // Code block fence
                val afterFence = codeStart + CODE_FENCE.length
                val firstNewline = text.indexOf('\n', afterFence)

                if (firstNewline != -1) {
                    val lang = text.substring(afterFence, firstNewline).trim().ifEmpty { null }
                    val codeContentStart = firstNewline + 1
                    val closingFence = text.indexOf(CODE_FENCE, codeContentStart)

                    if (closingFence != -1) {
                        var codeContent = text.substring(codeContentStart, closingFence)
                        if (codeContent.endsWith("\r\n")) {
                            codeContent = codeContent.dropLast(2)
                        } else if (codeContent.endsWith("\n") || codeContent.endsWith("\r")) {
                            codeContent = codeContent.dropLast(1)
                        }
                        blocks.add(
                            ChatContentBlock.CodeBlock(
                                language = lang,
                                code = codeContent,
                                isStreaming = false,
                            ),
                        )
                        cursor = closingFence + CODE_FENCE.length
                    } else {
                        // Unclosed code block
                        val codeContent = text.substring(codeContentStart)
                        blocks.add(
                            ChatContentBlock.CodeBlock(
                                language = lang,
                                code = codeContent,
                                isStreaming = isStreaming,
                            ),
                        )
                        cursor = length
                    }
                } else {
                    // No newline after code fence
                    val closingFence = text.indexOf(CODE_FENCE, afterFence)
                    if (closingFence != -1) {
                        blocks.add(
                            ChatContentBlock.CodeBlock(
                                language = null,
                                code = text.substring(afterFence, closingFence),
                                isStreaming = false,
                            ),
                        )
                        cursor = closingFence + CODE_FENCE.length
                    } else {
                        val lang = text.substring(afterFence).trim().ifEmpty { null }
                        blocks.add(
                            ChatContentBlock.CodeBlock(
                                language = lang,
                                code = "",
                                isStreaming = isStreaming,
                            ),
                        )
                        cursor = length
                    }
                }
            }
        }

        return blocks
    }

    /**
     * Strips thinking blocks from text for audio TTS or clean plain text usage.
     */
    fun stripThinking(text: String): String {
        var result = text
        for ((openTag, closeTag) in THINK_TAG_PAIRS) {
            if (!result.contains(openTag, ignoreCase = true)) continue
            val sb = java.lang.StringBuilder(result.length)
            var cursor = 0
            while (cursor < result.length) {
                val start = result.indexOf(openTag, cursor, ignoreCase = true)
                if (start == -1) {
                    sb.append(result, cursor, result.length)
                    break
                }
                if (start > cursor) {
                    sb.append(result, cursor, start)
                }
                val end = result.indexOf(closeTag, start + openTag.length, ignoreCase = true)
                if (end != -1) {
                    cursor = end + closeTag.length
                } else {
                    break
                }
            }
            result = sb.toString()
        }
        return result.trim()
    }

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
    fun parseInlineMarkdown(rawText: String): AnnotatedString {
        val text = formatLists(rawText)
        val builder = AnnotatedString.Builder()
        var i = 0
        val len = text.length

        while (i < len) {
            val c = text[i]

            // Inline code: `code`
            if (c == '`') {
                val close = text.indexOf('`', i + 1)
                if (close != -1) {
                    val code = text.substring(i + 1, close)
                    if (!code.contains('\n')) {
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
                        i = close + 1
                        continue
                    }
                }
                builder.append('`')
                i++
                continue
            }

            // Bold & Italic: ***text***
            if (text.startsWith("***", i) && i + 3 < len && !text[i + 3].isWhitespace()) {
                val close = text.indexOf("***", i + 3)
                if (close != -1 && !text[close - 1].isWhitespace()) {
                    val content = text.substring(i + 3, close)
                    if (!content.contains('\n') && content.isNotBlank()) {
                        val start = builder.length
                        builder.append(content)
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
                val close = text.indexOf("**", i + 2)
                if (close != -1 && !text[close - 1].isWhitespace()) {
                    val content = text.substring(i + 2, close)
                    if (!content.contains('\n') && content.isNotBlank()) {
                        val start = builder.length
                        builder.append(content)
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
                val close = text.indexOf('*', i + 1)
                if (close != -1 && !text[close - 1].isWhitespace()) {
                    val content = text.substring(i + 1, close)
                    if (!content.contains('\n') && content.isNotBlank()) {
                        val start = builder.length
                        builder.append(content)
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
