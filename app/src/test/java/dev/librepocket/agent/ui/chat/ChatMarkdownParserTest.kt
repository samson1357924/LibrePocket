package dev.librepocket.agent.ui.chat

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMarkdownParserTest {

    @Test
    fun parse_emptyText_returnsEmptyListOrStreamingBlock() {
        assertTrue(ChatMarkdownParser.parse("").isEmpty())
        val streamingBlocks = ChatMarkdownParser.parse("", isStreaming = true)
        assertEquals(1, streamingBlocks.size)
        assertTrue(streamingBlocks[0] is ChatContentBlock.ParagraphBlock)
        assertEquals("", (streamingBlocks[0] as ChatContentBlock.ParagraphBlock).annotatedText.text)
    }

    @Test
    fun parse_thinking_closed_returnsThinkingBlockNotStreaming() {
        val input = "<think>\nThinking about this problem...\n</think>\nHere is the answer."
        val blocks = ChatMarkdownParser.parse(input, isStreaming = true)

        assertEquals(2, blocks.size)
        val thinking = blocks[0] as ChatContentBlock.ThinkingBlock
        assertEquals("Thinking about this problem...", thinking.content)
        assertFalse("Closed think tag must not be streaming", thinking.isStreaming)

        val paragraph = blocks[1] as ChatContentBlock.ParagraphBlock
        assertEquals("Here is the answer.", paragraph.annotatedText.text)
    }

    @Test
    fun parse_thinking_unclosed_streaming_returnsThinkingBlockStreaming() {
        val input = "<think>\nReasoning in progress..."
        val blocks = ChatMarkdownParser.parse(input, isStreaming = true)

        assertEquals(1, blocks.size)
        val thinking = blocks[0] as ChatContentBlock.ThinkingBlock
        assertEquals("Reasoning in progress...", thinking.content)
        assertTrue("Unclosed think during stream must have isStreaming=true", thinking.isStreaming)
    }

    @Test
    fun parse_thinking_unclosed_notStreaming_returnsThinkingBlockNotStreaming() {
        val input = "<think>\nCut off reasoning"
        val blocks = ChatMarkdownParser.parse(input, isStreaming = false)

        assertEquals(1, blocks.size)
        val thinking = blocks[0] as ChatContentBlock.ThinkingBlock
        assertEquals("Cut off reasoning", thinking.content)
        assertFalse("Unclosed think when not streaming must have isStreaming=false", thinking.isStreaming)
    }

    @Test
    fun parse_thinking_caseInsensitive_andAlternativeTags() {
        val thinkUpper = "<THINK>Upper case</THINK>Answer"
        val blocks1 = ChatMarkdownParser.parse(thinkUpper)
        assertEquals(2, blocks1.size)
        assertEquals("Upper case", (blocks1[0] as ChatContentBlock.ThinkingBlock).content)

        val thought = "<thought>Alternative tag</thought>Answer"
        val blocks2 = ChatMarkdownParser.parse(thought)
        assertEquals(2, blocks2.size)
        assertEquals("Alternative tag", (blocks2[0] as ChatContentBlock.ThinkingBlock).content)
    }

    @Test
    fun parse_codeBlock_closedWithLanguage_returnsCodeBlock() {
        val input = "```kotlin\nfun main() {\n    println(\"hi\")\n}\n```"
        val blocks = ChatMarkdownParser.parse(input)

        assertEquals(1, blocks.size)
        val code = blocks[0] as ChatContentBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("fun main() {\n    println(\"hi\")\n}", code.code)
        assertFalse(code.isStreaming)
    }

    @Test
    fun parse_codeBlock_closedWithoutLanguage_returnsCodeBlock() {
        val input = "```\nval x = 42\n```"
        val blocks = ChatMarkdownParser.parse(input)

        assertEquals(1, blocks.size)
        val code = blocks[0] as ChatContentBlock.CodeBlock
        assertNull(code.language)
        assertEquals("val x = 42", code.code)
        assertFalse(code.isStreaming)
    }

    @Test
    fun parse_codeBlock_unclosedStreaming_returnsCodeBlockStreaming() {
        val input = "```python\ndef run():\n    pass"
        val blocks = ChatMarkdownParser.parse(input, isStreaming = true)

        assertEquals(1, blocks.size)
        val code = blocks[0] as ChatContentBlock.CodeBlock
        assertEquals("python", code.language)
        assertEquals("def run():\n    pass", code.code)
        assertTrue(code.isStreaming)
    }

    @Test
    fun parse_interleaved_thinking_paragraph_code_paragraph() {
        val input = """
            <think>
            Plan:
            1. Write a function
            </think>
            Here is the implementation:
            ```kotlin
            fun add(a: Int, b: Int) = a + b
            ```
            Hope this helps!
        """.trimIndent()

        val blocks = ChatMarkdownParser.parse(input)

        assertEquals(4, blocks.size)
        assertTrue(blocks[0] is ChatContentBlock.ThinkingBlock)
        assertEquals("Plan:\n1. Write a function", (blocks[0] as ChatContentBlock.ThinkingBlock).content)

        assertTrue(blocks[1] is ChatContentBlock.ParagraphBlock)
        assertEquals("Here is the implementation:", (blocks[1] as ChatContentBlock.ParagraphBlock).annotatedText.text)

        assertTrue(blocks[2] is ChatContentBlock.CodeBlock)
        val codeBlock = blocks[2] as ChatContentBlock.CodeBlock
        assertEquals("kotlin", codeBlock.language)
        assertEquals("fun add(a: Int, b: Int) = a + b", codeBlock.code)

        assertTrue(blocks[3] is ChatContentBlock.ParagraphBlock)
        assertEquals("Hope this helps!", (blocks[3] as ChatContentBlock.ParagraphBlock).annotatedText.text)
    }

    @Test
    fun parseInlineMarkdown_bold_italic_boldItalic() {
        val text = "Normal **bold text** and *italic text* and ***both*** end."
        val annotated = ChatMarkdownParser.parseInlineMarkdown(text)

        assertEquals("Normal bold text and italic text and both end.", annotated.text)

        val boldSpan = annotated.spanStyles.firstOrNull { it.item.fontWeight == FontWeight.Bold && it.item.fontStyle == null }
        val italicSpan = annotated.spanStyles.firstOrNull { it.item.fontStyle == FontStyle.Italic && it.item.fontWeight == null }
        val bothSpan = annotated.spanStyles.firstOrNull { it.item.fontWeight == FontWeight.Bold && it.item.fontStyle == FontStyle.Italic }

        assertTrue("Bold span must be present", boldSpan != null)
        assertEquals(7, boldSpan!!.start)
        assertEquals(16, boldSpan.end)

        assertTrue("Italic span must be present", italicSpan != null)
        assertEquals(21, italicSpan!!.start)
        assertEquals(32, italicSpan.end)

        assertTrue("Bold+Italic span must be present", bothSpan != null)
        assertEquals(37, bothSpan!!.start)
        assertEquals(41, bothSpan.end)
    }

    @Test
    fun parseInlineMarkdown_inlineCode() {
        val text = "Use `val result = 1` here."
        val annotated = ChatMarkdownParser.parseInlineMarkdown(text)

        assertEquals("Use val result = 1 here.", annotated.text)
        val codeSpan = annotated.spanStyles.firstOrNull { it.item.fontFamily == FontFamily.Monospace }
        assertTrue("Monospace code span must be present", codeSpan != null)
        assertEquals(4, codeSpan!!.start)
        assertEquals(18, codeSpan.end)
    }

    @Test
    fun parseInlineMarkdown_listPrefixesFormatted() {
        val raw = "- First item\n* Second item\n1. Numbered item"
        val annotated = ChatMarkdownParser.parseInlineMarkdown(raw)

        assertEquals("• First item\n• Second item\n1. Numbered item", annotated.text)
    }

    @Test
    fun parseInlineMarkdown_unmatchedSyntaxLeavesVerbatim() {
        val raw = "Single * asterisk and ` unmatched backtick and ** unmatched bold"
        val annotated = ChatMarkdownParser.parseInlineMarkdown(raw)

        assertEquals(raw, annotated.text)
        assertTrue("No spans should be applied for unmatched formatting", annotated.spanStyles.isEmpty())
    }

    @Test
    fun stripThinking_removesAllThinkingTags() {
        val input = "Hello <think>internal thinking</think>World <THINK>more</THINK>!"
        val stripped = ChatMarkdownParser.stripThinking(input)
        assertEquals("Hello World !", stripped)

        val unclosed = "Answer <think>incomplete"
        assertEquals("Answer", ChatMarkdownParser.stripThinking(unclosed))
    }

    @Test
    fun parse_inlineCodeShieldsThinkingTags_andStripPreservesRawCode() {
        val input = "Use `<think>literal</think>` and <think>secret</think>answer"
        val blocks = ChatMarkdownParser.parse(input)
        assertEquals("Use <think>literal</think> and", (blocks[0] as ChatContentBlock.ParagraphBlock).annotatedText.text)
        assertEquals("secret", (blocks[1] as ChatContentBlock.ThinkingBlock).content)
        assertEquals("Use `<think>literal</think>` and answer", ChatMarkdownParser.stripThinking(input))
    }

    @Test
    fun parse_fencedCodeOnlyClosesOnFenceLine() {
        val input = "```text\ninline ``` remains\n```suffix\n<think>literal</think>\n```\nafter"
        val blocks = ChatMarkdownParser.parse(input)
        assertEquals(2, blocks.size)
        assertEquals("inline ``` remains\n```suffix\n<think>literal</think>", (blocks[0] as ChatContentBlock.CodeBlock).code)
        assertEquals("after", (blocks[1] as ChatContentBlock.ParagraphBlock).annotatedText.text)
        assertEquals(input, ChatMarkdownParser.stripThinking(input))
    }

    @Test
    fun stripThinking_usesSameOrderingAsDisplayForMixedAndUnclosedTags() {
        val input = "before <thought>outer <think>inner</thought> visible </think> after"
        val blocks = ChatMarkdownParser.parse(input)
        assertEquals("outer <think>inner", (blocks[1] as ChatContentBlock.ThinkingBlock).content)
        assertEquals("before  visible </think> after", ChatMarkdownParser.stripThinking(input))
        assertEquals("before", ChatMarkdownParser.stripThinking("before <thinking>unclosed <think>x</think>"))
    }

    @Test
    fun parse_streamingPartialTagsAreNotDisplayedPrematurely() {
        for (prefix in listOf("<", "<t", "<thi", "<THINK", "<thought")) {
            val blocks = ChatMarkdownParser.parse("answer $prefix", isStreaming = true)
            assertEquals("answer", (blocks.single() as ChatContentBlock.ParagraphBlock).annotatedText.text)
            assertEquals("answer $prefix", (ChatMarkdownParser.parse("answer $prefix").single() as ChatContentBlock.ParagraphBlock).annotatedText.text)
        }
        val thinking = ChatMarkdownParser.parse("<think>reason</thi", isStreaming = true).single() as ChatContentBlock.ThinkingBlock
        assertEquals("reason", thinking.content)
        assertTrue(thinking.isStreaming)
        assertEquals("reason</thi", (ChatMarkdownParser.parse("<think>reason</thi").single() as ChatContentBlock.ThinkingBlock).content)
    }

    @Test
    fun parseInlineMarkdown_italicCanContainBoldWithoutConsumingBoldDelimiter() {
        val result = ChatMarkdownParser.parseInlineMarkdown("*outer **bold** end*")
        assertEquals("outer bold end", result.text)
        assertTrue(result.spanStyles.any { it.item.fontStyle == FontStyle.Italic && it.start == 0 && it.end == 14 })
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start == 6 && it.end == 10 })
        assertEquals("*outer **unclosed", ChatMarkdownParser.parseInlineMarkdown("*outer **unclosed").text)
    }

    @Test(timeout = 5000)
    fun bounds_manyAlternatingBlocksAndUnmatchedInlineDelimiters() {
        val blocks = buildString { repeat(12000) { append("<think>x</think>text ") } }
        assertEquals(24000, ChatMarkdownParser.parse(blocks).size)
        val unmatched = ("*x\n".repeat(12000)) + ("`x\n".repeat(12000))
        assertEquals(unmatched, ChatMarkdownParser.parseInlineMarkdown(unmatched).text)
    }

    @Test
    fun parse_fenceCloserMustBeAtLeastAsLongAsOpener() {
        val input = "````text\n```\n<think>literal</think>\n````\nafter"
        val blocks = ChatMarkdownParser.parse(input)
        val code = blocks[0] as ChatContentBlock.CodeBlock
        assertEquals("```\n<think>literal</think>", code.code)
        assertFalse(code.isStreaming)
        assertEquals("after", (blocks[1] as ChatContentBlock.ParagraphBlock).annotatedText.text)
        assertEquals(input, ChatMarkdownParser.stripThinking(input))

        val longer = ChatMarkdownParser.parse("````\nx\n`````\nafter")
        assertEquals("x", (longer[0] as ChatContentBlock.CodeBlock).code)
        assertEquals("after", (longer[1] as ChatContentBlock.ParagraphBlock).annotatedText.text)
        val unclosed = ChatMarkdownParser.parse("````\nx\n```").single() as ChatContentBlock.CodeBlock
        assertEquals("x\n```", unclosed.code)
        assertFalse(unclosed.isStreaming)
        val sameLine = ChatMarkdownParser.parse("````x```y````").single() as ChatContentBlock.CodeBlock
        assertEquals("x```y", sameLine.code)
    }

    @Test
    fun parse_inlineCodeTakesPrecedenceOverStyleDelimiters() {
        val input = "**`**<think>secret</think>`"
        val block = ChatMarkdownParser.parse(input).single() as ChatContentBlock.ParagraphBlock
        assertEquals("****<think>secret</think>", block.annotatedText.text)
        assertTrue(block.annotatedText.spanStyles.any { it.item.fontFamily == FontFamily.Monospace && it.start == 2 })
        assertTrue(block.annotatedText.spanStyles.none { it.item.fontWeight == FontWeight.Bold })
        assertEquals(input, ChatMarkdownParser.stripThinking(input))

        val nested = ChatMarkdownParser.parse("**before `<think>literal</think>` after**").single() as ChatContentBlock.ParagraphBlock
        assertEquals("before <think>literal</think> after", nested.annotatedText.text)
        assertTrue(nested.annotatedText.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
        assertTrue(nested.annotatedText.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun parse_escapedBacktickDoesNotHideThinkingAndKeepsFollowingCode() {
        val input = "Escaped \\` <think>secret</think> `code`"
        for (streaming in listOf(false, true)) {
            val blocks = ChatMarkdownParser.parse(input, isStreaming = streaming)
            assertEquals(3, blocks.size)
            assertEquals("Escaped \\`", (blocks[0] as ChatContentBlock.ParagraphBlock).annotatedText.text)
            assertEquals("secret", (blocks[1] as ChatContentBlock.ThinkingBlock).content)
            val code = (blocks[2] as ChatContentBlock.ParagraphBlock).annotatedText
            assertEquals("code", code.text)
            assertTrue(code.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
        }
        assertEquals("Escaped \\`  `code`", ChatMarkdownParser.stripThinking(input))
    }

    @Test
    fun parse_backslashParityDeterminesInlineCodeDelimiters() {
        for (count in 1..4) {
            val slashes = "\\".repeat(count)
            val input = "start ${slashes}`<think>secret</think>` end"
            for (streaming in listOf(false, true)) {
                val blocks = ChatMarkdownParser.parse(input, isStreaming = streaming)
                if (count % 2 == 1) {
                    assertEquals("start ${slashes}`", (blocks[0] as ChatContentBlock.ParagraphBlock).annotatedText.text)
                    assertEquals("secret", (blocks[1] as ChatContentBlock.ThinkingBlock).content)
                    assertEquals("` end", (blocks[2] as ChatContentBlock.ParagraphBlock).annotatedText.text)
                } else {
                    val paragraph = (blocks.single() as ChatContentBlock.ParagraphBlock).annotatedText
                    assertEquals("start $slashes<think>secret</think> end", paragraph.text)
                    assertTrue(paragraph.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
                }
            }
            val expected = if (count % 2 == 1) "start ${slashes}`` end" else input
            assertEquals(expected, ChatMarkdownParser.stripThinking(input))
        }
    }

    @Test
    fun parse_backslashesInsideInlineCodeDoNotEscapeCloser() {
        for (count in 1..4) {
            val slashes = "\\".repeat(count)
            val input = "Path `C:$slashes` <think>secret</think> `ok`"
            for (streaming in listOf(false, true)) {
                val blocks = ChatMarkdownParser.parse(input, isStreaming = streaming)
                assertEquals(3, blocks.size)
                val path = (blocks[0] as ChatContentBlock.ParagraphBlock).annotatedText
                assertEquals("Path C:$slashes", path.text)
                assertTrue(path.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
                assertEquals("secret", (blocks[1] as ChatContentBlock.ThinkingBlock).content)
                val ok = (blocks[2] as ChatContentBlock.ParagraphBlock).annotatedText
                assertEquals("ok", ok.text)
                assertTrue(ok.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
            }
            assertEquals("Path `C:$slashes`  `ok`", ChatMarkdownParser.stripThinking(input))
            assertEquals("C:$slashes", ChatMarkdownParser.parseInlineMarkdown("`C:$slashes`").text)
        }
        val literal = "`C:\\<think>literal</think>`"
        assertEquals("C:\\<think>literal</think>", (ChatMarkdownParser.parse(literal).single() as ChatContentBlock.ParagraphBlock).annotatedText.text)
        assertEquals(literal, ChatMarkdownParser.stripThinking(literal))
    }

    @Test
    fun parse_closingFenceAllowsAtMostThreeSpacesAndNoTabs() {
        for (indent in listOf("    ", "     ", "\t", "  \t")) {
            val input = "```\ncode\n${indent}```\n<think>literal</think>\n```\nafter"
            val blocks = ChatMarkdownParser.parse(input)
            assertEquals("code\n${indent}```\n<think>literal</think>", (blocks[0] as ChatContentBlock.CodeBlock).code)
            assertEquals("after", (blocks[1] as ChatContentBlock.ParagraphBlock).annotatedText.text)
            assertEquals(input, ChatMarkdownParser.stripThinking(input))
            val partial = ChatMarkdownParser.parse("```\ncode\n${indent}``", isStreaming = true).single() as ChatContentBlock.CodeBlock
            assertEquals("code\n${indent}``", partial.code)
        }
        for (indent in listOf("", " ", "  ", "   ")) {
            val input = "```\ncode\n${indent}```\n<think>secret</think>"
            val blocks = ChatMarkdownParser.parse(input)
            assertEquals("code", (blocks[0] as ChatContentBlock.CodeBlock).code)
            assertEquals("secret", (blocks[1] as ChatContentBlock.ThinkingBlock).content)
            assertEquals("```\ncode\n${indent}```", ChatMarkdownParser.stripThinking(input))
        }
    }

    @Test
    fun parse_indentedFenceOpenersAndInlineOpenersFollowExplicitRules() {
        for (indent in listOf("", " ", "  ", "   ")) {
            val input = "${indent}```txt\n<think>literal</think>\n${indent}```\nafter"
            val blocks = ChatMarkdownParser.parse(input)
            val code = blocks[0] as ChatContentBlock.CodeBlock
            assertEquals("txt", code.language)
            assertEquals("<think>literal</think>", code.code)
            assertEquals("after", (blocks[1] as ChatContentBlock.ParagraphBlock).annotatedText.text)
            assertEquals(input.trim(), ChatMarkdownParser.stripThinking(input))
        }
        for (indent in listOf("    ", "     ", "\t", "  \t")) {
            val input = "${indent}```\n<think>secret</think>\n${indent}```"
            for (streaming in listOf(false, true)) {
                val blocks = ChatMarkdownParser.parse(input, isStreaming = streaming)
                assertEquals(3, blocks.size)
                assertEquals("```", (blocks[0] as ChatContentBlock.ParagraphBlock).annotatedText.text)
                assertEquals("secret", (blocks[1] as ChatContentBlock.ThinkingBlock).content)
                assertEquals("```", (blocks[2] as ChatContentBlock.ParagraphBlock).annotatedText.text)
                assertTrue(blocks.none { it is ChatContentBlock.CodeBlock })
            }
            assertEquals("```\n\n${indent}```", ChatMarkdownParser.stripThinking(input))
        }
        val inline = "    prefix ```txt\ncode\n```\nafter"
        val blocks = ChatMarkdownParser.parse(inline)
        assertEquals("prefix", (blocks[0] as ChatContentBlock.ParagraphBlock).annotatedText.text)
        assertEquals("code", (blocks[1] as ChatContentBlock.CodeBlock).code)
        assertEquals("after", (blocks[2] as ChatContentBlock.ParagraphBlock).annotatedText.text)
        assertEquals(inline.trim(), ChatMarkdownParser.stripThinking(inline))
    }

    @Test(timeout = 5000)
    fun bounds_manyEscapedBackticksAndIndentedFenceCandidates() {
        val chunk = "\\` <think>x</think> `code` "
        val input = chunk.repeat(10000)
        assertEquals(20001, ChatMarkdownParser.parse(input).size)
        assertEquals(input.replace("<think>x</think>", "").trim(), ChatMarkdownParser.stripThinking(input))
        val fenced = "```\n" + "    ```\n".repeat(10000) + "```\n"
        val code = ChatMarkdownParser.parse(fenced).single() as ChatContentBlock.CodeBlock
        assertEquals("    ```\n".repeat(9999) + "    ```", code.code)
        val rejected = "    ```\n".repeat(10000)
        assertTrue(ChatMarkdownParser.parse(rejected).none { it is ChatContentBlock.CodeBlock })
        val closers = "`C:\\` <think>x</think> `ok` ".repeat(10000)
        assertEquals(20001, ChatMarkdownParser.parse(closers).size)
        assertEquals(closers.replace("<think>x</think>", "").trim(), ChatMarkdownParser.stripThinking(closers))
    }

    @Test
    fun parse_inlineItalicDoesNotReformatListPrefixes() {
        val text = ChatMarkdownParser.parseInlineMarkdown("*- item*")
        assertEquals("- item", text.text)
        assertTrue(text.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        assertEquals("• item", ChatMarkdownParser.parseInlineMarkdown("- item").text)
    }

    @Test
    fun parse_unclosedInlineCodeRecognizesTagsInBothStatesAndTts() {
        val input = "`<think>literal</think>"
        for (streaming in listOf(false, true)) {
            val blocks = ChatMarkdownParser.parse(input, isStreaming = streaming)
            assertEquals(2, blocks.size)
            assertEquals("`", (blocks[0] as ChatContentBlock.ParagraphBlock).annotatedText.text)
            assertEquals("literal", (blocks[1] as ChatContentBlock.ThinkingBlock).content)
        }
        assertEquals("`", ChatMarkdownParser.stripThinking(input))
        for (streaming in listOf(false, true)) {
            val finished = ChatMarkdownParser.parse("$input`", isStreaming = streaming).single() as ChatContentBlock.ParagraphBlock
            assertEquals("<think>literal</think>", finished.annotatedText.text)
            assertTrue(finished.annotatedText.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
            val nextLine = ChatMarkdownParser.parse("`unfinished\n<think>reason</think>", isStreaming = streaming)
            assertTrue(nextLine[1] is ChatContentBlock.ThinkingBlock)
        }
        assertEquals("$input`", ChatMarkdownParser.stripThinking("$input`"))
    }

    @Test
    fun parse_streamingFenceAtEofWaitsForLineEnd() {
        val prefix = "```\ncode\n```"
        val pending = ChatMarkdownParser.parse(prefix, isStreaming = true).single() as ChatContentBlock.CodeBlock
        assertEquals("code", pending.code)
        assertTrue(pending.isStreaming)
        assertFalse((ChatMarkdownParser.parse(prefix).single() as ChatContentBlock.CodeBlock).isStreaming)
        val confirmed = ChatMarkdownParser.parse("$prefix\nanswer", isStreaming = true)
        assertFalse((confirmed[0] as ChatContentBlock.CodeBlock).isStreaming)
        assertEquals("answer", (confirmed[1] as ChatContentBlock.ParagraphBlock).annotatedText.text)
        val extended = ChatMarkdownParser.parse("${prefix}Done\n<think>literal</think>", isStreaming = true).single() as ChatContentBlock.CodeBlock
        assertTrue(extended.isStreaming)
        assertEquals("code\n```Done\n<think>literal</think>", extended.code)
    }

    @Test
    fun parse_streamingPartialClosingFenceIsHiddenUntilDisambiguated() {
        for (ticks in listOf("`", "``")) {
            val input = "```\ncode\n  $ticks"
            val pending = ChatMarkdownParser.parse(input, isStreaming = true).single() as ChatContentBlock.CodeBlock
            assertEquals("code", pending.code)
            assertTrue(pending.isStreaming)
            val completed = ChatMarkdownParser.parse(input).single() as ChatContentBlock.CodeBlock
            assertEquals("code\n  $ticks", completed.code)
            assertEquals(input, ChatMarkdownParser.stripThinking(input))
            val literal = ChatMarkdownParser.parse("${input}Done", isStreaming = true).single() as ChatContentBlock.CodeBlock
            assertEquals("code\n  ${ticks}Done", literal.code)
            val line = ChatMarkdownParser.parse("$input\n", isStreaming = true).single() as ChatContentBlock.CodeBlock
            assertEquals("code\n  $ticks\n", line.code)
        }
        val longer = ChatMarkdownParser.parse("````\ncode\n```", isStreaming = true).single() as ChatContentBlock.CodeBlock
        assertEquals("code", longer.code)
        assertTrue(longer.isStreaming)
    }

    @Test
    fun parse_malformedFenceSuffixRemainsCodeUntilValidCloser() {
        val input = "```\ncode\n```Done\n<think>literal</think>\n```\nanswer"
        val blocks = ChatMarkdownParser.parse(input)
        assertEquals("code\n```Done\n<think>literal</think>", (blocks[0] as ChatContentBlock.CodeBlock).code)
        assertEquals("answer", (blocks[1] as ChatContentBlock.ParagraphBlock).annotatedText.text)
        assertEquals(input, ChatMarkdownParser.stripThinking(input))
    }

    @Test(timeout = 5000)
    fun bounds_manyInlineCodePairsAndShortFenceRuns() {
        val inline = "`x` ".repeat(15000)
        assertEquals("x ".repeat(15000), ChatMarkdownParser.parseInlineMarkdown(inline).text)
        val input = "````\n" + "```\n".repeat(15000) + "````\n"
        assertEquals("```\n".repeat(14999) + "```", (ChatMarkdownParser.parse(input).single() as ChatContentBlock.CodeBlock).code)
    }

    @Test(timeout = 5000)
    fun bounds_extremeLargeText_completesLinearlyWithoutStackOverflow() {
        val large = StringBuilder().apply {
            repeat(1000) {
                append("<think>Thinking step $it</think>\n")
                append("Paragraph $it with **bold** and `code`.\n")
                append("```python\nprint($it)\n```\n")
            }
        }.toString()

        val start = System.currentTimeMillis()
        val blocks = ChatMarkdownParser.parse(large)
        val duration = System.currentTimeMillis() - start

        assertEquals(3000, blocks.size)
        assertTrue("Parsing 1000 blocks must complete in under 2000ms (took ${duration}ms)", duration < 2000)
    }

    @Test
    fun bounds_nestedOrMalformedTags_doesNotThrow() {
        val malformed = listOf(
            "<think><think>nested</think>",
            "</think>orphan closing",
            "``` ``` ```",
            "```\n```\n```",
            "<think>```code inside think```</think>",
            "```<think>think inside code</think>```",
            "",
            "   \n\n\t  ",
            "*********************",
            "````````````",
        )

        malformed.forEach { input ->
            val blocks = ChatMarkdownParser.parse(input)
            // Verify no exception is thrown and parse succeeds safely
            if (input.isNotBlank()) {
                assertTrue("Non-blank input must produce blocks or safe paragraphs", blocks.isNotEmpty())
            }
        }
    }
}
