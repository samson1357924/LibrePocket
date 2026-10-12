package dev.librepocket.agent.ui

import android.util.Log
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import dev.librepocket.agent.R
import dev.librepocket.agent.ui.chat.ChatScreen
import dev.librepocket.agent.ui.chat.ChatViewModel
import dev.librepocket.agent.ui.chat.ChatViewModelFactory
import dev.librepocket.agent.ui.chat.SessionListViewModel
import dev.librepocket.agent.ui.chat.SessionListViewModelFactory
import dev.librepocket.agent.ui.settings.SettingsScreen
import dev.librepocket.agent.ui.setup.EndpointGate
import dev.librepocket.agent.ui.setup.PendingDelete
import dev.librepocket.agent.ui.setup.SetupScreen
import dev.librepocket.agent.ui.setup.SetupViewModel
import dev.librepocket.agent.ui.setup.SetupViewModelFactory
import dev.librepocket.chat.ChatStatus
import dev.librepocket.entry.EntryDispatch
import dev.librepocket.entry.EntryRoute
import dev.librepocket.shell.PrivilegeBridgeState
import dev.librepocket.voice.VoiceSwitchStore
import dev.librepocket.voice.VoiceSwitchSnapshot
import dev.librepocket.voice.VoiceTools
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

object Routes {
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val SETUP = "setup"
}

// 語音持久化寫入失敗的可觀測性（仿 TurnController 類名字串 TAG 的 Log.w 先例）。
// R5-3 語義收斂：寫入失敗處理以 try/catch(Exception) 取代 runCatching（後者接
// Throwable）：Error 照常放行（不吞 VirtualMachineError 等），實務與舊語義無差別
//（DataStore 寫入只拋 Exception／CancellationException），CancellationException 照拋。
private const val TAG = "MainScreen"

@Composable
fun MainScreen(
    sharedText: String? = null,
    onSharedConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val app = LocalContext.current.applicationContext as android.app.Application
    val chatViewModel: ChatViewModel = viewModel(factory = ChatViewModelFactory(app))
    val setupViewModel: SetupViewModel = viewModel(factory = SetupViewModelFactory(app))
    val sessionListViewModel: SessionListViewModel = viewModel(factory = SessionListViewModelFactory(app))
    val appName = stringResource(R.string.app_name)
    val gate by setupViewModel.gate.collectAsStateWithLifecycle()
    val hasEndpoint = gate is EndpointGate.Ready
    // Stage B（R10 Finding 2 P2/UX）：啟動 replay 落定前視同 loading 的合成與
    // 守衛／spinner／drawer 接線只在 StartupGateHost 實現（單一來源，接線測直
    // 接測該 host）；此處只收集狀態並傳入，不重算、不重抄。
    val startupReconcilePending by setupViewModel.startupReconcilePending.collectAsStateWithLifecycle()
    // 登出失敗 Settings UI 可見（Re-review #7 Finding 2 階段2）：false 分支不導航
    // 不清空，只靠 form.errorCode（LOGOUT_*）驅動 Settings 失敗卡；簽名不改。
    val setupForm by setupViewModel.form.collectAsStateWithLifecycle()
    // Stage E 四態 journal domain：第二 VM 寫入的 journal 在此 VM 無碼時
    // 仍可觀測（KnownPending），供派生恢復態（見 effectiveLogoutRecovery）；
    // transient 讀失敗／毀損為未確認態（重試讀取），絕不誤報 fresh。
    val pendingTeardown by setupViewModel.pendingTeardown.collectAsStateWithLifecycle()
    val sessions by sessionListViewModel.sessions.collectAsStateWithLifecycle()
    val currentSessionId by chatViewModel.currentSessionId.collectAsStateWithLifecycle()

    // #3 語音開關真相源（VoiceSwitchStore，DataStore 持久化）：R4-2 三態快照
    // （loading／loaded／loadFailed）。UI 門禁 fail-closed：`!loaded || loadFailed`
    // 時傳全關 map（沿用 outputEnabled=false 同路徑：mic／朗讀入口隱藏、VoiceSpeaker
    // 不建構）；loadFailed 另經 voiceSwitchLoadFailed 在聊天頁顯示一行讀取問題提示；
    // tool 投影未來接線時以 VoiceTools.projectionSwitches 同一快照轉 map 為
    // ProjectionContext.userSwitches（失敗態視為關，同 key、同預設）。
    val voiceStore = remember(app) { VoiceSwitchStore(app) }
    // R5-1: remember + stateIn 快取，防重組重建 Flow 身分導致 collectAsStateWithLifecycle
    // 以 Flow 為 key 重收：裸 observeSwitches() 每次訂閱 onStart 先發 LOADING，
    // 重組即閃斷已載入設定、speaker 重建、朗讀中斷。stateIn 把上游轉為 composition
    // 綁定的單一 StateFlow（WhileSubscribed(5000) 快取 loaded 值撐過短暫無訂閱空窗）；
    // Store 側 onStart LOADING 保留給真正 cold start，不動生產語義。後人勿改回內聯
    // voiceStore.observeSwitches().collectAsStateWithLifecycle(...)。
    val switchesFlow = remember(voiceStore, scope) {
        voiceStore.observeSwitches().stateIn(scope, SharingStarted.WhileSubscribed(5000), VoiceSwitchSnapshot.LOADING)
    }
    val voiceSnapshot by switchesFlow.collectAsStateWithLifecycle()
    // R6-1: WhileSubscribed(5000) 逾時停上游後，重訂閱必經 onStart LOADING 首發射，
    // DataStore 第二次發射若延遲即出現 loaded→LOADING 空窗（閃斷＋speaker 重建）。
    // 顯示用值經 decideEffective 黏住最後有效快照：未知（LOADING）不覆蓋已知 loaded，
    // 明確 loadFailed 照常穿透。lastVoiceEffective 以 SideEffect 同步（初值 LOADING
    // 保持 fail-closed），避免在 remember 計算內寫 state。
    var lastVoiceEffective by remember { mutableStateOf(VoiceSwitchSnapshot.LOADING) }
    val voiceEffectiveSnapshot = remember(lastVoiceEffective, voiceSnapshot) {
        VoiceSwitchSnapshot.decideEffective(lastVoiceEffective, voiceSnapshot)
    }
    if (voiceEffectiveSnapshot != lastVoiceEffective) {
        SideEffect { lastVoiceEffective = voiceEffectiveSnapshot }
    }
    val voiceUiSwitches = if (voiceEffectiveSnapshot.loaded && !voiceEffectiveSnapshot.loadFailed) {
        voiceEffectiveSnapshot.switches
    } else {
        mapOf(VoiceTools.SWITCH_INPUT to false, VoiceTools.SWITCH_OUTPUT to false)
    }
    // R5-1: ack 同 pattern，只為穩定 Flow 身分（其上游重收本就只重發當前值，
    // 無 LOADING 閃斷）；初值 false 保持未確認語義。勿改回內聯。
    val ackFlow = remember(voiceStore, scope) {
        voiceStore.observeTtsAcknowledged().stateIn(scope, SharingStarted.WhileSubscribed(5000), false)
    }
    val ttsAcknowledged by ackFlow.collectAsStateWithLifecycle()

    // S3 提權橋產品態（BACKLOG D09）：審計表 + privilege_bridge 開關 +
    // 橋接授權三位一體；一鍵收回原子清表、關開關、撤授權（見 PrivilegeBridgeState）。
    val privilegeState = remember { PrivilegeBridgeState() }
    var privilegeAuditCount by remember { mutableIntStateOf(privilegeState.auditCount()) }

    // Gate first, dispatch later: stash the normalized entry text until an
    // endpoint exists, then send-now when idle or steer when busy.
    val chatStatus by chatViewModel.sessionState.collectAsStateWithLifecycle()
    LaunchedEffect(sharedText, hasEndpoint, chatStatus.status) {
        if (!sharedText.isNullOrBlank() && hasEndpoint) {
            val busy = chatStatus.status == ChatStatus.STREAMING ||
                chatStatus.status == ChatStatus.WAITING_STEERED
            when (EntryDispatch.route(isBusy = busy)) {
                EntryRoute.SendNow -> chatViewModel.sendDirect(sharedText!!)
                EntryRoute.QueueAsSteer -> chatViewModel.steer(sharedText!!)
            }
            onSharedConsumed()
        }
    }

    // Crash-resume (phase 1, Re-review #8 Finding 1 P1): a logout torn down
    // between the vault delete and the metadata clear restarts with a pending
    // journal (readers folded to NoEndpoint); replay it once at startup so the
    // teardown completes instead of stranding metadata. Stage 3 P2: the outcome
    // is stamped via onStartupReconcile (DELETE/UNKNOWN surface retryable codes
    // instead of silent discard); a failure keeps the journal for the next
    // logout retry, never crashes UI.
    LaunchedEffect(Unit) {
        setupViewModel.launchPendingDeleteReconcile { outcome, journal ->
            setupViewModel.onStartupReconcile(outcome, journal)
        }
    }

    // Edit-entry helper: prefill the form with the live endpoint (key stays
    // blank) so "edit" never shows another preset's defaults.
    fun goSetupForEdit() {
        val config = (gate as? EndpointGate.Ready)?.config
        if (config != null) setupViewModel.prefillForEdit(config)
        navController.navigate(Routes.SETUP) { launchSingleTop = true }
    }

    StartupGateHost(
        gate = gate,
        startupReconcilePending = startupReconcilePending,
        errorCode = setupForm.errorCode,
        logoutInFlight = setupForm.logoutInFlight,
        navController = navController,
        appName = appName,
        sessions = sessions,
        currentSessionId = currentSessionId,
        onNewChat = {
            chatViewModel.newChat()
            sessionListViewModel.refresh()
        },
        onOpenSession = { sessionId ->
            chatViewModel.openSession(sessionId)
            sessionListViewModel.refresh()
        },
        onEditEndpoint = { goSetupForEdit() },
        onDrawerOpened = { sessionListViewModel.refresh() },
        // Stage E：四態 journal 傳入 host 派生恢復態（單一來源純函數，見 NavGuard）。
        pendingTeardown = pendingTeardown,
        // Stage F：stale 抑制標籤同源傳入（四參派生；pure 只隱藏，真清卡靠 retry 終端）。
        logoutErrorSourceJournal = setupForm.logoutErrorSourceJournal,
        chatContent = { padding ->
            ChatScreen(
                padding = padding,
                viewModel = chatViewModel,
                onOpenSettings = { goSetupForEdit() },
                onTurnFinished = { sessionListViewModel.refresh() },
                userSwitches = voiceUiSwitches,
                voiceSwitchLoadFailed = voiceEffectiveSnapshot.loadFailed,
                ttsAcknowledged = ttsAcknowledged,
                onTtsAcknowledged = {
                    try {
                        voiceStore.acknowledgeTtsDisclosure()
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "voice tts ack persist failed", e)
                        false
                    }
                },
            )
        },
        settingsContent = { padding ->
            // Stage G F1 顯示身份即提交身份：此輪 composition 的派生快照即為
            // `SettingsScreen` 顯示的有效碼＋重試目標；`onRetryLogout` 直接讀此快照
            // 的 `retryJournal`（漂移後仍重試顯示的 `J_C`，終端快照比對即 `StaleGone`）。
            val displayedRecovery = effectiveLogoutRecovery(
                setupForm.errorCode?.takeIf { it.startsWith("LOGOUT_") },
                setupForm.logoutErrorSourceJournal,
                pendingTeardown = pendingTeardown,
                logoutInFlight = setupForm.logoutInFlight,
            )
            SettingsScreen(
                padding = padding,
                gate = gate,
                onEditEndpoint = { goSetupForEdit() },
                onLogout = {
                    setupViewModel.logout { success ->
                        if (success) {
                            chatViewModel.newChat()
                            sessionListViewModel.refresh()
                        }
                    }
                },
                onRevokePrivilege = {
                    privilegeState.revoke()
                    privilegeAuditCount = privilegeState.auditCount()
                },
                privilegeAuditCount = privilegeAuditCount,
                voiceInputEnabled = VoiceTools.inputEnabled(voiceEffectiveSnapshot),
                voiceOutputEnabled = VoiceTools.outputEnabled(voiceEffectiveSnapshot),
                voiceSwitchesEnabled = voiceEffectiveSnapshot.loaded && !voiceEffectiveSnapshot.loadFailed,
                voiceLoadFailed = voiceEffectiveSnapshot.loadFailed,
                onVoiceInputSwitch = { enabled ->
                    scope.launch {
                        try {
                            voiceStore.setInputEnabled(enabled)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "voice input switch persist failed", e)
                        }
                    }
                },
                onVoiceOutputSwitch = { enabled ->
                    scope.launch {
                        try {
                            voiceStore.setOutputEnabled(enabled)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "voice output switch persist failed", e)
                        }
                    }
                },
                // Stage E 四態派生恢復態（與 host 同一純函數單一來源）：無碼＋KnownPending
                // ＋非執行中 → LOGOUT_UNKNOWN（NoEndpoint 顯示 UNKNOWN 卡＋重試清除，
                // 無 dismiss）；無碼＋Unknown／Malformed＋非執行中 → LOGOUT_READ_RETRY
                // （重試讀取 only）；有碼沿用不動。Stage F 四參抑制 stale 回落
                // （同源沿用、Absent／異源 J_C 隱藏、未確認保留舊碼；STALE 恆顯；
                // 真正清卡靠 retry 終端，見 NavGuard 真值表）。
                // Stage G F1：派生攜帶有效身份——有效碼＋重試目標同源計算
                // （`effectiveLogoutRecovery`）：同源 `(code, J)`、異源 `J_C` 攜
                // `("LOGOUT_UNKNOWN", J_C)`（碼固定中性，不沿用舊 DELETE_FAILED）、
                // Absent 抑制 `(null, null)`、未確認保留 `(code, null)`、STALE 恆顯
                // `(STALE, null)`；`SettingsScreen` 收已派生值（有效碼＋重試身份顯示），
                // 重試 targeting 用顯示身份、提交仍快照比對（禁盲 replay）。
                // Safe reconcile 走重試清除入口（`retryPendingDelete`，同一
                // `runPendingDeleteLocked` 核心）；全新登出確認仍走 `onLogout`
                // fresh-only。
                logoutErrorCode = displayedRecovery.effectiveCode,
                logoutRetryJournal = displayedRecovery.retryJournal?.format(),
                logoutInFlight = setupForm.logoutInFlight,
                onDismissLogoutError = { setupViewModel.clearLogoutError() },
                // Stage G F1 重試 targeting（兩顆：Ready retained＋NoEndpoint retained）讀
                // 派生的 `retryJournal`（非 form tag）：`J`／`J_C` 非 null → 直接
                // `retryPendingDelete(target)`（顯示身份即提交身份，終端仍快照比對，
                // 漂移即 `StaleGone`，禁盲 replay）；`null`＋有碼 → 今日分流
                // （`form.tag.parse`：tag null／毀損 → 重試讀取；`ReadUnknown` 亦轉
                // 重試讀取）；`null`＋無碼 fresh → 不到此分支（新鮮確認鍵走 `onLogout`
                // 不變，見 `SettingsScreen` 按鍵分流）。
                // `Replayed(COMMITTED)` 比照登出成功清會話。
                onRetryLogout = {
                    // 讀顯示快照的 `retryJournal`（非 form tag；漂移後仍為顯示的 `J_C`）。
                    val target = displayedRecovery.retryJournal
                    if (target != null) {
                        setupViewModel.retryPendingDelete(target) { result ->
                            when (result) {
                                is SetupViewModel.RetryResult.Replayed -> {
                                    if (result.outcome == SetupViewModel.PendingDeleteOutcome.COMMITTED) {
                                        chatViewModel.newChat()
                                        sessionListViewModel.refresh()
                                    }
                                }
                                is SetupViewModel.RetryResult.StaleGone -> Unit
                                is SetupViewModel.RetryResult.ReadUnknown -> {
                                    setupViewModel.retryStartupReconcile { outcome, journal ->
                                        setupViewModel.onStartupReconcile(outcome, journal)
                                    }
                                }
                            }
                        }
                    } else if (displayedRecovery.effectiveCode != null) {
                        val tagRaw = setupViewModel.form.value.logoutErrorSourceJournal
                        val expected: PendingDelete? = try {
                            tagRaw?.let { PendingDelete.parse(it) }
                        } catch (_: Exception) {
                            null
                        }
                        if (expected == null) {
                            setupViewModel.retryStartupReconcile { outcome, journal ->
                                setupViewModel.onStartupReconcile(outcome, journal)
                            }
                        } else {
                            setupViewModel.retryPendingDelete(expected) { result ->
                                when (result) {
                                    is SetupViewModel.RetryResult.Replayed -> {
                                        if (result.outcome == SetupViewModel.PendingDeleteOutcome.COMMITTED) {
                                            chatViewModel.newChat()
                                            sessionListViewModel.refresh()
                                        }
                                    }
                                    is SetupViewModel.RetryResult.StaleGone -> Unit
                                    is SetupViewModel.RetryResult.ReadUnknown -> {
                                        setupViewModel.retryStartupReconcile { outcome, journal ->
                                            setupViewModel.onStartupReconcile(outcome, journal)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // fresh（Absent 抑制等派生 null）：重試鍵不可達（新鮮確認鍵走
                        // onLogout），此分支不觸任何副作用，防盲目回退全新 logout。
                    }
                },
                // Stage D1：中性碼重試讀取（reconcile-only，絕不調 logout；
                // enabled 綁 startupReconcilePending，見 SettingsScreen）。
                onRetryStartupRead = {
                    setupViewModel.retryStartupReconcile { outcome, journal ->
                        setupViewModel.onStartupReconcile(outcome, journal)
                    }
                },
                startupReconcilePending = startupReconcilePending,
            )
        },
        setupContent = { padding, isFirstRun ->
            SetupScreen(
                padding = padding,
                isFirstRun = isFirstRun,
                onSaved = {
                    navController.navigate(Routes.CHAT) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                viewModel = setupViewModel,
            )
        },
    )
}
