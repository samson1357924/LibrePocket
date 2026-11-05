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
import dev.librepocket.agent.assistant.AssistantSnapshot
import dev.librepocket.agent.assistant.assistantReasonText
import dev.librepocket.agent.assistant.effectiveAssistantReason
import dev.librepocket.agent.assistant.reconcileFallbackFailedOnHeldObserved
import dev.librepocket.provider.ProviderProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        onRecheck: () -> Unit = {},
    ) {
        composeRule.setContent {
            SettingsScreen(
                padding = PaddingValues(0.dp),
                gate = readyGate(),
                assistant = assistant,
                onRequestAssistantRole = onRequest,
                onRecheckAssistantRole = onRecheck,
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

    /**
     * re-review #3 finding #1 [P2]：VERIFYING 原因行渲染（[assistantReasonText]
     * 同源比對）。生產管線經 rememberAssistantRole 調和：RESULT_OK 後首幀即
     * VERIFYING（stored=null＋pendingVerify），held 確認即清除、超時收斂
     * （!everHeld→null 隱藏本行、everHeld→REVOKED），此處僅鎖定渲染層輸入。
     */
    @Test
    fun reasonVerifying_showsExactText() {
        setSettings(
            AssistantSettingsState(
                roleHeld = false,
                roleAvailable = true,
                reasonCode = AssistantReasonCode.VERIFYING,
            ),
        )
        composeRule.onNodeWithTag("assistantReason")
            .performScrollTo().assertIsDisplayed()
            .assertTextEquals("要求已送出，正在向系統確認角色狀態…")
    }

    /**
     * re-review #4 finding #1 [P2/Merge blocker]：UNCONFIRMED 原因行渲染
     * （[assistantReasonText] 同源比對）。生產管線經終止收斂寫入：
     * RESULT_OK 後重試耗盡仍未確認持有（!everHeld）或連續讀取失敗，
     * 原因行提示「可重新檢查」，主按鈕重試可達（ReadError 下「重新檢查」）。
     */
    @Test
    fun reasonUnconfirmed_showsExactText() {
        setSettings(
            AssistantSettingsState(
                roleHeld = false,
                roleAvailable = true,
                reasonCode = AssistantReasonCode.UNCONFIRMED,
            ),
        )
        composeRule.onNodeWithTag("assistantReason")
            .performScrollTo().assertIsDisplayed()
            .assertTextEquals(assistantReasonText(AssistantReasonCode.UNCONFIRMED))
    }

    /** UNCONFIRMED＋ReadError：未知頭部＋未確認原因行並存，主按鈕「重新檢查」可達。 */
    @Test
    fun unconfirmedWithReadError_showsBothAndRecheckReachable() {
        var rechecks = 0
        var requests = 0
        val reason = effectiveAssistantReason(
            AssistantReasonCode.UNCONFIRMED,
            AssistantSnapshot.ReadError,
            everHeld = false,
            pendingVerify = false,
        )
        assertEquals(AssistantReasonCode.UNCONFIRMED, reason)
        setSettings(
            AssistantSettingsState(
                roleHeld = false,
                roleAvailable = false,
                reasonCode = reason,
                readError = true,
            ),
            onRequest = { requests++ },
            onRecheck = { rechecks++ },
        )
        composeRule.onNodeWithText("無法確認助理狀態，請稍後重試。")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("assistantReason")
            .performScrollTo().assertIsDisplayed()
            .assertTextEquals(assistantReasonText(AssistantReasonCode.UNCONFIRMED))
        composeRule.onNodeWithText("重新檢查").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText("重新檢查").performScrollTo().performClick()
        // re-review #5 finding #2 方案 A 行為變更：ReadError「重新檢查」統一走
        // onRecheck（recheck 通道，只重讀不開頁），不再走 onRequest。
        assertEquals(1, rechecks)
        assertEquals(0, requests)
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

    /** ReadError 顯示「無法確認…」未知文案（與「不支援」區分），主按鈕為「重新檢查」可點。 */
    @Test
    fun readError_showsUnknownHeaderAndEnablesRecheck() {
        var rechecks = 0
        var requests = 0
        setSettings(
            AssistantSettingsState(
                roleHeld = false,
                roleAvailable = false,
                readError = true,
            ),
            onRequest = { requests++ },
            onRecheck = { rechecks++ },
        )
        composeRule.onNodeWithText("無法確認助理狀態，請稍後重試。")
            .performScrollTo().assertIsDisplayed()
        // re-review #4 finding #1 [P2/Merge blocker]：ReadError 主按鈕不再 disabled，
        // 文案「重新檢查」（與 Unsupported「前往語音輸入設定」區分），點擊觸發重讀。
        // re-review #5 finding #2 方案 A 行為變更：統一走 onRecheck（recheck 通道），
        // 不再走 onRequest（request() ReadError 分支直接委託 recheck()，同義）。
        composeRule.onNodeWithText("重新檢查").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText("重新檢查").performScrollTo().performClick()
        assertEquals(1, rechecks)
        assertEquals(0, requests)
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

    /**
     * re-review #3 finding #2 [P2]：ReadError＋GRANTED 不顯示「已設為…」。
     * 管線（[effectiveAssistantReason]）將歷史 GRANTED 遮罩為 null（歷史成功≠
     * 當前持有），故頭部「無法確認」＋原因行「已設為…」不可並存；此處經管線
     * 同源計算 reasonCode 再灌入渲染層，鎖定端到端隱藏。
     */
    @Test
    fun readErrorGranted_hidesGrantedReasonRow() {
        // 管線同源計算：ReadError＋GRANTED（everHeld=true 模擬 AlreadyHeld 路徑殘留）
        // 必須為 null，否則即管線迴歸。
        val reason = effectiveAssistantReason(
            AssistantReasonCode.GRANTED,
            AssistantSnapshot.ReadError,
            everHeld = true,
            pendingVerify = false,
        )
        assertNull(reason)
        setSettings(
            AssistantSettingsState(
                roleHeld = false,
                roleAvailable = false,
                reasonCode = reason,
                readError = true,
            ),
        )
        composeRule.onNodeWithText("無法確認助理狀態，請稍後重試。")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("assistantReason").assertDoesNotExist()
        composeRule.onNodeWithText(assistantReasonText(AssistantReasonCode.GRANTED))
            .assertDoesNotExist()
    }

    /**
     * re-review #4 finding #2 [P3/UX]：VERIFYING 顯示期間主按鈕 disabled。
     * state.verifying=true（rememberAssistantRole 由 pendingVerify 傳入）即禁用，
     * 防重複點擊重開授權頁；request() 入口另有 canStartRequest 防禦性 no-op。
     */
    @Test
    fun verifying_disablesMainButton() {
        setSettings(
            AssistantSettingsState(
                roleHeld = false,
                roleAvailable = true,
                reasonCode = AssistantReasonCode.VERIFYING,
                verifying = true,
            ),
        )
        composeRule.onNodeWithTag("assistantReason")
            .performScrollTo().assertIsDisplayed()
            .assertTextEquals(assistantReasonText(AssistantReasonCode.VERIFYING))
        composeRule.onNodeWithText("設為預設助理").performScrollTo().assertIsNotEnabled()
    }

    /** re-review #4 finding #2：非驗證中主按鈕保持 enabled（舊構造預設 verifying=false 相容）。 */
    @Test
    fun notVerifying_keepsMainButtonEnabled() {
        setSettings(
            AssistantSettingsState(roleHeld = false, roleAvailable = true),
        )
        composeRule.onNodeWithText("設為預設助理").performScrollTo().assertIsEnabled()
    }

    /**
     * re-review #4 finding #3 [P3/UX]：UNSUPPORTED 原因行＋fallback 失敗紅字並存渲染。
     * 原因行祈使（「請改用語音輸入設定」）不預設 fallback 已開啟，與紅字
     * 「無法開啟語音輸入設定」語義不矛盾（舊「已改用…」完成式與紅字矛盾）。
     */
    @Test
    fun unsupportedWithFallbackFailed_showsBothRowsWithoutContradiction() {
        setSettings(
            AssistantSettingsState(
                roleHeld = false,
                roleAvailable = false,
                reasonCode = AssistantReasonCode.UNSUPPORTED,
                fallbackFailed = true,
            ),
        )
        composeRule.onNodeWithTag("assistantReason")
            .performScrollTo().assertIsDisplayed()
            .assertTextEquals("不支援系統助理角色，請改用語音輸入設定。")
        composeRule.onNodeWithTag("assistantFallbackFailed")
            .performScrollTo().assertIsDisplayed()
            .assertTextEquals("無法開啟語音輸入設定")
    }

    /**
     * re-review #4 finding #3 [P3/UX]：REQUEST_FAILED 原因行＋fallback 失敗紅字並存渲染。
     * 同上：原因行「無法開啟系統授權頁，請改用語音輸入設定。」＋紅字並存，
     * 無「已改用」完成式矛盾。
     */
    @Test
    fun requestFailedWithFallbackFailed_showsBothRowsWithoutContradiction() {
        setSettings(
            AssistantSettingsState(
                roleHeld = false,
                roleAvailable = true,
                reasonCode = AssistantReasonCode.REQUEST_FAILED,
                fallbackFailed = true,
            ),
        )
        composeRule.onNodeWithTag("assistantReason")
            .performScrollTo().assertIsDisplayed()
            .assertTextEquals("無法開啟系統授權頁，請改用語音輸入設定。")
        composeRule.onNodeWithTag("assistantFallbackFailed")
            .performScrollTo().assertIsDisplayed()
            .assertTextEquals("無法開啟語音輸入設定")
    }

    // ---- re-review #5 finding #2 [P3/UX] 方案 A：UNCONFIRMED 獨立重查 ----

    /**
     * UNCONFIRMED＋Known !held＋available：主按鈕「重新檢查」enabled＋點擊走
     * onRecheck（只重讀不開授權頁）；原因行下方小字「重新要求授權」走 onRequest。
     * 生產管線同源計算 reason（Known(!held)＋stored UNCONFIRMED passthrough）。
     */
    @Test
    fun unconfirmedKnown_showsRecheckMainAndRerequestSecondary() {
        var rechecks = 0
        var requests = 0
        val reason = effectiveAssistantReason(
            AssistantReasonCode.UNCONFIRMED,
            AssistantSnapshot.Known(available = true, held = false),
            everHeld = false,
            pendingVerify = false,
        )
        assertEquals(AssistantReasonCode.UNCONFIRMED, reason)
        setSettings(
            AssistantSettingsState(
                roleHeld = false,
                roleAvailable = true,
                reasonCode = reason,
            ),
            onRequest = { requests++ },
            onRecheck = { rechecks++ },
        )
        composeRule.onNodeWithTag("assistantReason")
            .performScrollTo().assertIsDisplayed()
            .assertTextEquals(assistantReasonText(AssistantReasonCode.UNCONFIRMED))
        composeRule.onNodeWithText("重新檢查").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText("重新檢查").performScrollTo().performClick()
        assertEquals(1, rechecks)
        assertEquals(0, requests)
        composeRule.onNodeWithTag("assistantRerequest").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("重新要求授權").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("重新要求授權").performScrollTo().performClick()
        assertEquals(1, requests)
        assertEquals(1, rechecks)
    }

    /** UNCONFIRMED＋Known !held＋available：verifying 期間主副按鈕皆 disabled（含重新檢查）。 */
    @Test
    fun unconfirmedKnownVerifying_disablesBothActions() {
        setSettings(
            AssistantSettingsState(
                roleHeld = false,
                roleAvailable = true,
                reasonCode = AssistantReasonCode.UNCONFIRMED,
                verifying = true,
            ),
        )
        composeRule.onNodeWithText("重新檢查").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("assistantRerequest").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("重新要求授權").performScrollTo().assertIsDisplayed()
        // TextButton disabled 斷言：沿用主按鈕語義（verifying 阻擋，與 #2 防重入一致）。
        composeRule.onNodeWithText("重新要求授權").assertIsNotEnabled()
    }

    /** 非 UNCONFIRMED 的 Known 可要求態：無次按鈕，主按鈕仍是「設為預設助理」走 onRequest。 */
    @Test
    fun knownIdle_hasNoRerequestSecondary() {
        var rechecks = 0
        var requests = 0
        setSettings(
            AssistantSettingsState(roleHeld = false, roleAvailable = true),
            onRequest = { requests++ },
            onRecheck = { rechecks++ },
        )
        composeRule.onNodeWithText("設為預設助理").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag("assistantRerequest").assertDoesNotExist()
        composeRule.onNodeWithText("設為預設助理").performScrollTo().performClick()
        assertEquals(1, requests)
        assertEquals(0, rechecks)
    }

    /**
     * re-review #6 finding #3 [P3/UX]：held 清理後期望終態渲染——held＋
     * `fallbackFailed=false` 時無紅字，只留 held 頭部（鎖定清理後的期望終態）。
     *
     * 接線缺口誠實註記：Card 層 `state` 靜態灌入，測不出 `SideEffect` held 清除
     * 接線（`state` 可任意構造，`held=true＋fallbackFailed=true` 亦可渲染紅字——
     * 那是 SettingsScreen 照 `state` 顯示，非管線保證）；接線由純函數
     * [reconcileFallbackFailedOnHeldObserved]＋AssistantRoleTest 組合序列鎖定，
     * 真機 Lifecycle＋重組交錯未在此覆蓋（與既有缺口註記一致）。
     */
    @Test
    fun heldWithFallbackCleared_hidesErrorRow() {
        setSettings(
            AssistantSettingsState(
                roleHeld = true,
                roleAvailable = true,
                fallbackFailed = false,
            ),
        )
        composeRule.onNodeWithText("已設為預設助理：長按首頁鍵或系統手勢可叫出 LibrePocket。")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("assistantFallbackFailed").assertDoesNotExist()
    }
}
