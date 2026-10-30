package dev.librepocket.agent.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import dev.librepocket.session.TranscriptEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TranscriptViewerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun truncationAndLegacyUncertaintyAreVisible() {
        compose.setContent {
            TranscriptViewer("history", { id, _ ->
                listOf(
                    TranscriptEvent(seq = 1, sessionId = id, runId = "r", kind = "user", text = "cut", createdAt = 0, isTruncated = true),
                    TranscriptEvent(seq = 2, sessionId = id, runId = "r", kind = "assistant", text = "unknown", createdAt = 0),
                )
            }, {})
        }
        compose.onNodeWithText("舊備份可能未記錄截斷狀態；未標示截斷不代表內容完整。").assertIsDisplayed()
        compose.onNodeWithText("此事件內容已截斷。").assertIsDisplayed()
        compose.onAllNodesWithText("此事件內容已截斷。").assertCountEquals(1)
        val warningTop = compose.onNodeWithText("此事件內容已截斷。").fetchSemanticsNode().boundsInRoot.top
        val contentTop = compose.onNodeWithText("user: cut").fetchSemanticsNode().boundsInRoot.top
        assertTrue(warningTop < contentTop)
        compose.onNodeWithText("assistant: unknown").assertIsDisplayed()
    }

    @Test fun truncationWarningIsVisibleBeforeLongContent() {
        compose.setContent {
            TranscriptViewer("history", { id, _ ->
                listOf(TranscriptEvent(seq = 1, sessionId = id, runId = "r", kind = "user",
                    text = "long content ".repeat(1000), createdAt = 0, isTruncated = true))
            }, {})
        }
        compose.onNodeWithText("此事件內容已截斷。").assertIsDisplayed()
    }

    @Test fun pagesAreReadOnlyAndUseExclusiveSequenceCursor() {
        val calls = mutableListOf<Pair<String, Long>>()
        compose.setContent {
            TranscriptViewer("imported", { id, cursor ->
                calls.add(id to cursor)
                if (cursor == 0L) (1L..100L).map {
                    TranscriptEvent(seq = it, sessionId = id, runId = "r", kind = "user", text = "row $it", createdAt = 0)
                } else listOf(TranscriptEvent(seq = Long.MAX_VALUE, sessionId = id, runId = "r", kind = "assistant", text = "last", createdAt = 0))
            }, {})
        }
        compose.onNodeWithText("user: row 1").assertIsDisplayed()
        compose.onNodeWithTag("transcriptPages").performScrollToNode(hasText("下一頁"))
        compose.onNodeWithText("下一頁").performClick()
        compose.onNodeWithText("assistant: last").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("imported" to 0L, "imported" to 100L), calls) }
        compose.onNodeWithText("上一頁").performClick()
        compose.onNodeWithText("user: row 1").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("imported" to 0L, "imported" to 100L, "imported" to 0L), calls) }
    }

    @Test fun legacyTextAndFailureReasonAreRedactedForDisplay() {
        compose.setContent {
            TranscriptViewer("legacy", { id, _ ->
                listOf(TranscriptEvent(seq = 1, sessionId = id, runId = "r", kind = "assistant",
                    text = "email person@example.com key sk-abcdefgh1234", createdAt = 0,
                    failureReason = "token sk-zyxwvuts1234 email other@example.com"))
            }, {})
        }
        compose.onNodeWithText("assistant: email ⟦REDACTED:EMAIL⟧ key ⟦REDACTED:API_KEY⟧").assertIsDisplayed()
        compose.onNodeWithText("失敗原因：token ⟦REDACTED:API_KEY⟧ email ⟦REDACTED:EMAIL⟧").assertIsDisplayed()
        compose.onNodeWithText("person@example.com", substring = true).assertDoesNotExist()
        compose.onNodeWithText("sk-zyxwvuts1234", substring = true).assertDoesNotExist()
    }

    @Test fun previousPageRemainsAvailableAfterReadFailure() {
        compose.setContent {
            TranscriptViewer("history", { id, cursor ->
                if (cursor != 0L) error("failure")
                (1L..100L).map {
                    TranscriptEvent(seq = it, sessionId = id, runId = "r", kind = "user", text = "row $it", createdAt = 0)
                }
            }, {})
        }
        compose.onNodeWithTag("transcriptPages").performScrollToNode(hasText("下一頁"))
        compose.onNodeWithText("下一頁").performClick()
        compose.onNodeWithText("無法讀取對話，請關閉後重試。").assertIsDisplayed()
        compose.onNodeWithText("上一頁").performClick()
        compose.onNodeWithText("user: row 1").assertIsDisplayed()
    }

    @Test fun readFailureIsVisible() {
        compose.setContent { TranscriptViewer("missing", { _, _ -> error("failure") }, {}) }
        compose.onNodeWithText("無法讀取對話，請關閉後重試。").assertIsDisplayed()
    }
}
