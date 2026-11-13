package dev.librepocket.agent.ui.chat

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
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
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import org.junit.Assert.assertNotNull
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
        assertEquals("已展開", zhContext.getString(R.string.chat_thinking_state_expanded))
        assertEquals("已收合", zhContext.getString(R.string.chat_thinking_state_collapsed))
        assertEquals("Code", zhContext.getString(R.string.chat_code_default_language))
        assertEquals("複製程式碼", zhContext.getString(R.string.chat_code_copy_description))
        assertEquals("複製", zhContext.getString(R.string.chat_code_copy))
        assertEquals("已複製", zhContext.getString(R.string.chat_code_copied))
        assertEquals("正在串流回覆", zhContext.getString(R.string.chat_streaming_status))
        assertEquals("串流中…", zhContext.getString(R.string.chat_streaming_label))
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
        assertEquals("Expanded", enContext.getString(R.string.chat_thinking_state_expanded))
        assertEquals("Collapsed", enContext.getString(R.string.chat_thinking_state_collapsed))
        assertEquals("Code", enContext.getString(R.string.chat_code_default_language))
        assertEquals("Copy code", enContext.getString(R.string.chat_code_copy_description))
        assertEquals("Copy", enContext.getString(R.string.chat_code_copy))
        assertEquals("Copied", enContext.getString(R.string.chat_code_copied))
        assertEquals("Streaming reply in progress", enContext.getString(R.string.chat_streaming_status))
        assertEquals("Streaming…", enContext.getString(R.string.chat_streaming_label))
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

    @Test
    fun thinkingBlockView_header_exposesExpandedCollapsedStateDescription() {
        var block by mutableStateOf(
            ChatContentBlock.ThinkingBlock(
                content = "Reasoning...",
                isStreaming = false,
            )
        )

        composeRule.setContent {
            MaterialTheme {
                ThinkingBlockView(block = block)
            }
        }

        val title = context.getString(R.string.chat_thinking_title)
        val collapsedState = context.getString(R.string.chat_thinking_state_collapsed)
        val expandedState = context.getString(R.string.chat_thinking_state_expanded)

        // Collapsed by default when not streaming: header announces collapsed state.
        composeRule.onNodeWithText(title)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, collapsedState))

        // Expand: announced state flips to expanded.
        composeRule.onNodeWithText(title).performClick()
        composeRule.onNodeWithText(title)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expandedState))

        // Collapse again: announced state flips back.
        composeRule.onNodeWithText(title).performClick()
        composeRule.onNodeWithText(title)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, collapsedState))
    }

    @Test
    fun thinkingBlockView_streamingHeader_announcesExpandedStateByDefault() {
        val block = ChatContentBlock.ThinkingBlock(
            content = "Streaming...",
            isStreaming = true,
        )

        composeRule.setContent {
            MaterialTheme {
                ThinkingBlockView(block = block)
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.chat_thinking_title))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    context.getString(R.string.chat_thinking_state_expanded),
                )
            )
    }

    @Test
    fun codeBlockView_copyButton_isPoliteLiveRegion() {
        val block = ChatContentBlock.CodeBlock(
            language = "kotlin",
            code = "val x = 1",
            isStreaming = false,
        )

        composeRule.setContent {
            MaterialTheme {
                CodeBlockView(block = block)
            }
        }

        // Copy/Copied feedback is text-only, so the button must be a polite live region
        // for TalkBack to announce the Copied confirmation.
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_code_copy_description))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                )
            )
    }

    @Test
    fun codeBlockView_streamingCursor_announcesPlainCodeWithoutGlyph() {
        val code = "val streaming = true"

        composeRule.setContent {
            MaterialTheme {
                CodeBlockView(
                    block = ChatContentBlock.CodeBlock(
                        language = "kotlin",
                        code = code,
                        isStreaming = true,
                    )
                )
            }
        }

        // Visual text keeps the cursor...
        composeRule.onNodeWithText("$code \u258D").assertIsDisplayed()
        // ...but the announcement is the plain code without the cursor glyph.
        composeRule.onNodeWithContentDescription(code).assertIsDisplayed()
    }

    @Test
    fun thinkingBlockView_streamingCursor_announcesPlainContentWithoutGlyph() {
        val content = "Reasoning in progress"

        composeRule.setContent {
            MaterialTheme {
                ThinkingBlockView(
                    block = ChatContentBlock.ThinkingBlock(
                        content = content,
                        isStreaming = true,
                    )
                )
            }
        }

        composeRule.onNodeWithText("$content \u258D").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(content).assertIsDisplayed()
    }

    @Test
    fun chatMarkdownView_emptyStreaming_announcesStreamingLabelInsteadOfCursor() {
        // Blank input yields no blocks, hitting the standalone-cursor branch.
        composeRule.setContent {
            MaterialTheme {
                ChatMarkdownView(text = "   ", isStreaming = true)
            }
        }

        val label = context.getString(R.string.chat_streaming_label)
        composeRule.onNodeWithText("\u258D").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(label).assertIsDisplayed()
    }

    @Test
    fun chatMarkdownView_streamingParagraph_announcesTextWithoutCursor() {
        val body = "Streaming paragraph body"

        composeRule.setContent {
            MaterialTheme {
                ChatMarkdownView(text = body, isStreaming = true)
            }
        }

        composeRule.onNodeWithText("$body \u258D").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(body).assertIsDisplayed()
    }

    @Test
    fun chatMarkdownView_trailingCursor_announcesStreamingLabel() {
        composeRule.setContent {
            MaterialTheme {
                ChatMarkdownView(
                    text = "<think>Reasoning complete.</think>",
                    isStreaming = true,
                )
            }
        }

        val label = context.getString(R.string.chat_streaming_label)
        composeRule.onNodeWithText("\u258D").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(label).assertIsDisplayed()
    }

    @Test
    fun sanitizeCodeForClipboard_stripsEscAndInvisibleButPreservesJoinersAndMarks() {
        val raw = "ab\u001B[2Jcd\u200Be\u200Cf\u200Dg\u200Eh\u200Fi\uFEFFj\u2060k\u2061l\u2062m\u2063n\u2064o"
        assertEquals("ab[2Jcde\u200Cf\u200Dg\u200Eh\u200Fijklmno", sanitizeCodeForClipboard(raw))
    }

    @Test
    fun sanitizeCodeForClipboard_convertsLineAndParagraphSeparatorsToNewline() {
        assertEquals("a\nb", sanitizeCodeForClipboard("a\u2028b"))
        assertEquals("a\nb", sanitizeCodeForClipboard("a\u2029b"))
        // Plain newlines are preserved (multi-line paste risk is documented, not removed).
        assertEquals("a\nb", sanitizeCodeForClipboard("a\nb"))
    }

    @Test
    fun sanitizeCodeForClipboard_preservesZwJEmojiSequence() {
        val emoji = "👩\u200D💻"
        assertEquals(emoji, sanitizeCodeForClipboard(emoji))
    }

    @Test
    fun sanitizeCodeForClipboard_preservesMultilingualJoinersAndMarks() {
        // ZWNJ inside Persian/Hindi-adjacent text and LRM after Arabic: legitimate
        // joining / directional marks, kept verbatim for copy fidelity.
        val multilingual = "می\u200Cخواهم\u200C\u200D test עברית\u200Eالعربية"
        assertEquals(multilingual, sanitizeCodeForClipboard(multilingual))
    }

    @Test
    fun chatMarkdownView_streamingToCompleted_retainsManualExpandHoisted() {
        // Unclosed thinking manually expanded (explicit true via collapse→expand, since
        // streaming default is already expanded), then append </think> while the message
        // is still streaming: same trimmed content → same hash → override retained.
        var text by mutableStateOf("<think>Planning in progress")
        composeRule.setContent {
            MaterialTheme {
                ChatMarkdownView(text = text, isStreaming = true)
            }
        }

        val title = context.getString(R.string.chat_thinking_title)
        val expandDesc = context.getString(R.string.chat_thinking_expand)
        val collapseDesc = context.getString(R.string.chat_thinking_collapse)

        composeRule.onNodeWithText("Planning in progress ▍").assertIsDisplayed()
        // First click collapses (null → false), second re-expands (false → true, explicit).
        composeRule.onNodeWithText(title).performClick()
        composeRule.onNodeWithContentDescription(expandDesc).assertIsDisplayed()
        composeRule.onNodeWithText(title).performClick()
        composeRule.onNodeWithContentDescription(collapseDesc).assertIsDisplayed()
        composeRule.onNodeWithText("Planning in progress ▍").assertIsDisplayed()

        // Close the think tag; message-level isStreaming stays true but the block itself
        // becomes isStreaming=false. Exact-hash match must keep the manual expand.
        text = "<think>Planning in progress</think>"
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Planning in progress").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(collapseDesc).assertIsDisplayed()
    }

    @Test
    fun chatMarkdownView_streamingToCompleted_withoutInteraction_collapsesHoisted() {
        // No user interaction: appending </think> must auto-collapse per spec, even though
        // the message-level isStreaming is still true (block.isStreaming is false).
        var text by mutableStateOf("<think>Plan")

        composeRule.setContent {
            MaterialTheme {
                ChatMarkdownView(text = text, isStreaming = true)
            }
        }

        val expandDesc = context.getString(R.string.chat_thinking_expand)

        composeRule.onNodeWithText("Plan ▍").assertIsDisplayed()

        text = "<think>Plan</think>"
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Plan").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(expandDesc).assertIsDisplayed()
    }

    @Test
    fun chatMarkdownView_streamingGrowth_retainsManualExpandViaPrefixMigration() {
        // Streaming growth "Plan" → "Planning": stored hash(Plan)=true must migrate to
        // hash(Planning) because "Plan" is a strict prefix of "Planning".
        var text by mutableStateOf("<think>Plan")

        composeRule.setContent {
            MaterialTheme {
                ChatMarkdownView(text = text, isStreaming = true)
            }
        }

        val title = context.getString(R.string.chat_thinking_title)
        val expandDesc = context.getString(R.string.chat_thinking_expand)
        val collapseDesc = context.getString(R.string.chat_thinking_collapse)

        composeRule.onNodeWithText("Plan ▍").assertIsDisplayed()
        // Set explicit true (collapse then re-expand).
        composeRule.onNodeWithText(title).performClick()
        composeRule.onNodeWithContentDescription(expandDesc).assertIsDisplayed()
        composeRule.onNodeWithText(title).performClick()
        composeRule.onNodeWithContentDescription(collapseDesc).assertIsDisplayed()

        text = "<think>Planning"
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Planning ▍").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(collapseDesc).assertIsDisplayed()
    }

    @Test
    fun chatMarkdownView_streamingEmptyThinking_retainsManualCollapseAcrossGrowth() {
        // P2#1: collapsing the empty `<think>` card must survive growth to `<think>P`
        // and `<think>Plan` (empty string is a strict prefix of any growth).
        var text by mutableStateOf("<think>")

        composeRule.setContent {
            MaterialTheme {
                ChatMarkdownView(text = text, isStreaming = true)
            }
        }

        val title = context.getString(R.string.chat_thinking_title)
        val expandDesc = context.getString(R.string.chat_thinking_expand)
        val collapseDesc = context.getString(R.string.chat_thinking_collapse)

        // Empty streaming card defaults to expanded.
        composeRule.onNodeWithText(title).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(collapseDesc).assertIsDisplayed()

        // User manually collapses the empty card.
        composeRule.onNodeWithText(title).performClick()
        composeRule.onNodeWithContentDescription(expandDesc).assertIsDisplayed()

        // "" -> "P": manual collapse must be retained via prefix migration.
        text = "<think>P"
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(expandDesc).assertIsDisplayed()
        composeRule.onNodeWithText("P ▍").assertDoesNotExist()

        // "P" -> "Plan": retained again.
        text = "<think>Plan"
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(expandDesc).assertIsDisplayed()
        composeRule.onNodeWithText("Plan ▍").assertDoesNotExist()
    }

    @Test
    fun chatMarkdownView_sameContentThinkingCards_expandFirstOnly() {
        // P2#2: two completed cards with identical content get distinct occurrence-aware
        // ids; expanding the first must leave the second collapsed.
        composeRule.setContent {
            MaterialTheme {
                ChatMarkdownView(
                    text = "<think>Same reasoning</think>\n\n<think>Same reasoning</think>",
                    isStreaming = false,
                )
            }
        }

        val title = context.getString(R.string.chat_thinking_title)
        val expandDesc = context.getString(R.string.chat_thinking_expand)
        val collapseDesc = context.getString(R.string.chat_thinking_collapse)

        composeRule.onAllNodesWithText(title).assertCountEquals(2)
        composeRule.onAllNodesWithContentDescription(expandDesc).assertCountEquals(2)

        // Expand only the first card.
        composeRule.onAllNodesWithText(title)[0].performClick()
        composeRule.waitForIdle()

        // Exactly one card shows its content; the other stays collapsed.
        composeRule.onAllNodesWithText("Same reasoning").assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription(collapseDesc).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription(expandDesc).assertCountEquals(1)
        // The visible content belongs to the first card (above the second header).
        val contentY = composeRule.onNodeWithText("Same reasoning")
            .fetchSemanticsNode().positionInRoot.y
        val secondTitleY = composeRule.onAllNodesWithText(title)[1]
            .fetchSemanticsNode().positionInRoot.y
        assertTrue(contentY < secondTitleY)
    }

    @Test
    fun chatMarkdownView_sameContentThinkingCards_expandSecondOnly() {
        // Reverse direction: operating the second card must leave the first collapsed.
        composeRule.setContent {
            MaterialTheme {
                ChatMarkdownView(
                    text = "<think>Same reasoning</think>\n\n<think>Same reasoning</think>",
                    isStreaming = false,
                )
            }
        }

        val title = context.getString(R.string.chat_thinking_title)
        val expandDesc = context.getString(R.string.chat_thinking_expand)
        val collapseDesc = context.getString(R.string.chat_thinking_collapse)

        composeRule.onAllNodesWithText(title).assertCountEquals(2)

        // Expand only the second card.
        composeRule.onAllNodesWithText(title)[1].performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("Same reasoning").assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription(collapseDesc).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription(expandDesc).assertCountEquals(1)
        // The visible content belongs to the second card (below the first header).
        val contentY = composeRule.onNodeWithText("Same reasoning")
            .fetchSemanticsNode().positionInRoot.y
        val firstTitleY = composeRule.onAllNodesWithText(title)[0]
            .fetchSemanticsNode().positionInRoot.y
        assertTrue(contentY > firstTitleY)
    }

    @Test
    fun chatMarkdownView_prependDifferentThinking_doesNotMisapplyHoistedOverride() {
        // Prepending a different-content thinking card must not steal the old card's
        // override: old retains expand, new stays at default (collapsed, not streaming).
        var text by mutableStateOf("<think>Old reasoning content</think>\n\nAnswer")

        composeRule.setContent {
            MaterialTheme {
                ChatMarkdownView(text = text, isStreaming = false)
            }
        }

        val title = context.getString(R.string.chat_thinking_title)

        composeRule.onNodeWithText(title).performClick()
        composeRule.onNodeWithText("Old reasoning content").assertIsDisplayed()

        text = "<think>New reasoning content</think>\n\n<think>Old reasoning content</think>\n\nAnswer"
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Old reasoning content").assertIsDisplayed()
        composeRule.onNodeWithText("New reasoning content").assertDoesNotExist()
        composeRule.onAllNodesWithText(title).assertCountEquals(2)
    }

    @Test
    fun chatMarkdownView_splitCompletedPlusStreaming_retainsManualExpandOnFirst() {
        // Re-review #9 P2: ["Pl"] -> ["Plan", ""] in one append-only chunk (first card
        // completes, second empty streaming card starts). Manual expand on "Pl" must
        // survive on the first card; the second card stays at streaming default.
        var text by mutableStateOf("<think>Pl")

        composeRule.setContent {
            MaterialTheme {
                ChatMarkdownView(text = text, isStreaming = true)
            }
        }

        val title = context.getString(R.string.chat_thinking_title)
        val expandDesc = context.getString(R.string.chat_thinking_expand)
        val collapseDesc = context.getString(R.string.chat_thinking_collapse)

        // Streaming default is expanded; collapse then re-expand to record explicit true.
        composeRule.onNodeWithText(title).performClick()
        composeRule.onNodeWithContentDescription(expandDesc).assertIsDisplayed()
        composeRule.onNodeWithText(title).performClick()
        composeRule.onNodeWithContentDescription(collapseDesc).assertIsDisplayed()
        composeRule.onNodeWithText("Pl ▍").assertIsDisplayed()

        // One chunk completes the first card and opens an empty streaming card.
        text = "<think>Plan</think><think>"
        composeRule.waitForIdle()

        // First card retains the manual expand (completed default would collapse).
        composeRule.onNodeWithText("Plan").assertIsDisplayed()
        // Two thinking cards, both expanded (first via retained true, second via
        // streaming default).
        composeRule.onAllNodesWithText(title).assertCountEquals(2)
        composeRule.onAllNodesWithContentDescription(collapseDesc).assertCountEquals(2)
        // The retained content belongs to the first card (above the second header).
        val contentY = composeRule.onNodeWithText("Plan")
            .fetchSemanticsNode().positionInRoot.y
        val secondTitleY = composeRule.onAllNodesWithText(title)[1]
            .fetchSemanticsNode().positionInRoot.y
        assertTrue(contentY < secondTitleY)
    }

    @Test
    fun chatMarkdownView_splitCompletedPlusStreaming_retainsManualCollapseOnFirst() {
        // Same split as above but starting from a manual collapse: the first card must
        // stay collapsed and the empty streaming card must NOT inherit the collapse
        // (consumption: one previous entry feeds at most one current card).
        var text by mutableStateOf("<think>Pl")

        composeRule.setContent {
            MaterialTheme {
                ChatMarkdownView(text = text, isStreaming = true)
            }
        }

        val title = context.getString(R.string.chat_thinking_title)
        val expandDesc = context.getString(R.string.chat_thinking_expand)
        val collapseDesc = context.getString(R.string.chat_thinking_collapse)

        // Record explicit false on the single streaming card.
        composeRule.onNodeWithText(title).performClick()
        composeRule.onNodeWithContentDescription(expandDesc).assertIsDisplayed()
        composeRule.onNodeWithText("Pl ▍").assertDoesNotExist()

        text = "<think>Plan</think><think>"
        composeRule.waitForIdle()

        // First card stays collapsed; second card is at streaming default (expanded).
        composeRule.onAllNodesWithText(title).assertCountEquals(2)
        composeRule.onNodeWithText("Plan").assertDoesNotExist()
        composeRule.onAllNodesWithContentDescription(expandDesc).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription(collapseDesc).assertCountEquals(1)
        // Collapsed card is first (above), expanded streaming card is second (below).
        val expandY = composeRule.onNodeWithContentDescription(expandDesc)
            .fetchSemanticsNode().positionInRoot.y
        val collapseY = composeRule.onNodeWithContentDescription(collapseDesc)
            .fetchSemanticsNode().positionInRoot.y
        assertTrue(expandY < collapseY)
    }

    @Test
    fun chatMarkdownView_emptyCollapseThenSplit_collapseMovesToFirstAndSecondStaysExpanded() {
        // Blocking review bug: `[""]` -> `["X", ""]` in one append-only chunk. The
        // empty card's explicit `false` must move to `X` (step-3 prefix at the same
        // index); the new empty card reuses the old empty id, so with copy
        // semantics it would read the stale `false` and collapse instead of
        // defaulting to expanded while streaming.
        var text by mutableStateOf("<think>")

        composeRule.setContent {
            MaterialTheme {
                ChatMarkdownView(text = text, isStreaming = true)
            }
        }

        val title = context.getString(R.string.chat_thinking_title)
        val expandDesc = context.getString(R.string.chat_thinking_expand)
        val collapseDesc = context.getString(R.string.chat_thinking_collapse)

        // Empty streaming card defaults to expanded; record explicit false.
        composeRule.onNodeWithContentDescription(collapseDesc).assertIsDisplayed()
        composeRule.onNodeWithText(title).performClick()
        composeRule.onNodeWithContentDescription(expandDesc).assertIsDisplayed()

        // One chunk completes the first card and opens an empty streaming card.
        text = "<think>X</think><think>"
        composeRule.waitForIdle()

        // Two thinking cards rendered.
        composeRule.onAllNodesWithText(title).assertCountEquals(2)
        // First card retains the moved collapse: content hidden, exactly one
        // expand icon which sits above the second card.
        composeRule.onNodeWithText("X").assertDoesNotExist()
        composeRule.onAllNodesWithContentDescription(expandDesc).assertCountEquals(1)
        // Second (empty streaming) card defaults to expanded: exactly one
        // collapse icon, positioned below the collapsed first card.
        composeRule.onAllNodesWithContentDescription(collapseDesc).assertCountEquals(1)
        val expandY = composeRule.onNodeWithContentDescription(expandDesc)
            .fetchSemanticsNode().positionInRoot.y
        val collapseY = composeRule.onNodeWithContentDescription(collapseDesc)
            .fetchSemanticsNode().positionInRoot.y
        assertTrue(expandY < collapseY)
    }

    @Test
    fun chatMarkdownView_growthThenSplit_retainsExpandOnOldCard() {
        // Re-review #10 P2: `["Plan"]` -> `["Planning", "Plan"]` via non-append-only
        // raw (closed prev, curr does not start with prev). Identity-only must keep
        // the manual expand on the old second card; the new first card defaults to
        // collapsed. Old greedy prefix migration would steal it onto the first card.
        var text by mutableStateOf("<think>Plan</think>")

        composeRule.setContent {
            MaterialTheme {
                ChatMarkdownView(text = text, isStreaming = false)
            }
        }

        val title = context.getString(R.string.chat_thinking_title)
        val expandDesc = context.getString(R.string.chat_thinking_expand)
        val collapseDesc = context.getString(R.string.chat_thinking_collapse)

        // Single completed card defaults to collapsed; record explicit true.
        composeRule.onNodeWithText(title).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Plan").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(collapseDesc).assertIsDisplayed()

        // Non-append-only rewrite: first card is new ("Planning"), second is old ("Plan").
        text = "<think>Planning</think>\n\n<think>Plan</think>"
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText(title).assertCountEquals(2)
        // Old card (second) retains expand; new card (first) defaults to collapsed.
        composeRule.onNodeWithText("Plan").assertIsDisplayed()
        composeRule.onNodeWithText("Planning").assertDoesNotExist()
        composeRule.onAllNodesWithContentDescription(expandDesc).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription(collapseDesc).assertCountEquals(1)
        // Collapsed first card sits above the expanded second card.
        val expandY = composeRule.onNodeWithContentDescription(expandDesc)
            .fetchSemanticsNode().positionInRoot.y
        val collapseY = composeRule.onNodeWithContentDescription(collapseDesc)
            .fetchSemanticsNode().positionInRoot.y
        assertTrue(expandY < collapseY)
        // The visible content belongs to the second card (below the first header).
        val contentY = composeRule.onNodeWithText("Plan")
            .fetchSemanticsNode().positionInRoot.y
        val firstTitleY = composeRule.onAllNodesWithText(title)[0]
            .fetchSemanticsNode().positionInRoot.y
        assertTrue(contentY > firstTitleY)
    }

    @Test
    fun chatMarkdownView_growthThenSplit_retainsCollapseOnOldCard() {
        // Companion to the expand case: old card holds explicit `false`; the new
        // first card (completed) defaults to collapsed and the old second card
        // (streaming) must retain the collapse instead of defaulting to expanded.
        // Greedy prefix migration would move the collapse onto the first card and
        // leave the second at its streaming default (visible `Plan` cursor).
        var text by mutableStateOf("<think>Plan</think>")

        composeRule.setContent {
            MaterialTheme {
                ChatMarkdownView(text = text, isStreaming = true)
            }
        }

        val title = context.getString(R.string.chat_thinking_title)
        val expandDesc = context.getString(R.string.chat_thinking_expand)
        val collapseDesc = context.getString(R.string.chat_thinking_collapse)

        // Completed card in a streaming turn defaults to collapsed; record explicit
        // false via expand-then-collapse so the map holds `false` (not null).
        composeRule.onNodeWithText(title).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Plan").assertIsDisplayed()
        composeRule.onNodeWithText(title).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(expandDesc).assertIsDisplayed()
        composeRule.onNodeWithText("Plan").assertDoesNotExist()

        // Non-append-only: curr does not start with prev (`</think>` vs `ning...`).
        text = "<think>Planning</think><think>Plan"
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText(title).assertCountEquals(2)
        // Both cards collapsed: first via completed default, second via retained false
        // (streaming default would be expanded).
        composeRule.onNodeWithText("Planning").assertDoesNotExist()
        composeRule.onNodeWithText("Plan").assertDoesNotExist()
        composeRule.onNodeWithText("Plan \u258D").assertDoesNotExist()
        composeRule.onAllNodesWithContentDescription(expandDesc).assertCountEquals(2)
        composeRule.onAllNodesWithContentDescription(collapseDesc).assertCountEquals(0)
    }

    @Test
    fun chatMarkdownView_manualExpand_survivesRecreationWithoutRaw() {
        // Phase A: raw text and previous thinking contents live only in plain remember,
        // so recreation drops the anchors but must keep the user's manual expand choice
        // via the saveable overrides map (deterministic ids re-attach by exact-id read).
        val text = "<think>Reasoning that the user manually expanded</think>\n\nFinal answer."
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            MaterialTheme {
                ChatMarkdownView(text = text, isStreaming = false)
            }
        }

        val thinkingTitle = context.getString(R.string.chat_thinking_title)
        val collapseDesc = context.getString(R.string.chat_thinking_collapse)
        val expandDesc = context.getString(R.string.chat_thinking_expand)

        // Completed thinking block collapses by default.
        composeRule.onNodeWithContentDescription(expandDesc).assertIsDisplayed()
        composeRule.onNodeWithText(thinkingTitle).performClick()
        composeRule.onNodeWithContentDescription(collapseDesc).assertIsDisplayed()
        composeRule.onNodeWithText("Reasoning that the user manually expanded").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithContentDescription(collapseDesc).assertIsDisplayed()
        composeRule.onNodeWithText("Reasoning that the user manually expanded").assertIsDisplayed()
        composeRule.onNodeWithText("Final answer.").assertIsDisplayed()
    }

    @Test
    fun chatMarkdownView_longMessageWithoutThinking_restoresWithoutRawSaveable() {
        // Phase A structural pin: a 64 KiB+ message with no thinking blocks must survive
        // recreation even though no raw-related saveable exists (raw lives only in plain
        // remember; the only saveable is the empty overrides map).
        val body = "Lorem ipsum dolor sit amet. ".repeat(2400)
        assertTrue(body.toByteArray(Charsets.UTF_8).size > 64 * 1024)
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            MaterialTheme {
                ChatMarkdownView(text = body, isStreaming = false)
            }
        }

        composeRule.onNodeWithText("Lorem ipsum", substring = true).assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Lorem ipsum", substring = true).assertIsDisplayed()
    }

    @Test
    fun thinkingExpandedOverridesSaver_roundTripAndSizeBound() {
        // The only saveable in ChatMarkdownView: 64 full entries must restore exactly
        // and stay far below 50 KiB serialized (fixed-size "<16 hex>.<rank>" keys).
        val original = mutableStateMapOf<String, Boolean>()
        repeat(64) { i ->
            original[thinkingOverrideId("reasoning content $i", 0)] = i % 2 == 0
        }
        val saverScope = object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        val saved = with(ThinkingExpandedOverridesSaver) { saverScope.save(original) }
        assertNotNull(saved)
        val payload = saved!!
        val bytes = payload.sumOf { (key, _) -> key.toByteArray(Charsets.UTF_8).size + 1 }
        assertTrue("overrides payload $bytes bytes exceeds bound", bytes < 50 * 1024)
        val restored = with(ThinkingExpandedOverridesSaver) { restore(payload) }
        assertNotNull(restored)
        assertEquals(original.toMap(), restored!!.toMap())
    }

    @Test
    fun thinkingExpandedOverridesSaver_truncatesBeyondCap() {
        val original = mutableStateMapOf<String, Boolean>()
        repeat(ThinkingExpandedOverridesMaxEntries + 50) { i ->
            original["abcdef0123456789.$i"] = true
        }
        val saverScope = object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        val saved = with(ThinkingExpandedOverridesSaver) { saverScope.save(original) }
        assertNotNull(saved)
        assertTrue(
            "saved ${saved!!.size} entries exceeds cap $ThinkingExpandedOverridesMaxEntries",
            saved.size <= ThinkingExpandedOverridesMaxEntries,
        )
    }

    @Test
    fun putThinkingOverrideBounded_rejectsNewBeyondCap() {
        val map = mutableStateMapOf<String, Boolean>()
        repeat(ThinkingExpandedOverridesMaxEntries) { i ->
            map["abcdef0123456789.$i"] = true
        }
        // New id at cap: rejected, map unchanged (fail-closed to block.isStreaming).
        assertFalse(putThinkingOverrideBounded(map, "abcdef0123456789.new", true))
        assertFalse(map.containsKey("abcdef0123456789.new"))
        assertEquals(ThinkingExpandedOverridesMaxEntries, map.size)
        // Updating an existing id always succeeds, even at cap.
        assertTrue(putThinkingOverrideBounded(map, "abcdef0123456789.0", false))
        assertEquals(false, map["abcdef0123456789.0"])
    }

    @Test
    fun thinkingAlignIdentityOnly_rewriteAllDifferent_scalesLinearly() {
        // P3: identity-only content-exact uses an O(1) content -> indices lookup built
        // once per recomposition (O(K)). Rewriting K cards to K fully different cards
        // must scale ~linearly (well under the quadratic 4x plus noise headroom),
        // unlike a per-card linear scan over all previous entries.
        //
        // Timing is environment-sensitive (CI jitter/GC on short runs can inflate the
        // ratio). Use interleaved rounds with per-side medians on a larger base so the
        // noise share is small while keeping the 3.5 bound strictly inside (2, 4) to
        // discriminate linear (~2) from quadratic (~4) behavior.
        fun timedOnce(k: Int): Long {
            val prev = List(k) { "prev-content-$it" }
            val curr = List(k) { "curr-content-$it" }
            val prevIds = thinkingOverrideIds(prev)
            val currIds = thinkingOverrideIds(curr)
            val currSet = currIds.toSet()
            repeat(5) {
                alignThinkingOverridesIdentityOnly(prev, prevIds, currIds, curr, mutableMapOf(), currSet)
            }
            val start = System.nanoTime()
            repeat(10) {
                alignThinkingOverridesIdentityOnly(prev, prevIds, currIds, curr, mutableMapOf(), currSet)
            }
            return System.nanoTime() - start
        }
        val samples1 = LongArray(7)
        val samples2 = LongArray(7)
        repeat(7) { i ->
            samples1[i] = timedOnce(2000)
            samples2[i] = timedOnce(4000)
        }
        val t1 = samples1.sorted()[samples1.size / 2]
        val t2 = samples2.sorted()[samples2.size / 2]
        val ratio = t2.toDouble() / maxOf(1L, t1).toDouble()
        assertTrue(
            "identity-only 2x-input ratio $ratio exceeds linear bound (t1=${t1}ns t2=${t2}ns)",
            ratio < 3.5,
        )
        // Correctness pin: all-different rewrite migrates nothing and prunes stale.
        val prev = listOf("A", "B")
        val curr = listOf("C", "D")
        val prevIds = thinkingOverrideIds(prev)
        val currIds = thinkingOverrideIds(curr)
        val overrides = mutableMapOf(prevIds[0] to true)
        alignThinkingOverridesIdentityOnly(prev, prevIds, currIds, curr, overrides, currIds.toSet())
        assertFalse(overrides.containsKey(prevIds[0]))
        assertTrue(overrides.isEmpty())
    }
}
