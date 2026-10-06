package dev.librepocket.agent.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.librepocket.agent.R
import dev.librepocket.agent.ui.chat.ChatScreen
import dev.librepocket.agent.ui.chat.ChatViewModel
import dev.librepocket.agent.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

object Routes {
    const val CHAT = "chat"
    const val SETTINGS = "settings"
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
    val chatViewModel: ChatViewModel = viewModel()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val appName = stringResource(R.string.app_name)

    LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank()) {
            chatViewModel.prefill(sharedText)
            onSharedConsumed()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = appName,
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                NavigationDrawerItem(
                    label = { Text("新對話") },
                    selected = currentRoute == Routes.CHAT,
                    onClick = {
                        chatViewModel.newChat()
                        navController.navigate(Routes.CHAT) {
                            popUpTo(Routes.CHAT) { inclusive = false }
                            launchSingleTop = true
                        }
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
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
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(if (currentRoute == Routes.SETTINGS) "設定" else appName)
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "選單")
                        }
                    },
                    actions = {
                        if (currentRoute != Routes.SETTINGS) {
                            IconButton(onClick = { chatViewModel.newChat() }) {
                                Icon(Icons.Filled.Add, contentDescription = "新對話")
                            }
                        }
                    },
                )
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Routes.CHAT,
            ) {
                composable(Routes.CHAT) {
                    ChatScreen(padding = padding, viewModel = chatViewModel)
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(padding = padding)
                }
            }
        }
    }
}
