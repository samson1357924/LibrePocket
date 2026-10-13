package dev.librepocket.agent.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import dev.librepocket.agent.ui.setup.EndpointGate
import dev.librepocket.agent.ui.setup.PendingTeardownState
import dev.librepocket.session.SessionMeta
import kotlinx.coroutines.launch

/**
 * 啟動 gate 接線單一來源（Stage B：R10 Finding 2 P2/UX review REQUEST CHANGES）。
 *
 * [MainScreen] 與接線測試共用此 host：`navLoading` 合成（collect→
 * [startupEffectiveLoading]）、雙導航守衛（[shouldNavigateToSetup]／
 * [shouldNavigateToSettings]，同 keys、同參數）、全屏 spinner、drawer 手勢與
 * 選單鍵門禁（`hasEndpoint || logoutGuardError != null`）皆只在此實現。
 * 測試直接測此 composable，禁止在測試內重抄 [LaunchedEffect]——否則邏輯拷貝
 * 與生產接線脫鉤（mutation 改回 `gateLoading`、`&&` 或漏改一處不會紅）。
 *
 * 主體由 `MainScreen` 逐位搬入（行為一致）；唯一新增是 spinner 的
 * `testTag("startupLoading")`（接線測試斷言用）。
 *
 * 非 gate 邏輯不在此：entry 分發、replay 觸發、語音／提權／會話狀態仍歸
 * `MainScreen`（呼叫方經 [chatContent]／[settingsContent]／[setupContent] 與
 * 回調傳入）。
 *
 * Stage D2（R12 Finding 2 (b)）：派生恢復態內聚單一來源——有效碼
 * `effectiveLogoutRecovery(logoutGuardError, logoutErrorSourceJournal, pendingTeardown, logoutInFlight).effectiveCode`
 * （見 `NavGuard.kt`；Stage E 四態：有碼沿用，無碼＋KnownPending＋非執行中 →
 * `LOGOUT_UNKNOWN`，無碼＋Unknown／Malformed＋非執行中 → `LOGOUT_READ_RETRY`；
 * Stage F 四參抑制 stale 回落，Stage G F1 異源 `J_C` 攜 `LOGOUT_UNKNOWN`＋`J_C`
 * （不再 fresh 回退），見該函數 KDoc 真值表）
 * 代入雙守衛（nav 抑制＋CHAT→SETTINGS）、drawer 手勢與選單鍵門禁（恢復態啟用）；
 * `MainScreen` 另以同一純函數派生 `SettingsScreen` `recoveryCode`
 * （NoEndpoint 顯示 `LOGOUT_UNKNOWN` 卡＋重試清除，無 dismiss）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StartupGateHost(
    gate: EndpointGate,
    startupReconcilePending: Boolean,
    /** 原始 `form.errorCode`（未過濾；`LOGOUT_*` 過濾在此內聚）。 */
    errorCode: String?,
    logoutInFlight: Boolean,
    navController: NavHostController,
    appName: String,
    sessions: List<SessionMeta> = emptyList(),
    currentSessionId: String? = null,
    onNewChat: () -> Unit = {},
    onOpenSession: (String) -> Unit = {},
    onEditEndpoint: () -> Unit = {},
    onDrawerOpened: () -> Unit = {},
    chatContent: @Composable (PaddingValues) -> Unit,
    settingsContent: @Composable (PaddingValues) -> Unit,
    setupContent: @Composable (PaddingValues, Boolean) -> Unit,
    /**
     * Stage E 四態 journal domain（`SetupViewModel.pendingTeardown`
     * 收集值；[PendingTeardownState.Absent]＝確無 journal，
     * [PendingTeardownState.KnownPending]＝已確認存在，
     * [PendingTeardownState.UnknownReadFailure]／
     * [PendingTeardownState.MalformedPending]＝未確認）。僅經
     * [effectiveLogoutRecovery] 派生（confirmed 走 retained 重試清除，
     * unconfirmed 走重試讀取）；身份不作清除依據。
     */
    pendingTeardown: PendingTeardownState = PendingTeardownState.Absent,
    /**
     * Stage F stale 抑制標籤（`form.logoutErrorSourceJournal` 原字串；null 表無源）。
     *
     * 與 [errorCode] 同源傳入 [effectiveLogoutRecovery] 派生：同源 retained
     * 沿用，`Absent`／異源 `J_C` 抑制回落（純隱藏），未確認保留舊碼；真正清卡靠
     * `retryPendingDelete` 終端。舊三參呼叫（tag null）行為不變。
     */
    logoutErrorSourceJournal: String? = null,
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val hasEndpoint = gate is EndpointGate.Ready
    // Stage R2: ReadFailure (unconfirmed strict/vault read) suppresses nav
    // exactly like Loading — never decide Setup/Settings on an unknown.
    val gateLoading = gate is EndpointGate.Loading || gate is EndpointGate.ReadFailure
    // Stage B（R10 Finding 2 P2/UX）：啟動 replay 落定前視同 loading——gate
    // 已折疊 NoEndpoint 但落碼未發生時，兩守衛不決策（不先跳 Setup）、全屏
    // spinner 覆蓋（不暴露 NavHost(CHAT) 空態 FOUC）。條件見
    // startupEffectiveLoading（NavGuard.kt）；兩守衛簽名與語義不變。
    val navLoading = startupEffectiveLoading(gateLoading, startupReconcilePending)
    // 登出失敗 Settings UI 可見（Re-review #7 Finding 2 階段2）：false 分支不導航
    // 不清空，只靠 form.errorCode（LOGOUT_*）驅動 Settings 失敗卡；簽名不改。
    val logoutGuardError = errorCode?.takeIf { it.startsWith("LOGOUT_") }
    val logoutGuardBusy = logoutInFlight
    // Stage E 四態派生恢復態（單一來源）：有碼沿用；無碼＋KnownPending＋非執行中 →
    // LOGOUT_UNKNOWN（重試清除）；無碼＋Unknown／Malformed＋非執行中 →
    // LOGOUT_READ_RETRY（重試讀取 only）；Absent／在途 → null（不決策）。
    // 有效值代入雙守衛與 drawer／選單。
    // Stage F：改傳 tag（四參抑制 stale 回落；pure 只隱藏，真清卡靠 retry 終端）。
    // Stage G F1：改用攜身份派生 `effectiveLogoutRecovery` 的有效碼（異源 J_C 由
    // 隱藏改為攜 `LOGOUT_UNKNOWN`＋J_C，不再 fresh 回退；同源／Absent／未確認／
    // STALE／在途語義與舊四參一致，見 NavGuard 真值表）。
    val effectiveGuardError = effectiveLogoutRecovery(
        logoutGuardError,
        logoutErrorSourceJournal,
        pendingTeardown = pendingTeardown,
        logoutInFlight = logoutInFlight,
    ).effectiveCode

    // Hard gate: no usable endpoint -> setup (and back again after save/logout).
    // P2/BLOCK（Re-review #8 Finding 2 階段2）：有未恢復 LOGOUT_* 或 logoutInFlight
    // 時不強制跳（留在 SETTINGS 可恢復；STALE 待 dismiss 亦攔截）。條件見
    // shouldNavigateToSetup 純函數（NavGuard.kt，真值表單測）。
    LaunchedEffect(hasEndpoint, navLoading, currentRoute, effectiveGuardError, logoutGuardBusy) {
        if (!shouldNavigateToSetup(hasEndpoint, navLoading, currentRoute, effectiveGuardError, logoutGuardBusy)) {
            return@LaunchedEffect
        }
        navController.navigate(Routes.SETUP) {
            popUpTo(Routes.CHAT) { inclusive = true }
            launchSingleTop = true
        }
    }

    // 恢復導向（Stage 3 P2，可達性決策 (a)）：啟動 replay 失敗（LOGOUT_* +
    // NoEndpoint）時把 CHAT 自動導向 SETTINGS 恢復卡，而非留在無端點 CHAT 空態；
    // 與 SETUP 硬守衛互斥（有碼走此分支，無碼走 SETUP），條件見
    // shouldNavigateToSettings 純函數。Stage B：同以 navLoading（含落定閘）
    // 守門，落定後做一次完整資訊決策（直達 SETTINGS，無 SETUP 中間態）。
    // Stage D2：有效碼含派生 LOGOUT_UNKNOWN（跨 VM journal），同走此分支。
    LaunchedEffect(hasEndpoint, navLoading, currentRoute, effectiveGuardError, logoutGuardBusy) {
        if (!shouldNavigateToSettings(hasEndpoint, navLoading, currentRoute, effectiveGuardError, logoutGuardBusy)) {
            return@LaunchedEffect
        }
        navController.navigate(Routes.SETTINGS) {
            popUpTo(Routes.CHAT) { inclusive = true }
            launchSingleTop = true
        }
    }

    // History list follows the drawer: refresh whenever it opens.
    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Open) {
            onDrawerOpened()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Stage 3 P2 (a)：恢復態（LOGOUT_* + NoEndpoint）啟用 drawer 手勢，
        // 否則停在 CHAT 的使用者到不了 SETTINGS 恢復卡。
        // Stage D2：有效碼含派生 LOGOUT_UNKNOWN（跨 VM journal），同啟用。
        gesturesEnabled = hasEndpoint || effectiveGuardError != null,
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
                        onNewChat()
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
                                    onOpenSession(meta.sessionId)
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
                        onEditEndpoint()
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
                            // Stage 3 P2 (a)：恢復態啟用選單鍵（同 drawer 手勢）。
                            // Stage D2：有效碼含派生 LOGOUT_UNKNOWN，同啟用。
                            onClick = { scope.launch { drawerState.open() } },
                            enabled = hasEndpoint || effectiveGuardError != null,
                        ) {
                            Icon(Icons.Filled.Menu, contentDescription = "選單")
                        }
                    },
                    actions = {
                        if (currentRoute == Routes.CHAT) {
                            IconButton(
                                onClick = onNewChat,
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = "新對話")
                            }
                        }
                    },
                )
            },
        ) { padding ->
            // Stage B：落定前亦全屏 spinner（navLoading 含落定閘），避免
            // NavHost(CHAT) 空態 FOUC。
            if (navLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.testTag("startupLoading"))
                }
            } else {
                NavHost(
                    navController = navController,
                    startDestination = Routes.CHAT,
                ) {
                    composable(Routes.CHAT) {
                        chatContent(padding)
                    }
                    composable(Routes.SETTINGS) {
                        settingsContent(padding)
                    }
                    composable(Routes.SETUP) {
                        setupContent(padding, !hasEndpoint)
                    }
                }
            }
        }
    }
}
