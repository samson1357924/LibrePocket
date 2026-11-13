package dev.librepocket.agent.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import dev.librepocket.agent.ui.chat.ExportUiState
import dev.librepocket.agent.ui.chat.ImportUiState
import dev.librepocket.agent.ui.setup.EndpointGate
import dev.librepocket.agent.ui.setup.setupErrorText
import dev.librepocket.agent.assistant.AssistantReasonCode
import dev.librepocket.agent.assistant.AssistantSettingsState
import dev.librepocket.agent.assistant.assistantReasonText
import dev.librepocket.automation.AutomationSettingsState
import dev.librepocket.voice.VoiceTools
import dev.librepocket.voice.VoiceTts

data class TranscriptPickers(
    val exportPickerPending: Boolean = false,
    val exportPickerFailed: Boolean = false,
    val importPickerPending: Boolean = false,
    val importPickerFailed: Boolean = false,
    val launchExport: (String?) -> Unit = {},
    val launchImport: () -> Unit = {},
)

/** Lives above navigation/loading so pending document results retain their original target. */
@Composable
fun rememberTranscriptPickers(
    onExportCurrentSession: (String, Uri) -> Unit,
    onImportSession: (Uri) -> Unit,
    onDismissExportState: () -> Unit,
    onDismissImportState: () -> Unit,
): TranscriptPickers {
    var pendingExportSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var exportPickerFailed by rememberSaveable { mutableStateOf(false) }
    var importPickerFailed by rememberSaveable { mutableStateOf(false) }
    var importPickerPending by rememberSaveable { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/jsonl"),
    ) { uri ->
        val sessionId = pendingExportSessionId
        pendingExportSessionId = null
        if (uri != null && sessionId != null) onExportCurrentSession(sessionId, uri)
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        importPickerPending = false
        if (uri != null) onImportSession(uri)
    }
    val launchExport: (String?) -> Unit = { sessionId ->
        sessionId?.let { sessionId ->
            onDismissExportState()
            pendingExportSessionId = sessionId
            exportPickerFailed = false
            try {
                exportLauncher.launch("librepocket_${sessionId.take(8)}.jsonl")
            } catch (e: Exception) {
                pendingExportSessionId = null
                exportPickerFailed = true
            }
        }
    }
    val launchImport: () -> Unit = {
        if (!importPickerPending) {
            onDismissImportState()
            importPickerFailed = false
            importPickerPending = true
            try {
                importLauncher.launch(arrayOf("application/jsonl", "application/json", "text/*", "*/*"))
            } catch (e: Exception) {
                importPickerPending = false
                importPickerFailed = true
            }
        }
    }
    return TranscriptPickers(
        pendingExportSessionId != null, exportPickerFailed, importPickerPending, importPickerFailed,
        launchExport, launchImport,
    )
}

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
     * 三鍵預設：`voice_input` 開、`voice_output` 開、`openai_tts` 關（SCAFFOLD，
     * 目前尚未接線，切換僅持久化開關值、無任何作用）；
     * 關閉即隱藏聊天頁對應入口（mic 鈕／朗讀鈕，與投影層同 key）。
     * 形狀仿無障礙自動化開關（checked + onCheckedChange 回調）。
     * R4-2 三態：[voiceSwitchesEnabled] 為 `loaded && !loadFailed`（loading／
     * 失敗態禁用開關，避免在未知真相源上寫入）；[voiceLoadFailed] 時另顯示
     * 一行讀取問題提示。
     */
    voiceInputEnabled: Boolean = VoiceTools.SWITCH_INPUT_DEFAULT,
    voiceOutputEnabled: Boolean = VoiceTools.SWITCH_OUTPUT_DEFAULT,
    openAiTtsEnabled: Boolean = VoiceTools.SWITCH_OPENAI_DEFAULT,
    voiceSwitchesEnabled: Boolean = true,
    voiceLoadFailed: Boolean = false,
    onVoiceInputSwitch: (Boolean) -> Unit = {},
    onVoiceOutputSwitch: (Boolean) -> Unit = {},
    onOpenAiTtsSwitch: (Boolean) -> Unit = {},
    /**
     * D3 網路搜尋（Settings→Tools→Web；null 時整卡隱藏，仿 automation 模式）。
     * master 預設關；provider 狀態由 `probe()` 映射（可用／缺 key／缺 URL）；
     * 首次開啟走 consent sheet（`showConsent`）；DDGS opt-in＋非官方警語見卡內。
     */
    web: WebSearchSettingsState? = null,
    onWebMasterSwitch: (Boolean) -> Unit = {},
    onWebDdgsSwitch: (Boolean) -> Unit = {},
    onWebSearxngUrlChange: (String) -> Unit = {},
    onWebProbe: () -> Unit = {},
    onWebConsentConfirm: () -> Unit = {},
    onWebConsentDismiss: () -> Unit = {},
    onWebRetryLoad: () -> Unit = {},
    onWebResetSettings: () -> Unit = {},
    /**
     * C1 系統預設助理（Issue #137 C1b，三 flavor 共用 main 接線）：
     * null（未接線）時整卡隱藏（仿 automation 模式）；非 null 顯示狀態＋
     * 「設為預設助理」一鍵（RoleManager 流程）＋「語音輸入設定」fallback。
     * C1 不新增麥克風／浮窗／截圖權限。
     */
    assistant: AssistantSettingsState? = null,
    onRequestAssistantRole: () -> Unit = {},
    /**
     * 獨立重讀入口（re-review #5 finding #2 [P3/UX] 方案 A）：
     * ReadError 與 Known(!held)＋UNCONFIRMED 的主按鈕「重新檢查」皆走此回調
     * （生產接 `assistantRole.recheck`，只重讀不清碼不開頁）；預設 {} 保舊構造相容。
     */
    onRecheckAssistantRole: () -> Unit = {},
    onOpenVoiceInputSettings: () -> Unit = {},
    /**
     * 登出失敗碼（僅 `LOGOUT_*` 顯示；呼叫方由 form.errorCode 過濾傳入）。
     * 非 null 即顯示失敗卡；失敗不自動關 dialog（見 [showConfirmLogout]）。
     *
     * Stage A（R10 Finding 1 P2/BLOCK，方案 B 立即止血）：retained 碼
     * （`LOGOUT_READ_FAILED`／`LOGOUT_DELETE_FAILED`／`LOGOUT_CLEAR_FAILED`／
     * `LOGOUT_UNKNOWN`，journal 可能保留）不可 dismiss——journal 釋放前不給出口，
     * 同次執行無其他入口，故不暗示可稍後處理；僅 `LOGOUT_STALE`（journal 已丟棄，
     * save 不被擋）可關閉。
     *
     * Stage D1：`LOGOUT_READ_RETRY`（啟動 read-path `UNKNOWN`+null 中性碼）不屬
     * retained——Ready/NoEndpoint 兩分支另開分支，僅顯示 [重試讀取]（無取消／無
     * 重試清除／無關閉；enabled 綁 [startupReconcilePending]，非 [logoutInFlight]）。
     */
    logoutErrorCode: String? = null,
    /**
     * Stage G F1 已派生重試目標身份（`MainScreen` 由 `effectiveLogoutRecovery`
     * 同源計算的 `retryJournal.format()`；null 表無特定目標）。
     *
     * 僅作重試 targeting 顯示（retained 重試清除鍵上方小字）；提交仍經
     * `retryPendingDelete` 快照比對（禁盲 replay），此欄不作清除依據。
     */
    logoutRetryJournal: String? = null,
    /** 登出執行中：可見按鍵皆 disabled，確認/重試鍵轉 spinner 防重點。 */
    logoutInFlight: Boolean = false,
    /**
     * 關閉失敗卡（不調 logout）。
     *
     * 僅初始確認（無碼）與 `LOGOUT_STALE` 可達；retained 碼與 `LOGOUT_READ_RETRY`
     * 皆無 dismiss 入口。
     */
    onDismissLogoutError: () -> Unit = {},
    /** 重試登出；預設複用 [onLogout]。 */
    onRetryLogout: () -> Unit = onLogout,
    /**
     * Stage D1 非破壞性重試讀取入口（中性碼 `LOGOUT_READ_RETRY` 專用）。
     *
     * 呼叫方（MainScreen）接 `SetupViewModel.retryStartupReconcile`（reconcile-only，
     * 絕不調 logout）；按鍵 enabled 綁 [startupReconcilePending]（pending 中
     * disabled＋spinner），與 [logoutInFlight] 無關。
     */
    onRetryStartupRead: () -> Unit = {},
    /** 啟動 replay 落定閘：中性碼 [重試讀取] 鍵的 enabled／spinner 來源。 */
    startupReconcilePending: Boolean = false,
    /** 當前開啟之對話 ID（為空時停用匯出）。 */
    currentSessionId: String? = null,
    exportState: ExportUiState = ExportUiState.Idle,
    importState: ImportUiState = ImportUiState.Idle,
    pickers: TranscriptPickers = TranscriptPickers(),
    onViewTranscript: (String) -> Unit = {},
    onDismissExportState: () -> Unit = {},
    onDismissImportState: () -> Unit = {},
) {
    var confirmLogout by remember { mutableStateOf(false) }
    val launchExport = { pickers.launchExport(currentSessionId) }
    val launchImport = pickers.launchImport
    val exportPickerFailed = pickers.exportPickerFailed
    val importPickerFailed = pickers.importPickerFailed
    val importPickerPending = pickers.importPickerPending
    // 失敗不自動關 dialog：error 存續即重現確認態（confirm 已手動關閉又回來也一樣）。
    val showConfirmLogout = confirmLogout || logoutErrorCode != null
    val effectiveWeb = if (logoutInFlight) {
        web?.copy(switchesEnabled = false, urlEditable = false)
    } else {
        web
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .imePadding()
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
                                "麥克風音檔只駐留記憶體，app 層不寫入檔案系統（零 File 引用）；辨識正文寫入紀錄前會先經 best-effort 已知樣式遮罩（非匿名化）處理；" +
                                "系統朗讀由" + VoiceTts.ENGINE_DISCLOSURE + "，離線能力因引擎而異。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "雲端語音（Azure）僅直接下載版可用，需同時開啟「語音輸出」與「Azure 語音」" +
                                "（後者預設關閉）；送雲前經 best-effort 已知樣式遮罩，非匿名化；送雲文本將依雲端條款處理。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "雲端語音（OpenAI）支援所有版本，需同時開啟「語音輸出」與「OpenAI 語音」" +
                                "（後者預設關閉，目前尚未接線，切換無作用）；" +
                                "未來需經端點設定（KeyVault providerId \"openai_tts\"）設定 OpenAI API 金鑰（目前無設定入口）；" +
                                VoiceTools.OPENAI_VOICE_DISCLOSURE + "；" +
                                "送雲前經 best-effort 已知樣式遮罩，非匿名化；朗讀文本將送 OpenAI 依其條款處理；使用 OpenAI 語音會依其定價產生費用" +
                                "（SCAFFOLD：管線接線尚在收斂，未接線前一律使用系統語音）。",
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "OpenAI 語音（尚未接線，目前無作用）",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = openAiTtsEnabled,
                                onCheckedChange = onOpenAiTtsSwitch,
                                enabled = voiceSwitchesEnabled && voiceOutputEnabled,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                // D3 網路搜尋（Settings→Tools→Web）：僅傳入 web 非 null 才顯示
                //（master 預設關、`executionReady=false` 不可執行；provider 狀態＋probe＋首次開啟 consent＋DDGS 警語見卡內）。
                if (effectiveWeb != null) {
                    WebSearchCard(
                        state = effectiveWeb,
                        onMasterSwitch = onWebMasterSwitch,
                        onDdgsSwitch = onWebDdgsSwitch,
                        onSearxngUrlChange = onWebSearxngUrlChange,
                        onProbe = onWebProbe,
                        onConsentConfirm = onWebConsentConfirm,
                        onConsentDismiss = onWebConsentDismiss,
                        onRetryLoad = onWebRetryLoad,
                        onResetSettings = onWebResetSettings,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                // C1 系統預設助理（Issue #137 C1b）：僅 main 接線傳入非 null 才顯示
                // （三 flavor 共用；play 不加 a11y overlay，本卡與 a11y 無關）。
                // 被拒／撤銷／OEM 不支援／請求失敗皆顯示原因並提供 fallback 入口。
                if (assistant != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("預設助理（系統助手）", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                when {
                                    // Stage 2 finding C + re-review #4 #1：讀取失敗與真不支援區分。
                                    // ReadError 不宣稱任何狀態（roleHeld／roleAvailable 皆 false，
                                    // 見 AssistantSettingsState.readError），頭部誠實顯示未知；
                                    // 主按鈕改為「重新檢查」可重讀快照（fail-closed：不發要求，見 request）。
                                    assistant.readError -> "無法確認助理狀態，請稍後重試。"
                                    assistant.roleHeld -> "已設為預設助理：長按首頁鍵或系統手勢可叫出 LibrePocket。"
                                    !assistant.roleAvailable -> "此裝置不支援系統助理角色，可改用語音輸入設定。"
                                    else -> "尚未設為預設助理；授予後長按首頁鍵或系統手勢可叫出 LibrePocket。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            assistant.reasonCode?.let { code ->
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    assistantReasonText(code),
                                    style = MaterialTheme.typography.bodySmall,
                                    // A 原因碼色彩：被拒／撤銷／請求失敗／未確認走 error
                                    // （UNCONFIRMED 需使用者處理：重新檢查，
                                    // re-review #4 finding #1 [P2/Merge blocker]）；
                                    // 不支援中性（頭部已是不支援文案，原因段不追加紅字）；
                                    // GRANTED 正常不顯示（held=>null 已清除），若顯示亦中性。
                                    color = when (code) {
                                        AssistantReasonCode.DENIED,
                                        AssistantReasonCode.REVOKED,
                                        AssistantReasonCode.REQUEST_FAILED,
                                        AssistantReasonCode.UNCONFIRMED ->
                                            MaterialTheme.colorScheme.error
                                        // VERIFYING 明確中性：驗證中不宣稱成功／失敗
                                        // （re-review #3 finding #1；else 同中性，
                                        // 列出以鎖定語義）。
                                        AssistantReasonCode.VERIFYING ->
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.testTag("assistantReason"),
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            // Stage 2 review #3：不支援分支可達——!available 時主按鈕保持
                            // enabled（僅 held 才 disabled），文案改為「前往語音輸入設定」；
                            // 點擊仍走 onRequestAssistantRole → request() UnsupportedFallback
                            // 分支（設 UNSUPPORTED＋openFallback），與下方 OutlinedButton 同效。
                            // Stage 2 finding C＋re-review #4 finding #1 [P2/Merge blocker]：
                            // ReadError 時主按鈕保持 enabled，文案「重新檢查」：點擊走
                            // onRecheckAssistantRole → recheck()（只 refreshTick++ 觸發重讀，
                            // fail-closed 仍成立——不發要求；request() ReadError 分支
                            // 直接委託 recheck()，同通道）。
                            // re-review #5 finding #2 [P3/UX] 方案 A：Known(!held)＋available＋
                            // UNCONFIRMED（RESULT_OK 後重試耗盡，文案承諾「可重新檢查」）主按鈕
                            // 亦為「重新檢查」走 onRecheckAssistantRole（只重讀不清碼不開頁；
                            // 若重讀 held→Stage 1 清除＋everHeld 生效自動修復，仍 !held→
                            // UNCONFIRMED 保留），另在下方加小字「重新要求授權」走
                            // onRequestAssistantRole（明確重授權入口）。Unknown（ReadError／
                            // Unsupported 超時）UNCONFIRMED 走 readError／不支援分支，不另設碼。
                            // held 才 disabled；未知態不暗示可跳 fallback，
                            // OutlinedButton 仍可達。
                            // re-review #4 finding #2 [P3/UX]：驗證中（state.verifying，
                            // 含 VERIFYING 顯示幀及未知/held 重疊幀）主按鈕 disabled
                            // （含重新檢查，與 #2 防重入一致）；request() 入口另有
                            // canStartRequest 防禦性 no-op（第二層；recheck 不開授權頁，
                            // pending 守衛不擋 recheck，見 AssistantRoleUi.recheck KDoc）。
                            val unconfirmedRecheck = !assistant.readError &&
                                !assistant.roleHeld &&
                                assistant.roleAvailable &&
                                assistant.reasonCode == AssistantReasonCode.UNCONFIRMED
                            val isRecheck = assistant.readError || unconfirmedRecheck
                            Button(
                                onClick = if (isRecheck) onRecheckAssistantRole else onRequestAssistantRole,
                                enabled = !assistant.roleHeld && !assistant.verifying,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    when {
                                        isRecheck -> "重新檢查"
                                        assistant.roleHeld -> "設為預設助理"
                                        !assistant.roleAvailable -> "前往語音輸入設定"
                                        else -> "設為預設助理"
                                    },
                                )
                            }
                            // re-review #5 finding #2 方案 A 明確重授權入口：僅 UNCONFIRMED＋
                            // Known !held＋available 時顯示（ReadError 的重讀入口已是主按鈕，
                            // 不支援分支主按鈕已是 fallback，同效不另加）。verifying 期間
                            // disabled（與主按鈕一致，防重入）。
                            if (unconfirmedRecheck) {
                                TextButton(
                                    onClick = onRequestAssistantRole,
                                    enabled = !assistant.verifying,
                                    modifier = Modifier.fillMaxWidth().testTag("assistantRerequest"),
                                ) {
                                    Text("重新要求授權")
                                }
                            }
                            // B held 指引：取消須走系統設定（RoleManager 無應用側取消入口）。
                            if (assistant.roleHeld) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "如需取消，請至系統設定 → 預設應用程式",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("assistantCancelHint"),
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onOpenVoiceInputSettings,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("語音輸入設定")
                            }
                            // C fallback 失敗回饋：設定頁打不開時紅字提示（不跳轉）。
                            if (assistant.fallbackFailed) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "無法開啟語音輸入設定",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag("assistantFallbackFailed"),
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
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
                Spacer(modifier = Modifier.height(8.dp))
                TranscriptBackupCard(
                    currentSessionId = currentSessionId,
                    exportState = exportState,
                    importState = importState,
                    onLaunchExport = launchExport,
                    onLaunchImport = launchImport,
                    exportPickerPending = pickers.exportPickerPending,
                    importPickerPending = importPickerPending,
                    exportPickerFailed = exportPickerFailed,
                    importPickerFailed = importPickerFailed,
                    onViewTranscript = onViewTranscript,
                    onDismissExportState = onDismissExportState,
                    onDismissImportState = onDismissImportState,
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (!showConfirmLogout) {
                    OutlinedButton(
                        onClick = { confirmLogout = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("登出（清除金鑰）")
                    }
                } else {
                    // Stage D1：中性碼 `LOGOUT_READ_RETRY` 另開分支——非破壞性重試讀取，
                    // 不顯示破壞性確認文案，僅 [重試讀取]（無取消／無重試清除／無關閉；
                    // enabled 綁 startupReconcilePending，非 logoutInFlight）。
                    val isReadRetryReady = logoutErrorCode == "LOGOUT_READ_RETRY"
                    if (isReadRetryReady) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = setupErrorText("LOGOUT_READ_RETRY"),
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onRetryStartupRead,
                            enabled = !startupReconcilePending,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (startupReconcilePending) {
                                CircularProgressIndicator(strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.padding(4.dp))
                                Text("讀取中…")
                            } else {
                                Text("重試讀取")
                            }
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
                        // Stage A（R10 Finding 1 P2/BLOCK，方案 B）：retained 碼
                        // （STALE 以外全部 LOGOUT_*，Stage D1 起不含 READ_RETRY）
                        // 有身份（journal）時 journal 釋放前不給出口，只留重試；
                        // Stage R5：無身份時另給關閉（純 state 清除）；
                        // 僅 STALE（journal 已丟棄）留關閉；無碼初始確認才有取消。
                        // 結構性 follow-up（方案 A：journal domain state 驅動）另行處理。
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
                        val isStaleReadyError = logoutErrorCode == "LOGOUT_STALE"
                        // Stage D1：READ_RETRY 已另分支，此處 retained 明確排除它
                        // （防禦性：未來合併分支時亦不誤判為重試清除）。
                        val isRetainedReadyError = logoutErrorCode != null &&
                            !isStaleReadyError &&
                            logoutErrorCode != "LOGOUT_READ_RETRY"
                        // STALE 不需重試，僅關閉；retained 只留重試（無取消，不暗示稍後處理，
                        // 同次執行無其他入口）；無碼初始確認維持確定清除 + 取消。
                        if (!isStaleReadyError) {
                            // Stage G F1：重試 targeting 顯示身份（已派生 `logoutRetryJournal`；
                            // 提交仍快照比對，禁盲 replay）。fresh（無碼）無目標顯示。
                            if (isRetainedReadyError && logoutRetryJournal != null) {
                                Text(
                                    "重試目標：$logoutRetryJournal",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            // fresh 確定清除走 onLogout（全新 logout），retained 重試清除
                            // 走 onRetryLogout（Stage G 起帶顯示身份同源回放；Absent 抑制
                            // 回 fresh 時走 onLogout 不變）。
                            Button(
                                onClick = if (logoutErrorCode == null) onLogout else onRetryLogout,
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
                            // Stage R5：retained 但無身份（logoutRetryJournal==null）
                            // 時另給關閉（純 state 清除回 fresh 重確認；真 journal 由
                            // observation 重現；null 身份永不 fresh destructive）。
                            if (isRetainedReadyError && logoutRetryJournal == null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = onDismissLogoutError,
                                    enabled = !logoutInFlight,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("關閉")
                                }
                            }
                            if (!isRetainedReadyError) {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                        if (!isRetainedReadyError) {
                            // 取消/關閉語義：只關 dialog + 清失敗碼，絕不調 logout。
                            // retained 碼與 READ_RETRY 皆無此鍵（journal 釋放前不給出口；
                            // READ_RETRY 另分支本就無此鍵）。
                            OutlinedButton(
                                onClick = {
                                    confirmLogout = false
                                    onDismissLogoutError()
                                },
                                enabled = !logoutInFlight,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (isStaleReadyError) "關閉" else "取消")
                            }
                        }
                    }
                }
            }
            EndpointGate.NoEndpoint -> {
                // NoEndpoint 恢復入口（Re-review #8 Finding 2 P2/BLOCK 階段2；
                // Stage 3 P2：DELETE 失敗與中性 UNKNOWN 同為可重試碼；
                // Stage A R10 Finding 1 P2/BLOCK 方案 B 立即止血；
                // Stage D1：read-path UNKNOWN+null 改走中性 READ_RETRY（僅重試讀取），
                // retained UNKNOWN 僅指 clear-phase UNKNOWN+J（重試清除））：
                // 部分清除（LOGOUT_CLEAR_FAILED：金鑰已刪、設定仍在）gate 已折疊為
                // NoEndpoint；若只顯示「尚未設定」會丟失重試入口。有未恢復 LOGOUT_*
                // 時改顯示 setupErrorText + 重試（有身份 retained 碼僅重試；無身份另給關閉：
                // journal 釋放前不給出口，同次執行無其他入口，故不暗示稍後處理）/
                // STALE 僅關閉（journal 已丟棄，save 不被擋）；
                // inFlight 時可見按鍵 disabled + spinner。無碼時保留原尚未設定文案。
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
                    Spacer(modifier = Modifier.height(12.dp))
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
                    val isReadRetry = recoveryCode == "LOGOUT_READ_RETRY"
                    // Stage E：與 Ready 分支同步——retained 明確排除 READ_RETRY
                    // （經上游 if/else 已不可達，防禦性：未來合併分支時亦不誤判
                    // 為重試清除；Ready 分支 `isRetainedReadyError` 同式）。
                    val isRetainedNoEndpointError = !isStale &&
                        recoveryCode != "LOGOUT_READ_RETRY"
                    if (isReadRetry) {
                        // Stage D1 中性碼：非破壞性重試讀取，僅 [重試讀取]（無取消／無
                        // 重試清除／無關閉；enabled 綁 startupReconcilePending）。
                        Button(
                            onClick = onRetryStartupRead,
                            enabled = !startupReconcilePending,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (startupReconcilePending) {
                                CircularProgressIndicator(strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.padding(4.dp))
                                Text("讀取中…")
                            } else {
                                Text("重試讀取")
                            }
                        }
                    } else if (isRetainedNoEndpointError) {
                        // retained 碼（READ_FAILED/DELETE/CLEAR/UNKNOWN）：journal 可能保留。
                        // Stage G F1：重試 targeting 顯示身份（同 Ready 分支；提交仍快照比對）。
                        // Stage R5：無身份（logoutRetryJournal==null）時另給 [關閉]：
                        // 無 journal 可回放，重試只走 reconcile-only（下次 NO_OP 即
                        // 收斂回 fresh）；關閉為純 state 清除（clearLogoutError），
                        // 真 journal 由 observation 獨立重現，不會被關閉掩蓋；且
                        // null 身份永不觸發 fresh destructive logout（路由守衛見
                        // MainScreen／retryPendingDelete）。
                        if (logoutRetryJournal != null) {
                            Text(
                                "重試目標：$logoutRetryJournal",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
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
                        if (logoutRetryJournal == null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onDismissLogoutError,
                                enabled = !logoutInFlight,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("關閉")
                            }
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
                    Spacer(modifier = Modifier.height(12.dp))
                }
                if (effectiveWeb != null) {
                    WebSearchCard(
                        state = effectiveWeb,
                        onMasterSwitch = onWebMasterSwitch,
                        onDdgsSwitch = onWebDdgsSwitch,
                        onSearxngUrlChange = onWebSearxngUrlChange,
                        onProbe = onWebProbe,
                        onConsentConfirm = onWebConsentConfirm,
                        onConsentDismiss = onWebConsentDismiss,
                        onRetryLoad = onWebRetryLoad,
                        onResetSettings = onWebResetSettings,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (recoveryCode == null) {
                    TranscriptBackupCard(
                        currentSessionId = currentSessionId,
                        exportState = exportState,
                        importState = importState,
                        onLaunchExport = launchExport,
                        onLaunchImport = launchImport,
                        exportPickerPending = pickers.exportPickerPending,
                        importPickerPending = importPickerPending,
                        exportPickerFailed = exportPickerFailed,
                        importPickerFailed = importPickerFailed,
                        onViewTranscript = onViewTranscript,
                        onDismissExportState = onDismissExportState,
                        onDismissImportState = onDismissImportState,
                    )
                }
            }
            EndpointGate.Loading -> {
                Text(
                    "讀取設定中…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            EndpointGate.ReadFailure -> {
                // Stage R2: strict/vault read unconfirmed — never show the
                // NoEndpoint empty state (which would mislead to Setup).
                // Retry-read only; the next gateTick rebuild re-resolves.
                Text(
                    "設定讀取失敗，請重試讀取。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onRetryStartupRead,
                    enabled = !startupReconcilePending,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (startupReconcilePending) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.padding(4.dp))
                        Text("讀取中…")
                    } else {
                        Text("重試讀取")
                    }
                }
                if (effectiveWeb != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    WebSearchCard(
                        state = effectiveWeb,
                        onMasterSwitch = onWebMasterSwitch,
                        onDdgsSwitch = onWebDdgsSwitch,
                        onSearxngUrlChange = onWebSearxngUrlChange,
                        onProbe = onWebProbe,
                        onConsentConfirm = onWebConsentConfirm,
                        onConsentDismiss = onWebConsentDismiss,
                        onRetryLoad = onWebRetryLoad,
                        onResetSettings = onWebResetSettings,
                    )
                }
            }
        }
    }
}

@Composable
internal fun TranscriptBackupCard(
    currentSessionId: String?,
    exportState: ExportUiState,
    importState: ImportUiState,
    onLaunchExport: () -> Unit,
    onLaunchImport: () -> Unit,
    exportPickerPending: Boolean,
    importPickerPending: Boolean,
    exportPickerFailed: Boolean,
    importPickerFailed: Boolean,
    onViewTranscript: (String) -> Unit,
    onDismissExportState: () -> Unit = {},
    onDismissImportState: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("對話備份與匯出 (JSONL)", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "匯出會嘗試脫敏，但無法保證移除所有敏感資訊；分享前請自行檢查檔案。匯入建立本機副本，不驗證來源或真實性；可唯讀檢視，開啟對話可能建立不含歷史的新副本。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "儲存目的地由文件提供者管理；匯出失敗或取消時，目的地可能留下空檔或部分內容，請自行檢查並刪除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 匯出按鈕
            OutlinedButton(
                onClick = onLaunchExport,
                enabled = currentSessionId != null && !exportPickerPending && exportState !is ExportUiState.Exporting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (exportState is ExportUiState.Exporting) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("匯出中…")
                } else {
                    Text("匯出目前對話")
                }
            }
            if (exportPickerFailed) {
                Text("無法開啟儲存選擇器，請重試。", color = MaterialTheme.colorScheme.error)
            }
            if (currentSessionId == null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "（目前無開啟中的對話，請先開啟或建立對話）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (exportState) {
                is ExportUiState.Success -> {
                    Spacer(modifier = Modifier.height(6.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "對話已成功匯出！",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = onDismissExportState) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "關閉提示",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                }
                is ExportUiState.Error -> {
                    Spacer(modifier = Modifier.height(6.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "匯出失敗：${exportState.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = onDismissExportState) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "關閉提示",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }
                else -> {}
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 匯入按鈕
            OutlinedButton(
                onClick = onLaunchImport,
                enabled = !importPickerPending && importState !is ImportUiState.Importing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (importState is ImportUiState.Importing) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("匯入中…")
                } else {
                    Text("匯入對話檔")
                }
            }
            if (importPickerFailed) {
                Text("無法開啟檔案選擇器，請重試。", color = MaterialTheme.colorScheme.error)
            }
            when (importState) {
                is ImportUiState.Success -> {
                    Spacer(modifier = Modifier.height(6.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "對話已成功匯入！",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = onDismissImportState) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "關閉提示",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Button(
                                onClick = { onViewTranscript(importState.sessionId) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("唯讀檢視匯入內容")
                            }
                        }
                    }
                }
                is ImportUiState.Error -> {
                    Spacer(modifier = Modifier.height(6.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "匯入失敗：${importState.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = onDismissImportState) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "關閉提示",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }
                else -> {}
            }
        }
    }
}
