package dev.librepocket.agent.ui.settings

import android.app.Application
import android.view.MotionEvent
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointGate
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.web.WebProbe
import dev.librepocket.web.WebSearchSettings
import dev.librepocket.web.WebUnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * D3 Settings→Tools→Web Robolectric tests（#138 D3）。
 *
 * - master 開關預設關、回調可達；provider 狀態（可用／缺 URL／缺 key）顯示；
 * - DDGS 非官方警語必顯示（含卡內＋consent sheet）；
 * - `probe()` 按鈕回調＋訊息顯示；首次開啟 consent sheet 確認／取消可達；
 * - `SettingsScreen(web=null)` 整卡隱藏（仿 automation 模式）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WebSearchSettingsCardTest {

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

    private fun onWebTag(tag: String) =
        composeRule.onNodeWithTag(tag).performScrollTo()

    private fun webState(
        master: Boolean = false,
        ddgs: Boolean = false,
        showConsent: Boolean = false,
        probeMessage: String? = null,
    ) = WebSearchSettingsState(
        masterEnabled = master,
        ddgsOptIn = ddgs,
        searxngUrl = "",
        searxngProbe = WebProbe.NotReady(WebUnavailableReason.MISSING_URL),
        ddgsProbe = WebProbe.Ready,
        probeMessage = probeMessage,
        showConsent = showConsent,
        switchesEnabled = true,
    )

    @Test
    fun cardShowsMasterOffAndProviderStatusesAndDdgsWarning() {
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                web = webState(),
            )
        }
        onWebTag("webSearchCard").assertIsDisplayed()
        onWebTag("webMasterSwitch").assertIsDisplayed().assertIsOff()
        onWebTag("webDdgsSwitch").assertIsDisplayed().assertIsOff()
        // SearXNG 空 URL → 缺 URL；DDGS keyless 預設 Ready → 設定格式有效（未連網）。
        onWebTag("webSearxngStatus").assertIsDisplayed()
        composeRule.onNodeWithText("狀態：缺 URL", substring = true).performScrollTo().assertIsDisplayed()
        onWebTag("webDdgsStatus").assertIsDisplayed()
        composeRule.onNodeWithText(
            "狀態：${WebSearchSettings.statusText(WebSearchSettings.ProviderStatus.AVAILABLE)}",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
        // DDGS 非官方警語必顯示（D3 必須）。
        onWebTag("webDdgsWarning").assertIsDisplayed()
        assertTrue(WebSearchSettings.DDGS_UNOFFICIAL_WARNING.contains("非官方"))
    }

    @Test
    fun masterSwitchCallbackReachesCaller() {
        var toggled: Boolean? = null
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                web = webState(master = false),
                onWebMasterSwitch = { toggled = it },
            )
        }
        onWebTag("webMasterSwitch").performClick()
        assertEquals(true, toggled)
    }

    @Test
    fun ddgsSwitchAndUrlInputReachCaller() {
        var ddgs: Boolean? = null
        var url = "<unchanged>"
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                web = webState(),
                onWebDdgsSwitch = { ddgs = it },
                onWebSearxngUrlChange = { url = it },
            )
        }
        onWebTag("webDdgsSwitch").performClick()
        assertEquals(true, ddgs)
        onWebTag("webSearxngUrl").performTextInput("https://search.example")
        assertEquals("https://search.example", url)
    }

    @Test
    fun probeButtonCallbackAndMessageDisplayed() {
        var probed = 0
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                web = webState(probeMessage = "SearXNG：缺 URL；DDGS：設定格式有效（未連網）"),
                onWebProbe = { probed++ },
            )
        }
        onWebTag("webProbeButton").assertIsDisplayed().assertIsEnabled()
        onWebTag("webProbeButton").performClick()
        assertEquals(1, probed)
        onWebTag("webProbeMessage").assertIsDisplayed()
    }

    @Test
    fun consentSheetConfirmAndDismissReachCaller() {
        var confirmed = 0
        var dismissed = 0
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                web = webState(showConsent = true),
                onWebConsentConfirm = { confirmed++ },
                onWebConsentDismiss = { dismissed++ },
            )
        }
        // Consent 標題＋內文＋DDGS 警語皆顯示。
        composeRule.onNodeWithTag("webConsentTitle").assertIsDisplayed()
        composeRule.onNodeWithTag("webConsentText").assertIsDisplayed()
        composeRule.onNodeWithTag("webConsentDdgsWarning").assertIsDisplayed()
        composeRule.onNodeWithTag("webConsentConfirm").performClick()
        assertEquals(1, confirmed)
        assertEquals(0, dismissed)
    }

    @Test
    fun consentDismissReachesCaller() {
        var dismissed = 0
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                web = webState(showConsent = true),
                onWebConsentDismiss = { dismissed++ },
            )
        }
        composeRule.onNodeWithTag("webConsentDismiss").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun consentSubmittingDisablesConfirmAndDismiss() {
        var confirmed = 0
        var dismissed = 0
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                web = webState(showConsent = true).copy(consentSubmitting = true),
                onWebConsentConfirm = { confirmed++ },
                onWebConsentDismiss = { dismissed++ },
            )
        }
        composeRule.onNodeWithTag("webConsentConfirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("webConsentDismiss").assertIsNotEnabled()
        composeRule.onNodeWithTag("webConsentConfirm").performClick()
        composeRule.onNodeWithTag("webConsentDismiss").performClick()
        assertEquals(0, confirmed)
        assertEquals(0, dismissed)
    }

    @Test
    fun consentSheetWhenSwitchesDisabled_disablesConfirmButKeepsDismissEnabled() {
        var confirmed = 0
        var dismissed = 0
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                web = webState(showConsent = true).copy(switchesEnabled = false),
                onWebConsentConfirm = { confirmed++ },
                onWebConsentDismiss = { dismissed++ },
            )
        }
        composeRule.onNodeWithTag("webConsentConfirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("webConsentDismiss").assertIsEnabled()
        composeRule.onNodeWithTag("webConsentConfirm").performClick()
        assertEquals(0, confirmed)
        composeRule.onNodeWithTag("webConsentDismiss").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun missingKeyStatusDisplayed() {
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                web = webState().copy(
                    searxngProbe = WebProbe.NotReady(WebUnavailableReason.MISSING_KEY),
                ),
            )
        }
        composeRule.onNodeWithText("狀態：缺 key", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun invalidUrlAndNotReadyStatusDisplayed() {
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                web = webState().copy(
                    searxngProbe = WebProbe.NotReady(WebUnavailableReason.INVALID_URL),
                    ddgsProbe = WebProbe.NotReady(WebUnavailableReason.NO_PROVIDER),
                ),
            )
        }
        composeRule.onNodeWithText(
            "狀態：${WebSearchSettings.statusText(WebSearchSettings.ProviderStatus.INVALID_URL)}",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "狀態：${WebSearchSettings.statusText(WebSearchSettings.ProviderStatus.NOT_READY)}",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun webNullHidesEntireCard() {
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                web = null,
            )
        }
        composeRule.onNodeWithTag("webSearchCard").assertDoesNotExist()
        composeRule.onNodeWithTag("webMasterSwitch").assertDoesNotExist()
    }

    @Test
    fun switchesDisabledWhenNotLoaded() {
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                web = webState().copy(switchesEnabled = false),
            )
        }
        onWebTag("webMasterSwitch").assertIsNotEnabled()
        onWebTag("webDdgsSwitch").assertIsNotEnabled()
        onWebTag("webSearxngUrl").assertIsNotEnabled()
        onWebTag("webProbeButton").assertIsNotEnabled()
    }

    @Test
    fun urlEditableFalseDisablesUrlFieldOnly() {
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                web = webState().copy(switchesEnabled = true, urlEditable = false),
            )
        }
        onWebTag("webSearxngUrl").assertIsNotEnabled()
        onWebTag("webMasterSwitch").assertIsEnabled()
        onWebTag("webDdgsSwitch").assertIsEnabled()
        onWebTag("webProbeButton").assertIsEnabled()
    }

    @Test
    fun consentSubmittingBlocksBackPressDismissAndKeepsDialogDisplayed() {
        var submitting by mutableStateOf(true)
        var dismissed = 0
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                web = webState(showConsent = true).copy(consentSubmitting = submitting),
                onWebConsentDismiss = { dismissed++ },
            )
        }
        composeRule.onNodeWithTag("webConsentTitle").assertIsDisplayed()
        val dialog = ShadowDialog.getLatestDialog() as ComponentDialog
        composeRule.runOnIdle {
            dialog.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        assertEquals(0, dismissed)
        composeRule.onNodeWithTag("webConsentTitle").assertIsDisplayed()

        // Positive control: once consentSubmitting becomes false, back press reaches onWebConsentDismiss.
        submitting = false
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            dialog.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test
    fun consentSubmittingBlocksOutsideTouchDismissAndKeepsDialogDisplayed() {
        var submitting by mutableStateOf(true)
        var dismissed = 0
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                web = webState(showConsent = true).copy(consentSubmitting = submitting),
                onWebConsentDismiss = { dismissed++ },
            )
        }
        composeRule.onNodeWithTag("webConsentTitle").assertIsDisplayed()
        val dialog = ShadowDialog.getLatestDialog() as ComponentDialog

        fun dispatchOutsideTap(): Boolean {
            val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, -100f, -100f, 0)
            val up = MotionEvent.obtain(0L, 10L, MotionEvent.ACTION_UP, -100f, -100f, 0)
            return try {
                val downHandled = dialog.onTouchEvent(down)
                val upHandled = dialog.onTouchEvent(up)
                downHandled || upHandled
            } finally {
                down.recycle()
                up.recycle()
            }
        }

        val handledWhileSubmitting = composeRule.runOnIdle { dispatchOutsideTap() }
        composeRule.waitForIdle()
        assertFalse(handledWhileSubmitting)
        assertEquals(0, dismissed)
        composeRule.onNodeWithTag("webConsentTitle").assertIsDisplayed()

        // Positive control: once consentSubmitting becomes false, outside touch dismiss reaches onWebConsentDismiss.
        submitting = false
        composeRule.waitForIdle()
        val handledAfterSubmit = composeRule.runOnIdle { dispatchOutsideTap() }
        composeRule.waitForIdle()
        assertTrue(handledAfterSubmit)
        assertEquals(1, dismissed)
    }

    @Test
    fun probeButtonShowsHonestCopy() {
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                web = webState(),
            )
        }
        composeRule.onNodeWithText("檢查設定（不連線）").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun loadingStateDisplaysLoadingMessage() {
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                web = webState().copy(isLoading = true),
            )
        }
        onWebTag("webLoading").assertIsDisplayed()
        composeRule.onNodeWithText("網路搜尋設定讀取中…").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun loadFailedDisplaysErrorMessageAndRetryButtonInvokesCallback() {
        var retried = 0
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                web = webState().copy(loadFailed = true),
                onWebRetryLoad = { retried++ },
            )
        }
        onWebTag("webLoadFailed").assertIsDisplayed()
        composeRule.onNodeWithText("網路搜尋設定讀取失敗，開關暫時停用。").performScrollTo().assertIsDisplayed()
        onWebTag("webRetryLoadButton").assertIsDisplayed().performClick()
        assertEquals(1, retried)
    }

    @Test
    fun writeErrorDisplaysErrorBanner() {
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                web = webState().copy(writeError = "URL 含有不支援的控制字元"),
            )
        }
        onWebTag("webWriteError").assertIsDisplayed()
        composeRule.onNodeWithText("URL 含有不支援的控制字元").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun invalidUrlDisplaysSupportingText() {
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                web = webState().copy(
                    searxngProbe = WebProbe.NotReady(WebUnavailableReason.INVALID_URL),
                ),
            )
        }
        onWebTag("webSearxngUrl").assertIsDisplayed()
        composeRule.onNodeWithTag("webSearxngSupportingText", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("URL 格式無效，請以 http:// 或 https:// 開頭").assertIsDisplayed()
    }

    @Test
    fun canResetTrueEnablesResetButtonAndInvokesCallback() {
        var reset = 0
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                web = webState().copy(canReset = true, switchesEnabled = true),
                onWebResetSettings = { reset++ },
            )
        }
        onWebTag("webResetButton").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, reset)
    }

    @Test
    fun canResetFalseDisablesResetButton() {
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                web = webState().copy(canReset = false, switchesEnabled = true),
            )
        }
        onWebTag("webResetButton").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun consentAlertDialogContentIsScrollable() {
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                web = webState(showConsent = true),
            )
        }
        composeRule.onNodeWithTag("webConsentTitle").assertIsDisplayed()
        composeRule.onNodeWithTag("webConsentText").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("webConsentDdgsWarning").performScrollTo().assertIsDisplayed()
    }

    private class UnknownTestViewModel : ViewModel()

    @Test
    fun webSearchViewModelFactory_createsWebSearchViewModelAndRejectsUnknownClass() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val factory = WebSearchViewModelFactory(app)
        assertThrows(IllegalArgumentException::class.java) {
            factory.create(UnknownTestViewModel::class.java)
        }

        val vmStore = ViewModelStore()
        try {
            val vm = ViewModelProvider(vmStore, factory)[WebSearchViewModel::class.java]
            assertEquals(WebSearchViewModel::class.java, vm::class.java)
        } finally {
            vmStore.clear()
        }
    }
}
