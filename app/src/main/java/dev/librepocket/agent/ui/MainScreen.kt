package dev.librepocket.agent.ui

import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

object Routes {
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val SETUP = "setup"
}

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
    val sessions by sessionListViewModel.sessions.collectAsStateWithLifecycle()
    val currentSessionId by chatViewModel.currentSessionId.collectAsStateWithLifecycle()

    // Gate first, dispatch later: stash the normalized entry text until an
    // endpoint exists, then send-now when idle or steer when busy.
    val chatStatus by chatViewModel.sessionState.collectAsStateWithLifecycle()
    LaunchedEffect(sharedText, hasEndpoint, chatStatus.status) {
        if (!sharedText.isNullOrBlank() && hasEndpoint) {
            when (EntryDispatch.route(isBusy = chatStatus.status == ChatStatus.STREAMING)) {
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
                        navController.navigate(Routes.SETUP) { launchSingleTop = true }
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
                            onOpenSettings = {
                                navController.navigate(Routes.SETUP) { launchSingleTop = true }
                            },
                            onTurnFinished = { sessionListViewModel.refresh() },
                        )
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            padding = padding,
                            gate = gate,
                            onEditEndpoint = {
                                navController.navigate(Routes.SETUP) { launchSingleTop = true }
                            },
                            onLogout = { setupViewModel.logout() },
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
