package dev.librepocket.agent.ui.chat

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
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
import dev.librepocket.voice.shouldApplyUiEvent
import dev.librepocket.voice.VoiceTts
import dev.librepocket.voice.VoiceTtsConfirmState
import dev.librepocket.voice.TtsConfirmDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** User-visible text for a chat notice code; unknown codes fall through raw. */
internal fun chatNoticeText(code: String): String =
    when (code) {
        "NO_ENDPOINT" -> "尚未設定端點，請先設定 API 金鑰。"
        "POLICY_DENIED" -> "政策拒絕讀取金鑰（key.read DENY），本次未發送任何請求。"
        "KEY_READ_CONFIRM_REQUIRED" -> "需要本次確認才可讀取金鑰，本次未送出。"
        "CHAT_SEND_DENIED" -> "對話送出被政策拒絕（chat.send DENY），本次未發送任何請求。"
        "CHAT_APPROVAL_REQUIRED" -> "此操作需要明確核准；目前沒有互動核准流程，本次未送出，也未自動允許。"
        "CHAT_POLICY_UNAVAILABLE" -> "無法驗證對話權限，本次未送出，請稍後重試。"
        "CHAT_INPUT_TOO_LONG" -> "輸入過長，本次未送出，請縮短後重試。"
        "CHAT_STORE_UNAVAILABLE" -> "無法寫入對話紀錄，本次未送出，請稍後重試。"
        "CHAT_STORE_PENDING" -> "對話紀錄寫入確認中，結果未定請勿重送。"
        "CHAT_CANCELLED_RECOVERY" -> "對話已取消；尚未送出的排隊訊息已保留，請確認後手動送出。"
        "CHAT_QUEUE_RECOVERY_REQUIRED" -> "排隊中的訊息尚未送出，已保留，請確認後手動送出。"
        "UNKNOWN_SESSION" -> "找不到該會話，可能已被刪除。"
        "SEND_CANCELLED_ENDPOINT_CHANGED" -> "端點已變更，本次未送出，請確認後重送。"
        "RESUME_CROSS_PROVIDER_HISTORY_WITHHELD" -> "先前對話屬於其他 provider 或來源不明，為保護隱私不會自動傳給目前 provider；舊紀錄仍顯示於畫面，新對話從空白上下文開始。"
        "HISTORY_SNAPSHOT_INCOMPLETE" -> "切換模型時歷史快照寫入失敗，舊對話未能完整帶入；本次仍可繼續對話，重啟後僅保留新對話紀錄。"
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
    val keyReadConfirmSeq by viewModel.keyReadConfirmSequence.collectAsStateWithLifecycle()
    val pendingRecoveryCount by viewModel.pendingRecoveryCount.collectAsStateWithLifecycle()
    val currentSessionId by viewModel.currentSessionId.collectAsStateWithLifecycle()
    // S2: pending history open retained across ConfirmRequired (explicit resume target).
    val pendingOpenSid by viewModel.pendingOpenSessionId.collectAsStateWithLifecycle()
    // R5-5: send-time per-request trim (oldest-first messages dropped to fit
    // the request budget). Orthogonal to load-time resume omission; shown as
    // a persistent badge, never summed with any other count.
    val requestTrimmedCount by viewModel.requestTrimmedCount.collectAsStateWithLifecycle()
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
    // S7 key.read 確認對話框狀態（notice＋sequence 觸發，非自動彈窗擋路）。
    // snapshot 為不可變 binding（peekKeyReadBinding，只讀，不含金鑰內容）＋
    // P2 pending token（peekKeyReadConfirmToken，只讀，綁 binding＋generation＋
    // sequence＋opId/openSessionId）；confirming 為 mint 在途（按鈕 disable 防 double-tap）；
    // confirmResult null=待確認、true=已確認（有 pending open 顯示開啟歷史，
    // 無則請再按發送）、false=已失效；
    // dialogHidden 為本次 notice 的本地關閉（VM notice 在下次送出前保持，
    // 故需本地隱藏；notice 離開 KEY_READ_CONFIRM_REQUIRED 即重置）。
    // sequence 門：每次 deny sequence 單調遞增（VM 永不重置），故
    // ConfirmRequired→null→ConfirmRequired 中間 null 被 StateFlow
    // conflation 吃掉時仍以 seq 變化重置並重 peek；同 seq 內 effect 不重跑，
    // confirm 進度不受影響。
    // S2 resuming：顯式 resume 在途（按鈕 disable 防 double-tap；成功後
    // notice 離開 KEY 即重置，失敗 bump seq 即重置重 peek）。
    var keyReadBinding by remember { mutableStateOf<EndpointSessionBinding?>(null) }
    var keyReadToken by remember { mutableStateOf<PendingKeyReadConfirm?>(null) }
    var keyReadBindingReady by remember { mutableStateOf(false) }
    var keyReadConfirming by remember { mutableStateOf(false) }
    var keyReadConfirmResult by remember { mutableStateOf<Boolean?>(null) }
    var keyReadDialogHidden by remember { mutableStateOf(false) }
    var keyReadResuming by remember { mutableStateOf(false) }
    val keyReadScope = rememberCoroutineScope()
    LaunchedEffect(notice, keyReadConfirmSeq) {
        if (notice != "KEY_READ_CONFIRM_REQUIRED") {
            keyReadBinding = null
            keyReadToken = null
            keyReadBindingReady = false
            keyReadConfirming = false
            keyReadConfirmResult = null
            keyReadDialogHidden = false
            keyReadResuming = false
        } else {
            // 新 deny（seq 變化或 notice 新進 KEY）：無條件重置本地狀態後重 peek。
            keyReadBinding = null
            keyReadToken = null
            keyReadBindingReady = false
            keyReadConfirming = false
            keyReadConfirmResult = null
            keyReadDialogHidden = false
            keyReadResuming = false
            keyReadBinding = try {
                viewModel.peekKeyReadBinding()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            // P2: token 與 snapshot 同 emission 捕獲；任一為 null 即 fail-closed
            //（confirm 攜 null token 必 false，顯示已失效）。
            keyReadToken = try {
                viewModel.peekKeyReadConfirmToken()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            keyReadBindingReady = true
        }
    }
    // #2 朗讀確認中待朗讀（null 即無待確認朗讀；單一待確認位，後按覆蓋前按）。
    // P2 身分防線：純文字無法區分同文不同訊息亦無 session 身分，改攜 messageId＋
    // sessionId＋單調 token（見 PendingSpeak）；確認協程 capture 後 token＋世代＋
    // session 三者全等才朗讀。speakToken 每次新建／失效遞增，使舊確認協程失效。
    var pendingSpeak by remember { mutableStateOf<PendingSpeak?>(null) }
    var speakToken by remember { mutableLongStateOf(0L) }
    // P2 確認競態世代：outputEnabled 每次變更即 bump（首幀 hasPrev 為 false，
    // 不 bump；capture 皆發生在其後）。確認協程以此偵測 await 期間的開關翻轉，判定見
    // VoiceTtsConfirmState.shouldSpeakAfterConfirm（純函數，JVM 可測）。
    var outputGeneration by remember { mutableLongStateOf(0L) }
    val (prevOutputEnabled, hasPrevOutput) = rememberPrevious(outputEnabled)
    // M2 延遲建構：voice_output 關閉時不初始化 TTS 引擎（不佔系統服務）。
    // R5-2 Main 投遞 epoch fence：speaker 回調（binder 執行緒）攜事件 epoch，
    // 經主線程作用域投遞時以 shouldApplyUiEvent 比對當代 currentEpoch，不符丟棄
    //（舊 request 事件排入 Main 後、執行前若有新 speak 使 epoch 前進，即被擋下）。
    // remember 初始化器內拿不到自身（self-reference 不可寫），故不做實例比對、
    // 只比對 currentEpoch：舊實例事件由 shutdown terminal 在 speaker 層擋下
    //（見 VoiceSpeaker），epoch-only 已足夠（epoch 全域單調，跨實例永不重疊）。
    // holder 為 null（輸出關閉）時丟棄；
    // 直讀分支同步 onSpeakStarted 語義不變。
    val speakerHolder = remember { object { var speaker: VoiceSpeaker? = null } }
    val speaker: VoiceSpeaker? = remember(context, outputEnabled) {
        val created = if (outputEnabled) {
            VoiceSpeaker(
                context.applicationContext,
                // P3：引擎回呼在 binder 執行緒：經主線程作用域投遞（仿 SetupViewModel
                // logout 的 withContext(Dispatchers.Main)；VoiceSpeaker 層不動）。
                onError = { msg, epoch ->
                    confirmScope.launch {
                        val current = speakerHolder.speaker
                        if (current == null || !shouldApplyUiEvent(epoch, current.currentEpoch)) return@launch
                        voiceErrors.onTtsFailure(msg)
                    }
                },
                onDone = { epoch ->
                    confirmScope.launch {
                        val current = speakerHolder.speaker
                        if (current == null || !shouldApplyUiEvent(epoch, current.currentEpoch)) return@launch
                        voiceErrors.onTtsSuccess()
                    }
                },
            )
        } else {
            null
        }
        speakerHolder.speaker = created
        created
    }
    DisposableEffect(speaker) {
        onDispose { speaker?.shutdown() }
    }
    // voice_output 關閉：TTS 通道消失，清 tts 錯誤、寫入失敗警告與待確認朗讀
    //（token 遞增使 suspend 中的舊確認協程失效，恢復後丟棄不 speak）。
    LaunchedEffect(outputEnabled) {
        if (hasPrevOutput && outputEnabled != prevOutputEnabled) {
            outputGeneration++
        }
        if (!outputEnabled) {
            voiceErrors.onOutputDisabled()
            ttsConfirm.clearAckWarning()
            speakToken++
            pendingSpeak = null
        }
    }
    // 新對話不卸載 ChatScreen：session 切換即清待確認朗讀並使舊 token 失效，
    // 否則殘留 pending 會跨會話朗讀。首幀 hasPrev 為 false，不動
    //（與 outputGeneration 同判定形狀）。
    val (prevSessionId, hasPrevSession) = rememberPrevious(currentSessionId)
    LaunchedEffect(currentSessionId) {
        if (hasPrevSession && currentSessionId != prevSessionId) {
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

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length, session.status, requestTrimmedCount) {
        if (messages.isNotEmpty()) {
            try {
                // R5-5: the request-trim badge (when visible) occupies list
                // index 0, so the last message sits at messages.size, not
                // messages.size - 1.
                listState.scrollToItem(if (requestTrimmedCount > 0) messages.size else messages.size - 1)
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
                // R5-5: persistent request-trim badge pinned to the top of the
                // history list (N > 0 only). bodySmall + onSurfaceVariant, same
                // as the pending-steer badge below the list.
                if (requestTrimmedCount > 0) {
                    item(key = "request-trim-badge") {
                        Text(
                            text = "有 $requestTrimmedCount 則較早歷史未送出模型",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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

        // S2: pending history open 的顯式 resume 入口（對話框隱藏後仍可開啟；
        // 不自動 send；成功後 notice 離開 KEY 即消失）。
        if (notice == "KEY_READ_CONFIRM_REQUIRED" && pendingOpenSid != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "歷史會話待開啟",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        if (keyReadResuming) return@Button
                        keyReadResuming = true
                        pendingOpenSid?.let { viewModel.resumePendingOpen(it) }
                    },
                    enabled = !keyReadResuming,
                ) { Text(if (keyReadResuming) "開啟中…" else "開啟歷史") }
            }
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

    // S7 key.read 確認對話框：notice 觸發（非自動彈窗擋路），內容為不可變
    // binding 快照（唯讀，不含金鑰內容）＋ P2 pending token。確認僅 mint 一次性同意，不 auto-send；
    // 拒絕/取消僅丟棄未消耗的同意＋token，不發送。
    // S2: 有 pending open 時確認後顯示「已確認，開啟歷史」並提供顯式 resume
    //（不自動 send、不自動 resume）。
    if (notice == "KEY_READ_CONFIRM_REQUIRED" && !keyReadDialogHidden) {
        KeyReadConfirmDialog(
            binding = keyReadBinding,
            loading = !keyReadBindingReady,
            confirming = keyReadConfirming,
            confirmResult = keyReadConfirmResult,
            pendingOpenSessionId = pendingOpenSid,
            resuming = keyReadResuming,
            onConfirm = {
                if (keyReadConfirming || keyReadConfirmResult != null) return@KeyReadConfirmDialog
                // S3+P2: capture seq+binding+token at initiation; the mint runs
                // against the displayed snapshot + pending token (null ⇒
                // fail-closed false). Mint 前先驗新鮮由 VM 執行（三者全等才
                // mint）；mint 後的 stale completion 丟棄維持（雙保險）。
                val capturedSeq = viewModel.keyReadConfirmSequence.value
                val capturedBinding = keyReadBinding
                val capturedToken = keyReadToken
                keyReadConfirming = true
                keyReadScope.launch {
                    val ok = try {
                        viewModel.confirmKeyRead(capturedBinding, capturedToken)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                    // S3: stale completion must not overwrite a newer dialog
                    // generation. A sequence advance (new deny or replacement
                    // retention re-peek) or a notice departure (explicit
                    // discard/logout) — which together proxy every generation
                    // advance that matters — discards this in-flight result:
                    // neither confirming nor result is written.
                    if (capturedSeq != viewModel.keyReadConfirmSequence.value) return@launch
                    if (viewModel.notice.value != "KEY_READ_CONFIRM_REQUIRED") return@launch
                    keyReadConfirming = false
                    // 有 pending open 顯示開啟歷史，無則顯示再按發送；
                    // 皆不 auto-send（S2 resume 需顯式按「開啟歷史」）。
                    keyReadConfirmResult = ok
                }
            },
            onResumeOpen = {
                if (keyReadResuming) return@KeyReadConfirmDialog
                // S3: resume is a synchronous launch with no async completion
                // write; the resuming flag set below belongs to the current
                // dialog generation — LaunchedEffect(notice, seq) already resets
                // it on any newer deny/retention, so a stale resume never
                // leaves the new dialog stuck in "開啟中…".
                keyReadResuming = true
                pendingOpenSid?.let { viewModel.resumePendingOpen(it) }
            },
            onReject = {
                if (keyReadConfirming) return@KeyReadConfirmDialog
                // S3: only the current dialog generation may dismiss/hide. A
                // click racing a just-bumped sequence (recomposition pending)
                // must not burn-or-hide the new dialog's state. Dual gate with
                // onConfirm: seq alone does not proxy notice departure —
                // invalidateLifecycle clears the notice WITHOUT bumping seq —
                // so a stale click racing logout/newChat/open must also fail
                // on the notice check.
                if (viewModel.keyReadConfirmSequence.value != keyReadConfirmSeq) return@KeyReadConfirmDialog
                if (viewModel.notice.value != "KEY_READ_CONFIRM_REQUIRED") return@KeyReadConfirmDialog
                if (keyReadConfirmResult != true) viewModel.dismissKeyReadConfirm()
                keyReadDialogHidden = true
            },
            onCancel = {
                if (keyReadConfirming) return@KeyReadConfirmDialog
                // S3: same dual gate as onReject above.
                if (viewModel.keyReadConfirmSequence.value != keyReadConfirmSeq) return@KeyReadConfirmDialog
                if (viewModel.notice.value != "KEY_READ_CONFIRM_REQUIRED") return@KeyReadConfirmDialog
                if (keyReadConfirmResult != true) viewModel.dismissKeyReadConfirm()
                keyReadDialogHidden = true
            },
            onClose = {
                if (keyReadConfirming) return@KeyReadConfirmDialog
                // S3: same dual gate as onReject above.
                if (viewModel.keyReadConfirmSequence.value != keyReadConfirmSeq) return@KeyReadConfirmDialog
                if (viewModel.notice.value != "KEY_READ_CONFIRM_REQUIRED") return@KeyReadConfirmDialog
                if (keyReadConfirmResult != true) viewModel.dismissKeyReadConfirm()
                keyReadDialogHidden = true
            },
        )
    }
}

@Composable
internal fun KeyReadConfirmDialog(
    binding: EndpointSessionBinding?,
    loading: Boolean,
    confirming: Boolean,
    confirmResult: Boolean?,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    pendingOpenSessionId: String? = null,
    resuming: Boolean = false,
    onResumeOpen: () -> Unit = {},
) {
    // S7：key.read ASK 的一次性確認。快照欄位皆為連線身份（唯讀），不含金鑰內容。
    // confirmResult true=已確認（有 pending open 顯示開啟歷史，無則請再按發送）、
    // false=已失效；loading/confirming/resuming 期間按鈕 disable 防 double-tap；
    // binding null（peek 失敗／世代過期）顯示已失效。
    val hasPendingOpen = pendingOpenSessionId != null
    AlertDialog(
        onDismissRequest = {
            if (confirmResult != null) onClose() else onCancel()
        },
        title = { Text("讀取金鑰確認") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    confirmResult == true && hasPendingOpen -> {
                        Text("已確認，開啟歷史。")
                        if (resuming) {
                            Text("開啟中…")
                        }
                    }
                    confirmResult == true -> {
                        Text("已確認，請再按發送。")
                    }
                    confirmResult == false -> {
                        Text("已失效，請重新送出。")
                    }
                    loading -> {
                        Text("讀取連線資訊中…")
                    }
                    binding == null -> {
                        Text("已失效，請重新送出。")
                    }
                    else -> {
                        Text("本次送出需要讀取金鑰，請確認以下連線資訊（唯讀，不含金鑰內容）。")
                        Text("供應商：${binding.providerId}")
                        Text("伺服器位址：${binding.effectiveBaseUrl}")
                        Text("來源：${binding.origin}")
                        Text("協定：${binding.protocol}")
                        Text("金鑰別名：${binding.apiKeyRef}")
                        Text("設定版本：${binding.configRevision}")
                        Text("模型：${binding.model}")
                        if (confirming) {
                            Text("確認中…")
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (confirmResult == true && hasPendingOpen) {
                TextButton(onClick = onResumeOpen, enabled = !confirming && !resuming) {
                    Text(if (resuming) "開啟中…" else "開啟歷史")
                }
            } else if (confirmResult != null) {
                TextButton(onClick = onClose, enabled = !confirming && !resuming) { Text("關閉") }
            } else {
                TextButton(
                    onClick = onConfirm,
                    enabled = !confirming && !loading && binding != null,
                ) { Text("確認本次讀取") }
            }
        },
        dismissButton = {
            if (confirmResult == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onReject, enabled = !confirming) { Text("拒絕") }
                    TextButton(onClick = onCancel, enabled = !confirming) { Text("取消") }
                }
            } else if (confirmResult == true && hasPendingOpen) {
                TextButton(onClick = onClose, enabled = !confirming && !resuming) { Text("關閉") }
            }
        },
    )
}

@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
@Composable
internal fun MessageBubble(
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
                val displayText = displayAssistantText(msg.role, msg.text)
                if (isUser) {
                    Text(
                        text = if (msg.isPartial) displayText + " ▍" else displayText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    ChatMarkdownView(
                        text = displayText,
                        isStreaming = msg.isPartial,
                        textColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
                // S2 系統 TTS：助理氣泡加朗讀鈕（系統語音引擎處理，
                // 離線能力與資料傳輸取決於已安裝的引擎，非「本地保證」；
                // 零新權限；開關關閉時隱藏）。朗讀為逐則手勢，
                // 引擎揭露見按鈕無障礙文案（VoiceTts.SPEAK_BUTTON_DESCRIPTION）。
                if (!isUser && !msg.isPartial && showSpeak) {
                    val speakableText = remember(displayText) { ChatMarkdownParser.stripThinking(displayText) }
                    if (speakableText.isNotBlank()) {
                        IconButton(
                            onClick = { onSpeak(msg.id, speakableText) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = VoiceTts.SPEAK_BUTTON_DESCRIPTION,
                            )
                        }
                    }
                }
            }
        }
    }
}
