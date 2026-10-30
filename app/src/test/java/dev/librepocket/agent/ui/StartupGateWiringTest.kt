package dev.librepocket.agent.ui

import android.os.Bundle
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.librepocket.agent.ui.settings.SettingsScreen
import dev.librepocket.agent.ui.settings.rememberTranscriptPickers
import dev.librepocket.session.SessionMeta
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointGate
import dev.librepocket.agent.ui.setup.setupErrorText
import dev.librepocket.provider.ProviderProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage B review REQUEST CHANGES：生產接線層測試（R10 gate：Compose Navigation
 * test，任何先後次序皆可達恢復卡）。
 *
 * 背景：`StartupReconcilePendingTest` 的 8 tests 全是「手算 `navLoading` 再調
 * 純函數」——`MainScreen` 的 collect→`navLoading`、守衛傳參＋keys、spinner、
 * drawer／選單接線本身無測試鎖定（mutation 改回 `gateLoading`、`&&`、漏改一處
 * 不會紅）。本檔直測生產單一來源 [StartupGateHost]（與 [MainScreen] 共用的
 * composable，內含兩 `LaunchedEffect` 守衛；測試內不重抄 `LaunchedEffect`），
 * 以 `MutableStateFlow` 驅動 gate／pending／errorCode／logoutInFlight，經
 * `addOnDestinationChangedListener` 記錄導航序列斷言。
 *
 * 實現說明：以 `rememberNavController()` 注入（等價最小實現——不新增
 * `navigation-testing` 測試依賴；`TestNavHostController` 在此僅是控制器載體，
 * 真實 `NavHost` 導航行為由生產 host 擁有，`rememberNavController` 足夠）。
 * `settingsContent` 用真實 [SettingsScreen]（恢復卡文案／重試／關閉皆生產
 * UI），chat／setup 以路由標記文字（非測試對象，僅標記到達）。
 *
 * 現 8 tests 保留作單元層（見 `StartupReconcilePendingTest`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StartupGateWiringTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class Harness {
        val gate = MutableStateFlow<EndpointGate>(EndpointGate.NoEndpoint)
        val pending = MutableStateFlow(true)
        val error = MutableStateFlow<String?>(null)
        val inFlight = MutableStateFlow(false)
        var logoutCalls = 0
        var retryLogoutCalls = 0
        val imported = mutableListOf<Uri>()
        val opened = mutableListOf<String>()
        val viewed = mutableListOf<String>()
        val exported = mutableListOf<Pair<String, Uri>>()
        val currentSession = MutableStateFlow<String?>(null)
        val sessions = MutableStateFlow<List<SessionMeta>>(emptyList())
        val importing = MutableStateFlow(false)
        lateinit var nav: NavHostController
        val visited = mutableListOf<String?>()
    }

    @Composable
    private fun Harness.Content() {
        nav = rememberNavController()
        val g by gate.collectAsState()
        val p by pending.collectAsState()
        val e by error.collectAsState()
        val b by inFlight.collectAsState()
        val session by currentSession.collectAsState()
        val history by sessions.collectAsState()
        val busy by importing.collectAsState()
        val pickers = rememberTranscriptPickers(
            onExportCurrentSession = { id, uri -> exported.add(id to uri) },
            onImportSession = { imported.add(it) },
            onDismissExportState = {}, onDismissImportState = {},
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
            sessions = history,
            currentSessionId = session,
            onNewChat = { currentSession.value = null },
            onImportPickerStart = pickers.launchImport,
            importBusy = pickers.importPickerPending || busy,
            onOpenSession = { opened.add(it); currentSession.value = it },
            onViewTranscript = { viewed.add(it) },
            chatContent = { padding ->
                Text("CHAT_BODY", modifier = Modifier.padding(padding))
            },
            settingsContent = { padding ->
                SettingsScreen(
                    padding = padding,
                    gate = g,
                    currentSessionId = session, pickers = pickers,
                    onLogout = { logoutCalls++ },
                    logoutErrorCode = e,
                    logoutInFlight = b,
                    onDismissLogoutError = { error.value = null },
                    // Stage F：重試清除獨立於全新登出（生產改綁 `retryPendingDelete`）。
                    onRetryLogout = { retryLogoutCalls++ },
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
    fun slowOrder_gateFirstThenCode() {
        // gate 先折疊 NoEndpoint、落碼後到：生產順序（onDone 先落碼、finally 後
        // 落定 pending）復刻——error 先於 pending=false 落下。
        val h = Harness()
        render(h)
        // 落定前：spinner 顯示、SETUP 未訪問（NavHost 尚未組成，無當前路由）。
        composeRule.onNodeWithTag("startupLoading").assertIsDisplayed()
        composeRule.onAllNodesWithText("SETUP_BODY").assertCountEquals(0)
        composeRule.runOnIdle { assertTrue(h.visited.none { it == Routes.SETUP }) }

        h.error.value = "LOGOUT_DELETE_FAILED"
        composeRule.waitForIdle()
        // 仍 pending：守衛不決策，依舊 spinner、依舊無 SETUP。
        composeRule.onNodeWithTag("startupLoading").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(h.visited.none { it == Routes.SETUP }) }

        h.pending.value = false
        awaitRoute(h, Routes.SETTINGS)
        // 終點 SETTINGS、SETUP 從未訪問、恢復卡文案＋重試清除 displayed。
        assertEquals(Routes.SETTINGS, currentRoute(h))
        composeRule.runOnIdle { assertTrue(h.visited.none { it == Routes.SETUP }) }
        composeRule.onNodeWithText(setupErrorText("LOGOUT_DELETE_FAILED")).assertIsDisplayed()
        composeRule.onNodeWithText("重試清除").assertIsDisplayed()
        composeRule.onAllNodesWithText("SETUP_BODY").assertCountEquals(0)
    }

    @Test
    fun fastOrder_codeFirstThenGate() {
        // 落碼先於 gate 折疊：Loading＋碼＋pending → spinner；NoEndpoint＋落定
        // 直達 SETTINGS，無 SETUP 中間態。
        val h = Harness()
        h.gate.value = EndpointGate.Loading
        h.error.value = "LOGOUT_DELETE_FAILED"
        render(h)
        composeRule.onNodeWithTag("startupLoading").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(h.visited.none { it == Routes.SETUP }) }

        h.gate.value = EndpointGate.NoEndpoint
        h.pending.value = false
        awaitRoute(h, Routes.SETTINGS)
        assertEquals(Routes.SETTINGS, currentRoute(h))
        composeRule.runOnIdle { assertTrue(h.visited.none { it == Routes.SETUP }) }
        composeRule.onNodeWithText(setupErrorText("LOGOUT_DELETE_FAILED")).assertIsDisplayed()
        composeRule.onNodeWithText("重試清除").assertIsDisplayed()
    }

    @Test
    fun freshNoJournal_goesSetup() {
        // 無 journal 落定、無碼：fresh 走 SETUP，SETTINGS 未訪問。
        val h = Harness()
        h.pending.value = false
        render(h)
        awaitRoute(h, Routes.SETUP)
        assertEquals(Routes.SETUP, currentRoute(h))
        composeRule.onNodeWithText("SETUP_BODY").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(h.visited.none { it == Routes.SETTINGS }) }
    }

    @Test
    fun spinner_shownWhilePending_goneAfterSettle() {
        val h = Harness()
        render(h)
        composeRule.onNodeWithTag("startupLoading").assertIsDisplayed()
        // 落定／取消（pending=false、無碼）後 spinner 消失，轉入 fresh SETUP。
        h.pending.value = false
        awaitRoute(h, Routes.SETUP)
        composeRule.onAllNodesWithTag("startupLoading").assertCountEquals(0)
        composeRule.onNodeWithText("SETUP_BODY").assertIsDisplayed()
    }

    @Test
    fun menuDisabledWhenFresh() {
        // fresh 態（無碼）：選單鍵 disabled。
        val h = Harness()
        h.pending.value = false
        render(h)
        awaitRoute(h, Routes.SETUP)
        composeRule.onNodeWithContentDescription("選單").assertIsNotEnabled()
    }

    @Test
    fun setupSteersToRecoveryCardOnCodeArrival() {
        // Stage F2 (#18)：SETUP 停留態後到 retained 碼 → 自動轉向 SETTINGS 恢復卡
        // （不再靜止；drawer 手動路徑仍可用，但自動轉向優先）。
        // 經 SETUP 停留態切碼，證明 gate-first 發射序終於恢復卡、無乒乓。
        val h = Harness()
        h.pending.value = false
        render(h)
        awaitRoute(h, Routes.SETUP)
        h.error.value = "LOGOUT_DELETE_FAILED"
        awaitRoute(h, Routes.SETTINGS)
        assertEquals(Routes.SETTINGS, currentRoute(h))
        composeRule.onNodeWithText(setupErrorText("LOGOUT_DELETE_FAILED")).assertIsDisplayed()
        composeRule.onNodeWithText("重試清除").assertIsDisplayed()
        // 重試回調可達（Stage F：獨立重試入口，禁回退全新 `onLogout`）。
        composeRule.onNodeWithText("重試清除").performClick()
        composeRule.runOnIdle {
            assertEquals(1, h.retryLogoutCalls)
            assertEquals(0, h.logoutCalls)
        }
        // 終止在 SETTINGS：無乒乓。
        composeRule.waitForIdle()
        assertEquals(Routes.SETTINGS, currentRoute(h))
    }

    @Test
    fun dismiss_staleGoesToSetupOnceWithoutPingPong() {
        // SETTINGS＋STALE → 點關閉 → 恰一次 SETTINGS→SETUP，idle 後無乒乓。
        val h = Harness()
        h.pending.value = false
        h.error.value = "LOGOUT_STALE"
        render(h)
        awaitRoute(h, Routes.SETTINGS)
        composeRule.onNodeWithText(setupErrorText("LOGOUT_STALE")).assertIsDisplayed()
        composeRule.onNodeWithText("關閉").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(h.visited.none { it == Routes.SETUP }) }

        composeRule.onNodeWithText("關閉").performClick()
        awaitRoute(h, Routes.SETUP)
        composeRule.runOnIdle {
            val seq = h.visited.toList()
            val transitions = seq.zipWithNext().count { (a, b) -> a == Routes.SETTINGS && b == Routes.SETUP }
            assertEquals("exactly one SETTINGS->SETUP, got $seq", 1, transitions)
            assertEquals(1, seq.count { it == Routes.SETUP })
        }
        // idle 後無乒乓：仍在 SETUP、無新增導航事件。
        var settled: List<String?> = emptyList()
        composeRule.runOnIdle { settled = h.visited.toList() }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(settled, h.visited.toList())
            assertEquals(Routes.SETUP, h.nav.currentDestination?.route)
        }
        // dismiss 後為 fresh：恢復卡消失、選單鍵回到 disabled。
        composeRule.onAllNodesWithText("關閉").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("選單").assertIsNotEnabled()
    }

    @Test
    fun inFlightStaysWithoutNavigation() {
        // logoutInFlight 中不決策：CHAT＋碼＋inFlight → 落定後仍無導航（非
        // CHAT 空轉），inFlight 結束才導向 SETTINGS。
        val h = Harness()
        h.pending.value = false
        h.error.value = "LOGOUT_DELETE_FAILED"
        h.inFlight.value = true
        render(h)
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(h.visited.none { it == Routes.SETUP })
            assertTrue(h.visited.none { it == Routes.SETTINGS })
        }
        h.inFlight.value = false
        awaitRoute(h, Routes.SETTINGS)
        assertEquals(Routes.SETTINGS, currentRoute(h))
    }

    @Test
    fun drawerContainsImportJsonlItem() {
        val h = Harness()
        h.pending.value = false
        h.gate.value = EndpointGate.Ready(
            EndpointConfig(
                providerId = "preset:openai",
                presetId = "openai",
                label = "OpenAI",
                baseUrl = "https://api.openai.com/v1",
                protocol = ProviderProtocol.CHAT_COMPLETIONS,
                model = "gpt-4o-mini",
                apiKeyRef = "provider_key/preset:openai",
            ),
        )
        render(h)
        awaitRoute(h, Routes.CHAT)
        composeRule.onNodeWithContentDescription("選單").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("匯入對話 (JSONL)").assertIsDisplayed()
        composeRule.onNodeWithText("匯入會建立新的本機對話副本，不驗證來源或真實性。可從歷史對話唯讀檢視；開啟對話可能建立不含歷史的新副本。").assertIsDisplayed()
    }

    @Test
    fun drawerImportDoesNotNavigateOrOpenSession() {
        val registry = object : ActivityResultRegistry() {
            var request = -1
            override fun <I : Any?, O : Any?> onLaunch(
                requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                options: ActivityOptionsCompat?,
            ) { request = requestCode }
        }
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val h = Harness()
        h.pending.value = false
        h.gate.value = EndpointGate.Ready(
            EndpointConfig("preset:openai", "openai", "OpenAI", "https://api.openai.com/v1",
                ProviderProtocol.CHAT_COMPLETIONS, "gpt-4o-mini", "provider_key/preset:openai"),
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { h.Content() }
        }
        awaitRoute(h, Routes.CHAT)
        composeRule.onNodeWithContentDescription("選單").performClick()
        composeRule.onNodeWithText("匯入對話 (JSONL)").performClick()
        composeRule.onNodeWithContentDescription("選單").performClick()
        composeRule.onNodeWithText("匯入對話 (JSONL)").assertIsNotEnabled()
        val uri = Uri.parse("content://test/import")
        composeRule.runOnIdle { h.importing.value = true; registry.dispatchResult(registry.request, uri) }
        composeRule.onNodeWithText("匯入對話 (JSONL)").assertIsNotEnabled()
        composeRule.runOnIdle {
            assertEquals(listOf(uri), h.imported)
            assertTrue(h.opened.isEmpty())
            assertEquals(Routes.CHAT, h.nav.currentDestination?.route)
            h.importing.value = false
        }
        composeRule.onNodeWithText("匯入對話 (JSONL)").assertIsEnabled()
    }
    @Test
    fun settingsPickerResultSurvivesHostLoadingAndSessionChanges() {
        val registry = object : ActivityResultRegistry() {
            var request = -1
            override fun <I : Any?, O : Any?> onLaunch(
                requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                options: ActivityOptionsCompat?,
            ) { request = requestCode }
        }
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val h = Harness()
        h.pending.value = false
        h.currentSession.value = "original"
        h.sessions.value = listOf(SessionMeta("other", "other conversation", 0, 0, "model"))
        h.gate.value = EndpointGate.Ready(
            EndpointConfig("preset:openai", "openai", "OpenAI", "https://api.openai.com/v1",
                ProviderProtocol.CHAT_COMPLETIONS, "gpt-4o-mini", "provider_key/preset:openai"),
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { h.Content() }
        }
        awaitRoute(h, Routes.CHAT)
        composeRule.runOnIdle { h.nav.navigate(Routes.SETTINGS) }
        composeRule.onNodeWithText("匯出目前對話").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("選單").performClick()
        composeRule.onNodeWithText("新對話").performClick()
        composeRule.onNodeWithContentDescription("選單").performClick()
        composeRule.onNodeWithTag("sessionDrawerList").performScrollToNode(hasText("other conversation"))
        composeRule.onNodeWithText("other conversation").performClick()
        composeRule.runOnIdle { h.pending.value = true }
        composeRule.onNodeWithTag("startupLoading").assertIsDisplayed()
        val uri = Uri.parse("content://test/export-loading")
        composeRule.runOnIdle {
            registry.dispatchResult(registry.request, uri)
            assertEquals(listOf("original" to uri), h.exported)
            assertEquals(listOf("other"), h.opened)
        }
        composeRule.runOnIdle { h.pending.value = false }
        awaitRoute(h, Routes.CHAT)
        composeRule.runOnIdle { h.nav.navigate(Routes.SETTINGS) }
        composeRule.onNodeWithText("匯入對話檔").performScrollTo().performClick()
        composeRule.runOnIdle { h.pending.value = true; h.currentSession.value = null }
        composeRule.onNodeWithTag("startupLoading").assertIsDisplayed()
        val importedUri = Uri.parse("content://test/import-loading")
        composeRule.runOnIdle {
            registry.dispatchResult(registry.request, importedUri)
            assertEquals(listOf(importedUri), h.imported)
            assertEquals(listOf("other"), h.opened)
        }
    }

    @Test
    fun drawerOldHistoryIsReachableOnlyByExplicitClick() {
        val h = Harness()
        h.pending.value = false
        h.gate.value = EndpointGate.Ready(
            EndpointConfig("preset:openai", "openai", "OpenAI", "https://api.openai.com/v1",
                ProviderProtocol.CHAT_COMPLETIONS, "gpt-4o-mini", "provider_key/preset:openai"),
        )
        h.sessions.value = (0..24).map { SessionMeta("id-$it", "history-$it", 0, 0, "model") }
        render(h)
        awaitRoute(h, Routes.CHAT)
        composeRule.onNodeWithContentDescription("選單").performClick()
        composeRule.onNodeWithTag("sessionDrawerList").performScrollToNode(hasText("history-24"))
        composeRule.onNodeWithText("history-24").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("id-24"), h.opened)
            assertEquals(Routes.CHAT, h.nav.currentDestination?.route)
        }
    }

    @Test
    fun drawerReadOnlyEntryDoesNotOpenChat() {
        val h = Harness()
        h.pending.value = false
        h.gate.value = EndpointGate.Ready(
            EndpointConfig("preset:openai", "openai", "OpenAI", "https://api.openai.com/v1",
                ProviderProtocol.CHAT_COMPLETIONS, "gpt-4o-mini", "provider_key/preset:openai"),
        )
        h.sessions.value = listOf(SessionMeta("imported", "imported title", 0, 0, "imported/unknown"))
        render(h)
        awaitRoute(h, Routes.CHAT)
        composeRule.onNodeWithContentDescription("選單").performClick()
        composeRule.onNodeWithTag("sessionDrawerList").performScrollToNode(hasText("imported title"))
        composeRule.onNodeWithContentDescription("唯讀檢視 imported title").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("imported"), h.viewed)
            assertTrue(h.opened.isEmpty())
            assertEquals(Routes.CHAT, h.nav.currentDestination?.route)
        }
    }

}
