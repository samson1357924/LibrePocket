package dev.librepocket.agent.ui.setup

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.librepocket.preset.ProviderCatalog

private fun errorText(code: String): String = when (code) {
    "SETUP_UNKNOWN_PRESET" -> "未知的供應商，請重新選擇。"
    "SETUP_BASE_URL_BLANK" -> "請填寫伺服器位址。"
    "PROVIDER_BAD_URL" -> "位址格式不正確。"
    "PROVIDER_URL_MUST_BE_HTTPS" -> "必須使用 https（本機測試僅允許 loopback）。非 https 在正式版會被系統擋下。"
    "SETUP_KEY_TOO_SHORT" -> "金鑰太短（至少 8 字元），請檢查後重貼。"
    "PROVIDER_KEY_REF_BLANK", "PROVIDER_KEY_REF_MALFORMED" -> "內部參照異常，請重試。"
    "API key too short" -> "金鑰太短（至少 8 字元），請檢查後重貼。"
    else -> "儲存失敗（$code），請重試。"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    padding: PaddingValues,
    isFirstRun: Boolean,
    onSaved: () -> Unit,
    viewModel: SetupViewModel = viewModel(),
) {
    val state by viewModel.form.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Key-entry page: block screenshots / recents thumbnails while visible.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    // First-run gate: no back escape (chat cannot work without an endpoint).
    BackHandler(enabled = isFirstRun) { }

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

        ExposedDropdownMenuBox(
            expanded = presetExpanded,
            onExpandedChange = { presetExpanded = !presetExpanded },
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
            enabled = baseUrlEditable,
            label = { Text("伺服器位址") },
            supportingText = {
                if (baseUrlEditable) Text("自訂位址必須是 https（本機/內網測試位址除外）。")
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedTextField(
            value = state.model,
            onValueChange = viewModel::onModelChange,
            label = { Text("模型") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedTextField(
            value = state.apiKey,
            onValueChange = viewModel::onApiKeyChange,
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
            supportingText = { Text("金鑰只存於本機加密儲存，不會上傳別處；換機需重輸。") },
        )

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

        Button(
            onClick = viewModel::save,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.saving) {
                CircularProgressIndicator(strokeWidth = 2.dp)
                Spacer(modifier = Modifier.padding(4.dp))
                Text("儲存中…")
            } else {
                Text(if (isFirstRun) "儲存並開始聊天" else "儲存")
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "LibrePocket 為獨立社群專案，與上述供應商無任何關聯；此頁僅為你自填端點提供指名引用，不代表官方支援或認證。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
