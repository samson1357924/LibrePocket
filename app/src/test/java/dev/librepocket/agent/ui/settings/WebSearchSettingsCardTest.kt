package dev.librepocket.agent.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointGate
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.web.WebProbe
import dev.librepocket.web.WebSearchSettings
import dev.librepocket.web.WebUnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
        onWebTag("webMasterSwitch").assertIsDisplayed()
        // SearXNG 空 URL → 缺 URL；DDGS keyless 預設 Ready → 可用。
        onWebTag("webSearxngStatus").assertIsDisplayed()
        composeRule.onNodeWithText("狀態：缺 URL", substring = true).performScrollTo().assertIsDisplayed()
        onWebTag("webDdgsStatus").assertIsDisplayed()
        composeRule.onNodeWithText("狀態：可用", substring = true).performScrollTo().assertIsDisplayed()
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
                web = webState(probeMessage = "SearXNG：缺 URL；DDGS：可用"),
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
        onWebTag("webProbeButton").assertIsNotEnabled()
    }
}
