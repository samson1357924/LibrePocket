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
 * Stage A（R10 Finding 1 P2/BLOCK 方案 B）：Ready 分支同理——retained 碼
 * 只留重試、無取消；僅 STALE 留關閉；無碼初始確認才有取消。
 *
 * Robolectric + compose rule（unit source set）：失敗顯示 + 重試可達、
 * inFlight disabled + spinner、初始確認取消零調用、失敗不關 dialog（保持/重現）。
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
        // 失敗不關 dialog：失敗卡 + 重試鍵仍在；Stage A：retained 碼無取消/關閉出口。
        onLogoutArea(setupErrorText("LOGOUT_CLEAR_FAILED")).assertIsDisplayed()
        onLogoutArea("重試清除").assertIsDisplayed()
        composeRule.onAllNodesWithText("取消").assertCountEquals(0)
        composeRule.onAllNodesWithText("關閉").assertCountEquals(0)
        // 重試可達：再調一次 logout。
        onLogoutArea("重試清除").performClick()
        assertEquals(2, logoutCalls)
        onLogoutArea(setupErrorText("LOGOUT_CLEAR_FAILED")).assertIsDisplayed()
    }

    @Test
    fun logoutErrorAloneReopensConfirm() {
        // 失敗碼存續即重現確認態（內部 confirm 旗標為 false 的 fresh 組合也一樣）。
        // Stage A：Ready + STALE 僅關閉（無重試、無取消）。
        var dismissed = 0
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                logoutErrorCode = "LOGOUT_STALE",
                onDismissLogoutError = { dismissed++ },
            )
        }
        composeRule.onAllNodesWithText("登出（清除金鑰）").assertCountEquals(0)
        onLogoutArea(setupErrorText("LOGOUT_STALE")).assertIsDisplayed()
        composeRule.onAllNodesWithText("重試清除").assertCountEquals(0)
        composeRule.onAllNodesWithText("取消").assertCountEquals(0)
        onLogoutArea("關閉").assertIsDisplayed()
        onLogoutArea("關閉").performClick()
        assertEquals(1, dismissed)
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
        // Stage A：retained 碼僅重試可見（執行中 disabled + spinner），無取消/關閉。
        onLogoutArea("清除中…").assertIsNotEnabled()
        composeRule.onAllNodesWithText("取消").assertCountEquals(0)
        composeRule.onAllNodesWithText("關閉").assertCountEquals(0)
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
     * 重試/無障礙設定兩入口皆須可滾入可視區且可點。
     * Stage A：retained 碼無取消鍵（dismiss 不可達，計數保持 0）。
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
        // 兩入口皆 displayed（經產品容器 scrollTo 滾入）；取消/關閉皆無。
        onLogoutArea("前往系統無障礙設定").assertIsDisplayed()
        onLogoutArea("重試清除").assertIsDisplayed()
        composeRule.onAllNodesWithText("取消").assertCountEquals(0)
        composeRule.onAllNodesWithText("關閉").assertCountEquals(0)
        // 兩入口皆可點；無 dismiss 入口，計數保持 0。
        onLogoutArea("前往系統無障礙設定").performClick()
        onLogoutArea("重試清除").performClick()
        assertEquals(1, a11yClicks)
        assertEquals(1, retryCalls)
        assertEquals(0, dismissed)
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
        composeRule.onAllNodesWithText("取消").assertCountEquals(0)
        composeRule.onAllNodesWithText("關閉").assertCountEquals(0)
        onLogoutArea("前往系統無障礙設定").performClick()
        onLogoutArea("重試清除").performClick()
        assertEquals(1, a11yClicks)
        assertEquals(1, retryCalls)
        assertEquals(0, dismissed)
    }

    /**
     * BLOCKING 回歸：fresh「確定清除」走 onLogout（全新 logout），不被劫持為
     * onRetryLogout（生產接線形狀：雙傳 onLogout＋onRetryLogout）。
     */
    @Test
    fun freshConfirmGoesToLogoutNotRetry() {
        var logoutCalls = 0
        var retryCalls = 0
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                onLogout = { logoutCalls++ },
                onRetryLogout = { retryCalls++ },
                logoutErrorCode = null,
            )
        }
        onLogoutArea("登出（清除金鑰）").performClick()
        onLogoutArea("確定清除").performClick()
        assertEquals(1, logoutCalls)
        assertEquals(0, retryCalls)
    }

    /**
     * BLOCKING 回歸對側：retained「重試清除」走 onRetryLogout（同源回放），
     * 不回退全新 onLogout。
     */
    @Test
    fun retainedRetryGoesToRetryNotLogout() {
        var logoutCalls = 0
        var retryCalls = 0
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                onLogout = { logoutCalls++ },
                onRetryLogout = { retryCalls++ },
                logoutErrorCode = "LOGOUT_DELETE_FAILED",
            )
        }
        onLogoutArea("重試清除").performClick()
        assertEquals(0, logoutCalls)
        assertEquals(1, retryCalls)
    }
}
