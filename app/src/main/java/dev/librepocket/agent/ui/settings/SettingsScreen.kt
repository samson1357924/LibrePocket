package dev.librepocket.agent.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.librepocket.agent.ui.setup.EndpointGate
import dev.librepocket.agent.ui.setup.setupErrorText
import dev.librepocket.automation.AutomationSettingsState
import dev.librepocket.voice.VoiceTools
import dev.librepocket.voice.VoiceTts

@Composable
fun SettingsScreen(
    padding: PaddingValues,
    gate: EndpointGate = EndpointGate.Loading,
    onEditEndpoint: () -> Unit = {},
    onLogout: () -> Unit = {},
    /** S3 提權橋一鍵收回（呼叫方清 [PrivilegeAuditLog] + 關 `privilege_bridge` 開關）。 */
    onRevokePrivilege: () -> Unit = {},
    /** 提權審計筆數（僅計數，不含明文；0 表示無待收回授權痕跡）。 */
    privilegeAuditCount: Int = 0,
    /**
     * S3 無障礙自動化設定（P1 inert 收斂，PR#1 re-review）：
     * null（play 或未接線）時整卡隱藏；非 null（foss/github flavor wiring 傳入）
     * 才顯示開關 + 系統授權狀態 + 跳轉。本 PR flavor 持久化/確認 Dialog 尚未落地
     *（SCAFFOLD），先以契約就緒 + sticky-true 已修為邊界。
     */
    automation: AutomationSettingsState? = null,
    onAutomationSwitch: (Boolean) -> Unit = {},
    onOpenSystemA11y: () -> Unit = {},
    /**
     * #3 語音開關（DataStore 持久化，單一真相源見 `VoiceSwitchStore`）：
     * 預設皆開；關閉即隱藏聊天頁對應入口（mic 鈕／朗讀鈕，與投影層同 key）。
     * 形狀仿無障礙自動化開關（checked + onCheckedChange 回調）。
     * R4-2 三態：[voiceSwitchesEnabled] 為 `loaded && !loadFailed`（loading／
     * 失敗態禁用開關，避免在未知真相源上寫入）；[voiceLoadFailed] 時另顯示
     * 一行讀取問題提示。
     */
    voiceInputEnabled: Boolean = VoiceTools.SWITCH_INPUT_DEFAULT,
    voiceOutputEnabled: Boolean = VoiceTools.SWITCH_OUTPUT_DEFAULT,
    voiceSwitchesEnabled: Boolean = true,
    voiceLoadFailed: Boolean = false,
    onVoiceInputSwitch: (Boolean) -> Unit = {},
    onVoiceOutputSwitch: (Boolean) -> Unit = {},
    /**
     * 登出失敗碼（僅 `LOGOUT_*` 顯示；呼叫方由 form.errorCode 過濾傳入）。
     * 非 null 即顯示失敗卡；失敗不自動關 dialog（見 [showConfirmLogout]）。
     */
    logoutErrorCode: String? = null,
    /** 登出執行中：確認/重試/取消三鍵皆 disabled，確認/重試鍵轉 spinner 防重點。 */
    logoutInFlight: Boolean = false,
    /** 關閉失敗卡（取消語義：不調 logout）。 */
    onDismissLogoutError: () -> Unit = {},
    /** 重試登出；預設複用 [onLogout]。 */
    onRetryLogout: () -> Unit = onLogout,
) {
    var confirmLogout by remember { mutableStateOf(false) }
    // 失敗不自動關 dialog：error 存續即重現確認態（confirm 已手動關閉又回來也一樣）。
    val showConfirmLogout = confirmLogout || logoutErrorCode != null
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("設定", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(12.dp))
        when (val g = gate) {
            is EndpointGate.Ready -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(g.config.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            g.config.baseUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (g.config.model.isNotBlank()) {
                            Text(
                                "模型：${g.config.model}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "金鑰：●●●● 已設定",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onEditEndpoint, modifier = Modifier.fillMaxWidth()) {
                    Text("編輯端點")
                }
                Spacer(modifier = Modifier.height(12.dp))
                // S2 語音揭露（明示）：系統 STT/TTS 優先、音檔不落地、送雲前再 redact。
                // TODO(S2-voice, M3)：Azure key/region 設定入口 + 朗讀鏈路接線尚未落地。
                // 落地形狀：key 進 KeyVault providerId "azure_speech"（僅 github 版讀寫，
                // 見 AzureSpeechEngine.KEY_REF），region 為非密鑰字串另存設定；
                // 朗讀時雙開關（voice_output 且 azure_tts）皆開且有 key 才走
                // AzureSpeechEngine.synthesizeToSpeaker，否則維持系統 TTS。
                // 在此之前 AzureSpeechEngine 無產品呼叫者（僅門禁/投影可見）。
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("語音", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "語音輸入走系統辨識、語音輸出走系統朗讀，不新增任何權限；" +
                                "麥克風音檔只駐留記憶體、不落地存檔；辨識正文寫入紀錄前會先遮罩敏感內容；" +
                                "系統朗讀由" + VoiceTts.ENGINE_DISCLOSURE + "，離線能力因引擎而異。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "雲端語音（Azure）僅直接下載版可用，需同時開啟「語音輸出」與「Azure 語音」" +
                                "（後者預設關閉）；送雲前會再次遮罩敏感內容。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // R4-2 三態提示（沿用既有文字樣式；失敗態開關不可撥，
                        // 避免在未知真相源上寫入）。
                        if (voiceLoadFailed) {
                            Text(
                                "語音設定讀取失敗，開關暫時停用。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        } else if (!voiceSwitchesEnabled) {
                            Text(
                                "語音設定讀取中…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        // #3 語音開關（仿無障礙開關模式）：關閉即隱藏聊天頁對應入口。
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "語音輸入",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = voiceInputEnabled,
                                onCheckedChange = onVoiceInputSwitch,
                                enabled = voiceSwitchesEnabled,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "語音輸出",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = voiceOutputEnabled,
                                onCheckedChange = onVoiceOutputSwitch,
                                enabled = voiceSwitchesEnabled,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                // S3 提權橋揭露 + 一鍵收回（矩陣 §3：每次跨權限邊界呼叫攜原因碼寫審計，
                // 審計只記雜湊計數；此處一鍵清表並關閉提權橋開關）。
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("提權橋（Shizuku / Root，可選）", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "僅直接下載版可用，預設關閉；開啟後檔案跨域、提權子進程、截圖備選路徑" +
                                "才會經系統授權的橋接執行，每次呼叫都記一筆審計（只記雜湊與計數，不記原文）。" +
                                "目前審計筆數：$privilegeAuditCount",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onRevokePrivilege,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("一鍵收回提權授權並清空審計")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                // S3 無障礙自動化開關（P1 inert 收斂）：僅 flavor 傳入 automation 非 null
                // 才顯示（play 永遠 null 即隱藏）。開關預設關，系統授權需至系統設定手動開啟，
                // 當輪敏感動作另需二次確認（per-round Dialog，flavor 接線 PR 落地）。
                if (automation != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("螢幕自動化（無障礙，可選）", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "僅直接下載版可用，預設關閉；開啟後仍需至系統設定手動授予無障礙權限，" +
                                    "支付/刪除/發送類動作每次執行前需當輪二次確認。目前系統授權：" +
                                    (if (automation.serviceGranted) "已授予" else "未授予") +
                                    "；是否生效：" + (if (automation.effective) "已武裝" else "未武裝") + "。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Switch(
                                checked = automation.switchOn,
                                onCheckedChange = onAutomationSwitch,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onOpenSystemA11y,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("前往系統無障礙設定")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (!showConfirmLogout) {
                    OutlinedButton(
                        onClick = { confirmLogout = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("登出（清除金鑰）")
                    }
                } else {
                    Text(
                        "確定清除本機金鑰與端點設定？此動作無法復原。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // 登出失敗卡：文案複用 Setup 共享映射；CLEAR_FAILED 說明部分
                    // 狀態（金鑰已刪、設定仍在→重試）。gate NoEndpoint 原因重構
                    // 不在此做（follow-up：區分 CLEAR_FAILED 導致的 NO_KEY）。
                    logoutErrorCode?.let { code ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = setupErrorText(code),
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    // 確認即重試同一動作：失敗不關 dialog，原文案轉「重試清除」。
                    Button(
                        onClick = onRetryLogout,
                        enabled = !logoutInFlight,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (logoutInFlight) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.padding(4.dp))
                            Text("清除中…")
                        } else {
                            Text(if (logoutErrorCode == null) "確定清除" else "重試清除")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // 取消語義：只關 dialog + 清失敗碼，絕不調 logout。
                    OutlinedButton(
                        onClick = {
                            confirmLogout = false
                            onDismissLogoutError()
                        },
                        enabled = !logoutInFlight,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("取消")
                    }
                }
            }
            EndpointGate.NoEndpoint -> {
                // NoEndpoint 恢復入口（Re-review #8 Finding 2 P2/BLOCK 階段2；
                // Stage 3 P2：DELETE 失敗與中性 UNKNOWN 同為可重試碼）：
                // 部分清除（LOGOUT_CLEAR_FAILED：金鑰已刪、設定仍在）gate 已折疊為
                // NoEndpoint；若只顯示「尚未設定」會丟失重試入口。有未恢復 LOGOUT_*
                // 時改顯示 setupErrorText + 重試（可重試碼）/ STALE 僅關閉 + 取消；
                // inFlight 時按鍵 disabled + spinner。無碼時保留原尚未設定文案。
                // 恢復態隱藏「前往設定」（dismiss 回 fresh 後再出現），避免在元數據
                // 仍殘留時進 Setup 預填陳舊值；重試/關閉才是此態的恢復入口。
                val recoveryCode = logoutErrorCode?.takeIf { it.startsWith("LOGOUT_") }
                if (recoveryCode == null) {
                    Text(
                        "尚未設定 API 端點。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onEditEndpoint,
                        enabled = !logoutInFlight,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("前往設定")
                    }
                } else {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = setupErrorText(recoveryCode),
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    val isStale = recoveryCode == "LOGOUT_STALE"
                    if (!isStale) {
                        Button(
                            onClick = onRetryLogout,
                            enabled = !logoutInFlight,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (logoutInFlight) {
                                CircularProgressIndicator(strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.padding(4.dp))
                                Text("清除中…")
                            } else {
                                Text("重試清除")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onDismissLogoutError,
                            enabled = !logoutInFlight,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("取消")
                        }
                    } else {
                        // STALE：已有新的金鑰生效，不需重試，僅關閉待 dismiss
                        //（導航守衛亦攔截，見 shouldNavigateToSetup）。
                        OutlinedButton(
                            onClick = onDismissLogoutError,
                            enabled = !logoutInFlight,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("關閉")
                        }
                    }
                }
            }
            EndpointGate.Loading -> {
                Text(
                    "讀取設定中…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
