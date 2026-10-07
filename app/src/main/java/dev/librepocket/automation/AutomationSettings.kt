package dev.librepocket.automation

/**
 * 無障礙自動化設定頁中立契約（S3，P1 inert 收斂，PR#1 re-review）。
 *
 * - main 不直接引用 `src/github` / `src/foss` 的 Service/State（play 物理缺失，
 *   play_policy 黑名單不可破），只定義此中立狀態 + 回調；
 * - foss/github 各實作一層薄 wiring（DataStore 讀寫 `AutomationPolicy.SWITCH_KEY`
 *   並同步 `XxxA11yState.switchOn`，系統授權經 AccessibilityManager 真相源顯示，
 *   確認對話框經 SlowRouter.WAITING_CONFIRM → grantConfirmation）；
 * - `state == null`（play 或未接線）時設定頁整卡隱藏；
 * - 本 PR 內 flavor DataStore 持久化 + per-round 確認 Dialog + StepExecutor 綁定
 *   尚未落地（SCAFFOLD），先修 sticky-true + 契約就緒，投影仍以
 *   `automationEnabled=false` 為預設（見 CapabilityProjection）。
 */
data class AutomationSettingsState(
    val switchOn: Boolean,
    val serviceGranted: Boolean,
    val effective: Boolean,
)
