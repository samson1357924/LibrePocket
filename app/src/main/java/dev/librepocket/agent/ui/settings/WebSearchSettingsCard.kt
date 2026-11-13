package dev.librepocket.agent.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.librepocket.web.WebProbe
import dev.librepocket.web.WebSearchSettings
import dev.librepocket.web.WebUnavailableReason

/**
 * D3 網路搜尋設定狀態（Settings→Tools→Web；null 時整卡隱藏，仿 automation 模式）。
 *
 * - [masterEnabled]：`websearch` 總開關（預設關）；
 * - [ddgsOptIn]：DDGS opt-in（預設關；開啟即顯示非官方警語）；
 * - [searxngUrl]／[searxngProbe]／[ddgsProbe]：provider 狀態
 *   （可用／缺 URL／缺 key，見 [WebSearchSettings.statusText]）；
 * - [probeMessage]：`probe()` 測試結果（null＝尚未測試）；
 * - [showConsent]：首次開啟 consent sheet 是否顯示；
 * - [switchesEnabled]：讀取中／讀取失敗時禁用開關（fail-closed，不在未知真相源上寫入）；
 * - [urlEditable]：SearXNG URL 輸入框是否可編輯（讀取中／讀取失敗／同意提交中皆禁用）；
 * - [isLoading]：是否處於冷啟動讀取或重試讀取中；
 * - [loadFailed]：讀取失敗（需顯示重試讀取提示）；
 * - [writeError]：最近一次儲存／同意／重設失敗或輸入含不支援字元之錯誤訊息（null＝無錯誤）；
 * - [consentSubmitting]：首次同意提交中（禁用重複點擊與關閉）；
 * - [canReset]：目前是否存在可重設的非預設設定或草稿。
 */
data class WebSearchSettingsState(
    val masterEnabled: Boolean,
    val ddgsOptIn: Boolean,
    val searxngUrl: String = "",
    val searxngProbe: WebProbe = WebProbe.NotReady(WebUnavailableReason.MISSING_URL),
    val ddgsProbe: WebProbe = WebProbe.Ready,
    val probeMessage: String? = null,
    val showConsent: Boolean = false,
    val switchesEnabled: Boolean = true,
    val urlEditable: Boolean = true,
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val writeError: String? = null,
    val consentSubmitting: Boolean = false,
    val canReset: Boolean = false,
)

@Composable
fun WebSearchCard(
    state: WebSearchSettingsState,
    onMasterSwitch: (Boolean) -> Unit = {},
    onDdgsSwitch: (Boolean) -> Unit = {},
    onSearxngUrlChange: (String) -> Unit = {},
    onProbe: () -> Unit = {},
    onConsentConfirm: () -> Unit = {},
    onConsentDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("webSearchCard"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("網路搜尋（本地，可選）", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "關閉時不發任何搜尋請求；開啟需先完成同意。查詢會經網路送往設定的搜尋後端，" +
                    "依後端條款處理；本機快取僅保留 15 分鐘，取消不寫快取。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "網路搜尋",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.masterEnabled,
                    onCheckedChange = onMasterSwitch,
                    enabled = state.switchesEnabled,
                    modifier = Modifier.testTag("webMasterSwitch"),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            // SearXNG（預設後端，需自架 URL）。
            Text(
                "SearXNG（預設後端，需自架）",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = state.searxngUrl,
                onValueChange = onSearxngUrlChange,
                enabled = state.urlEditable && state.switchesEnabled,
                label = { Text("SearXNG baseUrl（例 https://search.example）") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("webSearxngUrl"),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "狀態：${WebSearchSettings.statusText(WebSearchSettings.statusFor(state.searxngProbe))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("webSearxngStatus"),
            )
            Spacer(modifier = Modifier.height(8.dp))
            // DDGS（opt-in＋非官方警語，D3 必須顯示）。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "DDGS（備援，需 opt-in）",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.ddgsOptIn,
                    onCheckedChange = onDdgsSwitch,
                    enabled = state.switchesEnabled,
                    modifier = Modifier.testTag("webDdgsSwitch"),
                )
            }
            Text(
                WebSearchSettings.DDGS_UNOFFICIAL_WARNING,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("webDdgsWarning"),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "狀態：${WebSearchSettings.statusText(WebSearchSettings.statusFor(state.ddgsProbe))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("webDdgsStatus"),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onProbe,
                enabled = state.switchesEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("webProbeButton"),
            ) {
                Text("測試連線（probe）")
            }
            state.probeMessage?.let { msg ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("webProbeMessage"),
                )
            }
        }
    }
    if (state.showConsent) {
        AlertDialog(
            onDismissRequest = { if (!state.consentSubmitting) onConsentDismiss() },
            properties = DialogProperties(
                dismissOnBackPress = !state.consentSubmitting,
                dismissOnClickOutside = !state.consentSubmitting,
            ),
            title = {
                Text(
                    WebSearchSettings.CONSENT_TITLE,
                    modifier = Modifier.testTag("webConsentTitle"),
                )
            },
            text = {
                Column {
                    Text(
                        WebSearchSettings.CONSENT_TEXT,
                        modifier = Modifier.testTag("webConsentText"),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        WebSearchSettings.DDGS_UNOFFICIAL_WARNING,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("webConsentDdgsWarning"),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onConsentConfirm,
                    enabled = !state.consentSubmitting && state.switchesEnabled,
                    modifier = Modifier.testTag("webConsentConfirm"),
                ) {
                    Text("同意並啟用")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onConsentDismiss,
                    enabled = !state.consentSubmitting,
                    modifier = Modifier.testTag("webConsentDismiss"),
                ) {
                    Text("取消")
                }
            },
        )
    }
}
