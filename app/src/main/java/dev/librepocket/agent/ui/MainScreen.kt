package dev.librepocket.agent.ui

import android.util.Log
import androidx.compose.foundation.layout.Box
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.librepocket.agent.R
import dev.librepocket.agent.ui.chat.ChatScreen
import dev.librepocket.agent.ui.chat.ChatViewModel
import dev.librepocket.agent.ui.chat.ChatViewModelFactory
import dev.librepocket.agent.ui.chat.SessionListViewModel
import dev.librepocket.agent.ui.chat.SessionListViewModelFactory
import dev.librepocket.agent.ui.settings.SettingsScreen
import dev.librepocket.agent.ui.setup.EndpointGate
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    sharedText: String? = null,
    onSharedConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val app = LocalContext.current.applicationContext as android.app.Application
    val chatViewModel: ChatViewModel = viewModel(factory = ChatViewModelFactory(app))
    val setupViewModel: SetupViewModel = viewModel(factory = SetupViewModelFactory(app))
    val sessionListViewModel: SessionListViewModel = viewModel(factory = SessionListViewModelFactory(app))
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val appName = stringResource(R.string.app_name)
    val gate by setupViewModel.gate.collectAsStateWithLifecycle()
    val hasEndpoint = gate is EndpointGate.Ready
    val gateLoading = gate is EndpointGate.Loading
    // 登出失敗 Settings UI 可見（Re-review #7 Finding 2 階段2）：false 分支不導航
    // 不清空，只靠 form.errorCode（LOGOUT_*）驅動 Settings 失敗卡；簽名不改。
    val setupForm by setupViewModel.form.collectAsStateWithLifecycle()
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

    // Hard gate: no usable endpoint -> setup (and back again after save/logout).
    LaunchedEffect(hasEndpoint, gateLoading, currentRoute) {
        if (gateLoading) return@LaunchedEffect
        if (!hasEndpoint && currentRoute != null && currentRoute != Routes.SETUP) {
            navController.navigate(Routes.SETUP) {
                popUpTo(Routes.CHAT) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // History list follows the drawer: refresh whenever it opens.
    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Open) {
            sessionListViewModel.refresh()
        }
    }

    // Edit-entry helper: prefill the form with the live endpoint (key stays
    // blank) so "edit" never shows another preset's defaults.
    fun goSetupForEdit() {
        val config = (gate as? EndpointGate.Ready)?.config
        if (config != null) setupViewModel.prefillForEdit(config)
        navController.navigate(Routes.SETUP) { launchSingleTop = true }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = hasEndpoint,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = appName,
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                NavigationDrawerItem(
                    label = { Text("新對話") },
                    selected = currentRoute == Routes.CHAT && currentSessionId == null,
                    onClick = {
                        chatViewModel.newChat()
                        sessionListViewModel.refresh()
                        navController.navigate(Routes.CHAT) {
                            popUpTo(Routes.CHAT) { inclusive = false }
                            launchSingleTop = true
                        }
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                )
                if (sessions.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = "歷史對話",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    sessions.take(20).forEach { meta ->
                        NavigationDrawerItem(
                            label = {
                                Text(
                                    text = meta.title.ifBlank { "(無標題)" },
                                    maxLines = 1,
                                )
                            },
                            selected = currentRoute == Routes.CHAT && currentSessionId == meta.sessionId,
                            onClick = {
                                scope.launch {
                                    chatViewModel.openSession(meta.sessionId)
                                    sessionListViewModel.refresh()
                                    navController.navigate(Routes.CHAT) {
                                        popUpTo(Routes.CHAT) { inclusive = false }
                                        launchSingleTop = true
                                    }
                                    drawerState.close()
                                }
                            },
                            icon = { Icon(Icons.Filled.ChatBubbleOutline, contentDescription = null) },
                        )
                    }
                }
                NavigationDrawerItem(
                    label = { Text("API 設定") },
                    selected = currentRoute == Routes.SETUP,
                    onClick = {
                        goSetupForEdit()
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Filled.Key, contentDescription = null) },
                )
                NavigationDrawerItem(
                    label = { Text("設定") },
                    selected = currentRoute == Routes.SETTINGS,
                    onClick = {
                        navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (currentRoute) {
                                Routes.SETTINGS -> "設定"
                                Routes.SETUP -> if (hasEndpoint) "編輯端點" else "設定 API 金鑰"
                                else -> appName
                            },
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                            enabled = hasEndpoint,
                        ) {
                            Icon(Icons.Filled.Menu, contentDescription = "選單")
                        }
                    },
                    actions = {
                        if (currentRoute == Routes.CHAT) {
                            IconButton(
                                onClick = {
                                    chatViewModel.newChat()
                                    sessionListViewModel.refresh()
                                },
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = "新對話")
                            }
                        }
                    },
                )
            },
        ) { padding ->
            if (gateLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                NavHost(
                    navController = navController,
                    startDestination = Routes.CHAT,
                ) {
                    composable(Routes.CHAT) {
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
                    }
                    composable(Routes.SETTINGS) {
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
                            logoutErrorCode = setupForm.errorCode?.takeIf { it.startsWith("LOGOUT_") },
                            logoutInFlight = setupForm.logoutInFlight,
                            onDismissLogoutError = { setupViewModel.clearLogoutError() },
                        )
                    }
                    composable(Routes.SETUP) {
                        SetupScreen(
                            padding = padding,
                            isFirstRun = !hasEndpoint,
                            onSaved = {
                                navController.navigate(Routes.CHAT) {
                                    popUpTo(Routes.SETUP) { inclusive = true }
                                    launchSingleTop = true
                                }
                            },
                            viewModel = setupViewModel,
                        )
                    }
                }
            }
        }
    }
}
