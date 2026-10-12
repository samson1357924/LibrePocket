package dev.librepocket.agent.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointGate
import dev.librepocket.agent.ui.setup.setupErrorText
import dev.librepocket.provider.ProviderProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 階段2：logout 失敗 Settings UI 可見（Re-review #7 Finding 2）。
 *
 * Robolectric + compose rule（unit source set）：失敗顯示 + 重試可達、
 * inFlight disabled + spinner、取消零調用、失敗不關 dialog（保持/重現）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun readyGate(): EndpointGate.Ready = EndpointGate.Ready(
        EndpointConfig(
            providerId = "preset:openai",
            presetId = "openai",
            label = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            protocol = ProviderProtocol.CHAT_COMPLETIONS,
            model = "gpt-4o-mini",
            apiKeyRef = "provider_key/preset:openai",
        ),
    )

    /**
     * Robolectric 活動視口小，Settings 整頁（產品不滾動）底部登出區會落在可視
     * 區外：觸控點不到、assertIsDisplayed 失敗。測試 harness 在外包一層可滾動
     * 容器（產品佈局不動），用 [performScrollTo] 把目標滾入可視區再互動/斷言。
     */
    private fun scrolledContent(content: @Composable () -> Unit) {
        composeRule.setContent {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                content()
            }
        }
    }

    private fun onLogoutArea(text: String) =
        composeRule.onNodeWithText(text).performScrollTo()

    @Test
    fun logoutFailureKeepsDialogAndRetryReachesLogout() {
        var error: String? by mutableStateOf(null)
        var logoutCalls = 0
        scrolledContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                onLogout = {
                    logoutCalls++
                    // 模擬 VM 失敗：保留 errorCode、上報 onDone(false)。
                    error = "LOGOUT_CLEAR_FAILED"
                },
                logoutErrorCode = error,
            )
        }
        onLogoutArea("登出（清除金鑰）").performClick()
        onLogoutArea("確定清除").performClick()
        assertEquals(1, logoutCalls)
        // 失敗不關 dialog：失敗卡 + 重試鍵仍在。
        onLogoutArea(setupErrorText("LOGOUT_CLEAR_FAILED")).assertIsDisplayed()
        onLogoutArea("重試清除").assertIsDisplayed()
        // 重試可達：再調一次 logout。
        composeRule.onNodeWithText("重試清除").performClick()
        assertEquals(2, logoutCalls)
        onLogoutArea(setupErrorText("LOGOUT_CLEAR_FAILED")).assertIsDisplayed()
    }

    @Test
    fun logoutErrorAloneReopensConfirm() {
        // 失敗碼存續即重現確認態（內部 confirm 旗標為 false 的 fresh 組合也一樣）。
        scrolledContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                logoutErrorCode = "LOGOUT_STALE",
            )
        }
        composeRule.onAllNodesWithText("登出（清除金鑰）").assertCountEquals(0)
        onLogoutArea(setupErrorText("LOGOUT_STALE")).assertIsDisplayed()
        onLogoutArea("重試清除").assertIsDisplayed()
        onLogoutArea("取消").assertIsDisplayed()
    }

    @Test
    fun logoutInFlightDisablesAllButtonsAndShowsSpinner() {
        scrolledContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                logoutErrorCode = "LOGOUT_CLEAR_FAILED",
                logoutInFlight = true,
            )
        }
        onLogoutArea("清除中…").assertIsDisplayed()
        // 確認/重試鍵與取消鍵皆 disabled，防重點。
        composeRule.onNodeWithText("清除中…").assertIsNotEnabled()
        onLogoutArea("取消").assertIsNotEnabled()
        onLogoutArea(setupErrorText("LOGOUT_CLEAR_FAILED")).assertIsDisplayed()
    }

    @Test
    fun cancelNeverCallsLogout() {
        var logoutCalls = 0
        var dismissed = 0
        scrolledContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                onLogout = { logoutCalls++ },
                onDismissLogoutError = { dismissed++ },
            )
        }
        onLogoutArea("登出（清除金鑰）").performClick()
        onLogoutArea("取消").performClick()
        // 取消語義：零調用，只做 dismiss。
        assertEquals(0, logoutCalls)
        assertEquals(1, dismissed)
        // dialog 關閉：回到初始鍵，確認鍵消失。
        onLogoutArea("登出（清除金鑰）").assertIsDisplayed()
        composeRule.onAllNodesWithText("確定清除").assertCountEquals(0)
        composeRule.onAllNodesWithText("取消").assertCountEquals(0)
    }

    @Test
    fun logoutErrorTextMapsFourCodes() {
        // READ 未動 / DELETE 未清 / CLEAR 部分 / STALE 新 key 生效。
        assertTrue(setupErrorText("LOGOUT_READ_FAILED").contains("未做任何變更"))
        assertTrue(setupErrorText("LOGOUT_DELETE_FAILED").contains("未清除"))
        assertTrue(setupErrorText("LOGOUT_CLEAR_FAILED").contains("部分清除"))
        assertTrue(setupErrorText("LOGOUT_CLEAR_FAILED").contains("重試"))
        assertTrue(setupErrorText("LOGOUT_STALE").contains("新的金鑰"))
    }
}
