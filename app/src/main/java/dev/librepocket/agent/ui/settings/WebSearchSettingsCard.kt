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
import dev.librepocket.web.WebProbe
import dev.librepocket.web.WebSearchSettings

/**
 * D3 網路搜尋設定狀態（Settings→Tools→Web；null 時整卡隱藏，仿 automation 模式）。
 *
 * - [masterEnabled]：`websearch` 總開關（預設關）；
 * - [ddgsOptIn]：DDGS opt-in（預設關；開啟即顯示非官方警語）；
 * - [searxngUrl]／[searxngProbe]／[ddgsProbe]：provider 狀態
 *   （可用／缺 URL／缺 key，見 [WebSearchSettings.statusText]）；
 * - [probeMessage]：`probe()` 測試結果（null＝尚未測試）；
 * - [showConsent]：首次開啟 consent sheet 是否顯示；
 * - [switchesEnabled]：讀取中／讀取失敗時禁用開關（fail-closed，不在未知真相源上寫入）。
 */
data class WebSearchSettingsState(
    val masterEnabled: Boolean,
    val ddgsOptIn: Boolean,
    val searxngUrl: String = "",
    val searxngProbe: WebProbe = WebProbe.NotReady(dev.librepocket.web.WebUnavailableReason.MISSING_URL),
    val ddgsProbe: WebProbe = WebProbe.Ready,
    val probeMessage: String? = null,
    val showConsent: Boolean = false,
    val switchesEnabled: Boolean = true,
    val urlEditable: Boolean = true,
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
            onDismissRequest = onConsentDismiss,
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
                    modifier = Modifier.testTag("webConsentConfirm"),
                ) {
                    Text("同意並啟用")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onConsentDismiss,
                    modifier = Modifier.testTag("webConsentDismiss"),
                ) {
                    Text("取消")
                }
            },
        )
    }
}
