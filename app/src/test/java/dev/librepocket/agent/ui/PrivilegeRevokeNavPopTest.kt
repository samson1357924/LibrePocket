package dev.librepocket.agent.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointGate
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.session.SessionMeta
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.librepocket.shell.PrivilegeBridgeState
import dev.librepocket.shell.PrivilegeBridgeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「一鍵收回」NavBackStackEntry-pop 生存整合測試（Re-review #9 / P2，#136-B1）。
 *
 * 生產接線：`MainScreen` 的 `settingsContent` 經 `StartupGateHost` 的
 * `NavHost { composable(Routes.SETTINGS) { settingsContent(padding) } }` 渲染。
 * 舊行為（Re-review #9 回報的缺陷）：[PrivilegeRevokeWiring] 的 `viewModel`
 * 未顯式指定 `viewModelStoreOwner` 時，owner 落在 composition 最近的 StoreOwner——
 * 即 SETTINGS destination 的 NavBackStackEntry（不是宿主 Activity）。抽屜「新對話」
 * （`navigate(Routes.CHAT) { popUpTo(Routes.CHAT) { inclusive = false }; launchSingleTop = true }`）
 * 會 pop 掉 SETTINGS → 該 entry 的 ViewModelStore 被清空 → 撤權 VM 銷毀——
 * 而 revoke() 的 `withContext(NonCancellable)` 仍在跑；重進 Settings 拿到全新
 * （Idle）VM，在途／失敗 banner 全失，使用者可重複進入撤權。
 *
 * 修復後行為（本檔釘住）：owner 經 [LocalContext] 沿 context 鏈解析為宿主
 * ComponentActivity（[findComponentActivity]），與 composition 深度無關；
 * SETTINGS 被 pop 亦不影響 VM 存續。兩個 test 覆蓋失敗→ERROR→重試路徑與
 * 成功→IDLE 路徑；Settings-pop 後重進的斷言（`revokeState=INFLIGHT` 仍顯示、
 * 再點不重入 executor）即 reproduction。
 *
 * Harness 鏡像 `StartupGateWiringTest`（`rememberNavController()` 注入
 * [StartupGateHost]；gate/pending/error/logoutInFlight 為 MutableStateFlow；
 * sessions 空、currentSession null），但 `settingsContent` 以探針 UI 取代真實
 * [dev.librepocket.agent.ui.settings.SettingsScreen]——與 [PrivilegeRevokeWiringTest]
 * 同一探針 idiom，且足以釘住 owner-lift 保證。`viewModelStoreOwner` 刻意**不**傳
 * （測預設解析路徑：context 鏈 → Activity）。
 *
 * executor 為 latch-controlled 假件（[Harness.latch]、[Harness.executorCalls]、
 * [Harness.failure]）：第一次呼叫駐在 IO 執行緒的 `latch.await(10s)`
 * （刻意不駐 Main——viewModelScope 走 Main.immediate，駐 Main 會堵死按鈕重組）；
 * 之後的呼叫（重試）不再駐擋、立即以 [Harness.failure] 回傳。10 秒上限僅防死鎖。
 * `true` = 撤權失敗（與 `executePrivilegeRevoke` 同極性 ⇒ ERROR banner）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivilegeRevokeNavPopTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 已建立的 Harness 清單；tearDown 一律 countDown 其 latch（含斷言失敗中止路徑）。 */
    private val harnesses = mutableListOf<Harness>()

    @After
    fun tearDown() {
        harnesses.forEach { it.latch.countDown() }
    }

    private fun newHarness(): Harness = Harness().also(harnesses::add)

    @Test
    fun revokeInFlight_survivesSettingsPop_thenFailureShowsError_retryReentersIdle() {
        val h = newHarness()
        render(h)
        awaitRoute(h, Routes.CHAT)

        // 進 Settings：探針初始 IDLE。
        composeRule.runOnIdle { h.nav.navigate(Routes.SETTINGS) }
        awaitRoute(h, Routes.SETTINGS)
        composeRule.onNodeWithText("revokeState=IDLE").assertIsDisplayed()

        // 點「一鍵收回」：executor 駐在 latch 上，撤權轉入 INFLIGHT。
        composeRule.onNodeWithTag("revokeProbe").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("revokeState=INFLIGHT").assertIsDisplayed()
        // executor 非同步在 IO 執行緒 incrementAndGet，讀數等待它完成（fail-closed timeout）
        composeRule.waitUntil(5_000) { h.executorCalls.get() == 1 }

        // 抽屜「新對話」：SETTINGS 被 pop 回 CHAT（生產 CHAT→SETTINGS 使用者路徑）。
        composeRule.onNodeWithContentDescription("選單").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("新對話").performClick()
        awaitRoute(h, Routes.CHAT)
        assertEquals("SETTINGS 被 pop 後 executor 不得重入", 1, h.executorCalls.get())

        // 重進 Settings：VM 必須存活（owner 是 Activity，不是 SETTINGS 條目）。
        composeRule.runOnIdle { h.nav.navigate(Routes.SETTINGS) }
        awaitRoute(h, Routes.SETTINGS)
        // 關鍵斷言：INFLIGHT 跨 pop 存活（bug 下此處為全新 VM ⇒ IDLE ⇒ 紅）。
        composeRule.onNodeWithText("revokeState=INFLIGHT").assertIsDisplayed()
        // 再點一次：VM 級 InFlight 守衛對新 composition 依然生效，executor 不重入。
        composeRule.onNodeWithTag("revokeProbe").performClick()
        assertEquals("重進 Settings 後的重複點擊不得重入 executor", 1, h.executorCalls.get())

        // 放行 executor（失敗）→ ERROR banner 對新 composition 可見。
        h.failure.set(true)
        h.latch.countDown()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("revokeState=ERROR").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("revokeState=ERROR").assertIsDisplayed()
        assertEquals(1, h.executorCalls.get())

        // 重試：第二次 executor 立即回傳（failure=false）→ IDLE。
        h.failure.set(false)
        composeRule.onNodeWithTag("revokeProbe").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("revokeState=IDLE").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(2, h.executorCalls.get())
    }

    @Test
    fun revokeInFlight_survivesSettingsPop_successEndsIdle() {
        val h = newHarness() // failure 預設 false = 撤權成功
        render(h)
        awaitRoute(h, Routes.CHAT)

        composeRule.runOnIdle { h.nav.navigate(Routes.SETTINGS) }
        awaitRoute(h, Routes.SETTINGS)
        composeRule.onNodeWithText("revokeState=IDLE").assertIsDisplayed()

        composeRule.onNodeWithTag("revokeProbe").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("revokeState=INFLIGHT").assertIsDisplayed()
        // executor 非同步在 IO 執行緒 incrementAndGet，讀數等待它完成（fail-closed timeout）
        composeRule.waitUntil(5_000) { h.executorCalls.get() == 1 }

        composeRule.onNodeWithContentDescription("選單").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("新對話").performClick()
        awaitRoute(h, Routes.CHAT)
        assertEquals(1, h.executorCalls.get())

        composeRule.runOnIdle { h.nav.navigate(Routes.SETTINGS) }
        awaitRoute(h, Routes.SETTINGS)
        composeRule.onNodeWithText("revokeState=INFLIGHT").assertIsDisplayed()
        composeRule.onNodeWithTag("revokeProbe").performClick()
        assertEquals("重進 Settings 後的重複點擊仍不得重入 executor", 1, h.executorCalls.get())

        h.latch.countDown()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("revokeState=IDLE").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        assertTrue(
            "成功不得出現失敗 banner",
            composeRule.onAllNodesWithText("revokeState=ERROR").fetchSemanticsNodes().isEmpty(),
        )
        assertEquals(1, h.executorCalls.get())
    }

    private fun render(h: Harness) {
        composeRule.setContent { h.Content() }
        composeRule.waitForIdle()
    }

    private fun awaitRoute(h: Harness, route: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            h.nav.currentDestination?.route == route
        }
    }

    private class Harness {
        val gate = MutableStateFlow<EndpointGate>(
            EndpointGate.Ready(
                EndpointConfig(
                    "preset:openai", "openai", "OpenAI", "https://api.openai.com/v1",
                    ProviderProtocol.CHAT_COMPLETIONS, "gpt-4o-mini", "provider_key/preset:openai",
                ),
            ),
        )
        val pending = MutableStateFlow(false)
        val error = MutableStateFlow<String?>(null)
        val inFlight = MutableStateFlow(false)
        val sessions = MutableStateFlow<List<SessionMeta>>(emptyList())
        val currentSession = MutableStateFlow<String?>(null)
        lateinit var nav: NavHostController

        val latch = CountDownLatch(1)
        val executorCalls = AtomicInteger(0)

        /** true = 撤權失敗（與 executePrivilegeRevoke 同極性 ⇒ ERROR banner）。 */
        val failure = AtomicBoolean(false)

        val executor: suspend (PrivilegeBridgeState, PrivilegeBridgeStore) -> Boolean = { _, _ ->
            withContext(Dispatchers.IO) {
                executorCalls.incrementAndGet()
                // 第一次呼叫駐在 latch 上；重試（第二次起）立即回傳，不駐擋。
                if (executorCalls.get() == 1) {
                    latch.await(10, TimeUnit.SECONDS)
                }
                failure.get()
            }
        }
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
            chatContent = { padding ->
                Text("CHAT_BODY", modifier = Modifier.padding(padding))
            },
            settingsContent = { padding ->
                val app = LocalContext.current.applicationContext as Application
                // viewModelStoreOwner 刻意不傳：測預設解析路徑（context 鏈 → Activity）。
                PrivilegeRevokeWiring(
                    app = app,
                    withdraw = {},
                    executor = executor,
                ) { onRevokePrivilege, inFlightState, errorState ->
                    Column(modifier = Modifier.padding(padding)) {
                        Text(
                            "revokeState=" + if (inFlightState) {
                                "INFLIGHT"
                            } else if (errorState) {
                                "ERROR"
                            } else {
                                "IDLE"
                            },
                        )
                        Button(
                            onClick = onRevokePrivilege,
                            modifier = Modifier.testTag("revokeProbe"),
                        ) {
                            Text("一鍵收回")
                        }
                    }
                }
            },
            setupContent = { padding, _ ->
                Text("SETUP_BODY", modifier = Modifier.padding(padding))
            },
        )
    }
}
