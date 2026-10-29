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
