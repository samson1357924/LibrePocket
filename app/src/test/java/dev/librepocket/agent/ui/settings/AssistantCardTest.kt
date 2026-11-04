package dev.librepocket.agent.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointGate
import dev.librepocket.agent.assistant.AssistantReasonCode
import dev.librepocket.agent.assistant.AssistantSettingsState
import dev.librepocket.agent.assistant.assistantReasonText
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
 *
 * Phase 4 補強（review WARN 回填）：
 * - C4：avail=true／held=false／reason=null 顯示「尚未設為…」且請求 enabled。
 * - C6：REVOKED／UNSUPPORTED／REQUEST_FAILED／GRANTED（GRANTED 只測非 held 組合，
 *   held+GRANTED 與頭部同字串會歧義，生產側 held 即清 reason 為 null）精確文案比對。
 * - C7：reason==null 時 DENIED 文案不存在。
 * - C8：(avail=false, held=true) 顯示 held 文案＋請求 disabled（decide B2：held 優先）。
 * - C9：held 下 fallback 仍可達（回調一次）。
 * - fallbackFailed=true 顯示「無法開啟語音輸入設定」error 行；held hint 顯示。
 * - reason 色彩 compose 斷言麻煩，至少斷言文案存在；一律用 testTag assistantReason
 *   區分原因行與頭部（held 頭部與 GRANTED 原因同字串，onNodeWithText 會命中兩節點，
 *   故用 testTag 或 onAllNodesWithText 計數）。
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
        composeRule.onNodeWithText("設為預設助理").performScrollTo().performClick()
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
        var requests = 0
        setSettings(
            AssistantSettingsState(roleHeld = false, roleAvailable = false),
            onRequest = { requests++ },
        )
        // 頭部不支援文案仍顯示（Stage 2 review #3：主按鈕改為 enabled「前往語音輸入設定」，
        // 點擊仍走 onRequestAssistantRole → request() UnsupportedFallback 分支）。
        composeRule.onNodeWithText("此裝置不支援系統助理角色，可改用語音輸入設定。")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("前往語音輸入設定").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText("前往語音輸入設定").performScrollTo().performClick()
        assertEquals(1, requests)
    }

    /** C4：可要求閒置態顯示「尚未設為…」頭部，請求 enabled，且無原因行。 */
    @Test
    fun availableIdle_showsNotYetHeaderAndEnablesRequest() {
        setSettings(AssistantSettingsState(roleHeld = false, roleAvailable = true))
        composeRule.onNodeWithText("尚未設為預設助理；授予後長按首頁鍵或系統手勢可叫出 LibrePocket。")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("設為預設助理").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag("assistantReason").assertDoesNotExist()
    }

    /**
     * C6 參數化：各原因碼精確文案（[assistantReasonText] 同源比對）。
     * GRANTED 只測非 held 組合：此為繞過調和、直灌 SettingsScreen 的渲染層輸入；
     * 經 rememberAssistantRole 管線時非 held GRANTED 會被調和為 REVOKED、held 則清 null
     * （調和語義見 AssistantRoleTest），故生產管線此處應顯示 REVOKED。
     * UNSUPPORTED 語義配 avail=false，其餘配 avail=true；原因行一律經 testTag 斷言避歧義。
     * （compose rule 單 test 只允許一次 setContent，故拆四個 test 表達參數化。）
     */
    @Test
    fun reasonRevoked_showsExactText() {
        setSettings(
            AssistantSettingsState(
                roleHeld = false,
                roleAvailable = true,
                reasonCode = AssistantReasonCode.REVOKED,
            ),
        )
        composeRule.onNodeWithTag("assistantReason")
            .performScrollTo().assertIsDisplayed()
            .assertTextEquals(assistantReasonText(AssistantReasonCode.REVOKED))
    }

    @Test
    fun reasonUnsupported_showsExactText() {
        setSettings(
            AssistantSettingsState(
                roleHeld = false,
                roleAvailable = false,
                reasonCode = AssistantReasonCode.UNSUPPORTED,
            ),
        )
        composeRule.onNodeWithTag("assistantReason")
            .performScrollTo().assertIsDisplayed()
            .assertTextEquals(assistantReasonText(AssistantReasonCode.UNSUPPORTED))
    }

    @Test
    fun reasonRequestFailed_showsExactText() {
        setSettings(
            AssistantSettingsState(
                roleHeld = false,
                roleAvailable = true,
                reasonCode = AssistantReasonCode.REQUEST_FAILED,
            ),
        )
        composeRule.onNodeWithTag("assistantReason")
            .performScrollTo().assertIsDisplayed()
            .assertTextEquals(assistantReasonText(AssistantReasonCode.REQUEST_FAILED))
    }

    @Test
    fun reasonGrantedNonHeld_showsExactText() {
        setSettings(
            AssistantSettingsState(
                roleHeld = false,
                roleAvailable = true,
                reasonCode = AssistantReasonCode.GRANTED,
            ),
        )
        composeRule.onNodeWithTag("assistantReason")
            .performScrollTo().assertIsDisplayed()
            .assertTextEquals(assistantReasonText(AssistantReasonCode.GRANTED))
    }

    /** C7：reason==null 時原因行與 DENIED 文案皆不存在。 */
    @Test
    fun nullReason_hidesDeniedTextAndReasonTag() {
        setSettings(AssistantSettingsState(roleHeld = false, roleAvailable = true))
        composeRule.onNodeWithTag("assistantReason").assertDoesNotExist()
        composeRule.onNodeWithText(assistantReasonText(AssistantReasonCode.DENIED))
            .assertDoesNotExist()
    }

    /**
     * C8：(avail=false, held=true) 仍顯示 held 頭部＋請求 disabled
     *（decide B2：held 優先於 avail）。計數斷言鎖定同字串恰出現一次。
     */
    @Test
    fun heldOverridesUnavailable_showsHeldAndDisablesRequest() {
        setSettings(AssistantSettingsState(roleHeld = true, roleAvailable = false))
        val heldText = "已設為預設助理：長按首頁鍵或系統手勢可叫出 LibrePocket。"
        composeRule.onAllNodesWithText(heldText).assertCountEquals(1)
        composeRule.onAllNodesWithText(heldText)[0].performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("設為預設助理").performScrollTo().assertIsNotEnabled()
        // held 下取消指引恆顯示（取消須走系統設定，見 SettingsScreen B held 指引）。
        composeRule.onNodeWithTag("assistantCancelHint")
            .performScrollTo().assertIsDisplayed()
            .assertTextEquals("如需取消，請至系統設定 → 預設應用程式")
    }

    /** C9：held 下 fallback 恆可達（點一次回調一次）。 */
    @Test
    fun held_fallbackAlwaysReachable() {
        var fallbacks = 0
        setSettings(
            AssistantSettingsState(roleHeld = true, roleAvailable = true),
            onFallback = { fallbacks++ },
        )
        composeRule.onNodeWithText("語音輸入設定").performScrollTo().performClick()
        assertEquals(1, fallbacks)
    }

    @Test
    fun fallbackFailed_showsErrorRow() {
        setSettings(
            AssistantSettingsState(
                roleHeld = false,
                roleAvailable = true,
                fallbackFailed = true,
            ),
        )
        composeRule.onNodeWithTag("assistantFallbackFailed")
            .performScrollTo().assertIsDisplayed()
            .assertTextEquals("無法開啟語音輸入設定")
    }

    @Test
    fun fallbackOk_hidesErrorRow() {
        setSettings(AssistantSettingsState(roleHeld = false, roleAvailable = true))
        composeRule.onNodeWithTag("assistantFallbackFailed").assertDoesNotExist()
    }

    @Test
    fun held_showsCancelHint() {
        setSettings(AssistantSettingsState(roleHeld = true, roleAvailable = true))
        composeRule.onNodeWithTag("assistantCancelHint")
            .performScrollTo().assertIsDisplayed()
            .assertTextEquals("如需取消，請至系統設定 → 預設應用程式")
    }

    @Test
    fun notHeld_hidesCancelHint() {
        setSettings(AssistantSettingsState(roleHeld = false, roleAvailable = true))
        composeRule.onNodeWithTag("assistantCancelHint").assertDoesNotExist()
    }

    /** DENIED 原因行經 testTag 存在且文案精確（color 斷言麻煩，只鎖文案）。 */
    @Test
    fun denied_showsReasonTagWithExactText() {
        setSettings(
            AssistantSettingsState(
                roleHeld = false,
                roleAvailable = true,
                reasonCode = AssistantReasonCode.DENIED,
            ),
        )
        composeRule.onNodeWithTag("assistantReason")
            .performScrollTo().assertIsDisplayed()
            .assertTextEquals(assistantReasonText(AssistantReasonCode.DENIED))
    }

    // ---- Stage 2 finding C：ReadError 頭部／主按鈕 fail-closed ----

    /** ReadError 顯示「無法確認…」未知文案（與「不支援」區分），主按鈕 disabled。 */
    @Test
    fun readError_showsUnknownHeaderAndDisablesRequest() {
        setSettings(
            AssistantSettingsState(
                roleHeld = false,
                roleAvailable = false,
                readError = true,
            ),
        )
        composeRule.onNodeWithText("無法確認助理狀態，請稍後重試。")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("設為預設助理").performScrollTo().assertIsNotEnabled()
    }

    /** ReadError 原因行 passthrough：stored 碼照常顯示（此處以 DENIED 為例）。 */
    @Test
    fun readError_passesThroughStoredReason() {
        setSettings(
            AssistantSettingsState(
                roleHeld = false,
                roleAvailable = false,
                reasonCode = AssistantReasonCode.DENIED,
                readError = true,
            ),
        )
        composeRule.onNodeWithText("無法確認助理狀態，請稍後重試。")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("assistantReason")
            .performScrollTo().assertIsDisplayed()
            .assertTextEquals(assistantReasonText(AssistantReasonCode.DENIED))
    }

    /** ReadError 無 stored 碼時原因行不存在（passthrough null，不憑空判撤銷）。 */
    @Test
    fun readErrorWithoutReason_hidesReasonTag() {
        setSettings(
            AssistantSettingsState(
                roleHeld = false,
                roleAvailable = false,
                readError = true,
            ),
        )
        composeRule.onNodeWithTag("assistantReason").assertDoesNotExist()
        composeRule.onNodeWithText(assistantReasonText(AssistantReasonCode.REVOKED))
            .assertDoesNotExist()
    }

    /** ReadError 下 fallback 按鈕仍可達（未知態只擋主按鈕，不擋語音輸入設定）。 */
    @Test
    fun readError_fallbackStillReachable() {
        var fallbacks = 0
        setSettings(
            AssistantSettingsState(
                roleHeld = false,
                roleAvailable = false,
                readError = true,
            ),
            onFallback = { fallbacks++ },
        )
        composeRule.onNodeWithText("語音輸入設定").performScrollTo().performClick()
        assertEquals(1, fallbacks)
    }
}
