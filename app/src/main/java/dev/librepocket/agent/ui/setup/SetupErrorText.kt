package dev.librepocket.agent.ui.setup

/**
 * 共享錯誤文案（SetupScreen 與 SettingsScreen 共用）。
 *
 * 由 SetupScreen 私有 `errorText` 抽出，原映射逐字保留；新增 LOGOUT_* 四碼
 * （登出失敗 Settings UI 可見，Re-review #7 Finding 2）：
 * - `LOGOUT_READ_FAILED`：讀取失敗，未動任何狀態（未刪、未清），可重試。
 * - `LOGOUT_DELETE_FAILED`：刪金鑰失敗，端點設定未清，可重試。
 * - `LOGOUT_CLEAR_FAILED`：部分清除——金鑰已刪但端點設定仍在（gate 讀 NO_KEY），
 *   重試以完成清除。
 * - `LOGOUT_STALE`：已有新的金鑰生效（併發 save 贏了 CAS），本次登出已過期，
 *   不需重試。
 * - `SAVE_BLOCKED_BY_TEARDOWN`：有待清理的登出狀態擋下本次儲存（未寫入任何
 *   資料，可重試；重試前由 reconcile 解決該登出狀態）。
 */
fun setupErrorText(code: String): String = when (code) {
    "SETUP_UNKNOWN_PRESET" -> "未知的供應商，請重新選擇。"
    "SETUP_BASE_URL_BLANK" -> "請填寫伺服器位址。"
    "PROVIDER_BAD_URL" -> "位址格式不正確。"
    "PROVIDER_URL_MUST_BE_HTTPS" -> "必須使用 https（本機/內網測試位址除外）。"
    "SETUP_KEY_TOO_SHORT" -> "金鑰太短（至少 8 字元），請檢查後重貼。"
    "PROVIDER_KEY_REF_BLANK", "PROVIDER_KEY_REF_MALFORMED" -> "內部參照異常，請重試。"
    "API key too short" -> "金鑰太短（至少 8 字元），請檢查後重貼。"
    "TEST_UNAUTHORIZED" -> "金鑰無效（認證失敗），請檢查後重貼。"
    "TEST_TOO_LARGE" -> "模型列表過大，未能完整載入，請重試。"
    "TEST_RETRYABLE" -> "伺服器忙碌或網路不穩，請稍後重試。"
    "POLICY_DENIED_KEY_WRITE" -> "政策拒絕寫入金鑰（key.write DENY），請檢查權限設定。"
    "LOGOUT_READ_FAILED" -> "讀取端點設定失敗，未做任何變更，請重試。"
    "LOGOUT_DELETE_FAILED" -> "刪除本機金鑰失敗，端點設定未清除，請重試。"
    "LOGOUT_CLEAR_FAILED" -> "部分清除：本機金鑰已刪除、但端點設定仍在，請重試以完成清除。"
    "LOGOUT_STALE" -> "已有新的金鑰生效，本次登出已過期，不需重試。"
    "SAVE_BLOCKED_BY_TEARDOWN" -> "有待清理的登出狀態，請重試。"
    else -> "儲存失敗（$code），請重試。"
}
