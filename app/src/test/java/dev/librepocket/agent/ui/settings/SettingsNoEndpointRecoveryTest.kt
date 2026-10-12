package dev.librepocket.agent.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import dev.librepocket.agent.ui.setup.EndpointGate
import dev.librepocket.agent.ui.setup.setupErrorText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 階段2：NoEndpoint 恢復入口（Re-review #8 Finding 2 P2/BLOCK）。
 * 階段3：產品容器可滾（Re-review #8 Finding 3）——直寫 setContent，
 * [performScrollTo] 證明產品 [SettingsScreen] root Column 本身可滾
 *（外包可滾容器已移除；與產品 scroll 嵌套會觸發無限約束崩潰，故必須直寫）。
 * Stage A（R10 Finding 1 P2/BLOCK 方案 B 立即止血）：
 * retained 碼（READ/DELETE/CLEAR/UNKNOWN，journal 可能保留）只留重試、無取消；
 * 僅 STALE（journal 已丟棄）留關閉。
 *
 * - `LOGOUT_CLEAR_FAILED`（部分清除，gate 已折疊 NoEndpoint）顯示恢復卡 +
 *   重試可達，且不再顯示「尚未設定」文案（readyGate 反例）；
 * - `LOGOUT_STALE`（已有新金鑰生效）無重試、僅關閉；
 * - 無碼時保留原尚未設定文案（fresh 不變）；
 * - inFlight 時可見按鍵 disabled + spinner（retained 無取消鍵）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsNoEndpointRecoveryTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * 階段3：直寫產品 [SettingsScreen]（外包可滾容器已移除，見類 KDoc）。
     */
    private fun onArea(text: String) =
        composeRule.onNodeWithText(text).performScrollTo()

    @Test
    fun noEndpointClearFailedShowsRecoveryAndRetryReachesLogout() {
        var logoutCalls = 0
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = EndpointGate.NoEndpoint,
                onLogout = { logoutCalls++ },
                logoutErrorCode = "LOGOUT_CLEAR_FAILED",
            )
        }
        // 恢復卡文案可見，重試可達；Stage A：retained 碼無取消/關閉出口。
        onArea(setupErrorText("LOGOUT_CLEAR_FAILED")).assertIsDisplayed()
        onArea("重試清除").assertIsDisplayed()
        composeRule.onAllNodesWithText("取消").assertCountEquals(0)
        composeRule.onAllNodesWithText("關閉").assertCountEquals(0)
        // readyGate 反例：恢復態不顯示「尚未設定」文案。
        composeRule.onAllNodesWithText("尚未設定 API 端點。").assertCountEquals(0)
        composeRule.onAllNodesWithText("前往設定").assertCountEquals(0)
        onArea("重試清除").performClick()
        assertEquals(1, logoutCalls)
    }

    @Test
    fun noEndpointStaleHasNoRetryOnlyDismiss() {
        var logoutCalls = 0
        var dismissed = 0
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = EndpointGate.NoEndpoint,
                onLogout = { logoutCalls++ },
                logoutErrorCode = "LOGOUT_STALE",
                onDismissLogoutError = { dismissed++ },
            )
        }
        // 新的金鑰文案可見，無重試，僅關閉。
        onArea(setupErrorText("LOGOUT_STALE")).assertIsDisplayed()
        composeRule.onAllNodesWithText("重試清除").assertCountEquals(0)
        composeRule.onAllNodesWithText("確定清除").assertCountEquals(0)
        onArea("關閉").assertIsDisplayed()
        onArea("關閉").performClick()
        assertEquals(1, dismissed)
        assertEquals(0, logoutCalls)
    }

    @Test
    fun noEndpointFreshUnchanged() {
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = EndpointGate.NoEndpoint,
            )
        }
        // 無碼時保留原尚未設定文案 + 前往設定，無恢復鍵。
        onArea("尚未設定 API 端點。").assertIsDisplayed()
        onArea("前往設定").assertIsDisplayed()
        composeRule.onAllNodesWithText("重試清除").assertCountEquals(0)
        composeRule.onAllNodesWithText("取消").assertCountEquals(0)
        composeRule.onAllNodesWithText("關閉").assertCountEquals(0)
    }

    @Test
    fun noEndpointInFlightDisablesAndShowsSpinner() {
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = EndpointGate.NoEndpoint,
                logoutErrorCode = "LOGOUT_CLEAR_FAILED",
                logoutInFlight = true,
            )
        }
        onArea(setupErrorText("LOGOUT_CLEAR_FAILED")).assertIsDisplayed()
        onArea("清除中…").assertIsDisplayed()
        onArea("清除中…").assertIsNotEnabled()
        // Stage A：retained 碼無取消/關閉鍵，僅重試（執行中 disabled + spinner）。
        composeRule.onAllNodesWithText("取消").assertCountEquals(0)
        composeRule.onAllNodesWithText("關閉").assertCountEquals(0)
    }

    @Test
    fun noEndpointRetryableCodesAllShowRetry() {
        // READ/DELETE 失敗同為可重試碼（STALE 除外）：同一 content 內切碼驗證，
        // 避免同一 test 內兩次 setContent（compose rule 單測只允許一次）。
        // Stage A：兩碼皆無取消出口。
        var code by mutableStateOf("LOGOUT_READ_FAILED")
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = EndpointGate.NoEndpoint,
                logoutErrorCode = code,
            )
        }
        onArea(setupErrorText("LOGOUT_READ_FAILED")).assertIsDisplayed()
        onArea("重試清除").assertIsDisplayed()
        composeRule.onAllNodesWithText("取消").assertCountEquals(0)
        composeRule.onAllNodesWithText("關閉").assertCountEquals(0)
        code = "LOGOUT_DELETE_FAILED"
        composeRule.waitForIdle()
        onArea(setupErrorText("LOGOUT_DELETE_FAILED")).assertIsDisplayed()
        onArea("重試清除").assertIsDisplayed()
        composeRule.onAllNodesWithText("取消").assertCountEquals(0)
        composeRule.onAllNodesWithText("關閉").assertCountEquals(0)
    }
}
