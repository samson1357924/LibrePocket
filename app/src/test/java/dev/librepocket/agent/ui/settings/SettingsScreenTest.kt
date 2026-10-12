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
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointGate
import dev.librepocket.agent.ui.setup.setupErrorText
import dev.librepocket.automation.AutomationSettingsState
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
 * 階段3：產品容器可滾（Re-review #8 Finding 3）——直寫 setContent，
 * [performScrollTo] 證明產品 [SettingsScreen] root Column 本身可滾。
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
     * 階段3：直寫產品 [SettingsScreen]（外包可滾容器已移除）。
     * Robolectric 活動視口小，底部登出區落在可視區外時，
     * [performScrollTo] 必須經產品 root Column 的 verticalScroll 滾入；
     * 產品未加 scroll 前此 helper 即紅（無可滾容器），加後轉綠。
     */
    private fun onLogoutArea(text: String) =
        composeRule.onNodeWithText(text).performScrollTo()

    @Test
    fun logoutFailureKeepsDialogAndRetryReachesLogout() {
        var error: String? by mutableStateOf(null)
        var logoutCalls = 0
        composeRule.setContent {
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
        onLogoutArea("重試清除").performClick()
        assertEquals(2, logoutCalls)
        onLogoutArea(setupErrorText("LOGOUT_CLEAR_FAILED")).assertIsDisplayed()
    }

    @Test
    fun logoutErrorAloneReopensConfirm() {
        // 失敗碼存續即重現確認態（內部 confirm 旗標為 false 的 fresh 組合也一樣）。
        composeRule.setContent {
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
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                logoutErrorCode = "LOGOUT_CLEAR_FAILED",
                logoutInFlight = true,
            )
        }
        onLogoutArea("清除中…").assertIsDisplayed()
        // 確認/重試鍵與取消鍵皆 disabled，防重點。
        onLogoutArea("清除中…").assertIsNotEnabled()
        onLogoutArea("取消").assertIsNotEnabled()
        onLogoutArea(setupErrorText("LOGOUT_CLEAR_FAILED")).assertIsDisplayed()
    }

    @Test
    fun cancelNeverCallsLogout() {
        var logoutCalls = 0
        var dismissed = 0
        composeRule.setContent {
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

    /**
     * 階段3 最高棧鎖定（Re-review #8 Finding 3）：
     * Ready + automation 非 null + CLEAR_FAILED 為全頁最高組合
     *（端點卡/語音卡/提權卡/自動化卡/失敗卡全在），直寫產品容器，
     * 重試/取消/無障礙設定三入口皆須可滾入可視區且可點。
     */
    @Test
    fun tallestStackAllActionsReachable() {
        var retryCalls = 0
        var dismissed = 0
        var a11yClicks = 0
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                automation = AutomationSettingsState(
                    switchOn = false,
                    serviceGranted = false,
                    effective = false,
                ),
                logoutErrorCode = "LOGOUT_CLEAR_FAILED",
                onRetryLogout = { retryCalls++ },
                onDismissLogoutError = { dismissed++ },
                onOpenSystemA11y = { a11yClicks++ },
            )
        }
        // 三入口皆 displayed（經產品容器 scrollTo 滾入）。
        onLogoutArea("前往系統無障礙設定").assertIsDisplayed()
        onLogoutArea("重試清除").assertIsDisplayed()
        onLogoutArea("取消").assertIsDisplayed()
        // 三入口皆可點。
        onLogoutArea("前往系統無障礙設定").performClick()
        onLogoutArea("重試清除").performClick()
        onLogoutArea("取消").performClick()
        assertEquals(1, a11yClicks)
        assertEquals(1, retryCalls)
        assertEquals(1, dismissed)
    }

    /**
     * 最高棧大字體變體：fontScale=1.3 下內容更高，仍須全可達。
     * 方法級 @Config 覆寫字體比例（sdk 沿用類級 33）。
     */
    @Test
    @Config(sdk = [33], fontScale = 1.3f)
    fun tallestStackLargeFontStillReachable() {
        var retryCalls = 0
        var dismissed = 0
        var a11yClicks = 0
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                automation = AutomationSettingsState(
                    switchOn = false,
                    serviceGranted = false,
                    effective = false,
                ),
                logoutErrorCode = "LOGOUT_CLEAR_FAILED",
                onRetryLogout = { retryCalls++ },
                onDismissLogoutError = { dismissed++ },
                onOpenSystemA11y = { a11yClicks++ },
            )
        }
        onLogoutArea("前往系統無障礙設定").assertIsDisplayed()
        onLogoutArea("重試清除").assertIsDisplayed()
        onLogoutArea("取消").assertIsDisplayed()
        onLogoutArea("前往系統無障礙設定").performClick()
        onLogoutArea("重試清除").performClick()
        onLogoutArea("取消").performClick()
        assertEquals(1, a11yClicks)
        assertEquals(1, retryCalls)
        assertEquals(1, dismissed)
    }
}
