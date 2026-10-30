package dev.librepocket.agent.ui.chat

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.librepocket.agent.R
import dev.librepocket.chat.UiMessage
import dev.librepocket.voice.VoiceTts
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeClipboardManager(
    private val throwOnSet: Boolean = false,
) : ClipboardManager {
    private var clipboardContent: AnnotatedString? = null
    override fun getText(): AnnotatedString? = clipboardContent
    override fun setText(annotatedString: AnnotatedString) {
        if (throwOnSet) {
            throw SecurityException("Clipboard access denied by MDM policy")
        }
        clipboardContent = annotatedString
    }
    override fun hasText(): Boolean = clipboardContent != null
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatMarkdownUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

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

        composeRule.onNodeWithText(context.getString(R.string.chat_thinking_title))
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        composeRule.onNodeWithText(context.getString(R.string.chat_thinking_in_progress)).assertIsDisplayed()
        composeRule.onNodeWithText("Analyzing the algorithm... ▍").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_thinking_collapse)).assertIsDisplayed()
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

        val title = context.getString(R.string.chat_thinking_title)
        val expandDesc = context.getString(R.string.chat_thinking_expand)
        val collapseDesc = context.getString(R.string.chat_thinking_collapse)

        // Title is visible
        composeRule.onNodeWithText(title)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        composeRule.onNodeWithContentDescription(expandDesc).assertIsDisplayed()

        // Content initially collapsed
        composeRule.onNodeWithText("Detailed reasoning steps here").assertDoesNotExist()

        // Click to expand
        composeRule.onNodeWithText(title).performClick()
        composeRule.onNodeWithText(title)
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        composeRule.onNodeWithText("Detailed reasoning steps here").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(collapseDesc).assertIsDisplayed()

        // Click to collapse again
        composeRule.onNodeWithText(title).performClick()
        composeRule.onNodeWithText("Detailed reasoning steps here").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(expandDesc).assertIsDisplayed()
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

        val copyText = context.getString(R.string.chat_code_copy)
        val copiedText = context.getString(R.string.chat_code_copied)

        // Verify language label and code content
        composeRule.onNodeWithText("kotlin").assertIsDisplayed()
        composeRule.onNodeWithText(codeText).assertIsDisplayed()
        composeRule.onNodeWithText(copyText)
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))

        // Click copy button
        composeRule.onNodeWithText(copyText).performClick()

        // Verify copy feedback and clipboard contents
        composeRule.onNodeWithText(copiedText).assertIsDisplayed()
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

        composeRule.onNodeWithText(context.getString(R.string.chat_code_default_language)).assertIsDisplayed()
        composeRule.onNodeWithText("SELECT 1;").assertIsDisplayed()
    }

    @Test
    fun codeBlockView_longLanguage_keepsCopyTargetAccessible() {
        val fakeClipboard = FakeClipboardManager()
        val block = ChatContentBlock.CodeBlock(
            language = "a".repeat(200),
            code = "code",
            isStreaming = false,
        )

        composeRule.setContent {
            CompositionLocalProvider(LocalClipboardManager provides fakeClipboard) {
                MaterialTheme {
                    Box(Modifier.size(width = 160.dp, height = 120.dp).clipToBounds()) {
                        CodeBlockView(
                            block = block,
                            modifier = Modifier.wrapContentHeight(align = Alignment.Top, unbounded = true),
                        )
                    }
                }
            }
        }

        val copyText = context.getString(R.string.chat_code_copy)
        val copiedText = context.getString(R.string.chat_code_copied)

        composeRule.onNodeWithText(copyText)
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        composeRule.onNodeWithText(copiedText).assertIsDisplayed()
        assertEquals("code", fakeClipboard.getText()?.text)
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
        composeRule.onNodeWithText(context.getString(R.string.chat_thinking_title)).assertIsDisplayed()

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

        composeRule.onNodeWithText(context.getString(R.string.chat_thinking_title)).assertIsDisplayed()
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

        composeRule.onNodeWithText(context.getString(R.string.chat_thinking_title)).assertIsDisplayed()
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

    @Test
    fun messageBubble_userThinkingTags_areDisplayedLiterally() {
        val text = "Before <think>literal</think> <thought>note</thought> <thinking>unfinished **text**"
        val msg = UiMessage(id = "msg-user-tags", role = "user", text = text, isPartial = false)

        composeRule.setContent {
            MaterialTheme { MessageBubble(msg = msg) }
        }

        composeRule.onNodeWithText(text).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.chat_thinking_title)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(VoiceTts.SPEAK_BUTTON_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun thinkingBlockView_streaming_userCanManuallyCollapse() {
        val block = ChatContentBlock.ThinkingBlock(
            content = "Streaming steps...",
            isStreaming = true,
        )

        composeRule.setContent {
            MaterialTheme {
                ThinkingBlockView(block = block)
            }
        }

        val title = context.getString(R.string.chat_thinking_title)
        val expandDesc = context.getString(R.string.chat_thinking_expand)
        val collapseDesc = context.getString(R.string.chat_thinking_collapse)

        // Initially expanded
        composeRule.onNodeWithText("Streaming steps... ▍").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(collapseDesc).assertIsDisplayed()

        // User clicks to collapse
        composeRule.onNodeWithText(title).performClick()

        // Now collapsed even though isStreaming is true
        composeRule.onNodeWithText("Streaming steps... ▍").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(expandDesc).assertIsDisplayed()
    }

    @Test
    fun codeBlockView_clipboardException_handledGracefully() {
        val throwingClipboard = FakeClipboardManager(throwOnSet = true)
        val block = ChatContentBlock.CodeBlock(
            language = "kotlin",
            code = "val x = 1",
            isStreaming = false,
        )

        composeRule.setContent {
            CompositionLocalProvider(LocalClipboardManager provides throwingClipboard) {
                MaterialTheme {
                    CodeBlockView(block = block)
                }
            }
        }

        val copyText = context.getString(R.string.chat_code_copy)

        // Click copy button; should not crash
        composeRule.onNodeWithText(copyText).performClick()
        // Should remain copy text rather than crash
        composeRule.onNodeWithText(copyText).assertIsDisplayed()
    }

    @Test
    fun localizedStrings_chinese_loadsCorrectly() {
        val zhConfig = Configuration(context.resources.configuration).apply {
            setLocale(Locale.CHINESE)
        }
        val zhContext = context.createConfigurationContext(zhConfig)

        assertEquals("思考過程", zhContext.getString(R.string.chat_thinking_title))
        assertEquals("思考中…", zhContext.getString(R.string.chat_thinking_in_progress))
        assertEquals("收折思考過程", zhContext.getString(R.string.chat_thinking_collapse))
        assertEquals("展開思考過程", zhContext.getString(R.string.chat_thinking_expand))
        assertEquals("Code", zhContext.getString(R.string.chat_code_default_language))
        assertEquals("複製程式碼", zhContext.getString(R.string.chat_code_copy_description))
        assertEquals("複製", zhContext.getString(R.string.chat_code_copy))
        assertEquals("已複製", zhContext.getString(R.string.chat_code_copied))
    }

    @Test
    fun localizedStrings_defaultLocale_loadsEnglishFallback() {
        val enConfig = Configuration(context.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        }
        val enContext = context.createConfigurationContext(enConfig)

        assertEquals("Thinking Process", enContext.getString(R.string.chat_thinking_title))
        assertEquals("Thinking…", enContext.getString(R.string.chat_thinking_in_progress))
        assertEquals("Collapse thinking process", enContext.getString(R.string.chat_thinking_collapse))
        assertEquals("Expand thinking process", enContext.getString(R.string.chat_thinking_expand))
        assertEquals("Code", enContext.getString(R.string.chat_code_default_language))
        assertEquals("Copy code", enContext.getString(R.string.chat_code_copy_description))
        assertEquals("Copy", enContext.getString(R.string.chat_code_copy))
        assertEquals("Copied", enContext.getString(R.string.chat_code_copied))
    }

    @Test
    @Config(qualifiers = "zh")
    fun thinkingBlockView_rendersInChineseWhenConfigured() {
        val block = ChatContentBlock.ThinkingBlock(
            content = "分析演算法中...",
            isStreaming = true,
        )

        composeRule.setContent {
            MaterialTheme {
                ThinkingBlockView(block = block)
            }
        }

        composeRule.onNodeWithText("思考過程").assertIsDisplayed()
        composeRule.onNodeWithText("思考中…").assertIsDisplayed()
        composeRule.onNodeWithText("分析演算法中... ▍").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("收折思考過程").assertIsDisplayed()
    }
}
