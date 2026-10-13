package dev.librepocket.agent.ui

import android.os.Bundle
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.agent.ui.settings.SettingsScreen
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointGate
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.agent.ui.setup.MutablePolicy
import dev.librepocket.agent.ui.setup.SetupViewModel
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.keystore.KeyVault
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage F1（#17 NEW Finding 1 P1）Host＋真 VM 持續訂閱整合測試。
 *
 * 舊 `ReadFailure` 被當永久 Loading：全屏 spinner 覆蓋重試入口、無操作能 bump
 * gateTick，形成死鎖。本檔以真 [SetupViewModel]＋真 [StartupGateHost]＋失敗注入
 * DataStore 證明：transient 失敗 → ReadFailure 落定後路由 SETTINGS、重試讀取可見
 * 可點、重試後同一訂閱恢復 Ready(A)；空 store 對照仍走 SETUP（未誤導）。
 */
private class FailReadsDataStore(
    private val delegate: DataStore<Preferences>,
    failReads: Int,
    private val error: IOException = IOException("STRICT_READ_FAILED"),
) : DataStore<Preferences> {
    private val remaining = AtomicInteger(failReads)
    override val data: Flow<Preferences>
        get() = flow {
            if (remaining.getAndDecrement() > 0) throw error
            emitAll(delegate.data)
        }
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        delegate.updateData(transform)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StartupGateReadFailureWiringTest {

    private val tmpDirs = ArrayList<java.io.File>()
    private val scopes = ArrayList<CoroutineScope>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        scopes.forEach { it.cancel() }
        tmpDirs.forEach { it.deleteRecursively() }
    }

    private val noProvider: (dev.librepocket.provider.ProviderConfig, dev.librepocket.provider.KeyProvider) -> LlmProvider =
        { _, _ -> error("no provider needed") as LlmProvider }

    private fun preseed(model: String = "m-old"): EndpointConfig = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = model,
        apiKeyRef = "provider_key/preset:openai",
    )

    private fun newVmBundle(dirName: String, failReads: Int): Triple<SetupViewModel, KeyVault, CoroutineScope> {
        val dir = Files.createTempDirectory(dirName).toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val raw = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "f1-read-failure.preferences_pb") },
        )
        val delegate = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            delegate.putKey("preset:openai", "unit-test-old-key-123456".toCharArray())
            EndpointStore(raw).save(preseed())
        }
        val store = EndpointStore(FailReadsDataStore(raw, failReads))
        val vault: KeyVault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking { vault.putKey("preset:openai", "unit-test-old-key-123456".toCharArray()) }
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        return Triple(vm, vault, scope)
    }

    private class Harness(val vm: SetupViewModel) {
        var retryReadCalls = 0
        var dismissed = 0
        lateinit var nav: NavHostController
        val visited = mutableListOf<String?>()
    }

    @Composable
    private fun Harness.Content() {
        nav = rememberNavController()
        val g by vm.gate.collectAsState()
        val p by vm.startupReconcilePending.collectAsState()
        val f by vm.form.collectAsState()
        val pt by vm.pendingTeardown.collectAsState()
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
        // 生產 MainScreen 同式接線（有效碼＋身份顯示、重試走 reconcile-only）。
        val displayed = effectiveLogoutRecovery(
            f.errorCode?.takeIf { it.startsWith("LOGOUT_") },
            f.logoutErrorSourceJournal,
            pendingTeardown = pt,
            logoutInFlight = f.logoutInFlight,
        )
        StartupGateHost(
            gate = g,
            startupReconcilePending = p,
            errorCode = f.errorCode,
            logoutInFlight = f.logoutInFlight,
            navController = nav,
            appName = "TestApp",
            pendingTeardown = pt,
            logoutErrorSourceJournal = f.logoutErrorSourceJournal,
            chatContent = { padding ->
                Text("CHAT_BODY", modifier = Modifier.padding(padding))
            },
            settingsContent = { padding ->
                SettingsScreen(
                    padding = padding,
                    gate = g,
                    logoutErrorCode = displayed.effectiveCode,
                    logoutRetryJournal = displayed.retryJournal?.format(),
                    logoutInFlight = f.logoutInFlight,
                    onDismissLogoutError = {
                        dismissed++
                        vm.clearLogoutError()
                    },
                    onRetryStartupRead = {
                        retryReadCalls++
                        vm.retryStartupReconcile { o, j -> vm.onStartupReconcile(o, j) }
                    },
                    startupReconcilePending = p,
                )
            },
            setupContent = { padding, _ ->
                Text("SETUP_BODY", modifier = Modifier.padding(padding))
            },
        )
    }

    @get:Rule
    val composeRule = createComposeRule()

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
        composeRule.waitUntil(timeoutMillis = 10_000) {
            h.nav.currentDestination?.route == route
        }
    }

    @Test
    fun readFailureSteersToSettingsAndRetryRecoversReady() {
        // F1 主回歸：valid A → 兩路初始讀各敗一次 → ReadFailure 落定 →
        // SETTINGS 重試可見可點 → 同一訂閱 Ready(A)，且從未訪問 SETUP。
        val (vm, _, _) = newVmBundle("f1-read-failure", failReads = 2)
        val h = Harness(vm)
        render(h)
        // 兩路初始收集各耗一次失敗：gate 首個非 Loading 必為 ReadFailure。
        val first = runBlocking {
            withTimeout(10_000) { vm.gate.first { it !is EndpointGate.Loading } }
        }
        assertTrue("transient failure must surface ReadFailure, got $first", first is EndpointGate.ReadFailure)
        runBlocking {
            withTimeout(10_000) { vm.pendingTeardown.first { it != dev.librepocket.agent.ui.setup.PendingTeardownState.Absent } }
        }
        // 初始 reconcile 落定（NO_OP，第 3 次讀成功），pending 归 false。
        runBlocking {
            withTimeout(10_000) {
                vm.launchPendingDeleteReconcile { o, j -> vm.onStartupReconcile(o, j) }.join()
            }
        }
        awaitRoute(h, Routes.SETTINGS)
        assertEquals(Routes.SETTINGS, currentRoute(h))
        composeRule.runOnIdle { assertTrue(h.visited.none { it == Routes.SETUP }) }
        // spinner 已撤（ReadFailure 非 Loading），重試入口可見可點。
        composeRule.onAllNodesWithTag("startupLoading").assertCountEquals(0)
        composeRule.onNodeWithText("設定讀取失敗，請重試讀取。").assertIsDisplayed()
        composeRule.onNodeWithText("重試讀取").assertIsDisplayed()
        composeRule.onNodeWithText("重試讀取").assertIsEnabled()
        // 重試 → 同一訂閱恢復 Ready(A)，仍在 SETTINGS（無 SETUP 乒乓）。
        composeRule.onNodeWithText("重試讀取").performClick()
        assertEquals(1, h.retryReadCalls)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            vm.gate.value is EndpointGate.Ready
        }
        val ready = vm.gate.value as EndpointGate.Ready
        assertEquals("m-old", ready.config.model)
        composeRule.onNodeWithText("OpenAI").assertIsDisplayed()
        assertEquals(Routes.SETTINGS, currentRoute(h))
        composeRule.runOnIdle { assertTrue(h.visited.none { it == Routes.SETUP }) }
    }

    @Test
    fun emptyStoreStillNavigatesToSetup() {
        // F1 對照：空 store＋健康讀 → NoEndpoint → SETUP（proven-Absent 未被
        // ReadFailure 轉向誤導；重試讀取不出現）。
        val dir = Files.createTempDirectory("f1-empty").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val raw = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "f1-empty.preferences_pb") },
        )
        val store = EndpointStore(raw)
        val vault: KeyVault = EncryptedPrefsVault(InMemoryPrefs())
        val vm = SetupViewModel(
            store = store,
            vaultSource = VaultSource { vault },
            policy = MutablePolicy(Verdict.ALLOW),
            buildProvider = noProvider,
        )
        val h = Harness(vm)
        render(h)
        runBlocking {
            withTimeout(10_000) {
                vm.launchPendingDeleteReconcile { o, j -> vm.onStartupReconcile(o, j) }.join()
            }
        }
        awaitRoute(h, Routes.SETUP)
        assertEquals(Routes.SETUP, currentRoute(h))
        composeRule.onNodeWithText("SETUP_BODY").assertIsDisplayed()
        composeRule.onAllNodesWithText("重試讀取").assertCountEquals(0)
    }
}
