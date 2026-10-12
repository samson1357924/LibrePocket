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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.librepocket.voice.VoiceErrorState
import dev.librepocket.voice.PendingSpeak
import dev.librepocket.voice.VoiceStt
import dev.librepocket.voice.VoiceSpeaker
import dev.librepocket.voice.VoiceTools
import dev.librepocket.voice.VoiceTts
import dev.librepocket.voice.VoiceTtsConfirmState
import dev.librepocket.voice.TtsConfirmDecision
import kotlinx.coroutines.launch

/** User-visible text for a chat notice code; unknown codes fall through raw. */
internal fun chatNoticeText(code: String): String =
    when (code) {
        "NO_ENDPOINT" -> "尚未設定端點，請先設定 API 金鑰。"
        "POLICY_DENIED" -> "政策拒絕讀取金鑰（key.read DENY），本次未發送任何請求。"
        "CHAT_SEND_DENIED" -> "對話送出被政策拒絕（chat.send DENY），本次未發送任何請求。"
        "CHAT_APPROVAL_REQUIRED" -> "此操作需要明確核准；目前沒有互動核准流程，本次未送出，也未自動允許。"
        "CHAT_POLICY_UNAVAILABLE" -> "無法驗證對話權限，本次未送出，請稍後重試"
        "CHAT_CANCELLED_RECOVERY" -> "對話已取消；尚未送出的排隊訊息已保留，請確認後手動送出。"
        "CHAT_QUEUE_RECOVERY_REQUIRED" -> "排隊中的訊息尚未送出，已保留，請確認後手動送出。"
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
    // 呼叫方（MainScreen 經 VoiceSwitchStore 三態快照轉 fail-closed 後的 map）傳入
    // 使用者開關；loading／讀錯時為全關 map（門禁關閉），loaded 時兩鍵恆在。
    // 預設空 map 即全預設值（voice_input/voice_output 預設開，僅預覽用）。
    // mic 鈕與朗讀鈕按此顯隱。
    userSwitches: Map<String, Boolean> = emptyMap(),
    // R4-2：設定讀取失敗旗（真相源讀錯時 true）：此時 userSwitches 已為全關
    //（入口隱藏、VoiceSpeaker 未建構），另在此顯示一行讀取問題提示（沿用下方
    // 錯誤區既有樣式，不新增設計語言）。
    voiceSwitchLoadFailed: Boolean = false,
    // #2 朗讀首次揭露確認（持久化，只問一次）：未確認前朗讀鈕先顯示內聯確認。
    // 確認鈕 await 寫入再行動（見 VoiceTtsConfirmState）：成功才算確認，
    // 失敗仍執行本次朗讀並顯示 TTS_ACK_PERSIST_WARNING（保持未確認，下次重問）。
    ttsAcknowledged: Boolean = false,
    onTtsAcknowledged: suspend () -> Boolean = { true },
) {
    val session by viewModel.sessionState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val pendingRecoveryCount by viewModel.pendingRecoveryCount.collectAsStateWithLifecycle()
    val currentSessionId by viewModel.currentSessionId.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val streaming = session.status == ChatStatus.STREAMING
    var wasActive by remember { mutableStateOf(false) }

    val inputEnabled = VoiceTools.inputEnabled(userSwitches)
    val outputEnabled = VoiceTools.outputEnabled(userSwitches)

    // S2 系統 STT（優先）+ 系統 TTS（優先）：零新權限。
    // #7 雙錯誤通道（各管各的）：STT 失敗經 onSttFailure → sttError 顯示，
    // STT 成功只清 sttError；TTS 引擎失敗（無引擎／初始化／語言／朗讀）
    // 經 speaker onError → ttsError 顯示，實際送引擎前／TTS 成功／
    // voice_output 關閉時清除（見 VoiceErrorState）。
    val context = LocalContext.current
    val voiceErrors = remember { VoiceErrorState() }
    // #2 確認編排（await 寫入 → 按結果行動；寫入中禁用確認鈕防連打），
    // 兼 TTS 引擎回呼的主線程投遞（見 speaker 接線）。
    val ttsConfirm = remember { VoiceTtsConfirmState() }
    val confirmScope = rememberCoroutineScope()
    // #2 朗讀確認中待朗讀（null 即無待確認朗讀；單一待確認位，後按覆蓋前按）。
    // P2 身分防線：純文字無法區分同文不同訊息亦無 session 身分，改攜 messageId＋
    // sessionId＋單調 token（見 PendingSpeak）；確認協程 capture 後 token＋世代＋
    // session 三者全等才朗讀。speakToken 每次新建／失效遞增，使舊確認協程失效。
    var pendingSpeak by remember { mutableStateOf<PendingSpeak?>(null) }
    var speakToken by remember { mutableLongStateOf(0L) }
    // P2 確認競態世代：outputEnabled 每次變更即 bump（初次組合時與 prev 同值，不 bump；
    // capture 皆發生在其後）。確認協程以此偵測 await 期間的開關翻轉，判定見
    // VoiceTtsConfirmState.shouldSpeakAfterConfirm（純函數，JVM 可測）。
    var outputGeneration by remember { mutableLongStateOf(0L) }
    var prevOutputEnabled by remember { mutableStateOf(outputEnabled) }
    // M2 延遲建構：voice_output 關閉時不初始化 TTS 引擎（不佔系統服務）。
    val speaker: VoiceSpeaker? = remember(context, outputEnabled) {
        if (outputEnabled) {
            VoiceSpeaker(
                context.applicationContext,
                // P3：引擎回呼在 binder 執行緒：經主線程作用域投遞（仿 SetupViewModel
                // logout 的 withContext(Dispatchers.Main)；VoiceSpeaker 層不動）。
                onError = { msg -> confirmScope.launch { voiceErrors.onTtsFailure(msg) } },
                onDone = { confirmScope.launch { voiceErrors.onTtsSuccess() } },
            )
        } else {
            null
        }
    }
    DisposableEffect(speaker) {
        onDispose { speaker?.shutdown() }
    }
    // voice_output 關閉：TTS 通道消失，清 tts 錯誤、寫入失敗警告與待確認朗讀
    //（token 遞增使 suspend 中的舊確認協程失效，恢復後丟棄不 speak）。
    LaunchedEffect(outputEnabled) {
        if (outputEnabled != prevOutputEnabled) {
            outputGeneration++
            prevOutputEnabled = outputEnabled
        }
        if (!outputEnabled) {
            voiceErrors.onOutputDisabled()
            ttsConfirm.clearAckWarning()
            speakToken++
            pendingSpeak = null
        }
    }
    // 新對話不卸載 ChatScreen：session 切換即清待確認朗讀並使舊 token 失效，
    // 否則殘留 pending 會跨會話朗讀。初次組合與 current 同值時不動
    //（仿 outputGeneration 的 prev 比對）。
    var prevSessionId by remember { mutableStateOf(currentSessionId) }
    LaunchedEffect(currentSessionId) {
        if (currentSessionId != prevSessionId) {
            prevSessionId = currentSessionId
            speakToken++
            pendingSpeak = null
        }
    }
    val sttLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val candidates = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = VoiceStt.pickResult(candidates)
            if (text != null) {
                voiceErrors.onSttSuccess()
                viewModel.prefill(text)
            } else {
                voiceErrors.onSttFailure(VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_EMPTY_RESULT))
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
            voiceErrors.onSttFailure(VoiceStt.fallbackMessage(reason, detail))
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
            voiceErrors.onSttFailure(VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_NO_SERVICE))
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
                    // 為 null（延遲建構）時直接不送引擎；引擎失敗經 onError
                    // 進 ttsError 顯示（見 VoiceSpeaker 失敗通道）。
                    // #2：未確認前先記下待朗讀文字、顯示內聯確認，確認後才 speak。
                    // 清錯只在實際送引擎前（直讀分支／確認接受分支）；進入確認
                    // 與取消確認皆不碰 tts 錯誤（取消後舊錯保留）。
                    MessageBubble(
                        msg,
                        onSpeak = { messageId, text ->
                            if (VoiceTts.requiresTtsConfirmation(ttsAcknowledged)) {
                                speakToken++
                                pendingSpeak = PendingSpeak(messageId, currentSessionId, text, speakToken)
                            } else {
                                voiceErrors.onSpeakStarted()
                                speaker?.speak(text)
                            }
                        },
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

        // Q3: outbox recovery entry. Restoring fills a blank box only and
        // never sends; both actions cost zero provider calls.
        if (pendingRecoveryCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "有 $pendingRecoveryCount 則未送出的訊息可恢復",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::restoreNextRecovered) { Text("恢復") }
                TextButton(onClick = viewModel::discardNextRecovered) { Text("捨棄") }
            }
        }

        // #7＋#2 錯誤與確認區：高度上限＋內部可滾（仿 SetupScreen verticalScroll 先例；
        // 小螢幕時保住底部輸入列；內容短時行為不變）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // #7 雙錯誤通道分別顯示（STT／TTS 各管各的，不互相清除）。
            voiceErrors.sttError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            voiceErrors.ttsError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // R4-2 設定讀取問題提示（沿用錯誤區既有樣式；開關已 fail-closed 隱藏入口）。
            if (voiceSwitchLoadFailed) {
                Text(
                    text = "語音設定讀取失敗，語音輸入與朗讀已停用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // #2 朗讀內聯確認（首次可見揭露：視力用戶不需進設定頁即可看到；
            // 既有朗讀鈕 contentDescription 保留）。確認鈕 await 寫入再行動：
            // 成功／寫入失敗皆執行本次朗讀（寫入失敗另顯示 TTS_ACK_PERSIST_WARNING，
            // 保持未確認，下次重問）；寫入中禁用確認鈕（連打防護，寫完恢復）；
            // 取消鈕只清待朗讀文字，不碰錯誤。
            pendingSpeak?.let { shown ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = VoiceTts.ttsConfirmMessage(),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    // P2：先 capture 當代 speaker＋待朗讀（token／session 身分）＋世代；
                                    // await 期間取消／開關翻轉／session 切換皆使 token 或世代或
                                    // session 失配，恢復後即丟棄（不 speak、不清錯、不碰 pending，
                                    // 後者已由各失效效應清空或屬其後的新確認）。
                                    val captured = shown
                                    val capturedSpeaker = speaker
                                    val capturedGeneration = outputGeneration
                                    confirmScope.launch {
                                        when (ttsConfirm.confirm(onTtsAcknowledged)) {
                                            TtsConfirmDecision.SUPPRESSED -> Unit
                                            else -> {
                                                if (!ttsConfirm.shouldSpeakAfterConfirm(
                                                        captured,
                                                        pendingSpeak,
                                                        capturedGeneration,
                                                        outputGeneration,
                                                        currentSessionId,
                                                    )
                                                ) {
                                                    return@launch
                                                }
                                                voiceErrors.onSpeakStarted()
                                                capturedSpeaker?.speak(captured.text)
                                                if (pendingSpeak?.token == captured.token) {
                                                    pendingSpeak = null
                                                }
                                            }
                                        }
                                    }
                                },
                                enabled = !ttsConfirm.confirming,
                            ) { Text("朗讀") }
                            TextButton(
                                onClick = {
                                    speakToken++
                                    pendingSpeak = null
                                },
                            ) { Text("取消") }
                        }
                    }
                }
            }

            // ack 寫入失敗警告（內聯可見；下次確認成功或輸出關閉時清除）。
            if (ttsConfirm.ackWriteFailed) {
                Text(
                    text = VoiceTts.TTS_ACK_PERSIST_WARNING,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
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
    onSpeak: (messageId: String, text: String) -> Unit = { _, _ -> },
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
                // S2 系統 TTS：助理氣泡加朗讀鈕（系統語音引擎處理，
                // 離線能力與資料傳輸取決於已安裝的引擎，非「本地保證」；
                // 零新權限；開關關閉時隱藏）。朗讀為逐則手勢，
                // 引擎揭露見按鈕無障礙文案（VoiceTts.SPEAK_BUTTON_DESCRIPTION）。
                if (!isUser && msg.text.isNotBlank() && !msg.isPartial && showSpeak) {
                    IconButton(
                        onClick = { onSpeak(msg.id, msg.text) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.VolumeUp,
                            contentDescription = VoiceTts.SPEAK_BUTTON_DESCRIPTION,
                        )
                    }
                }
            }
        }
    }
}
