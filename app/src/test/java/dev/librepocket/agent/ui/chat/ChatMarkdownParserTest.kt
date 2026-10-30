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
    fun parse_paragraphPreservesLeadingAndNestedListIndentation() {
        val input = "  - first\n    - nested\n  continuation"
        val paragraph = ChatMarkdownParser.parse(input).single() as ChatContentBlock.ParagraphBlock
        assertEquals("  • first\n    • nested\n  continuation", paragraph.annotatedText.text)
        assertEquals(input, ChatMarkdownParser.stripThinking(input))
        val afterThinking = ChatMarkdownParser.parse("<think>x</think>\n  - answer")
        assertEquals("  • answer", (afterThinking[1] as ChatContentBlock.ParagraphBlock).annotatedText.text)
    }

    @Test
    fun parse_doubleBacktickCodeSpansShieldTagsAndAllowSingleBackticks() {
        val input = "Before ``one `<think>literal</think>` two`` after <think>secret</think> end"
        for (streaming in listOf(false, true)) {
            val blocks = ChatMarkdownParser.parse(input, isStreaming = streaming)
            assertEquals(3, blocks.size)
            val paragraph = (blocks[0] as ChatContentBlock.ParagraphBlock).annotatedText
            assertEquals("Before one `<think>literal</think>` two after", paragraph.text)
            assertTrue(paragraph.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
            assertEquals("secret", (blocks[1] as ChatContentBlock.ThinkingBlock).content)
        }
        assertEquals("Before ``one `<think>literal</think>` two`` after  end", ChatMarkdownParser.stripThinking(input))
        val unmatched = "``<think>secret</think>`"
        for (streaming in listOf(false, true)) {
            assertTrue(ChatMarkdownParser.parse(unmatched, isStreaming = streaming).any { it is ChatContentBlock.ThinkingBlock })
        }
        assertEquals("```", ChatMarkdownParser.stripThinking(unmatched))
    }

    @Test
    fun parse_indentedCodeAtBlockBoundaryShieldsTagsInDisplayAndTts() {
        val input = "intro\n\n    <think>literal</think>\n\tval x = 1\nend <think>secret</think>"
        for (streaming in listOf(false, true)) {
            val blocks = ChatMarkdownParser.parse(input, isStreaming = streaming)
            assertEquals("intro", (blocks[0] as ChatContentBlock.ParagraphBlock).annotatedText.text)
            assertEquals("<think>literal</think>\nval x = 1", (blocks[1] as ChatContentBlock.CodeBlock).code)
            assertEquals("end", (blocks[2] as ChatContentBlock.ParagraphBlock).annotatedText.text)
            assertEquals("secret", (blocks[3] as ChatContentBlock.ThinkingBlock).content)
        }
        assertEquals("intro\n\n    <think>literal</think>\n\tval x = 1\nend", ChatMarkdownParser.stripThinking(input))
        val top = "    <think>literal</think>"
        assertEquals("<think>literal</think>", (ChatMarkdownParser.parse(top).single() as ChatContentBlock.CodeBlock).code)
        assertEquals(top, ChatMarkdownParser.stripThinking(top))
        // Like Markdown paragraphs, an indented continuation without a blank line is not an indented code block.
        val continuation = "paragraph\n    <think>secret</think>"
        assertTrue(ChatMarkdownParser.parse(continuation).any { it is ChatContentBlock.ThinkingBlock })
    }

    @Test
    fun parse_indentedCodeKeepsInternalBlankLinesAndStopsBeforeProse() {
        val input = "    first\n\n    second\n\n\n    <think>literal</think>\n\nanswer <think>secret</think>"
        for (streaming in listOf(false, true)) {
            val blocks = ChatMarkdownParser.parse(input, isStreaming = streaming)
            assertEquals(3, blocks.size)
            val code = blocks[0] as ChatContentBlock.CodeBlock
            assertEquals("first\n\nsecond\n\n\n<think>literal</think>", code.code)
            assertFalse(code.isStreaming)
            assertEquals("answer", (blocks[1] as ChatContentBlock.ParagraphBlock).annotatedText.text)
            assertEquals("secret", (blocks[2] as ChatContentBlock.ThinkingBlock).content)
        }
        assertEquals("    first\n\n    second\n\n\n    <think>literal</think>\n\nanswer", ChatMarkdownParser.stripThinking(input))
    }

    @Test
    fun parse_indentedCodeBlankLinesWithCrLfRemainOneCopyableBlock() {
        val input = "    first\r\n \t\r\n    \r\n\tsecond\r\n\r\nafter"
        val blocks = ChatMarkdownParser.parse(input)
        assertEquals(2, blocks.size)
        assertEquals("first\n\n\nsecond", (blocks[0] as ChatContentBlock.CodeBlock).code)
        assertEquals("after", (blocks[1] as ChatContentBlock.ParagraphBlock).annotatedText.text)
        assertEquals(input, ChatMarkdownParser.stripThinking(input))
    }

    @Test
    fun parse_indentedCodeStreamingCursorOnlyWhileAtEnd() {
        for (input in listOf("    code", "    code\n", "    code\n\n", "    code\n \t\n")) {
            val streaming = ChatMarkdownParser.parse(input, isStreaming = true).single() as ChatContentBlock.CodeBlock
            assertEquals("code", streaming.code)
            assertTrue(streaming.isStreaming)
            assertFalse((ChatMarkdownParser.parse(input).single() as ChatContentBlock.CodeBlock).isStreaming)
        }
        val extended = ChatMarkdownParser.parse("    one\n\n    two", isStreaming = true).single() as ChatContentBlock.CodeBlock
        assertEquals("one\n\ntwo", extended.code)
        assertTrue(extended.isStreaming)
        val completed = ChatMarkdownParser.parse("    code\n\nanswer", isStreaming = true)
        assertFalse((completed[0] as ChatContentBlock.CodeBlock).isStreaming)
        assertEquals("answer", (completed[1] as ChatContentBlock.ParagraphBlock).annotatedText.text)
    }

    @Test
    fun parse_nestedUnorderedListsAcrossBlankLinesRemainLists() {
        for (newline in listOf("\n", "\r\n")) {
            for (marker in listOf("-", "*")) {
                val input = "$marker parent${newline}  ${newline}${newline}    $marker child"
                for (streaming in listOf(false, true)) {
                    val blocks = ChatMarkdownParser.parse(input, isStreaming = streaming)
                    val paragraph = blocks.single() as ChatContentBlock.ParagraphBlock
                    assertEquals("• parent\n  \n\n    • child", paragraph.annotatedText.text)
                }
                assertEquals(input, ChatMarkdownParser.stripThinking(input))
            }
        }
        val standalone = ChatMarkdownParser.parse("    - code").single() as ChatContentBlock.CodeBlock
        assertEquals("- code", standalone.code)
        val afterParagraph = ChatMarkdownParser.parse("paragraph\n\n    - code")
        assertEquals("- code", (afterParagraph[1] as ChatContentBlock.CodeBlock).code)
        val insideCode = ChatMarkdownParser.parse("    code\n\n    - literal").single() as ChatContentBlock.CodeBlock
        assertEquals("code\n\n- literal", insideCode.code)
        val withThinking = "- parent\n\n    - child <think>secret</think>"
        assertTrue(ChatMarkdownParser.parse(withThinking).any { it is ChatContentBlock.ThinkingBlock })
        assertEquals("- parent\n\n    - child", ChatMarkdownParser.stripThinking(withThinking))
    }

    @Test(timeout = 3000)
    fun bounds_whitespaceOnlyLinesAlwaysAdvanceAtEofAndAfterCode() {
        val blankInputs = listOf("     ", "      ", "\t  ", "     \n", "     \r\n", "\n     ", "\r\n     ", "     \n      \r\n")
        for (input in blankInputs) {
            for (streaming in listOf(false, true)) {
                assertTrue("blank input must not produce a block: $input", ChatMarkdownParser.parse(input, isStreaming = streaming).isEmpty())
            }
            assertEquals("", ChatMarkdownParser.stripThinking(input))
        }
        for (suffix in listOf("     ", "     \n", "     \r\n", "\r\n     ", "\n      \r\n")) {
            val input = "    code\n$suffix"
            for (streaming in listOf(false, true)) {
                val code = ChatMarkdownParser.parse(input, isStreaming = streaming).single() as ChatContentBlock.CodeBlock
                assertEquals("code", code.code)
                assertEquals(streaming, code.isStreaming)
            }
            assertEquals("    code", ChatMarkdownParser.stripThinking(input))
        }
    }

    @Test
    fun parse_listContinuationRetainsContextAcrossBlankLines() {
        for (newline in listOf("\n", "\r\n")) {
            for (marker in listOf("-", "*")) {
                val input = "$marker parent${newline}  continuation${newline}${newline}    $marker child"
                for (streaming in listOf(false, true)) {
                    val paragraph = ChatMarkdownParser.parse(input, isStreaming = streaming).single() as ChatContentBlock.ParagraphBlock
                    assertEquals("• parent\n  continuation\n\n    • child", paragraph.annotatedText.text)
                }
                assertEquals(input, ChatMarkdownParser.stripThinking(input))
                val loose = "$marker parent${newline}${newline}    continuation paragraph${newline}${newline}    $marker child"
                val paragraph = ChatMarkdownParser.parse(loose).single() as ChatContentBlock.ParagraphBlock
                assertEquals("• parent\n\n    continuation paragraph\n\n    • child", paragraph.annotatedText.text)
                assertEquals(loose, ChatMarkdownParser.stripThinking(loose))
            }
        }
    }

    @Test
    fun parse_listContextEndsForUnindentedProseAndDeepCode() {
        val prose = "- parent\nordinary paragraph\n\n    - code"
        val proseBlocks = ChatMarkdownParser.parse(prose)
        assertEquals("- code", (proseBlocks[1] as ChatContentBlock.CodeBlock).code)
        val deepCode = "- parent\n\n        <think>literal</think>"
        val codeBlocks = ChatMarkdownParser.parse(deepCode)
        assertEquals("  <think>literal</think>", (codeBlocks[1] as ChatContentBlock.CodeBlock).code)
        assertEquals(deepCode, ChatMarkdownParser.stripThinking(deepCode))
        val standalone = ChatMarkdownParser.parse("    continuation paragraph").single() as ChatContentBlock.CodeBlock
        assertEquals("continuation paragraph", standalone.code)
    }

    @Test
    fun parse_listCodeIndentIsRelativeToMarkerContentColumn() {
        val cases = listOf("- " to 6, "  - " to 8, " -  " to 8, "*    " to 9)
        for ((marker, codeIndent) in cases) {
            for (newline in listOf("\n", "\r\n")) {
                val code = "<think>literal</think>"
                val input = "${marker}parent${newline}${newline}${" ".repeat(codeIndent)}$code"
                for (streaming in listOf(false, true)) {
                    val blocks = ChatMarkdownParser.parse(input, isStreaming = streaming)
                    assertEquals(2, blocks.size)
                    val codeBlock = blocks[1] as ChatContentBlock.CodeBlock
                    assertEquals(code, codeBlock.code)
                    assertEquals(streaming, codeBlock.isStreaming)
                }
                assertEquals(input, ChatMarkdownParser.stripThinking(input))

                val continuation = "${marker}parent${newline}${newline}${" ".repeat(codeIndent - 1)}<think>secret</think>"
                assertTrue(ChatMarkdownParser.parse(continuation).any { it is ChatContentBlock.ThinkingBlock })
                assertEquals("${marker}parent", ChatMarkdownParser.stripThinking(continuation))
            }
        }
    }

    @Test
    fun parse_listCodeRetainsColumnsWhenTabCrossesIndentBoundary() {
        for (newline in listOf("\n", "\r\n")) {
            val input = "- parent${newline}${newline}\t\t<think>literal</think>${newline}  \t\tsecond${newline}\t  exact"
            for (streaming in listOf(false, true)) {
                val blocks = ChatMarkdownParser.parse(input, isStreaming = streaming)
                val code = blocks[1] as ChatContentBlock.CodeBlock
                assertEquals("  <think>literal</think>\n  second\nexact", code.code)
                assertEquals(streaming, code.isStreaming)
            }
            assertEquals(input, ChatMarkdownParser.stripThinking(input))
        }
        val exactTab = "- parent\n\n\t  \tremaining"
        assertEquals("  remaining", (ChatMarkdownParser.parse(exactTab)[1] as ChatContentBlock.CodeBlock).code)
        assertEquals(exactTab, ChatMarkdownParser.stripThinking(exactTab))
        val mixed = "- parent\n\n      \t  \t<think>literal</think>\n\t \t \ttext\tinside"
        assertEquals("      <think>literal</think>\n      text\tinside", (ChatMarkdownParser.parse(mixed)[1] as ChatContentBlock.CodeBlock).code)
        assertEquals(mixed, ChatMarkdownParser.stripThinking(mixed))
    }

    @Test
    fun parse_indentedCodePreservesAlignedRecipeTabs() {
        for (newline in listOf("\n", "\r\n")) {
            val input = "    target:${newline}    \t@echo first${newline}    \t\t@echo nested"
            for (streaming in listOf(false, true)) {
                val code = ChatMarkdownParser.parse(input, isStreaming = streaming).single() as ChatContentBlock.CodeBlock
                assertEquals("target:\n\t@echo first\n\t\t@echo nested", code.code)
                assertEquals(streaming, code.isStreaming)
            }
            assertEquals(input, ChatMarkdownParser.stripThinking(input))
        }
        val alignedList = "  - parent\n\n        \t@echo <think>literal</think>"
        assertEquals("\t@echo <think>literal</think>", (ChatMarkdownParser.parse(alignedList)[1] as ChatContentBlock.CodeBlock).code)
        assertEquals(alignedList, ChatMarkdownParser.stripThinking(alignedList))
    }

    @Test
    fun parse_indentedCodeExpandsOnlyTabsWhoseStopsMove() {
        val input = "- parent\n\n      \t@echo one\n\t\t\t@echo two\n      \t  \t@echo three"
        val code = ChatMarkdownParser.parse(input)[1] as ChatContentBlock.CodeBlock
        assertEquals("  @echo one\n      @echo two\n      @echo three", code.code)
        assertEquals(input, ChatMarkdownParser.stripThinking(input))
        val unalignedTabInCode = "- parent\n\n      \t@echo text\tinside"
        assertEquals("  @echo text\tinside", (ChatMarkdownParser.parse(unalignedTabInCode)[1] as ChatContentBlock.CodeBlock).code)
        assertEquals(unalignedTabInCode, ChatMarkdownParser.stripThinking(unalignedTabInCode))
    }

    @Test
    fun parse_listContextSurvivesIndentedCodeAndRecognizesNestedItems() {
        for (newline in listOf("\n", "\r\n")) {
            val input = "- parent${newline}${newline}      print(1)${newline}${newline}    - <think>secret</think>"
            for (streaming in listOf(false, true)) {
                val blocks = ChatMarkdownParser.parse(input, isStreaming = streaming)
                assertEquals(4, blocks.size)
                assertEquals("print(1)", (blocks[1] as ChatContentBlock.CodeBlock).code)
                assertEquals("    -", (blocks[2] as ChatContentBlock.ParagraphBlock).annotatedText.text)
                assertEquals("secret", (blocks[3] as ChatContentBlock.ThinkingBlock).content)
            }
            assertEquals("- parent${newline}${newline}      print(1)${newline}${newline}    -", ChatMarkdownParser.stripThinking(input))
        }
        val nested = "- parent\n  - child\n\n        code\n\n    - grandchild <think>secret</think>"
        val blocks = ChatMarkdownParser.parse(nested)
        assertEquals("code", (blocks[1] as ChatContentBlock.CodeBlock).code)
        assertTrue(blocks.last() is ChatContentBlock.ThinkingBlock)
        assertEquals("- parent\n  - child\n\n        code\n\n    - grandchild", ChatMarkdownParser.stripThinking(nested))
    }

    @Test
    fun parse_listCodeFollowedByParentContinuationOrOutsideText() {
        val continuation = "- parent\n\n      code\n\n  continuation <think>secret</think>"
        val continuationBlocks = ChatMarkdownParser.parse(continuation)
        assertEquals("code", (continuationBlocks[1] as ChatContentBlock.CodeBlock).code)
        assertEquals("  continuation", (continuationBlocks[2] as ChatContentBlock.ParagraphBlock).annotatedText.text)
        assertEquals("secret", (continuationBlocks[3] as ChatContentBlock.ThinkingBlock).content)
        assertEquals("- parent\n\n      code\n\n  continuation", ChatMarkdownParser.stripThinking(continuation))

        val outside = "- parent\n\n      code\n\noutside\n\n    <think>literal</think>"
        val blocks = ChatMarkdownParser.parse(outside)
        assertEquals(4, blocks.size)
        assertEquals("code", (blocks[1] as ChatContentBlock.CodeBlock).code)
        assertEquals("outside", (blocks[2] as ChatContentBlock.ParagraphBlock).annotatedText.text)
        assertEquals("<think>literal</think>", (blocks[3] as ChatContentBlock.CodeBlock).code)
        assertEquals(outside, ChatMarkdownParser.stripThinking(outside))
    }

    @Test
    fun parse_nestedListReturnRestoresParentCodeIndent() {
        for (newline in listOf("\n", "\r\n")) {
            val input = "- parent${newline}  - child${newline}    - grandchild${newline}  parent continuation${newline}${newline}      <think>literal</think>"
            for (streaming in listOf(false, true)) {
                val blocks = ChatMarkdownParser.parse(input, isStreaming = streaming)
                assertEquals(2, blocks.size)
                val code = blocks[1] as ChatContentBlock.CodeBlock
                assertEquals("<think>literal</think>", code.code)
                assertEquals(streaming, code.isStreaming)
            }
            assertEquals(input, ChatMarkdownParser.stripThinking(input))
        }
        val childContinuation = "- parent\n  - child\n    child continuation\n\n        <think>literal</think>"
        assertEquals("<think>literal</think>", (ChatMarkdownParser.parse(childContinuation)[1] as ChatContentBlock.CodeBlock).code)
        assertEquals(childContinuation, ChatMarkdownParser.stripThinking(childContinuation))
        val thinkingInParent = "- parent\n  - child\n  parent continuation <think>secret</think>"
        assertTrue(ChatMarkdownParser.parse(thinkingInParent).any { it is ChatContentBlock.ThinkingBlock })
        assertEquals("- parent\n  - child\n  parent continuation", ChatMarkdownParser.stripThinking(thinkingInParent))
    }

    @Test(timeout = 5000)
    fun bounds_deepListContextPushAndParentReturn() {
        val input = buildString {
            append("- root\n")
            for (depth in 1..300) append(" ".repeat(depth * 2)).append("- child\n")
            append("  parent continuation\n\n      <think>literal</think>")
        }
        val blocks = ChatMarkdownParser.parse(input)
        assertEquals(2, blocks.size)
        assertEquals("<think>literal</think>", (blocks[1] as ChatContentBlock.CodeBlock).code)
        assertEquals(input, ChatMarkdownParser.stripThinking(input))
    }

    @Test
    fun parse_thinkingEndsListContextButInlineCodeDoesNot() {
        val input = "- parent <think>secret\n</think>\n\n    <think>literal</think>"
        for (streaming in listOf(false, true)) {
            val blocks = ChatMarkdownParser.parse(input, isStreaming = streaming)
            assertEquals(3, blocks.size)
            assertEquals("secret", (blocks[1] as ChatContentBlock.ThinkingBlock).content)
            assertEquals("<think>literal</think>", (blocks[2] as ChatContentBlock.CodeBlock).code)
        }
        assertEquals("- parent \n\n    <think>literal</think>", ChatMarkdownParser.stripThinking(input))

        val inline = "- parent `<think>literal</think>`\n\n    continuation"
        assertTrue(ChatMarkdownParser.parse(inline).single() is ChatContentBlock.ParagraphBlock)
        assertEquals(inline, ChatMarkdownParser.stripThinking(inline))
    }

    @Test(timeout = 5000)
    fun bounds_manyListRelativeCodeBlocksAndWhitespace() {
        val input = "- item\n\n      code\n\n".repeat(6000)
        val blocks = ChatMarkdownParser.parse(input)
        assertEquals(12000, blocks.size)
        assertTrue(blocks.filterIsInstance<ChatContentBlock.CodeBlock>().all { it.code == "code" })
        assertEquals(input.trimEnd(), ChatMarkdownParser.stripThinking(input))
        assertTrue(ChatMarkdownParser.parse("     ").isEmpty())
    }

    @Test(timeout = 5000)
    fun bounds_manyBlankLinesInsideIndentedCodeAndLists() {
        val code = "    a\n" + "\n    b\n".repeat(12000)
        val block = ChatMarkdownParser.parse(code).single() as ChatContentBlock.CodeBlock
        assertEquals("a\n" + "\nb\n".repeat(11999) + "\nb", block.code)
        val list = "- parent\n" + "\n".repeat(12000) + "    - child"
        assertTrue(ChatMarkdownParser.parse(list).single() is ChatContentBlock.ParagraphBlock)
    }

    @Test(timeout = 5000)
    fun bounds_manyDoubleBacktickSpansAndIndentedLines() {
        val spans = "``x `<think>literal</think>` y`` ".repeat(12000)
        assertEquals(1, ChatMarkdownParser.parse(spans).size)
        assertEquals(spans.trimEnd(), ChatMarkdownParser.stripThinking(spans))
        val code = "    <think>literal</think>\n".repeat(12000)
        assertEquals(1, ChatMarkdownParser.parse(code).size)
        assertEquals(code.trimEnd(), ChatMarkdownParser.stripThinking(code))
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
            assertEquals(input, ChatMarkdownParser.stripThinking(input))
        }
        for (indent in listOf("    ", "     ", "\t", "  \t")) {
            val input = "${indent}```\n<think>secret</think>\n${indent}```"
            for (streaming in listOf(false, true)) {
                val blocks = ChatMarkdownParser.parse(input, isStreaming = streaming)
                assertEquals(3, blocks.size)
                val expectedCode = indent.substring(when (indent) { "    " -> 4; "     " -> 4; "\t" -> 1; else -> 3 }) + "```"
                assertEquals(expectedCode, (blocks[0] as ChatContentBlock.CodeBlock).code)
                assertEquals("secret", (blocks[1] as ChatContentBlock.ThinkingBlock).content)
                assertEquals("${indent}```", (blocks[2] as ChatContentBlock.ParagraphBlock).annotatedText.text)
            }
            assertEquals("${indent}```\n\n${indent}```", ChatMarkdownParser.stripThinking(input))
        }
        val inline = "prefix ```txt\ncode\n```\nafter"
        val blocks = ChatMarkdownParser.parse(inline)
        assertEquals("prefix", (blocks[0] as ChatContentBlock.ParagraphBlock).annotatedText.text)
        assertEquals("code", (blocks[1] as ChatContentBlock.CodeBlock).code)
        assertEquals("after", (blocks[2] as ChatContentBlock.ParagraphBlock).annotatedText.text)
        assertEquals(inline, ChatMarkdownParser.stripThinking(inline))
        val continued = "lead\n    prefix ```txt\ncode\n```\nafter"
        val continuedBlocks = ChatMarkdownParser.parse(continued)
        assertEquals("lead\n    prefix", (continuedBlocks[0] as ChatContentBlock.ParagraphBlock).annotatedText.text)
        assertEquals("code", (continuedBlocks[1] as ChatContentBlock.CodeBlock).code)
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
        assertEquals(1, ChatMarkdownParser.parse(rejected).count { it is ChatContentBlock.CodeBlock })
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
