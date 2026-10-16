package dev.librepocket.agent.ui.setup

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.librepocket.models.ModelsDevSnapshot.SnapshotFallbackReason
import dev.librepocket.preset.ProviderCatalog

private const val THREAT_MODEL_URL =
    "https://github.com/samson1357924/LibrePocket/blob/main/docs/THREAT_MODEL.md"

private fun modelDirectoryStatusText(status: ModelDirectoryStatus): String? = when (status) {
    ModelDirectoryStatus.NotLoaded -> null
    ModelDirectoryStatus.Remote -> "模型目錄來源：models.dev（已更新）。"
    is ModelDirectoryStatus.Bundled -> {
        val reason = when (status.reason) {
            SnapshotFallbackReason.INVALID_URL -> "位址無效"
            SnapshotFallbackReason.HTTP_STATUS -> "伺服器回應錯誤"
            SnapshotFallbackReason.WIRE_BODY_TOO_LARGE -> "傳輸內容超過大小限制"
            SnapshotFallbackReason.DECODED_BODY_TOO_LARGE -> "解碼內容超過大小限制"
            SnapshotFallbackReason.TRUNCATED_BODY -> "回應不完整"
            SnapshotFallbackReason.UNSUPPORTED_CONTENT_ENCODING -> "壓縮格式不支援"
            SnapshotFallbackReason.EMPTY_BODY -> "回應為空"
            SnapshotFallbackReason.NETWORK_ERROR -> "無法連線"
            SnapshotFallbackReason.TIMEOUT -> "連線逾時"
            SnapshotFallbackReason.INVALID_JSON -> "資料格式無效"
            SnapshotFallbackReason.INVALID_SHAPE -> "資料結構無效"
            SnapshotFallbackReason.EMPTY_DIRECTORY -> "沒有可用模型"
        }
        "模型目錄來源：內建備援（$reason）。"
    }
}

private fun errorText(code: String): String = setupErrorText(code)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    padding: PaddingValues,
    isFirstRun: Boolean,
    onSaved: () -> Unit,
    viewModel: SetupViewModel,
) {
    val state by viewModel.form.collectAsStateWithLifecycle()
    // collectAsState (not WithLifecycle): mirrors every test harness and the
    // MainScreen->host wiring in this repo; the gate/pending snapshot only
    // drives the retry card and never leaves this composition.
    val gate by viewModel.gate.collectAsState()
    val startupPending by viewModel.startupReconcilePending.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    // Key-entry page: block screenshots / recents thumbnails while visible.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    // First-run gate: no back escape (chat cannot work without an endpoint).
    BackHandler(enabled = isFirstRun) { }

    // Legacy migration prefill: a quarantined pre-Stage1 custom row never
    // gates Ready, so without this the form would show defaults and lose
    // the saved address. One-shot + pristine-only inside the ViewModel: the
    // key stays blank (re-paste required), user input is never clobbered.
    LaunchedEffect(isFirstRun) {
        if (isFirstRun) viewModel.prefillLegacyMigrationIfNeeded()
    }
    // Retryable migration status: a transient read failure leaves the probe
    // RetryLater on a still-mounted first-run screen, where LaunchedEffect
    // will not refire. Surface an explicit retry action instead (the prefill
    // still re-verifies pristine/revision, so a stale tap cannot clobber).
    val migrationRetryNeeded by viewModel.migrationRetryNeeded.collectAsStateWithLifecycle()
    // Same pristine definition as the ViewModel gate: only offer retry while
    // the form is still untouched.
    val migrationFormPristine = state.presetId == ProviderCatalog.OPENAI_ID &&
        state.baseUrl == ProviderCatalog.OPENAI_BASE_URL &&
        state.model == ProviderCatalog.defaultModelFor(ProviderCatalog.OPENAI_ID) &&
        state.apiKey.isEmpty()
    val screenScope = rememberCoroutineScope()

    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.consumeSaved()
            onSaved()
        }
    }

    var presetExpanded by remember { mutableStateOf(false) }
    val presets = remember { ProviderCatalog.PRESETS }
    val selectedPreset = remember(state.presetId) {
        ProviderCatalog.preset(state.presetId) ?: presets.first()
    }
    val baseUrlEditable = state.presetId == ProviderCatalog.CUSTOM_ID

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (isFirstRun) "連接你的 AI" else "編輯端點",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = if (isFirstRun) {
                "LibrePocket 只用你自己的金鑰（BYOK），不經手任何帳號。選供應商、貼上 API Key 即可開始聊天。"
            } else {
                "切換供應商或更新金鑰。儲存後立即生效。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (isFirstRun && migrationRetryNeeded && migrationFormPristine) {
            OutlinedButton(
                onClick = { screenScope.launch { viewModel.prefillLegacyMigrationIfNeeded() } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("重新載入舊版設定")
            }
        }

        // Stage F2 (#18)：gate 讀取未確認時在 SETUP 內提供非破壞性重試入口。
        // gate-first 發射序／transient 失敗後使用者可能靜止在此頁（舊守衛不導、
        // Back 被攔、menu disabled），重試走同一安全 reconcile（只重讀＋回放，
        // 不碰表單草稿、不調 logout）；在途時 disabled＋spinner。
        if (gate is EndpointGate.ReadFailure) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "設定讀取失敗，請重試讀取。輸入草稿會保留。",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = {
                    viewModel.retryStartupReconcile { o, j -> viewModel.onStartupReconcile(o, j) }
                },
                enabled = !startupPending,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("setupRetryRead"),
            ) {
                if (startupPending) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.padding(4.dp))
                    Text("讀取中…")
                } else {
                    Text("重試讀取")
                }
            }
        }

        ExposedDropdownMenuBox(
            expanded = presetExpanded,
            onExpandedChange = { if (!state.saving && !state.confirmKeyWrite && !state.confirmRebind) presetExpanded = !presetExpanded },
        ) {
            OutlinedTextField(
                value = selectedPreset.label,
                onValueChange = {},
                readOnly = true,
                label = { Text("供應商") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = presetExpanded,
                onDismissRequest = { presetExpanded = false },
            ) {
                presets.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.label) },
                        onClick = {
                            viewModel.selectPreset(preset.id)
                            presetExpanded = false
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = viewModel::onBaseUrlChange,
            readOnly = !baseUrlEditable,
            enabled = baseUrlEditable && !state.saving && !state.confirmKeyWrite && !state.confirmRebind,
            label = { Text("伺服器位址") },
            supportingText = {
                if (baseUrlEditable) Text("自訂位址必須是 https（本機/內網測試位址除外）。")
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        var modelExpanded by remember { mutableStateOf(false) }
        // S5＋S4-C 對稱閘：preset/baseUrl/apiKey/三按鈕（save/test/refresh）以
        // `saving || confirmKeyWrite || confirmRebind` 三閘（UI 擋；VM entry 另有
        // confirmKeyWrite／confirmRebind／logoutInFlight silent-return 縱深，見 saveWithConsent／
        // testConnection／refreshModels）；model 因不 bump（#17 範圍外，見
        // onModelChange）故同樣雙閘。UI 與 VM 雙層，不宣稱單層充分。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExposedDropdownMenuBox(
                expanded = modelExpanded,
                onExpandedChange = {
                    if (!state.saving && !state.confirmKeyWrite && !state.confirmRebind) modelExpanded = !modelExpanded
                },
                modifier = Modifier.weight(1f),
            ) {
                OutlinedTextField(
                    value = state.model,
                    onValueChange = viewModel::onModelChange,
                    enabled = !state.saving && !state.confirmKeyWrite && !state.confirmRebind,
                    label = { Text("模型") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryEditable),
                    singleLine = true,
                )
                ExposedDropdownMenu(
                    expanded = modelExpanded,
                    onDismissRequest = { modelExpanded = false },
                ) {
                    if (state.modelOptions.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("先按「更新」載入模型列表，也可直接輸入") },
                            onClick = { modelExpanded = false },
                        )
                    } else {
                        state.modelOptions.take(50).forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    viewModel.onModelChange(option)
                                    modelExpanded = false
                                },
                            )
                        }
                    }
                }
            }
            IconButton(
                onClick = viewModel::refreshModels,
                enabled = !state.modelsLoading && !state.testing && !state.saving && !state.confirmKeyWrite && !state.confirmRebind,
            ) {
                if (state.modelsLoading) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = "更新模型列表")
                }
            }
        }
        modelDirectoryStatusText(state.modelDirectoryStatus)?.let { statusText ->
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = state.apiKey,
            onValueChange = viewModel::onApiKeyChange,
            enabled = !state.saving && !state.confirmKeyWrite && !state.confirmRebind,
            label = { Text("API Key") },
            placeholder = { Text("貼上你的金鑰") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (state.showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
            trailingIcon = {
                IconButton(onClick = viewModel::toggleShowKey) {
                    Icon(
                        imageVector = if (state.showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (state.showKey) "隱藏金鑰" else "顯示金鑰",
                    )
                }
            },
            supportingText = { Text("金鑰存於本機加密儲存；向你設定的 AI API 端點發出聊天或模型列表等需驗證的請求時，會傳送 API Key（包含自訂端點）。換機需重輸。") },
        )
        TextButton(
            onClick = { uriHandler.openUri(THREAT_MODEL_URL) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("查看威脅模型說明（docs/THREAT_MODEL.md）")
        }

        state.errorCode?.let { code ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = errorText(code),
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        state.testModels?.let { count ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "連線成功，找到 $count 個模型。",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = viewModel::testConnection,
                enabled = !state.testing && !state.saving && !state.modelsLoading && !state.confirmKeyWrite && !state.confirmRebind,
                modifier = Modifier.weight(1f),
            ) {
                if (state.testing) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.padding(4.dp))
                    Text("測試中…")
                } else {
                    Text("測試連線")
                }
            }
            Button(
                onClick = viewModel::save,
                enabled = !state.saving && !state.testing && !state.modelsLoading && !state.confirmKeyWrite && !state.confirmRebind,
                modifier = Modifier.weight(1f),
            ) {
                if (state.saving) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.padding(4.dp))
                    Text("儲存中…")
                } else {
                    Text(if (isFirstRun) "儲存並開始聊天" else "儲存")
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "LibrePocket 為獨立社群專案，與上述供應商無任何關聯；此頁僅為你自填端點提供指名引用，不代表官方支援或認證。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (state.confirmKeyWrite) {
        // S4: STAGED 與 KEEP_EXISTING 皆顯示身份列（provider label、baseUrl、
        // model；STAGED 的 oldModel 為 null 故僅顯示 newModel），STAGED 另沿用
        // key-write「存入」文案，KEEP_EXISTING 用保留文案（不提「存入」）。
        val keepExistingConfirm = state.pendingKeyWriteIntent == KeyWriteIntent.KEEP_EXISTING
        AlertDialog(
            onDismissRequest = viewModel::dismissKeyWriteConfirm,
            title = { Text(if (keepExistingConfirm) "確認更新設定" else "寫入金鑰確認") },
            text = {
                if (keepExistingConfirm) {
                    Column {
                        Text("保留既有金鑰，僅更新設定（不會讀取金鑰內容，僅確認存在；零 put/get/delete；不會寫入金鑰內容）。")
                        state.confirmSummary?.let { summary ->
                            Text("供應商：${summary.providerLabel}")
                            Text("伺服器位址：${summary.baseUrl}")
                            Text(
                                if (summary.oldModel != null) {
                                    "模型：${summary.oldModel} → ${summary.newModel}"
                                } else {
                                    "模型：${summary.newModel}"
                                },
                            )
                        }
                        TextButton(onClick = { uriHandler.openUri(THREAT_MODEL_URL) }) {
                            Text("查看威脅模型說明（docs/THREAT_MODEL.md）")
                        }
                    }
                } else {
                    Column {
                        Text("即將把此 API Key 存入本機加密儲存（key.write）。換機或還原後需重輸；向你設定的 AI API 端點發出聊天或模型列表等需驗證的請求時，會傳送 API Key（包含自訂端點）。確定繼續嗎？")
                        state.confirmSummary?.let { summary ->
                            Text("供應商：${summary.providerLabel}")
                            Text("伺服器位址：${summary.baseUrl}")
                            Text("模型：${summary.newModel}")
                        }
                        TextButton(onClick = { uriHandler.openUri(THREAT_MODEL_URL) }) {
                            Text("查看威脅模型說明（docs/THREAT_MODEL.md）")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmKeyWriteSave) {
                    Text(if (keepExistingConfirm) "確定更新" else "確定寫入")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissKeyWriteConfirm) { Text("取消") }
            },
        )
    }

    if (state.confirmRebind) {
        // S4-C：顯式重綁確認（舊→新快照＋確認／取消）。確認後只重綁＋清錯＋藏框，
        // 不 auto-send（需再按一次測試／儲存）；輸入與按鈕在期間 disabled（上）。
        AlertDialog(
            onDismissRequest = viewModel::dismissRebindConfirm,
            title = { Text("確認重綁金鑰") },
            text = {
                Column {
                    Text("此金鑰先前綁定至另一伺服器。確認後才會將此次輸入的金鑰綁定至目前伺服器，不會立即傳送或儲存，需再按一次測試／儲存。")
                    state.rebindSummary?.let { summary ->
                        Text(
                            if (summary.oldLabel != null && summary.oldBaseUrl != null) {
                                "原綁定：${summary.oldLabel} ${summary.oldBaseUrl}"
                            } else {
                                "原綁定：（無）"
                            },
                        )
                        Text("目前：${summary.newLabel} ${summary.newBaseUrl}")
                        Text("模型：${summary.newModel}")
                    }
                    TextButton(onClick = { uriHandler.openUri(THREAT_MODEL_URL) }) {
                        Text("查看威脅模型說明（docs/THREAT_MODEL.md）")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRebind) {
                    Text("確認重綁")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRebindConfirm) { Text("取消") }
            },
        )
    }
}
