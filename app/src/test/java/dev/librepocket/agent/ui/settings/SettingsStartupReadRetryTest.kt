package dev.librepocket.agent.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointGate
import dev.librepocket.agent.ui.setup.SetupViewModel
import dev.librepocket.agent.ui.setup.setupErrorText
import dev.librepocket.provider.ProviderProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage D1（R12 Finding 1 P2/BLOCK）UI/導航驗收。
 *
 * - 新碼 `LOGOUT_READ_RETRY`：Ready＋NoEndpoint 兩分支僅 [重試讀取]
 *   （無重試清除／無取消／無關閉；enabled 綁 startupReconcilePending）。
 * - 舊四碼（READ/DELETE/CLEAR/UNKNOWN retained）仍僅重試清除；STALE 僅關閉。
 * - 文案禁寫「已刪除」／「殘留」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsStartupReadRetryTest {

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

    private fun onArea(text: String) =
        composeRule.onNodeWithText(text).performScrollTo()

    @Test
    fun readRetryTextHasNoDeletedOrResidual() {
        val text = setupErrorText(SetupViewModel.LOGOUT_READ_RETRY)
        assertFalse("neutral wording must not claim deleted: $text", text.contains("已刪除"))
        assertFalse("neutral wording must not claim residual: $text", text.contains("殘留"))
        assertTrue(text.contains("未做任何變更"))
        assertTrue(text.contains("重試讀取"))
        // 與 retained UNKNOWN 嚴格區分（後者含殘留）。
        assertTrue(setupErrorText("LOGOUT_UNKNOWN").contains("殘留"))
        // 與 logout 讀失敗碼嚴格區分（不可複用）。
        assertEquals("LOGOUT_READ_FAILED", "LOGOUT_READ_FAILED")
        assertFalse(SetupViewModel.LOGOUT_READ_RETRY == "LOGOUT_READ_FAILED")
        assertFalse(SetupViewModel.LOGOUT_READ_RETRY == "LOGOUT_UNKNOWN")
    }

    @Test
    fun noEndpointReadRetryShowsOnlyRetryRead() {
        var retryReadCalls = 0
        var logoutCalls = 0
        var dismissed = 0
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = EndpointGate.NoEndpoint,
                onLogout = { logoutCalls++ },
                logoutErrorCode = SetupViewModel.LOGOUT_READ_RETRY,
                onDismissLogoutError = { dismissed++ },
                onRetryStartupRead = { retryReadCalls++ },
            )
        }
        onArea(setupErrorText(SetupViewModel.LOGOUT_READ_RETRY)).assertIsDisplayed()
        onArea("重試讀取").assertIsDisplayed()
        // 無重試清除／取消／關閉／前往設定。
        composeRule.onAllNodesWithText("重試清除").assertCountEquals(0)
        composeRule.onAllNodesWithText("取消").assertCountEquals(0)
        composeRule.onAllNodesWithText("關閉").assertCountEquals(0)
        composeRule.onAllNodesWithText("尚未設定 API 端點。").assertCountEquals(0)
        composeRule.onAllNodesWithText("前往設定").assertCountEquals(0)
        onArea("重試讀取").performClick()
        assertEquals(1, retryReadCalls)
        assertEquals("retry-read must never call logout", 0, logoutCalls)
        assertEquals(0, dismissed)
    }

    @Test
    fun readyReadRetryShowsOnlyRetryReadWithoutDestructivePrompt() {
        var retryReadCalls = 0
        var logoutCalls = 0
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                onLogout = { logoutCalls++ },
                logoutErrorCode = SetupViewModel.LOGOUT_READ_RETRY,
                onRetryStartupRead = { retryReadCalls++ },
            )
        }
        onArea(setupErrorText(SetupViewModel.LOGOUT_READ_RETRY)).assertIsDisplayed()
        onArea("重試讀取").assertIsDisplayed()
        // 非破壞性：不顯示破壞性確認文案與破壞性鍵。
        composeRule.onAllNodesWithText("確定清除本機金鑰與端點設定？此動作無法復原。").assertCountEquals(0)
        composeRule.onAllNodesWithText("確定清除").assertCountEquals(0)
        composeRule.onAllNodesWithText("重試清除").assertCountEquals(0)
        composeRule.onAllNodesWithText("取消").assertCountEquals(0)
        composeRule.onAllNodesWithText("關閉").assertCountEquals(0)
        composeRule.onAllNodesWithText("登出（清除金鑰）").assertCountEquals(0)
        onArea("重試讀取").performClick()
        assertEquals(1, retryReadCalls)
        assertEquals(0, logoutCalls)
    }

    @Test
    fun readRetryEnabledBindsPendingNotInFlight() {
        // pending=true → disabled＋讀取中…（即使 inFlight=false）。
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = EndpointGate.NoEndpoint,
                logoutErrorCode = SetupViewModel.LOGOUT_READ_RETRY,
                startupReconcilePending = true,
                logoutInFlight = false,
            )
        }
        onArea("讀取中…").assertIsDisplayed()
        onArea("讀取中…").assertIsNotEnabled()
        composeRule.onAllNodesWithText("重試讀取").assertCountEquals(0)
        composeRule.onAllNodesWithText("清除中…").assertCountEquals(0)
    }

    @Test
    fun readRetryIgnoresLogoutInFlight() {
        // inFlight=true 但 pending=false → 仍 enabled（綁 pending，非 inFlight）。
        var retryReadCalls = 0
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = EndpointGate.NoEndpoint,
                logoutErrorCode = SetupViewModel.LOGOUT_READ_RETRY,
                logoutInFlight = true,
                startupReconcilePending = false,
                onRetryStartupRead = { retryReadCalls++ },
            )
        }
        onArea("重試讀取").assertIsDisplayed()
        onArea("重試讀取").assertIsEnabled()
        onArea("重試讀取").performClick()
        assertEquals(1, retryReadCalls)
    }

    @Test
    fun readyReadRetryPendingShowsReadingSpinner() {
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                logoutErrorCode = SetupViewModel.LOGOUT_READ_RETRY,
                startupReconcilePending = true,
            )
        }
        onArea("讀取中…").assertIsDisplayed()
        onArea("讀取中…").assertIsNotEnabled()
        composeRule.onAllNodesWithText("清除中…").assertCountEquals(0)
    }

    @Test
    fun oldFourCodesStillOnlyRetryClear() {
        // 舊四碼（retained）仍僅重試清除（無重試讀取／無取消／無關閉）。
        var code by mutableStateOf("LOGOUT_READ_FAILED")
        var retryCalls = 0
        var readCalls = 0
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = EndpointGate.NoEndpoint,
                onLogout = { retryCalls++ },
                logoutErrorCode = code,
                onRetryStartupRead = { readCalls++ },
            )
        }
        for (c in listOf("LOGOUT_READ_FAILED", "LOGOUT_DELETE_FAILED", "LOGOUT_CLEAR_FAILED", "LOGOUT_UNKNOWN")) {
            code = c
            composeRule.waitForIdle()
            onArea(setupErrorText(c)).assertIsDisplayed()
            onArea("重試清除").assertIsDisplayed()
            composeRule.onAllNodesWithText("重試讀取").assertCountEquals(0)
            composeRule.onAllNodesWithText("取消").assertCountEquals(0)
            composeRule.onAllNodesWithText("關閉").assertCountEquals(0)
        }
        onArea("重試清除").performClick()
        assertEquals(1, retryCalls)
        assertEquals(0, readCalls)
    }

    @Test
    fun readyOldFourCodesStillOnlyRetryClear() {
        var code by mutableStateOf("LOGOUT_DELETE_FAILED")
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                logoutErrorCode = code,
            )
        }
        for (c in listOf("LOGOUT_DELETE_FAILED", "LOGOUT_CLEAR_FAILED", "LOGOUT_UNKNOWN", "LOGOUT_READ_FAILED")) {
            code = c
            composeRule.waitForIdle()
            onArea(setupErrorText(c)).assertIsDisplayed()
            onArea("重試清除").assertIsDisplayed()
            composeRule.onAllNodesWithText("重試讀取").assertCountEquals(0)
            composeRule.onAllNodesWithText("取消").assertCountEquals(0)
            composeRule.onAllNodesWithText("關閉").assertCountEquals(0)
        }
    }

    @Test
    fun staleStillOnlyDismiss() {
        // STALE 僅關閉（兩分支皆然）。
        var dismissed = 0
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = EndpointGate.NoEndpoint,
                logoutErrorCode = "LOGOUT_STALE",
                onDismissLogoutError = { dismissed++ },
            )
        }
        onArea(setupErrorText("LOGOUT_STALE")).assertIsDisplayed()
        composeRule.onAllNodesWithText("重試清除").assertCountEquals(0)
        composeRule.onAllNodesWithText("重試讀取").assertCountEquals(0)
        onArea("關閉").assertIsDisplayed()
        onArea("關閉").performClick()
        assertEquals(1, dismissed)
    }
}
