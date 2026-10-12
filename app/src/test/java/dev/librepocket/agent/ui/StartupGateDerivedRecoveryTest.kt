package dev.librepocket.agent.ui

import android.os.Bundle
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.librepocket.agent.ui.settings.SettingsScreen
import dev.librepocket.agent.ui.setup.EndpointGate
import dev.librepocket.agent.ui.setup.PendingDelete
import dev.librepocket.agent.ui.setup.setupErrorText
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage D2（R12 Finding 2 (b)(c)）Host 接線：派生恢復態。
 *
 * `pendingTeardown`（`SetupViewModel.pendingTeardown` 收集值）經
 * [effectiveLogoutGuardError] 派生 `LOGOUT_UNKNOWN`（無碼＋present＋非 inFlight）
 * 後，雙守衛（nav 抑制＋CHAT→SETTINGS）、drawer 手勢與選單鍵門禁皆啟用；
 * `SettingsScreen` 經同一純函數（`MainScreen` 側）得 `recoveryCode`，NoEndpoint
 * 顯示 UNKNOWN 卡＋重試清除、無 dismiss。inFlight 抑制派生（執行中不決策）。
 * spinner 語義不變（`startupReconcilePending` 驅動）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StartupGateDerivedRecoveryTest {

    private val journalB = PendingDelete("preset:openai", 1L, 1700000000001L)

    private class Harness {
        val gate = MutableStateFlow<EndpointGate>(EndpointGate.NoEndpoint)
        val pending = MutableStateFlow(false)
        val pendingTeardown = MutableStateFlow<PendingDelete?>(null)
        val error = MutableStateFlow<String?>(null)
        val inFlight = MutableStateFlow(false)
        var logoutCalls = 0
        var dismissCalls = 0
        lateinit var nav: NavHostController
        val visited = mutableListOf<String?>()
    }

    @Composable
    private fun Harness.Content() {
        nav = rememberNavController()
        val g by gate.collectAsState()
        val p by pending.collectAsState()
        val pt by pendingTeardown.collectAsState()
        val e by error.collectAsState()
        val b by inFlight.collectAsState()
        // 與 MainScreen 同一純函數單一來源：host 側 nav／drawer 用有效值；
        // Settings 側 recoveryCode 同以有效值傳入（生產 MainScreen 同式）。
        val effective = effectiveLogoutGuardError(
            e?.takeIf { it.startsWith("LOGOUT_") },
            pendingPresent = pt != null,
            logoutInFlight = b,
        )
        DisposableEffect(nav) {
            val listener = object : NavController.OnDestinationChangedListener {
                override fun onDestinationChanged(
                    controller: NavController,
                    destination: NavDestination,
                    arguments: Bundle?,
                ) {
                    visited.add(destination.route)
                }
            }
            nav.addOnDestinationChangedListener(listener)
            onDispose { nav.removeOnDestinationChangedListener(listener) }
        }
        StartupGateHost(
            gate = g,
            startupReconcilePending = p,
            errorCode = e,
            logoutInFlight = b,
            navController = nav,
            appName = "TestApp",
            pendingTeardown = pt,
            chatContent = { padding ->
                Text("CHAT_BODY", modifier = Modifier.padding(padding))
            },
            settingsContent = { padding ->
                SettingsScreen(
                    padding = padding,
                    gate = g,
                    onLogout = { logoutCalls++ },
                    logoutErrorCode = effective,
                    logoutInFlight = b,
                    onDismissLogoutError = {
                        dismissCalls++
                        error.value = null
                    },
                )
            },
            setupContent = { padding, _ ->
                Text("SETUP_BODY", modifier = Modifier.padding(padding))
            },
        )
    }

    private fun render(h: Harness) {
        composeRule.setContent { h.Content() }
        composeRule.waitForIdle()
    }

    @get:Rule
    val composeRule = createComposeRule()

    private fun currentRoute(h: Harness): String? {
        var route: String? = null
        composeRule.runOnIdle { route = h.nav.currentDestination?.route }
        return route
    }

    private fun awaitRoute(h: Harness, route: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            h.nav.currentDestination?.route == route
        }
    }

    @Test
    fun derivedPresent_steersChatToSettingsWithRecovery() {
        // 無碼＋J_B present＋非 inFlight → 有效 UNKNOWN：CHAT 直達 SETTINGS
        //（SETUP 從未訪問）、選單 enabled、恢復卡＋重試清除 displayed、無關閉。
        val h = Harness()
        h.pendingTeardown.value = journalB
        render(h)
        awaitRoute(h, Routes.SETTINGS)
        assertEquals(Routes.SETTINGS, currentRoute(h))
        composeRule.runOnIdle { assertTrue(h.visited.none { it == Routes.SETUP }) }
        composeRule.onNodeWithContentDescription("選單").assertIsEnabled()
        composeRule.onNodeWithText(setupErrorText("LOGOUT_UNKNOWN")).assertIsDisplayed()
        composeRule.onNodeWithText("重試清除").assertIsDisplayed()
        composeRule.onAllNodesWithText("關閉").assertCountEquals(0)
        composeRule.onAllNodesWithText("取消").assertCountEquals(0)
        composeRule.onAllNodesWithText("尚未設定 API 端點。").assertCountEquals(0)
        // 重試可達（safe reconcile 入口＝既有 onLogout）。
        composeRule.onNodeWithText("重試清除").performClick()
        composeRule.runOnIdle { assertEquals(1, h.logoutCalls) }
    }

    @Test
    fun derivedAbsent_goesSetup() {
        // 無 journal 常態：fresh 走 SETUP（無迴歸）。
        val h = Harness()
        render(h)
        awaitRoute(h, Routes.SETUP)
        assertEquals(Routes.SETUP, currentRoute(h))
        composeRule.onNodeWithText("SETUP_BODY").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("選單").assertIsNotEnabled()
    }

    @Test
    fun derivedSuppressedDuringInFlight() {
        // present＋inFlight → 有效 null：不決策（CHAT 空轉，無 SETUP／SETTINGS）、
        // 選單 disabled；inFlight 結束才導向 SETTINGS.
        val h = Harness()
        h.pendingTeardown.value = journalB
        h.inFlight.value = true
        render(h)
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(h.visited.none { it == Routes.SETUP })
            assertTrue(h.visited.none { it == Routes.SETTINGS })
        }
        composeRule.onNodeWithContentDescription("選單").assertIsNotEnabled()
        h.inFlight.value = false
        awaitRoute(h, Routes.SETTINGS)
        assertEquals(Routes.SETTINGS, currentRoute(h))
        composeRule.onNodeWithText(setupErrorText("LOGOUT_UNKNOWN")).assertIsDisplayed()
    }

    @Test
    fun drawerOpensToDerivedRecovery() {
        // SETUP 停留態切 present（跨 VM 寫入模擬）：兩守衛皆不重導（已在 SETUP），
        // 選單轉 enabled；drawer 開後點設定可達 UNKNOWN 恢復卡.
        val h = Harness()
        render(h)
        awaitRoute(h, Routes.SETUP)
        h.pendingTeardown.value = journalB
        composeRule.waitForIdle()
        assertEquals(Routes.SETUP, currentRoute(h))
        composeRule.onNodeWithContentDescription("選單").assertIsEnabled()
        composeRule.onNodeWithContentDescription("選單").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("設定").performClick()
        awaitRoute(h, Routes.SETTINGS)
        composeRule.onNodeWithText(setupErrorText("LOGOUT_UNKNOWN")).assertIsDisplayed()
        composeRule.onNodeWithText("重試清除").performClick()
        composeRule.runOnIdle { assertEquals(1, h.logoutCalls) }
    }

    @Test
    fun spinner_shownWhilePending_goneAfterSettle() {
        // spinner 語義不變：落定前全屏 spinner、守衛不決策；落定後派生直達 SETTINGS.
        val h = Harness()
        h.pending.value = true
        h.pendingTeardown.value = journalB
        render(h)
        composeRule.onNodeWithTag("startupLoading").assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(h.visited.none { it == Routes.SETUP })
            assertTrue(h.visited.none { it == Routes.SETTINGS })
        }
        h.pending.value = false
        awaitRoute(h, Routes.SETTINGS)
        composeRule.onAllNodesWithTag("startupLoading").assertCountEquals(0)
        composeRule.onNodeWithText(setupErrorText("LOGOUT_UNKNOWN")).assertIsDisplayed()
    }
}
