package dev.librepocket.agent.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import dev.librepocket.chat.UiMessage
import dev.librepocket.voice.VoiceTts
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeClipboardManager : ClipboardManager {
    private var clipboardContent: AnnotatedString? = null
    override fun getText(): AnnotatedString? = clipboardContent
    override fun setText(annotatedString: AnnotatedString) {
        clipboardContent = annotatedString
    }
    override fun hasText(): Boolean = clipboardContent != null
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatMarkdownUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun thinkingBlockView_streaming_initiallyExpanded() {
        val block = ChatContentBlock.ThinkingBlock(
            content = "Analyzing the algorithm...",
            isStreaming = true,
        )

        composeRule.setContent {
            MaterialTheme {
                ThinkingBlockView(block = block)
            }
        }

        composeRule.onNodeWithText("思考過程").assertIsDisplayed()
        composeRule.onNodeWithText("思考中…").assertIsDisplayed()
        composeRule.onNodeWithText("Analyzing the algorithm... ▍").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("收折思考過程").assertIsDisplayed()
    }

    @Test
    fun thinkingBlockView_notStreaming_collapsesByDefaultAndExpandsOnClick() {
        val block = ChatContentBlock.ThinkingBlock(
            content = "Detailed reasoning steps here",
            isStreaming = false,
        )

        composeRule.setContent {
            MaterialTheme {
                ThinkingBlockView(block = block)
            }
        }

        // Title is visible
        composeRule.onNodeWithText("思考過程").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("展開思考過程").assertIsDisplayed()

        // Content initially collapsed
        composeRule.onNodeWithText("Detailed reasoning steps here").assertDoesNotExist()

        // Click to expand
        composeRule.onNodeWithText("思考過程").performClick()
        composeRule.onNodeWithText("Detailed reasoning steps here").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("收折思考過程").assertIsDisplayed()

        // Click to collapse again
        composeRule.onNodeWithText("思考過程").performClick()
        composeRule.onNodeWithText("Detailed reasoning steps here").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("展開思考過程").assertIsDisplayed()
    }

    @Test
    fun codeBlockView_displaysLanguageAndCopiesCode() {
        val fakeClipboard = FakeClipboardManager()
        val codeText = "fun greet() = println(\"Hello\")"
        val block = ChatContentBlock.CodeBlock(
            language = "kotlin",
            code = codeText,
            isStreaming = false,
        )

        composeRule.setContent {
            CompositionLocalProvider(LocalClipboardManager provides fakeClipboard) {
                MaterialTheme {
                    CodeBlockView(block = block)
                }
            }
        }

        // Verify language label and code content
        composeRule.onNodeWithText("kotlin").assertIsDisplayed()
        composeRule.onNodeWithText(codeText).assertIsDisplayed()
        composeRule.onNodeWithText("複製").assertIsDisplayed()

        // Click copy button
        composeRule.onNodeWithText("複製").performClick()

        // Verify copy feedback and clipboard contents
        composeRule.onNodeWithText("已複製").assertIsDisplayed()
        assertEquals(codeText, fakeClipboard.getText()?.text)
    }

    @Test
    fun codeBlockView_defaultLanguageFallbackToCode() {
        val block = ChatContentBlock.CodeBlock(
            language = null,
            code = "SELECT 1;",
            isStreaming = false,
        )

        composeRule.setContent {
            MaterialTheme {
                CodeBlockView(block = block)
            }
        }

        composeRule.onNodeWithText("Code").assertIsDisplayed()
        composeRule.onNodeWithText("SELECT 1;").assertIsDisplayed()
    }

    @Test
    fun chatMarkdownView_rendersInterleavedBlocks() {
        val text = """
            <think>
            Step 1: Check inputs
            </think>
            Here is the code:
            ```python
            print("Done")
            ```
            All finished!
        """.trimIndent()

        composeRule.setContent {
            MaterialTheme {
                ChatMarkdownView(text = text, isStreaming = false)
            }
        }

        // Thinking header is visible
        composeRule.onNodeWithText("思考過程").assertIsDisplayed()

        // Paragraphs are visible
        composeRule.onNodeWithText("Here is the code:").assertIsDisplayed()
        composeRule.onNodeWithText("All finished!").assertIsDisplayed()

        // Code block is visible
        composeRule.onNodeWithText("python").assertIsDisplayed()
        composeRule.onNodeWithText("print(\"Done\")").assertIsDisplayed()
    }

    @Test
    fun chatMarkdownView_streamingParagraph_showsCursor() {
        val text = "Streaming response in progress"

        composeRule.setContent {
            MaterialTheme {
                ChatMarkdownView(text = text, isStreaming = true)
            }
        }

        composeRule.onNodeWithText("Streaming response in progress ▍").assertIsDisplayed()
    }

    @Test
    fun messageBubble_pureThinkingContent_doesNotShowSpeakButton() {
        val msg = UiMessage(
            id = "msg-pure-thinking",
            role = "assistant",
            text = "<think>Internal reasoning only without final reply</think>",
            isPartial = false,
        )

        composeRule.setContent {
            MaterialTheme {
                MessageBubble(
                    msg = msg,
                    showSpeak = true,
                )
            }
        }

        composeRule.onNodeWithText("思考過程").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(VoiceTts.SPEAK_BUTTON_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun messageBubble_thinkingWithContent_showsSpeakButtonAndSpeaksOnlyBodyText() {
        val msg = UiMessage(
            id = "msg-thinking-and-body",
            role = "assistant",
            text = "<think>Internal steps</think>Hello world!",
            isPartial = false,
        )
        var spokenId: String? = null
        var spokenText: String? = null

        composeRule.setContent {
            MaterialTheme {
                MessageBubble(
                    msg = msg,
                    showSpeak = true,
                    onSpeak = { id, text ->
                        spokenId = id
                        spokenText = text
                    },
                )
            }
        }

        composeRule.onNodeWithText("思考過程").assertIsDisplayed()
        composeRule.onNodeWithText("Hello world!").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(VoiceTts.SPEAK_BUTTON_DESCRIPTION).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(VoiceTts.SPEAK_BUTTON_DESCRIPTION).performClick()
        assertEquals("msg-thinking-and-body", spokenId)
        assertEquals("Hello world!", spokenText)
    }

    @Test
    fun messageBubble_userRole_doesNotShowSpeakButton() {
        val msg = UiMessage(
            id = "msg-user",
            role = "user",
            text = "User message text",
            isPartial = false,
        )

        composeRule.setContent {
            MaterialTheme {
                MessageBubble(
                    msg = msg,
                    showSpeak = true,
                )
            }
        }

        composeRule.onNodeWithText("User message text").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(VoiceTts.SPEAK_BUTTON_DESCRIPTION).assertDoesNotExist()
    }
}
