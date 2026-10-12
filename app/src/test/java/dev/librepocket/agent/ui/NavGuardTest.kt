package dev.librepocket.agent.ui

import dev.librepocket.agent.ui.setup.PendingDelete
import dev.librepocket.agent.ui.setup.PendingTeardownState
import dev.librepocket.agent.ui.setup.SetupViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 導航守衛真值表（Re-review #8 Finding 2 P2/BLOCK 階段2）：
 * `hasEndpoint × loading × error × inFlight → shouldNavigate`。
 *
 * 另含 nav 回歸：SETTINGS 發起登出失敗（`LOGOUT_CLEAR_FAILED`，gate 已折疊
 * NoEndpoint）仍留 SETTINGS；STALE 待 dismiss 亦攔截。
 */
class NavGuardTest {

    @Test
    fun loadingNeverNavigates() {
        assertEquals(false, shouldNavigateToSetup(false, true, Routes.CHAT, null, false))
        assertEquals(false, shouldNavigateToSetup(false, true, Routes.CHAT, "LOGOUT_CLEAR_FAILED", false))
        assertEquals(false, shouldNavigateToSetup(false, true, Routes.SETTINGS, null, true))
    }

    @Test
    fun hasEndpointNeverNavigates() {
        assertEquals(false, shouldNavigateToSetup(true, false, Routes.CHAT, null, false))
        assertEquals(false, shouldNavigateToSetup(true, false, Routes.SETTINGS, "LOGOUT_CLEAR_FAILED", false))
        assertEquals(false, shouldNavigateToSetup(true, false, Routes.SETUP, null, false))
    }

    @Test
    fun nullOrSetupRouteNeverNavigates() {
        assertEquals(false, shouldNavigateToSetup(false, false, null, null, false))
        assertEquals(false, shouldNavigateToSetup(false, false, Routes.SETUP, null, false))
        assertEquals(false, shouldNavigateToSetup(false, false, Routes.SETUP, "LOGOUT_CLEAR_FAILED", false))
    }

    @Test
    fun freshNoEndpointNavigatesFromChatAndSettings() {
        assertEquals(true, shouldNavigateToSetup(false, false, Routes.CHAT, null, false))
        assertEquals(true, shouldNavigateToSetup(false, false, Routes.SETTINGS, null, false))
    }

    @Test
    fun logoutErrorBlocksNavigateFromAnyRoute() {
        for (code in listOf("LOGOUT_READ_FAILED", "LOGOUT_DELETE_FAILED", "LOGOUT_CLEAR_FAILED")) {
            assertEquals("code=$code CHAT", false, shouldNavigateToSetup(false, false, Routes.CHAT, code, false))
            assertEquals("code=$code SETTINGS", false, shouldNavigateToSetup(false, false, Routes.SETTINGS, code, false))
        }
    }

    @Test
    fun staleBlocksNavigatePendingDismiss() {
        // STALE 亦攔截：待 dismiss 前不強制跳，否則「新的金鑰」文案不可達。
        assertEquals(false, shouldNavigateToSetup(false, false, Routes.SETTINGS, "LOGOUT_STALE", false))
        assertEquals(false, shouldNavigateToSetup(false, false, Routes.CHAT, "LOGOUT_STALE", false))
    }

    @Test
    fun logoutInFlightBlocksNavigate() {
        assertEquals(false, shouldNavigateToSetup(false, false, Routes.CHAT, null, true))
        assertEquals(false, shouldNavigateToSetup(false, false, Routes.SETTINGS, null, true))
        assertEquals(false, shouldNavigateToSetup(false, false, Routes.SETTINGS, "LOGOUT_CLEAR_FAILED", true))
    }

    @Test
    fun nonLogoutErrorDoesNotBlock() {
        // save 驗證碼等非 LOGOUT_* 不攔截（呼叫方已過濾，此為防禦性語義）。
        assertEquals(true, shouldNavigateToSetup(false, false, Routes.CHAT, "SETUP_KEY_TOO_SHORT", false))
        assertEquals(true, shouldNavigateToSetup(false, false, Routes.SETTINGS, "POLICY_DENIED_KEY_WRITE", false))
        assertEquals(true, shouldNavigateToSetup(false, false, Routes.CHAT, null, false))
    }

    @Test
    fun settingsStaysOnLogoutFailureRegression() {
        // nav 回歸：SETTINGS 發起登出、失敗折疊為 NoEndpoint 後仍留 SETTINGS 可恢復，
        // 而非被強制踢往 SETUP。
        assertEquals(
            false,
            shouldNavigateToSetup(
                hasEndpoint = false,
                gateLoading = false,
                currentRoute = Routes.SETTINGS,
                logoutErrorCode = "LOGOUT_CLEAR_FAILED",
                logoutInFlight = false,
            ),
        )
        // 執行中（inFlight、尚無碼）同樣不跳。
        assertEquals(
            false,
            shouldNavigateToSetup(false, false, Routes.SETTINGS, null, true),
        )
        // dismiss 後（無碼、非 inFlight）恢復強制跳轉。
        assertEquals(
            true,
            shouldNavigateToSetup(false, false, Routes.SETTINGS, null, false),
        )
    }

    /**
     * Stage E 四態派生碼行（R13 Finding P2/BLOCK）：
     * - 無碼＋KnownPending＋非 inFlight → `LOGOUT_UNKNOWN`（重試清除），與既有
     *   `LOGOUT_*` 一致走 SETTINGS、互斥不走 SETUP；
     * - 無碼＋UnknownReadFailure／MalformedPending＋非 inFlight →
     *   `LOGOUT_READ_RETRY`（D1 碼複用，重試讀取 only），同走 SETTINGS；
     * - 在途抑制派生（`null` 走 fresh SETUP）；Absent 常態 null；
     * - 有碼沿用（STALE 不被派生覆蓋，不論四態）。
     */
    @Test
    fun derivedUnknownBlocksSetupAndSteersToSettings() {
        val journal = PendingDelete("preset:openai", 1L, 1700000000001L)
        val known = PendingTeardownState.KnownPending(journal)
        val derived = effectiveLogoutGuardError(null, known, logoutInFlight = false)
        assertEquals("LOGOUT_UNKNOWN", derived)
        assertEquals(false, shouldNavigateToSetup(false, false, Routes.CHAT, derived, false))
        assertEquals(false, shouldNavigateToSetup(false, false, Routes.SETTINGS, derived, false))
        assertEquals(true, shouldNavigateToSettings(false, false, Routes.CHAT, derived, false))
        assertEquals(false, shouldNavigateToSettings(false, false, Routes.SETTINGS, derived, false))
        // 未確認態：中性 READ_RETRY（D1 碼同一常數），同走 SETTINGS、不走 SETUP.
        val retryUnknown = effectiveLogoutGuardError(
            null,
            PendingTeardownState.UnknownReadFailure,
            logoutInFlight = false,
        )
        assertEquals(SetupViewModel.LOGOUT_READ_RETRY, retryUnknown)
        assertEquals(false, shouldNavigateToSetup(false, false, Routes.CHAT, retryUnknown, false))
        assertEquals(true, shouldNavigateToSettings(false, false, Routes.CHAT, retryUnknown, false))
        val retryMalformed = effectiveLogoutGuardError(
            null,
            PendingTeardownState.MalformedPending,
            logoutInFlight = false,
        )
        assertEquals(SetupViewModel.LOGOUT_READ_RETRY, retryMalformed)
        assertEquals(false, shouldNavigateToSetup(false, false, Routes.CHAT, retryMalformed, false))
        assertEquals(true, shouldNavigateToSettings(false, false, Routes.CHAT, retryMalformed, false))
        // Absent 常態 null（fresh 走 SETUP）.
        assertNull(
            effectiveLogoutGuardError(null, PendingTeardownState.Absent, logoutInFlight = false),
        )
        // 在途抑制：有效 null＋inFlight → 不決策（兩守衛皆 false）。
        val suppressed = effectiveLogoutGuardError(null, known, logoutInFlight = true)
        assertEquals(null, suppressed)
        assertEquals(false, shouldNavigateToSetup(false, false, Routes.CHAT, suppressed, true))
        assertEquals(false, shouldNavigateToSettings(false, false, Routes.CHAT, suppressed, true))
        assertNull(
            effectiveLogoutGuardError(
                null,
                PendingTeardownState.UnknownReadFailure,
                logoutInFlight = true,
            ),
        )
        // 有碼沿用（STALE 不被派生覆蓋，不論四態）。
        assertEquals(
            "LOGOUT_STALE",
            effectiveLogoutGuardError("LOGOUT_STALE", known, logoutInFlight = false),
        )
        assertEquals(
            "LOGOUT_STALE",
            effectiveLogoutGuardError(
                "LOGOUT_STALE",
                PendingTeardownState.UnknownReadFailure,
                logoutInFlight = false,
            ),
        )
    }
}
