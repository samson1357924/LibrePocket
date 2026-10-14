package dev.librepocket.agent.ui.setup

/**
 * 共享錯誤文案（SetupScreen 與 SettingsScreen 共用）。
 *
 * 由 SetupScreen 私有 `errorText` 抽出，原映射逐字保留；新增 LOGOUT_* 六碼
 * （登出失敗 Settings UI 可見，Re-review #7 Finding 2；Stage 3 P2 拆分；
 * Stage D1 新增 `LOGOUT_READ_RETRY` 中性碼）：
 * - `LOGOUT_READ_FAILED`：讀取失敗，未動任何狀態（未刪、未清），可重試。
 * - `LOGOUT_DELETE_FAILED`：刪金鑰失敗，端點設定未清（金鑰仍可能在 vault，
 *   禁寫「已刪除」），可重試。
 * - `LOGOUT_CLEAR_FAILED`：部分清除——僅在 delete 已證勝（clear 拋+probe 同
 *   rev 仍見元數據+同 rev 重試仍失敗）時使用：金鑰已刪但端點設定仍在（gate
 *   讀 NO_KEY），重試以完成清除。未證勝的 delete 失敗一律用
 *   `LOGOUT_DELETE_FAILED`，未確認的用 `LOGOUT_UNKNOWN`。
 * - `LOGOUT_UNKNOWN`：未能確認清除結果（strict 重讀失敗或 journal 毀損，零
 *   vault I/O）：殘留待清理，可重試；文案禁寫「已刪除」。Stage D1 起僅用於
 *   clear-phase `UNKNOWN`（攜 journal）與 logout 路徑；read-path `UNKNOWN`
 *  （journal=null）改用 `LOGOUT_READ_RETRY`。
 * - `LOGOUT_READ_RETRY`（Stage D1）：啟動時讀取清除狀態失敗，未做任何變更，
 *   可重試讀取。僅用於啟動 replay read-path `UNKNOWN`（journal=null）：
 *   零 vault I/O、未刪未清；文案禁寫「已刪除」／「殘留待清理」。重試為非破壞性
 *   reconcile-only（`retryStartupReconcile`），絕不調 `logout()`。
 * - `LOGOUT_STALE`：已有新的金鑰生效（併發 save 贏了 CAS），本次登出已過期，
 *   不需重試。
 * - `SAVE_BLOCKED_BY_TEARDOWN`：有待清理的登出狀態擋下本次儲存（未寫入任何
 *   資料，可重試；重試前由 reconcile 解決該登出狀態）。
 * - `PROBE_POLICY_DENIED`（Stage S2）：provider.call 非 ALLOW（ASK／DENY／讀失敗），
 *   零 provider 構造、零 listModels（refreshModels 亦零目錄抓取），可重試（請檢查權限設定後重試）。
 * - `SETUP_KEY_IDENTITY_CHANGED`（Stage S3）：已貼金鑰綁定的 endpoint 身份
 *   （providerId＋effectiveBaseUrl，trim 後全字串相等、case-sensitive：host
 *   大小寫、尾端斜線、有無預設埠皆視為不同身份，不做其他正規化）與目前表單不符
 *   （貼上後切換了 preset／位址）：probe 零請求、save 零 vault 寫、不 mint consent。
 *   同字串重貼（含僅加空白、清空後重貼同一字串）因 `sha256(trimmed)` 不變永不重綁；
 *   須貼上不同金鑰觸發重綁（後再貼回亦可）或切回原伺服器（hash 語義見
 *   `SetupViewModel.boundKeyHash` S4-B 註解）。
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
    "PROBE_POLICY_DENIED" -> "政策未允許連線探測（provider.call 非 ALLOW），未發出任何請求，請檢查權限設定後重試。"
    "SETUP_KEY_IDENTITY_CHANGED" -> "金鑰與目前伺服器不符（貼上後切換了供應商／位址），未發出任何請求／寫入；同字串重貼（含僅加空白、清空後重貼同一字串）不會解除，請貼上不同金鑰觸發重綁（後再貼回亦可）或切回原伺服器。"
    "LOGOUT_READ_FAILED" -> "讀取端點設定失敗，未做任何變更，請重試。"
    "LOGOUT_DELETE_FAILED" -> "刪除本機金鑰失敗，端點設定未清除，請重試。"
    "LOGOUT_CLEAR_FAILED" -> "部分清除：本機金鑰已刪除、但端點設定仍在，請重試以完成清除。"
    "LOGOUT_UNKNOWN" -> "未能確認清除結果，殘留待清理，請重試。"
    "LOGOUT_READ_RETRY" -> "啟動時讀取清除狀態失敗，未做任何變更，請重試讀取。"
    "LOGOUT_STALE" -> "已有新的金鑰生效，本次登出已過期，不需重試。"
    "SAVE_BLOCKED_BY_TEARDOWN" -> "有待清理的登出狀態，請重試。"
    else -> "儲存失敗（$code），請重試。"
}
