package dev.librepocket.agent.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import dev.librepocket.agent.ui.setup.EndpointGate
import dev.librepocket.agent.ui.setup.setupErrorText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 3 P2 啟動失敗恢復可達（決策 (a) 的 Settings 側）：
 *
 * - `NoEndpoint` + `LOGOUT_DELETE_FAILED`（delete 未證勝）→ 恢復卡 + 重試可達；
 * - `NoEndpoint` + `LOGOUT_UNKNOWN`（中性碼）→ 恢復卡 + 重試可達，且文案不含
 *   「已刪除」（與 `LOGOUT_CLEAR_FAILED` 的「已刪除」斷言嚴格區分）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StartupFailRecoveryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun onArea(text: String) =
        composeRule.onNodeWithText(text).performScrollTo()

    @Test
    fun noEndpointDeleteFailedShowsRecoveryAndRetryReachesLogout() {
        val deleteText = setupErrorText("LOGOUT_DELETE_FAILED")
        assertFalse("delete-failed wording must not claim deleted: $deleteText", deleteText.contains("已刪除"))
        var logoutCalls = 0
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = EndpointGate.NoEndpoint,
                onLogout = { logoutCalls++ },
                logoutErrorCode = "LOGOUT_DELETE_FAILED",
            )
        }
        onArea(setupErrorText("LOGOUT_DELETE_FAILED")).assertIsDisplayed()
        onArea("重試清除").assertIsDisplayed()
        onArea("取消").assertIsDisplayed()
        composeRule.onAllNodesWithText("尚未設定 API 端點。").assertCountEquals(0)
        onArea("重試清除").performClick()
        assertEquals(1, logoutCalls)
    }

    @Test
    fun noEndpointUnknownShowsNeutralRecoveryAndRetryReachesLogout() {
        var logoutCalls = 0
        val text = setupErrorText("LOGOUT_UNKNOWN")
        assertFalse("neutral wording must not claim deleted: $text", text.contains("已刪除"))
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = EndpointGate.NoEndpoint,
                onLogout = { logoutCalls++ },
                logoutErrorCode = "LOGOUT_UNKNOWN",
            )
        }
        onArea(text).assertIsDisplayed()
        onArea("重試清除").assertIsDisplayed()
        onArea("取消").assertIsDisplayed()
        composeRule.onAllNodesWithText("尚未設定 API 端點。").assertCountEquals(0)
        onArea("重試清除").performClick()
        assertEquals(1, logoutCalls)
    }
}
