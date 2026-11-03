package dev.librepocket.agent.ui.chat

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun codeBlockView_copyButton_autoResetsAfterTwoSeconds() {
        val fakeClipboard = FakeClipboardManager()
        val codeText = "val x = 42"
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

        // Initially displays "Copy"
        composeRule.onNodeWithText(copyText).assertIsDisplayed()
        composeRule.onNodeWithText(copiedText).assertDoesNotExist()

        // Click "Copy" button
        composeRule.onNodeWithText(copyText).performClick()

        // Displays "Copied" and writes to clipboard
        composeRule.onNodeWithText(copiedText).assertIsDisplayed()
        composeRule.onNodeWithText(copyText).assertDoesNotExist()
        assertEquals(codeText, fakeClipboard.getText()?.text)

        // Advance virtual time by 2000ms
        composeRule.mainClock.advanceTimeBy(2000)

        // Automatically resets back to "Copy"
        composeRule.onNodeWithText(copyText).assertIsDisplayed()
        composeRule.onNodeWithText(copiedText).assertDoesNotExist()
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
    fun messageBubble_thinkingWithAttributes_rendersThinkingAndBody_speaksOnlyBodyText() {
        val msg = UiMessage(
            id = "msg-attr-thinking",
            role = "assistant",
            text = "<think id=\"1\">內心思考</think>回答正文",
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

        val thinkingTitle = context.getString(R.string.chat_thinking_title)
        val collapseDesc = context.getString(R.string.chat_thinking_collapse)
        val expandDesc = context.getString(R.string.chat_thinking_expand)

        // UI renders ThinkingBlock title and answer body
        composeRule.onNodeWithText(thinkingTitle).assertIsDisplayed()
        composeRule.onNodeWithText("回答正文").assertIsDisplayed()

        // Verify ThinkingBlock content can be expanded and inspected
        composeRule.onNodeWithContentDescription(expandDesc).assertIsDisplayed()
        composeRule.onNodeWithText("內心思考").assertDoesNotExist()

        composeRule.onNodeWithText(thinkingTitle).performClick()
        composeRule.onNodeWithText("內心思考").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(collapseDesc).assertIsDisplayed()

        // TTS button is displayed
        composeRule.onNodeWithContentDescription(VoiceTts.SPEAK_BUTTON_DESCRIPTION).assertIsDisplayed()

        // Click TTS button: spoken text must strictly contain only "回答正文" without thinking tags or content
        composeRule.onNodeWithContentDescription(VoiceTts.SPEAK_BUTTON_DESCRIPTION).performClick()
        assertEquals("msg-attr-thinking", spokenId)
        assertEquals("回答正文", spokenText)
    }

    @Test
    fun messageBubble_pureThinkingWithAttributes_doesNotShowSpeakButton() {
        val msg = UiMessage(
            id = "msg-secret-thought",
            role = "assistant",
            text = "<thought status=\"secret\">純機密思考</thought>",
            isPartial = false,
        )
        var speakInvoked = false

        composeRule.setContent {
            MaterialTheme {
                MessageBubble(
                    msg = msg,
                    showSpeak = true,
                    onSpeak = { _, _ ->
                        speakInvoked = true
                    },
                )
            }
        }

        val thinkingTitle = context.getString(R.string.chat_thinking_title)

        // UI renders ThinkingBlock title
        composeRule.onNodeWithText(thinkingTitle).assertIsDisplayed()

        // Verify thinking content inside block
        composeRule.onNodeWithText(thinkingTitle).performClick()
        composeRule.onNodeWithText("純機密思考").assertIsDisplayed()

        // TTS button must strictly NOT be displayed (Fail-Closed)
        composeRule.onNodeWithContentDescription(VoiceTts.SPEAK_BUTTON_DESCRIPTION).assertDoesNotExist()
        assertFalse(speakInvoked)
    }

    @Test
    fun messageBubble_unclosedOverlongAttributeThinking_doesNotShowSpeakButton() {
        val overlongTag = "<think note=\"" + "a".repeat(600) + "\">UNCLOSED_SECRET_THOUGHT"
        val msg = UiMessage(
            id = "msg-unclosed-overlong-thinking",
            role = "assistant",
            text = overlongTag,
            isPartial = false,
        )
        var currentMsg by mutableStateOf(msg)

        composeRule.setContent {
            MaterialTheme {
                MessageBubble(
                    msg = currentMsg,
                    showSpeak = true,
                )
            }
        }

        // TTS button must strictly NOT be displayed for unclosed overlong attribute thinking
        composeRule.onNodeWithContentDescription(VoiceTts.SPEAK_BUTTON_DESCRIPTION).assertDoesNotExist()

        val msgWithPrior = UiMessage(
            id = "msg-unclosed-prior-thinking",
            role = "assistant",
            text = "<think>prior</think><think note=\"" + "a".repeat(600) + "\">PRIVATE",
            isPartial = false,
        )

        currentMsg = msgWithPrior
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(VoiceTts.SPEAK_BUTTON_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun messageBubble_thinkingWithWhitespaceAndAttributes_rendersUIAndSuppressesTtsProperly() {
        val msg = UiMessage(
            id = "msg-whitespace-attr",
            role = "assistant",
            text = "<think   id=\"1\"  >內心思考</think   >回答正文",
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

        val thinkingTitle = context.getString(R.string.chat_thinking_title)

        composeRule.onNodeWithText(thinkingTitle).assertIsDisplayed()
        composeRule.onNodeWithText("回答正文").assertIsDisplayed()

        composeRule.onNodeWithContentDescription(VoiceTts.SPEAK_BUTTON_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(VoiceTts.SPEAK_BUTTON_DESCRIPTION).performClick()

        assertEquals("msg-whitespace-attr", spokenId)
        assertEquals("回答正文", spokenText)
    }

    @Test
    fun messageBubble_pureThinkingWithWhitespaceInTags_doesNotShowSpeakButton() {
        val msg = UiMessage(
            id = "msg-whitespace-pure",
            role = "assistant",
            text = "<thinking   >純空白思考</thinking   >",
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
    fun thinkingBlockView_streamingTransition_autoCollapsesWhenNoUserIntervention() {
        var block by mutableStateOf(
            ChatContentBlock.ThinkingBlock(
                content = "Analyzing the algorithm...",
                isStreaming = true,
            )
        )

        composeRule.setContent {
            MaterialTheme {
                ThinkingBlockView(block = block)
            }
        }

        val title = context.getString(R.string.chat_thinking_title)
        val inProgress = context.getString(R.string.chat_thinking_in_progress)
        val expandDesc = context.getString(R.string.chat_thinking_expand)
        val collapseDesc = context.getString(R.string.chat_thinking_collapse)

        // During streaming: initially expanded by default
        composeRule.onNodeWithText(title).assertIsDisplayed()
        composeRule.onNodeWithText(inProgress).assertIsDisplayed()
        composeRule.onNodeWithText("Analyzing the algorithm... ▍").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(collapseDesc).assertIsDisplayed()

        // Transition: streaming finishes without user intervention
        block = block.copy(isStreaming = false)

        // Automatically transitions to collapsed state
        composeRule.onNodeWithText(title).assertIsDisplayed()
        composeRule.onNodeWithText(inProgress).assertDoesNotExist()
        composeRule.onNodeWithText("Analyzing the algorithm... ▍").assertDoesNotExist()
        composeRule.onNodeWithText("Analyzing the algorithm...").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(expandDesc).assertIsDisplayed()
    }

    @Test
    fun thinkingBlockView_streamingTransition_preservesUserPreferenceWhenManuallyExpandedOrCollapsed() {
        var block by mutableStateOf(
            ChatContentBlock.ThinkingBlock(
                content = "Reasoning step 1...",
                isStreaming = true,
            )
        )

        composeRule.setContent {
            MaterialTheme {
                ThinkingBlockView(block = block)
            }
        }

        val title = context.getString(R.string.chat_thinking_title)
        val expandDesc = context.getString(R.string.chat_thinking_expand)
        val collapseDesc = context.getString(R.string.chat_thinking_collapse)

        // 1. Manually collapse during streaming
        composeRule.onNodeWithText("Reasoning step 1... ▍").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(collapseDesc).assertIsDisplayed()

        composeRule.onNodeWithText(title).performClick()
        composeRule.onNodeWithText("Reasoning step 1... ▍").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(expandDesc).assertIsDisplayed()

        // Transition to non-streaming: user's collapsed preference is preserved
        block = block.copy(isStreaming = false)
        composeRule.onNodeWithText("Reasoning step 1...").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(expandDesc).assertIsDisplayed()

        // 2. Manually expand while non-streaming
        composeRule.onNodeWithText(title).performClick()
        composeRule.onNodeWithText("Reasoning step 1...").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(collapseDesc).assertIsDisplayed()

        // Subsequent update preserves user's expanded preference
        block = block.copy(content = "Reasoning step 1 completed")
        composeRule.onNodeWithText("Reasoning step 1 completed").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(collapseDesc).assertIsDisplayed()
    }

    @Test
    fun thinkingBlockView_streamingTransition_manualExpandDuringStreaming_remainsExpandedAfterStreamingStops() {
        var block by mutableStateOf(
            ChatContentBlock.ThinkingBlock(
                content = "Detailed chain-of-thought...",
                isStreaming = true,
            )
        )

        composeRule.setContent {
            MaterialTheme {
                ThinkingBlockView(block = block)
            }
        }

        val title = context.getString(R.string.chat_thinking_title)
        val expandDesc = context.getString(R.string.chat_thinking_expand)
        val collapseDesc = context.getString(R.string.chat_thinking_collapse)

        // Initially expanded during streaming
        composeRule.onNodeWithText("Detailed chain-of-thought... ▍").assertIsDisplayed()

        // User clicks to collapse then clicks to explicitly re-expand (userExpanded becomes true)
        composeRule.onNodeWithText(title).performClick()
        composeRule.onNodeWithContentDescription(expandDesc).assertIsDisplayed()
        composeRule.onNodeWithText(title).performClick()
        composeRule.onNodeWithContentDescription(collapseDesc).assertIsDisplayed()
        composeRule.onNodeWithText("Detailed chain-of-thought... ▍").assertIsDisplayed()

        // Transition from streaming to non-streaming:
        // Unlike default (which collapses when userExpanded is null), userExpanded is true, so it remains expanded!
        block = block.copy(isStreaming = false)

        composeRule.onNodeWithText("Detailed chain-of-thought...").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(collapseDesc).assertIsDisplayed()
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
        // Verify flavor app_name is preserved in Chinese locale rather than overridden by main
        assertEquals(context.getString(R.string.app_name), zhContext.getString(R.string.app_name))
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

    @Test
    fun sanitizeCodeForClipboard_stripsBidiAndCarriageReturns() {
        // BiDi override, embedding, and isolate characters (\u202A..\u202E and \u2066..\u2069)
        val bidiChars = "\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069"
        val raw = "line1\r\nline2\rline3\u202Eevil${bidiChars}rest"
        val cleaned = sanitizeCodeForClipboard(raw)
        assertEquals("line1\nline2line3evilrest", cleaned)
    }

    @Test
    fun codeBlockView_streaming_copyButtonDisabledAndDoesNotCopy() {
        val fakeClipboard = FakeClipboardManager()
        val codeText = "fun inProgress() {\n    val s = 1"
        val block = ChatContentBlock.CodeBlock(
            language = "kotlin",
            code = codeText,
            isStreaming = true,
        )

        composeRule.setContent {
            CompositionLocalProvider(LocalClipboardManager provides fakeClipboard) {
                MaterialTheme {
                    CodeBlockView(block = block)
                }
            }
        }

        val copyDesc = context.getString(R.string.chat_code_copy_description)

        // Verify copy button accessibility description exists and is disabled during streaming
        composeRule.onNodeWithContentDescription(copyDesc)
            .assertIsDisplayed()
            .assertIsNotEnabled()

        // Attempting click must not populate clipboard
        try {
            composeRule.onNodeWithContentDescription(copyDesc).performClick()
        } catch (_: AssertionError) {
            // Disabled semantic nodes reject performClick in Compose rule
        }
        assertNull(fakeClipboard.getText())
    }

    @Test
    fun codeBlockView_nonStreaming_copyButtonEnabledAndCopiesSanitizedCode() {
        val fakeClipboard = FakeClipboardManager()
        val dirtyCode = "rm -rf /\r\n# innocent\u202E\rpython test.py"
        val block = ChatContentBlock.CodeBlock(
            language = "bash",
            code = dirtyCode,
            isStreaming = false,
        )

        composeRule.setContent {
            CompositionLocalProvider(LocalClipboardManager provides fakeClipboard) {
                MaterialTheme {
                    CodeBlockView(block = block)
                }
            }
        }

        val copyDesc = context.getString(R.string.chat_code_copy_description)
        val copiedDesc = context.getString(R.string.chat_code_copied)
        composeRule.onNodeWithContentDescription(copyDesc)
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        val copied = fakeClipboard.getText()?.text
        assertEquals("rm -rf /\n# innocentpython test.py", copied)

        // Verify content description updates to "Copied" for TalkBack screen reader
        composeRule.onNodeWithContentDescription(copiedDesc).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(copyDesc).assertDoesNotExist()

        // Advance virtual time by 2000ms to verify auto-reset back to default description
        composeRule.mainClock.advanceTimeBy(2000)
        composeRule.onNodeWithContentDescription(copyDesc).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(copiedDesc).assertDoesNotExist()
    }

    @Test
    fun messageBubble_pureThinkingMessages_hidesSpeakButton() {
        val pureThinkingSamples = listOf(
            "<think>Single closed reasoning</think>",
            "<thought>Alternative tag content</thought>",
            "<thinking>Another variant</thinking>",
            "<think>Step 1</think><thought>Step 2</thought>",
            "<think>Step 1</think>\n\n<thought>Step 2</thought>",
            "<think>Unclosed reasoning in progress...",
            "<thought>",
            "</think>",
            "<thinking></thinking>",
            "<think class=\"reasoning\">Styled think</think>",
            "   <think>Indented think</think>   ",
        )

        var currentMsg by mutableStateOf(
            UiMessage(
                id = "pure-think-init",
                role = "assistant",
                text = pureThinkingSamples.first(),
                isPartial = false,
            )
        )

        composeRule.setContent {
            MaterialTheme {
                MessageBubble(
                    msg = currentMsg,
                    showSpeak = true,
                )
            }
        }

        pureThinkingSamples.forEach { sampleText ->
            currentMsg = UiMessage(
                id = "pure-think-sample",
                role = "assistant",
                text = sampleText,
                isPartial = false,
            )
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription(VoiceTts.SPEAK_BUTTON_DESCRIPTION)
                .assertDoesNotExist()
        }
    }

    @Test
    fun chatMarkdownView_streamingThinkingBlockClosed_rendersTrailingCursor() {
        // Thinking block is closed, so ThinkingBlock.isStreaming = false,
        // but the message turn is still streaming (e.g. waiting for answer tokens).
        val text = "<think>Reasoning complete.</think>"

        composeRule.setContent {
            MaterialTheme {
                ChatMarkdownView(
                    text = text,
                    isStreaming = true,
                )
            }
        }

        // Thinking card is rendered (collapsed by default when isStreaming is false on the block)
        composeRule.onNodeWithText(context.getString(R.string.chat_thinking_title)).assertIsDisplayed()
        // Trailing cursor is displayed at the bottom of the column to prevent UI stall feeling
        composeRule.onNodeWithText("▍").assertIsDisplayed()
    }

    @Test
    fun chatMarkdownView_streamingCodeBlockClosed_rendersTrailingCursor() {
        // Code block is closed (``` closed with trailing newline), so CodeBlock.isStreaming = false,
        // but stream turn is still ongoing (e.g. waiting for trailing text).
        val text = "```kotlin\nval x = 1\n```\n"

        composeRule.setContent {
            MaterialTheme {
                ChatMarkdownView(
                    text = text,
                    isStreaming = true,
                )
            }
        }

        composeRule.onNodeWithText("kotlin").assertIsDisplayed()
        composeRule.onNodeWithText("▍").assertIsDisplayed()
    }

    @Test
    fun chatMarkdownView_streamingBlockInsertion_preservesThinkingBlockExpandedState() {
        // Initially, streaming message contains a closed thinking block
        var text by mutableStateOf("<think>Analyzing user query and planning response step 1</think>")
        val isStreaming by mutableStateOf(true)

        composeRule.setContent {
            MaterialTheme {
                ChatMarkdownView(
                    text = text,
                    isStreaming = isStreaming,
                )
            }
        }

        val thinkingTitle = context.getString(R.string.chat_thinking_title)
        val collapseDesc = context.getString(R.string.chat_thinking_collapse)
        val expandDesc = context.getString(R.string.chat_thinking_expand)

        // Thinking block is closed, so by default it collapses
        composeRule.onNodeWithContentDescription(expandDesc).assertIsDisplayed()

        // User manually expands the thinking block
        composeRule.onNodeWithText(thinkingTitle).performClick()
        composeRule.onNodeWithContentDescription(collapseDesc).assertIsDisplayed()
        composeRule.onNodeWithText("Analyzing user query and planning response step 1").assertIsDisplayed()

        // A preceding paragraph appears before the thinking block. The closed thinking
        // content itself is final (growth happens while streaming/unclosed, covered by
        // streaming-key tests), so its content-identity key is unchanged and expansion stays
        // on the same card instead of shifting positionally.
        text = "Leading context paragraph before thinking.\n\n<think>Analyzing user query and planning response step 1</think>\n\nFinal answer starting..."
        composeRule.waitForIdle()

        // Verify that the preceding paragraph is rendered
        composeRule.onNodeWithText("Leading context paragraph before thinking.").assertIsDisplayed()

        // Verify that the thinking block retained its manually expanded state!
        composeRule.onNodeWithContentDescription(collapseDesc).assertIsDisplayed()
        composeRule.onNodeWithText("Analyzing user query and planning response step 1").assertIsDisplayed()

        // Text progresses further with additional streaming chunks (answer grows, thinking unchanged)
        text = "Leading context paragraph before thinking.\n\n<think>Analyzing user query and planning response step 1</think>\n\nFinal answer starting... and completed!"
        composeRule.waitForIdle()

        // Thinking block continues to retain its expanded state without reset or misapplication
        composeRule.onNodeWithContentDescription(collapseDesc).assertIsDisplayed()
        composeRule.onNodeWithText("Analyzing user query and planning response step 1").assertIsDisplayed()
    }

    @Test
    fun chatMarkdownView_streamingBlockInsertion_doesNotMisapplyCopiedState() {
        val fakeClipboard = FakeClipboardManager()
        var text by mutableStateOf("```kotlin\nval x = 1\n```")

        composeRule.setContent {
            CompositionLocalProvider(LocalClipboardManager provides fakeClipboard) {
                MaterialTheme {
                    ChatMarkdownView(text = text, isStreaming = false)
                }
            }
        }

        val copyText = context.getString(R.string.chat_code_copy)
        val copiedText = context.getString(R.string.chat_code_copied)

        // Initial state: click copy on the Kotlin code block
        composeRule.onNodeWithText(copyText).performClick()
        composeRule.onNodeWithText(copiedText).assertIsDisplayed()

        // Prepend a Python code block before the Kotlin code block
        text = "```python\nprint('new')\n```\n\n```kotlin\nval x = 1\n```"
        composeRule.waitForIdle()

        // The newly prepended Python block must have "Copy", while the existing Kotlin block retains "Copied"
        composeRule.onNodeWithText("python").assertIsDisplayed()
        composeRule.onNodeWithText("kotlin").assertIsDisplayed()
        composeRule.onNodeWithText(copyText).assertIsDisplayed()
        composeRule.onNodeWithText(copiedText).assertIsDisplayed()
    }

    @Test
    fun chatMarkdownView_streamingThinking_preservesCollapsedStateAcrossShortContentUpdates() {
        var text by mutableStateOf("<think>Pl")

        composeRule.setContent {
            MaterialTheme {
                ChatMarkdownView(
                    text = text,
                    isStreaming = true,
                )
            }
        }

        val title = context.getString(R.string.chat_thinking_title)
        val collapseDesc = context.getString(R.string.chat_thinking_collapse)
        val expandDesc = context.getString(R.string.chat_thinking_expand)

        // Initial state during streaming: thinking card is expanded by default
        composeRule.onNodeWithText(title).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(collapseDesc).assertIsDisplayed()
        composeRule.onNodeWithText("Pl ▍").assertIsDisplayed()

        // User manually collapses the thinking card at <think>Pl
        composeRule.onNodeWithText(title).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(expandDesc).assertIsDisplayed()
        composeRule.onNodeWithText("Pl ▍").assertDoesNotExist()

        // Progressively stream chunks (< 32 characters): Pla -> Plan -> Planning
        text = "<think>Pla"
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(expandDesc).assertIsDisplayed()
        composeRule.onNodeWithText("Pla ▍").assertDoesNotExist()

        text = "<think>Plan"
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(expandDesc).assertIsDisplayed()
        composeRule.onNodeWithText("Plan ▍").assertDoesNotExist()

        text = "<think>Planning"
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(expandDesc).assertIsDisplayed()
        composeRule.onNodeWithText("Planning ▍").assertDoesNotExist()
    }

    @Test
    fun chatContentBlock_key_generatesStructuralKeys() {
        val blocks = ChatMarkdownParser.parse("<think>Analyzing user query</think>\n\nHello\n\n```kotlin\nval x = 1\n```\n\n<think>Second</think>")
        val expectedThinkA = "think_c_${ChatMarkdownParser.contentHashHex16("Analyzing user query")}"
        val expectedThinkB = "think_c_${ChatMarkdownParser.contentHashHex16("Second")}"
        assertEquals(expectedThinkA, blocks[0].key)
        assertEquals("para_0", blocks[1].key)
        val expectedCodeKey = "code_fenced_kotlin_${ChatMarkdownParser.contentHashHex16("val x = 1")}"
        assertEquals(expectedCodeKey, blocks[2].key)
        assertEquals(expectedThinkB, blocks[3].key)

        val think1 = ChatContentBlock.ThinkingBlock("Analyzing user query and planning step 1", isStreaming = true, key = "think_streaming_0")
        val think2 = ChatContentBlock.ThinkingBlock("Analyzing user query and planning step 1\nwith more text appended", isStreaming = true, key = "think_streaming_0")
        assertEquals(think1.key, think2.key)

        val code1 = ChatContentBlock.CodeBlock("kotlin", "val x = 1\nval y = 2", isStreaming = false, key = "code_fenced_kotlin_123")
        val code2 = ChatContentBlock.CodeBlock("kotlin", "val x = 1\nval y = 2", isStreaming = true, key = "code_fenced_kotlin_123")
        assertEquals(code1.key, code2.key)

        val para = ChatContentBlock.ParagraphBlock(AnnotatedString("Hello world"), key = "para_0")
        assertEquals("para_0", para.key)

        // Duplicate code blocks in the same message are disambiguated as base, base_dup2, ...
        val dupBlocks = ChatMarkdownParser.parse("```kotlin\nval x = 1\n```\n\n```kotlin\nval x = 1\n```")
        val baseCodeKey = "code_fenced_kotlin_${ChatMarkdownParser.contentHashHex16("val x = 1")}"
        assertEquals(baseCodeKey, dupBlocks[0].key)
        assertEquals("${baseCodeKey}_dup2", dupBlocks[1].key)

        // Streaming code blocks use streaming counter keys
        val streamingBlocks = ChatMarkdownParser.parse("```kotlin\nval x = 1", isStreaming = true)
        assertEquals("code_streaming_0", (streamingBlocks[0] as ChatContentBlock.CodeBlock).key)

        // Streaming thinking blocks use streaming keys stable across growth
        val streamingThink = ChatMarkdownParser.parse("<think>Pl", isStreaming = true)
        assertEquals("think_streaming_0", (streamingThink[0] as ChatContentBlock.ThinkingBlock).key)
    }

    @Test
    fun messageBubble_malformedSuspectScopeOverInnerBlock_doesNotShowSpeakButton() {
        val raw = "<think ??? >OUTER <think>inner</think>LEAK"
        val msg = UiMessage(
            id = "msg-malformed-scope-inner-leak",
            role = "assistant",
            text = raw,
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

        composeRule.onNodeWithContentDescription(VoiceTts.SPEAK_BUTTON_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun messageBubble_malformedSuspectScopeOverCodeBlock_doesNotShowSpeakButton() {
        val rawWithCode = "<think ??? >OUTER \n```\ncode\n```\nLEAK"
        val msg = UiMessage(
            id = "msg-malformed-scope-code-leak",
            role = "assistant",
            text = rawWithCode,
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

        composeRule.onNodeWithContentDescription(VoiceTts.SPEAK_BUTTON_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun chatMarkdownView_thinkingPrepend_preservesWhichCardIsExpanded() {
        // Regression for positional think_0/think_1 misbinding: prepending a *different*
        // thinking card must not move the user's expansion to the new card.
        var text by mutableStateOf("<think>Old reasoning content</think>\n\nAnswer")
        composeRule.setContent {
            MaterialTheme {
                ChatMarkdownView(text = text, isStreaming = false)
            }
        }
        val title = context.getString(R.string.chat_thinking_title)
        // Only one card initially: expand it.
        composeRule.onNodeWithText(title).performClick()
        composeRule.onNodeWithText("Old reasoning content").assertIsDisplayed()

        // Prepend a different thinking block before the old one.
        text = "<think>New reasoning content</think>\n\n<think>Old reasoning content</think>\n\nAnswer"
        composeRule.waitForIdle()

        // Old card (second) must retain expansion; new card (first) stays collapsed by default.
        composeRule.onNodeWithText("Old reasoning content").assertIsDisplayed()
        composeRule.onNodeWithText("New reasoning content").assertDoesNotExist()
        // Two thinking cards rendered.
        composeRule.onAllNodesWithText(title).assertCountEquals(2)
    }

    @Test
    fun chatMarkdownView_codeCollisionSwap_copiedFollowsContentNotPosition() {
        // "Aa"/"BB" collide under String.hashCode(); with content-identity keys Copied must
        // stay attached to the Aa card after order swap — verified per-card via layout
        // position, not mere existence of one Copied node.
        val fakeClipboard = FakeClipboardManager()
        var text by mutableStateOf("```kotlin\nAa\n```\n\n```kotlin\nBB\n```")
        composeRule.setContent {
            CompositionLocalProvider(LocalClipboardManager provides fakeClipboard) {
                MaterialTheme {
                    ChatMarkdownView(text = text, isStreaming = false)
                }
            }
        }
        val copyText = context.getString(R.string.chat_code_copy)
        val copiedText = context.getString(R.string.chat_code_copied)

        // Click Copy on the first card (Aa). Both cards initially show Copy.
        composeRule.onAllNodesWithText(copyText).assertCountEquals(2)
        composeRule.onAllNodesWithText(copyText)[0].performClick()
        composeRule.waitForIdle()
        fun yOf(matchText: String): Float =
            composeRule.onNodeWithText(matchText).fetchSemanticsNode().positionInRoot.y
        // Cards lay out top-to-bottom; each card's copy button sits above its code text.
        // Aa is the first card, so its Copied button must sit above BB's Copy button.
        assertTrue("Aa card must be first", yOf("Aa") < yOf("BB"))
        assertTrue("Copied must follow Aa card", yOf(copiedText) < yOf(copyText))

        // Swap order: BB first, Aa second. Copied must follow Aa to the second card.
        text = "```kotlin\nBB\n```\n\n```kotlin\nAa\n```"
        composeRule.waitForIdle()
        assertTrue("Aa card must now be second", yOf("BB") < yOf("Aa"))
        assertTrue(
            "Copied must follow Aa card after swap",
            yOf(copyText) < yOf(copiedText),
        )
        // BB card shows Copy (single Copy node remains).
        composeRule.onAllNodesWithText(copyText).assertCountEquals(1)
        composeRule.onAllNodesWithText(copiedText).assertCountEquals(1)
    }

    @Test
    fun chatMarkdownView_streamingToCompletedCode_pinsKeyChangeSemantics() {
        // Pin test: streaming→completed changes block.key (code_streaming_0 →
        // code_fenced_*), so Compose recreates the node and horizontal scroll resets.
        // Content and copy affordance must survive; scroll preservation is a P3 limitation.
        val streamingBlocks = ChatMarkdownParser.parse("```kotlin\npartial", isStreaming = true)
        val completedBlocks = ChatMarkdownParser.parse("```kotlin\npartial\n```", isStreaming = false)
        val streamingKey = (streamingBlocks[0] as ChatContentBlock.CodeBlock).key
        val completedKey = (completedBlocks[0] as ChatContentBlock.CodeBlock).key
        assertEquals("code_streaming_0", streamingKey)
        assertFalse(streamingKey == completedKey)

        val fakeClipboard = FakeClipboardManager()
        var text by mutableStateOf("```kotlin\npartial")
        var streaming by mutableStateOf(true)
        composeRule.setContent {
            CompositionLocalProvider(LocalClipboardManager provides fakeClipboard) {
                MaterialTheme {
                    ChatMarkdownView(text = text, isStreaming = streaming)
                }
            }
        }
        // Streaming: code + cursor visible, copy disabled.
        composeRule.onNodeWithText("partial ▍").assertIsDisplayed()

        // Complete the fence: same content, copy becomes enabled.
        text = "```kotlin\npartial\n```"
        streaming = false
        composeRule.waitForIdle()
        composeRule.onNodeWithText("partial").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.chat_code_copy)).assertIsDisplayed()
    }
}
