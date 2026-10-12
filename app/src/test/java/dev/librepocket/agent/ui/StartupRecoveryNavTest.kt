package dev.librepocket.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stage 3 P2 可達性決策 (a)：啟動 replay 失敗（`LOGOUT_*` + `NoEndpoint`）時
 * CHAT 自動導向 SETTINGS 恢復卡，而非留在無端點 CHAT 空態。
 *
 * - [shouldNavigateToSetup] 語義不變（兩者互斥：有碼走 SETTINGS，無碼走 SETUP）；
 * - [shouldNavigateToSettings] 僅在 CHAT 生效（已在 SETTINGS/SETUP 不重導，
 *   `null` 初始幀不決策；loading/inFlight 不決策）。
 */
class StartupRecoveryNavTest {

    @Test
    fun deleteAndUnknownSteerFromChatToSettings() {
        for (code in listOf("LOGOUT_DELETE_FAILED", "LOGOUT_UNKNOWN", "LOGOUT_CLEAR_FAILED", "LOGOUT_READ_FAILED")) {
            assertEquals(
                "code=$code",
                true,
                shouldNavigateToSettings(false, false, Routes.CHAT, code, false),
            )
            // 同態絕不走 SETUP 分支（互斥）。
            assertEquals(
                "code=$code setup",
                false,
                shouldNavigateToSetup(false, false, Routes.CHAT, code, false),
            )
        }
    }

    @Test
    fun staleSteersFromChatToDismiss() {
        assertEquals(true, shouldNavigateToSettings(false, false, Routes.CHAT, "LOGOUT_STALE", false))
        assertEquals(false, shouldNavigateToSetup(false, false, Routes.CHAT, "LOGOUT_STALE", false))
    }

    @Test
    fun alreadyInSettingsOrSetupDoesNotResteer() {
        assertEquals(false, shouldNavigateToSettings(false, false, Routes.SETTINGS, "LOGOUT_DELETE_FAILED", false))
        assertEquals(false, shouldNavigateToSettings(false, false, Routes.SETUP, "LOGOUT_DELETE_FAILED", false))
        assertEquals(false, shouldNavigateToSettings(false, false, null, "LOGOUT_DELETE_FAILED", false))
    }

    @Test
    fun freshOrBusyNeverSteersToSettings() {
        // 無碼走 SETUP 分支（fresh 語義不變）。
        assertEquals(false, shouldNavigateToSettings(false, false, Routes.CHAT, null, false))
        assertEquals(true, shouldNavigateToSetup(false, false, Routes.CHAT, null, false))
        // 非 LOGOUT_* 碼不導向。
        assertEquals(false, shouldNavigateToSettings(false, false, Routes.CHAT, "SETUP_KEY_TOO_SHORT", false))
        // 有端點 / loading / inFlight 不決策。
        assertEquals(false, shouldNavigateToSettings(true, false, Routes.CHAT, "LOGOUT_DELETE_FAILED", false))
        assertEquals(false, shouldNavigateToSettings(false, true, Routes.CHAT, "LOGOUT_DELETE_FAILED", false))
        assertEquals(false, shouldNavigateToSettings(false, false, Routes.CHAT, "LOGOUT_DELETE_FAILED", true))
        assertEquals(false, shouldNavigateToSettings(false, false, Routes.CHAT, null, true))
    }
}
