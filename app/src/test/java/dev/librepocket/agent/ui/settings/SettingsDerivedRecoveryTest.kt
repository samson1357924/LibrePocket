package dev.librepocket.agent.ui.settings

import androidx.compose.foundation.layout.PaddingValues
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
 * Stage D2（R12 Finding 2 (b)）Settings 派生卡：NoEndpoint＋派生 `LOGOUT_UNKNOWN`
 * （跨 VM journal，`MainScreen` 經 `effectiveLogoutGuardError` 傳入）顯示 UNKNOWN
 * 卡＋重試清除、無 dismiss（STALE 外語義不動）；在途 disabled＋spinner。
 *
 * Safe reconcile 入口複用既有 `onLogout`（`onRetryLogout` 預設）；文案禁寫
 * 「已刪除」（未確認路徑）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsDerivedRecoveryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun onArea(text: String) =
        composeRule.onNodeWithText(text).performScrollTo()

    @Test
    fun noEndpointDerivedUnknownShowsRecoveryRetryAndDismiss() {
        // Stage R5：無身份（null journal）UNKNOWN 卡：重試＋關閉皆可達；
        // 有身份（journal）時僅重試（見 SettingsNoEndpointRecoveryTest 有身份用例）。
        var logoutCalls = 0
        var dismissed = 0
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = EndpointGate.NoEndpoint,
                onLogout = { logoutCalls++ },
                logoutErrorCode = "LOGOUT_UNKNOWN",
                onDismissLogoutError = { dismissed++ },
            )
        }
        // UNKNOWN 卡文案可見（禁寫已刪除），重試＋關閉可達；無取消出口.
        onArea(setupErrorText("LOGOUT_UNKNOWN")).assertIsDisplayed()
        onArea("重試清除").assertIsDisplayed()
        onArea("關閉").assertIsDisplayed()
        composeRule.onAllNodesWithText("取消").assertCountEquals(0)
        composeRule.onAllNodesWithText("尚未設定 API 端點。").assertCountEquals(0)
        composeRule.onAllNodesWithText("前往設定").assertCountEquals(0)
        onArea("重試清除").performClick()
        assertEquals(1, logoutCalls)
        assertEquals(0, dismissed)
        onArea("關閉").performClick()
        assertEquals(1, dismissed)
        // 文案斷言：殘留待清理（已觀測存在），禁寫已刪除（未確認路徑）.
        val text = setupErrorText("LOGOUT_UNKNOWN")
        assertEquals(false, text.contains("已刪除"))
    }

    @Test
    fun noEndpointDerivedUnknownInFlightDisablesAndShowsSpinner() {
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = EndpointGate.NoEndpoint,
                logoutErrorCode = "LOGOUT_UNKNOWN",
                logoutInFlight = true,
            )
        }
        onArea(setupErrorText("LOGOUT_UNKNOWN")).assertIsDisplayed()
        onArea("清除中…").assertIsDisplayed()
        onArea("清除中…").assertIsNotEnabled()
        // Stage R5：無身份時關閉可見但同樣 disabled（在途）。
        onArea("關閉").assertIsDisplayed()
        onArea("關閉").assertIsNotEnabled()
        composeRule.onAllNodesWithText("取消").assertCountEquals(0)
    }
}
