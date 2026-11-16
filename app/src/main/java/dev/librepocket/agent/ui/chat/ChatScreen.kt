package dev.librepocket.agent.ui.chat

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.librepocket.agent.R
import dev.librepocket.agent.assistant.AssistantVoice
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
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "ChatScreen"

/** Test seam: supplies the STT arbiter's clock (monotonic nanos); production uses [SystemClock.elapsedRealtimeNanos]. */
internal val LocalVoiceSttArbiterClock = staticCompositionLocalOf<() -> Long> { { SystemClock.elapsedRealtimeNanos() } }

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
    // C2 active voice task retrigger handoff（trusted, cross-process token）。When a later
    // assistant gesture is accepted for an already-active voice task, UI consumes the token once
    // and relaunches system STT under the same delivery/switch gates. Consume must succeed to
    // preserve once-only/cancellation semantics.
    voiceRetriggerToken: Long = 0L,
    onVoiceRetriggerConsumed: (Long) -> Boolean = { false },
    // R4-2：設定讀取失敗旗（真相源讀錯時 true）：此時 userSwitches 已為全關
    //（入口隱藏、VoiceSpeaker 未建構），另在此顯示一行讀取問題提示（沿用下方
    // 錯誤區既有樣式，不新增設計語言）。
    voiceSwitchLoadFailed: Boolean = false,
    // #2 朗讀首次揭露確認（持久化，只問一次）：未確認前朗讀鈕先顯示內聯確認。
    // 確認鈕 await 寫入再行動（見 VoiceTtsConfirmState）：成功才算確認，
    // 失敗仍執行本次朗讀並顯示 TTS_ACK_PERSIST_WARNING（保持未確認，下次重問）。
    ttsAcknowledged: Boolean = false,
    onTtsAcknowledged: suspend () -> Boolean = { true },
    // C2 session 語音喚起（Issue #137）：MainActivity 經 MainScreen 傳入的
    // 一次態 voice 請求（MainScreen 已守衛 `chatSafeBoundary` 單一來源；true 且開關快照已落定（voiceSwitchesLoaded）
    // 即自動走下方既有 launchSystemStt()（RecognizerIntent 委派，零新權限），
    // 先消費再啟動（防重組重放）。
    // 結果經 AssistantVoice.resolveVoicePrefill 截斷至上限並只 prefill 不自動送出；
    // 開關關閉／讀取失敗（loadFailed→userSwitches 全關）／無服務時由 launchSystemStt
    // 內既有降級話術承擔（USER_DISABLED / NO_PRIVILEGE）。預設 false／true 保舊構造／預覽相容。
    voiceInputRequested: Boolean = false,
    onVoiceInputConsumed: () -> Unit = {},
    voiceSwitchesLoaded: Boolean = true,
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
    // R5-5: provider-request context omission（window＋100k-trim 總腿；
    // 100k 單腿 debug 計數保留在 VM 層 requestTrimmedCount）
    val contextOmittedCount by viewModel.contextOmittedCount.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val streaming = session.status == ChatStatus.STREAMING
    // rememberSaveable: survives activity recreation so a turn that completes after rotation
    // still fires onTurnFinished instead of being dropped with the pre-rotation state.
    var wasActive by rememberSaveable { mutableStateOf(false) }

    val inputEnabled = VoiceTools.inputEnabled(userSwitches)
    val outputEnabled = VoiceTools.outputEnabled(userSwitches)

    // S2 系統 STT（優先）+ 系統 TTS（優先）：零新權限。
    // #7 雙錯誤通道（各管各的）：STT 失敗經 onSttFailure → sttError 顯示，
    // STT 成功只清 sttError；TTS 引擎失敗（無引擎／初始化／語言／朗讀）
    // 經 speaker onError → ttsError 顯示，實際送引擎前／TTS 成功／
    // voice_output 關閉時清除（見 VoiceErrorState）。
    val context = LocalContext.current
    val voiceErrors = remember { VoiceErrorState() }
    // 「語音辨識無回應」提示的重設鈕開關：只有無 slot 可派的降級路徑才設 true，
    // 成功預留 slot（launch 實際佔位）時設回 false；控制底部重設鈕的顯示。
    var showVoiceReset by remember { mutableStateOf(false) }
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
    // F1/P2 single-owner 單飛閘（Stage C）：所有 STT 啟動一律經 sttArbiter 決策；
    // 同時最多一枚 arm 持有閘，watchdog 釋放的懸掛 recognizer 可能與其 successor 並存——
    // 結果歸屬以各 launch 的 slot（本檔）與 generation（sttArbiter）精確對應；
    // 消費仍由呼叫點的一次性 callback 負責。
    // sttArbiterState 為 currentInFlight 的 compose 鏡像（arm 承認／result settle 時翻轉，
    // 重觸發上方統一決策效應續行下一 arm）；userVoiceArmed 為 mic 鈕的一次態 arm
    //（閘忙時按住佇列一次，settle 後續行）。
    // 活動重建（recreation）後，ActivityResultRegistry 可能把重建前 launch 的結果重新投遞給
    // 重建後的 composition；此時 remembered 記憶體狀態是全新的，必須先還原 launch 歸屬快照
    // （slot 身分 + arbiter generation 水位），重建前的 in-flight launch 才能被精確歸屬。
    // SttLaunchStateSaver 還原時 fail-closed 驗證，bundle 形狀不合一律退回 fresh()。
    // arbiter 時鐘來源為 elapsedRealtimeNanos（monotonic、含 deep sleep、跨 process 一致、
    // 可跨 recreation 還原；wall-clock 會隨牆鐘跳變、nanoTime 無法跨 recreation 比較）。
    val arbiterClock = LocalVoiceSttArbiterClock.current
    val sttLaunchBootstrap = LocalSttLaunchStateBootstrap.current
    var sttLaunchSnapshot by rememberSaveable(stateSaver = SttLaunchStateSaver) {
        mutableStateOf(sttLaunchBootstrap ?: SttLaunchState.fresh())
    }
    val sttArbiter = remember {
        VoiceSttArbiter(
            clockNanos = arbiterClock,
            initialAdmitGeneration = sttLaunchSnapshot.admitGeneration,
            initialInFlight = sttLaunchSnapshot.inFlightName?.let(VoiceSttArmKind::valueOf),
            initialAdmittedAtNanos = sttLaunchSnapshot.admittedAtNanos,
        )
    }
    // 鏡像初始值取自還原快照：閘還原後鏡像立即一致（不等首次效應），之後仍由效應翻轉覆寫。
    var sttArbiterState by remember { mutableStateOf(sttLaunchSnapshot.inFlightName?.let(VoiceSttArmKind::valueOf)) }
    // settle 計數器：每完成一次 slot 結算（含 stale/late return）＋1。stale settle 不翻轉
    // sttArbiterState（鏡像本來就是 null），少了此旗標，slot 釋放後佇列中的未消費 arm
    // （如兩 slot 楔住時未消費的 INITIAL）會擱淺到下一次無關的狀態翻轉；以此旗標作為決策
    // 效應的 key，讓 settle 確定性地喚起一次重裁決。
    var sttSettleSeq by remember { mutableStateOf(0) }
    // 使用者硬重設世代（reset）：「重設語音辨識」+1。launcher 的 registry key 由 per-entry
    // screenId＋此世代推導（"stt-<screenId>-slotN-<epoch>"）：重設後，重設前的 launch（與其 rc→key 綁定）永遠不可達——其晚到
    // 結果在 registry 邊界永不投遞（late result 停靠 pendingResults，鍵不再註冊），無法冒充
    // 重設後的 launch；slot/gen 表僅為第二層縱深防禦。
    // in-process 的負世代鍵名仍然唯一；與 epoch-0 同鍵名僅可能經跨重建還原夾為 0 的路徑出現
    // （須搭配 process death，且 outstanding launch 的 gate 由 gen==0L fail-closed 丟棄）；
    // ResetEpochSaver 維持 fail-closed 夾取（save 側原值寫入、restore 側負值夾為 0）。
    // 世代唯一性由 monotonic 遞增保證，wrap 僅於 ~2^31 次重設後出現（Int 世代遞增）。rememberSaveable：跨 activity 重建還原（epoch>0 時
    //  outstanding launch 重建後仍可歸屬）。
    var resetEpoch by rememberSaveable(saver = ResetEpochSaver) { mutableIntStateOf(0) }
    // Entry-scoped identity：entry-scoped rememberSaveable＋ScreenIdSaver（restore 驗證
    // 36-char UUID 或 fresh random；never crashes；corruption ⇒ keys unmatchable）；同一 entry
    // recreation 還原同一 UUID，新 entry 必得新 UUID。前提：CHAT back-stack entry 永不以
    // restoreState=true 還原（目前全倉皆無此用法）；未來若引入，須重新驗證跨 entry 歸屬。
    var screenId by rememberSaveable(saver = ScreenIdSaver) { mutableStateOf(UUID.randomUUID().toString()) }
    // 每枚進行中 launch 各自佔一個回呼 slot（共 2 枚）：slotBusy 標記佔用、slotGen 記錄該 slot 對應的
    // admit generation（launch 時自 currentGeneration 讀取，即剛承認的那一枚 arm 的 generation）。
    // result callback 以 slot 歸屬配對——每枚 pending launch 各自佔 slot、歸屬精確，不受回傳順序影響
    // （先回哪一枚都命中自己的 slot）；never-deliver 的 launch 會留 busy slot，於其最終回傳時釋放，
    // 或經 watchdog 釋放後由 successor 重新承認（successor 佔另一 slot）；兩 slot 全忙（僅 watchdog
    // 釋放 successor 後的殘餘 wedge）時，新 launch 降級 launched=false、閘立即釋放。slot 表經
    // rememberSaveable 快照還原（activity 重建後仍可歸屬重建前 launch），供 non-compose 回呼與
    // launchSystemStt() 直接讀寫。
    /** 兩 slot：watchdog 釋放後懸掛的 launch 與其 successor 並存，各佔一枚；slot 不主動回收——提前歸還會破壞 late return 的結果歸屬，僅由各自 late return 釋放。 */
    val slotBusy = remember { booleanArrayOf(sttLaunchSnapshot.slotBusy[0], sttLaunchSnapshot.slotBusy[1]) }
    val slotGen = remember { longArrayOf(sttLaunchSnapshot.slotGen[0], sttLaunchSnapshot.slotGen[1]) }
    /** 是否還有一枚回呼 slot 可供新 launch 歸屬（true = 可派；false = 兩 slot 皆被懸掛 recognizer 佔用）。 */
    fun hasFreeSttSlot(): Boolean = !slotBusy[0] || !slotBusy[1]
    // 狀態快照寫入 rememberSaveable：admit／降級／settle／watchdog 釋放後皆更新，
    // 使 activity 重建時能還原 slot 歸屬與閘的 in-flight／generation 水位。
    fun persistLaunchState() {
        sttLaunchSnapshot = SttLaunchState(
            slotBusy = slotBusy.toList(),
            slotGen = slotGen.toList(),
            admitGeneration = sttArbiter.currentGeneration,
            inFlightName = sttArbiter.currentInFlight?.name,
            admittedAtNanos = sttArbiter.admittedAtNanosSnapshot,
        )
    }
    var userVoiceArmed by remember { mutableStateOf(false) }

    // 使用者明確觸發的硬重設（語音楔住恢復鈕）：清空 slot 表與閘、保留 generation 水位與
    // 一次性 pending arm（INITIAL／RETRIGGER 由宿主持有，不得被本函式靜默丟棄）；mic（USER）
    // 一次性 arm 由使用者明確放棄。重設遞增 resetEpoch：重設後的 launcher 以新世代 key
    // （"stt-<screenId>-slotN-<epoch>"，screenId 為 per-entry identity）於重組時同步註冊，重設前的 launcher 在其 onDispose 中同步 unregister——
    // 依 androidx.activity.result 契約，in-flight unregister 保留 rc→key 綁定但移除 callback，
    // 重設前 launch 的晚到結果自此在 registry 邊界永不投遞（late result 停靠 pendingResults，鍵不再註冊），
    // 無法冒充重設後的 launch、也無法提早 prefill／結算其閘；slot/gen 表（gen==0L fail-closed）
    // 僅作為第二層縱深防禦。sttSettleSeq 遞增使 pending arm 確定性續行裁決。
    fun resetVoiceSttCluster() {
        slotBusy[0] = false
        slotBusy[1] = false
        slotGen[0] = 0L
        slotGen[1] = 0L
        sttArbiter.hardReset()
        sttArbiterState = null
        userVoiceArmed = false
        sttSettleSeq += 1
        resetEpoch += 1
        persistLaunchState()
        voiceErrors.clearSttError()
        showVoiceReset = false
    }
    fun handleSttResult(slot: Int, result: ActivityResult) {
        // STT 結果回呼一律先結算單飛閘：以該 slot 登記的 admit generation 配對
        // （onResultSettled(gen) 對 watchdog 後承認的 successor gen 是 no-op），無論
        // resultCode／降級路徑，至多一次 onResultSettled(gen)。鏡像同步自閘（不是無條件清空）
        // ——stale callback 不得在 successor 仍持有閘時把鏡像清掉；release 簿記不因下方 stale
        // 判定而跳過。本 slot 隨即釋放。
        val gen = slotGen[slot]
        // fail-closed：gen == 0L 代表這枚結果無法歸屬於任何 launch——registry 可能在 activity
        // 重建後重新投遞重建前的結果，而重建後的 slot 表是全新的（slotGen 全 0L）。production
        // 每枚 launch 都登記非 0 generation，故 0L 一律不可歸屬：直接丟棄，不做任何簿記
        // （不 prefill、不動 slot、不結算閘），避免污染重建後的新狀態。
        if (isUnattributedVoiceResult(gen)) {
            Log.w(TAG, "dropping unattributed STT result (slot=$slot): post-recreation delivery")
            return
        }
        // 「gate 仍歸屬本次啟動」必須在下方 settle 之前擷取：新鮮結果由 settle 釋放 slot
        // （currentInFlight 隨即為 null），若於 bookkeeping 後才讀取，會把新鮮結果誤判為
        // stale 而不寫入草稿；這正是 stale（watchdog 釋放、無 successor、generation 不動）
        // 與 fresh（settle 剛釋放）的分歧點。
        val stillAdmitting = !isStaleVoiceResult(gen, sttArbiter.currentGeneration, sttArbiter.currentInFlight)
        sttArbiter.onResultSettled(gen)
        slotBusy[slot] = false
        slotGen[slot] = 0L
        sttArbiterState = sttArbiter.currentInFlight
        sttSettleSeq += 1
        persistLaunchState()
        // stale 判定（isStaleVoiceResult，VoiceSttArbiter.kt）：slot generation 落後於當代
        // admitGeneration（successor 已承認超越），或回呼進入當下 gate 已不歸屬本次啟動
        // （watchdog 釋放後無 successor——generation 不動、slot 已空）。gen == 0L 已於函式頂端
        // fail-closed 丟棄（重建後 registry 重新投遞重建前結果、slot 表全新時），此處必非 0L。
        // 注意層次：重設（「重設語音辨識」）之後，重設前 rc 的晚到結果在 registry 邊界永不投遞
        // （late result 停靠 pendingResults，鍵不再註冊；pre-reset launcher unregister 後 callback 移除），根本不會進入本函式；gen == 0L 的
        // 頂端 fail-closed 僅為第二層縱深防禦。跨 entry 的晚到結果同理在 registry 邊界終結：
        // key 內嵌 per-entry screenId，已 popUpTo 銷毀 entry 的 outstanding result 永遠不會投遞進
        // 新 entry 的 callback，根本不會進入本函式。（前提：CHAT entry 永不以 restoreState=true 還原；見 screenId KDoc／docs）
        // STALE-SPECIMENS: stale-result 案例（S3-style watchdog 驅逐、successor 超越、gen==0L fail-closed drop）
        // 釘在 VoiceSttArbiterTest／isStaleVoiceResult 純述詞層／SttLaunchStateTest；Compose 層 stale 模擬亦可經
        // LocalVoiceSttArbiterClock 注入假時鐘（見 ChatScreenVoiceLaunchTest）。
        if (!stillAdmitting) {
            // stale result from a watchdog-evicted launch: release bookkeeping happened already
            // (slot freed above); never let a 2-minute-old transcript overwrite the draft.
            return
        }
        // TODO(#137): implement explicit auto-send handling before enabling; do not discard prefill results.
        // Runtime contract today: voice results are prefill-only. Even if AUTO_SEND_AFTER_VOICE is
        // flipped prematurely, never silently drop STT results—prefill/error handling below must still run.
        val autoSendAfterVoice = AssistantVoice.AUTO_SEND_AFTER_VOICE
        if (result.resultCode == Activity.RESULT_OK) {
            // Treat extras retrieval + candidate extraction/validation + resolve as one untrusted boundary.
            // Handles wrong container/element types (including generic-erasure/ClassCastException) and
            // parcel errors (e.g. BadParcelableException) without crashing, prefilling, or sending.
            val text = try {
                val raw = result.data?.extras?.get(RecognizerIntent.EXTRA_RESULTS)
                AssistantVoice.resolveVoicePrefill(raw as? List<*>)
            } catch (_: Exception) {
                null
            }
            if (text != null) {
                voiceErrors.onSttSuccess()
                viewModel.prefill(text)
                if (autoSendAfterVoice) {
                    // TODO(#137): wire explicit user-confirmed send here; intentionally no-op for now.
                }
            } else {
                voiceErrors.onSttFailure(VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_EMPTY_RESULT))
            }
        }
        // 非 OK（使用者取消）維持手動輸入，不報錯。
    }
    val sttRegistry = LocalActivityResultRegistryOwner.current?.activityResultRegistry
        ?: error("No ActivityResultRegistryOwner was provided via LocalActivityResultRegistryOwner")
    // androidx.activity.compose（libs.versions.toml 聲明 1.10.1，atomic-group 衝突解析實際採用 1.13.0；bytecode 已驗證）的 rememberLauncherForActivityResult 的手工等價物，差異：
    // (1) key 由 per-entry screenId＋resetEpoch 推導（entry-scoped identity＋每枚重設世代）——重設後重設前 launch 立即與 registry 斷鏈；
    // (2) callbacks 經 rememberUpdatedState 委派當代 handleSttResult（與 androidx 相同）。
    val latestHandleSttResult by rememberUpdatedState { slot: Int, result: ActivityResult -> handleSttResult(slot, result) }
    val slot0LauncherHolder = remember { object { var launcher: ActivityResultLauncher<Intent>? = null } }
    val slot1LauncherHolder = remember { object { var launcher: ActivityResultLauncher<Intent>? = null } }
    val slot0Key = "stt-$screenId-slot0-$resetEpoch"
    val slot1Key = "stt-$screenId-slot1-$resetEpoch"
    DisposableEffect(slot0Key, sttRegistry) {
        val launcher = sttRegistry.register(
            slot0Key,
            ActivityResultContracts.StartActivityForResult(),
            ActivityResultCallback { result -> latestHandleSttResult(0, result) },
        )
        slot0LauncherHolder.launcher = launcher
        onDispose {
            launcher.unregister()
            slot0LauncherHolder.launcher = null
        }
    }
    DisposableEffect(slot1Key, sttRegistry) {
        val launcher = sttRegistry.register(
            slot1Key,
            ActivityResultContracts.StartActivityForResult(),
            ActivityResultCallback { result -> latestHandleSttResult(1, result) },
        )
        slot1LauncherHolder.launcher = launcher
        onDispose {
            launcher.unregister()
            slot1LauncherHolder.launcher = null
        }
    }
    // true iff launchSystemStt() invoked the chosen slot launcher without throwing (degrade paths and exceptions return false).
    fun launchSystemStt(): Boolean {
        val probe = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        // B1：探測必須帶 MATCH_DEFAULT_ONLY，只認「預設可處理」的辨識服務，
        // 避免列出無 DEFAULT category 的非可啟動目標；action 與 manifest
        // <queries> 同為 RECOGNIZE_SPEECH（見 VoiceStt.ACTION）。
        // 探測本身拋例外（SecurityException 等）：視為無服務，落入既有降級路徑。
        val hasService = try {
            context.packageManager.queryIntentActivities(
                probe,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            ).isNotEmpty()
        } catch (_: Exception) {
            false
        }
        val reason = VoiceStt.degradeReason(hasRecognitionService = hasService, inputEnabled = inputEnabled)
        if (reason != null) {
            val detail = if (reason == DenyReason.USER_DISABLED) {
                VoiceStt.DETAIL_SWITCH_OFF
            } else {
                VoiceStt.DETAIL_NO_SERVICE
            }
            voiceErrors.onSttFailure(VoiceStt.fallbackMessage(reason, detail))
            // Not a wedge: no reset affordance for SWITCH_OFF / no-service degrades.
            showVoiceReset = false
            return false
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, VoiceStt.LANGUAGE)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, VoiceStt.MAX_RESULTS)
        }
        // 每次 launch 歸屬一個回呼 slot，兩 slot 結果歸屬精確、不受回傳順序影響；2 分鐘無結果由
        // watchdog 釋放單飛閘（不消費 arm），wedge slot 仍於其 late return 時釋放。
        val slotIndex = if (!slotBusy[0]) 0 else if (!slotBusy[1]) 1 else -1
        if (slotIndex == -1) {
            // 兩 slot 全忙：recognizer 服務在、但回不來（never-callback 懸掛）。
            // 僅作為與呼叫端預檢之間的競態安全網；不消費任何一次性旗標／token。
            voiceErrors.onSttFailure(VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_RECOGNIZER_UNRESPONSIVE))
            showVoiceReset = true
            return false
        }
        slotBusy[slotIndex] = true
        slotGen[slotIndex] = sttArbiter.currentGeneration
        showVoiceReset = false
        persistLaunchState()
        return try {
            val launcher = if (slotIndex == 0) slot0LauncherHolder.launcher else slot1LauncherHolder.launcher
            if (launcher == null) {
                // reset 進行中途狀態：記錄後走 checkNotNull → catch 的既有降級路徑（釋放 slot、報錯、不 wedge）。
                Log.w(TAG, "STT launcher not mounted (reset in flight); failing closed")
            }
            checkNotNull(launcher) { "STT launcher not mounted (reset in flight)" }
            launcher.launch(intent)
            true
        } catch (t: Exception) {
            Log.w(TAG, "STT launch failed; degrading", t)
            slotBusy[slotIndex] = false
            slotGen[slotIndex] = 0L
            persistLaunchState()
            voiceErrors.onSttFailure(VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_NO_SERVICE))
            // Wedge flag is unrelated to a launch exception; do not show the reset affordance.
            showVoiceReset = false
            false
        }
    }
    // C2 自動喚起／retrigger／mic：所有 STT 啟動統一經單一決策效應（F1/P2 單飛閘）。
    // 優先序由 arbiter 決定（INITIAL > RETRIGGER > USER），switches 落定前不消費不啟動；
    // 先消費再啟動，消費失敗（如 retrigger TTL／provider 競態）則放棄啟動並釋放閘，
    // 交出下一個已 arm 的 arm；降級（launch 回 false）亦在同一幀繼續裁決下一 arm。
    // settle（STT result callback 第一行 onResultSettled）經 sttArbiterState 重觸發本效應，
    // 確定性地續行下一 arm。效應內以本地旗標影子參數，避免同幀內重複消費 stale 快照。
    LaunchedEffect(voiceInputRequested, voiceRetriggerToken, voiceSwitchesLoaded, sttArbiterState, userVoiceArmed, sttSettleSeq) {
        var initialRequested = voiceInputRequested
        var retriggerArmed = isRetriggerArmed(voiceRetriggerToken)
        var userArmed = userVoiceArmed
        while (true) {
            when (val decision = sttArbiter.decide(
                initialRequested = initialRequested,
                retriggerArmed = retriggerArmed,
                userRequested = userArmed,
                switchesLoaded = voiceSwitchesLoaded,
            )) {
                VoiceSttDecision.Wait, VoiceSttDecision.Idle -> {
                    // 閘表可能因 watchdog 驅逐而改變（decide 內部清除 in-flight）：收尾前落一次快照。
                    persistLaunchState()
                    break
                }
                is VoiceSttDecision.Admit -> {
                    val arm = decision.arm
                    // 任何例外（消費回呼／launchSystemStt 內部未捕捉等，含 CancellationException）
                    // 都必須先釋放單飛閘並清同影子區域變數，否則閘永久楔住；釋放後
                    // CancellationException 原樣 rethrow，其餘降級為 STT 失敗提示（見 catch 區塊）。
                    try {
                        when (arm) {
                            VoiceSttArmKind.INITIAL -> {
                                // reserve-before-consume：兩 slot 都被 never-callback 的 recognizer 佔用時，
                                // 這枚 arm 沒有可歸屬的回呼 slot——先消費會把 voiceInputRequested 一次性旗標
                                // 燒進 no-op，且使用者毫無感知。此處釋放閘、提示、保持 arm 未消費
                                // （下次狀態翻轉時重試），並 break 跳出 while——同一幀續行裁決會以同一 arm
                                // 自旋，故旗標必須留存、由下一次 decide 重試。
                                if (!hasFreeSttSlot()) {
                                    voiceErrors.onSttFailure(VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_RECOGNIZER_UNRESPONSIVE))
                                    showVoiceReset = true
                                    sttArbiter.onLaunchReported(VoiceSttArmKind.INITIAL, launched = false, sttArbiter.currentGeneration)
                                    sttArbiterState = sttArbiter.currentInFlight
                                    persistLaunchState()
                                    break
                                }
                                initialRequested = false
                                // 先消費再啟動：啟動後重組不再重放；開關關閉或 loadFailed 時，
                                // launchSystemStt() 內 cleanly 降級為 USER_DISABLED／NO_PRIVILEGE。
                                onVoiceInputConsumed()
                                val launched = launchSystemStt()
                                sttArbiter.onLaunchReported(VoiceSttArmKind.INITIAL, launched, sttArbiter.currentGeneration)
                                if (launched) {
                                    sttArbiterState = VoiceSttArmKind.INITIAL
                                }
                            }
                            VoiceSttArmKind.RETRIGGER -> {
                                // reserve-before-consume：無 slot 可派時不呼叫 onVoiceRetriggerConsumed，
                                // retrigger token 保留至 TTL 到期前仍可重試；釋放閘並 break 跳出 while
                                // （同一幀續裁決會自旋），arm 保持未消費留待下次狀態翻轉重試。
                                if (!hasFreeSttSlot()) {
                                    voiceErrors.onSttFailure(VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_RECOGNIZER_UNRESPONSIVE))
                                    showVoiceReset = true
                                    sttArbiter.onLaunchReported(VoiceSttArmKind.RETRIGGER, launched = false, sttArbiter.currentGeneration)
                                    sttArbiterState = sttArbiter.currentInFlight
                                    persistLaunchState()
                                    break
                                }
                                retriggerArmed = false
                                if (onVoiceRetriggerConsumed(voiceRetriggerToken)) {
                                    val launched = launchSystemStt()
                                    sttArbiter.onLaunchReported(VoiceSttArmKind.RETRIGGER, launched, sttArbiter.currentGeneration)
                                    if (launched) {
                                        sttArbiterState = VoiceSttArmKind.RETRIGGER
                                    }
                                } else {
                                    // Consume rejected (TTL／provider race)：不啟動，立即釋放閘。
                                    sttArbiter.onLaunchReported(VoiceSttArmKind.RETRIGGER, launched = false, sttArbiter.currentGeneration)
                                }
                            }
                            VoiceSttArmKind.USER -> {
                                // reserve-before-consume：無 slot 可派時不清 userVoiceArmed，
                                // mic arm 保持一次態留待下次狀態翻轉重試；釋放閘並 break 跳出 while
                                // （同一幀續裁決會自旋）。
                                if (!hasFreeSttSlot()) {
                                    voiceErrors.onSttFailure(VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_RECOGNIZER_UNRESPONSIVE))
                                    showVoiceReset = true
                                    sttArbiter.onLaunchReported(VoiceSttArmKind.USER, launched = false, sttArbiter.currentGeneration)
                                    sttArbiterState = sttArbiter.currentInFlight
                                    persistLaunchState()
                                    break
                                }
                                userArmed = false
                                userVoiceArmed = false
                                val launched = launchSystemStt()
                                sttArbiter.onLaunchReported(VoiceSttArmKind.USER, launched, sttArbiter.currentGeneration)
                                if (launched) {
                                    sttArbiterState = VoiceSttArmKind.USER
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        // 無論例外類型（含 CancellationException）都先歸零影子變數、
                        // 釋放閘並同步鏡像——閘必須先釋放，否則殘留 inFlight 會讓
                        // 整個畫面工作階段的語音功能靜默死鎖。釋放後才做例外判別：
                        // CancellationException／Error 原樣 rethrow（不得吞）；其餘未預期
                        // throwable 記錄 Log.w（只記 throwable 本身，不含 payload）並
                        // 降級為 STT 失敗提示，避免直接讓整個畫面崩潰。
                        when (arm) {
                            VoiceSttArmKind.INITIAL -> initialRequested = false
                            VoiceSttArmKind.RETRIGGER -> retriggerArmed = false
                            VoiceSttArmKind.USER -> {
                                userArmed = false
                                userVoiceArmed = false
                            }
                        }
                        sttArbiter.onLaunchReported(arm, launched = false, sttArbiter.currentGeneration)
                        sttArbiterState = sttArbiter.currentInFlight
                        if (t is CancellationException || t is Error) {
                            // rethrow 路徑跳過下方的統一快照：閘剛釋放，鏡像與快照必須一致。
                            persistLaunchState()
                            throw t
                        }
                        Log.w(TAG, "unexpected throwable in STT arm; gate released, degrading", t)
                        voiceErrors.onSttFailure(VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_NO_SERVICE))
                        showVoiceReset = false
                    }
                    // admit／false 路徑（含 watchdog 驅逐）收尾：落快照供 activity 重建還原。
                    persistLaunchState()
                }
            }
        }
        // 保持鏡像與閘一致（涵蓋完全降級或 watchdog 釋放後 currentInFlight == null 的收尾）。
        sttArbiterState = sttArbiter.currentInFlight
    }
    // watchdog 啟動後的主動喚起：RecognizerActivity 懸掛時 recognizer 永不回呼，
    // 單靠停用的單飛閘會永久卡在 inFlight。到期後把鏡像落下 (null) 以觸發一次 re-decide；
    // decide() 會在 slot 到期移除後放行下一個 armed arm。
    LaunchedEffect(sttArbiterState) {
        if (sttArbiterState != null) {
            delay(VoiceSttArbiter.RECOGNIZER_WATCHDOG_NANOS / 1_000_000L + 1_000L)
            sttArbiterState = null
        }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length, session.status, contextOmittedCount) {
        if (messages.isNotEmpty()) {
            try {
                // R5-5: the context-omission badge (when visible) occupies
                // list index 0, so the last message sits at messages.size,
                // not messages.size - 1.
                listState.scrollToItem(if (contextOmittedCount > 0) messages.size else messages.size - 1)
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
                // R5-5: persistent context-omission badge pinned to the top
                // of the history list (N > 0 only). bodySmall +
                // onSurfaceVariant, same as the pending-steer badge below
                // the list. N counts rows absent from the outgoing request
                // (window + send-time trim legs).
                if (contextOmittedCount > 0) {
                    item(key = "context-omission-badge") {
                        Text(
                            text = "有 $contextOmittedCount 則較早歷史未送出模型",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics {
                                liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
                            },
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
                        val streamingStatus = stringResource(R.string.chat_streaming_status)
                        val streamingLabel = stringResource(R.string.chat_streaming_label)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
                                    contentDescription = streamingStatus
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
                                        modifier = Modifier
                                            .padding(2.dp)
                                            .semantics {
                                                contentDescription = streamingStatus
                                            },
                                        strokeWidth = 2.dp,
                                    )
                                    Text(streamingLabel)
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
                if (showVoiceReset) {
                    // 無 slot 可派（兩 slot 皆被懸掛 recognizer 佔用）時附重設鈕：使用者觸發的
                    // 硬重設——清空 slot 表與閘、保留 generation 水位、遞增 resetEpoch（重設前
                    // launcher 於重組時同步 unregister，其晚到結果在 registry 邊界永不投遞）。語義是
                    // 「放棄未返回的辨識結果（晚到者在 registry 邊界永不投遞，slot/gen 表為第二層
                    // 縱深防禦，見 resetVoiceSttCluster 註解）」，不是「修好卡住的辨識器」。
                    // 每個 entry 各自 screenId，重設世代為 per-entry 推導。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                        )
                        TextButton(onClick = { resetVoiceSttCluster() }) { Text("重設語音辨識") }
                    }
                } else {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
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
                    // F1/P2：mic 亦入單飛閘（ArmKind.USER）；閘忙時點按佇列一次，
                    // 於 in-flight STT settle 後續行（同幀不疊加 RecognizerIntent）。
                    if (inputEnabled) {
                        IconButton(onClick = { userVoiceArmed = true }) {
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
                        // Streaming cursor "▍" is visual only: announce the plain text
                        // so TalkBack skips the cursor glyph.
                        modifier = Modifier.semantics { contentDescription = displayText },
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
                    // TTS pipeline (P2-3): strip thinking (fail-closed) then verbalize
                    // (drop fence delimiters/tool-marker lines, unwrap inline code).
                    // P2-2 documented divergence: English prose resembling a malformed
                    // opener (e.g. `I <think that 5 > 3 is true>`) is absorbed to EOF
                    // here while display renders it verbatim — fail-closed prefers
                    // hiding a possible thought over speaking it.
                    val speakableText = remember(displayText) { ChatMarkdownParser.speakableText(displayText) }
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
