package dev.librepocket.agent.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointGate
import dev.librepocket.agent.assistant.AssistantReasonCode
import dev.librepocket.agent.assistant.AssistantSettingsState
import dev.librepocket.provider.ProviderProtocol
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C1b 預設助理卡測試（Issue #137：Robolectric + compose rule，unit source set）。
 *
 * - 未接線（null）整卡隱藏；已持有顯示狀態且按鍵 disabled；
 * - 可要求時按鍵直達回調；原因碼（被拒）顯示文案且 fallback 按鍵可達。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AssistantCardTest {

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

    private fun setSettings(
        assistant: AssistantSettingsState?,
        onRequest: () -> Unit = {},
        onFallback: () -> Unit = {},
    ) {
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                assistant = assistant,
                onRequestAssistantRole = onRequest,
                onOpenVoiceInputSettings = onFallback,
            )
        }
    }

    @Test
    fun nullAssistant_hidesCard() {
        setSettings(null)
        composeRule.onNodeWithText("預設助理（系統助手）").assertDoesNotExist()
    }

    @Test
    fun held_showsHeldStateAndDisablesRequest() {
        setSettings(AssistantSettingsState(roleHeld = true, roleAvailable = true))
        composeRule.onNodeWithText("預設助理（系統助手）").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("設為預設助理").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun available_requestsRoleViaCallback() {
        var requests = 0
        setSettings(
            AssistantSettingsState(roleHeld = false, roleAvailable = true),
            onRequest = { requests++ },
        )
        composeRule.onNodeWithText("設為預設助理").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText("設為預設助理").performClick()
        assertEquals(1, requests)
    }

    @Test
    fun denied_showsReasonAndFallbackReachesCallback() {
        var fallbacks = 0
        setSettings(
            AssistantSettingsState(
                roleHeld = false,
                roleAvailable = true,
                reasonCode = AssistantReasonCode.DENIED,
            ),
            onFallback = { fallbacks++ },
        )
        composeRule.onNodeWithText("要求被拒：維持現狀，可改用語音輸入設定或稍後重試。")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("語音輸入設定").performScrollTo().performClick()
        assertEquals(1, fallbacks)
    }

    @Test
    fun unsupported_showsUnsupportedState() {
        setSettings(AssistantSettingsState(roleHeld = false, roleAvailable = false))
        composeRule.onNodeWithText("此裝置不支援系統助理角色，可改用語音輸入設定。")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("設為預設助理").performScrollTo().assertIsNotEnabled()
    }
}
