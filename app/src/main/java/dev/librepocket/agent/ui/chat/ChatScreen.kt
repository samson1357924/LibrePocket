package dev.librepocket.agent.ui.chat

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.librepocket.chat.ChatStatus
import dev.librepocket.chat.UiMessage
import dev.librepocket.tool.DenyReason
import dev.librepocket.voice.VoiceStt
import dev.librepocket.voice.VoiceSpeaker
import dev.librepocket.voice.VoiceTools

/** User-visible text for a chat notice code; unknown codes fall through raw. */
internal fun chatNoticeText(code: String): String =
    when (code) {
        "NO_ENDPOINT" -> "尚未設定端點，請先設定 API 金鑰。"
        "POLICY_DENIED" -> "政策拒絕讀取金鑰（key.read DENY），本次未發送任何請求。"
        "UNKNOWN_SESSION" -> "找不到該會話，可能已被刪除。"
        "SEND_CANCELLED_ENDPOINT_CHANGED" -> "端點已變更，本次未送出，請確認後重送。"
        else -> code
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    padding: PaddingValues,
    viewModel: ChatViewModel = viewModel(),
    onOpenSettings: () -> Unit = {},
    onTurnFinished: () -> Unit = {},
    // M2 語音開關（與 ProjectionContext 同源：同 key、同預設，缺鍵即預設值）。
    // 呼叫方（持久化開關落地後）傳入使用者開關快照；預設空 map 即全預設值
    // （voice_input/voice_output 預設開）。mic 鈕與朗讀鈕按此顯隱。
    userSwitches: Map<String, Boolean> = emptyMap(),
) {
    val session by viewModel.sessionState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val streaming = session.status == ChatStatus.STREAMING
    var wasActive by remember { mutableStateOf(false) }

    val inputEnabled = userSwitches.getOrDefault(VoiceTools.SWITCH_INPUT, VoiceTools.SWITCH_INPUT_DEFAULT)
    val outputEnabled = userSwitches.getOrDefault(VoiceTools.SWITCH_OUTPUT, VoiceTools.SWITCH_OUTPUT_DEFAULT)

    // S2 系統 STT（優先）+ 系統 TTS（優先）：零新權限。
    val context = LocalContext.current
    var voiceError by remember { mutableStateOf<String?>(null) }
    // M2 延遲建構：voice_output 關閉時不初始化 TTS 引擎（不佔系統服務）。
    val speaker: VoiceSpeaker? = remember(context, outputEnabled) {
        if (outputEnabled) VoiceSpeaker(context.applicationContext) else null
    }
    DisposableEffect(speaker) {
        onDispose { speaker?.shutdown() }
    }
    val sttLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val candidates = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = VoiceStt.pickResult(candidates)
            if (text != null) {
                voiceError = null
                viewModel.prefill(text)
            } else {
                voiceError = VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_EMPTY_RESULT)
            }
        }
        // 非 OK（使用者取消）維持手動輸入，不報錯。
    }
    fun launchSystemStt() {
        val probe = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        // B1：探測必須帶 MATCH_DEFAULT_ONLY，只認「預設可處理」的辨識服務，
        // 避免列出無 DEFAULT category 的非可啟動目標；action 與 manifest
        // <queries> 同為 RECOGNIZE_SPEECH（見 VoiceStt.ACTION）。
        val hasService = context.packageManager.queryIntentActivities(
            probe,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
        ).isNotEmpty()
        val reason = VoiceStt.degradeReason(hasRecognitionService = hasService, inputEnabled = inputEnabled)
        if (reason != null) {
            val detail = if (reason == DenyReason.USER_DISABLED) {
                VoiceStt.DETAIL_SWITCH_OFF
            } else {
                VoiceStt.DETAIL_NO_SERVICE
            }
            voiceError = VoiceStt.fallbackMessage(reason, detail)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, VoiceStt.LANGUAGE)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, VoiceStt.MAX_RESULTS)
        }
        try {
            sttLauncher.launch(intent)
        } catch (_: Exception) {
            voiceError = VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_NO_SERVICE)
        }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length, session.status) {
        if (messages.isNotEmpty()) {
            try {
                listState.scrollToItem(messages.size - 1)
            } catch (_: IllegalArgumentException) {
                // List not laid out yet; next recomposition will settle.
            }
        }
    }

    LaunchedEffect(session.status) {
        if (session.status == ChatStatus.STREAMING) {
            wasActive = true
        } else if (wasActive) {
            wasActive = false
            onTurnFinished()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .imePadding(),
    ) {
        if (messages.isEmpty()) {
            // Gemini/ChatGPT-style empty state.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "有什麼可以幫你的嗎？",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("幫我寫一段話", "解釋一個概念", "規劃今天", "翻譯一段文字").forEach { hint ->
                            AssistChip(
                                onClick = {
                                    viewModel.onInputChange(hint)
                                    viewModel.send()
                                },
                                label = { Text(hint) },
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages, key = { it.id }) { msg ->
                    // M2：voice_output 關閉時隱藏朗讀鈕（與投影層同開關）；speaker
                    // 為 null（延遲建構）時 onSpeak 為 no-op。
                    MessageBubble(
                        msg,
                        onSpeak = { speaker?.speak(it) },
                        showSpeak = outputEnabled,
                    )
                }
                if (streaming) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
                                    contentDescription = "正在串流回覆"
                                },
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.padding(2.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Text("串流中…")
                                }
                            }
                        }
                    }
                }
            }
        }

        if (session.pendingSteerCount > 0) {
            Text(
                text = "有 ${session.pendingSteerCount} 則指令排隊中",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        session.error?.let { error ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (viewModel.canRetry) {
                            Button(onClick = viewModel::retry) { Text("重試") }
                        }
                        TextButton(onClick = onOpenSettings) { Text("檢查設定") }
                    }
                }
            }
        }

        notice?.let {
            Text(
                text = chatNoticeText(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        voiceError?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        // Bottom input bar (ChatGPT/Gemini style).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // TODO(P1): attach file/photo entry point; currently placeholder (disabled semantics).
            IconButton(onClick = { /* TODO(P1): attach placeholder */ }) {
                Icon(Icons.Filled.Add, contentDescription = "新增附件")
            }
            OutlinedTextField(
                value = input,
                onValueChange = viewModel::onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("輸入訊息") },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
            )
            if (streaming) {
                IconButton(onClick = viewModel::cancel) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "停止",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                val canSend = input.isNotBlank()
                if (canSend) {
                    IconButton(onClick = viewModel::send) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "傳送",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    // S2 系統 STT：RecognizerIntent 委託（FREE_FORM / zh-TW / MAX 1），
                    // 結果進 ChatViewModel.prefill()；無服務降級手動輸入。
                    // M2：voice_input 關閉時隱藏 mic 鈕（與投影層同開關）。
                    if (inputEnabled) {
                        IconButton(onClick = { launchSystemStt() }) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = "語音輸入",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    msg: UiMessage,
    onSpeak: (String) -> Unit = {},
    // M2：voice_output 開關（false 即隱藏朗讀鈕，與 ToolRegistry 投影同源）。
    showSpeak: Boolean = true,
) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp,
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            ),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = if (msg.isPartial) msg.text + " ▍" else msg.text,
                    style = MaterialTheme.typography.bodyLarge,
                )
                // S2 系統 TTS：助理氣泡加朗讀鈕（本地引擎，零新權限；開關關閉時隱藏）。
                if (!isUser && msg.text.isNotBlank() && !msg.isPartial && showSpeak) {
                    IconButton(
                        onClick = { onSpeak(msg.text) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.VolumeUp,
                            contentDescription = "朗讀",
                        )
                    }
                }
            }
        }
    }
}
